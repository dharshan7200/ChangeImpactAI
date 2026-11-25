package com.changeimpact.analysis.service;

import com.changeimpact.diff.model.FileCategory;
import com.changeimpact.diff.model.FileChange;
import com.changeimpact.diff.service.GitDiffService;
import com.changeimpact.parser.service.CodeAnalysisService;
import com.changeimpact.parser.service.CodeAnalysisService.IncrementalPlan;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.lib.Repository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Plans incremental analysis of a commit from the closest already analysed commit of the same repository.
 *
 * <p>A file can be copied instead of parsed when it is unchanged <b>and</b> its symbol resolution cannot be affected
 * by the change. Re-parsed are: every added/modified/renamed Java file, every unchanged file with a dependency on a
 * type declared in a changed or removed file (incl. members and nested types), and every file in a package that gains,
 * loses or changes a file (same-package simple names resolve without imports). Falls back to a full parse when the
 * re-parse set exceeds {@code app.ingestion.incremental-max-fraction} of the files.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class IncrementalAnalysisPlanner {

    private static final Pattern PACKAGE = Pattern.compile("^\\s*package\\s+([\\w.]+)\\s*;", Pattern.MULTILINE);
    private static final int MAX_CANDIDATES = 8;

    private final JdbcTemplate jdbc;
    private final GitDiffService gitDiffService;

    @Value("${app.ingestion.incremental-enabled:true}")
    private boolean enabled = true;

    @Value("${app.ingestion.incremental-max-fraction:0.5}")
    private double maxFraction = 0.5;

    public Optional<IncrementalPlan> plan(Repository git, UUID repositoryId, String commitSha) {
        if (!enabled) return Optional.empty();
        try {
            return doPlan(git, repositoryId, commitSha);
        } catch (Exception e) {
            log.warn("Incremental planning for {} failed, falling back to a full parse: {}", commitSha, e.getMessage());
            return Optional.empty();
        }
    }

    private Optional<IncrementalPlan> doPlan(Repository git, UUID repositoryId, String commitSha) throws Exception {
        // commits analysed by the current analyser version (rows of older versions must not be carried forward),
        // most recent first
        List<String> candidates = jdbc.queryForList("""
                SELECT f.commit_sha FROM source_files f WHERE f.repository_id = ? AND f.commit_sha <> ?
                 GROUP BY f.commit_sha HAVING min(f.analyzer_version) = ? AND max(f.analyzer_version) = ?
                 ORDER BY max(f.created_at) DESC LIMIT ?""",
                String.class, repositoryId, commitSha, CodeAnalysisService.ANALYZER_VERSION,
                CodeAnalysisService.ANALYZER_VERSION, MAX_CANDIDATES);
        String best = null;
        List<FileChange> bestChanges = null;
        for (String candidate : candidates) {
            List<FileChange> javaChanges;
            try {
                javaChanges = gitDiffService.diff(git, candidate, commitSha).stream()
                        .filter(f -> f.category() == FileCategory.JAVA_SOURCE || f.category() == FileCategory.JAVA_TEST).toList();
            } catch (Exception e) {
                continue; // candidate not in this clone (e.g. force-pushed away)
            }
            if (bestChanges == null || javaChanges.size() < bestChanges.size()) {
                best = candidate;
                bestChanges = javaChanges;
            }
        }
        if (best == null) return Optional.empty();
        final String base = best;

        Map<String, String> baseFiles = new HashMap<>(); // relative path → package
        // Java files only: other languages are always parsed in full (they take no part in incremental analysis)
        jdbc.query("SELECT relative_path, package_name FROM source_files WHERE repository_id = ? AND commit_sha = ? AND language = 'java'",
                rs -> { baseFiles.put(rs.getString(1), Objects.toString(rs.getString(2), "")); }, repositoryId, best);

        Set<String> changedNew = new HashSet<>();   // paths in the commit that must be parsed
        Set<String> changedOld = new HashSet<>();   // paths in the base that are changed or gone
        Set<String> packages = new HashSet<>();
        for (FileChange f : bestChanges) {
            if (!"DELETED".equals(f.changeType())) {
                changedNew.add(f.path());
                gitDiffService.readFile(git, commitSha, f.path()).ifPresent(src -> {
                    Matcher m = PACKAGE.matcher(src);
                    packages.add(m.find() ? m.group(1) : "");
                });
            }
            String old = f.oldPath() != null ? f.oldPath() : f.path();
            if (!"ADDED".equals(f.changeType())) {
                changedOld.add(old);
                if (baseFiles.containsKey(old)) packages.add(baseFiles.get(old));
            }
        }

        // types (incl. nested) declared in changed or removed base files
        Set<String> changedTypes = new HashSet<>();
        if (!changedOld.isEmpty()) {
            jdbc.query(con -> {
                var ps = con.prepareStatement("""
                        SELECT s.qualified_name FROM symbols s JOIN source_files f ON f.id = s.source_file_id
                         WHERE s.repository_id = ? AND s.commit_sha = ? AND f.relative_path = ANY (?)
                           AND s.symbol_type IN ('CLASS', 'INTERFACE', 'ENUM', 'RECORD')""");
                ps.setObject(1, repositoryId);
                ps.setString(2, base);
                ps.setArray(3, con.createArrayOf("varchar", changedOld.toArray(String[]::new)));
                return ps;
            }, rs -> { changedTypes.add(rs.getString(1).replace('$', '.')); });
        }

        // unchanged base files that reference a changed type, or share a package with a changed file
        Set<String> affected = new HashSet<>();
        if (!changedTypes.isEmpty()) {
            jdbc.query("""
                    SELECT DISTINCT f.relative_path, d.target_qualified_name FROM dependencies d
                      JOIN symbols s ON s.id = d.source_symbol_id JOIN source_files f ON f.id = s.source_file_id
                     WHERE d.repository_id = ? AND d.commit_sha = ?""", rs -> {
                String path = rs.getString(1);
                if (!affected.contains(path) && referencesType(rs.getString(2), changedTypes)) affected.add(path);
            }, repositoryId, best);
        }
        baseFiles.forEach((path, pkg) -> { if (packages.contains(pkg)) affected.add(path); });

        Set<String> parse = new HashSet<>(changedNew);
        Set<String> copy = new HashSet<>();
        for (String path : baseFiles.keySet()) {
            if (changedOld.contains(path)) continue;
            if (affected.contains(path)) parse.add(path); else copy.add(path);
        }
        int total = parse.size() + copy.size();
        if (total == 0 || parse.size() > maxFraction * total) {
            log.info("Incremental analysis of {} not worthwhile: {} of {} files affected (base {})", commitSha, parse.size(), total, best);
            return Optional.empty();
        }
        log.info("Incremental analysis of {} from {}: {} Java file(s) changed, parse {} / reuse {}",
                commitSha, best, bestChanges.size(), parse.size(), copy.size());
        return Optional.of(new IncrementalPlan(best, parse, copy));
    }

    /** True when the dependency target is, or is a member of, one of the types (or of a type nested in one). */
    static boolean referencesType(String target, Set<String> types) {
        String key = target.replace('$', '.');
        int hash = key.indexOf('#');
        String owner = hash > 0 ? key.substring(0, hash) : key;
        int paren = owner.indexOf('(');
        if (paren > 0) owner = owner.substring(0, paren);
        // the owner itself or any enclosing type: a.b.Outer.Inner → a.b.Outer → a.b (stops at packages, harmless)
        for (String candidate = owner; !candidate.isEmpty(); ) {
            if (types.contains(candidate)) return true;
            int dot = candidate.lastIndexOf('.');
            if (dot < 0) break;
            candidate = candidate.substring(0, dot);
        }
        return false;
    }
}

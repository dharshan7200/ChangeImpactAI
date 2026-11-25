package com.changeimpact.parser.service;

import com.changeimpact.parser.model.ExtractedDependency;
import com.changeimpact.parser.model.ExtractedSymbol;
import com.changeimpact.parser.model.ParsedSourceFile;
import com.changeimpact.parser.model.ResolutionStatus;
import com.changeimpact.parser.model.SymbolType;
import com.changeimpact.repository.entity.Repository;
import com.changeimpact.symbols.entity.SourceFile;
import com.changeimpact.symbols.entity.Symbol;
import com.changeimpact.symbols.entity.SymbolDependency;
import com.changeimpact.symbols.repository.SourceFileRepository;
import com.changeimpact.symbols.repository.SymbolDependencyRepository;
import com.changeimpact.symbols.repository.SymbolRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.Builder;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Path;
import java.util.*;

/**
 * Orchestrates AST parsing, symbol persistence, and deterministic dependency linking for a repository at a specific
 * commit — either for all files, or incrementally: files that are unchanged since an already analysed base commit and
 * not affected by the changed files are copied, the others are parsed. The type solver always sees every source file
 * of the commit, and all dependencies are linked against the commit's complete symbol set, so both modes produce the
 * same rows (verified by {@code IncrementalAnalysisIntegrationTest} and on real repositories).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CodeAnalysisService {

    private final com.changeimpact.parser.language.LanguageRegistry languages;
    private final SourceFileRepository sourceFileRepository;
    private final SymbolRepository symbolRepository;
    private final SymbolDependencyRepository symbolDependencyRepository;
    private final JdbcTemplate jdbc;
    private final com.changeimpact.parser.libraries.LibraryResolver libraryResolver;

    @PersistenceContext
    private EntityManager entityManager;

    @Getter
    @Builder
    public static class AnalysisStats {
        private final int parsedFiles;
        private final int totalSymbols;
        private final int totalDependencies;
        private final int unresolvedDependencies;
    }

    /** Files to parse and files to copy (unchanged and unaffected) from an analysed base commit. */
    public record IncrementalPlan(String baseCommit, Set<String> parse, Set<String> copy) {}

    /**
     * Version of the symbol and dependency extraction, stored with every analysed commit. Increase it whenever a change
     * alters the rows produced for the same source (parser patches, resolution rules, symbol naming): incremental
     * analysis only copies rows from commits of the current version. 1 = Phase 6 (JavaParser overload, array and
     * type-parameter fixes; first-declaration-wins linking).
     */
    public static final int ANALYZER_VERSION = 1;

    @Transactional
    public AnalysisStats analyzeRepository(Repository repository, String commitSha, Path workspaceRoot) {
        return analyze(repository, commitSha, workspaceRoot, null);
    }

    /** Parses all Java files ({@code plan == null}) or only the planned ones, and persists the commit's analysis. */
    @Transactional
    public AnalysisStats analyze(Repository repository, String commitSha, Path workspaceRoot, IncrementalPlan plan) {
        log.info("Starting static code intelligence analysis for repo: {} [commit: {}]{}", repository.getFullName(), commitSha,
                plan == null ? "" : " incrementally from " + plan.baseCommit() + " (parse " + plan.parse().size()
                        + ", reuse " + plan.copy().size() + " files)");

        // every enabled language (Java first); the incremental plan only covers languages that support it, the
        // others are always parsed in full
        // Library classpath of this commit (Java): the resolution of every file depends on it, so rows of a base commit
        // parsed with a different classpath must not be reused.
        var classpath = languages.languages().contains("java") ? libraryResolver.resolve(workspaceRoot) : null;
        if (classpath != null) {
            recordLibraries(repository.getId(), commitSha, classpath);
            if (plan != null && !classpath.fingerprint().equals(libraryFingerprint(repository.getId(), plan.baseCommit()))) {
                log.info("Library classpath differs from base commit {}: full analysis instead of incremental", plan.baseCommit());
                plan = null;
            }
        }
        List<Path> libraryJars = classpath != null && classpath.usable() ? classpath.jars() : List.of();

        Map<String, ParsedSourceFile> parsedFilesMap = new TreeMap<>(); // path order: see "first declaration wins"
        for (com.changeimpact.parser.language.LanguageParser language : languages.parsers()) {
            try (var session = language.open(workspaceRoot, libraryJars)) {
                for (Path file : session.sourceFiles()) {
                    if (plan != null && language.incremental() && !plan.parse().contains(relative(workspaceRoot, file))) {
                        continue;
                    }
                    session.parse(file).ifPresent(parsed -> parsedFilesMap.put(parsed.getRelativePath(), parsed));
                }
            }
        }

        // 0. Incremental: copy the unaffected files' source files, symbols and outgoing dependencies
        int copiedFiles = plan == null || plan.copy().isEmpty() ? 0
                : copyFromBase(repository.getId(), plan.baseCommit(), commitSha, plan.copy());

        // 1. Persist SourceFile records
        Map<String, SourceFile> savedSourceFiles = new HashMap<>();
        for (ParsedSourceFile parsed : parsedFilesMap.values()) {
            SourceFile sourceFile = SourceFile.builder()
                    .repository(repository)
                    .commitSha(commitSha)
                    .relativePath(parsed.getRelativePath())
                    .packageName(parsed.getPackageName())
                    .linesOfCode(parsed.getLinesOfCode())
                    .sizeBytes(parsed.getSizeBytes())
                    .isTest(parsed.isTest())
                    .analyzerVersion(ANALYZER_VERSION)
                    .language(parsed.getLanguage())
                    .build();
            sourceFile = sourceFileRepository.save(sourceFile);
            savedSourceFiles.put(parsed.getRelativePath(), sourceFile);
        }

        // 2. Persist primary symbols (classes, interfaces, records, enums)
        // Several declarations can share a qualified name (constant-specific bodies of an enum overriding the same
        // method, local classes, the same type in two modules). The first declaration in (path, line) order wins as
        // dependency source and as link target, in the full and in the incremental path alike.
        Map<String, Symbol> savedSymbolsByQualifiedName = new HashMap<>();
        List<Symbol> savedSymbols = new ArrayList<>();
        List<ExtractedSymbol> deferredMemberSymbols = new ArrayList<>();

        for (ParsedSourceFile parsed : parsedFilesMap.values()) {
            SourceFile sourceFile = savedSourceFiles.get(parsed.getRelativePath());

            for (ExtractedSymbol sym : parsed.getSymbols()) {
                if (isTopLevelType(sym.getSymbolType())) {
                    Symbol symbolEntity = toSymbolEntity(repository, commitSha, sourceFile, sym, null);
                    symbolEntity = symbolRepository.save(symbolEntity);
                    savedSymbolsByQualifiedName.putIfAbsent(sym.getQualifiedName(), symbolEntity);
                    savedSymbols.add(symbolEntity);
                } else {
                    deferredMemberSymbols.add(sym);
                }
            }
        }

        // 3. Persist child symbols (methods, constructors, fields) linking parent_symbol_id
        for (ExtractedSymbol sym : deferredMemberSymbols) {
            Symbol parentSymbol = savedSymbolsByQualifiedName.get(sym.getParentQualifiedName());
            SourceFile sourceFile = parentSymbol != null ? parentSymbol.getSourceFile() : null;

            if (sourceFile != null) {
                Symbol symbolEntity = toSymbolEntity(repository, commitSha, sourceFile, sym, parentSymbol);
                symbolEntity = symbolRepository.save(symbolEntity);
                savedSymbolsByQualifiedName.putIfAbsent(sym.getQualifiedName(), symbolEntity);
                savedSymbols.add(symbolEntity);
            }
        }

        // 4. Persist directed dependencies, linking each target to an internal symbol where one exists.
        //    Lookup keys normalise nested-type separators ('$' in declared names, '.' in resolved names).
        //    With a plan, copied symbols are linkable targets too (JPA references, nothing is loaded).
        Map<String, Symbol> symbolsByKey = new HashMap<>();
        if (copiedFiles > 0) {
            // copied and parsed symbols of this commit, in the same order as below (COLLATE "C" = Java String order)
            entityManager.flush();
            jdbc.query("""
                    SELECT s.id, s.qualified_name FROM symbols s JOIN source_files f ON f.id = s.source_file_id
                     WHERE s.repository_id = ? AND s.commit_sha = ?
                     ORDER BY f.relative_path COLLATE "C", s.start_line NULLS LAST, s.start_column NULLS LAST,
                              s.qualified_name COLLATE "C"
                    """,
                    rs -> {
                        symbolsByKey.putIfAbsent(lookupKey(rs.getString(2)),
                                entityManager.getReference(Symbol.class, rs.getObject(1, UUID.class)));
                    },
                    repository.getId(), commitSha);
        } else {
            savedSymbols.stream()
                    .sorted(Comparator.comparing((Symbol sym) -> sym.getSourceFile().getRelativePath())
                            .thenComparing(Symbol::getStartLine, Comparator.nullsLast(Comparator.naturalOrder()))
                            .thenComparing(Symbol::getStartColumn, Comparator.nullsLast(Comparator.naturalOrder()))
                            .thenComparing(Symbol::getQualifiedName))
                    .forEach(sym -> symbolsByKey.putIfAbsent(lookupKey(sym.getQualifiedName()), sym));
        }

        int unresolvedCount = 0;
        List<SymbolDependency> dependenciesToSave = new ArrayList<>();

        for (ParsedSourceFile parsed : parsedFilesMap.values()) {
            for (ExtractedDependency dep : parsed.getDependencies()) {
                Symbol sourceSymbol = savedSymbolsByQualifiedName.get(dep.getSourceQualifiedName());
                if (sourceSymbol == null) {
                    continue; // Source was not captured (e.g. member of an anonymous/local class)
                }

                TargetMatch match = matchTarget(dep, symbolsByKey);
                if (match.target() == null && isDatabaseObject(dep.getTargetQualifiedName())) {
                    continue; // a table named in code that the repository's SQL does not define (or SQL not analysed)
                }
                if (match.target() == null) {
                    unresolvedCount++;
                }

                dependenciesToSave.add(SymbolDependency.builder()
                        .repository(repository)
                        .commitSha(commitSha)
                        .sourceSymbol(sourceSymbol)
                        .targetSymbol(match.target())
                        .targetQualifiedName(truncate(dep.getTargetQualifiedName(), 1024))
                        .dependencyType(dep.getDependencyType())
                        .isExternal(match.target() == null)
                        .sourceLocationLine(dep.getSourceLine())
                        .resolutionStatus(match.status())
                        .build());
            }
        }

        symbolDependencyRepository.saveAll(dependenciesToSave);
        int copiedDependencies = 0;
        if (copiedFiles > 0) {
            entityManager.flush();
            int[] relinked = relinkCopiedDependencies(symbolsByKey);
            int dropped = jdbc.update("DELETE FROM dependencies d USING cia_copied_deps c WHERE c.id = d.id"
                    + " AND d.target_symbol_id IS NULL AND d.target_qualified_name LIKE ?", DATABASE_PREFIX + "%");
            copiedDependencies = relinked[0] - dropped;
            unresolvedCount += relinked[1] - dropped;
        }

        int totalSymbols = copiedFiles > 0
                ? (int) symbolRepository.countByRepositoryIdAndCommitSha(repository.getId(), commitSha)
                : savedSymbolsByQualifiedName.size();
        log.info("Analysis complete for repo: {} -> Files: {} parsed + {} reused, Symbols: {}, Dependencies: {} (Unresolved: {})",
                repository.getFullName(), savedSourceFiles.size(), copiedFiles, totalSymbols,
                dependenciesToSave.size() + copiedDependencies, unresolvedCount);

        recordLanguages(repository.getId(), commitSha);
        com.changeimpact.common.metrics.PlatformMetrics.parse(copiedFiles > 0 ? "incremental" : "full",
                savedSourceFiles.size(), copiedFiles);
        return AnalysisStats.builder()
                .parsedFiles(savedSourceFiles.size() + copiedFiles)
                .totalSymbols(totalSymbols)
                .totalDependencies(dependenciesToSave.size() + copiedDependencies)
                .unresolvedDependencies(unresolvedCount)
                .build();
    }

    /** Which languages this commit was analysed for (see CommitAnalysisService.ensureAnalyzed). */
    private void recordLanguages(UUID repositoryId, String commitSha) {
        jdbc.update(con -> {
            var ps = con.prepareStatement("""
                    INSERT INTO analysed_commit_languages (repository_id, commit_sha, languages) VALUES (?, ?, ?)
                    ON CONFLICT (repository_id, commit_sha) DO UPDATE SET languages = EXCLUDED.languages, analysed_at = NOW()""");
            ps.setObject(1, repositoryId);
            ps.setString(2, commitSha);
            ps.setArray(3, con.createArrayOf("varchar", languages.versionedLanguages().toArray(String[]::new)));
            return ps;
        });
    }

    /** Stores which library classpath a commit was parsed with. */
    private void recordLibraries(UUID repositoryId, String commitSha, com.changeimpact.parser.libraries.LibraryResolver.Classpath c) {
        String artifacts;
        try {
            artifacts = tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(c.artifacts());
        } catch (Exception e) {
            artifacts = "[]";
        }
        jdbc.update("""
                INSERT INTO commit_libraries (repository_id, commit_sha, status, build_system, fingerprint, jar_count, total_bytes,
                                              artifacts, message, resolve_ms)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?)
                ON CONFLICT (repository_id, commit_sha) DO UPDATE SET status = EXCLUDED.status, build_system = EXCLUDED.build_system,
                    fingerprint = EXCLUDED.fingerprint, jar_count = EXCLUDED.jar_count, total_bytes = EXCLUDED.total_bytes,
                    artifacts = EXCLUDED.artifacts, message = EXCLUDED.message, resolve_ms = EXCLUDED.resolve_ms, resolved_at = NOW()""",
                repositoryId, commitSha, c.status().name(), c.buildSystem(), c.fingerprint(), c.jars().size(), c.totalBytes(),
                artifacts, c.message(), c.millis());
        log.info("Libraries for {}: {} {} ({} jars{})", commitSha.substring(0, Math.min(7, commitSha.length())), c.status(),
                c.buildSystem() == null ? "" : c.buildSystem(), c.jars().size(), c.message() == null ? "" : "; " + c.message());
    }

    /** True when library resolution is on and failed for this commit last time (worth retrying on a re-index). */
    public boolean libraryRetryWanted(UUID repositoryId, String commitSha) {
        return libraryResolver.enabled() && "FAILED".equals(jdbc.query(
                "SELECT status FROM commit_libraries WHERE repository_id = ? AND commit_sha = ?",
                rs -> rs.next() ? rs.getString(1) : null, repositoryId, commitSha));
    }

    /** Library classpath record of a commit for display (status, build system, jar count, message), or null. */
    public Map<String, Object> libraries(UUID repositoryId, String commitSha) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT status, build_system AS \"buildSystem\", jar_count AS \"jarCount\", total_bytes AS \"totalBytes\", message,"
                + " resolve_ms AS \"resolveMs\", resolved_at AS \"resolvedAt\" FROM commit_libraries WHERE repository_id = ? AND commit_sha = ?",
                repositoryId, commitSha);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** Fingerprint of the library classpath a commit was parsed with ({@code legacy} for commits analysed before it was recorded). */
    public String libraryFingerprint(UUID repositoryId, String commitSha) {
        List<String> f = jdbc.queryForList("SELECT fingerprint FROM commit_libraries WHERE repository_id = ? AND commit_sha = ?",
                String.class, repositoryId, commitSha);
        return f.isEmpty() ? "legacy" : f.get(0);
    }

    /** Languages (with parser versions) a commit was analysed for; commits analysed before languages were recorded count as Java only. */
    public Set<String> analysedLanguages(UUID repositoryId, String commitSha) {
        List<String[]> rows = jdbc.query("SELECT languages FROM analysed_commit_languages WHERE repository_id = ? AND commit_sha = ?",
                (rs, i) -> (String[]) rs.getArray(1).getArray(), repositoryId, commitSha);
        if (rows.isEmpty()) return Set.of("java@1");
        Set<String> out = new HashSet<>();
        for (String t : rows.get(0)) out.add(com.changeimpact.parser.language.LanguageRegistry.normalise(t));
        return out;
    }

    /** Source files per enabled language under {@code root} (scan only, nothing is parsed). */
    public Map<String, Integer> sourceFileCounts(Path root) {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (var language : languages.parsers()) {
            try (var session = language.open(root)) {
                out.put(language.language(), session.sourceFiles().size());
            }
        }
        return out;
    }

    /** Enabled languages with parser versions ({@code java@1}, {@code python@2}). */
    public List<String> enabledLanguages() {
        return languages.versionedLanguages();
    }

    /** Copies source files, symbols (with parents) and outgoing dependencies of {@code files} from base to commit. */
    private int copyFromBase(UUID repositoryId, String baseCommit, String commitSha, Set<String> files) {
        String[] paths = files.toArray(String[]::new);
        jdbc.execute("CREATE TEMP TABLE IF NOT EXISTS cia_map_sf (old_id uuid PRIMARY KEY, new_id uuid) ON COMMIT DROP");
        jdbc.execute("CREATE TEMP TABLE IF NOT EXISTS cia_map_sym (old_id uuid PRIMARY KEY, new_id uuid) ON COMMIT DROP");
        jdbc.execute("CREATE TEMP TABLE IF NOT EXISTS cia_copied_deps (id uuid PRIMARY KEY) ON COMMIT DROP");
        jdbc.execute("TRUNCATE cia_map_sf, cia_map_sym, cia_copied_deps");
        int n = jdbc.update(con -> {
            var ps = con.prepareStatement("INSERT INTO cia_map_sf SELECT id, gen_random_uuid() FROM source_files"
                    + " WHERE repository_id = ? AND commit_sha = ? AND relative_path = ANY (?)");
            ps.setObject(1, repositoryId);
            ps.setString(2, baseCommit);
            ps.setArray(3, con.createArrayOf("varchar", paths));
            return ps;
        });
        jdbc.update("INSERT INTO source_files (id, repository_id, commit_sha, relative_path, package_name, lines_of_code,"
                + " size_bytes, is_test, analyzer_version, language, created_at)"
                + " SELECT m.new_id, f.repository_id, ?, f.relative_path, f.package_name, f.lines_of_code, f.size_bytes, f.is_test,"
                + " f.analyzer_version, f.language, now()"
                + " FROM source_files f JOIN cia_map_sf m ON m.old_id = f.id", commitSha);
        jdbc.update("INSERT INTO cia_map_sym SELECT s.id, gen_random_uuid() FROM symbols s JOIN cia_map_sf m ON m.old_id = s.source_file_id");
        jdbc.update("INSERT INTO symbols (id, repository_id, source_file_id, commit_sha, symbol_type, qualified_name, simple_name,"
                + " parent_symbol_id, return_type, parameters, visibility, is_static, is_abstract, is_final, annotations,"
                + " start_line, end_line, start_column, end_column, resolution_status, created_at, content_hash, annotation_text, complexity)"
                + " SELECT ms.new_id, s.repository_id, mf.new_id, ?, s.symbol_type, s.qualified_name, s.simple_name,"
                + " mp.new_id, s.return_type, s.parameters, s.visibility, s.is_static, s.is_abstract, s.is_final, s.annotations,"
                + " s.start_line, s.end_line, s.start_column, s.end_column, s.resolution_status, now(), s.content_hash, s.annotation_text, s.complexity"
                + " FROM symbols s JOIN cia_map_sym ms ON ms.old_id = s.id"
                + " JOIN cia_map_sf mf ON mf.old_id = s.source_file_id"
                + " LEFT JOIN cia_map_sym mp ON mp.old_id = s.parent_symbol_id", commitSha);
        // the extractor's own verdict is RESOLVED or UNRESOLVED; PARTIAL/EXTERNAL are decided again when re-linking
        jdbc.update("WITH ins AS ("
                + " INSERT INTO dependencies (id, repository_id, commit_sha, source_symbol_id, target_symbol_id, target_qualified_name,"
                + " dependency_type, is_external, source_location_line, resolution_status, created_at)"
                + " SELECT gen_random_uuid(), d.repository_id, ?, ms.new_id, NULL, d.target_qualified_name, d.dependency_type,"
                + " TRUE, d.source_location_line, CASE WHEN d.resolution_status = 'UNRESOLVED' THEN 'UNRESOLVED' ELSE 'RESOLVED' END, now()"
                + " FROM dependencies d JOIN cia_map_sym ms ON ms.old_id = d.source_symbol_id"
                + " RETURNING id)"
                + " INSERT INTO cia_copied_deps SELECT id FROM ins", commitSha);
        return n;
    }

    /** Links copied dependencies exactly like parsed ones; returns {copied, unlinked}. */
    private int[] relinkCopiedDependencies(Map<String, Symbol> symbolsByKey) {
        List<Object[]> updates = new ArrayList<>();
        int[] unlinked = {0};
        jdbc.query("SELECT d.id, d.target_qualified_name, d.resolution_status FROM dependencies d"
                + " JOIN cia_copied_deps c ON c.id = d.id", rs -> {
            TargetMatch m = matchTarget(ResolutionStatus.valueOf(rs.getString(3)), rs.getString(2), symbolsByKey);
            if (m.target() == null) unlinked[0]++;
            updates.add(new Object[]{m.target() == null ? null : m.target().getId(), m.status().name(), m.target() == null,
                    rs.getObject(1, UUID.class)});
        });
        jdbc.batchUpdate("UPDATE dependencies SET target_symbol_id = ?, resolution_status = ?, is_external = ? WHERE id = ?", updates);
        return new int[]{updates.size(), unlinked[0]};
    }

    private static String relative(Path root, Path file) {
        return root.relativize(file).toString().replace('\\', '/');
    }

    /** Result of linking a dependency target to an internal symbol. */
    record TargetMatch(Symbol target, ResolutionStatus status) {}

    /**
     * Links a dependency to an internal symbol and assigns an honest resolution status:
     * <ul>
     *   <li>RESOLVED   – the solver resolved it and the exact internal symbol exists</li>
     *   <li>PARTIAL    – resolved to an internal type, but the exact member was not found
     *                    (e.g. Lombok-generated or implicit default constructor); linked to the type</li>
     *   <li>EXTERNAL   – resolved, but declared outside the repository (JDK, libraries)</li>
     *   <li>UNRESOLVED – the solver could not resolve it; never linked to a symbol</li>
     * </ul>
     */
    static TargetMatch matchTarget(ExtractedDependency dep, Map<String, Symbol> symbolsByKey) {
        return matchTarget(dep.getResolutionStatus(), dep.getTargetQualifiedName(), symbolsByKey);
    }

    static TargetMatch matchTarget(ResolutionStatus extracted, String targetQualifiedName, Map<String, Symbol> symbolsByKey) {
        if (extracted == ResolutionStatus.UNRESOLVED) {
            return new TargetMatch(null, ResolutionStatus.UNRESOLVED);
        }
        String key = lookupKey(targetQualifiedName);
        Symbol exact = symbolsByKey.get(key);
        if (exact != null) {
            return new TargetMatch(exact, extracted);
        }
        int hash = key.indexOf('#');
        if (hash > 0) {
            Symbol owner = symbolsByKey.get(key.substring(0, hash));
            if (owner != null) {
                return new TargetMatch(owner, ResolutionStatus.PARTIAL);
            }
        }
        return new TargetMatch(null, ResolutionStatus.EXTERNAL);
    }

    private static final String DATABASE_PREFIX = com.changeimpact.parser.language.ts.EmbeddedSql.PREFIX;

    /**
     * Links to database objects ({@code db:owners}) are kept only when the repository's SQL defines the object: they
     * come from SQL found in strings, ORM mappings and naming conventions, so an unmatched one says nothing certain.
     */
    static boolean isDatabaseObject(String targetQualifiedName) {
        return targetQualifiedName != null && targetQualifiedName.startsWith(DATABASE_PREFIX);
    }

    static String lookupKey(String qualifiedName) {
        return qualifiedName.replace('$', '.');
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }

    private boolean isTopLevelType(SymbolType type) {
        return type.isTypeLike();
    }

    private Symbol toSymbolEntity(Repository repository, String commitSha, SourceFile sourceFile,
                                  ExtractedSymbol sym, Symbol parent) {
        return Symbol.builder()
                .repository(repository)
                .sourceFile(sourceFile)
                .commitSha(commitSha)
                .symbolType(sym.getSymbolType())
                .qualifiedName(sym.getQualifiedName())
                .simpleName(sym.getSimpleName())
                .parentSymbol(parent)
                .returnType(sym.getReturnType())
                .parameters(sym.getParameters())
                .visibility(sym.getVisibility())
                .isStatic(sym.isStatic())
                .isAbstract(sym.isAbstract())
                .isFinal(sym.isFinal())
                .annotations(sym.getAnnotations() != null ? sym.getAnnotations().toArray(new String[0]) : null)
                .startLine(sym.getStartLine())
                .endLine(sym.getEndLine())
                .startColumn(sym.getStartColumn())
                .endColumn(sym.getEndColumn())
                .resolutionStatus(sym.getResolutionStatus())
                .contentHash(sym.getContentHash())
                .annotationText(sym.getAnnotationText())
                .complexity(sym.getComplexity())
                .build();
    }
}

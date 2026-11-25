package com.changeimpact.symbols.browser;

import com.changeimpact.common.exception.BusinessException;
import com.changeimpact.common.exception.ResourceNotFoundException;
import com.changeimpact.ingestion.workspace.WorkspaceManager;
import com.changeimpact.repository.entity.Repository;
import lombok.RequiredArgsConstructor;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;

/**
 * Read-only code browser: the analysed source files of a commit, their text (read from the Git object database at
 * that commit — no working-tree or filesystem path is involved), their symbols and every reference the analysis
 * recorded, with the resolved target's file and line for go-to-definition. Only files the analysis knows
 * ({@code source_files} of that commit) can be read. The platform never writes to a repository.
 */
@Service
@RequiredArgsConstructor
public class SourceBrowserService {

    public static final int MAX_FILE_BYTES = 1024 * 1024;

    private final JdbcTemplate jdbc;
    private final WorkspaceManager workspaces;

    public record FileEntry(String path, String language, int linesOfCode, boolean test) {}

    public record FileList(String commitSha, List<FileEntry> files) {}

    public record SymbolEntry(UUID id, String qualifiedName, String simpleName, String symbolType, int startLine, int endLine,
                              int dependents) {}

    /** A reference on {@code line}: resolved in-repository targets carry their file and line. */
    public record Reference(int line, UUID sourceSymbolId, String targetName, String status, String dependencyType,
                            UUID targetSymbolId, String targetPath, Integer targetLine) {}

    public record FileView(String commitSha, String path, String language, String content, int lines,
                           List<SymbolEntry> symbols, List<Reference> references) {}

    public FileList files(Repository repo, String commit) {
        String sha = commit(repo, commit);
        List<FileEntry> files = jdbc.query("""
                SELECT relative_path, language, lines_of_code, is_test FROM source_files
                 WHERE repository_id = ? AND commit_sha = ? ORDER BY relative_path""",
                (rs, i) -> new FileEntry(rs.getString(1), rs.getString(2), rs.getInt(3), rs.getBoolean(4)), repo.getId(), sha);
        return new FileList(sha, files);
    }

    public FileView file(Repository repo, String commit, String path) {
        String sha = commit(repo, commit);
        if (path == null || path.isBlank() || path.length() > 1024) throw new BusinessException("INVALID_PATH", "path is required");
        List<Map<String, Object>> sf = jdbc.queryForList(
                "SELECT id, language FROM source_files WHERE repository_id = ? AND commit_sha = ? AND relative_path = ?",
                repo.getId(), sha, path);
        if (sf.isEmpty()) throw new ResourceNotFoundException("SourceFile", path);
        UUID fileId = (UUID) sf.get(0).get("id");
        String content = read(repo, sha, path);

        List<SymbolEntry> symbols = jdbc.query("""
                SELECT s.id, s.qualified_name, s.simple_name, s.symbol_type, s.start_line, s.end_line,
                       (SELECT count(DISTINCT d.source_symbol_id) FROM dependencies d WHERE d.target_symbol_id = s.id)
                  FROM symbols s WHERE s.source_file_id = ? ORDER BY s.start_line, s.qualified_name""",
                (rs, i) -> new SymbolEntry(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getInt(5), rs.getInt(6), rs.getInt(7)), fileId);
        List<Reference> refs = jdbc.query("""
                SELECT d.source_location_line, d.source_symbol_id, COALESCE(t.qualified_name, d.target_qualified_name), d.resolution_status,
                       d.dependency_type, t.id, tf.relative_path, t.start_line
                  FROM dependencies d
                  JOIN symbols s ON s.id = d.source_symbol_id
                  LEFT JOIN symbols t ON t.id = d.target_symbol_id
                  LEFT JOIN source_files tf ON tf.id = t.source_file_id
                 WHERE s.source_file_id = ? AND d.source_location_line > 0
                 ORDER BY d.source_location_line, 3""",
                (rs, i) -> new Reference(rs.getInt(1), rs.getObject(2, UUID.class), rs.getString(3), rs.getString(4), rs.getString(5),
                        rs.getObject(6, UUID.class), rs.getString(7), (Integer) rs.getObject(8)), fileId);
        return new FileView(sha, path, (String) sf.get(0).get("language"), content, (int) content.lines().count(), symbols, refs);
    }

    /** The requested analysed commit, or the last indexed one. */
    private String commit(Repository repo, String commit) {
        String sha = commit == null || commit.isBlank() ? repo.getLastIndexedCommit() : commit.strip();
        if (sha == null) throw new BusinessException("NOT_INDEXED", "Index the repository first");
        if (!sha.matches("[0-9a-fA-F]{7,64}")) throw new BusinessException("INVALID_COMMIT", "commit must be a commit SHA");
        List<String> full = jdbc.queryForList("SELECT DISTINCT commit_sha FROM source_files WHERE repository_id = ? AND commit_sha LIKE ? LIMIT 2",
                String.class, repo.getId(), sha.toLowerCase(Locale.ROOT) + "%");
        if (full.size() != 1) throw new BusinessException("COMMIT_NOT_ANALYSED", "Commit " + sha + " has not been analysed");
        return full.get(0);
    }

    private String read(Repository repo, String sha, String path) {
        var dir = workspaces.getWorkspacePath(repo.getId());
        if (!Files.isDirectory(dir.resolve(".git")) && !Files.isRegularFile(dir.resolve("HEAD"))) {
            throw new BusinessException("SOURCE_UNAVAILABLE", "The repository's Git workspace is not available; re-index it");
        }
        try (Git git = Git.open(dir.toFile()); RevWalk walk = new RevWalk(git.getRepository())) {
            RevCommit c = walk.parseCommit(ObjectId.fromString(sha));
            try (TreeWalk tw = TreeWalk.forPath(git.getRepository(), path, c.getTree())) {
                if (tw == null) throw new ResourceNotFoundException("SourceFile", path);
                ObjectLoader loader = git.getRepository().open(tw.getObjectId(0));
                if (loader.getSize() > MAX_FILE_BYTES) throw new BusinessException("FILE_TOO_LARGE", "File larger than 1 MB");
                byte[] bytes = loader.getBytes();
                for (int i = 0; i < Math.min(bytes.length, 8000); i++) {
                    if (bytes[i] == 0) throw new BusinessException("FILE_NOT_TEXT", "Binary file");
                }
                return new String(bytes, StandardCharsets.UTF_8);
            }
        } catch (BusinessException | ResourceNotFoundException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException("SOURCE_UNAVAILABLE", "Could not read " + path + " at " + sha.substring(0, 7) + ": " + e.getMessage());
        }
    }
}

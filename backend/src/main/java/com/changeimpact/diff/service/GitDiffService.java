package com.changeimpact.diff.service;

import com.changeimpact.common.exception.BusinessException;
import com.changeimpact.diff.model.CommitRange;
import com.changeimpact.diff.model.FileCategory;
import com.changeimpact.diff.model.FileChange;
import com.changeimpact.diff.model.Hunk;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.diff.Edit;
import org.eclipse.jgit.diff.RawTextComparator;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevTree;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.revwalk.filter.RevFilter;
import org.eclipse.jgit.treewalk.AbstractTreeIterator;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.treewalk.EmptyTreeIterator;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.util.io.DisabledOutputStream;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Commit-level Git operations for change analysis, built on JGit.
 *
 * <ul>
 *   <li>{@link #resolveRange} resolves SHAs / branches / tags and optionally the merge-base</li>
 *   <li>{@link #diff} lists file changes with rename detection, line counts and hunks
 *       (whitespace changes ignored, like {@code git diff -w})</li>
 *   <li>{@link #exportJavaSources} writes the Java sources of a commit to a directory so the
 *       existing JavaParser pipeline can analyse any historical commit without touching the
 *       working tree of the indexing workspace</li>
 * </ul>
 */
@Service
@Slf4j
public class GitDiffService {

    private static final Pattern SAFE_REF = Pattern.compile("^(?!-)(?!.*\\.\\.)[A-Za-z0-9._/~^-]{1,200}$");
    private static final int MAX_HUNKS_PER_FILE = 200;
    private static final long MAX_DIFFABLE_BYTES = 2 * 1024 * 1024;

    /**
     * Resolves a user-supplied ref (full/short SHA, branch, remote branch or tag) to a commit.
     */
    public RevCommit resolveCommit(Repository repo, String ref) throws IOException {
        if (ref == null || !SAFE_REF.matcher(ref.trim()).matches()) {
            throw new BusinessException("INVALID_REF", "Invalid commit reference: " + ref);
        }
        String r = ref.trim();
        List<String> candidates = List.of(r, "refs/remotes/origin/" + r, "refs/tags/" + r, "refs/heads/" + r);
        for (String candidate : candidates) {
            ObjectId id;
            try {
                id = repo.resolve(candidate + "^{commit}");
            } catch (org.eclipse.jgit.errors.MissingObjectException | org.eclipse.jgit.errors.IncorrectObjectTypeException e) {
                continue; // a full SHA that is not (or not a commit) in the object database
            }
            if (id != null) {
                try (RevWalk walk = new RevWalk(repo)) {
                    return walk.parseCommit(id);
                }
            }
        }
        throw new BusinessException("COMMIT_NOT_FOUND", "Commit or ref not found in repository: " + ref);
    }

    public CommitRange resolveRange(Repository repo, String baseRef, String headRef, boolean useMergeBase) throws IOException {
        RevCommit base = resolveCommit(repo, baseRef);
        RevCommit head = resolveCommit(repo, headRef);
        if (useMergeBase) {
            try (RevWalk walk = new RevWalk(repo)) {
                walk.setRevFilter(RevFilter.MERGE_BASE);
                walk.markStart(walk.parseCommit(base));
                walk.markStart(walk.parseCommit(head));
                RevCommit mergeBase = walk.next();
                if (mergeBase == null) {
                    throw new BusinessException("NO_MERGE_BASE", "The two commits have no common ancestor");
                }
                return new CommitRange(mergeBase.name(), head.name(), true);
            }
        }
        return new CommitRange(base.name(), head.name(), false);
    }

    /** File changes from {@code baseSha} to {@code headSha} (or from the empty tree when base is null). */
    public List<FileChange> diff(Repository repo, String baseSha, String headSha) throws IOException {
        try (RevWalk walk = new RevWalk(repo);
             DiffFormatter formatter = new DiffFormatter(DisabledOutputStream.INSTANCE)) {
            formatter.setRepository(repo);
            formatter.setDiffComparator(RawTextComparator.WS_IGNORE_ALL);
            formatter.setDetectRenames(true);

            AbstractTreeIterator oldTree = baseSha == null ? new EmptyTreeIterator() : treeParser(repo, walk, baseSha);
            AbstractTreeIterator newTree = treeParser(repo, walk, headSha);

            List<FileChange> changes = new ArrayList<>();
            for (DiffEntry entry : formatter.scan(oldTree, newTree)) {
                changes.add(toFileChange(formatter, entry));
            }
            changes.sort(Comparator.comparing(FileChange::path));
            return changes;
        }
    }

    /** Diff of a commit against its first parent (root commits: against the empty tree). */
    public List<FileChange> diffAgainstParent(Repository repo, RevCommit commit) throws IOException {
        String parent = commit.getParentCount() > 0 ? commit.getParent(0).name() : null;
        return diff(repo, parent, commit.name());
    }

    private FileChange toFileChange(DiffFormatter formatter, DiffEntry entry) throws IOException {
        String type = switch (entry.getChangeType()) {
            case ADD -> "ADDED";
            case DELETE -> "DELETED";
            case RENAME -> "RENAMED";
            case COPY -> "COPIED";
            default -> "MODIFIED";
        };
        String path = entry.getChangeType() == DiffEntry.ChangeType.DELETE ? entry.getOldPath() : entry.getNewPath();
        String oldPath = switch (entry.getChangeType()) {
            case RENAME, COPY, DELETE -> entry.getOldPath();
            default -> null;
        };

        int additions = 0;
        int deletions = 0;
        List<Hunk> hunks = new ArrayList<>();
        try {
            for (Edit edit : formatter.toFileHeader(entry).toEditList()) {
                additions += edit.getLengthB();
                deletions += edit.getLengthA();
                if (hunks.size() < MAX_HUNKS_PER_FILE) {
                    // JGit edits are 0-based half-open ranges; store 1-based inclusive ranges.
                    hunks.add(new Hunk(edit.getBeginA() + 1, edit.getEndA(), edit.getBeginB() + 1, edit.getEndB()));
                }
            }
        } catch (Exception e) {
            log.debug("Could not compute edits for {}: {}", path, e.getMessage()); // binary or unreadable
        }
        return new FileChange(path, oldPath, type, FileCategory.of(path), additions, deletions, hunks);
    }

    /** Reads a text file as it exists in a commit. */
    public Optional<String> readFile(Repository repo, String commitSha, String path) throws IOException {
        if (path == null) {
            return Optional.empty();
        }
        try (RevWalk walk = new RevWalk(repo)) {
            RevTree tree = walk.parseCommit(repo.resolve(commitSha)).getTree();
            try (TreeWalk tw = TreeWalk.forPath(repo, path, tree)) {
                if (tw == null) {
                    return Optional.empty();
                }
                ObjectLoader loader = repo.open(tw.getObjectId(0), Constants.OBJ_BLOB);
                if (loader.getSize() > MAX_DIFFABLE_BYTES) {
                    return Optional.empty();
                }
                return Optional.of(new String(loader.getBytes(), StandardCharsets.UTF_8));
            }
        }
    }

    /**
     * Writes all non-excluded {@code .java} files of a commit into {@code target}, preserving relative paths.
     * Only blobs are read; the repository working tree is not modified.
     *
     * @return number of files written
     */
    public int exportJavaSources(Repository repo, String commitSha, Path target, List<String> excludedPaths) throws IOException {
        return exportSources(repo, commitSha, target, excludedPaths, java.util.Set.of(".java"));
    }

    /** Writes the files of a commit whose names end with one of {@code extensions} to {@code target}. */
    public int exportSources(Repository repo, String commitSha, Path target, List<String> excludedPaths,
                             java.util.Set<String> extensions) throws IOException {
        Path root = target.toAbsolutePath().normalize();
        Files.createDirectories(root);
        int count = 0;
        try (RevWalk walk = new RevWalk(repo); TreeWalk tw = new TreeWalk(repo)) {
            tw.addTree(walk.parseCommit(repo.resolve(commitSha)).getTree());
            tw.setRecursive(true);
            while (tw.next()) {
                String path = tw.getPathString();
                if (extensions.stream().noneMatch(path::endsWith) || isExcluded(path, excludedPaths)) {
                    continue;
                }
                Path out = root.resolve(path).normalize();
                if (!out.startsWith(root)) {
                    continue; // never write outside the snapshot directory
                }
                ObjectLoader loader = repo.open(tw.getObjectId(0), Constants.OBJ_BLOB);
                if (loader.getSize() > MAX_DIFFABLE_BYTES) {
                    continue;
                }
                Files.createDirectories(out.getParent());
                Files.write(out, loader.getBytes());
                count++;
            }
        }
        return count;
    }

    static boolean isExcluded(String path, List<String> excludedPaths) {
        String p = "/" + path;
        for (String ex : excludedPaths) {
            String dir = ex.endsWith("/") ? ex : ex + "/";
            if (p.contains("/" + dir)) {
                return true;
            }
        }
        return false;
    }

    private static CanonicalTreeParser treeParser(Repository repo, RevWalk walk, String sha) throws IOException {
        RevTree tree = walk.parseCommit(repo.resolve(sha)).getTree();
        CanonicalTreeParser parser = new CanonicalTreeParser();
        try (var reader = repo.newObjectReader()) {
            parser.reset(reader, tree.getId());
        }
        return parser;
    }

    /** Fetches every branch and tag so that any ref the user names can be resolved. */
    /** Fetches one explicit refspec (e.g. a pull request head); failures are logged, not thrown. */
    public void fetchRefSpec(Git git, org.eclipse.jgit.transport.CredentialsProvider credentials, int timeoutSeconds, String refSpec) {
        try {
            var fetch = git.fetch().setRemote("origin")
                    .setRefSpecs(new org.eclipse.jgit.transport.RefSpec(refSpec))
                    .setTimeout(timeoutSeconds);
            if (credentials != null) {
                fetch.setCredentialsProvider(credentials);
            }
            fetch.call();
        } catch (Exception e) {
            log.warn("Fetching {} failed (continuing with local refs): {}", refSpec, e.getMessage());
        }
    }

    public void fetchAllRefs(Git git, org.eclipse.jgit.transport.CredentialsProvider credentials, int timeoutSeconds) {
        try {
            var fetch = git.fetch()
                    .setRemote("origin")
                    .setRefSpecs(new org.eclipse.jgit.transport.RefSpec("+refs/heads/*:refs/remotes/origin/*"),
                            new org.eclipse.jgit.transport.RefSpec("+refs/tags/*:refs/tags/*"))
                    .setTimeout(timeoutSeconds);
            if (credentials != null) {
                fetch.setCredentialsProvider(credentials);
            }
            fetch.call();
        } catch (Exception e) {
            log.warn("Fetching all refs failed (continuing with local refs): {}", e.getMessage());
        }
    }
}

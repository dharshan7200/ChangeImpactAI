package com.changeimpact.ingestion.workspace;

import com.changeimpact.common.config.AppProperties;
import com.changeimpact.common.exception.IngestionException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.ResetCommand;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import java.util.UUID;

/**
 * Manages sandboxed local workspaces for repository Git operations.
 *
 * <p>Security Protections:
 * <ul>
 *   <li>Each repository is strictly isolated inside its own UUID directory</li>
 *   <li>Path traversal validation guarantees no operations escape the sandbox</li>
 *   <li>Incremental fetching avoids recloning massive repositories on every run</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class WorkspaceManager {

    private final AppProperties appProperties;
    private GitUrlGuard gitUrlGuard;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setGitUrlGuard(GitUrlGuard gitUrlGuard) {
        this.gitUrlGuard = gitUrlGuard;
    }

    /**
     * Resolves and prepares the isolated workspace directory for a repository.
     */
    public Path getWorkspacePath(UUID repositoryId) {
        String basePathStr = appProperties.getIngestion().getWorkspaceBasePath();
        if (basePathStr == null || basePathStr.isBlank()) {
            basePathStr = Paths.get(System.getProperty("java.io.tmpdir"), "changeimpact", "workspaces").toString();
        }

        Path basePath = Paths.get(basePathStr).toAbsolutePath().normalize();
        Path repoPath = basePath.resolve(repositoryId.toString()).normalize();

        // Enforce path confinement
        if (!repoPath.startsWith(basePath)) {
            throw new IngestionException("Directory traversal attempt detected for repository: " + repositoryId);
        }

        File dir = repoPath.toFile();
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IngestionException("Failed to create workspace directory: " + repoPath);
        }

        return repoPath;
    }

    /**
     * Clones or incrementally fetches the target repository branch.
     *
     * @param repositoryId  Repository UUID
     * @param cloneUrl      Git clone URL
     * @param branch        Target branch name (e.g. main)
     * @param accessToken   Decrypted GitHub access token (optional for public repos)
     * @return Open Git instance (caller should close)
     */
    private final java.util.concurrent.ConcurrentHashMap<UUID, java.util.concurrent.locks.ReentrantLock> repositoryLocks =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** Work that may throw checked exceptions while holding the repository lock. */
    @FunctionalInterface
    public interface LockedWork<T> {
        T run() throws Exception;
    }

    /**
     * Serialises all work on one repository's Git workspace and on its per-commit analysis data. Indexing and
     * change analysis run in different consumers; without this, concurrent fetches fail on Git ref locks and a
     * re-index could rewrite the working tree or the symbols of a commit while an analysis reads them.
     * In-process lock: one backend instance per workspace volume (see deployment notes).
     */
    public <T> T withRepositoryLock(UUID repositoryId, long timeoutMinutes, LockedWork<T> work) throws Exception {
        var lock = repositoryLocks.computeIfAbsent(repositoryId, id -> new java.util.concurrent.locks.ReentrantLock(true));
        if (!lock.tryLock(timeoutMinutes, java.util.concurrent.TimeUnit.MINUTES)) {
            throw new IngestionException("Repository " + repositoryId + " is busy with another job for more than "
                    + timeoutMinutes + " minutes");
        }
        try {
            return work.run();
        } finally {
            lock.unlock();
        }
    }

    /** Result of probing a remote: reachable (exists and is readable with the given credentials) and its default branch. */
    public record RemoteProbe(boolean reachable, Optional<String> defaultBranch) {}

    /**
     * Probes a remote like {@code git ls-remote --symref <url> HEAD}: whether it exists and is readable, and which
     * branch {@code HEAD} points to.
     */
    public RemoteProbe probeRemote(String cloneUrl, String accessToken) {
        if (gitUrlGuard != null) gitUrlGuard.check(cloneUrl);
        try {
            var ls = Git.lsRemoteRepository().setRemote(cloneUrl).setTimeout(60);
            var credentials = credentials(accessToken);
            if (credentials != null) ls.setCredentialsProvider(credentials);
            org.eclipse.jgit.lib.Ref head = ls.callAsMap().get(Constants.HEAD);
            return new RemoteProbe(true, head != null && head.isSymbolic()
                    ? Optional.of(Repository.shortenRefName(head.getTarget().getName())) : Optional.empty());
        } catch (Exception e) {
            log.info("Remote {} is not readable: {}", cloneUrl, e.getMessage());
            return new RemoteProbe(false, Optional.empty());
        }
    }

    public Git cloneOrFetchRepository(UUID repositoryId, String cloneUrl, String branch, String accessToken) {
        if (gitUrlGuard != null) gitUrlGuard.check(cloneUrl); // SSRF protection (null only in unit tests)
        Path workspacePath = getWorkspacePath(repositoryId);
        File gitDir = new File(workspacePath.toFile(), ".git");

        UsernamePasswordCredentialsProvider credentialsProvider = null;
        if (accessToken != null && !accessToken.isBlank()) {
            // GitHub OAuth / PAT uses token as username or "x-access-token"
            credentialsProvider = new UsernamePasswordCredentialsProvider("x-access-token", accessToken);
        }

        try {
            if (gitDir.exists()) {
                log.info("Incremental fetch for repository {} in {}", repositoryId, workspacePath);
                Git git = Git.open(workspacePath.toFile());

                // Explicit refspec: the original clone may have fetched only another branch.
                var fetchCommand = git.fetch()
                        .setRemote("origin")
                        .setRefSpecs(new RefSpec("+refs/heads/" + branch + ":refs/remotes/origin/" + branch))
                        .setCheckFetchedObjects(true)
                        .setTimeout(appProperties.getGithub().getCloneTimeoutSeconds());
                if (credentialsProvider != null) {
                    fetchCommand.setCredentialsProvider(credentialsProvider);
                }
                fetchCommand.call();

                String remoteRef = "origin/" + branch;
                if (git.getRepository().resolve(remoteRef) == null) {
                    throw new IngestionException("Branch '" + branch + "' does not exist on the remote repository");
                }

                // NOTE: JGit ignores setStartPoint() unless setCreateBranch(true) is used, so checking out an
                // existing local branch alone would NOT move it to the fetched commits. Create the local
                // branch when missing, then hard-reset it to the fetched remote head.
                boolean localBranchExists = git.getRepository().findRef(Constants.R_HEADS + branch) != null;
                git.checkout()
                        .setName(branch)
                        .setCreateBranch(!localBranchExists)
                        .setStartPoint(remoteRef)
                        .setForced(true)
                        .call();
                git.reset().setMode(ResetCommand.ResetType.HARD).setRef(remoteRef).call();
                git.clean().setCleanDirectories(true).setForce(true).call();

                return git;
            } else {
                log.info("Cloning repository {} from {} into {}", repositoryId, cloneUrl, workspacePath);
                var cloneCommand = Git.cloneRepository()
                        .setURI(cloneUrl)
                        .setDirectory(workspacePath.toFile())
                        .setBranch(branch != null ? branch : "main")
                        .setCloneAllBranches(false)
                        .setTimeout(appProperties.getGithub().getCloneTimeoutSeconds());

                if (credentialsProvider != null) {
                    cloneCommand.setCredentialsProvider(credentialsProvider);
                }

                return cloneCommand.call();
            }
        } catch (GitAPIException | IOException e) {
            log.error("Git operation failed for repository {}: {}", repositoryId, e.getMessage(), e);
            throw new IngestionException("Git clone/fetch failed: " + e.getMessage(), e);
        }
    }

    /**
     * Retrieves the latest commit SHA for HEAD or the current branch.
     */
    public String getHeadCommitSha(Git git) {
        try {
            Repository repository = git.getRepository();
            ObjectId head = repository.resolve(Constants.HEAD);
            if (head == null) {
                throw new IngestionException("Unable to resolve HEAD commit");
            }
            return head.name();
        } catch (IOException e) {
            throw new IngestionException("Failed to read HEAD commit SHA", e);
        }
    }

    /**
     * Retrieves RevCommit for a specific SHA.
     */
    public RevCommit getRevCommit(Git git, String commitSha) {
        try (RevWalk revWalk = new RevWalk(git.getRepository())) {
            ObjectId objId = git.getRepository().resolve(commitSha);
            if (objId == null) {
                return null;
            }
            return revWalk.parseCommit(objId);
        } catch (IOException e) {
            log.warn("Failed to resolve commit {}: {}", commitSha, e.getMessage());
            return null;
        }
    }

    private static final java.util.regex.Pattern FULL_SHA = java.util.regex.Pattern.compile("^[0-9a-f]{40}$");

    /**
     * Isolated directory holding the exported sources of one commit (used to analyse historical commits
     * without touching the indexing working tree). Lives next to, not inside, the repository workspace.
     */
    public Path getSnapshotPath(UUID repositoryId, String commitSha) {
        if (commitSha == null || !FULL_SHA.matcher(commitSha).matches()) {
            throw new IngestionException("Invalid commit SHA for snapshot: " + commitSha);
        }
        Path repoPath = getWorkspacePath(repositoryId);
        Path snapshot = repoPath.resolveSibling(repositoryId + ".snapshots").resolve(commitSha).normalize();
        if (!snapshot.startsWith(repoPath.getParent())) {
            throw new IngestionException("Directory traversal attempt detected for snapshot: " + commitSha);
        }
        return snapshot;
    }

    public void deleteSnapshot(UUID repositoryId, String commitSha) {
        Path snapshot = getSnapshotPath(repositoryId, commitSha);
        try {
            FileUtils.deleteDirectory(snapshot.toFile());
        } catch (IOException e) {
            log.warn("Failed to delete snapshot {}: {}", snapshot, e.getMessage());
        }
    }

    /** Credentials for HTTPS Git operations with a GitHub token (null for public repositories). */
    public static UsernamePasswordCredentialsProvider credentials(String accessToken) {
        return (accessToken == null || accessToken.isBlank())
                ? null
                : new UsernamePasswordCredentialsProvider("x-access-token", accessToken);
    }

    /**
     * Cleans up the workspace directory on disk.
     */
    public void deleteWorkspace(UUID repositoryId) {
        Path workspacePath = getWorkspacePath(repositoryId);
        try {
            FileUtils.deleteDirectory(workspacePath.toFile());
            log.info("Cleaned workspace directory for repository: {}", repositoryId);
        } catch (IOException e) {
            log.warn("Failed to delete workspace directory {}: {}", workspacePath, e.getMessage());
        }
    }
}


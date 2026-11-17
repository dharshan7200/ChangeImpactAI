package com.changeimpact.ingestion.workspace;

import com.changeimpact.common.config.AppProperties;
import com.changeimpact.common.exception.IngestionException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
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
    public Git cloneOrFetchRepository(UUID repositoryId, String cloneUrl, String branch, String accessToken) {
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

                var fetchCommand = git.fetch().setRemote("origin").setCheckFetchedObjects(true);
                if (credentialsProvider != null) {
                    fetchCommand.setCredentialsProvider(credentialsProvider);
                }
                fetchCommand.call();

                // Checkout target branch
                git.checkout()
                        .setName(branch)
                        .setStartPoint("origin/" + branch)
                        .setForced(true)
                        .call();

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


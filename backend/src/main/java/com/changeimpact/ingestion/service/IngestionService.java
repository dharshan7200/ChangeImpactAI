package com.changeimpact.ingestion.service;

import com.changeimpact.common.exception.IngestionException;
import com.changeimpact.common.exception.ResourceNotFoundException;
import com.changeimpact.github.service.GitHubAccountService;
import com.changeimpact.ingestion.workspace.WorkspaceManager;
import com.changeimpact.repository.entity.AnalysisJob;
import com.changeimpact.repository.entity.Commit;
import com.changeimpact.repository.entity.Repository;
import com.changeimpact.repository.entity.RepositoryBranch;
import com.changeimpact.repository.entity.RepositoryIndex;
import com.changeimpact.repository.repository.AnalysisJobRepository;
import com.changeimpact.repository.repository.CommitRepository;
import com.changeimpact.repository.repository.RepositoryBranchRepository;
import com.changeimpact.repository.repository.RepositoryIndexRepository;
import com.changeimpact.repository.repository.RepositoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.revwalk.RevCommit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Service that coordinates repository cloning, Git inspection, file scanning,
 * and database status tracking during repository ingestion.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class IngestionService {

    private final RepositoryRepository repositoryRepository;
    private final RepositoryIndexRepository repositoryIndexRepository;
    private final AnalysisJobRepository analysisJobRepository;
    private final CommitRepository commitRepository;
    private final RepositoryBranchRepository repositoryBranchRepository;
    private final GitHubAccountService gitHubAccountService;
    private final WorkspaceManager workspaceManager;
    private final JavaFileScanner javaFileScanner;
    private final com.changeimpact.parser.service.CodeAnalysisService codeAnalysisService;
    private final com.changeimpact.architecture.service.ArchitectureRuleEngine architectureRuleEngine;

    @Transactional
    public void executeIndexingJob(UUID jobId, UUID repositoryId, String branch) {
        log.info("Starting execution of repository indexing job: {} for repo: {}, branch: {}", jobId, repositoryId, branch);

        AnalysisJob job = analysisJobRepository.findById(jobId)
                .orElseThrow(() -> new ResourceNotFoundException("AnalysisJob", jobId));
        job.markRunning();
        job.updateProgress("CLONING", "Cloning repository workspace from GitHub...", 10);
        analysisJobRepository.saveAndFlush(job);

        Repository repository = repositoryRepository.findById(repositoryId)
                .orElseThrow(() -> new ResourceNotFoundException("Repository", repositoryId));

        String targetBranch = (branch != null && !branch.isBlank()) ? branch : repository.getDefaultBranch();

        String accessToken = null;
        if (repository.getConnectedAccount() != null) {
            accessToken = gitHubAccountService.getDecryptedAccessToken(repository.getConnectedAccount());
        }

        RepositoryIndex repoIndex = RepositoryIndex.builder()
                .repository(repository)
                .branchName(targetBranch)
                .commitSha("PENDING")
                .status(RepositoryIndex.Status.RUNNING)
                .startedAt(Instant.now())
                .build();
        repositoryIndexRepository.saveAndFlush(repoIndex);

        try (Git git = workspaceManager.cloneOrFetchRepository(repositoryId, repository.getCloneUrl(), targetBranch, accessToken)) {
            String headCommitSha = workspaceManager.getHeadCommitSha(git);
            repoIndex.setCommitSha(headCommitSha);

            job.updateProgress("SCANNING", "Scanning Java source files in repository...", 30);
            analysisJobRepository.saveAndFlush(job);

            Path workspacePath = workspaceManager.getWorkspacePath(repositoryId);
            List<JavaFileMetadata> javaFiles = javaFileScanner.scanJavaFiles(workspacePath);

            // Persist branch metadata
            saveBranchMetadata(repository, targetBranch, headCommitSha);

            // Persist recent commits
            persistRecentCommits(git, repository);

            int totalSymbols = 0;
            int totalDeps = 0;
            int unresolved = 0;
            int parsedFiles = 0;

            if (javaFiles.isEmpty()) {
                log.warn("Repository {} contains 0 Java files", repository.getFullName());
                job.updateProgress("COMPLETED", "Completed: 0 Java source files found in this repository.", 100);
            } else {
                job.updateProgress("PARSING", "Extracting AST symbols from " + javaFiles.size() + " Java files (JavaParser)...", 55);
                analysisJobRepository.saveAndFlush(job);

                // Run Phase 2 static AST code intelligence analysis
                var stats = codeAnalysisService.analyzeRepository(repository, headCommitSha, workspacePath);
                totalSymbols = stats.getTotalSymbols();
                totalDeps = stats.getTotalDependencies();
                unresolved = stats.getUnresolvedDependencies();
                parsedFiles = stats.getParsedFiles();

                job.updateProgress("GRAPH_BUILDING", "Constructing directed dependency graph (JGraphT)...", 80);
                analysisJobRepository.saveAndFlush(job);

                // Run architectural boundary validation
                job.updateProgress("ANALYZING", "Computing coupling metrics and detecting cyclic loops...", 92);
                analysisJobRepository.saveAndFlush(job);
                architectureRuleEngine.analyzeArchitecture(repository, headCommitSha);
            }

            // Complete the repository index
            Instant completedAt = Instant.now();
            long durationMs = completedAt.toEpochMilli() - repoIndex.getStartedAt().toEpochMilli();
            repoIndex.setStatus(RepositoryIndex.Status.COMPLETED);
            repoIndex.setCompletedAt(completedAt);
            repoIndex.setDurationMs(durationMs);
            repoIndex.setTotalFiles(javaFiles.size());
            repoIndex.setIndexedFiles(parsedFiles);
            repoIndex.setFailedFiles(0);
            repoIndex.setTotalSymbols(totalSymbols);
            repoIndex.setTotalDependencies(totalDeps);
            repositoryIndexRepository.save(repoIndex);

            // Update repository entity
            repository.setIndexingStatus(Repository.IndexingStatus.INDEXED);
            repository.setLastIndexedAt(completedAt);
            repository.setLastIndexedCommit(headCommitSha);
            repositoryRepository.save(repository);

            // Mark job completed
            Map<String, Object> resultSummary = Map.of(
                    "status", "SUCCESS",
                    "totalFiles", javaFiles.size(),
                    "totalSymbols", totalSymbols,
                    "totalDependencies", totalDeps,
                    "unresolvedDependencies", unresolved,
                    "commitSha", headCommitSha,
                    "branch", targetBranch,
                    "durationMs", durationMs
            );
            job.markCompleted(resultSummary);
            analysisJobRepository.save(job);

            log.info("Successfully completed repository indexing job: {} for repo: {}, indexed {} files, {} symbols",
                    jobId, repository.getFullName(), javaFiles.size(), totalSymbols);

        } catch (Exception e) {
            log.error("Indexing failed for repository {}: {}", repositoryId, e.getMessage(), e);

            repoIndex.setStatus(RepositoryIndex.Status.FAILED);
            repoIndex.setCompletedAt(Instant.now());
            repoIndex.setErrorMessage(e.getMessage());
            repositoryIndexRepository.save(repoIndex);

            repository.setIndexingStatus(Repository.IndexingStatus.FAILED);
            repositoryRepository.save(repository);

            job.markFailed(e.getMessage());
            analysisJobRepository.save(job);
        }
    }

    private void saveBranchMetadata(Repository repository, String branchName, String headCommitSha) {
        RepositoryBranch branch = repositoryBranchRepository.findByRepositoryIdAndName(repository.getId(), branchName)
                .orElseGet(() -> RepositoryBranch.builder()
                        .repository(repository)
                        .name(branchName)
                        .isDefault(branchName.equals(repository.getDefaultBranch()))
                        .build());

        branch.setCommitSha(headCommitSha);
        branch.setLastSeenAt(Instant.now());
        repositoryBranchRepository.save(branch);
    }

    private void persistRecentCommits(Git git, Repository repository) {
        try {
            Iterable<RevCommit> commits = git.log().setMaxCount(30).call();
            for (RevCommit rev : commits) {
                String sha = rev.name();
                if (!commitRepository.existsByRepositoryIdAndSha(repository.getId(), sha)) {
                    List<String> parentShas = new ArrayList<>();
                    for (RevCommit p : rev.getParents()) {
                        parentShas.add(p.name());
                    }

                    Commit commit = Commit.builder()
                            .repository(repository)
                            .sha(sha)
                            .message(rev.getFullMessage())
                            .authorName(rev.getAuthorIdent().getName())
                            .authorEmail(rev.getAuthorIdent().getEmailAddress())
                            .authorDate(rev.getAuthorIdent().getWhen().toInstant())
                            .committerName(rev.getCommitterIdent().getName())
                            .committerEmail(rev.getCommitterIdent().getEmailAddress())
                            .commitDate(rev.getCommitterIdent().getWhen().toInstant())
                            .parentShas(parentShas.toArray(new String[0]))
                            .build();

                    commitRepository.save(commit);
                }
            }
        } catch (Exception e) {
            log.warn("Could not retrieve commit log for repository {}: {}", repository.getId(), e.getMessage());
        }
    }
}


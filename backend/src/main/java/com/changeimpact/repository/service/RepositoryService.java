package com.changeimpact.repository.service;

import com.changeimpact.common.exception.BusinessException;
import com.changeimpact.common.exception.ResourceNotFoundException;
import com.changeimpact.github.client.GitHubApiClient;
import com.changeimpact.github.dto.GitHubRepositoryDto;
import com.changeimpact.github.service.GitHubAccountService;
import com.changeimpact.ingestion.jobs.RepositoryIndexJobPublisher;
import com.changeimpact.repository.dto.ConnectRepositoryRequest;
import com.changeimpact.repository.dto.RepositoryResponse;
import com.changeimpact.repository.entity.AnalysisJob;
import com.changeimpact.repository.entity.ConnectedAccount;
import com.changeimpact.repository.entity.Repository;
import com.changeimpact.repository.entity.User;
import com.changeimpact.repository.repository.AnalysisJobRepository;
import com.changeimpact.repository.repository.ConnectedAccountRepository;
import com.changeimpact.repository.repository.RepositoryRepository;
import com.changeimpact.repository.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Service for connecting, managing and indexing repositories.
 *
 * <p>When a user connects a repository, this service:
 * <ol>
 *   <li>Validates access via the GitHub API</li>
 *   <li>Creates or updates the Repository entity</li>
 *   <li>Optionally queues an indexing job</li>
 * </ol>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RepositoryService {

    private final RepositoryRepository repositoryRepository;
    private final UserRepository userRepository;
    private final ConnectedAccountRepository connectedAccountRepository;
    private final AnalysisJobRepository analysisJobRepository;
    private final GitHubApiClient gitHubApiClient;
    private final GitHubAccountService gitHubAccountService;
    private final RepositoryIndexJobPublisher jobPublisher;

    @Transactional
    public RepositoryResponse connectRepository(UUID userId, ConnectRepositoryRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", userId));

        ConnectedAccount account = null;
        if (request.connectedAccountId() != null) {
            account = connectedAccountRepository.findById(request.connectedAccountId())
                    .filter(a -> a.getUser().getId().equals(userId) && a.getIsActive())
                    .orElse(null);
        }
        if (account == null) {
            account = connectedAccountRepository.findByUserIdAndIsActiveTrue(userId).stream().findFirst().orElse(null);
        }

        // Verify repository access via GitHub API
        String rawName = request.fullName().trim();
        if (rawName.startsWith("https://github.com/")) {
            rawName = rawName.substring("https://github.com/".length());
        }
        if (rawName.endsWith(".git")) {
            rawName = rawName.substring(0, rawName.length() - 4);
        }

        String[] parts = rawName.split("/");
        if (parts.length != 2) {
            throw new BusinessException("INVALID_REPO_NAME",
                    "Repository name must be in format 'owner/repo' or full GitHub URL");
        }

        String owner = parts[0].trim();
        String repoName = parts[1].trim();
        String cleanFullName = owner + "/" + repoName;

        // Prevent duplicate connections
        if (repositoryRepository.existsByOwnerIdAndFullNameAndDeletedAtIsNull(userId, cleanFullName)) {
            throw new BusinessException("REPO_ALREADY_CONNECTED",
                    "Repository " + cleanFullName + " is already connected");
        }

        GitHubRepositoryDto githubRepo = null;
        if (account != null) {
            try {
                String token = gitHubAccountService.getDecryptedAccessToken(account);
                githubRepo = gitHubApiClient.getRepository(token, owner, repoName);
            } catch (Exception e) {
                log.info("Could not fetch repo metadata from GitHub API for {} using account token (might be a public repo from another account): {}", cleanFullName, e.getMessage());
            }
        }

        String cloneUrl;
        String htmlUrl;
        String description = null;
        String defaultBranch = (request.branch() != null && !request.branch().isBlank()) ? request.branch() : "main";
        String language = "Java";
        boolean isPrivate = false;
        boolean isFork = false;
        int forkCount = 0;
        int starCount = 0;
        Long sizeKb = null;
        Long githubRepoId = null;

        if (githubRepo != null) {
            cloneUrl = githubRepo.cloneUrl();
            htmlUrl = githubRepo.htmlUrl();
            description = githubRepo.description();
            defaultBranch = (request.branch() != null && !request.branch().isBlank()) ? request.branch() : (githubRepo.defaultBranch() != null ? githubRepo.defaultBranch() : "main");
            language = githubRepo.language() != null ? githubRepo.language() : "Java";
            isPrivate = githubRepo.isPrivate();
            isFork = githubRepo.isFork();
            forkCount = githubRepo.forksCount() != null ? githubRepo.forksCount() : 0;
            starCount = githubRepo.stargazersCount() != null ? githubRepo.stargazersCount() : 0;
            sizeKb = githubRepo.size() != null ? githubRepo.size().longValue() : null;
            githubRepoId = githubRepo.id();
        } else {
            // Public repository fallback (belongs to another user or organization)
            cloneUrl = "https://github.com/" + cleanFullName + ".git";
            htmlUrl = "https://github.com/" + cleanFullName;
            description = "Public repository (" + cleanFullName + ")";
        }

        Repository repo = Repository.builder()
                .owner(user)
                .connectedAccount(account)
                .githubRepoId(githubRepoId)
                .fullName(cleanFullName)
                .name(repoName)
                .description(description)
                .cloneUrl(cloneUrl)
                .htmlUrl(htmlUrl)
                .defaultBranch(defaultBranch)
                .language(language)
                .isPrivate(isPrivate)
                .isFork(isFork)
                .forkCount(forkCount)
                .starCount(starCount)
                .sizeKb(sizeKb)
                .build();

        repositoryRepository.save(repo);
        log.info("Repository connected: {} for user: {}", repo.getFullName(), userId);

        return RepositoryResponse.from(repo);
    }

    @Transactional(readOnly = true)
    public Page<RepositoryResponse> listRepositories(UUID userId, Pageable pageable) {
        return repositoryRepository.findByOwnerIdAndDeletedAtIsNull(userId, pageable)
                .map(RepositoryResponse::from);
    }

    @Transactional(readOnly = true)
    public RepositoryResponse getRepository(UUID userId, UUID repoId) {
        Repository repo = repositoryRepository.findByIdAndOwnerIdAndDeletedAtIsNull(repoId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Repository", repoId));
        return RepositoryResponse.from(repo);
    }

    @Transactional
    public UUID triggerIndexing(UUID userId, UUID repoId) {
        Repository repo = repositoryRepository.findByIdAndOwnerIdAndDeletedAtIsNull(repoId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Repository", repoId));

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", userId));

        // Idempotency: prevent simultaneous active index jobs for the same repository (within last 5 minutes)
        Instant activeThreshold = Instant.now().minus(java.time.Duration.ofMinutes(5));
        List<AnalysisJob> runningJobs = analysisJobRepository.findByRepositoryIdAndStatus(repoId, AnalysisJob.Status.RUNNING);
        boolean hasActiveRunning = runningJobs.stream()
                .anyMatch(j -> j.getCreatedAt() != null && j.getCreatedAt().isAfter(activeThreshold));
        if (hasActiveRunning) {
            throw new BusinessException("JOB_ALREADY_RUNNING",
                    "An indexing job is already running for this repository");
        }
        List<AnalysisJob> queuedJobs = analysisJobRepository.findByRepositoryIdAndStatus(repoId, AnalysisJob.Status.QUEUED);
        boolean hasActiveQueued = queuedJobs.stream()
                .anyMatch(j -> j.getCreatedAt() != null && j.getCreatedAt().isAfter(activeThreshold));
        if (hasActiveQueued) {
            throw new BusinessException("JOB_ALREADY_RUNNING",
                    "An indexing job is already queued for this repository");
        }

        String idempotencyKey = "index:" + repoId + ":" + repo.getDefaultBranch() + ":" + Instant.now().toEpochMilli();

        // Mark repository as indexing
        repo.setIndexingStatus(Repository.IndexingStatus.INDEXING);
        repositoryRepository.save(repo);

        // Create job record
        Map<String, Object> payload = new java.util.HashMap<>();
        payload.put("repositoryId", repoId.toString());
        payload.put("branch", repo.getDefaultBranch() != null ? repo.getDefaultBranch() : "main");
        payload.put("cloneUrl", repo.getCloneUrl() != null ? repo.getCloneUrl() : "");

        AnalysisJob job = AnalysisJob.builder()
                .repository(repo)
                .owner(user)
                .jobType(AnalysisJob.JobType.REPOSITORY_INDEX)
                .payload(payload)
                .idempotencyKey(idempotencyKey)
                .build();
        analysisJobRepository.saveAndFlush(job);

        final UUID finalJobId = job.getId();
        final String finalBranch = repo.getDefaultBranch();

        // Publish to RabbitMQ safely after database transaction commits
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()) {
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                    new org.springframework.transaction.support.TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            jobPublisher.publishRepositoryIndexJob(finalJobId, repoId, finalBranch);
                        }
                    });
        } else {
            jobPublisher.publishRepositoryIndexJob(finalJobId, repoId, finalBranch);
        }

        log.info("Indexing triggered for repository: {} job: {}", repo.getFullName(), job.getId());
        return job.getId();
    }

    @Transactional(readOnly = true)
    public AnalysisJob getJobStatus(UUID userId, UUID jobId) {
        return analysisJobRepository.findByIdAndOwnerId(jobId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("AnalysisJob", jobId));
    }

    @Transactional
    public void deleteRepository(UUID userId, UUID repoId) {
        Repository repo = repositoryRepository.findByIdAndOwnerIdAndDeletedAtIsNull(repoId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Repository", repoId));

        repo.setDeletedAt(Instant.now());
        repositoryRepository.save(repo);
        log.info("Repository soft-deleted: {} by user: {}", repo.getFullName(), userId);
    }
}


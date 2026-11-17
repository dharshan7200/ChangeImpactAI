package com.changeimpact.github.service;

import com.changeimpact.common.exception.BusinessException;
import com.changeimpact.common.exception.GitHubIntegrationException;
import com.changeimpact.common.exception.ResourceNotFoundException;
import com.changeimpact.github.client.GitHubApiClient;
import com.changeimpact.github.dto.GitHubRepositoryDto;
import com.changeimpact.repository.entity.ConnectedAccount;
import com.changeimpact.repository.entity.User;
import com.changeimpact.repository.repository.ConnectedAccountRepository;
import com.changeimpact.repository.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Service for managing GitHub account connections.
 *
 * <p>Handles the OAuth token exchange flow, encrypted token storage,
 * and account metadata synchronisation from GitHub.
 *
 * <p>Access tokens are encrypted before storage using
 * {@link TokenEncryptionService}. They are decrypted only when needed
 * for GitHub API calls and are never logged or included in API responses.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class GitHubAccountService {

    private final ConnectedAccountRepository connectedAccountRepository;
    private final UserRepository userRepository;
    private final GitHubApiClient gitHubApiClient;
    private final TokenEncryptionService tokenEncryptionService;

    /**
     * Connects a GitHub account to a platform user using an OAuth access token.
     *
     * <p>If the GitHub account was previously connected and then disconnected,
     * it is re-activated. If it's a new connection, a new record is created.
     */
    @Transactional
    public ConnectedAccount connectGitHubAccount(UUID userId, String accessToken) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", userId));

        GitHubApiClient.GitHubUserDto githubUser;
        try {
            githubUser = gitHubApiClient.getAuthenticatedUser(accessToken);
        } catch (GitHubIntegrationException e) {
            throw new BusinessException("GITHUB_AUTH_FAILED",
                    "Failed to authenticate with GitHub: " + e.getMessage());
        }

        String encryptedToken = tokenEncryptionService.encrypt(accessToken);
        String providerAccountId = githubUser.id().toString();

        return connectedAccountRepository
                .findByUserIdAndProviderAndProviderAccountId(userId, "github", providerAccountId)
                .map(existing -> {
                    // Re-activate and update token
                    existing.setIsActive(true);
                    existing.setEncryptedAccessToken(encryptedToken);
                    existing.setLogin(githubUser.login());
                    existing.setDisplayName(githubUser.name());
                    existing.setAvatarUrl(githubUser.avatarUrl());
                    existing.setEmail(githubUser.email());
                    existing.setLastRefreshedAt(Instant.now());
                    log.info("Re-activated GitHub account: {} for user: {}", githubUser.login(), userId);
                    return connectedAccountRepository.save(existing);
                })
                .orElseGet(() -> {
                    ConnectedAccount account = ConnectedAccount.builder()
                            .user(user)
                            .provider("github")
                            .providerAccountId(providerAccountId)
                            .login(githubUser.login())
                            .displayName(githubUser.name())
                            .avatarUrl(githubUser.avatarUrl())
                            .email(githubUser.email())
                            .encryptedAccessToken(encryptedToken)
                            .build();
                    log.info("Connected new GitHub account: {} for user: {}", githubUser.login(), userId);
                    return connectedAccountRepository.save(account);
                });
    }

    /**
     * Returns the decrypted access token for a connected account.
     * Never expose this token in API responses or logs.
     */
    public String getDecryptedAccessToken(ConnectedAccount account) {
        return tokenEncryptionService.decrypt(account.getEncryptedAccessToken());
    }

    /**
     * Returns all active GitHub connections for a user.
     */
    @Transactional(readOnly = true)
    public List<ConnectedAccount> listActiveConnections(UUID userId) {
        return connectedAccountRepository.findActiveGitHubAccountsByUserId(userId);
    }

    /**
     * Lists repositories accessible to a connected GitHub account.
     */
    @Transactional(readOnly = true)
    public List<GitHubRepositoryDto> listRepositories(UUID userId, UUID accountId, int page) {
        ConnectedAccount account = connectedAccountRepository.findById(accountId)
                .filter(a -> a.getUser().getId().equals(userId) && a.getIsActive())
                .orElseThrow(() -> new ResourceNotFoundException("ConnectedAccount", accountId));

        String token = getDecryptedAccessToken(account);
        try {
            return gitHubApiClient.listUserRepositories(token, page);
        } catch (Exception e) {
            log.error("Failed to list repositories from GitHub for account {}: {}", accountId, e.getMessage());
            return List.of();
        }
    }

    /**
     * Disconnects a GitHub account (soft delete — marks inactive).
     */
    @Transactional
    public void disconnectAccount(UUID userId, UUID accountId) {
        ConnectedAccount account = connectedAccountRepository.findById(accountId)
                .filter(a -> a.getUser().getId().equals(userId))
                .orElseThrow(() -> new ResourceNotFoundException("ConnectedAccount", accountId));

        account.setIsActive(false);
        account.setEncryptedAccessToken(null);
        account.setEncryptedRefreshToken(null);
        connectedAccountRepository.save(account);
        log.info("Disconnected GitHub account: {} for user: {}", account.getLogin(), userId);
    }
}


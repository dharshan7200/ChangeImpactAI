package com.changeimpact.repository.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * A GitHub account (or other OAuth provider) linked to a platform user.
 *
 * <p>Access tokens are stored encrypted at the application layer using AES-256.
 * The {@link com.changeimpact.github.service.TokenEncryptionService} handles
 * encryption/decryption before values reach this entity.
 *
 * <p>A user may connect multiple GitHub accounts (personal + org bot account).
 * Each connected account has its own token and installation ID.
 */
@Entity
@Table(name = "connected_accounts", indexes = {
        @Index(name = "idx_connected_accounts_user_id", columnList = "user_id"),
        @Index(name = "idx_connected_accounts_provider", columnList = "provider, provider_account_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString(exclude = {"encryptedAccessToken", "encryptedRefreshToken"})
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class ConnectedAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @EqualsAndHashCode.Include
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    @com.fasterxml.jackson.annotation.JsonIgnore
    private User user;

    @Column(nullable = false, length = 50)
    @Builder.Default
    private String provider = "github";

    @Column(nullable = false, length = 255)
    private String providerAccountId;

    @Column(nullable = false, length = 255)
    private String login;

    @Column(length = 255)
    private String displayName;

    @Column(length = 1024)
    private String avatarUrl;

    @Column(length = 255)
    private String email;

    // Encrypted at application layer — never log or expose these
    @Column(columnDefinition = "TEXT")
    private String encryptedAccessToken;

    @Column(columnDefinition = "TEXT")
    private String encryptedRefreshToken;

    private Instant tokenExpiresAt;

    @Column(columnDefinition = "TEXT[]")
    private String[] scopes;

    private Long githubAppInstallationId;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant connectedAt;

    private Instant lastRefreshedAt;

    @Column(nullable = false)
    @Builder.Default
    private Boolean isActive = true;
}


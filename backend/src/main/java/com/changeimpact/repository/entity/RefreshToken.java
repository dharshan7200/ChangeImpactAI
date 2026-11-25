package com.changeimpact.repository.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * A refresh token stored against a user.
 *
 * <p>The token value is stored as a SHA-256 hash — plaintext is never persisted.
 * When a client presents a refresh token, the application hashes it and looks it
 * up against {@code tokenHash}.
 *
 * <p>Expired and revoked tokens should be periodically cleaned up by a scheduled job.
 */
@Entity
@Table(name = "refresh_tokens", indexes = {
        @Index(name = "idx_refresh_tokens_user_id", columnList = "user_id"),
        @Index(name = "idx_refresh_tokens_token_hash", columnList = "tokenHash")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString(exclude = "tokenHash")
public class RefreshToken {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false, unique = true, length = 255)
    private String tokenHash;

    @Column(nullable = false)
    private Instant expiresAt;

    @Column(nullable = false)
    @Builder.Default
    private Boolean revoked = false;

    private Instant revokedAt;

    @Column(length = 512)
    private String userAgent;

    @Column(length = 45)
    private String ipAddress;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    public boolean isExpired() {
        return Instant.now().isAfter(expiresAt);
    }

    public boolean isValid() {
        return !revoked && !isExpired();
    }

    public void revoke() {
        this.revoked = true;
        this.revokedAt = Instant.now();
    }
}


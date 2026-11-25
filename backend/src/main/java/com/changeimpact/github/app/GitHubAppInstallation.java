package com.changeimpact.github.app;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.UUID;

@Entity
@Table(name = "github_app_installations")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GitHubAppInstallation {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true)
    private Long installationId;

    @Column(nullable = false)
    private String accountLogin;

    @Column(length = 30)
    private String accountType;

    /** all | selected */
    @Column(length = 20)
    private String repositorySelection;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private java.util.List<String> repositories;

    private boolean suspended;

    @org.hibernate.annotations.CreationTimestamp
    @Column(nullable = false, updatable = false)
    private java.time.Instant createdAt;

    @org.hibernate.annotations.UpdateTimestamp
    @Column(nullable = false)
    private java.time.Instant updatedAt;

    /** Whether this installation grants access to {@code owner/repo}. */
    public boolean covers(String fullName) {
        if (suspended || fullName == null) return false;
        if ("all".equalsIgnoreCase(repositorySelection)) {
            return fullName.toLowerCase().startsWith(accountLogin.toLowerCase() + "/");
        }
        return repositories != null && repositories.stream().anyMatch(r -> r.equalsIgnoreCase(fullName));
    }
}

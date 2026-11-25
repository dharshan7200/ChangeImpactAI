package com.changeimpact.repository.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Entity representing a Git branch tracked for a repository.
 */
@Entity
@Table(name = "repository_branches", indexes = {
        @Index(name = "idx_repository_branches_repo_id", columnList = "repository_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class RepositoryBranch {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @EqualsAndHashCode.Include
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "repository_id", nullable = false)
    private Repository repository;

    @Column(nullable = false, length = 255)
    @EqualsAndHashCode.Include
    private String name;

    @Column(length = 255)
    private String commitSha;

    @Column(nullable = false)
    @Builder.Default
    private Boolean isDefault = false;

    @Column(nullable = false)
    @Builder.Default
    private Boolean isProtected = false;

    @Column(nullable = false)
    @Builder.Default
    private Instant lastSeenAt = Instant.now();
}


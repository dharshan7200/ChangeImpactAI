package com.changeimpact.repository.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * Records a single indexing run for a repository at a specific commit.
 *
 * <p>Each time a repository is indexed, a new {@link RepositoryIndex} is created.
 * This provides an audit trail of indexing history and allows partial recovery
 * when an indexing job fails partway through.
 */
@Entity
@Table(name = "repository_indexes", indexes = {
        @Index(name = "idx_repository_indexes_repo_id", columnList = "repository_id"),
        @Index(name = "idx_repository_indexes_status", columnList = "status")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class RepositoryIndex {

    public enum Status {
        PENDING, RUNNING, COMPLETED, FAILED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @EqualsAndHashCode.Include
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "repository_id", nullable = false)
    private Repository repository;

    @Column(nullable = false, length = 255)
    private String branchName;

    @Column(nullable = false, length = 255)
    private String commitSha;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    @Builder.Default
    private Status status = Status.PENDING;

    private Instant startedAt;
    private Instant completedAt;
    private Long durationMs;
    private Integer totalFiles;
    private Integer indexedFiles;
    private Integer failedFiles;
    private Integer totalSymbols;
    private Integer totalDependencies;

    @Column(columnDefinition = "TEXT")
    private String errorMessage;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant createdAt;
}


package com.changeimpact.repository.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * A GitHub repository connected to the platform.
 *
 * <p>Repositories are owned by a platform user and optionally linked to
 * a {@link ConnectedAccount} that provides the access credentials.
 *
 * <p>{@code indexingStatus} tracks the state of the code intelligence index:
 * <ul>
 *   <li>NOT_INDEXED — newly connected, no index built yet</li>
 *   <li>INDEXING — an indexing job is currently running</li>
 *   <li>INDEXED — a valid index exists for {@code lastIndexedCommit}</li>
 *   <li>STALE — the repository has new commits since the last index</li>
 *   <li>FAILED — the last indexing attempt failed</li>
 * </ul>
 */
@Entity
@Table(name = "repositories", indexes = {
        @Index(name = "idx_repositories_owner_id", columnList = "owner_id"),
        @Index(name = "idx_repositories_full_name", columnList = "fullName"),
        @Index(name = "idx_repositories_indexing_status", columnList = "indexingStatus")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class Repository {

    public enum IndexingStatus {
        NOT_INDEXED, INDEXING, INDEXED, FAILED, STALE
    }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @EqualsAndHashCode.Include
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "owner_id", nullable = false)
    @com.fasterxml.jackson.annotation.JsonIgnore
    private User owner;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "connected_account_id")
    @com.fasterxml.jackson.annotation.JsonIgnore
    private ConnectedAccount connectedAccount;

    private Long githubRepoId;

    @Column(nullable = false, length = 512)
    private String fullName;

    @Column(nullable = false, length = 255)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(nullable = false, length = 2048)
    private String cloneUrl;

    @Column(length = 2048)
    private String htmlUrl;

    @Column(nullable = false, length = 255)
    @Builder.Default
    private String defaultBranch = "main";

    @Column(length = 100)
    private String language;

    @Column(nullable = false)
    @Builder.Default
    private Boolean isPrivate = false;

    @Column(nullable = false)
    @Builder.Default
    private Boolean isFork = false;

    @Builder.Default
    private Integer forkCount = 0;

    @Builder.Default
    private Integer starCount = 0;

    private Long sizeKb;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    @Builder.Default
    private IndexingStatus indexingStatus = IndexingStatus.NOT_INDEXED;

    private Instant lastIndexedAt;

    @Column(length = 255)
    private String lastIndexedCommit;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(nullable = false)
    private Instant updatedAt;

    private Instant deletedAt;

    public boolean isIndexed() {
        return indexingStatus == IndexingStatus.INDEXED;
    }

    public boolean isDeleted() {
        return deletedAt != null;
    }
}


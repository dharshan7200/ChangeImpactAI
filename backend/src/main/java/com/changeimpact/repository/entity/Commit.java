package com.changeimpact.repository.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * Entity representing a Git commit associated with a repository.
 */
@Entity
@Table(name = "commits", indexes = {
        @Index(name = "idx_commits_repository_id", columnList = "repository_id"),
        @Index(name = "idx_commits_sha", columnList = "repository_id, sha")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class Commit {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @EqualsAndHashCode.Include
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "repository_id", nullable = false)
    private Repository repository;

    @Column(nullable = false, length = 255)
    @EqualsAndHashCode.Include
    private String sha;

    @Column(columnDefinition = "TEXT")
    private String message;

    @Column(length = 255)
    private String authorName;

    @Column(length = 255)
    private String authorEmail;

    private Instant authorDate;

    @Column(length = 255)
    private String committerName;

    @Column(length = 255)
    private String committerEmail;

    private Instant commitDate;

    @Column(columnDefinition = "TEXT[]")
    private String[] parentShas;

    @Column(nullable = false)
    @Builder.Default
    private Integer addedFiles = 0;

    @Column(nullable = false)
    @Builder.Default
    private Integer modifiedFiles = 0;

    @Column(nullable = false)
    @Builder.Default
    private Integer deletedFiles = 0;

    @Column(nullable = false)
    @Builder.Default
    private Integer additions = 0;

    @Column(nullable = false)
    @Builder.Default
    private Integer deletions = 0;

    @Column(nullable = false)
    @Builder.Default
    private Boolean isMerge = false;

    /** Heuristic from the commit message ("Revert ..." / "This reverts commit"). */
    @Column(nullable = false)
    @Builder.Default
    private Boolean isRevert = false;

    /** Heuristic label from the commit message (fix/bug/hotfix...). Not verified ground truth. */
    @Column(nullable = false)
    @Builder.Default
    private Boolean isBugfix = false;

    @Column(nullable = false)
    @Builder.Default
    private Boolean historyScanned = false;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant createdAt;
}


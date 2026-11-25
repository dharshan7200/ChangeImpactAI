package com.changeimpact.analysis.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** One commit-to-commit change impact analysis. Child rows are deleted with it (ON DELETE CASCADE). */
@Entity
@Table(name = "change_analyses")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ChangeAnalysis {

    public enum Status { QUEUED, RUNNING, COMPLETED, FAILED }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "repository_id", nullable = false)
    private UUID repositoryId;

    @Column(name = "owner_id", nullable = false)
    private UUID ownerId;

    @Column(name = "job_id")
    private UUID jobId;

    @Column(nullable = false)
    private String baseRef;

    @Column(nullable = false)
    private String headRef;

    @Column(length = 64)
    private String baseCommit;

    @Column(length = 64)
    private String headCommit;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private Status status = Status.QUEUED;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> options;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> summary;

    @Column(precision = 5, scale = 1)
    private BigDecimal riskScore;

    @Column(length = 20)
    private String riskLevel;

    @Column(columnDefinition = "TEXT")
    private String errorMessage;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    private Instant completedAt;

    /** MANUAL or PULL_REQUEST */
    @Column(nullable = false, length = 20)
    @Builder.Default
    private String trigger = "MANUAL";

    @Column(name = "pull_request_id")
    private UUID pullRequestId;
}

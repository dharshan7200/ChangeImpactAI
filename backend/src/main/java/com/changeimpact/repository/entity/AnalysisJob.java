package com.changeimpact.repository.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Represents an asynchronous background job submitted to the system.
 *
 * <p>Jobs are queued via RabbitMQ and their status is tracked here.
 * The {@code idempotencyKey} prevents the same logical job from being
 * submitted twice (e.g., two indexing requests for the same repo+commit).
 *
 * <p>The {@code payload} and {@code result} fields store JSON — job-specific
 * parameters and output summary respectively. The concrete structure depends
 * on the job type and is documented in the respective job handler.
 */
@Entity
@Table(name = "analysis_jobs", indexes = {
        @Index(name = "idx_analysis_jobs_repository_id", columnList = "repository_id"),
        @Index(name = "idx_analysis_jobs_owner_id", columnList = "owner_id"),
        @Index(name = "idx_analysis_jobs_status", columnList = "status"),
        @Index(name = "idx_analysis_jobs_idempotency_key", columnList = "idempotencyKey")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class AnalysisJob {

    public enum Status {
        QUEUED, RUNNING, COMPLETED, FAILED, CANCELLED
    }

    public enum JobType {
        REPOSITORY_INDEX, CHANGE_ANALYSIS, PR_ANALYSIS, ARCHITECTURE_HISTORY, DEFECT_MODEL, RAG_INDEX, AI_REVIEW
    }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @EqualsAndHashCode.Include
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "repository_id", nullable = false)
    @com.fasterxml.jackson.annotation.JsonIgnore
    private Repository repository;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "owner_id", nullable = false)
    @com.fasterxml.jackson.annotation.JsonIgnore
    private User owner;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 100)
    private JobType jobType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    @Builder.Default
    private Status status = Status.QUEUED;

    @Column(nullable = false)
    @Builder.Default
    private Integer priority = 5;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> payload;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> result;

    @Column(length = 100)
    @Builder.Default
    private String currentStage = "QUEUED";

    @Column(length = 255)
    @Builder.Default
    private String currentMessage = "Job queued in background...";

    @Column
    @Builder.Default
    private Integer progressPercent = 0;

    @Column(columnDefinition = "TEXT")
    private String errorMessage;

    @Column(nullable = false)
    @Builder.Default
    private Integer retryCount = 0;

    @Column(nullable = false)
    @Builder.Default
    private Integer maxRetries = 3;

    @Column(nullable = false, updatable = false)
    @Builder.Default
    private Instant queuedAt = Instant.now();

    private Instant startedAt;
    private Instant completedAt;
    private Long durationMs;

    @Column(unique = true, length = 512)
    private String idempotencyKey;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(nullable = false)
    private Instant updatedAt;

    public void markRunning() {
        this.status = Status.RUNNING;
        this.startedAt = Instant.now();
        this.currentStage = "RUNNING";
        this.currentMessage = "Starting indexing worker...";
        this.progressPercent = 5;
    }

    public void updateProgress(String stage, String message, int percent) {
        this.currentStage = stage;
        this.currentMessage = message != null && message.length() > 250 ? message.substring(0, 247) + "..." : message;
        this.progressPercent = percent;
    }

    public void markCompleted(Map<String, Object> result) {
        this.status = Status.COMPLETED;
        this.completedAt = Instant.now();
        this.currentStage = "COMPLETED";
        this.currentMessage = "Indexing completed successfully";
        this.progressPercent = 100;
        this.result = result;
        if (this.startedAt != null) {
            this.durationMs = this.completedAt.toEpochMilli() - this.startedAt.toEpochMilli();
        }
    }

    public void markFailed(String errorMessage) {
        this.status = Status.FAILED;
        this.completedAt = Instant.now();
        this.currentStage = "FAILED";
        String msg = errorMessage != null ? errorMessage : "Indexing failed";
        this.currentMessage = msg.length() > 250 ? msg.substring(0, 247) + "..." : msg;
        this.errorMessage = errorMessage;
        if (this.startedAt != null) {
            this.durationMs = this.completedAt.toEpochMilli() - this.startedAt.toEpochMilli();
        }
    }

    public boolean canRetry() {
        return retryCount < maxRetries;
    }

    @Transient
    @com.fasterxml.jackson.annotation.JsonProperty("repositoryId")
    public UUID getRepositoryId() {
        return repository != null ? repository.getId() : null;
    }

    @Transient
    @com.fasterxml.jackson.annotation.JsonProperty("ownerId")
    public UUID getOwnerId() {
        return owner != null ? owner.getId() : null;
    }
}


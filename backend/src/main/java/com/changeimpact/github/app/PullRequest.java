package com.changeimpact.github.app;

import jakarta.persistence.*;
import lombok.*;

import java.util.UUID;

@Entity
@Table(name = "pull_requests")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PullRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "repository_id", nullable = false)
    private UUID repositoryId;

    @Column(nullable = false)
    private Integer number;

    @Column(length = 1024)
    private String title;

    private String author;

    @Column(length = 1024)
    private String url;

    @Column(nullable = false, length = 20)
    private String state;

    private boolean draft;

    private String baseRef;

    @Column(length = 64)
    private String baseSha;

    private String headRef;

    @Column(length = 64)
    private String headSha;

    @Column(name = "last_analysis_id")
    private UUID lastAnalysisId;

    @Column(length = 20)
    private String riskLevel;

    @Column(precision = 5, scale = 1)
    private java.math.BigDecimal riskScore;

    private Long checkRunId;

    /** NOT_CONFIGURED, QUEUED, IN_PROGRESS, COMPLETED, FAILED_TO_PUBLISH */
    @Column(length = 40)
    private String checkStatus;

    @Column(length = 30)
    private String checkConclusion;

    @Column(columnDefinition = "TEXT")
    private String checkError;

    @org.hibernate.annotations.CreationTimestamp
    @Column(nullable = false, updatable = false)
    private java.time.Instant createdAt;

    @org.hibernate.annotations.UpdateTimestamp
    @Column(nullable = false)
    private java.time.Instant updatedAt;
}

package com.changeimpact.testing.reports.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.UUID;

/** A CI artefact (coverage, test results, mutation or dependency report) uploaded for one commit. */
@Entity
@Table(name = "quality_reports")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class QualityReport {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    public enum Type { JACOCO, JUNIT, PIT, DEPENDENCY_CHECK }

    @Column(name = "repository_id", nullable = false)
    private UUID repositoryId;

    @Column(nullable = false, length = 64)
    private String commitSha;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private Type reportType;

    private String fileName;

    @Column(name = "uploaded_by")
    private UUID uploadedBy;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private java.util.Map<String, Object> summary;

    @org.hibernate.annotations.CreationTimestamp
    @Column(nullable = false, updatable = false)
    private java.time.Instant createdAt;
}

package com.changeimpact.testing.recommendations.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.UUID;

/** A recommended test (or test gap / scenario) for one change analysis. Recommended is not executed: see executionStatus. */
@Entity
@Table(name = "test_recommendations")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TestRecommendation {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "analysis_id", nullable = false)
    private UUID analysisId;

    /** DIRECT, INDIRECT, MISSING_TEST, SCENARIO */
    @Column(nullable = false, length = 20)
    private String category;

    /** HIGH, MEDIUM, LOW */
    @Column(nullable = false, length = 10)
    private String priority;

    @Column(name = "test_symbol_id")
    private UUID testSymbolId;

    @Column(length = 1024)
    private String testClass;

    @Column(length = 512)
    private String testMethod;

    @Column(length = 1024)
    private String targetSymbol;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String reason;

    /** PASSED, FAILED, SKIPPED, NOT_EXECUTED, NO_RESULTS, NOT_APPLICABLE */
    @Column(nullable = false, length = 20)
    private String executionStatus;

    private Integer depth;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private java.util.Map<String, Object> evidence;
}

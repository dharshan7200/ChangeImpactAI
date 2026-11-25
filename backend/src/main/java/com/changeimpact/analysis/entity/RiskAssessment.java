package com.changeimpact.analysis.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Explainable heuristic risk score (0..100, not a probability). changedSymbolId null = whole change. */
@Entity
@Table(name = "risk_assessments")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RiskAssessment {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "analysis_id", nullable = false)
    private UUID analysisId;

    @Column(name = "changed_symbol_id")
    private UUID changedSymbolId;

    @Column(nullable = false, precision = 5, scale = 1)
    private BigDecimal score;

    @Column(nullable = false, length = 20)
    private String level;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    private List<Map<String, Object>> factors;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant createdAt;
}

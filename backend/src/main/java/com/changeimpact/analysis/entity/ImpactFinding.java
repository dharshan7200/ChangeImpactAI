package com.changeimpact.analysis.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A symbol affected by a changed symbol, with the exact dependency path that connects them.
 * {@code evidence}: VERIFIED_REFERENCE = the affected symbol directly references the changed symbol
 * (a resolved edge in the code); TRANSITIVE_PROPAGATION = reached through intermediate symbols, so every
 * edge is real but the behavioural impact is an inference.
 */
@Entity
@Table(name = "impact_findings")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ImpactFinding {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "analysis_id", nullable = false)
    private UUID analysisId;

    @Column(name = "changed_symbol_id", nullable = false)
    private UUID changedSymbolId;

    @Column(name = "affected_symbol_id")
    private UUID affectedSymbolId;

    @Column(nullable = false, length = 64)
    private String graphCommit;

    @Column(nullable = false, length = 1024)
    private String affectedQualifiedName;

    @Column(nullable = false)
    private String affectedSimpleName;

    @Column(nullable = false, length = 50)
    private String affectedSymbolType;

    @Column(length = 1024)
    private String affectedOwnerType;

    @Column(length = 1024)
    private String filePath;

    private Integer startLine;

    @Column(length = 512)
    private String packageName;

    @Column(length = 512)
    private String moduleName;

    private int depth;

    @Column(precision = 6, scale = 4, nullable = false)
    private BigDecimal strength;

    @Column(nullable = false, length = 20)
    private String impactType;

    @Column(nullable = false, length = 40)
    private String evidence;

    @Column(name = "is_test")
    private boolean test;

    private boolean alsoChanged;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    private List<Map<String, Object>> dependencyPath;
}

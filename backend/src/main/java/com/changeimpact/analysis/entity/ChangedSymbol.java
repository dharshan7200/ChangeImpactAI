package com.changeimpact.analysis.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "changed_symbols")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ChangedSymbol {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "analysis_id", nullable = false)
    private UUID analysisId;

    @Column(nullable = false, length = 1024)
    private String qualifiedName;

    @Column(length = 1024)
    private String previousQualifiedName;

    @Column(nullable = false)
    private String simpleName;

    @Column(nullable = false, length = 50)
    private String symbolType;

    @Column(nullable = false, columnDefinition = "TEXT[]")
    private String[] changeKinds;

    private boolean breaking;

    @Column(name = "base_symbol_id")
    private UUID baseSymbolId;

    @Column(name = "head_symbol_id")
    private UUID headSymbolId;

    @Column(length = 1024)
    private String filePath;

    private Integer startLine;

    private Integer endLine;

    @Column(name = "is_test")
    private boolean test;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> details;

    private int directDependents;

    private int transitiveDependents;

    @Column(precision = 5, scale = 1)
    private BigDecimal riskScore;

    @Column(length = 20)
    private String riskLevel;
}

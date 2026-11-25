package com.changeimpact.securityanalysis.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.UUID;

/** A security-relevant observation about a change. Tools are analysis sources, not proof of (in)security. */
@Entity
@Table(name = "security_findings")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SecurityFinding {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "analysis_id", nullable = false)
    private UUID analysisId;

    /** STATIC_RULES, SEMGREP, DEPENDENCY_CHECK, BUILD_DIFF */
    @Column(nullable = false, length = 30)
    private String source;

    @Column(nullable = false, length = 40)
    private String category;

    private String ruleId;

    /** INFO, LOW, MEDIUM, HIGH, CRITICAL */
    @Column(nullable = false, length = 10)
    private String severity;

    @Column(nullable = false, length = 512)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String message;

    @Column(length = 1024)
    private String filePath;

    private Integer startLine;

    private Integer endLine;

    @Column(length = 1024)
    private String symbol;

    /** CHANGED (in modified code), IMPACTED (in affected code), DEPENDENCY (build dependency) */
    @Column(nullable = false, length = 20)
    private String relation;

    @Column(length = 100)
    private String cwe;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private java.util.Map<String, Object> evidence;
}

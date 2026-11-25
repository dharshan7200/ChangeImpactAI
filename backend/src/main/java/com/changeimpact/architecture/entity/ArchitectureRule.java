package com.changeimpact.architecture.entity;

import com.changeimpact.repository.entity.Repository;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Entity representing an architectural rule (circular dependencies, layer restrictions).
 */
@Entity
@Table(name = "architecture_rules", indexes = {
        @Index(name = "idx_arch_rules_repo", columnList = "repository_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class ArchitectureRule {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @EqualsAndHashCode.Include
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "repository_id")
    private Repository repository;

    @Column(nullable = false, length = 255)
    private String ruleName;

    @Column(nullable = false, length = 50)
    private String ruleType;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(nullable = false, length = 20)
    @Builder.Default
    private String severity = "MEDIUM";

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> configuration;

    @Column(nullable = false)
    @Builder.Default
    private Boolean enabled = true;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @org.hibernate.annotations.UpdateTimestamp
    private Instant updatedAt;
}


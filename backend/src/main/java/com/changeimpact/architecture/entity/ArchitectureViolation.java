package com.changeimpact.architecture.entity;

import com.changeimpact.repository.entity.Repository;
import com.changeimpact.symbols.entity.Symbol;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * Entity representing an architectural boundary violation detected in a commit.
 */
@Entity
@Table(name = "architecture_violations", indexes = {
        @Index(name = "idx_arch_violations_repo_commit", columnList = "repository_id, commitSha"),
        @Index(name = "idx_arch_violations_severity", columnList = "severity")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class ArchitectureViolation {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @EqualsAndHashCode.Include
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "repository_id", nullable = false)
    private Repository repository;

    @Column(nullable = false, length = 255)
    private String commitSha;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "rule_id")
    private ArchitectureRule rule;

    @Column(nullable = false, length = 255)
    private String ruleName;

    @Column(nullable = false, length = 20)
    private String severity;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "source_symbol_id")
    private Symbol sourceSymbol;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "target_symbol_id")
    private Symbol targetSymbol;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String description;

    @Column(length = 1024)
    private String sourceLocation;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant detectedAt;
}


package com.changeimpact.symbols.entity;

import com.changeimpact.parser.model.DependencyType;
import com.changeimpact.parser.model.ResolutionStatus;
import com.changeimpact.repository.entity.Repository;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * Entity representing a directed dependency relationship between symbols.
 */
@Entity
@Table(name = "dependencies", indexes = {
        @Index(name = "idx_deps_repo_commit", columnList = "repository_id, commitSha"),
        @Index(name = "idx_deps_source", columnList = "source_symbol_id"),
        @Index(name = "idx_deps_target", columnList = "target_symbol_id"),
        @Index(name = "idx_deps_target_name", columnList = "targetQualifiedName"),
        @Index(name = "idx_deps_type", columnList = "dependencyType")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class SymbolDependency {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @EqualsAndHashCode.Include
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "repository_id", nullable = false)
    private Repository repository;

    @Column(nullable = false, length = 255)
    private String commitSha;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "source_symbol_id", nullable = false)
    private Symbol sourceSymbol;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "target_symbol_id")
    private Symbol targetSymbol;

    @Column(nullable = false, length = 1024)
    private String targetQualifiedName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private DependencyType dependencyType;

    @Column(nullable = false)
    @Builder.Default
    private Boolean isExternal = false;

    private Integer sourceLocationLine;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    @Builder.Default
    private ResolutionStatus resolutionStatus = ResolutionStatus.RESOLVED;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant createdAt;
}


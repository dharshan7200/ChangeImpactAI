package com.changeimpact.symbols.entity;

import com.changeimpact.parser.model.ResolutionStatus;
import com.changeimpact.parser.model.SymbolType;
import com.changeimpact.repository.entity.Repository;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * Entity representing an extracted Java code symbol (class, method, field, etc.).
 */
@Entity
@Table(name = "symbols", indexes = {
        @Index(name = "idx_symbols_repo_commit", columnList = "repository_id, commitSha"),
        @Index(name = "idx_symbols_file", columnList = "source_file_id"),
        @Index(name = "idx_symbols_qualified", columnList = "qualifiedName"),
        @Index(name = "idx_symbols_parent", columnList = "parent_symbol_id"),
        @Index(name = "idx_symbols_type", columnList = "symbolType")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class Symbol {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @EqualsAndHashCode.Include
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "repository_id", nullable = false)
    private Repository repository;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "source_file_id", nullable = false)
    private SourceFile sourceFile;

    @Column(nullable = false, length = 255)
    private String commitSha;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private SymbolType symbolType;

    @Column(nullable = false, length = 1024)
    private String qualifiedName;

    @Column(nullable = false, length = 255)
    private String simpleName;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_symbol_id")
    private Symbol parentSymbol;

    @Column(length = 512)
    private String returnType;

    @Column(columnDefinition = "TEXT")
    private String parameters;

    @Column(nullable = false, length = 20)
    @Builder.Default
    private String visibility = "PACKAGE_PRIVATE";

    @Column(nullable = false)
    @Builder.Default
    private Boolean isStatic = false;

    @Column(nullable = false)
    @Builder.Default
    private Boolean isAbstract = false;

    @Column(nullable = false)
    @Builder.Default
    private Boolean isFinal = false;

    @Column(columnDefinition = "TEXT[]")
    private String[] annotations;

    @Column(nullable = false)
    private Integer startLine;

    @Column(nullable = false)
    private Integer endLine;

    private Integer startColumn;
    private Integer endColumn;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    @Builder.Default
    private ResolutionStatus resolutionStatus = ResolutionStatus.RESOLVED;

    /** SHA-256 of the declaration printed without comments (types: header only). */
    @Column(length = 64)
    private String contentHash;

    /** Full annotation expressions, newline separated (e.g. {@code @GetMapping("/owners")}). */
    @Column(columnDefinition = "TEXT")
    private String annotationText;

    /** Cyclomatic complexity for methods/constructors. */
    private Integer complexity;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant createdAt;
}


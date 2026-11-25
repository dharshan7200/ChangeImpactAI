package com.changeimpact.symbols.entity;

import com.changeimpact.repository.entity.Repository;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * Represents an analyzed source file within a specific repository commit.
 */
@Entity
@Table(name = "source_files", indexes = {
        @Index(name = "idx_source_files_repo_commit", columnList = "repository_id, commitSha"),
        @Index(name = "idx_source_files_path", columnList = "relativePath")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class SourceFile {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @EqualsAndHashCode.Include
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "repository_id", nullable = false)
    private Repository repository;

    @Column(nullable = false, length = 255)
    private String commitSha;

    @Column(nullable = false, length = 1024)
    private String relativePath;

    @Column(length = 512)
    private String packageName;

    @Column(nullable = false)
    @Builder.Default
    private Integer linesOfCode = 0;

    @Column(nullable = false)
    @Builder.Default
    private Long sizeBytes = 0L;

    @Column(nullable = false)
    @Builder.Default
    private Boolean isTest = false;
    /** Version of the extraction that produced this commit's rows (see CodeAnalysisService.ANALYZER_VERSION). */
    @Column(name = "analyzer_version", nullable = false)
    @Builder.Default
    private Integer analyzerVersion = 0;

    /** Source language ({@code java}, {@code python}); see LanguageParser. */
    @Column(nullable = false, length = 20)
    @Builder.Default
    private String language = "java";

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant createdAt;
}


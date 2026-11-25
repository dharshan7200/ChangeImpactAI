package com.changeimpact.testing.reports.entity;

import jakarta.persistence.*;
import lombok.*;

import java.util.UUID;

@Entity
@Table(name = "coverage_files")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CoverageFile {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "report_id", nullable = false)
    private UUID reportId;

    /** Package path + file name as JaCoCo reports it, e.g. {@code com/x/Foo.java}. */
    @Column(nullable = false, length = 1024)
    private String sourcePath;

    @Column(nullable = false, columnDefinition = "INT[]")
    private int[] coveredLines;

    @Column(nullable = false, columnDefinition = "INT[]")
    private int[] missedLines;
}

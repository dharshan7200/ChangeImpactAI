package com.changeimpact.testing.reports.entity;

import jakarta.persistence.*;
import lombok.*;

import java.util.UUID;

@Entity
@Table(name = "mutation_results")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MutationResult {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "report_id", nullable = false)
    private UUID reportId;

    @Column(nullable = false, length = 1024)
    private String className;

    @Column(nullable = false)
    private String methodName;

    @Column(length = 1024)
    private String methodDesc;

    private Integer lineNumber;

    private String mutator;

    /** KILLED, SURVIVED, NO_COVERAGE, TIMED_OUT, MEMORY_ERROR, RUN_ERROR */
    @Column(nullable = false, length = 30)
    private String status;

    @Column(columnDefinition = "TEXT")
    private String killingTest;

    @Column(columnDefinition = "TEXT")
    private String description;
}

package com.changeimpact.testing.reports.entity;

import jakarta.persistence.*;
import lombok.*;

import java.util.UUID;

@Entity
@Table(name = "test_executions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TestExecution {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "report_id", nullable = false)
    private UUID reportId;

    @Column(nullable = false, length = 1024)
    private String className;

    @Column(nullable = false, length = 512)
    private String methodName;

    /** PASSED, FAILED, ERROR, SKIPPED */
    @Column(nullable = false, length = 20)
    private String status;

    private Long durationMs;

    @Column(columnDefinition = "TEXT")
    private String message;
}

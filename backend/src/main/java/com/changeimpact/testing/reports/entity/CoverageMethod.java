package com.changeimpact.testing.reports.entity;

import jakarta.persistence.*;
import lombok.*;

import java.util.UUID;

@Entity
@Table(name = "coverage_methods")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CoverageMethod {

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

    private Integer firstLine;

    private int linesCovered;

    private int linesMissed;

    private int branchesCovered;

    private int branchesMissed;
}

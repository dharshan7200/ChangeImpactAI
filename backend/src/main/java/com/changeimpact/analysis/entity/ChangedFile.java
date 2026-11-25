package com.changeimpact.analysis.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "changed_files")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ChangedFile {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "analysis_id", nullable = false)
    private UUID analysisId;

    @Column(nullable = false, length = 1024)
    private String path;

    @Column(length = 1024)
    private String oldPath;

    @Column(nullable = false, length = 20)
    private String changeType;

    @Column(nullable = false, length = 30)
    private String fileCategory;

    private int additions;

    private int deletions;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private List<Map<String, Integer>> hunks;
}

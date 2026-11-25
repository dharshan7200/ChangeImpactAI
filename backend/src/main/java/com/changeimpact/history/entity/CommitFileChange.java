package com.changeimpact.history.entity;

import jakarta.persistence.*;
import lombok.*;

import java.util.UUID;

/** A file touched by a commit (diff against its first parent). Basis for churn and history analytics. */
@Entity
@Table(name = "commit_file_changes")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CommitFileChange {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "repository_id", nullable = false)
    private UUID repositoryId;

    @Column(nullable = false, length = 64)
    private String commitSha;

    @Column(nullable = false, length = 1024)
    private String path;

    @Column(length = 1024)
    private String oldPath;

    @Column(nullable = false, length = 20)
    private String changeType;

    @Column(nullable = false)
    private int additions;

    @Column(nullable = false)
    private int deletions;
}

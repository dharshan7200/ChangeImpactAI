package com.changeimpact.history.repository;

import com.changeimpact.history.entity.CommitFileChange;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Repository
public interface CommitFileChangeRepository extends JpaRepository<CommitFileChange, UUID> {

    /** Rows of [path, commitCount, additions+deletions] for the given paths. */
    @Query("SELECT f.path, COUNT(f), SUM(f.additions + f.deletions) FROM CommitFileChange f " +
           "WHERE f.repositoryId = :repositoryId AND f.path IN :paths GROUP BY f.path")
    List<Object[]> churnForPaths(UUID repositoryId, Collection<String> paths);

    /** Most frequently modified files: rows of [path, commitCount, lineChurn, bugfixCommitCount]. */
    @Query(value = """
            SELECT f.path, COUNT(*) AS commits, SUM(f.additions + f.deletions) AS churn,
                   SUM(CASE WHEN c.is_bugfix THEN 1 ELSE 0 END) AS bugfix_commits
            FROM commit_file_changes f
            JOIN commits c ON c.repository_id = f.repository_id AND c.sha = f.commit_sha
            WHERE f.repository_id = :repositoryId
            GROUP BY f.path
            ORDER BY commits DESC, churn DESC
            LIMIT :limit
            """, nativeQuery = true)
    List<Object[]> topChurnedFiles(UUID repositoryId, int limit);

    boolean existsByRepositoryIdAndCommitSha(UUID repositoryId, String commitSha);
}

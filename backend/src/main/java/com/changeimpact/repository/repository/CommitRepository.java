package com.changeimpact.repository.repository;

import com.changeimpact.repository.entity.Commit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface CommitRepository extends JpaRepository<Commit, UUID> {

    Optional<Commit> findByRepositoryIdAndSha(UUID repositoryId, String sha);

    Page<Commit> findByRepositoryIdOrderByCommitDateDesc(UUID repositoryId, Pageable pageable);

    boolean existsByRepositoryIdAndSha(UUID repositoryId, String sha);

    long countByRepositoryIdAndHistoryScannedTrue(UUID repositoryId);

    /** Rows of [weekStart (timestamptz), commits, bugfixCommits, revertCommits] for the last N weeks. */
    @org.springframework.data.jpa.repository.Query(value = """
            SELECT date_trunc('week', commit_date) AS week, COUNT(*),
                   SUM(CASE WHEN is_bugfix THEN 1 ELSE 0 END), SUM(CASE WHEN is_revert THEN 1 ELSE 0 END)
            FROM commits
            WHERE repository_id = :repositoryId AND commit_date >= NOW() - make_interval(weeks => :weeks)
            GROUP BY week ORDER BY week
            """, nativeQuery = true)
    java.util.List<Object[]> weeklyActivity(UUID repositoryId, int weeks);
}


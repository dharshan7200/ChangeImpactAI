package com.changeimpact.architecture.repository;

import com.changeimpact.architecture.entity.ArchitectureRule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface ArchitectureRuleRepository extends JpaRepository<ArchitectureRule, UUID> {

    /** Rules in effect: enabled global and repository rules, minus global rules the repository switched off. */
    @Query(value = """
            SELECT r.* FROM architecture_rules r
             WHERE (r.repository_id = :repositoryId OR r.repository_id IS NULL) AND r.enabled
               AND NOT EXISTS (SELECT 1 FROM architecture_rule_overrides o
                                WHERE o.rule_id = r.id AND o.repository_id = :repositoryId AND NOT o.enabled)
             ORDER BY r.repository_id NULLS FIRST, r.created_at, r.id""", nativeQuery = true)
    List<ArchitectureRule> findEffectiveRulesForRepository(UUID repositoryId);

    /** Global rules and the repository's own rules (enabled or not), globals first. */
    @Query("SELECT r FROM ArchitectureRule r WHERE r.repository.id = :repositoryId OR r.repository IS NULL ORDER BY r.createdAt, r.id")
    List<ArchitectureRule> findVisibleRules(UUID repositoryId);

    @Query("SELECT r FROM ArchitectureRule r WHERE r.repository IS NULL ORDER BY r.createdAt, r.id")
    List<ArchitectureRule> findGlobalRules();

    long countByRepositoryId(UUID repositoryId);
}


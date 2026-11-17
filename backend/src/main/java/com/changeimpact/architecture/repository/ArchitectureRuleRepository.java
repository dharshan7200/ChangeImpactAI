package com.changeimpact.architecture.repository;

import com.changeimpact.architecture.entity.ArchitectureRule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface ArchitectureRuleRepository extends JpaRepository<ArchitectureRule, UUID> {

    @Query("SELECT r FROM ArchitectureRule r WHERE (r.repository.id = :repositoryId OR r.repository IS NULL) AND r.enabled = true")
    List<ArchitectureRule> findEffectiveRulesForRepository(UUID repositoryId);
}


package com.changeimpact.architecture.service;

import com.changeimpact.architecture.dto.ArchitectureViolationResponse;
import com.changeimpact.architecture.entity.ArchitectureRule;
import com.changeimpact.architecture.entity.ArchitectureViolation;
import com.changeimpact.architecture.repository.ArchitectureRuleRepository;
import com.changeimpact.architecture.repository.ArchitectureViolationRepository;
import com.changeimpact.common.exception.BusinessException;
import com.changeimpact.common.exception.ResourceNotFoundException;
import com.changeimpact.ingestion.workspace.WorkspaceManager;
import com.changeimpact.repository.entity.Repository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.*;

/**
 * Architecture rule management. Global rules (no repository) are defaults for every repository and are edited by
 * administrators; a repository can switch a global rule off for itself. Repository rules belong to the repository's
 * owner. Changes apply to the next indexing/analysis; {@link #evaluate} recomputes the stored violations of the last
 * indexed commit immediately, {@link #preview} shows what a draft rule would report without storing anything.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ArchitectureRuleService {

    public static final int MAX_RULES_PER_REPOSITORY = 50;
    static final int PREVIEW_LIMIT = 200;
    private static final Set<String> SEVERITIES = Set.of("HIGH", "MEDIUM", "LOW");

    private final ArchitectureRuleRepository rules;
    private final ArchitectureViolationRepository violations;
    private final ArchitectureRuleEngine engine;
    private final WorkspaceManager workspaceManager;
    private final TransactionTemplate tx;
    private final JdbcTemplate jdbc;

    public record RuleRequest(String ruleName, String ruleType, String description, String severity,
                              Map<String, Object> configuration, Boolean enabled) {}

    public record RuleView(UUID id, String scope, String ruleName, String ruleType, String description, String severity,
                           Map<String, Object> configuration, boolean enabled, boolean active, Instant createdAt,
                           Instant updatedAt, Long violationsAtLastCommit) {}

    public record Preview(String commitSha, int total, boolean truncated, List<ArchitectureViolationResponse> violations) {}

    public record Evaluation(String commitSha, int rulesInEffect, int violations, Map<String, Integer> byRule) {}

    // ------------------------------------------------------------------ repository scope

    public List<RuleView> list(Repository repo) {
        Map<UUID, Boolean> overrides = overrides(repo.getId());
        Map<UUID, Long> counts = new HashMap<>();
        if (repo.getLastIndexedCommit() != null) {
            jdbc.query("SELECT rule_id, count(*) FROM architecture_violations WHERE repository_id = ? AND commit_sha = ? AND rule_id IS NOT NULL GROUP BY rule_id",
                    rs -> { counts.put(rs.getObject(1, UUID.class), rs.getLong(2)); }, repo.getId(), repo.getLastIndexedCommit());
        }
        List<RuleView> out = new ArrayList<>();
        for (ArchitectureRule r : rules.findVisibleRules(repo.getId())) {
            boolean global = r.getRepository() == null;
            boolean active = r.getEnabled() && (!global || overrides.getOrDefault(r.getId(), true));
            out.add(view(r, active, repo.getLastIndexedCommit() == null ? null : counts.getOrDefault(r.getId(), 0L)));
        }
        return out;
    }

    public RuleView create(Repository repo, RuleRequest req) {
        if (rules.countByRepositoryId(repo.getId()) >= MAX_RULES_PER_REPOSITORY) {
            throw new BusinessException("TOO_MANY_RULES", "A repository can have at most " + MAX_RULES_PER_REPOSITORY + " rules");
        }
        ArchitectureRule r = ArchitectureRule.builder().repository(repo).build();
        apply(r, req, true);
        return view(rules.save(r), r.getEnabled(), null);
    }

    public RuleView update(Repository repo, UUID ruleId, RuleRequest req) {
        ArchitectureRule r = ownRule(repo, ruleId);
        apply(r, req, false);
        return view(rules.save(r), r.getEnabled(), null);
    }

    public void delete(Repository repo, UUID ruleId) {
        rules.delete(ownRule(repo, ruleId));
    }

    /** Switches a rule on or off for this repository (global rules: a per-repository override). */
    public RuleView setActive(Repository repo, UUID ruleId, boolean active) {
        ArchitectureRule r = rules.findById(ruleId).orElseThrow(() -> new ResourceNotFoundException("ArchitectureRule", ruleId));
        if (r.getRepository() == null) {
            jdbc.update("""
                    INSERT INTO architecture_rule_overrides (repository_id, rule_id, enabled) VALUES (?, ?, ?)
                    ON CONFLICT (repository_id, rule_id) DO UPDATE SET enabled = EXCLUDED.enabled, updated_at = NOW()""",
                    repo.getId(), ruleId, active);
            return view(r, r.getEnabled() && active, null);
        }
        ArchitectureRule own = ownRule(repo, ruleId);
        own.setEnabled(active);
        return view(rules.save(own), active, null);
    }

    /** What a draft rule reports on the last indexed commit (nothing is stored). */
    public Preview preview(Repository repo, RuleRequest req) {
        String commit = indexedCommit(repo);
        ArchitectureRule draft = ArchitectureRule.builder().repository(repo).build();
        apply(draft, req, true);
        List<ArchitectureViolation> found;
        try {
            found = engine.preview(repo, commit, draft);
        } catch (BoundedPattern.BudgetExceeded e) {
            throw new BusinessException("INVALID_RULE", e.getMessage());
        }
        List<ArchitectureViolationResponse> shown = found.stream().limit(PREVIEW_LIMIT).map(ArchitectureViolationResponse::from).toList();
        return new Preview(commit, found.size(), found.size() > PREVIEW_LIMIT, shown);
    }

    /** Recomputes the stored violations of the last indexed commit with the rules now in effect. */
    public Evaluation evaluate(Repository repo) {
        String commit = indexedCommit(repo);
        try {
            return workspaceManager.withRepositoryLock(repo.getId(), 1, () -> tx.execute(status -> {
                violations.deleteByRepositoryIdAndCommitSha(repo.getId(), commit);
                List<ArchitectureViolation> found = engine.analyzeArchitecture(repo, commit);
                // the stored architecture snapshot of this commit carries the violation count: rebuild it on next view
                jdbc.update("DELETE FROM architecture_snapshots WHERE repository_id = ? AND commit_sha = ?", repo.getId(), commit);
                Map<String, Integer> byRule = new TreeMap<>();
                found.forEach(v -> byRule.merge(v.getRuleName(), 1, Integer::sum));
                return new Evaluation(commit, rules.findEffectiveRulesForRepository(repo.getId()).size(), found.size(), byRule);
            }));
        } catch (com.changeimpact.common.exception.IngestionException e) { // lock not obtained within a minute
            throw new BusinessException("REPOSITORY_BUSY", "The repository is busy with another job; try again when it has finished");
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ------------------------------------------------------------------ global scope (administrators)

    public List<RuleView> listGlobal() {
        return rules.findGlobalRules().stream().map(r -> view(r, r.getEnabled(), null)).toList();
    }

    public RuleView createGlobal(RuleRequest req) {
        ArchitectureRule r = ArchitectureRule.builder().build();
        apply(r, req, true);
        return view(rules.save(r), r.getEnabled(), null);
    }

    public RuleView updateGlobal(UUID ruleId, RuleRequest req) {
        ArchitectureRule r = globalRule(ruleId);
        apply(r, req, false);
        return view(rules.save(r), r.getEnabled(), null);
    }

    public void deleteGlobal(UUID ruleId) {
        rules.delete(globalRule(ruleId));
    }

    // ------------------------------------------------------------------ helpers

    private ArchitectureRule ownRule(Repository repo, UUID ruleId) {
        ArchitectureRule r = rules.findById(ruleId).orElseThrow(() -> new ResourceNotFoundException("ArchitectureRule", ruleId));
        if (r.getRepository() == null) {
            throw new BusinessException("GLOBAL_RULE_READ_ONLY", "Global default rules are managed by administrators; switch it off for this repository instead");
        }
        if (!r.getRepository().getId().equals(repo.getId())) throw new ResourceNotFoundException("ArchitectureRule", ruleId);
        return r;
    }

    private ArchitectureRule globalRule(UUID ruleId) {
        ArchitectureRule r = rules.findById(ruleId).orElseThrow(() -> new ResourceNotFoundException("ArchitectureRule", ruleId));
        if (r.getRepository() != null) throw new ResourceNotFoundException("ArchitectureRule", ruleId);
        return r;
    }

    private static String indexedCommit(Repository repo) {
        if (repo.getLastIndexedCommit() == null) throw new BusinessException("NOT_INDEXED", "Index the repository first");
        return repo.getLastIndexedCommit();
    }

    /** Validates and copies a request onto a rule; {@code full}: all required fields must be present (create). */
    static void apply(ArchitectureRule r, RuleRequest req, boolean full) {
        if (req == null) throw new BusinessException("INVALID_RULE", "Rule definition missing");
        String name = req.ruleName() == null ? (full ? null : r.getRuleName()) : req.ruleName().strip();
        if (name == null || name.isEmpty() || name.length() > 120) throw new BusinessException("INVALID_RULE", "ruleName: 1–120 characters");
        String type = req.ruleType() == null ? (full ? null : r.getRuleType()) : req.ruleType().strip().toUpperCase(Locale.ROOT);
        String severity = req.severity() == null ? (r.getSeverity() == null ? "MEDIUM" : r.getSeverity()) : req.severity().strip().toUpperCase(Locale.ROOT);
        if (!SEVERITIES.contains(severity)) throw new BusinessException("INVALID_RULE", "severity must be HIGH, MEDIUM or LOW");
        String description = req.description() == null ? r.getDescription() : req.description().strip();
        if (description != null && description.length() > 1000) throw new BusinessException("INVALID_RULE", "description: at most 1000 characters");
        Map<String, Object> config = req.configuration() == null && !full ? r.getConfiguration() : req.configuration();
        Map<String, Object> normalised;
        try {
            normalised = ArchitectureRuleEngine.validate(type, config);
        } catch (IllegalArgumentException e) {
            throw new BusinessException("INVALID_RULE", e.getMessage());
        }
        r.setRuleName(name);
        r.setRuleType(type);
        r.setSeverity(severity);
        r.setDescription(description);
        r.setConfiguration(normalised);
        r.setEnabled(req.enabled() == null ? (r.getEnabled() == null || r.getEnabled()) : req.enabled());
    }

    private Map<UUID, Boolean> overrides(UUID repositoryId) {
        Map<UUID, Boolean> m = new HashMap<>();
        jdbc.query("SELECT rule_id, enabled FROM architecture_rule_overrides WHERE repository_id = ?",
                rs -> { m.put(rs.getObject(1, UUID.class), rs.getBoolean(2)); }, repositoryId);
        return m;
    }

    private static RuleView view(ArchitectureRule r, boolean active, Long count) {
        return new RuleView(r.getId(), r.getRepository() == null ? "GLOBAL" : "REPOSITORY", r.getRuleName(), r.getRuleType(),
                r.getDescription(), r.getSeverity(), r.getConfiguration() == null ? Map.of() : r.getConfiguration(),
                Boolean.TRUE.equals(r.getEnabled()), active, r.getCreatedAt(), r.getUpdatedAt(), count);
    }
}

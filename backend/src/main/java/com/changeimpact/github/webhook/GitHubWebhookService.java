package com.changeimpact.github.webhook;

import com.changeimpact.analysis.entity.ChangeAnalysis;
import com.changeimpact.analysis.repository.ChangeAnalysisRepository;
import com.changeimpact.analysis.service.ChangeAnalysisService;
import com.changeimpact.common.config.AppProperties;
import com.changeimpact.common.exception.BusinessException;
import com.changeimpact.github.app.CheckRunPublisher;
import com.changeimpact.github.app.GitHubAppInstallation;
import com.changeimpact.github.app.GitHubAppInstallationRepository;
import com.changeimpact.github.app.PullRequest;
import com.changeimpact.github.app.PullRequestRepository;
import com.changeimpact.repository.entity.Repository;
import com.changeimpact.repository.repository.RepositoryRepository;
import com.changeimpact.repository.service.RepositoryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Handles verified GitHub App webhook deliveries.
 *
 * <ul>
 *   <li>{@code pull_request} opened / synchronize / reopened / ready_for_review → pull request upserted, analysis of
 *       merge-base..head queued, Check Run created as {@code queued} (first connected copy of the repository only, so a
 *       pull request never gets duplicate check runs). Drafts are skipped unless configured.</li>
 *   <li>{@code pull_request} closed / edited → pull request state updated.</li>
 *   <li>{@code push} to the default branch → the repository is re-indexed so later analyses use a current graph.</li>
 *   <li>{@code installation}, {@code installation_repositories} → installation and its repositories tracked.</li>
 *   <li>{@code ping} → acknowledged.</li>
 * </ul>
 * Every delivery is recorded once (X-GitHub-Delivery is unique); repeated deliveries are acknowledged and skipped.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class GitHubWebhookService {

    private static final Set<String> ANALYZE_ACTIONS = Set.of("opened", "synchronize", "reopened", "ready_for_review");
    private static final Pattern FULL_NAME = Pattern.compile("^[A-Za-z0-9_.-]{1,100}/[A-Za-z0-9_.-]{1,100}$");
    private static final Pattern SHA = Pattern.compile("^[0-9a-f]{40}$");

    private final AppProperties appProperties;
    private final WebhookDeliveryRepository deliveryRepository;
    private final GitHubAppInstallationRepository installationRepository;
    private final PullRequestRepository pullRequestRepository;
    private final RepositoryRepository repositoryRepository;
    private final RepositoryService repositoryService;
    private final ChangeAnalysisService changeAnalysisService;
    private final ChangeAnalysisRepository analysisRepository;
    private final CheckRunPublisher checkRunPublisher;
    private final TransactionTemplate transactionTemplate;

    public record Outcome(String status, String detail, List<String> analysisIds) {
        static Outcome ignored(String detail) {
            return new Outcome("IGNORED", detail, List.of());
        }
    }

    /**
     * Records the delivery; returns false when it was already received (duplicate). A GitHub redelivery reuses the
     * delivery id: deliveries that FAILED, or that are stuck in PROCESSING (e.g. after a crash), are processed again.
     */
    public boolean register(String deliveryId, String event, String payloadHash) {
        try {
            return Boolean.TRUE.equals(transactionTemplate.execute(s -> {
                Optional<WebhookDelivery> existing = deliveryRepository.findByDeliveryId(deliveryId);
                if (existing.isPresent()) {
                    WebhookDelivery d = existing.get();
                    boolean stale = "PROCESSING".equals(d.getStatus()) && d.getReceivedAt() != null
                            && d.getReceivedAt().isBefore(Instant.now().minusSeconds(600));
                    if (!"FAILED".equals(d.getStatus()) && !stale) return false;
                    d.setStatus("PROCESSING");
                    d.setError(null);
                    d.setProcessed(false);
                    d.setDetail("redelivery");
                    deliveryRepository.save(d);
                    return true;
                }
                deliveryRepository.saveAndFlush(WebhookDelivery.builder().deliveryId(deliveryId).event(event)
                        .payloadHash(payloadHash).status("PROCESSING").build());
                return true;
            }));
        } catch (DataIntegrityViolationException e) {
            return false; // concurrent redelivery won the unique constraint
        }
    }

    public Outcome process(String deliveryId, String event, JsonNode payload) {
        Outcome outcome;
        try {
            outcome = switch (event) {
                case "ping" -> new Outcome("ACCEPTED", "pong" + (payload.path("hook_id").isMissingNode() ? "" : " (hook " + payload.path("hook_id").asLong() + ")"), List.of());
                case "pull_request" -> pullRequest(payload);
                case "push" -> push(payload);
                case "installation" -> installation(payload);
                case "installation_repositories" -> installationRepositories(payload);
                default -> Outcome.ignored("Event '" + event + "' is not used by ChangeImpact AI");
            };
        } catch (Exception e) {
            log.warn("Webhook delivery {} ({}) failed: {}", deliveryId, event, e.getMessage(), e);
            outcome = new Outcome("FAILED", e.getMessage(), List.of());
        }
        Outcome result = outcome;
        com.changeimpact.common.metrics.PlatformMetrics.webhook(event, result.status());
        transactionTemplate.executeWithoutResult(s -> deliveryRepository.findByDeliveryId(deliveryId).ifPresent(d -> {
            d.setAction(cut(payload.path("action").asString(null), 100));
            d.setRepositoryFullName(cut(payload.path("repository").path("full_name").asString(null), 512));
            if (payload.path("installation").path("id").isNumber()) d.setInstallationId(payload.path("installation").path("id").asLong());
            d.setStatus(result.status());
            if ("FAILED".equals(result.status())) d.setError(result.detail()); else d.setDetail(result.detail());
            d.setAnalysisIds(result.analysisIds());
            d.setProcessed(true);
            d.setProcessedAt(Instant.now());
            deliveryRepository.save(d);
        }));
        return result;
    }

    // ------------------------------------------------------------------ pull_request

    private Outcome pullRequest(JsonNode p) {
        String action = p.path("action").asString("");
        JsonNode pr = p.path("pull_request");
        String fullName = p.path("repository").path("full_name").asString("");
        int number = pr.path("number").asInt(p.path("number").asInt(0));
        String baseSha = pr.path("base").path("sha").asString("");
        String headSha = pr.path("head").path("sha").asString("");
        if (!FULL_NAME.matcher(fullName).matches() || number <= 0 || !SHA.matcher(baseSha).matches() || !SHA.matcher(headSha).matches()) {
            return new Outcome("FAILED", "Malformed pull_request payload", List.of());
        }
        List<Repository> repos = repositoryRepository.findByFullNameIgnoreCaseAndDeletedAtIsNullOrderByCreatedAtAsc(fullName);
        if (repos.isEmpty()) return Outcome.ignored("Repository " + fullName + " is not connected in ChangeImpact AI");
        Long installationId = p.path("installation").path("id").isNumber() ? p.path("installation").path("id").asLong() : null;
        if (installationId != null) trackInstallationFromEvent(installationId, p);

        boolean draft = pr.path("draft").asBoolean(false);
        boolean analyze = ANALYZE_ACTIONS.contains(action) && "open".equals(pr.path("state").asString(""))
                && (!draft || appProperties.getGithub().isAnalyzeDraftPullRequests());
        List<String> analyses = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        for (int i = 0; i < repos.size(); i++) {
            UUID repoId = repos.get(i).getId();
            boolean publishChecks = i == 0 && installationId != null;
            String note = transactionTemplate.execute(s -> {
                Repository repo = repositoryRepository.findById(repoId).orElseThrow();
                PullRequest row = upsertPullRequest(repo.getId(), number, pr);
                if (!analyze) {
                    return ANALYZE_ACTIONS.contains(action) && draft ? "draft pull request skipped" : "action '" + action + "' recorded";
                }
                Map<String, Object> github = new LinkedHashMap<>();
                github.put("repository", fullName);
                github.put("pullNumber", number);
                github.put("headSha", headSha);
                if (installationId != null) github.put("installationId", installationId);
                Optional<ChangeAnalysis> created = changeAnalysisService.createForPullRequest(repo, row.getId(), number, baseSha, headSha, github);
                if (created.isEmpty()) return "analysis of " + headSha.substring(0, 7) + " already in progress";
                ChangeAnalysis analysis = created.get();
                analyses.add(analysis.getId().toString());
                row.setLastAnalysisId(analysis.getId());
                row.setRiskLevel(null);
                row.setRiskScore(null);
                row.setCheckConclusion(null);
                row.setCheckError(null);
                row.setCheckRunId(null);
                if (!publishChecks || !checkRunPublisher.enabled()) {
                    row.setCheckStatus(publishChecks || installationId == null ? "NOT_CONFIGURED" : "PUBLISHED_BY_OTHER_COPY");
                } else {
                    // created before commit, so the worker always sees the check run id
                    String[] error = new String[1];
                    Optional<Long> checkRun = checkRunPublisher.createQueued(
                            new CheckRunPublisher.Target(installationId, fullName, number, headSha), analysis.getId(), error);
                    if (checkRun.isPresent()) {
                        Map<String, Object> options = new LinkedHashMap<>(analysis.getOptions());
                        @SuppressWarnings("unchecked")
                        Map<String, Object> gh = new LinkedHashMap<>((Map<String, Object>) options.get("github"));
                        gh.put("checkRunId", checkRun.get());
                        options.put("github", gh);
                        analysis.setOptions(options);
                        analysisRepository.save(analysis);
                        row.setCheckRunId(checkRun.get());
                        row.setCheckStatus("QUEUED");
                    } else {
                        row.setCheckStatus("FAILED_TO_PUBLISH");
                        row.setCheckError(error[0]);
                    }
                }
                pullRequestRepository.save(row);
                return "analysis " + analysis.getId() + " queued";
            });
            notes.add(repoId + ": " + note);
        }
        return new Outcome(analyses.isEmpty() ? "IGNORED" : "ACCEPTED",
                "#" + number + " " + action + " → " + String.join("; ", notes), analyses);
    }

    private PullRequest upsertPullRequest(UUID repositoryId, int number, JsonNode pr) {
        PullRequest row = pullRequestRepository.findByRepositoryIdAndNumber(repositoryId, number)
                .orElseGet(() -> PullRequest.builder().repositoryId(repositoryId).number(number).build());
        row.setTitle(cut(pr.path("title").asString(null), 1024));
        row.setAuthor(cut(pr.path("user").path("login").asString(null), 255));
        row.setUrl(cut(pr.path("html_url").asString(null), 1024));
        String state = pr.path("merged").asBoolean(false) ? "merged" : pr.path("state").asString("open");
        row.setState(cut(state, 20));
        row.setDraft(pr.path("draft").asBoolean(false));
        row.setBaseRef(cut(pr.path("base").path("ref").asString(null), 255));
        row.setBaseSha(pr.path("base").path("sha").asString(null));
        row.setHeadRef(cut(pr.path("head").path("ref").asString(null), 255));
        row.setHeadSha(pr.path("head").path("sha").asString(null));
        return pullRequestRepository.save(row);
    }

    // ------------------------------------------------------------------ push

    private Outcome push(JsonNode p) {
        String fullName = p.path("repository").path("full_name").asString("");
        String ref = p.path("ref").asString("");
        if (p.path("deleted").asBoolean(false)) return Outcome.ignored("Branch deletion");
        List<Repository> repos = repositoryRepository.findByFullNameIgnoreCaseAndDeletedAtIsNullOrderByCreatedAtAsc(fullName);
        if (repos.isEmpty()) return Outcome.ignored("Repository " + fullName + " is not connected in ChangeImpact AI");
        List<String> notes = new ArrayList<>();
        boolean started = false;
        for (Repository repo : repos) {
            String branch = repo.getDefaultBranch() != null ? repo.getDefaultBranch() : "main";
            if (!ref.equals("refs/heads/" + branch)) {
                notes.add(repo.getId() + ": push to " + ref + " (default branch is " + branch + ")");
                continue;
            }
            UUID ownerId = transactionTemplate.execute(s -> repositoryRepository.findById(repo.getId()).map(r -> r.getOwner().getId()).orElse(null));
            try {
                UUID jobId = repositoryService.triggerIndexing(ownerId, repo.getId(), null);
                notes.add(repo.getId() + ": re-index job " + jobId);
                started = true;
            } catch (BusinessException e) {
                notes.add(repo.getId() + ": " + e.getMessage());
            }
        }
        return new Outcome(started ? "ACCEPTED" : "IGNORED", String.join("; ", notes), List.of());
    }

    // ------------------------------------------------------------------ installations

    private Outcome installation(JsonNode p) {
        String action = p.path("action").asString("");
        JsonNode inst = p.path("installation");
        if (!inst.path("id").isNumber()) return new Outcome("FAILED", "Malformed installation payload", List.of());
        long id = inst.path("id").asLong();
        return transactionTemplate.execute(s -> {
            if ("deleted".equals(action)) {
                installationRepository.findByInstallationId(id).ifPresent(installationRepository::delete);
                return new Outcome("ACCEPTED", "installation " + id + " removed", List.of());
            }
            GitHubAppInstallation row = installationRepository.findByInstallationId(id)
                    .orElseGet(() -> GitHubAppInstallation.builder().installationId(id).repositories(new ArrayList<>()).build());
            row.setAccountLogin(cut(inst.path("account").path("login").asString("unknown"), 255));
            row.setAccountType(cut(inst.path("account").path("type").asString(null), 30));
            row.setRepositorySelection(cut(inst.path("repository_selection").asString(row.getRepositorySelection()), 20));
            if ("suspend".equals(action)) row.setSuspended(true);
            if ("unsuspend".equals(action) || "created".equals(action)) row.setSuspended(false);
            if (p.path("repositories").isArray()) {
                List<String> repos = new ArrayList<>();
                p.path("repositories").forEach(r -> repos.add(r.path("full_name").asString()));
                row.setRepositories(repos);
            }
            installationRepository.save(row);
            return new Outcome("ACCEPTED", "installation " + id + " " + action + " (" + row.getAccountLogin() + ")", List.of());
        });
    }

    private Outcome installationRepositories(JsonNode p) {
        JsonNode inst = p.path("installation");
        if (!inst.path("id").isNumber()) return new Outcome("FAILED", "Malformed installation payload", List.of());
        long id = inst.path("id").asLong();
        return transactionTemplate.execute(s -> {
            GitHubAppInstallation row = installationRepository.findByInstallationId(id)
                    .orElseGet(() -> GitHubAppInstallation.builder().installationId(id)
                            .accountLogin(cut(inst.path("account").path("login").asString("unknown"), 255))
                            .repositories(new ArrayList<>()).build());
            Set<String> repos = new LinkedHashSet<>(row.getRepositories() != null ? row.getRepositories() : List.of());
            p.path("repositories_added").forEach(r -> repos.add(r.path("full_name").asString()));
            p.path("repositories_removed").forEach(r -> repos.remove(r.path("full_name").asString()));
            row.setRepositories(new ArrayList<>(repos));
            if (p.path("repository_selection").isString()) row.setRepositorySelection(cut(p.path("repository_selection").asString(), 20));
            installationRepository.save(row);
            return new Outcome("ACCEPTED", "installation " + id + ": " + repos.size() + " repositor(ies)", List.of());
        });
    }

    /** Installations created before ChangeImpact AI received the installation event are learned from later events. */
    private void trackInstallationFromEvent(long installationId, JsonNode p) {
        String fullName = p.path("repository").path("full_name").asString("");
        transactionTemplate.executeWithoutResult(s -> {
            GitHubAppInstallation row = installationRepository.findByInstallationId(installationId)
                    .orElseGet(() -> GitHubAppInstallation.builder().installationId(installationId)
                            .accountLogin(cut(p.path("repository").path("owner").path("login").asString("unknown"), 255))
                            .repositories(new ArrayList<>()).build());
            List<String> repos = new ArrayList<>(row.getRepositories() != null ? row.getRepositories() : List.of());
            if (!fullName.isEmpty() && repos.stream().noneMatch(fullName::equalsIgnoreCase)) {
                repos.add(fullName);
                row.setRepositories(repos);
                installationRepository.save(row);
            } else if (row.getId() == null) {
                installationRepository.save(row);
            }
        });
    }

    private static String cut(String v, int max) {
        return v == null || v.length() <= max ? v : v.substring(0, max);
    }
}

package com.changeimpact.admin;

import com.changeimpact.common.config.AppProperties;
import com.changeimpact.ingestion.workspace.WorkspaceManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;

/**
 * Data retention: removes old analyses (cascading to their findings, test and security results), webhook deliveries,
 * quality reports (keeping the latest of each type per repository), workspaces of deleted repositories and leftover
 * commit snapshots. Runs daily (app.retention.cron) and on demand by an administrator.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RetentionService {

    private final AppProperties appProperties;
    private final JdbcTemplate jdbc;
    private final WorkspaceManager workspaceManager;
    private final TransactionTemplate transactionTemplate;

    @Scheduled(cron = "${app.retention.cron:0 30 3 * * *}")
    public void scheduled() {
        if (appProperties.getRetention().isEnabled()) {
            log.info("Retention run: {}", run());
        }
    }

    public Map<String, Object> run() {
        AppProperties.Retention r = appProperties.getRetention();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("analyses", r.getAnalysesDays() <= 0 ? 0 : transactionTemplate.execute(s -> jdbc.update(
                "DELETE FROM change_analyses WHERE status IN ('COMPLETED','FAILED') AND created_at < now() - make_interval(days => ?)",
                r.getAnalysesDays())));
        out.put("webhookDeliveries", r.getWebhookDeliveriesDays() <= 0 ? 0 : transactionTemplate.execute(s -> jdbc.update(
                "DELETE FROM webhook_deliveries WHERE received_at < now() - make_interval(days => ?)", r.getWebhookDeliveriesDays())));
        out.put("qualityReports", r.getQualityReportsDays() <= 0 ? 0 : transactionTemplate.execute(s -> jdbc.update("""
                DELETE FROM quality_reports q
                 WHERE q.created_at < now() - make_interval(days => ?)
                   AND q.id <> (SELECT l.id FROM quality_reports l
                                 WHERE l.repository_id = q.repository_id AND l.report_type = q.report_type
                                 ORDER BY l.created_at DESC LIMIT 1)""", r.getQualityReportsDays())));
        out.put("deletedRepositoryWorkspaces", deleteWorkspacesOfDeletedRepositories());
        out.put("staleSnapshots", deleteStaleSnapshots(r.getStaleSnapshotHours()));
        return out;
    }

    private int deleteWorkspacesOfDeletedRepositories() {
        int n = 0;
        for (UUID id : jdbc.queryForList("SELECT id FROM repositories WHERE deleted_at IS NOT NULL", UUID.class)) {
            Path ws = workspaceManager.getWorkspacePath(id);
            if (Files.isDirectory(ws)) {
                FileUtils.deleteQuietly(ws.toFile());
                n++;
            }
        }
        return n;
    }

    /** {@code <base>/<repositoryId>.snapshots/<sha>} directories older than the limit. */
    private int deleteStaleSnapshots(int hours) {
        if (hours <= 0) return 0;
        Path base = workspaceManager.getWorkspacePath(UUID.randomUUID()).getParent();
        File[] roots = base == null ? null : base.toFile().listFiles(f -> f.isDirectory() && f.getName().endsWith(".snapshots"));
        if (roots == null) return 0;
        long cutoff = Instant.now().minusSeconds(hours * 3600L).toEpochMilli();
        int n = 0;
        for (File root : roots) {
            File[] snapshots = root.listFiles(File::isDirectory);
            if (snapshots == null) continue;
            for (File s : snapshots) {
                if (s.lastModified() < cutoff) {
                    FileUtils.deleteQuietly(s);
                    n++;
                }
            }
        }
        return n;
    }
}

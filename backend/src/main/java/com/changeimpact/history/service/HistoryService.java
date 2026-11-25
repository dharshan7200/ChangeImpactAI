package com.changeimpact.history.service;

import com.changeimpact.diff.model.FileChange;
import com.changeimpact.diff.service.GitDiffService;
import com.changeimpact.history.entity.CommitFileChange;
import com.changeimpact.history.repository.CommitFileChangeRepository;
import com.changeimpact.repository.entity.Commit;
import com.changeimpact.repository.repository.CommitRepository;
import com.changeimpact.repository.repository.RepositoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.revwalk.RevCommit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.regex.Pattern;

/**
 * Collects historical change intelligence from the Git log: per-commit file changes, line churn,
 * merge / revert / bug-fix flags.
 *
 * <p>Revert and bug-fix flags are <b>heuristic labels derived from commit messages</b>. They are stored
 * so that a future, properly evaluated risk model can use them, but they are not ground truth and
 * are never presented as such. Merge commits are recorded but their file changes are not counted
 * (equivalent to {@code git log --no-merges}), so churn is not inflated by merges.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class HistoryService {

    private static final Pattern REVERT = Pattern.compile("(?is)^\\s*revert\\b|this reverts commit [0-9a-f]{7,40}");
    private static final Pattern BUGFIX = Pattern.compile(
            "(?i)\\b(fix(es|ed|ing)?|bug(fix)?s?|hot-?fix|patch(es|ed)?|regression|defect|crash(es)?|issue\\s*#\\d+)\\b");

    private final CommitRepository commitRepository;
    private final CommitFileChangeRepository fileChangeRepository;
    private final RepositoryRepository repositoryRepository;
    private final GitDiffService gitDiffService;
    private final org.springframework.transaction.support.TransactionTemplate transactionTemplate;

    /** Stop after this many consecutive per-commit failures (e.g. database unavailable). */
    private static final int MAX_CONSECUTIVE_FAILURES = 5;

    public record ChurnStats(long commits, long changedLines) {}

    public record ScanResult(int scannedCommits, int newCommits) {}

    /**
     * Scans up to {@code maxCommits} commits reachable from {@code headSha}. Already scanned commits are
     * skipped, so repeated scans are cheap. Each commit is stored in its own transaction and failures are
     * contained: history is supplementary data and must never fail indexing or an analysis.
     */
    public ScanResult scan(Git git, UUID repositoryId, String headSha, int maxCommits) {
        int scanned = 0;
        int added = 0;
        int consecutiveFailures = 0;
        try {
            Iterable<RevCommit> commits = git.log().add(git.getRepository().resolve(headSha)).setMaxCount(maxCommits).call();
            for (RevCommit rev : commits) {
                scanned++;
                try {
                    Boolean isNew = transactionTemplate.execute(status -> {
                        try {
                            return scanCommit(git, repositoryId, rev);
                        } catch (java.io.IOException e) {
                            throw new java.io.UncheckedIOException(e);
                        }
                    });
                    if (Boolean.TRUE.equals(isNew)) {
                        added++;
                    }
                    consecutiveFailures = 0;
                } catch (Exception e) {
                    log.warn("History scan skipped commit {}: {}", rev.name(), e.getMessage());
                    if (++consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                        log.warn("History scan for repository {} aborted after {} consecutive failures", repositoryId, consecutiveFailures);
                        break;
                    }
                }
            }
        } catch (Exception e) {
            log.warn("History scan for repository {} stopped early: {}", repositoryId, e.getMessage());
        }
        log.info("History scan for repository {}: {} commits visited, {} new", repositoryId, scanned, added);
        return new ScanResult(scanned, added);
    }

    /** @return true when the commit row was newly created */
    private boolean scanCommit(Git git, UUID repositoryId, RevCommit rev) throws java.io.IOException {
        Commit commit = commitRepository.findByRepositoryIdAndSha(repositoryId, rev.name()).orElse(null);
        if (commit != null && Boolean.TRUE.equals(commit.getHistoryScanned())) {
            return false;
        }
        boolean isNew = commit == null;
        if (isNew) {
            commit = newCommit(repositoryRepository.getReferenceById(repositoryId), rev);
        }
        String message = rev.getFullMessage();
        boolean merge = rev.getParentCount() > 1;
        commit.setIsMerge(merge);
        commit.setIsRevert(isRevert(message));
        commit.setIsBugfix(!merge && isBugfix(message));

        if (!merge && !fileChangeRepository.existsByRepositoryIdAndCommitSha(repositoryId, rev.name())) {
            List<FileChange> changes = gitDiffService.diffAgainstParent(git.getRepository(), rev);
            int additions = 0;
            int deletions = 0;
            int addedFiles = 0;
            int modifiedFiles = 0;
            int deletedFiles = 0;
            List<CommitFileChange> rows = new ArrayList<>();
            Set<String> seenPaths = new HashSet<>();
            for (FileChange fc : changes) {
                additions += fc.additions();
                deletions += fc.deletions();
                switch (fc.changeType()) {
                    case "ADDED", "COPIED" -> addedFiles++;
                    case "DELETED" -> deletedFiles++;
                    default -> modifiedFiles++;
                }
                if (seenPaths.add(fc.path()) && fc.path().length() <= 1024) {
                    rows.add(CommitFileChange.builder()
                            .repositoryId(repositoryId)
                            .commitSha(rev.name())
                            .path(fc.path())
                            .oldPath(fc.oldPath())
                            .changeType(fc.changeType())
                            .additions(fc.additions())
                            .deletions(fc.deletions())
                            .build());
                }
            }
            fileChangeRepository.saveAll(rows);
            commit.setAdditions(additions);
            commit.setDeletions(deletions);
            commit.setAddedFiles(addedFiles);
            commit.setModifiedFiles(modifiedFiles);
            commit.setDeletedFiles(deletedFiles);
        }
        commit.setHistoryScanned(true);
        commitRepository.save(commit);
        return isNew;
    }

    /** Commits that touched each path within the scanned history window. */
    @Transactional(readOnly = true)
    public Map<String, ChurnStats> churn(UUID repositoryId, Collection<String> paths) {
        Map<String, ChurnStats> result = new HashMap<>();
        if (paths.isEmpty()) {
            return result;
        }
        for (Object[] row : fileChangeRepository.churnForPaths(repositoryId, paths)) {
            result.put((String) row[0], new ChurnStats(((Number) row[1]).longValue(),
                    row[2] != null ? ((Number) row[2]).longValue() : 0L));
        }
        return result;
    }

    public static boolean isRevert(String message) {
        return message != null && REVERT.matcher(message).find();
    }

    public static boolean isBugfix(String message) {
        if (message == null) {
            return false;
        }
        String subject = message.lines().findFirst().orElse("");
        return BUGFIX.matcher(subject).find();
    }

    private static Commit newCommit(com.changeimpact.repository.entity.Repository repository, RevCommit rev) {
        List<String> parents = new ArrayList<>();
        for (RevCommit p : rev.getParents()) {
            parents.add(p.name());
        }
        return Commit.builder()
                .repository(repository)
                .sha(rev.name())
                .message(rev.getFullMessage())
                .authorName(rev.getAuthorIdent().getName())
                .authorEmail(rev.getAuthorIdent().getEmailAddress())
                .authorDate(rev.getAuthorIdent().getWhenAsInstant())
                .committerName(rev.getCommitterIdent().getName())
                .committerEmail(rev.getCommitterIdent().getEmailAddress())
                .commitDate(rev.getCommitterIdent().getWhenAsInstant())
                .parentShas(parents.toArray(new String[0]))
                .build();
    }
}

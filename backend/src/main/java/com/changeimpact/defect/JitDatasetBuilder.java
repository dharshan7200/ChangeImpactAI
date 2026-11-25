package com.changeimpact.defect;

import com.changeimpact.history.service.HistoryService;
import org.eclipse.jgit.diff.*;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevSort;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.AbstractTreeIterator;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.treewalk.EmptyTreeIterator;
import org.eclipse.jgit.util.io.DisabledOutputStream;

import java.util.*;

/**
 * Builds a just-in-time defect dataset straight from Git history: one sample per non-merge commit that changes source
 * code, with the change-level features of Kamei et al. (2013). Every feature of a commit is computed only from that
 * commit and the history <em>before</em> it (commits are processed oldest first), so no information from the future
 * leaks into a sample. For bug-fix commits (message heuristic) the source lines they delete or modify are kept, which
 * {@link SzzLabeler} traces back to the commits that introduced them.
 *
 * <p>Features: NS (subsystems = top-level directories), ND (directories), NF (files), ENTROPY (distribution of the
 * change over its files, normalised), LA / LD (lines added / deleted), LT (lines of the touched files before the
 * change), FIX (the commit is a bug fix), NDEV (distinct earlier authors of the files), AGE (mean days since the files'
 * last change), NUC (distinct earlier commits that changed the files), EXP / REXP / SEXP (author experience: earlier
 * commits, recency-weighted, and in the same subsystems).
 */
public final class JitDatasetBuilder {

    public static final List<String> FEATURES = List.of(
            "NS", "ND", "NF", "ENTROPY", "LA", "LD", "LT", "FIX", "NDEV", "AGE", "NUC", "EXP", "REXP", "SEXP");

    /** Source files considered (configuration, documentation and data files are not). */
    static final Set<String> CODE_EXTENSIONS = Set.of("java", "kt", "kts", "scala", "groovy", "py", "js", "jsx", "ts",
            "tsx", "go", "rb", "php", "c", "h", "cc", "cpp", "hpp", "cs", "swift", "rs");

    /** Fixes that change more files than this are treated as refactorings / bulk changes and not traced (SZZ noise). */
    static final int MAX_FIX_FILES = 30;
    static final int MAX_FIX_LINES = 400;

    /** One commit: features, whether it is a fix, and (for fixes) the old-side source lines it deleted or modified. */
    public record Sample(String sha, long time, String author, String parent, boolean fix, double[] features,
                        Map<String, List<Integer>> fixedLines, String subject) {}

    private record FileState(int lines, long lastChange, Set<String> authors, Set<String> commits) {}

    private final Map<String, FileState> files = new HashMap<>();
    private final Map<String, List<Long>> authorCommitTimes = new HashMap<>();
    private final Map<String, Map<String, Integer>> authorSubsystemCommits = new HashMap<>();

    /**
     * Samples for the newest {@code maxCommits} non-merge commits reachable from {@code head}, oldest first.
     * State (file ages, authors, experience) is only built from commits inside that window, so the oldest samples
     * have incomplete history: callers treat the first part of the window as warm-up.
     */
    public List<Sample> build(Repository repo, String head, int maxCommits) throws Exception {
        List<RevCommit> commits = new ArrayList<>();
        try (RevWalk walk = new RevWalk(repo)) {
            walk.sort(RevSort.COMMIT_TIME_DESC);
            walk.markStart(walk.parseCommit(ObjectId.fromString(head)));
            for (RevCommit c : walk) {
                if (c.getParentCount() > 1) continue;
                commits.add(c);
                if (commits.size() >= maxCommits) break;
            }
        }
        Collections.reverse(commits);
        // stable order: commit time, then SHA (equal timestamps are common in rebased series)
        commits.sort(Comparator.comparingLong((RevCommit c) -> c.getCommitTime()).thenComparing(RevCommit::getName));

        List<Sample> out = new ArrayList<>();
        try (ObjectReader reader = repo.newObjectReader();
             DiffFormatter df = new DiffFormatter(DisabledOutputStream.INSTANCE)) {
            df.setRepository(repo);
            df.setDiffComparator(RawTextComparator.DEFAULT);
            df.setDetectRenames(true);
            for (RevCommit c : commits) {
                Sample s = sample(repo, reader, df, c);
                if (s != null) out.add(s);
            }
        }
        return out;
    }

    private Sample sample(Repository repo, ObjectReader reader, DiffFormatter df, RevCommit c) throws Exception {
        RevCommit parent = c.getParentCount() == 0 ? null : c.getParent(0);
        AbstractTreeIterator oldTree = parent == null ? new EmptyTreeIterator() : tree(reader, repo, parent);
        List<DiffEntry> entries = df.scan(oldTree, tree(reader, repo, c));
        long time = c.getCommitTime() * 1000L;
        String author = author(c);
        boolean fix = HistoryService.isBugfix(c.getFullMessage());

        Map<String, Integer> churn = new LinkedHashMap<>();
        Map<String, List<Integer>> fixedLines = new LinkedHashMap<>();
        Set<String> subsystems = new TreeSet<>(), dirs = new TreeSet<>();
        int la = 0, ld = 0, lt = 0;
        Set<String> priorAuthors = new HashSet<>(), priorCommits = new HashSet<>();
        double ageDays = 0;
        int ageCount = 0;
        List<Runnable> stateUpdates = new ArrayList<>();

        for (DiffEntry e : entries) {
            String path = e.getChangeType() == DiffEntry.ChangeType.DELETE ? e.getOldPath() : e.getNewPath();
            if (!isCode(path)) continue;
            String oldPath = e.getOldPath();
            RawText before = e.getChangeType() == DiffEntry.ChangeType.ADD ? RawText.EMPTY_TEXT : text(reader, e.getOldId().toObjectId());
            if (before == null) continue; // binary
            EditList edits = df.toFileHeader(e).toEditList();
            int added = 0, deleted = 0;
            List<Integer> touchedOld = new ArrayList<>();
            for (Edit edit : edits) {
                added += edit.getLengthB();
                deleted += edit.getLengthA();
                for (int line = edit.getBeginA(); line < edit.getEndA(); line++) touchedOld.add(line);
            }
            if (added + deleted == 0 && e.getChangeType() != DiffEntry.ChangeType.RENAME) continue;
            la += added;
            ld += deleted;
            churn.put(path, added + deleted);
            subsystems.add(path.contains("/") ? path.substring(0, path.indexOf('/')) : "");
            dirs.add(path.contains("/") ? path.substring(0, path.lastIndexOf('/')) : "");

            FileState prior = e.getChangeType() == DiffEntry.ChangeType.ADD ? null : files.get(oldPath);
            if (prior != null) {
                lt += prior.lines();
                priorAuthors.addAll(prior.authors());
                priorCommits.addAll(prior.commits());
                ageDays += (time - prior.lastChange()) / 86_400_000.0;
                ageCount++;
            } else if (e.getChangeType() != DiffEntry.ChangeType.ADD) {
                lt += before.size();   // file older than the window: its size is known, its history is not
            }
            if (fix && e.getChangeType() != DiffEntry.ChangeType.ADD) {
                List<Integer> relevant = touchedOld.stream().filter(i -> isRelevantLine(before.getString(i))).toList();
                if (!relevant.isEmpty()) fixedLines.put(oldPath, relevant);
            }
            int newLines = Math.max(0, (prior != null ? prior.lines() : before.size()) + added - deleted);
            DiffEntry.ChangeType type = e.getChangeType();
            stateUpdates.add(() -> {
                FileState base = files.remove(oldPath);
                if (type == DiffEntry.ChangeType.DELETE) return;
                Set<String> a = base == null ? new HashSet<>() : new HashSet<>(base.authors());
                Set<String> cs = base == null ? new HashSet<>() : new HashSet<>(base.commits());
                a.add(author);
                cs.add(c.getName());
                files.put(path, new FileState(newLines, time, a, cs));
            });
        }
        if (churn.isEmpty()) return null;

        int nf = churn.size();
        double total = churn.values().stream().mapToInt(Integer::intValue).sum();
        double entropy = 0;
        if (nf > 1 && total > 0) {
            for (int v : churn.values()) {
                if (v == 0) continue;
                double p = v / total;
                entropy -= p * (Math.log(p) / Math.log(2));
            }
            entropy /= Math.log(nf) / Math.log(2);
        }
        priorAuthors.remove(author);
        List<Long> authorTimes = authorCommitTimes.getOrDefault(author, List.of());
        double rexp = 0;
        for (long t : authorTimes) rexp += 1.0 / (1 + (time - t) / (365.25 * 86_400_000.0));
        Map<String, Integer> subs = authorSubsystemCommits.getOrDefault(author, Map.of());
        int sexp = subsystems.stream().mapToInt(s -> subs.getOrDefault(s, 0)).max().orElse(0);

        double[] f = {subsystems.size(), dirs.size(), nf, entropy, la, ld, lt, fix ? 1 : 0, priorAuthors.size(),
                ageCount == 0 ? 0 : ageDays / ageCount, priorCommits.size(), authorTimes.size(), rexp, sexp};

        stateUpdates.forEach(Runnable::run);
        authorCommitTimes.computeIfAbsent(author, k -> new ArrayList<>()).add(time);
        Map<String, Integer> as = authorSubsystemCommits.computeIfAbsent(author, k -> new HashMap<>());
        subsystems.forEach(s -> as.merge(s, 1, Integer::sum));

        boolean traceable = fix && nf <= MAX_FIX_FILES
                && fixedLines.values().stream().mapToInt(List::size).sum() <= MAX_FIX_LINES;
        return new Sample(c.getName(), time, author, parent == null ? null : parent.getName(), fix, f,
                traceable ? fixedLines : Map.of(), c.getShortMessage());
    }

    static boolean isCode(String path) {
        int dot = path.lastIndexOf('.');
        return dot > 0 && CODE_EXTENSIONS.contains(path.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    /** Lines SZZ should trace: not blank, not comments, not imports/package declarations. */
    static boolean isRelevantLine(String line) {
        String t = line.strip();
        if (t.isEmpty() || t.equals("{") || t.equals("}") || t.equals(");")) return false;
        return !(t.startsWith("//") || t.startsWith("/*") || t.startsWith("*") || t.startsWith("#")
                || t.startsWith("import ") || t.startsWith("package ") || t.startsWith("@Override"));
    }

    static String author(RevCommit c) {
        String email = c.getAuthorIdent().getEmailAddress();
        return email != null && !email.isBlank() ? email.toLowerCase(Locale.ROOT) : c.getAuthorIdent().getName().toLowerCase(Locale.ROOT);
    }

    private static AbstractTreeIterator tree(ObjectReader reader, Repository repo, RevCommit c) throws Exception {
        CanonicalTreeParser p = new CanonicalTreeParser();
        p.reset(reader, c.getTree().getId());
        return p;
    }

    private static RawText text(ObjectReader reader, ObjectId id) throws Exception {
        byte[] bytes = reader.open(id).getCachedBytes(8 * 1024 * 1024);
        return RawText.isBinary(bytes) ? null : new RawText(bytes);
    }
}

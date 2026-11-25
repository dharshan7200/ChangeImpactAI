package com.changeimpact.analysis.review;

import lombok.RequiredArgsConstructor;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Suggests reviewers for a change from code ownership in the repository history: people who changed the changed files
 * (weight 1) and the impacted files (weight ½) before the change, recency-weighted (half-life one year), as their share
 * of each file's history. Authors of the change itself and bots are never suggested. A heuristic with its evidence
 * (files, commits, last change) attached — not a judgement of who is qualified.
 */
@Service
@RequiredArgsConstructor
public class ReviewerSuggestionService {

    static final double IMPACTED_WEIGHT = 0.5;
    static final double HALF_LIFE_DAYS = 365;
    static final int MAX_SUGGESTIONS = 5;

    private final JdbcTemplate jdbc;

    public record Person(String name, String email, int commits) {}

    /** {@code lastChange} as ISO-8601 text: the result is stored in the analysis summary (JSON). */
    public record FileOwnership(String path, String relation, int commits, double share, String lastChange) {}

    public record Suggestion(String name, String email, double score, int changedFilesOwned, int impactedFilesOwned,
                             String lastChange, List<FileOwnership> evidence) {}

    public record Result(int historyCommits, Instant historyUntil, List<Person> changeAuthors, List<Suggestion> suggestions,
                         int filesWithoutHistory) {}

    /** One commit touching a file, by an author. */
    record Touch(String path, String email, String name, Instant date) {}

    public Result suggest(Git git, UUID repositoryId, String baseCommit, String headCommit,
                          Collection<String> changedPaths, Collection<String> impactedPaths) throws Exception {
        Instant baseDate;
        Map<String, Person> authors = new LinkedHashMap<>();
        try (RevWalk walk = new RevWalk(git.getRepository())) {
            RevCommit base = walk.parseCommit(ObjectId.fromString(baseCommit));
            baseDate = Instant.ofEpochSecond(base.getCommitTime());
            walk.markStart(walk.parseCommit(ObjectId.fromString(headCommit)));
            walk.markUninteresting(base);
            for (RevCommit c : walk) {
                String email = c.getAuthorIdent().getEmailAddress().toLowerCase(Locale.ROOT);
                Person p = authors.get(email);
                authors.put(email, new Person(c.getAuthorIdent().getName(), email, p == null ? 1 : p.commits() + 1));
            }
        }
        Set<String> paths = new HashSet<>(changedPaths);
        paths.addAll(impactedPaths);
        List<Touch> touches = paths.isEmpty() ? List.of() : jdbc.query(con -> {
            var ps = con.prepareStatement("""
                    SELECT cfc.path, lower(c.author_email), c.author_name, c.commit_date
                      FROM commit_file_changes cfc
                      JOIN commits c ON c.repository_id = cfc.repository_id AND c.sha = cfc.commit_sha
                     WHERE cfc.repository_id = ? AND cfc.path = ANY (?) AND c.commit_date <= ?""");
            ps.setObject(1, repositoryId);
            ps.setArray(2, con.createArrayOf("varchar", paths.toArray(String[]::new)));
            ps.setTimestamp(3, Timestamp.from(baseDate));
            return ps;
        }, (rs, i) -> new Touch(rs.getString(1), rs.getString(2), rs.getString(3), rs.getTimestamp(4).toInstant()));
        Integer history = jdbc.queryForObject("SELECT count(*) FROM commits WHERE repository_id = ? AND commit_date <= ?",
                Integer.class, repositoryId, Timestamp.from(baseDate));
        Set<String> changed = new HashSet<>(changedPaths);
        // people commit under several addresses: exclude the change's authors by e-mail and by name
        Set<String> excluded = new HashSet<>(authors.keySet());
        authors.values().forEach(p -> excluded.add(nameKey(p.name())));
        List<Suggestion> suggestions = rank(touches, changed, excluded, baseDate);
        Set<String> withHistory = new HashSet<>();
        touches.forEach(t -> withHistory.add(t.path()));
        int withoutHistory = (int) changed.stream().filter(p -> !withHistory.contains(p)).count();
        return new Result(history == null ? 0 : history, baseDate, new ArrayList<>(authors.values()), suggestions, withoutHistory);
    }

    /** Ranks candidate reviewers (pure function, unit-tested). */
    static List<Suggestion> rank(List<Touch> touches, Set<String> changedPaths, Set<String> excludedEmails, Instant until) {
        // recency-weighted contributions per file and author
        Map<String, Map<String, Double>> weight = new HashMap<>();
        Map<String, Map<String, Integer>> commits = new HashMap<>();
        Map<String, Instant> last = new HashMap<>();
        Map<String, Instant> lastPerFile = new HashMap<>();
        Map<String, String> names = new HashMap<>();
        for (Touch t : touches) {
            if (isBot(t.email(), t.name())) continue;
            double ageDays = Math.max(0, Duration.between(t.date(), until).toHours() / 24.0);
            double w = Math.pow(0.5, ageDays / HALF_LIFE_DAYS);
            weight.computeIfAbsent(t.path(), k -> new HashMap<>()).merge(t.email(), w, Double::sum);
            commits.computeIfAbsent(t.path(), k -> new HashMap<>()).merge(t.email(), 1, Integer::sum);
            last.merge(t.email(), t.date(), (a, b) -> a.isAfter(b) ? a : b);
            lastPerFile.merge(t.path() + "\n" + t.email(), t.date(), (a, b) -> a.isAfter(b) ? a : b);
            names.putIfAbsent(t.email(), t.name());
        }
        Map<String, Double> score = new HashMap<>();
        Map<String, List<FileOwnership>> evidence = new HashMap<>();
        for (var file : weight.entrySet()) {
            double total = file.getValue().values().stream().mapToDouble(Double::doubleValue).sum();
            boolean isChanged = changedPaths.contains(file.getKey());
            for (var author : file.getValue().entrySet()) {
                if (excludedEmails.contains(author.getKey()) || excludedEmails.contains(nameKey(names.get(author.getKey())))) continue;
                double share = author.getValue() / total;
                score.merge(author.getKey(), (isChanged ? 1.0 : IMPACTED_WEIGHT) * share, Double::sum);
                evidence.computeIfAbsent(author.getKey(), k -> new ArrayList<>()).add(new FileOwnership(file.getKey(),
                        isChanged ? "CHANGED" : "IMPACTED", commits.get(file.getKey()).get(author.getKey()), round(share),
                        String.valueOf(lastPerFile.get(file.getKey() + "\n" + author.getKey()))));
            }
        }
        return score.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                .limit(MAX_SUGGESTIONS)
                .map(e -> {
                    List<FileOwnership> ev = evidence.get(e.getKey()).stream()
                            .sorted(Comparator.comparing((FileOwnership f) -> !"CHANGED".equals(f.relation()))
                                    .thenComparing(Comparator.comparingDouble(FileOwnership::share).reversed())
                                    .thenComparing(FileOwnership::path))
                            .toList();
                    int changedOwned = (int) ev.stream().filter(f -> "CHANGED".equals(f.relation())).count();
                    return new Suggestion(names.get(e.getKey()), e.getKey(), round(e.getValue()), changedOwned,
                            ev.size() - changedOwned, String.valueOf(last.get(e.getKey())), ev.stream().limit(6).toList());
                })
                .toList();
    }

    /** Name key for matching one person across e-mail addresses ("name:" prefix keeps it apart from e-mails). */
    static String nameKey(String name) {
        return "name:" + (name == null ? "" : name.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " "));
    }

    static boolean isBot(String email, String name) {
        String e = email == null ? "" : email, n = name == null ? "" : name.toLowerCase(Locale.ROOT);
        return e.contains("[bot]") || n.contains("[bot]") || n.endsWith("-bot") || n.equals("dependabot")
                || e.startsWith("bot@") || e.contains("dependabot") || e.contains("renovate");
    }

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}

package com.changeimpact.architecture.twin;

import com.changeimpact.architecture.twin.TwinModel.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jgrapht.Graph;
import org.jgrapht.alg.connectivity.KosarajuStrongConnectivityInspector;
import org.jgrapht.graph.DefaultDirectedGraph;
import org.jgrapht.graph.DefaultEdge;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Architecture digital twin: a structural model of the production code of every analysed commit, derived only from
 * the commit's parsed symbols and resolved dependencies (nothing is guessed), stored per commit, and compared between
 * commits. See {@link TwinModel} for the metrics.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ArchitectureTwinService {

    /** Increase when the computation changes: stored snapshots of an older version are rebuilt on access. */
    static final int TWIN_VERSION = 1;
    /** Most recent analysed commits shown in the trend. */
    static final int MAX_TREND_POINTS = 40;

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String TYPE_KINDS = com.changeimpact.parser.model.SymbolType.TYPE_LIKE_SQL;
    private static final String CALLABLE_KINDS = com.changeimpact.parser.model.SymbolType.CALLABLE_SQL;

    private final JdbcTemplate jdbc;

    // ------------------------------------------------------------------ commits

    /** Full SHA of an analysed commit for a full SHA or an unambiguous prefix (≥ 7 characters), if analysed. */
    public Optional<String> resolveAnalysedCommit(UUID repositoryId, String ref) {
        if (ref == null || !ref.matches("[0-9a-fA-F]{7,64}")) return Optional.empty();
        List<String> shas = jdbc.queryForList("SELECT DISTINCT commit_sha FROM source_files WHERE repository_id = ? AND commit_sha LIKE ?",
                String.class, repositoryId, ref.toLowerCase(Locale.ROOT) + "%");
        return shas.size() == 1 ? Optional.of(shas.get(0)) : Optional.empty();
    }

    /** Analysed commits of the repository, newest commit date first (commits outside the scanned history last). */
    public List<String> analysedCommits(UUID repositoryId, int limit) {
        return jdbc.queryForList("""
                SELECT f.commit_sha FROM source_files f
                  LEFT JOIN commits c ON c.repository_id = f.repository_id AND c.sha = f.commit_sha
                 WHERE f.repository_id = ?
                 GROUP BY f.commit_sha
                 ORDER BY max(c.commit_date) DESC NULLS LAST, max(f.created_at) DESC
                 LIMIT ?""", String.class, repositoryId, limit);
    }

    // ------------------------------------------------------------------ snapshots

    /** The stored snapshot of an analysed commit, built (and stored) when missing or outdated. */
    public Optional<Snapshot> snapshot(UUID repositoryId, String commitSha) {
        String fingerprint = fingerprint(repositoryId, commitSha);
        if (fingerprint == null) return Optional.empty();
        List<Snapshot> stored = jdbc.query("""
                SELECT commit_sha, commit_date, commit_message, summary::text, packages::text, edges::text, cycles::text
                  FROM architecture_snapshots
                 WHERE repository_id = ? AND commit_sha = ? AND twin_version = ? AND source_fingerprint = ?
                   AND (commit_date IS NOT NULL OR NOT EXISTS (SELECT 1 FROM commits c
                                                               WHERE c.repository_id = architecture_snapshots.repository_id
                                                                 AND c.sha = architecture_snapshots.commit_sha))""",
                (rs, i) -> new Snapshot(rs.getString(1), instant(rs.getTimestamp(2)), rs.getString(3),
                        JSON.readValue(rs.getString(4), Summary.class),
                        JSON.readValue(rs.getString(5), new TypeReference<List<PackageNode>>() {}),
                        JSON.readValue(rs.getString(6), new TypeReference<List<PackageEdge>>() {}),
                        JSON.readValue(rs.getString(7), new TypeReference<List<List<String>>>() {})),
                repositoryId, commitSha, TWIN_VERSION, fingerprint);
        if (!stored.isEmpty()) return Optional.of(stored.get(0));
        long start = System.nanoTime();
        Snapshot s = compute(repositoryId, commitSha);
        jdbc.update("""
                INSERT INTO architecture_snapshots (repository_id, commit_sha, twin_version, source_fingerprint, commit_date,
                                                    commit_message, summary, packages, edges, cycles)
                VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?::jsonb, ?::jsonb)
                ON CONFLICT (repository_id, commit_sha) DO UPDATE SET twin_version = EXCLUDED.twin_version,
                    source_fingerprint = EXCLUDED.source_fingerprint, commit_date = EXCLUDED.commit_date,
                    commit_message = EXCLUDED.commit_message, summary = EXCLUDED.summary, packages = EXCLUDED.packages,
                    edges = EXCLUDED.edges, cycles = EXCLUDED.cycles, created_at = NOW()""",
                repositoryId, commitSha, TWIN_VERSION, fingerprint,
                s.commitDate() == null ? null : Timestamp.from(s.commitDate()), s.commitMessage(),
                JSON.writeValueAsString(s.summary()), JSON.writeValueAsString(s.packages()),
                JSON.writeValueAsString(s.edges()), JSON.writeValueAsString(s.cycles()));
        log.info("Architecture snapshot of {} built in {} ms: {} packages, {} package dependencies, {} cycle(s)",
                commitSha.substring(0, 7), (System.nanoTime() - start) / 1_000_000, s.summary().packages(),
                s.summary().packageEdges(), s.summary().cycles());
        return Optional.of(s);
    }

    /** Summaries of the most recent analysed commits, oldest first. */
    public List<TrendPoint> trend(UUID repositoryId) {
        List<TrendPoint> points = new ArrayList<>();
        for (String sha : analysedCommits(repositoryId, MAX_TREND_POINTS)) {
            snapshot(repositoryId, sha).ifPresent(s -> points.add(point(s)));
        }
        Collections.reverse(points);
        points.sort(Comparator.comparing(TrendPoint::commitDate, Comparator.nullsLast(Comparator.naturalOrder())));
        return points;
    }

    private String fingerprint(UUID repositoryId, String commitSha) {
        return jdbc.query("SELECT count(*), max(created_at) FROM source_files WHERE repository_id = ? AND commit_sha = ?",
                rs -> {
                    rs.next();
                    long n = rs.getLong(1);
                    Timestamp t = rs.getTimestamp(2);
                    return n == 0 || t == null ? null : n + "@" + t.toInstant().toEpochMilli();
                }, repositoryId, commitSha);
    }

    Snapshot compute(UUID repositoryId, String commitSha) {
        Map<String, long[]> fileStats = new TreeMap<>();   // package → [files, loc]
        jdbc.query("""
                SELECT coalesce(f.package_name, ''), count(*), coalesce(sum(f.lines_of_code), 0)
                  FROM source_files f WHERE f.repository_id = ? AND f.commit_sha = ? AND NOT f.is_test GROUP BY 1""",
                rs -> { fileStats.put(rs.getString(1), new long[]{rs.getLong(2), rs.getLong(3)}); }, repositoryId, commitSha);
        Map<String, long[]> symbolStats = new HashMap<>(); // package → [types, abstract types, methods, complexity]
        jdbc.query("""
                SELECT coalesce(f.package_name, ''),
                       count(*) FILTER (WHERE s.symbol_type IN %1$s),
                       count(*) FILTER (WHERE s.symbol_type = 'INTERFACE' OR (s.symbol_type = 'CLASS' AND s.is_abstract)),
                       count(*) FILTER (WHERE s.symbol_type IN %2$s),
                       coalesce(sum(s.complexity) FILTER (WHERE s.symbol_type IN %2$s), 0)
                  FROM symbols s JOIN source_files f ON f.id = s.source_file_id
                 WHERE s.repository_id = ? AND s.commit_sha = ? AND NOT f.is_test GROUP BY 1""".formatted(TYPE_KINDS, CALLABLE_KINDS),
                rs -> { symbolStats.put(rs.getString(1), new long[]{rs.getLong(2), rs.getLong(3), rs.getLong(4), rs.getLong(5)}); },
                repositoryId, commitSha);
        List<long[]> edgeCounts = new ArrayList<>();
        List<String[]> edgeNames = new ArrayList<>();
        jdbc.query("""
                SELECT coalesce(sf.package_name, ''), coalesce(tf.package_name, ''), count(*), count(DISTINCT (sf.id, tf.id))
                  FROM dependencies d
                  JOIN symbols s ON s.id = d.source_symbol_id JOIN source_files sf ON sf.id = s.source_file_id
                  JOIN symbols t ON t.id = d.target_symbol_id JOIN source_files tf ON tf.id = t.source_file_id
                 WHERE d.repository_id = ? AND d.commit_sha = ? AND NOT sf.is_test AND NOT tf.is_test
                   AND coalesce(sf.package_name, '') <> coalesce(tf.package_name, '')
                 GROUP BY 1, 2""", rs -> {
            edgeNames.add(new String[]{rs.getString(1), rs.getString(2)});
            edgeCounts.add(new long[]{rs.getLong(3), rs.getLong(4)});
        }, repositoryId, commitSha);

        Graph<String, DefaultEdge> graph = new DefaultDirectedGraph<>(DefaultEdge.class);
        fileStats.keySet().forEach(graph::addVertex);
        for (String[] e : edgeNames) {
            graph.addVertex(e[0]);
            graph.addVertex(e[1]);
            graph.addEdge(e[0], e[1]);
        }
        List<List<String>> cycles = new KosarajuStrongConnectivityInspector<>(graph).stronglyConnectedSets().stream()
                .filter(set -> set.size() > 1)
                .map(set -> set.stream().sorted().toList())
                .sorted(Comparator.<List<String>>comparingInt(List::size).reversed().thenComparing(l -> l.get(0)))
                .toList();
        Map<String, Integer> cycleOf = new HashMap<>();
        for (int i = 0; i < cycles.size(); i++) for (String p : cycles.get(i)) cycleOf.put(p, i);
        Map<String, Integer> order = layerOrder(graph, cycleOf);

        List<PackageNode> packages = new ArrayList<>();
        for (String p : new TreeSet<>(graph.vertexSet())) {
            long[] fs = fileStats.getOrDefault(p, new long[2]);
            long[] ss = symbolStats.getOrDefault(p, new long[4]);
            int ca = graph.inDegreeOf(p), ce = graph.outDegreeOf(p);
            double instability = ca + ce == 0 ? 0 : (double) ce / (ca + ce);
            double abstractness = ss[0] == 0 ? 0 : (double) ss[1] / ss[0];
            packages.add(new PackageNode(p, (int) fs[0], (int) ss[0], (int) ss[1], (int) ss[2], fs[1], ss[3], ca, ce,
                    round(instability), round(abstractness), round(Math.abs(abstractness + instability - 1)),
                    cycleOf.getOrDefault(p, -1), order.get(p)));
        }
        List<PackageEdge> edges = new ArrayList<>();
        for (int i = 0; i < edgeNames.size(); i++) {
            String s = edgeNames.get(i)[0], t = edgeNames.get(i)[1];
            Integer cs = cycleOf.get(s);
            edges.add(new PackageEdge(s, t, edgeCounts.get(i)[0], edgeCounts.get(i)[1], cs != null && cs.equals(cycleOf.get(t))));
        }
        edges.sort(Comparator.comparing(PackageEdge::source).thenComparing(PackageEdge::target));

        Integer violations = jdbc.queryForObject("SELECT count(*) FROM architecture_violations WHERE repository_id = ? AND commit_sha = ?",
                Integer.class, repositoryId, commitSha);
        Object[] commit = jdbc.query("SELECT commit_date, message FROM commits WHERE repository_id = ? AND sha = ?",
                rs -> rs.next() ? new Object[]{instant(rs.getTimestamp(1)), firstLine(rs.getString(2))} : new Object[]{null, null},
                repositoryId, commitSha);
        List<PackageNode> withTypes = packages.stream().filter(p -> p.types() > 0).toList();
        Summary summary = new Summary(packages.size(),
                packages.stream().mapToInt(PackageNode::files).sum(),
                packages.stream().mapToInt(PackageNode::types).sum(),
                packages.stream().mapToInt(PackageNode::methods).sum(),
                packages.stream().mapToLong(PackageNode::loc).sum(),
                packages.stream().mapToLong(PackageNode::complexity).sum(),
                edges.size(), edges.stream().mapToLong(PackageEdge::references).sum(),
                cycles.size(), cycleOf.size(),
                round(withTypes.stream().mapToDouble(PackageNode::instability).average().orElse(0)),
                round(withTypes.stream().mapToDouble(PackageNode::distance).average().orElse(0)),
                violations == null ? 0 : violations);
        return new Snapshot(commitSha, (Instant) commit[0], (String) commit[1], summary, packages, edges, cycles);
    }

    /**
     * Dependencies-first order of the packages: Kahn's algorithm on the graph of package cycles (each cycle is one
     * node), ties broken by name, packages of one cycle kept together. In a matrix laid out in this order, dependencies
     * on packages listed earlier are "downward"; anything above the diagonal is inside a cycle.
     */
    static Map<String, Integer> layerOrder(Graph<String, DefaultEdge> graph, Map<String, Integer> cycleOf) {
        Map<String, String> groupOf = new HashMap<>();
        Map<String, List<String>> members = new TreeMap<>();
        for (String p : graph.vertexSet()) {
            String group = cycleOf.containsKey(p) ? "cycle#" + cycleOf.get(p) : p;
            groupOf.put(p, group);
            members.computeIfAbsent(group, k -> new ArrayList<>()).add(p);
        }
        // a group can be placed once every group it depends on is placed
        Map<String, Set<String>> dependsOn = new HashMap<>();
        Map<String, Set<String>> dependents = new HashMap<>();
        members.keySet().forEach(g -> { dependsOn.put(g, new HashSet<>()); dependents.put(g, new HashSet<>()); });
        for (DefaultEdge e : graph.edgeSet()) {
            String a = groupOf.get(graph.getEdgeSource(e)), b = groupOf.get(graph.getEdgeTarget(e));
            if (!a.equals(b)) { dependsOn.get(a).add(b); dependents.get(b).add(a); }
        }
        Comparator<String> byName = Comparator.comparing(g -> members.get(g).stream().sorted().findFirst().orElse(g));
        PriorityQueue<String> ready = new PriorityQueue<>(byName);
        members.keySet().stream().filter(g -> dependsOn.get(g).isEmpty()).forEach(ready::add);
        Map<String, Integer> order = new HashMap<>();
        int next = 0;
        while (!ready.isEmpty()) {
            String g = ready.poll();
            for (String p : members.get(g).stream().sorted().toList()) order.put(p, next++);
            for (String d : dependents.get(g)) {
                dependsOn.get(d).remove(g);
                if (dependsOn.get(d).isEmpty()) ready.add(d);
            }
        }
        return order;
    }

    // ------------------------------------------------------------------ hotspots

    /** Production files of an analysed commit with churn, complexity, fan-in and coverage, hotspots marked. */
    public Optional<Hotspots> hotspots(UUID repositoryId, String commitSha) {
        if (fingerprint(repositoryId, commitSha) == null) return Optional.empty();
        Instant commitDate = jdbc.query("SELECT commit_date FROM commits WHERE repository_id = ? AND sha = ?",
                rs -> rs.next() ? instant(rs.getTimestamp(1)) : null, repositoryId, commitSha);
        // history up to the commit (when its date is known), so an old commit is not judged by later changes
        String until = commitDate == null ? "" : " AND (cm.commit_date IS NULL OR cm.commit_date <= ?)";
        UUID coverageReport = jdbc.query("""
                SELECT id FROM quality_reports WHERE repository_id = ? AND commit_sha = ? AND report_type = 'JACOCO'
                 ORDER BY created_at DESC LIMIT 1""", rs -> rs.next() ? rs.getObject(1, UUID.class) : null, repositoryId, commitSha);
        List<Object> args = new ArrayList<>(List.of(repositoryId, commitSha, repositoryId, commitSha, repositoryId));
        if (commitDate != null) args.add(Timestamp.from(commitDate));
        args.addAll(List.of(repositoryId, commitSha));
        String coverageJoin = coverageReport == null ? "" : """
                LEFT JOIN LATERAL (SELECT sum(cardinality(cf.covered_lines))::float
                                          / nullif(sum(cardinality(cf.covered_lines) + cardinality(cf.missed_lines)), 0) AS ratio
                                     FROM coverage_files cf
                                    WHERE cf.report_id = '%s'
                                      AND (f.relative_path = cf.source_path OR f.relative_path LIKE '%%/' || cf.source_path)) cov ON TRUE
                """.formatted(coverageReport);
        String sql = """
                WITH files AS (
                    SELECT f.id, f.relative_path, coalesce(f.package_name, '') AS pkg, f.lines_of_code AS loc
                      FROM source_files f WHERE f.repository_id = ? AND f.commit_sha = ? AND NOT f.is_test
                ), sym AS (
                    SELECT s.source_file_id AS fid,
                           count(*) FILTER (WHERE s.symbol_type IN %1$s) AS types,
                           count(*) FILTER (WHERE s.symbol_type IN %2$s) AS methods,
                           coalesce(sum(s.complexity) FILTER (WHERE s.symbol_type IN %2$s), 0) AS complexity,
                           coalesce(max(s.complexity) FILTER (WHERE s.symbol_type IN %2$s), 0) AS max_complexity,
                           min(s.qualified_name) FILTER (WHERE s.symbol_type IN %1$s AND s.parent_symbol_id IS NULL) AS primary_type
                      FROM symbols s WHERE s.repository_id = ? AND s.commit_sha = ? GROUP BY 1
                ), churn AS (
                    SELECT cfc.path, count(DISTINCT cfc.commit_sha) AS commits, sum(cfc.additions + cfc.deletions) AS lines,
                           count(DISTINCT lower(cm.author_email)) AS authors, max(cm.commit_date) AS last_change
                      FROM commit_file_changes cfc
                      JOIN commits cm ON cm.repository_id = cfc.repository_id AND cm.sha = cfc.commit_sha
                     WHERE cfc.repository_id = ?%3$s
                     GROUP BY 1
                ), fanin AS (
                    SELECT tf.id AS fid, count(DISTINCT sf.id) AS dependents
                      FROM dependencies d
                      JOIN symbols s ON s.id = d.source_symbol_id JOIN source_files sf ON sf.id = s.source_file_id
                      JOIN symbols t ON t.id = d.target_symbol_id JOIN source_files tf ON tf.id = t.source_file_id
                     WHERE d.repository_id = ? AND d.commit_sha = ? AND sf.id <> tf.id GROUP BY 1
                )
                SELECT f.relative_path, f.pkg, sym.primary_type, f.loc, coalesce(sym.types, 0), coalesce(sym.methods, 0),
                       coalesce(sym.complexity, 0), coalesce(sym.max_complexity, 0), coalesce(churn.commits, 0),
                       coalesce(churn.lines, 0), coalesce(churn.authors, 0), churn.last_change, coalesce(fanin.dependents, 0),
                       %4$s
                  FROM files f
                  LEFT JOIN sym ON sym.fid = f.id
                  LEFT JOIN churn ON churn.path = f.relative_path
                  LEFT JOIN fanin ON fanin.fid = f.id
                  %5$s
                 ORDER BY f.relative_path""".formatted(TYPE_KINDS, CALLABLE_KINDS, until,
                coverageReport == null ? "NULL::float" : "cov.ratio", coverageJoin);
        record Row(String path, String pkg, String type, long loc, int types, int methods, long complexity, int maxCx,
                   int commits, long lines, int authors, Instant last, int fanIn, Double coverage) {}
        List<Row> rows = jdbc.query(sql, (rs, i) -> {
            double cov = rs.getDouble(14);
            return new Row(rs.getString(1), rs.getString(2), rs.getString(3), rs.getLong(4), rs.getInt(5), rs.getInt(6),
                    rs.getLong(7), rs.getInt(8), rs.getInt(9), rs.getLong(10), rs.getInt(11), instant(rs.getTimestamp(12)),
                    rs.getInt(13), rs.wasNull() ? null : round(cov));
        }, args.toArray());
        double[] churnRank = percentileRanks(rows.stream().mapToDouble(Row::commits).toArray());
        double[] complexityRank = percentileRanks(rows.stream().mapToDouble(Row::complexity).toArray());
        List<FileHotspot> files = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            Row r = rows.get(i);
            double score = round(churnRank[i] * complexityRank[i]);
            boolean hotspot = r.commits() >= 2 && churnRank[i] >= 0.75 && complexityRank[i] >= 0.75;
            files.add(new FileHotspot(r.path(), r.pkg(), r.type(), r.loc(), r.types(), r.methods(), r.complexity(), r.maxCx(),
                    r.commits(), r.lines(), r.authors(), r.last(), r.fanIn(), r.coverage(), score, hotspot));
        }
        Integer history = jdbc.queryForObject("SELECT count(*) FROM commits WHERE repository_id = ?"
                + (commitDate == null ? "" : " AND (commit_date IS NULL OR commit_date <= ?)"), Integer.class,
                commitDate == null ? new Object[]{repositoryId} : new Object[]{repositoryId, Timestamp.from(commitDate)});
        return Optional.of(new Hotspots(commitSha, history == null ? 0 : history, coverageReport != null, files));
    }

    /** Percentile rank in [0, 1] of each value (ties share their average rank; all-equal values rank 0). */
    static double[] percentileRanks(double[] values) {
        int n = values.length;
        double[] ranks = new double[n];
        if (n <= 1) return ranks;
        Integer[] idx = new Integer[n];
        for (int i = 0; i < n; i++) idx[i] = i;
        Arrays.sort(idx, Comparator.comparingDouble(i -> values[i]));
        for (int i = 0; i < n; ) {
            int j = i;
            while (j + 1 < n && values[idx[j + 1]] == values[idx[i]]) j++;
            double rank = (i + j) / 2.0 / (n - 1);
            for (int k = i; k <= j; k++) ranks[idx[k]] = values[idx[i]] == 0 ? 0 : rank;
            i = j + 1;
        }
        return ranks;
    }

    // ------------------------------------------------------------------ comparison

    public Comparison compare(UUID repositoryId, Snapshot base, Snapshot head) {
        Map<String, PackageNode> before = base.packages().stream().collect(Collectors.toMap(PackageNode::name, p -> p));
        Map<String, PackageNode> after = head.packages().stream().collect(Collectors.toMap(PackageNode::name, p -> p));
        List<String> added = after.keySet().stream().filter(p -> !before.containsKey(p)).sorted().toList();
        List<String> removed = before.keySet().stream().filter(p -> !after.containsKey(p)).sorted().toList();

        Map<String, PackageEdge> eb = base.edges().stream().collect(Collectors.toMap(e -> e.source() + "→" + e.target(), e -> e));
        Map<String, PackageEdge> eh = head.edges().stream().collect(Collectors.toMap(e -> e.source() + "→" + e.target(), e -> e));
        List<EdgeDelta> addedEdges = new ArrayList<>(), removedEdges = new ArrayList<>(), changedEdges = new ArrayList<>();
        for (var e : new TreeMap<>(eh).entrySet()) {
            PackageEdge old = eb.get(e.getKey());
            if (old == null) addedEdges.add(new EdgeDelta(e.getValue().source(), e.getValue().target(), 0, e.getValue().references(), "ADDED"));
            else if (old.references() != e.getValue().references())
                changedEdges.add(new EdgeDelta(old.source(), old.target(), old.references(), e.getValue().references(), "CHANGED"));
        }
        for (var e : new TreeMap<>(eb).entrySet()) {
            if (!eh.containsKey(e.getKey())) removedEdges.add(new EdgeDelta(e.getValue().source(), e.getValue().target(), e.getValue().references(), 0, "REMOVED"));
        }
        Set<Set<String>> cyclesBefore = base.cycles().stream().map(HashSet::new).collect(Collectors.toSet());
        Set<Set<String>> cyclesAfter = head.cycles().stream().map(HashSet::new).collect(Collectors.toSet());
        List<List<String>> newCycles = head.cycles().stream().filter(c -> !cyclesBefore.contains(new HashSet<>(c))).toList();
        List<List<String>> resolvedCycles = base.cycles().stream().filter(c -> !cyclesAfter.contains(new HashSet<>(c))).toList();

        List<PackageDelta> deltas = new ArrayList<>();
        for (String p : new TreeSet<>(union(before.keySet(), after.keySet()))) {
            PackageNode a = before.get(p), b = after.get(p);
            String status = a == null ? "ADDED" : b == null ? "REMOVED" : "CHANGED";
            if (a != null && b != null && a.loc() == b.loc() && a.ca() == b.ca() && a.ce() == b.ce() && a.types() == b.types()) continue;
            deltas.add(new PackageDelta(p, status, a == null ? 0 : a.loc(), b == null ? 0 : b.loc(),
                    a == null ? 0 : a.ca(), b == null ? 0 : b.ca(), a == null ? 0 : a.ce(), b == null ? 0 : b.ce(),
                    a == null ? 0 : a.instability(), b == null ? 0 : b.instability(),
                    a == null ? 0 : a.distance(), b == null ? 0 : b.distance()));
        }
        Set<String> violationsBefore = violations(repositoryId, base.commitSha());
        Set<String> violationsAfter = violations(repositoryId, head.commitSha());
        List<String> newViolations = violationsAfter.stream().filter(v -> !violationsBefore.contains(v)).sorted().toList();
        List<String> resolvedViolations = violationsBefore.stream().filter(v -> !violationsAfter.contains(v)).sorted().toList();

        List<String> findings = new ArrayList<>();
        if (!newCycles.isEmpty()) newCycles.forEach(c -> findings.add("New package cycle: " + String.join(" ↔ ", c)));
        if (!addedEdges.isEmpty()) findings.add(addedEdges.size() + " new package dependenc" + (addedEdges.size() == 1 ? "y" : "ies")
                + ", e.g. " + addedEdges.get(0).source() + " → " + addedEdges.get(0).target());
        if (!newViolations.isEmpty()) findings.add(newViolations.size() + " new architecture rule violation(s)");
        for (PackageDelta d : deltas) {
            if (!"CHANGED".equals(d.status())) continue;
            if (d.instabilityAfter() - d.instabilityBefore() >= 0.2)
                findings.add(d.name() + " became less stable (I " + d.instabilityBefore() + " → " + d.instabilityAfter() + ")");
            if (d.distanceAfter() - d.distanceBefore() >= 0.2)
                findings.add(d.name() + " moved away from the main sequence (D " + d.distanceBefore() + " → " + d.distanceAfter() + ")");
        }
        if (!resolvedCycles.isEmpty()) findings.add(resolvedCycles.size() + " package cycle(s) resolved");
        if (!removedEdges.isEmpty()) findings.add(removedEdges.size() + " package dependenc" + (removedEdges.size() == 1 ? "y" : "ies") + " removed");
        if (findings.isEmpty()) findings.add("No structural drift: same packages, dependencies and cycles");
        return new Comparison(point(base), point(head), added, removed, addedEdges, removedEdges, changedEdges,
                newCycles, resolvedCycles, deltas, newViolations, resolvedViolations, findings);
    }

    private Set<String> violations(UUID repositoryId, String commitSha) {
        return new HashSet<>(jdbc.queryForList("SELECT rule_name || ': ' || description FROM architecture_violations WHERE repository_id = ? AND commit_sha = ?",
                String.class, repositoryId, commitSha));
    }

    // ------------------------------------------------------------------ helpers

    static TrendPoint point(Snapshot s) {
        return new TrendPoint(s.commitSha(), s.commitDate(), s.commitMessage(), s.summary());
    }

    private static <T> Set<T> union(Set<T> a, Set<T> b) {
        Set<T> u = new HashSet<>(a);
        u.addAll(b);
        return u;
    }

    private static Instant instant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }

    private static String firstLine(String message) {
        if (message == null) return null;
        String line = message.strip().lines().findFirst().orElse("");
        return line.length() > 200 ? line.substring(0, 200) : line;
    }

    static double round(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }
}

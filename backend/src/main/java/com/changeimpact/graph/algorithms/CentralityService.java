package com.changeimpact.graph.algorithms;

import com.changeimpact.graph.model.GraphEdge;
import com.changeimpact.graph.model.GraphNode;
import com.changeimpact.graph.service.DependencyGraphService;
import lombok.RequiredArgsConstructor;
import org.jgrapht.Graph;
import org.jgrapht.alg.scoring.BetweennessCentrality;
import org.jgrapht.alg.scoring.PageRank;
import org.jgrapht.graph.AsSubgraph;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Centrality of types in the class-level dependency graph (one vertex per class/interface/enum/record, one edge per
 * "uses" relation, as in {@link DependencyGraphService#getTypeGraph}).
 * <ul>
 *   <li><b>in-degree</b>: how many types depend on this one (afferent); <b>out-degree</b>: how many it uses.</li>
 *   <li><b>betweenness</b> (Brandes, normalised to 0–1): the share of shortest dependency paths between other types
 *       that pass through this one — a high value marks a broker whose change propagates widely.</li>
 *   <li><b>PageRank</b> (damping 0.85, edges point from user to used type): importance of being used by important
 *       types; values sum to 1 over the analysed types.</li>
 * </ul>
 * All three are exact, deterministic graph measures (ties ordered by qualified name). Test code is excluded unless
 * requested, so test fixtures do not inflate the scores of production types. Results are cached per commit.
 */
@Service
@RequiredArgsConstructor
public class CentralityService {

    public static final double DAMPING = 0.85;
    private static final int CACHE_SIZE = 16;

    private final DependencyGraphService graphs;

    public record TypeCentrality(UUID symbolId, String qualifiedName, String simpleName, String packageName,
                                 String sourceFile, int startLine, int inDegree, int outDegree,
                                 double betweenness, double pageRank, int rank) {}

    public record CentralityReport(String commitSha, int types, int dependencies, boolean includesTests,
                                   String rankedBy, List<TypeCentrality> top) {}

    private final Map<String, List<TypeCentrality>> cache = Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, List<TypeCentrality>> e) { return size() > CACHE_SIZE; }
    });

    /** Top {@code limit} types ordered by {@code rankBy} (betweenness, pagerank, indegree or outdegree). */
    public CentralityReport report(UUID repositoryId, String commitSha, boolean includeTests, String rankBy, int limit) {
        List<TypeCentrality> all = compute(repositoryId, commitSha, includeTests);
        Comparator<TypeCentrality> order = switch (rankBy == null ? "betweenness" : rankBy.toLowerCase(Locale.ROOT)) {
            case "pagerank" -> Comparator.comparingDouble(TypeCentrality::pageRank).reversed();
            case "indegree" -> Comparator.comparingInt(TypeCentrality::inDegree).reversed();
            case "outdegree" -> Comparator.comparingInt(TypeCentrality::outDegree).reversed();
            case "betweenness" -> Comparator.comparingDouble(TypeCentrality::betweenness).reversed();
            default -> throw new IllegalArgumentException("rankBy must be betweenness, pagerank, indegree or outdegree");
        };
        order = order.thenComparing(TypeCentrality::qualifiedName);
        List<TypeCentrality> sorted = all.stream().sorted(order).toList();
        List<TypeCentrality> top = new ArrayList<>();
        for (int i = 0; i < Math.min(limit, sorted.size()); i++) {
            TypeCentrality t = sorted.get(i);
            top.add(new TypeCentrality(t.symbolId(), t.qualifiedName(), t.simpleName(), t.packageName(), t.sourceFile(),
                    t.startLine(), t.inDegree(), t.outDegree(), t.betweenness(), t.pageRank(), i + 1));
        }
        int edges = all.stream().mapToInt(TypeCentrality::outDegree).sum();
        return new CentralityReport(commitSha, all.size(), edges, includeTests,
                rankBy == null ? "betweenness" : rankBy.toLowerCase(Locale.ROOT), top);
    }

    List<TypeCentrality> compute(UUID repositoryId, String commitSha, boolean includeTests) {
        String key = repositoryId + ":" + commitSha + ":" + includeTests;
        List<TypeCentrality> hit = cache.get(key);
        if (hit != null) return hit;
        List<TypeCentrality> result = compute(graphs.getTypeGraph(repositoryId, commitSha), includeTests);
        cache.put(key, result);
        return result;
    }

    /** Centrality of every type of {@code typeGraph} (self-loops ignored). Visible for tests. */
    public static List<TypeCentrality> compute(Graph<GraphNode, GraphEdge> typeGraph, boolean includeTests) {
        Set<GraphNode> vertices = typeGraph.vertexSet().stream()
                .filter(v -> includeTests || !v.isTestCode())
                .filter(v -> isProgramCode(v))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Graph<GraphNode, GraphEdge> g = new AsSubgraph<>(typeGraph, vertices);
        if (vertices.isEmpty()) return List.of();

        Map<GraphNode, Double> betweenness = new BetweennessCentrality<>(g, true).getScores();
        Map<GraphNode, Double> pageRank = new PageRank<>(g, DAMPING, 200, 1e-9).getScores();

        List<TypeCentrality> out = new ArrayList<>(vertices.size());
        for (GraphNode v : vertices) {
            int in = (int) g.incomingEdgesOf(v).stream().filter(e -> !g.getEdgeSource(e).equals(v)).map(g::getEdgeSource).distinct().count();
            int outDeg = (int) g.outgoingEdgesOf(v).stream().filter(e -> !g.getEdgeTarget(e).equals(v)).map(g::getEdgeTarget).distinct().count();
            out.add(new TypeCentrality(v.getId(), v.getQualifiedName(), v.getSimpleName(), v.getPackageName(), v.getSourceFile(),
                    v.getStartLine(), in, outDeg, round(betweenness.getOrDefault(v, 0.0)), round(pageRank.getOrDefault(v, 0.0)), 0));
        }
        out.sort(Comparator.comparing(TypeCentrality::qualifiedName));
        return List.copyOf(out);
    }

    private static double round(double v) {
        return Math.round(v * 1_000_000d) / 1_000_000d;
    }

    /**
     * Centrality ranks program types. Pages, stylesheets and database objects are linked into the graph (a shared
     * stylesheet would otherwise top PageRank), but they are not where architectural risk concentrates.
     */
    static boolean isProgramCode(GraphNode v) {
        if (v.getQualifiedName() != null && v.getQualifiedName().startsWith(com.changeimpact.parser.language.ts.EmbeddedSql.PREFIX)) return false;
        String f = v.getSourceFile() == null ? "" : v.getSourceFile().toLowerCase(java.util.Locale.ROOT);
        return !f.matches(".*\\.(html|htm|jinja|jinja2|j2|twig|njk|liquid|css|sql)$");
    }
}

package com.changeimpact.impact.service;

import com.changeimpact.common.config.AppProperties;
import com.changeimpact.graph.model.GraphEdge;
import com.changeimpact.graph.model.GraphNode;
import com.changeimpact.parser.model.DependencyType;
import lombok.RequiredArgsConstructor;
import org.jgrapht.Graph;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Propagates a change backwards through the dependency graph (who depends on the changed symbol) and keeps,
 * for every affected symbol, the <b>strongest</b> dependency path found within the depth limit.
 *
 * <h2>Strength</h2>
 * {@code strength(path) = Π weight(edge) × decay^(depth-1)} where {@code weight(edge)} is the highest
 * configured weight among the relationship kinds on that edge (e.g. EXTENDS 1.0, METHOD_CALL 0.9,
 * PARAMETER_TYPE 0.6, IMPORT 0.3). So a direct caller of a changed method scores 0.9, a caller of that
 * caller 0.9 × 0.9 × 0.85 ≈ 0.69, while something that only mentions the type in an import scores 0.3.
 * Transitive dependents are therefore not treated as equally risky.
 *
 * <p>Search: best-first (max-heap on strength). Because all weights are ≤ 1, strengths never increase
 * along a path, which makes the first time a node is settled its strongest path (Dijkstra on -log).
 * Paths exceeding {@code maxDepth} hops are not expanded.
 */
@Service
@RequiredArgsConstructor
public class ImpactPropagationService {

    private final AppProperties appProperties;

    /** Traversal options. {@code relationshipTypes} empty = all configured kinds. */
    public record Options(int maxDepth, Set<DependencyType> relationshipTypes) {}

    /** One step of a dependency path; {@code relationship} = how this symbol depends on the previous one. */
    public record PathStep(UUID symbolId, String qualifiedName, String simpleName, String symbolType,
                           List<String> relationship, int line) {}

    /** An affected symbol with the strongest path from the changed symbol (path[0]) to it (last element). */
    public record Hit(GraphNode node, int depth, double strength, List<PathStep> path) {}

    public Options defaultOptions() {
        return new Options(appProperties.getImpact().getDefaultMaxDepth(), Set.of());
    }

    public List<Hit> propagate(Graph<GraphNode, GraphEdge> graph, GraphNode changed, Options options) {
        if (changed == null || !graph.containsVertex(changed)) {
            return List.of();
        }
        int maxDepth = Math.max(1, Math.min(options.maxDepth(), appProperties.getImpact().getMaxDepthLimit()));
        double decay = appProperties.getImpact().getDepthDecay();
        Map<String, Double> weights = appProperties.getImpact().getRelationshipWeights();

        // Seeds: the symbol itself, plus all members when a whole type changed.
        Set<GraphNode> seeds = new LinkedHashSet<>();
        seeds.add(changed);
        if (changed.isType()) {
            graph.vertexSet().stream().filter(n -> changed.getId().equals(n.getOwnerTypeId())).forEach(seeds::add);
        }

        record State(GraphNode node, double strength, int depth) {}
        Map<GraphNode, Double> best = new HashMap<>();
        Map<GraphNode, Integer> depthOf = new HashMap<>();
        Map<GraphNode, GraphNode> parent = new HashMap<>();
        Map<GraphNode, GraphEdge> parentEdge = new HashMap<>();
        PriorityQueue<State> queue = new PriorityQueue<>(Comparator.comparingDouble(State::strength).reversed());
        for (GraphNode s : seeds) {
            best.put(s, 1.0);
            depthOf.put(s, 0);
            queue.add(new State(s, 1.0, 0));
        }

        Set<GraphNode> settled = new HashSet<>();
        while (!queue.isEmpty()) {
            State cur = queue.poll();
            if (!settled.add(cur.node()) || cur.depth() >= maxDepth) {
                continue;
            }
            for (GraphEdge edge : graph.incomingEdgesOf(cur.node())) {
                double w = edgeWeight(edge, weights, options.relationshipTypes());
                if (w <= 0) {
                    continue;
                }
                GraphNode dependent = graph.getEdgeSource(edge);
                if (seeds.contains(dependent)) {
                    continue;
                }
                int depth = cur.depth() + 1;
                double strength = cur.strength() * w * (depth > 1 ? decay : 1.0);
                if (strength > best.getOrDefault(dependent, 0.0)) {
                    best.put(dependent, strength);
                    depthOf.put(dependent, depth);
                    parent.put(dependent, cur.node());
                    parentEdge.put(dependent, edge);
                    queue.add(new State(dependent, strength, depth));
                }
            }
        }

        List<Hit> hits = new ArrayList<>();
        for (Map.Entry<GraphNode, Double> e : best.entrySet()) {
            GraphNode node = e.getKey();
            if (seeds.contains(node)) {
                continue;
            }
            hits.add(new Hit(node, depthOf.get(node), e.getValue(), pathTo(node, parent, parentEdge, changed)));
        }
        hits.sort(Comparator.comparingDouble(Hit::strength).reversed()
                .thenComparingInt(Hit::depth)
                .thenComparing(h -> h.node().getQualifiedName()));
        int cap = appProperties.getImpact().getMaxFindingsPerChange();
        return hits.size() > cap ? hits.subList(0, cap) : hits;
    }

    static double edgeWeight(GraphEdge edge, Map<String, Double> weights, Set<DependencyType> allowed) {
        double w = 0;
        for (DependencyType t : edge.getTypes()) {
            if (allowed.isEmpty() || allowed.contains(t)) {
                w = Math.max(w, weights.getOrDefault(t.name(), 0.0));
            }
        }
        return w;
    }

    private static List<PathStep> pathTo(GraphNode node, Map<GraphNode, GraphNode> parent,
                                         Map<GraphNode, GraphEdge> parentEdge, GraphNode changed) {
        LinkedList<PathStep> path = new LinkedList<>();
        GraphNode cur = node;
        while (cur != null) {
            GraphEdge e = parentEdge.get(cur);
            path.addFirst(new PathStep(cur.getId(), cur.getQualifiedName(), cur.getSimpleName(),
                    cur.getSymbolType().name(),
                    e == null ? List.of() : e.getTypes().stream().map(Enum::name).sorted().toList(),
                    e == null ? cur.getStartLine() : e.getSourceLine()));
            cur = parent.get(cur);
        }
        // A member seed of a changed type: prefix the type itself so the path starts at the changed symbol.
        if (!path.isEmpty() && !path.getFirst().symbolId().equals(changed.getId())) {
            path.addFirst(new PathStep(changed.getId(), changed.getQualifiedName(), changed.getSimpleName(),
                    changed.getSymbolType().name(), List.of("MEMBER_OF"), changed.getStartLine()));
        }
        return path;
    }
}

package com.changeimpact.graph.service;

import com.changeimpact.common.exception.ResourceNotFoundException;
import com.changeimpact.graph.algorithms.CouplingMetricsCalculator;
import com.changeimpact.graph.algorithms.CycleDetectorService;
import com.changeimpact.graph.algorithms.DependencyPathFinder;
import com.changeimpact.graph.model.CouplingMetrics;
import com.changeimpact.graph.model.DependencyPath;
import com.changeimpact.graph.model.GraphEdge;
import com.changeimpact.graph.model.GraphNode;
import com.changeimpact.parser.model.DependencyType;
import lombok.Builder;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jgrapht.Graph;
import org.jgrapht.graph.DefaultDirectedGraph;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Query service over the JGraphT dependency graph of a repository commit.
 *
 * <p>Two graphs are maintained per (repository, commit):
 * <ul>
 *   <li><b>member graph</b> – every symbol (type, method, constructor, field) is a vertex. Used for precise
 *       traversal: dependencies, dependents (blast radius) and paths.</li>
 *   <li><b>type graph</b> – a projection where every member is folded into its enclosing type and parallel
 *       references are merged. Used for architecture-level questions: coupling metrics (Ca/Ce/I), cycles
 *       between classes, layer rules, and the readable class-level visualisation.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DependencyGraphService {

    /** Views available for the visualisation snapshot. */
    public enum GraphView { PACKAGE, CLASS, MEMBER }

    private static final int MAX_CACHED_GRAPHS = 16;

    private final DependencyGraphBuilder graphBuilder;
    private final DependencyPathFinder pathFinder;
    private final CycleDetectorService cycleDetector;
    private final CouplingMetricsCalculator metricsCalculator;

    private record CachedGraph(Graph<GraphNode, GraphEdge> members,
                               Map<UUID, GraphNode> index,
                               Graph<GraphNode, GraphEdge> types) {}

    /** Small LRU cache keyed by "repoId:commitSha" (graphs are immutable once built). */
    private final Map<String, CachedGraph> graphCache = Collections.synchronizedMap(
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, CachedGraph> eldest) {
                    return size() > MAX_CACHED_GRAPHS;
                }
            });

    private CachedGraph cached(UUID repositoryId, String commitSha) {
        String key = repositoryId + ":" + commitSha;
        CachedGraph existing = graphCache.get(key);
        if (existing != null) {
            return existing;
        }
        Graph<GraphNode, GraphEdge> members = graphBuilder.buildGraph(repositoryId, commitSha);
        Map<UUID, GraphNode> index = new HashMap<>();
        members.vertexSet().forEach(n -> index.put(n.getId(), n));
        CachedGraph built = new CachedGraph(members, index, projectToTypes(members, index));
        graphCache.put(key, built);
        return built;
    }

    public Graph<GraphNode, GraphEdge> getGraph(UUID repositoryId, String commitSha) {
        return cached(repositoryId, commitSha).members();
    }

    public Graph<GraphNode, GraphEdge> getTypeGraph(UUID repositoryId, String commitSha) {
        return cached(repositoryId, commitSha).types();
    }

    public void evictCache(UUID repositoryId, String commitSha) {
        graphCache.remove(repositoryId + ":" + commitSha);
    }

    // ---------------------------------------------------------------------
    // Traversal queries (member graph)
    // ---------------------------------------------------------------------

    public List<GraphNode> getDirectDependencies(UUID repositoryId, String commitSha, UUID symbolId) {
        return new ArrayList<>(traverse(repositoryId, commitSha, symbolId, 1, true));
    }

    public List<GraphNode> getDirectDependents(UUID repositoryId, String commitSha, UUID symbolId) {
        return new ArrayList<>(traverse(repositoryId, commitSha, symbolId, 1, false));
    }

    /** Forward (outgoing) breadth-first traversal up to {@code maxDepth} hops. */
    public Set<GraphNode> getTransitiveDependencies(UUID repositoryId, String commitSha, UUID symbolId, int maxDepth) {
        return traverse(repositoryId, commitSha, symbolId, maxDepth, true);
    }

    /** Reverse (incoming) breadth-first traversal up to {@code maxDepth} hops: the blast radius. */
    public Set<GraphNode> getTransitiveDependents(UUID repositoryId, String commitSha, UUID symbolId, int maxDepth) {
        return traverse(repositoryId, commitSha, symbolId, maxDepth, false);
    }

    /**
     * BFS from a symbol. For a type, the traversal starts from the type <i>and all of its members</i>,
     * because callers of {@code Owner#getPet(String)} are affected when the class {@code Owner} changes.
     * The start symbols themselves are excluded from the result.
     */
    private Set<GraphNode> traverse(UUID repositoryId, String commitSha, UUID symbolId, int maxDepth, boolean forward) {
        CachedGraph cg = cached(repositoryId, commitSha);
        Graph<GraphNode, GraphEdge> graph = cg.members();
        GraphNode start = findNode(cg, symbolId);

        Set<GraphNode> seeds = new LinkedHashSet<>();
        seeds.add(start);
        if (start.isType()) {
            graph.vertexSet().stream()
                    .filter(n -> start.getId().equals(n.getOwnerTypeId()))
                    .forEach(seeds::add);
        }

        Set<GraphNode> visited = new LinkedHashSet<>(seeds);
        Deque<GraphNode> frontier = new ArrayDeque<>(seeds);
        for (int depth = 0; depth < Math.max(1, maxDepth) && !frontier.isEmpty(); depth++) {
            Deque<GraphNode> next = new ArrayDeque<>();
            for (GraphNode current : frontier) {
                Set<GraphEdge> edges = forward ? graph.outgoingEdgesOf(current) : graph.incomingEdgesOf(current);
                for (GraphEdge edge : edges) {
                    GraphNode neighbor = forward ? graph.getEdgeTarget(edge) : graph.getEdgeSource(edge);
                    if (visited.add(neighbor)) {
                        next.add(neighbor);
                    }
                }
            }
            frontier = next;
        }
        visited.removeAll(seeds);
        return visited;
    }

    public Optional<DependencyPath> findShortestPath(UUID repositoryId, String commitSha,
                                                     UUID sourceSymbolId, UUID targetSymbolId) {
        CachedGraph cg = cached(repositoryId, commitSha);
        GraphNode source = findNode(cg, sourceSymbolId);
        GraphNode target = findNode(cg, targetSymbolId);
        if (source.isType() && target.isType()) {
            return pathFinder.findShortestPath(cg.types(), source, target);
        }
        return pathFinder.findShortestPath(cg.members(), source, target);
    }

    // ---------------------------------------------------------------------
    // Architecture-level analysis (type graph)
    // ---------------------------------------------------------------------

    /** A strongly connected group of types plus one concrete example cycle through it. */
    public record CycleGroup(List<GraphNode> members, List<GraphNode> exampleCycle) {}

    /**
     * Circular dependencies between types. Each non-trivial strongly connected component is reported once
     * with a shortest example cycle, which keeps the result bounded (enumerating every simple cycle is
     * exponential on real code bases).
     */
    public List<CycleGroup> findCycleGroups(UUID repositoryId, String commitSha) {
        return cycleDetector.findCycleGroups(getTypeGraph(repositoryId, commitSha)).stream()
                .map(g -> new CycleGroup(g.members(), g.exampleCycle()))
                .toList();
    }

    /** Example cycles (one per strongly connected group of types). */
    public List<List<GraphNode>> detectCycles(UUID repositoryId, String commitSha) {
        return findCycleGroups(repositoryId, commitSha).stream().map(CycleGroup::exampleCycle).toList();
    }

    /** Robert C. Martin's Ca / Ce / Instability per type. */
    public List<CouplingMetrics> calculateMetrics(UUID repositoryId, String commitSha) {
        return metricsCalculator.calculateMetrics(getTypeGraph(repositoryId, commitSha));
    }

    /**
     * Folds every member into its enclosing type. An edge A→B exists when any member of A references any
     * member of B (or B itself); relationship kinds and reference counts are merged into that edge.
     */
    static Graph<GraphNode, GraphEdge> projectToTypes(Graph<GraphNode, GraphEdge> members, Map<UUID, GraphNode> index) {
        Graph<GraphNode, GraphEdge> types = new DefaultDirectedGraph<>(GraphEdge.class);
        for (GraphNode n : members.vertexSet()) {
            GraphNode owner = index.getOrDefault(n.ownerOrSelf(), n);
            types.addVertex(owner);
        }
        for (GraphEdge e : members.edgeSet()) {
            GraphNode src = index.getOrDefault(members.getEdgeSource(e).ownerOrSelf(), members.getEdgeSource(e));
            GraphNode tgt = index.getOrDefault(members.getEdgeTarget(e).ownerOrSelf(), members.getEdgeTarget(e));
            if (src.equals(tgt)) {
                continue;
            }
            GraphEdge existing = types.getEdge(src, tgt);
            if (existing != null) {
                existing.merge(e.getTypes(), e.getOccurrences());
            } else {
                GraphEdge projected = new GraphEdge(
                        UUID.nameUUIDFromBytes((src.getId() + ">" + tgt.getId()).getBytes(StandardCharsets.UTF_8)),
                        e.getDependencyType(), e.getSourceLine(), false);
                projected.merge(e.getTypes(), e.getOccurrences() - 1);
                types.addEdge(src, tgt, projected);
            }
        }
        return types;
    }

    // ---------------------------------------------------------------------
    // Snapshot for the React Flow frontend
    // ---------------------------------------------------------------------

    @Getter
    @Builder
    public static class GraphSnapshotDto {
        private final String view;
        private final List<NodeViewDto> nodes;
        private final List<EdgeViewDto> edges;
        private final int totalNodes;
        private final int totalEdges;
    }

    @Getter
    @Builder
    public static class NodeViewDto {
        private final String id;
        private final String label;
        private final String type;
        private final String qualifiedName;
        private final String packageName;
        private final String sourceFile;
        private final int startLine;
        private final int endLine;
        /** Enclosing type id (member view) – lets the UI map member-level results onto class nodes. */
        private final String ownerId;
        /** Number of folded members (class view) or types (package view). */
        private final int memberCount;
        private final int inDegree;
        private final int outDegree;
        private final boolean testCode;
    }

    @Getter
    @Builder
    public static class EdgeViewDto {
        private final String id;
        private final String source;
        private final String target;
        /** Primary relationship kind (first occurrence). */
        private final String type;
        /** All relationship kinds merged into this edge. */
        private final List<String> types;
        /** Number of source-code references represented by this edge. */
        private final int count;
        private final int line;
    }

    public GraphSnapshotDto getGraphSnapshot(UUID repositoryId, String commitSha, String packageFilter) {
        return getGraphSnapshot(repositoryId, commitSha, packageFilter, GraphView.MEMBER);
    }

    public GraphSnapshotDto getGraphSnapshot(UUID repositoryId, String commitSha, String packageFilter, GraphView view) {
        CachedGraph cg = cached(repositoryId, commitSha);
        return switch (view) {
            case MEMBER -> snapshotOf(cg.members(), packageFilter, view, memberCounts(cg));
            case CLASS -> snapshotOf(cg.types(), packageFilter, view, memberCounts(cg));
            case PACKAGE -> packageSnapshot(cg.types(), packageFilter);
        };
    }

    private static Map<UUID, Integer> memberCounts(CachedGraph cg) {
        Map<UUID, Integer> counts = new HashMap<>();
        cg.members().vertexSet().forEach(n -> {
            if (!n.isType() && n.getOwnerTypeId() != null) {
                counts.merge(n.getOwnerTypeId(), 1, Integer::sum);
            }
        });
        return counts;
    }

    private static boolean matchesPackage(GraphNode n, String packageFilter) {
        return packageFilter == null || packageFilter.isBlank()
                || (n.getPackageName() != null && n.getPackageName().startsWith(packageFilter))
                || n.getQualifiedName().startsWith(packageFilter);
    }

    private static GraphSnapshotDto snapshotOf(Graph<GraphNode, GraphEdge> graph, String packageFilter,
                                               GraphView view, Map<UUID, Integer> memberCounts) {
        Set<GraphNode> kept = graph.vertexSet().stream()
                .filter(n -> matchesPackage(n, packageFilter))
                .collect(Collectors.toCollection(LinkedHashSet::new));

        List<EdgeViewDto> edges = new ArrayList<>();
        Map<GraphNode, int[]> degree = new HashMap<>();
        for (GraphEdge edge : graph.edgeSet()) {
            GraphNode src = graph.getEdgeSource(edge);
            GraphNode tgt = graph.getEdgeTarget(edge);
            if (kept.contains(src) && kept.contains(tgt)) {
                edges.add(toEdgeDto(edge, src.getId().toString(), tgt.getId().toString()));
                degree.computeIfAbsent(src, k -> new int[2])[1]++;
                degree.computeIfAbsent(tgt, k -> new int[2])[0]++;
            }
        }

        List<NodeViewDto> nodes = kept.stream().map(n -> {
            int[] d = degree.getOrDefault(n, new int[2]);
            return NodeViewDto.builder()
                    .id(n.getId().toString())
                    .label(n.getSimpleName())
                    .type(n.getSymbolType().name())
                    .qualifiedName(n.getQualifiedName())
                    .packageName(n.getPackageName())
                    .sourceFile(n.getSourceFile())
                    .startLine(n.getStartLine())
                    .endLine(n.getEndLine())
                    .ownerId(n.getOwnerTypeId() != null ? n.getOwnerTypeId().toString() : n.getId().toString())
                    .memberCount(memberCounts.getOrDefault(n.getId(), 0))
                    .inDegree(d[0])
                    .outDegree(d[1])
                    .testCode(n.isTestCode())
                    .build();
        }).toList();

        return GraphSnapshotDto.builder()
                .view(view.name())
                .nodes(nodes)
                .edges(edges)
                .totalNodes(nodes.size())
                .totalEdges(edges.size())
                .build();
    }

    private static GraphSnapshotDto packageSnapshot(Graph<GraphNode, GraphEdge> types, String packageFilter) {
        Map<String, Integer> typeCounts = new TreeMap<>();
        for (GraphNode n : types.vertexSet()) {
            if (matchesPackage(n, packageFilter)) {
                typeCounts.merge(packageOf(n), 1, Integer::sum);
            }
        }

        record PkgEdge(String src, String tgt) {}
        Map<PkgEdge, EnumSet<DependencyType>> edgeTypes = new LinkedHashMap<>();
        Map<PkgEdge, Integer> edgeCounts = new HashMap<>();
        for (GraphEdge e : types.edgeSet()) {
            String src = packageOf(types.getEdgeSource(e));
            String tgt = packageOf(types.getEdgeTarget(e));
            if (src.equals(tgt) || !typeCounts.containsKey(src) || !typeCounts.containsKey(tgt)) {
                continue;
            }
            PkgEdge key = new PkgEdge(src, tgt);
            edgeTypes.computeIfAbsent(key, k -> EnumSet.noneOf(DependencyType.class)).addAll(e.getTypes());
            edgeCounts.merge(key, e.getOccurrences(), Integer::sum);
        }

        Map<String, int[]> degree = new HashMap<>();
        List<EdgeViewDto> edges = new ArrayList<>();
        edgeTypes.forEach((key, kinds) -> {
            List<String> kindNames = kinds.stream().map(Enum::name).toList();
            edges.add(EdgeViewDto.builder()
                    .id(packageId(key.src()) + ">" + packageId(key.tgt()))
                    .source(packageId(key.src()))
                    .target(packageId(key.tgt()))
                    .type(kindNames.get(0))
                    .types(kindNames)
                    .count(edgeCounts.get(key))
                    .line(0)
                    .build());
            degree.computeIfAbsent(key.src(), k -> new int[2])[1]++;
            degree.computeIfAbsent(key.tgt(), k -> new int[2])[0]++;
        });

        List<NodeViewDto> nodes = typeCounts.entrySet().stream().map(entry -> {
            String pkg = entry.getKey();
            int[] d = degree.getOrDefault(pkg, new int[2]);
            return NodeViewDto.builder()
                    .id(packageId(pkg))
                    .label(pkg.isEmpty() ? "(default package)" : pkg.substring(pkg.lastIndexOf('.') + 1))
                    .type("PACKAGE")
                    .qualifiedName(pkg.isEmpty() ? "(default package)" : pkg)
                    .packageName(pkg)
                    .sourceFile("")
                    .ownerId(packageId(pkg))
                    .memberCount(entry.getValue())
                    .inDegree(d[0])
                    .outDegree(d[1])
                    .build();
        }).toList();

        return GraphSnapshotDto.builder()
                .view(GraphView.PACKAGE.name())
                .nodes(nodes)
                .edges(edges)
                .totalNodes(nodes.size())
                .totalEdges(edges.size())
                .build();
    }

    private static String packageOf(GraphNode n) {
        return n.getPackageName() != null ? n.getPackageName() : "";
    }

    private static String packageId(String pkg) {
        return "pkg:" + pkg;
    }

    private static EdgeViewDto toEdgeDto(GraphEdge edge, String source, String target) {
        return EdgeViewDto.builder()
                .id(edge.getId().toString())
                .source(source)
                .target(target)
                .type(edge.getDependencyType().name())
                .types(edge.getTypes().stream().map(Enum::name).toList())
                .count(edge.getOccurrences())
                .line(edge.getSourceLine())
                .build();
    }

    // ---------------------------------------------------------------------
    // Statistics
    // ---------------------------------------------------------------------

    @Getter
    @Builder
    public static class GraphStatsDto {
        private final int symbols;
        private final int memberEdges;
        private final int types;
        private final int typeEdges;
        private final int packages;
        private final Map<String, Long> symbolsByType;
        private final Map<String, Long> memberEdgesByRelationship;
        private final int cycleGroups;
        private final int typesInCycles;
        private final int isolatedTypes;
    }

    public GraphStatsDto getStats(UUID repositoryId, String commitSha) {
        CachedGraph cg = cached(repositoryId, commitSha);
        Map<String, Long> byType = cg.members().vertexSet().stream()
                .collect(Collectors.groupingBy(n -> n.getSymbolType().name(), TreeMap::new, Collectors.counting()));
        Map<String, Long> byRelationship = new TreeMap<>();
        cg.members().edgeSet().forEach(e -> e.getTypes().forEach(t -> byRelationship.merge(t.name(), 1L, Long::sum)));
        List<CycleGroup> cycles = findCycleGroups(repositoryId, commitSha);
        long isolated = cg.types().vertexSet().stream()
                .filter(n -> cg.types().degreeOf(n) == 0).count();
        long packages = cg.types().vertexSet().stream().map(DependencyGraphService::packageOf).distinct().count();

        return GraphStatsDto.builder()
                .symbols(cg.members().vertexSet().size())
                .memberEdges(cg.members().edgeSet().size())
                .types(cg.types().vertexSet().size())
                .typeEdges(cg.types().edgeSet().size())
                .packages((int) packages)
                .symbolsByType(byType)
                .memberEdgesByRelationship(byRelationship)
                .cycleGroups(cycles.size())
                .typesInCycles(cycles.stream().mapToInt(c -> c.members().size()).sum())
                .isolatedTypes((int) isolated)
                .build();
    }

    private GraphNode findNode(CachedGraph cg, UUID symbolId) {
        GraphNode node = cg.index().get(symbolId);
        if (node == null) {
            throw new ResourceNotFoundException("Symbol", symbolId);
        }
        return node;
    }
}

package com.changeimpact.graph.service;

import com.changeimpact.common.exception.ResourceNotFoundException;
import com.changeimpact.graph.algorithms.CouplingMetricsCalculator;
import com.changeimpact.graph.algorithms.CycleDetectorService;
import com.changeimpact.graph.algorithms.DependencyPathFinder;
import com.changeimpact.graph.model.CouplingMetrics;
import com.changeimpact.graph.model.DependencyPath;
import com.changeimpact.graph.model.GraphEdge;
import com.changeimpact.graph.model.GraphNode;
import lombok.Builder;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jgrapht.Graph;
import org.jgrapht.traverse.BreadthFirstIterator;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * High-performance query service for the JGraphT symbol dependency graph.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DependencyGraphService {

    private final DependencyGraphBuilder graphBuilder;
    private final DependencyPathFinder pathFinder;
    private final CycleDetectorService cycleDetector;
    private final CouplingMetricsCalculator metricsCalculator;

    // In-memory cache key: "repoId:commitSha"
    private final Map<String, Graph<GraphNode, GraphEdge>> graphCache = new ConcurrentHashMap<>();

    public Graph<GraphNode, GraphEdge> getGraph(UUID repositoryId, String commitSha) {
        String key = repositoryId + ":" + commitSha;
        return graphCache.computeIfAbsent(key, k -> graphBuilder.buildGraph(repositoryId, commitSha));
    }

    public void evictCache(UUID repositoryId, String commitSha) {
        graphCache.remove(repositoryId + ":" + commitSha);
    }

    // ---- Traversal Queries ----

    public List<GraphNode> getDirectDependencies(UUID repositoryId, String commitSha, UUID symbolId) {
        Graph<GraphNode, GraphEdge> graph = getGraph(repositoryId, commitSha);
        GraphNode node = findNode(graph, symbolId);

        return graph.outgoingEdgesOf(node).stream()
                .map(graph::getEdgeTarget)
                .collect(Collectors.toList());
    }

    public List<GraphNode> getDirectDependents(UUID repositoryId, String commitSha, UUID symbolId) {
        Graph<GraphNode, GraphEdge> graph = getGraph(repositoryId, commitSha);
        GraphNode node = findNode(graph, symbolId);

        return graph.incomingEdgesOf(node).stream()
                .map(graph::getEdgeSource)
                .collect(Collectors.toList());
    }

    /**
     * Traverses forward dependencies up to the specified max depth.
     */
    public Set<GraphNode> getTransitiveDependencies(UUID repositoryId, String commitSha, UUID symbolId, int maxDepth) {
        Graph<GraphNode, GraphEdge> graph = getGraph(repositoryId, commitSha);
        GraphNode startNode = findNode(graph, symbolId);

        Set<GraphNode> visited = new HashSet<>();
        Queue<GraphNode> queue = new ArrayDeque<>();
        queue.add(startNode);
        visited.add(startNode);

        int currentDepth = 0;
        while (!queue.isEmpty() && currentDepth < maxDepth) {
            int levelSize = queue.size();
            for (int i = 0; i < levelSize; i++) {
                GraphNode current = queue.poll();
                for (GraphEdge edge : graph.outgoingEdgesOf(current)) {
                    GraphNode neighbor = graph.getEdgeTarget(edge);
                    if (visited.add(neighbor)) {
                        queue.add(neighbor);
                    }
                }
            }
            currentDepth++;
        }

        visited.remove(startNode); // exclude self
        return visited;
    }

    /**
     * Traverses reverse dependents (blast radius) up to the specified max depth.
     */
    public Set<GraphNode> getTransitiveDependents(UUID repositoryId, String commitSha, UUID symbolId, int maxDepth) {
        Graph<GraphNode, GraphEdge> graph = getGraph(repositoryId, commitSha);
        GraphNode startNode = findNode(graph, symbolId);

        Set<GraphNode> visited = new HashSet<>();
        Queue<GraphNode> queue = new ArrayDeque<>();
        queue.add(startNode);
        visited.add(startNode);

        int currentDepth = 0;
        while (!queue.isEmpty() && currentDepth < maxDepth) {
            int levelSize = queue.size();
            for (int i = 0; i < levelSize; i++) {
                GraphNode current = queue.poll();
                for (GraphEdge edge : graph.incomingEdgesOf(current)) {
                    GraphNode dependent = graph.getEdgeSource(edge);
                    if (visited.add(dependent)) {
                        queue.add(dependent);
                    }
                }
            }
            currentDepth++;
        }

        visited.remove(startNode);
        return visited;
    }

    public Optional<DependencyPath> findShortestPath(UUID repositoryId, String commitSha,
                                                     UUID sourceSymbolId, UUID targetSymbolId) {
        Graph<GraphNode, GraphEdge> graph = getGraph(repositoryId, commitSha);
        GraphNode source = findNode(graph, sourceSymbolId);
        GraphNode target = findNode(graph, targetSymbolId);

        return pathFinder.findShortestPath(graph, source, target);
    }

    public List<List<GraphNode>> detectCycles(UUID repositoryId, String commitSha) {
        Graph<GraphNode, GraphEdge> graph = getGraph(repositoryId, commitSha);
        return cycleDetector.findSimpleCycles(graph);
    }

    public List<CouplingMetrics> calculateMetrics(UUID repositoryId, String commitSha) {
        Graph<GraphNode, GraphEdge> graph = getGraph(repositoryId, commitSha);
        return metricsCalculator.calculateMetrics(graph);
    }

    // ---- Snapshot for React Flow Frontend ----

    @Getter
    @Builder
    public static class GraphSnapshotDto {
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
        private final String sourceFile;
        private final int startLine;
        private final int endLine;
    }

    @Getter
    @Builder
    public static class EdgeViewDto {
        private final String id;
        private final String source;
        private final String target;
        private final String type;
        private final int line;
    }

    public GraphSnapshotDto getGraphSnapshot(UUID repositoryId, String commitSha, String packageFilter) {
        Graph<GraphNode, GraphEdge> graph = getGraph(repositoryId, commitSha);

        List<NodeViewDto> nodes = new ArrayList<>();
        Set<GraphNode> filteredNodes = new HashSet<>();

        for (GraphNode node : graph.vertexSet()) {
            if (packageFilter == null || packageFilter.isBlank() || node.getQualifiedName().startsWith(packageFilter)) {
                filteredNodes.add(node);
                nodes.add(NodeViewDto.builder()
                        .id(node.getId().toString())
                        .label(node.getSimpleName())
                        .type(node.getSymbolType().name())
                        .qualifiedName(node.getQualifiedName())
                        .sourceFile(node.getSourceFile())
                        .startLine(node.getStartLine())
                        .endLine(node.getEndLine())
                        .build());
            }
        }

        List<EdgeViewDto> edges = new ArrayList<>();
        for (GraphEdge edge : graph.edgeSet()) {
            GraphNode src = graph.getEdgeSource(edge);
            GraphNode tgt = graph.getEdgeTarget(edge);

            if (filteredNodes.contains(src) && filteredNodes.contains(tgt)) {
                edges.add(EdgeViewDto.builder()
                        .id(edge.getId().toString())
                        .source(src.getId().toString())
                        .target(tgt.getId().toString())
                        .type(edge.getDependencyType().name())
                        .line(edge.getSourceLine())
                        .build());
            }
        }

        return GraphSnapshotDto.builder()
                .nodes(nodes)
                .edges(edges)
                .totalNodes(nodes.size())
                .totalEdges(edges.size())
                .build();
    }

    private GraphNode findNode(Graph<GraphNode, GraphEdge> graph, UUID symbolId) {
        return graph.vertexSet().stream()
                .filter(n -> n.getId().equals(symbolId))
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("Symbol", symbolId));
    }
}


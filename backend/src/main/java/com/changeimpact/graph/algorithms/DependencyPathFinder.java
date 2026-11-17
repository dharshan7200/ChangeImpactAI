package com.changeimpact.graph.algorithms;

import com.changeimpact.graph.model.DependencyPath;
import com.changeimpact.graph.model.GraphEdge;
import com.changeimpact.graph.model.GraphNode;
import org.jgrapht.Graph;
import org.jgrapht.alg.shortestpath.DijkstraShortestPath;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.Optional;

/**
 * Computes shortest directed dependency paths between two symbols in JGraphT.
 */
@Component
public class DependencyPathFinder {

    /**
     * Finds the shortest directed path from sourceNode to targetNode.
     */
    public Optional<DependencyPath> findShortestPath(Graph<GraphNode, GraphEdge> graph,
                                                     GraphNode sourceNode,
                                                     GraphNode targetNode) {
        if (!graph.containsVertex(sourceNode) || !graph.containsVertex(targetNode)) {
            return Optional.empty();
        }

        DijkstraShortestPath<GraphNode, GraphEdge> dijkstra = new DijkstraShortestPath<>(graph);
        var path = dijkstra.getPath(sourceNode, targetNode);

        if (path == null) {
            return Optional.empty();
        }

        return Optional.of(DependencyPath.builder()
                .nodes(path.getVertexList())
                .edges(path.getEdgeList())
                .length(path.getLength())
                .build());
    }
}


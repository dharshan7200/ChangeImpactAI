package com.changeimpact.graph.algorithms;

import com.changeimpact.graph.model.GraphEdge;
import com.changeimpact.graph.model.GraphNode;
import org.jgrapht.Graph;
import org.jgrapht.alg.cycle.CycleDetector;
import org.jgrapht.alg.cycle.TarjanSimpleCycles;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * Detects circular dependency cycles within the symbol graph using JGraphT.
 */
@Component
public class CycleDetectorService {

    /**
     * Checks if the graph contains any cycles.
     */
    public boolean hasCycles(Graph<GraphNode, GraphEdge> graph) {
        CycleDetector<GraphNode, GraphEdge> detector = new CycleDetector<>(graph);
        return detector.detectCycles();
    }

    /**
     * Finds all nodes participating in circular dependency loops.
     */
    public Set<GraphNode> findCycleNodes(Graph<GraphNode, GraphEdge> graph) {
        CycleDetector<GraphNode, GraphEdge> detector = new CycleDetector<>(graph);
        return detector.findCycles();
    }

    /**
     * Discovers all elementary directed cycles in the graph via Tarjan's algorithm.
     * Each returned list represents an ordered sequence of nodes forming a closed loop.
     */
    public List<List<GraphNode>> findSimpleCycles(Graph<GraphNode, GraphEdge> graph) {
        if (!hasCycles(graph)) {
            return Collections.emptyList();
        }
        TarjanSimpleCycles<GraphNode, GraphEdge> tarjan = new TarjanSimpleCycles<>(graph);
        return tarjan.findSimpleCycles();
    }
}


package com.changeimpact.graph.algorithms;

import com.changeimpact.graph.model.GraphEdge;
import com.changeimpact.graph.model.GraphNode;
import org.jgrapht.Graph;
import org.jgrapht.alg.connectivity.KosarajuStrongConnectivityInspector;
import org.jgrapht.alg.cycle.CycleDetector;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Detects circular dependencies using strongly connected components (SCCs).
 *
 * <p>Every SCC with more than one vertex is a group of mutually dependent symbols. Reporting each group
 * once, together with one shortest example cycle, keeps the output linear in the graph size. (Listing
 * every elementary cycle with Tarjan/Johnson is exponential in the worst case and produced one violation
 * per cycle on real code bases.)
 */
@Component
public class CycleDetectorService {

    /** A non-trivial strongly connected component and a shortest cycle through its first member. */
    public record CycleGroup(List<GraphNode> members, List<GraphNode> exampleCycle) {}

    public boolean hasCycles(Graph<GraphNode, GraphEdge> graph) {
        return new CycleDetector<>(graph).detectCycles();
    }

    /** All vertices that take part in at least one cycle. */
    public Set<GraphNode> findCycleNodes(Graph<GraphNode, GraphEdge> graph) {
        return new CycleDetector<>(graph).findCycles();
    }

    public List<CycleGroup> findCycleGroups(Graph<GraphNode, GraphEdge> graph) {
        List<CycleGroup> groups = new ArrayList<>();
        for (Set<GraphNode> scc : new KosarajuStrongConnectivityInspector<>(graph).stronglyConnectedSets()) {
            if (scc.size() < 2) {
                continue; // self-loops are excluded when the graph is built
            }
            List<GraphNode> members = new ArrayList<>(scc);
            members.sort(Comparator.comparing(GraphNode::getQualifiedName));
            groups.add(new CycleGroup(members, shortestCycleThrough(graph, members.get(0), scc)));
        }
        groups.sort(Comparator.comparingInt((CycleGroup g) -> g.members().size()).reversed());
        return groups;
    }

    /** One shortest cycle per strongly connected group (bounded alternative to enumerating all cycles). */
    public List<List<GraphNode>> findSimpleCycles(Graph<GraphNode, GraphEdge> graph) {
        return findCycleGroups(graph).stream().map(CycleGroup::exampleCycle).toList();
    }

    /** BFS restricted to the SCC from {@code start}'s successors back to {@code start}. */
    private static List<GraphNode> shortestCycleThrough(Graph<GraphNode, GraphEdge> graph, GraphNode start,
                                                        Set<GraphNode> scc) {
        Map<GraphNode, GraphNode> previous = new HashMap<>();
        Deque<GraphNode> queue = new ArrayDeque<>();
        for (GraphEdge e : graph.outgoingEdgesOf(start)) {
            GraphNode next = graph.getEdgeTarget(e);
            if (scc.contains(next) && !previous.containsKey(next)) {
                previous.put(next, start);
                queue.add(next);
            }
        }
        while (!queue.isEmpty()) {
            GraphNode current = queue.poll();
            for (GraphEdge e : graph.outgoingEdgesOf(current)) {
                GraphNode next = graph.getEdgeTarget(e);
                if (next.equals(start)) {
                    LinkedList<GraphNode> cycle = new LinkedList<>();
                    for (GraphNode n = current; n != null && !n.equals(start); n = previous.get(n)) {
                        cycle.addFirst(n);
                    }
                    cycle.addFirst(start);
                    return cycle;
                }
                if (scc.contains(next) && !previous.containsKey(next)) {
                    previous.put(next, current);
                    queue.add(next);
                }
            }
        }
        return List.of(start); // unreachable for a valid SCC
    }
}

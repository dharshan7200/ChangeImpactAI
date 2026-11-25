package com.changeimpact.architecture.twin;

import org.jgrapht.Graph;
import org.jgrapht.graph.DefaultDirectedGraph;
import org.jgrapht.graph.DefaultEdge;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Architecture twin: percentile ranks, history sampling, layer order")
class ArchitectureTwinAlgorithmsTest {

    @Test
    @DisplayName("percentile ranks: ties share a rank, zero stays zero, the maximum ranks 1")
    void percentileRanks() {
        double[] r = ArchitectureTwinService.percentileRanks(new double[]{0, 5, 5, 10, 20});
        assertArrayEquals(new double[]{0, 0.375, 0.375, 0.75, 1.0}, r, 1e-9);
        assertArrayEquals(new double[]{0, 0, 0}, ArchitectureTwinService.percentileRanks(new double[]{0, 0, 0}), 1e-9);
        assertArrayEquals(new double[]{0}, ArchitectureTwinService.percentileRanks(new double[]{7}), 1e-9);
    }

    @Test
    @DisplayName("history sampling: evenly spaced, newest and oldest included, oldest first")
    void sample() {
        List<String> newestFirst = IntStream.range(0, 100).mapToObj(i -> "c" + i).toList();
        List<String> picked = ArchitectureHistoryService.sample(newestFirst, 5);
        assertEquals(List.of("c99", "c74", "c50", "c25", "c0"), picked);
        assertEquals(List.of("c2", "c1", "c0"), ArchitectureHistoryService.sample(List.of("c0", "c1", "c2"), 10));
    }

    @Test
    @DisplayName("layer order: dependencies before dependents, a cycle kept together, ties by name")
    void layerOrder() {
        Graph<String, DefaultEdge> g = new DefaultDirectedGraph<>(DefaultEdge.class);
        for (String p : List.of("web", "service", "repo", "domain", "util", "a", "b")) g.addVertex(p);
        g.addEdge("web", "service");
        g.addEdge("service", "repo");
        g.addEdge("repo", "domain");
        g.addEdge("service", "domain");
        g.addEdge("domain", "util");
        g.addEdge("a", "b");
        g.addEdge("b", "a");      // cycle
        g.addEdge("a", "util");
        Map<String, Integer> o = ArchitectureTwinService.layerOrder(g, Map.of("a", 0, "b", 0));
        assertTrue(o.get("util") < o.get("domain") && o.get("domain") < o.get("repo") && o.get("repo") < o.get("service")
                && o.get("service") < o.get("web"), o.toString());
        assertEquals(1, Math.abs(o.get("a") - o.get("b")), "cycle members adjacent: " + o);
        assertTrue(o.get("util") < o.get("a"), o.toString());
        assertEquals(7, o.values().stream().distinct().count());
    }
}

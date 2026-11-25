package com.changeimpact.graph.algorithms;

import com.changeimpact.graph.model.GraphEdge;
import com.changeimpact.graph.model.GraphNode;
import com.changeimpact.parser.model.DependencyType;
import com.changeimpact.parser.model.SymbolType;
import org.jgrapht.Graph;
import org.jgrapht.graph.DefaultDirectedGraph;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

@DisplayName("Centrality of types: values computed by hand")
class CentralityServiceTest {

    private static GraphNode type(String name, boolean test) {
        return GraphNode.builder().id(UUID.nameUUIDFromBytes(name.getBytes())).qualifiedName("p." + name).simpleName(name)
                .symbolType(SymbolType.CLASS).packageName("p").sourceFile(name + ".java").startLine(1).testCode(test).build();
    }

    /** A → B, D → B, B → C, plus test T → C. Production shortest paths A→C and D→C both pass through B. */
    private static Graph<GraphNode, GraphEdge> graph() {
        Graph<GraphNode, GraphEdge> g = new DefaultDirectedGraph<>(GraphEdge.class);
        GraphNode a = type("A", false), b = type("B", false), c = type("C", false), d = type("D", false), t = type("T", true);
        for (GraphNode n : new GraphNode[]{a, b, c, d, t}) g.addVertex(n);
        g.addEdge(a, b, new GraphEdge(UUID.randomUUID(), DependencyType.METHOD_CALL, 1, false));
        g.addEdge(d, b, new GraphEdge(UUID.randomUUID(), DependencyType.METHOD_CALL, 1, false));
        g.addEdge(b, c, new GraphEdge(UUID.randomUUID(), DependencyType.METHOD_CALL, 1, false));
        g.addEdge(t, c, new GraphEdge(UUID.randomUUID(), DependencyType.METHOD_CALL, 1, false));
        return g;
    }

    private static Map<String, CentralityService.TypeCentrality> byName(boolean includeTests) {
        return CentralityService.compute(graph(), includeTests).stream()
                .collect(Collectors.toMap(CentralityService.TypeCentrality::simpleName, Function.identity()));
    }

    @Test
    void brokerHasTheBetweennessAndTheSinkTheHighestPageRank() {
        var r = byName(false);
        assertThat(r).doesNotContainKey("T");                        // test code excluded by default
        // B lies on 2 of the (n-1)(n-2) = 6 ordered pairs' shortest paths
        assertThat(r.get("B").betweenness()).isCloseTo(2.0 / 6.0, within(1e-6));
        assertThat(r.get("A").betweenness()).isZero();
        assertThat(r.get("C").betweenness()).isZero();
        assertThat(r.get("B").inDegree()).isEqualTo(2);
        assertThat(r.get("B").outDegree()).isEqualTo(1);
        assertThat(r.get("C").pageRank()).isGreaterThan(r.get("B").pageRank());
        assertThat(r.get("B").pageRank()).isGreaterThan(r.get("A").pageRank());
        assertThat(r.values().stream().mapToDouble(CentralityService.TypeCentrality::pageRank).sum()).isCloseTo(1.0, within(1e-4));
    }

    @Test
    void testCodeCountsOnlyWhenRequested() {
        var r = byName(true);
        assertThat(r).containsKey("T");
        assertThat(r.get("C").inDegree()).isEqualTo(2);
    }

    @Test
    void deterministicOrder() {
        assertThat(CentralityService.compute(graph(), false)).isEqualTo(CentralityService.compute(graph(), false));
    }

    @org.junit.jupiter.api.Test
    void pagesStylesheetsAndTablesAreNotRanked() {
        java.util.function.BiFunction<String, String, com.changeimpact.graph.model.GraphNode> n = (qn, file) ->
                com.changeimpact.graph.model.GraphNode.builder().qualifiedName(qn).sourceFile(file).build();
        org.assertj.core.api.Assertions.assertThat(CentralityService.isProgramCode(n.apply("a.Owner", "src/a/Owner.java"))).isTrue();
        org.assertj.core.api.Assertions.assertThat(CentralityService.isProgramCode(n.apply("src.Button", "src/Button.tsx"))).isTrue();
        org.assertj.core.api.Assertions.assertThat(CentralityService.isProgramCode(n.apply("static.site.css", "static/site.css"))).isFalse();
        org.assertj.core.api.Assertions.assertThat(CentralityService.isProgramCode(n.apply("t.layout.html", "t/layout.html"))).isFalse();
        org.assertj.core.api.Assertions.assertThat(CentralityService.isProgramCode(n.apply("db:owners", "db/schema.sql"))).isFalse();
    }
}

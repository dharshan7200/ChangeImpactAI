package com.changeimpact.impact.service;

import com.changeimpact.common.config.AppProperties;
import com.changeimpact.graph.model.GraphEdge;
import com.changeimpact.graph.model.GraphNode;
import com.changeimpact.parser.model.DependencyType;
import com.changeimpact.parser.model.SymbolType;
import org.jgrapht.Graph;
import org.jgrapht.graph.DefaultDirectedGraph;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Impact propagation (weighted strongest paths)")
class ImpactPropagationServiceTest {

    private ImpactPropagationService service;
    private Graph<GraphNode, GraphEdge> g;
    private GraphNode repoType, findById, service1, controller, importer, testNode;

    private static GraphNode node(String qn, SymbolType type, UUID owner, boolean test) {
        UUID id = UUID.randomUUID();
        return GraphNode.builder().id(id).qualifiedName(qn).simpleName(qn.replaceAll("\\(.*$", "").replaceAll(".*[.#]", ""))
                .symbolType(type).ownerTypeId(owner == null ? id : owner).testCode(test).packageName("p").build();
    }

    private void edge(GraphNode from, GraphNode to, DependencyType... types) {
        GraphEdge e = new GraphEdge(UUID.randomUUID(), types[0], 10, false);
        for (int i = 1; i < types.length; i++) e.merge(types[i], 1);
        g.addEdge(from, to, e);
    }

    @BeforeEach
    void setUp() {
        AppProperties props = new AppProperties();
        service = new ImpactPropagationService(props);
        g = new DefaultDirectedGraph<>(GraphEdge.class);
        repoType = node("p.Repo", SymbolType.INTERFACE, null, false);
        findById = node("p.Repo#findById(String)", SymbolType.METHOD, repoType.getId(), false);
        service1 = node("p.Service#load(String)", SymbolType.METHOD, null, false);
        controller = node("p.Controller#show(String)", SymbolType.METHOD, null, false);
        importer = node("p.Other", SymbolType.CLASS, null, false);
        testNode = node("p.ServiceTest#loads()", SymbolType.METHOD, null, true);
        List.of(repoType, findById, service1, controller, importer, testNode).forEach(g::addVertex);
        edge(service1, findById, DependencyType.METHOD_CALL);
        edge(controller, service1, DependencyType.METHOD_CALL, DependencyType.PARAMETER_TYPE);
        edge(importer, repoType, DependencyType.IMPORT);
        edge(testNode, service1, DependencyType.METHOD_CALL);
    }

    @Test
    @DisplayName("strength decays with depth and relationship weight; paths are recorded")
    void strengthsAndPaths() {
        var hits = service.propagate(g, findById, new ImpactPropagationService.Options(3, Set.of()));
        Map<String, ImpactPropagationService.Hit> byName = hits.stream()
                .collect(Collectors.toMap(h -> h.node().getQualifiedName(), h -> h));

        assertEquals(1, byName.get("p.Service#load(String)").depth());
        assertEquals(0.9, byName.get("p.Service#load(String)").strength(), 1e-9);
        assertEquals(2, byName.get("p.Controller#show(String)").depth());
        assertEquals(0.9 * 0.9 * 0.85, byName.get("p.Controller#show(String)").strength(), 1e-9);
        assertTrue(byName.get("p.ServiceTest#loads()").node().isTestCode());
        assertFalse(byName.containsKey("p.Other"), "importing the interface is not a dependency on findById");

        var path = byName.get("p.Controller#show(String)").path();
        assertEquals(List.of("findById", "load", "show"), path.stream().map(ImpactPropagationService.PathStep::simpleName).toList());
        assertEquals(List.of("METHOD_CALL", "PARAMETER_TYPE"), path.get(2).relationship());
    }

    @Test
    @DisplayName("depth limit and relationship filter are honoured")
    void depthAndFilter() {
        // ServiceTest#loads calls Service#load, i.e. it is 2 hops away from findById
        assertEquals(Set.of("p.Service#load(String)"),
                service.propagate(g, findById, new ImpactPropagationService.Options(1, Set.of())).stream()
                        .map(h -> h.node().getQualifiedName()).collect(Collectors.toSet()));
        assertTrue(service.propagate(g, findById, new ImpactPropagationService.Options(3, Set.of(DependencyType.EXTENDS))).isEmpty());
    }

    @Test
    @DisplayName("a changed type propagates from all its members; weak IMPORT edges score low")
    void typeChangeIncludesMembers() {
        var hits = service.propagate(g, repoType, new ImpactPropagationService.Options(3, Set.of()));
        Map<String, Double> strength = hits.stream().collect(Collectors.toMap(h -> h.node().getQualifiedName(),
                ImpactPropagationService.Hit::strength));
        assertEquals(0.9, strength.get("p.Service#load(String)"), 1e-9);
        assertEquals(0.3, strength.get("p.Other"), 1e-9);
        var path = hits.stream().filter(h -> h.node().equals(service1)).findFirst().orElseThrow().path();
        assertEquals("Repo", path.get(0).simpleName());
        assertEquals(List.of("MEMBER_OF"), path.get(0).relationship());
    }
}

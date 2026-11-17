package com.changeimpact.graph.service;

import com.changeimpact.graph.algorithms.CouplingMetricsCalculator;
import com.changeimpact.graph.algorithms.CycleDetectorService;
import com.changeimpact.graph.algorithms.DependencyPathFinder;
import com.changeimpact.graph.model.*;
import com.changeimpact.parser.model.DependencyType;
import com.changeimpact.parser.model.SymbolType;
import org.jgrapht.Graph;
import org.jgrapht.graph.DefaultDirectedGraph;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("DependencyGraphService & Algorithms Tests")
class DependencyGraphServiceTest {

    @Mock
    private DependencyGraphBuilder graphBuilder;

    private DependencyPathFinder pathFinder;
    private CycleDetectorService cycleDetector;
    private CouplingMetricsCalculator metricsCalculator;
    private DependencyGraphService graphService;

    private UUID repoId;
    private String commitSha;

    private GraphNode nodeController;
    private GraphNode nodeService;
    private GraphNode nodeRepository;
    private GraphNode nodeDatabase;

    private Graph<GraphNode, GraphEdge> sampleGraph;

    @BeforeEach
    void setUp() {
        pathFinder = new DependencyPathFinder();
        cycleDetector = new CycleDetectorService();
        metricsCalculator = new CouplingMetricsCalculator();
        graphService = new DependencyGraphService(graphBuilder, pathFinder, cycleDetector, metricsCalculator);

        repoId = UUID.randomUUID();
        commitSha = "abc1234";

        nodeController = GraphNode.builder().id(UUID.randomUUID()).qualifiedName("com.example.OrderController").simpleName("OrderController").symbolType(SymbolType.CLASS).build();
        nodeService = GraphNode.builder().id(UUID.randomUUID()).qualifiedName("com.example.OrderService").simpleName("OrderService").symbolType(SymbolType.CLASS).build();
        nodeRepository = GraphNode.builder().id(UUID.randomUUID()).qualifiedName("com.example.OrderRepository").simpleName("OrderRepository").symbolType(SymbolType.INTERFACE).build();
        nodeDatabase = GraphNode.builder().id(UUID.randomUUID()).qualifiedName("com.example.Database").simpleName("Database").symbolType(SymbolType.CLASS).build();

        sampleGraph = new DefaultDirectedGraph<>(GraphEdge.class);
        sampleGraph.addVertex(nodeController);
        sampleGraph.addVertex(nodeService);
        sampleGraph.addVertex(nodeRepository);
        sampleGraph.addVertex(nodeDatabase);

        // Controller -> Service -> Repository -> Database
        sampleGraph.addEdge(nodeController, nodeService, new GraphEdge(UUID.randomUUID(), DependencyType.FIELD_ACCESS, 10, false));
        sampleGraph.addEdge(nodeService, nodeRepository, new GraphEdge(UUID.randomUUID(), DependencyType.FIELD_ACCESS, 20, false));
        sampleGraph.addEdge(nodeRepository, nodeDatabase, new GraphEdge(UUID.randomUUID(), DependencyType.METHOD_CALL, 30, false));

        when(graphBuilder.buildGraph(repoId, commitSha)).thenReturn(sampleGraph);
    }

    @Test
    @DisplayName("Should retrieve direct dependencies and direct dependents correctly")
    void shouldFindDirectDependenciesAndDependents() {
        List<GraphNode> dependenciesOfService = graphService.getDirectDependencies(repoId, commitSha, nodeService.getId());
        assertEquals(1, dependenciesOfService.size());
        assertEquals("OrderRepository", dependenciesOfService.get(0).getSimpleName());

        List<GraphNode> dependentsOfService = graphService.getDirectDependents(repoId, commitSha, nodeService.getId());
        assertEquals(1, dependentsOfService.size());
        assertEquals("OrderController", dependentsOfService.get(0).getSimpleName());
    }

    @Test
    @DisplayName("Should calculate blast radius (transitive dependents) accurately")
    void shouldCalculateBlastRadius() {
        // If Database changes, which components are in the blast radius up to depth 3?
        Set<GraphNode> blastRadius = graphService.getTransitiveDependents(repoId, commitSha, nodeDatabase.getId(), 3);

        assertEquals(3, blastRadius.size());
        assertTrue(blastRadius.contains(nodeRepository));
        assertTrue(blastRadius.contains(nodeService));
        assertTrue(blastRadius.contains(nodeController));
    }

    @Test
    @DisplayName("Should find shortest dependency path between symbols")
    void shouldFindShortestPath() {
        Optional<DependencyPath> pathOpt = graphService.findShortestPath(
                repoId, commitSha, nodeController.getId(), nodeDatabase.getId());

        assertTrue(pathOpt.isPresent());
        DependencyPath path = pathOpt.get();
        assertEquals(3, path.getLength());
        assertEquals(4, path.getNodes().size());
        assertEquals("OrderController", path.getNodes().get(0).getSimpleName());
        assertEquals("Database", path.getNodes().get(3).getSimpleName());
    }

    @Test
    @DisplayName("Should detect circular dependency loops")
    void shouldDetectCircularDependencies() {
        // Initially acyclic
        List<List<GraphNode>> initialCycles = graphService.detectCycles(repoId, commitSha);
        assertTrue(initialCycles.isEmpty());

        // Introduce circular edge: Database -> Service
        sampleGraph.addEdge(nodeDatabase, nodeService, new GraphEdge(UUID.randomUUID(), DependencyType.METHOD_CALL, 40, false));

        List<List<GraphNode>> cycles = graphService.detectCycles(repoId, commitSha);
        assertFalse(cycles.isEmpty(), "Must discover the cycle Service -> Repository -> Database -> Service");
    }

    @Test
    @DisplayName("Should calculate coupling and instability metrics")
    void shouldCalculateCouplingMetrics() {
        List<CouplingMetrics> metrics = graphService.calculateMetrics(repoId, commitSha);

        assertNotNull(metrics);
        assertEquals(4, metrics.size());

        // OrderController: in=0 (Ca), out=1 (Ce) => I = 1.0 (completely dependent)
        CouplingMetrics controllerMetrics = metrics.stream()
                .filter(m -> m.getQualifiedName().contains("Controller"))
                .findFirst().orElseThrow();
        assertEquals(0, controllerMetrics.getAfferentCoupling());
        assertEquals(1, controllerMetrics.getEfferentCoupling());
        assertEquals(1.0, controllerMetrics.getInstability());
    }
}


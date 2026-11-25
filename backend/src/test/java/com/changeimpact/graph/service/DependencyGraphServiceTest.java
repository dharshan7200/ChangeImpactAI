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
import static org.mockito.Mockito.lenient;
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

        lenient().when(graphBuilder.buildGraph(repoId, commitSha)).thenReturn(sampleGraph);
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
        // Graphs only change through re-indexing, which evicts the cached graph + type projection.
        graphService.evictCache(repoId, commitSha);

        List<List<GraphNode>> cycles = graphService.detectCycles(repoId, commitSha);
        assertEquals(1, cycles.size(), "One strongly connected group: Service -> Repository -> Database -> Service");
        assertEquals(Set.of(nodeService, nodeRepository, nodeDatabase), new HashSet<>(cycles.get(0)));
        assertFalse(cycles.get(0).contains(nodeController), "Controller only depends on the cycle; it is not part of it");
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

    @Test
    @DisplayName("Blast radius of a class includes callers of its methods; class view merges member references")
    void classBlastRadiusIncludesCallersOfMembers() {
        UUID ownerId = UUID.randomUUID();
        UUID controllerId = UUID.randomUUID();
        GraphNode owner = GraphNode.builder().id(ownerId).qualifiedName("p.Owner").simpleName("Owner")
                .symbolType(SymbolType.CLASS).ownerTypeId(ownerId).packageName("p").build();
        GraphNode getPet = GraphNode.builder().id(UUID.randomUUID()).qualifiedName("p.Owner#getPet(String)")
                .simpleName("getPet").symbolType(SymbolType.METHOD).ownerTypeId(ownerId).packageName("p").build();
        GraphNode controller = GraphNode.builder().id(controllerId).qualifiedName("p.web.OwnerController")
                .simpleName("OwnerController").symbolType(SymbolType.CLASS).ownerTypeId(controllerId).packageName("p.web").build();
        GraphNode handler = GraphNode.builder().id(UUID.randomUUID()).qualifiedName("p.web.OwnerController#show(String)")
                .simpleName("show").symbolType(SymbolType.METHOD).ownerTypeId(controllerId).packageName("p.web").build();
        GraphNode create = GraphNode.builder().id(UUID.randomUUID()).qualifiedName("p.web.OwnerController#create()")
                .simpleName("create").symbolType(SymbolType.METHOD).ownerTypeId(controllerId).packageName("p.web").build();

        Graph<GraphNode, GraphEdge> g = new DefaultDirectedGraph<>(GraphEdge.class);
        List.of(owner, getPet, controller, handler, create).forEach(g::addVertex);
        g.addEdge(handler, getPet, new GraphEdge(UUID.randomUUID(), DependencyType.METHOD_CALL, 12, false));
        g.addEdge(create, owner, new GraphEdge(UUID.randomUUID(), DependencyType.CONSTRUCTOR_CALL, 20, false));

        UUID otherRepo = UUID.randomUUID();
        when(graphBuilder.buildGraph(otherRepo, "c1")).thenReturn(g);

        // Method-level: exactly the caller of getPet
        assertEquals(Set.of(handler), graphService.getTransitiveDependents(otherRepo, "c1", getPet.getId(), 3));
        // Class-level: callers of any Owner member (and of Owner itself)
        assertEquals(Set.of(handler, create), graphService.getTransitiveDependents(otherRepo, "c1", ownerId, 3));

        var classView = graphService.getGraphSnapshot(otherRepo, "c1", null, DependencyGraphService.GraphView.CLASS);
        assertEquals(2, classView.getTotalNodes());
        assertEquals(1, classView.getTotalEdges(), "both member references fold into one class-level edge");
        var edge = classView.getEdges().get(0);
        assertEquals(controllerId.toString(), edge.getSource());
        assertEquals(ownerId.toString(), edge.getTarget());
        assertEquals(2, edge.getCount());
        assertEquals(Set.of("METHOD_CALL", "CONSTRUCTOR_CALL"), new HashSet<>(edge.getTypes()));

        var packageView = graphService.getGraphSnapshot(otherRepo, "c1", null, DependencyGraphService.GraphView.PACKAGE);
        assertEquals(2, packageView.getTotalNodes());
        assertEquals(1, packageView.getTotalEdges());
    }
}

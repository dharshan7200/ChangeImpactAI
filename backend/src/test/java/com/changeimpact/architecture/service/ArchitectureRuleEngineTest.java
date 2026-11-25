package com.changeimpact.architecture.service;

import com.changeimpact.architecture.entity.ArchitectureRule;
import com.changeimpact.architecture.entity.ArchitectureViolation;
import com.changeimpact.architecture.repository.ArchitectureRuleRepository;
import com.changeimpact.architecture.repository.ArchitectureViolationRepository;
import com.changeimpact.graph.model.GraphEdge;
import com.changeimpact.graph.model.GraphNode;
import com.changeimpact.graph.service.DependencyGraphService;
import com.changeimpact.parser.model.DependencyType;
import com.changeimpact.parser.model.SymbolType;
import com.changeimpact.repository.entity.Repository;
import com.changeimpact.symbols.entity.Symbol;
import com.changeimpact.symbols.repository.SymbolRepository;
import org.jgrapht.Graph;
import org.jgrapht.graph.DefaultDirectedGraph;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("ArchitectureRuleEngine Tests")
class ArchitectureRuleEngineTest {

    @Mock
    private ArchitectureRuleRepository architectureRuleRepository;
    @Mock
    private ArchitectureViolationRepository architectureViolationRepository;
    @Mock
    private DependencyGraphService dependencyGraphService;
    @Mock
    private SymbolRepository symbolRepository;

    @InjectMocks
    private ArchitectureRuleEngine architectureRuleEngine;

    private Repository repository;
    private String commitSha;

    @BeforeEach
    void setUp() {
        repository = Repository.builder().id(UUID.randomUUID()).fullName("owner/repo").build();
        commitSha = "c123456";

        when(architectureViolationRepository.saveAll(anyList())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    @DisplayName("Should detect Controller-to-Repository direct layer violation")
    void shouldDetectLayerViolation() {
        ArchitectureRule layerRule = ArchitectureRule.builder()
                .id(UUID.randomUUID())
                .ruleName("Controller to Repository Direct Access Violation")
                .ruleType("LAYER_VIOLATION")
                .severity("HIGH")
                .configuration(Map.of(
                        "sourcePattern", ".*Controller.*",
                        "forbiddenTargetPattern", ".*Repository.*"
                ))
                .enabled(true)
                .build();

        when(architectureRuleRepository.findEffectiveRulesForRepository(repository.getId()))
                .thenReturn(List.of(layerRule));

        GraphNode controller = GraphNode.builder().id(UUID.randomUUID()).qualifiedName("com.example.controller.OrderController").simpleName("OrderController").symbolType(SymbolType.CLASS).build();
        GraphNode repoNode = GraphNode.builder().id(UUID.randomUUID()).qualifiedName("com.example.repository.OrderRepository").simpleName("OrderRepository").symbolType(SymbolType.INTERFACE).build();

        Graph<GraphNode, GraphEdge> graph = new DefaultDirectedGraph<>(GraphEdge.class);
        graph.addVertex(controller);
        graph.addVertex(repoNode);
        graph.addEdge(controller, repoNode, new GraphEdge(UUID.randomUUID(), DependencyType.FIELD_ACCESS, 15, false));

        when(dependencyGraphService.getTypeGraph(repository.getId(), commitSha)).thenReturn(graph);

        Symbol controllerSym = Symbol.builder().id(controller.getId()).qualifiedName(controller.getQualifiedName()).build();
        Symbol repoSym = Symbol.builder().id(repoNode.getId()).qualifiedName(repoNode.getQualifiedName()).build();
        when(symbolRepository.findByRepositoryIdAndCommitSha(repository.getId(), commitSha))
                .thenReturn(List.of(controllerSym, repoSym));

        List<ArchitectureViolation> violations = architectureRuleEngine.analyzeArchitecture(repository, commitSha);

        assertNotNull(violations);
        assertEquals(1, violations.size());
        ArchitectureViolation v = violations.get(0);
        assertEquals("HIGH", v.getSeverity());
        assertTrue(v.getDescription().contains("directly depends on forbidden target"));
    }

    @Test
    @DisplayName("Layer rules ignore test code (tests legitimately use repositories directly)")
    void shouldIgnoreTestCodeForLayerRules() {
        ArchitectureRule layerRule = ArchitectureRule.builder()
                .id(UUID.randomUUID())
                .ruleName("Controller to Repository Direct Access Violation")
                .ruleType("LAYER_VIOLATION")
                .severity("HIGH")
                .configuration(Map.of("sourcePattern", ".*Controller.*", "forbiddenTargetPattern", ".*Repository.*"))
                .enabled(true)
                .build();
        when(architectureRuleRepository.findEffectiveRulesForRepository(repository.getId())).thenReturn(List.of(layerRule));

        GraphNode controllerTest = GraphNode.builder().id(UUID.randomUUID())
                .qualifiedName("com.example.controller.OrderControllerTests").simpleName("OrderControllerTests")
                .symbolType(SymbolType.CLASS).testCode(true).build();
        GraphNode repoNode = GraphNode.builder().id(UUID.randomUUID())
                .qualifiedName("com.example.repository.OrderRepository").simpleName("OrderRepository")
                .symbolType(SymbolType.INTERFACE).build();
        Graph<GraphNode, GraphEdge> graph = new DefaultDirectedGraph<>(GraphEdge.class);
        graph.addVertex(controllerTest);
        graph.addVertex(repoNode);
        graph.addEdge(controllerTest, repoNode, new GraphEdge(UUID.randomUUID(), DependencyType.FIELD_TYPE, 15, false));
        when(dependencyGraphService.getTypeGraph(repository.getId(), commitSha)).thenReturn(graph);
        when(symbolRepository.findByRepositoryIdAndCommitSha(repository.getId(), commitSha)).thenReturn(List.of());

        assertTrue(architectureRuleEngine.analyzeArchitecture(repository, commitSha).isEmpty());
    }
}

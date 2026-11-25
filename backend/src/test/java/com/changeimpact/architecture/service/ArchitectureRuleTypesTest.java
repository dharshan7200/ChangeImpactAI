package com.changeimpact.architecture.service;

import com.changeimpact.architecture.entity.ArchitectureRule;
import com.changeimpact.architecture.entity.ArchitectureViolation;
import com.changeimpact.graph.model.GraphEdge;
import com.changeimpact.graph.model.GraphNode;
import com.changeimpact.parser.model.DependencyType;
import com.changeimpact.parser.model.SymbolType;
import com.changeimpact.repository.entity.Repository;
import org.jgrapht.Graph;
import org.jgrapht.graph.DefaultDirectedGraph;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Architecture rule types, pattern budget and rule validation")
class ArchitectureRuleTypesTest {

    private final ArchitectureRuleEngine engine = new ArchitectureRuleEngine(null, null, null, null);
    private final Repository repo = Repository.builder().id(UUID.randomUUID()).fullName("o/r").build();

    private static GraphNode type(String qn, boolean test) {
        return GraphNode.builder().id(UUID.nameUUIDFromBytes(qn.getBytes())).qualifiedName(qn).simpleName(qn.substring(qn.lastIndexOf('.') + 1))
                .symbolType(SymbolType.CLASS).sourceFile(qn.replace('.', '/') + ".java").startLine(1).testCode(test).build();
    }

    /** web → service → repo is fine; repo → web is upward; web → repo skips the service layer; test code ignored. */
    private static Graph<GraphNode, GraphEdge> graph() {
        Graph<GraphNode, GraphEdge> g = new DefaultDirectedGraph<>(GraphEdge.class);
        GraphNode web = type("app.web.OrderController", false), svc = type("app.service.OrderService", false),
                repo = type("app.repo.OrderRepository", false), util = type("app.util.Strings", false),
                test = type("app.web.OrderControllerTest", true);
        for (GraphNode n : List.of(web, svc, repo, util, test)) g.addVertex(n);
        edge(g, web, svc); edge(g, svc, repo); edge(g, repo, web); edge(g, web, repo); edge(g, svc, util); edge(g, test, repo);
        return g;
    }

    private static void edge(Graph<GraphNode, GraphEdge> g, GraphNode a, GraphNode b) {
        g.addEdge(a, b, new GraphEdge(UUID.randomUUID(), DependencyType.METHOD_CALL, 3, false));
    }

    private List<ArchitectureViolation> run(String type, Map<String, Object> config) {
        ArchitectureRule rule = ArchitectureRule.builder().ruleName("r").ruleType(type).severity("HIGH")
                .configuration(ArchitectureRuleEngine.validate(type, config)).build();
        return engine.evaluate(repo, "c1", rule, graph(), Map.of());
    }

    private static Map<String, Object> layers(boolean allowSkipping) {
        return Map.of("layers", List.of(Map.of("name", "Web", "pattern", "\\.web\\."), Map.of("name", "Service", "pattern", "\\.service\\."),
                Map.of("name", "Repository", "pattern", "\\.repo\\.")), "allowLayerSkipping", allowSkipping);
    }

    @Test
    void layeredArchitectureFindsUpwardDependencies() {
        var v = run("LAYERED_ARCHITECTURE", layers(true));
        assertThat(v).extracting(ArchitectureViolation::getDescription)
                .singleElement().asString().contains("upward dependency", "'Repository' must not use the higher layer 'Web'", "OrderRepository");
    }

    @Test
    void strictLayeringAlsoFindsSkippedLayers() {
        var v = run("LAYERED_ARCHITECTURE", layers(false));
        assertThat(v).hasSize(2).extracting(ArchitectureViolation::getDescription)
                .anySatisfy(d -> assertThat(d).contains("layer skipped", "'Web' may only use the next layer 'Service', not 'Repository'"));
    }

    @Test
    void onlyAccessedByFlagsOtherUsersButNotTestsOrItself() {
        var v = run("ONLY_ACCESSED_BY", Map.of("targetPattern", "\\.repo\\.", "allowedSourcePattern", "\\.service\\."));
        assertThat(v).extracting(ArchitectureViolation::getDescription).singleElement().asString().contains("OrderController", "OrderRepository");
    }

    @Test
    void forbiddenDependencyStillWorks() {
        assertThat(run("FORBIDDEN_DEPENDENCY", Map.of("sourcePattern", "\\.service\\.", "forbiddenTargetPattern", "\\.util\\."))).hasSize(1);
    }

    @Test
    void catastrophicBacktrackingIsRejectedAtSaveTimeAndBoundedAtRunTime() {
        assertThatThrownBy(() -> ArchitectureRuleEngine.validate("FORBIDDEN_DEPENDENCY",
                Map.of("sourcePattern", "(.*a){8}b", "forbiddenTargetPattern", "x")))  // polynomial blow-up on Java 21
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("too much backtracking");
        BoundedPattern p = BoundedPattern.compile("(.*.*)*x");               // ~550k steps on this input
        assertThatThrownBy(() -> p.find("a".repeat(80) + "!")).isInstanceOf(BoundedPattern.BudgetExceeded.class);
        assertThat(BoundedPattern.compile("\\.web\\.").find("app.web.OrderController")).isTrue();
    }

    @Test
    void validationNamesTheProblem() {
        assertThatThrownBy(() -> ArchitectureRuleEngine.validate("NO_SUCH", Map.of())).hasMessageContaining("ruleType must be one of");
        assertThatThrownBy(() -> ArchitectureRuleEngine.validate("LAYER_VIOLATION", Map.of("sourcePattern", "x")))
                .hasMessageContaining("forbiddenTargetPattern is required");
        assertThatThrownBy(() -> ArchitectureRuleEngine.validate("LAYER_VIOLATION", Map.of("sourcePattern", "([", "forbiddenTargetPattern", "x")))
                .hasMessageContaining("sourcePattern: invalid regular expression");
        assertThatThrownBy(() -> ArchitectureRuleEngine.validate("LAYERED_ARCHITECTURE", Map.of("layers", List.of(Map.of("name", "A", "pattern", "a")))))
                .hasMessageContaining("2–20 layers");
        assertThatThrownBy(() -> ArchitectureRuleEngine.validate("LAYERED_ARCHITECTURE",
                Map.of("layers", List.of(Map.of("name", "A", "pattern", "a"), Map.of("name", "a", "pattern", "b")))))
                .hasMessageContaining("duplicate layer name");
        assertThat(ArchitectureRuleEngine.validate("CIRCULAR_DEPENDENCY", Map.of("ignored", 1))).isEmpty();
    }

    @Test
    void requestValidation() {
        ArchitectureRule r = ArchitectureRule.builder().build();
        assertThatThrownBy(() -> ArchitectureRuleService.apply(r, new ArchitectureRuleService.RuleRequest(" ", "CIRCULAR_DEPENDENCY", null, null, null, null), true))
                .hasMessageContaining("ruleName");
        assertThatThrownBy(() -> ArchitectureRuleService.apply(r, new ArchitectureRuleService.RuleRequest("n", "CIRCULAR_DEPENDENCY", null, "URGENT", null, null), true))
                .hasMessageContaining("severity");
        ArchitectureRuleService.apply(r, new ArchitectureRuleService.RuleRequest(" Web stays on top ", "layered_architecture", "d", "low", layers(true), null), true);
        assertThat(r.getRuleName()).isEqualTo("Web stays on top");
        assertThat(r.getRuleType()).isEqualTo("LAYERED_ARCHITECTURE");
        assertThat(r.getSeverity()).isEqualTo("LOW");
        assertThat(r.getEnabled()).isTrue();
        // partial update keeps the rest
        ArchitectureRuleService.apply(r, new ArchitectureRuleService.RuleRequest(null, null, null, "HIGH", null, false), false);
        assertThat(r.getRuleName()).isEqualTo("Web stays on top");
        assertThat(r.getConfiguration()).containsKey("layers");
        assertThat(r.getEnabled()).isFalse();
    }
}

package com.changeimpact.architecture.service;

import com.changeimpact.architecture.entity.ArchitectureRule;
import com.changeimpact.architecture.entity.ArchitectureViolation;
import com.changeimpact.architecture.repository.ArchitectureRuleRepository;
import com.changeimpact.architecture.repository.ArchitectureViolationRepository;
import com.changeimpact.graph.model.GraphEdge;
import com.changeimpact.graph.model.GraphNode;
import com.changeimpact.graph.service.DependencyGraphService;
import com.changeimpact.repository.entity.Repository;
import com.changeimpact.symbols.entity.Symbol;
import com.changeimpact.symbols.repository.SymbolRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jgrapht.Graph;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Validates repository architectural boundaries on the type-level dependency graph (one finding per class pair /
 * cycle group, not per method call). Rule types — the vocabulary follows ArchUnit's most used checks:
 * <ul>
 *   <li>{@code CIRCULAR_DEPENDENCY} — types that depend on each other in a cycle.</li>
 *   <li>{@code LAYER_VIOLATION} / {@code FORBIDDEN_DEPENDENCY} — {@code sourcePattern} must not use
 *       {@code forbiddenTargetPattern}.</li>
 *   <li>{@code LAYERED_ARCHITECTURE} — {@code layers} listed top to bottom (each {@code name} + {@code pattern}); a layer
 *       may use layers below it, never above; with {@code allowLayerSkipping=false} only the next layer down.</li>
 *   <li>{@code ONLY_ACCESSED_BY} — types matching {@code targetPattern} may only be used by types matching
 *       {@code allowedSourcePattern} (or by each other).</li>
 * </ul>
 * Patterns are case-insensitive regular expressions matched ("find") against qualified names, under a step budget
 * ({@link BoundedPattern}). Layer, forbidden-dependency and access rules apply to production code only: test code
 * legitimately wires and mocks every layer.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ArchitectureRuleEngine {

    public static final Set<String> RULE_TYPES = Set.of("CIRCULAR_DEPENDENCY", "LAYER_VIOLATION", "FORBIDDEN_DEPENDENCY",
            "LAYERED_ARCHITECTURE", "ONLY_ACCESSED_BY");
    static final int MAX_LAYERS = 20;

    private final ArchitectureRuleRepository architectureRuleRepository;
    private final ArchitectureViolationRepository architectureViolationRepository;
    private final DependencyGraphService dependencyGraphService;
    private final SymbolRepository symbolRepository;

    /** Executes all rules in effect for the repository against the dependency graph of a commit and stores the violations. */
    @Transactional
    public List<ArchitectureViolation> analyzeArchitecture(Repository repository, String commitSha) {
        log.info("Running architecture analysis for repo: {} [commit: {}]", repository.getFullName(), commitSha);
        List<ArchitectureRule> rules = architectureRuleRepository.findEffectiveRulesForRepository(repository.getId());
        Graph<GraphNode, GraphEdge> graph = dependencyGraphService.getTypeGraph(repository.getId(), commitSha);
        Map<UUID, Symbol> symbolEntityMap = symbolMap(repository, commitSha);
        List<ArchitectureViolation> violations = new ArrayList<>();
        for (ArchitectureRule rule : rules) {
            try {
                violations.addAll(evaluate(repository, commitSha, rule, graph, symbolEntityMap));
            } catch (RuntimeException e) { // a broken or too expensive rule must not fail indexing or analysis
                log.warn("Architecture rule '{}' skipped: {}", rule.getRuleName(), e.getMessage());
            }
        }
        List<ArchitectureViolation> savedViolations = architectureViolationRepository.saveAll(violations);
        log.info("Architecture analysis finished: detected {} violations for repo: {}", savedViolations.size(), repository.getFullName());
        return savedViolations;
    }

    /** Violations of one rule at a commit, not stored (rule preview). */
    @Transactional(readOnly = true)
    public List<ArchitectureViolation> preview(Repository repository, String commitSha, ArchitectureRule rule) {
        return evaluate(repository, commitSha, rule, dependencyGraphService.getTypeGraph(repository.getId(), commitSha),
                symbolMap(repository, commitSha));
    }

    private Map<UUID, Symbol> symbolMap(Repository repository, String commitSha) {
        return symbolRepository.findByRepositoryIdAndCommitSha(repository.getId(), commitSha).stream()
                .collect(Collectors.toMap(Symbol::getId, s -> s));
    }

    List<ArchitectureViolation> evaluate(Repository repository, String commitSha, ArchitectureRule rule,
                                         Graph<GraphNode, GraphEdge> graph, Map<UUID, Symbol> symbolMap) {
        List<ArchitectureViolation> out = new ArrayList<>();
        Map<String, Object> config = rule.getConfiguration() == null ? Map.of() : rule.getConfiguration();
        switch (rule.getRuleType()) {
            case "CIRCULAR_DEPENDENCY" -> checkCircularDependencies(repository, commitSha, rule, symbolMap, out);
            case "LAYER_VIOLATION", "FORBIDDEN_DEPENDENCY" -> {
                if (config.get("sourcePattern") == null || config.get("forbiddenTargetPattern") == null) return out;
                BoundedPattern source = BoundedPattern.compile((String) config.get("sourcePattern"));
                BoundedPattern forbidden = BoundedPattern.compile((String) config.get("forbiddenTargetPattern"));
                forEachProductionEdge(graph, (s, t, e) -> {
                    if (source.find(s.getQualifiedName()) && forbidden.find(t.getQualifiedName())) {
                        out.add(violation(repository, commitSha, rule, symbolMap, s, t, e, String.format(
                                "Architecture violation [%s]: '%s' (%s) directly depends on forbidden target '%s' (%s) via %d reference(s) %s",
                                rule.getRuleName(), s.getSimpleName(), s.getQualifiedName(), t.getSimpleName(), t.getQualifiedName(),
                                e.getOccurrences(), e.getTypes())));
                    }
                });
            }
            case "LAYERED_ARCHITECTURE" -> checkLayers(repository, commitSha, rule, config, graph, symbolMap, out);
            case "ONLY_ACCESSED_BY" -> {
                BoundedPattern target = BoundedPattern.compile((String) config.get("targetPattern"));
                BoundedPattern allowed = BoundedPattern.compile((String) config.get("allowedSourcePattern"));
                forEachProductionEdge(graph, (s, t, e) -> {
                    if (target.find(t.getQualifiedName()) && !target.find(s.getQualifiedName()) && !allowed.find(s.getQualifiedName())) {
                        out.add(violation(repository, commitSha, rule, symbolMap, s, t, e, String.format(
                                "Architecture violation [%s]: '%s' (%s) uses '%s', which may only be accessed by types matching %s (%d reference(s) %s)",
                                rule.getRuleName(), s.getSimpleName(), s.getQualifiedName(), t.getSimpleName(), allowed,
                                e.getOccurrences(), e.getTypes())));
                    }
                });
            }
            default -> log.debug("Unknown architecture rule type: {}", rule.getRuleType());
        }
        return out;
    }

    // ------------------------------------------------------------------------------------------------ rule types

    private void checkCircularDependencies(Repository repository, String commitSha, ArchitectureRule rule,
                                           Map<UUID, Symbol> symbolMap, List<ArchitectureViolation> violations) {
        for (DependencyGraphService.CycleGroup group : dependencyGraphService.findCycleGroups(repository.getId(), commitSha)) {
            List<GraphNode> cycle = group.exampleCycle();
            String cycleChain = cycle.stream().map(GraphNode::getSimpleName).collect(Collectors.joining(" -> "))
                    + " -> " + cycle.get(0).getSimpleName();
            String groupNote = group.members().size() > cycle.size()
                    ? String.format(" (%d types are mutually dependent in this group: %s)", group.members().size(),
                        group.members().stream().map(GraphNode::getSimpleName).collect(Collectors.joining(", ")))
                    : "";
            GraphNode firstNode = cycle.get(0);
            GraphNode secondNode = cycle.size() > 1 ? cycle.get(1) : firstNode;
            violations.add(ArchitectureViolation.builder()
                    .repository(repository).commitSha(commitSha).rule(rule).ruleName(rule.getRuleName()).severity(rule.getSeverity())
                    .sourceSymbol(symbolMap.get(firstNode.getId())).targetSymbol(symbolMap.get(secondNode.getId()))
                    .description("Circular dependency: " + cycleChain + groupNote)
                    .sourceLocation(firstNode.getSourceFile() + ":" + firstNode.getStartLine())
                    .build());
        }
    }

    record Layer(String name, BoundedPattern pattern) {}

    @SuppressWarnings("unchecked")
    static List<Layer> layers(Map<String, Object> config) {
        List<Layer> layers = new ArrayList<>();
        for (Object o : (List<Object>) config.getOrDefault("layers", List.of())) {
            Map<String, Object> l = (Map<String, Object>) o;
            layers.add(new Layer(String.valueOf(l.get("name")), BoundedPattern.compile((String) l.get("pattern"))));
        }
        return layers;
    }

    /** Index of the first layer whose pattern matches, or -1 for types outside every layer. */
    static int layerOf(List<Layer> layers, String qualifiedName) {
        for (int i = 0; i < layers.size(); i++) if (layers.get(i).pattern().find(qualifiedName)) return i;
        return -1;
    }

    private void checkLayers(Repository repository, String commitSha, ArchitectureRule rule, Map<String, Object> config,
                             Graph<GraphNode, GraphEdge> graph, Map<UUID, Symbol> symbolMap, List<ArchitectureViolation> out) {
        List<Layer> layers = layers(config);
        boolean allowSkipping = !Boolean.FALSE.equals(config.get("allowLayerSkipping"));
        Map<GraphNode, Integer> layerOf = new HashMap<>();
        forEachProductionEdge(graph, (s, t, e) -> {
            int from = layerOf.computeIfAbsent(s, n -> layerOf(layers, n.getQualifiedName()));
            int to = layerOf.computeIfAbsent(t, n -> layerOf(layers, n.getQualifiedName()));
            if (from < 0 || to < 0 || from == to) return;
            String reason = null;
            if (to < from) reason = "upward dependency: layer '" + layers.get(from).name() + "' must not use the higher layer '" + layers.get(to).name() + "'";
            else if (!allowSkipping && to > from + 1) reason = "layer skipped: '" + layers.get(from).name() + "' may only use the next layer '"
                    + layers.get(from + 1).name() + "', not '" + layers.get(to).name() + "'";
            if (reason != null) {
                out.add(violation(repository, commitSha, rule, symbolMap, s, t, e, String.format(
                        "Architecture violation [%s]: %s — '%s' (%s) uses '%s' (%s) via %d reference(s) %s",
                        rule.getRuleName(), reason, s.getSimpleName(), s.getQualifiedName(), t.getSimpleName(), t.getQualifiedName(),
                        e.getOccurrences(), e.getTypes())));
            }
        });
    }

    // ------------------------------------------------------------------------------------------------ helpers

    @FunctionalInterface
    private interface EdgeVisitor {
        void visit(GraphNode source, GraphNode target, GraphEdge edge);
    }

    /** Production-code edges in a stable order (by source, then target qualified name). */
    private static void forEachProductionEdge(Graph<GraphNode, GraphEdge> graph, EdgeVisitor visitor) {
        graph.edgeSet().stream()
                .filter(e -> !graph.getEdgeSource(e).isTestCode())
                .sorted(Comparator.comparing((GraphEdge e) -> graph.getEdgeSource(e).getQualifiedName())
                        .thenComparing(e -> graph.getEdgeTarget(e).getQualifiedName()))
                .forEach(e -> visitor.visit(graph.getEdgeSource(e), graph.getEdgeTarget(e), e));
    }

    private static ArchitectureViolation violation(Repository repository, String commitSha, ArchitectureRule rule,
                                                   Map<UUID, Symbol> symbolMap, GraphNode s, GraphNode t, GraphEdge e, String description) {
        return ArchitectureViolation.builder()
                .repository(repository).commitSha(commitSha).rule(rule).ruleName(rule.getRuleName()).severity(rule.getSeverity())
                .sourceSymbol(symbolMap.get(s.getId())).targetSymbol(symbolMap.get(t.getId()))
                .description(description)
                .sourceLocation(s.getSourceFile() + ":" + e.getSourceLine())
                .build();
    }

    // ------------------------------------------------------------------------------------------------ validation

    /**
     * Checks a rule definition and returns its normalised configuration (only the keys the rule type uses).
     * IllegalArgumentException with a message a user can act on.
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> validate(String ruleType, Map<String, Object> configuration) {
        if (ruleType == null || !RULE_TYPES.contains(ruleType)) {
            throw new IllegalArgumentException("ruleType must be one of " + new TreeSet<>(RULE_TYPES));
        }
        Map<String, Object> c = configuration == null ? Map.of() : configuration;
        Map<String, Object> out = new LinkedHashMap<>();
        switch (ruleType) {
            case "CIRCULAR_DEPENDENCY" -> { }
            case "LAYER_VIOLATION", "FORBIDDEN_DEPENDENCY" -> {
                out.put("sourcePattern", pattern(c, "sourcePattern"));
                out.put("forbiddenTargetPattern", pattern(c, "forbiddenTargetPattern"));
            }
            case "ONLY_ACCESSED_BY" -> {
                out.put("targetPattern", pattern(c, "targetPattern"));
                out.put("allowedSourcePattern", pattern(c, "allowedSourcePattern"));
            }
            case "LAYERED_ARCHITECTURE" -> {
                if (!(c.get("layers") instanceof List<?> raw) || raw.size() < 2 || raw.size() > MAX_LAYERS) {
                    throw new IllegalArgumentException("layers: list 2–" + MAX_LAYERS + " layers, top to bottom, each {name, pattern}");
                }
                List<Map<String, Object>> layers = new ArrayList<>();
                Set<String> names = new HashSet<>();
                for (Object o : raw) {
                    if (!(o instanceof Map<?, ?> m)) throw new IllegalArgumentException("each layer needs a name and a pattern");
                    Map<String, Object> l = (Map<String, Object>) m;
                    String name = l.get("name") == null ? "" : String.valueOf(l.get("name")).strip();
                    if (name.isEmpty() || name.length() > 60) throw new IllegalArgumentException("layer name: 1–60 characters");
                    if (!names.add(name.toLowerCase(Locale.ROOT))) throw new IllegalArgumentException("duplicate layer name '" + name + "'");
                    layers.add(new LinkedHashMap<>(Map.of("name", name, "pattern", pattern(l, "pattern"))));
                }
                out.put("layers", layers);
                out.put("allowLayerSkipping", !Boolean.FALSE.equals(c.get("allowLayerSkipping")));
            }
            default -> throw new IllegalStateException();
        }
        return out;
    }

    private static String pattern(Map<String, Object> c, String key) {
        Object v = c.get(key);
        if (!(v instanceof String s) || s.isBlank()) throw new IllegalArgumentException(key + " is required");
        try {
            BoundedPattern p = BoundedPattern.compile(s.strip());
            p.find("a".repeat(80) + "!");          // adversarial probe: rejects catastrophic backtracking at save time
            p.find("com.example.app.web.controller.OrderController$Inner");
            return s.strip();
        } catch (BoundedPattern.BudgetExceeded e) {
            throw new IllegalArgumentException(key + ": " + e.getMessage());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(key + ": " + e.getMessage());
        }
    }
}

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
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Validates repository architectural boundaries, identifying circular dependencies,
 * layer violations, and forbidden package interactions.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ArchitectureRuleEngine {

    private final ArchitectureRuleRepository architectureRuleRepository;
    private final ArchitectureViolationRepository architectureViolationRepository;
    private final DependencyGraphService dependencyGraphService;
    private final SymbolRepository symbolRepository;

    /**
     * Executes all active architectural rules against the dependency graph for a commit.
     */
    @Transactional
    public List<ArchitectureViolation> analyzeArchitecture(Repository repository, String commitSha) {
        log.info("Running architecture analysis for repo: {} [commit: {}]", repository.getFullName(), commitSha);

        List<ArchitectureRule> rules = architectureRuleRepository.findEffectiveRulesForRepository(repository.getId());
        Graph<GraphNode, GraphEdge> graph = dependencyGraphService.getGraph(repository.getId(), commitSha);

        Map<UUID, Symbol> symbolEntityMap = symbolRepository
                .findByRepositoryIdAndCommitSha(repository.getId(), commitSha)
                .stream()
                .collect(Collectors.toMap(Symbol::getId, s -> s));

        List<ArchitectureViolation> violations = new ArrayList<>();

        for (ArchitectureRule rule : rules) {
            switch (rule.getRuleType()) {
                case "CIRCULAR_DEPENDENCY" -> checkCircularDependencies(repository, commitSha, rule, graph, symbolEntityMap, violations);
                case "LAYER_VIOLATION", "FORBIDDEN_DEPENDENCY" -> checkLayerViolations(repository, commitSha, rule, graph, symbolEntityMap, violations);
                default -> log.debug("Unknown architecture rule type: {}", rule.getRuleType());
            }
        }

        List<ArchitectureViolation> savedViolations = architectureViolationRepository.saveAll(violations);
        log.info("Architecture analysis finished: detected {} violations for repo: {}",
                savedViolations.size(), repository.getFullName());

        return savedViolations;
    }

    private void checkCircularDependencies(Repository repository, String commitSha, ArchitectureRule rule,
                                           Graph<GraphNode, GraphEdge> graph, Map<UUID, Symbol> symbolMap,
                                           List<ArchitectureViolation> violations) {
        List<List<GraphNode>> cycles = dependencyGraphService.detectCycles(repository.getId(), commitSha);

        for (List<GraphNode> cycle : cycles) {
            String cycleChain = cycle.stream()
                    .map(GraphNode::getSimpleName)
                    .collect(Collectors.joining(" -> ")) + " -> " + cycle.get(0).getSimpleName();

            GraphNode firstNode = cycle.get(0);
            GraphNode lastNode = cycle.get(cycle.size() - 1);

            violations.add(ArchitectureViolation.builder()
                    .repository(repository)
                    .commitSha(commitSha)
                    .rule(rule)
                    .ruleName(rule.getRuleName())
                    .severity(rule.getSeverity())
                    .sourceSymbol(symbolMap.get(firstNode.getId()))
                    .targetSymbol(symbolMap.get(lastNode.getId()))
                    .description("Circular dependency cycle: " + cycleChain)
                    .sourceLocation(firstNode.getSourceFile() + ":" + firstNode.getStartLine())
                    .build());
        }
    }

    private void checkLayerViolations(Repository repository, String commitSha, ArchitectureRule rule,
                                      Graph<GraphNode, GraphEdge> graph, Map<UUID, Symbol> symbolMap,
                                      List<ArchitectureViolation> violations) {
        Map<String, Object> config = rule.getConfiguration();
        if (config == null) return;

        String sourcePatternStr = (String) config.get("sourcePattern");
        String forbiddenPatternStr = (String) config.get("forbiddenTargetPattern");

        if (sourcePatternStr == null || forbiddenPatternStr == null) return;

        Pattern sourcePattern = Pattern.compile(sourcePatternStr, Pattern.CASE_INSENSITIVE);
        Pattern forbiddenPattern = Pattern.compile(forbiddenPatternStr, Pattern.CASE_INSENSITIVE);

        for (GraphEdge edge : graph.edgeSet()) {
            GraphNode source = graph.getEdgeSource(edge);
            GraphNode target = graph.getEdgeTarget(edge);

            if (sourcePattern.matcher(source.getQualifiedName()).find() &&
                forbiddenPattern.matcher(target.getQualifiedName()).find()) {

                String desc = String.format("Architecture violation [%s]: '%s' (%s) directly depends on forbidden target '%s' (%s)",
                        rule.getRuleName(), source.getSimpleName(), source.getQualifiedName(),
                        target.getSimpleName(), target.getQualifiedName());

                violations.add(ArchitectureViolation.builder()
                        .repository(repository)
                        .commitSha(commitSha)
                        .rule(rule)
                        .ruleName(rule.getRuleName())
                        .severity(rule.getSeverity())
                        .sourceSymbol(symbolMap.get(source.getId()))
                        .targetSymbol(symbolMap.get(target.getId()))
                        .description(desc)
                        .sourceLocation(source.getSourceFile() + ":" + edge.getSourceLine())
                        .build());
            }
        }
    }
}


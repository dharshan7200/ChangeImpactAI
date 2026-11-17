package com.changeimpact.graph.service;

import com.changeimpact.graph.model.GraphEdge;
import com.changeimpact.graph.model.GraphNode;
import com.changeimpact.symbols.entity.Symbol;
import com.changeimpact.symbols.entity.SymbolDependency;
import com.changeimpact.symbols.repository.SymbolDependencyRepository;
import com.changeimpact.symbols.repository.SymbolRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jgrapht.Graph;
import org.jgrapht.graph.DefaultDirectedGraph;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Constructs JGraphT directed dependency graphs from persisted relational symbol records.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DependencyGraphBuilder {

    private final SymbolRepository symbolRepository;
    private final SymbolDependencyRepository symbolDependencyRepository;

    /**
     * Builds an in-memory JGraphT directed graph for a repository commit.
     */
    public Graph<GraphNode, GraphEdge> buildGraph(UUID repositoryId, String commitSha) {
        log.info("Constructing JGraphT dependency graph for repo: {} [commit: {}]", repositoryId, commitSha);

        Graph<GraphNode, GraphEdge> graph = new DefaultDirectedGraph<>(GraphEdge.class);

        List<Symbol> symbols = symbolRepository.findByRepositoryIdAndCommitSha(repositoryId, commitSha);
        Map<UUID, GraphNode> nodeMap = new HashMap<>();

        for (Symbol s : symbols) {
            GraphNode node = GraphNode.builder()
                    .id(s.getId())
                    .qualifiedName(s.getQualifiedName())
                    .simpleName(s.getSimpleName())
                    .symbolType(s.getSymbolType())
                    .sourceFile(s.getSourceFile() != null ? s.getSourceFile().getRelativePath() : "")
                    .startLine(s.getStartLine())
                    .endLine(s.getEndLine())
                    .build();

            nodeMap.put(s.getId(), node);
            graph.addVertex(node);
        }

        List<SymbolDependency> dependencies = symbolDependencyRepository
                .findAllWithSymbolsByRepositoryIdAndCommitSha(repositoryId, commitSha);

        int edgeCount = 0;
        for (SymbolDependency dep : dependencies) {
            if (dep.getTargetSymbol() == null) {
                continue; // Skip external library links not present in symbol graph
            }

            GraphNode sourceNode = nodeMap.get(dep.getSourceSymbol().getId());
            GraphNode targetNode = nodeMap.get(dep.getTargetSymbol().getId());

            if (sourceNode != null && targetNode != null && !sourceNode.equals(targetNode)) {
                GraphEdge edge = new GraphEdge(
                        dep.getId(),
                        dep.getDependencyType(),
                        dep.getSourceLocationLine() != null ? dep.getSourceLocationLine() : 0,
                        dep.getIsExternal()
                );

                try {
                    graph.addEdge(sourceNode, targetNode, edge);
                    edgeCount++;
                } catch (IllegalArgumentException e) {
                    log.debug("Skipping duplicate edge between {} and {}", sourceNode.getSimpleName(), targetNode.getSimpleName());
                }
            }
        }

        log.info("JGraphT graph constructed successfully: {} nodes, {} edges", graph.vertexSet().size(), edgeCount);
        return graph;
    }
}


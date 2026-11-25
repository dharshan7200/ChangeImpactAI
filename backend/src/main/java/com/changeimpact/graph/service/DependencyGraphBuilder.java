package com.changeimpact.graph.service;

import com.changeimpact.graph.model.GraphEdge;
import com.changeimpact.graph.model.GraphNode;
import com.changeimpact.parser.model.SymbolType;
import com.changeimpact.symbols.entity.Symbol;
import com.changeimpact.symbols.entity.SymbolDependency;
import com.changeimpact.symbols.repository.SymbolDependencyRepository;
import com.changeimpact.symbols.repository.SymbolRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jgrapht.Graph;
import org.jgrapht.graph.DefaultDirectedGraph;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

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
     * Builds the member-level graph: one vertex per symbol (types, methods, constructors, fields) and one
     * typed edge per (source, target) pair, with parallel references merged into that edge. Only
     * dependencies linked to an internal target symbol become edges; external/unresolved ones are not
     * part of the graph (they remain queryable in the dependencies table).
     */
    @Transactional(readOnly = true)
    public Graph<GraphNode, GraphEdge> buildGraph(UUID repositoryId, String commitSha) {
        long start = System.currentTimeMillis();
        Graph<GraphNode, GraphEdge> graph = new DefaultDirectedGraph<>(GraphEdge.class);

        List<Symbol> symbols = symbolRepository.findWithSourceFileByRepositoryIdAndCommitSha(repositoryId, commitSha);
        Map<UUID, Symbol> symbolsById = new HashMap<>();
        symbols.forEach(s -> symbolsById.put(s.getId(), s));

        Map<UUID, GraphNode> nodeMap = new HashMap<>();
        for (Symbol s : symbols) {
            GraphNode node = GraphNode.builder()
                    .id(s.getId())
                    .qualifiedName(s.getQualifiedName())
                    .simpleName(s.getSimpleName())
                    .symbolType(s.getSymbolType())
                    .sourceFile(s.getSourceFile() != null ? s.getSourceFile().getRelativePath() : "")
                    .packageName(s.getSourceFile() != null && s.getSourceFile().getPackageName() != null
                            ? s.getSourceFile().getPackageName() : "")
                    .startLine(s.getStartLine() != null ? s.getStartLine() : 0)
                    .endLine(s.getEndLine() != null ? s.getEndLine() : 0)
                    .ownerTypeId(ownerTypeId(s, symbolsById))
                    .testCode(s.getSourceFile() != null && Boolean.TRUE.equals(s.getSourceFile().getIsTest()))
                    .build();
            nodeMap.put(s.getId(), node);
            graph.addVertex(node);
        }

        List<SymbolDependency> dependencies = symbolDependencyRepository
                .findAllWithSymbolsByRepositoryIdAndCommitSha(repositoryId, commitSha);

        for (SymbolDependency dep : dependencies) {
            if (dep.getTargetSymbol() == null) {
                continue;
            }
            GraphNode sourceNode = nodeMap.get(dep.getSourceSymbol().getId());
            GraphNode targetNode = nodeMap.get(dep.getTargetSymbol().getId());
            if (sourceNode == null || targetNode == null || sourceNode.equals(targetNode)) {
                continue;
            }
            GraphEdge existing = graph.getEdge(sourceNode, targetNode);
            if (existing != null) {
                existing.merge(dep.getDependencyType(), 1);
            } else {
                graph.addEdge(sourceNode, targetNode, new GraphEdge(
                        dep.getId(),
                        dep.getDependencyType(),
                        dep.getSourceLocationLine() != null ? dep.getSourceLocationLine() : 0,
                        Boolean.TRUE.equals(dep.getIsExternal())));
            }
        }

        log.info("Dependency graph for repo {} [{}]: {} nodes, {} edges ({} references) in {} ms",
                repositoryId, commitSha, graph.vertexSet().size(), graph.edgeSet().size(),
                dependencies.size(), System.currentTimeMillis() - start);
        return graph;
    }

    /** Walks parent links up to the nearest enclosing type. */
    private static UUID ownerTypeId(Symbol symbol, Map<UUID, Symbol> symbolsById) {
        Symbol current = symbol;
        int guard = 0;
        while (current != null && !isType(current.getSymbolType()) && guard++ < 32) {
            Symbol parent = current.getParentSymbol();
            current = parent != null ? symbolsById.get(parent.getId()) : null;
        }
        return current != null ? current.getId() : symbol.getId();
    }

    private static boolean isType(SymbolType type) {
        return type != null && type.isTypeLike();
    }
}

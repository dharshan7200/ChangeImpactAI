package com.changeimpact.graph.model;

import com.changeimpact.parser.model.SymbolType;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

import java.util.UUID;

/**
 * Node in the JGraphT symbol dependency graph.
 */
@Getter
@Builder
@ToString
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class GraphNode {

    @EqualsAndHashCode.Include
    private final UUID id;

    private final String qualifiedName;
    private final String simpleName;
    private final SymbolType symbolType;
    private final String sourceFile;
    private final int startLine;
    private final int endLine;
}


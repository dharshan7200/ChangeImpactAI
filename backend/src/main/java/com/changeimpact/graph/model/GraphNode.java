package com.changeimpact.graph.model;

import com.changeimpact.parser.model.SymbolType;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

import java.util.UUID;

/**
 * Lightweight, immutable vertex of the in-memory JGraphT dependency graph.
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

    /** Package of the declaring source file (empty for the default package). */
    private final String packageName;

    /**
     * Nearest enclosing type (class/interface/enum/record) of this symbol; equal to {@link #id} for a type.
     * May be null for nodes created outside the builder (e.g. tests), in which case the node is its own owner.
     */
    private final UUID ownerTypeId;

    /** Declared in test sources (src/test/..., *Test.java, *Tests.java). */
    private final boolean testCode;

    public boolean isType() {
        return symbolType != null && symbolType.isTypeLike();
    }

    /** Owner type id, falling back to the node itself. */
    public UUID ownerOrSelf() {
        return ownerTypeId != null ? ownerTypeId : id;
    }
}

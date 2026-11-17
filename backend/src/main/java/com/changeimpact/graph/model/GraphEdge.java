package com.changeimpact.graph.model;

import com.changeimpact.parser.model.DependencyType;
import lombok.Getter;
import lombok.ToString;
import org.jgrapht.graph.DefaultEdge;

import java.util.UUID;

/**
 * Directed edge representing a typed dependency relationship in JGraphT.
 */
@Getter
@ToString
public class GraphEdge extends DefaultEdge {

    private final UUID id;
    private final DependencyType dependencyType;
    private final int sourceLine;
    private final boolean isExternal;

    public GraphEdge(UUID id, DependencyType dependencyType, int sourceLine, boolean isExternal) {
        this.id = id;
        this.dependencyType = dependencyType;
        this.sourceLine = sourceLine;
        this.isExternal = isExternal;
    }
}


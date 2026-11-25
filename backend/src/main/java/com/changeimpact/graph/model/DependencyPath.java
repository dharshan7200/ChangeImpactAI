package com.changeimpact.graph.model;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

/**
 * Represents a directed chain of dependencies connecting two symbols.
 */
@Getter
@Builder
public class DependencyPath {
    private final List<GraphNode> nodes;
    private final List<GraphEdge> edges;
    private final int length;
}


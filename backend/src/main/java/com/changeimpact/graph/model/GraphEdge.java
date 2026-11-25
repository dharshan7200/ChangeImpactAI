package com.changeimpact.graph.model;

import com.changeimpact.parser.model.DependencyType;
import lombok.Getter;
import lombok.ToString;
import org.jgrapht.graph.DefaultEdge;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

/**
 * Directed, typed edge of the dependency graph.
 *
 * <p>The graph keeps at most one edge per (source, target) pair so that graph algorithms (cycles,
 * paths, SCCs) are not distorted by parallel edges. Every additional relationship between the same
 * pair is merged into this edge: {@link #getTypes()} lists all relationship kinds and
 * {@link #getOccurrences()} counts the underlying source-code references.
 */
@Getter
@ToString
public class GraphEdge extends DefaultEdge {

    private final UUID id;
    /** Relationship that created the edge (first occurrence). */
    private final DependencyType dependencyType;
    /** Line of the first occurrence in the source member's file. */
    private final int sourceLine;
    private final boolean isExternal;

    private final EnumSet<DependencyType> types;
    private int occurrences = 1;

    public GraphEdge(UUID id, DependencyType dependencyType, int sourceLine, boolean isExternal) {
        this.id = id;
        this.dependencyType = dependencyType;
        this.sourceLine = sourceLine;
        this.isExternal = isExternal;
        this.types = EnumSet.of(dependencyType);
    }

    /** Merges another reference between the same two nodes into this edge. */
    public void merge(DependencyType type, int count) {
        types.add(type);
        occurrences += count;
    }

    public void merge(Set<DependencyType> otherTypes, int count) {
        types.addAll(otherTypes);
        occurrences += count;
    }

    public Set<DependencyType> getTypes() {
        return Collections.unmodifiableSet(types);
    }
}

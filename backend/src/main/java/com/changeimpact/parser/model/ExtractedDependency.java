package com.changeimpact.parser.model;

import lombok.Builder;
import lombok.Getter;

/**
 * In-memory representation of a directed dependency relationship between symbols.
 */
@Getter
@Builder
public class ExtractedDependency {
    private final String sourceQualifiedName;
    private final String targetQualifiedName;
    private final DependencyType dependencyType;
    private final boolean isExternal;
    private final int sourceLine;
    private final ResolutionStatus resolutionStatus;
}


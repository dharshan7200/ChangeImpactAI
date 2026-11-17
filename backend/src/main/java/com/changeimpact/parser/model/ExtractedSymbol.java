package com.changeimpact.parser.model;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

/**
 * In-memory representation of a code symbol extracted from an AST.
 */
@Getter
@Builder
public class ExtractedSymbol {
    private final String simpleName;
    private final String qualifiedName;
    private final SymbolType symbolType;
    private final String parentQualifiedName;
    private final String returnType;
    private final String parameters;
    private final String visibility;
    private final boolean isStatic;
    private final boolean isAbstract;
    private final boolean isFinal;
    private final List<String> annotations;
    private final int startLine;
    private final int endLine;
    private final int startColumn;
    private final int endColumn;
    private final ResolutionStatus resolutionStatus;
}


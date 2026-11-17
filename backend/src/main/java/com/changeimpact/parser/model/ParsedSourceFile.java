package com.changeimpact.parser.model;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

/**
 * Aggregates all symbols and dependencies extracted from a single source file.
 */
@Getter
@Builder
public class ParsedSourceFile {
    private final String relativePath;
    private final String packageName;
    private final int linesOfCode;
    private final long sizeBytes;
    private final boolean isTest;
    private final List<ExtractedSymbol> symbols;
    private final List<ExtractedDependency> dependencies;
}


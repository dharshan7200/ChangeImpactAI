package com.changeimpact.diff.semantic;

import com.changeimpact.parser.model.SymbolType;

import java.util.Set;
import java.util.UUID;

/**
 * Commit-independent view of a symbol used by {@link SemanticChangeDetector}. Built from persisted
 * {@code Symbol} rows in production and from parser output in tests.
 */
public record SymbolSnapshot(
        UUID id,
        String qualifiedName,
        String simpleName,
        SymbolType symbolType,
        String parentQualifiedName,
        String visibility,
        String returnType,
        String parameters,
        boolean isStatic,
        boolean isAbstract,
        boolean isFinal,
        String annotationText,
        String contentHash,
        Set<String> supertypes,
        String filePath,
        int startLine,
        int endLine,
        boolean test
) {
    public boolean isType() {
        return symbolType != null && symbolType.isTypeLike();
    }

    public boolean isCallable() {
        return symbolType != null && symbolType.isCallable();
    }

    /** {@code pkg.Type#name} for callables (without the parameter list). */
    public String callableKey() {
        int paren = qualifiedName.indexOf('(');
        return paren > 0 ? qualifiedName.substring(0, paren) : qualifiedName;
    }
}

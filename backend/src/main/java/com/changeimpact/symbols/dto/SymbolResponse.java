package com.changeimpact.symbols.dto;

import com.changeimpact.parser.model.ResolutionStatus;
import com.changeimpact.parser.model.SymbolType;
import com.changeimpact.symbols.entity.Symbol;

import java.util.List;
import java.util.UUID;

/**
 * API view of a {@link Symbol}.
 *
 * <p>Entities are never serialized directly: their lazy JPA associations ({@code sourceFile},
 * {@code repository}, {@code parentSymbol}) are Hibernate proxies that Jackson cannot serialize,
 * which previously made every symbol endpoint fail with HTTP 500. Must be built inside an open
 * persistence context (service transaction or open-in-view) so the lazy associations can load.
 */
public record SymbolResponse(
        UUID id,
        SymbolType symbolType,
        String qualifiedName,
        String simpleName,
        String packageName,
        String sourceFile,
        UUID parentSymbolId,
        String returnType,
        String parameters,
        String visibility,
        boolean isStatic,
        boolean isAbstract,
        boolean isFinal,
        List<String> annotations,
        Integer startLine,
        Integer endLine,
        ResolutionStatus resolutionStatus,
        String commitSha
) {
    public static SymbolResponse from(Symbol s) {
        return new SymbolResponse(
                s.getId(),
                s.getSymbolType(),
                s.getQualifiedName(),
                s.getSimpleName(),
                s.getSourceFile() != null ? s.getSourceFile().getPackageName() : null,
                s.getSourceFile() != null ? s.getSourceFile().getRelativePath() : null,
                s.getParentSymbol() != null ? s.getParentSymbol().getId() : null,
                s.getReturnType(),
                s.getParameters(),
                s.getVisibility(),
                Boolean.TRUE.equals(s.getIsStatic()),
                Boolean.TRUE.equals(s.getIsAbstract()),
                Boolean.TRUE.equals(s.getIsFinal()),
                s.getAnnotations() != null ? List.of(s.getAnnotations()) : List.of(),
                s.getStartLine(),
                s.getEndLine(),
                s.getResolutionStatus(),
                s.getCommitSha()
        );
    }
}

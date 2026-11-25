package com.changeimpact.architecture.dto;

import com.changeimpact.architecture.entity.ArchitectureViolation;

import java.time.Instant;
import java.util.UUID;

/**
 * API view of an {@link ArchitectureViolation} (entities with lazy associations are not serialized directly).
 */
public record ArchitectureViolationResponse(
        UUID id,
        String ruleName,
        String ruleType,
        String severity,
        String description,
        String sourceLocation,
        UUID sourceSymbolId,
        String sourceSymbol,
        UUID targetSymbolId,
        String targetSymbol,
        Instant detectedAt
) {
    public static ArchitectureViolationResponse from(ArchitectureViolation v) {
        return new ArchitectureViolationResponse(
                v.getId(),
                v.getRuleName(),
                v.getRule() != null ? v.getRule().getRuleType() : null,
                v.getSeverity(),
                v.getDescription(),
                v.getSourceLocation(),
                v.getSourceSymbol() != null ? v.getSourceSymbol().getId() : null,
                v.getSourceSymbol() != null ? v.getSourceSymbol().getQualifiedName() : null,
                v.getTargetSymbol() != null ? v.getTargetSymbol().getId() : null,
                v.getTargetSymbol() != null ? v.getTargetSymbol().getQualifiedName() : null,
                v.getDetectedAt()
        );
    }
}

package com.changeimpact.parser.model;

/**
 * Tracks whether a symbol or reference was deterministically resolved.
 */
public enum ResolutionStatus {
    RESOLVED,
    PARTIAL,
    UNRESOLVED,
    EXTERNAL
}


package com.changeimpact.parser.model;

/**
 * Structural and behavioral dependency types between symbols.
 */
public enum DependencyType {
    EXTENDS,
    IMPLEMENTS,
    METHOD_CALL,
    CONSTRUCTOR_CALL,
    FIELD_ACCESS,
    RETURN_TYPE,
    PARAMETER_TYPE,
    ANNOTATION
}


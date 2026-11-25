package com.changeimpact.parser.model;

/**
 * Structural and behavioral dependency types between symbols.
 */
public enum DependencyType {
    EXTENDS,
    IMPLEMENTS,
    METHOD_CALL,
    CONSTRUCTOR_CALL,
    /** A method/initializer reads or writes a field (or enum constant). */
    FIELD_ACCESS,
    /** A field is declared with the target type. */
    FIELD_TYPE,
    RETURN_TYPE,
    PARAMETER_TYPE,
    ANNOTATION,
    /** A compilation unit imports the target type (compile-time dependency). */
    IMPORT
}


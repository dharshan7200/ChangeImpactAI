package com.changeimpact.parser.model;

import java.util.Set;

/**
 * Categorization of extracted Abstract Syntax Tree (AST) code elements. {@code MODULE} and {@code FUNCTION} come from
 * non-Java languages (P6-7): a Python module is a type-like container of top-level declarations, a module-level
 * function is callable like a method.
 */
public enum SymbolType {
    PACKAGE,
    CLASS,
    INTERFACE,
    ENUM,
    RECORD,
    METHOD,
    CONSTRUCTOR,
    FIELD,
    MODULE,
    FUNCTION;

    /** Containers that own members and are graph nodes of the class view. */
    public static final Set<SymbolType> TYPE_LIKE = Set.of(CLASS, INTERFACE, ENUM, RECORD, MODULE);
    public static final Set<SymbolType> CALLABLE = Set.of(METHOD, CONSTRUCTOR, FUNCTION);
    /** The same sets as SQL lists, for queries over {@code symbols.symbol_type}. */
    public static final String TYPE_LIKE_SQL = "('CLASS', 'INTERFACE', 'ENUM', 'RECORD', 'MODULE')";
    public static final String CALLABLE_SQL = "('METHOD', 'CONSTRUCTOR', 'FUNCTION')";

    public boolean isTypeLike() {
        return TYPE_LIKE.contains(this);
    }

    public boolean isCallable() {
        return CALLABLE.contains(this);
    }
}

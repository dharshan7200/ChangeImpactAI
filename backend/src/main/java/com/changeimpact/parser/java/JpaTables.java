package com.changeimpact.parser.java;

import com.changeimpact.parser.language.ts.EmbeddedSql;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Tables and columns of JPA entities, named like Spring Boot's default naming strategy
 * ({@code CamelCaseToUnderscoresNamingStrategy}): {@code @Table(name = "owners")} or the entity name in snake case,
 * {@code @Column(name = …)} / {@code @JoinColumn(name = …)} or the field name in snake case ({@code _id} appended for
 * to-one associations). The links land on the repository's SQL definitions; CodeAnalysisService drops those that match
 * no table or column, so a different naming strategy only loses links, never invents one.
 */
final class JpaTables {

    private static final Set<String> NOT_COLUMNS = Set.of("Transient", "OneToMany", "ManyToMany", "Embedded", "EmbeddedId", "ElementCollection");

    private JpaTables() {}

    /** {@code db:owners} for an {@code @Entity}/{@code @Table} class, otherwise null. */
    static String table(ClassOrInterfaceDeclaration cid) {
        Optional<AnnotationExpr> table = annotation(cid.getAnnotations(), "Table");
        Optional<AnnotationExpr> entity = annotation(cid.getAnnotations(), "Entity");
        if (table.isEmpty() && entity.isEmpty()) return null;
        String name = table.flatMap(a -> attribute(a, "name")).orElse(null);
        if (name == null) name = entity.flatMap(a -> attribute(a, "name")).map(JpaTables::snake).orElse(null);
        if (name == null) name = snake(cid.getNameAsString());
        String schema = table.flatMap(a -> attribute(a, "schema")).orElse(null);
        return EmbeddedSql.name(schema == null || schema.isBlank() ? name : schema + "." + name);
    }

    static boolean mappedSuperclass(ClassOrInterfaceDeclaration cid) {
        return annotation(cid.getAnnotations(), "MappedSuperclass").isPresent();
    }

    /** Column of a persistent field, or null for transient, static and collection-valued fields. */
    static String column(FieldDeclaration fd, VariableDeclarator var) {
        if (fd.isStatic() || fd.isTransient()) return null;
        var annotations = fd.getAnnotations();
        if (annotations.stream().anyMatch(a -> NOT_COLUMNS.contains(a.getName().getIdentifier()))) return null;
        Optional<String> explicit = annotation(annotations, "Column").flatMap(a -> attribute(a, "name"))
                .or(() -> annotation(annotations, "JoinColumn").flatMap(a -> attribute(a, "name")));
        if (explicit.isPresent()) return explicit.get().toLowerCase(Locale.ROOT).replaceAll("[\"`]", "");
        boolean toOne = annotation(annotations, "ManyToOne").isPresent() || annotation(annotations, "OneToOne").isPresent();
        if (toOne && annotation(annotations, "OneToOne").flatMap(a -> attribute(a, "mappedBy")).isPresent()) return null;
        return snake(var.getNameAsString()) + (toOne ? "_id" : "");
    }

    static String snake(String name) {
        return name.replaceAll("([a-z0-9])([A-Z])", "$1_$2").replace('.', '_').toLowerCase(Locale.ROOT);
    }

    private static Optional<AnnotationExpr> annotation(Iterable<AnnotationExpr> annotations, String simpleName) {
        for (AnnotationExpr a : annotations) if (a.getName().getIdentifier().equals(simpleName)) return Optional.of(a);
        return Optional.empty();
    }

    private static Optional<String> attribute(AnnotationExpr a, String name) {
        if (!(a instanceof NormalAnnotationExpr n)) return Optional.empty();
        return n.getPairs().stream().filter(p -> p.getNameAsString().equals(name)).findFirst()
                .filter(p -> p.getValue() instanceof StringLiteralExpr)
                .map(p -> ((StringLiteralExpr) p.getValue()).getValue()).filter(v -> !v.isBlank());
    }
}

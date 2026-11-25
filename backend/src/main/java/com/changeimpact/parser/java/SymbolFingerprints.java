package com.changeimpact.parser.java;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.ConditionalExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithAnnotations;
import com.github.javaparser.ast.stmt.*;
import com.github.javaparser.printer.configuration.DefaultConfigurationOption;
import com.github.javaparser.printer.configuration.DefaultPrinterConfiguration;
import com.github.javaparser.printer.configuration.PrinterConfiguration;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.stream.Collectors;

/**
 * Deterministic fingerprints used for <b>semantic</b> change detection.
 *
 * <p>Declarations are printed with JavaParser's pretty printer with comments and Javadoc disabled, so a
 * change that only touches comments, whitespace or formatting produces the same hash and is not reported
 * as a code change. For types only the <i>header</i> is hashed (modifiers, annotations, name, type
 * parameters, supertypes): member changes are reported on the members themselves.
 */
final class SymbolFingerprints {

    private static final PrinterConfiguration NO_COMMENTS = new DefaultPrinterConfiguration()
            .removeOption(new DefaultConfigurationOption(DefaultPrinterConfiguration.ConfigOption.PRINT_COMMENTS))
            .removeOption(new DefaultConfigurationOption(DefaultPrinterConfiguration.ConfigOption.PRINT_JAVADOC));

    private SymbolFingerprints() {}

    static String print(Node node) {
        return node.toString(NO_COMMENTS);
    }

    static String declarationHash(Node node) {
        return sha256(print(node));
    }

    /** Header of a type: everything except its body/members. */
    static String typeHeaderHash(TypeDeclaration<?> type) {
        StringBuilder sb = new StringBuilder();
        type.getAnnotations().forEach(a -> sb.append(print(a)).append('\n'));
        type.getModifiers().forEach(m -> sb.append(m.getKeyword().asString()).append(' '));
        sb.append(type.getClass().getSimpleName()).append(' ').append(type.getNameAsString());
        if (type instanceof ClassOrInterfaceDeclaration cid) {
            sb.append(cid.isInterface() ? " interface" : " class");
            sb.append('<').append(cid.getTypeParameters().stream().map(SymbolFingerprints::print).collect(Collectors.joining(","))).append('>');
            sb.append(" extends ").append(cid.getExtendedTypes().stream().map(SymbolFingerprints::print).collect(Collectors.joining(",")));
            sb.append(" implements ").append(cid.getImplementedTypes().stream().map(SymbolFingerprints::print).collect(Collectors.joining(",")));
            sb.append(" permits ").append(cid.getPermittedTypes().stream().map(SymbolFingerprints::print).collect(Collectors.joining(",")));
        } else if (type instanceof RecordDeclaration rd) {
            sb.append('<').append(rd.getTypeParameters().stream().map(SymbolFingerprints::print).collect(Collectors.joining(","))).append('>');
            sb.append('(').append(rd.getParameters().stream().map(SymbolFingerprints::print).collect(Collectors.joining(","))).append(')');
            sb.append(" implements ").append(rd.getImplementedTypes().stream().map(SymbolFingerprints::print).collect(Collectors.joining(",")));
        } else if (type instanceof EnumDeclaration ed) {
            sb.append(" implements ").append(ed.getImplementedTypes().stream().map(SymbolFingerprints::print).collect(Collectors.joining(",")));
        }
        return sha256(sb.toString());
    }

    /** One variable of a (possibly multi-variable) field declaration, with the shared modifiers/annotations. */
    static String fieldHash(FieldDeclaration fd, VariableDeclarator var) {
        String header = fd.getAnnotations().stream().map(SymbolFingerprints::print).collect(Collectors.joining("\n"))
                + fd.getModifiers().stream().map(m -> m.getKeyword().asString()).collect(Collectors.joining(" "));
        return sha256(header + " " + print(var.getType()) + " " + print(var));
    }

    static String annotationText(NodeWithAnnotations<?> node) {
        if (node.getAnnotations().isEmpty()) {
            return null;
        }
        return node.getAnnotations().stream().map(AnnotationExpr::toString)
                .map(s -> s.replaceAll("\\s+", " ").trim())
                .collect(Collectors.joining("\n"));
    }

    /**
     * McCabe cyclomatic complexity: 1 + decision points (if, loops, case labels, catch, ternary, && and ||).
     * Lambdas and local/anonymous classes inside the body are included.
     */
    static int cyclomaticComplexity(Node callable) {
        int decisions = 0;
        decisions += callable.findAll(IfStmt.class).size();
        decisions += callable.findAll(ForStmt.class).size();
        decisions += callable.findAll(ForEachStmt.class).size();
        decisions += callable.findAll(WhileStmt.class).size();
        decisions += callable.findAll(DoStmt.class).size();
        decisions += callable.findAll(CatchClause.class).size();
        decisions += callable.findAll(ConditionalExpr.class).size();
        decisions += (int) callable.findAll(SwitchEntry.class).stream().filter(e -> !e.getLabels().isEmpty()).count();
        decisions += (int) callable.findAll(BinaryExpr.class).stream()
                .filter(b -> b.getOperator() == BinaryExpr.Operator.AND || b.getOperator() == BinaryExpr.Operator.OR)
                .count();
        return 1 + decisions;
    }

    static String sha256(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}

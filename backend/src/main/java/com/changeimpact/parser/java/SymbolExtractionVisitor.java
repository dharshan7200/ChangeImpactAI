package com.changeimpact.parser.java;

import com.changeimpact.parser.model.DependencyType;
import com.changeimpact.parser.model.ExtractedDependency;
import com.changeimpact.parser.model.ExtractedSymbol;
import com.changeimpact.parser.model.ResolutionStatus;
import com.changeimpact.parser.model.SymbolType;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.PackageDeclaration;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.nodeTypes.NodeWithAnnotations;
import com.github.javaparser.ast.nodeTypes.NodeWithModifiers;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;
import com.github.javaparser.resolution.declarations.ResolvedMethodDeclaration;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.stream.Collectors;

/**
 * JavaParser AST visitor that traverses compilation units, extracting symbols
 * (classes, interfaces, records, enums, methods, constructors, fields) and
 * structural/behavioral dependencies with deterministic symbol resolution.
 */
@Getter
@Slf4j
public class SymbolExtractionVisitor extends VoidVisitorAdapter<Void> {

    private String packageName = "";
    private final List<ExtractedSymbol> symbols = new ArrayList<>();
    private final List<ExtractedDependency> dependencies = new ArrayList<>();

    // Tracks current enclosing symbol context (e.g. Class -> Method)
    private final Deque<String> symbolContextStack = new ArrayDeque<>();

    @Override
    public void visit(PackageDeclaration pd, Void arg) {
        this.packageName = pd.getNameAsString();
        super.visit(pd, arg);
    }

    @Override
    public void visit(ClassOrInterfaceDeclaration cid, Void arg) {
        String simpleName = cid.getNameAsString();
        String qualifiedName = buildQualifiedName(simpleName);
        SymbolType type = cid.isInterface() ? SymbolType.INTERFACE : SymbolType.CLASS;

        String parentContext = symbolContextStack.peek();
        int startLine = getStartLine(cid);
        int endLine = getEndLine(cid);

        List<String> annotations = extractAnnotations(cid);

        ExtractedSymbol symbol = ExtractedSymbol.builder()
                .simpleName(simpleName)
                .qualifiedName(qualifiedName)
                .symbolType(type)
                .parentQualifiedName(parentContext)
                .visibility(getVisibility(cid))
                .isStatic(cid.isStatic())
                .isAbstract(cid.isAbstract())
                .isFinal(cid.isFinal())
                .annotations(annotations)
                .startLine(startLine)
                .endLine(endLine)
                .startColumn(getStartCol(cid))
                .endColumn(getEndCol(cid))
                .resolutionStatus(ResolutionStatus.RESOLVED)
                .build();
        symbols.add(symbol);

        // Record inheritance dependencies
        for (ClassOrInterfaceType extendedType : cid.getExtendedTypes()) {
            dependencies.add(ExtractedDependency.builder()
                    .sourceQualifiedName(qualifiedName)
                    .targetQualifiedName(resolveTypeName(extendedType))
                    .dependencyType(DependencyType.EXTENDS)
                    .isExternal(isExternalType(extendedType))
                    .sourceLine(getStartLine(extendedType))
                    .resolutionStatus(ResolutionStatus.RESOLVED)
                    .build());
        }

        // Record interface implementation dependencies
        for (ClassOrInterfaceType implementedType : cid.getImplementedTypes()) {
            dependencies.add(ExtractedDependency.builder()
                    .sourceQualifiedName(qualifiedName)
                    .targetQualifiedName(resolveTypeName(implementedType))
                    .dependencyType(DependencyType.IMPLEMENTS)
                    .isExternal(isExternalType(implementedType))
                    .sourceLine(getStartLine(implementedType))
                    .resolutionStatus(ResolutionStatus.RESOLVED)
                    .build());
        }

        // Push current class to context stack for child members
        symbolContextStack.push(qualifiedName);
        super.visit(cid, arg);
        symbolContextStack.pop();
    }

    @Override
    public void visit(RecordDeclaration rd, Void arg) {
        String simpleName = rd.getNameAsString();
        String qualifiedName = buildQualifiedName(simpleName);

        ExtractedSymbol symbol = ExtractedSymbol.builder()
                .simpleName(simpleName)
                .qualifiedName(qualifiedName)
                .symbolType(SymbolType.RECORD)
                .parentQualifiedName(symbolContextStack.peek())
                .visibility(getVisibility(rd))
                .isStatic(rd.isStatic())
                .isFinal(rd.isFinal())
                .annotations(extractAnnotations(rd))
                .startLine(getStartLine(rd))
                .endLine(getEndLine(rd))
                .startColumn(getStartCol(rd))
                .endColumn(getEndCol(rd))
                .resolutionStatus(ResolutionStatus.RESOLVED)
                .build();
        symbols.add(symbol);

        for (ClassOrInterfaceType implementedType : rd.getImplementedTypes()) {
            dependencies.add(ExtractedDependency.builder()
                    .sourceQualifiedName(qualifiedName)
                    .targetQualifiedName(resolveTypeName(implementedType))
                    .dependencyType(DependencyType.IMPLEMENTS)
                    .isExternal(isExternalType(implementedType))
                    .sourceLine(getStartLine(implementedType))
                    .resolutionStatus(ResolutionStatus.RESOLVED)
                    .build());
        }

        symbolContextStack.push(qualifiedName);
        super.visit(rd, arg);
        symbolContextStack.pop();
    }

    @Override
    public void visit(EnumDeclaration ed, Void arg) {
        String simpleName = ed.getNameAsString();
        String qualifiedName = buildQualifiedName(simpleName);

        ExtractedSymbol symbol = ExtractedSymbol.builder()
                .simpleName(simpleName)
                .qualifiedName(qualifiedName)
                .symbolType(SymbolType.ENUM)
                .parentQualifiedName(symbolContextStack.peek())
                .visibility(getVisibility(ed))
                .isStatic(ed.isStatic())
                .annotations(extractAnnotations(ed))
                .startLine(getStartLine(ed))
                .endLine(getEndLine(ed))
                .startColumn(getStartCol(ed))
                .endColumn(getEndCol(ed))
                .resolutionStatus(ResolutionStatus.RESOLVED)
                .build();
        symbols.add(symbol);

        symbolContextStack.push(qualifiedName);
        super.visit(ed, arg);
        symbolContextStack.pop();
    }

    @Override
    public void visit(MethodDeclaration md, Void arg) {
        String parentContext = symbolContextStack.peek();
        String simpleName = md.getNameAsString();
        String params = md.getParameters().stream()
                .map(p -> p.getType().asString())
                .collect(Collectors.joining(","));
        String returnType = md.getType().asString();
        String qualifiedName = (parentContext != null ? parentContext : packageName) + "#" + simpleName + "(" + params + ")";

        ExtractedSymbol symbol = ExtractedSymbol.builder()
                .simpleName(simpleName)
                .qualifiedName(qualifiedName)
                .symbolType(SymbolType.METHOD)
                .parentQualifiedName(parentContext)
                .returnType(returnType)
                .parameters(params)
                .visibility(getVisibility(md))
                .isStatic(md.isStatic())
                .isAbstract(md.isAbstract())
                .isFinal(md.isFinal())
                .annotations(extractAnnotations(md))
                .startLine(getStartLine(md))
                .endLine(getEndLine(md))
                .startColumn(getStartCol(md))
                .endColumn(getEndCol(md))
                .resolutionStatus(ResolutionStatus.RESOLVED)
                .build();
        symbols.add(symbol);

        // Record parameter type dependencies
        for (Parameter param : md.getParameters()) {
            dependencies.add(ExtractedDependency.builder()
                    .sourceQualifiedName(qualifiedName)
                    .targetQualifiedName(param.getType().asString())
                    .dependencyType(DependencyType.PARAMETER_TYPE)
                    .isExternal(true)
                    .sourceLine(getStartLine(param))
                    .resolutionStatus(ResolutionStatus.RESOLVED)
                    .build());
        }

        // Record return type dependency
        if (!"void".equalsIgnoreCase(returnType)) {
            dependencies.add(ExtractedDependency.builder()
                    .sourceQualifiedName(qualifiedName)
                    .targetQualifiedName(returnType)
                    .dependencyType(DependencyType.RETURN_TYPE)
                    .isExternal(true)
                    .sourceLine(getStartLine(md.getType()))
                    .resolutionStatus(ResolutionStatus.RESOLVED)
                    .build());
        }

        symbolContextStack.push(qualifiedName);
        super.visit(md, arg);
        symbolContextStack.pop();
    }

    @Override
    public void visit(ConstructorDeclaration cd, Void arg) {
        String parentContext = symbolContextStack.peek();
        String simpleName = cd.getNameAsString();
        String params = cd.getParameters().stream()
                .map(p -> p.getType().asString())
                .collect(Collectors.joining(","));
        String qualifiedName = (parentContext != null ? parentContext : packageName) + "#<init>(" + params + ")";

        ExtractedSymbol symbol = ExtractedSymbol.builder()
                .simpleName(simpleName)
                .qualifiedName(qualifiedName)
                .symbolType(SymbolType.CONSTRUCTOR)
                .parentQualifiedName(parentContext)
                .parameters(params)
                .visibility(getVisibility(cd))
                .annotations(extractAnnotations(cd))
                .startLine(getStartLine(cd))
                .endLine(getEndLine(cd))
                .startColumn(getStartCol(cd))
                .endColumn(getEndCol(cd))
                .resolutionStatus(ResolutionStatus.RESOLVED)
                .build();
        symbols.add(symbol);

        symbolContextStack.push(qualifiedName);
        super.visit(cd, arg);
        symbolContextStack.pop();
    }

    @Override
    public void visit(FieldDeclaration fd, Void arg) {
        String parentContext = symbolContextStack.peek();
        String fieldType = fd.getElementType().asString();

        for (VariableDeclarator var : fd.getVariables()) {
            String simpleName = var.getNameAsString();
            String qualifiedName = (parentContext != null ? parentContext : packageName) + "#" + simpleName;

            ExtractedSymbol symbol = ExtractedSymbol.builder()
                    .simpleName(simpleName)
                    .qualifiedName(qualifiedName)
                    .symbolType(SymbolType.FIELD)
                    .parentQualifiedName(parentContext)
                    .returnType(fieldType)
                    .visibility(getVisibility(fd))
                    .isStatic(fd.isStatic())
                    .isFinal(fd.isFinal())
                    .annotations(extractAnnotations(fd))
                    .startLine(getStartLine(var))
                    .endLine(getEndLine(var))
                    .startColumn(getStartCol(var))
                    .endColumn(getEndCol(var))
                    .resolutionStatus(ResolutionStatus.RESOLVED)
                    .build();
            symbols.add(symbol);

            dependencies.add(ExtractedDependency.builder()
                    .sourceQualifiedName(qualifiedName)
                    .targetQualifiedName(fieldType)
                    .dependencyType(DependencyType.FIELD_ACCESS)
                    .isExternal(true)
                    .sourceLine(getStartLine(var))
                    .resolutionStatus(ResolutionStatus.RESOLVED)
                    .build());
        }

        super.visit(fd, arg);
    }

    @Override
    public void visit(MethodCallExpr mce, Void arg) {
        String currentContext = symbolContextStack.peek();
        if (currentContext != null) {
            String targetQualifiedName;
            ResolutionStatus status;
            boolean isExternal = false;

            try {
                // Attempt deterministic symbol resolution via JavaSymbolSolver
                ResolvedMethodDeclaration resolved = mce.resolve();
                String declaringClass = resolved.declaringType().getQualifiedName();
                targetQualifiedName = declaringClass + "#" + resolved.getName() + "()";
                status = ResolutionStatus.RESOLVED;

                if (declaringClass.startsWith("java.") || declaringClass.startsWith("javax.") ||
                    declaringClass.startsWith("org.springframework.") || declaringClass.startsWith("org.slf4j.")) {
                    isExternal = true;
                }
            } catch (Exception e) {
                // Track unresolved method invocation gracefully without inventing symbols
                String scope = mce.getScope().map(Node::toString).orElse("");
                targetQualifiedName = (scope.isBlank() ? "" : scope + ".") + mce.getNameAsString() + "()";
                status = ResolutionStatus.UNRESOLVED;
                isExternal = true;
            }

            dependencies.add(ExtractedDependency.builder()
                    .sourceQualifiedName(currentContext)
                    .targetQualifiedName(targetQualifiedName)
                    .dependencyType(DependencyType.METHOD_CALL)
                    .isExternal(isExternal)
                    .sourceLine(getStartLine(mce))
                    .resolutionStatus(status)
                    .build());
        }

        super.visit(mce, arg);
    }

    @Override
    public void visit(ObjectCreationExpr oce, Void arg) {
        String currentContext = symbolContextStack.peek();
        if (currentContext != null) {
            String targetTypeName = oce.getType().asString();
            String targetQualifiedName;
            ResolutionStatus status;
            boolean isExternal = false;

            try {
                var resolved = oce.resolve();
                targetQualifiedName = resolved.declaringType().getQualifiedName() + "#<init>()";
                status = ResolutionStatus.RESOLVED;
            } catch (Exception e) {
                targetQualifiedName = targetTypeName + "#<init>()";
                status = ResolutionStatus.UNRESOLVED;
                isExternal = true;
            }

            dependencies.add(ExtractedDependency.builder()
                    .sourceQualifiedName(currentContext)
                    .targetQualifiedName(targetQualifiedName)
                    .dependencyType(DependencyType.CONSTRUCTOR_CALL)
                    .isExternal(isExternal)
                    .sourceLine(getStartLine(oce))
                    .resolutionStatus(status)
                    .build());
        }

        super.visit(oce, arg);
    }

    // ---- Helper Methods ----

    private String buildQualifiedName(String simpleName) {
        String parent = symbolContextStack.peek();
        if (parent != null) {
            return parent + "$" + simpleName;
        }
        return (packageName != null && !packageName.isBlank()) ? packageName + "." + simpleName : simpleName;
    }

    private String resolveTypeName(ClassOrInterfaceType type) {
        try {
            return type.resolve().describe();
        } catch (Exception e) {
            return type.asString();
        }
    }

    private boolean isExternalType(ClassOrInterfaceType type) {
        String name = type.asString();
        return name.startsWith("java.") || name.startsWith("javax.") ||
               name.startsWith("org.springframework.") || name.startsWith("com.google.");
    }

    private String getVisibility(NodeWithModifiers<?> node) {
        if (node.hasModifier(com.github.javaparser.ast.Modifier.Keyword.PUBLIC)) return "PUBLIC";
        if (node.hasModifier(com.github.javaparser.ast.Modifier.Keyword.PROTECTED)) return "PROTECTED";
        if (node.hasModifier(com.github.javaparser.ast.Modifier.Keyword.PRIVATE)) return "PRIVATE";
        return "PACKAGE_PRIVATE";
    }

    private List<String> extractAnnotations(NodeWithAnnotations<?> node) {
        return node.getAnnotations().stream()
                .map(AnnotationExpr::getNameAsString)
                .collect(Collectors.toList());
    }

    private int getStartLine(Node node) {
        return node.getRange().map(r -> r.begin.line).orElse(1);
    }

    private int getEndLine(Node node) {
        return node.getRange().map(r -> r.end.line).orElse(1);
    }

    private int getStartCol(Node node) {
        return node.getRange().map(r -> r.begin.column).orElse(1);
    }

    private int getEndCol(Node node) {
        return node.getRange().map(r -> r.end.column).orElse(1);
    }
}

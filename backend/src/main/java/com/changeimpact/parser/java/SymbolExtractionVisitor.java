package com.changeimpact.parser.java;

import com.changeimpact.parser.model.DependencyType;
import com.changeimpact.parser.model.ExtractedDependency;
import com.changeimpact.parser.model.ExtractedSymbol;
import com.changeimpact.parser.model.ResolutionStatus;
import com.changeimpact.parser.model.SymbolType;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.PackageDeclaration;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.MethodReferenceExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithAnnotations;
import com.github.javaparser.ast.nodeTypes.NodeWithModifiers;
import com.github.javaparser.ast.stmt.ExplicitConstructorInvocationStmt;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.Type;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;
import com.github.javaparser.resolution.declarations.ResolvedConstructorDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedMethodLikeDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedValueDeclaration;
import com.github.javaparser.resolution.types.ResolvedType;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.stream.Collectors;

/**
 * JavaParser AST visitor that extracts symbols (types, methods, constructors, fields, enum constants)
 * and typed dependencies, using JavaSymbolSolver for deterministic resolution.
 *
 * <h2>Naming scheme (must stay in sync with {@link #methodKey})</h2>
 * <ul>
 *   <li>Types: {@code pkg.Outer} and nested {@code pkg.Outer$Inner}</li>
 *   <li>Methods: {@code pkg.Type#name(P1,P2)} where each parameter is the <b>erased simple</b> type name
 *       (generics and package removed, varargs written as {@code []}), e.g.
 *       {@code Owner#addVisit(Integer,Visit)} or {@code Util#join(List,String[])}</li>
 *   <li>Constructors: {@code pkg.Type#<init>(P1,...)}; fields / enum constants: {@code pkg.Type#name}</li>
 * </ul>
 * Resolved call targets are rendered with the same scheme, so a call to an overloaded method links to
 * the exact overload instead of collapsing onto its class.
 *
 * <h2>Resolution honesty</h2>
 * A dependency is only marked {@link ResolutionStatus#RESOLVED} when the symbol solver actually resolved
 * it. Anything the solver cannot resolve (missing classpath, Lombok-generated members, reflection, ...)
 * is recorded as {@link ResolutionStatus#UNRESOLVED} with its source-text name and is never linked to
 * an internal symbol.
 */
@Getter
@Slf4j
public class SymbolExtractionVisitor extends VoidVisitorAdapter<Void> {

    private String packageName = "";
    private final List<ExtractedSymbol> symbols = new ArrayList<>();
    private final List<ExtractedDependency> dependencies = new ArrayList<>();

    /** Enclosing symbol context (type → member). Dependencies are attributed to the top of the stack. */
    private final Deque<String> symbolContextStack = new ArrayDeque<>();
    /** Table of the enclosing JPA entity ("" when the enclosing class is not one). */
    private final Deque<String> tableStack = new ArrayDeque<>();

    private final List<ImportDeclaration> imports = new ArrayList<>();

    // ------------------------------------------------------------------
    // Compilation unit / package / imports
    // ------------------------------------------------------------------

    @Override
    public void visit(CompilationUnit cu, Void arg) {
        super.visit(cu, arg);
        // Imports are a compile-time dependency of every top-level type declared in the file.
        List<String> topLevelTypes = symbols.stream()
                .filter(s -> s.getParentQualifiedName() == null)
                .map(ExtractedSymbol::getQualifiedName)
                .toList();
        for (ImportDeclaration imp : imports) {
            if (imp.isAsterisk() && !imp.isStatic()) {
                continue; // package wildcard: no single target type
            }
            String name = imp.getNameAsString();
            String target = (imp.isStatic() && !imp.isAsterisk())
                    ? name.substring(0, Math.max(0, name.lastIndexOf('.')))  // static member → its class
                    : name;
            if (target.isBlank()) {
                continue;
            }
            for (String source : topLevelTypes) {
                addDependency(source, target, DependencyType.IMPORT, line(imp), ResolutionStatus.RESOLVED);
            }
        }
    }

    @Override
    public void visit(PackageDeclaration pd, Void arg) {
        this.packageName = pd.getNameAsString();
        super.visit(pd, arg);
    }

    @Override
    public void visit(ImportDeclaration id, Void arg) {
        imports.add(id);
        super.visit(id, arg);
    }

    // ------------------------------------------------------------------
    // Type declarations
    // ------------------------------------------------------------------

    @Override
    public void visit(ClassOrInterfaceDeclaration cid, Void arg) {
        String qualifiedName = buildQualifiedName(cid.getNameAsString());
        SymbolType type = cid.isInterface() ? SymbolType.INTERFACE : SymbolType.CLASS;

        symbols.add(ExtractedSymbol.builder()
                .simpleName(cid.getNameAsString())
                .qualifiedName(qualifiedName)
                .symbolType(type)
                .parentQualifiedName(symbolContextStack.peek())
                .visibility(getVisibility(cid))
                .isStatic(cid.isStatic())
                .isAbstract(cid.isAbstract())
                .isFinal(cid.isFinal())
                .annotations(extractAnnotations(cid))
                .startLine(line(cid)).endLine(endLine(cid))
                .startColumn(col(cid)).endColumn(endCol(cid))
                .contentHash(SymbolFingerprints.typeHeaderHash(cid))
                .annotationText(SymbolFingerprints.annotationText(cid))
                .resolutionStatus(ResolutionStatus.RESOLVED)
                .build());

        for (ClassOrInterfaceType t : cid.getExtendedTypes()) {
            addTypeReference(qualifiedName, t, DependencyType.EXTENDS);
        }
        for (ClassOrInterfaceType t : cid.getImplementedTypes()) {
            addTypeReference(qualifiedName, t, DependencyType.IMPLEMENTS);
        }
        String table = JpaTables.table(cid);
        if (table != null) {
            addDependency(qualifiedName, table, DependencyType.ANNOTATION, line(cid), ResolutionStatus.RESOLVED);
            inheritedColumns(cid, qualifiedName, table);
        }

        symbolContextStack.push(qualifiedName);
        tableStack.push(table == null ? "" : table);
        super.visit(cid, arg);
        tableStack.pop();
        symbolContextStack.pop();
    }

    @Override
    public void visit(RecordDeclaration rd, Void arg) {
        String qualifiedName = buildQualifiedName(rd.getNameAsString());

        symbols.add(ExtractedSymbol.builder()
                .simpleName(rd.getNameAsString())
                .qualifiedName(qualifiedName)
                .symbolType(SymbolType.RECORD)
                .parentQualifiedName(symbolContextStack.peek())
                .visibility(getVisibility(rd))
                .isStatic(rd.isStatic())
                .isFinal(true)
                .annotations(extractAnnotations(rd))
                .startLine(line(rd)).endLine(endLine(rd))
                .startColumn(col(rd)).endColumn(endCol(rd))
                .contentHash(SymbolFingerprints.typeHeaderHash(rd))
                .annotationText(SymbolFingerprints.annotationText(rd))
                .resolutionStatus(ResolutionStatus.RESOLVED)
                .build());

        for (ClassOrInterfaceType t : rd.getImplementedTypes()) {
            addTypeReference(qualifiedName, t, DependencyType.IMPLEMENTS);
        }
        // Record components behave like final fields.
        for (Parameter component : rd.getParameters()) {
            String fieldQn = qualifiedName + "#" + component.getNameAsString();
            symbols.add(ExtractedSymbol.builder()
                    .simpleName(component.getNameAsString())
                    .qualifiedName(fieldQn)
                    .symbolType(SymbolType.FIELD)
                    .parentQualifiedName(qualifiedName)
                    .returnType(component.getType().asString())
                    .visibility("PRIVATE")
                    .isFinal(true)
                    .annotations(extractAnnotations(component))
                    .startLine(line(component)).endLine(endLine(component))
                    .startColumn(col(component)).endColumn(endCol(component))
                    .contentHash(SymbolFingerprints.declarationHash(component))
                    .annotationText(SymbolFingerprints.annotationText(component))
                    .resolutionStatus(ResolutionStatus.RESOLVED)
                    .build());
            addTypeDependencies(fieldQn, component.getType(), DependencyType.FIELD_TYPE);
        }

        symbolContextStack.push(qualifiedName);
        super.visit(rd, arg);
        symbolContextStack.pop();
    }

    @Override
    public void visit(EnumDeclaration ed, Void arg) {
        String qualifiedName = buildQualifiedName(ed.getNameAsString());

        symbols.add(ExtractedSymbol.builder()
                .simpleName(ed.getNameAsString())
                .qualifiedName(qualifiedName)
                .symbolType(SymbolType.ENUM)
                .parentQualifiedName(symbolContextStack.peek())
                .visibility(getVisibility(ed))
                .isStatic(ed.isStatic())
                .annotations(extractAnnotations(ed))
                .startLine(line(ed)).endLine(endLine(ed))
                .startColumn(col(ed)).endColumn(endCol(ed))
                .contentHash(SymbolFingerprints.typeHeaderHash(ed))
                .annotationText(SymbolFingerprints.annotationText(ed))
                .resolutionStatus(ResolutionStatus.RESOLVED)
                .build());

        for (ClassOrInterfaceType t : ed.getImplementedTypes()) {
            addTypeReference(qualifiedName, t, DependencyType.IMPLEMENTS);
        }

        symbolContextStack.push(qualifiedName);
        super.visit(ed, arg);
        symbolContextStack.pop();
    }

    @Override
    public void visit(EnumConstantDeclaration ecd, Void arg) {
        String parent = symbolContextStack.peek();
        if (parent != null) {
            symbols.add(ExtractedSymbol.builder()
                    .simpleName(ecd.getNameAsString())
                    .qualifiedName(parent + "#" + ecd.getNameAsString())
                    .symbolType(SymbolType.FIELD)
                    .parentQualifiedName(parent)
                    .returnType(simpleName(parent))
                    .visibility("PUBLIC")
                    .isStatic(true)
                    .isFinal(true)
                    .annotations(extractAnnotations(ecd))
                    .startLine(line(ecd)).endLine(endLine(ecd))
                    .startColumn(col(ecd)).endColumn(endCol(ecd))
                    .contentHash(SymbolFingerprints.declarationHash(ecd))
                    .annotationText(SymbolFingerprints.annotationText(ecd))
                    .resolutionStatus(ResolutionStatus.RESOLVED)
                    .build());
        }
        super.visit(ecd, arg);
    }

    // ------------------------------------------------------------------
    // Members
    // ------------------------------------------------------------------

    @Override
    public void visit(MethodDeclaration md, Void arg) {
        String parentContext = symbolContextStack.peek();
        String owner = parentContext != null ? parentContext : packageName;
        String params = declaredSignature(md.getParameters());
        String qualifiedName = owner + "#" + md.getNameAsString() + "(" + params + ")";
        String returnType = md.getType().asString();

        symbols.add(ExtractedSymbol.builder()
                .simpleName(md.getNameAsString())
                .qualifiedName(qualifiedName)
                .symbolType(SymbolType.METHOD)
                .parentQualifiedName(parentContext)
                .returnType(returnType)
                .parameters(md.getParameters().stream().map(p -> p.getType().asString() + (p.isVarArgs() ? "..." : ""))
                        .collect(Collectors.joining(",")))
                .visibility(getVisibility(md))
                .isStatic(md.isStatic())
                .isAbstract(md.isAbstract())
                .isFinal(md.isFinal())
                .annotations(extractAnnotations(md))
                .startLine(line(md)).endLine(endLine(md))
                .startColumn(col(md)).endColumn(endCol(md))
                .contentHash(SymbolFingerprints.declarationHash(md))
                .annotationText(SymbolFingerprints.annotationText(md))
                .complexity(SymbolFingerprints.cyclomaticComplexity(md))
                .resolutionStatus(ResolutionStatus.RESOLVED)
                .build());

        for (Parameter param : md.getParameters()) {
            addTypeDependencies(qualifiedName, param.getType(), DependencyType.PARAMETER_TYPE);
        }
        if (!md.getType().isVoidType()) {
            addTypeDependencies(qualifiedName, md.getType(), DependencyType.RETURN_TYPE);
        }

        symbolContextStack.push(qualifiedName);
        super.visit(md, arg);
        symbolContextStack.pop();
    }

    @Override
    public void visit(ConstructorDeclaration cd, Void arg) {
        String parentContext = symbolContextStack.peek();
        String owner = parentContext != null ? parentContext : packageName;
        String qualifiedName = owner + "#<init>(" + declaredSignature(cd.getParameters()) + ")";

        symbols.add(ExtractedSymbol.builder()
                .simpleName(cd.getNameAsString())
                .qualifiedName(qualifiedName)
                .symbolType(SymbolType.CONSTRUCTOR)
                .parentQualifiedName(parentContext)
                .parameters(cd.getParameters().stream().map(p -> p.getType().asString() + (p.isVarArgs() ? "..." : ""))
                        .collect(Collectors.joining(",")))
                .visibility(getVisibility(cd))
                .annotations(extractAnnotations(cd))
                .startLine(line(cd)).endLine(endLine(cd))
                .startColumn(col(cd)).endColumn(endCol(cd))
                .contentHash(SymbolFingerprints.declarationHash(cd))
                .annotationText(SymbolFingerprints.annotationText(cd))
                .complexity(SymbolFingerprints.cyclomaticComplexity(cd))
                .resolutionStatus(ResolutionStatus.RESOLVED)
                .build());

        for (Parameter param : cd.getParameters()) {
            addTypeDependencies(qualifiedName, param.getType(), DependencyType.PARAMETER_TYPE);
        }

        symbolContextStack.push(qualifiedName);
        super.visit(cd, arg);
        symbolContextStack.pop();
    }

    @Override
    public void visit(FieldDeclaration fd, Void arg) {
        String parentContext = symbolContextStack.peek();
        String owner = parentContext != null ? parentContext : packageName;

        for (VariableDeclarator var : fd.getVariables()) {
            String qualifiedName = owner + "#" + var.getNameAsString();
            symbols.add(ExtractedSymbol.builder()
                    .simpleName(var.getNameAsString())
                    .qualifiedName(qualifiedName)
                    .symbolType(SymbolType.FIELD)
                    .parentQualifiedName(parentContext)
                    .returnType(var.getType().asString())
                    .visibility(getVisibility(fd))
                    .isStatic(fd.isStatic())
                    .isFinal(fd.isFinal())
                    .annotations(extractAnnotations(fd))
                    .startLine(line(var)).endLine(endLine(var))
                    .startColumn(col(var)).endColumn(endCol(var))
                    .contentHash(SymbolFingerprints.fieldHash(fd, var))
                    .annotationText(SymbolFingerprints.annotationText(fd))
                    .resolutionStatus(ResolutionStatus.RESOLVED)
                    .build());
            addTypeDependencies(qualifiedName, var.getType(), DependencyType.FIELD_TYPE);
            String table = tableStack.peek();
            String column = table == null || table.isEmpty() ? null : JpaTables.column(fd, var);
            if (column != null) addDependency(qualifiedName, table + "#" + column, DependencyType.ANNOTATION, line(var), ResolutionStatus.RESOLVED);
        }

        super.visit(fd, arg);
    }

    // ------------------------------------------------------------------
    // Behavioural references (inside bodies / initializers)
    // ------------------------------------------------------------------

    /** SQL in string literals and text blocks ({@code @Query(nativeQuery = true)}, JdbcTemplate) → its tables. */
    @Override
    public void visit(com.github.javaparser.ast.expr.StringLiteralExpr s, Void arg) {
        sqlTables(s.getValue(), line(s));
        super.visit(s, arg);
    }

    @Override
    public void visit(com.github.javaparser.ast.expr.TextBlockLiteralExpr s, Void arg) {
        sqlTables(s.getValue(), line(s));
        super.visit(s, arg);
    }

    private void sqlTables(String literal, int line) {
        String context = symbolContextStack.peek();
        if (context == null) return;
        for (String table : com.changeimpact.parser.language.ts.EmbeddedSql.tables(literal)) {
            addDependency(context, table, DependencyType.FIELD_ACCESS, line, ResolutionStatus.RESOLVED);
        }
    }

    @Override
    public void visit(MethodCallExpr mce, Void arg) {
        String context = symbolContextStack.peek();
        if (context != null) {
            try {
                var resolved = mce.resolve();
                addDependency(context, methodKey(resolved, resolved.getName()), DependencyType.METHOD_CALL,
                        line(mce), ResolutionStatus.RESOLVED);
            } catch (Exception | StackOverflowError e) {
                String scope = mce.getScope().map(Node::toString).orElse("");
                addDependency(context, (scope.isBlank() ? "" : scope + ".") + mce.getNameAsString() + "()",
                        DependencyType.METHOD_CALL, line(mce), ResolutionStatus.UNRESOLVED);
            }
        }
        super.visit(mce, arg);
    }

    @Override
    public void visit(MethodReferenceExpr mre, Void arg) {
        String context = symbolContextStack.peek();
        if (context != null) {
            try {
                var resolved = mre.resolve();
                addDependency(context, methodKey(resolved, resolved.getName()), DependencyType.METHOD_CALL,
                        line(mre), ResolutionStatus.RESOLVED);
            } catch (Exception | StackOverflowError e) {
                addDependency(context, mre.getScope() + "::" + mre.getIdentifier(), DependencyType.METHOD_CALL,
                        line(mre), ResolutionStatus.UNRESOLVED);
            }
        }
        super.visit(mre, arg);
    }

    @Override
    public void visit(ObjectCreationExpr oce, Void arg) {
        String context = symbolContextStack.peek();
        if (context != null) {
            try {
                ResolvedConstructorDeclaration resolved = oce.resolve();
                addDependency(context, methodKey(resolved, "<init>"), DependencyType.CONSTRUCTOR_CALL,
                        line(oce), ResolutionStatus.RESOLVED);
            } catch (Exception | StackOverflowError e) {
                // Constructor overload not resolvable (e.g. implicit default constructor): fall back to the type.
                ResolvedType type = tryResolve(oce.getType());
                if (type != null && type.isReferenceType()) {
                    addDependency(context, type.asReferenceType().getQualifiedName() + "#<init>(?)",
                            DependencyType.CONSTRUCTOR_CALL, line(oce), ResolutionStatus.PARTIAL);
                } else {
                    addDependency(context, oce.getType().getNameWithScope() + "#<init>()",
                            DependencyType.CONSTRUCTOR_CALL, line(oce), ResolutionStatus.UNRESOLVED);
                }
            }
        }
        super.visit(oce, arg);
    }

    @Override
    public void visit(ExplicitConstructorInvocationStmt ecis, Void arg) {
        String context = symbolContextStack.peek();
        if (context != null) {
            try {
                ResolvedConstructorDeclaration resolved = ecis.resolve();
                addDependency(context, methodKey(resolved, "<init>"), DependencyType.CONSTRUCTOR_CALL,
                        line(ecis), ResolutionStatus.RESOLVED);
            } catch (Exception | StackOverflowError e) {
                addDependency(context, (ecis.isThis() ? "this" : "super") + "#<init>()",
                        DependencyType.CONSTRUCTOR_CALL, line(ecis), ResolutionStatus.UNRESOLVED);
            }
        }
        super.visit(ecis, arg);
    }

    @Override
    public void visit(FieldAccessExpr fae, Void arg) {
        String context = symbolContextStack.peek();
        if (context != null && isExpressionContext(fae)) {
            try {
                ResolvedValueDeclaration value = fae.resolve();
                String target = fieldKey(value, fae.getNameAsString());
                if (target != null) {
                    addDependency(context, target, DependencyType.FIELD_ACCESS, line(fae), ResolutionStatus.RESOLVED);
                }
            } catch (Exception | StackOverflowError e) {
                // Also hit for package/type-qualified names, which are not field accesses: record nothing.
            }
        }
        super.visit(fae, arg);
    }

    @Override
    public void visit(NameExpr ne, Void arg) {
        String context = symbolContextStack.peek();
        if (context != null && context.contains("#")) { // only inside members, not type-level contexts
            try {
                ResolvedValueDeclaration value = ne.resolve();
                String target = fieldKey(value, ne.getNameAsString());
                if (target != null) {
                    addDependency(context, target, DependencyType.FIELD_ACCESS, line(ne), ResolutionStatus.RESOLVED);
                }
            } catch (Exception | StackOverflowError e) {
                // Locals, parameters and type names used as scopes are not field dependencies.
            }
        }
        super.visit(ne, arg);
    }

    // ------------------------------------------------------------------
    // Dependency helpers
    // ------------------------------------------------------------------

    private void addDependency(String source, String target, DependencyType type, int line, ResolutionStatus status) {
        dependencies.add(ExtractedDependency.builder()
                .sourceQualifiedName(source)
                .targetQualifiedName(target)
                .dependencyType(type)
                .isExternal(false) // decided later by CodeAnalysisService: external == no internal target symbol
                .sourceLine(line)
                .resolutionStatus(status)
                .build());
    }

    /** EXTENDS / IMPLEMENTS: one dependency on the (erased) referenced type. */
    private void addTypeReference(String source, ClassOrInterfaceType type, DependencyType depType) {
        ResolvedType resolved = tryResolve(type);
        if (resolved != null && resolved.isReferenceType()) {
            addDependency(source, resolved.asReferenceType().getQualifiedName(), depType, line(type), ResolutionStatus.RESOLVED);
        } else {
            addDependency(source, type.getNameWithScope(), depType, line(type), ResolutionStatus.UNRESOLVED);
        }
    }

    /**
     * Records a dependency on every class/interface type mentioned in a declared type, including generic
     * arguments: {@code Map<String, List<Pet>>} yields Map, String, List and Pet. Primitives and type
     * variables are skipped.
     */
    private void addTypeDependencies(String source, Type type, DependencyType depType) {
        Set<String> seen = new HashSet<>();
        for (ClassOrInterfaceType cit : type.findAll(ClassOrInterfaceType.class)) {
            if (isScopeOfOuterType(cit)) {
                continue; // "Map" in "Map.Entry" is a qualifier, not a separate reference
            }
            ResolvedType resolved = tryResolve(cit);
            String target;
            ResolutionStatus status;
            if (resolved == null) {
                target = cit.getNameWithScope();
                status = ResolutionStatus.UNRESOLVED;
            } else if (resolved.isTypeVariable()) {
                continue;
            } else if (resolved.isReferenceType()) {
                target = resolved.asReferenceType().getQualifiedName();
                status = ResolutionStatus.RESOLVED;
            } else {
                continue;
            }
            if (seen.add(target)) {
                addDependency(source, target, depType, line(cit), status);
            }
        }
    }

    private static boolean isScopeOfOuterType(ClassOrInterfaceType cit) {
        return cit.getParentNode()
                .filter(p -> p instanceof ClassOrInterfaceType)
                .map(p -> ((ClassOrInterfaceType) p).getScope().map(s -> s == cit).orElse(false))
                .orElse(false);
    }

    /**
     * Columns of fields inherited from {@code @MappedSuperclass}es (Person#firstName → owners.first_name). They are
     * linked from the entity itself, the declaration in this file, so the link does not depend on the superclass's file
     * being parsed in the same run (incremental analysis copies unchanged files).
     */
    private void inheritedColumns(ClassOrInterfaceDeclaration entity, String entityQn, String table) {
        com.github.javaparser.resolution.declarations.ResolvedReferenceTypeDeclaration current;
        try {
            current = entity.resolve();
        } catch (Exception | StackOverflowError e) {
            return;
        }
        for (int depth = 0; depth < 8 && current != null; depth++) {
            com.github.javaparser.resolution.declarations.ResolvedReferenceTypeDeclaration superType = null;
            try {
                for (var a : current.getAncestors(true)) {
                    var d = a.getTypeDeclaration().orElse(null);
                    if (d != null && d.isClass() && !d.getQualifiedName().equals("java.lang.Object")) { superType = d; break; }
                }
            } catch (Exception | StackOverflowError e) {
                return;
            }
            if (superType == null) return;
            ClassOrInterfaceDeclaration superDecl;
            try {
                superDecl = superType.toAst().filter(n -> n instanceof ClassOrInterfaceDeclaration).map(n -> (ClassOrInterfaceDeclaration) n).orElse(null);
            } catch (Exception e) {
                superDecl = null;
            }
            if (superDecl == null || !JpaTables.mappedSuperclass(superDecl)) return; // library type, or an @Entity with its own table
            for (FieldDeclaration fd : superDecl.getFields()) {
                for (VariableDeclarator var : fd.getVariables()) {
                    String column = JpaTables.column(fd, var);
                    if (column != null) addDependency(entityQn, table + "#" + column, DependencyType.ANNOTATION, line(entity), ResolutionStatus.RESOLVED);
                }
            }
            current = superType;
        }
    }

    private static ResolvedType tryResolve(ClassOrInterfaceType type) {
        try {
            return type.resolve();
        } catch (Exception | StackOverflowError e) {
            return null;
        }
    }

    /** {@code pkg.Type#field} for resolved fields and enum constants, null for locals/parameters. */
    private static String fieldKey(ResolvedValueDeclaration value, String name) {
        if (value.isField()) {
            return value.asField().declaringType().getQualifiedName() + "#" + name;
        }
        if (value.isEnumConstant()) {
            ResolvedType enumType = value.getType();
            if (enumType.isReferenceType()) {
                return enumType.asReferenceType().getQualifiedName() + "#" + name;
            }
        }
        return null;
    }

    /** A FieldAccessExpr that is a value (not a package/type qualifier such as {@code java.util}). */
    private static boolean isExpressionContext(FieldAccessExpr fae) {
        return fae.getParentNode().map(p -> !(p instanceof ImportDeclaration)
                && !(p instanceof PackageDeclaration)
                && !(p instanceof ClassOrInterfaceType)).orElse(true);
    }

    /**
     * Renders a resolved method/constructor using the same key format as declared symbols:
     * {@code pkg.Type#name(ErasedSimple1,ErasedSimple2)}. If a parameter type cannot be described the
     * signature becomes {@code (?)}, which CodeAnalysisService treats as a partial (class-level) match.
     */
    static String methodKey(ResolvedMethodLikeDeclaration resolved, String name) {
        String owner = resolved.declaringType().getQualifiedName();
        StringBuilder params = new StringBuilder();
        try {
            for (int i = 0; i < resolved.getNumberOfParams(); i++) {
                if (i > 0) params.append(',');
                params.append(normalizeTypeName(resolved.getParam(i).getType().describe()));
            }
        } catch (Exception | StackOverflowError e) {
            params.setLength(0);
            params.append('?');
        }
        return owner + "#" + name + "(" + params + ")";
    }

    static String declaredSignature(NodeList<Parameter> parameters) {
        return parameters.stream()
                .map(p -> normalizeTypeName(p.getType().asString()) + (p.isVarArgs() ? "[]" : ""))
                .collect(Collectors.joining(","));
    }

    /**
     * Erased simple type name: {@code java.util.List<java.lang.String>} → {@code List},
     * {@code Map.Entry<K,V>[]} → {@code Entry[]}, {@code String...} → {@code String[]}.
     */
    static String normalizeTypeName(String typeName) {
        String t = typeName.replace("...", "[]").replaceAll("\\s+", "");
        // Remove (possibly nested) generic arguments.
        StringBuilder erased = new StringBuilder();
        int depth = 0;
        for (char c : t.toCharArray()) {
            if (c == '<') depth++;
            else if (c == '>') depth--;
            else if (depth == 0) erased.append(c);
        }
        String base = erased.toString();
        int arrayStart = base.indexOf('[');
        String suffix = arrayStart >= 0 ? base.substring(arrayStart) : "";
        String name = arrayStart >= 0 ? base.substring(0, arrayStart) : base;
        name = name.substring(Math.max(name.lastIndexOf('.'), name.lastIndexOf('$')) + 1);
        return name + suffix;
    }

    // ------------------------------------------------------------------
    // Generic helpers
    // ------------------------------------------------------------------

    private String buildQualifiedName(String simpleName) {
        String parent = symbolContextStack.peek();
        if (parent != null) {
            return parent + "$" + simpleName;
        }
        return (packageName != null && !packageName.isBlank()) ? packageName + "." + simpleName : simpleName;
    }

    private static String simpleName(String qualifiedName) {
        return qualifiedName.substring(Math.max(qualifiedName.lastIndexOf('.'), qualifiedName.lastIndexOf('$')) + 1);
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

    private static int line(Node node) {
        return node.getRange().map(r -> r.begin.line).orElse(1);
    }

    private static int endLine(Node node) {
        return node.getRange().map(r -> r.end.line).orElse(1);
    }

    private static int col(Node node) {
        return node.getRange().map(r -> r.begin.column).orElse(1);
    }

    private static int endCol(Node node) {
        return node.getRange().map(r -> r.end.column).orElse(1);
    }
}

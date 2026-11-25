package com.changeimpact.parser.language.ts;

import com.changeimpact.common.config.AppProperties;
import com.changeimpact.parser.model.*;
import org.springframework.stereotype.Component;
import org.treesitter.TSLanguage;
import org.treesitter.TSNode;
import org.treesitter.TreeSitterGo;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Go.
 *
 * <p>Model: a package (directory) is a {@code MODULE} named by its path ({@code internal/handlers} →
 * {@code internal.handlers}; the root package by its package name), emitted in its first file. Types are
 * {@code internal.handlers.Server}, methods {@code internal.handlers.Server#Start}, struct fields and interface methods
 * members of their type, functions and package-level variables/constants {@code internal.handlers#NewServer}. Import
 * paths are mapped to directories through {@code go.mod}; other packages are external ({@code go:fmt#Println}).
 *
 * <p>Links: imports; calls to package functions ({@code f()}, {@code pkg.F()}); method calls on receivers, parameters,
 * fields and locals whose type is declared ({@code x := NewServer()}, {@code &Server{}}, {@code var s Server}),
 * including methods promoted from embedded structs; composite literals as {@code CONSTRUCTOR_CALL}; embedding as
 * {@code EXTENDS}; parameter/result/field types; package variables; tables in SQL strings. Go interfaces are satisfied
 * implicitly: a repository type whose method set contains all methods of a repository interface gets an
 * {@code IMPLEMENTS} link (names only; signatures are not compared). Calls through interfaces stay on the interface
 * method.
 */
@Component
public class GoLanguageParser extends TreeSitterParser {

    private static final Set<String> COMMENTS = Set.of("comment");
    private static final Set<String> DECISIONS = Set.of("if_statement", "for_statement", "expression_case", "type_case",
            "communication_case");
    private static final Set<String> LOGICAL = Set.of("&&", "||");

    public GoLanguageParser(AppProperties appProperties) {
        super(appProperties, new TreeSitterGo());
    }

    @Override public String language() { return "go"; }
    @Override public Set<String> extensions() { return Set.of(".go"); }
    @Override protected TSLanguage grammarFor(String relativePath) { return new TreeSitterGo(); }

    @Override
    protected boolean accepts(String relativePath) {
        String n = relativePath.toLowerCase(Locale.ROOT);
        return n.endsWith(".go") && !n.endsWith(".pb.go") && !n.endsWith("_gen.go") && !n.contains("/testdata/") && !n.startsWith("testdata/");
    }

    /** Packages, their declarations and the module paths of the repository's go.mod files. */
    static final class Index {
        final TypeIndex types = new TypeIndex();
        final Map<String, String> pkgByDir = new HashMap<>();          // dir → package qn
        final Map<String, String> pkgName = new HashMap<>();           // dir → `package` clause name
        final Map<String, String> modules = new TreeMap<>(Comparator.comparingInt(String::length).reversed().thenComparing(Comparator.naturalOrder())); // module path → dir
        final Set<String> vars = new HashSet<>();                      // pkg#Name
        final Map<String, String> funcReturns = new HashMap<>();       // pkg#F → type qn
        final Set<String> interfaces = new HashSet<>();

        String pkg(String dir) {
            return pkgByDir.get(dir);
        }

        /** Import path → directory in the repository, or null if external. */
        String dirOf(String importPath) {
            for (var m : modules.entrySet()) {
                String mod = m.getKey();
                if (importPath.equals(mod) || importPath.startsWith(mod + "/")) {
                    String rel = importPath.substring(mod.length()).replaceFirst("^/", "");
                    String dir = m.getValue().isEmpty() ? rel : rel.isEmpty() ? m.getValue() : m.getValue() + "/" + rel;
                    return pkgByDir.containsKey(dir) ? dir : null;
                }
            }
            return null;
        }
    }

    static String importName(String path) {
        String[] parts = path.split("/");
        String last = parts[parts.length - 1];
        if (last.matches("v\\d+") && parts.length > 1) last = parts[parts.length - 2];
        if (last.matches(".*\\.v\\d+")) last = last.substring(0, last.lastIndexOf('.'));
        return last.replaceAll("^go-", "").replaceAll("[-.]", "_");
    }

    @Override
    protected Map<String, ParsedSourceFile> analyse(Path root, List<Source> sources) {
        Index index = new Index();
        readModules(root, index);
        List<Source> sorted = sources.stream().sorted(Comparator.comparing(Source::path)).toList();
        for (Source s : sorted) {
            String dir = directory(s.path());
            TSNode clause = firstNamed(s.root(), "package_clause");
            String name = clause == null || named(clause).isEmpty() ? "main" : s.text(named(clause).get(0));
            if (name.endsWith("_test") && index.pkgName.containsKey(dir)) continue;
            index.pkgName.putIfAbsent(dir, name.replaceAll("_test$", ""));
        }
        index.pkgName.forEach((dir, name) -> index.pkgByDir.put(dir, dir.isEmpty() ? name : pathName(dir, false)));
        for (Source s : sorted) declare(s, index);
        for (Source s : sorted) resolveTypes(s, index);
        Set<String> moduleEmitted = new HashSet<>();
        Map<String, ParsedSourceFile> out = new LinkedHashMap<>();
        for (Source s : sorted) {
            try {
                out.put(s.path(), new Extractor(s, index, moduleEmitted.add(directory(s.path()))).extract());
            } catch (Exception e) {
                org.slf4j.LoggerFactory.getLogger(getClass()).debug("Go file {} skipped: {}", s.path(), e.toString());
            }
        }
        implementsLinks(index, out, sorted);
        return out;
    }

    private static void readModules(Path root, Index index) {
        try (var walk = Files.walk(root, 6)) {
            walk.filter(p -> p.getFileName().toString().equals("go.mod"))
                    .filter(p -> SKIP_DIRS.stream().noneMatch(d -> root.relativize(p).toString().contains(d + "/")))
                    .forEach(p -> {
                        try {
                            for (String line : Files.readAllLines(p)) {
                                String l = line.trim();
                                if (l.startsWith("module ")) {
                                    String dir = root.relativize(p.getParent()).toString().replace('\\', '/');
                                    index.modules.put(l.substring(7).trim().replace("\"", ""), dir);
                                    break;
                                }
                            }
                        } catch (Exception e) {
                            // unreadable go.mod: imports into it stay external
                        }
                    });
        } catch (Exception e) {
            // no modules
        }
    }

    /** Pass 1a: types, functions, methods, fields, package variables. */
    private static void declare(Source s, Index index) {
        String pkg = index.pkg(directory(s.path()));
        for (TSNode d : named(s.root())) {
            switch (d.getType()) {
                case "type_declaration" -> {
                    for (TSNode spec : named(d)) {
                        if (!spec.getType().equals("type_spec") && !spec.getType().equals("type_alias")) continue;
                        String name = s.text(field(spec, "name"));
                        TSNode type = field(spec, "type");
                        boolean iface = type != null && type.getType().equals("interface_type");
                        TypeIndex.TypeInfo t = index.types.declare(pkg + "." + name, iface ? "interface" : "struct");
                        if (iface) {
                            index.interfaces.add(t.qn);
                            for (TSNode m : named(type)) if (m.getType().equals("method_elem") || m.getType().equals("method_spec")) t.methods.add(s.text(field(m, "name")));
                        } else if (type != null && type.getType().equals("struct_type")) {
                            for (TSNode f : descendants(type, Set.of("field_declaration"), Set.of("struct_type"))) {
                                for (TSNode n : named(f)) if (n.getType().equals("field_identifier")) t.fields.add(s.text(n));
                            }
                        }
                    }
                }
                case "function_declaration" -> index.types.function(pkg + "#" + s.text(field(d, "name")), s.text(field(d, "name")));
                case "method_declaration" -> {
                    String recv = receiverTypeName(s, field(d, "receiver"));
                    if (recv != null) index.types.declare(pkg + "." + recv, "struct").methods.add(s.text(field(d, "name")));
                }
                case "var_declaration", "const_declaration" -> {
                    for (TSNode spec : descendants(d, Set.of("var_spec", "const_spec"), Set.of())) {
                        for (TSNode n : named(spec)) if (n.getType().equals("identifier") && !s.text(n).equals("_")) index.vars.add(pkg + "#" + s.text(n));
                    }
                }
                default -> { }
            }
        }
    }

    static String receiverTypeName(Source s, TSNode receiver) {
        if (receiver == null) return null;
        TSNode p = firstNamed(receiver, "parameter_declaration");
        if (p == null) return null;
        TSNode t = field(p, "type");
        while (t != null && (t.getType().equals("pointer_type") || t.getType().equals("generic_type"))) {
            t = t.getType().equals("generic_type") ? field(t, "type") : named(t).isEmpty() ? null : named(t).get(0);
        }
        return t == null ? null : s.text(t);
    }

    /** Per-file imports: local name → directory (internal) or "go:" + path (external). */
    static Map<String, String> imports(Source s, Index index) {
        Map<String, String> out = new HashMap<>();
        for (TSNode spec : descendants(s.root(), Set.of("import_spec"), Set.of())) {
            TSNode pathNode = field(spec, "path");
            if (pathNode == null) continue;
            String path = s.text(pathNode).replaceAll("^[\"`]|[\"`]$", "");
            TSNode nameNode = field(spec, "name");
            String dir = index.dirOf(path);
            String local = nameNode != null ? s.text(nameNode) : dir != null ? index.pkgName.get(dir) : importName(path);
            out.put(local, dir != null ? dir : "go:" + path);
        }
        return out;
    }

    /** Pass 1b: field types, embedded types, result types (need all types declared). */
    private static void resolveTypes(Source s, Index index) {
        String pkg = index.pkg(directory(s.path()));
        Map<String, String> imports = imports(s, index);
        for (TSNode d : named(s.root())) {
            switch (d.getType()) {
                case "type_declaration" -> {
                    for (TSNode spec : named(d)) {
                        if (!spec.getType().equals("type_spec")) continue;
                        TypeIndex.TypeInfo t = index.types.get(pkg + "." + s.text(field(spec, "name")));
                        TSNode type = field(spec, "type");
                        if (t == null || type == null) continue;
                        if (type.getType().equals("struct_type")) {
                            for (TSNode f : descendants(type, Set.of("field_declaration"), Set.of("struct_type"))) {
                                String ft = typeOf(s, field(f, "type"), pkg, imports, index);
                                List<TSNode> names = named(f).stream().filter(n -> n.getType().equals("field_identifier")).toList();
                                if (names.isEmpty() && ft != null) t.supertypes.add(ft); // embedded
                                if (ft != null) for (TSNode n : names) t.fieldTypes.put(s.text(n), ft);
                            }
                        } else if (type.getType().equals("interface_type")) {
                            for (TSNode m : named(type)) {
                                if (m.getType().equals("type_elem") || m.getType().equals("constraint_elem")) {
                                    for (TSNode e : named(m)) { String et = typeOf(s, e, pkg, imports, index); if (et != null) t.supertypes.add(et); }
                                } else if (m.getType().equals("method_elem")) {
                                    String rt = resultType(s, field(m, "result"), pkg, imports, index);
                                    if (rt != null) t.returnTypes.put(s.text(field(m, "name")), rt);
                                }
                            }
                        }
                    }
                }
                case "function_declaration" -> {
                    String rt = resultType(s, field(d, "result"), pkg, imports, index);
                    if (rt != null) index.funcReturns.put(pkg + "#" + s.text(field(d, "name")), rt);
                }
                case "method_declaration" -> {
                    String recv = receiverTypeName(s, field(d, "receiver"));
                    String rt = resultType(s, field(d, "result"), pkg, imports, index);
                    TypeIndex.TypeInfo t = recv == null ? null : index.types.get(pkg + "." + recv);
                    if (t != null && rt != null) t.returnTypes.put(s.text(field(d, "name")), rt);
                }
                default -> { }
            }
        }
    }

    /** {@code T}, {@code *T}, {@code (T, error)} → T. */
    static String resultType(Source s, TSNode result, String pkg, Map<String, String> imports, Index index) {
        if (result == null) return null;
        if (result.getType().equals("parameter_list")) {
            List<TSNode> ps = named(result);
            if (ps.isEmpty()) return null;
            TSNode first = ps.get(0);
            return typeOf(s, field(first, "type") != null ? field(first, "type") : first, pkg, imports, index);
        }
        return typeOf(s, result, pkg, imports, index);
    }

    /** A named type in the repository: {@code T}, {@code *T}, {@code pkg.T}, {@code T[int]}; null otherwise. */
    static String typeOf(Source s, TSNode t, String pkg, Map<String, String> imports, Index index) {
        if (t == null) return null;
        switch (t.getType()) {
            case "pointer_type", "parenthesized_type" -> { return named(t).isEmpty() ? null : typeOf(s, named(t).get(0), pkg, imports, index); }
            case "generic_type" -> { return typeOf(s, field(t, "type"), pkg, imports, index); }
            case "type_identifier" -> {
                String q = pkg + "." + s.text(t);
                return index.types.has(q) ? q : null;
            }
            case "qualified_type" -> {
                String dir = imports.get(s.text(field(t, "package")));
                if (dir == null || dir.startsWith("go:")) return null;
                String q = index.pkg(dir) + "." + s.text(field(t, "name"));
                return index.types.has(q) ? q : null;
            }
            default -> { return null; }
        }
    }

    /** Structural typing: T implements I when T's method set (with embedded types) covers I's methods. */
    private static void implementsLinks(Index index, Map<String, ParsedSourceFile> out, List<Source> sources) {
        Map<String, ParsedSourceFile> fileOfType = new HashMap<>();
        for (ParsedSourceFile f : out.values()) for (ExtractedSymbol sym : f.getSymbols()) {
            if (sym.getSymbolType() == SymbolType.CLASS || sym.getSymbolType() == SymbolType.INTERFACE) fileOfType.putIfAbsent(sym.getQualifiedName(), f);
        }
        List<String> ifaces = index.interfaces.stream().sorted().toList();
        for (TypeIndex.TypeInfo t : index.types.all().stream().sorted(Comparator.comparing(x -> x.qn)).toList()) {
            if (t.kind.equals("interface")) continue;
            ParsedSourceFile f = fileOfType.get(t.qn);
            if (f == null) continue;
            for (String i : ifaces) {
                Set<String> required = methodSet(index, i);
                if (required.isEmpty()) continue;
                boolean all = required.stream().allMatch(m -> index.types.method(t.qn, m) != null);
                if (all) f.getDependencies().add(ExtractedDependency.builder().sourceQualifiedName(t.qn).targetQualifiedName(i)
                        .dependencyType(DependencyType.IMPLEMENTS).isExternal(false).sourceLine(f.getSymbols().stream()
                                .filter(x -> x.getQualifiedName().equals(t.qn)).findFirst().map(ExtractedSymbol::getStartLine).orElse(0))
                        .resolutionStatus(ResolutionStatus.PARTIAL).build());
            }
        }
    }

    private static Set<String> methodSet(Index index, String iface) {
        Set<String> out = new HashSet<>();
        Deque<String> q = new ArrayDeque<>(List.of(iface));
        Set<String> seen = new HashSet<>();
        while (!q.isEmpty()) {
            TypeIndex.TypeInfo t = index.types.get(q.poll());
            if (t == null || !seen.add(t.qn)) continue;
            out.addAll(t.methods);
            q.addAll(t.supertypes);
        }
        return out;
    }

    // ---- pass 2 -------------------------------------------------------------------------------------------------

    static final class Extractor {
        private final Source s;
        private final Index index;
        private final boolean emitModule;
        private final Emitter e = new Emitter();
        private final String pkg, dir;
        private final Map<String, String> imports;

        Extractor(Source s, Index index, boolean emitModule) {
            this.s = s;
            this.index = index;
            this.emitModule = emitModule;
            this.dir = directory(s.path());
            this.pkg = index.pkg(dir);
            this.imports = imports(s, index);
        }

        ParsedSourceFile extract() {
            if (emitModule) {
                StringBuilder clause = new StringBuilder("package ").append(index.pkgName.get(dir));
                e.symbols.add(symbol(dir.isEmpty() ? pkg : dir.substring(dir.lastIndexOf('/') + 1), pkg, SymbolType.MODULE, null, s.root())
                        .startLine(1).endLine(1).contentHash(hash(clause.toString())).build());
            }
            for (TSNode spec : descendants(s.root(), Set.of("import_spec"), Set.of())) {
                TSNode pathNode = field(spec, "path");
                if (pathNode == null) continue;
                String path = s.text(pathNode).replaceAll("^[\"`]|[\"`]$", "");
                String d = index.dirOf(path);
                e.dep(pkg, d != null ? index.pkg(d) : "go:" + path, DependencyType.IMPORT, spec);
            }
            for (TSNode d : named(s.root())) {
                switch (d.getType()) {
                    case "type_declaration" -> { for (TSNode spec : named(d)) if (spec.getType().equals("type_spec") || spec.getType().equals("type_alias")) type(spec, d); }
                    case "function_declaration" -> function(d, null);
                    case "method_declaration" -> {
                        String recv = receiverTypeName(s, field(d, "receiver"));
                        if (recv != null) function(d, pkg + "." + recv);
                    }
                    case "var_declaration", "const_declaration" -> variables(d);
                    default -> { }
                }
            }
            boolean test = s.path().endsWith("_test.go");
            return e.file(s, pkg, test, "go");
        }

        private static String visibility(String name) {
            return !name.isEmpty() && Character.isUpperCase(name.charAt(0)) ? "public" : "package";
        }

        private void type(TSNode spec, TSNode decl) {
            String name = s.text(field(spec, "name"));
            String qn = pkg + "." + name;
            TSNode type = field(spec, "type");
            boolean iface = type != null && type.getType().equals("interface_type");
            TSNode anchor = named(decl).size() == 1 ? decl : spec;
            e.symbols.add(symbol(name, qn, iface ? SymbolType.INTERFACE : SymbolType.CLASS, pkg, anchor).visibility(visibility(name))
                    .contentHash(hash(normalise(s, spec, COMMENTS))).build());
            if (type == null) return;
            if (type.getType().equals("struct_type")) {
                for (TSNode f : descendants(type, Set.of("field_declaration"), Set.of("struct_type"))) {
                    TSNode ft = field(f, "type");
                    List<TSNode> names = named(f).stream().filter(n -> n.getType().equals("field_identifier")).toList();
                    if (names.isEmpty()) {
                        String emb = typeOf(s, ft, pkg, imports, index);
                        if (emb != null) e.dep(qn, emb, DependencyType.EXTENDS, f);
                        continue;
                    }
                    for (TSNode n : names) {
                        String fname = s.text(n);
                        TSNode tag = field(f, "tag");
                        e.symbols.add(symbol(fname, qn + "#" + fname, SymbolType.FIELD, qn, f).visibility(visibility(fname))
                                .returnType(ft == null ? null : collapse(s.text(ft)))
                                .annotations(tag == null ? List.of() : List.of(s.text(tag).replace("`", "")))
                                .contentHash(hash(normalise(s, f, COMMENTS))).build());
                        typeRefs(ft, qn + "#" + fname, DependencyType.FIELD_TYPE);
                    }
                }
            } else if (iface) {
                for (TSNode m : named(type)) {
                    if (m.getType().equals("method_elem")) {
                        String mname = s.text(field(m, "name"));
                        e.symbols.add(symbol(mname, qn + "#" + mname, SymbolType.METHOD, qn, m).visibility(visibility(mname)).isAbstract(true)
                                .parameters(collapse(s.text(field(m, "parameters"))).replaceAll("^\\(|\\)$", ""))
                                .returnType(field(m, "result") == null ? null : collapse(s.text(field(m, "result"))))
                                .contentHash(hash(normalise(s, m, COMMENTS))).build());
                        typeRefs(field(m, "parameters"), qn + "#" + mname, DependencyType.PARAMETER_TYPE);
                        typeRefs(field(m, "result"), qn + "#" + mname, DependencyType.RETURN_TYPE);
                    } else {
                        for (TSNode t : named(m)) { String et = typeOf(s, t, pkg, imports, index); if (et != null) e.dep(qn, et, DependencyType.EXTENDS, m); }
                    }
                }
            } else {
                typeRefs(type, qn, DependencyType.FIELD_TYPE);
            }
        }

        private void variables(TSNode d) {
            for (TSNode spec : descendants(d, Set.of("var_spec", "const_spec"), Set.of())) {
                for (TSNode n : named(spec)) {
                    if (!n.getType().equals("identifier")) continue;
                    String name = s.text(n);
                    if (name.equals("_")) continue;
                    String qn = pkg + "#" + name;
                    e.symbols.add(symbol(name, qn, SymbolType.FIELD, pkg, spec).visibility(visibility(name)).isStatic(true)
                            .isFinal(d.getType().equals("const_declaration"))
                            .returnType(field(spec, "type") == null ? null : collapse(s.text(field(spec, "type"))))
                            .contentHash(hash(normalise(s, spec, COMMENTS))).build());
                    typeRefs(field(spec, "type"), qn, DependencyType.FIELD_TYPE);
                    TSNode value = field(spec, "value");
                    if (value != null) refs(value, qn, null, new HashMap<>(), new HashSet<>());
                }
            }
        }

        private void function(TSNode d, String typeQn) {
            String name = s.text(field(d, "name"));
            String qn = typeQn == null ? pkg + "#" + name : typeQn + "#" + name;
            TSNode params = field(d, "parameters"), result = field(d, "result"), body = field(d, "body");
            List<String> annotations = new ArrayList<>();
            if (typeQn == null && s.path().endsWith("_test.go") && name.matches("(Test|Benchmark|Fuzz|Example)([A-Z_].*)?")) annotations.add("test");
            e.symbols.add(symbol(name, qn, typeQn == null ? SymbolType.FUNCTION : SymbolType.METHOD,
                    typeQn == null ? pkg : typeQn, d).visibility(visibility(name))
                    .parameters(params == null ? "" : collapse(s.text(params)).replaceAll("^\\(|\\)$", ""))
                    .returnType(result == null ? null : collapse(s.text(result))).annotations(annotations)
                    .annotationText(annotations.isEmpty() ? null : String.join("\n", annotations))
                    .contentHash(hash(normalise(s, d, COMMENTS))).complexity(complexity(s, body, DECISIONS, Set.of("func_literal"), LOGICAL)).build());
            Map<String, String> locals = new HashMap<>();
            Set<String> declared = new HashSet<>();
            TSNode receiver = field(d, "receiver");
            if (receiver != null) for (TSNode p : named(receiver)) {
                TSNode pn = field(p, "name");
                if (pn != null) { locals.put(s.text(pn), typeQn); declared.add(s.text(pn)); }
            }
            for (TSNode p : named(params)) {
                String t = typeOf(s, field(p, "type"), pkg, imports, index);
                for (TSNode pn : named(p)) if (pn.getType().equals("identifier")) {
                    declared.add(s.text(pn));
                    if (t != null) locals.put(s.text(pn), t);
                }
            }
            typeRefs(params, qn, DependencyType.PARAMETER_TYPE);
            typeRefs(result, qn, DependencyType.RETURN_TYPE);
            if (body != null) {
                for (TSNode n : descendants(body, Set.of("short_var_declaration", "var_spec", "range_clause", "const_spec"), Set.of()))
                    for (TSNode id : descendants(field(n, "left") != null ? field(n, "left") : n, Set.of("identifier"), Set.of("expression_list")))
                        declared.add(s.text(id));
                for (TSNode n : descendants(body, Set.of("short_var_declaration"), Set.of())) {
                    for (TSNode id : named(field(n, "left"))) if (id.getType().equals("identifier")) declared.add(s.text(id));
                }
                refs(body, qn, typeQn, locals, declared);
            }
        }

        private void typeRefs(TSNode n, String source, DependencyType dt) {
            if (n == null) return;
            Set<String> named = Set.of("type_identifier", "qualified_type");
            for (TSNode t : named.contains(n.getType()) ? List.of(n) : descendants(n, named, Set.of("qualified_type"))) {
                String r = typeOf(s, t, pkg, imports, index);
                if (r != null) e.dep(source, r, dt, t);
            }
        }

        // references -------------------------------------------------------------------------------------------

        private void refs(TSNode n, String source, String typeQn, Map<String, String> locals, Set<String> declared) {
            if (n == null) return;
            switch (n.getType()) {
                case "short_var_declaration" -> {
                    List<TSNode> left = named(field(n, "left")), right = named(field(n, "right"));
                    for (int i = 0; i < left.size(); i++) {
                        TSNode value = right.size() == left.size() ? right.get(i) : i == 0 && right.size() == 1 ? right.get(0) : null;
                        String t = valueType(value, locals);
                        if (t != null) locals.put(s.text(left.get(i)), t);
                    }
                }
                case "var_spec" -> {
                    String t = typeOf(s, field(n, "type"), pkg, imports, index);
                    TSNode value = field(n, "value");
                    if (t == null && value != null && named(value).size() == 1) t = valueType(named(value).get(0), locals);
                    if (t != null) for (TSNode id : named(n)) if (id.getType().equals("identifier")) locals.put(s.text(id), t);
                    typeRefs(field(n, "type"), source, DependencyType.FIELD_TYPE);
                }
                case "composite_literal" -> {
                    String t = typeOf(s, field(n, "type"), pkg, imports, index);
                    if (t != null) e.dep(source, t, DependencyType.CONSTRUCTOR_CALL, n);
                }
                case "call_expression" -> {
                    String target = callTarget(field(n, "function"), locals, declared);
                    if (target != null) e.dep(source, target, DependencyType.METHOD_CALL, n);
                    TSNode fn = field(n, "function");
                    if (fn != null && fn.getType().equals("selector_expression")) refs(field(fn, "operand"), source, typeQn, locals, declared);
                    else if (fn != null && !fn.getType().equals("identifier")) refs(fn, source, typeQn, locals, declared);
                    refs(field(n, "arguments"), source, typeQn, locals, declared);
                    return;
                }
                case "selector_expression" -> {
                    TSNode operand = field(n, "operand");
                    String f = s.text(field(n, "field"));
                    if (operand != null && operand.getType().equals("identifier") && !declared.contains(s.text(operand)) && imports.containsKey(s.text(operand))) {
                        String d = imports.get(s.text(operand));
                        if (!d.startsWith("go:") && index.vars.contains(index.pkg(d) + "#" + f)) e.dep(source, index.pkg(d) + "#" + f, DependencyType.FIELD_ACCESS, n);
                        return;
                    }
                    String owner = valueType(operand, locals);
                    String target = owner == null ? null : index.types.field(owner, f);
                    if (target != null) e.dep(source, target, DependencyType.FIELD_ACCESS, n);
                    refs(operand, source, typeQn, locals, declared);
                    return;
                }
                case "identifier" -> {
                    String name = s.text(n);
                    if (!declared.contains(name) && index.vars.contains(pkg + "#" + name)) e.dep(source, pkg + "#" + name, DependencyType.FIELD_ACCESS, n);
                    return;
                }
                case "interpreted_string_literal", "raw_string_literal" -> {
                    for (String t : EmbeddedSql.tables(s.text(n).replaceAll("^[\"`]|[\"`]$", ""))) e.dep(source, t, DependencyType.FIELD_ACCESS, n);
                    return;
                }
                default -> { }
            }
            for (TSNode c : named(n)) refs(c, source, typeQn, locals, declared);
        }

        private String callTarget(TSNode fn, Map<String, String> locals, Set<String> declared) {
            if (fn == null) return null;
            if (fn.getType().equals("identifier")) {
                String name = s.text(fn);
                if (declared.contains(name)) return null;
                String q = pkg + "#" + name;
                return index.types.hasFunction(q) ? q : null;
            }
            if (fn.getType().equals("generic_type") || fn.getType().equals("index_expression")) {
                TSNode inner = named(fn).isEmpty() ? null : named(fn).get(0);
                return inner != null && inner.getType().equals("identifier") ? callTarget(inner, locals, declared) : null;
            }
            if (!fn.getType().equals("selector_expression")) return null;
            TSNode operand = field(fn, "operand");
            String method = s.text(field(fn, "field"));
            if (operand != null && operand.getType().equals("identifier") && !declared.contains(s.text(operand)) && imports.containsKey(s.text(operand))) {
                String d = imports.get(s.text(operand));
                if (d.startsWith("go:")) return d + "#" + method;
                String q = index.pkg(d) + "#" + method;
                return index.types.hasFunction(q) ? q : null;
            }
            String owner = valueType(operand, locals);
            return owner == null ? null : index.types.method(owner, method);
        }

        /** Declared type of an expression, when it is certain. */
        private String valueType(TSNode v, Map<String, String> locals) {
            if (v == null) return null;
            switch (v.getType()) {
                case "identifier" -> { return locals.get(s.text(v)); }
                case "unary_expression", "parenthesized_expression" -> {
                    TSNode op = field(v, "operand");
                    if (op == null && !named(v).isEmpty()) op = named(v).get(0);
                    return valueType(op, locals);
                }
                case "composite_literal" -> { return typeOf(s, field(v, "type"), pkg, imports, index); }
                case "selector_expression" -> {
                    String owner = valueType(field(v, "operand"), locals);
                    return owner == null ? null : index.types.fieldType(owner, s.text(field(v, "field")));
                }
                case "call_expression" -> {
                    TSNode fn = field(v, "function");
                    if (fn == null) return null;
                    if (fn.getType().equals("identifier")) {
                        String name = s.text(fn);
                        if (name.equals("new")) {
                            TSNode arg = named(field(v, "arguments")).stream().findFirst().orElse(null);
                            return typeOf(s, arg, pkg, imports, index);
                        }
                        return index.funcReturns.get(pkg + "#" + name);
                    }
                    if (fn.getType().equals("selector_expression")) {
                        TSNode operand = field(fn, "operand");
                        String m = s.text(field(fn, "field"));
                        if (operand != null && operand.getType().equals("identifier") && !locals.containsKey(s.text(operand)) && imports.containsKey(s.text(operand))) {
                            String d = imports.get(s.text(operand));
                            return d.startsWith("go:") ? null : index.funcReturns.get(index.pkg(d) + "#" + m);
                        }
                        String owner = valueType(operand, locals);
                        return owner == null ? null : index.types.returnType(owner, m);
                    }
                    return null;
                }
                default -> { return null; }
            }
        }
    }
}

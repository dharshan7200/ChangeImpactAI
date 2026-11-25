package com.changeimpact.parser.language.ts;

import com.changeimpact.common.config.AppProperties;
import com.changeimpact.parser.model.*;
import org.springframework.stereotype.Component;
import org.treesitter.TSLanguage;
import org.treesitter.TSNode;
import org.treesitter.TreeSitterCSharp;

import java.nio.file.Path;
import java.util.*;

/**
 * C# (ASP.NET Core, class libraries, tests).
 *
 * <p>Model: types are named by namespace ({@code App.Models.User}; nested {@code App.Models.User.Address}); methods,
 * constructors, properties and fields are members {@code App.Models.User#Save}. Overloads share one symbol (members
 * are keyed by name). Partial classes merge into the first declaration. Files with top-level statements
 * ({@code Program.cs}) are also a {@code MODULE} ({@code src.Web.Program.cs}).
 *
 * <p>Type names resolve like the compiler does for the common cases: nested types, the enclosing namespaces, {@code using}
 * namespaces (including {@code global using} from any file), aliases and {@code using static}. Links: base types
 * ({@code EXTENDS} to a class, {@code IMPLEMENTS} to an interface), {@code new T()}, calls on {@code this}/{@code base},
 * on static types, and on receivers whose declared type is known (parameters, fields, properties, locals with an
 * explicit type or {@code var x = new T()} / a call with a declared return type); field and property access; parameter,
 * return, field types; attributes; {@code [Table("name")]} to its table; tables in SQL strings. Extension methods,
 * LINQ lambdas over unknown types, {@code dynamic} and reflection are not resolved.
 */
@Component
public class CSharpLanguageParser extends TreeSitterParser {

    private static final Set<String> COMMENTS = Set.of("comment");
    static final Set<String> TYPES = Set.of("class_declaration", "interface_declaration", "struct_declaration", "record_declaration",
            "record_struct_declaration", "enum_declaration");
    private static final Set<String> DECISIONS = Set.of("if_statement", "for_statement", "foreach_statement", "while_statement",
            "do_statement", "catch_clause", "conditional_expression", "switch_section", "switch_expression_arm");
    private static final Set<String> LOGICAL = Set.of("&&", "||", "??");
    private static final Set<String> TEST_ATTRIBUTES = Set.of("Fact", "Theory", "Test", "TestMethod", "TestCase", "TestCaseSource", "DataTestMethod");
    private static final Set<String> FIELD_PARENTS = Set.of("argument", "assignment_expression", "return_statement", "binary_expression",
            "equals_value_clause", "arrow_expression_clause", "conditional_expression", "interpolation", "variable_declarator",
            "prefix_unary_expression", "postfix_unary_expression", "element_access_expression", "initializer_expression");

    public CSharpLanguageParser(AppProperties appProperties) {
        super(appProperties, new TreeSitterCSharp());
    }

    @Override public String language() { return "csharp"; }
    @Override public Set<String> extensions() { return Set.of(".cs"); }
    @Override protected TSLanguage grammarFor(String relativePath) { return new TreeSitterCSharp(); }

    @Override
    protected boolean accepts(String relativePath) {
        String n = relativePath.toLowerCase(Locale.ROOT);
        return n.endsWith(".cs") && !n.endsWith(".designer.cs") && !n.endsWith(".g.cs") && !n.endsWith(".g.i.cs")
                && !n.endsWith("assemblyinfo.cs") && !n.contains("/migrations/") && !n.startsWith("migrations/");
    }

    static boolean isTestPath(String rel) {
        String p = "/" + rel.toLowerCase(Locale.ROOT);
        return p.contains(".tests/") || p.contains(".test/") || p.contains("/tests/") || p.contains("/test/")
                || p.endsWith("tests.cs") || p.endsWith("test.cs");
    }

    /** Names visible in a file: namespace, imported namespaces, aliases and static imports. */
    static final class Scope {
        String namespace = "";
        final List<String> usings = new ArrayList<>();
        final Map<String, String> aliases = new HashMap<>();
        final List<String> staticUsings = new ArrayList<>();

        Scope copy() {
            Scope s = new Scope();
            s.namespace = namespace;
            s.usings.addAll(usings);
            s.aliases.putAll(aliases);
            s.staticUsings.addAll(staticUsings);
            return s;
        }
    }

    interface TypeVisitor {
        void visit(TSNode type, String qn, String outer, Scope scope);
    }

    /** Visits every type declaration (nested ones too) with its qualified name and scope; global statements to {@code globals}. */
    static void walk(Source s, Scope global, TypeVisitor visitor, List<TSNode> globals) {
        walk(s, s.root(), global.copy(), null, visitor, globals);
    }

    private static void walk(Source s, TSNode container, Scope scope, String outer, TypeVisitor visitor, List<TSNode> globals) {
        for (TSNode n : named(container)) {
            switch (n.getType()) {
                case "using_directive" -> using(s, n, scope);
                case "file_scoped_namespace_declaration" -> scope.namespace = nsJoin(scope.namespace, s.text(field(n, "name")));
                case "namespace_declaration" -> {
                    Scope inner = scope.copy();
                    inner.namespace = nsJoin(scope.namespace, s.text(field(n, "name")));
                    walk(s, field(n, "body"), inner, null, visitor, globals);
                }
                case "global_statement" -> { if (globals != null) globals.add(n); }
                default -> {
                    if (TYPES.contains(n.getType()) && field(n, "name") != null) {
                        String name = s.text(field(n, "name"));
                        String qn = outer != null ? outer + "." + name : nsJoin(scope.namespace, name);
                        visitor.visit(n, qn, outer, scope);
                        TSNode body = field(n, "body");
                        if (body != null) walk(s, body, scope, qn, visitor, null);
                    }
                }
            }
        }
    }

    private static String nsJoin(String ns, String name) {
        return ns.isEmpty() ? name.replaceAll("\\s", "") : ns + "." + name.replaceAll("\\s", "");
    }

    static void using(Source s, TSNode n, Scope scope) {
        String text = collapse(s.text(n)).replaceAll(";$", "").trim();
        text = text.replaceFirst("^global\\s+", "").replaceFirst("^using\\s+", "");
        if (text.startsWith("static ")) {
            scope.staticUsings.add(text.substring(7).trim());
        } else if (text.contains("=")) {
            String[] parts = text.split("=", 2);
            scope.aliases.put(parts[0].trim(), parts[1].trim().replaceAll("<.*$", ""));
        } else {
            scope.usings.add(text.trim());
        }
    }

    static List<String> modifiers(Source s, TSNode n) {
        List<String> out = new ArrayList<>();
        for (TSNode m : named(n)) if (m.getType().equals("modifier")) out.add(s.text(m));
        return out;
    }

    // ---- type names ---------------------------------------------------------------------------------------------

    /** Resolution context: index, scope and the enclosing type. */
    record Ctx(TypeIndex index, Scope scope, String enclosing) {

        /** A type name as written → qualified name declared in the repository, or the written qualified name, or null. */
        String resolve(String raw) {
            if (raw == null || raw.isBlank()) return null;
            String name = raw.replaceAll("<.*$", "").replace("global::", "").replace("?", "").trim();
            if (name.isEmpty()) return null;
            int dot = name.indexOf('.');
            String first = dot < 0 ? name : name.substring(0, dot);
            String alias = scope.aliases.get(first);
            if (alias != null) {
                String q = dot < 0 ? alias : alias + name.substring(dot);
                return q;
            }
            for (String e = enclosing; e != null; e = e.contains(".") ? e.substring(0, e.lastIndexOf('.')) : null) {
                if (index.has(e + "." + name)) return e + "." + name;
                TypeIndex.TypeInfo t = index.get(e);
                if (t != null) for (String sup : t.supertypes) if (index.has(sup + "." + name)) return sup + "." + name;
            }
            for (String ns = scope.namespace; ; ns = ns.contains(".") ? ns.substring(0, ns.lastIndexOf('.')) : "") {
                String q = ns.isEmpty() ? name : ns + "." + name;
                if (index.has(q)) return q;
                if (ns.isEmpty()) break;
            }
            for (String u : scope.usings) if (index.has(u + "." + name)) return u + "." + name;
            return dot > 0 ? name : null; // a qualified name outside the repository is kept as written
        }

        /** The class whose static members are imported, for a simple member name. */
        String staticOwner(String member) {
            for (String u : scope.staticUsings) {
                String t = resolve(u);
                if (t != null && index.method(t, member) != null) return t;
            }
            return null;
        }
    }

    /** The single type named by a type node ({@code User?}, {@code IRepo<User>} → IRepo), or null. */
    static String typeOf(Source s, TSNode type, Ctx ctx) {
        if (type == null) return null;
        return switch (type.getType()) {
            case "identifier", "qualified_name" -> ctx.resolve(s.text(type));
            case "generic_name" -> ctx.resolve(s.text(named(type).get(0)));
            case "nullable_type" -> typeOf(s, named(type).isEmpty() ? null : named(type).get(0), ctx);
            default -> null;
        };
    }

    @Override
    protected Map<String, ParsedSourceFile> analyse(Path root, List<Source> sources) {
        TypeIndex index = new TypeIndex();
        Scope global = new Scope();
        for (Source s : sources) for (TSNode n : named(s.root())) {
            if (n.getType().equals("using_directive") && s.text(n).trim().startsWith("global ")) using(s, n, global);
        }
        for (Source s : sources) walk(s, global, (n, qn, outer, scope) -> declare(s, n, qn, index), null);
        for (Source s : sources) walk(s, global, (n, qn, outer, scope) -> resolveMembers(s, n, qn, new Ctx(index, scope, qn)), null);
        Map<String, ParsedSourceFile> out = new LinkedHashMap<>();
        for (Source s : sources) {
            try {
                out.put(s.path(), new Extractor(s, index, global).extract());
            } catch (Exception e) {
                org.slf4j.LoggerFactory.getLogger(getClass()).debug("C# file {} skipped: {}", s.path(), e.toString());
            }
        }
        return out;
    }

    private static void declare(Source s, TSNode n, String qn, TypeIndex index) {
        TypeIndex.TypeInfo t = index.declare(qn, n.getType());
        boolean isInterface = n.getType().equals("interface_declaration");
        TSNode primary = firstNamed(n, "parameter_list");
        if (primary != null) for (TSNode p : named(primary)) if (field(p, "name") != null) t.fields.add(s.text(field(p, "name")));
        for (TSNode m : named(field(n, "body"))) {
            switch (m.getType()) {
                case "method_declaration" -> {
                    String name = s.text(field(m, "name"));
                    t.methods.add(name);
                    if (modifiers(s, m).contains("static")) t.staticMethods.add(name);
                }
                case "field_declaration", "event_field_declaration" -> {
                    for (TSNode d : descendants(m, Set.of("variable_declarator"), Set.of())) t.fields.add(s.text(field(d, "name")));
                }
                case "property_declaration", "event_declaration" -> t.fields.add(s.text(field(m, "name")));
                case "enum_member_declaration" -> t.fields.add(s.text(field(m, "name")));
                default -> { }
            }
        }
        if (isInterface) t.methods.addAll(t.staticMethods);
    }

    private static void resolveMembers(Source s, TSNode n, String qn, Ctx ctx) {
        TypeIndex.TypeInfo t = ctx.index().get(qn);
        if (t == null) return;
        TSNode bases = firstNamed(n, "base_list");
        for (TSNode b : named(bases)) {
            TSNode type = b.getType().equals("primary_constructor_base_type") ? named(b).get(0) : b;
            String r = typeOf(s, type, new Ctx(ctx.index(), ctx.scope(), outerOf(qn, ctx.index())));
            if (r != null && !t.supertypes.contains(r)) t.supertypes.add(r);
        }
        TSNode primary = firstNamed(n, "parameter_list");
        if (primary != null) for (TSNode p : named(primary)) {
            String type = typeOf(s, field(p, "type"), ctx);
            if (type != null && field(p, "name") != null) t.fieldTypes.put(s.text(field(p, "name")), type);
        }
        for (TSNode m : named(field(n, "body"))) {
            switch (m.getType()) {
                case "method_declaration" -> {
                    String rt = typeOf(s, field(m, "returns"), ctx);
                    if (rt == null) rt = taskResult(s, field(m, "returns"), ctx);
                    if (rt != null) t.returnTypes.put(s.text(field(m, "name")), rt);
                }
                case "field_declaration" -> {
                    TSNode vd = firstNamed(m, "variable_declaration");
                    String type = vd == null ? null : typeOf(s, field(vd, "type"), ctx);
                    if (type != null) for (TSNode d : descendants(m, Set.of("variable_declarator"), Set.of())) t.fieldTypes.put(s.text(field(d, "name")), type);
                }
                case "property_declaration" -> {
                    String type = typeOf(s, field(m, "type"), ctx);
                    if (type != null) t.fieldTypes.put(s.text(field(m, "name")), type);
                }
                default -> { }
            }
        }
    }

    /** {@code Task<User>} / {@code ValueTask<User>} → User (the awaited type). */
    static String taskResult(Source s, TSNode type, Ctx ctx) {
        if (type == null || !type.getType().equals("generic_name")) return null;
        String base = s.text(named(type).get(0));
        if (!base.equals("Task") && !base.equals("ValueTask")) return null;
        TSNode args = firstNamed(type, "type_argument_list");
        List<TSNode> a = named(args);
        return a.size() == 1 ? typeOf(s, a.get(0), ctx) : null;
    }

    private static String outerOf(String qn, TypeIndex index) {
        String o = qn.contains(".") ? qn.substring(0, qn.lastIndexOf('.')) : null;
        return o != null && index.has(o) ? o : null;
    }

    // ---- pass 2 -------------------------------------------------------------------------------------------------

    static final class Extractor {
        private final Source s;
        private final TypeIndex index;
        private final Scope global;
        private final Emitter e = new Emitter();
        private Ctx ctx;
        private boolean test;

        Extractor(Source s, TypeIndex index, Scope global) {
            this.s = s;
            this.index = index;
            this.global = global;
        }

        ParsedSourceFile extract() {
            List<TSNode> globals = new ArrayList<>();
            String[] firstNs = {null};
            test = isTestPath(s.path());
            walk(s, global, (n, qn, outer, scope) -> {
                if (firstNs[0] == null) firstNs[0] = scope.namespace;
                ctx = new Ctx(index, scope, qn);
                type(n, qn, outer);
            }, globals);
            if (!globals.isEmpty()) {
                String module = pathName(s.path(), true);
                Scope scope = global.copy();
                for (TSNode n : named(s.root())) if (n.getType().equals("using_directive")) using(s, n, scope);
                ctx = new Ctx(index, scope, null);
                StringBuilder text = new StringBuilder();
                globals.forEach(g -> text.append(normalise(s, g, COMMENTS)).append('\n'));
                e.symbols.add(0, symbol(s.path().substring(s.path().lastIndexOf('/') + 1), module, SymbolType.MODULE, null, s.root())
                        .startLine(1).endLine(Math.max(1, s.lines())).contentHash(hash(text.toString())).build());
                Map<String, String> locals = new HashMap<>();
                for (TSNode g : globals) refs(g, module, null, locals);
            }
            return e.file(s, firstNs[0] == null || firstNs[0].isEmpty() ? pathName(directory(s.path()), false) : firstNs[0], test, "csharp");
        }

        private List<String> attributes(TSNode n) {
            List<String> out = new ArrayList<>();
            for (TSNode list : named(n)) if (list.getType().equals("attribute_list"))
                for (TSNode a : descendants(list, Set.of("attribute"), Set.of())) out.add(collapse(s.text(a)));
            return out;
        }

        private static String visibility(List<String> mods, String dflt) {
            for (String v : List.of("public", "protected", "internal", "private")) if (mods.contains(v)) return v;
            return dflt;
        }

        private void type(TSNode n, String qn, String outer) {
            String name = s.text(field(n, "name"));
            List<String> mods = modifiers(s, n);
            List<String> attrs = attributes(n);
            SymbolType st = switch (n.getType()) {
                case "interface_declaration" -> SymbolType.INTERFACE;
                case "enum_declaration" -> SymbolType.ENUM;
                case "record_declaration", "record_struct_declaration" -> SymbolType.RECORD;
                default -> SymbolType.CLASS;
            };
            TSNode body = field(n, "body");
            int end = body == null ? n.getEndByte() : body.getStartByte();
            e.symbols.add(symbol(name, qn, st, outer, n).visibility(visibility(mods, outer == null ? "internal" : "private"))
                    .isStatic(mods.contains("static")).isAbstract(mods.contains("abstract")).isFinal(mods.contains("sealed"))
                    .annotations(attrs).annotationText(attrs.isEmpty() ? null : String.join("\n", attrs))
                    .contentHash(hash(collapse(new String(s.bytes(), n.getStartByte(), end - n.getStartByte(), java.nio.charset.StandardCharsets.UTF_8))))
                    .build());
            attributeDeps(n, qn);
            TSNode bases = firstNamed(n, "base_list");
            for (TSNode b : named(bases)) {
                TSNode type = b.getType().equals("primary_constructor_base_type") ? named(b).get(0) : b;
                String r = typeOf(s, type, new Ctx(index, ctx.scope(), outer));
                if (r == null) continue;
                TypeIndex.TypeInfo info = index.get(r);
                boolean iface = info != null ? info.kind.equals("interface_declaration") : TypeIndex.simple(r).matches("I[A-Z].*");
                e.dep(qn, r, iface && st != SymbolType.INTERFACE ? DependencyType.IMPLEMENTS : DependencyType.EXTENDS, b);
                if (b.getType().equals("primary_constructor_base_type")) refs(b, qn, qn, new HashMap<>());
            }
            TSNode primary = firstNamed(n, "parameter_list");
            if (primary != null) for (TSNode p : named(primary)) {
                if (field(p, "name") == null) continue;
                String f = s.text(field(p, "name"));
                if (st == SymbolType.RECORD) e.symbols.add(symbol(f, qn + "#" + f, SymbolType.FIELD, qn, p)
                        .returnType(field(p, "type") == null ? null : collapse(s.text(field(p, "type")))).contentHash(hash(normalise(s, p, COMMENTS))).build());
                typeRefs(field(p, "type"), st == SymbolType.RECORD ? qn + "#" + f : qn, DependencyType.FIELD_TYPE);
            }
            for (TSNode m : named(body)) member(m, qn);
        }

        private void member(TSNode m, String qn) {
            List<String> mods = modifiers(s, m);
            switch (m.getType()) {
                case "method_declaration", "constructor_declaration", "destructor_declaration", "operator_declaration", "conversion_operator_declaration" -> {
                    String name = field(m, "name") == null ? m.getType().replace("_declaration", "") : s.text(field(m, "name"));
                    String mqn = qn + "#" + name;
                    List<String> attrs = attributes(m);
                    if (attrs.stream().anyMatch(a -> TEST_ATTRIBUTES.contains(a.replaceAll("\\(.*$", "")))) test = true;
                    TSNode params = field(m, "parameters"), body = field(m, "body");
                    if (body == null) body = firstNamed(m, "arrow_expression_clause");
                    TSNode returns = field(m, "returns");
                    if (returns == null) returns = field(m, "type");
                    e.symbols.add(symbol(name, mqn, m.getType().equals("constructor_declaration") ? SymbolType.CONSTRUCTOR : SymbolType.METHOD, qn, m)
                            .parameters(params == null ? "" : collapse(s.text(params)).replaceAll("^\\(|\\)$", ""))
                            .returnType(returns == null ? null : collapse(s.text(returns)))
                            .visibility(visibility(mods, index.get(qn) != null && index.get(qn).kind.equals("interface_declaration") ? "public" : "private"))
                            .isStatic(mods.contains("static")).isAbstract(mods.contains("abstract")).isFinal(mods.contains("sealed"))
                            .annotations(attrs).annotationText(attrs.isEmpty() ? null : String.join("\n", attrs))
                            .contentHash(hash(normalise(s, m, COMMENTS)))
                            .complexity(complexity(s, body, DECISIONS, Set.of("local_function_statement"), LOGICAL)).build());
                    attributeDeps(m, mqn);
                    Map<String, String> locals = new HashMap<>();
                    parameters(params, mqn, locals);
                    typeRefs(returns, mqn, DependencyType.RETURN_TYPE);
                    TSNode init = firstNamed(m, "constructor_initializer");
                    if (init != null) {
                        String target = s.text(init).trim().startsWith(":base") || s.text(init).contains("base") ? index.superclass(qn) : qn;
                        if (target != null) e.dep(mqn, target, DependencyType.CONSTRUCTOR_CALL, init);
                        refs(init, mqn, qn, locals);
                    }
                    if (body != null) refs(body, mqn, qn, locals);
                }
                case "field_declaration", "event_field_declaration" -> {
                    TSNode vd = firstNamed(m, "variable_declaration");
                    if (vd == null) return;
                    for (TSNode d : named(vd)) {
                        if (!d.getType().equals("variable_declarator")) continue;
                        String f = s.text(field(d, "name"));
                        e.symbols.add(symbol(f, qn + "#" + f, SymbolType.FIELD, qn, d).visibility(visibility(mods, "private"))
                                .isStatic(mods.contains("static") || mods.contains("const")).isFinal(mods.contains("readonly") || mods.contains("const"))
                                .returnType(collapse(s.text(field(vd, "type")))).annotations(attributes(m))
                                .contentHash(hash(normalise(s, m, COMMENTS))).build());
                        typeRefs(field(vd, "type"), qn + "#" + f, DependencyType.FIELD_TYPE);
                        refs(d, qn + "#" + f, qn, new HashMap<>());
                    }
                }
                case "property_declaration", "event_declaration", "indexer_declaration" -> {
                    String f = field(m, "name") == null ? "this[]" : s.text(field(m, "name"));
                    List<String> attrs = attributes(m);
                    TSNode accessors = field(m, "accessors");
                    boolean hasBody = accessors != null && !descendants(accessors, Set.of("block", "arrow_expression_clause"), Set.of()).isEmpty()
                            || firstNamed(m, "arrow_expression_clause") != null;
                    e.symbols.add(symbol(f, qn + "#" + f, SymbolType.FIELD, qn, m).visibility(visibility(mods, "private"))
                            .isStatic(mods.contains("static")).isAbstract(mods.contains("abstract")).returnType(collapse(s.text(field(m, "type"))))
                            .annotations(attrs).annotationText(attrs.isEmpty() ? null : String.join("\n", attrs))
                            .contentHash(hash(normalise(s, m, COMMENTS)))
                            .complexity(hasBody ? complexity(s, m, DECISIONS, Set.of(), LOGICAL) : null).build());
                    typeRefs(field(m, "type"), qn + "#" + f, DependencyType.FIELD_TYPE);
                    for (TSNode c : named(m)) if (!c.getType().equals("attribute_list")) refs(c, qn + "#" + f, qn, new HashMap<>());
                }
                case "enum_member_declaration" -> {
                    String f = s.text(field(m, "name"));
                    e.symbols.add(symbol(f, qn + "#" + f, SymbolType.FIELD, qn, m).isStatic(true).isFinal(true)
                            .contentHash(hash(normalise(s, m, COMMENTS))).build());
                }
                default -> { }
            }
        }

        /** Attribute classes ({@code [Authorize]} → AuthorizeAttribute) and {@code [Table("users")]} → db.users. */
        private void attributeDeps(TSNode n, String source) {
            for (TSNode list : named(n)) {
                if (!list.getType().equals("attribute_list")) continue;
                for (TSNode a : descendants(list, Set.of("attribute"), Set.of())) {
                    TSNode name = field(a, "name");
                    if (name == null) continue;
                    String raw = s.text(name);
                    String r = ctx.resolve(raw.endsWith("Attribute") ? raw : raw + "Attribute");
                    if (r == null) r = ctx.resolve(raw);
                    if (r != null) e.dep(source, r, DependencyType.ANNOTATION, a);
                    if (raw.equals("Table") || raw.endsWith(".Table")) {
                        TSNode str = descendants(a, Set.of("string_literal"), Set.of()).stream().findFirst().orElse(null);
                        if (str != null) e.dep(source, EmbeddedSql.name(s.text(str).replaceAll("^\"|\"$", "")), DependencyType.ANNOTATION, a);
                    }
                }
            }
        }

        private void parameters(TSNode params, String source, Map<String, String> locals) {
            for (TSNode p : named(params)) {
                TSNode type = field(p, "type"), name = field(p, "name");
                if (type == null || name == null) continue;
                typeRefs(type, source, DependencyType.PARAMETER_TYPE);
                String t = typeOf(s, type, ctx);
                if (t != null) locals.put(s.text(name), t);
            }
        }

        /** Every repository type named inside a type ({@code Task<List<User>>} → User). */
        private void typeRefs(TSNode type, String source, DependencyType dt) {
            if (type == null) return;
            if (type.getType().equals("identifier") || type.getType().equals("qualified_name")) {
                String r = ctx.resolve(s.text(type));
                if (r != null) e.dep(source, r, dt, type);
                return;
            }
            if (type.getType().equals("generic_name")) {
                String r = ctx.resolve(s.text(named(type).get(0)));
                if (r != null) e.dep(source, r, dt, type);
            }
            for (TSNode c : named(type)) if (!type.getType().equals("generic_name") || !c.equals(named(type).get(0))) typeRefs(c, source, dt);
        }

        // references -------------------------------------------------------------------------------------------

        private void refs(TSNode n, String source, String classQn, Map<String, String> locals) {
            if (n == null) return;
            switch (n.getType()) {
                case "local_function_statement", "class_declaration", "struct_declaration", "record_declaration", "interface_declaration" -> { return; }
                case "variable_declaration" -> {
                    TSNode type = field(n, "type");
                    for (TSNode d : named(n)) {
                        if (!d.getType().equals("variable_declarator")) continue;
                        String t = type == null || type.getType().equals("implicit_type") ? valueType(lastNamed(d), classQn, locals) : typeOf(s, type, ctx);
                        if (t != null) locals.put(s.text(field(d, "name")), t);
                    }
                    if (type != null && !type.getType().equals("implicit_type")) typeRefs(type, source, DependencyType.FIELD_TYPE);
                }
                case "foreach_statement" -> {
                    TSNode type = field(n, "type"), left = field(n, "left");
                    String t = typeOf(s, type, ctx);
                    if (t != null && left != null) locals.put(s.text(left), t);
                }
                case "declaration_pattern" -> {
                    String t = typeOf(s, field(n, "type"), ctx);
                    if (t != null && field(n, "name") != null) locals.put(s.text(field(n, "name")), t);
                }
                case "object_creation_expression" -> {
                    String t = typeOf(s, field(n, "type"), ctx);
                    if (t != null) e.dep(source, t, DependencyType.CONSTRUCTOR_CALL, n);
                }
                case "invocation_expression" -> {
                    String target = callTarget(field(n, "function"), classQn, locals, source);
                    if (target != null) e.dep(source, target, DependencyType.METHOD_CALL, n);
                    for (TSNode c : named(n)) {
                        if (c.equals(field(n, "function")) && c.getType().equals("member_access_expression")) refs(field(c, "expression"), source, classQn, locals);
                        else if (!c.equals(field(n, "function"))) refs(c, source, classQn, locals);
                    }
                    return;
                }
                case "member_access_expression" -> {
                    String owner = receiverType(field(n, "expression"), classQn, locals, source, n);
                    if (owner != null) {
                        String target = index.field(owner, s.text(field(n, "name")));
                        if (target != null) e.dep(source, target, DependencyType.FIELD_ACCESS, n);
                    }
                    refs(field(n, "expression"), source, classQn, locals);
                    return;
                }
                case "identifier" -> {
                    TSNode parent = n.getParent();
                    String name = s.text(n);
                    if (classQn != null && parent != null && FIELD_PARENTS.contains(parent.getType()) && !locals.containsKey(name)
                            && !(parent.getType().equals("variable_declarator") && n.equals(field(parent, "name")))) {
                        String target = index.field(classQn, name);
                        if (target != null) e.dep(source, target, DependencyType.FIELD_ACCESS, n);
                    }
                    return;
                }
                case "string_literal", "verbatim_string_literal", "raw_string_literal", "interpolated_string_expression" -> {
                    for (String t : EmbeddedSql.tables(s.text(n).replaceAll("^[$@]*\"+|\"+$", ""))) e.dep(source, t, DependencyType.FIELD_ACCESS, n);
                    return;
                }
                default -> { }
            }
            for (TSNode c : named(n)) refs(c, source, classQn, locals);
        }

        private static TSNode lastNamed(TSNode n) {
            List<TSNode> l = named(n);
            return l.isEmpty() ? null : l.get(l.size() - 1);
        }

        private String callTarget(TSNode fn, String classQn, Map<String, String> locals, String source) {
            if (fn == null) return null;
            switch (fn.getType()) {
                case "identifier", "generic_name" -> {
                    String name = fn.getType().equals("generic_name") ? s.text(named(fn).get(0)) : s.text(fn);
                    if (locals.containsKey(name)) return null; // delegate variable
                    String own = classQn == null ? null : index.method(classQn, name);
                    if (own != null) return own;
                    for (String o = outerType(classQn); o != null; o = outerType(o)) {
                        String m = index.method(o, name);
                        if (m != null) return m;
                    }
                    String st = ctx.staticOwner(name);
                    return st == null ? null : index.method(st, name);
                }
                case "member_access_expression" -> {
                    TSNode nameNode = field(fn, "name");
                    String name = nameNode == null ? null : nameNode.getType().equals("generic_name") ? s.text(named(nameNode).get(0)) : s.text(nameNode);
                    String owner = receiverType(field(fn, "expression"), classQn, locals, source, fn);
                    if (owner == null || name == null) return null;
                    if (!index.has(owner)) return owner.contains(".") && staticReceiver(fn) ? owner + "#" + name : null;
                    return index.method(owner, name);
                }
                default -> {
                    return null;
                }
            }
        }

        private boolean staticReceiver(TSNode memberAccess) {
            TSNode ex = field(memberAccess, "expression");
            return ex != null && (ex.getType().equals("identifier") || ex.getType().equals("qualified_name")) && ctx.resolve(s.text(ex)) != null;
        }

        private String outerType(String qn) {
            return qn == null ? null : outerOf(qn, index);
        }

        /** Type of the receiver of a member access; {@code this}/{@code base} when the expression is absent or a keyword. */
        private String receiverType(TSNode ex, String classQn, Map<String, String> locals, String source, TSNode access) {
            if (ex == null) {
                String t = s.text(access);
                if (t.startsWith("base.") || t.startsWith("base?.")) return classQn == null ? null : index.superclass(classQn);
                return classQn;
            }
            switch (ex.getType()) {
                case "this", "this_expression" -> { return classQn; }
                case "base", "base_expression" -> { return classQn == null ? null : index.superclass(classQn); }
                case "identifier" -> {
                    String name = s.text(ex);
                    if (locals.containsKey(name)) return locals.get(name);
                    if (classQn != null) {
                        String ft = index.fieldType(classQn, name);
                        if (ft != null) {
                            String target = index.field(classQn, name);
                            if (target != null) e.dep(source, target, DependencyType.FIELD_ACCESS, ex);
                            return ft;
                        }
                        if (index.field(classQn, name) != null) return null;
                    }
                    return ctx.resolve(name); // static access on a type
                }
                case "qualified_name", "generic_name" -> { return typeOf(s, ex, ctx); }
                case "member_access_expression" -> {
                    TSNode inner = field(ex, "expression");
                    if (inner != null && (inner.getType().equals("identifier") || inner.getType().equals("member_access_expression"))) {
                        String q = ctx.resolve(s.text(ex)); // App.Models.User written in full
                        if (q != null && index.has(q)) return q;
                    }
                    String owner = receiverType(inner, classQn, locals, source, ex);
                    return owner == null || !index.has(owner) ? null : index.fieldType(owner, s.text(field(ex, "name")));
                }
                case "invocation_expression" -> {
                    TSNode fn = field(ex, "function");
                    if (fn == null) return null;
                    if (fn.getType().equals("member_access_expression")) {
                        String owner = receiverType(field(fn, "expression"), classQn, locals, source, fn);
                        return owner == null ? null : index.returnType(owner, s.text(field(fn, "name")));
                    }
                    if (fn.getType().equals("identifier") && classQn != null) return index.returnType(classQn, s.text(fn));
                    return null;
                }
                case "await_expression", "parenthesized_expression" -> {
                    return named(ex).isEmpty() ? null : receiverType(named(ex).get(0), classQn, locals, source, ex);
                }
                case "object_creation_expression" -> { return typeOf(s, field(ex, "type"), ctx); }
                default -> { return null; }
            }
        }

        private String valueType(TSNode v, String classQn, Map<String, String> locals) {
            if (v == null) return null;
            if (v.getType().equals("object_creation_expression")) return typeOf(s, field(v, "type"), ctx);
            if (v.getType().equals("await_expression") && !named(v).isEmpty()) return valueType(named(v).get(0), classQn, locals);
            if (v.getType().equals("invocation_expression") || v.getType().equals("member_access_expression") || v.getType().equals("identifier")) {
                String t = receiverTypeQuiet(v, classQn, locals);
                return index.has(t) ? t : null;
            }
            return null;
        }

        /** Receiver type without emitting field accesses (they are emitted when the initializer itself is visited). */
        private String receiverTypeQuiet(TSNode v, String classQn, Map<String, String> locals) {
            int before = e.deps.size();
            String t = receiverType(v, classQn, locals, "", v);
            while (e.deps.size() > before) e.deps.remove(e.deps.size() - 1);
            return t;
        }
    }
}

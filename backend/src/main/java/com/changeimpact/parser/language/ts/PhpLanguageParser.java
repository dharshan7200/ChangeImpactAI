package com.changeimpact.parser.language.ts;

import com.changeimpact.common.config.AppProperties;
import com.changeimpact.parser.model.*;
import org.springframework.stereotype.Component;
import org.treesitter.TSLanguage;
import org.treesitter.TSNode;
import org.treesitter.TreeSitterPhp;

import java.nio.file.Path;
import java.util.*;
import java.util.function.BiConsumer;

/**
 * PHP (including Laravel/Symfony code; Blade templates are not parsed).
 *
 * <p>Model: classes, interfaces, traits and enums are named by namespace ({@code App\Models\User} →
 * {@code App.Models.User}), methods {@code App.Models.User#save}, properties and constants are fields
 * ({@code ...User#email}), functions {@code App.Support#helper}. Every file is also a {@code MODULE}
 * ({@code routes/web.php} → {@code routes.web.php}) holding its top-level statements, and imports the classes it
 * {@code use}s.
 *
 * <p>Links: {@code extends}/{@code implements}/trait {@code use}; {@code new X}; {@code X::m()}, {@code self::},
 * {@code static::}, {@code parent::}; {@code $this->m()}, {@code $this->field->m()} and {@code $var->m()} where the type
 * of the receiver is declared (typed parameter, typed or promoted property, {@code $x = new X}, declared return type);
 * Laravel-style callables {@code [X::class, 'm']}; parameter/return/property types; tables in SQL strings. Calls on
 * receivers of unknown type and magic methods are not linked.
 */
@Component
public class PhpLanguageParser extends TreeSitterParser {

    private static final Set<String> COMMENTS = Set.of("comment");
    private static final Set<String> TYPES = Set.of("class_declaration", "interface_declaration", "trait_declaration", "enum_declaration");
    private static final Set<String> DECISIONS = Set.of("if_statement", "else_if_clause", "for_statement", "foreach_statement",
            "while_statement", "do_statement", "catch_clause", "conditional_expression", "case_statement", "match_conditional_expression");
    private static final Set<String> LOGICAL = Set.of("&&", "||", "and", "or", "??");
    private static final Set<String> CALLS = Set.of("member_call_expression", "nullsafe_member_call_expression");

    public PhpLanguageParser(AppProperties appProperties) {
        super(appProperties, new TreeSitterPhp());
    }

    @Override public String language() { return "php"; }
    @Override public Set<String> extensions() { return Set.of(".php"); }
    @Override protected TSLanguage grammarFor(String relativePath) { return new TreeSitterPhp(); }

    @Override
    protected boolean accepts(String relativePath) {
        String n = relativePath.toLowerCase(Locale.ROOT);
        return n.endsWith(".php") && !n.endsWith(".blade.php");
    }

    public static boolean isTest(String rel) {
        String p = "/" + rel;
        return p.contains("/tests/") || p.contains("/Tests/") || p.endsWith("Test.php");
    }

    static String norm(String phpName) {
        String n = phpName.strip();
        if (n.startsWith("\\")) n = n.substring(1);
        return n.replace('\\', '.');
    }

    /** Namespace and imports in effect at a statement. */
    static final class Scope {
        String namespace = "";
        final Map<String, String> uses = new HashMap<>();          // alias (lower case) → class qn
        final Map<String, String> useFunctions = new HashMap<>();  // alias → function qn
    }

    /** Walks the top-level statements of a file with their namespace scope (both namespace syntaxes). */
    static void walk(Source s, BiConsumer<TSNode, Scope> visitor) {
        Scope scope = new Scope();
        walk(s, s.root(), scope, visitor);
    }

    private static void walk(Source s, TSNode container, Scope scope, BiConsumer<TSNode, Scope> visitor) {
        for (TSNode stmt : named(container)) {
            switch (stmt.getType()) {
                case "namespace_definition" -> {
                    TSNode name = field(stmt, "name");
                    TSNode body = field(stmt, "body");
                    if (body != null) {
                        Scope inner = new Scope();
                        inner.namespace = name == null ? "" : norm(s.text(name));
                        walk(s, body, inner, visitor);
                    } else {
                        scope.namespace = name == null ? "" : norm(s.text(name));
                        scope.uses.clear();
                        scope.useFunctions.clear();
                    }
                }
                case "namespace_use_declaration" -> {
                    uses(s, stmt, scope);
                    visitor.accept(stmt, scope);
                }
                default -> visitor.accept(stmt, scope);
            }
        }
    }

    private static void uses(Source s, TSNode decl, Scope scope) {
        boolean function = hasToken(decl, "function"), constant = hasToken(decl, "const");
        if (constant) return;
        TSNode prefixNode = firstNamed(decl, "namespace_name");
        String prefix = prefixNode == null ? "" : norm(s.text(prefixNode)) + ".";
        List<TSNode> clauses = new ArrayList<>(descendants(decl, Set.of("namespace_use_clause"), Set.of()));
        for (TSNode c : clauses) {
            TSNode target = firstNamed(c, "qualified_name", "name");
            if (target == null) continue;
            String qn = prefix + norm(s.text(target));
            TSNode alias = field(c, "alias");
            String a = alias != null ? s.text(alias) : qn.substring(qn.lastIndexOf('.') + 1);
            boolean fn = function || hasToken(c, "function");
            if (fn) scope.useFunctions.put(a, qn.contains(".") ? qn.substring(0, qn.lastIndexOf('.')) + "#" + qn.substring(qn.lastIndexOf('.') + 1) : qn);
            else scope.uses.put(a.toLowerCase(Locale.ROOT), qn);
        }
    }

    static boolean hasToken(TSNode n, String token) {
        for (int i = 0; i < n.getChildCount(); i++) if (n.getChild(i).getType().equals(token)) return true;
        return false;
    }

    /** Class name as written → qualified name (resolved through {@code use}; may be outside the repository). */
    static String resolveClass(String raw, Scope scope, TypeIndex index) {
        if (raw == null || raw.isBlank()) return null;
        String r = raw.strip();
        if (r.startsWith("\\")) return norm(r);
        String n = norm(r);
        int dot = n.indexOf('.');
        String first = dot < 0 ? n : n.substring(0, dot);
        String imported = scope.uses.get(first.toLowerCase(Locale.ROOT));
        if (imported != null) return dot < 0 ? imported : imported + n.substring(dot);
        String local = scope.namespace.isEmpty() ? n : scope.namespace + "." + n;
        if (!index.has(local) && index.has(n)) return n;
        return local;
    }

    private static String functionQn(String ns, String module, String name) {
        return (ns.isEmpty() ? module : ns) + "#" + name;
    }

    @Override
    protected Map<String, ParsedSourceFile> analyse(Path root, List<Source> sources) {
        TypeIndex index = new TypeIndex();
        for (Source s : sources) declare(s, index);
        for (Source s : sources) resolveSupertypes(s, index);
        Map<String, ParsedSourceFile> out = new LinkedHashMap<>();
        for (Source s : sources) {
            try {
                out.put(s.path(), new Extractor(s, index).extract());
            } catch (Exception e) {
                org.slf4j.LoggerFactory.getLogger(getClass()).debug("PHP file {} skipped: {}", s.path(), e.toString());
            }
        }
        return out;
    }

    // ---- pass 1 -------------------------------------------------------------------------------------------------

    private static void declare(Source s, TypeIndex index) {
        String module = pathName(s.path(), true);
        walk(s, (stmt, scope) -> {
            if (TYPES.contains(stmt.getType())) {
                String name = s.text(field(stmt, "name"));
                if (name.isEmpty()) return;
                String qn = scope.namespace.isEmpty() ? name : scope.namespace + "." + name;
                TypeIndex.TypeInfo t = index.declare(qn, stmt.getType());
                for (TSNode m : members(stmt)) {
                    switch (m.getType()) {
                        case "method_declaration" -> {
                            String mn = s.text(field(m, "name"));
                            t.methods.add(mn);
                            if (firstNamed(m, "static_modifier") != null) t.staticMethods.add(mn);
                            for (TSNode p : named(field(m, "parameters"))) {
                                if (p.getType().equals("property_promotion_parameter")) {
                                    String f = s.text(field(p, "name")).replace("$", "");
                                    t.fields.add(f);
                                }
                            }
                        }
                        case "property_declaration" -> {
                            for (TSNode el : descendants(m, Set.of("property_element"), Set.of()))
                                t.fields.add(s.text(field(el, "name")).replace("$", ""));
                        }
                        case "const_declaration" -> {
                            for (TSNode el : descendants(m, Set.of("const_element"), Set.of())) {
                                TSNode nm = firstNamed(el, "name");
                                if (nm != null) t.fields.add(s.text(nm));
                            }
                        }
                        case "enum_case" -> t.fields.add(s.text(field(m, "name")));
                        default -> { }
                    }
                }
            } else if (stmt.getType().equals("function_definition")) {
                String name = s.text(field(stmt, "name"));
                if (!name.isEmpty()) index.function(functionQn(scope.namespace, module, name), name);
            }
        });
    }

    /** Supertypes, field types and return types need every class declared first. */
    private static void resolveSupertypes(Source s, TypeIndex index) {
        walk(s, (stmt, scope) -> {
            if (!TYPES.contains(stmt.getType())) return;
            String name = s.text(field(stmt, "name"));
            String qn = scope.namespace.isEmpty() ? name : scope.namespace + "." + name;
            TypeIndex.TypeInfo t = index.get(qn);
            if (t == null) return;
            for (TSNode h : named(stmt)) {
                if (h.getType().equals("base_clause") || h.getType().equals("class_interface_clause"))
                    for (TSNode n : named(h)) t.supertypes.add(resolveClass(s.text(n), scope, index));
            }
            for (TSNode m : members(stmt)) {
                if (m.getType().equals("use_declaration")) for (TSNode n : named(m)) {
                    if (n.getType().equals("name") || n.getType().equals("qualified_name")) t.supertypes.add(resolveClass(s.text(n), scope, index));
                }
                if (m.getType().equals("property_declaration")) {
                    String type = typeName(s, field(m, "type"), scope, index, qn);
                    if (type != null) for (TSNode el : descendants(m, Set.of("property_element"), Set.of()))
                        t.fieldTypes.put(s.text(field(el, "name")).replace("$", ""), type);
                }
                if (m.getType().equals("method_declaration")) {
                    String mn = s.text(field(m, "name"));
                    String ret = typeName(s, field(m, "return_type"), scope, index, qn);
                    if (ret != null) t.returnTypes.put(mn, ret);
                    for (TSNode p : named(field(m, "parameters"))) {
                        if (!p.getType().equals("property_promotion_parameter")) continue;
                        String type = typeName(s, field(p, "type"), scope, index, qn);
                        if (type != null) t.fieldTypes.put(s.text(field(p, "name")).replace("$", ""), type);
                    }
                }
            }
        });
    }

    static List<TSNode> members(TSNode typeDecl) {
        return named(field(typeDecl, "body"));
    }

    /** The single class named by a type ({@code ?User}, {@code User|null}, {@code self}); null for primitives or unions of classes. */
    static String typeName(Source s, TSNode type, Scope scope, TypeIndex index, String self) {
        if (type == null) return null;
        List<TSNode> names = descendants(type, Set.of("name", "qualified_name", "relative_scope"), Set.of("qualified_name"));
        if (names.size() != 1) return null;
        String raw = s.text(names.get(0));
        if (raw.equalsIgnoreCase("self") || raw.equalsIgnoreCase("static")) return self;
        return resolveClass(raw, scope, index);
    }

    // ---- pass 2 -------------------------------------------------------------------------------------------------

    static final class Extractor {
        private final Source s;
        private final TypeIndex index;
        private final Emitter e = new Emitter();
        private final String module;
        private Scope scope;

        Extractor(Source s, TypeIndex index) {
            this.s = s;
            this.index = index;
            this.module = pathName(s.path(), true);
        }

        ParsedSourceFile extract() {
            StringBuilder moduleText = new StringBuilder();
            List<Map.Entry<TSNode, Scope>> moduleLevel = new ArrayList<>();
            String[] firstNamespace = {null};
            walk(s, (stmt, sc) -> {
                scope = sc;
                if (firstNamespace[0] == null && !sc.namespace.isEmpty()) firstNamespace[0] = sc.namespace;
                switch (stmt.getType()) {
                    case "namespace_use_declaration" -> {
                        for (TSNode c : descendants(stmt, Set.of("namespace_use_clause"), Set.of())) {
                            TSNode target = firstNamed(c, "qualified_name", "name");
                            if (target == null || hasToken(stmt, "function") || hasToken(stmt, "const")) continue;
                            TSNode prefix = firstNamed(stmt, "namespace_name");
                            String qn = (prefix == null ? "" : norm(s.text(prefix)) + ".") + norm(s.text(target));
                            e.dep(module, qn, DependencyType.IMPORT, stmt);
                        }
                    }
                    case "class_declaration", "interface_declaration", "trait_declaration", "enum_declaration" -> type(stmt);
                    case "function_definition" -> function(stmt);
                    case "php_tag", "text_interpolation", "comment", "declare_statement" -> { }
                    default -> {
                        moduleLevel.add(Map.entry(stmt, sc));
                        moduleText.append(normalise(s, stmt, COMMENTS)).append('\n');
                    }
                }
            });
            e.symbols.add(0, symbol(s.path().substring(s.path().lastIndexOf('/') + 1), module, SymbolType.MODULE, null, s.root())
                    .startLine(1).endLine(Math.max(1, s.lines())).contentHash(hash(moduleText.toString())).build());
            for (var entry : moduleLevel) {
                scope = entry.getValue();
                refs(entry.getKey(), module, null, new HashMap<>());
            }
            String pkg = firstNamespace[0] != null ? firstNamespace[0] : pathName(directory(s.path()), false);
            return e.file(s, pkg, isTest(s.path()), "php");
        }

        private String qn(String name) {
            return scope.namespace.isEmpty() ? name : scope.namespace + "." + name;
        }

        private void type(TSNode n) {
            String name = s.text(field(n, "name"));
            if (name.isEmpty()) return;
            String qn = qn(name);
            SymbolType st = switch (n.getType()) {
                case "interface_declaration" -> SymbolType.INTERFACE;
                case "enum_declaration" -> SymbolType.ENUM;
                default -> SymbolType.CLASS;
            };
            List<String> annotations = attributes(n);
            if (n.getType().equals("trait_declaration")) annotations.add(0, "trait");
            TSNode body = field(n, "body");
            int end = body == null ? n.getEndByte() : body.getStartByte();
            String header = collapse(new String(s.bytes(), n.getStartByte(), end - n.getStartByte(), java.nio.charset.StandardCharsets.UTF_8));
            e.symbols.add(symbol(name, qn, st, scope.namespace.isEmpty() ? module : null, n)
                    .isAbstract(firstNamed(n, "abstract_modifier") != null).isFinal(firstNamed(n, "final_modifier") != null)
                    .annotations(annotations).annotationText(annotations.isEmpty() ? null : String.join("\n", annotations))
                    .contentHash(hash(header)).build());
            for (TSNode h : named(n)) {
                DependencyType dt = h.getType().equals("base_clause") ? (st == SymbolType.INTERFACE ? DependencyType.EXTENDS : DependencyType.EXTENDS)
                        : h.getType().equals("class_interface_clause") ? DependencyType.IMPLEMENTS : null;
                if (dt != null) for (TSNode t : named(h)) e.dep(qn, resolveClass(s.text(t), scope, index), dt, t);
            }
            for (TSNode m : named(body)) {
                switch (m.getType()) {
                    case "use_declaration" -> {
                        for (TSNode t : named(m)) if (t.getType().equals("name") || t.getType().equals("qualified_name"))
                            e.dep(qn, resolveClass(s.text(t), scope, index), DependencyType.EXTENDS, t);
                    }
                    case "method_declaration" -> method(m, qn);
                    case "property_declaration" -> {
                        String type = typeName(s, field(m, "type"), scope, index, qn);
                        for (TSNode el : descendants(m, Set.of("property_element"), Set.of())) {
                            String f = s.text(field(el, "name")).replace("$", "");
                            e.symbols.add(symbol(f, qn + "#" + f, SymbolType.FIELD, qn, el).visibility(visibility(m))
                                    .isStatic(firstNamed(m, "static_modifier") != null).returnType(collapseOrNull(field(m, "type")))
                                    .contentHash(hash(normalise(s, m, COMMENTS))).build());
                            if (type != null) e.dep(qn + "#" + f, type, DependencyType.FIELD_TYPE, m);
                            TSNode def = field(el, "default_value");
                            if (def != null) refs(def, qn + "#" + f, qn, new HashMap<>());
                        }
                    }
                    case "const_declaration" -> {
                        for (TSNode el : descendants(m, Set.of("const_element"), Set.of())) {
                            TSNode nm = firstNamed(el, "name");
                            if (nm == null) continue;
                            String f = s.text(nm);
                            e.symbols.add(symbol(f, qn + "#" + f, SymbolType.FIELD, qn, el).visibility(visibility(m)).isStatic(true).isFinal(true)
                                    .contentHash(hash(normalise(s, el, COMMENTS))).build());
                        }
                    }
                    case "enum_case" -> {
                        String f = s.text(field(m, "name"));
                        e.symbols.add(symbol(f, qn + "#" + f, SymbolType.FIELD, qn, m).isStatic(true).isFinal(true)
                                .contentHash(hash(normalise(s, m, COMMENTS))).build());
                    }
                    default -> { }
                }
            }
        }

        private String collapseOrNull(TSNode n) {
            return n == null ? null : collapse(s.text(n));
        }

        private List<String> attributes(TSNode n) {
            List<String> out = new ArrayList<>();
            for (TSNode a : named(n)) if (a.getType().equals("attribute_list"))
                for (TSNode attr : descendants(a, Set.of("attribute"), Set.of())) out.add(collapse(s.text(attr)));
            return out;
        }

        private String visibility(TSNode n) {
            TSNode v = firstNamed(n, "visibility_modifier");
            return v == null ? "public" : s.text(v);
        }

        private void method(TSNode m, String classQn) {
            String name = s.text(field(m, "name"));
            String qn = classQn + "#" + name;
            TSNode params = field(m, "parameters"), ret = field(m, "return_type"), body = field(m, "body");
            List<String> annotations = attributes(m);
            e.symbols.add(symbol(name, qn, name.equalsIgnoreCase("__construct") ? SymbolType.CONSTRUCTOR : SymbolType.METHOD, classQn, m)
                    .parameters(params == null ? "" : collapse(s.text(params)).replaceAll("^\\(|\\)$", ""))
                    .returnType(collapseOrNull(ret)).visibility(visibility(m))
                    .isStatic(firstNamed(m, "static_modifier") != null).isAbstract(firstNamed(m, "abstract_modifier") != null)
                    .isFinal(firstNamed(m, "final_modifier") != null)
                    .annotations(annotations).annotationText(annotations.isEmpty() ? null : String.join("\n", annotations))
                    .contentHash(hash(normalise(s, m, COMMENTS))).complexity(complexity(s, body, DECISIONS, Set.of(), LOGICAL)).build());
            Map<String, String> locals = parameters(params, qn, classQn);
            String rt = typeName(s, ret, scope, index, classQn);
            if (rt != null) e.dep(qn, rt, DependencyType.RETURN_TYPE, ret);
            if (body != null) refs(body, qn, classQn, locals);
        }

        private Map<String, String> parameters(TSNode params, String source, String classQn) {
            Map<String, String> locals = new HashMap<>();
            for (TSNode p : named(params)) {
                String type = typeName(s, field(p, "type"), scope, index, classQn);
                TSNode name = field(p, "name");
                if (type == null || name == null) continue;
                locals.put(s.text(name), type);
                e.dep(source, type, DependencyType.PARAMETER_TYPE, p);
            }
            return locals;
        }

        private void function(TSNode f) {
            String name = s.text(field(f, "name"));
            String qn = functionQn(scope.namespace, module, name);
            TSNode params = field(f, "parameters"), ret = field(f, "return_type"), body = field(f, "body");
            e.symbols.add(symbol(name, qn, SymbolType.FUNCTION, module, f)
                    .parameters(params == null ? "" : collapse(s.text(params)).replaceAll("^\\(|\\)$", ""))
                    .returnType(collapseOrNull(ret)).annotations(attributes(f))
                    .contentHash(hash(normalise(s, f, COMMENTS))).complexity(complexity(s, body, DECISIONS, Set.of(), LOGICAL)).build());
            Map<String, String> locals = parameters(params, qn, null);
            String rt = typeName(s, ret, scope, index, null);
            if (rt != null) e.dep(qn, rt, DependencyType.RETURN_TYPE, ret);
            if (body != null) refs(body, qn, null, locals);
        }

        // references -------------------------------------------------------------------------------------------

        private void refs(TSNode n, String source, String classQn, Map<String, String> locals) {
            if (n == null) return;
            switch (n.getType()) {
                case "function_definition", "class_declaration" -> { return; }
                case "assignment_expression" -> {
                    TSNode left = field(n, "left"), right = field(n, "right");
                    if (left != null && left.getType().equals("variable_name")) {
                        String type = valueType(right, classQn, locals);
                        if (type != null) locals.put(s.text(left), type);
                        else locals.remove(s.text(left));
                    }
                }
                case "object_creation_expression" -> {
                    TSNode cls = firstNamed(n, "name", "qualified_name");
                    if (cls != null) e.dep(source, resolveClass(s.text(cls), scope, index), DependencyType.CONSTRUCTOR_CALL, n);
                }
                case "member_call_expression", "nullsafe_member_call_expression" -> {
                    String type = receiverType(field(n, "object"), classQn, locals);
                    String target = type == null ? null : index.method(type, s.text(field(n, "name")));
                    if (target != null) e.dep(source, target, DependencyType.METHOD_CALL, n);
                }
                case "scoped_call_expression" -> {
                    String type = scopeClass(field(n, "scope"), classQn);
                    String method = s.text(field(n, "name"));
                    if (type != null) {
                        String target = index.has(type) ? index.method(type, method) : type + "#" + method;
                        if (target != null) e.dep(source, target, DependencyType.METHOD_CALL, n);
                    }
                }
                case "function_call_expression" -> {
                    TSNode fn = field(n, "function");
                    if (fn != null && (fn.getType().equals("name") || fn.getType().equals("qualified_name"))) {
                        String target = resolveFunction(s.text(fn));
                        if (target != null) e.dep(source, target, DependencyType.METHOD_CALL, n);
                    }
                }
                case "member_access_expression", "nullsafe_member_access_expression" -> {
                    TSNode obj = field(n, "object");
                    if (obj != null && s.text(obj).equals("$this") && classQn != null) {
                        String target = index.field(classQn, s.text(field(n, "name")));
                        if (target != null) e.dep(source, target, DependencyType.FIELD_ACCESS, n);
                    }
                }
                case "class_constant_access_expression" -> {
                    if (n.getNamedChildCount() == 2) {
                        String type = scopeClass(n.getNamedChild(0), classQn);
                        String member = s.text(n.getNamedChild(1));
                        if (type != null && !member.equals("class") && index.has(type)) {
                            String target = index.field(type, member);
                            if (target != null) e.dep(source, target, DependencyType.FIELD_ACCESS, n);
                        }
                    }
                }
                case "array_creation_expression" -> callable(n, source, classQn);
                case "string", "encapsed_string", "heredoc", "nowdoc" -> {
                    for (String t : EmbeddedSql.tables(s.text(n).replaceAll("^['\"]|['\"]$", ""))) e.dep(source, t, DependencyType.FIELD_ACCESS, n);
                    return;
                }
                default -> { }
            }
            for (TSNode c : named(n)) refs(c, source, classQn, locals);
        }

        /** {@code [UserController::class, 'index']} → {@code UserController#index}. */
        private void callable(TSNode array, String source, String classQn) {
            List<TSNode> els = named(array);
            if (els.size() != 2) return;
            TSNode first = els.get(0).getNamedChildCount() > 0 ? els.get(0).getNamedChild(0) : null;
            TSNode second = els.get(1).getNamedChildCount() > 0 ? els.get(1).getNamedChild(0) : null;
            if (first == null || second == null || !first.getType().equals("class_constant_access_expression") || !second.getType().equals("string")) return;
            if (first.getNamedChildCount() != 2 || !s.text(first.getNamedChild(1)).equals("class")) return;
            String type = scopeClass(first.getNamedChild(0), classQn);
            String method = s.text(second).replaceAll("^['\"]|['\"]$", "");
            if (type == null || !method.matches("[A-Za-z_]\\w*")) return;
            String target = index.has(type) ? index.method(type, method) : type + "#" + method;
            if (target != null) e.dep(source, target, DependencyType.METHOD_CALL, array);
        }

        private String scopeClass(TSNode scopeNode, String classQn) {
            if (scopeNode == null) return null;
            String raw = s.text(scopeNode);
            if (raw.equalsIgnoreCase("self") || raw.equalsIgnoreCase("static")) return classQn;
            if (raw.equalsIgnoreCase("parent")) return classQn == null ? null : index.superclass(classQn);
            if (!scopeNode.getType().equals("name") && !scopeNode.getType().equals("qualified_name")) return null;
            return resolveClass(raw, scope, index);
        }

        private String resolveFunction(String raw) {
            String n = norm(raw);
            if (raw.startsWith("\\")) {
                String q = n.contains(".") ? n.substring(0, n.lastIndexOf('.')) + "#" + n.substring(n.lastIndexOf('.') + 1) : null;
                return q != null && index.hasFunction(q) ? q : n.contains(".") ? null : index.uniqueFunction(n);
            }
            String imported = scope.useFunctions.get(n);
            if (imported != null) return imported;
            if (n.contains(".")) {
                String q = resolveClass(n.substring(0, n.lastIndexOf('.')), scope, index) + "#" + n.substring(n.lastIndexOf('.') + 1);
                return index.hasFunction(q) ? q : null;
            }
            String local = functionQn(scope.namespace, module, n);
            if (index.hasFunction(local)) return local;
            return index.uniqueFunction(n); // global function of another file (PHP falls back to the global namespace)
        }

        private String receiverType(TSNode obj, String classQn, Map<String, String> locals) {
            if (obj == null) return null;
            switch (obj.getType()) {
                case "variable_name" -> {
                    String v = s.text(obj);
                    return v.equals("$this") ? classQn : locals.get(v);
                }
                case "member_access_expression", "nullsafe_member_access_expression" -> {
                    String owner = receiverType(field(obj, "object"), classQn, locals);
                    return owner == null ? null : index.fieldType(owner, s.text(field(obj, "name")));
                }
                case "member_call_expression", "nullsafe_member_call_expression" -> {
                    String owner = receiverType(field(obj, "object"), classQn, locals);
                    return owner == null ? null : index.returnType(owner, s.text(field(obj, "name")));
                }
                case "scoped_call_expression" -> {
                    String owner = scopeClass(field(obj, "scope"), classQn);
                    return owner == null ? null : index.returnType(owner, s.text(field(obj, "name")));
                }
                case "parenthesized_expression", "object_creation_expression" -> {
                    return valueType(obj, classQn, locals);
                }
                default -> {
                    return null;
                }
            }
        }

        private String valueType(TSNode v, String classQn, Map<String, String> locals) {
            if (v == null) return null;
            if (v.getType().equals("parenthesized_expression") && v.getNamedChildCount() == 1) return valueType(v.getNamedChild(0), classQn, locals);
            if (v.getType().equals("object_creation_expression")) {
                TSNode cls = firstNamed(v, "name", "qualified_name");
                return cls == null ? null : resolveClass(s.text(cls), scope, index);
            }
            String t = receiverType(v, classQn, locals);
            return index.has(t) ? t : null;
        }
    }
}

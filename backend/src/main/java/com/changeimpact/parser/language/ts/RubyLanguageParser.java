package com.changeimpact.parser.language.ts;

import com.changeimpact.common.config.AppProperties;
import com.changeimpact.parser.model.*;
import org.springframework.stereotype.Component;
import org.treesitter.TSLanguage;
import org.treesitter.TSNode;
import org.treesitter.TreeSitterRuby;

import java.nio.file.Path;
import java.util.*;

/**
 * Ruby (plain Ruby, gems, Rails).
 *
 * <p>Model: modules and classes are named by their nesting ({@code module Billing; class Invoice} →
 * {@code Billing.Invoice}, {@code Billing::Invoice} the same), methods {@code Billing.Invoice#charge!} (class methods
 * too, flagged static), {@code attr_*} attributes are fields. Every file is a {@code MODULE}
 * ({@code app/models/invoice.rb} → {@code app.models.invoice.rb}) holding its top-level code — including RSpec/Minitest
 * blocks — and its {@code require}s.
 *
 * <p>Constants resolve lexically like Ruby does (enclosing modules, then top level). Links: superclass ({@code EXTENDS}),
 * {@code include}/{@code extend}/{@code prepend} ({@code EXTENDS}); {@code Const.new} ({@code CONSTRUCTOR_CALL});
 * {@code Const.m}; {@code self.m}, bare {@code m} and {@code super} to methods of the class and its ancestors in the
 * repository; calls on locals and instance variables assigned {@code Const.new}; Rails associations
 * ({@code has_many :orders} → {@code Order}); ActiveRecord models to their table ({@code db.invoices}) and
 * {@code create_table} in {@code db/} as table definitions; tables in SQL strings. Dynamic dispatch
 * ({@code send}, {@code method_missing}, metaprogramming) and calls on values of unknown class are not linked.
 */
@Component
public class RubyLanguageParser extends TreeSitterParser {

    private static final Set<String> COMMENTS = Set.of("comment");
    private static final Set<String> DECISIONS = Set.of("if", "unless", "elsif", "while", "until", "for", "when", "rescue",
            "conditional", "if_modifier", "unless_modifier", "while_modifier", "until_modifier", "rescue_modifier", "in_clause");
    private static final Set<String> LOGICAL = Set.of("&&", "||", "and", "or");
    private static final Set<String> MIXINS = Set.of("include", "extend", "prepend");
    private static final Set<String> ATTRS = Set.of("attr_reader", "attr_writer", "attr_accessor");
    private static final Set<String> ASSOCIATIONS = Set.of("has_many", "has_one", "belongs_to", "has_and_belongs_to_many");
    private static final Set<String> AR_BASES = Set.of("ApplicationRecord", "ActiveRecord.Base");

    public RubyLanguageParser(AppProperties appProperties) {
        super(appProperties, new TreeSitterRuby());
    }

    @Override public String language() { return "ruby"; }
    @Override public Set<String> extensions() { return Set.of(".rb", ".rake"); }
    @Override protected TSLanguage grammarFor(String relativePath) { return new TreeSitterRuby(); }

    public static boolean isTest(String rel) {
        String p = "/" + rel;
        return p.contains("/spec/") || p.contains("/test/") || rel.endsWith("_spec.rb") || rel.endsWith("_test.rb");
    }

    static String moduleOf(String rel) {
        return pathName(rel, true);
    }

    /** {@code orders} → {@code Order}, {@code categories} → {@code Category}, {@code line_items} → {@code LineItem}. */
    static String classify(String name, boolean plural) {
        String n = name;
        if (plural) {
            if (n.endsWith("ies")) n = n.substring(0, n.length() - 3) + "y";
            else if (n.endsWith("sses") || n.endsWith("xes") || n.endsWith("ches") || n.endsWith("shes")) n = n.substring(0, n.length() - 2);
            else if (n.endsWith("s") && !n.endsWith("ss")) n = n.substring(0, n.length() - 1);
        }
        StringBuilder sb = new StringBuilder();
        for (String part : n.split("_")) if (!part.isEmpty()) sb.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        return sb.toString();
    }

    /** {@code LineItem} → {@code line_items} (Rails table name convention, regular plurals). */
    static String tableize(String className) {
        String snake = className.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT);
        if (snake.endsWith("y") && !snake.matches(".*[aeiou]y$")) return snake.substring(0, snake.length() - 1) + "ies";
        if (snake.endsWith("s") || snake.endsWith("x") || snake.endsWith("ch") || snake.endsWith("sh")) return snake + "es";
        return snake + "s";
    }

    // ---- constant scopes ----------------------------------------------------------------------------------------

    /** Constant path as written ({@code A::B}, {@code ::C}) → its parts. */
    static List<String> constParts(Source s, TSNode n) {
        if (n == null) return List.of();
        if (n.getType().equals("constant")) return List.of(s.text(n));
        if (n.getType().equals("scope_resolution")) {
            List<String> out = new ArrayList<>(field(n, "scope") == null ? List.of("") : constParts(s, field(n, "scope")));
            if (field(n, "scope") != null && out.isEmpty()) return List.of();
            out.add(s.text(field(n, "name")));
            return out;
        }
        return List.of();
    }

    /** Lexical lookup of a constant from {@code nesting} (innermost first), like Ruby without ancestors. */
    static String resolve(List<String> parts, List<String> nesting, TypeIndex index) {
        if (parts.isEmpty()) return null;
        if (parts.get(0).isEmpty()) return String.join(".", parts.subList(1, parts.size()));
        String rest = String.join(".", parts);
        for (String scope : nesting) {
            String q = scope + "." + rest;
            if (index.has(q)) return q;
            TypeIndex.TypeInfo t = index.get(scope);
            if (t != null) for (String sup : t.supertypes) if (index.has(sup + "." + rest)) return sup + "." + rest;
        }
        return index.has(rest) ? rest : null;
    }

    /** Nesting list for a class body: innermost first, e.g. [Billing.Invoice, Billing]. */
    static List<String> nesting(String qn, List<String> outer) {
        List<String> out = new ArrayList<>();
        out.add(qn);
        out.addAll(outer);
        return out;
    }

    interface Visitor {
        void visit(TSNode decl, String qn, List<String> nestingInside, List<String> nestingOutside);
    }

    /** Visits module/class declarations (nested too) with their qualified names. */
    static void walk(Source s, TSNode container, List<String> nesting, Visitor v) {
        for (TSNode n : named(container)) {
            if (n.getType().equals("module") || n.getType().equals("class")) {
                List<String> parts = constParts(s, field(n, "name"));
                if (parts.isEmpty()) continue;
                String qn = parts.get(0).isEmpty() ? String.join(".", parts.subList(1, parts.size()))
                        : (nesting.isEmpty() ? "" : nesting.get(0) + ".") + String.join(".", parts);
                List<String> inside = nesting(qn, nesting);
                v.visit(n, qn, inside, nesting);
                walk(s, field(n, "body"), inside, v);
            } else if (n.getType().equals("body_statement") || n.getType().equals("begin")) {
                walk(s, n, nesting, v);
            }
        }
    }

    static String callName(Source s, TSNode call) {
        TSNode m = field(call, "method");
        return m == null ? "" : s.text(m);
    }

    static List<TSNode> args(TSNode call) {
        return named(field(call, "arguments"));
    }

    static String symbolName(Source s, TSNode n) {
        if (n == null) return null;
        if (n.getType().equals("simple_symbol")) return s.text(n).substring(1);
        if (n.getType().equals("string")) return s.text(n).replaceAll("^['\"]|['\"]$", "");
        return null;
    }

    @Override
    protected Map<String, ParsedSourceFile> analyse(Path root, List<Source> sources) {
        TypeIndex index = new TypeIndex();
        Set<String> files = new HashSet<>();
        sources.forEach(s -> files.add(s.path()));
        for (Source s : sources) walk(s, s.root(), List.of(), (n, qn, inside, outside) -> declare(s, n, qn, index));
        for (Source s : sources) walk(s, s.root(), List.of(), (n, qn, inside, outside) -> resolveAncestry(s, n, qn, inside, outside, index));
        for (Source s : sources) for (TSNode m : named(s.root())) {
            if (m.getType().equals("method")) index.function(moduleOf(s.path()) + "#" + s.text(field(m, "name")), s.text(field(m, "name")));
        }
        Map<String, ParsedSourceFile> out = new LinkedHashMap<>();
        for (Source s : sources) {
            try {
                out.put(s.path(), new Extractor(s, index, files).extract());
            } catch (Exception e) {
                org.slf4j.LoggerFactory.getLogger(getClass()).debug("Ruby file {} skipped: {}", s.path(), e.toString());
            }
        }
        return out;
    }

    private static void declare(Source s, TSNode n, String qn, TypeIndex index) {
        TypeIndex.TypeInfo t = index.declare(qn, n.getType());
        for (TSNode m : bodyStatements(n)) {
            switch (m.getType()) {
                case "method" -> t.methods.add(s.text(field(m, "name")));
                case "singleton_method" -> t.staticMethods.add(s.text(field(m, "name")));
                case "singleton_class" -> { for (TSNode sm : bodyStatements(m)) if (sm.getType().equals("method")) t.staticMethods.add(s.text(field(sm, "name"))); }
                case "call" -> {
                    String name = callName(s, m);
                    if (ATTRS.contains(name)) for (TSNode a : args(m)) {
                        String attr = symbolName(s, a);
                        if (attr == null) continue;
                        t.fields.add(attr);
                        if (!name.equals("attr_writer")) t.methods.add(attr);
                        if (!name.equals("attr_reader")) t.methods.add(attr + "=");
                    }
                    if ((name.equals("private") || name.equals("protected") || name.equals("public")) && !args(m).isEmpty()) {
                        TSNode def = args(m).get(0);
                        if (def.getType().equals("method")) t.methods.add(s.text(field(def, "name")));
                    }
                }
                default -> { }
            }
        }
    }

    static List<TSNode> bodyStatements(TSNode decl) {
        TSNode body = field(decl, "body");
        if (body == null) {
            for (TSNode c : named(decl)) if (c.getType().equals("body_statement")) return named(c);
            return List.of();
        }
        return body.getType().equals("body_statement") ? named(body) : List.of(body);
    }

    private static void resolveAncestry(Source s, TSNode n, String qn, List<String> inside, List<String> outside, TypeIndex index) {
        TypeIndex.TypeInfo t = index.get(qn);
        if (t == null) return;
        TSNode sup = field(n, "superclass");
        if (sup != null && !named(sup).isEmpty()) {
            List<String> parts = constParts(s, named(sup).get(0));
            String r = resolve(parts, outside, index);
            if (r == null && !parts.isEmpty()) r = String.join(".", parts).replaceFirst("^\\.", "");
            if (r != null && !t.supertypes.contains(r)) t.supertypes.add(0, r);
        }
        for (TSNode m : bodyStatements(n)) {
            if (!m.getType().equals("call") || !MIXINS.contains(callName(s, m)) || field(m, "receiver") != null) continue;
            for (TSNode a : args(m)) {
                String r = resolve(constParts(s, a), inside, index);
                if (r != null && !t.supertypes.contains(r)) t.supertypes.add(r);
            }
        }
        // instance variable types: @x = Const.new anywhere in the class
        for (TSNode a : descendants(field(n, "body"), Set.of("assignment"), Set.of("class", "module"))) {
            TSNode left = field(a, "left"), right = field(a, "right");
            if (left == null || !left.getType().equals("instance_variable") || right == null || !right.getType().equals("call")) continue;
            if (!callName(s, right).equals("new")) continue;
            String r = resolve(constParts(s, field(right, "receiver")), inside, index);
            if (r != null) t.fieldTypes.putIfAbsent(s.text(left), r);
        }
    }

    // ---- pass 2 -------------------------------------------------------------------------------------------------

    static final class Extractor {
        private final Source s;
        private final TypeIndex index;
        private final Set<String> files;
        private final Emitter e = new Emitter();
        private final String module;

        Extractor(Source s, TypeIndex index, Set<String> files) {
            this.s = s;
            this.index = index;
            this.files = files;
            this.module = moduleOf(s.path());
        }

        ParsedSourceFile extract() {
            StringBuilder moduleText = new StringBuilder();
            List<TSNode> top = new ArrayList<>();
            for (TSNode n : named(s.root())) {
                switch (n.getType()) {
                    case "module", "class" -> { }
                    case "method" -> method(n, null, List.of(), false, "public");
                    case "comment" -> { }
                    default -> { top.add(n); moduleText.append(normalise(s, n, COMMENTS)).append('\n'); }
                }
            }
            walk(s, s.root(), List.of(), this::type);
            e.symbols.add(0, symbol(s.path().substring(s.path().lastIndexOf('/') + 1), module, SymbolType.MODULE, null, s.root())
                    .startLine(1).endLine(Math.max(1, s.lines())).contentHash(hash(moduleText.toString())).build());
            Map<String, String> locals = new HashMap<>();
            for (TSNode n : top) refs(n, module, null, List.of(), locals, false);
            String pkg = pathName(directory(s.path()), false);
            return e.file(s, pkg, isTest(s.path()), "ruby");
        }

        private void require(TSNode call) {
            String name = callName(s, call);
            List<TSNode> a = args(call);
            if (a.isEmpty() || !a.get(0).getType().equals("string")) return;
            String spec = s.text(a.get(0)).replaceAll("^['\"]|['\"]$", "");
            String target = null;
            if (name.equals("require_relative")) {
                String p = join(directory(s.path()), spec.endsWith(".rb") ? spec : spec + ".rb");
                if (p != null && files.contains(p)) target = moduleOf(p);
            } else {
                String f = spec.endsWith(".rb") ? spec : spec + ".rb";
                for (String candidate : List.of("lib/" + f, f, "app/" + f)) if (files.contains(candidate)) { target = moduleOf(candidate); break; }
                if (target == null && !spec.startsWith(".")) target = "gem:" + spec.split("/")[0];
            }
            if (target != null) e.dep(module, target, DependencyType.IMPORT, call);
        }

        private void type(TSNode n, String qn, List<String> inside, List<String> outside) {
            boolean isClass = n.getType().equals("class");
            TSNode body = field(n, "body");
            int end = body == null ? n.getEndByte() : body.getStartByte();
            List<String> annotations = new ArrayList<>();
            boolean model = isClass && isActiveRecord(qn, new HashSet<>());
            if (model) annotations.add("model");
            e.symbols.add(symbol(qn.substring(qn.lastIndexOf('.') + 1), qn, isClass ? SymbolType.CLASS : SymbolType.MODULE,
                    qn.contains(".") ? qn.substring(0, qn.lastIndexOf('.')) : module, n)
                    .annotations(annotations).annotationText(annotations.isEmpty() ? null : String.join("\n", annotations))
                    .contentHash(hash(collapse(new String(s.bytes(), n.getStartByte(), end - n.getStartByte(), java.nio.charset.StandardCharsets.UTF_8))))
                    .build());
            TypeIndex.TypeInfo info = index.get(qn);
            TSNode sup = field(n, "superclass");
            if (sup != null && info != null && !info.supertypes.isEmpty()) e.dep(qn, info.supertypes.get(0), DependencyType.EXTENDS, sup);
            if (model) e.dep(qn, EmbeddedSql.PREFIX + tableize(qn.substring(qn.lastIndexOf('.') + 1)), DependencyType.ANNOTATION, n);
            String visibility = "public";
            boolean classScope = false;
            for (TSNode m : bodyStatements(n)) {
                switch (m.getType()) {
                    case "method" -> method(m, qn, inside, false, visibility);
                    case "singleton_method" -> method(m, qn, inside, true, "public");
                    case "singleton_class" -> { for (TSNode sm : bodyStatements(m)) if (sm.getType().equals("method")) method(sm, qn, inside, true, "public"); }
                    case "identifier" -> {
                        String v = s.text(m);
                        if (v.equals("private") || v.equals("protected") || v.equals("public")) visibility = v;
                        else refs(m, qn, qn, inside, new HashMap<>(), true);
                    }
                    case "call" -> classCall(m, qn, inside, visibility);
                    case "module", "class", "comment" -> { }
                    default -> refs(m, qn, qn, inside, new HashMap<>(), true);
                }
            }
        }

        private boolean isActiveRecord(String qn, Set<String> seen) {
            TypeIndex.TypeInfo t = index.get(qn);
            if (t == null || !seen.add(qn) || t.supertypes.isEmpty()) return false;
            String sup = t.supertypes.get(0);
            if (AR_BASES.contains(sup)) return !TypeIndex.simple(qn).equals("ApplicationRecord");
            return isActiveRecord(sup, seen);
        }

        /** Class-body DSL: mixins, attributes, associations, {@code private def}, other calls. */
        private void classCall(TSNode m, String qn, List<String> inside, String visibility) {
            String name = callName(s, m);
            if (field(m, "receiver") == null && MIXINS.contains(name)) {
                for (TSNode a : args(m)) {
                    String r = resolve(constParts(s, a), inside, index);
                    if (r != null) e.dep(qn, r, DependencyType.EXTENDS, a);
                }
                return;
            }
            if (field(m, "receiver") == null && ATTRS.contains(name)) {
                for (TSNode a : args(m)) {
                    String attr = symbolName(s, a);
                    if (attr != null) e.symbols.add(symbol(attr, qn + "#" + attr, SymbolType.FIELD, qn, a).visibility(visibility)
                            .annotations(List.of(name)).contentHash(hash(name + " " + attr)).build());
                }
                return;
            }
            if ((name.equals("private") || name.equals("protected") || name.equals("public")) && !args(m).isEmpty() && args(m).get(0).getType().equals("method")) {
                method(args(m).get(0), qn, inside, false, name);
                return;
            }
            if (field(m, "receiver") == null && ASSOCIATIONS.contains(name) && !args(m).isEmpty()) {
                String assoc = symbolName(s, args(m).get(0));
                if (assoc != null) {
                    String className = null;
                    for (TSNode p : descendants(field(m, "arguments"), Set.of("pair"), Set.of())) {
                        TSNode key = field(p, "key");
                        if (key != null && s.text(key).replace(":", "").trim().equals("class_name")) className = symbolName(s, field(p, "value"));
                    }
                    String cls = className != null ? className.replace("::", ".")
                            : classify(assoc, name.equals("has_many") || name.equals("has_and_belongs_to_many"));
                    String r = resolve(Arrays.asList(cls.split("\\.")), inside, index);
                    e.symbols.add(symbol(assoc, qn + "#" + assoc, SymbolType.FIELD, qn, m).annotations(List.of(name))
                            .annotationText(name).contentHash(hash(normalise(s, m, COMMENTS))).build());
                    if (r != null) e.dep(qn + "#" + assoc, r, DependencyType.FIELD_TYPE, m);
                }
                return;
            }
            refs(m, qn, qn, inside, new HashMap<>(), true);
        }

        private void method(TSNode m, String classQn, List<String> nesting, boolean singleton, String visibility) {
            String name = s.text(field(m, "name"));
            String owner = classQn == null ? module : classQn;
            String qn = owner + "#" + name;
            TSNode params = field(m, "parameters");
            e.symbols.add(symbol(name, qn, classQn == null ? SymbolType.FUNCTION : name.equals("initialize") ? SymbolType.CONSTRUCTOR : SymbolType.METHOD, owner, m)
                    .parameters(params == null ? "" : collapse(s.text(params)).replaceAll("^\\(|\\)$", ""))
                    .visibility(visibility).isStatic(singleton)
                    .contentHash(hash(normalise(s, m, COMMENTS)))
                    .complexity(complexity(s, m, DECISIONS, Set.of("method", "singleton_method", "class", "module"), LOGICAL)).build());
            Map<String, String> locals = new HashMap<>();
            for (TSNode p : named(params)) {
                TSNode id = p.getType().equals("identifier") ? p : firstNamed(p, "identifier");
                if (id != null) locals.put(s.text(id), "");
            }
            for (TSNode c : named(m)) {
                if (c.equals(field(m, "name")) || c.equals(params) || c.equals(field(m, "object"))) continue;
                refs(c, qn, classQn, nesting, locals, singleton);
            }
            superCalls(m, qn, classQn, name);
        }

        private void superCalls(TSNode m, String qn, String classQn, String name) {
            if (classQn == null) return;
            for (TSNode sup : descendants(m, Set.of("super"), Set.of("method", "singleton_method", "class", "module"))) {
                TypeIndex.TypeInfo t = index.get(classQn);
                if (t == null) continue;
                for (String a : t.supertypes) {
                    String target = index.method(a, name);
                    if (target != null) { e.dep(qn, target, DependencyType.METHOD_CALL, sup); break; }
                }
            }
        }

        // references -------------------------------------------------------------------------------------------

        private void refs(TSNode n, String source, String classQn, List<String> nesting, Map<String, String> locals, boolean singleton) {
            if (n == null) return;
            switch (n.getType()) {
                case "method", "singleton_method", "class", "module" -> { return; }
                case "assignment" -> {
                    TSNode left = field(n, "left"), right = field(n, "right");
                    if (left != null && left.getType().equals("identifier")) {
                        String t = valueType(right, classQn, nesting, locals);
                        locals.put(s.text(left), t == null ? "" : t);
                    }
                }
                case "call" -> {
                    call(n, source, classQn, nesting, locals, singleton);
                    for (TSNode c : named(n)) if (!c.equals(field(n, "method"))) refs(c, source, classQn, nesting, locals, singleton);
                    return;
                }
                case "identifier" -> {
                    String name = s.text(n);
                    TSNode parent = n.getParent();
                    if (!locals.containsKey(name) && parent != null && !parent.getType().equals("assignment") && !parent.getType().equals("call")
                            && !parent.getType().endsWith("parameters") && !parent.getType().equals("pair")) {
                        String target = ownMethod(classQn, name, singleton);
                        if (target == null && classQn == null) target = index.hasFunction(module + "#" + name) ? module + "#" + name : null;
                        if (target != null) e.dep(source, target, DependencyType.METHOD_CALL, n);
                    }
                    return;
                }
                case "block_parameters", "lambda_parameters" -> {
                    for (TSNode id : descendants(n, Set.of("identifier"), Set.of())) locals.put(s.text(id), "");
                    return;
                }
                case "instance_variable" -> {
                    if (classQn != null) {
                        String f = s.text(n).substring(1);
                        if (index.get(classQn) != null && index.field(classQn, f) != null) e.dep(source, classQn + "#" + f, DependencyType.FIELD_ACCESS, n);
                    }
                    return;
                }
                case "constant", "scope_resolution" -> {
                    TSNode parent = n.getParent();
                    if (parent != null && parent.getType().equals("call") && n.equals(field(parent, "receiver"))) return;
                    String r = resolve(constParts(s, n), nesting, index);
                    if (r != null && !r.equals(classQn)) e.dep(source, r, DependencyType.FIELD_ACCESS, n);
                    return;
                }
                case "string", "heredoc_body" -> {
                    for (String t : EmbeddedSql.tables(s.text(n).replaceAll("^['\"]|['\"]$", ""))) e.dep(source, t, DependencyType.FIELD_ACCESS, n);
                    return;
                }
                default -> { }
            }
            for (TSNode c : named(n)) refs(c, source, classQn, nesting, locals, singleton);
        }

        private String ownMethod(String classQn, String name, boolean singleton) {
            if (classQn == null) return null;
            TypeIndex.TypeInfo t = index.get(classQn);
            if (t == null) return null;
            return index.method(classQn, name);
        }

        private void call(TSNode n, String source, String classQn, List<String> nesting, Map<String, String> locals, boolean singleton) {
            String name = callName(s, n);
            TSNode receiver = field(n, "receiver");
            if (receiver == null) {
                if (name.equals("require") || name.equals("require_relative") || name.equals("load")) { require(n); return; }
                String target = locals.containsKey(name) ? null : ownMethod(classQn, name, singleton);
                if (target == null && classQn == null && index.hasFunction(module + "#" + name)) target = module + "#" + name;
                if (target != null) e.dep(source, target, DependencyType.METHOD_CALL, n);
                createTable(n);
                return;
            }
            String type = receiverType(receiver, classQn, nesting, locals);
            if (type == null) return;
            if (name.equals("new") && (receiver.getType().equals("constant") || receiver.getType().equals("scope_resolution"))) {
                e.dep(source, type, DependencyType.CONSTRUCTOR_CALL, n);
                return;
            }
            String target = index.method(type, name);
            if (target != null) e.dep(source, target, DependencyType.METHOD_CALL, n);
        }

        /** {@code create_table :users do |t| t.string :name end} in db/ → table and column symbols. */
        private void createTable(TSNode n) {
            if (!callName(s, n).equals("create_table") || args(n).isEmpty()) return;
            // the schema is authoritative; migrations only when the repository has no db/schema.rb
            boolean schema = s.path().endsWith("db/schema.rb");
            boolean migration = ("/" + s.path()).contains("/db/migrate/") && files.stream().noneMatch(f -> f.endsWith("db/schema.rb"));
            if (!schema && !migration) return;
            String table = symbolName(s, args(n).get(0));
            if (table == null) return;
            String qn = EmbeddedSql.name(table);
            e.symbols.add(symbol(table, qn, SymbolType.CLASS, module, n).annotations(List.of("table")).annotationText("table")
                    .contentHash(hash(normalise(s, n, COMMENTS))).build());
            TSNode block = firstNamed(n, "do_block", "block");
            for (TSNode c : descendants(block, Set.of("call"), Set.of())) {
                TSNode r = field(c, "receiver");
                if (r == null || !r.getType().equals("identifier") || args(c).isEmpty()) continue;
                String col = symbolName(s, args(c).get(0));
                String colType = callName(s, c);
                if (col == null || colType.equals("index")) continue;
                if (colType.equals("references") || colType.equals("belongs_to")) {
                    e.dep(qn, EmbeddedSql.name(tableize(classify(col, false))), DependencyType.FIELD_TYPE, c);
                    col = col + "_id";
                }
                e.symbols.add(symbol(col, qn + "#" + col.toLowerCase(Locale.ROOT), SymbolType.FIELD, qn, c).returnType(colType)
                        .contentHash(hash(normalise(s, c, COMMENTS))).build());
            }
        }

        private String receiverType(TSNode r, String classQn, List<String> nesting, Map<String, String> locals) {
            switch (r.getType()) {
                case "self" -> { return classQn; }
                case "constant", "scope_resolution" -> { return resolve(constParts(s, r), nesting, index); }
                case "identifier" -> {
                    String t = locals.get(s.text(r));
                    return t == null || t.isEmpty() ? null : t;
                }
                case "instance_variable" -> {
                    return classQn == null ? null : index.fieldType(classQn, s.text(r));
                }
                case "call", "parenthesized_statements" -> { return valueType(r, classQn, nesting, locals); }
                default -> { return null; }
            }
        }

        /** {@code Const.new(...)} → Const; a local/ivar of known class. */
        private String valueType(TSNode v, String classQn, List<String> nesting, Map<String, String> locals) {
            if (v == null) return null;
            if (v.getType().equals("parenthesized_statements") && v.getNamedChildCount() == 1) return valueType(v.getNamedChild(0), classQn, nesting, locals);
            if (v.getType().equals("call") && callName(s, v).equals("new")) {
                TSNode r = field(v, "receiver");
                if (r != null && (r.getType().equals("constant") || r.getType().equals("scope_resolution"))) return resolve(constParts(s, r), nesting, index);
                if (r != null && r.getType().equals("self")) return classQn;
            }
            if (v.getType().equals("identifier") || v.getType().equals("instance_variable")) return receiverType(v, classQn, nesting, locals);
            return null;
        }
    }
}

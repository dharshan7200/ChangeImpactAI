package com.changeimpact.parser.language.ts;

import com.changeimpact.common.config.AppProperties;
import com.changeimpact.parser.model.*;
import org.springframework.stereotype.Component;
import org.treesitter.*;
import tools.jackson.core.json.JsonReadFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Pattern;

/**
 * JavaScript and TypeScript, including JSX/TSX (React).
 *
 * <p>Model: a file is a {@code MODULE} named by its path without extension ({@code src/components/Button.tsx} →
 * {@code src.components.Button}; {@code index} files are their directory). Classes, interfaces, type aliases and enums
 * are {@code module.Name}; functions — declarations and {@code const f = () => …} — are {@code module#name}; methods are
 * {@code module.Class#name}. React components (capitalised functions or classes that render JSX, or extend
 * {@code Component}) carry the annotation {@code component}, hooks ({@code useX}) {@code hook}.
 *
 * <p>Imports are resolved like the bundler does: relative paths with extensions and {@code index} files,
 * {@code tsconfig}/{@code jsconfig} {@code paths} and {@code baseUrl}, named/default/namespace imports, re-exports
 * ({@code export … from}, {@code export *}) and CommonJS {@code require}/{@code module.exports}. Dependencies:
 * {@code IMPORT}; {@code METHOD_CALL} to imported or local functions, {@code this.m()}, {@code ns.f()};
 * {@code CONSTRUCTOR_CALL} for {@code new C()} and JSX elements ({@code <Button/>} uses the component);
 * {@code EXTENDS}/{@code IMPLEMENTS}; TypeScript parameter and return types; {@code className} strings and CSS Modules
 * ({@code styles.card}) to the stylesheet selectors; tables in SQL strings. Calls on values of unknown type are not
 * resolved. Packages ({@code react}, {@code lodash}) appear as external targets {@code npm:react#useState}.
 */
@Component
public class JavaScriptLanguageParser extends TreeSitterParser {

    static final List<String> EXTS = List.of(".ts", ".tsx", ".js", ".jsx", ".mjs", ".cjs", ".mts", ".cts");
    private static final Set<String> COMMENTS = Set.of("comment", "html_comment");
    private static final Set<String> FUNCTION_VALUES = Set.of("arrow_function", "function_expression", "function", "generator_function");
    private static final Set<String> DECISIONS = Set.of("if_statement", "for_statement", "for_in_statement", "while_statement",
            "do_statement", "catch_clause", "ternary_expression", "switch_case");
    private static final Set<String> LOGICAL = Set.of("&&", "||", "??");
    private static final Set<String> JSX = Set.of("jsx_element", "jsx_self_closing_element", "jsx_fragment");
    private static final Pattern COMPONENT_BASE = Pattern.compile("(React\\.)?(Pure)?Component");
    private static final JsonMapper LENIENT = JsonMapper.builder()
            .enable(JsonReadFeature.ALLOW_JAVA_COMMENTS, JsonReadFeature.ALLOW_TRAILING_COMMA).build();

    private final CssLanguageParser css;

    public JavaScriptLanguageParser(AppProperties appProperties, CssLanguageParser css) {
        super(appProperties, new TreeSitterTsx());
        this.css = css;
    }

    @Override public String language() { return "javascript"; }
    @Override public Set<String> extensions() { return new TreeSet<>(EXTS); }

    @Override
    protected boolean accepts(String relativePath) {
        String n = relativePath.toLowerCase(Locale.ROOT);
        return EXTS.stream().anyMatch(n::endsWith) && !n.endsWith(".d.ts") && !n.endsWith(".min.js")
                && !n.endsWith(".bundle.js");
    }

    @Override
    protected TSLanguage grammarFor(String p) {
        String n = p.toLowerCase(Locale.ROOT);
        if (n.endsWith(".tsx")) return new TreeSitterTsx();
        if (n.endsWith(".ts") || n.endsWith(".mts") || n.endsWith(".cts")) return new TreeSitterTypescript();
        return new TreeSitterJavascript();
    }

    /** {@code src/a/Button.tsx} → {@code src.a.Button}; {@code src/a/index.ts} → {@code src.a}. */
    public static String moduleName(String rel) {
        String p = rel;
        int slash = p.lastIndexOf('/'), dot = p.lastIndexOf('.');
        if (dot > slash) p = p.substring(0, dot);
        if (p.endsWith("/index")) p = p.substring(0, p.length() - 6);
        return p.replace('/', '.');
    }

    public static boolean isTest(String rel) {
        String p = "/" + rel.toLowerCase(Locale.ROOT);
        String name = p.substring(p.lastIndexOf('/') + 1);
        return name.matches(".*\\.(test|spec|e2e|cy|stories)\\.[cm]?[jt]sx?$") || p.contains("/__tests__/") || p.contains("/__mocks__/")
                || p.contains("/test/") || p.contains("/tests/") || p.contains("/e2e/") || p.contains("/cypress/");
    }

    // =============================================================================================================

    enum Kind { CLASS, FUNCTION, TYPE, VALUE }

    record Decl(String qn, Kind kind) {}

    /** Where an import points: a module of the repository, a stylesheet, or a package. */
    record Target(String module, String cssModule, String pkg) {
        static Target internal(String path) { return new Target(path, null, null); }
        static Target stylesheet(String path) { return new Target(null, path, null); }
        static Target external(String pkg) { return new Target(null, null, pkg); }
    }

    /** Export reference: a local name, or {@code name} of another module. */
    record ExportRef(String local, String fromPath, String importedName) {}

    /** Pass-1 view of a module: top-level declarations and exports. */
    static final class ModuleInfo {
        final String path, qn;
        final Map<String, Decl> locals = new HashMap<>();
        final Map<String, ExportRef> exports = new HashMap<>();
        final List<String> starFrom = new ArrayList<>();
        final Map<String, Set<String>> classMethods = new HashMap<>();
        ExportRef defaultExport;

        ModuleInfo(String path) {
            this.path = path;
            this.qn = moduleName(path);
        }
    }

    @Override
    protected Map<String, ParsedSourceFile> analyse(Path root, List<Source> sources) {
        Set<String> files = new HashSet<>();
        sources.forEach(s -> files.add(s.path()));
        Resolver resolver = new Resolver(root, files);
        Map<String, ModuleInfo> modules = new HashMap<>();
        for (Source s : sources) modules.put(s.path(), declare(s, resolver));
        CssLanguageParser.CssIndex cssIndex = css.index(root);
        Map<String, ParsedSourceFile> out = new LinkedHashMap<>();
        for (Source s : sources) {
            try {
                out.put(s.path(), new Extractor(s, modules, resolver, cssIndex).extract());
            } catch (Exception e) {
                org.slf4j.LoggerFactory.getLogger(getClass()).debug("JavaScript file {} skipped: {}", s.path(), e.toString());
            }
        }
        return out;
    }

    // ---- pass 1 -------------------------------------------------------------------------------------------------

    private static ModuleInfo declare(Source s, Resolver resolver) {
        ModuleInfo m = new ModuleInfo(s.path());
        for (TSNode stmt : named(s.root())) {
            if (stmt.getType().equals("export_statement")) {
                TSNode decl = field(stmt, "declaration"), source = field(stmt, "source");
                boolean isDefault = hasToken(stmt, "default");
                if (decl != null) {
                    for (String name : declareStatement(s, m, decl)) {
                        if (isDefault) m.defaultExport = new ExportRef(name, null, null);
                        else m.exports.put(name, new ExportRef(name, null, null));
                    }
                } else if (source != null) {
                    Target t = resolver.resolve(s.path(), unquote(s.text(source)));
                    TSNode clause = firstNamed(stmt, "export_clause");
                    TSNode nsExport = firstNamed(stmt, "namespace_export");
                    if (clause != null && t != null && t.module() != null) {
                        for (TSNode spec : named(clause)) {
                            String name = s.text(field(spec, "name")), alias = field(spec, "alias") == null ? name : s.text(field(spec, "alias"));
                            if (alias.equals("default")) m.defaultExport = new ExportRef(null, t.module(), name);
                            else m.exports.put(alias, new ExportRef(null, t.module(), name));
                        }
                    } else if (nsExport == null && t != null && t.module() != null) {
                        m.starFrom.add(t.module());
                    }
                } else if (isDefault) {
                    TSNode value = field(stmt, "value");
                    if (value == null) value = named(stmt).stream().filter(n -> !COMMENTS.contains(n.getType())).reduce((a, b) -> b).orElse(null);
                    if (value != null && value.getType().equals("identifier")) m.defaultExport = new ExportRef(s.text(value), null, null);
                    else if (value != null && (FUNCTION_VALUES.contains(value.getType()) || value.getType().equals("class")
                            || value.getType().equals("function_declaration") || value.getType().equals("class_declaration"))) {
                        Kind k = value.getType().startsWith("class") ? Kind.CLASS : Kind.FUNCTION;
                        m.locals.put("default", new Decl(k == Kind.CLASS ? m.qn + ".default" : m.qn + "#default", k));
                        m.defaultExport = new ExportRef("default", null, null);
                    }
                } else {
                    TSNode clause = firstNamed(stmt, "export_clause");
                    if (clause != null) for (TSNode spec : named(clause)) {
                        String name = s.text(field(spec, "name")), alias = field(spec, "alias") == null ? name : s.text(field(spec, "alias"));
                        if (alias.equals("default")) m.defaultExport = new ExportRef(name, null, null);
                        else m.exports.put(alias, new ExportRef(name, null, null));
                    }
                }
            } else if (stmt.getType().equals("expression_statement")) {
                commonJsExport(s, m, stmt);
            } else {
                declareStatement(s, m, stmt);
            }
        }
        return m;
    }

    /** Registers the declarations of a statement; returns their names. */
    private static List<String> declareStatement(Source s, ModuleInfo m, TSNode n) {
        List<String> names = new ArrayList<>();
        switch (n.getType()) {
            case "function_declaration", "generator_function_declaration", "function_signature" -> {
                String name = s.text(field(n, "name"));
                if (!name.isEmpty()) { m.locals.put(name, new Decl(m.qn + "#" + name, Kind.FUNCTION)); names.add(name); }
            }
            case "class_declaration", "abstract_class_declaration", "class" -> {
                String name = s.text(field(n, "name"));
                if (name.isEmpty()) break;
                String qn = m.qn + "." + name;
                m.locals.put(name, new Decl(qn, Kind.CLASS));
                Set<String> methods = new HashSet<>();
                for (TSNode member : named(field(n, "body"))) {
                    TSNode mn = field(member, "name");
                    if (mn != null) methods.add(s.text(mn));
                }
                m.classMethods.put(qn, methods);
                names.add(name);
            }
            case "interface_declaration", "type_alias_declaration", "enum_declaration" -> {
                String name = s.text(field(n, "name"));
                if (!name.isEmpty()) { m.locals.put(name, new Decl(m.qn + "." + name, Kind.TYPE)); names.add(name); }
            }
            case "lexical_declaration", "variable_declaration" -> {
                for (TSNode d : named(n)) {
                    if (!d.getType().equals("variable_declarator")) continue;
                    TSNode nameNode = field(d, "name");
                    if (nameNode == null || !nameNode.getType().equals("identifier")) continue;
                    String name = s.text(nameNode);
                    Kind k = functionBody(field(d, "value")) != null ? Kind.FUNCTION
                            : field(d, "value") != null && field(d, "value").getType().equals("class") ? Kind.CLASS : Kind.VALUE;
                    m.locals.put(name, new Decl(k == Kind.CLASS ? m.qn + "." + name : m.qn + "#" + name, k));
                    names.add(name);
                }
            }
            default -> { }
        }
        return names;
    }

    /** {@code module.exports = X}, {@code module.exports = { a, b }}, {@code exports.a = function () {}}. */
    private static void commonJsExport(Source s, ModuleInfo m, TSNode stmt) {
        TSNode a = firstNamed(stmt, "assignment_expression");
        if (a == null) return;
        String left = s.text(field(a, "left"));
        TSNode right = field(a, "right");
        if (right == null) return;
        if (left.equals("module.exports")) {
            if (right.getType().equals("identifier")) m.defaultExport = new ExportRef(s.text(right), null, null);
            else if (right.getType().equals("object")) {
                for (TSNode p : named(right)) {
                    if (p.getType().equals("shorthand_property_identifier")) m.exports.put(s.text(p), new ExportRef(s.text(p), null, null));
                    else if (p.getType().equals("pair") && field(p, "value") != null && field(p, "value").getType().equals("identifier"))
                        m.exports.put(s.text(field(p, "key")), new ExportRef(s.text(field(p, "value")), null, null));
                }
            }
        } else if (left.matches("(module\\.)?exports\\.[A-Za-z_$][\\w$]*") && functionBody(right) != null) {
            String name = left.substring(left.lastIndexOf('.') + 1);
            m.locals.put(name, new Decl(m.qn + "#" + name, Kind.FUNCTION));
            m.exports.put(name, new ExportRef(name, null, null));
        }
    }

    /** Body of a function-valued expression ({@code () => …}, {@code memo((p) => …)}, {@code forwardRef(…)}), or null. */
    static TSNode functionBody(TSNode value) {
        if (value == null) return null;
        if (FUNCTION_VALUES.contains(value.getType())) return value;
        if (value.getType().equals("call_expression")) {
            TSNode args = field(value, "arguments");
            for (TSNode a : named(args)) if (FUNCTION_VALUES.contains(a.getType())) return a;
            for (TSNode a : named(args)) { TSNode inner = functionBody(a); if (inner != null) return inner; } // memo(forwardRef(...))
        }
        if (value.getType().equals("as_expression") || value.getType().equals("satisfies_expression") || value.getType().equals("parenthesized_expression")) {
            return functionBody(named(value).isEmpty() ? null : named(value).get(0));
        }
        return null;
    }

    static boolean hasToken(TSNode n, String token) {
        for (int i = 0; i < n.getChildCount(); i++) if (n.getChild(i).getType().equals(token)) return true;
        return false;
    }

    static String unquote(String s) {
        return s.length() >= 2 ? s.substring(1, s.length() - 1) : s;
    }

    // ---- module resolution ---------------------------------------------------------------------------------------

    /** Bundler-like resolution of import specifiers, including tsconfig/jsconfig {@code paths} and {@code baseUrl}. */
    static final class Resolver {
        private final Path root;
        private final Set<String> files;
        private final Map<String, Config> configByDir = new HashMap<>();

        record Alias(String prefix, String suffix, List<String> targets) {}

        record Config(String dir, String baseUrl, List<Alias> aliases) {}

        Resolver(Path root, Set<String> files) {
            this.root = root;
            this.files = files;
        }

        Target resolve(String fromPath, String spec) {
            if (spec.isEmpty()) return null;
            if (spec.startsWith(".") || spec.startsWith("/")) {
                String base = join(directory(fromPath), spec);
                return base == null ? null : file(base);
            }
            Config c = config(directory(fromPath));
            if (c != null) {
                for (Alias a : c.aliases()) {
                    if (!spec.startsWith(a.prefix()) || !spec.endsWith(a.suffix()) || spec.length() < a.prefix().length() + a.suffix().length()) continue;
                    String star = spec.substring(a.prefix().length(), spec.length() - a.suffix().length());
                    for (String t : a.targets()) {
                        String base = join(c.dir(), t.replace("*", star));
                        Target hit = base == null ? null : file(base);
                        if (hit != null) return hit;
                    }
                }
                if (c.baseUrl() != null) {
                    String base = join(c.dir(), c.baseUrl() + "/" + spec);
                    Target hit = base == null ? null : file(base);
                    if (hit != null) return hit;
                }
            }
            if (spec.startsWith("node:")) return Target.external(spec);
            String[] parts = spec.split("/");
            return Target.external(spec.startsWith("@") && parts.length > 1 ? parts[0] + "/" + parts[1] : parts[0]);
        }

        private Target file(String base) {
            String b = base.toLowerCase(Locale.ROOT);
            if (b.endsWith(".css") || b.endsWith(".scss") || b.endsWith(".sass") || b.endsWith(".less")) {
                return Files.isRegularFile(root.resolve(base)) ? Target.stylesheet(base) : null;
            }
            if (files.contains(base)) return Target.internal(base);
            String stripped = base.replaceAll("\\.(js|jsx|mjs|cjs)$", ""); // TS ESM: import './x.js' means x.ts
            for (String candidateBase : List.of(base, stripped)) {
                for (String ext : EXTS) if (files.contains(candidateBase + ext)) return Target.internal(candidateBase + ext);
                for (String ext : EXTS) if (files.contains(candidateBase + "/index" + ext)) return Target.internal(candidateBase + "/index" + ext);
            }
            return null;
        }

        /** Nearest tsconfig.json / jsconfig.json at or above {@code dir}. */
        private Config config(String dir) {
            if (configByDir.containsKey(dir)) return configByDir.get(dir);
            Config c = null;
            for (String name : List.of("tsconfig.json", "jsconfig.json", "tsconfig.app.json")) {
                Path f = root.resolve(dir.isEmpty() ? name : dir + "/" + name);
                if (!Files.isRegularFile(f)) continue;
                try {
                    JsonNode opts = LENIENT.readTree(Files.readString(f)).path("compilerOptions");
                    String baseUrl = opts.path("baseUrl").isString() ? opts.path("baseUrl").asString() : null;
                    List<Alias> aliases = new ArrayList<>();
                    for (var e : opts.path("paths").properties()) {
                        String key = e.getKey();
                        int star = key.indexOf('*');
                        List<String> targets = new ArrayList<>();
                        e.getValue().forEach(v -> targets.add((baseUrl == null ? "" : baseUrl + "/") + v.asString()));
                        aliases.add(new Alias(star < 0 ? key : key.substring(0, star), star < 0 ? "" : key.substring(star + 1), targets));
                    }
                    aliases.sort(Comparator.comparingInt((Alias a) -> -a.prefix().length()));
                    c = new Config(dir, baseUrl, aliases);
                    break;
                } catch (Exception e) {
                    // unreadable config: plain resolution
                }
            }
            if (c == null && !dir.isEmpty()) c = config(directory(dir));
            configByDir.put(dir, c);
            return c;
        }
    }

    // ---- pass 2 -------------------------------------------------------------------------------------------------

    /** What a local name stands for in a module. */
    record Binding(String qn, Kind kind, String module, boolean namespace, String cssModule, String externalPkg, String externalName) {}

    static final class Extractor {
        private final Source s;
        private final ModuleInfo m;
        private final Map<String, ModuleInfo> modules;
        private final Resolver resolver;
        private final CssLanguageParser.CssIndex cssIndex;
        private final Map<String, Binding> bindings = new HashMap<>();
        private final Emitter e = new Emitter();

        Extractor(Source s, Map<String, ModuleInfo> modules, Resolver resolver, CssLanguageParser.CssIndex cssIndex) {
            this.s = s;
            this.m = modules.get(s.path());
            this.modules = modules;
            this.resolver = resolver;
            this.cssIndex = cssIndex;
        }

        ParsedSourceFile extract() {
            m.locals.forEach((name, d) -> bindings.put(name, new Binding(d.qn(), d.kind(), null, false, null, null, null)));
            StringBuilder moduleText = new StringBuilder();
            List<TSNode> moduleLevel = new ArrayList<>();
            for (TSNode stmt : named(s.root())) {
                switch (stmt.getType()) {
                    case "import_statement" -> importStatement(stmt);
                    case "lexical_declaration", "variable_declaration" -> {
                        if (requireDeclaration(stmt)) break;
                        if (!declarators(stmt)) { moduleLevel.add(stmt); moduleText.append(normalise(s, stmt, COMMENTS)).append('\n'); }
                    }
                    default -> { }
                }
            }
            String fileName = s.path().substring(s.path().lastIndexOf('/') + 1);
            for (TSNode stmt : named(s.root())) {
                String t = stmt.getType();
                if (t.equals("import_statement") || t.equals("lexical_declaration") || t.equals("variable_declaration") || COMMENTS.contains(t)) continue;
                if (t.equals("export_statement")) exportStatement(stmt, moduleLevel, moduleText);
                else if (t.equals("expression_statement") && commonJsFunction(stmt)) continue;
                else if (!declaration(stmt, null)) { moduleLevel.add(stmt); moduleText.append(normalise(s, stmt, COMMENTS)).append('\n'); }
            }
            e.symbols.add(0, symbol(fileName.replaceAll("\\.[^.]+$", ""), m.qn, SymbolType.MODULE, null, s.root())
                    .startLine(1).endLine(Math.max(1, s.lines())).contentHash(hash(moduleText.toString())).build());
            for (TSNode n : moduleLevel) refs(n, m.qn, null, DependencyType.METHOD_CALL);
            return e.file(s, pathName(directory(s.path()), false), isTest(s.path()), "javascript");
        }

        // imports ------------------------------------------------------------------------------------------

        private void importStatement(TSNode stmt) {
            TSNode source = field(stmt, "source");
            if (source == null) return;
            Target t = resolver.resolve(s.path(), unquote(s.text(source)));
            if (t == null) return;
            e.dep(m.qn, importTarget(t), DependencyType.IMPORT, stmt);
            TSNode clause = firstNamed(stmt, "import_clause");
            for (TSNode c : named(clause)) {
                switch (c.getType()) {
                    case "identifier" -> bindings.put(s.text(c), defaultBinding(t));
                    case "namespace_import" -> {
                        TSNode id = firstNamed(c, "identifier");
                        if (id != null) bindings.put(s.text(id), namespaceBinding(t));
                    }
                    case "named_imports" -> {
                        for (TSNode spec : named(c)) {
                            if (!spec.getType().equals("import_specifier")) continue;
                            String name = s.text(field(spec, "name"));
                            String alias = field(spec, "alias") == null ? name : s.text(field(spec, "alias"));
                            bindings.put(alias, namedBinding(t, name));
                        }
                    }
                    default -> { }
                }
            }
        }

        /** {@code const x = require('./x')} / {@code const { a } = require('./x')}; true if the statement was one. */
        private boolean requireDeclaration(TSNode stmt) {
            boolean any = false;
            for (TSNode d : named(stmt)) {
                if (!d.getType().equals("variable_declarator")) continue;
                TSNode v = field(d, "value");
                if (v == null || !v.getType().equals("call_expression") || !s.text(field(v, "function")).equals("require")) continue;
                TSNode arg = named(field(v, "arguments")).stream().findFirst().orElse(null);
                if (arg == null || !arg.getType().equals("string")) continue;
                Target t = resolver.resolve(s.path(), unquote(s.text(arg)));
                if (t == null) continue;
                any = true;
                e.dep(m.qn, importTarget(t), DependencyType.IMPORT, stmt);
                TSNode name = field(d, "name");
                if (name.getType().equals("identifier")) bindings.put(s.text(name), namespaceBinding(t));
                else if (name.getType().equals("object_pattern")) {
                    for (TSNode p : named(name)) {
                        if (p.getType().equals("shorthand_property_identifier_pattern")) bindings.put(s.text(p), namedBinding(t, s.text(p)));
                        else if (p.getType().equals("pair_pattern")) bindings.put(s.text(field(p, "value")), namedBinding(t, s.text(field(p, "key"))));
                    }
                }
            }
            return any;
        }

        private String importTarget(Target t) {
            if (t.module() != null) return moduleName(t.module());
            if (t.cssModule() != null) return CssLanguageParser.moduleName(t.cssModule());
            return "npm:" + t.pkg();
        }

        private Binding defaultBinding(Target t) {
            if (t.cssModule() != null) return new Binding(null, Kind.VALUE, null, false, t.cssModule(), null, null);
            if (t.pkg() != null) return new Binding(null, Kind.VALUE, null, true, null, t.pkg(), null);
            Decl d = resolveExport(t.module(), "default", 0);
            if (d != null) return new Binding(d.qn(), d.kind(), null, false, null, null, null);
            return new Binding(null, Kind.VALUE, t.module(), true, null, null, null); // CommonJS module object
        }

        private Binding namespaceBinding(Target t) {
            if (t.cssModule() != null) return new Binding(null, Kind.VALUE, null, false, t.cssModule(), null, null);
            if (t.pkg() != null) return new Binding(null, Kind.VALUE, null, true, null, t.pkg(), null);
            Decl d = modules.get(t.module()) != null && modules.get(t.module()).defaultExport != null ? resolveExport(t.module(), "default", 0) : null;
            return new Binding(d == null ? null : d.qn(), d == null ? Kind.VALUE : d.kind(), t.module(), true, null, null, null);
        }

        private Binding namedBinding(Target t, String name) {
            if (t.pkg() != null) return new Binding(null, Kind.VALUE, null, false, null, t.pkg(), name);
            if (t.module() == null) return new Binding(null, Kind.VALUE, null, false, null, null, null);
            Decl d = resolveExport(t.module(), name, 0);
            return d == null ? new Binding(null, Kind.VALUE, null, false, null, null, null) : new Binding(d.qn(), d.kind(), null, false, null, null, null);
        }

        /** Declaration exported as {@code name} by a module, following re-exports. */
        private Decl resolveExport(String path, String name, int depth) {
            ModuleInfo mod = modules.get(path);
            if (mod == null || depth > 12) return null;
            ExportRef ref = name.equals("default") ? mod.defaultExport : mod.exports.get(name);
            if (ref != null) {
                if (ref.local() != null) return mod.locals.get(ref.local());
                return resolveExport(ref.fromPath(), ref.importedName(), depth + 1);
            }
            if (!name.equals("default")) for (String star : mod.starFrom) {
                Decl d = resolveExport(star, name, depth + 1);
                if (d != null) return d;
            }
            return null;
        }

        // declarations ---------------------------------------------------------------------------------------

        private void exportStatement(TSNode stmt, List<TSNode> moduleLevel, StringBuilder moduleText) {
            TSNode decl = field(stmt, "declaration");
            TSNode source = field(stmt, "source");
            if (source != null) {
                Target t = resolver.resolve(s.path(), unquote(s.text(source)));
                if (t != null) e.dep(m.qn, importTarget(t), DependencyType.IMPORT, stmt);
                return;
            }
            if (decl != null) {
                if (!declaration(decl, hasToken(stmt, "default") ? "default" : null)) { moduleLevel.add(decl); moduleText.append(normalise(s, decl, COMMENTS)); }
                return;
            }
            if (hasToken(stmt, "default")) {
                TSNode value = field(stmt, "value");
                if (value == null) value = named(stmt).stream().filter(n -> !COMMENTS.contains(n.getType())).reduce((a, b) -> b).orElse(null);
                if (value == null) return;
                if (value.getType().equals("class")) classDecl(value, "default");
                else if (FUNCTION_VALUES.contains(value.getType())) function("default", m.qn + "#default", value, value, null);
                else { moduleLevel.add(value); moduleText.append(normalise(s, value, COMMENTS)); }
            }
        }

        /** {@code exports.run = function () {}} → function {@code module#run}. */
        private boolean commonJsFunction(TSNode stmt) {
            TSNode a = firstNamed(stmt, "assignment_expression");
            if (a == null) return false;
            String left = s.text(field(a, "left"));
            TSNode body = functionBody(field(a, "right"));
            if (body == null || !left.matches("(module\\.)?exports\\.[A-Za-z_$][\\w$]*")) return false;
            String name = left.substring(left.lastIndexOf('.') + 1);
            function(name, m.qn + "#" + name, stmt, body, null);
            return true;
        }

        /** Emits a top-level declaration; false if the statement declares nothing analysable. */
        private boolean declaration(TSNode n, String defaultName) {
            switch (n.getType()) {
                case "function_declaration", "generator_function_declaration" -> {
                    String name = field(n, "name") == null ? defaultName : s.text(field(n, "name"));
                    if (name == null) return false;
                    function(name, m.qn + "#" + name, n, n, null);
                    return true;
                }
                case "class_declaration", "abstract_class_declaration", "class" -> {
                    String name = field(n, "name") == null ? defaultName : s.text(field(n, "name"));
                    if (name == null) return false;
                    classDecl(n, name);
                    return true;
                }
                case "interface_declaration", "type_alias_declaration", "enum_declaration" -> {
                    String name = s.text(field(n, "name"));
                    String qn = m.qn + "." + name;
                    e.symbols.add(symbol(name, qn, n.getType().equals("enum_declaration") ? SymbolType.ENUM : SymbolType.INTERFACE, m.qn, n)
                            .contentHash(hash(normalise(s, n, COMMENTS))).build());
                    TSNode ext = firstNamed(n, "extends_type_clause");
                    for (TSNode t : descendants(ext, Set.of("type_identifier", "identifier"), Set.of())) {
                        String target = resolveName(s.text(t));
                        if (target != null) e.dep(qn, target, DependencyType.EXTENDS, t);
                    }
                    if (!n.getType().equals("interface_declaration") || ext == null) typeRefs(field(n, "value"), qn, DependencyType.FIELD_TYPE);
                    typeRefs(field(n, "body"), qn, DependencyType.FIELD_TYPE);
                    return true;
                }
                case "lexical_declaration", "variable_declaration" -> {
                    return declarators(n);
                }
                default -> {
                    return false;
                }
            }
        }

        /** Function-valued {@code const}s become functions; true if every declarator was one. */
        private boolean declarators(TSNode n) {
            boolean all = true, any = false;
            for (TSNode d : named(n)) {
                if (!d.getType().equals("variable_declarator")) continue;
                TSNode nameNode = field(d, "name"), value = field(d, "value");
                TSNode body = functionBody(value);
                if (body != null && nameNode != null && nameNode.getType().equals("identifier")) {
                    String name = s.text(nameNode);
                    function(name, m.qn + "#" + name, d, body, null);
                    any = true;
                } else if (value != null && value.getType().equals("class") && nameNode != null) {
                    classDecl(value, s.text(nameNode));
                    any = true;
                } else all = false;
            }
            return any && all;
        }

        private void function(String name, String qn, TSNode outer, TSNode fn, String classQn) {
            TSNode params = field(fn, "parameters");
            if (params == null) params = field(fn, "parameter");
            TSNode ret = field(fn, "return_type");
            TSNode body = field(fn, "body");
            List<String> annotations = new ArrayList<>();
            if (Character.isUpperCase(name.charAt(0)) && classQn == null && body != null
                    && (JSX.contains(body.getType()) || !descendants(body, JSX, Set.of("class_declaration")).isEmpty())) annotations.add("component");
            if (name.matches("use[A-Z0-9].*") && classQn == null) annotations.add("hook");
            boolean isStatic = classQn != null && hasToken(outer, "static");
            String visibility = name.startsWith("#") ? "private" : "public";
            TSNode access = firstNamed(outer, "accessibility_modifier");
            if (access != null) visibility = s.text(access);
            e.symbols.add(symbol(name, qn, classQn == null ? SymbolType.FUNCTION : name.equals("constructor") ? SymbolType.CONSTRUCTOR : SymbolType.METHOD,
                    classQn == null ? m.qn : classQn, outer)
                    .parameters(params == null ? "" : collapse(s.text(params)).replaceAll("^\\(|\\)$", ""))
                    .returnType(ret == null ? null : collapse(s.text(ret)).replaceFirst("^:\\s*", ""))
                    .visibility(visibility).isStatic(isStatic).isAbstract(hasToken(outer, "abstract"))
                    .annotations(annotations).annotationText(annotations.isEmpty() ? null : String.join("\n", annotations))
                    .contentHash(hash(normalise(s, outer, COMMENTS)))
                    .complexity(complexity(s, body, DECISIONS, Set.of("class_declaration", "class"), LOGICAL))
                    .build());
            typeRefs(params, qn, DependencyType.PARAMETER_TYPE);
            typeRefs(ret, qn, DependencyType.RETURN_TYPE);
            if (body != null) refs(body, qn, classQn, DependencyType.METHOD_CALL);
        }

        private void classDecl(TSNode n, String name) {
            String qn = m.qn + "." + name;
            List<String> annotations = new ArrayList<>();
            for (TSNode h : named(firstNamed(n, "class_heritage"))) {
                if (h.getType().equals("extends_clause") || !h.getType().endsWith("_clause")) {
                    TSNode value = h.getType().equals("extends_clause") ? field(h, "value") : h;
                    if (value == null) continue;
                    if (COMPONENT_BASE.matcher(s.text(value)).matches()) annotations.add("component");
                    String target = expressionTarget(value);
                    if (target != null) e.dep(qn, target, DependencyType.EXTENDS, value);
                } else if (h.getType().equals("implements_clause")) {
                    for (TSNode t : named(h)) {
                        String target = resolveName(s.text(t).replaceAll("<.*$", ""));
                        if (target != null) e.dep(qn, target, DependencyType.IMPLEMENTS, t);
                    }
                }
            }
            for (TSNode d : named(n)) if (d.getType().equals("decorator")) annotations.add(collapse(s.text(d)).substring(1));
            e.symbols.add(symbol(name, qn, SymbolType.CLASS, m.qn, n).isAbstract(n.getType().equals("abstract_class_declaration"))
                    .annotations(annotations).annotationText(annotations.isEmpty() ? null : String.join("\n", annotations))
                    .contentHash(hash(header(n))).build());
            for (TSNode member : named(field(n, "body"))) {
                String t = member.getType();
                TSNode nameNode = field(member, "name");
                if (nameNode == null) continue;
                String mname = s.text(nameNode);
                if (t.equals("method_definition") || t.equals("abstract_method_signature") || t.equals("method_signature")) {
                    function(mname, qn + "#" + mname, member, member, qn);
                } else if ((t.equals("public_field_definition") || t.equals("field_definition")) && functionBody(field(member, "value")) != null) {
                    function(mname, qn + "#" + mname, member, functionBody(field(member, "value")), qn);
                } else if (t.equals("public_field_definition") || t.equals("field_definition")) {
                    typeRefs(field(member, "type"), qn, DependencyType.FIELD_TYPE);
                    TSNode value = field(member, "value");
                    if (value != null) refs(value, qn, qn, DependencyType.METHOD_CALL);
                }
            }
        }

        /** Class text without its body (name, heritage, decorators): changes to members count on the members. */
        private String header(TSNode n) {
            TSNode body = field(n, "body");
            int end = body == null ? n.getEndByte() : body.getStartByte();
            return collapse(new String(s.bytes(), n.getStartByte(), end - n.getStartByte(), java.nio.charset.StandardCharsets.UTF_8));
        }

        // references -------------------------------------------------------------------------------------------

        private void refs(TSNode n, String source, String classQn, DependencyType callType) {
            if (n == null) return;
            switch (n.getType()) {
                case "call_expression" -> {
                    TSNode fn = field(n, "function");
                    String target = calleeTarget(fn, classQn);
                    if (target != null) e.dep(source, target, constructorTarget(target) ? DependencyType.CONSTRUCTOR_CALL : callType, n);
                }
                case "new_expression" -> {
                    String target = expressionTarget(field(n, "constructor"));
                    if (target != null) e.dep(source, target, DependencyType.CONSTRUCTOR_CALL, n);
                }
                case "jsx_opening_element", "jsx_self_closing_element" -> jsxElement(n, source);
                case "member_expression", "subscript_expression" -> {
                    String target = cssModuleMember(n);
                    if (target != null) { e.dep(source, target, DependencyType.FIELD_ACCESS, n); return; }
                }
                case "string", "template_string" -> {
                    for (String table : EmbeddedSql.tables(s.text(n).replaceAll("^[`'\"]|[`'\"]$", "")))
                        e.dep(source, table, DependencyType.FIELD_ACCESS, n);
                    return;
                }
                case "class_declaration", "class" -> { return; } // local classes are not analysed
                default -> { }
            }
            for (TSNode c : named(n)) refs(c, source, classQn, callType);
        }

        private void jsxElement(TSNode n, String source) {
            TSNode name = field(n, "name");
            if (name != null) {
                String text = s.text(name);
                if (!text.isEmpty() && Character.isUpperCase(text.charAt(0)) || name.getType().equals("member_expression")) {
                    String target = expressionTarget(name);
                    if (target != null) e.dep(source, target, DependencyType.CONSTRUCTOR_CALL, n);
                }
            }
            for (TSNode attr : named(n)) {
                if (!attr.getType().equals("jsx_attribute") || attr.getNamedChildCount() < 2) continue;
                String attrName = s.text(attr.getNamedChild(0));
                TSNode value = attr.getNamedChild(1);
                if ((attrName.equals("className") || attrName.equals("class")) && value.getType().equals("string")) {
                    for (String t : cssIndex.classTargets(unquote(s.text(value)))) e.dep(source, t, DependencyType.FIELD_ACCESS, attr);
                }
            }
        }

        /** {@code styles.card} / {@code styles['card']} on a CSS Module import → its selector. */
        private String cssModuleMember(TSNode n) {
            TSNode obj = field(n, "object");
            if (obj == null || !obj.getType().equals("identifier")) return null;
            Binding b = bindings.get(s.text(obj));
            if (b == null || b.cssModule() == null) return null;
            TSNode prop = n.getType().equals("member_expression") ? field(n, "property") : field(n, "index");
            if (prop == null) return null;
            String name = prop.getType().equals("string") ? unquote(s.text(prop)) : s.text(prop);
            String qn = CssLanguageParser.moduleName(b.cssModule()) + "#." + name;
            return cssIndex.targets("." + name).contains(qn) ? qn : null;
        }

        private boolean constructorTarget(String qn) {
            for (ModuleInfo mod : List.of(m)) for (Decl d : mod.locals.values()) if (d.qn().equals(qn)) return d.kind() == Kind.CLASS;
            return false;
        }

        private String calleeTarget(TSNode fn, String classQn) {
            if (fn == null) return null;
            if (fn.getType().equals("member_expression")) {
                TSNode obj = field(fn, "object");
                String prop = s.text(field(fn, "property"));
                if (obj != null && obj.getType().equals("this") && classQn != null) {
                    Set<String> methods = m.classMethods.get(classQn);
                    return methods != null && methods.contains(prop) ? classQn + "#" + prop : null;
                }
            }
            if (fn.getType().equals("identifier") && s.text(fn).equals("require")) return null;
            return expressionTarget(fn);
        }

        /** {@code X}, {@code ns.X}, {@code Cls.staticMethod} → qualified target, or null when not certain. */
        private String expressionTarget(TSNode n) {
            if (n == null) return null;
            String t = n.getType();
            if (t.equals("identifier") || t.equals("type_identifier")) return resolveName(s.text(n));
            if (t.equals("member_expression") || t.equals("nested_identifier") || t.equals("jsx_namespace_name")) {
                TSNode obj = field(n, "object");
                TSNode prop = field(n, "property");
                if (obj == null || prop == null || !obj.getType().equals("identifier")) return null;
                Binding b = bindings.get(s.text(obj));
                String p = s.text(prop);
                if (b == null) return null;
                if (b.namespace() && b.module() != null) {
                    Decl d = resolveExport(b.module(), p, 0);
                    if (d != null) return d.qn();
                }
                if (b.externalPkg() != null) return "npm:" + b.externalPkg() + "#" + (b.externalName() == null ? p : b.externalName() + "." + p);
                if (b.qn() != null && b.kind() == Kind.CLASS) {
                    Set<String> methods = methodsOf(b.qn());
                    if (methods != null && methods.contains(p)) return b.qn() + "#" + p;
                }
            }
            return null;
        }

        private Set<String> methodsOf(String classQn) {
            for (ModuleInfo mod : modules.values()) {
                Set<String> s = mod.classMethods.get(classQn);
                if (s != null) return s;
            }
            return null;
        }

        private String resolveName(String name) {
            Binding b = bindings.get(name);
            if (b == null) return null;
            if (b.qn() != null && b.kind() != Kind.VALUE) return b.qn();
            if (b.externalPkg() != null) return "npm:" + b.externalPkg() + (b.externalName() == null ? "" : "#" + b.externalName());
            return null;
        }

        private void typeRefs(TSNode n, String source, DependencyType type) {
            if (n == null) return;
            for (TSNode t : descendants(n, Set.of("type_identifier"), Set.of())) {
                String target = resolveName(s.text(t));
                if (target != null && !target.startsWith("npm:")) e.dep(source, target, type, t);
            }
        }
    }
}

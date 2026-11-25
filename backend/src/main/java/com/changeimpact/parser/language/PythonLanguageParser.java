package com.changeimpact.parser.language;

import com.changeimpact.common.config.AppProperties;
import com.changeimpact.diff.model.FileCategory;
import com.changeimpact.parser.model.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.treesitter.TSNode;
import org.treesitter.TSParser;
import org.treesitter.TSTree;
import org.treesitter.TreeSitterPython;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;
import java.util.stream.Stream;

/**
 * Experimental Python support (P6-7), parsed with tree-sitter (JNI binding).
 *
 * <p>Model: a file is a {@code MODULE} ({@code pkg.sub.mod}; {@code __init__.py} is the package itself), with
 * {@code CLASS}es ({@code pkg.sub.mod.Cls}, nested {@code ...Cls.Inner}), {@code METHOD}s ({@code ...Cls#name},
 * {@code __init__} as {@code CONSTRUCTOR}) and module-level {@code FUNCTION}s ({@code pkg.sub.mod#name}).
 *
 * <p>Dependencies are resolved by name in two passes — first every module, class and function of the repository is
 * indexed, then imports, base classes, decorators and calls are resolved through the module's imports and local
 * definitions: {@code IMPORT}, {@code EXTENDS}, {@code ANNOTATION} (decorators), {@code METHOD_CALL} (functions,
 * {@code self.m()} / {@code cls.m()}, {@code module.f()}), {@code CONSTRUCTOR_CALL} ({@code Cls()}). Calls on values of
 * unknown type ({@code obj.m()}), builtins and dynamic constructs are not resolved — nothing is guessed — so the
 * graph under-approximates Python's dynamic dispatch.
 */
@Component
@Slf4j
public class PythonLanguageParser implements LanguageParser {

    static final Set<String> SKIP_DIRS = Set.of(".git", ".hg", ".svn", "venv", ".venv", "env", ".env", "node_modules",
            "__pycache__", "build", "dist", ".tox", ".nox", ".eggs", "site-packages", ".mypy_cache", ".pytest_cache");
    static final long MAX_FILE_BYTES = 1_000_000;

    private final AppProperties appProperties;
    private final boolean available;

    public PythonLanguageParser(AppProperties appProperties) {
        this.appProperties = appProperties;
        boolean ok;
        try (TSParser p = new TSParser()) {
            ok = p.setLanguage(new TreeSitterPython());
        } catch (Throwable t) { // UnsatisfiedLinkError on platforms without a bundled native library
            log.warn("tree-sitter Python is not available here: {}", t.toString());
            ok = false;
        }
        this.available = ok;
    }

    @Override public String language() { return "python"; }
    @Override public Set<String> extensions() { return Set.of(".py"); }
    @Override public boolean experimental() { return false; }
    /** 2: parameter and return-type text kept as written (whitespace collapsed), receiver type inference. */
    /** 3: plus tables of SQL in string literals. */
    @Override public int version() { return 3; }
    @Override public boolean available() { return available; }
    @Override public boolean incremental() { return false; }

    @Override
    public Session open(Path root) {
        return new PySession(root, appProperties.getIngestion().getExcludedPaths(),
                appProperties.getIngestion().getMaxFilesPerRepository());
    }

    // ------------------------------------------------------------------------------------------------------------

    /** Parses all files of the tree on first use (pass 1: declarations index; pass 2: dependencies). */
    static final class PySession implements Session {
        private final Path root;
        private final List<String> excluded;
        private final int maxFiles;
        private List<Path> files;
        private Map<Path, ParsedSourceFile> results;

        PySession(Path root, List<String> excluded, int maxFiles) {
            this.root = root;
            this.excluded = excluded == null ? List.of() : excluded;
            this.maxFiles = maxFiles;
        }

        @Override
        public List<Path> sourceFiles() {
            if (files == null) {
                try (Stream<Path> s = Files.walk(root)) {
                    files = s.filter(Files::isRegularFile)
                            .filter(p -> p.getFileName().toString().endsWith(".py"))
                            .filter(p -> !skipped(root.relativize(p)))
                            .sorted()
                            .limit(maxFiles)
                            .toList();
                } catch (Exception e) {
                    log.warn("Could not scan Python files under {}: {}", root, e.getMessage());
                    files = List.of();
                }
            }
            return files;
        }

        private boolean skipped(Path relative) {
            String rel = relative.toString().replace('\\', '/');
            for (Path part : relative) if (SKIP_DIRS.contains(part.toString())) return true;
            for (String ex : excluded) if (!ex.isBlank() && (rel.startsWith(ex) || rel.contains("/" + ex))) return true;
            return false;
        }

        @Override
        public Optional<ParsedSourceFile> parse(Path file) {
            if (results == null) results = parseAll();
            return Optional.ofNullable(results.get(file));
        }

        private Map<Path, ParsedSourceFile> parseAll() {
            Map<Path, ModuleSource> sources = new LinkedHashMap<>();
            Index index = new Index();
            try (TSParser parser = new TSParser()) {
                parser.setLanguage(new TreeSitterPython());
                for (Path f : sourceFiles()) {
                    try {
                        if (Files.size(f) > MAX_FILE_BYTES) continue;
                        String text = com.changeimpact.parser.language.ts.TreeSitterParser.decode(Files.readAllBytes(f));
                        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
                        TSTree tree = parser.parseString(null, text);
                        String rel = root.relativize(f).toString().replace('\\', '/');
                        ModuleSource m = new ModuleSource(rel, moduleName(rel), bytes, text, tree, tree.getRootNode());
                        sources.put(f, m);
                        index.add(m);
                    } catch (Exception e) {
                        log.debug("Python file {} skipped: {}", f, e.getMessage());
                    }
                }
                Map<Path, ParsedSourceFile> out = new LinkedHashMap<>();
                sources.forEach((f, m) -> out.put(f, new Extractor(m, index).extract()));
                return out;
            }
        }
    }

    /** {@code src/pkg/mod.py} → {@code pkg.mod}; {@code pkg/__init__.py} → {@code pkg}. */
    static String moduleName(String relativePath) {
        String p = relativePath.substring(0, relativePath.length() - 3);
        if (p.startsWith("src/") || p.startsWith("lib/")) p = p.substring(4);
        if (p.endsWith("/__init__")) p = p.substring(0, p.length() - 9);
        else if (p.equals("__init__")) p = "__init__";
        return p.replace('/', '.');
    }

    record ModuleSource(String path, String module, byte[] bytes, String text, TSTree tree, TSNode root) {
        String text(TSNode n) {
            return new String(bytes, n.getStartByte(), n.getEndByte() - n.getStartByte(), StandardCharsets.UTF_8);
        }

        boolean isPackageInit() {
            return path.endsWith("__init__.py");
        }

        String packageName() {
            if (isPackageInit()) return module;
            int dot = module.lastIndexOf('.');
            return dot < 0 ? "" : module.substring(0, dot);
        }
    }

    /** Every module, class (dotted) and function/method of the repository. */
    static final class Index {
        final Set<String> modules = new HashSet<>();
        final Set<String> classes = new HashSet<>();     // pkg.mod.Cls, pkg.mod.Cls.Inner
        final Set<String> callables = new HashSet<>();   // pkg.mod#f, pkg.mod.Cls#m

        void add(ModuleSource m) {
            modules.add(m.module());
            collect(m, m.root(), m.module(), true);
        }

        private void collect(ModuleSource m, TSNode block, String scope, boolean moduleLevel) {
            for (int i = 0; i < block.getNamedChildCount(); i++) {
                TSNode n = definition(block.getNamedChild(i));
                if (n == null) continue;
                String name = m.text(n.getChildByFieldName("name"));
                if (n.getType().equals("class_definition")) {
                    String q = scope + "." + name;
                    classes.add(q);
                    collect(m, n.getChildByFieldName("body"), q, false);
                } else {
                    callables.add(scope + "#" + name);
                }
            }
        }

        /**
         * Maps a dotted Python name to this platform's qualified name: the longest known module prefix, then classes,
         * then a function or method. Unknown names (other libraries) are returned unchanged.
         */
        String qualify(String dotted) {
            String module = null;
            for (String candidate = dotted; ; ) {
                if (modules.contains(candidate)) { module = candidate; break; }
                int dot = candidate.lastIndexOf('.');
                if (dot < 0) break;
                candidate = candidate.substring(0, dot);
            }
            if (module == null) return dotted;
            if (module.length() == dotted.length()) return module;
            String[] rest = dotted.substring(module.length() + 1).split("\\.");
            String owner = module;
            for (int i = 0; i < rest.length; i++) {
                String asClass = owner + "." + rest[i];
                if (classes.contains(asClass)) { owner = asClass; continue; }
                String member = owner + "#" + rest[i];
                return i == rest.length - 1 ? member : member; // attribute of a callable: keep the callable
            }
            return owner;
        }

        boolean isClass(String qualified) {
            return classes.contains(qualified);
        }
    }

    // ------------------------------------------------------------------------------------------------------------

    /** Symbols and dependencies of one module. */
    static final class Extractor {
        private final ModuleSource m;
        private final Index index;
        private final Map<String, String> aliases = new HashMap<>(); // local name → dotted target
        private final Map<String, String> local = new HashMap<>();   // top-level def/class name → qualified name
        private final List<ExtractedSymbol> symbols = new ArrayList<>();
        private final List<ExtractedDependency> deps = new ArrayList<>();

        Extractor(ModuleSource m, Index index) {
            this.m = m;
            this.index = index;
        }

        ParsedSourceFile extract() {
            TSNode root = m.root();
            for (int i = 0; i < root.getNamedChildCount(); i++) {
                TSNode n = definition(root.getNamedChild(i));
                if (n == null) continue;
                String name = m.text(n.getChildByFieldName("name"));
                local.put(name, n.getType().equals("class_definition") ? m.module() + "." + name : m.module() + "#" + name);
            }
            imports(root);

            symbols.add(ExtractedSymbol.builder()
                    .simpleName(m.module().substring(m.module().lastIndexOf('.') + 1))
                    .qualifiedName(m.module()).symbolType(SymbolType.MODULE)
                    .visibility("public").annotations(List.of())
                    .startLine(1).endLine(root.getEndPoint().getRow() + 1).startColumn(1).endColumn(1)
                    .resolutionStatus(ResolutionStatus.RESOLVED)
                    .contentHash(hash(moduleLevelText(root)))
                    .build());
            calls(root, m.module(), null, localTypes(root, null));
            for (int i = 0; i < root.getNamedChildCount(); i++) declaration(root.getNamedChild(i), m.module(), null);

            int loc = (int) m.text().lines().count();
            return ParsedSourceFile.builder()
                    .relativePath(m.path()).packageName(m.packageName())
                    .linesOfCode(loc).sizeBytes(m.bytes().length)
                    .isTest(FileCategory.of(m.path()) == FileCategory.PYTHON_TEST)
                    .symbols(symbols).dependencies(deps).language("python")
                    .build();
        }

        // ---- imports ------------------------------------------------------------------------------------------

        private void imports(TSNode root) {
            for (int i = 0; i < root.getNamedChildCount(); i++) {
                TSNode s = root.getNamedChild(i);
                switch (s.getType()) {
                    case "import_statement" -> {
                        for (int j = 0; j < s.getNamedChildCount(); j++) {
                            TSNode c = s.getNamedChild(j);
                            if (c.getType().equals("aliased_import")) {
                                String target = m.text(c.getChildByFieldName("name"));
                                aliases.put(m.text(c.getChildByFieldName("alias")), target);
                                importDep(target, s);
                            } else if (c.getType().equals("dotted_name")) {
                                String target = m.text(c);
                                aliases.put(target, target);
                                aliases.putIfAbsent(target.split("\\.")[0], target.split("\\.")[0]);
                                importDep(target, s);
                            }
                        }
                    }
                    case "import_from_statement" -> {
                        TSNode moduleNode = s.getChildByFieldName("module_name");
                        String from = moduleNode == null || moduleNode.isNull() ? "" : resolveRelative(m.text(moduleNode));
                        boolean any = false;
                        for (int j = 0; j < s.getNamedChildCount(); j++) {
                            TSNode c = s.getNamedChild(j);
                            if (moduleNode != null && !moduleNode.isNull() && TSNode.eq(c, moduleNode)) continue;
                            String name, alias;
                            if (c.getType().equals("aliased_import")) {
                                name = m.text(c.getChildByFieldName("name"));
                                alias = m.text(c.getChildByFieldName("alias"));
                            } else if (c.getType().equals("dotted_name")) {
                                name = m.text(c);
                                alias = name;
                            } else continue;
                            String target = from.isEmpty() ? name : from + "." + name;
                            aliases.put(alias, target);
                            importDep(target, s);
                            any = true;
                        }
                        if (!any && !from.isEmpty()) importDep(from, s); // from x import *
                    }
                    default -> { }
                }
            }
        }

        /** {@code .x} / {@code ..x} relative to this module's package. */
        private String resolveRelative(String name) {
            if (!name.startsWith(".")) return name;
            int level = 0;
            while (level < name.length() && name.charAt(level) == '.') level++;
            String rest = name.substring(level);
            String base = m.packageName();
            for (int i = 1; i < level && !base.isEmpty(); i++) {
                int dot = base.lastIndexOf('.');
                base = dot < 0 ? "" : base.substring(0, dot);
            }
            return base.isEmpty() ? rest : rest.isEmpty() ? base : base + "." + rest;
        }

        private void importDep(String dotted, TSNode at) {
            dep(m.module(), index.qualify(dotted), DependencyType.IMPORT, at);
        }

        // ---- declarations -------------------------------------------------------------------------------------

        private void declaration(TSNode stmt, String scope, String className) {
            List<String> decorators = new ArrayList<>();
            TSNode n = stmt;
            if (stmt.getType().equals("decorated_definition")) {
                for (int i = 0; i < stmt.getNamedChildCount(); i++) {
                    TSNode d = stmt.getNamedChild(i);
                    if (d.getType().equals("decorator")) decorators.add(m.text(d).substring(1).strip());
                }
                n = stmt.getChildByFieldName("definition");
            }
            if (n == null || n.isNull()) return;
            if (n.getType().equals("class_definition")) classDef(stmt, n, scope, decorators);
            else if (n.getType().equals("function_definition")) functionDef(stmt, n, scope, className, decorators);
        }

        private void classDef(TSNode outer, TSNode n, String scope, List<String> decorators) {
            String name = m.text(n.getChildByFieldName("name"));
            String q = scope + "." + name;
            TSNode body = n.getChildByFieldName("body");
            TSNode supers = n.getChildByFieldName("superclasses");
            symbols.add(ExtractedSymbol.builder()
                    .simpleName(name).qualifiedName(q).symbolType(SymbolType.CLASS).parentQualifiedName(scope)
                    .visibility(visibility(name)).annotations(decorators)
                    .isAbstract(supers != null && !supers.isNull() && m.text(supers).contains("ABC"))
                    .startLine(row(outer)).endLine(n.getEndPoint().getRow() + 1)
                    .startColumn(n.getStartPoint().getColumn() + 1).endColumn(n.getEndPoint().getColumn() + 1)
                    .resolutionStatus(ResolutionStatus.RESOLVED)
                    .contentHash(hash(String.join("\n", decorators) + "\nclass " + name + (supers == null || supers.isNull() ? "" : normalise(supers))))
                    .annotationText(decorators.isEmpty() ? null : String.join("\n", decorators))
                    .build());
            if (supers != null && !supers.isNull()) {
                for (int i = 0; i < supers.getNamedChildCount(); i++) {
                    TSNode b = supers.getNamedChild(i);
                    if (b.getType().equals("keyword_argument")) continue; // metaclass=...
                    String target = resolveName(m.text(b), q);
                    if (target != null) dep(q, target, DependencyType.EXTENDS, b);
                }
            }
            decoratorDeps(outer, q);
            for (int i = 0; i < body.getNamedChildCount(); i++) declaration(body.getNamedChild(i), q, q);
            calls(body, q, q, Map.of()); // class-level statements (attribute initialisers)
        }

        private void functionDef(TSNode outer, TSNode n, String scope, String className, List<String> decorators) {
            String name = m.text(n.getChildByFieldName("name"));
            boolean method = className != null;
            String q = scope + "#" + name;
            TSNode params = n.getChildByFieldName("parameters");
            TSNode ret = n.getChildByFieldName("return_type");
            TSNode body = n.getChildByFieldName("body");
            boolean isStatic = decorators.stream().anyMatch(d -> d.equals("staticmethod") || d.equals("classmethod"));
            symbols.add(ExtractedSymbol.builder()
                    .simpleName(name).qualifiedName(q)
                    .symbolType(!method ? SymbolType.FUNCTION : name.equals("__init__") ? SymbolType.CONSTRUCTOR : SymbolType.METHOD)
                    .parentQualifiedName(scope)
                    .returnType(ret == null || ret.isNull() ? null : m.text(ret).replaceAll("\\s+", " ").strip())
                    .parameters(parameters(params, method && !decorators.contains("staticmethod")))
                    .visibility(visibility(name)).isStatic(isStatic)
                    .isAbstract(decorators.stream().anyMatch(d -> d.endsWith("abstractmethod")))
                    .annotations(decorators)
                    .startLine(row(outer)).endLine(n.getEndPoint().getRow() + 1)
                    .startColumn(n.getStartPoint().getColumn() + 1).endColumn(n.getEndPoint().getColumn() + 1)
                    .resolutionStatus(ResolutionStatus.RESOLVED)
                    .contentHash(hash(String.join("\n", decorators) + "\n" + normalise(n)))
                    .annotationText(decorators.isEmpty() ? null : String.join("\n", decorators))
                    .complexity(1 + decisions(body))
                    .build());
            decoratorDeps(outer, q);
            calls(body, q, className, localTypes(body, params));
        }

        private void decoratorDeps(TSNode outer, String source) {
            if (!outer.getType().equals("decorated_definition")) return;
            for (int i = 0; i < outer.getNamedChildCount(); i++) {
                TSNode d = outer.getNamedChild(i);
                if (!d.getType().equals("decorator") || d.getNamedChildCount() == 0) continue;
                TSNode e = d.getNamedChild(0);
                if (e.getType().equals("call")) e = e.getChildByFieldName("function");
                String target = resolveName(m.text(e), null);
                if (target != null) dep(source, target, DependencyType.ANNOTATION, d);
            }
        }

        // ---- calls --------------------------------------------------------------------------------------------

        /** Calls inside {@code node}; nested definitions are visited as their own symbols. */
        private void calls(TSNode node, String source, String className, Map<String, String> types) {
            for (int i = 0; i < node.getNamedChildCount(); i++) {
                TSNode c = node.getNamedChild(i);
                String t = c.getType();
                if (t.equals("class_definition") || t.equals("decorated_definition")) continue;
                if (t.equals("function_definition")) continue; // nested functions are not symbols; skipped
                if (t.equals("string")) {
                    String text = m.text(c);
                    for (String table : com.changeimpact.parser.language.ts.EmbeddedSql.tables(text.replaceAll("^[rbfuRBFU]*('''|\"\"\"|'|\")|('''|\"\"\"|'|\")$", "")))
                        dep(source, table, DependencyType.FIELD_ACCESS, c);
                    continue;
                }
                if (t.equals("call")) {
                    TSNode fn = c.getChildByFieldName("function");
                    String target = callTarget(fn, className, types);
                    if (target != null) {
                        boolean ctor = index.isClass(target);
                        dep(source, target, ctor ? DependencyType.CONSTRUCTOR_CALL : DependencyType.METHOD_CALL, c);
                    }
                }
                calls(c, source, className, types);
            }
        }

        private String callTarget(TSNode fn, String className, Map<String, String> types) {
            if (fn.getType().equals("attribute")) {
                TSNode obj = fn.getChildByFieldName("object");
                String attr = m.text(fn.getChildByFieldName("attribute"));
                String receiver = receiverClass(obj, className, types);
                if (receiver != null) return receiver + "#" + attr;
            }
            String text = m.text(fn);
            if (!text.matches("[A-Za-z_][\\w.]*")) return null; // subscripts, calls on unknown call results, ...
            return resolveName(text, null);
        }

        /**
         * The class of a call receiver when it is certain: {@code self}/{@code cls}, a constructor call of a known
         * class ({@code Cls(...).m()}), or a local name typed by {@link #localTypes}. Otherwise null — not guessed.
         */
        private String receiverClass(TSNode obj, String className, Map<String, String> types) {
            if (obj.getType().equals("identifier")) {
                String name = m.text(obj);
                if (className != null && (name.equals("self") || name.equals("cls"))) return className;
                return types.get(name);
            }
            if (obj.getType().equals("call")) return constructedClass(obj);
            return null;
        }

        /** Class instantiated by a call expression, if its callee resolves to a class of the repository. */
        private String constructedClass(TSNode call) {
            TSNode fn = call.getChildByFieldName("function");
            if (fn == null || fn.isNull()) return null;
            String text = m.text(fn);
            if (!text.matches("[A-Za-z_][\\w.]*")) return null;
            String target = resolveName(text, null);
            return target != null && index.isClass(target) ? target : null;
        }

        /**
         * Flow-insensitive local types: parameters annotated with a known class, and names whose every assignment in
         * the scope is a constructor call of the same known class. A name assigned anything else is left untyped.
         */
        private Map<String, String> localTypes(TSNode body, TSNode params) {
            Map<String, String> types = new HashMap<>();
            Set<String> conflicting = new HashSet<>();
            if (params != null && !params.isNull()) {
                for (int i = 0; i < params.getNamedChildCount(); i++) {
                    TSNode p = params.getNamedChild(i);
                    if (!p.getType().equals("typed_parameter") && !p.getType().equals("typed_default_parameter")) continue;
                    TSNode type = p.getChildByFieldName("type");
                    TSNode name = p.getType().equals("typed_parameter") ? p.getNamedChild(0) : p.getChildByFieldName("name");
                    if (type == null || type.isNull() || name == null || name.isNull() || !name.getType().equals("identifier")) continue;
                    String t = m.text(type).strip();
                    if (!t.matches("[A-Za-z_][\\w.]*")) continue;
                    String target = resolveName(t, null);
                    if (target != null && index.isClass(target)) types.put(m.text(name), target);
                }
            }
            assignments(body, types, conflicting);
            conflicting.forEach(types::remove);
            return types;
        }

        private void assignments(TSNode node, Map<String, String> types, Set<String> conflicting) {
            for (int i = 0; i < node.getNamedChildCount(); i++) {
                TSNode c = node.getNamedChild(i);
                String t = c.getType();
                if (t.equals("function_definition") || t.equals("class_definition") || t.equals("decorated_definition")
                        || t.equals("lambda")) continue;
                if (t.equals("assignment")) {
                    TSNode left = c.getChildByFieldName("left"), right = c.getChildByFieldName("right");
                    if (left != null && !left.isNull() && left.getType().equals("identifier")) {
                        String name = m.text(left);
                        String cls = right != null && !right.isNull() && right.getType().equals("call") ? constructedClass(right) : null;
                        String known = types.get(name);
                        if (cls == null || (known != null && !known.equals(cls))) conflicting.add(name);
                        else types.put(name, cls);
                    } else if (left != null && !left.isNull()) {
                        collectNames(left, conflicting); // tuple targets etc.: untyped
                    }
                } else if (t.equals("for_statement") || t.equals("with_item") || t.equals("augmented_assignment")) {
                    TSNode left = c.getChildByFieldName(t.equals("with_item") ? "value" : "left");
                    if (left != null && !left.isNull()) collectNames(left, conflicting);
                }
                assignments(c, types, conflicting);
            }
        }

        private void collectNames(TSNode n, Set<String> out) {
            if (n.getType().equals("identifier")) out.add(m.text(n));
            for (int i = 0; i < n.getNamedChildCount(); i++) collectNames(n.getNamedChild(i), out);
        }

        /** A dotted name in this module's scope → qualified target, or null if unknown (builtins, local variables). */
        private String resolveName(String dotted, String selfClass) {
            String head = dotted.contains(".") ? dotted.substring(0, dotted.indexOf('.')) : dotted;
            String rest = dotted.substring(head.length());
            if (local.containsKey(head)) {
                String q = local.get(head);
                if (rest.isEmpty()) return q;
                return q.contains("#") ? null : index.qualify(q + rest);
            }
            // longest imported prefix (import a.b.c binds "a.b.c" and "a")
            for (String candidate = dotted; ; ) {
                if (aliases.containsKey(candidate)) return index.qualify(aliases.get(candidate) + dotted.substring(candidate.length()));
                int dot = candidate.lastIndexOf('.');
                if (dot < 0) return null;
                candidate = candidate.substring(0, dot);
            }
        }

        // ---- helpers ------------------------------------------------------------------------------------------

        private void dep(String source, String target, DependencyType type, TSNode at) {
            deps.add(ExtractedDependency.builder()
                    .sourceQualifiedName(source).targetQualifiedName(target).dependencyType(type)
                    .isExternal(false).sourceLine(row(at)).resolutionStatus(ResolutionStatus.RESOLVED).build());
        }

        private String parameters(TSNode params, boolean dropReceiver) {
            if (params == null || params.isNull()) return "";
            List<String> out = new ArrayList<>();
            for (int i = 0; i < params.getNamedChildCount(); i++) {
                TSNode p = params.getNamedChild(i);
                if (p.getType().equals("comment")) continue;
                out.add(m.text(p).replaceAll("\\s+", " ").strip());
            }
            if (dropReceiver && !out.isEmpty() && (out.get(0).equals("self") || out.get(0).equals("cls"))) out.remove(0);
            return String.join(", ", out);
        }

        /** Top-level statements other than definitions and the module docstring (module-level code and constants). */
        private String moduleLevelText(TSNode root) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < root.getNamedChildCount(); i++) {
                TSNode s = root.getNamedChild(i);
                if (definition(s) != null || s.getType().equals("comment") || isDocstring(s, i)) continue;
                sb.append(normalise(s)).append('\n');
            }
            return sb.toString();
        }

        private static boolean isDocstring(TSNode s, int position) {
            return position == 0 && s.getType().equals("expression_statement") && s.getNamedChildCount() == 1
                    && s.getNamedChild(0).getType().equals("string");
        }

        /** Source of a node as its tokens (comments and docstrings dropped), so formatting changes do not count. */
        private String normalise(TSNode n) {
            StringBuilder sb = new StringBuilder();
            tokens(n, sb);
            return sb.toString().strip();
        }

        private void tokens(TSNode n, StringBuilder sb) {
            if (n.getType().equals("comment")) return;
            if (n.getType().equals("block") && n.getNamedChildCount() > 0 && isDocstring(n.getNamedChild(0), 0)) {
                for (int i = 0; i < n.getChildCount(); i++) {
                    TSNode c = n.getChild(i);
                    if (TSNode.eq(c, n.getNamedChild(0))) continue;
                    tokens(c, sb);
                }
                return;
            }
            if (n.getChildCount() == 0 || n.getType().equals("string")) {
                sb.append(m.text(n)).append(' ');
                return;
            }
            for (int i = 0; i < n.getChildCount(); i++) tokens(n.getChild(i), sb);
        }

        private static final Set<String> DECISIONS = Set.of("if_statement", "elif_clause", "for_statement",
                "while_statement", "except_clause", "conditional_expression", "boolean_operator", "for_in_clause",
                "if_clause", "case_clause");

        private static int decisions(TSNode n) {
            int count = 0;
            for (int i = 0; i < n.getNamedChildCount(); i++) {
                TSNode c = n.getNamedChild(i);
                String t = c.getType();
                if (t.equals("function_definition") || t.equals("class_definition") || t.equals("lambda")) continue;
                if (DECISIONS.contains(t)) count++;
                count += decisions(c);
            }
            return count;
        }

        private static int row(TSNode n) {
            return n.getStartPoint().getRow() + 1;
        }

        private static String visibility(String name) {
            return name.startsWith("_") && !(name.startsWith("__") && name.endsWith("__")) ? "private" : "public";
        }

        private static String hash(String s) {
            try {
                byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
                return HexFormat.of().formatHex(d);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
    }

    /** The class or function a top-level statement defines (unwrapping decorators), or null. */
    static TSNode definition(TSNode stmt) {
        if (stmt.getType().equals("class_definition") || stmt.getType().equals("function_definition")) return stmt;
        if (stmt.getType().equals("decorated_definition")) {
            TSNode d = stmt.getChildByFieldName("definition");
            return d == null || d.isNull() ? null : d;
        }
        return null;
    }
}

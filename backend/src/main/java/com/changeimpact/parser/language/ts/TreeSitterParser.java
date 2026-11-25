package com.changeimpact.parser.language.ts;

import com.changeimpact.common.config.AppProperties;
import com.changeimpact.parser.language.LanguageParser;
import com.changeimpact.parser.model.*;
import lombok.extern.slf4j.Slf4j;
import org.treesitter.TSLanguage;
import org.treesitter.TSNode;
import org.treesitter.TSParser;
import org.treesitter.TSTree;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;
import java.util.stream.Stream;

/**
 * Shared base of the tree-sitter languages: file scanning, parsing, and the two-pass analysis used by every parser —
 * pass 1 indexes the declarations of all files, pass 2 extracts symbols and dependencies of each file against that
 * index. Names are linked only when the target is certain (declared in the repository and reached through an import,
 * the enclosing scope or a known receiver type); nothing is guessed.
 */
@Slf4j
public abstract class TreeSitterParser implements LanguageParser {

    /** Directories that never contain analysable first-party source. */
    public static final Set<String> SKIP_DIRS = Set.of(".git", ".hg", ".svn", "node_modules", "bower_components", "vendor",
            "dist", "build", "out", "target", "bin", "obj", "coverage", ".next", ".nuxt", ".svelte-kit", ".turbo", ".cache",
            ".gradle", ".idea", ".vscode", "storybook-static", "__pycache__", ".venv", "venv", ".tox", "Pods", "tmp", "log");
    public static final long MAX_FILE_BYTES = 1_000_000;

    protected final AppProperties appProperties;
    private final boolean available;

    protected TreeSitterParser(AppProperties appProperties, TSLanguage probe) {
        this.appProperties = appProperties;
        boolean ok;
        try (TSParser p = new TSParser()) {
            ok = p.setLanguage(probe);
        } catch (Throwable t) { // UnsatisfiedLinkError where no native library is bundled
            log.warn("tree-sitter grammar for {} is not available here: {}", getClass().getSimpleName(), t.toString());
            ok = false;
        }
        this.available = ok;
    }

    @Override public boolean available() { return available; }
    @Override public boolean incremental() { return false; }
    @Override public boolean experimental() { return false; }

    /** Grammar for one file (e.g. TypeScript vs TSX), or null to skip it. */
    protected abstract TSLanguage grammarFor(String relativePath);

    /** Whether a file (relative path, lower case name) belongs to this language. */
    protected boolean accepts(String relativePath) {
        String n = relativePath.toLowerCase(Locale.ROOT);
        return extensions().stream().anyMatch(n::endsWith);
    }

    /** Pass 1 + pass 2 over all parsed files. */
    protected abstract Map<String, ParsedSourceFile> analyse(Path root, List<Source> sources);

    @Override
    public Session open(Path root) {
        return new Session() {
            private List<Path> files;
            private Map<String, ParsedSourceFile> results;

            @Override
            public List<Path> sourceFiles() {
                if (files == null) files = scan(root);
                return files;
            }

            @Override
            public Optional<ParsedSourceFile> parse(Path file) {
                if (results == null) results = parseAll(root, sourceFiles());
                return Optional.ofNullable(results.get(rel(root, file)));
            }
        };
    }

    protected List<Path> scan(Path root) {
        return scanFiles(root, appProperties, this::accepts, language());
    }

    /** Files under {@code root} accepted by {@code accepts}, skipping build/vendor directories and excluded paths. */
    public static List<Path> scanFiles(Path root, AppProperties appProperties, java.util.function.Predicate<String> accepts, String language) {
        List<String> excluded = appProperties.getIngestion().getExcludedPaths() == null ? List.of() : appProperties.getIngestion().getExcludedPaths();
        int max = appProperties.getIngestion().getMaxFilesPerRepository();
        try (Stream<Path> s = Files.walk(root)) {
            return s.filter(Files::isRegularFile)
                    .filter(p -> {
                        Path r = root.relativize(p);
                        for (Path part : r) if (SKIP_DIRS.contains(part.toString())) return false;
                        String rel = r.toString().replace('\\', '/');
                        for (String ex : excluded) if (!ex.isBlank() && (rel.startsWith(ex) || rel.contains("/" + ex))) return false;
                        return accepts.test(rel);
                    })
                    .sorted().limit(max).toList();
        } catch (Exception e) {
            log.warn("Could not scan {} files under {}: {}", language, root, e.getMessage());
            return List.of();
        }
    }

    private Map<String, ParsedSourceFile> parseAll(Path root, List<Path> files) {
        List<Source> sources = new ArrayList<>();
        Map<TSLanguage, TSParser> parsers = new HashMap<>();
        try {
            for (Path f : files) {
                String rel = rel(root, f);
                try {
                    if (Files.size(f) > MAX_FILE_BYTES) continue;
                    TSLanguage lang = grammarFor(rel);
                    if (lang == null) continue;
                    String text = decode(Files.readAllBytes(f));
                    byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
                    if (minified(text)) continue;
                    TSParser p = parsers.computeIfAbsent(lang, l -> { TSParser np = new TSParser(); np.setLanguage(l); return np; });
                    TSTree tree = p.parseString(null, text);
                    sources.add(new Source(rel, bytes, text, tree, tree.getRootNode()));
                } catch (Exception e) {
                    log.debug("{} file {} skipped: {}", language(), rel, e.getMessage());
                }
            }
            return analyse(root, sources);
        } finally {
            parsers.values().forEach(TSParser::close);
        }
    }

    /**
     * File content as tree-sitter sees it: UTF-8 without a byte-order mark. Node offsets are byte offsets into
     * {@code decode(raw).getBytes(UTF_8)} — not into the raw file, which differs when it has a BOM (the binding drops
     * it) or invalid UTF-8 (replaced on decoding).
     */
    public static String decode(byte[] raw) {
        String text = new String(raw, StandardCharsets.UTF_8);
        return text.startsWith("\uFEFF") ? text.substring(1) : text;
    }

    /** Bundled/minified files (a few very long lines) are build output, not source. */
    static boolean minified(String text) {
        if (text.length() < 20_000) return false;
        long lines = text.lines().count();
        return lines > 0 && text.length() / lines > 500;
    }

    public static String rel(Path root, Path file) {
        return root.relativize(file).toString().replace('\\', '/');
    }

    // =============================================================================================================

    /** One parsed file. The tree is kept so its nodes stay valid. */
    public record Source(String path, byte[] bytes, String text, TSTree tree, TSNode root) {
        public String text(TSNode n) {
            if (n == null || n.isNull()) return "";
            return new String(bytes, n.getStartByte(), n.getEndByte() - n.getStartByte(), StandardCharsets.UTF_8);
        }

        public int lines() {
            return (int) text.lines().count();
        }
    }

    /** Symbols and dependencies of one file under construction. */
    public static final class Emitter {
        public final List<ExtractedSymbol> symbols = new ArrayList<>();
        public final List<ExtractedDependency> deps = new ArrayList<>();
        private final Set<String> seen = new HashSet<>();

        public void dep(String source, String target, DependencyType type, TSNode at) {
            dep(source, target, type, row(at));
        }

        public void dep(String source, String target, DependencyType type, int line) {
            if (source == null || target == null || target.isBlank() || source.equals(target)) return;
            if (!seen.add(source + "|" + target + "|" + type + "|" + line)) return;
            deps.add(ExtractedDependency.builder().sourceQualifiedName(source).targetQualifiedName(target).dependencyType(type)
                    .isExternal(false).sourceLine(line).resolutionStatus(ResolutionStatus.RESOLVED).build());
        }

        public ParsedSourceFile file(Source s, String packageName, boolean test, String language) {
            return ParsedSourceFile.builder().relativePath(s.path()).packageName(packageName)
                    .linesOfCode(s.lines()).sizeBytes(s.bytes().length).isTest(test)
                    .symbols(symbols).dependencies(deps).language(language).build();
        }
    }

    /** Builder pre-filled with the position and default flags of {@code n}. */
    public static ExtractedSymbol.ExtractedSymbolBuilder symbol(String simple, String qualified, SymbolType type, String parent, TSNode n) {
        return ExtractedSymbol.builder().simpleName(simple).qualifiedName(qualified).symbolType(type).parentQualifiedName(parent)
                .visibility("public").annotations(List.of())
                .startLine(row(n)).endLine(n.getEndPoint().getRow() + 1)
                .startColumn(n.getStartPoint().getColumn() + 1).endColumn(n.getEndPoint().getColumn() + 1)
                .resolutionStatus(ResolutionStatus.RESOLVED);
    }

    // ---- node helpers -------------------------------------------------------------------------------------------

    public static int row(TSNode n) {
        return n == null || n.isNull() ? 0 : n.getStartPoint().getRow() + 1;
    }

    public static TSNode field(TSNode n, String name) {
        if (n == null || n.isNull()) return null;
        TSNode c = n.getChildByFieldName(name);
        return c == null || c.isNull() ? null : c;
    }

    public static List<TSNode> named(TSNode n) {
        List<TSNode> out = new ArrayList<>();
        if (n == null || n.isNull()) return out;
        for (int i = 0; i < n.getNamedChildCount(); i++) out.add(n.getNamedChild(i));
        return out;
    }

    public static TSNode firstNamed(TSNode n, String... types) {
        Set<String> t = Set.of(types);
        for (TSNode c : named(n)) if (t.contains(c.getType())) return c;
        return null;
    }

    /** Descendants of the given types; below the start node, {@code stop} types are not descended into (but still matched). */
    public static List<TSNode> descendants(TSNode n, Set<String> types, Set<String> stop) {
        List<TSNode> out = new ArrayList<>();
        collect(n, types, stop, out, true);
        return out;
    }

    private static void collect(TSNode n, Set<String> types, Set<String> stop, List<TSNode> out, boolean top) {
        if (n == null || n.isNull()) return;
        if (!top && types.contains(n.getType())) out.add(n);
        if (!top && stop.contains(n.getType())) return;
        for (int i = 0; i < n.getNamedChildCount(); i++) collect(n.getNamedChild(i), types, stop, out, false);
    }

    /** Decision points for cyclomatic complexity (1 + count), not descending into nested definitions. */
    public static int complexity(Source s, TSNode body, Set<String> decisions, Set<String> stop, Set<String> logicalOperators) {
        if (body == null) return 1;
        int[] count = {1};
        countDecisions(s, body, decisions, stop, logicalOperators, count, true);
        return count[0];
    }

    private static void countDecisions(Source s, TSNode n, Set<String> decisions, Set<String> stop, Set<String> ops, int[] count, boolean top) {
        if (!top && stop.contains(n.getType())) return;
        if (decisions.contains(n.getType())) count[0]++;
        if (n.getType().equals("binary_expression") || n.getType().equals("binary") || n.getType().equals("boolean_operator")) {
            TSNode op = field(n, "operator");
            String o = op != null ? s.text(op) : "";
            if (ops.contains(o)) count[0]++;
        }
        for (int i = 0; i < n.getNamedChildCount(); i++) countDecisions(s, n.getNamedChild(i), decisions, stop, ops, count, false);
    }

    /** Tokens of a node without comments, so formatting and comment edits do not count as changes. */
    public static String normalise(Source s, TSNode n, Set<String> commentTypes) {
        StringBuilder sb = new StringBuilder();
        tokens(s, n, commentTypes, sb);
        return sb.toString().strip();
    }

    private static void tokens(Source s, TSNode n, Set<String> comments, StringBuilder sb) {
        if (comments.contains(n.getType())) return;
        if (n.getChildCount() == 0 || n.getType().contains("string")) {
            sb.append(s.text(n)).append(' ');
            return;
        }
        for (int i = 0; i < n.getChildCount(); i++) tokens(s, n.getChild(i), comments, sb);
    }

    public static String collapse(String text) {
        return text == null ? null : text.replaceAll("\\s+", " ").strip();
    }

    public static String hash(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** {@code a/b/c.ext} → {@code a.b.c} (or with the extension kept). Dots in file names stay. */
    public static String pathName(String relativePath, boolean keepExtension) {
        String p = relativePath;
        if (!keepExtension) {
            int slash = p.lastIndexOf('/'), dot = p.lastIndexOf('.');
            if (dot > slash) p = p.substring(0, dot);
        }
        return p.replace('/', '.');
    }

    public static String directory(String relativePath) {
        int slash = relativePath.lastIndexOf('/');
        return slash < 0 ? "" : relativePath.substring(0, slash);
    }

    /** Lexically normalised {@code dir/relative} (no filesystem access), or null if it leaves the repository. */
    public static String join(String dir, String relative) {
        Deque<String> parts = new ArrayDeque<>();
        String combined = relative.startsWith("/") ? relative.substring(1) : (dir.isEmpty() ? relative : dir + "/" + relative);
        for (String part : combined.split("/")) {
            if (part.isEmpty() || part.equals(".")) continue;
            if (part.equals("..")) {
                if (parts.isEmpty()) return null;
                parts.removeLast();
            } else parts.addLast(part);
        }
        return String.join("/", parts);
    }
}

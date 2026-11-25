package com.changeimpact.parser.language.ts;

import com.changeimpact.common.config.AppProperties;
import com.changeimpact.parser.model.*;
import org.springframework.stereotype.Component;
import org.treesitter.TSLanguage;
import org.treesitter.TSNode;
import org.treesitter.TreeSitterCss;

import java.nio.file.Path;
import java.util.*;

/**
 * CSS stylesheets. A stylesheet is a {@code MODULE} (its path with the extension, e.g. {@code src.App.css}); every
 * class and id selector it defines is a {@code FIELD} member ({@code src.App.css#.card}, {@code ...##main}) whose
 * content is all declaration blocks that mention it, so a change to a rule is a change of the selector.
 * {@code @import} is an {@code IMPORT}. HTML, JSX/TSX ({@code className}) and CSS Modules ({@code styles.card}) link to
 * these selectors through {@link #index(Path)}. A stylesheet with more than {@value #MAX_SELECTORS} selectors is a
 * framework or compiled bundle (Bootstrap is about 2,000): it is one {@code MODULE} without selector members, and links to
 * its selectors attach to the stylesheet (PARTIAL).
 */
@Component
public class CssLanguageParser extends TreeSitterParser {

    private static final Set<String> COMMENTS = Set.of("comment", "js_comment");
    static final int MAX_SELECTORS = 500;

    public CssLanguageParser(AppProperties appProperties) {
        super(appProperties, new TreeSitterCss());
    }

    @Override public String language() { return "css"; }
    /** 2: framework-sized stylesheets are one module. */
    @Override public int version() { return 2; }
    @Override public Set<String> extensions() { return Set.of(".css"); }
    @Override protected TSLanguage grammarFor(String relativePath) { return new TreeSitterCss(); }

    public static String moduleName(String relativePath) {
        return pathName(relativePath, true);
    }

    @Override
    protected Map<String, ParsedSourceFile> analyse(Path root, List<Source> sources) {
        Map<String, ParsedSourceFile> out = new LinkedHashMap<>();
        Set<String> files = new HashSet<>();
        sources.forEach(s -> files.add(s.path()));
        for (Source s : sources) {
            String module = moduleName(s.path());
            Emitter e = new Emitter();
            e.symbols.add(symbol(s.path().substring(s.path().lastIndexOf('/') + 1), module, SymbolType.MODULE,
                    null, s.root()).startLine(1).endLine(Math.max(1, s.lines())).contentHash(hash(normalise(s, s.root(), COMMENTS))).build());
            for (TSNode imp : descendants(s.root(), Set.of("import_statement"), Set.of())) {
                TSNode str = descendants(imp, Set.of("string_value"), Set.of()).stream().findFirst().orElse(null);
                if (str == null) continue;
                String target = join(directory(s.path()), s.text(str).replaceAll("^['\"]|['\"]$", ""));
                if (target != null && files.contains(target)) e.dep(module, moduleName(target), DependencyType.IMPORT, imp);
            }
            Map<String, List<TSNode>> selectors = selectors(s);
            if (selectors.size() > MAX_SELECTORS) selectors = Map.of();
            selectors.forEach((name, rules) -> {
                TSNode first = rules.get(0), last = rules.get(rules.size() - 1);
                StringBuilder content = new StringBuilder();
                rules.forEach(r -> content.append(normalise(s, r, COMMENTS)).append('\n'));
                e.symbols.add(symbol(name, module + "#" + name, SymbolType.FIELD, module, first)
                        .endLine(last.getEndPoint().getRow() + 1).contentHash(hash(content.toString())).build());
            });
            out.put(s.path(), e.file(s, pathName(directory(s.path()), false), false, "css"));
        }
        return out;
    }

    /** {@code .class} / {@code #id} → the rule sets that select it, in source order. */
    static Map<String, List<TSNode>> selectors(Source s) {
        Map<String, List<TSNode>> out = new TreeMap<>();
        for (TSNode rule : descendants(s.root(), Set.of("rule_set"), Set.of())) {
            TSNode sel = firstNamed(rule, "selectors");
            for (TSNode c : descendants(sel, Set.of("class_name", "id_name"), Set.of())) {
                String name = (c.getType().equals("id_name") ? "#" : ".") + s.text(c).strip();
                List<TSNode> list = out.computeIfAbsent(name, k -> new ArrayList<>());
                if (list.isEmpty() || !list.get(list.size() - 1).equals(rule)) list.add(rule);
            }
        }
        return out;
    }

    /** Which stylesheets define a class or id: {@code .card} → [{@code src.App.css#.card}, …]. */
    public record CssIndex(Map<String, List<String>> selectors) {
        public List<String> targets(String selector) {
            return selectors.getOrDefault(selector, List.of());
        }

        /** Every class in a {@code class="a b"} attribute value → its selector symbols (at most 5 stylesheets each). */
        public List<String> classTargets(String classAttribute) {
            List<String> out = new ArrayList<>();
            for (String c : classAttribute.trim().split("\\s+")) {
                if (!c.matches("-?[A-Za-z_][\\w-]*")) continue;
                out.addAll(targets("." + c).stream().limit(5).toList());
            }
            return out;
        }
    }

    /** Index of the selectors of all stylesheets under {@code root} (empty when the grammar is unavailable). */
    public CssIndex index(Path root) {
        Map<String, List<String>> map = new HashMap<>();
        if (!available()) return new CssIndex(map);
        for (var e : open(root).sourceFiles().stream().map(f -> Map.entry(f, rel(root, f))).toList()) {
            try (var p = new org.treesitter.TSParser()) {
                p.setLanguage(new TreeSitterCss());
                byte[] raw = java.nio.file.Files.readAllBytes(e.getKey());
                if (raw.length > MAX_FILE_BYTES) continue;
                String text = decode(raw);
                byte[] bytes = text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                var tree = p.parseString(null, text);
                Source s = new Source(e.getValue(), bytes, text, tree, tree.getRootNode());
                for (String name : selectors(s).keySet()) {
                    map.computeIfAbsent(name, k -> new ArrayList<>()).add(moduleName(s.path()) + "#" + name);
                }
            } catch (Exception ex) {
                // unreadable stylesheet: no links to it
            }
        }
        return new CssIndex(map);
    }
}

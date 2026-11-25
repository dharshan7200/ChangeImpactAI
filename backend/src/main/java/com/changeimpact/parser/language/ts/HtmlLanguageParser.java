package com.changeimpact.parser.language.ts;

import com.changeimpact.common.config.AppProperties;
import com.changeimpact.parser.model.*;
import org.springframework.stereotype.Component;
import org.treesitter.TSLanguage;
import org.treesitter.TSNode;
import org.treesitter.TreeSitterHtml;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * HTML pages and server-side templates (Thymeleaf, Jinja/Django, Twig, Nunjucks, Liquid — anything that is HTML
 * with template tags).
 *
 * <p>Model: every page is a {@code MODULE} ({@code templates/owners/ownerDetails.html} →
 * {@code templates.owners.ownerDetails.html}); Thymeleaf {@code th:fragment}s and Jinja/Twig {@code block}s/
 * {@code macro}s are {@code FUNCTION}s of it ({@code ...layout.html#layout}).
 *
 * <p>Links: {@code <script src>} and {@code <link rel=stylesheet href>} to the repository's JavaScript and CSS (relative,
 * root-relative under the usual static roots, Thymeleaf {@code @{...}}); template inclusion ({@code th:replace},
 * {@code th:insert}, {@code th:include}, {@code layout:decorate}, {@code {% extends/include/import/from/embed %}}) to the
 * included template and the named fragment; {@code class}/{@code id} attributes to the stylesheet selectors that
 * define them. Inline scripts and styles, navigation links and URLs outside the repository are not linked.
 */
@Component
public class HtmlLanguageParser extends TreeSitterParser {

    private static final Set<String> COMMENTS = Set.of("comment");
    private static final List<String> STATIC_ROOTS = List.of("", "public/", "static/", "src/main/resources/static/", "src/main/resources/public/",
            "src/main/webapp/", "wwwroot/", "web/", "app/assets/", "assets/", "src/");
    private static final Pattern TEMPLATE_TAG = Pattern.compile("\\{%-?\\s*(extends|include|import|from|embed|render)\\s+['\"]([^'\"]+)['\"]");
    private static final Pattern BLOCK = Pattern.compile("\\{%-?\\s*(block|macro)\\s+([A-Za-z_][\\w]*)");
    private static final Pattern FRAGMENT_REF = Pattern.compile("^\\s*(?:~\\{)?\\s*([^:{}\\s][^:{}]*?)\\s*(?:::\\s*([A-Za-z_][\\w-]*))?\\s*(?:\\(.*)?\\}?\\s*$", Pattern.DOTALL);
    private static final Set<String> INCLUDE_ATTRS = Set.of("th:replace", "th:insert", "th:include", "data-th-replace", "data-th-insert",
            "layout:decorate", "layout:decorator", "data-layout-decorate");

    private final CssLanguageParser css;

    public HtmlLanguageParser(AppProperties appProperties, CssLanguageParser css) {
        super(appProperties, new TreeSitterHtml());
        this.css = css;
    }

    @Override public String language() { return "html"; }
    @Override public Set<String> extensions() { return Set.of(".html", ".htm", ".jinja", ".jinja2", ".j2", ".twig", ".njk", ".liquid"); }
    @Override protected TSLanguage grammarFor(String relativePath) { return new TreeSitterHtml(); }

    public static String moduleName(String rel) {
        return pathName(rel, true);
    }

    @Override
    protected Map<String, ParsedSourceFile> analyse(Path root, List<Source> sources) {
        Map<String, Source> byPath = new HashMap<>();
        sources.forEach(s -> byPath.put(s.path(), s));
        Map<String, Set<String>> fragments = new HashMap<>();
        for (Source s : sources) fragments.put(s.path(), fragmentNames(s));
        CssLanguageParser.CssIndex cssIndex = css.index(root);
        Map<String, ParsedSourceFile> out = new LinkedHashMap<>();
        for (Source s : sources) {
            try {
                out.put(s.path(), extract(root, s, byPath, fragments, cssIndex));
            } catch (Exception e) {
                org.slf4j.LoggerFactory.getLogger(getClass()).debug("HTML file {} skipped: {}", s.path(), e.toString());
            }
        }
        return out;
    }

    private static Set<String> fragmentNames(Source s) {
        Set<String> out = new LinkedHashSet<>();
        for (TSNode a : descendants(s.root(), Set.of("attribute"), Set.of())) {
            String name = attrName(s, a);
            if (name.equals("th:fragment") || name.equals("data-th-fragment")) {
                String v = attrValue(s, a);
                if (v != null) out.add(v.replaceAll("\\(.*$", "").strip());
            }
        }
        Matcher m = BLOCK.matcher(s.text());
        while (m.find()) out.add(m.group(2));
        return out;
    }

    static String attrName(Source s, TSNode attr) {
        TSNode n = firstNamed(attr, "attribute_name");
        return n == null ? "" : s.text(n).toLowerCase(Locale.ROOT);
    }

    static String attrValue(Source s, TSNode attr) {
        TSNode v = firstNamed(attr, "quoted_attribute_value", "attribute_value");
        if (v == null) return null;
        TSNode inner = firstNamed(v, "attribute_value");
        return inner != null ? s.text(inner) : s.text(v).replaceAll("^['\"]|['\"]$", "");
    }

    private ParsedSourceFile extract(Path root, Source s, Map<String, Source> byPath, Map<String, Set<String>> fragments,
                                     CssLanguageParser.CssIndex cssIndex) {
        String module = moduleName(s.path());
        Emitter e = new Emitter();
        e.symbols.add(symbol(s.path().substring(s.path().lastIndexOf('/') + 1), module, SymbolType.MODULE, null, s.root())
                .startLine(1).endLine(Math.max(1, s.lines())).contentHash(hash(normalise(s, s.root(), COMMENTS))).build());
        // fragments / blocks as members, so a change inside one is a change of that fragment
        for (TSNode el : descendants(s.root(), Set.of("element"), Set.of())) {
            TSNode start = firstNamed(el, "start_tag", "self_closing_tag");
            if (start == null) continue;
            for (TSNode a : named(start)) {
                if (!a.getType().equals("attribute")) continue;
                String name = attrName(s, a);
                if (!name.equals("th:fragment") && !name.equals("data-th-fragment")) continue;
                String v = attrValue(s, a);
                if (v == null) continue;
                String f = v.replaceAll("\\(.*$", "").strip();
                String params = v.contains("(") ? v.substring(v.indexOf('(') + 1).replaceAll("\\)\\s*$", "") : "";
                e.symbols.add(symbol(f, module + "#" + f, SymbolType.FUNCTION, module, el).parameters(params)
                        .annotations(List.of("fragment")).annotationText("fragment").contentHash(hash(normalise(s, el, COMMENTS))).build());
            }
        }
        Matcher b = BLOCK.matcher(s.text());
        while (b.find()) {
            String f = b.group(2);
            if (e.symbols.stream().anyMatch(x -> x.getQualifiedName().equals(module + "#" + f))) continue;
            int line = (int) s.text().substring(0, b.start()).chars().filter(c -> c == '\n').count() + 1;
            int end = blockEnd(s.text(), b.end(), b.group(1));
            int endLine = (int) s.text().substring(0, end).chars().filter(c -> c == '\n').count() + 1;
            e.symbols.add(symbol(f, module + "#" + f, SymbolType.FUNCTION, module, s.root()).startLine(line).endLine(endLine).startColumn(1).endColumn(1)
                    .annotations(List.of(b.group(1))).annotationText(b.group(1))
                    .contentHash(hash(SqlLanguageParser.collapse(s.text().substring(b.start(), end)))).build());
        }
        // attributes: resources, includes, styles
        for (TSNode a : descendants(s.root(), Set.of("attribute"), Set.of())) {
            String name = attrName(s, a);
            String value = attrValue(s, a);
            if (value == null) continue;
            String source = enclosingFragment(s, a, module, e);
            switch (name) {
                case "src", "th:src", "data-th-src" -> {
                    String target = resource(root, s.path(), value, true);
                    if (target != null) e.dep(source, target, DependencyType.IMPORT, a);
                }
                case "href", "th:href", "data-th-href" -> {
                    if (isStylesheetLink(s, a)) {
                        String target = resource(root, s.path(), value, false);
                        if (target != null) e.dep(source, target, DependencyType.IMPORT, a);
                    }
                }
                case "class", "th:class", "data-th-class" -> {
                    for (String t : cssIndex.classTargets(value.replaceAll("[${}'\"?:]", " "))) e.dep(source, t, DependencyType.FIELD_ACCESS, a);
                }
                case "id" -> {
                    if (value.matches("[A-Za-z][\\w-]*")) for (String t : cssIndex.targets("#" + value)) e.dep(source, t, DependencyType.FIELD_ACCESS, a);
                }
                default -> {
                    if (INCLUDE_ATTRS.contains(name)) include(s, value, byPath, fragments, source, e, row(a));
                }
            }
        }
        Matcher m = TEMPLATE_TAG.matcher(s.text());
        while (m.find()) {
            int line = (int) s.text().substring(0, m.start()).chars().filter(c -> c == '\n').count() + 1;
            String target = template(s.path(), m.group(2), byPath);
            if (target != null) e.dep(module, moduleName(target), DependencyType.IMPORT, line);
        }
        return e.file(s, pathName(directory(s.path()), false), false, "html");
    }

    private static int blockEnd(String text, int from, String kind) {
        Matcher end = Pattern.compile("\\{%-?\\s*end" + kind + "\\b[^%]*%\\}").matcher(text);
        return end.find(from) ? end.end() : Math.min(text.length(), from);
    }

    /** The Thymeleaf fragment an attribute is inside of, or the page. */
    private static String enclosingFragment(Source s, TSNode a, String module, Emitter e) {
        int line = row(a);
        String best = module;
        int bestSpan = Integer.MAX_VALUE;
        for (ExtractedSymbol sym : e.symbols) {
            if (sym.getSymbolType() != SymbolType.FUNCTION || !sym.getAnnotations().contains("fragment")) continue;
            if (sym.getStartLine() <= line && line <= sym.getEndLine() && sym.getEndLine() - sym.getStartLine() < bestSpan) {
                best = sym.getQualifiedName();
                bestSpan = sym.getEndLine() - sym.getStartLine();
            }
        }
        return best;
    }

    private static boolean isStylesheetLink(Source s, TSNode attr) {
        TSNode tag = attr.getParent();
        if (tag == null) return false;
        TSNode tagName = firstNamed(tag, "tag_name");
        if (tagName == null || !s.text(tagName).equalsIgnoreCase("link")) return false;
        for (TSNode a : named(tag)) {
            if (a.getType().equals("attribute") && attrName(s, a).equals("rel")) {
                String v = attrValue(s, a);
                return v != null && v.toLowerCase(Locale.ROOT).contains("stylesheet");
            }
        }
        return false;
    }

    /** Thymeleaf {@code ~{fragments/layout :: layout(...)}} → IMPORT of the template, METHOD_CALL of the fragment. */
    private static void include(Source s, String value, Map<String, Source> byPath, Map<String, Set<String>> fragments,
                                String source, Emitter e, int line) {
        Matcher m = FRAGMENT_REF.matcher(value);
        if (!m.matches()) return;
        String templateName = m.group(1).strip();
        String target = templateName.equals("this") || templateName.isEmpty() ? s.path() : template(s.path(), templateName, byPath);
        if (target == null) return;
        String targetModule = moduleName(target);
        if (!target.equals(s.path())) e.dep(source, targetModule, DependencyType.IMPORT, line);
        String fragment = m.group(2);
        if (fragment != null && fragments.getOrDefault(target, Set.of()).contains(fragment))
            e.dep(source, targetModule + "#" + fragment, DependencyType.METHOD_CALL, line);
    }

    /** Template name → repository path: relative to an enclosing {@code templates}/{@code views} folder, the page, or unique by suffix. */
    static String template(String from, String name, Map<String, Source> byPath) {
        String n = name.replaceFirst("^/", "");
        List<String> names = new ArrayList<>(List.of(n));
        if (!n.matches(".*\\.[A-Za-z0-9]+$")) for (String ext : List.of(".html", ".htm", ".jinja", ".j2", ".twig", ".njk")) names.add(n + ext);
        for (String dir = directory(from); ; dir = directory(dir)) {
            String last = dir.substring(dir.lastIndexOf('/') + 1);
            if (last.equals("templates") || last.equals("views") || last.equals("layouts") || dir.isEmpty()) {
                for (String c : names) {
                    String p = dir.isEmpty() ? c : dir + "/" + c;
                    if (byPath.containsKey(p)) return p;
                }
            }
            if (dir.isEmpty()) break;
        }
        for (String c : names) {
            String p = join(directory(from), c);
            if (p != null && byPath.containsKey(p)) return p;
        }
        for (String c : names) {
            List<String> hits = byPath.keySet().stream().filter(p -> p.endsWith("/templates/" + c)).sorted().toList();
            if (hits.size() == 1) return hits.get(0);
        }
        return null;
    }

    /** {@code src}/{@code href} → JavaScript or CSS module in the repository, or null. */
    static String resource(Path root, String from, String raw, boolean script) {
        String v = raw.strip();
        if (v.startsWith("@{")) v = v.substring(2, v.endsWith("}") ? v.length() - 1 : v.length());
        v = v.replaceAll("[?#].*$", "").replaceAll("\\(.*$", "");
        if (v.isEmpty() || v.matches("(?i)^([a-z][a-z0-9+.-]*:|//).*") || v.contains("${") || v.contains("{{") || v.contains("{%")) return null;
        List<String> candidates = new ArrayList<>();
        if (v.startsWith("/")) {
            String p = v.substring(1);
            for (String r : STATIC_ROOTS) candidates.add(r + p);
            for (String dir = directory(from); !dir.isEmpty(); dir = directory(dir)) candidates.add(dir + "/" + p);
        } else {
            String p = join(directory(from), v);
            if (p != null) candidates.add(p);
        }
        for (String c : candidates) {
            if (c == null || !Files.isRegularFile(root.resolve(c))) continue;
            String lower = c.toLowerCase(Locale.ROOT);
            if (lower.endsWith(".css")) return CssLanguageParser.moduleName(c);
            if (JavaScriptLanguageParser.EXTS.stream().anyMatch(lower::endsWith)) return JavaScriptLanguageParser.moduleName(c);
            return null;
        }
        return null;
    }
}

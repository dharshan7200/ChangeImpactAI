package com.changeimpact.parser.libraries;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Reads the dependencies a Gradle build declares — as text. Gradle itself is never run (a build script is code), so
 * only declarations written as literals are understood:
 * <ul>
 *   <li>{@code implementation 'g:a:v'}, {@code testImplementation("g:a:v")}, {@code api}, {@code compileOnly},
 *       {@code runtimeOnly}, {@code testCompileOnly}, … (Groovy and Kotlin DSL);</li>
 *   <li>version-catalog references {@code libs.foo.bar} from {@code gradle/libs.versions.toml};</li>
 *   <li>BOMs: {@code platform('g:a:v')} / {@code enforcedPlatform}, {@code mavenBom 'g:a:v'} and the Spring Boot
 *       plugin version ({@code org.springframework.boot:spring-boot-dependencies:<version>});</li>
 *   <li>{@code $name} / {@code ${name}} placeholders from {@code gradle.properties} and simple {@code ext}/{@code val}
 *       assignments.</li>
 * </ul>
 * Anything computed in code (loops, functions, convention plugins) is not seen; the caller reports what was found.
 */
public final class GradleBuildReader {

    public record Coordinate(String groupId, String artifactId, String version, String scope) {
        public String key() { return groupId + ":" + artifactId; }
    }

    public record Result(List<Coordinate> dependencies, List<Coordinate> boms, List<String> unparsed) {}

    private static final int MAX_FILES = 200;
    private static final Pattern CONFIG = Pattern.compile(
            "(?m)^\\s*(implementation|api|compileOnly|runtimeOnly|developmentOnly|compile|testImplementation|testCompileOnly|testRuntimeOnly"
            + "|testCompile|testFixturesImplementation|testFixturesApi|integrationTestImplementation)\\s*\\(?\\s*(.*)$");
    private static final Pattern LITERAL = Pattern.compile("[\"']([A-Za-z0-9_.\\-]+):([A-Za-z0-9_.\\-]+)(?::([^\"'@:]+))?(?::[^\"'@]*)?(?:@[a-z]+)?[\"']");
    private static final Pattern PLATFORM = Pattern.compile("(?:enforcedPlatform|platform)\\s*\\(\\s*(?:[\"']([^\"']+)[\"']|(libs\\.[A-Za-z0-9_.]+))");
    private static final Pattern MAVEN_BOM = Pattern.compile("mavenBom\\s*\\(?\\s*[\"']([^\"']+)[\"']");
    private static final Pattern CATALOG_REF = Pattern.compile("\\blibs\\.([A-Za-z0-9_.]+)");
    private static final Pattern BOOT_PLUGIN = Pattern.compile("id\\s*\\(?\\s*[\"']org\\.springframework\\.boot[\"']\\s*\\)?\\s*version\\s*\\(?\\s*[\"']([^\"']+)[\"']");
    private static final Pattern PLUGIN_ALIAS = Pattern.compile("alias\\s*\\(\\s*libs\\.plugins\\.([A-Za-z0-9_.]+)\\s*\\)");
    private static final Pattern ASSIGN = Pattern.compile("(?m)^\\s*(?:val|def|ext\\.|set\\(\\s*\")?\\s*([A-Za-z_][A-Za-z0-9_]*)\"?\\s*[=,]\\s*[\"']([^\"'$]+)[\"']");
    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{?([A-Za-z_][A-Za-z0-9_.]*)}?");

    private GradleBuildReader() {}

    public static boolean isGradleBuild(Path root) {
        return Files.exists(root.resolve("build.gradle")) || Files.exists(root.resolve("build.gradle.kts"))
                || Files.exists(root.resolve("settings.gradle")) || Files.exists(root.resolve("settings.gradle.kts"));
    }

    public static Result read(Path root) throws IOException {
        Map<String, String> props = new HashMap<>(properties(root.resolve("gradle.properties")));
        Catalog catalog = Catalog.read(root.resolve("gradle/libs.versions.toml"));
        List<Path> scripts;
        try (Stream<Path> s = Files.walk(root, 6)) {
            scripts = s.filter(p -> {
                        String n = p.getFileName().toString();
                        return (n.equals("build.gradle") || n.equals("build.gradle.kts")) && !p.toString().contains("/build/")
                                && !p.toString().contains("/.gradle/") && !p.toString().contains("/buildSrc/");
                    })
                    .sorted().limit(MAX_FILES).toList();
        }
        List<String> texts = new ArrayList<>();
        for (Path p : scripts) texts.add(stripComments(Files.readString(p)));
        for (String t : texts) {
            Matcher m = ASSIGN.matcher(t);
            while (m.find()) props.putIfAbsent(m.group(1), m.group(2));
        }

        LinkedHashMap<String, Coordinate> deps = new LinkedHashMap<>();
        LinkedHashMap<String, Coordinate> boms = new LinkedHashMap<>();
        List<String> unparsed = new ArrayList<>();
        for (String t : texts) {
            Matcher bp = BOOT_PLUGIN.matcher(t);
            if (bp.find()) bom(boms, "org.springframework.boot:spring-boot-dependencies:" + interpolate(bp.group(1), props));
            Matcher pa = PLUGIN_ALIAS.matcher(t);
            while (pa.find()) {
                String[] plugin = catalog.plugin(pa.group(1));
                if (plugin != null && plugin[0].equals("org.springframework.boot") && plugin[1] != null) {
                    bom(boms, "org.springframework.boot:spring-boot-dependencies:" + plugin[1]);
                }
            }
            Matcher mb = MAVEN_BOM.matcher(t);
            while (mb.find()) bom(boms, interpolate(mb.group(1), props));

            Matcher line = CONFIG.matcher(t);
            while (line.find()) {
                String scope = line.group(1).toLowerCase(Locale.ROOT).startsWith("test") || line.group(1).startsWith("integrationTest")
                        || line.group(1).startsWith("testFixtures") ? "test" : line.group(1).equals("compileOnly") ? "provided" : "compile";
                String rest = line.group(2);
                Matcher pf = PLATFORM.matcher(rest);
                if (pf.find()) {
                    if (pf.group(1) != null) bom(boms, interpolate(pf.group(1), props));
                    else {
                        String[] c = catalog.library(pf.group(2).substring(5));
                        if (c != null && c[2] != null) bom(boms, c[0] + ":" + c[1] + ":" + c[2]);
                    }
                    continue;
                }
                Matcher lit = LITERAL.matcher(rest);
                if (lit.find()) {
                    String v = lit.group(3) == null ? null : interpolate(lit.group(3), props);
                    if (v != null && v.contains("$")) v = null;
                    deps.putIfAbsent(lit.group(1) + ":" + lit.group(2), new Coordinate(lit.group(1), lit.group(2), v, scope));
                    continue;
                }
                Matcher ref = CATALOG_REF.matcher(rest);
                if (ref.find() && !ref.group(1).startsWith("plugins.") && !ref.group(1).startsWith("bundles.")) {
                    String[] c = catalog.library(ref.group(1));
                    if (c != null) deps.putIfAbsent(c[0] + ":" + c[1], new Coordinate(c[0], c[1], c[2], scope));
                    else unparsed.add(rest.strip());
                    continue;
                }
                if (ref.find(0) && ref.group(1).startsWith("bundles.")) {
                    for (String[] c : catalog.bundle(ref.group(1).substring("bundles.".length()))) {
                        deps.putIfAbsent(c[0] + ":" + c[1], new Coordinate(c[0], c[1], c[2], scope));
                    }
                    continue;
                }
                if (!rest.contains("project(") && !rest.contains("files(") && !rest.contains("fileTree(") && !rest.isBlank()) {
                    unparsed.add(rest.strip());
                }
            }
        }
        return new Result(List.copyOf(deps.values()), List.copyOf(boms.values()), unparsed.stream().distinct().limit(20).toList());
    }

    private static void bom(Map<String, Coordinate> boms, String gav) {
        String[] p = gav.split(":");
        if (p.length >= 3 && !p[2].contains("$")) boms.putIfAbsent(p[0] + ":" + p[1], new Coordinate(p[0], p[1], p[2], "import"));
    }

    static String interpolate(String s, Map<String, String> props) {
        Matcher m = PLACEHOLDER.matcher(s);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String v = props.get(m.group(1));
            m.appendReplacement(sb, Matcher.quoteReplacement(v == null ? m.group() : v));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String stripComments(String s) {
        return s.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)//.*$", "");
    }

    static Map<String, String> properties(Path file) throws IOException {
        Map<String, String> out = new HashMap<>();
        if (!Files.isRegularFile(file)) return out;
        Properties p = new Properties();
        try (var in = Files.newInputStream(file)) {
            p.load(in);
        }
        p.forEach((k, v) -> out.put(String.valueOf(k), String.valueOf(v).strip()));
        return out;
    }

    /** Minimal reader of the {@code [versions]}, {@code [libraries]}, {@code [bundles]} and {@code [plugins]} tables. */
    static final class Catalog {
        private final Map<String, String> versions = new HashMap<>();
        private final Map<String, String[]> libraries = new HashMap<>();
        private final Map<String, List<String>> bundles = new HashMap<>();
        private final Map<String, String[]> plugins = new HashMap<>();

        static Catalog read(Path file) throws IOException {
            Catalog c = new Catalog();
            if (!Files.isRegularFile(file)) return c;
            String section = "";
            Map<String, String> rawLibs = new LinkedHashMap<>(), rawPlugins = new LinkedHashMap<>();
            for (String line : Files.readAllLines(file)) {
                String l = line.replaceAll("\\s+#.*$", "").strip();
                if (l.isEmpty() || l.startsWith("#")) continue;
                if (l.startsWith("[")) { section = l.replaceAll("[\\[\\]\\s]", ""); continue; }
                int eq = l.indexOf('=');
                if (eq < 0) continue;
                String key = norm(l.substring(0, eq).strip().replace("\"", "")), value = l.substring(eq + 1).strip();
                switch (section) {
                    case "versions" -> c.versions.put(l.substring(0, eq).strip().replace("\"", ""), unquote(value));
                    case "libraries" -> rawLibs.put(key, value);
                    case "plugins" -> rawPlugins.put(key, value);
                    case "bundles" -> c.bundles.put(key, Arrays.stream(value.replaceAll("[\\[\\]\"]", "").split(","))
                            .map(String::strip).filter(x -> !x.isEmpty()).map(Catalog::norm).toList());
                    default -> { }
                }
            }
            rawLibs.forEach((k, v) -> c.libraries.put(k, c.parseLibrary(v)));
            rawPlugins.forEach((k, v) -> c.plugins.put(k, new String[]{field(v, "id"), c.version(v)}));
            return c;
        }

        private String[] parseLibrary(String v) {
            if (v.startsWith("\"")) {
                String[] p = unquote(v).split(":");
                return new String[]{p[0], p.length > 1 ? p[1] : "", p.length > 2 ? p[2] : null};
            }
            String module = field(v, "module");
            String g, a;
            if (module != null) { String[] p = module.split(":"); g = p[0]; a = p.length > 1 ? p[1] : ""; }
            else { g = field(v, "group"); a = field(v, "name"); }
            return new String[]{g, a, version(v)};
        }

        private String version(String v) {
            String ref = field(v, "version.ref");
            if (ref != null) return versions.get(ref);
            Matcher m = Pattern.compile("version\\s*=\\s*\"([^\"]+)\"").matcher(v);
            return m.find() ? m.group(1) : null;
        }

        private static String field(String v, String name) {
            Matcher m = Pattern.compile("(?<![A-Za-z.])" + Pattern.quote(name) + "\\s*=\\s*\"([^\"]+)\"").matcher(v);
            return m.find() ? m.group(1) : null;
        }

        private static String unquote(String v) { return v.replaceAll("^\"|\"$", ""); }

        /** Catalog aliases are referenced with '.', declared with '-', '_' or '.'. */
        static String norm(String alias) { return alias.replace('-', '.').replace('_', '.').toLowerCase(Locale.ROOT); }

        String[] library(String ref) { String[] c = libraries.get(norm(ref)); return c == null || c[0] == null ? null : c; }

        String[] plugin(String ref) { return plugins.get(norm(ref)); }

        List<String[]> bundle(String ref) {
            return bundles.getOrDefault(norm(ref), List.of()).stream().map(libraries::get).filter(Objects::nonNull).toList();
        }
    }
}

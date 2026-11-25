package com.changeimpact.securityanalysis.service;

import org.springframework.stereotype.Component;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;
import java.io.StringReader;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Compares declared dependencies of a Maven {@code pom.xml} or Gradle build file between two commits.
 * Properties ({@code ${x.version}}) declared in the same POM are resolved; versions inherited from a parent
 * or a BOM stay "(managed)". XML is parsed without DTD/external entity support.
 */
@Component
public class BuildDependencyDiff {

    public record Change(String coordinate, String before, String after, String type) {}

    private static final Pattern GRADLE = Pattern.compile(
            "(?:implementation|api|compileOnly|runtimeOnly|testImplementation|testRuntimeOnly|annotationProcessor|kapt)"
                    + "\\s*\\(?\\s*[\"']([\\w.\\-]+):([\\w.\\-]+):([^\"'@]+)[\"']");

    public List<Change> diff(String path, String before, String after) {
        Map<String, String> a = parse(path, before);
        Map<String, String> b = parse(path, after);
        List<Change> out = new ArrayList<>();
        Set<String> keys = new TreeSet<>(a.keySet());
        keys.addAll(b.keySet());
        for (String k : keys) {
            String va = a.get(k), vb = b.get(k);
            if (va == null) out.add(new Change(k, null, vb, "ADDED"));
            else if (vb == null) out.add(new Change(k, va, null, "REMOVED"));
            else if (!va.equals(vb)) out.add(new Change(k, va, vb, direction(va, vb)));
        }
        return out;
    }

    Map<String, String> parse(String path, String content) {
        if (content == null || content.isBlank()) return Map.of();
        return path.endsWith(".xml") ? parsePom(content) : parseGradle(content);
    }

    private Map<String, String> parseGradle(String content) {
        Map<String, String> deps = new TreeMap<>();
        Matcher m = GRADLE.matcher(content);
        while (m.find()) deps.put(m.group(1) + ":" + m.group(2), m.group(3).trim());
        return deps;
    }

    private Map<String, String> parsePom(String content) {
        Map<String, String> props = new HashMap<>();
        List<String[]> raw = new ArrayList<>();
        try {
            XMLInputFactory f = XMLInputFactory.newFactory();
            f.setProperty(XMLInputFactory.SUPPORT_DTD, false);
            f.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
            XMLStreamReader r = f.createXMLStreamReader(new StringReader(content));
            Deque<String> stack = new ArrayDeque<>();
            String[] dep = null;
            StringBuilder text = new StringBuilder();
            String projectVersion = null;
            while (r.hasNext()) {
                int ev = r.next();
                if (ev == XMLStreamConstants.START_ELEMENT) {
                    stack.push(r.getLocalName());
                    text.setLength(0);
                    if ("dependency".equals(r.getLocalName())) dep = new String[3];
                } else if (ev == XMLStreamConstants.CHARACTERS) {
                    text.append(r.getText());
                } else if (ev == XMLStreamConstants.END_ELEMENT) {
                    String name = stack.pop();
                    String parent = stack.peek();
                    String value = text.toString().trim();
                    if ("properties".equals(parent)) props.put(name, value);
                    if ("project".equals(parent) && "version".equals(name)) projectVersion = value;
                    if (dep != null && "dependency".equals(parent)) {
                        switch (name) {
                            case "groupId" -> dep[0] = value;
                            case "artifactId" -> dep[1] = value;
                            case "version" -> dep[2] = value;
                            default -> { }
                        }
                    }
                    if ("dependency".equals(name) && dep != null) {
                        if (dep[0] != null && dep[1] != null) raw.add(dep);
                        dep = null;
                    }
                    text.setLength(0);
                }
            }
            if (projectVersion != null) props.put("project.version", projectVersion);
        } catch (Exception e) {
            return Map.of();
        }
        Map<String, String> deps = new TreeMap<>();
        for (String[] d : raw) {
            deps.put(resolve(d[0], props) + ":" + resolve(d[1], props), d[2] == null ? "(managed)" : resolve(d[2], props));
        }
        return deps;
    }

    private static String resolve(String v, Map<String, String> props) {
        String out = v;
        for (int i = 0; i < 5 && out != null && out.contains("${"); i++) {
            Matcher m = Pattern.compile("\\$\\{([^}]+)}").matcher(out);
            StringBuilder sb = new StringBuilder();
            while (m.find()) m.appendReplacement(sb, Matcher.quoteReplacement(props.getOrDefault(m.group(1), m.group(0))));
            m.appendTail(sb);
            if (sb.toString().equals(out)) break;
            out = sb.toString();
        }
        return out;
    }

    /**
     * 1-based line declaring {@code group:artifact} in a build file (the artifactId element of a POM, the
     * coordinate string of a Gradle file), or null.
     */
    public static Integer lineOf(String content, String coordinate) {
        if (content == null || coordinate == null || !coordinate.contains(":")) return null;
        String artifact = coordinate.substring(coordinate.indexOf(':') + 1);
        String[] lines = content.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String l = lines[i];
            if (l.contains("<artifactId>" + artifact + "</artifactId>") || l.contains(coordinate + ":")) return i + 1;
        }
        return null;
    }

    /** UPGRADED / DOWNGRADED by numeric segment comparison, CHANGED if not comparable. */
    static String direction(String before, String after) {
        String[] x = before.split("[.\\-]"), y = after.split("[.\\-]");
        for (int i = 0; i < Math.min(x.length, y.length); i++) {
            try {
                int c = Integer.compare(Integer.parseInt(x[i]), Integer.parseInt(y[i]));
                if (c != 0) return c < 0 ? "UPGRADED" : "DOWNGRADED";
            } catch (NumberFormatException e) {
                return "CHANGED";
            }
        }
        return "CHANGED";
    }
}

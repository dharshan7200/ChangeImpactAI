package com.changeimpact.diff.semantic;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.StringReader;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Detects changed Spring configuration keys ({@code application*.yml|yaml|properties}) and links them to
 * the Java symbols that bind them through {@code @Value("${key}")}, {@code @ConfigurationProperties(prefix)}
 * or {@code @ConditionalOnProperty}.
 *
 * <p>Limitations (documented): relaxed binding variants ({@code my-key} vs {@code myKey}) are normalised by
 * removing dashes/underscores and lower-casing; programmatic access through {@code Environment.getProperty}
 * is not detected.
 */
@Component
@Slf4j
public class ConfigChangeAnalyzer {

    private static final Pattern STRING_LITERAL = Pattern.compile("\"([^\"]*)\"");
    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([^}:]+)");

    public record ConfigKeyChange(String file, String key, String changeType, String before, String after) {}

    /** Flattened key changes between two versions of one config file (either version may be null). */
    public List<ConfigKeyChange> diffKeys(String file, String before, String after) {
        Map<String, String> b = flatten(file, before);
        Map<String, String> a = flatten(file, after);
        List<ConfigKeyChange> changes = new ArrayList<>();
        Set<String> keys = new TreeSet<>(b.keySet());
        keys.addAll(a.keySet());
        for (String key : keys) {
            String vb = b.get(key);
            String va = a.get(key);
            if (vb == null) {
                changes.add(new ConfigKeyChange(file, key, "ADDED", null, mask(key, va)));
            } else if (va == null) {
                changes.add(new ConfigKeyChange(file, key, "REMOVED", mask(key, vb), null));
            } else if (!vb.equals(va)) {
                changes.add(new ConfigKeyChange(file, key, "MODIFIED", mask(key, vb), mask(key, va)));
            }
        }
        return changes;
    }

    /**
     * Returns, for each head symbol whose annotations bind a changed key, the list of keys it binds.
     */
    public Map<SymbolSnapshot, List<ConfigKeyChange>> bindingSymbols(Collection<SymbolSnapshot> headSymbols,
                                                                   List<ConfigKeyChange> keyChanges) {
        Map<SymbolSnapshot, List<ConfigKeyChange>> result = new LinkedHashMap<>();
        if (keyChanges.isEmpty()) {
            return result;
        }
        for (SymbolSnapshot s : headSymbols) {
            String text = s.annotationText();
            if (text == null || !(text.contains("Value") || text.contains("ConfigurationProperties")
                    || text.contains("ConditionalOnProperty"))) {
                continue;
            }
            for (String line : text.lines().toList()) {
                String name = SemanticChangeDetector.annotationName(line);
                List<String> literals = new ArrayList<>();
                Matcher m = STRING_LITERAL.matcher(line);
                while (m.find()) {
                    literals.add(m.group(1));
                }
                for (ConfigKeyChange change : keyChanges) {
                    String key = relaxed(change.key());
                    boolean bound = switch (name) {
                        case "Value" -> literals.stream().anyMatch(l -> placeholders(l).stream()
                                .anyMatch(p -> relaxed(p).equals(key)));
                        case "ConfigurationProperties" -> literals.stream().anyMatch(l -> !l.isBlank()
                                && key.startsWith(relaxed(l) + "."));
                        case "ConditionalOnProperty" -> literals.stream().anyMatch(l -> !l.isBlank()
                                && (key.equals(relaxed(l)) || key.startsWith(relaxed(l) + ".")
                                || key.endsWith("." + relaxed(l))));
                        default -> false;
                    };
                    if (bound) {
                        result.computeIfAbsent(s, k -> new ArrayList<>()).add(change);
                    }
                }
            }
        }
        return result;
    }

    // ------------------------------------------------------------------

    Map<String, String> flatten(String file, String content) {
        Map<String, String> out = new TreeMap<>();
        if (content == null || content.isBlank()) {
            return out;
        }
        try {
            if (file.endsWith(".properties")) {
                Properties p = new Properties();
                p.load(new StringReader(content));
                p.stringPropertyNames().forEach(k -> out.put(k, p.getProperty(k)));
            } else {
                Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
                for (Object document : yaml.loadAll(content)) {
                    flattenYaml("", document, out); // multi-document files (profiles) merge their keys
                }
            }
        } catch (Exception e) {
            log.debug("Could not parse config file {}: {}", file, e.getMessage());
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private void flattenYaml(String prefix, Object node, Map<String, String> out) {
        if (node instanceof Map<?, ?> map) {
            map.forEach((k, v) -> flattenYaml(prefix.isEmpty() ? String.valueOf(k) : prefix + "." + k, v, out));
        } else if (node instanceof List<?> list) {
            for (int i = 0; i < list.size(); i++) {
                flattenYaml(prefix + "[" + i + "]", list.get(i), out);
            }
        } else if (!prefix.isEmpty()) {
            out.put(prefix, String.valueOf(node));
        }
    }

    private static List<String> placeholders(String literal) {
        List<String> keys = new ArrayList<>();
        Matcher m = PLACEHOLDER.matcher(literal);
        while (m.find()) {
            keys.add(m.group(1).trim());
        }
        return keys;
    }

    private static String relaxed(String key) {
        return key.replace("-", "").replace("_", "").toLowerCase(Locale.ROOT);
    }

    /** Values of secret-looking keys are never stored or shown. */
    static String mask(String key, String value) {
        String k = key.toLowerCase(Locale.ROOT);
        if (k.contains("password") || k.contains("secret") || k.contains("token") || k.contains("credential")
                || k.endsWith("key") || k.contains("private")) {
            return "******";
        }
        return value != null && value.length() > 200 ? value.substring(0, 200) + "…" : value;
    }
}

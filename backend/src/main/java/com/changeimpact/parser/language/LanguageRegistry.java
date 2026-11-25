package com.changeimpact.parser.language;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * The languages analysed in this installation: {@code app.analysis.languages} (env {@code ANALYSIS_LANGUAGES}, default
 * {@code all}), restricted to parsers that can run here. {@code all} enables every language; a list
 * ({@code java,python,javascript}) enables those; {@code -name} removes one ({@code all,-sql}). Java is always included.
 */
@Component
@Slf4j
public class LanguageRegistry {

    private final List<LanguageParser> enabled;

    /** Common names accepted for a language. */
    static final Map<String, String> ALIASES = Map.of("typescript", "javascript", "ts", "javascript", "js", "javascript",
            "react", "javascript", "c#", "csharp", "cs", "csharp", "golang", "go", "rb", "ruby", "py", "python");

    public LanguageRegistry(List<LanguageParser> parsers, @Value("${app.analysis.languages:all}") String languages) {
        Set<String> wanted = new TreeSet<>();
        Set<String> removed = new TreeSet<>();
        for (String l : languages.split(",")) {
            String token = l.trim().toLowerCase(Locale.ROOT);
            if (token.isEmpty()) continue;
            if (token.equals("all")) parsers.forEach(p -> wanted.add(p.language()));
            else if (token.startsWith("-")) removed.add(token.substring(1));
            else wanted.add(ALIASES.getOrDefault(token, token));
        }
        removed.forEach(r -> wanted.remove(ALIASES.getOrDefault(r, r)));
        wanted.add("java");
        List<LanguageParser> out = new ArrayList<>();
        for (LanguageParser p : parsers.stream().sorted(Comparator.comparing(LanguageParser::language)).toList()) {
            if (!wanted.remove(p.language())) continue;
            if (!p.available()) {
                log.warn("Language '{}' is enabled but its parser cannot run in this environment; it is skipped", p.language());
                continue;
            }
            if (p.experimental()) log.info("Experimental language analysis enabled: {}", p.language());
            out.add(p);
        }
        wanted.forEach(l -> log.warn("Unknown language '{}' in app.analysis.languages is ignored", l));
        this.enabled = List.copyOf(out);
        log.info("Languages analysed ({}): {}", enabled.size(), versionedLanguages());
        Set<String> missing = new TreeSet<>();
        parsers.forEach(p -> missing.add(p.language()));
        enabled.forEach(p -> missing.remove(p.language()));
        if (!missing.isEmpty()) log.info("Languages not analysed in this installation: {} (ANALYSIS_LANGUAGES={})", missing, languages);
    }

    /** Enabled, available parsers, Java first then alphabetical. */
    public List<LanguageParser> parsers() {
        return enabled.stream().sorted(Comparator.comparing((LanguageParser p) -> !p.language().equals("java"))
                .thenComparing(LanguageParser::language)).toList();
    }

    public List<String> languages() {
        return parsers().stream().map(LanguageParser::language).toList();
    }

    /** {@code language@version} of every enabled parser, as recorded per analysed commit. */
    public List<String> versionedLanguages() {
        return parsers().stream().map(p -> p.language() + "@" + p.version()).toList();
    }

    /** {@code java} → {@code java@1}: tokens recorded before versions were added. */
    public static String normalise(String token) {
        return token.contains("@") ? token : token + "@1";
    }

    public Set<String> extensions() {
        Set<String> out = new TreeSet<>();
        enabled.forEach(p -> out.addAll(p.extensions()));
        return out;
    }
}

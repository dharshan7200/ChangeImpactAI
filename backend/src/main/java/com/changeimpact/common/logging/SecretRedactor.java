package com.changeimpact.common.logging;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Masks credentials that may end up in log lines or exception messages: PEM private keys, credentials embedded in
 * URLs, GitHub tokens (classic, fine-grained, installation, OAuth), JWTs, Google API keys, bearer/basic authorization
 * values and {@code password=}/{@code secret:}-style key/value pairs. Rules apply in order.
 */
public final class SecretRedactor {

    private static final String MASK = "[REDACTED]";

    private record Rule(Pattern pattern, String replacement) {}

    private static final List<Rule> RULES = List.of(
            new Rule(Pattern.compile("-----BEGIN [A-Z ]*PRIVATE KEY-----[\\s\\S]*?-----END [A-Z ]*PRIVATE KEY-----"), MASK),
            new Rule(Pattern.compile("(?i)(https?://)[^/\\s:@]+:[^/\\s@]+@"), "$1" + MASK + "@"),
            new Rule(Pattern.compile("\\b(?:gh[pousr]_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,})"), MASK),
            new Rule(Pattern.compile("\\beyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}"), MASK),
            new Rule(Pattern.compile("\\bAIza[0-9A-Za-z_-]{35}"), MASK),
            new Rule(Pattern.compile("(?i)\\b(bearer|basic|token)\\s+[A-Za-z0-9._~+/=-]{12,}"), "$1 " + MASK),
            new Rule(Pattern.compile("(?i)((?:password|passwd|secret|api[_-]?key|access[_-]?token|refresh[_-]?token|client[_-]?secret)"
                    + "\\s*[=:]\\s*)(?!\\[REDACTED\\])[^\\s,;&\"'@]+"), "$1" + MASK));

    private SecretRedactor() {}

    public static String redact(String text) {
        if (text == null || text.isEmpty()) return text;
        String out = text;
        for (Rule r : RULES) out = r.pattern().matcher(out).replaceAll(r.replacement());
        return out;
    }
}

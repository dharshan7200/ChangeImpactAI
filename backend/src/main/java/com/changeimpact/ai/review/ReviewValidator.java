package com.changeimpact.ai.review;

import tools.jackson.databind.JsonNode;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic checks of an AI answer against the evidence it was given — the language model never has the last
 * word. A finding is {@code VERIFIED} only when
 * <ul>
 *   <li>it cites at least one evidence item that exists (unknown ids are dropped and reported), and</li>
 *   <li>every code identifier it names exists: in the cited/provided evidence text or among the symbols of the
 *       analysed commits. Checked are names in backticks and, in plain text, tokens shaped like code: {@code Type#member},
 *       {@code a.b(}, lowerCamelCase with an inner capital ({@code processFindForm}) and snake_case
 *       ({@code rebuild_auth}). Capitalised plain words ({@code GitHub}, {@code JaCoCo}) are not checked, so product
 *       names do not count as inventions.</li>
 * </ul>
 * Otherwise it is kept but marked {@code UNVERIFIED} with the reasons, so the UI can show it as such.
 */
public final class ReviewValidator {

    /**
     * Code spans, paired in order. No length limit: a limit made a long qualified name (e.g. a method with its
     * parameter list) unmatchable, which shifted the pairing so that prose between two spans was read as code.
     */
    private static final Pattern BACKTICK = Pattern.compile("`([^`\\n]+)`");
    private static final Pattern IDENT = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    /** Code-shaped tokens outside backticks (see class comment). URLs are removed before matching. */
    private static final Pattern PLAIN_CODE = Pattern.compile(
            "(?<![\\w/.#-])(?:[A-Za-z_][A-Za-z0-9_]*(?:[.#][A-Za-z_][A-Za-z0-9_]*)*[#][A-Za-z_][A-Za-z0-9_]*"
            + "|[A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)+(?=\\()"
            + "|[a-z][a-z0-9]*[A-Z][A-Za-z0-9]*"
            + "|[a-z][a-z0-9]*(?:_[a-z0-9]+)+)(?![\\w])");
    private static final Pattern URL = Pattern.compile("https?://\\S+");
    /** Words that may appear in backticks without being code of this repository. */
    private static final Set<String> COMMON = Set.of("null", "true", "false", "this", "new", "return", "void", "int",
            "long", "boolean", "String", "Integer", "List", "Map", "Set", "Optional", "Object", "self", "None", "True", "False",
            "public", "private", "protected", "static", "final", "throw", "throws", "if", "else", "for", "while", "try", "catch",
            "Exception", "RuntimeException", "IllegalArgumentException", "NullPointerException", "Override",
            "Math", "Objects", "Arrays", "Collections", "System", "Thread", "len", "isinstance", "str", "dict", "list");

    public record Verdict(String status, List<String> validCitations, List<String> invalidCitations, List<String> unknownMentions) {}

    private final Set<String> evidenceIds;
    private final String evidenceText;
    private final Set<String> knownNames;

    /**
     * @param evidenceIds  ids the model was given (C1, I3, ...)
     * @param evidenceText all evidence text (code, names, paths) the model saw
     * @param knownNames   simple and qualified names of symbols of the analysed commits
     */
    public ReviewValidator(Set<String> evidenceIds, String evidenceText, Set<String> knownNames) {
        this.evidenceIds = evidenceIds;
        this.evidenceText = evidenceText;
        this.knownNames = knownNames;
    }

    public Verdict check(JsonNode citations, String... texts) {
        List<String> valid = new ArrayList<>(), invalid = new ArrayList<>();
        if (citations != null) {
            for (JsonNode c : citations) {
                String id = c.asString("").trim().replaceAll("^\\[|]$", "");
                if (id.isEmpty()) continue;
                if (evidenceIds.contains(id)) { if (!valid.contains(id)) valid.add(id); } else invalid.add(id);
            }
        }
        List<String> unknown = new ArrayList<>();
        for (String t : texts) {
            if (t == null) continue;
            Matcher m = BACKTICK.matcher(t);
            while (m.find()) {
                String mention = m.group(1).trim();
                if (!known(mention) && !unknown.contains(mention)) unknown.add(mention);
            }
            Matcher p = PLAIN_CODE.matcher(URL.matcher(BACKTICK.matcher(t).replaceAll(" ")).replaceAll(" "));
            while (p.find()) {
                String mention = p.group();
                if (!known(mention) && !unknown.contains(mention)) unknown.add(mention);
            }
        }
        String status = !valid.isEmpty() && unknown.isEmpty() ? "VERIFIED" : "UNVERIFIED";
        return new Verdict(status, valid, invalid, unknown);
    }

    /**
     * A mention is known when it occurs in the evidence, or each identifier in it is a known symbol name. A literal
     * without identifiers ({@code [[}, {@code 404}, {@code ""}) names no code and is accepted.
     */
    boolean known(String mention) {
        if (evidenceText.contains(mention)) return true;
        Matcher m = IDENT.matcher(mention);
        while (m.find()) {
            String id = m.group();
            if (id.length() <= 2 || COMMON.contains(id)) continue;
            if (!knownNames.contains(id) && !evidenceText.contains(id)) return false;
        }
        return true;
    }
}

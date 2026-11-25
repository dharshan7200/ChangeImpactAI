package com.changeimpact.architecture.service;

import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * A user-supplied regular expression that cannot hang the server. Matching runs on a character sequence that counts
 * character reads and aborts after {@link #MAX_STEPS} — a deterministic budget (unlike a timeout), so a rule either
 * always or never exceeds it. Catastrophic backtracking (e.g. {@code (a+)+b}) therefore ends with
 * {@link BudgetExceeded} instead of blocking a worker thread.
 */
public final class BoundedPattern {

    public static final int MAX_LENGTH = 300;
    static final int MAX_STEPS = 200_000;

    public static final class BudgetExceeded extends RuntimeException {
        BudgetExceeded(String pattern) {
            super("Pattern '" + pattern + "' needs too much backtracking; simplify it (e.g. avoid nested quantifiers like (a+)+)");
        }
    }

    private final Pattern pattern;

    private BoundedPattern(Pattern pattern) {
        this.pattern = pattern;
    }

    /** Compiles {@code regex} (case-insensitive, "find" semantics); IllegalArgumentException with a readable reason. */
    public static BoundedPattern compile(String regex) {
        if (regex == null || regex.isBlank()) throw new IllegalArgumentException("pattern must not be empty");
        if (regex.length() > MAX_LENGTH) throw new IllegalArgumentException("pattern longer than " + MAX_LENGTH + " characters");
        try {
            return new BoundedPattern(Pattern.compile(regex, Pattern.CASE_INSENSITIVE));
        } catch (PatternSyntaxException e) {
            throw new IllegalArgumentException("invalid regular expression: " + e.getDescription() + " near index " + e.getIndex());
        }
    }

    public boolean find(String input) {
        return pattern.matcher(new CountingSequence(input, pattern.pattern(), new int[]{0})).find();
    }

    @Override
    public String toString() {
        return pattern.pattern();
    }

    private record CountingSequence(String s, String pattern, int[] steps) implements CharSequence {
        @Override public int length() { return s.length(); }
        @Override public char charAt(int index) {
            if (++steps[0] > MAX_STEPS) throw new BudgetExceeded(pattern);
            return s.charAt(index);
        }
        @Override public CharSequence subSequence(int start, int end) { return new CountingSequence(s.substring(start, end), pattern, steps); }
        @Override public String toString() { return s; }
    }
}

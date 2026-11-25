package com.changeimpact.diff.model;

/**
 * A changed region of a file, 1-based and inclusive. For a pure insertion the old range is empty
 * ({@code oldEnd < oldStart}); for a pure deletion the new range is empty.
 */
public record Hunk(int oldStart, int oldEnd, int newStart, int newEnd) {

    public boolean overlapsNew(int start, int end) {
        return newEnd >= newStart && newStart <= end && newEnd >= start;
    }

    public boolean overlapsOld(int start, int end) {
        return oldEnd >= oldStart && oldStart <= end && oldEnd >= start;
    }
}

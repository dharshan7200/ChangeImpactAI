package com.changeimpact.diff.model;

/** Resolved commits of a comparison. {@code base} may be the merge-base of the requested refs. */
public record CommitRange(String base, String head, boolean usedMergeBase) {}

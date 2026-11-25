package com.changeimpact.diff.model;

import java.util.List;

/**
 * One file-level change between two commits.
 *
 * @param path       new path (old path for deletions)
 * @param oldPath    previous path for renames/copies/deletions, else null
 * @param changeType ADDED, MODIFIED, DELETED, RENAMED, COPIED
 */
public record FileChange(String path, String oldPath, String changeType, FileCategory category,
                         int additions, int deletions, List<Hunk> hunks) {}

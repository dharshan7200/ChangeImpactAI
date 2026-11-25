package com.changeimpact.ingestion.service;

import java.nio.file.Path;

/**
 * Metadata record for an identified Java source file in a repository workspace.
 */
public record JavaFileMetadata(
        String relativePath,
        Path absolutePath,
        long sizeBytes,
        int lineCount,
        boolean isTestFile
) {}


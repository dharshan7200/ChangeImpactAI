package com.changeimpact.ingestion.service;

import com.changeimpact.common.config.AppProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Scans a repository directory to locate and inspect all valid Java source files,
 * filtering out excluded paths such as build artifacts, target directories,
 * node_modules, and generated sources.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class JavaFileScanner {

    private final AppProperties appProperties;

    /**
     * Walks the repository workspace and gathers metadata for all Java source files.
     *
     * @param workspaceRoot Absolute root path of the cloned repository
     * @return List of identified Java files with metadata
     */
    public List<JavaFileMetadata> scanJavaFiles(Path workspaceRoot) {
        List<String> excludedPatterns = appProperties.getIngestion().getExcludedPaths();
        int maxFiles = appProperties.getIngestion().getMaxFilesPerRepository();

        List<JavaFileMetadata> javaFiles = new ArrayList<>();

        try (Stream<Path> stream = Files.walk(workspaceRoot)) {
            stream
                    .filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> !isExcluded(workspaceRoot, path, excludedPatterns))
                    .limit(maxFiles)
                    .forEach(path -> {
                        String relativePath = workspaceRoot.relativize(path).toString().replace('\\', '/');
                        long size = 0L;
                        int lines = 0;
                        try {
                            size = Files.size(path);
                            try (Stream<String> lineStream = Files.lines(path)) {
                                lines = (int) lineStream.count();
                            }
                        } catch (Exception e) {
                            log.debug("Could not inspect file {}: {}", relativePath, e.getMessage());
                        }

                        boolean isTest = relativePath.contains("/test/") ||
                                relativePath.endsWith("Test.java") ||
                                relativePath.endsWith("Tests.java") ||
                                relativePath.endsWith("TestCase.java");

                        javaFiles.add(new JavaFileMetadata(relativePath, path, size, lines, isTest));
                    });
        } catch (IOException e) {
            log.error("Failed to scan files in workspace {}: {}", workspaceRoot, e.getMessage(), e);
        }

        log.info("Scanned repository workspace {}: found {} Java files", workspaceRoot, javaFiles.size());
        return javaFiles;
    }

    private boolean isExcluded(Path root, Path file, List<String> excludedPatterns) {
        String relative = root.relativize(file).toString().replace('\\', '/');
        for (String pattern : excludedPatterns) {
            String cleanPattern = pattern.endsWith("/") ? pattern : pattern + "/";
            if (relative.startsWith(cleanPattern) || relative.contains("/" + cleanPattern)) {
                return true;
            }
            if (relative.equals(pattern) || relative.contains("/" + pattern)) {
                return true;
            }
        }
        return false;
    }
}


package com.changeimpact.ingestion.service;

import com.changeimpact.common.config.AppProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("JavaFileScanner Unit Tests")
class JavaFileScannerTest {

    private JavaFileScanner scanner;
    private AppProperties appProperties;

    @TempDir
    Path workspaceRoot;

    @BeforeEach
    void setUp() {
        appProperties = new AppProperties();
        appProperties.getIngestion().setExcludedPaths(List.of(
                "target/", "build/", ".git/", "node_modules/"
        ));
        appProperties.getIngestion().setMaxFilesPerRepository(100);
        scanner = new JavaFileScanner(appProperties);
    }

    @Test
    @DisplayName("Should detect Java source files and exclude target or build directories")
    void shouldScanAndFilterJavaFiles() throws IOException {
        // Create valid source file
        Path srcDir = Files.createDirectories(workspaceRoot.resolve("src/main/java/com/example"));
        Files.writeString(srcDir.resolve("Service.java"), "package com.example;\npublic class Service {}\n");

        // Create test file
        Path testDir = Files.createDirectories(workspaceRoot.resolve("src/test/java/com/example"));
        Files.writeString(testDir.resolve("ServiceTest.java"), "package com.example;\npublic class ServiceTest {}\n");

        // Create target directory file that should be excluded
        Path targetDir = Files.createDirectories(workspaceRoot.resolve("target/classes/com/example"));
        Files.writeString(targetDir.resolve("Generated.java"), "package com.example;\npublic class Generated {}\n");

        // Create non-java file
        Files.writeString(srcDir.resolve("README.md"), "# Readme\n");

        List<JavaFileMetadata> files = scanner.scanJavaFiles(workspaceRoot);

        assertEquals(2, files.size(), "Should only include Service.java and ServiceTest.java");

        assertTrue(files.stream().anyMatch(f -> f.relativePath().endsWith("Service.java") && !f.isTestFile()));
        assertTrue(files.stream().anyMatch(f -> f.relativePath().endsWith("ServiceTest.java") && f.isTestFile()));
        assertFalse(files.stream().anyMatch(f -> f.relativePath().contains("target")));
        assertFalse(files.stream().anyMatch(f -> f.relativePath().endsWith("README.md")));
    }
}


package com.changeimpact.parser.java;

import com.changeimpact.parser.model.ParsedSourceFile;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JarTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Parses individual Java files and repository source trees into ASTs
 * with deterministic type solving using JavaSymbolSolver.
 */
@Component
@Slf4j
public class JavaAstParser {

    /**
     * Creates a JavaParser instance configured with type solvers pointing to the project's source roots.
     */
    public JavaParser createConfiguredParser(Path workspaceRoot) {
        return createConfiguredParser(workspaceRoot, java.util.List.of());
    }

    /**
     * With {@code libraryJars} (the project's resolved dependencies): JDK classes, then the project's sources, then
     * its jars — library calls are typed against the versions the project really uses. Without jars (no build file,
     * resolution off or failed): the earlier behaviour, where reflection also sees the platform's own classpath.
     * Jars are read by JavaParser's {@link JarTypeSolver} (class files parsed with javassist, never loaded or run).
     */
    public JavaParser createConfiguredParser(Path workspaceRoot, java.util.List<Path> libraryJars) {
        boolean libraries = libraryJars != null && !libraryJars.isEmpty();
        CombinedTypeSolver combinedTypeSolver = new CombinedTypeSolver();
        // 1. JDK reflection solver (JDK only when the project's own libraries are available)
        combinedTypeSolver.add(new ReflectionTypeSolver(libraries));

        // 2. Discover and register Java source directories in the workspace
        try {
            findSourceRoots(workspaceRoot).forEach(srcRoot -> {
                log.debug("Adding source root to JavaSymbolSolver: {}", srcRoot);
                combinedTypeSolver.add(new JavaParserTypeSolver(srcRoot.toFile()));
            });
        } catch (Exception e) {
            log.warn("Could not inspect source roots for workspace {}: {}", workspaceRoot, e.getMessage());
            // Fallback to workspace root if directory resolution encounters errors
            combinedTypeSolver.add(new JavaParserTypeSolver(workspaceRoot.toFile()));
        }

        // 3. The project's dependency jars, after its sources (a source type wins over a same-named library type)
        if (libraries) {
            int added = 0;
            for (Path jar : libraryJars) {
                try {
                    combinedTypeSolver.add(new JarTypeSolver(jar));
                    added++;
                } catch (Exception | LinkageError e) {
                    log.warn("Library jar skipped for type resolution: {} ({})", jar.getFileName(), e.getMessage());
                }
            }
            log.debug("Type resolution uses {} library jar(s)", added);
        }

        JavaSymbolSolver symbolSolver = new JavaSymbolSolver(combinedTypeSolver);
        ParserConfiguration configuration = new ParserConfiguration()
                .setSymbolResolver(symbolSolver)
                .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17);

        return new JavaParser(configuration);
    }

    /**
     * Parses a single Java file using the given configured parser.
     */
    public Optional<ParsedSourceFile> parseSourceFile(JavaParser parser, Path workspaceRoot, Path javaFile) {
        try {
            String relativePath = workspaceRoot.relativize(javaFile).toString().replace('\\', '/');
            long sizeBytes = Files.size(javaFile);
            int lineCount;
            try (Stream<String> lines = Files.lines(javaFile)) {
                lineCount = (int) lines.count();
            }

            boolean isTest = relativePath.contains("/test/") ||
                    relativePath.endsWith("Test.java") ||
                    relativePath.endsWith("Tests.java");

            Optional<CompilationUnit> cuOpt = parser.parse(javaFile).getResult();
            if (cuOpt.isEmpty()) {
                log.warn("JavaParser returned no result for file: {}", relativePath);
                return Optional.empty();
            }

            CompilationUnit cu = cuOpt.get();
            SymbolExtractionVisitor visitor = new SymbolExtractionVisitor();
            cu.accept(visitor, null);

            ParsedSourceFile parsedFile = ParsedSourceFile.builder()
                    .relativePath(relativePath)
                    .packageName(visitor.getPackageName())
                    .linesOfCode(lineCount)
                    .sizeBytes(sizeBytes)
                    .isTest(isTest)
                    .symbols(visitor.getSymbols())
                    .dependencies(visitor.getDependencies())
                    .build();

            return Optional.of(parsedFile);

        } catch (IOException e) {
            log.error("Failed to read file {}: {}", javaFile, e.getMessage());
            return Optional.empty();
        } catch (Exception e) {
            log.error("Failed to parse AST for {}: {}", javaFile, e.getMessage(), e);
            return Optional.empty();
        }
    }

    private static final java.util.regex.Pattern PACKAGE_DECL =
            java.util.regex.Pattern.compile("^\\s*package\\s+([\\w.]+)\\s*;", java.util.regex.Pattern.MULTILINE);
    private static final java.util.Set<String> SKIPPED_DIRS =
            java.util.Set.of(".git", "target", "build", "out", "node_modules", ".gradle", ".idea");
    private static final int MAX_SOURCE_ROOTS = 200;

    /**
     * Infers source roots from the files themselves: a file declaring {@code package a.b} located at
     * {@code <root>/a/b/X.java} contributes {@code <root>}. This works for Maven/Gradle layouts at any
     * depth (multi-module builds) as well as non-standard layouts, unlike a fixed "src/main/java" search.
     */
    Stream<Path> findSourceRoots(Path workspaceRoot) throws IOException {
        java.util.Set<Path> roots = new java.util.LinkedHashSet<>();
        Files.walkFileTree(workspaceRoot, new java.nio.file.SimpleFileVisitor<>() {
            @Override
            public java.nio.file.FileVisitResult preVisitDirectory(Path dir, java.nio.file.attribute.BasicFileAttributes attrs) {
                if (!dir.equals(workspaceRoot) && SKIPPED_DIRS.contains(dir.getFileName().toString())) {
                    return java.nio.file.FileVisitResult.SKIP_SUBTREE;
                }
                return roots.size() >= MAX_SOURCE_ROOTS
                        ? java.nio.file.FileVisitResult.TERMINATE
                        : java.nio.file.FileVisitResult.CONTINUE;
            }

            @Override
            public java.nio.file.FileVisitResult visitFile(Path file, java.nio.file.attribute.BasicFileAttributes attrs) {
                if (file.toString().endsWith(".java")) {
                    Path root = sourceRootOf(file);
                    if (root != null && root.startsWith(workspaceRoot)) {
                        roots.add(root);
                    }
                }
                return java.nio.file.FileVisitResult.CONTINUE;
            }
        });
        if (roots.isEmpty()) {
            roots.add(workspaceRoot);
        }
        // sorted: the first solver that knows a type wins, so the order must not depend on the file system
        return roots.stream().sorted();
    }

    private static Path sourceRootOf(Path javaFile) {
        try {
            String head;
            try (Stream<String> lines = Files.lines(javaFile)) {
                head = lines.limit(80).collect(java.util.stream.Collectors.joining("\n"));
            }
            java.util.regex.Matcher m = PACKAGE_DECL.matcher(head);
            Path dir = javaFile.getParent();
            if (!m.find()) {
                return dir; // default package
            }
            String[] segments = m.group(1).split("\\.");
            for (int i = segments.length - 1; i >= 0; i--) {
                if (dir == null || !dir.getFileName().toString().equals(segments[i])) {
                    return null; // directory layout does not match the package; cannot infer
                }
                dir = dir.getParent();
            }
            return dir;
        } catch (Exception e) {
            return null;
        }
    }
}


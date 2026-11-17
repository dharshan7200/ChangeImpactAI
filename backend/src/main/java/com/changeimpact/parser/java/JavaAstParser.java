package com.changeimpact.parser.java;

import com.changeimpact.parser.model.ParsedSourceFile;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
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
        CombinedTypeSolver combinedTypeSolver = new CombinedTypeSolver();
        // 1. JDK reflection solver
        combinedTypeSolver.add(new ReflectionTypeSolver(false));

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

    private Stream<Path> findSourceRoots(Path workspaceRoot) {
        try {
            return Files.walk(workspaceRoot, 4)
                    .filter(Files::isDirectory)
                    .filter(p -> p.endsWith("src/main/java") || p.endsWith("src\\main\\java") ||
                                 p.endsWith("src/test/java") || p.endsWith("src\\test\\java"));
        } catch (IOException e) {
            return Stream.of(workspaceRoot);
        }
    }
}


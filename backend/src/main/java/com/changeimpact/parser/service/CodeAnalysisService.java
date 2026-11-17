package com.changeimpact.parser.service;

import com.changeimpact.ingestion.service.JavaFileMetadata;
import com.changeimpact.ingestion.service.JavaFileScanner;
import com.changeimpact.parser.java.JavaAstParser;
import com.changeimpact.parser.model.ExtractedDependency;
import com.changeimpact.parser.model.ExtractedSymbol;
import com.changeimpact.parser.model.ParsedSourceFile;
import com.changeimpact.parser.model.SymbolType;
import com.changeimpact.repository.entity.Repository;
import com.changeimpact.symbols.entity.SourceFile;
import com.changeimpact.symbols.entity.Symbol;
import com.changeimpact.symbols.entity.SymbolDependency;
import com.changeimpact.symbols.repository.SourceFileRepository;
import com.changeimpact.symbols.repository.SymbolDependencyRepository;
import com.changeimpact.symbols.repository.SymbolRepository;
import com.github.javaparser.JavaParser;
import lombok.Builder;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Path;
import java.util.*;

/**
 * Orchestrates full AST parsing, symbol persistence, and deterministic
 * dependency linking for a repository at a specific commit.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CodeAnalysisService {

    private final JavaAstParser javaAstParser;
    private final JavaFileScanner javaFileScanner;
    private final SourceFileRepository sourceFileRepository;
    private final SymbolRepository symbolRepository;
    private final SymbolDependencyRepository symbolDependencyRepository;

    @Getter
    @Builder
    public static class AnalysisStats {
        private final int parsedFiles;
        private final int totalSymbols;
        private final int totalDependencies;
        private final int unresolvedDependencies;
    }

    /**
     * Parses all Java source files in the repository workspace and persists
     * the symbol hierarchy and dependency relationships into PostgreSQL.
     */
    @Transactional
    public AnalysisStats analyzeRepository(Repository repository, String commitSha, Path workspaceRoot) {
        log.info("Starting static code intelligence analysis for repo: {} [commit: {}]",
                repository.getFullName(), commitSha);

        List<JavaFileMetadata> fileList = javaFileScanner.scanJavaFiles(workspaceRoot);
        JavaParser parser = javaAstParser.createConfiguredParser(workspaceRoot);

        Map<String, ParsedSourceFile> parsedFilesMap = new HashMap<>();
        for (JavaFileMetadata fileMeta : fileList) {
            javaAstParser.parseSourceFile(parser, workspaceRoot, fileMeta.absolutePath())
                    .ifPresent(parsed -> parsedFilesMap.put(parsed.getRelativePath(), parsed));
        }

        // 1. Persist SourceFile records
        Map<String, SourceFile> savedSourceFiles = new HashMap<>();
        for (ParsedSourceFile parsed : parsedFilesMap.values()) {
            SourceFile sourceFile = SourceFile.builder()
                    .repository(repository)
                    .commitSha(commitSha)
                    .relativePath(parsed.getRelativePath())
                    .packageName(parsed.getPackageName())
                    .linesOfCode(parsed.getLinesOfCode())
                    .sizeBytes(parsed.getSizeBytes())
                    .isTest(parsed.isTest())
                    .build();
            sourceFile = sourceFileRepository.save(sourceFile);
            savedSourceFiles.put(parsed.getRelativePath(), sourceFile);
        }

        // 2. Persist primary symbols (classes, interfaces, records, enums)
        Map<String, Symbol> savedSymbolsByQualifiedName = new HashMap<>();
        List<ExtractedSymbol> deferredMemberSymbols = new ArrayList<>();

        for (ParsedSourceFile parsed : parsedFilesMap.values()) {
            SourceFile sourceFile = savedSourceFiles.get(parsed.getRelativePath());

            for (ExtractedSymbol sym : parsed.getSymbols()) {
                if (isTopLevelType(sym.getSymbolType())) {
                    Symbol symbolEntity = toSymbolEntity(repository, commitSha, sourceFile, sym, null);
                    symbolEntity = symbolRepository.save(symbolEntity);
                    savedSymbolsByQualifiedName.put(sym.getQualifiedName(), symbolEntity);
                } else {
                    deferredMemberSymbols.add(sym);
                }
            }
        }

        // 3. Persist child symbols (methods, constructors, fields) linking parent_symbol_id
        for (ExtractedSymbol sym : deferredMemberSymbols) {
            Symbol parentSymbol = savedSymbolsByQualifiedName.get(sym.getParentQualifiedName());
            SourceFile sourceFile = parentSymbol != null ? parentSymbol.getSourceFile() : null;

            if (sourceFile != null) {
                Symbol symbolEntity = toSymbolEntity(repository, commitSha, sourceFile, sym, parentSymbol);
                symbolEntity = symbolRepository.save(symbolEntity);
                savedSymbolsByQualifiedName.put(sym.getQualifiedName(), symbolEntity);
            }
        }

        // 4. Persist directed dependencies with resolved target links
        int unresolvedCount = 0;
        List<SymbolDependency> dependenciesToSave = new ArrayList<>();

        for (ParsedSourceFile parsed : parsedFilesMap.values()) {
            for (ExtractedDependency dep : parsed.getDependencies()) {
                Symbol sourceSymbol = savedSymbolsByQualifiedName.get(dep.getSourceQualifiedName());
                if (sourceSymbol == null) {
                    continue; // Skip if source was not captured
                }

                // Look up internal target symbol by exact match or class-level match
                Symbol targetSymbol = savedSymbolsByQualifiedName.get(dep.getTargetQualifiedName());
                if (targetSymbol == null && dep.getTargetQualifiedName().contains("#")) {
                    String targetClass = dep.getTargetQualifiedName().substring(0, dep.getTargetQualifiedName().indexOf('#'));
                    targetSymbol = savedSymbolsByQualifiedName.get(targetClass);
                }

                boolean isExternal = (targetSymbol == null) || dep.isExternal();
                if (isExternal) {
                    unresolvedCount++;
                }

                SymbolDependency dependency = SymbolDependency.builder()
                        .repository(repository)
                        .commitSha(commitSha)
                        .sourceSymbol(sourceSymbol)
                        .targetSymbol(targetSymbol)
                        .targetQualifiedName(dep.getTargetQualifiedName())
                        .dependencyType(dep.getDependencyType())
                        .isExternal(isExternal)
                        .sourceLocationLine(dep.getSourceLine())
                        .resolutionStatus(dep.getResolutionStatus())
                        .build();

                dependenciesToSave.add(dependency);
            }
        }

        symbolDependencyRepository.saveAll(dependenciesToSave);

        log.info("Analysis complete for repo: {} -> Files: {}, Symbols: {}, Dependencies: {} (Unresolved: {})",
                repository.getFullName(), savedSourceFiles.size(), savedSymbolsByQualifiedName.size(),
                dependenciesToSave.size(), unresolvedCount);

        return AnalysisStats.builder()
                .parsedFiles(savedSourceFiles.size())
                .totalSymbols(savedSymbolsByQualifiedName.size())
                .totalDependencies(dependenciesToSave.size())
                .unresolvedDependencies(unresolvedCount)
                .build();
    }

    private boolean isTopLevelType(SymbolType type) {
        return type == SymbolType.CLASS || type == SymbolType.INTERFACE ||
               type == SymbolType.RECORD || type == SymbolType.ENUM;
    }

    private Symbol toSymbolEntity(Repository repository, String commitSha, SourceFile sourceFile,
                                  ExtractedSymbol sym, Symbol parent) {
        return Symbol.builder()
                .repository(repository)
                .sourceFile(sourceFile)
                .commitSha(commitSha)
                .symbolType(sym.getSymbolType())
                .qualifiedName(sym.getQualifiedName())
                .simpleName(sym.getSimpleName())
                .parentSymbol(parent)
                .returnType(sym.getReturnType())
                .parameters(sym.getParameters())
                .visibility(sym.getVisibility())
                .isStatic(sym.isStatic())
                .isAbstract(sym.isAbstract())
                .isFinal(sym.isFinal())
                .annotations(sym.getAnnotations() != null ? sym.getAnnotations().toArray(new String[0]) : null)
                .startLine(sym.getStartLine())
                .endLine(sym.getEndLine())
                .startColumn(sym.getStartColumn())
                .endColumn(sym.getEndColumn())
                .resolutionStatus(sym.getResolutionStatus())
                .build();
    }
}


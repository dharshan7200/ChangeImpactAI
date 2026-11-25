package com.changeimpact.analysis.service;

import com.changeimpact.architecture.repository.ArchitectureViolationRepository;
import com.changeimpact.architecture.service.ArchitectureRuleEngine;
import com.changeimpact.common.config.AppProperties;
import com.changeimpact.common.exception.ResourceNotFoundException;
import com.changeimpact.diff.semantic.SymbolSnapshot;
import com.changeimpact.diff.service.GitDiffService;
import com.changeimpact.graph.service.DependencyGraphService;
import com.changeimpact.ingestion.workspace.WorkspaceManager;
import com.changeimpact.parser.model.DependencyType;
import com.changeimpact.parser.service.CodeAnalysisService;
import com.changeimpact.repository.entity.Repository;
import com.changeimpact.repository.repository.RepositoryRepository;
import com.changeimpact.symbols.entity.Symbol;
import com.changeimpact.symbols.repository.SourceFileRepository;
import com.changeimpact.symbols.repository.SymbolDependencyRepository;
import com.changeimpact.symbols.repository.SymbolRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.api.Git;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Path;
import java.util.*;

/**
 * Makes the static analysis of <i>any</i> commit available, not only the indexed HEAD.
 *
 * <p>A commit's Java sources are exported from Git objects into an isolated snapshot directory
 * (the indexing working tree is never checked out to another commit), parsed with the same
 * JavaParser pipeline, persisted under that commit SHA, and the snapshot is deleted afterwards.
 * Results are reused: a commit is only (re-)analysed when it has no symbols yet or was analysed
 * before symbol fingerprints existed.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CommitAnalysisService {

    private final RepositoryRepository repositoryRepository;
    private final SymbolRepository symbolRepository;
    private final SymbolDependencyRepository symbolDependencyRepository;
    private final SourceFileRepository sourceFileRepository;
    private final ArchitectureViolationRepository architectureViolationRepository;
    private final CodeAnalysisService codeAnalysisService;
    private final ArchitectureRuleEngine architectureRuleEngine;
    private final DependencyGraphService dependencyGraphService;
    private final GitDiffService gitDiffService;
    private final com.changeimpact.parser.language.LanguageRegistry languageRegistry;
    private final WorkspaceManager workspaceManager;
    private final AppProperties appProperties;
    private final TransactionTemplate transactionTemplate;
    private final IncrementalAnalysisPlanner incrementalPlanner;

    /** @return true when the commit was (re-)analysed, false when existing results were reused */
    public boolean ensureAnalyzed(Git git, UUID repositoryId, String commitSha) throws Exception {
        boolean upToDate = symbolRepository.countByRepositoryIdAndCommitSha(repositoryId, commitSha) > 0
                && !symbolRepository.existsByRepositoryIdAndCommitShaAndContentHashIsNull(repositoryId, commitSha)
                // a language enabled after the commit was analysed: re-analyse, or its symbols would all look "added"
                && codeAnalysisService.analysedLanguages(repositoryId, commitSha).containsAll(codeAnalysisService.enabledLanguages());
        if (upToDate) {
            return false;
        }
        Path snapshot = workspaceManager.getSnapshotPath(repositoryId, commitSha);
        try {
            int files = gitDiffService.exportSources(git.getRepository(), commitSha, snapshot,
                    appProperties.getIngestion().getExcludedPaths(), languageRegistry.extensions());
            log.info("Analysing commit {} of repository {} ({} source files: {})", commitSha, repositoryId, files,
                    languageRegistry.languages());
            var plan = incrementalPlanner.plan(git.getRepository(), repositoryId, commitSha).orElse(null);
            transactionTemplate.executeWithoutResult(status -> {
                Repository repository = repositoryRepository.findById(repositoryId)
                        .orElseThrow(() -> new ResourceNotFoundException("Repository", repositoryId));
                architectureViolationRepository.deleteByRepositoryIdAndCommitSha(repositoryId, commitSha);
                sourceFileRepository.deleteByRepositoryIdAndCommitSha(repositoryId, commitSha);
                codeAnalysisService.analyze(repository, commitSha, snapshot, plan);
            });
            dependencyGraphService.evictCache(repositoryId, commitSha);
            transactionTemplate.executeWithoutResult(status -> architectureRuleEngine.analyzeArchitecture(
                    repositoryRepository.getReferenceById(repositoryId), commitSha));
            return true;
        } finally {
            workspaceManager.deleteSnapshot(repositoryId, commitSha);
        }
    }

    /** All symbols of a commit as comparison snapshots (supertypes resolved by name). */
    @Transactional(readOnly = true)
    public List<SymbolSnapshot> loadSnapshots(UUID repositoryId, String commitSha) {
        Map<String, Set<String>> supertypes = new HashMap<>();
        for (Object[] row : symbolDependencyRepository.findNamesByTypes(repositoryId, commitSha,
                List.of(DependencyType.EXTENDS, DependencyType.IMPLEMENTS))) {
            supertypes.computeIfAbsent((String) row[0], k -> new TreeSet<>()).add((String) row[1]);
        }
        List<SymbolSnapshot> out = new ArrayList<>();
        for (Symbol s : symbolRepository.findWithSourceFileByRepositoryIdAndCommitSha(repositoryId, commitSha)) {
            out.add(new SymbolSnapshot(
                    s.getId(),
                    s.getQualifiedName(),
                    s.getSimpleName(),
                    s.getSymbolType(),
                    s.getParentSymbol() != null ? s.getParentSymbol().getQualifiedName() : null,
                    s.getVisibility(),
                    s.getReturnType(),
                    s.getParameters(),
                    Boolean.TRUE.equals(s.getIsStatic()),
                    Boolean.TRUE.equals(s.getIsAbstract()),
                    Boolean.TRUE.equals(s.getIsFinal()),
                    s.getAnnotationText(),
                    s.getContentHash(),
                    supertypes.getOrDefault(s.getQualifiedName(), Set.of()),
                    s.getSourceFile() != null ? s.getSourceFile().getRelativePath() : "",
                    s.getStartLine() != null ? s.getStartLine() : 0,
                    s.getEndLine() != null ? s.getEndLine() : 0,
                    s.getSourceFile() != null && Boolean.TRUE.equals(s.getSourceFile().getIsTest())));
        }
        return out;
    }

    /** Complexity per symbol id (methods/constructors). */
    @Transactional(readOnly = true)
    public Map<UUID, Integer> complexities(UUID repositoryId, String commitSha) {
        Map<UUID, Integer> m = new HashMap<>();
        symbolRepository.findByRepositoryIdAndCommitSha(repositoryId, commitSha).forEach(s -> {
            if (s.getComplexity() != null) {
                m.put(s.getId(), s.getComplexity());
            }
        });
        return m;
    }
}

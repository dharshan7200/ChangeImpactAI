package com.changeimpact.testing.reports.service;

import com.changeimpact.common.exception.BusinessException;
import com.changeimpact.common.exception.ResourceNotFoundException;
import com.changeimpact.repository.entity.Commit;
import com.changeimpact.repository.repository.CommitRepository;
import com.changeimpact.repository.repository.RepositoryRepository;
import com.changeimpact.testing.reports.entity.*;
import com.changeimpact.testing.reports.parser.ReportParsers;
import com.changeimpact.testing.reports.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.InputStreamSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Stores CI quality artefacts per commit. Reports describe what CI actually measured or executed; the
 * platform never runs repository code itself.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ReportIngestionService {

    private static final Pattern FULL_SHA = Pattern.compile("^[0-9a-f]{40}$");
    private static final Pattern SHORT_SHA = Pattern.compile("^[0-9a-f]{7,39}$");

    private final RepositoryRepository repositoryRepository;
    private final CommitRepository commitRepository;
    private final QualityReportRepository reportRepository;
    private final CoverageFileRepository coverageFileRepository;
    private final CoverageMethodRepository coverageMethodRepository;
    private final TestExecutionRepository testExecutionRepository;
    private final MutationResultRepository mutationResultRepository;
    private final DependencyVulnerabilityRepository vulnerabilityRepository;
    private final ReportParsers parsers;

    /** One uploaded file. */
    public record Upload(String fileName, InputStreamSource source) {}

    @Transactional
    public QualityReport ingest(UUID userId, UUID repositoryId, QualityReport.Type type, String commitRef,
                                String fileName, InputStream content) throws IOException {
        byte[] bytes = content.readAllBytes();
        return ingest(userId, repositoryId, type, commitRef, List.of(new Upload(fileName, () -> new java.io.ByteArrayInputStream(bytes))));
    }

    /**
     * Ingests one report. JUnit results may consist of several files (Surefire writes one XML per test class);
     * they are merged into a single report. Every other type takes exactly one file.
     */
    @Transactional
    public QualityReport ingest(UUID userId, UUID repositoryId, QualityReport.Type type, String commitRef,
                                List<Upload> uploads) throws IOException {
        repositoryRepository.findByIdAndOwnerIdAndDeletedAtIsNull(repositoryId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Repository", repositoryId));
        if (uploads.isEmpty()) {
            throw new BusinessException("EMPTY_REPORT", "No report file uploaded");
        }
        if (uploads.size() > 1 && type != QualityReport.Type.JUNIT) {
            throw new BusinessException("TOO_MANY_FILES", "Only JUNIT reports may consist of several files; upload one " + type + " file");
        }
        if (uploads.size() > 500) {
            throw new BusinessException("TOO_MANY_FILES", "At most 500 JUnit files per upload");
        }
        String sha = resolveCommit(repositoryId, commitRef);
        String fileName = uploads.size() == 1 ? uploads.get(0).fileName() : uploads.size() + " files";

        Map<String, Object> summary = new LinkedHashMap<>();
        QualityReport report = reportRepository.save(QualityReport.builder()
                .repositoryId(repositoryId)
                .commitSha(sha)
                .reportType(type)
                .fileName(fileName == null ? null : fileName.replaceAll("[^A-Za-z0-9._-]", "_"))
                .uploadedBy(userId)
                .summary(summary)
                .build());
        UUID id = report.getId();

        switch (type) {
            case JACOCO -> {
                ReportParsers.JacocoReport r;
                try (InputStream content = uploads.get(0).source().getInputStream()) {
                    r = parsers.parseJacoco(content);
                }
                coverageFileRepository.saveAll(r.files().stream().map(f -> CoverageFile.builder()
                        .reportId(id).sourcePath(f.sourcePath()).coveredLines(f.coveredLines()).missedLines(f.missedLines()).build()).toList());
                coverageMethodRepository.saveAll(r.methods().stream().map(m -> CoverageMethod.builder()
                        .reportId(id).className(m.className()).methodName(m.methodName()).methodDesc(m.desc()).firstLine(m.firstLine())
                        .linesCovered(m.linesCovered()).linesMissed(m.linesMissed())
                        .branchesCovered(m.branchesCovered()).branchesMissed(m.branchesMissed()).build()).toList());
                summary.put("sourceFiles", r.files().size());
                summary.put("methods", r.methods().size());
                summary.put("lineCoverage", ratio(r.linesCovered(), r.linesMissed()));
                summary.put("branchCoverage", ratio(r.branchesCovered(), r.branchesMissed()));
                summary.put("linesCovered", r.linesCovered());
                summary.put("linesMissed", r.linesMissed());
            }
            case JUNIT -> {
                List<ReportParsers.TestCaseData> cases = new ArrayList<>();
                for (Upload u : uploads) {
                    try (InputStream content = u.source().getInputStream()) {
                        cases.addAll(parsers.parseJunit(content));
                    } catch (BusinessException e) {
                        throw new BusinessException(e.getErrorCode(), u.fileName() + ": " + e.getMessage());
                    }
                }
                testExecutionRepository.saveAll(cases.stream().map(c -> TestExecution.builder()
                        .reportId(id).className(c.className()).methodName(trim(c.methodName(), 512)).status(c.status())
                        .durationMs(c.durationMs()).message(c.message()).build()).toList());
                Map<String, Long> byStatus = new TreeMap<>();
                cases.forEach(c -> byStatus.merge(c.status(), 1L, Long::sum));
                summary.put("tests", cases.size());
                summary.put("files", uploads.size());
                summary.put("byStatus", byStatus);
            }
            case PIT -> {
                List<ReportParsers.MutationData> mutations;
                try (InputStream content = uploads.get(0).source().getInputStream()) {
                    mutations = parsers.parsePit(content);
                }
                mutationResultRepository.saveAll(mutations.stream().map(m -> MutationResult.builder()
                        .reportId(id).className(m.className()).methodName(trim(m.methodName(), 255)).methodDesc(m.desc())
                        .lineNumber(m.line()).mutator(m.mutator()).status(m.status()).killingTest(m.killingTest())
                        .description(m.description()).build()).toList());
                Map<String, Long> byStatus = new TreeMap<>();
                mutations.forEach(m -> byStatus.merge(m.status(), 1L, Long::sum));
                long killed = byStatus.getOrDefault("KILLED", 0L) + byStatus.getOrDefault("TIMED_OUT", 0L)
                        + byStatus.getOrDefault("MEMORY_ERROR", 0L);
                summary.put("mutants", mutations.size());
                summary.put("byStatus", byStatus);
                summary.put("mutationScore", mutations.isEmpty() ? null : Math.round(1000.0 * killed / mutations.size()) / 10.0);
            }
            case DEPENDENCY_CHECK -> {
                List<ReportParsers.VulnerabilityData> vulns;
                try (InputStream content = uploads.get(0).source().getInputStream()) {
                    vulns = parsers.parseDependencyCheck(content);
                }
                vulnerabilityRepository.saveAll(vulns.stream().map(v -> DependencyVulnerability.builder()
                        .reportId(id).dependency(v.dependency()).packageId(v.packageId()).vulnerability(v.vulnerability())
                        .severity(v.severity()).cvssScore(v.cvssScore()).cwes(v.cwes().toArray(String[]::new))
                        .description(v.description()).build()).toList());
                Map<String, Long> bySeverity = new TreeMap<>();
                vulns.forEach(v -> bySeverity.merge(String.valueOf(v.severity()), 1L, Long::sum));
                summary.put("vulnerabilities", vulns.size());
                summary.put("vulnerableDependencies", vulns.stream().map(ReportParsers.VulnerabilityData::dependency).distinct().count());
                summary.put("bySeverity", bySeverity);
            }
        }
        report.setSummary(summary);
        log.info("Ingested {} report for repository {} at {}: {}", type, repositoryId, sha, summary);
        return reportRepository.save(report);
    }

    /** Accepts a full SHA, or a short SHA that is unique among the repository's known commits. */
    String resolveCommit(UUID repositoryId, String ref) {
        String r = ref == null ? "" : ref.trim().toLowerCase(Locale.ROOT);
        if (FULL_SHA.matcher(r).matches()) {
            return r;
        }
        if (!SHORT_SHA.matcher(r).matches()) {
            throw new BusinessException("INVALID_COMMIT", "commitSha must be a full or abbreviated (7+) commit SHA");
        }
        List<Commit> matches = commitRepository.findByRepositoryIdOrderByCommitDateDesc(repositoryId,
                        org.springframework.data.domain.PageRequest.of(0, 2000)).stream()
                .filter(c -> c.getSha().startsWith(r)).limit(2).toList();
        if (matches.size() != 1) {
            throw new BusinessException("INVALID_COMMIT", matches.isEmpty()
                    ? "Unknown commit " + ref + " (index the repository first or pass the full SHA)"
                    : "Ambiguous commit prefix " + ref);
        }
        return matches.get(0).getSha();
    }

    private static Double ratio(int covered, int missed) {
        int total = covered + missed;
        return total == 0 ? null : Math.round(1000.0 * covered / total) / 10.0;
    }

    private static String trim(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}

package com.changeimpact.testing.coverage;

import com.changeimpact.testing.reports.entity.CoverageFile;
import com.changeimpact.testing.reports.entity.QualityReport;
import com.changeimpact.testing.reports.repository.CoverageFileRepository;
import com.changeimpact.testing.reports.repository.QualityReportRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * Line coverage lookups from an uploaded JaCoCo report. Coverage is only used for the exact commit it was
 * measured on (line numbers of other commits do not correspond).
 */
@Service
@RequiredArgsConstructor
public class CoverageService {

    private final QualityReportRepository reportRepository;
    private final CoverageFileRepository coverageFileRepository;

    /** Covered/missed executable lines per JaCoCo source path ({@code com/x/Foo.java}). */
    public record CoverageIndex(UUID reportId, String commitSha, Map<String, BitSet> covered, Map<String, BitSet> missed) {

        /** COVERED, MISSED or null (not an executable line / file not in report). */
        public String status(String repositoryFilePath, int line) {
            String key = keyFor(repositoryFilePath);
            if (key == null) return null;
            if (covered.get(key).get(line)) return "COVERED";
            if (missed.get(key).get(line)) return "MISSED";
            return null;
        }

        /** Executable-line coverage of the given lines: [covered, executable]. */
        public int[] coverage(String repositoryFilePath, Collection<Integer> lines) {
            int c = 0, e = 0;
            for (int line : lines) {
                String s = status(repositoryFilePath, line);
                if (s != null) {
                    e++;
                    if ("COVERED".equals(s)) c++;
                }
            }
            return new int[]{c, e};
        }

        public boolean knowsFile(String repositoryFilePath) {
            return keyFor(repositoryFilePath) != null;
        }

        private String keyFor(String repositoryFilePath) {
            if (repositoryFilePath == null) return null;
            String p = repositoryFilePath.replace('\\', '/');
            // JaCoCo paths are relative to the source root: match the longest path suffix.
            for (String key : covered.keySet()) {
                if (p.equals(key) || p.endsWith("/" + key)) return key;
            }
            return null;
        }
    }

    @Transactional(readOnly = true)
    public Optional<CoverageIndex> load(UUID repositoryId, String commitSha) {
        return reportRepository.findFirstByRepositoryIdAndCommitShaAndReportTypeOrderByCreatedAtDesc(
                        repositoryId, commitSha, QualityReport.Type.JACOCO)
                .map(report -> {
                    Map<String, BitSet> covered = new HashMap<>();
                    Map<String, BitSet> missed = new HashMap<>();
                    for (CoverageFile f : coverageFileRepository.findByReportId(report.getId())) {
                        covered.put(f.getSourcePath(), toBits(f.getCoveredLines()));
                        missed.put(f.getSourcePath(), toBits(f.getMissedLines()));
                    }
                    return new CoverageIndex(report.getId(), commitSha, covered, missed);
                });
    }

    private static BitSet toBits(int[] lines) {
        BitSet b = new BitSet();
        if (lines != null) for (int l : lines) if (l >= 0) b.set(l);
        return b;
    }
}

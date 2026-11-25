package com.changeimpact.testing.mutation;

import com.changeimpact.testing.reports.entity.MutationResult;
import com.changeimpact.testing.reports.entity.QualityReport;
import com.changeimpact.testing.reports.repository.MutationResultRepository;
import com.changeimpact.testing.reports.repository.QualityReportRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Test quality of the changed code from an uploaded PIT report: mutants that survived (tests ran but did not
 * notice the code being altered) or were not covered point to weak or missing tests. Only the report of the
 * exact head commit is used, because PIT locates mutants by line number.
 */
@Service
@RequiredArgsConstructor
public class MutationQualityService {

    private final QualityReportRepository reportRepository;
    private final MutationResultRepository mutationResultRepository;

    /** A production method/constructor to evaluate: owner type (binary name with $), simple name and line range. */
    public record Target(String qualifiedName, String ownerType, String methodName, int startLine, int endLine, boolean changed) {}

    public Map<String, Object> evaluate(UUID repositoryId, String headCommit, List<Target> targets) {
        Optional<QualityReport> report = reportRepository.findFirstByRepositoryIdAndCommitShaAndReportTypeOrderByCreatedAtDesc(
                repositoryId, headCommit, QualityReport.Type.PIT);
        Map<String, Object> out = new LinkedHashMap<>();
        if (report.isEmpty()) {
            out.put("status", "NO_REPORT_FOR_COMMIT");
            return out;
        }
        Map<String, List<MutationResult>> byClass = new HashMap<>();
        for (MutationResult m : mutationResultRepository.findByReportId(report.get().getId())) {
            byClass.computeIfAbsent(m.getClassName(), k -> new ArrayList<>()).add(m);
        }

        List<Map<String, Object>> methods = new ArrayList<>();
        long killed = 0, total = 0;
        for (Target t : targets) {
            List<MutationResult> mutants = byClass.getOrDefault(t.ownerType(), List.of()).stream()
                    .filter(m -> m.getMethodName().equals(t.methodName()))
                    .filter(m -> m.getLineNumber() == null || (m.getLineNumber() >= t.startLine() && m.getLineNumber() <= t.endLine()))
                    .toList();
            if (mutants.isEmpty()) continue;
            Map<String, Long> byStatus = new TreeMap<>();
            mutants.forEach(m -> byStatus.merge(m.getStatus(), 1L, Long::sum));
            long k = mutants.stream().filter(m -> Set.of("KILLED", "TIMED_OUT", "MEMORY_ERROR").contains(m.getStatus())).count();
            killed += k;
            total += mutants.size();
            Map<String, Object> mm = new LinkedHashMap<>();
            mm.put("symbol", t.qualifiedName());
            mm.put("changed", t.changed());
            mm.put("mutants", mutants.size());
            mm.put("byStatus", byStatus);
            mm.put("mutationScore", Math.round(1000.0 * k / mutants.size()) / 10.0);
            mm.put("survivors", mutants.stream().filter(m -> "SURVIVED".equals(m.getStatus()) || "NO_COVERAGE".equals(m.getStatus()))
                    .limit(10).map(m -> {
                        Map<String, Object> s = new LinkedHashMap<>();
                        s.put("line", m.getLineNumber());
                        s.put("status", m.getStatus());
                        s.put("mutator", m.getMutator());
                        s.put("description", m.getDescription());
                        return s;
                    }).toList());
            mm.put("killingTests", mutants.stream().map(MutationResult::getKillingTest).filter(Objects::nonNull)
                    .map(MutationQualityService::shortTestName).distinct().limit(10).toList());
            methods.add(mm);
        }
        methods.sort(Comparator.comparingDouble(m -> (Double) m.get("mutationScore")));
        out.put("status", "AVAILABLE");
        out.put("reportId", report.get().getId());
        out.put("methodsWithMutants", methods.size());
        out.put("mutationScore", total == 0 ? null : Math.round(1000.0 * killed / total) / 10.0);
        out.put("weakMethods", methods.stream().filter(m -> (Double) m.get("mutationScore") < 70.0).count());
        out.put("methods", methods);
        out.put("note", "Survived = tests ran but did not detect the mutation; NO_COVERAGE = no test executed the line.");
        return out;
    }

    /** PIT killing tests look like {@code com.x.FooTest.[engine:junit-jupiter]/[class:com.x.FooTest]/[method:bar()]}. */
    static String shortTestName(String killingTest) {
        String cls = killingTest;
        int bracket = cls.indexOf(".[");
        if (bracket > 0) cls = cls.substring(0, bracket);
        int m = killingTest.lastIndexOf("[method:");
        String method = m >= 0 ? killingTest.substring(m + 8).replaceAll("[\\])].*$", "").replace("(", "") : "";
        String simpleClass = cls.substring(cls.lastIndexOf('.') + 1);
        return method.isEmpty() ? simpleClass : simpleClass + "#" + method;
    }
}

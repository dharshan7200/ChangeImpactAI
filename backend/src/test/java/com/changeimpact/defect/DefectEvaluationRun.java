package com.changeimpact.defect;

import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.io.File;
import java.util.*;

/**
 * Offline evaluation on real repositories (not part of the normal build):
 * {@code mvn test -Dtest=DefectEvaluationRun -Ddefect.eval.repos=name=/path/to/clone,... -Ddefect.eval.out=report.json}
 */
@EnabledIfSystemProperty(named = "defect.eval.repos", matches = ".+")
class DefectEvaluationRun {

    record Repo(String name, List<JitDatasetBuilder.Sample> samples, List<SzzLabeler.Link> links) {}

    /** Sanity check against leakage: the same pipeline with shuffled training labels must score ~0.5 on test. */
    static double permutationAuc(List<JitDatasetBuilder.Sample> samples, List<SzzLabeler.Link> links) {
        int n = samples.size();
        var train = samples.subList((int) (n * DefectModelEvaluator.WARMUP), (int) (n * DefectModelEvaluator.TRAIN_END));
        var test = samples.subList((int) (n * DefectModelEvaluator.TRAIN_END), (int) (n * DefectModelEvaluator.TEST_END));
        int[] y = DefectModelEvaluator.labels(train, links, test.get(0).time());
        int[] yTest = DefectModelEvaluator.labels(test, links, Long.MAX_VALUE);
        Random r = new Random(42);
        double sum = 0;
        for (int rep = 0; rep < 5; rep++) {
            int[] shuffled = y.clone();
            for (int i = shuffled.length - 1; i > 0; i--) { int j = r.nextInt(i + 1); int t = shuffled[i]; shuffled[i] = shuffled[j]; shuffled[j] = t; }
            var m = (Map<?, ?>) DefectModelEvaluator.trainAndTest(train, shuffled, test, yTest).get("model");
            sum += (double) m.get("auc");
        }
        return sum / 5;
    }

    @Test
    void evaluate() throws Exception {
        int max = Integer.getInteger("defect.eval.maxCommits", 5000);
        Map<String, Object> report = new LinkedHashMap<>();
        List<Repo> repos = new ArrayList<>();
        for (String spec : System.getProperty("defect.eval.repos").split(",")) {
            String name = spec.substring(0, spec.indexOf('=')), path = spec.substring(spec.indexOf('=') + 1);
            try (Git git = Git.open(new File(path))) {
                String head = git.getRepository().resolve("HEAD").getName();
                long t0 = System.currentTimeMillis();
                var samples = new JitDatasetBuilder().build(git.getRepository(), head, max);
                long t1 = System.currentTimeMillis();
                var szz = new SzzLabeler().label(git, samples);
                long t2 = System.currentTimeMillis();
                var outcome = new DefectModelEvaluator().evaluate(samples, szz.links());
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("head", head);
                r.put("status", outcome.status());
                r.put("reason", outcome.reason());
                r.put("fixes", szz.fixes());
                r.put("tracedFixes", szz.tracedFixes());
                r.put("untraceableFixes", szz.untraceableFixes());
                r.put("links", szz.links().size());
                r.put("bugIntroducingCommits", szz.links().stream().map(SzzLabeler.Link::introducing).distinct().count());
                r.put("datasetMs", t1 - t0);
                r.put("szzMs", t2 - t1);
                r.put("evaluation", outcome.evaluation());
                r.put("permutationCheckAuc", permutationAuc(samples, szz.links()));
                if (!outcome.predictions().isEmpty()) r.put("examplePredictions", outcome.predictions().subList(0, Math.min(3, outcome.predictions().size())));
                report.put(name, r);
                repos.add(new Repo(name, samples, szz.links()));
                System.out.printf("%s: %d samples, %d fixes (%d traced), %d links, %s — %s (dataset %d ms, SZZ %d ms)%n",
                        name, samples.size(), szz.fixes(), szz.tracedFixes(), szz.links().size(), outcome.status(),
                        outcome.reason(), t1 - t0, t2 - t1);
            }
        }
        // cross-repository: train on all usable commits of A, test on B's test period
        Map<String, Object> cross = new LinkedHashMap<>();
        for (Repo a : repos) for (Repo b : repos) {
            if (a == b) continue;
            int na = a.samples().size(), nb = b.samples().size();
            var train = a.samples().subList((int) (na * DefectModelEvaluator.WARMUP), (int) (na * DefectModelEvaluator.TEST_END));
            var test = b.samples().subList((int) (nb * DefectModelEvaluator.TRAIN_END), (int) (nb * DefectModelEvaluator.TEST_END));
            int[] yTrain = DefectModelEvaluator.labels(train, a.links(), Long.MAX_VALUE);
            int[] yTest = DefectModelEvaluator.labels(test, b.links(), Long.MAX_VALUE);
            if (Arrays.stream(yTrain).sum() < 10 || Arrays.stream(yTest).sum() < 5) continue;
            var r = DefectModelEvaluator.trainAndTest(train, yTrain, test, yTest);
            Map<?, ?> m = (Map<?, ?>) r.get("model");
            cross.put(a.name() + " -> " + b.name(), Map.of("auc", m.get("auc"), "brier", m.get("brier"),
                    "f1", ((ClassificationMetrics.AtThreshold) m.get("atThreshold")).f1(),
                    "churnAuc", ((Map<?, ?>) r.get("churnBaseline")).get("auc")));
        }
        report.put("crossRepository", cross);
        ObjectMapper om = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();
        om.writeValue(new File(System.getProperty("defect.eval.out", "target/defect-evaluation.json")), report);
        cross.forEach((k, v) -> System.out.println("cross " + k + " " + v));
    }
}

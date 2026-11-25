package com.changeimpact.defect;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.PersonIdent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class DefectPredictionTest {

    @TempDir Path dir;
    private long clock = Instant.parse("2024-01-01T00:00:00Z").getEpochSecond();

    private String commit(Git git, String author, String message, Map<String, String> files) throws Exception {
        for (var e : files.entrySet()) {
            Path p = dir.resolve(e.getKey());
            if (e.getValue() == null) { Files.delete(p); git.rm().addFilepattern(e.getKey()).call(); continue; }
            Files.createDirectories(p.getParent());
            Files.writeString(p, e.getValue());
            git.add().addFilepattern(e.getKey()).call();
        }
        clock += 86_400; // one commit per day
        PersonIdent who = new PersonIdent(author, author.toLowerCase() + "@example.com", Instant.ofEpochSecond(clock), java.time.ZoneOffset.UTC);
        return git.commit().setSign(false).setMessage(message).setAuthor(who).setCommitter(who).call().getName();
    }

    @Test
    void featuresAreComputedFromPriorHistoryOnly() throws Exception {
        try (Git git = Git.init().setDirectory(dir.toFile()).call()) {
            commit(git, "Ann", "Initial", Map.of("core/src/A.java", "class A {\n int a = 1;\n int b = 2;\n}\n", "README.md", "x\n"));
            commit(git, "Bob", "Change A", Map.of("core/src/A.java", "class A {\n int a = 1;\n int b = 3;\n int c = 4;\n}\n"));
            String third = commit(git, "Ann", "Touch two subsystems", Map.of(
                    "core/src/A.java", "class A {\n int a = 9;\n int b = 3;\n int c = 4;\n}\n",
                    "web/src/B.java", "class B {}\n"));
            var samples = new JitDatasetBuilder().build(git.getRepository(), third, 100);
            assertThat(samples).hasSize(3);
            var f = samples.get(2).features();
            var names = JitDatasetBuilder.FEATURES;
            assertThat(f[names.indexOf("NS")]).isEqualTo(2);           // core, web
            assertThat(f[names.indexOf("NF")]).isEqualTo(2);           // README is not code
            assertThat(f[names.indexOf("LA")]).isEqualTo(2);           // 1 replaced line + new file line
            assertThat(f[names.indexOf("LD")]).isEqualTo(1);
            assertThat(f[names.indexOf("LT")]).isEqualTo(5);           // A had 5 lines before
            assertThat(f[names.indexOf("NDEV")]).isEqualTo(1);         // Bob (the author herself is not counted)
            assertThat(f[names.indexOf("NUC")]).isEqualTo(2);          // two earlier commits changed A
            assertThat(f[names.indexOf("AGE")]).isCloseTo(1.0, within(1e-9)); // A last changed a day earlier
            assertThat(f[names.indexOf("EXP")]).isEqualTo(1);          // Ann's earlier commits
            assertThat(f[names.indexOf("SEXP")]).isEqualTo(1);         // ...in "core"
            double h = -(2 / 3.0 * Math.log(2 / 3.0) + 1 / 3.0 * Math.log(1 / 3.0)) / Math.log(2);
            assertThat(f[names.indexOf("ENTROPY")]).isCloseTo(h, within(1e-9)); // churn 2 (A) vs 1 (B), normalised by log2(NF)
        }
    }

    @Test
    void szzLinksAFixToTheCommitThatIntroducedTheChangedLine() throws Exception {
        try (Git git = Git.init().setDirectory(dir.toFile()).call()) {
            commit(git, "Ann", "Initial", Map.of("src/Calc.java", "class Calc {\n int add(int a, int b) {\n  return a + b;\n }\n}\n"));
            String bad = commit(git, "Bob", "Add division", Map.of("src/Calc.java",
                    "class Calc {\n int add(int a, int b) {\n  return a + b;\n }\n int div(int a, int b) {\n  return a * b;\n }\n}\n"));
            commit(git, "Ann", "Add docs", Map.of("src/Calc.java",
                    "// calculator\nclass Calc {\n int add(int a, int b) {\n  return a + b;\n }\n int div(int a, int b) {\n  return a * b;\n }\n}\n"));
            String fix = commit(git, "Cy", "Fix wrong division operator", Map.of("src/Calc.java",
                    "// calculator\nclass Calc {\n int add(int a, int b) {\n  return a + b;\n }\n int div(int a, int b) {\n  return a / b;\n }\n}\n"));
            var samples = new JitDatasetBuilder().build(git.getRepository(), fix, 100);
            var result = new SzzLabeler().label(git, samples);
            assertThat(result.fixes()).isEqualTo(1);
            assertThat(result.links()).extracting(SzzLabeler.Link::introducing).containsExactly(bad);
            assertThat(result.links().get(0).fix()).isEqualTo(fix);
        }
    }

    @Test
    void aucBrierAndThresholdMetrics() {
        double[] p = {0.9, 0.8, 0.7, 0.3, 0.2, 0.1};
        int[] y = {1, 1, 0, 1, 0, 0};
        assertThat(ClassificationMetrics.auc(p, y)).isCloseTo(8 / 9.0, within(1e-12));
        assertThat(ClassificationMetrics.auc(new double[]{0.5, 0.5}, new int[]{1, 0})).isEqualTo(0.5); // ties count half
        var at = ClassificationMetrics.at(p, y, 0.5);
        assertThat(at.tp()).isEqualTo(2);
        assertThat(at.fp()).isEqualTo(1);
        assertThat(at.precision()).isCloseTo(2 / 3.0, within(1e-12));
        assertThat(at.recall()).isCloseTo(2 / 3.0, within(1e-12));
        assertThat(ClassificationMetrics.brier(new double[]{1, 0}, new int[]{1, 0})).isZero();
        var bins = ClassificationMetrics.calibration(new double[]{0.15, 0.15, 0.95}, new int[]{0, 1, 1});
        assertThat(bins.get(1).count()).isEqualTo(2);
        assertThat(bins.get(1).observedRate()).isEqualTo(0.5);
        assertThat(ClassificationMetrics.ece(bins)).isCloseTo((2 * 0.35 + 0.05) / 3, within(1e-12));
    }

    @Test
    void logisticRegressionRecoversASignalAndIsDeterministic() {
        Random r = new Random(7);
        List<double[]> x = new ArrayList<>();
        int[] y = new int[2000];
        for (int i = 0; i < y.length; i++) {
            double signal = r.nextDouble() * 100, noise = r.nextDouble() * 100;
            x.add(new double[]{signal, noise});
            double logit = 2.0 * ((Math.log1p(signal) - 3.6) / 0.9);
            y[i] = r.nextDouble() < LogisticModel.sigmoid(logit) ? 1 : 0;
        }
        LogisticModel a = LogisticModel.fit(x, y, 1.0), b = LogisticModel.fit(x, y, 1.0);
        assertThat(a.coefficients()).containsExactly(b.coefficients());
        assertThat(a.coefficients()[1]).isGreaterThan(1.0);                 // signal
        assertThat(Math.abs(a.coefficients()[2])).isLessThan(0.2);          // noise
        double[] p = x.stream().mapToDouble(a::predict).toArray();
        assertThat(ClassificationMetrics.auc(p, y)).isGreaterThan(0.75);
        LogisticModel restored = LogisticModel.fromMap(a.toMap(List.of("s", "n")));
        assertThat(restored.predict(x.get(3))).isCloseTo(a.predict(x.get(3)), within(1e-12));
    }

    @Test
    void evaluatorRefusesWithTooFewLabels() {
        List<JitDatasetBuilder.Sample> samples = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            samples.add(new JitDatasetBuilder.Sample("c" + i, i * 1000L, "a", null, false, new double[14], Map.of(), "c" + i));
        }
        var outcome = new DefectModelEvaluator().evaluate(samples, List.of(new SzzLabeler.Link("c20", "c30", 30_000)));
        assertThat(outcome.status()).isEqualTo(DefectModelEvaluator.Status.INSUFFICIENT_LABELS);
        assertThat(outcome.predictions()).isEmpty();
    }
}

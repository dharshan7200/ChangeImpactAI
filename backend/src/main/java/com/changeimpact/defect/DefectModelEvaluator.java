package com.changeimpact.defect;

import com.changeimpact.defect.JitDatasetBuilder.Sample;
import com.changeimpact.defect.SzzLabeler.Link;

import java.time.Instant;
import java.util.*;

/**
 * Time-ordered evaluation of the just-in-time defect model, and the gate that decides whether it may be used.
 *
 * <pre>
 *  window (oldest → newest, non-merge commits that change code)
 *  | warm-up 10 % | training 60 %                      | test 25 %            | immature 5 % |
 *                  labels: only fixes before the cutoff  labels: all fixes      excluded
 * </pre>
 * Warm-up commits have incomplete history features and are not used. Training labels only use fixes committed before
 * the first test commit — what a model trained at that moment could have known — so no future information leaks into
 * training. The newest commits are excluded from testing because their bugs have mostly not been fixed yet.
 *
 * <p>Gate: at least {@value #MIN_TRAIN_POSITIVES} bug-introducing commits in training and {@value #MIN_TEST_POSITIVES}
 * in test, a test ROC-AUC of at least {@value #MIN_AUC}, and at least {@value #MIN_GAIN} above the churn baseline
 * (larger changes are riskier). Otherwise the model is reported but not used for predictions.
 */
public final class DefectModelEvaluator {

    public static final double WARMUP = 0.10, TRAIN_END = 0.70, TEST_END = 0.95, LAMBDA = 1.0;
    public static final int MIN_TRAIN_POSITIVES = 30, MIN_TEST_POSITIVES = 10;
    public static final double MIN_AUC = 0.65, MIN_GAIN = 0.02;
    public static final int SCORED_COMMITS = 300;

    public enum Status { ACCEPTED, INSUFFICIENT_LABELS, NOT_BETTER_THAN_BASELINE }

    public record Outcome(Status status, String reason, Map<String, Object> evaluation, LogisticModel finalModel,
                          double threshold, List<Map<String, Object>> predictions) {}

    public Outcome evaluate(List<Sample> samples, List<Link> links) {
        Map<String, Object> ev = new LinkedHashMap<>();
        int n = samples.size();
        int warm = (int) (n * WARMUP), trainEnd = (int) (n * TRAIN_END), testEnd = (int) (n * TEST_END);
        List<Sample> train = samples.subList(warm, trainEnd), test = samples.subList(trainEnd, testEnd);
        ev.put("samples", n);
        ev.put("split", Map.of("warmup", warm, "train", train.size(), "test", test.size(), "immature", n - testEnd));
        if (train.isEmpty() || test.isEmpty()) {
            return new Outcome(Status.INSUFFICIENT_LABELS, "Too few commits (" + n + ") for a time-ordered split", ev, null, 0, List.of());
        }
        long cutoff = test.get(0).time();
        ev.put("trainPeriod", period(train));
        ev.put("testPeriod", period(test));
        int[] yTrain = labels(train, links, cutoff), yTest = labels(test, links, Long.MAX_VALUE);
        int trainPos = Arrays.stream(yTrain).sum(), testPos = Arrays.stream(yTest).sum();
        ev.put("trainPositives", trainPos);
        ev.put("testPositives", testPos);
        ev.put("trainPositiveRate", trainPos / (double) train.size());
        ev.put("testPositiveRate", testPos / (double) test.size());

        if (trainPos < MIN_TRAIN_POSITIVES || testPos < MIN_TEST_POSITIVES) {
            return new Outcome(Status.INSUFFICIENT_LABELS, "Only " + trainPos + " bug-introducing commits in training and "
                    + testPos + " in test (need " + MIN_TRAIN_POSITIVES + " and " + MIN_TEST_POSITIVES + ")", ev, null, 0, List.of());
        }
        Map<String, Object> result = trainAndTest(train, yTrain, test, yTest);
        ev.putAll(result);
        double auc = (double) ((Map<?, ?>) result.get("model")).get("auc");
        double churnAuc = (double) ((Map<?, ?>) result.get("churnBaseline")).get("auc");
        Status status;
        String reason;
        if (!(auc >= MIN_AUC)) {
            status = Status.NOT_BETTER_THAN_BASELINE;
            reason = String.format(Locale.ROOT, "Test ROC-AUC %.3f is below %.2f", auc, MIN_AUC);
        } else if (auc < churnAuc + MIN_GAIN) {
            status = Status.NOT_BETTER_THAN_BASELINE;
            reason = String.format(Locale.ROOT, "Test ROC-AUC %.3f does not beat the churn baseline (%.3f) by %.2f", auc, churnAuc, MIN_GAIN);
        } else {
            status = Status.ACCEPTED;
            reason = String.format(Locale.ROOT, "Test ROC-AUC %.3f (churn baseline %.3f) on %d later commits", auc, churnAuc, test.size());
        }
        if (status != Status.ACCEPTED) return new Outcome(status, reason, ev, null, 0, List.of());

        // final model: all usable commits, labels from every fix known now
        List<Sample> usable = samples.subList(warm, testEnd);
        int[] yAll = labels(usable, links, Long.MAX_VALUE);
        LogisticModel model = LogisticModel.fit(features(usable), yAll, LAMBDA);
        double threshold = ClassificationMetrics.bestF1Threshold(scores(model, usable), yAll);
        List<Map<String, Object>> predictions = new ArrayList<>();
        for (Sample s : samples.subList(Math.max(warm, n - SCORED_COMMITS), n)) predictions.add(prediction(model, threshold, s));
        Collections.reverse(predictions); // newest first
        return new Outcome(status, reason, ev, model, threshold, predictions);
    }

    /** Trains on one sample set and evaluates on another (also used for cross-repository runs). */
    public static Map<String, Object> trainAndTest(List<Sample> train, int[] yTrain, List<Sample> test, int[] yTest) {
        Map<String, Object> out = new LinkedHashMap<>();
        LogisticModel model = LogisticModel.fit(features(train), yTrain, LAMBDA);
        double[] pTrain = scores(model, train), pTest = scores(model, test);
        double threshold = ClassificationMetrics.bestF1Threshold(pTrain, yTrain);
        var bins = ClassificationMetrics.calibration(pTest, yTest);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("auc", ClassificationMetrics.auc(pTest, yTest));
        m.put("brier", ClassificationMetrics.brier(pTest, yTest));
        m.put("ece", ClassificationMetrics.ece(bins));
        m.put("threshold", threshold);
        m.put("atThreshold", ClassificationMetrics.at(pTest, yTest, threshold));
        m.put("atHalf", ClassificationMetrics.at(pTest, yTest, 0.5));
        m.put("calibration", bins);
        m.put("coefficients", model.toMap(JitDatasetBuilder.FEATURES));
        out.put("model", m);

        // baseline 1: churn (LA + LD) — "larger changes are riskier"
        double[] cTrain = churn(train), cTest = churn(test);
        double cThreshold = ClassificationMetrics.bestF1Threshold(cTrain, yTrain);
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("auc", ClassificationMetrics.auc(cTest, yTest));
        c.put("atThreshold", ClassificationMetrics.at(cTest, yTest, cThreshold));
        out.put("churnBaseline", c);

        // baseline 2: training prevalence for every commit (no ranking; the reference for Brier)
        double prevalence = Arrays.stream(yTrain).average().orElse(0);
        double[] constant = new double[test.size()];
        Arrays.fill(constant, prevalence);
        out.put("priorBaseline", Map.of("auc", 0.5, "brier", ClassificationMetrics.brier(constant, yTest), "rate", prevalence));
        return out;
    }

    public static int[] labels(List<Sample> samples, List<Link> links, long fixedBefore) {
        Set<String> buggy = new HashSet<>();
        for (Link l : links) if (l.fixTime() < fixedBefore) buggy.add(l.introducing());
        int[] y = new int[samples.size()];
        for (int i = 0; i < y.length; i++) y[i] = buggy.contains(samples.get(i).sha()) ? 1 : 0;
        return y;
    }

    static List<double[]> features(List<Sample> s) {
        return s.stream().map(Sample::features).toList();
    }

    static double[] scores(LogisticModel m, List<Sample> s) {
        double[] p = new double[s.size()];
        for (int i = 0; i < p.length; i++) p[i] = m.predict(s.get(i).features());
        return p;
    }

    static double[] churn(List<Sample> s) {
        int la = JitDatasetBuilder.FEATURES.indexOf("LA"), ld = JitDatasetBuilder.FEATURES.indexOf("LD");
        double[] c = new double[s.size()];
        for (int i = 0; i < c.length; i++) c[i] = s.get(i).features()[la] + s.get(i).features()[ld];
        return c;
    }

    static Map<String, Object> prediction(LogisticModel model, double threshold, Sample s) {
        double p = model.predict(s.features());
        double[] contrib = model.contributions(s.features());
        List<Map<String, Object>> top = new ArrayList<>();
        Integer[] order = new Integer[contrib.length];
        for (int j = 0; j < order.length; j++) order[j] = j;
        Arrays.sort(order, (a, b) -> Double.compare(Math.abs(contrib[b]), Math.abs(contrib[a])));
        for (int k = 0; k < 4; k++) {
            int j = order[k];
            top.add(Map.of("feature", JitDatasetBuilder.FEATURES.get(j), "value", s.features()[j], "contribution", contrib[j]));
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("sha", s.sha());
        m.put("time", Instant.ofEpochMilli(s.time()).toString());
        m.put("message", s.subject() == null || s.subject().length() <= 120 ? s.subject() : s.subject().substring(0, 117) + "...");
        m.put("probability", p);
        m.put("flagged", p >= threshold);
        m.put("drivers", top);
        return m;
    }

    private static Map<String, Object> period(List<Sample> s) {
        return Map.of("from", Instant.ofEpochMilli(s.get(0).time()).toString(),
                "to", Instant.ofEpochMilli(s.get(s.size() - 1).time()).toString());
    }
}

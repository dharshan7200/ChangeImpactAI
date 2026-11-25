package com.changeimpact.defect;

import java.util.*;

/** Evaluation metrics for scored binary predictions. */
public final class ClassificationMetrics {

    private ClassificationMetrics() {}

    public record AtThreshold(double threshold, int tp, int fp, int fn, int tn, double precision, double recall, double f1) {}

    public record Bin(double lower, double upper, int count, double meanPredicted, double observedRate) {}

    /** ROC-AUC as the Mann–Whitney probability that a positive outranks a negative (ties count half). NaN if one class is missing. */
    public static double auc(double[] score, int[] y) {
        int n = score.length;
        Integer[] idx = new Integer[n];
        for (int i = 0; i < n; i++) idx[i] = i;
        Arrays.sort(idx, Comparator.comparingDouble(i -> score[i]));
        double[] rank = new double[n];
        for (int i = 0; i < n; ) {
            int j = i;
            while (j + 1 < n && score[idx[j + 1]] == score[idx[i]]) j++;
            double r = (i + j) / 2.0 + 1;
            for (int k = i; k <= j; k++) rank[idx[k]] = r;
            i = j + 1;
        }
        long pos = Arrays.stream(y).filter(v -> v == 1).count(), neg = n - pos;
        if (pos == 0 || neg == 0) return Double.NaN;
        double sum = 0;
        for (int i = 0; i < n; i++) if (y[i] == 1) sum += rank[i];
        return (sum - pos * (pos + 1) / 2.0) / (pos * (double) neg);
    }

    public static double brier(double[] p, int[] y) {
        double s = 0;
        for (int i = 0; i < p.length; i++) s += (p[i] - y[i]) * (p[i] - y[i]);
        return p.length == 0 ? Double.NaN : s / p.length;
    }

    public static AtThreshold at(double[] p, int[] y, double threshold) {
        int tp = 0, fp = 0, fn = 0, tn = 0;
        for (int i = 0; i < p.length; i++) {
            boolean predicted = p[i] >= threshold;
            if (predicted && y[i] == 1) tp++;
            else if (predicted) fp++;
            else if (y[i] == 1) fn++;
            else tn++;
        }
        double precision = tp + fp == 0 ? 0 : tp / (double) (tp + fp);
        double recall = tp + fn == 0 ? 0 : tp / (double) (tp + fn);
        double f1 = precision + recall == 0 ? 0 : 2 * precision * recall / (precision + recall);
        return new AtThreshold(threshold, tp, fp, fn, tn, precision, recall, f1);
    }

    /** Threshold (among the observed scores) with the best F1. */
    public static double bestF1Threshold(double[] p, int[] y) {
        double best = 0.5, bestF1 = -1;
        TreeSet<Double> candidates = new TreeSet<>();
        for (double v : p) candidates.add(v);
        for (double t : candidates) {
            double f1 = at(p, y, t).f1();
            if (f1 > bestF1 + 1e-12) { bestF1 = f1; best = t; }
        }
        return best;
    }

    /** Reliability diagram: ten equal-width bins of predicted probability. */
    public static List<Bin> calibration(double[] p, int[] y) {
        int bins = 10;
        int[] count = new int[bins];
        double[] sumP = new double[bins], sumY = new double[bins];
        for (int i = 0; i < p.length; i++) {
            int b = Math.min(bins - 1, (int) (p[i] * bins));
            count[b]++;
            sumP[b] += p[i];
            sumY[b] += y[i];
        }
        List<Bin> out = new ArrayList<>();
        for (int b = 0; b < bins; b++) {
            out.add(new Bin(b / (double) bins, (b + 1) / (double) bins, count[b],
                    count[b] == 0 ? Double.NaN : sumP[b] / count[b], count[b] == 0 ? Double.NaN : sumY[b] / count[b]));
        }
        return out;
    }

    /** Expected calibration error: count-weighted mean |predicted − observed| over the bins. */
    public static double ece(List<Bin> bins) {
        int n = bins.stream().mapToInt(Bin::count).sum();
        double s = 0;
        for (Bin b : bins) if (b.count() > 0) s += b.count() * Math.abs(b.meanPredicted() - b.observedRate());
        return n == 0 ? Double.NaN : s / n;
    }
}

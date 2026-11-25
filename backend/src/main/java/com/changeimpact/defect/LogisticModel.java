package com.changeimpact.defect;

import java.util.*;

/**
 * L2-regularised logistic regression fitted with Newton–Raphson (IRLS). Inputs are log1p-transformed (all JIT
 * features are non-negative and heavily skewed) and standardised with the training mean and deviation. Small,
 * deterministic and fully inspectable: the model is its coefficients, and each prediction can be decomposed into
 * per-feature contributions on the log-odds scale.
 */
public final class LogisticModel {

    private final double[] mean, std, coef; // coef[0] = intercept
    private final double lambda;

    private LogisticModel(double[] mean, double[] std, double[] coef, double lambda) {
        this.mean = mean;
        this.std = std;
        this.coef = coef;
        this.lambda = lambda;
    }

    public static LogisticModel fit(List<double[]> x, int[] y, double lambda) {
        int n = x.size(), d = x.get(0).length;
        double[] mean = new double[d], std = new double[d];
        for (double[] row : x) for (int j = 0; j < d; j++) mean[j] += Math.log1p(row[j]) / n;
        for (double[] row : x) for (int j = 0; j < d; j++) std[j] += Math.pow(Math.log1p(row[j]) - mean[j], 2) / n;
        for (int j = 0; j < d; j++) std[j] = std[j] < 1e-12 ? 1 : Math.sqrt(std[j]);
        double[][] z = new double[n][];
        for (int i = 0; i < n; i++) z[i] = standardise(x.get(i), mean, std);

        double[] w = new double[d + 1];
        double prevalence = Arrays.stream(y).average().orElse(0.5);
        w[0] = Math.log(Math.max(1e-6, prevalence) / Math.max(1e-6, 1 - prevalence));
        for (int iter = 0; iter < 100; iter++) {
            double[] g = new double[d + 1];
            double[][] h = new double[d + 1][d + 1];
            for (int i = 0; i < n; i++) {
                double p = sigmoid(dot(w, z[i]));
                double r = p - y[i], s = Math.max(p * (1 - p), 1e-9);
                g[0] += r;
                h[0][0] += s;
                for (int j = 0; j < d; j++) {
                    g[j + 1] += r * z[i][j];
                    h[0][j + 1] += s * z[i][j];
                    for (int k = j; k < d; k++) h[j + 1][k + 1] += s * z[i][j] * z[i][k];
                }
            }
            for (int j = 0; j <= d; j++) for (int k = 0; k < j; k++) h[j][k] = h[k][j];
            for (int j = 1; j <= d; j++) { g[j] += lambda * w[j]; h[j][j] += lambda; }
            double[] step = solve(h, g);
            double max = 0;
            for (int j = 0; j <= d; j++) { w[j] -= step[j]; max = Math.max(max, Math.abs(step[j])); }
            if (max < 1e-8) break;
        }
        return new LogisticModel(mean, std, w, lambda);
    }

    public double predict(double[] features) {
        return sigmoid(dot(coef, standardise(features, mean, std)));
    }

    /** Contribution of each feature to the log-odds of this sample, relative to an average sample. */
    public double[] contributions(double[] features) {
        double[] z = standardise(features, mean, std), out = new double[z.length];
        for (int j = 0; j < z.length; j++) out[j] = coef[j + 1] * z[j];
        return out;
    }

    public double[] coefficients() { return coef.clone(); }

    public Map<String, Object> toMap(List<String> names) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "logistic-regression-l2");
        m.put("lambda", lambda);
        m.put("intercept", coef[0]);
        List<Map<String, Object>> fs = new ArrayList<>();
        for (int j = 0; j < names.size(); j++) {
            fs.add(Map.of("name", names.get(j), "coefficient", coef[j + 1], "mean", mean[j], "std", std[j]));
        }
        m.put("features", fs);
        m.put("transform", "log1p, then standardised with the training mean and deviation");
        return m;
    }

    @SuppressWarnings("unchecked")
    public static LogisticModel fromMap(Map<String, Object> m) {
        List<Map<String, Object>> fs = (List<Map<String, Object>>) m.get("features");
        int d = fs.size();
        double[] mean = new double[d], std = new double[d], coef = new double[d + 1];
        coef[0] = ((Number) m.get("intercept")).doubleValue();
        for (int j = 0; j < d; j++) {
            coef[j + 1] = ((Number) fs.get(j).get("coefficient")).doubleValue();
            mean[j] = ((Number) fs.get(j).get("mean")).doubleValue();
            std[j] = ((Number) fs.get(j).get("std")).doubleValue();
        }
        return new LogisticModel(mean, std, coef, ((Number) m.get("lambda")).doubleValue());
    }

    private static double[] standardise(double[] x, double[] mean, double[] std) {
        double[] z = new double[x.length];
        for (int j = 0; j < x.length; j++) z[j] = (Math.log1p(Math.max(0, x[j])) - mean[j]) / std[j];
        return z;
    }

    private static double dot(double[] w, double[] z) {
        double s = w[0];
        for (int j = 0; j < z.length; j++) s += w[j + 1] * z[j];
        return s;
    }

    static double sigmoid(double v) {
        return v >= 0 ? 1 / (1 + Math.exp(-v)) : Math.exp(v) / (1 + Math.exp(v));
    }

    /** Gaussian elimination with partial pivoting. */
    static double[] solve(double[][] a0, double[] b0) {
        int n = b0.length;
        double[][] a = new double[n][];
        for (int i = 0; i < n; i++) a[i] = a0[i].clone();
        double[] b = b0.clone();
        for (int c = 0; c < n; c++) {
            int p = c;
            for (int r = c + 1; r < n; r++) if (Math.abs(a[r][c]) > Math.abs(a[p][c])) p = r;
            double[] t = a[c]; a[c] = a[p]; a[p] = t;
            double tb = b[c]; b[c] = b[p]; b[p] = tb;
            if (Math.abs(a[c][c]) < 1e-12) continue;
            for (int r = c + 1; r < n; r++) {
                double f = a[r][c] / a[c][c];
                if (f == 0) continue;
                for (int k = c; k < n; k++) a[r][k] -= f * a[c][k];
                b[r] -= f * b[c];
            }
        }
        double[] x = new double[n];
        for (int r = n - 1; r >= 0; r--) {
            double s = b[r];
            for (int k = r + 1; k < n; k++) s -= a[r][k] * x[k];
            x[r] = Math.abs(a[r][r]) < 1e-12 ? 0 : s / a[r][r];
        }
        return x;
    }
}

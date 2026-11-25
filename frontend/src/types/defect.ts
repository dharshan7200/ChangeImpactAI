// Historical defect-prediction prototype (backend: defect/DefectModelService).
export type DefectStatus = 'ACCEPTED' | 'INSUFFICIENT_LABELS' | 'NOT_BETTER_THAN_BASELINE';

export interface AtThreshold { threshold: number; tp: number; fp: number; fn: number; tn: number; precision: number; recall: number; f1: number }
export interface CalibrationBin { lower: number; upper: number; count: number; meanPredicted: number | null; observedRate: number | null }
export interface Driver { feature: string; value: number; contribution: number }
export interface DefectPrediction { sha: string; time: string; probability: number; flagged: boolean; drivers: Driver[]; message?: string }

export interface DefectEvaluation {
  samples: number;
  split: { warmup: number; train: number; test: number; immature: number };
  trainPeriod?: { from: string; to: string };
  testPeriod?: { from: string; to: string };
  trainPositives?: number;
  testPositives?: number;
  trainPositiveRate?: number;
  testPositiveRate?: number;
  model?: {
    auc: number; brier: number; ece: number; threshold: number; atThreshold: AtThreshold; atHalf: AtThreshold;
    calibration: CalibrationBin[];
    coefficients: { intercept: number; features: { name: string; coefficient: number }[] };
  };
  churnBaseline?: { auc: number; atThreshold: AtThreshold };
  priorBaseline?: { auc: number; brier: number; rate: number };
}

export interface DefectModel {
  status: DefectStatus;
  reason: string;
  headCommit: string;
  current: boolean;
  trainedAt: string;
  threshold: number | null;
  dataset: { commits: number; from: string | null; to: string | null; fixes: number; tracedFixes: number; untraceableFixes: number;
    bugIntroducingCommits: number; datasetMs: number; labelingMs: number; maxCommits: number };
  evaluation: DefectEvaluation;
  predictions: DefectPrediction[];
}

export interface DefectRangeView {
  status: DefectStatus | 'NOT_TRAINED' | 'OUTDATED' | 'UNAVAILABLE';
  reason?: string;
  trainedAt?: string;
  commits?: DefectPrediction[];
  notScored?: number;
  withoutCodeChanges?: number;
  threshold?: number;
  testAuc?: number;
}

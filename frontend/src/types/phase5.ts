// Phase 5: test intelligence, security impact, GitHub App, team dashboard

export type Severity = 'INFO' | 'LOW' | 'MEDIUM' | 'HIGH' | 'CRITICAL';

export interface TestRecommendation {
  id: string;
  category: 'DIRECT' | 'INDIRECT' | 'MISSING_TEST' | 'SCENARIO';
  priority: 'HIGH' | 'MEDIUM' | 'LOW';
  testClass?: string;
  testMethod?: string;
  targetSymbol?: string;
  reason: string;
  executionStatus: 'PASSED' | 'FAILED' | 'SKIPPED' | 'NOT_EXECUTED' | 'NO_RESULTS' | 'NOT_APPLICABLE';
  depth?: number;
  evidence?: Record<string, unknown>;
}

export interface MutationMethod {
  symbol: string;
  changed: boolean;
  mutants: number;
  byStatus: Record<string, number>;
  mutationScore: number;
  survivors: { line?: number; status: string; mutator: string; description?: string }[];
  killingTests: string[];
}

export interface TestsSummary {
  status: 'COMPLETED' | 'FAILED' | 'PENDING' | 'NOT_RUN';
  detail?: string;
  byCategory?: Record<string, number>;
  testClasses?: number;
  executionResults?: 'NO_RESULTS_UPLOADED' | { reportId: string; byStatus: Record<string, number> };
  changedLineCoverage?:
    | 'NO_REPORT_FOR_COMMIT'
    | { reportId: string; changedLinesCovered: number; changedLinesExecutable: number; ratio: number | null;
        bySymbol: { symbol: string; covered: number; executable: number }[] };
  mutation?: { status: 'AVAILABLE' | 'NO_REPORT_FOR_COMMIT'; mutationScore?: number | null; weakMethods?: number;
               methodsWithMutants?: number; methods?: MutationMethod[]; note?: string };
  note?: string;
}

export interface TestsResponse {
  summary: TestsSummary;
  recommendations: TestRecommendation[];
  note: string;
}

export interface SecurityFinding {
  id: string;
  source: 'STATIC_RULES' | 'SEMGREP' | 'BUILD_DIFF' | 'DEPENDENCY_CHECK';
  category: string;
  ruleId?: string;
  severity: Severity;
  title: string;
  message?: string;
  filePath?: string;
  startLine?: number;
  endLine?: number;
  symbol?: string;
  relation: 'CHANGED' | 'IMPACTED' | 'DEPENDENCY';
  cwe?: string;
}

export interface SecuritySummary {
  status: 'COMPLETED' | 'FAILED' | 'PENDING' | 'NOT_RUN';
  detail?: string;
  semgrep?: { status: string; detail?: string; filesScanned: number; findings: number };
  dependencyChanges?: { file: string; coordinate: string; type: string; before: string; after: string }[];
  dependencyCheck?: 'NO_REPORT_FOR_COMMIT' | { reportId: string; preExistingVulnerabilities: Record<string, number> };
  bySeverity?: Record<string, number>;
  findings?: number;
  note?: string;
}

export interface SecurityResponse {
  summary: SecuritySummary;
  findings: SecurityFinding[];
  note: string;
}

export type ReportType = 'JACOCO' | 'JUNIT' | 'PIT' | 'DEPENDENCY_CHECK';

export interface QualityReport {
  id: string;
  repositoryId: string;
  commitSha: string;
  reportType: ReportType;
  fileName?: string;
  summary: Record<string, unknown>;
  createdAt: string;
}

export interface PullRequest {
  id: string;
  repositoryId: string;
  number: number;
  title?: string;
  author?: string;
  url?: string;
  state: string;
  draft: boolean;
  baseRef?: string;
  baseSha?: string;
  headRef?: string;
  headSha?: string;
  lastAnalysisId?: string;
  riskLevel?: string;
  riskScore?: number;
  checkRunId?: number;
  checkStatus?: string;
  checkConclusion?: string;
  checkError?: string;
  updatedAt?: string;
}

export interface GitHubAppStatus {
  appConfigured: boolean;
  webhookSecretConfigured: boolean;
  checksEnabled: boolean;
  checkName: string;
  failOn: string;
  analyzeDraftPullRequests: boolean;
  webhookPath: string;
  detailsLinks: boolean;
  installUrl?: string;
  requiredPermissions: Record<string, string>;
  requiredEvents: string[];
  repositories: { repositoryId: string; fullName: string; installed: boolean; installationId?: number; suspended: boolean }[];
}

export interface WebhookDelivery {
  id: string;
  deliveryId: string;
  event: string;
  action?: string;
  repositoryFullName?: string;
  status: string;
  detail?: string;
  error?: string;
  analysisIds?: string[];
  receivedAt: string;
}

export interface DashboardAnalysis {
  analysisId: string;
  repository: string;
  baseRef: string;
  headRef: string;
  riskLevel?: string;
  riskScore?: number;
  trigger: string;
  pullRequestNumber?: number;
  createdAt: string;
}

export interface LatestReportValue<T> {
  value: T;
  commitSha: string;
  reportedAt: string;
}

export interface TeamDashboard {
  windowDays: number;
  totals: Record<string, number>;
  openPullRequests: {
    repository: string; repositoryId: string; number: number; title?: string; author?: string; url?: string; draft: boolean;
    riskLevel?: string; riskScore?: number; checkStatus?: string; checkConclusion?: string; analysisId?: string; updatedAt?: string;
  }[];
  highRiskChanges: DashboardAnalysis[];
  security: {
    bySeverity: Record<string, number>; byCategory: Record<string, number>; bySource: Record<string, number>;
    top: { severity: Severity; title: string; source: string; location?: string; repository?: string; analysisId: string }[];
  };
  riskTrend: { date: string; analyses: number; maxScore: number | null; avgScore: number | null; high: number }[];
  repositories: {
    repositoryId: string; fullName: string; indexingStatus: string; lastIndexedAt?: string; lastIndexedCommit?: string;
    architectureViolations?: number | null;
    coverage?: LatestReportValue<number | null> | null;
    tests?: LatestReportValue<Record<string, number> | null> | null;
    mutationScore?: LatestReportValue<number | null> | null;
    knownVulnerabilities?: LatestReportValue<Record<string, number> | null> | null;
    analyses: number;
    lastAnalysis?: DashboardAnalysis | null;
  }[];
  executionHistory: { jobId: string; type: string; status: string; repository?: string; stage?: string; createdAt: string; durationMs?: number; error?: string }[];
  note: string;
}

export interface ApiResponse<T> {
  success: boolean;
  message?: string;
  data: T;
  errors?: Array<{ field?: string; message: string; code?: string }>;
  metadata?: Record<string, any>;
  timestamp: string;
}

export interface UserSummary {
  id: string;
  email: string;
  username: string;
  displayName: string;
  avatarUrl?: string;
}

export interface AuthResponse {
  accessToken: string;
  refreshToken?: string;
  accessTokenExpiresInMs: number;
  user: UserSummary;
}

export interface ConnectedAccount {
  id: string;
  provider: string;
  providerAccountId: string;
  login: string;
  displayName?: string;
  avatarUrl?: string;
  email?: string;
  connectedAt: string;
  isActive: boolean;
}

export interface GitHubRepository {
  id: number;
  name: string;
  full_name: string;
  description?: string;
  clone_url: string;
  html_url?: string;
  default_branch: string;
  language?: string;
  private: boolean;
  fork: boolean;
  forks_count?: number;
  stargazers_count?: number;
  size?: number;
}

export interface Repository {
  id: string;
  fullName: string;
  name: string;
  description?: string;
  htmlUrl?: string;
  defaultBranch: string;
  language?: string;
  isPrivate: boolean;
  isFork: boolean;
  starCount: number;
  forkCount: number;
  indexingStatus: 'NOT_INDEXED' | 'INDEXING' | 'INDEXED' | 'FAILED' | 'STALE';
  lastIndexedAt?: string;
  lastIndexedCommit?: string;
  createdAt: string;
}

export interface AnalysisJob {
  id: string;
  repositoryId: string;
  jobType: 'REPOSITORY_INDEX' | 'CHANGE_ANALYSIS' | 'PR_ANALYSIS';
  status: 'QUEUED' | 'RUNNING' | 'COMPLETED' | 'FAILED' | 'CANCELLED';
  priority: number;
  payload?: Record<string, any>;
  result?: Record<string, any>;
  errorMessage?: string;
  retryCount: number;
  queuedAt: string;
  startedAt?: string;
  completedAt?: string;
  durationMs?: number;
}

export interface CodeSymbol {
  id: string;
  symbolType: 'PACKAGE' | 'CLASS' | 'INTERFACE' | 'ENUM' | 'RECORD' | 'METHOD' | 'CONSTRUCTOR' | 'FIELD' | 'MODULE' | 'FUNCTION';
  qualifiedName: string;
  simpleName: string;
  returnType?: string;
  parameters?: string;
  visibility: string;
  isStatic: boolean;
  isAbstract: boolean;
  isFinal: boolean;
  annotations?: string[];
  startLine: number;
  endLine: number;
  resolutionStatus: string;
}

export type GraphViewMode = 'PACKAGE' | 'CLASS' | 'MEMBER';

export interface GraphNodeView {
  id: string;
  label: string;
  /** CLASS | INTERFACE | ENUM | RECORD | METHOD | CONSTRUCTOR | FIELD | PACKAGE */
  type: string;
  qualifiedName: string;
  packageName?: string;
  sourceFile: string;
  startLine: number;
  endLine: number;
  /** Enclosing type id (equals id for a type) */
  ownerId?: string;
  /** Members folded into a class (class view) or types in a package (package view) */
  memberCount?: number;
  inDegree?: number;
  outDegree?: number;
  testCode?: boolean;
}

export interface GraphEdgeView {
  id: string;
  source: string;
  target: string;
  /** Primary relationship kind */
  type: string;
  /** All relationship kinds merged into this edge */
  types?: string[];
  /** Number of source-code references behind this edge */
  count?: number;
  line: number;
}

export interface GraphSnapshot {
  view?: GraphViewMode;
  nodes: GraphNodeView[];
  edges: GraphEdgeView[];
  totalNodes: number;
  totalEdges: number;
}

/** Node returned by the dependencies/dependents (blast radius) endpoints */
export interface TraversalNode {
  id: string;
  qualifiedName: string;
  simpleName: string;
  symbolType: string;
  sourceFile: string;
  startLine: number;
  endLine: number;
  ownerTypeId?: string;
  packageName?: string;
  testCode?: boolean;
}

export interface GraphStats {
  commitSha?: string;
  graph?: {
    symbols: number;
    memberEdges: number;
    types: number;
    typeEdges: number;
    packages: number;
    symbolsByType: Record<string, number>;
    memberEdgesByRelationship: Record<string, number>;
    cycleGroups: number;
    typesInCycles: number;
    isolatedTypes: number;
  };
  referencesByResolution?: Record<string, number>;
  libraries?: { status: 'RESOLVED' | 'PARTIAL' | 'FAILED' | 'NO_BUILD_FILE' | 'DISABLED'; buildSystem?: string | null; jarCount: number; totalBytes: number; message?: string | null; resolveMs?: number | null } | null;
}

export interface ArchitectureViolation {
  id: string;
  ruleName: string;
  ruleType?: string;
  sourceSymbol?: string;
  targetSymbol?: string;
  severity: 'HIGH' | 'MEDIUM' | 'LOW';
  description: string;
  sourceLocation?: string;
  detectedAt: string;
}

export interface CouplingMetrics {
  symbolId: string;
  qualifiedName: string;
  symbolType: string;
  afferentCoupling: number;
  efferentCoupling: number;
  instability: number;
  hotspot: boolean;
}



// ---------------------------------------------------------------------------
// Phase 3: change impact analysis
// ---------------------------------------------------------------------------

export type RiskLevel = 'LOW' | 'MEDIUM' | 'HIGH' | 'CRITICAL';

export interface CommitInfo {
  sha: string;
  shortSha: string;
  message: string;
  author: string;
  date: string;
  parents?: string[];
  additions: number;
  deletions: number;
  filesChanged: number;
  merge: boolean;
  revert: boolean;
  bugfix: boolean;
}

export interface AnalysisSummary {
  aiReview?: { status: string; detail: string };
  baseCommit: string;
  headCommit: string;
  usedMergeBase: boolean;
  filesChanged: number;
  filesByCategory: Record<string, number>;
  additions: number;
  deletions: number;
  symbolsChanged: number;
  productionSymbolsChanged: number;
  breakingChanges: number;
  changesByKind: Record<string, number>;
  directlyImpacted: number;
  totalImpacted: number;
  impactedTests: number;
  riskDistribution: Record<RiskLevel, number>;
  affectedPackages: { packageName: string; impactedSymbols: number; maxStrength: number }[];
  affectedModules: Record<string, number>;
  configKeyChanges: { file: string; key: string; changeType: string; before?: string; after?: string }[];
  buildFilesChanged: boolean;
  cosmeticJavaFiles: string[];
}

export interface ChangeAnalysis {
  id: string;
  repositoryId: string;
  baseRef: string;
  headRef: string;
  baseCommit?: string;
  headCommit?: string;
  status: 'QUEUED' | 'RUNNING' | 'COMPLETED' | 'FAILED';
  options?: {
    maxDepth: number;
    relationshipTypes: string[];
    useMergeBase: boolean;
    pullRequestNumber?: number;
    github?: { repository: string; pullNumber: number; headSha: string; installationId?: number; checkRunId?: number };
  };
  trigger?: 'MANUAL' | 'PULL_REQUEST';
  pullRequestId?: string;
  riskScore?: number;
  riskLevel?: RiskLevel;
  summary?: AnalysisSummary;
  errorMessage?: string;
  createdAt: string;
  completedAt?: string;
  stage?: string;
  progressMessage?: string;
  progressPercent?: number;
}

export interface RiskFactor {
  key: string;
  label: string;
  raw: unknown;
  normalized: number;
  weight: number;
  contribution: number;
  explanation: string;
}

export interface PathStep {
  symbolId: string;
  qualifiedName: string;
  simpleName: string;
  symbolType: string;
  relationship: string[];
  line: number;
}

export interface ImpactFindingView {
  id: string;
  changedSymbolId: string;
  affectedSymbolId?: string;
  affectedQualifiedName: string;
  affectedSimpleName: string;
  affectedSymbolType: string;
  affectedOwnerType?: string;
  filePath?: string;
  startLine?: number;
  packageName?: string;
  moduleName?: string;
  depth: number;
  strength: number;
  impactType: 'DIRECT' | 'INDIRECT';
  evidence: 'VERIFIED_REFERENCE' | 'TRANSITIVE_PROPAGATION';
  test: boolean;
  alsoChanged: boolean;
  dependencyPath: PathStep[];
}

export interface ChangedSymbolView {
  id: string;
  qualifiedName: string;
  previousQualifiedName?: string;
  simpleName: string;
  symbolType: string;
  changeKinds: string[];
  breaking: boolean;
  filePath?: string;
  startLine?: number;
  endLine?: number;
  test: boolean;
  details?: Record<string, unknown>;
  directDependents: number;
  transitiveDependents: number;
  riskScore: number;
  riskLevel: RiskLevel;
}

export interface ChangedFileView {
  id: string;
  path: string;
  oldPath?: string;
  changeType: string;
  fileCategory: string;
  additions: number;
  deletions: number;
}

export interface AnalysisReport {
  analysisId: string;
  repository: string;
  base: { ref: string; sha: string; message?: string; author?: string; date?: string };
  head: { ref: string; sha: string; message?: string; author?: string; date?: string };
  riskScore?: number;
  riskLevel?: RiskLevel;
  summary?: AnalysisSummary;
  disclaimer: string;
  architectureConcerns?: { rule: string; severity: string; description: string; location: string }[];
  testsReferencingChanges?: { testClass: string; references: number }[];
  verificationSteps?: { priority: string; action: string; reason: string; evidence: string[] }[];
  checklist?: ChecklistItem[];
  reviewers?: ReviewerSection;
  architecture?: ArchitectureDrift;
  defectModel?: import('./defect').DefectRangeView;
}

export interface ChecklistItem {
  area: 'REVIEW' | 'TESTS' | 'SECURITY' | 'ARCHITECTURE';
  action: string;
  evidence: string[];
  moreEvidence?: number;
}

export interface ReviewerSuggestion {
  name: string;
  email: string;
  score: number;
  changedFilesOwned: number;
  impactedFilesOwned: number;
  lastChange?: string;
  evidence: { path: string; relation: 'CHANGED' | 'IMPACTED'; commits: number; share: number; lastChange?: string }[];
}

export interface ReviewerSection {
  status: 'OK' | 'FAILED';
  detail?: string;
  historyCommits?: number;
  historyUntil?: string;
  changeAuthors?: { name: string; email: string; commits: number }[];
  suggestions?: ReviewerSuggestion[];
  filesWithoutHistory?: number;
}

export interface ArchitectureDrift {
  status: 'OK' | 'FAILED';
  detail?: string;
  findings?: string[];
  newCycles?: string[][];
  resolvedCycles?: string[][];
  addedDependencies?: { source: string; target: string; referencesAfter: number }[];
  addedDependencyCount?: number;
  removedDependencyCount?: number;
  addedPackages?: string[];
  removedPackages?: string[];
  newViolations?: string[];
  newViolationCount?: number;
  resolvedViolationCount?: number;
  before?: { packages: number; packageEdges: number; cycles: number; violations: number; averageDistance: number };
  after?: { packages: number; packageEdges: number; cycles: number; violations: number; averageDistance: number };
}

export interface RepositoryHistory {
  weeks: number;
  scannedCommits: number;
  commitsInWindow: number;
  bugfixCommitsInWindow: number;
  revertCommitsInWindow: number;
  weekly: { week: string; commits: number; bugfixCommits: number; revertCommits: number }[];
  mostChangedFiles: { path: string; commits: number; changedLines: number; bugfixCommits: number }[];
  note: string;
}

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
  symbolType: 'PACKAGE' | 'CLASS' | 'INTERFACE' | 'ENUM' | 'RECORD' | 'METHOD' | 'CONSTRUCTOR' | 'FIELD';
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

export interface GraphNodeView {
  id: string;
  label: string;
  type: string;
  qualifiedName: string;
  sourceFile: string;
  startLine: number;
  endLine: number;
}

export interface GraphEdgeView {
  id: string;
  source: string;
  target: string;
  type: string;
  line: number;
}

export interface GraphSnapshot {
  nodes: GraphNodeView[];
  edges: GraphEdgeView[];
  totalNodes: number;
  totalEdges: number;
}

export interface ArchitectureViolation {
  id: string;
  ruleName: string;
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
  isHotspot: boolean;
}



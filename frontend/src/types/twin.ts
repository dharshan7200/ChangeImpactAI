// Architecture digital twin (backend: architecture/twin/TwinModel). Null fields may be omitted by the API.

export interface TwinSummary {
  packages: number;
  files: number;
  types: number;
  methods: number;
  loc: number;
  complexity: number;
  packageEdges: number;
  crossPackageReferences: number;
  cycles: number;
  packagesInCycles: number;
  averageInstability: number;
  averageDistance: number;
  violations: number;
}

export interface TwinPackage {
  name: string;
  files: number;
  types: number;
  abstractTypes: number;
  methods: number;
  loc: number;
  complexity: number;
  ca: number;
  ce: number;
  instability: number;
  abstractness: number;
  distance: number;
  cycleGroup: number;
  order: number;
}

export interface TwinEdge {
  source: string;
  target: string;
  references: number;
  filePairs: number;
  inCycle: boolean;
}

export interface TwinSnapshot {
  commitSha: string;
  commitDate?: string;
  commitMessage?: string;
  summary: TwinSummary;
  packages: TwinPackage[];
  edges: TwinEdge[];
  cycles: string[][];
}

export interface TwinTrendPoint {
  commitSha: string;
  commitDate?: string;
  commitMessage?: string;
  summary: TwinSummary;
}

export interface TwinFile {
  path: string;
  packageName: string;
  primaryType?: string;
  loc: number;
  types: number;
  methods: number;
  complexity: number;
  maxMethodComplexity: number;
  churnCommits: number;
  churnLines: number;
  authors: number;
  lastChanged?: string;
  fanIn: number;
  coverage?: number;
  hotspotScore: number;
  hotspot: boolean;
}

export interface TwinHotspots {
  commitSha: string;
  historyCommits: number;
  coverageAvailable: boolean;
  files: TwinFile[];
}

export interface TwinEdgeDelta {
  source: string;
  target: string;
  referencesBefore: number;
  referencesAfter: number;
  status: 'ADDED' | 'REMOVED' | 'CHANGED';
}

export interface TwinPackageDelta {
  name: string;
  status: 'ADDED' | 'REMOVED' | 'CHANGED';
  locBefore: number;
  locAfter: number;
  caBefore: number;
  caAfter: number;
  ceBefore: number;
  ceAfter: number;
  instabilityBefore: number;
  instabilityAfter: number;
  distanceBefore: number;
  distanceAfter: number;
}

export interface TwinComparison {
  base: TwinTrendPoint;
  head: TwinTrendPoint;
  addedPackages: string[];
  removedPackages: string[];
  addedEdges: TwinEdgeDelta[];
  removedEdges: TwinEdgeDelta[];
  changedEdges: TwinEdgeDelta[];
  newCycles: string[][];
  resolvedCycles: string[][];
  packageDeltas: TwinPackageDelta[];
  newViolations: string[];
  resolvedViolations: string[];
  findings: string[];
}

export interface AnalysedCommit {
  sha: string;
  commitDate?: string;
  message?: string;
  snapshot: boolean;
}

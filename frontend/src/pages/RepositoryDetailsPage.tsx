import React, { useState } from 'react';
import { ArchitectureTwinPanel } from '../components/architecture/ArchitectureTwinPanel';
import { useParams, Link, useSearchParams } from 'react-router-dom';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { repositoriesApi } from '../api/repositories';
import { codeApi } from '../api/code';
import {
  FolderGit2, Play, ArrowLeft, GitCommit, FileCode, Clock,
  CheckCircle2, AlertTriangle, ExternalLink, RefreshCw,
  Network, ShieldAlert, Activity, Flame, GitBranch, Layers, GitCompare, History, GitPullRequest, FileCheck2, Bot
} from 'lucide-react';
import { StatusBadge } from './DashboardPage';
import { DependencyGraphExplorer } from '../components/graph/DependencyGraphExplorer';
import { ChangeImpactPanel } from '../components/analysis/ChangeImpactPanel';
import { HistoryPanel } from '../components/analysis/HistoryPanel';
import { DefectModelPanel } from '../components/analysis/DefectModelPanel';
import { PullRequestsPanel } from '../components/repository/PullRequestsPanel';
import { ReportsPanel } from '../components/repository/ReportsPanel';
import { AssistantPanel } from '../components/ai/AssistantPanel';
import { RuleManager } from '../components/architecture/RuleManager';
const CodeBrowser = React.lazy(() => import('../components/code/CodeBrowser'));

export const RepositoryDetailsPage: React.FC = () => {
  const { id } = useParams<{ id: string }>();
  type Tab = 'graph' | 'impact' | 'prs' | 'reports' | 'history' | 'architecture' | 'code' | 'assistant' | 'jobs';
  const [searchParams, setSearchParams] = useSearchParams();
  const TABS: Tab[] = ['graph', 'impact', 'prs', 'reports', 'history', 'architecture', 'code', 'assistant', 'jobs'];
  const [activeTab, setActiveTabState] = useState<Tab>(TABS.includes(searchParams.get('tab') as Tab) ? (searchParams.get('tab') as Tab) : 'graph');
  const setActiveTab = (t: Tab) => { setActiveTabState(t); setSearchParams(t === 'graph' ? {} : { tab: t }, { replace: true }); };
  const codePath = searchParams.get('path');
  const codeLine = searchParams.get('line') ? Number(searchParams.get('line')) : null;
  const openCode = (path: string, line?: number) => {
    setActiveTabState('code');
    setSearchParams(line ? { tab: 'code', path, line: String(line) } : { tab: 'code', path });
  };
  const queryClient = useQueryClient();

  const { data: repo, isLoading: repoLoading } = useQuery({
    queryKey: ['repository', id],
    queryFn: () => repositoriesApi.getById(id!),
    enabled: !!id,
    refetchInterval: (query) => {
      const data = query.state.data;
      return data?.indexingStatus === 'INDEXING' ? 2000 : false;
    },
  });

  const { data: indexStatus } = useQuery({
    queryKey: ['repository-index-status', id],
    queryFn: () => repositoriesApi.getIndexStatus(id!),
    enabled: !!id,
    refetchInterval: () => (repo?.indexingStatus === 'INDEXING' ? 1500 : 5000),
  });

  const { data: jobsData, isLoading: jobsLoading } = useQuery({
    queryKey: ['repository-jobs', id],
    queryFn: () => repositoriesApi.listJobs(id!),
    enabled: !!id,
    refetchInterval: 5000,
  });

  const { data: graphStats } = useQuery({
    queryKey: ['graph-stats', id, repo?.lastIndexedCommit],
    queryFn: () => codeApi.getGraphStats(id!),
    enabled: !!id && repo?.indexingStatus === 'INDEXED',
  });

  const { data: violations, isError: violationsError } = useQuery({
    queryKey: ['architecture-violations', id, repo?.lastIndexedCommit],
    queryFn: () => codeApi.getArchitectureViolations(id!),
    enabled: !!id && repo?.indexingStatus === 'INDEXED',
  });

  const { data: metrics, isError: metricsError } = useQuery({
    queryKey: ['coupling-metrics', id, repo?.lastIndexedCommit],
    queryFn: () => codeApi.getCouplingMetrics(id!),
    enabled: !!id && repo?.indexingStatus === 'INDEXED',
  });

  const indexMutation = useMutation({
    mutationFn: () => repositoriesApi.triggerIndexing(id!),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['repository', id] });
      queryClient.invalidateQueries({ queryKey: ['repository-index-status', id] });
      queryClient.invalidateQueries({ queryKey: ['repository-jobs', id] });
    },
  });

  if (repoLoading) {
    return <div className="py-20 text-center text-slate-500 text-sm">Loading repository details...</div>;
  }

  if (!repo) {
    return (
      <div className="py-20 text-center">
        <p className="text-slate-400 text-sm">Repository not found or access denied.</p>
        <Link to="/repositories" className="text-cyan-400 text-xs mt-2 inline-block">
          &larr; Back to repositories
        </Link>
      </div>
    );
  }

  const jobs = jobsData?.content || [];
  const hotspots = (metrics || []).filter((m) => m.hotspot);

  return (
    <div className="space-y-6">
      {/* Back button */}
      <div>
        <Link
          to="/repositories"
          className="inline-flex items-center space-x-1.5 text-xs text-slate-400 hover:text-cyan-400 transition"
        >
          <ArrowLeft className="w-3.5 h-3.5" />
          <span>Back to Repositories</span>
        </Link>
      </div>

      {/* Header Info Banner */}
      <div className="bg-slate-900 border border-slate-800 rounded-2xl p-6 flex flex-col md:flex-row md:items-center justify-between gap-4">
        <div className="flex min-w-0 items-start space-x-4">
          <div className="w-12 h-12 rounded-xl bg-cyan-500/10 border border-cyan-500/30 flex items-center justify-center text-cyan-400 shrink-0">
            <FolderGit2 className="w-6 h-6" />
          </div>
          <div className="min-w-0">
            <div className="flex flex-wrap items-center gap-x-3 gap-y-1">
              <h1 className="text-2xl font-bold text-white tracking-tight break-all">{repo.fullName}</h1>
              <StatusBadge status={repo.indexingStatus} />
            </div>
            {repo.description && <p className="text-sm text-slate-400 mt-1">{repo.description}</p>}
            <div className="flex flex-wrap items-center gap-4 text-xs text-slate-500 mt-3 font-mono">
              <span className="flex items-center space-x-1 text-slate-400">
                <FileCode className="w-3.5 h-3.5 text-amber-400" />
                <span>{repo.language || '—'}</span>
              </span>
              <span>Branch: <strong className="text-slate-300">{repo.defaultBranch}</strong></span>
              {repo.htmlUrl && (
                <a
                  href={repo.htmlUrl}
                  target="_blank"
                  rel="noreferrer"
                  className="text-cyan-400 hover:text-cyan-300 flex items-center space-x-1 font-sans"
                >
                  <span>GitHub</span>
                  <ExternalLink className="w-3 h-3" />
                </a>
              )}
            </div>
          </div>
        </div>

        <div className="flex items-center space-x-3">
          <button
            onClick={() => queryClient.invalidateQueries({ queryKey: ['repository', id] })}
            className="p-2.5 bg-slate-800 hover:bg-slate-700 text-slate-300 rounded-xl border border-slate-700 transition cursor-pointer"
            title="Refresh details"
          >
            <RefreshCw className="w-4 h-4" />
          </button>
          <button
            onClick={() => indexMutation.mutate()}
            disabled={repo.indexingStatus === 'INDEXING' || indexMutation.isPending}
            className="px-5 py-2.5 bg-cyan-500 hover:bg-cyan-400 text-slate-950 font-semibold text-xs rounded-xl flex items-center space-x-2 transition shadow-lg shadow-cyan-500/20 disabled:opacity-50 cursor-pointer"
          >
            <Play className="w-4 h-4" />
            <span>{repo.indexingStatus === 'INDEXING' ? 'Indexing In Progress...' : 'Start Indexing'}</span>
          </button>
        </div>
      </div>

      {/* Metrics Row */}
      <div className="grid grid-cols-1 md:grid-cols-4 gap-4">
        <div className="bg-slate-900 border border-slate-800 p-4 rounded-xl">
          <div className="flex items-center justify-between text-slate-400 mb-1">
            <span className="text-xs uppercase font-medium tracking-wider">Indexed Commit</span>
            <GitCommit className="w-4 h-4 text-cyan-400" />
          </div>
          <p className="text-sm font-mono text-white mt-1 break-all">
            {repo.lastIndexedCommit ? repo.lastIndexedCommit.substring(0, 10) : 'None'}
          </p>
          <p className="text-[10px] text-slate-500 mt-1">HEAD SHA at indexing execution</p>
        </div>

        <div className="bg-slate-900 border border-slate-800 p-4 rounded-xl">
          <div className="flex items-center justify-between text-slate-400 mb-1">
            <span className="text-xs uppercase font-medium tracking-wider">Total Symbols</span>
            <Activity className="w-4 h-4 text-emerald-400" />
          </div>
          <p className="text-xl font-bold text-white mt-1">{graphStats?.graph ? graphStats.graph.symbols : '-'}</p>
          <p className="text-[10px] text-slate-500 mt-1">
            {graphStats?.graph
              ? `${graphStats.graph.types} types · ${graphStats.graph.memberEdges} dependency edges`
              : 'Classes, methods, fields extracted'}
          </p>
        </div>

        <div className="bg-slate-900 border border-slate-800 p-4 rounded-xl">
          <div className="flex items-center justify-between text-slate-400 mb-1">
            <span className="text-xs uppercase font-medium tracking-wider">Coupling Hotspots</span>
            <Flame className="w-4 h-4 text-amber-400" />
          </div>
          <p className="text-xl font-bold text-amber-400 mt-1">{hotspots.length}</p>
          <p className="text-[10px] text-slate-500 mt-1">High afferent/efferent coupling</p>
        </div>

        <div className="bg-slate-900 border border-slate-800 p-4 rounded-xl">
          <div className="flex items-center justify-between text-slate-400 mb-1">
            <span className="text-xs uppercase font-medium tracking-wider">Architecture Violations</span>
            <ShieldAlert className="w-4 h-4 text-rose-400" />
          </div>
          <p className="text-xl font-bold text-rose-400 mt-1">{violations ? violations.length : 0}</p>
          <p className="text-[10px] text-slate-500 mt-1">Cycles and layer violations</p>
        </div>
      </div>

      {/* Real-Time Indexing Progress Stepper */}
      {(repo.indexingStatus === 'INDEXING' || indexStatus?.indexingStatus === 'INDEXING' || indexMutation.isPending) && (
        <div className="bg-gradient-to-r from-slate-900 via-cyan-950/40 to-slate-900 border border-cyan-500/30 rounded-2xl p-6 shadow-2xl relative overflow-hidden">
          <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-3 mb-4">
            <div className="flex items-center space-x-3">
              <div className="relative flex items-center justify-center">
                <div className="w-4 h-4 bg-cyan-400 rounded-full animate-ping opacity-75" />
                <div className="w-3.5 h-3.5 bg-cyan-500 rounded-full absolute" />
              </div>
              <div>
                <h3 className="text-xs font-bold text-white tracking-widest uppercase flex items-center space-x-2">
                  <span>Real-Time Ingestion Pipeline Active</span>
                  <span className="px-2 py-0.5 rounded-full bg-cyan-500/20 text-cyan-300 text-[10px] lowercase font-mono">
                    {indexStatus?.currentStage || 'running'}
                  </span>
                </h3>
                <p className="text-xs text-cyan-300 font-mono mt-1">
                  {indexStatus?.currentMessage || 'Processing repository in isolated background sandbox...'}
                </p>
              </div>
            </div>
            <div className="text-right shrink-0">
              <span className="text-2xl font-mono font-bold text-cyan-400">
                {indexStatus?.progressPercent || 15}%
              </span>
            </div>
          </div>

          {/* Progress Bar */}
          <div className="w-full bg-slate-950 rounded-full h-2 mb-5 overflow-hidden border border-slate-800">
            <div
              className="bg-gradient-to-r from-cyan-500 via-teal-400 to-emerald-400 h-2 rounded-full transition-all duration-700 ease-out"
              style={{ width: `${Math.max(indexStatus?.progressPercent || 15, 8)}%` }}
            />
          </div>

          {/* 5 Real-Time Pipeline Stages */}
          <div className="grid grid-cols-2 sm:grid-cols-3 lg:grid-cols-5 gap-2.5">
            {[
              { id: 'CLONING', label: '1. Git Workspace Clone', icon: FolderGit2, minPct: 10 },
              { id: 'SCANNING', label: '2. Source File Discovery', icon: FileCode, minPct: 30 },
              { id: 'PARSING', label: '3. AST & Symbol Engine', icon: Layers, minPct: 55 },
              { id: 'GRAPH_BUILDING', label: '4. JGraphT Multi-Graph', icon: GitBranch, minPct: 80 },
              { id: 'ANALYZING', label: '5. Metrics & Hotspots', icon: ShieldAlert, minPct: 92 },
            ].map((step) => {
              const currentPct = indexStatus?.progressPercent || 15;
              const isDone = currentPct > step.minPct;
              const isCurrent = currentPct >= step.minPct && (!isDone || currentPct < step.minPct + 25);
              const StepIcon = step.icon;

              return (
                <div
                  key={step.id}
                  className={`p-3 rounded-xl border transition flex items-center space-x-2 ${
                    isDone
                      ? 'bg-emerald-500/10 border-emerald-500/30 text-emerald-300'
                      : isCurrent
                      ? 'bg-cyan-500/20 border-cyan-500/50 text-cyan-200 ring-1 ring-cyan-500/30 animate-pulse'
                      : 'bg-slate-950/40 border-slate-800/60 text-slate-600'
                  }`}
                >
                  <StepIcon className={`w-4 h-4 shrink-0 ${isCurrent ? 'animate-bounce' : ''}`} />
                  <span className="text-[11px] font-medium truncate">{step.label}</span>
                </div>
              );
            })}
          </div>
        </div>
      )}

      {/* No analysable files banner */}
      {repo.indexingStatus === 'INDEXED' && graphStats?.graph && graphStats.graph.symbols === 0 && (
        <div className="bg-amber-500/10 border border-amber-500/30 rounded-2xl p-4 flex items-start space-x-3 text-amber-300">
          <AlertTriangle className="w-5 h-5 shrink-0 mt-0.5 text-amber-400" />
          <div className="text-xs space-y-1">
            <p className="font-semibold text-amber-200">No analysable source files found</p>
            <p className="text-amber-300/80">
              This repository has no files in the analysed languages (Java, Python, JavaScript/TypeScript, PHP, C#, Go,
              Ruby, HTML templates, CSS, SQL) — for example a profile README or documentation repository.
            </p>
          </div>
        </div>
      )}

      {/* Tabs navigation */}
      <div className="-mx-1 overflow-x-auto border-b border-slate-800" role="tablist" aria-label="Repository views">
        <div className="flex min-w-max gap-1 px-1">
          {([
            ['graph', 'Dependency Graph', Network, undefined],
            ['impact', 'Change Impact', GitCompare, undefined],
            ['prs', 'Pull Requests', GitPullRequest, undefined],
            ['reports', 'Quality Reports', FileCheck2, undefined],
            ['history', 'History', History, undefined],
            ['architecture', 'Architecture', ShieldAlert, violations?.length || 0],
            ['code', 'Code', FileCode, undefined],
            ['assistant', 'AI Assistant', Bot, undefined],
            ['jobs', 'Ingestion Jobs', Clock, jobs.length],
          ] as [Tab, string, React.ElementType, number | undefined][]).map(([key, label, Icon, count]) => {
            const active = activeTab === key;
            return (
              <button
                key={key}
                type="button"
                role="tab"
                aria-selected={active}
                onClick={() => setActiveTab(key)}
                className={`relative flex min-h-[44px] items-center gap-2 whitespace-nowrap px-3.5 text-sm font-medium transition-colors ${
                  active ? 'text-white' : 'text-slate-400 hover:text-slate-100'
                }`}
              >
                <Icon className={`h-4 w-4 ${active ? 'text-cyan-300' : 'text-slate-500'}`} aria-hidden />
                <span>{label}</span>
                {count !== undefined && (
                  <span className={`rounded-full px-1.5 py-px font-mono text-[10px] ${active ? 'bg-cyan-400/15 text-cyan-200' : 'bg-slate-800 text-slate-400'}`}>{count}</span>
                )}
                <span className={`absolute inset-x-2 -bottom-px h-0.5 rounded-full transition-colors ${active ? 'bg-cyan-400' : 'bg-transparent'}`} aria-hidden />
              </button>
            );
          })}
        </div>
      </div>

      {activeTab === 'graph' && (
        <div className="space-y-4">
          <DependencyGraphExplorer repositoryId={id!} />
        </div>
      )}

      {activeTab === 'impact' && <ChangeImpactPanel repositoryId={id!} />}
      {activeTab === 'history' && <div className="space-y-6"><HistoryPanel repositoryId={id!} /><DefectModelPanel repositoryId={id!} /></div>}
      {activeTab === 'prs' && <PullRequestsPanel repositoryId={id!} />}
      {activeTab === 'reports' && <ReportsPanel repositoryId={id!} defaultCommit={repo?.lastIndexedCommit ?? undefined} />}

      {/* Tab 2: Architecture digital twin (code city, hotspots, matrix, main sequence, trends, compare) + rules */}
      {activeTab === 'architecture' && (
        <ArchitectureTwinPanel repositoryId={id!} rules={(
        <div className="space-y-6">
          <RuleManager repositoryId={id!} />
          {graphStats?.referencesByResolution && (
            <div className="bg-slate-900 border border-slate-800 rounded-2xl p-6">
              <h2 className="text-base font-bold text-white mb-1">Analysis Coverage</h2>
              <p className="text-xs text-slate-400 mb-4">
                How each source-code reference was resolved by JavaParser&apos;s symbol solver. Only RESOLVED and
                PARTIAL references to code in this repository become graph edges; nothing is guessed.
              </p>
              <div className="grid grid-cols-2 md:grid-cols-4 gap-3 text-xs">
                {[
                  { key: 'RESOLVED', label: 'Resolved (internal)', cls: 'text-emerald-400', hint: 'Exact symbol in this repository' },
                  { key: 'PARTIAL', label: 'Partial', cls: 'text-amber-400', hint: 'Type known, exact member not (e.g. generated code)' },
                  { key: 'EXTERNAL', label: 'External', cls: 'text-sky-400', hint: 'JDK or library code (resolved against the project\'s dependency jars)' },
                  { key: 'UNRESOLVED', label: 'Unresolved', cls: 'text-rose-400', hint: 'Not resolvable (type unknown, e.g. a library that could not be resolved)' },
                ].map((r) => (
                  <div key={r.key} className="bg-slate-950 border border-slate-800 rounded-xl p-3">
                    <div className="text-slate-400">{r.label}</div>
                    <div className={`text-xl font-bold mt-1 ${r.cls}`}>{graphStats.referencesByResolution?.[r.key] ?? 0}</div>
                    <div className="text-[10px] text-slate-500 mt-1">{r.hint}</div>
                  </div>
                ))}
              </div>
              {graphStats.graph && (
                <p className="text-[11px] text-slate-500 mt-3">
                  {graphStats.graph.symbols} symbols · {graphStats.graph.types} types in {graphStats.graph.packages} packages ·{' '}
                  {graphStats.graph.memberEdges} member-level edges · {graphStats.graph.typeEdges} class-level edges ·{' '}
                  {graphStats.graph.cycleGroups} circular dependency group(s)
                </p>
              )}
              {graphStats.libraries && (
                <p className={`text-[11px] mt-2 ${graphStats.libraries.status === 'RESOLVED' ? 'text-slate-400' : graphStats.libraries.status === 'PARTIAL' || graphStats.libraries.status === 'FAILED' ? 'text-amber-300' : 'text-slate-500'}`}>
                  Libraries: {{
                    RESOLVED: `${graphStats.libraries.jarCount} dependency jars from the ${graphStats.libraries.buildSystem === 'GRADLE' ? 'Gradle' : 'Maven'} build were used to type library calls`,
                    PARTIAL: `${graphStats.libraries.jarCount} dependency jars used; some could not be resolved`,
                    FAILED: 'the build\'s dependencies could not be resolved, so library calls stay unresolved (re-index to retry)',
                    NO_BUILD_FILE: 'no pom.xml or Gradle build at the repository root, so library calls stay unresolved',
                    DISABLED: 'library resolution is switched off on this server',
                  }[graphStats.libraries.status]}
                  {graphStats.libraries.message && <span className="block text-slate-500 [overflow-wrap:anywhere]">{graphStats.libraries.message}</span>}
                </p>
              )}
            </div>
          )}

          {/* Violations List */}
          <div className="bg-slate-900 border border-slate-800 rounded-2xl p-6">
            <h2 className="text-base font-bold text-white mb-1">Architecture Boundary Violations</h2>
            <p className="text-xs text-slate-400 mb-4">
              Rule-based checks on the class-level dependency graph: circular dependencies (strongly connected
              components) and configurable layer rules. Test code is excluded from layer rules.
            </p>

            {violationsError ? (
              <div className="py-8 text-center text-rose-400 text-xs">
                <AlertTriangle className="w-6 h-6 mx-auto mb-2" />
                Could not load architecture violations.
              </div>
            ) : !violations ? (
              <div className="py-8 text-center text-slate-500 text-xs">Not analysed yet.</div>
            ) : violations.length === 0 ? (
              <div className="py-8 text-center text-slate-500 text-xs">
                <CheckCircle2 className="w-8 h-8 text-emerald-400 mx-auto mb-2" />
                <p className="font-semibold text-slate-300">Clean Architecture</p>
                <p className="mt-0.5">No circular dependencies or forbidden layer couplings detected.</p>
              </div>
            ) : (
              <div className="space-y-3">
                {violations.map((v) => (
                  <div key={v.id} className="bg-slate-950 p-4 rounded-xl border border-slate-800 flex items-start justify-between">
                    <div>
                      <div className="flex items-center space-x-2">
                        <span className={`text-[10px] font-bold px-2 py-0.5 rounded uppercase ${
                          v.severity === 'HIGH' ? 'bg-rose-950 text-rose-400 border border-rose-800/60' : 'bg-amber-950 text-amber-400 border border-amber-800/60'
                        }`}>
                          {v.severity}
                        </span>
                        <h4 className="font-bold text-white text-xs">{v.ruleName}</h4>
                      </div>
                      <p className="text-xs text-slate-300 mt-2 font-mono break-all">{v.description}</p>
                      {v.sourceLocation && (
                        <p className="text-[11px] text-slate-500 font-mono mt-1">Source: {v.sourceLocation}</p>
                      )}
                    </div>
                  </div>
                ))}
              </div>
            )}
          </div>

          {/* Hotspots & Metrics Table */}
          <div className="bg-slate-900 border border-slate-800 rounded-2xl p-6">
            <h2 className="text-base font-bold text-white mb-1">Coupling & Instability Metrics</h2>
            <p className="text-xs text-slate-400 mb-4">
              Robert C. Martin&apos;s instability I = Ce / (Ca + Ce), computed per class on the class-level graph.
              Ca = classes that depend on it, Ce = classes it depends on. Hotspot: Ca ≥ 5 or Ce ≥ 10.
            </p>

            {metricsError ? (
              <div className="py-8 text-center text-rose-400 text-xs">Could not load coupling metrics.</div>
            ) : !metrics || metrics.length === 0 ? (
              <div className="py-8 text-center text-slate-500 text-xs">No metrics calculated yet.</div>
            ) : (
              <div className="overflow-x-auto">
                <table className="w-full text-left text-xs font-mono">
                  <thead className="uppercase text-slate-500 border-b border-slate-800 pb-2">
                    <tr>
                      <th className="pb-3 font-semibold font-sans">Symbol</th>
                      <th className="pb-3 font-semibold font-sans">Type</th>
                      <th className="pb-3 font-semibold font-sans">Afferent (Ca)</th>
                      <th className="pb-3 font-semibold font-sans">Efferent (Ce)</th>
                      <th className="pb-3 font-semibold font-sans">Instability (I)</th>
                      <th className="pb-3 font-semibold font-sans">Hotspot</th>
                    </tr>
                  </thead>
                  <tbody className="divide-y divide-slate-800/60">
                    {metrics.slice(0, 15).map((m) => (
                      <tr key={m.symbolId} className="hover:bg-slate-850/50 transition">
                        <td className="py-3 text-slate-200 font-bold max-w-xs truncate" title={m.qualifiedName}>
                          {m.qualifiedName}
                        </td>
                        <td className="py-3 text-cyan-400">{m.symbolType}</td>
                        <td className="py-3 text-emerald-400 font-bold">{m.afferentCoupling}</td>
                        <td className="py-3 text-amber-400">{m.efferentCoupling}</td>
                        <td className="py-3 text-slate-300">{m.instability}</td>
                        <td className="py-3">
                          {m.hotspot ? (
                            <span className="px-2 py-0.5 rounded text-[10px] font-bold bg-amber-950/80 text-amber-400 border border-amber-800/60 flex items-center space-x-1 w-max">
                              <Flame className="w-3 h-3" />
                              <span>HOTSPOT</span>
                            </span>
                          ) : (
                            <span className="text-slate-500 font-sans">-</span>
                          )}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </div>
        </div>
        )} />
      )}

      {activeTab === 'code' && (
        <React.Suspense fallback={<p className="text-xs text-slate-500">Loading the code browser…</p>}>
          <CodeBrowser repositoryId={id!} path={codePath} line={codeLine} onNavigate={openCode} />
        </React.Suspense>
      )}

      {activeTab === 'assistant' && <AssistantPanel repositoryId={repo.id} />}

      {/* Tab 3: Background Jobs */}
      {activeTab === 'jobs' && (
        <div className="bg-slate-900 border border-slate-800 rounded-2xl p-6">
          <div className="flex items-center justify-between mb-4">
            <div>
              <h2 className="text-base font-bold text-white">RabbitMQ Job Execution History</h2>
              <p className="text-xs text-slate-400 mt-0.5">Asynchronous task executions for this repository</p>
            </div>
          </div>

          {jobsLoading ? (
            <div className="py-8 text-center text-slate-500 text-sm">Loading jobs history...</div>
          ) : jobs.length === 0 ? (
            <div className="py-8 text-center text-slate-500 text-sm">
              No background jobs executed yet. Click &quot;Start Indexing&quot; to queue an indexing job.
            </div>
          ) : (
            <div className="overflow-x-auto">
              <table className="w-full text-left text-sm">
                <thead className="text-xs uppercase text-slate-500 border-b border-slate-800 pb-2">
                  <tr>
                    <th className="pb-3 font-semibold">Job ID</th>
                    <th className="pb-3 font-semibold">Type</th>
                    <th className="pb-3 font-semibold">Status</th>
                    <th className="pb-3 font-semibold">Queued At</th>
                    <th className="pb-3 font-semibold">Duration</th>
                    <th className="pb-3 font-semibold">Result</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-slate-800/60 font-mono text-xs">
                  {jobs.map((job) => (
                    <tr key={job.id} className="hover:bg-slate-850/50 transition">
                      <td className="py-3 text-cyan-400">{job.id.substring(0, 8)}...</td>
                      <td className="py-3 text-slate-300 font-sans">{job.jobType}</td>
                      <td className="py-3">
                        <span
                          className={`inline-block px-2 py-0.5 rounded text-[10px] font-bold ${
                            job.status === 'COMPLETED'
                              ? 'bg-emerald-950/80 text-emerald-400 border border-emerald-800/60'
                              : job.status === 'RUNNING'
                              ? 'bg-amber-950/80 text-amber-400 border border-amber-800/60 animate-pulse'
                              : job.status === 'FAILED'
                              ? 'bg-rose-950/80 text-rose-400 border border-rose-800/60'
                              : 'bg-slate-800 text-slate-400'
                          }`}
                        >
                          {job.status}
                        </span>
                      </td>
                      <td className="py-3 text-slate-400">{new Date(job.queuedAt).toLocaleTimeString()}</td>
                      <td className="py-3 text-slate-400">{job.durationMs != null ? `${job.durationMs}ms` : '-'}</td>
                      <td className="py-3 text-slate-400 font-sans">
                        {job.errorMessage ? (
                          <span className="text-rose-400 flex items-center space-x-1">
                            <AlertTriangle className="w-3.5 h-3.5" />
                            <span>{job.errorMessage}</span>
                          </span>
                        ) : job.result ? (
                          <span className="text-slate-300">
                            {job.result.totalSymbols ? `${job.result.totalSymbols} symbols, ${job.result.totalDependencies} deps` : 'OK'}
                          </span>
                        ) : (
                          '-'
                        )}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </div>
      )}
    </div>
  );
};

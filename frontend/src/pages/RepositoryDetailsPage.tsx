import React, { useState } from 'react';
import { useParams, Link } from 'react-router-dom';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { repositoriesApi } from '../api/repositories';
import { codeApi } from '../api/code';
import {
  FolderGit2, Play, ArrowLeft, GitCommit, FileCode, Clock,
  CheckCircle2, AlertTriangle, ExternalLink, RefreshCw,
  Network, ShieldAlert, Activity, Flame, GitBranch, Layers
} from 'lucide-react';
import { StatusBadge } from './DashboardPage';
import { DependencyGraphExplorer } from '../components/graph/DependencyGraphExplorer';

export const RepositoryDetailsPage: React.FC = () => {
  const { id } = useParams<{ id: string }>();
  const [activeTab, setActiveTab] = useState<'graph' | 'architecture' | 'jobs'>('graph');
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

  const { data: violations } = useQuery({
    queryKey: ['architecture-violations', id],
    queryFn: () => codeApi.getArchitectureViolations(id!),
    enabled: !!id && repo?.indexingStatus === 'INDEXED',
  });

  const { data: metrics } = useQuery({
    queryKey: ['coupling-metrics', id],
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
  const hotspots = (metrics || []).filter((m) => m.isHotspot);

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
        <div className="flex items-start space-x-4">
          <div className="w-12 h-12 rounded-xl bg-cyan-500/10 border border-cyan-500/30 flex items-center justify-center text-cyan-400 shrink-0">
            <FolderGit2 className="w-6 h-6" />
          </div>
          <div>
            <div className="flex items-center space-x-3">
              <h1 className="text-2xl font-bold text-white tracking-tight">{repo.fullName}</h1>
              <StatusBadge status={repo.indexingStatus} />
            </div>
            {repo.description && <p className="text-sm text-slate-400 mt-1">{repo.description}</p>}
            <div className="flex flex-wrap items-center gap-4 text-xs text-slate-500 mt-3 font-mono">
              <span className="flex items-center space-x-1 text-slate-400">
                <FileCode className="w-3.5 h-3.5 text-amber-400" />
                <span>{repo.language || 'Java'}</span>
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
          <p className="text-xl font-bold text-white mt-1">{metrics ? metrics.length : '-'}</p>
          <p className="text-[10px] text-slate-500 mt-1">Classes, methods, records extracted</p>
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
              { id: 'SCANNING', label: '2. Java File Discovery', icon: FileCode, minPct: 30 },
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

      {/* Zero Java Files Banner */}
      {repo.indexingStatus === 'INDEXED' && metrics && metrics.length === 0 && (
        <div className="bg-amber-500/10 border border-amber-500/30 rounded-2xl p-4 flex items-start space-x-3 text-amber-300">
          <AlertTriangle className="w-5 h-5 shrink-0 mt-0.5 text-amber-400" />
          <div className="text-xs space-y-1">
            <p className="font-semibold text-amber-200">No Java Source Files Found</p>
            <p className="text-amber-300/80">
              This repository does not contain any <code className="bg-amber-950/60 px-1 py-0.5 rounded font-mono">.java</code> files (for example, it may be a profile README or documentation repository). ChangeImpact AI specializes in Java AST and dependency graph intelligence.
            </p>
          </div>
        </div>
      )}

      {/* Tabs navigation */}
      <div className="flex items-center space-x-2 border-b border-slate-800 pb-1">
        <button
          onClick={() => setActiveTab('graph')}
          className={`flex items-center space-x-2 px-4 py-2.5 rounded-lg text-xs font-semibold transition cursor-pointer ${
            activeTab === 'graph'
              ? 'bg-cyan-500/10 text-cyan-400 border border-cyan-500/20'
              : 'text-slate-400 hover:text-slate-200 hover:bg-slate-800/60'
          }`}
        >
          <Network className="w-4 h-4" />
          <span>Interactive Dependency Graph</span>
        </button>

        <button
          onClick={() => setActiveTab('architecture')}
          className={`flex items-center space-x-2 px-4 py-2.5 rounded-lg text-xs font-semibold transition cursor-pointer ${
            activeTab === 'architecture'
              ? 'bg-cyan-500/10 text-cyan-400 border border-cyan-500/20'
              : 'text-slate-400 hover:text-slate-200 hover:bg-slate-800/60'
          }`}
        >
          <ShieldAlert className="w-4 h-4" />
          <span>Architecture & Hotspots ({violations?.length || 0})</span>
        </button>

        <button
          onClick={() => setActiveTab('jobs')}
          className={`flex items-center space-x-2 px-4 py-2.5 rounded-lg text-xs font-semibold transition cursor-pointer ${
            activeTab === 'jobs'
              ? 'bg-cyan-500/10 text-cyan-400 border border-cyan-500/20'
              : 'text-slate-400 hover:text-slate-200 hover:bg-slate-800/60'
          }`}
        >
          <Clock className="w-4 h-4" />
          <span>Ingestion Jobs ({jobs.length})</span>
        </button>
      </div>

      {/* Tab 1: Dependency Graph */}
      {activeTab === 'graph' && (
        <div className="space-y-4">
          <DependencyGraphExplorer repositoryId={id!} />
        </div>
      )}

      {/* Tab 2: Architecture & Hotspots */}
      {activeTab === 'architecture' && (
        <div className="space-y-6">
          {/* Violations List */}
          <div className="bg-slate-900 border border-slate-800 rounded-2xl p-6">
            <h2 className="text-base font-bold text-white mb-1">Architecture Boundary Violations</h2>
            <p className="text-xs text-slate-400 mb-4">
              Structural violations detected using ArchUnit and custom graph rule validation.
            </p>

            {!violations || violations.length === 0 ? (
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
              Robert C. Martin&apos;s Instability metric $I = Ce / (Ca + Ce)$. Identifies components with high blast radius.
            </p>

            {!metrics || metrics.length === 0 ? (
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
                          {m.isHotspot ? (
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
      )}

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

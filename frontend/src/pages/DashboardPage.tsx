import React from 'react';
import { useQuery } from '@tanstack/react-query';
import { repositoriesApi } from '../api/repositories';
import { githubApi } from '../api/github';
import { Link } from 'react-router-dom';
import { FolderGit2, Github, CheckCircle2, Clock, PlayCircle, ArrowRight, Layers, FileCode } from 'lucide-react';

export const DashboardPage: React.FC = () => {
  const { data: reposData, isLoading: reposLoading } = useQuery({
    queryKey: ['repositories'],
    queryFn: () => repositoriesApi.list(0, 10),
  });

  const { data: accountsData } = useQuery({
    queryKey: ['github-accounts'],
    queryFn: () => githubApi.listAccounts(),
  });

  const repositories = reposData?.content || [];
  const accounts = accountsData || [];

  const indexedCount = repositories.filter((r) => r.indexingStatus === 'INDEXED').length;
  const indexingCount = repositories.filter((r) => r.indexingStatus === 'INDEXING').length;

  return (
    <div className="space-y-8">
      {/* Welcome banner */}
      <div className="bg-gradient-to-r from-slate-900 via-slate-900 to-cyan-950/40 border border-slate-800 rounded-2xl p-8 relative overflow-hidden">
        <div className="absolute right-0 top-0 bottom-0 w-1/3 bg-gradient-to-l from-cyan-500/5 to-transparent pointer-events-none" />
        <div className="max-w-2xl">
          <span className="text-xs font-semibold uppercase tracking-wider text-cyan-400 bg-cyan-950/60 border border-cyan-800 px-2.5 py-1 rounded-full">
            Phase 1 &bull; Backend Architecture & GitHub Ingestion
          </span>
          <h1 className="text-3xl font-extrabold text-white tracking-tight mt-3">
            Code Change Impact Analysis
          </h1>
          <p className="text-slate-400 text-sm mt-2 leading-relaxed">
            ChangeImpact AI connects to your GitHub repositories, parses Java source code using JavaParser and JGit,
            constructs deterministic symbol dependency graphs, and analyses change blast radiuses.
          </p>
          <div className="flex flex-wrap items-center gap-3 mt-6">
            <Link
              to="/repositories"
              className="px-4 py-2 bg-cyan-500 hover:bg-cyan-400 text-slate-950 font-semibold text-xs rounded-xl flex items-center space-x-2 transition shadow-lg shadow-cyan-500/20"
            >
              <FolderGit2 className="w-4 h-4" />
              <span>Connect Repository</span>
            </Link>
            <Link
              to="/github"
              className="px-4 py-2 bg-slate-800 hover:bg-slate-700 text-slate-200 text-xs font-medium rounded-xl border border-slate-700 flex items-center space-x-2 transition"
            >
              <Github className="w-4 h-4" />
              <span>Manage GitHub Accounts ({accounts.length})</span>
            </Link>
          </div>
        </div>
      </div>

      {/* Metrics Row */}
      <div className="grid grid-cols-1 md:grid-cols-4 gap-5">
        <div className="bg-slate-900 border border-slate-800 p-5 rounded-xl">
          <div className="flex items-center justify-between text-slate-400 mb-2">
            <span className="text-xs font-medium uppercase tracking-wider">Connected Repos</span>
            <FolderGit2 className="w-4 h-4 text-cyan-400" />
          </div>
          <p className="text-2xl font-bold text-white">{repositories.length}</p>
          <p className="text-xs text-slate-500 mt-1">Across all linked accounts</p>
        </div>

        <div className="bg-slate-900 border border-slate-800 p-5 rounded-xl">
          <div className="flex items-center justify-between text-slate-400 mb-2">
            <span className="text-xs font-medium uppercase tracking-wider">Indexed Repositories</span>
            <CheckCircle2 className="w-4 h-4 text-emerald-400" />
          </div>
          <p className="text-2xl font-bold text-white">{indexedCount}</p>
          <p className="text-xs text-slate-500 mt-1">Ready for static analysis</p>
        </div>

        <div className="bg-slate-900 border border-slate-800 p-5 rounded-xl">
          <div className="flex items-center justify-between text-slate-400 mb-2">
            <span className="text-xs font-medium uppercase tracking-wider">Active Indexing</span>
            <Clock className="w-4 h-4 text-amber-400" />
          </div>
          <p className="text-2xl font-bold text-white">{indexingCount}</p>
          <p className="text-xs text-slate-500 mt-1">Jobs currently in RabbitMQ</p>
        </div>

        <div className="bg-slate-900 border border-slate-800 p-5 rounded-xl">
          <div className="flex items-center justify-between text-slate-400 mb-2">
            <span className="text-xs font-medium uppercase tracking-wider">GitHub Accounts</span>
            <Github className="w-4 h-4 text-purple-400" />
          </div>
          <p className="text-2xl font-bold text-white">{accounts.length}</p>
          <p className="text-xs text-slate-500 mt-1">Active OAuth / PAT tokens</p>
        </div>
      </div>

      {/* Repositories Quick Table */}
      <div className="bg-slate-900 border border-slate-800 rounded-2xl p-6">
        <div className="flex items-center justify-between mb-6">
          <div>
            <h2 className="text-lg font-bold text-white">Connected Repositories</h2>
            <p className="text-xs text-slate-400 mt-0.5">Manage repositories and trigger JGit indexing pipelines</p>
          </div>
          <Link
            to="/repositories"
            className="text-xs font-semibold text-cyan-400 hover:text-cyan-300 flex items-center space-x-1"
          >
            <span>View All</span>
            <ArrowRight className="w-3.5 h-3.5" />
          </Link>
        </div>

        {reposLoading ? (
          <div className="py-12 text-center text-slate-500 text-sm">Loading repositories...</div>
        ) : repositories.length === 0 ? (
          <div className="py-12 text-center">
            <FolderGit2 className="w-12 h-12 text-slate-700 mx-auto mb-3" />
            <p className="text-sm font-medium text-slate-300">No repositories connected yet</p>
            <p className="text-xs text-slate-500 mt-1 mb-4">Connect your GitHub account to import a Java repository.</p>
            <Link
              to="/github"
              className="inline-flex items-center space-x-2 px-4 py-2 bg-cyan-500/10 text-cyan-400 border border-cyan-500/30 rounded-xl text-xs font-semibold hover:bg-cyan-500/20 transition"
            >
              <Github className="w-4 h-4" />
              <span>Connect GitHub</span>
            </Link>
          </div>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-left text-sm">
              <thead className="text-xs uppercase text-slate-500 border-b border-slate-800 pb-2">
                <tr>
                  <th className="pb-3 font-semibold">Repository</th>
                  <th className="pb-3 font-semibold">Language</th>
                  <th className="pb-3 font-semibold">Default Branch</th>
                  <th className="pb-3 font-semibold">Status</th>
                  <th className="pb-3 font-semibold">Last Indexed</th>
                  <th className="pb-3 font-semibold text-right">Action</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-800/60">
                {repositories.map((repo) => (
                  <tr key={repo.id} className="hover:bg-slate-850/50 transition">
                    <td className="py-4">
                      <Link to={`/repositories/${repo.id}`} className="font-semibold text-slate-200 hover:text-cyan-400 flex items-center space-x-2">
                        <FolderGit2 className="w-4 h-4 text-cyan-400" />
                        <span>{repo.fullName}</span>
                      </Link>
                    </td>
                    <td className="py-4 text-slate-400">
                      <span className="flex items-center space-x-1.5">
                        <FileCode className="w-3.5 h-3.5 text-amber-400" />
                        <span>{repo.language || 'Java'}</span>
                      </span>
                    </td>
                    <td className="py-4 font-mono text-xs text-slate-400">{repo.defaultBranch}</td>
                    <td className="py-4">
                      <StatusBadge status={repo.indexingStatus} />
                    </td>
                    <td className="py-4 text-xs text-slate-400">
                      {repo.lastIndexedAt ? new Date(repo.lastIndexedAt).toLocaleDateString() : 'Never'}
                    </td>
                    <td className="py-4 text-right">
                      <Link
                        to={`/repositories/${repo.id}`}
                        className="px-3 py-1.5 bg-slate-800 hover:bg-slate-700 text-slate-200 text-xs rounded-lg border border-slate-700 inline-flex items-center space-x-1.5 transition"
                      >
                        <Layers className="w-3.5 h-3.5" />
                        <span>Inspect</span>
                      </Link>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>
    </div>
  );
};

export const StatusBadge: React.FC<{ status: string }> = ({ status }) => {
  switch (status) {
    case 'INDEXED':
      return (
        <span className="inline-flex items-center space-x-1 px-2.5 py-0.5 rounded-full text-xs font-medium bg-emerald-950/60 text-emerald-400 border border-emerald-800/50">
          <CheckCircle2 className="w-3 h-3" />
          <span>INDEXED</span>
        </span>
      );
    case 'INDEXING':
      return (
        <span className="inline-flex items-center space-x-1 px-2.5 py-0.5 rounded-full text-xs font-medium bg-amber-950/60 text-amber-400 border border-amber-800/50 animate-pulse">
          <Clock className="w-3 h-3" />
          <span>INDEXING</span>
        </span>
      );
    case 'FAILED':
      return (
        <span className="inline-flex items-center space-x-1 px-2.5 py-0.5 rounded-full text-xs font-medium bg-rose-950/60 text-rose-400 border border-rose-800/50">
          <span>FAILED</span>
        </span>
      );
    default:
      return (
        <span className="inline-flex items-center space-x-1 px-2.5 py-0.5 rounded-full text-xs font-medium bg-slate-800 text-slate-400 border border-slate-700">
          <PlayCircle className="w-3 h-3" />
          <span>NOT INDEXED</span>
        </span>
      );
  }
};


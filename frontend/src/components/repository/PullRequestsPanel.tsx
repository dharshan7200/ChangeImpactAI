import React from 'react';
import { Link } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { CheckCircle2, CircleDashed, ExternalLink, GitPullRequest, XCircle } from 'lucide-react';
import { githubAppApi } from '../../api/phase5';
import { RiskBadge, shortSha } from '../analysis/risk';

const CHECK_STATUS: Record<string, string> = {
  QUEUED: 'check queued',
  IN_PROGRESS: 'check running',
  COMPLETED: 'check published',
  FAILED_TO_PUBLISH: 'check could not be published',
  NOT_CONFIGURED: 'Check Runs not configured',
  PUBLISHED_BY_OTHER_COPY: 'check published by another connection',
};

export const ConclusionChip: React.FC<{ conclusion?: string; status?: string }> = ({ conclusion, status }) => {
  if (conclusion === 'success') return <span className="inline-flex items-center gap-1 text-[11px] text-emerald-300"><CheckCircle2 className="w-3 h-3" /> success</span>;
  if (conclusion === 'failure') return <span className="inline-flex items-center gap-1 text-[11px] text-rose-300 font-semibold"><XCircle className="w-3 h-3" /> failure</span>;
  if (conclusion) return <span className="inline-flex items-center gap-1 text-[11px] text-slate-300"><CircleDashed className="w-3 h-3" /> {conclusion}</span>;
  return <span className="text-[11px] text-slate-500">{status ? CHECK_STATUS[status] ?? status : '—'}</span>;
};

export const PullRequestsPanel: React.FC<{ repositoryId: string }> = ({ repositoryId }) => {
  const { data: prs = [], isLoading } = useQuery({
    queryKey: ['pull-requests', repositoryId],
    queryFn: () => githubAppApi.pullRequests(repositoryId),
    refetchInterval: 10000,
  });

  return (
    <div className="bg-slate-900 border border-slate-800 rounded-2xl p-6">
      <h2 className="text-base font-bold text-white mb-1 flex items-center gap-2"><GitPullRequest className="w-4 h-4 text-violet-400" /> Pull requests</h2>
      <p className="text-xs text-slate-400 mb-4">
        Pull requests reported by the GitHub App. Each push to an open pull request is analysed (merge-base → head) and,
        when the App is configured, published as a Check Run.
      </p>
      {isLoading ? (
        <p className="text-xs text-slate-500">Loading…</p>
      ) : prs.length === 0 ? (
        <p className="text-xs text-slate-500">
          No pull requests received yet. Install the GitHub App on this repository — see <Link to="/github" className="text-cyan-400 hover:underline">GitHub</Link> for setup.
        </p>
      ) : (
        <table className="w-full text-xs">
          <thead className="text-[10px] uppercase tracking-wider text-slate-500 border-b border-slate-800">
            <tr><th className="text-left py-2">Pull request</th><th className="text-left py-2">State</th><th className="text-left py-2">Head</th>
                <th className="text-left py-2">Risk</th><th className="text-left py-2">Check run</th><th className="py-2" /></tr>
          </thead>
          <tbody className="divide-y divide-slate-800/70">
            {prs.map((pr) => (
              <tr key={pr.id}>
                <td className="py-2.5 pr-3">
                  <div className="text-slate-100">#{pr.number} {pr.title}</div>
                  <div className="text-[11px] text-slate-500">{pr.author} · {pr.headRef} → {pr.baseRef}</div>
                </td>
                <td className="py-2.5 pr-3 text-slate-300">{pr.state}{pr.draft ? ' (draft)' : ''}</td>
                <td className="py-2.5 pr-3 font-mono text-slate-400">{shortSha(pr.headSha)}</td>
                <td className="py-2.5 pr-3"><RiskBadge level={pr.riskLevel} score={pr.riskScore} /></td>
                <td className="py-2.5 pr-3" title={pr.checkError ?? undefined}>
                  <ConclusionChip conclusion={pr.checkConclusion} status={pr.checkStatus} />
                  {pr.checkError && <div className="text-[10px] text-rose-400 truncate max-w-[220px]">{pr.checkError}</div>}
                </td>
                <td className="py-2.5 text-right whitespace-nowrap">
                  {pr.lastAnalysisId && <Link to={`/analyses/${pr.lastAnalysisId}`} className="text-cyan-400 hover:underline mr-3">analysis</Link>}
                  {pr.url && <a href={pr.url} target="_blank" rel="noreferrer" className="text-slate-400 hover:text-slate-200 inline-flex items-center gap-1">GitHub <ExternalLink className="w-3 h-3" /></a>}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </div>
  );
};

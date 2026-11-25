import React, { useEffect, useMemo, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { useMutation, useQuery } from '@tanstack/react-query';
import { GitCompare, Play, Clock, AlertTriangle, ArrowRight, Bug } from 'lucide-react';
import { analysesApi } from '../../api/analyses';
import { RiskBadge, shortSha } from './risk';
import { AnalysisTimeline } from './AnalysisTimeline';

const RELATIONSHIPS = ['METHOD_CALL', 'CONSTRUCTOR_CALL', 'FIELD_ACCESS', 'EXTENDS', 'IMPLEMENTS',
  'PARAMETER_TYPE', 'RETURN_TYPE', 'FIELD_TYPE', 'IMPORT'];

/** Repository tab: pick two commits, start an analysis, and see previous analyses. */
export const ChangeImpactPanel: React.FC<{ repositoryId: string }> = ({ repositoryId }) => {
  const navigate = useNavigate();
  const { data: commitsPage, isLoading: commitsLoading } = useQuery({
    queryKey: ['commits', repositoryId],
    queryFn: () => analysesApi.commits(repositoryId, 200),
  });
  const { data: analyses } = useQuery({
    queryKey: ['analyses', repositoryId],
    queryFn: () => analysesApi.list(repositoryId),
    refetchInterval: (q) => (q.state.data?.content.some((a) => a.status === 'QUEUED' || a.status === 'RUNNING') ? 2000 : false),
  });

  const commits = useMemo(() => commitsPage?.content ?? [], [commitsPage]);
  const [baseRef, setBaseRef] = useState('');
  const [headRef, setHeadRef] = useState('');
  const [maxDepth, setMaxDepth] = useState(3);
  const [useMergeBase, setUseMergeBase] = useState(false);
  const [types, setTypes] = useState<string[]>([]);

  // Sensible default: compare the latest commit with its predecessor.
  useEffect(() => {
    if (commits.length >= 2 && !baseRef && !headRef) {
      setHeadRef(commits[0].sha);
      setBaseRef(commits[1].sha);
    }
  }, [commits, baseRef, headRef]);

  const create = useMutation({
    mutationFn: () =>
      analysesApi.create({ repositoryId, baseRef: baseRef.trim(), headRef: headRef.trim(), maxDepth, useMergeBase, relationshipTypes: types }),
    onSuccess: (res) => navigate(`/analyses/${res.analysisId}`),
  });
  const errorMessage = (create.error as any)?.response?.data?.message ?? (create.error as Error | null)?.message;

  const commitLabel = (sha: string) => {
    const c = commits.find((x) => x.sha === sha);
    return c ? `${c.shortSha} · ${c.message}` : sha;
  };

  return (
    <div className="space-y-6">
      <AnalysisTimeline repositoryId={repositoryId} />
      <div className="bg-slate-900 border border-slate-800 rounded-2xl p-6">
        <div className="flex items-center gap-2 mb-1">
          <GitCompare className="w-4 h-4 text-cyan-400" />
          <h2 className="text-base font-bold text-white">Compare two commits</h2>
        </div>
        <p className="text-xs text-slate-400 mb-5">
          Detects semantic changes between the commits, propagates them through the dependency graph and scores the risk.
          Use a commit SHA, branch or tag. Commits listed here come from the indexed history.
        </p>

        <datalist id="commit-refs">
          {commits.map((c) => (
            <option key={c.sha} value={c.sha}>
              {c.shortSha} {c.message}
            </option>
          ))}
        </datalist>

        <div className="grid grid-cols-1 md:grid-cols-[1fr_auto_1fr] gap-3 items-end">
          <RefPicker label="Base (before)" value={baseRef} onChange={setBaseRef} commits={commits} loading={commitsLoading} />
          <ArrowRight className="hidden md:block w-5 h-5 text-slate-600 mb-2.5" />
          <RefPicker label="Head (after)" value={headRef} onChange={setHeadRef} commits={commits} loading={commitsLoading} />
        </div>
        {(baseRef || headRef) && (
          <p className="text-[11px] text-slate-500 mt-2 font-mono truncate">
            {commitLabel(baseRef)} → {commitLabel(headRef)}
          </p>
        )}

        <div className="flex flex-wrap items-center gap-4 mt-5 text-xs text-slate-300">
          <label className="flex items-center gap-2">
            Traversal depth
            <select
              value={maxDepth}
              onChange={(e) => setMaxDepth(Number(e.target.value))}
              className="bg-slate-950 border border-slate-800 rounded-lg px-2 py-1.5"
            >
              {[1, 2, 3, 4, 5, 6].map((d) => (
                <option key={d} value={d}>
                  {d} hop{d > 1 ? 's' : ''}
                </option>
              ))}
            </select>
          </label>
          <label className="flex items-center gap-2 cursor-pointer" title="Compare from the common ancestor (pull-request semantics)">
            <input type="checkbox" checked={useMergeBase} onChange={(e) => setUseMergeBase(e.target.checked)} />
            Use merge-base
          </label>
          <details className="text-slate-400">
            <summary className="cursor-pointer select-none">Relationship types ({types.length === 0 ? 'all' : types.length})</summary>
            <div className="flex flex-wrap gap-1.5 mt-2">
              {RELATIONSHIPS.map((t) => (
                <label key={t} className="flex items-center gap-1 bg-slate-950 border border-slate-800 rounded-md px-2 py-1">
                  <input
                    type="checkbox"
                    checked={types.includes(t)}
                    onChange={(e) => setTypes((prev) => (e.target.checked ? [...prev, t] : prev.filter((x) => x !== t)))}
                  />
                  {t.toLowerCase().replace(/_/g, ' ')}
                </label>
              ))}
            </div>
          </details>
          <button
            type="button"
            disabled={!baseRef || !headRef || create.isPending}
            onClick={() => create.mutate()}
            className="ml-auto px-4 py-2 bg-cyan-500 hover:bg-cyan-400 text-slate-950 font-semibold rounded-xl flex items-center gap-2 disabled:opacity-40 cursor-pointer"
          >
            <Play className="w-4 h-4" />
            {create.isPending ? 'Queuing…' : 'Analyse change impact'}
          </button>
        </div>
        {errorMessage && (
          <p className="mt-3 text-xs text-rose-400 flex items-center gap-1.5">
            <AlertTriangle className="w-3.5 h-3.5" /> {errorMessage}
          </p>
        )}
      </div>

      <div className="bg-slate-900 border border-slate-800 rounded-2xl p-6">
        <h2 className="text-base font-bold text-white mb-4">Previous analyses</h2>
        {!analyses || analyses.content.length === 0 ? (
          <p className="text-xs text-slate-500 py-6 text-center">No analyses yet.</p>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-xs">
              <thead className="text-slate-500 uppercase text-[10px] tracking-wider border-b border-slate-800">
                <tr>
                  <th className="text-left py-2 font-semibold">Compared</th>
                  <th className="text-left py-2 font-semibold">Status</th>
                  <th className="text-left py-2 font-semibold">Risk</th>
                  <th className="text-right py-2 font-semibold">Symbols changed</th>
                  <th className="text-right py-2 font-semibold">Impacted</th>
                  <th className="text-right py-2 font-semibold">Created</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-800/60">
                {analyses.content.map((a) => (
                  <tr key={a.id} className="hover:bg-slate-800/40">
                    <td className="py-2.5">
                      <Link to={`/analyses/${a.id}`} className="font-mono text-cyan-400 hover:text-cyan-300">
                        {shortSha(a.baseCommit ?? a.baseRef)} → {shortSha(a.headCommit ?? a.headRef)}
                      </Link>
                    </td>
                    <td className="py-2.5 text-slate-300">
                      {a.status === 'RUNNING' || a.status === 'QUEUED' ? (
                        <span className="flex items-center gap-1 text-cyan-300">
                          <Clock className="w-3 h-3" /> {a.stage ?? a.status} {a.progressPercent ?? 0}%
                        </span>
                      ) : a.status === 'FAILED' ? (
                        <span className="text-rose-400" title={a.errorMessage}>FAILED</span>
                      ) : (
                        a.status
                      )}
                    </td>
                    <td className="py-2.5">
                      <RiskBadge level={a.riskLevel} score={a.riskScore} />
                    </td>
                    <td className="py-2.5 text-right text-slate-300 font-mono">{a.summary?.productionSymbolsChanged ?? '—'}</td>
                    <td className="py-2.5 text-right text-slate-300 font-mono">{a.summary?.totalImpacted ?? '—'}</td>
                    <td className="py-2.5 text-right text-slate-500">{new Date(a.createdAt).toLocaleString()}</td>
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

const RefPicker: React.FC<{
  label: string;
  value: string;
  onChange: (v: string) => void;
  commits: { sha: string; shortSha: string; message: string; bugfix: boolean }[];
  loading: boolean;
}> = ({ label, value, onChange, commits, loading }) => (
  <div className="min-w-0">
    <label className="text-[11px] uppercase tracking-wider text-slate-500 font-semibold">{label}</label>
    <div className="flex gap-2 mt-1">
      <select
        value={commits.some((c) => c.sha === value) ? value : ''}
        onChange={(e) => onChange(e.target.value)}
        className="flex-1 min-w-0 bg-slate-950 border border-slate-800 rounded-xl px-2.5 py-2 text-xs text-slate-200 focus:outline-none focus:border-cyan-500"
      >
        <option value="">{loading ? 'Loading commits…' : 'Choose a commit…'}</option>
        {commits.map((c) => (
          <option key={c.sha} value={c.sha}>
            {c.shortSha} {c.bugfix ? '[fix] ' : ''}
            {c.message.length > 70 ? c.message.substring(0, 70) + '…' : c.message}
          </option>
        ))}
      </select>
      <input
        list="commit-refs"
        value={value}
        onChange={(e) => onChange(e.target.value)}
        placeholder="sha / branch / tag"
        className="w-32 shrink-0 bg-slate-950 border border-slate-800 rounded-xl px-2.5 py-2 text-xs font-mono text-slate-200 placeholder-slate-600 focus:outline-none focus:border-cyan-500"
      />
    </div>
    {commits.find((c) => c.sha === value)?.bugfix && (
      <p className="text-[10px] text-slate-500 mt-1 flex items-center gap-1">
        <Bug className="w-3 h-3" /> message suggests a bug fix (heuristic label)
      </p>
    )}
  </div>
);

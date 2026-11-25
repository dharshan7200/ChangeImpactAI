import React, { useState } from 'react';
import { Link } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { CartesianGrid, Line, LineChart, ReferenceLine, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts';
import {
  Activity, CheckCircle2, Clock, FolderGit2, GitPullRequest, PlayCircle, ShieldAlert, ShieldHalf, Table2, TrendingUp,
} from 'lucide-react';
import { dashboardApi } from '../api/phase5';
import type { TeamDashboard } from '../types/phase5';
import { RiskBadge, shortSha } from '../components/analysis/risk';
import { ConclusionChip } from '../components/repository/PullRequestsPanel';
import { SeverityBadge } from '../components/analysis/SecurityPanel';

const SERIES = '#3987e5'; // categorical slot 1 (dark surface)

export const DashboardPage: React.FC = () => {
  const [days, setDays] = useState(30);
  const { data, isLoading } = useQuery({ queryKey: ['team-dashboard', days], queryFn: () => dashboardApi.team(days), refetchInterval: 30000 });

  return (
    <div className="space-y-6">
      <div className="flex flex-wrap items-end justify-between gap-3">
        <div>
          <h1 className="text-2xl font-extrabold text-white tracking-tight">Team dashboard</h1>
          <p className="text-xs text-slate-400 mt-1">Change risk, pull requests, quality and security across your repositories.</p>
        </div>
        <div className="flex gap-1 text-xs" role="group" aria-label="Time window">
          {[7, 30, 90].map((d) => (
            <button key={d} type="button" onClick={() => setDays(d)}
                    className={`px-3 py-1.5 rounded-lg border cursor-pointer ${days === d ? 'bg-slate-800 border-slate-600 text-white' : 'border-slate-800 text-slate-400 hover:text-slate-200'}`}>
              {d} days
            </button>
          ))}
        </div>
      </div>

      {isLoading || !data ? (
        <div className="py-20 text-center text-slate-500 text-sm">Loading dashboard…</div>
      ) : data.repositories.length === 0 ? (
        <EmptyState />
      ) : (
        <>
          <div className="grid grid-cols-2 md:grid-cols-3 xl:grid-cols-5 gap-3">
            <Kpi label="Open pull requests" value={data.totals.openPullRequests} hint={`${data.totals.openHighRiskPullRequests} at HIGH risk or above`}
                 emphasis={data.totals.openHighRiskPullRequests > 0} />
            <Kpi label="High-risk changes" value={data.totals.highRiskChanges} hint={`of ${data.totals.distinctChanges} distinct changes (${data.totals.analyses} runs) in ${days} days`} />
            <Kpi label="Failed analyses" value={data.totals.failedAnalyses} hint="could not be computed" />
            <Kpi label="High/critical security" value={data.totals.securityFindingsHighOrCritical} hint="findings in analysed changes"
                 emphasis={data.totals.securityFindingsHighOrCritical > 0} />
            <Kpi label="Repositories indexed" value={`${data.totals.indexedRepositories}/${data.totals.repositories}`} hint="current dependency graph" />
          </div>

          <RiskTrend data={data} />

          <div className="grid grid-cols-1 xl:grid-cols-2 gap-4">
            <Panel title="Open pull requests" icon={<GitPullRequest className="w-4 h-4 text-violet-400" />} subtitle="Riskiest first; from GitHub App webhooks">
              {data.openPullRequests.length === 0 ? <Muted>No open pull requests reported by the GitHub App.</Muted> : (
                <table className="w-full text-xs">
                  <tbody className="divide-y divide-slate-800/70">
                    {data.openPullRequests.map((p) => (
                      <tr key={p.repositoryId + p.number}>
                        <td className="py-2 pr-3">
                          <div className="text-slate-100 truncate max-w-[280px]" title={p.title}>#{p.number} {p.title}</div>
                          <div className="text-[11px] text-slate-500">{p.repository} · {p.author}{p.draft ? ' · draft' : ''}</div>
                        </td>
                        <td className="py-2 pr-3"><RiskBadge level={p.riskLevel} score={p.riskScore} /></td>
                        <td className="py-2 pr-3"><ConclusionChip conclusion={p.checkConclusion} status={p.checkStatus} /></td>
                        <td className="py-2 text-right">{p.analysisId && <Link to={`/analyses/${p.analysisId}`} className="text-cyan-400 hover:underline">open</Link>}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              )}
            </Panel>
            <Panel title="High-risk changes" icon={<ShieldAlert className="w-4 h-4 text-orange-400" />} subtitle={`HIGH or CRITICAL analyses in the last ${days} days`}>
              {data.highRiskChanges.length === 0 ? <Muted>No high-risk changes in this window.</Muted> : (
                <table className="w-full text-xs">
                  <tbody className="divide-y divide-slate-800/70">
                    {data.highRiskChanges.map((a) => (
                      <tr key={a.analysisId}>
                        <td className="py-2 pr-3">
                          <Link to={`/analyses/${a.analysisId}`} className="font-mono text-slate-100 hover:text-cyan-300">
                            {shortSha(a.baseRef)} → {shortSha(a.headRef)}
                          </Link>
                          <div className="text-[11px] text-slate-500">
                            {a.repository}{a.pullRequestNumber != null ? ` · PR #${a.pullRequestNumber}` : ''} · {new Date(a.createdAt).toLocaleDateString()}
                          </div>
                        </td>
                        <td className="py-2 text-right"><RiskBadge level={a.riskLevel} score={a.riskScore} /></td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              )}
            </Panel>
          </div>

          <Panel title="Security findings" icon={<ShieldHalf className="w-4 h-4 text-rose-400" />} subtitle={`In changes analysed in the last ${days} days`}>
            <div className="flex flex-wrap gap-3 mb-3">
              {['CRITICAL', 'HIGH', 'MEDIUM', 'LOW', 'INFO'].filter((s) => data.security.bySeverity[s]).map((s) => (
                <span key={s} className="inline-flex items-center gap-1.5 text-xs"><SeverityBadge severity={s} /><span className="font-mono text-slate-300">{data.security.bySeverity[s]}</span></span>
              ))}
              {Object.keys(data.security.bySeverity).length === 0 && <Muted>No security findings.</Muted>}
            </div>
            {data.security.top.length > 0 && (
              <ul className="divide-y divide-slate-800/70">
                {data.security.top.map((f, i) => (
                  <li key={i} className="py-2 flex items-start gap-3 text-xs">
                    <SeverityBadge severity={f.severity} />
                    <div className="min-w-0 flex-1">
                      <div className="text-slate-100 [overflow-wrap:anywhere]">{f.title}</div>
                      <div className="text-[11px] text-slate-500 [overflow-wrap:anywhere]">{f.repository} · {f.source}{f.location ? ` · ${f.location}` : ''}</div>
                    </div>
                    <Link to={`/analyses/${f.analysisId}`} className="text-cyan-400 hover:underline shrink-0">open</Link>
                  </li>
                ))}
              </ul>
            )}
          </Panel>

          <Panel title="Repository health" icon={<Activity className="w-4 h-4 text-cyan-400" />} subtitle="Latest index and the latest uploaded CI report of each kind">
            <div className="overflow-x-auto">
              <table className="w-full text-xs min-w-[760px]">
                <thead className="text-[10px] uppercase tracking-wider text-slate-500 border-b border-slate-800">
                  <tr>
                    <th className="text-left py-2">Repository</th><th className="text-left py-2">Index</th>
                    <th className="text-right py-2">Arch. violations</th><th className="text-right py-2">Line coverage</th>
                    <th className="text-right py-2">Tests</th><th className="text-right py-2">Mutation score</th>
                    <th className="text-right py-2">Known vulns</th><th className="text-right py-2">Last analysis</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-slate-800/70">
                  {data.repositories.map((r) => {
                    const tests = r.tests?.value ?? null;
                    const vulns = r.knownVulnerabilities?.value ?? null;
                    const vulnTotal = vulns ? Object.values(vulns).reduce((a, b) => a + b, 0) : null;
                    return (
                      <tr key={r.repositoryId}>
                        <td className="py-2 pr-3"><Link to={`/repositories/${r.repositoryId}`} className="text-slate-100 hover:text-cyan-300">{r.fullName}</Link></td>
                        <td className="py-2 pr-3"><StatusBadge status={r.indexingStatus} /></td>
                        <td className="py-2 pr-3 text-right font-mono text-slate-300">{r.architectureViolations ?? '—'}</td>
                        <td className="py-2 pr-3 text-right font-mono text-slate-300" title={r.coverage ? `commit ${shortSha(r.coverage.commitSha)}` : undefined}>
                          {r.coverage?.value != null ? `${r.coverage.value}%` : '—'}
                        </td>
                        <td className="py-2 pr-3 text-right font-mono" title={r.tests ? `commit ${shortSha(r.tests.commitSha)}` : undefined}>
                          {tests ? <span className={(tests.FAILED ?? 0) > 0 ? 'text-rose-300' : 'text-slate-300'}>{tests.PASSED ?? 0} ✓ {tests.FAILED ? `${tests.FAILED} ✗` : ''}</span> : <span className="text-slate-500">—</span>}
                        </td>
                        <td className="py-2 pr-3 text-right font-mono text-slate-300">{r.mutationScore?.value != null ? `${r.mutationScore.value}%` : '—'}</td>
                        <td className="py-2 pr-3 text-right font-mono" title={vulns ? Object.entries(vulns).map(([k, v]) => `${v} ${k}`).join(', ') : undefined}>
                          {vulnTotal != null ? <span className={(vulns!.CRITICAL ?? 0) + (vulns!.HIGH ?? 0) > 0 ? 'text-rose-300' : 'text-slate-300'}>{vulnTotal}</span> : <span className="text-slate-500">—</span>}
                        </td>
                        <td className="py-2 text-right">{r.lastAnalysis ? <Link to={`/analyses/${r.lastAnalysis.analysisId}`}><RiskBadge level={r.lastAnalysis.riskLevel} score={r.lastAnalysis.riskScore} /></Link> : <span className="text-slate-500">—</span>}</td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>
          </Panel>

          <Panel title="Execution history" icon={<Clock className="w-4 h-4 text-slate-400" />} subtitle="Latest indexing and analysis jobs">
            <div className="overflow-x-auto">
            <table className="w-full text-xs">
              <tbody className="divide-y divide-slate-800/70">
                {data.executionHistory.map((j) => (
                  <tr key={j.jobId}>
                    <td className="py-1.5 pr-3 text-slate-500 whitespace-nowrap">{new Date(j.createdAt).toLocaleString()}</td>
                    <td className="py-1.5 pr-3 text-slate-300">{j.type === 'CHANGE_ANALYSIS' ? 'change analysis' : j.type.toLowerCase().replace(/_/g, ' ')}</td>
                    <td className="py-1.5 pr-3 text-slate-400">{j.repository}</td>
                    <td className={`py-1.5 pr-3 font-semibold ${j.status === 'FAILED' ? 'text-rose-300' : j.status === 'COMPLETED' ? 'text-emerald-300' : 'text-amber-300'}`}>{j.status}</td>
                    <td className="py-1.5 text-right font-mono text-slate-500">{j.durationMs != null ? `${(j.durationMs / 1000).toFixed(1)} s` : ''}</td>
                  </tr>
                ))}
              </tbody>
            </table>
            </div>
          </Panel>
          <p className="text-[11px] text-slate-500">{data.note}</p>
        </>
      )}
    </div>
  );
};

// ---------------------------------------------------------------------------

const RiskTrend: React.FC<{ data: TeamDashboard }> = ({ data }) => {
  const [table, setTable] = useState(false);
  const points = data.riskTrend.map((p) => ({ ...p, label: p.date.slice(5) }));
  const any = points.some((p) => p.maxScore != null);
  return (
    <Panel
      title="Risk trend — highest change risk per day"
      icon={<TrendingUp className="w-4 h-4 text-cyan-400" />}
      subtitle="Heuristic score 0–100 of the riskiest analysed change each day; gaps are days without analyses"
      action={
        <button type="button" onClick={() => setTable(!table)} className="text-[11px] text-slate-400 hover:text-slate-200 inline-flex items-center gap-1 cursor-pointer">
          <Table2 className="w-3.5 h-3.5" /> {table ? 'Chart' : 'Table'}
        </button>
      }
    >
      {!any ? <Muted>No completed analyses in this window.</Muted> : table ? (
        <div className="max-h-64 overflow-y-auto">
          <table className="w-full text-xs">
            <thead className="text-[10px] uppercase tracking-wider text-slate-500"><tr><th className="text-left py-1">Date</th><th className="text-right">Analyses</th><th className="text-right">Highest</th><th className="text-right">Average</th><th className="text-right">HIGH+</th></tr></thead>
            <tbody className="divide-y divide-slate-800/70">
              {points.filter((p) => p.analyses > 0).map((p) => (
                <tr key={p.date}><td className="py-1 text-slate-300">{p.date}</td><td className="text-right font-mono">{p.analyses}</td>
                  <td className="text-right font-mono">{p.maxScore}</td><td className="text-right font-mono">{p.avgScore}</td><td className="text-right font-mono">{p.high}</td></tr>
              ))}
            </tbody>
          </table>
        </div>
      ) : (
        <div className="h-56">
          <ResponsiveContainer width="100%" height="100%">
            <LineChart data={points} margin={{ top: 8, right: 64, bottom: 0, left: -16 }}>
              <CartesianGrid stroke="#334155" strokeOpacity={0.35} vertical={false} />
              <XAxis dataKey="label" tick={{ fill: '#94a3b8', fontSize: 10 }} tickLine={false} axisLine={{ stroke: '#334155' }} interval="preserveStartEnd" minTickGap={24} />
              <YAxis domain={[0, 100]} ticks={[0, 25, 50, 75, 100]} tick={{ fill: '#94a3b8', fontSize: 10 }} tickLine={false} axisLine={false} />
              {[{ y: 25, l: 'MEDIUM' }, { y: 50, l: 'HIGH' }, { y: 75, l: 'CRITICAL' }].map((t) => (
                <ReferenceLine key={t.y} y={t.y} stroke="#64748b" strokeDasharray="3 4" strokeOpacity={0.6}
                               label={{ value: t.l, position: 'right', fill: '#94a3b8', fontSize: 10 }} />
              ))}
              <Tooltip
                cursor={{ stroke: '#64748b', strokeWidth: 1 }}
                content={({ active, payload }) => {
                  const p = active && payload?.length ? (payload[0].payload as (typeof points)[number]) : null;
                  if (!p || p.analyses === 0) return null;
                  return (
                    <div className="bg-slate-950 border border-slate-700 rounded-lg px-3 py-2 text-[11px] text-slate-300 shadow-xl">
                      <div className="font-semibold text-slate-100">{p.date}</div>
                      <div>Highest risk <span className="font-mono text-white">{p.maxScore}</span> · average <span className="font-mono">{p.avgScore}</span></div>
                      <div>{p.analyses} analysis(es), {p.high} HIGH or above</div>
                    </div>
                  );
                }}
              />
              <Line type="monotone" dataKey="maxScore" stroke={SERIES} strokeWidth={2} connectNulls={false} isAnimationActive={false}
                    dot={{ r: 4, fill: SERIES, stroke: '#0f172a', strokeWidth: 2 }} activeDot={{ r: 6, stroke: '#0f172a', strokeWidth: 2 }} />
            </LineChart>
          </ResponsiveContainer>
        </div>
      )}
    </Panel>
  );
};

const Kpi: React.FC<{ label: string; value: React.ReactNode; hint?: string; emphasis?: boolean }> = ({ label, value, hint, emphasis }) => (
  <div className={`bg-slate-900 border rounded-xl p-4 ${emphasis ? 'border-orange-500/40' : 'border-slate-800'}`}>
    <div className="text-[11px] uppercase tracking-wider text-slate-400">{label}</div>
    <div className="text-2xl font-bold text-white mt-1">{value}</div>
    {hint && <div className="text-[10px] text-slate-500 mt-0.5">{hint}</div>}
  </div>
);

const Panel: React.FC<{ title: string; subtitle?: string; icon?: React.ReactNode; action?: React.ReactNode; children: React.ReactNode }> = ({
  title, subtitle, icon, action, children,
}) => (
  <div className="min-w-0 bg-slate-900 border border-slate-800 rounded-2xl p-5">
    <div className="flex items-start justify-between gap-3">
      <h3 className="text-sm font-bold text-white flex items-center gap-2">{icon}{title}</h3>
      {action}
    </div>
    {subtitle && <p className="text-[11px] text-slate-500 mt-0.5 mb-3">{subtitle}</p>}
    {children}
  </div>
);

const Muted: React.FC<{ children: React.ReactNode }> = ({ children }) => <p className="text-xs text-slate-500">{children}</p>;

const EmptyState: React.FC = () => (
  <div className="bg-slate-900 border border-slate-800 rounded-2xl p-10 text-center">
    <FolderGit2 className="w-8 h-8 text-slate-600 mx-auto" />
    <p className="text-sm text-slate-300 mt-3">No repositories yet.</p>
    <Link to="/repositories" className="inline-block mt-4 px-4 py-2 bg-cyan-500 hover:bg-cyan-400 text-slate-950 font-semibold text-xs rounded-xl">Connect a repository</Link>
  </div>
);

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

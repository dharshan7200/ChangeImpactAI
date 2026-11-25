import React, { useState } from 'react';
import { Link } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { Area, AreaChart, CartesianGrid, ReferenceArea, ReferenceLine, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts';
import {
  Activity, CheckCircle2, Clock, FolderGit2, GitPullRequest, Gauge, PlayCircle, ShieldAlert, ShieldHalf, Table2, TrendingUp, XCircle,
} from 'lucide-react';
import { dashboardApi } from '../api/phase5';
import type { TeamDashboard } from '../types/phase5';
import { RISK_COLORS, RiskBadge, shortSha } from '../components/analysis/risk';
import { ConclusionChip } from '../components/repository/PullRequestsPanel';
import { SeverityBadge } from '../components/analysis/SecurityPanel';
import { useCountUp } from '../hooks/useCountUp';

const SERIES = '#28d5ec'; // the single series: brand signal cyan (no other series shares the chart)

export const DashboardPage: React.FC = () => {
  const [days, setDays] = useState(30);
  const { data, isLoading } = useQuery({ queryKey: ['team-dashboard', days], queryFn: () => dashboardApi.team(days), refetchInterval: 30000 });

  return (
    <div className="space-y-6">
      <div className="flex flex-wrap items-end justify-between gap-3">
        <div>
          <p className="font-mono text-[11px] uppercase tracking-[0.18em] text-cyan-300/80">Overview</p>
          <h1 className="mt-1 text-[28px] font-semibold text-white">Team dashboard</h1>
          <p className="text-sm text-slate-400 mt-1">Change risk, pull requests, quality and security across your repositories.</p>
        </div>
        <div className="flex rounded-xl border border-slate-800 bg-slate-900/70 p-1 text-xs" role="group" aria-label="Time window">
          {[7, 30, 90].map((d) => (
            <button key={d} type="button" onClick={() => setDays(d)} aria-pressed={days === d}
                    className={`min-h-[36px] rounded-lg px-3.5 font-medium transition-colors duration-150 ${days === d ? 'bg-slate-800 text-white shadow-[inset_0_1px_0_rgba(255,255,255,0.05)]' : 'text-slate-400 hover:text-slate-100'}`}>
              {d} days
            </button>
          ))}
        </div>
      </div>

      {isLoading || !data ? (
        <div className="space-y-6" aria-busy="true" aria-label="Loading dashboard">
          <div className="grid gap-4 lg:grid-cols-[1.3fr_1fr]">
            <div className="skeleton h-[236px] rounded-2xl" />
            <div className="grid grid-cols-2 gap-4">{[0, 1, 2, 3].map((i) => <div key={i} className="skeleton h-[110px] rounded-2xl" />)}</div>
          </div>
          <div className="skeleton h-72" />
          <div className="grid grid-cols-1 xl:grid-cols-2 gap-4"><div className="skeleton h-56" /><div className="skeleton h-56" /></div>
        </div>
      ) : data.repositories.length === 0 ? (
        <EmptyState />
      ) : (
        <>
          <div className="stagger grid gap-4 lg:grid-cols-[1.3fr_1fr]">
            <HeroRisk data={data} days={days} />
            <div className="stagger grid grid-cols-2 gap-4">
              <Kpi icon={GitPullRequest} label="Open pull requests" value={data.totals.openPullRequests}
                   hint={`${data.totals.openHighRiskPullRequests} at HIGH risk or above`} emphasis={data.totals.openHighRiskPullRequests > 0} />
              <Kpi icon={ShieldAlert} label="High-risk changes" value={data.totals.highRiskChanges}
                   hint={`of ${data.totals.distinctChanges} distinct changes (${data.totals.analyses} runs)`} />
              <Kpi icon={ShieldHalf} label="High/critical security" value={data.totals.securityFindingsHighOrCritical}
                   hint="findings in analysed changes" emphasis={data.totals.securityFindingsHighOrCritical > 0} />
              <Kpi icon={XCircle} label="Failed analyses" value={data.totals.failedAnalyses} hint="could not be computed" />
            </div>
          </div>

          <div className="animate-fade-up" style={{ animationDelay: '200ms' }}><RiskTrend data={data} /></div>

          <div className="stagger grid grid-cols-1 xl:grid-cols-2 gap-4">
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
            {Object.keys(data.security.bySeverity).length === 0 ? <Muted>No security findings.</Muted> : <SeverityBar counts={data.security.bySeverity} />}
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
                    <td className="py-1.5 pr-3"><JobStatus status={j.status} /></td>
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
      title="Risk trend"
      icon={<TrendingUp className="w-4 h-4 text-cyan-400" />}
      subtitle="Highest change risk per day (heuristic 0–100); gaps are days without analyses"
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
        <div className="h-64">
          <ResponsiveContainer width="100%" height="100%">
            <AreaChart data={points} margin={{ top: 8, right: 70, bottom: 0, left: -16 }}>
              <defs>
                <linearGradient id="riskFill" x1="0" y1="0" x2="0" y2="1">
                  <stop offset="0%" stopColor={SERIES} stopOpacity={0.28} />
                  <stop offset="100%" stopColor={SERIES} stopOpacity={0} />
                </linearGradient>
              </defs>
              {/* risk bands: faint status tints, always named on the right */}
              <ReferenceArea y1={50} y2={75} fill={RISK_COLORS.HIGH} fillOpacity={0.045} ifOverflow="hidden" />
              <ReferenceArea y1={75} y2={100} fill={RISK_COLORS.CRITICAL} fillOpacity={0.06} ifOverflow="hidden" />
              <CartesianGrid stroke="#252d3a" vertical={false} />
              <XAxis dataKey="label" tick={{ fill: '#94a2b6', fontSize: 10 }} tickLine={false} axisLine={{ stroke: '#252d3a' }} interval="preserveStartEnd" minTickGap={24} />
              <YAxis domain={[0, 100]} ticks={[0, 25, 50, 75, 100]} tick={{ fill: '#94a2b6', fontSize: 10 }} tickLine={false} axisLine={false} />
              {[{ y: 25, l: 'MEDIUM' }, { y: 50, l: 'HIGH' }, { y: 75, l: 'CRITICAL' }].map((t) => (
                <ReferenceLine key={t.y} y={t.y} stroke="#3a4454" strokeDasharray="3 4"
                               label={{ value: t.l, position: 'right', fill: '#94a2b6', fontSize: 10 }} />
              ))}
              <Tooltip
                cursor={{ stroke: '#525f73', strokeWidth: 1 }}
                content={({ active, payload }) => {
                  const p = active && payload?.length ? (payload[0].payload as (typeof points)[number]) : null;
                  if (!p || p.analyses === 0) return null;
                  return (
                    <div className="rounded-xl border border-slate-700 bg-slate-950/95 px-3 py-2 text-[11px] text-slate-300 shadow-xl backdrop-blur">
                      <div className="font-semibold text-slate-100">{p.date}</div>
                      <div>Highest risk <span className="font-mono text-white">{p.maxScore}</span> · average <span className="font-mono">{p.avgScore}</span></div>
                      <div>{p.analyses} analysis(es), {p.high} HIGH or above</div>
                    </div>
                  );
                }}
              />
              <Area type="monotone" dataKey="maxScore" stroke={SERIES} strokeWidth={2} fill="url(#riskFill)" connectNulls={false} isAnimationActive={false}
                    dot={{ r: 4, fill: SERIES, stroke: '#141a24', strokeWidth: 2 }} activeDot={{ r: 6, stroke: '#141a24', strokeWidth: 2 }} />
            </AreaChart>
          </ResponsiveContainer>
        </div>
      )}
    </Panel>
  );
};

const CountUp: React.FC<{ value: number }> = ({ value }) => <>{useCountUp(value)}</>;

/** Numbers count up briefly; "11/13"-style values count each part. */
function animated(value: React.ReactNode): React.ReactNode {
  if (typeof value === 'number') return <CountUp value={value} />;
  if (typeof value === 'string' && /^\d+\/\d+$/.test(value)) {
    const [a, b] = value.split('/').map(Number);
    return <><CountUp value={a} />/<CountUp value={b} /></>;
  }
  return value;
}

const Kpi: React.FC<{ icon: React.ElementType; label: string; value: React.ReactNode; hint?: string; emphasis?: boolean }> = ({
  icon: Icon, label, value, hint, emphasis,
}) => (
  <div className={`group relative overflow-hidden rounded-2xl border bg-gradient-to-b from-slate-900 to-slate-900/50 p-4 shadow-panel transition-colors duration-200 hover:border-slate-700 ${
    emphasis ? 'border-orange-500/40' : 'border-slate-800/80'}`}>
    <div className="flex items-center justify-between">
      <span className="text-[11px] font-medium uppercase tracking-wider text-slate-400">{label}</span>
      <span className={`flex h-7 w-7 items-center justify-center rounded-lg border ${emphasis ? 'border-orange-500/30 bg-orange-500/10 text-orange-300' : 'border-slate-800 bg-slate-950/60 text-slate-400 group-hover:text-cyan-300'}`}>
        <Icon className="h-3.5 w-3.5" aria-hidden />
      </span>
    </div>
    <div className="font-display mt-2 text-[30px] font-semibold leading-none text-white tabular-nums">{animated(value)}</div>
    {hint && <div className="mt-2 text-[11px] text-slate-500">{hint}</div>}
  </div>
);

/** The headline: the riskiest change of the window, with its level, a sparkline of daily peaks and index coverage. */
const HeroRisk: React.FC<{ data: TeamDashboard; days: number }> = ({ data, days }) => {
  const days_ = data.riskTrend.filter((p) => p.maxScore != null);
  const peak = days_.reduce<(typeof days_)[number] | null>((m, p) => (m == null || (p.maxScore ?? 0) > (m.maxScore ?? 0) ? p : m), null);
  const score = peak?.maxScore ?? null;
  const level = score == null ? null : score >= 75 ? 'CRITICAL' : score >= 50 ? 'HIGH' : score >= 25 ? 'MEDIUM' : 'LOW';
  const indexed = data.totals.indexedRepositories, total = data.totals.repositories;
  return (
    <div className="relative overflow-hidden rounded-2xl border border-slate-800/80 bg-gradient-to-br from-slate-900 via-slate-900 to-cyan-950/30 p-6 shadow-panel">
      <div className="pointer-events-none absolute -right-24 -top-24 h-64 w-64 rounded-full bg-cyan-400/10 blur-3xl" aria-hidden />
      <div className="relative flex items-start justify-between gap-4">
        <div>
          <p className="flex items-center gap-2 text-[11px] font-medium uppercase tracking-wider text-slate-400">
            <Gauge className="h-3.5 w-3.5 text-cyan-300" aria-hidden /> Peak change risk · last {days} days
          </p>
          <div className="mt-3 flex items-end gap-3">
            <span className="font-display text-6xl font-semibold leading-none text-white tabular-nums">{score == null ? '—' : <CountUp value={score} />}</span>
            {score != null && <span className="mb-1.5 text-sm text-slate-500">/ 100</span>}
          </div>
          <div className="mt-3 flex flex-wrap items-center gap-2 text-xs text-slate-400">
            {level && <RiskBadge level={level} score={score} />}
            {peak && <span>on {peak.date} · {data.totals.analyses} analyses in the window</span>}
          </div>
        </div>
        <CoverageRing value={indexed} total={total} />
      </div>
      <div className="relative mt-5 h-16" aria-hidden>
        <ResponsiveContainer width="100%" height="100%">
          <AreaChart data={data.riskTrend} margin={{ top: 4, right: 4, bottom: 0, left: 4 }}>
            <defs>
              <linearGradient id="heroFill" x1="0" y1="0" x2="0" y2="1">
                <stop offset="0%" stopColor={SERIES} stopOpacity={0.35} />
                <stop offset="100%" stopColor={SERIES} stopOpacity={0} />
              </linearGradient>
            </defs>
            <YAxis hide domain={[0, 100]} />
            <Area type="monotone" dataKey="maxScore" stroke={SERIES} strokeWidth={2} fill="url(#heroFill)" connectNulls isAnimationActive={false} dot={false} />
          </AreaChart>
        </ResponsiveContainer>
      </div>
      <p className="relative mt-1 text-[11px] text-slate-500">Daily peak of the heuristic risk score (0–100), not a probability.</p>
    </div>
  );
};

/** Part of a whole as a ring: indexed repositories of all connected ones. */
const CoverageRing: React.FC<{ value: number; total: number }> = ({ value, total }) => {
  const r = 26, c = 2 * Math.PI * r, k = total ? value / total : 0;
  return (
    <div className="flex shrink-0 flex-col items-center" role="img" aria-label={`${value} of ${total} repositories indexed`}>
      <svg width="68" height="68" viewBox="0 0 68 68" aria-hidden>
        <circle cx="34" cy="34" r={r} fill="none" stroke="#252d3a" strokeWidth="6" />
        <circle cx="34" cy="34" r={r} fill="none" stroke={SERIES} strokeWidth="6" strokeLinecap="round"
                strokeDasharray={`${c * k} ${c}`} transform="rotate(-90 34 34)" className="transition-[stroke-dasharray] duration-700" />
        <text x="34" y="38" textAnchor="middle" className="fill-white font-display" fontSize="13" fontWeight="600">{value}/{total}</text>
      </svg>
      <span className="mt-1 text-[10px] uppercase tracking-wider text-slate-500">indexed</span>
    </div>
  );
};

const SEVERITY_ORDER = ['CRITICAL', 'HIGH', 'MEDIUM', 'LOW', 'INFO'] as const;
const SEVERITY_COLOR: Record<string, string> = { ...RISK_COLORS, INFO: '#76859b' };

/** Part-to-whole of findings by severity: one bar, 2 px surface gaps, every segment named with its count below. */
const SeverityBar: React.FC<{ counts: Record<string, number> }> = ({ counts }) => {
  const rows = SEVERITY_ORDER.filter((k) => counts[k]).map((k) => ({ k, n: counts[k] }));
  const total = rows.reduce((t, r) => t + r.n, 0);
  return (
    <div className="mb-4">
      <div className="flex h-2.5 w-full gap-[2px] overflow-hidden rounded-full" role="img"
           aria-label={rows.map((r) => `${r.n} ${r.k}`).join(', ')}>
        {rows.map((r) => (
          <div key={r.k} title={`${r.n} ${r.k}`} className="h-full first:rounded-l-full last:rounded-r-full"
               style={{ width: `${(r.n / total) * 100}%`, background: SEVERITY_COLOR[r.k] }} />
        ))}
      </div>
      <div className="mt-3 flex flex-wrap gap-x-5 gap-y-2">
        {rows.map((r) => (
          <span key={r.k} className="inline-flex items-center gap-2 text-xs">
            <SeverityBadge severity={r.k} />
            <span className="font-mono text-slate-200">{r.n}</span>
          </span>
        ))}
      </div>
    </div>
  );
};

const JobStatus: React.FC<{ status: string }> = ({ status }) => {
  const c = status === 'FAILED' ? 'bg-rose-400' : status === 'COMPLETED' ? 'bg-emerald-400' : 'bg-amber-400 animate-pulse';
  const t = status === 'FAILED' ? 'text-rose-300' : status === 'COMPLETED' ? 'text-emerald-300' : 'text-amber-300';
  return <span className={`inline-flex items-center gap-1.5 text-[11px] font-semibold ${t}`}><span className={`h-1.5 w-1.5 rounded-full ${c}`} aria-hidden />{status}</span>;
};

const Panel: React.FC<{ title: string; subtitle?: string; icon?: React.ReactNode; action?: React.ReactNode; children: React.ReactNode }> = ({
  title, subtitle, icon, action, children,
}) => (
  <section className="min-w-0 rounded-2xl border border-slate-800/80 bg-gradient-to-b from-slate-900 to-slate-900/60 p-5 shadow-panel">
    <div className="flex items-start justify-between gap-3">
      <h3 className="flex items-center gap-2.5 text-[15px] font-semibold text-white">
        {icon && <span className="flex h-7 w-7 items-center justify-center rounded-lg border border-slate-800 bg-slate-950/60">{icon}</span>}
        {title}
      </h3>
      {action}
    </div>
    {subtitle && <p className="mt-1 mb-4 text-xs text-slate-500">{subtitle}</p>}
    {children}
  </section>
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

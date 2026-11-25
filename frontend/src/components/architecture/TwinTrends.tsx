import React from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { CartesianGrid, Legend, Line, LineChart, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts';
import { History, Loader2 } from 'lucide-react';
import { apiClient } from '../../api/client';
import { twinApi } from '../../api/twin';
import type { TwinTrendPoint } from '../../types/twin';
import { AXIS, Card, Empty, fmt, GRID, SERIES, TooltipBox } from './twinShared';

interface Row { label: string; sha: string; message?: string; [k: string]: string | number | undefined }

const SmallMultiple: React.FC<{ title: string; rows: Row[]; series: { key: string; name: string; color: string }[]; digits?: number; domain?: [number, number] }> =
  ({ title, rows, series, digits = 0, domain }) => (
    <div className="bg-slate-950 border border-slate-800 rounded-xl p-4">
      <div className="text-xs font-semibold text-slate-200 mb-2">{title}</div>
      <div className="h-48">
        <ResponsiveContainer width="100%" height="100%">
          <LineChart data={rows} margin={{ top: 6, right: 12, bottom: 0, left: 0 }}>
            <CartesianGrid stroke={GRID} vertical={false} />
            <XAxis dataKey="label" stroke={AXIS} tick={{ fontSize: 10 }} interval="preserveStartEnd" minTickGap={24} />
            <YAxis stroke={AXIS} tick={{ fontSize: 10 }} width={48} domain={domain ?? ['auto', 'auto']} allowDecimals={digits > 0}
              tickFormatter={(v: number) => fmt(v, digits)} />
            <Tooltip cursor={{ stroke: '#64748b' }} content={({ active, payload }) => {
              const r = active && payload?.length ? (payload[0].payload as Row) : null;
              if (!r) return null;
              return (
                <TooltipBox>
                  <div className="font-mono text-white">{r.sha.substring(0, 7)} · {r.label}</div>
                  {r.message && <div className="text-slate-500 mb-1">{r.message}</div>}
                  {series.map((s) => <div key={s.key}><span style={{ color: s.color }}>●</span> {s.name}: {fmt(r[s.key] as number, digits)}</div>)}
                </TooltipBox>
              );
            }} />
            {series.length > 1 && <Legend wrapperStyle={{ fontSize: 11 }} />}
            {series.map((s) => (
              <Line key={s.key} type="linear" dataKey={s.key} name={s.name} stroke={s.color} strokeWidth={2}
                dot={{ r: 4, strokeWidth: 0, fill: s.color }} activeDot={{ r: 6 }} isAnimationActive={false} />
            ))}
          </LineChart>
        </ResponsiveContainer>
      </div>
    </div>
  );

/** How the architecture evolved over the analysed commits, with a backfill job for more history. */
export const TwinTrends: React.FC<{ repositoryId: string }> = ({ repositoryId }) => {
  const qc = useQueryClient();
  const [count, setCount] = React.useState(10);
  const [jobId, setJobId] = React.useState<string | null>(null);
  const { data: trend, isLoading } = useQuery({ queryKey: ['twin-trend', repositoryId], queryFn: () => twinApi.trend(repositoryId) });
  const start = useMutation({
    mutationFn: () => twinApi.backfill(repositoryId, count),
    onSuccess: (r) => setJobId(r.jobId),
  });
  const { data: job } = useQuery({
    queryKey: ['twin-job', jobId],
    enabled: !!jobId,
    queryFn: async () => (await apiClient.get(`/jobs/${jobId}`)).data.data as { status: string; progressPercent?: number; currentMessage?: string; errorMessage?: string },
    refetchInterval: (q) => (q.state.data && ['COMPLETED', 'FAILED'].includes(q.state.data.status) ? false : 2000),
  });
  React.useEffect(() => {
    if (job?.status === 'COMPLETED') {
      qc.invalidateQueries({ queryKey: ['twin-trend', repositoryId] });
      qc.invalidateQueries({ queryKey: ['twin-commits', repositoryId] });
    }
  }, [job?.status, qc, repositoryId]);

  const rows: Row[] = (trend ?? []).map((p: TwinTrendPoint) => ({
    label: p.commitDate ? new Date(p.commitDate).toLocaleDateString(undefined, { year: '2-digit', month: 'short', day: 'numeric' }) : p.commitSha.substring(0, 7),
    sha: p.commitSha,
    message: p.commitMessage,
    loc: p.summary.loc,
    edges: p.summary.packageEdges,
    packages: p.summary.packages,
    cycles: p.summary.cycles,
    inCycles: p.summary.packagesInCycles,
    violations: p.summary.violations,
    instability: p.summary.averageInstability,
    distance: p.summary.averageDistance,
  }));
  const running = !!jobId && job && !['COMPLETED', 'FAILED'].includes(job.status);

  return (
    <Card title="Architecture trends"
      subtitle={<>One point per analysed commit (production code). Commits get analysed when they are indexed, used in a change
        analysis, or by the history backfill, which analyses commits spread evenly over the default branch (incrementally, oldest first).</>}
      right={
        <div className="flex items-center gap-2">
          <select value={count} onChange={(e) => setCount(Number(e.target.value))} disabled={!!running}
            className="bg-slate-950 border border-slate-800 rounded-lg px-2 py-1.5 text-xs text-slate-300">
            {[6, 10, 15, 20].map((n) => <option key={n} value={n}>{n} commits</option>)}
          </select>
          <button onClick={() => start.mutate()} disabled={!!running || start.isPending}
            className="flex items-center gap-1.5 text-xs font-semibold px-3 py-1.5 rounded-lg bg-sky-600 hover:bg-sky-500 text-white disabled:opacity-50">
            {running ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <History className="w-3.5 h-3.5" />} Analyse history
          </button>
        </div>
      }>
      {jobId && job && (
        <div className={`mb-4 text-xs rounded-lg px-3 py-2 border ${job.status === 'FAILED' ? 'border-rose-800 text-rose-300 bg-rose-950/40'
          : job.status === 'COMPLETED' ? 'border-emerald-800 text-emerald-300 bg-emerald-950/30' : 'border-slate-700 text-slate-300 bg-slate-950'}`}>
          {job.status === 'FAILED' ? `History analysis failed: ${job.errorMessage ?? 'unknown error'}`
            : `${job.status === 'COMPLETED' ? 'Done' : 'Running'} · ${job.progressPercent ?? 0}% · ${job.currentMessage ?? ''}`}
          {running && <div className="h-1 bg-slate-800 rounded-full mt-2 overflow-hidden"><div className="h-full bg-sky-500 transition-all" style={{ width: `${job.progressPercent ?? 0}%` }} /></div>}
        </div>
      )}
      {isLoading ? <Empty>Loading trend…</Empty> : rows.length < 2 ? (
        <Empty>Only {rows.length} analysed commit so far. Use “Analyse history” to analyse commits across the branch history.</Empty>
      ) : (
        <div className="grid grid-cols-1 lg:grid-cols-2 gap-4">
          <SmallMultiple title="Size: lines of production code" rows={rows} series={[{ key: 'loc', name: 'lines of code', color: SERIES[0] }]} />
          <SmallMultiple title="Coupling: dependencies between packages" rows={rows}
            series={[{ key: 'edges', name: 'package dependencies', color: SERIES[0] }, { key: 'packages', name: 'packages', color: SERIES[2] }]} />
          <SmallMultiple title="Structural problems" rows={rows}
            series={[{ key: 'cycles', name: 'package cycles', color: SERIES[1] }, { key: 'inCycles', name: 'packages in cycles', color: SERIES[4] },
              { key: 'violations', name: 'rule violations', color: SERIES[3] }]} />
          <SmallMultiple title="Balance: average instability and distance from the main sequence" rows={rows} digits={2} domain={[0, 1]}
            series={[{ key: 'instability', name: 'avg instability I', color: SERIES[0] }, { key: 'distance', name: 'avg distance D', color: SERIES[1] }]} />
        </div>
      )}
    </Card>
  );
};

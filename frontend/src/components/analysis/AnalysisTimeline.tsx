import React from 'react';
import { useQuery } from '@tanstack/react-query';
import { useNavigate } from 'react-router-dom';
import { CartesianGrid, ResponsiveContainer, Scatter, ScatterChart, Tooltip, XAxis, YAxis, ZAxis } from 'recharts';
import { CalendarClock } from 'lucide-react';
import { analysesApi } from '../../api/analyses';
import type { ChangeAnalysis } from '../../types';
import { AXIS, GRID, STATUS, TooltipBox } from '../architecture/twinShared';

// Risk levels use the reserved status palette, always paired with the level's name in the legend and tooltip.
const LEVELS: { level: string; color: string }[] = [
  { level: 'LOW', color: STATUS.good },
  { level: 'MEDIUM', color: STATUS.warning },
  { level: 'HIGH', color: STATUS.serious },
  { level: 'CRITICAL', color: STATUS.critical },
];

interface Point { id: string; x: number; y: number; z: number; level: string; pr: boolean; label: string; a: ChangeAnalysis }

/** Every completed analysis of the repository over time: risk score, size and trigger; click to open it. */
export const AnalysisTimeline: React.FC<{ repositoryId: string }> = ({ repositoryId }) => {
  const navigate = useNavigate();
  const { data, isLoading } = useQuery({ queryKey: ['analysis-timeline', repositoryId], queryFn: () => analysesApi.list(repositoryId, 0, 100) });
  const all = data?.content ?? [];
  const points: Point[] = all.filter((a) => a.status === 'COMPLETED' && a.riskScore != null).map((a) => ({
    id: a.id,
    x: new Date(a.completedAt ?? a.createdAt).getTime(),
    y: Number(a.riskScore),
    z: Math.max(1, a.summary?.productionSymbolsChanged ?? a.summary?.symbolsChanged ?? 1),
    level: a.riskLevel ?? 'LOW',
    pr: a.trigger === 'PULL_REQUEST' || !!a.options?.pullRequestNumber,
    label: `${(a.baseCommit ?? a.baseRef).substring(0, 7)} → ${(a.headCommit ?? a.headRef).substring(0, 7)}`,
    a,
  }));
  const failed = all.filter((a) => a.status === 'FAILED').length;
  const xs = points.map((p) => p.x);
  const pad = xs.length ? Math.max(60_000, (Math.max(...xs) - Math.min(...xs)) * 0.05) : 0;
  const fmtTime = (t: number) => new Date(t).toLocaleString(undefined, { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' });

  return (
    <div className="bg-slate-900 border border-slate-800 rounded-2xl p-5">
      <div className="flex flex-wrap items-start justify-between gap-3 mb-2">
        <div>
          <h2 className="text-sm font-bold text-white flex items-center gap-2"><CalendarClock className="w-4 h-4 text-sky-400" />Analysis timeline</h2>
          <p className="text-[11px] text-slate-400 mt-1">Completed analyses by time and risk score (size = changed production symbols). Click a point to open the analysis.</p>
        </div>
        <div className="flex flex-wrap items-center gap-3 text-[11px] text-slate-400">
          {LEVELS.map((l) => <span key={l.level} className="flex items-center gap-1.5"><span className="w-2.5 h-2.5 rounded-full" style={{ background: l.color }} />{l.level.toLowerCase()}</span>)}
          <span className="flex items-center gap-1.5"><svg width="10" height="10"><circle cx="5" cy="5" r="4" fill="none" stroke="#94a3b8" /></svg>manual</span>
          <span className="flex items-center gap-1.5"><svg width="10" height="10"><path d="M5 1 L9 9 L1 9 Z" fill="none" stroke="#94a3b8" /></svg>pull request</span>
        </div>
      </div>
      {isLoading ? <div className="py-10 text-center text-xs text-slate-500">Loading…</div> : points.length === 0 ? (
        <div className="py-10 text-center text-xs text-slate-500">No completed analyses yet.</div>
      ) : (
        <div className="h-64">
          <ResponsiveContainer width="100%" height="100%">
            <ScatterChart margin={{ top: 8, right: 16, bottom: 4, left: 0 }}>
              <CartesianGrid stroke={GRID} />
              <XAxis type="number" dataKey="x" domain={[Math.min(...xs) - pad, Math.max(...xs) + pad]} scale="time" stroke={AXIS}
                tick={{ fontSize: 10 }} tickFormatter={fmtTime} minTickGap={40} />
              <YAxis type="number" dataKey="y" domain={[0, 100]} ticks={[0, 25, 50, 75, 100]} stroke={AXIS} tick={{ fontSize: 10 }} width={36}
                label={{ value: 'risk', angle: -90, position: 'insideLeft', fill: AXIS, fontSize: 10 }} />
              <ZAxis type="number" dataKey="z" range={[40, 500]} />
              <Tooltip cursor={false} content={({ active, payload }) => {
                const p = active && payload?.length ? (payload[0].payload as Point) : null;
                if (!p) return null;
                return (
                  <TooltipBox>
                    <div className="font-mono text-white">{p.label}</div>
                    <div>{fmtTime(p.x)} · {p.pr ? `pull request #${p.a.options?.pullRequestNumber ?? ''}` : 'manual'}</div>
                    <div>risk {p.level.toLowerCase()} ({p.y.toFixed(1)}) · {p.a.summary?.filesChanged ?? 0} files · {p.z} symbols</div>
                    <div className="text-slate-500">click to open</div>
                  </TooltipBox>
                );
              }} />
              {LEVELS.flatMap((l) => [false, true].map((pr) => (
                <Scatter key={l.level + pr} data={points.filter((p) => p.level === l.level && p.pr === pr)} fill={l.color} fillOpacity={0.85}
                  stroke="#0f172a" shape={pr ? 'triangle' : 'circle'} isAnimationActive={false} className="cursor-pointer"
                  onClick={(e: unknown) => { const id = (e as { payload?: Point })?.payload?.id; if (id) navigate(`/analyses/${id}`); }} />
              )))}
            </ScatterChart>
          </ResponsiveContainer>
        </div>
      )}
      {failed > 0 && <p className="text-[11px] text-slate-500 mt-1">{failed} failed analysis(es) not shown.</p>}
    </div>
  );
};

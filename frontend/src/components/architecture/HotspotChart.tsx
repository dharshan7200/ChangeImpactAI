import React from 'react';
import { CartesianGrid, ReferenceArea, ReferenceLine, ResponsiveContainer, Scatter, ScatterChart, Tooltip, XAxis, YAxis, ZAxis } from 'recharts';
import type { TwinFile, TwinHotspots } from '../../types/twin';
import { AXIS, Card, commonPrefix, fileName, fmt, GRID, SERIES, shortName, STATUS, TooltipBox } from './twinShared';

/** Unique, readable ticks for a square-root axis from 0 to max (Recharts' own ticks repeat on sqrt scales). */
function sqrtTicks(max: number): number[] {
  const nice = [0, 1, 2, 5, 10, 20, 50, 100, 200, 500, 1000, 2000, 5000, 10000, 20000, 50000];
  const out: number[] = [];
  for (const v of nice.filter((x) => x <= max)) {
    if (!out.length || Math.sqrt(v) - Math.sqrt(out[out.length - 1]) >= Math.sqrt(max) / 9) out.push(v);
  }
  return out;
}

function quantile(values: number[], q: number) {
  if (!values.length) return 0;
  const s = [...values].sort((a, b) => a - b);
  return s[Math.min(s.length - 1, Math.floor(q * (s.length - 1)))];
}

/**
 * Hotspot matrix: change frequency against complexity, one bubble per file (size = lines of code). Files that are both
 * changed often and complex (top-right) are where defects and effort concentrate; they are the refactoring candidates.
 */
export const HotspotChart: React.FC<{ hotspots: TwinHotspots }> = ({ hotspots }) => {
  const files = hotspots.files;
  const prefix = React.useMemo(() => commonPrefix([...new Set(files.map((f) => f.packageName))]), [files]);
  // guide lines at the lowest values that actually qualified as hotspots (top quarter of both, ≥ 2 changes)
  const qualified = files.filter((f) => f.hotspot);
  const churnLine = qualified.length ? Math.min(...qualified.map((f) => f.churnCommits)) : quantile(files.map((f) => f.churnCommits), 0.75);
  const cxLine = qualified.length ? Math.min(...qualified.map((f) => f.complexity)) : quantile(files.map((f) => f.complexity), 0.75);
  const maxChurn = Math.max(1, ...files.map((f) => f.churnCommits));
  const maxCx = Math.max(1, ...files.map((f) => f.complexity));
  const point = (f: TwinFile) => ({ ...f, x: f.churnCommits, y: f.complexity, z: Math.max(1, f.loc) });
  const hot = files.filter((f) => f.hotspot).map(point);
  const rest = files.filter((f) => !f.hotspot).map(point);
  const top = [...files].sort((a, b) => b.hotspotScore - a.hotspotScore || b.complexity - a.complexity).slice(0, 15);

  return (
    <div className="space-y-6">
      <Card title="Hotspots: change frequency × complexity"
        subtitle={<>Each bubble is a production file (size = lines of code). The dashed lines mark the lowest change count and
          complexity among hotspots (top quarter of both, at least two changes); the shaded top-right area holds them. Change frequency counts the
          {' '}{hotspots.historyCommits} scanned commits up to this commit; axes use a square-root scale.</>}>
        <div className="flex items-center gap-4 text-[11px] text-slate-400 mb-2">
          <span className="flex items-center gap-1.5"><span className="w-2.5 h-2.5 rounded-full" style={{ background: STATUS.critical }} />hotspot ({hot.length})</span>
          <span className="flex items-center gap-1.5"><span className="w-2.5 h-2.5 rounded-full" style={{ background: SERIES[0] }} />other files ({rest.length})</span>
        </div>
        <div className="h-[420px]">
          <ResponsiveContainer width="100%" height="100%">
            <ScatterChart margin={{ top: 10, right: 20, bottom: 30, left: 10 }}>
              <CartesianGrid stroke={GRID} />
              <ReferenceArea x1={churnLine} x2={maxChurn} y1={cxLine} y2={maxCx} fill={STATUS.critical} fillOpacity={0.06} stroke="none" />
              <XAxis type="number" dataKey="x" name="changes" scale="sqrt" domain={[0, maxChurn]} ticks={sqrtTicks(maxChurn)} stroke={AXIS} tick={{ fontSize: 11 }}
                label={{ value: 'commits that changed the file', position: 'insideBottom', offset: -18, fill: AXIS, fontSize: 11 }} />
              <YAxis type="number" dataKey="y" name="complexity" scale="sqrt" domain={[0, maxCx]} ticks={sqrtTicks(maxCx)} stroke={AXIS} tick={{ fontSize: 11 }}
                label={{ value: 'complexity', angle: -90, position: 'insideLeft', fill: AXIS, fontSize: 11 }} />
              <ZAxis type="number" dataKey="z" range={[16, 420]} />
              <ReferenceLine x={churnLine} stroke={AXIS} strokeDasharray="4 4" />
              <ReferenceLine y={cxLine} stroke={AXIS} strokeDasharray="4 4" />
              <Tooltip cursor={false} content={({ active, payload }) => {
                const f = active && payload?.length ? (payload[0].payload as TwinFile) : null;
                if (!f) return null;
                return (
                  <TooltipBox>
                    <div className="font-semibold text-white">{fileName(f.path)}</div>
                    <div className="text-slate-500 mb-1">{shortName(f.packageName, prefix)}</div>
                    <div>{f.churnCommits} changes · complexity {fmt(f.complexity)} (max method {f.maxMethodComplexity})</div>
                    <div>{fmt(f.loc)} lines · {f.authors} author(s) · {f.fanIn} dependent file(s)</div>
                    <div>score {f.hotspotScore.toFixed(2)}{f.hotspot && <span className="text-rose-400 font-semibold"> · hotspot</span>}</div>
                  </TooltipBox>
                );
              }} />
              <Scatter name="other files" data={rest} fill={SERIES[0]} fillOpacity={0.55} stroke="#0f172a" strokeWidth={1} isAnimationActive={false} />
              <Scatter name="hotspots" data={hot} fill={STATUS.critical} fillOpacity={0.9} stroke="#0f172a" strokeWidth={1} isAnimationActive={false} />
            </ScatterChart>
          </ResponsiveContainer>
        </div>
      </Card>

      <Card title="Refactoring candidates" subtitle="Files ranked by hotspot score (churn percentile × complexity percentile). Knowledge spread = distinct authors in the scanned history.">
        <div className="overflow-x-auto">
          <table className="w-full text-left text-xs">
            <thead className="text-slate-400 border-b border-slate-800">
              <tr>
                <th className="py-2 pr-3 font-semibold">File</th>
                <th className="py-2 pr-3 font-semibold text-right">Changes</th>
                <th className="py-2 pr-3 font-semibold text-right">Authors</th>
                <th className="py-2 pr-3 font-semibold text-right">Complexity</th>
                <th className="py-2 pr-3 font-semibold text-right">Lines</th>
                <th className="py-2 pr-3 font-semibold text-right">Dependents</th>
                {hotspots.coverageAvailable && <th className="py-2 pr-3 font-semibold text-right">Coverage</th>}
                <th className="py-2 font-semibold w-40">Score</th>
              </tr>
            </thead>
            <tbody>
              {top.map((f) => (
                <tr key={f.path} className="border-b border-slate-800/60">
                  <td className="py-2 pr-3">
                    <div className="font-mono text-slate-200">{fileName(f.path)}{f.hotspot && <span className="ml-2 text-[10px] font-sans font-bold text-rose-400 uppercase">hotspot</span>}</div>
                    <div className="text-[10px] text-slate-500">{shortName(f.packageName, prefix)}</div>
                  </td>
                  <td className="py-2 pr-3 text-right text-slate-300">{f.churnCommits}</td>
                  <td className="py-2 pr-3 text-right text-slate-300">{f.authors}</td>
                  <td className="py-2 pr-3 text-right text-slate-300">{fmt(f.complexity)}</td>
                  <td className="py-2 pr-3 text-right text-slate-300">{fmt(f.loc)}</td>
                  <td className="py-2 pr-3 text-right text-slate-300">{f.fanIn}</td>
                  {hotspots.coverageAvailable && <td className="py-2 pr-3 text-right text-slate-300">{f.coverage != null ? `${Math.round(f.coverage * 100)}%` : '–'}</td>}
                  <td className="py-2">
                    <div className="flex items-center gap-2">
                      <div className="h-1.5 flex-1 bg-slate-800 rounded-full overflow-hidden">
                        <div className="h-full rounded-full" style={{ width: `${f.hotspotScore * 100}%`, background: f.hotspot ? STATUS.critical : SERIES[0] }} />
                      </div>
                      <span className="text-slate-300 w-8 text-right">{f.hotspotScore.toFixed(2)}</span>
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </Card>
    </div>
  );
};

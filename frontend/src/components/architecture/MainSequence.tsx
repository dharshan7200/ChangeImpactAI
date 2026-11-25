import React from 'react';
import { CartesianGrid, ReferenceArea, ReferenceLine, ResponsiveContainer, Scatter, ScatterChart, Tooltip, XAxis, YAxis, ZAxis } from 'recharts';
import type { TwinPackage, TwinSnapshot } from '../../types/twin';
import { AXIS, Card, commonPrefix, fmt, GRID, SERIES, shortName, TooltipBox } from './twinShared';

/**
 * Robert C. Martin's main sequence: abstractness against instability per package (size = lines of code). Packages near
 * the diagonal A + I = 1 are balanced; concrete and stable ones (bottom-left, "zone of pain") are hard to change,
 * abstract and unstable ones (top-right, "zone of uselessness") are abstractions nobody uses.
 */
export const MainSequence: React.FC<{ snapshot: TwinSnapshot }> = ({ snapshot }) => {
  const prefix = commonPrefix(snapshot.packages.map((p) => p.name));
  const withTypes = snapshot.packages.filter((p) => p.types > 0).map((p) => ({ ...p, x: p.instability, y: p.abstractness, z: Math.max(1, p.loc) }));
  const inCycle = withTypes.filter((p) => p.cycleGroup >= 0);
  const free = withTypes.filter((p) => p.cycleGroup < 0);
  const ranked = [...withTypes].sort((a, b) => b.distance - a.distance);

  return (
    <div className="grid grid-cols-1 xl:grid-cols-2 gap-6">
      <Card title="Main sequence (abstractness × instability)"
        subtitle={<>One bubble per package (size = lines of code). I = Ce / (Ca + Ce) — how much the package depends on others;
          A = share of interfaces and abstract classes. The diagonal is the balanced "main sequence"; distance D = |A + I − 1|.</>}>
        <div className="flex items-center gap-4 text-[11px] text-slate-400 mb-2">
          <span className="flex items-center gap-1.5"><span className="w-2.5 h-2.5 rounded-full" style={{ background: SERIES[0] }} />package ({free.length})</span>
          <span className="flex items-center gap-1.5"><span className="w-2.5 h-2.5 rounded-full" style={{ background: SERIES[1] }} />in a package cycle ({inCycle.length})</span>
        </div>
        <div className="h-[420px]">
          <ResponsiveContainer width="100%" height="100%">
            <ScatterChart margin={{ top: 10, right: 20, bottom: 30, left: 10 }}>
              <CartesianGrid stroke={GRID} />
              <ReferenceArea x1={0} x2={0.3} y1={0} y2={0.3} fill="#e66767" fillOpacity={0.07} stroke="none"
                label={{ value: 'zone of pain', position: 'insideBottomLeft', fill: '#e66767', fontSize: 10 }} />
              <ReferenceArea x1={0.7} x2={1} y1={0.7} y2={1} fill="#c98500" fillOpacity={0.07} stroke="none"
                label={{ value: 'zone of uselessness', position: 'insideTopRight', fill: '#c98500', fontSize: 10 }} />
              <ReferenceLine segment={[{ x: 0, y: 1 }, { x: 1, y: 0 }]} stroke={AXIS} strokeDasharray="5 4" />
              <XAxis type="number" dataKey="x" domain={[0, 1]} ticks={[0, 0.25, 0.5, 0.75, 1]} stroke={AXIS} tick={{ fontSize: 11 }}
                label={{ value: 'instability I', position: 'insideBottom', offset: -18, fill: AXIS, fontSize: 11 }} />
              <YAxis type="number" dataKey="y" domain={[0, 1]} ticks={[0, 0.25, 0.5, 0.75, 1]} stroke={AXIS} tick={{ fontSize: 11 }}
                label={{ value: 'abstractness A', angle: -90, position: 'insideLeft', fill: AXIS, fontSize: 11 }} />
              <ZAxis type="number" dataKey="z" range={[30, 700]} />
              <Tooltip cursor={false} content={({ active, payload }) => {
                const p = active && payload?.length ? (payload[0].payload as TwinPackage) : null;
                if (!p) return null;
                return (
                  <TooltipBox>
                    <div className="font-semibold text-white">{shortName(p.name, prefix)}</div>
                    <div>I {p.instability.toFixed(2)} · A {p.abstractness.toFixed(2)} · D {p.distance.toFixed(2)}</div>
                    <div>Ca {p.ca} (depend on it) · Ce {p.ce} (it depends on)</div>
                    <div>{p.types} types ({p.abstractTypes} abstract) · {fmt(p.loc)} lines</div>
                  </TooltipBox>
                );
              }} />
              <Scatter data={free} fill={SERIES[0]} fillOpacity={0.75} stroke="#0f172a" isAnimationActive={false} />
              <Scatter data={inCycle} fill={SERIES[1]} fillOpacity={0.8} stroke="#0f172a" isAnimationActive={false} />
            </ScatterChart>
          </ResponsiveContainer>
        </div>
      </Card>

      <Card title="Packages by distance from the main sequence" subtitle="Largest distance first. Ca = packages depending on it, Ce = packages it depends on.">
        <div className="overflow-auto max-h-[460px]">
          <table className="w-full text-left text-xs">
            <thead className="text-slate-400 border-b border-slate-800 sticky top-0 bg-slate-900">
              <tr>
                <th className="py-2 pr-2 font-semibold">Package</th>
                <th className="py-2 pr-2 font-semibold text-right">Ca</th>
                <th className="py-2 pr-2 font-semibold text-right">Ce</th>
                <th className="py-2 pr-2 font-semibold text-right">I</th>
                <th className="py-2 pr-2 font-semibold text-right">A</th>
                <th className="py-2 pr-2 font-semibold text-right">D</th>
                <th className="py-2 font-semibold text-right">Lines</th>
              </tr>
            </thead>
            <tbody>
              {ranked.map((p) => (
                <tr key={p.name} className="border-b border-slate-800/60">
                  <td className="py-1.5 pr-2 font-mono text-slate-200">
                    {shortName(p.name, prefix)}{p.cycleGroup >= 0 && <span className="ml-1.5 text-[9px] font-sans uppercase text-orange-400">cycle</span>}
                  </td>
                  <td className="py-1.5 pr-2 text-right text-slate-300">{p.ca}</td>
                  <td className="py-1.5 pr-2 text-right text-slate-300">{p.ce}</td>
                  <td className="py-1.5 pr-2 text-right text-slate-300">{p.instability.toFixed(2)}</td>
                  <td className="py-1.5 pr-2 text-right text-slate-300">{p.abstractness.toFixed(2)}</td>
                  <td className="py-1.5 pr-2 text-right text-white font-semibold">{p.distance.toFixed(2)}</td>
                  <td className="py-1.5 text-right text-slate-300">{fmt(p.loc)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </Card>
    </div>
  );
};

import React from 'react';
import { useQuery } from '@tanstack/react-query';
import { Bar, BarChart, CartesianGrid, Cell, ReferenceLine, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts';
import { AlertTriangle, ArrowRight, CheckCircle2, GitCompare } from 'lucide-react';
import { twinApi } from '../../api/twin';
import type { AnalysedCommit, TwinEdgeDelta } from '../../types/twin';
import { AXIS, Card, commonPrefix, DIVERGING, Empty, fmt, GRID, shortName, TooltipBox } from './twinShared';

const label = (c: AnalysedCommit) =>
  `${c.sha.substring(0, 7)} · ${c.commitDate ? new Date(c.commitDate).toLocaleDateString() : 'date unknown'} · ${(c.message ?? '').substring(0, 50)}`;

const Delta: React.FC<{ title: string; before: number; after: number; digits?: number; worseWhenUp?: boolean | null }> = ({ title, before, after, digits = 0, worseWhenUp = true }) => {
  const d = after - before;
  // null: neither direction is good or bad (size)
  const tone = d === 0 || worseWhenUp === null ? 'text-slate-400' : (d > 0) === worseWhenUp ? 'text-rose-400' : 'text-emerald-400';
  return (
    <div className="bg-slate-950 border border-slate-800 rounded-xl p-3">
      <div className="text-[11px] uppercase tracking-wider text-slate-400">{title}</div>
      <div className="flex items-baseline gap-2 mt-1">
        <span className="text-slate-400 text-sm">{fmt(before, digits)}</span>
        <ArrowRight className="w-3 h-3 text-slate-500" />
        <span className="text-white text-xl font-bold">{fmt(after, digits)}</span>
        <span className={`text-xs font-semibold ${tone}`}>{d === 0 ? '±0' : `${d > 0 ? '+' : ''}${fmt(d, digits)}`}</span>
      </div>
    </div>
  );
};

/** Architecture drift between two analysed commits. */
export const TwinCompare: React.FC<{ repositoryId: string; commits: AnalysedCommit[] }> = ({ repositoryId, commits }) => {
  const ordered = [...commits].sort((a, b) => (a.commitDate ?? '').localeCompare(b.commitDate ?? ''));
  const [base, setBase] = React.useState(ordered[0]?.sha ?? '');
  const [head, setHead] = React.useState(ordered[ordered.length - 1]?.sha ?? '');
  const enabled = !!base && !!head && base !== head;
  const { data: c, isLoading, error } = useQuery({
    queryKey: ['twin-compare', repositoryId, base, head], enabled,
    queryFn: () => twinApi.compare(repositoryId, base, head),
  });

  if (commits.length < 2) return <Card title="Compare commits"><Empty>At least two analysed commits are needed — use “Analyse history” on the Trends view.</Empty></Card>;
  const prefix = c ? commonPrefix([...c.packageDeltas.map((d) => d.name), ...c.addedEdges.flatMap((e) => [e.source, e.target])]) : '';
  const coupling = (c?.packageDeltas ?? []).map((d) => ({ name: shortName(d.name, prefix), full: d.name, delta: d.ceAfter - d.ceBefore, d }))
    .filter((x) => x.delta !== 0).sort((a, b) => b.delta - a.delta);

  const edgeList = (title: string, edges: TwinEdgeDelta[], tone: string) => (
    <div>
      <div className={`text-xs font-semibold mb-2 ${tone}`}>{title} ({edges.length})</div>
      {edges.length === 0 ? <div className="text-[11px] text-slate-500">none</div> : (
        <ul className="space-y-1 max-h-56 overflow-auto">
          {edges.map((e) => (
            <li key={e.source + e.target} className="text-[11px] font-mono text-slate-300 flex flex-wrap items-center gap-x-1.5 break-all">
              <span>{shortName(e.source, prefix)}</span>
              <ArrowRight className="w-3 h-3 text-slate-500 shrink-0" />
              <span>{shortName(e.target, prefix)}</span>
              <span className="text-slate-500 font-sans whitespace-nowrap">({fmt(e.status === 'REMOVED' ? e.referencesBefore : e.referencesAfter)} refs)</span>
            </li>
          ))}
        </ul>
      )}
    </div>
  );

  return (
    <div className="space-y-6">
      <Card title="Compare two commits" subtitle="Structural drift between two analysed commits: packages, package dependencies, cycles, coupling and rule violations.">
        <div className="flex flex-wrap items-center gap-3">
          <select value={base} onChange={(e) => setBase(e.target.value)} className="bg-slate-950 border border-slate-800 rounded-lg px-2 py-1.5 text-xs text-slate-300 max-w-md">
            {ordered.map((x) => <option key={x.sha} value={x.sha}>{label(x)}</option>)}
          </select>
          <GitCompare className="w-4 h-4 text-slate-500" />
          <select value={head} onChange={(e) => setHead(e.target.value)} className="bg-slate-950 border border-slate-800 rounded-lg px-2 py-1.5 text-xs text-slate-300 max-w-md">
            {ordered.map((x) => <option key={x.sha} value={x.sha}>{label(x)}</option>)}
          </select>
        </div>
      </Card>
      {!enabled ? <Empty>Pick two different commits.</Empty> : isLoading ? <Empty>Comparing…</Empty> : error || !c ? <Empty>Could not compare these commits.</Empty> : (
        <>
          <div className="grid grid-cols-2 md:grid-cols-3 xl:grid-cols-6 gap-3">
            <Delta title="Packages" before={c.base.summary.packages} after={c.head.summary.packages} worseWhenUp={null} />
            <Delta title="Lines of code" before={c.base.summary.loc} after={c.head.summary.loc} worseWhenUp={null} />
            <Delta title="Package deps" before={c.base.summary.packageEdges} after={c.head.summary.packageEdges} />
            <Delta title="Cycles" before={c.base.summary.cycles} after={c.head.summary.cycles} />
            <Delta title="Rule violations" before={c.base.summary.violations} after={c.head.summary.violations} />
            <Delta title="Avg distance D" before={c.base.summary.averageDistance} after={c.head.summary.averageDistance} digits={2} />
          </div>

          <Card title="Findings">
            <ul className="space-y-2">
              {c.findings.map((f) => {
                const good = /resolved|removed|No structural drift/.test(f);
                return (
                  <li key={f} className="flex items-start gap-2 text-xs text-slate-200">
                    {good ? <CheckCircle2 className="w-4 h-4 text-emerald-400 shrink-0" /> : <AlertTriangle className="w-4 h-4 text-amber-400 shrink-0" />}
                    <span className="break-all">{f}</span>
                  </li>
                );
              })}
            </ul>
            {c.newCycles.length > 0 && (
              <div className="mt-4 space-y-2">
                {c.newCycles.map((cy, i) => (
                  <div key={i} className="bg-rose-950/30 border border-rose-900/60 rounded-xl p-3">
                    <div className="text-[11px] text-rose-400 font-semibold mb-1.5">New cycle · {cy.length} packages</div>
                    <div className="flex flex-wrap gap-1.5">{cy.map((p) => <span key={p} className="text-[10px] font-mono bg-slate-800 text-slate-200 px-1.5 py-0.5 rounded">{shortName(p, prefix)}</span>)}</div>
                  </div>
                ))}
              </div>
            )}
          </Card>

          <div className="grid grid-cols-1 xl:grid-cols-2 gap-6">
            <Card title="Package dependencies">
              <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
                {edgeList('New', c.addedEdges, 'text-rose-300')}
                {edgeList('Removed', c.removedEdges, 'text-emerald-300')}
              </div>
              {(c.addedPackages.length > 0 || c.removedPackages.length > 0) && (
                <div className="mt-4 text-[11px] text-slate-400">
                  {c.addedPackages.length > 0 && <div>New packages: <span className="font-mono text-slate-200">{c.addedPackages.map((p) => shortName(p, prefix)).join(', ')}</span></div>}
                  {c.removedPackages.length > 0 && <div>Removed packages: <span className="font-mono text-slate-200">{c.removedPackages.map((p) => shortName(p, prefix)).join(', ')}</span></div>}
                </div>
              )}
            </Card>

            <Card title="Change in outgoing coupling (Ce) per package"
              subtitle={<>Bars to the right: the package depends on more packages than before (<span style={{ color: DIVERGING.positive }}>red</span>); to the left: fewer (<span style={{ color: DIVERGING.negative }}>blue</span>).</>}>
              {coupling.length === 0 ? <Empty>No package changed its outgoing coupling.</Empty> : (
                <div style={{ height: Math.max(140, coupling.length * 26 + 40) }}>
                  <ResponsiveContainer width="100%" height="100%">
                    <BarChart data={coupling} layout="vertical" margin={{ top: 4, right: 24, bottom: 4, left: 8 }}>
                      <CartesianGrid stroke={GRID} horizontal={false} />
                      <XAxis type="number" stroke={AXIS} tick={{ fontSize: 10 }} allowDecimals={false} />
                      <YAxis type="category" dataKey="name" stroke={AXIS} tick={{ fontSize: 10 }} width={150} />
                      <ReferenceLine x={0} stroke={AXIS} />
                      <Tooltip cursor={{ fill: '#1e293b' }} content={({ active, payload }) => {
                        const x = active && payload?.length ? (payload[0].payload as (typeof coupling)[number]) : null;
                        if (!x) return null;
                        return <TooltipBox><div className="font-semibold text-white">{x.name}</div>Ce {x.d.ceBefore} → {x.d.ceAfter} · Ca {x.d.caBefore} → {x.d.caAfter}<br />I {x.d.instabilityBefore.toFixed(2)} → {x.d.instabilityAfter.toFixed(2)}</TooltipBox>;
                      }} />
                      <Bar dataKey="delta" radius={[3, 3, 3, 3]} isAnimationActive={false}>
                        {coupling.map((x) => <Cell key={x.full} fill={x.delta > 0 ? DIVERGING.positive : DIVERGING.negative} />)}
                      </Bar>
                    </BarChart>
                  </ResponsiveContainer>
                </div>
              )}
            </Card>
          </div>

          {(c.newViolations.length > 0 || c.resolvedViolations.length > 0) && (
            <Card title="Architecture rule violations">
              <div className="grid grid-cols-1 md:grid-cols-2 gap-4 text-[11px]">
                <div><div className="text-rose-300 font-semibold mb-1">New ({c.newViolations.length})</div>
                  <ul className="space-y-1 font-mono text-slate-300 max-h-48 overflow-auto">{c.newViolations.map((v) => <li key={v} className="break-all">{v}</li>)}</ul></div>
                <div><div className="text-emerald-300 font-semibold mb-1">Resolved ({c.resolvedViolations.length})</div>
                  <ul className="space-y-1 font-mono text-slate-300 max-h-48 overflow-auto">{c.resolvedViolations.map((v) => <li key={v} className="break-all">{v}</li>)}</ul></div>
              </div>
            </Card>
          )}
        </>
      )}
    </div>
  );
};

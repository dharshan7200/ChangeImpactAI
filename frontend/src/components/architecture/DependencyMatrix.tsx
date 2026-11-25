import React from 'react';
import type { TwinSnapshot } from '../../types/twin';
import { Card, commonPrefix, fmt, RampLegend, sequential, shortName, STATUS, TooltipBox } from './twinShared';

/**
 * Dependency structure matrix (DSM): row package → column package, shaded by the number of references. Packages are
 * ordered dependencies-first, so in a layered design every mark sits below the diagonal; marks above it belong to
 * package cycles (outlined in red).
 */
export const DependencyMatrix: React.FC<{ snapshot: TwinSnapshot }> = ({ snapshot }) => {
  const pkgs = React.useMemo(() => [...snapshot.packages].sort((a, b) => a.order - b.order), [snapshot]);
  const prefix = React.useMemo(() => commonPrefix(pkgs.map((p) => p.name)), [pkgs]);
  const index = React.useMemo(() => new Map(pkgs.map((p, i) => [p.name, i])), [pkgs]);
  const cells = React.useMemo(() => new Map(snapshot.edges.map((e) => [`${index.get(e.source)}:${index.get(e.target)}`, e])), [snapshot, index]);
  const maxRefs = Math.max(1, ...snapshot.edges.map((e) => e.references));
  const [hover, setHover] = React.useState<{ r: number; c: number } | null>(null);

  const n = pkgs.length;
  const cell = n <= 20 ? 26 : n <= 40 ? 18 : 12;
  const labelW = Math.min(300, 40 + Math.max(...pkgs.map((p) => shortName(p.name, prefix).length)) * 6.4);
  const top = 34;
  const size = n * cell;
  const hovered = hover ? cells.get(`${hover.r}:${hover.c}`) : undefined;

  return (
    <div className="grid grid-cols-1 xl:grid-cols-[1fr_320px] gap-6">
      <Card title="Dependency structure matrix"
        subtitle={<>Row depends on column. Packages are ordered so that dependencies come first: in a cleanly layered design all
          marks are below the diagonal. Marks above it are upward dependencies inside a package cycle
          (<span className="text-rose-400">red outline</span>). Shade = number of references (calls, field types, inheritance, …); production code only.</>}>
        <div className="flex flex-wrap items-center gap-4 mb-3">
          <RampLegend low="1" high={fmt(maxRefs)} label="References" />
          <span className="flex items-center gap-1.5 text-[11px] text-slate-400">
            <span className="inline-block w-3 h-3 border-2 rounded-sm" style={{ borderColor: STATUS.critical }} /> in a package cycle
          </span>
        </div>
        <div className="overflow-auto relative" onMouseLeave={() => setHover(null)}>
          <svg width={labelW + size + 8} height={top + size + 8} role="img" aria-label="Package dependency matrix">
            {/* column numbers */}
            {pkgs.map((p, c) => (
              <text key={'c' + p.name} x={labelW + c * cell + cell / 2} y={top - 8} fontSize={cell >= 18 ? 10 : 8} textAnchor="middle"
                fill={hover?.c === c ? '#f8fafc' : '#94a3b8'}>{c + 1}</text>
            ))}
            {pkgs.map((p, r) => (
              <g key={'r' + p.name}>
                <text x={labelW - 8} y={top + r * cell + cell / 2 + 3.5} fontSize={cell >= 18 ? 11 : 9} textAnchor="end"
                  fill={hover?.r === r ? '#f8fafc' : p.cycleGroup >= 0 ? '#fca5a5' : '#cbd5e1'}>
                  {shortName(p.name, prefix)} <tspan fill="#64748b">{r + 1}</tspan>
                </text>
                {pkgs.map((q, c) => {
                  const e = cells.get(`${r}:${c}`);
                  const highlight = hover && (hover.r === r || hover.c === c);
                  return (
                    <rect key={q.name} x={labelW + c * cell + 1} y={top + r * cell + 1} width={cell - 2} height={cell - 2} rx={2}
                      fill={r === c ? '#334155' : e ? sequential(Math.log1p(e.references) / Math.log1p(maxRefs)) : highlight ? '#172036' : '#111827'}
                      stroke={e?.inCycle ? STATUS.critical : 'none'} strokeWidth={e?.inCycle ? 1.5 : 0}
                      onMouseEnter={() => setHover({ r, c })} />
                  );
                })}
              </g>
            ))}
            {/* diagonal guide */}
            <line x1={labelW} y1={top} x2={labelW + size} y2={top + size} stroke="#475569" strokeDasharray="3 3" strokeWidth={0.8} pointerEvents="none" />
          </svg>
        </div>
        <div className="h-14 mt-2">
          {hover && (hover.r === hover.c ? (
            <TooltipBox>{shortName(pkgs[hover.r].name, prefix)}: {pkgs[hover.r].types} types, {fmt(pkgs[hover.r].loc)} lines, Ca {pkgs[hover.r].ca} · Ce {pkgs[hover.r].ce}</TooltipBox>
          ) : hovered ? (
            <TooltipBox>
              <span className="text-white font-semibold">{shortName(hovered.source, prefix)}</span> → <span className="text-white font-semibold">{shortName(hovered.target, prefix)}</span>:
              {' '}{fmt(hovered.references)} references from {hovered.filePairs} file pair(s){hovered.inCycle && <span className="text-rose-400"> · part of a cycle</span>}
            </TooltipBox>
          ) : (
            <TooltipBox>{shortName(pkgs[hover.r].name, prefix)} does not depend on {shortName(pkgs[hover.c].name, prefix)}</TooltipBox>
          ))}
        </div>
      </Card>

      <Card title={`Package cycles (${snapshot.cycles.length})`} subtitle="Packages that depend on each other, directly or through others (strongly connected components). Each cycle can only be released, tested and understood as a whole.">
        {snapshot.cycles.length === 0 ? (
          <div className="text-xs text-emerald-400 py-4">No package cycles — the packages form a clean hierarchy.</div>
        ) : (
          <div className="space-y-3">
            {snapshot.cycles.map((c, i) => (
              <div key={i} className="bg-slate-950 border border-rose-900/50 rounded-xl p-3">
                <div className="text-[11px] text-rose-400 font-semibold mb-1.5">Cycle {i + 1} · {c.length} packages</div>
                <div className="flex flex-wrap gap-1.5">
                  {c.map((p) => <span key={p} className="text-[10px] font-mono bg-slate-800 text-slate-200 px-1.5 py-0.5 rounded">{shortName(p, prefix)}</span>)}
                </div>
              </div>
            ))}
          </div>
        )}
      </Card>
    </div>
  );
};

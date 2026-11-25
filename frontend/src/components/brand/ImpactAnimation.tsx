import React from 'react';

/**
 * What ChangeImpact computes, as a 6-second loop: a change in OrderService#place travels along the dependency edges
 * and lights up what it reaches — callers, a client, a table — and finally the one test worth running. Pure SVG and
 * CSS (stroke-dash + opacity only), no JavaScript timers; stops under prefers-reduced-motion.
 */
type N = { id: string; x: number; y: number; label: string; kind: 'change' | 'code' | 'table' | 'test'; level: number };

const NODES: N[] = [
  { id: 'c', x: 80, y: 104, label: 'OrderService#place', kind: 'change', level: 0 },
  { id: 'a', x: 184, y: 46, label: 'CheckoutController', kind: 'code', level: 1 },
  { id: 'b', x: 184, y: 162, label: 'PaymentClient', kind: 'code', level: 1 },
  { id: 'd', x: 292, y: 26, label: 'OrderApi', kind: 'code', level: 2 },
  { id: 'e', x: 292, y: 104, label: 'db:orders', kind: 'table', level: 2 },
  { id: 'f', x: 292, y: 182, label: 'RefundJob', kind: 'code', level: 2 },
  { id: 'g', x: 378, y: 66, label: 'CheckoutControllerTest', kind: 'test', level: 3 },
];
const EDGES: [string, string][] = [['c', 'a'], ['c', 'b'], ['c', 'e'], ['a', 'd'], ['b', 'f'], ['a', 'g']];
const STEP = 0.8; // seconds between impact levels
const DRAW = 0.72; // an edge takes 12 % of the 6 s loop to draw

const byId = Object.fromEntries(NODES.map((n) => [n.id, n]));
const COLOR = { change: '#28d5ec', code: '#9af4fb', table: '#fbbf24', test: '#34d399' } as const;

export const ImpactAnimation: React.FC<{ className?: string }> = ({ className }) => (
  <figure className={className} aria-label="Animation: a change propagates through the dependency graph to the code, table and test it affects">
    <svg viewBox="0 0 430 210" className="h-auto w-full overflow-visible" aria-hidden>
      {EDGES.map(([s, t]) => {
        const a = byId[s], b = byId[t];
        const d = `M${a.x},${a.y} C${(a.x + b.x) / 2},${a.y} ${(a.x + b.x) / 2},${b.y} ${b.x},${b.y}`;
        return (
          <g key={s + t}>
            <path d={d} fill="none" stroke="#252d3a" strokeWidth={1.5} />
            <path d={d} fill="none" stroke={COLOR[b.kind]} strokeWidth={1.8} strokeLinecap="round" pathLength={1}
                  strokeDasharray="1" className="animate-edge-draw" style={{ animationDelay: `${b.level * STEP - DRAW}s` }} />
          </g>
        );
      })}
      {NODES.map((n) => {
        const r = n.kind === 'change' ? 7 : 5.5;
        const anchorEnd = n.x > 330; // rightmost labels end at their node so they stay inside the box
        return (
          <g key={n.id}>
            {n.kind === 'change' && (
              <circle cx={n.x} cy={n.y} r={r} fill="none" stroke={COLOR.change} strokeWidth={1.5}
                      className="animate-change-ring" style={{ transformOrigin: `${n.x}px ${n.y}px`, transformBox: 'view-box' }} />
            )}
            <circle cx={n.x} cy={n.y} r={r} fill="#141a24" stroke="#3a4454" strokeWidth={1.2} />
            <circle cx={n.x} cy={n.y} r={r} fill={COLOR[n.kind]} className="animate-node-hit"
                    style={{ animationDelay: `${n.level * STEP}s`, filter: `drop-shadow(0 0 6px ${COLOR[n.kind]})` }} />
            <text x={anchorEnd ? n.x + 8 : n.x} y={n.kind === 'change' ? n.y - 15 : n.kind === 'test' ? n.y + 20 : n.y - 11} textAnchor={anchorEnd ? 'end' : 'middle'}
                  className="fill-slate-400 font-mono" fontSize={9.5}>
              {n.kind === 'change' ? 'Δ ' : ''}{n.label}
            </text>
          </g>
        );
      })}
    </svg>
    <figcaption className="mt-3 flex flex-wrap items-center gap-x-4 gap-y-1 font-mono text-[11px] text-slate-400">
      <Legend color={COLOR.change} label="changed" />
      <Legend color={COLOR.code} label="impacted code" />
      <Legend color={COLOR.table} label="table" />
      <Legend color={COLOR.test} label="test to run" />
    </figcaption>
  </figure>
);

const Legend: React.FC<{ color: string; label: string }> = ({ color, label }) => (
  <span className="inline-flex items-center gap-1.5"><span className="h-2 w-2 rounded-full" style={{ background: color }} />{label}</span>
);

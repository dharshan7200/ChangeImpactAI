import React from 'react';

// Colours from the validated reference palette (dataviz skill), dark mode.
// Sequential (magnitude): one blue hue; on the dark surface the lowest values recede (dark), high values are light.
export const SEQUENTIAL = ['#0d366b', '#104281', '#184f95', '#1c5cab', '#256abf', '#2a78d6', '#3987e5',
  '#5598e7', '#6da7ec', '#86b6ef', '#9ec5f4', '#b7d3f6'];
export const SERIES = ['#3987e5', '#d95926', '#199e70', '#c98500', '#d55181', '#0ca30c', '#9085e9', '#e66767'];
export const STATUS = { good: '#0ca30c', warning: '#fab219', serious: '#ec835a', critical: '#d03b3b' };
export const GRID = '#1e293b';
export const AXIS = '#94a3b8';
export const MUTED = '#64748b';
// Diverging (blue ↔ red, grey midpoint): decreases blue, increases red.
export const DIVERGING = { negative: '#3987e5', neutral: '#383835', positive: '#e66767' };

/** Colour for a value in [0, 1] on the sequential ramp. */
export function sequential(t: number): string {
  const v = Number.isFinite(t) ? Math.min(1, Math.max(0, t)) : 0;
  return SEQUENTIAL[Math.min(SEQUENTIAL.length - 1, Math.floor(v * SEQUENTIAL.length))];
}

/** Longest common dotted prefix of package names (e.g. "org.apache.commons.lang3"), for short labels. */
export function commonPrefix(names: string[]): string {
  if (names.length < 2) return '';
  const split = names.map((n) => n.split('.'));
  const first = split[0];
  let i = 0;
  while (i < first.length - 1 && split.every((s) => s.length > i + 1 && s[i] === first[i])) i++;
  return first.slice(0, i).join('.');
}

export function shortName(name: string, prefix: string): string {
  if (!name) return '(default package)';
  if (prefix && name === prefix) return name.split('.').pop() ?? name;
  return prefix && name.startsWith(prefix + '.') ? name.substring(prefix.length + 1) : name;
}

export const fmt = (n: number | undefined | null, digits = 0) =>
  n == null ? '–' : n.toLocaleString(undefined, { maximumFractionDigits: digits, minimumFractionDigits: digits });

export const fileName = (path: string) => path.substring(path.lastIndexOf('/') + 1);

export const Tile: React.FC<{ label: string; value: React.ReactNode; hint?: string; tone?: 'critical' | 'default' }> = ({ label, value, hint, tone }) => (
  <div className="bg-slate-900 border border-slate-800 rounded-xl p-4" title={hint}>
    <div className="text-[11px] uppercase tracking-wider text-slate-400">{label}</div>
    <div className={`text-2xl font-bold mt-1 ${tone === 'critical' ? 'text-rose-400' : 'text-white'}`}>{value}</div>
    {hint && <div className="text-[10px] text-slate-500 mt-1 leading-snug">{hint}</div>}
  </div>
);

export const Card: React.FC<{ title: string; subtitle?: React.ReactNode; right?: React.ReactNode; children: React.ReactNode }> = ({ title, subtitle, right, children }) => (
  <div className="bg-slate-900 border border-slate-800 rounded-2xl p-5">
    <div className="flex flex-wrap items-start justify-between gap-3 mb-3">
      <div>
        <h2 className="text-base font-bold text-white">{title}</h2>
        {subtitle && <p className="text-xs text-slate-400 mt-1 max-w-3xl leading-relaxed">{subtitle}</p>}
      </div>
      {right}
    </div>
    {children}
  </div>
);

export const TooltipBox: React.FC<{ children: React.ReactNode }> = ({ children }) => (
  <div className="bg-slate-950 border border-slate-700 rounded-lg px-3 py-2 text-[11px] text-slate-300 shadow-xl max-w-xs">{children}</div>
);

/** Gradient legend for the sequential ramp. */
export const RampLegend: React.FC<{ low: string; high: string; label: string }> = ({ low, high, label }) => (
  <div className="flex items-center gap-2 text-[11px] text-slate-400">
    <span>{label}</span>
    <span>{low}</span>
    <span className="inline-flex h-2.5 w-32 rounded-sm overflow-hidden border border-slate-700">
      {SEQUENTIAL.map((c) => <span key={c} style={{ background: c }} className="flex-1" />)}
    </span>
    <span>{high}</span>
  </div>
);

export const Empty: React.FC<{ children: React.ReactNode }> = ({ children }) => (
  <div className="py-12 text-center text-slate-500 text-xs">{children}</div>
);

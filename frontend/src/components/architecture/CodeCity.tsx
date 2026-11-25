import React from 'react';
import type { TwinFile, TwinHotspots } from '../../types/twin';
import { Card, commonPrefix, fileName, fmt, RampLegend, sequential, shortName, STATUS, TooltipBox } from './twinShared';

type Metric = 'hotspotScore' | 'churn' | 'complexity' | 'coverage';
interface Rect { x: number; y: number; w: number; h: number }
interface Item<T> { value: number; data: T }

/** Squarified treemap layout (Bruls, Huizing, van Wijk): rectangles with aspect ratios close to 1. */
function squarify<T>(items: Item<T>[], rect: Rect): (Rect & { data: T })[] {
  const total = items.reduce((s, i) => s + i.value, 0);
  if (total <= 0 || rect.w <= 0 || rect.h <= 0) return [];
  const scale = (rect.w * rect.h) / total;
  const queue = [...items].filter((i) => i.value > 0).sort((a, b) => b.value - a.value).map((i) => ({ ...i, area: i.value * scale }));
  const out: (Rect & { data: T })[] = [];
  let free = { ...rect };
  const worst = (row: typeof queue, side: number) => {
    const sum = row.reduce((s, r) => s + r.area, 0);
    const max = Math.max(...row.map((r) => r.area)), min = Math.min(...row.map((r) => r.area));
    return Math.max((side * side * max) / (sum * sum), (sum * sum) / (side * side * min));
  };
  while (queue.length) {
    const side = Math.min(free.w, free.h);
    const row = [queue.shift()!];
    while (queue.length && worst([...row, queue[0]], side) <= worst(row, side)) row.push(queue.shift()!);
    const sum = row.reduce((s, r) => s + r.area, 0);
    if (free.w >= free.h) {          // place the row as a column on the left
      const w = sum / free.h;
      let y = free.y;
      for (const r of row) { const h = r.area / w; out.push({ x: free.x, y, w, h, data: r.data }); y += h; }
      free = { x: free.x + w, y: free.y, w: free.w - w, h: free.h };
    } else {                          // as a row on top
      const h = sum / free.w;
      let x = free.x;
      for (const r of row) { const w = r.area / h; out.push({ x, y: free.y, w, h, data: r.data }); x += w; }
      free = { x: free.x, y: free.y + h, w: free.w, h: free.h - h };
    }
  }
  return out;
}

function useWidth(ref: React.RefObject<HTMLDivElement>) {
  const [w, setW] = React.useState(900);
  React.useEffect(() => {
    if (!ref.current) return;
    const ro = new ResizeObserver((e) => setW(Math.max(320, Math.floor(e[0].contentRect.width))));
    ro.observe(ref.current);
    return () => ro.disconnect();
  }, [ref]);
  return w;
}

const METRICS: { key: Metric; label: string; low: string; high: string; hint: string }[] = [
  { key: 'hotspotScore', label: 'Hotspot score', low: '0', high: '1', hint: 'change-frequency percentile × complexity percentile' },
  { key: 'churn', label: 'Change frequency', low: '0', high: 'max', hint: 'commits that changed the file (scanned history)' },
  { key: 'complexity', label: 'Complexity', low: '0', high: 'max', hint: 'sum of the cyclomatic complexity of its methods' },
  { key: 'coverage', label: 'Line coverage', low: '0%', high: '100%', hint: 'from the latest JaCoCo report of this commit' },
];

/**
 * "Code city" as a treemap: packages are blocks, files are tiles sized by lines of code and coloured by the chosen
 * metric (sequential blue: brighter = more). Hotspots are outlined in red.
 */
export const CodeCity: React.FC<{ hotspots: TwinHotspots }> = ({ hotspots }) => {
  const ref = React.useRef<HTMLDivElement>(null);
  const width = useWidth(ref);
  const height = Math.max(420, Math.min(720, Math.round(width * 0.62)));
  const [metric, setMetric] = React.useState<Metric>('hotspotScore');
  const [hover, setHover] = React.useState<{ f: TwinFile; x: number; y: number } | null>(null);

  const files = hotspots.files.filter((f) => f.loc > 0);
  const prefix = React.useMemo(() => commonPrefix([...new Set(files.map((f) => f.packageName))]), [files]);
  const maxChurn = Math.max(1, ...files.map((f) => f.churnCommits));
  const maxCx = Math.max(1, ...files.map((f) => f.complexity));
  const value = (f: TwinFile): number | null => {
    switch (metric) {
      case 'hotspotScore': return f.hotspotScore;
      case 'churn': return Math.sqrt(f.churnCommits / maxChurn);
      case 'complexity': return Math.sqrt(f.complexity / maxCx);
      case 'coverage': return f.coverage ?? null;
    }
  };

  const layout = React.useMemo(() => {
    const byPkg = new Map<string, TwinFile[]>();
    files.forEach((f) => byPkg.set(f.packageName, [...(byPkg.get(f.packageName) ?? []), f]));
    const pkgs = squarify([...byPkg.entries()].map(([name, fs]) => ({ value: fs.reduce((s, f) => s + f.loc, 0), data: { name, fs } })),
      { x: 0, y: 0, w: width, h: height });
    return pkgs.map((p) => {
      const header = p.h > 34 && p.w > 60 ? 16 : 0;
      const inner = { x: p.x + 2, y: p.y + 2 + header, w: p.w - 4, h: p.h - 4 - header };
      return { ...p, header, tiles: squarify(p.data.fs.map((f) => ({ value: f.loc, data: f })), inner) };
    });
  }, [files, width, height]);

  const m = METRICS.find((x) => x.key === metric)!;
  return (
    <Card
      title="Code city"
      subtitle={<>Every production file is a tile sized by lines of code, grouped into package blocks. Colour shows the selected
        metric ({m.hint}); <span className="text-rose-400">red outlines</span> mark hotspots — files in the top quarter of both change
        frequency and complexity, changed at least twice. History: {hotspots.historyCommits} commits up to this commit.</>}
      right={
        <div className="flex items-center gap-2">
          {METRICS.map((x) => (
            <button key={x.key} disabled={x.key === 'coverage' && !hotspots.coverageAvailable}
              title={x.key === 'coverage' && !hotspots.coverageAvailable ? 'Upload a JaCoCo report for this commit to colour by coverage' : x.hint}
              onClick={() => setMetric(x.key)}
              className={`text-[11px] px-2.5 py-1 rounded-lg border transition ${metric === x.key ? 'bg-sky-600/20 border-sky-500 text-sky-300'
                : 'border-slate-700 text-slate-400 hover:text-slate-200'} disabled:opacity-40 disabled:cursor-not-allowed`}>
              {x.label}
            </button>
          ))}
        </div>
      }>
      <div className="flex flex-wrap items-center justify-between gap-2 mb-2">
        <RampLegend low={m.low} high={m.high} label={m.label} />
        <div className="flex items-center gap-2 text-[11px] text-slate-400">
          <span className="inline-block w-3 h-3 rounded-sm border-2" style={{ borderColor: STATUS.critical }} /> hotspot
          {metric === 'coverage' && <><span className="inline-block w-3 h-3 rounded-sm bg-slate-700 ml-3" /> no coverage data</>}
        </div>
      </div>
      <div ref={ref} className="relative w-full" onMouseLeave={() => setHover(null)}>
        <svg width={width} height={height} role="img" aria-label="Treemap of files by package">
          {layout.map((p) => (
            <g key={p.data.name}>
              <rect x={p.x + 0.5} y={p.y + 0.5} width={Math.max(0, p.w - 1)} height={Math.max(0, p.h - 1)} fill="#0b1220" stroke="#334155" />
              {p.header > 0 && (
                <text x={p.x + 5} y={p.y + 13} fontSize={10.5} fill="#cbd5e1" style={{ pointerEvents: 'none' }}>
                  {truncate(shortName(p.data.name, prefix), Math.floor((p.w - 10) / 6))}
                </text>
              )}
              {p.tiles.map((t) => {
                const v = value(t.data);
                return (
                  <rect key={t.data.path} x={t.x + 0.5} y={t.y + 0.5} width={Math.max(0, t.w - 1)} height={Math.max(0, t.h - 1)} rx={1.5}
                    fill={v == null ? '#334155' : sequential(v)}
                    stroke={t.data.hotspot ? STATUS.critical : 'none'} strokeWidth={t.data.hotspot ? 1.6 : 0}
                    onMouseMove={(e) => {
                      const box = ref.current!.getBoundingClientRect();
                      setHover({ f: t.data, x: e.clientX - box.left, y: e.clientY - box.top });
                    }} />
                );
              })}
              {p.tiles.filter((t) => t.w > 70 && t.h > 18).map((t) => (
                <text key={'l' + t.data.path} x={t.x + 4} y={t.y + 13} fontSize={10} fill={(value(t.data) ?? 0) > 0.6 ? '#0b0b0b' : '#e2e8f0'}
                  style={{ pointerEvents: 'none' }}>
                  {truncate(fileName(t.data.path).replace(/\.java$/, ''), Math.floor((t.w - 8) / 5.8))}
                </text>
              ))}
            </g>
          ))}
        </svg>
        {hover && (
          <div className="absolute pointer-events-none z-10" style={{ left: Math.min(hover.x + 14, width - 260), top: hover.y + 14 }}>
            <TooltipBox>
              <div className="font-semibold text-white">{fileName(hover.f.path)}</div>
              <div className="text-slate-500 mb-1 break-all">{shortName(hover.f.packageName, prefix)}</div>
              <div>{fmt(hover.f.loc)} lines · {hover.f.methods} methods · complexity {fmt(hover.f.complexity)}</div>
              <div>changed in {hover.f.churnCommits} commits ({fmt(hover.f.churnLines)} lines) by {hover.f.authors} author(s)</div>
              <div>{hover.f.fanIn} file(s) depend on it{hover.f.coverage != null ? ` · coverage ${Math.round(hover.f.coverage * 100)}%` : ''}</div>
              <div className="mt-1">hotspot score {hover.f.hotspotScore.toFixed(2)}{hover.f.hotspot ? <span className="text-rose-400 font-semibold"> · hotspot</span> : ''}</div>
            </TooltipBox>
          </div>
        )}
      </div>
    </Card>
  );
};

function truncate(s: string, max: number) {
  if (max < 3) return '';
  return s.length > max ? s.substring(0, Math.max(1, max - 1)) + '…' : s;
}

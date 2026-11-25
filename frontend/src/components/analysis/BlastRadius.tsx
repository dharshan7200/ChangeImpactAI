import React from 'react';
import { useQuery } from '@tanstack/react-query';
import { Radar } from 'lucide-react';
import { apiClient } from '../../api/client';
import type { ApiResponse, ImpactFindingView } from '../../types';

interface Page<T> { content: T[]; totalElements: number }

const MAX_FINDINGS = 2000;

/** All impact findings (strongest first), up to MAX_FINDINGS, fetched in pages of 500. */
async function loadFindings(analysisId: string, includeTests: boolean): Promise<{ content: ImpactFindingView[]; totalElements: number }> {
  const content: ImpactFindingView[] = [];
  let total = 0;
  for (let page = 0; content.length < MAX_FINDINGS; page++) {
    const res = await apiClient.get<ApiResponse<Page<ImpactFindingView>>>(`/analyses/${analysisId}/impacts`, { params: { includeTests, size: 500, page } });
    const p = res.data.data;
    total = p.totalElements;
    content.push(...p.content);
    if (p.content.length < 500 || content.length >= total) break;
  }
  return { content, totalElements: total };
}
import { RampLegend, sequential, SERIES } from '../architecture/twinShared';

interface Node {
  key: string;
  name: string;
  qualifiedName: string;
  symbolType: string;
  depth: number;
  relationship: string[];
  children: Node[];
  parent?: Node;
  ends: ImpactFindingView[];      // findings whose path ends here
  value: number;
  strength: number;
  test: boolean;
}

/** Builds a tree from the dependency paths (changed symbol → … → affected symbol) of all findings. */
function buildTree(findings: ImpactFindingView[]): Node {
  const root: Node = { key: 'root', name: 'change', qualifiedName: '', symbolType: '', depth: 0, relationship: [], children: [], ends: [], value: 0, strength: 1, test: false };
  for (const f of findings) {
    let node = root;
    f.dependencyPath.forEach((step, i) => {
      let child = node.children.find((c) => c.key === step.symbolId);
      if (!child) {
        child = { key: step.symbolId, name: step.simpleName, qualifiedName: step.qualifiedName, symbolType: step.symbolType, depth: i + 1,
          relationship: step.relationship, children: [], parent: node, ends: [], value: 0, strength: 0, test: false };
        node.children.push(child);
      }
      node = child;
    });
    node.ends.push(f);
    node.strength = Math.max(node.strength, Number(f.strength));
    node.test = node.test || f.test;
  }
  const total = (n: Node): number => {
    n.value = n.ends.length + n.children.reduce((s, c) => s + total(c), 0);
    n.children.sort((a, b) => b.value - a.value || a.name.localeCompare(b.name));
    return n.value;
  };
  total(root);
  return root;
}

function arc(cx: number, cy: number, r0: number, r1: number, a0: number, a1: number): string {
  const large = a1 - a0 > Math.PI ? 1 : 0;
  const p = (r: number, a: number) => `${cx + r * Math.sin(a)},${cy - r * Math.cos(a)}`;
  if (a1 - a0 >= 2 * Math.PI - 1e-6) { // full ring: two halves
    const mid = a0 + Math.PI;
    return `M${p(r1, a0)}A${r1},${r1} 0 1 1 ${p(r1, mid)}A${r1},${r1} 0 1 1 ${p(r1, a0)}`
      + `M${p(r0, a0)}A${r0},${r0} 0 1 0 ${p(r0, mid)}A${r0},${r0} 0 1 0 ${p(r0, a0)}Z`;
  }
  return `M${p(r1, a0)}A${r1},${r1} 0 ${large} 1 ${p(r1, a1)}L${p(r0, a1)}A${r0},${r0} 0 ${large} 0 ${p(r0, a0)}Z`;
}

interface Slice { node: Node; a0: number; a1: number }

/**
 * Blast radius as a sunburst: the centre is the change, the first ring the changed symbols, every further ring one
 * more dependency hop; arcs are sized by how many impacted symbols lie beyond them. Built only from the analysis'
 * dependency paths. Click an arc to zoom in, the centre to zoom out.
 */
export const BlastRadius: React.FC<{ analysisId: string }> = ({ analysisId }) => {
  const [includeTests, setIncludeTests] = React.useState(false);
  const { data, isLoading } = useQuery({
    queryKey: ['blast-radius', analysisId, includeTests],
    queryFn: () => loadFindings(analysisId, includeTests),
  });
  const root = React.useMemo(() => buildTree(data?.content ?? []), [data]);
  const [focus, setFocus] = React.useState<Node | null>(null);
  const [hover, setHover] = React.useState<Node | null>(null);
  React.useEffect(() => setFocus(null), [data]);
  const center = focus ?? root;

  const size = 520, cx = size / 2, cy = size / 2, inner = 54;
  const maxDepth = React.useMemo(() => {
    let m = 0;
    const walk = (n: Node) => { m = Math.max(m, n.depth - center.depth); n.children.forEach(walk); };
    walk(center);
    return Math.max(1, m);
  }, [center]);
  const ring = (size / 2 - inner - 8) / maxDepth;

  const slices: Slice[] = [];
  const layout = (n: Node, a0: number, a1: number) => {
    let a = a0;
    for (const c of n.children) {
      const span = ((a1 - a0) * c.value) / Math.max(1, n.value);
      slices.push({ node: c, a0: a, a1: a + span });
      layout(c, a, a + span);
      a += span;
    }
  };
  layout(center, 0, 2 * Math.PI);

  const crumbs = (n: Node | null) => {
    const out: string[] = [];
    for (let x: Node | undefined = n ?? undefined; x && x.depth > 0; x = x.parent) out.unshift(x.name);
    return out;
  };
  const colour = (n: Node) => (n.depth === 1 ? SERIES[1] : n.test ? SERIES[2] : sequential(0.15 + 0.85 * n.strength));
  const total = data?.totalElements ?? 0;

  return (
    <div className="bg-slate-900 border border-slate-800 rounded-2xl p-5">
      <div className="flex flex-wrap items-start justify-between gap-3 mb-2">
        <div>
          <h2 className="text-sm font-bold text-white flex items-center gap-2"><Radar className="w-4 h-4 text-orange-400" />Blast radius</h2>
          <p className="text-[11px] text-slate-400 mt-1 max-w-2xl leading-relaxed">
            Centre: the change. First ring: changed symbols; each further ring is one more dependency hop. An arc's width is the number
            of impacted symbols reached through it; its shade the propagation strength. Click an arc to zoom in, the centre to zoom out.
          </p>
        </div>
        <label className="flex items-center gap-2 text-[11px] text-slate-300">
          <input type="checkbox" checked={includeTests} onChange={(e) => setIncludeTests(e.target.checked)} className="accent-sky-500" /> include test code
        </label>
      </div>
      {isLoading ? <div className="py-16 text-center text-xs text-slate-500">Loading impact paths…</div> : root.children.length === 0 ? (
        <div className="py-16 text-center text-xs text-slate-500">No code depends on the changed symbols.</div>
      ) : (
        <div className="flex flex-col lg:flex-row gap-5 items-center lg:items-start">
          <svg width={size} height={size} viewBox={`0 0 ${size} ${size}`} className="max-w-full h-auto shrink-0" role="img" aria-label="Blast radius sunburst"
            onMouseLeave={() => setHover(null)}>
            {slices.map((s) => {
              const d = s.node.depth - center.depth;
              if (s.a1 - s.a0 < 0.002) return null;
              return (
                <path key={s.node.key + ':' + crumbs(s.node).join('/')} d={arc(cx, cy, inner + (d - 1) * ring + 1, inner + d * ring - 1, s.a0 + 0.002, s.a1 - 0.002)}
                  fill={colour(s.node)} fillOpacity={hover && hover !== s.node && !isAncestor(s.node, hover) ? 0.45 : 1}
                  stroke="#0f172a" strokeWidth={1} className="cursor-pointer"
                  onMouseEnter={() => setHover(s.node)} onClick={() => s.node.children.length && setFocus(s.node)} />
              );
            })}
            <circle cx={cx} cy={cy} r={inner - 3} fill="#0b1220" stroke="#334155" className={focus ? 'cursor-pointer' : ''}
              onClick={() => setFocus(focus?.parent && focus.parent.depth > 0 ? focus.parent : null)} />
            <text x={cx} y={cy - 4} textAnchor="middle" fontSize={11} fill="#e2e8f0" style={{ pointerEvents: 'none' }}>
              {focus ? truncate(focus.name, 14) : `${root.children.length} with impacts`}
            </text>
            <text x={cx} y={cy + 12} textAnchor="middle" fontSize={10} fill="#94a3b8" style={{ pointerEvents: 'none' }}>
              {focus ? 'click to go back' : `${total} impacts`}
            </text>
          </svg>
          <div className="flex-1 min-w-0 space-y-3 text-xs">
            <div className="flex flex-wrap gap-3 text-[11px] text-slate-400">
              <span className="flex items-center gap-1.5"><span className="w-3 h-3 rounded-sm" style={{ background: SERIES[1] }} />changed symbol</span>
              {includeTests && <span className="flex items-center gap-1.5"><span className="w-3 h-3 rounded-sm" style={{ background: SERIES[2] }} />test code</span>}
            </div>
            <RampLegend low="weak" high="strong" label="Propagation strength" />
            <div className="bg-slate-950 border border-slate-800 rounded-xl p-3 min-h-[120px]">
              {hover ? (
                <>
                  <div className="text-[10px] text-slate-500 break-all">{crumbs(hover).join(' → ')}</div>
                  <div className="text-white font-semibold mt-1 break-all">{hover.qualifiedName}</div>
                  <div className="text-slate-400 mt-1">{hover.symbolType.toLowerCase()} · {hover.depth === 1 ? 'changed' : `${hover.depth - 1} hop(s) from the change`}
                    {hover.relationship.length > 0 && <> · via {hover.relationship.join(', ').toLowerCase()}</>}</div>
                  {hover.ends.length > 0 && <div className="text-slate-400">strength {hover.strength.toFixed(2)}{hover.test ? ' · test code' : ''}{hover.ends[0].filePath ? ` · ${hover.ends[0].filePath.split('/').pop()}:${hover.ends[0].startLine}` : ''}</div>}
                  <div className="text-slate-500 mt-1">{hover.value} impacted symbol(s) through here{hover.children.length > 0 ? ' · click to zoom' : ''}</div>
                </>
              ) : <div className="text-slate-500">Hover an arc to see the dependency path.</div>}
            </div>
            <div>
              <div className="text-[11px] text-slate-400 mb-1.5">Widest reach from {focus ? focus.name : 'the change'}</div>
              <ul className="space-y-1">
                {center.children.slice(0, 8).map((c) => (
                  <li key={c.key} className="flex items-center gap-2">
                    <span className="w-2.5 h-2.5 rounded-sm shrink-0" style={{ background: colour(c) }} />
                    <button className="font-mono text-slate-200 truncate hover:text-white text-left" onClick={() => c.children.length && setFocus(c)} title={c.qualifiedName}>{c.name}</button>
                    <span className="text-slate-500 ml-auto">{c.value}</span>
                  </li>
                ))}
              </ul>
            </div>
            {total > (data?.content.length ?? 0) && <p className="text-[11px] text-slate-500">Showing the {data?.content.length} strongest of {total} impacts.</p>}
          </div>
        </div>
      )}
    </div>
  );
};

function isAncestor(a: Node, b: Node) {
  for (let x: Node | undefined = b; x; x = x.parent) if (x === a) return true;
  return false;
}

function truncate(s: string, n: number) {
  return s.length > n ? s.substring(0, n - 1) + '…' : s;
}

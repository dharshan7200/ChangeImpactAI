import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  ReactFlow,
  Background,
  Controls,
  MiniMap,
  Panel,
  Handle,
  Position,
  MarkerType,
  useNodesState,
  type Edge,
  type Node,
  type NodeProps,
  type ReactFlowInstance,
} from '@xyflow/react';
import '@xyflow/react/dist/style.css';
import dagre from '@dagrejs/dagre';
import {
  ArrowDownLeft,
  ArrowDown,
  ArrowRight,
  ArrowUpRight,
  Box,
  Compass,
  Eye,
  EyeOff,
  FileCode,
  Focus,
  FolderTree,
  Layers,
  Maximize2,
  Search,
  Zap,
} from 'lucide-react';
import { codeApi } from '../../api/code';
import type { GraphEdgeView, GraphNodeView, GraphSnapshot, GraphViewMode, TraversalNode } from '../../types';

// ---------------------------------------------------------------------------
// Relationship groups: every edge is coloured by the most "structural" kind it carries.
// ---------------------------------------------------------------------------

interface RelationshipGroup {
  key: string;
  label: string;
  types: string[];
  color: string;
  dashed?: boolean;
}

const RELATIONSHIP_GROUPS: RelationshipGroup[] = [
  { key: 'inherits', label: 'Extends / implements', types: ['EXTENDS', 'IMPLEMENTS'], color: '#c084fc' },
  { key: 'calls', label: 'Method calls', types: ['METHOD_CALL'], color: '#38bdf8' },
  { key: 'creates', label: 'Creates (new)', types: ['CONSTRUCTOR_CALL'], color: '#34d399' },
  { key: 'fields', label: 'Field access', types: ['FIELD_ACCESS'], color: '#fbbf24' },
  {
    key: 'types',
    label: 'Type usage',
    types: ['FIELD_TYPE', 'PARAMETER_TYPE', 'RETURN_TYPE', 'ANNOTATION'],
    color: '#94a3b8',
  },
  { key: 'imports', label: 'Imports', types: ['IMPORT'], color: '#64748b', dashed: true },
];

/** Class/package views show everything but imports; the dense member view starts with behavioural edges. */
function defaultGroups(view: GraphViewMode): Set<string> {
  const keys = view === 'MEMBER' ? ['inherits', 'calls', 'creates'] : ['inherits', 'calls', 'creates', 'fields', 'types'];
  return new Set(keys);
}

const GROUP_OF_TYPE: Record<string, RelationshipGroup> = {};
RELATIONSHIP_GROUPS.forEach((g) => g.types.forEach((t) => (GROUP_OF_TYPE[t] = g)));

const COLOR_USES = '#22d3ee'; // selected → dependency
const COLOR_USED_BY = '#fb923c'; // dependent → selected
const COLOR_BLAST = '#f43f5e';

const PACKAGE_PALETTE = [
  '#38bdf8', '#a78bfa', '#34d399', '#fbbf24', '#f472b6', '#60a5fa',
  '#4ade80', '#fb923c', '#2dd4bf', '#e879f9', '#facc15', '#818cf8',
];

function packageColor(pkg: string | undefined): string {
  const key = pkg || '';
  let hash = 0;
  for (let i = 0; i < key.length; i++) hash = (hash * 31 + key.charCodeAt(i)) | 0;
  return PACKAGE_PALETTE[Math.abs(hash) % PACKAGE_PALETTE.length];
}

function edgeTypes(e: GraphEdgeView): string[] {
  return e.types && e.types.length ? e.types : [e.type];
}

function shortPackage(pkg: string | undefined): string {
  if (!pkg) return '(default)';
  const parts = pkg.split('.');
  return parts.length > 2 ? '…' + parts.slice(-2).join('.') : pkg;
}

function formatType(t: string): string {
  return t.toLowerCase().replace(/_/g, ' ');
}

// ---------------------------------------------------------------------------
// Custom node
// ---------------------------------------------------------------------------

type HighlightState = 'normal' | 'selected' | 'uses' | 'usedBy' | 'both' | 'blast' | 'dim';

interface SymbolNodeData extends Record<string, unknown> {
  raw: GraphNodeView;
  state: HighlightState;
  horizontal: boolean;
  view: GraphViewMode;
  onSelect?: (id: string) => void;
}

const NODE_WIDTH = 230;
const NODE_HEIGHT = 66;

const STATE_STYLES: Record<HighlightState, { border: string; shadow: string; opacity: number; bg: string }> = {
  normal: { border: '#334155', shadow: 'none', opacity: 1, bg: '#0b1220' },
  selected: { border: '#22d3ee', shadow: '0 0 0 3px rgba(34,211,238,0.35), 0 0 24px rgba(34,211,238,0.35)', opacity: 1, bg: '#082f49' },
  uses: { border: COLOR_USES, shadow: '0 0 14px rgba(34,211,238,0.25)', opacity: 1, bg: '#0b1220' },
  usedBy: { border: COLOR_USED_BY, shadow: '0 0 14px rgba(251,146,60,0.25)', opacity: 1, bg: '#0b1220' },
  both: { border: '#e2e8f0', shadow: '0 0 14px rgba(226,232,240,0.2)', opacity: 1, bg: '#0b1220' },
  blast: { border: COLOR_BLAST, shadow: '0 0 18px rgba(244,63,94,0.4)', opacity: 1, bg: '#2a0a14' },
  dim: { border: '#1e293b', shadow: 'none', opacity: 0.18, bg: '#0b1220' },
};

const SymbolNode: React.FC<NodeProps<Node<SymbolNodeData>>> = ({ id, data }) => {
  const { raw, state, horizontal, view, onSelect } = data;
  const st = STATE_STYLES[state];
  const accent = packageColor(raw.packageName);
  const targetPos = horizontal ? Position.Left : Position.Top;
  const sourcePos = horizontal ? Position.Right : Position.Bottom;
  const memberLabel = view === 'PACKAGE' ? 'types' : 'members';

  return (
    <div
      onClick={() => {
        onSelect?.(id);
      }}
      className="rounded-xl text-left font-sans select-none transition-shadow duration-150"
      style={{
        width: NODE_WIDTH,
        height: NODE_HEIGHT,
        background: st.bg,
        borderTop: `1.5px solid ${st.border}`,
        borderRight: `1.5px solid ${st.border}`,
        borderBottom: `1.5px solid ${st.border}`,
        borderLeft: `5px solid ${accent}`,
        boxShadow: st.shadow,
        opacity: st.opacity,
        padding: '7px 10px 6px 10px',
      }}
      title={raw.qualifiedName}
    >
      <Handle type="target" position={targetPos} style={{ opacity: 0, pointerEvents: 'none' }} />
      <div className="flex items-center gap-1.5">
        <span className={`text-[8.5px] font-bold px-1 py-px rounded tracking-wider uppercase shrink-0 ${typeBadgeClass(raw.type)}`}>
          {raw.type === 'CONSTRUCTOR' ? 'CTOR' : raw.type}
        </span>
        {raw.testCode && (
          <span className="text-[8.5px] font-bold px-1 py-px rounded bg-slate-800 text-slate-400 border border-slate-700">TEST</span>
        )}
        <span className="font-bold text-[12.5px] text-white truncate">{raw.label}</span>
      </div>
      <div className="text-[10px] text-slate-400 font-mono truncate mt-0.5">{shortPackage(raw.packageName)}</div>
      <div className="flex items-center justify-between text-[10px] mt-1 text-slate-500">
        <span className="flex items-center gap-2">
          <span title="Used by (incoming dependencies)" className="flex items-center gap-0.5">
            <ArrowDownLeft className="w-3 h-3" style={{ color: COLOR_USED_BY }} />
            {raw.inDegree ?? 0}
          </span>
          <span title="Uses (outgoing dependencies)" className="flex items-center gap-0.5">
            <ArrowUpRight className="w-3 h-3" style={{ color: COLOR_USES }} />
            {raw.outDegree ?? 0}
          </span>
        </span>
        {!!raw.memberCount && view !== 'MEMBER' && (
          <span>
            {raw.memberCount} {memberLabel}
          </span>
        )}
      </div>
      <Handle type="source" position={sourcePos} style={{ opacity: 0, pointerEvents: 'none' }} />
    </div>
  );
};

const nodeTypes = { symbol: SymbolNode };

// ---------------------------------------------------------------------------
// Layout: dagre for connected nodes, compact grid below for unconnected ones.
// ---------------------------------------------------------------------------

function computeLayout(
  nodes: GraphNodeView[],
  edges: GraphEdgeView[],
  horizontal: boolean
): Record<string, { x: number; y: number }> {
  const positions: Record<string, { x: number; y: number }> = {};
  const connected = new Set<string>();
  edges.forEach((e) => {
    connected.add(e.source);
    connected.add(e.target);
  });

  const g = new dagre.graphlib.Graph();
  g.setDefaultEdgeLabel(() => ({}));
  g.setGraph({
    rankdir: horizontal ? 'LR' : 'TB',
    nodesep: horizontal ? 22 : 36,
    ranksep: horizontal ? 120 : 80,
    marginx: 30,
    marginy: 30,
    ranker: 'network-simplex',
  });
  nodes.filter((n) => connected.has(n.id)).forEach((n) => g.setNode(n.id, { width: NODE_WIDTH, height: NODE_HEIGHT }));
  edges.forEach((e) => g.setEdge(e.source, e.target, { weight: Math.min(e.count ?? 1, 5) }));
  dagre.layout(g);

  let maxY = 0;
  let maxX = 0;
  g.nodes().forEach((id) => {
    const p = g.node(id);
    if (!p) return;
    positions[id] = { x: p.x - NODE_WIDTH / 2, y: p.y - NODE_HEIGHT / 2 };
    maxY = Math.max(maxY, p.y + NODE_HEIGHT / 2);
    maxX = Math.max(maxX, p.x + NODE_WIDTH / 2);
  });

  const isolated = nodes.filter((n) => !connected.has(n.id));
  if (isolated.length) {
    const cols = Math.max(1, Math.min(isolated.length, Math.floor(Math.max(maxX, 1200) / (NODE_WIDTH + 20))));
    const startY = connected.size ? maxY + 90 : 30;
    isolated.forEach((n, i) => {
      positions[n.id] = {
        x: 30 + (i % cols) * (NODE_WIDTH + 20),
        y: startY + Math.floor(i / cols) * (NODE_HEIGHT + 20),
      };
    });
  }
  return positions;
}

/**
 * 1-hop focus layout: dependents (who uses the selection) on the left, the selection in the middle and
 * its dependencies on the right. Reads like "callers → me → callees" without edges crossing the centre.
 */
function computeEgoLayout(
  selectedId: string,
  nodes: GraphNodeView[],
  edges: GraphEdgeView[],
  horizontal: boolean
): Record<string, { x: number; y: number }> {
  const usedBy: GraphNodeView[] = [];
  const uses: GraphNodeView[] = [];
  const outgoing = new Set(edges.filter((e) => e.source === selectedId).map((e) => e.target));
  const incoming = new Set(edges.filter((e) => e.target === selectedId).map((e) => e.source));
  nodes.forEach((n) => {
    if (n.id === selectedId) return;
    if (outgoing.has(n.id)) uses.push(n);
    else if (incoming.has(n.id)) usedBy.push(n);
    else uses.push(n);
  });
  const byName = (a: GraphNodeView, b: GraphNodeView) => a.label.localeCompare(b.label);
  usedBy.sort(byName);
  uses.sort(byName);

  const positions: Record<string, { x: number; y: number }> = {};
  const colGap = NODE_WIDTH + 170;
  const rowGap = NODE_HEIGHT + 18;
  const place = (list: GraphNodeView[], col: number) =>
    list.forEach((n, i) => {
      const offset = (i - (list.length - 1) / 2) * (horizontal ? rowGap : NODE_WIDTH + 20);
      positions[n.id] = horizontal ? { x: col * colGap, y: offset } : { x: offset, y: col * (NODE_HEIGHT + 150) };
    });
  place(usedBy, -1);
  positions[selectedId] = { x: 0, y: 0 };
  place(uses, 1);
  return positions;
}

// ---------------------------------------------------------------------------
// Explorer
// ---------------------------------------------------------------------------

interface Props {
  repositoryId: string;
}

type FocusMode = 'highlight' | 'neighborhood';

const VIEW_LABELS: Record<GraphViewMode, { label: string; noun: string; singular: string }> = {
  PACKAGE: { label: 'Packages', noun: 'packages', singular: 'package' },
  CLASS: { label: 'Classes', noun: 'classes', singular: 'class' },
  MEMBER: { label: 'Methods & fields', noun: 'symbols', singular: 'symbol' },
};

/** Initial fit keeps labels legible; the Fit button and minimap still give the full overview. */
const READABLE_FIT = { padding: 0.12, minZoom: 0.62, duration: 300 };

export const DependencyGraphExplorer: React.FC<Props> = ({ repositoryId }) => {
  const [view, setView] = useState<GraphViewMode>('CLASS');
  const [snapshot, setSnapshot] = useState<GraphSnapshot | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const [packageFilter, setPackageFilter] = useState('');
  const [search, setSearch] = useState('');
  const [searchOpen, setSearchOpen] = useState(false);
  const [hideTests, setHideTests] = useState(true);
  const [hideIsolated, setHideIsolated] = useState(true);
  const [horizontal, setHorizontal] = useState(true);
  const [enabledGroups, setEnabledGroups] = useState<Set<string>>(() => defaultGroups('CLASS'));

  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [focusMode, setFocusMode] = useState<FocusMode>('highlight');
  const [focusDepth, setFocusDepth] = useState(1);
  const [hoveredEdge, setHoveredEdge] = useState<GraphEdgeView | null>(null);

  const [blast, setBlast] = useState<{ ids: Set<string>; symbols: TraversalNode[]; depth: number } | null>(null);
  const [blastDepth, setBlastDepth] = useState(3);
  const [loadingBlast, setLoadingBlast] = useState(false);

  const [rf, setRf] = useState<ReactFlowInstance<Node<SymbolNodeData>, Edge> | null>(null);

  const fetchGraph = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      setSnapshot(await codeApi.getGraphSnapshot(repositoryId, view));
    } catch (e) {
      console.error('Failed to load dependency graph', e);
      setError('Could not load the dependency graph.');
    } finally {
      setLoading(false);
    }
  }, [repositoryId, view]);

  useEffect(() => {
    fetchGraph();
  }, [fetchGraph]);

  // Reset selection whenever the view changes (node ids differ per view).
  useEffect(() => {
    setSelectedId(null);
    setBlast(null);
    setFocusMode('highlight');
    setEnabledGroups(defaultGroups(view));
  }, [view]);

  const nodeById = useMemo(() => {
    const m = new Map<string, GraphNodeView>();
    snapshot?.nodes.forEach((n) => m.set(n.id, n));
    return m;
  }, [snapshot]);

  const packages = useMemo(() => {
    const s = new Set<string>();
    snapshot?.nodes.forEach((n) => n.packageName && s.add(n.packageName));
    return Array.from(s).sort();
  }, [snapshot]);

  // 1) Node filters (tests, package) ------------------------------------------------
  const baseNodes = useMemo(() => {
    if (!snapshot) return [] as GraphNodeView[];
    return snapshot.nodes.filter(
      (n) =>
        (!hideTests || !n.testCode) &&
        (!packageFilter || (n.packageName ?? '').startsWith(packageFilter) || n.qualifiedName.startsWith(packageFilter))
    );
  }, [snapshot, hideTests, packageFilter]);

  const baseNodeIds = useMemo(() => new Set(baseNodes.map((n) => n.id)), [baseNodes]);

  const baseEdges = useMemo(
    () => (snapshot?.edges ?? []).filter((e) => baseNodeIds.has(e.source) && baseNodeIds.has(e.target)),
    [snapshot, baseNodeIds]
  );

  const groupCounts = useMemo(() => {
    const counts: Record<string, number> = {};
    baseEdges.forEach((e) => {
      const seen = new Set<string>();
      edgeTypes(e).forEach((t) => {
        const g = GROUP_OF_TYPE[t];
        if (g && !seen.has(g.key)) {
          seen.add(g.key);
          counts[g.key] = (counts[g.key] ?? 0) + 1;
        }
      });
    });
    return counts;
  }, [baseEdges]);

  // 2) Relationship filter ------------------------------------------------------------
  const relEdges = useMemo(
    () => baseEdges.filter((e) => edgeTypes(e).some((t) => enabledGroups.has(GROUP_OF_TYPE[t]?.key ?? ''))),
    [baseEdges, enabledGroups]
  );

  // 3) Neighbourhood focus + isolated-node hiding --------------------------------------
  const { visibleNodes, visibleEdges, isolatedCount } = useMemo(() => {
    let nodes = baseNodes;
    let edges = relEdges;

    if (focusMode === 'neighborhood' && selectedId && baseNodeIds.has(selectedId)) {
      const keep = new Set<string>([selectedId]);
      let frontier = new Set<string>([selectedId]);
      for (let d = 0; d < focusDepth; d++) {
        const next = new Set<string>();
        relEdges.forEach((e) => {
          if (frontier.has(e.source) && !keep.has(e.target)) next.add(e.target);
          if (frontier.has(e.target) && !keep.has(e.source)) next.add(e.source);
        });
        next.forEach((id) => keep.add(id));
        frontier = next;
      }
      nodes = nodes.filter((n) => keep.has(n.id));
      edges = edges.filter((e) => keep.has(e.source) && keep.has(e.target));
    }

    const connected = new Set<string>();
    edges.forEach((e) => {
      connected.add(e.source);
      connected.add(e.target);
    });
    const isolated = nodes.filter((n) => !connected.has(n.id) && n.id !== selectedId).length;
    if (hideIsolated) {
      nodes = nodes.filter((n) => connected.has(n.id) || n.id === selectedId);
    }
    return { visibleNodes: nodes, visibleEdges: edges, isolatedCount: isolated };
  }, [baseNodes, relEdges, baseNodeIds, focusMode, selectedId, focusDepth, hideIsolated]);

  // 4) Layout (independent of highlighting so selection never reshuffles the graph) ----
  const egoLayout = focusMode === 'neighborhood' && focusDepth === 1 && !!selectedId;
  const positions = useMemo(
    () =>
      egoLayout && selectedId
        ? computeEgoLayout(selectedId, visibleNodes, visibleEdges, horizontal)
        : computeLayout(visibleNodes, visibleEdges, horizontal),
    // selectedId only matters for the ego layout; otherwise selection must not trigger a re-layout
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [visibleNodes, visibleEdges, horizontal, egoLayout, egoLayout ? selectedId : null]
  );

  // Canvas bounds: the layout plus a margin. Panning stops there and nodes cannot be dragged beyond it, so the graph
  // can never be lost in endless empty space.
  const extent = useMemo((): [[number, number], [number, number]] | undefined => {
    const ps = Object.values(positions);
    if (!ps.length) return undefined;
    const pad = 320;
    const minX = Math.min(...ps.map((p) => p.x)), minY = Math.min(...ps.map((p) => p.y));
    const maxX = Math.max(...ps.map((p) => p.x)) + NODE_WIDTH, maxY = Math.max(...ps.map((p) => p.y)) + NODE_HEIGHT;
    return [[minX - pad, minY - pad], [maxX + pad, maxY + pad]];
  }, [positions]);

  // Neighbours of the selection inside the current relationship filter.
  const { usesIds, usedByIds } = useMemo(() => {
    const uses = new Set<string>();
    const usedBy = new Set<string>();
    if (selectedId) {
      relEdges.forEach((e) => {
        if (e.source === selectedId) uses.add(e.target);
        if (e.target === selectedId) usedBy.add(e.source);
      });
    }
    return { usesIds: uses, usedByIds: usedBy };
  }, [relEdges, selectedId]);

  const selectNode = useCallback(
    (id: string | null, center = false) => {
      setSelectedId(id);
      setBlast(null);
      // The member graph is too dense to read whole: walking it neighbourhood by neighbourhood is.
      if (id && view === 'MEMBER') setFocusMode('neighborhood');
      if (id && center && rf) {
        // Make sure the node is visible under the current filters before centering.
        const n = nodeById.get(id);
        if (n?.testCode) setHideTests(false);
        if (n && packageFilter && !(n.packageName ?? '').startsWith(packageFilter)) setPackageFilter('');
        setTimeout(() => {
          const p = rf.getNode(id);
          if (p) rf.setCenter(p.position.x + NODE_WIDTH / 2, p.position.y + NODE_HEIGHT / 2, { zoom: 1.1, duration: 500 });
        }, 120);
      }
    },
    [rf, nodeById, packageFilter, view]
  );

  // 5) React Flow nodes & edges --------------------------------------------------------
  const styledNodes: Node<SymbolNodeData>[] = useMemo(
    () =>
      visibleNodes.map((n) => {
        let state: HighlightState = 'normal';
        if (blast) {
          state = n.id === selectedId ? 'selected' : blast.ids.has(n.id) ? 'blast' : 'dim';
        } else if (selectedId) {
          if (n.id === selectedId) state = 'selected';
          else if (usesIds.has(n.id) && usedByIds.has(n.id)) state = 'both';
          else if (usesIds.has(n.id)) state = 'uses';
          else if (usedByIds.has(n.id)) state = 'usedBy';
          else state = 'dim';
        }
        return {
          id: n.id,
          type: 'symbol',
          position: positions[n.id] ?? { x: 0, y: 0 },
          data: {
            raw: n,
            state,
            horizontal,
            view,
            onSelect: (id: string) => selectNode(id),
          },
          draggable: true,
        };
      }),
    [visibleNodes, positions, selectedId, usesIds, usedByIds, blast, horizontal, view, selectNode]
  );

  // React Flow v12 measures nodes through onNodesChange, so nodes must live in React Flow state.
  // Highlight-only updates keep the user's dragged positions; a new layout resets them.
  const [rfNodes, setRfNodes, onNodesChange] = useNodesState<Node<SymbolNodeData>>([]);
  const lastLayout = useRef(positions);
  useEffect(() => {
    const layoutChanged = lastLayout.current !== positions;
    lastLayout.current = positions;
    setRfNodes((prev) => {
      const prevById = new Map(prev.map((n) => [n.id, n]));
      return styledNodes.map((n) => {
        const old = prevById.get(n.id);
        if (!old) return n;
        return { ...n, measured: old.measured, position: layoutChanged ? n.position : old.position };
      });
    });
  }, [styledNodes, positions, setRfNodes]);

  // A new view never starts from the previous view's nodes: React Flow draws an edge only after both of its nodes
  // were measured, and replacing every node of a mounted instance at once sometimes left them unmeasured, so the
  // graph showed nodes without lines. Clearing here plus a fresh instance per view (key below) avoids that.
  useEffect(() => {
    setRfNodes([]);
  }, [view, setRfNodes]);

  const rfEdges: Edge[] = useMemo(
    () =>
      visibleEdges.map((e) => {
        const kinds = edgeTypes(e);
        const group =
          RELATIONSHIP_GROUPS.find((g) => enabledGroups.has(g.key) && g.types.some((t) => kinds.includes(t))) ??
          RELATIONSHIP_GROUPS[1];
        const count = e.count ?? 1;
        const baseWidth = Math.min(1 + Math.log2(count), 4.5);

        let stroke = group.color;
        let opacity = selectedId || blast ? 0.07 : 0.55;
        let width = baseWidth;
        let animated = false;
        let label: string | undefined;

        if (blast) {
          if (blast.ids.has(e.source) && (blast.ids.has(e.target) || e.target === selectedId)) {
            stroke = COLOR_BLAST;
            opacity = 0.9;
            width = baseWidth + 1;
            animated = true;
          }
        } else if (selectedId && (e.source === selectedId || e.target === selectedId)) {
          stroke = e.source === selectedId ? COLOR_USES : COLOR_USED_BY;
          opacity = 1;
          width = baseWidth + 1;
          animated = true;
          label = `${count}× ${kinds.map(formatType).join(', ')}`;
        }
        if (hoveredEdge?.id === e.id) {
          opacity = 1;
          width += 1;
        }

        return {
          id: e.id,
          source: e.source,
          target: e.target,
          type: 'default',
          animated,
          label,
          labelStyle: { fill: '#e2e8f0', fontSize: 10, fontWeight: 600 },
          labelBgStyle: { fill: '#0f172a', fillOpacity: 0.92 },
          labelBgPadding: [4, 2] as [number, number],
          labelBgBorderRadius: 4,
          style: {
            stroke,
            strokeWidth: width,
            opacity,
            strokeDasharray: group.dashed && !animated ? '5 4' : undefined,
          },
          markerEnd: { type: MarkerType.ArrowClosed, color: stroke, width: 16, height: 16 },
          data: { raw: e },
        } as Edge;
      }),
    [visibleEdges, enabledGroups, selectedId, blast, hoveredEdge]
  );

  // Fit the view when the visible structure changes (not on highlight changes).
  useEffect(() => {
    if (!rf) return;
    // Small graphs (e.g. a focused neighbourhood) always fit entirely; large ones keep labels legible.
    const options = Object.keys(positions).length > 30 ? READABLE_FIT : { ...READABLE_FIT, minZoom: 0.2 };
    const t = setTimeout(() => rf.fitView(options), 60);
    return () => clearTimeout(t);
  }, [rf, positions]);

  // Search -------------------------------------------------------------------------------
  const searchResults = useMemo(() => {
    const q = search.trim().toLowerCase();
    if (!q || !snapshot) return [];
    return snapshot.nodes
      .filter((n) => n.label.toLowerCase().includes(q) || n.qualifiedName.toLowerCase().includes(q))
      .sort((a, b) => (b.inDegree ?? 0) + (b.outDegree ?? 0) - ((a.inDegree ?? 0) + (a.outDegree ?? 0)))
      .slice(0, 12);
  }, [search, snapshot]);

  const selected = selectedId ? nodeById.get(selectedId) ?? null : null;

  // Inspector neighbour lists (with relationship kinds and reference counts) -------------
  const neighbourRows = useMemo(() => {
    const uses: { node: GraphNodeView; edge: GraphEdgeView }[] = [];
    const usedBy: { node: GraphNodeView; edge: GraphEdgeView }[] = [];
    if (selectedId && snapshot) {
      relEdges.forEach((e) => {
        if (e.source === selectedId) {
          const n = nodeById.get(e.target);
          if (n) uses.push({ node: n, edge: e });
        }
        if (e.target === selectedId) {
          const n = nodeById.get(e.source);
          if (n) usedBy.push({ node: n, edge: e });
        }
      });
    }
    const byCount = (a: { edge: GraphEdgeView }, b: { edge: GraphEdgeView }) => (b.edge.count ?? 1) - (a.edge.count ?? 1);
    return { uses: uses.sort(byCount), usedBy: usedBy.sort(byCount) };
  }, [selectedId, snapshot, relEdges, nodeById]);

  // Blast radius ------------------------------------------------------------------------
  const computeBlastRadius = async () => {
    if (!selected || view === 'PACKAGE') return;
    setLoadingBlast(true);
    try {
      const dependents = await codeApi.getDependents(selected.id, blastDepth);
      const ids = new Set<string>();
      dependents.forEach((d) => ids.add(view === 'CLASS' ? d.ownerTypeId ?? d.id : d.id));
      ids.delete(selected.id);
      setBlast({ ids, symbols: dependents, depth: blastDepth });
    } catch (e) {
      console.error('Failed to calculate blast radius', e);
    } finally {
      setLoadingBlast(false);
    }
  };

  const toggleGroup = (key: string) =>
    setEnabledGroups((prev) => {
      const next = new Set(prev);
      if (next.has(key)) next.delete(key);
      else next.add(key);
      return next;
    });

  const noun = VIEW_LABELS[view].noun;
  const totalRefs = visibleEdges.reduce((s, e) => s + (e.count ?? 1), 0);
  const blastClasses = blast ? new Set(blast.symbols.map((s) => s.ownerTypeId ?? s.id)).size : 0;
  const blastTests = blast ? blast.symbols.filter((s) => s.testCode).length : 0;

  return (
    <div className="space-y-3">
      {/* ------------------------------------------------------------------ Toolbar */}
      <div className="bg-slate-900 border border-slate-800 p-3 rounded-2xl shadow-xl space-y-3">
        <div className="flex flex-wrap items-center gap-3">
          {/* View level */}
          <div className="flex items-center bg-slate-950 p-1 rounded-xl border border-slate-800">
            {(['PACKAGE', 'CLASS', 'MEMBER'] as GraphViewMode[]).map((v) => {
              const Icon = v === 'PACKAGE' ? FolderTree : v === 'CLASS' ? Box : Layers;
              return (
                <button
                  key={v}
                  type="button"
                  onClick={() => setView(v)}
                  className={`px-3 py-1.5 text-xs font-semibold rounded-lg flex items-center gap-1.5 transition cursor-pointer ${view === v ? 'bg-cyan-500 text-slate-950 shadow' : 'text-slate-400 hover:text-white'
                    }`}
                >
                  <Icon className="w-3.5 h-3.5" />
                  {VIEW_LABELS[v].label}
                </button>
              );
            })}
          </div>

          {/* Search → focus */}
          <div className="relative flex-1 min-w-[220px] max-w-sm">
            <Search className="w-3.5 h-3.5 absolute left-3 top-2.5 text-slate-500" />
            <input
              type="text"
              placeholder={`Find ${VIEW_LABELS[view].noun} and focus…`}
              value={search}
              onChange={(e) => {
                setSearch(e.target.value);
                setSearchOpen(true);
              }}
              onFocus={() => setSearchOpen(true)}
              onBlur={() => setTimeout(() => setSearchOpen(false), 150)}
              className="w-full bg-slate-950 border border-slate-800 rounded-xl pl-8 pr-3 py-1.5 text-xs text-slate-100 placeholder-slate-600 focus:outline-none focus:border-cyan-500"
            />
            {searchOpen && searchResults.length > 0 && (
              <div className="absolute z-20 mt-1 w-full bg-slate-900 border border-slate-700 rounded-xl shadow-2xl overflow-hidden">
                {searchResults.map((n) => (
                  <button
                    key={n.id}
                    type="button"
                    onMouseDown={() => {
                      selectNode(n.id, true);
                      setSearch('');
                      setSearchOpen(false);
                    }}
                    className="w-full text-left px-3 py-1.5 hover:bg-slate-800 flex items-center justify-between gap-2"
                  >
                    <span className="truncate text-xs text-slate-100 font-mono">{n.label}</span>
                    <span className="text-[10px] text-slate-500 truncate">{shortPackage(n.packageName)}</span>
                  </button>
                ))}
              </div>
            )}
          </div>

          {/* Package filter */}
          {packages.length > 1 && (
            <select
              value={packageFilter}
              onChange={(e) => setPackageFilter(e.target.value)}
              className="bg-slate-950 border border-slate-800 rounded-xl px-2.5 py-1.5 text-xs text-slate-300 focus:outline-none focus:border-cyan-500 max-w-[220px]"
            >
              <option value="">All packages ({packages.length})</option>
              {packages.map((p) => (
                <option key={p} value={p}>
                  {p}
                </option>
              ))}
            </select>
          )}

          <div className="flex items-center gap-1 ml-auto">
            <button
              type="button"
              onClick={() => setHorizontal((h) => !h)}
              className="px-2.5 py-1.5 rounded-lg text-xs text-slate-300 bg-slate-950 border border-slate-800 hover:border-slate-600 flex items-center gap-1 cursor-pointer"
              title="Toggle layout direction"
            >
              {horizontal ? <ArrowRight className="w-3.5 h-3.5" /> : <ArrowDown className="w-3.5 h-3.5" />}
              {horizontal ? 'Left → right' : 'Top → down'}
            </button>
            <button
              type="button"
              onClick={() => rf?.fitView({ padding: 0.15, duration: 300 })}
              className="px-2.5 py-1.5 rounded-lg text-xs text-slate-300 bg-slate-950 border border-slate-800 hover:border-slate-600 flex items-center gap-1 cursor-pointer"
            >
              <Maximize2 className="w-3.5 h-3.5" /> Fit
            </button>
          </div>
        </div>

        {/* Relationship chips + visibility toggles */}
        <div className="flex flex-wrap items-center gap-1.5">
          <span className="text-[10px] uppercase tracking-wider text-slate-500 mr-1">Show</span>
          {RELATIONSHIP_GROUPS.map((g) => {
            const on = enabledGroups.has(g.key);
            const count = groupCounts[g.key] ?? 0;
            return (
              <button
                key={g.key}
                type="button"
                onClick={() => toggleGroup(g.key)}
                disabled={count === 0}
                className={`px-2 py-1 rounded-lg text-[11px] flex items-center gap-1.5 border transition cursor-pointer disabled:cursor-not-allowed disabled:opacity-30 ${on ? 'bg-slate-800 border-slate-600 text-slate-100' : 'bg-transparent border-slate-800 text-slate-500 line-through'
                  }`}
                title={g.types.join(', ')}
              >
                <span
                  className="inline-block w-4 h-0"
                  style={{ borderTop: `2.5px ${g.dashed ? 'dashed' : 'solid'} ${g.color}`, opacity: on ? 1 : 0.4 }}
                />
                {g.label}
                <span className="text-slate-500">{count}</span>
              </button>
            );
          })}
          <span className="w-px h-5 bg-slate-800 mx-1" />
          <button
            type="button"
            onClick={() => setHideTests((v) => !v)}
            className={`px-2 py-1 rounded-lg text-[11px] flex items-center gap-1 border cursor-pointer ${hideTests ? 'border-slate-800 text-slate-400' : 'border-slate-600 bg-slate-800 text-slate-100'
              }`}
          >
            {hideTests ? <EyeOff className="w-3 h-3" /> : <Eye className="w-3 h-3" />}
            {hideTests ? 'Test code hidden' : 'Test code shown'}
          </button>
          <button
            type="button"
            onClick={() => setHideIsolated((v) => !v)}
            className={`px-2 py-1 rounded-lg text-[11px] flex items-center gap-1 border cursor-pointer ${hideIsolated ? 'border-slate-800 text-slate-400' : 'border-slate-600 bg-slate-800 text-slate-100'
              }`}
          >
            {hideIsolated ? <EyeOff className="w-3 h-3" /> : <Eye className="w-3 h-3" />}
            {hideIsolated ? `${isolatedCount} unconnected hidden` : 'Unconnected shown'}
          </button>
        </div>
      </div>

      {/* ------------------------------------------------------------------ Canvas + inspector */}
      <div className="grid grid-cols-1 lg:grid-cols-4 gap-3">
        <div className="lg:col-span-3 h-[720px] bg-slate-950 border border-slate-800 rounded-2xl overflow-hidden relative shadow-2xl">
          {loading ? (
            <div className="h-full flex flex-col items-center justify-center text-slate-500 text-sm">
              <div className="w-8 h-8 border-2 border-cyan-400 border-t-transparent rounded-full animate-spin mb-3" />
              Building dependency graph…
            </div>
          ) : error ? (
            <div className="h-full flex flex-col items-center justify-center text-rose-400 text-sm">{error}</div>
          ) : styledNodes.length === 0 ? (
            <div className="h-full flex flex-col items-center justify-center text-slate-500 text-sm p-6 text-center">
              <Layers className="w-12 h-12 text-slate-700 mb-3" />
              <p className="font-semibold text-slate-300">Nothing to show with the current filters</p>
              <p className="text-xs text-slate-500 mt-1 max-w-sm">
                Enable more relationship kinds, show unconnected {noun} or test code, or clear the package filter.
              </p>
            </div>
          ) : (
            <ReactFlow<Node<SymbolNodeData>, Edge>
              key={view}
              colorMode="dark"
              nodes={rfNodes}
              edges={rfEdges}
              onNodesChange={onNodesChange}
              nodeTypes={nodeTypes}
              onInit={setRf}
              onNodeClick={(_, n) => selectNode(n.id)}
              onPaneClick={() => selectNode(null)}
              onEdgeMouseEnter={(_, e) => setHoveredEdge((e.data as { raw: GraphEdgeView }).raw)}
              onEdgeMouseLeave={() => setHoveredEdge(null)}
              nodesConnectable={false}
              nodesDraggable={true}
              className="dependency-graph"
              minZoom={0.15}
              maxZoom={2.5}
              translateExtent={extent}
              nodeExtent={extent}
              fitView
              // NOTE: no `duration` here - an animated *initial* fit prevents @xyflow/react 12.12 from
              // rendering edges. Animated fits are only used for later, imperative fitView calls.
              fitViewOptions={{ padding: READABLE_FIT.padding, minZoom: READABLE_FIT.minZoom }}
            >
              <Background color="#1e293b" gap={24} size={1} />
              <Controls showInteractive={false} className="!bg-slate-900 !border-slate-800" />
              <MiniMap
                pannable
                zoomable
                nodeColor={(n) => {
                  const d = n.data as SymbolNodeData;
                  if (d.state === 'selected') return '#22d3ee';
                  if (d.state === 'blast') return COLOR_BLAST;
                  if (d.state === 'uses') return COLOR_USES;
                  if (d.state === 'usedBy') return COLOR_USED_BY;
                  return packageColor(d.raw.packageName);
                }}
                maskColor="rgba(2, 6, 23, 0.7)"
                style={{ background: '#0f172a', border: '1px solid #1e293b', borderRadius: 12 }}
              />

              <Panel position="top-left">
                <div className="bg-slate-900/90 backdrop-blur border border-slate-800 px-3 py-1.5 rounded-xl text-xs text-slate-400 flex items-center gap-2 shadow-lg">
                  <span>
                    <strong className="text-white">{styledNodes.length}</strong> {noun}
                  </span>
                  <span>·</span>
                  <span>
                    <strong className="text-white">{rfEdges.length}</strong> connections
                  </span>
                  <span className="text-slate-500">({totalRefs} code references)</span>
                  {focusMode === 'neighborhood' && selected && (
                    <span className="text-cyan-400">· neighbourhood of {selected.label}</span>
                  )}
                </div>
              </Panel>

              {/* Legend */}
              <Panel position="bottom-left" style={{ marginLeft: 56 }}>
                <div className="bg-slate-900/90 backdrop-blur border border-slate-800 px-3 py-2 rounded-xl text-[10px] text-slate-400 shadow-lg space-y-1">
                  {selectedId || blast ? (
                    <>
                      <LegendLine color={COLOR_USES} label="Selected uses →" />
                      <LegendLine color={COLOR_USED_BY} label="→ uses selected (dependent)" />
                      {blast && <LegendLine color={COLOR_BLAST} label={`Blast radius (depth ${blast.depth})`} />}
                      <div className="text-slate-500">Click empty canvas to clear</div>
                    </>
                  ) : (
                    <>
                      {RELATIONSHIP_GROUPS.filter((g) => enabledGroups.has(g.key) && (groupCounts[g.key] ?? 0) > 0).map((g) => (
                        <LegendLine key={g.key} color={g.color} label={g.label} dashed={g.dashed} />
                      ))}
                      <div className="text-slate-500">Thicker line = more references · left bar colour = package</div>
                    </>
                  )}
                </div>
              </Panel>

              {!selectedId && styledNodes.length > 120 && (
                <Panel position="top-center">
                  <div className="bg-amber-500/10 border border-amber-500/40 text-amber-200 px-3 py-2 rounded-xl text-xs shadow-lg max-w-md text-center">
                    Large graph ({styledNodes.length} {noun}). Search a symbol or pick a package; selecting a node
                    shows just its neighbourhood{view === 'MEMBER' ? ' (click neighbours to walk the call graph)' : ''}.
                  </div>
                </Panel>
              )}

              {hoveredEdge && (
                <Panel position="bottom-center">
                  <div className="bg-slate-900/95 border border-slate-700 px-3 py-2 rounded-xl text-xs text-slate-200 shadow-xl max-w-xl">
                    <span className="font-mono font-semibold">{nodeById.get(hoveredEdge.source)?.label}</span>
                    <span className="text-slate-500"> → </span>
                    <span className="font-mono font-semibold">{nodeById.get(hoveredEdge.target)?.label}</span>
                    <span className="text-slate-400">
                      {' '}
                      · {hoveredEdge.count ?? 1} reference{(hoveredEdge.count ?? 1) > 1 ? 's' : ''} ·{' '}
                      {edgeTypes(hoveredEdge).map(formatType).join(', ')}
                    </span>
                  </div>
                </Panel>
              )}
            </ReactFlow>
          )}
        </div>

        {/* ---------------------------------------------------------------- Inspector */}
        <div className="bg-slate-900 border border-slate-800 rounded-2xl p-4 h-[720px] overflow-hidden shadow-2xl flex flex-col">
          <div className="flex items-center gap-2 pb-3 border-b border-slate-800">
            <Compass className="w-4 h-4 text-cyan-400" />
            <h3 className="font-bold text-white text-sm">Inspector</h3>
          </div>

          {selected ? (
            <div className="overflow-y-auto pr-1 space-y-3 text-xs pt-3">
              <div className="p-3 bg-slate-950 rounded-xl border border-slate-800" style={{ borderLeft: `4px solid ${packageColor(selected.packageName)}` }}>
                <span className={`text-[9px] font-bold px-1.5 py-0.5 rounded uppercase tracking-wider ${typeBadgeClass(selected.type)}`}>
                  {selected.type}
                </span>
                <h4 className="text-base font-bold text-white mt-1.5 break-all">{selected.label}</h4>
                <p className="text-slate-400 text-[11px] font-mono mt-0.5 break-all">{selected.qualifiedName}</p>
                {selected.sourceFile && (
                  <div className="mt-2 pt-2 border-t border-slate-900 flex items-center gap-1.5 text-slate-400 text-[11px]">
                    <FileCode className="w-3.5 h-3.5 text-amber-400 shrink-0" />
                    <span className="truncate font-mono" title={selected.sourceFile}>
                      {selected.sourceFile}:{selected.startLine}-{selected.endLine}
                    </span>
                  </div>
                )}
              </div>

              {/* Focus controls */}
              <div className="flex items-center gap-1.5">
                <button
                  type="button"
                  onClick={() => setFocusMode((m) => (m === 'neighborhood' ? 'highlight' : 'neighborhood'))}
                  className={`flex-1 py-2 px-2 rounded-xl text-xs font-semibold flex items-center justify-center gap-1.5 border cursor-pointer transition ${focusMode === 'neighborhood'
                    ? 'bg-cyan-500/15 border-cyan-500/50 text-cyan-300'
                    : 'bg-slate-950 border-slate-800 text-slate-300 hover:border-slate-600'
                    }`}
                  title="Show only this node and its direct connections"
                >
                  <Focus className="w-3.5 h-3.5" />
                  {focusMode === 'neighborhood' ? 'Showing neighbourhood' : 'Isolate neighbourhood'}
                </button>
                {focusMode === 'neighborhood' && (
                  <select
                    value={focusDepth}
                    onChange={(e) => setFocusDepth(Number(e.target.value))}
                    className="bg-slate-950 border border-slate-800 rounded-xl px-2 py-2 text-xs text-slate-300"
                    title="Hops"
                  >
                    <option value={1}>1 hop</option>
                    <option value={2}>2 hops</option>
                    <option value={3}>3 hops</option>
                  </select>
                )}
              </div>

              {view === 'PACKAGE' ? (
                <button
                  type="button"
                  onClick={() => {
                    setPackageFilter(selected.packageName ?? '');
                    setView('CLASS');
                  }}
                  className="w-full py-2 px-3 bg-slate-950 border border-slate-800 hover:border-slate-600 text-slate-200 rounded-xl text-xs font-semibold flex items-center justify-center gap-2 cursor-pointer"
                >
                  <Box className="w-3.5 h-3.5" /> Open the {selected.memberCount} classes in this package
                </button>
              ) : (
                <div className="space-y-1.5">
                  <div className="flex items-center gap-1.5">
                    <button
                      type="button"
                      onClick={computeBlastRadius}
                      disabled={loadingBlast}
                      className="flex-1 py-2 px-3 bg-rose-500/10 hover:bg-rose-500/20 text-rose-300 border border-rose-500/30 rounded-xl text-xs font-semibold flex items-center justify-center gap-2 disabled:opacity-50 cursor-pointer"
                    >
                      <Zap className="w-3.5 h-3.5" />
                      {loadingBlast ? 'Traversing…' : 'Blast radius'}
                    </button>
                    <select
                      value={blastDepth}
                      onChange={(e) => setBlastDepth(Number(e.target.value))}
                      className="bg-slate-950 border border-slate-800 rounded-xl px-2 py-2 text-xs text-slate-300"
                      title="Traversal depth"
                    >
                      {[1, 2, 3, 4, 5].map((d) => (
                        <option key={d} value={d}>
                          depth {d}
                        </option>
                      ))}
                    </select>
                  </div>
                  {blast && (
                    <div className="p-2.5 bg-rose-950/40 border border-rose-800/60 rounded-xl text-rose-200 space-y-1">
                      <div className="font-bold flex items-center gap-1.5">
                        <Zap className="w-3.5 h-3.5 text-rose-400" />
                        {blast.symbols.length} dependent symbols in {blastClasses} classes
                      </div>
                      <p className="text-[10px] text-rose-300/80">
                        Reverse traversal up to {blast.depth} hops{view === 'CLASS' ? ', including callers of this class’s members' : ''}.
                        {blastTests > 0 && ` ${blastTests} are test code${hideTests ? ' (hidden in the graph)' : ''}.`}
                      </p>
                      <button type="button" onClick={() => setBlast(null)} className="text-[10px] underline text-rose-300 cursor-pointer">
                        Clear blast radius
                      </button>
                    </div>
                  )}
                </div>
              )}

              <NeighbourList
                title="Used by"
                hint="depends on the selection"
                color={COLOR_USED_BY}
                icon={<ArrowDownLeft className="w-3.5 h-3.5" style={{ color: COLOR_USED_BY }} />}
                rows={neighbourRows.usedBy}
                onSelect={(id) => selectNode(id, true)}
              />
              <NeighbourList
                title="Uses"
                hint="the selection depends on"
                color={COLOR_USES}
                icon={<ArrowUpRight className="w-3.5 h-3.5" style={{ color: COLOR_USES }} />}
                rows={neighbourRows.uses}
                onSelect={(id) => selectNode(id, true)}
              />
            </div>
          ) : (
            <div className="text-slate-400 text-xs pt-4 space-y-4">
              <div className="text-center py-6">
                <Eye className="w-8 h-8 mx-auto mb-2 text-slate-700" />
                <p className="font-semibold text-slate-300">Select a {VIEW_LABELS[view].singular}</p>
                <p className="mt-1 text-slate-500">Click a node or use the search box.</p>
              </div>
              <ul className="space-y-2 text-[11px] text-slate-400 list-disc pl-4">
                <li>
                  <strong className="text-slate-200">Packages → Classes → Methods</strong>: start high-level and drill down.
                </li>
                <li>
                  Selecting a node highlights what it <span style={{ color: COLOR_USES }}>uses</span> and what{' '}
                  <span style={{ color: COLOR_USED_BY }}>uses it</span>; everything else fades.
                </li>
                <li>
                  <strong className="text-slate-200">Isolate neighbourhood</strong> re-lays out only the connected part for a clean read.
                </li>
                <li>Hover a line to see how many code references it represents and of which kind.</li>
              </ul>
            </div>
          )}
        </div>
      </div>
    </div>
  );
};

// ---------------------------------------------------------------------------
// Small components & helpers
// ---------------------------------------------------------------------------

const LegendLine: React.FC<{ color: string; label: string; dashed?: boolean }> = ({ color, label, dashed }) => (
  <div className="flex items-center gap-2">
    <span className="inline-block w-5 h-0" style={{ borderTop: `2.5px ${dashed ? 'dashed' : 'solid'} ${color}` }} />
    <span>{label}</span>
  </div>
);

const NeighbourList: React.FC<{
  title: string;
  hint: string;
  color: string;
  icon: React.ReactNode;
  rows: { node: GraphNodeView; edge: GraphEdgeView }[];
  onSelect: (id: string) => void;
}> = ({ title, hint, color, icon, rows, onSelect }) => (
  <div>
    <h5 className="text-[11px] font-bold uppercase tracking-wider text-slate-400 flex items-center gap-1 mb-1.5">
      {icon}
      <span style={{ color }}>{title}</span>
      <span className="text-slate-500">({rows.length})</span>
      <span className="normal-case font-normal tracking-normal text-slate-600 ml-1">{hint}</span>
    </h5>
    {rows.length === 0 ? (
      <p className="text-[11px] text-slate-600 italic">None with the current filters.</p>
    ) : (
      <div className="space-y-1 max-h-48 overflow-y-auto">
        {rows.map(({ node, edge }) => (
          <button
            key={node.id + edge.id}
            type="button"
            onClick={() => onSelect(node.id)}
            className="w-full text-left px-2 py-1.5 rounded-lg bg-slate-950/80 hover:bg-slate-800 transition group"
            style={{ borderLeft: `3px solid ${packageColor(node.packageName)}` }}
          >
            <div className="flex items-center justify-between gap-2">
              <span className="truncate font-mono text-[11px] text-slate-200 group-hover:text-white">{node.label}</span>
              <span className="text-[10px] text-slate-500 shrink-0">{edge.count ?? 1}×</span>
            </div>
            <div className="text-[9.5px] text-slate-500 truncate">{edgeTypes(edge).map(formatType).join(' · ')}</div>
          </button>
        ))}
      </div>
    )}
  </div>
);

function typeBadgeClass(type: string): string {
  switch (type) {
    case 'CLASS':
      return 'bg-blue-950/80 text-blue-300 border border-blue-800/60';
    case 'INTERFACE':
      return 'bg-purple-950/80 text-purple-300 border border-purple-800/60';
    case 'RECORD':
      return 'bg-cyan-950/80 text-cyan-300 border border-cyan-800/60';
    case 'ENUM':
      return 'bg-amber-950/80 text-amber-300 border border-amber-800/60';
    case 'METHOD':
      return 'bg-emerald-950/80 text-emerald-300 border border-emerald-800/60';
    case 'CONSTRUCTOR':
      return 'bg-teal-950/80 text-teal-300 border border-teal-800/60';
    case 'PACKAGE':
      return 'bg-sky-950/80 text-sky-300 border border-sky-800/60';
    case 'MODULE':
      return 'bg-indigo-950/80 text-indigo-300 border border-indigo-800/60';
    case 'FUNCTION':
      return 'bg-lime-950/80 text-lime-300 border border-lime-800/60';
    default:
      return 'bg-slate-800 text-slate-300 border border-slate-700';
  }
}

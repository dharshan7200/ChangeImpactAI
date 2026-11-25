import React, { useEffect, useMemo, useState } from 'react';
import {
  Background,
  Controls,
  Handle,
  MarkerType,
  Position,
  ReactFlow,
  useNodesState,
  type Edge,
  type Node,
  type NodeProps,
  type ReactFlowInstance,
} from '@xyflow/react';
import '@xyflow/react/dist/style.css';
import type { ChangedSymbolView, ImpactFindingView } from '../../types';

const COL_GAP = 290;
const ROW_GAP = 74;
const NODE_W = 220;

interface ImpactNodeData extends Record<string, unknown> {
  title: string;
  subtitle: string;
  kind: 'changed' | 'direct' | 'indirect' | 'path';
  strength?: number;
  test?: boolean;
  alsoChanged?: boolean;
  selected?: boolean;
}

const KIND_STYLE: Record<ImpactNodeData['kind'], { border: string; bg: string; label: string }> = {
  changed: { border: '#22d3ee', bg: '#082f49', label: 'CHANGED' },
  direct: { border: '#fb923c', bg: '#0b1220', label: 'DIRECT' },
  indirect: { border: '#64748b', bg: '#0b1220', label: 'INDIRECT' },
  path: { border: '#334155', bg: '#0b1220', label: 'VIA' },
};

const ImpactNode: React.FC<NodeProps<Node<ImpactNodeData>>> = ({ data }) => {
  const s = KIND_STYLE[data.kind];
  return (
    <div
      className="rounded-xl px-3 py-2 text-left"
      style={{
        width: NODE_W,
        background: s.bg,
        border: `1.5px ${data.test ? 'dashed' : 'solid'} ${data.selected ? '#e2e8f0' : s.border}`,
        boxShadow: data.selected ? '0 0 0 3px rgba(226,232,240,0.25)' : undefined,
      }}
      title={data.subtitle}
    >
      <Handle type="target" position={Position.Left} style={{ opacity: 0 }} />
      <div className="flex items-center gap-1.5 text-[9px] font-bold tracking-wider">
        <span style={{ color: s.border }}>{s.label}</span>
        {data.test && <span className="text-slate-400">TEST</span>}
        {data.alsoChanged && <span className="text-cyan-300">ALSO CHANGED</span>}
        {data.strength !== undefined && <span className="ml-auto font-mono text-slate-400">{data.strength.toFixed(2)}</span>}
      </div>
      <div className="text-[12px] font-semibold text-white truncate">{data.title}</div>
      <div className="text-[10px] text-slate-500 font-mono truncate">{data.subtitle}</div>
      <Handle type="source" position={Position.Right} style={{ opacity: 0 }} />
    </div>
  );
};

const nodeTypes = { impact: ImpactNode };

function label(qn: string): string {
  const hash = qn.indexOf('#');
  const type = hash >= 0 ? qn.substring(0, hash) : qn;
  const simpleType = type.substring(type.lastIndexOf('.') + 1);
  return hash >= 0 ? `${simpleType}.${qn.substring(hash + 1).replace(/\(.*$/, '()')}` : simpleType;
}

/**
 * Impact propagation as columns by distance: the changed symbol on the left, direct dependents next,
 * then indirect ones. Every edge comes from a stored dependency path; arrows point in the direction the
 * impact travels (towards the dependent). Line thickness follows impact strength.
 */
export const ImpactGraph: React.FC<{
  changed: ChangedSymbolView;
  findings: ImpactFindingView[];
  selectedFindingId?: string | null;
  onSelect?: (finding: ImpactFindingView) => void;
}> = ({ changed, findings, selectedFindingId, onSelect }) => {
  const { nodes, edges } = useMemo(() => {
    const top = [...findings].sort((a, b) => b.strength - a.strength).slice(0, 60);
    const byId = new Map<string, { col: number; data: ImpactNodeData; findingId?: string }>();
    const edgeMap = new Map<string, Edge>();
    const rootId = 'changed';
    byId.set(rootId, {
      col: 0,
      data: { title: label(changed.qualifiedName), subtitle: changed.qualifiedName, kind: 'changed' },
    });

    for (const f of top) {
      const path = f.dependencyPath;
      path.forEach((step, i) => {
        const id = i === 0 ? rootId : step.symbolId;
        const isTarget = i === path.length - 1;
        if (!byId.has(id) || (isTarget && byId.get(id)!.data.kind === 'path')) {
          byId.set(id, {
            col: i,
            findingId: isTarget ? f.id : byId.get(id)?.findingId,
            data: isTarget
              ? {
                  title: label(step.qualifiedName),
                  subtitle: step.qualifiedName,
                  kind: f.impactType === 'DIRECT' ? 'direct' : 'indirect',
                  strength: f.strength,
                  test: f.test,
                  alsoChanged: f.alsoChanged,
                }
              : byId.get(id)?.data ?? { title: label(step.qualifiedName), subtitle: step.qualifiedName, kind: 'path' },
          });
        }
        if (i > 0) {
          const from = i - 1 === 0 ? rootId : path[i - 1].symbolId;
          const key = `${from}>${id}`;
          if (!edgeMap.has(key)) {
            const rel = step.relationship.length ? step.relationship.map((r) => r.toLowerCase().replace(/_/g, ' ')).join(', ') : 'member';
            edgeMap.set(key, {
              id: key,
              source: from,
              target: id,
              type: 'default',
              label: rel,
              labelStyle: { fill: '#cbd5e1', fontSize: 9 },
              labelBgStyle: { fill: '#0f172a', fillOpacity: 0.9 },
              style: { stroke: f.impactType === 'DIRECT' && i === 1 ? '#fb923c' : '#64748b', strokeWidth: 1 + 2.5 * f.strength },
              markerEnd: { type: MarkerType.ArrowClosed, color: '#94a3b8', width: 14, height: 14 },
            });
          }
        }
      });
    }

    const columns = new Map<number, string[]>();
    byId.forEach((v, id) => columns.set(v.col, [...(columns.get(v.col) ?? []), id]));
    const rfNodes: Node<ImpactNodeData>[] = [];
    columns.forEach((ids, col) => {
      ids.sort((a, b) => (byId.get(b)!.data.strength ?? 2) - (byId.get(a)!.data.strength ?? 2));
      ids.forEach((id, row) => {
        const entry = byId.get(id)!;
        rfNodes.push({
          id,
          type: 'impact',
          position: { x: col * COL_GAP, y: (row - (ids.length - 1) / 2) * ROW_GAP },
          data: { ...entry.data, selected: !!entry.findingId && entry.findingId === selectedFindingId },
        });
      });
    });
    return { nodes: rfNodes, edges: [...edgeMap.values()] };
  }, [changed, findings, selectedFindingId]);

  // React Flow v12 measures nodes via onNodesChange, so they must live in React Flow state.
  const [rfNodes, setRfNodes, onNodesChange] = useNodesState<Node<ImpactNodeData>>([]);
  useEffect(() => {
    setRfNodes((prev) => {
      const measured = new Map(prev.map((n) => [n.id, n.measured]));
      return nodes.map((n) => ({ ...n, measured: measured.get(n.id) }));
    });
  }, [nodes, setRfNodes]);

  // Fit only after React Flow has measured the nodes (an initial fit would use zero-size nodes).
  const [rf, setRf] = useState<ReactFlowInstance<Node<ImpactNodeData>, Edge> | null>(null);
  useEffect(() => {
    if (!rf) return;
    const t = setTimeout(() => rf.fitView({ padding: 0.2, maxZoom: 1 }), 80);
    return () => clearTimeout(t);
  }, [rf, nodes]);

  const findingByNode = useMemo(() => {
    const m = new Map<string, ImpactFindingView>();
    findings.forEach((f) => m.set(f.dependencyPath[f.dependencyPath.length - 1]?.symbolId, f));
    return m;
  }, [findings]);

  if (findings.length === 0) {
    return (
      <div className="h-full flex items-center justify-center text-xs text-slate-500 p-6 text-center">
        No dependents found within the traversal depth: nothing in the repository references this symbol
        (static analysis). It may still be used through reflection, dependency injection or by external consumers.
      </div>
    );
  }

  return (
    <ReactFlow
      colorMode="dark"
      nodes={rfNodes}
      edges={edges}
      onNodesChange={onNodesChange}
      onInit={setRf}
      nodeTypes={nodeTypes}
      fitView
      fitViewOptions={{ padding: 0.2, maxZoom: 1 }}
      minZoom={0.1}
      nodesConnectable={false}
      nodesDraggable={false}
      onNodeClick={(_, n) => {
        const f = findingByNode.get(n.id);
        if (f && onSelect) onSelect(f);
      }}
    >
      <Background color="#1e293b" gap={24} size={1} />
      <Controls showInteractive={false} />
    </ReactFlow>
  );
};

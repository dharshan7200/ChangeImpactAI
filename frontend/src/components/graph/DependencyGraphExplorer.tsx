import React, { useState, useEffect, useCallback, useMemo } from 'react';
import {
  ReactFlow,
  Controls,
  Background,
  MiniMap,
  Panel,
  useNodesState,
  useEdgesState,
  Node,
  Edge,
  MarkerType,
} from '@xyflow/react';
import '@xyflow/react/dist/style.css';
import dagre from '@dagrejs/dagre';
import { GraphSnapshot, GraphNodeView } from '../../types';
import { codeApi } from '../../api/code';
import {
  Search,
  Zap,
  Layers,
  FileCode,
  CheckCircle2,
  Box,
  Compass,
  ArrowDownCircle,
  ArrowRightCircle,
  Grid,
  Eye,
  ArrowUpRight,
  ArrowDownLeft,
} from 'lucide-react';

interface Props {
  repositoryId: string;
}

type ViewMode = 'architecture' | 'components' | 'all';
type LayoutDirection = 'TB' | 'LR' | 'GRID';

export const DependencyGraphExplorer: React.FC<Props> = ({ repositoryId }) => {
  const [snapshot, setSnapshot] = useState<GraphSnapshot | null>(null);
  const [loading, setLoading] = useState(true);
  const [searchQuery, setSearchQuery] = useState('');
  const [selectedPackage, setSelectedPackage] = useState<string>('ALL');
  const [viewMode, setViewMode] = useState<ViewMode>('architecture');
  const [layoutDir, setLayoutDir] = useState<LayoutDirection>('TB');

  const [selectedNode, setSelectedNode] = useState<GraphNodeView | null>(null);
  const [blastRadiusNodes, setBlastRadiusNodes] = useState<Set<string>>(new Set());
  const [loadingBlast, setLoadingBlast] = useState(false);

  const [nodes, setNodes, onNodesChange] = useNodesState<Node>([]);
  const [edges, setEdges, onEdgesChange] = useEdgesState<Edge>([]);

  const fetchGraph = useCallback(async () => {
    setLoading(true);
    try {
      const data = await codeApi.getGraphSnapshot(repositoryId);
      setSnapshot(data);
    } catch (e) {
      console.error('Failed to load dependency graph snapshot', e);
    } finally {
      setLoading(false);
    }
  }, [repositoryId]);

  useEffect(() => {
    fetchGraph();
  }, [fetchGraph]);

  // Extract distinct packages from nodes
  const packages = useMemo(() => {
    if (!snapshot) return [];
    const set = new Set<string>();
    snapshot.nodes.forEach((n) => {
      const lastDot = n.qualifiedName.lastIndexOf('.');
      if (lastDot > 0) {
        set.add(n.qualifiedName.substring(0, lastDot));
      }
    });
    return Array.from(set).sort();
  }, [snapshot]);

  // Filter nodes based on view mode (architecture vs components vs all), package, and search
  const filteredData = useMemo(() => {
    if (!snapshot) return { nodes: [], edges: [] };

    let candidateNodes = snapshot.nodes;

    // View mode filtering:
    // 'architecture': only high-level classes, interfaces, records, enums
    if (viewMode === 'architecture') {
      candidateNodes = candidateNodes.filter((n) =>
        ['CLASS', 'INTERFACE', 'RECORD', 'ENUM'].includes(n.type)
      );
    } else if (viewMode === 'components') {
      candidateNodes = candidateNodes.filter((n) =>
        ['CLASS', 'INTERFACE', 'RECORD', 'METHOD'].includes(n.type)
      );
    }

    // Package filtering
    if (selectedPackage !== 'ALL') {
      candidateNodes = candidateNodes.filter((n) =>
        n.qualifiedName.startsWith(selectedPackage)
      );
    }

    // Search query filtering
    if (searchQuery.trim()) {
      const q = searchQuery.toLowerCase();
      candidateNodes = candidateNodes.filter(
        (n) =>
          n.label.toLowerCase().includes(q) ||
          n.qualifiedName.toLowerCase().includes(q) ||
          n.type.toLowerCase().includes(q)
      );
    }

    const nodeIds = new Set(candidateNodes.map((n) => n.id));

    // Keep edges where BOTH source and target are in the filtered set
    const candidateEdges = snapshot.edges.filter(
      (e) => nodeIds.has(e.source) && nodeIds.has(e.target)
    );

    return { nodes: candidateNodes, edges: candidateEdges };
  }, [snapshot, viewMode, selectedPackage, searchQuery]);

  // Find incoming & outgoing neighbors of selected node
  const { incomingNeighbors, outgoingNeighbors } = useMemo(() => {
    if (!selectedNode || !snapshot) {
      return { incomingNeighbors: [], outgoingNeighbors: [] };
    }
    const inIds = new Set<string>();
    const outIds = new Set<string>();

    snapshot.edges.forEach((e) => {
      if (e.target === selectedNode.id) inIds.add(e.source);
      if (e.source === selectedNode.id) outIds.add(e.target);
    });

    const incoming = snapshot.nodes.filter((n) => inIds.has(n.id));
    const outgoing = snapshot.nodes.filter((n) => outIds.has(n.id));

    return { incomingNeighbors: incoming, outgoingNeighbors: outgoing };
  }, [selectedNode, snapshot]);

  // Apply layout: Dagre (Hierarchical TB or LR) or Grid
  useEffect(() => {
    if (!filteredData.nodes.length) {
      setNodes([]);
      setEdges([]);
      return;
    }

    const count = filteredData.nodes.length;
    const selectedId = selectedNode?.id;
    const connectedNeighborIds = new Set<string>();
    if (selectedId) {
      connectedNeighborIds.add(selectedId);
      filteredData.edges.forEach((e) => {
        if (e.source === selectedId) connectedNeighborIds.add(e.target);
        if (e.target === selectedId) connectedNeighborIds.add(e.source);
      });
    }

    let positions: Record<string, { x: number; y: number }> = {};

    if (layoutDir === 'GRID') {
      const cols = Math.max(1, Math.ceil(Math.sqrt(count)));
      const spacingX = 260;
      const spacingY = 130;
      filteredData.nodes.forEach((n, idx) => {
        positions[n.id] = {
          x: (idx % cols) * spacingX + 40,
          y: Math.floor(idx / cols) * spacingY + 40,
        };
      });
    } else {
      // Dagre hierarchical directed layout
      const dagreGraph = new dagre.graphlib.Graph();
      dagreGraph.setDefaultEdgeLabel(() => ({}));
      const isHorizontal = layoutDir === 'LR';
      dagreGraph.setGraph({
        rankdir: layoutDir,
        nodesep: isHorizontal ? 50 : 65,
        ranksep: isHorizontal ? 120 : 85,
        marginx: 50,
        marginy: 50,
      });

      filteredData.nodes.forEach((n) => {
        dagreGraph.setNode(n.id, { width: 220, height: 75 });
      });

      filteredData.edges.forEach((e) => {
        dagreGraph.setEdge(e.source, e.target);
      });

      dagre.layout(dagreGraph);

      filteredData.nodes.forEach((n) => {
        const p = dagreGraph.node(n.id);
        positions[n.id] = {
          x: p ? p.x - 110 : 0,
          y: p ? p.y - 37.5 : 0,
        };
      });
    }

    // Build React Flow nodes
    const rfNodes: Node[] = filteredData.nodes.map((n) => {
      const isSelected = n.id === selectedId;
      const isBlast = blastRadiusNodes.has(n.id);
      const isConnected = !selectedId || connectedNeighborIds.has(n.id) || isBlast;
      const pos = positions[n.id] || { x: 0, y: 0 };

      return {
        id: n.id,
        position: pos,
        data: {
          label: (
            <div className="text-left font-sans select-none">
              <div className="flex items-center justify-between mb-1.5">
                <span className={`text-[9px] font-bold px-1.5 py-0.5 rounded tracking-wider uppercase ${getTypeBadgeClass(n.type)}`}>
                  {n.type}
                </span>
                <span className="text-[9px] text-slate-500 font-mono truncate max-w-[80px]">
                  {getSimplePackage(n.qualifiedName)}
                </span>
              </div>
              <p className="font-bold text-xs text-white truncate max-w-[190px]" title={n.label}>
                {n.label}
              </p>
              <p className="text-[10px] text-slate-400 font-mono truncate max-w-[190px] mt-0.5" title={n.qualifiedName}>
                {n.qualifiedName}
              </p>
            </div>
          ),
          rawNode: n,
        },
        style: {
          background: isSelected
            ? 'linear-gradient(135deg, #0369a1, #0c4a6e)'
            : isBlast
            ? 'linear-gradient(135deg, #881337, #4c0519)'
            : '#090d16',
          color: '#f8fafc',
          border: isSelected
            ? '2px solid #38bdf8'
            : isBlast
            ? '2px solid #f43f5e'
            : isConnected
            ? '1px solid #334155'
            : '1px solid #1e293b',
          borderRadius: '12px',
          padding: '10px 14px',
          width: 220,
          opacity: isConnected ? 1 : 0.25,
          transition: 'all 0.3s ease',
          boxShadow: isSelected
            ? '0 0 20px rgba(56, 189, 248, 0.45)'
            : isBlast
            ? '0 0 20px rgba(244, 63, 94, 0.5)'
            : '0 4px 6px -1px rgba(0, 0, 0, 0.3)',
        },
      };
    });

    // Build React Flow edges with smooth curves
    const rfEdges: Edge[] = filteredData.edges.map((e) => {
      const isBlastEdge = blastRadiusNodes.has(e.source) || blastRadiusNodes.has(e.target);
      const isConnectedEdge =
        !selectedId || e.source === selectedId || e.target === selectedId;

      return {
        id: e.id,
        source: e.source,
        target: e.target,
        type: 'smoothstep',
        animated: isBlastEdge,
        label: e.type,
        labelStyle: {
          fill: isBlastEdge ? '#f43f5e' : isConnectedEdge ? '#94a3b8' : '#475569',
          fontSize: 9,
          fontFamily: 'JetBrains Mono',
          fontWeight: 600,
        },
        style: {
          stroke: isBlastEdge ? '#f43f5e' : isConnectedEdge ? '#38bdf8' : '#334155',
          strokeWidth: isBlastEdge ? 2.5 : isConnectedEdge && selectedId ? 2 : 1,
          opacity: !selectedId || isConnectedEdge || isBlastEdge ? 1 : 0.15,
        },
        markerEnd: {
          type: MarkerType.ArrowClosed,
          color: isBlastEdge ? '#f43f5e' : isConnectedEdge ? '#38bdf8' : '#475569',
          width: 14,
          height: 14,
        },
      };
    });

    setNodes(rfNodes);
    setEdges(rfEdges);
  }, [filteredData, selectedNode, blastRadiusNodes, layoutDir, setNodes, setEdges]);

  const onNodeClick = (_: any, node: Node) => {
    const raw = (node.data as any)?.rawNode as GraphNodeView;
    setSelectedNode(raw);
    setBlastRadiusNodes(new Set());
  };

  const handleComputeBlastRadius = async () => {
    if (!selectedNode) return;
    setLoadingBlast(true);
    try {
      const dependents = await codeApi.getDependents(selectedNode.id, 3);
      const set = new Set<string>();
      set.add(selectedNode.id);
      dependents.forEach((d: any) => set.add(d.id));
      setBlastRadiusNodes(set);
    } catch (e) {
      console.error('Failed to calculate blast radius', e);
    } finally {
      setLoadingBlast(false);
    }
  };

  const handleSelectNeighbor = (node: GraphNodeView) => {
    setSelectedNode(node);
    setBlastRadiusNodes(new Set());
  };

  return (
    <div className="space-y-4">
      {/* Top Filter & View Mode Bar */}
      <div className="bg-slate-900 border border-slate-800 p-3.5 rounded-2xl shadow-xl flex flex-wrap items-center justify-between gap-3">
        {/* Left: View Mode Pills (Architecture vs Components vs All) */}
        <div className="flex items-center space-x-1.5 bg-slate-950 p-1 rounded-xl border border-slate-800">
          <button
            type="button"
            onClick={() => setViewMode('architecture')}
            className={`px-3 py-1.5 text-xs font-semibold rounded-lg flex items-center space-x-1.5 transition cursor-pointer ${
              viewMode === 'architecture'
                ? 'bg-cyan-500 text-slate-950 shadow-md shadow-cyan-500/20'
                : 'text-slate-400 hover:text-white'
            }`}
          >
            <Box className="w-3.5 h-3.5" />
            <span>Architecture (Classes)</span>
          </button>
          <button
            type="button"
            onClick={() => setViewMode('components')}
            className={`px-3 py-1.5 text-xs font-semibold rounded-lg flex items-center space-x-1.5 transition cursor-pointer ${
              viewMode === 'components'
                ? 'bg-cyan-500 text-slate-950 shadow-md shadow-cyan-500/20'
                : 'text-slate-400 hover:text-white'
            }`}
          >
            <Layers className="w-3.5 h-3.5" />
            <span>+ Methods</span>
          </button>
          <button
            type="button"
            onClick={() => setViewMode('all')}
            className={`px-3 py-1.5 text-xs font-semibold rounded-lg flex items-center space-x-1.5 transition cursor-pointer ${
              viewMode === 'all'
                ? 'bg-cyan-500 text-slate-950 shadow-md shadow-cyan-500/20'
                : 'text-slate-400 hover:text-white'
            }`}
          >
            <Grid className="w-3.5 h-3.5" />
            <span>All Symbols</span>
          </button>
        </div>

        {/* Center: Search and Package Dropdown */}
        <div className="flex items-center space-x-2 flex-1 max-w-md">
          <div className="relative flex-1">
            <Search className="w-3.5 h-3.5 absolute left-3 top-2.5 text-slate-500" />
            <input
              type="text"
              placeholder="Search symbol (e.g. CspSolver, Service)..."
              value={searchQuery}
              onChange={(e) => setSearchQuery(e.target.value)}
              className="w-full bg-slate-950 border border-slate-800 rounded-xl pl-8 pr-3 py-1.5 text-xs text-slate-100 placeholder-slate-600 focus:outline-none focus:border-cyan-500"
            />
          </div>

          {packages.length > 0 && (
            <select
              value={selectedPackage}
              onChange={(e) => setSelectedPackage(e.target.value)}
              className="bg-slate-950 border border-slate-800 rounded-xl px-2.5 py-1.5 text-xs text-slate-300 focus:outline-none focus:border-cyan-500 max-w-[160px] truncate"
            >
              <option value="ALL">All Packages ({packages.length})</option>
              {packages.map((pkg) => (
                <option key={pkg} value={pkg}>
                  {getSimplePackage(pkg)}
                </option>
              ))}
            </select>
          )}
        </div>

        {/* Right: Layout Toggle Buttons */}
        <div className="flex items-center space-x-1.5 bg-slate-950 p-1 rounded-xl border border-slate-800 text-xs">
          <button
            type="button"
            onClick={() => setLayoutDir('TB')}
            className={`px-2.5 py-1 rounded-lg flex items-center space-x-1 transition cursor-pointer ${
              layoutDir === 'TB' ? 'bg-slate-800 text-cyan-400 font-bold' : 'text-slate-400 hover:text-slate-200'
            }`}
            title="Hierarchical: Top to Bottom"
          >
            <ArrowDownCircle className="w-3.5 h-3.5" />
            <span>Top-Down</span>
          </button>
          <button
            type="button"
            onClick={() => setLayoutDir('LR')}
            className={`px-2.5 py-1 rounded-lg flex items-center space-x-1 transition cursor-pointer ${
              layoutDir === 'LR' ? 'bg-slate-800 text-cyan-400 font-bold' : 'text-slate-400 hover:text-slate-200'
            }`}
            title="Hierarchical: Left to Right"
          >
            <ArrowRightCircle className="w-3.5 h-3.5" />
            <span>Left-Right</span>
          </button>
          <button
            type="button"
            onClick={() => setLayoutDir('GRID')}
            className={`px-2.5 py-1 rounded-lg flex items-center space-x-1 transition cursor-pointer ${
              layoutDir === 'GRID' ? 'bg-slate-800 text-cyan-400 font-bold' : 'text-slate-400 hover:text-slate-200'
            }`}
            title="Compact Grid"
          >
            <Grid className="w-3.5 h-3.5" />
            <span>Grid</span>
          </button>
        </div>
      </div>

      {/* Main Canvas + Inspector Grid */}
      <div className="grid grid-cols-1 lg:grid-cols-4 gap-4">
        {/* React Flow Graph Canvas */}
        <div className="lg:col-span-3 h-[640px] bg-slate-950 border border-slate-800 rounded-2xl overflow-hidden relative shadow-2xl">
          {loading ? (
            <div className="h-full flex flex-col items-center justify-center text-slate-500 text-sm">
              <div className="w-8 h-8 border-2 border-cyan-400 border-t-transparent rounded-full animate-spin mb-3" />
              <span>Constructing JGraphT directed dependency graph...</span>
            </div>
          ) : nodes.length === 0 ? (
            <div className="h-full flex flex-col items-center justify-center text-slate-500 text-sm p-6 text-center">
              <Layers className="w-12 h-12 text-slate-700 mb-3" />
              <p className="font-semibold text-slate-300">No symbols matching current filter</p>
              <p className="text-xs text-slate-500 mt-1 max-w-sm">
                Try switching view mode to <strong>+ Methods</strong> or <strong>All Symbols</strong>, or clear your search query.
              </p>
              <button
                type="button"
                onClick={() => {
                  setViewMode('all');
                  setSearchQuery('');
                  setSelectedPackage('ALL');
                }}
                className="mt-4 px-4 py-1.5 bg-slate-800 hover:bg-slate-700 text-slate-200 text-xs rounded-xl border border-slate-700 transition"
              >
                Reset All Filters
              </button>
            </div>
          ) : (
            <ReactFlow
              key={`${viewMode}-${layoutDir}`}
              nodes={nodes}
              edges={edges}
              onNodesChange={onNodesChange}
              onEdgesChange={onEdgesChange}
              onNodeClick={onNodeClick}
              fitView
              fitViewOptions={{ padding: 0.2 }}
              minZoom={0.1}
              maxZoom={2.5}
            >
              <Controls className="bg-slate-900 border border-slate-800 fill-slate-200" />
              <MiniMap
                nodeColor={(n) => {
                  if (blastRadiusNodes.has(n.id)) return '#f43f5e';
                  if (selectedNode?.id === n.id) return '#38bdf8';
                  return '#1e293b';
                }}
                maskColor="rgba(10, 15, 26, 0.75)"
                className="bg-slate-900/90 border border-slate-800 rounded-xl"
              />
              <Background color="#1e293b" gap={24} size={1} />

              {/* Status Header Badge Panel */}
              <Panel position="top-left" className="bg-slate-900/90 backdrop-blur-md border border-slate-800 px-3 py-1.5 rounded-xl text-xs text-slate-400 flex items-center space-x-3 shadow-lg">
                <span>
                  Showing <strong className="text-white">{filteredData.nodes.length}</strong> of{' '}
                  <strong className="text-slate-400">{snapshot?.totalNodes || 0}</strong> symbols
                </span>
                <span>•</span>
                <span>
                  <strong className="text-white">{filteredData.edges.length}</strong> dependencies
                </span>
                {selectedNode && (
                  <>
                    <span>•</span>
                    <button
                      type="button"
                      onClick={() => {
                        setSelectedNode(null);
                        setBlastRadiusNodes(new Set());
                      }}
                      className="text-cyan-400 hover:text-cyan-300 font-semibold underline cursor-pointer"
                    >
                      Clear Selection
                    </button>
                  </>
                )}
              </Panel>
            </ReactFlow>
          )}
        </div>

        {/* Symbol Details & Impact Inspector Sidebar */}
        <div className="bg-slate-900 border border-slate-800 rounded-2xl p-5 flex flex-col justify-between h-[640px] overflow-hidden shadow-2xl">
          <div className="overflow-y-auto pr-1 space-y-4">
            <div className="flex items-center justify-between pb-3 border-b border-slate-800">
              <div className="flex items-center space-x-2">
                <Compass className="w-4 h-4 text-cyan-400" />
                <h3 className="font-bold text-white text-sm">Symbol Inspector</h3>
              </div>
              {selectedNode && (
                <span className="text-[10px] text-slate-500 font-mono">
                  {selectedNode.type}
                </span>
              )}
            </div>

            {selectedNode ? (
              <div className="space-y-4 text-xs">
                {/* Header Card */}
                <div className="p-3 bg-slate-950 rounded-xl border border-slate-800">
                  <span className={`text-[9px] font-bold px-2 py-0.5 rounded uppercase tracking-wider ${getTypeBadgeClass(selectedNode.type)}`}>
                    {selectedNode.type}
                  </span>
                  <h4 className="text-base font-bold text-white mt-1.5 break-all">
                    {selectedNode.label}
                  </h4>
                  <p className="text-slate-400 text-[11px] font-mono mt-0.5 break-all">
                    {selectedNode.qualifiedName}
                  </p>
                  <div className="mt-2.5 pt-2 border-t border-slate-900 flex items-center space-x-1.5 text-slate-400 text-[11px]">
                    <FileCode className="w-3.5 h-3.5 text-amber-400 shrink-0" />
                    <span className="truncate font-mono" title={selectedNode.sourceFile}>
                      {selectedNode.sourceFile}
                    </span>
                  </div>
                  <p className="text-[10px] text-slate-500 font-mono mt-0.5">
                    Lines: {selectedNode.startLine} - {selectedNode.endLine}
                  </p>
                </div>

                {/* Blast Radius Action */}
                <div>
                  <button
                    onClick={handleComputeBlastRadius}
                    disabled={loadingBlast}
                    className="w-full py-2.5 px-3 bg-rose-500/10 hover:bg-rose-500/20 text-rose-400 border border-rose-500/30 rounded-xl text-xs font-semibold flex items-center justify-center space-x-2 transition disabled:opacity-50 cursor-pointer shadow-lg shadow-rose-950/40"
                  >
                    <Zap className="w-3.5 h-3.5" />
                    <span>{loadingBlast ? 'Traversing Graph...' : 'Calculate Blast Radius'}</span>
                  </button>
                  <p className="text-[10px] text-slate-500 text-center mt-1">
                    Finds all direct and transitive dependents up to depth 3
                  </p>
                </div>

                {/* Blast Radius Result Pill */}
                {blastRadiusNodes.size > 0 && (
                  <div className="p-2.5 bg-rose-950/50 border border-rose-800/60 rounded-xl text-rose-300 text-xs">
                    <div className="font-bold flex items-center space-x-1.5 mb-1">
                      <Zap className="w-3.5 h-3.5 text-rose-400" />
                      <span>Blast Radius: {blastRadiusNodes.size} symbols impacted</span>
                    </div>
                    <p className="text-[10px] text-rose-400">
                      Modifying this symbol will propagate changes through {blastRadiusNodes.size - 1} dependent components.
                    </p>
                  </div>
                )}

                {/* Incoming Callers */}
                <div>
                  <h5 className="text-[11px] font-bold uppercase tracking-wider text-slate-400 flex items-center space-x-1 mb-2">
                    <ArrowDownLeft className="w-3.5 h-3.5 text-emerald-400" />
                    <span>Called / Used By ({incomingNeighbors.length})</span>
                  </h5>
                  {incomingNeighbors.length === 0 ? (
                    <p className="text-[11px] text-slate-600 italic">No incoming calls in current index.</p>
                  ) : (
                    <div className="space-y-1 max-h-36 overflow-y-auto">
                      {incomingNeighbors.map((n) => (
                        <button
                          key={n.id}
                          type="button"
                          onClick={() => handleSelectNeighbor(n)}
                          className="w-full text-left p-1.5 rounded-lg bg-slate-950/80 hover:bg-slate-800 text-slate-300 hover:text-white transition flex items-center justify-between group"
                        >
                          <span className="truncate max-w-[180px] font-mono text-[11px]">{n.label}</span>
                          <span className="text-[9px] text-slate-500 group-hover:text-cyan-400">{n.type}</span>
                        </button>
                      ))}
                    </div>
                  )}
                </div>

                {/* Outgoing Callees */}
                <div>
                  <h5 className="text-[11px] font-bold uppercase tracking-wider text-slate-400 flex items-center space-x-1 mb-2">
                    <ArrowUpRight className="w-3.5 h-3.5 text-cyan-400" />
                    <span>Calls / Depends On ({outgoingNeighbors.length})</span>
                  </h5>
                  {outgoingNeighbors.length === 0 ? (
                    <p className="text-[11px] text-slate-600 italic">No outgoing dependencies.</p>
                  ) : (
                    <div className="space-y-1 max-h-36 overflow-y-auto">
                      {outgoingNeighbors.map((n) => (
                        <button
                          key={n.id}
                          type="button"
                          onClick={() => handleSelectNeighbor(n)}
                          className="w-full text-left p-1.5 rounded-lg bg-slate-950/80 hover:bg-slate-800 text-slate-300 hover:text-white transition flex items-center justify-between group"
                        >
                          <span className="truncate max-w-[180px] font-mono text-[11px]">{n.label}</span>
                          <span className="text-[9px] text-slate-500 group-hover:text-cyan-400">{n.type}</span>
                        </button>
                      ))}
                    </div>
                  )}
                </div>
              </div>
            ) : (
              <div className="text-center py-24 text-slate-500 text-xs">
                <Eye className="w-8 h-8 mx-auto mb-2 text-slate-700" />
                <p className="font-semibold text-slate-400">No Symbol Selected</p>
                <p className="mt-1 text-slate-600 max-w-[200px] mx-auto">
                  Click any node in the graph to inspect its callers, callees, and compute its blast radius.
                </p>
              </div>
            )}
          </div>

          <div className="pt-3 border-t border-slate-800 text-[11px] text-slate-500 flex items-center justify-between">
            <span className="flex items-center space-x-1">
              <CheckCircle2 className="w-3.5 h-3.5 text-emerald-400" />
              <span>JGraphT Engine Active</span>
            </span>
            <span className="font-mono text-[10px] text-slate-600">
              {layoutDir} Layout
            </span>
          </div>
        </div>
      </div>
    </div>
  );
};

function getSimplePackage(qualifiedName: string): string {
  const parts = qualifiedName.split('.');
  if (parts.length > 2) {
    return parts.slice(-3, -1).join('.');
  }
  return parts.slice(0, -1).join('.');
}

function getTypeBadgeClass(type: string): string {
  switch (type) {
    case 'CLASS':
      return 'bg-blue-950/80 text-blue-400 border border-blue-800/60';
    case 'INTERFACE':
      return 'bg-purple-950/80 text-purple-400 border border-purple-800/60';
    case 'RECORD':
      return 'bg-cyan-950/80 text-cyan-400 border border-cyan-800/60';
    case 'ENUM':
      return 'bg-amber-950/80 text-amber-400 border border-amber-800/60';
    case 'METHOD':
      return 'bg-emerald-950/80 text-emerald-400 border border-emerald-800/60';
    case 'FIELD':
      return 'bg-slate-800/80 text-slate-400 border border-slate-700';
    default:
      return 'bg-slate-800 text-slate-400 border border-slate-700';
  }
}

import React from 'react';
import { useQuery } from '@tanstack/react-query';
import { Boxes, Flame, Network, GitCompare, Grid3x3, LineChart as LineIcon, ScatterChart as ScatterIcon, ShieldCheck } from 'lucide-react';
import { twinApi } from '../../api/twin';
import { CodeCity } from './CodeCity';
import { HotspotChart } from './HotspotChart';
import { DependencyMatrix } from './DependencyMatrix';
import { MainSequence } from './MainSequence';
import { TwinTrends } from './TwinTrends';
import { TwinCompare } from './TwinCompare';
import { CentralityPanel } from './CentralityPanel';
import { Empty, fmt, Tile } from './twinShared';

type View = 'city' | 'hotspots' | 'matrix' | 'sequence' | 'centrality' | 'trends' | 'compare' | 'rules';

const VIEWS: { key: View; label: string; icon: React.ElementType; perCommit: boolean }[] = [
  { key: 'city', label: 'Code city', icon: Boxes, perCommit: true },
  { key: 'hotspots', label: 'Hotspots', icon: Flame, perCommit: true },
  { key: 'matrix', label: 'Dependency matrix', icon: Grid3x3, perCommit: true },
  { key: 'sequence', label: 'Main sequence', icon: ScatterIcon, perCommit: true },
  { key: 'centrality', label: 'Centrality', icon: Network, perCommit: false },
  { key: 'trends', label: 'Trends', icon: LineIcon, perCommit: false },
  { key: 'compare', label: 'Compare', icon: GitCompare, perCommit: false },
  { key: 'rules', label: 'Rules & coupling', icon: ShieldCheck, perCommit: false },
];

/**
 * Architecture digital twin of the repository: per-commit structure (code city, hotspots, dependency matrix, main
 * sequence), its evolution (trends) and drift between commits (compare). The rule/coupling view from Phase 2 stays as
 * the last tab.
 */
export const ArchitectureTwinPanel: React.FC<{ repositoryId: string; rules: React.ReactNode }> = ({ repositoryId, rules }) => {
  const [view, setView] = React.useState<View>('city');
  const [commit, setCommit] = React.useState<string | undefined>(undefined);
  const { data: commits } = useQuery({ queryKey: ['twin-commits', repositoryId], queryFn: () => twinApi.commits(repositoryId) });
  const perCommit = VIEWS.find((v) => v.key === view)!.perCommit;
  const { data: snapshot, isLoading: snapLoading, error: snapError } = useQuery({
    queryKey: ['twin-snapshot', repositoryId, commit ?? 'default'],
    queryFn: () => twinApi.snapshot(repositoryId, commit),
    enabled: (commits?.length ?? 0) > 0,
  });
  const { data: hotspots, isLoading: hotLoading } = useQuery({
    queryKey: ['twin-hotspots', repositoryId, commit ?? 'default'],
    queryFn: () => twinApi.hotspots(repositoryId, commit),
    enabled: (commits?.length ?? 0) > 0 && (view === 'city' || view === 'hotspots'),
  });

  if (commits && commits.length === 0) return <Empty>Index the repository first — the architecture twin is built from analysed commits.</Empty>;
  const s = snapshot?.summary;
  const hotspotCount = hotspots?.files.filter((f) => f.hotspot).length;

  return (
    <div className="space-y-5">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div className="flex flex-wrap gap-1 bg-slate-900 border border-slate-800 rounded-xl p-1">
          {VIEWS.map((v) => (
            <button key={v.key} onClick={() => setView(v.key)}
              className={`flex items-center gap-1.5 text-xs px-3 py-1.5 rounded-lg transition ${view === v.key ? 'bg-slate-700 text-white' : 'text-slate-400 hover:text-slate-200'}`}>
              <v.icon className="w-3.5 h-3.5" /> {v.label}
            </button>
          ))}
        </div>
        {perCommit && commits && commits.length > 0 && (
          <select value={commit ?? snapshot?.commitSha ?? ''} onChange={(e) => setCommit(e.target.value)}
            className="bg-slate-950 border border-slate-800 rounded-lg px-2 py-1.5 text-xs text-slate-300 max-w-sm" aria-label="Commit">
            {commits.map((c) => (
              <option key={c.sha} value={c.sha}>
                {c.sha.substring(0, 7)} · {c.commitDate ? new Date(c.commitDate).toLocaleDateString() : 'date unknown'} · {(c.message ?? '').substring(0, 48)}
              </option>
            ))}
          </select>
        )}
      </div>

      {perCommit && s && (
        <div className="grid grid-cols-2 md:grid-cols-3 xl:grid-cols-6 gap-3">
          <Tile label="Packages" value={s.packages} hint={`${s.files} production files, ${fmt(s.types)} types`} />
          <Tile label="Lines of code" value={fmt(s.loc)} hint={`${fmt(s.methods)} methods, complexity ${fmt(s.complexity)}`} />
          <Tile label="Package dependencies" value={s.packageEdges} hint={`${fmt(s.crossPackageReferences)} cross-package references`} />
          <Tile label="Package cycles" value={s.cycles} tone={s.cycles > 0 ? 'critical' : 'default'} hint={`${s.packagesInCycles} packages involved`} />
          <Tile label="Avg distance D" value={s.averageDistance.toFixed(2)} hint={`from the main sequence; avg instability ${s.averageInstability.toFixed(2)}`} />
          <Tile label="Hotspots" value={hotspotCount ?? '…'} tone={(hotspotCount ?? 0) > 0 ? 'critical' : 'default'} hint={`${s.violations} architecture rule violation(s)`} />
        </div>
      )}

      {perCommit && (snapLoading ? <Empty>Building the architecture snapshot…</Empty> : snapError ? <Empty>Could not load the snapshot of this commit.</Empty> : null)}
      {view === 'city' && (hotLoading ? <Empty>Computing file metrics…</Empty> : hotspots && <CodeCity hotspots={hotspots} />)}
      {view === 'hotspots' && (hotLoading ? <Empty>Computing file metrics…</Empty> : hotspots && <HotspotChart hotspots={hotspots} />)}
      {view === 'matrix' && snapshot && <DependencyMatrix snapshot={snapshot} />}
      {view === 'sequence' && snapshot && <MainSequence snapshot={snapshot} />}
      {view === 'centrality' && <CentralityPanel repositoryId={repositoryId} />}
      {view === 'trends' && <TwinTrends repositoryId={repositoryId} />}
      {view === 'compare' && commits && <TwinCompare repositoryId={repositoryId} commits={commits} />}
      {view === 'rules' && rules}
    </div>
  );
};

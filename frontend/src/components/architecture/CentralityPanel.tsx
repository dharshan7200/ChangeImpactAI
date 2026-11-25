import React from 'react';
import { useQuery } from '@tanstack/react-query';
import { apiClient } from '../../api/client';
import type { ApiResponse } from '../../types';
import { Empty, fmt, SERIES } from './twinShared';

interface TypeCentrality { symbolId: string; qualifiedName: string; simpleName: string; packageName: string; sourceFile: string; startLine: number;
  inDegree: number; outDegree: number; betweenness: number; pageRank: number; rank: number }
interface CentralityReport { commitSha: string; types: number; dependencies: number; includesTests: boolean; rankedBy: string; top: TypeCentrality[] }

const MEASURES: { key: string; label: string; value: (t: TypeCentrality) => number; format: (v: number) => string; help: string }[] = [
  { key: 'betweenness', label: 'Betweenness', value: (t) => t.betweenness, format: (v) => v.toFixed(4),
    help: 'Share of shortest dependency paths between other types that pass through this type (0–1). High = a broker: changes to it reach many otherwise unrelated parts.' },
  { key: 'pagerank', label: 'PageRank', value: (t) => t.pageRank, format: (v) => v.toFixed(4),
    help: 'Importance of being used by important types (damping 0.85; sums to 1 over all types). High = foundational type many others build on.' },
  { key: 'indegree', label: 'Used by', value: (t) => t.inDegree, format: (v) => fmt(v),
    help: 'Number of distinct types that depend on this type (afferent coupling).' },
  { key: 'outdegree', label: 'Uses', value: (t) => t.outDegree, format: (v) => fmt(v),
    help: 'Number of distinct types this type depends on (efferent coupling).' },
];

/** Most central types of the last indexed commit (class-level dependency graph). */
export const CentralityPanel: React.FC<{ repositoryId: string }> = ({ repositoryId }) => {
  const [rankBy, setRankBy] = React.useState('betweenness');
  const [includeTests, setIncludeTests] = React.useState(false);
  const { data, isLoading, error } = useQuery({
    queryKey: ['centrality', repositoryId, rankBy, includeTests],
    queryFn: async () => (await apiClient.get<ApiResponse<CentralityReport>>(`/code/repositories/${repositoryId}/centrality`,
      { params: { rankBy, includeTests, limit: 30 } })).data.data,
  });
  const measure = MEASURES.find((m) => m.key === rankBy)!;
  const max = Math.max(1e-12, ...(data?.top ?? []).map(measure.value));

  return (
    <div className="bg-slate-900 border border-slate-800 rounded-2xl p-5 space-y-4">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div className="max-w-3xl">
          <h3 className="text-sm font-bold text-white">Central types</h3>
          <p className="text-[11px] text-slate-400 mt-1 leading-relaxed">{measure.help} Computed on the class-level dependency graph of the last
            indexed commit{data ? ` (${fmt(data.types)} types, ${fmt(data.dependencies)} type dependencies)` : ''}; exact graph measures, not estimates.</p>
        </div>
        <div className="flex flex-wrap items-center gap-2">
          <div className="flex gap-1 bg-slate-950 border border-slate-800 rounded-lg p-0.5" role="group" aria-label="Rank by">
            {MEASURES.map((m) => (
              <button key={m.key} onClick={() => setRankBy(m.key)} aria-pressed={rankBy === m.key}
                className={`text-[11px] px-2.5 py-1 rounded-md ${rankBy === m.key ? 'bg-slate-700 text-white' : 'text-slate-400 hover:text-slate-200'}`}>{m.label}</button>
            ))}
          </div>
          <label className="flex items-center gap-1.5 text-[11px] text-slate-400">
            <input type="checkbox" checked={includeTests} onChange={(e) => setIncludeTests(e.target.checked)} /> include test code
          </label>
        </div>
      </div>
      {isLoading ? <Empty>Computing centrality…</Empty> : error ? <Empty>Could not compute centrality (is the repository indexed?).</Empty> : !data?.top.length ? <Empty>No types found.</Empty> : (
        <div className="overflow-x-auto">
          <table className="w-full text-xs min-w-[640px]">
            <thead>
              <tr className="text-left text-slate-500 border-b border-slate-800">
                <th className="py-1.5 pr-2 font-medium w-8">#</th>
                <th className="py-1.5 pr-2 font-medium">Type</th>
                <th className="py-1.5 pr-2 font-medium w-[30%]">{measure.label}</th>
                {MEASURES.filter((m) => m.key !== rankBy).map((m) => <th key={m.key} className="py-1.5 px-2 font-medium text-right">{m.label}</th>)}
              </tr>
            </thead>
            <tbody>
              {data.top.map((t) => (
                <tr key={t.symbolId} className="border-b border-slate-800/60 hover:bg-slate-800/30">
                  <td className="py-1.5 pr-2 text-slate-500">{t.rank}</td>
                  <td className="py-1.5 pr-2 min-w-0">
                    <div className="text-slate-100 font-medium">{t.simpleName}</div>
                    <div className="text-[10px] text-slate-500 truncate max-w-[22rem]" title={`${t.sourceFile}:${t.startLine}`}>{t.packageName}</div>
                  </td>
                  <td className="py-1.5 pr-2">
                    <div className="flex items-center gap-2">
                      <div className="flex-1 h-2 bg-slate-800 rounded-sm overflow-hidden">
                        <div className="h-full rounded-sm" style={{ width: `${(measure.value(t) / max) * 100}%`, background: SERIES[0] }} />
                      </div>
                      <span className="text-slate-200 tabular-nums w-14 text-right">{measure.format(measure.value(t))}</span>
                    </div>
                  </td>
                  {MEASURES.filter((m) => m.key !== rankBy).map((m) => (
                    <td key={m.key} className="py-1.5 px-2 text-right text-slate-400 tabular-nums">{m.format(m.value(t))}</td>
                  ))}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
};

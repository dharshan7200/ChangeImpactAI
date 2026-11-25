import React from 'react';
import { useQuery } from '@tanstack/react-query';
import { Bar, BarChart, CartesianGrid, Legend, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts';
import { analysesApi } from '../../api/analyses';

// Dark-mode categorical slots 1 and 2 from the validated reference palette (checked against the slate surface).
const SERIES_1 = '#3987e5';
const SERIES_2 = '#d95926';
const GRID = '#1e293b';
const AXIS = '#94a3b8';

/** Historical change intelligence: weekly activity and the most frequently changed files. */
export const HistoryPanel: React.FC<{ repositoryId: string }> = ({ repositoryId }) => {
  const [weeks, setWeeks] = React.useState(52);
  const { data, isLoading } = useQuery({
    queryKey: ['history', repositoryId, weeks],
    queryFn: () => analysesApi.history(repositoryId, weeks),
  });

  if (isLoading) return <div className="py-16 text-center text-slate-500 text-sm">Loading history…</div>;
  if (!data) return <div className="py-16 text-center text-slate-500 text-sm">No history available.</div>;

  const weekly = data.weekly.map((w) => ({
    week: new Date(w.week).toLocaleDateString(undefined, { month: 'short', day: 'numeric' }),
    other: w.commits - w.bugfixCommits,
    bugfix: w.bugfixCommits,
    reverts: w.revertCommits,
    total: w.commits,
  }));
  const files = data.mostChangedFiles.slice(0, 10).map((f) => ({
    ...f,
    name: f.path.substring(f.path.lastIndexOf('/') + 1),
  }));

  return (
    <div className="space-y-6">
      <div className="grid grid-cols-2 md:grid-cols-4 gap-3">
        <Tile label="Commits scanned" value={data.scannedCommits} hint="Most recent commits reachable from HEAD" />
        <Tile label={`Commits in ${data.weeks} weeks`} value={data.commitsInWindow} />
        <Tile label="Bug-fix commits" value={data.bugfixCommitsInWindow} hint="Heuristic label from commit messages" />
        <Tile label="Revert commits" value={data.revertCommitsInWindow} hint="Heuristic label from commit messages" />
      </div>

      <div className="bg-slate-900 border border-slate-800 rounded-2xl p-6">
        <div className="flex items-center justify-between mb-1">
          <h2 className="text-base font-bold text-white">Weekly commit activity</h2>
          <select
            value={weeks}
            onChange={(e) => setWeeks(Number(e.target.value))}
            className="bg-slate-950 border border-slate-800 rounded-lg px-2 py-1 text-xs text-slate-300"
          >
            {[12, 26, 52, 104, 260].map((w) => (
              <option key={w} value={w}>
                Last {w} weeks
              </option>
            ))}
          </select>
        </div>
        <p className="text-xs text-slate-400 mb-4">All commits per week; bug-fix commits are a heuristic label from commit messages.</p>
        {weekly.length === 0 ? (
          <p className="py-10 text-center text-xs text-slate-500">No commits in this window.</p>
        ) : (
          <div className="h-64">
            <ResponsiveContainer width="100%" height="100%">
              <BarChart data={weekly} barCategoryGap={2}>
                <CartesianGrid stroke={GRID} vertical={false} />
                <XAxis dataKey="week" tick={{ fill: AXIS, fontSize: 10 }} axisLine={{ stroke: GRID }} tickLine={false} minTickGap={16} />
                <YAxis allowDecimals={false} tick={{ fill: AXIS, fontSize: 10 }} axisLine={false} tickLine={false} width={28} />
                <Tooltip
                  cursor={{ fill: 'rgba(148,163,184,0.08)' }}
                  contentStyle={{ background: '#0f172a', border: '1px solid #334155', borderRadius: 10, fontSize: 12 }}
                  labelStyle={{ color: '#e2e8f0' }}
                  itemStyle={{ color: '#e2e8f0' }}
                  formatter={(value, name) => [String(value), name === 'bugfix' ? 'Bug-fix commits' : 'Other commits']}
                />
                <Legend
                  formatter={(v) => <span style={{ color: '#cbd5e1', fontSize: 11 }}>{v === 'bugfix' ? 'Bug-fix commits (heuristic)' : 'Other commits'}</span>}
                />
                <Bar isAnimationActive={false} dataKey="other" stackId="c" fill={SERIES_1} stroke="#0f172a" strokeWidth={1} />
                <Bar isAnimationActive={false} dataKey="bugfix" stackId="c" fill={SERIES_2} stroke="#0f172a" strokeWidth={1} radius={[4, 4, 0, 0]} />
              </BarChart>
            </ResponsiveContainer>
          </div>
        )}
      </div>

      <div className="bg-slate-900 border border-slate-800 rounded-2xl p-6">
        <h2 className="text-base font-bold text-white mb-1">Most frequently changed files</h2>
        <p className="text-xs text-slate-400 mb-4">Commits touching each file in the scanned history (excluding merge commits).</p>
        {files.length === 0 ? (
          <p className="py-10 text-center text-xs text-slate-500">No file history yet. Re-index the repository to collect it.</p>
        ) : (
          <div style={{ height: files.length * 30 + 20 }}>
            <ResponsiveContainer width="100%" height="100%">
              <BarChart data={files} layout="vertical" margin={{ left: 8, right: 40 }}>
                <CartesianGrid stroke={GRID} horizontal={false} />
                <XAxis type="number" allowDecimals={false} tick={{ fill: AXIS, fontSize: 10 }} axisLine={false} tickLine={false} />
                <YAxis type="category" dataKey="name" width={210} tick={{ fill: '#cbd5e1', fontSize: 11 }} axisLine={false} tickLine={false} />
                <Tooltip
                  cursor={{ fill: 'rgba(148,163,184,0.08)' }}
                  contentStyle={{ background: '#0f172a', border: '1px solid #334155', borderRadius: 10, fontSize: 12 }}
                  labelStyle={{ color: '#e2e8f0' }}
                  itemStyle={{ color: '#e2e8f0' }}
                  labelFormatter={(_, p) => (p?.[0]?.payload as { path?: string })?.path ?? ''}
                  formatter={(value, _n, item) => {
                    const f = item.payload as { changedLines: number; bugfixCommits: number };
                    return [`${value} commits · ${f.changedLines} lines · ${f.bugfixCommits} bug-fix`, 'Changes'];
                  }}
                />
                <Bar isAnimationActive={false} dataKey="commits" fill={SERIES_1} radius={[0, 4, 4, 0]} barSize={16}
                     label={{ position: 'right', fill: '#cbd5e1', fontSize: 11 }} />
              </BarChart>
            </ResponsiveContainer>
          </div>
        )}
        <p className="text-[11px] text-slate-500 mt-3">{data.note}</p>
      </div>
    </div>
  );
};

const Tile: React.FC<{ label: string; value: number; hint?: string }> = ({ label, value, hint }) => (
  <div className="bg-slate-900 border border-slate-800 rounded-xl p-4" title={hint}>
    <div className="text-[11px] uppercase tracking-wider text-slate-400">{label}</div>
    <div className="text-2xl font-bold text-white mt-1">{value}</div>
    {hint && <div className="text-[10px] text-slate-500 mt-1">{hint}</div>}
  </div>
);

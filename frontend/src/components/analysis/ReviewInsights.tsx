import React from 'react';
import { ArrowRight, Boxes, CheckSquare, ClipboardCheck, GitBranch, ShieldAlert, Square, TestTube2, Users } from 'lucide-react';
import type { ArchitectureDrift, ChecklistItem, ReviewerSection } from '../../types';

const AREA: Record<ChecklistItem['area'], { icon: React.ElementType; label: string; cls: string }> = {
  REVIEW: { icon: Users, label: 'Review', cls: 'text-sky-300 border-sky-500/40' },
  TESTS: { icon: TestTube2, label: 'Tests', cls: 'text-emerald-300 border-emerald-500/40' },
  SECURITY: { icon: ShieldAlert, label: 'Security', cls: 'text-rose-300 border-rose-500/40' },
  ARCHITECTURE: { icon: Boxes, label: 'Architecture', cls: 'text-violet-300 border-violet-500/40' },
};

const Panel: React.FC<{ title: string; icon: React.ReactNode; subtitle?: React.ReactNode; right?: React.ReactNode; children: React.ReactNode }> =
  ({ title, icon, subtitle, right, children }) => (
    <div className="bg-slate-900 border border-slate-800 rounded-2xl p-5">
      <div className="flex items-start justify-between gap-3 mb-3">
        <div>
          <h2 className="text-sm font-bold text-white flex items-center gap-2">{icon}{title}</h2>
          {subtitle && <p className="text-[11px] text-slate-400 mt-1 leading-relaxed">{subtitle}</p>}
        </div>
        {right}
      </div>
      {children}
    </div>
  );

/** Pre-merge checklist; ticks are remembered per analysis in this browser only. */
export const ChecklistCard: React.FC<{ analysisId: string; items: ChecklistItem[] }> = ({ analysisId, items }) => {
  const key = `checklist:${analysisId}`;
  const [done, setDone] = React.useState<Set<number>>(() => {
    try { return new Set(JSON.parse(localStorage.getItem(key) ?? '[]') as number[]); } catch { return new Set(); }
  });
  const toggle = (i: number) => setDone((prev) => {
    const next = new Set(prev);
    if (next.has(i)) next.delete(i); else next.add(i);
    try { localStorage.setItem(key, JSON.stringify([...next])); } catch { /* storage unavailable: keep in memory */ }
    return next;
  });
  return (
    <Panel title="Pre-merge checklist" icon={<ClipboardCheck className="w-4 h-4 text-emerald-400" />}
      subtitle="Derived by rules from this report (also posted as a task list in the pull request's check). Ticks are saved in this browser."
      right={<span className="text-[11px] text-slate-400 whitespace-nowrap">{done.size} / {items.length} done</span>}>
      <ul className="space-y-2.5">
        {items.map((it, i) => {
          const a = AREA[it.area] ?? AREA.REVIEW;
          const checked = done.has(i);
          return (
            <li key={i} className="flex gap-3">
              <button onClick={() => toggle(i)} className="mt-0.5 shrink-0 text-slate-400 hover:text-white" aria-label={checked ? 'Mark as not done' : 'Mark as done'}>
                {checked ? <CheckSquare className="w-4 h-4 text-emerald-400" /> : <Square className="w-4 h-4" />}
              </button>
              <div className={`text-xs ${checked ? 'opacity-50 line-through' : ''}`}>
                <div className="flex flex-wrap items-center gap-2">
                  <span className={`inline-flex items-center gap-1 text-[10px] font-semibold px-1.5 py-0.5 rounded border ${a.cls}`}>
                    <a.icon className="w-3 h-3" />{a.label}
                  </span>
                  <span className="text-slate-100">{it.action}</span>
                </div>
                {it.evidence.length > 0 && (
                  <div className="text-[11px] text-slate-400 font-mono mt-1 break-all">
                    {it.evidence.join(' · ')}{it.moreEvidence ? ` … and ${it.moreEvidence} more` : ''}
                  </div>
                )}
              </div>
            </li>
          );
        })}
      </ul>
    </Panel>
  );
};

const fileName = (p: string) => p.substring(p.lastIndexOf('/') + 1);

/** Suggested reviewers: recency-weighted ownership of the changed (and impacted) files before the change. */
export const ReviewersCard: React.FC<{ section?: ReviewerSection }> = ({ section }) => {
  if (!section) return null;
  const s = section.suggestions ?? [];
  const max = Math.max(0.01, ...s.map((x) => x.score));
  return (
    <Panel title="Suggested reviewers" icon={<Users className="w-4 h-4 text-sky-400" />}
      subtitle={<>Who knows the affected code: share of each changed file's history (impacted files count half), recent work weighted
        higher, from {section.historyCommits ?? 0} commits before the change. Authors of the change are excluded.</>}>
      {section.status === 'FAILED' ? <p className="text-xs text-rose-300">Could not compute: {section.detail}</p> : s.length === 0 ? (
        <p className="text-xs text-slate-500">Nobody else changed these files in the scanned history.</p>
      ) : (
        <ul className="space-y-3">
          {s.map((r) => (
            <li key={r.email}>
              <div className="flex items-center justify-between text-xs">
                <span className="text-slate-100 font-semibold">{r.name}</span>
                <span className="text-slate-400">{r.changedFilesOwned} changed · {r.impactedFilesOwned} impacted file(s)</span>
              </div>
              <div className="h-1.5 bg-slate-800 rounded-full mt-1.5 overflow-hidden">
                <div className="h-full rounded-full bg-sky-500" style={{ width: `${(100 * r.score) / max}%` }} />
              </div>
              <div className="text-[11px] text-slate-400 mt-1 font-mono break-all">
                {r.evidence.slice(0, 3).map((e) => `${fileName(e.path)} ${Math.round(e.share * 100)}%${e.relation === 'IMPACTED' ? ' (impacted)' : ''}`).join(' · ')}
                {r.lastChange && <span className="font-sans text-slate-500"> · last change {new Date(r.lastChange).toLocaleDateString()}</span>}
              </div>
            </li>
          ))}
        </ul>
      )}
      {(section.changeAuthors?.length ?? 0) > 0 && (
        <p className="text-[11px] text-slate-500 mt-3">Change by: {section.changeAuthors!.map((a) => a.name).join(', ')}</p>
      )}
      {(section.filesWithoutHistory ?? 0) > 0 && (
        <p className="text-[11px] text-slate-500 mt-1">{section.filesWithoutHistory} changed file(s) have no earlier history (new files or older than the scanned window).</p>
      )}
    </Panel>
  );
};

const short = (p: string) => p.split('.').slice(-2).join('.');

/** Architecture drift between base and head (digital twin comparison). */
export const DriftCard: React.FC<{ drift?: ArchitectureDrift }> = ({ drift }) => {
  if (!drift) return null;
  if (drift.status === 'FAILED') return null;
  const b = drift.before, a = drift.after;
  const delta = (x: number, y: number, worseUp: boolean | null = true) => {
    const d = y - x;
    // null: size, neither direction is good or bad
    const tone = d === 0 || worseUp === null ? 'text-slate-500' : (d > 0) === worseUp ? 'text-rose-400' : 'text-emerald-400';
    return <span className={`text-[10px] font-semibold ${tone}`}>{d === 0 ? '±0' : d > 0 ? `+${d}` : d}</span>;
  };
  const drifted = (drift.newCycles?.length ?? 0) > 0 || (drift.addedDependencyCount ?? 0) > 0 || (drift.newViolationCount ?? 0) > 0;
  return (
    <Panel title="Architecture drift" icon={<GitBranch className="w-4 h-4 text-violet-400" />}
      subtitle="Package structure of the head commit compared with the base commit (production code).">
      {b && a && (
        <div className="grid grid-cols-4 gap-2 mb-3 text-center">
          {[['Packages', b.packages, a.packages, null], ['Package deps', b.packageEdges, a.packageEdges, true], ['Cycles', b.cycles, a.cycles, true],
            ['Rule violations', b.violations, a.violations, true]].map(([label, x, y, worse]) => (
            <div key={label as string} className="bg-slate-950 border border-slate-800 rounded-lg p-2">
              <div className="text-[10px] uppercase tracking-wider text-slate-500">{label as string}</div>
              <div className="text-sm text-white font-semibold">{x as number} <ArrowRight className="inline w-3 h-3 text-slate-500" /> {y as number} {delta(x as number, y as number, worse as boolean | null)}</div>
            </div>
          ))}
        </div>
      )}
      <ul className="space-y-1.5">
        {(drift.findings ?? []).map((f) => (
          <li key={f} className={`text-xs break-all ${drifted && !/resolved|removed|No structural drift/.test(f) ? 'text-amber-200' : 'text-slate-300'}`}>• {f}</li>
        ))}
      </ul>
      {(drift.addedDependencies?.length ?? 0) > 0 && (
        <div className="mt-3 text-[11px] font-mono text-slate-400 space-y-0.5">
          {drift.addedDependencies!.slice(0, 6).map((e) => (
            <div key={e.source + e.target}>+ {short(e.source)} <ArrowRight className="inline w-3 h-3" /> {short(e.target)} <span className="font-sans text-slate-500">({e.referencesAfter} refs)</span></div>
          ))}
        </div>
      )}
    </Panel>
  );
};

import React from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Plus, Trash2, Pencil, Play, RefreshCw, X, ArrowUp, ArrowDown } from 'lucide-react';
import { apiClient } from '../../api/client';
import type { ApiResponse } from '../../types';

type RuleType = 'CIRCULAR_DEPENDENCY' | 'LAYER_VIOLATION' | 'FORBIDDEN_DEPENDENCY' | 'LAYERED_ARCHITECTURE' | 'ONLY_ACCESSED_BY';
interface Layer { name: string; pattern: string }
interface Rule {
  id: string; scope: 'GLOBAL' | 'REPOSITORY'; ruleName: string; ruleType: RuleType; description?: string; severity: string;
  configuration: Record<string, any>; enabled: boolean; active: boolean; violationsAtLastCommit?: number | null;
}
interface Draft { id?: string; ruleName: string; ruleType: RuleType; description: string; severity: string; configuration: Record<string, any> }
interface Preview { commitSha: string; total: number; truncated: boolean; violations: { description: string; sourceLocation: string; severity: string }[] }

const TYPES: { key: RuleType; label: string; help: string }[] = [
  { key: 'LAYERED_ARCHITECTURE', label: 'Layered architecture', help: 'Layers from top to bottom. A layer may use the layers below it, never above. Strict mode: only the next layer down.' },
  { key: 'FORBIDDEN_DEPENDENCY', label: 'Forbidden dependency', help: 'Types matching the source pattern must not use types matching the target pattern.' },
  { key: 'ONLY_ACCESSED_BY', label: 'Only accessed by', help: 'Types matching the target pattern may only be used by types matching the allowed pattern (or by each other).' },
  { key: 'CIRCULAR_DEPENDENCY', label: 'No cycles', help: 'Types must not depend on each other in a cycle.' },
];
const typeLabel = (t: RuleType) => TYPES.find((x) => x.key === t)?.label ?? (t === 'LAYER_VIOLATION' ? 'Forbidden dependency' : t);

const empty = (): Draft => ({ ruleName: '', ruleType: 'LAYERED_ARCHITECTURE', description: '', severity: 'MEDIUM',
  configuration: { layers: [{ name: 'Web', pattern: '\\.web\\.' }, { name: 'Service', pattern: '\\.service\\.' }, { name: 'Repository', pattern: '\\.repository\\.' }], allowLayerSkipping: true } });

const errorText = (e: unknown) => (e as any)?.response?.data?.message ?? 'Request failed';
const input = 'w-full bg-slate-950 border border-slate-700 rounded-lg px-2.5 py-1.5 text-xs text-white placeholder-slate-600 font-mono';

/** Create, edit, switch off, preview and re-evaluate the architecture rules of a repository. */
export const RuleManager: React.FC<{ repositoryId: string }> = ({ repositoryId }) => {
  const qc = useQueryClient();
  const base = `/repositories/${repositoryId}/architecture/rules`;
  const { data: rules, isLoading } = useQuery({ queryKey: ['arch-rules', repositoryId], queryFn: async () => (await apiClient.get<ApiResponse<Rule[]>>(base)).data.data });
  const [draft, setDraft] = React.useState<Draft | null>(null);
  const refresh = () => { qc.invalidateQueries({ queryKey: ['arch-rules', repositoryId] }); };

  const toggle = useMutation({ mutationFn: (r: Rule) => apiClient.put(`${base}/${r.id}/active`, { active: !r.active }), onSuccess: refresh });
  const remove = useMutation({ mutationFn: (r: Rule) => apiClient.delete(`${base}/${r.id}`), onSuccess: refresh });
  const evaluate = useMutation({
    mutationFn: async () => (await apiClient.post<ApiResponse<{ violations: number; rulesInEffect: number }>>(`${base}/evaluate`)).data.data,
    onSuccess: () => { refresh(); qc.invalidateQueries({ queryKey: ['architecture-violations', repositoryId] }); qc.invalidateQueries({ queryKey: ['twin-snapshot', repositoryId] }); },
  });

  return (
    <div className="bg-slate-900 border border-slate-800 rounded-2xl p-6 space-y-4">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div className="max-w-3xl">
          <h2 className="text-base font-bold text-white">Architecture rules</h2>
          <p className="text-xs text-slate-400 mt-1">Checked on every indexing and analysis against the class-level dependency graph (production code;
            patterns are case-insensitive regular expressions on qualified names). Global defaults apply to every repository and can be switched off here.</p>
        </div>
        <div className="flex gap-2">
          <button onClick={() => evaluate.mutate()} disabled={evaluate.isPending}
            className="flex items-center gap-1.5 px-3 py-1.5 rounded-lg bg-slate-700 hover:bg-slate-600 disabled:opacity-50 text-white text-xs">
            <RefreshCw className={`w-3.5 h-3.5 ${evaluate.isPending ? 'animate-spin' : ''}`} /> Re-evaluate now</button>
          <button onClick={() => setDraft(empty())} className="flex items-center gap-1.5 px-3 py-1.5 rounded-lg bg-cyan-600 hover:bg-cyan-500 text-white text-xs font-semibold">
            <Plus className="w-3.5 h-3.5" /> New rule</button>
        </div>
      </div>
      {evaluate.isSuccess && <p className="text-xs text-emerald-300">Re-evaluated: {evaluate.data.violations} violation(s) from {evaluate.data.rulesInEffect} rule(s) in effect.</p>}
      {evaluate.isError && <p className="text-xs text-rose-300">{errorText(evaluate.error)}</p>}
      {draft && <RuleForm base={base} draft={draft} onClose={() => setDraft(null)} onSaved={() => { setDraft(null); refresh(); }} />}
      {isLoading ? <p className="text-xs text-slate-500">Loading rules…</p> : (
        <ul className="divide-y divide-slate-800 border border-slate-800 rounded-xl">
          {(rules ?? []).map((r) => (
            <li key={r.id} className={`p-3 flex flex-wrap items-center gap-3 ${r.active ? '' : 'opacity-60'}`}>
              <div className="flex-1 min-w-[14rem]">
                <div className="flex flex-wrap items-center gap-2">
                  <span className="text-sm font-semibold text-white">{r.ruleName}</span>
                  <span className="text-[10px] px-1.5 py-0.5 rounded bg-slate-800 text-slate-300">{typeLabel(r.ruleType)}</span>
                  <span className={`text-[10px] px-1.5 py-0.5 rounded ${r.scope === 'GLOBAL' ? 'bg-violet-950 text-violet-300' : 'bg-cyan-950 text-cyan-300'}`}>{r.scope === 'GLOBAL' ? 'global default' : 'this repository'}</span>
                  <span className="text-[10px] text-slate-400">{r.severity}</span>
                </div>
                {r.description && <p className="text-[11px] text-slate-400 mt-0.5">{r.description}</p>}
                <RuleSummary rule={r} />
              </div>
              <span className="text-xs text-slate-300 tabular-nums" title="Violations at the last indexed commit">
                {r.violationsAtLastCommit == null ? '–' : `${r.violationsAtLastCommit} violation${r.violationsAtLastCommit === 1 ? '' : 's'}`}</span>
              <label className="flex items-center gap-1.5 text-[11px] text-slate-300 cursor-pointer">
                <input type="checkbox" checked={r.active} disabled={toggle.isPending} onChange={() => toggle.mutate(r)} aria-label={`Rule ${r.ruleName} in effect`} /> on
              </label>
              {r.scope === 'REPOSITORY' && (
                <>
                  <button onClick={() => setDraft({ id: r.id, ruleName: r.ruleName, ruleType: r.ruleType === 'LAYER_VIOLATION' ? 'FORBIDDEN_DEPENDENCY' : r.ruleType,
                    description: r.description ?? '', severity: r.severity, configuration: r.configuration })}
                    className="p-1.5 rounded hover:bg-slate-800 text-slate-400" aria-label={`Edit ${r.ruleName}`}><Pencil className="w-3.5 h-3.5" /></button>
                  <button onClick={() => { if (window.confirm(`Delete rule "${r.ruleName}"?`)) remove.mutate(r); }}
                    className="p-1.5 rounded hover:bg-slate-800 text-rose-400" aria-label={`Delete ${r.ruleName}`}><Trash2 className="w-3.5 h-3.5" /></button>
                </>
              )}
            </li>
          ))}
        </ul>
      )}
      {(toggle.isError || remove.isError) && <p className="text-xs text-rose-300">{errorText(toggle.error ?? remove.error)}</p>}
    </div>
  );
};

const RuleSummary: React.FC<{ rule: Rule }> = ({ rule }) => {
  const c = rule.configuration ?? {};
  const code = (s: string) => <code className="px-1 rounded bg-slate-800 text-cyan-200">{s}</code>;
  let text: React.ReactNode = null;
  if (rule.ruleType === 'LAYERED_ARCHITECTURE') text = <>{(c.layers ?? []).map((l: Layer, i: number) => <span key={i}>{i > 0 && ' → '}{l.name}</span>)}{c.allowLayerSkipping === false && ' (strict)'}</>;
  else if (rule.ruleType === 'LAYER_VIOLATION' || rule.ruleType === 'FORBIDDEN_DEPENDENCY') text = <>{code(c.sourcePattern)} must not use {code(c.forbiddenTargetPattern)}</>;
  else if (rule.ruleType === 'ONLY_ACCESSED_BY') text = <>{code(c.targetPattern)} only used by {code(c.allowedSourcePattern)}</>;
  return text ? <p className="text-[11px] text-slate-500 mt-1 break-all">{text}</p> : null;
};

const RuleForm: React.FC<{ base: string; draft: Draft; onClose: () => void; onSaved: () => void }> = ({ base, draft: initial, onClose, onSaved }) => {
  const [d, setD] = React.useState<Draft>(initial);
  const set = (k: keyof Draft, v: any) => setD((x) => ({ ...x, [k]: v }));
  const setC = (k: string, v: any) => setD((x) => ({ ...x, configuration: { ...x.configuration, [k]: v } }));
  const body = () => ({ ruleName: d.ruleName, ruleType: d.ruleType, description: d.description, severity: d.severity, configuration: d.configuration });
  const preview = useMutation({ mutationFn: async () => (await apiClient.post<ApiResponse<Preview>>(`${base}/preview`, body())).data.data });
  const save = useMutation({ mutationFn: () => (d.id ? apiClient.put(`${base}/${d.id}`, body()) : apiClient.post(base, body())), onSuccess: onSaved });
  const changeType = (t: RuleType) => setD((x) => ({ ...x, ruleType: t, configuration: t === 'LAYERED_ARCHITECTURE' ? empty().configuration
    : t === 'FORBIDDEN_DEPENDENCY' ? { sourcePattern: '\\.domain\\.', forbiddenTargetPattern: '\\.web\\.' }
    : t === 'ONLY_ACCESSED_BY' ? { targetPattern: '\\.repository\\.', allowedSourcePattern: '\\.service\\.' } : {} }));
  const layers: Layer[] = d.configuration.layers ?? [];
  const setLayer = (i: number, k: keyof Layer, v: string) => setC('layers', layers.map((l, j) => (j === i ? { ...l, [k]: v } : l)));
  const move = (i: number, dir: -1 | 1) => { const ls = [...layers]; [ls[i], ls[i + dir]] = [ls[i + dir], ls[i]]; setC('layers', ls); };

  return (
    <div className="border border-cyan-900/60 bg-slate-950/60 rounded-xl p-4 space-y-3">
      <div className="flex items-center justify-between">
        <h3 className="text-sm font-semibold text-white">{d.id ? 'Edit rule' : 'New rule'}</h3>
        <button onClick={onClose} className="p-1 rounded hover:bg-slate-800 text-slate-400" aria-label="Close"><X className="w-4 h-4" /></button>
      </div>
      <div className="grid md:grid-cols-[1fr_12rem_8rem] gap-2">
        <input className={input + ' font-sans'} placeholder="Rule name" value={d.ruleName} onChange={(e) => set('ruleName', e.target.value)} maxLength={120} aria-label="Rule name" />
        <select className={input + ' font-sans'} value={d.ruleType} onChange={(e) => changeType(e.target.value as RuleType)} aria-label="Rule type">
          {TYPES.map((t) => <option key={t.key} value={t.key}>{t.label}</option>)}
        </select>
        <select className={input + ' font-sans'} value={d.severity} onChange={(e) => set('severity', e.target.value)} aria-label="Severity">
          {['HIGH', 'MEDIUM', 'LOW'].map((s) => <option key={s}>{s}</option>)}
        </select>
      </div>
      <p className="text-[11px] text-slate-400">{TYPES.find((t) => t.key === d.ruleType)?.help}</p>
      <input className={input + ' font-sans'} placeholder="Description (optional)" value={d.description} onChange={(e) => set('description', e.target.value)} maxLength={1000} aria-label="Description" />

      {d.ruleType === 'LAYERED_ARCHITECTURE' && (
        <div className="space-y-1.5">
          {layers.map((l, i) => (
            <div key={i} className="grid grid-cols-[1.5rem_8rem_1fr_auto] gap-1.5 items-center">
              <span className="text-[10px] text-slate-500 text-right">{i + 1}</span>
              <input className={input + ' font-sans'} value={l.name} onChange={(e) => setLayer(i, 'name', e.target.value)} aria-label={`Layer ${i + 1} name`} />
              <input className={input} value={l.pattern} onChange={(e) => setLayer(i, 'pattern', e.target.value)} aria-label={`Layer ${i + 1} pattern`} />
              <div className="flex">
                <button disabled={i === 0} onClick={() => move(i, -1)} className="p-1 text-slate-400 disabled:opacity-30" aria-label="Move up"><ArrowUp className="w-3.5 h-3.5" /></button>
                <button disabled={i === layers.length - 1} onClick={() => move(i, 1)} className="p-1 text-slate-400 disabled:opacity-30" aria-label="Move down"><ArrowDown className="w-3.5 h-3.5" /></button>
                <button disabled={layers.length <= 2} onClick={() => setC('layers', layers.filter((_, j) => j !== i))} className="p-1 text-rose-400 disabled:opacity-30" aria-label="Remove layer"><X className="w-3.5 h-3.5" /></button>
              </div>
            </div>
          ))}
          <div className="flex flex-wrap items-center gap-3">
            <button disabled={layers.length >= 20} onClick={() => setC('layers', [...layers, { name: `Layer ${layers.length + 1}`, pattern: '' }])}
              className="text-[11px] text-cyan-300 hover:text-cyan-200">+ add layer</button>
            <label className="flex items-center gap-1.5 text-[11px] text-slate-300">
              <input type="checkbox" checked={d.configuration.allowLayerSkipping === false} onChange={(e) => setC('allowLayerSkipping', !e.target.checked)} /> strict (no skipping layers)
            </label>
          </div>
        </div>
      )}
      {d.ruleType === 'FORBIDDEN_DEPENDENCY' && (
        <div className="grid md:grid-cols-2 gap-2">
          <input className={input} placeholder="source pattern" value={d.configuration.sourcePattern ?? ''} onChange={(e) => setC('sourcePattern', e.target.value)} aria-label="Source pattern" />
          <input className={input} placeholder="forbidden target pattern" value={d.configuration.forbiddenTargetPattern ?? ''} onChange={(e) => setC('forbiddenTargetPattern', e.target.value)} aria-label="Forbidden target pattern" />
        </div>
      )}
      {d.ruleType === 'ONLY_ACCESSED_BY' && (
        <div className="grid md:grid-cols-2 gap-2">
          <input className={input} placeholder="target pattern" value={d.configuration.targetPattern ?? ''} onChange={(e) => setC('targetPattern', e.target.value)} aria-label="Target pattern" />
          <input className={input} placeholder="allowed source pattern" value={d.configuration.allowedSourcePattern ?? ''} onChange={(e) => setC('allowedSourcePattern', e.target.value)} aria-label="Allowed source pattern" />
        </div>
      )}

      <div className="flex flex-wrap items-center gap-2">
        <button onClick={() => preview.mutate()} disabled={preview.isPending}
          className="flex items-center gap-1.5 px-3 py-1.5 rounded-lg bg-slate-700 hover:bg-slate-600 disabled:opacity-50 text-white text-xs"><Play className="w-3.5 h-3.5" /> Preview</button>
        <button onClick={() => save.mutate()} disabled={save.isPending || !d.ruleName.trim()}
          className="px-3 py-1.5 rounded-lg bg-cyan-600 hover:bg-cyan-500 disabled:opacity-50 text-white text-xs font-semibold">{d.id ? 'Save changes' : 'Create rule'}</button>
        <span className="text-[11px] text-slate-500">Saved rules apply from the next indexing or analysis, or now with "Re-evaluate".</span>
      </div>
      {(preview.isError || save.isError) && <p className="text-xs text-rose-300">{errorText(preview.error ?? save.error)}</p>}
      {preview.data && (
        <div className="text-xs">
          <p className="text-slate-300">{preview.data.total === 0 ? 'No violations' : `${preview.data.total} violation(s)`} at {preview.data.commitSha.substring(0, 7)}{preview.data.truncated ? ' (first 200 shown)' : ''}</p>
          <ul className="mt-1 space-y-1 max-h-56 overflow-y-auto">
            {preview.data.violations.slice(0, 50).map((v, i) => (
              <li key={i} className="text-[11px] text-slate-400 [overflow-wrap:anywhere]">{v.description} <span className="text-slate-600">{v.sourceLocation}</span></li>
            ))}
          </ul>
        </div>
      )}
    </div>
  );
};

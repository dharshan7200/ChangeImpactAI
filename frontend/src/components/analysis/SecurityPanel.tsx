import React, { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Info, Package, ShieldCheck, ShieldHalf } from 'lucide-react';
import { qualityApi } from '../../api/phase5';
import type { SecurityFinding } from '../../types/phase5';
import { RiskBadge } from './risk';

const SEVERITIES = ['CRITICAL', 'HIGH', 'MEDIUM', 'LOW', 'INFO'] as const;

export const SeverityBadge: React.FC<{ severity: string }> = ({ severity }) =>
  severity === 'INFO' ? (
    <span className="inline-flex items-center gap-1 text-[10px] font-bold text-slate-300 border border-slate-600 rounded px-1.5 py-0.5">
      <Info className="w-3 h-3" /> INFO
    </span>
  ) : (
    <RiskBadge level={severity} />
  );

const SOURCE_LABEL: Record<string, string> = {
  STATIC_RULES: 'sensitive-code rules',
  SEMGREP: 'Semgrep',
  BUILD_DIFF: 'build diff',
  DEPENDENCY_CHECK: 'Dependency-Check',
};

const RELATION_LABEL: Record<string, string> = {
  CHANGED: 'in changed lines',
  IMPACTED: 'affected code',
  DEPENDENCY: 'dependency',
};

export const SecurityPanel: React.FC<{ analysisId: string }> = ({ analysisId }) => {
  const { data, isLoading } = useQuery({ queryKey: ['analysis-security', analysisId], queryFn: () => qualityApi.security(analysisId) });
  const [open, setOpen] = useState<string | null>(null);

  if (isLoading) return <Shell><p className="text-xs text-slate-500">Loading security analysis…</p></Shell>;
  const s = data?.summary;
  if (!s || s.status !== 'COMPLETED') {
    return (
      <Shell>
        <p className="text-xs text-slate-500">
          {s?.status === 'FAILED' ? `Security analysis failed: ${s.detail}` : 'Security analysis was not computed for this analysis.'}
        </p>
      </Shell>
    );
  }
  const findings = data?.findings ?? [];
  const dc = typeof s.dependencyCheck === 'object' ? s.dependencyCheck : null;
  const preExisting = dc ? Object.values(dc.preExistingVulnerabilities).reduce((a, b) => a + b, 0) : 0;

  return (
    <Shell>
      <div className="flex flex-wrap items-center gap-x-6 gap-y-2 mb-4 text-xs">
        <div className="flex items-center gap-2">
          {SEVERITIES.filter((sev) => (s.bySeverity?.[sev] ?? 0) > 0).map((sev) => (
            <span key={sev} className="inline-flex items-center gap-1">
              <SeverityBadge severity={sev} /> <span className="font-mono text-slate-300">{s.bySeverity?.[sev]}</span>
            </span>
          ))}
          {findings.length === 0 && (
            <span className="text-emerald-300 flex items-center gap-1"><ShieldCheck className="w-3.5 h-3.5" /> No findings for this change</span>
          )}
        </div>
        <div className="text-slate-400">
          Semgrep: <span className="text-slate-200">{s.semgrep?.status}</span>
          {s.semgrep?.status === 'COMPLETED' ? ` · ${s.semgrep.filesScanned} file(s), ${s.semgrep.findings} match(es)` : s.semgrep?.detail ? ` · ${s.semgrep.detail}` : ''}
        </div>
        <div className="text-slate-400">
          Dependency-Check: <span className="text-slate-200">{dc ? 'report for this commit' : 'no report for this commit'}</span>
          {dc && preExisting > 0 && ` · ${preExisting} vulnerability(ies) in unchanged dependencies`}
        </div>
      </div>

      {findings.length > 0 && (
        <div className="border border-slate-800 rounded-xl divide-y divide-slate-800/70 max-h-[420px] overflow-y-auto">
          {findings.map((f: SecurityFinding) => (
            <button key={f.id} type="button" onClick={() => setOpen(open === f.id ? null : f.id)}
                    className="w-full text-left p-3 hover:bg-slate-800/40 cursor-pointer">
              <div className="flex items-start gap-3">
                <div className="shrink-0 pt-0.5"><SeverityBadge severity={f.severity} /></div>
                <div className="min-w-0 flex-1">
                  <div className="text-xs text-slate-100">{f.title}</div>
                  <div className="text-[11px] text-slate-500 mt-0.5 flex flex-wrap gap-x-3">
                    <span>{SOURCE_LABEL[f.source] ?? f.source}</span>
                    <span>{RELATION_LABEL[f.relation] ?? f.relation}</span>
                    {f.filePath && <span className="font-mono">{f.filePath}{f.startLine ? `:${f.startLine}` : ''}</span>}
                    {f.cwe && <span>{f.cwe}</span>}
                  </div>
                  {open === f.id && f.message && <div className="text-[11px] text-slate-300 mt-2 whitespace-pre-wrap">{f.message}</div>}
                </div>
              </div>
            </button>
          ))}
        </div>
      )}

      {(s.dependencyChanges?.length ?? 0) > 0 && (
        <div className="mt-4">
          <h4 className="text-xs font-semibold text-slate-200 mb-2 flex items-center gap-1.5"><Package className="w-3.5 h-3.5 text-violet-300" /> Dependency changes</h4>
          <table className="w-full text-[11px]">
            <tbody className="divide-y divide-slate-800/70">
              {s.dependencyChanges!.map((d) => (
                <tr key={d.file + d.coordinate}>
                  <td className="py-1 pr-3 font-semibold text-slate-400 w-24">{d.type}</td>
                  <td className="py-1 pr-3 font-mono text-slate-200">{d.coordinate}</td>
                  <td className="py-1 pr-3 font-mono text-slate-400">{d.before === 'null' ? '∅' : d.before} → {d.after === 'null' ? '∅' : d.after}</td>
                  <td className="py-1 text-slate-500">{d.file}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      <p className="text-[10px] text-slate-500 mt-3">{data?.note} Click a finding for details.</p>
    </Shell>
  );
};

const Shell: React.FC<{ children: React.ReactNode }> = ({ children }) => (
  <div className="bg-slate-900 border border-slate-800 rounded-2xl p-5">
    <h3 className="text-sm font-bold text-white flex items-center gap-2">
      <ShieldHalf className="w-4 h-4 text-rose-400" /> Security impact
    </h3>
    <p className="text-[11px] text-slate-500 mt-0.5 mb-3">
      Security-sensitive code touched or reached by the change, Semgrep matches in changed and directly impacted files, and
      dependency changes joined with known vulnerabilities.
    </p>
    {children}
  </div>
);

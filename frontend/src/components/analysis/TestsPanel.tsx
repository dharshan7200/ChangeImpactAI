import React, { useMemo, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { CheckCircle2, CircleDashed, FlaskConical, Lightbulb, MinusCircle, SearchX, XCircle } from 'lucide-react';
import { qualityApi } from '../../api/phase5';
import type { TestRecommendation } from '../../types/phase5';
import { shortName } from './risk';

const PRIORITY_STYLE: Record<string, string> = {
  HIGH: 'text-orange-300 border-orange-500/40',
  MEDIUM: 'text-amber-200 border-amber-500/30',
  LOW: 'text-slate-300 border-slate-600',
};

export const ExecutionChip: React.FC<{ status: TestRecommendation['executionStatus'] }> = ({ status }) => {
  const map: Record<string, { label: string; cls: string; Icon: React.ElementType }> = {
    PASSED: { label: 'passed', cls: 'text-emerald-300', Icon: CheckCircle2 },
    FAILED: { label: 'failed', cls: 'text-rose-300 font-semibold', Icon: XCircle },
    SKIPPED: { label: 'skipped', cls: 'text-slate-400', Icon: MinusCircle },
    NOT_EXECUTED: { label: 'not run', cls: 'text-amber-300', Icon: CircleDashed },
    NO_RESULTS: { label: 'no results', cls: 'text-slate-500', Icon: CircleDashed },
    NOT_APPLICABLE: { label: '—', cls: 'text-slate-600', Icon: MinusCircle },
  };
  const m = map[status] ?? map.NO_RESULTS;
  return (
    <span className={`inline-flex items-center gap-1 text-[11px] ${m.cls}`}>
      <m.Icon className="w-3 h-3" /> {m.label}
    </span>
  );
};

/** Short display name of a test: Outer.Inner#method. */
function testName(r: TestRecommendation): string {
  const cls = (r.testClass ?? '').substring((r.testClass ?? '').lastIndexOf('.') + 1).replace(/\$/g, '.');
  return `${cls}#${r.testMethod ?? ''}`;
}

const Meter: React.FC<{ value: number; max: number }> = ({ value, max }) => (
  <div className="h-1.5 bg-slate-800 rounded-full overflow-hidden" role="meter" aria-valuenow={value} aria-valuemax={max}>
    <div className="h-full rounded-full" style={{ width: `${max === 0 ? 0 : (100 * value) / max}%`, background: '#3987e5' }} />
  </div>
);

export const TestsPanel: React.FC<{ analysisId: string }> = ({ analysisId }) => {
  const { data, isLoading } = useQuery({ queryKey: ['analysis-tests', analysisId], queryFn: () => qualityApi.tests(analysisId) });
  const [filter, setFilter] = useState<'ALL' | 'DIRECT' | 'INDIRECT' | 'FAILED'>('ALL');

  const selected = useMemo(
    () => (data?.recommendations ?? []).filter((r) => r.category === 'DIRECT' || r.category === 'INDIRECT'),
    [data]
  );
  const visible = selected.filter((r) =>
    filter === 'ALL' ? true : filter === 'FAILED' ? r.executionStatus === 'FAILED' : r.category === filter
  );
  const gaps = (data?.recommendations ?? []).filter((r) => r.category === 'MISSING_TEST');
  const scenarios = (data?.recommendations ?? []).filter((r) => r.category === 'SCENARIO');

  if (isLoading) return <Shell><p className="text-xs text-slate-500">Loading test intelligence…</p></Shell>;
  const s = data?.summary;
  if (!s || s.status !== 'COMPLETED') {
    return (
      <Shell>
        <p className="text-xs text-slate-500">
          {s?.status === 'FAILED' ? `Test intelligence failed: ${s.detail}` : 'Test intelligence was not computed for this analysis.'}
        </p>
      </Shell>
    );
  }

  const exec = typeof s.executionResults === 'object' ? s.executionResults : null;
  const cov = typeof s.changedLineCoverage === 'object' ? s.changedLineCoverage : null;
  const mut = s.mutation?.status === 'AVAILABLE' ? s.mutation : null;
  const failed = selected.filter((r) => r.executionStatus === 'FAILED').length;
  const side = (cov != null && cov.bySymbol.length > 0) || (mut?.methods ?? []).some((m) => m.mutationScore < 70);

  return (
    <Shell>
      <div className="grid grid-cols-2 lg:grid-cols-4 gap-3 mb-4">
        <Tile label="Tests to run" value={selected.length}
              hint={`${s.byCategory?.DIRECT ?? 0} direct · ${s.byCategory?.INDIRECT ?? 0} indirect · ${s.testClasses ?? 0} classes`} />
        <Tile label="Last results" value={exec ? `${exec.byStatus.PASSED ?? 0} passed` : '—'}
              hint={exec ? `${failed} failed · ${exec.byStatus.NOT_EXECUTED ?? 0} not in the uploaded run` : 'no JUnit results for this commit'}
              emphasis={failed > 0} />
        <Tile label="Changed-line coverage" value={cov && cov.ratio != null ? `${cov.ratio}%` : '—'}
              hint={cov ? `${cov.changedLinesCovered} of ${cov.changedLinesExecutable} executable lines (JaCoCo)` : 'no JaCoCo report for this commit'} />
        <Tile label="Mutation score (changed)" value={mut?.mutationScore != null ? `${mut.mutationScore}%` : '—'}
              hint={mut ? `${mut.weakMethods ?? 0} method(s) below 70% (PIT)` : 'no PIT report for this commit'}
              emphasis={(mut?.weakMethods ?? 0) > 0} />
      </div>

      <div className={`grid grid-cols-1 gap-4 ${side ? 'xl:grid-cols-5' : ''}`}>
        <div className={side ? 'xl:col-span-3' : ''}>
          <div className="flex items-center justify-between mb-2">
            <h4 className="text-xs font-semibold text-slate-200">Selected tests ({visible.length})</h4>
            <div className="flex gap-1 text-[11px]">
              {(['ALL', 'DIRECT', 'INDIRECT', 'FAILED'] as const).map((f) => (
                <button key={f} type="button" onClick={() => setFilter(f)}
                        className={`px-2 py-0.5 rounded-lg border cursor-pointer ${filter === f ? 'bg-slate-800 border-slate-600 text-white' : 'border-slate-800 text-slate-400'}`}>
                  {f === 'ALL' ? 'All' : f.charAt(0) + f.slice(1).toLowerCase()}
                </button>
              ))}
            </div>
          </div>
          {visible.length === 0 ? (
            <p className="text-xs text-slate-500 py-3">No test method reaches the changed code through the dependency graph.</p>
          ) : (
            <div className="max-h-80 overflow-y-auto border border-slate-800 rounded-xl">
              <table className="w-full text-xs table-fixed">
                <colgroup><col className="w-20" /><col /><col className="w-16" /><col className="w-12" /><col className="w-24" /></colgroup>
                <thead className="sticky top-0 bg-slate-900 text-[10px] uppercase tracking-wider text-slate-500">
                  <tr><th className="text-left p-2">Priority</th><th className="text-left p-2">Test</th><th className="text-left p-2">Kind</th>
                      <th className="text-right p-2">Hops</th><th className="text-left p-2">Last result</th></tr>
                </thead>
                <tbody className="divide-y divide-slate-800/70">
                  {visible.map((r) => (
                    <tr key={r.id} className="hover:bg-slate-800/40" title={r.reason}>
                      <td className="p-2"><span className={`px-1.5 py-0.5 rounded text-[10px] font-bold border ${PRIORITY_STYLE[r.priority]}`}>{r.priority}</span></td>
                      <td className="p-2 font-mono text-slate-200 truncate" title={`${r.testClass}#${r.testMethod}`}>{testName(r)}</td>
                      <td className="p-2 text-slate-400">{r.category === 'DIRECT' ? 'direct' : 'indirect'}</td>
                      <td className="p-2 text-right font-mono text-slate-400">{r.depth ?? ''}</td>
                      <td className="p-2"><ExecutionChip status={r.executionStatus} /></td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
          <p className="text-[10px] text-slate-500 mt-1.5">
            Direct = only test code between the test and the change. Selected statically from the dependency graph; hover a row for the reason.
          </p>
        </div>

        {side && <div className="xl:col-span-2 space-y-4">
          {cov && cov.bySymbol.length > 0 && (
            <div>
              <h4 className="text-xs font-semibold text-slate-200 mb-2">Coverage of changed lines</h4>
              <div className="space-y-2">
                {cov.bySymbol.map((b) => (
                  <div key={b.symbol} className="text-[11px]">
                    <div className="flex justify-between text-slate-300">
                      <span className="font-mono truncate" title={b.symbol}>{shortName(b.symbol)}</span>
                      <span className="font-mono shrink-0 ml-2">{b.covered}/{b.executable}</span>
                    </div>
                    <Meter value={b.covered} max={b.executable} />
                  </div>
                ))}
              </div>
            </div>
          )}
          {mut && (mut.methods ?? []).some((m) => m.mutationScore < 70) && (
            <div>
              <h4 className="text-xs font-semibold text-slate-200 mb-2">Weakly tested changed methods (PIT)</h4>
              <ul className="space-y-2">
                {(mut.methods ?? []).filter((m) => m.mutationScore < 70).slice(0, 6).map((m) => (
                  <li key={m.symbol} className="text-[11px]">
                    <div className="flex justify-between">
                      <span className="font-mono text-slate-200 truncate" title={m.symbol}>{shortName(m.symbol)}</span>
                      <span className="font-mono text-amber-200 shrink-0 ml-2">{m.mutationScore}%</span>
                    </div>
                    <div className="text-slate-500">
                      {m.byStatus.SURVIVED ?? 0} survived · {m.byStatus.NO_COVERAGE ?? 0} not covered of {m.mutants}
                      {m.survivors[0] && <> — e.g. line {m.survivors[0].line}: {m.survivors[0].description ?? m.survivors[0].mutator}</>}
                    </div>
                  </li>
                ))}
              </ul>
            </div>
          )}
        </div>}
      </div>

      {(gaps.length > 0 || scenarios.length > 0) && (
        <div className="grid grid-cols-1 xl:grid-cols-2 gap-4 mt-4 pt-4 border-t border-slate-800">
          <div>
            <h4 className="text-xs font-semibold text-slate-200 mb-2 flex items-center gap-1.5"><SearchX className="w-3.5 h-3.5 text-amber-300" /> Changed code no test reaches ({gaps.length})</h4>
            <ul className="space-y-1.5">
              {gaps.map((g) => (
                <li key={g.id} className="text-[11px] flex gap-2">
                  <span className={`shrink-0 h-fit px-1.5 py-0.5 rounded text-[10px] font-bold border ${PRIORITY_STYLE[g.priority]}`}>{g.priority}</span>
                  <div className="min-w-0">
                    <div className="font-mono text-slate-200 truncate" title={g.targetSymbol}>{shortName(g.targetSymbol ?? '')}</div>
                    <div className="text-slate-500">{g.reason}</div>
                  </div>
                </li>
              ))}
            </ul>
          </div>
          <div>
            <h4 className="text-xs font-semibold text-slate-200 mb-2 flex items-center gap-1.5"><Lightbulb className="w-3.5 h-3.5 text-cyan-300" /> Suggested scenarios ({scenarios.length})</h4>
            <ul className="space-y-1.5">
              {scenarios.map((g) => (
                <li key={g.id} className="text-[11px] text-slate-300">
                  <span className="font-mono text-slate-400">{shortName(g.targetSymbol ?? '')}: </span>{g.reason}
                </li>
              ))}
            </ul>
          </div>
        </div>
      )}
    </Shell>
  );
};

const Shell: React.FC<{ children: React.ReactNode }> = ({ children }) => (
  <div className="bg-slate-900 border border-slate-800 rounded-2xl p-5">
    <h3 className="text-sm font-bold text-white flex items-center gap-2">
      <FlaskConical className="w-4 h-4 text-emerald-400" /> Tests
    </h3>
    <p className="text-[11px] text-slate-500 mt-0.5 mb-3">
      Which tests to run, whether they ran and how well the changed code is tested. Coverage, results and mutation data come
      only from reports uploaded for this exact head commit.
    </p>
    {children}
  </div>
);

const Tile: React.FC<{ label: string; value: React.ReactNode; hint?: string; emphasis?: boolean }> = ({ label, value, hint, emphasis }) => (
  <div className={`bg-slate-950 border rounded-xl p-3 ${emphasis ? 'border-orange-500/40' : 'border-slate-800'}`}>
    <div className="text-[10px] uppercase tracking-wider text-slate-400">{label}</div>
    <div className="text-xl font-bold text-white mt-0.5">{value}</div>
    {hint && <div className="text-[10px] text-slate-500 mt-0.5">{hint}</div>}
  </div>
);

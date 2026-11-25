import React, { useEffect, useMemo, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { Bar, BarChart, Cell, ResponsiveContainer, Tooltip, XAxis, YAxis, LabelList } from 'recharts';
import {
  AlertTriangle,
  ArrowLeft,
  ArrowRight,
  CheckCircle2,
  ClipboardCheck,
  Download,
  FileCode,
  GitCommit,
  Info,
  Layers,
  ListChecks,
  Network,
  Route,
  ShieldAlert,
  GitPullRequest,
} from 'lucide-react';
import { analysesApi } from '../api/analyses';
import type { ChangedSymbolView, ImpactFindingView, RiskLevel } from '../types';
import { ImpactGraph } from '../components/analysis/ImpactGraph';
import { KindChip, KIND_LABELS, RISK_COLORS, RISK_LEVELS, RiskBadge, shortName, shortSha } from '../components/analysis/risk';
import { TestsPanel } from '../components/analysis/TestsPanel';
import { SecurityPanel } from '../components/analysis/SecurityPanel';
import { ChecklistCard, DriftCard, ReviewersCard } from '../components/analysis/ReviewInsights';
import { DefectEstimateCard } from '../components/analysis/DefectModelPanel';
import { AiReviewCard } from '../components/ai/AiReviewCard';
import { BlastRadius } from '../components/analysis/BlastRadius';

export const AnalysisPage: React.FC = () => {
  const { id } = useParams<{ id: string }>();

  const { data: analysis } = useQuery({
    queryKey: ['analysis', id],
    queryFn: () => analysesApi.get(id!),
    enabled: !!id,
    refetchInterval: (q) => (q.state.data?.status === 'COMPLETED' || q.state.data?.status === 'FAILED' ? false : 1500),
  });
  const done = analysis?.status === 'COMPLETED';

  const { data: report } = useQuery({ queryKey: ['analysis-report', id], queryFn: () => analysesApi.report(id!), enabled: done });
  const { data: changes } = useQuery({ queryKey: ['analysis-changes', id], queryFn: () => analysesApi.changes(id!), enabled: done });
  const { data: risk } = useQuery({ queryKey: ['analysis-risk', id], queryFn: () => analysesApi.risk(id!), enabled: done });

  const [showTests, setShowTests] = useState(false);
  const [selectedSymbolId, setSelectedSymbolId] = useState<string | null>(null);
  const [selectedFinding, setSelectedFinding] = useState<ImpactFindingView | null>(null);
  const [impactFilter, setImpactFilter] = useState<'ALL' | 'DIRECT' | 'INDIRECT'>('ALL');

  const symbols = useMemo(
    () => (changes?.symbols ?? []).filter((s) => showTests || !s.test),
    [changes, showTests]
  );
  useEffect(() => {
    if (!selectedSymbolId && symbols.length) setSelectedSymbolId(symbols[0].id);
  }, [symbols, selectedSymbolId]);
  const selected = symbols.find((s) => s.id === selectedSymbolId) ?? null;

  const { data: impactsPage } = useQuery({
    queryKey: ['analysis-impacts', id, selectedSymbolId],
    queryFn: () => analysesApi.impacts(id!, { changedSymbolId: selectedSymbolId!, includeTests: true }),
    enabled: done && !!selectedSymbolId,
  });
  const impacts = useMemo(
    () =>
      (impactsPage?.content ?? []).filter(
        (f) => (showTests || !f.test) && (impactFilter === 'ALL' || f.impactType === impactFilter)
      ),
    [impactsPage, showTests, impactFilter]
  );
  useEffect(() => setSelectedFinding(null), [selectedSymbolId]);

  const factors = risk?.symbols.find((s) => s.changedSymbolId === selectedSymbolId)?.factors ?? [];

  const downloadMarkdown = async () => {
    const md = await analysesApi.reportMarkdown(id!);
    const url = URL.createObjectURL(new Blob([md], { type: 'text/markdown' }));
    const a = document.createElement('a');
    a.href = url;
    a.download = `change-impact-${shortSha(analysis?.baseCommit)}-${shortSha(analysis?.headCommit)}.md`;
    a.click();
    URL.revokeObjectURL(url);
  };

  if (!analysis) return <div className="py-20 text-center text-slate-500 text-sm">Loading analysis…</div>;
  const s = analysis.summary;

  return (
    <div className="space-y-6">
      <Link to={`/repositories/${analysis.repositoryId}`} className="inline-flex items-center gap-1.5 text-xs text-slate-400 hover:text-cyan-400">
        <ArrowLeft className="w-3.5 h-3.5" /> Back to repository
      </Link>

      {/* Header */}
      <div className="bg-slate-900 border border-slate-800 rounded-2xl p-6 flex flex-col lg:flex-row lg:items-center justify-between gap-4">
        <div className="min-w-0">
          <div className="text-[11px] uppercase tracking-wider text-slate-500 mb-1">Change impact analysis · {report?.repository}</div>
          <div className="flex flex-wrap items-center gap-2 font-mono text-lg text-white">
            <GitCommit className="w-5 h-5 text-slate-500" />
            {shortSha(analysis.baseCommit ?? analysis.baseRef)}
            <ArrowRight className="w-4 h-4 text-slate-500" />
            {shortSha(analysis.headCommit ?? analysis.headRef)}
            {s?.usedMergeBase && <span className="text-[10px] font-sans text-slate-400 border border-slate-700 rounded px-1.5 py-0.5">merge-base</span>}
            {analysis.options?.pullRequestNumber != null && (
              <span className="text-[10px] font-sans text-violet-200 border border-violet-500/40 rounded px-1.5 py-0.5 inline-flex items-center gap-1">
                <GitPullRequest className="w-3 h-3" /> PR #{analysis.options.pullRequestNumber}
              </span>
            )}
          </div>
          {report && (
            <div className="text-xs text-slate-400 mt-1.5 space-y-0.5">
              <div className="truncate">base: {report.base.message ?? report.base.ref}</div>
              <div className="truncate">head: {report.head.message ?? report.head.ref}</div>
            </div>
          )}
        </div>
        <div className="flex items-center gap-3 shrink-0">
          {done && <RiskBadge level={analysis.riskLevel} score={analysis.riskScore} size="lg" />}
          {done && (
            <button
              type="button"
              onClick={downloadMarkdown}
              className="px-3 py-2 rounded-xl bg-slate-800 hover:bg-slate-700 text-slate-200 text-xs flex items-center gap-1.5 border border-slate-700 cursor-pointer"
            >
              <Download className="w-3.5 h-3.5" /> Markdown report
            </button>
          )}
        </div>
      </div>

      {/* Progress / failure */}
      {(analysis.status === 'QUEUED' || analysis.status === 'RUNNING') && (
        <div className="bg-slate-900 border border-cyan-500/30 rounded-2xl p-6">
          <div className="flex items-center justify-between text-xs text-slate-300 mb-2">
            <span className="font-semibold text-cyan-300">{analysis.stage ?? analysis.status}</span>
            <span className="font-mono">{analysis.progressPercent ?? 0}%</span>
          </div>
          <div className="h-2 bg-slate-800 rounded-full overflow-hidden">
            <div className="h-full bg-cyan-400 transition-all" style={{ width: `${Math.max(analysis.progressPercent ?? 0, 4)}%` }} />
          </div>
          <p className="text-xs text-slate-400 mt-2">{analysis.progressMessage}</p>
        </div>
      )}
      {analysis.status === 'FAILED' && (
        <div className="bg-rose-950/40 border border-rose-800/60 rounded-2xl p-5 text-sm text-rose-200 flex items-start gap-2">
          <AlertTriangle className="w-4 h-4 mt-0.5 shrink-0" />
          <div>
            <div className="font-semibold">Analysis failed</div>
            <div className="text-xs text-rose-300 mt-1">{analysis.errorMessage}</div>
          </div>
        </div>
      )}

      {done && s && (
        <>
          {/* Headline numbers */}
          <div className="grid grid-cols-2 md:grid-cols-3 xl:grid-cols-6 gap-3">
            <Stat label="Files changed" value={s.filesChanged} hint={`+${s.additions} / −${s.deletions} lines`} />
            <Stat label="Symbols changed" value={s.productionSymbolsChanged} hint={`${s.symbolsChanged - s.productionSymbolsChanged} in test code`} />
            <Stat label="Contract-breaking" value={s.breakingChanges} hint="signature / visibility / mapping" emphasis={s.breakingChanges > 0} />
            <Stat label="Directly impacted" value={s.directlyImpacted} hint="verified references" />
            <Stat label="Total impacted" value={s.totalImpacted} hint={`within ${analysis.options?.maxDepth ?? 3} hops`} />
            <Stat label="Tests referencing" value={s.impactedTests} hint="test symbols (static)" />
          </div>

          <div className="grid grid-cols-1 xl:grid-cols-3 gap-4">
            <Card title="Risk distribution" icon={<ShieldAlert className="w-4 h-4 text-amber-400" />} subtitle="Production symbol changes per heuristic risk level">
              <RiskDistribution distribution={s.riskDistribution} />
            </Card>
            <Card title="Changes by kind" icon={<Layers className="w-4 h-4 text-cyan-400" />} subtitle="Semantic classification (comments/formatting ignored)">
              <div className="space-y-1.5">
                {Object.entries(s.changesByKind)
                  .sort((a, b) => b[1] - a[1])
                  .map(([kind, n]) => (
                    <div key={kind} className="flex items-center justify-between text-xs">
                      <KindChip kind={kind} />
                      <span className="font-mono text-slate-300">{n}</span>
                    </div>
                  ))}
                {s.cosmeticJavaFiles.length > 0 && (
                  <p className="text-[11px] text-slate-500 pt-1">
                    {s.cosmeticJavaFiles.length} source file(s) changed only in comments/formatting.
                  </p>
                )}
              </div>
            </Card>
            <Card title="Affected packages" icon={<Network className="w-4 h-4 text-violet-400" />} subtitle="Impacted production symbols per package">
              {s.affectedPackages.length === 0 ? (
                <p className="text-xs text-slate-500">No production code depends on the changed symbols.</p>
              ) : (
                <div className="space-y-1.5">
                  {s.affectedPackages.slice(0, 8).map((p) => (
                    <div key={p.packageName} className="text-xs">
                      <div className="flex justify-between text-slate-300">
                        <span className="font-mono truncate" title={p.packageName}>{p.packageName || '(default)'}</span>
                        <span className="font-mono">{p.impactedSymbols}</span>
                      </div>
                      <div className="h-1.5 bg-slate-800 rounded-full mt-1 overflow-hidden">
                        <div
                          className="h-full rounded-full"
                          style={{
                            width: `${(100 * p.impactedSymbols) / Math.max(...s.affectedPackages.map((x) => x.impactedSymbols))}%`,
                            background: '#3987e5',
                          }}
                        />
                      </div>
                    </div>
                  ))}
                </div>
              )}
            </Card>
          </div>

          {/* Pre-merge checklist, reviewers, architecture drift */}
          {report && (
            <div className="grid grid-cols-1 xl:grid-cols-2 gap-4">
              {report.checklist && report.checklist.length > 0 && <ChecklistCard analysisId={analysis.id} items={report.checklist} />}
              <div className="space-y-4">
                <ReviewersCard section={report.reviewers} />
                <DriftCard drift={report.architecture} />
                <DefectEstimateCard view={report.defectModel} />
              </div>
            </div>
          )}

          <AiReviewCard analysisId={analysis.id} />

          <BlastRadius analysisId={analysis.id} />

          {/* Verification steps */}
          {report?.verificationSteps && report.verificationSteps.length > 0 && (
            <Card title="Recommended verification" icon={<ListChecks className="w-4 h-4 text-emerald-400" />} subtitle="Generated by explicit rules from the deterministic findings">
              <ul className="space-y-2">
                {report.verificationSteps.map((step, i) => (
                  <li key={i} className="flex gap-3 text-xs">
                    <span
                      className={`shrink-0 h-fit px-1.5 py-0.5 rounded text-[10px] font-bold border ${
                        step.priority === 'HIGH' ? 'text-orange-300 border-orange-500/40' : 'text-slate-300 border-slate-600'
                      }`}
                    >
                      {step.priority}
                    </span>
                    <div>
                      <div className="text-slate-100">{step.action}</div>
                      <div className="text-slate-500">{step.reason}</div>
                      {step.evidence.length > 0 && (
                        <div className="text-[11px] text-slate-400 font-mono mt-0.5">{step.evidence.slice(0, 6).map((e) => (/^[\w.$]+(#\S*)?$/.test(e) ? shortName(e) : e)).join(' · ')}</div>
                      )}
                    </div>
                  </li>
                ))}
              </ul>
            </Card>
          )}

          {/* Phase 5: tests and security */}
          <TestsPanel analysisId={analysis.id} />
          <SecurityPanel analysisId={analysis.id} />

          {/* Changed symbols + detail */}
          <div className="grid grid-cols-1 xl:grid-cols-5 gap-4">
            <div className="xl:col-span-2 bg-slate-900 border border-slate-800 rounded-2xl p-4 flex flex-col max-h-[880px]">
              <div className="flex items-center justify-between mb-3">
                <h2 className="text-sm font-bold text-white">Changed symbols ({symbols.length})</h2>
                <label className="text-[11px] text-slate-400 flex items-center gap-1.5 cursor-pointer">
                  <input type="checkbox" checked={showTests} onChange={(e) => setShowTests(e.target.checked)} /> include test code
                </label>
              </div>
              <div className="overflow-y-auto space-y-1.5 pr-1">
                {symbols.map((sym) => (
                  <button
                    key={sym.id}
                    type="button"
                    onClick={() => setSelectedSymbolId(sym.id)}
                    className={`w-full text-left p-2.5 rounded-xl border transition cursor-pointer ${
                      sym.id === selectedSymbolId ? 'border-cyan-500/60 bg-cyan-500/5' : 'border-slate-800 bg-slate-950 hover:border-slate-600'
                    }`}
                  >
                    <div className="flex items-center justify-between gap-2">
                      <span className="font-mono text-xs text-slate-100 truncate" title={sym.qualifiedName}>
                        {shortName(sym.qualifiedName)}
                      </span>
                      <RiskBadge level={sym.riskLevel} score={sym.riskScore} />
                    </div>
                    <div className="flex flex-wrap gap-1 mt-1.5 items-center">
                      {sym.changeKinds.map((k) => (
                        <KindChip key={k} kind={k} />
                      ))}
                      {sym.breaking && <span className="text-[10px] text-orange-300 font-semibold">contract-breaking</span>}
                      {sym.test && <span className="text-[10px] text-slate-500">test code</span>}
                    </div>
                    <div className="text-[10px] text-slate-500 mt-1">
                      {sym.directDependents} direct · {sym.transitiveDependents} transitive dependents
                    </div>
                  </button>
                ))}
              </div>
            </div>

            <div className="xl:col-span-3 space-y-4">
              {selected ? (
                <>
                  <SymbolDetail symbol={selected} />
                  <Card
                    title="Why this risk level"
                    icon={<Info className="w-4 h-4 text-amber-400" />}
                    subtitle="Each factor's contribution in points (weighted, 0–100 total). Heuristic, not a probability."
                  >
                    <FactorBars factors={factors} />
                  </Card>
                  <div className="bg-slate-900 border border-slate-800 rounded-2xl p-4">
                    <div className="flex flex-wrap items-center justify-between gap-2 mb-3">
                      <h3 className="text-sm font-bold text-white flex items-center gap-2">
                        <Network className="w-4 h-4 text-cyan-400" /> Impact graph
                      </h3>
                      <div className="flex items-center gap-1 text-[11px]">
                        {(['ALL', 'DIRECT', 'INDIRECT'] as const).map((f) => (
                          <button
                            key={f}
                            type="button"
                            onClick={() => setImpactFilter(f)}
                            className={`px-2 py-1 rounded-lg border cursor-pointer ${
                              impactFilter === f ? 'bg-slate-800 border-slate-600 text-white' : 'border-slate-800 text-slate-400'
                            }`}
                          >
                            {f === 'ALL' ? 'All' : f === 'DIRECT' ? 'Direct' : 'Indirect'}
                          </button>
                        ))}
                      </div>
                    </div>
                    <div className="h-[380px] bg-slate-950 border border-slate-800 rounded-xl overflow-hidden">
                      <ImpactGraph
                        changed={selected}
                        findings={impacts}
                        selectedFindingId={selectedFinding?.id}
                        onSelect={setSelectedFinding}
                      />
                    </div>
                    <p className="text-[10px] text-slate-500 mt-2">
                      Arrows show the direction impact travels (to dependents). Orange = direct, verified reference; grey = indirect
                      (each edge verified, behavioural effect inferred). Dashed = test code. Number = impact strength 0–1.
                    </p>
                  </div>
                  <ImpactList findings={impacts} selected={selectedFinding} onSelect={setSelectedFinding} />
                </>
              ) : (
                <div className="bg-slate-900 border border-slate-800 rounded-2xl p-10 text-center text-xs text-slate-500">
                  No production symbols changed. Enable “include test code” to see test-only changes.
                </div>
              )}
            </div>
          </div>

          {/* Files, config, architecture, tests */}
          <div className="grid grid-cols-1 xl:grid-cols-2 gap-4">
            <Card title={`Changed files (${changes?.files.length ?? 0})`} icon={<FileCode className="w-4 h-4 text-amber-400" />} subtitle="JGit diff with rename detection; whitespace-only edits ignored">
              <div className="max-h-72 overflow-y-auto divide-y divide-slate-800/60">
                {(changes?.files ?? []).map((f) => (
                  <div key={f.id} className="py-1.5 flex items-center gap-2 text-xs">
                    <span className="w-16 shrink-0 text-[10px] font-bold text-slate-400">{f.changeType}</span>
                    <span className="font-mono text-slate-200 truncate flex-1" title={f.oldPath ? `${f.oldPath} → ${f.path}` : f.path}>
                      {f.path}
                    </span>
                    <span className="text-[10px] text-slate-500 shrink-0">{f.fileCategory.replace('_', ' ').toLowerCase()}</span>
                    <span className="font-mono text-[11px] shrink-0 text-slate-300">
                      +{f.additions} −{f.deletions}
                    </span>
                  </div>
                ))}
              </div>
              {s.configKeyChanges.length > 0 && (
                <div className="mt-3 pt-3 border-t border-slate-800">
                  <div className="text-xs font-semibold text-slate-200 mb-1">Configuration keys changed</div>
                  {s.configKeyChanges.map((k) => (
                    <div key={k.file + k.key} className="text-[11px] font-mono text-slate-400">
                      {k.changeType} {k.key}: {k.before ?? '∅'} → {k.after ?? '∅'}
                    </div>
                  ))}
                </div>
              )}
            </Card>
            <div className="space-y-4">
              <Card title="Architecture concerns" icon={<ShieldAlert className="w-4 h-4 text-rose-400" />} subtitle="Violations at head that involve changed or directly impacted classes">
                {!report?.architectureConcerns?.length ? (
                  <p className="text-xs text-slate-500 flex items-center gap-1.5">
                    <CheckCircle2 className="w-3.5 h-3.5 text-emerald-400" /> None involve the changed code.
                  </p>
                ) : (
                  <ul className="space-y-1.5 text-xs">
                    {report.architectureConcerns.map((c, i) => (
                      <li key={i} className="text-slate-300">
                        <span className="text-rose-300 font-semibold">{c.rule}</span>
                        <div className="text-slate-500 text-[11px]">{c.location}</div>
                      </li>
                    ))}
                  </ul>
                )}
              </Card>
            </div>
          </div>

          <p className="text-[11px] text-slate-500 flex gap-1.5">
            <ClipboardCheck className="w-3.5 h-3.5 shrink-0" /> {report?.disclaimer}
          </p>
        </>
      )}
    </div>
  );
};

// ---------------------------------------------------------------------------

const Stat: React.FC<{ label: string; value: number; hint?: string; emphasis?: boolean }> = ({ label, value, hint, emphasis }) => (
  <div className={`bg-slate-900 border rounded-xl p-4 ${emphasis ? 'border-orange-500/40' : 'border-slate-800'}`}>
    <div className="text-[11px] uppercase tracking-wider text-slate-400">{label}</div>
    <div className="text-2xl font-bold text-white mt-1">{value}</div>
    {hint && <div className="text-[10px] text-slate-500 mt-0.5">{hint}</div>}
  </div>
);

const Card: React.FC<{ title: string; subtitle?: string; icon?: React.ReactNode; children: React.ReactNode }> = ({ title, subtitle, icon, children }) => (
  <div className="bg-slate-900 border border-slate-800 rounded-2xl p-5">
    <h3 className="text-sm font-bold text-white flex items-center gap-2">
      {icon}
      {title}
    </h3>
    {subtitle && <p className="text-[11px] text-slate-500 mt-0.5 mb-3">{subtitle}</p>}
    {children}
  </div>
);

const RiskDistribution: React.FC<{ distribution: Record<RiskLevel, number> }> = ({ distribution }) => {
  const data = RISK_LEVELS.map((level) => ({ level, count: distribution?.[level] ?? 0 }));
  return (
    <div className="h-44">
      <ResponsiveContainer width="100%" height="100%">
        <BarChart data={data} layout="vertical" margin={{ left: 4, right: 36 }}>
          <XAxis type="number" hide allowDecimals={false} />
          <YAxis type="category" dataKey="level" width={78} tick={{ fill: '#cbd5e1', fontSize: 11 }} axisLine={false} tickLine={false} />
          <Tooltip
            cursor={{ fill: 'rgba(148,163,184,0.08)' }}
            contentStyle={{ background: '#0f172a', border: '1px solid #334155', borderRadius: 10, fontSize: 12 }}
            labelStyle={{ color: '#e2e8f0' }}
            itemStyle={{ color: '#e2e8f0' }}
            formatter={(v) => [`${v} symbol change(s)`, 'Count']}
          />
          <Bar isAnimationActive={false} dataKey="count" radius={[0, 4, 4, 0]} barSize={18}>
            {data.map((d) => (
              <Cell key={d.level} fill={RISK_COLORS[d.level]} />
            ))}
            <LabelList dataKey="count" position="right" fill="#e2e8f0" fontSize={11} />
          </Bar>
        </BarChart>
      </ResponsiveContainer>
    </div>
  );
};

const FactorBars: React.FC<{ factors: { key: string; label: string; contribution: number; normalized: number; weight: number; explanation: string }[] }> = ({ factors }) => {
  const max = Math.max(1, ...factors.map((f) => f.contribution));
  return (
    <div className="space-y-2">
      {factors.map((f) => (
        <div key={f.key} className="text-xs" title={`normalized ${f.normalized} × weight ${f.weight}`}>
          <div className="flex justify-between">
            <span className="text-slate-200">{f.label}</span>
            <span className="font-mono text-slate-300">{f.contribution.toFixed(1)} pts</span>
          </div>
          <div className="h-1.5 bg-slate-800 rounded-full mt-1 overflow-hidden">
            <div className="h-full rounded-full" style={{ width: `${(100 * f.contribution) / max}%`, background: '#3987e5' }} />
          </div>
          <div className="text-[10px] text-slate-500 mt-0.5">{f.explanation}</div>
        </div>
      ))}
    </div>
  );
};

const SymbolDetail: React.FC<{ symbol: ChangedSymbolView }> = ({ symbol }) => {
  const details = symbol.details ?? {};
  const entries = Object.entries(details);
  return (
    <div className="bg-slate-900 border border-slate-800 rounded-2xl p-5">
      <div className="flex flex-wrap items-start justify-between gap-2">
        <div className="min-w-0">
          <div className="text-[10px] uppercase tracking-wider text-slate-500">{symbol.symbolType}</div>
          <div className="font-mono text-sm text-white break-all">{symbol.qualifiedName}</div>
          {symbol.previousQualifiedName && (
            <div className="font-mono text-[11px] text-slate-500 break-all">was {symbol.previousQualifiedName}</div>
          )}
          <div className="text-[11px] text-slate-400 mt-1 font-mono">
            {symbol.filePath}:{symbol.startLine}
          </div>
        </div>
        <RiskBadge level={symbol.riskLevel} score={symbol.riskScore} size="lg" />
      </div>
      <div className="flex flex-wrap gap-1.5 mt-3">
        {symbol.changeKinds.map((k) => (
          <KindChip key={k} kind={k} />
        ))}
      </div>
      {entries.length > 0 && (
        <div className="mt-3 space-y-1.5">
          {entries.map(([key, value]) => (
            <DetailRow key={key} name={key} value={value} />
          ))}
        </div>
      )}
      {symbol.changeKinds.length === 1 && symbol.changeKinds[0] === 'BODY_CHANGED' && (
        <p className="text-[11px] text-slate-500 mt-2">Signature, visibility and annotations are unchanged; only the implementation differs.</p>
      )}
      {symbol.changeKinds.includes('ADDED') && (
        <p className="text-[11px] text-slate-500 mt-2">{KIND_LABELS.ADDED}: new code has no previous dependents; impact shown is from the head commit.</p>
      )}
    </div>
  );
};

const DetailRow: React.FC<{ name: string; value: unknown }> = ({ name, value }) => {
  const label = name.replace(/([A-Z])/g, ' $1').toLowerCase();
  if (value && typeof value === 'object' && !Array.isArray(value) && 'before' in (value as Record<string, unknown>)) {
    const v = value as { before: unknown; after: unknown };
    return (
      <div className="text-xs grid grid-cols-[120px_1fr] gap-2">
        <span className="text-slate-500">{label}</span>
        <span className="font-mono">
          <span className="text-rose-300 line-through decoration-rose-500/50">{String(v.before ?? '∅')}</span>
          <span className="text-slate-500"> → </span>
          <span className="text-emerald-300">{String(v.after ?? '∅')}</span>
        </span>
      </div>
    );
  }
  const list = Array.isArray(value) ? value : [value];
  if (list.length === 0) return null;
  return (
    <div className="text-xs grid grid-cols-[120px_1fr] gap-2">
      <span className="text-slate-500">{label}</span>
      <span className="font-mono text-slate-200 break-all">{list.map(String).join(', ')}</span>
    </div>
  );
};

const ImpactList: React.FC<{
  findings: ImpactFindingView[];
  selected: ImpactFindingView | null;
  onSelect: (f: ImpactFindingView) => void;
}> = ({ findings, selected, onSelect }) => (
  <div className="bg-slate-900 border border-slate-800 rounded-2xl p-4">
    <h3 className="text-sm font-bold text-white flex items-center gap-2 mb-3">
      <Route className="w-4 h-4 text-cyan-400" /> Affected symbols &amp; dependency paths ({findings.length})
    </h3>
    {findings.length === 0 ? (
      <p className="text-xs text-slate-500">No affected symbols with the current filter.</p>
    ) : (
      <div className="max-h-96 overflow-y-auto divide-y divide-slate-800/60">
        {findings.map((f) => (
          <div key={f.id} className="py-2">
            <button type="button" onClick={() => onSelect(f)} className="w-full text-left cursor-pointer">
              <div className="flex items-center gap-2 text-xs">
                <span className={`text-[10px] font-bold w-16 shrink-0 ${f.impactType === 'DIRECT' ? 'text-orange-300' : 'text-slate-400'}`}>
                  {f.impactType}
                </span>
                <span className="font-mono text-slate-100 truncate flex-1" title={f.affectedQualifiedName}>
                  {shortName(f.affectedQualifiedName)}
                </span>
                {f.test && <span className="text-[10px] text-slate-500">test</span>}
                {f.alsoChanged && <span className="text-[10px] text-cyan-300">also changed</span>}
                <span className="font-mono text-[11px] text-slate-400 w-12 text-right">{f.strength.toFixed(2)}</span>
              </div>
            </button>
            {selected?.id === f.id && (
              <div className="mt-2 ml-16 space-y-1">
                <div className="text-[10px] text-slate-500">
                  {f.evidence === 'VERIFIED_REFERENCE'
                    ? 'Verified: this symbol references the changed symbol directly.'
                    : 'Transitive: every edge below is a resolved reference; the behavioural effect is inferred.'}{' '}
                  Each line depends on the line above it.{' '}
                  {f.filePath}:{f.startLine}
                </div>
                <ol className="space-y-0.5">
                  {f.dependencyPath.map((step, i) => (
                    <li key={i} className="text-[11px] font-mono flex items-center gap-1.5">
                      <span className="text-slate-600 w-4">{i}</span>
                      <span className="text-slate-200">{shortName(step.qualifiedName)}</span>
                      {i > 0 && (
                        <span className="text-slate-500">
                          {step.relationship.length ? step.relationship.map((r) => r.toLowerCase().replace(/_/g, ' ')).join(', ') : 'member'}
                          {step.line ? ` · line ${step.line}` : ''}
                        </span>
                      )}
                    </li>
                  ))}
                </ol>
              </div>
            )}
          </div>
        ))}
      </div>
    )}
  </div>
);

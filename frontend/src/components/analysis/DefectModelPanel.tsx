import React from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { FlaskConical, Play } from 'lucide-react';
import { apiClient } from '../../api/client';
import { defectApi } from '../../api/defect';
import type { CalibrationBin, DefectModel, DefectPrediction } from '../../types/defect';
import { AXIS, Card, DIVERGING, GRID, MUTED, SERIES, STATUS, Tile, TooltipBox, fmt, sequential } from '../architecture/twinShared';

const FEATURE_INFO: Record<string, string> = {
  NS: 'subsystems (top-level directories) touched', ND: 'directories touched', NF: 'files changed',
  ENTROPY: 'how evenly the change is spread over its files', LA: 'lines added', LD: 'lines deleted',
  LT: 'size of the touched files before the change', FIX: 'the commit is a bug fix', NDEV: 'earlier authors of the files',
  AGE: 'days since the files last changed', NUC: 'earlier commits to the files', EXP: "author's earlier commits",
  REXP: "author's recent experience", SEXP: "author's commits in the same subsystems",
};

const STATUS_LABEL: Record<string, { text: string; colour: string }> = {
  ACCEPTED: { text: 'Accepted — used in analyses', colour: STATUS.good },
  INSUFFICIENT_LABELS: { text: 'Not used — too few labelled bugs', colour: STATUS.warning },
  NOT_BETTER_THAN_BASELINE: { text: 'Not used — no better than the baseline', colour: STATUS.warning },
};

const pct = (v: number | null | undefined, d = 0) => (v == null || Number.isNaN(v) ? '—' : `${(100 * v).toFixed(d)} %`);
const day = (iso?: string | null) => (iso ? new Date(iso).toLocaleDateString(undefined, { year: 'numeric', month: 'short' }) : '—');

/**
 * Experimental just-in-time defect prediction: a logistic model trained on the repository's own history, with
 * bug-introducing commits found by SZZ. Shows how it was evaluated (time-ordered) against simple baselines, how well
 * its estimates are calibrated, what drives them, and — only if it passed the gate — scores of recent commits.
 */
export const DefectModelPanel: React.FC<{ repositoryId: string }> = ({ repositoryId }) => {
  const qc = useQueryClient();
  const [jobId, setJobId] = React.useState<string | null>(null);
  const { data: model, isLoading } = useQuery({ queryKey: ['defect-model', repositoryId], queryFn: () => defectApi.get(repositoryId) });
  const start = useMutation({ mutationFn: () => defectApi.train(repositoryId), onSuccess: (r) => setJobId(r.jobId) });
  const { data: job } = useQuery({
    queryKey: ['defect-job', jobId],
    enabled: !!jobId,
    queryFn: async () => (await apiClient.get(`/jobs/${jobId}`)).data.data as { status: string; progressPercent?: number; currentMessage?: string; errorMessage?: string },
    refetchInterval: (q) => (q.state.data && ['COMPLETED', 'FAILED'].includes(q.state.data.status) ? false : 2000),
  });
  React.useEffect(() => {
    if (job?.status === 'COMPLETED') qc.invalidateQueries({ queryKey: ['defect-model', repositoryId] });
  }, [job?.status, qc, repositoryId]);
  const running = !!job && !['COMPLETED', 'FAILED'].includes(job.status);

  const trainButton = (
    <div className="flex items-center gap-3">
      {running && <span className="text-[11px] text-slate-400">{job?.currentMessage} {job?.progressPercent != null && `(${job.progressPercent} %)`}</span>}
      {job?.status === 'FAILED' && <span className="text-[11px] text-rose-400">Failed: {job.errorMessage}</span>}
      <button onClick={() => start.mutate()} disabled={running || start.isPending}
        className="flex items-center gap-1.5 px-3 py-1.5 rounded-lg bg-sky-600 hover:bg-sky-500 disabled:opacity-50 text-white text-xs font-semibold">
        <Play className="w-3.5 h-3.5" />{model ? 'Retrain from history' : 'Train from history'}
      </button>
    </div>
  );

  return (
    <Card title="Defect prediction from history (experimental)"
      subtitle={<>A logistic model learns which kinds of commits introduced bugs in this repository: bug-fix commits are recognised by their
        message and traced back to the commits that last touched the lines they changed (SZZ). It is evaluated on later commits than it
        was trained on, against the simple rule “larger changes are riskier”, and is only used in analyses if it clearly beats it.
        Its scores are estimates from past patterns, never findings about the code.</>}
      right={trainButton}>
      {isLoading ? <div className="py-10 text-center text-xs text-slate-500">Loading…</div>
        : !model ? <div className="py-10 text-center text-xs text-slate-500 flex flex-col items-center gap-2"><FlaskConical className="w-6 h-6 text-slate-600" />
            Not trained yet. Training reads up to 5,000 commits of the default branch; on large repositories it takes a few minutes.</div>
        : <ModelView model={model} />}
    </Card>
  );
};

const ModelView: React.FC<{ model: DefectModel }> = ({ model }) => {
  const ev = model.evaluation;
  const st = STATUS_LABEL[model.status] ?? { text: model.status, colour: MUTED };
  return (
    <div className="space-y-5">
      <div className="flex flex-wrap items-center gap-3 text-xs">
        <span className="flex items-center gap-1.5 px-2.5 py-1 rounded-full border border-slate-700 text-slate-200">
          <span className="w-2 h-2 rounded-full" style={{ background: st.colour }} />{st.text}
        </span>
        <span className="text-slate-400">{model.reason}</span>
        {!model.current && <span className="text-amber-300">trained by an older version — retrain to use it</span>}
        <span className="text-slate-500 ml-auto">trained {new Date(model.trainedAt).toLocaleString()} at {model.headCommit.substring(0, 7)}</span>
      </div>

      <div className="grid grid-cols-2 md:grid-cols-4 xl:grid-cols-6 gap-3">
        <Tile label="Commits" value={fmt(model.dataset.commits)} hint={`${day(model.dataset.from)} – ${day(model.dataset.to)}; non-merge commits that change code`} />
        <Tile label="Bug fixes" value={fmt(model.dataset.fixes)} hint={`${model.dataset.tracedFixes} traced to their origin; ${model.dataset.untraceableFixes} only add lines or are too large`} />
        <Tile label="Bug-introducing" value={fmt(model.dataset.bugIntroducingCommits)} hint="commits whose lines a later fix changed (SZZ)" />
        <Tile label="Training" value={fmt(ev.split.train)} hint={ev.trainPositives != null ? `${ev.trainPositives} labelled bug-introducing (fixes known before the test period)` : 'after a warm-up of ' + ev.split.warmup} />
        <Tile label="Test (later)" value={fmt(ev.split.test)} hint={ev.testPositives != null ? `${ev.testPositives} bug-introducing; newest ${ev.split.immature} excluded (bugs not found yet)` : undefined} />
        <Tile label="Bug rate" value={pct(ev.testPositiveRate, 1)} hint="share of test commits labelled bug-introducing" />
      </div>

      {ev.model ? (
        <>
          <MetricsTable model={model} />
          <div className="grid grid-cols-1 xl:grid-cols-2 gap-4">
            <Calibration bins={ev.model.calibration} ece={ev.model.ece} />
            <Coefficients features={ev.model.coefficients.features} />
          </div>
        </>
      ) : (
        <p className="text-xs text-slate-400">No model was evaluated: the time-ordered split needs at least 30 bug-introducing commits in training and
          10 in the test period. Typical causes: a short history, few commit messages that mention fixes, or fixes that only add code.</p>
      )}
      {model.status === 'ACCEPTED' && model.predictions.length > 0 && <Predictions rows={model.predictions} threshold={model.threshold ?? 0.5} />}
    </div>
  );
};

const MetricsTable: React.FC<{ model: DefectModel }> = ({ model }) => {
  const ev = model.evaluation, m = ev.model!, c = ev.churnBaseline!, p = ev.priorBaseline!;
  const rows: [string, React.ReactNode, React.ReactNode, React.ReactNode, string][] = [
    ['ROC-AUC', fmt(m.auc, 3), fmt(c.auc, 3), '0.500', 'chance that a bug-introducing commit is ranked above a clean one'],
    ['Brier score', fmt(m.brier, 4), '—', fmt(p.brier, 4), 'mean squared error of the estimates (lower is better)'],
    ['Calibration error', fmt(m.ece, 3), '—', '—', 'mean gap between estimated and observed bug rate'],
    ['Precision', pct(m.atThreshold.precision), pct(c.atThreshold.precision), '—', 'flagged commits that were bug-introducing'],
    ['Recall', pct(m.atThreshold.recall), pct(c.atThreshold.recall), '—', 'bug-introducing commits that were flagged'],
    ['F1', fmt(m.atThreshold.f1, 3), fmt(c.atThreshold.f1, 3), '—', `flag threshold ${fmt(m.threshold, 3)}, chosen on the training period`],
  ];
  return (
    <div className="overflow-x-auto">
      <table className="w-full text-xs">
        <thead>
          <tr className="text-slate-400 border-b border-slate-800">
            <th className="text-left font-medium py-2 pr-3">Test period {day(ev.testPeriod?.from)} – {day(ev.testPeriod?.to)}</th>
            <th className="text-right font-medium py-2 px-3"><span className="inline-block w-2 h-2 rounded-sm mr-1.5" style={{ background: SERIES[0] }} />Model</th>
            <th className="text-right font-medium py-2 px-3">Churn baseline</th>
            <th className="text-right font-medium py-2 px-3">Prior rate</th>
            <th className="text-left font-medium py-2 pl-3 hidden md:table-cell">Meaning</th>
          </tr>
        </thead>
        <tbody>
          {rows.map(([name, a, b, d, help]) => (
            <tr key={name} className="border-b border-slate-800/60">
              <td className="py-1.5 pr-3 text-slate-300">{name}</td>
              <td className="py-1.5 px-3 text-right font-mono text-white">{a}</td>
              <td className="py-1.5 px-3 text-right font-mono text-slate-300">{b}</td>
              <td className="py-1.5 px-3 text-right font-mono text-slate-300">{d}</td>
              <td className="py-1.5 pl-3 text-slate-500 hidden md:table-cell">{help}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
};

/** Reliability diagram: estimated vs. observed bug rate per probability bin; on the diagonal = well calibrated. */
const Calibration: React.FC<{ bins: CalibrationBin[]; ece: number }> = ({ bins, ece }) => {
  const [hover, setHover] = React.useState<CalibrationBin | null>(null);
  const used = bins.filter((b) => b.count > 0 && b.meanPredicted != null && b.observedRate != null);
  const max = Math.min(1, Math.ceil(10 * Math.max(0.1, ...used.map((b) => Math.max(b.meanPredicted!, b.observedRate!)))) / 10);
  const W = 300, H = 240, L = 40, B = 30, T = 10, R = 10;
  const x = (v: number) => L + (v / max) * (W - L - R), y = (v: number) => H - B - (v / max) * (H - B - T);
  const maxCount = Math.max(1, ...used.map((b) => b.count));
  const ticks = Array.from({ length: Math.round(max * 10) + 1 }, (_, i) => i / 10).filter((_, i, a) => a.length <= 6 || i % 2 === 0);
  return (
    <div className="bg-slate-950 border border-slate-800 rounded-xl p-4">
      <div className="text-xs font-semibold text-white">Calibration</div>
      <div className="text-[11px] text-slate-400 mb-2">Test commits grouped by estimate; dot area = commits in the group. Points on the diagonal mean an
        estimate of 20 % really corresponds to about 1 in 5 commits. Calibration error {fmt(ece, 3)}.</div>
      <div className="relative">
        <svg viewBox={`0 0 ${W} ${H}`} className="w-full max-w-md h-auto" role="img" aria-label="Calibration diagram" onMouseLeave={() => setHover(null)}>
          {ticks.map((t) => (
            <g key={t}>
              <line x1={x(0)} x2={x(max)} y1={y(t)} y2={y(t)} stroke={GRID} />
              <text x={L - 6} y={y(t) + 3} textAnchor="end" fontSize={9} fill={AXIS}>{Math.round(t * 100)}%</text>
              <text x={x(t)} y={H - B + 14} textAnchor="middle" fontSize={9} fill={AXIS}>{Math.round(t * 100)}%</text>
            </g>
          ))}
          <line x1={x(0)} y1={y(0)} x2={x(max)} y2={y(max)} stroke={MUTED} strokeDasharray="4 4" />
          <text x={(L + W - R) / 2} y={H - 2} textAnchor="middle" fontSize={9} fill={AXIS}>estimated bug probability</text>
          <text x={10} y={(H - B) / 2} textAnchor="middle" fontSize={9} fill={AXIS} transform={`rotate(-90 10 ${(H - B) / 2})`}>observed rate</text>
          {used.map((b) => (
            <g key={b.lower} onMouseEnter={() => setHover(b)} className="cursor-default">
              <circle cx={x(b.meanPredicted!)} cy={y(b.observedRate!)} r={14} fill="transparent" />
              <circle cx={x(b.meanPredicted!)} cy={y(b.observedRate!)} r={4 + 8 * Math.sqrt(b.count / maxCount)}
                fill={SERIES[0]} fillOpacity={hover && hover !== b ? 0.4 : 0.9} stroke="#0b1220" strokeWidth={2} />
            </g>
          ))}
        </svg>
        {hover && (
          <div className="absolute top-2 right-2"><TooltipBox>
            <div className="text-white font-semibold">Estimates {Math.round(hover.lower * 100)}–{Math.round(hover.upper * 100)} %</div>
            <div>{hover.count} commits · mean estimate {pct(hover.meanPredicted, 1)} · observed {pct(hover.observedRate, 1)}</div>
          </TooltipBox></div>
        )}
      </div>
    </div>
  );
};

/** Standardised coefficients: how one standard deviation more of a feature changes the log-odds. */
const Coefficients: React.FC<{ features: { name: string; coefficient: number }[] }> = ({ features }) => {
  const sorted = [...features].sort((a, b) => Math.abs(b.coefficient) - Math.abs(a.coefficient));
  const max = Math.max(0.1, ...sorted.map((f) => Math.abs(f.coefficient)));
  return (
    <div className="bg-slate-950 border border-slate-800 rounded-xl p-4">
      <div className="text-xs font-semibold text-white">What the model learned</div>
      <div className="text-[11px] text-slate-400 mb-3">Effect of one standard deviation more of each feature on the odds of a bug (log-odds,
        features log-scaled). Effects are associations in this repository's history, not causes; correlated features share their effect.</div>
      <div className="flex gap-4 text-[11px] text-slate-400 mb-2">
        <span className="flex items-center gap-1.5"><span className="w-3 h-3 rounded-sm" style={{ background: DIVERGING.positive }} />raises the estimate</span>
        <span className="flex items-center gap-1.5"><span className="w-3 h-3 rounded-sm" style={{ background: DIVERGING.negative }} />lowers it</span>
      </div>
      <div className="space-y-1">
        {sorted.map((f) => {
          const w = (Math.abs(f.coefficient) / max) * 50;
          return (
            <div key={f.name} className="flex items-center gap-2 text-[11px]" title={`${f.name}: ${FEATURE_INFO[f.name] ?? ''} — ${f.coefficient.toFixed(3)}`}>
              <span className="w-12 font-mono text-slate-300">{f.name}</span>
              <div className="flex-1 relative h-3">
                <div className="absolute top-0 bottom-0 left-1/2 w-px bg-slate-700" />
                <div className="absolute top-0 bottom-0 rounded-sm" style={{
                  background: f.coefficient >= 0 ? DIVERGING.positive : DIVERGING.negative,
                  left: f.coefficient >= 0 ? '50%' : `${50 - w}%`, width: `${Math.max(w, 0.3)}%`,
                }} />
              </div>
              <span className="w-12 text-right font-mono text-slate-400">{f.coefficient >= 0 ? '+' : ''}{f.coefficient.toFixed(2)}</span>
              <span className="w-56 text-slate-500 truncate hidden lg:inline">{FEATURE_INFO[f.name]}</span>
            </div>
          );
        })}
      </div>
    </div>
  );
};

export const PredictionRows: React.FC<{ rows: DefectPrediction[]; threshold: number; limit?: number; compact?: boolean }> = ({ rows, threshold, limit = 12, compact }) => {
  const scale = Math.max(threshold * 2, 0.01);
  const drivers = (r: DefectPrediction) => r.drivers.filter((d) => d.contribution > 0).slice(0, 3).map((d) => d.feature).join(', ') || '—';
  const bar = (r: DefectPrediction) => (
    <span className="h-2 bg-slate-800 rounded-full overflow-hidden">
      <span className="block h-full rounded-full" style={{ width: `${Math.min(100, (r.probability * 100) / scale)}%`, background: sequential(0.3 + 0.7 * Math.min(1, r.probability / scale)) }} />
    </span>
  );
  const value = (r: DefectPrediction) => <span className={`font-mono text-right ${r.flagged ? 'text-amber-300' : 'text-slate-300'}`}>{pct(r.probability)}</span>;
  return (
    <ul className="space-y-1.5">
      {rows.slice(0, limit).map((r) => compact ? (
        <li key={r.sha} className="grid grid-cols-[4.5rem_minmax(0,1fr)_5rem_3rem] items-center gap-x-3 text-[11px]">
          <span className="font-mono text-slate-300">{r.sha.substring(0, 7)}</span>
          <span className="min-w-0">
            <span className="block text-slate-300 truncate" title={r.message}>{r.message ?? new Date(r.time).toLocaleDateString()}</span>
            <span className="block text-slate-500 truncate">raised by {drivers(r)}</span>
          </span>
          {bar(r)}
          {value(r)}
        </li>
      ) : (
        <li key={r.sha} className="grid grid-cols-[4.5rem_minmax(0,1fr)_7rem_3.5rem] md:grid-cols-[4.5rem_minmax(0,1fr)_9rem_3.5rem_16rem] items-center gap-3 text-[11px]">
          <span className="font-mono text-slate-300">{r.sha.substring(0, 7)}</span>
          <span className="text-slate-400 truncate" title={r.message}>{r.message ?? new Date(r.time).toLocaleDateString()}</span>
          {bar(r)}
          {value(r)}
          <span className="hidden md:block text-slate-500 truncate">{drivers(r)}</span>
        </li>
      ))}
    </ul>
  );
};

const Predictions: React.FC<{ rows: DefectPrediction[]; threshold: number }> = ({ rows, threshold }) => {
  const flagged = rows.filter((r) => r.flagged).length;
  return (
    <div className="bg-slate-950 border border-slate-800 rounded-xl p-4">
      <div className="text-xs font-semibold text-white">Recent commits</div>
      <div className="text-[11px] text-slate-400 mb-3">Final model (trained on all labelled history) applied to the newest commits.
        {' '}{flagged} of {rows.length} are above the flag threshold of {pct(threshold, 1)} (amber). Right column: features that raised the estimate most.</div>
      <PredictionRows rows={rows} threshold={threshold} />
    </div>
  );
};

/** Analysis view: the accepted model's estimates for the commits of this change (nothing if no accepted model). */
export const DefectEstimateCard: React.FC<{ view?: import('../../types/defect').DefectRangeView }> = ({ view }) => {
  if (!view || view.status !== 'ACCEPTED' || !view.commits || view.commits.length === 0) return null;
  return (
    <div className="bg-slate-900 border border-slate-800 rounded-2xl p-5">
      <h2 className="text-sm font-bold text-white flex items-center gap-2"><FlaskConical className="w-4 h-4 text-violet-400" />History-based defect estimate
        <span className="text-[10px] font-normal px-1.5 py-0.5 rounded border border-slate-700 text-slate-400">experimental</span></h2>
      <p className="text-[11px] text-slate-400 mt-1 mb-3 leading-relaxed">
        How likely each commit of this change is to introduce a bug, judged only by how similar commits behaved in this repository's
        history (test ROC-AUC {fmt(view.testAuc, 2)}). An estimate from past patterns, not a finding about this code.
        {view.withoutCodeChanges ? ` ${view.withoutCodeChanges} commit(s) change no source code and are not scored.` : ''}
        {view.notScored ? ` ${view.notScored} commit(s) are newer than the model — retrain it on the repository page to score them.` : ''}
      </p>
      <PredictionRows rows={view.commits} threshold={view.threshold ?? 0.5} limit={8} compact />
    </div>
  );
};

import React from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Bot, CheckCircle2, AlertTriangle, Loader2 } from 'lucide-react';
import { aiApi, type AiFinding, type EvidenceRef } from '../../api/ai';
import { RichText } from './RichText';
import { STATUS } from '../architecture/twinShared';

const SEV: Record<string, { label: string; colour: string }> = {
  HIGH: { label: 'High', colour: STATUS.serious }, MEDIUM: { label: 'Medium', colour: STATUS.warning }, LOW: { label: 'Low', colour: '#94a3b8' },
};

/**
 * AI review of an analysis (Gemini). Every finding cites evidence ids from the deterministic report; the platform
 * validates them and marks findings that cite nothing valid or name unknown code as UNVERIFIED.
 */
export const AiReviewCard: React.FC<{ analysisId: string }> = ({ analysisId }) => {
  const qc = useQueryClient();
  const { data } = useQuery({
    queryKey: ['ai-review', analysisId],
    queryFn: () => aiApi.review(analysisId),
    refetchInterval: (q) => (q.state.data && ['QUEUED', 'RUNNING'].includes(q.state.data.status) ? 2500 : false),
  });
  const start = useMutation({ mutationFn: () => aiApi.startReview(analysisId), onSuccess: () => qc.invalidateQueries({ queryKey: ['ai-review', analysisId] }) });
  const [cited, setCited] = React.useState<string | null>(null);
  const running = data && ['QUEUED', 'RUNNING'].includes(data.status);
  const evidence = data?.evidence ?? {};

  const startButton = (
    <button onClick={() => start.mutate()} disabled={running || start.isPending || data?.aiEnabled === false}
      className="flex items-center gap-1.5 px-3 py-1.5 rounded-lg bg-violet-600 hover:bg-violet-500 disabled:opacity-50 text-white text-xs font-semibold">
      <Bot className="w-3.5 h-3.5" />{data?.status === 'COMPLETED' || data?.status === 'FAILED' ? 'Review again' : 'Generate AI review'}
    </button>
  );

  return (
    <div className="bg-slate-900 border border-slate-800 rounded-2xl p-5">
      <div className="flex flex-wrap items-start justify-between gap-3 mb-3">
        <div>
          <h2 className="text-sm font-bold text-white flex items-center gap-2"><Bot className="w-4 h-4 text-violet-400" />AI review
            <span className="text-[10px] font-normal px-1.5 py-0.5 rounded border border-slate-700 text-slate-400">Gemini · validated</span></h2>
          <p className="text-[11px] text-slate-400 mt-1 max-w-3xl leading-relaxed">
            A language model reads this report's evidence (changed code before/after, impact paths, tests, security findings, related code)
            and explains what a reviewer should check. Each finding must cite that evidence; the platform checks every citation and every
            code name it mentions. The deterministic report above is never changed by it.
          </p>
        </div>
        {startButton}
      </div>
      {start.isError && <p className="text-xs text-rose-300 mb-2">{(start.error as any)?.response?.data?.message ?? 'Could not start the review.'}</p>}
      {!data || data.status === 'NOT_REQUESTED' ? (
        <p className="text-xs text-slate-500">{data?.aiEnabled === false ? 'AI review is not configured on this server (GEMINI_API_KEY).' : 'No AI review yet. It takes about 10–60 seconds.'}</p>
      ) : running ? (
        <p className="text-xs text-slate-400 flex items-center gap-2"><Loader2 className="w-4 h-4 animate-spin" />Reviewing with {data.model}…</p>
      ) : data.status === 'FAILED' ? (
        <p className="text-xs text-rose-300">The review failed: {data.errorMessage}</p>
      ) : data.result && (
        <div className="space-y-4">
          <div className="text-xs text-slate-200 leading-relaxed"><RichText text={data.result.summary} /></div>
          <div className="flex flex-wrap gap-3 text-[11px] text-slate-400">
            <span className="flex items-center gap-1"><CheckCircle2 className="w-3.5 h-3.5 text-emerald-400" />{data.validation?.verified ?? 0} verified</span>
            <span className="flex items-center gap-1"><AlertTriangle className="w-3.5 h-3.5 text-amber-400" />{data.validation?.unverified ?? 0} unverified</span>
            <span>{data.validation?.evidenceItems} evidence items</span>
            {data.validation?.relatedCodeRetrieval && <span title="How related code was retrieved for the evidence">related code: {data.validation.relatedCodeRetrieval}</span>}
          </div>
          <ul className="space-y-3">
            {data.result.findings.map((f, i) => <FindingRow key={i} f={f} onCite={setCited} />)}
          </ul>
          {data.result.testSuggestions.length > 0 && (
            <div>
              <div className="text-xs font-semibold text-white mb-1.5">Suggested tests</div>
              <ul className="space-y-1.5">
                {data.result.testSuggestions.map((t, i) => (
                  <li key={i} className="text-xs text-slate-300 [overflow-wrap:anywhere]"><span className="text-slate-100">{t.title}</span>
                    {t.status !== 'VERIFIED' && <span className="ml-2 text-[10px] text-amber-300">unverified</span>}
                    <div className="text-slate-400"><RichText text={t.rationale} onCite={setCited} /></div>
                    <Cites ids={t.citations} onCite={setCited} /></li>
                ))}
              </ul>
            </div>
          )}
          {data.result.questionsForAuthor.length > 0 && (
            <div>
              <div className="text-xs font-semibold text-white mb-1.5">Questions for the author</div>
              <ul className="list-disc pl-5 space-y-1">{data.result.questionsForAuthor.map((q, i) => <li key={i} className="text-xs text-slate-300"><RichText text={q} onCite={setCited} /></li>)}</ul>
            </div>
          )}
          {cited && evidence[cited] && <EvidencePanel id={cited} e={evidence[cited]} onClose={() => setCited(null)} />}
          <div className="text-[10px] text-slate-500 border-t border-slate-800 pt-2">
            {data.model} · prompt {data.promptVersion} · {data.promptTokens?.toLocaleString()} + {data.outputTokens?.toLocaleString()} tokens · {((data.latencyMs ?? 0) / 1000).toFixed(1)} s ·
            {' '}{data.completedAt && new Date(data.completedAt).toLocaleString()} · generated by a language model; check before acting
          </div>
        </div>
      )}
    </div>
  );
};

const Cites: React.FC<{ ids: string[]; onCite: (id: string) => void }> = ({ ids, onCite }) => (
  <span className="inline-flex flex-wrap gap-1 mt-1">{ids.map((id) => (
    <button key={id} onClick={() => onCite(id)} className="px-1.5 rounded bg-slate-800 hover:bg-slate-700 text-sky-300 text-[10px] font-mono">{id}</button>
  ))}</span>
);

const FindingRow: React.FC<{ f: AiFinding; onCite: (id: string) => void }> = ({ f, onCite }) => {
  const sev = SEV[f.severity] ?? SEV.LOW;
  return (
    <li className={`border rounded-xl p-3 [overflow-wrap:anywhere] ${f.status === 'VERIFIED' ? 'border-slate-800 bg-slate-950' : 'border-amber-900/60 bg-amber-950/10'}`}>
      <div className="flex flex-wrap items-center gap-2">
        <span className="flex items-center gap-1 text-[10px] font-semibold px-1.5 py-0.5 rounded border border-slate-700 text-slate-200">
          <span className="w-2 h-2 rounded-full" style={{ background: sev.colour }} />{sev.label}</span>
        <span className="text-[10px] text-slate-400 uppercase tracking-wide">{f.category.toLowerCase()}</span>
        <span className="text-xs font-semibold text-white">{f.title}</span>
        <span className={`ml-auto text-[10px] px-1.5 py-0.5 rounded ${f.status === 'VERIFIED' ? 'bg-emerald-950 text-emerald-300' : 'bg-amber-950 text-amber-300'}`}
          title={f.reason}>{f.status === 'VERIFIED' ? 'verified' : 'unverified'}</span>
      </div>
      <div className="mt-1.5"><RichText text={f.explanation} onCite={onCite} /></div>
      {f.suggestion && <div className="mt-1.5 text-xs text-slate-400"><span className="text-slate-500">Suggestion: </span><RichText text={f.suggestion} onCite={onCite} /></div>}
      {f.status !== 'VERIFIED' && <p className="mt-1 text-[11px] text-amber-300">{f.reason}{f.unknownMentions?.length ? `: ${f.unknownMentions.join(', ')}` : ''}</p>}
      <Cites ids={f.citations} onCite={onCite} />
    </li>
  );
};

export const EvidencePanel: React.FC<{ id: string; e: EvidenceRef; onClose: () => void }> = ({ id, e, onClose }) => (
  <div className="border border-sky-900/60 bg-sky-950/20 rounded-xl p-3 text-xs flex items-start gap-3">
    <span className="font-mono text-sky-300">{id}</span>
    <div className="flex-1 min-w-0">
      <div className="text-slate-400">{(e.type ?? e.kind ?? '').replace(/([A-Z])/g, ' $1').toLowerCase()}</div>
      <div className="text-white font-mono break-all">{e.name}</div>
      {e.file && <div className="text-slate-500 break-all">{e.file}{e.startLine ? `:${e.startLine}` : ''}{e.endLine ? `–${e.endLine}` : ''}</div>}
    </div>
    <button onClick={onClose} className="text-slate-500 hover:text-white">close</button>
  </div>
);

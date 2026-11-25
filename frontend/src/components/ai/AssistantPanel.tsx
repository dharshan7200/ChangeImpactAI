import React from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Bot, Database, Search, Send, Loader2 } from 'lucide-react';
import { apiClient } from '../../api/client';
import { aiApi, type AssistantAnswer } from '../../api/ai';
import { RichText } from './RichText';
import { EvidencePanel } from './AiReviewCard';

/** Repository assistant + the AI search index it relies on, with a search inspector showing every retriever's rank. */
export const AssistantPanel: React.FC<{ repositoryId: string }> = ({ repositoryId }) => {
  const qc = useQueryClient();
  const { data: status } = useQuery({ queryKey: ['rag-status', repositoryId], queryFn: () => aiApi.ragStatus(repositoryId) });
  const [jobId, setJobId] = React.useState<string | null>(null);
  const build = useMutation({ mutationFn: () => aiApi.ragIndex(repositoryId), onSuccess: (r) => setJobId(r.jobId) });
  const { data: job } = useQuery({
    queryKey: ['rag-job', jobId], enabled: !!jobId,
    queryFn: async () => (await apiClient.get(`/jobs/${jobId}`)).data.data as { status: string; progressPercent?: number; currentMessage?: string; errorMessage?: string },
    refetchInterval: (q) => (q.state.data && ['COMPLETED', 'FAILED'].includes(q.state.data.status) ? false : 2500),
  });
  React.useEffect(() => { if (job?.status === 'COMPLETED') qc.invalidateQueries({ queryKey: ['rag-status', repositoryId] }); }, [job?.status, qc, repositoryId]);
  const indexed = (status?.chunks ?? 0) > 0;
  const building = !!job && !['COMPLETED', 'FAILED'].includes(job.status);

  return (
    <div className="space-y-6">
      <div className="bg-slate-900 border border-slate-800 rounded-2xl p-5">
        <div className="flex flex-wrap items-start justify-between gap-3">
          <div>
            <h2 className="text-sm font-bold text-white flex items-center gap-2"><Database className="w-4 h-4 text-violet-400" />AI search index</h2>
            <p className="text-[11px] text-slate-400 mt-1 max-w-3xl leading-relaxed">
              Every method, constructor, function and type header of the last indexed commit becomes a searchable chunk (secrets redacted),
              embedded with {status?.embeddingModel ?? 'Gemini'} and found by full-text and vector search together. Required for the assistant and
              for related-code evidence in AI reviews.
            </p>
            <p className="text-xs text-slate-300 mt-2">
              {!status ? '…' : !status.aiEnabled ? 'AI features are not configured on this server (GEMINI_API_KEY).'
                : indexed ? `${status.chunks.toLocaleString()} chunks searchable at ${status.commit?.substring(0, 7)}`
                : `Not built yet for ${status.commit?.substring(0, 7) ?? 'this repository'} (${status.candidates.toLocaleString()} code units).`}
            </p>
            {building && <p className="text-[11px] text-slate-400 mt-1 flex items-center gap-2"><Loader2 className="w-3.5 h-3.5 animate-spin" />{job?.currentMessage}</p>}
            {job?.status === 'FAILED' && <p className="text-[11px] text-rose-300 mt-1">{job.errorMessage}</p>}
          </div>
          {status?.aiEnabled && (
            <button onClick={() => build.mutate()} disabled={building || build.isPending}
              className="px-3 py-1.5 rounded-lg bg-violet-600 hover:bg-violet-500 disabled:opacity-50 text-white text-xs font-semibold">
              {indexed ? 'Update index' : 'Build index'}</button>
          )}
        </div>
      </div>
      {indexed && status?.aiEnabled && <Ask repositoryId={repositoryId} />}
      {indexed && status?.aiEnabled && <SearchInspector repositoryId={repositoryId} />}
    </div>
  );
};

const Ask: React.FC<{ repositoryId: string }> = ({ repositoryId }) => {
  const [question, setQuestion] = React.useState('');
  const { data: history, refetch } = useQuery({ queryKey: ['assistant-history', repositoryId], queryFn: () => aiApi.history(repositoryId) });
  const ask = useMutation({ mutationFn: (q: string) => aiApi.ask(repositoryId, q), onSuccess: () => { setQuestion(''); refetch(); } });
  return (
    <div className="bg-slate-900 border border-slate-800 rounded-2xl p-5">
      <h2 className="text-sm font-bold text-white flex items-center gap-2"><Bot className="w-4 h-4 text-violet-400" />Ask about this repository</h2>
      <p className="text-[11px] text-slate-400 mt-1 mb-3">Answers come only from retrieved code and verified dependency facts, with citations the platform checks.
        If the code does not answer the question, the assistant says so.</p>
      <form onSubmit={(e) => { e.preventDefault(); if (question.trim()) ask.mutate(question.trim()); }} className="flex gap-2">
        <input value={question} onChange={(e) => setQuestion(e.target.value)} maxLength={1000} placeholder="e.g. How are owners found by last name?"
          className="flex-1 min-w-0 bg-slate-950 border border-slate-700 rounded-lg px-3 py-2 text-xs text-white placeholder-slate-500" />
        <button disabled={ask.isPending || !question.trim()} className="px-3 py-2 rounded-lg bg-violet-600 hover:bg-violet-500 disabled:opacity-50 text-white text-xs font-semibold flex items-center gap-1.5">
          {ask.isPending ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <Send className="w-3.5 h-3.5" />}Ask</button>
      </form>
      {ask.isError && <p className="text-xs text-rose-300 mt-2">{(ask.error as any)?.response?.data?.message ?? 'The assistant could not answer.'}</p>}
      <div className="space-y-4 mt-4">{(history ?? []).map((a) => <AnswerView key={a.id} a={a} />)}</div>
    </div>
  );
};

const AnswerView: React.FC<{ a: AssistantAnswer }> = ({ a }) => {
  const [cited, setCited] = React.useState<string | null>(null);
  const byId = Object.fromEntries(a.evidence.map((e) => [e.id!, e]));
  const label = a.status === 'VERIFIED' ? ['verified', 'bg-emerald-950 text-emerald-300'] : a.status === 'INSUFFICIENT_EVIDENCE'
    ? ['not answerable from the code', 'bg-slate-800 text-slate-300'] : ['unverified', 'bg-amber-950 text-amber-300'];
  return (
    <div className="border border-slate-800 rounded-xl p-3 bg-slate-950 [overflow-wrap:anywhere]">
      <div className="flex flex-wrap items-center gap-2 mb-2">
        <span className="text-xs font-semibold text-white">{a.question}</span>
        <span className={`ml-auto text-[10px] px-1.5 py-0.5 rounded ${label[1]}`}>{label[0]}</span>
      </div>
      <RichText text={a.answer} onCite={setCited} validIds={new Set(a.evidence.map((e) => e.id!))} />
      {a.unknownMentions?.length ? <p className="text-[11px] text-amber-300 mt-1">Names not found in the code: {a.unknownMentions.join(', ')}</p> : null}
      {cited && byId[cited] && <div className="mt-2"><EvidencePanel id={cited} e={byId[cited]} onClose={() => setCited(null)} /></div>}
      <div className="text-[10px] text-slate-500 mt-2">{a.model} · {a.promptTokens} + {a.outputTokens} tokens · {(a.latencyMs / 1000).toFixed(1)} s · {a.evidence.length} evidence items
        {a.createdAt && ` · ${new Date(a.createdAt).toLocaleString()}`}{a.retrieval && ` · retrieval: ${a.retrieval}`}</div>
    </div>
  );
};

const SearchInspector: React.FC<{ repositoryId: string }> = ({ repositoryId }) => {
  const [query, setQuery] = React.useState('');
  const [mode, setMode] = React.useState('auto');
  const search = useMutation({ mutationFn: () => aiApi.search(repositoryId, query, mode, 10) });
  const [open, setOpen] = React.useState<string | null>(null);
  return (
    <div className="bg-slate-900 border border-slate-800 rounded-2xl p-5">
      <h2 className="text-sm font-bold text-white flex items-center gap-2"><Search className="w-4 h-4 text-sky-400" />Search inspector</h2>
      <p className="text-[11px] text-slate-400 mt-1 mb-3">What the AI features retrieve. <span className="text-slate-300">auto</span> (what the assistant and AI review use)
        searches code names with full-text + vector fused by Reciprocal Rank Fusion (k = 60), and questions with vector search alone, which measured better.</p>
      <form onSubmit={(e) => { e.preventDefault(); if (query.trim()) search.mutate(); }} className="flex flex-wrap gap-2">
        <input value={query} onChange={(e) => setQuery(e.target.value)} placeholder="Search code by meaning or words"
          className="flex-1 min-w-[12rem] bg-slate-950 border border-slate-700 rounded-lg px-3 py-2 text-xs text-white placeholder-slate-500" />
        <select value={mode} onChange={(e) => setMode(e.target.value)} className="bg-slate-950 border border-slate-700 rounded-lg px-2 text-xs text-slate-200">
          <option value="auto">auto</option><option value="hybrid">hybrid</option><option value="fts">full-text only</option><option value="vector">vector only</option>
        </select>
        <button className="px-3 py-2 rounded-lg bg-slate-700 hover:bg-slate-600 text-white text-xs">Search</button>
      </form>
      {search.isError && <p className="text-xs text-rose-300 mt-2">{(search.error as any)?.response?.data?.message ?? 'Search failed.'}</p>}
      {search.data && (
        <p className="text-[11px] text-slate-400 mt-3">Used: <span className="text-slate-200">{search.data.mode === 'fts' ? 'full-text' : search.data.mode}</span>
          {search.data.note && <span className="block text-amber-300 mt-0.5">{search.data.note}</span>}</p>
      )}
      {search.data && (
        <ol className="mt-2 space-y-1">
          {search.data.hits.map((h, i) => (
            <li key={h.chunkId} className="text-xs">
              <button onClick={() => setOpen(open === h.chunkId ? null : h.chunkId)} className="w-full grid grid-cols-[1.5rem_minmax(0,1fr)_auto] gap-2 text-left hover:bg-slate-800/60 rounded px-1 py-0.5">
                <span className="text-slate-500">{i + 1}</span>
                <span className="font-mono text-slate-200 truncate" title={h.qualifiedName}>{h.qualifiedName}</span>
                <span className="text-[10px] text-slate-500 whitespace-nowrap">fts {h.ftsRank ?? '–'} · vec {h.vectorRank ?? '–'}{h.similarity != null ? ` (${h.similarity.toFixed(2)})` : ''}</span>
              </button>
              {open === h.chunkId && <pre className="mt-1 mb-2 p-2 bg-slate-950 border border-slate-800 rounded text-[10px] text-slate-300 overflow-x-auto max-h-64">{h.filePath}:{h.startLine}{'\n'}{h.content}</pre>}
            </li>
          ))}
        </ol>
      )}
    </div>
  );
};

import React from 'react';

/** Minimal, safe rendering of model text: paragraphs, numbered/bulleted lines, **bold**, `code`, [K1]-style citations. */
export const RichText: React.FC<{ text: string; onCite?: (id: string) => void; validIds?: Set<string> }> = ({ text, onCite, validIds }) => {
  const inline = (s: string, key: string) => {
    const parts = s.split(/(`[^`]+`|\*\*[^*]+\*\*|\[[A-Z]\d+(?:,\s*[A-Z]\d+)*\])/g);
    return parts.map((p, i) => {
      if (p.startsWith('`') && p.endsWith('`') && p.length > 2) return <code key={key + i} className="px-1 py-0.5 rounded bg-slate-800 text-cyan-200 text-[11px] font-mono break-all">{p.slice(1, -1)}</code>;
      if (p.startsWith('**') && p.endsWith('**')) return <strong key={key + i} className="text-white">{p.slice(2, -2)}</strong>;
      if (/^\[[A-Z]\d+(?:,\s*[A-Z]\d+)*\]$/.test(p)) {
        return p.slice(1, -1).split(/,\s*/).map((id) => (
          <button key={key + i + id} onClick={() => onCite?.(id)}
            className={`mx-0.5 px-1 rounded text-[10px] font-mono align-baseline ${validIds && !validIds.has(id) ? 'bg-rose-950 text-rose-300 line-through' : 'bg-slate-800 text-sky-300 hover:bg-slate-700'}`}>{id}</button>
        ));
      }
      return <React.Fragment key={key + i}>{p}</React.Fragment>;
    });
  };
  return (
    <div className="space-y-1.5 text-xs text-slate-300 leading-relaxed [overflow-wrap:anywhere]">
      {text.split(/\n+/).filter((l) => l.trim()).map((line, i) => {
        const m = line.match(/^\s*(\d+\.|[-*])\s+(.*)$/);
        return m ? <div key={i} className="flex gap-2"><span className="text-slate-500 shrink-0">{m[1].replace('*', '•').replace('-', '•')}</span><span>{inline(m[2], 'l' + i)}</span></div>
          : <p key={i}>{inline(line, 'p' + i)}</p>;
      })}
    </div>
  );
};

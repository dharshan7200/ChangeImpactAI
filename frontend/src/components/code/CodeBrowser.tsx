import React from 'react';
import { useQuery } from '@tanstack/react-query';
import Editor, { type OnMount } from '@monaco-editor/react';
import { FileCode, Folder, Search, ArrowRight, ArrowLeft } from 'lucide-react';
import { monaco, monacoLanguage, MONACO_LANGUAGES } from './monacoSetup';
import { apiClient } from '../../api/client';
import { codeApi } from '../../api/code';
import type { ApiResponse } from '../../types';

interface FileEntry { path: string; language: string; linesOfCode: number; test: boolean }
interface SymbolEntry { id: string; qualifiedName: string; simpleName: string; symbolType: string; startLine: number; endLine: number; dependents: number }
interface Reference { line: number; sourceSymbolId: string; targetName: string; status: string; dependencyType: string; targetSymbolId?: string; targetPath?: string; targetLine?: number }
interface FileView { commitSha: string; path: string; language: string; content: string; lines: number; symbols: SymbolEntry[]; references: Reference[] }

const short = (qn: string) => qn.replace(/^.*[.#]/, '').replace(/\(.*$/, '');
const STATUS_TEXT: Record<string, string> = { RESOLVED: 'in this repository', PARTIAL: 'type known, member not', EXTERNAL: 'JDK / library', UNRESOLVED: 'not resolvable' };

/**
 * Read-only code browser (Monaco). Navigation uses the platform's own analysis: Ctrl/⌘-click or F12 jumps to the
 * resolved declaration, hovering shows what a line references, the side panel lists callers and callees.
 */
export const CodeBrowser: React.FC<{ repositoryId: string; path: string | null; line: number | null;
  onNavigate: (path: string, line?: number) => void }> = ({ repositoryId, path, line, onNavigate }) => {
  const [filter, setFilter] = React.useState('');
  const [cursorLine, setCursorLine] = React.useState<number>(line ?? 1);
  const editorRef = React.useRef<Parameters<OnMount>[0] | null>(null);
  const [mounted, setMounted] = React.useState(0);
  const viewRef = React.useRef<FileView | null>(null);
  const navRef = React.useRef(onNavigate);
  navRef.current = onNavigate;

  const { data: files } = useQuery({
    queryKey: ['source-files', repositoryId],
    queryFn: async () => (await apiClient.get<ApiResponse<{ commitSha: string; files: FileEntry[] }>>(`/repositories/${repositoryId}/source/files`)).data.data,
  });
  const current = path ?? files?.files.find((f) => !f.test)?.path ?? null;
  const { data: view, error, isLoading } = useQuery({
    queryKey: ['source-file', repositoryId, current], enabled: !!current,
    queryFn: async () => (await apiClient.get<ApiResponse<FileView>>(`/repositories/${repositoryId}/source/file`, { params: { path: current } })).data.data,
  });
  viewRef.current = view ?? null;

  // the innermost symbol around the cursor
  const symbol = React.useMemo(() => (view?.symbols ?? []).filter((s) => s.startLine <= cursorLine && cursorLine <= s.endLine)
    .sort((a, b) => (a.endLine - a.startLine) - (b.endLine - b.startLine))[0], [view, cursorLine]);
  const lineRefs = (view?.references ?? []).filter((r) => r.line === cursorLine);
  const { data: callers } = useQuery({ queryKey: ['dependents', symbol?.id], enabled: !!symbol, queryFn: () => codeApi.getDependents(symbol!.id, 1) });
  const { data: callees } = useQuery({ queryKey: ['dependencies', symbol?.id], enabled: !!symbol, queryFn: () => codeApi.getDependencies(symbol!.id, 1) });

  // jump to the requested line whenever file or line change
  React.useEffect(() => {
    const ed = editorRef.current;
    if (!ed || !view) return;
    const l = Math.max(1, Math.min(line ?? 1, view.lines || 1));
    ed.revealLineInCenter(l);
    ed.setPosition({ lineNumber: l, column: 1 });
    setCursorLine(l);
    const decorations = view.symbols.map((s) => ({
      range: new monaco.Range(s.startLine, 1, s.startLine, 1),
      options: { isWholeLine: false, glyphMarginClassName: s.symbolType === 'CLASS' || s.symbolType === 'INTERFACE' || s.symbolType === 'ENUM' || s.symbolType === 'RECORD'
        ? 'cia-glyph-type' : 'cia-glyph-member', glyphMarginHoverMessage: { value: `**${s.symbolType.toLowerCase()}** \`${s.simpleName}\` — used by ${s.dependents} symbol(s)` } },
    }));
    const coll = ed.createDecorationsCollection(decorations);
    return () => coll.clear();
  }, [view, line, mounted]);

  const onMount: OnMount = (ed) => {
    editorRef.current = ed;
    setMounted((n) => n + 1); // the editor exists now: reveal the requested line and add the markers
    ed.onDidChangeCursorPosition((e) => setCursorLine(e.position.lineNumber));
    const target = (lineNumber: number, word: string | undefined) => {
      const refs = (viewRef.current?.references ?? []).filter((r) => r.line === lineNumber && r.targetPath && r.targetLine);
      return refs.find((r) => word && short(r.targetName) === word) ?? (refs.length === 1 ? refs[0] : undefined);
    };
    ed.onMouseDown((e) => {
      if (!(e.event.ctrlKey || e.event.metaKey) || !e.target.position) return;
      const word = ed.getModel()?.getWordAtPosition(e.target.position)?.word;
      const t = target(e.target.position.lineNumber, word);
      if (t) navRef.current(t.targetPath!, t.targetLine);
    });
    ed.addAction({ id: 'cia-goto', label: 'Go to declaration (ChangeImpact analysis)', keybindings: [monaco.KeyCode.F12],
      contextMenuGroupId: 'navigation', run: (editor) => {
        const pos = editor.getPosition();
        if (!pos) return;
        const t = target(pos.lineNumber, editor.getModel()?.getWordAtPosition(pos)?.word);
        if (t) navRef.current(t.targetPath!, t.targetLine);
      } });
  };

  // hover: what the analysis resolved on this line (one provider per language, reading the current view)
  React.useEffect(() => {
    const disposables = MONACO_LANGUAGES.map((lang) => monaco.languages.registerHoverProvider(lang, {
      provideHover: (model, position) => {
        const v = viewRef.current;
        if (!v || model !== editorRef.current?.getModel()) return null;
        const word = model.getWordAtPosition(position)?.word;
        const refs = v.references.filter((r) => r.line === position.lineNumber && (!word || short(r.targetName) === word));
        if (!refs.length) return null;
        return { contents: refs.slice(0, 6).map((r) => ({ value: `\`${r.targetName}\` — ${STATUS_TEXT[r.status] ?? r.status}${r.targetPath ? ` · ${r.targetPath}:${r.targetLine} (Ctrl/⌘-click or F12)` : ''}` })) };
      },
    }));
    return () => disposables.forEach((d) => d.dispose());
  }, []);

  const shown = (files?.files ?? []).filter((f) => !filter || f.path.toLowerCase().includes(filter.toLowerCase()));
  const groups = React.useMemo(() => {
    const m = new Map<string, FileEntry[]>();
    shown.forEach((f) => { const d = f.path.includes('/') ? f.path.substring(0, f.path.lastIndexOf('/')) : '.'; m.set(d, [...(m.get(d) ?? []), f]); });
    return [...m.entries()];
  }, [shown]);

  const Link: React.FC<{ n: { sourceFile?: string; startLine?: number; simpleName: string; qualifiedName: string; testCode?: boolean } }> = ({ n }) => (
    <button disabled={!n.sourceFile} onClick={() => n.sourceFile && onNavigate(n.sourceFile, n.startLine)} title={n.qualifiedName}
      className="block w-full text-left truncate text-[11px] text-sky-300 hover:text-sky-200 disabled:text-slate-500">
      {n.simpleName}{n.testCode ? <span className="text-slate-500"> (test)</span> : null}
    </button>
  );

  return (
    <div className="grid grid-cols-1 lg:grid-cols-[16rem_minmax(0,1fr)_16rem] gap-3 min-h-[70vh]">
      <aside className="bg-slate-900 border border-slate-800 rounded-2xl p-3 flex flex-col min-h-0 max-h-[78vh]">
        <div className="relative mb-2">
          <Search className="w-3.5 h-3.5 text-slate-500 absolute left-2 top-2" />
          <input value={filter} onChange={(e) => setFilter(e.target.value)} placeholder="Filter files" aria-label="Filter files"
            className="w-full bg-slate-950 border border-slate-700 rounded-lg pl-7 pr-2 py-1.5 text-xs text-white placeholder-slate-600" />
        </div>
        <p className="text-[10px] text-slate-500 mb-2">{files ? `${files.files.length} analysed files at ${files.commitSha.substring(0, 7)}` : 'Loading…'}</p>
        <div className="overflow-y-auto min-h-0 flex-1 space-y-2 pr-1">
          {groups.map(([dir, fs]) => (
            <div key={dir}>
              <div className="flex items-center gap-1 text-[10px] text-slate-500 truncate" title={dir}><Folder className="w-3 h-3 shrink-0" />{dir}</div>
              {fs.map((f) => (
                <button key={f.path} onClick={() => onNavigate(f.path)}
                  className={`w-full flex items-center gap-1.5 text-left text-xs px-1.5 py-0.5 rounded ${f.path === current ? 'bg-slate-700 text-white' : 'text-slate-300 hover:bg-slate-800'}`}>
                  <FileCode className="w-3 h-3 shrink-0 text-slate-500" /><span className="truncate">{f.path.substring(f.path.lastIndexOf('/') + 1)}</span>
                  {f.test && <span className="ml-auto text-[9px] text-slate-500">test</span>}
                </button>
              ))}
            </div>
          ))}
        </div>
      </aside>

      <section className="bg-slate-900 border border-slate-800 rounded-2xl overflow-hidden flex flex-col min-h-[60vh]">
        <div className="px-3 py-2 border-b border-slate-800 text-xs text-slate-300 truncate font-mono" title={current ?? ''}>
          {current ?? 'No file'}{view && <span className="text-slate-500"> · {view.lines} lines · {view.commitSha.substring(0, 7)} · read-only</span>}
        </div>
        {error ? <p className="p-4 text-xs text-rose-300">{(error as any)?.response?.data?.message ?? 'Could not load the file.'}</p>
          : isLoading || !view ? <p className="p-4 text-xs text-slate-500">Loading…</p> : (
          <div className="flex-1 min-h-[60vh]">
            <Editor height="100%" theme="vs-dark" path={`${view.commitSha}/${view.path}`} language={monacoLanguage(view.path, view.language)}
              value={view.content} onMount={onMount}
              options={{ readOnly: true, domReadOnly: true, minimap: { enabled: false }, glyphMargin: true, fontSize: 12, scrollBeyondLastLine: false,
                renderLineHighlight: 'all', automaticLayout: true, contextmenu: true }} />
          </div>
        )}
      </section>

      <aside className="bg-slate-900 border border-slate-800 rounded-2xl p-3 space-y-3 text-xs max-h-[78vh] overflow-y-auto">
        <div>
          <h3 className="text-[11px] font-semibold text-slate-200 mb-1">Line {cursorLine} references</h3>
          {lineRefs.length === 0 ? <p className="text-[11px] text-slate-500">None recorded on this line.</p> : (
            <ul className="space-y-1">{lineRefs.slice(0, 20).map((r, i) => (
              <li key={i} className="text-[11px] [overflow-wrap:anywhere]">
                {r.targetPath ? <button onClick={() => onNavigate(r.targetPath!, r.targetLine)} className="text-sky-300 hover:text-sky-200 text-left">{r.targetName}</button>
                  : <span className="text-slate-300">{r.targetName}</span>}
                <span className="block text-[10px] text-slate-500">{r.dependencyType.toLowerCase().replace(/_/g, ' ')} · {STATUS_TEXT[r.status] ?? r.status}</span>
              </li>))}</ul>
          )}
        </div>
        {symbol && (
          <>
            <div className="border-t border-slate-800 pt-2">
              <h3 className="text-[11px] font-semibold text-slate-200 [overflow-wrap:anywhere]">{symbol.simpleName}</h3>
              <p className="text-[10px] text-slate-500">{symbol.symbolType.toLowerCase()} · lines {symbol.startLine}–{symbol.endLine}</p>
            </div>
            <div>
              <h4 className="flex items-center gap-1 text-[11px] text-slate-400 mb-1"><ArrowLeft className="w-3 h-3" /> Used by ({callers?.length ?? '…'})</h4>
              {(callers ?? []).slice(0, 25).map((n) => <Link key={n.id} n={n} />)}
            </div>
            <div>
              <h4 className="flex items-center gap-1 text-[11px] text-slate-400 mb-1"><ArrowRight className="w-3 h-3" /> Uses ({callees?.length ?? '…'})</h4>
              {(callees ?? []).slice(0, 25).map((n) => <Link key={n.id} n={n} />)}
            </div>
          </>
        )}
        <p className="text-[10px] text-slate-500 border-t border-slate-800 pt-2">Ctrl/⌘-click or F12 on a name jumps to its declaration. Read-only: ChangeImpact never changes repository code.</p>
      </aside>
    </div>
  );
};

export default CodeBrowser;

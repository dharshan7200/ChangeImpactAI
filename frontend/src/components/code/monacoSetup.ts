// Monaco bundled locally (the Content-Security-Policy allows scripts, workers and fonts from 'self' only, so the
// default CDN loader of @monaco-editor/react cannot be used). Only the editor core, its features and the syntax modes
// of the analysed languages are included — no language servers: navigation comes from ChangeImpact's own analysis.
import * as monaco from 'monaco-editor/esm/vs/editor/editor.api';
import 'monaco-editor/esm/vs/editor/editor.all';
import 'monaco-editor/esm/vs/basic-languages/java/java.contribution';
import 'monaco-editor/esm/vs/basic-languages/python/python.contribution';
import 'monaco-editor/esm/vs/basic-languages/javascript/javascript.contribution';
import 'monaco-editor/esm/vs/basic-languages/typescript/typescript.contribution';
import 'monaco-editor/esm/vs/basic-languages/php/php.contribution';
import 'monaco-editor/esm/vs/basic-languages/csharp/csharp.contribution';
import 'monaco-editor/esm/vs/basic-languages/go/go.contribution';
import 'monaco-editor/esm/vs/basic-languages/ruby/ruby.contribution';
import 'monaco-editor/esm/vs/basic-languages/html/html.contribution';
import 'monaco-editor/esm/vs/basic-languages/twig/twig.contribution';
import 'monaco-editor/esm/vs/basic-languages/liquid/liquid.contribution';
import 'monaco-editor/esm/vs/basic-languages/css/css.contribution';
import 'monaco-editor/esm/vs/basic-languages/sql/sql.contribution';
import EditorWorker from 'monaco-editor/esm/vs/editor/editor.worker?worker';
import { loader } from '@monaco-editor/react';

(self as unknown as { MonacoEnvironment: unknown }).MonacoEnvironment = { getWorker: () => new EditorWorker() };
loader.config({ monaco });

export { monaco };

/** Monaco syntax mode of a file: by extension (TypeScript vs JavaScript), else the analysed language. */
export function monacoLanguage(path: string, language: string): string {
  const ext = path.toLowerCase().split('.').pop() ?? '';
  const byExt: Record<string, string> = {
    ts: 'typescript', tsx: 'typescript', mts: 'typescript', cts: 'typescript',
    js: 'javascript', jsx: 'javascript', mjs: 'javascript', cjs: 'javascript',
    twig: 'twig', liquid: 'liquid', rake: 'ruby',
  };
  return byExt[ext] ?? (MONACO_LANGUAGES.includes(language) ? language : 'plaintext');
}

/** Syntax modes bundled above; hover and navigation are registered for each. */
export const MONACO_LANGUAGES = ['java', 'python', 'javascript', 'typescript', 'php', 'csharp', 'go', 'ruby', 'html', 'twig', 'liquid', 'css', 'sql'];

// Monaco cancels pending work (e.g. tokenization) when a model is replaced and rejects it with a "Canceled" error;
// that is expected, not a failure, so it is not reported as an unhandled rejection.
window.addEventListener('unhandledrejection', (e) => {
  const r = e.reason as { name?: string; message?: string } | undefined;
  if (r && (r.name === 'Canceled' || r.message === 'Canceled')) e.preventDefault();
});

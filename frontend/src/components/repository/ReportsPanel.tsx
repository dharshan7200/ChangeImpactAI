import React, { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { AlertTriangle, CheckCircle2, FileUp, Terminal } from 'lucide-react';
import { qualityApi } from '../../api/phase5';
import type { QualityReport, ReportType } from '../../types/phase5';
import { shortSha } from '../analysis/risk';

const TYPES: { type: ReportType; label: string; file: string; accept: string }[] = [
  { type: 'JACOCO', label: 'JaCoCo coverage', file: 'target/site/jacoco/jacoco.xml', accept: '.xml' },
  { type: 'JUNIT', label: 'JUnit / Surefire results', file: 'target/surefire-reports/TEST-*.xml (select all)', accept: '.xml' },
  { type: 'PIT', label: 'PIT mutation testing', file: 'target/pit-reports/mutations.xml', accept: '.xml' },
  { type: 'DEPENDENCY_CHECK', label: 'OWASP Dependency-Check', file: 'target/dependency-check-report.json', accept: '.json' },
];

function summaryText(r: QualityReport): string {
  const s = r.summary as Record<string, any>;
  switch (r.reportType) {
    case 'JACOCO': return `${s.lineCoverage}% lines · ${s.branchCoverage}% branches · ${s.sourceFiles} files`;
    case 'JUNIT': return `${s.tests} tests · ${Object.entries(s.byStatus ?? {}).map(([k, v]) => `${v} ${String(k).toLowerCase()}`).join(', ')}`;
    case 'PIT': return `${s.mutants} mutants · score ${s.mutationScore}%`;
    case 'DEPENDENCY_CHECK': return `${s.vulnerabilities} vulnerabilities in ${s.vulnerableDependencies} dependencies`;
  }
}

export const ReportsPanel: React.FC<{ repositoryId: string; defaultCommit?: string }> = ({ repositoryId, defaultCommit }) => {
  const queryClient = useQueryClient();
  const [type, setType] = useState<ReportType>('JACOCO');
  const [commitSha, setCommitSha] = useState(defaultCommit ?? '');
  const [files, setFiles] = useState<File[]>([]);
  const { data: reports = [] } = useQuery({ queryKey: ['reports', repositoryId], queryFn: () => qualityApi.listReports(repositoryId) });

  const upload = useMutation({
    mutationFn: () => qualityApi.uploadReport(repositoryId, type, commitSha.trim(), files),
    onSuccess: () => {
      setFiles([]);
      queryClient.invalidateQueries({ queryKey: ['reports', repositoryId] });
    },
  });
  const error = (upload.error as any)?.response?.data?.message ?? (upload.error as Error | null)?.message;
  const meta = TYPES.find((t) => t.type === type)!;

  return (
    <div className="grid grid-cols-1 xl:grid-cols-5 gap-4">
      <div className="xl:col-span-2 bg-slate-900 border border-slate-800 rounded-2xl p-6 space-y-3">
        <h2 className="text-base font-bold text-white flex items-center gap-2"><FileUp className="w-4 h-4 text-cyan-400" /> Upload a CI report</h2>
        <p className="text-xs text-slate-400">
          Reports are tied to the exact commit they were produced for; analyses use them only when their head commit matches.
          ChangeImpact AI never builds or runs your code — reports come from your CI.
        </p>
        <label className="block text-[11px] text-slate-400">Report type
          <select value={type} onChange={(e) => { setType(e.target.value as ReportType); setFiles([]); }}
                  className="mt-1 w-full bg-slate-950 border border-slate-700 rounded-lg px-2 py-1.5 text-xs text-slate-100">
            {TYPES.map((t) => <option key={t.type} value={t.type}>{t.label}</option>)}
          </select>
        </label>
        <label className="block text-[11px] text-slate-400">Commit SHA (full or 7+ characters)
          <input value={commitSha} onChange={(e) => setCommitSha(e.target.value)} placeholder="e.g. 500158f"
                 className="mt-1 w-full bg-slate-950 border border-slate-700 rounded-lg px-2 py-1.5 text-xs font-mono text-slate-100" />
        </label>
        <label className="block text-[11px] text-slate-400">File{type === 'JUNIT' ? 's' : ''} — <span className="font-mono">{meta.file}</span>
          <input type="file" accept={meta.accept} multiple={type === 'JUNIT'}
                 onChange={(e) => setFiles(Array.from(e.target.files ?? []))}
                 className="mt-1 block w-full text-xs text-slate-300 file:mr-3 file:px-3 file:py-1.5 file:rounded-lg file:border-0 file:bg-slate-800 file:text-slate-200" />
        </label>
        <button type="button" disabled={!files.length || commitSha.trim().length < 7 || upload.isPending} onClick={() => upload.mutate()}
                className="px-4 py-2 rounded-xl bg-cyan-500 hover:bg-cyan-400 disabled:opacity-40 text-slate-950 font-semibold text-xs cursor-pointer">
          {upload.isPending ? 'Uploading…' : `Upload ${files.length > 1 ? `${files.length} files` : 'report'}`}
        </button>
        {upload.isSuccess && <p className="text-xs text-emerald-300 flex items-center gap-1.5"><CheckCircle2 className="w-3.5 h-3.5" /> Ingested: {summaryText(upload.data)}</p>}
        {error && <p className="text-xs text-rose-300 flex items-center gap-1.5"><AlertTriangle className="w-3.5 h-3.5" /> {error}</p>}
        <div className="pt-3 border-t border-slate-800">
          <div className="text-[11px] text-slate-400 flex items-center gap-1.5 mb-1"><Terminal className="w-3.5 h-3.5" /> From CI</div>
          <pre className="text-[10px] text-slate-400 bg-slate-950 border border-slate-800 rounded-lg p-2 overflow-x-auto">{`curl -H "Authorization: Bearer $TOKEN" \\
  -F type=${type} -F commitSha=$GITHUB_SHA \\
  -F file=@${meta.file.split(' ')[0]} \\
  $API/api/v1/repositories/${repositoryId}/reports`}</pre>
        </div>
      </div>

      <div className="xl:col-span-3 bg-slate-900 border border-slate-800 rounded-2xl p-6">
        <h2 className="text-base font-bold text-white mb-3">Uploaded reports ({reports.length})</h2>
        {reports.length === 0 ? (
          <p className="text-xs text-slate-500">No reports yet.</p>
        ) : (
          <table className="w-full text-xs">
            <thead className="text-[10px] uppercase tracking-wider text-slate-500 border-b border-slate-800">
              <tr><th className="text-left py-2">Type</th><th className="text-left py-2">Commit</th><th className="text-left py-2">Summary</th><th className="text-left py-2">Uploaded</th></tr>
            </thead>
            <tbody className="divide-y divide-slate-800/70">
              {reports.map((r) => (
                <tr key={r.id}>
                  <td className="py-2 pr-3 font-semibold text-slate-300">{TYPES.find((t) => t.type === r.reportType)?.label ?? r.reportType}</td>
                  <td className="py-2 pr-3 font-mono text-slate-400">{shortSha(r.commitSha)}</td>
                  <td className="py-2 pr-3 text-slate-300">{summaryText(r)}</td>
                  <td className="py-2 text-slate-500">{new Date(r.createdAt).toLocaleString()}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>
    </div>
  );
};

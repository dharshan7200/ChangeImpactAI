import React from 'react';
import { Link } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { Bot, CheckCircle2, CircleDashed, ExternalLink, Webhook } from 'lucide-react';
import { githubAppApi } from '../../api/phase5';

const Check: React.FC<{ ok: boolean; label: string; hint: string }> = ({ ok, label, hint }) => (
  <li className="flex gap-2 text-xs">
    {ok ? <CheckCircle2 className="w-4 h-4 text-emerald-400 shrink-0" /> : <CircleDashed className="w-4 h-4 text-slate-500 shrink-0" />}
    <div>
      <div className={ok ? 'text-slate-100' : 'text-slate-300'}>{label}</div>
      <div className="text-[11px] text-slate-500">{hint}</div>
    </div>
  </li>
);

const STATUS_CLS: Record<string, string> = {
  ACCEPTED: 'text-emerald-300',
  IGNORED: 'text-slate-400',
  FAILED: 'text-rose-300',
  PROCESSING: 'text-amber-300',
};

export const GitHubAppPanel: React.FC = () => {
  const { data: status } = useQuery({ queryKey: ['github-app'], queryFn: githubAppApi.status });
  const { data: deliveries = [] } = useQuery({ queryKey: ['github-app-deliveries'], queryFn: githubAppApi.deliveries, refetchInterval: 15000 });
  if (!status) return null;
  const webhookUrl = `https://<your-backend-host>${status.webhookPath}`;

  return (
    <div className="bg-slate-900 border border-slate-800 rounded-2xl p-6 space-y-5">
      <div>
        <h2 className="text-base font-bold text-white flex items-center gap-2"><Bot className="w-4 h-4 text-violet-400" /> GitHub App — pull request automation</h2>
        <p className="text-xs text-slate-400 mt-1">
          With the App installed, every opened or updated pull request is analysed automatically and the result is published as the
          “{status.checkName}” Check Run (risk, tests to run, security findings, annotations on the changed lines).
        </p>
      </div>

      <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
        <div>
          <h3 className="text-xs font-semibold text-slate-200 mb-2">Status</h3>
          <ul className="space-y-2.5">
            <Check ok={status.appConfigured} label="App credentials" hint="GITHUB_APP_CLIENT_ID (or GITHUB_APP_ID) and GITHUB_APP_PRIVATE_KEY_PATH on the backend" />
            <Check ok={status.webhookSecretConfigured} label="Webhook secret" hint="GITHUB_WEBHOOK_SECRET — deliveries without a valid signature are rejected" />
            <Check ok={status.checksEnabled && status.appConfigured} label="Check Runs" hint={`Fails at overall risk ${status.failOn} or an introduced vulnerability (configurable)`} />
            <Check ok={status.detailsLinks} label="Links back to ChangeImpact AI" hint="CHANGEIMPACT_PUBLIC_URL — the Check Run's “Details” opens the full analysis" />
          </ul>
        </div>
        <div>
          <h3 className="text-xs font-semibold text-slate-200 mb-2">Setup</h3>
          <ol className="list-decimal list-inside space-y-1.5 text-[11px] text-slate-400">
            <li>Register a GitHub App (Settings → Developer settings → GitHub Apps).</li>
            <li>Webhook URL <span className="font-mono text-slate-300">{webhookUrl}</span>, content type <span className="font-mono">application/json</span>, a random secret.</li>
            <li>Permissions: {Object.entries(status.requiredPermissions).map(([k, v]) => `${k.replace('_', ' ')} ${v}`).join(' · ')}.</li>
            <li>Events: {status.requiredEvents.join(', ')}.</li>
            <li>Generate a private key and configure the backend variables listed on the left.</li>
            <li>Install the App on your repositories{status.installUrl && <> — <a href={status.installUrl} target="_blank" rel="noreferrer" className="text-cyan-400 hover:underline inline-flex items-center gap-0.5">install <ExternalLink className="w-3 h-3" /></a></>}.</li>
          </ol>
        </div>
      </div>

      <div>
        <h3 className="text-xs font-semibold text-slate-200 mb-2">Your repositories</h3>
        <div className="flex flex-wrap gap-2">
          {status.repositories.map((r) => (
            <Link key={r.repositoryId} to={`/repositories/${r.repositoryId}`}
                  className={`text-[11px] px-2 py-1 rounded-lg border ${r.installed ? 'border-emerald-700/60 text-emerald-200' : 'border-slate-700 text-slate-400'}`}>
              {r.fullName} · {r.installed ? (r.suspended ? 'suspended' : 'App installed') : 'App not installed'}
            </Link>
          ))}
          {status.repositories.length === 0 && <span className="text-[11px] text-slate-500">Connect a repository first.</span>}
        </div>
      </div>

      <div>
        <h3 className="text-xs font-semibold text-slate-200 mb-2 flex items-center gap-1.5"><Webhook className="w-3.5 h-3.5" /> Recent webhook deliveries</h3>
        {deliveries.length === 0 ? (
          <p className="text-[11px] text-slate-500">No deliveries for your repositories yet.</p>
        ) : (
          <div className="max-h-64 overflow-y-auto border border-slate-800 rounded-xl">
            <table className="w-full text-[11px]">
              <thead className="sticky top-0 bg-slate-900 text-[10px] uppercase tracking-wider text-slate-500">
                <tr><th className="text-left p-2">Received</th><th className="text-left p-2">Event</th><th className="text-left p-2">Repository</th><th className="text-left p-2">Result</th></tr>
              </thead>
              <tbody className="divide-y divide-slate-800/70">
                {deliveries.map((d) => (
                  <tr key={d.id}>
                    <td className="p-2 text-slate-500 whitespace-nowrap">{new Date(d.receivedAt).toLocaleString()}</td>
                    <td className="p-2 text-slate-300">{d.event}{d.action ? ` · ${d.action}` : ''}</td>
                    <td className="p-2 font-mono text-slate-400">{d.repositoryFullName}</td>
                    <td className="p-2">
                      <span className={`font-semibold ${STATUS_CLS[d.status] ?? 'text-slate-300'}`}>{d.status}</span>
                      <div className="text-slate-500">{d.error ?? d.detail}</div>
                      {(d.analysisIds ?? []).map((a) => <Link key={a} to={`/analyses/${a}`} className="text-cyan-400 hover:underline mr-2">analysis</Link>)}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>
    </div>
  );
};

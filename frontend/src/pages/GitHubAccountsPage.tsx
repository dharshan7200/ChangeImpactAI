import React, { useState } from 'react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { githubApi } from '../api/github';
import { Github, Plus, Trash2, KeyRound, CheckCircle2, AlertCircle, ExternalLink, Shield } from 'lucide-react';

export const GitHubAccountsPage: React.FC = () => {
  const [tokenInput, setTokenInput] = useState('');
  const [showModal, setShowModal] = useState(false);
  const [errorMsg, setErrorMsg] = useState<string | null>(null);

  const queryClient = useQueryClient();

  const { data: accounts, isLoading } = useQuery({
    queryKey: ['github-accounts'],
    queryFn: () => githubApi.listAccounts(),
  });

  const connectMutation = useMutation({
    mutationFn: (token: string) => githubApi.connect(token),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['github-accounts'] });
      setShowModal(false);
      setTokenInput('');
      setErrorMsg(null);
    },
    onError: (err: any) => {
      setErrorMsg(err.response?.data?.message || 'Failed to authenticate with GitHub. Check your token scopes.');
    },
  });

  const disconnectMutation = useMutation({
    mutationFn: (id: string) => githubApi.disconnect(id),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['github-accounts'] });
    },
  });

  const handleConnect = (e: React.FormEvent) => {
    e.preventDefault();
    setErrorMsg(null);
    connectMutation.mutate(tokenInput.trim());
  };

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-bold text-white tracking-tight">GitHub Accounts</h1>
          <p className="text-xs text-slate-400 mt-1">
            Connect personal access tokens or GitHub OAuth to link repositories for change impact indexing
          </p>
        </div>
        <button
          onClick={() => setShowModal(true)}
          className="px-4 py-2.5 bg-cyan-500 hover:bg-cyan-400 text-slate-950 text-xs font-semibold rounded-xl flex items-center space-x-2 transition shadow-lg shadow-cyan-500/20 cursor-pointer"
        >
          <Plus className="w-4 h-4" />
          <span>Connect Account</span>
        </button>
      </div>

      {/* Account List */}
      <div className="bg-slate-900 border border-slate-800 rounded-2xl p-6">
        {isLoading ? (
          <div className="py-12 text-center text-slate-500 text-sm">Loading connected accounts...</div>
        ) : !accounts || accounts.length === 0 ? (
          <div className="py-12 text-center">
            <Github className="w-12 h-12 text-slate-700 mx-auto mb-3" />
            <p className="text-sm font-medium text-slate-300">No GitHub accounts connected</p>
            <p className="text-xs text-slate-500 mt-1 mb-4">
              Add a GitHub Personal Access Token (classic or fine-grained) with <code className="text-cyan-400">repo</code> scope.
            </p>
            <button
              onClick={() => setShowModal(true)}
              className="inline-flex items-center space-x-2 px-4 py-2 bg-cyan-500/10 text-cyan-400 border border-cyan-500/30 rounded-xl text-xs font-semibold hover:bg-cyan-500/20 transition cursor-pointer"
            >
              <Plus className="w-4 h-4" />
              <span>Connect Now</span>
            </button>
          </div>
        ) : (
          <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
            {accounts.map((acc) => (
              <div
                key={acc.id}
                className="bg-slate-950/70 border border-slate-800 rounded-xl p-5 flex items-start justify-between"
              >
                <div className="flex items-start space-x-4">
                  {acc.avatarUrl ? (
                    <img src={acc.avatarUrl} alt={acc.login} className="w-12 h-12 rounded-full border border-slate-700" />
                  ) : (
                    <div className="w-12 h-12 rounded-full bg-slate-800 flex items-center justify-center text-slate-300 border border-slate-700">
                      <Github className="w-6 h-6" />
                    </div>
                  )}
                  <div>
                    <div className="flex items-center space-x-2">
                      <h3 className="font-bold text-white text-base">{acc.login}</h3>
                      <span className="inline-flex items-center space-x-1 text-[11px] bg-emerald-950/60 text-emerald-400 border border-emerald-800/50 px-2 py-0.5 rounded-full">
                        <CheckCircle2 className="w-3 h-3" />
                        <span>Active</span>
                      </span>
                    </div>
                    {acc.displayName && <p className="text-xs text-slate-400 mt-0.5">{acc.displayName}</p>}
                    <p className="text-xs text-slate-500 mt-2 font-mono">
                      Connected: {new Date(acc.connectedAt).toLocaleDateString()}
                    </p>
                  </div>
                </div>

                <button
                  onClick={() => disconnectMutation.mutate(acc.id)}
                  title="Disconnect Account"
                  className="p-2 text-slate-500 hover:text-rose-400 hover:bg-slate-900 rounded-lg transition"
                >
                  <Trash2 className="w-4 h-4" />
                </button>
              </div>
            ))}
          </div>
        )}
      </div>

      {/* Security notice */}
      <div className="bg-slate-900/60 border border-slate-800/80 rounded-xl p-4 flex items-start space-x-3 text-xs text-slate-400">
        <Shield className="w-5 h-5 text-cyan-400 shrink-0 mt-0.5" />
        <div>
          <p className="font-semibold text-slate-300">Enterprise Token Security</p>
          <p className="mt-0.5">
            GitHub access tokens are encrypted with AES-256-GCM before database storage. They are never logged,
            exposed in REST responses, or shared with client-side code.
          </p>
        </div>
      </div>

      {/* Connect Modal */}
      {showModal && (
        <div className="fixed inset-0 z-50 bg-slate-950/80 backdrop-blur-sm flex items-center justify-center p-4">
          <div className="bg-slate-900 border border-slate-800 rounded-2xl max-w-lg w-full p-6 shadow-2xl relative">
            <h2 className="text-lg font-bold text-white mb-2">Connect GitHub Account</h2>
            <p className="text-xs text-slate-400 mb-6">
              Provide a GitHub Personal Access Token (PAT) with <code className="text-cyan-400 font-mono">repo</code> permissions.
            </p>

            {errorMsg && (
              <div className="mb-4 p-3 rounded-xl bg-rose-950/40 border border-rose-800/60 text-rose-300 text-xs flex items-start space-x-2">
                <AlertCircle className="w-4 h-4 text-rose-400 shrink-0 mt-0.5" />
                <span>{errorMsg}</span>
              </div>
            )}

            <form onSubmit={handleConnect} className="space-y-4">
              <div>
                <label className="block text-xs font-semibold uppercase tracking-wider text-slate-400 mb-2">
                  Personal Access Token (PAT)
                </label>
                <div className="relative">
                  <KeyRound className="w-5 h-5 absolute left-3.5 top-3 text-slate-500" />
                  <input
                    type="password"
                    required
                    value={tokenInput}
                    onChange={(e) => setTokenInput(e.target.value)}
                    placeholder="ghp_••••••••••••••••••••••••••••••••"
                    className="w-full bg-slate-950 border border-slate-800 rounded-xl pl-11 pr-4 py-2.5 text-sm text-slate-100 placeholder-slate-600 focus:outline-none focus:border-cyan-500 transition font-mono"
                  />
                </div>
              </div>

              <div className="text-[11px] text-slate-500 flex items-center justify-between">
                <span>Required scope: <strong>repo</strong> (for private repos) or public read</span>
                <a
                  href="https://github.com/settings/tokens/new"
                  target="_blank"
                  rel="noreferrer"
                  className="text-cyan-400 hover:text-cyan-300 flex items-center space-x-1"
                >
                  <span>Generate token</span>
                  <ExternalLink className="w-3 h-3" />
                </a>
              </div>

              <div className="flex items-center justify-end space-x-3 pt-4 border-t border-slate-800">
                <button
                  type="button"
                  onClick={() => setShowModal(false)}
                  className="px-4 py-2 rounded-xl text-xs font-medium text-slate-400 hover:text-slate-200 transition"
                >
                  Cancel
                </button>
                <button
                  type="submit"
                  disabled={connectMutation.isPending}
                  className="px-5 py-2 rounded-xl bg-cyan-500 hover:bg-cyan-400 text-slate-950 text-xs font-semibold transition disabled:opacity-50 cursor-pointer shadow-lg shadow-cyan-500/20"
                >
                  {connectMutation.isPending ? 'Verifying with GitHub...' : 'Save & Connect'}
                </button>
              </div>
            </form>
          </div>
        </div>
      )}
    </div>
  );
};


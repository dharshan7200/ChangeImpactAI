import React, { useState, useEffect } from 'react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { repositoriesApi } from '../api/repositories';
import { githubApi } from '../api/github';
import { Link } from 'react-router-dom';
import { FolderGit2, Plus, Play, Trash2, Github, AlertCircle, Layers, FileCode, Globe } from 'lucide-react';
import { StatusBadge } from './DashboardPage';

export const RepositoriesPage: React.FC = () => {
  const [showModal, setShowModal] = useState(false);
  const [connectTab, setConnectTab] = useState<'account' | 'public'>('account');
  const [selectedAccountId, setSelectedAccountId] = useState('');
  const [selectedRepoFullName, setSelectedRepoFullName] = useState('');
  const [customBranch, setCustomBranch] = useState('main');
  const [manualMode, setManualMode] = useState(false);
  const [errorMsg, setErrorMsg] = useState<string | null>(null);

  const queryClient = useQueryClient();

  const { data: reposData, isLoading: reposLoading } = useQuery({
    queryKey: ['repositories'],
    queryFn: () => repositoriesApi.list(0, 50),
  });

  const { data: accounts } = useQuery({
    queryKey: ['github-accounts'],
    queryFn: () => githubApi.listAccounts(),
  });

  useEffect(() => {
    if (accounts && accounts.length > 0 && !selectedAccountId) {
      setSelectedAccountId(accounts[0].id);
    }
  }, [accounts, selectedAccountId]);

  const { data: remoteRepos, isLoading: remoteLoading } = useQuery({
    queryKey: ['remote-repos', selectedAccountId],
    queryFn: () => githubApi.listRemoteRepos(selectedAccountId),
    enabled: !!selectedAccountId,
  });

  const connectMutation = useMutation({
    mutationFn: (payload: { connectedAccountId?: string; fullName: string; branch?: string }) =>
      repositoriesApi.connect(payload),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['repositories'] });
      setShowModal(false);
      setSelectedRepoFullName('');
      setErrorMsg(null);
    },
    onError: (err: any) => {
      setErrorMsg(err.response?.data?.message || 'Failed to connect repository.');
    },
  });

  const indexMutation = useMutation({
    mutationFn: (id: string) => repositoriesApi.triggerIndexing(id),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['repositories'] });
    },
  });

  const deleteMutation = useMutation({
    mutationFn: (id: string) => repositoriesApi.delete(id),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['repositories'] });
    },
  });

  const handleConnectSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    if (!selectedRepoFullName.trim()) {
      setErrorMsg('Please specify a repository name (e.g. owner/repo)');
      return;
    }
    setErrorMsg(null);
    connectMutation.mutate({
      connectedAccountId: connectTab === 'account' ? (selectedAccountId || undefined) : undefined,
      fullName: selectedRepoFullName.trim(),
      branch: customBranch.trim() || 'main',
    });
  };

  const repositories = reposData?.content || [];

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-bold text-white tracking-tight">Connected Repositories</h1>
          <p className="text-xs text-slate-400 mt-1">
            Repositories imported from GitHub for AST parsing and dependency blast-radius analysis
          </p>
        </div>
        <button
          onClick={() => {
            if (accounts && accounts.length > 0 && !selectedAccountId) {
              setSelectedAccountId(accounts[0].id);
            }
            setShowModal(true);
          }}
          className="px-4 py-2.5 bg-cyan-500 hover:bg-cyan-400 text-slate-950 text-xs font-semibold rounded-xl flex items-center space-x-2 transition shadow-lg shadow-cyan-500/20 cursor-pointer"
        >
          <Plus className="w-4 h-4" />
          <span>Connect Repository</span>
        </button>
      </div>

      <div className="bg-slate-900 border border-slate-800 rounded-2xl p-6">
        {reposLoading ? (
          <div className="py-12 text-center text-slate-500 text-sm">Loading repositories...</div>
        ) : repositories.length === 0 ? (
          <div className="py-12 text-center">
            <FolderGit2 className="w-12 h-12 text-slate-700 mx-auto mb-3" />
            <p className="text-sm font-medium text-slate-300">No repositories connected yet</p>
            <p className="text-xs text-slate-500 mt-1 mb-4">
              Connect a Java repository to start building its dependency graph.
            </p>
            <button
              onClick={() => setShowModal(true)}
              className="inline-flex items-center space-x-2 px-4 py-2 bg-cyan-500/10 text-cyan-400 border border-cyan-500/30 rounded-xl text-xs font-semibold hover:bg-cyan-500/20 transition cursor-pointer"
            >
              <Plus className="w-4 h-4" />
              <span>Connect Repository</span>
            </button>
          </div>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-left text-sm">
              <thead className="text-xs uppercase text-slate-500 border-b border-slate-800 pb-2">
                <tr>
                  <th className="pb-3 font-semibold">Repository</th>
                  <th className="pb-3 font-semibold">Language</th>
                  <th className="pb-3 font-semibold">Branch</th>
                  <th className="pb-3 font-semibold">Indexing Status</th>
                  <th className="pb-3 font-semibold">Last Indexed</th>
                  <th className="pb-3 font-semibold text-right">Actions</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-800/60">
                {repositories.map((repo) => (
                  <tr key={repo.id} className="hover:bg-slate-850/50 transition">
                    <td className="py-4">
                      <Link to={`/repositories/${repo.id}`} className="font-semibold text-slate-200 hover:text-cyan-400 flex items-center space-x-2">
                        <FolderGit2 className="w-4 h-4 text-cyan-400" />
                        <span>{repo.fullName}</span>
                      </Link>
                    </td>
                    <td className="py-4 text-slate-400">
                      <span className="flex items-center space-x-1.5">
                        <FileCode className="w-3.5 h-3.5 text-amber-400" />
                        <span>{repo.language || '—'}</span>
                      </span>
                    </td>
                    <td className="py-4 font-mono text-xs text-slate-400">{repo.defaultBranch}</td>
                    <td className="py-4">
                      <StatusBadge status={repo.indexingStatus} />
                    </td>
                    <td className="py-4 text-xs text-slate-400">
                      {repo.lastIndexedAt ? new Date(repo.lastIndexedAt).toLocaleDateString() : 'Never'}
                    </td>
                    <td className="py-4 text-right">
                      <div className="flex items-center justify-end space-x-2">
                        <button
                          onClick={() => indexMutation.mutate(repo.id)}
                          disabled={repo.indexingStatus === 'INDEXING' || indexMutation.isPending}
                          className="px-3 py-1.5 bg-cyan-500/10 hover:bg-cyan-500/20 text-cyan-400 border border-cyan-500/30 text-xs rounded-lg inline-flex items-center space-x-1.5 transition disabled:opacity-40 cursor-pointer"
                          title="Trigger Asynchronous Indexing via RabbitMQ"
                        >
                          <Play className="w-3.5 h-3.5" />
                          <span>{repo.indexingStatus === 'INDEXING' ? 'Indexing...' : 'Index'}</span>
                        </button>

                        <Link
                          to={`/repositories/${repo.id}`}
                          className="px-3 py-1.5 bg-slate-800 hover:bg-slate-700 text-slate-200 text-xs rounded-lg border border-slate-700 inline-flex items-center space-x-1.5 transition"
                        >
                          <Layers className="w-3.5 h-3.5" />
                          <span>Details</span>
                        </Link>

                        <button
                          onClick={() => {
                            if (window.confirm(`Disconnect ${repo.fullName}?`)) {
                              deleteMutation.mutate(repo.id);
                            }
                          }}
                          className="p-1.5 text-slate-500 hover:text-rose-400 hover:bg-slate-800 rounded-lg transition"
                          title="Delete Repository"
                        >
                          <Trash2 className="w-4 h-4" />
                        </button>
                      </div>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>

      {/* Connect Modal */}
      {showModal && (
        <div className="fixed inset-0 z-50 bg-slate-950/80 backdrop-blur-sm flex items-center justify-center p-4">
          <div className="bg-slate-900 border border-slate-800 rounded-2xl max-w-lg w-full p-6 shadow-2xl relative">
            <h2 className="text-lg font-bold text-white mb-1">Connect GitHub Repository</h2>
            <p className="text-xs text-slate-400 mb-6">
              Select an authorized GitHub account and import a Java project.
            </p>

            {errorMsg && (
              <div className="mb-4 p-3 rounded-xl bg-rose-950/40 border border-rose-800/60 text-rose-300 text-xs flex items-start space-x-2">
                <AlertCircle className="w-4 h-4 text-rose-400 shrink-0 mt-0.5" />
                <span>{errorMsg}</span>
              </div>
            )}

            {/* Navigation Tabs inside modal */}
            <div className="flex border-b border-slate-800 mb-5">
              <button
                type="button"
                onClick={() => {
                  setConnectTab('account');
                  setErrorMsg(null);
                }}
                className={`flex items-center space-x-2 py-2.5 px-4 text-xs font-semibold border-b-2 transition cursor-pointer ${
                  connectTab === 'account'
                    ? 'border-cyan-400 text-cyan-400'
                    : 'border-transparent text-slate-400 hover:text-slate-200'
                }`}
              >
                <Github className="w-3.5 h-3.5" />
                <span>My GitHub Account</span>
              </button>
              <button
                type="button"
                onClick={() => {
                  setConnectTab('public');
                  setErrorMsg(null);
                }}
                className={`flex items-center space-x-2 py-2.5 px-4 text-xs font-semibold border-b-2 transition cursor-pointer ${
                  connectTab === 'public'
                    ? 'border-cyan-400 text-cyan-400'
                    : 'border-transparent text-slate-400 hover:text-slate-200'
                }`}
              >
                <Globe className="w-3.5 h-3.5" />
                <span>Any Public Repository</span>
              </button>
            </div>

            {connectTab === 'public' ? (
              <form onSubmit={handleConnectSubmit} className="space-y-4">
                <div className="p-3 bg-cyan-950/20 border border-cyan-800/40 rounded-xl text-xs text-cyan-300">
                  Analyze <strong>any open-source public repository</strong> from any GitHub user or organization (no account authorization required).
                </div>

                <div>
                  <label className="block text-xs font-semibold uppercase tracking-wider text-slate-400 mb-2">
                    Repository (owner/repo or GitHub URL)
                  </label>
                  <input
                    type="text"
                    required
                    placeholder="e.g. spring-projects/spring-petclinic or https://github.com/..."
                    value={selectedRepoFullName}
                    onChange={(e) => setSelectedRepoFullName(e.target.value)}
                    className="w-full bg-slate-950 border border-slate-800 rounded-xl px-4 py-2.5 text-sm text-slate-100 placeholder-slate-600 focus:outline-none focus:border-cyan-500 transition"
                  />
                  <div className="mt-2.5 flex flex-wrap items-center gap-1.5 text-[11px] text-slate-400">
                    <span>Try popular Java repos:</span>
                    <button
                      type="button"
                      onClick={() => {
                        setSelectedRepoFullName('spring-projects/spring-petclinic');
                        setCustomBranch('main');
                      }}
                      className="px-2 py-0.5 rounded bg-slate-800 hover:bg-slate-700 text-cyan-400 hover:text-cyan-300 transition cursor-pointer"
                    >
                      spring-petclinic
                    </button>
                    <button
                      type="button"
                      onClick={() => {
                        setSelectedRepoFullName('iluwatar/java-design-patterns');
                        setCustomBranch('master');
                      }}
                      className="px-2 py-0.5 rounded bg-slate-800 hover:bg-slate-700 text-cyan-400 hover:text-cyan-300 transition cursor-pointer"
                    >
                      java-design-patterns
                    </button>
                    <button
                      type="button"
                      onClick={() => {
                        setSelectedRepoFullName('google/guava');
                        setCustomBranch('master');
                      }}
                      className="px-2 py-0.5 rounded bg-slate-800 hover:bg-slate-700 text-cyan-400 hover:text-cyan-300 transition cursor-pointer"
                    >
                      guava
                    </button>
                  </div>
                </div>

                <div>
                  <label className="block text-xs font-semibold uppercase tracking-wider text-slate-400 mb-2">
                    Branch
                  </label>
                  <input
                    type="text"
                    required
                    value={customBranch}
                    onChange={(e) => setCustomBranch(e.target.value)}
                    placeholder="main or master"
                    className="w-full bg-slate-950 border border-slate-800 rounded-xl px-4 py-2 text-sm text-slate-100 placeholder-slate-600 focus:outline-none focus:border-cyan-500 transition font-mono"
                  />
                </div>

                <div className="flex items-center justify-end space-x-3 pt-4 border-t border-slate-800">
                  <button
                    type="button"
                    onClick={() => {
                      setShowModal(false);
                      setErrorMsg(null);
                    }}
                    className="px-4 py-2 rounded-xl text-xs font-medium text-slate-400 hover:text-slate-200 transition"
                  >
                    Cancel
                  </button>
                  <button
                    type="submit"
                    disabled={connectMutation.isPending}
                    className="px-5 py-2 rounded-xl bg-cyan-500 hover:bg-cyan-400 text-slate-950 text-xs font-semibold transition disabled:opacity-50 cursor-pointer shadow-lg shadow-cyan-500/20"
                  >
                    {connectMutation.isPending ? 'Connecting...' : 'Connect Public Repo'}
                  </button>
                </div>
              </form>
            ) : !accounts || accounts.length === 0 ? (
              <div className="text-center py-6">
                <Github className="w-10 h-10 text-slate-600 mx-auto mb-2" />
                <p className="text-sm font-semibold text-slate-200">No connected GitHub accounts</p>
                <p className="text-xs text-slate-400 mt-1 mb-4">
                  Please connect your GitHub account first or use the <strong>Any Public Repository</strong> tab.
                </p>
                <div className="flex items-center justify-center space-x-3">
                  <Link
                    to="/github"
                    className="px-4 py-2 bg-cyan-500 text-slate-950 text-xs font-semibold rounded-xl inline-flex items-center space-x-2"
                  >
                    <Plus className="w-4 h-4" />
                    <span>Go to GitHub Accounts</span>
                  </Link>
                  <button
                    type="button"
                    onClick={() => setConnectTab('public')}
                    className="px-4 py-2 bg-slate-800 hover:bg-slate-700 text-slate-200 text-xs font-semibold rounded-xl inline-flex items-center space-x-2 transition"
                  >
                    <Globe className="w-4 h-4 text-cyan-400" />
                    <span>Use Public Repo</span>
                  </button>
                </div>
              </div>
            ) : (
              <form onSubmit={handleConnectSubmit} className="space-y-4">
                <div>
                  <label className="block text-xs font-semibold uppercase tracking-wider text-slate-400 mb-2">
                    GitHub Account
                  </label>
                  <select
                    value={selectedAccountId}
                    onChange={(e) => {
                      setSelectedAccountId(e.target.value);
                      setSelectedRepoFullName('');
                    }}
                    className="w-full bg-slate-950 border border-slate-800 rounded-xl px-4 py-2.5 text-sm text-slate-100 focus:outline-none focus:border-cyan-500 transition"
                  >
                    {accounts.map((acc) => (
                      <option key={acc.id} value={acc.id}>
                        {acc.login} ({acc.displayName || 'GitHub'})
                      </option>
                    ))}
                  </select>
                </div>

                <div>
                  <div className="flex items-center justify-between mb-2">
                    <label className="block text-xs font-semibold uppercase tracking-wider text-slate-400">
                      Select Remote Repository
                    </label>
                    <button
                      type="button"
                      onClick={() => setManualMode(!manualMode)}
                      className="text-[11px] text-cyan-400 hover:text-cyan-300 transition underline cursor-pointer"
                    >
                      {manualMode ? 'Choose from list' : 'Type name manually'}
                    </button>
                  </div>

                  {manualMode ? (
                    <input
                      type="text"
                      required
                      placeholder="owner/repo-name (e.g. dharshan7200/my-java-project)"
                      value={selectedRepoFullName}
                      onChange={(e) => setSelectedRepoFullName(e.target.value)}
                      className="w-full bg-slate-950 border border-slate-800 rounded-xl px-4 py-2.5 text-sm text-slate-100 placeholder-slate-600 focus:outline-none focus:border-cyan-500 transition"
                    />
                  ) : remoteLoading ? (
                    <div className="flex items-center space-x-2 text-xs text-cyan-400 py-2.5">
                      <div className="w-3.5 h-3.5 border-2 border-cyan-400 border-t-transparent rounded-full animate-spin" />
                      <span>Fetching repositories from GitHub...</span>
                    </div>
                  ) : remoteRepos && remoteRepos.length > 0 ? (
                    <select
                      value={selectedRepoFullName}
                      onChange={(e) => {
                        const val = e.target.value;
                        setSelectedRepoFullName(val);
                        const chosen = remoteRepos.find((r) => r.full_name === val);
                        if (chosen?.default_branch) {
                          setCustomBranch(chosen.default_branch);
                        }
                      }}
                      className="w-full bg-slate-950 border border-slate-800 rounded-xl px-4 py-2.5 text-sm text-slate-100 focus:outline-none focus:border-cyan-500 transition"
                    >
                      <option value="">-- Choose a repository ({remoteRepos.length} found) --</option>
                      {remoteRepos.map((r) => (
                        <option key={r.id} value={r.full_name}>
                          {r.full_name} ({r.language || 'Code'})
                        </option>
                      ))}
                    </select>
                  ) : (
                    <div>
                      <p className="text-xs text-slate-400 mb-2">
                        Enter your repository name directly (e.g. <code className="text-cyan-400">dharshan7200/your-repo</code>):
                      </p>
                      <input
                        type="text"
                        required
                        placeholder="owner/repo-name"
                        value={selectedRepoFullName}
                        onChange={(e) => setSelectedRepoFullName(e.target.value)}
                        className="w-full bg-slate-950 border border-slate-800 rounded-xl px-4 py-2.5 text-sm text-slate-100 placeholder-slate-600 focus:outline-none focus:border-cyan-500 transition"
                      />
                    </div>
                  )}
                </div>

                <div>
                  <label className="block text-xs font-semibold uppercase tracking-wider text-slate-400 mb-2">
                    Default Branch
                  </label>
                  <input
                    type="text"
                    required
                    value={customBranch}
                    onChange={(e) => setCustomBranch(e.target.value)}
                    placeholder="main"
                    className="w-full bg-slate-950 border border-slate-800 rounded-xl px-4 py-2.5 text-sm text-slate-100 placeholder-slate-600 focus:outline-none focus:border-cyan-500 transition font-mono"
                  />
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
                    {connectMutation.isPending ? 'Connecting...' : 'Import Repository'}
                  </button>
                </div>
              </form>
            )}
          </div>
        </div>
      )}
    </div>
  );
};


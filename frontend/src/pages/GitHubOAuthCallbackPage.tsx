import React from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { useQueryClient } from '@tanstack/react-query';
import { AlertCircle, CheckCircle2, Loader2 } from 'lucide-react';
import { githubApi } from '../api/github';

/** GitHub redirects here after consent; the code is exchanged server-side (the client secret never reaches the browser). */
export const GitHubOAuthCallbackPage: React.FC = () => {
  const [params] = useSearchParams();
  const navigate = useNavigate();
  const qc = useQueryClient();
  const [state, setState] = React.useState<{ status: 'working' | 'done' | 'error'; message: string }>({ status: 'working', message: 'Connecting your GitHub account…' });
  const started = React.useRef(false);

  React.useEffect(() => {
    if (started.current) return; // React strict mode runs effects twice; the state is single-use
    started.current = true;
    const error = params.get('error');
    const code = params.get('code'), st = params.get('state');
    if (error) {
      setState({ status: 'error', message: params.get('error_description') || `GitHub returned: ${error}` });
      return;
    }
    if (!code || !st) {
      setState({ status: 'error', message: 'The GitHub response is missing the authorization code.' });
      return;
    }
    githubApi.oauthComplete(code, st)
      .then((account) => {
        qc.invalidateQueries({ queryKey: ['github-accounts'] });
        setState({ status: 'done', message: `Connected GitHub account ${account.login}.` });
        setTimeout(() => navigate('/github', { replace: true }), 1200);
      })
      .catch((err) => setState({ status: 'error', message: err.response?.data?.message || 'Could not connect the GitHub account.' }));
  }, [params, navigate, qc]);

  return (
    <div className="max-w-lg mx-auto mt-16 bg-slate-900 border border-slate-800 rounded-2xl p-8 text-center space-y-3">
      {state.status === 'working' && <Loader2 className="w-8 h-8 text-cyan-400 mx-auto animate-spin" />}
      {state.status === 'done' && <CheckCircle2 className="w-8 h-8 text-emerald-400 mx-auto" />}
      {state.status === 'error' && <AlertCircle className="w-8 h-8 text-rose-400 mx-auto" />}
      <p className="text-sm text-slate-200">{state.message}</p>
      {state.status === 'error' && (
        <button onClick={() => navigate('/github')} className="text-xs text-cyan-400 hover:text-cyan-300">Back to GitHub accounts</button>
      )}
    </div>
  );
};

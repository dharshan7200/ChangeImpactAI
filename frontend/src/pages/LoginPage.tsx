import React, { useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { ArrowRight, KeyRound, Mail } from 'lucide-react';
import { useAuth } from '../context/AuthContext';
import { AuthShell, Field, FormError, PrimaryButton } from '../components/brand/AuthShell';

export const LoginPage: React.FC = () => {
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  const { login } = useAuth();
  const navigate = useNavigate();

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError(null);
    setSubmitting(true);
    try {
      await login(email, password);
      navigate('/');
    } catch (err: any) {
      setError(err.response?.data?.message || 'Invalid credentials. Please verify your email and password.');
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <AuthShell
      title="Welcome back"
      subtitle="Sign in to review the impact of your changes."
      footer={
        <p>
          New to ChangeImpact?{' '}
          <Link to="/register" className="font-medium text-cyan-300 underline-offset-4 hover:text-cyan-200 hover:underline">
            Create an account
          </Link>
        </p>
      }
    >
      <FormError message={error} />
      <form onSubmit={handleSubmit} className="space-y-5" noValidate={false}>
        <Field id="email" label="Email" icon={Mail} type="email" required autoComplete="email" autoFocus
               value={email} onChange={(e) => setEmail(e.target.value)} placeholder="you@company.com" />
        <Field id="password" label="Password" icon={KeyRound} type="password" required autoComplete="current-password"
               value={password} onChange={(e) => setPassword(e.target.value)} placeholder="Your password" />
        <PrimaryButton type="submit" busy={submitting} className="mt-2">
          {submitting ? 'Signing in…' : <>Sign in <ArrowRight className="h-4 w-4" aria-hidden /></>}
        </PrimaryButton>
      </form>
    </AuthShell>
  );
};

import React, { useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { ArrowRight, AtSign, KeyRound, Mail, User } from 'lucide-react';
import { useAuth } from '../context/AuthContext';
import { AuthShell, Field, FormError, PrimaryButton } from '../components/brand/AuthShell';

export const RegisterPage: React.FC = () => {
  const [email, setEmail] = useState('');
  const [username, setUsername] = useState('');
  const [displayName, setDisplayName] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  const { register } = useAuth();
  const navigate = useNavigate();

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError(null);
    setSubmitting(true);
    try {
      await register(email, username, password, displayName);
      navigate('/');
    } catch (err: any) {
      const errList = err.response?.data?.errors;
      if (errList && errList.length > 0) {
        setError(errList.map((e: any) => e.message).join(', '));
      } else {
        setError(err.response?.data?.message || 'Registration failed. Please check your details.');
      }
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <AuthShell
      title="Create your account"
      subtitle="Connect a repository and see the impact of every change."
      footer={
        <p>
          Already have an account?{' '}
          <Link to="/login" className="font-medium text-cyan-300 underline-offset-4 hover:text-cyan-200 hover:underline">
            Sign in
          </Link>
        </p>
      }
    >
      <FormError message={error} />
      <form onSubmit={handleSubmit} className="space-y-4">
        <Field id="email" label="Email" icon={Mail} type="email" required autoComplete="email" autoFocus
               value={email} onChange={(e) => setEmail(e.target.value)} placeholder="you@company.com" />
        <div className="grid gap-4 sm:grid-cols-2">
          <Field id="username" label="Username" icon={AtSign} type="text" required autoComplete="username"
                 pattern="[-a-zA-Z0-9_]+" title="Letters, digits, - and _"
                 value={username} onChange={(e) => setUsername(e.target.value)} placeholder="janedoe" />
          <Field id="displayName" label="Display name" icon={User} type="text" autoComplete="name"
                 value={displayName} onChange={(e) => setDisplayName(e.target.value)} placeholder="Optional" />
        </div>
        <Field id="password" label="Password" icon={KeyRound} type="password" required minLength={8} autoComplete="new-password"
               hint="At least 8 characters." value={password} onChange={(e) => setPassword(e.target.value)} placeholder="Choose a password" />
        <PrimaryButton type="submit" busy={submitting} className="mt-2">
          {submitting ? 'Creating account…' : <>Create account <ArrowRight className="h-4 w-4" aria-hidden /></>}
        </PrimaryButton>
      </form>
    </AuthShell>
  );
};

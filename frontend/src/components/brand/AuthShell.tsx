import React from 'react';
import { AlertCircle, GitCompareArrows, Radar, ShieldCheck, type LucideIcon } from 'lucide-react';
import { Logo } from './Logo';
import { ImpactAnimation } from './ImpactAnimation';
import { LANGUAGE_MARKS, LanguageStrip } from './LanguageIcons';

const POINTS: { icon: LucideIcon; title: string; text: string }[] = [
  { icon: GitCompareArrows, title: 'Semantic diff', text: 'Signature, behaviour and API changes, not just changed lines.' },
  { icon: Radar, title: 'Impact & risk', text: 'Every caller, dependent and table a change reaches, with an explained risk score.' },
  { icon: ShieldCheck, title: 'Tests & security', text: 'The tests to run first and the security findings in the change.' },
];

/** Split-screen frame for sign-in and registration: product story on the left, the form on the right. */
export const AuthShell: React.FC<{ title: string; subtitle: string; children: React.ReactNode; footer: React.ReactNode }> = ({
  title, subtitle, children, footer,
}) => (
  <div className="min-h-screen grid lg:grid-cols-[1.05fr_1fr]">
    {/* Brand panel */}
    <aside className="relative hidden lg:flex flex-col justify-between overflow-hidden border-r border-slate-800/80 bg-slate-900/40 p-12">
      <Logo size={36} />
      <div className="relative max-w-md animate-fade-up">
        <p className="font-mono text-xs uppercase tracking-[0.2em] text-cyan-300/90">Change impact intelligence</p>
        <h2 className="mt-4 text-4xl font-semibold leading-[1.15] text-white">
          See what a change will break <span className="text-cyan-300">before</span> it ships.
        </h2>
        <div className="mt-8 rounded-xl border border-slate-800 bg-slate-950/70 p-4 shadow-panel animate-fade-up" style={{ animationDelay: '120ms' }}>
          <ImpactAnimation />
        </div>
        <ul className="mt-8 space-y-4">
          {POINTS.map(({ icon: Icon, title: t, text }, i) => (
            <li key={t} className="flex gap-4 animate-fade-up" style={{ animationDelay: `${240 + i * 90}ms` }}>
              <span className="mt-0.5 flex h-9 w-9 shrink-0 items-center justify-center rounded-lg border border-slate-700/80 bg-slate-900">
                <Icon className="h-[18px] w-[18px] text-cyan-300" aria-hidden />
              </span>
              <span>
                <span className="block text-sm font-semibold text-slate-100">{t}</span>
                <span className="block text-sm text-slate-400">{text}</span>
              </span>
            </li>
          ))}
        </ul>
      </div>
      <div className="relative">
        <p className="mb-2.5 text-xs text-slate-500">Analyses {LANGUAGE_MARKS.length} languages</p>
        <div className="max-w-[260px]"><LanguageStrip /></div>
      </div>
    </aside>

    {/* Form */}
    <main className="flex items-center justify-center px-5 py-12 sm:px-10">
      <div className="w-full max-w-[400px] animate-fade-up">
        <div className="lg:hidden mb-10 flex justify-center"><Logo size={36} /></div>
        <h1 className="text-[28px] font-semibold text-white">{title}</h1>
        <p className="mt-1.5 text-sm text-slate-400">{subtitle}</p>
        <div className="mt-8">{children}</div>
        <div className="mt-8 border-t border-slate-800 pt-6 text-sm text-slate-400">{footer}</div>
      </div>
    </main>
  </div>
);

/** Labelled text input with optional leading icon, helper text and an error shown beside the field. */
export const Field: React.FC<{
  id: string;
  label: string;
  icon?: LucideIcon;
  hint?: string;
  error?: string | null;
} & React.InputHTMLAttributes<HTMLInputElement>> = ({ id, label, icon: Icon, hint, error, className, ...input }) => (
  <div>
    <label htmlFor={id} className="block text-sm font-medium text-slate-200 mb-1.5">{label}</label>
    <div className="relative">
      {Icon && <Icon className="pointer-events-none absolute left-3.5 top-1/2 h-[18px] w-[18px] -translate-y-1/2 text-slate-500" aria-hidden />}
      <input
        id={id}
        aria-describedby={hint || error ? `${id}-help` : undefined}
        aria-invalid={error ? true : undefined}
        className={`h-11 w-full rounded-lg border bg-slate-900/80 ${Icon ? 'pl-11' : 'pl-3.5'} pr-3.5 text-[15px] text-slate-100 placeholder-slate-600 transition-colors
          focus:outline-none focus:ring-2 focus:ring-cyan-400/40 ${error ? 'border-rose-500/70 focus:border-rose-400' : 'border-slate-700/80 hover:border-slate-600 focus:border-cyan-400'} ${className ?? ''}`}
        {...input}
      />
    </div>
    {(hint || error) && (
      <p id={`${id}-help`} className={`mt-1.5 text-xs ${error ? 'text-rose-300' : 'text-slate-500'}`}>{error || hint}</p>
    )}
  </div>
);

export const FormError: React.FC<{ message: string | null }> = ({ message }) =>
  message ? (
    <div role="alert" className="mb-6 flex items-start gap-3 rounded-lg border border-rose-800/60 bg-rose-950/40 p-3.5 text-sm text-rose-200">
      <AlertCircle className="mt-0.5 h-[18px] w-[18px] shrink-0 text-rose-400" aria-hidden />
      <span>{message}</span>
    </div>
  ) : null;

/** Primary call to action. */
export const PrimaryButton: React.FC<React.ButtonHTMLAttributes<HTMLButtonElement> & { busy?: boolean }> = ({ busy, children, className, ...b }) => (
  <button
    {...b}
    disabled={busy || b.disabled}
    aria-busy={busy || undefined}
    className={`relative inline-flex h-11 w-full items-center justify-center gap-2 rounded-lg bg-cyan-400 px-4 text-[15px] font-semibold text-slate-950
      shadow-glow transition-[background-color,transform] duration-150 hover:bg-cyan-300 active:translate-y-px disabled:cursor-not-allowed disabled:opacity-60 ${className ?? ''}`}
  >
    {busy && <span className="h-4 w-4 animate-spin rounded-full border-2 border-slate-950/30 border-t-slate-950" aria-hidden />}
    {children}
  </button>
);

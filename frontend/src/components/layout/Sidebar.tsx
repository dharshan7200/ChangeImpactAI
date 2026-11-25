import React from 'react';
import { NavLink } from 'react-router-dom';
import { FolderGit2, Github, LayoutDashboard, Play, Plug, ScanSearch, type LucideIcon } from 'lucide-react';

export const NAV_ITEMS: { to: string; label: string; icon: LucideIcon; hint: string }[] = [
  { to: '/', label: 'Overview', icon: LayoutDashboard, hint: 'Risk, pull requests and findings' },
  { to: '/repositories', label: 'Repositories', icon: FolderGit2, hint: 'Connect, index and analyse' },
  { to: '/github', label: 'GitHub', icon: Github, hint: 'Accounts and the GitHub App' },
];

const STEPS: { icon: LucideIcon; text: string }[] = [
  { icon: Plug, text: 'Connect a repository' },
  { icon: Play, text: 'Index it (all languages)' },
  { icon: ScanSearch, text: 'Analyse a change or pull request' },
];

const LANGUAGES = ['Java', 'Python', 'JS/TS', 'React', 'PHP', 'C#', 'Go', 'Ruby', 'HTML', 'CSS', 'SQL'];

/** Horizontal navigation shown instead of the sidebar on narrow screens. */
export const MobileNav: React.FC = () => (
  <nav className="md:hidden sticky top-16 z-20 flex gap-1 overflow-x-auto border-b border-slate-800/80 bg-slate-950/90 px-3 py-2 backdrop-blur" aria-label="Main">
    {NAV_ITEMS.map(({ to, label, icon: Icon }) => (
      <NavLink key={to} to={to} end={to === '/'}
               className={({ isActive }) => `flex min-h-[40px] items-center gap-2 whitespace-nowrap rounded-lg px-3 text-sm font-medium transition-colors ${
                 isActive ? 'bg-cyan-400/10 text-cyan-200' : 'text-slate-400 hover:text-slate-100'}`}>
        <Icon className="h-4 w-4" aria-hidden /> {label}
      </NavLink>
    ))}
  </nav>
);

export const Sidebar: React.FC = () => (
  <aside className="hidden md:flex w-64 shrink-0 flex-col justify-between border-r border-slate-800/80 bg-slate-950/40 px-3 py-5">
    <div className="space-y-8">
      <div>
        <p className="px-3 pb-2 text-[11px] font-semibold uppercase tracking-[0.14em] text-slate-500">Workspace</p>
        <nav className="space-y-0.5" aria-label="Main">
          {NAV_ITEMS.map(({ to, label, icon: Icon, hint }) => (
            <NavLink
              key={to}
              to={to}
              end={to === '/'}
              title={hint}
              className={({ isActive }) =>
                `group relative flex min-h-[44px] items-center gap-3 rounded-lg px-3 text-sm font-medium transition-colors ${
                  isActive ? 'bg-slate-800/70 text-white' : 'text-slate-400 hover:bg-slate-900 hover:text-slate-100'
                }`
              }
            >
              {({ isActive }) => (
                <>
                  <span className={`absolute left-0 top-2.5 bottom-2.5 w-[3px] rounded-r-full transition-colors ${isActive ? 'bg-cyan-400' : 'bg-transparent'}`} aria-hidden />
                  <Icon className={`h-[18px] w-[18px] ${isActive ? 'text-cyan-300' : 'text-slate-500 group-hover:text-slate-300'}`} aria-hidden />
                  <span>{label}</span>
                </>
              )}
            </NavLink>
          ))}
        </nav>
      </div>

      <div className="px-3">
        <p className="pb-3 text-[11px] font-semibold uppercase tracking-[0.14em] text-slate-500">Getting started</p>
        <ol className="relative space-y-3 border-l border-slate-800 pl-4">
          {STEPS.map(({ icon: Icon, text }, i) => (
            <li key={text} className="relative flex items-start gap-2.5 text-[13px] leading-5 text-slate-300">
              <span className="absolute -left-[25px] top-px flex h-[18px] w-[18px] items-center justify-center rounded-full border border-slate-700 bg-slate-950 font-mono text-[10px] text-slate-400">
                {i + 1}
              </span>
              <Icon className="mt-0.5 h-4 w-4 shrink-0 text-cyan-300/80" aria-hidden />
              {text}
            </li>
          ))}
        </ol>
      </div>
    </div>

    <div className="space-y-3 px-3">
      <div>
        <p className="pb-2 text-[11px] font-semibold uppercase tracking-[0.14em] text-slate-500">Languages</p>
        <div className="flex flex-wrap gap-1">
          {LANGUAGES.map((l) => (
            <span key={l} className="rounded border border-slate-800 bg-slate-900/60 px-1.5 py-0.5 font-mono text-[10px] text-slate-400">{l}</span>
          ))}
        </div>
      </div>
      <p className="border-t border-slate-800/80 pt-3 font-mono text-[11px] text-slate-500">ChangeImpact AI · v0.1.0</p>
    </div>
  </aside>
);

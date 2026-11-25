import React from 'react';
import { Link } from 'react-router-dom';
import { LogOut } from 'lucide-react';
import { useAuth } from '../../context/AuthContext';
import { Logo } from '../brand/Logo';

function initials(name: string | undefined): string {
  const parts = (name ?? '').trim().split(/[\s._-]+/).filter(Boolean);
  return ((parts[0]?.[0] ?? '?') + (parts[1]?.[0] ?? '')).toUpperCase();
}

export const Navbar: React.FC = () => {
  const { user, logout } = useAuth();
  const name = user?.displayName || user?.username;

  return (
    <header className="sticky top-0 z-30 h-16 shrink-0 border-b border-slate-800/80 bg-slate-950/80 backdrop-blur-md px-4 md:px-6 flex items-center justify-between">
      <Link to="/" className="rounded-lg" aria-label="ChangeImpact AI — overview">
        <Logo size={32} />
      </Link>

      <div className="flex items-center gap-2 sm:gap-3">
        <div className="flex items-center gap-3 rounded-full border border-slate-800 bg-slate-900/70 py-1 pl-1 pr-1 sm:pr-4">
          <span className="flex h-8 w-8 items-center justify-center rounded-full bg-gradient-to-br from-cyan-400 to-blue-500 font-display text-xs font-semibold text-slate-950" aria-hidden>
            {initials(name)}
          </span>
          <span className="hidden sm:block text-left leading-tight">
            <span className="block max-w-[180px] truncate text-sm font-medium text-slate-100">{name}</span>
            <span className="block max-w-[180px] truncate text-xs text-slate-400">{user?.email}</span>
          </span>
        </div>
        <button
          type="button"
          onClick={logout}
          aria-label="Sign out"
          title="Sign out"
          className="flex h-10 w-10 items-center justify-center rounded-lg text-slate-400 transition-colors hover:bg-slate-800/80 hover:text-rose-300"
        >
          <LogOut className="h-[18px] w-[18px]" aria-hidden />
        </button>
      </div>
    </header>
  );
};

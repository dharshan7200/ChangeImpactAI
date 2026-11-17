import React from 'react';
import { useAuth } from '../../context/AuthContext';
import { LogOut, User, ShieldCheck } from 'lucide-react';

export const Navbar: React.FC = () => {
  const { user, logout } = useAuth();

  return (
    <header className="h-16 bg-slate-900 border-b border-slate-800 px-6 flex items-center justify-between z-10">
      <div className="flex items-center space-x-3">
        <div className="w-8 h-8 rounded-lg bg-gradient-to-br from-cyan-500 to-blue-600 flex items-center justify-center font-bold text-white shadow-lg shadow-cyan-500/20">
          Δ
        </div>
        <div>
          <span className="font-extrabold text-lg text-white tracking-tight">ChangeImpact</span>
          <span className="text-cyan-400 font-bold ml-1 text-sm bg-cyan-950/60 px-1.5 py-0.5 rounded border border-cyan-800">AI</span>
        </div>
      </div>

      <div className="flex items-center space-x-4">
        <div className="flex items-center space-x-2 text-xs bg-slate-800 px-2.5 py-1.5 rounded-full border border-slate-700 text-slate-300">
          <ShieldCheck className="w-4 h-4 text-emerald-400" />
          <span>Spring Boot 3.5 &bull; Java 17</span>
        </div>

        <div className="flex items-center space-x-3 pl-2 border-l border-slate-800">
          <div className="flex items-center space-x-2 text-sm">
            <div className="w-8 h-8 rounded-full bg-slate-800 flex items-center justify-center text-slate-300 border border-slate-700">
              <User className="w-4 h-4" />
            </div>
            <div className="hidden md:block text-left">
              <p className="text-sm font-medium text-slate-200 leading-none">{user?.displayName || user?.username}</p>
              <p className="text-xs text-slate-400 leading-tight mt-0.5">{user?.email}</p>
            </div>
          </div>

          <button
            onClick={logout}
            title="Log Out"
            className="p-2 text-slate-400 hover:text-rose-400 hover:bg-slate-800 rounded-lg transition"
          >
            <LogOut className="w-4 h-4" />
          </button>
        </div>
      </div>
    </header>
  );
};


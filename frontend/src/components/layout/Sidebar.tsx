import React from 'react';
import { NavLink } from 'react-router-dom';
import { LayoutDashboard, FolderGit2, Github, Activity, Terminal } from 'lucide-react';

export const Sidebar: React.FC = () => {
  const navItems = [
    { to: '/', label: 'Overview', icon: LayoutDashboard },
    { to: '/repositories', label: 'Repositories', icon: FolderGit2 },
    { to: '/github', label: 'GitHub Accounts', icon: Github },
  ];

  return (
    <aside className="w-64 bg-slate-900 border-r border-slate-800 flex flex-col justify-between p-4">
      <div className="space-y-6">
        <div>
          <p className="px-3 text-xs font-semibold uppercase tracking-wider text-slate-500 mb-2">
            Navigation
          </p>
          <nav className="space-y-1">
            {navItems.map((item) => {
              const Icon = item.icon;
              return (
                <NavLink
                  key={item.to}
                  to={item.to}
                  end={item.to === '/'}
                  className={({ isActive }) =>
                    `flex items-center space-x-3 px-3 py-2.5 rounded-lg text-sm font-medium transition ${
                      isActive
                        ? 'bg-cyan-500/10 text-cyan-400 border border-cyan-500/20'
                        : 'text-slate-400 hover:text-slate-200 hover:bg-slate-800/60'
                    }`
                  }
                >
                  <Icon className="w-4 h-4" />
                  <span>{item.label}</span>
                </NavLink>
              );
            })}
          </nav>
        </div>

        <div>
          <p className="px-3 text-xs font-semibold uppercase tracking-wider text-slate-500 mb-2">
            Platform Engine
          </p>
          <div className="bg-slate-950/60 rounded-lg p-3 border border-slate-800/80 text-xs space-y-2">
            <div className="flex items-center justify-between text-slate-400">
              <span className="flex items-center space-x-1.5">
                <Activity className="w-3.5 h-3.5 text-emerald-400" />
                <span>RabbitMQ Ingestion</span>
              </span>
              <span className="text-emerald-400 font-mono text-[10px] bg-emerald-950/50 px-1 py-0.5 rounded border border-emerald-800/50">ACTIVE</span>
            </div>
            <div className="flex items-center justify-between text-slate-400">
              <span className="flex items-center space-x-1.5">
                <Terminal className="w-3.5 h-3.5 text-cyan-400" />
                <span>JGit Sandbox</span>
              </span>
              <span className="text-cyan-400 font-mono text-[10px] bg-cyan-950/50 px-1 py-0.5 rounded border border-cyan-800/50">READY</span>
            </div>
          </div>
        </div>
      </div>

      <div className="pt-4 border-t border-slate-800 text-xs text-slate-500 text-center font-mono">
        ChangeImpact AI &bull; v0.1.0 (Phase 1)
      </div>
    </aside>
  );
};


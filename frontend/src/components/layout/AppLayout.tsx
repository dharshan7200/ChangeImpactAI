import React from 'react';
import { Outlet, useLocation } from 'react-router-dom';
import { Navbar } from './Navbar';
import { MobileNav, Sidebar } from './Sidebar';

export const AppLayout: React.FC = () => {
  const { pathname } = useLocation();
  return (
    <div className="min-h-screen flex flex-col">
      <a href="#main" className="sr-only focus:not-sr-only focus:fixed focus:left-4 focus:top-3 focus:z-50 focus:rounded-lg focus:bg-cyan-400 focus:px-3 focus:py-2 focus:text-sm focus:font-semibold focus:text-slate-950">
        Skip to content
      </a>
      <Navbar />
      <MobileNav />
      <div className="flex flex-1 min-h-0">
        <Sidebar />
        <main id="main" className="flex-1 min-w-0 px-4 py-6 md:px-8 md:py-8">
          {/* keyed by path so each page enters with the same short fade */}
          <div key={pathname} className="mx-auto max-w-7xl animate-fade-up">
            <Outlet />
          </div>
        </main>
      </div>
    </div>
  );
};

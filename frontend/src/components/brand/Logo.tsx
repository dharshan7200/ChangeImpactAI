import React, { useId } from 'react';

/**
 * The ChangeImpact mark: a delta (Δ, "change") drawn as a dependency graph. The solid apex is the changed code, the
 * ring its impact, the two nodes the dependents it reaches — what the platform computes for every change.
 */
export const LogoMark: React.FC<{ size?: number; className?: string; title?: string }> = ({ size = 32, className, title }) => {
  const id = useId().replace(/:/g, '');
  return (
    <svg width={size} height={size} viewBox="0 0 40 40" className={className} role={title ? 'img' : undefined}
         aria-hidden={title ? undefined : true} aria-label={title}>
      <defs>
        <linearGradient id={`${id}t`} x1="0" y1="0" x2="1" y2="1">
          <stop offset="0" stopColor="#16202d" />
          <stop offset="1" stopColor="#0b0f16" />
        </linearGradient>
        <linearGradient id={`${id}s`} x1="0" y1="0" x2="1" y2="1">
          <stop offset="0" stopColor="#28d5ec" />
          <stop offset="1" stopColor="#7aa7ff" />
        </linearGradient>
        <linearGradient id={`${id}f`} x1="0" y1="0" x2="0" y2="1">
          <stop offset="0" stopColor="#28d5ec" stopOpacity=".22" />
          <stop offset="1" stopColor="#28d5ec" stopOpacity="0" />
        </linearGradient>
      </defs>
      <rect x="1" y="1" width="38" height="38" rx="10" fill={`url(#${id}t)`} stroke="#28d5ec" strokeOpacity=".35" />
      <path d="M20 11.5 11 28.5H29Z" fill={`url(#${id}f)`} stroke={`url(#${id}s)`} strokeWidth="2.2" strokeLinejoin="round" />
      <circle cx="20" cy="11.5" r="6.4" fill="none" stroke="#28d5ec" strokeOpacity=".45" strokeWidth="1.3" />
      <circle cx="11" cy="28.5" r="2.9" fill="#e6ecf3" />
      <circle cx="29" cy="28.5" r="2.9" fill="#e6ecf3" />
      <circle cx="20" cy="11.5" r="3.7" fill={`url(#${id}s)`} />
    </svg>
  );
};

/** Mark + wordmark. */
export const Logo: React.FC<{ size?: number; className?: string; subtitle?: string }> = ({ size = 32, className = '', subtitle }) => (
  <span className={`inline-flex items-center gap-2.5 ${className}`}>
    <LogoMark size={size} />
    <span className="leading-none">
      <span className="font-display font-semibold tracking-tight text-white" style={{ fontSize: size * 0.53 }}>
        Change<span className="text-cyan-300">Impact</span>
      </span>
      <span className="ml-1.5 align-[2px] font-mono text-[10px] font-semibold tracking-wider text-cyan-200/90 border border-cyan-400/30 bg-cyan-400/10 rounded px-1 py-[1px]">
        AI
      </span>
      {subtitle && <span className="block mt-1 text-[11px] font-normal text-slate-400 tracking-normal">{subtitle}</span>}
    </span>
  </span>
);

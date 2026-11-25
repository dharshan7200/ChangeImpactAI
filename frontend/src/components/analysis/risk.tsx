import React from 'react';
import { AlertOctagon, AlertTriangle, CheckCircle2, ShieldAlert } from 'lucide-react';
import type { RiskLevel } from '../../types';

/**
 * Reserved status palette (dataviz reference): good / warning / serious / critical.
 * Never reused for anything but risk level, and always shown with an icon + text label.
 */
export const RISK_COLORS: Record<RiskLevel, string> = {
  LOW: '#0ca30c',
  MEDIUM: '#fab219',
  HIGH: '#ec835a',
  CRITICAL: '#d03b3b',
};

export const RISK_LEVELS: RiskLevel[] = ['LOW', 'MEDIUM', 'HIGH', 'CRITICAL'];

const ICONS: Record<RiskLevel, React.ElementType> = {
  LOW: CheckCircle2,
  MEDIUM: AlertTriangle,
  HIGH: ShieldAlert,
  CRITICAL: AlertOctagon,
};

export const RiskBadge: React.FC<{ level?: string | null; score?: number | null; size?: 'sm' | 'lg' }> = ({
  level,
  score,
  size = 'sm',
}) => {
  if (!level) return <span className="text-slate-500 text-xs">—</span>;
  const l = level as RiskLevel;
  const Icon = ICONS[l] ?? AlertTriangle;
  const color = RISK_COLORS[l] ?? '#94a3b8';
  const big = size === 'lg';
  return (
    <span
      className={`inline-flex items-center gap-1.5 rounded-lg font-bold border ${big ? 'px-3 py-1.5 text-sm' : 'px-2 py-0.5 text-[10px]'}`}
      style={{ color, borderColor: `${color}66`, background: `${color}14` }}
      title="Heuristic risk score (0–100), not a probability"
    >
      <Icon className={big ? 'w-4 h-4' : 'w-3 h-3'} />
      <span>{l}</span>
      {score !== undefined && score !== null && <span className="font-mono text-slate-200">{Number(score).toFixed(1)}</span>}
    </span>
  );
};

/** Human labels for semantic change kinds; contract-breaking kinds are flagged in the UI. */
export const KIND_LABELS: Record<string, string> = {
  ADDED: 'Added',
  REMOVED: 'Removed',
  MOVED: 'Moved / renamed',
  PARAMETERS_CHANGED: 'Parameters changed',
  PARAMETERS_EXTENDED: 'Optional parameters added',
  PARAMETER_ANNOTATIONS_CHANGED: 'Parameter type hints changed',
  RETURN_TYPE_CHANGED: 'Return type changed',
  FIELD_TYPE_CHANGED: 'Field type changed',
  VISIBILITY_REDUCED: 'Visibility reduced',
  VISIBILITY_EXPANDED: 'Visibility expanded',
  MODIFIERS_CHANGED: 'Modifiers changed',
  TYPE_HIERARCHY_CHANGED: 'Hierarchy changed',
  DECLARATION_CHANGED: 'Declaration changed',
  REST_MAPPING_CHANGED: 'REST mapping changed',
  SECURITY_RULE_CHANGED: 'Security rule changed',
  ENTITY_MAPPING_CHANGED: 'Entity mapping changed',
  TRANSACTION_BOUNDARY_CHANGED: 'Transaction boundary',
  CONFIG_BINDING_CHANGED: 'Config binding changed',
  CONFIGURATION_VALUE_CHANGED: 'Config value changed',
  ANNOTATIONS_CHANGED: 'Annotations changed',
  BODY_CHANGED: 'Body changed',
};

export const KindChip: React.FC<{ kind: string }> = ({ kind }) => (
  <span className="inline-block text-[10px] px-1.5 py-0.5 rounded-md bg-slate-800 border border-slate-700 text-slate-200 whitespace-nowrap">
    {KIND_LABELS[kind] ?? kind}
  </span>
);

export function shortSha(sha?: string | null): string {
  return sha ? sha.substring(0, 7) : '—';
}

export function shortName(qualifiedName: string): string {
  const member = qualifiedName.indexOf('#');
  const type = member >= 0 ? qualifiedName.substring(0, member) : qualifiedName;
  const simpleType = type.substring(type.lastIndexOf('.') + 1);
  return member >= 0 ? `${simpleType}#${qualifiedName.substring(member + 1)}` : simpleType;
}

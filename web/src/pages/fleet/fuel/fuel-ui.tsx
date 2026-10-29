import type { ReactNode } from 'react';
import { StatusPill, type Tone } from '@/components/list';
import {
  ANOMALY_LABEL, ANOMALY_STATUS_LABEL, CARD_STATUS_LABEL, DIP_STATUS_LABEL, LOG_STATUS_LABEL,
  type AnomalyStatus, type AnomalyType, type CardStatus, type DipStatus, type FuelLogStatus,
} from '@/features/fleet/fuel-api';

/** Shared bits of the fuel screens (fleet 1.5): status pills, anomaly chips, stat tiles, efficiency text. */

const LOG_TONE: Record<FuelLogStatus, Tone> = { draft: 'warning', posted: 'success', reversed: 'neutral' };

export function FuelLogStatusPill({ status }: { status: FuelLogStatus }) {
  return <StatusPill tone={LOG_TONE[status] ?? 'neutral'}>{LOG_STATUS_LABEL[status] ?? status}</StatusPill>;
}

const ANOMALY_TONE: Record<AnomalyStatus, Tone> = { open: 'danger', acknowledged: 'warning', resolved: 'neutral' };

export function AnomalyStatusPill({ status }: { status: AnomalyStatus }) {
  return <StatusPill tone={ANOMALY_TONE[status] ?? 'neutral'}>{ANOMALY_STATUS_LABEL[status] ?? status}</StatusPill>;
}

export function AnomalyChips({ types }: { types: AnomalyType[] }) {
  if (!types.length) return <span className="text-muted-foreground">—</span>;
  return (
    <div className="flex flex-wrap gap-1">
      {types.map((t) => <StatusPill key={t} tone="danger" dot={false}>{ANOMALY_LABEL[t] ?? t}</StatusPill>)}
    </div>
  );
}

const CARD_TONE: Record<CardStatus, Tone> = { active: 'success', blocked: 'danger', expired: 'warning', cancelled: 'neutral' };

export function CardStatusPill({ status }: { status: CardStatus }) {
  return <StatusPill tone={CARD_TONE[status] ?? 'neutral'}>{CARD_STATUS_LABEL[status] ?? status}</StatusPill>;
}

const DIP_TONE: Record<DipStatus, Tone> = {
  open: 'info', accepted: 'success', pending_approval: 'warning', adjusted: 'success', rejected: 'neutral', failed: 'danger',
};

export function DipStatusPill({ status }: { status: DipStatus }) {
  return <StatusPill tone={DIP_TONE[status] ?? 'neutral'}>{DIP_STATUS_LABEL[status] ?? status}</StatusPill>;
}

export function StatTile({ label, value, hint, tone }: { label: string; value: ReactNode; hint?: ReactNode; tone?: 'danger' | 'warning' }) {
  const color = tone === 'danger' ? 'text-rose-600' : tone === 'warning' ? 'text-amber-600' : '';
  return (
    <div className="rounded-xl border bg-card p-3 shadow-sm">
      <div className="text-xs text-muted-foreground">{label}</div>
      <div className={`text-xl font-semibold tabular-nums ${color}`}>{value}</div>
      {hint && <div className="text-xs text-muted-foreground">{hint}</div>}
    </div>
  );
}

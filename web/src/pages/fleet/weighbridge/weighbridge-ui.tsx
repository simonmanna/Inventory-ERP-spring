import type { ReactNode } from 'react';
import { StatusPill, type Tone } from '@/components/list';
import { tx } from '@/lib/i18n/tx';
import { TICKET_STATUS_LABEL, type TicketStatus } from '@/features/fleet/weighbridge-api';

/** Shared bits of the weighbridge screens (fleet 1.4): status pill, kg formatting, the big weight display. */

const TONE: Record<TicketStatus, Tone> = {
  open: 'info',
  first_weighed: 'warning',
  exception: 'danger',
  completed: 'success',
  cancelled: 'neutral',
  void: 'neutral',
};

export function TicketStatusPill({ status }: { status: TicketStatus }) {
  return <StatusPill tone={TONE[status] ?? 'neutral'}>{TICKET_STATUS_LABEL[status] ?? status}</StatusPill>;
}

export function ManualFlag() {
  return <StatusPill tone="accent" dot={false}>{tx('Manual')}</StatusPill>;
}

/** Large tablet-friendly weight read-out. */
export function WeightDisplay({ value, caption, tone }: { value: ReactNode; caption?: ReactNode; tone?: 'stable' | 'error' | 'idle' }) {
  const color = tone === 'stable' ? 'text-emerald-600' : tone === 'error' ? 'text-rose-600' : 'text-foreground';
  return (
    <div className="rounded-2xl border bg-card p-4 text-center shadow-sm">
      <div className={`font-mono text-5xl font-bold tabular-nums sm:text-6xl ${color}`}>{value}</div>
      {caption && <div className="mt-1 text-sm text-muted-foreground">{caption}</div>}
    </div>
  );
}

export function Stat({ label, value, onClick, tone }: { label: string; value: ReactNode; onClick?: () => void; tone?: 'danger' | 'warning' }) {
  const color = tone === 'danger' ? 'text-rose-600' : tone === 'warning' ? 'text-amber-600' : '';
  return (
    <button type="button" onClick={onClick} className="rounded-xl border bg-card p-3 text-left shadow-sm hover:bg-accent">
      <div className="text-xs text-muted-foreground">{label}</div>
      <div className={`text-2xl font-semibold tabular-nums ${color}`}>{value}</div>
    </button>
  );
}

export function Row({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="flex justify-between gap-3 border-b py-1.5 text-sm last:border-0">
      <span className="text-muted-foreground">{label}</span>
      <span className="text-right font-medium">{children}</span>
    </div>
  );
}

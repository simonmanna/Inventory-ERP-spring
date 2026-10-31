import type { ReactNode } from 'react';
import { StatusPill, type Tone } from '@/components/list';
import { cn } from '@/lib/utils';
import { tx } from '@/lib/i18n/tx';
import {
  DOWNTIME_STATUS_LABEL, DRAFT_STATUS_LABEL, FLOOR_STATUS_LABEL, REASON_CATEGORY_LABEL, SHIFT_REPORT_STATUS_LABEL, pct,
  type DowntimeReason, type DowntimeStatus, type DraftStatus, type FloorStatus, type Oee, type ShiftReportStatus,
} from '@/features/manufacturing/api-shopfloor';

/** Shared bits of the shop-floor screens (ADR-S015): pills, OEE tiles, the touch reason grid. */

const FLOOR_TONE: Record<FloorStatus, Tone> = { running: 'success', paused: 'warning', down: 'danger', idle: 'neutral' };
const REPORT_TONE: Record<ShiftReportStatus, Tone> = { open: 'info', submitted: 'warning', approved: 'success' };
const DRAFT_TONE: Record<DraftStatus, Tone> = { draft: 'warning', posted: 'success', rejected: 'neutral' };
const DOWN_TONE: Record<DowntimeStatus, Tone> = { draft: 'warning', confirmed: 'info', rejected: 'neutral' };

export const FloorStatusPill = ({ status }: { status: FloorStatus }) =>
  <StatusPill tone={FLOOR_TONE[status] ?? 'neutral'}>{FLOOR_STATUS_LABEL[status] ?? status}</StatusPill>;
export const ShiftReportStatusPill = ({ status }: { status: ShiftReportStatus }) =>
  <StatusPill tone={REPORT_TONE[status] ?? 'neutral'}>{SHIFT_REPORT_STATUS_LABEL[status] ?? status}</StatusPill>;
export const DraftStatusPill = ({ status }: { status: DraftStatus }) =>
  <StatusPill tone={DRAFT_TONE[status] ?? 'neutral'}>{DRAFT_STATUS_LABEL[status] ?? status}</StatusPill>;
export const DowntimeStatusPill = ({ status }: { status: DowntimeStatus }) =>
  <StatusPill tone={DOWN_TONE[status] ?? 'neutral'}>{DOWNTIME_STATUS_LABEL[status] ?? status}</StatusPill>;

export const FLOOR_BORDER: Record<FloorStatus, string> = {
  running: 'border-l-emerald-500', paused: 'border-l-amber-500', down: 'border-l-red-500', idle: 'border-l-muted-foreground/30',
};

function Tile({ label, value, hint, strong }: { label: string; value: ReactNode; hint?: string; strong?: boolean }) {
  return (
    <div className={cn('rounded-lg border bg-card p-3', strong && 'border-primary/40 bg-primary/5')}>
      <div className="text-xs text-muted-foreground">{label}</div>
      <div className={cn('font-semibold tabular-nums', strong ? 'text-2xl' : 'text-xl')}>{value}</div>
      {hint && <div className="text-xs text-muted-foreground">{hint}</div>}
    </div>
  );
}

/** OEE = availability × performance × quality, with the minutes behind it. */
export function OeeTiles({ oee, compact }: { oee: Oee | null | undefined; compact?: boolean }) {
  if (!oee) return <p className="text-sm text-muted-foreground">{tx('No shift data yet.')}</p>;
  return (
    <div className={cn('grid gap-2', compact ? 'grid-cols-2 sm:grid-cols-4' : 'grid-cols-2 md:grid-cols-4')}>
      <Tile strong label={tx('OEE')} value={pct(oee.oee)} />
      <Tile label={tx('Availability')} value={pct(oee.availability)}
        hint={compact ? undefined : tx('{{run}} of {{planned}} min running', { run: oee.runMinutes, planned: oee.plannedMinutes })} />
      <Tile label={tx('Performance')} value={pct(oee.performance)}
        hint={compact ? undefined : oee.performance === null ? tx('Set an ideal rate') : oee.performanceCapped ? tx('Capped at 100 %') : undefined} />
      <Tile label={tx('Quality')} value={pct(oee.quality)}
        hint={compact ? undefined : tx('{{good}} good of {{total}}', { good: Number(oee.goodQty), total: Number(oee.totalQty) })} />
    </div>
  );
}

/** Big touch targets for picking a downtime reason, grouped by category. */
export function ReasonGrid({ reasons, value, onChange }: { reasons: DowntimeReason[]; value: string; onChange: (code: string) => void }) {
  if (!reasons.length) {
    return <p className="text-sm text-muted-foreground">{tx('No downtime reasons yet. Load the standard reasons in Shop-floor Setup.')}</p>;
  }
  return (
    <div className="grid grid-cols-2 gap-2 sm:grid-cols-3">
      {reasons.map((r) => (
        <button key={r.id} type="button" onClick={() => onChange(r.code)}
          className={cn('min-h-[64px] rounded-lg border p-3 text-left transition-colors hover:bg-muted',
            value === r.code && 'border-primary bg-primary/10 ring-2 ring-primary/40')}>
          <div className="font-medium leading-tight">{r.name}</div>
          <div className="text-xs text-muted-foreground">{REASON_CATEGORY_LABEL[r.category] ?? r.category}{r.isPlanned ? ` · ${tx('planned')}` : ''}</div>
        </button>
      ))}
    </div>
  );
}

/** Large numeric input for gloved fingers. */
export function BigNumber({ label, value, onChange, autoFocus }: { label: string; value: string; onChange: (v: string) => void; autoFocus?: boolean }) {
  return (
    <label className="block space-y-1">
      <span className="text-sm font-medium">{label}</span>
      <input inputMode="decimal" autoFocus={autoFocus} value={value}
        onChange={(e) => onChange(e.target.value.replace(',', '.'))}
        className="h-14 w-full rounded-lg border bg-background px-4 text-2xl font-semibold tabular-nums outline-none focus:ring-2 focus:ring-primary/50" />
    </label>
  );
}

export const isNum = (v: string) => v.trim() !== '' && Number.isFinite(Number(v)) && Number(v) >= 0;

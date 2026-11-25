import { tx } from '@/lib/i18n/tx';
import type { MfgTone } from './status';

/** Decimal strings from the API → number (0 when absent). */
export const n = (v: unknown): number => {
  const x = Number(v ?? 0);
  return Number.isFinite(x) ? x : 0;
};
export const fmtQty = (v: unknown, digits = 3) => n(v).toLocaleString(undefined, { maximumFractionDigits: digits });
export const fmtMoney = (v: unknown) => n(v).toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 });
export const fmtPct = (v: number | null | undefined, digits = 0) =>
  v == null || !Number.isFinite(v) ? '—' : `${v.toLocaleString(undefined, { maximumFractionDigits: digits })}%`;

/** Local yyyy-mm-dd. */
export const ymdLocal = (d: Date = new Date()) =>
  `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;

const OPEN = new Set(['draft', 'confirmed', 'in_progress', 'qc_hold']);

export type DueState = 'late' | 'today' | 'soon' | 'upcoming' | 'none';

/** Where an open order stands against its scheduled date (closed orders and undated ones: 'none'). */
export function dueState(scheduledFor: string | null | undefined, status: string, today = ymdLocal()): DueState {
  if (!scheduledFor || !OPEN.has(status)) return 'none';
  const d = scheduledFor.slice(0, 10);
  if (d < today) return 'late';
  if (d === today) return 'today';
  const days = (new Date(`${d}T00:00:00`).getTime() - new Date(`${today}T00:00:00`).getTime()) / 86_400_000;
  return days <= 3 ? 'soon' : 'upcoming';
}

export const DUE_TONE: Record<DueState, MfgTone> = { late: 'danger', today: 'warning', soon: 'info', upcoming: 'neutral', none: 'muted' };
export const dueLabel = (s: DueState) =>
  ({ late: tx('Late'), today: tx('Due today'), soon: tx('Due soon'), upcoming: tx('Upcoming'), none: '' })[s];

/** Produced / planned as a percentage (null when nothing planned). */
export const progressPct = (produced: unknown, planned: unknown): number | null => {
  const p = n(planned);
  return p > 0 ? (n(produced) / p) * 100 : null;
};

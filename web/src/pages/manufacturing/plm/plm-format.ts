import { num, type Num } from '@/features/manufacturing/api-plm';

/** Number formatting for the PLM application screens (ADR-S086). */

/** A number with sign, for deltas. */
export function signed(v: Num | null | undefined, digits = 2): string {
  if (v == null || v === '') return '—';
  const n = num(v);
  const s = n.toLocaleString(undefined, { minimumFractionDigits: digits, maximumFractionDigits: digits });
  return n > 0 ? `+${s}` : s;
}

export function plain(v: Num | null | undefined, digits = 2): string {
  if (v == null || v === '') return '—';
  return num(v).toLocaleString(undefined, { minimumFractionDigits: 0, maximumFractionDigits: digits });
}

/** Render a master-data value (blank / null shows a dash). */
export function fieldValue(v: unknown): string {
  if (v == null || v === '') return '—';
  if (typeof v === 'object') return JSON.stringify(v);
  return String(v);
}

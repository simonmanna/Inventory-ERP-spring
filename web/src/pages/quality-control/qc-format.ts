/** Formatting helpers of the quality-control screens (ADR-S087). */

export { toOptions, num } from '../fleet/fleet-format';

/** "4 – 6 %" style tolerance text. */
export function tolerance(min: unknown, max: unknown, unit?: string | null): string {
  const u = unit ? ` ${unit}` : '';
  const has = (v: unknown) => v !== null && v !== undefined && v !== '';
  if (has(min) && has(max)) return `${min} – ${max}${u}`;
  if (has(min)) return `≥ ${min}${u}`;
  if (has(max)) return `≤ ${max}${u}`;
  return '—';
}

/** Number or undefined from a text input ("" → undefined). */
export const optNum = (v: string | number | null | undefined): number | undefined =>
  v === null || v === undefined || String(v).trim() === '' ? undefined : Number(v);

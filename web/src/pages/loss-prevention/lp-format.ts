/** Pure formatting helpers of the loss-prevention report screens (Insights, Research). No React, no DOM. */

export type Num = number | string | null | undefined;

/** A number from the API (a decimal may arrive as a string) in the browser's locale, or `—` when there is none. */
export const num = (v: Num, digits = 2) =>
  v === null || v === undefined || v === '' ? '—' : Number(v).toLocaleString(undefined, { maximumFractionDigits: digits });

/** A fraction from 0 to 1 as a percentage with at most one decimal, or `—`. */
export const pct = (v: Num) =>
  v === null || v === undefined || v === '' ? '—' : `${(Number(v) * 100).toLocaleString(undefined, { maximumFractionDigits: 1 })}%`;

/** Seconds as m:ss (or h:mm:ss). */
export const dur = (v: Num) => {
  if (v === null || v === undefined || v === '') return '—';
  const s = Math.round(Number(v));
  const h = Math.floor(s / 3600);
  const m = Math.floor((s % 3600) / 60);
  const ss = String(s % 60).padStart(2, '0');
  return h > 0 ? `${h}:${String(m).padStart(2, '0')}:${ss}` : `${m}:${ss}`;
};

/** An hour of the day (0 to 23) as `hh:00`. */
export const hourText = (h: number) => `${String(h).padStart(2, '0')}:00`;

/** Distinct values for a filter, with the chosen one kept even when this page no longer shows it. */
export function options(values: (string | null | undefined)[], chosen: string, label: (v: string) => string = (v) => v) {
  const set = new Set(values.filter((v): v is string => !!v));
  if (chosen) set.add(chosen);
  return [...set].sort().map((v) => ({ value: v, label: label(v) }));
}

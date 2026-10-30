/** Pure helpers of the precast screens (2.4): cube totals, curing progress, number parsing. */

export { num, toOptions } from '../../fleet/fleet-format';
export { optNum, today } from '../../quality/quality-format';

type Num = string | number | null | undefined;

/** Finite number or 0 (decimals arrive as strings). */
export const n = (v: Num): number => {
  const x = Number(v ?? 0);
  return Number.isFinite(x) ? x : 0;
};

/** Cube split check: good + second grade + broken must equal the batch quantity. */
export function cubeTotal(quantity: Num, good: Num, second: Num, broken: Num): { total: number; expected: number; remaining: number; ok: boolean } {
  const expected = n(quantity);
  const total = n(good) + n(second) + n(broken);
  const remaining = Math.round((expected - total) * 1e6) / 1e6;
  return { total, expected, remaining, ok: Math.abs(remaining) < 1e-9 && n(good) >= 0 && n(second) >= 0 && n(broken) >= 0 };
}

/** Elapsed share (0–100) of the curing period from startedAt to curingUntil, at `now`. */
export function curingProgressPct(startedAt: string | null | undefined, curingUntil: string | null | undefined, now: number = Date.now()): number {
  if (!startedAt || !curingUntil) return 0;
  const start = new Date(startedAt).getTime();
  const end = new Date(curingUntil).getTime();
  if (!Number.isFinite(start) || !Number.isFinite(end) || end <= start) return 100;
  return Math.max(0, Math.min(100, ((now - start) / (end - start)) * 100));
}

/** "4.2 / 7" days cured against the minimum. */
export const daysLabel = (daysCured: Num, minDays: Num) =>
  `${n(daysCured).toLocaleString(undefined, { maximumFractionDigits: 1 })} / ${minDays == null || minDays === '' ? '—' : n(minDays)}`;

/** Percentage text, "—" when absent. */
export const pct = (v: Num, digits = 1) =>
  v === null || v === undefined || v === '' ? '—' : `${n(v).toLocaleString(undefined, { maximumFractionDigits: digits })}%`;

/** Positive-number check for a text input. */
export const positive = (v: string) => v.trim() !== '' && Number.isFinite(Number(v)) && Number(v) > 0;
/** Empty or a non-negative number. */
export const nonNegativeOrEmpty = (v: string) => v.trim() === '' || (Number.isFinite(Number(v)) && Number(v) >= 0);

/** "390 × 190 × 190" block dimensions in mm, "—" when none is set. */
export const dims = (s: { lengthMm: Num; widthMm: Num; heightMm: Num }) =>
  [s.lengthMm, s.widthMm, s.heightMm].some((v) => v != null && v !== '')
    ? [s.lengthMm, s.widthMm, s.heightMm].map((v) => (v == null || v === '' ? '?' : n(v).toLocaleString(undefined, { maximumFractionDigits: 0 }))).join(' × ')
    : '—';

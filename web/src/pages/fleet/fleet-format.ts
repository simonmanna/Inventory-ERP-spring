/** Formatting helpers of the fleet screens (ADR-S009). */

export const toOptions = (m: Record<string, string>) => Object.entries(m).map(([value, label]) => ({ value, label }));

/** "12,500.5" for meters and quantities; "—" when absent. */
export function num(value: string | number | null | undefined, digits = 1): string {
  if (value === null || value === undefined || value === '') return '—';
  const n = Number(value);
  return Number.isFinite(n) ? n.toLocaleString(undefined, { maximumFractionDigits: digits }) : String(value);
}

/** Local datetime-input value ("2026-10-29T08:30") for an ISO instant, and back. */
export const toLocalInput = (iso: Date) => {
  const d = new Date(iso.getTime() - iso.getTimezoneOffset() * 60_000);
  return d.toISOString().slice(0, 16);
};
export const fromLocalInput = (v: string) => (v ? new Date(v).toISOString() : undefined);

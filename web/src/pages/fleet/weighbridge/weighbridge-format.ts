/** Formatting helpers of the weighbridge screens (fleet 1.4). */

/** "32,150 kg"; "—" when absent. */
export function kg(value: string | number | null | undefined): string {
  if (value === null || value === undefined || value === '') return '—';
  const n = Number(value);
  return Number.isFinite(n) ? `${n.toLocaleString(undefined, { maximumFractionDigits: 2 })} kg` : String(value);
}

export function qty(value: string | number | null | undefined, unit?: string | null): string {
  if (value === null || value === undefined || value === '') return '—';
  const n = Number(value);
  return `${Number.isFinite(n) ? n.toLocaleString(undefined, { maximumFractionDigits: 4 }) : value}${unit ? ` ${unit}` : ''}`;
}

import { num } from '../fleet-format';

/** Formatting helpers of the fuel screens (fleet 1.5). */

/** "32.5 L/100 km" / "14.2 L/h" / "—". */
export function efficiencyText(lPer100Km: string | null | undefined, lPerHour: string | null | undefined): string {
  const parts: string[] = [];
  if (lPer100Km) parts.push(`${num(lPer100Km, 1)} L/100 km`);
  if (lPerHour) parts.push(`${num(lPerHour, 1)} L/h`);
  return parts.length ? parts.join(' · ') : '—';
}

/** "1,250 km · 3,400 h" from a log's meters. */
export function meterText(odometer: string | null | undefined, hours: string | null | undefined): string {
  return [odometer ? `${num(odometer, 0)} km` : null, hours ? `${num(hours, 1)} h` : null].filter(Boolean).join(' · ') || '—';
}

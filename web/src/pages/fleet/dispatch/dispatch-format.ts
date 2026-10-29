import { tx } from '@/lib/i18n/tx';

/** Formatting / date helpers of the dispatch screens (fleet 1.3). */

/** Truck / transporter label of a trip. */
export function carrierText(t: {
  carrier: 'own' | 'transporter'; vehicleCode: string | null; vehiclePlate: string | null; driverName: string | null;
  transporterName: string | null; transporterPlate: string | null;
}): string {
  if (t.carrier === 'transporter') {
    return t.transporterName ? [t.transporterName, t.transporterPlate].filter(Boolean).join(' · ') : tx('Not assigned');
  }
  if (!t.vehicleCode) return tx('Not assigned');
  return [t.vehicleCode, t.vehiclePlate, t.driverName].filter(Boolean).join(' · ');
}

export const todayIso = () => {
  const d = new Date();
  return new Date(d.getTime() - d.getTimezoneOffset() * 60_000).toISOString().slice(0, 10);
};

export const addDays = (iso: string, n: number) => {
  const d = new Date(`${iso}T00:00:00Z`);
  d.setUTCDate(d.getUTCDate() + n);
  return d.toISOString().slice(0, 10);
};

/** Monday of the week of `iso`. */
export const weekStart = (iso: string) => {
  const d = new Date(`${iso}T00:00:00Z`);
  const dow = (d.getUTCDay() + 6) % 7;
  return addDays(iso, -dow);
};

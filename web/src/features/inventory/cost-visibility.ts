import { useAuthStore } from '@/stores/auth.store';

/** The permission that shows stock costs and values (IPR S-2). */
export const VALUATION_READ = 'inventory_valuation:read';

/** Whether the signed-in user may see stock costs and values. */
export function useSeesCost(): boolean {
  return useAuthStore((s) => s.hasPermission(VALUATION_READ));
}

/**
 * A cost or value the server hides (null) from a caller without `inventory_valuation:read`, or that the caller cannot
 * see: a dash, never a zero.
 */
export function costText(
  value: number | string | null | undefined,
  seesCost: boolean,
  format: (v: number | string) => string,
): string {
  if (!seesCost || value === null || value === undefined) return '—';
  return format(value);
}

/** The manual reservation routes create and consume only manual sources (IPR C-5); a document's follow it. */
export function canConsumeReservation(r: { sourceType: string; status: string }): boolean {
  return r.status === 'active' && r.sourceType === 'manual';
}

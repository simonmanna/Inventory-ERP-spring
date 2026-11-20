import { clsx, type ClassValue } from 'clsx';
import { twMerge } from 'tailwind-merge';
import { useAuthStore } from '@/stores/auth.store';

export function cn(...inputs: ClassValue[]): string {
  return twMerge(clsx(inputs));
}

/** ISO minor-unit digits of a currency (UGX 0, USD/SSP 2), 2 when the runtime does not know the code. */
function currencyDigits(code: string): number {
  try {
    return new Intl.NumberFormat('en', { style: 'currency', currency: code }).resolvedOptions().maximumFractionDigits ?? 2;
  } catch {
    return 2;
  }
}

/**
 * Amount in the organisation's functional currency. FX-1: it printed "UGX" with no decimals for every
 * organisation, which mislabelled SSP / USD books and dropped cents.
 */
export function formatCurrency(n: number | string | null | undefined): string {
  const code = useAuthStore.getState().organization?.currencyCode || 'UGX';
  const digits = currencyDigits(code);
  const v = n == null ? 0 : typeof n === 'string' ? Number(n) : n;
  const safe = Number.isNaN(v) ? 0 : v;
  return `${code} ${safe.toLocaleString('en', { minimumFractionDigits: digits, maximumFractionDigits: digits })}`;
}

import { useAuthStore } from '@/stores/auth.store';
import { tx } from '@/lib/i18n/tx';

/**
 * Org base currency, set in Settings → Company. Never guesses a currency: when
 * it is unknown this is '' and `money()` renders a plain number.
 */
export function useOrgCurrency(): string {
  return useAuthStore((s) => s.organization?.currencyCode ?? '');
}

/**
 * Format a money amount. `decimals` (optional) is the currency's minor-unit count
 * — e.g. the org base currency's `decimalPlaces`; omitted, it stays at 2 (the
 * historical behaviour every existing caller relies on).
 */
export function money(value?: string | number | null, currency?: string, decimals = 2): string {
  if (value === null || value === undefined || value === '') return '-';
  const n = typeof value === 'string' ? Number(value) : value;
  if (Number.isNaN(n)) return '-';
  const digits = Number.isInteger(decimals) && decimals >= 0 && decimals <= 6 ? decimals : 2;
  try {
    return new Intl.NumberFormat(undefined, {
      style: currency ? 'currency' : 'decimal',
      currency: currency || undefined,
      minimumFractionDigits: digits,
      maximumFractionDigits: digits,
    }).format(n);
  } catch {
    return n.toLocaleString(undefined, { minimumFractionDigits: digits, maximumFractionDigits: digits });
  }
}

/** ISO 4217 minor units for a currency code per the browser's Intl data (fallback 2). */
export function currencyDecimals(code?: string | null): number {
  if (!code) return 2;
  try {
    return new Intl.NumberFormat(undefined, { style: 'currency', currency: code }).resolvedOptions()
      .maximumFractionDigits ?? 2;
  } catch {
    return 2;
  }
}

/**
 * A decimal amount (string or number) as an integer count of minor units, parsed
 * from its decimal text so sums and balance checks never accumulate float error.
 * Extra fraction digits beyond `decimals` are rounded half-up. Invalid / empty = 0.
 */
export function toMinorUnits(value: string | number | null | undefined, decimals = 2): number {
  if (value === null || value === undefined || value === '') return 0;
  const text = typeof value === 'number' ? (Number.isFinite(value) ? value.toFixed(Math.min(decimals + 2, 20)) : '0') : value.trim();
  const m = /^([+-])?(\d*)(?:\.(\d*))?$/.exec(text);
  if (!m || (!m[2] && !m[3])) return 0;
  const sign = m[1] === '-' ? -1 : 1;
  const whole = m[2] || '0';
  const frac = (m[3] ?? '').padEnd(decimals + 1, '0');
  let minor = Number(whole) * 10 ** decimals + Number(frac.slice(0, decimals) || '0');
  if (Number(frac[decimals] ?? '0') >= 5) minor += 1;
  return sign * minor;
}

/** Minor units back to a decimal string with `decimals` fraction digits (for display or JSON). */
export function fromMinorUnits(minor: number, decimals = 2): string {
  const sign = minor < 0 ? '-' : '';
  const abs = Math.abs(minor);
  if (decimals === 0) return `${sign}${abs}`;
  const factor = 10 ** decimals;
  return `${sign}${Math.floor(abs / factor)}.${String(abs % factor).padStart(decimals, '0')}`;
}

export function formatMoney(value?: string | number | null, currency?: string): string {
  return money(value, currency);
}

export function date(value?: string | Date | null): string {
  if (!value) return '-';
  const d = typeof value === 'string' ? new Date(value) : value;
  return d.toLocaleDateString();
}

export function dateTime(value?: string | Date | null): string {
  if (!value) return '-';
  const d = typeof value === 'string' ? new Date(value) : value;
  return d.toLocaleString();
}

export function relativeTime(value?: string | Date | null): string {
  if (!value) return '-';
  const d = typeof value === 'string' ? new Date(value) : value;
  const diffMs = Date.now() - d.getTime();
  const sec = Math.floor(diffMs / 1000);
  if (sec < 60) return `${sec}s ago`;
  const min = Math.floor(sec / 60);
  if (min < 60) return `${min}m ago`;
  const hr = Math.floor(min / 60);
  if (hr < 24) return `${hr}h ago`;
  const days = Math.floor(hr / 24);
  if (days < 30) return `${days}d ago`;
  return d.toLocaleDateString();
}

const STATUS_LABELS: Record<string, string> = {
  not_paid: tx('Not paid'),
  partial: tx('Partial'),
  paid: tx('Paid'),
  overpaid: tx('Overpaid'),
};

export function statusLabel(value?: string | null): string {
  if (!value) return '-';
  return STATUS_LABELS[value] ?? value.replace(/_/g, ' ');
}

export function formatBytes(n: number | null | undefined): string {
  if (n == null) return '—';
  if (n < 1024) return `${n} B`;
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(1)} KB`;
  if (n < 1024 * 1024 * 1024) return `${(n / 1024 / 1024).toFixed(1)} MB`;
  return `${(n / 1024 / 1024 / 1024).toFixed(2)} GB`;
}

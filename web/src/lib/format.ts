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

/**
 * A stored org-local wall-clock bound (fiscal period / year start or end, ADR-S070) as a local Date on the same
 * calendar day. The API sends these as `YYYY-MM-DDTHH:mm:ss.sssZ`, but they are clock readings, not instants:
 * `new Date(value)` would show a period ending `…T23:59:59.999Z` as the next day east of UTC.
 */
export function wallDay(value: string): Date {
  const m = /^(\d{4})-(\d{2})-(\d{2})/.exec(value);
  return m ? new Date(Number(m[1]), Number(m[2]) - 1, Number(m[3])) : new Date(value);
}

/** {@link date} for a wall-clock bound: the stored calendar day in every browser time zone. */
export function wallDate(value?: string | null): string {
  if (!value) return '-';
  return wallDay(value).toLocaleDateString();
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
  // A future time (a due date) reads "in 3h", not "-10800s ago".
  const future = diffMs < 0;
  const fmt = (n: number, unit: string) => (future ? `in ${n}${unit}` : `${n}${unit} ago`);
  const sec = Math.floor(Math.abs(diffMs) / 1000);
  if (sec < 60) return future ? 'in <1m' : `${sec}s ago`;
  const min = Math.floor(sec / 60);
  if (min < 60) return fmt(min, 'm');
  const hr = Math.floor(min / 60);
  if (hr < 24) return fmt(hr, 'h');
  const days = Math.floor(hr / 24);
  if (days < 30) return fmt(days, 'd');
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

const pad2 = (n: number) => String(n).padStart(2, '0');

/** `YYYY-MM-DD` in the browser's local time (an `<input type="date">` value; `toISOString` would give the UTC day). */
export function localDateInput(d: Date = new Date()): string {
  return `${d.getFullYear()}-${pad2(d.getMonth() + 1)}-${pad2(d.getDate())}`;
}

/** `YYYY-MM-DDTHH:mm` in local time, for `<input type="datetime-local">` defaults. */
export function localDateTimeInput(d: Date = new Date()): string {
  return `${localDateInput(d)}T${pad2(d.getHours())}:${pad2(d.getMinutes())}`;
}

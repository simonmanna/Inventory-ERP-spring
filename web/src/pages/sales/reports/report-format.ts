import { date } from '@/lib/format';
import type { ColumnType, ReportRow } from '@/features/sales/report-center-api';

const num2 = new Intl.NumberFormat(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 });
const num3 = new Intl.NumberFormat(undefined, { maximumFractionDigits: 3 });
const int = new Intl.NumberFormat(undefined, { maximumFractionDigits: 0 });
const compact = new Intl.NumberFormat(undefined, { notation: 'compact', maximumFractionDigits: 1 });

export const NUMERIC: ColumnType[] = ['money', 'number', 'qty', 'percent', 'days', 'count'];

/** Display text of a report cell. */
export function formatCell(value: unknown, type: ColumnType, row?: ReportRow): string {
  if (value === null || value === undefined || value === '') return '—';
  const t = type === 'auto' ? ((row?.type as ColumnType) ?? 'number') : type;
  const n = typeof value === 'number' ? value : Number(value);
  switch (t) {
    case 'money': return Number.isNaN(n) ? String(value) : num2.format(n);
    case 'qty':
    case 'number': return Number.isNaN(n) ? String(value) : num3.format(n);
    case 'count': return Number.isNaN(n) ? String(value) : int.format(n);
    case 'percent': return Number.isNaN(n) ? String(value) : `${num3.format(n)}%`;
    case 'days': return Number.isNaN(n) ? String(value) : `${int.format(n)} d`;
    case 'date': return date(String(value));
    case 'status': return statusText(String(value));
    default: return String(value);
  }
}

export function compactNumber(v: number): string {
  return compact.format(v);
}

export function statusText(v: string): string {
  const s = v.replace(/_/g, ' ');
  return s.charAt(0).toUpperCase() + s.slice(1);
}

/** Tone of a status value (good / warn / bad / info / neutral). */
export function statusTone(v: string): 'good' | 'warn' | 'bad' | 'info' | 'neutral' {
  const s = v.toLowerCase();
  if (/(^up$|achieved|on_time|on time|paid|done|full|active|approved|converted|delivered|closed|peak|accepted|posted|actual)/.test(s)) return 'good';
  if (/(at_risk|at risk|on_track|partial|pending|sent|in_fulfilment|in transit|in_transit|draft|month to date|normal|flat|expiring)/.test(s)) return 'warn';
  if (/(^down$|late|overdue|behind|rejected|cancelled|inactive|lost|expired|not_paid|never|low|none|loss)/.test(s)) return 'bad';
  if (/(confirmed|projection|open|invoice|sales order|quotation|receipt|delivery)/.test(s)) return 'info';
  return 'neutral';
}

export const TONE_CLASS: Record<ReturnType<typeof statusTone>, string> = {
  good: 'bg-emerald-100 text-emerald-800 dark:bg-emerald-500/15 dark:text-emerald-300',
  warn: 'bg-amber-100 text-amber-800 dark:bg-amber-500/15 dark:text-amber-300',
  bad: 'bg-rose-100 text-rose-800 dark:bg-rose-500/15 dark:text-rose-300',
  info: 'bg-sky-100 text-sky-800 dark:bg-sky-500/15 dark:text-sky-300',
  neutral: 'bg-muted text-muted-foreground',
};

/** Validated categorical palette (fixed order, light / dark). */
export const SERIES_LIGHT = ['#2a78d6', '#eb6834', '#1baf7a', '#eda100', '#e87ba4', '#008300', '#6250d6', '#e34948'];
export const SERIES_DARK = ['#3987e5', '#d95926', '#199e70', '#c98500', '#d55181', '#008300', '#9085e9', '#e66767'];

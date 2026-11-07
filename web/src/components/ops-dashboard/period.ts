import { useCallback, useMemo } from 'react';
import { useSearchParams } from 'react-router-dom';
import { tx } from '@/lib/i18n/tx';

/** The period filter shared by the operations dashboards; kept in the URL so a view can be linked. */
export type PeriodKey = '30d' | '90d' | 'ytd' | '12m';
export type Grain = 'day' | 'week' | 'month';

export interface Period {
  key: PeriodKey;
  from: string;
  to: string;
  /** Finest grain that keeps a trend chart readable for this length. */
  grain: Grain;
}

export interface Bucket { key: string; label: string; from: string; to: string }

export const periodOptions = (): Array<{ key: PeriodKey; label: string }> => [
  { key: '30d', label: tx('30 days') },
  { key: '90d', label: tx('90 days') },
  { key: 'ytd', label: tx('Year to date') },
  { key: '12m', label: tx('12 months') },
];

const pad = (n: number) => String(n).padStart(2, '0');
export const isoDate = (d: Date) => `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
const parse = (iso: string) => {
  const [y, m, d] = iso.slice(0, 10).split('-').map(Number);
  return new Date(y, m - 1, d);
};

export function periodFor(key: PeriodKey, now = new Date()): Period {
  const to = isoDate(now);
  if (key === '30d') return { key, from: isoDate(new Date(now.getFullYear(), now.getMonth(), now.getDate() - 29)), to, grain: 'day' };
  if (key === '90d') return { key, from: isoDate(new Date(now.getFullYear(), now.getMonth(), now.getDate() - 89)), to, grain: 'week' };
  if (key === 'ytd') return { key, from: `${now.getFullYear()}-01-01`, to, grain: 'month' };
  return { key: '12m', from: isoDate(new Date(now.getFullYear(), now.getMonth() - 11, 1)), to, grain: 'month' };
}

export function usePeriod(fallback: PeriodKey = '90d') {
  const [search, setSearch] = useSearchParams();
  const raw = search.get('period');
  const key: PeriodKey = raw === '30d' || raw === '90d' || raw === 'ytd' || raw === '12m' ? raw : fallback;
  const period = useMemo(() => periodFor(key), [key]);
  const setPeriod = useCallback((next: PeriodKey) => {
    setSearch((prev) => {
      const out = new URLSearchParams(prev);
      out.set('period', next);
      return out;
    }, { replace: true });
  }, [setSearch]);
  return { period, setPeriod };
}

/** The bucket a date belongs to: the day, the Monday of its week, or the first of its month. */
export function bucketKey(value: string, grain: Grain): string {
  const iso = value.slice(0, 10);
  if (grain === 'day') return iso;
  if (grain === 'month') return `${iso.slice(0, 7)}-01`;
  const d = parse(iso);
  d.setDate(d.getDate() - ((d.getDay() + 6) % 7));
  return isoDate(d);
}

export function bucketLabel(key: string, grain: Grain): string {
  const d = parse(key);
  return grain === 'month'
    ? d.toLocaleDateString(undefined, { month: 'short' })
    : d.toLocaleDateString(undefined, { month: 'short', day: 'numeric' });
}

/** Every bucket of the period in order, so a quiet day shows as a gap and the time axis stays honest. */
export function bucketsOf(period: Pick<Period, 'from' | 'to'>, grain: Grain): Bucket[] {
  const out: Bucket[] = [];
  const end = parse(period.to);
  let cursor = parse(bucketKey(period.from, grain));
  while (cursor <= end) {
    const next = new Date(cursor);
    if (grain === 'day') next.setDate(next.getDate() + 1);
    else if (grain === 'week') next.setDate(next.getDate() + 7);
    else next.setMonth(next.getMonth() + 1);
    const last = new Date(next);
    last.setDate(last.getDate() - 1);
    const key = isoDate(cursor);
    out.push({
      key,
      label: bucketLabel(key, grain),
      from: key < period.from ? period.from : key,
      to: isoDate(last) > period.to ? period.to : isoDate(last),
    });
    cursor = next;
  }
  return out;
}

/** Adds rows up into the period's buckets; every field named in `fields` is summed. */
export function rollUp<T>(
  rows: T[],
  dateOf: (r: T) => string | null | undefined,
  fields: Record<string, (r: T) => number>,
  period: Pick<Period, 'from' | 'to'>,
  grain: Grain,
): Array<Record<string, string | number>> {
  const byKey = new Map<string, Record<string, string | number>>();
  for (const b of bucketsOf(period, grain)) {
    const row: Record<string, string | number> = { bucket: b.key, label: b.label };
    for (const f of Object.keys(fields)) row[f] = 0;
    byKey.set(b.key, row);
  }
  for (const r of rows) {
    const when = dateOf(r);
    if (!when) continue;
    const row = byKey.get(bucketKey(when, grain));
    if (!row) continue;
    for (const [f, read] of Object.entries(fields)) row[f] = Number(row[f]) + read(r);
  }
  return [...byKey.values()];
}

/** Buckets for reports that only answer one range at a time: at most 12 requests. */
export function queryBuckets(period: Period): Bucket[] {
  return bucketsOf(period, period.key === '30d' ? 'week' : 'month');
}

/** For reports with a maximum range: a longer period shows its latest days. */
export function clampDays(period: Pick<Period, 'from' | 'to'>, days: number): { from: string; to: string; clipped: boolean } {
  const start = parse(period.to);
  start.setDate(start.getDate() - (days - 1));
  const limit = isoDate(start);
  return period.from < limit ? { from: limit, to: period.to, clipped: true } : { from: period.from, to: period.to, clipped: false };
}

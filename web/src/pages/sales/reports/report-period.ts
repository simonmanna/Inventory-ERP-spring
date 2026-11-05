import { tx } from '@/lib/i18n/tx';
import type { ReportParams } from '@/features/sales/report-center-api';

// ------------------------------------------------------------------ periods

const iso = (d: Date) => `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;

export const PERIODS: Array<[string, string]> = [
  ['today', tx('Today')], ['this_week', tx('This week')], ['this_month', tx('This month')], ['last_month', tx('Last month')],
  ['this_quarter', tx('This quarter')], ['last_quarter', tx('Last quarter')], ['this_year', tx('This year')],
  ['last_year', tx('Last year')], ['last_12_months', tx('Last 12 months')], ['custom', tx('Custom range')],
];

export function periodRange(p: string): [string, string] | null {
  const t = new Date();
  const y = t.getFullYear();
  const m = t.getMonth();
  const q = Math.floor(m / 3) * 3;
  switch (p) {
    case 'today': return [iso(t), iso(t)];
    case 'this_week': { const d = new Date(t); d.setDate(t.getDate() - ((t.getDay() + 6) % 7)); return [iso(d), iso(t)]; }
    case 'this_month': return [iso(new Date(y, m, 1)), iso(t)];
    case 'last_month': return [iso(new Date(y, m - 1, 1)), iso(new Date(y, m, 0))];
    case 'this_quarter': return [iso(new Date(y, q, 1)), iso(t)];
    case 'last_quarter': return [iso(new Date(y, q - 3, 1)), iso(new Date(y, q, 0))];
    case 'this_year': return [iso(new Date(y, 0, 1)), iso(t)];
    case 'last_year': return [iso(new Date(y - 1, 0, 1)), iso(new Date(y - 1, 11, 31))];
    case 'last_12_months': return [iso(new Date(y, m - 11, 1)), iso(t)];
    default: return null;
  }
}

/** Request parameters from the page's URL state (period preset resolved to dates; UI-only keys dropped). */
export function requestParams(state: ReportParams): ReportParams {
  const out: ReportParams = {};
  const range = periodRange(state.period ?? 'this_year');
  for (const [k, v] of Object.entries(state)) {
    if (v && !['r', 'period', 'view'].includes(k)) out[k] = v;
  }
  if (range) {
    out.from = range[0];
    out.to = range[1];
  }
  return out;
}


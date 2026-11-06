import { tx } from '@/lib/i18n/tx';
import type { SalesSummary } from '@/features/sales/api';
import type { ReportDashboard } from '@/features/sales/report-center-api';

export type InsightTone = 'bad' | 'warn' | 'good' | 'info';
export interface Insight { key: string; tone: InsightTone; text: string; report?: string }

const ORDER: InsightTone[] = ['bad', 'warn', 'good', 'info'];

/**
 * Plain-language reading of the dashboard numbers, for people who do not read charts all day. Only states what the
 * data shows: no invented thresholds, and a line is dropped when its number is missing. Most urgent first.
 */
export function buildInsights(dash: ReportDashboard, summary: SalesSummary | undefined, fmt: (v: number) => string): Insight[] {
  const kpi = (key: string) => dash.kpis.find((k) => k.key === key);
  const revenue = Number(kpi('revenue')?.value ?? 0);
  const out: Insight[] = [];

  const delta = kpi('revenue')?.sub;
  if (revenue <= 0) {
    out.push({ key: 'revenue', tone: 'info', text: tx('No sales recorded in this period.') });
  } else if (delta != null) {
    const d = Number(delta);
    out.push({
      key: 'revenue', tone: d < 0 ? 'warn' : 'good', report: 'monthly-sales',
      text: d < 0 ? `${tx('Net sales are down')} ${Math.abs(d)}% ${tx('on the previous period.')}` : `${tx('Net sales are up')} ${d}% ${tx('on the previous period.')}`,
    });
  }

  const overdue = Number(kpi('overdue')?.value ?? 0);
  const outstanding = Number(kpi('outstanding')?.value ?? 0);
  if (overdue > 0) {
    out.push({ key: 'overdue', tone: 'bad', report: 'overdue-invoices', text: `${fmt(overdue)} ${tx('is overdue across')} ${kpi('overdue')?.sub ?? 0} ${tx('invoices.')}` });
  } else if (outstanding > 0) {
    out.push({ key: 'overdue', tone: 'good', report: 'outstanding-balances', text: `${fmt(outstanding)} ${tx('still to collect, and nothing is overdue.')}` });
  }

  const target = kpi('target');
  if (target?.value != null) {
    const v = Number(target.value);
    out.push({ key: 'target', tone: v >= 100 ? 'good' : 'warn', report: 'target-vs-actual', text: v >= 100 ? `${tx('Sales target reached:')} ${v}%.` : `${v}% ${tx('of the sales target reached so far.')}` });
  }

  const margin = kpi('margin')?.sub;
  if (revenue > 0 && margin != null) {
    out.push({ key: 'margin', tone: 'info', report: 'gross-profit', text: `${tx('Gross profit is')} ${margin}% ${tx('of net sales.')}` });
  }

  const customer = dash.topCustomers[0];
  if (revenue > 0 && customer?.label) {
    out.push({ key: 'customer', tone: 'info', report: 'top-customers', text: `${customer.label} ${tx('is the biggest customer, at')} ${Math.round((Number(customer.revenue) / revenue) * 100)}% ${tx('of net sales.')}` });
  }

  if (summary && summary.conversion.quotes > 0) {
    out.push({ key: 'conversion', tone: 'info', text: `${summary.conversion.converted} ${tx('of')} ${summary.conversion.quotes} ${tx('quotations became orders in the last 90 days.')}` });
  }

  return out.sort((a, b) => ORDER.indexOf(a.tone) - ORDER.indexOf(b.tone)).slice(0, 4);
}

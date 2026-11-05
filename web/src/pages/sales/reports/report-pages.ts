import {
  BadgePercent, BarChart3, ClipboardList, FileText, LineChart, Package, Receipt, Telescope, TrendingUp, Users, UsersRound,
  type LucideIcon,
} from 'lucide-react';
import type { ReportCatalog, ReportParams } from '@/features/sales/report-center-api';

/** Report center pages (child navigation under Sales → Reporting); each shows its categories' reports as tabs. */
export interface ReportPage { slug: string; title: string; description: string; icon: LucideIcon; categories: string[] }

export const REPORT_PAGES: ReportPage[] = [
  { slug: 'performance', title: 'Sales Performance', description: 'Summary, period sales, comparisons, targets, trends, branches, salespeople and channels.', icon: TrendingUp, categories: ['performance'] },
  { slug: 'customers', title: 'Customer Reports', description: 'Who buys, how often, what they prefer, who is growing and who stopped buying.', icon: Users, categories: ['customers'] },
  { slug: 'products', title: 'Product Reports', description: 'Product and category sales, top / slow / non-moving items and quantities by unit (t, m³, bags, pieces).', icon: Package, categories: ['products'] },
  { slug: 'orders', title: 'Orders & Fulfilment', description: 'Order registers and status, backorders, fulfilment, deliveries, trips, vehicles and drivers.', icon: ClipboardList, categories: ['orders', 'deliveries'] },
  { slug: 'quotations', title: 'Quotations & Pipeline', description: 'Quotation registers, conversion, follow-up, lost opportunities and the CRM pipeline.', icon: FileText, categories: ['quotations'] },
  { slug: 'billing', title: 'Billing & Collections', description: 'Invoices, aging, credit vs cash sales, tax, receipts, balances, deposits and allocations.', icon: Receipt, categories: ['invoices', 'collections'] },
  { slug: 'discounts', title: 'Discounts & Returns', description: 'Discounts by customer, product and salesperson, approvals, returns and net sales.', icon: BadgePercent, categories: ['discounts'] },
  { slug: 'team', title: 'Team Performance', description: 'Representatives: sales, targets, ranking, acquisition, retention, collections, commission and activity.', icon: UsersRound, categories: ['team'] },
  { slug: 'profitability', title: 'Profitability', description: 'Gross profit on actual inventory cost by product, customer, branch and salesperson; margin and price analysis.', icon: BarChart3, categories: ['profitability'] },
  { slug: 'forecasting', title: 'Forecasting & Planning', description: 'Forecast, projection, targets, growth, demand trends, seasonality and budget vs actual.', icon: Telescope, categories: ['forecasting'] },
];

export const HUB_ICON = LineChart;

export function pageForCategory(category: string): ReportPage | undefined {
  return REPORT_PAGES.find((p) => p.categories.includes(category));
}

/** Link to a report (in its page) with filters. */
export function reportHref(catalog: ReportCatalog | undefined, key: string, params: ReportParams = {}): string {
  const cat = catalog?.categories.find((c) => c.reports.some((r) => r.key === key));
  const page = cat ? pageForCategory(cat.key) : undefined;
  const q = new URLSearchParams({ r: key, ...params });
  return `/sales/reports/${page?.slug ?? 'performance'}?${q.toString()}`;
}

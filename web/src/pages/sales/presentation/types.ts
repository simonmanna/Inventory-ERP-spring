/** Response of `GET /sales/analytics/presentation` (SalesPresentationService). */

export interface Compare { current: number; previous: number; changePercent: number | null }

export interface PresentationProduct {
  key: string; label: string; unit: string | null; quantity: number; revenue: number; margin: number;
  marginPercent: number | null; sharePercent: number | null;
}

export interface PresentationCategory {
  key: string | null; label: string; unit: string | null; quantity: number | null; revenue: number; previousRevenue: number;
  changePercent: number | null; margin: number; marginPercent: number | null; sharePercent: number | null;
  productCount: number; products: PresentationProduct[];
}

export interface TrendBucket { from: string; to: string; revenue: number; margin: number; byCategory: Record<string, number> }

export interface Advisory { kind: string; severity: 'info' | 'warning' | 'critical'; title: string; detail: string }

export interface ActivityRow { type: string; title: string; subject: string | null; date: string | null }

export interface PresentationData {
  organization: { name: string; currencyCode: string };
  period: { from: string; to: string; days: number };
  previousPeriod: { from: string; to: string };
  generatedAt: string;
  kpis: {
    revenue: Compare; margin: Compare; marginPercent: { current: number | null; previous: number | null };
    invoices: { current: number; previous: number }; activeCustomers: { current: number; previous: number };
    averageInvoice: Compare; quantity: { current: number; previous: number };
    quotationsIssued: { count: number; value: number; previousCount: number; converted: number; conversionPercent: number | null };
    ordersConfirmed: { count: number; value: number; previousCount: number; previousValue: number };
    ordersLost: { count: number; value: number; cancelledOrders: number; lostQuotations: number };
    collections: Compare; newCustomers: number;
  };
  categories: PresentationCategory[];
  trend: { granularity: 'period' | 'month'; series: Array<{ key: string; label: string }>; buckets: TrendBucket[] };
  topCustomers: Array<{ partnerId: string; name: string; revenue: number; previousRevenue: number; changePercent: number | null; sharePercent: number | null; invoices: number }>;
  salespeople: Array<{ userId: string | null; name: string; revenue: number; margin: number; marginPercent: number | null; customers: number; invoices: number }>;
  pipeline: {
    open: { count: number; value: number; weighted: number }; won: { count: number; value: number }; lost: { count: number; value: number };
    newLeads: number; newOpportunities: number; winRatePercent: number | null;
    byStage: Array<{ stage: string; count: number; value: number }>;
    lostReasons: Array<{ reason: string; count: number; value: number }>;
    topOpen: Array<{ id: string; name: string; customer: string | null; stage: string; amount: number; probability: number; expectedClose: string | null; owner: string | null }>;
  };
  lostBusiness: Array<{ kind: 'order' | 'quotation'; number: string; customer: string | null; value: number; reason: string | null; date: string | null }>;
  receivables: { total: number; overdue: number; overdueCustomers: number; aging: Array<{ bucket: string; amount: number }> };
  activities: { counts: Record<string, number>; highlights: ActivityRow[]; upcoming: ActivityRow[] };
  forecast: { conversionRate: number; months: Array<{ month: string; runRate: number; openOrders: number; weightedQuotations: number; agreements: number; expected: number }> };
  advisories: Advisory[];
}

/** Free-text the presenter adds on the page; each becomes part of a slide. */
export interface DeckNotes {
  title: string;
  subtitle: string;
  presenter: string;
  role: string;
  highlights: string;
  marketing: string;
  actions: string;
}

export type ThemeKey = 'midnight' | 'corporate' | 'emerald' | 'sunset';

export type SectionKey =
  | 'agenda' | 'summary' | 'funnel' | 'trend' | 'mix' | 'categories' | 'customers' | 'team'
  | 'pipeline' | 'lost' | 'receivables' | 'activity' | 'forecast' | 'risks' | 'actions' | 'closing';

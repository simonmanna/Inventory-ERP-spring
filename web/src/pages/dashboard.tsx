import { useMemo, useState, type ReactNode } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import {
  AlertCircle, ArrowDownRight, ArrowRight, ArrowUpRight, Boxes, CircleDollarSign, ClipboardList, Clock, CreditCard,
  FileText, Flame, HandCoins, Package, Plus, Receipt, RefreshCw, ShoppingBag, ShoppingCart, Store, TrendingUp, Truck,
  UserPlus, Users, Wallet, Activity as ActivityIcon, type LucideIcon,
} from 'lucide-react';
import { Link } from 'react-router-dom';
import {
  Area, AreaChart, Bar, BarChart, CartesianGrid, Cell, Legend, Pie, PieChart, ResponsiveContainer,
  Tooltip as RTooltip, XAxis, YAxis,
} from 'recharts';
import { PERMISSIONS } from '@erp/shared';
import { Button } from '@/components/ui/button';
import { Skeleton } from '@/components/ui/skeleton';
import { useTheme } from '@/components/theme-provider';
import { usePartnerStats } from '@/features/partners/api';
import { useProducts } from '@/features/products/api';
import { useAuthStore } from '@/stores/auth.store';
import { api } from '@/lib/api';
import { formatMoney } from '@/lib/format';
import { cn } from '@/lib/utils';
import { useSalesByHour, useSalesSummary, useTopItems, useItemsByGroup } from '@/pages/pos/api';
import { tx } from '@/lib/i18n/tx';

/* ──────────────────────────────────────────────────────────────────────────
   Chart colours: the validated reference palette (dataviz skill), fixed order, own dark steps.
   Light passes all checks on #fff (contrast relief = labelled legends with values); dark passes on #191919.
   ────────────────────────────────────────────────────────────────────────── */
const SERIES = {
  light: ['#257de0', '#f47230', '#00b894', '#f2a600', '#ee7fac'],
  dark: ['#338ced', '#e76624', '#00a888', '#d28e05', '#e24d8b'],
};
const OTHER = { light: '#a3a3a3', dark: '#6b6b6b' };
/** Receivable age = status, each bucket also labelled with its name and amount (never colour alone). */
const AGEING = {
  light: ['#257de0', '#fbb315', '#f18a59', '#dc3c3d', '#9f1d1e'],
  dark: ['#338ced', '#fbb315', '#f18a59', '#dc3c3d', '#eb6263'],
};

const styles = `
  .dash-in { animation: dash-in .45s cubic-bezier(.2,.8,.2,1) both; }
  @keyframes dash-in { from { opacity: 0; transform: translateY(8px); } to { opacity: 1; transform: none; } }
  @media (prefers-reduced-motion: reduce) { .dash-in { animation: none; } }
`;

/* ── Date helpers — local-time ISO so "today" means the operator's today ── */
const localIso = (d: Date) =>
  `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
const addDays = (d: Date, n: number) => { const c = new Date(d); c.setDate(c.getDate() + n); return c; };

type RangeId = 'today' | '7d' | '30d' | 'mtd';
const RANGES: { id: RangeId; label: string }[] = [
  { id: 'today', label: tx('Today') },
  { id: '7d', label: tx('7 days') },
  { id: '30d', label: tx('30 days') },
  { id: 'mtd', label: tx('Month') },
];

function resolveRange(id: RangeId) {
  const today = new Date();
  switch (id) {
    case 'today': return { from: localIso(today), to: localIso(today), days: 1 };
    case '7d': return { from: localIso(addDays(today, -6)), to: localIso(today), days: 7 };
    case '30d': return { from: localIso(addDays(today, -29)), to: localIso(today), days: 30 };
    case 'mtd': {
      const first = new Date(today.getFullYear(), today.getMonth(), 1);
      const days = Math.round((today.getTime() - first.getTime()) / 86_400_000) + 1;
      return { from: localIso(first), to: localIso(today), days };
    }
  }
}

/** Window of the same length immediately before `from` — the delta baseline. */
function previousRange(from: string, days: number) {
  const start = new Date(`${from}T00:00:00`);
  return { from: localIso(addDays(start, -days)), to: localIso(addDays(start, -1)) };
}

const num = (v: unknown) => {
  const n = typeof v === 'string' ? Number(v) : typeof v === 'number' ? v : NaN;
  return Number.isFinite(n) ? n : 0;
};

/** Compact money for tiles and axes — "UGX 1.25M" beats a number that wraps. */
function compactMoney(value: number, currency?: string): string {
  try {
    return new Intl.NumberFormat(undefined, {
      style: currency ? 'currency' : 'decimal', currency: currency || undefined, notation: 'compact', maximumFractionDigits: 1,
    }).format(value);
  } catch {
    return formatMoney(value, currency);
  }
}

/** Percent change vs the previous window. null when the baseline is zero. */
function delta(current: number, previous: number): number | null {
  if (!previous) return null;
  return ((current - previous) / Math.abs(previous)) * 100;
}

const monthLabel = (ym: string) => {
  const [y, m] = ym.split('-').map(Number);
  return new Date(y, (m || 1) - 1, 1).toLocaleDateString(undefined, { month: 'short' });
};

interface KPIData {
  openInvoices: number; overdueInvoices: number; cashPosition: number; revenueMonth: number; netIncomeMonth: number;
  arAging: { current: number; b30: number; b60: number; b90: number; over90: number };
}
interface ActivityItem { id: string; type: string; description: string; amount?: number; at: string; href: string }
interface ReorderRow { productId: string; code: string; name: string; onHand: number; par: number; belowPar: boolean; suggestedOrderQty: number }
/** `GET /sales/analytics/dashboard` (net invoiced revenue, ADR-S011 S10). */
interface SalesDash {
  revenueThisMonth: number; revenueLastMonth: number; growthPercent: number | null;
  quotationsPending: { count: number; total: number }; ordersToDeliver: { count: number; total: number };
  ordersToInvoice: number; overdueReceivables: { customers: number; total: number };
  chart: Array<{ month: string; revenue: number; margin: number }>;
  topCustomers: Array<{ partnerId: string; name: string; revenue: number }>;
}

interface Kpi {
  key: string; label: string; value?: string; sub?: string; change?: number | null; invert?: boolean;
  icon: LucideIcon; href: string; spark?: number[]; tone: 'blue' | 'orange' | 'aqua' | 'yellow' | 'magenta' | 'slate';
}

const TONE: Record<Kpi['tone'], string> = {
  blue: 'bg-sky-500/10 text-sky-700 dark:text-sky-300',
  orange: 'bg-orange-500/10 text-orange-700 dark:text-orange-300',
  aqua: 'bg-emerald-500/10 text-emerald-700 dark:text-emerald-300',
  yellow: 'bg-amber-500/10 text-amber-700 dark:text-amber-300',
  magenta: 'bg-pink-500/10 text-pink-700 dark:text-pink-300',
  slate: 'bg-muted text-foreground',
};

export function DashboardPage() {
  const user = useAuthStore((s) => s.user);
  const org = useAuthStore((s) => s.organization);
  const hasPermission = useAuthStore((s) => s.hasPermission);
  const { theme } = useTheme();
  const mode = theme === 'dark' ? 'dark' : 'light';
  const colors = SERIES[mode];
  const qc = useQueryClient();

  const canPosReports = hasPermission(PERMISSIONS.pos.reports);
  const canInventory = hasPermission(PERMISSIONS.inventory.read);
  // Finance audit A4: the cash position, net income, AR ageing and recent activity need report:accounting.
  const canAccounting = hasPermission(PERMISSIONS.report.accounting);
  const canSales = hasPermission(PERMISSIONS.sales.reportRead);
  const seesMargin = hasPermission(PERMISSIONS.sales.costRead);

  const [range, setRange] = useState<RangeId>('today');
  const { from, to, days } = resolveRange(range);
  const prev = useMemo(() => previousRange(from, days), [from, days]);
  const currency = org?.currencyCode;

  /* ── ERP sales (invoiced revenue, quotations, deliveries, receivables). Branch-scoped users get 403: hide. ── */
  const sales = useQuery<SalesDash>({
    queryKey: ['sales', 'analytics', 'dashboard'],
    queryFn: async () => (await api.get<SalesDash>('/sales/analytics/dashboard')).data,
    enabled: canSales,
    retry: false,
    refetchInterval: 5 * 60_000,
  });
  const salesOk = canSales && !sales.isError;

  /* ── Point of sale analytics — gated on pos:reports so a waiter's dashboard does not fire 403s. ── */
  const groupBy = days > 45 ? 'week' : 'day';
  const summary = useSalesSummary(from, to, groupBy, {}, canPosReports);
  const prevSummary = useSalesSummary(prev.from, prev.to, groupBy, {}, canPosReports);
  const hourly = useSalesByHour(from, to, undefined, {}, canPosReports && range === 'today');
  const topItems = useTopItems(from, to, 5, {}, canPosReports);
  const byGroup = useItemsByGroup(from, to, {}, canPosReports);

  /* ── Accounting KPIs + activity — gated on report:accounting ── */
  const kpi = useQuery<KPIData>({
    queryKey: ['dashboard-kpi'],
    queryFn: async () => (await api.get<KPIData>('/reports/dashboard-kpi')).data,
    enabled: canAccounting,
    refetchInterval: 60_000,
  });
  const activity = useQuery<ActivityItem[]>({
    queryKey: ['dashboard-activity'],
    queryFn: async () => (await api.get<{ data: ActivityItem[] }>('/reports/dashboard-activity?limit=8')).data.data,
    enabled: canAccounting,
    refetchInterval: 30_000,
  });
  const reorder = useQuery<ReorderRow[]>({
    queryKey: ['dashboard-reorder'],
    queryFn: async () => (await api.get<ReorderRow[]>('/inventory/reports/reorder')).data,
    enabled: canInventory,
    staleTime: 5 * 60_000,
  });
  const partners = usePartnerStats();
  const products = useProducts({ page: 1, pageSize: 1 });

  const totals = summary.data?.totals;
  const prevTotals = prevSummary.data?.totals;
  const posOrders = num(totals?.orders);

  const posSeries = useMemo(() => {
    if (range === 'today') {
      const buckets: { hour: number; count: number; total: string }[] = hourly.data?.buckets ?? [];
      return buckets.map((b) => ({ label: `${String(b.hour).padStart(2, '0')}:00`, value: num(b.total) }));
    }
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    const periods: any[] = summary.data?.periods ?? [];
    return periods.map((p) => ({ label: p.periodKey?.length === 10 ? p.periodKey.slice(5) : p.periodKey, value: num(p.grossSales) }));
  }, [range, hourly.data, summary.data]);

  const categories = useMemo(() => {
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    const rows: any[] = Array.isArray(byGroup.data) ? byGroup.data : [];
    const top = rows.slice(0, 4).map((g) => ({ name: g.groupName || tx('Uncategorised'), value: num(g.totalAmount) }));
    const rest = rows.slice(4).reduce((s, g) => s + num(g.totalAmount), 0);
    return rest > 0 ? [...top, { name: tx('Others'), value: rest }] : top;
  }, [byGroup.data]);
  const categoryTotal = categories.reduce((s, c) => s + c.value, 0);

  const lowStock = useMemo(() => (reorder.data ?? []).filter((r) => r.belowPar).slice(0, 5), [reorder.data]);
  const belowParCount = useMemo(() => (reorder.data ?? []).filter((r) => r.belowPar).length, [reorder.data]);

  const chart = useMemo(() => (sales.data?.chart ?? []).map((c) => ({ label: monthLabel(c.month), revenue: num(c.revenue), margin: num(c.margin) })), [sales.data]);
  const thisMonth = sales.data?.chart?.at(-1);
  const marginPct = thisMonth && num(thisMonth.revenue) ? (num(thisMonth.margin) / num(thisMonth.revenue)) * 100 : null;

  /* ── KPI strip: ERP sales first, then cash; POS when there is no ERP sales access. ── */
  const kpis: Kpi[] = [];
  if (salesOk) {
    const d = sales.data;
    kpis.push(
      { key: 'rev', label: tx('Revenue this month'), value: d ? compactMoney(num(d.revenueThisMonth), currency) : undefined,
        sub: d ? `${tx('Last month')} ${compactMoney(num(d.revenueLastMonth), currency)}` : undefined, change: d?.growthPercent ?? null,
        icon: TrendingUp, href: '/sales/analytics', spark: chart.map((c) => c.revenue), tone: 'blue' },
      ...(seesMargin ? [{ key: 'margin', label: tx('Gross margin'), value: thisMonth ? compactMoney(num(thisMonth.margin), currency) : undefined,
        sub: marginPct != null ? `${marginPct.toFixed(1)}% ${tx('of revenue')}` : tx('This month'), icon: CircleDollarSign, href: '/sales/analytics', tone: 'aqua' as const }] : []),
      { key: 'quotes', label: tx('Open quotations'), value: d ? String(d.quotationsPending.count) : undefined,
        sub: d ? `${compactMoney(num(d.quotationsPending.total), currency)} ${tx('in play')}` : undefined, icon: ClipboardList, href: '/sales/quotations', tone: 'yellow' },
      { key: 'deliver', label: tx('Orders to deliver'), value: d ? String(d.ordersToDeliver.count) : undefined,
        sub: d ? `${compactMoney(num(d.ordersToDeliver.total), currency)} · ${d.ordersToInvoice} ${tx('to invoice')}` : undefined, icon: Truck, href: '/sales/orders', tone: 'orange' },
      { key: 'overdue', label: tx('Overdue receivables'), value: d ? compactMoney(num(d.overdueReceivables.total), currency) : undefined,
        sub: d ? `${d.overdueReceivables.customers} ${tx('customers')}` : undefined, icon: AlertCircle, href: '/ar-aging', tone: 'magenta' },
    );
  } else if (canPosReports) {
    kpis.push(
      { key: 'pos', label: tx('Point of sale'), value: totals ? compactMoney(num(totals.grossSales), currency) : undefined,
        sub: `${posOrders} ${tx('orders')}`, change: totals && prevTotals ? delta(num(totals.grossSales), num(prevTotals.grossSales)) : null,
        icon: Store, href: '/pos/reports', spark: posSeries.map((s) => s.value), tone: 'blue' },
      { key: 'aov', label: tx('Average order'), value: totals ? compactMoney(num(totals.avgOrderValue), currency) : undefined,
        change: totals && prevTotals ? delta(num(totals.avgOrderValue), num(prevTotals.avgOrderValue)) : null, icon: Receipt, href: '/pos/reports', tone: 'aqua' },
      { key: 'refunds', label: tx('Refunds'), value: totals ? compactMoney(num(totals.refunds), currency) : undefined,
        change: totals && prevTotals ? delta(num(totals.refunds), num(prevTotals.refunds)) : null, invert: true, icon: ArrowDownRight, href: '/pos/reports', tone: 'orange' },
    );
  }
  if (canAccounting) {
    kpis.push({ key: 'cash', label: tx('Cash position'), value: kpi.data ? compactMoney(num(kpi.data.cashPosition), currency) : undefined,
      sub: kpi.data ? `${tx('Net income')} ${compactMoney(num(kpi.data.netIncomeMonth), currency)} ${tx('this month')}` : undefined,
      icon: Wallet, href: '/trial-balance', tone: 'slate' });
    if (!salesOk) {
      kpis.push({ key: 'open', label: tx('Open invoices'), value: kpi.data ? String(kpi.data.openInvoices) : undefined,
        sub: kpi.data ? `${kpi.data.overdueInvoices} ${tx('overdue')}` : undefined, icon: FileText, href: '/invoices', tone: 'magenta' });
    }
  }
  const kpisLoading = (canSales && sales.isLoading) || (canAccounting && kpi.isLoading) || (!salesOk && canPosReports && summary.isLoading);

  const greeting = (() => {
    const h = new Date().getHours();
    if (h < 12) return tx('Good morning');
    if (h < 18) return tx('Good afternoon');
    return tx('Good evening');
  })();
  const monogram = (org?.name ?? '?').split(/\s+/).filter(Boolean).slice(0, 2).map((w) => w[0]?.toUpperCase()).join('');

  const refreshAll = () => {
    for (const k of [['dashboard-kpi'], ['dashboard-activity'], ['dashboard-reorder'], ['pos-reports'], ['sales', 'analytics', 'dashboard']]) {
      qc.invalidateQueries({ queryKey: k });
    }
  };
  const refreshing = summary.isFetching || kpi.isFetching || activity.isFetching || sales.isFetching;
  const updatedAt = Math.max(kpi.dataUpdatedAt || 0, sales.dataUpdatedAt || 0, summary.dataUpdatedAt || 0);

  const actions = [
    { to: '/sales/quotations/new', label: tx('New quotation'), icon: ClipboardList, ok: hasPermission(PERMISSIONS.sales.quotationWrite) },
    { to: '/sales/orders/new', label: tx('New sales order'), icon: ShoppingCart, ok: hasPermission(PERMISSIONS.sales.orderWrite) },
    { to: '/sales/cash-sales/new', label: tx('New cash sale'), icon: ShoppingBag, ok: hasPermission(PERMISSIONS.sales.cashSaleCreate) },
    { to: '/invoices/new', label: tx('New invoice'), icon: FileText, ok: hasPermission(PERMISSIONS.invoice.create) },
    { to: '/payments', label: tx('Record payment'), icon: HandCoins, ok: hasPermission(PERMISSIONS.payment.create) },
    { to: '/procurement/purchase-orders/new', label: tx('New purchase'), icon: Truck, ok: hasPermission(PERMISSIONS.procurement.purchaseOrder.create) },
    { to: '/products/new', label: tx('Add product'), icon: Package, ok: hasPermission(PERMISSIONS.product.create) },
    { to: '/partners', label: tx('Add customer'), icon: UserPlus, ok: hasPermission(PERMISSIONS.partner.create) },
  ].filter((a) => a.ok);

  return (
    <>
      <style>{styles}</style>
      <div className="page-wrap space-y-5">
        {/* ── Hero ── */}
        <div className="page-hero hero-sky dash-in !mb-0">
          <div className="page-hero-inner !py-5">
            <div className="flex min-w-0 items-center gap-4">
              <div className="flex h-14 w-14 shrink-0 items-center justify-center rounded-2xl border border-white/30 bg-white/15 text-xl font-bold tracking-tight backdrop-blur-md">
                {monogram || '·'}
              </div>
              <div className="min-w-0">
                <div className="text-[11px] font-semibold uppercase tracking-[0.18em] text-white/75">
                  {new Date().toLocaleDateString(undefined, { weekday: 'long', day: 'numeric', month: 'long', year: 'numeric' })}
                </div>
                <h1 className="truncate text-2xl font-bold tracking-tight md:text-[28px]">{greeting}, {user?.firstName}</h1>
                <div className="truncate text-sm text-white/85">{org?.name} · {tx('Business overview')}</div>
              </div>
            </div>
            <div className="flex flex-wrap items-center gap-2">
              {salesOk && sales.data && (
                <HeroStat label={tx('Revenue this month')} value={compactMoney(num(sales.data.revenueThisMonth), currency)} change={sales.data.growthPercent} />
              )}
              {canAccounting && kpi.data && <HeroStat label={tx('Cash position')} value={compactMoney(num(kpi.data.cashPosition), currency)} />}
              <Button variant="outline" onClick={refreshAll} className="h-auto self-stretch border-white/35 bg-white/10 text-white hover:bg-white/20 hover:text-white">
                <RefreshCw className={cn('mr-2 h-4 w-4', refreshing && 'animate-spin')} /> {tx('Refresh')}
              </Button>
            </div>
          </div>
        </div>

        {/* ── KPI strip ── */}
        {kpis.length > 0 && (
          <div className={cn('grid gap-3 sm:grid-cols-2', kpis.length >= 5 ? 'lg:grid-cols-3 xl:grid-cols-6' : kpis.length === 4 ? 'lg:grid-cols-4' : 'lg:grid-cols-3')}>
            {kpis.map((k, i) => <KpiTile key={k.key} k={k} loading={kpisLoading && !k.value} index={i} color={colors[0]} />)}
          </div>
        )}

        {/* ── Revenue trend + top customers ── */}
        {salesOk && (
          <div className="grid gap-4 lg:grid-cols-3">
            <Panel className="lg:col-span-2" delay={80} icon={TrendingUp} title={tx('Revenue & margin')}
              subtitle={seesMargin ? tx('Net invoiced revenue and gross margin, last 12 months') : tx('Net invoiced revenue, last 12 months')}
              action={<Link to="/sales/analytics" className="text-xs font-semibold text-primary hover:underline">{tx('Sales analytics')}</Link>}>
              {sales.isLoading ? <Skeleton className="h-72 w-full" /> : chart.every((c) => !c.revenue) ? (
                <EmptyState icon={TrendingUp} label={tx('No invoiced sales in the last 12 months.')} />
              ) : (
                <div className="h-72 w-full">
                  <ResponsiveContainer width="100%" height="100%">
                    <BarChart data={chart} margin={{ top: 8, right: 4, left: 0, bottom: 0 }} barGap={2} barCategoryGap="22%">
                      <CartesianGrid vertical={false} stroke="hsl(var(--border))" strokeDasharray="0" />
                      <XAxis dataKey="label" tick={{ fontSize: 11, fill: 'hsl(var(--muted-foreground))' }} tickLine={false} axisLine={false} />
                      <YAxis tick={{ fontSize: 11, fill: 'hsl(var(--muted-foreground))' }} tickLine={false} axisLine={false} width={60} tickFormatter={(v: number) => compactMoney(v)} />
                      <RTooltip cursor={{ fill: 'hsl(var(--muted) / 0.6)' }} content={<ChartTip currency={currency} />} />
                      {seesMargin && <Legend verticalAlign="top" align="right" height={28} iconType="circle" iconSize={8} wrapperStyle={{ fontSize: 12 }} />}
                      <Bar dataKey="revenue" name={tx('Revenue')} fill={colors[0]} radius={[4, 4, 0, 0]} maxBarSize={28} />
                      {seesMargin && <Bar dataKey="margin" name={tx('Gross margin')} fill={colors[1]} radius={[4, 4, 0, 0]} maxBarSize={28} />}
                    </BarChart>
                  </ResponsiveContainer>
                </div>
              )}
            </Panel>

            <Panel delay={120} icon={Users} title={tx('Top customers')} subtitle={tx('By revenue, last 12 months')}
              action={<Link to="/partners" className="text-xs font-semibold text-primary hover:underline">{tx('Customers')}</Link>}>
              {sales.isLoading ? <Skeleton className="h-72 w-full" /> : !sales.data?.topCustomers?.length ? (
                <EmptyState icon={Users} label={tx('No customer revenue yet.')} />
              ) : (
                <ol className="space-y-3.5">
                  {sales.data.topCustomers.slice(0, 6).map((c, i, arr) => {
                    const pct = Math.max(3, Math.round((num(c.revenue) / (num(arr[0]?.revenue) || 1)) * 100));
                    return (
                      <li key={c.partnerId} className="group">
                        <div className="flex items-baseline justify-between gap-2 text-sm">
                          <Link to={`/partners/${c.partnerId}`} className="flex min-w-0 items-center gap-2 hover:underline">
                            <span className="w-4 shrink-0 text-xs font-semibold tabular-nums text-muted-foreground">{i + 1}</span>
                            <span className="truncate font-medium">{c.name}</span>
                          </Link>
                          <span className="shrink-0 text-xs font-semibold tabular-nums">{compactMoney(num(c.revenue), currency)}</span>
                        </div>
                        <div className="ml-6 mt-1.5 h-1.5 overflow-hidden rounded-full bg-muted">
                          <div className="h-full rounded-full transition-all duration-700" style={{ width: `${pct}%`, background: colors[0] }} />
                        </div>
                      </li>
                    );
                  })}
                </ol>
              )}
            </Panel>
          </div>
        )}

        {/* ── Receivables, activity, stock ── */}
        <div className="grid gap-4 lg:grid-cols-3">
          <Panel delay={160} icon={Receipt} title={tx('Receivables ageing')} subtitle={tx('Open customer balances by age')}
            action={canAccounting ? <Link to="/ar-aging" className="text-xs font-semibold text-primary hover:underline">{tx('Details')}</Link> : undefined}>
            {!canAccounting ? <NoAccess label={tx('Requires the report:accounting permission.')} />
              : !kpi.data ? <Skeleton className="h-40 w-full" />
              : <Ageing data={kpi.data.arAging} currency={currency} colors={AGEING[mode]} />}
          </Panel>

          <Panel delay={200} icon={ActivityIcon} title={tx('Recent activity')} subtitle={tx('Latest postings in your organization')}>
            {!canAccounting ? <NoAccess label={tx('Requires the report:accounting permission.')} />
              : activity.isLoading ? <Skeleton className="h-48 w-full" />
              : !activity.data?.length ? <EmptyState icon={ActivityIcon} label={tx('No activity yet.')} />
              : (
                <ol className="relative max-h-80 space-y-3 overflow-y-auto pr-1 [scrollbar-color:hsl(var(--border))_transparent] [scrollbar-width:thin] before:absolute before:bottom-2 before:left-[11px] before:top-2 before:w-px before:bg-border">
                  {activity.data.map((a) => (
                    <li key={a.id} className="relative flex gap-3 pl-0">
                      <span className="relative z-10 mt-0.5 flex h-6 w-6 shrink-0 items-center justify-center rounded-full border bg-card text-muted-foreground">
                        <FileText className="h-3 w-3" />
                      </span>
                      <div className="min-w-0 flex-1">
                        <Link to={a.href} className="block truncate text-sm hover:underline" title={a.description}>{a.description}</Link>
                        <div className="flex items-center justify-between gap-2 text-[11px] text-muted-foreground">
                          <span>{new Date(a.at).toLocaleString(undefined, { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' })}</span>
                          {a.amount != null && <span className="font-semibold tabular-nums text-foreground">{formatMoney(a.amount, currency)}</span>}
                        </div>
                      </div>
                    </li>
                  ))}
                </ol>
              )}
          </Panel>

          <Panel delay={240} icon={Package} title={tx('Stock alerts')} subtitle={belowParCount ? `${belowParCount} ${tx('items at or below par')}` : tx('Items at or below par')}
            action={canInventory ? <Link to="/inventory" className="text-xs font-semibold text-primary hover:underline">{tx('Inventory')}</Link> : undefined}>
            {!canInventory ? <NoAccess label={tx('Requires the inventory:read permission.')} />
              : reorder.isLoading ? <Skeleton className="h-48 w-full" />
              : lowStock.length === 0 ? <EmptyState icon={Package} label={tx('All items above par.')} />
              : (
                <ul className="space-y-3">
                  {lowStock.map((r) => {
                    const pct = r.par > 0 ? Math.max(0, Math.min(100, (num(r.onHand) / num(r.par)) * 100)) : 0;
                    return (
                      <li key={r.productId}>
                        <div className="flex items-baseline justify-between gap-2 text-sm">
                          <span className="truncate font-medium" title={r.name}>{r.name}</span>
                          <span className="shrink-0 text-xs tabular-nums text-muted-foreground">{num(r.onHand)} / {num(r.par)}</span>
                        </div>
                        <div className="mt-1.5 flex items-center gap-2">
                          <div className="h-1.5 flex-1 overflow-hidden rounded-full bg-muted">
                            <div className="h-full rounded-full" style={{ width: `${pct}%`, background: AGEING[mode][pct < 25 ? 3 : 2] }} />
                          </div>
                          <span className="shrink-0 text-[11px] text-muted-foreground">{tx('Reorder')} {num(r.suggestedOrderQty)}</span>
                        </div>
                      </li>
                    );
                  })}
                </ul>
              )}
          </Panel>
        </div>

        {/* ── Point of sale (range-based) ── */}
        {canPosReports && (
          <Panel delay={280} icon={Store} title={tx('Point of sale')}
            subtitle={range === 'today' ? tx('Gross sales by hour') : tx('Gross sales by {{groupBy}}', { groupBy })}
            action={
              <div className="flex items-center rounded-lg border bg-muted/40 p-0.5">
                {RANGES.map((r) => (
                  <button key={r.id} type="button" onClick={() => setRange(r.id)}
                    className={cn('rounded-md px-2.5 py-1 text-xs font-semibold transition-colors', range === r.id ? 'bg-card text-foreground shadow-sm' : 'text-muted-foreground hover:text-foreground')}>
                    {r.label}
                  </button>
                ))}
              </div>
            }>
            {summary.isLoading ? <Skeleton className="h-56 w-full" /> : posOrders === 0 ? (
              <div className="flex items-center gap-3 rounded-xl border border-dashed p-4 text-sm text-muted-foreground">
                <Store className="h-5 w-5 opacity-50" />{tx('No point-of-sale sales in this period.')}
                <Link to="/pos/reports" className="ml-auto text-xs font-semibold text-primary hover:underline">{tx('POS reports')}</Link>
              </div>
            ) : (
              <div className="grid gap-6 lg:grid-cols-4">
                <div className="lg:col-span-2">
                  <div className="mb-2 flex items-baseline gap-3">
                    <span className="text-2xl font-semibold tabular-nums">{formatMoney(num(totals?.grossSales), currency)}</span>
                    <span className="text-xs text-muted-foreground">{posOrders} {tx('orders')}</span>
                    <Delta change={totals && prevTotals ? delta(num(totals.grossSales), num(prevTotals.grossSales)) : null} />
                  </div>
                  <div className="h-48 w-full">
                    <ResponsiveContainer width="100%" height="100%">
                      <AreaChart data={posSeries} margin={{ top: 4, right: 4, left: 0, bottom: 0 }}>
                        <defs>
                          <linearGradient id="dashPos" x1="0" y1="0" x2="0" y2="1">
                            <stop offset="0%" stopColor={colors[0]} stopOpacity={0.28} />
                            <stop offset="100%" stopColor={colors[0]} stopOpacity={0.02} />
                          </linearGradient>
                        </defs>
                        <CartesianGrid vertical={false} stroke="hsl(var(--border))" />
                        <XAxis dataKey="label" tick={{ fontSize: 11, fill: 'hsl(var(--muted-foreground))' }} tickLine={false} axisLine={false} minTickGap={16} />
                        <YAxis tick={{ fontSize: 11, fill: 'hsl(var(--muted-foreground))' }} tickLine={false} axisLine={false} width={56} tickFormatter={(v: number) => compactMoney(v)} />
                        <RTooltip content={<ChartTip currency={currency} />} cursor={{ stroke: 'hsl(var(--muted-foreground))', strokeDasharray: '3 3' }} />
                        <Area type="monotone" dataKey="value" name={tx('Sales')} stroke={colors[0]} strokeWidth={2} fill="url(#dashPos)" dot={false} activeDot={{ r: 4 }} />
                      </AreaChart>
                    </ResponsiveContainer>
                  </div>
                </div>
                <div>
                  <h3 className="mb-2 flex items-center gap-1.5 text-xs font-semibold uppercase tracking-wide text-muted-foreground"><Flame className="h-3.5 w-3.5" />{tx('Best sellers')}</h3>
                  {!Array.isArray(topItems.data) || !topItems.data.length ? <p className="text-sm text-muted-foreground">—</p> : (
                    <ol className="space-y-2 text-sm">
                      {/* eslint-disable-next-line @typescript-eslint/no-explicit-any */}
                      {(topItems.data as any[]).map((it, i) => (
                        <li key={it.productId} className="flex items-center gap-2">
                          <span className="w-4 text-xs font-semibold tabular-nums text-muted-foreground">{i + 1}</span>
                          <span className="min-w-0 flex-1 truncate">{it.name}</span>
                          <span className="text-xs font-semibold tabular-nums">{compactMoney(num(it.total), currency)}</span>
                        </li>
                      ))}
                    </ol>
                  )}
                </div>
                <div>
                  <h3 className="mb-2 flex items-center gap-1.5 text-xs font-semibold uppercase tracking-wide text-muted-foreground"><Boxes className="h-3.5 w-3.5" />{tx('By category')}</h3>
                  {!categories.length ? <p className="text-sm text-muted-foreground">—</p> : (
                    <div className="flex items-center gap-3">
                      <div className="h-24 w-24 shrink-0">
                        <ResponsiveContainer width="100%" height="100%">
                          <PieChart>
                            <Pie data={categories} dataKey="value" nameKey="name" innerRadius={28} outerRadius={46} paddingAngle={2} stroke="hsl(var(--card))" strokeWidth={2}>
                              {categories.map((c, i) => <Cell key={c.name} fill={i < 4 ? colors[i] : OTHER[mode]} />)}
                            </Pie>
                            <RTooltip formatter={(v: number, n: string) => [formatMoney(Number(v), currency), n]} contentStyle={{ borderRadius: 10, fontSize: 12 }} />
                          </PieChart>
                        </ResponsiveContainer>
                      </div>
                      <ul className="min-w-0 flex-1 space-y-1 text-xs">
                        {categories.map((c, i) => (
                          <li key={c.name} className="flex items-center gap-1.5">
                            <span className="h-2 w-2 shrink-0 rounded-full" style={{ background: i < 4 ? colors[i] : OTHER[mode] }} />
                            <span className="min-w-0 flex-1 truncate">{c.name}</span>
                            <span className="font-semibold tabular-nums">{categoryTotal ? Math.round((c.value / categoryTotal) * 100) : 0}%</span>
                          </li>
                        ))}
                      </ul>
                    </div>
                  )}
                </div>
              </div>
            )}
          </Panel>
        )}

        {/* ── Quick actions + master data ── */}
        <div className="grid gap-4 lg:grid-cols-3">
          {actions.length > 0 && (
            <Panel className="lg:col-span-2" delay={320} icon={Plus} title={tx('Quick actions')} subtitle={tx('Start the most common tasks')}>
              <div className="grid grid-cols-2 gap-2 sm:grid-cols-4">
                {actions.map((a) => (
                  <Link key={a.to} to={a.to}
                    className="group flex flex-col items-start gap-2 rounded-xl border bg-card p-3 text-sm font-medium transition-all hover:-translate-y-0.5 hover:border-primary/40 hover:shadow-md motion-reduce:hover:translate-y-0">
                    <span className="flex h-8 w-8 items-center justify-center rounded-lg bg-primary/10 text-primary transition-colors group-hover:bg-primary group-hover:text-primary-foreground">
                      <a.icon className="h-4 w-4" />
                    </span>
                    {a.label}
                  </Link>
                ))}
              </div>
            </Panel>
          )}
          <Panel delay={360} icon={Boxes} title={tx('Records')} subtitle={tx('Master data at a glance')} className={actions.length ? '' : 'lg:col-span-3'}>
            <div className="grid grid-cols-2 gap-2">
              <CountTile label={tx('Partners')} value={partners.data?.meta?.total} href="/partners" icon={Users} />
              <CountTile label={tx('Products')} value={products.data?.meta?.total} href="/products" icon={Package} />
              {canAccounting && <CountTile label={tx('Open invoices')} value={kpi.data?.openInvoices} href="/invoices" icon={FileText} />}
              {canAccounting && <CountTile label={tx('Overdue invoices')} value={kpi.data?.overdueInvoices} href="/ar-aging" icon={AlertCircle} />}
            </div>
          </Panel>
        </div>

        {/* ── Status bar ── */}
        <div className="flex flex-wrap items-center gap-x-5 gap-y-2 rounded-xl border bg-card px-4 py-2.5 text-xs text-muted-foreground shadow-sm">
          <span className="flex items-center gap-1.5"><Clock className="h-3.5 w-3.5" />{tx('Business date')} <strong className="text-foreground">{new Date().toLocaleDateString()}</strong></span>
          <span className="flex items-center gap-1.5">
            <span className={cn('h-2 w-2 rounded-full', kpi.isError || sales.isError ? 'bg-amber-500' : 'bg-emerald-500')} />
            {tx('Data')} <strong className="text-foreground">{kpi.isError || sales.isError ? tx('Partly unavailable') : tx('Live')}</strong>
          </span>
          <span className="flex items-center gap-1.5"><RefreshCw className="h-3.5 w-3.5" />{tx('Updated')}
            <strong className="text-foreground">{updatedAt ? new Date(updatedAt).toLocaleTimeString(undefined, { hour: '2-digit', minute: '2-digit' }) : '—'}</strong></span>
          <Link to={salesOk ? '/sales/analytics' : '/pos/reports'} className="ml-auto inline-flex items-center gap-1 font-semibold text-primary hover:underline">
            {tx('Full reports')}<ArrowRight className="h-3.5 w-3.5" />
          </Link>
        </div>
      </div>
    </>
  );
}

/* ────────────────────────────────────────────────────────────────────────── */

function HeroStat({ label, value, change }: { label: string; value: string; change?: number | null }) {
  return (
    <div className="rounded-xl border border-white/25 bg-white/10 px-3.5 py-2 backdrop-blur-md">
      <div className="text-[10px] font-semibold uppercase tracking-[0.14em] text-white/75">{label}</div>
      <div className="flex items-baseline gap-2">
        <span className="text-lg font-bold tabular-nums">{value}</span>
        {change != null && (
          <span className="inline-flex items-center text-[11px] font-semibold text-white/90">
            {change >= 0 ? <ArrowUpRight className="h-3 w-3" /> : <ArrowDownRight className="h-3 w-3" />}{Math.abs(change).toFixed(1)}%
          </span>
        )}
      </div>
    </div>
  );
}

function Panel({ icon: Icon, title, subtitle, action, children, className, delay = 0 }: {
  icon: LucideIcon; title: string; subtitle?: string; action?: ReactNode; children: ReactNode; className?: string; delay?: number;
}) {
  return (
    <section className={cn('dash-in rounded-2xl border bg-card p-5 shadow-sm', className)} style={{ animationDelay: `${delay}ms` }}>
      <header className="mb-4 flex items-start justify-between gap-3">
        <div className="flex min-w-0 items-start gap-2.5">
          <span className="mt-0.5 flex h-7 w-7 shrink-0 items-center justify-center rounded-lg bg-primary/10 text-primary"><Icon className="h-3.5 w-3.5" /></span>
          <div className="min-w-0">
            <h2 className="text-[15px] font-semibold leading-tight">{title}</h2>
            {subtitle && <p className="mt-0.5 text-xs text-muted-foreground">{subtitle}</p>}
          </div>
        </div>
        {action}
      </header>
      {children}
    </section>
  );
}

function Delta({ change, invert }: { change?: number | null; invert?: boolean }) {
  if (change == null) return null;
  const up = change >= 0;
  const good = invert ? !up : up;
  return (
    <span className={cn('inline-flex items-center gap-0.5 rounded-full px-1.5 py-0.5 text-[11px] font-semibold',
      good ? 'bg-emerald-500/10 text-emerald-700 dark:text-emerald-300' : 'bg-rose-500/10 text-rose-700 dark:text-rose-300')}>
      {up ? <ArrowUpRight className="h-3 w-3" /> : <ArrowDownRight className="h-3 w-3" />}
      {Math.abs(change).toFixed(1)}%
    </span>
  );
}

function KpiTile({ k, loading, index, color }: { k: Kpi; loading: boolean; index: number; color: string }) {
  const Icon = k.icon;
  const spark = (k.spark ?? []).filter((v) => Number.isFinite(v));
  return (
    <Link to={k.href} className="dash-in group block" style={{ animationDelay: `${index * 40}ms` }}>
      <div className="flex h-full flex-col rounded-2xl border bg-card p-4 shadow-sm transition-all duration-200 group-hover:-translate-y-0.5 group-hover:border-primary/40 group-hover:shadow-md motion-reduce:group-hover:translate-y-0">
        <div className="flex items-start justify-between gap-2">
          <span className="text-xs font-medium text-muted-foreground">{k.label}</span>
          <span className={cn('flex h-8 w-8 shrink-0 items-center justify-center rounded-lg', TONE[k.tone])}><Icon className="h-4 w-4" /></span>
        </div>
        <div className="mt-2 flex flex-wrap items-baseline gap-2">
          {loading ? <Skeleton className="h-7 w-24" /> : <span className="text-2xl font-semibold tracking-tight tabular-nums">{k.value ?? '—'}</span>}
          <Delta change={k.change} invert={k.invert} />
        </div>
        {k.sub && <p className="mt-0.5 truncate text-xs text-muted-foreground" title={k.sub}>{k.sub}</p>}
        {spark.length > 1 && (
          <div className="mt-auto h-9 w-full pt-2">
            <ResponsiveContainer width="100%" height="100%">
              <AreaChart data={spark.map((v, i) => ({ i, v }))} margin={{ top: 2, right: 0, left: 0, bottom: 0 }}>
                <defs>
                  <linearGradient id={`spark-${k.key}`} x1="0" y1="0" x2="0" y2="1">
                    <stop offset="0%" stopColor={color} stopOpacity={0.25} />
                    <stop offset="100%" stopColor={color} stopOpacity={0} />
                  </linearGradient>
                </defs>
                <Area type="monotone" dataKey="v" stroke={color} strokeWidth={1.75} fill={`url(#spark-${k.key})`} dot={false} isAnimationActive={false} />
              </AreaChart>
            </ResponsiveContainer>
          </div>
        )}
      </div>
    </Link>
  );
}

/** One horizontal bar split by age (2px gaps), then a labelled row per bucket with amount and share. */
function Ageing({ data, currency, colors }: { data: KPIData['arAging']; currency?: string; colors: string[] }) {
  const rows = [
    { label: tx('Current'), value: num(data.current) },
    { label: tx('1–30 days'), value: num(data.b30) },
    { label: tx('31–60 days'), value: num(data.b60) },
    { label: tx('61–90 days'), value: num(data.b90) },
    { label: tx('Over 90 days'), value: num(data.over90) },
  ];
  const total = rows.reduce((s, r) => s + r.value, 0);
  if (!total) return <EmptyState icon={Receipt} label={tx('No open receivables.')} />;
  const overdue = total - rows[0].value;
  return (
    <div className="space-y-4">
      <div className="flex items-baseline justify-between">
        <span className="text-2xl font-semibold tabular-nums">{compactMoney(total, currency)}</span>
        <span className="text-xs text-muted-foreground">{Math.round((overdue / total) * 100)}% {tx('overdue')}</span>
      </div>
      <div className="flex h-3 w-full gap-[2px] overflow-hidden rounded-full bg-muted">
        {rows.map((r, i) => r.value > 0 && (
          <div key={r.label} title={`${r.label}: ${formatMoney(r.value, currency)}`} className="h-full first:rounded-l-full last:rounded-r-full"
            style={{ width: `${(r.value / total) * 100}%`, background: colors[i], minWidth: 4 }} />
        ))}
      </div>
      <ul className="space-y-1.5 text-sm">
        {rows.map((r, i) => (
          <li key={r.label} className="flex items-center gap-2">
            <span className="h-2.5 w-2.5 shrink-0 rounded-sm" style={{ background: colors[i] }} />
            <span className="flex-1 text-muted-foreground">{r.label}</span>
            <span className="w-10 text-right text-xs tabular-nums text-muted-foreground">{Math.round((r.value / total) * 100)}%</span>
            <span className="w-28 text-right text-xs font-semibold tabular-nums">{formatMoney(r.value, currency)}</span>
          </li>
        ))}
      </ul>
    </div>
  );
}

// eslint-disable-next-line @typescript-eslint/no-explicit-any
function ChartTip({ active, payload, label, currency }: { active?: boolean; payload?: any[]; label?: string; currency?: string }) {
  if (!active || !payload?.length) return null;
  return (
    <div className="rounded-lg border bg-popover px-3 py-2 text-xs text-popover-foreground shadow-md">
      <div className="mb-1 font-semibold">{label}</div>
      {payload.map((p) => (
        <div key={p.dataKey} className="flex items-center gap-2">
          <span className="h-2 w-2 rounded-full" style={{ background: p.color }} />
          <span className="text-muted-foreground">{p.name}</span>
          <span className="ml-auto pl-4 font-semibold tabular-nums">{formatMoney(Number(p.value), currency)}</span>
        </div>
      ))}
    </div>
  );
}

function CountTile({ label, value, href, icon: Icon }: { label: string; value?: number; href: string; icon: LucideIcon }) {
  return (
    <Link to={href} className="group rounded-xl border p-3 transition-colors hover:border-primary/40 hover:bg-muted/40">
      <div className="flex items-center justify-between text-xs text-muted-foreground">{label}<Icon className="h-3.5 w-3.5 opacity-60" /></div>
      <div className="mt-1 text-xl font-semibold tabular-nums">{value ?? '—'}</div>
    </Link>
  );
}

function EmptyState({ icon: Icon, label }: { icon: LucideIcon; label: string }) {
  return (
    <div className="flex flex-col items-center justify-center gap-2 py-10 text-muted-foreground">
      <Icon className="h-7 w-7 opacity-40" />
      <p className="text-sm">{label}</p>
    </div>
  );
}

function NoAccess({ label }: { label: string }) {
  return (
    <div className="flex flex-col items-center justify-center gap-2 py-10 text-muted-foreground">
      <CreditCard className="h-6 w-6 opacity-40" />
      <p className="text-center text-xs">{label}</p>
    </div>
  );
}

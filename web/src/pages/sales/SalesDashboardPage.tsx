import { useMemo, type CSSProperties, type ReactNode } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import {
  AlertCircle, AlertTriangle, ArrowDownRight, ArrowRight, ArrowUpRight, CheckCircle2, CircleDollarSign, ClipboardList, FileText, Info,
  Loader2, PauseCircle, RefreshCw, ShieldCheck, Truck, type LucideIcon,
} from 'lucide-react';
import { Button } from '@/components/ui/button';
import { money } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';
import { cn } from '@/lib/utils';
import { useSalesSummary } from '@/features/sales/api';
import { useReportCatalog, useReportDashboard, useReportLookups, type DashboardTop, type ReportParams } from '@/features/sales/report-center-api';
import { useAuthStore } from '@/stores/auth.store';
import { ReportChart } from './reports/ReportChart';
import { REPORT_PAGES, reportHref } from './reports/report-pages';
import { PERIODS, requestParams } from './reports/report-period';
import { buildInsights, type InsightTone } from './dashboard/insights';
import { compactMoney, displayLabel, useCountUp } from './dashboard/utils';
import { CategoryTreemap, ChannelDonut, HeroTrend, Leaderboard, Panel, Reveal } from './dashboard/widgets';

const PERIOD_PILLS = ['this_month', 'this_quarter', 'this_year', 'last_12_months', 'last_year'];
const hairline = 'color-mix(in srgb, var(--sb-text) 16%, transparent)';

function greeting(): string {
  const h = new Date().getHours();
  return h < 12 ? tx('Good morning') : h < 18 ? tx('Good afternoon') : tx('Good evening');
}

const orderCount = (n: number) => `${n} ${n === 1 ? tx('order') : tx('orders')}`;

const TONE: Record<InsightTone, { icon: LucideIcon; label: string; className: string }> = {
  bad: { icon: AlertCircle, label: 'Act now', className: 'bg-rose-100 text-rose-800 dark:bg-rose-500/15 dark:text-rose-300' },
  warn: { icon: AlertTriangle, label: 'Watch', className: 'bg-amber-100 text-amber-800 dark:bg-amber-500/15 dark:text-amber-300' },
  good: { icon: CheckCircle2, label: 'Good', className: 'bg-emerald-100 text-emerald-800 dark:bg-emerald-500/15 dark:text-emerald-300' },
  info: { icon: Info, label: 'Note', className: 'bg-muted text-muted-foreground' },
};

function HeroStat({ label, value, sub, title, to, children }: { label: string; value: string; sub?: string; title?: string; to: string; children?: ReactNode }) {
  return (
    <Link to={to} title={title} className="flex items-center justify-between gap-2 rounded-xl border p-3 transition-colors hover:bg-[var(--sb-hover)]" style={{ borderColor: hairline }}>
      <span className="min-w-0">
        <span className="block truncate text-xs" style={{ color: 'var(--sb-text)' }}>{label}</span>
        <span className="mt-0.5 block truncate text-lg font-semibold tabular-nums">{value}</span>
        {sub && <span className="block truncate text-xs" style={{ color: 'var(--sb-text)' }}>{sub}</span>}
      </span>
      {children}
    </Link>
  );
}

function Ring({ percent }: { percent: number }) {
  const r = 17;
  const c = 2 * Math.PI * r;
  return (
    <svg viewBox="0 0 44 44" className="h-11 w-11 shrink-0 -rotate-90" aria-hidden>
      <circle cx="22" cy="22" r={r} fill="none" stroke="var(--sb-text)" strokeOpacity={0.16} strokeWidth="5" />
      <circle cx="22" cy="22" r={r} fill="none" stroke="var(--sb-active-bar)" strokeWidth="5" strokeLinecap="round" strokeDasharray={`${(Math.min(100, Math.max(0, percent)) / 100) * c} ${c}`} className="transition-[stroke-dasharray] duration-700" />
    </svg>
  );
}

function FlowStage({ step, icon: Icon, title, value, sub, to, foot }: { step: number; icon: LucideIcon; title: string; value: string; sub: string; to: string; foot?: string }) {
  return (
    <div className="relative">
      <Link to={to} className="block h-full rounded-xl border bg-background/60 p-4 transition-all hover:-translate-y-0.5 hover:border-primary/40 hover:shadow-md motion-reduce:hover:translate-y-0">
        <span className="flex items-center gap-2 text-xs font-medium text-muted-foreground">
          <span className="flex h-7 w-7 items-center justify-center rounded-lg bg-primary/10 text-primary"><Icon className="h-4 w-4" /></span>
          {step}. {title}
        </span>
        <span className="mt-3 block text-2xl font-semibold tabular-nums">{value}</span>
        <span className="block truncate text-xs text-muted-foreground">{sub}</span>
        {foot && <span className="mt-2 block truncate border-t pt-2 text-xs text-muted-foreground">{foot}</span>}
      </Link>
      {step < 4 && <ArrowRight className="absolute -right-[18px] top-1/2 z-10 hidden h-4 w-4 -translate-y-1/2 text-muted-foreground lg:block" aria-hidden />}
    </div>
  );
}

/** Sales → Dashboard: one screen for anyone from a new hire to the general manager; every number opens its report. */
export function SalesDashboardPage() {
  const [search, setSearch] = useSearchParams();
  const state: ReportParams = useMemo(() => ({ period: search.get('period') ?? 'this_year', branchId: search.get('branchId') ?? '' }), [search]);
  const params = useMemo(() => requestParams(state), [state]);
  const dashQuery = useReportDashboard(params);
  const { data: summary } = useSalesSummary();
  const { data: catalog } = useReportCatalog();
  const { data: lookups } = useReportLookups();
  const user = useAuthStore((s) => s.user);
  const currency = useAuthStore((s) => s.organization?.currencyCode);
  const dash = dashQuery.data;

  const kpi = (key: string) => dash?.kpis.find((k) => k.key === key);
  const revenue = Number(kpi('revenue')?.value ?? 0);
  const counted = useCountUp(revenue);

  const setFilter = (k: string, v: string) => {
    const next = new URLSearchParams(search);
    if (v) next.set(k, v); else next.delete(k);
    setSearch(next, { replace: true });
  };
  const href = (key: string, extra: ReportParams = {}) => {
    const p: ReportParams = { period: state.period };
    if (state.branchId) p.branchId = state.branchId;
    return reportHref(catalog, key, { ...p, ...extra });
  };
  const drill = (r: DashboardTop) => (r._drill ? href(r._drill.report, r._drill.params) : undefined);
  const fmt = (v: unknown) => compactMoney(v, currency);
  const periodLabel = PERIODS.find(([k]) => k === state.period)?.[1] ?? '';

  const delta = kpi('revenue')?.sub;
  const deltaN = delta == null ? null : Number(delta);
  const marginPct = kpi('margin')?.sub;
  const target = kpi('target');
  const q = summary?.quotations ?? {};
  const openQuoteKeys = ['draft', 'pending_approval', 'approved', 'sent'];
  const quoteCount = openQuoteKeys.reduce((s, k) => s + (q[k]?.count ?? 0), 0);
  const quoteValue = openQuoteKeys.reduce((s, k) => s + Number(q[k]?.total ?? 0), 0);
  const overdue = Number(kpi('overdue')?.value ?? 0);

  const insights = dash ? buildInsights(dash, summary, fmt) : [];
  const attention = [
    { key: 'overdue', icon: AlertCircle, label: tx('Overdue invoices'), count: Number(kpi('overdue')?.sub ?? 0), note: overdue > 0 ? fmt(overdue) : undefined, bad: true, to: href('overdue-invoices') },
    { key: 'hold', icon: PauseCircle, label: tx('Orders on hold'), count: summary?.onHold ?? 0, note: tx('credit or manual hold'), bad: false, to: '/sales/orders?tab=on_hold' },
    { key: 'approvals', icon: ShieldCheck, label: tx('Waiting for approval'), count: summary?.pendingApprovals ?? 0, note: tx('quotes, orders, changes'), bad: false, to: '/approvals' },
    { key: 'pending', icon: ClipboardList, label: tx('Pending orders'), count: Number(kpi('pendingOrders')?.value ?? 0), note: tx('draft or awaiting approval'), bad: false, to: href('pending-orders') },
  ].sort((a, b) => Number(b.count > 0) - Number(a.count > 0));
  const allClear = attention.every((a) => a.count === 0);

  const heroStyle: CSSProperties = {
    background: 'radial-gradient(90% 130% at 100% 0%, color-mix(in srgb, var(--sb-active-bar) 16%, transparent) 0%, transparent 60%), var(--sb-sidebar)',
    color: 'var(--sb-active)',
  };

  return (
    <div className="space-y-5">
      <Reveal>
        <section className="overflow-hidden rounded-2xl p-5 shadow-lg md:p-7" style={heroStyle}>
          <div className="flex flex-wrap items-start justify-between gap-3">
            <div>
              <h1 className="flex items-center gap-2 text-xl font-semibold">
                {tx('Sales Dashboard')}
                {dashQuery.isFetching && <Loader2 className="h-4 w-4 animate-spin" style={{ color: 'var(--sb-text)' }} />}
              </h1>
              <p className="text-sm" style={{ color: 'var(--sb-text)' }}>{greeting()}{user?.firstName ? `, ${user.firstName}` : ''}. {tx('Here is how sales are doing.')}</p>
            </div>
            <div className="flex flex-wrap items-center gap-2">
              <div className="flex flex-wrap rounded-lg border p-0.5" style={{ borderColor: hairline }} role="group" aria-label={tx('Period')}>
                {PERIOD_PILLS.map((k) => {
                  const on = state.period === k;
                  return (
                    <button key={k} type="button" aria-pressed={on} onClick={() => setFilter('period', k)}
                      className={cn('rounded-md px-2.5 py-1 text-xs font-medium transition-colors', !on && 'hover:bg-[var(--sb-hover)]')}
                      style={on ? { background: 'var(--sb-active-bar)', color: '#1f2023' } : { color: 'var(--sb-text)' }}>
                      {PERIODS.find(([p]) => p === k)?.[1]}
                    </button>
                  );
                })}
              </div>
              <select aria-label={tx('Branch')} className="h-8 rounded-lg border bg-transparent px-2 text-xs" style={{ borderColor: hairline, color: 'var(--sb-text)' }} value={state.branchId} onChange={(e) => setFilter('branchId', e.target.value)}>
                <option className="bg-card text-foreground" value="">{tx('All branches')}</option>
                {(lookups?.branches ?? []).map((b) => <option className="bg-card text-foreground" key={b.id} value={b.id}>{b.name}</option>)}
              </select>
            </div>
          </div>

          {dashQuery.isError && !dash ? (
            <div className="flex h-72 flex-col items-center justify-center gap-3 text-center">
              <AlertTriangle className="h-6 w-6" />
              <p className="text-sm">{tx('The sales figures could not be loaded.')}</p>
              <Button size="sm" variant="secondary" onClick={() => dashQuery.refetch()}><RefreshCw className="me-1.5 h-3.5 w-3.5" />{tx('Try again')}</Button>
            </div>
          ) : !dash ? (
            <div className="flex h-72 items-center justify-center"><Loader2 className="h-6 w-6 animate-spin" /></div>
          ) : (
            <>
              <div className="mt-6 grid grid-cols-1 gap-6 lg:grid-cols-[minmax(0,5fr)_minmax(0,7fr)]">
                <div>
                  <p className="text-sm" style={{ color: 'var(--sb-text)' }}>{tx('Net sales')} · {periodLabel}</p>
                  <p className="mt-1 text-5xl font-bold tracking-tight tabular-nums md:text-6xl" title={money(revenue, currency)}>{fmt(counted)}</p>
                  <div className="mt-3 flex items-center gap-2 text-sm">
                    {deltaN == null ? (
                      <span style={{ color: 'var(--sb-text)' }}>{tx('No previous period to compare with')}</span>
                    ) : (
                      <>
                        <span className="inline-flex items-center gap-1 rounded-full px-2 py-0.5 font-medium" style={{ background: hairline }}>
                          {deltaN < 0 ? <ArrowDownRight className="h-3.5 w-3.5" /> : <ArrowUpRight className="h-3.5 w-3.5" />}{Math.abs(deltaN)}%
                        </span>
                        <span style={{ color: 'var(--sb-text)' }}>{deltaN < 0 ? tx('lower than the previous period') : tx('higher than the previous period')}</span>
                      </>
                    )}
                  </div>
                </div>
                <div>
                  <div className="mb-1 flex flex-wrap items-center justify-between gap-2 text-xs" style={{ color: 'var(--sb-text)' }}>
                    <span className="flex items-center gap-3">
                      <span className="inline-flex items-center gap-1.5"><span className="h-2.5 w-2.5 rounded-sm" style={{ background: 'var(--sb-active-bar)' }} />{tx('Net sales')}</span>
                      <span className="inline-flex items-center gap-1.5"><span className="h-0.5 w-3.5" style={{ background: 'var(--sb-active)' }} />{tx('Gross profit')}</span>
                    </span>
                    <Link to={href('sales-trend', { period: 'last_12_months' })} className="inline-flex items-center gap-1 hover:underline">{tx('12-month trend')}<ArrowRight className="h-3 w-3" /></Link>
                  </div>
                  <div className="h-44"><HeroTrend data={dash.trend} currency={currency} /></div>
                </div>
              </div>
              <div className="mt-5 grid grid-cols-2 gap-3 lg:grid-cols-4">
                <HeroStat label={tx('Gross profit')} value={fmt(kpi('margin')?.value)} title={money(Number(kpi('margin')?.value ?? 0), currency)} sub={marginPct != null ? `${marginPct}% ${tx('margin')}` : undefined} to={href('gross-profit')} />
                <HeroStat label={tx('Sales orders')} value={String(kpi('orders')?.value ?? 0)} sub={periodLabel} to={href('order-register')} />
                <HeroStat label={tx('Average order value')} value={fmt(kpi('aov')?.value)} title={money(Number(kpi('aov')?.value ?? 0), currency)} to={href('sales-summary')} />
                <HeroStat label={tx('Sales target')} value={target?.value != null ? `${target.value}%` : '—'} sub={target?.value != null ? `${tx('of')} ${fmt(target.sub)}` : tx('No company target set')} to={href('target-vs-actual')}>
                  {target?.value != null && <Ring percent={Number(target.value)} />}
                </HeroStat>
              </div>
            </>
          )}
        </section>
      </Reveal>

      {dash && (
        <>
          <Reveal delay={80} className="grid grid-cols-1 gap-5 lg:grid-cols-[minmax(0,3fr)_minmax(0,2fr)]">
            <Panel title={tx('What the numbers say')} hint={`${periodLabel} · ${tx('in plain words')}`}>
              {insights.length === 0 && <p className="py-6 text-center text-sm text-muted-foreground">{tx('Nothing to report yet.')}</p>}
              <ul className="grid grid-cols-1 gap-3 sm:grid-cols-2">
                {insights.map((n) => {
                  const t = TONE[n.tone];
                  const body = (
                    <>
                      <span className={cn('inline-flex items-center gap-1 rounded-full px-2 py-0.5 text-[11px] font-medium', t.className)}><t.icon className="h-3 w-3" />{tx(t.label)}</span>
                      <span className="mt-2 block text-sm leading-snug">{n.text}</span>
                    </>
                  );
                  return <li key={n.key}>{n.report ? <Link to={href(n.report)} className="block h-full rounded-xl border p-3.5 transition-colors hover:border-primary/40 hover:bg-muted/40">{body}</Link> : <div className="h-full rounded-xl border p-3.5">{body}</div>}</li>;
                })}
              </ul>
            </Panel>
            <Panel title={tx('Needs attention')} hint={tx('Right now · whole company')}>
              {allClear && <p className="mb-3 flex items-center gap-2 rounded-lg bg-emerald-100 px-3 py-2 text-sm font-medium text-emerald-800 dark:bg-emerald-500/15 dark:text-emerald-300"><CheckCircle2 className="h-4 w-4" />{tx('All clear. Nothing is waiting.')}</p>}
              <ul className="divide-y">
                {attention.map((a) => (
                  <li key={a.key}>
                    <Link to={a.to} className="-mx-2 flex items-center gap-3 rounded-lg px-2 py-2.5 transition-colors hover:bg-muted/50">
                      <a.icon className={cn('h-4 w-4 shrink-0', a.count === 0 ? 'text-muted-foreground' : a.bad ? 'text-rose-600 dark:text-rose-400' : 'text-amber-600 dark:text-amber-400')} />
                      <span className="min-w-0 flex-1">
                        <span className="block truncate text-sm font-medium">{a.label}</span>
                        {a.note && <span className="block truncate text-xs text-muted-foreground">{a.note}</span>}
                      </span>
                      <span className={cn('text-lg font-semibold tabular-nums', a.count === 0 && 'text-muted-foreground')}>{a.count}</span>
                    </Link>
                  </li>
                ))}
              </ul>
            </Panel>
          </Reveal>

          <Reveal delay={140}>
            <Panel title={tx('From quote to cash')} hint={tx('Where sales stand right now · whole company')}>
              <div className="grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-4 lg:gap-5">
                <FlowStage step={1} icon={FileText} title={tx('Open quotations')} value={fmt(quoteValue)} sub={`${quoteCount} ${quoteCount === 1 ? tx('quotation') : tx('quotations')}`} to="/sales/quotations"
                  foot={summary ? `${summary.conversion.rate}% ${tx('converted, last 90 days')}` : undefined} />
                <FlowStage step={2} icon={ClipboardList} title={tx('Open orders')} value={fmt(summary?.openOrders.total)} sub={orderCount(summary?.openOrders.count ?? 0)} to="/sales/orders?tab=confirmed%2Cin_fulfilment" />
                <FlowStage step={3} icon={Truck} title={tx('To deliver')} value={fmt(summary?.toFulfil.total)} sub={orderCount(summary?.toFulfil.count ?? 0)} to="/sales/orders?tab=to_fulfil" />
                <FlowStage step={4} icon={CircleDollarSign} title={tx('To collect')} value={fmt(kpi('outstanding')?.value)} sub={tx('unpaid invoices')} to={href('outstanding-balances')}
                  foot={`${fmt(overdue)} ${tx('overdue')}`} />
              </div>
            </Panel>
          </Reveal>

          <Reveal delay={200} className="grid grid-cols-1 gap-5 xl:grid-cols-3">
            <Panel title={tx('Sales by channel')} href={href('sales-by-channel')}>
              <ChannelDonut rows={dash.byChannel} order={(lookups?.channels ?? []).map((c) => c.id)} currency={currency} total={dash.byChannel.reduce((s, r) => s + Math.max(0, Number(r.revenue) || 0), 0)} />
            </Panel>
            <Panel title={tx('Sales by product category')} hint={tx('Bigger and darker means more sales')} href={href('category-sales')}>
              <CategoryTreemap rows={dash.byCategory} currency={currency} />
            </Panel>
            <Panel title={tx('Sales by branch')} href={href('sales-by-branch')}>
              <ReportChart spec={{ kind: 'hbar', x: 'label', stacked: false, series: [{ key: 'revenue', label: tx('Net sales'), kind: 'bar' }, { key: 'margin', label: tx('Gross profit'), kind: 'bar' }] }}
                rows={dash.byBranch.map((r) => ({ ...r, label: displayLabel(r.label) }))} columns={[{ key: 'revenue', label: 'Net sales', type: 'money' }, { key: 'margin', label: 'Gross profit', type: 'money' }]} height={240} />
            </Panel>
          </Reveal>

          <Reveal delay={260} className="grid grid-cols-1 gap-5 md:grid-cols-2 xl:grid-cols-3">
            <Leaderboard title={tx('Top sales representatives')} rows={dash.topSalespeople} href={href('salesperson-ranking')} currency={currency} drillHref={drill} />
            <Leaderboard title={tx('Top customers')} rows={dash.topCustomers} href={href('top-customers')} currency={currency} drillHref={drill} />
            <Leaderboard title={tx('Top products')} rows={dash.topProducts} href={href('top-products')} currency={currency} drillHref={drill} />
          </Reveal>

          <Reveal delay={320}>
            <Panel title={tx('Go deeper')} hint={`${catalog?.count ?? '…'} ${tx('reports, grouped by topic')}`} href="/sales/reports" action={tx('All sales reports')}>
              <div className="grid grid-cols-2 gap-2 md:grid-cols-3 xl:grid-cols-5">
                {REPORT_PAGES.map((p) => (
                  <Link key={p.slug} to={`/sales/reports/${p.slug}`} className="group flex items-center gap-2.5 rounded-xl border p-2.5 text-sm font-medium transition-colors hover:border-primary/40 hover:bg-primary/5">
                    <span className="flex h-8 w-8 shrink-0 items-center justify-center rounded-lg bg-primary/10 text-primary"><p.icon className="h-4 w-4" /></span>
                    <span className="min-w-0 truncate group-hover:text-primary">{tx(p.title)}</span>
                  </Link>
                ))}
              </div>
            </Panel>
          </Reveal>
        </>
      )}
    </div>
  );
}

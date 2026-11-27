import { useMemo, type CSSProperties, type ReactNode } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import {
  AlertCircle, AlertTriangle, ArrowDownRight, ArrowRight, ArrowUpRight, BarChart3, CheckCircle2, ClipboardCheck, ClipboardList,
  FileClock, FilePen, FileSearch, Gauge, Info, Layers, Loader2, PackageCheck, PackageOpen, Receipt, RefreshCw, Scale, ShieldCheck,
  ShoppingCart, Truck, Users, Wallet, type LucideIcon,
} from 'lucide-react';
import { Button } from '@/components/ui/button';
import { StatusPill } from '@/components/list';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { money } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';
import { cn } from '@/lib/utils';
import { useBranchOptions } from '@/features/finance/api';
import { useProcurementDashboard, useScorecards, type ProcurementDashboard } from '@/features/procurement/api';
import { useAuthStore } from '@/stores/auth.store';
import { PERIODS } from '@/pages/sales/reports/report-period';
import { compactMoney, useCountUp } from '@/pages/sales/dashboard/utils';
import { Panel, Reveal } from '@/pages/sales/dashboard/widgets';
import { CategoryTreemap, GradeBar, MoneyFlow, Ranked, SpendTrend, SupplierDonut } from './widgets';

const PERIOD_PILLS = ['this_month', 'this_quarter', 'this_year', 'last_12_months', 'last_year'];
const hairline = 'color-mix(in srgb, var(--sb-text) 16%, transparent)';
const GRADE_TONE = { A: 'success', B: 'info', C: 'warning', D: 'danger' } as const;

type Tone = 'bad' | 'warn' | 'good' | 'info';
const TONE: Record<Tone, { icon: LucideIcon; label: string; className: string }> = {
  bad: { icon: AlertCircle, label: 'Act now', className: 'bg-rose-100 text-rose-800 dark:bg-rose-500/15 dark:text-rose-300' },
  warn: { icon: AlertTriangle, label: 'Watch', className: 'bg-amber-100 text-amber-800 dark:bg-amber-500/15 dark:text-amber-300' },
  good: { icon: CheckCircle2, label: 'Good', className: 'bg-emerald-100 text-emerald-800 dark:bg-emerald-500/15 dark:text-emerald-300' },
  info: { icon: Info, label: 'Note', className: 'bg-muted text-muted-foreground' },
};

function greeting(): string {
  const h = new Date().getHours();
  return h < 12 ? tx('Good morning') : h < 18 ? tx('Good afternoon') : tx('Good evening');
}

const pctText = (v: string | null | undefined) => (v == null ? '—' : `${Math.round(Number(v))}%`);
const count = (n: number, one: string, many: string) => `${n} ${n === 1 ? one : many}`;

/** Plain-language reading of the numbers: only what the data shows, most urgent first. */
function buildInsights(d: ProcurementDashboard, fmt: (v: unknown) => string): Array<{ key: string; tone: Tone; text: string; to?: string }> {
  const out: Array<{ key: string; tone: Tone; text: string; to?: string }> = [];
  const k = d.kpis;
  const spend = Number(k.spend);
  if (spend <= 0) out.push({ key: 'spend', tone: 'info', text: tx('No purchase orders in this period.') });
  else if (k.spendChangePct != null) {
    const c = Number(k.spendChangePct);
    out.push({ key: 'spend', tone: c > 25 ? 'warn' : 'info', to: '/procurement/purchase-orders',
      text: c >= 0 ? `${tx('Purchasing spend is up')} ${Math.abs(c)}% ${tx('on the previous period.')}` : `${tx('Purchasing spend is down')} ${Math.abs(c)}% ${tx('on the previous period.')}` });
  }
  if (d.pipeline.toPay.overdueCount > 0) {
    out.push({ key: 'overdue', tone: 'bad', to: '/ap-aging', text: `${fmt(d.pipeline.toPay.overdue)} ${tx('of supplier bills is overdue across')} ${d.pipeline.toPay.overdueCount} ${tx('bills.')}` });
  } else if (Number(d.pipeline.toPay.value) > 0) {
    out.push({ key: 'overdue', tone: 'good', to: '/ap-aging', text: `${fmt(d.pipeline.toPay.value)} ${tx('owed to suppliers, and nothing is overdue.')}` });
  }
  if (d.pipeline.toReceive.late > 0) {
    out.push({ key: 'late', tone: 'warn', to: '/procurement/reports/open-orders', text: `${d.pipeline.toReceive.late} ${tx('open orders are past their delivery date.')}` });
  }
  if (k.onTimePct != null) {
    const v = Number(k.onTimePct);
    out.push({ key: 'otd', tone: v >= 90 ? 'good' : v >= 70 ? 'info' : 'warn', text: `${Math.round(v)}% ${tx('of delivered orders arrived on time')}${k.averageLeadDays ? `, ${tx('after')} ${k.averageLeadDays} ${tx('days on average')}` : ''}.` });
  }
  const top = d.bySupplier[0];
  if (spend > 0 && top?.label) {
    const share = Math.round((top.spend / spend) * 100);
    out.push({ key: 'concentration', tone: share >= 50 ? 'warn' : 'info', to: top.id ? `/procurement/suppliers/${top.id}` : undefined,
      text: `${top.label} ${tx('takes')} ${share}% ${tx('of spend')}${share >= 50 ? ` — ${tx('consider a second source.')}` : '.'}` });
  }
  if (Number(d.pipeline.toBill.value) > 0) {
    out.push({ key: 'grni', tone: 'info', to: '/procurement/grni', text: `${fmt(d.pipeline.toBill.value)} ${tx('of goods received is still waiting for the supplier bill.')}` });
  }
  const order: Tone[] = ['bad', 'warn', 'good', 'info'];
  return out.sort((a, b) => order.indexOf(a.tone) - order.indexOf(b.tone)).slice(0, 6);
}

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

function FlowStage({ step, last, icon: Icon, title, value, sub, to, foot, alert }: { step: number; last?: boolean; icon: LucideIcon; title: string; value: string; sub: string; to: string; foot?: string; alert?: boolean }) {
  return (
    <div className="relative">
      <Link to={to} className="block h-full rounded-xl border bg-background/60 p-4 transition-all hover:-translate-y-0.5 hover:border-primary/40 hover:shadow-md motion-reduce:hover:translate-y-0">
        <span className="flex items-center gap-2 text-xs font-medium text-muted-foreground">
          <span className="flex h-7 w-7 items-center justify-center rounded-lg bg-primary/10 text-primary"><Icon className="h-4 w-4" /></span>
          {step}. {title}
        </span>
        <span className="mt-3 block text-2xl font-semibold tabular-nums">{value}</span>
        <span className="block truncate text-xs text-muted-foreground">{sub}</span>
        {foot && <span className={cn('mt-2 block truncate border-t pt-2 text-xs', alert ? 'font-medium text-rose-600 dark:text-rose-400' : 'text-muted-foreground')}>{foot}</span>}
      </Link>
      {!last && <ArrowRight className="absolute -right-[18px] top-1/2 z-10 hidden h-4 w-4 -translate-y-1/2 text-muted-foreground xl:block" aria-hidden />}
    </div>
  );
}

const DEEPER: Array<{ to: string; icon: LucideIcon; title: string }> = [
  { to: '/procurement/reports/open-orders', icon: FileClock, title: 'Open orders' },
  { to: '/procurement/grni', icon: PackageOpen, title: 'Received, not billed' },
  { to: '/procurement/three-way-match', icon: Scale, title: 'Three-way match' },
  { to: '/ap-aging', icon: Wallet, title: 'Payables aging' },
  { to: '/procurement/commitments', icon: Layers, title: 'Commitments & budget' },
  { to: '/procurement/landed-cost-reports', icon: Truck, title: 'Landed cost reports' },
  { to: '/procurement/suppliers', icon: Users, title: 'Suppliers' },
  { to: '/procurement/rfqs', icon: FileSearch, title: 'Requests for quotation' },
  { to: '/procurement/inspections', icon: ClipboardCheck, title: 'Incoming inspections' },
  { to: '/procurement/reports/emergency', icon: AlertTriangle, title: 'Emergency purchases' },
];

/** Procurement → Analytics: one screen from the buyer to the finance manager; every number opens its list. */
export function ProcurementDashboardPage() {
  const [search, setSearch] = useSearchParams();
  const period = search.get('period') ?? 'this_year';
  const branchId = search.get('branchId') ?? '';
  const q = useProcurementDashboard(period, branchId);
  const { data: branches = [] } = useBranchOptions();
  const { data: cards = [] } = useScorecards();
  const user = useAuthStore((s) => s.user);
  const currency = useAuthStore((s) => s.organization?.currencyCode);
  const d = q.data;

  const spend = Number(d?.kpis.spend ?? 0);
  const counted = useCountUp(spend);
  const fmt = (v: unknown) => compactMoney(v, currency);
  const periodLabel = PERIODS.find(([k]) => k === period)?.[1] ?? '';
  const insights = useMemo(() => (d ? buildInsights(d, (v) => compactMoney(v, currency)) : []), [d, currency]);

  const setFilter = (k: string, v: string) => {
    const next = new URLSearchParams(search);
    if (v) next.set(k, v); else next.delete(k);
    setSearch(next, { replace: true });
  };

  const change = d?.kpis.spendChangePct == null ? null : Number(d.kpis.spendChangePct);
  const attention = d ? [
    { key: 'late', icon: Truck, label: tx('Late deliveries'), count: d.attention.lateDeliveries, note: tx('open orders past their date'), bad: true, to: '/procurement/reports/open-orders' },
    { key: 'overdue', icon: AlertCircle, label: tx('Overdue supplier bills'), count: d.attention.overdueBills, note: Number(d.pipeline.toPay.overdue) > 0 ? fmt(d.pipeline.toPay.overdue) : undefined, bad: true, to: '/ap-aging' },
    { key: 'approvals', icon: ShieldCheck, label: tx('Waiting for approval'), count: d.attention.pendingApprovals, note: tx('requests, orders, bills, payments'), bad: false, to: '/approvals' },
    { key: 'inspections', icon: ClipboardCheck, label: tx('Inspections to complete'), count: d.attention.pendingInspections, note: tx('goods held for quality'), bad: false, to: '/procurement/inspections' },
    { key: 'drafts', icon: FilePen, label: tx('Draft supplier bills'), count: d.attention.draftBills, note: tx('entered, not posted'), bad: false, to: '/vendor-bills' },
  ].sort((a, b) => Number(b.count > 0) - Number(a.count > 0)) : [];
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
                {tx('Procurement Dashboard')}
                {q.isFetching && <Loader2 className="h-4 w-4 animate-spin" style={{ color: 'var(--sb-text)' }} />}
              </h1>
              <p className="text-sm" style={{ color: 'var(--sb-text)' }}>{greeting()}{user?.firstName ? `, ${user.firstName}` : ''}. {tx('Here is where the purchasing money goes.')}</p>
            </div>
            <div className="flex flex-wrap items-center gap-2">
              <div className="flex flex-wrap rounded-lg border p-0.5" style={{ borderColor: hairline }} role="group" aria-label={tx('Period')}>
                {PERIOD_PILLS.map((k) => {
                  const on = period === k;
                  return (
                    <button key={k} type="button" aria-pressed={on} onClick={() => setFilter('period', k)}
                      className={cn('rounded-md px-2.5 py-1 text-xs font-medium transition-colors', !on && 'hover:bg-[var(--sb-hover)]')}
                      style={on ? { background: 'var(--sb-active-bar)', color: '#1f2023' } : { color: 'var(--sb-text)' }}>
                      {PERIODS.find(([p]) => p === k)?.[1]}
                    </button>
                  );
                })}
              </div>
              {branches.length > 0 && (
                <select aria-label={tx('Branch')} className="h-8 rounded-lg border bg-transparent px-2 text-xs" style={{ borderColor: hairline, color: 'var(--sb-text)' }} value={branchId} onChange={(e) => setFilter('branchId', e.target.value)}>
                  <option className="bg-card text-foreground" value="">{tx('All branches')}</option>
                  {branches.map((b) => <option className="bg-card text-foreground" key={b.id} value={b.id}>{b.name}</option>)}
                </select>
              )}
            </div>
          </div>

          {q.isError && !d ? (
            <div className="flex h-72 flex-col items-center justify-center gap-3 text-center">
              <AlertTriangle className="h-6 w-6" />
              <p className="text-sm">{tx('The purchasing figures could not be loaded.')}</p>
              <Button size="sm" variant="secondary" onClick={() => q.refetch()}><RefreshCw className="me-1.5 h-3.5 w-3.5" />{tx('Try again')}</Button>
            </div>
          ) : !d ? (
            <div className="flex h-72 items-center justify-center"><Loader2 className="h-6 w-6 animate-spin" /></div>
          ) : (
            <>
              <div className="mt-6 grid grid-cols-1 gap-6 lg:grid-cols-[minmax(0,5fr)_minmax(0,7fr)]">
                <div>
                  <p className="text-sm" style={{ color: 'var(--sb-text)' }}>{tx('Purchasing spend')} · {periodLabel}</p>
                  <p className="mt-1 text-5xl font-bold tracking-tight tabular-nums md:text-6xl" title={money(spend, currency)}>{fmt(counted)}</p>
                  <p className="mt-1 text-xs" style={{ color: 'var(--sb-text)' }}>{tx('Approved purchase orders, before tax')}</p>
                  <div className="mt-3 flex items-center gap-2 text-sm">
                    {change == null ? (
                      <span style={{ color: 'var(--sb-text)' }}>{tx('No previous period to compare with')}</span>
                    ) : (
                      <>
                        <span className="inline-flex items-center gap-1 rounded-full px-2 py-0.5 font-medium" style={{ background: hairline }}>
                          {change < 0 ? <ArrowDownRight className="h-3.5 w-3.5" /> : <ArrowUpRight className="h-3.5 w-3.5" />}{Math.abs(change)}%
                        </span>
                        <span style={{ color: 'var(--sb-text)' }}>{change < 0 ? tx('lower than the previous period') : tx('higher than the previous period')}</span>
                      </>
                    )}
                  </div>
                </div>
                <div>
                  <div className="mb-1 flex flex-wrap items-center justify-between gap-2 text-xs" style={{ color: 'var(--sb-text)' }}>
                    <span className="flex items-center gap-3">
                      <span className="inline-flex items-center gap-1.5"><span className="h-2.5 w-2.5 rounded-sm" style={{ background: 'var(--sb-active-bar)' }} />{tx('Ordered')}</span>
                      <span className="inline-flex items-center gap-1.5"><span className="h-0.5 w-3.5" style={{ background: 'var(--sb-active)' }} />{tx('Billed by suppliers')}</span>
                    </span>
                    <span>{tx('12-month trend')}</span>
                  </div>
                  <div className="h-44"><SpendTrend data={d.trend} currency={currency} /></div>
                </div>
              </div>
              <div className="mt-5 grid grid-cols-2 gap-3 lg:grid-cols-4">
                <HeroStat label={tx('Purchase orders')} value={String(d.kpis.orders)} sub={`${tx('previous period')}: ${d.kpis.previousOrders}`} to="/procurement/purchase-orders" />
                <HeroStat label={tx('Average order value')} value={fmt(d.kpis.averageOrder)} title={money(Number(d.kpis.averageOrder), currency)} sub={count(d.kpis.suppliers, tx('supplier used'), tx('suppliers used'))} to="/procurement/purchase-orders" />
                <HeroStat label={tx('On-time delivery')} value={pctText(d.kpis.onTimePct)} sub={d.kpis.inFullPct != null ? `${pctText(d.kpis.inFullPct)} ${tx('in full')}` : tx('No deliveries yet')} to="/procurement/goods-receipts">
                  {d.kpis.onTimePct != null && <Ring percent={Number(d.kpis.onTimePct)} />}
                </HeroStat>
                <HeroStat label={tx('Average lead time')} value={d.kpis.averageLeadDays != null ? `${d.kpis.averageLeadDays} ${tx('days')}` : '—'} sub={tx('approval to first delivery')} to="/procurement/suppliers" />
              </div>
            </>
          )}
        </section>
      </Reveal>

      {d && (
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
                  return <li key={n.key}>{n.to ? <Link to={n.to} className="block h-full rounded-xl border p-3.5 transition-colors hover:border-primary/40 hover:bg-muted/40">{body}</Link> : <div className="h-full rounded-xl border p-3.5">{body}</div>}</li>;
                })}
              </ul>
              {d.alerts.length > 0 && (
                <ul className="mt-4 space-y-1.5 border-t pt-3">
                  {d.alerts.slice(0, 4).map((a, i) => (
                    <li key={i}>
                      <Link to={a.link} className="flex items-center gap-2 text-xs text-muted-foreground hover:text-foreground">
                        {a.severity === 'info' ? <Info className="h-3.5 w-3.5 shrink-0" /> : <AlertTriangle className={cn('h-3.5 w-3.5 shrink-0', a.severity === 'danger' ? 'text-rose-600' : 'text-amber-600')} />}
                        <span className="truncate">{a.message}</span>
                      </Link>
                    </li>
                  ))}
                </ul>
              )}
            </Panel>
            <Panel title={tx('Needs attention')} hint={tx('Right now')}>
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
            <Panel title={tx('From request to payment')} hint={tx('Where purchasing stands right now')}>
              <div className="grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-5 xl:gap-5">
                <FlowStage step={1} icon={ClipboardList} title={tx('Open requests')} value={fmt(d.pipeline.requests.value)} sub={count(d.pipeline.requests.count, tx('request'), tx('requests'))} to="/procurement/purchase-requests" />
                <FlowStage step={2} icon={ShoppingCart} title={tx('Draft orders')} value={fmt(d.pipeline.drafts.value)} sub={count(d.pipeline.drafts.count, tx('order'), tx('orders'))} to="/procurement/purchase-orders" />
                <FlowStage step={3} icon={Truck} title={tx('To receive')} value={fmt(d.pipeline.toReceive.value)} sub={count(d.pipeline.toReceive.count, tx('open order'), tx('open orders'))} to="/procurement/reports/open-orders"
                  foot={d.pipeline.toReceive.late > 0 ? `${d.pipeline.toReceive.late} ${tx('late')}` : tx('Nothing late')} alert={d.pipeline.toReceive.late > 0} />
                <FlowStage step={4} icon={PackageCheck} title={tx('Received, not billed')} value={fmt(d.pipeline.toBill.value)} sub={count(d.pipeline.toBill.count, tx('receipt'), tx('receipts'))} to="/procurement/grni" />
                <FlowStage step={5} last icon={Receipt} title={tx('To pay')} value={fmt(d.pipeline.toPay.value)} sub={count(d.pipeline.toPay.count, tx('open bill'), tx('open bills'))} to="/ap-aging"
                  foot={`${fmt(d.pipeline.toPay.overdue)} ${tx('overdue')} · ${fmt(d.pipeline.toPay.dueNext7Days)} ${tx('due in 7 days')}`} alert={Number(d.pipeline.toPay.overdue) > 0} />
              </div>
            </Panel>
          </Reveal>

          <Reveal delay={200} className="grid grid-cols-1 gap-5 xl:grid-cols-3">
            <Panel title={tx('Spend by supplier')} hint={tx('Share of the period’s orders')} href="/procurement/suppliers">
              <SupplierDonut rows={d.bySupplier} total={spend} currency={currency} />
            </Panel>
            <Panel title={tx('Spend by product category')} hint={tx('Bigger and darker means more spend')}>
              <CategoryTreemap rows={d.byCategory} currency={currency} />
            </Panel>
            <Panel title={tx('Purchase to pay')} hint={`${periodLabel} · ${tx('ordered, received, billed, paid')}`}>
              <MoneyFlow currency={currency} steps={[
                { key: 'ordered', label: tx('Ordered'), value: spend, note: tx('approved orders, before tax'), to: '/procurement/purchase-orders' },
                { key: 'received', label: tx('Received'), value: Number(d.kpis.received), note: tx('posted goods receipts, at cost'), to: '/procurement/goods-receipts' },
                { key: 'billed', label: tx('Billed'), value: Number(d.kpis.billed), note: tx('posted supplier bills, with tax'), to: '/vendor-bills' },
                { key: 'paid', label: tx('Paid'), value: Number(d.kpis.paid), note: tx('supplier payments'), to: '/supplier-payments' },
              ]} />
            </Panel>
          </Reveal>

          <Reveal delay={260} className="grid grid-cols-1 gap-5 md:grid-cols-2 xl:grid-cols-3">
            <Ranked title={tx('Top suppliers')} hint={periodLabel} rows={d.bySupplier.slice(0, 6)} href="/procurement/suppliers" currency={currency}
              sub={(r) => `${count(r.orders, tx('order'), tx('orders'))} · ${spend > 0 ? Math.round((r.spend / spend) * 100) : 0}%`} rowHref={(r) => (r.id ? `/procurement/suppliers/${r.id}` : undefined)} />
            <Ranked title={tx('Top products bought')} hint={periodLabel} rows={d.topProducts.slice(0, 6)} currency={currency}
              sub={(r) => `${Number(r.quantity).toLocaleString()} ${tx('units')}`} />
            <Panel title={tx('Supplier performance')} hint={`${d.suppliers.count} ${tx('suppliers scored')}${d.suppliers.averageScore != null ? ` · ${tx('average')} ${Math.round(d.suppliers.averageScore)}/100` : ''}`}>
              <GradeBar grades={d.suppliers.grades} />
              {d.suppliers.best.length > 0 && (
                <div className="mt-4">
                  <p className="mb-1.5 flex items-center gap-1.5 text-xs font-medium text-muted-foreground"><Gauge className="h-3.5 w-3.5" />{tx('Best scores')}</p>
                  <ul className="space-y-1.5">
                    {d.suppliers.best.slice(0, 3).map((c) => (
                      <li key={c.partnerId}><Link to={`/procurement/suppliers/${c.partnerId}`} className="flex items-center gap-2 text-sm hover:underline">
                        <span className="min-w-0 flex-1 truncate">{c.partnerName}</span>
                        <StatusPill tone={GRADE_TONE[c.grade]}>{c.grade} · {Math.round(c.score)}</StatusPill>
                      </Link></li>
                    ))}
                  </ul>
                </div>
              )}
              {d.suppliers.weakest.length > 0 && (
                <div className="mt-4">
                  <p className="mb-1.5 flex items-center gap-1.5 text-xs font-medium text-muted-foreground"><AlertTriangle className="h-3.5 w-3.5" />{tx('Needs a conversation')}</p>
                  <ul className="space-y-1.5">
                    {d.suppliers.weakest.slice(0, 3).map((c) => (
                      <li key={c.partnerId}><Link to={`/procurement/suppliers/${c.partnerId}`} className="flex items-center gap-2 text-sm hover:underline">
                        <span className="min-w-0 flex-1 truncate">{c.partnerName}</span>
                        <span className="text-xs text-muted-foreground">{pctText(c.onTimePct)} {tx('on time')}</span>
                        <StatusPill tone={GRADE_TONE[c.grade]}>{c.grade} · {Math.round(c.score)}</StatusPill>
                      </Link></li>
                    ))}
                  </ul>
                </div>
              )}
            </Panel>
          </Reveal>

          {d.byBranch.length > 1 && (
            <Reveal delay={300} className="grid grid-cols-1 gap-5">
              <Ranked title={tx('Spend by branch')} hint={periodLabel} rows={d.byBranch} currency={currency}
                sub={(r) => count(r.orders, tx('order'), tx('orders'))} />
            </Reveal>
          )}

          <Reveal delay={320}>
            <Panel title={tx('Supplier scorecards')} hint={tx('Last 12 months · on time, in full, quality and claims')}>
              <div className="-mx-5 overflow-x-auto">
                <Table>
                  <TableHeader><TableRow>
                    <TableHead>{tx('Supplier')}</TableHead><TableHead className="text-right">{tx('Orders')}</TableHead>
                    <TableHead className="text-right">{tx('Spend')}</TableHead><TableHead className="text-right">{tx('On time')}</TableHead>
                    <TableHead className="text-right">{tx('In full')}</TableHead><TableHead className="text-right">{tx('Rejected')}</TableHead>
                    <TableHead className="text-right">{tx('Claims')}</TableHead><TableHead className="text-right">{tx('Lead time (days)')}</TableHead>
                    <TableHead className="text-right">{tx('Score')}</TableHead>
                  </TableRow></TableHeader>
                  <TableBody>
                    {!cards.length && <TableRow><TableCell colSpan={9} className="py-8 text-center text-muted-foreground">{tx('No orders in the last 12 months.')}</TableCell></TableRow>}
                    {cards.map((c) => (
                      <TableRow key={c.partnerId}>
                        <TableCell><Link className="hover:underline" to={`/procurement/suppliers/${c.partnerId}`}>{c.partnerName}</Link></TableCell>
                        <TableCell className="text-right tabular-nums">{c.orders}</TableCell>
                        <TableCell className="text-right tabular-nums">{money(c.spend, currency)}</TableCell>
                        <TableCell className="text-right tabular-nums">{pctText(c.onTimePct)}</TableCell>
                        <TableCell className="text-right tabular-nums">{pctText(c.inFullPct)}</TableCell>
                        <TableCell className="text-right tabular-nums">{pctText(c.rejectPct)}</TableCell>
                        <TableCell className="text-right tabular-nums">{c.claims}</TableCell>
                        <TableCell className="text-right tabular-nums">{c.averageLeadDays ?? '—'}</TableCell>
                        <TableCell className="text-right"><StatusPill tone={GRADE_TONE[c.grade]}>{c.grade} · {c.score.toFixed(0)}</StatusPill></TableCell>
                      </TableRow>
                    ))}
                  </TableBody>
                </Table>
              </div>
            </Panel>
          </Reveal>

          <Reveal delay={360}>
            <Panel title={tx('Go deeper')} hint={tx('Purchasing reports and work lists')}>
              <div className="grid grid-cols-2 gap-2 md:grid-cols-3 xl:grid-cols-5">
                {DEEPER.map((p) => (
                  <Link key={p.to} to={p.to} className="group flex items-center gap-2.5 rounded-xl border p-2.5 text-sm font-medium transition-colors hover:border-primary/40 hover:bg-primary/5">
                    <span className="flex h-8 w-8 shrink-0 items-center justify-center rounded-lg bg-primary/10 text-primary"><p.icon className="h-4 w-4" /></span>
                    <span className="min-w-0 truncate group-hover:text-primary">{tx(p.title)}</span>
                  </Link>
                ))}
              </div>
            </Panel>
          </Reveal>
        </>
      )}
      {!d && !q.isError && <span className="sr-only"><BarChart3 /></span>}
    </div>
  );
}

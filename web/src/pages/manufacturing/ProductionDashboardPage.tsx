import { useMemo } from 'react';
import { Link } from 'react-router-dom';
import { AlertOctagon, CirclePause, Factory, Moon, Play } from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { date, money, useOrgCurrency } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';
import { cn } from '@/lib/utils';
import { compactMoney } from '@/pages/sales/dashboard/utils';
import { compactNumber } from '@/pages/sales/reports/report-format';
import { useOps } from '@/components/ops-dashboard/api';
import { CalendarHeatmap, Donut, Empty, FlagPill, Gauge, RankedBars, SegmentBar, STATUS, TrendChart, type RankRow } from '@/components/ops-dashboard/charts';
import { Brief, KpiCard, num, numOrNull, Panel, PeriodSelect, pctText, ProgressCell, qtyText, Reveal, type BriefItem } from '@/components/ops-dashboard/kit';
import { Button } from '@/components/ui/button';
import { StatusPill, type Tone } from '@/components/list';
import { clampDays, isoDate, rollUp, usePeriod } from '@/components/ops-dashboard/period';
import type { Board, FloorStatus, OeeReport } from '@/features/manufacturing/api-shopfloor';
import type { ProductionOrder } from '@/features/manufacturing/types';
import type { StageWipReport } from '@/features/manufacturing/api-stages';

const REPORT = PERMISSIONS.production.report;

interface Kpis { orders: number; plannedQty: string; producedQty: string; scrapQty: string; yieldPct: number | null; wastePct: number | null; totalCost: string; avgCostPerBatch: string; wasteByReason: Array<{ reason: string; qty: string }> }
interface TrendRow { date: string; orders: number; produced: string; scrap: string; cost: string }
interface Usage { productId: string; name: string; qtyConsumed: string; cost: string }
interface CostSummary { rows: Array<{ key: string; label: string; quantity: string; cost: string; unitCost: string | null }>; totals: { cost: string } }
interface Breakage { byReason: Array<{ productId: string; productName: string; stage: string; reasonCode: string; reasonName: string; quantity: string; postings: number }> }

const toPct = (v: unknown) => { const x = numOrNull(v); return x == null ? null : x * 100; };

const FLOOR_TONE: Record<FloorStatus, Tone> = { running: 'success', paused: 'warning', down: 'danger', idle: 'neutral' };
const ORDER_TONE: Record<string, Tone> = { draft: 'neutral', confirmed: 'info', in_progress: 'info', qc_hold: 'warning', completed: 'success', cancelled: 'neutral' };
const ORDER_LABEL: Record<string, () => string> = {
  draft: () => tx('Draft'), confirmed: () => tx('Confirmed'), in_progress: () => tx('In progress'), qc_hold: () => tx('QC hold'), completed: () => tx('Completed'), cancelled: () => tx('Cancelled'),
};

/** A small trend line for a headline card. */
function Sparkline({ values }: { values: number[] }) {
  if (values.length < 2 || values.every((v) => v === 0)) return null;
  const max = Math.max(...values) || 1;
  const points = values.map((v, i) => `${(i / (values.length - 1)) * 100},${28 - (v / max) * 24}`).join(' ');
  return (
    <svg viewBox="0 0 100 30" preserveAspectRatio="none" className="h-8 w-full text-primary" aria-hidden>
      <polygon points={`0,30 ${points} 100,30`} fill="currentColor" fillOpacity={0.12} />
      <polyline points={points} fill="none" stroke="currentColor" strokeWidth="1.5" vectorEffect="non-scaling-stroke" />
    </svg>
  );
}

const FLOOR: Record<FloorStatus, { label: () => string; icon: typeof Play; tile: string; dot: string }> = {
  running: { label: () => tx('Running'), icon: Play, tile: 'border-emerald-500/50 bg-emerald-500/10', dot: 'text-emerald-600 dark:text-emerald-400' },
  paused: { label: () => tx('Paused'), icon: CirclePause, tile: 'border-amber-500/50 bg-amber-500/10', dot: 'text-amber-600 dark:text-amber-400' },
  down: { label: () => tx('Down'), icon: AlertOctagon, tile: 'border-rose-500/60 bg-rose-500/10', dot: 'text-rose-600 dark:text-rose-400' },
  idle: { label: () => tx('Idle'), icon: Moon, tile: 'bg-muted/40', dot: 'text-muted-foreground' },
};

/** Production dashboard: cost and yield over time, the floor right now, equipment effectiveness, and what the money buys. */
export function ProductionDashboardPage() {
  const currency = useOrgCurrency();
  const { period, setPeriod } = usePeriod();
  const range = { from: period.from, to: period.to };
  // These reports compare completedAt with the bound as an instant: a bare date would cut off the last day.
  const stamped = { from: `${period.from}T00:00:00.000Z`, to: `${period.to}T23:59:59.999Z` };
  const cmoney = (v: unknown) => compactMoney(v, currency);

  const orders = useOps<ProductionOrder[]>('/manufacturing/production-orders', undefined, PERMISSIONS.productionOrder.read);
  const kpis = useOps<Kpis>('/manufacturing/reports/kpis', stamped, REPORT);
  const trends = useOps<TrendRow[]>('/manufacturing/reports/trends', stamped, REPORT);
  const usage = useOps<Usage[]>('/manufacturing/reports/ingredient-usage', stamped, REPORT);
  const costs = useOps<CostSummary>('/manufacturing/costing/reports/summary', range, REPORT);
  const breakage = useOps<Breakage>('/manufacturing/precast/reports/breakage', range, REPORT);
  const wip = useOps<StageWipReport>('/manufacturing/reports/stage-wip', undefined, REPORT);
  const oeeRange = clampDays(period, 93);
  const oee = useOps<OeeReport>('/manufacturing/shop-floor/oee', { from: oeeRange.from, to: oeeRange.to }, REPORT);
  const board = useOps<Board>('/manufacturing/shop-floor/dashboard', undefined, PERMISSIONS.workOrder.read, 60_000);

  const processing = useOps<{ stockByStage: Array<{ stage: string | null; value: string }> }>('/manufacturing/processing/dashboard', range, PERMISSIONS.processing.read);
  const canReport = kpis.allowed;
  const k = kpis.data;
  const list = orders.data ?? [];
  const today = isoDate(new Date());

  const stat = useMemo(() => {
    const by = (s: string) => list.filter((o) => o.status === s);
    const inPeriod = (d: string | null) => !!d && d.slice(0, 10) >= period.from && d.slice(0, 10) <= period.to;
    return {
      draft: by('draft').length,
      confirmed: by('confirmed').length,
      inProgress: by('in_progress').length,
      qcHold: by('qc_hold').length,
      completed: by('completed').filter((o) => inPeriod(o.completedAt)).length,
      completedToday: by('completed').filter((o) => o.completedAt?.slice(0, 10) === today).length,
      active: [...by('in_progress'), ...by('qc_hold'), ...by('confirmed')],
      late: [...by('in_progress'), ...by('confirmed')].filter((o) => o.scheduledFor && o.scheduledFor.slice(0, 10) < today).length,
    };
  }, [orders.data, period, today]); // eslint-disable-line react-hooks/exhaustive-deps

  const costTrend = useMemo(() => rollUp(trends.data ?? [], (r) => r.date, { cost: (r) => num(r.cost) }, period, period.grain), [trends.data, period]);
  const heat = useMemo(() => Object.fromEntries((trends.data ?? []).map((r) => [r.date.slice(0, 10), r.orders])), [trends.data]);

  const rhythm = useMemo(() => {
    const counts = Object.values(heat).filter((v) => v > 0);
    const total = counts.reduce((s, v) => s + v, 0);
    return { days: counts.length, avg: counts.length ? (total / counts.length).toFixed(1) : '—', peak: counts.length ? Math.max(...counts) : '—' };
  }, [heat]);

  const toProduce = stat.active.reduce((sum, o) => sum + Math.max(0, num(o.plannedQty) - num(o.producedQty)), 0);

  const pipeline = [
    { key: 'draft', label: tx('Draft'), value: stat.draft, color: '#86b6ef', ink: '#0d2747', href: '/manufacturing/orders' },
    { key: 'confirmed', label: tx('Confirmed'), value: stat.confirmed, color: '#3987e5', href: '/manufacturing/orders' },
    { key: 'in_progress', label: tx('In progress'), value: stat.inProgress, color: '#1c5cab', href: '/manufacturing/orders' },
    { key: 'qc_hold', label: tx('QC hold'), value: stat.qcHold, color: STATUS.warning, ink: '#0b0b0b', href: '/manufacturing/orders' },
    { key: 'completed', label: tx('Completed in period'), value: stat.completed, color: '#0d366b', href: '/manufacturing/orders' },
  ];

  const productRows: RankRow[] = (costs.data?.rows ?? []).map((r) => ({
    key: r.key, label: r.label, value: num(r.cost), display: cmoney(r.cost),
    sub: tx('{{q}} produced', { q: compactNumber(num(r.quantity)) }),
    note: numOrNull(r.unitCost) != null ? tx('{{c}} per unit', { c: compactNumber(num(r.unitCost)) }) : undefined,
  })).sort((a, b) => b.value - a.value);

  const materials = (usage.data ?? []).map((u) => ({ key: u.productId, label: u.name, value: num(u.cost) }));

  const waste = useMemo(() => {
    const m = new Map<string, { label: string; value: number; items: Set<string> }>();
    for (const r of breakage.data?.byReason ?? []) {
      const g = m.get(r.reasonCode) ?? { label: r.reasonName, value: 0, items: new Set<string>() };
      g.value += num(r.quantity);
      g.items.add(r.productName);
      m.set(r.reasonCode, g);
    }
    for (const r of k?.wasteByReason ?? []) {
      const g = m.get(r.reason) ?? { label: r.reason.replace(/_/g, ' '), value: 0, items: new Set<string>() };
      g.value += num(r.qty);
      m.set(r.reason, g);
    }
    return [...m.entries()].map(([key, g]) => ({ key, label: g.label, value: g.value, display: qtyText(g.value, 1), sub: [...g.items].join(', ') })).sort((a, b) => b.value - a.value);
  }, [breakage.data, k]);

  const centres: RankRow[] = (oee.data?.workCenters ?? []).filter((w) => numOrNull(w.oee) != null).map((w) => {
    const v = toPct(w.oee) ?? 0;
    return {
      key: w.workCenterId, label: w.workCenterName, value: v, display: pctText(v), sub: w.shifts === 1 ? tx('1 shift') : tx('{{count}} shifts', { count: w.shifts }),
      note: tx('{{m}} min down', { m: qtyText(w.downtimeMinutes) }), flag: v < 60 ? { tone: 'critical' as const, label: tx('Low') } : v < 85 ? { tone: 'warning' as const, label: tx('Watch') } : undefined,
    };
  }).sort((a, b) => a.value - b.value);

  const stockByStage = (processing.data?.stockByStage ?? []).map((r) => ({
    key: r.stage ?? 'other', value: num(r.value),
    label: r.stage === 'raw' ? tx('Raw materials') : r.stage === 'semi_finished' ? tx('Semi-finished') : r.stage === 'finished' ? tx('Finished goods') : tx('Other lots'),
  }));
  const wipTotals = wip.data?.totals;
  const drift = num(wipTotals?.drift);
  const oeeTotal = toPct(oee.data?.total.oee);
  const b = board.data;
  const pendingDrafts = (b?.workCenters ?? []).reduce((s, w) => s + w.pendingDrafts, 0);

  const downCount = b?.totals.down ?? 0;
  const brief: BriefItem[] = [];
  if (downCount > 0) brief.push({ key: 'down', tone: 'bad', text: (downCount === 1 ? tx('{{count}} work centre is down right now.', { count: downCount }) : tx('{{count}} work centres are down right now.', { count: downCount })), to: '/manufacturing/floor-board' });
  if (wipTotals && !wipTotals.balanced) brief.push({ key: 'wip', tone: 'bad', text: tx('Work in progress differs from the ledger by {{value}}.', { value: money(drift, currency) }), to: '/manufacturing/stage-wip' });
  if (stat.late > 0) brief.push({ key: 'late', tone: 'warn', text: (stat.late === 1 ? tx('{{count}} active order is past its scheduled date.', { count: stat.late }) : tx('{{count}} active orders are past their scheduled date.', { count: stat.late })), to: '/manufacturing/orders' });
  if (stat.qcHold > 0) brief.push({ key: 'qc', tone: 'warn', text: (stat.qcHold === 1 ? tx('{{count}} order is held for quality checks.', { count: stat.qcHold }) : tx('{{count}} orders are held for quality checks.', { count: stat.qcHold })), to: '/manufacturing/orders' });
  if (pendingDrafts > 0) brief.push({ key: 'drafts', tone: 'warn', text: (pendingDrafts === 1 ? tx('{{count}} floor report is waiting for confirmation.', { count: pendingDrafts }) : tx('{{count}} floor reports are waiting for confirmation.', { count: pendingDrafts })), to: '/manufacturing/shift-reports' });
  if (oeeTotal != null && oeeTotal < 60) brief.push({ key: 'oee', tone: 'warn', text: tx('Equipment effectiveness is low at {{pct}}.', { pct: pctText(oeeTotal) }), to: '/manufacturing/oee' });
  if (k && (k.wastePct ?? 0) >= 3) brief.push({ key: 'waste', tone: 'warn', text: tx('Scrap is {{pct}} of planned output.', { pct: pctText(k.wastePct) }) });
  if (k && (k.yieldPct ?? 0) >= 98) brief.push({ key: 'yield', tone: 'good', text: tx('Yield is healthy at {{pct}} across {{count}} orders.', { pct: pctText(k.yieldPct), count: k.orders }) });

  return (
    <div className="space-y-5">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div className="flex items-center gap-3">
          <span className="grid h-10 w-10 place-items-center rounded-xl bg-primary/10 text-primary"><Factory className="h-5 w-5" aria-hidden /></span>
          <div>
            <h1 className="text-xl font-semibold">{tx('Production Dashboard')}</h1>
            <p className="text-sm text-muted-foreground">{tx('Today\'s floor at a glance, and what production is costing.')}</p>
          </div>
        </div>
        <div className="flex flex-wrap items-center gap-2">
          <PeriodSelect value={period.key} onChange={setPeriod} />
          <Button asChild variant="outline"><Link to="/manufacturing/boms">{tx('Recipes')}</Link></Button>
          <Button asChild><Link to="/manufacturing/orders">{tx('Orders')}</Link></Button>
        </div>
      </div>

      <Reveal className="grid gap-4 sm:grid-cols-2 xl:grid-cols-5">
        <KpiCard label={tx('Production orders')} value={qtyText(stat.inProgress)} sub={tx('In progress · {{count}} confirmed', { count: stat.confirmed })} to="/manufacturing/orders"
          chart={canReport ? <Sparkline values={costTrend.map((r) => num(r.cost))} /> : undefined} loading={orders.isLoading} />
        <KpiCard label={tx('To produce')} value={qtyText(toProduce)} sub={tx('Units on open orders')} to="/manufacturing/orders" loading={orders.isLoading} />
        <KpiCard label={tx('Completed')} value={qtyText(stat.completed)} sub={tx('{{count}} today', { count: stat.completedToday })} to="/manufacturing/orders" loading={orders.isLoading} />
        {canReport && <KpiCard label={tx('Yield')} value={pctText(k?.yieldPct)} sub={tx('Produced vs planned')} ring={k?.yieldPct ?? null} loading={kpis.isLoading} />}
        {canReport && <KpiCard label={tx('OEE')} value={pctText(oeeTotal)} sub={tx('Target 85%')} ring={oeeTotal} to="/manufacturing/oee" loading={oee.isLoading} />}
      </Reveal>

      <Reveal delay={40} className="grid gap-5 xl:grid-cols-5">
        <Panel className="xl:col-span-3" title={tx('Production orders')} hint={tx('Open now')} href="/manufacturing/orders" action={tx('View all')}>
          {stat.active.length === 0 ? <Empty>{tx('Nothing on the floor right now.')}</Empty> : (
            <div className="overflow-x-auto">
              <table className="w-full text-sm">
                <thead className="text-left text-xs text-muted-foreground"><tr><th className="pb-2 pr-3 font-medium">{tx('Order')}</th><th className="pb-2 pr-3 font-medium">{tx('Batch')}</th><th className="pb-2 pr-3 text-right font-medium">{tx('Quantity')}</th><th className="pb-2 pr-3 font-medium">{tx('Status')}</th><th className="w-40 pb-2 pr-3 font-medium">{tx('Progress')}</th><th className="pb-2 text-right font-medium">{tx('Expected')}</th></tr></thead>
                <tbody className="divide-y">
                  {stat.active.slice(0, 6).map((o) => {
                    const planned = num(o.plannedQty) || 1;
                    const late = (o.status === 'in_progress' || o.status === 'confirmed') && !!o.scheduledFor && o.scheduledFor.slice(0, 10) < today;
                    return (
                      <tr key={o.id} className="hover:bg-muted/40">
                        <td className="whitespace-nowrap py-2.5 pr-3"><Link className="font-medium text-primary hover:underline" to={`/manufacturing/orders/${o.id}`}>{o.orderCode}</Link></td>
                        <td className="max-w-48 truncate pr-3 font-mono text-xs">{o.batchNumber ?? '—'}</td>
                        <td className="whitespace-nowrap pr-3 text-right tabular-nums">{qtyText(num(o.plannedQty))}</td>
                        <td className="pr-3"><StatusPill tone={ORDER_TONE[o.status] ?? 'neutral'}>{ORDER_LABEL[o.status]?.() ?? o.status}</StatusPill></td>
                        <td className="pr-3"><ProgressCell percent={(num(o.producedQty) / planned) * 100} /></td>
                        <td className="whitespace-nowrap text-right text-xs">{o.scheduledFor ? date(o.scheduledFor) : '—'}{late && <span className="ml-1.5"><FlagPill tone="warning">{tx('Late')}</FlagPill></span>}</td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>
          )}
        </Panel>
        <Panel className="xl:col-span-2" title={tx('Work centres')} hint={tx('Status now, effectiveness in the period')} href="/manufacturing/floor-board" action={tx('View all')}>
          {(b?.workCenters ?? []).length === 0 ? <Empty>{tx('No work centres set up.')}</Empty> : (
            <div className="overflow-x-auto">
              <table className="w-full text-sm">
                <thead className="text-left text-xs text-muted-foreground"><tr><th className="pb-2 pr-3 font-medium">{tx('Work centre')}</th><th className="pb-2 pr-3 font-medium">{tx('Status')}</th><th className="w-36 pb-2 font-medium">{tx('OEE')}</th></tr></thead>
                <tbody className="divide-y">
                  {(b?.workCenters ?? []).slice(0, 6).map((w) => {
                    const effect = toPct(oee.data?.workCenters.find((x) => x.workCenterId === w.workCenterId)?.oee);
                    return (
                      <tr key={w.workCenterId} className="hover:bg-muted/40">
                        <td className="max-w-40 truncate py-2.5 pr-3" title={w.name}>{w.name}</td>
                        <td className="pr-3"><StatusPill tone={FLOOR_TONE[w.status]}>{FLOOR[w.status].label()}</StatusPill></td>
                        <td><ProgressCell percent={effect} tone={effect == null ? 'bg-muted' : effect >= 85 ? 'bg-emerald-500' : effect >= 60 ? 'bg-amber-500' : 'bg-rose-500'} /></td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>
          )}
        </Panel>
      </Reveal>

      {canReport && (
        <Reveal delay={60} className="grid gap-5 lg:grid-cols-3">
          <Panel title={tx('Value on hand')} hint={tx('Lots by stage and work in progress')} href="/manufacturing/processing/stock" action={tx('Stock by stage')}>
            <dl className="divide-y text-sm">
              {stockByStage.map((r) => (
                <div key={r.key} className="flex items-baseline justify-between gap-3 py-2.5"><dt className="font-medium">{r.label}</dt><dd className="tabular-nums" title={money(r.value, currency)}>{cmoney(r.value)}</dd></div>
              ))}
              <div className="flex items-baseline justify-between gap-3 py-2.5"><dt className="font-medium">{tx('Work in progress')}</dt><dd className="tabular-nums" title={money(wipTotals?.expectedWip, currency)}>{cmoney(wipTotals?.expectedWip)}</dd></div>
            </dl>
          </Panel>
          <Panel title={tx('Production analysis')} hint={tx('Cost of production in the period')} href="/manufacturing/reports" action={tx('Reports')}>
            <TrendChart kind="area" height={190} data={costTrend} format={(v) => cmoney(v)} axisFormat={compactNumber} series={[{ key: 'cost', label: tx('Cost') }]} />
          </Panel>
          <Panel title={tx('Top products')} hint={tx('By production cost')} href="/manufacturing/costing" action={tx('Costing')}>
            <Donut rows={productRows.slice(0, 5).map((r) => ({ key: r.key, label: r.label, value: r.value }))} format={cmoney} size={140} centerSub={tx('cost')} stacked />
          </Panel>
        </Reveal>
      )}

      <Reveal delay={80} className="grid gap-5 lg:grid-cols-2">
        <Brief items={brief} />
        {canReport && (
          <Panel title={tx('Equipment effectiveness')} hint={oeeRange.clipped ? tx('Latest 93 days. World class is 85%.') : tx('World class is 85%.')} href="/manufacturing/oee" action={tx('Report')}>
            <div className="grid grid-cols-2 gap-2 sm:grid-cols-4">
              <Gauge label={tx('OEE')} value={oeeTotal} threshold={{ good: 85, warn: 60 }} size={104} />
              <Gauge label={tx('Availability')} value={toPct(oee.data?.total.availability)} threshold={{ good: 90, warn: 75 }} size={104} />
              <Gauge label={tx('Performance')} value={toPct(oee.data?.total.performance)} threshold={{ good: 95, warn: 75 }} size={104} />
              <Gauge label={tx('Quality')} value={toPct(oee.data?.total.quality)} threshold={{ good: 99, warn: 95 }} size={104} />
            </div>
          </Panel>
        )}
      </Reveal>

      {board.allowed && (
        <Reveal delay={140}>
          <Panel title={tx('The floor right now')} hint={b?.currentShift ? tx('Shift in progress · refreshes every minute') : tx('Refreshes every minute')} href="/manufacturing/floor-board" action={tx('Floor board')}>
            {!b || b.workCenters.length === 0 ? <Empty>{tx('No work centres set up.')}</Empty> : (
              <ul className="grid grid-cols-2 gap-2 sm:grid-cols-3 lg:grid-cols-4 xl:grid-cols-6">
                {b.workCenters.map((w) => {
                  const f = FLOOR[w.status];
                  const Icon = f.icon;
                  return (
                    <li key={w.workCenterId}>
                      <Link to="/manufacturing/floor-board" className={cn('block h-full rounded-xl border p-2.5 transition-transform hover:-translate-y-0.5 motion-reduce:hover:translate-y-0', f.tile)}>
                        <span className={cn('flex items-center gap-1.5 text-[11px] font-semibold', f.dot)}><Icon className={cn('h-3.5 w-3.5', w.status === 'running' && 'animate-pulse motion-reduce:animate-none')} aria-hidden />{f.label()}</span>
                        <span className="mt-1 block truncate text-sm font-medium" title={w.name}>{w.name}</span>
                        <span className="block truncate text-[11px] text-muted-foreground">{w.workOrders[0] ? `${w.workOrders[0].orderCode}${w.workOrders[0].productName ? ` · ${w.workOrders[0].productName}` : ''}` : w.code}</span>
                      </Link>
                    </li>
                  );
                })}
              </ul>
            )}
          </Panel>
        </Reveal>
      )}

      <Reveal delay={200} className="grid gap-5 lg:grid-cols-2">
        <Panel title={tx('Order pipeline')} hint={tx('Open orders now; completed within the period')} href="/manufacturing/orders" action={tx('Orders')}>
          <SegmentBar rows={pipeline} />
        </Panel>
        {canReport && (
          <Panel title={tx('Production rhythm')} hint={tx('Orders completed each day')}>
            <CalendarHeatmap from={period.from} to={period.to} values={heat} unit={tx('orders')} />
            <dl className="mt-4 grid grid-cols-3 gap-2 border-t pt-4 text-center">
              <div><dd className="text-xl font-semibold">{rhythm.days}</dd><dt className="text-[11px] text-muted-foreground">{tx('Days with output')}</dt></div>
              <div><dd className="text-xl font-semibold">{rhythm.avg}</dd><dt className="text-[11px] text-muted-foreground">{tx('Orders per active day')}</dt></div>
              <div><dd className="text-xl font-semibold">{rhythm.peak}</dd><dt className="text-[11px] text-muted-foreground">{tx('Busiest day')}</dt></div>
            </dl>
          </Panel>
        )}
      </Reveal>

      {canReport && (
        <Reveal delay={260} className="grid gap-5 lg:grid-cols-3">
          <Panel title={tx('Cost by product')} hint={tx('Highest first')} href="/manufacturing/costing" action={tx('Costing')}>
            <RankedBars rows={productRows} limit={6} />
          </Panel>
          <Panel title={tx('Materials consumed')} hint={tx('By cost')}>
            <Donut rows={materials} format={cmoney} size={150} centerSub={tx('materials')} stacked />
          </Panel>
          <Panel title={tx('Equipment effectiveness by work centre')} hint={tx('Lowest first')} href="/manufacturing/oee" action={tx('Report')}>
            <RankedBars rows={centres} max={100} limit={6} />
          </Panel>
        </Reveal>
      )}

      {canReport && (
        <Reveal delay={300} className="grid gap-5 lg:grid-cols-2">
          <Panel title={tx('Waste by reason')} hint={tx('Pieces or stock units, as recorded')}>
            <RankedBars rows={waste} limit={6} />
          </Panel>
          <Panel title={tx('Work in progress')} hint={tx('Value still on the floor, against the ledger')} href="/manufacturing/stage-wip" action={tx('Report')}>
            {!wipTotals ? <Empty /> : (
              <>
                <div className="mb-3 flex flex-wrap items-baseline justify-between gap-2">
                  <span className="text-2xl font-semibold" title={money(wipTotals.expectedWip, currency)}>{cmoney(wipTotals.expectedWip)}</span>
                  {wipTotals.balanced ? <FlagPill tone="good">{tx('Matches the ledger')}</FlagPill> : <FlagPill tone="critical">{tx('Differs by {{value}}', { value: cmoney(drift) })}</FlagPill>}
                </div>
                <SegmentBar format={(v) => cmoney(v)} rows={[
                  { key: 'stage', label: tx('Staged orders'), value: num(wipTotals.stageWip), color: '#1c5cab' },
                  { key: 'legacy', label: tx('Single-stage orders'), value: num(wipTotals.legacyOrdersWip), color: '#3987e5' },
                  { key: 'process', label: tx('Process costing'), value: num(wipTotals.processCostingWip), color: '#86b6ef', ink: '#0d2747' },
                ]} />
              </>
            )}
          </Panel>
        </Reveal>
      )}
    </div>
  );
}

export default ProductionDashboardPage;

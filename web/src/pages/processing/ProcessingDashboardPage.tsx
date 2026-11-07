import { Link } from 'react-router-dom';
import { Plus, Workflow } from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { Button } from '@/components/ui/button';
import { useAuthStore } from '@/stores/auth.store';
import { money, useOrgCurrency, date } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';
import { compactMoney } from '@/pages/sales/dashboard/utils';
import { Donut, Empty, RankedBars, type RankRow } from '@/components/ops-dashboard/charts';
import { KpiCard, Panel, PeriodSelect, Reveal, num, pctText } from '@/components/ops-dashboard/kit';
import { usePeriod } from '@/components/ops-dashboard/period';
import { STAGE_LABEL, useProcessingDashboard } from '@/features/processing/api';
import { JobStatusPill, qty } from './processing-ui';

/** The whole chain for management: stock and profit by stage, what is in process, and yield by step. */
export function ProcessingDashboardPage() {
  const has = useAuthStore((s) => s.hasPermission);
  const currency = useOrgCurrency();
  const { period, setPeriod } = usePeriod();
  const { data: d, isLoading, isError } = useProcessingDashboard(period.from, period.to);
  const cmoney = (v: unknown) => compactMoney(v, currency);
  const jobCount = (status: string) => d?.jobs.find((j) => j.status === status)?.count ?? 0;
  const margin = d?.margin ?? [];
  const revenue = margin.reduce((s, r) => s + num(r.revenue), 0);
  const cogs = margin.reduce((s, r) => s + num(r.cogs), 0);
  const profit = revenue - cogs;
  const stockValue = (d?.stockByStage ?? []).reduce((s, r) => s + num(r.value), 0);
  const stage = new Map<string, { revenue: number; cogs: number }>();
  margin.forEach((r) => {
    const key = r.stage ?? 'other';
    const v = stage.get(key) ?? { revenue: 0, cogs: 0 };
    stage.set(key, { revenue: v.revenue + num(r.revenue), cogs: v.cogs + num(r.cogs) });
  });
  const profitRows: RankRow[] = [...stage.entries()].map(([key, v]) => ({
    key, label: STAGE_LABEL[key] ?? tx('Other'), value: v.revenue - v.cogs, display: cmoney(v.revenue - v.cogs),
    sub: tx('{{revenue}} revenue · {{cogs}} cost', { revenue: cmoney(v.revenue), cogs: cmoney(v.cogs) }),
  }));
  const stock = (d?.stockByStage ?? []).map((r) => ({ key: r.stage ?? 'other', label: STAGE_LABEL[r.stage ?? ''] ?? tx('Other'), value: num(r.value) }));
  return (
    <div className="space-y-5">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div className="flex items-center gap-3">
          <span className="grid h-10 w-10 place-items-center rounded-xl bg-primary/10 text-primary"><Workflow className="h-5 w-5" aria-hidden /></span>
          <div><h1 className="text-xl font-semibold">{tx('Processing Dashboard')}</h1><p className="text-sm text-muted-foreground">{tx('From extraction or purchase to sale, at every stage.')}</p></div>
        </div>
        <div className="flex flex-wrap items-center gap-2">
          <PeriodSelect value={period.key} onChange={setPeriod} />
          {has(PERMISSIONS.processing.post) && <Button asChild><Link to="/manufacturing/processing/jobs/new"><Plus className="mr-1.5 h-4 w-4" /> {tx('New job')}</Link></Button>}
        </div>
      </div>
      {isError && <Empty>{tx('Could not load the dashboard. Try again.')}</Empty>}
      <Reveal className="grid gap-4 sm:grid-cols-2 xl:grid-cols-5">
        <KpiCard label={tx('Jobs in process')} value={qty(jobCount('in_progress') + jobCount('partially_output'), 0)} sub={tx('{{count}} completed in period', { count: jobCount('completed') })} to="/manufacturing/processing/jobs" loading={isLoading} />
        <KpiCard label={tx('Value in process')} value={cmoney(d?.wipValue)} title={money(d?.wipValue, currency)} sub={tx('Work in progress now')} loading={isLoading} />
        <KpiCard label={tx('Stock value')} value={cmoney(stockValue)} title={money(stockValue, currency)} sub={tx('Lots at every stage')} to="/manufacturing/processing/stock" loading={isLoading} />
        <KpiCard label={tx('Revenue')} value={cmoney(revenue)} title={money(revenue, currency)} sub={tx('Processed products')} loading={isLoading} />
        <KpiCard label={tx('Gross profit')} value={cmoney(profit)} title={money(profit, currency)} sub={revenue ? tx('{{pct}} margin', { pct: pctText((profit / revenue) * 100) }) : tx('On actual cost')} ring={revenue ? (profit / revenue) * 100 : null} loading={isLoading} />
      </Reveal>
      <Reveal delay={80} className="grid gap-5 lg:grid-cols-2">
        <Panel title={tx('Gross profit by stage')} hint={tx('Stage the product was sold at. Actual cost, not list cost.')}><RankedBars rows={profitRows} emptyText={tx('No sale of processed products in this period.')} /></Panel>
        <Panel title={tx('Stock by stage')} hint={tx('Value on hand now')} href="/manufacturing/processing/stock" action={tx('Open')}>
          {stock.length === 0 ? <Empty>{tx('No lot-tracked stock yet.')}</Empty> : <Donut rows={stock} format={cmoney} size={150} centerSub={tx('on hand')} stacked />}
        </Panel>
      </Reveal>
      <Reveal delay={140} className="grid gap-5 lg:grid-cols-2">
        <Panel title={tx('Yield by step')} hint={tx('Mass yield against expected, in this period')} href="/manufacturing/processing/templates" action={tx('Steps')}>
          {(d?.yieldByTemplate ?? []).length === 0 ? <Empty>{tx('No job in this period.')}</Empty> : (
            <div className="overflow-x-auto"><table className="w-full text-sm">
              <thead className="text-left text-xs text-muted-foreground"><tr><th className="py-1 pr-3 font-medium">{tx('Step')}</th><th className="pr-3 text-right font-medium">{tx('Jobs')}</th><th className="pr-3 text-right font-medium">{tx('Input')}</th><th className="pr-3 font-medium">{tx('Yield')}</th><th className="text-right font-medium">{tx('Expected')}</th></tr></thead>
              <tbody className="divide-y">{(d?.yieldByTemplate ?? []).map((y) => {
                const input = num(y.inputBasis);
                const actual = input > 0 ? (num(y.outputBasis) / input) * 100 : 0;
                const low = actual < num(y.expectedYieldPct) - 0.5;
                return (
                  <tr key={y.code}>
                    <td className="py-2 pr-3 font-medium">{y.name}</td>
                    <td className="pr-3 text-right">{y.jobs}</td>
                    <td className="whitespace-nowrap pr-3 text-right">{qty(input)} {y.yieldBasisUnit}</td>
                    <td className="w-36 pr-3"><div className="flex items-center gap-2"><div className="h-1.5 flex-1 overflow-hidden rounded-full bg-muted"><div className={`h-full rounded-full ${low ? 'bg-amber-500' : 'bg-emerald-500'}`} style={{ width: `${Math.min(100, actual)}%` }} /></div><span className="w-11 text-right text-xs tabular-nums">{pctText(actual)}</span></div></td>
                    <td className="text-right text-muted-foreground">{pctText(num(y.expectedYieldPct))}</td>
                  </tr>
                );
              })}</tbody>
            </table></div>
          )}
        </Panel>
        <Panel title={tx('Latest jobs')} href="/manufacturing/processing/jobs" action={tx('View all')}>
          {(d?.recentJobs ?? []).length === 0 ? <Empty>{tx('No job yet.')}</Empty> : (
            <ul className="divide-y text-sm">{(d?.recentJobs ?? []).map((j) => (
              <li key={j.id}><Link to={`/manufacturing/processing/jobs/${j.id}`} className="-mx-2 flex items-center justify-between gap-3 rounded-md px-2 py-2 hover:bg-muted/60"><span className="min-w-0"><span className="font-medium text-primary">{j.code}</span> <span className="truncate text-muted-foreground">{j.templateName} · {date(j.jobDate)}</span></span><JobStatusPill status={j.status} /></Link></li>
            ))}</ul>
          )}
        </Panel>
      </Reveal>
      <Reveal delay={200}>
        <Panel title={tx('Profit by product')} hint={tx('Revenue, actual cost of sale and margin in this period')}>
          {margin.length === 0 ? <Empty>{tx('No sale of processed products in this period.')}</Empty> : (
            <div className="overflow-x-auto"><table className="w-full text-sm">
              <thead className="text-left text-xs text-muted-foreground"><tr><th className="py-1 pr-3 font-medium">{tx('Product')}</th><th className="pr-3 font-medium">{tx('Stage')}</th><th className="pr-3 font-medium">{tx('Category')}</th><th className="pr-3 text-right font-medium">{tx('Sold')}</th><th className="pr-3 text-right font-medium">{tx('Revenue')}</th><th className="pr-3 text-right font-medium">{tx('Cost of sale')}</th><th className="pr-3 text-right font-medium">{tx('Gross profit')}</th><th className="text-right font-medium">{tx('Margin')}</th></tr></thead>
              <tbody className="divide-y">{margin.map((r) => (
                <tr key={r.productId}><td className="py-2 pr-3 font-medium">{r.productName}</td><td className="pr-3">{STAGE_LABEL[r.stage ?? ''] ?? '—'}</td><td className="pr-3">{r.categoryName ?? '—'}</td><td className="pr-3 text-right">{qty(r.quantity)}</td><td className="pr-3 text-right">{money(r.revenue, currency)}</td><td className="pr-3 text-right">{money(r.cogs, currency)}</td><td className="pr-3 text-right font-medium">{money(r.grossProfit, currency)}</td><td className="text-right">{r.marginPct == null ? '—' : pctText(num(r.marginPct))}</td></tr>
              ))}</tbody>
            </table></div>
          )}
        </Panel>
      </Reveal>
    </div>
  );
}

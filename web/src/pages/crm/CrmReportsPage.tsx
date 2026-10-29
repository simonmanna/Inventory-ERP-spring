import { useSearchParams } from 'react-router-dom';
import type { ReactNode } from 'react';
import { BarChart3, Download } from 'lucide-react';
import {
  Bar, BarChart, CartesianGrid, Legend, Line, ComposedChart, ResponsiveContainer, Tooltip, XAxis, YAxis,
} from 'recharts';
import { Button } from '@/components/ui/button';
import { Skeleton } from '@/components/ui/skeleton';
import { ListPageHeader } from '@/components/list';
import { money } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';
import { cn } from '@/lib/utils';
import { QUOTE_STATUS_LABEL, useCrmLookups, useCrmReport, type Breakdown, type CrmReport } from '@/features/crm/suite-api';
import { KpiCard, Meter, colorOf, compact, typeLabel } from './crm-ui';

const iso = (d: Date) => d.toISOString().slice(0, 10);
const PRESETS: Array<{ days: number; label: string }> = [
  { days: 30, label: tx('30 days') }, { days: 90, label: tx('90 days') }, { days: 180, label: tx('6 months') }, { days: 365, label: tx('1 year') },
];

/** CRM management report with filters: KPIs, funnel, breakdowns, trend, forecast, speed, quotations (ADR-S010). */
export function CrmReportsPage() {
  const [params, setParams] = useSearchParams();
  const { data: lk } = useCrmLookups();
  const get = (k: string) => params.get(k) ?? '';
  const set = (patch: Record<string, string>) => setParams((p) => { const n = new URLSearchParams(p); for (const [k, v] of Object.entries(patch)) { if (v) n.set(k, v); else n.delete(k); } return n; }, { replace: true });
  const filters = {
    from: get('from') || iso(new Date(Date.now() - 89 * 86_400_000)), to: get('to') || iso(new Date()),
    ownerId: get('ownerId') || undefined, teamId: get('teamId') || undefined, sourceId: get('sourceId') || undefined,
    tagId: get('tagId') || undefined, kind: get('kind') || undefined, country: get('country') || undefined,
  };
  const { data, isLoading, isFetching } = useCrmReport(filters);
  const cur = data?.currency;
  const sel = 'h-9 rounded-md border bg-background px-2 text-sm';

  return (
    <div className="space-y-4">
      <ListPageHeader icon={BarChart3} title={tx('CRM reports')} description={data ? `${tx('Deals created')} ${data.range.from} → ${data.range.to}` : tx('Pipeline, conversion, speed and revenue')}
        actions={data && <Button variant="outline" onClick={() => exportCsv(data)}><Download className="mr-1.5 h-4 w-4" />{tx('Export CSV')}</Button>} />
      <div className="flex flex-wrap items-center gap-2 rounded-xl border bg-card p-3 shadow-sm">
        <label className="flex items-center gap-1.5 text-sm">{tx('From')}<input type="date" className={sel} value={filters.from} onChange={(e) => set({ from: e.target.value })} /></label>
        <label className="flex items-center gap-1.5 text-sm">{tx('To')}<input type="date" className={sel} value={filters.to} onChange={(e) => set({ to: e.target.value })} /></label>
        <div className="flex gap-1">{PRESETS.map((p) => <Button key={p.days} size="sm" variant="ghost" onClick={() => set({ from: iso(new Date(Date.now() - (p.days - 1) * 86_400_000)), to: '' })}>{p.label}</Button>)}</div>
        <select className={sel} value={get('ownerId')} onChange={(e) => set({ ownerId: e.target.value })}><option value="">{tx('All salespeople')}</option>{(lk?.users ?? []).map((u) => <option key={u.id} value={u.id}>{u.name}</option>)}</select>
        <select className={sel} value={get('teamId')} onChange={(e) => set({ teamId: e.target.value })}><option value="">{tx('All teams')}</option>{(lk?.teams ?? []).map((u) => <option key={u.id} value={u.id}>{u.name}</option>)}</select>
        <select className={sel} value={get('sourceId')} onChange={(e) => set({ sourceId: e.target.value })}><option value="">{tx('All sources')}</option>{(lk?.sources ?? []).map((u) => <option key={u.id} value={u.id}>{u.name}</option>)}</select>
        <select className={sel} value={get('tagId')} onChange={(e) => set({ tagId: e.target.value })}><option value="">{tx('All tags')}</option>{(lk?.tags ?? []).map((u) => <option key={u.id} value={u.id}>{u.name}</option>)}</select>
        <select className={sel} value={get('kind')} onChange={(e) => set({ kind: e.target.value })}><option value="">{tx('Leads & opportunities')}</option><option value="lead">{tx('Leads only')}</option><option value="opportunity">{tx('Opportunities only')}</option></select>
        {isFetching && <span className="text-xs text-muted-foreground">{tx('Updating…')}</span>}
      </div>

      {isLoading && <Skeleton className="h-96 w-full" />}
      {data && (
        <div className={cn('space-y-4', isFetching && 'opacity-70')}>
          <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4 xl:grid-cols-8">
            <KpiCard label={tx('Leads')} value={data.kpis.leads} sub={`${data.kpis.leadConversion}% ${tx('converted')}`} />
            <KpiCard label={tx('Opportunities')} value={data.kpis.opportunities} sub={`${data.kpis.open} ${tx('still open')}`} />
            <KpiCard label={tx('Open pipeline')} value={compact(data.kpis.openValue)} sub={`${tx('weighted')} ${compact(data.kpis.weightedValue)}`} />
            <KpiCard label={tx('Won')} value={data.kpis.won} sub={money(data.kpis.wonValue, cur)} tone="success" />
            <KpiCard label={tx('Win rate')} value={`${data.kpis.winRate}%`} sub={`${data.kpis.lost} ${tx('lost')}`} />
            <KpiCard label={tx('Avg deal size')} value={compact(data.kpis.avgDealSize)} />
            <KpiCard label={tx('Sales cycle')} value={`${data.kpis.avgCycleDays} d`} sub={tx('created → won')} />
            <KpiCard label={tx('Quote acceptance')} value={`${data.kpis.quoteAcceptance}%`} sub={`${data.kpis.quotations} ${tx('quotes')}`} />
          </div>

          <div className="grid gap-4 lg:grid-cols-2">
            <Card title={tx('Conversion funnel')} sub={tx('Deals created in the period that reached each stage')}>
              <div className="space-y-2">
                {data.funnel.map((f) => {
                  const top = Math.max(1, data.funnel[0]?.count ?? 1);
                  return (
                    <div key={f.key} className="grid grid-cols-[120px_1fr_70px] items-center gap-2 text-sm">
                      <span className="truncate">{f.label}</span>
                      <div className="h-6 rounded bg-muted"><div className={cn('flex h-full items-center rounded px-2 text-xs font-semibold text-white', colorOf(f.color).bar)} style={{ width: `${Math.max(f.count ? 4 : 0, (f.count / top) * 100)}%` }}>{f.count || ''}</div></div>
                      <span className="text-right text-xs text-muted-foreground">{Math.round((f.count / top) * 100)}%</span>
                    </div>
                  );
                })}
              </div>
            </Card>
            <Card title={tx('Monthly trend')} sub={tx('Deals created vs. won, and won revenue')}>
              <div className="h-64">
                <ResponsiveContainer>
                  <ComposedChart data={data.trend}>
                    <CartesianGrid strokeDasharray="3 3" className="stroke-muted" />
                    <XAxis dataKey="month" fontSize={11} />
                    <YAxis yAxisId="l" fontSize={11} allowDecimals={false} />
                    <YAxis yAxisId="r" orientation="right" fontSize={11} tickFormatter={(v) => compact(v)} />
                    <Tooltip formatter={(v: number, n: string) => (n === tx('Won revenue') ? money(v, cur) : v)} />
                    <Legend />
                    <Bar yAxisId="l" dataKey="created" name={tx('Created')} fill="#94a3b8" radius={[3, 3, 0, 0]} />
                    <Bar yAxisId="l" dataKey="won" name={tx('Won')} fill="#10b981" radius={[3, 3, 0, 0]} />
                    <Line yAxisId="r" dataKey="wonValue" name={tx('Won revenue')} stroke="#6366f1" strokeWidth={2} dot={false} />
                  </ComposedChart>
                </ResponsiveContainer>
              </div>
            </Card>
          </div>

          <div className="grid gap-4 lg:grid-cols-2">
            <Card title={tx('Open pipeline by stage')}>
              {data.byStage.map((s) => (
                <div key={s.key} className="grid grid-cols-[130px_1fr_40px_90px] items-center gap-2 py-1 text-sm">
                  <span className="flex items-center gap-1.5 truncate"><span className={cn('h-2 w-2 rounded-full', colorOf(s.color).dot)} />{s.label}</span>
                  <Meter value={Number(s.amount)} max={Math.max(...data.byStage.map((x) => Number(x.amount)), 1)} />
                  <span className="text-right tabular-nums">{s.count}</span>
                  <span className="text-right tabular-nums">{compact(s.amount)}</span>
                </div>
              ))}
              {data.byStage.length === 0 && <Empty />}
            </Card>
            <Card title={tx('Forecast')} sub={tx('Open deals by expected closing month')}>
              <div className="h-56">
                <ResponsiveContainer>
                  <BarChart data={data.forecast}>
                    <CartesianGrid strokeDasharray="3 3" className="stroke-muted" />
                    <XAxis dataKey="month" fontSize={11} />
                    <YAxis fontSize={11} tickFormatter={(v) => compact(v)} />
                    <Tooltip formatter={(v: number) => money(v, cur)} />
                    <Legend />
                    <Bar dataKey="amount" name={tx('Expected')} fill="#cbd5e1" radius={[3, 3, 0, 0]} />
                    <Bar dataKey="weighted" name={tx('Weighted')} fill="#6366f1" radius={[3, 3, 0, 0]} />
                  </BarChart>
                </ResponsiveContainer>
              </div>
            </Card>
          </div>

          <Card title={tx('By salesperson')} sub={tx('Workload and outcomes for coaching')}><BreakdownTable rows={data.byOwner} first={tx('Salesperson')} cur={cur} /></Card>
          <div className="grid gap-4 lg:grid-cols-2">
            <Card title={tx('By source')}><BreakdownTable rows={data.bySource} first={tx('Source')} cur={cur} compactCols /></Card>
            <Card title={tx('By sales team')}><BreakdownTable rows={data.byTeam} first={tx('Team')} cur={cur} compactCols /></Card>
          </div>
          <div className="grid gap-4 lg:grid-cols-2">
            <Card title={tx('By tag')}><BreakdownTable rows={data.byTag} first={tx('Tag')} cur={cur} compactCols /></Card>
            <Card title={tx('By country')}><BreakdownTable rows={data.byCountry} first={tx('Country')} cur={cur} compactCols /></Card>
          </div>
          <div className="grid gap-4 lg:grid-cols-3">
            <Card title={tx('Why deals are lost')}>
              {data.lostReasons.map((l) => (
                <div key={String(l.key)} className="grid grid-cols-[1fr_80px_30px] items-center gap-2 py-1 text-sm">
                  <span className="truncate">{l.label}</span><Meter value={l.count} max={Math.max(...data.lostReasons.map((x) => x.count), 1)} className="bg-rose-500" /><span className="text-right tabular-nums">{l.count}</span>
                </div>
              ))}
              {data.lostReasons.length === 0 && <Empty />}
            </Card>
            <Card title={tx('Average time in stage')} sub={tx('Where deals wait longest')}>
              {data.timeInStage.map((t) => (
                <div key={t.key} className="grid grid-cols-[1fr_80px_50px] items-center gap-2 py-1 text-sm">
                  <span className="truncate">{t.label}</span><Meter value={Number(t.avgDays)} max={Math.max(...data.timeInStage.map((x) => Number(x.avgDays)), 1)} className={Number(t.avgDays) > 14 ? 'bg-amber-500' : ''} /><span className="text-right tabular-nums">{Number(t.avgDays)} d</span>
                </div>
              ))}
              {data.timeInStage.length === 0 && <Empty />}
            </Card>
            <Card title={tx('Quotations & activities')}>
              {data.quotations.byStatus.map((q) => (
                <div key={q.key} className="flex justify-between py-0.5 text-sm"><span>{QUOTE_STATUS_LABEL[q.key] ?? q.key}</span><span className="tabular-nums">{q.count} · {compact(q.value)}</span></div>
              ))}
              <div className="my-2 border-t" />
              {data.activities.byType.map((a) => (
                <div key={a.key} className="flex justify-between py-0.5 text-sm"><span>{typeLabel(a.key)} {tx('done')}</span><span className="tabular-nums">{a.done}</span></div>
              ))}
              <div className="flex justify-between py-0.5 text-sm font-medium text-rose-600"><span>{tx('Overdue activities now')}</span><span>{data.activities.overdue}</span></div>
            </Card>
          </div>
        </div>
      )}
    </div>
  );
}

function Card({ title, sub, children }: { title: string; sub?: string; children: ReactNode }) {
  return (
    <div className="rounded-xl border bg-card p-4 shadow-sm">
      <h2 className="font-semibold">{title}</h2>
      {sub && <p className="mb-3 text-xs text-muted-foreground">{sub}</p>}
      <div className={sub ? '' : 'mt-3'}>{children}</div>
    </div>
  );
}

const Empty = () => <p className="text-sm text-muted-foreground">{tx('No data in this range.')}</p>;

function BreakdownTable({ rows, first, cur, compactCols }: { rows: Breakdown[]; first: string; cur?: string; compactCols?: boolean }) {
  if (!rows.length) return <Empty />;
  const max = Math.max(...rows.map((r) => r.total), 1);
  return (
    <div className="overflow-x-auto">
      <table className="w-full min-w-[480px] text-sm">
        <thead className="text-left text-xs uppercase text-muted-foreground">
          <tr><th className="py-1.5">{first}</th><th className="py-1.5">{tx('Deals')}</th>{!compactCols && <th className="py-1.5 text-right">{tx('Leads')}</th>}<th className="py-1.5 text-right">{tx('Open')}</th>
            <th className="py-1.5 text-right">{tx('Won')}</th>{!compactCols && <th className="py-1.5 text-right">{tx('Lost')}</th>}<th className="py-1.5 text-right">{tx('Win rate')}</th>
            <th className="py-1.5 text-right">{tx('Won value')}</th>{!compactCols && <th className="py-1.5 text-right">{tx('Pipeline')}</th>}</tr>
        </thead>
        <tbody>
          {rows.map((r) => (
            <tr key={String(r.key)} className="border-t">
              <td className="py-1.5 pr-2">{r.label}</td>
              <td className="py-1.5 pr-2"><span className="flex items-center gap-2"><Meter value={r.total} max={max} /><span className="tabular-nums">{r.total}</span></span></td>
              {!compactCols && <td className="py-1.5 text-right tabular-nums">{r.leads}</td>}
              <td className="py-1.5 text-right tabular-nums">{r.open}</td>
              <td className="py-1.5 text-right font-semibold tabular-nums">{r.won}</td>
              {!compactCols && <td className="py-1.5 text-right tabular-nums">{r.lost}</td>}
              <td className="py-1.5 text-right tabular-nums">{r.winRate}%</td>
              <td className="py-1.5 text-right tabular-nums">{money(r.wonValue, cur)}</td>
              {!compactCols && <td className="py-1.5 text-right tabular-nums">{money(r.openValue, cur)}</td>}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

function exportCsv(r: CrmReport) {
  const lines: string[][] = [['Section', 'Key', 'Deals', 'Open', 'Won', 'Lost', 'Win rate %', 'Won value', 'Open value']];
  const add = (section: string, rows: Breakdown[]) => rows.forEach((b) => lines.push([section, b.label, String(b.total), String(b.open), String(b.won), String(b.lost), String(b.winRate), String(b.wonValue), String(b.openValue)]));
  add('Salesperson', r.byOwner); add('Source', r.bySource); add('Team', r.byTeam); add('Tag', r.byTag); add('Country', r.byCountry);
  lines.push([]); lines.push(['KPI', 'Value']);
  for (const [k, v] of Object.entries(r.kpis)) lines.push([k, String(v)]);
  const csv = lines.map((l) => l.map((c) => `"${String(c ?? '').replace(/"/g, '""')}"`).join(',')).join('\n');
  const url = URL.createObjectURL(new Blob([csv], { type: 'text/csv' }));
  const a = document.createElement('a');
  a.href = url; a.download = `crm-report-${r.range.from}-${r.range.to}.csv`; a.click();
  URL.revokeObjectURL(url);
}

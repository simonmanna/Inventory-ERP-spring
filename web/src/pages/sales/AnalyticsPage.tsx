import { useState } from 'react';
import { PERMISSIONS } from '@erp/shared';
import { useQuery } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import { Bar, BarChart, CartesianGrid, Legend, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts';
import { AlertTriangle, Info, LineChart, Lightbulb } from 'lucide-react';
import { Input } from '@/components/ui/input';
import { ListPageHeader } from '@/components/list';
import { api } from '@/lib/api';
import { money } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';
import { cn } from '@/lib/utils';
import { KpiCard } from '@/pages/crm/crm-ui';
import { useAuthStore } from '@/stores/auth.store';

// ---- Types (ADR-S011 S10) ----

interface Dashboard {
  revenueThisMonth: number; revenueLastMonth: number; growthPercent: number | null;
  quotationsPending: { count: number; total: number }; ordersToDeliver: { count: number; total: number };
  ordersToInvoice: number; overdueReceivables: { customers: number; total: number };
  chart: Array<{ month: string; revenue: number; margin: number }>;
  topCustomers: Array<{ partnerId: string; name: string; revenue: number }>;
}
interface AnalysisRow { key: string; label: string; quantity: number; revenue: number; cost: number; margin: number; marginPercent: number | null; documents: number }
interface Analysis { rows: AnalysisRow[]; totals: { revenue: number; cost: number; margin: number; marginPercent: number | null } }
interface ProfitRow { partnerId: string; name: string; revenue: number; cost: number; grossMargin: number; freight: number; rebates: number; commissions: number; netContribution: number; netPercent: number | null }
interface Forecast { conversionRate: number; months: Array<{ month: string; runRate: number; openOrders: number; weightedQuotations: number; agreements: number; expected: number }> }
interface Advice { kind: string; severity: 'info' | 'warning' | 'critical'; title: string; detail: string; entityType: string; entityId: string; partnerId: string | null }

type Tab = 'overview' | 'analysis' | 'profitability' | 'forecast';
const th = 'px-3 py-2';
const monthStart = (back: number) => { const d = new Date(); d.setDate(1); d.setMonth(d.getMonth() - back); return d.toISOString().slice(0, 10); };

/** Sales analytics: dashboard, analysis, profitability, forecast and the advisor. */
export function AnalyticsPage() {
  const [tab, setTab] = useState<Tab>('overview');
  const seesCost = useAuthStore((s) => s.hasPermission(PERMISSIONS.sales.costRead));
  const TABS: Array<[Tab, string]> = [['overview', tx('Overview')], ['analysis', tx('Analysis')], ...(seesCost ? [['profitability', tx('Customer profitability')] as [Tab, string]] : []), ['forecast', tx('Forecast')]];
  return (
    <div className="space-y-4">
      <ListPageHeader icon={LineChart} title={tx('Sales analytics')} description={tx('Net invoiced revenue and margin, customer profitability, forecast and advisories.')} />
      <div className="flex flex-wrap rounded-lg border bg-card p-0.5">
        {TABS.map(([k, l]) => <button key={k} onClick={() => setTab(k)} className={cn('rounded-md px-3 py-1.5 text-sm', tab === k ? 'bg-primary text-primary-foreground' : 'hover:bg-muted')}>{l}</button>)}
      </div>
      {tab === 'overview' && <Overview />}
      {tab === 'analysis' && <AnalysisTab />}
      {tab === 'profitability' && <Profitability />}
      {tab === 'forecast' && <ForecastTab />}
    </div>
  );
}

function Overview() {
  const { data: d } = useQuery({ queryKey: ['sales', 'analytics', 'dashboard'], queryFn: async () => (await api.get<Dashboard>('/sales/analytics/dashboard')).data });
  const { data: advice } = useQuery({ queryKey: ['sales', 'advisor'], queryFn: async () => (await api.get<Advice[]>('/sales/advisor')).data });
  if (!d) return null;
  return (
    <div className="space-y-4">
      <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-5">
        <KpiCard label={tx('Revenue this month')} value={money(d.revenueThisMonth)} sub={d.growthPercent == null ? tx('no revenue last month') : `${d.growthPercent > 0 ? '+' : ''}${d.growthPercent}% ${tx('vs last month')}`} tone={d.growthPercent != null && d.growthPercent < 0 ? 'danger' : 'success'} />
        <KpiCard label={tx('Quotations pending')} value={d.quotationsPending.count} sub={money(d.quotationsPending.total)} />
        <KpiCard label={tx('Orders to deliver')} value={d.ordersToDeliver.count} sub={money(d.ordersToDeliver.total)} />
        <KpiCard label={tx('Orders to invoice')} value={d.ordersToInvoice} />
        <KpiCard label={tx('Overdue receivables')} value={money(d.overdueReceivables.total)} sub={`${d.overdueReceivables.customers} ${tx('customers')}`} tone={Number(d.overdueReceivables.total) > 0 ? 'danger' : undefined} />
      </div>
      <div className="grid gap-4 lg:grid-cols-[minmax(0,2fr)_minmax(0,1fr)]">
        <div className="h-72 rounded-xl border bg-card p-3 shadow-sm">
          <ResponsiveContainer width="100%" height="100%">
            <BarChart data={d.chart}>
              <CartesianGrid strokeDasharray="3 3" vertical={false} />
              <XAxis dataKey="month" fontSize={11} />
              <YAxis fontSize={11} width={70} tickFormatter={(v: number) => Intl.NumberFormat(undefined, { notation: 'compact' }).format(v)} />
              <Tooltip formatter={(v: number) => money(v)} />
              <Legend />
              <Bar dataKey="revenue" name={tx('Revenue')} fill="hsl(var(--primary))" />
              <Bar dataKey="margin" name={tx('Margin')} fill="hsl(142 60% 45%)" />
            </BarChart>
          </ResponsiveContainer>
        </div>
        <div className="rounded-xl border bg-card p-3 shadow-sm">
          <h3 className="mb-2 text-sm font-semibold">{tx('Top customers this month')}</h3>
          <ul className="space-y-1 text-sm">{d.topCustomers.map((c) => <li key={c.partnerId} className="flex justify-between"><span>{c.name}</span><span className="tabular-nums">{money(c.revenue)}</span></li>)}
            {d.topCustomers.length === 0 && <li className="text-muted-foreground">{tx('No sales yet this month.')}</li>}</ul>
        </div>
      </div>
      <div className="rounded-xl border bg-card p-3 shadow-sm">
        <h3 className="mb-2 flex items-center gap-2 text-sm font-semibold"><Lightbulb className="h-4 w-4" />{tx('Advisor')} <span className="text-xs font-normal text-muted-foreground">{tx('read-only suggestions from your sales and receivables')}</span></h3>
        <ul className="space-y-2">
          {(advice ?? []).map((a, i) => (
            <li key={i} className="flex gap-2 text-sm">
              {a.severity === 'info' ? <Info className="mt-0.5 h-4 w-4 shrink-0 text-sky-600" /> : <AlertTriangle className="mt-0.5 h-4 w-4 shrink-0 text-amber-600" />}
              <div><p className="font-medium">{a.title}</p><p className="text-muted-foreground">{a.detail}</p>{adviceLink(a)}</div>
            </li>
          ))}
          {advice && advice.length === 0 && <li className="text-sm text-muted-foreground">{tx('Nothing needs attention.')}</li>}
        </ul>
      </div>
    </div>
  );
}

function adviceLink(a: Advice) {
  const to = a.entityType === 'sales_order' ? `/sales/orders/${a.entityId}` : a.entityType === 'sales_quotation' ? `/crm/quotations/${a.entityId}`
    : a.entityType === 'document' ? `/invoices/${a.entityId}` : a.entityType === 'sales_agreement' ? '/sales/agreements' : a.partnerId ? '/partners' : null;
  return to ? <Link to={to} className="text-xs text-primary hover:underline">{tx('Open')}</Link> : null;
}

function Range({ from, to, onChange }: { from: string; to: string; onChange: (f: string, t: string) => void }) {
  return (
    <div className="flex gap-2">
      <Input type="date" className="w-40" value={from} onChange={(e) => onChange(e.target.value, to)} />
      <Input type="date" className="w-40" value={to} onChange={(e) => onChange(from, e.target.value)} />
    </div>
  );
}

function AnalysisTab() {
  const seesCost = useAuthStore((s) => s.hasPermission(PERMISSIONS.sales.costRead));
  const [groupBy, setGroupBy] = useState('product');
  const [from, setFrom] = useState(monthStart(2));
  const [to, setTo] = useState(new Date().toISOString().slice(0, 10));
  const { data } = useQuery({ queryKey: ['sales', 'analytics', 'analysis', groupBy, from, to], queryFn: async () => (await api.get<Analysis>('/sales/analytics/analysis', { params: { groupBy, from, to } })).data });
  const DIMS: Array<[string, string]> = [['product', tx('Product')], ['productCategory', tx('Product category')], ['customer', tx('Customer')], ['customerCategory', tx('Customer category')], ['branch', tx('Branch')], ['salesperson', tx('Salesperson')], ['month', tx('Month')]];
  return (
    <div className="space-y-2">
      <div className="flex flex-wrap gap-2">
        <select className="h-9 rounded-md border bg-background px-2 text-sm" value={groupBy} onChange={(e) => setGroupBy(e.target.value)}>{DIMS.map(([k, l]) => <option key={k} value={k}>{l}</option>)}</select>
        <Range from={from} to={to} onChange={(f, t) => { setFrom(f); setTo(t); }} />
      </div>
      <div className="overflow-x-auto rounded-xl border bg-card shadow-sm">
        <table className="w-full min-w-[720px] text-sm">
          <thead className="bg-muted/40 text-left text-xs font-semibold uppercase text-muted-foreground"><tr>
            <th className={th}>{DIMS.find(([k]) => k === groupBy)?.[1]}</th><th className={cn(th, 'text-right')}>{tx('Qty')}</th><th className={cn(th, 'text-right')}>{tx('Revenue')}</th>
            {seesCost && <><th className={cn(th, 'text-right')}>{tx('Cost')}</th><th className={cn(th, 'text-right')}>{tx('Margin')}</th><th className={cn(th, 'text-right')}>{tx('Margin %')}</th></>}</tr></thead>
          <tbody>
            {(data?.rows ?? []).map((r) => (
              <tr key={r.key ?? 'none'} className="border-t"><td className={th}>{r.label}</td><td className={cn(th, 'text-right tabular-nums')}>{r.quantity}</td>
                <td className={cn(th, 'text-right tabular-nums')}>{money(r.revenue)}</td>{seesCost && <><td className={cn(th, 'text-right tabular-nums')}>{money(r.cost)}</td>
                <td className={cn(th, 'text-right tabular-nums', Number(r.margin) < 0 && 'text-rose-600')}>{money(r.margin)}</td><td className={cn(th, 'text-right')}>{r.marginPercent ?? '—'}%</td></>}</tr>
            ))}
            {data && <tr className="border-t bg-muted/30 font-semibold"><td className={th}>{tx('Total')}</td><td /><td className={cn(th, 'text-right tabular-nums')}>{money(data.totals.revenue)}</td>
              {seesCost && <><td className={cn(th, 'text-right tabular-nums')}>{money(data.totals.cost)}</td><td className={cn(th, 'text-right tabular-nums')}>{money(data.totals.margin)}</td><td className={cn(th, 'text-right')}>{data.totals.marginPercent ?? '—'}%</td></>}</tr>}
          </tbody>
        </table>
      </div>
      <p className="text-xs text-muted-foreground">{tx('Revenue = posted invoices minus credit notes, net of tax; cost = current product cost.')}</p>
    </div>
  );
}

function Profitability() {
  const [from, setFrom] = useState(monthStart(2));
  const [to, setTo] = useState(new Date().toISOString().slice(0, 10));
  const { data } = useQuery({ queryKey: ['sales', 'analytics', 'profit', from, to], queryFn: async () => (await api.get<{ rows: ProfitRow[] }>('/sales/analytics/profitability', { params: { from, to } })).data });
  return (
    <div className="space-y-2">
      <Range from={from} to={to} onChange={(f, t) => { setFrom(f); setTo(t); }} />
      <div className="overflow-x-auto rounded-xl border bg-card shadow-sm">
        <table className="w-full min-w-[900px] text-sm">
          <thead className="bg-muted/40 text-left text-xs font-semibold uppercase text-muted-foreground"><tr>
            <th className={th}>{tx('Customer')}</th>{[tx('Revenue'), tx('Cost'), tx('Gross margin'), tx('Freight'), tx('Rebates'), tx('Commissions'), tx('Net contribution'), '%'].map((h) => <th key={h} className={cn(th, 'text-right')}>{h}</th>)}</tr></thead>
          <tbody>
            {(data?.rows ?? []).map((r) => (
              <tr key={r.partnerId} className="border-t"><td className={th}>{r.name}</td>
                {[r.revenue, r.cost, r.grossMargin, r.freight, r.rebates, r.commissions].map((v, i) => <td key={i} className={cn(th, 'text-right tabular-nums')}>{money(v)}</td>)}
                <td className={cn(th, 'text-right font-medium tabular-nums', Number(r.netContribution) < 0 && 'text-rose-600')}>{money(r.netContribution)}</td><td className={cn(th, 'text-right')}>{r.netPercent ?? '—'}%</td></tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}

function ForecastTab() {
  const [months, setMonths] = useState(3);
  const { data } = useQuery({ queryKey: ['sales', 'analytics', 'forecast', months], queryFn: async () => (await api.get<Forecast>('/sales/analytics/forecast', { params: { months } })).data });
  return (
    <div className="space-y-2">
      <select className="h-9 rounded-md border bg-background px-2 text-sm" value={months} onChange={(e) => setMonths(Number(e.target.value))}>{[3, 6, 12].map((m) => <option key={m} value={m}>{m} {tx('months')}</option>)}</select>
      <div className="overflow-x-auto rounded-xl border bg-card shadow-sm">
        <table className="w-full min-w-[720px] text-sm">
          <thead className="bg-muted/40 text-left text-xs font-semibold uppercase text-muted-foreground"><tr>
            <th className={th}>{tx('Month')}</th>{[tx('Run rate'), tx('Open orders'), tx('Quotations (weighted)'), tx('Agreements'), tx('Expected')].map((h) => <th key={h} className={cn(th, 'text-right')}>{h}</th>)}</tr></thead>
          <tbody>
            {(data?.months ?? []).map((m) => (
              <tr key={m.month} className="border-t"><td className={th}>{m.month}</td>
                {[m.runRate, m.openOrders, m.weightedQuotations, m.agreements].map((v, i) => <td key={i} className={cn(th, 'text-right tabular-nums')}>{money(v)}</td>)}
                <td className={cn(th, 'text-right font-semibold tabular-nums')}>{money(m.expected)}</td></tr>
            ))}
          </tbody>
        </table>
      </div>
      {data && <p className="text-xs text-muted-foreground">{tx('Quotations weighted by the 90-day conversion rate of')} {data.conversionRate}%. {tx('Expected = the larger of the run rate and orders + quotations + agreements.')}</p>}
    </div>
  );
}

import { useMemo, useState } from 'react';
import { Link, useNavigate, useSearchParams } from 'react-router-dom';
import { ArrowRight, Loader2, Search } from 'lucide-react';
import { Input } from '@/components/ui/input';
import { ListPageHeader } from '@/components/list';
import { tx } from '@/lib/i18n/tx';
import { useReportCatalog, useReportDashboard, useReportLookups, type DashboardTop, type ReportParams } from '@/features/sales/report-center-api';
import { ReportChart } from './ReportChart';
import { formatCell } from './report-format';
import { KpiTiles } from './ReportViewer';
import { PERIODS, requestParams } from './report-period';
import { HUB_ICON, REPORT_PAGES, reportHref } from './report-pages';

function TopList({ title, rows, report, onOpen }: { title: string; rows: DashboardTop[]; report: string; onOpen: (key: string, params?: ReportParams) => void }) {
  const max = Math.max(1, ...rows.map((r) => Number(r.revenue) || 0));
  return (
    <div className="rounded-xl border bg-card p-4 shadow-sm">
      <div className="mb-3 flex items-center justify-between">
        <h3 className="text-sm font-semibold">{title}</h3>
        <button type="button" onClick={() => onOpen(report)} className="inline-flex items-center gap-1 text-xs text-primary hover:underline">{tx('Full report')}<ArrowRight className="h-3 w-3" /></button>
      </div>
      {rows.length === 0 && <p className="py-6 text-center text-sm text-muted-foreground">{tx('No sales in this period.')}</p>}
      <ul className="space-y-2.5">
        {rows.map((r, i) => (
          <li key={r.id ?? i}>
            <button type="button" className="w-full text-left" disabled={!r._drill} onClick={() => r._drill && onOpen(r._drill.report, r._drill.params)}>
              <div className="flex items-baseline justify-between gap-3 text-sm">
                <span className="truncate"><span className="mr-1.5 text-xs text-muted-foreground">{i + 1}</span>{r.label}</span>
                <span className="shrink-0 tabular-nums font-medium">{formatCell(r.revenue, 'money')}</span>
              </div>
              <div className="mt-1 h-1.5 overflow-hidden rounded-full bg-muted">
                <div className="h-full rounded-full bg-primary" style={{ width: `${Math.max(2, (Number(r.revenue) / max) * 100)}%` }} />
              </div>
            </button>
          </li>
        ))}
      </ul>
    </div>
  );
}

/** Sales → Reporting → Sales Reports: executive dashboard and the searchable catalog of every sales report. */
export function SalesReportsHubPage() {
  const [search, setSearch] = useSearchParams();
  const state: ReportParams = useMemo(() => ({ period: search.get('period') ?? 'this_year', branchId: search.get('branchId') ?? '' }), [search]);
  const params = useMemo(() => requestParams(state), [state]);
  const { data: dash, isFetching } = useReportDashboard(params);
  const { data: catalog } = useReportCatalog();
  const { data: lk } = useReportLookups();
  const navigate = useNavigate();
  const [q, setQ] = useState('');
  const open = (key: string, extra: ReportParams = {}) => {
    const p: ReportParams = { period: state.period };
    if (state.branchId) p.branchId = state.branchId;
    navigate(reportHref(catalog, key, { ...p, ...extra }));
  };
  const setFilter = (k: string, v: string) => {
    const next = new URLSearchParams(search);
    if (v) next.set(k, v); else next.delete(k);
    setSearch(next, { replace: true });
  };
  const needle = q.trim().toLowerCase();

  return (
    <div className="space-y-5">
      <ListPageHeader icon={HUB_ICON} title={tx('Sales reports')}
        description={`${tx('Executive overview and')} ${catalog?.count ?? '…'} ${tx('reports across sales, customers, products, orders, deliveries, billing, collections, team and profitability.')}`}
        actions={(
          <div className="flex flex-wrap items-center gap-2">
            {isFetching && <Loader2 className="h-4 w-4 animate-spin text-muted-foreground" />}
            <select className="h-9 rounded-md border bg-background px-2 text-sm" value={state.period} onChange={(e) => setFilter('period', e.target.value)}>
              {PERIODS.filter(([k]) => k !== 'custom').map(([k, l]) => <option key={k} value={k}>{l}</option>)}
            </select>
            <select className="h-9 rounded-md border bg-background px-2 text-sm" value={state.branchId} onChange={(e) => setFilter('branchId', e.target.value)}>
              <option value="">{tx('All branches')}</option>
              {(lk?.branches ?? []).map((b) => <option key={b.id} value={b.id}>{b.name}</option>)}
            </select>
          </div>
        )} />

      {dash ? (
        <>
          <KpiTiles kpis={dash.kpis.map((k) => ({
            key: k.key, label: k.label, value: k.value, type: k.type,
            hint: k.sub == null || (k.key === 'target' && k.value == null) ? (k.key === 'target' ? tx('No company target set') : null) : k.key === 'revenue' ? `${formatCell(k.sub, 'percent')} ${tx('vs previous period')}`
              : k.key === 'margin' ? `${tx('Margin')} ${formatCell(k.sub, 'percent')}` : k.key === 'overdue' ? `${k.sub} ${tx('invoices')}`
                : k.key === 'target' ? `${tx('Target')} ${formatCell(k.sub, 'money')}` : formatCell(k.sub, 'money'),
            onClick: () => open(k.report),
          }))} />
          <div className="grid gap-4 xl:grid-cols-[minmax(0,2fr)_minmax(0,1fr)]">
            <div className="rounded-xl border bg-card p-4 shadow-sm">
              <div className="mb-2 flex items-center justify-between">
                <h3 className="text-sm font-semibold">{tx('Sales trend — last 12 months')}</h3>
                <button type="button" onClick={() => open('sales-trend', { period: 'last_12_months' })} className="inline-flex items-center gap-1 text-xs text-primary hover:underline">{tx('Trend analysis')}<ArrowRight className="h-3 w-3" /></button>
              </div>
              <ReportChart spec={{ kind: 'combo', x: 'label', stacked: false, series: [{ key: 'revenue', label: tx('Net sales'), kind: 'bar' }, { key: 'margin', label: tx('Gross profit'), kind: 'line' }] }}
                rows={dash.trend} columns={[{ key: 'revenue', label: 'Net sales', type: 'money' }, { key: 'margin', label: 'Gross profit', type: 'money' }]} height={280} />
            </div>
            <div className="rounded-xl border bg-card p-4 shadow-sm">
              <div className="mb-2 flex items-center justify-between">
                <h3 className="text-sm font-semibold">{tx('Sales by channel')}</h3>
                <button type="button" onClick={() => open('sales-by-channel')} className="inline-flex items-center gap-1 text-xs text-primary hover:underline">{tx('Details')}<ArrowRight className="h-3 w-3" /></button>
              </div>
              <ReportChart spec={{ kind: 'pie', x: 'label', stacked: false, series: [{ key: 'revenue', label: tx('Net sales'), kind: 'bar' }] }}
                rows={dash.byChannel} columns={[{ key: 'revenue', label: 'Net sales', type: 'money' }]} height={280} />
            </div>
          </div>
          <div className="grid gap-4 md:grid-cols-2 xl:grid-cols-3">
            <TopList title={tx('Top 5 customers')} rows={dash.topCustomers} report="top-customers" onOpen={open} />
            <TopList title={tx('Top 5 products')} rows={dash.topProducts} report="top-products" onOpen={open} />
            <TopList title={tx('Top 5 sales representatives')} rows={dash.topSalespeople} report="salesperson-ranking" onOpen={open} />
          </div>
          <div className="grid gap-4 lg:grid-cols-2">
            <div className="rounded-xl border bg-card p-4 shadow-sm">
              <div className="mb-2 flex items-center justify-between">
                <h3 className="text-sm font-semibold">{tx('Sales by branch')}</h3>
                <button type="button" onClick={() => open('sales-by-branch')} className="inline-flex items-center gap-1 text-xs text-primary hover:underline">{tx('Details')}<ArrowRight className="h-3 w-3" /></button>
              </div>
              <ReportChart spec={{ kind: 'hbar', x: 'label', stacked: false, series: [{ key: 'revenue', label: tx('Net sales'), kind: 'bar' }, { key: 'margin', label: tx('Gross profit'), kind: 'bar' }] }}
                rows={dash.byBranch} columns={[{ key: 'revenue', label: 'Net sales', type: 'money' }, { key: 'margin', label: 'Gross profit', type: 'money' }]} height={240}
                onSelect={(r) => r._drill && open(r._drill.report, r._drill.params)} />
            </div>
            <div className="rounded-xl border bg-card p-4 shadow-sm">
              <div className="mb-2 flex items-center justify-between">
                <h3 className="text-sm font-semibold">{tx('Sales by product category')}</h3>
                <button type="button" onClick={() => open('category-sales')} className="inline-flex items-center gap-1 text-xs text-primary hover:underline">{tx('Details')}<ArrowRight className="h-3 w-3" /></button>
              </div>
              <ReportChart spec={{ kind: 'hbar', x: 'label', stacked: false, series: [{ key: 'revenue', label: tx('Net sales'), kind: 'bar' }] }}
                rows={dash.byCategory} columns={[{ key: 'revenue', label: 'Net sales', type: 'money' }]} height={240}
                onSelect={(r) => r._drill && open(r._drill.report, r._drill.params)} />
            </div>
          </div>
        </>
      ) : (
        <div className="flex h-60 items-center justify-center"><Loader2 className="h-6 w-6 animate-spin text-muted-foreground" /></div>
      )}

      <section className="space-y-3">
        <div className="flex flex-wrap items-center justify-between gap-3">
          <h2 className="text-lg font-semibold">{tx('All sales reports')}</h2>
          <div className="relative">
            <Search className="pointer-events-none absolute left-2.5 top-2.5 h-4 w-4 text-muted-foreground" />
            <Input className="h-9 w-72 pl-8" placeholder={tx('Search reports…')} value={q} onChange={(e) => setQ(e.target.value)} />
          </div>
        </div>
        <div className="grid gap-4 md:grid-cols-2 xl:grid-cols-3">
          {REPORT_PAGES.map((p) => {
            const reports = (catalog?.categories ?? []).filter((c) => p.categories.includes(c.key)).flatMap((c) => c.reports)
              .filter((r) => !needle || `${r.title} ${r.description}`.toLowerCase().includes(needle));
            if (catalog && reports.length === 0) return null;
            const Icon = p.icon;
            return (
              <div key={p.slug} className="flex flex-col rounded-xl border bg-card p-4 shadow-sm">
                <Link to={`/sales/reports/${p.slug}`} className="group flex items-start gap-3">
                  <span className="flex h-9 w-9 shrink-0 items-center justify-center rounded-lg bg-primary/10 text-primary"><Icon className="h-5 w-5" /></span>
                  <span className="min-w-0">
                    <span className="flex items-center gap-1 font-semibold group-hover:text-primary">{tx(p.title)}<span className="text-xs font-normal text-muted-foreground">· {reports.length}</span></span>
                    <span className="block text-xs text-muted-foreground">{tx(p.description)}</span>
                  </span>
                </Link>
                <ul className="mt-3 flex flex-wrap gap-1.5">
                  {reports.slice(0, needle ? 50 : 8).map((r) => (
                    <li key={r.key}>
                      <button type="button" onClick={() => open(r.key)} title={r.description}
                        className="rounded-full border px-2.5 py-1 text-xs text-foreground/80 hover:border-primary/50 hover:bg-primary/5 hover:text-primary">{r.title}</button>
                    </li>
                  ))}
                  {!needle && reports.length > 8 && (
                    <li><Link to={`/sales/reports/${p.slug}`} className="inline-block px-2 py-1 text-xs text-primary hover:underline">+{reports.length - 8} {tx('more')}</Link></li>
                  )}
                </ul>
              </div>
            );
          })}
        </div>
      </section>
    </div>
  );
}

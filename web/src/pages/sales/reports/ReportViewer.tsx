import { useMemo, useState, type ReactNode } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import {
  ArrowDown, ArrowUp, BarChart3, ChevronRight, Columns2, Download, FileSpreadsheet, FileText, Info, Loader2, Printer, RotateCcw,
  Search, Table2,
} from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { SearchableSelect } from '@/components/ui/searchable-select';
import {
  DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu';
import { exportPDF } from '@/lib/export-pdf';
import { notify } from '@/lib/notify';
import { tx } from '@/lib/i18n/tx';
import { cn } from '@/lib/utils';
import {
  downloadReport, useReportCatalog, useReportLookups, useReportRun, type ReportColumn, type ReportDef, type ReportKpi,
  type ReportParams, type ReportResult, type ReportRow,
} from '@/features/sales/report-center-api';
import { ReportChart } from './ReportChart';
import { formatCell, NUMERIC, statusTone, TONE_CLASS } from './report-format';
import { reportHref } from './report-pages';
import { PERIODS, periodRange, requestParams } from './report-period';

// ------------------------------------------------------------------ filters

const STATUS_OPTIONS: Record<string, string[]> = {
  order: ['draft', 'pending_approval', 'confirmed', 'in_fulfilment', 'done', 'cancelled'],
  quotation: ['draft', 'pending_approval', 'approved', 'sent', 'accepted', 'rejected', 'expired', 'cancelled', 'converted'],
  invoice: ['not_paid', 'partial', 'paid', 'overpaid'],
  customer: ['active', 'at_risk', 'inactive', 'never'],
  return: ['requested', 'approved', 'received', 'inspected', 'closed', 'rejected', 'cancelled'],
};

function statusKind(key: string): string {
  if (key.startsWith('quotation')) return 'quotation';
  if (key.startsWith('invoice')) return 'invoice';
  if (key === 'customer-activity') return 'customer';
  if (key.startsWith('return')) return 'return';
  return 'order';
}

function dimensionOptions(key: string): Array<[string, string]> {
  if (key === 'quotation-conversion-rate') return [['salesperson', tx('Salesperson')], ['customer', tx('Customer')], ['branch', tx('Branch')], ['month', tx('Month')]];
  if (key === 'target-vs-actual') return [['', tx('Scope and month')], ['month', tx('Month')], ['salesperson', tx('Salesperson')]];
  return [['customer', tx('Customer')], ['product', tx('Product')], ['category', tx('Product category')], ['customerCategory', tx('Customer type')],
    ['branch', tx('Branch')], ['salesperson', tx('Salesperson')], ['team', tx('Sales team')], ['channel', tx('Channel')]];
}

function defaultDimension(key: string): string {
  return ({ 'sales-comparison': 'branch', 'sales-matrix': 'product', 'discount-impact': 'category', 'quotation-conversion-rate': 'salesperson' } as Record<string, string>)[key] ?? '';
}

function defaultMeasure(key: string): string {
  return ['product-trend', 'product-by-branch'].includes(key) ? 'quantity' : 'revenue';
}

const ALL = '__all';
const selectCls = 'h-9 rounded-md border bg-background px-2 text-sm';

function Field({ label, children }: { label: string; children: ReactNode }) {
  return (
    <label className="flex min-w-0 flex-col gap-1">
      <span className="text-[11px] font-medium uppercase tracking-wide text-muted-foreground">{label}</span>
      {children}
    </label>
  );
}

export function ReportFilters({ def, state, onChange }: { def: ReportDef; state: ReportParams; onChange: (patch: ReportParams) => void }) {
  const { data: lk } = useReportLookups();
  const has = (k: string) => def.filters.includes(k);
  const period = state.period ?? 'this_year';
  const pick = (label: string, key: string, options: Array<{ id: string; name: string }> | undefined) => (
    <Field label={label}>
      <SearchableSelect className="w-52" value={state[key] || ALL} onValueChange={(v) => onChange({ [key]: v === ALL ? '' : v })}
        options={[{ value: ALL, label: tx('All') }, ...(options ?? []).map((o) => ({ value: o.id, label: o.name }))]} />
    </Field>
  );
  const plain = (label: string, key: string, options: Array<[string, string]>, all = true, fallback = '') => (
    <Field label={label}>
      <select className={selectCls} value={state[key] || fallback} onChange={(e) => onChange({ [key]: e.target.value })}>
        {all && <option value="">{tx('All')}</option>}
        {options.map(([v, l]) => <option key={v} value={v}>{l}</option>)}
      </select>
    </Field>
  );
  const range = periodRange(period);
  return (
    <div className="flex flex-wrap items-end gap-3 rounded-xl border bg-card p-3 shadow-sm print:hidden">
      {has('period') && (
        <>
          {plain(tx('Period'), 'period', PERIODS, false, 'this_year')}
          {period === 'custom' ? (
            <>
              <Field label={tx('From')}><Input type="date" className="w-40" value={state.from ?? ''} onChange={(e) => onChange({ from: e.target.value })} /></Field>
              <Field label={tx('To')}><Input type="date" className="w-40" value={state.to ?? ''} onChange={(e) => onChange({ to: e.target.value })} /></Field>
            </>
          ) : range && <span className="pb-2 text-xs text-muted-foreground">{range[0]} → {range[1]}</span>}
        </>
      )}
      {has('asOf') && <Field label={tx('As of')}><Input type="date" className="w-40" value={state.asOf ?? ''} onChange={(e) => onChange({ asOf: e.target.value })} /></Field>}
      {has('branch') && plain(tx('Branch'), 'branchId', (lk?.branches ?? []).map((b) => [b.id, b.name]))}
      {has('salesperson') && pick(tx('Salesperson'), 'salespersonId', lk?.salespeople)}
      {has('customer') && pick(tx('Customer'), 'partnerId', lk?.customers)}
      {has('product') && pick(tx('Product'), 'productId', lk?.products)}
      {has('category') && plain(tx('Product category'), 'categoryId', (lk?.categories ?? []).map((b) => [b.id, b.name]))}
      {has('customerCategory') && plain(tx('Customer type'), 'customerCategoryId', (lk?.customerCategories ?? []).map((b) => [b.id, b.name]))}
      {has('channel') && plain(tx('Channel'), 'channel', (lk?.channels ?? []).map((b) => [b.id, b.name]))}
      {has('status') && plain(tx('Status'), 'status', STATUS_OPTIONS[statusKind(def.key)].map((s) => [s, s.replace(/_/g, ' ')]))}
      {has('groupBy') && plain(tx('Group by'), 'groupBy', [['day', tx('Day')], ['week', tx('Week')], ['month', tx('Month')], ['quarter', tx('Quarter')], ['year', tx('Year')]], false, 'month')}
      {has('dimension') && plain(tx('Analyse by'), 'dimension', dimensionOptions(def.key), false, defaultDimension(def.key))}
      {has('compare') && plain(tx('Compare with'), 'compare', [['previous_period', tx('Previous period')], ['previous_year', tx('Same period last year')]], false, 'previous_period')}
      {has('measure') && plain(tx('Measure'), 'measure', [['revenue', tx('Net sales')], ['quantity', tx('Quantity')], ['margin', tx('Gross profit')]], false, defaultMeasure(def.key))}
      {has('threshold') && <Field label={tx('Threshold %')}><Input type="number" className="w-24" value={state.threshold ?? ''} placeholder="10" onChange={(e) => onChange({ threshold: e.target.value })} /></Field>}
      {has('days') && <Field label={tx('Days')}><Input type="number" className="w-24" value={state.days ?? ''} placeholder={def.key === 'customer-activity' ? '90' : def.key.includes('due') ? '14' : '7'} onChange={(e) => onChange({ days: e.target.value })} /></Field>}
      {has('limit') && <Field label={tx('Show top')}><Input type="number" className="w-24" value={state.limit ?? ''} placeholder="10" onChange={(e) => onChange({ limit: e.target.value })} /></Field>}
      <Button variant="ghost" size="sm" className="ml-auto" onClick={() => onChange(Object.fromEntries(Object.keys(state).filter((k) => k !== 'r' && k !== 'view').map((k) => [k, ''])))}>
        <RotateCcw className="mr-1.5 h-3.5 w-3.5" />{tx('Reset')}
      </Button>
    </div>
  );
}

// ------------------------------------------------------------------ KPI tiles

export function KpiTiles({ kpis, onClick }: { kpis: Array<ReportKpi & { onClick?: () => void }>; onClick?: (k: ReportKpi) => void }) {
  if (kpis.length === 0) return null;
  return (
    <div className="grid grid-cols-2 gap-3 sm:grid-cols-3 lg:grid-cols-5">
      {kpis.map((k) => {
        const clickable = !!(k.onClick || onClick);
        return (
          <button key={k.key} type="button" disabled={!clickable} onClick={() => (k.onClick ? k.onClick() : onClick?.(k))}
            className={cn('rounded-xl border bg-card p-3.5 text-left shadow-sm transition-colors', clickable && 'hover:border-primary/40 hover:bg-muted/30')}>
            <p className="truncate text-[11px] font-medium uppercase tracking-wide text-muted-foreground">{k.label}</p>
            <p className="mt-1 truncate text-xl font-semibold tabular-nums">{formatCell(k.value, k.type)}</p>
            {k.hint && <p className="mt-0.5 truncate text-xs text-muted-foreground">{k.hint}</p>}
          </button>
        );
      })}
    </div>
  );
}

// ------------------------------------------------------------------ table

function Cell({ col, row }: { col: ReportColumn; row: ReportRow }) {
  const v = row[col.key];
  if (col.type === 'status' && v != null && v !== '') {
    return <span className={cn('whitespace-nowrap rounded-full px-2 py-0.5 text-xs font-medium', TONE_CLASS[statusTone(String(v))])}>{formatCell(v, 'status')}</span>;
  }
  const txt = formatCell(v, col.type, row);
  const negative = NUMERIC.includes(col.type) && typeof v === 'number' && v < 0;
  return <span className={cn(negative && 'text-rose-600 dark:text-rose-400')}>{txt}</span>;
}

function ReportTable({ report, onDrill }: { report: ReportResult; onDrill: (row: ReportRow) => void }) {
  const [sort, setSort] = useState<{ key: string; dir: 1 | -1 } | null>(null);
  const [q, setQ] = useState('');
  const [limit, setLimit] = useState(200);
  const rows = useMemo(() => {
    let r = report.rows;
    if (q.trim()) {
      const needle = q.trim().toLowerCase();
      r = r.filter((row) => report.columns.some((c) => String(row[c.key] ?? '').toLowerCase().includes(needle)));
    }
    if (sort) {
      r = [...r].sort((a, b) => {
        const x = a[sort.key];
        const y = b[sort.key];
        if (typeof x === 'number' || typeof y === 'number') return ((Number(x ?? -Infinity)) - Number(y ?? -Infinity)) * sort.dir;
        return String(x ?? '').localeCompare(String(y ?? '')) * sort.dir;
      });
    }
    return r;
  }, [report, q, sort]);
  const right = (c: ReportColumn) => NUMERIC.includes(c.type) || c.type === 'auto';
  const hasTotals = Object.keys(report.totals).length > 0;
  return (
    <div className="rounded-xl border bg-card shadow-sm">
      <div className="flex flex-wrap items-center gap-2 border-b px-3 py-2 print:hidden">
        <div className="relative">
          <Search className="pointer-events-none absolute left-2.5 top-2.5 h-4 w-4 text-muted-foreground" />
          <Input className="h-9 w-64 pl-8" placeholder={tx('Search in this report…')} value={q} onChange={(e) => setQ(e.target.value)} />
        </div>
        <span className="text-xs text-muted-foreground">{rows.length} {tx('rows')}</span>
      </div>
      <div className="max-h-[70vh] overflow-auto">
        <table className="w-full text-sm">
          <thead className="sticky top-0 z-10 bg-muted/80 text-left text-xs font-semibold uppercase tracking-wide text-muted-foreground backdrop-blur">
            <tr>
              {report.columns.map((c) => (
                <th key={c.key} className={cn('cursor-pointer select-none whitespace-nowrap px-3 py-2 hover:text-foreground', right(c) && 'text-right')}
                  onClick={() => setSort((s) => (s?.key === c.key ? (s.dir === -1 ? { key: c.key, dir: 1 } : null) : { key: c.key, dir: -1 }))}>
                  <span className="inline-flex items-center gap-1">
                    {c.label}
                    {sort?.key === c.key && (sort.dir === -1 ? <ArrowDown className="h-3 w-3" /> : <ArrowUp className="h-3 w-3" />)}
                  </span>
                </th>
              ))}
              <th className="w-6" />
            </tr>
          </thead>
          <tbody>
            {rows.length === 0 && (
              <tr><td colSpan={report.columns.length + 1} className="px-3 py-10 text-center text-sm text-muted-foreground">{tx('No data for these filters.')}</td></tr>
            )}
            {rows.slice(0, limit).map((r, i) => {
              const drill = !!(r._route || r._drill);
              return (
                <tr key={i} onClick={drill ? () => onDrill(r) : undefined}
                  className={cn('border-t transition-colors', drill && 'cursor-pointer hover:bg-primary/5')}>
                  {report.columns.map((c, ci) => (
                    <td key={c.key} className={cn('px-3 py-2', right(c) ? 'whitespace-nowrap text-right tabular-nums' : 'max-w-[280px] truncate', ci === 0 && 'font-medium')}>
                      <Cell col={c} row={r} />
                    </td>
                  ))}
                  <td className="pr-2 text-muted-foreground">{drill && <ChevronRight className="h-4 w-4" />}</td>
                </tr>
              );
            })}
          </tbody>
          {hasTotals && rows.length > 0 && (
            <tfoot className="sticky bottom-0 bg-muted/90 font-semibold backdrop-blur">
              <tr className="border-t-2">
                {report.columns.map((c) => (
                  <td key={c.key} className={cn('whitespace-nowrap px-3 py-2', right(c) && 'text-right tabular-nums')}>
                    {report.totals[c.key] !== undefined ? formatCell(report.totals[c.key], c.type === 'auto' ? 'number' : c.type) : ''}
                  </td>
                ))}
                <td />
              </tr>
            </tfoot>
          )}
        </table>
      </div>
      {rows.length > limit && (
        <div className="border-t p-2 text-center print:hidden">
          <Button variant="ghost" size="sm" onClick={() => setLimit((l) => l + 500)}>{tx('Show more')} ({rows.length - limit})</Button>
        </div>
      )}
    </div>
  );
}

// ------------------------------------------------------------------ viewer

type View = 'split' | 'table' | 'chart';

function cellsFor(report: ReportResult): { headers: string[]; body: string[][]; foot: string[][] } {
  const headers = report.columns.map((c) => c.label);
  const body = report.rows.map((r) => report.columns.map((c) => formatCell(r[c.key], c.type, r)));
  const foot = Object.keys(report.totals).length
    ? [report.columns.map((c) => (report.totals[c.key] !== undefined ? formatCell(report.totals[c.key], c.type === 'auto' ? 'number' : c.type) : ''))]
    : [];
  return { headers, body, foot };
}

function printReport(report: ReportResult) {
  const { headers, body, foot } = cellsFor(report);
  const esc = (s: string) => s.replace(/[&<>"]/g, (ch) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[ch] as string));
  const num = report.columns.map((c) => NUMERIC.includes(c.type));
  const w = window.open('', '_blank', 'width=1100,height=800');
  if (!w) return;
  w.document.write(`<!doctype html><html><head><meta charset="utf-8"><title>${esc(report.title)}</title><style>
    body{font:12px system-ui,sans-serif;color:#111;margin:24px}h1{font-size:18px;margin:0}p{color:#555;margin:4px 0 12px}
    .k{display:flex;flex-wrap:wrap;gap:8px;margin-bottom:12px}.k div{border:1px solid #ddd;border-radius:6px;padding:6px 10px}
    .k b{display:block;font-size:14px}table{border-collapse:collapse;width:100%}th,td{border-bottom:1px solid #e5e5e5;padding:4px 6px;text-align:left}
    th{background:#f4f4f4;font-size:11px;text-transform:uppercase}.r{text-align:right;font-variant-numeric:tabular-nums}tfoot td{font-weight:700;border-top:2px solid #999}
    @page{size:${headers.length > 7 ? 'landscape' : 'portrait'};margin:12mm}</style></head><body>
    <h1>${esc(report.title)}</h1><p>${esc(report.from)} – ${esc(report.to)} · ${esc(tx('Printed'))} ${new Date().toLocaleString()}</p>
    <div class="k">${report.kpis.map((k) => `<div>${esc(k.label)}<b>${esc(formatCell(k.value, k.type))}</b></div>`).join('')}</div>
    <table><thead><tr>${headers.map((h, i) => `<th class="${num[i] ? 'r' : ''}">${esc(h)}</th>`).join('')}</tr></thead>
    <tbody>${body.map((r) => `<tr>${r.map((c, i) => `<td class="${num[i] ? 'r' : ''}">${esc(c)}</td>`).join('')}</tr>`).join('')}</tbody>
    <tfoot>${foot.map((r) => `<tr>${r.map((c, i) => `<td class="${num[i] ? 'r' : ''}">${esc(c)}</td>`).join('')}</tr>`).join('')}</tfoot></table>
    ${report.notes.map((n) => `<p>${esc(n)}</p>`).join('')}</body></html>`);
  w.document.close();
  w.focus();
  setTimeout(() => w.print(), 300);
}

/** One report: filters, KPI tiles, chart / table toggle, drill-down, export (Excel, CSV, PDF) and print. */
export function ReportViewer({ def, state, onChange }: { def: ReportDef; state: ReportParams; onChange: (patch: ReportParams) => void }) {
  const params = useMemo(() => requestParams(state), [state]);
  const { data: report, isFetching, error } = useReportRun(def.key, params);
  const { data: catalog } = useReportCatalog();
  const navigate = useNavigate();
  const [busy, setBusy] = useState(false);
  const view: View = (state.view as View) || 'split';
  const hasChart = !!report && report.chart.kind !== 'none';

  const drill = (row: ReportRow) => {
    if (row._drill) {
      const p: ReportParams = {};
      for (const [k, v] of Object.entries(state)) if (v && k !== 'r') p[k] = v;
      navigate(reportHref(catalog, row._drill.report, { ...p, ...row._drill.params }));
    } else if (row._route) {
      navigate(row._route);
    }
  };
  const exportFile = async (format: 'xlsx' | 'csv') => {
    setBusy(true);
    try {
      await downloadReport(def.key, params, format);
    } catch {
      notify.error(tx('Export failed'));
    } finally {
      setBusy(false);
    }
  };
  const exportPdf = () => {
    if (!report) return;
    const { headers, body, foot } = cellsFor(report);
    exportPDF(`${def.key}_${report.from}_${report.to}`, report.title, headers, body, {
      subtitle: `${report.from} – ${report.to}${report.kpis.length ? ' · ' + report.kpis.map((k) => `${k.label}: ${formatCell(k.value, k.type)}`).join(' · ') : ''}`,
      align: report.columns.map((c) => (NUMERIC.includes(c.type) ? 'right' : 'left')), foot,
    });
  };

  return (
    <div className="min-w-0 space-y-4">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div className="min-w-0">
          <h2 className="flex items-center gap-2 text-lg font-semibold">
            {def.title}
            {isFetching && <Loader2 className="h-4 w-4 animate-spin text-muted-foreground" />}
          </h2>
          <p className="text-sm text-muted-foreground">{def.description}</p>
        </div>
        <div className="flex items-center gap-2 print:hidden">
          {hasChart && (
            <div className="flex rounded-lg border bg-card p-0.5" role="tablist" aria-label={tx('View')}>
              {([['split', Columns2, tx('Both')], ['chart', BarChart3, tx('Chart')], ['table', Table2, tx('Table')]] as const).map(([v, Icon, label]) => (
                <button key={v} type="button" role="tab" aria-selected={view === v} onClick={() => onChange({ view: v === 'split' ? '' : v })}
                  className={cn('flex items-center gap-1.5 rounded-md px-2.5 py-1.5 text-xs font-medium', view === v ? 'bg-primary text-primary-foreground' : 'text-muted-foreground hover:bg-muted')}>
                  <Icon className="h-3.5 w-3.5" />{label}
                </button>
              ))}
            </div>
          )}
          <DropdownMenu>
            <DropdownMenuTrigger asChild>
              <Button variant="outline" size="sm" disabled={!report || busy}>
                {busy ? <Loader2 className="mr-1.5 h-4 w-4 animate-spin" /> : <Download className="mr-1.5 h-4 w-4" />}{tx('Export')}
              </Button>
            </DropdownMenuTrigger>
            <DropdownMenuContent align="end">
              <DropdownMenuItem onClick={() => exportFile('xlsx')}><FileSpreadsheet className="mr-2 h-4 w-4" />{tx('Excel (.xlsx)')}</DropdownMenuItem>
              <DropdownMenuItem onClick={() => exportFile('csv')}><FileText className="mr-2 h-4 w-4" />{tx('CSV')}</DropdownMenuItem>
              <DropdownMenuItem onClick={exportPdf}><FileText className="mr-2 h-4 w-4" />{tx('PDF')}</DropdownMenuItem>
            </DropdownMenuContent>
          </DropdownMenu>
          <Button variant="outline" size="sm" disabled={!report} onClick={() => report && printReport(report)}>
            <Printer className="mr-1.5 h-4 w-4" />{tx('Print')}
          </Button>
        </div>
      </div>

      <ReportFilters def={def} state={state} onChange={onChange} />

      {error && <div className="rounded-xl border border-rose-200 bg-rose-50 p-4 text-sm text-rose-800 dark:border-rose-900 dark:bg-rose-950/40 dark:text-rose-300">{tx('Could not load this report.')} {(error as { response?: { data?: { message?: string } } }).response?.data?.message ?? ''}</div>}
      {!report && !error && <div className="flex h-60 items-center justify-center"><Loader2 className="h-6 w-6 animate-spin text-muted-foreground" /></div>}

      {report && (
        <div className={cn('space-y-4 transition-opacity', isFetching && 'opacity-70')}>
          <KpiTiles kpis={report.kpis} />
          {hasChart && view !== 'table' && (
            <div className="rounded-xl border bg-card p-3 shadow-sm">
              <ReportChart spec={report.chart} rows={report.chartData ?? report.rows} columns={report.columns} height={view === 'chart' ? 440 : 300}
                onSelect={report.chartData ? undefined : (r) => (r._route || r._drill ? drill(r) : undefined)} />
            </div>
          )}
          {view !== 'chart' && <ReportTable report={report} onDrill={drill} />}
          {report.notes.length > 0 && (
            <div className="space-y-1 text-xs text-muted-foreground">
              {report.notes.map((n) => <p key={n} className="flex items-start gap-1.5"><Info className="mt-0.5 h-3.5 w-3.5 shrink-0" />{n}</p>)}
            </div>
          )}
        </div>
      )}
    </div>
  );
}

export function ReportLink({ to, children }: { to: string; children: ReactNode }) {
  return <Link to={to} className="text-primary hover:underline">{children}</Link>;
}

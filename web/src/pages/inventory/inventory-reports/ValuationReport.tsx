import { useQuery } from '@tanstack/react-query';
import { useNavigate } from 'react-router-dom';
import { Badge } from '@/components/ui/badge';
import { Card } from '@/components/ui/card';
import { api } from '@/lib/api';
import { cn } from '@/lib/utils';
import { date } from '@/lib/format';
import { DataTable, Kpi, KpiGrid, ReportState, money, qty, type Column } from './shared';
import { FilterBar, type useReportFilters } from './filters';
import { tx } from '@/lib/i18n/tx';

interface ValuationRow {
  productId: string;
  code: string;
  name: string;
  category: string | null;
  uom: string | null;
  quantity: number;
  avgCost: number;
  value: number;
  salesPrice: number;
  retailValue: number;
  minQuantity: number | null;
  stockStatus: 'in_stock' | 'low' | 'out' | 'negative';
  lastMovedAt: string | null;
  locations: { locationId: string; code: string; name: string; quantity: number }[];
}

interface ValuationResponse {
  data: ValuationRow[];
  totals: { items: number; quantity: number; value: number; retailValue: number; inStock: number; low: number; out: number; negative: number };
  byCategory: { category: string; items: number; quantity: number; value: number; share: number }[];
}

const STATUS: Record<ValuationRow['stockStatus'], { label: string; className: string }> = {
  in_stock: { label: tx('In stock'), className: 'bg-emerald-100 text-emerald-700 border-transparent' },
  low: { label: tx('Low'), className: 'bg-amber-100 text-amber-700 border-transparent' },
  out: { label: tx('Out'), className: 'bg-muted text-muted-foreground border-transparent' },
  negative: { label: tx('Negative'), className: 'bg-red-100 text-red-700 border-transparent' },
};

export function ValuationReport({ ctl }: { ctl: ReturnType<typeof useReportFilters> }) {
  const navigate = useNavigate();
  const { filters: f, set, toQuery } = ctl;
  const qs = toQuery(['locationId', 'categoryId', 'productId', 'search', 'status', 'includeZero']);

  const report = useQuery<ValuationResponse>({
    queryKey: ['inventory-report', 'valuation', qs],
    queryFn: async () => (await api.get(`/inventory/reports/valuation?${qs}`)).data,
  });
  const t = report.data?.totals;

  const columns: Column<ValuationRow>[] = [
    { key: 'code', header: tx('Code'), render: (r) => <span className="font-mono text-xs text-muted-foreground">{r.code}</span>, sort: (r) => r.code },
    { key: 'name', header: tx('Item'), className: 'whitespace-nowrap font-medium', render: (r) => <>{r.name}{r.uom && <span className="text-xs text-muted-foreground"> · {r.uom}</span>}</>, sort: (r) => r.name, footer: tx('Totals — {{v1}} item(s)', { v1: t?.items ?? 0 }) },
    { key: 'category', header: tx('Category'), render: (r) => <span className="text-muted-foreground">{r.category ?? '—'}</span>, sort: (r) => r.category },
    { key: 'status', header: tx('Status'), align: 'center', render: (r) => <Badge className={STATUS[r.stockStatus].className}>{STATUS[r.stockStatus].label}</Badge>, sort: (r) => r.stockStatus },
    {
      key: 'locations', header: tx('Locations'),
      render: (r) => <span className="text-xs text-muted-foreground">{r.locations.map((l) => `${l.code}: ${qty(l.quantity)}`).join(' · ') || '—'}</span>,
      csv: (r) => r.locations.map((l) => `${l.code}: ${l.quantity}`).join(' | '),
    },
    { key: 'quantity', header: tx('On Hand'), align: 'right', render: (r) => <span className={cn('font-semibold', r.quantity < 0 && 'text-destructive')}>{qty(r.quantity)}</span>, sort: (r) => r.quantity, footer: qty(t?.quantity) },
    { key: 'minQuantity', header: tx('Par'), align: 'right', render: (r) => (r.minQuantity != null ? qty(r.minQuantity) : '—'), sort: (r) => r.minQuantity },
    { key: 'avgCost', header: tx('Avg Cost'), align: 'right', render: (r) => money(r.avgCost), sort: (r) => r.avgCost },
    { key: 'value', header: tx('Stock Value'), align: 'right', render: (r) => <span className="font-semibold">{money(r.value)}</span>, sort: (r) => r.value, footer: money(t?.value) },
    { key: 'share', header: tx('% of Value'), align: 'right', render: (r) => (t?.value ? `${((r.value / t.value) * 100).toFixed(1)}%` : '—'), sort: (r) => r.value },
    { key: 'retailValue', header: tx('Retail Value'), align: 'right', render: (r) => (r.salesPrice ? money(r.retailValue) : '—'), sort: (r) => r.retailValue, footer: money(t?.retailValue) },
    { key: 'lastMovedAt', header: tx('Last Moved'), render: (r) => <span className="whitespace-nowrap text-xs text-muted-foreground">{date(r.lastMovedAt)}</span>, sort: (r) => r.lastMovedAt },
  ];

  const statusTile = (key: string, label: string, count: number | undefined, tone?: 'good' | 'bad' | 'warn') => (
    <button type="button" className="text-left" onClick={() => set({ status: f.status === key ? 'all' : key })}>
      <div className={cn('rounded-lg ring-offset-2', f.status === key && 'ring-2 ring-primary')}>
        <Kpi label={label} value={(count ?? 0).toLocaleString()} tone={tone} hint={f.status === key ? tx('Filtering — click to clear') : tx('Click to filter')} />
      </div>
    </button>
  );

  return (
    <div className="space-y-4">
      <FilterBar ctl={ctl} show={['location', 'category', 'product', 'search', 'status', 'zero']} />
      <ReportState query={report}>
        {t && (
          <KpiGrid>
            <Kpi label={tx('Stock value (cost)')} value={money(t.value)} hint={tx('{{items}} item(s) shown', { items: t.items })} />
            <Kpi label={tx('Retail value')} value={money(t.retailValue)} hint={t.retailValue > t.value ? tx('Potential margin {{v1}}', { v1: money(t.retailValue - t.value) }) : undefined} />
            {statusTile('in_stock', tx('In stock'), t.inStock, 'good')}
            {statusTile('low', tx('Low stock'), t.low, 'warn')}
            {statusTile('out', tx('Out of stock'), t.out)}
            {statusTile('negative', tx('Negative stock'), t.negative, 'bad')}
          </KpiGrid>
        )}

        {report.data && report.data.byCategory.length > 0 && (
          <Card className="p-3">
            <div className="mb-2 text-sm font-medium">{tx('Value by category')}</div>
            <div className="space-y-1.5">
              {report.data.byCategory.slice(0, 12).map((c) => (
                <div key={c.category} className="grid grid-cols-[minmax(0,10rem)_1fr_auto] items-center gap-3 text-sm">
                  <span className="truncate" title={c.category}>{c.category}</span>
                  <div className="h-2 overflow-hidden rounded bg-muted">
                    <div className="h-full rounded bg-primary" style={{ width: `${Math.max(1, c.share)}%` }} />
                  </div>
                  <span className="whitespace-nowrap font-mono text-xs tabular-nums">
                    {money(c.value)} · {c.share}% · {c.items} {tx('item(s)')}
                  </span>
                </div>
              ))}
            </div>
          </Card>
        )}

        <DataTable
          rows={report.data?.data ?? []}
          columns={columns}
          rowKey={(r) => r.productId}
          defaultSort={{ key: 'value', dir: 'desc' }}
          exportName={`inventory-valuation_${new Date().toISOString().slice(0, 10)}`}
          emptyText={tx('No items match the selected filters.')}
          onRowClick={(r) => navigate(`/inventory/items/${r.productId}`)}
          toolbar={<span className="text-xs">{tx('Current on-hand at running average cost.')}</span>}
        />
      </ReportState>
    </div>
  );
}

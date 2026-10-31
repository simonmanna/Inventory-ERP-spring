import { useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { TrendingUp } from 'lucide-react';
import { Card, CardDescription, CardHeader, CardTitle } from '@/components/ui/card';
import { DataTable, type Column } from '@/components/data-table';
import { DateRangeFilter, FilterSelect, ListCard, ListPageHeader, ListToolbar } from '@/components/list';
import { SearchableSelect } from '@/components/ui/searchable-select';
import { usePartners } from '@/features/partners/api';
import { useProducts } from '@/features/products/api';
import { usePriceHistory, type PriceHistoryRow } from '@/features/procurement/api';
import { date, money } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';

/** What was paid for a product over time, per supplier, against what was agreed. */
export function PriceHistoryPage() {
  const [params, setParams] = useSearchParams();
  const productId = params.get('product') ?? '';
  const [partnerId, setPartnerId] = useState('');
  const [from, setFrom] = useState('');
  const [to, setTo] = useState('');
  const { data: products } = useProducts({ page: 1, pageSize: 200 });
  const { data: partners } = usePartners({ page: 1, pageSize: 200 });
  const { data, isLoading, isError } = usePriceHistory({
    productId,
    partnerId: partnerId || undefined,
    from: from || undefined,
    to: to || undefined,
  });

  const columns: Column<PriceHistoryRow>[] = [
    { key: 'orderDate', header: tx('Date'), render: (r) => date(r.orderDate) },
    {
      key: 'orderNumber',
      header: tx('Order'),
      render: (r) => (
        <Link to={`/procurement/purchase-orders/${r.purchaseOrderId}`} className="text-primary hover:underline">
          {r.orderNumber}
        </Link>
      ),
    },
    { key: 'partnerName', header: tx('Supplier'), render: (r) => r.partnerName ?? '-' },
    { key: 'quantity', header: tx('Qty'), className: 'text-right', render: (r) => Number(r.quantity) },
    { key: 'baseUnitPrice', header: tx('Unit price'), className: 'text-right', render: (r) => money(r.baseUnitPrice) },
    {
      key: 'agreedPrice',
      header: tx('Agreed price'),
      className: 'text-right',
      render: (r) => (r.agreedPrice === null ? '-' : money(r.agreedPrice)),
    },
    {
      key: 'variance',
      header: tx('Variance'),
      className: 'text-right',
      render: (r) => (r.variance === null ? '-' : money(r.variance)),
    },
  ];

  const tiles = [
    { label: tx('Lowest'), value: data?.summary.minPrice },
    { label: tx('Highest'), value: data?.summary.maxPrice },
    { label: tx('Weighted average'), value: data?.summary.averagePrice },
    { label: tx('Latest'), value: data?.summary.lastPrice },
  ];

  return (
    <div className="space-y-4">
      <ListPageHeader
        title={tx('Price History')}
        description={tx('Prices from purchase orders, per base unit in base currency.')}
        icon={TrendingUp}
      />
      <ListToolbar>
        <SearchableSelect
          value={productId}
          onValueChange={(v) => setParams(v ? { product: v } : {}, { replace: true })}
          options={(products?.data ?? []).map((p) => ({ value: p.id, label: p.name, code: p.code }))}
          placeholder={tx('Select product')}
          className="w-[260px]"
        />
        <FilterSelect
          value={partnerId}
          onChange={setPartnerId}
          allLabel={tx('All suppliers')}
          options={(partners?.data ?? []).filter((p) => p.isSupplier).map((p) => ({ value: p.id, label: p.name }))}
          className="w-[200px]"
        />
        <DateRangeFilter from={from} to={to} onChange={(f, t) => { setFrom(f); setTo(t); }} />
      </ListToolbar>

      {!productId ? (
        <Card>
          <CardHeader className="text-center text-sm text-muted-foreground">
            {tx('Choose a product to see its prices.')}
          </CardHeader>
        </Card>
      ) : (
        <>
          <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
            {tiles.map((t) => (
              <Card key={t.label}>
                <CardHeader className="pb-4">
                  <CardDescription>{t.label}</CardDescription>
                  <CardTitle className="text-xl">{t.value === null || t.value === undefined ? '-' : money(t.value)}</CardTitle>
                </CardHeader>
              </Card>
            ))}
          </div>
          {data?.summary.truncated && (
            <p className="text-sm text-muted-foreground">{tx('Showing the latest 500 lines. Narrow the dates to see more.')}</p>
          )}
          {isError && <p className="text-sm text-destructive">{tx('Could not load the price history.')}</p>}
          <ListCard>
            <DataTable
              columns={columns}
              data={data?.rows ?? []}
              loading={isLoading}
              getRowId={(r) => `${r.purchaseOrderId}-${r.orderDate}-${r.quantity}-${r.unitPrice}`}
              emptyMessage={tx('No purchases of this product in this period.')}
            />
          </ListCard>
        </>
      )}
    </div>
  );
}

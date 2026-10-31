import { useState } from 'react';
import { BarChart3 } from 'lucide-react';
import { DataTable, type Column } from '@/components/data-table';
import { DateRangeFilter, FilterSelect, ListCard, ListPageHeader, ListToolbar } from '@/components/list';
import { useSupplierPerformance, type SupplierPerformanceRow } from '@/features/procurement/api';
import { usePartners } from '@/features/partners/api';
import { money } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';

const percent = (v: number | null | undefined) => (v === null || v === undefined ? '-' : `${v}%`);

/** Supplier scorecard: delivery, fill rate, quality from inspections and price variance. */
export function SupplierPerformancePage() {
  const [from, setFrom] = useState('');
  const [to, setTo] = useState('');
  const [partnerId, setPartnerId] = useState('');
  const { data, isLoading, isError } = useSupplierPerformance({
    from: from || undefined,
    to: to || undefined,
    partnerId: partnerId || undefined,
  });
  const { data: partners } = usePartners({ page: 1, pageSize: 200 });
  const supplierOptions = (partners?.data ?? [])
    .filter((p) => p.isSupplier)
    .map((p) => ({ value: p.id, label: p.name }));

  const columns: Column<SupplierPerformanceRow>[] = [
    { key: 'partnerName', header: tx('Supplier'), render: (r) => r.partnerName ?? '-' },
    { key: 'orders', header: tx('Orders'), className: 'text-right' },
    { key: 'spend', header: tx('Spend'), className: 'text-right', render: (r) => money(r.spend) },
    { key: 'onTimeRate', header: tx('On time'), className: 'text-right', render: (r) => percent(r.onTimeRate) },
    {
      key: 'avgDaysLate',
      header: tx('Avg days late'),
      className: 'text-right',
      render: (r) => r.avgDaysLate ?? '-',
    },
    { key: 'fillRate', header: tx('Fill rate'), className: 'text-right', render: (r) => percent(r.fillRate) },
    { key: 'qualityRate', header: tx('Quality pass'), className: 'text-right', render: (r) => percent(r.qualityRate) },
    {
      key: 'priceVarianceRate',
      header: tx('Price variance'),
      className: 'text-right',
      render: (r) => percent(r.priceVarianceRate),
    },
    { key: 'score', header: tx('Score'), className: 'text-right font-medium', render: (r) => r.score ?? '-' },
  ];

  return (
    <div className="space-y-4">
      <ListPageHeader
        title={tx('Supplier Performance')}
        description={tx('Score: on time 40, quality 30, fill rate 20, price 10, over the measures with data.')}
        icon={BarChart3}
      />
      <ListToolbar>
        <DateRangeFilter from={from} to={to} onChange={(f, t) => { setFrom(f); setTo(t); }} />
        <FilterSelect
          value={partnerId}
          onChange={setPartnerId}
          allLabel={tx('All suppliers')}
          options={supplierOptions}
          className="w-[200px]"
        />
      </ListToolbar>
      {isError && <p className="text-sm text-destructive">{tx('Could not load the supplier scorecard.')}</p>}
      <ListCard>
        <DataTable
          columns={columns}
          data={data?.rows ?? []}
          loading={isLoading}
          getRowId={(r) => r.partnerId}
          emptyMessage={tx('No purchasing activity in this period.')}
        />
      </ListCard>
    </div>
  );
}

import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { DataTable, type Column } from '@/components/data-table';
import { ReportFilterBar, type ReportFilters } from '@/components/report-filter-bar';
import { useHrDepartments } from '@/features/hr/api';
import { api } from '@/lib/api';
import { money } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';

interface SalesRow {
  key: string;
  label: string;
  quantity: number;
  amount: number;
  sharePercent: number;
}

interface SalesAnalysis {
  rows: SalesRow[];
  totalAmount: number;
  totalQuantity: number;
}

const GROUPS: { value: string; label: () => string }[] = [
  { value: 'product', label: () => tx('Product') },
  { value: 'category', label: () => tx('Category') },
  { value: 'branch', label: () => tx('Branch') },
  { value: 'customer', label: () => tx('Customer') },
  { value: 'salesperson', label: () => tx('Salesperson') },
  { value: 'month', label: () => tx('Month') },
];

function isoDay(d: Date): string {
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
}

/** Net sales of posted invoices by product, branch, customer, salesperson or month (SALES-5). */
export function SalesAnalysisPage() {
  const now = new Date();
  const [filters, setFilters] = useState<ReportFilters>({
    from: isoDay(new Date(now.getFullYear(), now.getMonth(), 1)), to: isoDay(now),
  });
  const [groupBy, setGroupBy] = useState('product');
  const { data: departmentData } = useHrDepartments();
  const departments = (Array.isArray(departmentData) ? departmentData : departmentData?.data ?? []) as
    { id: string; name: string }[];
  const { data, isLoading, isError } = useQuery({
    queryKey: ['sales-analysis', groupBy, filters],
    queryFn: async () => (await api.get<SalesAnalysis>('/reports/ar/sales-analysis', {
      params: { groupBy, from: filters.from, to: filters.to, branchId: filters.branchId, departmentId: filters.departmentId },
    })).data,
  });

  const columns: Column<SalesRow>[] = [
    { key: 'label', header: GROUPS.find((g) => g.value === groupBy)?.label() ?? '' },
    { key: 'quantity', header: tx('Quantity'), className: 'text-right', render: (r) => r.quantity.toLocaleString() },
    { key: 'amount', header: tx('Net sales'), className: 'text-right font-medium', render: (r) => money(r.amount) },
    { key: 'sharePercent', header: tx('Share %'), className: 'text-right' },
  ];

  return (
    <div className="space-y-4">
      <div>
        <h1 className="text-2xl font-semibold">{tx('Sales Analysis')}</h1>
        <p className="text-sm text-muted-foreground">{tx('Net sales of posted ERP and POS invoices. Credit notes are not deducted.')}</p>
      </div>
      <div className="flex flex-wrap items-center gap-2">
        <select
          aria-label={tx('Group by')}
          className="rounded border bg-background px-2 py-1.5 text-sm"
          value={groupBy}
          onChange={(e) => setGroupBy(e.target.value)}
        >
          {GROUPS.map((g) => <option key={g.value} value={g.value}>{g.label()}</option>)}
        </select>
        <ReportFilterBar
          value={filters}
          onChange={setFilters}
          show={{ branch: true, department: true }}
          departments={departments}
        />
      </div>
      {isError ? (
        <p className="text-sm text-destructive">{tx('Could not load')}</p>
      ) : (
        <>
          <DataTable columns={columns} data={data?.rows ?? []} loading={isLoading} getRowId={(r) => r.key} />
          {data && <p className="text-right text-sm font-medium">{tx('Total')}: {money(data.totalAmount)}</p>}
        </>
      )}
    </div>
  );
}

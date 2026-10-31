import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { DataTable, type Column } from '@/components/data-table';
import { ReportFilterBar, type ReportFilters } from '@/components/report-filter-bar';
import { api } from '@/lib/api';
import { money } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';

interface BranchRow {
  branchId: string;
  code?: string;
  name: string;
  income: number;
  expense: number;
  netProfit: number;
  marginPercent: number | null;
  salesAmount: number;
  salesCount: number;
  openReceivables: number;
  stockValue: number;
  deliveries: number;
  deliveredTonnes: number;
  producedQty: number;
}

function monthStart(): string {
  const d = new Date();
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-01`;
}

function today(): string {
  const d = new Date();
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
}

/** Branches side by side with an operational and financial total (BR-2). */
export function BranchPerformancePage() {
  const [filters, setFilters] = useState<ReportFilters>({ from: monthStart(), to: today() });
  const { data, isLoading, isError } = useQuery({
    queryKey: ['branch-comparison', filters.from, filters.to],
    queryFn: async () => (await api.get<{ rows: BranchRow[]; total: BranchRow }>('/reports/branches/comparison', {
      params: { from: filters.from, to: filters.to },
    })).data,
  });

  const columns: Column<BranchRow>[] = [
    { key: 'name', header: tx('Branch') },
    { key: 'income', header: tx('Revenue'), className: 'text-right', render: (r) => money(r.income) },
    { key: 'expense', header: tx('Expenses'), className: 'text-right', render: (r) => money(r.expense) },
    { key: 'netProfit', header: tx('Net profit'), className: 'text-right font-medium', render: (r) => money(r.netProfit) },
    { key: 'marginPercent', header: tx('Margin %'), className: 'text-right', render: (r) => (r.marginPercent === null ? '-' : r.marginPercent) },
    { key: 'openReceivables', header: tx('Open invoices'), className: 'text-right', render: (r) => money(r.openReceivables) },
    { key: 'stockValue', header: tx('Stock value'), className: 'text-right', render: (r) => money(r.stockValue) },
    { key: 'deliveries', header: tx('Deliveries'), className: 'text-right' },
    { key: 'deliveredTonnes', header: tx('Tonnes delivered'), className: 'text-right' },
    { key: 'producedQty', header: tx('Produced'), className: 'text-right' },
  ];

  const rows = data ? [...data.rows, data.total] : [];

  return (
    <div className="space-y-4">
      <div>
        <h1 className="text-2xl font-semibold">{tx('Branch Performance')}</h1>
        <p className="text-sm text-muted-foreground">
          {tx('Profit from the ledger, plus receivables, stock, deliveries and production per branch. Assign locations to branches for stock and production to appear here.')}
        </p>
      </div>
      <ReportFilterBar value={filters} onChange={setFilters} />
      {isError ? (
        <p className="text-sm text-destructive">{tx('Could not load')}</p>
      ) : (
        <div className="overflow-x-auto">
          <DataTable columns={columns} data={rows} loading={isLoading} getRowId={(r) => r.branchId} />
        </div>
      )}
    </div>
  );
}

import { useState } from 'react';
import { Link } from 'react-router-dom';
import { Dialog, DialogContent, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { DataTable, type Column } from '@/components/data-table';
import { DataTablePagination } from '@/components/list';
import { tx } from '@/lib/i18n/tx';
import { useDocuments, type AnalyticsQuery, type DocumentRow } from './api';
import { formatValue, kpiLabel } from './labels';
import type { Drill } from './DashboardView';

const PAGE_SIZE = 25;

/** The screen of each document type the drill-downs list. */
const LINKS: Record<string, (id: string) => string> = {
  quarry_extraction: (id) => `/quarry/extraction/${id}`,
  crushing_run: (id) => `/manufacturing/crushing/runs/${id}`,
  precast_order: (id) => `/manufacturing/precast/orders/${id}`,
  product: (id) => `/products/${id}/edit`,
  inventory_count: () => '/inventory/count',
  stockpile_survey: () => '/manufacturing/crushing/stockpiles',
  purchase_order: (id) => `/procurement/purchase-orders/${id}`,
  invoice: (id) => `/invoices/${id}`,
  pos_invoice: (id) => `/pos/receipts/${id}`,
  vendor_bill: (id) => `/vendor-bills/${id}`,
  fleet_trip: (id) => `/fleet/trips/${id}`,
  fleet_fuel: (id) => `/fleet/fuel/logs/${id}`,
  fleet_work_order: (id) => `/fleet/maintenance/work-orders/${id}`,
  fleet_downtime: () => '/fleet/maintenance/downtime',
};

/** The documents behind a KPI, one page at a time, each a link to its own screen. */
export function DrillDownDialog({ path, drill, query, onClose }: {
  path: string; drill: Drill; query: AnalyticsQuery; onClose: () => void;
}) {
  const [page, setPage] = useState(1);
  const { data, isLoading, isError } = useDocuments(path, drill.kpi, { ...query, ...drill.extra }, page, PAGE_SIZE);
  const columns: Column<DocumentRow>[] = [
    {
      key: 'number',
      header: tx('Document'),
      render: (r) => {
        const link = LINKS[r.type]?.(r.id);
        const text = r.number ?? r.id;
        return link ? <Link className="text-primary hover:underline" to={link}>{text}</Link> : text;
      },
    },
    { key: 'date', header: tx('Date'), render: (r) => formatValue(r.date, 'date') },
    { key: 'party', header: tx('Reference'), render: (r) => r.party ?? '—' },
    { key: 'status', header: tx('Status'), render: (r) => r.status ?? '—' },
    { key: 'quantity', header: tx('Quantity'), className: 'text-right', render: (r) => formatValue(r.quantity, 'qty') },
    { key: 'amount', header: tx('Amount'), className: 'text-right', render: (r) => formatValue(r.amount, 'money') },
  ];
  return (
    <Dialog open onOpenChange={(o) => !o && onClose()}>
      <DialogContent className="max-h-[90vh] overflow-y-auto sm:max-w-4xl">
        <DialogHeader><DialogTitle>{drill.title || kpiLabel(drill.kpi)}</DialogTitle></DialogHeader>
        {isError ? (
          <p className="text-sm text-destructive">{tx('Could not load')}</p>
        ) : (
          <>
            <div className="overflow-x-auto">
              <DataTable columns={columns} data={data?.data ?? []} loading={isLoading} compact
                getRowId={(r) => `${r.type}:${r.id}:${r.number ?? ''}`} emptyMessage={tx('No documents for this figure')} />
            </div>
            {data && (
              <DataTablePagination page={data.meta.page} pageSize={data.meta.pageSize} total={data.meta.total}
                totalPages={data.meta.totalPages} onPageChange={setPage} />
            )}
          </>
        )}
      </DialogContent>
    </Dialog>
  );
}

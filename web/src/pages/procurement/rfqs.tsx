import { Link, useNavigate } from 'react-router-dom';
import { FileText, Plus } from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { DataTable, type Column } from '@/components/data-table';
import {
  DataTablePagination,
  FilterSelect,
  ListCard,
  ListPageHeader,
  ListToolbar,
  SearchInput,
  useListState,
} from '@/components/list';
import { RFQ_STATUS_VARIANT, useRfqs, type RfqRow } from '@/features/procurement/api';
import { date, statusLabel } from '@/lib/format';
import { useAuthStore } from '@/stores/auth.store';
import { tx } from '@/lib/i18n/tx';

/** Requests for quotation: list with status filter. */
export function RfqsPage() {
  const navigate = useNavigate();
  const has = useAuthStore((s) => s.hasPermission);
  const list = useListState({ status: '' });
  const { data, isLoading, isError } = useRfqs({
    page: list.page,
    pageSize: list.pageSize,
    search: list.search || undefined,
    status: list.filters.status || undefined,
  });

  const columns: Column<RfqRow>[] = [
    {
      key: 'rfqNumber',
      header: tx('RFQ'),
      render: (r) => (
        <Link
          to={`/procurement/rfqs/${r.id}`}
          className="font-medium text-primary hover:underline"
          onClick={(e) => e.stopPropagation()}
        >
          {r.rfqNumber}
        </Link>
      ),
    },
    { key: 'title', header: tx('Title') },
    { key: 'dueDate', header: tx('Due date'), render: (r) => (r.dueDate ? date(r.dueDate) : '-') },
    { key: 'suppliers', header: tx('Suppliers'), className: 'text-right' },
    { key: 'quotations', header: tx('Quotations'), className: 'text-right' },
    {
      key: 'status',
      header: tx('Status'),
      render: (r) => <Badge variant={RFQ_STATUS_VARIANT[r.status]}>{statusLabel(r.status)}</Badge>,
    },
  ];

  return (
    <div className="space-y-4">
      <ListPageHeader
        title={tx('Requests for Quotation')}
        description={tx('Ask several suppliers for prices, compare the quotes and award a purchase order.')}
        icon={FileText}
        actions={
          has(PERMISSIONS.procurement.rfq.manage) ? (
            <Button asChild>
              <Link to="/procurement/rfqs/new"><Plus className="mr-2 h-4 w-4" />{tx('New RFQ')}</Link>
            </Button>
          ) : undefined
        }
      />
      <ListToolbar>
        <SearchInput
          value={list.searchInput}
          onChange={list.setSearchInput}
          placeholder={tx('Search by number or title...')}
        />
        <FilterSelect
          value={list.filters.status}
          onChange={(v) => list.setFilter('status', v)}
          allLabel={tx('All statuses')}
          options={[
            { value: 'draft', label: tx('Draft') },
            { value: 'sent', label: tx('Sent') },
            { value: 'awarded', label: tx('Awarded') },
            { value: 'cancelled', label: tx('Cancelled') },
          ]}
        />
      </ListToolbar>
      {isError && <p className="text-sm text-destructive">{tx('Could not load RFQs.')}</p>}
      <ListCard>
        <DataTable
          columns={columns}
          data={data?.data ?? []}
          loading={isLoading}
          getRowId={(r) => r.id}
          onRowClick={(r) => navigate(`/procurement/rfqs/${r.id}`)}
          emptyMessage={tx('No RFQs yet.')}
        />
        {data?.meta && (
          <DataTablePagination
            page={data.meta.page}
            pageSize={data.meta.pageSize}
            total={data.meta.total}
            totalPages={data.meta.totalPages}
            onPageChange={list.setPage}
            onPageSizeChange={list.setPageSize}
            noun={tx('RFQ')}
          />
        )}
      </ListCard>
    </div>
  );
}

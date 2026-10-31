import { Link, useNavigate } from 'react-router-dom';
import { FileText, Plus } from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { Button } from '@/components/ui/button';
import { Badge } from '@/components/ui/badge';
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
import { useExpenses, type Invoice } from '@/features/invoicing/api';
import { usePartners } from '@/features/partners/api';
import { money, date, statusLabel } from '@/lib/format';
import { useAuthStore } from '@/stores/auth.store';
import { tx } from '@/lib/i18n/tx';

const STATUS_VARIANT: Record<string, 'default' | 'secondary' | 'outline' | 'destructive'> = {
  draft: 'secondary',
  posted: 'default',
  paid: 'outline',
  cancelled: 'destructive',
};

/** Accounts-payable bills: list with status and supplier filters; `/expenses` stays the expense register. */
export function VendorBillsPage() {
  const navigate = useNavigate();
  const has = useAuthStore((s) => s.hasPermission);
  const list = useListState({ status: '', partnerId: '' });
  const { data, isLoading, isError } = useExpenses({
    page: list.page,
    pageSize: list.pageSize,
    search: list.search || undefined,
    status: list.filters.status || undefined,
    partnerId: list.filters.partnerId || undefined,
  });
  const { data: partners } = usePartners({ page: 1, pageSize: 200 });
  const supplierOptions = (partners?.data ?? [])
    .filter((p) => p.isSupplier)
    .map((p) => ({ value: p.id, label: p.name }));

  const columns: Column<Invoice>[] = [
    {
      key: 'documentNumber',
      header: tx('Bill'),
      render: (b) => (
        <Link
          to={`/vendor-bills/${b.id}`}
          className="font-medium text-primary hover:underline"
          onClick={(e) => e.stopPropagation()}
        >
          {b.documentNumber}
        </Link>
      ),
    },
    { key: 'partner', header: tx('Supplier'), render: (b) => b.partner?.name ?? '-' },
    { key: 'reference', header: tx('Reference'), render: (b) => b.reference ?? '-' },
    { key: 'issueDate', header: tx('Bill date'), render: (b) => date(b.issueDate) },
    { key: 'dueDate', header: tx('Due date'), render: (b) => (b.dueDate ? date(b.dueDate) : '-') },
    { key: 'totalAmount', header: tx('Total'), className: 'text-right', render: (b) => money(b.totalAmount) },
    { key: 'amountResidual', header: tx('Balance due'), className: 'text-right', render: (b) => money(b.amountResidual) },
    {
      key: 'status',
      header: tx('Status'),
      render: (b) => <Badge variant={STATUS_VARIANT[b.status] ?? 'secondary'}>{statusLabel(b.status)}</Badge>,
    },
  ];

  return (
    <div className="space-y-4">
      <ListPageHeader
        title={tx('Vendor Bills')}
        description={tx('Supplier invoices: enter, match to receipts, post and pay.')}
        icon={FileText}
        actions={
          has(PERMISSIONS.expense.create) ? (
            <Button asChild>
              <Link to="/vendor-bills/new"><Plus className="mr-2 h-4 w-4" />{tx('New vendor bill')}</Link>
            </Button>
          ) : undefined
        }
      />

      <ListToolbar>
        <SearchInput
          value={list.searchInput}
          onChange={list.setSearchInput}
          placeholder={tx('Search by number or reference...')}
        />
        <FilterSelect
          value={list.filters.status}
          onChange={(v) => list.setFilter('status', v)}
          allLabel={tx('All statuses')}
          options={[
            { value: 'draft', label: tx('Draft') },
            { value: 'posted', label: tx('Posted') },
            { value: 'paid', label: tx('Paid') },
            { value: 'cancelled', label: tx('Cancelled') },
          ]}
        />
        <FilterSelect
          value={list.filters.partnerId}
          onChange={(v) => list.setFilter('partnerId', v)}
          allLabel={tx('All suppliers')}
          options={supplierOptions}
          className="w-[200px]"
        />
      </ListToolbar>

      {isError && <p className="text-sm text-destructive">{tx('Could not load vendor bills.')}</p>}

      <ListCard>
        <DataTable
          columns={columns}
          data={data?.data ?? []}
          loading={isLoading}
          getRowId={(b) => b.id}
          onRowClick={(b) => navigate(`/vendor-bills/${b.id}`)}
          emptyMessage={tx('No vendor bills found.')}
        />
        {data?.meta && (
          <DataTablePagination
            page={data.meta.page}
            pageSize={data.meta.pageSize}
            total={data.meta.total}
            totalPages={data.meta.totalPages}
            onPageChange={list.setPage}
            onPageSizeChange={list.setPageSize}
            noun={tx('bill')}
          />
        )}
      </ListCard>
    </div>
  );
}

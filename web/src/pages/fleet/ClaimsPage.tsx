import { useNavigate } from 'react-router-dom';
import { Plus, Receipt } from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { Button } from '@/components/ui/button';
import { DataTable, type Column } from '@/components/data-table';
import {
  DataTablePagination, DateRangeFilter, FilterChips, FilterSelect, ListCard, ListPageHeader, ListToolbar, SearchInput,
  useListState, type ActiveChip,
} from '@/components/list';
import { useAuthStore } from '@/stores/auth.store';
import { date, money } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';
import { CLAIM_STATUS_LABEL, useClaims, type Claim, driverLabel } from '@/features/fleet/drivers-api';
import { toOptions } from './fleet-format';
import { ClaimStatusPill } from './drivers-ui';

const FILTERS = { status: '', settlement: '', from: '', to: '' };

const SETTLEMENT_LABEL: Record<string, string> = {
  expenses: tx('Paid via expenses'),
  payroll: tx('Exported to payroll'),
};

/** Driver allowance & trip-expense claims (fleet 1.2): status / settlement / date filters, approval queue. */
export function ClaimsPage() {
  const navigate = useNavigate();
  const canCreate = useAuthStore((s) => s.hasPermission(PERMISSIONS.fleetDriver.allowance));
  const list = useListState(FILTERS);
  const { status, settlement, from, to } = list.filters;
  const { data, isLoading, isFetching, isError } = useClaims({
    page: list.page,
    pageSize: list.pageSize,
    search: list.search || undefined,
    status: status || undefined,
    settlement: settlement || undefined,
    from: from || undefined,
    to: to || undefined,
  });
  const rows = data?.data ?? [];
  const meta = data?.meta;

  const chips: ActiveChip[] = [
    ...(list.search ? [{ key: 'q', label: `“${list.search}”`, onRemove: () => list.setSearchInput('') }] : []),
    ...(status ? [{ key: 'status', label: CLAIM_STATUS_LABEL[status as keyof typeof CLAIM_STATUS_LABEL] ?? status, onRemove: () => list.setFilter('status', '') }] : []),
    ...(settlement ? [{ key: 'settlement', label: SETTLEMENT_LABEL[settlement] ?? settlement, onRemove: () => list.setFilter('settlement', '') }] : []),
    ...(from || to ? [{ key: 'range', label: `${from || '…'} – ${to || '…'}`, onRemove: () => list.setFilters({ from: '', to: '' }) }] : []),
  ];

  const columns: Column<Claim>[] = [
    { key: 'code', header: tx('Claim'), render: (c) => <span className="font-medium text-primary">{c.code}</span> },
    { key: 'date', header: tx('Date'), render: (c) => <span className="whitespace-nowrap">{date(c.claimDate)}</span> },
    { key: 'driver', header: tx('Driver'), render: (c) => <div><div>{driverLabel(c)}</div><div className="text-xs text-muted-foreground">{c.driverCode}</div></div> },
    { key: 'vehicle', header: tx('Vehicle'), render: (c) => c.vehicleRegistrationPlate ?? c.vehicleCode ?? '—' },
    { key: 'description', header: tx('Description'), render: (c) => c.description ?? '—' },
    { key: 'amount', header: tx('Amount'), className: 'text-right', render: (c) => <span className="tabular-nums">{money(c.totalAmount, c.currencyCode)}</span> },
    { key: 'status', header: tx('Status'), render: (c) => <ClaimStatusPill status={c.status} /> },
  ];

  return (
    <div className="space-y-4">
      <ListPageHeader
        icon={Receipt}
        title={tx('Driver Claims')}
        description={tx('Allowances and trip expenses: submit, approve, then pay through expenses or export to payroll.')}
        actions={canCreate && (
          <Button onClick={() => navigate('/fleet/claims/new')}><Plus className="mr-1.5 h-4 w-4" /> {tx('New claim')}</Button>
        )}
      />
      <ListToolbar chips={<FilterChips chips={chips} onClearAll={list.clearFilters} />}>
        <SearchInput value={list.searchInput} onChange={list.setSearchInput} placeholder={tx('Search claim, driver…')} />
        <FilterSelect value={status} onChange={(v) => list.setFilter('status', v)} options={toOptions(CLAIM_STATUS_LABEL)} allLabel={tx('All statuses')} />
        <FilterSelect value={settlement} onChange={(v) => list.setFilter('settlement', v)} options={toOptions(SETTLEMENT_LABEL)} allLabel={tx('Any settlement')} />
        <DateRangeFilter from={from} to={to} onChange={(f, t) => list.setFilters({ from: f, to: t })} />
      </ListToolbar>
      <ListCard>
        <div className={isFetching && !isLoading ? 'opacity-60 transition-opacity' : 'transition-opacity'}>
          <DataTable
            columns={columns}
            data={rows}
            loading={isLoading}
            loadingRows={8}
            getRowId={(c) => c.id}
            onRowClick={(c) => navigate(`/fleet/claims/${c.id}`)}
            cellClassName="py-2.5 px-4"
            headerRowClassName="h-10"
            emptyMessage={isError ? tx('Could not load the claims. Try again.') : chips.length ? tx('No claims match these filters.') : tx('No claims yet.')}
          />
        </div>
        {meta && (
          <DataTablePagination page={meta.page} pageSize={list.pageSize} total={meta.total} totalPages={meta.totalPages}
            onPageChange={list.setPage} onPageSizeChange={list.setPageSize} noun="claim" />
        )}
      </ListCard>
    </div>
  );
}

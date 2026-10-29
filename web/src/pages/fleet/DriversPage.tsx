import { useNavigate } from 'react-router-dom';
import { Eye, IdCard, Plus } from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { Button } from '@/components/ui/button';
import { DataTable, type Column } from '@/components/data-table';
import {
  DataTablePagination, FilterChips, FilterSelect, ListCard, ListPageHeader, ListToolbar, SearchInput,
  useListState, type ActiveChip,
} from '@/components/list';
import { useAuthStore } from '@/stores/auth.store';
import { date } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';
import { useFleetLookups } from '@/features/fleet/api';
import { DRIVER_STATUS_LABEL, DRIVER_TYPE_LABEL, useDrivers, type Driver } from '@/features/fleet/drivers-api';
import { toOptions } from './fleet-format';
import { DocStatePill, DriverStatusPill } from './drivers-ui';

const FILTERS = { status: '', driverType: '', categoryId: '', documents: '' };

const DOCUMENT_FILTER: Record<string, string> = {
  attention: tx('Documents need attention'),
  expired: tx('Expired documents'),
  expiring: tx('Expiring soon'),
};

/** Drivers & operators (fleet 1.2): profiles with licence state, qualified classes and status filters. */
export function DriversPage() {
  const navigate = useNavigate();
  const canCreate = useAuthStore((s) => s.hasPermission(PERMISSIONS.fleetDriver.create));
  const list = useListState(FILTERS);
  const { status, driverType, categoryId, documents } = list.filters;
  const lookups = useFleetLookups();
  const categories = lookups.data?.categories ?? [];
  const { data, isLoading, isFetching, isError } = useDrivers({
    page: list.page,
    pageSize: list.pageSize,
    search: list.search || undefined,
    status: status || undefined,
    driverType: driverType || undefined,
    categoryId: categoryId || undefined,
    documents: documents || undefined,
    sortBy: list.sort?.by,
    sortOrder: list.sort?.order,
  });
  const rows = data?.data ?? [];
  const meta = data?.meta;

  const chips: ActiveChip[] = [
    ...(list.search ? [{ key: 'q', label: `“${list.search}”`, onRemove: () => list.setSearchInput('') }] : []),
    ...(status ? [{ key: 'status', label: DRIVER_STATUS_LABEL[status as keyof typeof DRIVER_STATUS_LABEL] ?? status, onRemove: () => list.setFilter('status', '') }] : []),
    ...(driverType ? [{ key: 'type', label: DRIVER_TYPE_LABEL[driverType as keyof typeof DRIVER_TYPE_LABEL] ?? driverType, onRemove: () => list.setFilter('driverType', '') }] : []),
    ...(categoryId ? [{ key: 'cat', label: categories.find((c) => c.id === categoryId)?.name ?? categoryId, onRemove: () => list.setFilter('categoryId', '') }] : []),
    ...(documents ? [{ key: 'docs', label: DOCUMENT_FILTER[documents] ?? documents, onRemove: () => list.setFilter('documents', '') }] : []),
  ];

  const columns: Column<Driver>[] = [
    { key: 'code', header: tx('Code'), sortKey: 'code', render: (d) => <span className="font-medium text-primary">{d.code}</span> },
    {
      key: 'name', header: tx('Name'), sortKey: 'name',
      render: (d) => (
        <div className="min-w-[9rem]">
          <div>{d.name ?? '—'}</div>
          <div className="text-xs text-muted-foreground">{[d.employee?.employeeCode, DRIVER_TYPE_LABEL[d.driverType]].filter(Boolean).join(' · ')}</div>
        </div>
      ),
    },
    { key: 'licence', header: tx('Licence'), render: (d) => (d.licenceNumber ? <span>{d.licenceNumber}{d.licenceClasses.length ? <span className="text-xs text-muted-foreground"> · {d.licenceClasses.join(', ')}</span> : null}</span> : '—') },
    { key: 'expires', header: tx('Licence expires'), sortKey: 'licenceExpiresOn', render: (d) => (d.licenceExpiresOn ? date(d.licenceExpiresOn) : '—') },
    { key: 'docs', header: tx('Documents'), render: (d) => <DocStatePill state={d.documentState} /> },
    { key: 'classes', header: tx('Qualified classes'), render: (d) => (d.qualifications.length ? d.qualifications.map((q) => q.name).join(', ') : '—') },
    { key: 'out', header: tx('Checked out'), render: (d) => (d.openCheckout ? d.openCheckout.vehicleRegistrationPlate ?? d.openCheckout.vehicleCode : '—') },
    { key: 'status', header: tx('Status'), sortKey: 'status', render: (d) => <DriverStatusPill status={d.status} /> },
    {
      key: 'actions', header: '', className: 'w-12 text-right',
      render: (d) => (
        <Button variant="ghost" size="icon" className="h-8 w-8" aria-label={tx('View driver')} onClick={() => navigate(`/fleet/drivers/${d.id}`)}>
          <Eye className="h-4 w-4" />
        </Button>
      ),
    },
  ];

  return (
    <div className="space-y-4">
      <ListPageHeader
        icon={IdCard}
        title={tx('Drivers & Operators')}
        description={tx('Licences, medical and permit expiries, qualified vehicle classes, assignments and check-outs.')}
        actions={canCreate && (
          <Button onClick={() => navigate('/fleet/drivers/new')}>
            <Plus className="mr-1.5 h-4 w-4" /> {tx('Add driver')}
          </Button>
        )}
      />

      <ListToolbar chips={<FilterChips chips={chips} onClearAll={list.clearFilters} />}>
        <SearchInput value={list.searchInput} onChange={list.setSearchInput} placeholder={tx('Search code, name, licence…')} />
        <FilterSelect value={status} onChange={(v) => list.setFilter('status', v)} options={toOptions(DRIVER_STATUS_LABEL)} allLabel={tx('All statuses')} />
        <FilterSelect value={driverType} onChange={(v) => list.setFilter('driverType', v)} options={toOptions(DRIVER_TYPE_LABEL)} allLabel={tx('All types')} />
        <FilterSelect value={categoryId} onChange={(v) => list.setFilter('categoryId', v)} options={categories.map((c) => ({ value: c.id, label: c.name }))} allLabel={tx('All classes')} />
        <FilterSelect value={documents} onChange={(v) => list.setFilter('documents', v)} options={toOptions(DOCUMENT_FILTER)} allLabel={tx('Any documents')} />
      </ListToolbar>

      <ListCard>
        <div className={isFetching && !isLoading ? 'opacity-60 transition-opacity' : 'transition-opacity'}>
          <DataTable
            columns={columns}
            data={rows}
            loading={isLoading}
            loadingRows={8}
            getRowId={(d) => d.id}
            onRowClick={(d) => navigate(`/fleet/drivers/${d.id}`)}
            sort={list.sort}
            onSortChange={list.setSort}
            cellClassName="py-2.5 px-4"
            headerRowClassName="h-10"
            emptyMessage={isError ? tx('Could not load the drivers. Try again.') : chips.length ? tx('No drivers match these filters.') : tx('No drivers registered yet.')}
          />
        </div>
        {meta && (
          <DataTablePagination
            page={meta.page}
            pageSize={list.pageSize}
            total={meta.total}
            totalPages={meta.totalPages}
            onPageChange={list.setPage}
            onPageSizeChange={list.setPageSize}
            noun="driver"
          />
        )}
      </ListCard>
    </div>
  );
}

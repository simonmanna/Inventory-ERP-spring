import { useNavigate } from 'react-router-dom';
import { Eye, Plus, Truck } from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { Button } from '@/components/ui/button';
import { DataTable, type Column } from '@/components/data-table';
import {
  DataTablePagination, FilterChips, FilterSelect, ListCard, ListPageHeader, ListToolbar, SearchInput,
  useListState, type ActiveChip,
} from '@/components/list';
import { useAuthStore } from '@/stores/auth.store';
import { tx } from '@/lib/i18n/tx';
import {
  OWNERSHIP_LABEL, STATUS_LABEL, useFleetLookups, useVehicles, type Vehicle,
} from '@/features/fleet/api';
import { VehicleStatusPill } from './fleet-ui';
import { num, toOptions } from './fleet-format';

const FILTERS = { categoryId: '', status: '', branchId: '', ownership: '' };

/** Fleet register: vehicles and equipment with class / status / branch / ownership filters (fleet 1.1). */
export function VehiclesPage() {
  const navigate = useNavigate();
  const canCreate = useAuthStore((s) => s.hasPermission(PERMISSIONS.fleetVehicle.create));
  const list = useListState(FILTERS);
  const { categoryId, status, branchId, ownership } = list.filters;
  const lookups = useFleetLookups();
  const { data, isLoading, isFetching, isError } = useVehicles({
    page: list.page,
    pageSize: list.pageSize,
    search: list.search || undefined,
    categoryId: categoryId || undefined,
    status: status || undefined,
    branchId: branchId || undefined,
    ownership: ownership || undefined,
    sortBy: list.sort?.by,
    sortOrder: list.sort?.order,
  });
  const rows = data?.data ?? [];
  const meta = data?.meta;
  const categories = lookups.data?.categories ?? [];
  const branches = lookups.data?.branches ?? [];

  const chips: ActiveChip[] = [
    ...(list.search ? [{ key: 'q', label: `“${list.search}”`, onRemove: () => list.setSearchInput('') }] : []),
    ...(categoryId ? [{ key: 'cat', label: categories.find((c) => c.id === categoryId)?.name ?? categoryId, onRemove: () => list.setFilter('categoryId', '') }] : []),
    ...(status ? [{ key: 'status', label: STATUS_LABEL[status as keyof typeof STATUS_LABEL] ?? status, onRemove: () => list.setFilter('status', '') }] : []),
    ...(branchId ? [{ key: 'branch', label: branches.find((b) => b.id === branchId)?.name ?? branchId, onRemove: () => list.setFilter('branchId', '') }] : []),
    ...(ownership ? [{ key: 'own', label: OWNERSHIP_LABEL[ownership as keyof typeof OWNERSHIP_LABEL] ?? ownership, onRemove: () => list.setFilter('ownership', '') }] : []),
  ];

  const meter = (v: Vehicle) => {
    if (v.meters.odometer) return `${num(v.meters.odometer.value, 0)} km`;
    if (v.meters.hours) return `${num(v.meters.hours.value)} h`;
    return '—';
  };

  const columns: Column<Vehicle>[] = [
    { key: 'code', header: tx('Code'), sortKey: 'code', render: (v) => <span className="font-medium text-primary">{v.code}</span> },
    { key: 'registrationPlate', header: tx('Plate'), sortKey: 'registrationPlate', render: (v) => v.registrationPlate ?? <span className="text-muted-foreground">—</span> },
    {
      key: 'name', header: tx('Vehicle / equipment'), sortKey: 'make',
      render: (v) => (
        <div className="min-w-[10rem]">
          <div>{v.name ?? ([v.make, v.model].filter(Boolean).join(' ') || '—')}</div>
          {v.name && (v.make || v.model) && <div className="text-xs text-muted-foreground">{[v.make, v.model, v.year].filter(Boolean).join(' · ')}</div>}
        </div>
      ),
    },
    { key: 'category', header: tx('Class'), render: (v) => v.category?.name ?? '—' },
    { key: 'payload', header: tx('Payload (t)'), className: 'text-right', render: (v) => <span className="tabular-nums">{num(v.payloadCapacityT, 3)}</span> },
    { key: 'meter', header: tx('Meter'), className: 'text-right', render: (v) => <span className="tabular-nums">{meter(v)}</span> },
    { key: 'branch', header: tx('Branch'), render: (v) => v.branch?.name ?? '—' },
    { key: 'ownership', header: tx('Ownership'), render: (v) => OWNERSHIP_LABEL[v.ownership] },
    { key: 'status', header: tx('Status'), sortKey: 'status', render: (v) => <VehicleStatusPill status={v.status} /> },
    {
      key: 'actions', header: '', className: 'w-12 text-right',
      render: (v) => (
        <Button variant="ghost" size="icon" className="h-8 w-8" aria-label={tx('View vehicle')} onClick={() => navigate(`/fleet/vehicles/${v.id}`)}>
          <Eye className="h-4 w-4" />
        </Button>
      ),
    },
  ];

  return (
    <div className="space-y-4">
      <ListPageHeader
        icon={Truck}
        title={tx('Vehicles & Equipment')}
        description={tx('Trucks, trailers, plant and light vehicles: identity, capacity, meters, compliance and status.')}
        actions={canCreate && (
          <Button onClick={() => navigate('/fleet/vehicles/new')}>
            <Plus className="mr-1.5 h-4 w-4" /> {tx('Register vehicle')}
          </Button>
        )}
      />

      <ListToolbar chips={<FilterChips chips={chips} onClearAll={list.clearFilters} />}>
        <SearchInput value={list.searchInput} onChange={list.setSearchInput} placeholder={tx('Search code, plate, VIN, make…')} />
        <FilterSelect value={categoryId} onChange={(v) => list.setFilter('categoryId', v)} options={categories.map((c) => ({ value: c.id, label: c.name }))} allLabel={tx('All classes')} />
        <FilterSelect value={status} onChange={(v) => list.setFilter('status', v)} options={toOptions(STATUS_LABEL)} allLabel={tx('All statuses')} />
        {branches.length > 0 && (
          <FilterSelect value={branchId} onChange={(v) => list.setFilter('branchId', v)} options={branches.map((b) => ({ value: b.id, label: b.name }))} allLabel={tx('All branches')} />
        )}
        <FilterSelect value={ownership} onChange={(v) => list.setFilter('ownership', v)} options={toOptions(OWNERSHIP_LABEL)} allLabel={tx('Any ownership')} />
      </ListToolbar>

      <ListCard>
        <div className={isFetching && !isLoading ? 'opacity-60 transition-opacity' : 'transition-opacity'}>
          <DataTable
            columns={columns}
            data={rows}
            loading={isLoading}
            loadingRows={8}
            getRowId={(v) => v.id}
            onRowClick={(v) => navigate(`/fleet/vehicles/${v.id}`)}
            sort={list.sort}
            onSortChange={list.setSort}
            cellClassName="py-2.5 px-4"
            headerRowClassName="h-10"
            emptyMessage={isError ? tx('Could not load the vehicles. Try again.') : chips.length ? tx('No vehicles match these filters.') : tx('No vehicles registered yet.')}
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
            noun="vehicle"
          />
        )}
      </ListCard>
    </div>
  );
}

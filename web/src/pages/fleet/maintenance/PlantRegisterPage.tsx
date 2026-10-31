import { useNavigate } from 'react-router-dom';
import { Coins, Eye, Factory, Plus } from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { Button } from '@/components/ui/button';
import { DataTable, type Column } from '@/components/data-table';
import {
  DataTablePagination, FilterChips, FilterSelect, ListCard, ListPageHeader, ListToolbar, SearchInput,
  useListState, type ActiveChip,
} from '@/components/list';
import { useAuthStore } from '@/stores/auth.store';
import { tx } from '@/lib/i18n/tx';
import { STATUS_LABEL, useFleetLookups } from '@/features/fleet/api';
import { usePlantClasses, usePlantUnits, type PlantUnit } from '@/features/fleet/plant-api';
import { VehicleStatusPill } from '../fleet-ui';
import { num, toOptions } from '../fleet-format';

const FILTERS = { categoryId: '', status: '', locationId: '' };

/** Fixed-plant register by location (CMMS-1). Detail, schedules and work orders are the shared fleet screens. */
export function PlantRegisterPage() {
  const navigate = useNavigate();
  const has = useAuthStore((s) => s.hasPermission);
  const list = useListState(FILTERS);
  const { categoryId, status, locationId } = list.filters;
  const lookups = useFleetLookups();
  const classes = usePlantClasses();
  const { data, isLoading, isFetching, isError } = usePlantUnits({
    page: list.page,
    pageSize: list.pageSize,
    search: list.search || undefined,
    categoryId: categoryId || undefined,
    status: status || undefined,
    locationId: locationId || undefined,
  });
  const locations = lookups.data?.locations ?? [];
  const classOptions = classes.data ?? [];
  const rows = data?.data ?? [];
  const meta = data?.meta;

  const chips: ActiveChip[] = [
    ...(list.search ? [{ key: 'q', label: `“${list.search}”`, onRemove: () => list.setSearchInput('') }] : []),
    ...(categoryId ? [{ key: 'cat', label: classOptions.find((c) => c.id === categoryId)?.name ?? categoryId, onRemove: () => list.setFilter('categoryId', '') }] : []),
    ...(status ? [{ key: 'status', label: STATUS_LABEL[status as keyof typeof STATUS_LABEL] ?? status, onRemove: () => list.setFilter('status', '') }] : []),
    ...(locationId ? [{ key: 'loc', label: locations.find((l) => l.id === locationId)?.name ?? locationId, onRemove: () => list.setFilter('locationId', '') }] : []),
  ];

  const columns: Column<PlantUnit>[] = [
    { key: 'code', header: tx('Code'), render: (u) => <span className="font-medium text-primary">{u.code}</span> },
    { key: 'name', header: tx('Equipment'), render: (u) => u.name ?? ([u.make, u.model].filter(Boolean).join(' ') || '—') },
    { key: 'class', header: tx('Class'), render: (u) => u.categoryName },
    { key: 'location', header: tx('Location'), render: (u) => u.locationName ?? '—' },
    { key: 'hours', header: tx('Hours'), className: 'text-right', render: (u) => <span className="tabular-nums">{num(u.lifetimeHours)}</span> },
    { key: 'asset', header: tx('Fixed asset'), render: (u) => u.assetCode ?? '—' },
    { key: 'open', header: tx('Open work orders'), className: 'text-right', render: (u) => u.openWorkOrders },
    { key: 'status', header: tx('Status'), render: (u) => <VehicleStatusPill status={u.status} /> },
    {
      key: 'actions', header: '', className: 'w-12 text-right',
      render: (u) => (
        <Button variant="ghost" size="icon" className="h-8 w-8" aria-label={tx('View equipment')} onClick={() => navigate(`/fleet/vehicles/${u.id}`)}>
          <Eye className="h-4 w-4" />
        </Button>
      ),
    },
  ];

  return (
    <div className="space-y-4">
      <ListPageHeader
        icon={Factory}
        title={tx('Fixed Plant')}
        description={tx('Crushers, conveyors, screens, block machines and generators: one maintenance system with the trucks.')}
        actions={(
          <div className="flex flex-wrap gap-2">
            {has(PERMISSIONS.fleet.report) && (
              <Button variant="outline" onClick={() => navigate('/fleet/plant/costs')}>
                <Coins className="mr-1.5 h-4 w-4" /> {tx('Cost per equipment')}
              </Button>
            )}
            {has(PERMISSIONS.fleetVehicle.create) && (
              <Button onClick={() => navigate('/fleet/vehicles/new')}>
                <Plus className="mr-1.5 h-4 w-4" /> {tx('Register plant')}
              </Button>
            )}
          </div>
        )}
      />

      <ListToolbar chips={<FilterChips chips={chips} onClearAll={list.clearFilters} />}>
        <SearchInput value={list.searchInput} onChange={list.setSearchInput} placeholder={tx('Search code, name, make…')} />
        <FilterSelect value={categoryId} onChange={(v) => list.setFilter('categoryId', v)} options={classOptions.map((c) => ({ value: c.id, label: c.name }))} allLabel={tx('All classes')} />
        <FilterSelect value={locationId} onChange={(v) => list.setFilter('locationId', v)} options={locations.map((l) => ({ value: l.id, label: l.name }))} allLabel={tx('All locations')} />
        <FilterSelect value={status} onChange={(v) => list.setFilter('status', v)} options={toOptions(STATUS_LABEL)} allLabel={tx('All statuses')} />
      </ListToolbar>

      <ListCard>
        <div className={isFetching && !isLoading ? 'opacity-60 transition-opacity' : 'transition-opacity'}>
          <DataTable
            columns={columns}
            data={rows}
            loading={isLoading}
            loadingRows={8}
            getRowId={(u) => u.id}
            onRowClick={(u) => navigate(`/fleet/vehicles/${u.id}`)}
            cellClassName="py-2.5 px-4"
            headerRowClassName="h-10"
            emptyMessage={isError ? tx('Could not load the plant register. Try again.') : chips.length ? tx('No equipment matches these filters.') : tx('No fixed plant registered yet.')}
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
            noun="unit"
          />
        )}
      </ListCard>
    </div>
  );
}

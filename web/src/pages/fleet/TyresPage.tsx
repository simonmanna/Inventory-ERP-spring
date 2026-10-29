import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { CircleDot, Eye, Plus } from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { Button } from '@/components/ui/button';
import { DataTable, type Column } from '@/components/data-table';
import {
  DataTablePagination, FilterChips, FilterSelect, ListCard, ListPageHeader, ListToolbar, SearchInput, StatusPill,
  useListState, type ActiveChip, type Tone,
} from '@/components/list';
import { useAuthStore } from '@/stores/auth.store';
import { money } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';
import {
  TYRE_STATUS_LABEL, TYRE_TYPE_LABEL, positionLabel, useTyres, type Tyre, type TyreStatus,
} from '@/features/fleet/tyres-api';
import { num, toOptions } from './fleet-format';
import { TyreFormDialog } from './tyre-dialogs';

const FILTERS = { status: '', tyreType: '' };

export const TYRE_TONE: Record<TyreStatus, Tone> = {
  in_stock: 'info', fitted: 'success', removed: 'warning', retreaded: 'accent', scrapped: 'neutral',
};

export function TyreStatusPill({ status }: { status: TyreStatus }) {
  return <StatusPill tone={TYRE_TONE[status] ?? 'neutral'}>{TYRE_STATUS_LABEL[status] ?? status}</StatusPill>;
}

/** Tyre register (fleet 1.7): serial, size, where it is fitted, km run, tread and cost per km. */
export function TyresPage() {
  const navigate = useNavigate();
  const canEdit = useAuthStore((s) => s.hasPermission(PERMISSIONS.fleetMaintenance.tyre));
  const list = useListState(FILTERS);
  const { status, tyreType } = list.filters;
  const [creating, setCreating] = useState(false);
  const { data, isLoading, isFetching, isError } = useTyres({
    page: list.page, pageSize: list.pageSize, search: list.search || undefined, status: status || undefined,
    tyreType: tyreType || undefined, sortBy: list.sort?.by, sortOrder: list.sort?.order,
  });
  const chips: ActiveChip[] = [
    ...(list.search ? [{ key: 'q', label: `“${list.search}”`, onRemove: () => list.setSearchInput('') }] : []),
    ...(status ? [{ key: 'status', label: TYRE_STATUS_LABEL[status as TyreStatus] ?? status, onRemove: () => list.setFilter('status', '') }] : []),
    ...(tyreType ? [{ key: 'type', label: TYRE_TYPE_LABEL[tyreType as keyof typeof TYRE_TYPE_LABEL] ?? tyreType, onRemove: () => list.setFilter('tyreType', '') }] : []),
  ];

  const columns: Column<Tyre>[] = [
    { key: 'serialNumber', header: tx('Serial number'), sortKey: 'serialNumber', render: (t) => <span className="font-medium text-primary">{t.serialNumber}</span> },
    {
      key: 'brand', header: tx('Tyre'), sortKey: 'brand',
      render: (t) => (
        <div className="min-w-[9rem]">
          <div>{[t.brand, t.model].filter(Boolean).join(' ') || '—'}</div>
          <div className="text-xs text-muted-foreground">{[t.size, TYRE_TYPE_LABEL[t.tyreType]].filter(Boolean).join(' · ')}</div>
        </div>
      ),
    },
    {
      key: 'vehicle', header: tx('Fitted to'),
      render: (t) => (t.vehicle ? (
        <div><div>{t.vehicle.code}</div><div className="text-xs text-muted-foreground">{positionLabel(t.position)}</div></div>
      ) : '—'),
    },
    { key: 'km', header: tx('Km run'), className: 'text-right', render: (t) => <span className="tabular-nums">{num(t.stats.km, 0)}</span> },
    {
      key: 'tread', header: tx('Tread (mm)'), sortKey: 'treadDepthMm', className: 'text-right',
      render: (t) => <span className={t.stats.belowMinimum ? 'font-medium tabular-nums text-rose-600' : 'tabular-nums'}>{num(t.treadDepthMm)}</span>,
    },
    { key: 'cpk', header: tx('Cost / km'), className: 'text-right', render: (t) => <span className="tabular-nums">{t.stats.costPerKm ? money(t.stats.costPerKm) : '—'}</span> },
    { key: 'status', header: tx('Status'), sortKey: 'status', render: (t) => <TyreStatusPill status={t.status} /> },
    {
      key: 'actions', header: '', className: 'w-12 text-right',
      render: (t) => (
        <Button variant="ghost" size="icon" className="h-8 w-8" aria-label={tx('View tyre')} onClick={() => navigate(`/fleet/tyres/${t.id}`)}>
          <Eye className="h-4 w-4" />
        </Button>
      ),
    },
  ];

  return (
    <div className="space-y-4">
      <ListPageHeader icon={CircleDot} title={tx('Tyres')}
        description={tx('Tyres by serial number: fitment, rotation, removal, tread depth and cost per km.')}
        actions={canEdit && <Button onClick={() => setCreating(true)}><Plus className="mr-1.5 h-4 w-4" /> {tx('Register tyre')}</Button>} />
      <ListToolbar chips={<FilterChips chips={chips} onClearAll={list.clearFilters} />}>
        <SearchInput value={list.searchInput} onChange={list.setSearchInput} placeholder={tx('Search serial, brand, size…')} />
        <FilterSelect value={status} onChange={(v) => list.setFilter('status', v)} options={toOptions(TYRE_STATUS_LABEL)} allLabel={tx('All statuses')} />
        <FilterSelect value={tyreType} onChange={(v) => list.setFilter('tyreType', v)} options={toOptions(TYRE_TYPE_LABEL)} allLabel={tx('All types')} />
      </ListToolbar>
      <ListCard>
        <div className={isFetching && !isLoading ? 'opacity-60 transition-opacity' : 'transition-opacity'}>
          <DataTable columns={columns} data={data?.data ?? []} loading={isLoading} loadingRows={8} getRowId={(t) => t.id}
            onRowClick={(t) => navigate(`/fleet/tyres/${t.id}`)} sort={list.sort} onSortChange={list.setSort}
            cellClassName="py-2.5 px-4" headerRowClassName="h-10"
            emptyMessage={isError ? tx('Could not load the tyres. Try again.') : chips.length ? tx('No tyres match these filters.') : tx('No tyres registered yet.')} />
        </div>
        {data?.meta && (
          <DataTablePagination page={data.meta.page} pageSize={list.pageSize} total={data.meta.total} totalPages={data.meta.totalPages}
            onPageChange={list.setPage} onPageSizeChange={list.setPageSize} noun="tyre" />
        )}
      </ListCard>
      {creating && <TyreFormDialog open onOpenChange={setCreating} onSaved={(t) => navigate(`/fleet/tyres/${t.id}`)} />}
    </div>
  );
}

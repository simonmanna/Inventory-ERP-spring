import { useNavigate } from 'react-router-dom';
import { CalendarRange, Truck } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { DataTable, type Column } from '@/components/data-table';
import {
  DataTablePagination, DateRangeFilter, FilterChips, FilterSelect, ListCard, ListPageHeader, ListToolbar, SearchInput,
  dateRangeLabel, useListState, type ActiveChip,
} from '@/components/list';
import { date } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';
import { TRIP_STATUS_LABEL, useDispatchLookups, useTrips, type TripSummary } from '@/features/fleet/dispatch-api';
import { num, toOptions } from '../fleet-format';
import { TripStatusPill } from './dispatch-ui';
import { carrierText } from './dispatch-format';

const FILTERS = { status: '', vehicleId: '', from: '', to: '' };
const STATUS_FILTER: Record<string, string> = {
  active: tx('Active'), open: tx('Not yet dispatched'), on_road: tx('On the road'), ...TRIP_STATUS_LABEL,
};

/** Trips (fleet 1.3): every load with its truck / transporter, tonnage and state. */
export function TripsPage() {
  const navigate = useNavigate();
  const list = useListState(FILTERS);
  const { status, vehicleId, from, to } = list.filters;
  const lookups = useDispatchLookups();
  const { data, isLoading, isFetching, isError } = useTrips({
    page: list.page, pageSize: list.pageSize, search: list.search || undefined, status: status || undefined,
    vehicleId: vehicleId || undefined, dateFrom: from || undefined, dateTo: to || undefined,
  });
  const rows = data?.data ?? [];
  const meta = data?.meta;
  const vehicles = lookups.data?.vehicles ?? [];

  const chips: ActiveChip[] = [
    ...(list.search ? [{ key: 'q', label: `“${list.search}”`, onRemove: () => list.setSearchInput('') }] : []),
    ...(vehicleId ? [{ key: 'v', label: vehicles.find((v) => v.id === vehicleId)?.code ?? vehicleId, onRemove: () => list.setFilter('vehicleId', '') }] : []),
    ...(from || to ? [{ key: 'd', label: dateRangeLabel(from, to), onRemove: () => list.setFilters({ from: '', to: '' }) }] : []),
  ];

  const columns: Column<TripSummary>[] = [
    { key: 'code', header: tx('Trip'), render: (t) => <span className="font-medium text-primary">{t.code}</span> },
    { key: 'date', header: tx('Planned'), render: (t) => <span className="whitespace-nowrap">{date(t.plannedDate)}</span> },
    { key: 'order', header: tx('Delivery order'), render: (t) => t.deliveryOrderCode },
    { key: 'customer', header: tx('Customer / site'), render: (t) => [t.partnerName, t.siteName].filter(Boolean).join(' · ') },
    { key: 'carrier', header: tx('Truck / transporter'), render: (t) => carrierText(t) },
    { key: 'tonnes', header: tx('Tonnes'), className: 'text-right', render: (t) => <span className="tabular-nums">{num(t.deliveredTonnes ?? t.loadedTonnes ?? t.plannedTonnes, 2)}</span> },
    { key: 'note', header: tx('Delivery note'), render: (t) => t.deliveryNoteNumber ?? '—' },
    { key: 'status', header: tx('Status'), render: (t) => <TripStatusPill status={t.status} /> },
  ];
  const empty = isError ? tx('Could not load the trips. Try again.') : tx('No trips match these filters.');

  return (
    <div className="space-y-4">
      <ListPageHeader icon={Truck} title={tx('Trips')} description={tx('Loads of the delivery orders, from planning to proof of delivery.')}
        actions={<Button variant="outline" onClick={() => navigate('/fleet/dispatch')}><CalendarRange className="mr-1.5 h-4 w-4" /> {tx('Dispatch Board')}</Button>} />
      <ListToolbar chips={<FilterChips chips={chips} onClearAll={list.clearFilters} />}>
        <SearchInput value={list.searchInput} onChange={list.setSearchInput} placeholder={tx('Search trip, order, customer, plate…')} />
        <FilterSelect value={status} onChange={(v) => list.setFilter('status', v)} options={toOptions(STATUS_FILTER)} allLabel={tx('All statuses')} />
        <FilterSelect value={vehicleId} onChange={(v) => list.setFilter('vehicleId', v)} options={vehicles.map((v) => ({ value: v.id, label: [v.code, v.registrationPlate].filter(Boolean).join(' · ') }))} allLabel={tx('All vehicles')} />
        <DateRangeFilter from={from} to={to} onChange={(f, t) => list.setFilters({ from: f, to: t })} />
      </ListToolbar>
      <div className="space-y-2 sm:hidden">
        {isLoading && <div className="rounded-xl border bg-card p-4 text-sm text-muted-foreground">…</div>}
        {!isLoading && rows.length === 0 && <div className="rounded-xl border bg-card p-4 text-center text-sm text-muted-foreground">{empty}</div>}
        {rows.map((t) => (
          <button key={t.id} type="button" onClick={() => navigate(`/fleet/trips/${t.id}`)} className="w-full rounded-xl border bg-card p-3 text-left shadow-sm active:bg-accent">
            <div className="flex items-center justify-between gap-2"><span className="font-medium">{t.code}</span><TripStatusPill status={t.status} /></div>
            <div className="mt-1 text-xs text-muted-foreground">{date(t.plannedDate)} · {t.partnerName} · {carrierText(t)}</div>
          </button>
        ))}
      </div>
      <div className="hidden sm:block">
        <ListCard>
          <div className={isFetching && !isLoading ? 'opacity-60 transition-opacity' : 'transition-opacity'}>
            <DataTable columns={columns} data={rows} loading={isLoading} loadingRows={8} getRowId={(t) => t.id}
              onRowClick={(t) => navigate(`/fleet/trips/${t.id}`)} cellClassName="py-2.5 px-4" headerRowClassName="h-10" emptyMessage={empty} />
          </div>
        </ListCard>
      </div>
      {meta && (
        <DataTablePagination page={meta.page} pageSize={list.pageSize} total={meta.total} totalPages={meta.totalPages}
          onPageChange={list.setPage} onPageSizeChange={list.setPageSize} noun="trip" />
      )}
    </div>
  );
}

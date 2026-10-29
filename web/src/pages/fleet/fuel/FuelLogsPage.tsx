import { Link, useNavigate } from 'react-router-dom';
import { AlertTriangle, CreditCard, Cylinder, Eye, Fuel, Plus } from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { Button } from '@/components/ui/button';
import { DataTable, type Column } from '@/components/data-table';
import {
  DataTablePagination, DateRangeFilter, FilterChips, FilterSelect, ListCard, ListPageHeader, ListToolbar, SearchInput,
  dateRangeLabel, useListState, type ActiveChip,
} from '@/components/list';
import { useAuthStore } from '@/stores/auth.store';
import { dateTime, money } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';
import {
  ANOMALY_LABEL, LOG_STATUS_LABEL, SOURCE_LABEL, useFuelLogs, useFuelLookups, useFuelOverview, type AnomalyType,
  type FuelLog,
} from '@/features/fleet/fuel-api';
import { num, toOptions } from '../fleet-format';
import { AnomalyChips, FuelLogStatusPill, StatTile } from './fuel-ui';
import { efficiencyText, meterText } from './fuel-format';

const FILTERS = { vehicleId: '', source: '', status: '', anomaly: '', from: '', to: '' };

const ANOMALY_FILTER: Record<string, string> = {
  any: tx('Any anomaly'),
  open: tx('Open anomalies'),
  none: tx('No anomaly'),
  ...ANOMALY_LABEL,
};

/** Fuel logs (fleet 1.5): filters by vehicle, date, source, status and anomaly; phone-friendly cards on small screens. */
export function FuelLogsPage() {
  const navigate = useNavigate();
  const has = useAuthStore((s) => s.hasPermission);
  const list = useListState(FILTERS);
  const { vehicleId, source, status, anomaly, from, to } = list.filters;
  const lookups = useFuelLookups();
  const overview = useFuelOverview();
  const { data, isLoading, isFetching, isError } = useFuelLogs({
    page: list.page,
    pageSize: list.pageSize,
    search: list.search || undefined,
    vehicleId: vehicleId || undefined,
    source: source || undefined,
    status: status || undefined,
    anomaly: anomaly || undefined,
    dateFrom: from || undefined,
    dateTo: to || undefined,
    sortBy: list.sort?.by,
    sortOrder: list.sort?.order,
  });
  const rows = data?.data ?? [];
  const meta = data?.meta;
  const vehicles = lookups.data?.vehicles ?? [];
  const vehicleLabel = (id: string) => {
    const v = vehicles.find((x) => x.id === id);
    return v ? [v.code, v.registrationPlate].filter(Boolean).join(' · ') : id;
  };

  const chips: ActiveChip[] = [
    ...(list.search ? [{ key: 'q', label: `“${list.search}”`, onRemove: () => list.setSearchInput('') }] : []),
    ...(vehicleId ? [{ key: 'v', label: vehicleLabel(vehicleId), onRemove: () => list.setFilter('vehicleId', '') }] : []),
    ...(source ? [{ key: 's', label: SOURCE_LABEL[source as keyof typeof SOURCE_LABEL] ?? source, onRemove: () => list.setFilter('source', '') }] : []),
    ...(status ? [{ key: 'st', label: LOG_STATUS_LABEL[status as keyof typeof LOG_STATUS_LABEL] ?? status, onRemove: () => list.setFilter('status', '') }] : []),
    ...(anomaly ? [{ key: 'a', label: ANOMALY_FILTER[anomaly] ?? anomaly, onRemove: () => list.setFilter('anomaly', '') }] : []),
    ...(from || to ? [{ key: 'd', label: dateRangeLabel(from, to), onRemove: () => list.setFilters({ from: '', to: '' }) }] : []),
  ];

  const cost = (l: FuelLog) => (l.amount ? money(l.amount) : '—');

  const columns: Column<FuelLog>[] = [
    { key: 'fuelledAt', header: tx('When'), sortKey: 'fuelledAt', render: (l) => <span className="whitespace-nowrap">{dateTime(l.fuelledAt)}</span> },
    { key: 'code', header: tx('Log'), sortKey: 'code', render: (l) => <span className="font-medium text-primary">{l.code}</span> },
    { key: 'vehicle', header: tx('Vehicle'), sortKey: 'vehicle', render: (l) => <span className="whitespace-nowrap">{[l.vehicle?.code, l.vehicle?.registrationPlate].filter(Boolean).join(' · ')}</span> },
    { key: 'source', header: tx('Source'), render: (l) => (l.source === 'tank' ? `${SOURCE_LABEL.tank}: ${l.tank?.code ?? ''}` : l.stationName ?? SOURCE_LABEL.station) },
    { key: 'litres', header: tx('Litres'), sortKey: 'litres', className: 'text-right', render: (l) => <span className="tabular-nums">{num(l.litres, 2)}</span> },
    { key: 'amount', header: tx('Cost'), sortKey: 'amount', className: 'text-right', render: (l) => <span className="tabular-nums">{cost(l)}</span> },
    { key: 'meter', header: tx('Meter'), className: 'text-right', render: (l) => <span className="tabular-nums">{meterText(l.odometer, l.hours)}</span> },
    { key: 'eff', header: tx('Efficiency'), render: (l) => <span className="whitespace-nowrap tabular-nums">{efficiencyText(l.lPer100Km, l.lPerHour)}</span> },
    { key: 'anomalies', header: tx('Anomalies'), render: (l) => <AnomalyChips types={l.anomalies as AnomalyType[]} /> },
    { key: 'status', header: tx('Status'), render: (l) => <FuelLogStatusPill status={l.status} /> },
    {
      key: 'actions', header: '', className: 'w-12 text-right',
      render: (l) => (
        <Button variant="ghost" size="icon" className="h-8 w-8" aria-label={tx('View fuel log')} onClick={() => navigate(`/fleet/fuel/logs/${l.id}`)}>
          <Eye className="h-4 w-4" />
        </Button>
      ),
    },
  ];

  const o = overview.data;
  const empty = isError ? tx('Could not load the fuel logs. Try again.') : chips.length ? tx('No fuel logs match these filters.') : tx('No fuel logged yet.');

  return (
    <div className="space-y-4">
      <ListPageHeader
        icon={Fuel}
        title={tx('Fuel Logs')}
        description={tx('Fuel bought at stations or issued from site tanks, with efficiency and anomaly checks.')}
        actions={
          <>
            <Button variant="outline" asChild><Link to="/fleet/fuel/anomalies"><AlertTriangle className="mr-1.5 h-4 w-4" /> {tx('Anomalies')}</Link></Button>
            <Button variant="outline" asChild><Link to="/fleet/fuel/tanks"><Cylinder className="mr-1.5 h-4 w-4" /> {tx('Tanks')}</Link></Button>
            <Button variant="outline" asChild><Link to="/fleet/fuel/cards"><CreditCard className="mr-1.5 h-4 w-4" /> {tx('Cards')}</Link></Button>
            {has(PERMISSIONS.fleetFuel.create) && (
              <Button onClick={() => navigate('/fleet/fuel/new')}><Plus className="mr-1.5 h-4 w-4" /> {tx('Record fuel')}</Button>
            )}
          </>
        }
      />

      {o && (
        <div className="grid grid-cols-2 gap-3 sm:grid-cols-4">
          <StatTile label={tx('Litres this month')} value={num(o.month.litres, 0)} hint={`${tx('Tank')} ${num(o.month.tankLitres, 0)} · ${tx('Station')} ${num(o.month.stationLitres, 0)}`} />
          <StatTile label={tx('Fuel cost this month')} value={money(o.month.amount)} hint={tx('{{count}} posted logs', { count: o.month.logs })} />
          <StatTile label={tx('Open anomalies')} value={o.openAnomalies} tone={o.openAnomalies > 0 ? 'danger' : undefined} />
          <StatTile label={tx('Drafts to post')} value={o.drafts} tone={o.drafts > 0 ? 'warning' : undefined} />
        </div>
      )}

      <ListToolbar chips={<FilterChips chips={chips} onClearAll={list.clearFilters} />}>
        <SearchInput value={list.searchInput} onChange={list.setSearchInput} placeholder={tx('Search log, receipt, station, vehicle…')} />
        <FilterSelect value={vehicleId} onChange={(v) => list.setFilter('vehicleId', v)} options={vehicles.map((v) => ({ value: v.id, label: [v.code, v.registrationPlate].filter(Boolean).join(' · ') }))} allLabel={tx('All vehicles')} />
        <FilterSelect value={source} onChange={(v) => list.setFilter('source', v)} options={toOptions(SOURCE_LABEL)} allLabel={tx('All sources')} />
        <FilterSelect value={status} onChange={(v) => list.setFilter('status', v)} options={toOptions(LOG_STATUS_LABEL)} allLabel={tx('All statuses')} />
        <FilterSelect value={anomaly} onChange={(v) => list.setFilter('anomaly', v)} options={toOptions(ANOMALY_FILTER)} allLabel={tx('Anomalies: all')} />
        <DateRangeFilter from={from} to={to} onChange={(f, t) => list.setFilters({ from: f, to: t })} />
      </ListToolbar>

      {data?.summary && (
        <p className="text-sm text-muted-foreground">
          {tx('Posted in this view: {{litres}} L · {{amount}}', { litres: num(data.summary.litres, 0), amount: money(data.summary.amount) })}
        </p>
      )}

      {/* Phones: one card per log. */}
      <div className="space-y-2 sm:hidden">
        {isLoading && <div className="rounded-xl border bg-card p-4 text-sm text-muted-foreground">…</div>}
        {!isLoading && rows.length === 0 && <div className="rounded-xl border bg-card p-4 text-center text-sm text-muted-foreground">{empty}</div>}
        {rows.map((l) => (
          <button key={l.id} type="button" onClick={() => navigate(`/fleet/fuel/logs/${l.id}`)}
            className="w-full rounded-xl border bg-card p-3 text-left shadow-sm active:bg-accent">
            <div className="flex items-center justify-between gap-2">
              <span className="font-medium">{l.vehicle?.code} · {num(l.litres, 1)} L</span>
              <FuelLogStatusPill status={l.status} />
            </div>
            <div className="mt-1 text-xs text-muted-foreground">{dateTime(l.fuelledAt)} · {l.code} · {cost(l)}</div>
            <div className="mt-1 text-xs">{efficiencyText(l.lPer100Km, l.lPerHour)}</div>
            {(l.anomalies as AnomalyType[]).length > 0 && <div className="mt-2"><AnomalyChips types={l.anomalies as AnomalyType[]} /></div>}
          </button>
        ))}
      </div>

      <div className="hidden sm:block">
        <ListCard>
          <div className={isFetching && !isLoading ? 'opacity-60 transition-opacity' : 'transition-opacity'}>
            <DataTable
              columns={columns}
              data={rows}
              loading={isLoading}
              loadingRows={8}
              getRowId={(l) => l.id}
              onRowClick={(l) => navigate(`/fleet/fuel/logs/${l.id}`)}
              sort={list.sort}
              onSortChange={list.setSort}
              cellClassName="py-2.5 px-4"
              headerRowClassName="h-10"
              emptyMessage={empty}
            />
          </div>
        </ListCard>
      </div>
      {meta && (
        <DataTablePagination
          page={meta.page}
          pageSize={list.pageSize}
          total={meta.total}
          totalPages={meta.totalPages}
          onPageChange={list.setPage}
          onPageSizeChange={list.setPageSize}
          noun="fuel log"
        />
      )}
    </div>
  );
}

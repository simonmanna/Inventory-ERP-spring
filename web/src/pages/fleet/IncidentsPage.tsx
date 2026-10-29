import { useNavigate } from 'react-router-dom';
import { Eye, Plus, Shield } from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { Button } from '@/components/ui/button';
import { DataTable, type Column } from '@/components/data-table';
import {
  DataTablePagination, DateRangeFilter, FilterChips, FilterSelect, ListCard, ListPageHeader, ListToolbar, SearchInput,
  StatusPill, dateRangeLabel, useListState, type ActiveChip, type Tone,
} from '@/components/list';
import { useAuthStore } from '@/stores/auth.store';
import { dateTime, money } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';
import {
  INCIDENT_STATUS_LABEL, INCIDENT_TYPE_LABEL, SEVERITY_LABEL, useIncidents, type Incident, type IncidentStatus,
} from '@/features/fleet/tyres-api';
import { toOptions } from './fleet-format';

const FILTERS = { status: '', incidentType: '', from: '', to: '' };

const STATUS_TONE: Record<IncidentStatus, Tone> = {
  reported: 'warning', investigating: 'info', repairing: 'accent', closed: 'success', cancelled: 'neutral',
};
const SEVERITY_TONE: Record<Incident['severity'], Tone> = { minor: 'neutral', major: 'warning', critical: 'danger' };

export function IncidentStatusPill({ status }: { status: IncidentStatus }) {
  return <StatusPill tone={STATUS_TONE[status] ?? 'neutral'}>{INCIDENT_STATUS_LABEL[status] ?? status}</StatusPill>;
}

export function SeverityPill({ severity }: { severity: Incident['severity'] }) {
  return <StatusPill tone={SEVERITY_TONE[severity] ?? 'neutral'} dot={false}>{SEVERITY_LABEL[severity] ?? severity}</StatusPill>;
}

/** Incidents & accidents (fleet 1.7) with status / type / period filters. */
export function IncidentsPage() {
  const navigate = useNavigate();
  const canCreate = useAuthStore((s) => s.hasPermission(PERMISSIONS.fleetIncident.create));
  const list = useListState(FILTERS);
  const { status, incidentType, from, to } = list.filters;
  const { data, isLoading, isFetching, isError } = useIncidents({
    page: list.page, pageSize: list.pageSize, search: list.search || undefined, status: status || undefined,
    incidentType: incidentType || undefined, from: from || undefined, to: to || undefined,
  });
  const chips: ActiveChip[] = [
    ...(list.search ? [{ key: 'q', label: `“${list.search}”`, onRemove: () => list.setSearchInput('') }] : []),
    ...(status ? [{ key: 'status', label: INCIDENT_STATUS_LABEL[status as IncidentStatus] ?? status, onRemove: () => list.setFilter('status', '') }] : []),
    ...(incidentType ? [{ key: 'type', label: INCIDENT_TYPE_LABEL[incidentType] ?? incidentType, onRemove: () => list.setFilter('incidentType', '') }] : []),
    ...(from || to ? [{ key: 'range', label: dateRangeLabel(from, to), onRemove: () => list.setFilters({ from: '', to: '' }) }] : []),
  ];
  const columns: Column<Incident>[] = [
    { key: 'number', header: tx('Number'), render: (i) => <span className="font-medium text-primary">{i.incidentNumber}</span> },
    { key: 'occurredAt', header: tx('When'), render: (i) => <span className="whitespace-nowrap">{dateTime(i.occurredAt)}</span> },
    { key: 'vehicle', header: tx('Vehicle'), render: (i) => <div><div>{i.vehicle.code}</div><div className="text-xs text-muted-foreground">{i.vehicle.registrationPlate ?? ''}</div></div> },
    { key: 'driver', header: tx('Driver'), render: (i) => i.driver?.name ?? '—' },
    { key: 'type', header: tx('Type'), render: (i) => INCIDENT_TYPE_LABEL[i.incidentType] ?? i.incidentType },
    { key: 'severity', header: tx('Severity'), render: (i) => <SeverityPill severity={i.severity} /> },
    { key: 'cost', header: tx('Estimated cost'), className: 'text-right', render: (i) => (i.estimatedCost ? money(i.estimatedCost) : '—') },
    { key: 'claims', header: tx('Claims'), className: 'text-right', render: (i) => i.claimCount ?? 0 },
    { key: 'status', header: tx('Status'), render: (i) => <IncidentStatusPill status={i.status} /> },
    {
      key: 'actions', header: '', className: 'w-12 text-right',
      render: (i) => (
        <Button variant="ghost" size="icon" className="h-8 w-8" aria-label={tx('View incident')} onClick={() => navigate(`/fleet/incidents/${i.id}`)}>
          <Eye className="h-4 w-4" />
        </Button>
      ),
    },
  ];

  return (
    <div className="space-y-4">
      <ListPageHeader icon={Shield} title={tx('Incidents & Claims')}
        description={tx('Accidents and incidents of vehicles and plant: damage, third parties, police reports, photos and insurance claims.')}
        actions={canCreate && <Button onClick={() => navigate('/fleet/incidents/new')}><Plus className="mr-1.5 h-4 w-4" /> {tx('Report incident')}</Button>} />
      <ListToolbar chips={<FilterChips chips={chips} onClearAll={list.clearFilters} />}>
        <SearchInput value={list.searchInput} onChange={list.setSearchInput} placeholder={tx('Search number, vehicle, place…')} />
        <FilterSelect value={status} onChange={(v) => list.setFilter('status', v)} options={toOptions(INCIDENT_STATUS_LABEL)} allLabel={tx('All statuses')} />
        <FilterSelect value={incidentType} onChange={(v) => list.setFilter('incidentType', v)} options={toOptions(INCIDENT_TYPE_LABEL)} allLabel={tx('All types')} />
        <DateRangeFilter from={from} to={to} onChange={(f, t) => list.setFilters({ from: f, to: t })} />
      </ListToolbar>
      <ListCard>
        <div className={isFetching && !isLoading ? 'opacity-60 transition-opacity' : 'transition-opacity'}>
          <DataTable columns={columns} data={data?.data ?? []} loading={isLoading} loadingRows={8} getRowId={(i) => i.id}
            onRowClick={(i) => navigate(`/fleet/incidents/${i.id}`)} cellClassName="py-2.5 px-4" headerRowClassName="h-10"
            emptyMessage={isError ? tx('Could not load the incidents. Try again.') : chips.length ? tx('No incidents match these filters.') : tx('No incidents reported.')} />
        </div>
        {data?.meta && (
          <DataTablePagination page={data.meta.page} pageSize={list.pageSize} total={data.meta.total} totalPages={data.meta.totalPages}
            onPageChange={list.setPage} onPageSizeChange={list.setPageSize} noun="incident" />
        )}
      </ListCard>
    </div>
  );
}

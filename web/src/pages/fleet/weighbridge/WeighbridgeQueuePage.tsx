import { Link, useNavigate } from 'react-router-dom';
import { AlertTriangle, BarChart3, Eye, Plus, Scale, Settings2, Truck } from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { Button } from '@/components/ui/button';
import { DataTable, type Column } from '@/components/data-table';
import {
  DataTablePagination, DateRangeFilter, FilterChips, FilterSelect, ListCard, ListPageHeader, ListToolbar, SearchInput,
  dateRangeLabel, useListState, type ActiveChip,
} from '@/components/list';
import { useAuthStore } from '@/stores/auth.store';
import { dateTime } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';
import {
  QUEUE_LABEL, SUBJECT_LABEL, useQueueCounts, useTickets, useWeighbridgeLookups, type Ticket, type TicketQueue,
} from '@/features/fleet/weighbridge-api';
import { toOptions } from '../fleet-format';
import { ManualFlag, Stat, TicketStatusPill } from './weighbridge-ui';
import { kg } from './weighbridge-format';

const FILTERS = { queue: 'live', stationId: '', subjectType: '', from: '', to: '' };
const QUEUES: TicketQueue[] = ['live', 'open', 'awaiting_second', 'exceptions', 'completed', 'void', 'cancelled', 'all'];

/** Weighbridge ticket queue (fleet 1.4): open / awaiting second weighing / exceptions / completed, tablet friendly. */
export function WeighbridgeQueuePage() {
  const navigate = useNavigate();
  const has = useAuthStore((s) => s.hasPermission);
  const list = useListState(FILTERS);
  const { queue, stationId, subjectType, from, to } = list.filters;
  const lookups = useWeighbridgeLookups();
  const counts = useQueueCounts(stationId || undefined);
  const { data, isLoading, isFetching, isError } = useTickets({
    page: list.page,
    pageSize: list.pageSize,
    queue: (queue || 'live') as TicketQueue,
    stationId: stationId || undefined,
    subjectType: subjectType || undefined,
    search: list.search || undefined,
    from: from || undefined,
    to: to || undefined,
  });
  const rows = data?.data ?? [];
  const stations = lookups.data?.stations ?? [];
  const chips: ActiveChip[] = [
    ...(list.search ? [{ key: 'q', label: `“${list.search}”`, onRemove: () => list.setSearchInput('') }] : []),
    ...(stationId ? [{ key: 's', label: stations.find((s) => s.id === stationId)?.code ?? stationId, onRemove: () => list.setFilter('stationId', '') }] : []),
    ...(subjectType ? [{ key: 't', label: SUBJECT_LABEL[subjectType as keyof typeof SUBJECT_LABEL] ?? subjectType, onRemove: () => list.setFilter('subjectType', '') }] : []),
    ...(from || to ? [{ key: 'd', label: dateRangeLabel(from, to), onRemove: () => list.setFilters({ from: '', to: '' }) }] : []),
  ];
  const open = (t: Ticket) => navigate(`/fleet/weighbridge/tickets/${t.id}`);
  const columns: Column<Ticket>[] = [
    { key: 'number', header: tx('Ticket'), render: (t) => <span className="font-medium text-primary">{t.ticketNumber}</span> },
    { key: 'created', header: tx('Registered'), render: (t) => <span className="whitespace-nowrap">{dateTime(t.createdAt)}</span> },
    { key: 'truck', header: tx('Truck'), render: (t) => <span className="whitespace-nowrap font-medium">{t.registrationPlate}</span> },
    { key: 'subject', header: tx('Subject'), render: (t) => SUBJECT_LABEL[t.subjectType] },
    { key: 'partner', header: tx('Customer / supplier'), render: (t) => t.partner?.name ?? '—' },
    { key: 'product', header: tx('Product'), render: (t) => t.product?.name ?? '—' },
    { key: 'net', header: tx('Net'), className: 'text-right', render: (t) => <span className="tabular-nums">{kg(t.netKg)}</span> },
    {
      key: 'status', header: tx('Status'),
      render: (t) => <div className="flex flex-wrap gap-1"><TicketStatusPill status={t.status} />{t.isManual && <ManualFlag />}</div>,
    },
    {
      key: 'actions', header: '', className: 'w-12 text-right',
      render: (t) => (
        <Button variant="ghost" size="icon" className="h-8 w-8" aria-label={tx('Open ticket')} onClick={() => open(t)}>
          <Eye className="h-4 w-4" />
        </Button>
      ),
    },
  ];
  const c = counts.data;
  const empty = isError ? tx('Could not load the tickets. Try again.') : tx('No weighbridge tickets in this queue.');

  return (
    <div className="space-y-4">
      <ListPageHeader
        icon={Scale}
        title={tx('Weighbridge')}
        description={tx('Register trucks, capture first and second weights, print tickets and follow up exceptions.')}
        actions={
          <>
            <Button variant="outline" asChild><Link to="/fleet/weighbridge/exceptions"><AlertTriangle className="mr-1.5 h-4 w-4" /> {tx('Exceptions')}</Link></Button>
            <Button variant="outline" asChild><Link to="/fleet/weighbridge/tares"><Truck className="mr-1.5 h-4 w-4" /> {tx('Stored tares')}</Link></Button>
            <Button variant="outline" asChild><Link to="/fleet/weighbridge/reports"><BarChart3 className="mr-1.5 h-4 w-4" /> {tx('Report')}</Link></Button>
            {has(PERMISSIONS.weighbridge.configure) && (
              <Button variant="outline" asChild><Link to="/fleet/weighbridge/settings"><Settings2 className="mr-1.5 h-4 w-4" /> {tx('Settings')}</Link></Button>
            )}
            {has(PERMISSIONS.weighbridge.weigh) && (
              <Button size="lg" onClick={() => navigate('/fleet/weighbridge/tickets/new')}><Plus className="mr-1.5 h-5 w-5" /> {tx('New ticket')}</Button>
            )}
          </>
        }
      />
      {c && (
        <div className="grid grid-cols-2 gap-3 sm:grid-cols-4">
          <Stat label={tx('Open')} value={c.open} onClick={() => list.setFilter('queue', 'open')} />
          <Stat label={tx('Awaiting second weighing')} value={c.awaitingSecond} tone={c.awaitingSecond > 0 ? 'warning' : undefined} onClick={() => list.setFilter('queue', 'awaiting_second')} />
          <Stat label={tx('Exceptions')} value={c.exceptions} tone={c.exceptions > 0 ? 'danger' : undefined} onClick={() => list.setFilter('queue', 'exceptions')} />
          <Stat label={tx('Completed today')} value={c.completedToday} onClick={() => list.setFilter('queue', 'completed')} />
        </div>
      )}
      <div className="flex flex-wrap gap-2">
        {QUEUES.map((q) => (
          <Button key={q} size="sm" variant={(queue || 'live') === q ? 'default' : 'outline'} onClick={() => list.setFilter('queue', q)}>
            {QUEUE_LABEL[q]}
          </Button>
        ))}
      </div>
      <ListToolbar chips={<FilterChips chips={chips} onClearAll={() => list.setFilters({ stationId: '', subjectType: '', from: '', to: '' })} />}>
        <SearchInput value={list.searchInput} onChange={list.setSearchInput} placeholder={tx('Search ticket, plate, reference, customer…')} />
        <FilterSelect value={stationId} onChange={(v) => list.setFilter('stationId', v)} options={stations.map((s) => ({ value: s.id, label: s.code }))} allLabel={tx('All stations')} />
        <FilterSelect value={subjectType} onChange={(v) => list.setFilter('subjectType', v)} options={toOptions(SUBJECT_LABEL)} allLabel={tx('All subjects')} />
        <DateRangeFilter from={from} to={to} onChange={(f, t) => list.setFilters({ from: f, to: t })} />
      </ListToolbar>

      <div className="space-y-2 sm:hidden">
        {isLoading && <div className="rounded-xl border bg-card p-4 text-sm text-muted-foreground">…</div>}
        {!isLoading && rows.length === 0 && <div className="rounded-xl border bg-card p-4 text-center text-sm text-muted-foreground">{empty}</div>}
        {rows.map((t) => (
          <button key={t.id} type="button" onClick={() => open(t)} className="w-full rounded-xl border bg-card p-3 text-left shadow-sm active:bg-accent">
            <div className="flex items-center justify-between gap-2">
              <span className="text-lg font-semibold">{t.registrationPlate}</span>
              <TicketStatusPill status={t.status} />
            </div>
            <div className="mt-1 text-xs text-muted-foreground">{t.ticketNumber} · {dateTime(t.createdAt)}</div>
            <div className="mt-1 text-sm">{t.product?.name ?? SUBJECT_LABEL[t.subjectType]} · {kg(t.netKg)}</div>
          </button>
        ))}
      </div>
      <div className="hidden sm:block">
        <ListCard>
          <div className={isFetching && !isLoading ? 'opacity-60 transition-opacity' : 'transition-opacity'}>
            <DataTable columns={columns} data={rows} loading={isLoading} loadingRows={8} getRowId={(t) => t.id} onRowClick={open}
              cellClassName="py-2.5 px-4" headerRowClassName="h-10" emptyMessage={empty} />
          </div>
        </ListCard>
      </div>
      {data?.meta && (
        <DataTablePagination page={data.meta.page} pageSize={list.pageSize} total={data.meta.total} totalPages={data.meta.totalPages}
          onPageChange={list.setPage} onPageSizeChange={list.setPageSize} noun="ticket" />
      )}
    </div>
  );
}

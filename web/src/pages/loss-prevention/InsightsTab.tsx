import { useState } from 'react';
import { RefreshCw } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Card, CardContent } from '@/components/ui/card';
import { DataTable, type Column } from '@/components/data-table';
import { DateRangeFilter, FilterSelect, ListCard, ListToolbar } from '@/components/list';
import { dateTime } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';
import { apiMessage, notify } from '@/lib/notify';
import {
  useInsightsFootfall, useInsightsHourly, useInsightsKitchen, useInsightsOccupancy, useInsightsPour, useInsightsQueues,
  useInsightsRecompute, useInsightsStaff, useInsightsSummary, useInsightsTables, type FootfallFigures,
  type FootfallZoneRow, type HourRow,
  type InsightsSummary, type OccupancyHourRow, type OccupancyRow, type PourRow, type QueueHourRow, type QueueRow,
  type StaffRow, type StationRow, type TableRow,
} from '@/features/loss-prevention/insights-api';
import { dur, hourText, num, pct, type Num } from './lp-format';

const TABLE = { cellClassName: 'py-2.5 px-4', headerRowClassName: 'h-10' } as const;

function Stat({ label, value, hint, partial }: { label: string; value: string | number; hint?: string; partial?: string }) {
  return (
    <Card>
      <CardContent className="p-4">
        <p className="text-xs text-muted-foreground">
          {label}
          {partial && <span className="ml-1.5 rounded bg-amber-100 px-1.5 py-0.5 text-amber-900 dark:bg-amber-900/40 dark:text-amber-200">{partial}</span>}
        </p>
        <p className="text-2xl font-semibold tabular-nums">{value}</p>
        {hint && <p className="text-xs text-muted-foreground">{hint}</p>}
      </CardContent>
    </Card>
  );
}

/** The sales figures shared by the staff table, per role or per person. */
const salesColumns: Column<StaffRow>[] = [
  { key: 'count', header: tx('Sales'), className: 'text-right', render: (r) => r.count },
  { key: 'averageTicket', header: tx('Average sale'), className: 'text-right', render: (r) => num(r.averageTicket) },
  { key: 'itemsPerSale', header: tx('Items per sale'), className: 'text-right', render: (r) => num(r.itemsPerSale, 1) },
  { key: 'voids', header: tx('Void rate'), className: 'text-right', render: (r) => pct(r.voids.amountRate) },
  { key: 'refunds', header: tx('Refund rate'), className: 'text-right', render: (r) => pct(r.refunds.amountRate) },
  { key: 'discounts', header: tx('Discount rate'), className: 'text-right', render: (r) => pct(r.discounts.rate) },
];

/**
 * Operations insights (LP6a): how the business ran, from the same ledger as the cases. No camera is needed for any
 * of these figures. Per-person staff figures are offered only with `lp:investigate`. Where the licence includes
 * cameras, the figures they count (LP6b) follow.
 */
export function InsightsTab({ canConfigure, canInvestigate, vision }: {
  canConfigure: boolean; canInvestigate: boolean; vision: boolean;
}) {
  const [from, setFrom] = useState('');
  const [to, setTo] = useState('');
  const [groupBy, setGroupBy] = useState('');
  const filters = { from: from || undefined, to: to || undefined };
  const summary = useInsightsSummary(filters);
  const hourly = useInsightsHourly(filters);
  const kitchen = useInsightsKitchen(filters);
  const staff = useInsightsStaff({ ...filters, groupBy: groupBy || undefined });
  const tables = useInsightsTables(filters);
  const pour = useInsightsPour(filters);
  const recompute = useInsightsRecompute();
  const d = summary.data;
  const m = d?.metrics;

  const onRecompute = () => {
    if (!d) return;
    recompute.mutate({ from: d.from, to: d.to }, {
      onSuccess: (r) => notify.success(`${r.days} ${tx('day(s) will be computed again within a few minutes')}`),
      onError: (e) => notify.error(apiMessage(e, tx('Could not start the recompute'))),
    });
  };

  const hourColumns: Column<HourRow>[] = [
    { key: 'hour', header: tx('Hour'), render: (r) => `${String(r.hour).padStart(2, '0')}:00` },
    { key: 'sales', header: tx('Sales'), className: 'text-right', render: (r) => r.sales },
    { key: 'averageTicket', header: tx('Average sale'), className: 'text-right', render: (r) => num(r.averageTicket) },
    { key: 'voids', header: tx('Voids'), className: 'text-right', render: (r) => r.voids },
    { key: 'refunds', header: tx('Refunds'), className: 'text-right', render: (r) => r.refunds },
    { key: 'kitchenTickets', header: tx('Kitchen tickets'), className: 'text-right', render: (r) => r.kitchenTickets },
    { key: 'averagePrepSeconds', header: tx('Average preparation'), className: 'text-right', render: (r) => dur(r.averagePrepSeconds) },
    { key: 'averageServeSeconds', header: tx('Average order to serve'), className: 'text-right', render: (r) => dur(r.averageServeSeconds) },
  ];
  const stationColumns: Column<StationRow>[] = [
    { key: 'station', header: tx('Station'), render: (r) => r.station ?? tx('Not recorded') },
    { key: 'tickets', header: tx('Tickets'), className: 'text-right', render: (r) => r.tickets },
    { key: 'averagePrepSeconds', header: tx('Average preparation'), className: 'text-right', render: (r) => dur(r.averagePrepSeconds) },
    { key: 'worstDayP90PrepSeconds', header: tx('Slowest day, 90th percentile'), className: 'text-right', render: (r) => dur(r.worstDayP90PrepSeconds) },
    { key: 'averageServeSeconds', header: tx('Average order to serve'), className: 'text-right', render: (r) => dur(r.averageServeSeconds) },
    { key: 'recallRate', header: tx('Recall rate'), className: 'text-right', render: (r) => pct(r.recallRate) },
  ];
  const staffColumns: Column<StaffRow>[] = [
    staff.data?.groupBy === 'person'
      ? { key: 'name', header: tx('Person'), render: (r) => r.name ?? r.actorId ?? tx('Not recorded') }
      : { key: 'role', header: tx('Role'), render: (r) => `${r.role ?? tx('No role recorded')} (${r.people ?? 0})` },
    ...salesColumns,
  ];
  const tableColumns: Column<TableRow>[] = [
    { key: 'name', header: tx('Table'), render: (r) => r.name ?? r.tableId },
    { key: 'orders', header: tx('Orders'), className: 'text-right', render: (r) => r.orders },
    { key: 'daysUsed', header: tx('Days used'), className: 'text-right', render: (r) => r.daysUsed },
    { key: 'turnsPerDayUsed', header: tx('Orders per day used'), className: 'text-right', render: (r) => num(r.turnsPerDayUsed) },
    { key: 'averagePerOrder', header: tx('Average order'), className: 'text-right', render: (r) => num(r.averagePerOrder) },
  ];
  const pourColumns: Column<PourRow>[] = [
    { key: 'productName', header: tx('Product'), render: (r) => r.productName ?? r.productId ?? tx('Not recorded') },
    { key: 'lines', header: tx('Counts'), className: 'text-right', render: (r) => r.lines },
    { key: 'soldMl', header: tx('Sold (ml)'), className: 'text-right', render: (r) => num(r.soldMl, 0) },
    { key: 'consumedMl', header: tx('Consumed (ml)'), className: 'text-right', render: (r) => num(r.consumedMl, 0) },
    { key: 'yield', header: tx('Yield'), className: 'text-right', render: (r) => pct(r.yield) },
    { key: 'consumedCost', header: tx('Cost of consumed'), className: 'text-right', render: (r) => num(r.consumedCost) },
  ];

  const cashValue = m ? (m.cash.withinToleranceRate !== null ? pct(m.cash.withinToleranceRate) : pct(m.cash.balancedRate)) : '—';
  const cashHint = m ? (m.cash.tolerance !== null
    ? `${m.cash.withinTolerance ?? 0} / ${m.cash.sessions} ${tx('sessions within')} ${num(m.cash.tolerance)}`
    : `${m.cash.balanced} / ${m.cash.sessions} ${tx('sessions balanced; set rule C08 for a tolerance')}`) : undefined;

  const tableCover = d?.coverage.tables;
  const tablesPartial = tableCover?.partial ? tx('Partial') : undefined;
  const tablesHint = tableCover?.partial
    ? `${tableCover.salesRecordingTable} / ${tableCover.sales} ${tx('sales record their table; sales recorded before this report was added do not')}`
    : tx('Orders per table per day it was used');

  return (
    <>
      <ListToolbar>
        <DateRangeFilter from={from} to={to} onChange={(f, t) => { setFrom(f); setTo(t); }} />
        {d && <span className="text-sm text-muted-foreground">{d.from} – {d.to}</span>}
        {canConfigure && (
          <Button variant="outline" size="sm" disabled={!d || recompute.isPending} onClick={onRecompute}>
            <RefreshCw className="mr-1.5 h-4 w-4" />{tx('Compute this period again')}
          </Button>
        )}
      </ListToolbar>
      {summary.isError && <p className="text-destructive">{tx('Could not load the insights. Try again.')}</p>}
      {d && (
        <p className="text-xs text-muted-foreground">
          {d.status.lastComputedAt ? `${tx('Last computed')}: ${dateTime(d.status.lastComputedAt)}. ` : ''}
          {Number(d.status.pendingDays) > 0 && `${d.status.pendingDays} ${tx('day(s) waiting to be computed')}. `}
          {Number(d.status.failedDays) > 0 && `${d.status.failedDays} ${tx('day(s) failed to compute and will be tried again')}.`}
          {` ${tx('Days with sales')}: ${d.coverage.sales.daysWithData} / ${d.coverage.sales.periodDays}; ${tx('with kitchen tickets')}: ${d.coverage.kitchen.daysWithData}; ${tx('with closed cash sessions')}: ${d.coverage.cash.daysWithData}; ${tx('with bottle counts')}: ${d.coverage.pour.daysWithData}.`}
        </p>
      )}
      {m && (
        <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
          <Stat label={tx('Kitchen preparation, average')} value={dur(m.kitchen.averagePrepSeconds)}
            hint={`${m.kitchen.prepared} ${tx('tickets; slowest day p90')} ${dur(m.kitchen.worstDayP90PrepSeconds)}`} />
          <Stat label={tx('Order to serve, average')} value={dur(m.kitchen.averageServeSeconds)}
            hint={`${m.kitchen.dispatched} ${tx('tickets dispatched')}`} />
          <Stat label={tx('Kitchen recall rate')} value={pct(m.kitchen.recallRate)}
            hint={`${m.kitchen.recalled} / ${m.kitchen.tickets} ${tx('tickets')}`} />
          <Stat label={tx('Table turnover')} value={num(m.tables.turnsPerTablePerDay)} hint={tablesHint}
            partial={tablesPartial} />
          <Stat label={tx('Average sale')} value={num(m.sales.averageTicket)}
            hint={`${m.sales.count} ${tx('sales')}, ${num(m.sales.itemsPerSale, 1)} ${tx('items per sale')}`} />
          <Stat label={tx('Void, refund, discount rates')}
            value={`${pct(m.sales.voids.amountRate)} · ${pct(m.sales.refunds.amountRate)} · ${pct(m.sales.discounts.rate)}`}
            hint={tx('Share of the amount sold')} />
          <Stat label={tx('Cash session accuracy')} value={cashValue} hint={cashHint} />
          <Stat label={tx('Pour yield')} value={pct(m.pour.yield)}
            hint={`${num(m.pour.soldMl, 0)} ${tx('ml sold of')} ${num(m.pour.consumedMl, 0)} ${tx('ml consumed')}`} />
        </div>
      )}
      <p className="text-sm font-medium">{tx('By hour of the day')}</p>
      <ListCard>
        <div className="overflow-x-auto">
          <DataTable columns={hourColumns} data={(hourly.data?.rows ?? []).filter((r) => r.sales > 0 || r.kitchenTickets > 0)}
            loading={hourly.isLoading} loadingRows={4} getRowId={(r) => String(r.hour)} {...TABLE}
            emptyMessage={tx('No sales or kitchen tickets in this period.')} />
        </div>
      </ListCard>
      <p className="text-sm font-medium">{tx('Kitchen stations')}</p>
      <ListCard>
        <div className="overflow-x-auto">
          <DataTable columns={stationColumns} data={kitchen.data?.rows ?? []} loading={kitchen.isLoading} loadingRows={3}
            getRowId={(r) => r.station ?? ''} {...TABLE} emptyMessage={tx('No kitchen tickets in this period.')} />
        </div>
      </ListCard>
      <div className="flex items-center gap-3">
        <p className="text-sm font-medium">{tx('Staff')}</p>
        {canInvestigate && (
          <FilterSelect value={groupBy} onChange={setGroupBy} allLabel={tx('By role')}
            options={[{ value: 'person', label: tx('By person') }]} />
        )}
      </div>
      <ListCard>
        <div className="overflow-x-auto">
          <DataTable columns={staffColumns} data={staff.data?.rows ?? []} loading={staff.isLoading} loadingRows={3}
            getRowId={(r) => r.actorId ?? r.role ?? ''} {...TABLE} emptyMessage={tx('No sales in this period.')} />
        </div>
      </ListCard>
      <p className="text-sm font-medium">{tx('Tables')}</p>
      {tableCover?.partial && (
        <p className="text-xs text-muted-foreground">
          {tx('Only sales that record their table are counted here; sales recorded before this report was added do not, so earlier periods are incomplete.')}
        </p>
      )}
      <ListCard>
        <div className="overflow-x-auto">
          <DataTable columns={tableColumns} data={tables.data?.rows ?? []} loading={tables.isLoading} loadingRows={3}
            getRowId={(r) => r.tableId} {...TABLE} emptyMessage={tx('No sales with a table in this period.')} />
        </div>
      </ListCard>
      <p className="text-sm font-medium">{tx('Pour yield by product')}</p>
      <ListCard>
        <div className="overflow-x-auto">
          <DataTable columns={pourColumns} data={pour.data?.rows ?? []} loading={pour.isLoading} loadingRows={3}
            getRowId={(r) => r.productId ?? ''} {...TABLE} emptyMessage={tx('No bottle counts in this period.')} />
        </div>
      </ListCard>
      {vision && <CameraInsights filters={filters} summary={d} />}
      {d && <p className="text-xs text-muted-foreground">{d.note}</p>}
    </>
  );
}

/**
 * Figures counted by cameras (LP6b): footfall and conversion at entrances, queues, table occupancy. They count people
 * and tables, never who they are, and how accurately a camera counts has not been measured: the note says so first.
 */
function CameraInsights({ filters, summary }: { filters: { from?: string; to?: string }; summary?: InsightsSummary }) {
  const footfall = useInsightsFootfall(filters);
  const queues = useInsightsQueues(filters);
  const occupancy = useInsightsOccupancy(filters);
  const c = summary?.metrics.camera;
  const cover = summary?.coverage.camera;
  const note = summary?.cameraNote ?? footfall.data?.cameraNote ?? queues.data?.cameraNote ?? occupancy.data?.cameraNote;

  const footfallColumns = <T extends FootfallFigures>(first: Column<T>): Column<T>[] => [
    first,
    { key: 'in', header: tx('People in'), className: 'text-right', render: (r) => r.in },
    { key: 'out', header: tx('People out'), className: 'text-right', render: (r) => r.out },
    { key: 'sales', header: tx('Sales'), className: 'text-right', render: (r) => r.sales },
    { key: 'conversionRate', header: tx('Conversion'), className: 'text-right', render: (r) => pct(r.conversionRate) },
  ];
  const dayColumns = footfallColumns<FootfallFigures & { day: string }>(
    { key: 'day', header: tx('Day'), render: (r) => r.day });
  const footfallHourColumns = footfallColumns<FootfallFigures & { hour: number }>(
    { key: 'hour', header: tx('Hour'), render: (r) => hourText(r.hour) });
  const entranceColumns: Column<FootfallZoneRow>[] = [
    { key: 'name', header: tx('Entrance'), render: (r) => r.name ?? r.zoneId },
    { key: 'cameraName', header: tx('Camera'), render: (r) => r.cameraName ?? '—' },
    { key: 'in', header: tx('People in'), className: 'text-right', render: (r) => r.in },
    { key: 'out', header: tx('People out'), className: 'text-right', render: (r) => r.out },
    { key: 'gapSeconds', header: tx('Time without a picture'), className: 'text-right', render: (r) => dur(r.gapSeconds) },
  ];
  const queueColumns: Column<QueueRow>[] = [
    { key: 'name', header: tx('Queue'), render: (r) => r.name ?? r.zoneId },
    { key: 'waits', header: tx('People who waited'), className: 'text-right', render: (r) => r.waits },
    { key: 'averageWaitSeconds', header: tx('Average wait'), className: 'text-right', render: (r) => dur(r.averageWaitSeconds) },
    { key: 'worstDayP90WaitSeconds', header: tx('Slowest day, 90th percentile'), className: 'text-right', render: (r) => dur(r.worstDayP90WaitSeconds) },
    { key: 'averageLength', header: tx('Average length (people)'), className: 'text-right', render: (r) => num(r.averageLength) },
    { key: 'worstDayP90Length', header: tx('Longest day, 90th percentile (people)'), className: 'text-right', render: (r) => num(r.worstDayP90Length) },
    { key: 'observedSeconds', header: tx('Time observed'), className: 'text-right', render: (r) => dur(r.observedSeconds) },
    { key: 'gapSeconds', header: tx('Time without a picture'), className: 'text-right', render: (r) => dur(r.gapSeconds) },
  ];
  const queueHourColumns: Column<QueueHourRow>[] = [
    { key: 'hour', header: tx('Hour'), render: (r) => hourText(r.hour) },
    { key: 'waits', header: tx('People who waited'), className: 'text-right', render: (r) => r.waits },
    { key: 'averageWaitSeconds', header: tx('Average wait'), className: 'text-right', render: (r) => dur(r.averageWaitSeconds) },
    { key: 'averageLength', header: tx('Average length (people)'), className: 'text-right', render: (r) => num(r.averageLength) },
  ];
  const tableName = (r: OccupancyRow) => {
    const table = r.tableName ? `${r.tableNumber !== null && r.tableNumber !== undefined ? `${r.tableNumber} · ` : ''}${r.tableName}` : null;
    return table ? `${r.name ?? r.zoneId} (${table})` : r.name ?? r.zoneId;
  };
  const occupancyColumns: Column<OccupancyRow>[] = [
    { key: 'name', header: tx('Dining zone'), render: tableName },
    { key: 'occupancyRate', header: tx('Occupied'), className: 'text-right', render: (r) => pct(r.occupancyRate) },
    { key: 'occupiedSeconds', header: tx('Time occupied'), className: 'text-right', render: (r) => dur(r.occupiedSeconds) },
    { key: 'seatings', header: tx('Seatings'), className: 'text-right', render: (r) => r.seatings },
    { key: 'ordered', header: tx('With an order'), className: 'text-right', render: (r) => (r.linkedType === 'table' ? r.ordered : '—') },
    { key: 'averageWaitToOrderSeconds', header: tx('Average wait to order'), className: 'text-right', render: (r) => dur(r.averageWaitToOrderSeconds) },
    { key: 'worstDayP90WaitToOrderSeconds', header: tx('Slowest day, 90th percentile'), className: 'text-right', render: (r) => dur(r.worstDayP90WaitToOrderSeconds) },
    { key: 'gapSeconds', header: tx('Time without a picture'), className: 'text-right', render: (r) => dur(r.gapSeconds) },
  ];
  const occupancyHourColumns: Column<OccupancyHourRow>[] = [
    { key: 'hour', header: tx('Hour'), render: (r) => hourText(r.hour) },
    { key: 'occupancyRate', header: tx('Occupied'), className: 'text-right', render: (r) => pct(r.occupancyRate) },
    { key: 'seatings', header: tx('Seatings'), className: 'text-right', render: (r) => r.seatings },
    { key: 'averageWaitToOrderSeconds', header: tx('Average wait to order'), className: 'text-right', render: (r) => dur(r.averageWaitToOrderSeconds) },
  ];

  const failed = footfall.isError || queues.isError || occupancy.isError;
  const set = (v: Num) => v !== null && v !== undefined && v !== '';

  return (
    <>
      <p className="pt-2 text-base font-semibold">{tx('Counted by cameras')}</p>
      {note && (
        <p className="rounded-md border border-amber-300 bg-amber-50 p-3 text-sm text-amber-900 dark:border-amber-800 dark:bg-amber-900/30 dark:text-amber-100">
          {note}
        </p>
      )}
      {cover && (
        <p className="text-xs text-muted-foreground">
          {`${tx('Days with camera counts')}: ${cover.daysWithData} / ${cover.periodDays}; ${tx('time without a usable picture')}: ${dur(cover.gapSeconds)}.`}
          {!cover.measured && ` ${tx('Not yet checked against a reviewed sample of video.')}`}
        </p>
      )}
      {failed && <p className="text-destructive">{tx('Could not load the camera figures. Try again.')}</p>}
      {c && (
        <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
          <Stat label={tx('Footfall, people in')} value={c.footfall.in}
            hint={`${c.footfall.out} ${tx('people out')}`} />
          <Stat label={tx('Conversion')} value={pct(c.footfall.conversionRate)}
            hint={`${c.footfall.sales} ${tx('sales on the days with footfall, per person in')}`} />
          <Stat label={tx('Queue wait, average')} value={dur(c.queues.averageWaitSeconds)}
            hint={`${c.queues.waits} ${tx('people waited; slowest day p90')} ${dur(c.queues.worstDayP90WaitSeconds)}; ${tx('average length')} ${num(c.queues.averageLength)}`} />
          <Stat label={tx('Table occupancy')} value={pct(c.tables.occupancyRate)}
            hint={`${c.tables.seatings} ${tx('seatings')}${set(c.tables.averageWaitToOrderSeconds)
              ? `; ${tx('average wait to order')} ${dur(c.tables.averageWaitToOrderSeconds)}` : ''}`} />
        </div>
      )}
      <p className="text-sm font-medium">{tx('Footfall by day')}</p>
      <ListCard>
        <div className="overflow-x-auto">
          <DataTable columns={dayColumns} data={(footfall.data?.days ?? []).filter((r) => r.in > 0 || r.out > 0)}
            loading={footfall.isLoading} loadingRows={3} getRowId={(r) => r.day} {...TABLE}
            emptyMessage={tx('No one counted at an entrance in this period.')} />
        </div>
      </ListCard>
      <p className="text-sm font-medium">{tx('Footfall by hour of the day')}</p>
      <ListCard>
        <div className="overflow-x-auto">
          <DataTable columns={footfallHourColumns} data={(footfall.data?.hours ?? []).filter((r) => r.in > 0 || r.out > 0)}
            loading={footfall.isLoading} loadingRows={3} getRowId={(r) => String(r.hour)} {...TABLE}
            emptyMessage={tx('No one counted at an entrance in this period.')} />
        </div>
      </ListCard>
      <p className="text-sm font-medium">{tx('Entrances')}</p>
      <ListCard>
        <div className="overflow-x-auto">
          <DataTable columns={entranceColumns} data={footfall.data?.zones ?? []} loading={footfall.isLoading} loadingRows={2}
            getRowId={(r) => r.zoneId} {...TABLE} emptyMessage={tx('No entrance zones with counts in this period.')} />
        </div>
      </ListCard>
      <p className="text-sm font-medium">{tx('Queues')}</p>
      <ListCard>
        <div className="overflow-x-auto">
          <DataTable columns={queueColumns} data={queues.data?.rows ?? []} loading={queues.isLoading} loadingRows={2}
            getRowId={(r) => r.zoneId} {...TABLE} emptyMessage={tx('No queue zones with counts in this period.')} />
        </div>
      </ListCard>
      <p className="text-sm font-medium">{tx('Queues by hour of the day')}</p>
      <ListCard>
        <div className="overflow-x-auto">
          <DataTable columns={queueHourColumns}
            data={(queues.data?.hours ?? []).filter((r) => r.waits > 0 || set(r.averageLength))}
            loading={queues.isLoading} loadingRows={3} getRowId={(r) => String(r.hour)} {...TABLE}
            emptyMessage={tx('No queue zones with counts in this period.')} />
        </div>
      </ListCard>
      <p className="text-sm font-medium">{tx('Table occupancy')}</p>
      <p className="text-xs text-muted-foreground">
        {tx('A seating is a table seen occupied right after it was seen empty. The wait to order runs to the first kitchen ticket of the table, so it is shown only for dining zones linked to a table.')}
      </p>
      <ListCard>
        <div className="overflow-x-auto">
          <DataTable columns={occupancyColumns} data={occupancy.data?.rows ?? []} loading={occupancy.isLoading}
            loadingRows={2} getRowId={(r) => r.zoneId} {...TABLE}
            emptyMessage={tx('No dining zones with counts in this period.')} />
        </div>
      </ListCard>
      <p className="text-sm font-medium">{tx('Table occupancy by hour of the day')}</p>
      <ListCard>
        <div className="overflow-x-auto">
          <DataTable columns={occupancyHourColumns}
            data={(occupancy.data?.hours ?? []).filter((r) => set(r.occupancyRate) || r.seatings > 0)}
            loading={occupancy.isLoading} loadingRows={3} getRowId={(r) => String(r.hour)} {...TABLE}
            emptyMessage={tx('No dining zones with counts in this period.')} />
        </div>
      </ListCard>
    </>
  );
}

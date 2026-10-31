import { useNavigate } from 'react-router-dom';
import { ClipboardCheck, Eye } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { DataTable, type Column } from '@/components/data-table';
import {
  DataTablePagination, DateRangeFilter, FilterChips, FilterSelect, ListCard, ListPageHeader, ListToolbar, useListState,
  type ActiveChip,
} from '@/components/list';
import { date } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';
import { useWorkCenters } from '@/features/manufacturing/api';
import {
  SHIFT_REPORT_STATUS_LABEL, pct, qty, useShiftReports, type ShiftReport, type ShiftReportStatus,
} from '@/features/manufacturing/api-shopfloor';
import { ShiftReportStatusPill } from './shopfloor-ui';

const FILTERS = { workCenterId: '', status: '', from: '', to: '' };

/** Shift reports (ADR-S015): one per work centre and shift; submitted by the shift lead, approved by a supervisor. */
export function ShiftReportsPage() {
  const navigate = useNavigate();
  const list = useListState(FILTERS);
  const { workCenterId, status, from, to } = list.filters;
  const centres = useWorkCenters();
  const { data, isLoading, isFetching, isError } = useShiftReports({
    page: list.page, pageSize: list.pageSize, workCenterId: workCenterId || undefined, status: status || undefined,
    from: from || undefined, to: to || undefined,
  });
  const centreName = (id: string) => (centres.data ?? []).find((c: any) => c.id === id)?.name ?? id;
  const chips: ActiveChip[] = [
    ...(workCenterId ? [{ key: 'wc', label: centreName(workCenterId), onRemove: () => list.setFilter('workCenterId', '') }] : []),
    ...(status ? [{ key: 'status', label: SHIFT_REPORT_STATUS_LABEL[status as ShiftReportStatus] ?? status, onRemove: () => list.setFilter('status', '') }] : []),
    ...(from || to ? [{ key: 'range', label: `${from || '…'} → ${to || '…'}`, onRemove: () => list.setFilters({ from: '', to: '' }) }] : []),
  ];
  const columns: Column<ShiftReport>[] = [
    { key: 'code', header: tx('Report'), render: (r) => <span className="font-medium text-primary">{r.code}</span> },
    { key: 'date', header: tx('Date'), render: (r) => <span className="whitespace-nowrap">{date(r.shiftDate)}</span> },
    { key: 'shift', header: tx('Shift'), render: (r) => r.shiftName },
    { key: 'wc', header: tx('Work centre'), render: (r) => <div><div>{r.workCenterName}</div><div className="text-xs text-muted-foreground">{r.workCenterCode}</div></div> },
    { key: 'good', header: tx('Good'), className: 'text-right', render: (r) => qty(r.oee?.goodQty) },
    { key: 'scrap', header: tx('Scrap'), className: 'text-right', render: (r) => qty(r.oee?.scrapQty) },
    { key: 'down', header: tx('Downtime'), className: 'text-right', render: (r) => tx('{{n}} min', { n: r.oee?.downtimeMinutes ?? 0 }) },
    { key: 'oee', header: tx('OEE'), className: 'text-right', render: (r) => <b>{pct(r.oee?.oee)}</b> },
    { key: 'drafts', header: tx('Drafts'), className: 'text-right', render: (r) => r.pendingDrafts || '—' },
    { key: 'status', header: tx('Status'), render: (r) => <ShiftReportStatusPill status={r.status} /> },
    {
      key: 'actions', header: '', className: 'w-12 text-right',
      render: (r) => <Button variant="ghost" size="icon" className="h-8 w-8" aria-label={tx('View')} onClick={() => navigate(`/manufacturing/shift-reports/${r.id}`)}><Eye className="h-4 w-4" /></Button>,
    },
  ];
  return (
    <div className="space-y-4">
      <ListPageHeader icon={ClipboardCheck} title={tx('Shift reports')}
        description={tx('Output, scrap, downtime, crew and OEE per work centre and shift, with supervisor approval.')} />
      <ListToolbar chips={<FilterChips chips={chips} onClearAll={list.clearFilters} />}>
        <FilterSelect value={workCenterId} onChange={(v) => list.setFilter('workCenterId', v)} allLabel={tx('All work centres')}
          options={(centres.data ?? []).map((c: any) => ({ value: c.id, label: c.name }))} />
        <FilterSelect value={status} onChange={(v) => list.setFilter('status', v)} allLabel={tx('All statuses')}
          options={Object.entries(SHIFT_REPORT_STATUS_LABEL).map(([value, label]) => ({ value, label }))} />
        <DateRangeFilter from={from} to={to} onChange={(f, t) => list.setFilters({ from: f, to: t })} />
      </ListToolbar>
      <ListCard>
        <div className={isFetching && !isLoading ? 'opacity-60 transition-opacity' : 'transition-opacity'}>
          <DataTable columns={columns} data={data?.data ?? []} loading={isLoading} getRowId={(r) => r.id}
            onRowClick={(r) => navigate(`/manufacturing/shift-reports/${r.id}`)} cellClassName="py-2.5 px-4" headerRowClassName="h-10"
            emptyMessage={isError ? tx('Could not load the shift reports.') : chips.length ? tx('No shift reports match these filters.') : tx('No shift reports yet. They open when the floor records output, downtime or crew.')} />
        </div>
        {data?.meta && (
          <DataTablePagination page={data.meta.page} pageSize={list.pageSize} total={data.meta.total} totalPages={data.meta.totalPages}
            onPageChange={list.setPage} onPageSizeChange={list.setPageSize} noun="report" />
        )}
      </ListCard>
    </div>
  );
}

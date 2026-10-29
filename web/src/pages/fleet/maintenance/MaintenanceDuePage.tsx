import { useNavigate } from 'react-router-dom';
import { CalendarClock, Wrench } from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { Button } from '@/components/ui/button';
import { DataTable, type Column } from '@/components/data-table';
import {
  DataTablePagination, FilterSelect, ListCard, ListPageHeader, ListToolbar, useListState,
} from '@/components/list';
import { useAuthStore } from '@/stores/auth.store';
import { notify, apiMessage } from '@/lib/notify';
import { date } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';
import { useFleetLookups } from '@/features/fleet/api';
import {
  DUE_STATE_LABEL, useDueSchedules, useGenerateDue, useGenerateOne, WO_STATUS_LABEL, type ServiceSchedule,
} from '@/features/fleet/maintenance-api';
import { DueStatePill, dueText, unitLabel } from './maint-ui';

const FILTERS = { state: '', categoryId: '', branchId: '' };

/** Services due and overdue (km / hours / calendar, whichever first) with work-order generation (fleet 1.6). */
export function MaintenanceDuePage() {
  const navigate = useNavigate();
  const has = useAuthStore((s) => s.hasPermission);
  const list = useListState(FILTERS, { defaultPageSize: 50 });
  const { state, categoryId, branchId } = list.filters;
  const lookups = useFleetLookups();
  const { data, isLoading, isError } = useDueSchedules({
    page: list.page, pageSize: list.pageSize, state: state || 'attention', categoryId: categoryId || undefined,
    branchId: branchId || undefined,
  });
  const generateAll = useGenerateDue();
  const generateOne = useGenerateOne();

  const runAll = async () => {
    try {
      const r = await generateAll.mutateAsync();
      notify.success(tx('{{n}} work order(s) raised', { n: r.created }));
    } catch (err) {
      notify.error(apiMessage(err, tx('Could not raise the work orders')));
    }
  };
  const runOne = async (s: ServiceSchedule) => {
    try {
      const wo = await generateOne.mutateAsync(s.id);
      navigate(`/fleet/maintenance/work-orders/${wo.id}`);
    } catch (err) {
      notify.error(apiMessage(err, tx('Could not raise the work order')));
    }
  };

  const columns: Column<ServiceSchedule>[] = [
    { key: 'unit', header: tx('Vehicle / equipment'), render: (s) => <span className="font-medium text-primary">{unitLabel(s.vehicle)}</span> },
    { key: 'class', header: tx('Class'), render: (s) => s.vehicle?.categoryName ?? '—' },
    { key: 'name', header: tx('Service'), render: (s) => s.name },
    { key: 'state', header: tx('Status'), render: (s) => <DueStatePill state={s.dueState} /> },
    { key: 'remaining', header: tx('Remaining'), render: (s) => <span className="tabular-nums">{dueText(s.due)}</span> },
    { key: 'last', header: tx('Last service'), render: (s) => date(s.lastDoneOn) },
    {
      key: 'wo', header: tx('Work order'),
      render: (s) => (s.liveWorkOrder
        ? `${s.liveWorkOrder.code} · ${WO_STATUS_LABEL[s.liveWorkOrder.status]}`
        : has(PERMISSIONS.fleetMaintenance.create)
          ? <Button size="sm" variant="outline" onClick={(e) => { e.stopPropagation(); runOne(s); }} disabled={generateOne.isPending}>{tx('Raise work order')}</Button>
          : '—'),
    },
  ];

  return (
    <div className="space-y-4">
      <ListPageHeader
        icon={CalendarClock}
        title={tx('Services Due')}
        description={tx('Preventive maintenance due by kilometres, engine hours or date — whichever comes first.')}
        actions={has(PERMISSIONS.fleetMaintenance.schedule) && (
          <Button onClick={runAll} disabled={generateAll.isPending}><Wrench className="mr-1.5 h-4 w-4" /> {tx('Raise all due work orders')}</Button>
        )}
      />
      <ListToolbar>
        <FilterSelect value={state} onChange={(v) => list.setFilter('state', v)}
          options={[{ value: 'due', label: DUE_STATE_LABEL.due }, { value: 'overdue', label: DUE_STATE_LABEL.overdue }, { value: 'all', label: tx('All schedules') }]}
          allLabel={tx('Due and overdue')} />
        <FilterSelect value={categoryId} onChange={(v) => list.setFilter('categoryId', v)}
          options={(lookups.data?.categories ?? []).map((c) => ({ value: c.id, label: c.name }))} allLabel={tx('All classes')} />
        {(lookups.data?.branches ?? []).length > 0 && (
          <FilterSelect value={branchId} onChange={(v) => list.setFilter('branchId', v)}
            options={(lookups.data?.branches ?? []).map((b) => ({ value: b.id, label: b.name }))} allLabel={tx('All branches')} />
        )}
      </ListToolbar>
      <ListCard>
        <DataTable
          columns={columns}
          data={data?.data ?? []}
          loading={isLoading}
          getRowId={(s) => s.id}
          onRowClick={(s) => navigate(`/fleet/vehicles/${s.vehicleId}?tab=maintenance`)}
          emptyMessage={isError ? tx('Could not load the schedules.') : tx('Nothing is due. All services are up to date.')}
        />
        {data?.meta && (
          <DataTablePagination page={data.meta.page} pageSize={list.pageSize} total={data.meta.total}
            totalPages={data.meta.totalPages} onPageChange={list.setPage} onPageSizeChange={list.setPageSize} noun="schedule" />
        )}
      </ListCard>
    </div>
  );
}

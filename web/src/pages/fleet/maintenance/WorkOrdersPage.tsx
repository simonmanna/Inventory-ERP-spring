import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { ClipboardList, Plus } from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Textarea } from '@/components/ui/textarea';
import { Dialog, DialogContent, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { DataTable, type Column } from '@/components/data-table';
import {
  DataTablePagination, FilterChips, FilterSelect, ListCard, ListPageHeader, ListToolbar, SearchInput, useListState,
  type ActiveChip,
} from '@/components/list';
import { useAuthStore } from '@/stores/auth.store';
import { notify, apiMessage } from '@/lib/notify';
import { date, money } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';
import {
  PRIORITY_LABEL, useCreateWorkOrder, useWorkOrders, WO_STATUS_LABEL, WO_TYPE_LABEL, type Priority, type WorkOrder,
  type WorkOrderType,
} from '@/features/fleet/maintenance-api';
import { Field, OptionalSelect } from '../fleet-ui';
import { toOptions } from '../fleet-format';
import { PriorityPill, unitLabel, useUnitOptions, WorkOrderStatusPill } from './maint-ui';

const FILTERS = { status: '', type: '', priority: '' };

/** Maintenance work orders: preventive, corrective, breakdown and defect repairs (fleet 1.6). */
export function WorkOrdersPage() {
  const navigate = useNavigate();
  const canCreate = useAuthStore((s) => s.hasPermission(PERMISSIONS.fleetMaintenance.create));
  const list = useListState(FILTERS);
  const { status, type, priority } = list.filters;
  const [creating, setCreating] = useState(false);
  const { data, isLoading, isFetching, isError } = useWorkOrders({
    page: list.page, pageSize: list.pageSize, search: list.search || undefined, status: status || undefined,
    type: type || undefined, priority: priority || undefined, sortBy: list.sort?.by, sortOrder: list.sort?.order,
  });

  const statusOptions = [{ value: 'live', label: tx('Open work') }, ...toOptions(WO_STATUS_LABEL)];
  const chips: ActiveChip[] = [
    ...(list.search ? [{ key: 'q', label: `“${list.search}”`, onRemove: () => list.setSearchInput('') }] : []),
    ...(status ? [{ key: 's', label: statusOptions.find((o) => o.value === status)?.label ?? status, onRemove: () => list.setFilter('status', '') }] : []),
    ...(type ? [{ key: 't', label: WO_TYPE_LABEL[type as WorkOrderType] ?? type, onRemove: () => list.setFilter('type', '') }] : []),
    ...(priority ? [{ key: 'p', label: PRIORITY_LABEL[priority as Priority] ?? priority, onRemove: () => list.setFilter('priority', '') }] : []),
  ];

  const columns: Column<WorkOrder>[] = [
    { key: 'code', header: tx('Number'), sortKey: 'code', render: (w) => <span className="font-medium text-primary">{w.code}</span> },
    { key: 'unit', header: tx('Vehicle / equipment'), render: (w) => unitLabel(w.vehicle) },
    { key: 'title', header: tx('Title'), render: (w) => <span className="line-clamp-2 min-w-[10rem]">{w.title}</span> },
    { key: 'type', header: tx('Type'), render: (w) => WO_TYPE_LABEL[w.type] },
    { key: 'priority', header: tx('Priority'), sortKey: 'priority', render: (w) => <PriorityPill priority={w.priority} /> },
    { key: 'dueOn', header: tx('Due'), sortKey: 'dueOn', render: (w) => (w.dueOn ? date(w.dueOn) : '—') },
    { key: 'cost', header: tx('Cost'), sortKey: 'totalCost', className: 'text-right', render: (w) => <span className="tabular-nums">{money(w.totalCost)}</span> },
    { key: 'status', header: tx('Status'), sortKey: 'status', render: (w) => <WorkOrderStatusPill status={w.status} /> },
  ];

  return (
    <div className="space-y-4">
      <ListPageHeader
        icon={ClipboardList}
        title={tx('Maintenance Work Orders')}
        description={tx('Workshop jobs on trucks and plant: tasks, spare parts, labour, outside repairs and cost.')}
        actions={canCreate && <Button onClick={() => setCreating(true)}><Plus className="mr-1.5 h-4 w-4" /> {tx('New work order')}</Button>}
      />
      <ListToolbar chips={<FilterChips chips={chips} onClearAll={list.clearFilters} />}>
        <SearchInput value={list.searchInput} onChange={list.setSearchInput} placeholder={tx('Search number, title, vehicle…')} />
        <FilterSelect value={status} onChange={(v) => list.setFilter('status', v)} options={statusOptions} allLabel={tx('All statuses')} />
        <FilterSelect value={type} onChange={(v) => list.setFilter('type', v)} options={toOptions(WO_TYPE_LABEL)} allLabel={tx('All types')} />
        <FilterSelect value={priority} onChange={(v) => list.setFilter('priority', v)} options={toOptions(PRIORITY_LABEL)} allLabel={tx('Any priority')} />
      </ListToolbar>
      <ListCard>
        <div className={isFetching && !isLoading ? 'opacity-60 transition-opacity' : 'transition-opacity'}>
          <DataTable
            columns={columns}
            data={data?.data ?? []}
            loading={isLoading}
            getRowId={(w) => w.id}
            onRowClick={(w) => navigate(`/fleet/maintenance/work-orders/${w.id}`)}
            sort={list.sort}
            onSortChange={list.setSort}
            emptyMessage={isError ? tx('Could not load the work orders.') : chips.length ? tx('No work orders match these filters.') : tx('No work orders yet.')}
          />
        </div>
        {data?.meta && (
          <DataTablePagination page={data.meta.page} pageSize={list.pageSize} total={data.meta.total}
            totalPages={data.meta.totalPages} onPageChange={list.setPage} onPageSizeChange={list.setPageSize} noun="work order" />
        )}
      </ListCard>
      <NewWorkOrderDialog open={creating} onOpenChange={setCreating} onCreated={(id) => navigate(`/fleet/maintenance/work-orders/${id}`)} />
    </div>
  );
}

/** New corrective / breakdown / preventive work order; `vehicleId` preselects the unit. */
export function NewWorkOrderDialog({ open, onOpenChange, onCreated, vehicleId }: {
  open: boolean; onOpenChange: (o: boolean) => void; onCreated: (id: string) => void; vehicleId?: string;
}) {
  const units = useUnitOptions();
  const create = useCreateWorkOrder();
  const [unit, setUnit] = useState(vehicleId ?? '');
  const [type, setType] = useState<WorkOrderType>('corrective');
  const [priority, setPriority] = useState<Priority>('normal');
  const [title, setTitle] = useState('');
  const [description, setDescription] = useState('');
  const [dueOn, setDueOn] = useState('');
  const [tasks, setTasks] = useState('');
  const unitId = vehicleId ?? unit;

  const submit = async () => {
    try {
      const wo = await create.mutateAsync({
        vehicleId: unitId, type, priority, title: title.trim(), description: description.trim() || undefined,
        dueOn: dueOn || undefined, tasks: tasks.split('\n').map((t) => t.trim()).filter(Boolean),
      });
      notify.success(tx('Work order {{code}} created', { code: wo.code }));
      setTitle(''); setDescription(''); setTasks(''); setDueOn('');
      onOpenChange(false);
      onCreated(wo.id);
    } catch (err) {
      notify.error(apiMessage(err, tx('Could not create the work order')));
    }
  };

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-h-[90vh] overflow-y-auto">
        <DialogHeader><DialogTitle>{tx('New work order')}</DialogTitle></DialogHeader>
        <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
          {!vehicleId && (
            <Field label={tx('Vehicle / equipment *')} className="sm:col-span-2">
              <OptionalSelect value={unit} onChange={setUnit} options={units} placeholder={tx('Choose a vehicle')} />
            </Field>
          )}
          <Field label={tx('Type')}>
            <OptionalSelect value={type} onChange={(v) => setType((v || 'corrective') as WorkOrderType)} options={toOptions(WO_TYPE_LABEL)} placeholder={tx('Type')} />
          </Field>
          <Field label={tx('Priority')}>
            <OptionalSelect value={priority} onChange={(v) => setPriority((v || 'normal') as Priority)} options={toOptions(PRIORITY_LABEL)} placeholder={tx('Priority')} />
          </Field>
          <Field label={tx('Title *')} className="sm:col-span-2"><Input value={title} onChange={(e) => setTitle(e.target.value)} maxLength={200} /></Field>
          <Field label={tx('Description')} className="sm:col-span-2"><Textarea rows={3} value={description} onChange={(e) => setDescription(e.target.value)} /></Field>
          <Field label={tx('Due date')}><Input type="date" value={dueOn} onChange={(e) => setDueOn(e.target.value)} /></Field>
          <Field label={tx('Tasks (one per line)')} className="sm:col-span-2"><Textarea rows={3} value={tasks} onChange={(e) => setTasks(e.target.value)} /></Field>
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={() => onOpenChange(false)}>{tx('Cancel')}</Button>
          <Button onClick={submit} disabled={!unitId || !title.trim() || create.isPending}>{tx('Create')}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

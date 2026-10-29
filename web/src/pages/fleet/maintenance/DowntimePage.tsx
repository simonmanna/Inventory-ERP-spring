import { useState } from 'react';
import { Link } from 'react-router-dom';
import { Plus, TimerOff } from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { Button } from '@/components/ui/button';
import { Textarea } from '@/components/ui/textarea';
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { DataTable, type Column } from '@/components/data-table';
import {
  DataTablePagination, FilterSelect, ListCard, ListPageHeader, ListToolbar, StatusPill, useListState,
} from '@/components/list';
import { useAuthStore } from '@/stores/auth.store';
import { notify, apiMessage } from '@/lib/notify';
import { dateTime } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';
import {
  REASON_LABEL, useDowntime, useEndDowntime, useStartDowntime, type Downtime,
} from '@/features/fleet/maintenance-api';
import { Field, OptionalSelect } from '../fleet-ui';
import { num, toOptions } from '../fleet-format';
import { unitLabel, useUnitOptions } from './maint-ui';

const FILTERS = { kind: '', reasonCode: '', open: '' };

const KIND_LABEL = { unplanned: tx('Breakdown (unplanned)'), planned: tx('Planned service') };

/** Downtime log: breakdowns and planned service time with reason codes and work orders (fleet 1.6). */
export function DowntimePage() {
  const has = useAuthStore((s) => s.hasPermission);
  const list = useListState(FILTERS);
  const { kind, reasonCode, open } = list.filters;
  const { data, isLoading, isError } = useDowntime({
    page: list.page, pageSize: list.pageSize, kind: kind || undefined, reasonCode: reasonCode || undefined,
    open: open === '' ? undefined : open === 'true',
  });
  const end = useEndDowntime();
  const [recording, setRecording] = useState(false);

  const finish = async (d: Downtime) => {
    try {
      await end.mutateAsync({ id: d.id });
      notify.success(tx('Downtime ended; the vehicle is back in service'));
    } catch (err) {
      notify.error(apiMessage(err, tx('Could not end the downtime')));
    }
  };

  const columns: Column<Downtime>[] = [
    { key: 'unit', header: tx('Vehicle / equipment'), render: (d) => <Link className="font-medium text-primary hover:underline" to={`/fleet/vehicles/${d.vehicleId}`}>{unitLabel(d.vehicle)}</Link> },
    { key: 'kind', header: tx('Kind'), render: (d) => <StatusPill tone={d.kind === 'unplanned' ? 'danger' : 'info'}>{KIND_LABEL[d.kind]}</StatusPill> },
    { key: 'reason', header: tx('Reason'), render: (d) => <div>{REASON_LABEL[d.reasonCode] ?? d.reasonCode}{d.description && <div className="text-xs text-muted-foreground">{d.description}</div>}</div> },
    { key: 'start', header: tx('From'), render: (d) => <span className="whitespace-nowrap">{dateTime(d.startedAt)}</span> },
    { key: 'end', header: tx('To'), render: (d) => (d.endedAt ? <span className="whitespace-nowrap">{dateTime(d.endedAt)}</span> : <StatusPill tone="warning">{tx('Still down')}</StatusPill>) },
    { key: 'hours', header: tx('Hours'), className: 'text-right', render: (d) => <span className="tabular-nums">{num(d.durationHours, 2)}</span> },
    { key: 'wo', header: tx('Work order'), render: (d) => (d.workOrder ? <Link className="text-primary hover:underline" to={`/fleet/maintenance/work-orders/${d.workOrder.id}`}>{d.workOrder.code}</Link> : '—') },
    {
      key: 'actions', header: '', className: 'text-right',
      render: (d) => !d.endedAt && has(PERMISSIONS.fleetMaintenance.update) && (
        <Button size="sm" variant="outline" onClick={() => finish(d)} disabled={end.isPending}>{tx('End downtime')}</Button>
      ),
    },
  ];

  return (
    <div className="space-y-4">
      <ListPageHeader icon={TimerOff} title={tx('Downtime')}
        description={tx('When trucks and plant were out of service, why, and for how long.')}
        actions={has(PERMISSIONS.fleetMaintenance.create) && <Button onClick={() => setRecording(true)}><Plus className="mr-1.5 h-4 w-4" /> {tx('Report breakdown')}</Button>} />
      <ListToolbar>
        <FilterSelect value={kind} onChange={(v) => list.setFilter('kind', v)} options={toOptions(KIND_LABEL)} allLabel={tx('All kinds')} />
        <FilterSelect value={reasonCode} onChange={(v) => list.setFilter('reasonCode', v)} options={toOptions(REASON_LABEL)} allLabel={tx('All reasons')} />
        <FilterSelect value={open} onChange={(v) => list.setFilter('open', v)}
          options={[{ value: 'true', label: tx('Still down') }, { value: 'false', label: tx('Ended') }]} allLabel={tx('Any state')} />
      </ListToolbar>
      <ListCard>
        <DataTable columns={columns} data={data?.data ?? []} loading={isLoading} getRowId={(d) => d.id}
          emptyMessage={isError ? tx('Could not load the downtime.') : tx('No downtime recorded.')} />
        {data?.meta && (
          <DataTablePagination page={data.meta.page} pageSize={list.pageSize} total={data.meta.total}
            totalPages={data.meta.totalPages} onPageChange={list.setPage} onPageSizeChange={list.setPageSize} noun="record" />
        )}
      </ListCard>
      <DowntimeDialog open={recording} onOpenChange={setRecording} />
    </div>
  );
}

/** Report a breakdown (or planned downtime); optionally raise the work order with it. */
export function DowntimeDialog({ open, onOpenChange, vehicleId }: { open: boolean; onOpenChange: (o: boolean) => void; vehicleId?: string }) {
  const units = useUnitOptions();
  const start = useStartDowntime();
  const [unit, setUnit] = useState('');
  const [kind, setKind] = useState<'unplanned' | 'planned'>('unplanned');
  const [reasonCode, setReasonCode] = useState('');
  const [description, setDescription] = useState('');
  const [createWorkOrder, setCreateWorkOrder] = useState(true);
  const unitId = vehicleId ?? unit;
  const submit = async () => {
    try {
      await start.mutateAsync({ vehicleId: unitId, kind, reasonCode, description: description.trim() || undefined, createWorkOrder });
      notify.success(tx('Downtime recorded'));
      setReasonCode(''); setDescription('');
      onOpenChange(false);
    } catch (err) {
      notify.error(apiMessage(err, tx('Could not record the downtime')));
    }
  };
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>{tx('Report breakdown')}</DialogTitle>
          <DialogDescription>{tx('The vehicle is taken out of service until the downtime ends.')}</DialogDescription>
        </DialogHeader>
        <div className="space-y-4">
          {!vehicleId && (
            <Field label={tx('Vehicle / equipment *')}><OptionalSelect value={unit} onChange={setUnit} options={units} placeholder={tx('Choose a vehicle')} /></Field>
          )}
          <Field label={tx('Kind')}><OptionalSelect value={kind} onChange={(v) => setKind((v || 'unplanned') as 'unplanned' | 'planned')} options={toOptions(KIND_LABEL)} placeholder={tx('Kind')} /></Field>
          <Field label={tx('Reason *')}><OptionalSelect value={reasonCode} onChange={setReasonCode} options={toOptions(REASON_LABEL)} placeholder={tx('Choose a reason')} /></Field>
          <Field label={tx('What happened')}><Textarea rows={3} value={description} onChange={(e) => setDescription(e.target.value)} /></Field>
          <label className="flex items-center gap-2 text-sm">
            <input type="checkbox" checked={createWorkOrder} onChange={(e) => setCreateWorkOrder(e.target.checked)} /> {tx('Raise a work order now')}
          </label>
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={() => onOpenChange(false)}>{tx('Cancel')}</Button>
          <Button onClick={submit} disabled={!unitId || !reasonCode || start.isPending}>{tx('Record')}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

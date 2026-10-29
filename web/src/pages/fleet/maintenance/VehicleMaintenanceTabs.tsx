import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { CheckCircle2, Plus, ShieldAlert } from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Dialog, DialogContent, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { DataTable, type Column } from '@/components/data-table';
import { useAuthStore } from '@/stores/auth.store';
import { notify, apiMessage } from '@/lib/notify';
import { date, dateTime, money } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';
import type { Vehicle } from '@/features/fleet/api';
import {
  REASON_LABEL, useCreateSchedule, useDefects, useDeleteSchedule, useDowntime, useEligibility, useGenerateOne,
  useInspections, useServiceTemplates, useUpdateSchedule, useVehicleSchedules, useWorkOrders, WO_TYPE_LABEL,
  type ServiceSchedule, type WorkOrder,
} from '@/features/fleet/maintenance-api';
import { Field, OptionalSelect } from '../fleet-ui';
import { num } from '../fleet-format';
import { DueStatePill, dueText, toNumber, WorkOrderStatusPill } from './maint-ui';
import { intervalText } from './ServiceTemplatesPage';
import { NewWorkOrderDialog } from './WorkOrdersPage';
import { DowntimeDialog } from './DowntimePage';
import { DefectsTable, ReportDefectDialog } from './DefectsPage';
import { inspectionColumns } from './InspectionsPage';

/** Vehicle detail → Maintenance: service schedules with next due, work orders, downtime (fleet 1.6). */
export function VehicleMaintenanceTab({ vehicle }: { vehicle: Vehicle }) {
  const navigate = useNavigate();
  const has = useAuthStore((s) => s.hasPermission);
  const schedules = useVehicleSchedules(vehicle.id);
  const workOrders = useWorkOrders({ page: 1, pageSize: 20, vehicleId: vehicle.id });
  const downtime = useDowntime({ page: 1, pageSize: 10, vehicleId: vehicle.id });
  const generate = useGenerateOne();
  const update = useUpdateSchedule();
  const remove = useDeleteSchedule();
  const [adding, setAdding] = useState(false);
  const [newWo, setNewWo] = useState(false);
  const [breakdown, setBreakdown] = useState(false);
  const live = vehicle.status !== 'disposed';
  const canSchedule = has(PERMISSIONS.fleetMaintenance.schedule);

  const act = async (fn: () => Promise<unknown>) => {
    try { await fn(); } catch (err) { notify.error(apiMessage(err, tx('The action failed'))); }
  };

  const scheduleColumns: Column<ServiceSchedule>[] = [
    { key: 'name', header: tx('Service'), render: (s) => <span className="font-medium">{s.name}</span> },
    { key: 'every', header: tx('Every'), render: (s) => intervalText(s) },
    { key: 'last', header: tx('Last service'), render: (s) => `${date(s.lastDoneOn)}${s.lastDoneKm ? ` · ${num(s.lastDoneKm, 0)} km` : ''}${s.lastDoneHours ? ` · ${num(s.lastDoneHours, 1)} h` : ''}` },
    { key: 'remaining', header: tx('Remaining'), render: (s) => <span className="tabular-nums">{dueText(s.due)}</span> },
    { key: 'state', header: tx('Status'), render: (s) => (s.status === 'paused' ? tx('Paused') : <DueStatePill state={s.dueState} />) },
    {
      key: 'actions', header: '', className: 'text-right',
      render: (s) => (
        <div className="flex justify-end gap-1">
          {has(PERMISSIONS.fleetMaintenance.create) && live && (
            <Button size="sm" variant="outline" onClick={() => act(async () => { const wo = await generate.mutateAsync(s.id); navigate(`/fleet/maintenance/work-orders/${wo.id}`); })}>{tx('Raise work order')}</Button>
          )}
          {canSchedule && (
            <>
              <Button size="sm" variant="ghost" onClick={() => act(() => update.mutateAsync({ id: s.id, body: { status: s.status === 'paused' ? 'active' : 'paused' } }))}>{s.status === 'paused' ? tx('Resume') : tx('Pause')}</Button>
              <Button size="sm" variant="ghost" onClick={() => window.confirm(tx('Delete this schedule?')) && act(() => remove.mutateAsync(s.id))}>{tx('Delete')}</Button>
            </>
          )}
        </div>
      ),
    },
  ];
  const woColumns: Column<WorkOrder>[] = [
    { key: 'code', header: tx('Number'), render: (w) => <span className="font-medium text-primary">{w.code}</span> },
    { key: 'title', header: tx('Title'), render: (w) => w.title },
    { key: 'type', header: tx('Type'), render: (w) => WO_TYPE_LABEL[w.type] },
    { key: 'reported', header: tx('Reported'), render: (w) => date(w.reportedAt) },
    { key: 'cost', header: tx('Cost'), className: 'text-right', render: (w) => <span className="tabular-nums">{money(w.totalCost)}</span> },
    { key: 'status', header: tx('Status'), render: (w) => <WorkOrderStatusPill status={w.status} /> },
  ];

  return (
    <div className="space-y-4">
      {live && (
        <div className="flex flex-wrap gap-2">
          {has(PERMISSIONS.fleetMaintenance.create) && <Button onClick={() => setNewWo(true)}><Plus className="mr-1.5 h-4 w-4" />{tx('New work order')}</Button>}
          {has(PERMISSIONS.fleetMaintenance.create) && <Button variant="outline" onClick={() => setBreakdown(true)}>{tx('Report breakdown')}</Button>}
          {canSchedule && <Button variant="outline" onClick={() => setAdding(true)}>{tx('Add service schedule')}</Button>}
        </div>
      )}
      <Card>
        <CardHeader><CardTitle className="text-base">{tx('Service schedules')}</CardTitle></CardHeader>
        <CardContent>
          <DataTable columns={scheduleColumns} data={schedules.data ?? []} loading={schedules.isLoading} getRowId={(s) => s.id}
            emptyMessage={schedules.isError ? tx('Could not load the schedules.') : tx('No service schedule yet. Apply a template or add one.')} />
        </CardContent>
      </Card>
      <Card>
        <CardHeader><CardTitle className="text-base">{tx('Work orders')}</CardTitle></CardHeader>
        <CardContent>
          <DataTable columns={woColumns} data={workOrders.data?.data ?? []} loading={workOrders.isLoading} getRowId={(w) => w.id}
            onRowClick={(w) => navigate(`/fleet/maintenance/work-orders/${w.id}`)} emptyMessage={tx('No work orders yet.')} />
        </CardContent>
      </Card>
      <Card>
        <CardHeader><CardTitle className="text-base">{tx('Downtime')}</CardTitle></CardHeader>
        <CardContent className="space-y-1 text-sm">
          {(downtime.data?.data ?? []).map((d) => (
            <div key={d.id} className="flex flex-wrap justify-between gap-2 border-b py-1.5 last:border-0">
              <span>{REASON_LABEL[d.reasonCode] ?? d.reasonCode}{d.description ? ` · ${d.description}` : ''}</span>
              <span className="text-muted-foreground">{dateTime(d.startedAt)} → {d.endedAt ? dateTime(d.endedAt) : tx('still down')} · {num(d.durationHours, 1)} h</span>
            </div>
          ))}
          {(downtime.data?.data ?? []).length === 0 && <p className="text-muted-foreground">{tx('No downtime recorded.')}</p>}
        </CardContent>
      </Card>
      {adding && <ScheduleDialog vehicleId={vehicle.id} categoryId={vehicle.categoryId} onClose={() => setAdding(false)} />}
      <NewWorkOrderDialog open={newWo} onOpenChange={setNewWo} vehicleId={vehicle.id} onCreated={(id) => navigate(`/fleet/maintenance/work-orders/${id}`)} />
      <DowntimeDialog open={breakdown} onOpenChange={setBreakdown} vehicleId={vehicle.id} />
    </div>
  );
}

function ScheduleDialog({ vehicleId, categoryId, onClose }: { vehicleId: string; categoryId: string; onClose: () => void }) {
  const templates = useServiceTemplates();
  const create = useCreateSchedule();
  const [templateId, setTemplateId] = useState('');
  const [name, setName] = useState('');
  const [km, setKm] = useState('');
  const [hours, setHours] = useState('');
  const [days, setDays] = useState('');
  const options = (templates.data ?? []).filter((t) => !t.categoryId || t.categoryId === categoryId).map((t) => ({ value: t.id, label: t.name }));
  const custom = !templateId;
  const submit = async () => {
    try {
      await create.mutateAsync({ vehicleId, body: custom
        ? { name: name.trim(), intervalKm: toNumber(km), intervalHours: toNumber(hours), intervalDays: toNumber(days) }
        : { templateId } });
      notify.success(tx('Schedule added'));
      onClose();
    } catch (err) {
      notify.error(apiMessage(err, tx('Could not add the schedule')));
    }
  };
  return (
    <Dialog open onOpenChange={(o) => !o && onClose()}>
      <DialogContent>
        <DialogHeader><DialogTitle>{tx('Add service schedule')}</DialogTitle></DialogHeader>
        <div className="grid grid-cols-1 gap-4 sm:grid-cols-3">
          <Field label={tx('Template')} className="sm:col-span-3">
            <OptionalSelect value={templateId} onChange={setTemplateId} options={options} noneLabel={tx('Custom schedule')} placeholder={tx('Custom schedule')} />
          </Field>
          {custom && (
            <>
              <Field label={tx('Name *')} className="sm:col-span-3"><Input value={name} onChange={(e) => setName(e.target.value)} maxLength={120} /></Field>
              <Field label={tx('Every (km)')}><Input type="number" min="0" value={km} onChange={(e) => setKm(e.target.value)} /></Field>
              <Field label={tx('Every (engine hours)')}><Input type="number" min="0" value={hours} onChange={(e) => setHours(e.target.value)} /></Field>
              <Field label={tx('Every (days)')}><Input type="number" min="0" value={days} onChange={(e) => setDays(e.target.value)} /></Field>
            </>
          )}
        </div>
        <p className="text-xs text-muted-foreground">{tx('The first service is counted from today and the current meter readings.')}</p>
        <DialogFooter>
          <Button variant="outline" onClick={onClose}>{tx('Cancel')}</Button>
          <Button onClick={submit} disabled={create.isPending || (custom && (!name.trim() || !(km || hours || days)))}>{tx('Add')}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

/** Vehicle detail → Inspections: dispatch eligibility, inspections and defects (fleet 1.6). */
export function VehicleInspectionsTab({ vehicle }: { vehicle: Vehicle }) {
  const navigate = useNavigate();
  const has = useAuthStore((s) => s.hasPermission);
  const eligibility = useEligibility(vehicle.id);
  const inspections = useInspections({ page: 1, pageSize: 20, vehicleId: vehicle.id });
  const defects = useDefects({ page: 1, pageSize: 50, vehicleId: vehicle.id, status: 'open' });
  const [reporting, setReporting] = useState(false);
  const e = eligibility.data;
  return (
    <div className="space-y-4">
      {e && (
        <div className={e.eligible
          ? 'flex items-start gap-2 rounded-lg border border-emerald-300 bg-emerald-50 px-4 py-3 text-sm text-emerald-900 dark:border-emerald-500/40 dark:bg-emerald-500/10 dark:text-emerald-200'
          : 'flex items-start gap-2 rounded-lg border border-rose-300 bg-rose-50 px-4 py-3 text-sm text-rose-900 dark:border-rose-500/40 dark:bg-rose-500/10 dark:text-rose-200'}>
          {e.eligible ? <CheckCircle2 className="mt-0.5 h-4 w-4 shrink-0" /> : <ShieldAlert className="mt-0.5 h-4 w-4 shrink-0" />}
          <div>
            <div className="font-medium">{e.eligible ? tx('Can be dispatched') : tx('Blocked from dispatch')}</div>
            {e.reasons.map((r) => <div key={r}>{r}</div>)}
            {e.warnings.map((w) => <div key={w} className="opacity-80">{w}</div>)}
          </div>
        </div>
      )}
      {vehicle.status !== 'disposed' && (
        <div className="flex flex-wrap gap-2">
          {has(PERMISSIONS.fleetMaintenance.inspect) && <Button onClick={() => navigate(`/fleet/inspections/new?vehicleId=${vehicle.id}`)}><Plus className="mr-1.5 h-4 w-4" />{tx('New inspection')}</Button>}
          {has(PERMISSIONS.fleetMaintenance.inspect) && <Button variant="outline" onClick={() => setReporting(true)}>{tx('Report defect')}</Button>}
        </div>
      )}
      <Card>
        <CardHeader><CardTitle className="text-base">{tx('Open defects')}</CardTitle></CardHeader>
        <CardContent>
          <DefectsTable defects={defects.data?.data ?? []} loading={defects.isLoading} withVehicle={false} emptyMessage={tx('No open defects.')} />
        </CardContent>
      </Card>
      <Card>
        <CardHeader><CardTitle className="text-base">{tx('Inspections')}</CardTitle></CardHeader>
        <CardContent>
          <DataTable columns={inspectionColumns(false)} data={inspections.data?.data ?? []} loading={inspections.isLoading} getRowId={(i) => i.id}
            onRowClick={(i) => navigate(`/fleet/inspections/${i.id}`)} emptyMessage={tx('No inspections recorded yet.')} />
        </CardContent>
      </Card>
      <ReportDefectDialog open={reporting} onOpenChange={setReporting} vehicleId={vehicle.id} />
    </div>
  );
}

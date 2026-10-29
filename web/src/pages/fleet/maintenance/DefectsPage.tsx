import { useState } from 'react';
import { Link } from 'react-router-dom';
import { AlertTriangle, Plus } from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Textarea } from '@/components/ui/textarea';
import { Dialog, DialogContent, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { DataTable, type Column } from '@/components/data-table';
import { DataTablePagination, FilterSelect, ListCard, ListPageHeader, ListToolbar, useListState } from '@/components/list';
import { useAuthStore } from '@/stores/auth.store';
import { notify, apiMessage } from '@/lib/notify';
import { dateTime } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';
import {
  DEFECT_STATUS_LABEL, SEVERITY_LABEL, useCloseDefect, useDefects, useDefectWorkOrder, useReportDefect, type Defect,
  type Severity,
} from '@/features/fleet/maintenance-api';
import { Field, OptionalSelect } from '../fleet-ui';
import { toOptions } from '../fleet-format';
import { DefectStatusPill, SeverityPill, unitLabel, useUnitOptions } from './maint-ui';

/** Defect table with actions; used by the defects page and the vehicle's Inspections tab. */
export function DefectsTable({ defects, loading, emptyMessage, withVehicle }: {
  defects: Defect[]; loading?: boolean; emptyMessage: string; withVehicle: boolean;
}) {
  const has = useAuthStore((s) => s.hasPermission);
  const toWo = useDefectWorkOrder();
  const close = useCloseDefect();
  const [closing, setClosing] = useState<Defect | null>(null);
  const raise = async (d: Defect) => {
    try {
      await toWo.mutateAsync({ id: d.id });
      notify.success(tx('Work order raised'));
    } catch (err) {
      notify.error(apiMessage(err, tx('Could not raise the work order')));
    }
  };
  const columns: Column<Defect>[] = [
    { key: 'code', header: tx('Number'), render: (d) => <span className="font-medium">{d.code}</span> },
    ...(withVehicle ? [{ key: 'unit', header: tx('Vehicle / equipment'), render: (d: Defect) => <Link className="text-primary hover:underline" to={`/fleet/vehicles/${d.vehicleId}`}>{unitLabel(d.vehicle)}</Link> }] : []),
    { key: 'title', header: tx('Defect'), render: (d) => <div className="min-w-[10rem]">{d.title}{d.description && <div className="text-xs text-muted-foreground">{d.description}</div>}</div> },
    { key: 'severity', header: tx('Severity'), render: (d) => <SeverityPill severity={d.severity} /> },
    { key: 'status', header: tx('Status'), render: (d) => <DefectStatusPill status={d.status} /> },
    { key: 'reported', header: tx('Reported'), render: (d) => <span className="whitespace-nowrap">{dateTime(d.reportedAt)}</span> },
    { key: 'wo', header: tx('Work order'), render: (d) => (d.workOrder ? <Link className="text-primary hover:underline" to={`/fleet/maintenance/work-orders/${d.workOrder.id}`}>{d.workOrder.code}</Link> : '—') },
    {
      key: 'actions', header: '', className: 'text-right',
      render: (d) => (
        <div className="flex justify-end gap-1">
          {d.status === 'open' && has(PERMISSIONS.fleetMaintenance.create) && <Button size="sm" variant="outline" onClick={() => raise(d)} disabled={toWo.isPending}>{tx('Raise work order')}</Button>}
          {['open', 'in_repair'].includes(d.status) && has(PERMISSIONS.fleetMaintenance.inspect) && <Button size="sm" variant="ghost" onClick={() => setClosing(d)}>{tx('Close')}</Button>}
        </div>
      ),
    },
  ];
  return (
    <>
      <DataTable columns={columns} data={defects} loading={loading} getRowId={(d) => d.id} emptyMessage={emptyMessage} />
      {closing && <CloseDialog defect={closing} onClose={() => setClosing(null)} close={close} />}
    </>
  );
}

function CloseDialog({ defect, onClose, close }: { defect: Defect; onClose: () => void; close: ReturnType<typeof useCloseDefect> }) {
  const [outcome, setOutcome] = useState<'resolved' | 'dismissed'>('resolved');
  const [resolution, setResolution] = useState('');
  const submit = async () => {
    try {
      await close.mutateAsync({ id: defect.id, body: { outcome, resolution: resolution.trim() } });
      notify.success(tx('Defect closed'));
      onClose();
    } catch (err) {
      notify.error(apiMessage(err, tx('Could not close the defect')));
    }
  };
  return (
    <Dialog open onOpenChange={(o) => !o && onClose()}>
      <DialogContent>
        <DialogHeader><DialogTitle>{tx('Close defect {{code}}', { code: defect.code })}</DialogTitle></DialogHeader>
        <Field label={tx('Outcome')}>
          <OptionalSelect value={outcome} onChange={(v) => setOutcome((v || 'resolved') as 'resolved' | 'dismissed')}
            options={[{ value: 'resolved', label: tx('Fixed on the spot') }, { value: 'dismissed', label: tx('Not a defect') }]} placeholder={tx('Outcome')} />
        </Field>
        <Field label={tx('What was done *')}><Textarea rows={3} value={resolution} onChange={(e) => setResolution(e.target.value)} /></Field>
        <DialogFooter>
          <Button variant="outline" onClick={onClose}>{tx('Cancel')}</Button>
          <Button onClick={submit} disabled={!resolution.trim() || close.isPending}>{tx('Close defect')}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

/** Report a defect directly (driver / mechanic). */
export function ReportDefectDialog({ open, onOpenChange, vehicleId }: { open: boolean; onOpenChange: (o: boolean) => void; vehicleId?: string }) {
  const units = useUnitOptions();
  const report = useReportDefect();
  const [unit, setUnit] = useState('');
  const [title, setTitle] = useState('');
  const [description, setDescription] = useState('');
  const [severity, setSeverity] = useState<Severity>('minor');
  const unitId = vehicleId ?? unit;
  const submit = async () => {
    try {
      await report.mutateAsync({ vehicleId: unitId, title: title.trim(), description: description.trim() || undefined, severity });
      notify.success(severity === 'critical' ? tx('Critical defect reported: the vehicle is blocked from dispatch') : tx('Defect reported'));
      setTitle(''); setDescription('');
      onOpenChange(false);
    } catch (err) {
      notify.error(apiMessage(err, tx('Could not report the defect')));
    }
  };
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent>
        <DialogHeader><DialogTitle>{tx('Report defect')}</DialogTitle></DialogHeader>
        <div className="space-y-4">
          {!vehicleId && <Field label={tx('Vehicle / equipment *')}><OptionalSelect value={unit} onChange={setUnit} options={units} placeholder={tx('Choose a vehicle')} /></Field>}
          <Field label={tx('Defect *')}><Input value={title} onChange={(e) => setTitle(e.target.value)} maxLength={200} /></Field>
          <Field label={tx('Severity')}><OptionalSelect value={severity} onChange={(v) => setSeverity((v || 'minor') as Severity)} options={toOptions(SEVERITY_LABEL)} placeholder={tx('Severity')} /></Field>
          <Field label={tx('Description')}><Textarea rows={3} value={description} onChange={(e) => setDescription(e.target.value)} /></Field>
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={() => onOpenChange(false)}>{tx('Cancel')}</Button>
          <Button onClick={submit} disabled={!unitId || !title.trim() || report.isPending}>{tx('Report')}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

/** Defects across the fleet: open first by severity; critical ones block dispatch (fleet 1.6). */
export function DefectsPage() {
  const canReport = useAuthStore((s) => s.hasPermission(PERMISSIONS.fleetMaintenance.inspect));
  const list = useListState({ status: '', severity: '' });
  const { status, severity } = list.filters;
  const { data, isLoading, isError } = useDefects({ page: list.page, pageSize: list.pageSize, status: status || 'open', severity: severity || undefined });
  const [reporting, setReporting] = useState(false);
  return (
    <div className="space-y-4">
      <ListPageHeader icon={AlertTriangle} title={tx('Defects')}
        description={tx('Faults found on inspections or reported by drivers. A critical defect blocks the vehicle from dispatch.')}
        actions={canReport && <Button onClick={() => setReporting(true)}><Plus className="mr-1.5 h-4 w-4" /> {tx('Report defect')}</Button>} />
      <ListToolbar>
        <FilterSelect value={status} onChange={(v) => list.setFilter('status', v)}
          options={[...toOptions(DEFECT_STATUS_LABEL).filter((o) => o.value !== 'open'), { value: 'all', label: tx('All defects') }]} allLabel={tx('Open and in repair')} />
        <FilterSelect value={severity} onChange={(v) => list.setFilter('severity', v)} options={toOptions(SEVERITY_LABEL)} allLabel={tx('Any severity')} />
      </ListToolbar>
      <ListCard>
        <DefectsTable defects={data?.data ?? []} loading={isLoading} withVehicle
          emptyMessage={isError ? tx('Could not load the defects.') : tx('No open defects.')} />
        {data?.meta && (
          <DataTablePagination page={data.meta.page} pageSize={list.pageSize} total={data.meta.total}
            totalPages={data.meta.totalPages} onPageChange={list.setPage} onPageSizeChange={list.setPageSize} noun="defect" />
        )}
      </ListCard>
      <ReportDefectDialog open={reporting} onOpenChange={setReporting} />
    </div>
  );
}

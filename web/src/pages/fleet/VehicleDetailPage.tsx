import { useState, type ReactNode } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { ArrowLeft, Edit, RefreshCw, Trash2 } from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs';
import { Skeleton } from '@/components/ui/skeleton';
import { Textarea } from '@/components/ui/textarea';
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import {
  AlertDialog, AlertDialogAction, AlertDialogCancel, AlertDialogContent, AlertDialogDescription, AlertDialogFooter,
  AlertDialogHeader, AlertDialogTitle,
} from '@/components/ui/alert-dialog';
import { DataTable, type Column } from '@/components/data-table';
import { useAuthStore } from '@/stores/auth.store';
import { notify, apiMessage } from '@/lib/notify';
import { date, dateTime, money } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';
import {
  FUEL_LABEL, METER_TYPE_LABEL, OWNERSHIP_LABEL, STATUS_LABEL, useChangeStatus, useDeleteVehicle, useLinkedAsset,
  useStatusHistory, useVehicle, type LinkedAsset, type StatusHistoryRow, type Vehicle, type VehicleStatus,
} from '@/features/fleet/api';
import { Field, OptionalSelect, VehicleStatusPill } from './fleet-ui';
import { num } from './fleet-format';
import { VehicleMetersTab } from './VehicleMetersTab';
import { VehicleComplianceTab } from './VehicleComplianceTab';
import { VehicleFilesTab } from './VehicleFilesTab';
import { VehicleDriversTab } from './VehicleDriversTab';

/** Vehicle detail (fleet 1.1): overview, meters + corrections, compliance, status history, linked asset, files. */
export function VehicleDetailPage() {
  const { id = '' } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const { data: v, isLoading, isError, refetch } = useVehicle(id);
  const has = useAuthStore((s) => s.hasPermission);
  const [tab, setTab] = useState('overview');
  const [statusOpen, setStatusOpen] = useState(false);
  const [deleting, setDeleting] = useState(false);
  const remove = useDeleteVehicle();

  if (isLoading) return <div className="space-y-3"><Skeleton className="h-12 w-1/2" /><Skeleton className="h-64 w-full" /></div>;
  if (isError || !v) {
    return (
      <div className="p-8 text-center text-muted-foreground">
        <p>{tx('Vehicle not found')}</p>
        <Button variant="link" onClick={() => refetch()}><RefreshCw className="mr-1 h-4 w-4" /> {tx('Retry')}</Button>
      </div>
    );
  }
  const disposed = v.status === 'disposed';
  const title = v.name ?? ([v.make, v.model].filter(Boolean).join(' ') || v.code);

  const confirmDelete = async () => {
    try {
      await remove.mutateAsync(v.id);
      notify.success(tx('Vehicle deleted'));
      navigate('/fleet/vehicles');
    } catch (err) {
      notify.error(apiMessage(err, tx('Could not delete the vehicle')));
      setDeleting(false);
    }
  };

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-start gap-3">
        <Button variant="ghost" size="icon" aria-label={tx('Back')} onClick={() => navigate('/fleet/vehicles')}><ArrowLeft className="h-5 w-5" /></Button>
        <div className="min-w-0 flex-1">
          <div className="flex flex-wrap items-center gap-2">
            <h1 className="truncate text-2xl font-bold">{title}</h1>
            <VehicleStatusPill status={v.status} />
          </div>
          <p className="text-sm text-muted-foreground">
            {[v.code, v.registrationPlate, v.category?.name].filter(Boolean).join(' · ')}
          </p>
        </div>
        <div className="flex flex-wrap gap-2">
          {has(PERMISSIONS.fleetVehicle.status) && !disposed && (
            <Button variant="outline" onClick={() => setStatusOpen(true)}>{tx('Change status')}</Button>
          )}
          {has(PERMISSIONS.fleetVehicle.update) && !disposed && (
            <Button variant="outline" onClick={() => navigate(`/fleet/vehicles/${v.id}/edit`)}><Edit className="mr-1.5 h-4 w-4" /> {tx('Edit')}</Button>
          )}
          {has(PERMISSIONS.fleetVehicle.delete) && (
            <Button variant="ghost" size="icon" aria-label={tx('Delete')} onClick={() => setDeleting(true)}><Trash2 className="h-4 w-4" /></Button>
          )}
        </div>
      </div>

      {(v.compliance?.expired ?? 0) + (v.compliance?.expiring ?? 0) > 0 && (
        <button type="button" onClick={() => setTab('compliance')}
          className="w-full rounded-lg border border-amber-300 bg-amber-50 px-4 py-2 text-left text-sm text-amber-900 dark:border-amber-500/40 dark:bg-amber-500/10 dark:text-amber-200">
          {tx('Compliance needs attention: {{expired}} expired, {{expiring}} expiring soon.', { expired: v.compliance?.expired ?? 0, expiring: v.compliance?.expiring ?? 0 })}
        </button>
      )}

      <Tabs value={tab} onValueChange={setTab}>
        <TabsList className="flex h-auto flex-wrap justify-start">
          <TabsTrigger value="overview">{tx('Overview')}</TabsTrigger>
          <TabsTrigger value="meters">{tx('Meters')}</TabsTrigger>
          <TabsTrigger value="compliance">{tx('Compliance')}</TabsTrigger>
          <TabsTrigger value="history">{tx('Status history')}</TabsTrigger>
          <TabsTrigger value="asset">{tx('Fixed asset')}</TabsTrigger>
          <TabsTrigger value="files">{tx('Photos & documents')}</TabsTrigger>
          {has(PERMISSIONS.fleetDriver.read) && <TabsTrigger value="drivers">{tx('Driver assignments / check-outs')}</TabsTrigger>}
        </TabsList>
        <TabsContent value="overview"><OverviewTab v={v} /></TabsContent>
        <TabsContent value="meters"><VehicleMetersTab vehicle={v} /></TabsContent>
        <TabsContent value="compliance"><VehicleComplianceTab vehicle={v} /></TabsContent>
        <TabsContent value="history"><HistoryTab vehicleId={v.id} /></TabsContent>
        <TabsContent value="asset"><AssetTab vehicle={v} active={tab === 'asset'} /></TabsContent>
        <TabsContent value="files"><VehicleFilesTab vehicle={v} /></TabsContent>
        <TabsContent value="drivers">{tab === 'drivers' && <VehicleDriversTab vehicle={v} />}</TabsContent>
      </Tabs>

      <StatusDialog vehicle={v} open={statusOpen} onOpenChange={setStatusOpen} />

      <AlertDialog open={deleting} onOpenChange={setDeleting}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>{tx('Delete vehicle {{code}}?', { code: v.code })}</AlertDialogTitle>
            <AlertDialogDescription>{tx('Only a vehicle without operating history can be deleted; otherwise mark it disposed.')}</AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>{tx('Cancel')}</AlertDialogCancel>
            <AlertDialogAction onClick={confirmDelete}>{tx('Delete')}</AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </div>
  );
}

function Item({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="space-y-0.5">
      <dt className="text-xs text-muted-foreground">{label}</dt>
      <dd className="text-sm font-medium">{children ?? '—'}</dd>
    </div>
  );
}

function OverviewTab({ v }: { v: Vehicle }) {
  const odo = v.meters.odometer;
  const hours = v.meters.hours;
  return (
    <div className="grid grid-cols-1 gap-4 lg:grid-cols-3">
      <Card className="lg:col-span-2">
        <CardHeader><CardTitle className="text-base">{tx('Vehicle details')}</CardTitle></CardHeader>
        <CardContent>
          <dl className="grid grid-cols-2 gap-4 sm:grid-cols-3">
            <Item label={tx('Code')}>{v.code}</Item>
            <Item label={tx('Registration plate')}>{v.registrationPlate}</Item>
            <Item label={tx('VIN / chassis number')}>{v.vin}</Item>
            <Item label={tx('Engine number')}>{v.engineNumber}</Item>
            <Item label={tx('Make / model')}>{[v.make, v.model].filter(Boolean).join(' ') || null}</Item>
            <Item label={tx('Year')}>{v.year}</Item>
            <Item label={tx('Class')}>{v.category?.name}</Item>
            <Item label={tx('Ownership')}>{OWNERSHIP_LABEL[v.ownership]}{v.ownerPartner ? ` · ${v.ownerPartner.name}` : ''}</Item>
            <Item label={tx('Payload capacity (t)')}>{num(v.payloadCapacityT, 3)}</Item>
            <Item label={tx('Body volume (m³)')}>{num(v.bodyVolumeM3, 3)}</Item>
            <Item label={tx('Axle configuration')}>{v.axleConfig}</Item>
            <Item label={tx('Fuel type')}>{FUEL_LABEL[v.fuelType] ?? v.fuelType}</Item>
            <Item label={tx('Tank size (L)')}>{num(v.tankCapacityL, 2)}</Item>
            <Item label={tx('Home branch')}>{v.branch?.name}</Item>
            <Item label={tx('Home site / yard')}>{v.homeLocation?.name}</Item>
            <Item label={tx('Cost centre')}>{v.costCenter ? `${v.costCenter.code ?? ''} ${v.costCenter.name}`.trim() : null}</Item>
            <Item label={tx('Acquired on')}>{v.acquiredOn ? date(v.acquiredOn) : null}</Item>
            <Item label={tx('Status since')}>{v.statusChangedAt ? dateTime(v.statusChangedAt) : null}</Item>
          </dl>
          {v.statusReason && <p className="mt-4 text-sm text-muted-foreground">{tx('Status reason')}: {v.statusReason}</p>}
          {v.notes && <p className="mt-2 whitespace-pre-wrap text-sm">{v.notes}</p>}
        </CardContent>
      </Card>
      <div className="space-y-4">
        <Card>
          <CardHeader><CardTitle className="text-base">{tx('Meters')}</CardTitle></CardHeader>
          <CardContent className="space-y-3 text-sm">
            <p className="text-muted-foreground">{METER_TYPE_LABEL[v.meterType]}</p>
            {odo && (
              <div>
                <div className="text-2xl font-semibold tabular-nums">{num(odo.value, 1)} km</div>
                <div className="text-xs text-muted-foreground">{tx('Lifetime')}: {num(odo.lifetime, 1)} km · {odo.at ? dateTime(odo.at) : '—'}</div>
              </div>
            )}
            {hours && (
              <div>
                <div className="text-2xl font-semibold tabular-nums">{num(hours.value, 1)} h</div>
                <div className="text-xs text-muted-foreground">{tx('Lifetime')}: {num(hours.lifetime, 1)} h · {hours.at ? dateTime(hours.at) : '—'}</div>
              </div>
            )}
            {!odo && !hours && <p className="text-muted-foreground">{tx('No readings yet.')}</p>}
            {(v.pendingCorrections ?? 0) > 0 && <p className="text-amber-700">{tx('A meter correction is waiting for approval.')}</p>}
          </CardContent>
        </Card>
        <Card>
          <CardHeader><CardTitle className="text-base">{tx('Compliance')}</CardTitle></CardHeader>
          <CardContent className="grid grid-cols-3 gap-2 text-center text-sm">
            <div><div className="text-xl font-semibold text-rose-600">{v.compliance?.expired ?? 0}</div><div className="text-xs text-muted-foreground">{tx('Expired')}</div></div>
            <div><div className="text-xl font-semibold text-amber-600">{v.compliance?.expiring ?? 0}</div><div className="text-xs text-muted-foreground">{tx('Expiring soon')}</div></div>
            <div><div className="text-xl font-semibold">{v.compliance?.total ?? 0}</div><div className="text-xs text-muted-foreground">{tx('Documents')}</div></div>
          </CardContent>
        </Card>
      </div>
    </div>
  );
}

function HistoryTab({ vehicleId }: { vehicleId: string }) {
  const { data, isLoading, isError } = useStatusHistory(vehicleId);
  const columns: Column<StatusHistoryRow>[] = [
    { key: 'changedAt', header: tx('When'), render: (r) => <span className="whitespace-nowrap">{dateTime(r.changedAt)}</span> },
    { key: 'from', header: tx('From'), render: (r) => (r.fromStatus ? STATUS_LABEL[r.fromStatus as VehicleStatus] ?? r.fromStatus : '—') },
    { key: 'to', header: tx('To'), render: (r) => <VehicleStatusPill status={r.toStatus} /> },
    { key: 'reason', header: tx('Reason'), render: (r) => r.reason ?? '—' },
    { key: 'source', header: tx('Source'), render: (r) => r.source.replace(/_/g, ' ') },
    { key: 'meters', header: tx('Meters'), render: (r) => [r.odometer ? `${num(r.odometer, 0)} km` : null, r.hours ? `${num(r.hours)} h` : null].filter(Boolean).join(' · ') || '—' },
    { key: 'by', header: tx('By'), render: (r) => [r.changedByFirstName, r.changedByLastName].filter(Boolean).join(' ') || '—' },
  ];
  return (
    <Card>
      <CardContent className="pt-4">
        <DataTable columns={columns} data={data ?? []} loading={isLoading} getRowId={(r) => r.id}
          emptyMessage={isError ? tx('Could not load the history.') : tx('No status changes yet.')} />
      </CardContent>
    </Card>
  );
}

const depreciationColumns: Column<LinkedAsset['depreciation'][number]>[] = [
  { key: 'period', header: tx('Period') },
  { key: 'amount', header: tx('Depreciation'), className: 'text-right', render: (r) => money(r.depreciationAmount) },
  { key: 'acc', header: tx('Accumulated'), className: 'text-right', render: (r) => money(r.accumulatedDepr) },
  { key: 'book', header: tx('Book value'), className: 'text-right', render: (r) => money(r.bookValue) },
  { key: 'posted', header: tx('Posted'), render: (r) => (r.isPosted ? tx('Yes') : tx('No')) },
];

function AssetTab({ vehicle, active }: { vehicle: Vehicle; active: boolean }) {
  const { data, isLoading } = useLinkedAsset(vehicle.id, active);
  if (!vehicle.assetId) {
    return (
      <Card><CardContent className="py-8 text-center text-sm text-muted-foreground">
        {tx('Not linked to a fixed asset. Link one from Edit to see its depreciation here.')}
      </CardContent></Card>
    );
  }
  if (isLoading || !data) return <Skeleton className="h-40 w-full" />;
  const a = data.asset;
  return (
    <div className="space-y-4">
      <Card>
        <CardHeader><CardTitle className="text-base">{tx('Fixed asset')} — {a ? `${a.assetCode} · ${a.name}` : '—'}</CardTitle></CardHeader>
        <CardContent>
          <dl className="grid grid-cols-2 gap-4 sm:grid-cols-4">
            <Item label={tx('Category')}>{a?.categoryName}</Item>
            <Item label={tx('Purchase cost')}>{a?.purchaseCost ? money(a.purchaseCost) : null}</Item>
            <Item label={tx('Accumulated depreciation')}>{data.accumulatedDepreciation ? money(data.accumulatedDepreciation) : null}</Item>
            <Item label={tx('Book value')}>{data.bookValue ? money(data.bookValue) : null}</Item>
          </dl>
          {a && <Link to={`/fixed-assets/${a.id}`} className="mt-4 inline-block text-sm text-primary hover:underline">{tx('Open in Fixed Assets')}</Link>}
        </CardContent>
      </Card>
      <Card>
        <CardHeader><CardTitle className="text-base">{tx('Depreciation')}</CardTitle></CardHeader>
        <CardContent>
          <DataTable
            columns={depreciationColumns}
            data={data.depreciation}
            getRowId={(r) => r.id}
            emptyMessage={tx('No depreciation recorded yet.')}
          />
        </CardContent>
      </Card>
    </div>
  );
}

function StatusDialog({ vehicle, open, onOpenChange }: { vehicle: Vehicle; open: boolean; onOpenChange: (o: boolean) => void }) {
  const [status, setStatus] = useState<VehicleStatus | ''>('');
  const [reason, setReason] = useState('');
  const change = useChangeStatus();
  const needsReason = status === 'breakdown' || status === 'disposed';
  const options = (Object.keys(STATUS_LABEL) as VehicleStatus[]).filter((s) => s !== vehicle.status)
    .map((s) => ({ value: s, label: STATUS_LABEL[s] }));

  const submit = async () => {
    if (!status) return;
    try {
      await change.mutateAsync({ id: vehicle.id, status, reason: reason.trim() || undefined });
      notify.success(tx('Status changed to {{status}}', { status: STATUS_LABEL[status] }));
      setStatus('');
      setReason('');
      onOpenChange(false);
    } catch (err) {
      notify.error(apiMessage(err, tx('Could not change the status')));
    }
  };

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>{tx('Change status of {{code}}', { code: vehicle.code })}</DialogTitle>
          <DialogDescription>{tx('Only active vehicles can be dispatched. Disposal is final.')}</DialogDescription>
        </DialogHeader>
        <div className="space-y-4">
          <Field label={tx('New status')}>
            <OptionalSelect value={status} onChange={(v) => setStatus(v as VehicleStatus | '')} placeholder={tx('Choose a status')} options={options} />
          </Field>
          <Field label={needsReason ? tx('Reason *') : tx('Reason')}>
            <Textarea rows={3} value={reason} onChange={(e) => setReason(e.target.value)} />
          </Field>
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={() => onOpenChange(false)}>{tx('Cancel')}</Button>
          <Button onClick={submit} disabled={!status || (needsReason && !reason.trim()) || change.isPending}>{tx('Change status')}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

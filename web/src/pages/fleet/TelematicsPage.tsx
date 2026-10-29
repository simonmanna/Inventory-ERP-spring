import { useState } from 'react';
import { Plus, Radio, RefreshCw } from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { SearchableSelect } from '@/components/ui/searchable-select';
import { DataTable, type Column } from '@/components/data-table';
import { ListCard, ListPageHeader, StatusPill, type Tone } from '@/components/list';
import { useAuthStore } from '@/stores/auth.store';
import { notify, apiMessage } from '@/lib/notify';
import { dateTime } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';
import { useVehicles } from '@/features/fleet/api';
import {
  useCreateDevice, useDeleteDevice, useSyncTelematics, useTelematicsDevices, useTelematicsSettings, useUpdateDevice,
  type TelematicsDevice,
} from '@/features/fleet/telematics-api';
import { Field, OptionalSelect } from './fleet-ui';

const SYNC_TONE: Record<string, Tone> = { ok: 'success', partial: 'warning', error: 'danger' };

/** Telematics settings (fleet 1.8): provider configuration, units ↔ vehicles, sync now. */
export function TelematicsPage() {
  const canConfigure = useAuthStore((s) => s.hasPermission(PERMISSIONS.fleet.settings));
  const [showInactive, setShowInactive] = useState(false);
  const settings = useTelematicsSettings(canConfigure);
  const devices = useTelematicsDevices({ includeInactive: showInactive });
  const sync = useSyncTelematics();
  const remove = useDeleteDevice();
  const update = useUpdateDevice();
  const [editing, setEditing] = useState<TelematicsDevice | 'new' | null>(null);

  const syncNow = async () => {
    try {
      const r = await sync.mutateAsync();
      notify.success(tx('Synced {{devices}} device(s): {{positions}} positions, {{events}} events, {{readings}} meter readings', {
        devices: r.devices, positions: r.positions, events: r.events, readings: r.meterReadings,
      }));
    } catch (err) {
      notify.error(apiMessage(err, tx('Could not sync')));
    }
  };

  const columns: Column<TelematicsDevice>[] = [
    { key: 'unit', header: tx('Unit'), render: (d) => <div><div className="font-medium">{d.externalUnitId}</div><div className="text-xs text-muted-foreground">{d.name ?? d.provider}</div></div> },
    { key: 'vehicle', header: tx('Vehicle'), render: (d) => (d.vehicle ? `${d.vehicle.code}${d.vehicle.registrationPlate ? ` · ${d.vehicle.registrationPlate}` : ''}` : <span className="text-muted-foreground">{tx('Not mapped')}</span>) },
    { key: 'sync', header: tx('Meters'), render: (d) => [d.syncOdometer ? tx('Odometer') : null, d.syncHours ? tx('Engine hours') : null].filter(Boolean).join(' · ') || '—' },
    { key: 'last', header: tx('Last position'), render: (d) => (d.lastPositionAt ? dateTime(d.lastPositionAt) : '—') },
    {
      key: 'status', header: tx('Last sync'),
      render: (d) => (d.lastSyncStatus ? (
        <div className="space-y-0.5">
          <StatusPill tone={SYNC_TONE[d.lastSyncStatus] ?? 'neutral'}>{d.lastSyncStatus}</StatusPill>
          {d.lastError && <div className="line-clamp-2 max-w-[18rem] text-xs text-rose-600">{d.lastError}</div>}
        </div>
      ) : '—'),
    },
    { key: 'active', header: tx('Active'), render: (d) => (d.isActive ? tx('Yes') : tx('No')) },
    {
      key: 'actions', header: '', className: 'text-right',
      render: (d) => canConfigure && (
        <div className="flex justify-end gap-1">
          <Button size="sm" variant="ghost" onClick={() => setEditing(d)}>{tx('Edit')}</Button>
          {d.isActive ? (
            <Button size="sm" variant="ghost" onClick={async () => {
              try {
                const r = await remove.mutateAsync(d.id);
                notify.success(r.deleted ? tx('Device deleted') : tx('Device deactivated (it has synced data)'));
              } catch (err) {
                notify.error(apiMessage(err, tx('Could not remove the device')));
              }
            }}>{tx('Remove')}</Button>
          ) : (
            <Button size="sm" variant="ghost" onClick={() => update.mutateAsync({ id: d.id, body: { isActive: true } })
              .catch((err) => notify.error(apiMessage(err, tx('Could not activate the device'))))}>{tx('Activate')}</Button>
          )}
        </div>
      ),
    },
  ];

  return (
    <div className="space-y-4">
      <ListPageHeader icon={Radio} title={tx('Telematics')}
        description={tx('GPS / telematics units mapped to vehicles. Positions, odometer / engine hours, ignition events and alerts are pulled every few minutes.')}
        actions={canConfigure && (
          <>
            <Button variant="outline" onClick={syncNow} disabled={sync.isPending}><RefreshCw className="mr-1.5 h-4 w-4" /> {tx('Sync now')}</Button>
            <Button onClick={() => setEditing('new')}><Plus className="mr-1.5 h-4 w-4" /> {tx('Add device')}</Button>
          </>
        )} />
      {settings.data && (
        <Card>
          <CardHeader><CardTitle className="text-base">{tx('Provider')}</CardTitle></CardHeader>
          <CardContent className="grid grid-cols-2 gap-4 text-sm sm:grid-cols-4">
            <div><div className="text-xs text-muted-foreground">{tx('Configured provider')}</div><div className="font-medium">{settings.data.provider}</div></div>
            <div><div className="text-xs text-muted-foreground">{tx('Available')}</div><div>{settings.data.available.join(', ')}</div></div>
            <div><div className="text-xs text-muted-foreground">{tx('Vendor URL / API key')}</div><div>{settings.data.baseUrlConfigured && settings.data.apiKeyConfigured ? tx('Configured') : tx('Not configured')}</div></div>
            <div><div className="text-xs text-muted-foreground">{tx('Positions kept')}</div><div>{tx('{{days}} days', { days: settings.data.retentionDays })}</div></div>
          </CardContent>
        </Card>
      )}
      <label className="flex items-center gap-2 text-sm">
        <input type="checkbox" checked={showInactive} onChange={(e) => setShowInactive(e.target.checked)} /> {tx('Show inactive devices')}
      </label>
      <ListCard>
        <DataTable columns={columns} data={devices.data ?? []} loading={devices.isLoading} getRowId={(d) => d.id}
          emptyMessage={devices.isError ? tx('Could not load the devices.') : tx('No telematics devices yet.')} />
      </ListCard>
      {editing && <DeviceDialog device={editing === 'new' ? undefined : editing} provider={settings.data?.provider} onClose={() => setEditing(null)} />}
    </div>
  );
}

function DeviceDialog({ device, provider, onClose }: { device?: TelematicsDevice; provider?: string; onClose: () => void }) {
  const [vehicleId, setVehicleId] = useState(device?.vehicleId ?? '');
  const [unit, setUnit] = useState(device?.externalUnitId ?? '');
  const [name, setName] = useState(device?.name ?? '');
  const [imei, setImei] = useState(device?.imei ?? '');
  const [sim, setSim] = useState(device?.simNumber ?? '');
  const [odometer, setOdometer] = useState(device?.syncOdometer ?? true);
  const [hours, setHours] = useState(device?.syncHours ?? true);
  const vehicles = useVehicles({ page: 1, pageSize: 200, sortBy: 'code' });
  const create = useCreateDevice();
  const update = useUpdateDevice();
  const valid = unit.trim().length > 0;
  const submit = async () => {
    const body: Record<string, unknown> = {
      vehicleId: vehicleId || undefined, externalUnitId: unit.trim(), name: name.trim() || undefined, imei: imei.trim() || undefined,
      simNumber: sim.trim() || undefined, syncOdometer: odometer, syncHours: hours,
    };
    try {
      if (device) {
        const clear = ['vehicleId', 'name', 'imei', 'simNumber'].filter((k) => body[k] === undefined && (device as unknown as Record<string, unknown>)[k] != null);
        await update.mutateAsync({ id: device.id, body: { ...body, clear } });
      } else {
        await create.mutateAsync(body);
      }
      notify.success(tx('Device saved'));
      onClose();
    } catch (err) {
      notify.error(apiMessage(err, tx('Could not save the device')));
    }
  };
  return (
    <Dialog open onOpenChange={(o) => !o && onClose()}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>{device ? tx('Edit device') : tx('Add device')}</DialogTitle>
          <DialogDescription>{tx('Provider')}: {device?.provider ?? provider ?? '—'}</DialogDescription>
        </DialogHeader>
        <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
          <Field label={tx('Provider unit id *')}><Input value={unit} onChange={(e) => setUnit(e.target.value)} /></Field>
          <Field label={tx('Name')}><Input value={name} onChange={(e) => setName(e.target.value)} /></Field>
          <Field label={tx('Vehicle')} className="sm:col-span-2">
            <SearchableSelect value={vehicleId} onValueChange={setVehicleId} placeholder={tx('Choose a vehicle')} searchPlaceholder={tx('Search code or plate…')}
              options={(vehicles.data?.data ?? []).map((v) => ({ value: v.id, label: v.code, hint: v.registrationPlate ?? undefined }))} />
            {vehicleId && <button type="button" className="mt-1 text-xs text-primary hover:underline" onClick={() => setVehicleId('')}>{tx('Unmap')}</button>}
          </Field>
          <Field label={tx('IMEI')}><Input value={imei} onChange={(e) => setImei(e.target.value)} /></Field>
          <Field label={tx('SIM number')}><Input value={sim} onChange={(e) => setSim(e.target.value)} /></Field>
          <Field label={tx('Sync meters')} className="sm:col-span-2">
            <OptionalSelect value={odometer && hours ? 'both' : odometer ? 'odometer' : hours ? 'hours' : 'none'} placeholder={tx('Sync meters')}
              onChange={(v) => { setOdometer(v === 'both' || v === 'odometer'); setHours(v === 'both' || v === 'hours'); }}
              options={[{ value: 'both', label: tx('Odometer and engine hours') }, { value: 'odometer', label: tx('Odometer') },
                { value: 'hours', label: tx('Engine hours') }, { value: 'none', label: tx('None') }]} />
          </Field>
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={onClose}>{tx('Cancel')}</Button>
          <Button onClick={submit} disabled={!valid || create.isPending || update.isPending}>{tx('Save')}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

import { useState } from 'react';
import { Link } from 'react-router-dom';
import { MapPin } from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs';
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { DataTable, type Column } from '@/components/data-table';
import { DataTablePagination, ListCard, ListPageHeader, StatusPill, type Tone } from '@/components/list';
import { useAuthStore } from '@/stores/auth.store';
import { notify, apiMessage } from '@/lib/notify';
import { dateTime } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';
import {
  EVENT_TYPE_LABEL, mapUrl, useAcknowledgeAlert, useLatestPositions, useTelematicsEvents, type LatestPosition,
  type TelematicsEvent,
} from '@/features/fleet/telematics-api';
import { num } from './fleet-format';
import { OptionalSelect } from './fleet-ui';

const SEVERITY_TONE: Record<TelematicsEvent['severity'], Tone> = { info: 'info', warning: 'warning', critical: 'danger' };

/** Live fleet (fleet 1.8): last known position of every tracked vehicle, and the telematics alerts to acknowledge. */
export function TelematicsLivePage() {
  const [tab, setTab] = useState('positions');
  const latest = useLatestPositions(tab === 'positions');
  const columns: Column<LatestPosition>[] = [
    { key: 'vehicle', header: tx('Vehicle'), render: (r) => <Link className="font-medium text-primary hover:underline" to={`/fleet/vehicles/${r.vehicleId}`}>{r.code}</Link> },
    { key: 'plate', header: tx('Plate'), render: (r) => r.registrationPlate ?? '—' },
    { key: 'at', header: tx('Last position'), render: (r) => (r.lastPositionAt ? dateTime(r.lastPositionAt) : '—') },
    {
      key: 'where', header: tx('Where'),
      render: (r) => (r.lastLatitude && r.lastLongitude ? (
        <a className="inline-flex items-center gap-1 text-primary hover:underline" href={mapUrl(r.lastLatitude, r.lastLongitude)} target="_blank" rel="noreferrer">
          <MapPin className="h-3.5 w-3.5" /> {Number(r.lastLatitude).toFixed(5)}, {Number(r.lastLongitude).toFixed(5)}
        </a>
      ) : '—'),
    },
    { key: 'speed', header: tx('Speed (km/h)'), className: 'text-right', render: (r) => num(r.lastSpeedKmh, 0) },
    { key: 'ignition', header: tx('Ignition'), render: (r) => (r.lastIgnition == null ? '—' : r.lastIgnition ? tx('On') : tx('Off')) },
    { key: 'odometer', header: tx('Odometer'), className: 'text-right', render: (r) => num(r.lastOdometerKm, 0) },
    { key: 'alerts', header: tx('Open alerts'), className: 'text-right', render: (r) => (r.openAlerts > 0 ? <span className="font-medium text-amber-700">{r.openAlerts}</span> : 0) },
  ];
  return (
    <div className="space-y-4">
      <ListPageHeader icon={MapPin} title={tx('Live Fleet')} description={tx('Where each tracked vehicle was last seen, and the alerts its telematics unit raised.')} />
      <Tabs value={tab} onValueChange={setTab}>
        <TabsList>
          <TabsTrigger value="positions">{tx('Last positions')}</TabsTrigger>
          <TabsTrigger value="alerts">{tx('Alerts')}</TabsTrigger>
        </TabsList>
        <TabsContent value="positions">
          <ListCard>
            <DataTable columns={columns} data={latest.data ?? []} loading={latest.isLoading} getRowId={(r) => r.deviceId}
              emptyMessage={latest.isError ? tx('Could not load the positions.') : tx('No tracked vehicles. Map telematics units to vehicles in Telematics.')} />
          </ListCard>
        </TabsContent>
        <TabsContent value="alerts"><AlertsTable active={tab === 'alerts'} /></TabsContent>
      </Tabs>
    </div>
  );
}

/** Alerts (speeding, idling, geofence, harsh driving) with acknowledgement; optionally of one vehicle. */
export function AlertsTable({ active, vehicleId }: { active: boolean; vehicleId?: string }) {
  const [filter, setFilter] = useState('open');
  const [page, setPage] = useState(1);
  const canAck = useAuthStore((s) => s.hasPermission(PERMISSIONS.fleetTrip.update));
  const events = useTelematicsEvents({ page, pageSize: 20, vehicleId, alertsOnly: filter === 'all', open: filter === 'open' }, active);
  const [ack, setAck] = useState<TelematicsEvent | null>(null);
  const columns: Column<TelematicsEvent>[] = [
    { key: 'at', header: tx('When'), render: (e) => <span className="whitespace-nowrap">{dateTime(e.occurredAt)}</span> },
    ...(vehicleId ? [] : [{ key: 'vehicle', header: tx('Vehicle'), render: (e: TelematicsEvent) => e.vehicle.code }]),
    { key: 'type', header: tx('Alert'), render: (e) => EVENT_TYPE_LABEL[e.eventType] ?? e.eventType },
    { key: 'severity', header: tx('Severity'), render: (e) => <StatusPill tone={SEVERITY_TONE[e.severity]}>{e.severity}</StatusPill> },
    { key: 'speed', header: tx('Speed (km/h)'), className: 'text-right', render: (e) => num(e.speedKmh, 0) },
    {
      key: 'where', header: tx('Where'),
      render: (e) => (e.latitude && e.longitude
        ? <a className="text-primary hover:underline" href={mapUrl(e.latitude, e.longitude)} target="_blank" rel="noreferrer">{tx('Map')}</a> : '—'),
    },
    {
      key: 'ack', header: '', className: 'text-right',
      render: (e) => (e.acknowledgedAt
        ? <span className="text-xs text-muted-foreground">{tx('Acknowledged')} {e.acknowledgedByName ?? ''}{e.acknowledgeNote ? ` · ${e.acknowledgeNote}` : ''}</span>
        : canAck && <Button size="sm" variant="outline" onClick={() => setAck(e)}>{tx('Acknowledge')}</Button>),
    },
  ];
  return (
    <div className="space-y-3">
      <div className="w-56">
        <OptionalSelect value={filter} onChange={(v) => { setFilter(v || 'open'); setPage(1); }} placeholder={tx('Open alerts')}
          options={[{ value: 'open', label: tx('Open alerts') }, { value: 'all', label: tx('All alerts') }]} />
      </div>
      <ListCard>
        <DataTable columns={columns} data={events.data?.data ?? []} loading={events.isLoading} getRowId={(e) => e.id}
          emptyMessage={events.isError ? tx('Could not load the alerts.') : tx('No alerts.')} />
        {events.data?.meta && events.data.meta.total > 0 && (
          <DataTablePagination page={events.data.meta.page} pageSize={20} total={events.data.meta.total} totalPages={events.data.meta.totalPages} onPageChange={setPage} noun="alert" />
        )}
      </ListCard>
      {ack && <AcknowledgeDialog event={ack} onClose={() => setAck(null)} />}
    </div>
  );
}

function AcknowledgeDialog({ event, onClose }: { event: TelematicsEvent; onClose: () => void }) {
  const [note, setNote] = useState('');
  const acknowledge = useAcknowledgeAlert();
  const submit = async () => {
    try {
      await acknowledge.mutateAsync({ id: event.id, note: note.trim() || undefined });
      notify.success(tx('Alert acknowledged'));
      onClose();
    } catch (err) {
      notify.error(apiMessage(err, tx('Could not acknowledge the alert')));
    }
  };
  return (
    <Dialog open onOpenChange={(o) => !o && onClose()}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>{tx('Acknowledge')} · {EVENT_TYPE_LABEL[event.eventType] ?? event.eventType}</DialogTitle>
          <DialogDescription>{event.vehicle.code} · {dateTime(event.occurredAt)}</DialogDescription>
        </DialogHeader>
        <Input placeholder={tx('Note (e.g. driver warned)')} value={note} onChange={(e) => setNote(e.target.value)} />
        <DialogFooter>
          <Button variant="outline" onClick={onClose}>{tx('Cancel')}</Button>
          <Button onClick={submit} disabled={acknowledge.isPending}>{tx('Acknowledge')}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

import { useState } from 'react';
import { Link } from 'react-router-dom';
import { MapPin } from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { DataTable, type Column } from '@/components/data-table';
import { DataTablePagination } from '@/components/list';
import { useAuthStore } from '@/stores/auth.store';
import { dateTime } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';
import type { Vehicle } from '@/features/fleet/api';
import {
  mapUrl, useLastPosition, useTelematicsDevices, useVehicleDistance, useVehiclePositions, type PositionRow,
} from '@/features/fleet/telematics-api';
import { num } from './fleet-format';
import { OptionalSelect } from './fleet-ui';
import { AlertsTable } from './TelematicsLivePage';

/** Vehicle "Telematics" tab (fleet 1.8): unit, last position, distance driven, recent positions and alerts. */
export function VehicleTelematicsTab({ vehicle, active }: { vehicle: Vehicle; active: boolean }) {
  const canTrips = useAuthStore((s) => s.hasPermission(PERMISSIONS.fleetTrip.read));
  const [hours, setHours] = useState('24');
  const [page, setPage] = useState(1);
  const devices = useTelematicsDevices({ vehicleId: vehicle.id });
  const last = useLastPosition(vehicle.id, active && canTrips);
  const distance = useVehicleDistance(vehicle.id, Number(hours), active && canTrips);
  const positions = useVehiclePositions(vehicle.id, page, active && canTrips);
  const device = devices.data?.[0];

  if (!device && !devices.isLoading) {
    return (
      <Card><CardContent className="py-8 text-center text-sm text-muted-foreground">
        {tx('No telematics unit is mapped to this vehicle.')}{' '}
        <Link to="/fleet/telematics" className="text-primary hover:underline">{tx('Telematics')}</Link>
      </CardContent></Card>
    );
  }
  const columns: Column<PositionRow>[] = [
    { key: 'at', header: tx('When'), render: (p) => <span className="whitespace-nowrap">{dateTime(p.recordedAt)}</span> },
    {
      key: 'where', header: tx('Where'),
      render: (p) => <a className="text-primary hover:underline" href={mapUrl(p.latitude, p.longitude)} target="_blank" rel="noreferrer">{Number(p.latitude).toFixed(5)}, {Number(p.longitude).toFixed(5)}</a>,
    },
    { key: 'speed', header: tx('Speed (km/h)'), className: 'text-right', render: (p) => num(p.speedKmh, 0) },
  ];
  const pos = last.data?.position;
  return (
    <div className="space-y-4">
      <div className="grid grid-cols-1 gap-4 md:grid-cols-3">
        <Card>
          <CardHeader><CardTitle className="text-base">{tx('Unit')}</CardTitle></CardHeader>
          <CardContent className="space-y-1 text-sm">
            <div className="font-medium">{device?.externalUnitId} <span className="text-muted-foreground">· {device?.provider}</span></div>
            <div className="text-muted-foreground">{tx('Last sync')}: {device?.lastSyncAt ? dateTime(device.lastSyncAt) : '—'} {device?.lastSyncStatus ? `· ${device.lastSyncStatus}` : ''}</div>
            {device?.lastError && <div className="text-xs text-rose-600">{device.lastError}</div>}
            <div>{tx('Odometer')}: {num(device?.lastOdometerKm, 0)} km · {tx('Engine hours')}: {num(device?.lastEngineHours)}</div>
          </CardContent>
        </Card>
        <Card>
          <CardHeader><CardTitle className="text-base">{tx('Last position')}</CardTitle></CardHeader>
          <CardContent className="space-y-1 text-sm">
            {!canTrips ? <p className="text-muted-foreground">{tx('You do not have access to positions.')}</p> : pos ? (
              <>
                <a className="inline-flex items-center gap-1 text-primary hover:underline" href={mapUrl(pos.latitude, pos.longitude)} target="_blank" rel="noreferrer">
                  <MapPin className="h-4 w-4" /> {Number(pos.latitude).toFixed(5)}, {Number(pos.longitude).toFixed(5)}
                </a>
                <div className="text-muted-foreground">{dateTime(pos.at)} · {num(pos.speedKmh, 0)} km/h</div>
              </>
            ) : <p className="text-muted-foreground">{tx('No position reported yet.')}</p>}
          </CardContent>
        </Card>
        <Card>
          <CardHeader className="flex flex-row items-center justify-between space-y-0">
            <CardTitle className="text-base">{tx('Distance driven')}</CardTitle>
            <div className="w-32">
              <OptionalSelect value={hours} onChange={(v) => setHours(v || '24')} placeholder="24 h"
                options={[{ value: '24', label: tx('Last 24 hours') }, { value: '168', label: tx('Last 7 days') }]} />
            </div>
          </CardHeader>
          <CardContent>
            <div className="text-2xl font-semibold tabular-nums">{canTrips ? `${num(distance.data?.distanceKm, 1)} km` : '—'}</div>
            <div className="text-xs text-muted-foreground">{tx('From {{points}} GPS positions', { points: distance.data?.points ?? 0 })}</div>
          </CardContent>
        </Card>
      </div>
      {canTrips && (
        <>
          <Card>
            <CardHeader><CardTitle className="text-base">{tx('Recent positions')}</CardTitle></CardHeader>
            <CardContent>
              <DataTable columns={columns} data={positions.data?.data ?? []} loading={positions.isLoading} getRowId={(p) => p.id}
                emptyMessage={positions.isError ? tx('Could not load the positions.') : tx('No positions in the last 24 hours.')} />
              {positions.data?.meta && positions.data.meta.total > 0 && (
                <DataTablePagination page={positions.data.meta.page} pageSize={20} total={positions.data.meta.total}
                  totalPages={positions.data.meta.totalPages} onPageChange={setPage} noun="position" />
              )}
            </CardContent>
          </Card>
          <Card>
            <CardHeader><CardTitle className="text-base">{tx('Alerts')}</CardTitle></CardHeader>
            <CardContent><AlertsTable active={active} vehicleId={vehicle.id} /></CardContent>
          </Card>
        </>
      )}
    </div>
  );
}

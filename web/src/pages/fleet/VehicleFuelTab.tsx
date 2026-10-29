import { Link, useNavigate } from 'react-router-dom';
import { Plus } from 'lucide-react';
import { CartesianGrid, Line, LineChart, ReferenceLine, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts';
import { PERMISSIONS } from '@erp/shared';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Skeleton } from '@/components/ui/skeleton';
import { DataTable, type Column } from '@/components/data-table';
import { useAuthStore } from '@/stores/auth.store';
import { date, dateTime, money } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';
import type { Vehicle } from '@/features/fleet/api';
import { useVehicleFuel, type AnomalyType, type FuelLog } from '@/features/fleet/fuel-api';
import { num } from './fleet-format';
import { AnomalyChips, FuelLogStatusPill, StatTile } from './fuel/fuel-ui';
import { efficiencyText } from './fuel/fuel-format';

/**
 * Fuel tab of the vehicle detail (fleet 1.5): totals, average efficiency, the class band and the full-to-full
 * efficiency trend (L/100 km for road units, L/h for plant — one measure per chart, never two axes).
 */
export function VehicleFuelTab({ vehicle, active }: { vehicle: Vehicle; active: boolean }) {
  const navigate = useNavigate();
  const canCreate = useAuthStore((s) => s.hasPermission(PERMISSIONS.fleetFuel.create));
  const { data, isLoading, isError } = useVehicleFuel(vehicle.id, active);
  if (isLoading || (!data && !isError)) return <Skeleton className="h-64 w-full" />;
  if (isError || !data) return <Card><CardContent className="py-8 text-center text-sm text-muted-foreground">{tx('Could not load the fuel data.')}</CardContent></Card>;

  const s = data.summary;
  const byHours = vehicle.meterType === 'hours' || (!data.trend.some((t) => t.lPer100Km) && data.trend.some((t) => t.lPerHour));
  const unit = byHours ? 'L/h' : 'L/100 km';
  const points = data.trend
    .map((t) => ({ code: t.code, at: date(t.fuelledAt), value: Number(byHours ? t.lPerHour : t.lPer100Km) }))
    .filter((p) => Number.isFinite(p.value) && p.value > 0);
  const min = byHours ? data.band.minLPerHour : data.band.minLPer100Km;
  const max = byHours ? data.band.maxLPerHour : data.band.maxLPer100Km;

  const columns: Column<FuelLog>[] = [
    { key: 'fuelledAt', header: tx('When'), render: (l) => <span className="whitespace-nowrap">{dateTime(l.fuelledAt)}</span> },
    { key: 'code', header: tx('Log'), render: (l) => <span className="font-medium text-primary">{l.code}</span> },
    { key: 'litres', header: tx('Litres'), className: 'text-right', render: (l) => <span className="tabular-nums">{num(l.litres, 1)}</span> },
    { key: 'cost', header: tx('Cost'), className: 'text-right', render: (l) => (l.amount ? money(l.amount) : '—') },
    { key: 'eff', header: tx('Efficiency'), render: (l) => efficiencyText(l.lPer100Km, l.lPerHour) },
    { key: 'anomalies', header: tx('Anomalies'), render: (l) => <AnomalyChips types={l.anomalies as AnomalyType[]} /> },
    { key: 'status', header: tx('Status'), render: (l) => <FuelLogStatusPill status={l.status} /> },
  ];

  return (
    <div className="space-y-4">
      <div className="grid grid-cols-2 gap-3 sm:grid-cols-4">
        <StatTile label={tx('Fuel logs')} value={s.logs} hint={s.lastFuelledAt ? tx('Last {{date}}', { date: date(s.lastFuelledAt) }) : undefined} />
        <StatTile label={tx('Litres')} value={num(s.litres, 0)} />
        <StatTile label={tx('Fuel cost')} value={money(s.amount)} />
        <StatTile label={tx('Average efficiency')} value={efficiencyText(s.averageLPer100Km, s.averageLPerHour)}
          hint={s.anomalies > 0 ? tx('{{count}} anomalies', { count: s.anomalies }) : undefined} tone={s.anomalies > 0 ? 'warning' : undefined} />
      </div>
      <Card>
        <CardHeader className="flex flex-row items-center justify-between space-y-0">
          <CardTitle className="text-base">{tx('Efficiency trend ({{unit}})', { unit })}</CardTitle>
          {canCreate && vehicle.status !== 'disposed' && (
            <Button size="sm" onClick={() => navigate(`/fleet/fuel/new?vehicleId=${vehicle.id}`)}><Plus className="mr-1.5 h-4 w-4" /> {tx('Record fuel')}</Button>
          )}
        </CardHeader>
        <CardContent>
          {points.length < 2 ? (
            <p className="py-6 text-center text-sm text-muted-foreground">{tx('Two full-tank fills are needed before an efficiency can be measured.')}</p>
          ) : (
            <div className="h-64">
              <ResponsiveContainer width="100%" height="100%">
                <LineChart data={points} margin={{ top: 8, right: 16, bottom: 0, left: 0 }}>
                  <CartesianGrid stroke="hsl(var(--border))" strokeDasharray="3 3" vertical={false} />
                  <XAxis dataKey="at" tick={{ fontSize: 11, fill: 'hsl(var(--muted-foreground))' }} tickLine={false} axisLine={false} />
                  <YAxis tick={{ fontSize: 11, fill: 'hsl(var(--muted-foreground))' }} tickLine={false} axisLine={false} width={44} />
                  <Tooltip formatter={(v: number) => [`${num(v, 1)} ${unit}`, tx('Efficiency')]} labelFormatter={(l, p) => `${l} · ${p?.[0]?.payload?.code ?? ''}`} />
                  {min && <ReferenceLine y={Number(min)} stroke="hsl(var(--muted-foreground))" strokeDasharray="4 4" label={{ value: tx('min'), position: 'insideTopLeft', fontSize: 10 }} />}
                  {max && <ReferenceLine y={Number(max)} stroke="hsl(var(--muted-foreground))" strokeDasharray="4 4" label={{ value: tx('max'), position: 'insideTopLeft', fontSize: 10 }} />}
                  <Line type="monotone" dataKey="value" stroke="hsl(var(--primary))" strokeWidth={2} dot={{ r: 4 }} activeDot={{ r: 6 }} />
                </LineChart>
              </ResponsiveContainer>
            </div>
          )}
          {(min || max) && <p className="mt-2 text-xs text-muted-foreground">{tx('Class band: {{min}} – {{max}} {{unit}}', { min: min ? num(min, 1) : '—', max: max ? num(max, 1) : '—', unit })}</p>}
        </CardContent>
      </Card>
      <Card>
        <CardHeader className="flex flex-row items-center justify-between space-y-0">
          <CardTitle className="text-base">{tx('Recent fuel logs')}</CardTitle>
          <Link to={`/fleet/fuel?vehicleId=${vehicle.id}`} className="text-sm text-primary hover:underline">{tx('All fuel logs')}</Link>
        </CardHeader>
        <CardContent>
          <DataTable columns={columns} data={data.recent} getRowId={(l) => l.id} onRowClick={(l) => navigate(`/fleet/fuel/logs/${l.id}`)}
            emptyMessage={tx('No fuel logged for this vehicle yet.')} />
        </CardContent>
      </Card>
    </div>
  );
}

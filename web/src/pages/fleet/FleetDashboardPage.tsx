import type { ReactNode } from 'react';
import { Link } from 'react-router-dom';
import { BarChart3, Plus, ShieldAlert, Truck, Wrench } from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Skeleton } from '@/components/ui/skeleton';
import { ListPageHeader } from '@/components/list';
import { useAuthStore } from '@/stores/auth.store';
import { tx } from '@/lib/i18n/tx';
import { KIND_LABEL, STATUS_LABEL, useFleetOverview, type VehicleStatus } from '@/features/fleet/api';
import { useFleetKpis, type FleetKpis } from '@/features/fleet/costing-api';
import { money } from '@/lib/format';
import { num } from './fleet-format';
import { VehicleStatusPill } from './fleet-ui';

/**
 * Fleet landing page: register size by status and class, compliance and meter-correction attention (fleet 1.1) and,
 * for report holders, the month-to-date KPIs (fleet 1.9).
 */
export function FleetDashboardPage() {
  const { data, isLoading, isError } = useFleetOverview();
  const canCreate = useAuthStore((s) => s.hasPermission(PERMISSIONS.fleetVehicle.create));
  const canReport = useAuthStore((s) => s.hasPermission(PERMISSIONS.fleet.report));
  const kpis = useFleetKpis(canReport);

  return (
    <div className="space-y-4">
      <ListPageHeader
        icon={Truck}
        title={tx('Fleet')}
        description={tx('Trucks, plant and equipment at a glance.')}
        actions={canCreate && (
          <Button asChild><Link to="/fleet/vehicles/new"><Plus className="mr-1.5 h-4 w-4" /> {tx('Register vehicle')}</Link></Button>
        )}
      />
      {canReport && <KpiTiles data={kpis.data} loading={kpis.isLoading} />}
      {isLoading && <Skeleton className="h-40 w-full" />}
      {isError && <p className="text-sm text-muted-foreground">{tx('Could not load the fleet overview.')}</p>}
      {data && (
        <>
          <div className="grid grid-cols-2 gap-3 lg:grid-cols-4">
            <Tile label={tx('Vehicles in service')} value={data.total} to="/fleet/vehicles" />
            <Tile label={tx('Compliance expired')} value={data.compliance.expired ?? 0} to="/fleet/compliance?due=expired" tone="danger" icon={<ShieldAlert className="h-4 w-4" />} />
            <Tile label={tx('Compliance expiring')} value={data.compliance.expiring ?? 0} to="/fleet/compliance?due=expiring" tone="warning" icon={<ShieldAlert className="h-4 w-4" />} />
            <Tile label={tx('Meter corrections waiting')} value={data.pendingCorrections} to="/approvals" tone={data.pendingCorrections ? 'warning' : undefined} icon={<Wrench className="h-4 w-4" />} />
          </div>
          <div className="grid grid-cols-1 gap-4 lg:grid-cols-2">
            <Card>
              <CardHeader><CardTitle className="text-base">{tx('By status')}</CardTitle></CardHeader>
              <CardContent className="space-y-2">
                {data.byStatus.length === 0 && <p className="text-sm text-muted-foreground">{tx('No vehicles registered yet.')}</p>}
                {data.byStatus.map((s) => (
                  <Link key={s.status} to={`/fleet/vehicles?status=${s.status}`} className="flex items-center justify-between rounded-md px-2 py-1.5 hover:bg-accent">
                    <VehicleStatusPill status={s.status} />
                    <span className="tabular-nums font-semibold" title={STATUS_LABEL[s.status as VehicleStatus]}>{s.count}</span>
                  </Link>
                ))}
              </CardContent>
            </Card>
            <Card>
              <CardHeader><CardTitle className="text-base">{tx('By class')}</CardTitle></CardHeader>
              <CardContent className="space-y-1">
                {data.byCategory.length === 0 && <p className="text-sm text-muted-foreground">{tx('No vehicles registered yet.')}</p>}
                {data.byCategory.map((c) => (
                  <Link key={c.id} to={`/fleet/vehicles?categoryId=${c.id}`} className="flex items-center justify-between rounded-md px-2 py-1.5 text-sm hover:bg-accent">
                    <span>{c.name} <span className="text-xs text-muted-foreground">· {KIND_LABEL[c.kind]}</span></span>
                    <span className="tabular-nums font-semibold">{c.count}</span>
                  </Link>
                ))}
              </CardContent>
            </Card>
          </div>
        </>
      )}
    </div>
  );
}

function Tile({ label, value, to, tone, icon }: { label: string; value: number; to: string; tone?: 'danger' | 'warning'; icon?: ReactNode }) {
  const color = tone === 'danger' && value > 0 ? 'text-rose-600' : tone === 'warning' && value > 0 ? 'text-amber-600' : '';
  return (
    <Link to={to}>
      <Card className="h-full transition-colors hover:bg-accent/40">
        <CardContent className="space-y-1 pt-4">
          <div className="flex items-center gap-1.5 text-xs text-muted-foreground">{icon}{label}</div>
          <div className={`text-3xl font-semibold tabular-nums ${color}`}>{value}</div>
        </CardContent>
      </Card>
    </Link>
  );
}

const pctText = (v: string | null | undefined) => (v == null ? '—' : `${num(v, 1)} %`);

/** Month-to-date KPIs of the fleet (fleet 1.9 reports). */
function KpiTiles({ data, loading }: { data?: FleetKpis; loading: boolean }) {
  if (loading) return <Skeleton className="h-28 w-full" />;
  if (!data) return null;
  const attention = data.renewalsExpired + data.servicesOverdue;
  const tiles: Array<{ label: string; value: string; to: string; tone?: string; hint?: string }> = [
    { label: tx('Deliveries (month)'), value: String(data.deliveries), to: '/fleet/reports?report=deliveries', hint: tx('{{t}} t delivered', { t: num(data.tonnes, 1) }) },
    { label: tx('On time'), value: pctText(data.onTimePct), to: '/fleet/reports?report=deliveries' },
    { label: tx('Transport cost / t'), value: data.transportCostPerTonne == null ? '—' : money(data.transportCostPerTonne), to: '/fleet/reports?report=transport-cost' },
    { label: tx('Delivery margin'), value: money(data.deliveryMargin), to: '/fleet/reports?report=delivery-profitability', tone: Number(data.deliveryMargin) < 0 ? 'text-rose-600' : undefined, hint: pctText(data.deliveryMarginPct) },
    { label: tx('Availability'), value: pctText(data.availabilityPct), to: '/fleet/reports?report=utilisation', hint: tx('Utilisation {{u}}', { u: pctText(data.utilisationPct) }) },
    { label: tx('Fuel (month)'), value: money(data.fuelCost), to: '/fleet/reports?report=fuel', hint: tx('{{l}} L', { l: num(data.fuelLitres, 0) }) },
    { label: tx('Cost of ownership (month)'), value: money(data.totalCostOfOwnership), to: '/fleet/reports?report=vehicle-costs', hint: data.costPerKm ? tx('{{c}} / km', { c: num(data.costPerKm, 0) }) : undefined },
    { label: tx('Renewals & services'), value: String(attention), to: '/fleet/reports?report=renewals', tone: attention ? 'text-rose-600' : undefined, hint: tx('{{e}} expiring · {{d}} due', { e: data.renewalsExpiring, d: data.servicesDue }) },
    { label: tx('Freight to bill'), value: String(data.unbilledTransporterTrips), to: '/fleet/freight-bills', tone: data.unbilledTransporterTrips ? 'text-amber-600' : undefined, hint: tx('{{n}} bill(s) to match', { n: data.freightBillsToMatch }) },
  ];
  return (
    <div className="space-y-2">
      <div className="flex items-center gap-1.5 text-sm font-medium text-muted-foreground"><BarChart3 className="h-4 w-4" />{tx('This month')}</div>
      <div className="grid grid-cols-2 gap-3 md:grid-cols-3 xl:grid-cols-5">
        {tiles.map((t) => (
          <Link key={t.label} to={t.to}>
            <Card className="h-full transition-colors hover:bg-accent/40">
              <CardContent className="space-y-1 pt-4">
                <div className="text-xs text-muted-foreground">{t.label}</div>
                <div className={`text-2xl font-semibold tabular-nums ${t.tone ?? ''}`}>{t.value}</div>
                {t.hint && <div className="text-xs text-muted-foreground">{t.hint}</div>}
              </CardContent>
            </Card>
          </Link>
        ))}
      </div>
    </div>
  );
}

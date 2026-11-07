import { useMemo } from 'react';
import { Link } from 'react-router-dom';
import { Plus, Truck } from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { useAuthStore } from '@/stores/auth.store';
import { date, money, useOrgCurrency } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';
import { cn } from '@/lib/utils';
import { compactMoney } from '@/pages/sales/dashboard/utils';
import { compactNumber } from '@/pages/sales/reports/report-format';
import { useOps } from '@/components/ops-dashboard/api';
import { Donut, Empty, FlagPill, Gauge, RankedBars, SegmentBar, STATUS, TrendChart, useSeries, type RankRow } from '@/components/ops-dashboard/charts';
import { Brief, DashboardHero, HeroAction, HeroStat, num, numOrNull, Panel, pctText, qtyText, Reveal, type BriefItem } from '@/components/ops-dashboard/kit';
import { rollUp, usePeriod } from '@/components/ops-dashboard/period';
import { KIND_LABEL, STATUS_LABEL, type VehicleStatus } from '@/features/fleet/api';

const REPORT = PERMISSIONS.fleet.report;

interface Overview {
  total: number;
  byStatus: Array<{ status: VehicleStatus; count: number }>;
  byCategory: Array<{ id: string; code: string; name: string; kind: keyof typeof KIND_LABEL; count: number }>;
  compliance: { expired?: number; expiring?: number };
  pendingCorrections: number;
}
type Envelope<R, T = R> = { rows: R[]; totals: T };
interface DeliveryRow { key: string; label: string; trips: number; onTime: number; late: number; onTimePct: string | null; tonnes: string | null; km: string | null; returnedTrips?: number }
interface CarrierRow { key: string; label: string; trips: number; tonnes: string | null; transportCost: string; costPerTonne: string | null; costPerKm: string | null }
interface CostRow { key: string; label: string; categoryName?: string; total: string; fuel: string; maintenance: string; tyres: string; insurance: string; compliance: string; depreciation: string; incidents: string; allowances: string; fines: string; perKm: string | null }
interface UtilRow { key: string; label: string; categoryName?: string; vehicleId?: string; trips: number; downtimeHours: string; utilisationPct: string | null; availabilityPct: string | null }
type UtilReport = Envelope<UtilRow> & { downtimeByReason: Array<{ reason: string; events: number; hours: string }> };
interface FuelRow { key: string; label: string; litres: string; cost: string; openAnomalies: number; lPer100Km: string | null }
interface ProfitRow { key: string; label: string; trips: number; revenue: string; margin: string; marginPct: string | null }
interface RenewalRow { key: string; kind: string; item: string; label: string; reference: string | null; dueOn: string; daysLeft: number; state: 'expired' | 'overdue' | 'expiring' | 'due' | string; link?: string }
interface Renewals { rows: RenewalRow[]; counts: { expired: number; overdue: number; expiring: number; due: number } }

const REASON: Record<string, () => string> = {
  preventive_service: () => tx('Preventive service'),
  breakdown: () => tx('Breakdown'),
  tyres: () => tx('Tyres'),
  accident: () => tx('Accident'),
  inspection: () => tx('Inspection'),
  repair: () => tx('Repair'),
  no_driver: () => tx('No driver'),
  other: () => tx('Other'),
};
const reasonLabel = (r: string) => {
  const known = REASON[r]?.();
  if (known) return known;
  const words = r.replace(/_/g, ' ');
  return words.charAt(0).toUpperCase() + words.slice(1);
};

function statusColor(status: string, palette: string[]): string {
  if (status === 'active') return STATUS.good;
  if (status === 'in_workshop' || status === 'maintenance') return STATUS.serious;
  if (status === 'disposed' || status === 'written_off') return STATUS.critical;
  if (status === 'inactive' || status === 'sold') return STATUS.neutral;
  return palette[0];
}

/**
 * Fleet landing page: on-time delivery and what it costs, what vehicles cost to own, availability, fuel, and the
 * renewals coming due. Without the reports permission it keeps the register, class and compliance view.
 */
export function FleetDashboardPage() {
  const currency = useOrgCurrency();
  const palette = useSeries();
  const canCreate = useAuthStore((s) => s.hasPermission(PERMISSIONS.fleetVehicle.create));
  const { period, setPeriod } = usePeriod();
  const range = { from: period.from, to: period.to };
  const cmoney = (v: unknown) => compactMoney(v, currency);

  const overview = useOps<Overview>('/fleet/overview', undefined, PERMISSIONS.fleetVehicle.read);
  const deliveries = useOps<Envelope<DeliveryRow>>('/fleet/reports/deliveries', { ...range, groupBy: 'day' }, REPORT);
  const carriers = useOps<Envelope<CarrierRow>>('/fleet/reports/transport-cost', { ...range, groupBy: 'carrier' }, REPORT);
  const costs = useOps<Envelope<CostRow>>('/fleet/reports/vehicle-costs', { ...range, groupBy: 'vehicle' }, REPORT);
  const util = useOps<UtilReport>('/fleet/reports/utilisation', { ...range, groupBy: 'vehicle' }, REPORT);
  const fuel = useOps<Envelope<FuelRow>>('/fleet/reports/fuel', { ...range, groupBy: 'vehicle' }, REPORT);
  const profit = useOps<Envelope<ProfitRow>>('/fleet/reports/delivery-profitability', { ...range, groupBy: 'customer' }, REPORT);
  const renewals = useOps<Renewals>('/fleet/reports/renewals', undefined, REPORT);

  const canReport = deliveries.allowed;
  const o = overview.data;
  const dt = deliveries.data?.totals;
  const onTime = numOrNull(dt?.onTimePct);
  const availability = numOrNull(util.data?.totals.availabilityPct);
  const utilisation = numOrNull(util.data?.totals.utilisationPct);
  const carrierTotals = carriers.data?.totals;
  const profitTotals = profit.data?.totals;

  const trend = useMemo(() => rollUp(
    deliveries.data?.rows ?? [],
    (r) => r.key,
    { onTime: (r) => r.onTime, late: (r) => r.late },
    period,
    period.grain,
  ), [deliveries.data, period]);

  const statusSeg = (o?.byStatus ?? []).map((s) => ({
    key: s.status, label: STATUS_LABEL[s.status] ?? s.status, value: s.count, color: statusColor(s.status, palette), href: `/fleet/vehicles?status=${s.status}`,
  }));
  const byClass: RankRow[] = (o?.byCategory ?? []).map((c) => ({
    key: c.id, label: c.name, value: c.count, display: String(c.count), sub: KIND_LABEL[c.kind], href: `/fleet/vehicles?categoryId=${c.id}`,
  })).sort((a, b) => b.value - a.value);

  const partNames = [tx('Fuel'), tx('Maintenance'), tx('Tyres'), tx('Insurance & compliance'), tx('Depreciation'), tx('Other')];
  const parts = (r: CostRow) => [
    num(r.fuel), num(r.maintenance), num(r.tyres), num(r.insurance) + num(r.compliance), num(r.depreciation),
    num(r.incidents) + num(r.allowances) + num(r.fines),
  ];
  const costRows: RankRow[] = (costs.data?.rows ?? []).map((r) => ({
    key: r.key, label: r.label, sub: r.categoryName, value: num(r.total), display: cmoney(r.total), note: numOrNull(r.perKm) != null ? tx('{{c}} / km', { c: compactNumber(num(r.perKm)) }) : undefined, parts: parts(r),
    href: `/fleet/vehicles/${r.key}`,
  })).sort((a, b) => b.value - a.value);
  const costTotals = costs.data?.totals;
  const composition = costTotals ? parts(costTotals).map((value, i) => ({ key: partNames[i], label: partNames[i], value })) : [];

  const availRows: RankRow[] = (util.data?.rows ?? []).map((r) => {
    const a = num(r.availabilityPct);
    return {
      key: r.key, label: r.label, sub: r.categoryName, value: a, display: pctText(numOrNull(r.availabilityPct)),
      note: tx('used {{pct}} · {{hours}} h down', { pct: pctText(numOrNull(r.utilisationPct)), hours: qtyText(num(r.downtimeHours)) }),
      href: `/fleet/vehicles/${r.key}`,
      flag: a < 70 ? { tone: 'critical' as const, label: tx('Low') } : a < 85 ? { tone: 'warning' as const, label: tx('Watch') } : undefined,
    };
  }).sort((a, b) => a.value - b.value);

  const downtimeRows = (util.data?.downtimeByReason ?? []).map((r) => ({
    key: r.reason, label: reasonLabel(r.reason), value: num(r.hours), display: `${qtyText(num(r.hours))} h`, sub: r.events === 1 ? tx('1 event') : tx('{{count}} events', { count: r.events }),
  })).sort((a, b) => b.value - a.value);

  const fuelRows: RankRow[] = (fuel.data?.rows ?? []).map((r) => ({
    key: r.key, label: r.label, value: num(r.cost), display: cmoney(r.cost),
    sub: tx('{{l}} L', { l: qtyText(num(r.litres)) }), note: numOrNull(r.lPer100Km) != null ? tx('{{v}} L/100 km', { v: num(r.lPer100Km).toFixed(1) }) : undefined,
    flag: r.openAnomalies > 0 ? { tone: 'warning' as const, label: r.openAnomalies === 1 ? tx('1 anomaly') : tx('{{count}} anomalies', { count: r.openAnomalies }) } : undefined,
    href: `/fleet/vehicles/${r.key}`,
  })).sort((a, b) => b.value - a.value);

  const profitRows: RankRow[] = (profit.data?.rows ?? []).map((r) => ({
    key: r.key, label: r.label, value: Math.abs(num(r.margin)), display: cmoney(r.margin), sub: r.trips === 1 ? tx('1 trip') : tx('{{count}} trips', { count: r.trips }), note: pctText(numOrNull(r.marginPct)),
    flag: num(r.margin) < 0 ? { tone: 'critical' as const, label: tx('Loss') } : undefined,
  })).sort((a, b) => b.value - a.value);

  const rn = renewals.data;
  const renewalSeg = [
    { key: 'expired', label: tx('Expired'), value: rn?.counts.expired ?? 0, color: STATUS.critical, href: '/fleet/reports?report=renewals' },
    { key: 'overdue', label: tx('Service overdue'), value: rn?.counts.overdue ?? 0, color: STATUS.serious, href: '/fleet/reports?report=renewals' },
    { key: 'expiring', label: tx('Expiring soon'), value: rn?.counts.expiring ?? 0, color: STATUS.warning, ink: '#0b0b0b', href: '/fleet/reports?report=renewals' },
    { key: 'due', label: tx('Service due'), value: rn?.counts.due ?? 0, color: palette[0], href: '/fleet/reports?report=renewals' },
  ];
  const nextRenewals = [...(rn?.rows ?? [])].sort((a, b) => a.daysLeft - b.daysLeft).slice(0, 6);

  const anomalies = fuel.data?.totals ? fuel.data.rows.reduce((s, r) => s + r.openAnomalies, 0) : 0;
  const own = carriers.data?.rows.find((r) => r.key === 'own');
  const hired = carriers.data?.rows.find((r) => r.key === 'transporter');
  const expired = o?.compliance.expired ?? 0;
  const expiring = o?.compliance.expiring ?? 0;
  const svcLate = rn?.counts.overdue ?? 0;
  const corrections = o?.pendingCorrections ?? 0;
  const brief: BriefItem[] = [];
  if (expired > 0) brief.push({ key: 'exp', tone: 'bad', text: (expired === 1 ? tx('{{count}} compliance document has expired.', { count: expired }) : tx('{{count}} compliance documents have expired.', { count: expired })), to: '/fleet/compliance?due=expired' });
  if (svcLate > 0) brief.push({ key: 'svc', tone: 'bad', text: (svcLate === 1 ? tx('{{count}} vehicle service is overdue.', { count: svcLate }) : tx('{{count}} vehicle services are overdue.', { count: svcLate })), to: '/fleet/maintenance' });
  if (onTime != null && onTime < 85) brief.push({ key: 'otd', tone: 'bad', text: tx('On-time delivery is only {{pct}}.', { pct: pctText(onTime) }), to: '/fleet/reports?report=deliveries' });
  if (num(profitTotals?.margin) < 0) brief.push({ key: 'margin', tone: 'bad', text: tx('Deliveries are losing money in this period.'), to: '/fleet/reports?report=delivery-profitability' });
  if (anomalies > 0) brief.push({ key: 'fuel', tone: 'warn', text: (anomalies === 1 ? tx('{{count}} fuel anomaly is waiting for review.', { count: anomalies }) : tx('{{count}} fuel anomalies are waiting for review.', { count: anomalies })), to: '/fleet/fuel' });
  if (expiring > 0) brief.push({ key: 'soon', tone: 'warn', text: (expiring === 1 ? tx('{{count}} compliance document expires soon.', { count: expiring }) : tx('{{count}} compliance documents expire soon.', { count: expiring })), to: '/fleet/compliance?due=expiring' });
  if (corrections > 0) brief.push({ key: 'meter', tone: 'warn', text: (corrections === 1 ? tx('{{count}} meter correction is waiting for approval.', { count: corrections }) : tx('{{count}} meter corrections are waiting for approval.', { count: corrections })), to: '/approvals' });
  if (own && hired && num(own.costPerTonne) > 0 && num(hired.costPerTonne) > 0) brief.push({ key: 'compare', tone: 'info', text: tx('Own fleet costs {{own}} per tonne; third-party transporters cost {{hired}}.', { own: cmoney(own.costPerTonne), hired: cmoney(hired.costPerTonne) }), to: '/fleet/reports?report=transport-cost' });
  if (onTime != null && onTime >= 95) brief.push({ key: 'good', tone: 'good', text: tx('On-time delivery is strong at {{pct}}.', { pct: pctText(onTime) }) });

  return (
    <div className="space-y-5">
      <DashboardHero
        icon={Truck}
        title={tx('Fleet')}
        subtitle={tx('Trucks, plant and equipment at a glance.')}
        period={period.key}
        onPeriod={setPeriod}
        fetching={overview.isFetching || deliveries.isFetching}
        actions={canCreate && <HeroAction to="/fleet/vehicles/new" icon={Plus} primary>{tx('Register vehicle')}</HeroAction>}
        headline={canReport
          ? { label: tx('Deliveries completed'), value: dt?.trips ?? 0, format: (v) => qtyText(v), sub: tx('{{t}} t delivered over {{km}} km', { t: qtyText(num(dt?.tonnes), 1), km: qtyText(num(dt?.km)) }) }
          : { label: tx('Vehicles in service'), value: o?.total ?? 0, format: (v) => qtyText(v) }}
        chartTitle={canReport ? tx('Trips on time and late') : tx('Vehicles by class')}
        chart={canReport
          ? <TrendChart onHero kind="stacked" height={170} data={trend} format={(v) => qtyText(v)} series={[{ key: 'onTime', label: tx('On time'), color: 'var(--sb-active-bar)' }, { key: 'late', label: tx('Late'), color: STATUS.critical }]} />
          : <TrendChart onHero height={170} data={byClass.map((c) => ({ label: c.label, count: c.value }))} format={(v) => qtyText(v)} series={[{ key: 'count', label: tx('Vehicles'), color: 'var(--sb-active-bar)' }]} />}
        stats={(
          <>
            {canReport && <HeroStat label={tx('On time')} value={pctText(onTime)} sub={tx('{{count}} late', { count: dt?.late ?? 0 })} to="/fleet/reports?report=deliveries" ring={onTime} />}
            {canReport && <HeroStat label={tx('Availability')} value={pctText(availability)} sub={tx('Utilisation {{u}}', { u: pctText(utilisation) })} to="/fleet/reports?report=utilisation" ring={availability} />}
            {canReport && <HeroStat label={tx('Transport cost / t')} value={carrierTotals?.costPerTonne == null ? '—' : cmoney(carrierTotals.costPerTonne)} title={carrierTotals?.costPerTonne == null ? undefined : money(carrierTotals.costPerTonne, currency)} sub={tx('{{total}} in total', { total: cmoney(carrierTotals?.transportCost) })} to="/fleet/reports?report=transport-cost" />}
            {canReport && <HeroStat label={tx('Delivery margin')} value={cmoney(profitTotals?.margin)} title={money(profitTotals?.margin, currency)} sub={pctText(numOrNull(profitTotals?.marginPct))} to="/fleet/reports?report=delivery-profitability" />}
            <HeroStat label={tx('Vehicles in service')} value={qtyText(o?.total ?? 0)} to="/fleet/vehicles" />
            <HeroStat label={tx('Compliance expired')} value={qtyText(o?.compliance.expired ?? 0)} sub={tx('{{count}} expiring', { count: o?.compliance.expiring ?? 0 })} to="/fleet/compliance?due=expired" />
          </>
        )}
      />

      <Reveal delay={80} className="grid gap-5 lg:grid-cols-2">
        <Brief items={brief} />
        {canReport ? (
          <Panel title={tx('Fleet health')} hint={tx('For the selected period')}>
            <div className="grid grid-cols-3 gap-2">
              <Gauge label={tx('On time')} value={onTime} threshold={{ good: 90, warn: 75 }} sub={tx('{{count}} trips', { count: dt?.trips ?? 0 })} />
              <Gauge label={tx('Availability')} value={availability} threshold={{ good: 90, warn: 75 }} sub={tx('{{h}} h down', { h: qtyText(num(util.data?.totals.downtimeHours)) })} />
              <Gauge label={tx('Utilisation')} value={utilisation} sub={tx('Share of available hours worked')} />
            </div>
          </Panel>
        ) : (
          <Panel title={tx('Register by status')} href="/fleet/vehicles" action={tx('View all')}><SegmentBar rows={statusSeg} /></Panel>
        )}
      </Reveal>

      {canReport && (
        <Reveal delay={140} className="grid gap-5 lg:grid-cols-5">
          <Panel className="lg:col-span-3" title={tx('Cost of ownership by vehicle')} hint={tx('Highest first')} href="/fleet/reports?report=vehicle-costs" action={tx('Report')}>
            <RankedBars rows={costRows} parts={partNames} limit={7} />
          </Panel>
          <Panel className="lg:col-span-2" title={tx('Where fleet money goes')} hint={tx('All vehicles')}>
            <Donut rows={composition} center={cmoney(costTotals?.total)} format={cmoney} size={190} stacked />
          </Panel>
        </Reveal>
      )}

      {canReport && (
        <Reveal delay={200} className="grid gap-5 lg:grid-cols-3">
          <Panel title={tx('Availability by vehicle')} hint={tx('Lowest first')} href="/fleet/reports?report=utilisation" action={tx('Report')}>
            <RankedBars rows={availRows} max={100} limit={6} />
          </Panel>
          <Panel title={tx('Why vehicles stand still')} hint={tx('Downtime hours by reason')} href="/fleet/maintenance" action={tx('Maintenance')}>
            <RankedBars rows={downtimeRows} limit={6} />
          </Panel>
          <Panel title={tx('Fuel spend by vehicle')} hint={tx('Highest first')} href="/fleet/reports?report=fuel" action={tx('Report')}>
            <RankedBars rows={fuelRows} limit={6} />
          </Panel>
        </Reveal>
      )}

      <Reveal delay={260} className="grid gap-5 lg:grid-cols-3">
        {canReport && (
          <Panel title={tx('Delivery margin by customer')} hint={tx('Largest first')} href="/fleet/reports?report=delivery-profitability" action={tx('Report')}>
            <RankedBars rows={profitRows} limit={6} />
          </Panel>
        )}
        <Panel title={tx('Register')} hint={tx('{{count}} vehicles', { count: o?.total ?? 0 })} href="/fleet/vehicles" action={tx('View all')}>
          <SegmentBar rows={statusSeg} />
          <div className="mt-5"><RankedBars rows={byClass} limit={6} /></div>
        </Panel>
        {canReport && (
          <Panel title={tx('Renewals and services')} hint={tx('Next 30 days and anything late')} href="/fleet/reports?report=renewals" action={tx('Report')}>
            <SegmentBar rows={renewalSeg} />
            {nextRenewals.length === 0 ? <Empty>{tx('Nothing coming due.')}</Empty> : (
              <ul className="mt-4 divide-y text-sm">
                {nextRenewals.map((r) => (
                  <li key={r.key} className="py-1.5">
                    <Link to={r.link ?? '/fleet/reports?report=renewals'} className="flex items-center gap-2 hover:text-primary">
                      <span className="min-w-0 flex-1 truncate" title={`${r.label} · ${r.reference ?? r.item}`}>{r.label}</span>
                      <FlagPill tone={r.daysLeft < 0 ? 'critical' : 'warning'}>{r.daysLeft < 0 ? tx('{{d}} d late', { d: -r.daysLeft }) : tx('in {{d}} d', { d: r.daysLeft })}</FlagPill>
                    </Link>
                    <div className={cn('truncate text-[11px] text-muted-foreground')}>{r.reference ?? r.item} · {date(r.dueOn)}</div>
                  </li>
                ))}
              </ul>
            )}
          </Panel>
        )}
      </Reveal>
    </div>
  );
}

import { useState } from 'react';
import { Link } from 'react-router-dom';
import { BarChart3, Download, Loader2 } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import {
  DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu';
import { Tabs, TabsList, TabsTrigger } from '@/components/ui/tabs';
import { DataTable, type Column } from '@/components/data-table';
import { DateRangeFilter, FilterSelect, ListCard, ListPageHeader, ListToolbar, StatusPill, useListState } from '@/components/list';
import { notify, apiMessage } from '@/lib/notify';
import { date, money } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';
import {
  COST_BASIS_LABEL, RENEWAL_KIND_LABEL, RENEWAL_STATE_LABEL, downloadReport, useDeliveriesReport, useFuelReport,
  useProfitability, useRenewals, useTonnesReport, useTransportCost, useUtilisation, useVehicleCosts,
  type CostRow, type DeliveryRow, type FuelRow, type ProfitRow, type RenewalRow, type TonnesRow, type TransportCostRow,
  type UtilisationRow,
} from '@/features/fleet/costing-api';
import { num, toOptions } from '../fleet-format';

type ReportKey = 'vehicle-costs' | 'delivery-profitability' | 'deliveries' | 'tonnes' | 'transport-cost' | 'utilisation' | 'fuel' | 'renewals';

const REPORTS: Record<ReportKey, string> = {
  'vehicle-costs': tx('Cost of ownership'),
  'delivery-profitability': tx('Delivery profitability'),
  deliveries: tx('Deliveries & on time'),
  tonnes: tx('Tonnes delivered'),
  'transport-cost': tx('Transport cost per tonne'),
  utilisation: tx('Utilisation & availability'),
  fuel: tx('Fuel'),
  renewals: tx('Renewals & services'),
};

const GROUPS: Record<ReportKey, Record<string, string>> = {
  'vehicle-costs': { vehicle: tx('By vehicle'), category: tx('By class'), branch: tx('By branch'), month: tx('By month') },
  'delivery-profitability': { trip: tx('By trip'), delivery_order: tx('By delivery order'), customer: tx('By customer'), site: tx('By site'), day: tx('By day'), month: tx('By month') },
  deliveries: { day: tx('By day'), month: tx('By month'), customer: tx('By customer'), site: tx('By site'), vehicle: tx('By vehicle'), driver: tx('By driver'), transporter: tx('By transporter'), carrier: tx('By carrier') },
  tonnes: { vehicle: tx('By vehicle'), driver: tx('By driver'), customer: tx('By customer'), site: tx('By site'), transporter: tx('By transporter'), product: tx('By product'), month: tx('By month') },
  'transport-cost': { carrier: tx('By carrier'), vehicle: tx('By vehicle'), transporter: tx('By transporter'), customer: tx('By customer'), site: tx('By site'), month: tx('By month') },
  utilisation: { vehicle: tx('By vehicle'), category: tx('By class') },
  fuel: { vehicle: tx('By vehicle'), category: tx('By class'), month: tx('By month') },
  renewals: {},
};

const CARRIERS = { own: tx('Own fleet'), transporter: tx('Transporters') };
const KINDS: Record<string, string> = { ...RENEWAL_KIND_LABEL };
const WINDOWS: Record<string, string> = { '7': tx('Next 7 days'), '30': tx('Next 30 days'), '60': tx('Next 60 days'), '90': tx('Next 90 days') };

const FILTERS = { report: '', from: '', to: '', groupBy: '', carrier: '', days: '', kind: '' };

const n0 = (v: string | number | null | undefined) => num(v, 0);
const n1 = (v: string | number | null | undefined) => num(v, 1);
const n2 = (v: string | number | null | undefined) => num(v, 2);
const pct = (v: string | null | undefined) => (v == null ? '—' : `${num(v, 1)} %`);
const right = 'text-right tabular-nums whitespace-nowrap';

/**
 * Fleet reports (fleet 1.9): cost of ownership and unit rates, delivery profitability, deliveries on time, tonnes,
 * transport cost per tonne, utilisation / availability, fuel and upcoming renewals — with period, grouping and
 * carrier filters kept in the URL and CSV / XLSX / PDF downloads rendered by the server.
 */
export function FleetReportsPage() {
  const list = useListState(FILTERS);
  const f = list.filters;
  const report = (f.report || 'vehicle-costs') as ReportKey;
  const groupOptions = GROUPS[report];
  const groupBy = f.groupBy && groupOptions[f.groupBy] ? f.groupBy : Object.keys(groupOptions)[0] ?? '';
  const params = { from: f.from || undefined, to: f.to || undefined, groupBy: groupBy || undefined, carrier: f.carrier || undefined };
  const renewalParams = { days: f.days || '30', kind: f.kind || undefined };
  const [busy, setBusy] = useState(false);

  const download = async (format: 'csv' | 'xlsx' | 'pdf') => {
    setBusy(true);
    try {
      await downloadReport(report, format, report === 'renewals' ? renewalParams : params);
    } catch (e) {
      notify.error(tx('Export failed'), apiMessage(e, tx('Could not build the report file.')));
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="space-y-4">
      <ListPageHeader icon={BarChart3} title={tx('Fleet Reports')}
        description={tx('Cost of ownership, delivery profitability, deliveries and tonnes, utilisation, fuel and renewals. Periods default to the month to date.')}
        actions={(
          <DropdownMenu>
            <DropdownMenuTrigger asChild>
              <Button variant="outline" disabled={busy} className="gap-1.5">
                {busy ? <Loader2 className="h-4 w-4 animate-spin" /> : <Download className="h-4 w-4" />} {tx('Export')}
              </Button>
            </DropdownMenuTrigger>
            <DropdownMenuContent align="end">
              <DropdownMenuItem onClick={() => download('csv')}>{tx('CSV')}</DropdownMenuItem>
              <DropdownMenuItem onClick={() => download('xlsx')}>{tx('Excel (XLSX)')}</DropdownMenuItem>
              <DropdownMenuItem onClick={() => download('pdf')}>{tx('PDF')}</DropdownMenuItem>
            </DropdownMenuContent>
          </DropdownMenu>
        )} />
      <Tabs value={report} onValueChange={(v) => list.setFilters({ report: v, groupBy: '' })}>
        <div className="overflow-x-auto">
          <TabsList className="h-auto flex-wrap justify-start">
            {(Object.keys(REPORTS) as ReportKey[]).map((k) => <TabsTrigger key={k} value={k}>{REPORTS[k]}</TabsTrigger>)}
          </TabsList>
        </div>
      </Tabs>
      <ListToolbar>
        {report !== 'renewals' ? (
          <>
            <DateRangeFilter from={f.from} to={f.to} onChange={(a, b) => list.setFilters({ from: a, to: b })} />
            {Object.keys(groupOptions).length > 1 && (
              <FilterSelect value={groupBy} onChange={(v) => list.setFilter('groupBy', v)} options={toOptions(groupOptions)} allLabel={Object.values(groupOptions)[0]} />
            )}
            {['delivery-profitability', 'deliveries', 'tonnes', 'transport-cost'].includes(report) && (
              <FilterSelect value={f.carrier} onChange={(v) => list.setFilter('carrier', v)} options={toOptions(CARRIERS)} allLabel={tx('All carriers')} />
            )}
          </>
        ) : (
          <>
            <FilterSelect value={f.days} onChange={(v) => list.setFilter('days', v)} options={toOptions(WINDOWS)} allLabel={WINDOWS['30']} />
            <FilterSelect value={f.kind} onChange={(v) => list.setFilter('kind', v)} options={toOptions(KINDS)} allLabel={tx('Everything')} />
          </>
        )}
        <Link to="/fleet/weighbridge/reports" className="ml-auto text-sm text-primary hover:underline">{tx('Weighbridge discrepancies')} →</Link>
      </ListToolbar>
      {report === 'vehicle-costs' && <CostReport params={params} />}
      {report === 'delivery-profitability' && <ProfitReport params={params} groupBy={groupBy} />}
      {report === 'deliveries' && <DeliveryReport params={params} />}
      {report === 'tonnes' && <TonnesReport params={params} groupBy={groupBy} />}
      {report === 'transport-cost' && <TransportCostReport params={params} />}
      {report === 'utilisation' && <UtilisationReport params={params} />}
      {report === 'fuel' && <FuelReportView params={params} />}
      {report === 'renewals' && <RenewalsReport params={renewalParams} />}
    </div>
  );
}

type P = Record<string, string | undefined>;

function ReportTable<T extends { key: string }>({ columns, rows, totals, loading, error, empty, footnote }: {
  columns: Column<T>[]; rows: T[]; totals?: T; loading: boolean; error: boolean; empty: string; footnote?: string;
}) {
  const data = totals && rows.length ? [...rows, totals] : rows;
  return (
    <ListCard>
      <div className="overflow-x-auto">
        <DataTable columns={columns} data={data} loading={loading} loadingRows={6} getRowId={(r) => `${r.key}`}
          cellClassName="py-2 px-3" headerRowClassName="h-10"
          emptyMessage={error ? tx('Could not load the report.') : empty} />
      </div>
      {footnote && <p className="border-t px-4 py-2 text-xs text-muted-foreground">{footnote}</p>}
    </ListCard>
  );
}

const label = <T extends { key: string; label: string }>(): Column<T> => ({
  key: 'label', header: tx('Group'),
  render: (r) => <span className={r.key === 'total' ? 'font-semibold' : 'font-medium'}>{r.key === 'total' ? tx('Total') : r.label}</span>,
});

function CostReport({ params }: { params: P }) {
  const { data, isLoading, isError } = useVehicleCosts(params);
  const columns: Column<CostRow>[] = [
    {
      key: 'label', header: tx('Vehicle / group'),
      render: (r) => (r.key === 'total' ? <span className="font-semibold">{tx('Total')}</span>
        : r.vehicleId ? <Link className="font-medium text-primary hover:underline" to={`/fleet/vehicles/${r.vehicleId}`}>{r.label}</Link>
          : <span className="font-medium">{r.label}</span>),
    },
    { key: 'dep', header: tx('Depreciation'), className: right, render: (r) => money(r.depreciation) },
    { key: 'fuel', header: tx('Fuel'), className: right, render: (r) => money(r.fuel) },
    { key: 'mnt', header: tx('Maintenance'), className: right, render: (r) => money(r.maintenance) },
    { key: 'tyres', header: tx('Tyres'), className: right, render: (r) => money(r.tyres) },
    { key: 'ins', header: tx('Insurance'), className: right, render: (r) => money(r.insurance) },
    { key: 'lic', header: tx('Licences & permits'), className: right, render: (r) => money(r.compliance) },
    { key: 'inc', header: tx('Incidents'), className: right, render: (r) => money(r.incidents) },
    { key: 'allow', header: tx('Driver allowances'), className: right, render: (r) => money(r.allowances) },
    { key: 'fines', header: tx('Fines'), className: right, render: (r) => money(r.fines) },
    { key: 'total', header: tx('Total cost'), className: `${right} font-semibold`, render: (r) => money(r.total) },
    { key: 'km', header: tx('km'), className: right, render: (r) => n0(r.km) },
    { key: 'hours', header: tx('Engine h'), className: right, render: (r) => n1(r.hours) },
    { key: 'tonnes', header: tx('Tonnes'), className: right, render: (r) => n1(r.tonnes) },
    { key: 'trips', header: tx('Trips'), className: right, render: (r) => r.trips },
    { key: 'perKm', header: tx('Cost / km'), className: right, render: (r) => n2(r.perKm) },
    { key: 'perHour', header: tx('Cost / h'), className: right, render: (r) => n2(r.perHour) },
    { key: 'perTonne', header: tx('Cost / t'), className: right, render: (r) => n2(r.perTonne) },
    { key: 'perTrip', header: tx('Cost / trip'), className: right, render: (r) => n2(r.perTrip) },
  ];
  return <ReportTable columns={columns} rows={data?.rows ?? []} totals={data?.totals} loading={isLoading} error={isError}
    empty={tx('No vehicles in this period.')}
    footnote={tx('Depreciation from the linked fixed asset; fuel, maintenance, tyres, insurance and licence fees, incidents net of insurance recoveries, driver allowances and company fines of the period. Use from meter readings and delivered trips.')} />;
}

function ProfitReport({ params, groupBy }: { params: P; groupBy: string }) {
  const { data, isLoading, isError } = useProfitability(params);
  const trip = groupBy === 'trip';
  const columns: Column<ProfitRow>[] = [
    trip ? {
      key: 'label', header: tx('Trip'),
      render: (r) => (r.key === 'total' ? <span className="font-semibold">{tx('Total')}</span> : (
        <div className="min-w-[180px]">
          <Link className="font-medium text-primary hover:underline" to={`/fleet/trips/${r.tripId}`}>{r.label}</Link>
          <div className="text-xs text-muted-foreground">{r.deliveryOrder} · {r.customer}{r.site ? ` · ${r.site}` : ''}</div>
        </div>
      )),
    } : label<ProfitRow>(),
    ...(trip ? [
      { key: 'day', header: tx('Delivered'), render: (r: ProfitRow) => (r.day ? date(r.day) : '') },
      {
        key: 'carrier', header: tx('Carrier'),
        render: (r: ProfitRow) => (r.carrier ? (r.carrier === 'own' ? r.vehicle ?? tx('Own fleet') : `${tx('Transporter')} · ${r.transporter ?? ''}`) : ''),
      },
    ] : []),
    { key: 'trips', header: tx('Trips'), className: right, render: (r) => r.trips },
    { key: 'tonnes', header: tx('Tonnes'), className: right, render: (r) => n1(r.tonnes) },
    { key: 'revenue', header: tx('Goods revenue'), className: right, render: (r) => money(r.revenue) },
    { key: 'charge', header: tx('Transport charge'), className: right, render: (r) => money(r.transportCharge) },
    { key: 'product', header: tx('Product cost'), className: right, render: (r) => <span title={r.productCostEstimated ? tx('Estimated at the product cost price') : undefined}>{money(r.productCost)}{r.productCostEstimated ? ' *' : ''}</span> },
    { key: 'tripCost', header: tx('Trip cost'), className: right, render: (r) => <span title={r.costBasis ? COST_BASIS_LABEL[r.costBasis] ?? r.costBasis : undefined}>{money(r.tripCost)}</span> },
    { key: 'margin', header: tx('Margin'), className: `${right} font-semibold`, render: (r) => <span className={Number(r.margin) < 0 ? 'text-rose-600' : ''}>{money(r.margin)}</span> },
    { key: 'pct', header: tx('Margin %'), className: right, render: (r) => pct(r.marginPct) },
  ];
  return <ReportTable columns={columns} rows={data?.rows ?? []} totals={data?.totals} loading={isLoading} error={isError}
    empty={tx('No deliveries in this period.')}
    footnote={tx('Own-fleet trip cost = the truck\'s cost of the period per {{basis}} × the trip, plus driver allowances claimed for the trip; transporter trips use the freight (billed amount when on a freight bill). * product cost estimated at cost price.', { basis: COST_BASIS_LABEL[data?.allocationBasis ?? 'km'] ?? 'km' })} />;
}

function DeliveryReport({ params }: { params: P }) {
  const { data, isLoading, isError } = useDeliveriesReport(params);
  const columns: Column<DeliveryRow>[] = [
    label<DeliveryRow>(),
    { key: 'trips', header: tx('Deliveries'), className: right, render: (r) => r.trips },
    { key: 'orders', header: tx('Delivery orders'), className: right, render: (r) => r.orders },
    { key: 'onTime', header: tx('On time'), className: right, render: (r) => r.onTime },
    { key: 'late', header: tx('Late'), className: right, render: (r) => <span className={r.late ? 'text-amber-600' : ''}>{r.late}</span> },
    { key: 'pct', header: tx('On time %'), className: `${right} font-semibold`, render: (r) => pct(r.onTimePct) },
    { key: 'tonnes', header: tx('Tonnes'), className: right, render: (r) => n1(r.tonnes) },
    { key: 'km', header: tx('km'), className: right, render: (r) => n0(r.km) },
  ];
  return <ReportTable columns={columns} rows={data?.rows ?? []} totals={data?.totals} loading={isLoading} error={isError}
    empty={tx('No deliveries in this period.')}
    footnote={tx('On time = proof of delivery by the requested day, and on that day by the end of the delivery window. Returned trips in the period: {{n}}.', { n: data?.totals?.returnedTrips ?? 0 })} />;
}

function TonnesReport({ params, groupBy }: { params: P; groupBy: string }) {
  const { data, isLoading, isError } = useTonnesReport(params);
  const columns: Column<TonnesRow>[] = [
    label<TonnesRow>(),
    { key: 'trips', header: tx('Trips'), className: right, render: (r) => r.trips },
    ...(groupBy === 'product' ? [{ key: 'qty', header: tx('Quantity'), className: right, render: (r: TonnesRow) => n2(r.quantity) }] : []),
    { key: 'tonnes', header: tx('Tonnes'), className: `${right} font-semibold`, render: (r) => n1(r.tonnes) },
    ...(groupBy !== 'product' ? [{ key: 'perTrip', header: tx('t / trip'), className: right, render: (r: TonnesRow) => n1(r.tonnesPerTrip) }] : []),
  ];
  return <ReportTable columns={columns} rows={data?.rows ?? []} totals={data?.totals} loading={isLoading} error={isError} empty={tx('No deliveries in this period.')} />;
}

function TransportCostReport({ params }: { params: P }) {
  const { data, isLoading, isError } = useTransportCost(params);
  const columns: Column<TransportCostRow>[] = [
    label<TransportCostRow>(),
    { key: 'trips', header: tx('Trips'), className: right, render: (r) => r.trips },
    { key: 'tonnes', header: tx('Tonnes'), className: right, render: (r) => n1(r.tonnes) },
    { key: 'km', header: tx('km'), className: right, render: (r) => n0(r.km) },
    { key: 'cost', header: tx('Transport cost'), className: right, render: (r) => money(r.transportCost) },
    { key: 'perT', header: tx('Cost / t'), className: `${right} font-semibold`, render: (r) => n2(r.costPerTonne) },
    { key: 'perKm', header: tx('Cost / km'), className: right, render: (r) => n2(r.costPerKm) },
    { key: 'charge', header: tx('Charged to customers'), className: right, render: (r) => money(r.transportCharge) },
    { key: 'rec', header: tx('Recovery %'), className: right, render: (r) => pct(r.recoveryPct) },
  ];
  return <ReportTable columns={columns} rows={data?.rows ?? []} totals={data?.totals} loading={isLoading} error={isError} empty={tx('No deliveries in this period.')} />;
}

function UtilisationReport({ params }: { params: P }) {
  const { data, isLoading, isError } = useUtilisation(params);
  const columns: Column<UtilisationRow>[] = [
    {
      key: 'label', header: tx('Vehicle / group'),
      render: (r) => (r.key === 'total' ? <span className="font-semibold">{tx('Total')}</span>
        : r.vehicleId ? <Link className="font-medium text-primary hover:underline" to={`/fleet/vehicles/${r.vehicleId}`}>{r.label}</Link>
          : <span className="font-medium">{r.label}</span>),
    },
    { key: 'units', header: tx('Units'), className: right, render: (r) => r.units },
    { key: 'active', header: tx('Active days'), className: right, render: (r) => `${r.activeDays} / ${r.periodDays}` },
    { key: 'util', header: tx('Utilisation %'), className: `${right} font-semibold`, render: (r) => pct(r.utilisationPct) },
    { key: 'avail', header: tx('Availability %'), className: right, render: (r) => pct(r.availabilityPct) },
    { key: 'down', header: tx('Downtime h'), className: right, render: (r) => n1(r.downtimeHours) },
    { key: 'trips', header: tx('Trips'), className: right, render: (r) => r.trips },
    { key: 'km', header: tx('km'), className: right, render: (r) => n0(r.km) },
    { key: 'hours', header: tx('Engine h'), className: right, render: (r) => n1(r.hours) },
    { key: 'tonnes', header: tx('Tonnes'), className: right, render: (r) => n1(r.tonnes) },
  ];
  return (
    <div className="space-y-4">
      <ReportTable columns={columns} rows={data?.rows ?? []} totals={data?.totals} loading={isLoading} error={isError}
        empty={tx('No vehicles in this period.')}
        footnote={tx('Active day = a trip dispatched or a check-out that day. Availability from recorded downtime (see Availability & Reliability).')} />
      {!!data?.downtimeByReason.length && (
        <Card>
          <CardHeader><CardTitle className="text-base">{tx('Downtime by reason')}</CardTitle></CardHeader>
          <CardContent className="grid grid-cols-2 gap-2 sm:grid-cols-4">
            {data.downtimeByReason.map((d) => (
              <div key={d.reason} className="rounded-md border px-3 py-2">
                <div className="text-xs text-muted-foreground">{d.reason.replace(/_/g, ' ')}</div>
                <div className="text-lg font-semibold tabular-nums">{n1(d.hours)} h</div>
                <div className="text-xs text-muted-foreground">{tx('{{n}} event(s)', { n: d.events })}</div>
              </div>
            ))}
          </CardContent>
        </Card>
      )}
    </div>
  );
}

function FuelReportView({ params }: { params: P }) {
  const { data, isLoading, isError } = useFuelReport(params);
  const columns: Column<FuelRow>[] = [
    label<FuelRow>(),
    { key: 'logs', header: tx('Logs'), className: right, render: (r) => r.logs },
    { key: 'litres', header: tx('Litres'), className: `${right} font-semibold`, render: (r) => n1(r.litres) },
    { key: 'station', header: tx('Station L'), className: right, render: (r) => n1(r.stationLitres) },
    { key: 'tank', header: tx('Tank L'), className: right, render: (r) => n1(r.tankLitres) },
    { key: 'cost', header: tx('Cost'), className: right, render: (r) => money(r.cost) },
    { key: 'km', header: tx('km'), className: right, render: (r) => n0(r.km) },
    { key: 'l100', header: tx('L/100 km'), className: right, render: (r) => n2(r.lPer100Km) },
    { key: 'lh', header: tx('L/h'), className: right, render: (r) => n2(r.lPerHour) },
    { key: 'perKm', header: tx('Cost / km'), className: right, render: (r) => n2(r.costPerKm) },
    { key: 'anom', header: tx('Open anomalies'), className: right, render: (r) => <span className={r.openAnomalies ? 'text-amber-600' : ''}>{r.openAnomalies}</span> },
  ];
  return <ReportTable columns={columns} rows={data?.rows ?? []} totals={data?.totals} loading={isLoading} error={isError} empty={tx('No posted fuel in this period.')}
    footnote={tx('Consumption over the measured full-to-full segments of the period.')} />;
}

function RenewalsReport({ params }: { params: P }) {
  const { data, isLoading, isError } = useRenewals(params);
  const tone = (s: RenewalRow['state']) => (s === 'expired' || s === 'overdue' ? 'danger' : 'warning');
  const columns: Column<RenewalRow & { key: string }>[] = [
    { key: 'due', header: tx('Due'), render: (r) => <span className="whitespace-nowrap">{r.dueOn ? date(r.dueOn) : '—'}</span> },
    { key: 'state', header: tx('State'), render: (r) => <StatusPill tone={tone(r.state)}>{RENEWAL_STATE_LABEL[r.state]}{r.daysLeft != null ? ` · ${r.daysLeft} d` : ''}</StatusPill> },
    { key: 'kind', header: tx('Kind'), render: (r) => RENEWAL_KIND_LABEL[r.kind] },
    { key: 'item', header: tx('Item'), render: (r) => r.item.replace(/_/g, ' ') },
    { key: 'who', header: tx('Vehicle / driver'), render: (r) => <Link className="text-primary hover:underline" to={r.link}>{r.label}</Link> },
    { key: 'ref', header: tx('Reference'), render: (r) => r.reference ?? '—' },
  ];
  return (
    <div className="space-y-4">
      {data && (
        <div className="grid grid-cols-2 gap-3 sm:grid-cols-4">
          {(['expired', 'overdue', 'expiring', 'due'] as const).map((s) => (
            <Card key={s}><CardContent className="pt-4">
              <div className="text-xs text-muted-foreground">{RENEWAL_STATE_LABEL[s]}</div>
              <div className={`text-2xl font-semibold tabular-nums ${data.counts[s] && (s === 'expired' || s === 'overdue') ? 'text-rose-600' : ''}`}>{data.counts[s]}</div>
            </CardContent></Card>
          ))}
        </div>
      )}
      <ReportTable columns={columns} rows={data?.rows ?? []} loading={isLoading} error={isError} empty={tx('Nothing to renew in this window.')} />
    </div>
  );
}

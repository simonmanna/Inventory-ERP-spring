import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Skeleton } from '@/components/ui/skeleton';
import { DataTable, type Column } from '@/components/data-table';
import { money } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';
import type { Vehicle } from '@/features/fleet/api';
import { useVehicleCostSummary, type CostRow } from '@/features/fleet/costing-api';
import { num } from './fleet-format';

const right = 'text-right tabular-nums whitespace-nowrap';

/** Vehicle detail tab (fleet 1.9): cost of ownership per month over the last 12 months and the unit rates. */
export function VehicleCostTab({ vehicle, active }: { vehicle: Vehicle; active: boolean }) {
  const { data, isLoading, isError } = useVehicleCostSummary(active ? vehicle.id : undefined, { groupBy: 'month' });
  if (!active) return null;
  if (isLoading) return <Skeleton className="h-40 w-full" />;
  if (isError || !data) return <p className="text-sm text-muted-foreground">{tx('Could not load the costs.')}</p>;
  const t = data.totals;
  const columns: Column<CostRow>[] = [
    { key: 'label', header: tx('Month'), render: (r) => <span className="font-medium">{r.label}</span> },
    { key: 'dep', header: tx('Depreciation'), className: right, render: (r) => money(r.depreciation) },
    { key: 'fuel', header: tx('Fuel'), className: right, render: (r) => money(r.fuel) },
    { key: 'mnt', header: tx('Maintenance'), className: right, render: (r) => money(r.maintenance) },
    { key: 'tyres', header: tx('Tyres'), className: right, render: (r) => money(r.tyres) },
    { key: 'ins', header: tx('Insurance & licences'), className: right, render: (r) => money(Number(r.insurance) + Number(r.compliance)) },
    { key: 'other', header: tx('Allowances, fines, incidents'), className: right, render: (r) => money(Number(r.allowances) + Number(r.fines) + Number(r.incidents)) },
    { key: 'total', header: tx('Total'), className: `${right} font-semibold`, render: (r) => money(r.total) },
    { key: 'km', header: tx('km'), className: right, render: (r) => num(r.km, 0) },
    { key: 'perKm', header: tx('Cost / km'), className: right, render: (r) => num(r.perKm, 2) },
  ];
  const tiles = [
    { label: tx('Cost of ownership (12 months)'), value: money(t.total) },
    { label: tx('Cost / km'), value: num(t.perKm, 2) },
    { label: tx('Cost / engine hour'), value: num(t.perHour, 2) },
    { label: tx('Cost / tonne delivered'), value: num(t.perTonne, 2) },
    { label: tx('Cost / trip'), value: num(t.perTrip, 2) },
  ];
  return (
    <div className="space-y-4">
      <div className="grid grid-cols-2 gap-3 md:grid-cols-5">
        {tiles.map((x) => (
          <Card key={x.label}><CardContent className="space-y-0.5 pt-4">
            <div className="text-xs text-muted-foreground">{x.label}</div>
            <div className="text-xl font-semibold tabular-nums">{x.value}</div>
          </CardContent></Card>
        ))}
      </div>
      <Card>
        <CardHeader><CardTitle className="text-base">{tx('By month')}</CardTitle></CardHeader>
        <CardContent className="overflow-x-auto p-0">
          <DataTable columns={columns} data={data.rows} getRowId={(r) => r.key} cellClassName="py-2 px-3" headerRowClassName="h-10" emptyMessage={tx('No costs recorded.')} />
        </CardContent>
      </Card>
    </div>
  );
}

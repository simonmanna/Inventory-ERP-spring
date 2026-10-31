import { useState } from 'react';
import { Coins } from 'lucide-react';
import { Input } from '@/components/ui/input';
import { DataTable, type Column } from '@/components/data-table';
import { FilterSelect, ListCard, ListPageHeader, ListToolbar } from '@/components/list';
import { money } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';
import { useFleetLookups } from '@/features/fleet/api';
import { usePlantClasses, usePlantCosts, type PlantCostRow } from '@/features/fleet/plant-api';
import { num } from '../fleet-format';

const right = 'text-right tabular-nums whitespace-nowrap';
const iso = (d: Date) => d.toISOString().slice(0, 10);

/** Operating cost per piece of fixed plant over a period (CMMS-1). */
export function PlantCostsPage() {
  const lookups = useFleetLookups();
  const classes = usePlantClasses();
  const [from, setFrom] = useState(iso(new Date(Date.now() - 29 * 86_400_000)));
  const [to, setTo] = useState(iso(new Date()));
  const [categoryId, setCategoryId] = useState('');
  const [locationId, setLocationId] = useState('');
  const { data, isLoading, isError } = usePlantCosts({
    from, to, categoryId: categoryId || undefined, locationId: locationId || undefined,
  });
  const columns: Column<PlantCostRow>[] = [
    { key: 'code', header: tx('Code'), render: (r) => <span className="font-medium">{r.code}</span> },
    { key: 'name', header: tx('Equipment'), render: (r) => r.name ?? '—' },
    { key: 'class', header: tx('Class'), render: (r) => r.categoryName ?? '—' },
    { key: 'dep', header: tx('Depreciation'), className: right, render: (r) => money(r.depreciation) },
    { key: 'fuel', header: tx('Fuel'), className: right, render: (r) => money(r.fuel) },
    { key: 'mnt', header: tx('Maintenance'), className: right, render: (r) => money(r.maintenance) },
    { key: 'total', header: tx('Total'), className: `${right} font-semibold`, render: (r) => money(r.total) },
    { key: 'hours', header: tx('Hours'), className: right, render: (r) => num(r.hours) },
    { key: 'perHour', header: tx('Cost / engine hour'), className: right, render: (r) => num(r.perHour, 2) },
    { key: 'wo', header: tx('Work orders'), className: right, render: (r) => r.workOrders },
    { key: 'down', header: tx('Downtime (h)'), className: right, render: (r) => num(r.downtimeHours, 2) },
  ];
  const t = data?.totals;
  return (
    <div className="space-y-4">
      <ListPageHeader icon={Coins} title={tx('Fixed Plant Costs')}
        description={tx('Depreciation, fuel and maintenance per piece of equipment, with running hours and downtime.')} />
      <ListToolbar>
        <Input type="date" className="w-40" value={from} onChange={(e) => setFrom(e.target.value)} aria-label={tx('From')} />
        <Input type="date" className="w-40" value={to} onChange={(e) => setTo(e.target.value)} aria-label={tx('To')} />
        <FilterSelect value={categoryId} onChange={setCategoryId} options={(classes.data ?? []).map((c) => ({ value: c.id, label: c.name }))} allLabel={tx('All classes')} />
        <FilterSelect value={locationId} onChange={setLocationId} options={(lookups.data?.locations ?? []).map((l) => ({ value: l.id, label: l.name }))} allLabel={tx('All locations')} />
      </ListToolbar>
      {t && (
        <p className="text-sm text-muted-foreground">
          {tx('Total')}: <span className="font-medium text-foreground">{money(t.total)}</span>
          {' · '}{tx('Cost / engine hour')}: <span className="font-medium text-foreground">{num(t.perHour, 2)}</span>
          {' · '}{tx('Downtime (h)')}: <span className="font-medium text-foreground">{num(t.downtimeHours, 2)}</span>
        </p>
      )}
      <ListCard>
        <DataTable
          columns={columns}
          data={data?.rows ?? []}
          loading={isLoading}
          getRowId={(r) => r.vehicleId ?? ''}
          cellClassName="py-2.5 px-4"
          headerRowClassName="h-10"
          emptyMessage={isError ? tx('Could not load the costs.') : tx('No fixed plant registered yet.')}
        />
      </ListCard>
    </div>
  );
}

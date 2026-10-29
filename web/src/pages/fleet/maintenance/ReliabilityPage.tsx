import { useState } from 'react';
import { Activity } from 'lucide-react';
import { Input } from '@/components/ui/input';
import { DataTable, type Column } from '@/components/data-table';
import { FilterSelect, ListCard, ListPageHeader, ListToolbar } from '@/components/list';
import { tx } from '@/lib/i18n/tx';
import { useFleetLookups } from '@/features/fleet/api';
import { useReliability, type ReliabilityReport } from '@/features/fleet/maintenance-api';
import { num } from '../fleet-format';
import { unitLabel } from './maint-ui';

type Row = ReliabilityReport['rows'][number];

const iso = (d: Date) => d.toISOString().slice(0, 10);

/** Availability %, MTBF and MTTR per vehicle or class over a period (fleet 1.6). */
export function ReliabilityPage() {
  const lookups = useFleetLookups();
  const [from, setFrom] = useState(iso(new Date(Date.now() - 29 * 86_400_000)));
  const [to, setTo] = useState(iso(new Date()));
  const [groupBy, setGroupBy] = useState('');
  const [categoryId, setCategoryId] = useState('');
  const { data, isLoading, isError } = useReliability({ from, to, groupBy: groupBy || undefined, categoryId: categoryId || undefined });
  const byClass = data?.groupBy === 'class';

  const columns: Column<Row>[] = [
    byClass
      ? { key: 'class', header: tx('Class'), render: (r) => <span className="font-medium">{r.category?.name}</span> }
      : { key: 'unit', header: tx('Vehicle / equipment'), render: (r) => <span className="font-medium">{unitLabel(r.vehicle)}</span> },
    ...(byClass ? [{ key: 'units', header: tx('Units'), className: 'text-right', render: (r: Row) => r.units ?? '—' }] : []),
    { key: 'avail', header: tx('Availability %'), className: 'text-right', render: (r) => <span className="tabular-nums font-medium">{r.availabilityPct === null ? '—' : `${num(r.availabilityPct, 2)} %`}</span> },
    { key: 'down', header: tx('Downtime (h)'), className: 'text-right', render: (r) => <span className="tabular-nums">{num(r.downtimeHours, 2)}</span> },
    { key: 'unplanned', header: tx('Breakdown (h)'), className: 'text-right', render: (r) => <span className="tabular-nums">{num(r.unplannedHours, 2)}</span> },
    { key: 'failures', header: tx('Failures'), className: 'text-right', render: (r) => r.failures },
    { key: 'mtbf', header: tx('MTBF (h)'), className: 'text-right', render: (r) => <span className="tabular-nums">{num(r.mtbfHours, 2)}</span> },
    { key: 'mttr', header: tx('MTTR (h)'), className: 'text-right', render: (r) => <span className="tabular-nums">{num(r.mttrHours, 2)}</span> },
  ];
  const t = data?.total;

  return (
    <div className="space-y-4">
      <ListPageHeader icon={Activity} title={tx('Availability & Reliability')}
        description={tx('Availability = time in service ÷ elapsed time. MTBF = uptime per breakdown. MTTR = repair time per breakdown.')} />
      <ListToolbar>
        <Input type="date" className="w-40" value={from} onChange={(e) => setFrom(e.target.value)} aria-label={tx('From')} />
        <Input type="date" className="w-40" value={to} onChange={(e) => setTo(e.target.value)} aria-label={tx('To')} />
        <FilterSelect value={groupBy} onChange={setGroupBy} options={[{ value: 'class', label: tx('By class') }]} allLabel={tx('By vehicle')} />
        <FilterSelect value={categoryId} onChange={setCategoryId} options={(lookups.data?.categories ?? []).map((c) => ({ value: c.id, label: c.name }))} allLabel={tx('All classes')} />
      </ListToolbar>
      {t && (
        <div className="grid grid-cols-2 gap-3 sm:grid-cols-4">
          <Kpi label={tx('Fleet availability')} value={t.availabilityPct === null ? '—' : `${num(t.availabilityPct, 2)} %`} />
          <Kpi label={tx('Failures')} value={String(t.failures)} />
          <Kpi label={tx('MTBF (h)')} value={num(t.mtbfHours, 2)} />
          <Kpi label={tx('MTTR (h)')} value={num(t.mttrHours, 2)} />
        </div>
      )}
      <ListCard>
        <DataTable columns={columns} data={data?.rows ?? []} loading={isLoading}
          getRowId={(r) => r.vehicle?.id ?? r.category?.id ?? ''}
          emptyMessage={isError ? tx('Could not load the report.') : tx('No vehicles in this selection.')} />
      </ListCard>
    </div>
  );
}

function Kpi({ label, value }: { label: string; value: string }) {
  return (
    <div className="rounded-lg border bg-card p-3">
      <div className="text-xs text-muted-foreground">{label}</div>
      <div className="text-xl font-semibold tabular-nums">{value}</div>
    </div>
  );
}

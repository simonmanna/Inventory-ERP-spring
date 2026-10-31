import { useState } from 'react';
import { BarChart3 } from 'lucide-react';
import { Input } from '@/components/ui/input';
import { Skeleton } from '@/components/ui/skeleton';
import { ListPageHeader } from '@/components/list';
import { money } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';
import { Field, OptionalSelect } from '@/pages/fleet/fleet-ui';
import { COST_KIND_LABEL, ROLE_LABEL, useCrushingLookups, useCrushingReport, type FractionRole } from '@/features/crushing/api';
import { Section, pct, qty, today, tonnes } from './crushing-ui';

type Row = Record<string, string | number | null | Array<Record<string, string>>>;
const v = (r: Row, k: string) => r[k] as string | null;

function Table({ head, rows, render }: { head: string[]; rows: Row[]; render: (r: Row) => Array<string | number | null> }) {
  if (!rows.length) return <p className="text-sm text-muted-foreground">{tx('No posted run in this period.')}</p>;
  return (
    <div className="overflow-x-auto">
      <table className="w-full text-sm">
        <thead className="text-left text-xs text-muted-foreground"><tr>{head.map((h, i) => <th key={h} className={`py-1.5 ${i ? 'text-right' : ''}`}>{h}</th>)}</tr></thead>
        <tbody>{rows.map((r, i) => <tr key={i} className="border-t">{render(r).map((c, j) => <td key={j} className={`py-1.5 ${j ? 'text-right tabular-nums' : ''}`}>{c ?? '—'}</td>)}</tr>)}</tbody>
      </table>
    </div>
  );
}

/** Yield & loss, cost per tonne and plant hours / throughput over posted crushing runs. */
export function CrushingReportsPage() {
  const lookups = useCrushingLookups();
  const [from, setFrom] = useState(today().slice(0, 8) + '01');
  const [to, setTo] = useState(today());
  const [plantId, setPlantId] = useState('');
  const p = { from, to, plantId };
  const yieldQ = useCrushingReport('yield', p);
  const costQ = useCrushingReport('cost-per-tonne', p);
  const hoursQ = useCrushingReport('plant-hours', p);
  const rows = (q: { data?: Record<string, unknown> }, k: string) => ((q.data?.[k] as Row[] | undefined) ?? []);
  return (
    <div className="space-y-4">
      <ListPageHeader icon={BarChart3} title={tx('Crushing Reports')} description={tx('Expected vs actual yield, cost per tonne by product and plant, plant hours and throughput.')} />
      <div className="grid grid-cols-1 gap-3 sm:grid-cols-3">
        <Field label={tx('From')}><Input type="date" value={from} onChange={(e) => setFrom(e.target.value)} /></Field>
        <Field label={tx('To')}><Input type="date" value={to} onChange={(e) => setTo(e.target.value)} /></Field>
        <Field label={tx('Plant')}><OptionalSelect value={plantId} onChange={setPlantId} placeholder={tx('All plants')} noneLabel={tx('All plants')} options={(lookups.data?.plants ?? []).map((x) => ({ value: x.id, label: x.code }))} /></Field>
      </div>
      <Section title={tx('Yield and loss')}>
        {yieldQ.isLoading ? <Skeleton className="h-24 w-full" /> : <>
          <Table head={[tx('Plant'), tx('Runs'), tx('Feed'), tx('Output'), tx('Loss'), tx('Expected'), tx('Actual'), tx('Circulating load')]} rows={rows(yieldQ, 'plants')}
            render={(r) => [v(r, 'plantCode'), v(r, 'runs'), tonnes(v(r, 'feedTonnes')), tonnes(v(r, 'outputTonnes')), tonnes(v(r, 'lossTonnes')), pct(v(r, 'expectedYieldPct')), pct(v(r, 'actualYieldPct')), pct(v(r, 'circulatingLoadPct'))]} />
          <Table head={[tx('Product'), tx('Role'), tx('Expected'), tx('Actual'), tx('Variance'), tx('Actual %')]} rows={rows(yieldQ, 'products')}
            render={(r) => [v(r, 'productName'), ROLE_LABEL[v(r, 'role') as FractionRole] ?? v(r, 'role'), tonnes(v(r, 'expectedTonnes')), tonnes(v(r, 'actualTonnes')), tonnes(v(r, 'varianceTonnes')), pct(v(r, 'actualPct'))]} />
        </>}
      </Section>
      <Section title={tx('Cost per tonne')}>
        {costQ.isLoading ? <Skeleton className="h-24 w-full" /> : <>
          <Table head={[tx('Product'), tx('Tonnes'), tx('Value'), tx('Per tonne')]} rows={rows(costQ, 'products')}
            render={(r) => [v(r, 'productName'), tonnes(v(r, 'tonnes')), money(v(r, 'value')), money(v(r, 'costPerTonne'))]} />
          <Table head={[tx('Plant'), tx('Feed cost'), tx('Conversion cost'), tx('Abnormal loss written off'), tx('Total cost'), tx('Per tonne'), tx('Cost by kind')]} rows={rows(costQ, 'plants')}
            render={(r) => [v(r, 'plantCode'), money(v(r, 'feedCost')), money(v(r, 'conversionCost')), money(v(r, 'abnormalLossValue')), money(v(r, 'totalCost')), money(v(r, 'costPerTonne')),
              ((r.byKind as Array<Record<string, string>>) ?? []).map((k) => `${COST_KIND_LABEL[k.kind] ?? k.kind} ${money(k.amount)}`).join(' · ')]} />
        </>}
      </Section>
      <Section title={tx('Plant hours and throughput')}>
        {hoursQ.isLoading ? <Skeleton className="h-24 w-full" /> : <>
          <Table head={[tx('Plant'), tx('Operating hours'), tx('Downtime hours'), tx('Availability'), tx('Throughput'), tx('Utilisation')]} rows={rows(hoursQ, 'plants')}
            render={(r) => [v(r, 'plantCode'), qty(v(r, 'operatingHours')), qty(v(r, 'downtimeHours')), pct(v(r, 'availabilityPct')), v(r, 'throughputTph') ? `${qty(v(r, 'throughputTph'))} t/h` : null, pct(v(r, 'utilisationPct'))]} />
          <Table head={[tx('Unit'), tx('Hours'), 'kWh', tx('Diesel litres'), tx('Throughput'), tx('kWh per tonne')]} rows={rows(hoursQ, 'units')}
            render={(r) => [`${v(r, 'plantCode')} · ${v(r, 'unitCode')} · ${v(r, 'unitName')}`, qty(v(r, 'runHours')), qty(v(r, 'kwh')), qty(v(r, 'dieselLitres')), v(r, 'throughputTph') ? `${qty(v(r, 'throughputTph'))} t/h` : null, qty(v(r, 'kwhPerTonne'), 4)]} />
        </>}
      </Section>
    </div>
  );
}

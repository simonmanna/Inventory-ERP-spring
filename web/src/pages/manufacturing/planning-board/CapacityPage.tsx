import { useState } from 'react';
import { AlertOctagon, Gauge } from 'lucide-react';
import { Badge } from '@/components/ui/badge';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { DateRangeFilter, FilterSelect, ListToolbar } from '@/components/list';
import { cn } from '@/lib/utils';
import { EmptyState, MfgPageHeader, PageSkeleton, ProgressBar, TONE_PILL } from '../mfg-ui';
import { tx } from '@/lib/i18n/tx';
import { pct, qty, useAggregates, useCapacity, type CapacityBucket, type CapacityRow } from '@/features/manufacturing/api-planning';
import { addDays, ymd } from './planning-ui';

const utilTone = (v: unknown) => {
  const x = Number(v ?? 0);
  return x > 100 ? 'danger' as const : x >= 85 ? 'warning' as const : x > 0 ? 'success' as const : 'muted' as const;
};

/** Heat map: one cell per work centre and period, coloured and labelled by utilisation. */
function HeatMap({ rows }: { rows: CapacityRow[] }) {
  const periods = rows[0]?.buckets ?? [];
  if (periods.length === 0) return null;
  const cell = (b: CapacityBucket) => {
    const v = Number(b.utilisationPct ?? 0);
    if (b.utilisationPct == null) return 'bg-muted text-muted-foreground';
    if (b.overloaded || v > 100) return 'bg-rose-500 text-white';
    if (v >= 85) return 'bg-amber-400 text-amber-950';
    if (v >= 50) return 'bg-emerald-500 text-white';
    if (v > 0) return 'bg-emerald-200 text-emerald-900 dark:bg-emerald-900 dark:text-emerald-100';
    return 'bg-muted/60 text-muted-foreground';
  };
  return (
    <div className="mb-5">
      <div className="mb-2 flex flex-wrap items-center gap-3 text-[11px] text-muted-foreground">
        <span className="font-medium text-foreground">{tx('Load by period')}</span>
        {[['bg-emerald-200 dark:bg-emerald-900', '< 50%'], ['bg-emerald-500', '50–85%'], ['bg-amber-400', '85–100%'], ['bg-rose-500', '> 100%']].map(([c, l]) => (
          <span key={l} className="inline-flex items-center gap-1"><span className={cn('h-3 w-3 rounded-sm', c)} aria-hidden />{l}</span>
        ))}
      </div>
      <div className="overflow-x-auto">
        <table className="border-separate border-spacing-1 text-[11px]">
          <thead>
            <tr>
              <th className="sticky left-0 z-[1] bg-card" />
              {periods.map((b) => <th key={b.from} className="min-w-11 px-0.5 font-medium text-muted-foreground">{b.from.slice(5, 10)}</th>)}
            </tr>
          </thead>
          <tbody>
            {rows.map((w) => (
              <tr key={w.workCenterId}>
                <th className="sticky left-0 z-[1] max-w-36 truncate bg-card pr-2 text-left text-xs font-medium" title={w.name}>{w.name}</th>
                {w.buckets.map((b) => (
                  <td key={b.from} title={`${w.name} · ${b.from}: ${pct(b.utilisationPct)} (${qty(b.loadHours)} / ${qty(b.capacityHours)} h)`}
                    className={cn('h-9 min-w-11 rounded-md text-center font-semibold tabular-nums', cell(b))}>
                    {b.utilisationPct == null ? '—' : `${Math.round(Number(b.utilisationPct))}`}{b.overloaded && '!'}
                  </td>
                ))}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}

/** Load vs capacity with the bottleneck, crushing circuits and the aggregate plan (ADR-S019). */
export function CapacityPage() {
  const today = new Date();
  const [from, setFrom] = useState(ymd(today));
  const [to, setTo] = useState(ymd(addDays(today, 13)));
  const [bucket, setBucket] = useState('day');
  const { data, isLoading, isError } = useCapacity({ from, to, bucket });
  const aggregates = useAggregates({ from, to });
  return (
    <div className="space-y-4 p-4 sm:p-6">
      <MfgPageHeader icon={Gauge} title={tx('Capacity Planning')}
        subtitle={tx('Load against capacity per work centre in hours and tonnes, with the bottleneck.')} />
      <ListToolbar>
        <DateRangeFilter from={from} to={to} onChange={(f, t) => { if (f && t) { setFrom(f); setTo(t); } }} />
        <FilterSelect value={bucket} onChange={(v) => setBucket(v || 'day')} allLabel={tx('Period')}
          options={[{ value: 'day', label: tx('By day') }, { value: 'week', label: tx('By week') }]} />
      </ListToolbar>
      {isLoading && <PageSkeleton />}
      {isError && <p className="text-destructive">{tx('Could not load capacity.')}</p>}
      {data?.bottleneck && (
        <p className={cn('flex items-center gap-2 rounded-xl px-4 py-3 text-sm font-medium ring-1 ring-inset', TONE_PILL.danger)}>
          <AlertOctagon className="h-5 w-5 shrink-0" aria-hidden />
          {tx('Bottleneck: {{name}} at {{pct}}', { name: data.bottleneck.name, pct: pct(data.bottleneck.utilisationPct) })}
        </p>
      )}
      {data && (
        <Card>
          <CardHeader><CardTitle>{tx('Work centres')}</CardTitle></CardHeader>
          <CardContent className="overflow-x-auto">
            {data.workCenters.length === 0 ? <EmptyState compact icon={Gauge} title={tx('No active work centres.')} /> : (<>
              <HeatMap rows={data.workCenters} />
              <Table>
                <TableHeader><TableRow>
                  <TableHead>{tx('Work centre')}</TableHead><TableHead className="text-right">{tx('Capacity (h)')}</TableHead>
                  <TableHead className="text-right">{tx('Load (h)')}</TableHead><TableHead className="text-right">{tx('Unscheduled (h)')}</TableHead>
                  <TableHead className="text-right">{tx('Utilisation')}</TableHead><TableHead className="text-right">{tx('Capacity (qty)')}</TableHead>
                  <TableHead className="text-right">{tx('Load (qty)')}</TableHead><TableHead>{tx('Load by period')}</TableHead>
                </TableRow></TableHeader>
                <TableBody>
                  {data.workCenters.map((w) => (
                    <TableRow key={w.workCenterId}>
                      <TableCell>{w.name}{w.isBottleneck && <Badge variant="destructive" className="ml-2">{tx('Bottleneck')}</Badge>}</TableCell>
                      <TableCell className="text-right tabular-nums">{qty(w.capacityHours)}</TableCell>
                      <TableCell className="text-right tabular-nums">{qty(w.loadHours)}</TableCell>
                      <TableCell className="text-right tabular-nums">{qty(w.unscheduledHours)}</TableCell>
                      <TableCell className="min-w-32 text-right">{pct(w.utilisationPct)}<ProgressBar className="mt-1" size="xs" value={Number(w.utilisationPct ?? 0)} tone={utilTone(w.utilisationPct)} ariaLabel={tx('Utilisation')} /></TableCell>
                      <TableCell className="text-right tabular-nums">{qty(w.capacityQty)}</TableCell>
                      <TableCell className="text-right tabular-nums">{qty(w.loadQty)}</TableCell>
                      <TableCell>
                        <div className="flex h-6 items-end gap-px">
                          {w.buckets.map((b) => (
                            <div key={b.from} title={`${b.from}: ${pct(b.utilisationPct)}`}
                              className={`w-2 ${b.overloaded ? 'bg-destructive' : 'bg-primary'}`}
                              style={{ height: `${Math.max(4, Math.min(100, Number(b.utilisationPct ?? 0)))}%` }} />
                          ))}
                        </div>
                      </TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            </>)}
          </CardContent>
        </Card>
      )}
      {data && data.plants.length > 0 && (
        <Card>
          <CardHeader><CardTitle>{tx('Crushing circuits')}</CardTitle></CardHeader>
          <CardContent className="overflow-x-auto">
            <Table>
              <TableHeader><TableRow>
                <TableHead>{tx('Plant')}</TableHead><TableHead>{tx('Bottleneck')}</TableHead><TableHead className="text-right">{tx('t/h')}</TableHead>
                <TableHead className="text-right">{tx('Hours available')}</TableHead><TableHead className="text-right">{tx('Capacity (t)')}</TableHead>
              </TableRow></TableHeader>
              <TableBody>
                {data.plants.map((p) => (
                  <TableRow key={p.plantId}>
                    <TableCell>{p.name}</TableCell><TableCell>{p.bottleneck ?? '-'}</TableCell>
                    <TableCell className="text-right tabular-nums">{qty(p.bottleneckTph)}</TableCell>
                    <TableCell className="text-right tabular-nums">{qty(p.availableHours)}</TableCell>
                    <TableCell className="text-right tabular-nums">{qty(p.capacityTonnes)}</TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </CardContent>
        </Card>
      )}
      {aggregates.data?.plans.map((p) => (
        <Card key={p.profileId}>
          <CardHeader>
            <CardTitle>{p.plantName} · {p.profileName}</CardTitle>
            <p className="text-sm text-muted-foreground">
              {tx('Feed {{feed}} t of {{product}}, {{stock}} t in stock, {{hours}} h needed, {{available}} h available', {
                feed: qty(p.feedTonnes), product: p.feedProductName, stock: qty(p.feedOnHand), hours: qty(p.hoursRequired), available: qty(p.hoursAvailable),
              })}
              {p.feasible === false && <Badge variant="destructive" className="ml-2">{tx('Over capacity')}</Badge>}
              {Number(p.feedShortfall) > 0 && <Badge variant="destructive" className="ml-2">{tx('Not enough feed')}</Badge>}
            </p>
          </CardHeader>
          <CardContent className="overflow-x-auto">
            <Table>
              <TableHeader><TableRow>
                <TableHead>{tx('Product')}</TableHead><TableHead className="text-right">{tx('Demand')}</TableHead>
                <TableHead className="text-right">{tx('On hand')}</TableHead><TableHead className="text-right">{tx('To make')}</TableHead>
                <TableHead className="text-right">{tx('Expected output')}</TableHead><TableHead className="text-right">{tx('Surplus')}</TableHead>
              </TableRow></TableHeader>
              <TableBody>
                {p.fractions.map((f) => (
                  <TableRow key={f.productId}>
                    <TableCell>{f.name}</TableCell><TableCell className="text-right tabular-nums">{qty(f.demandQty)}</TableCell>
                    <TableCell className="text-right tabular-nums">{qty(f.onHandQty)}</TableCell>
                    <TableCell className="text-right tabular-nums">{qty(f.netDemandQty)}</TableCell>
                    <TableCell className="text-right tabular-nums">{qty(f.expectedOutputQty)}</TableCell>
                    <TableCell className="text-right tabular-nums">{qty(f.surplusQty)}</TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </CardContent>
        </Card>
      ))}
    </div>
  );
}

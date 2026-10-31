import { useState } from 'react';
import { Gauge } from 'lucide-react';
import { Badge } from '@/components/ui/badge';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { DateRangeFilter, FilterSelect, ListPageHeader, ListToolbar } from '@/components/list';
import { tx } from '@/lib/i18n/tx';
import { pct, qty, useAggregates, useCapacity } from '@/features/manufacturing/api-planning';
import { addDays, ymd } from './planning-ui';

/** Load vs capacity with the bottleneck, crushing circuits and the aggregate plan (ADR-S019). */
export function CapacityPage() {
  const today = new Date();
  const [from, setFrom] = useState(ymd(today));
  const [to, setTo] = useState(ymd(addDays(today, 13)));
  const [bucket, setBucket] = useState('day');
  const { data, isLoading, isError } = useCapacity({ from, to, bucket });
  const aggregates = useAggregates({ from, to });
  return (
    <div className="space-y-4">
      <ListPageHeader icon={Gauge} title={tx('Capacity Planning')}
        description={tx('Load against capacity per work centre in hours and tonnes, with the bottleneck.')} />
      <ListToolbar>
        <DateRangeFilter from={from} to={to} onChange={(f, t) => { if (f && t) { setFrom(f); setTo(t); } }} />
        <FilterSelect value={bucket} onChange={(v) => setBucket(v || 'day')} allLabel={tx('Period')}
          options={[{ value: 'day', label: tx('By day') }, { value: 'week', label: tx('By week') }]} />
      </ListToolbar>
      {isLoading && <p className="text-muted-foreground">{tx('Loading…')}</p>}
      {isError && <p className="text-destructive">{tx('Could not load capacity.')}</p>}
      {data?.bottleneck && (
        <p className="text-sm">{tx('Bottleneck: {{name}} at {{pct}}', { name: data.bottleneck.name, pct: pct(data.bottleneck.utilisationPct) })}</p>
      )}
      {data && (
        <Card>
          <CardHeader><CardTitle>{tx('Work centres')}</CardTitle></CardHeader>
          <CardContent className="overflow-x-auto">
            {data.workCenters.length === 0 ? <p className="text-sm text-muted-foreground">{tx('No active work centres.')}</p> : (
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
                      <TableCell className="text-right">{pct(w.utilisationPct)}</TableCell>
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
            )}
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

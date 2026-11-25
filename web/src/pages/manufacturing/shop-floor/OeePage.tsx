import { useState } from 'react';
import { Link } from 'react-router-dom';
import { TrendingUp } from 'lucide-react';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { DateRangeFilter, FilterSelect, ListToolbar } from '@/components/list';
import { Gauge } from '@/components/ops-dashboard/charts';
import { EmptyState, MfgPageHeader, PageSkeleton, pctTone, ProgressBar } from '../mfg-ui';
import { date } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';
import { useWorkCenters } from '@/features/manufacturing/api';
import { minutesLabel, pct, qty, useOee } from '@/features/manufacturing/api-shopfloor';
import { OeeTiles, ShiftReportStatusPill } from './shopfloor-ui';

const ymd = (d: Date) => d.toISOString().slice(0, 10);
const asPct = (v: unknown) => (v === null || v === undefined || v === '' ? null : Number(v) * 100);

/** OEE report (ADR-S015): availability × performance × quality per work centre and shift for a period. */
export function OeePage() {
  const today = new Date();
  const [from, setFrom] = useState(ymd(new Date(today.getTime() - 6 * 86_400_000)));
  const [to, setTo] = useState(ymd(today));
  const [workCenterId, setWorkCenterId] = useState('');
  const centres = useWorkCenters();
  const { data, isLoading, isError } = useOee({ from: from || undefined, to: to || undefined, workCenterId: workCenterId || undefined });
  return (
    <div className="space-y-4 p-4 sm:p-6">
      <MfgPageHeader icon={TrendingUp} title={tx('OEE')}
        subtitle={tx('Overall equipment effectiveness per work centre and shift, from shift reports (at most 93 days).')} />
      <ListToolbar>
        <DateRangeFilter from={from} to={to} onChange={(f, t) => { setFrom(f); setTo(t); }} />
        <FilterSelect value={workCenterId} onChange={setWorkCenterId} allLabel={tx('All work centres')}
          options={(centres.data ?? []).map((c: any) => ({ value: c.id, label: c.name }))} />
      </ListToolbar>
      {isLoading && <PageSkeleton />}
      {isError && <p className="text-destructive">{tx('Could not load the OEE report.')}</p>}
      {data && (
        <>
          <Card>
            <CardHeader><CardTitle>{tx('All work centres · {{n}} shifts', { n: data.total.shifts })}</CardTitle></CardHeader>
            <CardContent className="space-y-5">
              <div className="grid grid-cols-2 gap-4 md:grid-cols-4">
                <Gauge label={tx('OEE')} value={asPct(data.total.oee)} threshold={{ good: 85, warn: 60 }} size={128} sub={tx('Target 85%')} />
                <Gauge label={tx('Availability')} value={asPct(data.total.availability)} threshold={{ good: 90, warn: 75 }} size={112}
                  sub={tx('{{run}} of {{planned}} min running', { run: data.total.runMinutes, planned: data.total.plannedMinutes })} />
                <Gauge label={tx('Performance')} value={asPct(data.total.performance)} threshold={{ good: 95, warn: 75 }} size={112} />
                <Gauge label={tx('Quality')} value={asPct(data.total.quality)} threshold={{ good: 99, warn: 95 }} size={112}
                  sub={tx('{{good}} good of {{total}}', { good: Number(data.total.goodQty), total: Number(data.total.totalQty) })} />
              </div>
              <p className="text-center text-xs text-muted-foreground">{tx('OEE = availability × performance × quality')}</p>
              <OeeTiles oee={data.total} compact />
            </CardContent>
          </Card>
          <Card>
            <CardHeader><CardTitle>{tx('By work centre')}</CardTitle></CardHeader>
            <CardContent className="overflow-x-auto">
              {data.workCenters.length === 0 ? <EmptyState compact icon={TrendingUp} title={tx('No shift reports in this period.')} /> : (
                <Table>
                  <TableHeader><TableRow>
                    <TableHead>{tx('Work centre')}</TableHead><TableHead className="text-right">{tx('Shifts')}</TableHead>
                    <TableHead className="text-right">{tx('Planned')}</TableHead><TableHead className="text-right">{tx('Downtime')}</TableHead>
                    <TableHead className="text-right">{tx('Good')}</TableHead><TableHead className="text-right">{tx('Scrap')}</TableHead>
                    <TableHead className="text-right">{tx('Availability')}</TableHead><TableHead className="text-right">{tx('Performance')}</TableHead>
                    <TableHead className="text-right">{tx('Quality')}</TableHead><TableHead className="text-right">{tx('OEE')}</TableHead>
                  </TableRow></TableHeader>
                  <TableBody>
                    {data.workCenters.map((w) => (
                      <TableRow key={w.workCenterId}>
                        <TableCell>{w.workCenterName}<div className="text-xs text-muted-foreground">{w.workCenterCode}</div></TableCell>
                        <TableCell className="text-right">{w.shifts}</TableCell>
                        <TableCell className="text-right">{minutesLabel(w.plannedMinutes)}</TableCell>
                        <TableCell className="text-right">{minutesLabel(w.downtimeMinutes)}</TableCell>
                        <TableCell className="text-right tabular-nums">{qty(w.goodQty)}</TableCell>
                        <TableCell className="text-right tabular-nums">{qty(w.scrapQty)}</TableCell>
                        <TableCell className="text-right">{pct(w.availability)}</TableCell>
                        <TableCell className="text-right">{pct(w.performance)}</TableCell>
                        <TableCell className="text-right">{pct(w.quality)}</TableCell>
                        <TableCell className="min-w-36 text-right font-semibold">{pct(w.oee)}<ProgressBar className="mt-1" size="xs" value={asPct(w.oee)} tone={pctTone(asPct(w.oee))} ariaLabel={tx('OEE')} /></TableCell>
                      </TableRow>
                    ))}
                  </TableBody>
                </Table>
              )}
            </CardContent>
          </Card>
          <Card>
            <CardHeader><CardTitle>{tx('By shift')}</CardTitle></CardHeader>
            <CardContent className="overflow-x-auto">
              {data.shifts.length === 0 ? <p className="text-sm text-muted-foreground">{tx('No shift reports in this period.')}</p> : (
                <Table>
                  <TableHeader><TableRow>
                    <TableHead>{tx('Report')}</TableHead><TableHead>{tx('Date')}</TableHead><TableHead>{tx('Shift')}</TableHead>
                    <TableHead>{tx('Work centre')}</TableHead><TableHead className="text-right">{tx('A')}</TableHead>
                    <TableHead className="text-right">{tx('P')}</TableHead><TableHead className="text-right">{tx('Q')}</TableHead>
                    <TableHead className="text-right">{tx('OEE')}</TableHead><TableHead>{tx('Status')}</TableHead>
                  </TableRow></TableHeader>
                  <TableBody>
                    {data.shifts.map((s) => (
                      <TableRow key={s.shiftReportId}>
                        <TableCell><Link className="text-primary hover:underline" to={`/manufacturing/shift-reports/${s.shiftReportId}`}>{s.code}</Link></TableCell>
                        <TableCell className="whitespace-nowrap">{date(s.shiftDate)}</TableCell>
                        <TableCell>{s.shiftCode}</TableCell>
                        <TableCell>{s.workCenterName}</TableCell>
                        <TableCell className="text-right">{pct(s.availability)}</TableCell>
                        <TableCell className="text-right">{pct(s.performance)}</TableCell>
                        <TableCell className="text-right">{pct(s.quality)}</TableCell>
                        <TableCell className="text-right font-semibold">{pct(s.oee)}</TableCell>
                        <TableCell><ShiftReportStatusPill status={s.status} /></TableCell>
                      </TableRow>
                    ))}
                  </TableBody>
                </Table>
              )}
            </CardContent>
          </Card>
        </>
      )}
    </div>
  );
}

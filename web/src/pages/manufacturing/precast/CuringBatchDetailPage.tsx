import { useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { ArrowLeft } from 'lucide-react';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Skeleton } from '@/components/ui/skeleton';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { StatusPill } from '@/components/list';
import { dateTime } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';
import { useCuringBatch } from '@/features/manufacturing/api-precast';
import { Item, LoadError } from './precast-ui';
import { BatchCard } from './CuringBoardPage';
import { CuringActionDialog, type CuringAction } from './CuringActions';
import { num } from './precast-format';

/** Curing batch detail (2.4): actions, readings and moves history, cubing result, quality lots. */
export function CuringBatchDetailPage() {
  const { id = '' } = useParams<{ id: string }>();
  const { data: b, isLoading, isError, refetch } = useCuringBatch(id);
  const [action, setAction] = useState<CuringAction | null>(null);

  if (isLoading) return <div className="space-y-3"><Skeleton className="h-12 w-1/2" /><Skeleton className="h-64 w-full" /></div>;
  if (isError || !b) return <LoadError message={tx('Curing batch not found')} onRetry={() => refetch()} />;

  return (
    <div className="space-y-4">
      <div className="space-y-1">
        <Link to="/manufacturing/precast/curing" className="inline-flex items-center text-sm text-muted-foreground hover:underline">
          <ArrowLeft className="mr-1 h-4 w-4" /> {tx('Curing board')}
        </Link>
        <h1 className="text-2xl font-bold tracking-tight">{b.code}</h1>
        <p className="text-sm text-muted-foreground">
          <Link className="text-primary underline" to={`/manufacturing/precast/orders/${b.orderId}`}>{b.orderCode}</Link> · {b.productName}
        </p>
      </div>

      <div className="grid gap-4 lg:grid-cols-3">
        <div className="lg:col-span-1"><BatchCard batch={b} onAction={setAction} /></div>
        <Card className="lg:col-span-2">
          <CardHeader><CardTitle>{tx('Batch')}</CardTitle></CardHeader>
          <CardContent>
            <dl className="grid grid-cols-2 gap-3 sm:grid-cols-4">
              <Item label={tx('Batch number')}>{b.batchNumber}</Item>
              <Item label={tx('Pressed')}>{dateTime(b.startedAt)}</Item>
              <Item label={tx('Shift')}>{b.shift}</Item>
              <Item label={tx('Press cycles')}>{num(b.cycles, 0)}</Item>
              <Item label={tx('Broken at press')}>{num(b.brokenAtPress, 0)}</Item>
              <Item label={tx('Minimum curing days')}>{num(b.minCuringDays, 0)}</Item>
              <Item label={tx('Curing until')}>{b.curingUntil ? dateTime(b.curingUntil) : null}</Item>
              <Item label={tx('Last reading')}>{b.lastReadingAt ? `${num(b.lastTemperatureC, 1)} °C · ${dateTime(b.lastReadingAt)}` : null}</Item>
              <Item label={tx('Good')}>{num(b.qtyGood, 0)}</Item>
              <Item label={tx('Second grade')}>{num(b.qtySecondGrade, 0)}</Item>
              <Item label={tx('Broken at cubing')}>{num(b.qtyBroken, 0)}</Item>
              <Item label={tx('Scrap reason')}>{b.scrapReasonCode}</Item>
              <Item label={tx('Cubed')}>{b.cubedAt ? dateTime(b.cubedAt) : null}</Item>
              <Item label={tx('Released')}>{b.releasedAt ? dateTime(b.releasedAt) : null}</Item>
              <Item label={tx('QC reference')}>{b.qcReference}</Item>
              <Item label={tx('QC pass due')}>{b.qcPassDueAt ? dateTime(b.qcPassDueAt) : null}</Item>
            </dl>
            {b.notes && <p className="mt-3 text-sm text-muted-foreground">{b.notes}</p>}
          </CardContent>
        </Card>
      </div>

      <Card>
        <CardHeader><CardTitle>{tx('Readings and moves')}</CardTitle></CardHeader>
        <CardContent className="overflow-x-auto">
          {!b.readings.length ? <p className="text-sm text-muted-foreground">{tx('No readings yet.')}</p> : (
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>{tx('When')}</TableHead>
                  <TableHead>{tx('Kind')}</TableHead>
                  <TableHead className="text-right">{tx('Temperature (°C)')}</TableHead>
                  <TableHead className="text-right">{tx('Humidity %')}</TableHead>
                  <TableHead>{tx('Curing area')}</TableHead>
                  <TableHead>{tx('Notes')}</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {b.readings.map((r) => (
                  <TableRow key={r.id}>
                    <TableCell className="whitespace-nowrap">{dateTime(r.readAt)}</TableCell>
                    <TableCell>{r.kind === 'move' ? tx('Move') : tx('Reading')}</TableCell>
                    <TableCell className="text-right tabular-nums">{num(r.temperatureC, 1)}</TableCell>
                    <TableCell className="text-right tabular-nums">{num(r.humidityPct, 0)}</TableCell>
                    <TableCell>{r.curingAreaCode ?? '—'}{r.palletLocation ? ` · ${r.palletLocation}` : ''}</TableCell>
                    <TableCell className="text-sm text-muted-foreground">{r.notes ?? ''}</TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          )}
        </CardContent>
      </Card>

      <Card>
        <CardHeader><CardTitle>{tx('Quality lots')}</CardTitle></CardHeader>
        <CardContent className="overflow-x-auto">
          {!b.qualityLots.length ? <p className="text-sm text-muted-foreground">{tx('No quality lots for this batch.')}</p> : (
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>{tx('Lot')}</TableHead>
                  <TableHead>{tx('Stage')}</TableHead>
                  <TableHead className="text-right">{tx('Quantity')}</TableHead>
                  <TableHead>{tx('Result')}</TableHead>
                  <TableHead>{tx('Status')}</TableHead>
                  <TableHead>{tx('Opened')}</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {b.qualityLots.map((l) => (
                  <TableRow key={l.id}>
                    <TableCell><Link className="text-primary underline" to={`/quality/lots/${l.id}`}>{l.lotNumber}</Link></TableCell>
                    <TableCell>{l.stage.replace(/_/g, ' ')}</TableCell>
                    <TableCell className="text-right tabular-nums">{num(l.quantity, 0)}</TableCell>
                    <TableCell><StatusPill tone={l.result === 'pass' ? 'success' : l.result === 'fail' ? 'danger' : 'neutral'}>{l.result}</StatusPill></TableCell>
                    <TableCell>{l.status}</TableCell>
                    <TableCell className="whitespace-nowrap">{dateTime(l.createdAt)}</TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          )}
        </CardContent>
      </Card>

      {action && <CuringActionDialog action={action} batch={b} onClose={() => setAction(null)} />}
    </div>
  );
}

export default CuringBatchDetailPage;

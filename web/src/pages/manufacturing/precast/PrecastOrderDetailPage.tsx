import { useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { ArrowLeft, ExternalLink, Hammer } from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Textarea } from '@/components/ui/textarea';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Skeleton } from '@/components/ui/skeleton';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { useAuthStore } from '@/stores/auth.store';
import { notify, apiMessage } from '@/lib/notify';
import { date, dateTime, money, useOrgCurrency } from '@/lib/format';
import { cn } from '@/lib/utils';
import { tx } from '@/lib/i18n/tx';
import { useAvailability } from '@/features/manufacturing/api-advanced';
import { usePrecastOrder, usePressingRun, type BlockCosting, type PrecastOrderDetail } from '@/features/manufacturing/api-precast';
import {
  BatchStatusPill, CuringAreaSelect, Field, Item, LoadError, OrderStatusPill, QcPill, ScalingSummary, ScrapReasonField, Tile,
} from './precast-ui';
import { daysLabel, nonNegativeOrEmpty, num, optNum, pct, today } from './precast-format';

const ACTIVE = ['confirmed', 'in_progress', 'qc_hold'];

/** Precast order detail (2.4): pressing runs, curing batches, costing per acceptable block, material variance. */
export function PrecastOrderDetailPage() {
  const { id = '' } = useParams<{ id: string }>();
  const has = useAuthStore((s) => s.hasPermission);
  const { data: order, isLoading, isError, refetch } = usePrecastOrder(id);
  const [showAvailability, setShowAvailability] = useState(false);

  if (isLoading) return <div className="space-y-3"><Skeleton className="h-12 w-1/2" /><Skeleton className="h-64 w-full" /></div>;
  if (isError || !order) return <LoadError message={tx('Order not found')} onRetry={() => refetch()} />;
  const canPress = has(PERMISSIONS.productionOrder.complete) && ACTIVE.includes(order.status);

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div className="space-y-1">
          <Link to="/manufacturing/precast/orders" className="inline-flex items-center text-sm text-muted-foreground hover:underline">
            <ArrowLeft className="mr-1 h-4 w-4" /> {tx('Precast orders')}
          </Link>
          <h1 className="text-2xl font-bold tracking-tight">{order.orderCode}</h1>
          <div className="flex flex-wrap items-center gap-2 text-sm text-muted-foreground">
            <span>{order.productName}</span>
            <OrderStatusPill status={order.status} />
            {order.mixDesignId && (
              <Link className="text-primary underline" to={`/manufacturing/precast/mix-designs/${order.mixDesignId}`}>
                {order.mixDesign?.name ?? order.bomCode} {order.bomVersion != null ? `v${order.bomVersion}` : ''}
              </Link>
            )}
          </div>
        </div>
        <div className="flex flex-wrap gap-2">
          <Button variant="outline" onClick={() => setShowAvailability((v) => !v)}>{tx('Material availability')}</Button>
          <Button variant="outline" asChild>
            <Link to={`/manufacturing/orders/${order.id}`}><ExternalLink className="mr-1 h-4 w-4" /> {tx('Stages, conversion costs and reversals')}</Link>
          </Button>
        </div>
      </div>

      <dl className="grid grid-cols-2 gap-3 rounded-xl border bg-card p-4 sm:grid-cols-4">
        <Item label={tx('Planned')}>{num(order.plannedQty, 0)}</Item>
        <Item label={tx('Good blocks')}>{num(order.producedQty, 0)}</Item>
        <Item label={tx('Batch number')}>{order.batchNumber}</Item>
        <Item label={tx('Scheduled')}>{order.scheduledFor ? date(order.scheduledFor) : null}</Item>
      </dl>

      {showAvailability && <AvailabilityCard orderId={order.id} />}

      {order.costing && <CostingCard costing={order.costing} />}

      <div className="grid gap-4 lg:grid-cols-5">
        <Card className="lg:col-span-3">
          <CardHeader><CardTitle>{tx('Stages')}</CardTitle></CardHeader>
          <CardContent className="space-y-2">
            {!order.stages.length && <p className="text-sm text-muted-foreground">{tx('No stages on this order.')}</p>}
            {order.stages.map((st) => (
              <div key={st.id} className="flex flex-wrap items-center justify-between gap-2 rounded-lg border p-3">
                <div>
                  <p className="font-medium">{st.sequence}. {st.name} <span className="text-xs text-muted-foreground">({st.role === 'pressing' ? tx('Pressing') : st.role === 'curing' ? tx('Curing') : tx('Other')})</span></p>
                  <p className="text-xs text-muted-foreground">
                    {tx('Good')}: {num(st.qtyGood, 0)} · {tx('Scrap')}: {num(st.qtyScrap, 0)} · {tx('Quarantined')}: {num(st.qtyQuarantined, 0)}
                    {st.minCuringDays != null && <> · {tx('Minimum curing days')}: {num(st.minCuringDays, 0)}</>}
                    {st.qcPassDueAt && <> · {tx('QC pass due')}: {dateTime(st.qcPassDueAt)}</>}
                  </p>
                </div>
                <div className="flex flex-wrap items-center gap-1">
                  <span className="rounded-full border px-2 py-0.5 text-xs">{st.status.replace('_', ' ')}</span>
                  <QcPill status={st.qcStatus} />
                </div>
              </div>
            ))}
          </CardContent>
        </Card>
        <div className="lg:col-span-2">
          {canPress ? <PressingRunForm order={order} /> : (
            <Card><CardContent className="p-4 text-sm text-muted-foreground">{tx('Pressing runs can be recorded on confirmed or in-progress orders.')}</CardContent></Card>
          )}
        </div>
      </div>

      <Card>
        <CardHeader><CardTitle>{tx('Curing batches')}</CardTitle></CardHeader>
        <CardContent className="overflow-x-auto">
          {!order.batches.length ? <p className="text-sm text-muted-foreground">{tx('No curing batches yet. Record a pressing run.')}</p> : (
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>{tx('Code')}</TableHead>
                  <TableHead>{tx('Batch number')}</TableHead>
                  <TableHead className="text-right">{tx('Quantity')}</TableHead>
                  <TableHead className="text-right">{tx('Pallets')}</TableHead>
                  <TableHead>{tx('Curing area')}</TableHead>
                  <TableHead>{tx('Status')}</TableHead>
                  <TableHead className="text-right">{tx('Days cured')}</TableHead>
                  <TableHead>{tx('Curing until')}</TableHead>
                  <TableHead>{tx('Ready')}</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {order.batches.map((b) => (
                  <TableRow key={b.id}>
                    <TableCell><Link className="font-mono text-xs text-primary underline" to={`/manufacturing/precast/curing/${b.id}`}>{b.code}</Link></TableCell>
                    <TableCell>{b.batchNumber ?? '—'}</TableCell>
                    <TableCell className="text-right tabular-nums">{num(b.quantity, 0)}</TableCell>
                    <TableCell className="text-right tabular-nums">{num(b.pallets, 0)}</TableCell>
                    <TableCell>{b.curingAreaCode ?? '—'}{b.palletLocation ? ` · ${b.palletLocation}` : ''}</TableCell>
                    <TableCell><div className="flex flex-wrap gap-1"><BatchStatusPill status={b.status} /><QcPill status={b.qcStatus} /></div></TableCell>
                    <TableCell className="text-right tabular-nums">{daysLabel(b.daysCured, b.minCuringDays)}</TableCell>
                    <TableCell className="whitespace-nowrap">{b.curingUntil ? dateTime(b.curingUntil) : '—'}</TableCell>
                    <TableCell>{b.status === 'curing' ? (b.ready ? tx('Yes') : tx('No')) : '—'}</TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          )}
          <div className="mt-3"><Link className="text-sm text-primary underline" to={`/manufacturing/precast/curing?q=${encodeURIComponent(order.orderCode)}`}>{tx('Open the curing board')}</Link></div>
        </CardContent>
      </Card>

      {order.scaling && (
        <Card>
          <CardHeader><CardTitle>{tx('Planned scaling')}</CardTitle></CardHeader>
          <CardContent><ScalingSummary scaling={order.scaling} /></CardContent>
        </Card>
      )}
    </div>
  );
}

function CostingCard({ costing: c }: { costing: BlockCosting }) {
  const currency = useOrgCurrency();
  const m = (v: unknown) => money(v as string, currency);
  return (
    <Card>
      <CardHeader><CardTitle>{tx('Block costing')}</CardTitle></CardHeader>
      <CardContent className="space-y-4">
        <div className="grid grid-cols-2 gap-3 md:grid-cols-4">
          <Tile strong label={tx('Cost per acceptable block')} value={c.costPerAcceptableBlock == null ? '—' : m(c.costPerAcceptableBlock)}
            hint={`${tx('Cost per pressed block')}: ${c.costPerPressedBlock == null ? '—' : m(c.costPerPressedBlock)}`} />
          <Tile label={tx('Total cost')} value={m(c.totalCost)} hint={`${tx('Material')}: ${m(c.materialCost)} · ${tx('Conversion')}: ${m(c.conversionCost)}`} />
          <Tile label={tx('Finished goods value')} value={m(c.finishedGoodsValue)} hint={`${tx('Second grade')}: ${m(c.secondGradeValue)}`} />
          <Tile label={tx('Rejection rate')} value={pct(c.rejectionRatePct)} hint={`${tx('Abnormal scrap')}: ${m(c.abnormalScrapValue)}`} />
        </div>
        <dl className="grid grid-cols-2 gap-3 sm:grid-cols-4 lg:grid-cols-7">
          <Item label={tx('Blocks pressed')}>{num(c.blocksPressed, 0)}</Item>
          <Item label={tx('Broken at press')}>{num(c.brokenAtPress, 0)}</Item>
          <Item label={tx('In curing')}>{num(c.inCuring, 0)}</Item>
          <Item label={tx('Broken at cubing')}>{num(c.brokenAtCubing, 0)}</Item>
          <Item label={tx('Second grade')}>{num(c.secondGrade, 0)}</Item>
          <Item label={tx('Good blocks')}>{num(c.goodBlocks, 0)}</Item>
          <Item label={tx('Waste')}>{num(c.waste, 0)}</Item>
        </dl>
        {Object.keys(c.conversionByKind ?? {}).length > 0 && (
          <div className="flex flex-wrap gap-2 text-sm">
            <span className="text-muted-foreground">{tx('Conversion by kind')}:</span>
            {Object.entries(c.conversionByKind).map(([k, v]) => <span key={k} className="rounded-full border px-2 py-0.5">{k.replace(/_/g, ' ')}: {m(v)}</span>)}
          </div>
        )}
        {c.materialVariance.length > 0 && (
          <div className="overflow-x-auto">
            <p className="mb-1 text-sm font-medium">{tx('Material variance')}</p>
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>{tx('Material')}</TableHead>
                  <TableHead className="text-right">{tx('Standard')}</TableHead>
                  <TableHead className="text-right">{tx('Actual')}</TableHead>
                  <TableHead className="text-right">{tx('Variance')}</TableHead>
                  <TableHead className="text-right">{tx('Variance %')}</TableHead>
                  <TableHead className="text-right">{tx('Value')}</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {c.materialVariance.map((v) => (
                  <TableRow key={v.productId}>
                    <TableCell>{v.productName ?? v.productId}</TableCell>
                    <TableCell className="text-right tabular-nums">{num(v.standardQty, 3)}</TableCell>
                    <TableCell className="text-right tabular-nums">{num(v.actualQty, 3)}</TableCell>
                    <TableCell className={cn('text-right tabular-nums', Number(v.varianceQty) > 0 && 'text-destructive')}>{num(v.varianceQty, 3)}</TableCell>
                    <TableCell className="text-right tabular-nums">{pct(v.variancePct)}</TableCell>
                    <TableCell className={cn('text-right tabular-nums', Number(v.varianceValue) > 0 && 'text-destructive')}>{m(v.varianceValue)}</TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </div>
        )}
      </CardContent>
    </Card>
  );
}

interface AvailabilityRow { lineId: string; productName: string; required: string; onHand: string; shortage: string }

function AvailabilityCard({ orderId }: { orderId: string }) {
  const { data, isLoading, isError } = useAvailability(orderId);
  const rows = (data ?? []) as AvailabilityRow[];
  return (
    <Card>
      <CardHeader><CardTitle>{tx('Material availability')}</CardTitle></CardHeader>
      <CardContent className="overflow-x-auto">
        {isLoading ? <p className="text-sm text-muted-foreground">{tx('Loading…')}</p>
          : isError ? <p className="text-sm text-destructive">{tx('Could not load the material availability.')}</p>
            : !rows.length ? <p className="text-sm text-muted-foreground">{tx('No materials to check.')}</p> : (
              <Table>
                <TableHeader>
                  <TableRow>
                    <TableHead>{tx('Material')}</TableHead>
                    <TableHead className="text-right">{tx('Required')}</TableHead>
                    <TableHead className="text-right">{tx('On hand')}</TableHead>
                    <TableHead className="text-right">{tx('Shortage')}</TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {rows.map((a) => (
                    <TableRow key={a.lineId}>
                      <TableCell>{a.productName}</TableCell>
                      <TableCell className="text-right tabular-nums">{num(a.required, 3)}</TableCell>
                      <TableCell className="text-right tabular-nums">{num(a.onHand, 3)}</TableCell>
                      <TableCell className={cn('text-right tabular-nums', Number(a.shortage) > 0 && 'text-destructive')}>{num(a.shortage, 3)}</TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            )}
      </CardContent>
    </Card>
  );
}

/** Pressing run (mobile friendly): good blocks go to a curing batch; broken blocks post as press scrap. */
function PressingRunForm({ order }: { order: PrecastOrderDetail }) {
  const press = usePressingRun();
  const [qtyGood, setQtyGood] = useState('');
  const [qtyBroken, setQtyBroken] = useState('');
  const [scrapReasonCode, setScrapReasonCode] = useState('');
  const [cycles, setCycles] = useState('');
  const [pallets, setPallets] = useState('');
  const [curingAreaId, setCuringAreaId] = useState(order.mixDesign?.defaultCuringAreaId ?? '');
  const [palletLocation, setPalletLocation] = useState('');
  const [shift, setShift] = useState('');
  const [batchNumber, setBatchNumber] = useState(order.batchNumber ?? '');
  const [lastRun, setLastRun] = useState(false);
  const [runDate, setRunDate] = useState(today());
  const [notes, setNotes] = useState('');
  const goodOk = qtyGood.trim() !== '' && nonNegativeOrEmpty(qtyGood);
  const numsOk = [qtyBroken, cycles, pallets].every(nonNegativeOrEmpty);
  const somethingPosted = Number(qtyGood || 0) > 0 || Number(qtyBroken || 0) > 0;
  const needsReason = Number(qtyBroken || 0) > 0 && !scrapReasonCode;
  const valid = goodOk && numsOk && somethingPosted;
  const reset = () => {
    setQtyGood(''); setQtyBroken(''); setScrapReasonCode(''); setCycles(''); setPallets(''); setPalletLocation(''); setNotes(''); setLastRun(false);
  };
  const submit = async () => {
    try {
      const out = await press.mutateAsync({
        orderId: order.id,
        body: {
          qtyGood: Number(qtyGood), qtyBroken: optNum(qtyBroken), scrapReasonCode: scrapReasonCode.trim() || undefined,
          cycles: optNum(cycles), pallets: optNum(pallets), curingAreaId: curingAreaId || undefined,
          palletLocation: palletLocation.trim() || undefined, shift: shift.trim() || undefined,
          batchNumber: batchNumber.trim() || undefined, lastRun, date: runDate || undefined, notes: notes.trim() || undefined,
        },
      });
      notify.success(out.batch ? tx('Pressing run recorded: curing batch {{code}}', { code: out.batch.code }) : tx('Pressing run recorded'));
      reset();
    } catch (err) {
      notify.error(apiMessage(err, tx('Could not record the pressing run')));
    }
  };
  return (
    <Card>
      <CardHeader><CardTitle className="flex items-center gap-2"><Hammer className="h-5 w-5" /> {tx('Pressing run')}</CardTitle></CardHeader>
      <CardContent className="space-y-3">
        <div className="grid grid-cols-2 gap-3">
          <Field label={tx('Good blocks *')} error={qtyGood !== '' && !goodOk ? tx('Enter zero or more') : undefined}>
            <Input className="h-12 text-lg" inputMode="numeric" value={qtyGood} onChange={(e) => setQtyGood(e.target.value)} />
          </Field>
          <Field label={tx('Broken blocks')} error={!nonNegativeOrEmpty(qtyBroken) ? tx('Enter zero or more') : undefined}>
            <Input className="h-12 text-lg" inputMode="numeric" value={qtyBroken} onChange={(e) => setQtyBroken(e.target.value)} />
          </Field>
          <Field label={tx('Press cycles')}><Input className="h-11" inputMode="numeric" value={cycles} onChange={(e) => setCycles(e.target.value)} /></Field>
          <Field label={tx('Pallets')}><Input className="h-11" inputMode="numeric" value={pallets} onChange={(e) => setPallets(e.target.value)} /></Field>
        </div>
        {Number(qtyBroken || 0) > 0 && <ScrapReasonField value={scrapReasonCode} onChange={setScrapReasonCode} />}
        {needsReason && <p className="text-xs text-muted-foreground">{tx('Give a scrap reason for the broken blocks.')}</p>}
        <div className="grid grid-cols-2 gap-3">
          <Field label={tx('Curing area')} className="col-span-2"><CuringAreaSelect value={curingAreaId} onChange={setCuringAreaId} noneLabel={tx('Default')} /></Field>
          <Field label={tx('Pallet location / bay')}><Input className="h-11" value={palletLocation} onChange={(e) => setPalletLocation(e.target.value)} /></Field>
          <Field label={tx('Shift')}><Input className="h-11" value={shift} onChange={(e) => setShift(e.target.value)} /></Field>
          <Field label={tx('Batch number')}><Input className="h-11" value={batchNumber} onChange={(e) => setBatchNumber(e.target.value)} /></Field>
          <Field label={tx('Date')}><Input className="h-11" type="date" value={runDate} onChange={(e) => setRunDate(e.target.value)} /></Field>
        </div>
        <label className="flex items-center gap-2 text-sm"><input type="checkbox" className="h-5 w-5" checked={lastRun} onChange={(e) => setLastRun(e.target.checked)} /> {tx('Last pressing run of this order')}</label>
        <Field label={tx('Notes')}><Textarea rows={2} value={notes} onChange={(e) => setNotes(e.target.value)} /></Field>
        <Button className="h-12 w-full text-base" onClick={submit} disabled={!valid || press.isPending}>{tx('Record pressing run')}</Button>
      </CardContent>
    </Card>
  );
}

export default PrecastOrderDetailPage;

import { useMemo, useState } from 'react';
import { useParams, Link } from 'react-router-dom';
import { OrderAdvancedPanel } from './OrderAdvancedPanel';
import { OrderStagesPanel } from './OrderStagesPanel';
import { useOrderStages } from '@/features/manufacturing/api-stages';
import {
  ArrowLeft, Play, PackageCheck, XCircle, ClipboardCheck, ShieldCheck, Undo2, Hammer, Pause, Boxes, PackageOpen, Coins, History,
  Wrench, Calculator, CalendarClock, SearchX, CheckCircle2,
} from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Dialog, DialogContent, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { Donut } from '@/components/ops-dashboard/charts';
import { useAuthStore } from '@/stores/auth.store';
import { notify } from '@/lib/notify';
import { date, dateTime } from '@/lib/format';
import { cn } from '@/lib/utils';
import {
  useProductionAction,
  useProductionOrder,
  useWorkOrders,
  useGenerateWorkOrders,
  useWorkOrderAction,
} from '@/features/manufacturing/api';
import { orderProductName } from './ProductionOrdersPage';
import { tx } from '@/lib/i18n/tx';
import { QualityChecksPanel } from '@/pages/quality-control/QualityChecksPanel';
import {
  DUE_TONE, dueLabel, dueState, EmptyState, Fact, fmtMoney, fmtQty, MfgCard, MfgPageHeader, MfgStatus, n, PageSkeleton, PlanActualBar,
  ProgressRing, progressPct, TONE_FILL, TonePill, type MfgTone,
} from './mfg-ui';

interface TimelineEvent { key: string; at: string; title: string; detail?: string; tone: MfgTone }

export function ProductionOrderDetailPage() {
  const { id = '' } = useParams();
  const hasPermission = useAuthStore((s) => s.hasPermission);
  const { data: order, isLoading } = useProductionOrder(id);
  // ADR-S011: a multi-stage order runs through its stage actions instead of start / complete / QC / reverse.
  const { data: staged } = useOrderStages(id);
  const multiStage = !!staged?.multiStage;

  const confirm = useProductionAction('confirm');
  const start = useProductionAction('start');
  const complete = useProductionAction('complete');
  const cancel = useProductionAction('cancel');
  const qcAction = useProductionAction('qc');
  const reverse = useProductionAction('reverse');

  const { data: workOrders } = useWorkOrders(id);
  const generateWo = useGenerateWorkOrders();
  const woStart = useWorkOrderAction('start');
  const woPause = useWorkOrderAction('pause');
  const woComplete = useWorkOrderAction('complete');

  const [completeOpen, setCompleteOpen] = useState(false);
  const [qtyProduced, setQtyProduced] = useState('');
  const [scrapQty, setScrapQty] = useState('');
  const [qcOpen, setQcOpen] = useState(false);
  const [passedQty, setPassedQty] = useState('');
  const [failedQty, setFailedQty] = useState('');

  // Activity is derived from the order's own timestamps plus the stage postings already loaded for the stage panel.
  const timeline = useMemo<TimelineEvent[]>(() => {
    if (!order) return [];
    const ev: TimelineEvent[] = [{ key: 'created', at: order.createdAt, title: tx('Order created'), tone: 'neutral' }];
    if (order.startedAt) ev.push({ key: 'started', at: order.startedAt, title: tx('Production started'), tone: 'progress' });
    for (const s of staged?.stages ?? []) {
      for (const p of s.postings) {
        const bits = [p.kind.replace(/_/g, ' ')];
        if (n(p.qtyGood)) bits.push(`${tx('Good')} ${fmtQty(p.qtyGood)}`);
        if (n(p.qtyScrap)) bits.push(`${tx('Scrap')} ${fmtQty(p.qtyScrap)}`);
        if (p.reversedAt) bits.push(tx('reversed'));
        ev.push({
          key: p.id, at: p.postedAt, title: `${p.code} · ${s.name}`, detail: bits.join(' · '),
          tone: p.reversedAt || p.kind === 'qc_scrap' ? 'danger' : p.kind === 'qc_release' ? 'success' : p.kind === 'output' ? 'info' : 'progress',
        });
      }
    }
    if (order.completedAt) ev.push({ key: 'completed', at: order.completedAt, title: tx('Order completed'), tone: 'success' });
    return ev.sort((a, b) => (a.at < b.at ? 1 : -1));
  }, [order, staged]);

  if (isLoading) return <div className="p-4 sm:p-6"><PageSkeleton /></div>;
  if (!order) {
    return (
      <div className="p-4 sm:p-6">
        <EmptyState icon={SearchX} title={tx('Order not found.')} action={<Button asChild variant="outline"><Link to="/manufacturing/orders">{tx('Orders')}</Link></Button>} />
      </div>
    );
  }

  const act = async (m: { mutateAsync: (a: { id: string; body?: Record<string, unknown> }) => Promise<unknown> }, body?: Record<string, unknown>, ok?: string) => {
    try {
      await m.mutateAsync({ id, body });
      if (ok) notify.success(ok);
    } catch (e: any) {
      notify.error(tx('Action failed'), e?.response?.data?.message ?? e?.message);
    }
  };

  const doComplete = async () => {
    if (!(Number(qtyProduced) > 0)) {
      notify.error(tx('Enter the actual quantity produced.'));
      return;
    }
    await act(complete, { qtyProduced: Number(qtyProduced), scrapQty: scrapQty ? Number(scrapQty) : undefined }, tx('Order completed — finished goods received.'));
    setCompleteOpen(false);
    setQtyProduced('');
    setScrapQty('');
  };

  const doQc = async () => {
    if (!(Number(passedQty) >= 0)) return;
    await act(qcAction, { passedQty: Number(passedQty), failedQty: failedQty ? Number(failedQty) : undefined, wasteCategory: 'qc_rejection' }, tx('QC recorded.'));
    setQcOpen(false);
    setPassedQty('');
    setFailedQty('');
  };

  const canConfirm = hasPermission(PERMISSIONS.productionOrder.create) && order.status === 'draft';
  const canStart = !multiStage && hasPermission(PERMISSIONS.productionOrder.start) && order.status === 'confirmed';
  const canComplete = !multiStage && hasPermission(PERMISSIONS.productionOrder.complete) && order.status === 'in_progress';
  const canQc = !multiStage && hasPermission(PERMISSIONS.productionOrder.qc) && order.status === 'qc_hold';
  const canReverse = !multiStage && hasPermission(PERMISSIONS.productionOrder.cancel) && order.status === 'completed' && Number(order.producedQty) > 0;
  const canCancel = hasPermission(PERMISSIONS.productionOrder.cancel) && (multiStage ? ['draft', 'confirmed'].includes(order.status) : ['draft', 'confirmed', 'in_progress'].includes(order.status));

  const pct = progressPct(order.producedQty, order.plannedQty);
  const product = orderProductName(order);
  const due = dueState(order.scheduledFor, order.status);
  const made = n(order.producedQty) + n(order.scrapQty);
  const scrapPct = made > 0 ? (n(order.scrapQty) / made) * 100 : null;
  const costRows = order.materials.filter((m) => n(m.totalCost) > 0)
    .map((m) => ({ key: m.id, label: m.productName, value: n(m.totalCost) }))
    .sort((a, b) => b.value - a.value);
  const conversion = n(order.totalCost) - n(order.materialCost);
  if (conversion > 0.005) costRows.push({ key: 'conv', label: tx('Conversion and overhead'), value: conversion });

  return (
    <div className="space-y-5 p-4 sm:p-6">
      <MfgPageHeader
        back={<Button asChild variant="ghost" size="icon" className="shrink-0"><Link to="/manufacturing/orders" aria-label={tx('Back to orders')}><ArrowLeft className="h-4 w-4" /></Link></Button>}
        eyebrow={tx('Production order')}
        title={<span className="font-mono">{order.orderCode}</span>}
        badges={<MfgStatus status={order.status} kind="order" size="lg" />}
        subtitle={<>{product ? `${product} · ` : ''}{tx('Planned')} {Number(order.plannedQty)} {tx('· created')} {new Date(order.createdAt).toLocaleDateString()}</>}
        actions={(canConfirm || canStart || canComplete || canQc || canReverse || canCancel) && (
          <>
            {canConfirm && <Button variant="outline" onClick={() => act(confirm, undefined, tx('Order confirmed.'))}><ClipboardCheck className="mr-2 h-4 w-4" /> {tx('Confirm')}</Button>}
            {canStart && <Button onClick={() => act(start, undefined, tx('Started — materials consumed into WIP.'))}><Play className="mr-2 h-4 w-4" /> {tx('Start')}</Button>}
            {canComplete && <Button onClick={() => setCompleteOpen(true)}><PackageCheck className="mr-2 h-4 w-4" /> {tx('Complete')}</Button>}
            {canQc && <Button onClick={() => setQcOpen(true)}><ShieldCheck className="mr-2 h-4 w-4" /> {tx('Record QC')}</Button>}
            {canReverse && <Button variant="outline" onClick={() => act(reverse, { reason: 'reversed from UI' }, tx('Order reversed.'))}><Undo2 className="mr-2 h-4 w-4" /> {tx('Reverse')}</Button>}
            {canCancel && <Button variant="destructive" onClick={() => act(cancel, { reason: 'cancelled from UI' }, tx('Order cancelled.'))}><XCircle className="mr-2 h-4 w-4" /> {tx('Cancel')}</Button>}
          </>
        )} />

      <section className="grid items-center gap-5 rounded-2xl border bg-card p-4 shadow-sm sm:p-5 md:grid-cols-[auto_1fr]">
        <div className="flex justify-center">
          <ProgressRing value={pct} size={120} stroke={10} tone={order.status === 'completed' ? 'success' : 'progress'} sub={tx('produced')} />
        </div>
        <dl className="grid grid-cols-2 gap-x-4 gap-y-4 sm:grid-cols-4">
          <Fact label={tx('Produced')} value={<>{fmtQty(order.producedQty)} <span className="text-xs font-normal text-muted-foreground">/ {fmtQty(order.plannedQty)}</span></>} />
          <Fact label={tx('Scrap')} tone={n(order.scrapQty) > 0 ? 'warning' : undefined}
            value={<>{fmtQty(order.scrapQty)}{scrapPct != null && <span className="ml-1 text-xs font-normal text-muted-foreground">({scrapPct.toFixed(1)}%)</span>}</>} />
          <Fact label={tx('Material cost')} value={fmtMoney(order.materialCost)} />
          <Fact label={tx('Unit cost')} value={order.status === 'completed' ? fmtMoney(order.outputUnitCost) : '—'} />
          <Fact label={tx('Yield variance')} value={Number(order.yieldVariance).toFixed(2)} tone={n(order.yieldVariance) < 0 ? 'danger' : undefined} />
          <Fact label={tx('Batch')} value={<span className="font-mono text-sm">{order.batchNumber ?? '—'}</span>} />
          <div className="min-w-0">
            <dt className="truncate text-[11px] uppercase tracking-wide text-muted-foreground">{tx('Scheduled')}</dt>
            <dd className="flex flex-wrap items-center gap-1.5 font-semibold">
              {order.scheduledFor ? date(order.scheduledFor) : '—'}
              {(due === 'late' || due === 'today') && <TonePill tone={DUE_TONE[due]} icon={CalendarClock}>{dueLabel(due)}</TonePill>}
            </dd>
          </div>
          <div className="col-span-2 flex items-end sm:col-span-1">
            <Button asChild variant="outline" size="sm"><Link to={`/manufacturing/costing/orders/${id}`}><Calculator className="mr-1.5 h-4 w-4" aria-hidden />{tx('Actual cost and variances')}</Link></Button>
          </div>
        </dl>
      </section>

      <OrderStagesPanel orderId={id} />

      <div className="grid gap-5 xl:grid-cols-3">
        <MfgCard className="xl:col-span-2" icon={Boxes} title={tx('Materials')} description={tx('Bar = consumed, tick = planned')}>
          {order.materials.length === 0 ? <EmptyState compact icon={Boxes} title={tx('No materials on this order.')} /> : (
            <div className="overflow-x-auto">
              <table className="w-full min-w-[560px] text-sm">
                <thead className="text-left text-xs uppercase tracking-wide text-muted-foreground">
                  <tr>
                    <th className="pb-2 pr-3 font-semibold">{tx('Product')}</th>
                    <th className="w-56 px-3 pb-2 font-semibold">{tx('Consumed')} / {tx('Planned')}</th>
                    <th className="px-3 pb-2 text-right font-semibold">{tx('Unit cost')}</th>
                    <th className="pb-2 pl-3 text-right font-semibold">{tx('Total')}</th>
                  </tr>
                </thead>
                <tbody className="divide-y">
                  {order.materials.map((m) => (
                    <tr key={m.id}>
                      <td className="py-2.5 pr-3 font-medium">{m.productName}{m.batchNumber && <div className="font-mono text-[11px] font-normal text-muted-foreground">{m.batchNumber}</div>}</td>
                      <td className="px-3 py-2.5"><PlanActualBar planned={n(m.qtyPlanned)} actual={n(m.qtyConsumed)} /></td>
                      <td className="px-3 py-2.5 text-right tabular-nums">{fmtMoney(m.unitCost)}</td>
                      <td className="py-2.5 pl-3 text-right font-medium tabular-nums">{fmtMoney(m.totalCost)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </MfgCard>
        <MfgCard icon={Coins} title={tx('Cost breakdown')} description={tx('Where the order cost comes from')}>
          {costRows.length === 0 ? <EmptyState compact icon={Coins} title={tx('No cost posted yet.')} /> : (
            <Donut rows={costRows.slice(0, 8)} format={fmtMoney} size={150} centerSub={tx('cost')} stacked />
          )}
        </MfgCard>
      </div>

      <QualityChecksPanel sources={[
        { sourceType: 'production_order', sourceId: id },
        ...(workOrders ?? []).map((w: { id: string; name?: string }) => ({ sourceType: 'work_order' as const, sourceId: w.id, label: w.name })),
      ]} />

      <OrderAdvancedPanel order={order as any} />

      <div className="grid gap-5 xl:grid-cols-3">
        <MfgCard className="xl:col-span-2" icon={PackageOpen} title={tx('Outputs')}>
          {order.outputs.length === 0 ? <EmptyState compact icon={PackageOpen} title={tx('No outputs.')} /> : (
            <ul className="grid grid-cols-1 gap-3 sm:grid-cols-2">
              {order.outputs.map((o) => {
                const p = progressPct(o.qtyProduced, o.qtyExpected);
                return (
                  <li key={o.id} className="min-w-0 rounded-xl border p-3">
                    <div className="flex items-start justify-between gap-2">
                      <div className="min-w-0">
                        <div className="truncate font-medium">{o.productName}</div>
                        <div className="font-mono text-[11px] text-muted-foreground">{o.batchNumber ?? '—'}</div>
                      </div>
                      <TonePill tone={o.kind === 'output' ? 'info' : 'neutral'}>{o.kind}</TonePill>
                    </div>
                    <div className="mt-2 flex items-center gap-3">
                      <ProgressRing value={p} size={48} stroke={10} tone={p != null && p >= 100 ? 'success' : 'progress'} />
                      <dl className="grid min-w-0 flex-1 grid-cols-2 gap-2 text-sm">
                        <Fact label={tx('Expected')} value={fmtQty(o.qtyExpected)} />
                        <Fact label={tx('Produced')} value={fmtQty(o.qtyProduced)} />
                      </dl>
                    </div>
                  </li>
                );
              })}
            </ul>
          )}
        </MfgCard>
        <MfgCard icon={History} title={tx('Activity')}>
          {timeline.length === 0 ? <EmptyState compact icon={History} title={tx('No activity yet.')} /> : (
            <ol className="relative max-h-80 space-y-3 overflow-y-auto pl-5 before:absolute before:inset-y-1 before:left-[6px] before:w-px before:bg-border">
              {timeline.map((e) => (
                <li key={e.key} className="relative">
                  <span className={cn('absolute -left-5 top-1 h-3.5 w-3.5 rounded-full ring-4 ring-card', TONE_FILL[e.tone])} aria-hidden />
                  <div className="text-sm font-medium">{e.title}</div>
                  {e.detail && <div className="text-xs text-muted-foreground">{e.detail}</div>}
                  <div className="text-[11px] text-muted-foreground">{dateTime(e.at)}</div>
                </li>
              ))}
            </ol>
          )}
        </MfgCard>
      </div>

      <MfgCard icon={Wrench} title={tx('Work Orders')}
        actions={!workOrders?.length && ['confirmed', 'in_progress'].includes(order.status) && (
          <Button size="sm" variant="outline" onClick={async () => {
            try { await generateWo.mutateAsync(id); notify.success(tx('Work orders generated from routing.')); }
            catch (e: any) { notify.error(tx('No routing'), e?.response?.data?.message ?? e?.message); }
          }}><Hammer className="mr-1 h-3 w-3" /> {tx('Generate')}</Button>
        )}>
        {!workOrders?.length ? (
          <EmptyState compact icon={Wrench} title={tx('No work orders. Generate them from the BOM\'s routing (Phase 4).')} />
        ) : (
          <ol className="space-y-2">
            {workOrders.map((w: any) => (
              <li key={w.id} className="flex flex-wrap items-center gap-3 rounded-xl border p-3">
                <span className="grid h-8 w-8 shrink-0 place-items-center rounded-full bg-muted text-sm font-semibold tabular-nums">{w.operationSequence}</span>
                <div className="min-w-0 flex-1">
                  <div className="truncate font-medium">{w.name}</div>
                  <div className="text-xs text-muted-foreground">{w.workCenter?.name ?? '—'} · {tx('Labour (min)')} {w.labourMins}</div>
                </div>
                <MfgStatus status={w.status} kind="workOrder" />
                <div className="flex gap-1">
                  {(w.status === 'pending' || w.status === 'paused') && (
                    <Button size="icon" variant="outline" aria-label={tx('Start')} title={tx('Start')} onClick={() => woStart.mutate({ id: w.id, orderId: id })}><Play className="h-4 w-4" /></Button>
                  )}
                  {w.status === 'in_progress' && (
                    <>
                      <Button size="icon" variant="outline" aria-label={tx('Pause')} title={tx('Pause')} onClick={() => woPause.mutate({ id: w.id, orderId: id })}><Pause className="h-4 w-4" /></Button>
                      <Button size="icon" aria-label={tx('Complete')} title={tx('Complete')} onClick={() => woComplete.mutate({ id: w.id, orderId: id, body: {} })}><PackageCheck className="h-4 w-4" /></Button>
                    </>
                  )}
                  {w.status === 'completed' && <CheckCircle2 className="h-5 w-5 text-emerald-500" aria-hidden />}
                </div>
              </li>
            ))}
          </ol>
        )}
      </MfgCard>

      <Dialog open={qcOpen} onOpenChange={setQcOpen}>
        <DialogContent>
          <DialogHeader><DialogTitle>{tx('Record QC ·')} {order.orderCode}</DialogTitle></DialogHeader>
          <div className="space-y-4">
            <p className="text-sm text-muted-foreground">{tx('Passed units move to the sale location; failed units are scrapped from quarantine.')}</p>
            <div className="grid grid-cols-2 gap-4">
              <div className="space-y-1"><Label>{tx('Passed')}</Label><Input type="number" value={passedQty} onChange={(e) => setPassedQty(e.target.value)} /></div>
              <div className="space-y-1"><Label>{tx('Failed')}</Label><Input type="number" value={failedQty} onChange={(e) => setFailedQty(e.target.value)} /></div>
            </div>
          </div>
          <DialogFooter>
            <Button variant="outline" onClick={() => setQcOpen(false)}>{tx('Cancel')}</Button>
            <Button onClick={doQc}>{tx('Record')}</Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      <Dialog open={completeOpen} onOpenChange={setCompleteOpen}>
        <DialogContent>
          <DialogHeader><DialogTitle>{tx('Complete')} {order.orderCode}</DialogTitle></DialogHeader>
          <div className="space-y-4">
            <div className="space-y-1">
              <Label>{tx('Actual quantity produced')}</Label>
              <Input type="number" value={qtyProduced} onChange={(e) => setQtyProduced(e.target.value)} placeholder={String(Number(order.plannedQty))} />
              <p className="text-xs text-muted-foreground">{tx('Unit cost = material cost ÷ this quantity, so under-yield raises the cost.')}</p>
            </div>
            <div className="space-y-1">
              <Label>{tx('Scrap quantity (optional)')}</Label>
              <Input type="number" value={scrapQty} onChange={(e) => setScrapQty(e.target.value)} />
            </div>
          </div>
          <DialogFooter>
            <Button variant="outline" onClick={() => setCompleteOpen(false)}>{tx('Cancel')}</Button>
            <Button onClick={doComplete} disabled={complete.isPending}>{tx('Receive finished goods')}</Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  );
}

export default ProductionOrderDetailPage;

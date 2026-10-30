import { useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { ArrowLeft, Ban, FileText, Layers, PackageCheck, Plus, RefreshCw, Split } from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Skeleton } from '@/components/ui/skeleton';
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { DataTable, type Column } from '@/components/data-table';
import { useAuthStore } from '@/stores/auth.store';
import { notify, apiMessage } from '@/lib/notify';
import { date, dateTime, money } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';
import {
  DISCREPANCY_KIND_LABEL, PRIORITY_LABEL, REASON_LABEL, SOURCE_TYPE_LABEL, useAddTrip, useCancelDeliveryOrder,
  useChargePreview, useCloseDeliveryOrder, useDeliveryOrder, useDispatchLookups, useInvoiceDeliveryOrder, useLoadPlan,
  usePlanLoads, toNum, type DeliveryOrderDetail, type DeliveryOrderLine, type TripSummary,
} from '@/features/fleet/dispatch-api';
import { Field, OptionalSelect } from '../fleet-ui';
import { num } from '../fleet-format';
import { AssignDialog } from './AssignDialog';
import { DeliveryOrderStatusPill, DiscrepancyStatusPill, Item, ReasonDialog, TripStatusPill } from './dispatch-ui';
import { carrierText } from './dispatch-format';
import { WeighTicketsCard } from './WeighTicketsCard';

/** Delivery order detail (fleet 1.3): lines and progress, trips, planning, charges, invoice, discrepancies. */
export function DeliveryOrderDetailPage() {
  const { id = '' } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const has = useAuthStore((s) => s.hasPermission);
  const { data: o, isLoading, isError, refetch } = useDeliveryOrder(id);
  const cancel = useCancelDeliveryOrder();
  const close = useCloseDeliveryOrder();
  const invoice = useInvoiceDeliveryOrder();
  const [planning, setPlanning] = useState(false);
  const [adding, setAdding] = useState(false);
  const [cancelling, setCancelling] = useState(false);
  const [closing, setClosing] = useState(false);
  const [assigning, setAssigning] = useState<TripSummary | null>(null);
  const live = !!o && !['closed', 'cancelled'].includes(o.status);
  const charge = useChargePreview(id, !!o && live);

  if (isLoading) return <div className="space-y-3"><Skeleton className="h-12 w-1/2" /><Skeleton className="h-64 w-full" /></div>;
  if (isError || !o) {
    return (
      <div className="p-8 text-center text-muted-foreground">
        <p>{tx('Delivery order not found')}</p>
        <Button variant="link" onClick={() => refetch()}><RefreshCw className="mr-1 h-4 w-4" /> {tx('Retry')}</Button>
      </div>
    );
  }

  const canPlan = has(PERMISSIONS.fleetDispatch.plan) && live;
  const remaining = o.lines.some((l) => Number(l.remainingQty) > 0);
  const canInvoice = has(PERMISSIONS.fleetDispatch.update) && has(PERMISSIONS.invoice.create)
    && ['delivered', 'closed'].includes(o.status) && (!o.goodsInvoiceId || !o.chargesInvoiceId);

  const run = async (fn: () => Promise<unknown>, ok: string, fail: string) => {
    try {
      await fn();
      notify.success(ok);
    } catch (err) {
      notify.error(apiMessage(err, fail));
    }
  };

  const lineColumns: Column<DeliveryOrderLine>[] = [
    { key: 'product', header: tx('Product'), render: (l) => <span className="font-medium">{l.productName}</span> },
    { key: 'unit', header: tx('Unit'), render: (l) => l.uomCode ?? '—' },
    { key: 'ordered', header: tx('Ordered'), className: 'text-right', render: (l) => num(l.orderedQty, 3) },
    { key: 'planned', header: tx('Planned'), className: 'text-right', render: (l) => num(l.plannedQty, 3) },
    { key: 'dispatched', header: tx('Dispatched'), className: 'text-right', render: (l) => num(l.dispatchedQty, 3) },
    { key: 'delivered', header: tx('Delivered'), className: 'text-right', render: (l) => num(l.deliveredQty, 3) },
    { key: 'returned', header: tx('Returned'), className: 'text-right', render: (l) => num(l.returnedQty, 3) },
    { key: 'remaining', header: tx('To plan'), className: 'text-right', render: (l) => <span className="font-medium">{num(l.remainingQty, 3)}</span> },
    { key: 'factor', header: tx('t / unit'), className: 'text-right', render: (l) => (l.tonnesPerUnit ? num(l.tonnesPerUnit, 3) : '—') },
  ];

  const tripColumns: Column<TripSummary>[] = [
    { key: 'code', header: tx('Trip'), render: (t) => <Link to={`/fleet/trips/${t.id}`} className="font-medium text-primary hover:underline">{t.code}</Link> },
    { key: 'date', header: tx('Planned'), render: (t) => date(t.plannedDate) },
    { key: 'carrier', header: tx('Truck / transporter'), render: (t) => carrierText(t) },
    { key: 'tonnes', header: tx('Tonnes'), className: 'text-right', render: (t) => num(t.deliveredTonnes ?? t.loadedTonnes ?? t.plannedTonnes, 2) },
    { key: 'note', header: tx('Delivery note'), render: (t) => t.deliveryNoteNumber ?? '—' },
    { key: 'status', header: tx('Status'), render: (t) => <TripStatusPill status={t.status} /> },
    {
      key: 'actions', header: '', className: 'text-right',
      render: (t) => canPlan && ['planned', 'loading', 'weighed'].includes(t.status) && (
        <Button size="sm" variant="outline" onClick={() => setAssigning(t)}>{t.vehicleId || t.transporterPartnerId ? tx('Reassign') : tx('Assign')}</Button>
      ),
    },
  ];

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-start gap-3">
        <Button variant="ghost" size="icon" aria-label={tx('Back')} onClick={() => navigate('/fleet/delivery-orders')}><ArrowLeft className="h-5 w-5" /></Button>
        <div className="min-w-0 flex-1">
          <div className="flex flex-wrap items-center gap-2">
            <h1 className="text-2xl font-bold">{o.code}</h1>
            <DeliveryOrderStatusPill status={o.status} />
          </div>
          <p className="text-sm text-muted-foreground">{o.partnerName}{o.siteName ? ` · ${o.siteName}` : ''} · {date(o.requestedDate)}</p>
        </div>
        <div className="flex flex-wrap gap-2">
          {canPlan && remaining && (
            <>
              <Button onClick={() => setPlanning(true)}><Split className="mr-1.5 h-4 w-4" /> {tx('Plan loads')}</Button>
              <Button variant="outline" onClick={() => setAdding(true)}><Plus className="mr-1.5 h-4 w-4" /> {tx('Add trip')}</Button>
            </>
          )}
          {canInvoice && (
            <Button variant="outline" disabled={invoice.isPending} onClick={() => run(async () => {
              const out = await invoice.mutateAsync({ id: o.id });
              if (out.invoice) notify.success(tx('Draft invoice {{number}} created', { number: out.invoice.documentNumber }));
            }, tx('Invoice requested'), tx('Could not invoice the delivery order'))}>
              <FileText className="mr-1.5 h-4 w-4" /> {tx('Create invoice')}
            </Button>
          )}
          {live && has(PERMISSIONS.fleetDispatch.update) && (
            <Button variant="outline" onClick={() => setClosing(true)}><PackageCheck className="mr-1.5 h-4 w-4" /> {tx('Close')}</Button>
          )}
          {live && has(PERMISSIONS.fleetDispatch.cancel) && (
            <Button variant="ghost" onClick={() => setCancelling(true)}><Ban className="mr-1.5 h-4 w-4" /> {tx('Cancel order')}</Button>
          )}
        </div>
      </div>

      {o.status === 'cancelled' && o.cancelReason && <div className="rounded-lg border bg-muted/40 px-4 py-2 text-sm">{tx('Cancelled: {{reason}}', { reason: o.cancelReason })}</div>}
      {o.warnings && o.warnings.length > 0 && (
        <div className="rounded-lg border border-amber-300 bg-amber-50 px-4 py-2 text-sm text-amber-900 dark:bg-amber-500/10 dark:text-amber-200">
          {o.warnings.map((w) => <div key={w}>⚠ {w}</div>)}
        </div>
      )}

      <div className="grid grid-cols-1 gap-4 lg:grid-cols-3">
        <Card className="lg:col-span-2">
          <CardHeader><CardTitle className="text-base">{tx('Delivery')}</CardTitle></CardHeader>
          <CardContent>
            <dl className="grid grid-cols-2 gap-4 sm:grid-cols-3">
              <Item label={tx('Customer')}>{o.partnerName}</Item>
              <Item label={tx('Site')}>{o.site ? `${o.site.code} · ${o.site.name}` : null}</Item>
              <Item label={tx('Address')}>{o.site ? [o.site.address, o.site.city].filter(Boolean).join(', ') || null : null}</Item>
              <Item label={tx('Zone')}>{o.site?.zone}</Item>
              <Item label={tx('Requested')}>{`${date(o.requestedDate)}${o.windowFrom ? ` ${o.windowFrom}–${o.windowTo ?? ''}` : ''}`}</Item>
              <Item label={tx('Priority')}>{PRIORITY_LABEL[o.priority]}</Item>
              <Item label={tx('Source')}>{o.sourceNumber ? `${SOURCE_TYPE_LABEL[o.sourceType]} ${o.sourceNumber}` : SOURCE_TYPE_LABEL[o.sourceType]}</Item>
              <Item label={tx('Yard / warehouse')}>{o.locationName}</Item>
              <Item label={tx('Stock')}>{o.stockMode === 'dispatch' ? tx('Issued at dispatch') : tx('Issued by the invoice')}</Item>
              <Item label={tx('Contact')}>{[o.contactName, o.contactPhone].filter(Boolean).join(' · ') || null}</Item>
              <Item label={tx('Goods invoice')}>{o.goodsInvoiceNumber}</Item>
            </dl>
            {o.site?.accessNotes && <p className="mt-3 text-sm"><span className="text-muted-foreground">{tx('Access notes')}:</span> {o.site.accessNotes}</p>}
            {o.notes && <p className="mt-2 whitespace-pre-wrap text-sm">{o.notes}</p>}
          </CardContent>
        </Card>
        <Card>
          <CardHeader><CardTitle className="text-base">{tx('Transport charge')}</CardTitle></CardHeader>
          <CardContent className="space-y-1 text-sm">
            {o.charges.map((c) => (
              <div key={c.id} className="flex justify-between gap-2"><span>{c.description}</span><span className="tabular-nums">{money(c.amount)}{c.invoiceNumber ? ` · ${c.invoiceNumber}` : ''}</span></div>
            ))}
            {o.charges.length === 0 && charge.data?.charge && (
              <>
                <div className="flex justify-between gap-2"><span>{charge.data.charge.description}</span><span className="font-medium tabular-nums">{money(charge.data.charge.amount)}</span></div>
                <p className="text-xs text-muted-foreground">{tx('Estimate from the delivered trips; billed on the invoice.')}</p>
              </>
            )}
            {o.charges.length === 0 && charge.data && !charge.data.charge && <p className="text-muted-foreground">{tx('No delivery-charge rule applies.')}</p>}
          </CardContent>
        </Card>
      </div>

      <Card>
        <CardHeader><CardTitle className="text-base">{tx('Products')}</CardTitle></CardHeader>
        <CardContent className="overflow-x-auto p-0">
          <DataTable columns={lineColumns} data={o.lines} getRowId={(l) => l.id} cellClassName="py-2 px-4" headerRowClassName="h-10" emptyMessage="—" />
        </CardContent>
      </Card>

      <Card>
        <CardHeader><CardTitle className="text-base">{tx('Trips')}</CardTitle></CardHeader>
        <CardContent className="overflow-x-auto p-0">
          <DataTable columns={tripColumns} data={o.trips} getRowId={(t) => t.id} cellClassName="py-2 px-4" headerRowClassName="h-10"
            emptyMessage={tx('No trips yet: plan the loads.')} />
        </CardContent>
      </Card>

      <WeighTicketsCard tickets={o.weighbridgeTickets ?? []} showTrip />

      {o.discrepancies.length > 0 && (
        <Card>
          <CardHeader><CardTitle className="text-base">{tx('Discrepancies')}</CardTitle></CardHeader>
          <CardContent className="space-y-2">
            {o.discrepancies.map((d) => (
              <div key={d.id} className="flex flex-wrap items-center justify-between gap-2 rounded-lg border p-3 text-sm">
                <span>
                  <span className="font-medium">{d.code}</span> · {DISCREPANCY_KIND_LABEL[d.kind]} · {d.productName} · {num(d.differenceQty, 3)} · {REASON_LABEL[d.reason] ?? d.reason}
                  {d.creditNoteNumber ? ` · ${tx('Credit note {{n}}', { n: d.creditNoteNumber })}` : ''}
                </span>
                <span className="flex items-center gap-2"><span className="text-xs text-muted-foreground">{dateTime(d.createdAt)}</span><DiscrepancyStatusPill status={d.status} /></span>
              </div>
            ))}
            <Link to={`/fleet/delivery-discrepancies?deliveryOrderId=${o.id}`} className="text-sm text-primary hover:underline">{tx('Review discrepancies')}</Link>
          </CardContent>
        </Card>
      )}

      {planning && <PlanLoadsDialog order={o} onClose={() => setPlanning(false)} />}
      {adding && <AddTripDialog order={o} onClose={() => setAdding(false)} />}
      <AssignDialog trip={assigning} onClose={() => setAssigning(null)} />
      <ReasonDialog open={cancelling} title={tx('Cancel delivery order {{code}}', { code: o.code })} confirmLabel={tx('Cancel order')}
        busy={cancel.isPending} onClose={() => setCancelling(false)}
        onConfirm={(reason) => run(async () => { await cancel.mutateAsync({ id: o.id, reason }); setCancelling(false); }, tx('Delivery order cancelled'), tx('Could not cancel the delivery order'))} />
      <ReasonDialog open={closing} title={tx('Close delivery order {{code}}', { code: o.code })} required={false}
        description={tx('The remaining quantity will not be delivered.')} confirmLabel={tx('Close')} busy={close.isPending}
        onClose={() => setClosing(false)}
        onConfirm={(reason) => run(async () => { await close.mutateAsync({ id: o.id, reason }); setClosing(false); }, tx('Delivery order closed'), tx('Could not close the delivery order'))} />
    </div>
  );
}

/** Split the remaining quantity by a truck's (or an entered) capacity: preview first, then create the trips. */
function PlanLoadsDialog({ order, onClose }: { order: DeliveryOrderDetail; onClose: () => void }) {
  const lookups = useDispatchLookups();
  const plan = usePlanLoads();
  const [vehicleId, setVehicleId] = useState('');
  const [trailerId, setTrailerId] = useState('');
  const [driverId, setDriverId] = useState('');
  const [payloadT, setPayloadT] = useState('');
  const [volumeM3, setVolumeM3] = useState('');
  const [plannedDate, setPlannedDate] = useState(order.requestedDate);
  const preview = useLoadPlan(order.id, {
    vehicleId: vehicleId || undefined, trailerId: trailerId || undefined, payloadT: payloadT || undefined,
    volumeM3: volumeM3 || undefined,
  }, true);
  const vehicles = lookups.data?.vehicles ?? [];

  const submit = async () => {
    try {
      const out = await plan.mutateAsync({ id: order.id, body: {
        vehicleId: vehicleId || undefined, trailerId: trailerId || undefined, driverId: driverId || undefined,
        payloadT: toNum(payloadT), volumeM3: toNum(volumeM3), plannedDate: plannedDate || undefined,
      } });
      notify.success(tx('{{count}} trip(s) planned', { count: out.createdTripIds?.length ?? 0 }));
      onClose();
    } catch (err) {
      notify.error(apiMessage(err, tx('Could not plan the loads')));
    }
  };

  return (
    <Dialog open onOpenChange={(o) => { if (!o) onClose(); }}>
      <DialogContent className="max-w-2xl">
        <DialogHeader>
          <DialogTitle><Layers className="mr-1.5 inline h-4 w-4" />{tx('Plan loads for {{code}}', { code: order.code })}</DialogTitle>
          <DialogDescription>{tx('Loads respect the truck payload (t) and body volume (m³). Choose a truck to assign all loads to it.')}</DialogDescription>
        </DialogHeader>
        <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
          <Field label={tx('Truck')}>
            <OptionalSelect value={vehicleId} onChange={setVehicleId} noneLabel={tx('Capacity only')}
              options={vehicles.filter((v) => v.kind !== 'trailer').map((v) => ({ value: v.id, label: [v.code, v.registrationPlate, v.payloadCapacityT ? `${num(v.payloadCapacityT, 1)} t` : null].filter(Boolean).join(' · ') }))}
              placeholder={tx('Capacity only')} />
          </Field>
          <Field label={tx('Trailer')}>
            <OptionalSelect value={trailerId} onChange={setTrailerId} noneLabel={tx('No trailer')} disabled={!vehicleId}
              options={vehicles.filter((v) => v.kind === 'trailer').map((v) => ({ value: v.id, label: [v.code, v.payloadCapacityT ? `${num(v.payloadCapacityT, 1)} t` : null].filter(Boolean).join(' · ') }))}
              placeholder={tx('No trailer')} />
          </Field>
          <Field label={tx('Driver')}>
            <OptionalSelect value={driverId} onChange={setDriverId} noneLabel={tx('Later')} disabled={!vehicleId}
              options={(lookups.data?.drivers ?? []).map((d) => ({ value: d.id, label: `${d.code} · ${d.name}` }))} placeholder={tx('Later')} />
          </Field>
          <Field label={tx('Planned date')}><Input type="date" value={plannedDate} onChange={(e) => setPlannedDate(e.target.value)} /></Field>
          <Field label={tx('Payload (t)')}><Input type="number" min={0} step="0.1" placeholder={tx('From the truck')} value={payloadT} onChange={(e) => setPayloadT(e.target.value)} /></Field>
          <Field label={tx('Body volume (m³)')}><Input type="number" min={0} step="0.1" placeholder={tx('From the truck')} value={volumeM3} onChange={(e) => setVolumeM3(e.target.value)} /></Field>
        </div>
        <div className="max-h-64 space-y-2 overflow-y-auto">
          {preview.isError && <p className="text-sm text-destructive">{apiMessage(preview.error, tx('Could not plan the loads'))}</p>}
          {preview.data?.warnings.map((w) => <p key={w} className="text-sm text-amber-700">⚠ {w}</p>)}
          {preview.data?.loads.map((l, i) => (
            <div key={i} className="rounded-lg border p-2 text-sm">
              <div className="font-medium">{tx('Load {{n}}', { n: i + 1 })} · {num(l.tonnes, 2)} t · {num(l.m3, 2)} m³</div>
              {l.lines.map((x) => <div key={x.deliveryOrderLineId} className="text-xs text-muted-foreground">{x.productName}: {num(x.quantity, 3)} {x.uomCode ?? ''}</div>)}
            </div>
          ))}
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={onClose}>{tx('Cancel')}</Button>
          <Button disabled={plan.isPending || !preview.data} onClick={submit}>
            {tx('Create {{count}} trip(s)', { count: preview.data?.loads.length ?? 0 })}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

/** One trip with chosen quantities. */
function AddTripDialog({ order, onClose }: { order: DeliveryOrderDetail; onClose: () => void }) {
  const add = useAddTrip();
  const [qty, setQty] = useState<Record<string, string>>({});
  const [plannedDate, setPlannedDate] = useState(order.requestedDate);
  const lines = order.lines.filter((l) => Number(l.remainingQty) > 0);
  const chosen = lines.filter((l) => Number(qty[l.id]) > 0);
  const submit = async () => {
    try {
      const out = await add.mutateAsync({ id: order.id, body: {
        plannedDate, lines: chosen.map((l) => ({ deliveryOrderLineId: l.id, quantity: toNum(qty[l.id]) })),
      } });
      notify.success(tx('Trip {{code}} planned', { code: out.code }));
      onClose();
    } catch (err) {
      notify.error(apiMessage(err, tx('Could not add the trip')));
    }
  };
  return (
    <Dialog open onOpenChange={(o) => { if (!o) onClose(); }}>
      <DialogContent>
        <DialogHeader><DialogTitle>{tx('Add trip')}</DialogTitle></DialogHeader>
        <div className="space-y-3">
          <Field label={tx('Planned date')}><Input type="date" value={plannedDate} onChange={(e) => setPlannedDate(e.target.value)} /></Field>
          {lines.map((l) => (
            <Field key={l.id} label={`${l.productName} (${tx('max')} ${num(l.remainingQty, 3)} ${l.uomCode ?? ''})`}>
              <Input type="number" min={0} max={Number(l.remainingQty)} step="0.001" value={qty[l.id] ?? ''} onChange={(e) => setQty({ ...qty, [l.id]: e.target.value })} />
            </Field>
          ))}
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={onClose}>{tx('Cancel')}</Button>
          <Button disabled={add.isPending || chosen.length === 0} onClick={submit}>{tx('Add trip')}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

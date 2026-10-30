import { useEffect, useMemo, useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { ArrowLeft, Ban, FileText, Gauge, Keyboard, PackageCheck, Printer, RefreshCw, Scale, Truck } from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Textarea } from '@/components/ui/textarea';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { StatusPill } from '@/components/list';
import { useAuthStore } from '@/stores/auth.store';
import { notify, apiMessage } from '@/lib/notify';
import { dateTime } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';
import {
  SOURCE_LABEL, SUBJECT_LABEL, TRIP_FEED_LABEL, useCancelTicket, useFeedTrip, useLiveRead, usePrintTicket, useTicket,
  useTicketGoodsReceipt, useUseStoredTare, useVoidTicket, useWeigh, useWeighbridgeLookups, type LiveReading, type Ticket,
} from '@/features/fleet/weighbridge-api';
import { Field, OptionalSelect } from '../fleet-ui';
import { ManualFlag, Row, TicketStatusPill, WeightDisplay } from './weighbridge-ui';
import { kg, qty } from './weighbridge-format';

/** The weighing screen (fleet 1.4): big weight read-out, capture buttons, stored tare, manual entry, print, void. */
export function TicketDetailPage() {
  const { id } = useParams();
  const navigate = useNavigate();
  const has = useAuthStore((s) => s.hasPermission);
  const ticket = useTicket(id);
  const lookups = useWeighbridgeLookups();
  const live = useLiveRead();
  const weigh = useWeigh();
  const storedTare = useUseStoredTare();
  const print = usePrintTicket();
  const [deviceId, setDeviceId] = useState('');
  const [reading, setReading] = useState<LiveReading | null>(null);
  const [readError, setReadError] = useState<string | null>(null);
  const [dialog, setDialog] = useState<'manual' | 'void' | 'cancel' | 'grn' | null>(null);
  const [saveTare, setSaveTare] = useState(false);
  const t = ticket.data;
  const devices = useMemo(() => (lookups.data?.devices ?? []).filter((d) => !t || d.stationId === t.stationId), [lookups.data, t]);
  useEffect(() => {
    if (!deviceId && devices.length > 0) setDeviceId(devices[0].id);
  }, [deviceId, devices]);

  if (ticket.isLoading) return <div className="p-6 text-sm text-muted-foreground">…</div>;
  if (ticket.isError || !t) return <div className="p-6 text-sm text-rose-600">{tx('Could not load the ticket.')}</div>;

  const canWeigh = has(PERMISSIONS.weighbridge.weigh);
  const slot: 'first' | 'second' | null = t.status === 'open' ? 'first' : t.status === 'first_weighed' ? 'second' : null;
  const readNow = async () => {
    if (!deviceId) return;
    try {
      setReadError(null);
      setReading(await live.mutateAsync(deviceId));
    } catch (err) {
      setReading(null);
      setReadError(apiMessage(err, tx('Could not read the indicator')));
    }
  };
  const capture = async () => {
    if (!slot || !deviceId) return;
    try {
      const next = await weigh.mutateAsync({ id: t.id, slot, body: { deviceId, saveTare: slot === 'second' ? saveTare : undefined } });
      notify.success(next.status === 'completed' ? tx('Ticket completed: net {{net}}', { net: kg(next.netKg) })
        : next.status === 'exception' ? tx('Out of tolerance: the ticket needs an approved exception')
          : tx('First weight captured: {{weight}}', { weight: kg(next.firstWeightKg) }));
    } catch (err) {
      notify.error(apiMessage(err, tx('Could not capture the weight')));
    }
  };
  const useTare = async () => {
    try {
      const next = await storedTare.mutateAsync(t.id);
      notify.success(next.status === 'completed' ? tx('Ticket completed: net {{net}}', { net: kg(next.netKg) }) : tx('Stored tare applied'));
    } catch (err) {
      notify.error(apiMessage(err, tx('Could not use the stored tare')));
    }
  };
  const doPrint = () => print.mutateAsync(t.id).catch((err) => notify.error(apiMessage(err, tx('Could not print the ticket'))));
  const shown = reading ? kg(reading.weightKg) : '— kg';

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-center gap-3">
        <Button variant="ghost" size="sm" onClick={() => navigate('/fleet/weighbridge')}><ArrowLeft className="mr-1 h-4 w-4" /> {tx('Queue')}</Button>
        <h1 className="text-2xl font-semibold">{t.ticketNumber}</h1>
        <TicketStatusPill status={t.status} />
        {t.isManual && <ManualFlag />}
        {t.usedStoredTare && <StatusPill tone="info" dot={false}>{tx('Stored tare')}</StatusPill>}
        <span className="text-sm text-muted-foreground">{t.station.code} · {SUBJECT_LABEL[t.subjectType]}</span>
      </div>

      <div className="grid grid-cols-1 gap-4 lg:grid-cols-3">
        <div className="space-y-4 lg:col-span-2">
          {slot && canWeigh && (
            <Card>
              <CardContent className="space-y-3 pt-6">
                <WeightDisplay value={shown} tone={readError ? 'error' : reading ? 'stable' : 'idle'}
                  caption={readError ?? (reading ? `${tx('Stable')} · ${reading.rawFrame}` : tx('Read the scale to see the weight on the platform'))} />
                <div className="grid grid-cols-1 gap-2 sm:grid-cols-3">
                  <OptionalSelect value={deviceId} onChange={setDeviceId} placeholder={tx('Indicator')}
                    options={devices.map((d) => ({ value: d.id, label: `${d.code}${d.name ? ` — ${d.name}` : ''}` }))} />
                  <Button size="lg" variant="outline" onClick={readNow} disabled={!deviceId || live.isPending}>
                    <RefreshCw className="mr-1.5 h-5 w-5" /> {tx('Read scale')}
                  </Button>
                  <Button size="lg" className="h-14 text-base" onClick={capture} disabled={!deviceId || weigh.isPending}>
                    <Gauge className="mr-1.5 h-5 w-5" /> {slot === 'first' ? tx('Capture first weight') : tx('Capture second weight')}
                  </Button>
                </div>
                <div className="flex flex-wrap items-center gap-2">
                  {t.firstSource !== 'stored_tare' && (
                    <Button variant="outline" onClick={useTare} disabled={storedTare.isPending}><Truck className="mr-1.5 h-4 w-4" /> {tx('Use stored tare')}</Button>
                  )}
                  {has(PERMISSIONS.weighbridge.manualEntry) && (
                    <Button variant="outline" onClick={() => setDialog('manual')}><Keyboard className="mr-1.5 h-4 w-4" /> {tx('Manual entry')}</Button>
                  )}
                  {slot === 'second' && (
                    <label className="flex items-center gap-2 text-sm">
                      <input type="checkbox" checked={saveTare} onChange={(e) => setSaveTare(e.target.checked)} /> {tx('Save the tare as stored tare')}
                    </label>
                  )}
                  <Button variant="ghost" className="ml-auto text-rose-600" onClick={() => setDialog('cancel')}><Ban className="mr-1.5 h-4 w-4" /> {tx('Cancel ticket')}</Button>
                </div>
              </CardContent>
            </Card>
          )}

          <Card>
            <CardHeader><CardTitle className="text-base">{tx('Weighings')}</CardTitle></CardHeader>
            <CardContent className="space-y-3">
              {(['first', 'second'] as const).map((s) => <WeighingLine key={s} t={t} slot={s} />)}
              <div className="grid grid-cols-3 gap-2 text-center">
                <div className="rounded-lg border p-2"><div className="text-xs text-muted-foreground">{tx('Gross')}</div><div className="text-lg font-semibold tabular-nums">{kg(t.grossKg)}</div></div>
                <div className="rounded-lg border p-2"><div className="text-xs text-muted-foreground">{tx('Tare')}</div><div className="text-lg font-semibold tabular-nums">{kg(t.tareKg)}</div></div>
                <div className="rounded-lg border bg-primary/5 p-2"><div className="text-xs text-muted-foreground">{tx('Net')}</div><div className="text-2xl font-bold tabular-nums">{kg(t.netKg)}</div></div>
              </div>
              {t.netQty && <p className="text-right text-sm">{tx('Net quantity')}: <b>{qty(t.netQty, t.netUomCode)}</b></p>}
              {t.varianceKg !== null && (
                <p className={`text-sm ${t.withinTolerance ? 'text-emerald-700' : 'text-rose-600'}`}>
                  {tx('Variance {{variance}} ({{pct}} %), allowed ±{{allowed}}', { variance: kg(t.varianceKg), pct: t.variancePct ?? '—', allowed: kg(t.allowedVarianceKg) })}
                  {' · '}{t.withinTolerance ? tx('within tolerance') : tx('out of tolerance')}
                </p>
              )}
            </CardContent>
          </Card>

          {(t.exceptions ?? []).length > 0 && (
            <Card>
              <CardHeader><CardTitle className="text-base">{tx('Exceptions')}</CardTitle></CardHeader>
              <CardContent className="space-y-2 text-sm">
                {(t.exceptions ?? []).map((e) => (
                  <div key={e.id} className="flex flex-wrap items-center justify-between gap-2 rounded-lg border p-2">
                    <span>{tx('Variance {{variance}} vs allowed ±{{allowed}}', { variance: kg(e.varianceKg), allowed: kg(e.allowedVarianceKg) })}</span>
                    <StatusPill tone={e.status === 'pending' ? 'warning' : e.status === 'approved' ? 'success' : 'neutral'}>{e.status}</StatusPill>
                    {e.decisionNotes && <span className="w-full text-xs text-muted-foreground">{e.decisionNotes}</span>}
                  </div>
                ))}
                {t.status === 'exception' && <Button variant="outline" asChild><Link to="/fleet/weighbridge/exceptions">{tx('Open the exception queue')}</Link></Button>}
              </CardContent>
            </Card>
          )}
        </div>

        <div className="space-y-4">
          <Card>
            <CardHeader><CardTitle className="text-base">{tx('Ticket')}</CardTitle></CardHeader>
            <CardContent>
              <Row label={tx('Truck')}>{t.registrationPlate}{t.vehicle ? ` (${t.vehicle.code})` : ''}</Row>
              {t.trailerPlate && <Row label={tx('Trailer plate')}>{t.trailerPlate}</Row>}
              {(t.driverName || t.driver) && <Row label={tx('Driver')}>{t.driverName ?? t.driver?.code}</Row>}
              {t.partner && <Row label={t.direction === 'inbound' ? tx('Supplier') : tx('Customer')}>{t.partner.name}</Row>}
              {t.purchaseOrder && <Row label={tx('Purchase order')}>{t.purchaseOrder.orderNumber}</Row>}
              {t.deliveryOrderId && (
                <Row label={tx('Delivery order')}>
                  <Link className="text-primary" to={`/fleet/delivery-orders/${t.deliveryOrderId}`}>{t.deliveryOrder?.code ?? t.deliveryOrderId}</Link>
                </Row>
              )}
              {t.tripId && (
                <Row label={tx('Trip')}>
                  <Link className="text-primary" to={`/fleet/trips/${t.tripId}`}>{t.trip?.code ?? t.tripId}</Link>
                  {t.trip && <span className="ml-1 text-xs text-muted-foreground">({t.trip.status.replace('_', ' ')})</span>}
                </Row>
              )}
              {t.referenceNumber && <Row label={tx('Reference')}>{t.referenceNumber}</Row>}
              {t.product && <Row label={tx('Product')}>{t.product.name}</Row>}
              {t.orderedQty && <Row label={tx('Ordered')}>{qty(t.orderedQty, t.orderedUomCode)} ({kg(t.expectedKg)})</Row>}
              <Row label={tx('Registered')}>{dateTime(t.createdAt)}</Row>
              {t.completedAt && <Row label={tx('Completed')}>{dateTime(t.completedAt)}</Row>}
              {t.printCount > 0 && <Row label={tx('Printed')}>{tx('{{count}} time(s)', { count: t.printCount })}</Row>}
              {t.reissuedFrom && <Row label={tx('Reissue of')}><Link className="text-primary" to={`/fleet/weighbridge/tickets/${t.reissuedFrom.id}`}>{t.reissuedFrom.ticketNumber}</Link></Row>}
              {t.replacedBy && <Row label={tx('Replaced by')}><Link className="text-primary" to={`/fleet/weighbridge/tickets/${t.replacedBy.id}`}>{t.replacedBy.ticketNumber}</Link></Row>}
              {t.voidReason && <Row label={tx('Void reason')}>{t.voidReason}</Row>}
              {t.cancelReason && <Row label={tx('Cancel reason')}>{t.cancelReason}</Row>}
            </CardContent>
          </Card>
          {t.tripId && t.tripFeedStatus && <TripFeedCard t={t} canWeigh={canWeigh} />}
          {(t.status === 'completed' || t.status === 'void') && (
            <Card>
              <CardContent className="space-y-2 pt-6">
                <Button className="w-full" size="lg" onClick={doPrint} disabled={print.isPending}>
                  <Printer className="mr-1.5 h-5 w-5" /> {t.printCount > 0 ? tx('Print copy') : tx('Print ticket')}
                </Button>
                {t.status === 'completed' && has(PERMISSIONS.weighbridge.void) && (
                  <Button className="w-full" variant="outline" onClick={() => setDialog('void')}><FileText className="mr-1.5 h-4 w-4" /> {tx('Void & reissue')}</Button>
                )}
                {t.subjectType === 'purchase_receipt' && t.status === 'completed' && (
                  t.goodsReceipt ? (
                    <p className="text-sm"><PackageCheck className="mr-1 inline h-4 w-4" />{tx('Goods receipt {{number}} ({{status}})', { number: t.goodsReceipt.receiptNumber, status: t.goodsReceipt.status })}</p>
                  ) : canWeigh && (
                    <Button className="w-full" variant="outline" onClick={() => setDialog('grn')}><PackageCheck className="mr-1.5 h-4 w-4" /> {tx('Create goods receipt')}</Button>
                  )
                )}
              </CardContent>
            </Card>
          )}
        </div>
      </div>
      {dialog === 'manual' && slot && <ManualDialog t={t} slot={slot} onClose={() => setDialog(null)} />}
      {dialog === 'void' && <VoidDialog t={t} onClose={() => setDialog(null)} onDone={(nid) => { setDialog(null); if (nid) navigate(`/fleet/weighbridge/tickets/${nid}`); }} />}
      {dialog === 'cancel' && <CancelDialog t={t} onClose={() => setDialog(null)} />}
      {dialog === 'grn' && <GoodsReceiptDialog t={t} onClose={() => setDialog(null)} />}
    </div>
  );
}

/** 1.4b: how the completed ticket fed its trip; retry when it could not (e.g. after adding a kg-per-unit setting). */
function TripFeedCard({ t, canWeigh }: { t: Ticket; canWeigh: boolean }) {
  const feed = useFeedTrip();
  const status = t.tripFeedStatus!;
  const retry = async () => {
    try {
      await feed.mutateAsync(t.id);
      notify.success(tx('The trip now carries the net weight of this ticket'));
    } catch (err) {
      notify.error(apiMessage(err, tx('Could not feed the trip')));
    }
  };
  return (
    <Card>
      <CardHeader><CardTitle className="text-base">{tx('Trip loading')}</CardTitle></CardHeader>
      <CardContent className="space-y-2 text-sm">
        <StatusPill tone={status === 'fed' ? 'success' : status === 'failed' ? 'danger' : 'neutral'}>{TRIP_FEED_LABEL[status]}</StatusPill>
        {t.tripFeedMessage && <p className="text-muted-foreground">{t.tripFeedMessage}</p>}
        {t.status === 'completed' && status !== 'fed' && canWeigh && (
          <Button className="w-full" variant="outline" onClick={retry} disabled={feed.isPending}>
            <Truck className="mr-1.5 h-4 w-4" /> {tx('Feed the trip again')}
          </Button>
        )}
      </CardContent>
    </Card>
  );
}

function WeighingLine({ t, slot }: { t: Ticket; slot: 'first' | 'second' }) {
  const w = slot === 'first' ? t.firstWeightKg : t.secondWeightKg;
  const source = slot === 'first' ? t.firstSource : t.secondSource;
  const at = slot === 'first' ? t.firstWeighedAt : t.secondWeighedAt;
  const frame = slot === 'first' ? t.firstRawFrame : t.secondRawFrame;
  const reason = slot === 'first' ? t.firstManualReason : t.secondManualReason;
  const device = slot === 'first' ? t.firstDeviceId : t.secondDeviceId;
  const by = slot === 'first' ? t.firstBy : t.secondBy;
  return (
    <div className="flex flex-wrap items-center justify-between gap-2 rounded-lg border p-3">
      <div>
        <div className="text-xs text-muted-foreground">{slot === 'first' ? tx('First weighing') : tx('Second weighing')}</div>
        <div className="text-xl font-semibold tabular-nums">{w ? kg(w) : '—'}</div>
      </div>
      {source && (
        <div className="text-right text-xs text-muted-foreground">
          <div>{SOURCE_LABEL[source]}{device && t.devices?.[device] ? ` ${t.devices[device]}` : ''} · {at ? dateTime(at) : ''}</div>
          {frame && <div className="font-mono">{frame}</div>}
          {reason && <div className="text-violet-700">{reason}</div>}
          {by && t.users?.[by] && <div>{t.users[by]}</div>}
        </div>
      )}
    </div>
  );
}

function ManualDialog({ t, slot, onClose }: { t: Ticket; slot: 'first' | 'second'; onClose: () => void }) {
  const [weight, setWeight] = useState('');
  const [reason, setReason] = useState('');
  const weigh = useWeigh();
  const submit = async () => {
    try {
      await weigh.mutateAsync({ id: t.id, slot, body: { manualKg: Number(weight), manualReason: reason.trim() } });
      notify.success(tx('Manual weight recorded (flagged on the ticket)'));
      onClose();
    } catch (err) {
      notify.error(apiMessage(err, tx('Could not record the weight')));
    }
  };
  return (
    <Dialog open onOpenChange={(o) => !o && onClose()}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>{tx('Manual weight entry')}</DialogTitle>
          <DialogDescription>{tx('Manual weights are flagged on the ticket and in reports. Give the reason.')}</DialogDescription>
        </DialogHeader>
        <div className="space-y-3">
          <Field label={tx('Weight (kg) *')}><Input className="h-12 text-lg" type="number" inputMode="decimal" value={weight} onChange={(e) => setWeight(e.target.value)} /></Field>
          <Field label={tx('Reason *')}><Textarea value={reason} onChange={(e) => setReason(e.target.value)} rows={2} /></Field>
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={onClose}>{tx('Cancel')}</Button>
          <Button onClick={submit} disabled={!(Number(weight) > 0) || !reason.trim() || weigh.isPending}>{tx('Record weight')}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

function VoidDialog({ t, onClose, onDone }: { t: Ticket; onClose: () => void; onDone: (reissuedId?: string) => void }) {
  const [reason, setReason] = useState('');
  const [reissue, setReissue] = useState(true);
  const [copyWeights, setCopyWeights] = useState(true);
  const [reference, setReference] = useState(t.referenceNumber ?? '');
  const [orderedQty, setOrderedQty] = useState(t.orderedQty ?? '');
  const voidTicket = useVoidTicket();
  const submit = async () => {
    try {
      const corrections: Record<string, unknown> = {};
      if (reference.trim() && reference.trim() !== (t.referenceNumber ?? '')) corrections.referenceNumber = reference.trim();
      if (orderedQty && orderedQty !== (t.orderedQty ?? '')) corrections.orderedQty = Number(orderedQty);
      const r = await voidTicket.mutateAsync({ id: t.id, body: { reason: reason.trim(), reissue, copyWeights, corrections: reissue ? corrections : undefined } });
      notify.success(r.reissued ? tx('Ticket voided and reissued as {{number}}', { number: r.reissued.ticketNumber }) : tx('Ticket voided'));
      onDone(r.reissued?.id);
    } catch (err) {
      notify.error(apiMessage(err, tx('Could not void the ticket')));
    }
  };
  return (
    <Dialog open onOpenChange={(o) => !o && onClose()}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>{tx('Void & reissue')}</DialogTitle>
          <DialogDescription>{tx('A completed ticket is never edited: void it with a reason and reissue a corrected ticket.')}</DialogDescription>
        </DialogHeader>
        <div className="space-y-3">
          <Field label={tx('Reason *')}><Textarea value={reason} onChange={(e) => setReason(e.target.value)} rows={2} /></Field>
          <label className="flex items-center gap-2 text-sm"><input type="checkbox" checked={reissue} onChange={(e) => setReissue(e.target.checked)} /> {tx('Reissue a corrected ticket')}</label>
          {reissue && (
            <>
              <label className="flex items-center gap-2 text-sm"><input type="checkbox" checked={copyWeights} onChange={(e) => setCopyWeights(e.target.checked)} /> {tx('Keep the weighings (otherwise weigh again)')}</label>
              <Field label={tx('Reference (delivery note no.)')}><Input value={reference} onChange={(e) => setReference(e.target.value)} /></Field>
              <Field label={tx('Ordered / expected quantity')}><Input type="number" value={orderedQty} onChange={(e) => setOrderedQty(e.target.value)} /></Field>
            </>
          )}
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={onClose}>{tx('Cancel')}</Button>
          <Button variant="destructive" onClick={submit} disabled={!reason.trim() || voidTicket.isPending}>{tx('Void ticket')}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

function CancelDialog({ t, onClose }: { t: Ticket; onClose: () => void }) {
  const [reason, setReason] = useState('');
  const cancel = useCancelTicket();
  return (
    <Dialog open onOpenChange={(o) => !o && onClose()}>
      <DialogContent>
        <DialogHeader><DialogTitle>{tx('Cancel ticket')}</DialogTitle></DialogHeader>
        <Field label={tx('Reason *')}><Textarea value={reason} onChange={(e) => setReason(e.target.value)} rows={2} /></Field>
        <DialogFooter>
          <Button variant="outline" onClick={onClose}>{tx('Back')}</Button>
          <Button variant="destructive" disabled={!reason.trim() || cancel.isPending} onClick={() => cancel.mutateAsync({ id: t.id, reason: reason.trim() })
            .then(() => { notify.success(tx('Ticket cancelled')); onClose(); })
            .catch((err) => notify.error(apiMessage(err, tx('Could not cancel the ticket'))))}>{tx('Cancel ticket')}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

function GoodsReceiptDialog({ t, onClose }: { t: Ticket; onClose: () => void }) {
  const lookups = useWeighbridgeLookups();
  const [locationId, setLocationId] = useState(t.locationId ?? '');
  const grn = useTicketGoodsReceipt();
  return (
    <Dialog open onOpenChange={(o) => !o && onClose()}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle><Scale className="mr-1 inline h-4 w-4" />{tx('Create goods receipt')}</DialogTitle>
          <DialogDescription>{tx('A draft goods receipt with the net quantity is created in Purchases; the store posts it.')}</DialogDescription>
        </DialogHeader>
        <Field label={tx('Receiving store *')}>
          <OptionalSelect value={locationId} onChange={setLocationId} placeholder={tx('Choose a store')}
            options={(lookups.data?.locations ?? []).map((l) => ({ value: l.id, label: `${l.code} — ${l.name}` }))} />
        </Field>
        <DialogFooter>
          <Button variant="outline" onClick={onClose}>{tx('Cancel')}</Button>
          <Button disabled={!locationId || grn.isPending} onClick={() => grn.mutateAsync({ id: t.id, locationId })
            .then(() => { notify.success(tx('Goods receipt created')); onClose(); })
            .catch((err) => notify.error(apiMessage(err, tx('Could not create the goods receipt'))))}>{tx('Create')}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

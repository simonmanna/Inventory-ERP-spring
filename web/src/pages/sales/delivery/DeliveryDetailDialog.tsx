import { useState } from 'react';
import { PERMISSIONS } from '@erp/shared';
import {
  Ban, Camera, ClipboardCheck, Download, History, Navigation, PackageCheck, Pencil, Printer, RotateCcw,
  ShieldCheck, Truck, Undo2, XCircle, type LucideIcon,
} from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Skeleton } from '@/components/ui/skeleton';
import { Dialog, DialogContent, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs';
import { AttachmentPanel } from '@/components/dms/AttachmentPanel';
import { ColumnPicker, useColumns, type ColumnDef } from '@/components/column-picker';
import { api } from '@/lib/api';
import { date as fmtDate, dateTime, money } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';
import { cn } from '@/lib/utils';
import { useAuthStore } from '@/stores/auth.store';
import {
  openDeliveryNote, readDataUrl, useDelivery, useDeliveryAction, useDeliveryHistory, useDeliverySummary, type HistoryEntry, type SalesDelivery,
} from '@/features/sales/delivery-api';
import { DeliveryNotePreview, ReturnsList, orderReturnView } from './delivery-docs';
import { Detail, METHOD_META, StageBadge, n, qty, scheduleLabel } from './delivery-ui';
import { PickedCell } from './HandoverDeliveryView';
import { DeliveryWeighbridgeTab, WeighSummary } from './DeliveryWeighbridge';
import { CustomerWeightDialog, CustomerWeightPanel, customerWeightNoteLine } from './CustomerWeight';
import { QuantityBucketsPanel } from './DeliveryQuantities';
import { GatePassPanel } from '@/features/gate-pass/GatePassPanel';
import { QualityChecksPanel } from '@/pages/quality-control/QualityChecksPanel';

type LineCol = 'product' | 'scheduled' | 'deadline' | 'planned' | 'picked' | 'dispatched' | 'delivered' | 'returned' | 'rejected'
  | 'damaged' | 'shortage' | 'batch';

const LINE_COLS: ColumnDef<LineCol>[] = [
  { key: 'product', label: tx('Product'), locked: true },
  { key: 'scheduled', label: tx('Date scheduled') },
  { key: 'deadline', label: tx('Deadline') },
  { key: 'planned', label: tx('Demand'), locked: true },
  { key: 'picked', label: tx('Picked') },
  { key: 'dispatched', label: tx('Dispatched') },
  { key: 'delivered', label: tx('Accepted') },
  { key: 'returned', label: tx('Returned') },
  { key: 'rejected', label: tx('Rejected') },
  { key: 'damaged', label: tx('Damaged') },
  { key: 'shortage', label: tx('Shortage') },
  { key: 'batch', label: tx('Batch'), defaultVisible: false },
];

export type DeliveryActionKey =
  | 'view' | 'edit' | 'approve' | 'dispatch' | 'depart' | 'confirm' | 'fail' | 'return' | 'cancel' | 'reschedule' | 'note' | 'print';

export interface DeliveryActionDef { key: DeliveryActionKey; label: string; icon: LucideIcon; danger?: boolean; primary?: boolean }

/** Which actions one delivery offers to the current user, in display order. */
export function useDeliveryActionDefs() {
  const has = useAuthStore((s) => s.hasPermission);
  const P = PERMISSIONS.sales;
  return (d: Pick<SalesDelivery, 'stage' | 'status' | 'approvalRequired' | 'approvedAt' | 'deliveryNoteNumber'>, orderOpen: boolean): DeliveryActionDef[] => {
    const out: DeliveryActionDef[] = [];
    const waiting = d.approvalRequired && !d.approvedAt;
    if (d.stage === 'scheduled') {
      if (waiting && has(P.deliveryApprove)) out.push({ key: 'approve', label: tx('Approve'), icon: ShieldCheck, primary: true });
      if (!waiting && orderOpen && has(P.deliveryDispatch)) out.push({ key: 'dispatch', label: tx('Dispatch (issue stock)'), icon: Truck, primary: true });
      if (has(P.deliveryWrite)) out.push({ key: 'edit', label: d.status === 'planned' ? tx('Edit / reschedule') : tx('Edit details'), icon: Pencil });
      if (has(P.deliveryCancel)) out.push({ key: 'cancel', label: tx('Cancel delivery'), icon: Ban, danger: true });
    }
    if (d.stage === 'dispatched' || d.stage === 'in_transit') {
      if (d.stage === 'dispatched' && has(P.deliveryDispatch)) out.push({ key: 'depart', label: tx('Mark in transit'), icon: Navigation });
      if (has(P.deliveryConfirm)) out.push({ key: 'confirm', label: tx('Confirm delivery'), icon: ClipboardCheck, primary: true });
      if (has(P.deliveryWrite)) out.push({ key: 'edit', label: tx('Update tracking'), icon: Pencil });
      if (has(P.deliveryConfirm)) {
        out.push({ key: 'fail', label: tx('Delivery attempt failed'), icon: XCircle, danger: true });
        out.push({ key: 'return', label: tx('Customer returned load'), icon: Undo2, danger: true });
      }
    }
    if ((d.stage === 'delivered' || d.stage === 'partially_delivered') && has(P.deliveryWrite)) {
      out.push({ key: 'edit', label: tx('Edit trip costs'), icon: Pencil });
    }
    if ((d.stage === 'failed' || d.stage === 'returned' || d.stage === 'cancelled') && orderOpen && has(P.deliveryWrite)) {
      out.push({ key: 'reschedule', label: tx('Reschedule'), icon: RotateCcw, primary: d.stage === 'failed' });
    }
    if (d.deliveryNoteNumber) {
      out.push({ key: 'note', label: tx('Download delivery note'), icon: Download });
      out.push({ key: 'print', label: tx('Print delivery note'), icon: Printer });
    }
    return out;
  };
}

/** One delivery in full: logistics, quantities per product, proof of delivery, documents and history. */
export function DeliveryDetailDialog({ id, orderOpen, currency, onAction, onClose }: {
  id: string; orderOpen: boolean; currency?: string; onAction: (key: DeliveryActionKey, d: SalesDelivery) => void; onClose: () => void;
}) {
  const { data: d, isLoading } = useDelivery(id);
  const summary = useDeliverySummary(d?.orderId ?? '');
  const cols = useColumns<LineCol>('delivery-lines', LINE_COLS);
  const on = cols.shown;
  const [tab, setTab] = useState('details');
  const history = useDeliveryHistory(id, tab === 'history');
  const defs = useDeliveryActionDefs();
  const canWrite = useAuthStore((s) => s.hasPermission(PERMISSIONS.sales.deliveryWrite));
  const canConfirm = useAuthStore((s) => s.hasPermission(PERMISSIONS.sales.deliveryConfirm));
  const [weighing, setWeighing] = useState(false);
  const addPhoto = useDeliveryAction(async (f: File) => api.post(`/sales/deliveries/${id}/photos`, { dataUrl: await readDataUrl(f), caption: f.name }),
    tx('Photo added'));

  return (
    <Dialog open onOpenChange={(v) => !v && onClose()}>
      <DialogContent className="max-h-[92vh] max-w-5xl overflow-y-auto">
        <DialogHeader>
          <DialogTitle className="flex flex-wrap items-center gap-2">
            <Truck className="h-5 w-5 text-primary" />{d?.code ?? tx('Delivery')}{d && <StageBadge stage={d.stage} />}
            {d?.deliveryNoteNumber && <span className="text-xs font-normal text-muted-foreground">{tx('Delivery note')} {d.deliveryNoteNumber}</span>}
          </DialogTitle>
        </DialogHeader>
        {isLoading || !d ? <div className="space-y-2"><Skeleton className="h-6 w-1/2" /><Skeleton className="h-40 w-full" /></div> : (
          <>
            <div className="flex flex-wrap gap-2">
              {defs(d, orderOpen).map((a) => (
                <Button key={a.key} size="sm" variant={a.primary ? 'default' : a.danger ? 'ghost' : 'outline'}
                  className={cn(a.danger && 'text-rose-600 hover:bg-rose-50 hover:text-rose-700 dark:hover:bg-rose-950/40')}
                  onClick={() => (a.key === 'note' || a.key === 'print' ? openDeliveryNote(d, a.key === 'note' ? 'download' : 'print') : onAction(a.key, d))}>
                  <a.icon className="mr-1.5 h-4 w-4" />{a.label}
                </Button>
              ))}
            </div>
            {(d.cancelReason || d.returnReason) && (
              <p className="rounded-lg border border-rose-200 bg-rose-50 px-3 py-2 text-sm text-rose-800 dark:border-rose-900 dark:bg-rose-950/30 dark:text-rose-200">
                {d.stage === 'cancelled' ? tx('Cancelled') : d.attemptFailed ? tx('Attempt failed') : tx('Returned')}: {d.cancelReason ?? d.returnReason}
              </p>
            )}
            <GatePassPanel sourceType="delivery" sourceId={d.id} />
            <QualityChecksPanel sources={[{ sourceType: 'sales_delivery', sourceId: d.id }]} />
            <Tabs value={tab} onValueChange={setTab}>
              <TabsList>
                <TabsTrigger value="details">{tx('Details')}</TabsTrigger>
                <TabsTrigger value="weighbridge">{tx('Weighbridge')}{d.weighbridgeTickets?.length ? ` (${d.weighbridgeTickets.length})` : ''}</TabsTrigger>
                <TabsTrigger value="note">{tx('Delivery note')}</TabsTrigger>
                <TabsTrigger value="returns">{tx('Returns')}</TabsTrigger>
                <TabsTrigger value="pod">{tx('Proof of delivery')}</TabsTrigger>
                <TabsTrigger value="documents">{tx('Documents')}{d.photos?.length ? ` (${d.photos.length})` : ''}</TabsTrigger>
                <TabsTrigger value="history">{tx('History')}</TabsTrigger>
              </TabsList>

              <TabsContent value="details" className="space-y-4">
                <dl className="grid gap-x-6 gap-y-3 rounded-xl border p-4 sm:grid-cols-2 lg:grid-cols-4">
                  <Detail label={tx('Method')}>{d.deliveryMethod ? METHOD_META[d.deliveryMethod].label : '—'}</Detail>
                  <Detail label={tx('Scheduled')}>{scheduleLabel(d.plannedDate, d.scheduledTime)}</Detail>
                  <Detail label={d.deliveryMethod === 'customer_collect' ? tx('Collect from') : tx('Dispatch warehouse')}>{d.locationName}</Detail>
                  <Detail label={d.deliveryMethod === 'warehouse_transfer' ? tx('Destination warehouse') : tx('Delivery address')}>
                    {d.deliveryMethod === 'warehouse_transfer' ? d.destinationLocationName : d.deliveryAddress}</Detail>
                  <Detail label={tx('Driver')}>{[d.driverName, d.driverPhone].filter(Boolean).join(' · ') || null}</Detail>
                  <Detail label={tx('Vehicle')}>{[d.vehiclePlate, d.vehicleType].filter(Boolean).join(' · ') || null}</Detail>
                  <Detail label={tx('Transport company')}>{d.transportCompany}</Detail>
                  <Detail label={tx('Tracking no.')}>{d.trackingNumber}</Detail>
                  <Detail label={tx('Site contact')}>{[d.siteContactName, d.siteContactPhone].filter(Boolean).join(' · ') || null}</Detail>
                  <Detail label={tx('Route')}>{d.route}</Detail>
                  <Detail label={tx('Offloading equipment')}>{d.offloadingEquipment}</Detail>
                  <Detail label={tx('Expected arrival')}>{d.expectedArrivalAt ? dateTime(d.expectedArrivalAt) : null}</Detail>
                  <Detail label={tx('Dispatched')}>{d.dispatchedAt ? dateTime(d.dispatchedAt) : null}</Detail>
                  <Detail label={tx('Departed')}>{d.departedAt ? dateTime(d.departedAt) : null}</Detail>
                  <Detail label={tx('Actual arrival')}>{d.arrivedAt ? dateTime(d.arrivedAt) : null}</Detail>
                  <Detail label={tx('Delivered')}>{d.deliveredAt ? dateTime(d.deliveredAt) : null}</Detail>
                  <Detail label={tx('Transport cost')}>{d.transportCost != null ? money(d.transportCost, currency) : null}</Detail>
                  <Detail label={tx('Fuel / trip expenses')}>{d.tripExpenses != null ? money(d.tripExpenses, currency) : null}</Detail>
                  <Detail label={tx('Instructions')} className="sm:col-span-2">{d.notes}</Detail>
                </dl>
                <WeighSummary d={d} />
                <div className="overflow-x-auto rounded-xl border">
                  <div className="flex items-center justify-between border-b px-3 py-1.5">
                    <span className="text-xs font-medium text-muted-foreground">{tx('Operations')}</span><ColumnPicker columns={cols} />
                  </div>
                  <table className="w-full min-w-[720px] text-sm">
                    <thead className="bg-muted/40 text-left text-[11px] font-semibold uppercase tracking-wide text-muted-foreground">
                      <tr>
                        <th className="px-3 py-2">{tx('Product')}</th>
                        {on('scheduled') && <th className="px-3 py-2">{tx('Date scheduled')}</th>}
                        {on('deadline') && <th className="px-3 py-2">{tx('Deadline')}</th>}
                        <th className="px-3 py-2 text-right">{tx('Demand')}</th>
                        {on('picked') && <th className="px-3 py-2 text-right">{tx('Picked')}</th>}
                        {on('dispatched') && <th className="px-3 py-2 text-right">{tx('Dispatched')}</th>}
                        {on('delivered') && <th className="px-3 py-2 text-right">{tx('Accepted')}</th>}
                        {on('returned') && <th className="px-3 py-2 text-right">{tx('Returned')}</th>}
                        {on('rejected') && <th className="px-3 py-2 text-right">{tx('Rejected')}</th>}
                        {on('damaged') && <th className="px-3 py-2 text-right">{tx('Damaged')}</th>}
                        {on('shortage') && <th className="px-3 py-2 text-right">{tx('Shortage')}</th>}
                        {on('batch') && <th className="px-3 py-2">{tx('Batch')}</th>}
                      </tr>
                    </thead>
                    <tbody>
                      {(d.lines ?? []).map((l) => {
                        const deadline = summary.data?.deadline;
                        const late = !!deadline && !d.deliveredAt && deadline < new Date().toISOString().slice(0, 10);
                        return (
                          <tr key={l.id} className="border-t">
                            <td className="px-3 py-2 font-medium">{l.productName}<span className="ml-1 text-xs font-normal text-muted-foreground">{l.uomCode}</span></td>
                            {on('scheduled') && <td className="whitespace-nowrap px-3 py-2">{scheduleLabel(d.plannedDate, d.scheduledTime)}</td>}
                            {on('deadline') && <td className={cn('whitespace-nowrap px-3 py-2', late && 'font-medium text-rose-600')}>{deadline ? fmtDate(deadline) : '—'}</td>}
                            <td className="px-3 py-2 text-right tabular-nums">{qty(l.plannedQty)}</td>
                            {on('picked') && <td className="px-3 py-2 text-right tabular-nums"><PickedCell picked={n(l.loadedQty ?? l.dispatchedQty)} ordered={n(l.plannedQty)} /></td>}
                            {on('dispatched') && <td className="px-3 py-2 text-right tabular-nums">{l.dispatchedQty == null ? '—' : qty(l.dispatchedQty)}</td>}
                            {on('delivered') && <td className="px-3 py-2 text-right tabular-nums">{l.deliveredQty == null ? '—' : qty(l.deliveredQty)}</td>}
                            {on('returned') && <td className={cn('px-3 py-2 text-right tabular-nums', n(l.returnedQty) > 0 && 'font-medium text-orange-600')}>{n(l.returnedQty) > 0 ? qty(l.returnedQty) : '—'}</td>}
                            {on('rejected') && <td className={cn('px-3 py-2 text-right tabular-nums', n(l.rejectedQty) > 0 && 'text-orange-600')}>{qty(l.rejectedQty)}</td>}
                            {on('damaged') && <td className={cn('px-3 py-2 text-right tabular-nums', n(l.damagedQty) > 0 && 'text-rose-600')}>{qty(l.damagedQty)}</td>}
                            {on('shortage') && <td className={cn('px-3 py-2 text-right tabular-nums', n(l.shortageQty) > 0 && 'text-amber-600')}>{qty(l.shortageQty)}</td>}
                            {on('batch') && <td className="px-3 py-2 text-xs text-muted-foreground">{l.batchNumber ?? '—'}</td>}
                          </tr>
                        );
                      })}
                    </tbody>
                  </table>
                </div>
              </TabsContent>

              <TabsContent value="weighbridge"><DeliveryWeighbridgeTab d={d} /></TabsContent>

              <TabsContent value="note">
                <DeliveryNotePreview number={d.deliveryNoteNumber ?? tx('(numbered at dispatch)')} sourceLabel={tx('Order')} sourceNumber={d.orderNumber}
                  customer={d.partnerName} address={d.deliveryMethod === 'warehouse_transfer' ? d.destinationLocationName : d.deliveryAddress}
                  contact={[d.siteContactName, d.siteContactPhone].filter(Boolean).join(' · ') || null}
                  shippingDate={d.dispatchedAt} scheduledDate={d.plannedDate} deadline={summary.data?.deadline}
                  receivedBy={d.pod?.receiverName ?? null}
                  footnote={customerWeightNoteLine(d.customerWeight)}
                  lines={(d.lines ?? []).map((l) => ({ id: l.id, name: l.productName ?? '', uom: l.uomCode, ordered: n(l.plannedQty),
                    delivered: n(l.deliveredQty ?? (d.pod ? 0 : l.dispatchedQty)), returned: n(l.returnedQty) + n(l.rejectedQty) }))}
                  onPrint={d.deliveryNoteNumber ? () => void openDeliveryNote(d, 'print') : undefined}
                  onDownload={d.deliveryNoteNumber ? () => void openDeliveryNote(d, 'download') : undefined}
                  hint={d.deliveryNoteNumber ? undefined : tx('Preview — the delivery note gets its number and can be printed once the delivery is dispatched.')} />
              </TabsContent>

              <TabsContent value="returns" className="space-y-4">
                {(() => {
                  const back = (d.lines ?? []).filter((l) => n(l.returnedQty) + n(l.rejectedQty) + n(l.damagedQty) > 0);
                  const lineIds = new Set((d.lines ?? []).map((l) => l.orderLineId));
                  const rmas = (summary.data?.returns ?? []).filter((r) => r.lines.some((l) => lineIds.has(l.orderLineId))).map(orderReturnView);
                  return (
                    <>
                      <div className="rounded-xl border">
                        <p className="border-b bg-muted/30 px-4 py-2.5 text-sm font-semibold">{tx('Returned from this delivery')}</p>
                        {d.returnReason && <p className="px-4 pt-2 text-xs text-muted-foreground">{d.attemptFailed ? tx('Attempt failed') : tx('Returned')}: {d.returnReason}</p>}
                        {back.length ? (
                          <table className="w-full text-sm">
                            <thead className="text-left text-[11px] uppercase tracking-wide text-muted-foreground">
                              <tr><th className="px-4 py-2">{tx('Product')}</th><th className="px-3 py-2 text-right">{tx('Returned load')}</th>
                                <th className="px-3 py-2 text-right">{tx('Rejected')}</th><th className="px-3 py-2 text-right">{tx('Damaged')}</th></tr>
                            </thead>
                            <tbody>
                              {back.map((l) => (
                                <tr key={l.id} className="border-t">
                                  <td className="px-4 py-2">{l.productName}<span className="ml-1 text-xs text-muted-foreground">{l.uomCode}</span></td>
                                  <td className="px-3 py-2 text-right tabular-nums">{qty(l.returnedQty)}</td>
                                  <td className="px-3 py-2 text-right tabular-nums">{qty(l.rejectedQty)}</td>
                                  <td className="px-3 py-2 text-right tabular-nums">{qty(l.damagedQty)}</td>
                                </tr>
                              ))}
                            </tbody>
                          </table>
                        ) : <p className="px-4 py-4 text-sm text-muted-foreground">{tx('Nothing came back from this delivery.')}</p>}
                      </div>
                      <div>
                        <p className="mb-2 text-sm font-semibold">{tx('Customer returns for these products')}</p>
                        <ReturnsList returns={rmas} empty={tx('No customer returns for the products on this delivery.')} />
                      </div>
                    </>
                  );
                })()}
              </TabsContent>

              <TabsContent value="pod">
                {d.pod ? (
                  <div className="grid gap-4 md:grid-cols-[1fr_280px]">
                    <dl className="grid gap-x-6 gap-y-3 rounded-xl border p-4 sm:grid-cols-2">
                      <Detail label={tx('Received by')}>{d.pod.receiverName}</Detail>
                      <Detail label={tx('Receiver phone')}>{d.pod.receiverPhone}</Detail>
                      <Detail label={tx('Confirmed at')}>{dateTime(d.pod.receivedAt)}</Detail>
                      <Detail label={tx('Actual arrival')}>{d.arrivedAt ? dateTime(d.arrivedAt) : null}</Detail>
                      <Detail label={tx('Customer remarks')} className="sm:col-span-2">{d.pod.notes}</Detail>
                    </dl>
                    <div className="rounded-xl border p-3">
                      <p className="mb-2 text-[11px] font-medium uppercase tracking-wide text-muted-foreground">{tx('Signature')}</p>
                      {d.pod.signatureUrl ? <img src={d.pod.signatureUrl} alt={tx('Signature')} className="h-28 w-full rounded border bg-white object-contain" />
                        : <p className="text-sm text-muted-foreground">{tx('No signature captured.')}</p>}
                    </div>
                    <div className="md:col-span-2"><QuantityBucketsPanel d={d} /></div>
                    <div className="md:col-span-2">
                      <CustomerWeightPanel d={d} canRecord={canConfirm && (d.stage === 'delivered' || d.stage === 'partially_delivered')}
                        onRecord={() => setWeighing(true)} />
                    </div>
                  </div>
                ) : (
                  <Empty icon={PackageCheck} text={d.stage === 'dispatched' || d.stage === 'in_transit'
                    ? tx('Awaiting confirmation from the customer site.') : tx('Proof of delivery is recorded once the goods arrive.')} />
                )}
              </TabsContent>

              <TabsContent value="documents" className="space-y-4">
                <div className="rounded-xl border p-4">
                  <div className="mb-2 flex items-center justify-between">
                    <h4 className="flex items-center gap-1.5 text-sm font-semibold"><Camera className="h-4 w-4" />{tx('Delivery photos')}</h4>
                    {canWrite && (
                      <label className="inline-flex h-8 cursor-pointer items-center rounded-md border bg-background px-3 text-xs hover:bg-muted">
                        <Camera className="mr-1.5 h-3.5 w-3.5" />{tx('Add photo')}
                        <input type="file" accept="image/*" className="hidden" onChange={(e) => { const f = e.target.files?.[0]; e.target.value = ''; if (f) addPhoto.mutate(f); }} />
                      </label>
                    )}
                  </div>
                  {d.photos?.length ? (
                    <div className="grid grid-cols-3 gap-2 sm:grid-cols-5">
                      {d.photos.map((p) => (
                        <a key={p.id} href={p.url ?? undefined} target="_blank" rel="noreferrer" className="group block overflow-hidden rounded-lg border">
                          {p.url ? <img src={p.url} alt={p.caption ?? ''} className="h-24 w-full object-cover transition-transform group-hover:scale-105" />
                            : <div className="flex h-24 items-center justify-center text-xs text-muted-foreground">{p.caption}</div>}
                        </a>
                      ))}
                    </div>
                  ) : <p className="text-sm text-muted-foreground">{tx('No photos yet.')}</p>}
                </div>
                <AttachmentPanel resType="sales_delivery" resId={d.id} title={tx('Delivery documents')} />
              </TabsContent>

              <TabsContent value="history">
                {history.isLoading ? <Skeleton className="h-32 w-full" /> : history.data?.length ? (
                  <ol className="relative ml-2 space-y-4 border-l pl-5">
                    {history.data.map((h) => (
                      <li key={h.id} className="relative">
                        <span className="absolute -left-[27px] top-1 flex h-3 w-3 items-center justify-center rounded-full border-2 border-background bg-primary" />
                        <p className="text-sm font-medium">{describe(h)}</p>
                        <p className="text-xs text-muted-foreground">{dateTime(h.createdAt)}{h.actorName ? ` · ${h.actorName}` : ''}</p>
                        {changes(h).length > 0 && (
                          <ul className="mt-1 space-y-0.5 text-xs text-muted-foreground">
                            {changes(h).map((c) => <li key={c}>{c}</li>)}
                          </ul>
                        )}
                      </li>
                    ))}
                  </ol>
                ) : <Empty icon={History} text={tx('No history yet.')} />}
              </TabsContent>
            </Tabs>
          </>
        )}
        {weighing && d && <CustomerWeightDialog delivery={d} onClose={() => setWeighing(false)} />}
      </DialogContent>
    </Dialog>
  );
}

function Empty({ icon: Icon, text }: { icon: LucideIcon; text: string }) {
  return (
    <div className="flex flex-col items-center justify-center gap-2 rounded-xl border border-dashed py-10 text-center text-sm text-muted-foreground">
      <Icon className="h-6 w-6" />{text}
    </div>
  );
}

const FIELD_LABEL: Record<string, string> = {
  plannedDate: tx('Scheduled date'), scheduledTime: tx('Time'), driverName: tx('Driver'), driverPhone: tx('Driver phone'),
  vehiclePlate: tx('Vehicle'), vehicleType: tx('Vehicle type'), transportCompany: tx('Transport company'), trackingNumber: tx('Tracking no.'),
  deliveryAddress: tx('Delivery address'), siteContactName: tx('Site contact'), siteContactPhone: tx('Contact phone'), route: tx('Route'),
  offloadingEquipment: tx('Offloading equipment'), expectedArrivalAt: tx('Expected arrival'), transportCost: tx('Transport cost'),
  tripExpenses: tx('Trip expenses'), deliveryMethod: tx('Method'), locationId: tx('Dispatch warehouse'),
  destinationLocationId: tx('Destination warehouse'), lines: tx('Quantities'), notes: tx('Instructions'),
};

const STATUS_TEXT: Record<string, string> = {
  dispatched: tx('Dispatched — stock issued'), in_transit: tx('In transit'), delivered: tx('Delivered — proof of delivery recorded'),
  cancelled: tx('Cancelled'), returned: tx('Load returned to stock'), failed: tx('Delivery attempt failed'), closed: tx('Closed'),
};

function describe(h: HistoryEntry): string {
  const nv = h.newValues ?? {};
  const status = typeof nv.status === 'string' ? nv.status : null;
  if (h.action === 'create') return tx('Delivery scheduled');
  if (h.action === 'approve') return tx('Approved for dispatch');
  if (status && STATUS_TEXT[status]) return STATUS_TEXT[status];
  if (h.action === 'issue') return STATUS_TEXT.dispatched;
  if (h.action === 'cancel') return STATUS_TEXT.cancelled;
  if (h.action === 'receive') return STATUS_TEXT.returned;
  if (nv.plannedDate && h.oldValues?.plannedDate) return tx('Rescheduled');
  return tx('Details updated');
}

function changes(h: HistoryEntry): string[] {
  const nv = h.newValues ?? {};
  const ov = h.oldValues ?? {};
  const out: string[] = [];
  for (const [k, v] of Object.entries(nv)) {
    if (k === 'status' || !(k in FIELD_LABEL)) {
      if (k === 'reason' && v) out.push(`${tx('Reason')}: ${String(v)}`);
      continue;
    }
    if (k === 'lines') { out.push(tx('Quantities re-planned')); continue; }
    const before = ov[k];
    out.push(`${FIELD_LABEL[k]}: ${before != null && before !== '' ? `${String(before)} → ` : ''}${v == null || v === '' ? '—' : String(v)}`);
  }
  if (h.action === 'create') {
    if (nv.plate) out.push(`${tx('Vehicle')}: ${String(nv.plate)}`);
  }
  return out;
}


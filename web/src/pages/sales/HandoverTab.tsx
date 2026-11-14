import { useEffect, useMemo, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { toast } from 'sonner';
import { CalendarClock, CheckCircle2, Truck, XCircle } from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { Button } from '@/components/ui/button';
import { Skeleton } from '@/components/ui/skeleton';
import { api } from '@/lib/api';
import { apiErrorMessage } from '@/lib/api-error';
import { date } from '@/lib/format';
import { openPdf, type ReturnView } from './delivery/delivery-docs';
import { tx } from '@/lib/i18n/tx';
import type { HandoverForm } from './handover-draft';
import { HandoverDeliveryView, handoverBody, type HandoverLineView, type HandoverStatus } from './delivery/HandoverDeliveryView';

export type { HandoverStatus } from './delivery/HandoverDeliveryView';

/** Delivery & fulfilment handover of a sales order / invoice (same model as the branch cash sale delivery tab). */
export interface Handover {
  sourceType: 'sales_order' | 'invoice'; sourceId: string; stage: 'plan' | 'ready' | 'closed';
  method: HandoverForm['method']; status: HandoverStatus; locationId: string | null; locationName: string | null;
  address: string | null; contactName: string | null; contactPhone: string | null; scheduledDate: string | null;
  driver: string | null; vehicle: string | null; receivedBy: string | null; notes: string | null;
  landmark: string | null; loadingSite: string | null; deliveryScope: 'full' | 'partial' | null;
  offloadingEquipment: 'yes' | 'no' | null;
  gpsCoordinates?: string | null; offloadingSite?: string | null; destinationLocationId?: string | null;
  destinationLocationName?: string | null; courierName?: string | null; deadline?: string | null;
  deliveredAt: string | null; deliveredByName: string | null; saved: boolean;
  deliveryNoteNumber?: string | null; sourceNumber?: string | null; returns?: ReturnView[];
  lines: Array<{
    id: string; productName: string | null; description: string; uomCode: string | null; quantity: number; issuedQty: number | null; handedOverQty: number;
    pickedQty?: number | null; invoicedQty?: number | null; returnedQty?: number | null;
  }>;
}

/** Fields still open once the handover is completed. */
const RECEIPT: Array<keyof HandoverForm> = ['receivedBy', 'notes'];

function toForm(h: Handover): HandoverForm {
  return {
    method: h.method, locationId: h.locationId ?? '', address: h.address ?? '', contactName: h.contactName ?? '',
    contactPhone: h.contactPhone ?? '', scheduledDate: h.scheduledDate ? h.scheduledDate.slice(0, 10) : '',
    driver: h.driver ?? '', vehicle: h.vehicle ?? '', receivedBy: h.receivedBy ?? '', notes: h.notes ?? '',
    landmark: h.landmark ?? '', loadingSite: h.loadingSite ?? '', deliveryScope: h.deliveryScope ?? '',
    offloadingEquipment: h.offloadingEquipment ?? '', gpsCoordinates: h.gpsCoordinates ?? '', offloadingSite: h.offloadingSite ?? '',
    destinationLocationId: h.destinationLocationId ?? '', courierName: h.courierName ?? '', deadline: h.deadline ? h.deadline.slice(0, 10) : '',
  };
}

function useHandover(path: string | null) {
  return useQuery({ queryKey: ['handover', path], enabled: !!path, queryFn: async () => (await api.get<Handover>(path!)).data });
}

/**
 * Delivery & fulfilment tab of a document with one handover (invoice): status flow and actions, overview, the delivery
 * plan (four methods, address / site, location & site details), transport, receipt and per-line fulfilment.
 * {@code path} is `/sales/invoices/:id/handover` (or `/sales/orders/:id/handover`).
 */
export function HandoverTab({ path, canEdit, readyHint, sourceLabel, issuedLabel = tx('Issued from stock'), currency, customerName }: {
  path: string; canEdit: boolean; readyHint: string; sourceLabel: string; issuedLabel?: string; currency?: string; customerName?: string | null;
}) {
  const qc = useQueryClient();
  const { data: h, isError, error, refetch } = useHandover(path);
  const [f, setF] = useState<HandoverForm | null>(null);
  useEffect(() => { if (h) setF(toForm(h)); }, [h]);
  const save = useMutation({
    mutationFn: async ({ body }: { body: Record<string, unknown>; ok: string }) => (await api.patch<Handover>(path, body)).data,
    onSuccess: (d, v) => { qc.setQueryData(['handover', path], d); qc.invalidateQueries({ queryKey: ['sales'] }); toast.success(v.ok); },
    onError: (e) => toast.error(apiErrorMessage(e, tx('Could not save the delivery details'))),
  });
  const lines = useMemo<HandoverLineView[]>(() => (h?.lines ?? []).map((l) => ({
    id: l.id, name: l.productName ?? l.description, description: l.description, uom: l.uomCode,
    ordered: Number(l.quantity), issued: l.issuedQty == null ? null : Number(l.issuedQty), handedOver: Number(l.handedOverQty ?? 0),
    picked: l.pickedQty == null ? null : Number(l.pickedQty), invoiced: l.invoicedQty == null ? null : Number(l.invoicedQty),
    returned: l.returnedQty == null ? null : Number(l.returnedQty),
  })), [h]);

  if (isError) {
    return (
      <div className="py-6 text-center text-sm">
        <p className="text-rose-600">{apiErrorMessage(error, tx('Could not load the delivery details'))}</p>
        <Button className="mt-2" size="sm" variant="outline" onClick={() => refetch()}>{tx('Retry')}</Button>
      </div>
    );
  }
  if (!h || !f) return <div className="space-y-4"><Skeleton className="h-10 w-full max-w-xl" /><Skeleton className="h-48 w-full" /><Skeleton className="h-40 w-full" /></div>;

  const status = h.status;
  const closed = h.stage === 'closed';
  const ready = h.stage === 'ready';
  const done = status === 'delivered';
  const editable = canEdit && !closed;
  const busy = save.isPending;
  const collect = f.method === 'pickup';
  const base = toForm(h);
  const dirty = (Object.keys(f) as Array<keyof HandoverForm>).some((k) => (f[k] ?? '') !== (base[k] ?? ''));
  const send = (extra?: Record<string, unknown>, ok = tx('Delivery details saved')) =>
    save.mutate({ body: { ...handoverBody(f, done ? RECEIPT : undefined), ...extra }, ok });

  const actions = editable && !done && (
    <>
      {status !== 'scheduled' && <Button size="sm" variant="outline" disabled={busy} onClick={() => send({ status: 'scheduled' }, collect ? tx('Pickup scheduled') : tx('Delivery scheduled'))}><CalendarClock className="mr-1 h-4 w-4" />{tx('Schedule')}</Button>}
      {!collect && status !== 'dispatched' && <Button size="sm" variant="outline" disabled={busy || !ready} title={ready ? undefined : readyHint} onClick={() => send({ status: 'dispatched' }, tx('Goods dispatched'))}><Truck className="mr-1 h-4 w-4" />{tx('Mark dispatched')}</Button>}
      <Button size="sm" disabled={busy || !ready} title={ready ? undefined : readyHint} onClick={() => send({ status: 'delivered' }, collect ? tx('Goods collected') : tx('Delivery completed'))}>
        <CheckCircle2 className="mr-1 h-4 w-4" />{collect ? tx('Mark collected') : tx('Mark delivered')}
      </Button>
      {status !== 'failed' && status !== 'pending' && <Button size="sm" variant="ghost" className="text-rose-600" disabled={busy} onClick={() => send({ status: 'failed' }, tx('Delivery marked failed'))}><XCircle className="mr-1 h-4 w-4" />{tx('Failed')}</Button>}
    </>
  );
  const notice = closed ? `${tx('This')} ${sourceLabel} ${tx('is cancelled; delivery details are read-only.')}`
    : !ready ? `${readyHint}. ${tx('You can plan the handover now; dispatch and delivery wait for it.')}` : null;

  return (
    <HandoverDeliveryView form={f} onChange={setF} status={status} lines={lines} stockLocationName={h.locationName} issuedLabel={issuedLabel}
      planLocked={!editable || done} receiptLocked={!editable} actions={actions || undefined} notice={notice}
      completed={done ? `${collect ? tx('Collected') : tx('Delivered')} ${h.deliveredAt ? date(h.deliveredAt) : ''}${h.deliveredByName ? ` · ${h.deliveredByName}` : ''}` : undefined}
      dirty={editable && dirty} saving={busy} onSave={() => send()} onDiscard={() => setF(base)}
      attachments={h.sourceType === 'invoice'
        ? { resType: 'invoice_delivery', resId: h.sourceId, writePermission: PERMISSIONS.invoice.update }
        : { resType: 'sales_order_delivery', resId: h.sourceId, writePermission: PERMISSIONS.sales.deliveryWrite }}
      docs={h.sourceType === 'invoice' ? {
        noteNumber: h.deliveryNoteNumber ?? null, sourceLabel: tx('Invoice'), sourceNumber: h.sourceNumber, customerName,
        deliveredAt: h.deliveredAt, returns: h.returns ?? [], currency,
        onNote: (mode) => void openPdf(`${path}/note`, `${(h.deliveryNoteNumber ?? 'delivery-note').replace(/\//g, '-')}.pdf`, mode),
      } : undefined} />
  );
}

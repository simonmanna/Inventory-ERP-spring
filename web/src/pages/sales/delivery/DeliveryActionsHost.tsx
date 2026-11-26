import { useState, type ReactNode } from 'react';
import { idempotentPost } from '@/lib/idempotent-request';
import { Navigation, ShieldCheck, Truck } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { api } from '@/lib/api';
import { tx } from '@/lib/i18n/tx';
import type { SalesOrder } from '@/features/sales/api';
import { useDelivery, useDeliveryAction, type DeliveryPlan, type SalesDelivery, type SummaryLine } from '@/features/sales/delivery-api';
import { ConfirmDeliveryDialog, ReasonDialog } from './DeliveryActionDialogs';
import { DeliveryDetailDialog, type DeliveryActionKey } from './DeliveryDetailDialog';
import { DeliveryEditorDialog } from './DeliveryEditorDialog';

export type HostAction = { key: DeliveryActionKey | 'add' | 'view'; delivery?: SalesDelivery; initialQty?: Record<string, number> };

/** Renders the dialog of the delivery action in progress (view, add, edit, reschedule, dispatch, confirm, …). */
export function DeliveryActionDialogs({ order, lines, plan, action, onAction, onClose }: {
  order: SalesOrder; lines: SummaryLine[]; plan?: DeliveryPlan; action: HostAction | null;
  onAction: (key: DeliveryActionKey | 'view', d: SalesDelivery) => void; onClose: () => void;
}) {
  if (!action) return null;
  const d = action.delivery;
  const orderOpen = ['confirmed', 'in_fulfilment'].includes(order.status) && !order.onHold;
  switch (action.key) {
    case 'view':
      return <DeliveryDetailDialog id={d!.id} orderOpen={orderOpen} currency={order.currencyCode} onClose={onClose} onAction={(k, x) => onAction(k, x)} />;
    case 'add':
      return <DeliveryEditorDialog orderId={order.id} mode={{ kind: 'create', initialQty: action.initialQty }} lines={lines} plan={plan}
        fallbackLocationId={order.locationId} onClose={onClose} />;
    case 'edit':
      return <DeliveryEditorDialog orderId={order.id} mode={{ kind: 'edit', delivery: d! }} lines={lines} plan={plan} fallbackLocationId={order.locationId} onClose={onClose} />;
    case 'reschedule':
      return <DeliveryEditorDialog orderId={order.id} mode={{ kind: 'reschedule', delivery: d! }} lines={lines} plan={plan} fallbackLocationId={order.locationId} onClose={onClose} />;
    case 'confirm':
      return <ConfirmDeliveryDialog delivery={d!} onClose={onClose} />;
    case 'cancel':
    case 'fail':
    case 'return':
      return <ReasonDialog delivery={d!} kind={action.key} onClose={onClose} />;
    case 'dispatch':
      return <DispatchDialog delivery={d!} onClose={onClose} />;
    case 'depart':
      return <SimpleConfirm delivery={d!} onClose={onClose} icon={Navigation} title={`${tx('Mark')} ${d!.code} ${tx('in transit')}?`} button={tx('Mark in transit')} ok={tx('Delivery in transit')}
        body={tx('Records the departure time: the goods have left the gate.')} run={() => api.post(`/sales/deliveries/${d!.id}/depart`)} />;
    case 'approve':
      return <SimpleConfirm delivery={d!} onClose={onClose} icon={ShieldCheck} title={`${tx('Approve')} ${d!.code}?`} button={tx('Approve')} ok={tx('Delivery approved')}
        body={tx('Approves the delivery for dispatch. You cannot approve a delivery you created.')} run={() => api.post(`/sales/deliveries/${d!.id}/approve`)} />;
    default:
      return null;
  }
}

/**
 * Dispatch: issues the planned quantities. Serial-tracked lines name one serial number per unit (R4d, DEL-2), one per
 * line of the text box; they are recorded on the delivery and issued from stock with the goods.
 */
export function DispatchDialog({ delivery, onClose }: { delivery: SalesDelivery; onClose: () => void }) {
  const { data } = useDelivery(delivery.id);
  const serialLines = (data?.lines ?? []).filter((l) => l.serialTracking);
  const [serials, setSerials] = useState<Record<string, string>>({});
  const parse = (v: string | undefined) => (v ?? '').split(/[\n,;]+/).map((x) => x.trim()).filter(Boolean);
  const textOf = (id: string, saved?: string[]) => serials[id] ?? (saved ?? []).join('\n');
  const m = useDeliveryAction(() => idempotentPost(`/sales/deliveries/${delivery.id}/issue`, serialLines.length
    ? { lines: serialLines.map((l) => ({ loadLineId: l.id, serialNumbers: parse(textOf(l.id, l.serialNumbers)) })) }
    : undefined), tx('Goods dispatched — stock issued'));
  return (
    <Dialog open onOpenChange={(v) => !v && onClose()}>
      <DialogContent className="max-w-md" key={delivery.id}>
        <DialogHeader>
          <DialogTitle>{`${tx('Dispatch')} ${delivery.code}?`}</DialogTitle>
          <DialogDescription>{tx('This issues the planned quantities from')} <strong>{delivery.locationName ?? tx('the dispatch warehouse')}</strong>, {tx('posts the cost of goods and numbers the delivery note. Confirming the delivery later does not issue stock again.')}</DialogDescription>
        </DialogHeader>
        {serialLines.map((l) => {
          const qty = l.loadedQty ?? l.plannedQty;
          const count = parse(textOf(l.id, l.serialNumbers)).length;
          return (
            <div key={l.id} className="space-y-1">
              <label htmlFor={`sn-${l.id}`} className="text-sm font-medium">{l.productName} · {tx('serial numbers')} ({count}/{qty})</label>
              <textarea id={`sn-${l.id}`} rows={3} className="w-full rounded-md border bg-background px-2 py-1 font-mono text-sm"
                value={textOf(l.id, l.serialNumbers)} onChange={(e) => setSerials((s) => ({ ...s, [l.id]: e.target.value }))}
                placeholder={tx('One serial number per line')} />
            </div>
          );
        })}
        <DialogFooter>
          <Button variant="outline" onClick={onClose}>{tx('Back')}</Button>
          <Button disabled={m.isPending || !data} onClick={() => m.mutate(undefined, { onSuccess: onClose })}><Truck className="mr-1.5 h-4 w-4" />{tx('Dispatch')}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

function SimpleConfirm({ delivery, title, body, button, ok, icon: Icon, run, onClose }: {
  delivery: SalesDelivery; title: string; body: ReactNode; button: string; ok: string; icon: typeof Truck; run: () => Promise<unknown>; onClose: () => void;
}) {
  const m = useDeliveryAction(run, ok);
  return (
    <Dialog open onOpenChange={(v) => !v && onClose()}>
      <DialogContent className="max-w-md" key={delivery.id}>
        <DialogHeader><DialogTitle>{title}</DialogTitle><DialogDescription>{body}</DialogDescription></DialogHeader>
        <DialogFooter>
          <Button variant="outline" onClick={onClose}>{tx('Back')}</Button>
          <Button disabled={m.isPending} onClick={() => m.mutate(undefined, { onSuccess: onClose })}><Icon className="mr-1.5 h-4 w-4" />{button}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

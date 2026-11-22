import { useEffect, useMemo, useState } from 'react';
import { CalendarClock, CheckCircle2, Truck, XCircle } from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { Button } from '@/components/ui/button';
import { api } from '@/lib/api';
import { date } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';
import { useAuthStore } from '@/stores/auth.store';
import type { PlanMethod } from '@/features/sales/delivery-api';
import type { CashSale, DeliveryStatus } from '@/features/sales/cash-sales-api';
import type { HandoverForm } from '@/pages/sales/handover-draft';
import { HandoverDeliveryView, TRAVEL_ONLY, type HandoverLineView } from '@/pages/sales/delivery/HandoverDeliveryView';
import { openPdf } from '@/pages/sales/delivery/delivery-docs';

/** A branch cash sale stores pickup / delivery only, on the sale itself, from the sale's stock location. */
export const CASH_SALE_METHODS: PlanMethod[] = ['pickup', 'delivery'];
export const CASH_SALE_HIDE: Array<keyof HandoverForm> = ['locationId', 'gpsCoordinates', 'offloadingSite', 'destinationLocationId', 'courierName'];

/** Handover form field → cash sale column. */
const CASH_KEYS: Partial<Record<keyof HandoverForm, string>> = {
  method: 'deliveryMethod', address: 'deliveryAddress', contactName: 'deliveryContactName', contactPhone: 'deliveryContactPhone',
  scheduledDate: 'deliveryScheduledDate', driver: 'deliveryDriver', vehicle: 'deliveryVehicle', receivedBy: 'deliveryReceivedBy',
  notes: 'deliveryNotes', landmark: 'deliveryLandmark', loadingSite: 'deliveryLoadingSite', deliveryScope: 'deliveryScope',
  offloadingEquipment: 'deliveryOffloadingEquipment', deadline: 'deliveryDeadline',
};
const RECEIPT: Array<keyof HandoverForm> = ['receivedBy', 'notes'];

function toForm(s: CashSale): HandoverForm {
  return {
    method: s.deliveryMethod ?? 'pickup', locationId: '',
    address: s.deliveryAddress ?? s.customerAddress ?? '',
    contactName: s.deliveryContactName ?? s.customerName ?? '',
    contactPhone: s.deliveryContactPhone ?? s.customerPhone ?? '',
    scheduledDate: s.deliveryScheduledDate ? String(s.deliveryScheduledDate).slice(0, 10) : '',
    driver: s.deliveryDriver ?? '', vehicle: s.deliveryVehicle ?? '', receivedBy: s.deliveryReceivedBy ?? '', notes: s.deliveryNotes ?? '',
    landmark: s.deliveryLandmark ?? '', loadingSite: s.deliveryLoadingSite ?? '', deliveryScope: s.deliveryScope ?? '',
    offloadingEquipment: s.deliveryOffloadingEquipment ?? '', deadline: s.deliveryDeadline ? String(s.deliveryDeadline).slice(0, 10) : '',
  };
}

/** Cash sale delivery body from the form: every mapped field (empty = null), travel-only fields cleared on a pickup. */
function cashBody(f: HandoverForm, only?: Array<keyof HandoverForm>) {
  const b: Record<string, unknown> = {};
  (Object.keys(CASH_KEYS) as Array<keyof HandoverForm>).forEach((k) => {
    if (only && !only.includes(k)) return;
    b[CASH_KEYS[k]!] = (k === 'method' ? f.method : f[k]) || null;
    if (!only && f.method === 'pickup' && TRAVEL_ONLY.includes(k)) b[CASH_KEYS[k]!] = null;
  });
  return b;
}

/**
 * Delivery and fulfilment of a branch cash sale: what was issued from the showroom stock location, how the goods
 * reach the customer (pickup or delivery) and where that handover stands. Logistics only — stock posts at confirm.
 */
export function CashSaleDelivery({ sale, busy, run }: { sale: CashSale; busy: boolean; run: (fn: () => Promise<unknown>, ok: string) => Promise<void> }) {
  const canEdit = useAuthStore((st) => st.hasPermission(PERMISSIONS.sales.cashSaleCreate));
  const base = useMemo(() => toForm(sale), [sale]);
  const [f, setF] = useState<HandoverForm>(base);
  useEffect(() => { setF(base); }, [base]);
  const status: DeliveryStatus = sale.deliveryStatus ?? 'pending';
  const closed = sale.executionStatus === 'voided' || sale.executionStatus === 'cancelled';
  const confirmed = sale.executionStatus === 'confirmed';
  const done = status === 'delivered';
  const editable = canEdit && !closed;
  const collect = f.method === 'pickup';
  const dirty = (Object.keys(CASH_KEYS) as Array<keyof HandoverForm>).some((k) => (f[k] ?? '') !== (base[k] ?? ''));
  const save = (extra?: Record<string, unknown>, ok = tx('Delivery details saved')) =>
    run(() => api.patch(`/sales/cash-sales/${sale.id}/delivery`, { ...cashBody(f, done ? RECEIPT : undefined), ...extra }), ok);

  const lines = useMemo<HandoverLineView[]>(() => (sale.lines ?? []).map((l) => ({
    id: l.id, name: l.productName ?? l.description ?? '', description: l.description, uom: l.uomCode ?? null,
    ordered: Number(l.quantity), issued: Number(l.deliveredQty ?? 0), handedOver: done ? Number(l.quantity) : 0,
    picked: status === 'dispatched' || done ? Number(l.quantity) : 0, invoiced: Number(l.invoicedQty ?? 0), returned: Number(l.returnedQty ?? 0),
  })), [sale.lines, done, status]);

  // what each step still needs, said on the button instead of a refused save
  const noAddress = !collect && !f.address.trim() ? tx('Enter the delivery address first') : undefined;
  const notConfirmed = confirmed ? undefined : tx('Confirm the sale first');
  const scheduleBlock = noAddress ?? (!f.scheduledDate ? tx('Enter the scheduled date first') : undefined);
  const moveBlock = notConfirmed ?? noAddress;
  const actions = editable && !done && (
    <>
      {!collect && status !== 'scheduled' && <Button size="sm" variant="outline" disabled={busy || !!scheduleBlock} title={scheduleBlock} onClick={() => save({ deliveryStatus: 'scheduled' }, tx('Delivery scheduled'))}><CalendarClock className="mr-1 h-4 w-4" />{tx('Schedule')}</Button>}
      {!collect && status !== 'dispatched' && <Button size="sm" variant="outline" disabled={busy || !!moveBlock} title={moveBlock} onClick={() => save({ deliveryStatus: 'dispatched' }, tx('Goods dispatched'))}><Truck className="mr-1 h-4 w-4" />{tx('Mark dispatched')}</Button>}
      <Button size="sm" disabled={busy || !!moveBlock} title={moveBlock} onClick={() => save({ deliveryStatus: 'delivered' }, collect ? tx('Goods collected') : tx('Delivery completed'))}>
        <CheckCircle2 className="mr-1 h-4 w-4" />{collect ? tx('Mark collected') : tx('Mark delivered')}
      </Button>
      {!collect && status !== 'failed' && status !== 'pending' && <Button size="sm" variant="ghost" className="text-rose-600" disabled={busy} onClick={() => save({ deliveryStatus: 'failed' }, tx('Delivery marked failed'))}><XCircle className="mr-1 h-4 w-4" />{tx('Failed')}</Button>}
    </>
  );
  const hint = editable && !done ? (moveBlock && confirmed ? moveBlock : !collect && status !== 'scheduled' && scheduleBlock && !confirmed ? `${scheduleBlock} ${tx('to schedule the delivery.')}` : null) : null;
  const notice = closed ? tx('This sale is voided or discarded; delivery details are read-only.')
    : !confirmed ? tx('Goods are issued from stock when the sale is confirmed. You can plan the delivery now; dispatch and delivery need the sale confirmed.')
      : sale.confirmedAt ? `${tx('Issued from')} ${sale.locationName ?? tx('the stock location')} ${tx('on')} ${date(sale.confirmedAt)}.` : null;

  return (
    <div data-tour="sales.cash.delivery">
    <HandoverDeliveryView form={f} onChange={setF} status={status} lines={lines} stockLocationName={sale.locationName}
      methods={CASH_SALE_METHODS} hide={CASH_SALE_HIDE} planLocked={!editable || done} receiptLocked={!editable}
      actions={actions || undefined} notice={hint ? <>{notice}{notice ? ' ' : ''}<span className="font-medium">{hint}.</span></> : notice}
      tip={tx('Stock leaves the showroom when the sale is confirmed; here you track how the goods reach the customer.')}
      completed={done ? `${collect ? tx('Collected') : tx('Delivered')} ${sale.deliveredAt ? date(sale.deliveredAt) : ''}${sale.deliveredByName ? ` · ${sale.deliveredByName}` : ''}` : undefined}
      dirty={editable && dirty} saving={busy} onSave={() => void save()} onDiscard={() => setF(base)}
      attachments={{ resType: 'cash_sale_delivery', resId: sale.id, writePermission: PERMISSIONS.sales.cashSaleCreate }}
      docs={{
        noteNumber: sale.deliveryNoteNumber ?? `DN/${sale.number}`, sourceLabel: tx('Cash sale'), sourceNumber: sale.number,
        customerName: sale.customerName ?? sale.partnerName, deliveredAt: sale.deliveredAt, returns: sale.returns ?? [], currency: sale.currencyCode,
        onNote: (mode) => void openPdf(`/sales/cash-sales/${sale.id}/delivery-note`, `DN-${sale.number}.pdf`, mode),
      }} />
    </div>
  );
}

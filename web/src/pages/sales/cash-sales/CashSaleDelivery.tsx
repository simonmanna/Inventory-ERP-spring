import { useEffect, useState } from 'react';
import { CheckCircle2, MapPin, PackageCheck, Save, Store, Truck, XCircle } from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Textarea } from '@/components/ui/textarea';
import { api } from '@/lib/api';
import { date } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';
import { cn } from '@/lib/utils';
import { useAuthStore } from '@/stores/auth.store';
import { Field } from '@/pages/crm/crm-ui';
import { DELIVERY_STATUS_LABEL, type CashSale, type DeliveryStatus } from '@/features/sales/cash-sales-api';
import { OptionField, SCOPE_OPTIONS, YES_NO_OPTIONS } from '@/pages/sales/HandoverDetails';

type Form = {
  deliveryMethod: 'pickup' | 'delivery'; deliveryAddress: string; deliveryContactName: string; deliveryContactPhone: string;
  deliveryScheduledDate: string; deliveryDriver: string; deliveryVehicle: string; deliveryReceivedBy: string; deliveryNotes: string;
  deliveryLandmark: string; deliveryLoadingSite: string; deliveryScope: string; deliveryOffloadingEquipment: string;
};

const STEPS: DeliveryStatus[] = ['pending', 'scheduled', 'dispatched', 'delivered'];
const qty = (v: number | string | null | undefined) => Number(v ?? 0).toFixed(2);

function toForm(s: CashSale): Form {
  return {
    deliveryMethod: s.deliveryMethod ?? 'pickup',
    deliveryAddress: s.deliveryAddress ?? s.customerAddress ?? '',
    deliveryContactName: s.deliveryContactName ?? s.customerName ?? '',
    deliveryContactPhone: s.deliveryContactPhone ?? s.customerPhone ?? '',
    deliveryScheduledDate: s.deliveryScheduledDate ? String(s.deliveryScheduledDate).slice(0, 10) : '',
    deliveryDriver: s.deliveryDriver ?? '', deliveryVehicle: s.deliveryVehicle ?? '',
    deliveryReceivedBy: s.deliveryReceivedBy ?? '', deliveryNotes: s.deliveryNotes ?? '',
    deliveryLandmark: s.deliveryLandmark ?? '', deliveryLoadingSite: s.deliveryLoadingSite ?? '',
    deliveryScope: s.deliveryScope ?? '', deliveryOffloadingEquipment: s.deliveryOffloadingEquipment ?? '',
  };
}

/**
 * Delivery and fulfilment of a branch cash sale: what was issued from the showroom stock location, how the goods
 * reach the customer (pickup or delivery) and where that handover stands. Logistics only — stock posts at confirm.
 */
export function CashSaleDelivery({ sale, busy, run }: { sale: CashSale; busy: boolean; run: (fn: () => Promise<unknown>, ok: string) => Promise<void> }) {
  const canEdit = useAuthStore((st) => st.hasPermission(PERMISSIONS.sales.cashSaleCreate));
  const [f, setF] = useState<Form>(() => toForm(sale));
  useEffect(() => { setF(toForm(sale)); }, [sale]);
  const status: DeliveryStatus = sale.deliveryStatus ?? 'pending';
  const closed = sale.executionStatus === 'voided' || sale.executionStatus === 'cancelled';
  const confirmed = sale.executionStatus === 'confirmed';
  const done = status === 'delivered';
  const editable = canEdit && !closed;
  const lockedField = (k: keyof Form) => !editable || (done && k !== 'deliveryReceivedBy' && k !== 'deliveryNotes');
  const isDelivery = f.deliveryMethod === 'delivery';

  const payload = (extra?: Partial<Record<string, unknown>>) => {
    const b: Record<string, unknown> = {};
    (Object.keys(f) as Array<keyof Form>).forEach((k) => {
      if (done && k !== 'deliveryReceivedBy' && k !== 'deliveryNotes') return;
      b[k] = f[k] || null;
    });
    if (!isDelivery && !done) { b.deliveryDriver = null; b.deliveryVehicle = null; b.deliveryLandmark = null; b.deliveryOffloadingEquipment = null; }
    return { ...b, ...extra };
  };
  const save = (extra?: Record<string, unknown>, ok = tx('Delivery details saved')) =>
    run(() => api.patch(`/sales/cash-sales/${sale.id}/delivery`, payload(extra)), ok);
  const set = (k: keyof Form, v: string) => setF({ ...f, [k]: v });
  const text = (k: keyof Form, label: string, type = 'text') => (
    <Field label={label}>
      {lockedField(k) ? <p className="py-2 text-sm">{(type === 'date' && f[k] ? date(f[k]) : f[k]) || '—'}</p>
        : <Input type={type} value={f[k]} onChange={(e) => set(k, e.target.value)} />}
    </Field>
  );

  const lines = sale.lines ?? [];
  const ordered = lines.reduce((a, l) => a + Number(l.quantity), 0);
  const issued = lines.reduce((a, l) => a + Number(l.deliveredQty ?? 0), 0);
  const stepIndex = STEPS.indexOf(status);

  return (
    <div className="space-y-5">
      {/* status */}
      <section className="flex flex-wrap items-center justify-between gap-3">
        <ol className="flex flex-wrap items-center gap-1 text-xs">
          {STEPS.map((st, i) => (
            <li key={st} className="flex items-center gap-1">
              <span className={cn('rounded-full border px-2.5 py-1 font-medium',
                status === 'failed' ? 'text-muted-foreground'
                  : i < stepIndex ? 'border-emerald-200 bg-emerald-50 text-emerald-800 dark:bg-transparent'
                    : i === stepIndex ? 'border-primary bg-primary text-primary-foreground' : 'text-muted-foreground')}>
                {DELIVERY_STATUS_LABEL[st]}
              </span>
              {i < STEPS.length - 1 && <span className="text-muted-foreground">›</span>}
            </li>
          ))}
          {status === 'failed' && <li className="ml-2 rounded-full border border-rose-200 bg-rose-50 px-2.5 py-1 font-medium text-rose-800 dark:bg-transparent">{DELIVERY_STATUS_LABEL.failed}</li>}
        </ol>
        {editable && !done && (
          <div className="flex flex-wrap gap-2">
            {isDelivery && status !== 'scheduled' && <Button size="sm" variant="outline" disabled={busy} onClick={() => save({ deliveryStatus: 'scheduled' }, tx('Delivery scheduled'))}>{tx('Schedule')}</Button>}
            {isDelivery && status !== 'dispatched' && <Button size="sm" variant="outline" disabled={busy || !confirmed} title={confirmed ? undefined : tx('Confirm the sale first')} onClick={() => save({ deliveryStatus: 'dispatched' }, tx('Goods dispatched'))}><Truck className="mr-1 h-4 w-4" />{tx('Mark dispatched')}</Button>}
            <Button size="sm" disabled={busy || !confirmed} title={confirmed ? undefined : tx('Confirm the sale first')} onClick={() => save({ deliveryStatus: 'delivered' }, isDelivery ? tx('Delivery completed') : tx('Goods collected'))}>
              <CheckCircle2 className="mr-1 h-4 w-4" />{isDelivery ? tx('Mark delivered') : tx('Mark collected')}
            </Button>
            {isDelivery && status !== 'failed' && status !== 'pending' && <Button size="sm" variant="ghost" className="text-rose-600" disabled={busy} onClick={() => save({ deliveryStatus: 'failed' }, tx('Delivery marked failed'))}><XCircle className="mr-1 h-4 w-4" />{tx('Failed')}</Button>}
          </div>
        )}
      </section>
      {!confirmed && !closed && <p className="text-xs text-muted-foreground">{tx('Goods are issued from stock when the sale is confirmed. You can plan the delivery now; dispatch and delivery need the sale confirmed.')}</p>}
      {closed && <p className="text-xs text-muted-foreground">{tx('This sale is voided or discarded; delivery details are read-only.')}</p>}
      {done && <p className="text-xs text-emerald-700">{tx('Completed')} {sale.deliveredAt ? date(sale.deliveredAt) : ''}{sale.deliveredByName ? ` · ${sale.deliveredByName}` : ''}</p>}

      {/* fulfilment */}
      <section>
        <h3 className="mb-2 flex items-center gap-1.5 text-sm font-semibold"><PackageCheck className="h-4 w-4" />{tx('Fulfilment')}</h3>
        <div className="mb-2 flex flex-wrap gap-x-6 gap-y-1 text-xs text-muted-foreground">
          <span>{tx('Stock location')}: <span className="font-medium text-foreground">{sale.locationName ?? '—'}</span></span>
          <span>{tx('Issued')} {qty(issued)} / {qty(ordered)}</span>
          {sale.confirmedAt && <span>{tx('Issued on')} {date(sale.confirmedAt)}</span>}
        </div>
        <div className="overflow-x-auto">
          <table className="w-full min-w-[560px] text-sm">
            <thead className="border-b text-left text-xs font-semibold uppercase text-muted-foreground"><tr>
              <th className="py-1.5">{tx('Product')}</th><th className="py-1.5 text-right">{tx('Ordered')}</th>
              <th className="py-1.5 text-right">{tx('Issued from stock')}</th><th className="py-1.5 text-right">{tx('To issue')}</th>
              <th className="py-1.5 text-right">{tx('Handed over')}</th>
            </tr></thead>
            <tbody>
              {lines.map((l) => {
                const rem = Number(l.quantity) - Number(l.deliveredQty ?? 0);
                return (
                  <tr key={l.id} className="border-b">
                    <td className="py-1.5"><span className="font-medium">{l.productName}</span>{l.description && l.description !== l.productName && <span className="ml-1 text-xs text-muted-foreground">{l.description}</span>}</td>
                    <td className="py-1.5 text-right tabular-nums">{qty(l.quantity)} {l.uomCode ?? ''}</td>
                    <td className="py-1.5 text-right tabular-nums">{qty(l.deliveredQty)}</td>
                    <td className={cn('py-1.5 text-right tabular-nums', rem > 0 && 'text-amber-600')}>{qty(rem)}</td>
                    <td className="py-1.5 text-right tabular-nums">{done ? qty(l.quantity) : qty(0)}</td>
                  </tr>
                );
              })}
              {lines.length === 0 && <tr><td colSpan={5} className="py-3 text-center text-muted-foreground">{tx('No lines yet.')}</td></tr>}
            </tbody>
          </table>
        </div>
      </section>

      {/* delivery details */}
      <section>
        <h3 className="mb-2 flex items-center gap-1.5 text-sm font-semibold"><MapPin className="h-4 w-4" />{tx('Delivery details')}</h3>
        <div className="mb-3 flex gap-2">
          {([['pickup', tx('Customer pickup'), Store], ['delivery', tx('Deliver to customer'), Truck]] as const).map(([v, label, Icon]) => (
            <button key={v} type="button" disabled={lockedField('deliveryMethod')} onClick={() => set('deliveryMethod', v)}
              className={cn('flex items-center gap-1.5 rounded-md border px-3 py-1.5 text-sm disabled:cursor-default',
                f.deliveryMethod === v ? 'border-primary bg-primary/10 font-medium text-primary' : 'text-muted-foreground hover:bg-muted', lockedField('deliveryMethod') && f.deliveryMethod !== v && 'opacity-50')}>
              <Icon className="h-4 w-4" />{label}
            </button>
          ))}
        </div>
        <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
          {isDelivery && <div className="sm:col-span-2 lg:col-span-3">{text('deliveryAddress', tx('Delivery address'))}</div>}
          {isDelivery && text('deliveryLandmark', tx('GPS / Landmark'))}
          {text('deliveryLoadingSite', tx('Loading site'))}
          <OptionField label={tx('Delivery scope')} value={f.deliveryScope} options={SCOPE_OPTIONS} locked={lockedField('deliveryScope')} onChange={(v) => set('deliveryScope', v)} />
          {isDelivery && <OptionField label={tx('Offloading equipment')} value={f.deliveryOffloadingEquipment} options={YES_NO_OPTIONS} locked={lockedField('deliveryOffloadingEquipment')} onChange={(v) => set('deliveryOffloadingEquipment', v)} />}
          {text('deliveryContactName', isDelivery ? tx('Contact person') : tx('Collected by (name)'))}
          {text('deliveryContactPhone', tx('Contact phone'))}
          {text('deliveryScheduledDate', isDelivery ? tx('Scheduled date') : tx('Pickup date'), 'date')}
          {isDelivery && text('deliveryDriver', tx('Driver'))}
          {isDelivery && text('deliveryVehicle', tx('Vehicle'))}
          {text('deliveryReceivedBy', tx('Received by'))}
          <div className="sm:col-span-2 lg:col-span-3">
            <Field label={tx('Delivery notes')}>
              {lockedField('deliveryNotes') ? <p className="py-2 text-sm">{f.deliveryNotes || '—'}</p>
                : <Textarea rows={2} value={f.deliveryNotes} onChange={(e) => set('deliveryNotes', e.target.value)} />}
            </Field>
          </div>
        </div>
        {editable && <Button className="mt-3" size="sm" variant="secondary" disabled={busy} onClick={() => save()}><Save className="mr-1 h-4 w-4" />{tx('Save delivery details')}</Button>}
      </section>
    </div>
  );
}

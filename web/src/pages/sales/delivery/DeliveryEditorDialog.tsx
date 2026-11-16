import { useMemo, useState, type ReactNode } from 'react';
import { useQuery } from '@tanstack/react-query';
import { AlertTriangle, CalendarClock, MapPin, PackageOpen, Truck } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Textarea } from '@/components/ui/textarea';
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { api } from '@/lib/api';
import { locationsApi } from '@/lib/api/locations';
import { tx } from '@/lib/i18n/tx';
import { cn } from '@/lib/utils';
import {
  useDeliveryAction, type DeliveryMethod, type DeliveryPlan, type SalesDelivery, type SummaryLine,
} from '@/features/sales/delivery-api';
import { METHOD_META, PLAN_TO_METHOD, fromLocalInput, n, qty, toLocalInput } from './delivery-ui';

export function useStockLocations() {
  return useQuery({ queryKey: ['inventory', 'locations', 'handover'], queryFn: () => locationsApi.list({ pageSize: 200 }), staleTime: 60_000 });
}

type Form = {
  deliveryMethod: DeliveryMethod; plannedDate: string; scheduledTime: string; locationId: string; destinationLocationId: string;
  deliveryAddress: string; siteContactName: string; siteContactPhone: string; driverName: string; driverPhone: string;
  vehiclePlate: string; vehicleType: string; transportCompany: string; trackingNumber: string; route: string;
  offloadingEquipment: string; expectedArrivalAt: string; transportCost: string; tripExpenses: string; notes: string;
};
type Key = keyof Form;

const ON_ROAD: Key[] = ['driverPhone', 'trackingNumber', 'route', 'expectedArrivalAt', 'transportCost', 'tripExpenses', 'siteContactName', 'siteContactPhone'];
const COSTS: Key[] = ['transportCost', 'tripExpenses'];
const today = () => new Date().toISOString().slice(0, 10);
const s = (v: unknown) => (v == null ? '' : String(v));

function fromDelivery(d: SalesDelivery, keepSchedule: boolean): Form {
  return {
    deliveryMethod: d.deliveryMethod ?? 'site_delivery', plannedDate: keepSchedule ? s(d.plannedDate).slice(0, 10) : today(),
    scheduledTime: keepSchedule ? s(d.scheduledTime) : '', locationId: s(d.locationId), destinationLocationId: s(d.destinationLocationId),
    deliveryAddress: s(d.deliveryAddress), siteContactName: s(d.siteContactName), siteContactPhone: s(d.siteContactPhone),
    driverName: s(d.driverName), driverPhone: s(d.driverPhone), vehiclePlate: s(d.vehiclePlate), vehicleType: s(d.vehicleType),
    transportCompany: s(d.transportCompany), trackingNumber: keepSchedule ? s(d.trackingNumber) : '', route: s(d.route),
    offloadingEquipment: s(d.offloadingEquipment), expectedArrivalAt: keepSchedule ? toLocalInput(d.expectedArrivalAt) : '',
    transportCost: keepSchedule ? s(d.transportCost) : '', tripExpenses: keepSchedule ? s(d.tripExpenses) : '', notes: s(d.notes),
  };
}

function fromPlan(p: DeliveryPlan | undefined, fallbackLocation: string | null): Form {
  return {
    deliveryMethod: p ? PLAN_TO_METHOD[p.method] : 'site_delivery', plannedDate: p?.scheduledDate?.slice(0, 10) || today(), scheduledTime: '',
    locationId: p?.locationId ?? fallbackLocation ?? '', destinationLocationId: p?.destinationLocationId ?? '', deliveryAddress: p?.address ?? '',
    siteContactName: p?.contactName ?? '', siteContactPhone: p?.contactPhone ?? '', driverName: '', driverPhone: '', vehiclePlate: '',
    vehicleType: '', transportCompany: p?.method === 'courier' ? p?.courierName ?? '' : '', trackingNumber: '', route: '',
    offloadingEquipment: p?.offloadingEquipment === 'yes' ? tx('Required on site') : '', expectedArrivalAt: '', transportCost: '',
    tripExpenses: '', notes: '',
  };
}

export type EditorMode =
  | { kind: 'create'; initialQty?: Record<string, number> }
  | { kind: 'edit'; delivery: SalesDelivery }
  | { kind: 'reschedule'; delivery: SalesDelivery };

/**
 * Plan, edit or reschedule one delivery: method, schedule, dispatch warehouse, destination, transport and the quantity
 * of every order line. A line can never take more than remains to be scheduled (the server enforces it too).
 */
export function DeliveryEditorDialog({ orderId, mode, lines, plan, fallbackLocationId, onClose, onSaved }: {
  orderId: string; mode: EditorMode; lines: SummaryLine[]; plan?: DeliveryPlan; fallbackLocationId: string | null;
  onClose: () => void; onSaved?: (d: SalesDelivery) => void;
}) {
  const locations = useStockLocations();
  const existing = mode.kind === 'create' ? null : mode.delivery;
  const editing = mode.kind === 'edit';
  const [f, setF] = useState<Form>(() => (existing ? fromDelivery(existing, editing) : fromPlan(plan, fallbackLocationId)));
  const initial = useMemo(() => f, []); // eslint-disable-line react-hooks/exhaustive-deps

  /** Planned on this delivery per order line (edit: still counts as available to it). */
  const mine = useMemo(() => {
    const m: Record<string, number> = {};
    if (existing && editing) for (const l of existing.lines ?? []) if (l.orderLineId) m[l.orderLineId] = n(m[l.orderLineId]) + n(l.plannedQty);
    return m;
  }, [existing, editing]);
  const available = (l: SummaryLine) => n(l.toSchedule) + n(mine[l.lineId]);
  const [q, setQ] = useState<Record<string, string>>(() => {
    const init: Record<string, string> = {};
    for (const l of lines) {
      if (mode.kind === 'edit') init[l.lineId] = mine[l.lineId] ? String(mine[l.lineId]) : '';
      else if (mode.kind === 'reschedule') {
        const was = (mode.delivery.lines ?? []).filter((x) => x.orderLineId === l.lineId).reduce((a, x) => a + n(x.plannedQty), 0);
        const v = Math.min(was, n(l.toSchedule));
        init[l.lineId] = v > 0 ? String(v) : '';
      } else {
        const v = mode.initialQty?.[l.lineId];
        init[l.lineId] = v ? String(v) : '';
      }
    }
    return init;
  });

  const stage = existing?.stage;
  const rawStatus = existing?.status;
  const allowed = (k: Key): boolean => {
    if (!editing) return true;
    if (stage === 'scheduled') return true;
    if (stage === 'dispatched' || stage === 'in_transit') return ON_ROAD.includes(k);
    if (stage === 'delivered' || stage === 'partially_delivered') return COSTS.includes(k);
    return false;
  };
  const qtyEditable = !editing || rawStatus === 'planned';
  const set = (k: Key, v: string) => setF((x) => ({ ...x, [k]: v }));

  const save = useDeliveryAction(async () => {
    if (editing) {
      const body: Record<string, unknown> = {};
      (Object.keys(f) as Key[]).forEach((k) => {
        if (!allowed(k) || f[k] === initial[k]) return;
        body[k] = k === 'expectedArrivalAt' ? fromLocalInput(f[k]) : (f[k] || null);
      });
      if (qtyEditable && lines.some((l) => n(q[l.lineId]) !== n(mine[l.lineId]))) {
        body.lines = lines.filter((l) => n(q[l.lineId]) > 0 || mine[l.lineId]).map((l) => ({ lineId: l.lineId, quantity: n(q[l.lineId]) }));
      }
      return (await api.patch<SalesDelivery>(`/sales/deliveries/${existing!.id}`, body)).data;
    }
    const body: Record<string, unknown> = {};
    (Object.keys(f) as Key[]).forEach((k) => {
      if (!f[k]) return;
      body[k] = k === 'expectedArrivalAt' ? fromLocalInput(f[k]) : k === 'transportCost' || k === 'tripExpenses' ? Number(f[k]) : f[k];
    });
    body.lines = lines.filter((l) => n(q[l.lineId]) > 0).map((l) => ({ lineId: l.lineId, quantity: n(q[l.lineId]) }));
    if (mode.kind === 'reschedule') body.rescheduledFromId = mode.delivery.id;
    return (await api.post<SalesDelivery>(`/sales/orders/${orderId}/deliveries`, body)).data;
  }, editing ? tx('Delivery updated') : mode.kind === 'reschedule' ? tx('Delivery rescheduled') : tx('Delivery scheduled'));

  const method = f.deliveryMethod;
  const plateNeeded = method === 'site_delivery' || method === 'warehouse_transfer';
  const over = lines.filter((l) => n(q[l.lineId]) > available(l) + 1e-9);
  const negative = lines.some((l) => n(q[l.lineId]) < 0);
  const total = lines.reduce((a, l) => a + Math.max(0, n(q[l.lineId])), 0);
  const problems: string[] = [];
  if (qtyEditable && total <= 0) problems.push(tx('Enter a quantity for at least one product'));
  if (over.length) problems.push(tx('A quantity is more than remains to be scheduled'));
  if (negative) problems.push(tx('Quantities must not be less than 0'));
  if (allowed('vehiclePlate') && plateNeeded && !f.vehiclePlate.trim()) problems.push(tx('Enter the vehicle registration'));
  if (allowed('destinationLocationId') && method === 'warehouse_transfer' && !f.destinationLocationId) problems.push(tx('Choose the destination warehouse'));
  if (allowed('transportCompany') && method === 'courier' && !f.transportCompany.trim()) problems.push(tx('Enter the courier / logistics company'));
  if (allowed('plannedDate') && !f.plannedDate) problems.push(tx('Choose the scheduled date'));

  const fill = (share: number | null) => setQ(Object.fromEntries(lines.map((l) => {
    const v = share === null ? 0 : Math.floor(available(l) * share * 1000) / 1000;
    return [l.lineId, v > 0 ? String(v) : ''];
  })));

  const locs = (locations.data?.data ?? []).filter((l) => l.isActive || l.id === f.locationId || l.id === f.destinationLocationId);
  const text = (k: Key, label: string, opts: { type?: string; placeholder?: string; required?: boolean; className?: string } = {}) => (
    <Labeled label={label} required={opts.required} className={opts.className}>
      <Input type={opts.type ?? 'text'} value={f[k]} disabled={!allowed(k)} placeholder={opts.placeholder}
        step={opts.type === 'number' ? 'any' : undefined} min={opts.type === 'number' ? 0 : undefined}
        onChange={(e) => set(k, e.target.value)} />
    </Labeled>
  );
  const select = (k: Key, label: string, required?: boolean) => (
    <Labeled label={label} required={required}>
      <select className="h-9 w-full rounded-md border bg-background px-2 text-sm disabled:opacity-60" value={f[k]} disabled={!allowed(k)}
        onChange={(e) => set(k, e.target.value)}>
        <option value="">{tx('Select a warehouse')}</option>
        {locs.map((l) => <option key={l.id} value={l.id}>{l.name} ({l.code})</option>)}
      </select>
    </Labeled>
  );

  const title = editing ? `${tx('Edit delivery')} ${existing!.code}` : mode.kind === 'reschedule' ? `${tx('Reschedule')} ${existing!.code}` : tx('Add delivery');
  return (
    <Dialog open onOpenChange={(v) => !v && onClose()}>
      <DialogContent className="max-h-[92vh] max-w-4xl overflow-y-auto">
        <DialogHeader>
          <DialogTitle>{title}</DialogTitle>
          <DialogDescription>
            {editing && stage !== 'scheduled'
              ? tx('The goods have left: only tracking, contact and cost details can change.')
              : tx('Each delivery has its own date, transport and quantities. Stock is issued when the delivery is dispatched.')}
          </DialogDescription>
        </DialogHeader>

        <div className="space-y-5">
          <Group icon={Truck} title={tx('Delivery method')}>
            <div className="grid grid-cols-2 gap-2 md:grid-cols-4">
              {(Object.keys(METHOD_META) as DeliveryMethod[]).map((m) => {
                const meta = METHOD_META[m];
                const Icon = meta.icon;
                const on = method === m;
                return (
                  <button key={m} type="button" disabled={!allowed('deliveryMethod')} onClick={() => set('deliveryMethod', m)} aria-pressed={on}
                    className={cn('flex flex-col items-center gap-1 rounded-lg border px-2 py-2.5 text-center text-xs transition-colors disabled:cursor-not-allowed',
                      on ? 'border-primary bg-primary/5 text-primary ring-1 ring-primary' : 'hover:bg-muted', !allowed('deliveryMethod') && !on && 'opacity-50')}>
                    <Icon className="h-5 w-5" /><span className="font-medium">{meta.label}</span>
                  </button>
                );
              })}
            </div>
          </Group>

          <Group icon={CalendarClock} title={tx('Schedule & route')}>
            <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
              {text('plannedDate', tx('Scheduled date'), { type: 'date', required: true })}
              {text('scheduledTime', tx('Time'), { type: 'time' })}
              {select('locationId', method === 'customer_collect' ? tx('Collect from') : tx('Dispatch warehouse'))}
              {text('expectedArrivalAt', tx('Expected arrival'), { type: 'datetime-local' })}
              {method === 'warehouse_transfer' && select('destinationLocationId', tx('Destination warehouse'), true)}
              {method !== 'customer_collect' && method !== 'warehouse_transfer' && text('deliveryAddress', tx('Delivery address / site'), { className: 'sm:col-span-2' })}
              {method !== 'customer_collect' && text('route', tx('Route'), { placeholder: tx('e.g. Kampala – Mukono via Jinja Rd'), className: method === 'warehouse_transfer' ? 'sm:col-span-1 lg:col-span-3' : 'sm:col-span-2' })}
              {text('siteContactName', method === 'customer_collect' ? tx('Collected by') : tx('Site contact'))}
              {text('siteContactPhone', tx('Contact phone'), { type: 'tel' })}
              {method === 'site_delivery' && text('offloadingEquipment', tx('Offloading equipment'), { placeholder: tx('Crane, forklift, manual…'), className: 'sm:col-span-2' })}
            </div>
          </Group>

          <Group icon={MapPin} title={method === 'courier' ? tx('Courier') : method === 'customer_collect' ? tx("Customer's transport") : tx('Transport')}>
            <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
              {method === 'courier' && text('transportCompany', tx('Courier / logistics company'), { required: true })}
              {method === 'courier' && text('trackingNumber', tx('Tracking number'))}
              {method !== 'courier' && text('driverName', tx('Driver'))}
              {method !== 'courier' && text('driverPhone', tx('Driver phone'), { type: 'tel' })}
              {text('vehiclePlate', tx('Vehicle registration'), { required: plateNeeded, placeholder: 'UBH 123D' })}
              {text('vehicleType', tx('Vehicle type'), { placeholder: tx('Tipper, flatbed, van…') })}
              {method !== 'courier' && method !== 'customer_collect' && text('transportCompany', tx('Transport company'))}
              {method !== 'courier' && method !== 'customer_collect' && text('trackingNumber', tx('Tracking / waybill no.'))}
              {method !== 'customer_collect' && text('transportCost', tx('Transport cost'), { type: 'number' })}
              {method !== 'customer_collect' && text('tripExpenses', tx('Fuel / trip expenses'), { type: 'number' })}
            </div>
          </Group>

          <Group icon={PackageOpen} title={tx('Products and quantities')} actions={qtyEditable && (
            <div className="flex flex-wrap gap-1.5">
              <Button type="button" size="sm" variant="outline" onClick={() => fill(1)}>{tx('All remaining')}</Button>
              <Button type="button" size="sm" variant="outline" onClick={() => fill(0.5)}>50%</Button>
              <Button type="button" size="sm" variant="ghost" onClick={() => fill(null)}>{tx('Clear')}</Button>
            </div>
          )}>
            <div className="overflow-x-auto rounded-lg border">
              <table className="w-full min-w-[640px] text-sm">
                <thead className="bg-muted/40 text-left text-[11px] font-semibold uppercase tracking-wide text-muted-foreground">
                  <tr>
                    <th className="px-3 py-2">{tx('Product')}</th>
                    <th className="px-3 py-2 text-right">{tx('Ordered')}</th>
                    <th className="px-3 py-2 text-right">{tx('Delivered')}</th>
                    <th className="px-3 py-2 text-right">{tx('Available')}</th>
                    <th className="w-44 px-3 py-2 text-right">{tx('This delivery')}</th>
                  </tr>
                </thead>
                <tbody>
                  {lines.map((l) => {
                    const bad = n(q[l.lineId]) > available(l) + 1e-9;
                    return (
                      <tr key={l.lineId} className="border-t">
                        <td className="px-3 py-2"><span className="font-medium">{l.productName ?? l.description}</span>
                          {l.productName && l.description && l.description !== l.productName && <span className="block text-xs text-muted-foreground">{l.description}</span>}</td>
                        <td className="px-3 py-2 text-right tabular-nums">{qty(l.ordered)} <span className="text-xs text-muted-foreground">{l.uomCode}</span></td>
                        <td className="px-3 py-2 text-right tabular-nums">{qty(l.delivered)}</td>
                        <td className={cn('px-3 py-2 text-right tabular-nums', available(l) <= 0 && 'text-muted-foreground')}>{qty(available(l))}</td>
                        <td className="px-3 py-1.5">
                          <div className="flex items-center justify-end gap-1.5">
                            <Input type="number" min={0} step="any" inputMode="decimal" aria-label={`${tx('Quantity')} ${l.productName ?? l.description}`}
                              disabled={!qtyEditable || (available(l) <= 0 && !q[l.lineId])} value={q[l.lineId] ?? ''}
                              onChange={(e) => setQ((x) => ({ ...x, [l.lineId]: e.target.value }))}
                              className={cn('h-8 w-28 text-right', bad && 'border-rose-500 focus-visible:ring-rose-500')} />
                            <span className="w-8 text-xs text-muted-foreground">{l.uomCode}</span>
                          </div>
                          {bad && <p className="mt-0.5 text-right text-[11px] text-rose-600">{tx('Max')} {qty(available(l))}</p>}
                        </td>
                      </tr>
                    );
                  })}
                  {!lines.length && <tr><td colSpan={5} className="px-3 py-6 text-center text-muted-foreground">{tx('No order lines.')}</td></tr>}
                </tbody>
              </table>
            </div>
            {!qtyEditable && <p className="mt-2 text-xs text-muted-foreground">{tx('Quantities are fixed once loading starts.')}</p>}
          </Group>

          <Labeled label={tx('Delivery instructions')}>
            <Textarea rows={2} maxLength={2000} value={f.notes} disabled={!allowed('notes')} onChange={(e) => set('notes', e.target.value)}
              placeholder={tx('e.g. Deliver during working hours (8 AM – 5 PM)')} />
          </Labeled>
        </div>

        {problems.length > 0 && (
          <div className="flex items-start gap-2 rounded-lg border border-amber-200 bg-amber-50 px-3 py-2 text-xs text-amber-900 dark:border-amber-900 dark:bg-amber-950/30 dark:text-amber-200">
            <AlertTriangle className="mt-0.5 h-3.5 w-3.5 shrink-0" /><span>{problems.join(' · ')}</span>
          </div>
        )}
        <DialogFooter>
          <Button variant="outline" onClick={onClose}>{tx('Cancel')}</Button>
          <Button disabled={problems.length > 0 || save.isPending}
            onClick={() => save.mutate(undefined, { onSuccess: (d) => { onSaved?.(d as SalesDelivery); onClose(); } })}>
            {save.isPending ? tx('Saving…') : editing ? tx('Save changes') : mode.kind === 'reschedule' ? tx('Reschedule delivery') : tx('Schedule delivery')}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

function Group({ icon: Icon, title, actions, children }: { icon: typeof Truck; title: string; actions?: ReactNode; children: ReactNode }) {
  return (
    <section>
      <div className="mb-2 flex flex-wrap items-center justify-between gap-2">
        <h4 className="flex items-center gap-1.5 text-xs font-semibold uppercase tracking-wide text-muted-foreground"><Icon className="h-3.5 w-3.5" />{title}</h4>
        {actions}
      </div>
      {children}
    </section>
  );
}

export function Labeled({ label, required, className, children }: { label: string; required?: boolean; className?: string; children: ReactNode }) {
  return (
    <label className={cn('block space-y-1', className)}>
      <span className="text-xs font-medium text-muted-foreground">{label}{required && <span className="ml-0.5 text-rose-500">*</span>}</span>
      {children}
    </label>
  );
}

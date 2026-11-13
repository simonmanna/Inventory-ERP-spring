import { useEffect, useMemo, useState, type ReactNode } from 'react';
import { PERMISSIONS } from '@erp/shared';
import {
  AlertTriangle, Boxes, CalendarDays, CheckCircle2, ClipboardList, Eye, FileText, Lightbulb, ListChecks, MoreVertical, PackageCheck,
  Plus, Save, Search, Settings2, Truck, Undo2,
} from 'lucide-react';
import { toast } from 'sonner';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Skeleton } from '@/components/ui/skeleton';
import { Textarea } from '@/components/ui/textarea';
import {
  DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuSeparator, DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu';
import { api } from '@/lib/api';
import { apiErrorMessage } from '@/lib/api-error';
import { date as fmtDate } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';
import { cn } from '@/lib/utils';
import { useAuthStore } from '@/stores/auth.store';
import { ColumnPicker, useColumns, type ColumnDef } from '@/components/column-picker';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs';
import type { SalesOrder } from '@/features/sales/api';
import {
  openDeliveryNote, useDeliveryAction, useDeliveryPlan, useDeliverySummary, useOrderDeliveries,
  type DeliveryMethod, type DeliveryPlan, type DeliveryStage, type OrderReturn, type SalesDelivery, type SummaryLine,
} from '@/features/sales/delivery-api';
import { ReturnsList, orderReturnView } from './delivery-docs';
import { PickedCell } from './HandoverDeliveryView';
import type { HandoverForm } from '../handover-draft';
import { DeliveryActionDialogs } from './DeliveryActionsHost';
import { DeliveryPlanPanel } from './DeliveryPlanPanel';
import { useDeliveryActionDefs, type DeliveryActionKey } from './DeliveryDetailDialog';
import {
  Kpi, LineBar, METHOD_META, OrderStageStepper, PLAN_TO_METHOD, Progress, STAGE_META, Section, StageBadge, n, qty, qtyUnit, scheduleLabel,
} from './delivery-ui';

/** Order-level plan values in form shape. */
function planForm(p: DeliveryPlan): HandoverForm {
  return {
    method: p.method, locationId: p.locationId ?? '', address: p.address ?? '', contactName: p.contactName ?? '', contactPhone: p.contactPhone ?? '',
    scheduledDate: p.scheduledDate ? p.scheduledDate.slice(0, 10) : '', driver: '', vehicle: '', receivedBy: '', notes: p.notes ?? '',
    landmark: p.landmark ?? '', loadingSite: p.loadingSite ?? '', deliveryScope: p.deliveryScope ?? '', offloadingEquipment: p.offloadingEquipment ?? '',
    gpsCoordinates: p.gpsCoordinates ?? '', offloadingSite: p.offloadingSite ?? '', destinationLocationId: p.destinationLocationId ?? '',
    courierName: p.courierName ?? '', deadline: p.deadline ? p.deadline.slice(0, 10) : '',
  };
}

const PLAN_KEYS: Array<keyof HandoverForm> = ['method', 'locationId', 'address', 'contactName', 'contactPhone', 'scheduledDate', 'notes', 'landmark',
  'loadingSite', 'deliveryScope', 'offloadingEquipment', 'gpsCoordinates', 'offloadingSite', 'destinationLocationId', 'courierName', 'deadline'];

type StageFilter = 'all' | 'active' | DeliveryStage;

type DeliveryCol = 'note' | 'scheduled' | 'deadline' | 'method' | 'warehouse' | 'driver' | 'planned' | 'dispatched' | 'delivered'
  | 'returned' | 'deliveredAt' | 'scope';

const DELIVERY_COLS: ColumnDef<DeliveryCol>[] = [
  { key: 'note', label: tx('Delivery note') },
  { key: 'scheduled', label: tx('Date scheduled') },
  { key: 'deadline', label: tx('Deadline'), defaultVisible: false },
  { key: 'method', label: tx('Method') },
  { key: 'warehouse', label: tx('Warehouse'), defaultVisible: false },
  { key: 'driver', label: tx('Driver / vehicle') },
  { key: 'planned', label: tx('Planned') },
  { key: 'dispatched', label: tx('Dispatched') },
  { key: 'delivered', label: tx('Delivered') },
  { key: 'returned', label: tx('Returned / rejected'), defaultVisible: false },
  { key: 'deliveredAt', label: tx('Delivered on'), defaultVisible: false },
  { key: 'scope', label: tx('Scope') },
];

/**
 * Delivery & fulfilment of a saved sales order: overview, status, order-level delivery plan, deliveries (each with its
 * own date, transport, quantities and outcome), per-line fulfilment, instructions and confirmation.
 */
export function OrderDeliveryTab({ order, requestedDate, onRequestedDate, dateEditable, footer }: {
  order: SalesOrder; requestedDate: string; onRequestedDate: (v: string) => void; dateEditable: boolean; footer?: ReactNode;
}) {
  const has = useAuthStore((s) => s.hasPermission);
  const P = PERMISSIONS.sales;
  const summary = useDeliverySummary(order.id);
  const deliveries = useOrderDeliveries(order.id);
  const plan = useDeliveryPlan(order.id);
  const [form, setForm] = useState<HandoverForm | null>(null);
  useEffect(() => { if (plan.data) setForm(planForm(plan.data)); }, [plan.data]);
  const dirty = !!form && !!plan.data && PLAN_KEYS.some((k) => (form[k] ?? '') !== (planForm(plan.data!)[k] ?? ''));
  const planLocked = !has(P.orderWrite) || plan.data?.stage === 'closed';
  const savePlan = useDeliveryAction(async () => {
    const body: Record<string, unknown> = {};
    for (const k of PLAN_KEYS) body[k] = (form![k] as string) || null;
    body.method = form!.method;
    await api.patch(`/sales/orders/${order.id}/handover`, body);
  }, tx('Delivery details saved'));

  const orderOpen = ['confirmed', 'in_fulfilment'].includes(order.status) && !order.onHold;
  const canPlan = orderOpen && has(P.deliveryWrite);
  const [action, setAction] = useState<{ key: DeliveryActionKey | 'add' | 'view'; delivery?: SalesDelivery; initialQty?: Record<string, number> } | null>(null);
  const run = async (key: DeliveryActionKey | 'view', d: SalesDelivery) => {
    if (key === 'note' || key === 'print') { void openDeliveryNote(d, key === 'note' ? 'download' : 'print'); return; }
    if (['edit', 'reschedule', 'confirm'].includes(key) && !d.lines) {
      try { d = (await api.get<SalesDelivery>(`/sales/deliveries/${d.id}`)).data; } catch (e) { toast.error(apiErrorMessage(e, tx('Could not load the delivery'))); return; }
    }
    setAction({ key, delivery: d });
  };

  const s = summary.data;
  const lines = s?.lines ?? [];
  const single = s && s.totals.length === 1 ? s.totals[0] : null;
  const unit = single?.uomCode ?? null;
  const list = deliveries.data ?? [];
  const awaiting = list.filter((d) => d.stage === 'dispatched' || d.stage === 'in_transit').length;
  const problems = list.filter((d) => d.stage === 'failed' || d.stage === 'returned' || d.stage === 'partially_delivered').length;
  const toScheduleTotal = lines.reduce((a, l) => a + n(l.toSchedule), 0);

  return (
    <div className="space-y-5">
      {/* status strip */}
      <div className="grid gap-4 xl:grid-cols-[auto_minmax(0,1fr)_minmax(0,300px)] xl:items-center">
        <div className="flex items-center gap-3 rounded-xl border bg-card px-4 py-3 shadow-sm">
          <CalendarDays className="h-5 w-5 text-primary" />
          <div>
            <p className="text-xs font-medium text-muted-foreground">{tx('Requested date')}</p>
            {dateEditable ? <Input type="date" className="mt-0.5 h-8 w-40" value={requestedDate} onChange={(e) => onRequestedDate(e.target.value)} />
              : <p className="text-sm font-semibold">{order.requestedDate ? fmtDate(order.requestedDate) : '—'}</p>}
          </div>
        </div>
        {s ? <OrderStageStepper stage={s.stage} /> : <Skeleton className="h-9 w-full max-w-xl" />}
        <div className="flex items-start gap-3 rounded-xl border border-blue-200 bg-blue-50/70 px-4 py-3 text-sm dark:border-blue-900 dark:bg-blue-950/30">
          <Lightbulb className="mt-0.5 h-5 w-5 shrink-0 text-blue-600" />
          <div><p className="font-semibold text-blue-900 dark:text-blue-200">{tx('Flexible delivery')}</p>
            <p className="text-xs text-blue-800/80 dark:text-blue-300/80">{tx('Create several deliveries and confirm what actually arrived on each one.')}</p></div>
        </div>
      </div>

      {/* overview */}
      {summary.isError ? <ErrorBox text={apiErrorMessage(summary.error, tx('Could not load the delivery overview'))} onRetry={() => summary.refetch()} /> : !s ? (
        <div className="grid gap-3 sm:grid-cols-3 xl:grid-cols-6">{Array.from({ length: 6 }, (_, i) => <Skeleton key={i} className="h-[74px] rounded-xl" />)}</div>
      ) : (
        <div className="space-y-3">
          <div className="grid gap-3 sm:grid-cols-3 xl:grid-cols-6">
            <Kpi icon={ClipboardList} label={tx('Ordered')} value={single ? qtyUnit(single.ordered, unit) : `${lines.length} ${tx('lines')}`} />
            <Kpi icon={CalendarDays} tone="slate" label={tx('Scheduled')} value={single ? qtyUnit(single.scheduled, unit) : n(s.stages.scheduled)} hint={single ? undefined : tx('deliveries')} />
            <Kpi icon={Truck} tone="blue" label={tx('Dispatched')} value={single ? qtyUnit(single.dispatched, unit) : n(s.stages.dispatched) + n(s.stages.in_transit)} hint={tx('on the road')} />
            <Kpi icon={PackageCheck} tone="green" label={tx('Delivered')} value={single ? qtyUnit(single.delivered, unit) : `${qty(s.percentDelivered)}%`} hint={single ? undefined : tx('by value')} />
            <Kpi icon={AlertTriangle} tone="amber" label={tx('Outstanding')} value={single ? qtyUnit(single.outstanding, unit) : lines.filter((l) => n(l.outstanding) > 0).length}
              hint={single ? `${qty(single.toSchedule)} ${tx('to schedule')}` : tx('lines open')} />
            <Kpi icon={Boxes} tone="indigo" label={tx('Delivery trips')} value={s.trips} hint={n(s.stages.cancelled) ? `${s.stages.cancelled} ${tx('cancelled')}` : undefined} />
          </div>
          <div className="flex items-center gap-3 rounded-xl border bg-card px-4 py-2.5 shadow-sm">
            <span className="whitespace-nowrap text-sm font-medium">{tx('Total delivered')}: {qty(s.percentDelivered)}%</span>
            <Progress value={n(s.percentDelivered)} />
            {!single && <span className="whitespace-nowrap text-xs text-muted-foreground">{tx('mixed units — weighted by value')}</span>}
          </div>
        </div>
      )}

      {/* plan */}
      <Section icon={Settings2} title={tx('Delivery plan')} actions={plan.data?.saved === false && <span className="text-xs text-muted-foreground">{tx('Defaults from the order')}</span>}>
        {plan.isError ? <ErrorBox text={apiErrorMessage(plan.error, tx('Could not load the delivery details'))} onRetry={() => plan.refetch()} />
          : !form ? <Skeleton className="h-48 w-full" />
            : <DeliveryPlanPanel value={form} onChange={setForm} locked={planLocked} fallbackLocationName={plan.data?.locationName} />}
      </Section>

      {/* quick add */}
      {canPlan && s && toScheduleTotal > 0 && (
        <QuickAdd orderId={order.id} lines={lines} plan={plan.data} form={form} fallbackLocationId={order.locationId}
          nextIndex={list.filter((d) => d.stage !== 'cancelled').length + 1}
          onAdvanced={(initialQty) => setAction({ key: 'add', initialQty })} />
      )}
      {!orderOpen && order.status !== 'done' && (
        <p className="rounded-lg border border-dashed px-4 py-3 text-sm text-muted-foreground">
          {order.onHold ? tx('The order is on hold; deliveries resume once it is released.') : tx('Confirm the sales order to schedule deliveries.')}
        </p>
      )}

      {/* deliveries */}
      <DeliveriesTable rows={list} loading={deliveries.isLoading} error={deliveries.isError ? apiErrorMessage(deliveries.error, tx('Could not load the deliveries')) : null}
        onRetry={() => deliveries.refetch()} percent={n(s?.percentDelivered)} orderOpen={orderOpen} orderedTotal={single ? n(single.ordered) : null}
        canAdd={canPlan && toScheduleTotal > 0} onAdd={() => setAction({ key: 'add' })} onAction={run} deadline={s?.deadline ?? null} />

      {/* per line */}
      {s && lines.length > 0 && <LineFulfilment lines={lines} deadline={s.deadline} returns={s.returns ?? []} deliveries={list} onAction={run} />}

      {/* notes / confirmation */}
      <div className="grid gap-4 lg:grid-cols-3">
        <Section icon={FileText} title={tx('Delivery notes / instructions')}>
          {form ? (
            <div className="space-y-1">
              <Textarea rows={3} maxLength={500} disabled={planLocked} value={form.notes} placeholder={tx('e.g. Please deliver during working hours (8 AM – 5 PM).')}
                onChange={(e) => setForm({ ...form, notes: e.target.value })} />
              <p className="text-right text-[11px] text-muted-foreground">{form.notes.length}/500</p>
            </div>
          ) : <Skeleton className="h-20 w-full" />}
        </Section>
        <Section icon={ListChecks} title={tx('Tracking & confirmation')}>
          <ul className="space-y-2 text-sm">
            <li className="flex items-center justify-between"><span className="text-muted-foreground">{tx('Awaiting proof of delivery')}</span><span className="font-semibold tabular-nums">{awaiting}</span></li>
            <li className="flex items-center justify-between"><span className="text-muted-foreground">{tx('Exceptions (failed, returned, short)')}</span>
              <span className={cn('font-semibold tabular-nums', problems > 0 && 'text-amber-600')}>{problems}</span></li>
            <li className="text-xs text-muted-foreground">{tx('Each delivery records the receiver, signature, photos and the accepted, rejected, damaged and short quantities.')}</li>
          </ul>
        </Section>
        <div className="flex items-start gap-3 rounded-xl border border-emerald-200 bg-emerald-50/70 p-4 dark:border-emerald-900 dark:bg-emerald-950/30">
          <CheckCircle2 className="mt-0.5 h-5 w-5 shrink-0 text-emerald-600" />
          <div>
            <p className="text-sm font-semibold text-emerald-900 dark:text-emerald-200">{tx('Partial & multiple delivery supported')}</p>
            <p className="text-xs text-emerald-800/80 dark:text-emerald-300/80">{tx('Deliver in parts and track each delivery separately. Stock is issued once, at dispatch.')}</p>
          </div>
        </div>
      </div>

      {footer}

      {!planLocked && form && (
        <div className="sticky bottom-0 z-10 -mx-5 flex items-center justify-end gap-2 border-t bg-card/95 px-5 py-3 backdrop-blur">
          {dirty && <span className="mr-auto text-xs text-amber-600">{tx('Unsaved delivery details')}</span>}
          <Button variant="outline" disabled={!dirty || savePlan.isPending} onClick={() => plan.data && setForm(planForm(plan.data))}>{tx('Cancel')}</Button>
          <Button disabled={!dirty || savePlan.isPending} onClick={() => savePlan.mutate(undefined)}><Save className="mr-1.5 h-4 w-4" />{savePlan.isPending ? tx('Saving…') : tx('Save delivery details')}</Button>
        </div>
      )}

      <DeliveryActionDialogs order={order} lines={lines} plan={plan.data} action={action} onAction={run} onClose={() => setAction(null)} />
    </div>
  );
}

function ErrorBox({ text, onRetry }: { text: string; onRetry: () => void }) {
  return (
    <div className="flex items-center justify-between gap-3 rounded-xl border border-rose-200 bg-rose-50 px-4 py-3 text-sm text-rose-800 dark:border-rose-900 dark:bg-rose-950/30 dark:text-rose-200">
      <span>{text}</span><Button size="sm" variant="outline" onClick={onRetry}>{tx('Retry')}</Button>
    </div>
  );
}

/** Inline "Add delivery" row: date, method, driver, vehicle and a share of what remains (or a quantity, for one line). */
function QuickAdd({ orderId, lines, plan, form, fallbackLocationId, nextIndex, onAdvanced }: {
  orderId: string; lines: SummaryLine[]; plan?: DeliveryPlan; form: HandoverForm | null; fallbackLocationId: string | null; nextIndex: number;
  onAdvanced: (initialQty: Record<string, number>) => void;
}) {
  const open = lines.filter((l) => n(l.toSchedule) > 0);
  const oneLine = open.length === 1 ? open[0] : null;
  const planMethod = form ? PLAN_TO_METHOD[form.method] : plan ? PLAN_TO_METHOD[plan.method] : 'site_delivery';
  const [date, setDate] = useState(() => new Date().toISOString().slice(0, 10));
  const [method, setMethod] = useState<DeliveryMethod>(planMethod);
  useEffect(() => setMethod(planMethod), [planMethod]);
  const [driver, setDriver] = useState('');
  const [vehicle, setVehicle] = useState('');
  const multiple = (form?.deliveryScope ?? plan?.deliveryScope) !== 'full';
  const [scope, setScope] = useState<'full' | 'partial'>(multiple ? 'partial' : 'full');
  const [amount, setAmount] = useState('');
  const [unit, setUnit] = useState<'%' | 'qty'>('%');
  useEffect(() => { if (!oneLine && unit === 'qty') setUnit('%'); }, [oneLine, unit]);

  const quantities = useMemo(() => {
    const out: Record<string, number> = {};
    for (const l of open) {
      const avail = n(l.toSchedule);
      let v = avail;
      if (scope === 'partial') v = unit === 'qty' ? Math.min(n(amount), avail) : Math.floor(avail * Math.min(n(amount), 100) * 10) / 1000;
      if (v > 0) out[l.lineId] = v;
    }
    return out;
  }, [open, scope, unit, amount]);
  const needsPlate = method === 'site_delivery' || method === 'warehouse_transfer';
  const missing = !date ? tx('Choose the date') : needsPlate && !vehicle.trim() ? tx('Enter the vehicle')
    : method === 'warehouse_transfer' && !form?.destinationLocationId ? tx('Set the destination warehouse in the plan, or use More options')
      : method === 'courier' && !form?.courierName ? tx('Set the courier in the plan, or use More options')
        : scope === 'partial' && !(n(amount) > 0) ? tx('Enter the quantity') : unit === 'qty' && oneLine && n(amount) > n(oneLine.toSchedule) ? `${tx('Max')} ${qty(oneLine.toSchedule)}`
          : !Object.keys(quantities).length ? tx('Enter the quantity') : null;

  const add = useDeliveryAction(async () => {
    const body: Record<string, unknown> = {
      plannedDate: date, deliveryMethod: method, driverName: driver || undefined, vehiclePlate: vehicle || undefined,
      locationId: form?.locationId || fallbackLocationId || undefined, deliveryAddress: method === 'site_delivery' || method === 'courier' ? form?.address || undefined : undefined,
      siteContactName: form?.contactName || undefined, siteContactPhone: form?.contactPhone || undefined,
      destinationLocationId: method === 'warehouse_transfer' ? form?.destinationLocationId || undefined : undefined,
      transportCompany: method === 'courier' ? form?.courierName || undefined : undefined,
      notes: form?.notes || undefined,
      lines: Object.entries(quantities).map(([lineId, quantity]) => ({ lineId, quantity })),
    };
    await api.post(`/sales/orders/${orderId}/deliveries`, body);
  }, tx('Delivery scheduled'));

  const sel = 'h-9 w-full rounded-md border bg-background px-2 text-sm';
  return (
    <section className="rounded-xl border border-primary/30 bg-primary/[0.03] p-4 shadow-sm">
      <div className="mb-3 flex flex-wrap items-center justify-between gap-2">
        <h3 className="flex items-center gap-2 text-sm font-semibold"><span className="flex h-6 w-6 items-center justify-center rounded-full bg-primary text-primary-foreground"><Plus className="h-4 w-4" /></span>{tx('Add delivery')}</h3>
        <button type="button" className="text-xs font-medium text-primary hover:underline" onClick={() => onAdvanced(quantities)}>{tx('More options & per-product quantities…')}</button>
      </div>
      <div className="grid items-end gap-3 sm:grid-cols-2 lg:grid-cols-4 2xl:grid-cols-[110px_150px_1fr_1fr_1fr_130px_170px_auto]">
        <Field label={tx('Delivery #')}><Input disabled value={`${tx('Delivery')} ${nextIndex}`} /></Field>
        <Field label={tx('Scheduled date')}><Input type="date" value={date} onChange={(e) => setDate(e.target.value)} /></Field>
        <Field label={tx('Delivery method')}>
          <select className={sel} value={method} onChange={(e) => setMethod(e.target.value as DeliveryMethod)}>
            {(Object.keys(METHOD_META) as DeliveryMethod[]).map((m) => <option key={m} value={m}>{METHOD_META[m].label}</option>)}
          </select>
        </Field>
        <Field label={tx('Driver')}><Input value={driver} placeholder={tx('Driver name')} onChange={(e) => setDriver(e.target.value)} /></Field>
        <Field label={tx('Vehicle')}><Input value={vehicle} placeholder="UBH 123D" onChange={(e) => setVehicle(e.target.value)} /></Field>
        <Field label={tx('Delivery scope')}>
          <select className={sel} value={scope} onChange={(e) => setScope(e.target.value as 'full' | 'partial')}>
            <option value="full">{tx('All remaining')}</option><option value="partial">{tx('Partial')}</option>
          </select>
        </Field>
        <Field label={tx('Quantity / share')}>
          <div className="flex">
            <Input type="number" min={0} step="any" disabled={scope === 'full'} className="rounded-r-none" value={scope === 'full' ? '' : amount}
              placeholder={scope === 'full' ? '100' : unit === '%' ? '60' : qty(oneLine?.toSchedule)} onChange={(e) => setAmount(e.target.value)} />
            <select className="h-9 rounded-l-none rounded-r-md border border-l-0 bg-background px-2 text-sm" value={unit} disabled={scope === 'full'}
              onChange={(e) => setUnit(e.target.value as '%' | 'qty')}>
              <option value="%">%</option>{oneLine && <option value="qty">{oneLine.uomCode ?? tx('qty')}</option>}
            </select>
          </div>
        </Field>
        <Button disabled={!!missing || add.isPending} title={missing ?? undefined} onClick={() => add.mutate(undefined, { onSuccess: () => { setAmount(''); setDriver(''); setVehicle(''); } })}>
          <Plus className="mr-1 h-4 w-4" />{tx('Add')}
        </Button>
      </div>
      {missing && (driver || vehicle || amount) && <p className="mt-2 text-xs text-amber-700 dark:text-amber-400">{missing}</p>}
      {!multiple && <p className="mt-2 text-xs text-muted-foreground">{tx('Multiple deliveries are off in the plan: this delivery takes everything still open.')}</p>}
    </section>
  );
}

function Field({ label, children }: { label: string; children: ReactNode }) {
  return <label className="block space-y-1"><span className="text-xs font-medium text-muted-foreground">{label}</span>{children}</label>;
}

/** The deliveries of the order: filters, search and per-delivery actions. */
function DeliveriesTable({ rows, loading, error, onRetry, percent, orderOpen, orderedTotal, canAdd, onAdd, onAction, deadline }: {
  rows: SalesDelivery[]; loading: boolean; error: string | null; onRetry: () => void; percent: number; orderOpen: boolean; deadline: string | null;
  orderedTotal: number | null; canAdd: boolean; onAdd: () => void; onAction: (key: DeliveryActionKey | 'view', d: SalesDelivery) => void;
}) {
  const defs = useDeliveryActionDefs();
  const cols = useColumns<DeliveryCol>('order-deliveries', DELIVERY_COLS);
  const on = cols.shown;
  const [filter, setFilter] = useState<StageFilter>('all');
  const [search, setSearch] = useState('');
  const ordered = useMemo(() => [...rows].sort((a, b) => (a.createdAt < b.createdAt ? -1 : 1)), [rows]);
  const index = useMemo(() => new Map(ordered.map((d, i) => [d.id, i + 1])), [ordered]);
  const shown = ordered.filter((d) => {
    if (filter === 'active' && !['scheduled', 'dispatched', 'in_transit'].includes(d.stage)) return false;
    if (filter !== 'all' && filter !== 'active' && d.stage !== filter) return false;
    const q = search.trim().toLowerCase();
    return !q || [d.code, d.driverName, d.vehiclePlate, d.deliveryNoteNumber, d.trackingNumber, d.transportCompany].some((v) => v?.toLowerCase().includes(q));
  });
  const th = 'px-3 py-2.5';
  const today = new Date().toISOString().slice(0, 10);
  const lateFor = (d: SalesDelivery) => !!deadline && deadline < today && ['scheduled', 'dispatched', 'in_transit', 'failed'].includes(d.stage);

  return (
    <Section icon={Truck} title={<>{tx('Deliveries')} <span className="font-normal text-muted-foreground">({rows.filter((d) => d.stage !== 'cancelled').length})</span></>}
      bodyClassName="p-0" actions={
        <>
          <div className="hidden items-center gap-2 rounded-full border bg-background px-3 py-1 md:flex">
            <span className="text-xs font-medium">{tx('Total delivered')}: {qty(percent)}%</span><Progress value={percent} className="w-28" />
          </div>
          <div className="relative">
            <Search className="pointer-events-none absolute left-2.5 top-1/2 h-3.5 w-3.5 -translate-y-1/2 text-muted-foreground" />
            <Input className="h-8 w-44 pl-8 text-xs" placeholder={tx('Search deliveries')} value={search} onChange={(e) => setSearch(e.target.value)} aria-label={tx('Search deliveries')} />
          </div>
          <select className="h-8 rounded-md border bg-background px-2 text-xs" value={filter} onChange={(e) => setFilter(e.target.value as StageFilter)} aria-label={tx('Filter by status')}>
            <option value="all">{tx('All statuses')}</option><option value="active">{tx('Open (not yet delivered)')}</option>
            {(Object.keys(STAGE_META) as DeliveryStage[]).map((k) => <option key={k} value={k}>{STAGE_META[k].label}</option>)}
          </select>
          <ColumnPicker columns={cols} className="h-8 w-8" />
          {canAdd && <Button size="sm" onClick={onAdd}><Plus className="mr-1 h-4 w-4" />{tx('Add delivery')}</Button>}
        </>
      }>
      {error ? <div className="p-4"><ErrorBox text={error} onRetry={onRetry} /></div> : loading ? <div className="space-y-2 p-4"><Skeleton className="h-10 w-full" /><Skeleton className="h-10 w-full" /></div> : (
        <div className="overflow-x-auto">
          <table className="w-full min-w-[1080px] text-sm">
            <thead className="bg-muted/40 text-left text-[11px] font-semibold uppercase tracking-wide text-muted-foreground">
              <tr>
                <th className={cn(th, 'w-12')} /><th className={th}>{tx('Delivery no.')}</th>
                {on('note') && <th className={th}>{tx('Delivery note')}</th>}
                {on('scheduled') && <th className={th}>{tx('Date scheduled')}</th>}
                {on('deadline') && <th className={th}>{tx('Deadline')}</th>}
                {on('method') && <th className={th}>{tx('Method')}</th>}
                {on('warehouse') && <th className={th}>{tx('Warehouse')}</th>}
                {on('driver') && <th className={th}>{tx('Driver / vehicle')}</th>}
                {on('planned') && <th className={cn(th, 'text-right')}>{tx('Planned')}</th>}
                {on('dispatched') && <th className={cn(th, 'text-right')}>{tx('Dispatched')}</th>}
                {on('delivered') && <th className={cn(th, 'text-right')}>{tx('Delivered')}</th>}
                {on('returned') && <th className={cn(th, 'text-right')}>{tx('Returned')}</th>}
                {on('deliveredAt') && <th className={th}>{tx('Delivered on')}</th>}
                {on('scope') && <th className={th}>{tx('Scope')}</th>}
                <th className={th}>{tx('Status')}</th><th className={cn(th, 'text-right')}>{tx('Actions')}</th>
              </tr>
            </thead>
            <tbody>
              {shown.map((d) => {
                const M = d.deliveryMethod ? METHOD_META[d.deliveryMethod] : null;
                const unit = d.uomCodes && !d.uomCodes.includes(',') ? d.uomCodes : null;
                const share = orderedTotal ? Math.round((n(d.plannedQty) / orderedTotal) * 100) : null;
                const acts = defs(d, orderOpen).filter((a) => a.key !== 'note' && a.key !== 'print');
                const muted = d.stage === 'cancelled';
                return (
                  <tr key={d.id} className={cn('border-t transition-colors hover:bg-muted/30', muted && 'text-muted-foreground')}>
                    <td className={th}><span className="flex h-7 w-7 items-center justify-center rounded-md bg-primary/10 text-xs font-semibold text-primary">{index.get(d.id)}</span></td>
                    <td className={th}>
                      <button type="button" className="font-semibold hover:text-primary hover:underline" onClick={() => onAction('view', d)}>{d.code}</button>
                      <div className="mt-0.5 flex flex-wrap gap-1 text-[11px] text-muted-foreground">
                        {d.deliveryNoteNumber && !on('note') && <span>{d.deliveryNoteNumber}</span>}
                        {d.rescheduledFromId && <span className="rounded bg-muted px-1">{tx('Rescheduled')}</span>}
                        {d.approvalRequired && !d.approvedAt && <span className="rounded bg-amber-100 px-1 text-amber-800 dark:bg-amber-950 dark:text-amber-300">{tx('Needs approval')}</span>}
                      </div>
                    </td>
                    {on('note') && <td className={th}>{d.deliveryNoteNumber
                      ? <button type="button" className="inline-flex items-center gap-1 whitespace-nowrap text-xs font-medium text-primary hover:underline" onClick={() => onAction('print', d)} title={tx('Print delivery note')}>
                        <FileText className="h-3.5 w-3.5" />{d.deliveryNoteNumber}</button>
                      : <span className="text-[11px] text-muted-foreground">{tx('At dispatch')}</span>}</td>}
                    {on('scheduled') && <td className={cn(th, 'whitespace-nowrap')}>{scheduleLabel(d.plannedDate, d.scheduledTime)}</td>}
                    {on('deadline') && <td className={cn(th, 'whitespace-nowrap', lateFor(d) && 'font-medium text-rose-600')}>{deadline ? fmtDate(deadline) : '—'}</td>}
                    {on('method') && <td className={th}>{M ? <span className="flex items-center gap-1.5 whitespace-nowrap"><M.icon className="h-4 w-4 text-muted-foreground" />{M.label}</span> : '—'}</td>}
                    {on('warehouse') && <td className={th}>{d.locationName ?? '—'}{d.destinationLocationName && <span className="block text-[11px] text-muted-foreground">→ {d.destinationLocationName}</span>}</td>}
                    {on('driver') && <td className={th}>{d.driverName || d.transportCompany || '—'}<span className="block text-[11px] text-muted-foreground">{[d.vehiclePlate, d.trackingNumber].filter(Boolean).join(' · ')}</span></td>}
                    {on('planned') && <td className={cn(th, 'text-right tabular-nums')}>{unit ? qtyUnit(d.plannedQty, unit) : `${d.lineCount} ${tx('products')}`}</td>}
                    {on('dispatched') && <td className={cn(th, 'text-right tabular-nums')}>{d.dispatchedAt ? (unit ? qty(d.dispatchedQty) : '✓') : '—'}</td>}
                    {on('delivered') && <td className={cn(th, 'text-right tabular-nums')}>{d.deliveredAt ? (unit ? qty(d.deliveredQty) : '✓') : '—'}
                      {(n(d.rejectedQty) > 0 || n(d.damagedQty) > 0) && <span className="block text-[11px] text-amber-600">{n(d.rejectedQty) > 0 && `${qty(d.rejectedQty)} ${tx('rej.')}`} {n(d.damagedQty) > 0 && `${qty(d.damagedQty)} ${tx('dmg.')}`}</span>}</td>}
                    {on('returned') && <td className={cn(th, 'text-right tabular-nums', d.stage === 'returned' && 'font-medium text-orange-600')}>
                      {d.stage === 'returned' ? (unit ? qty(d.dispatchedQty) : tx('All')) : n(d.rejectedQty) > 0 ? (unit ? qty(d.rejectedQty) : '✓') : '—'}</td>}
                    {on('deliveredAt') && <td className={cn(th, 'whitespace-nowrap')}>{d.deliveredAt ? fmtDate(d.deliveredAt) : '—'}</td>}
                    {on('scope') && <td className={th}>{share !== null && !muted ? (
                      <span className={cn('whitespace-nowrap rounded-full border px-2 py-0.5 text-[11px] font-medium', share >= 100 ? 'border-emerald-200 bg-emerald-50 text-emerald-700 dark:border-emerald-900 dark:bg-emerald-950/40 dark:text-emerald-300' : 'border-blue-200 bg-blue-50 text-blue-700 dark:border-blue-900 dark:bg-blue-950/40 dark:text-blue-300')}>
                        {share >= 100 ? tx('Full') : tx('Partial')} ({share}%)</span>) : '—'}</td>}
                    <td className={th}><StageBadge stage={d.stage} /></td>
                    <td className={cn(th, 'text-right')}>
                      <div className="flex items-center justify-end gap-1">
                        <Button size="sm" variant="outline" className="h-8" onClick={() => onAction('view', d)}><Eye className="mr-1 h-3.5 w-3.5" />{tx('View')}</Button>
                        <DropdownMenu>
                          <DropdownMenuTrigger asChild><Button size="icon" variant="ghost" className="h-8 w-8" aria-label={tx('More actions')}><MoreVertical className="h-4 w-4" /></Button></DropdownMenuTrigger>
                          <DropdownMenuContent align="end" className="w-56">
                            {acts.map((a) => (
                              <DropdownMenuItem key={a.key} className={cn(a.danger && 'text-rose-600 focus:text-rose-700')} onClick={() => onAction(a.key, d)}>
                                <a.icon className="mr-2 h-4 w-4" />{a.label}</DropdownMenuItem>
                            ))}
                            {d.deliveryNoteNumber && <>
                              {acts.length > 0 && <DropdownMenuSeparator />}
                              <DropdownMenuItem onClick={() => onAction('note', d)}><FileText className="mr-2 h-4 w-4" />{tx('Delivery note (PDF)')}</DropdownMenuItem>
                              <DropdownMenuItem onClick={() => onAction('print', d)}><FileText className="mr-2 h-4 w-4" />{tx('Print delivery note')}</DropdownMenuItem>
                            </>}
                            {!acts.length && !d.deliveryNoteNumber && <DropdownMenuItem disabled>{tx('No actions')}</DropdownMenuItem>}
                          </DropdownMenuContent>
                        </DropdownMenu>
                      </div>
                    </td>
                  </tr>
                );
              })}
              {!shown.length && (
                <tr><td colSpan={cols.count + 3} className="px-4 py-10 text-center">
                  <Truck className="mx-auto mb-2 h-7 w-7 text-muted-foreground/60" />
                  <p className="text-sm font-medium">{rows.length ? tx('No deliveries match the filter.') : tx('No deliveries yet')}</p>
                  {!rows.length && <p className="text-xs text-muted-foreground">{canAdd ? tx('Add a delivery above to schedule the first shipment.') : tx('Deliveries appear here once they are scheduled.')}</p>}
                </td></tr>
              )}
            </tbody>
          </table>
        </div>
      )}
    </Section>
  );
}

type OrderLineCol = 'product' | 'scheduledDate' | 'deadline' | 'ordered' | 'scheduled' | 'picked' | 'dispatched' | 'delivered' | 'invoiced'
  | 'returned' | 'exceptions' | 'outstanding' | 'toSchedule' | 'progress';

const ORDER_LINE_COLS: ColumnDef<OrderLineCol>[] = [
  { key: 'product', label: tx('Product'), locked: true },
  { key: 'scheduledDate', label: tx('Date scheduled') },
  { key: 'deadline', label: tx('Deadline') },
  { key: 'ordered', label: tx('Demand'), locked: true },
  { key: 'scheduled', label: tx('Scheduled qty') },
  { key: 'picked', label: tx('Picked') },
  { key: 'dispatched', label: tx('Dispatched') },
  { key: 'delivered', label: tx('Delivered') },
  { key: 'invoiced', label: tx('Invoiced') },
  { key: 'returned', label: tx('Returned') },
  { key: 'exceptions', label: tx('Exceptions'), defaultVisible: false },
  { key: 'outstanding', label: tx('Outstanding') },
  { key: 'toSchedule', label: tx('To schedule'), defaultVisible: false },
  { key: 'progress', label: tx('Progress') },
];

/**
 * Per order line: dates, demand, scheduled, picked, on the road, delivered, invoiced, returned, exceptions,
 * outstanding and what can still be scheduled (optional columns), plus the order's delivery notes and returns.
 */
function LineFulfilment({ lines, deadline, returns, deliveries, onAction }: {
  lines: SummaryLine[]; deadline: string | null; returns: OrderReturn[]; deliveries: SalesDelivery[];
  onAction: (key: DeliveryActionKey | 'view', d: SalesDelivery) => void;
}) {
  const [tab, setTab] = useState('operations');
  const cols = useColumns<OrderLineCol>('order-delivery-lines', ORDER_LINE_COLS);
  const on = cols.shown;
  const th = 'px-3 py-2.5';
  const today = new Date().toISOString().slice(0, 10);
  const notes = deliveries.filter((d) => d.deliveryNoteNumber);
  const views = returns.map(orderReturnView);
  return (
    <Section icon={PackageCheck} title={tx('Fulfilment by order line')} bodyClassName="p-0"
      actions={<span className="flex items-center gap-3 text-[11px] text-muted-foreground">
        {tab === 'operations' && <>
          <span className="hidden items-center gap-1 md:flex"><span className="h-2 w-2 rounded-full bg-emerald-500" />{tx('Delivered')}</span>
          <span className="hidden items-center gap-1 md:flex"><span className="h-2 w-2 rounded-full bg-blue-500" />{tx('Dispatched')}</span>
          <span className="hidden items-center gap-1 md:flex"><span className="h-2 w-2 rounded-full bg-slate-300" />{tx('Scheduled')}</span>
          <ColumnPicker columns={cols} />
        </>}</span>}>
      <Tabs value={tab} onValueChange={setTab}>
        <TabsList className="mx-4 mt-3">
          <TabsTrigger value="operations">{tx('Operations')}</TabsTrigger>
          <TabsTrigger value="notes"><FileText className="mr-1 h-3.5 w-3.5" />{tx('Delivery notes')}{notes.length ? ` (${notes.length})` : ''}</TabsTrigger>
          <TabsTrigger value="returns"><Undo2 className="mr-1 h-3.5 w-3.5" />{tx('Returns')}{returns.length ? ` (${returns.length})` : ''}</TabsTrigger>
        </TabsList>
        <TabsContent value="operations" className="mt-2">
          <div className="overflow-x-auto">
            <table className="w-full min-w-[980px] text-sm">
              <thead className="bg-muted/40 text-left text-[11px] font-semibold uppercase tracking-wide text-muted-foreground">
                <tr>
                  <th className={th}>{tx('Product')}</th>
                  {on('scheduledDate') && <th className={th}>{tx('Date scheduled')}</th>}
                  {on('deadline') && <th className={th}>{tx('Deadline')}</th>}
                  <th className={cn(th, 'text-right')}>{tx('Demand')}</th>
                  {on('scheduled') && <th className={cn(th, 'text-right')}>{tx('Scheduled')}</th>}
                  {on('picked') && <th className={cn(th, 'text-right')}>{tx('Picked')}</th>}
                  {on('dispatched') && <th className={cn(th, 'text-right')}>{tx('Dispatched')}</th>}
                  {on('delivered') && <th className={cn(th, 'text-right')}>{tx('Delivered')}</th>}
                  {on('invoiced') && <th className={cn(th, 'text-right')}>{tx('Invoiced')}</th>}
                  {on('returned') && <th className={cn(th, 'text-right')}>{tx('Returned')}</th>}
                  {on('exceptions') && <th className={cn(th, 'text-right')}>{tx('Exceptions')}</th>}
                  {on('outstanding') && <th className={cn(th, 'text-right')}>{tx('Outstanding')}</th>}
                  {on('toSchedule') && <th className={cn(th, 'text-right')}>{tx('To schedule')}</th>}
                  {on('progress') && <th className={cn(th, 'w-48')}>{tx('Progress')}</th>}
                </tr>
              </thead>
              <tbody>
                {lines.map((l) => {
                  const exceptions = n(l.rejected) + n(l.damaged) + n(l.shortage);
                  const late = !!deadline && n(l.outstanding) > 0 && deadline < today;
                  const returned = n(l.customerReturned) + n(l.returned);
                  return (
                    <tr key={l.lineId} className="border-t">
                      <td className={th}><span className="font-medium">{l.productName ?? l.description}</span>
                        {l.productName && l.description && l.description !== l.productName && <span className="block text-xs text-muted-foreground">{l.description}</span>}</td>
                      {on('scheduledDate') && <td className={cn(th, 'whitespace-nowrap')}>{l.scheduledDate ? fmtDate(l.scheduledDate) : l.lastDeliveredAt ? <span className="text-muted-foreground">{tx('Delivered')} {fmtDate(l.lastDeliveredAt)}</span> : '—'}</td>}
                      {on('deadline') && <td className={cn(th, 'whitespace-nowrap', late && 'font-medium text-rose-600')} title={late ? tx('Past the deadline with quantity outstanding') : undefined}>{deadline ? fmtDate(deadline) : '—'}</td>}
                      <td className={cn(th, 'text-right tabular-nums')}>{qtyUnit(l.ordered, l.uomCode)}</td>
                      {on('scheduled') && <td className={cn(th, 'text-right tabular-nums')}>{qty(l.scheduled)}</td>}
                      {on('picked') && <td className={cn(th, 'text-right tabular-nums')}><PickedCell picked={n(l.picked)} ordered={n(l.ordered)} /></td>}
                      {on('dispatched') && <td className={cn(th, 'text-right tabular-nums')}>{qty(l.dispatched)}</td>}
                      {on('delivered') && <td className={cn(th, 'text-right font-medium tabular-nums text-emerald-700 dark:text-emerald-400')}>{qty(l.delivered)}</td>}
                      {on('invoiced') && <td className={cn(th, 'text-right tabular-nums')}>{qty(l.invoiced)}</td>}
                      {on('returned') && <td className={cn(th, 'text-right tabular-nums', returned > 0 && 'font-medium text-orange-600')}
                        title={`${tx('Customer returns')} ${qty(l.customerReturned)} · ${tx('Returned loads')} ${qty(l.returned)}`}>{returned > 0 ? qty(returned) : '—'}</td>}
                      {on('exceptions') && <td className={cn(th, 'text-right tabular-nums', exceptions > 0 && 'text-amber-600')}
                        title={`${tx('Rejected')} ${qty(l.rejected)} · ${tx('Damaged')} ${qty(l.damaged)} · ${tx('Shortage')} ${qty(l.shortage)}`}>{exceptions > 0 ? qty(exceptions) : '—'}</td>}
                      {on('outstanding') && <td className={cn(th, 'text-right font-medium tabular-nums', n(l.outstanding) > 0 && 'text-amber-700 dark:text-amber-400')}>{qty(l.outstanding)}</td>}
                      {on('toSchedule') && <td className={cn(th, 'text-right tabular-nums')}>{qty(l.toSchedule)}</td>}
                      {on('progress') && <td className={th}><div className="flex items-center gap-2"><LineBar ordered={n(l.ordered)} delivered={n(l.delivered)} dispatched={n(l.dispatched)} scheduled={n(l.scheduled)} />
                        <span className="w-11 text-right text-xs tabular-nums text-muted-foreground">{qty(l.percentDelivered)}%</span></div></td>}
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        </TabsContent>
        <TabsContent value="notes" className="px-4 pb-4">
          {notes.length ? (
            <ul className="divide-y rounded-xl border">
              {notes.map((d) => (
                <li key={d.id} className="flex flex-wrap items-center justify-between gap-2 px-4 py-2.5">
                  <div>
                    <button type="button" className="font-semibold hover:text-primary hover:underline" onClick={() => onAction('view', d)}>{d.deliveryNoteNumber}</button>
                    <span className="ml-2 text-xs text-muted-foreground">{d.code} · {d.dispatchedAt ? fmtDate(d.dispatchedAt) : scheduleLabel(d.plannedDate, d.scheduledTime)}</span>
                    <span className="ml-2"><StageBadge stage={d.stage} /></span>
                  </div>
                  <div className="flex gap-2">
                    <Button size="sm" variant="outline" onClick={() => onAction('print', d)}><FileText className="mr-1.5 h-4 w-4" />{tx('Print')}</Button>
                    <Button size="sm" variant="outline" onClick={() => onAction('note', d)}>{tx('PDF')}</Button>
                  </div>
                </li>
              ))}
            </ul>
          ) : (
            <div className="flex flex-col items-center justify-center gap-2 rounded-xl border border-dashed py-10 text-center text-sm text-muted-foreground">
              <FileText className="h-6 w-6" />{tx('Each delivery gets a numbered delivery note when it is dispatched.')}
            </div>
          )}
        </TabsContent>
        <TabsContent value="returns" className="px-4 pb-4">
          <ReturnsList returns={views} empty={tx('No customer returns on this order.')} />
        </TabsContent>
      </Tabs>
    </Section>
  );
}

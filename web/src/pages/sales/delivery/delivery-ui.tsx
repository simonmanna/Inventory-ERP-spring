import type { ReactNode } from 'react';
import { CalendarClock, CheckCircle2, CircleDashed, Package, PackageCheck, Store, Truck, Warehouse, type LucideIcon } from 'lucide-react';
import { tx } from '@/lib/i18n/tx';
import { cn } from '@/lib/utils';
import type { DeliveryMethod, DeliveryStage, OrderDeliveryStage, PlanMethod } from '@/features/sales/delivery-api';

/** Quantity with up to 3 decimals, grouped. */
export const qty = (v: number | string | null | undefined) =>
  Number(v ?? 0).toLocaleString(undefined, { maximumFractionDigits: 3 });

/** Quantity with its unit, e.g. "40 t". */
export const qtyUnit = (v: number | string | null | undefined, unit: string | null | undefined) =>
  `${qty(v)}${unit ? ` ${unit}` : ''}`;

export const n = (v: unknown) => Number(v ?? 0);

export const METHOD_META: Record<DeliveryMethod, { label: string; hint: string; icon: LucideIcon }> = {
  customer_collect: { label: tx('Customer collect'), hint: tx('Customer picks up'), icon: Store },
  site_delivery: { label: tx('Delivery to site'), hint: tx('We deliver to customer'), icon: Truck },
  warehouse_transfer: { label: tx('Transfer to warehouse'), hint: tx('Ship to another warehouse'), icon: Warehouse },
  courier: { label: tx('Courier / logistics'), hint: tx('Third-party courier'), icon: Package },
};

/** Order-level plan method ↔ delivery method. */
export const PLAN_TO_METHOD: Record<PlanMethod, DeliveryMethod> = {
  pickup: 'customer_collect', delivery: 'site_delivery', transfer: 'warehouse_transfer', courier: 'courier',
};
export const METHOD_TO_PLAN: Record<DeliveryMethod, PlanMethod> = {
  customer_collect: 'pickup', site_delivery: 'delivery', warehouse_transfer: 'transfer', courier: 'courier',
};

export const STAGE_META: Record<DeliveryStage, { label: string; className: string; dot: string }> = {
  scheduled: { label: tx('Scheduled'), className: 'border-slate-200 bg-slate-50 text-slate-700 dark:border-slate-700 dark:bg-slate-800/40 dark:text-slate-200', dot: 'bg-slate-400' },
  dispatched: { label: tx('Dispatched'), className: 'border-blue-200 bg-blue-50 text-blue-700 dark:border-blue-900 dark:bg-blue-950/40 dark:text-blue-300', dot: 'bg-blue-500' },
  in_transit: { label: tx('In transit'), className: 'border-indigo-200 bg-indigo-50 text-indigo-700 dark:border-indigo-900 dark:bg-indigo-950/40 dark:text-indigo-300', dot: 'bg-indigo-500' },
  partially_delivered: { label: tx('Partially delivered'), className: 'border-amber-200 bg-amber-50 text-amber-800 dark:border-amber-900 dark:bg-amber-950/40 dark:text-amber-300', dot: 'bg-amber-500' },
  delivered: { label: tx('Delivered'), className: 'border-emerald-200 bg-emerald-50 text-emerald-700 dark:border-emerald-900 dark:bg-emerald-950/40 dark:text-emerald-300', dot: 'bg-emerald-500' },
  failed: { label: tx('Failed'), className: 'border-rose-200 bg-rose-50 text-rose-700 dark:border-rose-900 dark:bg-rose-950/40 dark:text-rose-300', dot: 'bg-rose-500' },
  returned: { label: tx('Returned'), className: 'border-orange-200 bg-orange-50 text-orange-700 dark:border-orange-900 dark:bg-orange-950/40 dark:text-orange-300', dot: 'bg-orange-500' },
  cancelled: { label: tx('Cancelled'), className: 'border-zinc-200 bg-zinc-50 text-zinc-500 line-through decoration-zinc-400/60 dark:border-zinc-700 dark:bg-zinc-800/40', dot: 'bg-zinc-400' },
};

export function StageBadge({ stage, className }: { stage: DeliveryStage; className?: string }) {
  const m = STAGE_META[stage] ?? STAGE_META.scheduled;
  return (
    <span className={cn('inline-flex items-center gap-1.5 whitespace-nowrap rounded-full border px-2 py-0.5 text-[11px] font-medium', m.className, className)}>
      <span className={cn('h-1.5 w-1.5 rounded-full', m.dot)} />{m.label}
    </span>
  );
}

const ORDER_STEPS: Array<{ key: OrderDeliveryStage; label: string; icon: LucideIcon }> = [
  { key: 'pending', label: tx('Pending'), icon: CircleDashed },
  { key: 'scheduled', label: tx('Scheduled'), icon: CalendarClock },
  { key: 'dispatched', label: tx('Dispatched'), icon: Truck },
  { key: 'partially_delivered', label: tx('Partially delivered'), icon: PackageCheck },
  { key: 'delivered', label: tx('Delivered'), icon: CheckCircle2 },
];

/** Order delivery lifecycle: Pending → Scheduled → Dispatched → Partially delivered → Delivered. */
export function OrderStageStepper({ stage }: { stage: OrderDeliveryStage }) {
  const at = ORDER_STEPS.findIndex((s) => s.key === stage);
  return (
    <ol className="flex flex-wrap items-center gap-2" aria-label={tx('Delivery status')}>
      {ORDER_STEPS.map((s, i) => {
        const Icon = s.icon;
        const done = i < at || stage === 'delivered';
        const current = i === at;
        return (
          <li key={s.key} aria-current={current ? 'step' : undefined}
            className={cn('flex items-center gap-2 rounded-full border px-3.5 py-1.5 text-sm transition-colors',
              current && stage !== 'delivered' && 'border-primary bg-primary text-primary-foreground shadow-sm',
              current && stage === 'delivered' && 'border-emerald-600 bg-emerald-600 text-white shadow-sm',
              !current && done && 'border-emerald-200 bg-emerald-50 text-emerald-800 dark:border-emerald-900 dark:bg-emerald-950/30 dark:text-emerald-300',
              !current && !done && 'bg-card text-muted-foreground')}>
            <Icon className="h-4 w-4" />
            <span className="font-medium">{s.label}</span>
          </li>
        );
      })}
    </ol>
  );
}

/** A titled card section. */
export function Section({ icon: Icon, title, actions, children, className, bodyClassName }: {
  icon?: LucideIcon; title: ReactNode; actions?: ReactNode; children: ReactNode; className?: string; bodyClassName?: string;
}) {
  return (
    <section className={cn('rounded-xl border bg-card shadow-sm', className)}>
      <header className="flex flex-wrap items-center justify-between gap-2 border-b px-4 py-3">
        <h3 className="flex items-center gap-2 text-sm font-semibold">{Icon && <Icon className="h-4 w-4 text-primary" />}{title}</h3>
        {actions && <div className="flex flex-wrap items-center gap-2">{actions}</div>}
      </header>
      <div className={cn('p-4', bodyClassName)}>{children}</div>
    </section>
  );
}

/** KPI tile of the delivery overview. */
export function Kpi({ label, value, hint, tone = 'default', icon: Icon }: {
  label: string; value: ReactNode; hint?: ReactNode; tone?: 'default' | 'blue' | 'indigo' | 'green' | 'amber' | 'slate'; icon: LucideIcon;
}) {
  const tones = {
    default: 'text-foreground bg-muted', blue: 'text-blue-600 bg-blue-50 dark:bg-blue-950/40', indigo: 'text-indigo-600 bg-indigo-50 dark:bg-indigo-950/40',
    green: 'text-emerald-600 bg-emerald-50 dark:bg-emerald-950/40', amber: 'text-amber-600 bg-amber-50 dark:bg-amber-950/40', slate: 'text-slate-600 bg-slate-100 dark:bg-slate-800/50',
  } as const;
  return (
    <div className="flex items-start gap-3 rounded-xl border bg-card p-3 shadow-sm">
      <span className={cn('flex h-9 w-9 shrink-0 items-center justify-center rounded-lg', tones[tone])}><Icon className="h-4 w-4" /></span>
      <div className="min-w-0">
        <p className="text-[11px] font-medium uppercase tracking-wide text-muted-foreground">{label}</p>
        <p className="truncate text-lg font-semibold tabular-nums leading-tight">{value}</p>
        {hint && <p className="truncate text-[11px] text-muted-foreground">{hint}</p>}
      </div>
    </div>
  );
}

/** Thin progress bar, 0–100. */
export function Progress({ value, className, tone = 'green' }: { value: number; className?: string; tone?: 'green' | 'blue' }) {
  const v = Math.max(0, Math.min(100, value));
  return (
    <div className={cn('h-2 w-full overflow-hidden rounded-full bg-muted', className)} role="progressbar" aria-valuenow={v} aria-valuemin={0} aria-valuemax={100}>
      <div className={cn('h-full rounded-full transition-all', tone === 'green' ? 'bg-emerald-500' : 'bg-primary')} style={{ width: `${v}%` }} />
    </div>
  );
}

/** Stacked bar of one order line: delivered / dispatched / scheduled / open, as a share of the ordered quantity. */
export function LineBar({ ordered, delivered, dispatched, scheduled }: { ordered: number; delivered: number; dispatched: number; scheduled: number }) {
  const pct = (v: number) => (ordered > 0 ? Math.max(0, Math.min(100, (v / ordered) * 100)) : 0);
  return (
    <div className="flex h-2 w-full overflow-hidden rounded-full bg-muted">
      <div className="h-full bg-emerald-500" style={{ width: `${pct(delivered)}%` }} title={tx('Delivered')} />
      <div className="h-full bg-blue-500" style={{ width: `${pct(dispatched)}%` }} title={tx('Dispatched')} />
      <div className="h-full bg-slate-300 dark:bg-slate-600" style={{ width: `${pct(scheduled)}%` }} title={tx('Scheduled')} />
    </div>
  );
}

/** Label / value pair of a details grid. */
export function Detail({ label, children, className }: { label: string; children: ReactNode; className?: string }) {
  return (
    <div className={cn('min-w-0', className)}>
      <dt className="text-[11px] font-medium uppercase tracking-wide text-muted-foreground">{label}</dt>
      <dd className="mt-0.5 break-words text-sm">{children ?? '—'}</dd>
    </div>
  );
}

/** `2026-11-13` + `10:00` → readable local string. */
export function scheduleLabel(date: string | null, time: string | null) {
  if (!date) return '—';
  const d = new Date(`${date.slice(0, 10)}T${time ?? '00:00'}:00`);
  const day = d.toLocaleDateString(undefined, { day: '2-digit', month: 'short', year: 'numeric' });
  return time ? `${day}, ${d.toLocaleTimeString(undefined, { hour: '2-digit', minute: '2-digit' })}` : day;
}

/** ISO instant → value of a `datetime-local` input (local time). */
export function toLocalInput(iso: string | null | undefined) {
  if (!iso) return '';
  const d = new Date(iso);
  const pad = (x: number) => String(x).padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

/** Value of a `datetime-local` input → ISO instant (or null). */
export const fromLocalInput = (v: string) => (v ? new Date(v).toISOString() : null);

export const REASON_OPTIONS = [
  ['site_closed', tx('Site closed / no access')], ['customer_rejected', tx('Customer rejected')], ['damaged', tx('Damaged')],
  ['spillage', tx('Spillage / loss in transit')], ['weighing_variance', tx('Weighing variance')], ['wrong_product', tx('Wrong product')],
  ['theft', tx('Theft')], ['other', tx('Other')],
] as const;

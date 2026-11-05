import type { ReactNode } from 'react';
import { tx } from '@/lib/i18n/tx';
import { cn } from '@/lib/utils';
import type { DeliveryStatus, InvoiceStatus, SalesOrderStatus } from '@/features/sales/api';

export const ORDER_STATUS_LABEL: Record<SalesOrderStatus, string> = {
  draft: tx('Draft'), pending_approval: tx('Pending approval'), confirmed: tx('Confirmed'),
  in_fulfilment: tx('In fulfilment'), done: tx('Done'), cancelled: tx('Cancelled'),
};
const ORDER_TONE: Record<SalesOrderStatus, string> = {
  draft: 'bg-slate-100 text-slate-700', pending_approval: 'bg-amber-100 text-amber-800', confirmed: 'bg-sky-100 text-sky-800',
  in_fulfilment: 'bg-indigo-100 text-indigo-800', done: 'bg-emerald-100 text-emerald-800', cancelled: 'bg-rose-100 text-rose-800',
};
export const DELIVERY_LABEL: Record<DeliveryStatus, string> = { none: tx('Not delivered'), partial: tx('Partially delivered'), full: tx('Delivered') };
export const INVOICE_LABEL: Record<InvoiceStatus, string> = { nothing: tx('Nothing to invoice'), to_invoice: tx('To invoice'), partial: tx('Partially invoiced'), invoiced: tx('Fully invoiced') };

export function OrderStatusPill({ status, onHold }: { status: SalesOrderStatus; onHold?: boolean }) {
  return (
    <span className="inline-flex items-center gap-1">
      <span className={cn('rounded-full px-2 py-0.5 text-xs font-medium dark:bg-opacity-20', ORDER_TONE[status])}>{ORDER_STATUS_LABEL[status]}</span>
      {onHold && <span className="rounded-full bg-rose-600 px-2 py-0.5 text-xs font-medium text-white">{tx('On hold')}</span>}
    </span>
  );
}

/** Odoo-style status ribbon: Quotation → Order → Delivered → Invoiced. */
export function StatusRibbon({ steps, current }: { steps: string[]; current: number }) {
  return (
    <ol className="flex overflow-hidden rounded-lg border text-xs">
      {steps.map((s, i) => (
        <li key={s} className={cn('flex-1 px-3 py-1.5 text-center', i < current ? 'bg-primary/10 text-primary' : i === current ? 'bg-primary font-semibold text-primary-foreground' : 'bg-card text-muted-foreground', i > 0 && 'border-l')}>{s}</li>
      ))}
    </ol>
  );
}

/** Smart button: count + label linking to related documents. */
export function SmartButton({ icon, count, label, onClick, disabled }: { icon: ReactNode; count: number | string; label: string; onClick?: () => void; disabled?: boolean }) {
  return (
    <button type="button" disabled={disabled} onClick={onClick} className="flex min-w-[120px] items-center gap-2 rounded-lg border bg-card px-3 py-2 text-left text-sm shadow-sm hover:bg-muted disabled:cursor-default disabled:opacity-60">
      <span className="text-muted-foreground">{icon}</span>
      <span><span className="block text-base font-semibold leading-none">{count}</span><span className="text-xs text-muted-foreground">{label}</span></span>
    </button>
  );
}

/** Odoo-style header field: label on the left, value beside it on the same row. */
export function InlineField({ label, children, className }: { label: string; children: ReactNode; className?: string }) {
  return (
    <div className={cn('grid grid-cols-[8.5rem_minmax(0,1fr)] items-baseline gap-x-4', className)}>
      <span className="text-sm font-medium text-muted-foreground">{label}</span>
      <div className="min-w-0 text-sm">{children}</div>
    </div>
  );
}

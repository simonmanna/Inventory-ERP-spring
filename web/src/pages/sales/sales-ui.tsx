import type { ReactNode } from 'react';
import { tx } from '@/lib/i18n/tx';
import { cn } from '@/lib/utils';
import type { DeliveryStatus, InvoiceStatus, SalesApprovalStatus, SalesOrderStage } from '@/features/sales/api';

export const ORDER_STATUS_LABEL: Record<SalesOrderStage, string> = {
  draft: tx('Draft'), pending_approval: tx('Pending approval'), confirmed: tx('Confirmed'),
  in_fulfilment: tx('In fulfilment'), done: tx('Done'), cancelled: tx('Cancelled'),
};
const ORDER_TONE: Record<SalesOrderStage, string> = {
  draft: 'bg-slate-100 text-slate-700', pending_approval: 'bg-amber-100 text-amber-800', confirmed: 'bg-sky-100 text-sky-800',
  in_fulfilment: 'bg-indigo-100 text-indigo-800', done: 'bg-emerald-100 text-emerald-800', cancelled: 'bg-rose-100 text-rose-800',
};
export const DELIVERY_LABEL: Record<DeliveryStatus, string> = { none: tx('Not delivered'), partial: tx('Partially delivered'), full: tx('Delivered') };
export const INVOICE_LABEL: Record<InvoiceStatus, string> = { nothing: tx('Nothing to invoice'), to_invoice: tx('To invoice'), partial: tx('Partially invoiced'), invoiced: tx('Fully invoiced') };

const APPROVAL_CHIP: Partial<Record<SalesApprovalStatus, [string, string]>> = {
  required: [tx('Needs approval'), 'border border-amber-300 text-amber-800 dark:text-amber-300'],
  approved: [tx('Approved'), 'border border-emerald-300 text-emerald-800 dark:text-emerald-300'],
  rejected: [tx('Rejected'), 'border border-rose-300 text-rose-800 dark:text-rose-300'],
};

/** A sale's status: the stage (ADR-S094 §4.6) and, on a draft, where its approval stands (§4.2). */
export function OrderStatusPill({ status, approval, onHold }: { status: SalesOrderStage; approval?: SalesApprovalStatus; onHold?: boolean }) {
  const chip = status === 'draft' && approval ? APPROVAL_CHIP[approval] : undefined;
  return (
    <span className="inline-flex items-center gap-1">
      <span className={cn('rounded-full px-2 py-0.5 text-xs font-medium dark:bg-opacity-20', ORDER_TONE[status] ?? ORDER_TONE.draft)}>{ORDER_STATUS_LABEL[status] ?? status}</span>
      {chip && <span className={cn('rounded-full px-2 py-0.5 text-xs font-medium', chip[1])}>{chip[0]}</span>}
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
    <div className={cn('grid grid-cols-[9rem_minmax(0,1fr)] min-h-12 items-center gap-x-3 py-1.5', className)}>
      <span className="text-sm font-semibold">{label}</span>
      <div className="min-w-0 text-sm">{children}</div>
    </div>
  );
}

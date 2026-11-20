import { tx } from '@/lib/i18n/tx';
import { cn } from '@/lib/utils';

export const PO_STATUS_LABELS: Record<string, string> = {
  active: tx('Active'), partially_received: tx('Partially Received'),
  received: tx('Received'), cancelled: tx('Cancelled'),
};

export const PO_PAYMENT_STATUS_LABELS: Record<string, string> = {
  not_paid: tx('Unpaid'), paid: tx('Paid'), partial: tx('Partial'),
};

const STATUS_TONE: Record<string, string> = {
  active: 'bg-sky-100 text-sky-800', partially_received: 'bg-indigo-100 text-indigo-800',
  received: 'bg-emerald-100 text-emerald-800', cancelled: 'bg-rose-100 text-rose-800',
};

/** Purchase order status pill, in the tones of the sales order pill. */
export function PoStatusPill({ status }: { status: string }) {
  return (
    <span className={cn('rounded-full px-2 py-0.5 text-xs font-medium dark:bg-opacity-20', STATUS_TONE[status] ?? 'bg-slate-100 text-slate-700')}>
      {PO_STATUS_LABELS[status] ?? status}
    </span>
  );
}

/** Cash / Credit tag. */
export function PaymentTypeTag({ type }: { type: string }) {
  const cash = type === 'cash';
  return (
    <span className={cn('rounded-full border px-2 py-0.5 text-xs font-medium', cash ? 'border-yellow-400 text-yellow-700' : 'border-blue-400 text-blue-700')}>
      {cash ? tx('Cash') : tx('Credit')}
    </span>
  );
}

export const GRN_STATUS_LABELS: Record<string, string> = {
  draft: tx('Draft'), posted: tx('Posted'), cancelled: tx('Cancelled'), reversed: tx('Reversed'),
};

const GRN_TONE: Record<string, string> = {
  draft: 'bg-slate-100 text-slate-700', posted: 'bg-emerald-100 text-emerald-800',
  cancelled: 'bg-rose-100 text-rose-800', reversed: 'bg-amber-100 text-amber-800',
};

/** Goods receipt status pill. */
export function GrnStatusPill({ status }: { status: string }) {
  return (
    <span className={cn('rounded-full px-2 py-0.5 text-xs font-medium dark:bg-opacity-20', GRN_TONE[status] ?? 'bg-slate-100 text-slate-700')}>
      {GRN_STATUS_LABELS[status] ?? status}
    </span>
  );
}

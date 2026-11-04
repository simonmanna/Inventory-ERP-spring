import { cn } from '@/lib/utils';
import {
  EXECUTION_LABEL, FISCAL_LABEL, PROCESSING_LABEL,
  type ExecutionStatus, type FiscalStatus, type ProcessingStatus,
} from '@/features/sales/cash-sales-api';

const EXEC_TONE: Record<ExecutionStatus, string> = {
  draft: 'bg-slate-100 text-slate-700 dark:bg-slate-800 dark:text-slate-200',
  confirmed: 'bg-emerald-100 text-emerald-800 dark:bg-emerald-900/40 dark:text-emerald-200',
  voided: 'bg-rose-100 text-rose-800 dark:bg-rose-900/40 dark:text-rose-200',
  cancelled: 'bg-slate-100 text-slate-500 dark:bg-slate-800 dark:text-slate-400',
};
const PROC_TONE: Record<ProcessingStatus, string> = {
  not_submitted: 'bg-slate-100 text-slate-600 dark:bg-slate-800 dark:text-slate-300',
  submitted: 'bg-amber-100 text-amber-800 dark:bg-amber-900/40 dark:text-amber-200',
  under_review: 'bg-amber-100 text-amber-800 dark:bg-amber-900/40 dark:text-amber-200',
  returned_for_correction: 'bg-orange-100 text-orange-800 dark:bg-orange-900/40 dark:text-orange-200',
  approved: 'bg-sky-100 text-sky-800 dark:bg-sky-900/40 dark:text-sky-200',
  invoiced: 'bg-emerald-100 text-emerald-800 dark:bg-emerald-900/40 dark:text-emerald-200',
  rejected: 'bg-rose-100 text-rose-800 dark:bg-rose-900/40 dark:text-rose-200',
};

const pill = 'inline-flex items-center rounded-full px-2 py-0.5 text-xs font-medium';

export function ExecutionPill({ status, className }: { status: ExecutionStatus; className?: string }) {
  return <span className={cn(pill, EXEC_TONE[status], className)}>{EXECUTION_LABEL[status]}</span>;
}

export function ProcessingPill({ status, className }: { status: ProcessingStatus; className?: string }) {
  return <span className={cn(pill, PROC_TONE[status], className)}>{PROCESSING_LABEL[status]}</span>;
}

export function FiscalPill({ status, className }: { status: FiscalStatus; className?: string }) {
  const tone = status === 'issued' ? 'bg-emerald-50 text-emerald-700' : status === 'failed' || status === 'cancellation_pending'
    ? 'bg-rose-50 text-rose-700' : 'bg-slate-50 text-slate-600';
  return <span className={cn(pill, 'border', tone, className)}>{FISCAL_LABEL[status]}</span>;
}

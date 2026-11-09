import type { ReactNode } from 'react';
import { cn } from '@/lib/utils';
import { tx } from '@/lib/i18n/tx';
import { selectClass } from '@/features/projects/ui';
import type { CostElement, FulfilmentClass, WbsElement } from '@/features/projects/costing-api';

export const COST_ELEMENT_LABEL: Record<CostElement, string> = {
  material: tx('Material'),
  labour: tx('Labour'),
  equipment: tx('Equipment'),
  subcontract: tx('Subcontract'),
  other: tx('Other'),
  overhead: tx('Overhead'),
};

export const FULFILMENT_LABEL: Record<FulfilmentClass, string> = {
  incremental: tx('Incremental'),
  directly_related: tx('Directly related'),
  excluded: tx('Excluded'),
};

export const SOURCE_LABEL: Record<string, string> = {
  project_issue: tx('Stock issue'),
  project_timesheet: tx('Timesheets'),
  project_equipment: tx('Equipment'),
  project_reclass: tx('Reclass'),
};

export const costElementLabel = (e: string) => COST_ELEMENT_LABEL[e as CostElement] ?? e;

export function wbsLabel(wbs: WbsElement[], id: string | null | undefined, code?: string | null): string {
  const w = wbs.find((x) => x.id === id);
  if (w) return `${w.code} · ${w.name}`;
  return code ?? '—';
}

/** Numeric value of a decimal string for display decisions only (sign, colour) — never for totals sent back. */
export const num = (v: unknown) => {
  const n = Number(v ?? 0);
  return Number.isFinite(n) ? n : 0;
};

export function WbsSelect({ wbs, value, onChange, costableOnly, allowEmpty, className }: {
  wbs: WbsElement[];
  value: string;
  onChange: (v: string) => void;
  costableOnly?: boolean;
  allowEmpty?: string;
  className?: string;
}) {
  const options = costableOnly ? wbs.filter((w) => w.isCostable) : wbs;
  return (
    <select className={cn(selectClass, className)} value={value} onChange={(e) => onChange(e.target.value)}>
      <option value="">{allowEmpty ?? tx('Select a WBS element…')}</option>
      {options.map((w) => <option key={w.id} value={w.id}>{w.code} · {w.name}</option>)}
    </select>
  );
}

export function CostElementSelect({ value, onChange, className }: { value: string; onChange: (v: string) => void; className?: string }) {
  return (
    <select className={cn(selectClass, className)} value={value} onChange={(e) => onChange(e.target.value)}>
      {Object.entries(COST_ELEMENT_LABEL).map(([k, label]) => <option key={k} value={k}>{label}</option>)}
    </select>
  );
}

export function Field({ label, hint, children, className }: { label: string; hint?: string; children: ReactNode; className?: string }) {
  return (
    <label className={cn('block space-y-1 text-sm', className)}>
      <span className="font-medium">{label}</span>
      {children}
      {hint && <span className="block text-xs text-muted-foreground">{hint}</span>}
    </label>
  );
}

export function Section({ title, actions, children }: { title: string; actions?: ReactNode; children: ReactNode }) {
  return (
    <section className="rounded-xl border bg-card p-4 shadow-sm">
      <div className="mb-3 flex flex-wrap items-center justify-between gap-2">
        <h3 className="text-sm font-semibold">{title}</h3>
        {actions && <div className="flex flex-wrap items-center gap-2">{actions}</div>}
      </div>
      {children}
    </section>
  );
}

const STATUS_TONE: Record<string, string> = {
  draft: 'bg-slate-100 text-slate-700 dark:bg-slate-500/20 dark:text-slate-300',
  approved: 'bg-emerald-100 text-emerald-700 dark:bg-emerald-500/20 dark:text-emerald-300',
  superseded: 'bg-muted text-muted-foreground',
  posted: 'bg-emerald-100 text-emerald-700 dark:bg-emerald-500/20 dark:text-emerald-300',
  reversed: 'bg-rose-100 text-rose-700 dark:bg-rose-500/20 dark:text-rose-300',
};

const STATUS_LABEL: Record<string, string> = {
  draft: tx('Draft'),
  approved: tx('Approved'),
  superseded: tx('Superseded'),
  posted: tx('Posted'),
  reversed: tx('Reversed'),
};

export function StatusChip({ status }: { status: string }) {
  return (
    <span className={cn('inline-flex rounded px-1.5 py-0.5 text-xs font-medium', STATUS_TONE[status] ?? 'bg-muted text-muted-foreground')}>
      {STATUS_LABEL[status] ?? status}
    </span>
  );
}

export const thClass = 'px-3 py-2 text-left text-xs font-medium text-muted-foreground';
export const tdClass = 'px-3 py-2';
export const numCell = 'px-3 py-2 text-right tabular-nums';

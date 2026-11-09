import type { ReactNode } from 'react';
import { Button } from '@/components/ui/button';
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { Label } from '@/components/ui/label';
import { money } from '@/lib/format';
import { cn } from '@/lib/utils';
import { tx } from '@/lib/i18n/tx';
import type { Num } from '@/features/projects/api';

/** Contract permissions of the signed-in user (view / draft / post / approve). */
export interface ContractPerms { read: boolean; manage: boolean; post: boolean; approve: boolean }

export const num = (v: Num | null | undefined) => (v == null || v === '' ? 0 : Number(v));

export function Money({ value, className }: { value: Num | null | undefined; className?: string }) {
  return <span className={cn('tabular-nums', num(value) < 0 && 'text-rose-600', className)}>{value == null ? '—' : money(value)}</span>;
}

export const pct = (v: Num | null | undefined, digits = 2) => `${num(v).toFixed(digits).replace(/\.?0+$/, '')}%`;

const TONES: Record<string, string> = {
  draft: 'bg-slate-100 text-slate-700 dark:bg-slate-500/20 dark:text-slate-300',
  pending: 'bg-amber-100 text-amber-700 dark:bg-amber-500/20 dark:text-amber-300',
  open: 'bg-amber-100 text-amber-700 dark:bg-amber-500/20 dark:text-amber-300',
  held: 'bg-amber-100 text-amber-700 dark:bg-amber-500/20 dark:text-amber-300',
  approved: 'bg-sky-100 text-sky-700 dark:bg-sky-500/20 dark:text-sky-300',
  active: 'bg-sky-100 text-sky-700 dark:bg-sky-500/20 dark:text-sky-300',
  certified: 'bg-emerald-100 text-emerald-700 dark:bg-emerald-500/20 dark:text-emerald-300',
  posted: 'bg-emerald-100 text-emerald-700 dark:bg-emerald-500/20 dark:text-emerald-300',
  released: 'bg-emerald-100 text-emerald-700 dark:bg-emerald-500/20 dark:text-emerald-300',
  recovered: 'bg-emerald-100 text-emerald-700 dark:bg-emerald-500/20 dark:text-emerald-300',
  completed: 'bg-emerald-100 text-emerald-700 dark:bg-emerald-500/20 dark:text-emerald-300',
  rejected: 'bg-rose-100 text-rose-700 dark:bg-rose-500/20 dark:text-rose-300',
  cancelled: 'bg-rose-100 text-rose-700 dark:bg-rose-500/20 dark:text-rose-300',
  reversed: 'bg-rose-100 text-rose-700 dark:bg-rose-500/20 dark:text-rose-300',
};

const STATUS_LABELS = (): Record<string, string> => ({
  draft: tx('Draft'), pending: tx('Pending'), open: tx('Open'), held: tx('Held'), approved: tx('Approved'),
  active: tx('Active'), certified: tx('Certified'), posted: tx('Posted'), released: tx('Released'),
  recovered: tx('Recovered'), completed: tx('Completed'), rejected: tx('Rejected'), cancelled: tx('Cancelled'), reversed: tx('Reversed'),
});

export function StatusPill({ status }: { status: string }) {
  return (
    <span className={cn('inline-flex rounded px-1.5 py-0.5 text-xs font-medium', TONES[status] ?? TONES.draft)}>
      {STATUS_LABELS()[status] ?? status}
    </span>
  );
}

/** Section card: title, hint and an action button on the right, then the body. */
export function Section({ title, hint, action, children }: { title: string; hint?: string; action?: ReactNode; children: ReactNode }) {
  return (
    <section className="rounded-xl border bg-card shadow-sm">
      <div className="flex flex-wrap items-start justify-between gap-2 border-b px-4 py-3">
        <div>
          <h3 className="text-sm font-semibold">{title}</h3>
          {hint && <p className="text-xs text-muted-foreground">{hint}</p>}
        </div>
        {action}
      </div>
      <div className="p-4">{children}</div>
    </section>
  );
}

export function Empty({ children }: { children: ReactNode }) {
  return <p className="py-6 text-center text-sm text-muted-foreground">{children}</p>;
}

/** A scrollable table with the shared header style. */
export function Table({ head, children }: { head: Array<string | { label: string; right?: boolean }>; children: ReactNode }) {
  return (
    <div className="overflow-x-auto">
      <table className="w-full text-sm">
        <thead>
          <tr className="border-b text-left text-xs uppercase tracking-wide text-muted-foreground">
            {head.map((h, i) => {
              const c = typeof h === 'string' ? { label: h } : h;
              return <th key={i} className={cn('px-2 py-2 font-medium', c.right && 'text-right')}>{c.label}</th>;
            })}
          </tr>
        </thead>
        <tbody className="divide-y">{children}</tbody>
      </table>
    </div>
  );
}

export const td = 'px-2 py-2 align-top';
export const tdRight = 'px-2 py-2 text-right align-top tabular-nums';

export function Field({ label, hint, children, className }: { label: string; hint?: string; children: ReactNode; className?: string }) {
  return (
    <div className={cn('space-y-1', className)}>
      <Label className="text-xs">{label}</Label>
      {children}
      {hint && <p className="text-xs text-muted-foreground">{hint}</p>}
    </div>
  );
}

/** A create / action form in a dialog; submit is disabled while invalid or pending. */
export function FormDialog({
  open, onClose, title, description, onSubmit, submitLabel, valid = true, pending, children,
}: {
  open: boolean;
  onClose: () => void;
  title: string;
  description?: string;
  onSubmit: () => void;
  submitLabel: string;
  valid?: boolean;
  pending?: boolean;
  children: ReactNode;
}) {
  return (
    <Dialog open={open} onOpenChange={(o) => !o && onClose()}>
      <DialogContent className="max-h-[92vh] max-w-xl overflow-y-auto">
        <form onSubmit={(e) => { e.preventDefault(); if (valid && !pending) onSubmit(); }} className="space-y-4">
          <DialogHeader>
            <DialogTitle>{title}</DialogTitle>
            {description && <DialogDescription>{description}</DialogDescription>}
          </DialogHeader>
          <div className="grid gap-3 sm:grid-cols-2">{children}</div>
          <DialogFooter>
            <Button type="button" variant="outline" onClick={onClose}>{tx('Cancel')}</Button>
            <Button type="submit" disabled={!valid || pending}>{submitLabel}</Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  );
}

export const isAmount = (v: string) => v.trim() !== '' && !Number.isNaN(Number(v));

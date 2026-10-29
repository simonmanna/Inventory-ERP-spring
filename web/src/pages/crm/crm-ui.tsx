import type { ReactNode } from 'react';
import { CalendarClock, CheckSquare, Clock, Mail, PhoneCall, StickyNote, Star, Users } from 'lucide-react';
import { Label } from '@/components/ui/label';
import { StatusPill, type Tone } from '@/components/list';
import { cn } from '@/lib/utils';
import { tx } from '@/lib/i18n/tx';
import {
  ACTIVITY_TYPE_LABEL, QUOTE_STATUS_LABEL, type ActivityState, type Color, type PlannedType, type QuoteStatus, type Tag,
} from '@/features/crm/suite-api';

/** Shared bits of the CRM sales-suite screens (ADR-S010). */

export const COLOR_CLASS: Record<Color, { dot: string; chip: string; bar: string }> = {
  slate: { dot: 'bg-slate-400', chip: 'bg-slate-100 text-slate-700 dark:bg-slate-500/20 dark:text-slate-200', bar: 'bg-slate-400' },
  sky: { dot: 'bg-sky-500', chip: 'bg-sky-100 text-sky-800 dark:bg-sky-500/20 dark:text-sky-200', bar: 'bg-sky-500' },
  blue: { dot: 'bg-blue-500', chip: 'bg-blue-100 text-blue-800 dark:bg-blue-500/20 dark:text-blue-200', bar: 'bg-blue-500' },
  indigo: { dot: 'bg-indigo-500', chip: 'bg-indigo-100 text-indigo-800 dark:bg-indigo-500/20 dark:text-indigo-200', bar: 'bg-indigo-500' },
  violet: { dot: 'bg-violet-500', chip: 'bg-violet-100 text-violet-800 dark:bg-violet-500/20 dark:text-violet-200', bar: 'bg-violet-500' },
  pink: { dot: 'bg-pink-500', chip: 'bg-pink-100 text-pink-800 dark:bg-pink-500/20 dark:text-pink-200', bar: 'bg-pink-500' },
  rose: { dot: 'bg-rose-500', chip: 'bg-rose-100 text-rose-800 dark:bg-rose-500/20 dark:text-rose-200', bar: 'bg-rose-500' },
  orange: { dot: 'bg-orange-500', chip: 'bg-orange-100 text-orange-800 dark:bg-orange-500/20 dark:text-orange-200', bar: 'bg-orange-500' },
  amber: { dot: 'bg-amber-500', chip: 'bg-amber-100 text-amber-800 dark:bg-amber-500/20 dark:text-amber-200', bar: 'bg-amber-500' },
  lime: { dot: 'bg-lime-500', chip: 'bg-lime-100 text-lime-800 dark:bg-lime-500/20 dark:text-lime-200', bar: 'bg-lime-500' },
  emerald: { dot: 'bg-emerald-500', chip: 'bg-emerald-100 text-emerald-800 dark:bg-emerald-500/20 dark:text-emerald-200', bar: 'bg-emerald-500' },
  teal: { dot: 'bg-teal-500', chip: 'bg-teal-100 text-teal-800 dark:bg-teal-500/20 dark:text-teal-200', bar: 'bg-teal-500' },
};

export function colorOf(c: string | null | undefined) {
  return COLOR_CLASS[(c ?? 'slate') as Color] ?? COLOR_CLASS.slate;
}

/** Odoo-style 0–3 star priority; clickable when `onChange` is set (click the current star again to clear). */
export function Stars({ value, onChange, size = 'sm' }: { value: number; onChange?: (v: number) => void; size?: 'sm' | 'md' }) {
  const cls = size === 'md' ? 'h-5 w-5' : 'h-4 w-4';
  return (
    <span className="inline-flex items-center" onClick={(e) => onChange && e.stopPropagation()}>
      {[1, 2, 3].map((n) => (
        <button
          key={n}
          type="button"
          disabled={!onChange}
          aria-label={tx('Priority') + ' ' + n}
          onPointerDown={(e) => onChange && e.stopPropagation()}
          onClick={(e) => { e.stopPropagation(); onChange?.(value === n ? n - 1 : n); }}
          className={cn('p-0.5', onChange ? 'cursor-pointer hover:scale-110' : 'cursor-default')}
        >
          <Star className={cn(cls, n <= value ? 'fill-amber-400 text-amber-400' : 'text-muted-foreground/40')} />
        </button>
      ))}
    </span>
  );
}

export const TYPE_ICON: Record<string, typeof PhoneCall> = {
  call: PhoneCall,
  email: Mail,
  meeting: Users,
  task: CheckSquare,
  note: StickyNote,
};

const STATE_COLOR: Record<ActivityState, string> = {
  overdue: 'text-rose-600',
  today: 'text-amber-600',
  planned: 'text-emerald-600',
  none: 'text-muted-foreground/50',
};

/** The next-activity icon of a card, coloured by its state (red overdue, orange today, green planned). */
export function ActivityBadge({ state, type, title }: { state: ActivityState; type?: string | null; title?: string | null }) {
  const Icon = state === 'none' ? Clock : TYPE_ICON[type ?? 'task'] ?? CalendarClock;
  return (
    <span title={title ?? (state === 'none' ? tx('No activity planned') : undefined)} className={cn('inline-flex', STATE_COLOR[state])}>
      <Icon className="h-4 w-4" />
    </span>
  );
}

/** Kanban column progress bar: share of cards per activity state. */
export function ActivityBar({ counts, total }: { counts: Record<ActivityState, number>; total: number }) {
  if (!total) return <div className="h-1.5 w-full rounded-full bg-muted" />;
  const seg = (n: number, cls: string, label: string) => n > 0 && (
    <div className={cls} style={{ width: `${(n / total) * 100}%` }} title={`${label}: ${n}`} />
  );
  return (
    <div className="flex h-1.5 w-full overflow-hidden rounded-full bg-muted">
      {seg(counts.planned, 'bg-emerald-500', tx('Planned'))}
      {seg(counts.today, 'bg-amber-500', tx('Today'))}
      {seg(counts.overdue, 'bg-rose-500', tx('Overdue'))}
      {seg(counts.none, 'bg-slate-300 dark:bg-slate-600', tx('No activity'))}
    </div>
  );
}

export function TagChips({ ids, tags, max = 3 }: { ids: string[]; tags: Tag[]; max?: number }) {
  if (!ids?.length) return null;
  const shown = ids.map((id) => tags.find((t) => t.id === id)).filter(Boolean) as Tag[];
  return (
    <div className="flex flex-wrap gap-1">
      {shown.slice(0, max).map((t) => (
        <span key={t.id} className={cn('rounded-full px-2 py-0.5 text-[11px] font-medium', colorOf(t.color).chip)}>{t.name}</span>
      ))}
      {shown.length > max && <span className="text-[11px] text-muted-foreground">+{shown.length - max}</span>}
    </div>
  );
}

export function Avatar({ name, className }: { name?: string | null; className?: string }) {
  const initials = (name ?? '?').split(/\s+/).map((p) => p[0]).filter(Boolean).slice(0, 2).join('').toUpperCase();
  return (
    <span
      title={name ?? tx('Unassigned')}
      className={cn('inline-flex h-6 w-6 shrink-0 items-center justify-center rounded-full bg-primary/15 text-[10px] font-bold text-primary', !name && 'bg-muted text-muted-foreground', className)}
    >
      {name ? initials : '—'}
    </span>
  );
}

const QUOTE_TONE: Record<QuoteStatus, Tone> = {
  draft: 'neutral',
  pending_approval: 'warning',
  approved: 'info',
  sent: 'accent',
  accepted: 'success',
  rejected: 'danger',
  expired: 'neutral',
  cancelled: 'neutral',
  converted: 'success',
};

export function QuoteStatusPill({ status }: { status: QuoteStatus }) {
  return <StatusPill tone={QUOTE_TONE[status] ?? 'neutral'}>{QUOTE_STATUS_LABEL[status] ?? status}</StatusPill>;
}

export function DealStatusPill({ status }: { status: 'open' | 'won' | 'lost' }) {
  const tone: Tone = status === 'won' ? 'success' : status === 'lost' ? 'danger' : 'info';
  const label = status === 'won' ? tx('Won') : status === 'lost' ? tx('Lost') : tx('Open');
  return <StatusPill tone={tone}>{label}</StatusPill>;
}

export function Field({ label, children, className, hint }: { label: string; children: ReactNode; className?: string; hint?: string }) {
  return (
    <div className={cn('space-y-1.5', className)}>
      <Label>{label}</Label>
      {children}
      {hint && <p className="text-xs text-muted-foreground">{hint}</p>}
    </div>
  );
}

export function typeLabel(t: string): string {
  return ACTIVITY_TYPE_LABEL[t as PlannedType] ?? t;
}

/** Compact amount: 12.3k / 1.2M. */
export function compact(n: number | null | undefined): string {
  const v = Number(n ?? 0);
  if (Math.abs(v) >= 1_000_000) return `${(v / 1_000_000).toFixed(1)}M`;
  if (Math.abs(v) >= 10_000) return `${(v / 1000).toFixed(1)}k`;
  return v.toLocaleString(undefined, { maximumFractionDigits: 0 });
}

export function KpiCard({ label, value, sub, tone }: { label: string; value: ReactNode; sub?: ReactNode; tone?: 'danger' | 'success' }) {
  return (
    <div className="rounded-xl border bg-card p-4 shadow-sm">
      <p className="text-xs font-medium uppercase tracking-wide text-muted-foreground">{label}</p>
      <p className={cn('mt-1 text-2xl font-bold tabular-nums', tone === 'danger' && 'text-rose-600', tone === 'success' && 'text-emerald-600')}>{value}</p>
      {sub && <p className="mt-0.5 text-xs text-muted-foreground">{sub}</p>}
    </div>
  );
}

/** Horizontal meter used in report tables. */
export function Meter({ value, max, className }: { value: number; max: number; className?: string }) {
  return (
    <div className="h-2 w-full min-w-[60px] overflow-hidden rounded-full bg-muted">
      <div className={cn('h-full rounded-full bg-primary', className)} style={{ width: `${max ? Math.max(2, (value / max) * 100) : 0}%` }} />
    </div>
  );
}

import {
  Archive, Ban, CheckCircle2, CircleDashed, CirclePause, ClipboardCheck, Clock, FlaskConical, Hourglass, Loader, Moon,
  OctagonAlert, PencilLine, Play, ShieldAlert, ShieldCheck, ShieldX, Sparkles, type LucideIcon,
} from 'lucide-react';
import { tx } from '@/lib/i18n/tx';
import { cn } from '@/lib/utils';

/**
 * One colour + icon + word per manufacturing status, so an order, a work order, a stage and a QC lot read the same on
 * every screen. Colour is never alone: every pill carries an icon and a label.
 */

export type MfgTone = 'neutral' | 'info' | 'progress' | 'warning' | 'success' | 'danger' | 'muted';

export const TONE_PILL: Record<MfgTone, string> = {
  neutral: 'bg-slate-100 text-slate-700 ring-slate-500/25 dark:bg-slate-500/15 dark:text-slate-300',
  info: 'bg-sky-50 text-sky-700 ring-sky-600/25 dark:bg-sky-500/15 dark:text-sky-300',
  progress: 'bg-violet-50 text-violet-700 ring-violet-600/25 dark:bg-violet-500/15 dark:text-violet-300',
  warning: 'bg-amber-50 text-amber-800 ring-amber-600/30 dark:bg-amber-500/15 dark:text-amber-300',
  success: 'bg-emerald-50 text-emerald-700 ring-emerald-600/25 dark:bg-emerald-500/15 dark:text-emerald-300',
  danger: 'bg-rose-50 text-rose-700 ring-rose-600/25 dark:bg-rose-500/15 dark:text-rose-300',
  muted: 'bg-muted text-muted-foreground ring-border',
};

/** Solid fills for bars, rings and dots. */
export const TONE_FILL: Record<MfgTone, string> = {
  neutral: 'bg-slate-400', info: 'bg-sky-500', progress: 'bg-violet-500', warning: 'bg-amber-500',
  success: 'bg-emerald-500', danger: 'bg-rose-500', muted: 'bg-muted-foreground/40',
};
export const TONE_TEXT: Record<MfgTone, string> = {
  neutral: 'text-slate-500 dark:text-slate-400', info: 'text-sky-600 dark:text-sky-400', progress: 'text-violet-600 dark:text-violet-400',
  warning: 'text-amber-600 dark:text-amber-400', success: 'text-emerald-600 dark:text-emerald-400', danger: 'text-rose-600 dark:text-rose-400',
  muted: 'text-muted-foreground',
};
export const TONE_BORDER: Record<MfgTone, string> = {
  neutral: 'border-slate-300 dark:border-slate-600', info: 'border-sky-400/70', progress: 'border-violet-400/70', warning: 'border-amber-400/80',
  success: 'border-emerald-400/70', danger: 'border-rose-400/80', muted: 'border-border',
};
/** Hex for SVG / recharts, readable on the card surface in both themes. */
export const TONE_HEX: Record<MfgTone, string> = {
  neutral: '#94a3b8', info: '#0ea5e9', progress: '#8b5cf6', warning: '#f59e0b', success: '#10b981', danger: '#f43f5e', muted: '#a1a1aa',
};

export type StatusKind = 'order' | 'workOrder' | 'stage' | 'qc' | 'bom' | 'floor' | 'generic';

interface Meta { tone: MfgTone; icon: LucideIcon; label: () => string; spin?: boolean }

const ORDER: Record<string, Meta> = {
  draft: { tone: 'neutral', icon: PencilLine, label: () => tx('Draft') },
  confirmed: { tone: 'info', icon: ClipboardCheck, label: () => tx('Confirmed') },
  in_progress: { tone: 'progress', icon: Loader, label: () => tx('In progress'), spin: true },
  qc_hold: { tone: 'warning', icon: ShieldAlert, label: () => tx('QC hold') },
  completed: { tone: 'success', icon: CheckCircle2, label: () => tx('Completed') },
  cancelled: { tone: 'danger', icon: Ban, label: () => tx('Cancelled') },
};
const WORK: Record<string, Meta> = {
  pending: { tone: 'neutral', icon: CircleDashed, label: () => tx('Pending') },
  ready: { tone: 'info', icon: Clock, label: () => tx('Ready') },
  in_progress: ORDER.in_progress,
  paused: { tone: 'warning', icon: CirclePause, label: () => tx('Paused') },
  completed: ORDER.completed,
  done: ORDER.completed,
  cancelled: ORDER.cancelled,
};
const QC: Record<string, Meta> = {
  not_required: { tone: 'muted', icon: ShieldCheck, label: () => tx('Not required') },
  pending: { tone: 'warning', icon: Hourglass, label: () => tx('Awaiting QC') },
  curing: { tone: 'info', icon: FlaskConical, label: () => tx('Curing') },
  passed: { tone: 'success', icon: ShieldCheck, label: () => tx('Passed') },
  failed: { tone: 'danger', icon: ShieldX, label: () => tx('Failed') },
};
const BOM: Record<string, Meta> = {
  draft: ORDER.draft,
  active: { tone: 'success', icon: Sparkles, label: () => tx('Active') },
  archived: { tone: 'muted', icon: Archive, label: () => tx('Archived') },
};
const FLOOR: Record<string, Meta> = {
  running: { tone: 'success', icon: Play, label: () => tx('Running') },
  paused: { tone: 'warning', icon: CirclePause, label: () => tx('Paused') },
  down: { tone: 'danger', icon: OctagonAlert, label: () => tx('Down') },
  idle: { tone: 'neutral', icon: Moon, label: () => tx('Idle') },
};

const BY_KIND: Record<StatusKind, Record<string, Meta>> = {
  order: ORDER, workOrder: WORK, stage: WORK, qc: QC, bom: BOM, floor: FLOOR,
  generic: { ...WORK, ...ORDER, ...BOM, ...FLOOR, passed: QC.passed, failed: QC.failed, curing: QC.curing },
};

const humanise = (s: string) => s.replace(/_/g, ' ').replace(/^\w/, (c) => c.toUpperCase());

/** Tone, icon and word for a status. Unknown values fall back to a neutral pill with the raw value humanised. */
export function statusMeta(status: string | null | undefined, kind: StatusKind = 'generic'): Meta {
  const key = status ?? '';
  return BY_KIND[kind][key] ?? BY_KIND.generic[key] ?? { tone: 'neutral', icon: CircleDashed, label: () => humanise(key || '—') };
}

export function MfgStatus({ status, kind = 'generic', size = 'sm', className, label }: {
  status: string | null | undefined; kind?: StatusKind; size?: 'xs' | 'sm' | 'lg'; className?: string; label?: string;
}) {
  const m = statusMeta(status, kind);
  const Icon = m.icon;
  return (
    <span className={cn('inline-flex shrink-0 items-center gap-1 whitespace-nowrap rounded-full font-medium ring-1 ring-inset', TONE_PILL[m.tone],
      size === 'xs' && 'px-1.5 py-px text-[11px]', size === 'sm' && 'px-2 py-0.5 text-xs', size === 'lg' && 'px-3 py-1 text-sm', className)}>
      <Icon className={cn(size === 'lg' ? 'h-4 w-4' : 'h-3 w-3', m.spin && 'animate-spin [animation-duration:3s] motion-reduce:animate-none')} aria-hidden />
      {label ?? m.label()}
    </span>
  );
}

/** Small pill for any tone with an explicit icon and text. */
export function TonePill({ tone, icon: Icon, children, className }: { tone: MfgTone; icon?: LucideIcon; children: React.ReactNode; className?: string }) {
  return (
    <span className={cn('inline-flex shrink-0 items-center gap-1 whitespace-nowrap rounded-full px-2 py-0.5 text-xs font-medium ring-1 ring-inset', TONE_PILL[tone], className)}>
      {Icon && <Icon className="h-3 w-3" aria-hidden />}{children}
    </span>
  );
}

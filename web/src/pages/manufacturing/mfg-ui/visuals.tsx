import { useId, type ReactNode } from 'react';
import { Link } from 'react-router-dom';
import { Area, AreaChart, ResponsiveContainer } from 'recharts';
import { ArrowDownRight, ArrowRight, ArrowUpRight, Minus, type LucideIcon } from 'lucide-react';
import { Skeleton } from '@/components/ui/skeleton';
import { cn } from '@/lib/utils';
import { TONE_FILL, TONE_HEX, TONE_PILL, TONE_TEXT, type MfgTone } from './status';

/* ------------------------------------------------------------------ progress */

const clamp = (v: number) => Math.min(100, Math.max(0, v));

/** Tone for a percentage against good / warn thresholds (higher is better). */
export function pctTone(v: number | null | undefined, good = 85, warn = 60): MfgTone {
  if (v == null || !Number.isFinite(v)) return 'muted';
  return v >= good ? 'success' : v >= warn ? 'warning' : 'danger';
}

/** Circular progress with the value in the middle. */
export function ProgressRing({ value, size = 72, stroke = 8, tone = 'progress', label, sub, className }: {
  value: number | null | undefined; size?: number; stroke?: number; tone?: MfgTone; label?: ReactNode; sub?: ReactNode; className?: string;
}) {
  const r = 50 - stroke / 2 - 1;
  const c = 2 * Math.PI * r;
  const has = value != null && Number.isFinite(value);
  const v = has ? clamp(value as number) : 0;
  return (
    <div className={cn('relative inline-grid shrink-0 place-items-center', className)} style={{ width: size, height: size }}
      role="img" aria-label={has ? `${Math.round(v)}%` : '—'}>
      <svg viewBox="0 0 100 100" className="absolute inset-0 -rotate-90" aria-hidden>
        <circle cx="50" cy="50" r={r} fill="none" stroke="hsl(var(--muted))" strokeWidth={stroke} />
        {has && (
          <circle cx="50" cy="50" r={r} fill="none" stroke={TONE_HEX[tone]} strokeWidth={stroke} strokeLinecap="round"
            strokeDasharray={`${(v / 100) * c} ${c}`} className="transition-[stroke-dasharray] duration-700 motion-reduce:transition-none" />
        )}
      </svg>
      <div className="relative text-center leading-tight">
        <div className={cn('font-semibold tabular-nums', size >= 96 ? 'text-xl' : size >= 64 ? 'text-base' : 'text-xs')}>{label ?? (has ? `${Math.round(v)}%` : '—')}</div>
        {sub && <div className="text-[10px] text-muted-foreground">{sub}</div>}
      </div>
    </div>
  );
}

/** Horizontal progress bar; optional label and secondary (planned) marker. */
export function ProgressBar({ value, tone = 'progress', size = 'sm', showLabel, marker, className, ariaLabel }: {
  value: number | null | undefined; tone?: MfgTone; size?: 'xs' | 'sm' | 'md' | 'lg'; showLabel?: boolean; marker?: number; className?: string; ariaLabel?: string;
}) {
  const has = value != null && Number.isFinite(value);
  const v = has ? clamp(value as number) : 0;
  const h = { xs: 'h-1', sm: 'h-1.5', md: 'h-2.5', lg: 'h-4' }[size];
  return (
    <div className={cn('flex min-w-0 items-center gap-2', className)}>
      <div className={cn('relative w-full min-w-12 overflow-hidden rounded-full bg-muted', h)} role="progressbar" aria-valuemin={0} aria-valuemax={100}
        aria-valuenow={has ? Math.round(v) : undefined} aria-label={ariaLabel}>
        <div className={cn('h-full rounded-full transition-[width] duration-700 motion-reduce:transition-none', TONE_FILL[tone])} style={{ width: `${v}%` }} />
        {marker != null && <span className="absolute inset-y-0 w-0.5 bg-foreground/60" style={{ left: `${clamp(marker)}%` }} aria-hidden />}
      </div>
      {showLabel && <span className="w-10 shrink-0 text-right text-xs tabular-nums text-muted-foreground">{has ? `${Math.round(v)}%` : '—'}</span>}
    </div>
  );
}

/** Planned vs actual on one track: the bar is actual, the tick is planned; over-use turns amber / red. */
export function PlanActualBar({ planned, actual, format = (n: number) => n.toLocaleString(undefined, { maximumFractionDigits: 3 }) }: {
  planned: number; actual: number; format?: (n: number) => string;
}) {
  const top = Math.max(planned, actual, 0.000001);
  const ratio = planned > 0 ? actual / planned : actual > 0 ? 2 : 0;
  const tone: MfgTone = ratio > 1.05 ? 'danger' : ratio > 1.0001 ? 'warning' : ratio >= 0.9999 ? 'success' : 'progress';
  return (
    <div className="min-w-28">
      <div className="relative h-2 overflow-hidden rounded-full bg-muted" role="img" aria-label={`${format(actual)} / ${format(planned)}`}>
        <div className={cn('h-full rounded-full', TONE_FILL[tone])} style={{ width: `${(actual / top) * 100}%` }} />
        <span className="absolute inset-y-0 w-0.5 bg-foreground/70" style={{ left: `calc(${(planned / top) * 100}% - 1px)` }} aria-hidden />
      </div>
      <div className="mt-0.5 flex justify-between text-[10px] tabular-nums text-muted-foreground">
        <span>{planned > 0 ? `${Math.round(ratio * 100)}%` : '—'}</span>
        <span>{format(actual)} / {format(planned)}</span>
      </div>
    </div>
  );
}

/* ------------------------------------------------------------------ sparkline + KPI tile */

export function Sparkline({ values, tone = 'progress', height = 36 }: { values: number[]; tone?: MfgTone; height?: number }) {
  const id = useId().replace(/:/g, '');
  if (values.length < 2 || values.every((v) => v === 0)) return null;
  const data = values.map((v, i) => ({ i, v }));
  const color = TONE_HEX[tone];
  return (
    <div style={{ height }} aria-hidden className="w-full">
      <ResponsiveContainer width="100%" height="100%">
        <AreaChart data={data} margin={{ top: 2, right: 0, bottom: 0, left: 0 }}>
          <defs>
            <linearGradient id={`sp${id}`} x1="0" y1="0" x2="0" y2="1">
              <stop offset="0%" stopColor={color} stopOpacity={0.35} />
              <stop offset="100%" stopColor={color} stopOpacity={0} />
            </linearGradient>
          </defs>
          <Area type="monotone" dataKey="v" stroke={color} strokeWidth={1.75} fill={`url(#sp${id})`} isAnimationActive={false} dot={false} />
        </AreaChart>
      </ResponsiveContainer>
    </div>
  );
}

export interface Delta { value: number; label?: string; /** True when a rise is bad (scrap, late). */ invert?: boolean }

function DeltaChip({ delta }: { delta: Delta }) {
  const up = delta.value > 0;
  const flat = delta.value === 0;
  const good = flat ? null : up !== !!delta.invert;
  const Icon = flat ? Minus : up ? ArrowUpRight : ArrowDownRight;
  return (
    <span className={cn('inline-flex items-center gap-0.5 rounded-full px-1.5 py-px text-[11px] font-medium ring-1 ring-inset',
      good == null ? TONE_PILL.muted : good ? TONE_PILL.success : TONE_PILL.danger)}>
      <Icon className="h-3 w-3" aria-hidden />{delta.label ?? `${Math.abs(delta.value).toLocaleString(undefined, { maximumFractionDigits: 1 })}`}
    </span>
  );
}

/** KPI tile: icon, label, big value, context line, delta and an optional sparkline or ring. */
export function StatTile({ icon: Icon, label, value, sub, tone = 'info', delta, spark, ring, to, loading, title, className }: {
  icon?: LucideIcon; label: string; value: ReactNode; sub?: ReactNode; tone?: MfgTone; delta?: Delta; spark?: number[];
  ring?: number | null; to?: string; loading?: boolean; title?: string; className?: string;
}) {
  const inner = (
    <>
      <div className="flex items-start justify-between gap-2">
        <div className="min-w-0">
          <div className="flex items-center gap-2">
            {Icon && <span className={cn('grid h-8 w-8 shrink-0 place-items-center rounded-lg ring-1 ring-inset', TONE_PILL[tone])}><Icon className="h-4 w-4" aria-hidden /></span>}
            <span className="truncate text-xs font-medium uppercase tracking-wide text-muted-foreground">{label}</span>
          </div>
          <div className="mt-2 truncate text-2xl font-semibold tabular-nums tracking-tight" title={title}>
            {loading ? <Skeleton className="h-7 w-20" /> : value}
          </div>
          {(sub || delta) && (
            <div className="mt-1 flex min-w-0 flex-wrap items-center gap-1.5 text-xs text-muted-foreground">
              {delta && <DeltaChip delta={delta} />}
              {sub && <span className="truncate">{sub}</span>}
            </div>
          )}
        </div>
        {ring !== undefined && <ProgressRing value={ring} size={52} stroke={10} tone={pctTone(ring)} />}
      </div>
      {spark && <div className="mt-2"><Sparkline values={spark} tone={tone} /></div>}
    </>
  );
  const cls = cn('group relative block h-full overflow-hidden rounded-2xl border bg-card p-4 shadow-sm', className);
  return to
    ? <Link to={to} className={cn(cls, 'transition-all hover:-translate-y-0.5 hover:border-primary/40 hover:shadow-md focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring motion-reduce:hover:translate-y-0')}>{inner}</Link>
    : <div className={cls}>{inner}</div>;
}

/* ------------------------------------------------------------------ headers, sections, empty, skeletons */

/** Page header used across manufacturing: icon tile, title, subtitle, optional back link, actions and badges. */
export function MfgPageHeader({ icon: Icon, title, subtitle, actions, back, badges, eyebrow }: {
  icon?: LucideIcon; title: ReactNode; subtitle?: ReactNode; actions?: ReactNode; back?: ReactNode; badges?: ReactNode; eyebrow?: ReactNode;
}) {
  return (
    <header className="relative overflow-hidden rounded-2xl border bg-card p-4 shadow-sm sm:p-5">
      <div className="pointer-events-none absolute inset-0 bg-[radial-gradient(80%_120%_at_100%_0%,hsl(var(--primary)/0.10),transparent_60%)]" aria-hidden />
      <div className="relative flex flex-wrap items-start justify-between gap-3">
        <div className="flex min-w-0 items-start gap-3">
          {back}
          {Icon && (
            <span className="grid h-11 w-11 shrink-0 place-items-center rounded-xl bg-gradient-to-br from-primary to-primary/70 text-primary-foreground shadow-sm">
              <Icon className="h-5 w-5" aria-hidden />
            </span>
          )}
          <div className="min-w-0">
            {eyebrow && <div className="text-xs font-medium uppercase tracking-wide text-muted-foreground">{eyebrow}</div>}
            <h1 className="flex flex-wrap items-center gap-2 text-xl font-semibold tracking-tight sm:text-2xl">{title}{badges}</h1>
            {subtitle && <p className="mt-0.5 text-sm text-muted-foreground">{subtitle}</p>}
          </div>
        </div>
        {actions && <div className="flex w-full flex-wrap items-center gap-2 sm:w-auto">{actions}</div>}
      </div>
    </header>
  );
}

export function SectionHeader({ icon: Icon, title, description, actions, className }: {
  icon?: LucideIcon; title: ReactNode; description?: ReactNode; actions?: ReactNode; className?: string;
}) {
  return (
    <div className={cn('mb-3 flex flex-wrap items-start justify-between gap-2', className)}>
      <div className="flex min-w-0 items-start gap-2">
        {Icon && <span className="mt-0.5 grid h-7 w-7 shrink-0 place-items-center rounded-lg bg-primary/10 text-primary"><Icon className="h-4 w-4" aria-hidden /></span>}
        <div className="min-w-0">
          <h2 className="font-semibold leading-tight">{title}</h2>
          {description && <p className="mt-0.5 text-xs text-muted-foreground">{description}</p>}
        </div>
      </div>
      {actions && <div className="flex flex-wrap items-center gap-2">{actions}</div>}
    </div>
  );
}

/** Card shell with a section header. */
export function MfgCard({ icon, title, description, actions, children, className, bodyClassName, href, hrefLabel }: {
  icon?: LucideIcon; title?: ReactNode; description?: ReactNode; actions?: ReactNode; children: ReactNode; className?: string; bodyClassName?: string;
  href?: string; hrefLabel?: string;
}) {
  return (
    <section className={cn('min-w-0 rounded-2xl border bg-card p-4 shadow-sm sm:p-5', className)}>
      {title && (
        <SectionHeader icon={icon} title={title} description={description} actions={
          <>
            {actions}
            {href && <Link to={href} className="inline-flex items-center gap-1 text-xs font-medium text-primary hover:underline">{hrefLabel}<ArrowRight className="h-3 w-3" aria-hidden /></Link>}
          </>
        } />
      )}
      <div className={cn('min-w-0', bodyClassName)}>{children}</div>
    </section>
  );
}

export function EmptyState({ icon: Icon, title, description, action, compact, className }: {
  icon: LucideIcon; title: ReactNode; description?: ReactNode; action?: ReactNode; compact?: boolean; className?: string;
}) {
  return (
    <div className={cn('flex flex-col items-center justify-center text-center', compact ? 'gap-1.5 py-6' : 'gap-2 py-12', className)}>
      <div className="relative">
        <div className="absolute inset-0 -m-3 rounded-full bg-primary/5" aria-hidden />
        <span className={cn('relative grid place-items-center rounded-2xl border border-dashed border-primary/30 bg-card text-primary/70', compact ? 'h-10 w-10' : 'h-14 w-14')}>
          <Icon className={compact ? 'h-5 w-5' : 'h-7 w-7'} aria-hidden />
        </span>
      </div>
      <div className={cn('font-medium', compact ? 'mt-1 text-sm' : 'mt-2')}>{title}</div>
      {description && <p className="max-w-sm text-sm text-muted-foreground">{description}</p>}
      {action && <div className="mt-2">{action}</div>}
    </div>
  );
}

export function TileRowSkeleton({ count = 4 }: { count?: number }) {
  return (
    <div className="grid grid-cols-2 gap-3 lg:grid-cols-4">
      {Array.from({ length: count }, (_, i) => (
        <div key={i} className="rounded-2xl border bg-card p-4"><Skeleton className="h-4 w-24" /><Skeleton className="mt-3 h-7 w-16" /><Skeleton className="mt-2 h-3 w-28" /></div>
      ))}
    </div>
  );
}

export function TableSkeleton({ rows = 5, cols = 5 }: { rows?: number; cols?: number }) {
  return (
    <div className="space-y-2" aria-busy="true">
      {Array.from({ length: rows }, (_, r) => (
        <div key={r} className="flex gap-3">
          {Array.from({ length: cols }, (_, c) => <Skeleton key={c} className={cn('h-5', c === 0 ? 'w-1/4' : 'flex-1')} />)}
        </div>
      ))}
    </div>
  );
}

export function PageSkeleton() {
  return (
    <div className="space-y-4" aria-busy="true">
      <Skeleton className="h-24 w-full rounded-2xl" />
      <TileRowSkeleton />
      <Skeleton className="h-64 w-full rounded-2xl" />
    </div>
  );
}

/** Label / value pair for dense fact lists. */
export function Fact({ label, value, className, tone }: { label: ReactNode; value: ReactNode; className?: string; tone?: MfgTone }) {
  return (
    <div className={cn('min-w-0', className)}>
      <dt className="truncate text-[11px] uppercase tracking-wide text-muted-foreground">{label}</dt>
      <dd className={cn('truncate font-semibold tabular-nums', tone && TONE_TEXT[tone])}>{value}</dd>
    </div>
  );
}

/** Segmented filter chip with a count. */
export function FilterChip({ active, onClick, children, count, tone = 'neutral', icon: Icon }: {
  active: boolean; onClick: () => void; children: ReactNode; count?: number; tone?: MfgTone; icon?: LucideIcon;
}) {
  return (
    <button type="button" onClick={onClick} aria-pressed={active}
      className={cn('inline-flex shrink-0 items-center gap-1.5 rounded-full border px-3 py-1.5 text-xs font-medium transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring',
        active ? 'border-primary bg-primary text-primary-foreground shadow-sm' : 'bg-card text-muted-foreground hover:bg-accent hover:text-foreground')}>
      {Icon ? <Icon className="h-3.5 w-3.5" aria-hidden /> : <span className={cn('h-2 w-2 rounded-full', TONE_FILL[tone])} aria-hidden />}
      {children}
      {count != null && <span className={cn('rounded-full px-1.5 tabular-nums', active ? 'bg-primary-foreground/20' : 'bg-muted')}>{count}</span>}
    </button>
  );
}

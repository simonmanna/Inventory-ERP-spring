import type { CSSProperties, ReactNode } from 'react';
import { Link } from 'react-router-dom';
import { AlertCircle, AlertTriangle, ArrowRight, CheckCircle2, Info, Loader2, type LucideIcon } from 'lucide-react';
import { tx } from '@/lib/i18n/tx';
import { cn } from '@/lib/utils';
import { useCountUp } from '@/pages/sales/dashboard/utils';
import { Panel, Reveal } from '@/pages/sales/dashboard/widgets';
import { periodOptions, type PeriodKey } from './period';

export { Panel, Reveal };

const hairline = 'color-mix(in srgb, var(--sb-text) 16%, transparent)';
const heroStyle: CSSProperties = {
  background: 'radial-gradient(90% 130% at 100% 0%, color-mix(in srgb, var(--sb-active-bar) 18%, transparent) 0%, transparent 60%), var(--sb-sidebar)',
  color: 'var(--sb-active)',
};

export function HeroRing({ percent }: { percent: number | null }) {
  const r = 17;
  const c = 2 * Math.PI * r;
  return (
    <svg viewBox="0 0 44 44" className="h-11 w-11 shrink-0 -rotate-90" aria-hidden>
      <circle cx="22" cy="22" r={r} fill="none" stroke="var(--sb-text)" strokeOpacity={0.16} strokeWidth="5" />
      {percent != null && <circle cx="22" cy="22" r={r} fill="none" stroke="var(--sb-active-bar)" strokeWidth="5" strokeLinecap="round" strokeDasharray={`${(Math.min(100, Math.max(0, percent)) / 100) * c} ${c}`} className="transition-[stroke-dasharray] duration-700 motion-reduce:transition-none" />}
    </svg>
  );
}

export function HeroStat({ label, value, sub, title, to, ring }: { label: string; value: string; sub?: string; title?: string; to?: string; ring?: number | null }) {
  const inner = (
    <>
      <span className="min-w-0">
        <span className="block truncate text-xs" style={{ color: 'var(--sb-text)' }}>{label}</span>
        <span className="mt-0.5 block truncate text-lg font-semibold">{value}</span>
        {sub && <span className="block truncate text-xs" style={{ color: 'var(--sb-text)' }}>{sub}</span>}
      </span>
      {ring !== undefined && <HeroRing percent={ring} />}
    </>
  );
  const cls = 'flex items-center justify-between gap-2 rounded-xl border p-3 transition-colors';
  return to
    ? <Link to={to} title={title} className={cn(cls, 'hover:bg-[var(--sb-hover)]')} style={{ borderColor: hairline }}>{inner}</Link>
    : <div title={title} className={cls} style={{ borderColor: hairline }}>{inner}</div>;
}

export interface HeroProps {
  icon: LucideIcon;
  title: string;
  subtitle: string;
  period: PeriodKey;
  onPeriod: (p: PeriodKey) => void;
  fetching?: boolean;
  actions?: ReactNode;
  headline: { label: string; value: number; format: (n: number) => string; title?: string; sub?: ReactNode };
  chartTitle: string;
  chart: ReactNode;
  stats: ReactNode;
}

/** The band at the top of each operations dashboard, in the theme's sidebar colours. */
export function DashboardHero({ icon: Icon, title, subtitle, period, onPeriod, fetching, actions, headline, chartTitle, chart, stats }: HeroProps) {
  const counted = useCountUp(headline.value);
  return (
    <Reveal>
      <section className="overflow-hidden rounded-2xl p-5 shadow-lg md:p-7" style={heroStyle}>
        <div className="flex flex-wrap items-start justify-between gap-3">
          <div className="min-w-0">
            <h1 className="flex items-center gap-2 text-xl font-semibold">
              <Icon className="h-5 w-5" style={{ color: 'var(--sb-active-bar)' }} aria-hidden />
              {title}
              {fetching && <Loader2 className="h-4 w-4 animate-spin" style={{ color: 'var(--sb-text)' }} aria-label={tx('Updating')} />}
            </h1>
            <p className="text-sm" style={{ color: 'var(--sb-text)' }}>{subtitle}</p>
          </div>
          <div className="flex flex-wrap items-center gap-2">
            <div className="flex flex-wrap rounded-lg border p-0.5" style={{ borderColor: hairline }} role="group" aria-label={tx('Period')}>
              {periodOptions().map((o) => {
                const on = o.key === period;
                return (
                  <button key={o.key} type="button" aria-pressed={on} onClick={() => onPeriod(o.key)}
                    className={cn('rounded-md px-2.5 py-1 text-xs font-medium transition-colors', !on && 'hover:bg-[var(--sb-hover)]')}
                    style={on ? { background: 'var(--sb-active-bar)', color: 'var(--sb-sidebar)' } : { color: 'var(--sb-text)' }}>
                    {o.label}
                  </button>
                );
              })}
            </div>
            {actions}
          </div>
        </div>
        <div className="mt-6 grid gap-6 lg:grid-cols-5">
          <div className="min-w-0 lg:col-span-3">
            <div className="text-xs" style={{ color: 'var(--sb-text)' }}>{headline.label}</div>
            <div className="mt-0.5 text-4xl font-semibold tracking-tight md:text-5xl" title={headline.title}>{headline.format(counted)}</div>
            {headline.sub && <div className="mt-1 text-sm" style={{ color: 'var(--sb-text)' }}>{headline.sub}</div>}
            {chart && (
              <>
                <div className="mt-4 text-xs font-medium" style={{ color: 'var(--sb-text)' }}>{chartTitle}</div>
                <div className="mt-2">{chart}</div>
              </>
            )}
          </div>
          <div className="grid content-start gap-2.5 sm:grid-cols-2 lg:col-span-2">{stats}</div>
        </div>
      </section>
    </Reveal>
  );
}

/** Period switch for a page without a hero. */
export function PeriodSelect({ value, onChange }: { value: PeriodKey; onChange: (p: PeriodKey) => void }) {
  return (
    <div className="inline-flex rounded-lg border bg-card p-0.5 text-xs" role="group" aria-label={tx('Period')}>
      {periodOptions().map((o) => (
        <button key={o.key} type="button" aria-pressed={o.key === value} onClick={() => onChange(o.key)}
          className={cn('rounded-md px-2.5 py-1.5 font-medium transition-colors', o.key === value ? 'bg-primary text-primary-foreground' : 'text-muted-foreground hover:bg-accent hover:text-foreground')}>
          {o.label}
        </button>
      ))}
    </div>
  );
}

/** A headline figure: label, value, one line of context, and an optional ring or sparkline. */
export function KpiCard({ label, value, sub, title, to, ring, chart, loading }: { label: string; value: ReactNode; sub?: ReactNode; title?: string; to?: string; ring?: number | null; chart?: ReactNode; loading?: boolean }) {
  const inner = (
    <>
      <div className="flex items-start justify-between gap-3">
        <div className="min-w-0">
          <div className="truncate text-sm font-medium">{label}</div>
          <div className="mt-1 truncate text-2xl font-semibold tabular-nums" title={title}>{loading ? <span className="inline-block h-7 w-16 animate-pulse rounded bg-muted motion-reduce:animate-none" /> : value}</div>
          {sub && <div className="mt-0.5 truncate text-xs text-muted-foreground">{sub}</div>}
        </div>
        {ring !== undefined && <CardRing percent={ring} />}
      </div>
      {chart && <div className="mt-2">{chart}</div>}
    </>
  );
  const cls = 'block h-full rounded-2xl border bg-card p-4 shadow-sm';
  return to ? <Link to={to} className={cn(cls, 'transition-colors hover:border-primary/50')}>{inner}</Link> : <div className={cls}>{inner}</div>;
}

function CardRing({ percent }: { percent: number | null }) {
  const r = 18;
  const c = 2 * Math.PI * r;
  const v = percent == null ? 0 : Math.min(100, Math.max(0, percent));
  const tone = percent == null ? 'text-muted' : v >= 85 ? 'text-emerald-500' : v >= 60 ? 'text-amber-500' : 'text-rose-500';
  return (
    <svg viewBox="0 0 46 46" className="h-12 w-12 shrink-0 -rotate-90" aria-hidden>
      <circle cx="23" cy="23" r={r} fill="none" stroke="currentColor" className="text-muted" strokeWidth="6" />
      {percent != null && <circle cx="23" cy="23" r={r} fill="none" stroke="currentColor" className={cn(tone, 'transition-[stroke-dasharray] duration-700 motion-reduce:transition-none')} strokeWidth="6" strokeLinecap="round" strokeDasharray={`${(v / 100) * c} ${c}`} />}
    </svg>
  );
}

/** A value on a track, with the percentage beside it. */
export function ProgressCell({ percent, tone = 'bg-primary' }: { percent: number | null; tone?: string }) {
  const v = percent == null ? 0 : Math.min(100, Math.max(0, percent));
  return (
    <div className="flex items-center gap-2">
      <div className="h-1.5 w-full min-w-16 overflow-hidden rounded-full bg-muted"><div className={cn('h-full rounded-full transition-[width] duration-700 motion-reduce:transition-none', tone)} style={{ width: `${v}%` }} /></div>
      <span className="w-9 shrink-0 text-right text-xs tabular-nums text-muted-foreground">{percent == null ? '—' : `${Math.round(v)}%`}</span>
    </div>
  );
}

/** Link styled as a button, for actions placed on the hero. */
export function HeroAction({ to, icon: Icon, children, primary }: { to: string; icon?: LucideIcon; children: ReactNode; primary?: boolean }) {
  return (
    <Link to={to} className={cn('inline-flex items-center gap-1.5 rounded-lg border px-2.5 py-1.5 text-xs font-medium transition-colors', !primary && 'hover:bg-[var(--sb-hover)]')}
      style={primary ? { background: 'var(--sb-active-bar)', color: 'var(--sb-sidebar)', borderColor: 'var(--sb-active-bar)' } : { borderColor: hairline, color: 'var(--sb-active)' }}>
      {Icon && <Icon className="h-3.5 w-3.5" aria-hidden />}{children}
    </Link>
  );
}

export type BriefTone = 'bad' | 'warn' | 'good' | 'info';
export interface BriefItem { key: string; tone: BriefTone; text: string; to?: string }

const TONE: Record<BriefTone, { icon: LucideIcon; label: () => string; className: string; order: number }> = {
  bad: { icon: AlertCircle, label: () => tx('Act now'), className: 'bg-rose-100 text-rose-800 dark:bg-rose-500/15 dark:text-rose-300', order: 0 },
  warn: { icon: AlertTriangle, label: () => tx('Watch'), className: 'bg-amber-100 text-amber-800 dark:bg-amber-500/15 dark:text-amber-300', order: 1 },
  good: { icon: CheckCircle2, label: () => tx('Good'), className: 'bg-emerald-100 text-emerald-800 dark:bg-emerald-500/15 dark:text-emerald-300', order: 2 },
  info: { icon: Info, label: () => tx('Note'), className: 'bg-muted text-muted-foreground', order: 3 },
};

/** The manager's short read of the numbers: most urgent first, each line opening the screen that deals with it. */
export function Brief({ items }: { items: BriefItem[] }) {
  const sorted = [...items].sort((a, b) => TONE[a.tone].order - TONE[b.tone].order);
  return (
    <Panel title={tx('Manager\'s brief')} hint={tx('What these numbers are saying, most urgent first.')}>
      {sorted.length === 0 ? <p className="py-6 text-center text-sm text-muted-foreground">{tx('Nothing needs attention right now.')}</p> : (
        <ul className="space-y-1.5">
          {sorted.slice(0, 7).map((i) => {
            const t = TONE[i.tone];
            const Icon = t.icon;
            const body = (
              <>
                <span className={cn('mt-px inline-flex w-[4.75rem] shrink-0 items-center justify-center gap-1 rounded-full px-1.5 py-0.5 text-[11px] font-medium', t.className)}><Icon className="h-3 w-3" aria-hidden />{t.label()}</span>
                <span className="min-w-0 flex-1 text-sm">{i.text}</span>
                {i.to && <ArrowRight className="mt-0.5 h-3.5 w-3.5 shrink-0 text-muted-foreground" aria-hidden />}
              </>
            );
            return <li key={i.key}>{i.to ? <Link to={i.to} className="-mx-2 flex items-start gap-2.5 rounded-lg px-2 py-1 transition-colors hover:bg-muted/60">{body}</Link> : <div className="flex items-start gap-2.5 py-1">{body}</div>}</li>;
          })}
        </ul>
      )}
    </Panel>
  );
}

/** Numbers from the API may arrive as strings, null or absent. */
export const num = (v: unknown): number => {
  const x = Number(v ?? 0);
  return Number.isFinite(x) ? x : 0;
};
/** Like `num`, but keeps "no figure" apart from zero. */
export const numOrNull = (v: unknown): number | null => {
  if (v === null || v === undefined || v === '') return null;
  const x = Number(v);
  return Number.isFinite(x) ? x : null;
};
export const pctText = (v: number | null | undefined, digits = 1) => (v == null ? '—' : `${v.toLocaleString(undefined, { maximumFractionDigits: digits })}%`);
export const qtyText = (v: number, digits = 0) => v.toLocaleString(undefined, { maximumFractionDigits: digits });

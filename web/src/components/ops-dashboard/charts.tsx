import { useMemo, useState, type CSSProperties, type ReactNode } from 'react';
import { Link } from 'react-router-dom';
import {
  Area, AreaChart, Bar, BarChart, CartesianGrid, Cell, Line, LineChart, Pie, PieChart, ResponsiveContainer, Tooltip, XAxis, YAxis,
} from 'recharts';
import { AlertCircle, AlertTriangle, CheckCircle2, type LucideIcon } from 'lucide-react';
import { useTheme } from '@/components/theme-provider';
import { tx } from '@/lib/i18n/tx';
import { cn } from '@/lib/utils';
import { compactNumber, SERIES_DARK, SERIES_LIGHT } from '@/pages/sales/reports/report-format';
import { bucketsOf, isoDate } from './period';

/**
 * Chart parts of the operations dashboards. One palette (validated for colour-blind separation on the card surface in
 * both themes), one y-scale per chart, hairline grids, and a data table behind every plot.
 */

export function useSeries(): string[] {
  const { theme } = useTheme();
  return theme === 'dark' ? SERIES_DARK : SERIES_LIGHT;
}

/** Reserved for meaning, never for identity; always shipped with an icon and a word. */
export const STATUS = { good: '#0ca30c', warning: '#fab219', serious: '#ec835a', critical: '#d03b3b', neutral: '#898781' } as const;

const STATUS_ICON: Record<'good' | 'warning' | 'critical', LucideIcon> = { good: CheckCircle2, warning: AlertTriangle, critical: AlertCircle };

const reduced = () => typeof window !== 'undefined' && !!window.matchMedia?.('(prefers-reduced-motion: reduce)').matches;

const TIP: CSSProperties = { borderRadius: 10, fontSize: 12, background: 'hsl(var(--card))', borderColor: 'hsl(var(--border))', color: 'hsl(var(--card-foreground))' };

export function Empty({ children }: { children?: ReactNode }) {
  return <p className="py-8 text-center text-sm text-muted-foreground">{children ?? tx('No data for this period.')}</p>;
}

export function Legend({ items, onHero }: { items: Array<{ label: string; color: string; value?: string }>; onHero?: boolean }) {
  return (
    <ul className={cn('flex flex-wrap gap-x-4 gap-y-1 text-xs', !onHero && 'text-muted-foreground')} style={onHero ? { color: 'var(--sb-text)' } : undefined}>
      {items.map((i) => (
        <li key={i.label} className="flex items-center gap-1.5">
          <span className="h-2.5 w-2.5 shrink-0 rounded-sm" style={{ background: i.color }} aria-hidden />
          <span>{i.label}</span>
          {i.value && <span className="font-medium tabular-nums text-foreground">{i.value}</span>}
        </li>
      ))}
    </ul>
  );
}

/** The table behind a chart: no value is reachable only through a tooltip. */
export function DataDetails({ heads, rows }: { heads: string[]; rows: Array<Array<string | number>> }) {
  if (rows.length === 0) return null;
  return (
    <details className="mt-3 text-xs text-muted-foreground">
      <summary className="cursor-pointer select-none hover:text-foreground">{tx('Show data')}</summary>
      <div className="mt-2 max-h-56 overflow-auto">
        <table className="w-full tabular-nums">
          <thead><tr>{heads.map((h, i) => <th key={h} className={cn('sticky top-0 bg-card pb-1 pr-3 font-medium', i ? 'text-right' : 'text-left')}>{h}</th>)}</tr></thead>
          <tbody>
            {rows.map((r) => (
              <tr key={String(r[0])} className="border-t border-border/50">
                {r.map((c, i) => <td key={i} className={cn('py-0.5 pr-3', i ? 'text-right' : 'text-left')}>{c}</td>)}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </details>
  );
}

export interface TrendSeries { key: string; label: string; color?: string }
type TrendRow = Record<string, string | number | null>;

interface TrendProps {
  data: TrendRow[];
  series: TrendSeries[];
  kind?: 'bars' | 'stacked' | 'area' | 'line';
  xKey?: string;
  format?: (n: number) => string;
  height?: number;
  /** Draw on the dashboard hero, in the sidebar's colours. */
  onHero?: boolean;
  /** Shorter form for the axis, where the full figure would not fit. */
  axisFormat?: (n: number) => string;
}

/** Bars, stacked bars, an area or lines over time: always one y-scale. */
export function TrendChart({ data, series, kind = 'bars', xKey = 'label', format = compactNumber, height = 240, onHero, axisFormat }: TrendProps) {
  const palette = useSeries();
  const color = (s: TrendSeries, i: number) => s.color ?? palette[i % palette.length];
  const ink = onHero ? 'var(--sb-text)' : 'hsl(var(--muted-foreground))';
  const grid = onHero
    ? { stroke: 'var(--sb-text)', strokeOpacity: 0.12 }
    : { stroke: 'hsl(var(--border))', strokeOpacity: 0.7 };
  const surface = onHero ? 'var(--sb-sidebar)' : 'hsl(var(--card))';
  const hasData = data.some((r) => series.some((s) => Number(r[s.key] ?? 0) !== 0));
  if (!hasData) {
    return <div style={onHero ? { color: 'var(--sb-text)', height } : undefined} className={onHero ? 'flex items-center justify-center text-sm' : undefined}>{onHero ? tx('No data for this period.') : <Empty />}</div>;
  }

  const common = { data, margin: { top: 8, right: 8, bottom: 0, left: 0 } };
  const axes = (
    <>
      <CartesianGrid vertical={false} {...grid} />
      <XAxis dataKey={xKey} tick={{ fontSize: 11, fill: ink }} tickLine={false} axisLine={false} minTickGap={18} />
      <YAxis tickFormatter={(v: number) => (axisFormat ?? format)(v)} tick={{ fontSize: 11, fill: ink }} tickLine={false} axisLine={false} width={52} tickCount={4} />
      <Tooltip contentStyle={TIP} cursor={kind === 'bars' || kind === 'stacked' ? { fill: ink, fillOpacity: 0.08 } : { stroke: ink, strokeOpacity: 0.4 }} formatter={(v: number, name: string) => [format(Number(v)), name]} />
    </>
  );
  const animate = !reduced();

  return (
    <div>
      {series.length > 1 && <div className="mb-2"><Legend onHero={onHero} items={series.map((s, i) => ({ label: s.label, color: color(s, i) }))} /></div>}
      <ResponsiveContainer width="100%" height={height}>
        {kind === 'area' ? (
          <AreaChart {...common}>
            {axes}
            {series.map((s, i) => (
              <Area key={s.key} dataKey={s.key} name={s.label} type="monotone" stroke={color(s, i)} strokeWidth={2} fill={color(s, i)} fillOpacity={0.18} isAnimationActive={animate} activeDot={{ r: 4, stroke: surface, strokeWidth: 2 }} />
            ))}
          </AreaChart>
        ) : kind === 'line' ? (
          <LineChart {...common}>
            {axes}
            {series.map((s, i) => (
              <Line key={s.key} dataKey={s.key} name={s.label} type="monotone" stroke={color(s, i)} strokeWidth={2} connectNulls dot={data.length <= 14 ? { r: 4, stroke: surface, strokeWidth: 2, fill: color(s, i) } : false} isAnimationActive={animate} activeDot={{ r: 4, stroke: surface, strokeWidth: 2 }} />
            ))}
          </LineChart>
        ) : (
          <BarChart {...common} barCategoryGap="24%" barGap={2}>
            {axes}
            {series.map((s, i) => (
              <Bar key={s.key} dataKey={s.key} name={s.label} fill={color(s, i)} maxBarSize={32} stackId={kind === 'stacked' ? 'a' : undefined}
                radius={kind === 'stacked' && i < series.length - 1 ? 0 : [4, 4, 0, 0]} stroke={kind === 'stacked' ? surface : undefined} strokeWidth={kind === 'stacked' ? 1 : 0} isAnimationActive={animate} />
            ))}
          </BarChart>
        )}
      </ResponsiveContainer>
      {!onHero && <DataDetails heads={[tx('Period'), ...series.map((s) => s.label)]} rows={data.map((r) => [String(r[xKey]), ...series.map((s) => (r[s.key] == null ? '—' : format(Number(r[s.key]))))])} />}
    </div>
  );
}

export interface Slice { key: string; label: string; value: number; color?: string; href?: string }

/** Part of a whole at a glance: at most six slices, the tail folded into "Other", with a share beside each name. */
export function Donut({ rows, center, centerSub, format = compactNumber, size = 168, stacked }: { rows: Slice[]; center?: string; centerSub?: string; format?: (n: number) => string; size?: number; /** Ring above the list, for narrow panels. */ stacked?: boolean }) {
  const palette = useSeries();
  const { data, total } = useMemo(() => {
    const positive = rows.filter((r) => r.value > 0);
    const sum = positive.reduce((s, r) => s + r.value, 0);
    if (positive.length <= 6) return { data: positive, total: sum };
    const sorted = [...positive].sort((a, b) => b.value - a.value);
    const rest = sorted.slice(5).reduce((s, r) => s + r.value, 0);
    return { data: [...sorted.slice(0, 5), { key: '__other', label: tx('Other'), value: rest, color: STATUS.neutral } as Slice], total: sum };
  }, [rows]);
  if (data.length === 0) return <Empty />;
  const fill = (r: Slice, i: number) => r.color ?? palette[i % palette.length];
  return (
    <div className={cn('flex flex-col items-center gap-4', !stacked && 'sm:flex-row')}>
      <div className="relative shrink-0" style={{ width: size, height: size }}>
        <ResponsiveContainer width="100%" height="100%">
          <PieChart>
            <Pie data={data} dataKey="value" nameKey="label" innerRadius="70%" outerRadius="100%" paddingAngle={data.length > 1 ? 2 : 0} stroke="hsl(var(--card))" strokeWidth={2} isAnimationActive={!reduced()}>
              {data.map((r, i) => <Cell key={r.key} fill={fill(r, i)} />)}
            </Pie>
            <Tooltip contentStyle={TIP} formatter={(v: number, name: string) => [format(Number(v)), name]} />
          </PieChart>
        </ResponsiveContainer>
        <div className="pointer-events-none absolute inset-0 flex flex-col items-center justify-center text-center">
          <span className={cn('font-semibold', (center ?? format(total)).length > 7 ? 'text-sm' : 'text-lg')}>{center ?? format(total)}</span>
          {centerSub && <span className="max-w-[70%] truncate text-[11px] text-muted-foreground">{centerSub}</span>}
        </div>
      </div>
      <ul className="w-full min-w-0 flex-1 space-y-1.5">
        {data.map((r, i) => {
          const body = (
            <>
              <span className="h-2.5 w-2.5 shrink-0 rounded-sm" style={{ background: fill(r, i) }} aria-hidden />
              <span className="min-w-0 flex-1 truncate">{r.label}</span>
              <span className="shrink-0 tabular-nums text-muted-foreground">{format(r.value)}</span>
              <span className="w-10 shrink-0 text-end font-medium tabular-nums">{total > 0 ? `${Math.round((r.value / total) * 100)}%` : '—'}</span>
            </>
          );
          return (
            <li key={r.key}>
              {r.href
                ? <Link to={r.href} className="-mx-1.5 flex items-center gap-2 rounded px-1.5 py-0.5 text-sm hover:bg-muted/60">{body}</Link>
                : <div className="flex items-center gap-2 py-0.5 text-sm">{body}</div>}
            </li>
          );
        })}
      </ul>
    </div>
  );
}

export interface RankRow {
  key: string;
  label: string;
  sub?: string;
  value: number;
  display: string;
  note?: string;
  href?: string;
  flag?: { tone: 'warning' | 'critical' | 'good'; label: string };
  /** Stacked parts of the bar, in the order of `parts` on the chart. */
  parts?: number[];
}

/** Ranked horizontal bars: one hue, the name and figure as text, a thin bar for the comparison. */
export function RankedBars({ rows, max, parts, limit = 8, emptyText }: { rows: RankRow[]; max?: number; parts?: string[]; limit?: number; emptyText?: string }) {
  const palette = useSeries();
  if (rows.length === 0) return <Empty>{emptyText}</Empty>;
  const shown = rows.slice(0, limit);
  const top = max ?? Math.max(1, ...shown.map((r) => r.value));
  return (
    <div>
      {parts && <div className="mb-3"><Legend items={parts.map((label, i) => ({ label, color: palette[i % palette.length] }))} /></div>}
      <ol className="space-y-2">
        {shown.map((r) => {
          const body = (
            <>
              <div className="flex items-baseline gap-2 text-sm">
                <span className="min-w-0 flex-1 truncate font-medium" title={r.label}>{r.label}</span>
                {r.flag && <FlagPill tone={r.flag.tone}>{r.flag.label}</FlagPill>}
                <span className="shrink-0 font-semibold tabular-nums">{r.display}</span>
              </div>
              <div className="mt-1 flex h-1.5 gap-px overflow-hidden rounded-full bg-muted">
                {r.parts
                  ? r.parts.map((p, i) => <div key={i} className="h-full transition-[width] duration-700 motion-reduce:transition-none" style={{ width: `${(p / top) * 100}%`, background: palette[i % palette.length] }} />)
                  : <div className="h-full rounded-full bg-primary transition-[width] duration-700 motion-reduce:transition-none" style={{ width: `${Math.max(1.5, (r.value / top) * 100)}%` }} />}
              </div>
              {(r.sub || r.note) && (
                <div className="mt-0.5 flex justify-between gap-2 text-[11px] text-muted-foreground">
                  <span className="truncate">{r.sub}</span>
                  <span className="shrink-0 tabular-nums">{r.note}</span>
                </div>
              )}
            </>
          );
          return <li key={r.key}>{r.href ? <Link to={r.href} className="-mx-2 block rounded-lg px-2 py-1 transition-colors hover:bg-muted/60">{body}</Link> : <div className="py-1">{body}</div>}</li>;
        })}
      </ol>
      {rows.length > limit && <p className="mt-2 text-[11px] text-muted-foreground">{tx('Top {{n}} of {{total}}', { n: limit, total: rows.length })}</p>}
    </div>
  );
}

export function FlagPill({ tone, children }: { tone: 'warning' | 'critical' | 'good'; children: ReactNode }) {
  const Icon = STATUS_ICON[tone];
  const cls = tone === 'critical'
    ? 'bg-rose-100 text-rose-800 dark:bg-rose-500/15 dark:text-rose-300'
    : tone === 'warning'
      ? 'bg-amber-100 text-amber-800 dark:bg-amber-500/15 dark:text-amber-300'
      : 'bg-emerald-100 text-emerald-800 dark:bg-emerald-500/15 dark:text-emerald-300';
  return <span className={cn('inline-flex shrink-0 items-center gap-1 rounded-full px-1.5 py-px text-[11px] font-medium', cls)}><Icon className="h-3 w-3" aria-hidden />{children}</span>;
}

export interface Threshold { good: number; warn: number }

/** Dial for a single percentage. The arc's colour states a verdict, so the word for it sits right underneath. */
export function Gauge({ value, label, sub, threshold, size = 120 }: { value: number | null | undefined; label: string; sub?: string; threshold?: Threshold; size?: number }) {
  const r = 42;
  const round = 2 * Math.PI * r;
  const arc = round * 0.75;
  const has = value != null && Number.isFinite(value);
  const shown = has ? Math.min(100, Math.max(0, value as number)) : 0;
  const verdict: 'good' | 'warning' | 'critical' | null = !has || !threshold ? null : (value as number) >= threshold.good ? 'good' : (value as number) >= threshold.warn ? 'warning' : 'critical';
  const stroke = verdict ? STATUS[verdict] : 'hsl(var(--primary))';
  const words = { good: tx('On track'), warning: tx('Watch'), critical: tx('Below target') };
  return (
    <figure className="flex flex-col items-center gap-1 text-center">
      <div className="relative" style={{ width: size, height: size * 0.86 }}>
        <svg viewBox="0 0 100 86" className="h-full w-full" role="img" aria-label={`${label}: ${has ? `${shown.toFixed(1)}%` : tx('no data')}`}>
          <g transform="rotate(135 50 50)">
            <circle cx="50" cy="50" r={r} fill="none" stroke="hsl(var(--muted))" strokeWidth="8" strokeDasharray={`${arc} ${round}`} strokeLinecap="round" />
            {has && <circle cx="50" cy="50" r={r} fill="none" stroke={stroke} strokeWidth="8" strokeLinecap="round" strokeDasharray={`${(shown / 100) * arc} ${round}`} className="transition-[stroke-dasharray] duration-1000 motion-reduce:transition-none" />}
          </g>
        </svg>
        <div className="absolute inset-0 flex items-center justify-center pt-1"><span className="text-xl font-semibold">{has ? `${Number.isInteger(shown) ? shown : shown.toFixed(1)}%` : '—'}</span></div>
      </div>
      <figcaption className="min-w-0">
        <div className="text-sm font-medium">{label}</div>
        {verdict && <div className="mt-0.5"><FlagPill tone={verdict}>{words[verdict]}</FlagPill></div>}
        {sub && <div className="mt-0.5 text-[11px] text-muted-foreground">{sub}</div>}
      </figcaption>
    </figure>
  );
}

/** Actual against a target, with the "should be here by today" tick. */
export function Bullet({ label, sub, actual, target, pace, display, status, href }: { label: string; sub?: string; actual: number; target: number; pace?: number; display: string; status?: { tone: 'good' | 'warning' | 'critical'; label: string }; href?: string }) {
  const top = Math.max(target, actual, 1) * 1.05;
  const body = (
    <>
      <div className="flex items-baseline gap-2 text-sm">
        <span className="min-w-0 flex-1 truncate font-medium" title={label}>{label}</span>
        {status && <FlagPill tone={status.tone}>{status.label}</FlagPill>}
        <span className="shrink-0 font-semibold tabular-nums">{display}</span>
      </div>
      <div className="relative mt-1.5 h-3 rounded-full bg-muted">
        <div className="h-full rounded-full bg-primary transition-[width] duration-700 motion-reduce:transition-none" style={{ width: `${(actual / top) * 100}%` }} />
        {pace != null && pace < target && <span className="absolute -inset-y-0.5 w-0.5 rounded bg-muted-foreground/70" style={{ left: `${(pace / top) * 100}%` }} title={tx('Expected by today')} />}
        <span className="absolute -inset-y-1 w-0.5 rounded bg-foreground" style={{ left: `${(target / top) * 100}%` }} title={tx('Target')} />
      </div>
      {sub && <div className="mt-1 truncate text-[11px] text-muted-foreground">{sub}</div>}
    </>
  );
  return href ? <Link to={href} className="-mx-2 block rounded-lg px-2 py-1 hover:bg-muted/60">{body}</Link> : <div className="py-1">{body}</div>;
}

export interface Segment { key: string; label: string; value: number; color: string; ink?: string; href?: string }

/** A pipeline or mix as one tall bar: counts inside the segments that fit them, the legend names every segment. */
export function SegmentBar({ rows, format = (n: number) => String(n) }: { rows: Segment[]; format?: (n: number) => string }) {
  const present = rows.filter((r) => r.value > 0);
  const total = present.reduce((s, r) => s + r.value, 0);
  if (total === 0) return <Empty />;
  return (
    <div className="space-y-3">
      <div className="flex h-11 gap-0.5 overflow-hidden rounded-lg">
        {present.map((r) => {
          const share = r.value / total;
          const inner = share > 0.07 ? <span className="px-1 text-sm font-semibold" style={{ color: r.ink ?? '#ffffff' }}>{format(r.value)}</span> : null;
          const style: CSSProperties = { width: `${share * 100}%`, background: r.color };
          const cls = 'flex min-w-[6px] items-center justify-center transition-[width] duration-700 motion-reduce:transition-none';
          return r.href
            ? <Link key={r.key} to={r.href} className={cn(cls, 'hover:opacity-85')} style={style} title={`${r.label}: ${format(r.value)}`}>{inner}</Link>
            : <div key={r.key} className={cls} style={style} title={`${r.label}: ${format(r.value)}`}>{inner}</div>;
        })}
      </div>
      <Legend items={present.map((r) => ({ label: r.label, color: r.color, value: format(r.value) }))} />
    </div>
  );
}

/** Blue ramp, near zero fading into the surface. */
const RAMP_LIGHT = ['#cde2fb', '#86b6ef', '#3987e5', '#1c5cab', '#0d366b'];
const RAMP_DARK = ['#104281', '#1c5cab', '#2a78d6', '#5598e7', '#9ec5f4'];

/** Activity by day: a column per week, a row per weekday. */
export function CalendarHeatmap({ from, to, values, unit, format = (n: number) => String(n) }: { from: string; to: string; values: Record<string, number>; unit: string; format?: (n: number) => string }) {
  const { theme } = useTheme();
  const ramp = theme === 'dark' ? RAMP_DARK : RAMP_LIGHT;
  const [hover, setHover] = useState<string | null>(null);
  const weeks = bucketsOf({ from, to }, 'week');
  const max = Math.max(0, ...Object.values(values));
  const shade = (v: number) => (v <= 0 || max === 0 ? 'hsl(var(--muted))' : ramp[Math.min(ramp.length - 1, Math.floor((v / max) * ramp.length))]);
  const dayName = (i: number) => new Date(2024, 0, 1 + i).toLocaleDateString(undefined, { weekday: 'short' });
  const activeDays = Object.keys(values).filter((d) => values[d] > 0 && d >= from && d <= to).sort();
  return (
    <div>
      <div className="flex gap-2 overflow-x-auto pb-1">
        <div className="grid shrink-0 grid-rows-7 gap-[3px] pt-[18px] text-[10px] leading-none text-muted-foreground">
          {[0, 1, 2, 3, 4, 5, 6].map((i) => <span key={i} className="flex h-3.5 items-center">{i % 2 === 0 ? dayName(i) : ''}</span>)}
        </div>
        <div className="flex gap-[3px]">
          {weeks.map((w, wi) => {
            const start = new Date(`${w.key}T00:00:00`);
            const showMonth = wi === 0 || start.getDate() <= 7;
            return (
              <div key={w.key} className="w-3.5 shrink-0">
                <div className="h-[18px] overflow-visible whitespace-nowrap text-[10px] leading-none text-muted-foreground">{showMonth ? start.toLocaleDateString(undefined, { month: 'short' }) : ''}</div>
                <div className="grid grid-rows-7 gap-[3px]">
                  {[0, 1, 2, 3, 4, 5, 6].map((d) => {
                    const day = new Date(start);
                    day.setDate(day.getDate() + d);
                    const iso = isoDate(day);
                    if (iso < from || iso > to) return <span key={d} className="h-3.5 w-3.5" />;
                    const v = values[iso] ?? 0;
                    const text = `${day.toLocaleDateString()}: ${format(v)} ${unit}`;
                    return <span key={d} className="h-3.5 w-3.5 rounded-[3px]" style={{ background: shade(v) }} title={text} onMouseEnter={() => setHover(text)} onMouseLeave={() => setHover(null)} />;
                  })}
                </div>
              </div>
            );
          })}
        </div>
      </div>
      <div className="mt-2 flex flex-wrap items-center justify-between gap-2 text-[11px] text-muted-foreground">
        <span className="min-h-4 tabular-nums">{hover ?? tx('Hover a day for its figure')}</span>
        <span className="flex items-center gap-1">{tx('Less')}<span className="h-2.5 w-2.5 rounded-sm bg-muted" />{ramp.map((c) => <span key={c} className="h-2.5 w-2.5 rounded-sm" style={{ background: c }} />)}{tx('More')}</span>
      </div>
      <DataDetails heads={[tx('Date'), unit]} rows={activeDays.map((d) => [d, format(values[d])])} />
    </div>
  );
}

/** A vertical fill per stockpile against its capacity. */
export function Silos({ rows }: { rows: Array<{ key: string; code: string; name: string; value: number; capacity: number | null; display: string; href?: string }> }) {
  if (rows.length === 0) return <Empty />;
  return (
    <div className="flex gap-3 overflow-x-auto pb-1">
      {rows.map((r) => {
        const fill = r.capacity && r.capacity > 0 ? (r.value / r.capacity) * 100 : null;
        const high = fill != null && fill >= 90;
        const body = (
          <>
            <div className="relative h-32 w-full overflow-hidden rounded-b-md rounded-t-xl border bg-muted/50">
              <div className={cn('absolute inset-x-0 bottom-0 transition-[height] duration-1000 motion-reduce:transition-none', !high && 'bg-primary/70')}
                style={{ height: `${fill == null ? 0 : Math.min(100, Math.max(0, fill))}%`, background: high ? STATUS.warning : undefined }} />
              <div className="absolute inset-0 flex items-center justify-center text-sm font-semibold">{fill == null ? '—' : `${fill > 0 && fill < 1 ? '<1' : Math.round(fill)}%`}</div>
            </div>
            <div className="mt-1.5 truncate text-xs font-medium" title={r.name}>{r.code}</div>
            <div className={cn('truncate text-[11px] tabular-nums', r.value < 0 ? 'text-rose-600' : 'text-muted-foreground')}>{r.display}</div>
            {high && <div className="mt-0.5"><FlagPill tone="warning">{tx('Nearly full')}</FlagPill></div>}
          </>
        );
        return r.href
          ? <Link key={r.key} to={r.href} className="w-20 shrink-0 rounded-lg transition-transform hover:-translate-y-0.5 motion-reduce:hover:translate-y-0">{body}</Link>
          : <div key={r.key} className="w-20 shrink-0">{body}</div>;
      })}
    </div>
  );
}

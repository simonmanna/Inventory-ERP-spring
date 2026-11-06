import type { CSSProperties, ReactNode } from 'react';
import { Link } from 'react-router-dom';
import { ArrowRight } from 'lucide-react';
import { Bar, CartesianGrid, Cell, ComposedChart, Line, Pie, PieChart, ResponsiveContainer, Tooltip, Treemap, XAxis, YAxis } from 'recharts';
import { useTheme } from '@/components/theme-provider';
import { money } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';
import { cn } from '@/lib/utils';
import type { DashboardTop } from '@/features/sales/report-center-api';
import { compactNumber, SERIES_DARK, SERIES_LIGHT } from '../reports/report-format';
import { compactMoney, displayLabel } from './utils';

/** Fade-and-rise on first paint, staggered by section. */
export function Reveal({ delay = 0, className, children }: { delay?: number; className?: string; children: ReactNode }) {
  return (
    <div className={cn('animate-in fade-in slide-in-from-bottom-2 fill-mode-both duration-500 motion-reduce:animate-none', className)} style={{ animationDelay: `${delay}ms` }}>
      {children}
    </div>
  );
}

export function Panel({ title, hint, href, action, className, children }: { title: string; hint?: string; href?: string; action?: string; className?: string; children: ReactNode }) {
  return (
    <section className={cn('flex h-full flex-col rounded-2xl border bg-card p-5 shadow-sm', className)}>
      <header className="mb-4 flex items-start justify-between gap-3">
        <div className="min-w-0">
          <h2 className="font-semibold">{title}</h2>
          {hint && <p className="mt-0.5 text-xs text-muted-foreground">{hint}</p>}
        </div>
        {href && <Link to={href} className="inline-flex shrink-0 items-center gap-1 text-xs text-primary hover:underline">{action ?? tx('Details')}<ArrowRight className="h-3 w-3" /></Link>}
      </header>
      <div className="min-h-0 flex-1">{children}</div>
    </section>
  );
}

function monthShort(label: string): string {
  const m = /^(\d{4})-(\d{2})$/.exec(label);
  return m ? new Date(Number(m[1]), Number(m[2]) - 1, 1).toLocaleString(undefined, { month: 'short' }) : label;
}

function monthLong(label: string): string {
  const m = /^(\d{4})-(\d{2})$/.exec(label);
  return m ? new Date(Number(m[1]), Number(m[2]) - 1, 1).toLocaleString(undefined, { month: 'long', year: 'numeric' }) : label;
}

const POPOVER: CSSProperties = { borderRadius: 10, fontSize: 12, background: 'hsl(var(--card))', borderColor: 'hsl(var(--border))', color: 'hsl(var(--card-foreground))' };

/** 12-month net sales and gross profit, drawn in the hero's sidebar colours. Same unit, so one y-scale. */
export function HeroTrend({ data, currency }: { data: Array<{ label: string; revenue: number; margin: number }>; currency?: string }) {
  const ink = 'var(--sb-text)';
  return (
    <ResponsiveContainer width="100%" height="100%">
      <ComposedChart data={data} margin={{ top: 8, right: 4, bottom: 0, left: 0 }} barCategoryGap="28%">
        <CartesianGrid vertical={false} stroke="var(--sb-text)" strokeOpacity={0.1} />
        <XAxis dataKey="label" tickFormatter={monthShort} tick={{ fontSize: 11, fill: ink }} tickLine={false} axisLine={false} minTickGap={4} />
        <YAxis tickFormatter={(v: number) => compactNumber(v)} tick={{ fontSize: 11, fill: ink }} tickLine={false} axisLine={false} width={44} tickCount={4} />
        <Tooltip contentStyle={POPOVER} cursor={{ fill: 'var(--sb-text)', fillOpacity: 0.07 }} labelFormatter={(l: string) => monthLong(l)} formatter={(v: number, name: string) => [money(v, currency), name]} />
        <Bar dataKey="revenue" name={tx('Net sales')} fill="var(--sb-active-bar)" radius={[4, 4, 0, 0]} maxBarSize={30} />
        <Line dataKey="margin" name={tx('Gross profit')} type="monotone" stroke="var(--sb-active)" strokeWidth={2} dot={false} activeDot={{ r: 4 }} />
      </ComposedChart>
    </ResponsiveContainer>
  );
}

function share(part: number, total: number): string {
  if (total <= 0) return '0%';
  const pct = (part / total) * 100;
  return pct > 0 && pct < 1 ? '<1%' : `${Math.round(pct)}%`;
}

/** Share of net sales by channel. Colour follows the channel's fixed position, never its rank in this period. */
export function ChannelDonut({ rows, order, currency, total }: { rows: DashboardTop[]; order: string[]; currency?: string; total: number }) {
  const { theme } = useTheme();
  const palette = theme === 'dark' ? SERIES_DARK : SERIES_LIGHT;
  const data = rows.filter((r) => Number(r.revenue) > 0);
  if (data.length === 0) return <Empty />;
  const color = (r: DashboardTop, i: number) => {
    const at = order.indexOf(String(r.id));
    return palette[(at >= 0 ? at : i) % palette.length];
  };
  return (
    <div className="flex flex-col items-center gap-4 sm:flex-row xl:flex-col 2xl:flex-row">
      <div className="relative h-44 w-44 shrink-0">
        <ResponsiveContainer width="100%" height="100%">
          <PieChart>
            <Pie data={data} dataKey="revenue" nameKey="label" innerRadius="70%" outerRadius="100%" paddingAngle={data.length > 1 ? 2 : 0} stroke="hsl(var(--card))" strokeWidth={2}>
              {data.map((r, i) => <Cell key={r.id ?? i} fill={color(r, i)} />)}
            </Pie>
            <Tooltip contentStyle={POPOVER} formatter={(v: number, name: string) => [money(v, currency), name]} />
          </PieChart>
        </ResponsiveContainer>
        <div className="pointer-events-none absolute inset-0 flex flex-col items-center justify-center text-center">
          <span className="text-lg font-semibold tabular-nums">{compactNumber(total)}</span>
          <span className="text-[11px] text-muted-foreground">{currency ?? tx('Net sales')}</span>
        </div>
      </div>
      <ul className="w-full min-w-0 flex-1 space-y-2">
        {data.map((r, i) => (
          <li key={r.id ?? i} className="flex items-center gap-2 text-sm">
            <span className="h-2.5 w-2.5 shrink-0 rounded-full" style={{ background: color(r, i) }} />
            <span className="min-w-0 flex-1 truncate">{r.label ?? tx('Other')}</span>
            <span className="shrink-0 tabular-nums text-muted-foreground">{share(Number(r.revenue), total)}</span>
          </li>
        ))}
      </ul>
    </div>
  );
}

// Fixed one-hue ramp: each step carries the text colour that stays readable on it, in every theme.
const RAMP: Array<{ min: number; fill: string; ink: string }> = [
  { min: 0.55, fill: '#174f96', ink: '#ffffff' },
  { min: 0.25, fill: '#2a6fc4', ink: '#ffffff' },
  { min: 0.1, fill: '#86b4ea', ink: '#0d2747' },
  { min: 0, fill: '#c6dcf6', ink: '#0d2747' },
];

interface CellProps { x?: number; y?: number; width?: number; height?: number; label?: string; revenue?: number; max: number }

function CategoryCell({ x = 0, y = 0, width = 0, height = 0, label, revenue = 0, max }: CellProps) {
  if (!label) return null;
  const step = RAMP.find((s) => revenue / max >= s.min) ?? RAMP[RAMP.length - 1];
  const chars = Math.floor((width - 16) / 7);
  const text = label.length > chars ? `${label.slice(0, Math.max(0, chars - 1))}…` : label;
  return (
    <g>
      <rect x={x} y={y} width={width} height={height} rx={6} fill={step.fill} stroke="hsl(var(--card))" strokeWidth={2} />
      {width > 64 && height > 30 && <text x={x + 9} y={y + 19} fontSize={12} fontWeight={600} fill={step.ink} stroke="none">{text}</text>}
      {width > 64 && height > 50 && <text x={x + 9} y={y + 35} fontSize={11} fill={step.ink} fillOpacity={0.85} stroke="none">{compactNumber(revenue)}</text>}
    </g>
  );
}

/** Product categories by net sales: area and shade both encode size. */
export function CategoryTreemap({ rows, currency }: { rows: DashboardTop[]; currency?: string }) {
  const data = rows.filter((r) => Number(r.revenue) > 0).map((r) => ({ ...r, label: displayLabel(r.label) }));
  if (data.length === 0) return <Empty />;
  const max = Math.max(...data.map((r) => Number(r.revenue)));
  return (
    <ResponsiveContainer width="100%" height={240}>
      <Treemap data={data} dataKey="revenue" nameKey="label" isAnimationActive={false} content={<CategoryCell max={max} />}>
        <Tooltip contentStyle={POPOVER} formatter={(v: number) => [money(v, currency), tx('Net sales')]} />
      </Treemap>
    </ResponsiveContainer>
  );
}

/** Ranked list with a share bar and each row's margin, for the people who manage these names. */
export function Leaderboard({ title, rows, href, currency, drillHref }: { title: string; rows: DashboardTop[]; href: string; currency?: string; drillHref: (row: DashboardTop) => string | undefined }) {
  const max = Math.max(1, ...rows.map((r) => Number(r.revenue) || 0));
  return (
    <Panel title={title} href={href} action={tx('Full report')}>
      {rows.length === 0 && <Empty />}
      <ol className="space-y-3">
        {rows.map((r, i) => {
          const revenue = Number(r.revenue) || 0;
          const pct = revenue > 0 ? Math.round((Number(r.margin) / revenue) * 100) : null;
          const to = drillHref(r);
          const body = (
            <>
              <div className="flex items-center gap-2.5 text-sm">
                <span className={cn('flex h-6 w-6 shrink-0 items-center justify-center rounded-full text-xs font-semibold tabular-nums', i === 0 ? 'bg-primary text-primary-foreground' : 'bg-muted text-muted-foreground')}>{i + 1}</span>
                <span className="min-w-0 flex-1 truncate font-medium">{displayLabel(r.label)}</span>
                <span className="shrink-0 font-semibold tabular-nums" title={money(revenue, currency)}>{compactMoney(revenue, currency)}</span>
              </div>
              <div className="mt-1.5 flex items-center gap-2 ps-[34px]">
                <div className="h-1.5 flex-1 overflow-hidden rounded-full bg-muted">
                  <div className="h-full rounded-full bg-primary transition-[width] duration-700" style={{ width: `${Math.max(2, (revenue / max) * 100)}%` }} />
                </div>
                {pct != null && <span className="w-20 shrink-0 text-end text-[11px] tabular-nums text-muted-foreground">{pct}% {tx('margin')}</span>}
              </div>
            </>
          );
          return <li key={r.id ?? i}>{to ? <Link to={to} className="-mx-2 block rounded-lg px-2 py-1 transition-colors hover:bg-muted/60">{body}</Link> : <div className="py-1">{body}</div>}</li>;
        })}
      </ol>
    </Panel>
  );
}

export function Empty() {
  return <p className="py-8 text-center text-sm text-muted-foreground">{tx('No sales in this period.')}</p>;
}

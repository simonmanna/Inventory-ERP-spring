import type { CSSProperties } from 'react';
import { Link } from 'react-router-dom';
import { Bar, CartesianGrid, Cell, ComposedChart, Line, Pie, PieChart, ResponsiveContainer, Tooltip, Treemap, XAxis, YAxis } from 'recharts';
import { useTheme } from '@/components/theme-provider';
import { money } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';
import { cn } from '@/lib/utils';
import type { ProcurementDashboardRow } from '@/features/procurement/api';
import { compactNumber, SERIES_DARK, SERIES_LIGHT } from '@/pages/sales/reports/report-format';
import { compactMoney, displayLabel } from '@/pages/sales/dashboard/utils';
import { Panel } from '@/pages/sales/dashboard/widgets';

const POPOVER: CSSProperties = { borderRadius: 10, fontSize: 12, background: 'hsl(var(--card))', borderColor: 'hsl(var(--border))', color: 'hsl(var(--card-foreground))' };

function monthShort(label: string): string {
  const m = /^(\d{4})-(\d{2})$/.exec(label);
  return m ? new Date(Number(m[1]), Number(m[2]) - 1, 1).toLocaleString(undefined, { month: 'short' }) : label;
}

function monthLong(label: string): string {
  const m = /^(\d{4})-(\d{2})$/.exec(label);
  return m ? new Date(Number(m[1]), Number(m[2]) - 1, 1).toLocaleString(undefined, { month: 'long', year: 'numeric' }) : label;
}

/** 12 months of ordered (bars) and billed (line), drawn in the hero's sidebar colours. */
export function SpendTrend({ data, currency }: { data: Array<{ label: string; ordered: number; billed: number }>; currency?: string }) {
  const ink = 'var(--sb-text)';
  return (
    <ResponsiveContainer width="100%" height="100%">
      <ComposedChart data={data} margin={{ top: 8, right: 4, bottom: 0, left: 0 }} barCategoryGap="28%">
        <CartesianGrid vertical={false} stroke="var(--sb-text)" strokeOpacity={0.1} />
        <XAxis dataKey="label" tickFormatter={monthShort} tick={{ fontSize: 11, fill: ink }} tickLine={false} axisLine={false} minTickGap={4} />
        <YAxis tickFormatter={(v: number) => compactNumber(v)} tick={{ fontSize: 11, fill: ink }} tickLine={false} axisLine={false} width={44} tickCount={4} />
        <Tooltip contentStyle={POPOVER} cursor={{ fill: 'var(--sb-text)', fillOpacity: 0.07 }} labelFormatter={(l: string) => monthLong(l)} formatter={(v: number, name: string) => [money(v, currency), name]} />
        <Bar dataKey="ordered" name={tx('Ordered')} fill="var(--sb-active-bar)" radius={[4, 4, 0, 0]} maxBarSize={30} />
        <Line dataKey="billed" name={tx('Billed by suppliers')} type="monotone" stroke="var(--sb-active)" strokeWidth={2} dot={false} activeDot={{ r: 4 }} />
      </ComposedChart>
    </ResponsiveContainer>
  );
}

function share(part: number, total: number): string {
  if (total <= 0) return '0%';
  const pct = (part / total) * 100;
  return pct > 0 && pct < 1 ? '<1%' : `${Math.round(pct)}%`;
}

/** Share of spend by supplier: the top names, the rest folded into "Other suppliers". */
export function SupplierDonut({ rows, total, currency }: { rows: ProcurementDashboardRow[]; total: number; currency?: string }) {
  const { theme } = useTheme();
  const palette = theme === 'dark' ? SERIES_DARK : SERIES_LIGHT;
  const top = rows.filter((r) => r.spend > 0).slice(0, 5);
  const rest = Math.max(0, total - top.reduce((s, r) => s + r.spend, 0));
  const data = [...top.map((r) => ({ label: displayLabel(r.label), spend: r.spend })), ...(rest > 0.5 ? [{ label: tx('Other suppliers'), spend: rest }] : [])];
  if (data.length === 0) return <Empty />;
  return (
    <div className="flex flex-col items-center gap-4 sm:flex-row xl:flex-col 2xl:flex-row">
      <div className="relative h-44 w-44 shrink-0">
        <ResponsiveContainer width="100%" height="100%">
          <PieChart>
            <Pie data={data} dataKey="spend" nameKey="label" innerRadius="70%" outerRadius="100%" paddingAngle={data.length > 1 ? 2 : 0} stroke="hsl(var(--card))" strokeWidth={2}>
              {data.map((r, i) => <Cell key={r.label} fill={i === top.length ? 'hsl(var(--muted-foreground))' : palette[i % palette.length]} />)}
            </Pie>
            <Tooltip contentStyle={POPOVER} formatter={(v: number, name: string) => [money(v, currency), name]} />
          </PieChart>
        </ResponsiveContainer>
        <div className="pointer-events-none absolute inset-0 flex flex-col items-center justify-center text-center">
          <span className="text-lg font-semibold tabular-nums">{compactNumber(total)}</span>
          <span className="text-[11px] text-muted-foreground">{currency ?? tx('Spend')}</span>
        </div>
      </div>
      <ul className="w-full min-w-0 flex-1 space-y-2">
        {data.map((r, i) => (
          <li key={r.label} className="flex items-center gap-2 text-sm">
            <span className="h-2.5 w-2.5 shrink-0 rounded-full" style={{ background: i === top.length ? 'hsl(var(--muted-foreground))' : palette[i % palette.length] }} />
            <span className="min-w-0 flex-1 truncate">{r.label}</span>
            <span className="shrink-0 tabular-nums text-muted-foreground">{share(r.spend, total)}</span>
          </li>
        ))}
      </ul>
    </div>
  );
}

const RAMP: Array<{ min: number; fill: string; ink: string }> = [
  { min: 0.55, fill: '#0f5c4a', ink: '#ffffff' },
  { min: 0.25, fill: '#1b8a6c', ink: '#ffffff' },
  { min: 0.1, fill: '#7fcfb5', ink: '#0b2e25' },
  { min: 0, fill: '#c4ebdd', ink: '#0b2e25' },
];

interface CellProps { x?: number; y?: number; width?: number; height?: number; label?: string; spend?: number; max: number }

function SpendCell({ x = 0, y = 0, width = 0, height = 0, label, spend = 0, max }: CellProps) {
  if (!label) return null;
  const step = RAMP.find((s) => spend / max >= s.min) ?? RAMP[RAMP.length - 1];
  const chars = Math.floor((width - 16) / 7);
  const text = label.length > chars ? `${label.slice(0, Math.max(0, chars - 1))}…` : label;
  return (
    <g>
      <rect x={x} y={y} width={width} height={height} rx={6} fill={step.fill} stroke="hsl(var(--card))" strokeWidth={2} />
      {width > 64 && height > 30 && <text x={x + 9} y={y + 19} fontSize={12} fontWeight={600} fill={step.ink} stroke="none">{text}</text>}
      {width > 64 && height > 50 && <text x={x + 9} y={y + 35} fontSize={11} fill={step.ink} fillOpacity={0.85} stroke="none">{compactNumber(spend)}</text>}
    </g>
  );
}

/** Product categories by spend: area and shade both encode size. */
export function CategoryTreemap({ rows, currency }: { rows: ProcurementDashboardRow[]; currency?: string }) {
  const data = rows.filter((r) => r.spend > 0).map((r) => ({ label: r.label === '(none)' ? tx('Uncategorised') : displayLabel(r.label), spend: r.spend }));
  if (data.length === 0) return <Empty />;
  const max = Math.max(...data.map((r) => r.spend));
  return (
    <ResponsiveContainer width="100%" height={240}>
      <Treemap data={data} dataKey="spend" nameKey="label" isAnimationActive={false} content={<SpendCell max={max} />}>
        <Tooltip contentStyle={POPOVER} formatter={(v: number) => [money(v, currency), tx('Spend')]} />
      </Treemap>
    </ResponsiveContainer>
  );
}

/** Ranked list with a share bar; {@code sub} gives each row its second figure. */
export function Ranked({ title, hint, rows, href, currency, sub, rowHref }: {
  title: string; hint?: string; rows: ProcurementDashboardRow[]; href?: string; currency?: string;
  sub: (row: ProcurementDashboardRow) => string; rowHref?: (row: ProcurementDashboardRow) => string | undefined;
}) {
  const max = Math.max(1, ...rows.map((r) => r.spend || 0));
  return (
    <Panel title={title} hint={hint} href={href} action={tx('Open')}>
      {rows.length === 0 && <Empty />}
      <ol className="space-y-3">
        {rows.map((r, i) => {
          const to = rowHref?.(r);
          const body = (
            <>
              <div className="flex items-center gap-2.5 text-sm">
                <span className={cn('flex h-6 w-6 shrink-0 items-center justify-center rounded-full text-xs font-semibold tabular-nums', i === 0 ? 'bg-primary text-primary-foreground' : 'bg-muted text-muted-foreground')}>{i + 1}</span>
                <span className="min-w-0 flex-1 truncate font-medium">{displayLabel(r.label)}</span>
                <span className="shrink-0 font-semibold tabular-nums" title={money(r.spend, currency)}>{compactMoney(r.spend, currency)}</span>
              </div>
              <div className="mt-1.5 flex items-center gap-2 ps-[34px]">
                <div className="h-1.5 flex-1 overflow-hidden rounded-full bg-muted">
                  <div className="h-full rounded-full bg-primary transition-[width] duration-700" style={{ width: `${Math.max(2, (r.spend / max) * 100)}%` }} />
                </div>
                <span className="w-28 shrink-0 truncate text-end text-[11px] tabular-nums text-muted-foreground">{sub(r)}</span>
              </div>
            </>
          );
          return <li key={r.id ?? `${r.label}-${i}`}>{to ? <Link to={to} className="-mx-2 block rounded-lg px-2 py-1 transition-colors hover:bg-muted/60">{body}</Link> : <div className="py-1">{body}</div>}</li>;
        })}
      </ol>
    </Panel>
  );
}

/** Ordered → received → billed → paid in the period, each as a bar against the biggest. */
export function MoneyFlow({ steps, currency }: { steps: Array<{ key: string; label: string; value: number; note?: string; to: string }>; currency?: string }) {
  const max = Math.max(1, ...steps.map((s) => s.value));
  return (
    <ul className="space-y-3.5">
      {steps.map((s, i) => (
        <li key={s.key}>
          <Link to={s.to} className="-mx-2 block rounded-lg px-2 py-1 transition-colors hover:bg-muted/60">
            <div className="flex items-baseline justify-between gap-2 text-sm">
              <span className="font-medium">{s.label}</span>
              <span className="font-semibold tabular-nums" title={money(s.value, currency)}>{compactMoney(s.value, currency)}</span>
            </div>
            <div className="mt-1.5 h-2.5 overflow-hidden rounded-full bg-muted">
              <div className="h-full rounded-full transition-[width] duration-700" style={{ width: `${Math.max(1.5, (s.value / max) * 100)}%`, background: SERIES_LIGHT[i % SERIES_LIGHT.length] }} />
            </div>
            {s.note && <span className="mt-1 block text-[11px] text-muted-foreground">{s.note}</span>}
          </Link>
        </li>
      ))}
    </ul>
  );
}

/** Supplier grades A–D as one stacked bar with counts. */
export function GradeBar({ grades }: { grades: Record<'A' | 'B' | 'C' | 'D', number> }) {
  const total = Object.values(grades).reduce((s, n) => s + n, 0);
  const tone: Record<string, string> = { A: 'bg-emerald-500', B: 'bg-sky-500', C: 'bg-amber-500', D: 'bg-rose-500' };
  if (total === 0) return <p className="text-sm text-muted-foreground">{tx('No supplier orders in this period.')}</p>;
  return (
    <div>
      <div className="flex h-3 overflow-hidden rounded-full bg-muted">
        {(['A', 'B', 'C', 'D'] as const).map((g) => grades[g] > 0 && <div key={g} className={tone[g]} style={{ width: `${(grades[g] / total) * 100}%` }} title={`${g}: ${grades[g]}`} />)}
      </div>
      <div className="mt-2 grid grid-cols-4 gap-1 text-center text-xs">
        {(['A', 'B', 'C', 'D'] as const).map((g) => (
          <span key={g} className="flex items-center justify-center gap-1.5"><span className={cn('h-2 w-2 rounded-full', tone[g])} />{g} · <span className="tabular-nums font-medium">{grades[g]}</span></span>
        ))}
      </div>
    </div>
  );
}

export function Empty() {
  return <p className="py-8 text-center text-sm text-muted-foreground">{tx('No purchases in this period.')}</p>;
}

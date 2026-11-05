import { useMemo } from 'react';
import {
  Area, Bar, CartesianGrid, Cell, ComposedChart, Legend, Line, Pie, PieChart, ResponsiveContainer, Tooltip, XAxis, YAxis,
} from 'recharts';
import { useTheme } from '@/components/theme-provider';
import { tx } from '@/lib/i18n/tx';
import type { ColumnType, ReportColumn, ReportChartSpec, ReportRow } from '@/features/sales/report-center-api';
import { compactNumber, formatCell, SERIES_DARK, SERIES_LIGHT } from './report-format';

interface Props {
  spec: ReportChartSpec;
  rows: ReportRow[];
  columns: ReportColumn[];
  height?: number;
  onSelect?: (row: ReportRow) => void;
}

const MAX_POINTS = 60;

function unitOf(key: string, columns: ReportColumn[], rows: ReportRow[]): ColumnType {
  const c = columns.find((x) => x.key === key);
  if (c && c.type !== 'auto') return c.type;
  if (/percent|rate|share|achievement|index|margin%|ratio/i.test(key)) return 'percent';
  if (/count|trips|deals|orders|receipts|invoices|returns|lines|customers|products|delivered$|on_time|late|no_due_date/i.test(key)) {
    return 'count';
  }
  const v = rows.find((r) => typeof r[key] === 'number')?.[key];
  return typeof v === 'number' && !Number.isInteger(v) ? 'money' : 'number';
}

function short(v: unknown, n = 22): string {
  const s = String(v ?? '—');
  return s.length > n ? `${s.slice(0, n - 1)}…` : s;
}

/**
 * Graphical view of a report: one y-scale only (series whose unit differs from the first are left to the table),
 * the validated categorical palette in fixed order, legends from two series on, hover tooltips on every mark.
 */
export function ReportChart({ spec, rows, columns, height = 320, onSelect }: Props) {
  const { theme } = useTheme();
  const palette = theme === 'dark' ? SERIES_DARK : SERIES_LIGHT;
  const grid = theme === 'dark' ? '#383835' : '#e7e6e2';
  const ink = theme === 'dark' ? '#c3c2b7' : '#52514e';

  const { series, unit, data } = useMemo(() => {
    const all = spec.series ?? [];
    const first = all[0] ? unitOf(all[0].key, columns, rows) : 'number';
    const same = (u: ColumnType) => u === first || (['money', 'number', 'qty'].includes(u) && ['money', 'number', 'qty'].includes(first));
    const kept = all.filter((s) => same(unitOf(s.key, columns, rows))).slice(0, 8);
    let d = rows.filter((r) => r[spec.x ?? 'label'] !== undefined);
    if (spec.kind === 'pie') {
      const k = kept[0]?.key;
      d = d.filter((r) => Number(r[k ?? '']) > 0);
      if (d.length > 7 && k) {
        const sorted = [...d].sort((a, b) => Number(b[k]) - Number(a[k]));
        const other = sorted.slice(6).reduce((s, r) => s + Number(r[k] ?? 0), 0);
        d = [...sorted.slice(0, 6), { [spec.x ?? 'label']: tx('Other'), [k]: other }];
      }
    } else if (d.length > MAX_POINTS) {
      d = d.slice(0, MAX_POINTS);
    }
    return { series: kept, unit: first, data: d };
  }, [spec, rows, columns]);

  if (spec.kind === 'none' || series.length === 0 || data.length === 0) {
    return <div className="flex h-40 items-center justify-center text-sm text-muted-foreground">{tx('No chart for this data.')}</div>;
  }
  const x = spec.x ?? 'label';
  const fmt = (v: unknown) => formatCell(v, unit);
  const tick = { fontSize: 11, fill: ink };
  const tooltip = (
    <Tooltip
      formatter={(v: unknown, name: string) => [fmt(v), name]}
      contentStyle={{ borderRadius: 8, fontSize: 12, background: theme === 'dark' ? '#1a1a19' : '#fcfcfb', borderColor: grid }}
      cursor={{ fill: theme === 'dark' ? 'rgba(255,255,255,0.04)' : 'rgba(0,0,0,0.04)' }}
    />
  );
  const legend = series.length > 1 ? <Legend wrapperStyle={{ fontSize: 12 }} iconType="circle" iconSize={8} /> : null;
  const click = onSelect ? (p: { payload?: ReportRow }) => p?.payload && onSelect(p.payload) : undefined;

  if (spec.kind === 'pie') {
    const k = series[0].key;
    return (
      <ResponsiveContainer width="100%" height={height}>
        <PieChart>
          <Pie data={data} dataKey={k} nameKey={x} innerRadius="52%" outerRadius="82%" paddingAngle={1} stroke={theme === 'dark' ? '#1a1a19' : '#fcfcfb'} strokeWidth={2} onClick={click}>
            {data.map((_, i) => <Cell key={i} fill={palette[i % palette.length]} className={onSelect ? 'cursor-pointer' : undefined} />)}
          </Pie>
          {tooltip}
          <Legend wrapperStyle={{ fontSize: 12 }} iconType="circle" iconSize={8} formatter={(v: unknown, _e: unknown, i: number) => {
            const total = data.reduce((s, r) => s + Number(r[k] ?? 0), 0);
            return `${short(v, 26)} · ${total ? Math.round((Number(data[i]?.[k] ?? 0) / total) * 100) : 0}%`;
          }} />
        </PieChart>
      </ResponsiveContainer>
    );
  }

  const horizontal = spec.kind === 'hbar';
  const h = horizontal ? Math.max(height, data.length * (series.length > 1 ? 34 : 26) + 60) : height;
  const renderSeries = series.map((s, i) => {
    const color = palette[i % palette.length];
    const kind = spec.kind === 'line' ? 'line' : spec.kind === 'area' ? 'area' : spec.kind === 'combo' ? s.kind : 'bar';
    if (kind === 'line') {
      return <Line key={s.key} type="monotone" dataKey={s.key} name={s.label} stroke={color} strokeWidth={2} dot={data.length <= 24 ? { r: 3 } : false} activeDot={{ r: 5 }} connectNulls />;
    }
    if (kind === 'area') {
      return <Area key={s.key} type="monotone" dataKey={s.key} name={s.label} stroke={color} fill={color} fillOpacity={0.15} strokeWidth={2} />;
    }
    return (
      <Bar key={s.key} dataKey={s.key} name={s.label} fill={color} stackId={spec.stacked ? 'a' : undefined} maxBarSize={horizontal ? 18 : 42}
        radius={spec.stacked ? 0 : horizontal ? [0, 4, 4, 0] : [4, 4, 0, 0]} onClick={click} className={onSelect ? 'cursor-pointer' : undefined} />
    );
  });
  return (
    <ResponsiveContainer width="100%" height={h}>
      <ComposedChart data={data} layout={horizontal ? 'vertical' : 'horizontal'} margin={{ top: 8, right: 16, bottom: 4, left: 4 }} barGap={2}>
        <CartesianGrid strokeDasharray="3 3" stroke={grid} vertical={horizontal} horizontal={!horizontal} />
        {horizontal ? (
          <>
            <XAxis type="number" tick={tick} tickFormatter={(v: number) => compactNumber(v)} stroke={grid} />
            <YAxis type="category" dataKey={x} tick={tick} width={150} tickFormatter={(v: unknown) => short(v)} stroke={grid} interval={0} />
          </>
        ) : (
          <>
            <XAxis dataKey={x} tick={tick} tickFormatter={(v: unknown) => short(v, 14)} stroke={grid} minTickGap={8} />
            <YAxis tick={tick} width={64} tickFormatter={(v: number) => (unit === 'percent' ? `${v}%` : compactNumber(v))} stroke={grid} />
          </>
        )}
        {tooltip}
        {legend}
        {renderSeries}
      </ComposedChart>
    </ResponsiveContainer>
  );
}

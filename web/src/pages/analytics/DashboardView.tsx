import {
  Bar, BarChart, CartesianGrid, Legend, Line, LineChart, ResponsiveContainer, Tooltip, XAxis, YAxis,
} from 'recharts';
import { Card } from '@/components/ui/card';
import { DataTable, type Column as TableColumn } from '@/components/data-table';
import { tx } from '@/lib/i18n/tx';
import { formatValue, kpiLabel, noteLabel } from './labels';
import type { Chart, Dashboard, DashboardTable, Row, Tile } from './api';

/** What a tile or table row asks to open: a document list, with an extra filter from a row. */
export interface Drill { kpi: string; title: string; extra?: Record<string, string> }

// Categorical slots of the dashboards, validated for colour-blind separation on the light and dark card surface.
// Hues keep this order and never cycle; each series also has a legend entry.
const THEME = '[--series-1:#2a78d6] [--series-2:#eb6834] [--series-3:#1baf7a] [--series-4:#eda100] [--series-5:#e87ba4] '
  + 'dark:[--series-1:#3987e5] dark:[--series-2:#d95926] dark:[--series-3:#199e70] dark:[--series-4:#c98500] dark:[--series-5:#d55181]';
const seriesColor = (i: number) => `var(--series-${i + 1})`;

/** Rows a table may not drill from: the buckets for documents with no customer or branch. */
const NO_DRILL = new Set(['none', 'unassigned', 'total']);

function TileCard({ tile, onDrill }: { tile: Tile; onDrill: (d: Drill) => void }) {
  const body = (
    <>
      <div className="text-xs text-muted-foreground">{kpiLabel(tile.key)}</div>
      <div className="text-xl font-semibold tabular-nums">{formatValue(tile.value, tile.format)}</div>
    </>
  );
  if (!tile.drill) return <div className="rounded-xl border bg-card p-3 shadow-sm">{body}</div>;
  const kpi = tile.drill;
  return (
    <button type="button" className="rounded-xl border bg-card p-3 text-left shadow-sm hover:border-primary/50"
      onClick={() => onDrill({ kpi, title: kpiLabel(kpi) })}>
      {body}
    </button>
  );
}

/** Tile row only, for the overview. */
export function TileGrid({ tiles, onDrill }: { tiles: Tile[]; onDrill: (d: Drill) => void }) {
  return (
    <div className="grid grid-cols-2 gap-3 sm:grid-cols-3 lg:grid-cols-4">
      {tiles.map((t) => <TileCard key={t.key} tile={t} onDrill={onDrill} />)}
    </div>
  );
}

function ChartCard({ chart }: { chart: Chart }) {
  const rows = chart.rows.map((r) => {
    const out: Row = { ...r };
    for (const s of chart.series) out[s.key] = r[s.key] === null || r[s.key] === undefined ? null : Number(r[s.key]);
    return out;
  });
  const name = (s: Chart['series'][number]) => s.label ?? kpiLabel(s.key);
  const axis = (
    <>
      <CartesianGrid strokeDasharray="3 3" vertical={false} className="stroke-muted" />
      <XAxis dataKey={chart.xKey} fontSize={11} minTickGap={16} tickFormatter={(v: string) => kpiLabel(String(v))} />
      <YAxis fontSize={11} width={70} tickFormatter={(v: number) => v.toLocaleString(undefined, { notation: 'compact' })} />
      <Tooltip formatter={(v: number) => formatValue(v, chart.format)} labelFormatter={(l: string) => kpiLabel(String(l))} />
      {chart.series.length > 1 && <Legend />}
    </>
  );
  return (
    <Card className="p-4">
      <h3 className="mb-3 text-sm font-medium">{kpiLabel(chart.key)}</h3>
      {rows.length === 0 ? (
        <p className="py-10 text-center text-sm text-muted-foreground">{tx('No data for this period')}</p>
      ) : (
        <div className="h-64">
          <ResponsiveContainer width="100%" height="100%">
            {chart.type === 'line' ? (
              <LineChart data={rows} margin={{ top: 5, right: 10, left: 0, bottom: 0 }}>
                {axis}
                {chart.series.map((s, i) => (
                  <Line key={s.key} dataKey={s.key} name={name(s)} stroke={seriesColor(i)} strokeWidth={2}
                    dot={{ r: 4 }} connectNulls type="monotone" />
                ))}
              </LineChart>
            ) : (
              <BarChart data={rows} margin={{ top: 5, right: 10, left: 0, bottom: 0 }} barGap={2} maxBarSize={32}>
                {axis}
                {chart.series.map((s, i) => (
                  <Bar key={s.key} dataKey={s.key} name={name(s)} fill={seriesColor(i)} radius={[4, 4, 0, 0]} />
                ))}
              </BarChart>
            )}
          </ResponsiveContainer>
        </div>
      )}
      {rows.length > 0 && (
        <details className="mt-2 text-xs text-muted-foreground">
          <summary className="cursor-pointer">{tx('Show data')}</summary>
          <div className="mt-2 overflow-x-auto">
            <table className="w-full tabular-nums">
              <thead>
                <tr>
                  <th className="pr-3 text-left font-medium" />
                  {chart.series.map((s) => <th key={s.key} className="pr-3 text-right font-medium">{name(s)}</th>)}
                </tr>
              </thead>
              <tbody>
                {rows.map((r) => (
                  <tr key={String(r[chart.xKey])}>
                    <td className="pr-3">{kpiLabel(String(r[chart.xKey]))}</td>
                    {chart.series.map((s) => <td key={s.key} className="pr-3 text-right">{formatValue(r[s.key], chart.format)}</td>)}
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </details>
      )}
    </Card>
  );
}

function TableCard({ table, onDrill }: { table: DashboardTable; onDrill: (d: Drill) => void }) {
  const columns: TableColumn<Row>[] = table.columns.map((c) => ({
    key: c.key,
    header: kpiLabel(c.key),
    className: c.format === 'text' ? undefined : 'text-right',
    render: (r) => formatValue(r[c.key], c.format),
  }));
  const { drill, drillParam, rowKey } = table;
  const drillable = (r: Row) => !!drill && !!drillParam && !!rowKey && !!r[rowKey] && !NO_DRILL.has(String(r[rowKey]));
  return (
    <Card className="p-4">
      <h3 className="mb-3 text-sm font-medium">{kpiLabel(table.key)}</h3>
      <div className="overflow-x-auto">
        <DataTable columns={columns} data={table.rows} compact
          emptyMessage={tx('No data for this period')}
          onRowClick={drill && drillParam && rowKey ? (r) => {
            if (drillable(r)) {
              onDrill({
                kpi: drill,
                title: `${kpiLabel(drill)}: ${String(r[table.columns[0].key] ?? '')}`,
                extra: { [drillParam]: String(r[rowKey]) },
              });
            }
          } : undefined} />
      </div>
    </Card>
  );
}

/** A dashboard from the server: tiles, charts, tables and caveats. */
export function DashboardView({ dashboard, onDrill }: { dashboard: Dashboard; onDrill: (d: Drill) => void }) {
  return (
    <div className={`space-y-4 ${THEME}`}>
      {dashboard.ignoredFilters.length > 0 && (
        <p className="text-sm text-muted-foreground">
          {tx('Not applied on this dashboard: {{list}}', { list: dashboard.ignoredFilters.map(kpiLabel).join(', ') })}
        </p>
      )}
      <TileGrid tiles={dashboard.tiles} onDrill={onDrill} />
      <div className="grid gap-4 lg:grid-cols-2">
        {dashboard.charts.map((c) => <ChartCard key={c.key} chart={c} />)}
      </div>
      {dashboard.tables.map((t) => <TableCard key={t.key} table={t} onDrill={onDrill} />)}
      {dashboard.notes.length > 0 && (
        <ul className="list-disc space-y-1 pl-5 text-xs text-muted-foreground">
          {dashboard.notes.map((n) => <li key={n}>{noteLabel(n)}</li>)}
        </ul>
      )}
    </div>
  );
}

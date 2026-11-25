import { useState } from 'react';
import {
  Bar, BarChart, CartesianGrid, ComposedChart, Legend, Line, LineChart, ReferenceLine, ResponsiveContainer, Tooltip, XAxis, YAxis,
} from 'recharts';
import { BarChart3 } from 'lucide-react';
import { Input } from '@/components/ui/input';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Skeleton } from '@/components/ui/skeleton';
import { FilterSelect, ListPageHeader } from '@/components/list';
import { tx } from '@/lib/i18n/tx';
import { cn } from '@/lib/utils';
import {
  useControlPoints, useQcPareto, useQcSpc, useQcSummary, type ParetoDimension, type QcSpc, type SpcPoint,
} from '@/features/quality-control/api';

const AXIS = { fontSize: 11, fill: 'hsl(var(--muted-foreground))' };
const GRID = 'hsl(var(--border))';
const PASS = 'hsl(var(--primary))';
const FAIL = 'hsl(var(--destructive))';
const SPEC = 'hsl(var(--muted-foreground))';

const DIMENSIONS: Array<{ value: ParetoDimension; label: string }> = [
  { value: 'failure_class', label: 'Failure class' },
  { value: 'control_point', label: 'Control point' },
  { value: 'product', label: 'Product' },
  { value: 'work_center', label: 'Work centre' },
  { value: 'team', label: 'Team' },
];

const isoDaysAgo = (d: number) => new Date(Date.now() - d * 86400000).toISOString().slice(0, 10);

/** Quality statistics (ADR-S087 §12): pass rate trend, Pareto of failures, and SPC charts of measure points. */
export function StatisticsPage() {
  const [from, setFrom] = useState(isoDaysAgo(89));
  const [to, setTo] = useState(isoDaysAgo(0));
  const [interval, setInterval] = useState('week');
  const [dimension, setDimension] = useState<ParetoDimension>('failure_class');
  const summary = useQcSummary({ from, to, interval });
  const pareto = useQcPareto({ from, to, dimension });
  const s = summary.data;
  return (
    <div className="space-y-4">
      <ListPageHeader icon={BarChart3} title={tx('Quality Statistics')}
        description={tx('Pass rate, where failures come from, and whether measured processes are in statistical control.')} />
      <div className="flex flex-wrap items-end gap-2">
        <label className="text-xs text-muted-foreground">{tx('From')}<Input type="date" className="h-9 w-40" value={from} onChange={(e) => setFrom(e.target.value)} /></label>
        <label className="text-xs text-muted-foreground">{tx('To')}<Input type="date" className="h-9 w-40" value={to} onChange={(e) => setTo(e.target.value)} /></label>
        <FilterSelect value={interval} onChange={(v) => setInterval(v || 'week')} allLabel={tx('Week')}
          options={[{ value: 'day', label: tx('Day') }, { value: 'week', label: tx('Week') }, { value: 'month', label: tx('Month') }]} />
      </div>

      <div className="grid grid-cols-2 gap-3 lg:grid-cols-4">
        <Tile label={tx('Pass rate')} value={s?.totals.passRate != null ? `${s.totals.passRate}%` : '—'} hint={tx('passed ÷ (passed + failed)')} />
        <Tile label={tx('Passed')} value={s?.totals.passed} />
        <Tile label={tx('Failed')} value={s?.totals.failed} tone="bad" />
        <Tile label={tx('Waived')} value={s?.totals.waived} />
      </div>

      <div className="grid grid-cols-1 gap-4 xl:grid-cols-2">
        <Card>
          <CardHeader className="pb-2"><CardTitle className="text-base">{tx('Checks and pass rate')}</CardTitle></CardHeader>
          <CardContent className="h-72">
            {summary.isLoading ? <Skeleton className="h-full w-full" /> : !s?.trend.length ? <Empty /> : (
              <ResponsiveContainer width="100%" height="100%">
                <ComposedChart data={s.trend.map((t) => ({ ...t, rate: t.passRate != null ? Number(t.passRate) : null }))}>
                  <CartesianGrid stroke={GRID} strokeDasharray="3 3" vertical={false} />
                  <XAxis dataKey="period" tick={AXIS} tickLine={false} axisLine={false} />
                  <YAxis yAxisId="n" tick={AXIS} tickLine={false} axisLine={false} width={36} allowDecimals={false} />
                  <YAxis yAxisId="pct" orientation="right" domain={[0, 100]} tick={AXIS} tickLine={false} axisLine={false} width={36} unit="%" />
                  <Tooltip />
                  <Legend wrapperStyle={{ fontSize: 12 }} />
                  <Bar yAxisId="n" dataKey="passed" name={tx('Passed')} stackId="a" fill={PASS} fillOpacity={0.75} />
                  <Bar yAxisId="n" dataKey="failed" name={tx('Failed')} stackId="a" fill={FAIL} fillOpacity={0.8} />
                  <Line yAxisId="pct" dataKey="rate" name={tx('Pass rate %')} stroke="hsl(var(--foreground))" strokeWidth={2} dot={{ r: 3 }} connectNulls />
                </ComposedChart>
              </ResponsiveContainer>
            )}
          </CardContent>
        </Card>
        <Card>
          <CardHeader className="flex flex-row items-center justify-between gap-2 pb-2">
            <CardTitle className="text-base">{tx('Pareto of failures')}</CardTitle>
            <FilterSelect value={dimension} onChange={(v) => setDimension((v || 'failure_class') as ParetoDimension)} allLabel={tx('Failure class')}
              options={DIMENSIONS.map((d) => ({ value: d.value, label: tx(d.label) }))} />
          </CardHeader>
          <CardContent className="h-72">
            {pareto.isLoading ? <Skeleton className="h-full w-full" /> : !pareto.data?.rows.length ? <Empty text={tx('No failed checks in this period.')} /> : (
              <ResponsiveContainer width="100%" height="100%">
                <ComposedChart data={pareto.data.rows.slice(0, 12).map((r) => ({ ...r, cum: Number(r.cumulativePercent) }))}>
                  <CartesianGrid stroke={GRID} strokeDasharray="3 3" vertical={false} />
                  <XAxis dataKey="label" tick={AXIS} tickLine={false} axisLine={false} interval={0} height={48}
                    tickFormatter={(v: string) => (v.length > 14 ? `${v.slice(0, 13)}…` : v)} />
                  <YAxis yAxisId="n" tick={AXIS} tickLine={false} axisLine={false} width={36} allowDecimals={false} />
                  <YAxis yAxisId="pct" orientation="right" domain={[0, 100]} tick={AXIS} tickLine={false} axisLine={false} width={36} unit="%" />
                  <Tooltip />
                  <Bar yAxisId="n" dataKey="count" name={tx('Failed checks')} fill={FAIL} fillOpacity={0.8} />
                  <Line yAxisId="pct" dataKey="cum" name={tx('Cumulative %')} stroke="hsl(var(--foreground))" strokeWidth={2} dot={{ r: 3 }} />
                  <ReferenceLine yAxisId="pct" y={80} stroke={SPEC} strokeDasharray="4 4" />
                </ComposedChart>
              </ResponsiveContainer>
            )}
          </CardContent>
        </Card>
      </div>

      {s && (
        <div className="grid grid-cols-1 gap-4 md:grid-cols-2 xl:grid-cols-4">
          <Breakdown title={tx('Failures by control point')} rows={s.byControlPoint} />
          <Breakdown title={tx('Failures by product')} rows={s.byProduct} />
          <Breakdown title={tx('Failures by work centre')} rows={s.byWorkCenter} />
          <Breakdown title={tx('Failures by team')} rows={s.byTeam} />
        </div>
      )}

      <SpcSection from={from} to={to} />
    </div>
  );
}

function Tile({ label, value, hint, tone }: { label: string; value: string | number | undefined; hint?: string; tone?: 'bad' }) {
  return (
    <Card>
      <CardContent className="p-4">
        <div className="text-xs text-muted-foreground">{label}</div>
        <div className={cn('text-2xl font-semibold tabular-nums', tone === 'bad' && Number(value) > 0 && 'text-destructive')}>{value ?? '—'}</div>
        {hint && <div className="text-[11px] text-muted-foreground">{hint}</div>}
      </CardContent>
    </Card>
  );
}

function Empty({ text }: { text?: string }) {
  return <div className="flex h-full items-center justify-center text-sm text-muted-foreground">{text ?? tx('No decided checks in this period.')}</div>;
}

function Breakdown({ title, rows }: { title: string; rows: Array<{ id: string | null; label: string; failed: number; failRate: string }> }) {
  return (
    <Card>
      <CardHeader className="pb-2"><CardTitle className="text-sm">{title}</CardTitle></CardHeader>
      <CardContent className="space-y-1.5 text-sm">
        {rows.length === 0 && <p className="text-muted-foreground">{tx('No failures.')}</p>}
        {rows.map((r) => (
          <div key={r.id ?? 'none'} className="flex items-center justify-between gap-2">
            <span className="truncate" title={r.label}>{r.label}</span>
            <span className="shrink-0 tabular-nums text-muted-foreground">{r.failed} · {r.failRate}%</span>
          </div>
        ))}
      </CardContent>
    </Card>
  );
}

// ------------------------------------------------------------------ SPC

function SpcSection({ from, to }: { from: string; to: string }) {
  const points = useControlPoints({ page: 1, pageSize: 200, checkType: 'measure' });
  const [pointId, setPointId] = useState('');
  const [baseline, setBaseline] = useState('');
  const [rules, setRules] = useState('1,4');
  const [segment, setSegment] = useState<number | undefined>(undefined);
  const spc = useQcSpc({ controlPointId: pointId || undefined, from, to, baseline: baseline ? Number(baseline) : undefined, rules, segment });
  const d = spc.data;
  return (
    <Card>
      <CardHeader className="space-y-3 pb-2">
        <CardTitle className="text-base">{tx('Statistical process control')}</CardTitle>
        <div className="flex flex-wrap items-end gap-2">
          <FilterSelect value={pointId} onChange={(v) => { setPointId(v); setSegment(undefined); }} allLabel={tx('Choose a measure control point')}
            options={(points.data?.data ?? []).map((p) => ({ value: p.id, label: `${p.reference} ${p.title}` }))} />
          <label className="text-xs text-muted-foreground">{tx('Baseline subgroups')}
            <Input type="number" min={2} className="h-9 w-32" placeholder={tx('all (trial)')} value={baseline} onChange={(e) => setBaseline(e.target.value)} />
          </label>
          <FilterSelect value={rules} onChange={(v) => setRules(v || '1,4')} allLabel={tx('Rules 1 and 4')}
            options={[{ value: '1', label: tx('Rule 1 only') }, { value: '1,4', label: tx('Rules 1 and 4') }, { value: '1,2,3,4', label: tx('Rules 1–4') }]} />
          {d && d.segments.length > 1 && (
            <FilterSelect value={String(d.segment ?? d.segments.length - 1)} onChange={(v) => setSegment(v === '' ? undefined : Number(v))} allLabel={tx('Latest specification')}
              options={d.segments.map((s) => ({ value: String(s.index), label: tx('Spec {{n}}: {{lsl}} – {{usl}} ({{v}} values)', { n: s.index + 1, lsl: s.lsl ?? '−∞', usl: s.usl ?? '+∞', v: s.values }) }))} />
          )}
        </div>
      </CardHeader>
      <CardContent className="space-y-4">
        {!pointId && <p className="text-sm text-muted-foreground">{tx('Pick a measure control point to see its control chart.')}</p>}
        {pointId && spc.isLoading && <Skeleton className="h-72 w-full" />}
        {pointId && spc.isError && <p className="text-sm text-destructive">{tx('Could not load the control chart.')}</p>}
        {d && !d.chart && <p className="text-sm text-muted-foreground">{d.message}</p>}
        {d?.chart && <SpcCharts data={d} />}
      </CardContent>
    </Card>
  );
}

function SpcCharts({ data }: { data: QcSpc }) {
  const c = data.chart!;
  const variable = c.ucl == null;
  const rows = c.points.map((p: SpcPoint) => ({
    label: c.method === 'I-MR' ? String(p.index + 1) : p.key,
    value: Number(p.value), range: p.range != null ? Number(p.range) : null,
    ucl: Number(p.ucl), lcl: Number(p.lcl), rucl: p.rangeUcl != null ? Number(p.rangeUcl) : null,
    flagged: p.violations.length > 0, violations: p.violations.join(', '), baseline: p.baseline,
  }));
  const lsl = data.spec?.lsl != null ? Number(data.spec.lsl) : null;
  const usl = data.spec?.usl != null ? Number(data.spec.usl) : null;
  const unit = data.controlPoint.unit ?? '';
  const dot = (props: { cx?: number; cy?: number; payload?: { flagged: boolean } }) => (
    <circle key={`${props.cx}-${props.cy}`} cx={props.cx} cy={props.cy} r={props.payload?.flagged ? 5 : 3}
      fill={props.payload?.flagged ? FAIL : PASS} stroke="none" />
  );
  const cap = data.capability;
  return (
    <>
      <div className="flex flex-wrap items-center gap-2 text-xs">
        <span className="rounded-full border px-2 py-0.5">{c.method === 'I-MR' ? tx('Individuals and moving range') : tx('X̄ and R')}</span>
        <span className="rounded-full border px-2 py-0.5">{c.limitsType === 'baseline' ? tx('Limits from the first {{k}} subgroups', { k: c.baselineSubgroups }) : tx('Trial limits (all data)')}</span>
        {c.provisional && <span className="rounded-full bg-amber-100 px-2 py-0.5 text-amber-900 dark:bg-amber-500/15 dark:text-amber-200">{tx('Provisional: too few subgroups')}</span>}
        <span className="text-muted-foreground">{tx('{{v}} values in {{k}} subgroups · σ̂ {{s}} ({{e}})', { v: c.values, k: c.subgroups, s: c.sigma, e: c.sigmaEstimator })}</span>
        {c.violations > 0 && <span className="text-destructive">{tx('{{n}} out-of-control signal(s)', { n: c.violations })}</span>}
      </div>
      <div className="h-72">
        <ResponsiveContainer width="100%" height="100%">
          <LineChart data={rows}>
            <CartesianGrid stroke={GRID} strokeDasharray="3 3" vertical={false} />
            <XAxis dataKey="label" tick={AXIS} tickLine={false} axisLine={false} />
            <YAxis tick={AXIS} tickLine={false} axisLine={false} width={52} domain={['auto', 'auto']} unit={unit ? ` ${unit}` : undefined} />
            <Tooltip formatter={(v: number, name: string) => [v, name]} labelFormatter={(l) => `${tx('Subgroup')} ${l}`} />
            <ReferenceLine y={Number(c.center)} stroke={PASS} label={{ value: 'CL', position: 'right', fontSize: 10 }} />
            {!variable && <ReferenceLine y={Number(c.ucl)} stroke={FAIL} strokeDasharray="6 3" label={{ value: 'UCL', position: 'right', fontSize: 10 }} />}
            {!variable && <ReferenceLine y={Number(c.lcl)} stroke={FAIL} strokeDasharray="6 3" label={{ value: 'LCL', position: 'right', fontSize: 10 }} />}
            {variable && <Line dataKey="ucl" name="UCL" type="stepAfter" stroke={FAIL} strokeDasharray="6 3" dot={false} />}
            {variable && <Line dataKey="lcl" name="LCL" type="stepAfter" stroke={FAIL} strokeDasharray="6 3" dot={false} />}
            {usl != null && <ReferenceLine y={usl} stroke={SPEC} strokeDasharray="2 4" label={{ value: 'USL', position: 'insideTopLeft', fontSize: 10 }} />}
            {lsl != null && <ReferenceLine y={lsl} stroke={SPEC} strokeDasharray="2 4" label={{ value: 'LSL', position: 'insideBottomLeft', fontSize: 10 }} />}
            <Line dataKey="value" name={c.method === 'I-MR' ? tx('Value') : tx('Subgroup mean')} stroke={PASS} strokeWidth={1.5} dot={dot} isAnimationActive={false} />
          </LineChart>
        </ResponsiveContainer>
      </div>
      <div className="h-40">
        <ResponsiveContainer width="100%" height="100%">
          <BarChart data={rows}>
            <CartesianGrid stroke={GRID} strokeDasharray="3 3" vertical={false} />
            <XAxis dataKey="label" tick={AXIS} tickLine={false} axisLine={false} />
            <YAxis tick={AXIS} tickLine={false} axisLine={false} width={52} />
            <Tooltip />
            <Bar dataKey="range" name={c.method === 'I-MR' ? tx('Moving range') : tx('Range')} fill={PASS} fillOpacity={0.6} />
            {c.rangeCenter != null && <ReferenceLine y={Number(c.rangeCenter)} stroke={PASS} />}
            {c.rangeUcl != null && <ReferenceLine y={Number(c.rangeUcl)} stroke={FAIL} strokeDasharray="6 3" label={{ value: 'UCL', position: 'right', fontSize: 10 }} />}
          </BarChart>
        </ResponsiveContainer>
      </div>
      <p className="text-xs text-muted-foreground">
        {tx('Control limits come only from the measured data. Specification limits (USL / LSL) are drawn for reference and are used only for capability.')}
      </p>
      {cap && (
        <div className="grid grid-cols-2 gap-3 md:grid-cols-5">
          {cap.valid ? (
            <>
              <Tile label="Cp" value={cap.cp ?? '—'} hint={cap.cp ? undefined : tx('needs both limits')} />
              <Tile label="Cpk" value={cap.cpk ?? '—'} />
              <Tile label="Cpu" value={cap.cpu ?? '—'} />
              <Tile label="Cpl" value={cap.cpl ?? '—'} />
              <Tile label={tx('Mean / σ̂')} value={`${cap.mean} / ${cap.sigma}`} hint={tx('n = {{n}}', { n: cap.n })} />
            </>
          ) : (
            <div className="col-span-full rounded-lg border border-dashed p-3 text-sm text-muted-foreground">
              {tx('Capability not reported: {{reason}}', { reason: cap.reason ?? '' })}
            </div>
          )}
        </div>
      )}
      {Object.values(data.excluded).some((v) => Number(v) > 0) && (
        <p className="text-xs text-muted-foreground">
          {tx('Excluded: {{m}} missing, {{c}} cancelled, {{i}} in an incomplete subgroup, {{k}} without a subgroup key.', {
            m: data.excluded.missing, c: data.excluded.cancelled, i: data.excluded.incompleteSubgroup, k: data.excluded.noSubgroupKey,
          })}
        </p>
      )}
    </>
  );
}

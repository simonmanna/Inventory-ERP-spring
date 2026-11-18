import { useEffect, useMemo, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Loader2, RotateCcw } from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Textarea } from '@/components/ui/textarea';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { api } from '@/lib/api';
import { apiErrorMessage } from '@/lib/api-error';
import { dateTime, money } from '@/lib/format';
import { notify } from '@/lib/notify';
import { useAuthStore } from '@/stores/auth.store';
import { tx } from '@/lib/i18n/tx';

type Row = Record<string, unknown>;
type Tab = 'margins' | 'reconciliation' | 'simulation';
type Layer = 'actual' | 'standard' | 'estimate';
type GroupBy = 'customer' | 'product' | 'project' | 'branch' | 'costCenter' | 'channel';

interface FactRow {
  segment: string | null;
  segmentName?: string | null;
  revenue: unknown;
  cogs: unknown;
  variances: unknown;
  distribution: unknown;
  overhead: unknown;
  cm1: unknown;
  cm2: unknown;
  cm3: unknown;
  statisticalOverhead: unknown;
}

const SELECT = 'h-9 rounded-md border border-input bg-background px-3 text-sm';
const today = new Date().toISOString().slice(0, 10);
const monthStart = `${today.slice(0, 8)}01`;

const s = (v: unknown) => (v == null ? '' : String(v));
const num = (v: unknown) => {
  const x = Number(v);
  return Number.isFinite(x) ? x : 0;
};
const day = (v: unknown) => s(v).slice(0, 10);
const pct = (part: number, whole: number) => (whole === 0 ? '—' : `${((part / whole) * 100).toFixed(1)} %`);

function parseJson<T>(v: unknown, fallback: T): T {
  if (v == null) return fallback;
  if (typeof v !== 'string') return v as T;
  try {
    return JSON.parse(v) as T;
  } catch {
    return fallback;
  }
}

const LAYERS: Array<[Layer, () => string, () => string]> = [
  ['actual', () => tx('Actual (the books)'), () => tx('What was posted in the general ledger for the period.')],
  ['standard', () => tx('Standard (list price × standard cost)'), () => tx('Invoiced quantities valued at the list selling price and the standard cost — what the period should have earned.')],
  ['estimate', () => tx('Estimate (project budgets)'), () => tx('Approved project budgets: the revenue and cost the projects were expected to bring.')],
];

const GROUPS: Array<[GroupBy, () => string]> = [
  ['customer', () => tx('Customer')],
  ['product', () => tx('Product')],
  ['project', () => tx('Project')],
  ['branch', () => tx('Branch')],
  ['costCenter', () => tx('Cost centre')],
  ['channel', () => tx('Sales channel')],
];

/** SimulationService.PARAMETERS with plain labels and what each one moves (SimulationEngine). */
const PARAMETERS: Array<{ key: string; label: () => string; hint: () => string }> = [
  { key: 'volumePct', label: () => tx('Sales volume changes by (%)'), hint: () => tx('More or fewer units sold; revenue, cost of sales and delivery cost move with it.') },
  { key: 'sellingPricePct', label: () => tx('Selling prices change by (%)'), hint: () => tx('Revenue per unit.') },
  { key: 'materialPricePct', label: () => tx('Material prices change by (%)'), hint: () => tx('Cost of sales.') },
  { key: 'importedCostSharePct', label: () => tx('Imported share of material cost (%)'), hint: () => tx('0–100: the part of the cost of sales bought in a foreign currency.') },
  { key: 'fxPct', label: () => tx('Exchange rate changes by (%)'), hint: () => tx('Applies to the imported share only.') },
  { key: 'fuelPricePct', label: () => tx('Fuel price changes by (%)'), hint: () => tx('Delivery (distribution) cost.') },
  { key: 'energyTariffPct', label: () => tx('Energy tariff changes by (%)'), hint: () => tx('The overhead pool.') },
  { key: 'capacityPct', label: () => tx('Capacity changes by (%)'), hint: () => tx('More capacity spreads the same overhead thinner; less makes it heavier.') },
];
const parameterLabel = (k: string) => PARAMETERS.find((p) => p.key === k)?.label() ?? k;

/**
 * Profitability and what-if simulation (review 2026-11-17, ease of use): contribution margins by segment from a
 * build of the books, its reconciliation back to net income, and scenarios that never post.
 */
export function ProfitabilityPage() {
  const has = useAuthStore((st) => st.hasPermission);
  const qc = useQueryClient();
  const canSimulate = has(PERMISSIONS.costSimulation.read);
  const [tab, setTab] = useState<Tab>('margins');
  const [period, setPeriod] = useState({ from: monthStart, to: today });
  const [runId, setRunId] = useState('');
  const runs = useQuery({
    queryKey: ['profitability', 'runs'],
    queryFn: async () => (await api.get<Row[]>('/costing/profitability/runs')).data,
  });
  const list = useMemo(() => runs.data ?? [], [runs.data]);
  useEffect(() => {
    if (!runId && list.length > 0) setRunId(s((list.find((r) => r.status === 'current') ?? list[0]).id));
  }, [list, runId]);
  const run = list.find((r) => s(r.id) === runId);
  const build = useMutation({
    mutationFn: async () => (await api.post<Row>('/costing/profitability/runs', period)).data,
    onSuccess: (r) => {
      notify.success(tx('Profitability rebuilt for {{from}} – {{to}}', { from: period.from, to: period.to }));
      setRunId(s(r?.id));
      void qc.invalidateQueries({ queryKey: ['profitability'] });
    },
    onError: (e) => notify.error(apiErrorMessage(e, tx('The build failed'))),
  });
  const tabs: Array<[Tab, string]> = [
    ['margins', tx('Margins')],
    ['reconciliation', tx('Agreement with the books')],
    ...(canSimulate ? [['simulation', tx('What-if simulation')] as [Tab, string]] : []),
  ];

  return (
    <div className="space-y-4">
      <div>
        <h1 className="text-2xl font-semibold">{tx('Profitability')}</h1>
        <p className="text-sm text-muted-foreground">
          {tx('Contribution margin by customer, product, project, branch, cost centre or channel — built from the posted books for a period, then reconciled back to net income.')}
        </p>
      </div>
      <Card>
        <CardContent className="flex flex-wrap items-end gap-3 py-4">
          <div className="grid gap-1">
            <Label className="text-xs text-muted-foreground">{tx('Build shown')}</Label>
            <select className={SELECT} value={runId} onChange={(e) => setRunId(e.target.value)}>
              {list.length === 0 && <option value="">{tx('No build yet')}</option>}
              {list.map((r) => (
                <option key={s(r.id)} value={s(r.id)}>
                  {day(r.periodFrom)} – {day(r.periodTo)} · {r.status === 'current' ? tx('current') : tx('superseded')} · {dateTime(s(r.createdAt))}
                </option>
              ))}
            </select>
          </div>
          {has(PERMISSIONS.profitability.manage) && (
            <>
              <div className="grid gap-1">
                <Label className="text-xs text-muted-foreground">{tx('Period to (re)build')}</Label>
                <div className="flex items-center gap-2">
                  <Input type="date" className="w-40" value={period.from} onChange={(e) => setPeriod({ ...period, from: e.target.value })} />
                  <span className="text-muted-foreground">–</span>
                  <Input type="date" className="w-40" value={period.to} onChange={(e) => setPeriod({ ...period, to: e.target.value })} />
                </div>
              </div>
              <Button disabled={!period.from || !period.to || period.from > period.to || build.isPending} onClick={() => build.mutate()}>
                {build.isPending ? <Loader2 className="h-4 w-4 animate-spin" /> : <RotateCcw className="h-4 w-4" />} {tx('Rebuild')}
              </Button>
              <p className="basis-full text-xs text-muted-foreground">
                {tx('Rebuilding reads the posted journals again; the previous build of the same period is kept as superseded. Nothing is posted.')}
              </p>
            </>
          )}
        </CardContent>
      </Card>
      <div className="flex flex-wrap gap-2">
        {tabs.map(([k, label]) => (
          <Button key={k} size="sm" variant={tab === k ? 'default' : 'outline'} onClick={() => setTab(k)}>{label}</Button>
        ))}
      </div>
      {!run ? (
        <p className="text-sm text-muted-foreground">{tx('Build profitability for a period to see margins.')}</p>
      ) : (
        <>
          {tab === 'margins' && <Margins runId={runId} />}
          {tab === 'reconciliation' && <Reconciliation runId={runId} />}
          {tab === 'simulation' && canSimulate && <Simulation run={run} canManage={has(PERMISSIONS.costSimulation.manage)} />}
        </>
      )}
    </div>
  );
}

function useFacts(runId: string, layer: Layer, groupBy: GroupBy, enabled = true) {
  return useQuery({
    queryKey: ['profitability', 'facts', runId, layer, groupBy],
    enabled: enabled && !!runId,
    queryFn: async () =>
      (await api.get<{ rows: FactRow[] }>(`/costing/profitability/runs/${runId}/facts`, { params: { layer, groupBy } })).data,
  });
}

function Margins({ runId }: { runId: string }) {
  const [layer, setLayer] = useState<Layer>('actual');
  const [groupBy, setGroupBy] = useState<GroupBy>('customer');
  const { data, isLoading } = useFacts(runId, layer, groupBy);
  const rows = useMemo(() => data?.rows ?? [], [data]);
  const totals = useMemo(() => {
    const keys = ['revenue', 'cogs', 'cm1', 'variances', 'distribution', 'cm2', 'overhead', 'cm3', 'statisticalOverhead'] as const;
    const t: Record<string, number> = {};
    for (const k of keys) t[k] = rows.reduce((a, r) => a + num(r[k]), 0);
    return t;
  }, [rows]);
  const anyStatistical = totals.statisticalOverhead !== 0;
  const layerHint = LAYERS.find(([k]) => k === layer)?.[2]();
  return (
    <Card>
      <CardHeader>
        <CardTitle>{tx('Contribution margins')}</CardTitle>
        <CardDescription className="max-w-4xl">
          {tx('Revenue − cost of sales = Margin 1 (gross margin). − Variances − delivery cost = Margin 2. − Overhead = Margin 3 (net contribution). Each margin shows what a segment contributes after one more layer of cost.')}
        </CardDescription>
      </CardHeader>
      <CardContent className="space-y-3">
        <div className="flex flex-wrap items-center gap-2">
          <select className={SELECT} value={layer} onChange={(e) => setLayer(e.target.value as Layer)}>
            {LAYERS.map(([k, label]) => <option key={k} value={k}>{label()}</option>)}
          </select>
          <select className={SELECT} value={groupBy} onChange={(e) => setGroupBy(e.target.value as GroupBy)}>
            {GROUPS.map(([k, label]) => <option key={k} value={k}>{tx('By {{what}}', { what: label().toLowerCase() })}</option>)}
          </select>
          <span className="text-sm text-muted-foreground">{layerHint}</span>
        </div>
        {isLoading ? (
          <p className="text-sm text-muted-foreground">{tx('Loading…')}</p>
        ) : rows.length === 0 ? (
          <p className="text-sm text-muted-foreground">{tx('No figures for this view.')}</p>
        ) : (
          <div className="overflow-x-auto">
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>{GROUPS.find(([k]) => k === groupBy)?.[1]()}</TableHead>
                  <TableHead className="text-right">{tx('Revenue')}</TableHead>
                  <TableHead className="text-right">{tx('Cost of sales')}</TableHead>
                  <TableHead className="text-right">{tx('Margin 1')}</TableHead>
                  <TableHead className="text-right">{tx('Variances')}</TableHead>
                  <TableHead className="text-right">{tx('Delivery cost')}</TableHead>
                  <TableHead className="text-right">{tx('Margin 2')}</TableHead>
                  <TableHead className="text-right">{tx('Overhead')}</TableHead>
                  <TableHead className="text-right">{tx('Margin 3')}</TableHead>
                  <TableHead className="text-right">{tx('Margin 3 %')}</TableHead>
                  {anyStatistical && <TableHead className="text-right">{tx('Memo: statistical overhead')}</TableHead>}
                </TableRow>
              </TableHeader>
              <TableBody>
                {rows.map((r, i) => (
                  <TableRow key={`${s(r.segment)}-${i}`}>
                    <TableCell className="font-medium">{r.segment == null ? <span className="text-muted-foreground">{tx('Not assigned')}</span> : s(r.segmentName) || tx('Unnamed')}</TableCell>
                    <TableCell className="text-right tabular-nums">{money(num(r.revenue))}</TableCell>
                    <TableCell className="text-right tabular-nums">{money(num(r.cogs))}</TableCell>
                    <TableCell className="text-right font-medium tabular-nums">{money(num(r.cm1))}</TableCell>
                    <TableCell className="text-right tabular-nums">{money(num(r.variances))}</TableCell>
                    <TableCell className="text-right tabular-nums">{money(num(r.distribution))}</TableCell>
                    <TableCell className="text-right font-medium tabular-nums">{money(num(r.cm2))}</TableCell>
                    <TableCell className="text-right tabular-nums">{money(num(r.overhead))}</TableCell>
                    <TableCell className={`text-right font-semibold tabular-nums ${num(r.cm3) < 0 ? 'text-destructive' : ''}`}>{money(num(r.cm3))}</TableCell>
                    <TableCell className="text-right tabular-nums">{pct(num(r.cm3), num(r.revenue))}</TableCell>
                    {anyStatistical && <TableCell className="text-right tabular-nums text-muted-foreground">{money(num(r.statisticalOverhead))}</TableCell>}
                  </TableRow>
                ))}
                <TableRow className="border-t-2 font-semibold">
                  <TableCell>{tx('Total')}</TableCell>
                  <TableCell className="text-right tabular-nums">{money(totals.revenue)}</TableCell>
                  <TableCell className="text-right tabular-nums">{money(totals.cogs)}</TableCell>
                  <TableCell className="text-right tabular-nums">{money(totals.cm1)}</TableCell>
                  <TableCell className="text-right tabular-nums">{money(totals.variances)}</TableCell>
                  <TableCell className="text-right tabular-nums">{money(totals.distribution)}</TableCell>
                  <TableCell className="text-right tabular-nums">{money(totals.cm2)}</TableCell>
                  <TableCell className="text-right tabular-nums">{money(totals.overhead)}</TableCell>
                  <TableCell className="text-right tabular-nums">{money(totals.cm3)}</TableCell>
                  <TableCell className="text-right tabular-nums">{pct(totals.cm3, totals.revenue)}</TableCell>
                  {anyStatistical && <TableCell className="text-right tabular-nums text-muted-foreground">{money(totals.statisticalOverhead)}</TableCell>}
                </TableRow>
              </TableBody>
            </Table>
          </div>
        )}
        {anyStatistical && (
          <p className="text-xs text-muted-foreground">
            {tx('Statistical overhead is a memo allocation only: it is not in the books and is never deducted from the margins.')}
          </p>
        )}
        {layer === 'actual' && rows.some((r) => r.segment == null) && (
          <p className="text-xs text-muted-foreground">
            {tx('“Not assigned” holds postings without this attribute (for example a sale without a customer when grouping by customer).')}
          </p>
        )}
      </CardContent>
    </Card>
  );
}

const BUCKETS: Record<string, () => string> = {
  unallocated: () => tx('Not in any segment (no customer, product or project on the posting)'),
  out_of_scope: () => tx('Out of scope (other income and other expense)'),
};

function Reconciliation({ runId }: { runId: string }) {
  const { data, isLoading } = useQuery({
    queryKey: ['profitability', 'reconciliation', runId],
    queryFn: async () => (await api.get<Row>(`/costing/profitability/runs/${runId}/reconciliation`)).data,
  });
  if (isLoading || !data) return <p className="text-sm text-muted-foreground">{tx('Loading…')}</p>;
  const accounts = (data.accounts as Row[] | undefined) ?? [];
  const difference = num(data.difference);
  const ok = Math.abs(difference) < 0.005;
  return (
    <Card>
      <CardHeader>
        <CardTitle className="flex items-center gap-2">
          {tx('Does it add up to net income?')}
          {ok ? <Badge className="bg-emerald-600 text-white hover:bg-emerald-600">{tx('Agrees')}</Badge> : <Badge variant="destructive">{tx('Difference {{amount}}', { amount: money(difference) })}</Badge>}
        </CardTitle>
        <CardDescription className="max-w-4xl">
          {tx('Every revenue and expense posting of the period lands in exactly one place: a segment, “not in any segment”, or “out of scope”. Together they must equal the net income in the general ledger.')}
        </CardDescription>
      </CardHeader>
      <CardContent className="space-y-4">
        <div className="grid gap-2 sm:grid-cols-2 lg:grid-cols-5">
          {[
            [tx('Segments'), data.segmentNet],
            [tx('Not in any segment'), data.unallocated],
            [tx('Out of scope'), data.outOfScope],
            [tx('Net income (general ledger)'), data.glNetIncome],
            [tx('Difference'), data.difference],
          ].map(([label, v]) => (
            <div key={s(label)} className="rounded-md border p-3">
              <div className="text-xs text-muted-foreground">{s(label)}</div>
              <div className="text-lg font-semibold tabular-nums">{money(num(v))}</div>
            </div>
          ))}
        </div>
        {!ok && (
          <p className="rounded-md bg-muted/50 p-2 text-sm">
            <span className="font-medium">{tx('What to do')}:</span>{' '}
            {tx('A difference means journals were posted or reversed after this build. Rebuild the period; if it remains, contact support with the build date.')}
          </p>
        )}
        {num(data.statisticalExcluded) !== 0 && (
          <p className="text-sm text-muted-foreground">
            {tx('Statistical overhead left out (memo only, not in the books): {{amount}}', { amount: money(num(data.statisticalExcluded)) })}
          </p>
        )}
        {accounts.length > 0 && (
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>{tx('Where')}</TableHead>
                <TableHead>{tx('Account')}</TableHead>
                <TableHead className="text-right">{tx('Amount (income +, cost −)')}</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {accounts.map((a, i) => (
                <TableRow key={`${s(a.accountId)}-${s(a.bucket)}-${i}`}>
                  <TableCell className="text-sm">{BUCKETS[s(a.bucket)]?.() ?? s(a.bucket)}</TableCell>
                  <TableCell>{s(a.code)} · {s(a.name)}</TableCell>
                  <TableCell className="text-right tabular-nums">{money(num(a.amount))}</TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        )}
      </CardContent>
    </Card>
  );
}

interface SimRow {
  segment: string | null;
  simulated: Row;
  actual: Row;
  standard: Row;
}

function Simulation({ run, canManage }: { run: Row; canManage: boolean }) {
  const qc = useQueryClient();
  const runId = s(run.id);
  const scenarios = useQuery({
    queryKey: ['profitability', 'scenarios'],
    queryFn: async () => (await api.get<Row[]>('/costing/simulation/scenarios')).data,
  });
  const [scenarioId, setScenarioId] = useState('');
  const list = useMemo(() => scenarios.data ?? [], [scenarios.data]);
  useEffect(() => {
    if (!scenarioId && list.length > 0) setScenarioId(s(list[0].id));
  }, [list, scenarioId]);
  const scenario = list.find((x) => s(x.id) === scenarioId);
  const [form, setForm] = useState<{ name: string; notes: string; values: Record<string, string> }>({ name: '', notes: '', values: {} });
  const create = useMutation({
    mutationFn: async () => {
      const parameters: Record<string, number> = {};
      for (const [k, v] of Object.entries(form.values)) if (v.trim() !== '') parameters[k] = Number(v);
      return (await api.post<Row>('/costing/simulation/scenarios', { name: form.name.trim(), parameters, notes: form.notes.trim() || undefined })).data;
    },
    onSuccess: (r) => {
      notify.success(tx('Scenario saved'));
      setScenarioId(s(r?.id));
      setForm({ name: '', notes: '', values: {} });
      void qc.invalidateQueries({ queryKey: ['profitability', 'scenarios'] });
    },
    onError: (e) => notify.error(apiErrorMessage(e, tx('The scenario could not be saved'))),
  });
  const [groupBy, setGroupBy] = useState<'product' | 'customer'>('product');
  const [includeUnallocated, setIncludeUnallocated] = useState(false);
  const [resultId, setResultId] = useState('');
  const results = useQuery({
    queryKey: ['profitability', 'sim-results', scenarioId],
    enabled: !!scenarioId,
    queryFn: async () => (await api.get<Row[]>(`/costing/simulation/scenarios/${scenarioId}/results`)).data,
  });
  const simulate = useMutation({
    mutationFn: async () =>
      (await api.post<Row>(`/costing/simulation/scenarios/${scenarioId}/simulate`, { profitabilityRunId: runId, groupBy, includeUnallocated })).data,
    onSuccess: (r) => {
      setResultId(s(r?.id));
      void qc.invalidateQueries({ queryKey: ['profitability', 'sim-results', scenarioId] });
    },
    onError: (e) => notify.error(apiErrorMessage(e, tx('The simulation failed'))),
  });
  const result = useQuery({
    queryKey: ['profitability', 'sim-result', resultId],
    enabled: !!resultId,
    queryFn: async () => (await api.get<Row>(`/costing/simulation/results/${resultId}`)).data,
  });
  const shown = result.data;
  const shownGroup = (s(shown?.groupBy) || groupBy) as GroupBy;
  const shownRun = s(shown?.profitabilityRunId) || runId;
  // segment names come from the profitability facts of the same build (the simulation stores ids only)
  const names = useFacts(shownRun, 'actual', shownGroup, !!shown);
  const nameOf = useMemo(() => {
    const m = new Map<string, string>();
    for (const r of names.data?.rows ?? []) if (r.segment != null) m.set(r.segment, s(r.segmentName) || r.segment);
    return m;
  }, [names.data]);
  const simRows = parseJson<SimRow[]>(shown?.results, []);
  const totals = parseJson<Row>(shown?.totals, {});
  const params = parseJson<Record<string, unknown>>(scenario?.parameters, {});
  const paramText = Object.entries(params).map(([k, v]) => `${parameterLabel(k)} ${s(v)}`).join(' · ');

  return (
    <div className="space-y-4">
      <p className="rounded-md bg-muted/50 p-2 text-sm">
        {tx('A simulation never posts anything: it replays the build shown above with your assumptions and keeps the result for comparison.')}
      </p>
      <div className="grid gap-4 lg:grid-cols-2">
        <Card>
          <CardHeader>
            <CardTitle>{tx('Scenario')}</CardTitle>
            <CardDescription>{tx('Pick a saved set of assumptions, then run it against the build shown above.')}</CardDescription>
          </CardHeader>
          <CardContent className="space-y-3">
            <select className={`${SELECT} w-full`} value={scenarioId} onChange={(e) => { setScenarioId(e.target.value); setResultId(''); }}>
              {list.length === 0 && <option value="">{tx('No scenario yet')}</option>}
              {list.map((x) => <option key={s(x.id)} value={s(x.id)}>{s(x.name)} · v{s(x.version)}</option>)}
            </select>
            {scenario && (
              <div className="text-sm">
                <p>{paramText || tx('No change to any assumption (a baseline).')}</p>
                {scenario.notes != null && s(scenario.notes) && <p className="text-muted-foreground">{s(scenario.notes)}</p>}
              </div>
            )}
            {canManage && scenario && (
              <div className="flex flex-wrap items-center gap-2">
                <select className={SELECT} value={groupBy} onChange={(e) => setGroupBy(e.target.value as 'product' | 'customer')}>
                  <option value="product">{tx('By product')}</option>
                  <option value="customer">{tx('By customer')}</option>
                </select>
                <label className="flex items-center gap-2 text-sm">
                  <input type="checkbox" checked={includeUnallocated} onChange={(e) => setIncludeUnallocated(e.target.checked)} />
                  {tx('Also spread costs that are in no segment')}
                </label>
                <Button disabled={simulate.isPending} onClick={() => simulate.mutate()}>
                  {simulate.isPending && <Loader2 className="h-4 w-4 animate-spin" />} {tx('Simulate')}
                </Button>
              </div>
            )}
            {(results.data ?? []).length > 0 && (
              <div className="space-y-1">
                <Label className="text-xs text-muted-foreground">{tx('Earlier results of this scenario')}</Label>
                <select className={`${SELECT} w-full`} value={resultId} onChange={(e) => setResultId(e.target.value)}>
                  <option value="">{tx('Choose a result…')}</option>
                  {(results.data ?? []).map((r) => {
                    const t = parseJson<Row>(r.totals, {});
                    return (
                      <option key={s(r.id)} value={s(r.id)}>
                        {dateTime(s(r.createdAt))} · {r.groupBy === 'customer' ? tx('by customer') : tx('by product')} · {tx('change')} {money(num(t.delta))}
                      </option>
                    );
                  })}
                </select>
              </div>
            )}
          </CardContent>
        </Card>
        {canManage && (
          <Card>
            <CardHeader>
              <CardTitle>{tx('New scenario')}</CardTitle>
              <CardDescription>{tx('Leave a box empty for “no change”. Saving under an existing name keeps the old one as an earlier version.')}</CardDescription>
            </CardHeader>
            <CardContent className="space-y-2">
              <Input placeholder={tx('Name, e.g. “Fuel +15 %”')} value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} />
              <div className="grid gap-2 sm:grid-cols-2">
                {PARAMETERS.map((p) => (
                  <div key={p.key} className="grid gap-1">
                    <Label className="text-xs">{p.label()}</Label>
                    <Input
                      type="number"
                      inputMode="decimal"
                      value={form.values[p.key] ?? ''}
                      onChange={(e) => setForm({ ...form, values: { ...form.values, [p.key]: e.target.value } })}
                    />
                    <span className="text-xs text-muted-foreground">{p.hint()}</span>
                  </div>
                ))}
              </div>
              <Textarea placeholder={tx('Notes (optional)')} rows={2} value={form.notes} onChange={(e) => setForm({ ...form, notes: e.target.value })} />
              <Button disabled={!form.name.trim() || create.isPending} onClick={() => create.mutate()}>
                {create.isPending && <Loader2 className="h-4 w-4 animate-spin" />} {tx('Save scenario')}
              </Button>
            </CardContent>
          </Card>
        )}
      </div>

      {shown && (
        <Card>
          <CardHeader>
            <CardTitle>{tx('Result')}</CardTitle>
            <CardDescription>{tx('Margin 3 (net contribution) as it was in the build, and as it would be under the scenario.')}</CardDescription>
          </CardHeader>
          <CardContent className="space-y-3">
            <div className="grid gap-2 sm:grid-cols-4">
              {[
                [tx('Revenue under the scenario'), totals.revenue, ''],
                [tx('Margin 3 in the build'), totals.actualCm3, ''],
                [tx('Margin 3 under the scenario'), totals.cm3, ''],
                [tx('Change'), totals.delta, num(totals.delta) < 0 ? 'text-destructive' : 'text-emerald-600'],
              ].map(([label, v, cls]) => (
                <div key={s(label)} className="rounded-md border p-3">
                  <div className="text-xs text-muted-foreground">{s(label)}</div>
                  <div className={`text-lg font-semibold tabular-nums ${s(cls)}`}>{money(num(v))}</div>
                </div>
              ))}
            </div>
            <div className="overflow-x-auto">
              <Table>
                <TableHeader>
                  <TableRow>
                    <TableHead>{shownGroup === 'customer' ? tx('Customer') : tx('Product')}</TableHead>
                    <TableHead className="text-right">{tx('Quantity: build → scenario')}</TableHead>
                    <TableHead className="text-right">{tx('Revenue: build → scenario')}</TableHead>
                    <TableHead className="text-right">{tx('Margin 3: build → scenario')}</TableHead>
                    <TableHead className="text-right">{tx('Change')}</TableHead>
                    <TableHead className="text-right">{tx('Full cost per unit')}</TableHead>
                    <TableHead className="text-right">{tx('Margin %')}</TableHead>
                    <TableHead className="text-right">{tx('Break-even quantity')}</TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {simRows.map((r, i) => {
                    const change = num(r.simulated?.cm3) - num(r.actual?.cm3);
                    return (
                      <TableRow key={`${s(r.segment)}-${i}`}>
                        <TableCell className="font-medium">
                          {r.segment == null ? <span className="text-muted-foreground">{tx('Not assigned')}</span> : nameOf.get(r.segment) ?? tx('Unnamed')}
                        </TableCell>
                        <TableCell className="text-right tabular-nums">{num(r.actual?.quantity).toLocaleString()} → {num(r.simulated?.quantity).toLocaleString()}</TableCell>
                        <TableCell className="text-right tabular-nums">{money(num(r.actual?.revenue))} → {money(num(r.simulated?.revenue))}</TableCell>
                        <TableCell className="text-right tabular-nums">{money(num(r.actual?.cm3))} → {money(num(r.simulated?.cm3))}</TableCell>
                        <TableCell className={`text-right font-medium tabular-nums ${change < 0 ? 'text-destructive' : 'text-emerald-600'}`}>{money(change)}</TableCell>
                        <TableCell className="text-right tabular-nums">{r.simulated?.unitCost == null ? '—' : money(num(r.simulated.unitCost))}</TableCell>
                        <TableCell className="text-right tabular-nums">{r.simulated?.marginPct == null ? '—' : `${num(r.simulated.marginPct).toFixed(1)} %`}</TableCell>
                        <TableCell className="text-right tabular-nums">{r.simulated?.breakEvenQuantity == null ? '—' : num(r.simulated.breakEvenQuantity).toLocaleString()}</TableCell>
                      </TableRow>
                    );
                  })}
                </TableBody>
              </Table>
            </div>
            <p className="text-xs text-muted-foreground">
              {tx('Break-even quantity: the units a segment must sell for its Margin 2 to cover the overhead it carries under the scenario. Overhead is re-spread by simulated revenue.')}
            </p>
          </CardContent>
        </Card>
      )}
    </div>
  );
}

export default ProfitabilityPage;

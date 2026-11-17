import { useMemo, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { AlertTriangle, CheckCircle2, ExternalLink, Loader2, Lock, RotateCcw, Unlock } from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card';
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Textarea } from '@/components/ui/textarea';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { api } from '@/lib/api';
import { apiErrorMessage } from '@/lib/api-error';
import { idempotentPost } from '@/lib/idempotent-request';
import { dateTime, money } from '@/lib/format';
import { notify } from '@/lib/notify';
import { referenceLabel } from '@/lib/ledger-links';
import { useAuthStore } from '@/stores/auth.store';
import { tx } from '@/lib/i18n/tx';

type Row = Record<string, unknown>;
type Period = { from: string; to: string };
type Tab = 'checklist' | 'reconciliations' | 'runs' | 'locks' | 'adjuncts';

interface Gate { seq: number; key: string; label: string; status: string; detail: string }
interface GatesResponse { from: string; to: string; gates: Gate[]; blocking: Gate | null; ready: boolean }
interface ReconRow { key: string; label: string; difference: unknown; ok: boolean }

const today = new Date().toISOString().slice(0, 10);
const monthStart = `${today.slice(0, 8)}01`;

const s = (v: unknown) => (v == null ? '' : String(v));
const num = (v: unknown) => {
  const x = Number(v);
  return Number.isFinite(x) ? x : 0;
};
const day = (v: unknown) => s(v).slice(0, 10);

/** jsonb columns arrive as an object or as their JSON text. */
function parseJson<T>(v: unknown, fallback: T): T {
  if (v == null) return fallback;
  if (typeof v !== 'string') return v as T;
  try {
    return JSON.parse(v) as T;
  } catch {
    return fallback;
  }
}

/** Plain-language copy per close step (keys from CostCloseService.gates), with the screen that fixes it. */
interface GateCopy {
  title: () => string;
  what: () => string;
  fix: () => string;
  to?: string;
  toLabel?: () => string;
  tab?: Tab;
}

const GATES: Record<string, GateCopy> = {
  landed_cost: {
    title: () => tx('Landed costs complete'),
    what: () => tx('Every landed-cost document (freight, duty, insurance) dated up to the period end is posted, so stock carries its full cost.'),
    fix: () => tx('Post — or delete — the draft landed-cost documents.'),
    to: '/procurement/landed-cost-vouchers',
    toLabel: () => tx('Open landed costs'),
  },
  absorption: {
    title: () => tx('Production overhead settled'),
    what: () => tx('For every work centre with a normal capacity, the overhead actually spent is compared with the overhead charged to production, and the difference (volume and spending variance) is posted.'),
    fix: () => tx('In Cost operations → Absorption settlement, preview and post the settlement for each work centre listed.'),
    to: '/costing/operations',
    toLabel: () => tx('Open cost operations'),
  },
  allocation: {
    title: () => tx('Shared costs allocated'),
    what: () => tx('Every active allocation cycle (spreading shared costs to departments, products or customers) has a posted run that covers the period end.'),
    fix: () => tx('Run and post the allocation cycles that are missing for the period.'),
    to: '/costing/allocation',
    toLabel: () => tx('Open cost allocation'),
  },
  nrv: {
    title: () => tx('Stock checked for value below cost (NRV)'),
    what: () => tx('Stock that can only be sold for less than it cost is written down to its net realisable value, and every item still written down is re-assessed each period.'),
    fix: () => tx('Post a net realisable value review for the period that includes every item listed (Costing & Valuation → Revaluation & NRV).'),
    to: '/costing/settings',
    toLabel: () => tx('Open Costing & Valuation'),
  },
  quarry: {
    title: () => tx('Quarry provisions and stripping posted'),
    what: () => tx('Each rehabilitation provision is unwound for the period (the discount reversing with time) and each capitalised stripping cost is amortised by the ore extracted.'),
    fix: () => tx('In Cost operations → Quarry, post “Unwind for period” for every provision and “Amortise for period” for every stripping asset listed.'),
    to: '/costing/operations',
    toLabel: () => tx('Open cost operations'),
  },
  revenue: {
    title: () => tx('Project revenue recognised'),
    what: () => tx('Every customer project with posted costs has a revenue-recognition run up to the period end, so revenue matches the work done.'),
    fix: () => tx("Open each project's contract tab and run revenue recognition to the period end."),
    to: '/projects',
    toLabel: () => tx('Open projects'),
  },
  cost_adjustment: {
    title: () => tx('Late costs carried into finished goods'),
    what: () => tx('A cost that arrived after its materials were used by production (a late freight bill, a price difference) waits in work in process. Once the order is completed, a cost adjustment run moves it to the finished goods — or to cost of sales for what is already sold.'),
    fix: () => tx('Run a cost adjustment for the period.'),
    tab: 'runs',
    toLabel: () => tx('Open cost adjustment runs'),
  },
  profitability: {
    title: () => tx('Profitability rebuilt and reconciled'),
    what: () => tx("Profitability for exactly this period is rebuilt from the books and agrees with the general ledger's net income."),
    fix: () => tx('Rebuild profitability for the period; if a difference remains, look at its reconciliation.'),
    to: '/costing/profitability',
    toLabel: () => tx('Open profitability'),
  },
  reconciliations: {
    title: () => tx('Sub-ledgers agree with the general ledger'),
    what: () => tx('Stock, inter-branch clearing, project work in process and contract balances agree with their general-ledger accounts.'),
    fix: () => tx('Open the reconciliations to see which one is off and why.'),
    tab: 'reconciliations',
    toLabel: () => tx('Open reconciliations'),
  },
};

/** What to do when a reconciliation (keys from CostCloseService.reconciliations) is off. */
const RECON: Record<string, { title: () => string; fix: () => string; to: string; toLabel: () => string }> = {
  inventory: {
    title: () => tx('Stock value vs inventory accounts'),
    fix: () => tx('The stock ledger and the inventory accounts differ: some document moved stock without its journal (or a journal was posted to an inventory account by hand). The tie-out lists the documents behind the difference.'),
    to: '/inventory-gl-tieout',
    toLabel: () => tx('Open the inventory tie-out'),
  },
  inter_branch_clearing: {
    title: () => tx('Inter-branch clearing nets to zero'),
    fix: () => tx('Every transfer one branch sends must be received by another. A balance means a transfer is still in transit or was received at a different value.'),
    to: '/costing/operations',
    toLabel: () => tx('Open cost operations'),
  },
  project_wip: {
    title: () => tx('Project work in process vs project costs'),
    fix: () => tx('A cost was posted to project work in process outside the project, or a revenue-recognition run is not posted.'),
    to: '/projects',
    toLabel: () => tx('Open projects'),
  },
  contracts: {
    title: () => tx('Retentions, advances and onerous provisions'),
    fix: () => tx("A manual journal was posted to a retention, customer-advance or onerous-contract account; reverse it and use the project's contract tab instead."),
    to: '/projects',
    toLabel: () => tx('Open projects'),
  },
};

const ADJUSTMENT_TYPES: Record<string, () => string> = {
  landed_cost: () => tx('Landed cost'),
  landed_accrual: () => tx('Landed cost accrual'),
  cost_adjustment: () => tx('Cost adjustment run'),
  nrv: () => tx('Net realisable value write-down'),
  std_price_difference: () => tx('Price difference against standard'),
  revaluation: () => tx('Revaluation'),
  overhead_settlement: () => tx('Overhead settlement'),
  stripping_amortisation: () => tx('Stripping amortisation'),
  rehab_production: () => tx('Rehabilitation provision'),
  transfer_freight: () => tx('Transfer freight'),
  ml_price_diff: () => tx('Material ledger price difference'),
};
const adjustmentLabel = (k: unknown) => ADJUSTMENT_TYPES[s(k)]?.() ?? referenceLabel(s(k));

const COMPONENTS: Record<string, () => string> = {
  price_difference: () => tx('Price difference held beside stock (1396)'),
  nrv_allowance: () => tx('Net realisable value allowance (1397)'),
};
const componentLabel = (k: unknown) => COMPONENTS[s(k)]?.() ?? referenceLabel(s(k));

function GateStatus({ status }: { status: string }) {
  if (status === 'pass') return <Badge className="bg-emerald-600 text-white hover:bg-emerald-600">{tx('Done')}</Badge>;
  if (status === 'fail') return <Badge variant="destructive">{tx('To do')}</Badge>;
  return <Badge variant="outline">{tx('Not needed')}</Badge>;
}

function useLocks() {
  return useQuery({
    queryKey: ['cost-close', 'locks'],
    queryFn: async () => (await api.get<Row[]>('/costing/close/locks')).data,
  });
}

/**
 * Cost close (review 2026-11-17, ease of use): the period-end costing checklist with what each step means and where
 * to fix it, the sub-ledger reconciliations, cost adjustment runs, period locks and the open price differences.
 */
export function CostClosePage() {
  const has = useAuthStore((st) => st.hasPermission);
  const [params, setParams] = useSearchParams();
  const canAdjuncts = has(PERMISSIONS.inventoryValuation.read);
  const tabs: Array<[Tab, string]> = [
    ['checklist', tx('Checklist')],
    ['reconciliations', tx('Reconciliations')],
    ['runs', tx('Cost adjustment runs')],
    ['locks', tx('Locked periods')],
    ...(canAdjuncts ? [['adjuncts', tx('Price differences & NRV')] as [Tab, string]] : []),
  ];
  const requested = params.get('tab') as Tab | null;
  const tab: Tab = requested && tabs.some(([k]) => k === requested) ? requested : 'checklist';
  const setTab = (t: Tab) => {
    const next = new URLSearchParams(params);
    next.set('tab', t);
    setParams(next, { replace: true });
  };
  const [period, setPeriod] = useState<Period>({ from: params.get('from') ?? monthStart, to: params.get('to') ?? today });
  const validPeriod = !!period.from && !!period.to && period.from <= period.to;

  return (
    <div className="space-y-4">
      <div>
        <h1 className="text-2xl font-semibold">{tx('Cost close')}</h1>
        <p className="text-sm text-muted-foreground">
          {tx('The month-end costing checklist: what still needs posting before the period can be locked, why, and where to do it.')}
        </p>
      </div>
      <div className="flex flex-wrap items-center gap-2">
        {tabs.map(([k, label]) => (
          <Button key={k} size="sm" variant={tab === k ? 'default' : 'outline'} onClick={() => setTab(k)}>{label}</Button>
        ))}
        <div className="ml-auto flex items-center gap-2">
          <Label className="text-xs text-muted-foreground">{tx('Period')}</Label>
          <Input type="date" className="w-40" value={period.from} onChange={(e) => setPeriod({ ...period, from: e.target.value })} />
          <span className="text-muted-foreground">–</span>
          <Input type="date" className="w-40" value={period.to} onChange={(e) => setPeriod({ ...period, to: e.target.value })} />
        </div>
      </div>
      {!validPeriod && <p className="text-sm text-destructive">{tx('Choose a period whose start is on or before its end.')}</p>}
      {tab === 'checklist' && validPeriod && (
        <Checklist period={period} canManage={has(PERMISSIONS.costingClose.manage)} onTab={setTab} />
      )}
      {tab === 'reconciliations' && validPeriod && <Reconciliations asOf={period.to} />}
      {tab === 'runs' && validPeriod && <AdjustmentRuns period={period} canManage={has(PERMISSIONS.costingClose.manage)} />}
      {tab === 'locks' && <Locks canReopen={has(PERMISSIONS.costingClose.reopen)} />}
      {tab === 'adjuncts' && canAdjuncts && (
        <PriceDifferences canPost={has(PERMISSIONS.inventoryValuation.post)} canLocations={has(PERMISSIONS.inventoryLocation.read)} />
      )}
    </div>
  );
}

function Checklist({ period, canManage, onTab }: { period: Period; canManage: boolean; onTab: (t: Tab) => void }) {
  const qc = useQueryClient();
  const gates = useQuery({
    queryKey: ['cost-close', 'gates', period.from, period.to],
    queryFn: async () => (await api.get<GatesResponse>('/costing/close/gates', { params: period })).data,
  });
  const { data: locks = [] } = useLocks();
  const lockedBy = locks.find((l) => l.status === 'locked' && day(l.periodFrom) <= period.to && day(l.periodTo) >= period.from);
  const [confirming, setConfirming] = useState(false);
  const lock = useMutation({
    mutationFn: async () => (await api.post<Row>('/costing/close/locks', period)).data,
    onSuccess: () => {
      notify.success(tx('Costing period locked'));
      setConfirming(false);
      void qc.invalidateQueries({ queryKey: ['cost-close'] });
    },
    onError: (e) => notify.error(apiErrorMessage(e, tx('The period could not be locked'))),
  });

  const data = gates.data;
  const applicable = (data?.gates ?? []).filter((g) => g.status !== 'n/a');
  const done = applicable.filter((g) => g.status === 'pass').length;
  const blocking = data?.blocking ?? null;

  return (
    <>
      <Card className={data?.ready ? 'border-emerald-500/50' : blocking ? 'border-amber-500/50' : undefined}>
        <CardContent className="flex flex-wrap items-center justify-between gap-3 py-4">
          <div className="flex items-start gap-3">
            {data?.ready ? <CheckCircle2 className="mt-0.5 h-5 w-5 text-emerald-600" /> : <AlertTriangle className="mt-0.5 h-5 w-5 text-amber-500" />}
            <div>
              {gates.isLoading ? (
                <p className="text-sm text-muted-foreground">{tx('Checking the period…')}</p>
              ) : data?.ready ? (
                <p className="font-medium">{tx('Every step is done for {{from}} – {{to}}. The period can be locked.', { from: data.from, to: data.to })}</p>
              ) : blocking ? (
                <p className="font-medium">
                  {tx('Step {{seq}} still needs attention: {{title}}.', { seq: blocking.seq, title: GATES[blocking.key]?.title() ?? blocking.label })}
                </p>
              ) : null}
              <p className="text-sm text-muted-foreground">
                {tx('{{done}} of {{total}} applicable steps done. Steps that do not apply to your business are marked “Not needed”.', { done, total: applicable.length })}
              </p>
              {lockedBy && (
                <p className="mt-1 flex items-center gap-1 text-sm text-emerald-700">
                  <Lock className="h-3.5 w-3.5" />
                  {tx('Locked: {{from}} – {{to}} (since {{at}}).', { from: day(lockedBy.periodFrom), to: day(lockedBy.periodTo), at: dateTime(s(lockedBy.lockedAt)) })}
                </p>
              )}
            </div>
          </div>
          <div className="flex gap-2">
            <Button variant="outline" size="sm" disabled={gates.isFetching} onClick={() => void gates.refetch()}>
              {gates.isFetching ? <Loader2 className="h-4 w-4 animate-spin" /> : <RotateCcw className="h-4 w-4" />} {tx('Check again')}
            </Button>
            {canManage && !lockedBy && (
              <Button size="sm" disabled={!data?.ready || lock.isPending} onClick={() => setConfirming(true)}>
                <Lock className="h-4 w-4" /> {tx('Lock this period')}
              </Button>
            )}
          </div>
        </CardContent>
      </Card>

      <div className="space-y-2">
        {(data?.gates ?? []).map((g) => {
          const copy = GATES[g.key];
          return (
            <Card key={g.key} className={g.status === 'fail' ? 'border-destructive/40' : undefined}>
              <CardContent className="space-y-2 py-4">
                <div className="flex flex-wrap items-center justify-between gap-2">
                  <div className="flex items-center gap-3">
                    <span className="flex h-7 w-7 items-center justify-center rounded-full border text-sm font-medium">{g.seq}</span>
                    <span className="font-medium">{copy?.title() ?? g.label}</span>
                  </div>
                  <GateStatus status={g.status} />
                </div>
                {copy && <p className="text-sm text-muted-foreground">{copy.what()}</p>}
                {g.status !== 'n/a' && g.detail && (
                  <p className="text-sm"><span className="text-muted-foreground">{tx('Found')}:</span> {g.detail}</p>
                )}
                {g.status === 'fail' && copy && (
                  <div className="flex flex-wrap items-center justify-between gap-2 rounded-md bg-muted/50 p-2 text-sm">
                    <span><span className="font-medium">{tx('What to do')}:</span> {copy.fix()}</span>
                    {copy.tab ? (
                      <Button size="sm" variant="outline" onClick={() => onTab(copy.tab as Tab)}>{copy.toLabel?.()}</Button>
                    ) : copy.to ? (
                      <Button size="sm" variant="outline" asChild>
                        <Link to={copy.to}>{copy.toLabel?.()} <ExternalLink className="h-3.5 w-3.5" /></Link>
                      </Button>
                    ) : null}
                  </div>
                )}
              </CardContent>
            </Card>
          );
        })}
      </div>

      <Dialog open={confirming} onOpenChange={setConfirming}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>{tx('Lock {{from}} – {{to}}?', { from: period.from, to: period.to })}</DialogTitle>
            <DialogDescription>
              {tx('Once locked, nobody can post, reverse or back-date a cost change into this period, so the reported stock values and margins stay as they are. A user with the reopen permission can unlock it later, giving a reason that is kept in the audit log.')}
            </DialogDescription>
          </DialogHeader>
          <DialogFooter>
            <Button variant="outline" onClick={() => setConfirming(false)}>{tx('Cancel')}</Button>
            <Button disabled={lock.isPending} onClick={() => lock.mutate()}>
              {lock.isPending && <Loader2 className="h-4 w-4 animate-spin" />} {tx('Lock the period')}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </>
  );
}

function Reconciliations({ asOf }: { asOf: string }) {
  const { data, isLoading } = useQuery({
    queryKey: ['cost-close', 'dashboard', asOf],
    queryFn: async () => (await api.get<{ asOf: string; rows: ReconRow[] }>('/costing/close/dashboard', { params: { asOf } })).data,
  });
  return (
    <Card>
      <CardHeader>
        <CardTitle>{tx('Sub-ledgers vs general ledger')}</CardTitle>
        <CardDescription>
          {tx('Each detailed ledger should add up to its control account in the general ledger as at {{asOf}}. A difference means something was posted to one side only.', { asOf: data?.asOf ?? asOf })}
        </CardDescription>
      </CardHeader>
      <CardContent>
        {isLoading ? (
          <p className="text-sm text-muted-foreground">{tx('Loading…')}</p>
        ) : (
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>{tx('Check')}</TableHead>
                <TableHead className="text-right">{tx('Difference')}</TableHead>
                <TableHead>{tx('Status')}</TableHead>
                <TableHead>{tx('What to do')}</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {(data?.rows ?? []).map((r) => {
                const copy = RECON[r.key];
                return (
                  <TableRow key={r.key}>
                    <TableCell className="font-medium">{copy?.title() ?? r.label}</TableCell>
                    <TableCell className="text-right tabular-nums">{money(num(r.difference))}</TableCell>
                    <TableCell>
                      {r.ok ? <Badge className="bg-emerald-600 text-white hover:bg-emerald-600">{tx('Agrees')}</Badge> : <Badge variant="destructive">{tx('Off')}</Badge>}
                    </TableCell>
                    <TableCell className="max-w-xl text-sm">
                      {r.ok ? (
                        <span className="text-muted-foreground">{tx('Nothing to do.')}</span>
                      ) : copy ? (
                        <div className="space-y-1">
                          <p>{copy.fix()}</p>
                          <Link to={copy.to} className="inline-flex items-center gap-1 text-primary hover:underline">{copy.toLabel()} <ExternalLink className="h-3 w-3" /></Link>
                        </div>
                      ) : null}
                    </TableCell>
                  </TableRow>
                );
              })}
            </TableBody>
          </Table>
        )}
      </CardContent>
    </Card>
  );
}

const ORDER_STATUS: Record<string, () => string> = {
  completed: () => tx('Completed'),
  closed: () => tx('Closed'),
};

function AdjustmentRuns({ period, canManage }: { period: Period; canManage: boolean }) {
  const qc = useQueryClient();
  const preview = useQuery({
    queryKey: ['cost-close', 'car-preview'],
    queryFn: async () => (await api.get<{ lines: Row[]; total: unknown }>('/costing/cost-adjustment-runs/preview')).data,
  });
  const runs = useQuery({
    queryKey: ['cost-close', 'car-runs'],
    queryFn: async () => (await api.get<Row[]>('/costing/cost-adjustment-runs')).data,
  });
  const [openId, setOpenId] = useState<string | null>(null);
  const detail = useQuery({
    queryKey: ['cost-close', 'car-run', openId],
    enabled: !!openId,
    queryFn: async () => (await api.get<Row>(`/costing/cost-adjustment-runs/${openId}`)).data,
  });
  const refresh = () => void qc.invalidateQueries({ queryKey: ['cost-close'] });
  const run = useMutation({
    mutationFn: async () => idempotentPost<Row>('/costing/cost-adjustment-runs', period),
    onSuccess: (r) => {
      notify.success(tx('Cost adjustment run {{number}} posted', { number: s(r?.number) }));
      refresh();
    },
    onError: (e) => notify.error(apiErrorMessage(e, tx('The cost adjustment run failed'))),
  });
  const [reversing, setReversing] = useState<Row | null>(null);
  const reverse = useMutation({
    mutationFn: async (id: string) => idempotentPost<Row>(`/costing/cost-adjustment-runs/${id}/reverse`),
    onSuccess: () => {
      notify.success(tx('Cost adjustment run reversed'));
      setReversing(null);
      refresh();
    },
    onError: (e) => notify.error(apiErrorMessage(e, tx('The run could not be reversed'))),
  });
  const lines = preview.data?.lines ?? [];
  const detailLines = (detail.data?.lines as Row[] | undefined) ?? [];

  return (
    <>
      <Card>
        <CardHeader className="flex flex-row flex-wrap items-start justify-between gap-2">
          <div>
            <CardTitle>{tx('Waiting to be carried forward')} · {money(num(preview.data?.total))}</CardTitle>
            <CardDescription className="max-w-3xl">
              {tx('When a cost arrives after its materials were already used by production (a late freight bill, a price difference), its share sits in work in process. Once the order is completed, a run moves it on to the finished goods — and to cost of sales for the part already sold.')}
            </CardDescription>
          </div>
          {canManage && (
            <Button disabled={lines.length === 0 || run.isPending} onClick={() => run.mutate()}>
              {run.isPending && <Loader2 className="h-4 w-4 animate-spin" />}
              {tx('Run for {{from}} – {{to}}', { from: period.from, to: period.to })}
            </Button>
          )}
        </CardHeader>
        <CardContent>
          {lines.length === 0 ? (
            <p className="text-sm text-muted-foreground">{tx('Nothing is waiting: no late cost sits in work in process of a completed order.')}</p>
          ) : (
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>{tx('Production order')}</TableHead>
                  <TableHead>{tx('Order status')}</TableHead>
                  <TableHead>{tx('Where the cost came from')}</TableHead>
                  <TableHead>{tx('Kind')}</TableHead>
                  <TableHead className="text-right">{tx('Amount')}</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {lines.map((l) => (
                  <TableRow key={s(l.id)}>
                    <TableCell className="font-medium">{s(l.orderCode)}</TableCell>
                    <TableCell>{ORDER_STATUS[s(l.orderStatus)]?.() ?? s(l.orderStatus)}</TableCell>
                    <TableCell>{adjustmentLabel(l.adjustmentType)} <span className="text-muted-foreground">· {referenceLabel(s(l.sourceType))}</span></TableCell>
                    <TableCell>{l.kind === 'release' ? tx('Released price difference') : tx('Late cost share')}</TableCell>
                    <TableCell className="text-right tabular-nums">{money(num(l.amount))}</TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          )}
        </CardContent>
      </Card>

      <Card>
        <CardHeader><CardTitle>{tx('Posted runs')}</CardTitle></CardHeader>
        <CardContent>
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>{tx('Number')}</TableHead>
                <TableHead>{tx('Period')}</TableHead>
                <TableHead className="text-right">{tx('Total')}</TableHead>
                <TableHead>{tx('Status')}</TableHead>
                <TableHead>{tx('Posted')}</TableHead>
                <TableHead />
              </TableRow>
            </TableHeader>
            <TableBody>
              {(runs.data ?? []).map((r) => (
                <TableRow key={s(r.id)}>
                  <TableCell className="font-medium">{s(r.number)}</TableCell>
                  <TableCell>{day(r.periodFrom)} – {day(r.periodTo)}</TableCell>
                  <TableCell className="text-right tabular-nums">{money(num(r.total))}</TableCell>
                  <TableCell>
                    {r.status === 'reversed' ? <Badge variant="secondary">{tx('Reversed')}</Badge> : <Badge variant="outline">{tx('Posted')}</Badge>}
                  </TableCell>
                  <TableCell className="text-sm text-muted-foreground">{dateTime(s(r.createdAt))}</TableCell>
                  <TableCell className="space-x-2 text-right">
                    <Button size="sm" variant="ghost" onClick={() => setOpenId(openId === s(r.id) ? null : s(r.id))}>
                      {openId === s(r.id) ? tx('Hide') : tx('Details')}
                    </Button>
                    {canManage && r.status === 'posted' && (
                      <Button size="sm" variant="outline" onClick={() => setReversing(r)}>{tx('Reverse')}</Button>
                    )}
                  </TableCell>
                </TableRow>
              ))}
              {(runs.data ?? []).length === 0 && (
                <TableRow><TableCell colSpan={6} className="text-sm text-muted-foreground">{tx('No runs yet.')}</TableCell></TableRow>
              )}
            </TableBody>
          </Table>
          {openId && (
            <div className="mt-3 rounded-md border p-3">
              <p className="mb-2 text-sm font-medium">{tx('Orders in run {{number}}', { number: s(detail.data?.number) })}</p>
              <Table>
                <TableHeader>
                  <TableRow>
                    <TableHead>{tx('Production order')}</TableHead>
                    <TableHead>{tx('Kind')}</TableHead>
                    <TableHead>{tx('Status')}</TableHead>
                    <TableHead className="text-right">{tx('Amount')}</TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {detailLines.map((l) => (
                    <TableRow key={s(l.id)}>
                      <TableCell>{s(l.orderCode)}</TableCell>
                      <TableCell>{l.releaseId ? tx('Released price difference') : tx('Late cost share')}</TableCell>
                      <TableCell>{l.status === 'reversed' ? tx('Reversed') : tx('Posted')}</TableCell>
                      <TableCell className="text-right tabular-nums">{money(num(l.amount))}</TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            </div>
          )}
        </CardContent>
      </Card>

      <Dialog open={!!reversing} onOpenChange={(open) => !open && setReversing(null)}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>{tx('Reverse run {{number}}?', { number: s(reversing?.number) })}</DialogTitle>
            <DialogDescription>
              {tx('The late costs go back to work in process of their orders and will be picked up by the next run. A run cannot be reversed once part of what it moved has itself been carried further (sold or used again) — post a correcting document instead.')}
            </DialogDescription>
          </DialogHeader>
          <DialogFooter>
            <Button variant="outline" onClick={() => setReversing(null)}>{tx('Cancel')}</Button>
            <Button variant="destructive" disabled={reverse.isPending} onClick={() => reversing && reverse.mutate(s(reversing.id))}>
              {reverse.isPending && <Loader2 className="h-4 w-4 animate-spin" />} {tx('Reverse')}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </>
  );
}

function Locks({ canReopen }: { canReopen: boolean }) {
  const qc = useQueryClient();
  const { data: locks = [], isLoading } = useLocks();
  const [reopening, setReopening] = useState<Row | null>(null);
  const [reason, setReason] = useState('');
  const reopen = useMutation({
    mutationFn: async () => (await api.post<Row>(`/costing/close/locks/${s(reopening?.id)}/reopen`, { reason: reason.trim() })).data,
    onSuccess: () => {
      notify.success(tx('Costing period reopened'));
      setReopening(null);
      setReason('');
      void qc.invalidateQueries({ queryKey: ['cost-close'] });
    },
    onError: (e) => notify.error(apiErrorMessage(e, tx('The period could not be reopened'))),
  });
  return (
    <>
      <Card>
        <CardHeader>
          <CardTitle>{tx('Locked periods')}</CardTitle>
          <CardDescription>{tx('A locked period accepts no cost changes. Reopening needs a reason, which is kept in the audit log.')}</CardDescription>
        </CardHeader>
        <CardContent>
          {isLoading ? (
            <p className="text-sm text-muted-foreground">{tx('Loading…')}</p>
          ) : (
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>{tx('Period')}</TableHead>
                  <TableHead>{tx('Status')}</TableHead>
                  <TableHead>{tx('Locked on')}</TableHead>
                  <TableHead>{tx('Checks at locking')}</TableHead>
                  <TableHead>{tx('Reopened')}</TableHead>
                  <TableHead />
                </TableRow>
              </TableHeader>
              <TableBody>
                {locks.map((l) => {
                  const snapshot = parseJson<Gate[]>(l.gates, []);
                  const passed = snapshot.filter((g) => g.status === 'pass').length;
                  const applicable = snapshot.filter((g) => g.status !== 'n/a').length;
                  return (
                    <TableRow key={s(l.id)}>
                      <TableCell className="font-medium">{day(l.periodFrom)} – {day(l.periodTo)}</TableCell>
                      <TableCell>
                        {l.status === 'locked'
                          ? <Badge className="gap-1 bg-emerald-600 text-white hover:bg-emerald-600"><Lock className="h-3 w-3" />{tx('Locked')}</Badge>
                          : <Badge variant="secondary" className="gap-1"><Unlock className="h-3 w-3" />{tx('Reopened')}</Badge>}
                      </TableCell>
                      <TableCell className="text-sm">{dateTime(s(l.lockedAt))}</TableCell>
                      <TableCell className="text-sm">
                        {snapshot.length > 0 ? tx('{{passed}} of {{applicable}} done', { passed, applicable }) : '—'}
                      </TableCell>
                      <TableCell className="max-w-sm text-sm">
                        {l.reopenedAt ? (
                          <>
                            <div>{dateTime(s(l.reopenedAt))}</div>
                            {l.reopenReason != null && <div className="text-muted-foreground">“{s(l.reopenReason)}”</div>}
                          </>
                        ) : '—'}
                      </TableCell>
                      <TableCell className="text-right">
                        {canReopen && l.status === 'locked' && (
                          <Button size="sm" variant="outline" onClick={() => { setReopening(l); setReason(''); }}>
                            <Unlock className="h-3.5 w-3.5" /> {tx('Reopen')}
                          </Button>
                        )}
                      </TableCell>
                    </TableRow>
                  );
                })}
                {locks.length === 0 && (
                  <TableRow><TableCell colSpan={6} className="text-sm text-muted-foreground">{tx('No period has been locked yet.')}</TableCell></TableRow>
                )}
              </TableBody>
            </Table>
          )}
        </CardContent>
      </Card>

      <Dialog open={!!reopening} onOpenChange={(open) => !open && setReopening(null)}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>{tx('Reopen {{from}} – {{to}}?', { from: day(reopening?.periodFrom), to: day(reopening?.periodTo) })}</DialogTitle>
            <DialogDescription>
              {tx('Cost changes can then be posted into this period again, which can change stock values and margins already reported. Lock it again when the correction is done.')}
            </DialogDescription>
          </DialogHeader>
          <div className="grid gap-1.5">
            <Label htmlFor="reopen-reason">{tx('Why does it need reopening? (at least 10 characters)')}</Label>
            <Textarea id="reopen-reason" value={reason} onChange={(e) => setReason(e.target.value)} rows={3} />
          </div>
          <DialogFooter>
            <Button variant="outline" onClick={() => setReopening(null)}>{tx('Cancel')}</Button>
            <Button disabled={reason.trim().length < 10 || reopen.isPending} onClick={() => reopen.mutate()}>
              {reopen.isPending && <Loader2 className="h-4 w-4 animate-spin" />} {tx('Reopen')}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </>
  );
}

function PriceDifferences({ canPost, canLocations }: { canPost: boolean; canLocations: boolean }) {
  const qc = useQueryClient();
  const { data: rows = [], isLoading } = useQuery({
    queryKey: ['cost-close', 'adjuncts'],
    queryFn: async () => (await api.get<Row[]>('/inventory/price-differences')).data,
  });
  const { data: locationNames } = useQuery({
    queryKey: ['cost-close', 'locations'],
    enabled: canLocations,
    staleTime: 60_000,
    queryFn: async () => {
      const res = await api.get<{ data: Array<{ id: string; code?: string | null; name: string }> }>('/inventory/locations', { params: { pageSize: 500 } });
      return new Map((res.data?.data ?? []).map((l) => [l.id, l.name || s(l.code)]));
    },
  });
  const [confirming, setConfirming] = useState(false);
  const release = useMutation({
    mutationFn: async () => idempotentPost<Row>('/inventory/price-differences/release'),
    onSuccess: (r) => {
      notify.success(tx('Released {{amount}} from {{count}} open amount(s)', { amount: money(num(r?.released)), count: num(r?.adjustments) }), s(r?.number));
      setConfirming(false);
      void qc.invalidateQueries({ queryKey: ['cost-close'] });
    },
    onError: (e) => notify.error(apiErrorMessage(e, tx('The release failed'))),
  });
  const totals = useMemo(() => {
    const out = new Map<string, number>();
    for (const r of rows) out.set(s(r.valuationComponent), (out.get(s(r.valuationComponent)) ?? 0) + num(r.openAmount));
    return [...out.entries()];
  }, [rows]);

  return (
    <>
      <Card>
        <CardHeader className="flex flex-row flex-wrap items-start justify-between gap-2">
          <div>
            <CardTitle>{tx('Price differences and NRV allowances still beside stock')}</CardTitle>
            <CardDescription className="max-w-3xl">
              {tx('With standard costing, the difference between what you paid and the standard is kept beside the stock (account 1396) until that stock is used or sold; a net realisable value write-down is kept as an allowance (1397). Releasing moves the part whose stock has since been consumed to where the stock went — cost of sales, work in process or write-off.')}
            </CardDescription>
          </div>
          {canPost && (
            <Button disabled={rows.length === 0 || release.isPending} onClick={() => setConfirming(true)}>{tx('Release consumed part')}</Button>
          )}
        </CardHeader>
        <CardContent className="space-y-3">
          {totals.length > 0 && (
            <div className="flex flex-wrap gap-2">
              {totals.map(([k, v]) => (
                <div key={k} className="rounded-md border px-3 py-2 text-sm">
                  <div className="text-muted-foreground">{componentLabel(k)}</div>
                  <div className="font-medium tabular-nums">{money(v)}</div>
                </div>
              ))}
            </div>
          )}
          {isLoading ? (
            <p className="text-sm text-muted-foreground">{tx('Loading…')}</p>
          ) : rows.length === 0 ? (
            <p className="text-sm text-muted-foreground">{tx('Nothing open: no price difference or NRV allowance is waiting to be released.')}</p>
          ) : (
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>{tx('Product')}</TableHead>
                  <TableHead>{tx('Location')}</TableHead>
                  <TableHead>{tx('Kind')}</TableHead>
                  <TableHead className="text-right">{tx('Documents')}</TableHead>
                  <TableHead className="text-right">{tx('Open amount')}</TableHead>
                  <TableHead />
                </TableRow>
              </TableHeader>
              <TableBody>
                {rows.map((r, i) => (
                  <TableRow key={`${s(r.productId)}-${s(r.locationId)}-${s(r.valuationComponent)}-${i}`}>
                    <TableCell className="font-medium">{s(r.productName)}</TableCell>
                    <TableCell>{locationNames?.get(s(r.locationId)) ?? '—'}</TableCell>
                    <TableCell>{componentLabel(r.valuationComponent)}</TableCell>
                    <TableCell className="text-right tabular-nums">{num(r.adjustments)}</TableCell>
                    <TableCell className="text-right tabular-nums">{money(num(r.openAmount))}</TableCell>
                    <TableCell className="text-right">
                      <Link
                        to={`/inventory/cost-explanation?productId=${encodeURIComponent(s(r.productId))}&locationId=${encodeURIComponent(s(r.locationId))}`}
                        className="text-sm text-primary hover:underline"
                      >
                        {tx('Why this cost?')}
                      </Link>
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          )}
          <p className="text-sm text-muted-foreground">
            {tx('Costs that could not be followed to where the stock went (lineage exceptions) are resolved in')}{' '}
            <Link to="/procurement/landed-cost-control" className="text-primary hover:underline">{tx('Landed Cost Control')}</Link>.
          </p>
        </CardContent>
      </Card>

      <Dialog open={confirming} onOpenChange={setConfirming}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>{tx('Release the consumed part now?')}</DialogTitle>
            <DialogDescription>
              {tx('One journal entry moves, for each open amount, the share whose stock has been used or sold since it was recorded. The share still in stock stays open for a later release.')}
            </DialogDescription>
          </DialogHeader>
          <DialogFooter>
            <Button variant="outline" onClick={() => setConfirming(false)}>{tx('Cancel')}</Button>
            <Button disabled={release.isPending} onClick={() => release.mutate()}>
              {release.isPending && <Loader2 className="h-4 w-4 animate-spin" />} {tx('Release')}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </>
  );
}

export default CostClosePage;

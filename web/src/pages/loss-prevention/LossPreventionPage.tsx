import { useState } from 'react';
import { RefreshCw, ShieldAlert } from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card, CardContent } from '@/components/ui/card';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs';
import {
  Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle,
} from '@/components/ui/dialog';
import { DataTable, type Column } from '@/components/data-table';
import { DataTablePagination, DateRangeFilter, FilterSelect, ListCard, ListPageHeader, ListToolbar } from '@/components/list';
import { useAuthStore } from '@/stores/auth.store';
import { dateTime } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';
import { apiMessage, notify } from '@/lib/notify';
import {
  CAUSE_CODES, OUTCOMES, useLpBarVariance, useLpByCashier, useLpCase, useLpCaseAction, useLpCases, useLpDashboard,
  useLpPerformance, useLpReplay, useLpRules, useLpRunComparison, useLpRuns, useLpSync, useSaveLpRule,
  type BarRow, type CashierRow, type Finding, type LpCase, type Rule, type Run, type Severity, type Summary,
} from '@/features/loss-prevention/api';

const SEVERITY: Record<Severity, 'destructive' | 'default' | 'secondary'> = {
  critical: 'destructive',
  warning: 'default',
  info: 'secondary',
};
const SELECT = 'h-9 w-full rounded-md border bg-background px-2 text-sm';
const TABLE = { cellClassName: 'py-2.5 px-4', headerRowClassName: 'h-10' } as const;

const severityLabel = (s: string) => (s === 'critical' ? tx('Critical') : s === 'warning' ? tx('Warning') : tx('Info'));
const STATE_LABELS: Record<string, string> = {
  open: tx('Not started'), reviewing: tx('In review'), escalated: tx('Escalated'), closed: tx('Closed'),
};
const OUTCOME_LABELS: Record<string, string> = {
  explained: tx('Explained'), confirmed_variance: tx('Confirmed variance'), false_alarm: tx('False alarm'),
  inconclusive: tx('Inconclusive'),
};
const ENGINE_LABELS: Record<string, string> = { cashier: tx('Cashier & kitchen'), bar: tx('Bar') };
const TIMELINE_LABELS: Record<string, string> = {
  opened: tx('Case opened'), viewed: tx('Viewed'), assigned: tx('Assigned'), note: tx('Note'),
  review_started: tx('Review started'), escalated: tx('Escalated'), finding_changed: tx('Figures changed'),
  finding_withdrawn: tx('No longer found'), closed: tx('Closed'), reopened: tx('Reopened'),
};
const words = (code: string) => code.replace(/_/g, ' ');
const num = (v: number | string | null | undefined, digits = 2) =>
  v === null || v === undefined || v === '' ? '—' : Number(v).toLocaleString(undefined, { maximumFractionDigits: digits });

/** Loss prevention (ADR-S077): variances found by reconciliation, reviewed as cases. A case states facts, not blame. */
export function LossPreventionPage() {
  const can = useAuthStore((s) => s.hasPermission);
  const canConfigure = can(PERMISSIONS.lossPrevention.configure);
  const canInvestigate = can(PERMISSIONS.lossPrevention.investigate);
  const sync = useLpSync();
  const onSync = () => sync.mutate(undefined, {
    onSuccess: (r) => notify.success(`${tx('Records read')}. ${r.windowsReconciled} ${tx('day(s) reconciled')}`),
    onError: (e) => notify.error(apiMessage(e, tx('Could not read the records'))),
  });
  return (
    <div className="space-y-4">
      <ListPageHeader icon={ShieldAlert} title={tx('Loss Prevention')}
        description={tx('Recorded sales, voids, cash and stock counts are reconciled on a schedule. A case is a variance to review, not an accusation.')}
        actions={canConfigure ? (
          <Button variant="outline" disabled={sync.isPending} onClick={onSync}>
            <RefreshCw className="mr-1.5 h-4 w-4" />{tx('Read records now')}
          </Button>
        ) : undefined} />
      <Tabs defaultValue="overview">
        <TabsList>
          <TabsTrigger value="overview">{tx('Overview')}</TabsTrigger>
          <TabsTrigger value="cases">{tx('Cases')}</TabsTrigger>
          <TabsTrigger value="reports">{tx('Reports')}</TabsTrigger>
          <TabsTrigger value="runs">{tx('Reconciliation runs')}</TabsTrigger>
          <TabsTrigger value="rules">{tx('Rules')}</TabsTrigger>
        </TabsList>
        <TabsContent value="overview" className="mt-4 space-y-4"><OverviewTab /></TabsContent>
        <TabsContent value="cases" className="mt-4 space-y-4"><CasesTab canInvestigate={canInvestigate} canConfigure={canConfigure} /></TabsContent>
        <TabsContent value="reports" className="mt-4 space-y-4"><ReportsTab /></TabsContent>
        <TabsContent value="runs" className="mt-4 space-y-4"><RunsTab canConfigure={canConfigure} /></TabsContent>
        <TabsContent value="rules" className="mt-4 space-y-4"><RulesTab canConfigure={canConfigure} /></TabsContent>
      </Tabs>
    </div>
  );
}

function Stat({ label, value, hint }: { label: string; value: string | number; hint?: string }) {
  return (
    <Card>
      <CardContent className="p-4">
        <p className="text-xs text-muted-foreground">{label}</p>
        <p className="text-2xl font-semibold tabular-nums">{value}</p>
        {hint && <p className="text-xs text-muted-foreground">{hint}</p>}
      </CardContent>
    </Card>
  );
}

function OverviewTab() {
  const { data, isLoading, isError } = useLpDashboard();
  if (isLoading) return <p className="text-muted-foreground">{tx('Loading…')}</p>;
  if (isError || !data) return <p className="text-destructive">{tx('Could not load the overview.')}</p>;
  const t = data.totals;
  const peak = Math.max(1, ...data.trend.map((d) => Number(d.cases)));
  return (
    <>
      <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-5">
        <Stat label={tx('Open cases')} value={t.open} />
        <Stat label={tx('Not started')} value={t.notStarted} />
        <Stat label={tx('In review')} value={t.reviewing} />
        <Stat label={tx('Escalated')} value={t.escalated} />
        <Stat label={tx('Exposure of open cases')} value={num(t.exposure)} hint={tx('An estimate; see each case for its method')} />
      </div>
      <div className="grid gap-3 lg:grid-cols-2">
        <Card>
          <CardContent className="space-y-2 p-4">
            <p className="font-medium">{tx('Open cases by rule')}</p>
            {data.byRule.length === 0 && <p className="text-sm text-muted-foreground">{tx('Nothing open.')}</p>}
            {data.byRule.map((r) => (
              <div key={r.ruleCode} className="flex items-center justify-between gap-3 text-sm">
                <span><span className="mr-2 font-mono text-xs text-muted-foreground">{r.ruleCode}</span>{r.title}</span>
                <span className="font-medium tabular-nums">{r.cases}</span>
              </div>
            ))}
          </CardContent>
        </Card>
        <Card>
          <CardContent className="space-y-2 p-4">
            <p className="font-medium">{tx('Cases per day, last 30 days')}</p>
            {data.trend.length === 0 && <p className="text-sm text-muted-foreground">{tx('No cases in this period.')}</p>}
            <div className="flex h-28 items-end gap-0.5">
              {data.trend.map((d) => (
                <div key={d.day} className="flex-1 rounded-t bg-primary/70" title={`${d.day}: ${d.cases}`}
                  style={{ height: `${Math.max(4, (Number(d.cases) / peak) * 100)}%` }} />
              ))}
            </div>
          </CardContent>
        </Card>
      </div>
      <p className="text-xs text-muted-foreground">{data.disclaimer}</p>
    </>
  );
}

function CasesTab({ canInvestigate, canConfigure }: { canInvestigate: boolean; canConfigure: boolean }) {
  const [state, setState] = useState('');
  const [engine, setEngine] = useState('');
  const [severity, setSeverity] = useState('');
  const [from, setFrom] = useState('');
  const [to, setTo] = useState('');
  const [page, setPage] = useState(1);
  const [pageSize, setPageSize] = useState(25);
  const [openId, setOpenId] = useState<string | null>(null);
  const filters = {
    state: state || undefined, engine: engine || undefined, severity: severity || undefined,
    from: from || undefined, to: to || undefined,
  };
  const { data, isLoading, isError } = useLpCases({ ...filters, page, pageSize });
  const columns: Column<LpCase>[] = [
    { key: 'caseNumber', header: tx('Case'), render: (c) => <span className="font-mono text-xs">{c.caseNumber}</span> },
    { key: 'businessDate', header: tx('Business day'), render: (c) => c.businessDate },
    { key: 'severity', header: tx('Severity'), render: (c) => <Badge variant={SEVERITY[c.severity]}>{severityLabel(c.severity)}</Badge> },
    {
      key: 'title', header: tx('Variance'), render: (c) => (
        <div className="min-w-[16rem]">
          <button type="button" className="text-left font-medium hover:underline" onClick={() => setOpenId(c.id)}>{c.title}</button>
          <p className="text-xs text-muted-foreground">{c.ruleCode} · {ENGINE_LABELS[c.engine] ?? c.engine}{c.subjectName ? ` · ${c.subjectName}` : ''}</p>
        </div>
      ),
    },
    { key: 'exposureAmount', header: tx('Exposure'), className: 'text-right', render: (c) => num(c.exposureAmount) },
    {
      key: 'state', header: tx('Status'), render: (c) => (
        <span className="text-sm">{c.outcome ? OUTCOME_LABELS[c.outcome] : STATE_LABELS[c.state]}</span>
      ),
    },
    { key: 'assigneeName', header: tx('Assigned to'), render: (c) => c.assigneeName ?? '—' },
  ];
  return (
    <>
      <ListToolbar>
        <FilterSelect value={state} onChange={(v) => { setState(v); setPage(1); }} allLabel={tx('All statuses')}
          options={Object.entries(STATE_LABELS).map(([value, label]) => ({ value, label }))} />
        <FilterSelect value={engine} onChange={(v) => { setEngine(v); setPage(1); }} allLabel={tx('All areas')}
          options={Object.entries(ENGINE_LABELS).map(([value, label]) => ({ value, label }))} />
        <FilterSelect value={severity} onChange={(v) => { setSeverity(v); setPage(1); }} allLabel={tx('All severities')}
          options={['critical', 'warning', 'info'].map((s) => ({ value: s, label: severityLabel(s) }))} />
        <DateRangeFilter from={from} to={to} onChange={(f, t) => { setFrom(f); setTo(t); setPage(1); }} />
      </ListToolbar>
      <ListCard>
        <div className="overflow-x-auto">
          <DataTable columns={columns} data={data?.data ?? []} loading={isLoading} loadingRows={6} getRowId={(c) => c.id}
            {...TABLE} emptyMessage={isError ? tx('Could not load the cases. Try again.') : tx('No cases here.')} />
        </div>
      </ListCard>
      {data?.meta && (
        <DataTablePagination page={data.meta.page} pageSize={pageSize} total={data.meta.total} totalPages={data.meta.totalPages}
          onPageChange={setPage} onPageSizeChange={setPageSize} noun="case" />
      )}
      {openId && (
        <CaseDialog id={openId} canInvestigate={canInvestigate} canConfigure={canConfigure} onClose={() => setOpenId(null)} />
      )}
    </>
  );
}

function FindingFigures({ f }: { f: Finding }) {
  return (
    <div className="grid grid-cols-3 gap-2 text-sm">
      <div><p className="text-xs text-muted-foreground">{tx('Expected')}</p><p className="tabular-nums">{num(f.expected)} {f.unit ?? ''}</p></div>
      <div><p className="text-xs text-muted-foreground">{tx('Recorded or counted')}</p><p className="tabular-nums">{num(f.observed)} {f.unit ?? ''}</p></div>
      <div><p className="text-xs text-muted-foreground">{tx('Difference')}</p><p className="font-medium tabular-nums">{num(f.delta)} {f.unit ?? ''}</p></div>
    </div>
  );
}

function CaseDialog({ id, canInvestigate, canConfigure, onClose }: {
  id: string; canInvestigate: boolean; canConfigure: boolean; onClose: () => void;
}) {
  const { data: c, isLoading, isError } = useLpCase(id);
  const action = useLpCaseAction();
  const [note, setNote] = useState('');
  const [outcome, setOutcome] = useState('');
  const [cause, setCause] = useState('');
  const run = (name: string, body?: Record<string, unknown>, done?: () => void) => action.mutate({ id, action: name, body }, {
    onSuccess: () => { notify.success(tx('Saved')); done?.(); },
    onError: (e) => notify.error(apiMessage(e, tx('The action failed'))),
  });
  const current = c?.findings.find((f) => f.status === 'active') ?? c?.findings[0];
  const closed = c?.state === 'closed';
  const closeInvalid = !outcome || (outcome === 'explained' && !cause);
  return (
    <Dialog open onOpenChange={(o) => { if (!o) onClose(); }}>
      <DialogContent className="max-h-[90vh] max-w-3xl overflow-y-auto">
        <DialogHeader>
          <DialogTitle>{c ? `${c.caseNumber} · ${c.title}` : tx('Case')}</DialogTitle>
          <DialogDescription>
            {tx('A case records a difference between what was recorded and what was counted or expected. It does not state a cause until a reviewer does.')}
          </DialogDescription>
        </DialogHeader>
        {isLoading && <p className="text-muted-foreground">{tx('Loading…')}</p>}
        {isError && <p className="text-destructive">{tx('Could not load the case.')}</p>}
        {c && (
          <div className="space-y-4">
            <div className="flex flex-wrap items-center gap-2 text-sm">
              <Badge variant={SEVERITY[c.severity]}>{severityLabel(c.severity)}</Badge>
              <Badge variant="secondary">{c.outcome ? OUTCOME_LABELS[c.outcome] : STATE_LABELS[c.state]}</Badge>
              {c.causeCode && <Badge variant="secondary">{words(c.causeCode)}</Badge>}
              <span className="text-muted-foreground">{c.ruleCode} · {c.businessDate}{c.subjectName ? ` · ${c.subjectName}` : ''}</span>
            </div>
            {current && (
              <Card>
                <CardContent className="space-y-3 p-4">
                  <FindingFigures f={current} />
                  {current.exposureAmount !== null && (
                    <p className="text-sm">{tx('Exposure estimate')}: <span className="font-medium tabular-nums">{num(current.exposureAmount)}</span>
                      {current.exposureMethod && <span className="text-muted-foreground"> ({current.exposureMethod})</span>}</p>
                  )}
                  {current.status === 'superseded' && (
                    <p className="text-sm text-muted-foreground">{tx('A later reconciliation run no longer finds this variance.')}</p>
                  )}
                  <dl className="grid grid-cols-2 gap-x-4 gap-y-1 text-xs">
                    {Object.entries(current.detail ?? {}).filter(([, v]) => v !== null && typeof v !== 'object').map(([k, v]) => (
                      <div key={k} className="flex justify-between gap-2"><dt className="text-muted-foreground">{k}</dt><dd>{String(v)}</dd></div>
                    ))}
                  </dl>
                </CardContent>
              </Card>
            )}
            <div>
              <p className="mb-1 text-sm font-medium">{tx('Records behind the figures')} ({c.events.length})</p>
              <div className="max-h-40 space-y-1 overflow-y-auto rounded-md border p-2 text-xs">
                {c.events.length === 0 && <p className="text-muted-foreground">{tx('This rule compares totals; it has no single record.')}</p>}
                {c.events.map((e) => (
                  <div key={e.id} className="flex justify-between gap-2">
                    <span>{dateTime(e.occurredAt)} · {e.type}</span>
                    <span className="text-muted-foreground">{e.evidenceSource} · {e.evidenceLevel} · {e.observationStatus}</span>
                  </div>
                ))}
              </div>
            </div>
            <div>
              <p className="mb-1 text-sm font-medium">{tx('History')}</p>
              <div className="max-h-48 space-y-1 overflow-y-auto rounded-md border p-2 text-xs">
                {c.timeline.filter((t) => t.kind !== 'viewed').map((t) => (
                  <div key={t.id}>
                    <span className="text-muted-foreground">{dateTime(t.createdAt)} · {t.actorName ?? tx('System')} · </span>
                    <span className="font-medium">{TIMELINE_LABELS[t.kind] ?? t.kind}</span>
                    {t.note && <span> — {t.note}</span>}
                  </div>
                ))}
              </div>
            </div>
            {canInvestigate && (
              <div className="space-y-3 border-t pt-3">
                <div className="space-y-1">
                  <Label>{tx('Note')}</Label>
                  <div className="flex gap-2">
                    <Input value={note} onChange={(e) => setNote(e.target.value)} placeholder={tx('What was checked and what was found')} />
                    <Button variant="outline" disabled={!note.trim() || action.isPending}
                      onClick={() => run('notes', { note: note.trim() }, () => setNote(''))}>{tx('Add note')}</Button>
                  </div>
                </div>
                {!closed && (
                  <>
                    <div className="flex flex-wrap gap-2">
                      {c.state !== 'reviewing' && (
                        <Button variant="outline" disabled={action.isPending} onClick={() => run('start-review')}>{tx('Start review')}</Button>
                      )}
                      {c.state !== 'escalated' && (
                        <Button variant="outline" disabled={!note.trim() || action.isPending}
                          onClick={() => run('escalate', { reason: note.trim() }, () => setNote(''))}>{tx('Escalate (uses the note as the reason)')}</Button>
                      )}
                    </div>
                    <div className="grid gap-2 sm:grid-cols-3">
                      <div className="space-y-1">
                        <Label>{tx('Outcome')}</Label>
                        <select className={SELECT} value={outcome} onChange={(e) => setOutcome(e.target.value)}>
                          <option value="">{tx('Choose…')}</option>
                          {OUTCOMES.map((o) => <option key={o} value={o}>{OUTCOME_LABELS[o]}</option>)}
                        </select>
                      </div>
                      <div className="space-y-1">
                        <Label>{tx('Cause')}{outcome === 'explained' ? ' *' : ''}</Label>
                        <select className={SELECT} value={cause} onChange={(e) => setCause(e.target.value)}>
                          <option value="">{tx('Not established')}</option>
                          {CAUSE_CODES.map((k) => <option key={k} value={k}>{words(k)}</option>)}
                        </select>
                      </div>
                      <div className="flex items-end">
                        <Button className="w-full" disabled={closeInvalid || action.isPending}
                          onClick={() => run('close', { outcome, causeCode: cause || null, note: note.trim() || null }, onClose)}>{tx('Close case')}</Button>
                      </div>
                    </div>
                  </>
                )}
                {closed && canConfigure && (
                  <Button variant="outline" disabled={!note.trim() || action.isPending}
                    onClick={() => run('reopen', { reason: note.trim() }, () => setNote(''))}>{tx('Reopen (uses the note as the reason)')}</Button>
                )}
              </div>
            )}
          </div>
        )}
        <DialogFooter>
          <Button variant="outline" onClick={onClose}>{tx('Close window')}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

function change(now: number, before: number) {
  if (now === before) return tx('same as the period before');
  return `${now > before ? '+' : ''}${now - before} ${tx('against the period before')}`;
}

function ReportsTab() {
  const [from, setFrom] = useState('');
  const [to, setTo] = useState('');
  const filters = { from: from || undefined, to: to || undefined };
  const perf = useLpPerformance(filters);
  const people = useLpByCashier(filters);
  const bar = useLpBarVariance(filters);
  const s: Summary | undefined = perf.data?.summary;
  const p: Summary | undefined = perf.data?.previous.summary;
  const ruleColumns: Column<NonNullable<typeof perf.data>['byRule'][number]>[] = [
    { key: 'ruleCode', header: tx('Rule'), render: (r) => <span><span className="mr-2 font-mono text-xs text-muted-foreground">{r.ruleCode}</span>{r.title}</span> },
    { key: 'cases', header: tx('Cases'), className: 'text-right', render: (r) => r.cases },
    { key: 'confirmed', header: tx('Confirmed variances'), className: 'text-right', render: (r) => r.confirmed },
    { key: 'falseAlarm', header: tx('False alarms'), className: 'text-right', render: (r) => r.falseAlarm },
    { key: 'exposure', header: tx('Exposure estimate'), className: 'text-right', render: (r) => num(r.exposure) },
    { key: 'exposureMethod', header: tx('How it is calculated'), render: (r) => r.exposureMethod ?? '—' },
  ];
  const peopleColumns: Column<CashierRow>[] = [
    { key: 'subjectName', header: tx('Person'), render: (r) => r.subjectName ?? r.subjectId },
    { key: 'cases', header: tx('Cases'), className: 'text-right', render: (r) => r.cases },
    { key: 'open', header: tx('Open'), className: 'text-right', render: (r) => r.open },
    { key: 'confirmed', header: tx('Confirmed variances'), className: 'text-right', render: (r) => r.confirmed },
    { key: 'explained', header: tx('Explained'), className: 'text-right', render: (r) => r.explained },
    { key: 'falseAlarm', header: tx('False alarms'), className: 'text-right', render: (r) => r.falseAlarm },
    { key: 'rules', header: tx('Rules'), render: (r) => (r.rules ?? []).join(', ') },
  ];
  const barColumns: Column<BarRow>[] = [
    { key: 'productName', header: tx('Product'), render: (r) => r.productName ?? r.productId },
    { key: 'lines', header: tx('Counts'), className: 'text-right', render: (r) => r.lines },
    { key: 'soldMl', header: tx('Sold (ml)'), className: 'text-right', render: (r) => num(r.soldMl, 0) },
    { key: 'varianceMl', header: tx('Variance (ml)'), className: 'text-right', render: (r) => num(r.varianceMl, 0) },
    { key: 'shortageMl', header: tx('Shortage (ml)'), className: 'text-right', render: (r) => num(r.shortageMl, 0) },
    { key: 'withoutCause', header: tx('Counts without a cause'), className: 'text-right', render: (r) => r.withoutCause },
  ];
  return (
    <>
      <ListToolbar>
        <DateRangeFilter from={from} to={to} onChange={(f, t) => { setFrom(f); setTo(t); }} />
        {perf.data && <span className="text-sm text-muted-foreground">{perf.data.from} – {perf.data.to}</span>}
      </ListToolbar>
      {perf.isError && <p className="text-destructive">{tx('Could not load the report. Try again.')}</p>}
      {s && p && (
        <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
          <Stat label={tx('Cases generated')} value={s.generated} hint={change(Number(s.generated), Number(p.generated))} />
          <Stat label={tx('Cases reviewed')} value={s.reviewed} hint={`${tx('Median hours to start')}: ${num(s.medianHoursToReview, 1)}`} />
          <Stat label={tx('Confirmed variances')} value={s.confirmedVariance} hint={change(Number(s.confirmedVariance), Number(p.confirmedVariance))} />
          <Stat label={tx('False alarms')} value={s.falseAlarm} hint={`${s.closedAutomatically} ${tx('closed automatically')}`} />
          <Stat label={tx('Explained')} value={s.explained} />
          <Stat label={tx('Inconclusive')} value={s.inconclusive} />
          <Stat label={tx('Escalated now')} value={s.escalated} />
          <Stat label={tx('Cases closed')} value={s.closed} hint={`${tx('Median hours to close')}: ${num(s.medianHoursToClose, 1)}`} />
        </div>
      )}
      <p className="text-sm font-medium">{tx('By rule')}</p>
      <ListCard>
        <div className="overflow-x-auto">
          <DataTable columns={ruleColumns} data={perf.data?.byRule ?? []} loading={perf.isLoading} loadingRows={4}
            getRowId={(r) => r.ruleCode} {...TABLE} emptyMessage={tx('No cases in this period.')} />
        </div>
      </ListCard>
      <p className="text-sm font-medium">{tx('By person')}</p>
      <ListCard>
        <div className="overflow-x-auto">
          <DataTable columns={peopleColumns} data={people.data?.rows ?? []} loading={people.isLoading} loadingRows={4}
            getRowId={(r) => r.subjectId} {...TABLE} emptyMessage={tx('No cases about a person in this period.')} />
        </div>
      </ListCard>
      <p className="text-sm font-medium">{tx('Bottle counts by product')}</p>
      <ListCard>
        <div className="overflow-x-auto">
          <DataTable columns={barColumns} data={bar.data?.rows ?? []} loading={bar.isLoading} loadingRows={4}
            getRowId={(r) => r.productId} {...TABLE} emptyMessage={tx('No bottle counts in this period.')} />
        </div>
      </ListCard>
      {perf.data && <p className="text-xs text-muted-foreground">{perf.data.disclaimer}</p>}
    </>
  );
}

function RunsTab({ canConfigure }: { canConfigure: boolean }) {
  const [engine, setEngine] = useState('');
  const [from, setFrom] = useState('');
  const [to, setTo] = useState('');
  const [page, setPage] = useState(1);
  const [pageSize, setPageSize] = useState(25);
  const [compare, setCompare] = useState<Run | null>(null);
  const { data, isLoading, isError } = useLpRuns({ engine: engine || undefined, from: from || undefined, to: to || undefined, page, pageSize });
  const replay = useLpReplay();
  const canReplay = canConfigure && !!engine && !!from && !!to;
  const onReplay = () => replay.mutate({ engine, from, to }, {
    onSuccess: (r) => notify.success(`${r.windows} ${tx('day(s) reconciled again')}, ${r.findings} ${tx('finding(s)')}`),
    onError: (e) => notify.error(apiMessage(e, tx('The replay failed'))),
  });
  const columns: Column<Run>[] = [
    { key: 'createdAt', header: tx('Run at'), render: (r) => dateTime(r.createdAt) },
    { key: 'businessDate', header: tx('Business day'), render: (r) => r.businessDate },
    { key: 'engine', header: tx('Area'), render: (r) => ENGINE_LABELS[r.engine] ?? r.engine },
    { key: 'trigger', header: tx('Why'), render: (r) => `${words(r.trigger)}${r.isFinal ? '' : ` · ${tx('day not finished')}`}` },
    { key: 'inputCount', header: tx('Records read'), className: 'text-right', render: (r) => r.inputCount },
    { key: 'findingCount', header: tx('Findings'), className: 'text-right', render: (r) => r.findingCount },
    {
      key: 'actions', header: '', render: (r) => (r.supersedesRunId ? (
        <Button size="sm" variant="outline" onClick={() => setCompare(r)}>{tx('Compare with previous')}</Button>
      ) : null),
    },
  ];
  return (
    <>
      <ListToolbar>
        <FilterSelect value={engine} onChange={(v) => { setEngine(v); setPage(1); }} allLabel={tx('All areas')}
          options={Object.entries(ENGINE_LABELS).map(([value, label]) => ({ value, label }))} />
        <DateRangeFilter from={from} to={to} onChange={(f, t) => { setFrom(f); setTo(t); setPage(1); }} />
        {canConfigure && (
          <Button size="sm" variant="outline" disabled={!canReplay || replay.isPending} onClick={onReplay}
            title={tx('Choose an area and a date range first. Earlier runs are kept.')}>
            <RefreshCw className="mr-1.5 h-4 w-4" />{tx('Reconcile this range again')}
          </Button>
        )}
      </ListToolbar>
      <ListCard>
        <div className="overflow-x-auto">
          <DataTable columns={columns} data={data?.data ?? []} loading={isLoading} loadingRows={6} getRowId={(r) => r.id}
            {...TABLE} emptyMessage={isError ? tx('Could not load the runs. Try again.') : tx('No runs here.')} />
        </div>
      </ListCard>
      {data?.meta && (
        <DataTablePagination page={data.meta.page} pageSize={pageSize} total={data.meta.total} totalPages={data.meta.totalPages}
          onPageChange={setPage} onPageSizeChange={setPageSize} noun="run" />
      )}
      {compare && <CompareDialog run={compare} onClose={() => setCompare(null)} />}
    </>
  );
}

function CompareDialog({ run, onClose }: { run: Run; onClose: () => void }) {
  const { data, isLoading, isError } = useLpRunComparison(run.supersedesRunId, run.id);
  const list = (title: string, rows: Finding[]) => (
    <div>
      <p className="text-sm font-medium">{title} ({rows.length})</p>
      {rows.map((f) => (
        <p key={f.id} className="text-sm text-muted-foreground">
          {f.ruleCode} · {f.title} · {num(f.delta)} {f.unit ?? ''}{f.caseNumber ? ` · ${f.caseNumber} (${STATE_LABELS[f.caseState ?? ''] ?? f.caseState})` : ''}
        </p>
      ))}
    </div>
  );
  return (
    <Dialog open onOpenChange={(o) => { if (!o) onClose(); }}>
      <DialogContent className="max-h-[90vh] max-w-2xl overflow-y-auto">
        <DialogHeader>
          <DialogTitle>{tx('What changed in this run')}</DialogTitle>
          <DialogDescription>{run.businessDate} · {ENGINE_LABELS[run.engine] ?? run.engine}</DialogDescription>
        </DialogHeader>
        {isLoading && <p className="text-muted-foreground">{tx('Loading…')}</p>}
        {isError && <p className="text-destructive">{tx('Could not compare the runs.')}</p>}
        {data && (
          <div className="space-y-3">
            <p className="text-sm">
              {data.inputChanged ? tx('New records arrived since the previous run.') : tx('The records are the same.')}{' '}
              {data.configChanged ? tx('The rules or settings changed.') : tx('The rules and settings are the same.')}
            </p>
            {list(tx('New findings'), data.added)}
            {list(tx('No longer found'), data.removed)}
            {list(tx('Figures changed'), data.changed.map((c) => c.after))}
            <p className="text-sm text-muted-foreground">{data.unchanged} {tx('finding(s) unchanged')}</p>
          </div>
        )}
        <DialogFooter><Button variant="outline" onClick={onClose}>{tx('Close window')}</Button></DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

function RulesTab({ canConfigure }: { canConfigure: boolean }) {
  const { data, isLoading, isError } = useLpRules();
  const [editing, setEditing] = useState<Rule | null>(null);
  if (isLoading) return <p className="text-muted-foreground">{tx('Loading…')}</p>;
  if (isError) return <p className="text-destructive">{tx('Could not load the rules.')}</p>;
  return (
    <div className="grid gap-3 md:grid-cols-2">
      {(data ?? []).map((r) => (
        <Card key={r.code}>
          <CardContent className="space-y-2 p-4">
            <div className="flex items-center justify-between gap-2">
              <p className="font-medium"><span className="mr-2 font-mono text-xs text-muted-foreground">{r.code}</span>{r.title}</p>
              <Badge variant={r.enabled && r.configured ? 'default' : 'secondary'}>
                {!r.enabled ? tx('Off') : r.configured ? tx('On') : tx('Needs a threshold')}
              </Badge>
            </div>
            <p className="text-xs text-muted-foreground">
              {ENGINE_LABELS[r.engine] ?? r.engine} · {severityLabel(r.severity)}
              {r.finalOnly ? ` · ${tx('runs when the business day is over')}` : ''}
              {r.version > 0 ? ` · ${tx('version')} ${r.version}` : ` · ${tx('default settings')}`}
            </p>
            <p className="text-sm">
              {Object.entries(r.params).map(([k, v]) => `${k}: ${v === null ? tx('not set') : Number(v)}`).join(' · ') || tx('No parameters')}
            </p>
            {canConfigure && <Button size="sm" variant="outline" onClick={() => setEditing(r)}>{tx('Edit')}</Button>}
          </CardContent>
        </Card>
      ))}
      {editing && <RuleDialog rule={editing} onClose={() => setEditing(null)} />}
    </div>
  );
}

function RuleDialog({ rule, onClose }: { rule: Rule; onClose: () => void }) {
  const [enabled, setEnabled] = useState(rule.enabled);
  const [severity, setSeverity] = useState<Severity>(rule.severity);
  const [params, setParams] = useState<Record<string, string>>(
    Object.fromEntries(Object.entries(rule.params).map(([k, v]) => [k, v === null ? '' : String(Number(v))])),
  );
  const save = useSaveLpRule();
  const invalid = Object.values(params).some((v) => v !== '' && (Number.isNaN(Number(v)) || Number(v) < 0));
  const onSave = () => save.mutate({
    code: rule.code,
    body: {
      enabled,
      severity,
      params: Object.fromEntries(Object.entries(params).map(([k, v]) => [k, v === '' ? null : Number(v)])),
    },
  }, {
    onSuccess: () => { notify.success(tx('Saved')); onClose(); },
    onError: (e) => notify.error(apiMessage(e, tx('Could not save'))),
  });
  return (
    <Dialog open onOpenChange={(o) => { if (!o) onClose(); }}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>{rule.code} · {rule.title}</DialogTitle>
          <DialogDescription>
            {tx('A change applies to new reconciliation runs. Past runs keep the settings they used; reconcile a range again to apply the change to it.')}
          </DialogDescription>
        </DialogHeader>
        <div className="space-y-3">
          <label className="flex items-center gap-2 text-sm">
            <input type="checkbox" checked={enabled} onChange={(e) => setEnabled(e.target.checked)} />
            {tx('This rule is on')}
          </label>
          <div className="space-y-1">
            <Label>{tx('Severity')}</Label>
            <select className={SELECT} value={severity} onChange={(e) => setSeverity(e.target.value as Severity)}>
              {(['info', 'warning', 'critical'] as Severity[]).map((s) => <option key={s} value={s}>{severityLabel(s)}</option>)}
            </select>
          </div>
          {Object.keys(params).map((k) => (
            <div key={k} className="space-y-1">
              <Label>{k}{rule.defaults.params[k] === null ? ` (${tx('the rule does not run until this is set')})` : ''}</Label>
              <Input type="number" min={0} value={params[k]} onChange={(e) => setParams((p) => ({ ...p, [k]: e.target.value }))} />
            </div>
          ))}
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={onClose}>{tx('Cancel')}</Button>
          <Button disabled={invalid || save.isPending} onClick={onSave}>{tx('Save')}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

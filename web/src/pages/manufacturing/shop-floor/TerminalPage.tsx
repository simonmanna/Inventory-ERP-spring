import { useEffect, useMemo, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import {
  AlertTriangle, ArrowLeft, CheckCircle2, Gauge, LogIn, LogOut, PackageMinus, Pause, Play, Plus, Square, Timer, Wrench,
} from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Textarea } from '@/components/ui/textarea';
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { useAuthStore } from '@/stores/auth.store';
import { notify, apiMessage } from '@/lib/notify';
import { dateTime, relativeTime } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';
import { cn } from '@/lib/utils';
import { useScrapReasons } from '@/features/manufacturing/api-advanced';
import { useOrderStages } from '@/features/manufacturing/api-stages';
import {
  qty, useBoard, useClockOff, useClockOn, useCompleteWorkOrder, useConfirmDowntime, useConfirmProduction, useEndDowntime,
  useGenerateWorkOrders, useLogDowntime, usePauseWorkOrder, useQueue, useReadMeter, useReasons, useRejectDowntime,
  useRejectProduction, useReportProduction, useStageIssueFromFloor, useStartWorkOrder,
  type Downtime, type ProductionReport, type Queue, type QueueItem,
} from '@/features/manufacturing/api-shopfloor';
import { BigNumber, FLOOR_BORDER, FloorStatusPill, ReasonGrid, ShiftReportStatusPill, isNum } from './shopfloor-ui';

const STORE_KEY = 'shopfloor.terminal.workCenter';

function remembered(): string {
  try { return window.localStorage.getItem(STORE_KEY) ?? ''; } catch { return ''; }
}
function remember(id: string) {
  try { if (id) window.localStorage.setItem(STORE_KEY, id); else window.localStorage.removeItem(STORE_KEY); } catch { /* ignore */ }
}

/** Operator terminal (ADR-S015): tablet-first, large touch targets; one work centre at a time. */
export function TerminalPage() {
  const [params, setParams] = useSearchParams();
  const wc = params.get('wc') || remembered();
  const choose = (id: string) => {
    remember(id);
    setParams(id ? { wc: id } : {}, { replace: true });
  };
  return (
    <div className="mx-auto max-w-5xl space-y-4 p-3 sm:p-6">
      {wc ? <Terminal workCenterId={wc} onChange={() => choose('')} /> : <WorkCentrePicker onPick={choose} />}
    </div>
  );
}

function WorkCentrePicker({ onPick }: { onPick: (id: string) => void }) {
  const board = useBoard();
  return (
    <div className="space-y-4">
      <div>
        <h1 className="text-2xl font-bold">{tx('Operator terminal')}</h1>
        <p className="text-sm text-muted-foreground">{tx('Choose your work centre.')}</p>
      </div>
      {board.isLoading && <p className="text-muted-foreground">{tx('Loading…')}</p>}
      {board.isError && <p className="text-destructive">{tx('Could not load the work centres.')}</p>}
      {board.data && board.data.workCenters.length === 0 && (
        <p className="text-muted-foreground">{tx('No active work centres. Create them under Work Centres.')}</p>
      )}
      <div className="grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-3">
        {board.data?.workCenters.map((c) => (
          <button key={c.workCenterId} type="button" onClick={() => onPick(c.workCenterId)}
            className={cn('min-h-[96px] rounded-xl border border-l-8 bg-card p-4 text-left shadow-sm transition hover:bg-muted', FLOOR_BORDER[c.status])}>
            <div className="flex items-center justify-between gap-2">
              <span className="text-lg font-semibold">{c.name}</span>
              <FloorStatusPill status={c.status} />
            </div>
            <div className="text-sm text-muted-foreground">{c.code}</div>
            {c.workOrders[0] && <div className="mt-1 text-sm">{c.workOrders[0].orderCode} · {c.workOrders[0].name}</div>}
          </button>
        ))}
      </div>
    </div>
  );
}

type Dlg =
  | { kind: 'stop'; item: QueueItem }
  | { kind: 'output'; item: QueueItem }
  | { kind: 'finish'; item: QueueItem }
  | { kind: 'issue'; item: QueueItem }
  | { kind: 'downtime' }
  | { kind: 'meter' }
  | { kind: 'confirm-output'; draft: ProductionReport }
  | { kind: 'confirm-stop'; draft: Downtime }
  | { kind: 'reject'; what: 'output' | 'stop'; id: string };

function Terminal({ workCenterId, onChange }: { workCenterId: string; onChange: () => void }) {
  const has = useAuthStore((s) => s.hasPermission);
  const me = useAuthStore((s) => s.user);
  const canManage = has(PERMISSIONS.workOrder.manage);
  const canReport = has(PERMISSIONS.productionOrder.complete);
  const canIssue = has(PERMISSIONS.productionOrder.start);
  const queue = useQueue(workCenterId);
  const [dlg, setDlg] = useState<Dlg | null>(null);
  const start = useStartWorkOrder();
  const generate = useGenerateWorkOrders();
  const clockOn = useClockOn();
  const clockOff = useClockOff();
  const endStop = useEndDowntime();

  const run = async (p: Promise<unknown>, ok: string, fail: string) => {
    try {
      await p;
      notify.success(ok);
    } catch (err) {
      notify.error(apiMessage(err, fail));
    }
  };

  if (queue.isLoading) return <p className="text-muted-foreground">{tx('Loading…')}</p>;
  if (queue.isError || !queue.data) {
    return (
      <div className="space-y-3">
        <p className="text-destructive">{tx('Could not load this work centre.')}</p>
        <Button variant="outline" onClick={onChange}><ArrowLeft className="mr-1.5 h-4 w-4" />{tx('Change work centre')}</Button>
      </div>
    );
  }
  const q: Queue = queue.data;
  const mine = q.operators.find((o) => o.userId === me?.id);
  const stops = q.openDowntime.filter((d) => d.status === 'confirmed');
  const status = stops.length ? 'down' : q.workOrders.some((w) => w.status === 'in_progress') ? 'running'
    : q.workOrders.some((w) => w.status === 'paused') ? 'paused' : 'idle';
  const drafts = q.productionDrafts.length + q.downtimeDrafts.length;

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div className="space-y-1">
          <Button variant="ghost" size="sm" className="-ml-2" onClick={onChange}><ArrowLeft className="mr-1 h-4 w-4" />{tx('Change work centre')}</Button>
          <div className="flex flex-wrap items-center gap-2">
            <h1 className="text-2xl font-bold">{q.workCenter.name}</h1>
            <FloorStatusPill status={status} />
          </div>
          <div className="flex flex-wrap items-center gap-2 text-sm text-muted-foreground">
            <span>{q.workCenter.code}</span>
            {q.currentShiftReport ? (
              <Link className="text-primary underline-offset-2 hover:underline" to={`/manufacturing/shift-reports/${q.currentShiftReport.id}`}>
                {q.currentShiftReport.code}
              </Link>
            ) : <span>{tx('No shift report yet')}</span>}
            {q.currentShiftReport && <ShiftReportStatusPill status={q.currentShiftReport.status} />}
            <span>· {tx('{{n}} on shift', { n: q.operators.length })}</span>
          </div>
        </div>
        {canManage && (
          mine ? (
            <Button size="lg" variant="outline" className="h-14 text-base" disabled={clockOff.isPending}
              onClick={() => run(clockOff.mutateAsync(mine.id), tx('Clocked off'), tx('Could not clock off'))}>
              <LogOut className="mr-2 h-5 w-5" />{tx('Clock off')}
            </Button>
          ) : (
            <Button size="lg" className="h-14 text-base" disabled={clockOn.isPending}
              onClick={() => run(clockOn.mutateAsync({ workCenterId }), tx('Clocked on'), tx('Could not clock on'))}>
              <LogIn className="mr-2 h-5 w-5" />{tx('Clock on')}
            </Button>
          )
        )}
      </div>

      {stops.map((d) => (
        <div key={d.id} className="flex flex-wrap items-center justify-between gap-3 rounded-xl border border-red-300 bg-red-50 p-4 dark:border-red-900 dark:bg-red-950/40">
          <div className="flex items-center gap-3">
            <AlertTriangle className="h-6 w-6 text-red-600" />
            <div>
              <div className="font-semibold">{tx('Stopped')}: {d.reasonName ?? d.reasonCode}</div>
              <div className="text-sm text-muted-foreground">{tx('since {{time}}', { time: relativeTime(d.startedAt) })}{d.workOrderName ? ` · ${d.workOrderName}` : ''}</div>
            </div>
          </div>
          {canManage && !d.workOrderId && (
            <Button size="lg" className="h-12" disabled={endStop.isPending}
              onClick={() => run(endStop.mutateAsync(d.id), tx('Stop ended'), tx('Could not end the stop'))}>
              {tx('End stop')}
            </Button>
          )}
          {d.workOrderId && <span className="text-sm text-muted-foreground">{tx('Resume the work order to end this stop.')}</span>}
        </div>
      ))}

      {canManage && (
        <div className="grid grid-cols-2 gap-2 sm:grid-cols-3">
          <Button variant="outline" className="h-14 text-base" onClick={() => setDlg({ kind: 'downtime' })}><Timer className="mr-2 h-5 w-5" />{tx('Log downtime')}</Button>
          <Button variant="outline" className="h-14 text-base" onClick={() => setDlg({ kind: 'meter' })}><Gauge className="mr-2 h-5 w-5" />{tx('Meter reading')}</Button>
          <Button variant="outline" className="col-span-2 h-14 text-base sm:col-span-1" asChild>
            <Link to="/manufacturing/floor-board"><Wrench className="mr-2 h-5 w-5" />{tx('Shop-floor live')}</Link>
          </Button>
        </div>
      )}

      <section className="space-y-3">
        <h2 className="text-lg font-semibold">{tx('Work orders')}</h2>
        {q.workOrders.length === 0 && q.stagesWithoutWorkOrders.length === 0 && (
          <p className="rounded-lg border border-dashed p-6 text-center text-muted-foreground">{tx('Nothing queued at this work centre.')}</p>
        )}
        {q.workOrders.map((w) => (
          <WorkOrderCard key={w.id} item={w} canManage={canManage} canReport={canReport} canIssue={canIssue}
            starting={start.isPending}
            onStart={() => run(start.mutateAsync(w.id), w.status === 'paused' ? tx('Resumed') : tx('Started'), tx('Could not start the work order'))}
            onDialog={setDlg} />
        ))}
        {q.stagesWithoutWorkOrders.map((s) => (
          <div key={s.stageId} className="flex flex-wrap items-center justify-between gap-3 rounded-xl border bg-card p-4">
            <div>
              <div className="font-semibold">{s.orderCode} · {s.name}</div>
              <div className="text-sm text-muted-foreground">{s.productName ?? ''} · {tx('No work orders yet')}</div>
            </div>
            {canManage && (
              <Button className="h-12" disabled={generate.isPending}
                onClick={() => run(generate.mutateAsync(s.productionOrderId), tx('Work orders created'), tx('Could not create the work orders'))}>
                <Plus className="mr-1.5 h-4 w-4" />{tx('Prepare work orders')}
              </Button>
            )}
          </div>
        ))}
      </section>

      {drafts > 0 && (
        <section className="space-y-3">
          <h2 className="text-lg font-semibold">{tx('Machine data to confirm')} <span className="text-muted-foreground">({drafts})</span></h2>
          {q.productionDrafts.map((d) => (
            <div key={d.id} className="flex flex-wrap items-center justify-between gap-3 rounded-xl border border-amber-300 bg-amber-50/60 p-4 dark:border-amber-900 dark:bg-amber-950/30">
              <div>
                <div className="font-semibold">{tx('Count')}: {qty(d.qtyGood)} {tx('good')} · {qty(d.qtyScrap)} {tx('scrap')}</div>
                <div className="text-sm text-muted-foreground">
                  {d.periodStart ? `${dateTime(d.periodStart)} → ${dateTime(d.periodEnd)}` : dateTime(d.reportedAt)}
                  {d.workOrderName ? ` · ${d.orderCode} · ${d.workOrderName}` : ` · ${tx('no work order matched')}`}
                </div>
              </div>
              {canReport && (
                <div className="flex gap-2">
                  <Button variant="outline" className="h-12" onClick={() => setDlg({ kind: 'reject', what: 'output', id: d.id })}>{tx('Reject')}</Button>
                  <Button className="h-12" onClick={() => setDlg({ kind: 'confirm-output', draft: d })}><CheckCircle2 className="mr-1.5 h-4 w-4" />{tx('Confirm')}</Button>
                </div>
              )}
            </div>
          ))}
          {q.downtimeDrafts.map((d) => (
            <div key={d.id} className="flex flex-wrap items-center justify-between gap-3 rounded-xl border border-amber-300 bg-amber-50/60 p-4 dark:border-amber-900 dark:bg-amber-950/30">
              <div>
                <div className="font-semibold">{tx('Machine stop')}: {d.reasonName ?? d.reasonCode ?? tx('no reason')}</div>
                <div className="text-sm text-muted-foreground">{dateTime(d.startedAt)} · {d.endedAt ? tx('{{n}} min', { n: d.minutes ?? 0 }) : tx('still stopped')}</div>
              </div>
              {canManage && (
                <div className="flex gap-2">
                  <Button variant="outline" className="h-12" onClick={() => setDlg({ kind: 'reject', what: 'stop', id: d.id })}>{tx('Reject')}</Button>
                  <Button className="h-12" onClick={() => setDlg({ kind: 'confirm-stop', draft: d })}><CheckCircle2 className="mr-1.5 h-4 w-4" />{tx('Confirm')}</Button>
                </div>
              )}
            </div>
          ))}
        </section>
      )}

      {dlg?.kind === 'stop' && <StopDialog item={dlg.item} onClose={() => setDlg(null)} />}
      {dlg?.kind === 'output' && <OutputDialog item={dlg.item} onClose={() => setDlg(null)} />}
      {dlg?.kind === 'finish' && <FinishDialog item={dlg.item} onClose={() => setDlg(null)} />}
      {dlg?.kind === 'issue' && <IssueDialog item={dlg.item} onClose={() => setDlg(null)} />}
      {dlg?.kind === 'downtime' && <DowntimeDialog queue={q} onClose={() => setDlg(null)} />}
      {dlg?.kind === 'meter' && <MeterDialog queue={q} onClose={() => setDlg(null)} />}
      {dlg?.kind === 'confirm-output' && <ConfirmOutputDialog draft={dlg.draft} queue={q} onClose={() => setDlg(null)} />}
      {dlg?.kind === 'confirm-stop' && <ConfirmStopDialog draft={dlg.draft} onClose={() => setDlg(null)} />}
      {dlg?.kind === 'reject' && <RejectDraftDialog what={dlg.what} id={dlg.id} onClose={() => setDlg(null)} />}
    </div>
  );
}

function WorkOrderCard({ item: w, canManage, canReport, canIssue, starting, onStart, onDialog }: {
  item: QueueItem; canManage: boolean; canReport: boolean; canIssue: boolean; starting: boolean;
  onStart: () => void; onDialog: (d: Dlg) => void;
}) {
  const running = w.status === 'in_progress';
  const held = w.multiStage && w.qcGate && w.qcStatus && !['passed', 'not_required'].includes(w.qcStatus);
  return (
    <div className={cn('space-y-3 rounded-xl border border-l-8 bg-card p-4 shadow-sm',
      running ? 'border-l-emerald-500' : w.status === 'paused' ? 'border-l-amber-500' : 'border-l-muted-foreground/30')}>
      <div className="flex flex-wrap items-start justify-between gap-2">
        <div>
          <div className="text-lg font-semibold">{w.orderCode} · {w.name}</div>
          <div className="text-sm text-muted-foreground">
            {w.productName ?? ''}{w.multiStage ? ` · ${tx('stage {{n}}', { n: w.stageSequence ?? '' })}` : ''}
            {' · '}{tx('planned {{qty}}', { qty: qty(w.multiStage ? w.plannedOutputQty : w.plannedQty) })}
          </div>
          <div className="text-sm">
            {tx('Good')}: <b className="tabular-nums">{qty(w.multiStage ? w.stageQtyGood : w.qtyGood)}</b>
            {w.startedAt && <span className="text-muted-foreground"> · {tx('started {{time}}', { time: relativeTime(w.startedAt) })}</span>}
          </div>
          {held && <div className="text-sm text-amber-700 dark:text-amber-400">{tx('QC checkpoint: {{status}}', { status: w.qcStatus })}</div>}
        </div>
        <span className="rounded-full bg-muted px-3 py-1 text-sm">{w.status === 'in_progress' ? tx('Running') : w.status === 'paused' ? tx('Stopped') : tx('Pending')}</span>
      </div>
      <div className="grid grid-cols-2 gap-2 sm:grid-cols-4">
        {canManage && !running && (
          <Button className="h-14 bg-emerald-600 text-base text-white hover:bg-emerald-700" disabled={starting} onClick={onStart}>
            <Play className="mr-2 h-5 w-5" />{w.status === 'paused' ? tx('Resume') : tx('Start')}
          </Button>
        )}
        {canManage && running && (
          <Button className="h-14 bg-amber-500 text-base text-white hover:bg-amber-600" onClick={() => onDialog({ kind: 'stop', item: w })}>
            <Pause className="mr-2 h-5 w-5" />{tx('Stop')}
          </Button>
        )}
        {canReport && (
          <Button variant="secondary" className="h-14 text-base" onClick={() => onDialog({ kind: 'output', item: w })}>
            <Plus className="mr-2 h-5 w-5" />{tx('Report output')}
          </Button>
        )}
        {canIssue && w.multiStage && w.issueMode === 'manual' && (
          <Button variant="secondary" className="h-14 text-base" onClick={() => onDialog({ kind: 'issue', item: w })}>
            <PackageMinus className="mr-2 h-5 w-5" />{tx('Issue material')}
          </Button>
        )}
        {canManage && w.status !== 'pending' && (
          <Button variant="outline" className="h-14 text-base" onClick={() => onDialog({ kind: 'finish', item: w })}>
            <Square className="mr-2 h-5 w-5" />{tx('Finish')}
          </Button>
        )}
      </div>
    </div>
  );
}

function useClose(onClose: () => void) {
  return async (p: Promise<unknown>, ok: string, fail: string) => {
    try {
      await p;
      notify.success(ok);
      onClose();
    } catch (err) {
      notify.error(apiMessage(err, fail));
    }
  };
}

function StopDialog({ item, onClose }: { item: QueueItem; onClose: () => void }) {
  const reasons = useReasons();
  const pause = usePauseWorkOrder();
  const [reason, setReason] = useState('');
  const [notes, setNotes] = useState('');
  const done = useClose(onClose);
  return (
    <Dialog open onOpenChange={(o) => !o && onClose()}>
      <DialogContent className="max-h-[92vh] overflow-y-auto sm:max-w-2xl">
        <DialogHeader>
          <DialogTitle>{tx('Why is {{name}} stopping?', { name: item.name })}</DialogTitle>
          <DialogDescription>{tx('The stop is recorded as downtime until you resume.')}</DialogDescription>
        </DialogHeader>
        <ReasonGrid reasons={reasons.data ?? []} value={reason} onChange={setReason} />
        <Textarea rows={2} placeholder={tx('Notes (optional)')} value={notes} onChange={(e) => setNotes(e.target.value)} />
        <DialogFooter>
          <Button variant="outline" className="h-12" onClick={onClose}>{tx('Cancel')}</Button>
          <Button className="h-12" disabled={!reason || pause.isPending}
            onClick={() => done(pause.mutateAsync({ id: item.id, body: { reasonCode: reason, notes: notes.trim() || undefined } }), tx('Stopped'), tx('Could not stop the work order'))}>
            <Pause className="mr-1.5 h-4 w-4" />{tx('Stop')}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

function ScrapReasonSelect({ value, onChange }: { value: string; onChange: (v: string) => void }) {
  const { list } = useScrapReasons();
  const options = (list.data ?? []).filter((r: any) => r.isActive !== false);
  if (!options.length) return null;
  return (
    <label className="block space-y-1">
      <span className="text-sm font-medium">{tx('Scrap reason')}</span>
      <Select value={value || '__none__'} onValueChange={(v) => onChange(v === '__none__' ? '' : v)}>
        <SelectTrigger className="h-12"><SelectValue /></SelectTrigger>
        <SelectContent>
          <SelectItem value="__none__">{tx('None')}</SelectItem>
          {options.map((r: any) => <SelectItem key={r.id} value={r.code}>{r.name}</SelectItem>)}
        </SelectContent>
      </Select>
    </label>
  );
}

function OutputDialog({ item, onClose }: { item: QueueItem; onClose: () => void }) {
  const report = useReportProduction();
  const [good, setGood] = useState('');
  const [scrap, setScrap] = useState('');
  const [reason, setReason] = useState('');
  const [completes, setCompletes] = useState(false);
  const [notes, setNotes] = useState('');
  const done = useClose(onClose);
  const valid = isNum(good || '0') && isNum(scrap || '0') && (Number(good || 0) + Number(scrap || 0) > 0 || completes);
  return (
    <Dialog open onOpenChange={(o) => !o && onClose()}>
      <DialogContent className="max-h-[92vh] overflow-y-auto sm:max-w-lg">
        <DialogHeader>
          <DialogTitle>{tx('Report output · {{name}}', { name: item.name })}</DialogTitle>
          <DialogDescription>
            {item.multiStage ? tx('Posts the stage output: stock, WIP and cost move now.') : tx('Adds to the work order’s counts; stock moves when the order completes.')}
          </DialogDescription>
        </DialogHeader>
        <div className="grid grid-cols-2 gap-3">
          <BigNumber label={tx('Good')} value={good} onChange={setGood} autoFocus />
          <BigNumber label={tx('Scrap')} value={scrap} onChange={setScrap} />
        </div>
        {Number(scrap || 0) > 0 && <ScrapReasonSelect value={reason} onChange={setReason} />}
        {item.multiStage && (
          <label className="flex min-h-[48px] items-center gap-3 rounded-lg border p-3">
            <input type="checkbox" className="h-6 w-6" checked={completes} onChange={(e) => setCompletes(e.target.checked)} />
            <span>{tx('This completes the stage')}</span>
          </label>
        )}
        <Textarea rows={2} placeholder={tx('Notes (optional)')} value={notes} onChange={(e) => setNotes(e.target.value)} />
        <DialogFooter>
          <Button variant="outline" className="h-12" onClick={onClose}>{tx('Cancel')}</Button>
          <Button className="h-12" disabled={!valid || report.isPending}
            onClick={() => done(report.mutateAsync({
              workOrderId: item.id, qtyGood: Number(good || 0), qtyScrap: Number(scrap || 0),
              scrapReasonCode: reason || undefined, completesStage: completes || undefined, notes: notes.trim() || undefined,
            }), tx('Output recorded'), tx('Could not record the output'))}>
            {tx('Record')}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

function FinishDialog({ item, onClose }: { item: QueueItem; onClose: () => void }) {
  const complete = useCompleteWorkOrder();
  const [notes, setNotes] = useState('');
  const done = useClose(onClose);
  return (
    <Dialog open onOpenChange={(o) => !o && onClose()}>
      <DialogContent className="sm:max-w-lg">
        <DialogHeader>
          <DialogTitle>{tx('Finish {{name}}?', { name: item.name })}</DialogTitle>
          <DialogDescription>{tx('Closes the operation and costs its logged time. Report the last output first.')}</DialogDescription>
        </DialogHeader>
        <Textarea rows={2} placeholder={tx('Notes (optional)')} value={notes} onChange={(e) => setNotes(e.target.value)} />
        <DialogFooter>
          <Button variant="outline" className="h-12" onClick={onClose}>{tx('Cancel')}</Button>
          <Button className="h-12" disabled={complete.isPending}
            onClick={() => done(complete.mutateAsync({ id: item.id, body: { notes: notes.trim() || undefined } }), tx('Work order finished'), tx('Could not finish the work order'))}>
            <Square className="mr-1.5 h-4 w-4" />{tx('Finish')}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

function IssueDialog({ item, onClose }: { item: QueueItem; onClose: () => void }) {
  const stages = useOrderStages(item.productionOrderId);
  const issue = useStageIssueFromFloor();
  const stage = stages.data?.stages.find((s) => s.id === item.stageId);
  const [qtys, setQtys] = useState<Record<string, string>>({});
  const done = useClose(onClose);
  const lines = Object.entries(qtys).filter(([, v]) => isNum(v) && Number(v) > 0)
    .map(([materialId, v]) => ({ materialId, quantity: Number(v) }));
  return (
    <Dialog open onOpenChange={(o) => !o && onClose()}>
      <DialogContent className="max-h-[92vh] overflow-y-auto sm:max-w-lg">
        <DialogHeader>
          <DialogTitle>{tx('Issue material · {{name}}', { name: item.name })}</DialogTitle>
          <DialogDescription>{tx('Confirm what was actually put into this stage; stock moves into WIP.')}</DialogDescription>
        </DialogHeader>
        {stages.isLoading && <p className="text-muted-foreground">{tx('Loading…')}</p>}
        {stage && stage.materials.length === 0 && <p className="text-muted-foreground">{tx('This stage has no materials of its own.')}</p>}
        <div className="space-y-3">
          {stage?.materials.map((m) => (
            <div key={m.id} className="space-y-1">
              <BigNumber label={m.productName} value={qtys[m.id] ?? ''} onChange={(v) => setQtys((p) => ({ ...p, [m.id]: v }))} />
              <p className="text-xs text-muted-foreground">{tx('Planned {{p}} · issued {{c}}', { p: qty(m.qtyPlanned), c: qty(m.qtyConsumed) })}</p>
            </div>
          ))}
        </div>
        <DialogFooter>
          <Button variant="outline" className="h-12" onClick={onClose}>{tx('Cancel')}</Button>
          <Button className="h-12" disabled={!item.stageId || !lines.length || issue.isPending}
            onClick={() => done(issue.mutateAsync({ stageId: item.stageId as string, body: { lines } }), tx('Material issued'), tx('Could not issue the material'))}>
            <PackageMinus className="mr-1.5 h-4 w-4" />{tx('Issue')}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

function DowntimeDialog({ queue, onClose }: { queue: Queue; onClose: () => void }) {
  const reasons = useReasons();
  const log = useLogDowntime();
  const [reason, setReason] = useState('');
  const [minutes, setMinutes] = useState('');
  const [workOrderId, setWorkOrderId] = useState('');
  const [notes, setNotes] = useState('');
  const done = useClose(onClose);
  const closed = minutes.trim() !== '';
  return (
    <Dialog open onOpenChange={(o) => !o && onClose()}>
      <DialogContent className="max-h-[92vh] overflow-y-auto sm:max-w-2xl">
        <DialogHeader>
          <DialogTitle>{tx('Log downtime')}</DialogTitle>
          <DialogDescription>{tx('Leave minutes empty for a stop that is still going on; end it from the banner.')}</DialogDescription>
        </DialogHeader>
        <ReasonGrid reasons={reasons.data ?? []} value={reason} onChange={setReason} />
        <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
          <BigNumber label={tx('Minutes (ended stop)')} value={minutes} onChange={setMinutes} />
          <label className="block space-y-1">
            <span className="text-sm font-medium">{tx('Work order')}</span>
            <Select value={workOrderId || '__none__'} onValueChange={(v) => setWorkOrderId(v === '__none__' ? '' : v)}>
              <SelectTrigger className="h-14"><SelectValue /></SelectTrigger>
              <SelectContent>
                <SelectItem value="__none__">{tx('Whole work centre')}</SelectItem>
                {queue.workOrders.map((w) => <SelectItem key={w.id} value={w.id}>{w.orderCode} · {w.name}</SelectItem>)}
              </SelectContent>
            </Select>
          </label>
        </div>
        <Textarea rows={2} placeholder={tx('Notes (optional)')} value={notes} onChange={(e) => setNotes(e.target.value)} />
        <DialogFooter>
          <Button variant="outline" className="h-12" onClick={onClose}>{tx('Cancel')}</Button>
          <Button className="h-12" disabled={!reason || (closed && !(isNum(minutes) && Number(minutes) >= 1)) || log.isPending}
            onClick={() => {
              const m = closed ? Math.round(Number(minutes)) : undefined;
              done(log.mutateAsync({
                workCenterId: queue.workCenter.id, reasonCode: reason, workOrderId: workOrderId || undefined,
                startedAt: m ? new Date(Date.now() - m * 60_000).toISOString() : undefined, minutes: m, notes: notes.trim() || undefined,
              }), tx('Downtime logged'), tx('Could not log the downtime'));
            }}>
            {closed ? tx('Log stop') : tx('Start stop')}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

function MeterDialog({ queue, onClose }: { queue: Queue; onClose: () => void }) {
  const read = useReadMeter();
  const meters = useMemo(() => queue.profile?.meterCodes ?? [], [queue.profile]);
  const [meter, setMeter] = useState(meters[0] ?? '');
  const [value, setValue] = useState('');
  const [reset, setReset] = useState(false);
  const done = useClose(onClose);
  useEffect(() => { if (!meter && meters[0]) setMeter(meters[0]); }, [meter, meters]);
  return (
    <Dialog open onOpenChange={(o) => !o && onClose()}>
      <DialogContent className="sm:max-w-lg">
        <DialogHeader>
          <DialogTitle>{tx('Meter reading')}</DialogTitle>
          <DialogDescription>{tx('Counters only go up; tick reset when the meter was replaced or rolled over.')}</DialogDescription>
        </DialogHeader>
        {meters.length ? (
          <div className="flex flex-wrap gap-2">
            {meters.map((m) => (
              <Button key={m} variant={m === meter ? 'default' : 'outline'} className="h-12 min-w-[96px]" onClick={() => setMeter(m)}>{m}</Button>
            ))}
          </div>
        ) : (
          <label className="block space-y-1">
            <span className="text-sm font-medium">{tx('Meter')}</span>
            <Input className="h-12" value={meter} onChange={(e) => setMeter(e.target.value)} placeholder={tx('e.g. hours, kwh, tonnes')} />
          </label>
        )}
        <BigNumber label={tx('Reading')} value={value} onChange={setValue} autoFocus />
        <label className="flex min-h-[48px] items-center gap-3 rounded-lg border p-3">
          <input type="checkbox" className="h-6 w-6" checked={reset} onChange={(e) => setReset(e.target.checked)} />
          <span>{tx('Meter reset / replaced')}</span>
        </label>
        <DialogFooter>
          <Button variant="outline" className="h-12" onClick={onClose}>{tx('Cancel')}</Button>
          <Button className="h-12" disabled={!meter.trim() || !isNum(value) || read.isPending}
            onClick={() => done(read.mutateAsync({ workCenterId: queue.workCenter.id, meterCode: meter.trim(), reading: Number(value), isReset: reset || undefined }),
              tx('Reading saved'), tx('Could not save the reading'))}>
            {tx('Save')}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

function ConfirmOutputDialog({ draft, queue, onClose }: { draft: ProductionReport; queue: Queue; onClose: () => void }) {
  const confirm = useConfirmProduction();
  const [good, setGood] = useState(String(Number(draft.qtyGood)));
  const [scrap, setScrap] = useState(String(Number(draft.qtyScrap)));
  const [reason, setReason] = useState('');
  const [workOrderId, setWorkOrderId] = useState(draft.workOrderId ?? '');
  const done = useClose(onClose);
  return (
    <Dialog open onOpenChange={(o) => !o && onClose()}>
      <DialogContent className="max-h-[92vh] overflow-y-auto sm:max-w-lg">
        <DialogHeader>
          <DialogTitle>{tx('Confirm machine count')}</DialogTitle>
          <DialogDescription>{tx('Correct the counts if the machine was wrong; confirming posts them like an operator report.')}</DialogDescription>
        </DialogHeader>
        <label className="block space-y-1">
          <span className="text-sm font-medium">{tx('Work order')}</span>
          <Select value={workOrderId || '__none__'} onValueChange={(v) => setWorkOrderId(v === '__none__' ? '' : v)}>
            <SelectTrigger className="h-14"><SelectValue /></SelectTrigger>
            <SelectContent>
              <SelectItem value="__none__">{tx('Choose a work order')}</SelectItem>
              {queue.workOrders.map((w) => <SelectItem key={w.id} value={w.id}>{w.orderCode} · {w.name}</SelectItem>)}
            </SelectContent>
          </Select>
        </label>
        <div className="grid grid-cols-2 gap-3">
          <BigNumber label={tx('Good')} value={good} onChange={setGood} />
          <BigNumber label={tx('Scrap')} value={scrap} onChange={setScrap} />
        </div>
        {Number(scrap || 0) > 0 && <ScrapReasonSelect value={reason} onChange={setReason} />}
        <DialogFooter>
          <Button variant="outline" className="h-12" onClick={onClose}>{tx('Cancel')}</Button>
          <Button className="h-12" disabled={!workOrderId || !isNum(good) || !isNum(scrap) || confirm.isPending}
            onClick={() => done(confirm.mutateAsync({ id: draft.id, body: {
              workOrderId, qtyGood: Number(good), qtyScrap: Number(scrap), scrapReasonCode: reason || undefined,
            } }), tx('Count confirmed'), tx('Could not confirm the count'))}>
            <CheckCircle2 className="mr-1.5 h-4 w-4" />{tx('Confirm')}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

function ConfirmStopDialog({ draft, onClose }: { draft: Downtime; onClose: () => void }) {
  const reasons = useReasons();
  const confirm = useConfirmDowntime();
  const [reason, setReason] = useState(draft.reasonCode ?? '');
  const done = useClose(onClose);
  return (
    <Dialog open onOpenChange={(o) => !o && onClose()}>
      <DialogContent className="max-h-[92vh] overflow-y-auto sm:max-w-2xl">
        <DialogHeader>
          <DialogTitle>{tx('Confirm machine stop')}</DialogTitle>
          <DialogDescription>{dateTime(draft.startedAt)} · {draft.endedAt ? tx('{{n}} min', { n: draft.minutes ?? 0 }) : tx('still stopped')}</DialogDescription>
        </DialogHeader>
        <ReasonGrid reasons={reasons.data ?? []} value={reason} onChange={setReason} />
        <DialogFooter>
          <Button variant="outline" className="h-12" onClick={onClose}>{tx('Cancel')}</Button>
          <Button className="h-12" disabled={!reason || confirm.isPending}
            onClick={() => done(confirm.mutateAsync({ id: draft.id, body: { reasonCode: reason } }), tx('Stop confirmed'), tx('Could not confirm the stop'))}>
            <CheckCircle2 className="mr-1.5 h-4 w-4" />{tx('Confirm')}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

function RejectDraftDialog({ what, id, onClose }: { what: 'output' | 'stop'; id: string; onClose: () => void }) {
  const rejectOutput = useRejectProduction();
  const rejectStop = useRejectDowntime();
  const [reason, setReason] = useState('');
  const done = useClose(onClose);
  const pending = rejectOutput.isPending || rejectStop.isPending;
  return (
    <Dialog open onOpenChange={(o) => !o && onClose()}>
      <DialogContent className="sm:max-w-lg">
        <DialogHeader>
          <DialogTitle>{what === 'output' ? tx('Reject machine count') : tx('Reject machine stop')}</DialogTitle>
          <DialogDescription>{tx('Nothing is posted; the reason is kept for the record.')}</DialogDescription>
        </DialogHeader>
        <Textarea rows={3} value={reason} onChange={(e) => setReason(e.target.value)} placeholder={tx('Reason *')} />
        <DialogFooter>
          <Button variant="outline" className="h-12" onClick={onClose}>{tx('Cancel')}</Button>
          <Button variant="destructive" className="h-12" disabled={!reason.trim() || pending}
            onClick={() => done(what === 'output' ? rejectOutput.mutateAsync({ id, reason: reason.trim() })
              : rejectStop.mutateAsync({ id, reason: reason.trim() }), tx('Draft rejected'), tx('Could not reject the draft'))}>
            {tx('Reject')}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

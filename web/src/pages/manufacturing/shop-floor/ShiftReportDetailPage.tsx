import { useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { ArrowLeft, CheckCircle2, Send, Undo2 } from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { Button } from '@/components/ui/button';
import { Textarea } from '@/components/ui/textarea';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { useAuthStore } from '@/stores/auth.store';
import { notify, apiMessage } from '@/lib/notify';
import { date, dateTime } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';
import {
  minutesLabel, qty, useApproveShiftReport, useRejectShiftReport, useShiftReport, useSubmitShiftReport,
} from '@/features/manufacturing/api-shopfloor';
import { DowntimeStatusPill, DraftStatusPill, OeeTiles, ShiftReportStatusPill } from './shopfloor-ui';

type Action = 'submit' | 'approve' | 'reject';

/** One shift report (ADR-S015): OEE, output, downtime, crew, meters; submit → approve / reject. */
export function ShiftReportDetailPage() {
  const { id } = useParams();
  const has = useAuthStore((s) => s.hasPermission);
  const me = useAuthStore((s) => s.user);
  const { data: r, isLoading, isError } = useShiftReport(id);
  const [action, setAction] = useState<Action | null>(null);
  if (isLoading) return <p className="p-6 text-muted-foreground">{tx('Loading…')}</p>;
  if (isError || !r) return <p className="p-6 text-destructive">{tx('Could not load the shift report.')}</p>;
  const canSubmit = has(PERMISSIONS.workOrder.manage) && r.status === 'open';
  const canDecide = has(PERMISSIONS.shopFloor.approve) && r.status === 'submitted';
  const ownSubmission = r.submittedByUser?.id === me?.id;
  return (
    <div className="space-y-4">
      <Button variant="ghost" size="sm" className="-ml-2" asChild>
        <Link to="/manufacturing/shift-reports"><ArrowLeft className="mr-1 h-4 w-4" />{tx('Shift reports')}</Link>
      </Button>
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <div className="flex flex-wrap items-center gap-2">
            <h1 className="text-2xl font-bold">{r.code}</h1>
            <ShiftReportStatusPill status={r.status} />
          </div>
          <p className="text-sm text-muted-foreground">
            {r.workCenterName} · {r.shiftName} · {date(r.shiftDate)} · {dateTime(r.startsAt)} → {dateTime(r.endsAt)}
          </p>
        </div>
        <div className="flex flex-wrap gap-2">
          {canSubmit && <Button onClick={() => setAction('submit')} disabled={r.pendingDrafts > 0}><Send className="mr-1.5 h-4 w-4" />{tx('Submit')}</Button>}
          {canDecide && <Button variant="outline" onClick={() => setAction('reject')}><Undo2 className="mr-1.5 h-4 w-4" />{tx('Reject')}</Button>}
          {canDecide && <Button onClick={() => setAction('approve')} disabled={ownSubmission}><CheckCircle2 className="mr-1.5 h-4 w-4" />{tx('Approve')}</Button>}
        </div>
      </div>
      {r.pendingDrafts > 0 && r.status === 'open' && (
        <p className="rounded-md bg-amber-50 px-3 py-2 text-sm dark:bg-amber-950/40">
          {tx('{{n}} machine drafts wait for confirmation on the terminal; the report can be submitted once they are confirmed or rejected.', { n: r.pendingDrafts })}
        </p>
      )}
      {canDecide && ownSubmission && <p className="text-sm text-muted-foreground">{tx('You submitted this report; another supervisor must approve it.')}</p>}
      {r.rejectionReason && r.status === 'open' && (
        <p className="rounded-md bg-red-50 px-3 py-2 text-sm dark:bg-red-950/40">{tx('Returned by the supervisor')}: {r.rejectionReason}</p>
      )}

      <Card>
        <CardHeader><CardTitle>{r.status === 'open' ? tx('OEE so far') : tx('OEE (as submitted)')}</CardTitle></CardHeader>
        <CardContent className="space-y-2">
          <OeeTiles oee={r.oee} />
          <p className="text-xs text-muted-foreground">
            {tx('Downtime {{down}} · run {{run}} · planned {{planned}}', { down: minutesLabel(r.oee.downtimeMinutes), run: minutesLabel(r.oee.runMinutes), planned: minutesLabel(r.oee.plannedMinutes) })}
          </p>
          {(r.notes || r.approvalNotes) && <p className="text-sm">{r.notes}{r.approvalNotes ? ` · ${tx('Supervisor')}: ${r.approvalNotes}` : ''}</p>}
          {r.approvedByUser && <p className="text-xs text-muted-foreground">{tx('Approved by {{name}} on {{time}}', { name: r.approvedByUser.name, time: dateTime(r.approvedAt) })}</p>}
        </CardContent>
      </Card>

      <Card>
        <CardHeader><CardTitle>{tx('Output & scrap')}</CardTitle></CardHeader>
        <CardContent className="overflow-x-auto">
          {r.productionReports.length === 0 ? <p className="text-sm text-muted-foreground">{tx('No output reported in this shift.')}</p> : (
            <Table>
              <TableHeader><TableRow>
                <TableHead>{tx('Time')}</TableHead><TableHead>{tx('Order')}</TableHead><TableHead>{tx('Operation')}</TableHead>
                <TableHead className="text-right">{tx('Good')}</TableHead><TableHead className="text-right">{tx('Scrap')}</TableHead>
                <TableHead>{tx('Source')}</TableHead><TableHead>{tx('Status')}</TableHead>
              </TableRow></TableHeader>
              <TableBody>
                {r.productionReports.map((p) => (
                  <TableRow key={p.id}>
                    <TableCell className="whitespace-nowrap">{dateTime(p.reportedAt)}</TableCell>
                    <TableCell>{p.productionOrderId ? <Link className="text-primary hover:underline" to={`/manufacturing/orders/${p.productionOrderId}`}>{p.orderCode}</Link> : '—'}</TableCell>
                    <TableCell>{p.workOrderName ?? '—'}{p.stageSequence ? ` · ${tx('stage {{n}}', { n: p.stageSequence })}` : ''}{p.completesStage ? ` · ${tx('completes')}` : ''}</TableCell>
                    <TableCell className="text-right tabular-nums">{qty(p.qtyGood)}</TableCell>
                    <TableCell className="text-right tabular-nums">{qty(p.qtyScrap)}{p.scrapReasonCode ? ` (${p.scrapReasonCode})` : ''}</TableCell>
                    <TableCell>{p.source === 'machine' ? tx('Machine') : tx('Operator')}</TableCell>
                    <TableCell><DraftStatusPill status={p.status} /></TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          )}
        </CardContent>
      </Card>

      <Card>
        <CardHeader><CardTitle>{tx('Downtime')}</CardTitle></CardHeader>
        <CardContent className="overflow-x-auto">
          {r.downtime.length === 0 ? <p className="text-sm text-muted-foreground">{tx('No downtime in this shift.')}</p> : (
            <Table>
              <TableHeader><TableRow>
                <TableHead>{tx('Start')}</TableHead><TableHead>{tx('End')}</TableHead><TableHead>{tx('Reason')}</TableHead>
                <TableHead className="text-right">{tx('Minutes')}</TableHead><TableHead>{tx('Source')}</TableHead><TableHead>{tx('Status')}</TableHead>
              </TableRow></TableHeader>
              <TableBody>
                {r.downtime.map((d) => (
                  <TableRow key={d.id}>
                    <TableCell className="whitespace-nowrap">{dateTime(d.startedAt)}</TableCell>
                    <TableCell className="whitespace-nowrap">{d.endedAt ? dateTime(d.endedAt) : tx('ongoing')}</TableCell>
                    <TableCell>{d.reasonName ?? d.reasonCode ?? '—'}{d.isPlanned ? ` · ${tx('planned')}` : ''}{d.workOrderName ? ` · ${d.workOrderName}` : ''}</TableCell>
                    <TableCell className="text-right tabular-nums">{d.minutes ?? '—'}</TableCell>
                    <TableCell>{d.source === 'machine' ? tx('Machine') : tx('Operator')}</TableCell>
                    <TableCell><DowntimeStatusPill status={d.status} /></TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          )}
        </CardContent>
      </Card>

      <div className="grid gap-4 lg:grid-cols-2">
        <Card>
          <CardHeader><CardTitle>{tx('Crew')}</CardTitle></CardHeader>
          <CardContent className="space-y-1 text-sm">
            {r.clockEntries.length === 0 ? <p className="text-muted-foreground">{tx('Nobody clocked on in this shift.')}</p> : r.clockEntries.map((c) => (
              <div key={c.id} className="flex justify-between gap-2 border-b py-1 last:border-0">
                <span>{c.userName ?? '—'}{c.role ? ` · ${c.role}` : ''}{c.crewCode ? ` · ${tx('crew')} ${c.crewCode}` : ''}{c.workOrderName ? ` · ${c.workOrderName}` : ''}</span>
                <span className="whitespace-nowrap text-muted-foreground">{dateTime(c.clockOnAt)} → {c.clockOffAt ? dateTime(c.clockOffAt) : tx('on shift')}</span>
              </div>
            ))}
          </CardContent>
        </Card>
        <Card>
          <CardHeader><CardTitle>{tx('Meter readings')}</CardTitle></CardHeader>
          <CardContent className="space-y-1 text-sm">
            {r.meterReadings.length === 0 ? <p className="text-muted-foreground">{tx('No meter readings in this shift.')}</p> : r.meterReadings.map((m) => (
              <div key={m.id} className="flex justify-between gap-2 border-b py-1 last:border-0">
                <span>{m.meterCode}: <b className="tabular-nums">{qty(m.reading)}</b>{m.isReset ? ` · ${tx('reset')}` : m.delta !== null ? ` (+${qty(m.delta)})` : ''}</span>
                <span className="text-muted-foreground">{dateTime(m.readAt)}</span>
              </div>
            ))}
          </CardContent>
        </Card>
      </div>
      {action && <DecisionDialog id={r.id} action={action} onClose={() => setAction(null)} />}
    </div>
  );
}

function DecisionDialog({ id, action, onClose }: { id: string; action: Action; onClose: () => void }) {
  const submit = useSubmitShiftReport();
  const approve = useApproveShiftReport();
  const reject = useRejectShiftReport();
  const [text, setText] = useState('');
  const pending = submit.isPending || approve.isPending || reject.isPending;
  const title = action === 'submit' ? tx('Submit the shift report') : action === 'approve' ? tx('Approve the shift report') : tx('Return the shift report');
  const go = async () => {
    try {
      if (action === 'submit') await submit.mutateAsync({ id, notes: text.trim() || undefined });
      else if (action === 'approve') await approve.mutateAsync({ id, notes: text.trim() || undefined });
      else await reject.mutateAsync({ id, reason: text.trim() });
      notify.success(action === 'submit' ? tx('Shift report submitted') : action === 'approve' ? tx('Shift report approved') : tx('Shift report returned'));
      onClose();
    } catch (err) {
      notify.error(apiMessage(err, tx('Could not update the shift report')));
    }
  };
  return (
    <Dialog open onOpenChange={(o) => !o && onClose()}>
      <DialogContent className="sm:max-w-lg">
        <DialogHeader>
          <DialogTitle>{title}</DialogTitle>
          <DialogDescription>
            {action === 'submit' ? tx('The OEE is frozen and the shift’s records are locked until a supervisor decides.')
              : action === 'approve' ? tx('Approval locks the shift for good.') : tx('The shift lead can correct the records and submit again.')}
          </DialogDescription>
        </DialogHeader>
        <Textarea rows={3} value={text} onChange={(e) => setText(e.target.value)}
          placeholder={action === 'reject' ? tx('What needs to be corrected? *') : tx('Notes (optional)')} />
        <DialogFooter>
          <Button variant="outline" onClick={onClose}>{tx('Cancel')}</Button>
          <Button onClick={go} disabled={pending || (action === 'reject' && !text.trim())}>{title}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

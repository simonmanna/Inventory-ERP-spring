import { useEffect, useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { ArrowLeft, FileWarning, Save } from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Textarea } from '@/components/ui/textarea';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Skeleton } from '@/components/ui/skeleton';
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { AttachmentPanel } from '@/components/dms/AttachmentPanel';
import { useAuthStore } from '@/stores/auth.store';
import { notify, apiMessage } from '@/lib/notify';
import { dateTime } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';
import { cn } from '@/lib/utils';
import {
  SOURCE_LABEL, useAlert, useAlertStages, useEscalateAlert, useMoveAlert, useRootCauses, useTags, useUpdateAlert, type QcAlert,
} from '@/features/quality-control/api';
import { FailureClassPicker, Field, Item, OptionalSelect, StaffPicker, TeamPicker } from './qc-ui';
import { PriorityStars, TagChips } from './alert-ui';

const SOURCE_PATH: Record<string, string> = {
  production_order: '/manufacturing/orders/',
  goods_receipt: '/procurement/goods-receipts/',
};

/** Quality alert (ADR-S087 §8): stage bar, investigation fields, NCR escalation, stage and assignment history. */
export function AlertDetailPage() {
  const { id = '' } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const { data: a, isLoading, isError } = useAlert(id);
  const has = useAuthStore((s) => s.hasPermission);
  const canWrite = has(PERMISSIONS.qualityControl.alert);
  const stages = useAlertStages(true);
  const move = useMoveAlert();
  const [moveTo, setMoveTo] = useState<string | null>(null);
  const [escalating, setEscalating] = useState(false);

  if (isLoading) return <div className="space-y-3"><Skeleton className="h-12 w-1/2" /><Skeleton className="h-64 w-full" /></div>;
  if (isError || !a) {
    return (
      <div className="p-8 text-center text-muted-foreground">
        <p>{tx('Quality alert not found')}</p>
        <Button variant="link" onClick={() => navigate('/quality-control/alerts')}>{tx('Back to alerts')}</Button>
      </div>
    );
  }
  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div className="flex items-start gap-3">
          <Button variant="ghost" size="icon" onClick={() => navigate('/quality-control/alerts')} aria-label={tx('Back')}><ArrowLeft className="h-4 w-4" /></Button>
          <div>
            <h1 className="flex flex-wrap items-center gap-2 text-xl font-semibold">
              {a.alertNumber} <PriorityStars value={a.priority} />
              {a.origin === 'auto' && <span className="rounded-full bg-muted px-2 py-0.5 text-xs font-normal text-muted-foreground">{tx('From a failed check')}</span>}
            </h1>
            <p className="text-sm text-muted-foreground">{a.title}</p>
            <div className="mt-1"><TagChips tags={a.tags} /></div>
          </div>
        </div>
        {canWrite && (
          <div className="flex flex-wrap gap-2">
            {a.ncrId
              ? <Button variant="outline" asChild><Link to={`/quality/ncrs/${a.ncrId}`}><FileWarning className="mr-1.5 h-4 w-4" /> {a.ncrNumber ?? tx('NCR')}</Link></Button>
              : <Button variant="outline" onClick={() => setEscalating(true)}><FileWarning className="mr-1.5 h-4 w-4" /> {tx('Escalate to NCR')}</Button>}
          </div>
        )}
      </div>

      <div className="flex flex-wrap gap-1 rounded-lg border bg-muted/30 p-1">
        {(stages.data ?? []).map((s) => (
          <button key={s.id} disabled={!canWrite || s.id === a.stageId || move.isPending} onClick={() => setMoveTo(s.id)}
            className={cn('rounded-md px-3 py-1.5 text-sm', s.id === a.stageId ? 'bg-primary text-primary-foreground' : 'text-muted-foreground hover:bg-background disabled:hover:bg-transparent')}>
            {s.name}
          </button>
        ))}
      </div>

      <div className="grid grid-cols-1 gap-4 lg:grid-cols-3">
        <div className="space-y-4 lg:col-span-2">
          <Investigation alert={a} canWrite={canWrite} />
          <Card>
            <CardHeader className="pb-2"><CardTitle className="text-base">{tx('History')}</CardTitle></CardHeader>
            <CardContent>
              <ol className="space-y-2 text-sm">
                {history(a).map((h) => (
                  <li key={h.key} className="flex flex-wrap items-baseline gap-x-2 border-l-2 pl-3">
                    <span className="font-medium">{h.what}</span>
                    <span className="text-xs text-muted-foreground">{dateTime(h.at)}{h.by ? ` · ${h.by}` : ''}</span>
                    {h.comment && <span className="w-full text-muted-foreground">“{h.comment}”</span>}
                  </li>
                ))}
              </ol>
            </CardContent>
          </Card>
        </div>
        <div className="space-y-4">
          <Card>
            <CardHeader className="pb-2"><CardTitle className="text-base">{tx('Details')}</CardTitle></CardHeader>
            <CardContent>
              <dl className="grid grid-cols-2 gap-3">
                <Item label={tx('Stage')}>{a.stageName}{a.closedAt ? ` · ${dateTime(a.closedAt)}` : ''}</Item>
                <Item label={tx('Raised')}>{dateTime(a.createdAt)}</Item>
                <Item label={tx('Check')}>{a.checkId ? <Link className="text-primary hover:underline" to={`/quality-control/checks/${a.checkId}`}>{a.checkNumber}</Link> : '—'}</Item>
                <Item label={tx('Correction check')}>{a.correctionCheckId ? <Link className="text-primary hover:underline" to={`/quality-control/checks/${a.correctionCheckId}`}>{a.correctionCheckNumber}</Link> : '—'}</Item>
                <Item label={tx('Control point')}>{a.controlPointId ? <Link className="text-primary hover:underline" to={`/quality-control/control-points/${a.controlPointId}`}>{a.controlPointReference}</Link> : '—'}</Item>
                <Item label={tx('Product')}>{a.productName}</Item>
                <Item label={tx('Batch / lot')}>{a.batchNumber}</Item>
                <Item label={tx('Source')}>
                  {a.sourceType ? (SOURCE_PATH[a.sourceType] && a.sourceId
                    ? <Link className="text-primary hover:underline" to={SOURCE_PATH[a.sourceType] + a.sourceId}>{a.sourceRef ?? SOURCE_LABEL[a.sourceType]}</Link>
                    : `${SOURCE_LABEL[a.sourceType]}${a.sourceRef ? ` · ${a.sourceRef}` : ''}`) : '—'}
                </Item>
                <Item label={tx('Supplier')}>{a.supplierName}</Item>
                <Item label={tx('NCR')}>{a.ncrId ? `${a.ncrNumber ?? ''}${a.ncrStatus ? ` (${a.ncrStatus})` : ''}` : '—'}</Item>
              </dl>
            </CardContent>
          </Card>
          <AttachmentPanel resType="qc_alert" resId={a.id} title={tx('Evidence')} writePermission={PERMISSIONS.qualityControl.alert} />
        </div>
      </div>
      {moveTo && <MoveDialog alert={a} stageId={moveTo} stageName={stages.data?.find((s) => s.id === moveTo)?.name ?? ''} onClose={() => setMoveTo(null)} />}
      {escalating && <EscalateDialog alert={a} onClose={() => setEscalating(false)} />}
    </div>
  );
}

function history(a: QcAlert) {
  const rows = [
    ...(a.stageHistory ?? []).map((h) => ({
      key: `s${h.id}`, at: h.createdAt, by: h.userName, comment: h.comment,
      what: h.fromStageName ? `${h.fromStageName} → ${h.toStageName}` : tx('Raised in {{stage}}', { stage: h.toStageName }),
    })),
    ...(a.assignmentHistory ?? []).map((h) => ({
      key: `a${h.id}`, at: h.createdAt, by: h.userName, comment: null,
      what: tx('Assigned: {{from}} → {{to}}', {
        from: [h.fromTeamName, h.fromResponsibleName].filter(Boolean).join(' / ') || tx('nobody'),
        to: [h.toTeamName, h.toResponsibleName].filter(Boolean).join(' / ') || tx('nobody'),
      }),
    })),
  ];
  return rows.sort((x, y) => x.at.localeCompare(y.at));
}

function Investigation({ alert: a, canWrite }: { alert: QcAlert; canWrite: boolean }) {
  const [f, setF] = useState(() => form(a));
  useEffect(() => setF(form(a)), [a]);
  const tags = useTags();
  const causes = useRootCauses(true);
  const update = useUpdateAlert();
  const set = (k: keyof ReturnType<typeof form>) => (v: string | number | string[]) => setF((s) => ({ ...s, [k]: v }));
  const save = async () => {
    try {
      await update.mutateAsync({ id: a.id, body: {
        title: f.title.trim(), description: f.description, priority: f.priority, teamId: f.teamId, responsibleUserId: f.responsibleUserId,
        failureClassId: f.failureClassId, rootCauseId: f.rootCauseId, correctiveAction: f.correctiveAction,
        preventiveAction: f.preventiveAction, tagIds: f.tagIds,
      } });
      notify.success(tx('Alert saved'));
    } catch (err) {
      notify.error(apiMessage(err, tx('Could not save the alert')));
    }
  };
  return (
    <Card>
      <CardHeader className="flex flex-row items-center justify-between pb-2">
        <CardTitle className="text-base">{tx('Investigation')}</CardTitle>
        {canWrite && <Button size="sm" onClick={save} disabled={update.isPending}><Save className="mr-1.5 h-4 w-4" /> {tx('Save')}</Button>}
      </CardHeader>
      <CardContent>
        <fieldset disabled={!canWrite} className="grid grid-cols-1 gap-3 sm:grid-cols-2">
          <Field label={tx('Title')} className="sm:col-span-2"><Input value={f.title} onChange={(e) => set('title')(e.target.value)} /></Field>
          <Field label={tx('Description')} className="sm:col-span-2"><Textarea rows={3} value={f.description} onChange={(e) => set('description')(e.target.value)} /></Field>
          <Field label={tx('Priority')}><div className="pt-1"><PriorityStars value={f.priority} onChange={canWrite ? set('priority') : undefined} /></div></Field>
          <Field label={tx('Team')}><TeamPicker value={f.teamId} onChange={set('teamId')} /></Field>
          <Field label={tx('Responsible')}><StaffPicker value={f.responsibleUserId} onChange={set('responsibleUserId')} /></Field>
          <Field label={tx('Failure class')}><FailureClassPicker value={f.failureClassId} onChange={set('failureClassId')} /></Field>
          <Field label={tx('Root cause')}>
            <OptionalSelect value={f.rootCauseId} onChange={set('rootCauseId')} placeholder={tx('Not identified')}
              options={(causes.data ?? []).map((c) => ({ value: c.id, label: c.name }))} />
          </Field>
          <Field label={tx('Corrective action')} className="sm:col-span-2"><Textarea rows={2} value={f.correctiveAction} onChange={(e) => set('correctiveAction')(e.target.value)} /></Field>
          <Field label={tx('Preventive action')} className="sm:col-span-2"><Textarea rows={2} value={f.preventiveAction} onChange={(e) => set('preventiveAction')(e.target.value)} /></Field>
          {!!tags.data?.length && (
            <Field label={tx('Tags')} className="sm:col-span-2">
              <div className="flex flex-wrap gap-1.5">
                {tags.data.map((t) => {
                  const on = f.tagIds.includes(t.id);
                  return (
                    <button key={t.id} type="button" onClick={() => set('tagIds')(on ? f.tagIds.filter((x) => x !== t.id) : [...f.tagIds, t.id])}
                      className={cn('rounded-full border px-2 py-0.5 text-xs', on ? 'border-primary bg-primary/10 text-primary' : 'text-muted-foreground')}>{t.name}</button>
                  );
                })}
              </div>
            </Field>
          )}
        </fieldset>
      </CardContent>
    </Card>
  );
}

const form = (a: QcAlert) => ({
  title: a.title, description: a.description ?? '', priority: a.priority, teamId: a.teamId ?? '', responsibleUserId: a.responsibleUserId ?? '',
  failureClassId: a.failureClassId ?? '', rootCauseId: a.rootCauseId ?? '', correctiveAction: a.correctiveAction ?? '',
  preventiveAction: a.preventiveAction ?? '', tagIds: a.tags.map((t) => t.id),
});

function MoveDialog({ alert: a, stageId, stageName, onClose }: { alert: QcAlert; stageId: string; stageName: string; onClose: () => void }) {
  const [comment, setComment] = useState('');
  const move = useMoveAlert();
  const submit = async () => {
    try {
      await move.mutateAsync({ id: a.id, stageId, comment: comment.trim() });
      onClose();
    } catch (err) {
      notify.error(apiMessage(err, tx('Could not move the alert')));
    }
  };
  return (
    <Dialog open onOpenChange={(o) => !o && onClose()}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>{tx('Move to {{stage}}', { stage: stageName })}</DialogTitle>
          <DialogDescription>{tx('The move is kept in the alert history. It does not change the check or an NCR.')}</DialogDescription>
        </DialogHeader>
        <Field label={tx('Comment (optional)')}><Textarea rows={3} value={comment} onChange={(e) => setComment(e.target.value)} /></Field>
        <DialogFooter>
          <Button variant="outline" onClick={onClose}>{tx('Cancel')}</Button>
          <Button onClick={submit} disabled={move.isPending}>{tx('Move')}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

function EscalateDialog({ alert: a, onClose }: { alert: QcAlert; onClose: () => void }) {
  const [severity, setSeverity] = useState('minor');
  const [owner, setOwner] = useState(a.responsibleUserId ?? '');
  const escalate = useEscalateAlert();
  const submit = async () => {
    try {
      const out = await escalate.mutateAsync({ id: a.id, body: { severity, ownerUserId: owner || undefined } });
      notify.success(out.created ? tx('NCR {{n}} opened', { n: out.ncrNumber ?? '' }) : tx('This alert already has an NCR.'));
      onClose();
    } catch (err) {
      notify.error(apiMessage(err, tx('Could not escalate the alert')));
    }
  };
  return (
    <Dialog open onOpenChange={(o) => !o && onClose()}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>{tx('Escalate to a nonconformity')}</DialogTitle>
          <DialogDescription>
            {tx('Opens an NCR in Quality with this alert’s product, batch, supplier, root cause and proposed actions. Resolving the alert later does not close the NCR.')}
          </DialogDescription>
        </DialogHeader>
        <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
          <Field label={tx('Severity')}>
            <OptionalSelect value={severity} onChange={(v) => setSeverity(v || 'minor')} placeholder={tx('Minor')}
              options={[{ value: 'minor', label: tx('Minor') }, { value: 'major', label: tx('Major') }, { value: 'critical', label: tx('Critical') }]} />
          </Field>
          <Field label={tx('NCR owner')}><StaffPicker value={owner} onChange={setOwner} /></Field>
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={onClose}>{tx('Cancel')}</Button>
          <Button onClick={submit} disabled={escalate.isPending}>{tx('Open NCR')}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

import { useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { ArrowLeft, Ban, CheckCircle2, GitBranchPlus, Plus, RefreshCw, RotateCcw, ShieldOff, Trash2, XCircle } from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Textarea } from '@/components/ui/textarea';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Skeleton } from '@/components/ui/skeleton';
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { AttachmentPanel } from '@/components/dms/AttachmentPanel';
import { useRecordAttachments } from '@/features/dms/api';
import { useAuthStore } from '@/stores/auth.store';
import { notify, apiMessage } from '@/lib/notify';
import { dateTime } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';
import {
  CHECK_STATUS_LABEL, CHECK_TYPE_LABEL, DISPOSITION_LABEL, SOURCE_LABEL, useCheckAction, useEvaluateCheck, useQcCheck,
  type CheckAction, type QcCheck, type QcDisposition, type QcEvaluation, type QcExecution, type WorksheetField,
} from '@/features/quality-control/api';
import { BlocksPill, CheckStatusPill, FailureClassPicker, Field, Item, OptionalSelect, OriginPill } from './qc-ui';
import { num, optNum, toOptions, tolerance } from './qc-format';

type Body = Record<string, unknown>;
interface SampleRow { sampleRef: string; batchNumber: string; sampleQty: string; measureValue: string; result: '' | 'passed' | 'failed' }
const emptySample = (): SampleRow => ({ sampleRef: '', batchNumber: '', sampleQty: '', measureValue: '', result: '' });

/** Check detail (ADR-S087 §2): execution panel per type, revisions, event log, waiver / reset / correction. */
export function CheckDetailPage() {
  const { id = '' } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const { data: c, isLoading, isError, refetch } = useQcCheck(id);
  const has = useAuthStore((s) => s.hasPermission);
  const [dialog, setDialog] = useState<null | 'waive' | 'reset' | 'correction' | 'cancel'>(null);

  if (isLoading) return <div className="space-y-3"><Skeleton className="h-12 w-1/2" /><Skeleton className="h-64 w-full" /></div>;
  if (isError || !c) {
    return (
      <div className="p-8 text-center text-muted-foreground">
        <p>{tx('Quality check not found')}</p>
        <Button variant="link" onClick={() => refetch()}><RefreshCw className="mr-1 h-4 w-4" /> {tx('Retry')}</Button>
      </div>
    );
  }
  const canManage = has(PERMISSIONS.qualityControl.manage);
  const closed = c.sourceClosed && c.phase === 'pre_completion';
  const done = c.status === 'passed' || c.status === 'failed' || c.status === 'waived';
  const s = c.snapshot ?? {};
  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div className="flex items-start gap-3">
          <Button variant="ghost" size="icon" onClick={() => navigate('/quality-control/checks')} aria-label={tx('Back')}><ArrowLeft className="h-4 w-4" /></Button>
          <div>
            <h1 className="flex flex-wrap items-center gap-2 text-xl font-semibold">
              {c.checkNumber} <CheckStatusPill status={c.status} /> <OriginPill origin={c.origin} /> <BlocksPill blocks={c.blocksSource} />
            </h1>
            <p className="text-sm text-muted-foreground">{c.title} · {CHECK_TYPE_LABEL[c.checkType]}</p>
          </div>
        </div>
        {canManage && (
          <div className="flex flex-wrap gap-2">
            {(c.status === 'pending' || c.status === 'failed') && !closed && <Button variant="outline" onClick={() => setDialog('waive')}><ShieldOff className="mr-1.5 h-4 w-4" /> {tx('Waive')}</Button>}
            {done && !closed && <Button variant="outline" onClick={() => setDialog('reset')}><RotateCcw className="mr-1.5 h-4 w-4" /> {tx('Reset')}</Button>}
            {done && <Button variant="outline" onClick={() => setDialog('correction')}><GitBranchPlus className="mr-1.5 h-4 w-4" /> {tx('Correction check')}</Button>}
            {c.status === 'pending' && c.origin !== 'generated' && <Button variant="outline" onClick={() => setDialog('cancel')}><Ban className="mr-1.5 h-4 w-4" /> {tx('Cancel check')}</Button>}
          </div>
        )}
      </div>

      {closed && (
        <div className="rounded-lg border border-amber-300 bg-amber-50 p-3 text-sm text-amber-900 dark:border-amber-500/40 dark:bg-amber-500/10 dark:text-amber-200">
          {tx('The source document is completed, so this check is closed. A manager can open a correction check; this record stays as it was.')}
        </div>
      )}

      <div className="grid grid-cols-1 gap-4 lg:grid-cols-3">
        <div className="space-y-4 lg:col-span-2">
          {c.status === 'pending' && !closed && has(PERMISSIONS.qualityControl.check) && <ExecutePanel check={c} />}
          {(s.instructions || s.failureMessage) && (
            <Card>
              <CardHeader className="pb-2"><CardTitle className="text-base">{tx('Instructions')}</CardTitle></CardHeader>
              <CardContent className="space-y-2 text-sm">
                {s.instructions && <p className="whitespace-pre-wrap">{s.instructions}</p>}
                {s.failureMessage && <p className="whitespace-pre-wrap text-muted-foreground"><span className="font-medium">{tx('If it fails:')}</span> {s.failureMessage}</p>}
              </CardContent>
            </Card>
          )}
          <Executions executions={c.executions ?? []} check={c} />
          <Card>
            <CardHeader className="pb-2"><CardTitle className="text-base">{tx('History')}</CardTitle></CardHeader>
            <CardContent>
              <ol className="space-y-2 text-sm">
                {(c.events ?? []).map((e) => (
                  <li key={e.id} className="flex flex-wrap items-baseline gap-x-2 border-l-2 pl-3">
                    <span className="font-medium">{eventLabel(e.action)}</span>
                    {e.fromStatus && e.toStatus && e.fromStatus !== e.toStatus && (
                      <span className="text-muted-foreground">{CHECK_STATUS_LABEL[e.fromStatus as keyof typeof CHECK_STATUS_LABEL] ?? e.fromStatus} → {CHECK_STATUS_LABEL[e.toStatus as keyof typeof CHECK_STATUS_LABEL] ?? e.toStatus}</span>
                    )}
                    {e.revision && <span className="text-xs text-muted-foreground">{tx('rev. {{n}}', { n: e.revision })}</span>}
                    <span className="text-xs text-muted-foreground">{dateTime(e.createdAt)}{e.userName ? ` · ${e.userName}` : ''}</span>
                    {e.reason && <span className="w-full text-muted-foreground">“{e.reason}”</span>}
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
                <Item label={tx('Control point')}>{c.controlPointId ? <Link className="text-primary hover:underline" to={`/quality-control/control-points/${c.controlPointId}`}>{c.controlPointReference}</Link> : '—'}</Item>
                <Item label={tx('Point version')}>{s.controlPointVersion ? `v${s.controlPointVersion}` : '—'}</Item>
                <Item label={tx('Product')}>{c.productName}</Item>
                <Item label={tx('Batch / lot')}>{c.batchNumber}</Item>
                <Item label={tx('Source')}>{c.sourceType === 'manual' ? '—' : SOURCE_LABEL[c.sourceType]}</Item>
                <Item label={tx('Reference')}>{c.sourceRef}</Item>
                <Item label={tx('Quantity covered')}>{c.coveredQty != null ? num(c.coveredQty, 4) : '—'}</Item>
                <Item label={tx('Quantity to test')}>{c.testQty != null ? num(c.testQty, 4) : '—'}</Item>
                <Item label={tx('Team')}>{c.teamName}</Item>
                <Item label={tx('Responsible')}>{c.responsibleName || '—'}</Item>
                {c.checkType === 'measure' && <Item label={tx('Tolerance')}>{tolerance(s.toleranceMin, s.toleranceMax, s.unit)}</Item>}
                {c.checkType === 'measure' && <Item label={tx('Norm')}>{s.norm != null ? `${s.norm}${s.unit ? ` ${s.unit}` : ''}` : '—'}</Item>}
                {c.checkType === 'worksheet' && s.worksheet && <Item label={tx('Worksheet')}>{`${s.worksheet.name} (v${s.worksheet.version})`}</Item>}
                <Item label={tx('Blocks the operation')}>{c.origin !== 'generated' ? tx('Never (manual)') : c.blocking ? (c.blockOnFailure ? tx('Until passed') : tx('Until done')) : c.blockOnFailure ? tx('When failed') : tx('No')}</Item>
                {c.failureClassName && <Item label={tx('Failure class')}>{c.failureClassName}</Item>}
                {c.failedQty != null && <Item label={tx('Failed quantity')}>{num(c.failedQty, 4)}</Item>}
                {c.correctsCheckId && <Item label={tx('Corrects')}><Link className="text-primary hover:underline" to={`/quality-control/checks/${c.correctsCheckId}`}>{c.correctsCheckNumber}</Link></Item>}
                {c.status === 'waived' && <Item label={tx('Waiver')}>{c.waiveDisposition ? DISPOSITION_LABEL[c.waiveDisposition] : ''}{c.excludedQty != null ? ` · ${num(c.excludedQty, 4)}` : ''}</Item>}
                {c.status === 'waived' && <Item label={tx('Waiver reason')}>{c.waiveReason}</Item>}
                {c.cancelReason && <Item label={tx('Cancel reason')}>{c.cancelReason}</Item>}
              </dl>
              {c.notes && <p className="mt-3 whitespace-pre-wrap text-sm text-muted-foreground">{c.notes}</p>}
            </CardContent>
          </Card>
          {!!c.corrections?.length && (
            <Card>
              <CardHeader className="pb-2"><CardTitle className="text-base">{tx('Correction checks')}</CardTitle></CardHeader>
              <CardContent className="space-y-1 text-sm">
                {c.corrections.map((x) => (
                  <Link key={x.id} to={`/quality-control/checks/${x.id}`} className="flex items-center justify-between rounded px-2 py-1 hover:bg-muted/50">
                    <span className="font-medium text-primary">{x.checkNumber}</span><CheckStatusPill status={x.status} />
                  </Link>
                ))}
              </CardContent>
            </Card>
          )}
          <AttachmentPanel resType="qc_check" resId={c.id} title={tx('Photos & evidence')} writePermission={PERMISSIONS.qualityControl.check} />
        </div>
      </div>

      {dialog && <ManageDialog check={c} kind={dialog} onClose={() => setDialog(null)} onDone={(out) => out.id !== c.id && navigate(`/quality-control/checks/${out.id}`)} />}
    </div>
  );
}

function eventLabel(action: string): string {
  const m: Record<string, string> = {
    created: tx('Created'), executed: tx('Executed'), waived: tx('Waived'), reset: tx('Reset'), cancelled: tx('Cancelled'),
    correction_opened: tx('Correction check opened'), recomputed: tx('Quantities recomputed'),
  };
  return m[action] ?? action.replace(/_/g, ' ');
}

// ---------------------------------------------------------------- execution

function ExecutePanel({ check: c }: { check: QcCheck }) {
  const evaluate = useEvaluateCheck();
  const action = useCheckAction();
  const [measure, setMeasure] = useState('');
  const [useSamples, setUseSamples] = useState(false);
  const [samples, setSamples] = useState<SampleRow[]>([emptySample()]);
  const [answers, setAnswers] = useState<Record<string, unknown>>({});
  const [pictureId, setPictureId] = useState('');
  const [notes, setNotes] = useState('');
  const [failing, setFailing] = useState<{ body: Body; evaluation: QcEvaluation | null; message: string | null } | null>(null);
  const attachments = useRecordAttachments('qc_check', c.checkType === 'picture' ? c.id : null);
  const busy = evaluate.isPending || action.isPending;

  const sampleBody = () => samples
    .filter((s) => s.sampleRef || s.batchNumber || s.sampleQty || s.measureValue || s.result)
    .map((s) => ({
      sampleRef: s.sampleRef.trim() || undefined, batchNumber: s.batchNumber.trim() || undefined, sampleQty: optNum(s.sampleQty),
      measureValue: c.checkType === 'measure' ? optNum(s.measureValue) : undefined, result: s.result || undefined,
    }));

  const execute = async (body: Body) => {
    try {
      const out = await action.mutateAsync({ id: c.id, action: 'execute', body });
      notify.success(out.status === 'passed' ? tx('Check passed') : tx('Check failed and recorded'));
      setFailing(null);
    } catch (err) {
      notify.error(apiMessage(err, tx('Could not record the check')));
    }
  };

  /** Evaluate first; a pass is saved at once, a failure asks for confirmation (Correct / Confirm). */
  const submit = async (body: Body) => {
    try {
      const r = await evaluate.mutateAsync({ id: c.id, body });
      if (r.result === 'passed') await execute(body);
      else setFailing({ body, evaluation: r.evaluation, message: r.failureMessage });
    } catch (err) {
      notify.error(apiMessage(err, tx('Could not evaluate the check')));
    }
  };

  const common = { notes: notes.trim() || undefined };
  const fields = c.snapshot?.worksheet?.fields ?? [];
  return (
    <Card className="border-primary/40">
      <CardHeader className="pb-2"><CardTitle className="text-base">{tx('Record the check')}</CardTitle></CardHeader>
      <CardContent className="space-y-4">
        {c.checkType === 'measure' && (
          <div className="space-y-3">
            <p className="text-sm text-muted-foreground">{tx('Tolerance')}: <span className="font-medium text-foreground">{tolerance(c.snapshot.toleranceMin, c.snapshot.toleranceMax, c.snapshot.unit)}</span></p>
            {!useSamples ? (
              <Field label={tx('Measured value') + (c.snapshot.unit ? ` (${c.snapshot.unit})` : '')}>
                <Input inputMode="decimal" className="max-w-xs" value={measure} onChange={(e) => setMeasure(e.target.value)} autoFocus />
              </Field>
            ) : <SampleTable rows={samples} onChange={setSamples} measure />}
            <Button variant="link" className="h-auto p-0" onClick={() => setUseSamples(!useSamples)}>
              {useSamples ? tx('Enter one value instead') : tx('Record several samples')}
            </Button>
          </div>
        )}
        {c.checkType === 'pass_fail' && (
          <details className="text-sm">
            <summary className="cursor-pointer text-muted-foreground">{tx('Record samples (optional)')}</summary>
            <div className="mt-2"><SampleTable rows={samples} onChange={setSamples} /></div>
          </details>
        )}
        {c.checkType === 'picture' && (
          <div className="space-y-2">
            <p className="text-sm text-muted-foreground">{tx('Attach the photo under “Photos & evidence”, then choose it here.')}</p>
            <OptionalSelect value={pictureId} onChange={setPictureId} placeholder={tx('Choose the photo')}
              options={(attachments.data ?? []).map((d) => ({ value: d.id, label: d.name }))} />
          </div>
        )}
        {c.checkType === 'worksheet' && <WorksheetForm fields={fields} answers={answers} onChange={setAnswers} />}
        {c.checkType !== 'instructions' && (
          <Field label={tx('Notes')}><Textarea rows={2} value={notes} onChange={(e) => setNotes(e.target.value)} /></Field>
        )}
        <div className="flex flex-wrap gap-2">
          {c.checkType === 'instructions' && (
            <Button disabled={busy} onClick={() => execute(common)}><CheckCircle2 className="mr-1.5 h-4 w-4" /> {tx('Mark as done')}</Button>
          )}
          {c.checkType === 'measure' && (
            <Button disabled={busy} onClick={() => submit(useSamples ? { ...common, samples: sampleBody() } : { ...common, measureValue: optNum(measure) })}>
              <CheckCircle2 className="mr-1.5 h-4 w-4" /> {tx('Validate')}
            </Button>
          )}
          {(c.checkType === 'pass_fail' || c.checkType === 'picture') && (
            <>
              <Button disabled={busy} onClick={() => submit({ ...common, result: 'passed', samples: sampleBody(), pictureDocumentId: pictureId || undefined })}>
                <CheckCircle2 className="mr-1.5 h-4 w-4" /> {tx('Pass')}
              </Button>
              <Button variant="destructive" disabled={busy}
                onClick={() => setFailing({ body: { ...common, result: 'failed', samples: sampleBody(), pictureDocumentId: pictureId || undefined }, evaluation: null, message: c.snapshot.failureMessage ?? null })}>
                <XCircle className="mr-1.5 h-4 w-4" /> {tx('Fail')}
              </Button>
            </>
          )}
          {c.checkType === 'worksheet' && (
            <Button disabled={busy} onClick={() => submit({ ...common, worksheetAnswers: answers })}><CheckCircle2 className="mr-1.5 h-4 w-4" /> {tx('Submit worksheet')}</Button>
          )}
        </div>
      </CardContent>
      {failing && (
        <FailDialog check={c} evaluation={failing.evaluation} message={failing.message} busy={action.isPending}
          onCorrect={() => setFailing(null)}
          onConfirm={(extra) => execute({ ...failing.body, ...extra })} />
      )}
    </Card>
  );
}

function SampleTable({ rows, onChange, measure }: { rows: SampleRow[]; onChange: (r: SampleRow[]) => void; measure?: boolean }) {
  const set = (i: number, patch: Partial<SampleRow>) => onChange(rows.map((r, j) => (j === i ? { ...r, ...patch } : r)));
  return (
    <div className="space-y-2">
      {rows.map((r, i) => (
        <div key={i} className="grid grid-cols-2 items-end gap-2 sm:grid-cols-[1fr_1fr_6rem_8rem_2.5rem]">
          <Input placeholder={tx('Sample {{n}}', { n: i + 1 })} value={r.sampleRef} onChange={(e) => set(i, { sampleRef: e.target.value })} />
          <Input placeholder={tx('Batch')} value={r.batchNumber} onChange={(e) => set(i, { batchNumber: e.target.value })} />
          <Input placeholder={tx('Qty')} inputMode="decimal" value={r.sampleQty} onChange={(e) => set(i, { sampleQty: e.target.value })} />
          {measure
            ? <Input placeholder={tx('Value')} inputMode="decimal" value={r.measureValue} onChange={(e) => set(i, { measureValue: e.target.value })} />
            : <OptionalSelect value={r.result} onChange={(v) => set(i, { result: v as SampleRow['result'] })} placeholder={tx('Result')}
                options={[{ value: 'passed', label: tx('Passed') }, { value: 'failed', label: tx('Failed') }]} />}
          <Button variant="ghost" size="icon" aria-label={tx('Remove')} disabled={rows.length === 1} onClick={() => onChange(rows.filter((_, j) => j !== i))}><Trash2 className="h-4 w-4" /></Button>
        </div>
      ))}
      <Button variant="outline" size="sm" onClick={() => onChange([...rows, emptySample()])}><Plus className="mr-1 h-4 w-4" /> {tx('Add sample')}</Button>
    </div>
  );
}

/** Renders a worksheet; the backend evaluator decides pass / fail. Fields hidden by showIf are not shown. */
function WorksheetForm({ fields, answers, onChange }: { fields: WorksheetField[]; answers: Record<string, unknown>; onChange: (a: Record<string, unknown>) => void }) {
  const set = (key: string, value: unknown) => {
    const next = { ...answers };
    if (value === '' || value === undefined) delete next[key];
    else next[key] = value;
    onChange(next);
  };
  const visible = (f: WorksheetField) => !f.showIf || String(answers[f.showIf.field] ?? '') === String(f.showIf.equals);
  return (
    <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
      {fields.filter(visible).map((f) => {
        const label = `${f.label}${f.required ? ' *' : ''}`;
        const v = answers[f.key];
        switch (f.type) {
          case 'number':
            return (
              <Field key={f.key} label={label + (f.min != null || f.max != null ? ` (${tolerance(f.min, f.max)})` : '')}>
                <Input inputMode="decimal" value={v === undefined ? '' : String(v)} onChange={(e) => set(f.key, e.target.value === '' ? '' : Number.isNaN(Number(e.target.value)) ? e.target.value : Number(e.target.value))} />
              </Field>
            );
          case 'checkbox':
            return (
              <label key={f.key} className="flex items-center gap-2 pt-6 text-sm">
                <input type="checkbox" className="h-4 w-4 accent-primary" checked={v === true} onChange={(e) => set(f.key, e.target.checked)} /> {label}
              </label>
            );
          case 'select':
            return (
              <Field key={f.key} label={label}>
                <OptionalSelect value={(v as string) ?? ''} onChange={(x) => set(f.key, x)} placeholder={tx('Choose')} noneLabel="—"
                  options={(f.options ?? []).map((o) => ({ value: o, label: o }))} />
              </Field>
            );
          case 'date':
            return <Field key={f.key} label={label}><Input type="date" value={(v as string) ?? ''} onChange={(e) => set(f.key, e.target.value)} /></Field>;
          default:
            return <Field key={f.key} label={label}><Input value={(v as string) ?? ''} maxLength={f.maxLength ?? undefined} onChange={(e) => set(f.key, e.target.value)} /></Field>;
        }
      })}
    </div>
  );
}

function FailDialog({ check: c, evaluation, message, busy, onCorrect, onConfirm }: {
  check: QcCheck; evaluation: QcEvaluation | null; message: string | null; busy: boolean;
  onCorrect: () => void; onConfirm: (extra: Body) => void;
}) {
  const [failureClassId, setFailureClassId] = useState('');
  const [failedQty, setFailedQty] = useState('');
  const reasons = [
    ...(evaluation?.values ?? []).filter((v) => !v.passed).map((v) => `${v.sampleNo ? tx('Sample {{n}}', { n: v.sampleNo }) + ': ' : ''}${v.value} — ${v.reason}`),
    ...(evaluation?.fields ?? []).filter((f) => f.passed === false).map((f) => `${f.label}: ${f.reason}`),
  ];
  return (
    <Dialog open onOpenChange={(o) => !o && onCorrect()}>
      <DialogContent className="sm:max-w-lg">
        <DialogHeader>
          <DialogTitle>{tx('The check fails')}</DialogTitle>
          <DialogDescription>{tx('Correct the entry, or confirm the failure to record it.')}</DialogDescription>
        </DialogHeader>
        {reasons.length > 0 && <ul className="list-disc space-y-1 pl-5 text-sm text-destructive">{reasons.map((r) => <li key={r}>{r}</li>)}</ul>}
        {message && <p className="whitespace-pre-wrap rounded-md bg-muted p-2 text-sm">{message}</p>}
        <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
          <Field label={tx('Failure class') + (c.failureClassRequired ? ' *' : '')}><FailureClassPicker value={failureClassId} onChange={setFailureClassId} /></Field>
          <Field label={tx('Failed quantity')}><Input inputMode="decimal" value={failedQty} onChange={(e) => setFailedQty(e.target.value)} placeholder={c.coveredQty != null ? `≤ ${c.coveredQty}` : ''} /></Field>
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={onCorrect}>{tx('Correct the entry')}</Button>
          <Button variant="destructive" disabled={busy || (c.failureClassRequired && !failureClassId)}
            onClick={() => onConfirm({ failureClassId: failureClassId || undefined, failedQty: optNum(failedQty) })}>{tx('Confirm failure')}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

// ---------------------------------------------------------------- history

function Executions({ executions, check: c }: { executions: QcExecution[]; check: QcCheck }) {
  if (!executions.length) return null;
  return (
    <Card>
      <CardHeader className="pb-2"><CardTitle className="text-base">{tx('Executions')}</CardTitle></CardHeader>
      <CardContent className="space-y-3">
        {executions.map((e) => (
          <div key={e.id} className={`rounded-lg border p-3 text-sm ${e.superseded ? 'opacity-70' : ''}`}>
            <div className="flex flex-wrap items-center gap-2">
              <span className="font-medium">{tx('Revision {{n}}', { n: e.revision })}</span>
              <CheckStatusPill status={e.result} />
              {e.superseded && <span className="text-xs text-muted-foreground">{tx('superseded by a reset')}</span>}
              <span className="ml-auto text-xs text-muted-foreground">{dateTime(e.executedAt)}{e.executedByName ? ` · ${e.executedByName}` : ''}</span>
            </div>
            {e.measureValue != null && <p className="mt-1">{tx('Value')}: <span className="font-medium">{e.measureValue}{c.snapshot.unit ? ` ${c.snapshot.unit}` : ''}</span></p>}
            {e.failureClassName && <p className="mt-1">{tx('Failure class')}: {e.failureClassName}{e.failedQty != null ? ` · ${tx('failed qty')} ${num(e.failedQty, 4)}` : ''}</p>}
            {e.samples.length > 0 && (
              <table className="mt-2 w-full text-xs">
                <thead className="text-muted-foreground"><tr><th className="text-left">#</th><th className="text-left">{tx('Sample')}</th><th className="text-left">{tx('Batch')}</th><th className="text-right">{tx('Qty')}</th><th className="text-right">{tx('Value')}</th><th className="text-right">{tx('Result')}</th></tr></thead>
                <tbody>
                  {e.samples.map((s) => (
                    <tr key={s.id} className="border-t">
                      <td>{s.sampleNo}</td><td>{s.sampleRef ?? '—'}</td><td>{s.batchNumber ?? '—'}</td>
                      <td className="text-right">{s.sampleQty ?? '—'}</td><td className="text-right">{s.measureValue ?? '—'}</td>
                      <td className={`text-right ${s.result === 'failed' ? 'text-destructive' : ''}`}>{s.result ? CHECK_STATUS_LABEL[s.result] : '—'}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
            {e.evaluation?.fields && (
              <ul className="mt-2 grid grid-cols-1 gap-1 text-xs sm:grid-cols-2">
                {e.evaluation.fields.filter((f) => f.visible).map((f) => (
                  <li key={f.key} className={f.passed === false ? 'text-destructive' : ''}>
                    <span className="text-muted-foreground">{f.label}:</span> {String((e.worksheetAnswers ?? {})[f.key] ?? '—')}{f.reason ? ` (${f.reason})` : ''}
                  </li>
                ))}
              </ul>
            )}
            {e.notes && <p className="mt-1 whitespace-pre-wrap text-muted-foreground">{e.notes}</p>}
          </div>
        ))}
      </CardContent>
    </Card>
  );
}

// ---------------------------------------------------------------- manage actions

function ManageDialog({ check: c, kind, onClose, onDone }: {
  check: QcCheck; kind: 'waive' | 'reset' | 'correction' | 'cancel'; onClose: () => void; onDone: (c: QcCheck) => void;
}) {
  const action = useCheckAction();
  const [reason, setReason] = useState('');
  const [disposition, setDisposition] = useState<QcDisposition | ''>('');
  const [excludedQty, setExcludedQty] = useState('');
  const [notes, setNotes] = useState('');
  const conf: Record<typeof kind, { title: string; description: string; action: CheckAction; ok: string }> = {
    waive: { title: tx('Waive {{n}}', { n: c.checkNumber }), description: tx('A waived check no longer blocks. The reason and disposition are recorded.'), action: 'waive', ok: tx('Waive') },
    reset: { title: tx('Reset {{n}}', { n: c.checkNumber }), description: tx('Opens revision {{n}}. The current result stays in the history.', { n: c.currentRevision + 1 }), action: 'reset', ok: tx('Reset') },
    correction: { title: tx('Correction check for {{n}}', { n: c.checkNumber }), description: tx('Creates a new, independent check. {{n}} is not changed.', { n: c.checkNumber }), action: 'corrections', ok: tx('Open correction check') },
    cancel: { title: tx('Cancel {{n}}', { n: c.checkNumber }), description: tx('The check stays in the list as cancelled.'), action: 'cancel', ok: tx('Cancel check') },
  };
  const k = conf[kind];
  const submit = async () => {
    const body: Body = { reason: reason.trim() };
    if (kind === 'waive') Object.assign(body, { disposition, excludedQty: disposition === 'quantity_excluded' ? optNum(excludedQty) : undefined });
    if (kind === 'correction') body.notes = notes.trim() || undefined;
    try {
      const out = await action.mutateAsync({ id: c.id, action: k.action, body });
      notify.success(kind === 'correction' ? tx('Correction check {{n}} opened', { n: out.checkNumber }) : tx('Done'));
      onDone(out);
      onClose();
    } catch (err) {
      notify.error(apiMessage(err, tx('Could not update the check')));
    }
  };
  const valid = reason.trim() && (kind !== 'waive' || (disposition && (disposition !== 'quantity_excluded' || excludedQty.trim())));
  return (
    <Dialog open onOpenChange={(o) => !o && onClose()}>
      <DialogContent className="sm:max-w-lg">
        <DialogHeader><DialogTitle>{k.title}</DialogTitle><DialogDescription>{k.description}</DialogDescription></DialogHeader>
        <div className="space-y-3">
          {kind === 'waive' && (
            <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
              <Field label={tx('Disposition *')}><OptionalSelect value={disposition} onChange={(v) => setDisposition(v as QcDisposition)} placeholder={tx('Choose')} options={toOptions(DISPOSITION_LABEL)} /></Field>
              {disposition === 'quantity_excluded' && <Field label={tx('Excluded quantity *')}><Input inputMode="decimal" value={excludedQty} onChange={(e) => setExcludedQty(e.target.value)} /></Field>}
            </div>
          )}
          <Field label={tx('Reason *')}><Textarea rows={3} value={reason} onChange={(e) => setReason(e.target.value)} /></Field>
          {kind === 'correction' && <Field label={tx('Notes')}><Textarea rows={2} value={notes} onChange={(e) => setNotes(e.target.value)} /></Field>}
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={onClose}>{tx('Back')}</Button>
          <Button onClick={submit} disabled={!valid || action.isPending} variant={kind === 'cancel' ? 'destructive' : 'default'}>{k.ok}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

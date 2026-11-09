import { useEffect, useState } from 'react';
import { CheckCircle2, XCircle } from 'lucide-react';
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Textarea } from '@/components/ui/textarea';
import {
  useCreateRequest, useProductBoms, useReviewRequest, useUpdateRequest, type ChangeRequest, type Priority, type ReasonCode,
} from '@/features/manufacturing/api-plm';
import { notify } from '@/lib/notify';
import { cn } from '@/lib/utils';
import { tx } from '@/lib/i18n/tx';
import { Field, ProductPicker } from './plm-ui';
import { PRIORITIES, PRIORITY, REASONS, selectClass } from './vocab';

/** New or edit a change request (ECR). */
export function RequestDialog({ open, onClose, request, productId: presetProduct }: {
  open: boolean; onClose: () => void; request?: ChangeRequest | null; productId?: string;
}) {
  const init = () => ({
    title: request?.title ?? '',
    description: request?.description ?? '',
    productId: request?.productId ?? presetProduct ?? '',
    bomId: request?.bomId ?? '',
    reasonCode: (request?.reasonCode ?? 'other') as ReasonCode,
    priority: (request?.priority ?? 'normal') as Priority,
    dueDate: request?.dueDate ? String(request.dueDate).slice(0, 10) : '',
  });
  const [f, setF] = useState(init);
  useEffect(() => {
    if (open) setF(init());
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open, request?.id, presetProduct]);
  const set = <K extends keyof typeof f>(k: K, v: (typeof f)[K]) => setF((s) => ({ ...s, [k]: v }));
  const { data: boms = [] } = useProductBoms(f.productId || undefined);
  const create = useCreateRequest();
  const update = useUpdateRequest();
  const busy = create.isPending || update.isPending;

  const submit = () => {
    if (!f.title.trim()) return notify.error(tx('Give the request a title.'));
    const body = {
      title: f.title.trim(),
      description: f.description,
      productId: f.productId,
      bomId: f.bomId,
      reasonCode: f.reasonCode,
      priority: f.priority,
      // The API cannot clear a due date (an empty value is not a date), so an empty field is left out.
      dueDate: f.dueDate || undefined,
    };
    const done = { onSuccess: () => { notify.success(request ? tx('Request saved') : tx('Request submitted')); onClose(); } };
    if (request) update.mutate({ id: request.id, body }, done);
    else create.mutate({ ...body, description: body.description || undefined, productId: body.productId || undefined, bomId: body.bomId || undefined }, done);
  };

  return (
    <Dialog open={open} onOpenChange={(o) => !o && onClose()}>
      <DialogContent className="max-h-[92vh] max-w-xl overflow-y-auto">
        <DialogHeader>
          <DialogTitle>{request ? tx('Edit {{n}}', { n: request.number }) : tx('New change request')}</DialogTitle>
          <DialogDescription>{tx('Describe the problem or the improvement; engineering reviews it and turns it into a change order.')}</DialogDescription>
        </DialogHeader>
        <div className="grid gap-4 sm:grid-cols-2">
          <Field label={tx('Title')} required className="sm:col-span-2">
            <Input value={f.title} maxLength={200} onChange={(e) => set('title', e.target.value)} />
          </Field>
          <Field label={tx('Product')}>
            <ProductPicker value={f.productId} onChange={(v) => { set('productId', v); set('bomId', ''); }} clearable placeholder={tx('Optional')} />
          </Field>
          <Field label={tx('BOM')}>
            <select className={selectClass} value={f.bomId} disabled={!f.productId} onChange={(e) => set('bomId', e.target.value)}>
              <option value="">{tx('Any / not specific')}</option>
              {boms.filter((b) => b.status !== 'archived').map((b) => (
                <option key={b.id} value={b.id}>{b.code} v{b.version} ({b.status})</option>
              ))}
            </select>
          </Field>
          <Field label={tx('Reason')}>
            <select className={selectClass} value={f.reasonCode} onChange={(e) => set('reasonCode', e.target.value as ReasonCode)}>
              {(Object.keys(REASONS) as ReasonCode[]).map((r) => <option key={r} value={r}>{REASONS[r]}</option>)}
            </select>
          </Field>
          <Field label={tx('Priority')}>
            <select className={selectClass} value={f.priority} onChange={(e) => set('priority', e.target.value as Priority)}>
              {PRIORITIES.map((p) => <option key={p} value={p}>{PRIORITY[p].label}</option>)}
            </select>
          </Field>
          <Field label={tx('Needed by')}>
            <Input type="date" value={f.dueDate} onChange={(e) => set('dueDate', e.target.value)} />
          </Field>
          <Field label={tx('Description')} className="sm:col-span-2">
            <Textarea rows={4} maxLength={4000} value={f.description} onChange={(e) => set('description', e.target.value)} placeholder={tx('What is wrong today, and what should change?')} />
          </Field>
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={onClose}>{tx('Cancel')}</Button>
          <Button onClick={submit} disabled={busy}>{request ? tx('Save') : tx('Submit request')}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

/** Accept or reject a new change request (rejecting needs a note). */
export function ReviewDialog({ request, onClose }: { request: ChangeRequest | null; onClose: () => void }) {
  const [decision, setDecision] = useState<'accept' | 'reject'>('accept');
  const [note, setNote] = useState('');
  useEffect(() => { if (request) { setDecision('accept'); setNote(''); } }, [request]);
  const review = useReviewRequest();
  const submit = () => {
    if (!request) return;
    if (decision === 'reject' && !note.trim()) return notify.error(tx('Give a reason to reject the change request.'));
    review.mutate({ id: request.id, decision, note: note.trim() || undefined }, {
      onSuccess: () => { notify.success(decision === 'accept' ? tx('Request accepted') : tx('Request rejected')); onClose(); },
    });
  };
  return (
    <Dialog open={!!request} onOpenChange={(o) => !o && onClose()}>
      <DialogContent className="max-w-md">
        <DialogHeader>
          <DialogTitle>{tx('Review {{n}}', { n: request?.number ?? '' })}</DialogTitle>
          <DialogDescription>{request?.title}</DialogDescription>
        </DialogHeader>
        {request?.description && <p className="whitespace-pre-wrap rounded-md bg-muted/40 p-3 text-sm">{request.description}</p>}
        <div className="grid grid-cols-2 gap-2">
          {(['accept', 'reject'] as const).map((d) => (
            <button
              key={d}
              type="button"
              onClick={() => setDecision(d)}
              className={cn('flex items-center justify-center gap-2 rounded-lg border px-3 py-2 text-sm font-medium',
                decision === d && d === 'accept' && 'border-emerald-500 bg-emerald-50 text-emerald-700 dark:bg-emerald-500/15 dark:text-emerald-300',
                decision === d && d === 'reject' && 'border-rose-500 bg-rose-50 text-rose-700 dark:bg-rose-500/15 dark:text-rose-300',
                decision !== d && 'hover:bg-muted')}
            >
              {d === 'accept' ? <CheckCircle2 className="h-4 w-4" /> : <XCircle className="h-4 w-4" />}
              {d === 'accept' ? tx('Accept') : tx('Reject')}
            </button>
          ))}
        </div>
        <Field label={decision === 'reject' ? tx('Reason for rejecting') : tx('Note')} required={decision === 'reject'}>
          <Textarea rows={3} maxLength={2000} value={note} onChange={(e) => setNote(e.target.value)} />
        </Field>
        <DialogFooter>
          <Button variant="outline" onClick={onClose}>{tx('Cancel')}</Button>
          <Button onClick={submit} disabled={review.isPending} variant={decision === 'reject' ? 'destructive' : 'default'}>
            {decision === 'accept' ? tx('Accept request') : tx('Reject request')}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

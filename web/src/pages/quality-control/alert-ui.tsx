import { useState } from 'react';
import { Star } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Textarea } from '@/components/ui/textarea';
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { notify, apiMessage } from '@/lib/notify';
import { tx } from '@/lib/i18n/tx';
import { cn } from '@/lib/utils';
import { PRIORITY_LABEL, useCreateAlert, useMoveAlert, useTags, type QcAlert, type QcSourceType, type QcTag } from '@/features/quality-control/api';
import { Field, ProductPicker, StaffPicker, TeamPicker } from './qc-ui';

/** Priority 0–3 as stars (Odoo style); clickable when {@code onChange} is given. */
export function PriorityStars({ value, onChange }: { value: number; onChange?: (v: number) => void }) {
  return (
    <span className="inline-flex items-center" title={tx(PRIORITY_LABEL[value] ?? 'Normal')}>
      {[1, 2, 3].map((n) => (
        <button key={n} type="button" disabled={!onChange}
          onClick={(e) => { e.stopPropagation(); onChange?.(value === n ? n - 1 : n); }}
          className={cn('p-0.5', onChange ? 'cursor-pointer' : 'cursor-default')} aria-label={tx('Priority {{n}}', { n })}>
          <Star className={cn('h-3.5 w-3.5', n <= value ? 'fill-amber-400 text-amber-400' : 'text-muted-foreground/40')} />
        </button>
      ))}
    </span>
  );
}

export function TagChips({ tags }: { tags: QcTag[] }) {
  if (!tags.length) return null;
  return (
    <span className="flex flex-wrap gap-1">
      {tags.map((t) => (
        <span key={t.id} className="rounded-full border px-1.5 py-0.5 text-[11px] leading-none"
          style={t.color ? { borderColor: t.color, color: t.color } : undefined}>{t.name}</span>
      ))}
    </span>
  );
}

/** A manual quality alert: free, from a failed check, or from a document; optionally placed in a stage. */
export function AlertDialog({ checkId, source, stageId, defaultTitle, onClose, onSaved }: {
  checkId?: string; source?: { sourceType: Exclude<QcSourceType, 'manual'>; sourceId: string }; stageId?: string;
  defaultTitle?: string; onClose: () => void; onSaved?: (a: QcAlert) => void;
}) {
  const [title, setTitle] = useState(defaultTitle ?? '');
  const [description, setDescription] = useState('');
  const [priority, setPriority] = useState(0);
  const [teamId, setTeamId] = useState('');
  const [responsible, setResponsible] = useState('');
  const [productId, setProductId] = useState('');
  const [batch, setBatch] = useState('');
  const [tagIds, setTagIds] = useState<string[]>([]);
  const tags = useTags();
  const create = useCreateAlert();
  const move = useMoveAlert();
  const submit = async () => {
    if (!title.trim()) { notify.error(tx('Give the alert a title.')); return; }
    try {
      let out = await create.mutateAsync({
        title: title.trim(), description: description.trim() || undefined, priority, teamId: teamId || undefined,
        responsibleUserId: responsible || undefined, checkId, ...(source ?? {}),
        productId: checkId ? undefined : productId || undefined, batchNumber: checkId ? undefined : batch.trim() || undefined,
        tagIds: tagIds.length ? tagIds : undefined,
      });
      if (stageId && out.stageId !== stageId) out = await move.mutateAsync({ id: out.id, stageId });
      notify.success(tx('Alert {{number}} raised', { number: out.alertNumber }));
      onSaved?.(out);
      onClose();
    } catch (err) {
      notify.error(apiMessage(err, tx('Could not raise the alert')));
    }
  };
  return (
    <Dialog open onOpenChange={(o) => !o && onClose()}>
      <DialogContent className="max-h-[90vh] overflow-y-auto sm:max-w-xl">
        <DialogHeader>
          <DialogTitle>{tx('New quality alert')}</DialogTitle>
          <DialogDescription>
            {checkId ? tx('Product, batch, source and team are taken from the check.') : tx('Track a quality problem through to its fix.')}
          </DialogDescription>
        </DialogHeader>
        <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
          <Field label={tx('Title')} className="sm:col-span-2"><Input value={title} onChange={(e) => setTitle(e.target.value)} autoFocus /></Field>
          <Field label={tx('Description')} className="sm:col-span-2"><Textarea rows={3} value={description} onChange={(e) => setDescription(e.target.value)} /></Field>
          <Field label={tx('Priority')}><div className="pt-1"><PriorityStars value={priority} onChange={setPriority} /></div></Field>
          <Field label={tx('Team')}><TeamPicker value={teamId} onChange={setTeamId} /></Field>
          <Field label={tx('Responsible')}><StaffPicker value={responsible} onChange={setResponsible} /></Field>
          {!checkId && <Field label={tx('Product')}><ProductPicker value={productId} onChange={setProductId} /></Field>}
          {!checkId && <Field label={tx('Batch / lot')}><Input value={batch} onChange={(e) => setBatch(e.target.value)} /></Field>}
          {!!tags.data?.length && (
            <Field label={tx('Tags')} className="sm:col-span-2">
              <div className="flex flex-wrap gap-1.5">
                {tags.data.map((t) => {
                  const on = tagIds.includes(t.id);
                  return (
                    <button key={t.id} type="button" onClick={() => setTagIds(on ? tagIds.filter((x) => x !== t.id) : [...tagIds, t.id])}
                      className={cn('rounded-full border px-2 py-0.5 text-xs', on ? 'border-primary bg-primary/10 text-primary' : 'text-muted-foreground')}>{t.name}</button>
                  );
                })}
              </div>
            </Field>
          )}
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={onClose}>{tx('Cancel')}</Button>
          <Button onClick={submit} disabled={create.isPending || move.isPending}>{tx('Raise alert')}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

import { useEffect, useMemo, useState } from 'react';
import { ArrowRight } from 'lucide-react';
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Textarea } from '@/components/ui/textarea';
import { useAuthStore } from '@/stores/auth.store';
import { notify } from '@/lib/notify';
import { tx } from '@/lib/i18n/tx';
import {
  useRegisterItem, useTransitionItem, useUpdateItem, type LifecycleState, type PlmItem, type PlmItemDetail,
} from '@/features/manufacturing/api-plm';
import { Field, LifecyclePill, ProductPicker, UserSelect } from './plm-ui';
import { LIFECYCLE, REGISTER_STATES, selectClass } from './vocab';

/** Put a product under lifecycle management. */
export function RegisterItemDialog({ open, onClose, registered, onDone }: {
  open: boolean; onClose: () => void; registered: Set<string>; onDone?: (item: PlmItem) => void;
}) {
  const me = useAuthStore((s) => s.user?.id ?? '');
  const blank = { productId: '', revision: 'A', lifecycleState: 'design' as (typeof REGISTER_STATES)[number], ownerId: me, description: '' };
  const [f, setF] = useState(blank);
  useEffect(() => {
    if (open) setF(blank);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open]);
  const register = useRegisterItem();
  const submit = () => {
    if (!f.productId) return notify.error(tx('Pick a product.'));
    register.mutate({
      productId: f.productId,
      revision: f.revision.trim() || undefined,
      lifecycleState: f.lifecycleState,
      ownerId: f.ownerId || undefined,
      description: f.description.trim() || undefined,
    }, {
      onSuccess: (item) => {
        notify.success(tx('Product registered'));
        onClose();
        onDone?.(item);
      },
    });
  };
  return (
    <Dialog open={open} onOpenChange={(o) => !o && onClose()}>
      <DialogContent className="max-w-lg">
        <DialogHeader>
          <DialogTitle>{tx('Register product')}</DialogTitle>
          <DialogDescription>{tx('Track the product through its lifecycle, revisions and engineering changes.')}</DialogDescription>
        </DialogHeader>
        <div className="grid gap-4 sm:grid-cols-2">
          <Field label={tx('Product')} required className="sm:col-span-2">
            <ProductPicker value={f.productId} onChange={(v) => setF({ ...f, productId: v })} exclude={registered} />
          </Field>
          <Field label={tx('Revision')} hint={tx('Bumped automatically when a change is applied.')}>
            <Input value={f.revision} maxLength={10} onChange={(e) => setF({ ...f, revision: e.target.value })} />
          </Field>
          <Field label={tx('Lifecycle state')}>
            <select className={selectClass} value={f.lifecycleState} onChange={(e) => setF({ ...f, lifecycleState: e.target.value as typeof f.lifecycleState })}>
              {REGISTER_STATES.map((s) => <option key={s} value={s}>{LIFECYCLE[s].label}</option>)}
            </select>
          </Field>
          <Field label={tx('Owner')} className="sm:col-span-2">
            <UserSelect value={f.ownerId} onChange={(v) => setF({ ...f, ownerId: v })} />
          </Field>
          <Field label={tx('Description')} className="sm:col-span-2">
            <Textarea rows={3} maxLength={2000} value={f.description} onChange={(e) => setF({ ...f, description: e.target.value })} />
          </Field>
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={onClose}>{tx('Cancel')}</Button>
          <Button onClick={submit} disabled={register.isPending}>{tx('Register')}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

/** Owner, description, notes and replacement product of a lifecycle item. */
export function EditItemDialog({ item, open, onClose }: { item: PlmItemDetail; open: boolean; onClose: () => void }) {
  const init = () => ({
    ownerId: item.ownerId ?? '', description: item.description ?? '', notes: item.notes ?? '',
    replacementProductId: item.replacementProductId ?? '',
  });
  const [f, setF] = useState(init);
  useEffect(() => {
    if (open) setF(init());
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open, item.id]);
  const update = useUpdateItem();
  const self = useMemo(() => new Set([item.productId]), [item.productId]);
  const submit = () => update.mutate({ id: item.id, body: f }, {
    onSuccess: () => { notify.success(tx('Saved')); onClose(); },
  });
  return (
    <Dialog open={open} onOpenChange={(o) => !o && onClose()}>
      <DialogContent className="max-w-lg">
        <DialogHeader>
          <DialogTitle>{tx('Edit {{code}}', { code: item.product.code })}</DialogTitle>
        </DialogHeader>
        <div className="grid gap-4">
          <Field label={tx('Owner')}>
            <UserSelect value={f.ownerId} onChange={(v) => setF({ ...f, ownerId: v })} />
          </Field>
          <Field label={tx('Description')}>
            <Textarea rows={3} maxLength={2000} value={f.description} onChange={(e) => setF({ ...f, description: e.target.value })} />
          </Field>
          <Field label={tx('Notes')}>
            <Textarea rows={3} maxLength={4000} value={f.notes} onChange={(e) => setF({ ...f, notes: e.target.value })} />
          </Field>
          <Field label={tx('Replacement product')} hint={tx('Suggested instead of this product once it is phased out.')}>
            <ProductPicker value={f.replacementProductId} onChange={(v) => setF({ ...f, replacementProductId: v })} exclude={self} clearable placeholder={tx('None')} />
          </Field>
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={onClose}>{tx('Cancel')}</Button>
          <Button onClick={submit} disabled={update.isPending}>{tx('Save')}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

/** Direct lifecycle transition (with a note and, when phasing out, a replacement product). */
export function TransitionDialog({ item, toState, onClose }: { item: PlmItemDetail; toState: LifecycleState | null; onClose: () => void }) {
  const [note, setNote] = useState('');
  const [replacement, setReplacement] = useState('');
  useEffect(() => {
    if (toState) { setNote(''); setReplacement(item.replacementProductId ?? ''); }
  }, [toState, item.replacementProductId]);
  const transition = useTransitionItem();
  const self = useMemo(() => new Set([item.productId]), [item.productId]);
  const ending = toState === 'phase_out' || toState === 'obsolete';
  const submit = () => {
    if (!toState) return;
    transition.mutate({
      id: item.id,
      body: { toState, note: note.trim() || undefined, replacementProductId: ending && replacement ? replacement : undefined },
    }, {
      onSuccess: () => { notify.success(tx('{{code}} is now {{state}}', { code: item.product.code, state: LIFECYCLE[toState].label })); onClose(); },
    });
  };
  return (
    <Dialog open={!!toState} onOpenChange={(o) => !o && onClose()}>
      <DialogContent className="max-w-md">
        <DialogHeader>
          <DialogTitle>{tx('Change lifecycle state')}</DialogTitle>
          <DialogDescription>{item.product.code} · {item.product.name}</DialogDescription>
        </DialogHeader>
        {toState && (
          <div className="space-y-4">
            <div className="flex items-center justify-center gap-3 rounded-lg bg-muted/40 py-3">
              <LifecyclePill state={item.lifecycleState} />
              <ArrowRight className="h-4 w-4 text-muted-foreground" />
              <LifecyclePill state={toState} />
            </div>
            {toState === 'obsolete' && (
              <p className="rounded-md bg-amber-50 px-3 py-2 text-xs text-amber-800 dark:bg-amber-500/15 dark:text-amber-300">
                {tx('Refused while the product is a component of an active BOM or has open production orders.')}
              </p>
            )}
            <Field label={tx('Note')}>
              <Textarea rows={3} maxLength={2000} value={note} onChange={(e) => setNote(e.target.value)} placeholder={tx('Why is the state changing?')} />
            </Field>
            {ending && (
              <Field label={tx('Replacement product')} hint={tx('Optional: the product that takes over.')}>
                <ProductPicker value={replacement} onChange={setReplacement} exclude={self} clearable placeholder={tx('None')} />
              </Field>
            )}
          </div>
        )}
        <DialogFooter>
          <Button variant="outline" onClick={onClose}>{tx('Cancel')}</Button>
          <Button onClick={submit} disabled={transition.isPending}>{tx('Change state')}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

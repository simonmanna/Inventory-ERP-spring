import { useEffect, useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { AlertTriangle } from 'lucide-react';
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Textarea } from '@/components/ui/textarea';
import { useRoutings } from '@/features/manufacturing/api';
import {
  useCancelChange, useConvertRequest, useCreateChange, usePlmDocuments, usePlmItems, useProductBoms, useUpdateChange,
  type ChangeDetail, type ChangeRequest, type ChangeType, type Effectivity, type LifecycleState, type Priority,
  type StockDisposition, type WipDisposition,
} from '@/features/manufacturing/api-plm';
import { notify } from '@/lib/notify';
import { cn } from '@/lib/utils';
import { tx } from '@/lib/i18n/tx';
import { CheckList, Field, LifecyclePill, ProductPicker, UserSelect } from './plm-ui';
import {
  CHANGE_TYPE, CHANGE_TYPES, DOC_TYPES, LIFECYCLE, PRIORITIES, PRIORITY, STOCK_DISPOSITION, TRANSITIONS, WIP_DISPOSITION,
  selectClass,
} from './vocab';

interface Target {
  bomId: string;
  routingId: string;
  targetLifecycleState: LifecycleState | '';
  documentIds: string[];
}
const blankTarget: Target = { bomId: '', routingId: '', targetLifecycleState: '', documentIds: [] };

/** What a typed ECO changes: the BOM to revise, the routing to copy, the target lifecycle state or the documents. */
function ChangeTargetFields({ type, productId, value, onChange }: {
  type: ChangeType; productId: string; value: Target; onChange: (t: Target) => void;
}) {
  const { data: boms = [], isLoading: bomsLoading } = useProductBoms(type === 'bom' || type === 'routing' ? productId || undefined : undefined);
  const { data: routings = [] } = useRoutings();
  const { data: items = [] } = usePlmItems({ productId }, type === 'product' && !!productId);
  const { data: drafts = [] } = usePlmDocuments({ status: 'draft' }, type === 'document');
  const item = items[0];

  const bomOptions = useMemo(
    () => boms.filter((b) => b.status !== 'archived')
      .sort((a, b) => Number(b.status === 'active') - Number(a.status === 'active') || b.version - a.version),
    [boms],
  );
  // Default to the active (default) BOM of the product.
  useEffect(() => {
    if (type === 'bom' && !value.bomId && bomOptions.length) {
      const pick = bomOptions.find((b) => b.status === 'active' && b.isDefault) ?? bomOptions[0];
      onChange({ ...value, bomId: pick.id });
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [type, bomOptions]);

  const routingOptions = useMemo(() => {
    const all = (routings as Array<{ id: string; name: string; productId?: string | null; isActive?: boolean; operations?: unknown[] }>);
    if (!productId) return all;
    const viaBom = new Set(boms.map((b) => b.routingId).filter(Boolean) as string[]);
    const mine = all.filter((r) => r.productId === productId || viaBom.has(r.id));
    return mine.length ? mine : all;
  }, [routings, productId, boms]);

  const docOptions = useMemo(
    () => drafts
      .slice()
      .sort((a, b) => Number(b.productId === productId) - Number(a.productId === productId))
      .map((d) => ({
        id: d.id,
        label: <><span className="font-mono text-xs text-muted-foreground">{d.number} v{d.version}</span> {d.title}</>,
        hint: [DOC_TYPES[d.docType], d.product ? `${d.product.code} ${d.product.name}` : null,
          d.changeNumber ? tx('on {{n}}', { n: d.changeNumber }) : null].filter(Boolean).join(' · '),
      })),
    [drafts, productId],
  );

  if (type === 'bom') {
    if (!productId) return <p className="text-sm text-muted-foreground">{tx('Pick the product whose BOM changes.')}</p>;
    return (
      <Field label={tx('BOM to revise')} required hint={tx('A new draft version is created; edit it, then approve and apply the change.')}>
        <select className={selectClass} value={value.bomId} onChange={(e) => onChange({ ...value, bomId: e.target.value })}>
          <option value="">{bomsLoading ? tx('Loading…') : bomOptions.length ? tx('Select a BOM…') : tx('This product has no BOM')}</option>
          {bomOptions.map((b) => (
            <option key={b.id} value={b.id}>{b.code} v{b.version} · {b.name} ({b.status}{b.isDefault ? `, ${tx('default')}` : ''})</option>
          ))}
        </select>
      </Field>
    );
  }
  if (type === 'routing') {
    return (
      <Field label={tx('Routing to revise')} required hint={tx('An inactive copy is created; edit its operations on the change.')}>
        <select className={selectClass} value={value.routingId} onChange={(e) => onChange({ ...value, routingId: e.target.value })}>
          <option value="">{tx('Select a routing…')}</option>
          {routingOptions.map((r) => (
            <option key={r.id} value={r.id}>{r.name}{r.isActive === false ? ` (${tx('inactive')})` : ''}</option>
          ))}
        </select>
      </Field>
    );
  }
  if (type === 'product') {
    if (!productId) return <p className="text-sm text-muted-foreground">{tx('Pick the product whose lifecycle changes.')}</p>;
    if (!item) {
      return (
        <p className="flex items-center gap-2 rounded-md bg-amber-50 px-3 py-2 text-sm text-amber-800 dark:bg-amber-500/15 dark:text-amber-300">
          <AlertTriangle className="h-4 w-4 shrink-0" />
          {tx('This product is not under lifecycle management yet; register it first.')}
        </p>
      );
    }
    return (
      <Field label={tx('Target lifecycle state')} required>
        <div className="flex flex-wrap items-center gap-2">
          <span className="text-xs text-muted-foreground">{tx('Now')}:</span>
          <LifecyclePill state={item.lifecycleState} />
        </div>
        <div className="mt-2 flex flex-wrap gap-2">
          {TRANSITIONS[item.lifecycleState].map((s) => (
            <button
              key={s}
              type="button"
              onClick={() => onChange({ ...value, targetLifecycleState: s })}
              className={cn('rounded-md border px-3 py-1.5 text-sm', value.targetLifecycleState === s ? 'border-primary bg-primary/10 font-medium' : 'hover:bg-muted')}
            >
              {LIFECYCLE[s].label}
            </button>
          ))}
        </div>
      </Field>
    );
  }
  return (
    <Field label={tx('Draft documents to release')} required hint={tx('They are released when the change is applied.')}>
      <CheckList
        options={docOptions}
        value={value.documentIds}
        onChange={(ids) => onChange({ ...value, documentIds: ids })}
        empty={tx('No draft documents. Create one in the document register first.')}
      />
    </Field>
  );
}

function targetError(type: ChangeType, t: Target): string | null {
  if (type === 'bom' && !t.bomId) return tx('Pick the BOM to revise.');
  if (type === 'routing' && !t.routingId) return tx('Pick the routing to revise.');
  if (type === 'product' && !t.targetLifecycleState) return tx('Pick the target lifecycle state.');
  if (type === 'document' && !t.documentIds.length) return tx('Pick at least one draft document.');
  return null;
}

function targetBody(type: ChangeType, t: Target) {
  return {
    bomId: type === 'bom' ? t.bomId : undefined,
    routingId: type === 'routing' ? t.routingId : undefined,
    targetLifecycleState: type === 'product' && t.targetLifecycleState ? t.targetLifecycleState : undefined,
    documentIds: type === 'document' ? t.documentIds : undefined,
  };
}

function TypeCards({ value, onChange }: { value: ChangeType; onChange: (t: ChangeType) => void }) {
  return (
    <div className="grid grid-cols-2 gap-2 sm:grid-cols-4">
      {CHANGE_TYPES.map((t) => {
        const meta = CHANGE_TYPE[t];
        const Icon = meta.icon;
        return (
          <button
            key={t}
            type="button"
            onClick={() => onChange(t)}
            className={cn('flex flex-col items-start gap-1 rounded-lg border p-3 text-left transition',
              value === t ? 'border-primary bg-primary/5 ring-1 ring-primary' : 'hover:bg-muted/50')}
          >
            <span className={cn('rounded-md p-1.5', meta.className)}><Icon className="h-4 w-4" /></span>
            <span className="text-sm font-medium">{meta.label}</span>
            <span className="text-[11px] leading-tight text-muted-foreground">{meta.hint}</span>
          </button>
        );
      })}
    </div>
  );
}

/** New engineering change order. */
export function ChangeDialog({ open, onClose, productId: presetProduct, presetType }: {
  open: boolean; onClose: () => void; productId?: string; presetType?: ChangeType;
}) {
  const navigate = useNavigate();
  const blank = {
    type: (presetType ?? 'bom') as ChangeType, title: '', description: '', reason: '', priority: 'normal' as Priority,
    productId: presetProduct ?? '', effectivity: 'asap' as Effectivity, effectiveDate: '',
    stockDisposition: 'use_up' as StockDisposition, wipDisposition: 'continue' as WipDisposition, newRevision: '', ownerId: '',
  };
  const [f, setF] = useState(blank);
  const [target, setTarget] = useState<Target>(blankTarget);
  useEffect(() => {
    if (open) { setF(blank); setTarget(blankTarget); }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open, presetProduct, presetType]);
  const create = useCreateChange();
  const set = <K extends keyof typeof f>(k: K, v: (typeof f)[K]) => setF((s) => ({ ...s, [k]: v }));

  const submit = () => {
    const err = targetError(f.type, target)
      ?? (f.type === 'product' && !f.productId ? tx('Pick a product.') : null)
      ?? (f.effectivity === 'date' && !f.effectiveDate ? tx('Pick the effective date.') : null);
    if (err) return notify.error(err);
    create.mutate({
      type: f.type,
      title: f.title.trim() || undefined,
      description: f.description.trim() || undefined,
      reason: f.reason.trim() || undefined,
      priority: f.priority,
      productId: f.productId || undefined,
      ...targetBody(f.type, target),
      effectivity: f.effectivity,
      effectiveDate: f.effectivity === 'date' ? f.effectiveDate : undefined,
      stockDisposition: f.stockDisposition,
      wipDisposition: f.wipDisposition,
      newRevision: f.newRevision.trim() || undefined,
      ownerId: f.ownerId || undefined,
    }, {
      onSuccess: (eco) => {
        notify.success(tx('{{n}} created', { n: eco.number }));
        onClose();
        navigate(`/manufacturing/plm/changes/${eco.id}`);
      },
    });
  };

  return (
    <Dialog open={open} onOpenChange={(o) => !o && onClose()}>
      <DialogContent className="max-h-[92vh] max-w-2xl overflow-y-auto">
        <DialogHeader>
          <DialogTitle>{tx('New change order')}</DialogTitle>
          <DialogDescription>{tx('An ECO moves through the approval stages, then is applied on its effective date.')}</DialogDescription>
        </DialogHeader>
        <div className="space-y-4">
          <TypeCards value={f.type} onChange={(t) => { set('type', t); setTarget(blankTarget); }} />
          <div className="grid gap-4 sm:grid-cols-2">
            <Field label={tx('Title')} className="sm:col-span-2" hint={tx('Leave empty for a generated title.')}>
              <Input value={f.title} maxLength={200} onChange={(e) => set('title', e.target.value)} />
            </Field>
            <Field label={tx('Product')} required={f.type === 'product' || f.type === 'bom'}>
              <ProductPicker value={f.productId} onChange={(v) => { set('productId', v); setTarget(blankTarget); }} clearable={f.type === 'document' || f.type === 'routing'} />
            </Field>
            <Field label={tx('Priority')}>
              <select className={selectClass} value={f.priority} onChange={(e) => set('priority', e.target.value as Priority)}>
                {PRIORITIES.map((p) => <option key={p} value={p}>{PRIORITY[p].label}</option>)}
              </select>
            </Field>
            <div className="sm:col-span-2">
              <ChangeTargetFields type={f.type} productId={f.productId} value={target} onChange={setTarget} />
            </div>
            <Field label={tx('Reason')} className="sm:col-span-2">
              <Input value={f.reason} maxLength={2000} onChange={(e) => set('reason', e.target.value)} placeholder={tx('Why is this change needed?')} />
            </Field>
            <Field label={tx('Description')} className="sm:col-span-2">
              <Textarea rows={3} maxLength={4000} value={f.description} onChange={(e) => set('description', e.target.value)} />
            </Field>
          </div>
          <EffectivityFields f={f} set={(k, v) => setF((s) => ({ ...s, [k]: v }))} />
          <div className="grid gap-4 sm:grid-cols-2">
            <Field label={tx('New revision')} hint={tx('Optional; otherwise the next revision (A → B).')}>
              <Input value={f.newRevision} maxLength={10} onChange={(e) => set('newRevision', e.target.value)} />
            </Field>
            <Field label={tx('Owner')}>
              <UserSelect value={f.ownerId} onChange={(v) => set('ownerId', v)} placeholder={tx('Me')} />
            </Field>
          </div>
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={onClose}>{tx('Cancel')}</Button>
          <Button onClick={submit} disabled={create.isPending}>{tx('Create change')}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

interface EffectivityValues {
  effectivity: Effectivity;
  effectiveDate: string;
  stockDisposition: StockDisposition;
  wipDisposition: WipDisposition;
}

function EffectivityFields<T extends EffectivityValues>({ f, set }: {
  f: T; set: (k: keyof EffectivityValues, v: string) => void;
}) {
  return (
    <div className="grid gap-4 rounded-lg border bg-muted/20 p-3 sm:grid-cols-2">
      <Field label={tx('Effective')}>
        <div className="flex gap-2">
          <select className={cn(selectClass, 'w-40')} value={f.effectivity} onChange={(e) => set('effectivity', e.target.value)}>
            <option value="asap">{tx('As soon as applied')}</option>
            <option value="date">{tx('On a date')}</option>
          </select>
          {f.effectivity === 'date' && (
            <Input type="date" value={f.effectiveDate} onChange={(e) => set('effectiveDate', e.target.value)} />
          )}
        </div>
      </Field>
      <div className="hidden sm:block" />
      <Field label={tx('Existing stock')}>
        <select className={selectClass} value={f.stockDisposition} onChange={(e) => set('stockDisposition', e.target.value)}>
          {(Object.keys(STOCK_DISPOSITION) as StockDisposition[]).map((k) => <option key={k} value={k}>{STOCK_DISPOSITION[k]}</option>)}
        </select>
      </Field>
      <Field label={tx('Work in progress')}>
        <select className={selectClass} value={f.wipDisposition} onChange={(e) => set('wipDisposition', e.target.value)}>
          {(Object.keys(WIP_DISPOSITION) as WipDisposition[]).map((k) => <option key={k} value={k}>{WIP_DISPOSITION[k]}</option>)}
        </select>
      </Field>
    </div>
  );
}

/** Edit a draft ECO. */
export function EditChangeDialog({ eco, open, onClose }: { eco: ChangeDetail; open: boolean; onClose: () => void }) {
  const init = () => ({
    title: eco.title ?? '', description: eco.description ?? '', reason: eco.reason ?? '', priority: eco.priority,
    effectivity: eco.effectivity ?? 'asap', effectiveDate: eco.effectiveDate ? String(eco.effectiveDate).slice(0, 10) : '',
    stockDisposition: eco.stockDisposition ?? 'use_up', wipDisposition: eco.wipDisposition ?? 'continue',
    newRevision: eco.newRevision ?? '', ownerId: eco.ownerId ?? '',
    targetLifecycleState: (eco.targetLifecycleState ?? '') as LifecycleState | '',
  });
  const [f, setF] = useState(init);
  useEffect(() => {
    if (open) setF(init());
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open, eco.id]);
  const update = useUpdateChange();
  const set = <K extends keyof typeof f>(k: K, v: (typeof f)[K]) => setF((s) => ({ ...s, [k]: v }));
  const from = eco.plmItem?.lifecycleState;
  const submit = () => {
    if (!f.title.trim()) return notify.error(tx('The title cannot be empty.'));
    if (f.effectivity === 'date' && !f.effectiveDate) return notify.error(tx('Pick the effective date.'));
    update.mutate({
      id: eco.id,
      body: {
        title: f.title.trim(),
        description: f.description,
        reason: f.reason,
        priority: f.priority,
        effectivity: f.effectivity,
        effectiveDate: f.effectivity === 'date' ? f.effectiveDate : undefined,
        stockDisposition: f.stockDisposition,
        wipDisposition: f.wipDisposition,
        newRevision: f.newRevision,
        ownerId: f.ownerId,
        targetLifecycleState: eco.type === 'product' && f.targetLifecycleState && f.targetLifecycleState !== eco.targetLifecycleState
          ? f.targetLifecycleState : undefined,
      },
    }, { onSuccess: () => { notify.success(tx('Change saved')); onClose(); } });
  };
  return (
    <Dialog open={open} onOpenChange={(o) => !o && onClose()}>
      <DialogContent className="max-h-[92vh] max-w-2xl overflow-y-auto">
        <DialogHeader>
          <DialogTitle>{tx('Edit {{n}}', { n: eco.number })}</DialogTitle>
        </DialogHeader>
        <div className="space-y-4">
          <div className="grid gap-4 sm:grid-cols-2">
            <Field label={tx('Title')} required className="sm:col-span-2">
              <Input value={f.title} maxLength={200} onChange={(e) => set('title', e.target.value)} />
            </Field>
            <Field label={tx('Priority')}>
              <select className={selectClass} value={f.priority} onChange={(e) => set('priority', e.target.value as Priority)}>
                {PRIORITIES.map((p) => <option key={p} value={p}>{PRIORITY[p].label}</option>)}
              </select>
            </Field>
            <Field label={tx('Owner')}>
              <UserSelect value={f.ownerId} onChange={(v) => set('ownerId', v)} />
            </Field>
            {eco.type === 'product' && from && (
              <Field label={tx('Target lifecycle state')} className="sm:col-span-2">
                <select className={selectClass} value={f.targetLifecycleState} onChange={(e) => set('targetLifecycleState', e.target.value as LifecycleState)}>
                  {TRANSITIONS[from].map((s) => <option key={s} value={s}>{LIFECYCLE[s].label}</option>)}
                  {f.targetLifecycleState && !TRANSITIONS[from].includes(f.targetLifecycleState as LifecycleState) && (
                    <option value={f.targetLifecycleState}>{LIFECYCLE[f.targetLifecycleState as LifecycleState]?.label}</option>
                  )}
                </select>
              </Field>
            )}
            <Field label={tx('Reason')} className="sm:col-span-2">
              <Input value={f.reason} maxLength={2000} onChange={(e) => set('reason', e.target.value)} />
            </Field>
            <Field label={tx('Description')} className="sm:col-span-2">
              <Textarea rows={3} maxLength={4000} value={f.description} onChange={(e) => set('description', e.target.value)} />
            </Field>
          </div>
          <EffectivityFields f={f} set={(k, v) => setF((s) => ({ ...s, [k]: v }))} />
          <Field label={tx('New revision')} hint={tx('Empty = next revision when applied.')}>
            <Input value={f.newRevision} maxLength={10} onChange={(e) => set('newRevision', e.target.value)} className="sm:w-40" />
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

/** Cancel (reject) an open ECO; its draft BOM / routing copy is discarded. */
export function CancelChangeDialog({ eco, open, onClose }: { eco: { id: string; number: string }; open: boolean; onClose: () => void }) {
  const [reason, setReason] = useState('');
  useEffect(() => { if (open) setReason(''); }, [open]);
  const cancel = useCancelChange();
  return (
    <Dialog open={open} onOpenChange={(o) => !o && onClose()}>
      <DialogContent className="max-w-md">
        <DialogHeader>
          <DialogTitle>{tx('Cancel {{n}}', { n: eco.number })}</DialogTitle>
          <DialogDescription>{tx('The draft BOM version or routing copy is discarded and attached draft documents are released from the change.')}</DialogDescription>
        </DialogHeader>
        <Field label={tx('Reason')}>
          <Textarea rows={3} maxLength={2000} value={reason} onChange={(e) => setReason(e.target.value)} />
        </Field>
        <DialogFooter>
          <Button variant="outline" onClick={onClose}>{tx('Keep it')}</Button>
          <Button
            variant="destructive"
            disabled={cancel.isPending}
            onClick={() => cancel.mutate({ id: eco.id, reason: reason.trim() || undefined }, {
              onSuccess: () => { notify.success(tx('{{n}} cancelled', { n: eco.number })); onClose(); },
            })}
          >
            {tx('Cancel change')}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

/** Turn a change request into an ECO. */
export function ConvertDialog({ request, onClose }: { request: ChangeRequest | null; onClose: () => void }) {
  const navigate = useNavigate();
  const [type, setType] = useState<ChangeType>('bom');
  const [title, setTitle] = useState('');
  const [target, setTarget] = useState<Target>(blankTarget);
  useEffect(() => {
    if (request) {
      setType(request.productId ? 'bom' : 'document');
      setTitle(request.title);
      setTarget({ ...blankTarget, bomId: request.bomId ?? '' });
    }
  }, [request]);
  const convert = useConvertRequest();
  const productId = request?.productId ?? '';
  const submit = () => {
    if (!request) return;
    const err = targetError(type, target) ?? (type === 'product' && !productId ? tx('The request has no product.') : null);
    if (err) return notify.error(err);
    convert.mutate({ id: request.id, body: { type, title: title.trim() || undefined, ...targetBody(type, target) } }, {
      onSuccess: (eco) => {
        notify.success(tx('{{n}} created from {{r}}', { n: eco.number, r: request.number }));
        onClose();
        navigate(`/manufacturing/plm/changes/${eco.id}`);
      },
    });
  };
  return (
    <Dialog open={!!request} onOpenChange={(o) => !o && onClose()}>
      <DialogContent className="max-h-[92vh] max-w-2xl overflow-y-auto">
        <DialogHeader>
          <DialogTitle>{tx('Convert {{r}} to a change order', { r: request?.number ?? '' })}</DialogTitle>
          <DialogDescription>
            {request?.product ? `${request.product.code} · ${request.product.name}` : tx('No product on the request')}
          </DialogDescription>
        </DialogHeader>
        <div className="space-y-4">
          <TypeCards value={type} onChange={(t) => { setType(t); setTarget({ ...blankTarget, bomId: t === 'bom' ? request?.bomId ?? '' : '' }); }} />
          <Field label={tx('Title')}>
            <Input value={title} maxLength={200} onChange={(e) => setTitle(e.target.value)} />
          </Field>
          <ChangeTargetFields type={type} productId={productId} value={target} onChange={setTarget} />
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={onClose}>{tx('Cancel')}</Button>
          <Button onClick={submit} disabled={convert.isPending}>{tx('Create change order')}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

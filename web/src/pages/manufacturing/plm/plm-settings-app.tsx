import { useEffect, useMemo, useState } from 'react';
import { Lock, Plus, Save, ShieldBan, Star, Tags, Trash2, Workflow } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Skeleton } from '@/components/ui/skeleton';
import { num, type CostPolicy, type LifecycleState } from '@/features/manufacturing/api-plm';
import {
  COST_POLICY, useChangeTypes, useCreateChangeType, useCreateTag, useDeleteChangeType, useDeleteTag, usePlmSettings,
  usePlmTags, useSavePlmSettings, useUpdateChangeType, useUpdateTag, type LifecyclePolicy, type PlmChangeType,
  type PlmTag, type PolicyActivity, type PolicyValue,
} from '@/features/manufacturing/api-plm-app';
import { notify } from '@/lib/notify';
import { cn } from '@/lib/utils';
import { tx } from '@/lib/i18n/tx';
import { Field, Panel } from './plm-ui';
import { ColorDot } from './plm-app-ui';
import { LIFECYCLE, selectClass } from './vocab';

/** PLM settings sections of the PLM application (ADR-S086): ECO types, tags, lifecycle policy and defaults. */

const PALETTE = ['#6366f1', '#0ea5e9', '#10b981', '#f59e0b', '#ef4444', '#8b5cf6', '#ec4899', '#64748b'];

function ColorInput({ value, onChange }: { value: string; onChange: (v: string) => void }) {
  return (
    <div className="flex items-center gap-1">
      {PALETTE.map((c) => (
        <button key={c} type="button" aria-label={c} onClick={() => onChange(c)}
          className={cn('h-5 w-5 rounded-full ring-offset-2 ring-offset-background', value === c && 'ring-2 ring-primary')}
          style={{ backgroundColor: c }} />
      ))}
    </div>
  );
}

// ---- ECO types --------------------------------------------------------------------------------------------------------

/** ECO types, each with its own stage pipeline; picking one shows its stages. */
export function ChangeTypesPanel({ selected, onSelect }: { selected: string; onSelect: (id: string) => void }) {
  const { data: types = [], isLoading } = useChangeTypes();
  const create = useCreateChangeType();
  const [draft, setDraft] = useState({ name: '', color: PALETTE[0] });
  useEffect(() => {
    if (!selected && types.length) onSelect((types.find((t) => t.isDefault) ?? types[0]).id);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [types, selected]);
  const add = () => {
    if (!draft.name.trim()) return notify.error(tx('Give the type a name.'));
    create.mutate({ name: draft.name.trim(), color: draft.color }, {
      onSuccess: (t) => { notify.success(tx('Type {{n}} added with a default pipeline', { n: t.name })); setDraft({ name: '', color: PALETTE[0] }); onSelect(t.id); },
    });
  };
  return (
    <Panel title={tx('ECO types')} icon={Workflow}>
      {isLoading ? <Skeleton className="h-24 w-full" /> : (
        <div className="space-y-1.5">
          {types.map((t) => <TypeRow key={t.id} type={t} active={t.id === selected} onSelect={() => onSelect(t.id)} />)}
        </div>
      )}
      <div className="mt-3 space-y-2 border-t pt-3">
        <Input value={draft.name} maxLength={80} placeholder={tx('New type, e.g. Packaging change')} onChange={(e) => setDraft({ ...draft, name: e.target.value })}
          onKeyDown={(e) => { if (e.key === 'Enter') add(); }} />
        <div className="flex items-center justify-between gap-2">
          <ColorInput value={draft.color} onChange={(c) => setDraft({ ...draft, color: c })} />
          <Button size="sm" onClick={add} disabled={create.isPending}><Plus className="mr-1 h-4 w-4" />{tx('Add')}</Button>
        </div>
      </div>
    </Panel>
  );
}

function TypeRow({ type, active, onSelect }: { type: PlmChangeType; active: boolean; onSelect: () => void }) {
  const update = useUpdateChangeType();
  const remove = useDeleteChangeType();
  const [editing, setEditing] = useState(false);
  const [name, setName] = useState(type.name);
  const [color, setColor] = useState(type.color ?? PALETTE[0]);
  useEffect(() => { setName(type.name); setColor(type.color ?? PALETTE[0]); }, [type.name, type.color]);
  const open = num(type.openChanges);
  return (
    <div className={cn('rounded-lg border px-2.5 py-2 text-sm transition', active ? 'border-primary bg-primary/5' : 'hover:bg-muted/50')}>
      <div className="flex cursor-pointer items-center gap-2" onClick={onSelect}>
        <ColorDot color={type.color} />
        <span className="min-w-0 flex-1 truncate font-medium">{type.name}</span>
        {type.isDefault && <Star className="h-3.5 w-3.5 fill-amber-400 text-amber-400" aria-label={tx('Default')} />}
        <span className="text-[11px] text-muted-foreground">{tx('{{n}} stages', { n: num(type.stageCount) })} · {tx('{{n}} open', { n: open })}</span>
      </div>
      {active && (
        editing ? (
          <div className="mt-2 space-y-2">
            <Input value={name} maxLength={80} onChange={(e) => setName(e.target.value)} />
            <ColorInput value={color} onChange={setColor} />
            <div className="flex gap-1">
              <Button size="sm" disabled={update.isPending} onClick={() => update.mutate({ id: type.id, body: { name: name.trim(), color } }, {
                onSuccess: () => { notify.success(tx('Type saved')); setEditing(false); },
              })}><Save className="mr-1 h-4 w-4" />{tx('Save')}</Button>
              <Button size="sm" variant="ghost" onClick={() => setEditing(false)}>{tx('Cancel')}</Button>
            </div>
          </div>
        ) : (
          <div className="mt-2 flex flex-wrap gap-1">
            <Button size="sm" variant="outline" onClick={() => setEditing(true)}>{tx('Rename')}</Button>
            {!type.isDefault && (
              <Button size="sm" variant="outline" disabled={update.isPending} onClick={() => update.mutate({ id: type.id, body: { isDefault: true } }, {
                onSuccess: () => notify.success(tx('{{n}} is now the default type', { n: type.name })),
              })}><Star className="mr-1 h-3.5 w-3.5" />{tx('Make default')}</Button>
            )}
            {!type.isDefault && (
              <Button size="sm" variant="ghost" disabled={open > 0 || remove.isPending}
                title={open > 0 ? tx('{{n}} open change(s) use this type', { n: open }) : tx('Delete type')}
                onClick={() => confirm(tx('Delete type {{n}} and its stages?', { n: type.name })) && remove.mutate(type.id, {
                  onSuccess: () => notify.success(tx('Type deleted')),
                })}><Trash2 className="h-4 w-4" /></Button>
            )}
          </div>
        )
      )}
    </div>
  );
}

// ---- tags -------------------------------------------------------------------------------------------------------------

export function TagsPanel() {
  const { data: tags = [], isLoading } = usePlmTags();
  const create = useCreateTag();
  const [draft, setDraft] = useState({ name: '', color: PALETTE[1] });
  const add = () => {
    if (!draft.name.trim()) return notify.error(tx('Give the tag a name.'));
    create.mutate({ name: draft.name.trim(), color: draft.color }, {
      onSuccess: () => { notify.success(tx('Tag added')); setDraft({ name: '', color: PALETTE[1] }); },
    });
  };
  return (
    <Panel title={tx('Tags')} icon={Tags}>
      {isLoading ? <Skeleton className="h-24 w-full" /> : (
        <div className="grid gap-2 md:grid-cols-2">
          {tags.map((t) => <TagRow key={t.id} tag={t} />)}
          {!tags.length && <p className="text-sm text-muted-foreground">{tx('No tags yet. Tags group change orders across types (e.g. Safety, Cost down, Customer request).')}</p>}
        </div>
      )}
      <div className="mt-3 flex flex-wrap items-end gap-2 border-t pt-3">
        <Field label={tx('New tag')}><Input value={draft.name} maxLength={40} onChange={(e) => setDraft({ ...draft, name: e.target.value })} className="w-56" /></Field>
        <ColorInput value={draft.color} onChange={(c) => setDraft({ ...draft, color: c })} />
        <Button onClick={add} disabled={create.isPending}><Plus className="mr-1 h-4 w-4" />{tx('Add')}</Button>
      </div>
    </Panel>
  );
}

function TagRow({ tag }: { tag: PlmTag }) {
  const update = useUpdateTag();
  const remove = useDeleteTag();
  const [name, setName] = useState(tag.name);
  const [color, setColor] = useState(tag.color ?? PALETTE[1]);
  useEffect(() => { setName(tag.name); setColor(tag.color ?? PALETTE[1]); }, [tag.name, tag.color]);
  const dirty = name.trim() !== tag.name || color !== (tag.color ?? PALETTE[1]);
  return (
    <div className="space-y-2 rounded-lg border p-2.5">
      <div className="flex items-center gap-2">
        <ColorDot color={color} />
        <Input value={name} maxLength={40} onChange={(e) => setName(e.target.value)} className="h-8" />
        <span className="whitespace-nowrap text-[11px] text-muted-foreground">{tx('{{n}} changes', { n: num(tag.changes) })}</span>
      </div>
      <div className="flex items-center justify-between gap-2">
        <ColorInput value={color} onChange={setColor} />
        <div className="flex gap-1">
          <Button size="sm" disabled={!dirty || update.isPending} onClick={() => update.mutate({ id: tag.id, body: { name: name.trim(), color } }, {
            onSuccess: () => notify.success(tx('Tag saved')),
          })}><Save className="h-4 w-4" /></Button>
          <Button size="sm" variant="ghost" disabled={remove.isPending} aria-label={tx('Delete tag')}
            onClick={() => confirm(tx('Delete tag {{n}}? It is removed from its change orders.', { n: tag.name })) && remove.mutate(tag.id, {
              onSuccess: () => notify.success(tx('Tag deleted')),
            })}><Trash2 className="h-4 w-4" /></Button>
        </div>
      </div>
    </div>
  );
}

// ---- lifecycle policy & defaults ----------------------------------------------------------------------------------------

const ACTIVITY_LABEL: Record<PolicyActivity, { label: string; hint: string }> = {
  purchase: { label: 'Purchase', hint: 'Purchase requests, RFQs and purchase orders' },
  sell: { label: 'Sell', hint: 'Sales quotations and sales orders (the POS till is not affected)' },
  replenish: { label: 'Replenish', hint: 'Reorder-rule suggestions and alert-driven requests' },
};

/** What purchasing, sales and replenishment may do with a product in each lifecycle state, plus ECO defaults. */
export function PolicyPanel() {
  const { data, isLoading } = usePlmSettings();
  const save = useSavePlmSettings();
  const [policy, setPolicy] = useState<LifecyclePolicy | null>(null);
  const [cost, setCost] = useState<CostPolicy>('none');
  const [fai, setFai] = useState(false);
  useEffect(() => {
    if (data) { setPolicy(data.lifecyclePolicy); setCost(data.defaultCostPolicy); setFai(data.firstArticleDefault); }
  }, [data]);
  const dirty = useMemo(() => !!data && !!policy && (JSON.stringify(policy) !== JSON.stringify(data.lifecyclePolicy)
    || cost !== data.defaultCostPolicy || fai !== data.firstArticleDefault), [data, policy, cost, fai]);
  if (isLoading || !data || !policy) return <Skeleton className="h-72 w-full rounded-xl" />;
  const toggle = (s: LifecycleState, a: PolicyActivity) => setPolicy({
    ...policy, [s]: { ...policy[s], [a]: policy[s][a] === 'allow' ? 'block' : 'allow' as PolicyValue },
  });
  const submit = () => save.mutate({ lifecyclePolicy: policy, defaultCostPolicy: cost, firstArticleDefault: fai }, {
    onSuccess: () => notify.success(tx('PLM settings saved')),
  });
  return (
    <div className="space-y-4">
      <Panel title={tx('Lifecycle rules')} icon={ShieldBan}>
        <p className="mb-3 text-sm text-muted-foreground">
          {tx('A blocked activity is refused with the product’s state and replacement; replenishment leaves the product out instead. A released product is always allowed.')}
        </p>
        <div className="overflow-x-auto">
          <table className="w-full min-w-[520px] text-sm">
            <thead>
              <tr className="text-left text-xs text-muted-foreground">
                <th className="py-2 pr-3 font-medium">{tx('Lifecycle state')}</th>
                {data.activities.map((a) => <th key={a} className="px-2 py-2 font-medium" title={tx(ACTIVITY_LABEL[a].hint)}>{tx(ACTIVITY_LABEL[a].label)}</th>)}
              </tr>
            </thead>
            <tbody>
              {data.states.map((s) => (
                <tr key={s} className="border-t">
                  <td className="py-2 pr-3 font-medium">{LIFECYCLE[s]?.label ?? s}</td>
                  {data.activities.map((a) => {
                    const v = policy[s][a];
                    const locked = s === 'released';
                    const changed = v !== data.defaults[s][a];
                    return (
                      <td key={a} className="px-2 py-1.5">
                        <button type="button" disabled={locked} onClick={() => toggle(s, a)}
                          className={cn('inline-flex min-w-[84px] items-center justify-center gap-1 rounded-md border px-2 py-1 text-xs font-medium transition',
                            v === 'allow' ? 'border-emerald-200 bg-emerald-50 text-emerald-800 dark:border-emerald-500/30 dark:bg-emerald-500/10 dark:text-emerald-300'
                              : 'border-rose-200 bg-rose-50 text-rose-800 dark:border-rose-500/30 dark:bg-rose-500/10 dark:text-rose-300',
                            locked ? 'cursor-not-allowed opacity-70' : 'hover:opacity-80')}>
                          {locked && <Lock className="h-3 w-3" />}{v === 'allow' ? tx('Allowed') : tx('Blocked')}
                        </button>
                        {changed && <span className="ml-1 text-[10px] text-muted-foreground">{tx('custom')}</span>}
                      </td>
                    );
                  })}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </Panel>
      <Panel title={tx('Defaults for new change orders')} icon={Workflow}>
        <div className="grid gap-4 md:grid-cols-2">
          <Field label={tx('Cost on apply (BOM changes)')} hint={tx(COST_POLICY[cost].hint) + ' ' + tx('Activate falls back to propose for products not on standard cost.')}>
            <select className={selectClass} value={cost} onChange={(e) => setCost(e.target.value as CostPolicy)}>
              {(Object.keys(COST_POLICY) as CostPolicy[]).map((p) => <option key={p} value={p}>{tx(COST_POLICY[p].label)}</option>)}
            </select>
          </Field>
          <Field label={tx('First-article inspection (BOM and routing changes)')}>
            <label className="flex h-10 items-center gap-2 text-sm">
              <input type="checkbox" className="h-4 w-4 accent-primary" checked={fai} onChange={(e) => setFai(e.target.checked)} />
              {tx('Inspect the first article made on a new version')}
            </label>
          </Field>
        </div>
      </Panel>
      <div className="flex justify-end">
        <Button onClick={submit} disabled={!dirty || save.isPending}><Save className="mr-1 h-4 w-4" />{tx('Save settings')}</Button>
      </div>
    </div>
  );
}

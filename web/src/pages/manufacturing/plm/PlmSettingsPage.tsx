import { useEffect, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { Plus, Save, Settings2, ShieldBan, ShieldCheck, Tags, Trash2, UserPlus, Workflow, X } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Skeleton } from '@/components/ui/skeleton';
import { ListPageHeader } from '@/components/list/list-page';
import {
  num, useCreateStage, useDeleteStage, useOrgUsers, usePlmStages, useSetStageApprovers, useUpdateStage, type ApproverKind, type PlmStage,
} from '@/features/manufacturing/api-plm';
import { notify } from '@/lib/notify';
import { cn } from '@/lib/utils';
import { tx } from '@/lib/i18n/tx';
import { Avatar, Field, Panel, PlmSubNav, TabBar, UserSelect } from './plm-ui';
import { ChangeTypesPanel, PolicyPanel, TagsPanel } from './plm-settings-app';

type Section = 'pipeline' | 'tags' | 'rules';

/**
 * PLM settings (ADR-S063 / ADR-S086): ECO types with their stage pipelines and approvers, tags, and the lifecycle
 * rules for purchasing / sales / replenishment with the defaults of new change orders.
 */
export function PlmSettingsPage() {
  const [params, setParams] = useSearchParams();
  const section = (params.get('section') as Section) || 'pipeline';
  const setSection = (v: Section) => {
    const next = new URLSearchParams(params);
    if (v === 'pipeline') next.delete('section'); else next.set('section', v);
    setParams(next, { replace: true });
  };
  const [typeId, setTypeId] = useState('');
  const { data: all = [], isLoading } = usePlmStages(typeId || undefined);
  const stages = all.filter((s) => !typeId || !s.typeId || s.typeId === typeId);
  const create = useCreateStage();
  const [draft, setDraft] = useState({ name: '', sequence: '', requiresApproval: false, description: '' });
  const add = () => {
    if (!draft.name.trim()) return notify.error(tx('Give the stage a name.'));
    create.mutate({
      name: draft.name.trim(),
      sequence: draft.sequence === '' ? undefined : Number(draft.sequence),
      requiresApproval: draft.requiresApproval,
      description: draft.description.trim() || undefined,
      typeId: typeId || undefined,
    }, {
      onSuccess: () => { notify.success(tx('Stage added')); setDraft({ name: '', sequence: '', requiresApproval: false, description: '' }); },
    });
  };

  return (
    <div className="space-y-4">
      <ListPageHeader
        title={tx('PLM settings')}
        description={tx('Each ECO type has its own stages. A stage that requires approval can only be left forwards once its approvers signed off; the last stage means approved.')}
        icon={Settings2}
      />
      <PlmSubNav />
      <TabBar tabs={[
        { id: 'pipeline' as const, label: tx('Types and stages'), icon: Workflow },
        { id: 'tags' as const, label: tx('Tags'), icon: Tags },
        { id: 'rules' as const, label: tx('Lifecycle rules and defaults'), icon: ShieldBan },
      ]} value={section} onChange={setSection} />

      {section === 'tags' && <TagsPanel />}
      {section === 'rules' && <PolicyPanel />}
      {section === 'pipeline' && (
      <div className="grid gap-4 lg:grid-cols-[300px_1fr]">
      <ChangeTypesPanel selected={typeId} onSelect={setTypeId} />
      <div className="min-w-0 space-y-3">
      {isLoading ? <Skeleton className="h-72 w-full rounded-xl" /> : (
        <div className="space-y-3">
          {stages.map((s, i) => <StageRow key={s.id} stage={s} index={i} final={i === stages.length - 1} />)}
        </div>
      )}

      <Panel title={tx('Add a stage')} icon={Plus}>
        <div className="grid gap-3 md:grid-cols-[1fr_120px_1.5fr_auto_auto] md:items-end">
          <Field label={tx('Name')} required>
            <Input value={draft.name} maxLength={80} onChange={(e) => setDraft({ ...draft, name: e.target.value })} placeholder={tx('e.g. Quality review')} />
          </Field>
          <Field label={tx('Sequence')}>
            <Input type="number" min={0} value={draft.sequence} onChange={(e) => setDraft({ ...draft, sequence: e.target.value })} placeholder={tx('auto')} />
          </Field>
          <Field label={tx('Description')}>
            <Input value={draft.description} maxLength={500} onChange={(e) => setDraft({ ...draft, description: e.target.value })} />
          </Field>
          <label className="flex h-10 items-center gap-2 text-sm">
            <input type="checkbox" className="h-4 w-4 accent-primary" checked={draft.requiresApproval} onChange={(e) => setDraft({ ...draft, requiresApproval: e.target.checked })} />
            {tx('Requires approval')}
          </label>
          <Button onClick={add} disabled={create.isPending}><Plus className="mr-1 h-4 w-4" />{tx('Add')}</Button>
        </div>
        <p className="mt-2 text-xs text-muted-foreground">{tx('Stages are ordered by sequence; without one the stage goes last (and becomes the approved stage).')}</p>
      </Panel>
      </div>
      </div>
      )}
    </div>
  );
}

function StageRow({ stage, index, final }: { stage: PlmStage; index: number; final: boolean }) {
  const init = () => ({
    name: stage.name, sequence: String(stage.sequence), requiresApproval: stage.requiresApproval, description: stage.description ?? '',
    folded: !!stage.folded,
  });
  const [f, setF] = useState(init);
  useEffect(() => setF(init()),
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [stage.id, stage.name, stage.sequence, stage.requiresApproval, stage.description, stage.folded]);
  const dirty = f.name !== stage.name || f.sequence !== String(stage.sequence) || f.requiresApproval !== stage.requiresApproval
    || f.description !== (stage.description ?? '') || f.folded !== !!stage.folded;
  const update = useUpdateStage();
  const remove = useDeleteStage();
  const open = num(stage.openChanges);

  const save = () => {
    if (!f.name.trim()) return notify.error(tx('Give the stage a name.'));
    update.mutate({
      id: stage.id,
      body: {
        name: f.name.trim(), sequence: f.sequence === '' ? undefined : Number(f.sequence), requiresApproval: f.requiresApproval,
        description: f.description, folded: f.folded,
      },
    }, { onSuccess: () => notify.success(tx('Stage saved')) });
  };

  return (
    <div className={cn('rounded-xl border bg-card shadow-sm', final && 'border-emerald-300 dark:border-emerald-500/40')}>
      <div className="grid gap-3 p-4 md:grid-cols-[40px_1fr_100px_1.5fr_auto_auto] md:items-end">
        <span className={cn('flex h-9 w-9 items-center justify-center rounded-full text-sm font-semibold',
          final ? 'bg-emerald-100 text-emerald-700 dark:bg-emerald-500/20 dark:text-emerald-300' : stage.requiresApproval ? 'bg-amber-100 text-amber-800 dark:bg-amber-500/20 dark:text-amber-300' : 'bg-muted')}>
          {index + 1}
        </span>
        <Field label={tx('Name')}>
          <Input value={f.name} maxLength={80} onChange={(e) => setF({ ...f, name: e.target.value })} />
        </Field>
        <Field label={tx('Sequence')}>
          <Input type="number" min={0} value={f.sequence} onChange={(e) => setF({ ...f, sequence: e.target.value })} />
        </Field>
        <Field label={tx('Description')}>
          <Input value={f.description} maxLength={500} onChange={(e) => setF({ ...f, description: e.target.value })} />
        </Field>
        <div className="flex flex-col gap-1">
          <label className="flex items-center gap-2 whitespace-nowrap text-sm">
            <input type="checkbox" className="h-4 w-4 accent-primary" checked={f.requiresApproval} onChange={(e) => setF({ ...f, requiresApproval: e.target.checked })} />
            {tx('Requires approval')}
          </label>
          <label className="flex items-center gap-2 whitespace-nowrap text-sm" title={tx('Shown collapsed on the board')}>
            <input type="checkbox" className="h-4 w-4 accent-primary" checked={f.folded} onChange={(e) => setF({ ...f, folded: e.target.checked })} />
            {tx('Folded on the board')}
          </label>
        </div>
        <div className="flex gap-1">
          <Button size="sm" onClick={save} disabled={!dirty || update.isPending}><Save className="mr-1 h-4 w-4" />{tx('Save')}</Button>
          <Button size="sm" variant="ghost" aria-label={tx('Delete stage')} disabled={open > 0 || remove.isPending}
            title={open > 0 ? tx('{{n}} open change(s) in this stage', { n: open }) : tx('Delete stage')}
            onClick={() => confirm(tx('Delete stage {{s}}?', { s: stage.name })) && remove.mutate(stage.id, { onSuccess: () => notify.success(tx('Stage deleted')) })}>
            <Trash2 className="h-4 w-4" />
          </Button>
        </div>
      </div>
      <div className="flex flex-wrap items-center gap-2 border-t px-4 py-2 text-xs text-muted-foreground">
        {final && <span className="rounded bg-emerald-100 px-1.5 py-0.5 font-medium text-emerald-700 dark:bg-emerald-500/20 dark:text-emerald-300">{tx('Final stage = approved')}</span>}
        {index === 0 && <span className="rounded bg-sky-100 px-1.5 py-0.5 font-medium text-sky-700 dark:bg-sky-500/20 dark:text-sky-300">{tx('New changes start here')}</span>}
        <span>{tx('{{n}} open change(s)', { n: open })}</span>
      </div>
      {(stage.requiresApproval || (stage.approvers?.length ?? 0) > 0) && <ApproversEditor stage={stage} />}
    </div>
  );
}

function ApproversEditor({ stage }: { stage: PlmStage }) {
  const init = () => (stage.approvers ?? []).map((a) => ({ userId: a.userId, userName: a.userName, kind: a.kind }));
  const [rows, setRows] = useState(init);
  const [adding, setAdding] = useState('');
  useEffect(() => setRows(init()),
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [stage.approvers]);
  const save = useSetStageApprovers();
  const original = JSON.stringify(init().map((r) => [r.userId, r.kind]));
  const dirty = JSON.stringify(rows.map((r) => [r.userId, r.kind])) !== original;

  return (
    <div className="border-t bg-muted/20 px-4 py-3">
      <div className="mb-2 flex items-center gap-2 text-sm font-medium">
        <ShieldCheck className="h-4 w-4 text-amber-600" />{tx('Approvers')}
        {!rows.length && <span className="text-xs font-normal text-muted-foreground">{tx('None named: anyone with the PLM approve permission can approve.')}</span>}
      </div>
      <div className="flex flex-wrap gap-2">
        {rows.map((r, i) => (
          <span key={r.userId} className="flex items-center gap-1.5 rounded-full border bg-background py-0.5 pl-0.5 pr-1.5 text-sm">
            <Avatar name={r.userName} />
            {r.userName ?? '?'}
            <select
              className="rounded bg-transparent text-xs text-muted-foreground outline-none"
              value={r.kind}
              onChange={(e) => setRows(rows.map((x, j) => (j === i ? { ...x, kind: e.target.value as ApproverKind } : x)))}
            >
              <option value="required">{tx('required')}</option>
              <option value="optional">{tx('optional')}</option>
              <option value="comment">{tx('comment only')}</option>
            </select>
            <button type="button" className="text-muted-foreground hover:text-foreground" aria-label={tx('Remove')} onClick={() => setRows(rows.filter((_, j) => j !== i))}>
              <X className="h-3.5 w-3.5" />
            </button>
          </span>
        ))}
      </div>
      <div className="mt-2 flex flex-wrap items-center gap-2">
        <div className="w-60">
          <AddApprover exclude={rows.map((r) => r.userId)} value={adding} onPick={(id, name) => { setRows([...rows, { userId: id, userName: name, kind: 'required' }]); setAdding(''); }} />
        </div>
        <Button size="sm" variant={dirty ? 'default' : 'outline'} disabled={!dirty || save.isPending}
          onClick={() => save.mutate({ id: stage.id, approvers: rows.map((r) => ({ userId: r.userId, kind: r.kind })) }, {
            onSuccess: () => notify.success(tx('Approvers saved')),
          })}>
          <Save className="mr-1 h-4 w-4" />{tx('Save approvers')}
        </Button>
      </div>
    </div>
  );
}

function AddApprover({ exclude, value, onPick }: { exclude: string[]; value: string; onPick: (id: string, name: string) => void }) {
  const { data: users = [] } = useOrgUsers();
  return (
    <div className="relative [&_select]:pl-8">
      <UserPlus className="pointer-events-none absolute left-2 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" />
      <UserSelect
        value={value}
        exclude={exclude}
        placeholder={tx('Add approver…')}
        onChange={(id) => { if (id) onPick(id, users.find((u) => u.id === id)?.name ?? id); }}
      />
    </div>
  );
}

import { useEffect, useState } from 'react';
import { ArrowDown, ArrowUp, Plus, Trash2, X } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Textarea } from '@/components/ui/textarea';
import { tx } from '@/lib/i18n/tx';
import { num, useAssignableUsers, usePmMutation, type MemberRole, type StageKind, type Workspace } from '@/features/projects/api';
import { Avatar, ColorPicker, MultiPicker, selectClass } from '@/features/projects/ui';

export function SettingsTab({ ws }: { ws: Workspace }) {
  return (
    <div className="grid gap-4 xl:grid-cols-2">
      <General ws={ws} />
      <div className="space-y-4">
        <Members ws={ws} />
        <Stages ws={ws} />
      </div>
    </div>
  );
}

function General({ ws }: { ws: Workspace }) {
  const { data: users = [] } = useAssignableUsers();
  const init = () => ({
    name: ws.name, description: ws.description ?? '', managerId: ws.managerId ?? '', privacy: ws.privacy,
    color: ws.color, startDate: ws.startDate ?? '', endDate: ws.endDate ?? '', plannedHours: String(num(ws.plannedHours) || ''),
    allowTimesheets: ws.allowTimesheets, allowMilestones: ws.allowMilestones, allowDependencies: ws.allowDependencies,
    allowRecurring: ws.allowRecurring, isTemplate: ws.isTemplate,
  });
  const [f, setF] = useState(init);
  // eslint-disable-next-line react-hooks/exhaustive-deps
  useEffect(() => setF(init()), [ws]);
  const save = usePmMutation(() => ({
    method: 'patch',
    url: `/projects/${ws.id}/workspace`,
    body: {
      name: f.name.trim(), description: f.description, managerId: f.managerId || undefined, privacy: f.privacy,
      color: f.color, startDate: f.startDate || null, endDate: f.endDate || null,
      plannedHours: f.plannedHours ? Number(f.plannedHours) : 0, allowTimesheets: f.allowTimesheets,
      allowMilestones: f.allowMilestones, allowDependencies: f.allowDependencies, allowRecurring: f.allowRecurring,
      isTemplate: f.isTemplate,
    },
  }), { success: tx('Project saved') });
  const set = <K extends keyof typeof f>(k: K, v: (typeof f)[K]) => setF((s) => ({ ...s, [k]: v }));

  return (
    <form className="space-y-4 rounded-xl border bg-card p-4 shadow-sm" onSubmit={(e) => { e.preventDefault(); save.mutate(); }}>
      <h3 className="text-sm font-semibold">{tx('General')}</h3>
      <div className="space-y-1.5"><Label>{tx('Name')}</Label><Input value={f.name} onChange={(e) => set('name', e.target.value)} required /></div>
      <div className="space-y-1.5"><Label>{tx('Description')}</Label><Textarea rows={3} value={f.description} onChange={(e) => set('description', e.target.value)} /></div>
      <div className="grid gap-3 sm:grid-cols-2">
        <div className="space-y-1.5">
          <Label>{tx('Project manager')}</Label>
          <select className={selectClass} value={f.managerId} onChange={(e) => set('managerId', e.target.value)}>
            {users.map((u) => <option key={u.id} value={u.id}>{u.name || u.email}</option>)}
          </select>
        </div>
        <div className="space-y-1.5">
          <Label>{tx('Visibility')}</Label>
          <select className={selectClass} value={f.privacy} onChange={(e) => set('privacy', e.target.value as typeof f.privacy)}>
            <option value="internal">{tx('Everyone with project access')}</option>
            <option value="members">{tx('Members only')}</option>
          </select>
        </div>
        <div className="space-y-1.5"><Label>{tx('Start date')}</Label><Input type="date" value={f.startDate} onChange={(e) => set('startDate', e.target.value)} /></div>
        <div className="space-y-1.5"><Label>{tx('End date')}</Label><Input type="date" value={f.endDate} onChange={(e) => set('endDate', e.target.value)} /></div>
        <div className="space-y-1.5"><Label>{tx('Planned hours')}</Label><Input type="number" min={0} value={f.plannedHours} onChange={(e) => set('plannedHours', e.target.value)} /></div>
      </div>
      <div className="space-y-1.5"><Label>{tx('Colour')}</Label><ColorPicker value={f.color} onChange={(v) => set('color', v)} /></div>
      <div className="grid gap-2 sm:grid-cols-2">
        {([
          ['allowTimesheets', tx('Timesheets')], ['allowMilestones', tx('Milestones')],
          ['allowDependencies', tx('Task dependencies')], ['allowRecurring', tx('Recurring tasks')],
          ['isTemplate', tx('Template')],
        ] as const).map(([k, label]) => (
          <label key={k} className="flex items-center gap-2 text-sm">
            <input type="checkbox" className="h-4 w-4 accent-primary" checked={f[k]} onChange={(e) => set(k, e.target.checked)} />{label}
          </label>
        ))}
      </div>
      <div className="flex justify-end"><Button type="submit" disabled={save.isPending || !f.name.trim()}>{tx('Save changes')}</Button></div>
    </form>
  );
}

function Members({ ws }: { ws: Workspace }) {
  const { data: users = [] } = useAssignableUsers();
  const save = usePmMutation((members: Array<{ userId: string; role: MemberRole }>) => ({ method: 'put', url: `/projects/${ws.id}/members`, body: { members } }));
  const current = ws.members.map((m) => ({ userId: m.userId, role: m.role }));
  return (
    <section className="space-y-3 rounded-xl border bg-card p-4 shadow-sm">
      <div className="flex items-center justify-between">
        <h3 className="text-sm font-semibold">{tx('Members')}</h3>
        <MultiPicker
          options={users.filter((u) => !ws.members.some((m) => m.userId === u.id))}
          value={[]}
          placeholder=""
          onChange={(ids) => save.mutate([...current, ...ids.map((id) => ({ userId: id, role: 'member' as MemberRole }))])}
          renderOption={(u) => <span className="flex items-center gap-2"><Avatar user={u} />{u.name}</span>}
          trigger={<Button size="sm" variant="outline"><Plus className="mr-1 h-4 w-4" />{tx('Add people')}</Button>}
        />
      </div>
      <ul className="divide-y">
        {ws.members.map((m) => (
          <li key={m.userId} className="flex items-center gap-3 py-2">
            <Avatar user={{ id: m.userId, name: m.name }} size="md" />
            <div className="min-w-0 flex-1">
              <p className="truncate text-sm font-medium">{m.name || m.email}</p>
              <p className="truncate text-xs text-muted-foreground">{m.email}</p>
            </div>
            {m.userId === ws.managerId ? (
              <span className="text-xs font-medium text-primary">{tx('Manager')}</span>
            ) : (
              <>
                <select
                  className={`${selectClass} h-8 w-28`}
                  value={m.role}
                  onChange={(e) => save.mutate(current.map((c) => (c.userId === m.userId ? { ...c, role: e.target.value as MemberRole } : c)))}
                >
                  <option value="member">{tx('Member')}</option>
                  <option value="viewer">{tx('Viewer')}</option>
                  <option value="manager">{tx('Co-manager')}</option>
                </select>
                <button type="button" onClick={() => save.mutate(current.filter((c) => c.userId !== m.userId))} aria-label={tx('Remove')}>
                  <X className="h-4 w-4 text-muted-foreground hover:text-rose-600" />
                </button>
              </>
            )}
          </li>
        ))}
      </ul>
      <p className="text-xs text-muted-foreground">{tx('Viewers can see the project but not change tasks. Members-only projects are hidden from everyone else.')}</p>
    </section>
  );
}

function Stages({ ws }: { ws: Workspace }) {
  const [name, setName] = useState('');
  const add = usePmMutation(() => ({ method: 'post', url: `/projects/${ws.id}/stages`, body: { name: name.trim(), kind: 'open' } }));
  const update = usePmMutation((v: { id: string; body: { name?: string; kind?: StageKind; folded?: boolean } }) => ({ method: 'patch', url: `/projects/stages/${v.id}`, body: v.body }));
  const remove = usePmMutation((id: string) => ({ method: 'delete', url: `/projects/stages/${id}` }));
  const reorder = usePmMutation((ids: string[]) => ({ method: 'post', url: `/projects/${ws.id}/stages/reorder`, body: { stageIds: ids } }));
  const ids = ws.stages.map((s) => s.id);
  const swap = (i: number, j: number) => { const n = [...ids]; [n[i], n[j]] = [n[j], n[i]]; reorder.mutate(n); };
  return (
    <section className="space-y-3 rounded-xl border bg-card p-4 shadow-sm">
      <h3 className="text-sm font-semibold">{tx('Task stages')}</h3>
      <ul className="space-y-1.5">
        {ws.stages.map((s, i) => (
          <li key={s.id} className="flex items-center gap-2">
            <div className="flex flex-col">
              <button type="button" disabled={i === 0} onClick={() => swap(i, i - 1)} className="disabled:opacity-20" aria-label={tx('Move up')}><ArrowUp className="h-3 w-3" /></button>
              <button type="button" disabled={i === ws.stages.length - 1} onClick={() => swap(i, i + 1)} className="disabled:opacity-20" aria-label={tx('Move down')}><ArrowDown className="h-3 w-3" /></button>
            </div>
            <Input
              className="h-8 flex-1"
              defaultValue={s.name}
              key={`${s.id}-${s.name}`}
              onBlur={(e) => e.target.value.trim() && e.target.value !== s.name && update.mutate({ id: s.id, body: { name: e.target.value.trim() } })}
            />
            <select className={`${selectClass} h-8 w-32`} value={s.kind} onChange={(e) => update.mutate({ id: s.id, body: { kind: e.target.value as StageKind } })}>
              <option value="open">{tx('In progress')}</option>
              <option value="done">{tx('Closes as done')}</option>
              <option value="cancelled">{tx('Closes as cancelled')}</option>
            </select>
            <label className="flex items-center gap-1 text-xs text-muted-foreground" title={tx('Folded stages show as a narrow column on the board')}>
              <input type="checkbox" className="accent-primary" checked={s.folded} onChange={(e) => update.mutate({ id: s.id, body: { folded: e.target.checked } })} />{tx('Fold')}
            </label>
            <button type="button" onClick={() => remove.mutate(s.id)} aria-label={tx('Delete stage')}>
              <Trash2 className="h-4 w-4 text-muted-foreground hover:text-rose-600" />
            </button>
          </li>
        ))}
      </ul>
      <form className="flex gap-2" onSubmit={(e) => { e.preventDefault(); if (name.trim()) add.mutate(undefined, { onSuccess: () => setName('') }); }}>
        <Input className="h-8" value={name} onChange={(e) => setName(e.target.value)} placeholder={tx('New stage name')} />
        <Button size="sm" type="submit" variant="secondary" disabled={!name.trim()}>{tx('Add stage')}</Button>
      </form>
      <p className="text-xs text-muted-foreground">{tx('A stage that still holds tasks cannot be deleted; move them first.')}</p>
    </section>
  );
}

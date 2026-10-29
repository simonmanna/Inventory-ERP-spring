import { useEffect, useState } from 'react';
import { toast } from 'sonner';
import { DndContext, PointerSensor, closestCenter, useSensor, useSensors, type DragEndEvent } from '@dnd-kit/core';
import { SortableContext, arrayMove, useSortable, verticalListSortingStrategy } from '@dnd-kit/sortable';
import { CSS } from '@dnd-kit/utilities';
import { GripVertical, Pencil, Plus, Settings2, Trash2, Trophy } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Textarea } from '@/components/ui/textarea';
import { Skeleton } from '@/components/ui/skeleton';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs';
import { Dialog, DialogContent, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { ListPageHeader } from '@/components/list';
import { OptionalSelect } from '@/pages/fleet/fleet-ui';
import { apiErrorMessage } from '@/lib/api-error';
import { money } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';
import { cn } from '@/lib/utils';
import {
  COLORS, crmApi, useCrmLookups, useCrmMutation, type Color, type CrmSettings, type Stage, type Team,
} from '@/features/crm/suite-api';
import { Field, colorOf } from './crm-ui';

const onErr = (m: string) => (e: unknown) => toast.error(apiErrorMessage(e, m));

/** CRM configuration: pipeline stages (drag to reorder), sales teams, tags, sources, lost reasons, policies. */
export function CrmSettingsPage() {
  const { data: lk, isLoading } = useCrmLookups();
  if (isLoading || !lk) return <Skeleton className="h-96 w-full" />;
  return (
    <div className="space-y-4">
      <ListPageHeader icon={Settings2} title={tx('CRM configuration')} description={tx('Shape the pipeline to how your team sells.')} />
      <Tabs defaultValue="stages">
        <TabsList className="flex-wrap">
          <TabsTrigger value="stages">{tx('Stages')}</TabsTrigger>
          <TabsTrigger value="teams">{tx('Sales teams')}</TabsTrigger>
          <TabsTrigger value="tags">{tx('Tags')}</TabsTrigger>
          <TabsTrigger value="sources">{tx('Sources')}</TabsTrigger>
          <TabsTrigger value="lost">{tx('Lost reasons')}</TabsTrigger>
          <TabsTrigger value="settings">{tx('Policies')}</TabsTrigger>
        </TabsList>
        <TabsContent value="stages"><StagesTab stages={lk.stages} teams={lk.teams} /></TabsContent>
        <TabsContent value="teams"><TeamsTab teams={lk.teams} users={lk.users} /></TabsContent>
        <TabsContent value="tags"><LookupTab kind="tags" rows={lk.tags.map((t) => ({ ...t, isActive: true }))} withColor /></TabsContent>
        <TabsContent value="sources"><LookupTab kind="sources" rows={lk.sources} /></TabsContent>
        <TabsContent value="lost"><LookupTab kind="lost-reasons" rows={lk.lostReasons} /></TabsContent>
        <TabsContent value="settings"><PolicyTab settings={lk.settings} users={lk.users} /></TabsContent>
      </Tabs>
    </div>
  );
}

function ColorPicker({ value, onChange }: { value: Color; onChange: (c: Color) => void }) {
  return (
    <div className="flex flex-wrap gap-1.5">
      {COLORS.map((c) => (
        <button key={c} type="button" title={c} onClick={() => onChange(c)} className={cn('h-6 w-6 rounded-full ring-offset-2', colorOf(c).dot, value === c && 'ring-2 ring-primary')} />
      ))}
    </div>
  );
}

// ------------------------------------------------------------------ stages

function StagesTab({ stages, teams }: { stages: Stage[]; teams: Team[] }) {
  const [order, setOrder] = useState(stages);
  const [editing, setEditing] = useState<Partial<Stage> | null>(null);
  useEffect(() => setOrder(stages), [stages]);
  const reorder = useCrmMutation(crmApi.reorderStages);
  const del = useCrmMutation(crmApi.deleteStage);
  const sensors = useSensors(useSensor(PointerSensor, { activationConstraint: { distance: 4 } }));
  const onDragEnd = (e: DragEndEvent) => {
    if (!e.over || e.active.id === e.over.id) return;
    const next = arrayMove(order, order.findIndex((s) => s.id === e.active.id), order.findIndex((s) => s.id === e.over!.id));
    setOrder(next);
    reorder.mutate(next.map((s) => s.id), { onError: onErr(tx('Could not reorder')) });
  };
  return (
    <div className="space-y-3">
      <div className="flex items-center justify-between">
        <p className="text-sm text-muted-foreground">{tx('Drag to reorder. Each stage carries a default win probability; the won stage closes the deal.')}</p>
        <Button onClick={() => setEditing({ probability: 20, color: 'sky', isActive: true, legacyStage: 'qualified' })}><Plus className="mr-1 h-4 w-4" />{tx('Add stage')}</Button>
      </div>
      <DndContext sensors={sensors} collisionDetection={closestCenter} onDragEnd={onDragEnd}>
        <SortableContext items={order.map((s) => s.id)} strategy={verticalListSortingStrategy}>
          <div className="divide-y rounded-xl border bg-card shadow-sm">
            {order.map((s) => (
              <StageRow key={s.id} s={s} teamName={teams.find((t) => t.id === s.teamId)?.name} onEdit={() => setEditing(s)}
                onDelete={() => confirm(tx('Delete this stage?')) && del.mutate(s.id, { onError: onErr(tx('Could not delete')) })} />
            ))}
          </div>
        </SortableContext>
      </DndContext>
      {editing && <StageDialog stage={editing} teams={teams} onClose={() => setEditing(null)} />}
    </div>
  );
}

function StageRow({ s, teamName, onEdit, onDelete }: { s: Stage; teamName?: string; onEdit: () => void; onDelete: () => void }) {
  const { attributes, listeners, setNodeRef, transform, transition, isDragging } = useSortable({ id: s.id });
  return (
    <div ref={setNodeRef} style={{ transform: CSS.Transform.toString(transform), transition }} className={cn('flex items-center gap-3 bg-card px-3 py-2.5', isDragging && 'relative z-10 shadow-lg', !s.isActive && 'opacity-50')}>
      <button {...attributes} {...listeners} className="cursor-grab text-muted-foreground" aria-label={tx('Drag')}><GripVertical className="h-4 w-4" /></button>
      <span className={cn('h-3 w-3 rounded-full', colorOf(s.color).dot)} />
      <div className="min-w-0 flex-1">
        <p className="flex items-center gap-1.5 font-medium">{s.name}{s.isWon && <Trophy className="h-3.5 w-3.5 text-emerald-600" />}{s.fold && <span className="text-xs text-muted-foreground">({tx('folded')})</span>}</p>
        <p className="text-xs text-muted-foreground">{Number(s.probability)}% · {s.openCount ?? 0} {tx('open')}{teamName ? ` · ${teamName}` : ''}{s.rottingDays ? ` · ${tx('rots after')} ${s.rottingDays}d` : ''}</p>
      </div>
      <Button size="sm" variant="ghost" onClick={onEdit}><Pencil className="h-4 w-4" /></Button>
      <Button size="sm" variant="ghost" className="text-destructive" onClick={onDelete}><Trash2 className="h-4 w-4" /></Button>
    </div>
  );
}

const LEGACY = [
  { value: 'lead', label: tx('Lead / new') }, { value: 'qualified', label: tx('Qualified') }, { value: 'proposal', label: tx('Proposal') },
  { value: 'negotiation', label: tx('Negotiation') },
];

function StageDialog({ stage, teams, onClose }: { stage: Partial<Stage>; teams: Team[]; onClose: () => void }) {
  const [s, setS] = useState(stage);
  const set = (p: Partial<Stage>) => setS((x) => ({ ...x, ...p }));
  const save = useCrmMutation(async () => {
    const body: Partial<Stage> = { name: s.name, probability: Number(s.probability ?? 0), color: s.color, isWon: !!s.isWon, fold: !!s.fold, isActive: s.isActive ?? true,
      requirements: s.requirements ?? null, teamId: s.teamId ?? null, rottingDays: s.rottingDays ? Number(s.rottingDays) : null, legacyStage: s.isWon ? 'won' : s.legacyStage };
    return s.id ? crmApi.updateStage({ id: s.id, ...body }) : crmApi.createStage(body);
  });
  return (
    <Dialog open onOpenChange={(o) => !o && onClose()}>
      <DialogContent className="max-w-lg">
        <DialogHeader><DialogTitle>{s.id ? tx('Edit stage') : tx('New stage')}</DialogTitle></DialogHeader>
        <div className="grid gap-3 sm:grid-cols-2">
          <Field label={tx('Name')} className="sm:col-span-2"><Input autoFocus value={s.name ?? ''} onChange={(e) => set({ name: e.target.value })} /></Field>
          <Field label={tx('Probability (%)')}><Input type="number" min={0} max={100} value={s.probability ?? 0} onChange={(e) => set({ probability: Number(e.target.value) })} /></Field>
          <Field label={tx('Rotting after (days)')} hint={tx('Empty = organization default')}><Input type="number" min={1} value={s.rottingDays ?? ''} onChange={(e) => set({ rottingDays: e.target.value ? Number(e.target.value) : null })} /></Field>
          <Field label={tx('Colour')} className="sm:col-span-2"><ColorPicker value={(s.color ?? 'sky') as Color} onChange={(c) => set({ color: c })} /></Field>
          {!s.isWon && (
            <Field label={tx('Reporting category')} hint={tx('Used by older screens and the mobile app')}>
              <OptionalSelect value={s.legacyStage ?? 'qualified'} onChange={(v) => set({ legacyStage: v || 'qualified' })} options={LEGACY} placeholder="" />
            </Field>
          )}
          <Field label={tx('Sales team')}>
            <OptionalSelect value={s.teamId ?? ''} onChange={(v) => set({ teamId: v || null })} options={teams.map((t) => ({ value: t.id, label: t.name }))} placeholder={tx('All teams')} noneLabel={tx('All teams')} />
          </Field>
          <Field label={tx('Requirements')} className="sm:col-span-2" hint={tx('Shown as a tooltip on the column')}><Textarea rows={2} value={s.requirements ?? ''} onChange={(e) => set({ requirements: e.target.value })} /></Field>
          <label className="flex items-center gap-2 text-sm"><input type="checkbox" checked={!!s.isWon} onChange={(e) => set({ isWon: e.target.checked, probability: e.target.checked ? 100 : s.probability })} />{tx('Won stage')}</label>
          <label className="flex items-center gap-2 text-sm"><input type="checkbox" checked={!!s.fold} onChange={(e) => set({ fold: e.target.checked })} />{tx('Folded in kanban')}</label>
          <label className="flex items-center gap-2 text-sm"><input type="checkbox" checked={s.isActive ?? true} onChange={(e) => set({ isActive: e.target.checked })} />{tx('Active')}</label>
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={onClose}>{tx('Cancel')}</Button>
          <Button disabled={!s.name || save.isPending} onClick={() => save.mutate(undefined, { onSuccess: () => { toast.success(tx('Saved')); onClose(); }, onError: onErr(tx('Could not save')) })}>{tx('Save')}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

// ------------------------------------------------------------------ teams

function TeamsTab({ teams, users }: { teams: Team[]; users: Array<{ id: string; name: string }> }) {
  const [editing, setEditing] = useState<Partial<Team> | null>(null);
  const del = useCrmMutation(crmApi.deleteTeam);
  const name = (id: string | null) => users.find((u) => u.id === id)?.name ?? '—';
  return (
    <div className="space-y-3">
      <div className="flex justify-end"><Button onClick={() => setEditing({ color: 'sky', isActive: true, memberIds: [], monthlyTarget: 0 })}><Plus className="mr-1 h-4 w-4" />{tx('Add team')}</Button></div>
      <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
        {teams.map((t) => (
          <div key={t.id} className={cn('rounded-xl border bg-card p-4 shadow-sm', !t.isActive && 'opacity-60')}>
            <div className="flex items-center justify-between">
              <p className="flex items-center gap-2 font-semibold"><span className={cn('h-3 w-3 rounded-full', colorOf(t.color).dot)} />{t.name}</p>
              <div><Button size="sm" variant="ghost" onClick={() => setEditing(t)}><Pencil className="h-4 w-4" /></Button>
                <Button size="sm" variant="ghost" className="text-destructive" onClick={() => confirm(tx('Delete this team?')) && del.mutate(t.id)}><Trash2 className="h-4 w-4" /></Button></div>
            </div>
            <p className="mt-1 text-sm text-muted-foreground">{tx('Leader')}: {name(t.leaderId)}</p>
            <p className="text-sm text-muted-foreground">{t.memberIds.length} {tx('members')} · {tx('Target')} {money(t.monthlyTarget)}/{tx('month')}</p>
          </div>
        ))}
        {teams.length === 0 && <p className="text-sm text-muted-foreground">{tx('No sales teams yet.')}</p>}
      </div>
      {editing && <TeamDialog team={editing} users={users} onClose={() => setEditing(null)} />}
    </div>
  );
}

function TeamDialog({ team, users, onClose }: { team: Partial<Team>; users: Array<{ id: string; name: string }>; onClose: () => void }) {
  const [t, setT] = useState(team);
  const save = useCrmMutation(crmApi.saveTeam);
  return (
    <Dialog open onOpenChange={(o) => !o && onClose()}>
      <DialogContent className="max-w-lg">
        <DialogHeader><DialogTitle>{t.id ? tx('Edit team') : tx('New team')}</DialogTitle></DialogHeader>
        <div className="space-y-3">
          <Field label={tx('Name')}><Input value={t.name ?? ''} onChange={(e) => setT({ ...t, name: e.target.value })} /></Field>
          <Field label={tx('Leader')}><OptionalSelect value={t.leaderId ?? ''} onChange={(v) => setT({ ...t, leaderId: v || null })} options={users.map((u) => ({ value: u.id, label: u.name }))} placeholder={tx('None')} noneLabel={tx('None')} /></Field>
          <Field label={tx('Members')}>
            <div className="flex max-h-40 flex-wrap gap-1.5 overflow-y-auto">
              {users.map((u) => {
                const on = (t.memberIds ?? []).includes(u.id);
                return <button key={u.id} type="button" onClick={() => setT({ ...t, memberIds: on ? t.memberIds!.filter((x) => x !== u.id) : [...(t.memberIds ?? []), u.id] })}
                  className={cn('rounded-full border px-2.5 py-0.5 text-xs', on ? 'border-primary bg-primary/10 text-primary' : 'hover:bg-muted')}>{u.name}</button>;
              })}
            </div>
          </Field>
          <Field label={tx('Monthly target')}><Input type="number" min={0} value={t.monthlyTarget ?? 0} onChange={(e) => setT({ ...t, monthlyTarget: Number(e.target.value) })} /></Field>
          <Field label={tx('Colour')}><ColorPicker value={(t.color ?? 'sky') as Color} onChange={(c) => setT({ ...t, color: c })} /></Field>
          <label className="flex items-center gap-2 text-sm"><input type="checkbox" checked={t.isActive ?? true} onChange={(e) => setT({ ...t, isActive: e.target.checked })} />{tx('Active')}</label>
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={onClose}>{tx('Cancel')}</Button>
          <Button disabled={!t.name || save.isPending} onClick={() => save.mutate({ id: t.id, name: t.name, leaderId: t.leaderId ?? null, memberIds: t.memberIds ?? [], monthlyTarget: t.monthlyTarget ?? 0, color: t.color, isActive: t.isActive ?? true },
            { onSuccess: () => { toast.success(tx('Saved')); onClose(); }, onError: onErr(tx('Could not save')) })}>{tx('Save')}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

// ------------------------------------------------------------------ simple lookups

function LookupTab({ kind, rows, withColor }: { kind: 'tags' | 'sources' | 'lost-reasons'; rows: Array<{ id: string; name: string; isActive: boolean; color?: Color }>; withColor?: boolean }) {
  const [name, setName] = useState('');
  const [color, setColor] = useState<Color>('sky');
  const save = useCrmMutation(crmApi.saveLookup);
  const del = useCrmMutation(crmApi.deleteLookup);
  const add = () => save.mutate({ kind, name, ...(withColor ? { color } : {}) }, { onSuccess: () => setName(''), onError: onErr(tx('Could not save')) });
  return (
    <div className="max-w-2xl space-y-3">
      <div className="flex flex-wrap items-end gap-2 rounded-xl border bg-card p-3 shadow-sm">
        <Field label={tx('Name')} className="min-w-[200px] flex-1"><Input value={name} onChange={(e) => setName(e.target.value)} onKeyDown={(e) => e.key === 'Enter' && name.trim() && add()} /></Field>
        {withColor && <Field label={tx('Colour')}><ColorPicker value={color} onChange={setColor} /></Field>}
        <Button disabled={!name.trim() || save.isPending} onClick={add}><Plus className="mr-1 h-4 w-4" />{tx('Add')}</Button>
      </div>
      <div className="divide-y rounded-xl border bg-card shadow-sm">
        {rows.map((r) => (
          <div key={r.id} className="flex items-center gap-3 px-3 py-2 text-sm">
            {withColor && <span className={cn('rounded-full px-2 py-0.5 text-xs', colorOf(r.color).chip)}>{r.name}</span>}
            {!withColor && <span className={cn('flex-1', !r.isActive && 'text-muted-foreground line-through')}>{r.name}</span>}
            {withColor && <span className="flex-1" />}
            {!withColor && (
              <label className="flex items-center gap-1.5 text-xs"><input type="checkbox" checked={r.isActive} onChange={(e) => save.mutate({ kind, id: r.id, isActive: e.target.checked })} />{tx('Active')}</label>
            )}
            <Button size="sm" variant="ghost" className="text-destructive" onClick={() => confirm(tx('Delete?')) && del.mutate({ kind, id: r.id }, { onError: onErr(tx('Could not delete')) })}><Trash2 className="h-4 w-4" /></Button>
          </div>
        ))}
        {rows.length === 0 && <p className="p-4 text-sm text-muted-foreground">{tx('Nothing yet.')}</p>}
      </div>
    </div>
  );
}

// ------------------------------------------------------------------ policies

function PolicyTab({ settings, users }: { settings: CrmSettings; users: Array<{ id: string; name: string }> }) {
  const [s, setS] = useState(settings);
  useEffect(() => setS(settings), [settings]);
  const save = useCrmMutation(crmApi.updateSettings);
  const set = (p: Partial<CrmSettings>) => setS((x) => ({ ...x, ...p }));
  return (
    <div className="max-w-3xl space-y-4">
      <section className="space-y-3 rounded-xl border bg-card p-4 shadow-sm">
        <h3 className="font-semibold">{tx('Quotation approval')}</h3>
        <div className="grid gap-3 sm:grid-cols-3">
          <Field label={tx('Max discount without approval (%)')}><Input type="number" min={0} max={100} value={s.quoteMaxDiscountPercent} onChange={(e) => set({ quoteMaxDiscountPercent: Number(e.target.value) })} /></Field>
          <Field label={tx('Approval above amount')} hint={tx('0 = no amount limit')}><Input type="number" min={0} value={s.quoteApprovalAmount} onChange={(e) => set({ quoteApprovalAmount: Number(e.target.value) })} /></Field>
          <Field label={tx('Quotation validity (days)')}><Input type="number" min={1} value={s.quoteValidityDays} onChange={(e) => set({ quoteValidityDays: Number(e.target.value) })} /></Field>
        </div>
        <label className="flex items-center gap-2 text-sm"><input type="checkbox" checked={s.creditCheck} onChange={(e) => set({ creditCheck: e.target.checked })} />{tx('Credit check: route quotes for customers on hold or over their credit limit to approval')}</label>
        <label className="flex items-center gap-2 text-sm"><input type="checkbox" checked={s.autoWinOnConversion} onChange={(e) => set({ autoWinOnConversion: e.target.checked })} />{tx('Mark the opportunity won when its quotation is converted')}</label>
        <Field label={tx('Default terms & conditions')}><Textarea rows={3} value={s.defaultQuoteTerms ?? ''} onChange={(e) => set({ defaultQuoteTerms: e.target.value })} /></Field>
      </section>
      <section className="space-y-3 rounded-xl border bg-card p-4 shadow-sm">
        <h3 className="font-semibold">{tx('Pipeline')}</h3>
        <div className="grid gap-3 sm:grid-cols-2">
          <Field label={tx('Rotting after (days without stage change)')}><Input type="number" min={1} value={s.rottingDays} onChange={(e) => set({ rottingDays: Number(e.target.value) })} /></Field>
          <Field label={tx('Lead assignment')}>
            <OptionalSelect value={s.assignmentMode} onChange={(v) => set({ assignmentMode: (v || 'manual') as CrmSettings['assignmentMode'] })}
              options={[{ value: 'manual', label: tx('Manual (creator)') }, { value: 'round_robin', label: tx('Round robin') }, { value: 'least_loaded', label: tx('Least open opportunities') }]} placeholder="" />
          </Field>
        </div>
        {s.assignmentMode !== 'manual' && (
          <Field label={tx('Assignment pool')}>
            <div className="flex flex-wrap gap-1.5">
              {users.map((u) => {
                const on = s.assignmentPool.includes(u.id);
                return <button key={u.id} type="button" onClick={() => set({ assignmentPool: on ? s.assignmentPool.filter((x) => x !== u.id) : [...s.assignmentPool, u.id] })}
                  className={cn('rounded-full border px-2.5 py-0.5 text-xs', on ? 'border-primary bg-primary/10 text-primary' : 'hover:bg-muted')}>{u.name}</button>;
              })}
            </div>
          </Field>
        )}
        <label className="flex items-center gap-2 text-sm"><input type="checkbox" checked={s.useLeads} onChange={(e) => set({ useLeads: e.target.checked })} />{tx('Use leads (qualify enquiries before the pipeline)')}</label>
      </section>
      <Button disabled={save.isPending} onClick={() => save.mutate({
        quoteMaxDiscountPercent: s.quoteMaxDiscountPercent, quoteApprovalAmount: s.quoteApprovalAmount, quoteValidityDays: s.quoteValidityDays,
        creditCheck: s.creditCheck, autoWinOnConversion: s.autoWinOnConversion, defaultQuoteTerms: s.defaultQuoteTerms, rottingDays: s.rottingDays,
        assignmentMode: s.assignmentMode, assignmentPool: s.assignmentPool, useLeads: s.useLeads,
      }, { onSuccess: () => toast.success(tx('Settings saved')), onError: onErr(tx('Could not save')) })}>{tx('Save settings')}</Button>
    </div>
  );
}

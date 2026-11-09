import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Dialog, DialogContent, DialogFooter, DialogHeader, DialogTitle, DialogDescription } from '@/components/ui/dialog';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Textarea } from '@/components/ui/textarea';
import { useAuthStore } from '@/stores/auth.store';
import { tx } from '@/lib/i18n/tx';
import { useAssignableUsers, useProjectBoard, usePmMutation, type Workspace } from './api';
import { Avatar, ColorPicker, MultiPicker, selectClass } from './ui';

interface Props {
  open: boolean;
  onClose: () => void;
  /** Duplicate an existing project instead of creating a blank one. */
  duplicateOf?: Workspace | null;
}

/** New project (blank or from a template) and "duplicate project". */
export function ProjectDialog({ open, onClose, duplicateOf }: Props) {
  const navigate = useNavigate();
  const me = useAuthStore((s) => s.user);
  const { data: users = [] } = useAssignableUsers();
  const { data: templates = [] } = useProjectBoard({ templates: true });
  const blank = {
    name: '', code: '', description: '', managerId: me?.id ?? '', memberIds: [] as string[], privacy: 'internal',
    color: 0, startDate: '', endDate: '', plannedHours: '', templateId: '', isTemplate: false,
    allowTimesheets: true, allowMilestones: true, allowDependencies: true, allowRecurring: true,
    includeTasks: true,
  };
  const [f, setF] = useState(blank);
  useEffect(() => {
    if (open) setF(duplicateOf ? { ...blank, name: `${duplicateOf.name} (${tx('copy')})` } : blank);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open, duplicateOf]);

  const create = usePmMutation<void, { id: string }>(() => ({
    method: 'post',
    url: '/projects/workspace',
    body: {
      name: f.name.trim(), code: f.code.trim() || undefined, description: f.description || undefined,
      managerId: f.managerId || undefined, memberIds: f.memberIds, privacy: f.privacy, color: f.color,
      startDate: f.startDate || undefined, endDate: f.endDate || undefined,
      plannedHours: f.plannedHours ? Number(f.plannedHours) : undefined, templateId: f.templateId || undefined,
      isTemplate: f.isTemplate, allowTimesheets: f.allowTimesheets, allowMilestones: f.allowMilestones,
      allowDependencies: f.allowDependencies, allowRecurring: f.allowRecurring,
    },
  }), { success: tx('Project created') });
  const duplicate = usePmMutation<void, { id: string }>(() => ({
    method: 'post',
    url: `/projects/${duplicateOf?.id}/duplicate`,
    body: {
      name: f.name.trim(), code: f.code.trim() || undefined, asTemplate: f.isTemplate,
      includeTasks: f.includeTasks, startDate: f.startDate || undefined,
    },
  }), { success: tx('Project duplicated') });

  const busy = create.isPending || duplicate.isPending;
  const submit = async () => {
    const res = duplicateOf ? await duplicate.mutateAsync() : await create.mutateAsync();
    onClose();
    if (res?.id) navigate(`/projects/${res.id}`);
  };
  const set = <K extends keyof typeof f>(k: K, v: (typeof f)[K]) => setF((s) => ({ ...s, [k]: v }));

  return (
    <Dialog open={open} onOpenChange={(o) => !o && onClose()}>
      <DialogContent className="max-h-[92vh] max-w-2xl overflow-y-auto">
        <DialogHeader>
          <DialogTitle>{duplicateOf ? tx('Duplicate project') : tx('New project')}</DialogTitle>
          <DialogDescription>
            {duplicateOf
              ? tx('Copies stages, milestones, members and (optionally) tasks. Dates shift to the new start date.')
              : tx('Projects get default stages (To Do, In Progress, Review, Done, Cancelled); change them any time.')}
          </DialogDescription>
        </DialogHeader>
        <form
          id="project-form"
          className="grid gap-4 sm:grid-cols-2"
          onSubmit={(e) => { e.preventDefault(); if (f.name.trim()) void submit(); }}
        >
          <div className="space-y-1.5 sm:col-span-2">
            <Label htmlFor="pname">{tx('Name')}</Label>
            <Input id="pname" autoFocus required value={f.name} onChange={(e) => set('name', e.target.value)} placeholder={tx('e.g. Office fit-out, Website relaunch')} />
          </div>
          <div className="space-y-1.5">
            <Label htmlFor="pcode">{tx('Code')}</Label>
            <Input id="pcode" value={f.code} onChange={(e) => set('code', e.target.value)} placeholder={tx('Automatic (PRJ-0001)')} />
          </div>
          <div className="space-y-1.5">
            <Label>{tx('Start date')}</Label>
            <Input type="date" value={f.startDate} onChange={(e) => set('startDate', e.target.value)} />
          </div>

          {!duplicateOf && (
            <>
              <div className="space-y-1.5">
                <Label>{tx('End date')}</Label>
                <Input type="date" value={f.endDate} min={f.startDate || undefined} onChange={(e) => set('endDate', e.target.value)} />
              </div>
              <div className="space-y-1.5">
                <Label>{tx('Planned hours')}</Label>
                <Input type="number" min={0} value={f.plannedHours} onChange={(e) => set('plannedHours', e.target.value)} />
              </div>
              <div className="space-y-1.5">
                <Label>{tx('Project manager')}</Label>
                <select className={selectClass} value={f.managerId} onChange={(e) => set('managerId', e.target.value)}>
                  {users.map((u) => <option key={u.id} value={u.id}>{u.name || u.email}</option>)}
                </select>
              </div>
              <div className="space-y-1.5">
                <Label>{tx('Members')}</Label>
                <MultiPicker
                  options={users.filter((u) => u.id !== f.managerId)}
                  value={f.memberIds}
                  onChange={(ids) => set('memberIds', ids)}
                  placeholder={tx('Add people')}
                  renderOption={(u) => <span className="flex items-center gap-2"><Avatar user={u} />{u.name}</span>}
                />
              </div>
              <div className="space-y-1.5">
                <Label>{tx('Visibility')}</Label>
                <select className={selectClass} value={f.privacy} onChange={(e) => set('privacy', e.target.value)}>
                  <option value="internal">{tx('Everyone with project access')}</option>
                  <option value="members">{tx('Members only')}</option>
                </select>
              </div>
              {templates.length > 0 && (
                <div className="space-y-1.5">
                  <Label>{tx('Start from template')}</Label>
                  <select className={selectClass} value={f.templateId} onChange={(e) => set('templateId', e.target.value)}>
                    <option value="">{tx('Blank project')}</option>
                    {templates.map((t) => <option key={t.id} value={t.id}>{t.code} · {t.name}</option>)}
                  </select>
                </div>
              )}
              <div className="space-y-1.5 sm:col-span-2">
                <Label>{tx('Description')}</Label>
                <Textarea rows={3} value={f.description} onChange={(e) => set('description', e.target.value)} />
              </div>
              <div className="space-y-1.5 sm:col-span-2">
                <Label>{tx('Colour')}</Label>
                <ColorPicker value={f.color} onChange={(v) => set('color', v)} />
              </div>
              <div className="grid gap-2 sm:col-span-2 sm:grid-cols-2">
                {([
                  ['allowTimesheets', tx('Timesheets')],
                  ['allowMilestones', tx('Milestones')],
                  ['allowDependencies', tx('Task dependencies')],
                  ['allowRecurring', tx('Recurring tasks')],
                ] as const).map(([k, label]) => (
                  <label key={k} className="flex items-center gap-2 text-sm">
                    <input type="checkbox" className="h-4 w-4 accent-primary" checked={f[k]} onChange={(e) => set(k, e.target.checked)} />
                    {label}
                  </label>
                ))}
              </div>
            </>
          )}
          {duplicateOf && (
            <label className="flex items-center gap-2 text-sm sm:col-span-2">
              <input type="checkbox" className="h-4 w-4 accent-primary" checked={f.includeTasks} onChange={(e) => set('includeTasks', e.target.checked)} />
              {tx('Copy tasks, subtasks and checklists')}
            </label>
          )}
          <label className="flex items-center gap-2 text-sm sm:col-span-2">
            <input type="checkbox" className="h-4 w-4 accent-primary" checked={f.isTemplate} onChange={(e) => set('isTemplate', e.target.checked)} />
            {tx('Save as a template (hidden from the board, reusable for new projects)')}
          </label>
        </form>
        <DialogFooter>
          <Button variant="outline" onClick={onClose}>{tx('Cancel')}</Button>
          <Button type="submit" form="project-form" disabled={!f.name.trim() || busy}>
            {duplicateOf ? tx('Duplicate') : tx('Create project')}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

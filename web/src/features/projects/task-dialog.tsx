import { useEffect, useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import {
  Bell, BellOff, CheckCircle2, Circle, Clock, GitBranch, Link2, ListChecks, Lock, MessageSquare, Play, Plus,
  Square, StickyNote, Trash2, X,
} from 'lucide-react';
import { Dialog, DialogContent, DialogTitle, DialogDescription } from '@/components/ui/dialog';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Textarea } from '@/components/ui/textarea';
import { Skeleton } from '@/components/ui/skeleton';
import { useAuthStore } from '@/stores/auth.store';
import { PERMISSIONS } from '@erp/shared';
import { relativeTime } from '@/lib/format';
import { cn } from '@/lib/utils';
import { tx } from '@/lib/i18n/tx';
import {
  num, useAssignableUsers, usePmMutation, useTags, useTask, useTaskMessages, useTasks, useTimer, useTimesheetLines, useWorkspace,
  type TaskDetail,
} from './api';
import { Avatar, MultiPicker, PRIORITY, TagChip, clock, hours, selectClass, todayIso } from './ui';

/** Task detail: every field editable in place, checklist, subtasks, dependencies, time and chatter. */
export function TaskDialog({ taskId, onClose, onOpenTask }: {
  taskId: string | null;
  onClose: () => void;
  onOpenTask: (id: string) => void;
}) {
  const { data: task, isLoading } = useTask(taskId);
  return (
    <Dialog open={!!taskId} onOpenChange={(o) => !o && onClose()}>
      <DialogContent className="max-h-[92vh] max-w-4xl overflow-y-auto p-0">
        {isLoading || !task ? (
          <div className="space-y-3 p-6">
            <DialogTitle className="sr-only">{tx('Task')}</DialogTitle>
            <Skeleton className="h-7 w-2/3" />
            <Skeleton className="h-40 w-full" />
          </div>
        ) : (
          <TaskBody task={task} onClose={onClose} onOpenTask={onOpenTask} />
        )}
      </DialogContent>
    </Dialog>
  );
}

function TaskBody({ task, onClose, onOpenTask }: { task: TaskDetail; onClose: () => void; onOpenTask: (id: string) => void }) {
  const can = useAuthStore((s) => s.hasPermission);
  const canLog = can(PERMISSIONS.projectTimesheet.log);
  const editable = task.canEdit;
  const { data: users = [] } = useAssignableUsers();
  const { data: tags = [] } = useTags();
  const { data: ws } = useWorkspace(task.projectId ?? undefined);
  const update = usePmMutation((body: Record<string, unknown>) => ({ method: 'patch', url: `/project-tasks/${task.id}`, body }));
  const done = usePmMutation((v: boolean) => ({ method: 'post', url: `/project-tasks/${task.id}/done`, body: { done: v } }));
  const follow = usePmMutation((v: boolean) => ({ method: 'post', url: `/project-tasks/${task.id}/follow`, body: { follow: v } }));
  const del = usePmMutation(() => ({ method: 'delete', url: `/project-tasks/${task.id}` }), { success: tx('Task deleted') });

  const [title, setTitle] = useState(task.title);
  const [description, setDescription] = useState(task.description ?? '');
  useEffect(() => { setTitle(task.title); setDescription(task.description ?? ''); }, [task.id, task.title, task.description]);

  const save = (body: Record<string, unknown>) => update.mutate(body);

  return (
    <div className="grid md:grid-cols-[1fr_280px]">
      {/* main column */}
      <div className="min-w-0 space-y-5 p-6">
        <div className="flex items-start gap-3 pr-6">
          <button
            type="button"
            disabled={!editable || done.isPending}
            onClick={() => done.mutate(!task.isClosed)}
            className={cn('mt-1 shrink-0', task.isClosed ? 'text-emerald-600' : 'text-muted-foreground hover:text-emerald-600')}
            aria-label={task.isClosed ? tx('Reopen') : tx('Mark as done')}
          >
            {task.isClosed ? <CheckCircle2 className="h-6 w-6" /> : <Circle className="h-6 w-6" />}
          </button>
          <div className="min-w-0 flex-1">
            <DialogDescription className="flex flex-wrap items-center gap-2 text-xs">
              <span className="font-mono">{task.number}</span>
              {task.projectId ? (
                <Link to={`/projects/${task.projectId}`} onClick={onClose} className="hover:underline">
                  {task.projectCode} · {task.projectName}
                </Link>
              ) : <span>{tx('Personal to-do')}</span>}
              {task.parent && (
                <button type="button" className="hover:underline" onClick={() => onOpenTask(task.parent!.id)}>
                  ↑ {task.parent.number} {task.parent.title}
                </button>
              )}
            </DialogDescription>
            <DialogTitle asChild>
              <input
                value={title}
                disabled={!editable}
                onChange={(e) => setTitle(e.target.value)}
                onBlur={() => title.trim() && title !== task.title && save({ title: title.trim() })}
                onKeyDown={(e) => e.key === 'Enter' && (e.target as HTMLInputElement).blur()}
                className={cn('mt-1 w-full bg-transparent text-xl font-semibold outline-none focus:rounded focus:ring-2 focus:ring-ring',
                  task.isClosed && 'text-muted-foreground line-through')}
              />
            </DialogTitle>
          </div>
        </div>

        {task.kanbanState === 'blocked' && (
          <div className="rounded-md border border-rose-200 bg-rose-50 px-3 py-2 text-sm text-rose-800 dark:border-rose-500/30 dark:bg-rose-500/10 dark:text-rose-200">
            <strong>{tx('Blocked')}</strong>{task.blockedReason ? ` — ${task.blockedReason}` : ''}
          </div>
        )}
        {task.blockedByOpen > 0 && (
          <div className="flex items-center gap-2 rounded-md border border-amber-200 bg-amber-50 px-3 py-2 text-sm text-amber-800 dark:border-amber-500/30 dark:bg-amber-500/10 dark:text-amber-200">
            <Lock className="h-4 w-4" /> {tx('Waiting on {{count}} open task(s)', { count: task.blockedByOpen })}
          </div>
        )}

        <section>
          <h3 className="mb-1.5 text-xs font-semibold uppercase tracking-wide text-muted-foreground">{tx('Description')}</h3>
          <Textarea
            value={description}
            disabled={!editable}
            rows={4}
            placeholder={tx('Add details, acceptance criteria, links…')}
            onChange={(e) => setDescription(e.target.value)}
            onBlur={() => description !== (task.description ?? '') && save({ description })}
          />
        </section>

        <Checklist task={task} editable={editable} />
        <Subtasks task={task} editable={editable} onOpenTask={onOpenTask} />
        {(ws?.allowDependencies ?? true) && task.projectId && (
          <Dependencies task={task} editable={editable} onOpenTask={onOpenTask} />
        )}
        {task.projectId && canLog && (ws?.allowTimesheets ?? true) && <TimeSection task={task} />}
        <Chatter taskId={task.id} editable={editable} />
      </div>

      {/* side column */}
      <aside className="space-y-4 border-t bg-muted/30 p-5 md:border-l md:border-t-0">
        {task.stages.length > 0 && (
          <Field label={tx('Stage')}>
            <select
              className={selectClass}
              disabled={!editable}
              value={task.stageId ?? ''}
              onChange={(e) => save({ stageId: e.target.value })}
            >
              {task.stages.map((s) => <option key={s.id} value={s.id}>{s.name}</option>)}
            </select>
          </Field>
        )}
        <Field label={tx('Assignees')}>
          <MultiPicker
            options={users}
            value={task.assignees.map((a) => a.id)}
            disabled={!editable}
            placeholder={tx('Unassigned')}
            onChange={(ids) => save({ assigneeIds: ids })}
            renderOption={(u) => <span className="flex items-center gap-2"><Avatar user={u} />{u.name}</span>}
          />
        </Field>
        <div className="grid grid-cols-2 gap-3">
          <Field label={tx('Priority')}>
            <select className={selectClass} disabled={!editable} value={task.priority} onChange={(e) => save({ priority: e.target.value })}>
              {Object.entries(PRIORITY).map(([k, v]) => <option key={k} value={k}>{v.label}</option>)}
            </select>
          </Field>
          <Field label={tx('State')}>
            <select
              className={selectClass}
              disabled={!editable}
              value={task.kanbanState}
              onChange={(e) => {
                const v = e.target.value;
                if (v === 'blocked') {
                  const reason = window.prompt(tx('Why is this task blocked?')) ?? '';
                  save({ kanbanState: v, blockedReason: reason });
                } else save({ kanbanState: v, blockedReason: null });
              }}
            >
              <option value="normal">{tx('In progress')}</option>
              <option value="ready">{tx('Ready')}</option>
              <option value="blocked">{tx('Blocked')}</option>
            </select>
          </Field>
        </div>
        <div className="grid grid-cols-2 gap-3">
          <Field label={tx('Start')}>
            <Input type="date" disabled={!editable} value={task.plannedStart ?? ''} onChange={(e) => save({ plannedStart: e.target.value || null })} />
          </Field>
          <Field label={tx('Deadline')}>
            <Input
              type="date"
              disabled={!editable}
              value={task.deadline ?? ''}
              className={cn(task.overdue && 'border-rose-400 text-rose-600')}
              onChange={(e) => save({ deadline: e.target.value || null })}
            />
          </Field>
        </div>
        <Field label={tx('Planned hours')}>
          <Input
            type="number"
            min={0}
            step="0.25"
            disabled={!editable}
            defaultValue={num(task.plannedHours) || ''}
            key={`ph-${task.id}-${task.plannedHours}`}
            onBlur={(e) => Number(e.target.value || 0) !== num(task.plannedHours) && save({ plannedHours: Number(e.target.value || 0) })}
          />
          {num(task.plannedHours) > 0 && (
            <p className="mt-1 text-xs text-muted-foreground">
              {tx('{{spent}} spent of {{planned}}', { spent: hours(task.effectiveHours), planned: hours(task.plannedHours) })}
            </p>
          )}
        </Field>
        <Field label={tx('Tags')}>
          <div className="flex flex-wrap gap-1">
            {task.tags.map((t) => (
              <TagChip key={t.id} tag={t} onRemove={editable ? () => save({ tagIds: task.tags.filter((x) => x.id !== t.id).map((x) => x.id) }) : undefined} />
            ))}
            {editable && (
              <MultiPicker
                options={tags}
                value={task.tags.map((t) => t.id)}
                placeholder={tx('Add tag')}
                onChange={(ids) => save({ tagIds: ids })}
                trigger={
                  <button type="button" className="rounded-full border border-dashed px-2 py-0.5 text-[11px] text-muted-foreground hover:text-foreground">
                    + {tx('Tag')}
                  </button>
                }
              />
            )}
          </div>
        </Field>
        {ws && ws.allowMilestones && ws.milestones.length > 0 && (
          <Field label={tx('Milestone')}>
            <select className={selectClass} disabled={!editable} value={task.milestoneId ?? ''} onChange={(e) => save({ milestoneId: e.target.value || null })}>
              <option value="">{tx('None')}</option>
              {ws.milestones.map((m) => <option key={m.id} value={m.id}>{m.name}</option>)}
            </select>
          </Field>
        )}
        {ws && ws.allowRecurring && (
          <Field label={tx('Repeat')}>
            <div className="flex gap-2">
              <select
                className={selectClass}
                disabled={!editable}
                value={task.recurrence ?? ''}
                onChange={(e) => save({ recurrence: e.target.value || null })}
              >
                <option value="">{tx('Does not repeat')}</option>
                <option value="daily">{tx('Daily')}</option>
                <option value="weekly">{tx('Weekly')}</option>
                <option value="monthly">{tx('Monthly')}</option>
                <option value="yearly">{tx('Yearly')}</option>
              </select>
              {task.recurrence && (
                <Input
                  type="number"
                  min={1}
                  className="w-16"
                  title={tx('Every N periods')}
                  disabled={!editable}
                  defaultValue={task.recurrenceInterval}
                  key={`ri-${task.recurrenceInterval}`}
                  onBlur={(e) => Number(e.target.value) !== task.recurrenceInterval && save({ recurrenceInterval: Number(e.target.value || 1) })}
                />
              )}
            </div>
          </Field>
        )}
        {ws && ws.wbs.length > 0 && (
          <Field label={tx('WBS element')}>
            <select className={selectClass} disabled={!editable} value={task.wbsId ?? ''} onChange={(e) => save({ wbsId: e.target.value || null })}>
              <option value="">{tx('None')}</option>
              {ws.wbs.map((w) => <option key={w.id} value={w.id}>{w.code} · {w.name}</option>)}
            </select>
          </Field>
        )}
        {task.source?.label && (
          <Field label={tx('Linked record')}>
            {task.source.path ? (
              <Link to={task.source.path} onClick={onClose} className="flex items-center gap-1 text-sm text-primary hover:underline">
                <Link2 className="h-3.5 w-3.5" /> {task.source.label}
              </Link>
            ) : <span className="text-sm">{task.source.label}</span>}
          </Field>
        )}

        <div className="space-y-2 border-t pt-4">
          <Button variant="outline" size="sm" className="w-full justify-start" onClick={() => follow.mutate(!task.isFollowing)}>
            {task.isFollowing ? <BellOff className="mr-2 h-4 w-4" /> : <Bell className="mr-2 h-4 w-4" />}
            {task.isFollowing ? tx('Unfollow') : tx('Follow')}
            <span className="ml-auto text-xs text-muted-foreground">{task.followers.length}</span>
          </Button>
          {task.canDelete && (
            <Button
              variant="outline"
              size="sm"
              className="w-full justify-start text-rose-600 hover:text-rose-700"
              disabled={del.isPending}
              onClick={async () => {
                if (!window.confirm(tx('Delete this task and its subtasks?'))) return;
                await del.mutateAsync();
                onClose();
              }}
            >
              <Trash2 className="mr-2 h-4 w-4" /> {tx('Delete task')}
            </Button>
          )}
          <p className="text-xs text-muted-foreground">{tx('Created')} {relativeTime(task.createdAt)}</p>
        </div>
      </aside>
    </div>
  );
}

function Field({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div className="space-y-1">
      <p className="text-xs font-medium text-muted-foreground">{label}</p>
      {children}
    </div>
  );
}

function SectionTitle({ icon: Icon, children, extra }: { icon: typeof Clock; children: React.ReactNode; extra?: React.ReactNode }) {
  return (
    <div className="mb-2 flex items-center gap-2">
      <Icon className="h-4 w-4 text-muted-foreground" />
      <h3 className="text-sm font-semibold">{children}</h3>
      {extra && <span className="ml-auto text-xs text-muted-foreground">{extra}</span>}
    </div>
  );
}

function Checklist({ task, editable }: { task: TaskDetail; editable: boolean }) {
  const [title, setTitle] = useState('');
  const add = usePmMutation((t: string) => ({ method: 'post', url: `/project-tasks/${task.id}/checklist`, body: { title: t } }));
  const toggle = usePmMutation((v: { id: string; isDone: boolean }) => ({ method: 'patch', url: `/project-tasks/checklist/${v.id}`, body: { isDone: v.isDone } }));
  const remove = usePmMutation((id: string) => ({ method: 'delete', url: `/project-tasks/checklist/${id}` }));
  if (!editable && task.checklist.length === 0) return null;
  return (
    <section>
      <SectionTitle icon={ListChecks} extra={task.checklistTotal ? `${task.checklistDone}/${task.checklistTotal}` : undefined}>
        {tx('Checklist')}
      </SectionTitle>
      <div className="space-y-1">
        {task.checklist.map((c) => (
          <div key={c.id} className="group flex items-center gap-2 rounded px-1 py-0.5 hover:bg-muted/50">
            <input
              type="checkbox"
              checked={c.isDone}
              disabled={!editable}
              onChange={(e) => toggle.mutate({ id: c.id, isDone: e.target.checked })}
              className="h-4 w-4 accent-primary"
            />
            <span className={cn('flex-1 text-sm', c.isDone && 'text-muted-foreground line-through')}>{c.title}</span>
            {editable && (
              <button type="button" className="opacity-0 group-hover:opacity-100" onClick={() => remove.mutate(c.id)} aria-label={tx('Remove')}>
                <X className="h-3.5 w-3.5 text-muted-foreground" />
              </button>
            )}
          </div>
        ))}
      </div>
      {editable && (
        <form
          className="mt-1.5 flex gap-2"
          onSubmit={(e) => { e.preventDefault(); if (title.trim()) { add.mutate(title.trim()); setTitle(''); } }}
        >
          <Input value={title} onChange={(e) => setTitle(e.target.value)} placeholder={tx('Add an item')} className="h-8" />
          <Button size="sm" variant="secondary" type="submit" disabled={!title.trim()}><Plus className="h-4 w-4" /></Button>
        </form>
      )}
    </section>
  );
}

function Subtasks({ task, editable, onOpenTask }: { task: TaskDetail; editable: boolean; onOpenTask: (id: string) => void }) {
  const [title, setTitle] = useState('');
  const add = usePmMutation((t: string) => ({
    method: 'post', url: '/project-tasks',
    body: { projectId: task.projectId ?? undefined, parentId: task.id, title: t },
  }));
  const done = usePmMutation((v: { id: string; done: boolean }) => ({ method: 'post', url: `/project-tasks/${v.id}/done`, body: { done: v.done } }));
  if (!editable && task.subtasks.length === 0) return null;
  return (
    <section>
      <SectionTitle icon={GitBranch} extra={task.subtaskCount ? `${task.subtaskDone}/${task.subtaskCount}` : undefined}>{tx('Subtasks')}</SectionTitle>
      <div className="divide-y rounded-md border">
        {task.subtasks.map((s) => (
          <div key={s.id} className="flex items-center gap-2 px-2 py-1.5">
            <button type="button" disabled={!editable} onClick={() => done.mutate({ id: s.id, done: !s.isClosed })}>
              {s.isClosed ? <CheckCircle2 className="h-4 w-4 text-emerald-600" /> : <Circle className="h-4 w-4 text-muted-foreground" />}
            </button>
            <button type="button" onClick={() => onOpenTask(s.id)} className={cn('flex-1 truncate text-left text-sm hover:underline', s.isClosed && 'text-muted-foreground line-through')}>
              {s.title}
            </button>
            {s.assignees.slice(0, 2).map((a) => <Avatar key={a.id} user={a} />)}
            {s.deadline && <span className={cn('text-xs', s.overdue ? 'text-rose-600' : 'text-muted-foreground')}>{s.deadline}</span>}
          </div>
        ))}
        {editable && (
          <form
            className="flex gap-2 p-1.5"
            onSubmit={(e) => { e.preventDefault(); if (title.trim()) { add.mutate(title.trim()); setTitle(''); } }}
          >
            <Input value={title} onChange={(e) => setTitle(e.target.value)} placeholder={tx('Add a subtask')} className="h-8 border-0 shadow-none focus-visible:ring-1" />
          </form>
        )}
      </div>
    </section>
  );
}

function Dependencies({ task, editable, onOpenTask }: { task: TaskDetail; editable: boolean; onOpenTask: (id: string) => void }) {
  const [picking, setPicking] = useState(false);
  const { data } = useTasks({ projectId: task.projectId ?? undefined, state: 'all', limit: 500 }, picking);
  const add = usePmMutation((dependsOnId: string) => ({ method: 'post', url: `/project-tasks/${task.id}/dependencies`, body: { dependsOnId } }));
  const remove = usePmMutation((dependsOnId: string) => ({ method: 'delete', url: `/project-tasks/${task.id}/dependencies/${dependsOnId}` }));
  const candidates = useMemo(
    () => (data?.data ?? []).filter((t) => t.id !== task.id && !task.blockedBy.some((b) => b.id === t.id)),
    [data, task],
  );
  if (!editable && !task.blockedBy.length && !task.blocking.length) return null;
  return (
    <section>
      <SectionTitle icon={Lock}>{tx('Dependencies')}</SectionTitle>
      <div className="grid gap-3 sm:grid-cols-2">
        <div>
          <p className="mb-1 text-xs text-muted-foreground">{tx('Blocked by')}</p>
          {task.blockedBy.map((b) => (
            <div key={b.id} className="flex items-center gap-1 text-sm">
              {b.isClosed ? <CheckCircle2 className="h-3.5 w-3.5 text-emerald-600" /> : <Circle className="h-3.5 w-3.5 text-amber-500" />}
              <button type="button" className="truncate hover:underline" onClick={() => onOpenTask(b.id)}>{b.number} {b.title}</button>
              {editable && (
                <button type="button" onClick={() => remove.mutate(b.id)} aria-label={tx('Remove')} className="ml-auto">
                  <X className="h-3.5 w-3.5 text-muted-foreground" />
                </button>
              )}
            </div>
          ))}
          {editable && (picking ? (
            <select
              autoFocus
              className={cn(selectClass, 'mt-1 h-8')}
              defaultValue=""
              onChange={(e) => { if (e.target.value) add.mutate(e.target.value); setPicking(false); }}
              onBlur={() => setPicking(false)}
            >
              <option value="">{tx('Choose a task…')}</option>
              {candidates.map((t) => <option key={t.id} value={t.id}>{t.number} · {t.title}</option>)}
            </select>
          ) : (
            <button type="button" className="mt-1 text-xs text-primary hover:underline" onClick={() => setPicking(true)}>+ {tx('Add blocker')}</button>
          ))}
        </div>
        <div>
          <p className="mb-1 text-xs text-muted-foreground">{tx('Blocking')}</p>
          {task.blocking.length === 0 && <p className="text-sm text-muted-foreground">—</p>}
          {task.blocking.map((b) => (
            <button key={b.id} type="button" className="block truncate text-left text-sm hover:underline" onClick={() => onOpenTask(b.id)}>
              {b.number} {b.title}
            </button>
          ))}
        </div>
      </div>
    </section>
  );
}

function TimeSection({ task }: { task: TaskDetail }) {
  const userId = useAuthStore((s) => s.user?.id);
  const { data: timer } = useTimer();
  const { data: lines = [] } = useTimesheetLines({ taskId: task.id });
  const [elapsed, setElapsed] = useState(0);
  const running = timer?.running && timer.taskId === task.id;
  useEffect(() => {
    if (!running || !timer?.startedAt) return;
    const started = new Date(timer.startedAt).getTime();
    const tick = () => setElapsed(Math.max(0, (Date.now() - started) / 1000));
    tick();
    const h = window.setInterval(tick, 1000);
    return () => window.clearInterval(h);
  }, [running, timer?.startedAt]);

  const start = usePmMutation(() => ({ method: 'post', url: '/project-timesheets/timer/start', body: { taskId: task.id } }));
  const stop = usePmMutation(() => ({ method: 'post', url: '/project-timesheets/timer/stop', body: {} }), { success: tx('Time logged') });
  const log = usePmMutation((v: { date: string; hours: number; description: string }) => ({
    method: 'post', url: '/project-timesheets', body: { taskId: task.id, ...v },
  }), { success: tx('Time logged') });
  const remove = usePmMutation((id: string) => ({ method: 'delete', url: `/project-timesheets/${id}` }));
  const [form, setForm] = useState({ date: todayIso(), hours: '', description: '' });

  return (
    <section>
      <SectionTitle icon={Clock} extra={`${hours(task.effectiveHours)} ${tx('total')} · ${hours(task.myHours)} ${tx('mine')}`}>
        {tx('Time')}
      </SectionTitle>
      <div className="flex flex-wrap items-center gap-2">
        {running ? (
          <Button size="sm" variant="destructive" onClick={() => stop.mutate()} disabled={stop.isPending}>
            <Square className="mr-1.5 h-3.5 w-3.5 fill-current" /> {tx('Stop')} <span className="ml-2 font-mono tabular-nums">{clock(elapsed)}</span>
          </Button>
        ) : (
          <Button size="sm" variant="outline" onClick={() => start.mutate()} disabled={start.isPending || task.isClosed}>
            <Play className="mr-1.5 h-3.5 w-3.5" /> {tx('Start timer')}
          </Button>
        )}
        <form
          className="flex flex-1 flex-wrap items-center gap-2"
          onSubmit={(e) => {
            e.preventDefault();
            const h = Number(form.hours);
            if (!h) return;
            log.mutate({ date: form.date, hours: h, description: form.description });
            setForm({ ...form, hours: '', description: '' });
          }}
        >
          <Input type="date" value={form.date} onChange={(e) => setForm({ ...form, date: e.target.value })} className="h-8 w-36" />
          <Input type="number" step="0.25" min="0.25" max="24" placeholder={tx('Hours')} value={form.hours} onChange={(e) => setForm({ ...form, hours: e.target.value })} className="h-8 w-20" />
          <Input placeholder={tx('What did you do?')} value={form.description} onChange={(e) => setForm({ ...form, description: e.target.value })} className="h-8 min-w-[140px] flex-1" />
          <Button size="sm" type="submit" variant="secondary" disabled={!Number(form.hours) || log.isPending}>{tx('Log')}</Button>
        </form>
      </div>
      {lines.length > 0 && (
        <div className="mt-2 divide-y rounded-md border text-sm">
          {lines.slice(0, 8).map((l) => (
            <div key={l.id} className="flex items-center gap-2 px-2 py-1.5">
              <span className="w-20 text-xs text-muted-foreground">{l.date}</span>
              <span className="w-24 truncate text-xs">{l.userName}</span>
              <span className="flex-1 truncate text-muted-foreground">{l.description}</span>
              <span className="font-medium tabular-nums">{hours(l.hours)}</span>
              {l.locked ? (
                <Lock className="h-3.5 w-3.5 text-muted-foreground" aria-label={tx('Approved in HR')} />
              ) : l.userId === userId ? (
                <button type="button" onClick={() => remove.mutate(l.id)} aria-label={tx('Remove')}><X className="h-3.5 w-3.5 text-muted-foreground" /></button>
              ) : <span className="w-3.5" />}
            </div>
          ))}
        </div>
      )}
    </section>
  );
}

function Chatter({ taskId, editable }: { taskId: string; editable: boolean }) {
  const { data: messages = [] } = useTaskMessages(taskId);
  const [kind, setKind] = useState<'comment' | 'note'>('comment');
  const [body, setBody] = useState('');
  const post = usePmMutation((v: { kind: string; body: string }) => ({ method: 'post', url: `/project-tasks/${taskId}/messages`, body: v }));
  return (
    <section>
      <SectionTitle icon={MessageSquare}>{tx('Activity')}</SectionTitle>
      {editable && (
        <form
          className="space-y-2"
          onSubmit={(e) => { e.preventDefault(); if (body.trim()) { post.mutate({ kind, body: body.trim() }); setBody(''); } }}
        >
          <div className="flex gap-1 text-xs">
            <button type="button" onClick={() => setKind('comment')} className={cn('rounded px-2 py-1', kind === 'comment' ? 'bg-primary text-primary-foreground' : 'hover:bg-muted')}>
              <MessageSquare className="mr-1 inline h-3 w-3" />{tx('Send message')}
            </button>
            <button type="button" onClick={() => setKind('note')} className={cn('rounded px-2 py-1', kind === 'note' ? 'bg-amber-500 text-white' : 'hover:bg-muted')}>
              <StickyNote className="mr-1 inline h-3 w-3" />{tx('Log note')}
            </button>
          </div>
          <Textarea
            rows={2}
            value={body}
            onChange={(e) => setBody(e.target.value)}
            placeholder={kind === 'comment' ? tx('Message followers…') : tx('Internal note…')}
          />
          <div className="flex justify-end"><Button size="sm" type="submit" disabled={!body.trim() || post.isPending}>{tx('Post')}</Button></div>
        </form>
      )}
      <ol className="mt-3 space-y-3">
        {messages.map((m) => (
          <li key={m.id} className="flex gap-2">
            {m.kind === 'system' ? (
              <span className="mt-0.5 flex h-6 w-6 shrink-0 items-center justify-center rounded-full bg-muted"><Circle className="h-2 w-2 fill-current text-muted-foreground" /></span>
            ) : <Avatar user={{ id: m.authorId ?? m.id, name: m.authorName }} />}
            <div className="min-w-0 flex-1">
              <p className="text-xs text-muted-foreground">
                <span className="font-medium text-foreground">{m.authorName ?? tx('System')}</span> · {relativeTime(m.createdAt)}
                {m.kind === 'note' && <span className="ml-1 rounded bg-amber-100 px-1 text-amber-800 dark:bg-amber-500/20 dark:text-amber-200">{tx('note')}</span>}
              </p>
              <p className={cn('whitespace-pre-wrap text-sm', m.kind === 'system' && 'text-muted-foreground')}>{m.body}</p>
            </div>
          </li>
        ))}
      </ol>
    </section>
  );
}

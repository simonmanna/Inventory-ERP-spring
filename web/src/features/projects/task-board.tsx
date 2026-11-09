import { useMemo, useState } from 'react';
import {
  DndContext, DragOverlay, PointerSensor, closestCorners, useDraggable, useDroppable, useSensor, useSensors,
  type DragEndEvent, type DragStartEvent,
} from '@dnd-kit/core';
import { CalendarDays, CheckCircle2, ChevronRight, Circle, GitBranch, ListChecks, Lock, Plus } from 'lucide-react';
import { Input } from '@/components/ui/input';
import { cn } from '@/lib/utils';
import { tx } from '@/lib/i18n/tx';
import { usePmMutation, type Member, type Priority, type Stage, type Task } from './api';
import { AvatarStack, PRIORITY, PriorityFlag, TagChip, hours, pmColor, selectClass, shortDate } from './ui';

/** Kanban of a project's stages; drag a card to move it (done / cancelled stages close the task). */
export function TaskBoard({ projectId, stages, tasks, editable, onOpen }: {
  projectId: string;
  stages: Stage[];
  tasks: Task[];
  editable: boolean;
  onOpen: (id: string) => void;
}) {
  const sensors = useSensors(useSensor(PointerSensor, { activationConstraint: { distance: 6 } }));
  const [dragging, setDragging] = useState<Task | null>(null);
  const [unfolded, setUnfolded] = useState<Record<string, boolean>>({});
  const move = usePmMutation((v: { id: string; stageId: string; index?: number }) => ({
    method: 'post', url: `/project-tasks/${v.id}/move`, body: { stageId: v.stageId, index: v.index },
  }));

  const byStage = useMemo(() => {
    const m = new Map<string, Task[]>();
    stages.forEach((s) => m.set(s.id, []));
    tasks.forEach((t) => { if (t.stageId && m.has(t.stageId)) m.get(t.stageId)!.push(t); });
    return m;
  }, [stages, tasks]);

  const onDragStart = (e: DragStartEvent) => setDragging(tasks.find((t) => t.id === e.active.id) ?? null);
  const onDragEnd = (e: DragEndEvent) => {
    setDragging(null);
    const task = tasks.find((t) => t.id === e.active.id);
    const overId = e.over?.id as string | undefined;
    if (!task || !overId) return;
    const stageId = stages.some((s) => s.id === overId) ? overId : tasks.find((t) => t.id === overId)?.stageId;
    if (!stageId || stageId === task.stageId) return;
    move.mutate({ id: task.id, stageId });
  };

  return (
    <DndContext sensors={sensors} collisionDetection={closestCorners} onDragStart={onDragStart} onDragEnd={onDragEnd}>
      <div className="flex gap-3 overflow-x-auto pb-4">
        {stages.map((s) => {
          const list = byStage.get(s.id) ?? [];
          const folded = s.folded && !unfolded[s.id];
          return folded ? (
            <button
              key={s.id}
              type="button"
              onClick={() => setUnfolded({ ...unfolded, [s.id]: true })}
              className="flex w-10 shrink-0 flex-col items-center gap-2 rounded-xl border bg-muted/40 py-3 text-xs text-muted-foreground hover:bg-muted"
            >
              <ChevronRight className="h-4 w-4" />
              <span className="[writing-mode:vertical-rl]">{s.name} ({list.length})</span>
            </button>
          ) : (
            <Column key={s.id} stage={s} tasks={list} projectId={projectId} editable={editable} onOpen={onOpen} />
          );
        })}
      </div>
      <DragOverlay>{dragging && <Card task={dragging} overlay />}</DragOverlay>
    </DndContext>
  );
}

function Column({ stage, tasks, projectId, editable, onOpen }: {
  stage: Stage; tasks: Task[]; projectId: string; editable: boolean; onOpen: (id: string) => void;
}) {
  const { setNodeRef, isOver } = useDroppable({ id: stage.id, disabled: !editable });
  const planned = tasks.reduce((a, t) => a + Number(t.plannedHours || 0), 0);
  return (
    <div
      ref={setNodeRef}
      className={cn('flex w-72 shrink-0 flex-col rounded-xl border bg-muted/40 transition-colors', isOver && 'border-primary bg-primary/5')}
    >
      <div className="flex items-center gap-2 px-3 pb-2 pt-3">
        <span className={cn('h-2 w-2 rounded-full',
          stage.kind === 'done' ? 'bg-emerald-500' : stage.kind === 'cancelled' ? 'bg-slate-400' : 'bg-sky-500')} />
        <h3 className="truncate text-sm font-semibold">{stage.name}</h3>
        <span className="rounded-full bg-background px-1.5 text-xs text-muted-foreground">{tasks.length}</span>
        {planned > 0 && <span className="ml-auto text-xs text-muted-foreground">{hours(planned)}</span>}
      </div>
      {editable && stage.kind === 'open' && <QuickAdd projectId={projectId} stageId={stage.id} />}
      <div className="flex min-h-[80px] flex-1 flex-col gap-2 px-2 pb-3">
        {tasks.map((t) => <Draggable key={t.id} task={t} editable={editable} onOpen={onOpen} />)}
      </div>
    </div>
  );
}

function QuickAdd({ projectId, stageId }: { projectId: string; stageId: string }) {
  const [open, setOpen] = useState(false);
  const [title, setTitle] = useState('');
  const create = usePmMutation((t: string) => ({ method: 'post', url: '/project-tasks', body: { projectId, stageId, title: t } }));
  if (!open) {
    return (
      <button type="button" onClick={() => setOpen(true)} className="mx-2 mb-2 flex items-center gap-1 rounded-md px-2 py-1 text-xs text-muted-foreground hover:bg-background hover:text-foreground">
        <Plus className="h-3.5 w-3.5" /> {tx('Add task')}
      </button>
    );
  }
  return (
    <form
      className="mx-2 mb-2"
      onSubmit={(e) => { e.preventDefault(); if (title.trim()) { create.mutate(title.trim()); setTitle(''); } }}
    >
      <Input
        autoFocus
        value={title}
        onChange={(e) => setTitle(e.target.value)}
        onBlur={() => !title && setOpen(false)}
        onKeyDown={(e) => e.key === 'Escape' && setOpen(false)}
        placeholder={tx('Task title, then Enter')}
        className="h-8 bg-background"
      />
    </form>
  );
}

function Draggable({ task, editable, onOpen }: { task: Task; editable: boolean; onOpen: (id: string) => void }) {
  const { attributes, listeners, setNodeRef, isDragging } = useDraggable({ id: task.id, disabled: !editable });
  const drop = useDroppable({ id: task.id, disabled: !editable });
  return (
    <div
      ref={(n) => { setNodeRef(n); drop.setNodeRef(n); }}
      {...attributes}
      {...listeners}
      onClick={() => onOpen(task.id)}
      className={cn(isDragging && 'opacity-30')}
    >
      <Card task={task} />
    </div>
  );
}

export function Card({ task, overlay }: { task: Task; overlay?: boolean }) {
  const c = pmColor(task.color);
  return (
    <div
      className={cn(
        'cursor-pointer space-y-2 rounded-lg border border-l-4 bg-card p-2.5 text-left shadow-sm transition hover:shadow-md',
        c.border,
        overlay && 'rotate-2 shadow-lg',
        task.isClosed && 'opacity-70',
      )}
    >
      <div className="flex items-start gap-1.5">
        {task.kanbanState === 'blocked' && <span title={tx('Blocked')} className="mt-1 h-2 w-2 shrink-0 rounded-full bg-rose-500" />}
        {task.kanbanState === 'ready' && <span title={tx('Ready')} className="mt-1 h-2 w-2 shrink-0 rounded-full bg-emerald-500" />}
        <p className={cn('flex-1 text-sm font-medium leading-snug', task.isClosed && 'line-through')}>{task.title}</p>
        <PriorityFlag priority={task.priority} className="mt-0.5" />
      </div>
      {task.tags.length > 0 && (
        <div className="flex flex-wrap gap-1">{task.tags.slice(0, 3).map((t) => <TagChip key={t.id} tag={t} />)}</div>
      )}
      <div className="flex items-center gap-2 text-xs text-muted-foreground">
        <span className="font-mono text-[10px]">{task.number}</span>
        {task.deadline && (
          <span className={cn('flex items-center gap-0.5', task.overdue && 'font-medium text-rose-600')}>
            <CalendarDays className="h-3 w-3" />{shortDate(task.deadline)}
          </span>
        )}
        {task.checklistTotal > 0 && (
          <span className={cn('flex items-center gap-0.5', task.checklistDone === task.checklistTotal && 'text-emerald-600')}>
            <ListChecks className="h-3 w-3" />{task.checklistDone}/{task.checklistTotal}
          </span>
        )}
        {task.subtaskCount > 0 && (
          <span className="flex items-center gap-0.5"><GitBranch className="h-3 w-3" />{task.subtaskDone}/{task.subtaskCount}</span>
        )}
        {task.blockedByOpen > 0 && <Lock className="h-3 w-3 text-amber-500" aria-label={tx('Waiting on other tasks')} />}
        <span className="ml-auto"><AvatarStack users={task.assignees} /></span>
      </div>
      {task.progress != null && Number(task.plannedHours) > 0 && (
        <div className="h-1 overflow-hidden rounded-full bg-muted">
          <div className={cn('h-full', task.progress > 100 ? 'bg-rose-500' : 'bg-primary')} style={{ width: `${Math.min(100, task.progress)}%` }} />
        </div>
      )}
    </div>
  );
}

/** Flat sortable-looking list of tasks, grouped by nothing; used by the List tab and My Tasks. */
export function TaskList({ tasks, onOpen, showProject, bulk }: {
  tasks: Task[];
  onOpen: (id: string) => void;
  showProject?: boolean;
  /** Enables row selection and bulk changes; stages only when every task belongs to one project. */
  bulk?: { stages?: Stage[]; members?: Member[] };
}) {
  const done = usePmMutation((v: { id: string; done: boolean }) => ({ method: 'post', url: `/project-tasks/${v.id}/done`, body: { done: v.done } }));
  const [selected, setSelected] = useState<string[]>([]);
  const visibleIds = useMemo(() => new Set(tasks.map((t) => t.id)), [tasks]);
  const picked = selected.filter((id) => visibleIds.has(id));
  const allPicked = tasks.length > 0 && picked.length === tasks.length;
  const toggle = (id: string) => setSelected((s) => (s.includes(id) ? s.filter((x) => x !== id) : [...s, id]));
  return (
    <div className="space-y-2">
      {bulk && picked.length > 0 && <BulkBar ids={picked} stages={bulk.stages} members={bulk.members} onDone={() => setSelected([])} />}
    <div className="overflow-x-auto rounded-xl border bg-card shadow-sm">
      <table className="w-full text-sm">
        <thead className="bg-muted/40 text-xs uppercase tracking-wide text-muted-foreground">
          <tr>
            {bulk && (
              <th className="w-8 px-3 py-2">
                <input
                  type="checkbox"
                  className="h-4 w-4 accent-primary"
                  aria-label={tx('Select all')}
                  checked={allPicked}
                  onChange={() => setSelected(allPicked ? [] : tasks.slice(0, 200).map((t) => t.id))}
                />
              </th>
            )}
            <th className="w-8 px-3 py-2" />
            <th className="px-3 py-2 text-left font-semibold">{tx('Task')}</th>
            {showProject && <th className="px-3 py-2 text-left font-semibold">{tx('Project')}</th>}
            <th className="px-3 py-2 text-left font-semibold">{tx('Stage')}</th>
            <th className="px-3 py-2 text-left font-semibold">{tx('Assignees')}</th>
            <th className="px-3 py-2 text-left font-semibold">{tx('Deadline')}</th>
            <th className="px-3 py-2 text-right font-semibold">{tx('Hours')}</th>
          </tr>
        </thead>
        <tbody className="divide-y">
          {tasks.map((t) => (
            <tr key={t.id} className={cn('cursor-pointer hover:bg-muted/40', picked.includes(t.id) && 'bg-primary/5')} onClick={() => onOpen(t.id)}>
              {bulk && (
                <td className="px-3 py-2" onClick={(e) => e.stopPropagation()}>
                  <input type="checkbox" className="h-4 w-4 accent-primary" aria-label={tx('Select task')} checked={picked.includes(t.id)} onChange={() => toggle(t.id)} />
                </td>
              )}
              <td className="px-3 py-2" onClick={(e) => e.stopPropagation()}>
                <button type="button" onClick={() => done.mutate({ id: t.id, done: !t.isClosed })} aria-label={tx('Toggle done')}>
                  {t.isClosed ? <CheckCircle2 className="h-4 w-4 text-emerald-600" /> : <Circle className="h-4 w-4 text-muted-foreground hover:text-emerald-600" />}
                </button>
              </td>
              <td className="max-w-[360px] px-3 py-2">
                <div className="flex items-center gap-1.5">
                  <PriorityFlag priority={t.priority} />
                  <span className={cn('truncate font-medium', t.isClosed && 'text-muted-foreground line-through')}>{t.title}</span>
                  {t.kanbanState === 'blocked' && <span className="rounded bg-rose-100 px-1 text-[10px] font-semibold text-rose-700 dark:bg-rose-500/20 dark:text-rose-300">{tx('Blocked')}</span>}
                </div>
                <div className="flex gap-2 text-xs text-muted-foreground">
                  <span className="font-mono">{t.number}</span>
                  {t.tags.slice(0, 3).map((g) => <span key={g.id}>#{g.name}</span>)}
                </div>
              </td>
              {showProject && <td className="px-3 py-2 text-xs">{t.projectCode ? `${t.projectCode} · ${t.projectName}` : <span className="text-muted-foreground">{tx('Personal')}</span>}</td>}
              <td className="px-3 py-2 text-xs">{t.stageName ?? '—'}</td>
              <td className="px-3 py-2"><AvatarStack users={t.assignees} /></td>
              <td className={cn('px-3 py-2 text-xs', t.overdue && 'font-medium text-rose-600')}>{t.deadline ?? '—'}</td>
              <td className="px-3 py-2 text-right text-xs tabular-nums">
                {Number(t.plannedHours) > 0 ? `${hours(t.effectiveHours, false)} / ${hours(t.plannedHours)}` : hours(t.effectiveHours)}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
    </div>
  );
}

/** Changes the selected tasks at once (POST /project-tasks/bulk, at most 200 tasks). */
function BulkBar({ ids, stages, members, onDone }: { ids: string[]; stages?: Stage[]; members?: Member[]; onDone: () => void }) {
  const [deadline, setDeadline] = useState('');
  const apply = usePmMutation<Record<string, unknown>, { changed: number }>(
    (changes) => ({ method: 'post', url: '/project-tasks/bulk', body: { taskIds: ids, ...changes } }),
    { success: tx('Tasks updated') },
  );
  const run = (changes: Record<string, unknown>) => apply.mutate(changes, { onSuccess: onDone });
  const cls = cn(selectClass, 'h-8 w-40 text-xs');
  return (
    <div className="flex flex-wrap items-center gap-2 rounded-lg border bg-muted/40 px-3 py-2 text-sm">
      <span className="font-medium">{tx('{{count}} selected', { count: ids.length })}</span>
      {stages && stages.length > 0 && (
        <select className={cls} value="" disabled={apply.isPending} onChange={(e) => e.target.value && run({ stageId: e.target.value })} aria-label={tx('Move to stage')}>
          <option value="">{tx('Move to stage…')}</option>
          {stages.map((st) => <option key={st.id} value={st.id}>{st.name}</option>)}
        </select>
      )}
      <select className={cls} value="" disabled={apply.isPending} onChange={(e) => e.target.value && run({ priority: e.target.value })} aria-label={tx('Set priority')}>
        <option value="">{tx('Set priority…')}</option>
        {(Object.keys(PRIORITY) as Priority[]).map((p) => <option key={p} value={p}>{PRIORITY[p].label}</option>)}
      </select>
      {members && members.length > 0 && (
        <select className={cls} value="" disabled={apply.isPending} onChange={(e) => e.target.value && run({ addAssigneeId: e.target.value })} aria-label={tx('Add assignee')}>
          <option value="">{tx('Add assignee…')}</option>
          {members.map((m) => <option key={m.userId} value={m.userId}>{m.name || m.email}</option>)}
        </select>
      )}
      <div className="flex items-center gap-1">
        <Input type="date" className="h-8 w-36 text-xs" value={deadline} onChange={(e) => setDeadline(e.target.value)} aria-label={tx('Deadline')} />
        <button type="button" className="rounded border bg-background px-2 py-1 text-xs hover:bg-muted disabled:opacity-50" disabled={apply.isPending} onClick={() => run({ deadline })}>
          {deadline ? tx('Set deadline') : tx('Clear deadline')}
        </button>
      </div>
      <button type="button" className="ml-auto text-xs text-muted-foreground hover:text-foreground" onClick={onDone}>{tx('Clear selection')}</button>
    </div>
  );
}

import { useMemo, useState } from 'react';
import { Link, useParams, useSearchParams } from 'react-router-dom';
import {
  ArrowLeft, BarChart3, CalendarRange, Copy, KanbanSquare, List, Lock, MessageSquare, Milestone as MilestoneIcon,
  Settings2, Star, TrendingUp,
} from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Skeleton } from '@/components/ui/skeleton';
import { SearchInput } from '@/components/list/list-toolbar';
import { useAuthStore } from '@/stores/auth.store';
import { PERMISSIONS } from '@erp/shared';
import { useDebouncedValue } from '@/lib/use-debounced-value';
import { cn } from '@/lib/utils';
import { tx } from '@/lib/i18n/tx';
import { usePmMutation, useTags, useTasks, useWorkspace } from '@/features/projects/api';
import { TaskBoard, TaskList } from '@/features/projects/task-board';
import { TaskDialog } from '@/features/projects/task-dialog';
import { ProjectDialog } from '@/features/projects/project-dialog';
import { ProjectStatusPill, UpdateStatusPill, pmColor, selectClass } from '@/features/projects/ui';
import { OverviewTab } from './tabs/OverviewTab';
import { MilestonesTab } from './tabs/MilestonesTab';
import { UpdatesTab } from './tabs/UpdatesTab';
import { DiscussionTab } from './tabs/DiscussionTab';
import { SettingsTab } from './tabs/SettingsTab';
import { GanttTab } from './tabs/GanttTab';

type Tab = 'board' | 'list' | 'overview' | 'timeline' | 'milestones' | 'updates' | 'discussion' | 'settings';

/** One project: kanban / list of tasks plus overview, timeline, milestones, status updates, chatter and settings. */
export function ProjectDetailPage() {
  const { id } = useParams<{ id: string }>();
  const [params, setParams] = useSearchParams();
  const tab = (params.get('tab') as Tab) || 'board';
  const openTask = params.get('task');
  const setParam = (k: string, v: string | null) => {
    const next = new URLSearchParams(params);
    if (v) next.set(k, v); else next.delete(k);
    setParams(next, { replace: k === 'task' ? false : true });
  };

  const me = useAuthStore((s) => s.user?.id);
  const canDuplicate = useAuthStore((s) => s.hasPermission)(PERMISSIONS.project.manage);
  const { data: ws, isLoading, error } = useWorkspace(id);
  const { data: tags = [] } = useTags();
  const [search, setSearch] = useState('');
  const [assignee, setAssignee] = useState('');
  const [tagId, setTagId] = useState('');
  const [showClosed, setShowClosed] = useState(false);
  const [duplicating, setDuplicating] = useState(false);
  const q = useDebouncedValue(search, 250);
  const taskView = tab === 'board' || tab === 'list';
  const { data: tasks } = useTasks(
    { projectId: id, search: q, assigneeId: assignee || undefined, tagId: tagId || undefined, topLevel: true, state: tab === 'list' && !showClosed ? 'open' : 'all', limit: 2000 },
    !!id && taskView,
  );
  const fav = usePmMutation(() => ({ method: 'post', url: `/projects/${id}/favorite` }));

  const tabs = useMemo(() => ([
    { id: 'board', label: tx('Board'), icon: KanbanSquare },
    { id: 'list', label: tx('List'), icon: List },
    { id: 'overview', label: tx('Overview'), icon: BarChart3 },
    { id: 'timeline', label: tx('Timeline'), icon: CalendarRange },
    ...(ws?.allowMilestones ? [{ id: 'milestones', label: tx('Milestones'), icon: MilestoneIcon }] : []),
    { id: 'updates', label: tx('Status updates'), icon: TrendingUp },
    { id: 'discussion', label: tx('Discussion'), icon: MessageSquare },
    ...(ws?.canManage ? [{ id: 'settings', label: tx('Settings'), icon: Settings2 }] : []),
  ] as Array<{ id: Tab; label: string; icon: typeof List }>), [ws?.allowMilestones, ws?.canManage]);

  if (error) {
    return (
      <div className="p-10 text-center text-muted-foreground">
        {tx('This project does not exist or you are not a member of it.')}{' '}
        <Link to="/projects" className="text-primary hover:underline">{tx('Back to projects')}</Link>
      </div>
    );
  }
  if (isLoading || !ws) return <div className="space-y-4"><Skeleton className="h-16 w-full" /><Skeleton className="h-96 w-full" /></div>;

  const c = pmColor(ws.color);
  // Board shows only top-level tasks; closed tasks live in done / cancelled stages.
  const visible = tasks?.data ?? [];

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div className="flex min-w-0 items-start gap-3">
          <Link to="/projects" className="mt-1.5 rounded p-1 text-muted-foreground hover:bg-muted hover:text-foreground" aria-label={tx('Back to projects')}>
            <ArrowLeft className="h-4 w-4" />
          </Link>
          <span className={cn('mt-2 h-3 w-3 shrink-0 rounded-full', ws.color ? c.bg : 'bg-primary')} />
          <div className="min-w-0">
            <div className="flex flex-wrap items-center gap-2 text-xs text-muted-foreground">
              <span className="font-mono">{ws.code}</span>
              <ProjectStatusPill status={ws.status} />
              <UpdateStatusPill status={ws.lastUpdateStatus} />
              {ws.privacy === 'members' && <span className="flex items-center gap-1"><Lock className="h-3 w-3" />{tx('Members only')}</span>}
              {ws.isTemplate && <span className="rounded bg-violet-100 px-1.5 font-medium text-violet-700 dark:bg-violet-500/20 dark:text-violet-300">{tx('Template')}</span>}
            </div>
            <h1 className="truncate text-2xl font-bold tracking-tight">{ws.name}</h1>
            <p className="text-sm text-muted-foreground">
              {ws.managerName && <>{tx('Manager')}: {ws.managerName}</>}
              {ws.customerName && <> · {ws.customerName}</>}
              {(ws.startDate || ws.endDate) && <> · {ws.startDate ?? '…'} → {ws.endDate ?? '…'}</>}
            </p>
          </div>
        </div>
        <div className="flex items-center gap-2">
          <Button variant="outline" size="sm" onClick={() => fav.mutate()}>
            <Star className={cn('mr-1.5 h-4 w-4', ws.isFavorite && 'fill-amber-400 text-amber-400')} />
            {ws.isFavorite ? tx('Favourite') : tx('Add to favourites')}
          </Button>
          {canDuplicate && (
            <Button variant="outline" size="sm" onClick={() => setDuplicating(true)}><Copy className="mr-1.5 h-4 w-4" />{tx('Duplicate')}</Button>
          )}
        </div>
      </div>

      <div className="flex gap-1 overflow-x-auto border-b">
        {tabs.map((t) => (
          <button
            key={t.id}
            type="button"
            onClick={() => setParam('tab', t.id === 'board' ? null : t.id)}
            className={cn('-mb-px flex shrink-0 items-center gap-1.5 border-b-2 px-3 py-2 text-sm',
              tab === t.id ? 'border-primary font-medium text-foreground' : 'border-transparent text-muted-foreground hover:text-foreground')}
          >
            <t.icon className="h-4 w-4" />{t.label}
          </button>
        ))}
      </div>

      {taskView && (
        <div className="flex flex-wrap items-center gap-2">
          <SearchInput value={search} onChange={setSearch} placeholder={tx('Search tasks…')} />
          <select className={cn(selectClass, 'w-44')} value={assignee} onChange={(e) => setAssignee(e.target.value)}>
            <option value="">{tx('Everyone')}</option>
            <option value="me">{tx('Assigned to me')}</option>
            <option value="none">{tx('Unassigned')}</option>
            {ws.members.filter((m) => m.userId !== me).map((m) => <option key={m.userId} value={m.userId}>{m.name || m.email}</option>)}
          </select>
          {tags.length > 0 && (
            <select className={cn(selectClass, 'w-40')} value={tagId} onChange={(e) => setTagId(e.target.value)}>
              <option value="">{tx('All tags')}</option>
              {tags.map((t) => <option key={t.id} value={t.id}>{t.name}</option>)}
            </select>
          )}
          {tab === 'list' && (
            <label className="flex items-center gap-2 text-sm text-muted-foreground">
              <input type="checkbox" className="h-4 w-4 accent-primary" checked={showClosed} onChange={(e) => setShowClosed(e.target.checked)} />
              {tx('Show closed')}
            </label>
          )}
          <span className="ml-auto text-xs text-muted-foreground">{tx('{{count}} tasks', { count: tasks?.total ?? 0 })}</span>
        </div>
      )}

      {tab === 'board' && (
        <TaskBoard projectId={ws.id} stages={ws.stages} tasks={visible} editable={ws.canEditTasks} onOpen={(t) => setParam('task', t)} />
      )}
      {tab === 'list' && <TaskList tasks={visible} onOpen={(t) => setParam('task', t)} />}
      {tab === 'overview' && <OverviewTab ws={ws} onOpenTask={(t) => setParam('task', t)} />}
      {tab === 'timeline' && <GanttTab projectId={ws.id} onOpenTask={(t) => setParam('task', t)} />}
      {tab === 'milestones' && <MilestonesTab ws={ws} />}
      {tab === 'updates' && <UpdatesTab ws={ws} />}
      {tab === 'discussion' && <DiscussionTab ws={ws} onOpenTask={(t) => setParam('task', t)} />}
      {tab === 'settings' && ws.canManage && <SettingsTab ws={ws} />}

      <TaskDialog taskId={openTask} onClose={() => setParam('task', null)} onOpenTask={(t) => setParam('task', t)} />
      <ProjectDialog open={duplicating} onClose={() => setDuplicating(false)} duplicateOf={ws} />
    </div>
  );
}

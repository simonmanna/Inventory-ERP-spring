import { useMemo, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { ListTodo, Plus } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Skeleton } from '@/components/ui/skeleton';
import { ListPageHeader } from '@/components/list/list-page';
import { SearchInput } from '@/components/list/list-toolbar';
import { useAuthStore } from '@/stores/auth.store';
import { PERMISSIONS } from '@erp/shared';
import { useDebouncedValue } from '@/lib/use-debounced-value';
import { cn } from '@/lib/utils';
import { tx } from '@/lib/i18n/tx';
import { usePmMutation, useTasks, type Task } from '@/features/projects/api';
import { TaskList } from '@/features/projects/task-board';
import { TaskDialog } from '@/features/projects/task-dialog';
import { EmptyState, addDaysIso, todayIso } from '@/features/projects/ui';

type View = 'assigned' | 'personal' | 'followed';

/** Everything on my plate across projects, grouped by when it is due, plus personal to-dos. */
export function MyTasksPage() {
  const [params, setParams] = useSearchParams();
  const openTask = params.get('task');
  const setTask = (id: string | null) => {
    const n = new URLSearchParams(params);
    if (id) n.set('task', id); else n.delete('task');
    setParams(n);
  };
  const canWrite = useAuthStore((s) => s.hasPermission)(PERMISSIONS.projectTask.write);
  const [view, setView] = useState<View>('assigned');
  const [search, setSearch] = useState('');
  const [showClosed, setShowClosed] = useState(false);
  const q = useDebouncedValue(search, 250);
  const { data, isLoading } = useTasks({
    assigneeId: view === 'assigned' ? 'me' : undefined,
    personal: view === 'personal' ? true : undefined,
    search: q,
    state: showClosed ? 'all' : 'open',
    limit: 1000,
  });
  const tasks = useMemo(() => data?.data ?? [], [data]);
  const groups = useMemo(() => groupByDue(tasks), [tasks]);
  const [todo, setTodo] = useState('');
  const create = usePmMutation((title: string) => ({ method: 'post', url: '/project-tasks', body: { title } }), { success: tx('To-do added') });

  return (
    <div className="space-y-4">
      <ListPageHeader title={tx('My tasks')} description={tx('Your assigned project tasks and personal to-dos, by due date.')} icon={ListTodo} />
      <div className="flex flex-wrap items-center gap-2">
        <div className="flex rounded-lg border bg-muted/40 p-0.5">
          {([['assigned', tx('Assigned to me')], ['personal', tx('Personal to-dos')]] as const).map(([v, label]) => (
            <button key={v} type="button" onClick={() => setView(v)} className={cn('rounded-md px-3 py-1 text-sm', view === v ? 'bg-background font-medium shadow-sm' : 'text-muted-foreground hover:text-foreground')}>
              {label}
            </button>
          ))}
        </div>
        <SearchInput value={search} onChange={setSearch} placeholder={tx('Search tasks…')} />
        <label className="flex items-center gap-2 text-sm text-muted-foreground">
          <input type="checkbox" className="h-4 w-4 accent-primary" checked={showClosed} onChange={(e) => setShowClosed(e.target.checked)} />
          {tx('Show closed')}
        </label>
      </div>

      {canWrite && (
        <form className="flex gap-2" onSubmit={(e) => { e.preventDefault(); if (todo.trim()) create.mutate(todo.trim(), { onSuccess: () => setTodo('') }); }}>
          <Input value={todo} onChange={(e) => setTodo(e.target.value)} placeholder={tx('Add a personal to-do and press Enter')} className="max-w-xl" />
          <Button type="submit" variant="secondary" disabled={!todo.trim()}><Plus className="mr-1 h-4 w-4" />{tx('Add')}</Button>
        </form>
      )}

      {isLoading ? <Skeleton className="h-64 w-full" /> : tasks.length === 0 ? (
        <EmptyState icon={ListTodo} title={tx('Nothing here — nice work!')} hint={view === 'assigned' ? tx('Tasks assigned to you in any project will show up here.') : tx('Personal to-dos are private tasks outside any project.')} />
      ) : (
        <div className="space-y-5">
          {groups.map((g) => g.tasks.length > 0 && (
            <section key={g.key}>
              <h2 className={cn('mb-2 text-sm font-semibold', g.key === 'overdue' && 'text-rose-600')}>
                {g.label} <span className="font-normal text-muted-foreground">({g.tasks.length})</span>
              </h2>
              <TaskList tasks={g.tasks} onOpen={setTask} showProject bulk={canWrite ? {} : undefined} />
            </section>
          ))}
        </div>
      )}
      <TaskDialog taskId={openTask} onClose={() => setTask(null)} onOpenTask={setTask} />
    </div>
  );
}

function groupByDue(tasks: Task[]) {
  const today = todayIso();
  const week = addDaysIso(today, 7);
  const g = {
    overdue: [] as Task[], today: [] as Task[], week: [] as Task[], later: [] as Task[], none: [] as Task[], closed: [] as Task[],
  };
  for (const t of tasks) {
    if (t.isClosed) g.closed.push(t);
    else if (!t.deadline) g.none.push(t);
    else if (t.deadline < today) g.overdue.push(t);
    else if (t.deadline === today) g.today.push(t);
    else if (t.deadline <= week) g.week.push(t);
    else g.later.push(t);
  }
  const byDeadline = (a: Task, b: Task) => (a.deadline ?? '').localeCompare(b.deadline ?? '');
  return [
    { key: 'overdue', label: tx('Overdue'), tasks: g.overdue.sort(byDeadline) },
    { key: 'today', label: tx('Today'), tasks: g.today },
    { key: 'week', label: tx('Next 7 days'), tasks: g.week.sort(byDeadline) },
    { key: 'later', label: tx('Later'), tasks: g.later.sort(byDeadline) },
    { key: 'none', label: tx('No deadline'), tasks: g.none },
    { key: 'closed', label: tx('Closed'), tasks: g.closed },
  ];
}

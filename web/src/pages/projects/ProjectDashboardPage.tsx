import { useState } from 'react';
import { Link } from 'react-router-dom';
import { AlertTriangle, LayoutDashboard } from 'lucide-react';
import { Bar, BarChart, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts';
import { Skeleton } from '@/components/ui/skeleton';
import { ListPageHeader } from '@/components/list/list-page';
import { useAuthStore } from '@/stores/auth.store';
import { PERMISSIONS } from '@erp/shared';
import { tx } from '@/lib/i18n/tx';
import { num, useDashboard } from '@/features/projects/api';
import { TaskList } from '@/features/projects/task-board';
import { TaskDialog } from '@/features/projects/task-dialog';
import { StatCard, UpdateStatusPill, hours, shortDay } from '@/features/projects/ui';
import { TimerCard } from './TimesheetsPage';

/** Personal project home: my open work, this week's hours and projects that need attention. */
export function ProjectDashboardPage() {
  const { data: d, isLoading } = useDashboard();
  const canLog = useAuthStore((s) => s.hasPermission)(PERMISSIONS.projectTimesheet.log);
  const [task, setTask] = useState<string | null>(null);
  if (isLoading || !d) return <Skeleton className="h-96 w-full" />;
  const chart = d.hoursByDay.map((h) => ({ day: shortDay(h.date), hours: num(h.hours) }));
  return (
    <div className="space-y-4">
      <ListPageHeader title={tx('Project dashboard')} description={tx('Your work across all projects at a glance.')} icon={LayoutDashboard} />
      {canLog && <TimerCard />}
      <div className="grid gap-3 sm:grid-cols-3 lg:grid-cols-6">
        <StatCard label={tx('My open tasks')} value={d.myTasks.open} />
        <StatCard label={tx('Overdue')} value={d.myTasks.overdue} tone={d.myTasks.overdue ? 'danger' : undefined} />
        <StatCard label={tx('Due today')} value={d.myTasks.dueToday} tone={d.myTasks.dueToday ? 'warning' : undefined} />
        <StatCard label={tx('Due this week')} value={d.myTasks.dueThisWeek} />
        <StatCard label={tx('Blocked')} value={d.myTasks.blocked} tone={d.myTasks.blocked ? 'warning' : undefined} />
        <StatCard label={tx('Done this week')} value={d.myTasks.doneThisWeek} tone="success" />
      </div>
      <div className="grid gap-4 lg:grid-cols-3">
        <section className="space-y-2 lg:col-span-2">
          <div className="flex items-center justify-between">
            <h2 className="text-sm font-semibold">{tx('Focus: due in the next 7 days')}</h2>
            <Link to="/projects/my-tasks" className="text-xs text-primary hover:underline">{tx('All my tasks')} →</Link>
          </div>
          {d.focus.length === 0
            ? <p className="rounded-xl border bg-card p-6 text-center text-sm text-muted-foreground">{tx('Nothing due this week.')}</p>
            : <TaskList tasks={d.focus} onOpen={setTask} showProject />}
        </section>
        <div className="space-y-4">
          <section className="rounded-xl border bg-card p-4 shadow-sm">
            <div className="mb-2 flex items-center justify-between">
              <h2 className="text-sm font-semibold">{tx('My hours this week')}</h2>
              <span className="text-sm font-semibold tabular-nums">{hours(d.weekHours)}</span>
            </div>
            <div className="h-40">
              <ResponsiveContainer width="100%" height="100%">
                <BarChart data={chart}>
                  <XAxis dataKey="day" tick={{ fontSize: 11 }} tickLine={false} axisLine={false} />
                  <YAxis tick={{ fontSize: 11 }} tickLine={false} axisLine={false} width={24} />
                  <Tooltip formatter={(v: number) => hours(v)} cursor={{ fill: 'hsl(var(--muted))' }} />
                  <Bar dataKey="hours" fill="hsl(var(--primary))" radius={[4, 4, 0, 0]} />
                </BarChart>
              </ResponsiveContainer>
            </div>
            <Link to="/projects/timesheets" className="text-xs text-primary hover:underline">{tx('Open timesheet')} →</Link>
          </section>
          <section className="rounded-xl border bg-card p-4 shadow-sm">
            <h2 className="mb-2 flex items-center gap-2 text-sm font-semibold"><AlertTriangle className="h-4 w-4 text-amber-500" />{tx('Projects needing attention')}</h2>
            {d.projectsAtRisk.length === 0 ? <p className="text-sm text-muted-foreground">{tx('All your projects look healthy.')}</p> : (
              <ul className="space-y-2">
                {d.projectsAtRisk.map((p) => (
                  <li key={p.id}>
                    <Link to={`/projects/${p.id}`} className="block rounded-md p-1.5 hover:bg-muted">
                      <div className="flex items-center gap-2 text-sm">
                        <span className="font-mono text-xs text-muted-foreground">{p.code}</span>
                        <span className="truncate font-medium">{p.name}</span>
                      </div>
                      <div className="mt-0.5 flex flex-wrap items-center gap-2 text-xs text-muted-foreground">
                        <UpdateStatusPill status={p.lastUpdateStatus} />
                        {p.overdue > 0 && <span className="text-rose-600">{tx('{{count}} overdue', { count: p.overdue })}</span>}
                        {p.endDate && p.endDate < d.today && <span className="text-rose-600">{tx('Past end date')}</span>}
                      </div>
                    </Link>
                  </li>
                ))}
              </ul>
            )}
          </section>
        </div>
      </div>
      <TaskDialog taskId={task} onClose={() => setTask(null)} onOpenTask={setTask} />
    </div>
  );
}

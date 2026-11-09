import { useState } from 'react';
import { BarChart3 } from 'lucide-react';
import { Bar, BarChart, ResponsiveContainer, Tooltip, XAxis, YAxis, Legend } from 'recharts';
import { Input } from '@/components/ui/input';
import { Skeleton } from '@/components/ui/skeleton';
import { ListPageHeader } from '@/components/list/list-page';
import { cn } from '@/lib/utils';
import { tx } from '@/lib/i18n/tx';
import { num, useProjectBoard, useTaskAnalysis, useTimeAnalysis, useWorkload } from '@/features/projects/api';
import { hours, selectClass } from '@/features/projects/ui';

type Tab = 'tasks' | 'time' | 'workload';

const TASK_GROUPS = [
  ['project', tx('Project')], ['stage', tx('Stage')], ['assignee', tx('Assignee')], ['tag', tx('Tag')],
  ['priority', tx('Priority')], ['milestone', tx('Milestone')], ['month', tx('Month created')],
] as const;
const TIME_GROUPS = [
  ['project', tx('Project')], ['task', tx('Task')], ['user', tx('Person')], ['week', tx('Week')], ['month', tx('Month')], ['date', tx('Day')],
] as const;

/** Task analysis, timesheet analysis and team workload across the projects the user can see. */
export function ProjectReportsPage() {
  const [tab, setTab] = useState<Tab>('tasks');
  const [projectId, setProjectId] = useState('');
  const [from, setFrom] = useState('');
  const [to, setTo] = useState('');
  const { data: projects = [] } = useProjectBoard({ status: '' });
  return (
    <div className="space-y-4">
      <ListPageHeader title={tx('Project reports')} description={tx('Throughput, time spent and who is carrying the load.')} icon={BarChart3} />
      <div className="flex flex-wrap items-center gap-2">
        <div className="flex rounded-lg border bg-muted/40 p-0.5">
          {([['tasks', tx('Task analysis')], ['time', tx('Timesheet analysis')], ['workload', tx('Workload')]] as const).map(([v, l]) => (
            <button key={v} type="button" onClick={() => setTab(v)} className={cn('rounded-md px-3 py-1 text-sm', tab === v ? 'bg-background font-medium shadow-sm' : 'text-muted-foreground hover:text-foreground')}>{l}</button>
          ))}
        </div>
        <select className={cn(selectClass, 'w-64')} value={projectId} onChange={(e) => setProjectId(e.target.value)}>
          <option value="">{tx('All projects')}</option>
          {projects.map((p) => <option key={p.id} value={p.id}>{p.code} · {p.name}</option>)}
        </select>
        {tab !== 'workload' && (
          <>
            <Input type="date" className="h-9 w-40" value={from} onChange={(e) => setFrom(e.target.value)} aria-label={tx('From')} />
            <span className="text-muted-foreground">→</span>
            <Input type="date" className="h-9 w-40" value={to} onChange={(e) => setTo(e.target.value)} aria-label={tx('To')} />
          </>
        )}
      </div>
      {tab === 'tasks' && <TaskReport projectId={projectId} from={from} to={to} />}
      {tab === 'time' && <TimeReport projectId={projectId} from={from} to={to} />}
      {tab === 'workload' && <WorkloadReport projectId={projectId} />}
    </div>
  );
}

function GroupPicker({ value, onChange, options }: { value: string; onChange: (v: string) => void; options: ReadonlyArray<readonly [string, string]> }) {
  return (
    <div className="flex flex-wrap items-center gap-1 text-sm">
      <span className="mr-1 text-muted-foreground">{tx('Group by')}</span>
      {options.map(([v, l]) => (
        <button key={v} type="button" onClick={() => onChange(v)} className={cn('rounded-full border px-2.5 py-0.5', value === v ? 'border-primary bg-primary/10 font-medium' : 'text-muted-foreground hover:bg-muted')}>{l}</button>
      ))}
    </div>
  );
}

function TaskReport({ projectId, from, to }: { projectId: string; from: string; to: string }) {
  const [picked, setGroupBy] = useState('project');
  // Stages belong to one project, so the API only groups by stage within a project.
  const groupBy = picked === 'stage' && !projectId ? 'project' : picked;
  const groups = projectId ? TASK_GROUPS : TASK_GROUPS.filter(([v]) => v !== 'stage');
  const { data = [], isLoading } = useTaskAnalysis({ groupBy, projectId: projectId || undefined, from: from || undefined, to: to || undefined });
  const chart = data.slice(0, 15).map((r) => ({ label: r.label ?? tx('(none)'), open: Number(r.open), closed: Number(r.closed) }));
  return (
    <div className="space-y-3">
      <GroupPicker value={groupBy} onChange={setGroupBy} options={groups} />
      {isLoading ? <Skeleton className="h-64 w-full" /> : (
        <>
          {chart.length > 0 && (
            <div className="h-64 rounded-xl border bg-card p-3 shadow-sm">
              <ResponsiveContainer width="100%" height="100%">
                <BarChart data={chart}>
                  <XAxis dataKey="label" tick={{ fontSize: 11 }} interval={0} angle={chart.length > 6 ? -20 : 0} textAnchor={chart.length > 6 ? 'end' : 'middle'} height={50} />
                  <YAxis tick={{ fontSize: 11 }} width={30} allowDecimals={false} />
                  <Tooltip />
                  <Legend />
                  <Bar dataKey="closed" stackId="a" name={tx('Closed')} fill="#10b981" />
                  <Bar dataKey="open" stackId="a" name={tx('Open')} fill="#0ea5e9" radius={[4, 4, 0, 0]} />
                </BarChart>
              </ResponsiveContainer>
            </div>
          )}
          <ReportTable
            head={[tx('Group'), tx('Total'), tx('Open'), tx('Closed'), tx('Overdue'), tx('Closed late'), tx('Planned'), tx('Spent'), tx('Avg days to close')]}
            rows={data.map((r) => [
              r.label ?? tx('(none)'), r.total, r.open, r.closed,
              <span key="o" className={cn(Number(r.overdue) > 0 && 'font-medium text-rose-600')}>{r.overdue}</span>,
              r.closedLate, hours(r.plannedHours), hours(r.effectiveHours), r.avgDaysToClose == null ? '—' : num(r.avgDaysToClose).toFixed(1),
            ])}
          />
        </>
      )}
    </div>
  );
}

function TimeReport({ projectId, from, to }: { projectId: string; from: string; to: string }) {
  const [groupBy, setGroupBy] = useState('project');
  const { data = [], isLoading } = useTimeAnalysis({ groupBy, projectId: projectId || undefined, from: from || undefined, to: to || undefined });
  const total = data.reduce((a, r) => a + num(r.hours), 0);
  const billable = data.reduce((a, r) => a + num(r.billableHours), 0);
  const chart = data.slice(0, 20).map((r) => ({ label: r.label ?? String(r.key ?? ''), billable: num(r.billableHours), other: num(r.hours) - num(r.billableHours) }));
  return (
    <div className="space-y-3">
      <GroupPicker value={groupBy} onChange={setGroupBy} options={TIME_GROUPS} />
      {isLoading ? <Skeleton className="h-64 w-full" /> : (
        <>
          <p className="text-sm text-muted-foreground">
            {tx('{{total}} logged, {{billable}} billable ({{pct}}%)', { total: hours(total), billable: hours(billable), pct: total ? Math.round((billable / total) * 100) : 0 })}
          </p>
          {chart.length > 0 && (
            <div className="h-64 rounded-xl border bg-card p-3 shadow-sm">
              <ResponsiveContainer width="100%" height="100%">
                <BarChart data={chart}>
                  <XAxis dataKey="label" tick={{ fontSize: 11 }} interval={0} angle={chart.length > 6 ? -20 : 0} textAnchor={chart.length > 6 ? 'end' : 'middle'} height={50} />
                  <YAxis tick={{ fontSize: 11 }} width={30} />
                  <Tooltip formatter={(v: number) => hours(v)} />
                  <Legend />
                  <Bar dataKey="billable" stackId="a" name={tx('Billable')} fill="#6366f1" />
                  <Bar dataKey="other" stackId="a" name={tx('Non-billable')} fill="#cbd5e1" radius={[4, 4, 0, 0]} />
                </BarChart>
              </ResponsiveContainer>
            </div>
          )}
          <ReportTable
            head={[tx('Group'), tx('Hours'), tx('Billable'), tx('Entries'), tx('People')]}
            rows={data.map((r) => [r.label ?? String(r.key ?? '—'), hours(r.hours), hours(r.billableHours), r.lines, r.people])}
          />
        </>
      )}
    </div>
  );
}

function WorkloadReport({ projectId }: { projectId: string }) {
  const { data = [], isLoading } = useWorkload(projectId || undefined);
  const max = Math.max(1, ...data.map((r) => Number(r.open)));
  if (isLoading) return <Skeleton className="h-64 w-full" />;
  return (
    <ReportTable
      head={[tx('Person'), tx('Open tasks'), tx('Overdue'), tx('Due this week'), tx('Remaining hours'), tx('Projects')]}
      rows={data.map((r) => [
        r.name || '—',
        <span key="b" className="flex items-center gap-2"><span className="h-2 rounded bg-sky-500" style={{ width: `${(Number(r.open) / max) * 120}px` }} />{r.open}</span>,
        <span key="o" className={cn(Number(r.overdue) > 0 && 'font-medium text-rose-600')}>{r.overdue}</span>,
        r.dueThisWeek, hours(r.remainingHours), r.projects,
      ])}
    />
  );
}

function ReportTable({ head, rows }: { head: string[]; rows: Array<Array<React.ReactNode>> }) {
  if (!rows.length) return <p className="rounded-xl border bg-card p-8 text-center text-sm text-muted-foreground">{tx('No data for these filters.')}</p>;
  return (
    <div className="overflow-x-auto rounded-xl border bg-card shadow-sm">
      <table className="w-full text-sm">
        <thead className="bg-muted/40 text-xs uppercase tracking-wide text-muted-foreground">
          <tr>{head.map((h, i) => <th key={h} className={cn('px-3 py-2 font-semibold', i === 0 ? 'text-left' : 'text-right')}>{h}</th>)}</tr>
        </thead>
        <tbody className="divide-y">
          {rows.map((r, i) => (
            <tr key={i} className="hover:bg-muted/30">
              {r.map((c, j) => <td key={j} className={cn('px-3 py-2', j === 0 ? 'font-medium' : 'text-right tabular-nums')}>{c}</td>)}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

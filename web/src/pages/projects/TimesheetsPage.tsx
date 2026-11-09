import { useEffect, useMemo, useState } from 'react';
import { ChevronLeft, ChevronRight, Clock, Lock, Play, Plus, Square, Timer as TimerIcon, Trash2 } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Skeleton } from '@/components/ui/skeleton';
import { ListPageHeader } from '@/components/list/list-page';
import { useAuthStore } from '@/stores/auth.store';
import { PERMISSIONS } from '@erp/shared';
import { cn } from '@/lib/utils';
import { tx } from '@/lib/i18n/tx';
import {
  num, useAssignableUsers, usePmMutation, useProjectBoard, useTasks, useTimer, useTimesheetLines, useWeek,
} from '@/features/projects/api';
import { addDaysIso, clock, hours, selectClass, shortDay, todayIso } from '@/features/projects/ui';

function mondayOf(iso: string) {
  const d = new Date(`${iso}T00:00:00`);
  const day = (d.getDay() + 6) % 7;
  return addDaysIso(iso, -day);
}

/** Weekly timesheet grid (type hours per day), the running timer and the detailed lines of the week. */
export function TimesheetsPage() {
  const canManage = useAuthStore((s) => s.hasPermission)(PERMISSIONS.projectTimesheet.manage);
  const me = useAuthStore((s) => s.user?.id);
  const [start, setStart] = useState(mondayOf(todayIso()));
  const [userId, setUserId] = useState('');
  const { data: users = [] } = useAssignableUsers();
  const { data: week, isLoading } = useWeek(start, userId || undefined);
  const { data: lines = [] } = useTimesheetLines({ userId: userId || me, from: start, to: addDaysIso(start, 6) }, !!me);
  const setCell = usePmMutation((v: { projectId: string; taskId?: string | null; date: string; hours: number }) => ({
    method: 'put', url: '/project-timesheets/week', body: { ...v, taskId: v.taskId ?? undefined, userId: userId || undefined },
  }));
  const removeLine = usePmMutation((id: string) => ({ method: 'delete', url: `/project-timesheets/${id}` }));
  const [extraRows, setExtraRows] = useState<Array<{ projectId: string; projectCode: string; projectName: string; taskId?: string; taskNumber?: string; taskTitle?: string }>>([]);
  useEffect(() => setExtraRows([]), [start, userId]);

  const rows = useMemo(() => {
    const base = week?.rows ?? [];
    const extra = extraRows
      .filter((x) => !base.some((r) => r.projectId === x.projectId && (r.taskId ?? null) === (x.taskId ?? null)))
      .map((x) => ({ ...x, hours: Array(7).fill(0), total: 0 }));
    return [...base, ...extra];
  }, [week, extraRows]);

  const today = todayIso();
  return (
    <div className="space-y-4">
      <ListPageHeader title={tx('Timesheets')} description={tx('Log time on project tasks. Entries flow to HR timesheets for approval and to project costing.')} icon={Clock} />
      <TimerCard />

      <div className="flex flex-wrap items-center gap-2">
        <Button variant="outline" size="icon" onClick={() => setStart(addDaysIso(start, -7))} aria-label={tx('Previous week')}><ChevronLeft className="h-4 w-4" /></Button>
        <Button variant="outline" size="sm" onClick={() => setStart(mondayOf(today))}>{tx('This week')}</Button>
        <Button variant="outline" size="icon" onClick={() => setStart(addDaysIso(start, 7))} aria-label={tx('Next week')}><ChevronRight className="h-4 w-4" /></Button>
        <span className="text-sm font-medium">{start} → {addDaysIso(start, 6)}</span>
        {canManage && (
          <select className={cn(selectClass, 'ml-auto w-56')} value={userId} onChange={(e) => setUserId(e.target.value)}>
            <option value="">{tx('My timesheet')}</option>
            {users.filter((u) => u.id !== me).map((u) => <option key={u.id} value={u.id}>{u.name || u.email}</option>)}
          </select>
        )}
      </div>

      {isLoading || !week ? <Skeleton className="h-64 w-full" /> : (
        <div className="overflow-x-auto rounded-xl border bg-card shadow-sm">
          <table className="w-full text-sm">
            <thead className="bg-muted/40 text-xs text-muted-foreground">
              <tr>
                <th className="min-w-[260px] px-3 py-2 text-left font-semibold uppercase">{tx('Project / task')}</th>
                {week.days.map((d) => (
                  <th key={d} className={cn('w-20 px-1 py-2 text-center font-semibold', d === today && 'text-primary')}>{shortDay(d)}</th>
                ))}
                <th className="w-20 px-3 py-2 text-right font-semibold uppercase">{tx('Total')}</th>
              </tr>
            </thead>
            <tbody className="divide-y">
              {rows.map((r) => (
                <tr key={`${r.projectId}/${r.taskId ?? ''}`}>
                  <td className="px-3 py-1.5">
                    <p className="truncate font-medium">{r.taskTitle ?? <span className="italic text-muted-foreground">{tx('No task')}</span>}</p>
                    <p className="truncate text-xs text-muted-foreground">{r.projectCode} · {r.projectName}{r.taskNumber ? ` · ${r.taskNumber}` : ''}</p>
                  </td>
                  {week.days.map((d, i) => (
                    <td key={d} className={cn('px-1 py-1', d === today && 'bg-primary/5')}>
                      <Input
                        type="number"
                        min={0}
                        max={24}
                        step="0.25"
                        className="h-8 px-1 text-center tabular-nums"
                        defaultValue={num(r.hours[i]) || ''}
                        key={`${r.projectId}-${r.taskId}-${d}-${r.hours[i]}`}
                        onBlur={(e) => {
                          const v = Number(e.target.value || 0);
                          if (v !== num(r.hours[i])) setCell.mutate({ projectId: r.projectId, taskId: r.taskId, date: d, hours: v });
                        }}
                      />
                    </td>
                  ))}
                  <td className="px-3 py-1.5 text-right font-medium tabular-nums">{hours(r.total)}</td>
                </tr>
              ))}
              <AddRow onAdd={(row) => setExtraRows((x) => [...x, row])} />
            </tbody>
            <tfoot className="border-t bg-muted/30 text-sm font-semibold">
              <tr>
                <td className="px-3 py-2">{tx('Total')}</td>
                {week.totals.map((t, i) => <td key={i} className={cn('px-1 py-2 text-center tabular-nums', num(t) > 10 && 'text-amber-600')}>{num(t) ? hours(t, false) : ''}</td>)}
                <td className="px-3 py-2 text-right tabular-nums">{hours(week.total)}</td>
              </tr>
            </tfoot>
          </table>
        </div>
      )}

      {lines.length > 0 && (
        <section>
          <h2 className="mb-2 text-sm font-semibold">{tx('Entries this week')}</h2>
          <div className="divide-y rounded-xl border bg-card text-sm shadow-sm">
            {lines.map((l) => (
              <div key={l.id} className="flex items-center gap-3 px-3 py-2">
                <span className="w-24 text-xs text-muted-foreground">{l.date}</span>
                <span className="min-w-0 flex-1 truncate">
                  <span className="font-medium">{l.taskTitle ?? l.projectName}</span>
                  <span className="text-muted-foreground"> · {l.projectCode}{l.description ? ` — ${l.description}` : ''}</span>
                </span>
                {!l.isBillable && <span className="text-xs text-muted-foreground">{tx('non-billable')}</span>}
                {l.hrStatus && <span className="rounded bg-muted px-1.5 text-[10px] uppercase text-muted-foreground">{l.hrStatus}</span>}
                <span className="w-16 text-right font-medium tabular-nums">{hours(l.hours)}</span>
                {l.locked
                  ? <Lock className="h-4 w-4 text-muted-foreground" aria-label={tx('Approved in HR — locked')} />
                  : <button type="button" onClick={() => removeLine.mutate(l.id)} aria-label={tx('Delete')}><Trash2 className="h-4 w-4 text-muted-foreground hover:text-rose-600" /></button>}
              </div>
            ))}
          </div>
        </section>
      )}
    </div>
  );
}

function AddRow({ onAdd }: { onAdd: (r: { projectId: string; projectCode: string; projectName: string; taskId?: string; taskNumber?: string; taskTitle?: string }) => void }) {
  const [open, setOpen] = useState(false);
  const [projectId, setProjectId] = useState('');
  const { data: projects = [] } = useProjectBoard({ filter: 'mine', status: 'open' });
  const { data: tasks } = useTasks({ projectId, state: 'open', limit: 1000 }, open && !!projectId);
  if (!open) {
    return (
      <tr>
        <td colSpan={9} className="px-3 py-2">
          <button type="button" onClick={() => setOpen(true)} className="flex items-center gap-1 text-sm text-primary hover:underline"><Plus className="h-4 w-4" />{tx('Add a line')}</button>
        </td>
      </tr>
    );
  }
  const p = projects.find((x) => x.id === projectId);
  return (
    <tr>
      <td colSpan={9} className="px-3 py-2">
        <div className="flex flex-wrap gap-2">
          <select className={cn(selectClass, 'w-64')} value={projectId} onChange={(e) => setProjectId(e.target.value)}>
            <option value="">{tx('Choose a project…')}</option>
            {projects.filter((x) => x.allowTimesheets).map((x) => <option key={x.id} value={x.id}>{x.code} · {x.name}</option>)}
          </select>
          {p && (
            <select
              className={cn(selectClass, 'w-72')}
              defaultValue=""
              onChange={(e) => {
                const t = tasks?.data.find((x) => x.id === e.target.value);
                onAdd({ projectId: p.id, projectCode: p.code, projectName: p.name, taskId: t?.id, taskNumber: t?.number, taskTitle: t?.title });
                setOpen(false); setProjectId('');
              }}
            >
              <option value="" disabled>{tx('Choose a task…')}</option>
              <option value="__none">{tx('Whole project (no task)')}</option>
              {(tasks?.data ?? []).map((t) => <option key={t.id} value={t.id}>{t.number} · {t.title}</option>)}
            </select>
          )}
          <Button variant="ghost" size="sm" onClick={() => setOpen(false)}>{tx('Cancel')}</Button>
        </div>
      </td>
    </tr>
  );
}

/** The user's running timer, with a live clock. */
export function TimerCard() {
  const { data: timer } = useTimer();
  const [elapsed, setElapsed] = useState(0);
  const [note, setNote] = useState('');
  useEffect(() => {
    if (!timer?.running || !timer.startedAt) return;
    const started = new Date(timer.startedAt).getTime();
    const tick = () => setElapsed(Math.max(0, (Date.now() - started) / 1000));
    tick();
    const h = window.setInterval(tick, 1000);
    return () => window.clearInterval(h);
  }, [timer?.running, timer?.startedAt]);
  const stop = usePmMutation(() => ({ method: 'post', url: '/project-timesheets/timer/stop', body: { description: note || undefined } }), { success: tx('Time logged') });
  const discard = usePmMutation(() => ({ method: 'post', url: '/project-timesheets/timer/discard', body: {} }));
  if (!timer?.running) {
    return (
      <div className="flex items-center gap-3 rounded-xl border border-dashed bg-card/50 px-4 py-3 text-sm text-muted-foreground">
        <TimerIcon className="h-5 w-5" />
        <span>{tx('No timer running. Start one from any task with')} <Play className="inline h-3.5 w-3.5" /> {tx('Start timer')}.</span>
      </div>
    );
  }
  return (
    <div className="flex flex-wrap items-center gap-3 rounded-xl border border-primary/40 bg-primary/5 px-4 py-3">
      <span className="relative flex h-3 w-3"><span className="absolute inline-flex h-full w-full animate-ping rounded-full bg-rose-400 opacity-75" /><span className="relative inline-flex h-3 w-3 rounded-full bg-rose-500" /></span>
      <span className="font-mono text-2xl font-semibold tabular-nums">{clock(elapsed)}</span>
      <div className="min-w-0">
        <p className="truncate font-medium">{timer.taskTitle}</p>
        <p className="truncate text-xs text-muted-foreground">{timer.projectCode} · {timer.projectName} · {timer.taskNumber}</p>
      </div>
      <Input className="ml-auto h-9 w-64" placeholder={tx('What are you working on?')} value={note} onChange={(e) => setNote(e.target.value)} />
      <Button onClick={() => stop.mutate(undefined, { onSuccess: () => setNote('') })} disabled={stop.isPending}><Square className="mr-1.5 h-3.5 w-3.5 fill-current" />{tx('Stop & log')}</Button>
      <Button variant="ghost" onClick={() => window.confirm(tx('Discard this timer without logging time?')) && discard.mutate()}>{tx('Discard')}</Button>
    </div>
  );
}

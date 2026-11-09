import { Link } from 'react-router-dom';
import { CalendarClock, Link2, Milestone as MilestoneIcon } from 'lucide-react';
import { Skeleton } from '@/components/ui/skeleton';
import { money } from '@/lib/format';
import { cn } from '@/lib/utils';
import { tx } from '@/lib/i18n/tx';
import { num, useOverview, type Workspace } from '@/features/projects/api';
import { AvatarStack, ProgressBar, StatCard, UpdateStatusPill, hours, shortDate } from '@/features/projects/ui';

export function OverviewTab({ ws, onOpenTask }: { ws: Workspace; onOpenTask: (id: string) => void }) {
  const { data: o, isLoading } = useOverview(ws.id);
  if (isLoading || !o) return <Skeleton className="h-96 w-full" />;
  const used = o.hours.usedPercent == null ? null : num(o.hours.usedPercent);
  const maxStage = Math.max(1, ...o.byStage.map((s) => s.count));
  const fin = o.financials;
  return (
    <div className="space-y-4">
      <div className="grid gap-3 sm:grid-cols-3 lg:grid-cols-6">
        <StatCard label={tx('Progress')} value={`${o.tasks.progress}%`} hint={tx('{{closed}} of {{total}} tasks closed', { closed: o.tasks.closed, total: o.tasks.total })} />
        <StatCard label={tx('Open tasks')} value={o.tasks.open} />
        <StatCard label={tx('Overdue')} value={o.tasks.overdue} tone={o.tasks.overdue ? 'danger' : undefined} />
        <StatCard label={tx('Blocked')} value={o.tasks.blocked} tone={o.tasks.blocked ? 'warning' : undefined} />
        <StatCard label={tx('Unassigned')} value={o.tasks.unassigned} />
        <StatCard
          label={tx('Hours used')}
          value={used == null ? hours(o.hours.effective) : `${used}%`}
          hint={tx('{{spent}} of {{planned}} planned', { spent: hours(o.hours.effective), planned: hours(o.hours.planned) })}
          tone={used != null && used > 100 ? 'danger' : undefined}
        />
      </div>

      <div className="grid gap-4 lg:grid-cols-3">
        <section className="rounded-xl border bg-card p-4 shadow-sm lg:col-span-2">
          <h3 className="mb-3 text-sm font-semibold">{tx('Tasks by stage')}</h3>
          <div className="space-y-2">
            {o.byStage.map((s) => (
              <div key={s.id} className="flex items-center gap-3 text-sm">
                <span className="w-32 truncate text-muted-foreground">{s.name}</span>
                <div className="h-5 flex-1 overflow-hidden rounded bg-muted">
                  <div
                    className={cn('h-full rounded', s.kind === 'done' ? 'bg-emerald-500' : s.kind === 'cancelled' ? 'bg-slate-400' : 'bg-sky-500')}
                    style={{ width: `${(s.count / maxStage) * 100}%` }}
                  />
                </div>
                <span className="w-8 text-right tabular-nums">{s.count}</span>
              </div>
            ))}
          </div>

          <h3 className="mb-2 mt-6 text-sm font-semibold">{tx('Workload by person')}</h3>
          {o.byAssignee.length === 0 ? <p className="text-sm text-muted-foreground">{tx('No assigned tasks yet.')}</p> : (
            <table className="w-full text-sm">
              <thead className="text-xs text-muted-foreground">
                <tr><th className="py-1 text-left font-medium">{tx('Person')}</th><th className="text-right font-medium">{tx('Open')}</th><th className="text-right font-medium">{tx('Overdue')}</th><th className="text-right font-medium">{tx('Done')}</th><th className="text-right font-medium">{tx('Planned')}</th></tr>
              </thead>
              <tbody className="divide-y">
                {o.byAssignee.map((a) => (
                  <tr key={a.userId}>
                    <td className="py-1.5"><span className="flex items-center gap-2"><AvatarStack users={[{ id: a.userId, name: a.name }]} />{a.name}</span></td>
                    <td className="text-right tabular-nums">{a.open}</td>
                    <td className={cn('text-right tabular-nums', a.overdue > 0 && 'font-medium text-rose-600')}>{a.overdue}</td>
                    <td className="text-right tabular-nums text-muted-foreground">{a.closed}</td>
                    <td className="text-right tabular-nums">{hours(a.plannedHours)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </section>

        <div className="space-y-4">
          <section className="rounded-xl border bg-card p-4 shadow-sm">
            <h3 className="mb-2 flex items-center gap-2 text-sm font-semibold">{tx('Latest status')} <UpdateStatusPill status={o.lastUpdate?.status} /></h3>
            {o.lastUpdate ? (
              <>
                <ProgressBar value={o.lastUpdate.progress} />
                <p className="mt-2 line-clamp-4 whitespace-pre-wrap text-sm">{o.lastUpdate.summary || '—'}</p>
                <p className="mt-1 text-xs text-muted-foreground">{o.lastUpdate.date}</p>
              </>
            ) : <p className="text-sm text-muted-foreground">{tx('No status update posted yet.')}</p>}
          </section>

          <section className="rounded-xl border bg-card p-4 shadow-sm">
            <h3 className="mb-2 flex items-center gap-2 text-sm font-semibold"><CalendarClock className="h-4 w-4" />{tx('Upcoming deadlines')}</h3>
            {o.upcoming.length === 0 ? <p className="text-sm text-muted-foreground">{tx('Nothing scheduled.')}</p> : (
              <ul className="space-y-1.5">
                {o.upcoming.map((t) => (
                  <li key={t.id}>
                    <button type="button" onClick={() => onOpenTask(t.id)} className="flex w-full items-center gap-2 text-left text-sm hover:underline">
                      <span className={cn('w-14 shrink-0 text-xs', t.overdue ? 'font-medium text-rose-600' : 'text-muted-foreground')}>{shortDate(t.deadline)}</span>
                      <span className="truncate">{t.title}</span>
                    </button>
                  </li>
                ))}
              </ul>
            )}
          </section>

          {o.milestones.length > 0 && (
            <section className="rounded-xl border bg-card p-4 shadow-sm">
              <h3 className="mb-2 flex items-center gap-2 text-sm font-semibold"><MilestoneIcon className="h-4 w-4" />{tx('Milestones')}</h3>
              <ul className="space-y-2">
                {o.milestones.map((m) => (
                  <li key={m.id} className="text-sm">
                    <div className="flex justify-between">
                      <span className={cn(m.isReached && 'text-muted-foreground line-through', m.late && 'text-rose-600')}>{m.name}</span>
                      <span className="text-xs text-muted-foreground">{shortDate(m.deadline)}</span>
                    </div>
                    {m.taskCount > 0 && <ProgressBar value={(m.doneCount / m.taskCount) * 100} tone="bg-violet-500" className="mt-1" />}
                  </li>
                ))}
              </ul>
            </section>
          )}

          {(o.links.salesOrder?.label || o.links.deal?.label || o.links.purchaseRequests > 0 || o.links.linkedTasks.length > 0) && (
            <section className="rounded-xl border bg-card p-4 shadow-sm">
              <h3 className="mb-2 flex items-center gap-2 text-sm font-semibold"><Link2 className="h-4 w-4" />{tx('Linked records')}</h3>
              <ul className="space-y-1 text-sm">
                {o.links.salesOrder?.label && <li>{tx('Sales order')}: {o.links.salesOrder.path ? <Link className="text-primary hover:underline" to={o.links.salesOrder.path}>{o.links.salesOrder.label}</Link> : o.links.salesOrder.label}</li>}
                {o.links.deal?.label && <li>{tx('Deal')}: {o.links.deal.path ? <Link className="text-primary hover:underline" to={o.links.deal.path}>{o.links.deal.label}</Link> : o.links.deal.label}</li>}
                {o.links.purchaseRequests > 0 && <li>{tx('{{count}} purchase request(s)', { count: o.links.purchaseRequests })}</li>}
                {o.links.linkedTasks.map((l) => <li key={l.resType} className="text-muted-foreground">{l.resType}: {l.count}</li>)}
              </ul>
            </section>
          )}
        </div>
      </div>

      {fin && (
        <section className="rounded-xl border bg-card p-4 shadow-sm">
          <h3 className="mb-3 text-sm font-semibold">{tx('Job costing')}</h3>
          <div className="grid gap-3 sm:grid-cols-3 lg:grid-cols-7">
            {([
              ['contractValue', tx('Contract value')], ['budget', tx('Budget')], ['committed', tx('Committed')],
              ['actual', tx('Actual cost')], ['costToComplete', tx('Cost to complete')], ['eac', tx('Estimate at completion')],
              ['variance', tx('Variance')],
            ] as const).map(([k, label]) => (
              <div key={k}>
                <p className="text-xs text-muted-foreground">{label}</p>
                <p className={cn('font-semibold tabular-nums', k === 'variance' && num(fin[k] as string) < 0 && 'text-rose-600')}>
                  {fin[k] == null ? '—' : money(fin[k] as string)}
                </p>
              </div>
            ))}
          </div>
        </section>
      )}
    </div>
  );
}

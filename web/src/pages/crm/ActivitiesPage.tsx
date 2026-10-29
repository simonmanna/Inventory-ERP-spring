import { useState } from 'react';
import { Link } from 'react-router-dom';
import { CalendarCheck, Check, X } from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { Button } from '@/components/ui/button';
import { Skeleton } from '@/components/ui/skeleton';
import { ListPageHeader } from '@/components/list';
import { useAuthStore } from '@/stores/auth.store';
import { dateTime, relativeTime } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';
import { cn } from '@/lib/utils';
import { crmApi, useCrmLookups, useCrmMutation, usePlanner } from '@/features/crm/suite-api';
import { ActivityBadge, typeLabel } from './crm-ui';
import { DoneDialog, ScheduleDialog } from './crm-dialogs';

const TABS = [
  { key: 'overdue', label: tx('Overdue') },
  { key: 'today', label: tx('Today') },
  { key: 'upcoming', label: tx('Upcoming') },
  { key: 'done', label: tx('Done') },
] as const;

/** "My activities": calls, meetings, emails and to-dos by due state, with reminders (ADR-S010). */
export function ActivitiesPage() {
  const [state, setState] = useState<string>('today');
  const [scope, setScope] = useState('mine');
  const [assignee, setAssignee] = useState('');
  const [type, setType] = useState('');
  const [doneId, setDoneId] = useState<string | null>(null);
  const [scheduling, setScheduling] = useState(false);
  const viewAll = useAuthStore((s) => s.hasPermission(PERMISSIONS.crm.dealViewAll));
  const { data: lk } = useCrmLookups();
  const { data, isLoading } = usePlanner({ state, scope, assignedToId: scope === 'all' ? assignee || undefined : undefined, type: type || undefined });
  const cancel = useCrmMutation(crmApi.cancelActivity);
  const counts = data?.counts;

  return (
    <div className="space-y-4">
      <ListPageHeader icon={CalendarCheck} title={tx('Activities')} description={tx('Your planned calls, meetings and follow-ups.')}
        actions={<Button onClick={() => setScheduling(true)}>{tx('Schedule activity')}</Button>} />
      <div className="flex flex-wrap items-center gap-2">
        <div className="flex rounded-lg border bg-card p-0.5">
          {TABS.map((t) => {
            const n = t.key === 'done' ? null : counts?.[t.key as 'overdue' | 'today' | 'upcoming'];
            return (
              <button key={t.key} onClick={() => setState(t.key)} className={cn('flex items-center gap-1.5 rounded-md px-3 py-1.5 text-sm', state === t.key ? 'bg-primary text-primary-foreground' : 'hover:bg-muted')}>
                {t.label}
                {n != null && n > 0 && <span className={cn('rounded-full px-1.5 text-xs', state === t.key ? 'bg-primary-foreground/20' : t.key === 'overdue' ? 'bg-rose-100 text-rose-700' : 'bg-muted')}>{n}</span>}
              </button>
            );
          })}
        </div>
        <select className="h-9 rounded-md border bg-background px-2 text-sm" value={type} onChange={(e) => setType(e.target.value)}>
          <option value="">{tx('All types')}</option>
          {(['call', 'email', 'meeting', 'task'] as const).map((t) => <option key={t} value={t}>{typeLabel(t)}</option>)}
        </select>
        {viewAll && (
          <>
            <select className="h-9 rounded-md border bg-background px-2 text-sm" value={scope} onChange={(e) => setScope(e.target.value)}>
              <option value="mine">{tx('Mine')}</option>
              <option value="all">{tx('Everyone')}</option>
            </select>
            {scope === 'all' && (
              <select className="h-9 rounded-md border bg-background px-2 text-sm" value={assignee} onChange={(e) => setAssignee(e.target.value)}>
                <option value="">{tx('Any salesperson')}</option>
                {(lk?.users ?? []).map((u) => <option key={u.id} value={u.id}>{u.name}</option>)}
              </select>
            )}
          </>
        )}
      </div>
      <div className="divide-y rounded-xl border bg-card shadow-sm">
        {isLoading && <div className="p-3"><Skeleton className="h-40 w-full" /></div>}
        {data?.data.map((a) => (
          <div key={a.id} className="flex flex-wrap items-start gap-3 p-3 text-sm">
            <ActivityBadge state={a.state === 'done' ? 'none' : (a.state ?? 'planned')} type={a.type} />
            <div className="min-w-0 flex-1">
              <p className="font-medium">{a.title} <span className="text-xs font-normal text-muted-foreground">· {typeLabel(a.type)}</span></p>
              <p className="text-xs text-muted-foreground">
                {a.dealId ? <Link className="text-primary hover:underline" to={`/crm/opportunities/${a.dealId}`}>{a.dealName}</Link> : null}
                {a.partnerName ? ` · ${a.partnerName}` : ''}
                {a.assignedToName ? ` · ${a.assignedToName}` : ''}
              </p>
              {a.outcome && <p className="mt-0.5 text-xs">{tx('Outcome')}: {a.outcome}</p>}
            </div>
            <div className={cn('text-right text-xs', a.state === 'overdue' ? 'text-rose-600' : a.state === 'today' ? 'text-amber-600' : 'text-muted-foreground')}>
              {a.state === 'done' ? dateTime(a.completedAt) : a.dueAt ? <>{relativeTime(a.dueAt)}<br />{dateTime(a.dueAt)}</> : tx('No due date')}
            </div>
            {a.state !== 'done' && (
              <div className="flex gap-1">
                <Button size="sm" variant="outline" onClick={() => setDoneId(a.id)}><Check className="mr-1 h-3.5 w-3.5" />{tx('Done')}</Button>
                <Button size="sm" variant="ghost" title={tx('Cancel')} onClick={() => cancel.mutate(a.id)}><X className="h-4 w-4" /></Button>
              </div>
            )}
          </div>
        ))}
        {data && data.data.length === 0 && <p className="p-8 text-center text-sm text-muted-foreground">{tx('Nothing here. 🎉')}</p>}
      </div>
      <DoneDialog open={!!doneId} onOpenChange={(v) => !v && setDoneId(null)} activityId={doneId ?? ''} />
      <ScheduleDialog open={scheduling} onOpenChange={setScheduling} />
    </div>
  );
}

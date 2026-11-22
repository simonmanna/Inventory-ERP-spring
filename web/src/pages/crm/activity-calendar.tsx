import { useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { toast } from 'sonner';
import { ChevronLeft, ChevronRight, Copy, Rss } from 'lucide-react';
import { api } from '@/lib/api';
import { apiErrorMessage } from '@/lib/api-error';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Skeleton } from '@/components/ui/skeleton';
import { Dialog, DialogContent, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { dateTime } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';
import { cn } from '@/lib/utils';
import { usePlanner, type PlannedActivity } from '@/features/crm/suite-api';
import { TYPE_ICON, typeLabel } from './crm-ui';

const iso = (d: Date) => `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
const addDays = (d: Date, n: number) => new Date(d.getFullYear(), d.getMonth(), d.getDate() + n);
/** Monday of the week containing {@code d}. */
const weekStart = (d: Date) => addDays(d, -((d.getDay() + 6) % 7));
const WEEKDAYS = [tx('Mon'), tx('Tue'), tx('Wed'), tx('Thu'), tx('Fri'), tx('Sat'), tx('Sun')];

/** Week / month calendar of planned and done CRM activities (CRM enhancements T2b). */
export function ActivityCalendar({ scope, assignedToId, type, onDone }: {
  scope: string; assignedToId?: string; type?: string; onDone?: (id: string) => void;
}) {
  const [mode, setMode] = useState<'week' | 'month'>('week');
  const [anchor, setAnchor] = useState(() => new Date());
  const navigate = useNavigate();

  const days = useMemo(() => {
    if (mode === 'week') {
      const s = weekStart(anchor);
      return Array.from({ length: 7 }, (_, i) => addDays(s, i));
    }
    const first = new Date(anchor.getFullYear(), anchor.getMonth(), 1);
    const s = weekStart(first);
    const last = new Date(anchor.getFullYear(), anchor.getMonth() + 1, 0);
    const n = Math.ceil(((last.getTime() - s.getTime()) / 86_400_000 + 1) / 7) * 7;
    return Array.from({ length: n }, (_, i) => addDays(s, i));
  }, [mode, anchor]);

  const from = iso(days[0]);
  const to = iso(days[days.length - 1]);
  const { data, isLoading } = usePlanner({ state: 'range', from, to, scope, assignedToId, type });
  const byDay = useMemo(() => {
    const m = new Map<string, PlannedActivity[]>();
    for (const a of data?.data ?? []) {
      if (!a.dueAt) continue;
      const k = iso(new Date(a.dueAt));
      m.set(k, [...(m.get(k) ?? []), a]);
    }
    return m;
  }, [data]);

  const today = iso(new Date());
  const step = (n: number) => setAnchor((a) => (mode === 'week' ? addDays(a, 7 * n) : new Date(a.getFullYear(), a.getMonth() + n, 1)));
  const title = mode === 'month'
    ? anchor.toLocaleDateString(undefined, { month: 'long', year: 'numeric' })
    : `${days[0].toLocaleDateString(undefined, { day: 'numeric', month: 'short' })} – ${days[6].toLocaleDateString(undefined, { day: 'numeric', month: 'short', year: 'numeric' })}`;
  const open = (a: PlannedActivity) => {
    if (a.dealId) navigate(`/crm/${a.dealKind === 'lead' ? 'leads' : 'opportunities'}/${a.dealId}`);
    else if (a.state !== 'done') onDone?.(a.id);
  };

  const Item = ({ a, compact }: { a: PlannedActivity; compact?: boolean }) => {
    const Icon = TYPE_ICON[a.type] ?? TYPE_ICON.task;
    const time = a.dueAt ? new Date(a.dueAt).toLocaleTimeString(undefined, { hour: '2-digit', minute: '2-digit' }) : '';
    return (
      <button type="button" onClick={() => open(a)} title={`${typeLabel(a.type)} · ${a.title}${a.dealName ? ` · ${a.dealName}` : ''} · ${a.dueAt ? dateTime(a.dueAt) : ''}`}
        className={cn('flex w-full items-center gap-1.5 truncate rounded-md border-l-2 px-1.5 py-1 text-left text-xs transition-colors hover:bg-muted',
          a.state === 'done' ? 'border-muted-foreground/30 text-muted-foreground line-through'
            : a.state === 'overdue' ? 'border-rose-500 bg-rose-50 dark:bg-rose-950/40'
              : a.state === 'today' ? 'border-amber-500 bg-amber-50 dark:bg-amber-950/40' : 'border-primary bg-primary/5')}>
        <Icon className="h-3 w-3 shrink-0" aria-hidden />
        {!compact && <span className="shrink-0 tabular-nums text-muted-foreground">{time}</span>}
        <span className="truncate">{a.title}</span>
      </button>
    );
  };

  return (
    <div className="rounded-xl border bg-card shadow-sm">
      <div className="flex flex-wrap items-center gap-2 border-b p-2">
        <Button size="sm" variant="ghost" onClick={() => step(-1)} aria-label={tx('Previous')}><ChevronLeft className="h-4 w-4" /></Button>
        <Button size="sm" variant="outline" onClick={() => setAnchor(new Date())}>{tx('Today')}</Button>
        <Button size="sm" variant="ghost" onClick={() => step(1)} aria-label={tx('Next')}><ChevronRight className="h-4 w-4" /></Button>
        <h2 className="ml-1 text-sm font-semibold">{title}</h2>
        <div className="ml-auto flex rounded-md border bg-background p-0.5">
          {(['week', 'month'] as const).map((m) => (
            <button key={m} onClick={() => setMode(m)} className={cn('rounded px-2.5 py-1 text-xs', mode === m ? 'bg-primary text-primary-foreground' : 'hover:bg-muted')}>
              {m === 'week' ? tx('Week') : tx('Month')}
            </button>
          ))}
        </div>
      </div>
      {isLoading && <div className="p-3"><Skeleton className="h-64 w-full" /></div>}
      {!isLoading && (
        <div className="overflow-x-auto">
          <div className="grid min-w-[720px] grid-cols-7">
            {WEEKDAYS.map((w) => <div key={w} className="border-b px-2 py-1.5 text-xs font-semibold uppercase tracking-wide text-muted-foreground">{w}</div>)}
            {days.map((d) => {
              const k = iso(d);
              const items = byDay.get(k) ?? [];
              const outside = mode === 'month' && d.getMonth() !== anchor.getMonth();
              const max = mode === 'month' ? 3 : 50;
              return (
                <div key={k} className={cn('min-h-28 space-y-1 border-b border-r p-1.5', mode === 'week' && 'min-h-72', outside && 'bg-muted/30')}>
                  <div className={cn('mb-1 flex h-6 w-6 items-center justify-center rounded-full text-xs', k === today ? 'bg-primary font-semibold text-primary-foreground' : outside ? 'text-muted-foreground' : '')}>{d.getDate()}</div>
                  {items.slice(0, max).map((a) => <Item key={a.id} a={a} compact={mode === 'month'} />)}
                  {items.length > max && (
                    <button type="button" className="px-1.5 text-xs text-primary hover:underline" onClick={() => { setMode('week'); setAnchor(d); }}>
                      +{items.length - max} {tx('more')}
                    </button>
                  )}
                </div>
              );
            })}
          </div>
        </div>
      )}
    </div>
  );
}

/** Personal ICS link so CRM activities appear in Google Calendar, Outlook or a phone calendar. */
export function CalendarFeedDialog({ open, onOpenChange }: { open: boolean; onOpenChange: (o: boolean) => void }) {
  const qc = useQueryClient();
  const [url, setUrl] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const { data: status } = useQuery({
    queryKey: ['crm-suite', 'calendar-feed'],
    queryFn: async () => (await api.get<{ active: boolean; createdAt?: string; lastUsedAt?: string | null }>('/crm/planner/calendar-feed')).data,
    enabled: open,
  });
  const act = async (create: boolean) => {
    setBusy(true);
    try {
      if (create) {
        const r = (await api.post<{ path: string }>('/crm/planner/calendar-feed')).data;
        setUrl(window.location.origin + r.path);
      } else {
        await api.delete('/crm/planner/calendar-feed');
        setUrl(null);
        toast.success(tx('Calendar link turned off'));
      }
      await qc.invalidateQueries({ queryKey: ['crm-suite', 'calendar-feed'] });
    } catch (e) {
      toast.error(apiErrorMessage(e, tx('Could not update the calendar link')));
    } finally {
      setBusy(false);
    }
  };
  return (
    <Dialog open={open} onOpenChange={(v) => { if (!v) setUrl(null); onOpenChange(v); }}>
      <DialogContent className="max-w-lg">
        <DialogHeader><DialogTitle>{tx('Add to your calendar')}</DialogTitle></DialogHeader>
        <div className="space-y-3 text-sm">
          <p className="text-muted-foreground">{tx('Subscribe to a private link and your CRM calls, meetings and to-dos (30 days back, 6 months ahead) appear in Google Calendar, Outlook or your phone. Calendar apps refresh it every few hours.')}</p>
          {url ? (
            <>
              <div className="flex gap-2">
                <Input readOnly value={url} onFocus={(e) => e.currentTarget.select()} aria-label={tx('Calendar link')} />
                <Button variant="outline" onClick={() => { void navigator.clipboard?.writeText(url); toast.success(tx('Copied')); }}><Copy className="h-4 w-4" /></Button>
              </div>
              <p className="rounded-lg bg-amber-50 p-2 text-xs text-amber-900 dark:bg-amber-950/40 dark:text-amber-200">{tx('Anyone with this link can see your activities. It is shown only now; create a new one to replace it.')}</p>
              <ul className="list-disc space-y-1 pl-5 text-xs text-muted-foreground">
                <li>{tx('Google Calendar: Other calendars → + → From URL.')}</li>
                <li>{tx('Outlook: Add calendar → Subscribe from web.')}</li>
                <li>{tx('iPhone: Settings → Calendar → Accounts → Add Subscribed Calendar.')}</li>
              </ul>
            </>
          ) : status?.active ? (
            <p>{tx('A calendar link is active')}{status.lastUsedAt ? ` · ${tx('last read')} ${dateTime(status.lastUsedAt)}` : ` · ${tx('not used yet')}`}.</p>
          ) : (
            <p>{tx('No calendar link yet.')}</p>
          )}
        </div>
        <DialogFooter>
          {status?.active && <Button variant="outline" disabled={busy} onClick={() => act(false)}>{tx('Turn off')}</Button>}
          <Button disabled={busy} onClick={() => act(true)}><Rss className="mr-1.5 h-4 w-4" />{status?.active ? tx('Create a new link') : tx('Create link')}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

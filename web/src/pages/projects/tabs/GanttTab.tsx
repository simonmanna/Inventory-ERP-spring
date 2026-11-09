import { useMemo, useState } from 'react';
import { CalendarRange, Diamond } from 'lucide-react';
import { Skeleton } from '@/components/ui/skeleton';
import { cn } from '@/lib/utils';
import { tx } from '@/lib/i18n/tx';
import { useGantt, type Task } from '@/features/projects/api';
import { EmptyState, addDaysIso, pmColor, todayIso } from '@/features/projects/ui';

const DAY = 86_400_000;
const toMs = (iso: string) => new Date(`${iso}T00:00:00`).getTime();

/** Read-only timeline: one bar per scheduled task (planned start → deadline), milestones as diamonds. */
export function GanttTab({ projectId, onOpenTask }: { projectId: string; onOpenTask: (id: string) => void }) {
  const { data, isLoading } = useGantt(projectId);
  const [zoom, setZoom] = useState<'day' | 'week'>('day');
  const px = zoom === 'day' ? 32 : 10;

  const model = useMemo(() => {
    if (!data) return null;
    const scheduled = data.tasks.filter((t) => t.plannedStart || t.deadline);
    const dates = [
      ...scheduled.flatMap((t) => [t.plannedStart, t.deadline]),
      ...data.milestones.map((m) => m.deadline),
      data.project.startDate, data.project.endDate,
    ].filter((d): d is string => !!d);
    if (!dates.length) return { scheduled, unscheduled: data.tasks, start: todayIso(), days: 0 };
    const min = dates.reduce((a, b) => (a < b ? a : b));
    const max = dates.reduce((a, b) => (a > b ? a : b));
    const start = addDaysIso(min, -2);
    const days = Math.round((toMs(max) - toMs(start)) / DAY) + 5;
    return { scheduled, unscheduled: data.tasks.filter((t) => !t.plannedStart && !t.deadline), start, days };
  }, [data]);

  if (isLoading || !data || !model) return <Skeleton className="h-96 w-full" />;
  if (!model.scheduled.length && !data.milestones.some((m) => m.deadline)) {
    return <EmptyState icon={CalendarRange} title={tx('Nothing on the timeline yet')} hint={tx('Give tasks a start date and a deadline to see them here.')} />;
  }

  const offset = (iso: string) => (Math.round((toMs(iso) - toMs(model.start)) / DAY)) * px;
  const today = offset(todayIso());
  const width = model.days * px;
  const ticks = Array.from({ length: model.days }, (_, i) => addDaysIso(model.start, i));
  const bar = (t: Task) => {
    const s = t.plannedStart ?? t.deadline!;
    const e = t.deadline ?? t.plannedStart!;
    return { left: offset(s), w: Math.max(px, offset(e) - offset(s) + px) };
  };
  const deps = data.dependencies;

  return (
    <div className="space-y-2">
      <div className="flex items-center gap-2 text-sm">
        <span className="text-muted-foreground">{tx('Zoom')}</span>
        {(['day', 'week'] as const).map((z) => (
          <button key={z} type="button" onClick={() => setZoom(z)} className={cn('rounded px-2 py-0.5', zoom === z ? 'bg-primary text-primary-foreground' : 'hover:bg-muted')}>
            {z === 'day' ? tx('Days') : tx('Weeks')}
          </button>
        ))}
        <span className="ml-auto text-xs text-muted-foreground">{tx('{{count}} dependency link(s)', { count: deps.length })}</span>
      </div>
      <div className="overflow-x-auto rounded-xl border bg-card shadow-sm">
        <div className="grid" style={{ gridTemplateColumns: `240px ${width}px` }}>
          {/* header */}
          <div className="sticky left-0 z-20 border-b border-r bg-card px-3 py-2 text-xs font-semibold uppercase text-muted-foreground">{tx('Task')}</div>
          <div className="relative h-9 border-b">
            {ticks.map((d, i) => {
              const date = new Date(`${d}T00:00:00`);
              const show = zoom === 'day' || date.getDay() === 1;
              return show ? (
                <span key={d} className={cn('absolute top-0 flex h-full items-center border-l pl-1 text-[10px] text-muted-foreground', (date.getDay() === 0 || date.getDay() === 6) && zoom === 'day' && 'bg-muted/40')}
                  style={{ left: i * px, width: zoom === 'day' ? px : px * 7 }}>
                  {zoom === 'day' ? date.getDate() : date.toLocaleDateString(undefined, { day: 'numeric', month: 'short' })}
                </span>
              ) : null;
            })}
          </div>
          {/* milestones row */}
          {data.milestones.some((m) => m.deadline) && (
            <>
              <div className="sticky left-0 z-10 border-b border-r bg-card px-3 py-1.5 text-xs font-medium text-violet-600">{tx('Milestones')}</div>
              <div className="relative h-8 border-b">
                {data.milestones.filter((m) => m.deadline).map((m) => (
                  <span key={m.id} title={`${m.name} · ${m.deadline}`} className="absolute top-1.5 flex items-center gap-1 whitespace-nowrap text-[10px]" style={{ left: offset(m.deadline!) }}>
                    <Diamond className={cn('h-4 w-4', m.isReached ? 'fill-emerald-500 text-emerald-600' : 'fill-violet-500 text-violet-600')} />
                    {m.name}
                  </span>
                ))}
              </div>
            </>
          )}
          {model.scheduled.map((t) => {
            const b = bar(t);
            const c = pmColor(t.color);
            return (
              <div key={t.id} className="contents">
                <button type="button" onClick={() => onOpenTask(t.id)} className="sticky left-0 z-10 truncate border-b border-r bg-card px-3 py-1.5 text-left text-sm hover:text-primary">
                  <span className="mr-1 font-mono text-[10px] text-muted-foreground">{t.number}</span>{t.title}
                </button>
                <div className="relative h-9 border-b">
                  <button
                    type="button"
                    onClick={() => onOpenTask(t.id)}
                    title={`${t.title}: ${t.plannedStart ?? '…'} → ${t.deadline ?? '…'}`}
                    className={cn('absolute top-1.5 h-6 overflow-hidden rounded-md px-1.5 text-left text-[11px] font-medium text-white shadow-sm',
                      t.isClosed ? 'bg-emerald-500/80' : t.overdue ? 'bg-rose-500' : t.color ? c.bg : 'bg-sky-500')}
                    style={{ left: b.left, width: b.w }}
                  >
                    {zoom === 'day' && t.title}
                  </button>
                </div>
              </div>
            );
          })}
        </div>
        {today >= 0 && today <= width && (
          <div className="pointer-events-none relative -mt-px">
            <span className="sr-only">{tx('Today')}</span>
          </div>
        )}
      </div>
      {model.unscheduled.length > 0 && (
        <p className="text-xs text-muted-foreground">{tx('{{count}} task(s) without dates are not shown.', { count: model.unscheduled.length })}</p>
      )}
    </div>
  );
}

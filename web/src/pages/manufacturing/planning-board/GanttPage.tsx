import { useMemo, useRef, useState, type DragEvent } from 'react';
import { Link } from 'react-router-dom';
import { AlertTriangle, CalendarDays, CalendarOff, GripVertical } from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { DateRangeFilter, ListToolbar } from '@/components/list';
import { cn } from '@/lib/utils';
import { EmptyState, MfgPageHeader, MfgStatus, PageSkeleton, statusMeta, TONE_FILL, TONE_PILL, type MfgTone } from '../mfg-ui';
import { useAuthStore } from '@/stores/auth.store';
import { dateTime } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';
import { apiMessage, notify } from '@/lib/notify';
import { useGantt, useReschedule, type GanttTask } from '@/features/manufacturing/api-planning';
import { addDays, ymd } from './planning-ui';

const DAY = 86_400_000;

/** Bar colour: late beats everything, then the work-order status. */
function barTone(t: GanttTask): MfgTone {
  if (t.late) return 'danger';
  const m = statusMeta(t.status, 'workOrder');
  return m.tone === 'neutral' ? (t.priority >= 3 ? 'warning' : 'info') : m.tone;
}

/**
 * Gantt board (ADR-S019): a lane per work centre. Dragging a bar moves the work order; the server snaps it to the first
 * free working minute and answers with any sequence warnings, so the calendar is always respected.
 */
export function GanttPage() {
  const today = new Date();
  const [from, setFrom] = useState(ymd(today));
  const [to, setTo] = useState(ymd(addDays(today, 14)));
  const { data, isLoading, isError } = useGantt({ from, to });
  const reschedule = useReschedule();
  const can = useAuthStore((s) => s.hasPermission);
  const canDrag = can(PERMISSIONS.workOrder.manage);
  const dragged = useRef<{ task: GanttTask; grabMs: number } | null>(null);
  const start = useMemo(() => new Date(`${from}T00:00:00Z`).getTime(), [from]);
  const end = useMemo(() => new Date(`${to}T00:00:00Z`).getTime() + DAY, [to]);
  const span = Math.max(end - start, DAY);
  const days = Math.round(span / DAY);
  const left = (ms: number) => `${Math.max(0, ((ms - start) / span) * 100)}%`;
  const width = (a: number, b: number) => `${Math.max(0.4, ((Math.min(b, end) - Math.max(a, start)) / span) * 100)}%`;

  const onDragStart = (e: DragEvent<HTMLDivElement>, task: GanttTask) => {
    const box = e.currentTarget.parentElement?.getBoundingClientRect();
    const barStart = new Date(task.plannedStartAt).getTime();
    const pointer = box ? start + ((e.clientX - box.left) / box.width) * span : barStart;
    dragged.current = { task, grabMs: pointer - barStart };
    e.dataTransfer.effectAllowed = 'move';
    e.dataTransfer.setData('text/plain', task.id);
  };

  const onDrop = (e: DragEvent<HTMLDivElement>, workCenterId: string) => {
    e.preventDefault();
    const drag = dragged.current;
    dragged.current = null;
    if (!drag) return;
    const box = e.currentTarget.getBoundingClientRect();
    const at = start + ((e.clientX - box.left) / box.width) * span - drag.grabMs;
    reschedule.mutate({
      id: drag.task.id,
      startAt: new Date(Math.max(at, start)).toISOString(),
      workCenterId: workCenterId !== drag.task.workCenterId ? workCenterId : undefined,
    }, {
      onSuccess: (r) => {
        if (r.warnings.length > 0) r.warnings.forEach((w) => notify.error(w));
        else notify.success(r.snapped ? tx('Moved to the next free working time') : tx('Rescheduled'));
      },
      onError: (err) => notify.error(apiMessage(err, tx('Could not reschedule'))),
    });
  };

  return (
    <div className="space-y-4 p-4 sm:p-6">
      <MfgPageHeader icon={CalendarDays} title={tx('Production Gantt')}
        subtitle={tx('Drag a bar to reschedule. Work centre calendars and capacity are respected.')} />
      <ListToolbar><DateRangeFilter from={from} to={to} onChange={(f, t) => { if (f && t) { setFrom(f); setTo(t); } }} /></ListToolbar>
      {isLoading && <PageSkeleton />}
      <div className="flex flex-wrap items-center gap-2 text-xs">
        {(['pending', 'in_progress', 'paused', 'completed'] as const).map((s) => <MfgStatus key={s} status={s} kind="workOrder" size="xs" />)}
        <span className={cn('inline-flex items-center gap-1 rounded-full px-2 py-px font-medium ring-1 ring-inset', TONE_PILL.danger)}><AlertTriangle className="h-3 w-3" aria-hidden />{tx('Late')}</span>
        <span className="inline-flex items-center gap-1 text-muted-foreground"><span className="h-3 w-3 rounded-sm bg-[repeating-linear-gradient(45deg,hsl(var(--destructive)/0.25)_0_3px,transparent_3px_6px)]" aria-hidden /><CalendarOff className="h-3 w-3" aria-hidden />{tx('Closed day')}</span>
        <span className="inline-flex items-center gap-1 text-muted-foreground"><span className="h-3 w-0.5 bg-rose-500" aria-hidden />{tx('Now')}</span>
      </div>
      {isError && <p className="text-destructive">{tx('Could not load the schedule.')}</p>}
      {data && (
        <Card>
          <CardContent className="overflow-x-auto pt-6">
            <div className="relative min-w-[900px] space-y-2">
              <div className="ml-40 flex text-xs text-muted-foreground">
                {Array.from({ length: days }, (_, i) => {
                  const d = new Date(start + i * DAY);
                  const weekend = d.getUTCDay() === 0 || d.getUTCDay() === 6;
                  const isToday = d.toISOString().slice(0, 10) === ymd(new Date());
                  return (
                    <div key={i} className={cn('flex-1 border-l pl-1', weekend && 'bg-muted/60', isToday && 'font-semibold text-rose-600 dark:text-rose-400')}>
                      <div>{d.toLocaleDateString(undefined, { weekday: 'short' })}</div>
                      <div className="tabular-nums">{d.toISOString().slice(5, 10)}</div>
                    </div>
                  );
                })}
              </div>
              {Date.now() >= start && Date.now() <= end && (
                <div className="pointer-events-none absolute bottom-0 top-0 z-10 ml-40 w-[calc(100%-10rem)]" aria-hidden>
                  <div className="absolute inset-y-0 w-0.5 bg-rose-500 shadow-[0_0_6px_rgba(244,63,94,0.6)]" style={{ left: left(Date.now()) }}>
                    <span className="absolute -left-1 top-0 h-2.5 w-2.5 rounded-full bg-rose-500" />
                  </div>
                </div>
              )}
              {data.lanes.map((lane) => (
                <div key={lane.workCenterId} className="flex items-stretch">
                  <div className="w-40 shrink-0 pr-2" title={lane.name}>
                    <div className="truncate text-sm font-medium">{lane.name}</div>
                    <div className="text-[11px] text-muted-foreground">{lane.code} · {tx('{{n}} jobs', { n: lane.tasks.length })}</div>
                  </div>
                  <div className="relative h-11 flex-1 overflow-hidden rounded-lg border bg-muted/20"
                    onDragOver={(e) => { if (canDrag) e.preventDefault(); }} onDrop={(e) => onDrop(e, lane.workCenterId)}>
                    {Array.from({ length: days - 1 }, (_, i) => <div key={`g${i}`} className="absolute inset-y-0 w-px bg-border/70" style={{ left: `${((i + 1) / days) * 100}%` }} aria-hidden />)}
                    {lane.closedDates.map((d) => {
                      const at = new Date(`${d}T00:00:00Z`).getTime();
                      return <div key={d} title={tx('Closed day')} className="absolute inset-y-0 bg-[repeating-linear-gradient(45deg,hsl(var(--destructive)/0.18)_0_4px,transparent_4px_8px)]" style={{ left: left(at), width: width(at, at + DAY) }} />;
                    })}
                    {lane.tasks.map((t) => {
                      const a = new Date(t.plannedStartAt).getTime();
                      const b = new Date(t.plannedEndAt).getTime();
                      return (
                        <div key={t.id} draggable={canDrag} onDragStart={(e) => onDragStart(e, t)}
                          title={`${t.orderCode} · ${t.name} · ${dateTime(t.plannedStartAt)} → ${dateTime(t.plannedEndAt)}${t.partnerName ? ` · ${t.partnerName}` : ''}`}
                          className={cn('absolute inset-y-1 flex items-center gap-0.5 overflow-hidden rounded-md px-1 text-xs font-medium text-white shadow-sm ring-1 ring-black/10 transition-shadow hover:shadow-md hover:ring-2 hover:ring-foreground/30',
                            TONE_FILL[barTone(t)], canDrag && 'cursor-move')}
                          style={{ left: left(a), width: width(a, b) }}>
                          {canDrag && <GripVertical className="h-3 w-3 shrink-0 opacity-70" aria-hidden />}
                          {t.late && <AlertTriangle className="h-3 w-3 shrink-0" aria-label={tx('Late')} />}
                          <span className="truncate">{t.orderCode}{t.productName ? ` · ${t.productName}` : ''}</span>
                        </div>
                      );
                    })}
                  </div>
                </div>
              ))}
              {data.lanes.length === 0 && <EmptyState compact icon={CalendarDays} title={tx('No active work centres.')} />}
            </div>
          </CardContent>
        </Card>
      )}
      {data && data.unscheduled.length > 0 && (
        <Card>
          <CardHeader><CardTitle>{tx('Not scheduled yet')}</CardTitle></CardHeader>
          <CardContent className="flex flex-wrap gap-2 text-sm">
            {data.unscheduled.map((t) => (
              <Link key={t.id} to={`/manufacturing/orders/${t.productionOrderId}`}
                className="inline-flex items-center gap-2 rounded-full border bg-card px-3 py-1 transition-colors hover:bg-muted">
                <span className="font-mono text-xs font-semibold text-primary">{t.orderCode}</span>
                <span className="text-muted-foreground">{t.name}</span>
                {t.late && <AlertTriangle className="h-3.5 w-3.5 text-rose-600" aria-label={tx('Late')} />}
              </Link>
            ))}
          </CardContent>
        </Card>
      )}
    </div>
  );
}

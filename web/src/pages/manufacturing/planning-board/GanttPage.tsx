import { useMemo, useRef, useState, type DragEvent } from 'react';
import { Link } from 'react-router-dom';
import { CalendarDays } from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { DateRangeFilter, ListPageHeader, ListToolbar } from '@/components/list';
import { useAuthStore } from '@/stores/auth.store';
import { dateTime } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';
import { apiMessage, notify } from '@/lib/notify';
import { useGantt, useReschedule, type GanttTask } from '@/features/manufacturing/api-planning';
import { addDays, ymd } from './planning-ui';

const DAY = 86_400_000;

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
    <div className="space-y-4">
      <ListPageHeader icon={CalendarDays} title={tx('Production Gantt')}
        description={tx('Drag a bar to reschedule. Work centre calendars and capacity are respected.')} />
      <ListToolbar><DateRangeFilter from={from} to={to} onChange={(f, t) => { if (f && t) { setFrom(f); setTo(t); } }} /></ListToolbar>
      {isLoading && <p className="text-muted-foreground">{tx('Loading…')}</p>}
      {isError && <p className="text-destructive">{tx('Could not load the schedule.')}</p>}
      {data && (
        <Card>
          <CardContent className="overflow-x-auto pt-6">
            <div className="min-w-[900px] space-y-2">
              <div className="ml-40 flex text-xs text-muted-foreground">
                {Array.from({ length: days }, (_, i) => (
                  <div key={i} className="flex-1 border-l pl-1">{new Date(start + i * DAY).toISOString().slice(5, 10)}</div>
                ))}
              </div>
              {data.lanes.map((lane) => (
                <div key={lane.workCenterId} className="flex items-stretch">
                  <div className="w-40 shrink-0 truncate pr-2 text-sm" title={lane.name}>{lane.name}</div>
                  <div className="relative h-10 flex-1 rounded border bg-muted/30"
                    onDragOver={(e) => { if (canDrag) e.preventDefault(); }} onDrop={(e) => onDrop(e, lane.workCenterId)}>
                    {lane.closedDates.map((d) => {
                      const at = new Date(`${d}T00:00:00Z`).getTime();
                      return <div key={d} className="absolute inset-y-0 bg-destructive/10" style={{ left: left(at), width: width(at, at + DAY) }} />;
                    })}
                    {lane.tasks.map((t) => {
                      const a = new Date(t.plannedStartAt).getTime();
                      const b = new Date(t.plannedEndAt).getTime();
                      return (
                        <div key={t.id} draggable={canDrag} onDragStart={(e) => onDragStart(e, t)}
                          title={`${t.orderCode} · ${t.name} · ${dateTime(t.plannedStartAt)} → ${dateTime(t.plannedEndAt)}${t.partnerName ? ` · ${t.partnerName}` : ''}`}
                          className={`absolute inset-y-1 truncate rounded px-1 text-xs text-white ${t.late ? 'bg-destructive' : t.priority >= 3 ? 'bg-orange-600' : 'bg-primary'} ${canDrag ? 'cursor-move' : ''}`}
                          style={{ left: left(a), width: width(a, b) }}>
                          {t.orderCode}
                        </div>
                      );
                    })}
                  </div>
                </div>
              ))}
              {data.lanes.length === 0 && <p className="text-sm text-muted-foreground">{tx('No active work centres.')}</p>}
            </div>
          </CardContent>
        </Card>
      )}
      {data && data.unscheduled.length > 0 && (
        <Card>
          <CardHeader><CardTitle>{tx('Not scheduled yet')}</CardTitle></CardHeader>
          <CardContent className="space-y-1 text-sm">
            {data.unscheduled.map((t) => (
              <div key={t.id}>
                <Link className="underline" to={`/manufacturing/orders/${t.productionOrderId}`}>{t.orderCode}</Link> · {t.name}
              </div>
            ))}
          </CardContent>
        </Card>
      )}
    </div>
  );
}

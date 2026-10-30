import { Link } from 'react-router-dom';
import { Radio, RefreshCw, Users } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { ListPageHeader } from '@/components/list';
import { dateTime, relativeTime } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';
import { cn } from '@/lib/utils';
import { FLOOR_STATUS_LABEL, pct, qty, useBoard, type FloorStatus } from '@/features/manufacturing/api-shopfloor';
import { FLOOR_BORDER, FloorStatusPill, ShiftReportStatusPill } from './shopfloor-ui';

/** Live shop-floor board (ADR-S015): status per work centre, current job, crew, open stop, shift OEE; refreshes every 30 s. */
export function FloorBoardPage() {
  const board = useBoard();
  const data = board.data;
  return (
    <div className="space-y-4">
      <ListPageHeader icon={Radio} title={tx('Shop-floor live')}
        description={tx('Live status of every work centre: running, stopped, idle, crew on shift and the current shift’s OEE.')}
        actions={<Button variant="outline" onClick={() => board.refetch()} disabled={board.isFetching}><RefreshCw className="mr-1.5 h-4 w-4" />{tx('Refresh')}</Button>} />
      {data && (
        <div className="flex flex-wrap gap-2 text-sm">
          {(Object.keys(FLOOR_STATUS_LABEL) as FloorStatus[]).map((s) => (
            <span key={s} className="rounded-full border px-3 py-1"><FloorStatusPill status={s} /> <b className="ml-1 tabular-nums">{data.totals[s] ?? 0}</b></span>
          ))}
          <span className="px-2 py-1 text-muted-foreground">{tx('Updated {{time}}', { time: dateTime(data.asOf) })}</span>
          {!data.currentShift && <span className="px-2 py-1 text-amber-700">{tx('No shift covers the current time — define shifts in Shop-floor Setup.')}</span>}
        </div>
      )}
      {board.isLoading && <p className="text-muted-foreground">{tx('Loading…')}</p>}
      {board.isError && <p className="text-destructive">{tx('Could not load the shop-floor board.')}</p>}
      {data && data.workCenters.length === 0 && <p className="text-muted-foreground">{tx('No active work centres.')}</p>}
      <div className="grid grid-cols-1 gap-3 md:grid-cols-2 xl:grid-cols-3">
        {data?.workCenters.map((c) => (
          <div key={c.workCenterId} className={cn('space-y-3 rounded-xl border border-l-8 bg-card p-4 shadow-sm', FLOOR_BORDER[c.status])}>
            <div className="flex items-start justify-between gap-2">
              <div>
                <div className="text-lg font-semibold">{c.name}</div>
                <div className="text-xs text-muted-foreground">{c.code}</div>
              </div>
              <FloorStatusPill status={c.status} />
            </div>
            {c.openDowntime && (
              <div className="rounded-md bg-red-50 px-3 py-2 text-sm dark:bg-red-950/40">
                {tx('Stopped')}: <b>{c.openDowntime.reasonName ?? c.openDowntime.reasonCode}</b> · {relativeTime(c.openDowntime.startedAt)}
              </div>
            )}
            {c.machineStopUnconfirmed && !c.openDowntime && (
              <div className="rounded-md bg-amber-50 px-3 py-2 text-sm dark:bg-amber-950/40">{tx('The machine reports a stop (not confirmed yet).')}</div>
            )}
            <div className="space-y-1 text-sm">
              {c.workOrders.length === 0 ? <div className="text-muted-foreground">{tx('No job running')}</div> : c.workOrders.slice(0, 3).map((w) => (
                <div key={w.id} className="flex justify-between gap-2">
                  <span className="truncate">{w.orderCode} · {w.name}{w.productName ? ` · ${w.productName}` : ''}</span>
                  <span className="shrink-0 text-muted-foreground">{w.status === 'in_progress' ? tx('Running') : tx('Stopped')}</span>
                </div>
              ))}
            </div>
            <div className="flex items-center gap-2 text-sm text-muted-foreground">
              <Users className="h-4 w-4" />
              {c.operators.length ? c.operators.map((o) => o.userName ?? '—').join(', ') : tx('Nobody clocked on')}
            </div>
            {c.shift?.oee ? (
              <div className="grid grid-cols-4 gap-1 rounded-lg bg-muted/50 p-2 text-center text-xs">
                <div><div className="text-muted-foreground">{tx('OEE')}</div><div className="text-base font-semibold">{pct(c.shift.oee.oee)}</div></div>
                <div><div className="text-muted-foreground">{tx('A')}</div><div className="font-medium">{pct(c.shift.oee.availability)}</div></div>
                <div><div className="text-muted-foreground">{tx('P')}</div><div className="font-medium">{pct(c.shift.oee.performance)}</div></div>
                <div><div className="text-muted-foreground">{tx('Q')}</div><div className="font-medium">{pct(c.shift.oee.quality)}</div></div>
              </div>
            ) : <div className="text-xs text-muted-foreground">{tx('No shift report for the current shift yet.')}</div>}
            <div className="flex flex-wrap items-center justify-between gap-2 text-sm">
              <span className="text-muted-foreground">
                {c.shift?.oee ? tx('Good {{good}} · scrap {{scrap}}', { good: qty(c.shift.oee.goodQty), scrap: qty(c.shift.oee.scrapQty) }) : ''}
                {c.pendingDrafts > 0 ? ` · ${tx('{{n}} machine drafts', { n: c.pendingDrafts })}` : ''}
              </span>
              <div className="flex items-center gap-2">
                {c.shift?.shiftReportId && c.shift.status && (
                  <Link to={`/manufacturing/shift-reports/${c.shift.shiftReportId}`} className="flex items-center gap-1 text-primary hover:underline">
                    {c.shift.code} <ShiftReportStatusPill status={c.shift.status} />
                  </Link>
                )}
                <Button size="sm" asChild><Link to={`/manufacturing/terminal?wc=${c.workCenterId}`}>{tx('Terminal')}</Link></Button>
              </div>
            </div>
          </div>
        ))}
      </div>
    </div>
  );
}

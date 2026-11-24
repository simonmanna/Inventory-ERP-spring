import { useState } from 'react';
import { Link } from 'react-router-dom';
import { Clock, Maximize2, Minimize2, Radio, RefreshCw, Tablet, Users, Wifi, Wrench } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { dateTime, relativeTime } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';
import { cn } from '@/lib/utils';
import { FLOOR_STATUS_LABEL, pct, qty, useBoard, type BoardCard, type FloorStatus } from '@/features/manufacturing/api-shopfloor';
import { ShiftReportStatusPill } from './shopfloor-ui';
import { EmptyState, MfgPageHeader, MfgStatus, PageSkeleton, pctTone, ProgressRing, statusMeta, TONE_FILL, TONE_PILL } from '../mfg-ui';

const STATUSES = Object.keys(FLOOR_STATUS_LABEL) as FloorStatus[];
const asPct = (v: unknown) => (v === null || v === undefined || v === '' ? null : Number(v) * 100);

/** Shift progress as a share of the shift window that has elapsed. */
function shiftElapsed(c: BoardCard): number | null {
  if (!c.shift) return null;
  const a = new Date(c.shift.startsAt).getTime();
  const b = new Date(c.shift.endsAt).getTime();
  if (!(b > a)) return null;
  return Math.min(100, Math.max(0, ((Date.now() - a) / (b - a)) * 100));
}

function CentreCard({ c, tv }: { c: BoardCard; tv: boolean }) {
  const m = statusMeta(c.status, 'floor');
  const Icon = m.icon;
  const oee = asPct(c.shift?.oee?.oee);
  const elapsed = shiftElapsed(c);
  return (
    <article className={cn('flex flex-col overflow-hidden rounded-2xl border bg-card shadow-sm', tv && 'border-white/10 bg-zinc-900 text-zinc-50',
      c.status === 'down' && 'ring-2 ring-rose-500/60')}>
      <div className={cn('flex items-center justify-between gap-2 px-4 py-2 text-sm font-semibold text-white', TONE_FILL[m.tone])}>
        <span className="flex items-center gap-2"><Icon className={cn('h-4 w-4', c.status === 'running' && 'animate-pulse motion-reduce:animate-none')} aria-hidden />{m.label()}</span>
        {c.openDowntime && <span className="flex items-center gap-1 text-xs font-medium"><Clock className="h-3.5 w-3.5" aria-hidden />{relativeTime(c.openDowntime.startedAt)}</span>}
      </div>
      <div className="flex flex-1 flex-col gap-3 p-4">
        <div className="flex items-start justify-between gap-3">
          <div className="min-w-0">
            <h2 className={cn('truncate font-semibold', tv ? 'text-2xl' : 'text-lg')}>{c.name}</h2>
            <div className={cn('text-xs', tv ? 'text-zinc-400' : 'text-muted-foreground')}>{c.code}</div>
          </div>
          <ProgressRing value={oee} size={tv ? 88 : 68} stroke={10} tone={pctTone(oee)} sub={tx('OEE')} />
        </div>

        {c.openDowntime && (
          <div className={cn('rounded-lg px-3 py-2 text-sm ring-1 ring-inset', TONE_PILL.danger)}>
            {tx('Stopped')}: <b>{c.openDowntime.reasonName ?? c.openDowntime.reasonCode}</b> · {relativeTime(c.openDowntime.startedAt)}
          </div>
        )}
        {c.machineStopUnconfirmed && !c.openDowntime && (
          <div className={cn('rounded-lg px-3 py-2 text-sm ring-1 ring-inset', TONE_PILL.warning)}>{tx('The machine reports a stop (not confirmed yet).')}</div>
        )}

        <div className="space-y-1.5 text-sm">
          {c.workOrders.length === 0 ? <div className={tv ? 'text-zinc-400' : 'text-muted-foreground'}>{tx('No job running')}</div> : c.workOrders.slice(0, 3).map((w) => (
            <div key={w.id} className={cn('flex items-center justify-between gap-2 rounded-lg px-2.5 py-1.5', tv ? 'bg-white/5' : 'bg-muted/50')}>
              <span className="min-w-0 truncate"><b className="font-mono">{w.orderCode}</b> · {w.name}{w.productName ? ` · ${w.productName}` : ''}</span>
              <MfgStatus status={w.status === 'in_progress' ? 'running' : 'paused'} kind="floor" size="xs" label={w.status === 'in_progress' ? tx('Running') : tx('Stopped')} />
            </div>
          ))}
        </div>

        {c.shift?.oee ? (
          <div className="grid grid-cols-3 gap-2 text-center text-xs">
            {([['A', tx('Availability'), c.shift.oee.availability], ['P', tx('Performance'), c.shift.oee.performance], ['Q', tx('Quality'), c.shift.oee.quality]] as const).map(([k, label, v]) => {
              const p = asPct(v);
              return (
                <div key={k} className={cn('rounded-lg p-2', tv ? 'bg-white/5' : 'bg-muted/50')} title={label}>
                  <div className={tv ? 'text-zinc-400' : 'text-muted-foreground'}>{label}</div>
                  <div className="font-semibold tabular-nums">{pct(v)}</div>
                  <div className="mt-1 h-1 overflow-hidden rounded-full bg-muted"><div className={cn('h-full', TONE_FILL[pctTone(p)])} style={{ width: `${Math.min(100, p ?? 0)}%` }} /></div>
                </div>
              );
            })}
          </div>
        ) : <div className={cn('text-xs', tv ? 'text-zinc-400' : 'text-muted-foreground')}>{tx('No shift report for the current shift yet.')}</div>}

        {elapsed != null && (
          <div className="text-xs">
            <div className={cn('mb-1 flex justify-between', tv ? 'text-zinc-400' : 'text-muted-foreground')}><span>{tx('Shift')} {c.shift?.code ?? ''}</span><span className="tabular-nums">{Math.round(elapsed)}%</span></div>
            <div className="h-1.5 overflow-hidden rounded-full bg-muted"><div className="h-full rounded-full bg-primary" style={{ width: `${elapsed}%` }} /></div>
          </div>
        )}

        <div className={cn('flex items-center gap-2 text-sm', tv ? 'text-zinc-300' : 'text-muted-foreground')}>
          <Users className="h-4 w-4 shrink-0" aria-hidden />
          <span className="truncate">{c.operators.length ? c.operators.map((o) => o.userName ?? '—').join(', ') : tx('Nobody clocked on')}</span>
        </div>

        <div className="mt-auto flex flex-wrap items-center justify-between gap-2 border-t pt-3 text-sm">
          <span className={cn('text-xs', tv ? 'text-zinc-400' : 'text-muted-foreground')}>
            {c.shift?.oee ? tx('Good {{good}} · scrap {{scrap}}', { good: qty(c.shift.oee.goodQty), scrap: qty(c.shift.oee.scrapQty) }) : ''}
            {c.pendingDrafts > 0 ? ` · ${tx('{{n}} machine drafts', { n: c.pendingDrafts })}` : ''}
          </span>
          <div className="flex items-center gap-2">
            {c.shift?.shiftReportId && c.shift.status && (
              <Link to={`/manufacturing/shift-reports/${c.shift.shiftReportId}`} className="flex items-center gap-1 text-primary hover:underline">
                {c.shift.code} <ShiftReportStatusPill status={c.shift.status} />
              </Link>
            )}
            <Button size="sm" asChild><Link to={`/manufacturing/terminal?wc=${c.workCenterId}`}><Tablet className="mr-1 h-4 w-4" aria-hidden />{tx('Terminal')}</Link></Button>
          </div>
        </div>
      </div>
    </article>
  );
}

/** Live shop-floor board (ADR-S015): status per work centre, current job, crew, open stop, shift OEE; refreshes every 30 s. */
export function FloorBoardPage() {
  const board = useBoard();
  const data = board.data;
  const [tv, setTv] = useState(false);
  const [filter, setFilter] = useState<FloorStatus | 'all'>('all');
  const centres = (data?.workCenters ?? []).filter((c) => filter === 'all' || c.status === filter);
  return (
    <div className={cn('space-y-4 p-4 sm:p-6', tv && 'dark min-h-screen rounded-2xl bg-zinc-950 text-zinc-50')}>
      {!tv ? (
        <MfgPageHeader icon={Radio} title={tx('Shop-floor live')}
          subtitle={tx('Live status of every work centre: running, stopped, idle, crew on shift and the current shift’s OEE.')}
          actions={
            <>
              <Button variant="outline" onClick={() => setTv(true)}><Maximize2 className="mr-1.5 h-4 w-4" aria-hidden />{tx('Wall-screen mode')}</Button>
              <Button variant="outline" onClick={() => board.refetch()} disabled={board.isFetching}><RefreshCw className={cn('mr-1.5 h-4 w-4', board.isFetching && 'animate-spin')} aria-hidden />{tx('Refresh')}</Button>
            </>
          } />
      ) : (
        <div className="flex flex-wrap items-center justify-between gap-3">
          <h1 className="flex items-center gap-2 text-2xl font-bold"><Radio className="h-6 w-6 text-emerald-400" aria-hidden />{tx('Shop-floor live')}</h1>
          <Button variant="secondary" onClick={() => setTv(false)}><Minimize2 className="mr-1.5 h-4 w-4" aria-hidden />{tx('Exit wall-screen mode')}</Button>
        </div>
      )}

      {data && (
        <div className="flex flex-wrap items-center gap-2">
          <div className="grid w-full grid-cols-2 gap-2 sm:flex sm:w-auto sm:flex-wrap">
            {STATUSES.map((s) => {
              const m = statusMeta(s, 'floor');
              const Icon = m.icon;
              return (
                <button key={s} type="button" aria-pressed={filter === s} onClick={() => setFilter(filter === s ? 'all' : s)}
                  className={cn('flex items-center gap-2 rounded-xl border px-3 py-2 text-left transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring',
                    filter === s ? 'border-primary ring-1 ring-primary' : tv ? 'border-white/10 bg-zinc-900 hover:bg-zinc-800' : 'bg-card hover:bg-accent')}>
                  <span className={cn('grid h-8 w-8 place-items-center rounded-lg text-white', TONE_FILL[m.tone])}><Icon className="h-4 w-4" aria-hidden /></span>
                  <span>
                    <span className="block text-xl font-bold leading-none tabular-nums">{data.totals[s] ?? 0}</span>
                    <span className={cn('text-xs', tv ? 'text-zinc-400' : 'text-muted-foreground')}>{m.label()}</span>
                  </span>
                </button>
              );
            })}
          </div>
          <span className={cn('flex items-center gap-1.5 px-2 py-1 text-xs', tv ? 'text-zinc-400' : 'text-muted-foreground')}>
            <Wifi className="h-3.5 w-3.5 text-emerald-500" aria-hidden />{tx('Updated {{time}}', { time: dateTime(data.asOf) })}
          </span>
          {!data.currentShift && <span className={cn('rounded-lg px-2 py-1 text-xs ring-1 ring-inset', TONE_PILL.warning)}>{tx('No shift covers the current time — define shifts in Shop-floor Setup.')}</span>}
        </div>
      )}
      {board.isLoading && <PageSkeleton />}
      {board.isError && <p className="text-destructive">{tx('Could not load the shop-floor board.')}</p>}
      {data && data.workCenters.length === 0 && <EmptyState icon={Wrench} title={tx('No active work centres.')} />}
      <div className={cn('grid grid-cols-1 gap-4 md:grid-cols-2', tv ? 'xl:grid-cols-3 2xl:grid-cols-4' : 'xl:grid-cols-3')}>
        {centres.map((c) => <CentreCard key={c.workCenterId} c={c} tv={tv} />)}
      </div>
    </div>
  );
}

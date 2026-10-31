import { useState } from 'react';
import { Link } from 'react-router-dom';
import { Boxes, ClipboardCheck, MoveRight, Printer, ShieldCheck, Thermometer, Hammer } from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { Button } from '@/components/ui/button';
import { Card, CardContent } from '@/components/ui/card';
import { Skeleton } from '@/components/ui/skeleton';
import {
  DataTablePagination, FilterChips, FilterSelect, ListPageHeader, ListToolbar, SearchInput, useListState, type ActiveChip,
} from '@/components/list';
import { useAuthStore } from '@/stores/auth.store';
import { notify, apiMessage } from '@/lib/notify';
import { dateTime } from '@/lib/format';
import { cn } from '@/lib/utils';
import { tx } from '@/lib/i18n/tx';
import {
  BATCH_STATUS_LABEL, useCuringAreas, useCuringBatches, useFinalInspection, type CuringBatch, type CuringBatchStatus,
} from '@/features/manufacturing/api-precast';
import { BatchStatusPill, QcPill } from './precast-ui';
import { CuringActionDialog, type CuringAction } from './CuringActions';
import { curingProgressPct, daysLabel, num, pct, toOptions } from './precast-format';

// No status in the URL means "curing" (the yard's default view); "all" lifts the filter.
const FILTERS = { status: '', area: '', ready: '' };

/** Curing board (2.4), mobile-first for the yard: one card per curing batch with big touch actions. */
export function CuringBoardPage() {
  const list = useListState(FILTERS, { defaultPageSize: 24 });
  const { status, area, ready } = list.filters;
  const areas = useCuringAreas();
  const [active, setActive] = useState<{ action: CuringAction; batch: CuringBatch } | null>(null);
  const { data, isLoading, isFetching, isError } = useCuringBatches({
    page: list.page, pageSize: list.pageSize, search: list.search || undefined, status: status === 'all' ? undefined : status || 'curing',
    curingAreaId: area || undefined, ready: ready === 'true' ? true : undefined,
  });
  const areaName = (id: string) => areas.data?.find((a) => a.id === id)?.code ?? id;
  const chips: ActiveChip[] = [
    ...(list.search ? [{ key: 'q', label: `“${list.search}”`, onRemove: () => list.setSearchInput('') }] : []),
    ...(status ? [{ key: 'status', label: status === 'all' ? tx('All statuses') : BATCH_STATUS_LABEL[status as CuringBatchStatus] ?? status, onRemove: () => list.setFilter('status', '') }] : []),
    ...(area ? [{ key: 'area', label: areaName(area), onRemove: () => list.setFilter('area', '') }] : []),
    ...(ready ? [{ key: 'ready', label: tx('Ready to cube'), onRemove: () => list.setFilter('ready', '') }] : []),
  ];
  const rows = data?.data ?? [];
  return (
    <div className="space-y-4">
      <ListPageHeader icon={Thermometer} title={tx('Curing board')} description={tx('Blocks curing in chambers and yards. Log temperatures, move pallets, cube when ready.')} />
      <ListToolbar chips={<FilterChips chips={chips} onClearAll={list.clearFilters} />}>
        <SearchInput value={list.searchInput} onChange={list.setSearchInput} placeholder={tx('Search batch, order, product…')} />
        <FilterSelect value={status} onChange={(v) => list.setFilter('status', v)} allLabel={BATCH_STATUS_LABEL.curing}
          options={[{ value: 'all', label: tx('All statuses') }, ...toOptions(BATCH_STATUS_LABEL).filter((o) => o.value !== 'curing')]} />
        <FilterSelect value={area} onChange={(v) => list.setFilter('area', v)} allLabel={tx('All curing areas')}
          options={(areas.data ?? []).map((a) => ({ value: a.id, label: `${a.code} · ${a.name}` }))} />
        <Button variant={ready ? 'default' : 'outline'} className="h-9" onClick={() => list.setFilter('ready', ready ? '' : 'true')}>
          <ClipboardCheck className="mr-1.5 h-4 w-4" /> {tx('Ready to cube')}
        </Button>
      </ListToolbar>

      {isLoading ? (
        <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-3">{Array.from({ length: 6 }).map((_, i) => <Skeleton key={i} className="h-56 w-full" />)}</div>
      ) : isError ? (
        <p className="p-8 text-center text-destructive">{tx('Could not load the curing batches.')}</p>
      ) : !rows.length ? (
        <p className="p-8 text-center text-muted-foreground">{chips.length ? tx('No batches match these filters.') : tx('Nothing is curing right now.')}</p>
      ) : (
        <div className={cn('grid gap-3 sm:grid-cols-2 xl:grid-cols-3', isFetching && 'opacity-60 transition-opacity')}>
          {rows.map((b) => <BatchCard key={b.id} batch={b} onAction={(action) => setActive({ action, batch: b })} />)}
        </div>
      )}
      {data?.meta && data.meta.total > 0 && (
        <div className="rounded-xl border bg-card">
          <DataTablePagination page={data.meta.page} pageSize={list.pageSize} total={data.meta.total} totalPages={data.meta.totalPages}
            onPageChange={list.setPage} onPageSizeChange={list.setPageSize} pageSizeOptions={[12, 24, 48]} noun="batch" />
        </div>
      )}
      {active && <CuringActionDialog action={active.action} batch={active.batch} onClose={() => setActive(null)} />}
    </div>
  );
}

export function BatchCard({ batch: b, onAction }: { batch: CuringBatch; onAction: (a: CuringAction) => void }) {
  const has = useAuthStore((s) => s.hasPermission);
  const canWork = has(PERMISSIONS.productionOrder.complete);
  const canQc = has(PERMISSIONS.productionOrder.qc);
  const finalInspection = useFinalInspection();
  const progress = b.status === 'curing' ? curingProgressPct(b.startedAt, b.curingUntil) : 100;
  const maturity = b.maturityPct == null ? null : Math.max(0, Math.min(100, Number(b.maturityPct)));
  const runFinal = async () => {
    try {
      await finalInspection.mutateAsync(b.id);
      notify.success(tx('Final inspection lot opened'));
    } catch (err) {
      notify.error(apiMessage(err, tx('Could not open the final inspection')));
    }
  };
  return (
    <Card className={cn('flex flex-col', b.status === 'curing' && b.ready && 'border-emerald-500/60')}>
      <CardContent className="flex flex-1 flex-col gap-3 p-4">
        <div className="flex items-start justify-between gap-2">
          <div className="min-w-0">
            <Link to={`/manufacturing/precast/curing/${b.id}`} className="font-mono text-sm font-semibold text-primary hover:underline">{b.code}</Link>
            <p className="truncate font-medium">{b.productName}</p>
            <p className="text-xs text-muted-foreground">{b.orderCode}{b.batchNumber ? ` · ${b.batchNumber}` : ''}</p>
          </div>
          <div className="flex flex-col items-end gap-1">
            <BatchStatusPill status={b.status} />
            <QcPill status={b.qcStatus} />
          </div>
        </div>
        <dl className="grid grid-cols-3 gap-2 text-sm">
          <div><dt className="text-xs text-muted-foreground">{tx('Blocks')}</dt><dd className="font-semibold tabular-nums">{num(b.quantity, 0)}</dd></div>
          <div><dt className="text-xs text-muted-foreground">{tx('Pallets')}</dt><dd className="font-semibold tabular-nums">{num(b.pallets, 0)}</dd></div>
          <div><dt className="text-xs text-muted-foreground">{tx('Days')}</dt><dd className="font-semibold tabular-nums">{daysLabel(b.daysCured, b.minCuringDays)}</dd></div>
          <div className="col-span-3"><dt className="text-xs text-muted-foreground">{tx('Curing area')}</dt><dd className="truncate">{b.curingAreaName ?? '—'}{b.palletLocation ? ` · ${b.palletLocation}` : ''}</dd></div>
        </dl>
        <div className="space-y-1">
          <div className="flex justify-between text-xs text-muted-foreground">
            <span>{b.status === 'curing' ? (b.ready ? tx('Ready to cube') : tx('Curing until')) : tx('Curing until')}</span>
            <span>{b.curingUntil ? dateTime(b.curingUntil) : '—'}</span>
          </div>
          <div className="h-2.5 overflow-hidden rounded-full bg-muted" role="progressbar" aria-valuenow={Math.round(progress)} aria-valuemin={0} aria-valuemax={100}>
            <div className={cn('h-full rounded-full', b.ready || b.status !== 'curing' ? 'bg-emerald-500' : 'bg-sky-500')} style={{ width: `${progress}%` }} />
          </div>
          {(b.maturity != null || maturity != null) && (
            <p className="text-xs text-muted-foreground">
              {tx('Maturity')}: {num(b.maturity, 0)}{b.targetMaturity != null ? ` / ${num(b.targetMaturity, 0)}` : ''} °C·h {maturity != null && `(${pct(maturity, 0)})`}
              {b.lastTemperatureC != null && ` · ${num(b.lastTemperatureC, 1)} °C`}
            </p>
          )}
        </div>
        <div className="mt-auto grid grid-cols-2 gap-2">
          {b.status === 'curing' && canWork && (
            <>
              <Button variant="outline" className="h-11" onClick={() => onAction('reading')}><Thermometer className="mr-1 h-4 w-4" /> {tx('Reading')}</Button>
              <Button variant="outline" className="h-11" onClick={() => onAction('move')}><MoveRight className="mr-1 h-4 w-4" /> {tx('Move')}</Button>
              <Button className="col-span-2 h-12" variant={b.ready ? 'default' : 'outline'} onClick={() => onAction('cube')}><Hammer className="mr-1 h-4 w-4" /> {tx('Cube')}</Button>
            </>
          )}
          {['curing', 'cubed', 'failed'].includes(b.status) && canQc && (
            <Button variant="outline" className="h-11" onClick={() => onAction('gate')}><ShieldCheck className="mr-1 h-4 w-4" /> {tx('QC gate')}</Button>
          )}
          {b.status === 'released' && canQc && !b.finalLotId && (
            <Button variant="outline" className="h-11" onClick={runFinal} disabled={finalInspection.isPending}><ClipboardCheck className="mr-1 h-4 w-4" /> {tx('Final inspection')}</Button>
          )}
          <Button variant="outline" className="h-11" asChild>
            <Link to={`/manufacturing/precast/curing/${b.id}/labels`}><Printer className="mr-1 h-4 w-4" /> {tx('Labels')}</Link>
          </Button>
          <Button variant="ghost" className="h-11" asChild>
            <Link to={`/manufacturing/precast/curing/${b.id}`}><Boxes className="mr-1 h-4 w-4" /> {tx('Details')}</Link>
          </Button>
        </div>
      </CardContent>
    </Card>
  );
}

export default CuringBoardPage;

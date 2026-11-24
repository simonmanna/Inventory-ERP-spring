import { useMemo, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { CalendarClock, ClipboardList, Columns3, Factory, LayoutList, PackageOpen, Plus, Search, SearchX } from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { Dialog, DialogContent, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { useAuthStore } from '@/stores/auth.store';
import { notify } from '@/lib/notify';
import { date } from '@/lib/format';
import { cn } from '@/lib/utils';
import { useBoms, useCreateProductionOrder, useProductionOrders } from '@/features/manufacturing/api';
import { useLocationOptions } from '@/features/manufacturing/options';
import type { ProductionOrder, ProductionOrderStatus } from '@/features/manufacturing/types';
import { tx } from '@/lib/i18n/tx';
import {
  DUE_TONE, dueLabel, dueState, EmptyState, FilterChip, fmtQty, MfgCard, MfgPageHeader, MfgStatus, ProgressBar, progressPct,
  statusMeta, TableSkeleton, TONE_BORDER, TONE_TEXT, TonePill, ymdLocal,
} from './mfg-ui';

/** Kept for any caller that still maps a status to a shadcn Badge variant. */
export const ORDER_STATUS_VARIANT: Record<ProductionOrderStatus, 'default' | 'secondary' | 'outline' | 'destructive'> = {
  draft: 'secondary',
  confirmed: 'outline',
  in_progress: 'default',
  qc_hold: 'outline',
  completed: 'default',
  cancelled: 'destructive',
};

const STATUSES: ProductionOrderStatus[] = ['draft', 'confirmed', 'in_progress', 'qc_hold', 'completed', 'cancelled'];
export const orderProductName = (o: ProductionOrder) =>
  o.outputs?.find((x) => x.kind === 'output')?.productName ?? o.outputs?.[0]?.productName ?? null;

function DueCell({ o, today }: { o: ProductionOrder; today: string }) {
  const s = dueState(o.scheduledFor, o.status, today);
  if (!o.scheduledFor) return <span className="text-muted-foreground">—</span>;
  return (
    <span className="inline-flex flex-wrap items-center gap-1.5">
      <span className={cn('whitespace-nowrap tabular-nums', s !== 'none' && TONE_TEXT[DUE_TONE[s]])}>{date(o.scheduledFor)}</span>
      {(s === 'late' || s === 'today') && <TonePill tone={DUE_TONE[s]} icon={CalendarClock}>{dueLabel(s)}</TonePill>}
    </span>
  );
}

export function ProductionOrdersPage() {
  const navigate = useNavigate();
  const hasPermission = useAuthStore((s) => s.hasPermission);
  const canCreate = hasPermission(PERMISSIONS.productionOrder.create);

  const { data: orders, isLoading } = useProductionOrders();
  const { data: activeBoms } = useBoms({ status: 'active' });
  const { data: locations } = useLocationOptions();
  const createOrder = useCreateProductionOrder();

  const [open, setOpen] = useState(false);
  const [bomId, setBomId] = useState('');
  const [runs, setRuns] = useState('1');
  const [locationId, setLocationId] = useState('');
  const [status, setStatus] = useState<'all' | 'late' | ProductionOrderStatus>('all');
  const [query, setQuery] = useState('');
  const [view, setView] = useState<'list' | 'board'>('list');
  const today = ymdLocal();

  const productionLocations = useMemo(
    () => (locations ?? []).filter((l) => l.isActive),
    [locations],
  );

  const counts = useMemo(() => {
    const c: Record<string, number> = { all: orders?.length ?? 0, late: 0 };
    for (const o of orders ?? []) {
      c[o.status] = (c[o.status] ?? 0) + 1;
      if (dueState(o.scheduledFor, o.status, today) === 'late') c.late += 1;
    }
    return c;
  }, [orders, today]);

  const filtered = useMemo(() => {
    const q = query.trim().toLowerCase();
    return (orders ?? []).filter((o) => {
      if (status === 'late' ? dueState(o.scheduledFor, o.status, today) !== 'late' : status !== 'all' && o.status !== status) return false;
      if (!q) return true;
      return [o.orderCode, o.batchNumber, orderProductName(o), o.notes].some((v) => v?.toLowerCase().includes(q));
    });
  }, [orders, status, query, today]);

  const submit = async () => {
    if (!bomId) {
      notify.error(tx('Pick an active BOM.'));
      return;
    }
    try {
      const order = await createOrder.mutateAsync({
        bomId,
        runs: Number(runs) || 1,
        locationId: locationId || undefined,
      });
      notify.success(tx('Order {{orderCode}} created.', { orderCode: order.orderCode }));
      setOpen(false);
      setBomId('');
      setRuns('1');
      setLocationId('');
      navigate(`/manufacturing/orders/${order.id}`);
    } catch (e: any) {
      notify.error(tx('Could not create order'), e?.response?.data?.message ?? e?.message);
    }
  };

  return (
    <div className="space-y-5 p-4 sm:p-6">
      <MfgPageHeader icon={Factory} title={tx('Production Orders')}
        subtitle={tx('Consume raw materials into WIP, receive finished goods out.')}
        actions={canCreate && (
          <Button onClick={() => setOpen(true)}>
            <Plus className="mr-2 h-4 w-4" /> {tx('New Order')}
          </Button>
        )} />

      <div className="flex flex-col gap-3">
        <div className="-mx-1 flex gap-2 overflow-x-auto px-1 pb-1" role="group" aria-label={tx('Status')}>
          <FilterChip active={status === 'all'} onClick={() => setStatus('all')} count={counts.all} icon={ClipboardList}>{tx('All')}</FilterChip>
          {STATUSES.map((s) => (
            <FilterChip key={s} active={status === s} onClick={() => setStatus(s)} count={counts[s] ?? 0} tone={statusMeta(s, 'order').tone}>
              {statusMeta(s, 'order').label()}
            </FilterChip>
          ))}
          <FilterChip active={status === 'late'} onClick={() => setStatus('late')} count={counts.late} tone="danger" icon={CalendarClock}>{tx('Late')}</FilterChip>
        </div>
        <div className="flex flex-wrap items-center gap-2">
          <div className="relative min-w-0 flex-1 sm:max-w-sm">
            <Search className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" aria-hidden />
            <Input value={query} onChange={(e) => setQuery(e.target.value)} placeholder={tx('Search order, product or batch…')} className="pl-9" aria-label={tx('Search')} />
          </div>
          <div className="inline-flex rounded-lg border bg-card p-0.5" role="group" aria-label={tx('View')}>
            <Button size="sm" variant={view === 'list' ? 'default' : 'ghost'} aria-pressed={view === 'list'} onClick={() => setView('list')}>
              <LayoutList className="mr-1.5 h-4 w-4" aria-hidden />{tx('List')}
            </Button>
            <Button size="sm" variant={view === 'board' ? 'default' : 'ghost'} aria-pressed={view === 'board'} onClick={() => setView('board')}>
              <Columns3 className="mr-1.5 h-4 w-4" aria-hidden />{tx('Board')}
            </Button>
          </div>
        </div>
      </div>

      {isLoading ? (
        <MfgCard><TableSkeleton rows={6} cols={6} /></MfgCard>
      ) : !orders?.length ? (
        <MfgCard>
          <EmptyState icon={PackageOpen} title={tx('No production orders yet.')}
            description={tx('Create an order from an active recipe to start producing.')}
            action={canCreate && <Button onClick={() => setOpen(true)}><Plus className="mr-2 h-4 w-4" />{tx('New Order')}</Button>} />
        </MfgCard>
      ) : filtered.length === 0 ? (
        <MfgCard><EmptyState icon={SearchX} compact title={tx('No orders match these filters.')} /></MfgCard>
      ) : view === 'list' ? (
        <section className="overflow-hidden rounded-2xl border bg-card shadow-sm">
          <div className="overflow-x-auto">
            <table className="w-full min-w-[720px] text-sm">
              <thead className="bg-muted/40 text-left text-xs uppercase tracking-wide text-muted-foreground">
                <tr>
                  <th className="px-4 py-2.5 font-semibold">{tx('Order')}</th>
                  <th className="px-3 py-2.5 font-semibold">{tx('Status')}</th>
                  <th className="w-48 px-3 py-2.5 font-semibold">{tx('Progress')}</th>
                  <th className="px-3 py-2.5 text-right font-semibold">{tx('Planned')}</th>
                  <th className="px-3 py-2.5 text-right font-semibold">{tx('Produced')}</th>
                  <th className="px-3 py-2.5 text-right font-semibold">{tx('Unit cost')}</th>
                  <th className="px-4 py-2.5 font-semibold">{tx('Scheduled')}</th>
                </tr>
              </thead>
              <tbody className="divide-y">
                {filtered.map((o) => (
                  <tr key={o.id} className="cursor-pointer transition-colors hover:bg-muted/40" onClick={() => navigate(`/manufacturing/orders/${o.id}`)}>
                    <td className="px-4 py-3">
                      <Link to={`/manufacturing/orders/${o.id}`} onClick={(e) => e.stopPropagation()} className="font-mono text-xs font-semibold text-primary hover:underline">{o.orderCode}</Link>
                      <div className="max-w-56 truncate text-xs text-muted-foreground">{orderProductName(o) ?? o.batchNumber ?? '—'}</div>
                    </td>
                    <td className="px-3 py-3"><MfgStatus status={o.status} kind="order" /></td>
                    <td className="px-3 py-3"><ProgressBar value={progressPct(o.producedQty, o.plannedQty)} showLabel tone={o.status === 'completed' ? 'success' : 'progress'} ariaLabel={tx('Progress')} /></td>
                    <td className="px-3 py-3 text-right tabular-nums">{fmtQty(o.plannedQty)}</td>
                    <td className="px-3 py-3 text-right tabular-nums">{fmtQty(o.producedQty)}</td>
                    <td className="px-3 py-3 text-right tabular-nums">{o.status === 'completed' ? Number(o.outputUnitCost).toFixed(2) : '—'}</td>
                    <td className="px-4 py-3"><DueCell o={o} today={today} /></td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </section>
      ) : (
        <div className="overflow-x-auto pb-2">
          <div className="flex gap-3">
            {STATUSES.filter((s) => status === 'all' || status === 'late' || status === s).map((s) => {
              const col = filtered.filter((o) => o.status === s);
              const m = statusMeta(s, 'order');
              return (
                <div key={s} className={cn('flex w-72 shrink-0 flex-col rounded-2xl border-t-4 bg-muted/40 p-2', TONE_BORDER[m.tone])}>
                  <div className="flex items-center justify-between px-1.5 py-1">
                    <MfgStatus status={s} kind="order" />
                    <span className="text-xs font-semibold tabular-nums text-muted-foreground">{col.length}</span>
                  </div>
                  <ul className="mt-1 flex flex-col gap-2">
                    {col.length === 0 && <li className="rounded-xl border border-dashed p-4 text-center text-xs text-muted-foreground">{tx('Nothing here')}</li>}
                    {col.map((o) => {
                      const due = dueState(o.scheduledFor, o.status, today);
                      return (
                        <li key={o.id}>
                          <Link to={`/manufacturing/orders/${o.id}`} className="block rounded-xl border bg-card p-3 shadow-sm transition-all hover:-translate-y-0.5 hover:shadow-md focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring motion-reduce:hover:translate-y-0">
                            <div className="flex items-start justify-between gap-2">
                              <span className="font-mono text-xs font-semibold text-primary">{o.orderCode}</span>
                              {(due === 'late' || due === 'today') && <TonePill tone={DUE_TONE[due]} icon={CalendarClock}>{dueLabel(due)}</TonePill>}
                            </div>
                            <div className="mt-1 truncate text-sm font-medium">{orderProductName(o) ?? '—'}</div>
                            <ProgressBar className="mt-2" value={progressPct(o.producedQty, o.plannedQty)} showLabel tone={s === 'completed' ? 'success' : 'progress'} ariaLabel={tx('Progress')} />
                            <div className="mt-1.5 flex justify-between text-[11px] text-muted-foreground">
                              <span className="tabular-nums">{fmtQty(o.producedQty)} / {fmtQty(o.plannedQty)}</span>
                              <span>{o.scheduledFor ? date(o.scheduledFor) : ''}</span>
                            </div>
                          </Link>
                        </li>
                      );
                    })}
                  </ul>
                </div>
              );
            })}
          </div>
        </div>
      )}

      <Dialog open={open} onOpenChange={setOpen}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>{tx('New Production Order')}</DialogTitle>
          </DialogHeader>
          <div className="space-y-4">
            <div className="space-y-1">
              <Label>{tx('Recipe (active BOM)')}</Label>
              <Select value={bomId} onValueChange={setBomId}>
                <SelectTrigger><SelectValue placeholder={tx('Select a recipe…')} /></SelectTrigger>
                <SelectContent>
                  {(activeBoms ?? []).map((b) => (
                    <SelectItem key={b.id} value={b.id}>{b.code} · {b.name} {tx('(makes')} {Number(b.outputQuantity)})</SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </div>
            <div className="space-y-1">
              <Label>{tx('Runs (× batch size)')}</Label>
              <Input type="number" value={runs} onChange={(e) => setRuns(e.target.value)} />
            </div>
            <div className="space-y-1">
              <Label>{tx('Production location (optional — defaults to the BOM\'s)')}</Label>
              <Select value={locationId} onValueChange={setLocationId}>
                <SelectTrigger><SelectValue placeholder={tx('Default')} /></SelectTrigger>
                <SelectContent>
                  {productionLocations.map((l) => (
                    <SelectItem key={l.id} value={l.id}>{l.name} ({l.type})</SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </div>
          </div>
          <DialogFooter>
            <Button variant="outline" onClick={() => setOpen(false)}>{tx('Cancel')}</Button>
            <Button onClick={submit} disabled={createOrder.isPending}>{tx('Create')}</Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  );
}

export default ProductionOrdersPage;

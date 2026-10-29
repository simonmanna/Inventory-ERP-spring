import { useState, type ReactNode } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { ArrowLeft, Edit, RefreshCw, Trash2 } from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Skeleton } from '@/components/ui/skeleton';
import {
  AlertDialog, AlertDialogAction, AlertDialogCancel, AlertDialogContent, AlertDialogDescription, AlertDialogFooter,
  AlertDialogHeader, AlertDialogTitle,
} from '@/components/ui/alert-dialog';
import { DataTable, type Column } from '@/components/data-table';
import { useAuthStore } from '@/stores/auth.store';
import { notify, apiMessage } from '@/lib/notify';
import { date, dateTime, money } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';
import {
  TYRE_EVENT_LABEL, TYRE_TYPE_LABEL, positionLabel, useDeleteTyre, useTyre, type TyreEvent, type TyreReading,
} from '@/features/fleet/tyres-api';
import { num } from './fleet-format';
import { TyreStatusPill } from './TyresPage';
import { TyreActionDialog, TyreFormDialog, type TyreActionKind } from './tyre-dialogs';

function Stat({ label, value, tone }: { label: string; value: ReactNode; tone?: string }) {
  return (
    <div className="rounded-lg border p-3">
      <div className="text-xs text-muted-foreground">{label}</div>
      <div className={`text-lg font-semibold tabular-nums ${tone ?? ''}`}>{value}</div>
    </div>
  );
}

/** Tyre detail (fleet 1.7): wear and cost figures, lifecycle actions, history and tread readings. */
export function TyreDetailPage() {
  const { id = '' } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const { data: t, isLoading, isError, refetch } = useTyre(id);
  const canEdit = useAuthStore((s) => s.hasPermission(PERMISSIONS.fleetMaintenance.tyre));
  const [action, setAction] = useState<TyreActionKind | null>(null);
  const [editing, setEditing] = useState(false);
  const [deleting, setDeleting] = useState(false);
  const remove = useDeleteTyre();

  if (isLoading) return <div className="space-y-3"><Skeleton className="h-12 w-1/2" /><Skeleton className="h-64 w-full" /></div>;
  if (isError || !t) {
    return (
      <div className="p-8 text-center text-muted-foreground">
        <p>{tx('Tyre not found')}</p>
        <Button variant="link" onClick={() => refetch()}><RefreshCw className="mr-1 h-4 w-4" /> {tx('Retry')}</Button>
      </div>
    );
  }
  const s = t.stats;
  const scrapped = t.status === 'scrapped';
  const actions: Array<{ kind: TyreActionKind; label: string; show: boolean }> = [
    { kind: 'fit', label: tx('Fit to vehicle'), show: ['in_stock', 'removed', 'retreaded'].includes(t.status) },
    { kind: 'rotate', label: tx('Rotate'), show: t.status === 'fitted' },
    { kind: 'remove', label: tx('Remove'), show: t.status === 'fitted' },
    { kind: 'retread', label: tx('Record retread'), show: t.status === 'removed' },
    { kind: 'readings', label: tx('Tread reading'), show: !scrapped },
    { kind: 'scrap', label: tx('Scrap'), show: ['in_stock', 'removed', 'retreaded'].includes(t.status) },
  ];

  const eventColumns: Column<TyreEvent>[] = [
    { key: 'eventAt', header: tx('When'), render: (e) => <span className="whitespace-nowrap">{dateTime(e.eventAt)}</span> },
    { key: 'type', header: tx('Event'), render: (e) => TYRE_EVENT_LABEL[e.eventType] ?? e.eventType },
    { key: 'vehicle', header: tx('Vehicle'), render: (e) => e.vehicleCode ?? '—' },
    {
      key: 'position', header: tx('Position'),
      render: (e) => (e.fromPosition || e.toPosition ? [e.fromPosition, e.toPosition].filter(Boolean).join(' → ') : '—'),
    },
    { key: 'odometer', header: tx('Odometer'), className: 'text-right', render: (e) => <span className="tabular-nums">{num(e.odometerLifetime, 0)}</span> },
    { key: 'km', header: tx('Km in stint'), className: 'text-right', render: (e) => <span className="tabular-nums">{num(e.kmRun, 0)}</span> },
    { key: 'cost', header: tx('Cost'), className: 'text-right', render: (e) => (e.cost ? money(e.cost) : '—') },
    { key: 'reason', header: tx('Reason'), render: (e) => <span className="line-clamp-1 max-w-[14rem] text-muted-foreground">{e.reason ?? e.notes ?? ''}</span> },
  ];
  const readingColumns: Column<TyreReading>[] = [
    { key: 'readingAt', header: tx('When'), render: (r) => <span className="whitespace-nowrap">{dateTime(r.readingAt)}</span> },
    { key: 'tread', header: tx('Tread (mm)'), className: 'text-right', render: (r) => <span className="tabular-nums">{num(r.treadDepthMm)}</span> },
    { key: 'pressure', header: tx('Pressure (kPa)'), className: 'text-right', render: (r) => <span className="tabular-nums">{num(r.pressureKpa)}</span> },
    { key: 'position', header: tx('Position'), render: (r) => r.position ?? '—' },
    { key: 'notes', header: tx('Notes'), render: (r) => r.notes ?? '' },
  ];

  const confirmDelete = async () => {
    try {
      await remove.mutateAsync(t.id);
      notify.success(tx('Tyre deleted'));
      navigate('/fleet/tyres');
    } catch (err) {
      notify.error(apiMessage(err, tx('Could not delete the tyre')));
      setDeleting(false);
    }
  };

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-start gap-3">
        <Button variant="ghost" size="icon" aria-label={tx('Back')} onClick={() => navigate('/fleet/tyres')}><ArrowLeft className="h-5 w-5" /></Button>
        <div className="min-w-0 flex-1">
          <div className="flex flex-wrap items-center gap-2">
            <h1 className="truncate text-2xl font-bold">{t.serialNumber}</h1>
            <TyreStatusPill status={t.status} />
            {s.belowMinimum && <span className="rounded-full bg-rose-100 px-2 py-0.5 text-xs font-medium text-rose-700">{tx('Below minimum tread')}</span>}
          </div>
          <p className="text-sm text-muted-foreground">
            {[t.brand, t.model, t.size, TYRE_TYPE_LABEL[t.tyreType]].filter(Boolean).join(' · ')}
          </p>
          {t.vehicle && (
            <p className="text-sm">
              {tx('Fitted to')} <Link className="text-primary hover:underline" to={`/fleet/vehicles/${t.vehicle.id}`}>{t.vehicle.code}</Link>
              {' · '}{positionLabel(t.position)}{t.fittedAt ? ` · ${date(t.fittedAt)}` : ''}
            </p>
          )}
        </div>
        {canEdit && (
          <div className="flex flex-wrap gap-2">
            {actions.filter((a) => a.show).map((a) => (
              <Button key={a.kind} variant={a.kind === 'fit' ? 'default' : 'outline'} onClick={() => setAction(a.kind)}>{a.label}</Button>
            ))}
            {!scrapped && <Button variant="outline" onClick={() => setEditing(true)}><Edit className="mr-1.5 h-4 w-4" /> {tx('Edit')}</Button>}
            {t.status === 'in_stock' && (
              <Button variant="ghost" size="icon" aria-label={tx('Delete')} onClick={() => setDeleting(true)}><Trash2 className="h-4 w-4" /></Button>
            )}
          </div>
        )}
      </div>

      <div className="grid grid-cols-2 gap-3 sm:grid-cols-3 lg:grid-cols-6">
        <Stat label={tx('Km run')} value={num(s.km, 0)} />
        <Stat label={tx('Total cost')} value={money(s.totalCost)} />
        <Stat label={tx('Cost / km')} value={s.costPerKm ? money(s.costPerKm) : '—'} />
        <Stat label={tx('Tread (mm)')} value={num(t.treadDepthMm)} tone={s.belowMinimum ? 'text-rose-600' : undefined} />
        <Stat label={tx('Tread left')} value={s.remainingTreadPct ? `${num(s.remainingTreadPct)} %` : '—'} />
        <Stat label={tx('Projected life (km)')} value={num(s.projectedLifeKm, 0)} />
      </div>

      <Card>
        <CardHeader><CardTitle className="text-base">{tx('Details')}</CardTitle></CardHeader>
        <CardContent>
          <dl className="grid grid-cols-2 gap-4 text-sm sm:grid-cols-4">
            <div><dt className="text-xs text-muted-foreground">{tx('Purchase cost')}</dt><dd>{t.purchaseCost ? money(t.purchaseCost) : '—'}</dd></div>
            <div><dt className="text-xs text-muted-foreground">{tx('Retreads')}</dt><dd>{t.retreadCount} · {money(t.extraCost)}</dd></div>
            <div><dt className="text-xs text-muted-foreground">{tx('Supplier')}</dt><dd>{t.supplier?.name ?? '—'}</dd></div>
            <div><dt className="text-xs text-muted-foreground">{tx('Purchased on')}</dt><dd>{t.purchasedOn ? date(t.purchasedOn) : '—'}</dd></div>
            <div><dt className="text-xs text-muted-foreground">{tx('Stock product')}</dt><dd>{t.product?.name ?? '—'}</dd></div>
            <div><dt className="text-xs text-muted-foreground">{tx('Issued from stock')}</dt><dd>{t.stockIssuedAt ? `${dateTime(t.stockIssuedAt)} · ${t.stockLedgerCode ?? ''}` : '—'}</dd></div>
            <div><dt className="text-xs text-muted-foreground">{tx('Wear per 1,000 km (mm)')}</dt><dd>{num(s.wearPer1000Km, 3)}</dd></div>
            <div><dt className="text-xs text-muted-foreground">{tx('Minimum tread depth (mm)')}</dt><dd>{num(t.minTreadDepthMm)}</dd></div>
          </dl>
          {t.scrapReason && <p className="mt-3 text-sm text-muted-foreground">{tx('Scrapped')}: {t.scrapReason}</p>}
          {t.notes && <p className="mt-2 whitespace-pre-wrap text-sm">{t.notes}</p>}
        </CardContent>
      </Card>

      <Card>
        <CardHeader><CardTitle className="text-base">{tx('History')}</CardTitle></CardHeader>
        <CardContent>
          <DataTable columns={eventColumns} data={t.events ?? []} getRowId={(e) => e.id} emptyMessage={tx('No events yet.')} />
        </CardContent>
      </Card>
      <Card>
        <CardHeader><CardTitle className="text-base">{tx('Tread readings')}</CardTitle></CardHeader>
        <CardContent>
          <DataTable columns={readingColumns} data={t.readings ?? []} getRowId={(r) => r.id} emptyMessage={tx('No readings yet.')} />
        </CardContent>
      </Card>

      {action && <TyreActionDialog kind={action} tyre={t} onClose={() => setAction(null)} />}
      {editing && <TyreFormDialog tyre={t} open onOpenChange={setEditing} />}
      <AlertDialog open={deleting} onOpenChange={setDeleting}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>{tx('Delete tyre {{serial}}?', { serial: t.serialNumber })}</AlertDialogTitle>
            <AlertDialogDescription>{tx('Only a tyre that was never fitted can be deleted; otherwise scrap it.')}</AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>{tx('Cancel')}</AlertDialogCancel>
            <AlertDialogAction onClick={confirmDelete}>{tx('Delete')}</AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </div>
  );
}

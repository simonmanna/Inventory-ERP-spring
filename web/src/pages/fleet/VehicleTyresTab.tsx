import { useState } from 'react';
import { Link } from 'react-router-dom';
import { PERMISSIONS } from '@erp/shared';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Skeleton } from '@/components/ui/skeleton';
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { useAuthStore } from '@/stores/auth.store';
import { money } from '@/lib/format';
import { cn } from '@/lib/utils';
import { tx } from '@/lib/i18n/tx';
import type { Vehicle } from '@/features/fleet/api';
import { positionLabel, useVehicleTyres, type Tyre, type WheelPosition } from '@/features/fleet/tyres-api';
import { num } from './fleet-format';
import { TyreActionDialog, type TyreActionKind } from './tyre-dialogs';

/**
 * Vehicle "Tyres" tab (fleet 1.7): the axle diagram from the vehicle's axle configuration — each wheel position shows
 * its tyre (serial, tread, km on this vehicle) or is empty; tap a position to fit, rotate, remove or measure.
 */
export function VehicleTyresTab({ vehicle, active }: { vehicle: Vehicle; active: boolean }) {
  const { data, isLoading, isError } = useVehicleTyres(vehicle.id, active);
  const canEdit = useAuthStore((s) => s.hasPermission(PERMISSIONS.fleetMaintenance.tyre)) && vehicle.status !== 'disposed';
  const [selected, setSelected] = useState<WheelPosition | null>(null);
  const [action, setAction] = useState<{ kind: TyreActionKind; tyre?: Tyre; position?: string } | null>(null);

  if (isLoading || (!data && !isError)) return <Skeleton className="h-64 w-full" />;
  if (isError || !data) return <Card><CardContent className="py-8 text-center text-sm text-muted-foreground">{tx('Could not load the tyres.')}</CardContent></Card>;

  const wheel = (p: WheelPosition) => {
    const t = p.tyre;
    return (
      <button key={p.code} type="button" onClick={() => setSelected(p)}
        className={cn('flex h-24 w-16 flex-col items-center justify-center rounded-lg border-2 p-1 text-center text-[11px] leading-tight transition-colors sm:w-20',
          t ? (t.stats.belowMinimum ? 'border-rose-500 bg-rose-50 dark:bg-rose-500/10' : 'border-emerald-500 bg-emerald-50 dark:bg-emerald-500/10')
            : 'border-dashed border-muted-foreground/40 bg-muted/30 hover:bg-muted')}>
        <span className="font-semibold">{p.code}</span>
        {t ? (
          <>
            <span className="truncate">{t.serialNumber}</span>
            <span className="tabular-nums">{num(t.treadDepthMm)} mm</span>
          </>
        ) : <span className="text-muted-foreground">{tx('Empty')}</span>}
      </button>
    );
  };

  return (
    <div className="space-y-4">
      <Card>
        <CardHeader className="flex flex-row flex-wrap items-center justify-between gap-2 space-y-0">
          <CardTitle className="text-base">
            {tx('Wheel positions')} · {data.axleConfig ?? tx('default layout')} · {tx('{{fitted}} of {{total}} fitted', { fitted: data.fittedCount, total: data.positionCount })}
          </CardTitle>
          {canEdit && <Button size="sm" variant="outline" onClick={() => setAction({ kind: 'fit' })}>{tx('Fit tyre')}</Button>}
        </CardHeader>
        <CardContent>
          <div className="mx-auto flex max-w-md flex-col items-center gap-4">
            <div className="text-xs uppercase tracking-wide text-muted-foreground">{tx('Front')}</div>
            {data.axles.map((a) => {
              const left = a.positions.filter((p) => p.side === 'left');
              const right = a.positions.filter((p) => p.side === 'right');
              return (
                <div key={a.number} className="flex w-full items-center justify-between gap-2">
                  <div className="flex gap-1">{left.map(wheel)}</div>
                  <div className="flex flex-1 flex-col items-center">
                    <div className="h-1 w-full rounded bg-muted-foreground/40" />
                    <span className="mt-1 text-[11px] text-muted-foreground">{tx('Axle {{n}}', { n: a.number })}{a.steer ? ` · ${tx('steer')}` : ''}</span>
                  </div>
                  <div className="flex gap-1">{right.map(wheel)}</div>
                </div>
              );
            })}
            {data.spares.length > 0 && <div className="flex gap-2 pt-2">{data.spares.map(wheel)}</div>}
          </div>
          {data.unplaced.length > 0 && (
            <p className="mt-4 text-sm text-amber-700">
              {tx('Fitted at positions the current axle configuration does not have')}: {data.unplaced.map((t) => `${t.serialNumber} (${t.position})`).join(', ')}
            </p>
          )}
        </CardContent>
      </Card>

      {selected && (
        <Dialog open onOpenChange={(o) => !o && setSelected(null)}>
          <DialogContent>
            <DialogHeader>
              <DialogTitle>{selected.code} — {positionLabel(selected.code)}</DialogTitle>
              <DialogDescription>{selected.tyre ? `${selected.tyre.serialNumber} · ${[selected.tyre.brand, selected.tyre.size].filter(Boolean).join(' · ')}` : tx('No tyre at this position.')}</DialogDescription>
            </DialogHeader>
            {selected.tyre && (
              <dl className="grid grid-cols-2 gap-3 text-sm">
                <div><dt className="text-xs text-muted-foreground">{tx('Tread (mm)')}</dt><dd>{num(selected.tyre.treadDepthMm)}</dd></div>
                <div><dt className="text-xs text-muted-foreground">{tx('Km on this vehicle')}</dt><dd>{num(selected.tyre.stats.currentStintKm, 0)}</dd></div>
                <div><dt className="text-xs text-muted-foreground">{tx('Km run')}</dt><dd>{num(selected.tyre.stats.km, 0)}</dd></div>
                <div><dt className="text-xs text-muted-foreground">{tx('Cost / km')}</dt><dd>{selected.tyre.stats.costPerKm ? money(selected.tyre.stats.costPerKm) : '—'}</dd></div>
              </dl>
            )}
            <DialogFooter className="flex-wrap gap-2">
              {selected.tyre && <Link to={`/fleet/tyres/${selected.tyre.id}`} className="self-center text-sm text-primary hover:underline">{tx('Open tyre')}</Link>}
              {canEdit && !selected.tyre && (
                <Button className="h-11" onClick={() => { setAction({ kind: 'fit', position: selected.code }); setSelected(null); }}>{tx('Fit tyre')}</Button>
              )}
              {canEdit && selected.tyre && (['readings', 'rotate', 'remove'] as const).map((k) => (
                <Button key={k} variant={k === 'remove' ? 'outline' : 'default'} className="h-11"
                  onClick={() => { setAction({ kind: k, tyre: selected.tyre ?? undefined }); setSelected(null); }}>
                  {k === 'readings' ? tx('Tread reading') : k === 'rotate' ? tx('Rotate') : tx('Remove')}
                </Button>
              ))}
            </DialogFooter>
          </DialogContent>
        </Dialog>
      )}
      {action && (
        <TyreActionDialog kind={action.kind} tyre={action.tyre} vehicleId={vehicle.id} position={action.position}
          onClose={() => setAction(null)} />
      )}
    </div>
  );
}

import { useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { CalendarRange, ChevronLeft, ChevronRight, ClipboardList, Plus, Truck } from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { ListPageHeader } from '@/components/list';
import { useAuthStore } from '@/stores/auth.store';
import { date } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';
import { PRIORITY_LABEL, useDispatchBoard, type TripSummary } from '@/features/fleet/dispatch-api';
import { num } from '../fleet-format';
import { AssignDialog } from './AssignDialog';
import { DeliveryOrderStatusPill, TripStatusPill } from './dispatch-ui';
import { addDays, carrierText, todayIso, weekStart } from './dispatch-format';

/**
 * Dispatch board (fleet 1.3): one column per planned day (stacked on phones) with its trips — status, truck / driver or
 * transporter, tonnage — plus the delivery orders that still have quantity to plan. Assign straight from a card.
 */
export function DispatchBoardPage() {
  const navigate = useNavigate();
  const has = useAuthStore((s) => s.hasPermission);
  const [from, setFrom] = useState(() => weekStart(todayIso()));
  const to = addDays(from, 6);
  const { data, isLoading, isError } = useDispatchBoard({ from, to });
  const [assigning, setAssigning] = useState<TripSummary | null>(null);
  const canPlan = has(PERMISSIONS.fleetDispatch.plan);
  const today = todayIso();

  return (
    <div className="space-y-4">
      <ListPageHeader
        icon={CalendarRange}
        title={tx('Dispatch Board')}
        description={tx('Trips per day by customer and site, and the delivery orders still to plan.')}
        actions={
          <>
            <Button variant="outline" asChild><Link to="/fleet/trips"><Truck className="mr-1.5 h-4 w-4" /> {tx('Trips')}</Link></Button>
            <Button variant="outline" asChild><Link to="/fleet/delivery-orders"><ClipboardList className="mr-1.5 h-4 w-4" /> {tx('Delivery Orders')}</Link></Button>
            {has(PERMISSIONS.fleetDispatch.create) && (
              <Button onClick={() => navigate('/fleet/delivery-orders/new')}><Plus className="mr-1.5 h-4 w-4" /> {tx('New delivery order')}</Button>
            )}
          </>
        }
      />

      <div className="flex flex-wrap items-center gap-2">
        <Button variant="outline" size="icon" aria-label={tx('Previous week')} onClick={() => setFrom(addDays(from, -7))}><ChevronLeft className="h-4 w-4" /></Button>
        <Input type="date" className="w-auto" value={from} onChange={(e) => e.target.value && setFrom(e.target.value)} />
        <span className="text-sm text-muted-foreground">→ {date(to)}</span>
        <Button variant="outline" size="icon" aria-label={tx('Next week')} onClick={() => setFrom(addDays(from, 7))}><ChevronRight className="h-4 w-4" /></Button>
        <Button variant="ghost" onClick={() => setFrom(weekStart(todayIso()))}>{tx('This week')}</Button>
      </div>

      {isError && <div className="rounded-xl border bg-card p-4 text-sm text-muted-foreground">{tx('Could not load the board. Try again.')}</div>}
      {isLoading && <div className="rounded-xl border bg-card p-4 text-sm text-muted-foreground">…</div>}

      {data && (
        <div className="grid grid-cols-1 gap-3 md:grid-cols-2 xl:grid-cols-4">
          <section className="rounded-xl border bg-muted/30 p-3 xl:row-span-2">
            <h2 className="mb-2 text-sm font-semibold">{tx('To plan')} ({data.unplanned.length})</h2>
            <div className="space-y-2">
              {data.unplanned.length === 0 && <p className="text-sm text-muted-foreground">{tx('Everything is planned.')}</p>}
              {data.unplanned.map((o) => (
                <button key={o.id} type="button" onClick={() => navigate(`/fleet/delivery-orders/${o.id}`)}
                  className="w-full rounded-lg border bg-card p-2.5 text-left text-sm shadow-sm hover:bg-accent">
                  <div className="flex items-center justify-between gap-2">
                    <span className="font-medium">{o.code}</span>
                    <DeliveryOrderStatusPill status={o.status} />
                  </div>
                  <div className="text-xs text-muted-foreground">{o.partnerName}{o.siteName ? ` · ${o.siteName}` : ''}</div>
                  <div className="mt-1 flex justify-between text-xs">
                    <span>{date(o.requestedDate)}{o.windowFrom ? ` ${o.windowFrom}–${o.windowTo ?? ''}` : ''}</span>
                    <span className="tabular-nums">{tx('{{qty}} to plan', { qty: num(o.unplannedQty, 2) })}</span>
                  </div>
                  {o.priority !== 'normal' && <div className="text-xs font-medium text-amber-700">{PRIORITY_LABEL[o.priority]}</div>}
                </button>
              ))}
            </div>
          </section>
          {data.days.map((d) => (
            <section key={d.date} className={`rounded-xl border p-3 ${d.date === today ? 'border-primary bg-primary/5' : 'bg-card'}`}>
              <h2 className="mb-2 text-sm font-semibold">
                {new Date(`${d.date}T00:00:00`).toLocaleDateString(undefined, { weekday: 'short', day: 'numeric', month: 'short' })}
                <span className="ml-1 font-normal text-muted-foreground">({d.trips.length})</span>
              </h2>
              <div className="space-y-2">
                {d.trips.length === 0 && <p className="text-xs text-muted-foreground">{tx('No trips.')}</p>}
                {d.trips.map((t) => (
                  <div key={t.id} className="rounded-lg border bg-background p-2.5 text-sm shadow-sm">
                    <div className="flex items-center justify-between gap-2">
                      <Link to={`/fleet/trips/${t.id}`} className="font-medium text-primary hover:underline">{t.code}</Link>
                      <TripStatusPill status={t.status} />
                    </div>
                    <div className="text-xs text-muted-foreground">{t.partnerName}{t.siteName ? ` · ${t.siteName}` : ''} · {t.deliveryOrderCode}</div>
                    <div className="mt-1 text-xs">{carrierText(t)}</div>
                    {(t.loadedTonnes || t.plannedTonnes) && (
                      <div className="text-xs tabular-nums text-muted-foreground">
                        {num(t.loadedTonnes ?? t.plannedTonnes, 2)} t{t.capacityT ? ` / ${num(t.capacityT, 1)} t` : ''}
                      </div>
                    )}
                    {canPlan && ['planned', 'loading', 'weighed'].includes(t.status) && (
                      <Button size="sm" variant="outline" className="mt-2 h-7 w-full" onClick={() => setAssigning(t)}>
                        {t.vehicleId || t.transporterPartnerId ? tx('Reassign') : tx('Assign')}
                      </Button>
                    )}
                  </div>
                ))}
              </div>
            </section>
          ))}
        </div>
      )}
      <AssignDialog trip={assigning} onClose={() => setAssigning(null)} />
    </div>
  );
}

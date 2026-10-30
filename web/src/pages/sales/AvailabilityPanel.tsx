import { toast } from 'sonner';
import { PackageCheck, PackageX, RefreshCw } from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { Button } from '@/components/ui/button';
import { useAuthStore } from '@/stores/auth.store';
import { apiErrorMessage } from '@/lib/api-error';
import { date } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';
import { atpApi, useOrderAtp, useSalesMutation, type SalesOrder } from '@/features/sales/api';

/** Stock availability, reservations and incoming supply per order line (ADR-S011 S4). */
export function AvailabilityPanel({ order }: { order: SalesOrder }) {
  const open = ['confirmed', 'in_fulfilment'].includes(order.status);
  const { data } = useOrderAtp(order.id, order.status !== 'cancelled');
  const canReserve = useAuthStore((s) => s.hasPermission(PERMISSIONS.sales.orderConfirm));
  const reserve = useSalesMutation(atpApi.reserve);
  if (!data?.lines.length) return null;
  return (
    <div className="rounded-xl border bg-card p-4 text-sm shadow-sm">
      <div className="mb-2 flex items-center justify-between">
        <h2 className="font-semibold">{tx('Availability')}</h2>
        {open && canReserve && (
          <Button size="sm" variant="ghost" onClick={() => reserve.mutate(order.id, {
            onSuccess: (r) => toast.success(`${tx('Reserved')} ${r.reserved} · ${tx('backorder')} ${r.backorder}`),
            onError: (e) => toast.error(apiErrorMessage(e, tx('Could not reserve'))),
          })}><RefreshCw className="mr-1 h-3.5 w-3.5" />{tx('Re-plan')}</Button>
        )}
      </div>
      <ul className="space-y-2">
        {data.lines.map((l) => {
          const desc = order.lines?.find((x) => x.id === l.lineId)?.description ?? '';
          const full = (l.reserved ?? 0) >= l.quantity || l.canPromiseToday;
          return (
            <li key={l.lineId} className="rounded-lg border p-2">
              <p className="flex items-center gap-1.5 font-medium">{full ? <PackageCheck className="h-4 w-4 text-emerald-600" /> : <PackageX className="h-4 w-4 text-rose-600" />}{desc}</p>
              <p className="text-xs text-muted-foreground">{tx('Open')} {l.quantity} · {tx('reserved')} {l.reserved ?? 0} · {tx('free now')} {l.availableNow}</p>
              {l.shortage > 0 && <p className="text-xs text-rose-700">{tx('Short')} {l.shortage}{l.earliestDate ? ` · ${tx('full by')} ${date(l.earliestDate)}` : ` · ${tx('no supply planned')}`}</p>}
              {l.promiseAtRisk && <p className="text-xs font-medium text-rose-700">{tx('Promised date at risk')}</p>}
              {(l.pegs ?? []).map((p) => <p key={p.supplyNumber} className="text-xs text-muted-foreground">↳ {p.supplyNumber}: {p.quantity} {p.expectedDate ? date(p.expectedDate) : ''}</p>)}
            </li>
          );
        })}
      </ul>
      {!data.production.connected && <p className="mt-2 text-[11px] text-muted-foreground">{tx('Production supply is not connected yet: promises use stock and purchases only.')}</p>}
    </div>
  );
}

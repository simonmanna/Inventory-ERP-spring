import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { toast } from 'sonner';
import { Pencil, Truck } from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Textarea } from '@/components/ui/textarea';
import { Dialog, DialogContent, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { api } from '@/lib/api';
import { useAuthStore } from '@/stores/auth.store';
import { apiErrorMessage } from '@/lib/api-error';
import { date } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';
import type { SalesOrder } from '@/features/sales/api';
import { Field } from '@/pages/crm/crm-ui';

interface FulfilmentLine {
  id: string; description: string; quantity: number; qtyCancelled: number; qtyLoaded: number; qtyDispatched: number;
  qtyReceived: number; qtyAccepted: number; qtyBillable: number; qtyInvoiced: number; qtyCredited: number; billingBasis: string;
  decisions: Array<{ basis: string; quantity: number; previousQty: number; reason: string | null; createdAt: string }>;
}
interface Fulfilment { lines: FulfilmentLine[]; deliveries: Array<{ id: string; code: string; status: string; requestedDate: string; trips: number }> }

const n = (v: unknown) => Number(v ?? 0);

/** Deliveries and the commercial / physical / billable quantities per line (ADR-S011 S5). */
export function FulfilmentPanel({ order }: { order: SalesOrder }) {
  const navigate = useNavigate();
  const qc = useQueryClient();
  const has = useAuthStore((s) => s.hasPermission);
  const { data } = useQuery({ queryKey: ['sales', 'fulfilment', order.id], queryFn: async () => (await api.get<Fulfilment>(`/sales/orders/${order.id}/fulfilment`)).data });
  const deliver = useMutation({
    mutationFn: async () => (await api.post<{ id: string; code: string }>(`/sales/orders/${order.id}/delivery-orders`, {})).data,
    onSuccess: (d) => { qc.invalidateQueries({ queryKey: ['sales'] }); toast.success(tx('Delivery order') + ' ' + d.code); navigate(`/fleet/delivery-orders/${d.id}`); },
    onError: (e) => toast.error(apiErrorMessage(e, tx('Could not create the delivery order'))),
  });
  const [edit, setEdit] = useState<FulfilmentLine | null>(null);
  const [qty, setQty] = useState(0);
  const [reason, setReason] = useState('');
  const override = useMutation({
    mutationFn: async () => (await api.post(`/sales/orders/${order.id}/lines/${edit!.id}/billable`, { quantity: qty, reason })).data,
    onSuccess: () => { qc.invalidateQueries({ queryKey: ['sales'] }); setEdit(null); toast.success(tx('Billable quantity updated')); },
    onError: (e) => toast.error(apiErrorMessage(e, tx('Could not update'))),
  });
  if (!data) return null;
  const open = ['confirmed', 'in_fulfilment'].includes(order.status);
  const live = data.deliveries.some((d) => d.status !== 'cancelled' && d.status !== 'closed');
  return (
    <div className="rounded-xl border bg-card p-4 text-sm shadow-sm">
      <div className="mb-2 flex items-center justify-between">
        <h2 className="font-semibold">{tx('Fulfilment')}</h2>
        {open && !live && !order.onHold && has(PERMISSIONS.sales.orderConfirm) && (
          <Button size="sm" disabled={deliver.isPending} onClick={() => deliver.mutate()}><Truck className="mr-1 h-3.5 w-3.5" />{tx('Create delivery order')}</Button>
        )}
      </div>
      {data.deliveries.map((d) => (
        <button key={d.id} className="mb-1 block text-xs text-primary hover:underline" onClick={() => navigate(`/fleet/delivery-orders/${d.id}`)}>
          {d.code} · {d.status} · {d.trips} {tx('trips')} · {date(d.requestedDate)}
        </button>
      ))}
      <div className="mt-2 overflow-x-auto">
        <table className="w-full text-xs">
          <thead className="text-left text-muted-foreground"><tr><th>{tx('Line')}</th><th className="text-right">{tx('Ordered')}</th><th className="text-right">{tx('Dispatched')}</th>
            <th className="text-right">{tx('Received')}</th><th className="text-right">{tx('Accepted')}</th><th className="text-right">{tx('Billable')}</th><th className="text-right">{tx('Invoiced')}</th><th /></tr></thead>
          <tbody>
            {data.lines.map((l) => (
              <tr key={l.id} className="border-t align-top">
                <td className="py-1">{l.description}<span className="block text-[10px] text-muted-foreground">{tx('basis')}: {l.billingBasis}</span></td>
                <td className="py-1 text-right tabular-nums">{n(l.quantity) - n(l.qtyCancelled)}</td>
                <td className="py-1 text-right tabular-nums">{n(l.qtyDispatched)}</td>
                <td className="py-1 text-right tabular-nums">{n(l.qtyReceived)}</td>
                <td className="py-1 text-right tabular-nums">{n(l.qtyAccepted)}</td>
                <td className="py-1 text-right font-medium tabular-nums" title={l.decisions[0]?.reason ?? ''}>{n(l.qtyBillable)}{l.decisions[0]?.basis === 'manual' && ' *'}</td>
                <td className="py-1 text-right tabular-nums">{n(l.qtyInvoiced) - n(l.qtyCredited)}</td>
                <td className="py-1 pl-1">{has(PERMISSIONS.sales.billingOverride) && order.status !== 'draft' && (
                  <button className="rounded p-0.5 text-muted-foreground hover:bg-muted" title={tx('Override billable quantity')} onClick={() => { setEdit(l); setQty(n(l.qtyBillable)); setReason(''); }}><Pencil className="h-3 w-3" /></button>
                )}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      {edit && (
        <Dialog open onOpenChange={(v) => !v && setEdit(null)}>
          <DialogContent className="max-w-md">
            <DialogHeader><DialogTitle>{tx('Billable quantity')} — {edit.description}</DialogTitle></DialogHeader>
            <p className="text-xs text-muted-foreground">{tx('Weighed')} {n(edit.qtyDispatched)} · {tx('accepted')} {n(edit.qtyAccepted)} · {tx('invoiced')} {n(edit.qtyInvoiced) - n(edit.qtyCredited)}</p>
            <Field label={tx('Billable quantity')}><Input type="number" value={qty} onChange={(e) => setQty(Number(e.target.value))} /></Field>
            <Field label={tx('Reason')}><Textarea rows={2} value={reason} onChange={(e) => setReason(e.target.value)} /></Field>
            <DialogFooter>
              <Button variant="outline" onClick={() => setEdit(null)}>{tx('Cancel')}</Button>
              <Button disabled={!reason.trim() || override.isPending} onClick={() => override.mutate()}>{tx('Save decision')}</Button>
            </DialogFooter>
          </DialogContent>
        </Dialog>
      )}
    </div>
  );
}

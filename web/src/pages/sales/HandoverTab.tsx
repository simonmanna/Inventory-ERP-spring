import { useEffect, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { toast } from 'sonner';
import { CheckCircle2, PackageCheck, Save, Truck, XCircle } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { api } from '@/lib/api';
import { apiErrorMessage } from '@/lib/api-error';
import { date } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';
import { cn } from '@/lib/utils';
import type { HandoverForm } from './handover-draft';
import { HandoverDetails, HandoverSteps } from './HandoverDetails';

/** Delivery & fulfilment handover of a sales order / invoice (same model as the branch cash sale delivery tab). */
export type HandoverStatus = 'pending' | 'scheduled' | 'dispatched' | 'delivered' | 'failed';
export interface Handover {
  sourceType: 'sales_order' | 'invoice'; sourceId: string; stage: 'plan' | 'ready' | 'closed';
  method: 'pickup' | 'delivery'; status: HandoverStatus; locationId: string | null; locationName: string | null;
  address: string | null; contactName: string | null; contactPhone: string | null; scheduledDate: string | null;
  driver: string | null; vehicle: string | null; receivedBy: string | null; notes: string | null;
  deliveredAt: string | null; deliveredByName: string | null; saved: boolean;
  lines: Array<{ id: string; productName: string | null; description: string; uomCode: string | null; quantity: number; issuedQty: number | null; handedOverQty: number }>;
}

type Form = HandoverForm;
const RECEIPT: Array<keyof Form> = ['receivedBy', 'notes'];
const qty = (v: number | string | null | undefined) => Number(v ?? 0).toFixed(2);

function toForm(h: Handover): Form {
  return {
    method: h.method, locationId: h.locationId ?? '', address: h.address ?? '', contactName: h.contactName ?? '',
    contactPhone: h.contactPhone ?? '', scheduledDate: h.scheduledDate ? h.scheduledDate.slice(0, 10) : '',
    driver: h.driver ?? '', vehicle: h.vehicle ?? '', receivedBy: h.receivedBy ?? '', notes: h.notes ?? '',
  };
}

function useHandover(path: string | null) {
  return useQuery({ queryKey: ['handover', path], enabled: !!path, queryFn: async () => (await api.get<Handover>(path!)).data });
}

/**
 * Delivery & fulfilment tab: status flow, per-line fulfilment and the handover details (customer collect from a store
 * / warehouse, or delivery to site). {@code path} is `/sales/orders/:id/handover` or `/sales/invoices/:id/handover`.
 */
export function HandoverTab({ path, canEdit, readyHint, sourceLabel, issuedLabel = tx('Issued from stock') }: {
  path: string; canEdit: boolean; readyHint: string; sourceLabel: string; issuedLabel?: string;
}) {
  const qc = useQueryClient();
  const { data: h, isError, error, refetch } = useHandover(path);
  const [f, setF] = useState<Form | null>(null);
  useEffect(() => { if (h) setF(toForm(h)); }, [h]);
  const save = useMutation({
    mutationFn: async ({ body }: { body: Record<string, unknown>; ok: string }) => (await api.patch<Handover>(path, body)).data,
    onSuccess: (d, v) => { qc.setQueryData(['handover', path], d); qc.invalidateQueries({ queryKey: ['sales'] }); toast.success(v.ok); },
    onError: (e) => toast.error(apiErrorMessage(e, tx('Could not save the delivery details'))),
  });
  if (isError) {
    return (
      <div className="py-6 text-center text-sm">
        <p className="text-rose-600">{apiErrorMessage(error, tx('Could not load the delivery details'))}</p>
        <Button className="mt-2" size="sm" variant="outline" onClick={() => refetch()}>{tx('Retry')}</Button>
      </div>
    );
  }
  if (!h || !f) return <p className="py-6 text-center text-sm text-muted-foreground">{tx('Loading…')}</p>;

  const status = h.status;
  const closed = h.stage === 'closed';
  const ready = h.stage === 'ready';
  const done = status === 'delivered';
  const editable = canEdit && !closed;
  const busy = save.isPending;
  const locked = (k: keyof Form) => !editable || (done && !RECEIPT.includes(k));
  const isDelivery = f.method === 'delivery';
  const send = (extra?: Record<string, unknown>, ok = tx('Delivery details saved')) => {
    const b: Record<string, unknown> = {};
    (Object.keys(f) as Array<keyof Form>).forEach((k) => { if (!done || RECEIPT.includes(k)) b[k] = f[k] || null; });
    if (!isDelivery && !done) { b.driver = null; b.vehicle = null; }
    save.mutate({ body: { ...b, ...extra }, ok });
  };
  const ordered = h.lines.reduce((a, l) => a + Number(l.quantity), 0);
  const showIssued = h.lines.some((l) => l.issuedQty != null);
  const issued = h.lines.reduce((a, l) => a + Number(l.issuedQty ?? 0), 0);
  const locationName = h.locationName;

  return (
    <div className="space-y-5">
      {/* status */}
      <section className="flex flex-wrap items-center justify-between gap-3">
        <HandoverSteps status={status} isDelivery={isDelivery} />
        {editable && !done && (
          <div className="flex flex-wrap gap-2">
            {status !== 'scheduled' && <Button size="sm" variant="outline" disabled={busy} onClick={() => send({ status: 'scheduled' }, isDelivery ? tx('Delivery scheduled') : tx('Pickup scheduled'))}>{tx('Schedule')}</Button>}
            {isDelivery && status !== 'dispatched' && <Button size="sm" variant="outline" disabled={busy || !ready} title={ready ? undefined : readyHint} onClick={() => send({ status: 'dispatched' }, tx('Goods dispatched'))}><Truck className="mr-1 h-4 w-4" />{tx('Mark dispatched')}</Button>}
            <Button size="sm" disabled={busy || !ready} title={ready ? undefined : readyHint} onClick={() => send({ status: 'delivered' }, isDelivery ? tx('Delivery completed') : tx('Goods collected'))}>
              <CheckCircle2 className="mr-1 h-4 w-4" />{isDelivery ? tx('Mark delivered') : tx('Mark collected')}
            </Button>
            {status !== 'failed' && status !== 'pending' && <Button size="sm" variant="ghost" className="text-rose-600" disabled={busy} onClick={() => send({ status: 'failed' }, tx('Delivery marked failed'))}><XCircle className="mr-1 h-4 w-4" />{tx('Failed')}</Button>}
          </div>
        )}
      </section>
      {!ready && !closed && <p className="text-xs text-muted-foreground">{readyHint}. {tx('You can plan the handover now; dispatch and delivery wait for it.')}</p>}
      {closed && <p className="text-xs text-muted-foreground">{tx('This')} {sourceLabel} {tx('is cancelled; delivery details are read-only.')}</p>}
      {done && <p className="text-xs text-emerald-700">{tx('Completed')} {h.deliveredAt ? date(h.deliveredAt) : ''}{h.deliveredByName ? ` · ${h.deliveredByName}` : ''}</p>}

      {/* fulfilment */}
      <section>
        <h3 className="mb-2 flex items-center gap-1.5 text-sm font-semibold"><PackageCheck className="h-4 w-4" />{tx('Fulfilment')}</h3>
        <div className="mb-2 flex flex-wrap gap-x-6 gap-y-1 text-xs text-muted-foreground">
          <span>{isDelivery ? tx('Ships from') : tx('Collect from')}: <span className="font-medium text-foreground">{locationName ?? '—'}</span></span>
          {showIssued && <span>{tx('Issued')} {qty(issued)} / {qty(ordered)}</span>}
        </div>
        <div className="overflow-x-auto">
          <table className="w-full min-w-[560px] text-sm">
            <thead className="border-b text-left text-xs font-semibold uppercase text-muted-foreground"><tr>
              <th className="py-1.5">{tx('Product')}</th><th className="py-1.5 text-right">{tx('Ordered')}</th>
              {showIssued && <><th className="py-1.5 text-right">{issuedLabel}</th><th className="py-1.5 text-right">{tx('To issue')}</th></>}
              <th className="py-1.5 text-right">{tx('Handed over')}</th>
            </tr></thead>
            <tbody>
              {h.lines.map((l) => {
                const rem = Number(l.quantity) - Number(l.issuedQty ?? 0);
                return (
                  <tr key={l.id} className="border-b">
                    <td className="py-1.5"><span className="font-medium">{l.productName ?? l.description}</span>{l.productName && l.description && l.description !== l.productName && <span className="ml-1 text-xs text-muted-foreground">{l.description}</span>}</td>
                    <td className="py-1.5 text-right tabular-nums">{qty(l.quantity)} {l.uomCode ?? ''}</td>
                    {showIssued && <>
                      <td className="py-1.5 text-right tabular-nums">{qty(l.issuedQty)}</td>
                      <td className={cn('py-1.5 text-right tabular-nums', rem > 0 && 'text-amber-600')}>{qty(rem)}</td>
                    </>}
                    <td className="py-1.5 text-right tabular-nums">{qty(l.handedOverQty)}</td>
                  </tr>
                );
              })}
              {h.lines.length === 0 && <tr><td colSpan={5} className="py-3 text-center text-muted-foreground">{tx('No lines yet.')}</td></tr>}
            </tbody>
          </table>
        </div>
      </section>

      {/* delivery details */}
      <div>
        <HandoverDetails f={f} onChange={setF} locked={locked} fallbackLocationName={h.locationName} />
        {editable && <Button className="mt-3" size="sm" variant="secondary" disabled={busy} onClick={() => send()}><Save className="mr-1 h-4 w-4" />{tx('Save delivery details')}</Button>}
      </div>
    </div>
  );
}

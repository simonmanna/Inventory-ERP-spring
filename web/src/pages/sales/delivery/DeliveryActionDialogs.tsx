import { useRef, useState } from 'react';
import { AlertTriangle, Camera, X } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Textarea } from '@/components/ui/textarea';
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { SignaturePad, type SignaturePadHandle } from '@/components/signature-pad';
import { api } from '@/lib/api';
import { tx } from '@/lib/i18n/tx';
import { cn } from '@/lib/utils';
import { idem, readDataUrl, useDeliveryAction, type SalesDelivery } from '@/features/sales/delivery-api';
import { Labeled } from './DeliveryEditorDialog';
import { REASON_OPTIONS, fromLocalInput, n, qty, toLocalInput } from './delivery-ui';
import { CustomerWeightFields, emptyWeightForm, uploadWeightEvidence, weightBody, type WeightForm } from './CustomerWeight';

type Entry = { delivered: string; rejected: string; damaged: string; reason: string; notes: string };

/**
 * Proof of delivery: per product the accepted, rejected and damaged quantities (the rest is a shortage), the receiver,
 * arrival and confirmation times, signature, photos and remarks. Rejected goods go back into stock; nothing is issued
 * again.
 */
export function ConfirmDeliveryDialog({ delivery, onClose }: { delivery: SalesDelivery; onClose: () => void }) {
  const lines = delivery.lines ?? [];
  const [e, setE] = useState<Record<string, Entry>>(() => Object.fromEntries(lines.map((l) => [l.id, {
    delivered: String(n(l.dispatchedQty)), rejected: '', damaged: '', reason: '', notes: '',
  }])));
  const now = toLocalInput(new Date().toISOString());
  const [receiver, setReceiver] = useState(delivery.siteContactName ?? '');
  const [phone, setPhone] = useState(delivery.siteContactPhone ?? '');
  const [arrived, setArrived] = useState(now);
  const [received, setReceived] = useState(now);
  const [remarks, setRemarks] = useState('');
  const [photos, setPhotos] = useState<Array<{ name: string; dataUrl: string }>>([]);
  const pad = useRef<SignaturePadHandle>(null);
  const [weighOpen, setWeighOpen] = useState(false);
  const [weight, setWeight] = useState<WeightForm>(() => emptyWeightForm());
  const cw = weightBody(weight);
  const patch = (id: string, p: Partial<Entry>) => setE((x) => ({ ...x, [id]: { ...x[id], ...p } }));

  const rows = lines.map((l) => {
    const x = e[l.id];
    const sent = n(l.dispatchedQty);
    const shortage = sent - n(x.delivered) - n(x.rejected) - n(x.damaged);
    const needsReason = Math.abs(shortage) > 1e-9 && !x.reason;
    return { l, x, sent, shortage, needsReason };
  });
  const problems: string[] = [];
  if (!receiver.trim()) problems.push(tx('Enter the receiver name'));
  if (rows.some((r) => r.shortage < -1e-9)) problems.push(tx('Delivered, rejected and damaged cannot exceed what was dispatched'));
  if (rows.some((r) => r.needsReason)) problems.push(tx('Give a reason for each shortage'));
  if (rows.some((r) => [r.x.delivered, r.x.rejected, r.x.damaged].some((v) => n(v) < 0))) problems.push(tx('Quantities must not be less than 0'));
  if (arrived && received && new Date(arrived) > new Date(received)) problems.push(tx('Arrival must be before the confirmation time'));
  if (weighOpen) problems.push(...cw.problems);

  const confirm = useDeliveryAction(async () => {
    await api.post(`/sales/deliveries/${delivery.id}/confirm`, {
      receiverName: receiver.trim(), receiverPhone: phone.trim() || undefined, receivedAt: fromLocalInput(received) ?? undefined,
      arrivedAt: fromLocalInput(arrived) ?? undefined, notes: remarks.trim() || undefined, signature: pad.current?.dataUrl() ?? undefined,
      lines: rows.map(({ l, x }) => ({
        loadLineId: l.id, deliveredQty: n(x.delivered), rejectedQty: n(x.rejected), damagedQty: n(x.damaged),
        reason: x.reason || undefined, notes: x.notes || undefined,
      })),
      customerWeight: weighOpen && cw.body ? cw.body : undefined,
    }, idem());
    if (weighOpen && cw.body) await uploadWeightEvidence(delivery.id, weight);
    for (const p of photos) await api.post(`/sales/deliveries/${delivery.id}/photos`, { dataUrl: p.dataUrl, caption: p.name });
  }, tx('Delivery confirmed'));

  return (
    <Dialog open onOpenChange={(v) => !v && onClose()}>
      <DialogContent className="max-h-[92vh] max-w-4xl overflow-y-auto">
        <DialogHeader>
          <DialogTitle>{tx('Confirm delivery')} {delivery.code}</DialogTitle>
          <DialogDescription>{tx('Record what the customer actually received. Rejected goods return to stock; damaged and short quantities are traced as discrepancies.')}</DialogDescription>
        </DialogHeader>

        <div className="overflow-x-auto rounded-lg border">
          <table className="w-full min-w-[760px] text-sm">
            <thead className="bg-muted/40 text-left text-[11px] font-semibold uppercase tracking-wide text-muted-foreground">
              <tr>
                <th className="px-3 py-2">{tx('Product')}</th><th className="px-3 py-2 text-right">{tx('Dispatched')}</th>
                <th className="px-3 py-2 text-right">{tx('Accepted')}</th><th className="px-3 py-2 text-right">{tx('Rejected')}</th>
                <th className="px-3 py-2 text-right">{tx('Damaged')}</th><th className="px-3 py-2 text-right">{tx('Shortage')}</th>
                <th className="px-3 py-2">{tx('Reason')}</th>
              </tr>
            </thead>
            <tbody>
              {rows.map(({ l, x, sent, shortage, needsReason }) => (
                <tr key={l.id} className="border-t align-top">
                  <td className="px-3 py-2"><span className="font-medium">{l.productName}</span><span className="block text-xs text-muted-foreground">{l.uomCode}</span></td>
                  <td className="px-3 py-2 text-right tabular-nums">{qty(sent)}</td>
                  {(['delivered', 'rejected', 'damaged'] as const).map((k) => (
                    <td key={k} className="px-2 py-1.5">
                      <Input type="number" min={0} step="any" className="ml-auto h-8 w-24 text-right" value={x[k]} aria-label={`${k} ${l.productName}`}
                        onChange={(ev) => patch(l.id, { [k]: ev.target.value })} />
                    </td>
                  ))}
                  <td className={cn('px-3 py-2 text-right tabular-nums', shortage > 1e-9 && 'font-medium text-amber-600', shortage < -1e-9 && 'font-medium text-rose-600')}>{qty(shortage)}</td>
                  <td className="px-2 py-1.5">
                    <select className={cn('h-8 w-40 rounded-md border bg-background px-2 text-xs', needsReason && 'border-amber-500')} value={x.reason}
                      onChange={(ev) => patch(l.id, { reason: ev.target.value })}>
                      <option value="">{n(x.rejected) > 0 ? tx('Customer rejected') : n(x.damaged) > 0 ? tx('Damaged') : '—'}</option>
                      {REASON_OPTIONS.map(([v, lbl]) => <option key={v} value={v}>{lbl}</option>)}
                    </select>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>

        <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
          <Labeled label={tx('Receiver name')} required><Input value={receiver} onChange={(ev) => setReceiver(ev.target.value)} /></Labeled>
          <Labeled label={tx('Receiver phone')}><Input type="tel" value={phone} onChange={(ev) => setPhone(ev.target.value)} /></Labeled>
          <Labeled label={tx('Actual arrival')}><Input type="datetime-local" value={arrived} max={now} onChange={(ev) => setArrived(ev.target.value)} /></Labeled>
          <Labeled label={tx('Confirmed at')}><Input type="datetime-local" value={received} max={now} onChange={(ev) => setReceived(ev.target.value)} /></Labeled>
        </div>
        <div className="grid gap-4 md:grid-cols-2">
          <Labeled label={tx('Customer signature')}><SignaturePad ref={pad} /></Labeled>
          <div className="space-y-3">
            <Labeled label={tx('Customer remarks')}><Textarea rows={3} maxLength={2000} value={remarks} onChange={(ev) => setRemarks(ev.target.value)} /></Labeled>
            <div>
              <span className="text-xs font-medium text-muted-foreground">{tx('Photos / proof of delivery')}</span>
              <div className="mt-1 flex flex-wrap gap-2">
                {photos.map((p, i) => (
                  <span key={i} className="relative">
                    <img src={p.dataUrl} alt={p.name} className="h-16 w-16 rounded-md border object-cover" />
                    <button type="button" aria-label={tx('Remove')} className="absolute -right-1.5 -top-1.5 rounded-full border bg-background p-0.5"
                      onClick={() => setPhotos((ps) => ps.filter((_, j) => j !== i))}><X className="h-3 w-3" /></button>
                  </span>
                ))}
                <label className="flex h-16 w-16 cursor-pointer flex-col items-center justify-center rounded-md border border-dashed text-[10px] text-muted-foreground hover:bg-muted">
                  <Camera className="mb-0.5 h-4 w-4" />{tx('Add')}
                  <input type="file" accept="image/*" multiple className="hidden" onChange={async (ev) => {
                    const files = Array.from(ev.target.files ?? []);
                    ev.target.value = '';
                    const read = await Promise.all(files.map(async (f) => ({ name: f.name, dataUrl: await readDataUrl(f) })));
                    setPhotos((ps) => [...ps, ...read]);
                  }} />
                </label>
              </div>
            </div>
          </div>
        </div>

        <div className="rounded-lg border p-3">
          <label className="flex items-center gap-2 text-sm font-medium">
            <input type="checkbox" checked={weighOpen} onChange={(ev) => setWeighOpen(ev.target.checked)} />
            {tx('The customer weighed the truck')}
          </label>
          {weighOpen && (
            <div className="mt-3 space-y-2">
              <p className="text-xs text-muted-foreground">{tx('Evidence only: the quarry ticket, stock and invoice do not change. Lighter by more than the tolerance raises a weight claim for approval; a multi-product truck asks you to confirm the split afterwards on the proof of delivery.')}</p>
              <CustomerWeightFields form={weight} onChange={setWeight} />
            </div>
          )}
        </div>

        {problems.length > 0 && (
          <div className="flex items-start gap-2 rounded-lg border border-amber-200 bg-amber-50 px-3 py-2 text-xs text-amber-900 dark:border-amber-900 dark:bg-amber-950/30 dark:text-amber-200">
            <AlertTriangle className="mt-0.5 h-3.5 w-3.5 shrink-0" /><span>{problems.join(' · ')}</span>
          </div>
        )}
        <DialogFooter>
          <Button variant="outline" onClick={onClose}>{tx('Cancel')}</Button>
          <Button disabled={problems.length > 0 || confirm.isPending} onClick={() => confirm.mutate(undefined, { onSuccess: onClose })}>
            {confirm.isPending ? tx('Saving…') : tx('Confirm delivery')}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

export type ReasonKind = 'cancel' | 'fail' | 'return';

const REASON_COPY: Record<ReasonKind, { title: string; body: string; button: string; ok: string }> = {
  cancel: {
    title: tx('Cancel delivery'), button: tx('Cancel delivery'), ok: tx('Delivery cancelled'),
    body: tx('The goods have not left. Its quantities become available to schedule again.'),
  },
  fail: {
    title: tx('Delivery attempt failed'), button: tx('Record failed attempt'), ok: tx('Failed attempt recorded'),
    body: tx('The whole load comes back into the dispatch warehouse at its issue cost. You can then reschedule it.'),
  },
  return: {
    title: tx('Customer returned the load'), button: tx('Record return'), ok: tx('Return recorded'),
    body: tx('The customer refused the whole load. It comes back into stock and can be scheduled again.'),
  },
};

/** Cancel / failed attempt / whole-load return: a reason (and a reason code) is required. */
export function ReasonDialog({ delivery, kind, onClose }: { delivery: SalesDelivery; kind: ReasonKind; onClose: () => void }) {
  const copy = REASON_COPY[kind];
  const [reason, setReason] = useState('');
  const [code, setCode] = useState(kind === 'fail' ? 'site_closed' : 'customer_rejected');
  const run = useDeliveryAction(async () => {
    const path = `/sales/deliveries/${delivery.id}/${kind}`;
    if (kind === 'cancel') await api.post(path, { reason: reason.trim() });
    else await api.post(path, { reason: reason.trim(), reasonCode: code }, idem());
  }, copy.ok);
  return (
    <Dialog open onOpenChange={(v) => !v && onClose()}>
      <DialogContent className="max-w-md">
        <DialogHeader>
          <DialogTitle>{copy.title} — {delivery.code}</DialogTitle>
          <DialogDescription>{copy.body}</DialogDescription>
        </DialogHeader>
        {kind !== 'cancel' && (
          <Labeled label={tx('Reason code')}>
            <select className="h-9 w-full rounded-md border bg-background px-2 text-sm" value={code} onChange={(e) => setCode(e.target.value)}>
              {REASON_OPTIONS.map(([v, l]) => <option key={v} value={v}>{l}</option>)}
            </select>
          </Labeled>
        )}
        <Labeled label={tx('Reason')} required><Textarea rows={3} maxLength={500} value={reason} onChange={(e) => setReason(e.target.value)} /></Labeled>
        <DialogFooter>
          <Button variant="outline" onClick={onClose}>{tx('Back')}</Button>
          <Button variant={kind === 'cancel' ? 'destructive' : 'default'} disabled={!reason.trim() || run.isPending}
            onClick={() => run.mutate(undefined, { onSuccess: onClose })}>{copy.button}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

import { useState } from 'react';
import { idempotentPost } from '@/lib/idempotent-request';
import { FileUp, History, PencilLine, X } from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Skeleton } from '@/components/ui/skeleton';
import { Textarea } from '@/components/ui/textarea';
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { dateTime } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';
import { cn } from '@/lib/utils';
import { useAuthStore } from '@/stores/auth.store';
import {
  readDataUrl, useDeliveryAction, useLineHistory, type ClaimDecision, type DeliveryClaim, type DeliveryLine,
  type SalesDelivery,
} from '@/features/sales/delivery-api';
import { Labeled } from './DeliveryEditorDialog';
import { n, qty } from './delivery-ui';

/**
 * Phase A A3 (plan §2, §3, §9): every dispatched quantity sits in one bucket — accepted, returned, damaged, confirmed
 * shortage, or pending dispute. A dismissed POD shortage stays disputed until an authorised, evidenced POD correction
 * reconciles it. Each line has a quantity history (facts, claims, decisions, corrections).
 */

export const DECISION_META: Record<ClaimDecision, { label: string; className: string }> = {
  pending: { label: tx('Pending'), className: 'border-amber-200 bg-amber-50 text-amber-900 dark:border-amber-900 dark:bg-amber-950/30 dark:text-amber-200' },
  confirmed: { label: tx('Confirmed'), className: 'border-rose-200 bg-rose-50 text-rose-800 dark:border-rose-900 dark:bg-rose-950/30 dark:text-rose-200' },
  dismissed: { label: tx('Dismissed'), className: 'border-zinc-200 bg-zinc-50 text-zinc-700 dark:border-zinc-800 dark:bg-zinc-900 dark:text-zinc-300' },
  reconciled: { label: tx('Reconciled by POD correction'), className: 'border-emerald-200 bg-emerald-50 text-emerald-800 dark:border-emerald-900 dark:bg-emerald-950/30 dark:text-emerald-200' },
  withdrawn: { label: tx('Withdrawn'), className: 'border-zinc-200 bg-zinc-50 text-zinc-500 dark:border-zinc-800 dark:bg-zinc-900 dark:text-zinc-400' },
};

const KIND_LABEL: Record<DeliveryClaim['kind'], string> = {
  shortage: tx('Shortage'), excess: tx('Excess'), returned: tx('Returned'), weight: tx('Weight dispute'), damaged: tx('Damaged'),
};

const EVENT_LABEL: Record<string, string> = {
  dispatched: tx('Dispatched'), pod_recorded: tx('Proof of delivery'), returned: tx('Returned'),
  damaged_recorded: tx('Damaged recorded'), pod_corrected: tx('POD corrected'), customer_weight_recorded: tx('Customer weight'),
  claim_opened: tx('Claim opened'), claim_quantity_changed: tx('Claim quantity changed'), claim_confirmed: tx('Claim confirmed'),
  claim_dismissed: tx('Claim dismissed'), claim_reconciled: tx('Claim reconciled'), claim_withdrawn: tx('Claim withdrawn'),
  excess_recorded: tx('Excess recorded'),
};

export function DecisionBadge({ decision }: { decision: ClaimDecision }) {
  const m = DECISION_META[decision] ?? DECISION_META.pending;
  return <span className={cn('inline-flex items-center rounded-full border px-2 py-0.5 text-xs font-medium', m.className)}>{m.label}</span>;
}

/** Buckets per line, the delivery's claims, and the entry points to the line history and the POD correction. */
export function QuantityBucketsPanel({ d }: { d: SalesDelivery }) {
  const [historyLine, setHistoryLine] = useState<DeliveryLine | null>(null);
  const [correcting, setCorrecting] = useState(false);
  const canCorrect = useAuthStore((s) => s.hasPermission(PERMISSIONS.sales.deliveryPodCorrect));
  const lines = d.lines ?? [];
  const disputed = lines.some((l) => n(l.pendingDisputedQty) > 0);
  const delivered = d.stage === 'delivered' || d.stage === 'partially_delivered';
  return (
    <div className="space-y-3 rounded-xl border p-4">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <h4 className="text-sm font-semibold">{tx('Quantities')}</h4>
        {canCorrect && delivered && disputed && (
          <Button size="sm" variant="outline" onClick={() => setCorrecting(true)}><PencilLine className="mr-1.5 h-4 w-4" />{tx('Correct proof of delivery')}</Button>
        )}
      </div>
      <div className="overflow-x-auto">
        <table className="w-full min-w-[640px] text-sm">
          <thead className="text-left text-[11px] font-semibold uppercase tracking-wide text-muted-foreground">
            <tr>
              <th className="py-1.5 pr-3">{tx('Product')}</th>
              <th className="py-1.5 pr-3 text-right">{tx('Dispatched')}</th>
              <th className="py-1.5 pr-3 text-right">{tx('Accepted')}</th>
              <th className="py-1.5 pr-3 text-right">{tx('Returned')}</th>
              <th className="py-1.5 pr-3 text-right">{tx('Damaged')}</th>
              <th className="py-1.5 pr-3 text-right">{tx('Confirmed shortage')}</th>
              <th className="py-1.5 pr-3 text-right">{tx('Pending dispute')}</th>
              <th className="py-1.5 pr-3 text-right">{tx('Excess')}</th>
              <th className="py-1.5" />
            </tr>
          </thead>
          <tbody>
            {lines.map((l) => (
              <tr key={l.id} className="border-t">
                <td className="py-1.5 pr-3 font-medium">{l.productName}<span className="ml-1 text-xs font-normal text-muted-foreground">{l.uomCode}</span></td>
                <td className="py-1.5 pr-3 text-right tabular-nums">{l.dispatchedQty == null ? '—' : qty(l.dispatchedQty)}</td>
                <td className="py-1.5 pr-3 text-right font-medium tabular-nums">{l.deliveredQty == null ? '—' : qty(l.acceptedQty)}</td>
                <td className="py-1.5 pr-3 text-right tabular-nums">{qty(l.returnedQty)}</td>
                <td className={cn('py-1.5 pr-3 text-right tabular-nums', n(l.damagedQty) > 0 && 'text-rose-600')}>{qty(l.damagedQty)}</td>
                <td className={cn('py-1.5 pr-3 text-right tabular-nums', n(l.confirmedShortageQty) > 0 && 'text-rose-600')}>{qty(l.confirmedShortageQty)}</td>
                <td className={cn('py-1.5 pr-3 text-right tabular-nums', n(l.pendingDisputedQty) > 0 && 'font-medium text-amber-600')}>{qty(l.pendingDisputedQty)}</td>
                <td className={cn('py-1.5 pr-3 text-right tabular-nums', n(l.excessQty) > 0 && 'text-sky-600')}>{qty(l.excessQty)}</td>
                <td className="py-1.5 text-right">
                  <Button size="sm" variant="ghost" className="h-7 px-2" onClick={() => setHistoryLine(l)} title={tx('Quantity history')}>
                    <History className="h-4 w-4" />
                  </Button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      {(d.claims?.length ?? 0) > 0 && (
        <div>
          <p className="mb-1.5 text-[11px] font-medium uppercase tracking-wide text-muted-foreground">{tx('Claims')}</p>
          <ul className="divide-y rounded-lg border text-sm">
            {d.claims!.map((c) => (
              <li key={c.id} className="flex flex-wrap items-center gap-2 px-3 py-2">
                <span className="font-mono text-xs">{c.code}</span>
                <span>{KIND_LABEL[c.kind] ?? c.kind}</span>
                <span className="tabular-nums">{qty(c.quantity)}</span>
                {n(c.reconciledQty) > 0 && <span className="text-xs text-muted-foreground">{tx('reconciled')} {qty(c.reconciledQty)}</span>}
                <DecisionBadge decision={c.claimDecision} />
                {c.claimDecision === 'dismissed' && c.kind === 'shortage' && (
                  <span className="text-xs text-amber-700 dark:text-amber-300">{tx('Still disputed — a POD correction is required')}</span>
                )}
                {(c.dismissReason || c.decisionNotes) && <span className="w-full text-xs text-muted-foreground">{c.dismissReason ?? c.decisionNotes}</span>}
              </li>
            ))}
          </ul>
        </div>
      )}
      {historyLine && <LineHistoryDialog deliveryId={d.id} line={historyLine} onClose={() => setHistoryLine(null)} />}
      {correcting && <PodCorrectionDialog d={d} onClose={() => setCorrecting(false)} />}
    </div>
  );
}

/** Quantity history of one delivery line, oldest first. Lines from before A3 show a reconstruction. */
export function LineHistoryDialog({ deliveryId, line, onClose }: { deliveryId: string; line: DeliveryLine; onClose: () => void }) {
  const { data, isLoading } = useLineHistory(deliveryId, line.id);
  const reconstructed = data?.events.some((e) => e.reconstructed);
  return (
    <Dialog open onOpenChange={(v) => !v && onClose()}>
      <DialogContent className="max-h-[85vh] max-w-2xl overflow-y-auto">
        <DialogHeader>
          <DialogTitle>{tx('Quantity history')} — {line.productName}</DialogTitle>
          {reconstructed && <DialogDescription>{tx('Recorded before quantity history existed: reconstructed from the line, its proof of delivery and its claims.')}</DialogDescription>}
        </DialogHeader>
        {isLoading || !data ? <Skeleton className="h-40 w-full" /> : (
          <ol className="space-y-2">
            {data.events.map((e, i) => (
              <li key={e.id ?? i} className="rounded-lg border px-3 py-2 text-sm">
                <div className="flex flex-wrap items-center justify-between gap-2">
                  <span className="font-medium">{EVENT_LABEL[e.kind] ?? e.kind}</span>
                  <span className="text-xs text-muted-foreground">{dateTime(e.at)}{e.userName ? ` · ${e.userName}` : ''}</span>
                </div>
                {e.newQty != null && (
                  <p className="tabular-nums text-muted-foreground">
                    {e.previousQty != null && n(e.previousQty) !== 0 ? `${qty(e.previousQty)} → ` : ''}{qty(e.newQty)} {line.uomCode ?? ''}
                    {e.bucket ? <span className="ml-2 text-xs">({e.bucket.replace('_', ' ')})</span> : null}
                  </p>
                )}
                {e.reason && <p className="text-xs text-muted-foreground">{e.reason}</p>}
                {e.evidenceFileIds.length > 0 && <p className="text-xs text-muted-foreground">{tx('Evidence files')}: {e.evidenceFileIds.length}</p>}
              </li>
            ))}
          </ol>
        )}
      </DialogContent>
    </Dialog>
  );
}

/**
 * Authorised POD correction: raise a line's delivered quantity (up to dispatched − returned − damaged) against its open
 * shortage claims, with a reason and evidence. The person who recorded the POD cannot correct it (the server says so).
 */
export function PodCorrectionDialog({ d, onClose }: { d: SalesDelivery; onClose: () => void }) {
  const lines = (d.lines ?? []).filter((l) => n(l.pendingDisputedQty) > 0 && l.deliveredQty != null);
  const [values, setValues] = useState<Record<string, string>>({});
  const [reason, setReason] = useState('');
  const [files, setFiles] = useState<Array<{ name: string; dataUrl: string }>>([]);
  const save = useDeliveryAction(async () => {
    const body = {
      lines: lines.filter((l) => values[l.id] && n(values[l.id]) > n(l.deliveredQty))
        .map((l) => ({ loadLineId: l.id, deliveredQty: Number(values[l.id]) })),
      reason: reason.trim(),
      evidence: files.map((f) => f.dataUrl),
    };
    await idempotentPost(`/sales/deliveries/${d.id}/pod-correction`, body);
  }, tx('Proof of delivery corrected'));
  const changed = lines.some((l) => values[l.id] && n(values[l.id]) > n(l.deliveredQty));
  const invalid = lines.some((l) => values[l.id] && (n(values[l.id]) < n(l.deliveredQty)
    || n(values[l.id]) > n(l.dispatchedQty) - n(l.returnedQty) - n(l.damagedQty)));
  return (
    <Dialog open onOpenChange={(v) => !v && onClose()}>
      <DialogContent className="max-w-xl">
        <DialogHeader>
          <DialogTitle>{tx('Correct proof of delivery')}</DialogTitle>
          <DialogDescription>{tx('Raise the delivered quantity where the evidence shows more arrived. The correction closes that much of the open shortage claims; it is recorded in the line history.')}</DialogDescription>
        </DialogHeader>
        <div className="space-y-3">
          {lines.map((l) => {
            const ceiling = n(l.dispatchedQty) - n(l.returnedQty) - n(l.damagedQty);
            return (
              <Labeled key={l.id} label={`${l.productName ?? ''} — ${tx('delivered now')} ${qty(l.deliveredQty)}, ${tx('at most')} ${qty(ceiling)} ${l.uomCode ?? ''}`}>
                <Input type="number" step="any" min={n(l.deliveredQty)} max={ceiling} value={values[l.id] ?? ''}
                  onChange={(e) => setValues((v) => ({ ...v, [l.id]: e.target.value }))} />
              </Labeled>
            );
          })}
          <Labeled label={tx('Reason')} required>
            <Textarea rows={2} value={reason} onChange={(e) => setReason(e.target.value)} maxLength={1000} />
          </Labeled>
          <div>
            <label className="inline-flex h-8 cursor-pointer items-center rounded-md border bg-background px-3 text-xs hover:bg-muted">
              <FileUp className="mr-1.5 h-3.5 w-3.5" />{tx('Add evidence (photo or PDF)')}
              <input type="file" accept="image/*,application/pdf" className="hidden" onChange={async (e) => {
                const f = e.target.files?.[0]; e.target.value = '';
                if (f) { const dataUrl = await readDataUrl(f); setFiles((x) => [...x, { name: f.name, dataUrl }]); }
              }} />
            </label>
            <ul className="mt-2 space-y-1 text-xs">
              {files.map((f, i) => (
                <li key={i} className="flex items-center gap-2">{f.name}
                  <button type="button" className="text-muted-foreground hover:text-foreground" onClick={() => setFiles((x) => x.filter((_, j) => j !== i))}>
                    <X className="h-3.5 w-3.5" />
                  </button>
                </li>
              ))}
            </ul>
          </div>
        </div>
        <DialogFooter>
          <Button variant="ghost" onClick={onClose}>{tx('Cancel')}</Button>
          <Button disabled={!changed || invalid || !reason.trim() || files.length === 0 || save.isPending}
            onClick={() => save.mutate(undefined, { onSuccess: onClose })}>{tx('Correct')}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

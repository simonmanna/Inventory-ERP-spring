import { useMemo, useState } from 'react';
import { Check, History, PencilLine, X } from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Dialog, DialogContent, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Textarea } from '@/components/ui/textarea';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { StatusPill } from '@/components/list';
import { useAuthStore } from '@/stores/auth.store';
import { errMsg, poChangeApi, usePoChangeMutation, usePoChanges, usePoLifecycle, type ChangeStatus, type PoRevisionSnapshot } from '@/features/procurement/api';
import { notify } from '@/lib/notify';
import { dateTime, money } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';

const TONE: Record<ChangeStatus, 'neutral' | 'info' | 'success' | 'warning' | 'danger'> = {
  pending: 'warning',
  approved: 'info',
  applied: 'success',
  rejected: 'danger',
  cancelled: 'neutral',
};

const LABEL: Record<ChangeStatus, string> = {
  pending: tx('Waiting for approval'),
  approved: tx('Approved'),
  applied: tx('Applied'),
  rejected: tx('Rejected'),
  cancelled: tx('Withdrawn'),
};

type Draft = Record<string, { quantity: string; unitPrice: string }>;

/**
 * Order changes after approval (ADR-PROCUREMENT-001 wave 5): ask for a quantity / price / date change with a reason;
 * decreases apply at once, increases above the policy wait for a second approver. Shows every revision with what
 * changed against the one before.
 */
export function PoChangesCard({ id }: { id: string }) {
  const { data: po } = usePoLifecycle(id);
  const { data: history } = usePoChanges(id);
  const has = useAuthStore((s) => s.hasPermission);
  const userId = useAuthStore((s) => s.user?.id);
  const canRequest = has(PERMISSIONS.poChange.request);
  const canApprove = has(PERMISSIONS.poChange.approve);
  const mutate = usePoChangeMutation((fn: () => Promise<unknown>) => fn());
  const [open, setOpen] = useState(false);
  const [reason, setReason] = useState('');
  const [delivery, setDelivery] = useState('');
  const [draft, setDraft] = useState<Draft>({});
  const [showRevisions, setShowRevisions] = useState(false);

  const lines = po?.lines ?? [];
  const isOpen = po?.status === 'active' || po?.status === 'partially_received';
  const newTotal = useMemo(
    () => lines.reduce((s, l) => s + Number(draft[l.id]?.quantity ?? l.quantity) * Number(draft[l.id]?.unitPrice ?? l.unitPrice), 0),
    [lines, draft],
  );
  if (!po || !history) return null;
  if (!isOpen && !history.changes.length) return null;

  const start = () => {
    setDraft(Object.fromEntries(lines.map((l) => [l.id, { quantity: String(Number(l.quantity)), unitPrice: String(Number(l.unitPrice)) }])));
    setReason('');
    setDelivery('');
    setOpen(true);
  };

  const submit = async () => {
    const changed = lines
      .map((l) => {
        const d = draft[l.id];
        const q = Number(d.quantity);
        const p = Number(d.unitPrice);
        return {
          lineId: l.id,
          quantity: q !== Number(l.quantity) ? q : undefined,
          unitPrice: p !== Number(l.unitPrice) ? p : undefined,
        };
      })
      .filter((c) => c.quantity !== undefined || c.unitPrice !== undefined);
    try {
      const res = (await mutate.mutateAsync(() =>
        poChangeApi.request(id, { reason, expectedDeliveryDate: delivery || undefined, lines: changed }),
      )) as { status: ChangeStatus };
      notify.success(res.status === 'applied' ? tx('Order changed') : tx('Change sent for approval'));
      setOpen(false);
    } catch (e) {
      notify.error(tx('Could not change the order'), errMsg(e));
    }
  };

  const decide = async (changeId: string, approve: boolean) => {
    try {
      if (approve) {
        await mutate.mutateAsync(() => poChangeApi.approve(changeId));
        notify.success(tx('Change approved and applied'));
      } else {
        const note = window.prompt(tx('Why is the change rejected?')) ?? '';
        if (!note.trim()) return;
        await mutate.mutateAsync(() => poChangeApi.reject(changeId, note));
        notify.success(tx('Change rejected'));
      }
    } catch (e) {
      notify.error(tx('Action failed'), errMsg(e));
    }
  };

  const withdraw = async (changeId: string) => {
    try {
      await mutate.mutateAsync(() => poChangeApi.cancel(changeId));
      notify.success(tx('Change withdrawn'));
    } catch (e) {
      notify.error(tx('Action failed'), errMsg(e));
    }
  };

  const pending = history.changes.find((c) => c.status === 'pending');
  const revisions = [...history.revisions].sort((a, b) => a.revision - b.revision);

  return (
    <Card>
      <CardHeader className="flex flex-row flex-wrap items-center justify-between gap-2">
        <CardTitle>
          {tx('Changes')} {history.revision > 0 && <span className="ml-2 text-sm font-normal text-muted-foreground">{tx('Revision {{n}}', { n: history.revision })}</span>}
        </CardTitle>
        <div className="flex gap-2">
          {revisions.length > 1 && (
            <Button size="sm" variant="ghost" onClick={() => setShowRevisions((v) => !v)}><History className="mr-1 h-4 w-4" />{tx('Revisions')}</Button>
          )}
          {canRequest && isOpen && !pending && (
            <Button size="sm" variant="outline" onClick={start}><PencilLine className="mr-1 h-4 w-4" />{tx('Request change')}</Button>
          )}
        </div>
      </CardHeader>
      <CardContent className="space-y-3">
        {!history.changes.length && <p className="text-sm text-muted-foreground">{tx('No changes since the order was approved.')}</p>}
        {history.changes.map((c) => (
          <div key={c.id} className="flex flex-wrap items-center justify-between gap-2 rounded-md border p-3 text-sm">
            <div>
              <div className="font-medium">#{c.changeNumber} · {c.reason}</div>
              <div className="text-xs text-muted-foreground">
                {money(c.oldTotal, po.currencyCode)} → {money(c.newTotal, po.currencyCode)} · {dateTime(c.createdAt)}
                {c.decisionNote ? ` · ${c.decisionNote}` : ''}
              </div>
            </div>
            <div className="flex items-center gap-2">
              <StatusPill tone={TONE[c.status]}>{LABEL[c.status]}</StatusPill>
              {c.status === 'pending' && canApprove && c.requestedBy !== userId && (
                <>
                  <Button size="sm" onClick={() => decide(c.id, true)}><Check className="mr-1 h-4 w-4" />{tx('Approve')}</Button>
                  <Button size="sm" variant="ghost" onClick={() => decide(c.id, false)}><X className="mr-1 h-4 w-4" />{tx('Reject')}</Button>
                </>
              )}
              {c.status === 'pending' && c.requestedBy === userId && (
                <Button size="sm" variant="ghost" onClick={() => withdraw(c.id)}>{tx('Withdraw')}</Button>
              )}
            </div>
          </div>
        ))}
        {showRevisions && <RevisionDiff revisions={revisions.map((r) => ({ revision: r.revision, snapshot: r.snapshot, at: r.createdAt }))} currency={po.currencyCode} />}
      </CardContent>

      <Dialog open={open} onOpenChange={setOpen}>
        <DialogContent className="max-w-2xl">
          <DialogHeader><DialogTitle>{tx('Request an order change')}</DialogTitle></DialogHeader>
          <div className="space-y-3">
            <Table>
              <TableHeader><TableRow>
                <TableHead>{tx('Item')}</TableHead><TableHead className="text-right">{tx('Received')}</TableHead>
                <TableHead className="w-28">{tx('Quantity')}</TableHead><TableHead className="w-32">{tx('Unit price')}</TableHead>
              </TableRow></TableHeader>
              <TableBody>
                {lines.map((l) => (
                  <TableRow key={l.id}>
                    <TableCell>{l.description}</TableCell>
                    <TableCell className="text-right">{Number(l.receivedQuantity)}</TableCell>
                    <TableCell>
                      <Input type="number" min={Number(l.receivedQuantity)} value={draft[l.id]?.quantity ?? ''}
                        onChange={(e) => setDraft((d) => ({ ...d, [l.id]: { ...d[l.id], quantity: e.target.value } }))} />
                    </TableCell>
                    <TableCell>
                      <Input type="number" min={0} disabled={Number(l.receivedQuantity) > 0} value={draft[l.id]?.unitPrice ?? ''}
                        onChange={(e) => setDraft((d) => ({ ...d, [l.id]: { ...d[l.id], unitPrice: e.target.value } }))} />
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
            <div className="grid gap-3 sm:grid-cols-2">
              <div>
                <Label>{tx('New expected delivery')}</Label>
                <Input type="date" value={delivery} onChange={(e) => setDelivery(e.target.value)} />
              </div>
              <div className="self-end text-sm text-muted-foreground">
                {tx('New total (before tax)')}: <span className="font-semibold text-foreground">{money(newTotal, po.currencyCode)}</span>
              </div>
            </div>
            <div>
              <Label>{tx('Reason')}</Label>
              <Textarea value={reason} onChange={(e) => setReason(e.target.value)} placeholder={tx('Why the order must change')} />
            </div>
            <p className="text-xs text-muted-foreground">{tx('Reductions apply immediately. Increases may need a second approver; the supplier is asked to confirm the order again.')}</p>
          </div>
          <DialogFooter>
            <Button variant="ghost" onClick={() => setOpen(false)}>{tx('Cancel')}</Button>
            <Button onClick={submit} disabled={!reason.trim() || mutate.isPending}>{tx('Submit change')}</Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </Card>
  );
}

/** Each revision against the one before: changed quantity, price and line total highlighted. */
function RevisionDiff({ revisions, currency }: { revisions: Array<{ revision: number; snapshot: PoRevisionSnapshot; at: string }>; currency: string }) {
  return (
    <div className="space-y-3">
      {revisions.slice(1).map((r, i) => {
        const prev = revisions[i].snapshot;
        const before = new Map(prev.lines.map((l) => [l.id, l]));
        return (
          <div key={r.revision} className="rounded-md border">
            <div className="flex justify-between border-b px-3 py-2 text-sm">
              <span className="font-medium">{tx('Revision {{n}}', { n: r.revision })}</span>
              <span className="text-muted-foreground">{money(prev.totalAmount, currency)} → {money(r.snapshot.totalAmount, currency)} · {dateTime(r.at)}</span>
            </div>
            <Table>
              <TableBody>
                {r.snapshot.lines.map((l) => {
                  const b = before.get(l.id);
                  const qty = b && Number(b.quantity) !== Number(l.quantity);
                  const price = b && Number(b.unitPrice) !== Number(l.unitPrice);
                  if (!qty && !price) return null;
                  return (
                    <TableRow key={l.id}>
                      <TableCell>{l.description}</TableCell>
                      <TableCell className={qty ? 'font-medium text-amber-700' : ''}>{qty ? `${Number(b!.quantity)} → ${Number(l.quantity)}` : Number(l.quantity)}</TableCell>
                      <TableCell className={price ? 'font-medium text-amber-700' : ''}>{price ? `${money(b!.unitPrice, currency)} → ${money(l.unitPrice, currency)}` : money(l.unitPrice, currency)}</TableCell>
                    </TableRow>
                  );
                })}
              </TableBody>
            </Table>
            {prev.expectedDeliveryDate !== r.snapshot.expectedDeliveryDate && (
              <div className="px-3 py-2 text-xs text-muted-foreground">{tx('Expected delivery')}: {prev.expectedDeliveryDate ?? '—'} → {r.snapshot.expectedDeliveryDate ?? '—'}</div>
            )}
          </div>
        );
      })}
    </div>
  );
}

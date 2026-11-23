import { useState } from 'react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { DoorOpen, Plus, RotateCcw, Send, X } from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card';
import { Button } from '@/components/ui/button';
import { Badge } from '@/components/ui/badge';
import { Skeleton } from '@/components/ui/skeleton';
import { Label } from '@/components/ui/label';
import { Textarea } from '@/components/ui/textarea';
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { ConfirmDialog } from '@/components/confirm-dialog';
import { Link } from 'react-router-dom';
import { api } from '@/lib/api';
import { notifyApiError } from '@/lib/api-error';
import { idempotentPatch } from '@/lib/idempotent-request';
import { notify } from '@/lib/notify';
import { useAuthStore } from '@/stores/auth.store';
import { formatMoney } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';
import { GatePassPanel } from '@/features/gate-pass/GatePassPanel';

interface DebitNote {
  id: string;
  noteNumber: string;
  direction: 'outbound' | 'inbound';
  partnerId: string;
  partner?: { name: string };
  totalAmount: number | string;
  currencyCode: string;
  status: 'draft' | 'posted' | 'cancelled' | 'reversed';
  reason: string;
  issueDate: string;
}

/** The most notes `GET /procurement/debit-notes` returns (newest issue date first). */
const LIST_LIMIT = 200;

export function DebitNotesPage() {
  const qc = useQueryClient();
  const org = useAuthStore((s) => s.organization);
  const [direction, setDirection] = useState('');
  const [gatePassFor, setGatePassFor] = useState<string | null>(null);
  const [status, setStatus] = useState('');
  const list = useQuery<DebitNote[]>({
    queryKey: ['debit-notes', direction, status],
    queryFn: async () => (await api.get<DebitNote[]>('/procurement/debit-notes', {
      params: { direction: direction || undefined, status: status || undefined },
    })).data,
  });
  const [confirming, setConfirming] = useState<{ note: DebitNote; action: 'post' | 'cancel' } | null>(null);
  const act = useMutation({
    mutationFn: async (vars: { id: string; action: 'post' | 'cancel' }) =>
      await idempotentPatch(`/procurement/debit-notes/${vars.id}/${vars.action}`),
    onSuccess: () => {
      notify.success(tx('Updated'));
      setConfirming(null);
      qc.invalidateQueries({ queryKey: ['debit-notes'] });
    },
    onError: (e: unknown) => notifyApiError(e, tx('Failed')),
  });
  // Document reversal of a posted note (owner decision 2026-11-17): GL mirrored, the referenced invoice / bill
  // restored, returned goods brought back; a reason is required.
  const has = useAuthStore((s) => s.hasPermission);
  const canPost = has(PERMISSIONS.debitNote.post);
  const canCancel = has(PERMISSIONS.debitNote.cancel);
  const [reversing, setReversing] = useState<DebitNote | null>(null);
  const [reverseReason, setReverseReason] = useState('');
  const reverse = useMutation({
    mutationFn: async (vars: { id: string; reason: string }) =>
      await idempotentPatch(`/procurement/debit-notes/${vars.id}/reverse`, { reason: vars.reason }),
    onSuccess: () => {
      notify.success(tx('Debit note reversed'));
      setReversing(null);
      setReverseReason('');
      qc.invalidateQueries({ queryKey: ['debit-notes'] });
      qc.invalidateQueries({ queryKey: ['invoices'] });
      qc.invalidateQueries({ queryKey: ['invoice'] });
    },
    onError: (e: unknown) => notifyApiError(e, tx('Failed')),
  });

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-semibold">{tx('Debit Notes')}</h1>
          <p className="text-sm text-muted-foreground">
            {tx('Increases to AR (outbound to customers) or AP (inbound from suppliers). Posts via PostingService.')}
          </p>
        </div>
        {has(PERMISSIONS.debitNote.create) && (
          <Button asChild>
            <Link to="/procurement/debit-notes/new"><Plus className="mr-2 h-4 w-4" />{tx('New debit note')}</Link>
          </Button>
        )}
      </div>

      <div className="flex flex-wrap items-center gap-2">
        <Select value={direction} onValueChange={setDirection}>
          <SelectTrigger className="h-9 w-48" aria-label={tx('Direction')}><SelectValue placeholder={tx('All directions')} /></SelectTrigger>
          <SelectContent>
            <SelectItem value="">{tx('All directions')}</SelectItem>
            <SelectItem value="outbound">{tx('To customer')}</SelectItem>
            <SelectItem value="inbound">{tx('From supplier')}</SelectItem>
          </SelectContent>
        </Select>
        <Select value={status} onValueChange={setStatus}>
          <SelectTrigger className="h-9 w-40" aria-label={tx('Status')}><SelectValue placeholder={tx('All statuses')} /></SelectTrigger>
          <SelectContent>
            <SelectItem value="">{tx('All statuses')}</SelectItem>
            <SelectItem value="draft">{tx('Draft')}</SelectItem>
            <SelectItem value="posted">{tx('Posted')}</SelectItem>
            <SelectItem value="cancelled">{tx('Cancelled')}</SelectItem>
            <SelectItem value="reversed">{tx('Reversed')}</SelectItem>
          </SelectContent>
        </Select>
      </div>

      {list.data && list.data.length >= LIST_LIMIT && (
        <p role="status" className="rounded-md border border-amber-300 bg-amber-50 px-3 py-2 text-sm text-amber-800">
          {tx('Showing the {{n}} most recent debit notes only. Filter by direction or status to find older ones.', { n: LIST_LIMIT })}
        </p>
      )}
      {list.isLoading && <Skeleton className="h-32 w-full" />}
      {list.data && list.data.length === 0 && (
        <Card>
          <CardContent className="p-8 text-center text-sm text-muted-foreground">
            {direction || status ? tx('No debit notes match these filters.') : tx('No debit notes yet.')}
          </CardContent>
        </Card>
      )}

      <div className="grid gap-3 md:grid-cols-2">
        {list.data?.map((n) => (
          <Card key={n.id}>
            <CardHeader>
              <div className="flex items-start justify-between">
                <div>
                  <CardTitle className="text-base">{n.noteNumber}</CardTitle>
                  <CardDescription>
                    {n.direction === 'outbound' ? tx('To customer') : tx('From supplier')} · {n.partner?.name ?? n.partnerId} · {n.reason.replace('_', ' ')}
                  </CardDescription>
                </div>
                <Badge variant={n.direction === 'outbound' ? 'default' : 'secondary'}>{n.direction}</Badge>
              </div>
            </CardHeader>
            <CardContent className="space-y-3 text-sm">
              <div className="flex justify-between">
                <span className="text-muted-foreground">{tx('Total')}</span>
                <span className="font-semibold">{formatMoney(n.totalAmount, n.currencyCode ?? org?.currencyCode)}</span>
              </div>
              <div className="flex justify-between text-xs">
                <span className="text-muted-foreground">{tx('Status')}</span>
                <Badge variant={n.status === 'cancelled' || n.status === 'reversed' ? 'destructive' : n.status === 'posted' ? 'default' : 'outline'}>
                  {n.status}
                </Badge>
              </div>
              <div className="flex gap-1 border-t pt-2">
                {n.status === 'draft' && canPost && (
                  <Button size="sm" disabled={act.isPending} onClick={() => setConfirming({ note: n, action: 'post' })}>
                    <Send className="mr-1 h-3 w-3" />{tx('Post')}
                  </Button>
                )}
                {n.status === 'draft' && canCancel && (
                  <Button size="sm" variant="ghost" disabled={act.isPending} onClick={() => setConfirming({ note: n, action: 'cancel' })}>
                    <X className="mr-1 h-3 w-3 text-destructive" />{tx('Cancel')}
                  </Button>
                )}
                {n.status === 'posted' && has(PERMISSIONS.debitNote.reverse) && (
                  <Button size="sm" variant="ghost" onClick={() => setReversing(n)}>
                    <RotateCcw className="mr-1 h-3 w-3 text-destructive" />{tx('Reverse')}
                  </Button>
                )}
                {n.direction === 'inbound' && n.reason === 'returned_goods' && (n.status === 'draft' || n.status === 'posted') && (
                  <Button size="sm" variant="ghost" onClick={() => setGatePassFor(gatePassFor === n.id ? null : n.id)}>
                    <DoorOpen className="mr-1 h-3 w-3" />{tx('Gate pass')}
                  </Button>
                )}
              </div>
              {gatePassFor === n.id && n.direction === 'inbound' && n.reason === 'returned_goods' && (
                <GatePassPanel sourceType="supplier_return" sourceId={n.id} compact />
              )}
            </CardContent>
          </Card>
        ))}
      </div>

      <ConfirmDialog
        open={!!confirming}
        onOpenChange={(o) => { if (!o) setConfirming(null); }}
        title={confirming?.action === 'cancel'
          ? tx('Cancel {{noteNumber}}?', { noteNumber: confirming.note.noteNumber })
          : tx('Post {{noteNumber}}?', { noteNumber: confirming?.note.noteNumber ?? '' })}
        description={<p>{confirming?.action === 'cancel'
          ? tx('The draft is closed without posting anything. A cancelled note cannot be posted later.')
          : tx('Posting books the note to the ledger and applies it to the partner\'s balance; a return to the supplier also takes the goods out of stock. Undoing it later needs a reversal.')}</p>}
        confirmLabel={confirming?.action === 'cancel' ? tx('Cancel note') : tx('Post note')}
        pendingLabel={confirming?.action === 'cancel' ? tx('Cancelling…') : tx('Posting…')}
        destructive={confirming?.action === 'cancel'}
        pending={act.isPending}
        onConfirm={() => confirming && act.mutate({ id: confirming.note.id, action: confirming.action })}
      />

      <Dialog open={reversing !== null} onOpenChange={(open) => { if (!open) setReversing(null); }}>
        <DialogContent className="max-w-md">
          <DialogHeader>
            <DialogTitle>{tx('Reverse')} {reversing?.noteNumber}</DialogTitle>
            <DialogDescription>
              {tx('Reverses the journal entry and gives back what the note changed on its invoice or bill; goods returned to the supplier come back into stock. A note whose amount was already paid cannot be reversed until that payment is voided.')}
            </DialogDescription>
          </DialogHeader>
          <div className="space-y-2">
            <Label htmlFor="debit-note-reverse-reason">{tx('Reason')}</Label>
            <Textarea
              id="debit-note-reverse-reason"
              value={reverseReason}
              maxLength={2000}
              onChange={(e) => setReverseReason(e.target.value)}
              placeholder={tx('Why is this debit note being reversed?')}
            />
          </div>
          <DialogFooter>
            <Button variant="outline" onClick={() => setReversing(null)}>{tx('Keep')}</Button>
            <Button
              variant="destructive"
              disabled={reverse.isPending || !reverseReason.trim() || !reversing}
              onClick={() => reversing && reverse.mutate({ id: reversing.id, reason: reverseReason.trim() })}
            >
              {reverse.isPending ? tx('Reversing...') : tx('Reverse')}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  );
}

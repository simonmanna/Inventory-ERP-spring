import { useState } from 'react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { Plus, RotateCcw, Send, X } from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card';
import { Button } from '@/components/ui/button';
import { Badge } from '@/components/ui/badge';
import { Skeleton } from '@/components/ui/skeleton';
import { Label } from '@/components/ui/label';
import { Textarea } from '@/components/ui/textarea';
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { Link } from 'react-router-dom';
import { api } from '@/lib/api';
import { idempotentPatch } from '@/lib/idempotent-request';
import { notify } from '@/lib/notify';
import { useAuthStore } from '@/stores/auth.store';
import { formatMoney } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';

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

export function DebitNotesPage() {
  const qc = useQueryClient();
  const org = useAuthStore((s) => s.organization);
  const list = useQuery<DebitNote[]>({
    queryKey: ['debit-notes'],
    queryFn: async () => (await api.get<DebitNote[]>('/procurement/debit-notes')).data,
  });
  const act = useMutation({
    mutationFn: async (vars: { id: string; action: 'post' | 'cancel' }) =>
      await idempotentPatch(`/procurement/debit-notes/${vars.id}/${vars.action}`),
    onSuccess: () => {
      notify.success(tx('Updated'));
      qc.invalidateQueries({ queryKey: ['debit-notes'] });
    },
    onError: (e: any) => notify.error(e?.response?.data?.message ?? tx('Failed')),
  });
  // Document reversal of a posted note (owner decision 2026-11-17): GL mirrored, the referenced invoice / bill
  // restored, returned goods brought back; a reason is required.
  const has = useAuthStore((s) => s.hasPermission);
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
    onError: (e: any) => notify.error(e?.response?.data?.message ?? tx('Failed')),
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
        <Button asChild>
          <Link to="/procurement/debit-notes/new"><Plus className="mr-2 h-4 w-4" />{tx('New debit note')}</Link>
        </Button>
      </div>

      {list.isLoading && <Skeleton className="h-32 w-full" />}
      {list.data && list.data.length === 0 && (
        <Card>
          <CardContent className="p-8 text-center text-sm text-muted-foreground">
            {tx('No debit notes yet.')}
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
                {n.status === 'draft' && (
                  <>
                    <Button size="sm" onClick={() => act.mutate({ id: n.id, action: 'post' })}>
                      <Send className="mr-1 h-3 w-3" />{tx('Post')}
                    </Button>
                    <Button size="sm" variant="ghost" onClick={() => act.mutate({ id: n.id, action: 'cancel' })}>
                      <X className="mr-1 h-3 w-3 text-destructive" />{tx('Cancel')}
                    </Button>
                  </>
                )}
                {n.status === 'posted' && has(PERMISSIONS.debitNote.reverse) && (
                  <Button size="sm" variant="ghost" onClick={() => setReversing(n)}>
                    <RotateCcw className="mr-1 h-3 w-3 text-destructive" />{tx('Reverse')}
                  </Button>
                )}
              </div>
            </CardContent>
          </Card>
        ))}
      </div>

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

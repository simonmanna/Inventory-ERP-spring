import { useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { PERMISSIONS } from '@erp/shared';
import { Button } from '@/components/ui/button';
import { Badge } from '@/components/ui/badge';
import { Card, CardContent } from '@/components/ui/card';
import { Label } from '@/components/ui/label';
import { Textarea } from '@/components/ui/textarea';
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table';
import { money, date } from '@/lib/format';
import { apiMessage, notify } from '@/lib/notify';
import { useAuthStore } from '@/stores/auth.store';
import { useCreditNote, usePostCreditNote, useReverseCreditNote } from '@/features/invoicing/api';
import { tx } from '@/lib/i18n/tx';

function SummaryRow({ label, value, bold }: { label: string; value: string; bold?: boolean }) {
  return (
    <div className={`flex justify-between ${bold ? 'font-semibold' : ''}`}>
      <span className={bold ? '' : 'text-muted-foreground'}>{label}</span>
      <span>{value}</span>
    </div>
  );
}

export function CreditNoteDetailPage() {
  const { id } = useParams();
  const navigate = useNavigate();
  const { data: cn, isLoading } = useCreditNote(id);
  const postCreditNote = usePostCreditNote();
  const has = useAuthStore((s) => s.hasPermission);
  // Return disposition applied at post time: restock the goods (reverses COGS)
  // or scrap them (revenue reversed only).
  const [disposition, setDisposition] = useState<'restock' | 'scrap'>('restock');
  const reverseCreditNote = useReverseCreditNote();
  const [reverseOpen, setReverseOpen] = useState(false);
  const [reverseReason, setReverseReason] = useState('');

  if (isLoading || !cn) {
    return <div className="text-sm text-muted-foreground">{tx('Loading...')}</div>;
  }

  const isDraft = cn.status === 'draft';
  // Document reversal (owner decision 2026-11-17): a posted note is reversed here, never from its journal entry.
  const canReverse = (cn.status === 'posted' || cn.status === 'paid') && has(PERMISSIONS.creditNote.reverse);

  const confirmReverse = async () => {
    try {
      await reverseCreditNote.mutateAsync({ id: cn.id, reason: reverseReason.trim() });
      setReverseOpen(false);
      setReverseReason('');
      notify.success(tx('Credit note reversed'));
    } catch (error) {
      notify.error(apiMessage(error, tx('Could not reverse the credit note')));
    }
  };

  return (
    <div className="space-y-6">
      <div className="flex items-start justify-between">
        <div>
          <div className="flex items-center gap-3">
            <h1 className="text-2xl font-semibold">{cn.documentNumber}</h1>
            <Badge variant={cn.status === 'cancelled' ? 'destructive' : 'default'}>{cn.status}</Badge>
          </div>
          <p className="text-sm text-muted-foreground">
            {cn.partner?.name} · {date(cn.issueDate)}
            {cn.reversedDocumentId ? tx(' · applied to an invoice') : ''}
          </p>
          {cn.reversal && (
            <p className="text-sm text-muted-foreground">
              {tx('Reversed')} {date(cn.reversal.reversedAt)}: {cn.reversal.reason}
            </p>
          )}
        </div>
        <div className="flex flex-wrap gap-2">
          <Button variant="outline" onClick={() => navigate('/credit-notes')}>
            {tx('Back')}
          </Button>
          {canReverse && (
            <Button variant="outline" onClick={() => setReverseOpen(true)} disabled={reverseCreditNote.isPending}>
              {tx('Reverse')}
            </Button>
          )}
          {isDraft && has(PERMISSIONS.creditNote.post) && (
            <div className="flex items-center gap-2">
              <select
                value={disposition}
                onChange={(e) => setDisposition(e.target.value as 'restock' | 'scrap')}
                className="rounded border bg-background px-2 py-1 text-sm"
                title={tx('What happens to returned goods when this note is posted')}
              >
                <option value="restock">{tx('Restock goods')}</option>
                <option value="scrap">{tx('Scrap goods')}</option>
              </select>
              <Button onClick={() => postCreditNote.mutate({ id: cn.id, disposition })} disabled={postCreditNote.isPending}>
                {tx('Post')}
              </Button>
            </div>
          )}
        </div>
      </div>

      <Card>
        <CardContent className="pt-6">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>{tx('Description')}</TableHead>
                <TableHead className="text-right">{tx('Qty')}</TableHead>
                <TableHead className="text-right">{tx('Unit price')}</TableHead>
                <TableHead className="text-right">{tx('Net')}</TableHead>
                <TableHead className="text-right">{tx('Tax')}</TableHead>
                <TableHead className="text-right">{tx('Total')}</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {cn.lines.map((l) => (
                <TableRow key={l.id}>
                  <TableCell>{l.description}</TableCell>
                  <TableCell className="text-right">{money(l.quantity)}</TableCell>
                  <TableCell className="text-right">{money(l.unitPrice)}</TableCell>
                  <TableCell className="text-right">{money(l.subtotal)}</TableCell>
                  <TableCell className="text-right">{money(l.taxAmount)}</TableCell>
                  <TableCell className="text-right">{money(l.total)}</TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>

          <div className="mt-4 flex justify-end">
            <div className="w-64 space-y-1 text-sm">
              <SummaryRow label={tx('Subtotal')} value={money(cn.subtotal)} />
              <SummaryRow label={tx('Tax')} value={money(cn.taxAmount)} />
              <SummaryRow label={tx('Total credited')} value={money(cn.totalAmount)} bold />
            </div>
          </div>
        </CardContent>
      </Card>

      <Dialog open={reverseOpen} onOpenChange={setReverseOpen}>
        <DialogContent className="max-w-md">
          <DialogHeader>
            <DialogTitle>{tx('Reverse')} {cn.documentNumber}</DialogTitle>
            <DialogDescription>
              {tx('Reverses the journal entry, gives the credit back to the invoices it was applied to and takes restocked goods back out of stock. A note that was refunded or paid out cannot be reversed until that payment is voided.')}
            </DialogDescription>
          </DialogHeader>
          <div className="space-y-2">
            <Label htmlFor="credit-note-reverse-reason">{tx('Reason')}</Label>
            <Textarea
              id="credit-note-reverse-reason"
              value={reverseReason}
              maxLength={2000}
              onChange={(e) => setReverseReason(e.target.value)}
              placeholder={tx('Why is this credit note being reversed?')}
            />
          </div>
          <DialogFooter>
            <Button variant="outline" onClick={() => setReverseOpen(false)}>{tx('Keep')}</Button>
            <Button
              variant="destructive"
              onClick={() => void confirmReverse()}
              disabled={reverseCreditNote.isPending || !reverseReason.trim()}
            >
              {reverseCreditNote.isPending ? tx('Reversing...') : tx('Reverse')}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  );
}

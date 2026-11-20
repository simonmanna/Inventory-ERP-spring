import { useMemo, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { toast } from 'sonner';
import type { PaginatedResult } from '@erp/shared';
import { api } from '@/lib/api';
import { apiErrorMessage } from '@/lib/api-error';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { useAllocatePayment, type Invoice, type PaymentDetail } from '@/features/invoicing/api';
import { date, money } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';
import { useAuthStore } from '@/stores/auth.store';

const round2 = (n: number) => Math.round(n * 100) / 100;

/**
 * FX-2 F: allocate a posted payment's unallocated remainder (an advance) to open documents of the same partner and
 * currency: sales invoices for a receipt, vendor bills for a supplier payment. For a foreign payment each row shows an
 * estimated realised FX: the server posts the exact figure at the document's carrying rate.
 */
export function AllocateAdvanceDialog({ payment, onClose }: { payment: PaymentDetail; onClose: () => void }) {
  const base = (useAuthStore((s) => s.organization?.currencyCode) ?? '').toUpperCase();
  const inbound = payment.direction !== 'outbound';
  const payCurrency = (payment.currencyId ?? '').trim().toUpperCase() || base;
  const foreign = !!base && payCurrency !== base;
  const payRate = Number(payment.exchangeRate ?? 0);
  const unallocated = Number(payment.unallocatedAmount);
  const allocate = useAllocatePayment();

  const docs = useQuery({
    queryKey: ['payment', payment.id, 'open-documents', inbound ? 'invoices' : 'bills', payment.partnerId],
    queryFn: async () =>
      (await api.get<PaginatedResult<Invoice>>(inbound ? '/invoices' : '/vendor-bills', {
        params: { partnerId: payment.partnerId, status: 'posted', page: 1, pageSize: 100 },
      })).data.data,
  });

  const open = useMemo(
    () => (docs.data ?? []).filter((d) =>
      d.partnerId === payment.partnerId
      && d.status === 'posted'
      && Number(d.amountResidual) > 0
      && (!d.documentType || d.documentType === (inbound ? 'sales_invoice' : 'vendor_bill'))
      && ((d.currencyId ?? '').trim().toUpperCase() || base) === payCurrency)
      .sort((a, b) => String(a.issueDate).localeCompare(String(b.issueDate))),
    [docs.data, payment.partnerId, inbound, base, payCurrency],
  );

  const [amounts, setAmounts] = useState<Record<string, string>>({});
  const total = Object.values(amounts).reduce((s, v) => s + (Number(v) > 0 ? Number(v) : 0), 0);
  const remaining = round2(unallocated - total);

  /** Max for a row: the document residual, capped by what is left of the advance once the other rows are counted. */
  const maxFor = (d: Invoice) => {
    const own = Number(amounts[d.id]) > 0 ? Number(amounts[d.id]) : 0;
    return round2(Math.max(0, Math.min(Number(d.amountResidual), unallocated - (total - own))));
  };

  /** Estimated realised FX for a row (positive = gain), or null when a rate is unknown. */
  const fxFor = (d: Invoice, amount: number): number | null => {
    if (!foreign || !(amount > 0)) return null;
    const docRate = Number(d.exchangeRate ?? 0);
    if (!(payRate > 0) || !(docRate > 0)) return null;
    const diff = (payRate - docRate) * amount;
    return inbound ? diff : -diff;
  };

  const rows = open.map((d) => ({ d, amount: Number(amounts[d.id]) > 0 ? Number(amounts[d.id]) : 0 }));
  const invalid = rows.some((r) => r.amount > Number(r.d.amountResidual) + 1e-9) || total > unallocated + 1e-9;
  const fxTotal = rows.reduce((s, r) => s + (fxFor(r.d, r.amount) ?? 0), 0);

  const autoFill = () => {
    let left = unallocated;
    const next: Record<string, string> = {};
    for (const d of open) {
      if (left <= 0) break;
      const a = round2(Math.min(Number(d.amountResidual), left));
      if (a > 0) next[d.id] = String(a);
      left = round2(left - a);
    }
    setAmounts(next);
  };

  const submit = async () => {
    const allocations = rows.filter((r) => r.amount > 0).map((r) => ({ documentId: r.d.id, amount: r.amount }));
    if (!allocations.length) return;
    try {
      await allocate.mutateAsync({ id: payment.id, allocations });
      toast.success(tx('Advance allocated'));
      onClose();
    } catch (err) {
      toast.error(apiErrorMessage(err, tx('Allocation failed')));
    }
  };

  return (
    <Dialog open onOpenChange={(o) => { if (!o && !allocate.isPending) onClose(); }}>
      <DialogContent className="sm:max-w-3xl">
        <DialogHeader>
          <DialogTitle>{tx('Allocate advance')} · {payment.paymentNumber}</DialogTitle>
          <DialogDescription>
            {tx('Unallocated')}: {money(unallocated)} {payCurrency}
            {foreign && payRate > 0 && <> · {tx('booked at {{v1}}', { v1: payRate })}</>}
            {' · '}{inbound ? tx('open invoices of this customer in the same currency') : tx('open bills of this supplier in the same currency')}
          </DialogDescription>
        </DialogHeader>

        <div className="max-h-[50vh] overflow-auto rounded-md border">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>{tx('Document')}</TableHead>
                <TableHead>{tx('Date')}</TableHead>
                <TableHead className="text-right">{tx('Open')}</TableHead>
                {foreign && <TableHead className="text-right">{tx('Rate')}</TableHead>}
                <TableHead className="w-36 text-right">{tx('Allocate')}</TableHead>
                {foreign && <TableHead className="text-right">{tx('FX (estimated)')}</TableHead>}
              </TableRow>
            </TableHeader>
            <TableBody>
              {docs.isLoading ? (
                <TableRow><TableCell colSpan={6} className="text-sm text-muted-foreground">{tx('Loading...')}</TableCell></TableRow>
              ) : open.length === 0 ? (
                <TableRow>
                  <TableCell colSpan={6} className="h-16 text-center text-sm text-muted-foreground">
                    {tx('No open documents of this partner in {{v1}}.', { v1: payCurrency })}
                  </TableCell>
                </TableRow>
              ) : (
                rows.map(({ d, amount }) => {
                  const fx = fxFor(d, amount);
                  const over = amount > Number(d.amountResidual) + 1e-9;
                  return (
                    <TableRow key={d.id}>
                      <TableCell className="font-medium">{d.documentNumber}</TableCell>
                      <TableCell className="text-xs">{date(d.issueDate)}</TableCell>
                      <TableCell className="text-right tabular-nums">{money(d.amountResidual)}</TableCell>
                      {foreign && <TableCell className="text-right text-xs tabular-nums">{d.exchangeRate ? String(d.exchangeRate) : '—'}</TableCell>}
                      <TableCell className="text-right">
                        <Input
                          type="number"
                          min={0}
                          step="0.01"
                          max={maxFor(d)}
                          value={amounts[d.id] ?? ''}
                          placeholder={String(maxFor(d))}
                          onChange={(e) => setAmounts((a) => ({ ...a, [d.id]: e.target.value }))}
                          className={`h-8 text-right ${over ? 'border-destructive' : ''}`}
                        />
                      </TableCell>
                      {foreign && (
                        <TableCell className={`text-right text-xs tabular-nums ${fx == null ? 'text-muted-foreground' : fx > 0 ? 'text-emerald-700' : fx < 0 ? 'text-red-700' : ''}`}>
                          {fx == null ? '—' : `${money(fx)} ${base}`}
                        </TableCell>
                      )}
                    </TableRow>
                  );
                })
              )}
            </TableBody>
          </Table>
        </div>

        <div className="flex flex-wrap items-center justify-between gap-2 text-sm">
          <Button variant="ghost" size="sm" onClick={autoFill} disabled={!open.length}>{tx('Fill oldest first')}</Button>
          <div className="space-x-3">
            <span>{tx('Allocating')} <b>{money(total)}</b></span>
            <span className={remaining < 0 ? 'text-destructive' : 'text-muted-foreground'}>{tx('Left')} {money(remaining)}</span>
            {foreign && total > 0 && (
              <span className={fxTotal > 0 ? 'text-emerald-700' : fxTotal < 0 ? 'text-red-700' : ''}>
                {tx('Estimated FX')} {money(fxTotal)} {base}
              </span>
            )}
          </div>
        </div>
        {foreign && (
          <p className="text-xs text-muted-foreground">
            {tx('Estimated: (payment rate − document rate) × amount; positive is a gain. The posted figure uses each document\'s carrying rate.')}
          </p>
        )}

        <DialogFooter>
          <Button variant="outline" onClick={onClose} disabled={allocate.isPending}>{tx('Cancel')}</Button>
          <Button onClick={submit} disabled={allocate.isPending || invalid || !(total > 0)}>{tx('Allocate')}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

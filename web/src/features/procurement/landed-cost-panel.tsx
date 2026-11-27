import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import { ExternalLink, Plus, X } from 'lucide-react';
import { api } from '@/lib/api';
import { idempotentPost } from '@/lib/idempotent-request';
import { notify } from '@/lib/notify';
import { date, money } from '@/lib/format';
import { Button } from '@/components/ui/button';
import { Badge } from '@/components/ui/badge';
import { tx } from '@/lib/i18n/tx';

interface LandedCostRow {
  id: string;
  code: string;
  status: string;
  voucherType: 'receipt' | 'voucher' | 'credit' | null;
  date: string;
  totalAmount: string;
  capitalizedAmount: string;
  expensedAmount: string;
  charges: Array<{ id: string; kind: string }>;
}

/**
 * Landed costs of a posted goods receipt. New freight, duty and insurance are entered as landed cost vouchers
 * (the per-receipt landed cost is retired: it capitalised the cost of goods already sold into stock). The list shows
 * the vouchers covering this receipt and older per-receipt entries; an old draft can still be cancelled.
 */
export function LandedCostPanel({ goodsReceiptId, canCreate, canCancel }: { goodsReceiptId: string; canCreate: boolean; canCancel: boolean }) {
  const qc = useQueryClient();
  const list = useQuery<LandedCostRow[]>({
    queryKey: ['landed-costs', goodsReceiptId],
    queryFn: async () => (await api.get<LandedCostRow[]>('/procurement/landed-costs', { params: { goodsReceiptId } })).data,
  });
  const cancel = useMutation({
    mutationFn: async (id: string) => await idempotentPost<LandedCostRow>(`/procurement/landed-costs/${id}/cancel`),
    onSuccess: () => {
      notify.success(tx('Landed cost cancelled'));
      qc.invalidateQueries({ queryKey: ['landed-costs', goodsReceiptId] });
    },
    onError: (e: any) => notify.error(e?.response?.data?.message ?? tx('Could not cancel landed cost')),
  });

  const rows = list.data ?? [];
  const isVoucher = (lc: LandedCostRow) => lc.voucherType === 'voucher' || lc.voucherType === 'credit';
  const posted = (lc: LandedCostRow) => lc.status === 'posted' || lc.status === 'reversed';

  return (
    <section aria-labelledby="landed-cost-heading" className="mt-4 rounded-md border p-4">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div>
          <h2 id="landed-cost-heading" className="text-sm font-semibold">{tx('Landed costs')}</h2>
          <p className="text-xs text-muted-foreground">
            {tx('Freight, duty and insurance are added as landed cost vouchers: the share of goods still on hand raises their cost, the share already sold goes to COGS.')}
          </p>
        </div>
        {canCreate && (
          <Button size="sm" variant="outline" asChild>
            <Link to={`/procurement/landed-cost-vouchers/new?goodsReceiptId=${encodeURIComponent(goodsReceiptId)}`}>
              <Plus className="mr-1 h-3.5 w-3.5" />{tx('New landed cost voucher')}
            </Link>
          </Button>
        )}
      </div>

      {rows.length > 0 && (
        <div className="mt-3 overflow-x-auto">
          <table className="w-full min-w-[560px] text-sm">
            <thead>
              <tr className="border-b text-left text-xs text-muted-foreground">
                <th scope="col" className="py-1 pr-2">{tx('Code')}</th>
                <th scope="col" className="py-1 pr-2">{tx('Date')}</th>
                <th scope="col" className="py-1 pr-2">{tx('Charges')}</th>
                <th scope="col" className="py-1 pr-2 text-right">{tx('Total')}</th>
                <th scope="col" className="py-1 pr-2 text-right">{tx('Capitalised')}</th>
                <th scope="col" className="py-1 pr-2 text-right">{tx('To COGS')}</th>
                <th scope="col" className="py-1 pr-2">{tx('Status')}</th>
                <th scope="col" className="py-1" />
              </tr>
            </thead>
            <tbody>
              {rows.map((lc) => (
                <tr key={lc.id} className="border-b last:border-0">
                  <td className="py-1.5 pr-2 font-mono text-xs">
                    {isVoucher(lc) ? <Link className="underline" to={`/procurement/landed-cost-vouchers/${lc.id}`}>{lc.code}</Link> : lc.code}
                  </td>
                  <td className="py-1.5 pr-2">{date(lc.date)}</td>
                  <td className="py-1.5 pr-2 text-xs">
                    {(lc.charges ?? []).map((c) => c.kind).join(', ')}
                    {!isVoucher(lc) && <span className="text-muted-foreground"> {tx('· per-receipt (retired)')}</span>}
                  </td>
                  <td className="py-1.5 pr-2 text-right tabular-nums">{money(Number(lc.totalAmount))}</td>
                  <td className="py-1.5 pr-2 text-right tabular-nums">{posted(lc) ? money(Number(lc.capitalizedAmount)) : '—'}</td>
                  <td className="py-1.5 pr-2 text-right tabular-nums">{posted(lc) ? money(Number(lc.expensedAmount)) : '—'}</td>
                  <td className="py-1.5 pr-2"><Badge variant={lc.status === 'posted' ? 'default' : lc.status === 'cancelled' || lc.status === 'reversed' ? 'destructive' : 'outline'}>{lc.status}</Badge></td>
                  <td className="py-1.5 text-right whitespace-nowrap">
                    {isVoucher(lc) && (
                      <Button size="sm" variant="ghost" asChild aria-label={tx('Open {{code}}', { code: lc.code })}>
                        <Link to={`/procurement/landed-cost-vouchers/${lc.id}`}><ExternalLink className="h-3.5 w-3.5" /></Link>
                      </Button>
                    )}
                    {!isVoucher(lc) && lc.status === 'draft' && canCancel && (
                      <Button size="sm" variant="ghost" aria-label={tx('Cancel {{code}}', { code: lc.code })} disabled={cancel.isPending} onClick={() => cancel.mutate(lc.id)}>
                        <X className="h-3.5 w-3.5" />
                      </Button>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  );
}

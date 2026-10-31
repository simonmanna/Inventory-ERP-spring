import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { toast } from 'sonner';
import { FileText, Wallet } from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Dialog, DialogContent, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { api } from '@/lib/api';
import { useAuthStore } from '@/stores/auth.store';
import { apiErrorMessage } from '@/lib/api-error';
import { date, money } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';
import type { SalesOrder } from '@/features/sales/api';
import { Field } from '@/pages/crm/crm-ui';

interface Billing {
  lines: Array<{ lineId: string; description: string; policy: string; quantity: number; amount: number }>;
  amount: number;
  deposits: Array<{ id: string; number: string; amount: number; available: number; status: string; source: string; receivedDate: string; paymentMethod: string | null }>;
  invoices: Array<{ id: string; documentNumber: string; status: string; totalAmount: number; amountResidual: number; downPayment: boolean }>;
}

const idem = () => ({ headers: { 'Idempotency-Key': crypto.randomUUID() } });

/** Invoicing, deposits / down payments and mobile-money collection of a sales order (ADR-S011 S6). */
export function BillingPanel({ order }: { order: SalesOrder }) {
  const navigate = useNavigate();
  const qc = useQueryClient();
  const has = useAuthStore((s) => s.hasPermission);
  const { data } = useQuery({ queryKey: ['sales', 'billing', order.id], queryFn: async () => (await api.get<Billing>(`/sales/orders/${order.id}/billing`)).data });
  const refresh = () => qc.invalidateQueries({ queryKey: ['sales'] });
  const err = (e: unknown) => toast.error(apiErrorMessage(e, tx('Action failed')));
  const invoice = useMutation({
    mutationFn: async (body: Record<string, unknown>) => (await api.post<{ id: string; documentNumber: string }>(`/sales/orders/${order.id}/invoices`, body, idem())).data,
    onSuccess: (d) => { refresh(); toast.success(tx('Draft invoice') + ' ' + d.documentNumber); navigate(`/invoices/${d.id}`); },
    onError: err,
  });
  const deposit = useMutation({
    mutationFn: async (body: Record<string, unknown>) => (await api.post(`/sales/orders/${order.id}/deposits`, body, idem())).data,
    onSuccess: () => { refresh(); setDialog(null); toast.success(tx('Deposit recorded')); }, onError: err,
  });
  const momo = useMutation({
    mutationFn: async (body: Record<string, unknown>) => {
      const r = (await api.post<{ id: string }>('/payments/mobile-money', body, idem())).data;
      return (await api.post<{ status: string }>(`/payments/mobile-money/${r.id}/refresh`, {})).data;
    },
    onSuccess: (r) => { refresh(); setDialog(null); toast[r.status === 'succeeded' ? 'success' : 'message'](tx('Mobile money') + ': ' + r.status); },
    onError: err,
  });
  const apply = useMutation({
    mutationFn: async ({ depositId, documentId }: { depositId: string; documentId: string }) => (await api.post(`/customer-deposits/${depositId}/apply`, { documentId }, idem())).data,
    onSuccess: () => { refresh(); toast.success(tx('Deposit applied')); }, onError: err,
  });
  const [dialog, setDialog] = useState<'deposit' | 'down' | null>(null);
  const [amount, setAmount] = useState(0);
  const [percent, setPercent] = useState(30);
  const [method, setMethod] = useState('bank');
  const [phone, setPhone] = useState('');
  const [target, setTarget] = useState<Record<string, string>>({});
  if (!data) return null;
  const canInvoice = has(PERMISSIONS.sales.invoiceCreate);
  const canDeposit = has(PERMISSIONS.sales.depositRecord);
  const postedOpen = data.invoices.filter((i) => i.status === 'posted' && Number(i.amountResidual) > 0 && !i.downPayment);
  return (
    <div className="rounded-xl border bg-card p-4 text-sm shadow-sm">
      <h2 className="mb-2 font-semibold">{tx('Billing')}</h2>
      {data.lines.length > 0 ? (
        <p className="text-xs text-muted-foreground">{tx('To invoice')}: {data.lines.map((l) => `${l.description} ${l.quantity}`).join(' · ')} — <strong>{money(data.amount, order.currencyCode)}</strong></p>
      ) : <p className="text-xs text-muted-foreground">{tx('Nothing to invoice right now.')}</p>}
      <div className="mt-2 flex flex-wrap gap-2">
        {canInvoice && data.lines.length > 0 && <Button size="sm" disabled={invoice.isPending} onClick={() => invoice.mutate({ mode: 'regular' })}><FileText className="mr-1 h-3.5 w-3.5" />{tx('Create invoice')}</Button>}
        {canInvoice && <Button size="sm" variant="outline" onClick={() => setDialog('down')}>{tx('Down payment invoice')}</Button>}
        {canDeposit && <Button size="sm" variant="outline" onClick={() => { setAmount(0); setDialog('deposit'); }}><Wallet className="mr-1 h-3.5 w-3.5" />{tx('Record deposit')}</Button>}
      </div>
      {data.invoices.length > 0 && <ul className="mt-3 space-y-1 text-xs">
        {data.invoices.map((i) => (
          <li key={i.id}><button className="text-primary hover:underline" onClick={() => navigate(`/invoices/${i.id}`)}>{i.documentNumber}</button>{i.downPayment && ` · ${tx('down payment')}`} · {i.status} · {money(i.totalAmount)} · {tx('open')} {money(i.amountResidual)}</li>
        ))}
      </ul>}
      {data.deposits.length > 0 && <div className="mt-3">
        <p className="text-xs font-medium">{tx('Deposits')}</p>
        <ul className="space-y-1 text-xs">
          {data.deposits.map((d) => (
            <li key={d.id} className="flex flex-wrap items-center gap-2">
              <span>{d.number} · {date(d.receivedDate)} · {money(d.amount)} · {tx('available')} {money(d.available)} · {d.status}</span>
              {d.status === 'open' && Number(d.available) > 0 && postedOpen.length > 0 && canDeposit && <>
                <select className="h-7 rounded border bg-background px-1" value={target[d.id] ?? ''} onChange={(e) => setTarget({ ...target, [d.id]: e.target.value })}>
                  <option value="">{tx('Apply to…')}</option>{postedOpen.map((i) => <option key={i.id} value={i.id}>{i.documentNumber}</option>)}
                </select>
                <Button size="sm" variant="ghost" disabled={!target[d.id]} onClick={() => apply.mutate({ depositId: d.id, documentId: target[d.id] })}>{tx('Apply')}</Button>
              </>}
            </li>
          ))}
        </ul>
      </div>}
      <Dialog open={dialog !== null} onOpenChange={(v) => !v && setDialog(null)}>
        <DialogContent className="max-w-md">
          <DialogHeader><DialogTitle>{dialog === 'down' ? tx('Down payment invoice') : tx('Record deposit')}</DialogTitle></DialogHeader>
          {dialog === 'down' ? (
            <Field label={tx('Percent of the order total')}><Input type="number" value={percent} onChange={(e) => setPercent(Number(e.target.value))} /></Field>
          ) : <div className="grid gap-3">
            <Field label={tx('Amount')}><Input type="number" value={amount} onChange={(e) => setAmount(Number(e.target.value))} /></Field>
            <Field label={tx('Method')}>
              <select className="h-9 w-full rounded-md border bg-background px-2 text-sm" value={method} onChange={(e) => setMethod(e.target.value)}>
                <option value="bank">{tx('Bank transfer')}</option><option value="cash">{tx('Cash')}</option><option value="cheque">{tx('Cheque')}</option>
                <option value="mobile_money">{tx('Mobile money (recorded)')}</option><option value="momo_request">{tx('Mobile money — request on phone')}</option>
              </select></Field>
            {method === 'momo_request' && <Field label={tx('Customer phone')}><Input value={phone} onChange={(e) => setPhone(e.target.value)} placeholder="+2567…" /></Field>}
          </div>}
          <DialogFooter>
            <Button variant="outline" onClick={() => setDialog(null)}>{tx('Cancel')}</Button>
            <Button onClick={() => {
              if (dialog === 'down') { invoice.mutate({ mode: 'down_payment', percent }); setDialog(null); }
              else if (method === 'momo_request') momo.mutate({ provider: 'mock', partnerId: order.partnerId, phone, amount, purpose: 'deposit', salesOrderId: order.id });
              else deposit.mutate({ amount, paymentMethod: method });
            }}>{tx('Confirm')}</Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  );
}

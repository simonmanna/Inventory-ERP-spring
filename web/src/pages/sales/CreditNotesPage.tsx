import { useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { toast } from 'sonner';
import { FileText, Plus, Receipt, Undo2 } from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Dialog, DialogContent, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { ListPageHeader } from '@/components/list';
import { api } from '@/lib/api';
import { idempotentPost } from '@/lib/idempotent-request';
import { apiErrorMessage } from '@/lib/api-error';
import { date, money } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';
import { Field } from '@/pages/crm/crm-ui';
import { PartnerPicker } from '@/pages/crm/crm-dialogs';
import { useAuthStore } from '@/stores/auth.store';

interface Row { id: string; documentNumber: string; status: string; issueDate: string; totalAmount: number; amountResidual: number; partnerId: string; partnerName: string | null; creditType: string | null }
const key = () => ({ headers: { 'Idempotency-Key': crypto.randomUUID() } });
const th = 'px-3 py-2';

/** Credit notes: raise one without a return, allocate open credit to invoices, refund it, print it. */
export function CreditNotesPage() {
  const qc = useQueryClient();
  const qk = ['sales', 'credit-notes'];
  const { data } = useQuery({ queryKey: qk, queryFn: async () => (await api.get<Row[]>('/sales/credit-notes')).data });
  const canWrite = useAuthStore((s) => s.hasPermission(PERMISSIONS.sales.creditNoteWrite));
  const [create, setCreate] = useState(false);
  const [alloc, setAlloc] = useState<Row | null>(null);
  const [refunding, setRefunding] = useState<Row | null>(null);
  const refresh = () => qc.invalidateQueries({ queryKey: qk });
  const fail = (e: unknown) => toast.error(apiErrorMessage(e, tx('Action failed')));
  const pdf = async (r: Row) => {
    try {
      const res = await api.get(`/sales/credit-notes/${r.id}/pdf`, { responseType: 'blob' });
      const url = URL.createObjectURL(res.data as Blob);
      const a = document.createElement('a'); a.href = url; a.download = `${r.documentNumber}.pdf`; a.click(); URL.revokeObjectURL(url);
    } catch (e) { fail(e); }
  };
  const refund = (r: Row) => setRefunding(r);
  return (
    <div className="space-y-4">
      <ListPageHeader icon={Receipt} title={tx('Credit Notes')} description={tx('Credit for returns, price changes and goodwill. Open credit can be allocated to invoices or refunded.')} actions={canWrite && <Button onClick={() => setCreate(true)}><Plus className="mr-1 h-4 w-4" />{tx('New credit note')}</Button>} />
      <div className="overflow-x-auto rounded-xl border bg-card shadow-sm">
        <table className="w-full min-w-[760px] text-sm">
          <thead className="bg-muted/40 text-left text-xs font-semibold uppercase text-muted-foreground"><tr><th className={th}>{tx('Number')}</th><th className={th}>{tx('Customer')}</th><th className={th}>{tx('Type')}</th><th className={th}>{tx('Date')}</th><th className={`${th} text-right`}>{tx('Total')}</th><th className={`${th} text-right`}>{tx('Open credit')}</th><th /></tr></thead>
          <tbody>
            {(data ?? []).map((r) => (
              <tr key={r.id} className="border-t"><td className={`${th} font-medium`}>{r.documentNumber}</td><td className={th}>{r.partnerName}</td><td className={th}>{tx(r.creditType ?? 'return')}</td><td className={th}>{date(r.issueDate)}</td><td className={`${th} text-right tabular-nums`}>{money(r.totalAmount)}</td><td className={`${th} text-right tabular-nums`}>{money(r.amountResidual)}</td>
                <td className={`${th} whitespace-nowrap text-right`}>
                  <Button size="sm" variant="ghost" onClick={() => pdf(r)}><FileText className="h-4 w-4" /></Button>
                  {canWrite && Number(r.amountResidual) > 0 && r.status === 'posted' && <><Button size="sm" variant="outline" onClick={() => setAlloc(r)}>{tx('Allocate')}</Button> <Button size="sm" variant="outline" onClick={() => refund(r)}><Undo2 className="mr-1 h-3 w-3" />{tx('Refund')}</Button></>}
                </td></tr>
            ))}
            {data && !data.length && <tr><td colSpan={7} className="p-6 text-center text-muted-foreground">{tx('No credit notes yet.')}</td></tr>}
          </tbody>
        </table>
      </div>
      {create && <NewCreditNote onClose={() => setCreate(false)} onDone={refresh} />}
      {alloc && <AllocateDialog note={alloc} onClose={() => setAlloc(null)} onDone={refresh} />}
      {refunding && <RefundDialog note={refunding} onClose={() => setRefunding(null)} onDone={refresh} />}
    </div>
  );
}

function NewCreditNote({ onClose, onDone }: { onClose: () => void; onDone: () => void }) {
  const [partnerId, setPartnerId] = useState('');
  const [partnerName, setPartnerName] = useState<string | null>(null);
  const [type, setType] = useState('price_adjustment');
  const [reason, setReason] = useState('');
  const [amount, setAmount] = useState('');
  const submit = () => api.post('/sales/credit-notes', { partnerId, type, reason, lines: [{ description: reason, quantity: 1, unitPrice: Number(amount) }] }, key()).then(() => { onDone(); onClose(); }).catch((e) => toast.error(apiErrorMessage(e, tx('Could not create the credit note'))));
  return (
    <Dialog open onOpenChange={(v) => !v && onClose()}>
      <DialogContent className="max-w-md">
        <DialogHeader><DialogTitle>{tx('New credit note')}</DialogTitle></DialogHeader>
        <Field label={tx('Customer')}><PartnerPicker value={partnerId} label={partnerName} onPick={(i, n) => { setPartnerId(i); setPartnerName(n || null); }} /></Field>
        <Field label={tx('Type')}><select className="h-9 w-full rounded-md border bg-background px-2 text-sm" value={type} onChange={(e) => setType(e.target.value)}><option value="price_adjustment">{tx('Price adjustment')}</option><option value="billing_error">{tx('Billing error')}</option><option value="goodwill">{tx('Goodwill')}</option><option value="return">{tx('Return')}</option></select></Field>
        <Field label={tx('Reason')}><Input value={reason} onChange={(e) => setReason(e.target.value)} /></Field>
        <Field label={tx('Amount')}><Input type="number" min={0} value={amount} onChange={(e) => setAmount(e.target.value)} /></Field>
        <DialogFooter><Button variant="outline" onClick={onClose}>{tx('Cancel')}</Button><Button disabled={!partnerId || !reason.trim() || !Number(amount)} onClick={submit}>{tx('Post')}</Button></DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

function AllocateDialog({ note, onClose, onDone }: { note: Row; onClose: () => void; onDone: () => void }) {
  const { data } = useQuery({ queryKey: ['sales', 'open-invoices', note.partnerId], queryFn: async () => (await api.get<{ data: Array<{ id: string; documentNumber: string; amountResidual: number; status: string }> }>('/invoices', { params: { partnerId: note.partnerId, pageSize: 100 } })).data.data });
  const open = (data ?? []).filter((i) => i.status === 'posted' && Number(i.amountResidual) > 0);
  const [documentId, setDocumentId] = useState('');
  const [amount, setAmount] = useState(String(note.amountResidual));
  const submit = () => api.post(`/sales/credit-notes/${note.id}/allocate`, { documentId, amount: Number(amount) }, key()).then(() => { onDone(); onClose(); }).catch((e) => toast.error(apiErrorMessage(e, tx('Could not allocate'))));
  return (
    <Dialog open onOpenChange={(v) => !v && onClose()}>
      <DialogContent className="max-w-md">
        <DialogHeader><DialogTitle>{tx('Allocate')} {note.documentNumber}</DialogTitle></DialogHeader>
        <Field label={tx('Invoice')}><select className="h-9 w-full rounded-md border bg-background px-2 text-sm" value={documentId} onChange={(e) => setDocumentId(e.target.value)}><option value="">{tx('Choose…')}</option>{open.map((i) => <option key={i.id} value={i.id}>{i.documentNumber} · {money(i.amountResidual)}</option>)}</select></Field>
        <Field label={tx('Amount')}><Input type="number" min={0} value={amount} onChange={(e) => setAmount(e.target.value)} /></Field>
        <DialogFooter><Button variant="outline" onClick={onClose}>{tx('Cancel')}</Button><Button disabled={!documentId || !Number(amount)} onClick={submit}>{tx('Allocate')}</Button></DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

/**
 * Refund the open credit by bank. FX-3: a foreign credit is refunded in its own currency; the rate is optional (blank =
 * the rate table) and only asked for when the credit is not in the functional currency.
 */
function RefundDialog({ note, onClose, onDone }: { note: Row; onClose: () => void; onDone: () => void }) {
  const base = useAuthStore((s) => s.organization?.currencyCode ?? '');
  const { data: detail } = useQuery({
    queryKey: ['credit-note', note.id, 'currency'],
    queryFn: async () => (await api.get<{ currencyId?: string | null }>(`/credit-notes/${note.id}`)).data,
    retry: false,
  });
  const currency = (detail?.currencyId ?? '').trim().toUpperCase();
  const foreign = !!currency && !!base && currency !== base;
  const [rate, setRate] = useState('');
  const [busy, setBusy] = useState(false);
  const submit = () => {
    setBusy(true);
    idempotentPost(`/sales/credit-notes/${note.id}/refund`, { paymentMethod: 'bank', ...(foreign && Number(rate) > 0 ? { exchangeRate: Number(rate) } : {}) })
      .then(() => { toast.success(tx('Refunded')); onDone(); onClose(); })
      .catch((e) => toast.error(apiErrorMessage(e, tx('Action failed'))))
      .finally(() => setBusy(false));
  };
  return (
    <Dialog open onOpenChange={(v) => !v && !busy && onClose()}>
      <DialogContent className="max-w-md">
        <DialogHeader><DialogTitle>{tx('Refund')} {note.documentNumber}</DialogTitle></DialogHeader>
        <p className="text-sm text-muted-foreground">
          {tx('Refund the open credit by bank?')} {money(note.amountResidual)}
          {foreign && <> · {tx('refunded in {{v1}}', { v1: currency })}</>}
        </p>
        {foreign && (
          <Field label={tx('Exchange rate (optional)')}>
            <Input type="number" min={0} step="any" value={rate} onChange={(e) => setRate(e.target.value)} placeholder={tx('rate table')} />
          </Field>
        )}
        {foreign && Number(rate) > 0 && (
          <p className="text-xs text-muted-foreground">1 {currency} = {rate} {base}</p>
        )}
        <DialogFooter><Button variant="outline" onClick={onClose} disabled={busy}>{tx('Cancel')}</Button><Button disabled={busy} onClick={submit}>{tx('Refund')}</Button></DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

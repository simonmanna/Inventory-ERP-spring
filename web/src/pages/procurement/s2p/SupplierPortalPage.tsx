import { useState } from 'react';
import { useParams } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { CheckCircle2 } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Textarea } from '@/components/ui/textarea';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { api } from '@/lib/api';
import { errMsg } from '@/features/procurement/api';
import { date, money } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';

type Line = { id?: string; lineNumber: number; description: string; quantity: string; uom?: string | null; requiredDate?: string | null; unitPrice?: string; subtotal?: string };
type PortalView = {
  documentType: 'rfq' | 'purchase_order';
  buyer: string;
  supplier: string;
  rfq?: { rfqNumber: string; title: string; status: string; deadline: string | null; deliveryTerms: string | null; notes: string | null };
  order?: { orderNumber: string; orderDate: string; expectedDeliveryDate: string | null; currencyCode: string; totalAmount: string; terms: string | null; acknowledgedAt: string | null; supplierReference: string | null; promisedDate: string | null };
  lines: Line[];
  lastQuotation?: { revision: number } | null;
  canQuote?: boolean;
  canConfirm?: boolean;
};

/**
 * Public supplier portal (ADR-PROCUREMENT-001 wave 7). Opened from a secure link — no login. The supplier answers one
 * RFQ (prices per line, lead time, freight) or confirms one purchase order.
 */
export function SupplierPortalPage() {
  const { token = '' } = useParams();
  const view = useQuery({
    queryKey: ['supplier-portal', token],
    retry: false,
    queryFn: async () => (await api.get<PortalView>(`/supplier-portal/${token}`)).data,
  });
  const [done, setDone] = useState<string | null>(null);

  return (
    <div className="min-h-screen bg-muted/30 px-4 py-8">
      <div className="mx-auto max-w-4xl space-y-4">
        {view.isLoading && <p className="text-center text-muted-foreground">{tx('Loading…')}</p>}
        {view.isError && (
          <Card><CardContent className="py-10 text-center">{errMsg(view.error)}</CardContent></Card>
        )}
        {done && (
          <Card><CardContent className="flex items-center gap-3 py-8 text-lg"><CheckCircle2 className="h-6 w-6 text-emerald-600" />{done}</CardContent></Card>
        )}
        {view.data && !done && (
          view.data.documentType === 'rfq'
            ? <RfqAnswer token={token} data={view.data} onDone={setDone} />
            : <OrderConfirm token={token} data={view.data} onDone={setDone} />
        )}
      </div>
    </div>
  );
}

function Header({ data, title, subtitle }: { data: PortalView; title: string; subtitle: string }) {
  return (
    <div>
      <div className="text-sm text-muted-foreground">{data.buyer} → {data.supplier}</div>
      <h1 className="text-2xl font-semibold">{title}</h1>
      <p className="text-sm text-muted-foreground">{subtitle}</p>
    </div>
  );
}

function RfqAnswer({ token, data, onDone }: { token: string; data: PortalView; onDone: (m: string) => void }) {
  const rfq = data.rfq!;
  const [prices, setPrices] = useState<Record<string, string>>({});
  const [form, setForm] = useState({ supplierReference: '', currencyCode: '', leadTimeDays: '', paymentTermsDays: '', freightAmount: '', notes: '' });
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const num = (v: string) => (v === '' ? undefined : Number(v));

  const submit = async () => {
    setBusy(true);
    setError(null);
    try {
      const res = await api.post<{ message: string }>(`/supplier-portal/${token}/quotation`, {
        supplierReference: form.supplierReference || undefined, currencyCode: form.currencyCode || undefined,
        leadTimeDays: num(form.leadTimeDays), paymentTermsDays: num(form.paymentTermsDays), freightAmount: num(form.freightAmount),
        notes: form.notes || undefined,
        lines: data.lines.filter((l) => prices[l.id!] !== undefined && prices[l.id!] !== '').map((l) => ({ rfqLineId: l.id!, unitPrice: Number(prices[l.id!]) })),
      });
      onDone(res.data.message);
    } catch (e) {
      setError(errMsg(e));
    } finally {
      setBusy(false);
    }
  };
  const decline = async () => {
    const reason = window.prompt(tx('Why are you not quoting? (optional)')) ?? '';
    try {
      const res = await api.post<{ message: string }>(`/supplier-portal/${token}/decline`, { reason: reason || undefined });
      onDone(res.data.message);
    } catch (e) {
      setError(errMsg(e));
    }
  };

  return (
    <>
      <Header data={data} title={`${rfq.rfqNumber} · ${rfq.title}`}
        subtitle={rfq.deadline ? tx('Please quote by {{d}}', { d: date(rfq.deadline) }) : tx('Request for quotation')} />
      {data.lastQuotation && <p className="text-sm text-muted-foreground">{tx('You already quoted (revision {{r}}). Sending again replaces it.', { r: data.lastQuotation.revision })}</p>}
      <Card>
        <CardContent className="p-0">
          <Table>
            <TableHeader><TableRow>
              <TableHead>#</TableHead><TableHead>{tx('Item')}</TableHead><TableHead className="text-right">{tx('Quantity')}</TableHead>
              <TableHead>{tx('Needed by')}</TableHead><TableHead className="w-40">{tx('Your unit price')}</TableHead>
            </TableRow></TableHeader>
            <TableBody>
              {data.lines.map((l) => (
                <TableRow key={l.id}>
                  <TableCell>{l.lineNumber}</TableCell>
                  <TableCell>{l.description}</TableCell>
                  <TableCell className="text-right">{Number(l.quantity)} {l.uom ?? ''}</TableCell>
                  <TableCell>{l.requiredDate ? date(l.requiredDate) : '—'}</TableCell>
                  <TableCell>
                    <Input type="number" min={0} disabled={!data.canQuote} value={prices[l.id!] ?? ''}
                      onChange={(e) => setPrices((p) => ({ ...p, [l.id!]: e.target.value }))} />
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </CardContent>
      </Card>
      {data.canQuote ? (
        <Card>
          <CardHeader><CardTitle>{tx('Your terms')}</CardTitle></CardHeader>
          <CardContent className="grid gap-3 sm:grid-cols-3">
            <div><Label>{tx('Your quotation number')}</Label><Input value={form.supplierReference} onChange={(e) => setForm({ ...form, supplierReference: e.target.value })} /></div>
            <div><Label>{tx('Currency')}</Label><Input maxLength={3} placeholder="UGX" value={form.currencyCode} onChange={(e) => setForm({ ...form, currencyCode: e.target.value.toUpperCase() })} /></div>
            <div><Label>{tx('Delivery in (days)')}</Label><Input type="number" min={0} value={form.leadTimeDays} onChange={(e) => setForm({ ...form, leadTimeDays: e.target.value })} /></div>
            <div><Label>{tx('Payment terms (days)')}</Label><Input type="number" min={0} value={form.paymentTermsDays} onChange={(e) => setForm({ ...form, paymentTermsDays: e.target.value })} /></div>
            <div><Label>{tx('Freight / delivery charge')}</Label><Input type="number" min={0} value={form.freightAmount} onChange={(e) => setForm({ ...form, freightAmount: e.target.value })} /></div>
            <div className="sm:col-span-3"><Label>{tx('Notes')}</Label><Textarea value={form.notes} onChange={(e) => setForm({ ...form, notes: e.target.value })} /></div>
            {error && <p className="text-sm text-red-600 sm:col-span-3">{error}</p>}
            <div className="flex justify-between sm:col-span-3">
              <Button variant="ghost" onClick={decline}>{tx('Decline to quote')}</Button>
              <Button onClick={submit} disabled={busy || !Object.values(prices).some((v) => v !== '')}>{tx('Send quotation')}</Button>
            </div>
          </CardContent>
        </Card>
      ) : <p className="text-sm text-muted-foreground">{tx('This request is no longer open for quotations.')}</p>}
    </>
  );
}

function OrderConfirm({ token, data, onDone }: { token: string; data: PortalView; onDone: (m: string) => void }) {
  const po = data.order!;
  const [ref, setRef] = useState('');
  const [promised, setPromised] = useState('');
  const [error, setError] = useState<string | null>(null);
  const confirm = async () => {
    try {
      const res = await api.post<{ message: string }>(`/supplier-portal/${token}/confirm`, { supplierReference: ref || undefined, promisedDate: promised || undefined });
      onDone(res.data.message);
    } catch (e) {
      setError(errMsg(e));
    }
  };
  return (
    <>
      <Header data={data} title={tx('Purchase order {{n}}', { n: po.orderNumber })}
        subtitle={`${date(po.orderDate)}${po.expectedDeliveryDate ? ` · ${tx('Delivery expected {{d}}', { d: date(po.expectedDeliveryDate) })}` : ''}`} />
      <Card>
        <CardContent className="p-0">
          <Table>
            <TableHeader><TableRow>
              <TableHead>#</TableHead><TableHead>{tx('Item')}</TableHead><TableHead className="text-right">{tx('Quantity')}</TableHead>
              <TableHead className="text-right">{tx('Unit price')}</TableHead><TableHead className="text-right">{tx('Amount')}</TableHead>
            </TableRow></TableHeader>
            <TableBody>
              {data.lines.map((l) => (
                <TableRow key={l.lineNumber}>
                  <TableCell>{l.lineNumber}</TableCell><TableCell>{l.description}</TableCell>
                  <TableCell className="text-right">{Number(l.quantity)}</TableCell>
                  <TableCell className="text-right">{money(l.unitPrice, po.currencyCode)}</TableCell>
                  <TableCell className="text-right">{money(l.subtotal, po.currencyCode)}</TableCell>
                </TableRow>
              ))}
              <TableRow><TableCell colSpan={4} className="text-right font-medium">{tx('Total')}</TableCell><TableCell className="text-right font-semibold">{money(po.totalAmount, po.currencyCode)}</TableCell></TableRow>
            </TableBody>
          </Table>
        </CardContent>
      </Card>
      {po.terms && <p className="whitespace-pre-line text-sm text-muted-foreground">{po.terms}</p>}
      {data.canConfirm ? (
        <Card>
          <CardHeader><CardTitle>{tx('Confirm the order')}</CardTitle></CardHeader>
          <CardContent className="grid gap-3 sm:grid-cols-2">
            <div><Label>{tx('Your order reference')}</Label><Input value={ref} onChange={(e) => setRef(e.target.value)} /></div>
            <div><Label>{tx('You will deliver by')}</Label><Input type="date" value={promised} onChange={(e) => setPromised(e.target.value)} /></div>
            {error && <p className="text-sm text-red-600 sm:col-span-2">{error}</p>}
            <div className="flex justify-end sm:col-span-2"><Button onClick={confirm}>{tx('Confirm order')}</Button></div>
          </CardContent>
        </Card>
      ) : (
        <p className="text-sm text-muted-foreground">
          {po.acknowledgedAt ? tx('Confirmed — reference {{r}}, delivery by {{d}}.', { r: po.supplierReference ?? '—', d: po.promisedDate ? date(po.promisedDate) : '—' }) : tx('This order can no longer be confirmed here.')}
        </p>
      )}
    </>
  );
}

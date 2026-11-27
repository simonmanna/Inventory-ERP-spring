import { useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { toast } from 'sonner';
import { Download, FileText, Mail } from 'lucide-react';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Skeleton } from '@/components/ui/skeleton';
import { ListPageHeader } from '@/components/list';
import { api } from '@/lib/api';
import { apiErrorMessage } from '@/lib/api-error';
import { date, money } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';

interface ProformaRow { id: string; documentNumber: string; status: string; issueDate: string; totalAmount: number; partnerName: string | null; sourceDocument: string | null; advanceAmount: string | null }
interface ProformaLine { id: string; description: string; quantity: number | string; unitPrice: number | string; discountPercent: number | string | null; taxAmount: number | string | null; total: number | string; unit: string | null; tax: string | null }
interface Proforma extends ProformaRow {
  notes: string | null; reference: string | null; dueDate: string | null; deliveryAddress: string | null; salespersonName: string | null;
  subtotal: number | string | null; taxAmount: number | string | null; discountTotal: number | string | null;
  advancePercent: number | string | null; bankDetails: string | null; mobileDetails: string | null; sourceOrderId: string | null;
  currencyCode: string | null; partnerId: string | null;
  partner: { id: string; name: string; email: string | null; phone: string | null; taxNumber: string | null; district: string | null } | null;
  lines?: ProformaLine[];
}

/** Proforma invoices: list. They carry no accounting. */
export function ProformasPage() {
  const [q, setQ] = useState('');
  const navigate = useNavigate();
  const { data, isLoading } = useQuery({ queryKey: ['sales', 'proformas', q], queryFn: async () => (await api.get<{ data: ProformaRow[] }>('/sales/proformas', { params: { q: q || undefined } })).data });
  return (
    <div className="space-y-4">
      <ListPageHeader icon={FileText} title={tx('Proforma Invoices')} description={tx('Payment requests from quotations and sales orders. A proforma is not a tax invoice and posts nothing.')} />
      <Input className="max-w-xs" placeholder={tx('Search number or customer')} value={q} onChange={(e) => setQ(e.target.value)} />
      <div className="overflow-x-auto rounded-xl border bg-card shadow-sm">
        <table className="w-full min-w-[640px] text-sm">
          <thead className="bg-muted/40 text-left text-xs font-semibold uppercase text-muted-foreground"><tr><th className="px-3 py-2">{tx('Number')}</th><th className="px-3 py-2">{tx('Customer')}</th><th className="px-3 py-2">{tx('Source')}</th><th className="px-3 py-2">{tx('Date')}</th><th className="px-3 py-2 text-right">{tx('Advance')}</th><th className="px-3 py-2 text-right">{tx('Total')}</th></tr></thead>
          <tbody>
            {isLoading && <tr><td colSpan={6} className="p-3"><Skeleton className="h-6 w-full" /></td></tr>}
            {data?.data.map((r) => (
              <tr key={r.id} className="cursor-pointer border-t hover:bg-muted/30" onClick={() => navigate(`/sales/proformas/${r.id}`)}>
                <td className="px-3 py-2 font-medium">{r.documentNumber}</td><td className="px-3 py-2">{r.partnerName}</td><td className="px-3 py-2">{r.sourceDocument ?? '—'}</td><td className="px-3 py-2">{date(r.issueDate)}</td>
                <td className="px-3 py-2 text-right tabular-nums">{r.advanceAmount ? money(Number(r.advanceAmount)) : '—'}</td><td className="px-3 py-2 text-right tabular-nums">{money(r.totalAmount)}</td>
              </tr>
            ))}
            {data && !data.data.length && <tr><td colSpan={6} className="p-6 text-center text-muted-foreground">{tx('No proforma invoices yet. Create one from a quotation or a sales order.')}</td></tr>}
          </tbody>
        </table>
      </div>
    </div>
  );
}

/** One proforma: customer, lines, totals, advance, payment details, PDF and email. */
export function ProformaPage() {
  const { id } = useParams();
  const { data: p, isLoading } = useQuery({ queryKey: ['sales', 'proforma', id], queryFn: async () => (await api.get<Proforma>(`/sales/proformas/${id}`)).data });
  const pdf = async () => {
    try {
      const r = await api.get(`/sales/proformas/${id}/pdf`, { responseType: 'blob' });
      const url = URL.createObjectURL(r.data as Blob);
      const a = document.createElement('a'); a.href = url; a.download = `${p?.documentNumber ?? 'proforma'}.pdf`; a.click();
      setTimeout(() => URL.revokeObjectURL(url), 1000);
    } catch (e) { toast.error(apiErrorMessage(e, tx('Could not download'))); }
  };
  const email = () => api.post(`/sales/proformas/${id}/email`, {}).then((r) => toast.success(tx('Sent to') + ' ' + r.data.sentTo)).catch((e) => toast.error(apiErrorMessage(e, tx('Could not send'))));
  if (isLoading) return <Skeleton className="h-96 w-full" />;
  if (!p) return <p className="text-sm text-muted-foreground">{tx('Proforma not found.')}</p>;
  const cur = p.currencyCode ?? undefined;
  const m = (v: number | string | null | undefined) => money(Number(v ?? 0), cur);
  const total = Number(p.totalAmount ?? 0);
  const tax = Number(p.taxAmount ?? 0);
  const discount = Number(p.discountTotal ?? 0);
  const subtotal = p.subtotal != null ? Number(p.subtotal) : total - tax;
  const advance = Number(p.advanceAmount ?? 0);
  const lines = p.lines ?? [];
  const facts: Array<[string, string | null | undefined]> = [
    [tx('Date'), p.issueDate ? date(p.issueDate) : null],
    [tx('Valid until'), p.dueDate ? date(p.dueDate) : null],
    [tx('Source'), p.sourceDocument],
    [tx('Customer ref'), p.reference],
    [tx('Salesperson'), p.salespersonName],
    [tx('Currency'), p.currencyCode],
  ];
  const customer = p.partner ?? null;
  return (
    <div className="space-y-4">
      <div className="text-sm"><Link to="/sales/proformas" className="font-medium text-primary hover:underline">{tx('Proforma Invoices')}</Link><span className="mx-1.5 text-muted-foreground">/</span><span className="font-semibold">{p.documentNumber}</span></div>
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <div className="flex items-center gap-2">
            <h1 className="text-xl font-semibold">{p.documentNumber}</h1>
            <Badge variant="secondary" className="capitalize">{p.status}</Badge>
          </div>
          <p className="text-sm text-muted-foreground">{tx('Proforma invoice')}{customer?.name ? ` · ${customer.name}` : ''}</p>
        </div>
        <div className="flex flex-wrap gap-2"><Button onClick={pdf}><Download className="mr-1.5 h-4 w-4" />{tx('Download PDF')}</Button><Button variant="outline" onClick={email}><Mail className="mr-1.5 h-4 w-4" />{tx('Email to customer')}</Button></div>
      </div>
      <p className="rounded-md border border-amber-200 bg-amber-50 px-3 py-2 text-xs text-amber-900 dark:border-amber-900/50 dark:bg-amber-950/30 dark:text-amber-200">{tx('Not a tax invoice. No journal or receivable is created.')}</p>
      <div className="grid gap-4 md:grid-cols-3">
        <section className="rounded-xl border bg-card p-4 text-sm shadow-sm">
          <h2 className="mb-2 text-xs font-semibold uppercase tracking-wide text-muted-foreground">{tx('Bill to')}</h2>
          {customer ? (
            <div className="space-y-0.5">
              <p className="font-semibold">{customer.name}</p>
              {customer.district && <p className="text-muted-foreground">{customer.district}</p>}
              {customer.phone && <p className="text-muted-foreground">{customer.phone}</p>}
              {customer.email && <p className="text-muted-foreground">{customer.email}</p>}
              {customer.taxNumber && <p className="text-muted-foreground">{tx('TIN')}: {customer.taxNumber}</p>}
            </div>
          ) : <p className="text-muted-foreground">—</p>}
          {p.deliveryAddress && <div className="mt-3"><h3 className="text-xs font-semibold uppercase tracking-wide text-muted-foreground">{tx('Deliver to')}</h3><p className="whitespace-pre-line">{p.deliveryAddress}</p></div>}
        </section>
        <section className="rounded-xl border bg-card p-4 text-sm shadow-sm">
          <h2 className="mb-2 text-xs font-semibold uppercase tracking-wide text-muted-foreground">{tx('Details')}</h2>
          <dl className="grid grid-cols-[auto_1fr] gap-x-4 gap-y-1">
            {facts.filter(([, v]) => v).map(([k, v]) => <div key={k} className="contents"><dt className="text-muted-foreground">{k}</dt><dd className="text-right font-medium">{v}</dd></div>)}
          </dl>
        </section>
        <section className="flex flex-col justify-center rounded-xl bg-primary p-4 text-primary-foreground shadow-sm">
          <p className="text-xs font-semibold uppercase tracking-wide opacity-80">{advance > 0 ? tx('Advance required') + (Number(p.advancePercent ?? 0) > 0 ? ` (${Number(p.advancePercent)}%)` : '') : tx('Amount to pay')}</p>
          <p className="text-2xl font-bold tabular-nums">{m(advance > 0 ? advance : total)}</p>
          {advance > 0 && <p className="text-xs opacity-80">{tx('of')} {m(total)} {tx('total')} · {tx('balance')} {m(total - advance)}</p>}
        </section>
      </div>
      <section className="overflow-x-auto rounded-xl border bg-card shadow-sm">
        <table className="w-full min-w-[640px] text-sm">
          <thead className="bg-muted/40 text-left text-xs font-semibold uppercase text-muted-foreground"><tr><th className="w-10 px-3 py-2">#</th><th className="px-3 py-2">{tx('Description')}</th><th className="px-3 py-2 text-right">{tx('Qty')}</th><th className="px-3 py-2 text-right">{tx('Unit price')}</th><th className="px-3 py-2 text-right">{tx('Disc %')}</th><th className="px-3 py-2 text-right">{tx('Tax')}</th><th className="px-3 py-2 text-right">{tx('Amount')}</th></tr></thead>
          <tbody>
            {lines.map((l, i) => (
              <tr key={l.id} className="border-t">
                <td className="px-3 py-2 text-muted-foreground">{i + 1}</td>
                <td className="px-3 py-2">{l.description}</td>
                <td className="px-3 py-2 text-right tabular-nums">{Number(l.quantity)}{l.unit ? ` ${l.unit}` : ''}</td>
                <td className="px-3 py-2 text-right tabular-nums">{m(l.unitPrice)}</td>
                <td className="px-3 py-2 text-right tabular-nums">{Number(l.discountPercent ?? 0) ? `${Number(l.discountPercent)}%` : '—'}</td>
                <td className="px-3 py-2 text-right">{l.tax ?? '—'}</td>
                <td className="px-3 py-2 text-right font-medium tabular-nums">{m(l.total)}</td>
              </tr>
            ))}
            {!lines.length && <tr><td colSpan={7} className="p-6 text-center text-muted-foreground">{tx('No lines.')}</td></tr>}
          </tbody>
        </table>
        <div className="flex justify-end border-t p-4">
          <dl className="grid w-full max-w-xs grid-cols-[1fr_auto] gap-x-6 gap-y-1 text-sm">
            {discount > 0 && <><dt className="text-muted-foreground">{tx('Gross amount')}</dt><dd className="text-right tabular-nums">{m(subtotal + discount)}</dd>
              <dt className="text-muted-foreground">{tx('Discount')}</dt><dd className="text-right tabular-nums">-{m(discount)}</dd></>}
            <dt className="text-muted-foreground">{tx('Subtotal')}</dt><dd className="text-right tabular-nums">{m(subtotal)}</dd>
            <dt className="text-muted-foreground">{tx('Tax')}</dt><dd className="text-right tabular-nums">{m(tax)}</dd>
            <dt className="border-t pt-1 font-semibold">{tx('Total')}</dt><dd className="border-t pt-1 text-right font-semibold tabular-nums">{m(total)}</dd>
            {advance > 0 && <><dt className="text-muted-foreground">{tx('Advance required')}</dt><dd className="text-right tabular-nums">{m(advance)}</dd>
              <dt className="text-muted-foreground">{tx('Balance on invoice')}</dt><dd className="text-right tabular-nums">{m(total - advance)}</dd></>}
          </dl>
        </div>
      </section>
      {(p.bankDetails || p.mobileDetails || p.notes) && (
        <div className="grid gap-4 md:grid-cols-2">
          {p.bankDetails && <section className="rounded-xl border bg-card p-4 text-sm shadow-sm"><h2 className="mb-1 text-xs font-semibold uppercase tracking-wide text-muted-foreground">{tx('Bank details')}</h2><p className="whitespace-pre-line">{p.bankDetails}</p></section>}
          {p.mobileDetails && <section className="rounded-xl border bg-card p-4 text-sm shadow-sm"><h2 className="mb-1 text-xs font-semibold uppercase tracking-wide text-muted-foreground">{tx('Mobile money')}</h2><p className="whitespace-pre-line">{p.mobileDetails}</p></section>}
          {p.notes && <section className="rounded-xl border bg-card p-4 text-sm shadow-sm md:col-span-2"><h2 className="mb-1 text-xs font-semibold uppercase tracking-wide text-muted-foreground">{tx('Notes')}</h2><p className="whitespace-pre-line">{p.notes}</p></section>}
        </div>
      )}
    </div>
  );
}

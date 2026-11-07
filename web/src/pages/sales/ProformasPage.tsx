import { useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { toast } from 'sonner';
import { FileText, Mail, Printer } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Skeleton } from '@/components/ui/skeleton';
import { ListPageHeader } from '@/components/list';
import { api } from '@/lib/api';
import { apiErrorMessage } from '@/lib/api-error';
import { date, money } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';

interface ProformaRow { id: string; documentNumber: string; status: string; issueDate: string; totalAmount: number; partnerName: string | null; sourceDocument: string | null; advanceAmount: string | null }
interface Proforma extends ProformaRow { notes: string | null; advancePercent: number | null; bankDetails: string | null; mobileDetails: string | null; lines?: Array<{ id: string; description: string; quantity: number; unitPrice: number; total: number }> }

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

/** One proforma: lines, advance, payment details, PDF and email. */
export function ProformaPage() {
  const { id } = useParams();
  const { data: p, isLoading } = useQuery({ queryKey: ['sales', 'proforma', id], queryFn: async () => (await api.get<Proforma>(`/sales/proformas/${id}`)).data });
  const pdf = async () => {
    try {
      const r = await api.get(`/sales/proformas/${id}/pdf`, { responseType: 'blob' });
      const url = URL.createObjectURL(r.data as Blob);
      const a = document.createElement('a'); a.href = url; a.download = `${p?.documentNumber}.pdf`; a.click(); URL.revokeObjectURL(url);
    } catch (e) { toast.error(apiErrorMessage(e, tx('Could not download'))); }
  };
  const email = () => api.post(`/sales/proformas/${id}/email`, {}).then((r) => toast.success(tx('Sent to') + ' ' + r.data.sentTo)).catch((e) => toast.error(apiErrorMessage(e, tx('Could not send'))));
  if (isLoading) return <Skeleton className="h-96 w-full" />;
  if (!p) return <p className="text-sm text-muted-foreground">{tx('Proforma not found.')}</p>;
  return (
    <div className="space-y-4">
      <div className="text-sm"><Link to="/sales/proformas" className="font-medium text-primary hover:underline">{tx('Proforma Invoices')}</Link><span className="mx-1.5 text-muted-foreground">/</span><span className="font-semibold">{p.documentNumber}</span></div>
      <div className="flex flex-wrap gap-2"><Button variant="outline" onClick={pdf}><Printer className="mr-1.5 h-4 w-4" />{tx('Download PDF')}</Button><Button variant="outline" onClick={email}><Mail className="mr-1.5 h-4 w-4" />{tx('Email to customer')}</Button></div>
      <section className="space-y-4 rounded-xl border bg-card p-5 text-sm shadow-sm">
        <p className="rounded-md bg-muted/50 p-2 text-xs text-muted-foreground">{tx('Not a tax invoice. No journal or receivable is created.')}</p>
        <table className="w-full"><thead className="text-left text-xs text-muted-foreground"><tr><th className="py-1">{tx('Description')}</th><th className="py-1 text-right">{tx('Qty')}</th><th className="py-1 text-right">{tx('Unit price')}</th><th className="py-1 text-right">{tx('Amount')}</th></tr></thead>
          <tbody>{(p.lines ?? []).map((l) => <tr key={l.id} className="border-t"><td className="py-1">{l.description}</td><td className="py-1 text-right tabular-nums">{Number(l.quantity)}</td><td className="py-1 text-right tabular-nums">{money(l.unitPrice)}</td><td className="py-1 text-right tabular-nums">{money(l.total)}</td></tr>)}</tbody></table>
        <div className="flex justify-end gap-8 border-t pt-2"><span>{tx('Advance required')}: <strong>{money(Number(p.advanceAmount ?? 0))}</strong></span><span>{tx('Total')}: <strong>{money(p.totalAmount)}</strong></span></div>
        {p.bankDetails && <p><span className="font-medium">{tx('Bank details')}: </span>{p.bankDetails}</p>}
        {p.mobileDetails && <p><span className="font-medium">{tx('Mobile money')}: </span>{p.mobileDetails}</p>}
      </section>
    </div>
  );
}

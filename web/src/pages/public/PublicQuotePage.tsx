import { useCallback, useEffect, useState } from 'react';
import { useParams } from 'react-router-dom';
import { CheckCircle2, Download, FileText, Loader2, XCircle } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Textarea } from '@/components/ui/textarea';
import { getApiBaseUrl } from '@/lib/api';
import { date, dateTime, money } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';

interface PublicLine {
  id: string;
  description: string | null;
  productName: string | null;
  variantName: string | null;
  quantity: number;
  unitPrice: number;
  discountPercent: number | null;
  lineTotal: number;
  isOptional: boolean | null;
  displayType: 'line_section' | 'line_note' | null;
}

interface PublicQuote {
  number: string;
  revision: number;
  status: string;
  issueDate: string;
  validUntil: string | null;
  currencyCode: string;
  partnerName: string | null;
  subtotal: number;
  discountTotal: number;
  taxAmount: number;
  total: number;
  notes: string | null;
  terms: string | null;
  lines: PublicLine[];
  company: { name: string };
  link: { decision: 'accepted' | 'declined' | null; decidedAt: string | null; signerName: string | null; expiresAt: string };
  canAnswer: boolean;
}

/** The customer's view of one shared quotation: read, download, accept or decline (CRM enhancements T3a). */
export function PublicQuotePage() {
  const { token = '' } = useParams();
  const base = `${getApiBaseUrl()}/public/sales/quotes/${encodeURIComponent(token)}`;
  const [q, setQ] = useState<PublicQuote | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [mode, setMode] = useState<'view' | 'decline'>('view');
  const [signer, setSigner] = useState('');
  const [note, setNote] = useState('');
  const [agree, setAgree] = useState(false);
  const [busy, setBusy] = useState(false);
  const [formError, setFormError] = useState<string | null>(null);

  const load = useCallback(async () => {
    try {
      const r = await fetch(base, { credentials: 'omit' });
      const body = await r.json().catch(() => ({}));
      if (!r.ok) throw new Error(body.message || tx('This link is not valid.'));
      setQ(body);
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
  }, [base]);
  useEffect(() => { void load(); }, [load]);

  const answer = async (action: 'accept' | 'decline') => {
    setBusy(true);
    setFormError(null);
    try {
      const body = action === 'accept' ? { signerName: signer, note: note || undefined } : { signerName: signer || undefined, reason: note || undefined };
      const r = await fetch(`${base}/${action}`, { method: 'POST', credentials: 'omit', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) });
      const out = await r.json().catch(() => ({}));
      if (!r.ok) throw new Error(Array.isArray(out.message) ? out.message.join(', ') : out.message || tx('Something went wrong. Please try again.'));
      setQ(out);
      setMode('view');
      setNote('');
    } catch (e) {
      setFormError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  };

  if (error) {
    return (
      <Shell>
        <div className="rounded-xl border bg-card p-8 text-center">
          <FileText className="mx-auto mb-3 h-10 w-10 text-muted-foreground" aria-hidden />
          <p className="font-medium">{error}</p>
          <p className="mt-1 text-sm text-muted-foreground">{tx('Please ask the sender for a new link.')}</p>
        </div>
      </Shell>
    );
  }
  if (!q) return <Shell><div className="flex justify-center p-16"><Loader2 className="h-6 w-6 animate-spin text-muted-foreground" aria-label={tx('Loading')} /></div></Shell>;

  const cur = q.currencyCode;
  return (
    <Shell company={q.company?.name}>
      <div className="space-y-4">
        <div className="flex flex-wrap items-start justify-between gap-3 rounded-xl border bg-card p-5">
          <div>
            <p className="text-xs uppercase tracking-wide text-muted-foreground">{tx('Quotation')}</p>
            <h1 className="text-2xl font-semibold">{q.number}{q.revision > 1 ? <span className="ml-2 text-sm font-normal text-muted-foreground">{tx('rev.')} {q.revision}</span> : null}</h1>
            <p className="mt-1 text-sm text-muted-foreground">
              {q.partnerName ? `${tx('For')} ${q.partnerName} · ` : ''}{tx('Issued')} {date(q.issueDate)}{q.validUntil ? ` · ${tx('Valid until')} ${date(q.validUntil)}` : ''}
            </p>
          </div>
          <div className="text-right">
            <p className="text-xs text-muted-foreground">{tx('Total')}</p>
            <p className="text-2xl font-semibold tabular-nums">{money(q.total, cur)}</p>
            <Button asChild variant="outline" size="sm" className="mt-2"><a href={`${base}/pdf`} target="_blank" rel="noreferrer"><Download className="mr-1.5 h-4 w-4" />{tx('Download PDF')}</a></Button>
          </div>
        </div>

        {q.link?.decision === 'accepted' && (
          <Banner tone="ok"><CheckCircle2 className="h-5 w-5" aria-hidden />{tx('Accepted by')} {q.link.signerName}{q.link.decidedAt ? ` · ${dateTime(q.link.decidedAt)}` : ''}. {tx('Thank you, we will be in touch about the next steps.')}</Banner>
        )}
        {q.link?.decision === 'declined' && (
          <Banner tone="no"><XCircle className="h-5 w-5" aria-hidden />{tx('You declined this quotation')}{q.link.decidedAt ? ` · ${dateTime(q.link.decidedAt)}` : ''}. {tx('Thank you for letting us know.')}</Banner>
        )}
        {!q.link?.decision && !q.canAnswer && (
          <Banner tone="info">{q.status === 'expired' || (q.validUntil && new Date(q.validUntil) < new Date(new Date().toDateString())) ? tx('This quotation has expired. Please contact us for an updated one.') : tx('This quotation can no longer be answered online.')}</Banner>
        )}

        <div className="overflow-x-auto rounded-xl border bg-card">
          <table className="w-full min-w-[560px] text-sm">
            <thead className="border-b bg-muted/40 text-left text-xs uppercase tracking-wide text-muted-foreground">
              <tr><th className="p-3">{tx('Description')}</th><th className="p-3 text-right">{tx('Qty')}</th><th className="p-3 text-right">{tx('Unit price')}</th><th className="p-3 text-right">{tx('Disc. %')}</th><th className="p-3 text-right">{tx('Amount')}</th></tr>
            </thead>
            <tbody className="divide-y">
              {q.lines.map((l) => l.displayType ? (
                <tr key={l.id}><td colSpan={5} className={l.displayType === 'line_section' ? 'bg-muted/30 p-3 font-semibold' : 'p-3 italic text-muted-foreground'}>{l.description}</td></tr>
              ) : (
                <tr key={l.id}>
                  <td className="p-3">
                    <p className="font-medium">{l.productName ?? l.description}{l.variantName ? ` — ${l.variantName}` : ''}{l.isOptional ? <span className="ml-2 rounded bg-muted px-1.5 text-xs font-normal">{tx('Optional')}</span> : null}</p>
                    {l.productName && l.description && l.description !== l.productName && <p className="text-xs text-muted-foreground">{l.description}</p>}
                  </td>
                  <td className="p-3 text-right tabular-nums">{Number(l.quantity)}</td>
                  <td className="p-3 text-right tabular-nums">{money(l.unitPrice, cur)}</td>
                  <td className="p-3 text-right tabular-nums">{l.discountPercent ? Number(l.discountPercent) : ''}</td>
                  <td className="p-3 text-right tabular-nums">{money(l.lineTotal, cur)}</td>
                </tr>
              ))}
            </tbody>
          </table>
          <dl className="ml-auto grid max-w-xs grid-cols-2 gap-y-1 border-t p-3 text-sm">
            <dt className="text-muted-foreground">{tx('Subtotal')}</dt><dd className="text-right tabular-nums">{money(q.subtotal, cur)}</dd>
            {Number(q.discountTotal) > 0 && <><dt className="text-muted-foreground">{tx('Discount')}</dt><dd className="text-right tabular-nums">−{money(q.discountTotal, cur)}</dd></>}
            <dt className="text-muted-foreground">{tx('Tax')}</dt><dd className="text-right tabular-nums">{money(q.taxAmount, cur)}</dd>
            <dt className="font-semibold">{tx('Total')}</dt><dd className="text-right font-semibold tabular-nums">{money(q.total, cur)}</dd>
          </dl>
        </div>

        {(q.notes || q.terms) && (
          <div className="space-y-3 rounded-xl border bg-card p-5 text-sm">
            {q.notes && <div><p className="mb-1 font-medium">{tx('Notes')}</p><p className="whitespace-pre-line text-muted-foreground">{q.notes}</p></div>}
            {q.terms && <div><p className="mb-1 font-medium">{tx('Terms and conditions')}</p><p className="whitespace-pre-line text-muted-foreground">{q.terms}</p></div>}
          </div>
        )}

        {q.canAnswer && (
          <div className="rounded-xl border bg-card p-5">
            {mode === 'view' ? (
              <form className="space-y-3" onSubmit={(e) => { e.preventDefault(); void answer('accept'); }}>
                <h2 className="text-lg font-semibold">{tx('Accept this quotation')}</h2>
                <label className="block text-sm"><span className="mb-1 block font-medium">{tx('Your full name')}</span>
                  <Input required minLength={2} maxLength={120} autoComplete="name" value={signer} onChange={(e) => setSigner(e.target.value)} />
                </label>
                <label className="block text-sm"><span className="mb-1 block font-medium">{tx('Note (optional)')}</span>
                  <Textarea rows={2} maxLength={1000} placeholder={tx('e.g. purchase order number, delivery wishes')} value={note} onChange={(e) => setNote(e.target.value)} />
                </label>
                <label className="flex items-start gap-2 text-sm">
                  <input type="checkbox" className="mt-0.5" checked={agree} onChange={(e) => setAgree(e.target.checked)} />
                  <span>{tx('I accept this quotation, including its terms, on behalf of')} {q.partnerName ?? tx('my company')}.</span>
                </label>
                {formError && <p role="alert" className="text-sm text-rose-600">{formError}</p>}
                <div className="flex flex-wrap gap-2">
                  <Button type="submit" disabled={busy || !agree || signer.trim().length < 2}>{busy ? <Loader2 className="mr-1.5 h-4 w-4 animate-spin" /> : <CheckCircle2 className="mr-1.5 h-4 w-4" />}{tx('Accept quotation')}</Button>
                  <Button type="button" variant="ghost" onClick={() => { setMode('decline'); setFormError(null); setNote(''); }}>{tx('Decline')}</Button>
                </div>
              </form>
            ) : (
              <form className="space-y-3" onSubmit={(e) => { e.preventDefault(); void answer('decline'); }}>
                <h2 className="text-lg font-semibold">{tx('Decline this quotation')}</h2>
                <label className="block text-sm"><span className="mb-1 block font-medium">{tx('Reason (optional)')}</span>
                  <Textarea rows={3} maxLength={1000} placeholder={tx('Tell us why, so we can do better next time')} value={note} onChange={(e) => setNote(e.target.value)} />
                </label>
                <label className="block text-sm"><span className="mb-1 block font-medium">{tx('Your name (optional)')}</span>
                  <Input maxLength={120} autoComplete="name" value={signer} onChange={(e) => setSigner(e.target.value)} />
                </label>
                {formError && <p role="alert" className="text-sm text-rose-600">{formError}</p>}
                <div className="flex flex-wrap gap-2">
                  <Button type="submit" variant="destructive" disabled={busy}>{tx('Decline quotation')}</Button>
                  <Button type="button" variant="ghost" onClick={() => { setMode('view'); setFormError(null); setNote(''); }}>{tx('Back')}</Button>
                </div>
              </form>
            )}
          </div>
        )}
      </div>
    </Shell>
  );
}

function Shell({ company, children }: { company?: string; children: React.ReactNode }) {
  return (
    <div className="min-h-screen bg-muted/30">
      <header className="border-b bg-background">
        <div className="mx-auto max-w-4xl px-4 py-3 text-sm font-semibold">{company ?? ' '}</div>
      </header>
      <main className="mx-auto max-w-4xl px-4 py-6">{children}</main>
    </div>
  );
}

function Banner({ tone, children }: { tone: 'ok' | 'no' | 'info'; children: React.ReactNode }) {
  const cls = tone === 'ok' ? 'border-emerald-200 bg-emerald-50 text-emerald-900 dark:border-emerald-900 dark:bg-emerald-950/40 dark:text-emerald-100'
    : tone === 'no' ? 'border-rose-200 bg-rose-50 text-rose-900 dark:border-rose-900 dark:bg-rose-950/40 dark:text-rose-100'
      : 'border-amber-200 bg-amber-50 text-amber-900 dark:border-amber-900 dark:bg-amber-950/40 dark:text-amber-100';
  return <div role="status" className={`flex items-center gap-2 rounded-xl border p-4 text-sm ${cls}`}>{children}</div>;
}

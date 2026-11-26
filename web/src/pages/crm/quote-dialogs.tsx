import { useEffect, useMemo, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { toast } from 'sonner';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Textarea } from '@/components/ui/textarea';
import { Dialog, DialogContent, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { api } from '@/lib/api';
import { apiErrorMessage } from '@/lib/api-error';
import { date, dateTime, money } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';
import { cn } from '@/lib/utils';
import { useAuthStore } from '@/stores/auth.store';
import {
  QUOTE_STATUS_LABEL, crmApi, useCrmLookups, useCrmMutation, useQuoteRevisions,
  type Quotation, type QuoteLine, type QuoteRevision,
} from '@/features/crm/suite-api';
import { Field } from './crm-ui';
import { diffLines, fillTemplate, num } from './quote-utils';

// ------------------------------------------------------------------ revision history

type Side = { label: string; header: Record<string, unknown>; lines: QuoteLine[] };
const CURRENT = 'current';

/** Header fields compared between two revisions. */
const HEADER_FIELDS: Array<[string, string, 'date' | 'money' | 'text']> = [
  ['issueDate', tx('Quotation date'), 'date'], ['validUntil', tx('Expiration'), 'date'], ['currencyCode', tx('Currency'), 'text'],
  ['deliveryAddress', tx('Delivery address'), 'text'], ['commitmentDate', tx('Delivery date'), 'date'],
  ['subtotal', tx('Untaxed amount'), 'money'], ['taxAmount', tx('Taxes'), 'money'], ['total', tx('Total'), 'money'],
  ['notes', tx('Notes'), 'text'], ['terms', tx('Terms and conditions'), 'text'],
];

/** Revisions of a quotation (snapshots taken by Revise), and a side-by-side comparison of any two (or with the current). */
export function QuoteRevisionsDialog({ q, open, onOpenChange }: { q: Quotation; open: boolean; onOpenChange: (v: boolean) => void }) {
  const { data: revisions, isLoading } = useQuoteRevisions(q.id, open);
  const { data: lk } = useCrmLookups();
  const userName = (id: string | null) => lk?.users.find((u) => u.id === id)?.name ?? '—';
  const [left, setLeft] = useState<string>('');
  const [right, setRight] = useState<string>(CURRENT);
  useEffect(() => { if (revisions?.length && !left) setLeft(revisions[0].id); }, [revisions, left]);

  const side = (key: string): Side | null => {
    if (key === CURRENT) return { label: `${tx('Current')} (r${q.revision})`, header: q as unknown as Record<string, unknown>, lines: q.lines ?? [] };
    const r = revisions?.find((x: QuoteRevision) => x.id === key);
    return r ? { label: `r${r.revision} · ${QUOTE_STATUS_LABEL[r.status] ?? r.status}`, header: r.snapshot, lines: (r.snapshot.lines ?? []) as QuoteLine[] } : null;
  };
  const a = side(left);
  const b = side(right);
  const cur = q.currencyCode;
  const fmt = (v: unknown, kind: 'date' | 'money' | 'text') => (v == null || v === '' ? '—' : kind === 'date' ? date(String(v)) : kind === 'money' ? money(num(v), cur) : String(v));
  const rows = useMemo(() => (a && b ? diffLines(a.lines, b.lines) : []), [a, b]);
  const options = [{ value: CURRENT, label: `${tx('Current')} (r${q.revision})` }, ...(revisions ?? []).map((r) => ({ value: r.id, label: `r${r.revision} · ${QUOTE_STATUS_LABEL[r.status] ?? r.status} · ${dateTime(r.createdAt)}` }))];
  const lineText = (l?: QuoteLine) => (l ? `${num(l.quantity)} × ${money(num(l.unitPrice), cur)}${num(l.discountPercent) ? ` −${num(l.discountPercent)}%` : ''}${l.isOptional ? ` (${tx('optional')})` : ''}` : '');
  const tone = { same: '', changed: 'bg-amber-50 dark:bg-amber-950/30', added: 'bg-emerald-50 dark:bg-emerald-950/30', removed: 'bg-rose-50 dark:bg-rose-950/30' };
  const changeLabel = { same: '', changed: tx('Changed'), added: tx('Added'), removed: tx('Removed') };

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-h-[90vh] max-w-5xl overflow-y-auto">
        <DialogHeader><DialogTitle>{tx('Revision history')} — {q.number}</DialogTitle></DialogHeader>
        {isLoading ? <p className="text-sm text-muted-foreground">{tx('Loading…')}</p> : !revisions?.length ? (
          <p className="text-sm text-muted-foreground">{tx('No earlier revisions. Revise creates one, keeping the terms as they were.')}</p>
        ) : (
          <div className="space-y-4 text-sm">
            <table className="w-full text-left">
              <thead className="text-xs uppercase text-muted-foreground"><tr><th className="py-1">{tx('Revision')}</th><th>{tx('Status')}</th><th className="text-right">{tx('Total')}</th><th>{tx('Saved')}</th><th>{tx('By')}</th></tr></thead>
              <tbody>
                {revisions.map((r) => (
                  <tr key={r.id} className="border-t"><td className="py-1 font-medium">r{r.revision}</td><td>{QUOTE_STATUS_LABEL[r.status] ?? r.status}</td>
                    <td className="text-right tabular-nums">{money(num(r.total), cur)}</td><td>{dateTime(r.createdAt)}</td><td>{userName(r.createdById)}</td></tr>
                ))}
              </tbody>
            </table>
            <div className="flex flex-wrap items-center gap-2 border-t pt-3">
              <span className="font-medium">{tx('Compare')}</span>
              <select aria-label={tx('Left revision')} className="h-9 rounded-md border bg-background px-2" value={left} onChange={(e) => setLeft(e.target.value)}>
                {options.map((o) => <option key={o.value} value={o.value}>{o.label}</option>)}
              </select>
              <span>{tx('with')}</span>
              <select aria-label={tx('Right revision')} className="h-9 rounded-md border bg-background px-2" value={right} onChange={(e) => setRight(e.target.value)}>
                {options.map((o) => <option key={o.value} value={o.value}>{o.label}</option>)}
              </select>
            </div>
            {a && b && (
              <>
                <div className="overflow-x-auto rounded-lg border">
                  <table className="w-full min-w-[640px]">
                    <thead className="bg-muted/40 text-xs uppercase text-muted-foreground"><tr><th className="p-2 text-left">{tx('Field')}</th><th className="p-2 text-left">{a.label}</th><th className="p-2 text-left">{b.label}</th></tr></thead>
                    <tbody>
                      {HEADER_FIELDS.map(([k, label, kind]) => {
                        const x = fmt(a.header[k], kind); const y = fmt(b.header[k], kind);
                        return <tr key={k} className={cn('border-t', x !== y && tone.changed)}><td className="p-2 text-muted-foreground">{label}</td><td className="whitespace-pre-line p-2">{x}</td><td className="whitespace-pre-line p-2">{y}</td></tr>;
                      })}
                    </tbody>
                  </table>
                </div>
                <div className="overflow-x-auto rounded-lg border">
                  <table className="w-full min-w-[640px]">
                    <thead className="bg-muted/40 text-xs uppercase text-muted-foreground"><tr><th className="p-2 text-left">{tx('Line')}</th><th className="p-2 text-left">{a.label}</th><th className="p-2 text-left">{b.label}</th><th className="p-2 text-left">{tx('Change')}</th></tr></thead>
                    <tbody>
                      {rows.length === 0 && <tr><td colSpan={4} className="p-3 text-center text-muted-foreground">{tx('No lines.')}</td></tr>}
                      {rows.map((r, i) => (
                        <tr key={i} className={cn('border-t', tone[r.change])}>
                          <td className="p-2">{(r.b ?? r.a)?.description}</td>
                          <td className="p-2 tabular-nums">{lineText(r.a)}{r.a && <span className="block text-xs text-muted-foreground">{money(num(r.a.lineTotal), cur)}</span>}</td>
                          <td className="p-2 tabular-nums">{lineText(r.b)}{r.b && <span className="block text-xs text-muted-foreground">{money(num(r.b.lineTotal), cur)}</span>}</td>
                          <td className="p-2 text-xs font-medium">{changeLabel[r.change]}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              </>
            )}
          </div>
        )}
      </DialogContent>
    </Dialog>
  );
}

// ------------------------------------------------------------------ email compose

const DEFAULT_SUBJECT = 'Quotation {number} from {company}';
const DEFAULT_BODY = 'Dear {customer},\n\nPlease find attached our quotation {number} for {total}, valid until {validUntil}.\n\n{link}\n\nKind regards,\n{company}';

/**
 * Compose the quotation email: to (the quotation contact's, else the customer's address), cc, subject and message from
 * the sales settings template, and optionally a fresh online link (any earlier link stops working).
 */
export function QuoteEmailDialog({ q, open, onOpenChange, canShare, onSent }: {
  q: Quotation; open: boolean; onOpenChange: (v: boolean) => void; canShare: boolean; onSent: () => void;
}) {
  const { data: lk } = useCrmLookups();
  const company = useAuthStore((s) => s.organization?.name ?? '');
  const partner = useQuery({
    queryKey: ['quote-email', 'partner', q.partnerId],
    queryFn: async () => (await api.get<{ email: string | null; contacts?: Array<{ id: string; email: string | null }> }>(`/partners/${q.partnerId}`)).data,
    enabled: open, retry: false,
  });
  const [to, setTo] = useState('');
  const [cc, setCc] = useState('');
  const [subject, setSubject] = useState('');
  const [body, setBody] = useState('');
  const [withLink, setWithLink] = useState(canShare);
  const [busy, setBusy] = useState(false);
  const vars = useMemo(() => ({ number: q.number, customer: q.partnerName ?? '', validUntil: q.validUntil ? date(q.validUntil) : '', total: money(q.total, q.currencyCode), company }), [q, company]);

  useEffect(() => {
    if (!open) return;
    setSubject(fillTemplate(lk?.settings.quoteEmailSubject || DEFAULT_SUBJECT, { ...vars, link: '' }));
    setBody(lk?.settings.quoteEmailBody || DEFAULT_BODY);
    setCc('');
    setWithLink(canShare);
  }, [open, lk, vars, canShare]);
  useEffect(() => {
    if (!open || !partner.data) return;
    const contact = partner.data.contacts?.find((c) => c.id === q.contactId);
    setTo(contact?.email || partner.data.email || '');
  }, [open, partner.data, q.contactId]);

  const send = async () => {
    setBusy(true);
    try {
      let link = '';
      if (withLink) {
        const r = (await api.post<{ path: string }>(`/sales/quotations/${q.id}/share-link`)).data;
        link = `${tx('View and accept online')}: ${window.location.origin}${r.path}`;
      }
      let text = fillTemplate(body, { ...vars, link });
      if (link && !body.includes('{link}')) text = `${text}\n\n${link}`;
      const out = await crmApi.emailQuote({ id: q.id, to: to.trim(), cc: cc.split(/[,;\s]+/).map((x) => x.trim()).filter(Boolean), subject, body: text.replace(/\n{3,}/g, '\n\n').trim() });
      if (out.delivered) toast.success(`${tx('Sent to')} ${out.sentTo}`);
      else toast.warning(tx('Marked as sent, but outgoing email is not set up on this server (SMTP).'));
      onSent();
      onOpenChange(false);
    } catch (e) {
      toast.error(apiErrorMessage(e, tx('Could not send')));
    } finally {
      setBusy(false);
    }
  };

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-w-2xl">
        <DialogHeader><DialogTitle>{tx('Email quotation')} {q.number}</DialogTitle></DialogHeader>
        <div className="space-y-3 text-sm">
          <Field label={tx('To')}><Input type="email" value={to} onChange={(e) => setTo(e.target.value)} placeholder="customer@example.com" /></Field>
          <Field label={tx('Cc (comma separated)')}><Input value={cc} onChange={(e) => setCc(e.target.value)} /></Field>
          <Field label={tx('Subject')}><Input value={subject} maxLength={300} onChange={(e) => setSubject(e.target.value)} /></Field>
          <Field label={tx('Message')}><Textarea rows={9} value={body} onChange={(e) => setBody(e.target.value)} /></Field>
          <p className="text-xs text-muted-foreground">{tx('Placeholders')}: {'{number} {customer} {validUntil} {total} {company} {link}'} · {tx('The quotation PDF is attached.')}</p>
          {canShare && (
            <label className="flex items-start gap-2">
              <input type="checkbox" className="mt-0.5" checked={withLink} onChange={(e) => setWithLink(e.target.checked)} />
              <span>{tx('Include an online link to view and accept')}<span className="block text-xs text-muted-foreground">{tx('A new link is created; an earlier link stops working.')}</span></span>
            </label>
          )}
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={() => onOpenChange(false)}>{tx('Cancel')}</Button>
          <Button disabled={busy || !to.trim()} onClick={() => void send()}>{tx('Send')}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

// ------------------------------------------------------------------ accept with optional products

/** "Customer accepted": which optional products the customer took (they then count in the totals). */
export function QuoteAcceptDialog({ q, open, onOpenChange, onDone }: { q: Quotation; open: boolean; onOpenChange: (v: boolean) => void; onDone: () => void }) {
  const optional = (q.lines ?? []).filter((l) => !l.displayType && l.isOptional && l.id);
  const [chosen, setChosen] = useState<string[]>([]);
  useEffect(() => { if (open) setChosen([]); }, [open]);
  const accept = useCrmMutation(crmApi.acceptQuote);
  const extra = optional.filter((l) => chosen.includes(l.id!)).reduce((s, l) => s + num(l.lineTotal), 0);
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-w-lg">
        <DialogHeader><DialogTitle>{tx('Customer accepted')}</DialogTitle></DialogHeader>
        <div className="space-y-2 text-sm">
          <p className="text-muted-foreground">{tx('Tick the optional products the customer takes; they are added to the totals.')}</p>
          {optional.map((l) => (
            <label key={l.id} className="flex items-center justify-between gap-2 rounded-lg border p-2">
              <span className="flex items-center gap-2"><input type="checkbox" checked={chosen.includes(l.id!)} onChange={(e) => setChosen((c) => (e.target.checked ? [...c, l.id!] : c.filter((x) => x !== l.id)))} />{l.description}</span>
              <span className="tabular-nums">{money(num(l.lineTotal), q.currencyCode)}</span>
            </label>
          ))}
          <p className="text-right font-medium">{tx('New total')}: {money(num(q.total) + extra, q.currencyCode)}</p>
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={() => onOpenChange(false)}>{tx('Cancel')}</Button>
          <Button disabled={accept.isPending} onClick={() => accept.mutate({ id: q.id, optionalLineIds: chosen }, {
            onSuccess: () => { toast.success(tx('Accepted by customer')); onDone(); onOpenChange(false); },
            onError: (e) => toast.error(apiErrorMessage(e, tx('Action failed'))),
          })}>{tx('Confirm acceptance')}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

// ------------------------------------------------------------------ templates

/** Save this quotation's lines, notes and terms as a reusable template. */
export function SaveTemplateDialog({ q, open, onOpenChange }: { q: Quotation; open: boolean; onOpenChange: (v: boolean) => void }) {
  const [name, setName] = useState('');
  const [description, setDescription] = useState('');
  useEffect(() => { if (open) { setName(''); setDescription(''); } }, [open]);
  const save = useCrmMutation(crmApi.saveQuoteTemplate);
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-w-md">
        <DialogHeader><DialogTitle>{tx('Save as template')}</DialogTitle></DialogHeader>
        <div className="space-y-3 text-sm">
          <Field label={tx('Template name')}><Input value={name} maxLength={120} onChange={(e) => setName(e.target.value)} /></Field>
          <Field label={tx('Description (optional)')}><Textarea rows={2} value={description} onChange={(e) => setDescription(e.target.value)} /></Field>
          <p className="text-xs text-muted-foreground">{tx('Lines, notes and terms are kept. Prices from price rules are worked out again when the template is used.')}</p>
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={() => onOpenChange(false)}>{tx('Cancel')}</Button>
          <Button disabled={save.isPending || !name.trim()} onClick={() => save.mutate({ name: name.trim(), quotationId: q.id, description: description.trim() || undefined }, {
            onSuccess: () => { toast.success(tx('Template saved')); onOpenChange(false); },
            onError: (e) => toast.error(apiErrorMessage(e, tx('Could not save'))),
          })}>{tx('Save')}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

import { useEffect, useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { toast } from 'sonner';
import { Merge, Package, Plus, Send, Trash2, UserPlus, X } from 'lucide-react';
import { api } from '@/lib/api';
import { apiErrorMessage } from '@/lib/api-error';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Textarea } from '@/components/ui/textarea';
import { SearchableSelect } from '@/components/ui/searchable-select';
import { Dialog, DialogContent, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { OptionalSelect } from '@/pages/fleet/fleet-ui';
import { money } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';
import { useDebouncedValue } from '@/lib/use-debounced-value';
import {
  useCrmMutation, useOpportunities, useProductOptions, type DealLine, type EmailTemplate, type OpportunityDetail,
} from '@/features/crm/suite-api';
import { Field } from './crm-ui';

const err = (fallback: string) => (e: unknown) => toast.error(apiErrorMessage(e, fallback));
const lineTotal = (l: DealLine) => Math.round(Number(l.quantity) * Number(l.unitPrice) * (100 - Number(l.discountPercent || 0))) / 100;

/** Expected products of an opportunity (CRM enhancements T3b); they pre-fill a new quotation. */
export function DealLinesCard({ o, canWrite }: { o: OpportunityDetail; canWrite: boolean }) {
  const [editing, setEditing] = useState<DealLine[] | null>(null);
  const products = useProductOptions();
  const productOpts = useMemo(() => (products.data ?? []).map((p) => ({ value: p.id, label: p.name, code: p.code ?? undefined })), [products.data]);
  const save = useCrmMutation((lines: DealLine[]) => api.put(`/crm/opportunities/${o.id}/lines`, {
    lines: lines.map((l) => ({ productId: l.productId, variantId: l.variantId ?? null, description: l.description, quantity: l.quantity, unitPrice: l.unitPrice, discountPercent: l.discountPercent })),
  }));
  const lines = o.lines ?? [];
  const rows = editing ?? lines;
  const total = rows.reduce((t, l) => t + lineTotal(l), 0);
  const patch = (i: number, p: Partial<DealLine>) => setEditing((ls) => (ls ?? []).map((l, j) => (j === i ? { ...l, ...p } : l)));
  const pick = (i: number, productId: string) => {
    const p = products.data?.find((x) => x.id === productId);
    patch(i, { productId: productId || null, description: p?.name ?? '', unitPrice: Number(p?.salesPrice ?? 0) });
  };

  return (
    <div>
      {rows.length === 0 && !editing && <p className="text-sm text-muted-foreground">{tx('No products yet. Add the products the customer is interested in; a new quotation starts from them.')}</p>}
      {rows.length > 0 && (
        <div className="overflow-x-auto rounded-lg border">
          <table className="w-full min-w-[560px] text-sm">
            <thead className="bg-muted/40 text-left text-xs text-muted-foreground">
              <tr><th className="p-2">{tx('Product')}</th><th className="w-20 p-2 text-right">{tx('Qty')}</th><th className="w-28 p-2 text-right">{tx('Unit price')}</th><th className="w-20 p-2 text-right">{tx('Disc. %')}</th><th className="w-28 p-2 text-right">{tx('Amount')}</th>{editing && <th className="w-8" />}</tr>
            </thead>
            <tbody className="divide-y">
              {rows.map((l, i) => editing ? (
                <tr key={i}>
                  <td className="space-y-1 p-1.5">
                    <SearchableSelect value={l.productId ?? ''} onValueChange={(v) => pick(i, v)} options={productOpts} placeholder={tx('Product (optional)')} className="w-full" />
                    <Input value={l.description} placeholder={tx('Description')} onChange={(e) => patch(i, { description: e.target.value })} />
                  </td>
                  <td className="p-1.5"><Input type="number" min={0} step="any" className="text-right" value={l.quantity} onChange={(e) => patch(i, { quantity: Number(e.target.value) })} /></td>
                  <td className="p-1.5"><Input type="number" min={0} step="any" className="text-right" value={l.unitPrice} onChange={(e) => patch(i, { unitPrice: Number(e.target.value) })} /></td>
                  <td className="p-1.5"><Input type="number" min={0} max={100} className="text-right" value={l.discountPercent} onChange={(e) => patch(i, { discountPercent: Number(e.target.value) })} /></td>
                  <td className="p-1.5 text-right tabular-nums">{money(lineTotal(l), o.currencyCode)}</td>
                  <td className="p-1.5"><button title={tx('Remove')} className="rounded p-1 text-muted-foreground hover:bg-muted" onClick={() => setEditing((ls) => (ls ?? []).filter((_, j) => j !== i))}><X className="h-4 w-4" /></button></td>
                </tr>
              ) : (
                <tr key={l.id ?? i}>
                  <td className="p-2"><p className="font-medium">{l.description}</p>{l.productCode && <p className="text-xs text-muted-foreground">{l.productCode}{l.variantName ? ` · ${l.variantName}` : ''}</p>}</td>
                  <td className="p-2 text-right tabular-nums">{Number(l.quantity)}</td>
                  <td className="p-2 text-right tabular-nums">{money(l.unitPrice, o.currencyCode)}</td>
                  <td className="p-2 text-right tabular-nums">{Number(l.discountPercent) || ''}</td>
                  <td className="p-2 text-right tabular-nums">{money(l.lineTotal ?? lineTotal(l), o.currencyCode)}</td>
                </tr>
              ))}
            </tbody>
            <tfoot><tr className="border-t font-semibold"><td className="p-2" colSpan={4}>{tx('Total')}</td><td className="p-2 text-right tabular-nums">{money(total, o.currencyCode)}</td>{editing && <td />}</tr></tfoot>
          </table>
        </div>
      )}
      {canWrite && (
        <div className="mt-2 flex flex-wrap gap-2">
          {!editing && <Button size="sm" variant="outline" onClick={() => setEditing(lines.length ? lines.map((l) => ({ ...l })) : [{ productId: null, description: '', quantity: 1, unitPrice: 0, discountPercent: 0 }])}><Package className="mr-1.5 h-4 w-4" />{lines.length ? tx('Edit products') : tx('Add products')}</Button>}
          {editing && (
            <>
              <Button size="sm" variant="outline" onClick={() => setEditing((ls) => [...(ls ?? []), { productId: null, description: '', quantity: 1, unitPrice: 0, discountPercent: 0 }])}><Plus className="mr-1 h-4 w-4" />{tx('Add a line')}</Button>
              <Button size="sm" disabled={save.isPending} onClick={() => save.mutate(editing, { onSuccess: () => { toast.success(tx('Products saved; expected revenue updated')); setEditing(null); }, onError: err(tx('Could not save the products')) })}>{tx('Save')}</Button>
              <Button size="sm" variant="ghost" onClick={() => setEditing(null)}>{tx('Cancel')}</Button>
            </>
          )}
        </div>
      )}
    </div>
  );
}

/** The other people on a deal (CRM enhancements T3b): contacts of the customer with a role. */
export function DealContactsCard({ o, canWrite }: { o: OpportunityDetail; canWrite: boolean }) {
  const [adding, setAdding] = useState(false);
  const [contactId, setContactId] = useState('');
  const [role, setRole] = useState('');
  const add = useCrmMutation(() => api.post(`/crm/opportunities/${o.id}/contacts`, { contactId, role: role || null }));
  const remove = useCrmMutation((id: string) => api.delete(`/crm/opportunities/${o.id}/contacts/${id}`));
  const linked = new Set((o.contacts ?? []).map((c) => c.contactId));
  const options = (o.partner?.contacts ?? []).filter((c) => !linked.has(c.id)).map((c) => ({ value: c.id, label: [c.firstName, c.lastName].filter(Boolean).join(' ') + (c.position ? ` — ${c.position}` : '') }));
  return (
    <div className="rounded-xl border bg-card p-4 shadow-sm">
      <div className="mb-2 flex items-center justify-between">
        <h2 className="text-sm font-semibold">{tx('People on this deal')}</h2>
        {canWrite && o.partner && <Button size="sm" variant="ghost" onClick={() => setAdding((v) => !v)}><UserPlus className="mr-1 h-4 w-4" />{tx('Add')}</Button>}
      </div>
      {(o.contacts ?? []).length === 0 && !adding && <p className="text-xs text-muted-foreground">{o.partner ? tx('Add the decision maker, finance or technical contacts of the customer.') : tx('Set a customer to add its contacts.')}</p>}
      <ul className="space-y-1.5">
        {(o.contacts ?? []).map((c) => (
          <li key={c.contactId} className="flex items-start gap-2 text-sm">
            <div className="min-w-0 flex-1">
              <p className="font-medium">{c.name ?? '—'}{c.role ? <span className="ml-1.5 rounded bg-muted px-1.5 text-xs font-normal">{c.role}</span> : null}</p>
              <p className="truncate text-xs text-muted-foreground">{[c.position, c.email, c.phone].filter(Boolean).join(' · ')}</p>
            </div>
            {canWrite && <button title={tx('Remove')} className="rounded p-1 text-muted-foreground hover:bg-muted" onClick={() => remove.mutate(c.contactId, { onError: err(tx('Could not remove')) })}><Trash2 className="h-3.5 w-3.5" /></button>}
          </li>
        ))}
      </ul>
      {adding && (
        <div className="mt-2 space-y-2 rounded-lg border p-2">
          {options.length === 0 ? <p className="text-xs text-muted-foreground">{tx('All contacts of this customer are already on the deal. Add contacts on the customer page.')}</p> : (
            <>
              <OptionalSelect value={contactId} onChange={setContactId} options={options} placeholder={tx('Choose a contact')} noneLabel={tx('Choose a contact')} />
              <Input value={role} maxLength={60} placeholder={tx('Role, e.g. Decision maker')} onChange={(e) => setRole(e.target.value)} />
              <Button size="sm" disabled={!contactId || add.isPending} onClick={() => add.mutate(undefined, { onSuccess: () => { setAdding(false); setContactId(''); setRole(''); }, onError: err(tx('Could not add')) })}>{tx('Add to deal')}</Button>
            </>
          )}
        </div>
      )}
    </div>
  );
}

/** Merge a duplicate lead / opportunity into this one (CRM enhancements T3b). */
export function MergeDealDialog({ o, open, onOpenChange }: { o: OpportunityDetail; open: boolean; onOpenChange: (v: boolean) => void }) {
  const [search, setSearch] = useState('');
  const q = useDebouncedValue(search, 300);
  const [sourceId, setSourceId] = useState('');
  const isLead = o.kind === 'lead';
  const path = isLead ? '/crm/leads' : '/crm/opportunities';
  const { data } = useOpportunities({ q: q || undefined, status: 'open', pageSize: 20, page: 1 }, path, open);
  const options = (data?.data ?? []).filter((d) => d.id !== o.id).map((d) => ({ value: d.id, label: `${d.name}${d.partnerName ?? d.companyName ? ` — ${d.partnerName ?? d.companyName}` : ''}` }));
  const merge = useCrmMutation(() => api.post(`${path}/${o.id}/merge`, { sourceId }));
  useEffect(() => { if (!open) { setSourceId(''); setSearch(''); } }, [open]);
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-w-lg">
        <DialogHeader><DialogTitle>{tx('Merge a duplicate into this record')}</DialogTitle></DialogHeader>
        <div className="space-y-3 text-sm">
          <p className="text-muted-foreground">{tx('Its activities, quotations, products and contacts move here; empty fields here are filled from it, tags are combined and its notes appended. The duplicate is then archived.')}</p>
          <SearchableSelect value={sourceId} onValueChange={setSourceId} options={options} onQueryChange={setSearch} placeholder={isLead ? tx('Find the duplicate lead…') : tx('Find the duplicate opportunity…')} className="w-full" />
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={() => onOpenChange(false)}>{tx('Cancel')}</Button>
          <Button disabled={!sourceId || merge.isPending} onClick={() => merge.mutate(undefined, { onSuccess: () => { toast.success(tx('Merged')); onOpenChange(false); }, onError: err(tx('Could not merge')) })}><Merge className="mr-1.5 h-4 w-4" />{tx('Merge')}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

/** Write an email from a lead / opportunity, optionally from a template (CRM enhancements T3b). */
export function EmailDialog({ o, open, onOpenChange }: { o: OpportunityDetail; open: boolean; onOpenChange: (v: boolean) => void }) {
  const path = o.kind === 'lead' ? '/crm/leads' : '/crm/opportunities';
  const [templateId, setTemplateId] = useState('');
  const [to, setTo] = useState('');
  const [subject, setSubject] = useState('');
  const [body, setBody] = useState('');
  const navigate = useNavigate();
  const templates = useQuery({
    queryKey: ['crm-suite', 'email-templates'],
    queryFn: async () => (await api.get<{ data: EmailTemplate[] }>('/crm/config/email-templates')).data.data,
    enabled: open,
  });
  useEffect(() => {
    if (!open) return;
    api.get<{ to: string | null; subject: string; body: string }>(`${path}/${o.id}/email`, { params: { templateId: templateId || undefined } })
      .then((r) => { setTo((t) => t || r.data.to || ''); if (templateId) { setSubject(r.data.subject); setBody(r.data.body); } })
      .catch(err(tx('Could not load the template')));
  }, [open, templateId, path, o.id]);
  useEffect(() => { if (!open) { setTemplateId(''); setTo(''); setSubject(''); setBody(''); } }, [open]);
  const send = useCrmMutation(() => api.post<{ sentTo: string; delivered: boolean }>(`${path}/${o.id}/email`, { to, subject, body }));
  const active = (templates.data ?? []).filter((t) => t.isActive);
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-h-[92vh] max-w-2xl overflow-y-auto">
        <DialogHeader><DialogTitle>{tx('Send an email')}</DialogTitle></DialogHeader>
        <div className="grid gap-3">
          <Field label={tx('Template')} hint={active.length === 0 ? tx('Create templates in CRM configuration → Email templates.') : undefined}>
            <OptionalSelect value={templateId} onChange={setTemplateId} options={active.map((t) => ({ value: t.id, label: t.name }))} placeholder={tx('No template')} noneLabel={tx('No template')} />
          </Field>
          <Field label={tx('To')}><Input type="email" value={to} onChange={(e) => setTo(e.target.value)} /></Field>
          <Field label={tx('Subject')}><Input value={subject} maxLength={200} onChange={(e) => setSubject(e.target.value)} /></Field>
          <Field label={tx('Message')}><Textarea rows={10} value={body} onChange={(e) => setBody(e.target.value)} /></Field>
          <p className="text-xs text-muted-foreground">{tx('Sent as plain text from the company address and logged in the history.')}</p>
        </div>
        <DialogFooter>
          {active.length === 0 && <Button variant="ghost" onClick={() => navigate('/crm/settings')}>{tx('Templates')}</Button>}
          <Button variant="outline" onClick={() => onOpenChange(false)}>{tx('Cancel')}</Button>
          <Button disabled={!to || !subject.trim() || !body.trim() || send.isPending} onClick={() => send.mutate(undefined, {
            onSuccess: (r) => { toast.success(r.data.delivered ? `${tx('Sent to')} ${r.data.sentTo}` : `${tx('Queued for')} ${r.data.sentTo}`); onOpenChange(false); },
            onError: err(tx('Could not send')),
          })}><Send className="mr-1.5 h-4 w-4" />{tx('Send')}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

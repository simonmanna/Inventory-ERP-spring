import { useEffect, useMemo, useState, type ReactNode } from 'react';
import { Link, useNavigate, useParams, useSearchParams } from 'react-router-dom';
import { toast } from 'sonner';
import { AlertTriangle, CheckCircle2, PackageCheck, PackageX, Plus, Printer, Sparkles, Trash2 } from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Textarea } from '@/components/ui/textarea';
import { Skeleton } from '@/components/ui/skeleton';
import { SearchableSelect } from '@/components/ui/searchable-select';
import { Dialog, DialogContent, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { OptionalSelect } from '@/pages/fleet/fleet-ui';
import { useAuthStore } from '@/stores/auth.store';
import { apiErrorMessage } from '@/lib/api-error';
import { date, money } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';
import { cn } from '@/lib/utils';
import {
  crmApi, useCrmLookups, useCrmMutation, useOpportunity, useProductOptions, useQuotation, useTaxOptions,
  type QuoteLine, type Quotation,
} from '@/features/crm/suite-api';
import { Field, QuoteStatusPill } from './crm-ui';
import { PartnerPicker } from './crm-dialogs';

const blankLine = (): QuoteLine => ({ productId: null, description: '', quantity: 1, unitPrice: 0, discountPercent: 0, taxId: null });
const SOURCE_LABEL: Record<string, string> = { list: tx('List price'), rule: tx('Price rule'), customer: tx('Customer discount'), manual: tx('Manual') };

/** Create / edit / process a quotation (ADR-S010). Editable while draft or rejected. */
export function QuotationEditorPage() {
  const { id } = useParams();
  const [params] = useSearchParams();
  const isNew = !id || id === 'new';
  const navigate = useNavigate();
  const { data: q, isLoading } = useQuotation(isNew ? undefined : id);
  const dealId = isNew ? params.get('dealId') ?? undefined : q?.dealId ?? undefined;
  const { data: deal } = useOpportunity(isNew ? dealId : undefined);
  const { data: lk } = useCrmLookups();
  const products = useProductOptions();
  const taxes = useTaxOptions();
  const canWrite = useAuthStore((s) => s.hasPermission(PERMISSIONS.crm.quotationWrite));
  const canConvert = useAuthStore((s) => s.hasPermission(PERMISSIONS.crm.quotationConvert));
  const canPrice = useAuthStore((s) => s.hasPermission(PERMISSIONS.crm.pricingRead));

  const [partnerId, setPartnerId] = useState('');
  const [partnerName, setPartnerName] = useState<string | null>(null);
  const [issueDate, setIssueDate] = useState(new Date().toISOString().slice(0, 10));
  const [validUntil, setValidUntil] = useState('');
  const [ownerId, setOwnerId] = useState('');
  const [notes, setNotes] = useState('');
  const [terms, setTerms] = useState('');
  const [lines, setLines] = useState<QuoteLine[]>([blankLine()]);
  const [dirty, setDirty] = useState(false);
  const [dialog, setDialog] = useState<'reject' | 'decline' | 'convert' | null>(null);
  const [reason, setReason] = useState('');
  const [target, setTarget] = useState<'order' | 'proforma'>('order');
  const [backorder, setBackorder] = useState(false);
  const [shortages, setShortages] = useState<string[] | null>(null);

  useEffect(() => {
    if (q) {
      setPartnerId(q.partnerId); setPartnerName(q.partnerName); setIssueDate(q.issueDate?.slice(0, 10) ?? '');
      setValidUntil(q.validUntil?.slice(0, 10) ?? ''); setOwnerId(q.ownerId ?? ''); setNotes(q.notes ?? ''); setTerms(q.terms ?? '');
      setLines(q.lines?.length ? q.lines.map((l) => ({ ...l })) : [blankLine()]); setDirty(false);
    }
  }, [q]);
  useEffect(() => {
    if (isNew && deal?.partner) { setPartnerId(deal.partner.id); setPartnerName(deal.partner.name); }
    else if (isNew && params.get('partnerId')) { setPartnerId(params.get('partnerId')!); setPartnerName(params.get('partnerName')); }
  }, [isNew, deal, params]);
  useEffect(() => { if (isNew && lk?.settings.defaultQuoteTerms) setTerms((t) => t || lk.settings.defaultQuoteTerms || ''); }, [isNew, lk]);

  const editable = isNew || (!!q && ['draft', 'rejected'].includes(q.status) && canWrite);
  const productOpts = useMemo(() => (products.data ?? []).map((p) => ({ value: p.id, label: p.name, code: p.code ?? undefined })), [products.data]);
  const taxRate = (taxId: string | null) => Number(taxes.data?.find((t) => t.id === taxId)?.rate ?? 0);
  const est = useMemo(() => {
    let sub = 0; let tax = 0;
    for (const l of lines) {
      const s = Number(l.quantity || 0) * Number(l.unitPrice || 0) * (1 - Number(l.discountPercent || 0) / 100);
      sub += s; tax += s * taxRate(l.taxId) / 100;
    }
    return { sub, tax, total: sub + tax };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [lines, taxes.data]);

  const patchLine = (i: number, p: Partial<QuoteLine>) => { setLines((ls) => ls.map((l, j) => (j === i ? { ...l, ...p, priceSource: p.priceSource ?? (('unitPrice' in p || 'discountPercent' in p) ? 'manual' : l.priceSource) } : l))); setDirty(true); };

  const priceLines = async (idx?: number, current: QuoteLine[] = lines) => {
    if (!canPrice) return;
    const targets = current.map((l, i) => ({ l, i })).filter(({ l, i }) => l.productId && (idx === undefined || i === idx));
    if (!targets.length) return;
    try {
      const res = await crmApi.resolvePrices({ partnerId: partnerId || undefined, lines: targets.map(({ l }) => ({ productId: l.productId!, quantity: Number(l.quantity || 1) })) });
      setLines((ls) => ls.map((l, i) => {
        const k = targets.findIndex((t) => t.i === i);
        if (k < 0) return l;
        const r = res.lines[k];
        return { ...l, listPrice: r.listPrice, unitPrice: r.unitPrice, discountPercent: r.discountPercent, priceSource: r.source, taxId: l.taxId ?? r.taxId, description: l.description || r.description };
      }));
      setDirty(true);
      if (idx === undefined) toast.success(tx('Customer prices applied'));
    } catch (e) { toast.error(apiErrorMessage(e, tx('Could not price the lines'))); }
  };

  const save = useCrmMutation(async () => {
    const body = {
      partnerId, issueDate, validUntil: validUntil || undefined, ownerId: ownerId || undefined, notes, terms,
      lines: lines.filter((l) => l.productId || l.description).map((l) => ({
        productId: l.productId || undefined, description: l.description || undefined, quantity: Number(l.quantity),
        unitPrice: l.priceSource === 'manual' || !l.productId ? Number(l.unitPrice) : undefined,
        discountPercent: l.priceSource === 'manual' || !l.productId ? Number(l.discountPercent) : undefined,
        taxId: l.taxId || null,
      })),
    };
    if (isNew) return crmApi.createQuote({ ...body, dealId });
    return crmApi.updateQuote({ id: q!.id, ...body });
  });
  const action = useCrmMutation(crmApi.quoteAction);
  const convert = useCrmMutation(crmApi.convertQuote);
  const del = useCrmMutation(crmApi.deleteQuote);

  const doSave = (then?: (saved: Quotation) => void) => {
    if (!partnerId) return toast.error(tx('Choose a customer'));
    save.mutate(undefined, {
      onSuccess: (saved) => { setDirty(false); if (isNew) navigate(`/crm/quotations/${saved.id}`, { replace: true }); else toast.success(tx('Saved')); then?.(saved); },
      onError: (e) => toast.error(apiErrorMessage(e, tx('Could not save'))),
    });
  };
  const run = (a: Parameters<typeof crmApi.quoteAction>[0]['action'], body?: Record<string, unknown>, msg?: string) =>
    action.mutate({ id: q!.id, action: a, body }, {
      onSuccess: (r) => toast.success(msg ?? (a === 'submit' && r.status === 'approved' ? tx('Approved automatically — within policy') : tx('Done'))),
      onError: (e) => toast.error(apiErrorMessage(e, tx('Action failed'))),
    });

  if (!isNew && isLoading) return <Skeleton className="h-96 w-full" />;
  if (!isNew && !q) return <p className="text-sm text-muted-foreground">{tx('Quotation not found.')}</p>;
  const status = q?.status ?? 'draft';
  const currency = q?.currencyCode ?? deal?.currencyCode;

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div className="text-sm">
          <Link to="/crm/quotations" className="font-medium text-primary hover:underline">{tx('Quotations')}</Link>
          <span className="mx-1.5 text-muted-foreground">/</span>
          <span className="font-semibold">{q ? q.number : tx('New')}</span>
          {q && q.revision > 0 && <span className="ml-1 text-xs text-muted-foreground">r{q.revision}</span>}
          {q && <span className="ml-3"><QuoteStatusPill status={status} /></span>}
        </div>
        {(q?.dealId || dealId) && (
          <Link to={`/crm/opportunities/${q?.dealId ?? dealId}`} className="text-sm text-primary hover:underline">← {q?.dealName ?? deal?.name ?? tx('Opportunity')}</Link>
        )}
      </div>

      <div className="flex flex-wrap gap-2">
        {editable && <Button onClick={() => doSave()} disabled={save.isPending}>{tx('Save')}</Button>}
        {q && editable && canWrite && <Button variant="secondary" disabled={action.isPending || save.isPending} onClick={() => dirty ? doSave(() => run('submit')) : run('submit')}>{tx('Submit for approval')}</Button>}
        {q?.status === 'pending_approval' && q.canApprove && <>
          <Button onClick={() => run('approve', {}, tx('Approved'))}><CheckCircle2 className="mr-1.5 h-4 w-4" />{tx('Approve')}</Button>
          <Button variant="destructive" onClick={() => { setReason(''); setDialog('reject'); }}>{tx('Reject')}</Button>
        </>}
        {q && ['approved', 'sent'].includes(status) && canWrite && <>
          {status === 'approved' && <Button onClick={() => run('send', undefined, tx('Marked as sent'))}>{tx('Mark as sent')}</Button>}
          <Button variant="secondary" onClick={() => run('accept', undefined, tx('Accepted by customer'))}>{tx('Customer accepted')}</Button>
          <Button variant="outline" onClick={() => { setReason(''); setDialog('decline'); }}>{tx('Customer declined')}</Button>
        </>}
        {q && ['approved', 'sent', 'accepted'].includes(status) && canConvert && (
          <Button onClick={() => { setShortages(null); setBackorder(false); setDialog('convert'); }}><PackageCheck className="mr-1.5 h-4 w-4" />{tx('Convert')}</Button>
        )}
        {q && !['converted', 'draft'].includes(status) && canWrite && <Button variant="outline" onClick={() => run('revise', undefined, tx('New revision in draft'))}>{tx('Revise')}</Button>}
        {q && !['converted', 'cancelled'].includes(status) && canWrite && <Button variant="ghost" onClick={() => run('cancel', undefined, tx('Cancelled'))}>{tx('Cancel quotation')}</Button>}
        {q && <Button variant="outline" onClick={() => printQuote(q, lk?.users.find((u) => u.id === q.ownerId)?.name)}><Printer className="mr-1.5 h-4 w-4" />{tx('Print')}</Button>}
        {q?.status === 'draft' && canWrite && (
          <Button variant="ghost" className="text-destructive" onClick={() => { if (confirm(tx('Delete this draft?'))) del.mutate(q.id, { onSuccess: () => navigate('/crm/quotations'), onError: (e) => toast.error(apiErrorMessage(e, tx('Could not delete'))) }); }}><Trash2 className="h-4 w-4" /></Button>
        )}
      </div>

      {q?.status === 'pending_approval' && (
        <Banner tone="warning" title={tx('Waiting for approval')} items={q.approvalReasons} />
      )}
      {q?.status === 'rejected' && <Banner tone="danger" title={tx('Rejected')} items={[q.rejectionReason ?? '']} />}
      {q?.status === 'converted' && (
        <Banner tone="success" title={q.convertedType === 'order' ? tx('Converted to order') : tx('Converted to proforma invoice')}
          items={[q.convertedNumber ?? '']} link={q.convertedType === 'order' ? `/orders/${q.convertedId}` : `/invoices/${q.convertedId}`} />
      )}

      <div className="grid gap-4 lg:grid-cols-[minmax(0,1fr)_320px]">
        <div className="space-y-4 rounded-xl border bg-card p-4 shadow-sm">
          <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
            <Field label={tx('Customer')} className="sm:col-span-2">
              {editable ? <PartnerPicker value={partnerId} label={partnerName} onPick={(i, n) => { setPartnerId(i); setPartnerName(n || null); setDirty(true); }} /> : <p className="py-2 text-sm font-medium">{q?.partnerName}</p>}
            </Field>
            <Field label={tx('Quotation date')}><Input type="date" disabled={!editable} value={issueDate} onChange={(e) => { setIssueDate(e.target.value); setDirty(true); }} /></Field>
            <Field label={tx('Valid until')}><Input type="date" disabled={!editable} value={validUntil} placeholder={tx('Default validity')} onChange={(e) => { setValidUntil(e.target.value); setDirty(true); }} /></Field>
            <Field label={tx('Salesperson')} className="sm:col-span-2">
              <OptionalSelect disabled={!editable} value={ownerId} onChange={(v) => { setOwnerId(v); setDirty(true); }} options={(lk?.users ?? []).map((u) => ({ value: u.id, label: u.name }))} placeholder={tx('Opportunity owner / me')} noneLabel={tx('Opportunity owner / me')} />
            </Field>
          </div>

          <div className="overflow-x-auto">
            <table className="w-full min-w-[900px] text-sm">
              <thead className="border-b text-left text-xs font-semibold uppercase tracking-wide text-muted-foreground">
                <tr><th className="py-2 pr-2">{tx('Product')}</th><th className="py-2 pr-2">{tx('Description')}</th><th className="w-20 py-2 pr-2 text-right">{tx('Qty')}</th>
                  <th className="w-28 py-2 pr-2 text-right">{tx('Unit price')}</th><th className="w-20 py-2 pr-2 text-right">{tx('Disc. %')}</th><th className="w-36 py-2 pr-2">{tx('Tax')}</th>
                  <th className="w-28 py-2 pr-2 text-right">{tx('Subtotal')}</th><th className="w-8" /></tr>
              </thead>
              <tbody>
                {lines.map((l, i) => {
                  const sub = Number(l.quantity || 0) * Number(l.unitPrice || 0) * (1 - Number(l.discountPercent || 0) / 100);
                  return (
                    <tr key={i} className="border-b align-top">
                      <td className="py-1.5 pr-2">
                        {editable
                          ? <SearchableSelect value={l.productId ?? ''} onValueChange={(v) => {
                              const next = lines.map((x, j) => (j === i ? { ...x, productId: v || null, priceSource: undefined, description: '' } : x));
                              setLines(next); setDirty(true); void priceLines(i, next);
                            }} options={productOpts} placeholder={tx('Product…')} className="w-56" />
                          : <span className="font-medium">{l.productName ?? '—'}</span>}
                        <div className="mt-1 flex flex-wrap items-center gap-1.5 text-[11px]">
                          {l.priceSource && <span className={cn('rounded px-1.5 py-0.5', l.priceSource === 'manual' ? 'bg-amber-100 text-amber-800' : 'bg-muted text-muted-foreground')}>{SOURCE_LABEL[l.priceSource]}</span>}
                          {l.listPrice != null && Number(l.listPrice) !== Number(l.unitPrice) && <span className="text-muted-foreground">{tx('List')} {money(l.listPrice, currency)}</span>}
                          {l.stock && (l.stock.sufficient
                            ? <span className="inline-flex items-center gap-0.5 text-emerald-700"><PackageCheck className="h-3 w-3" />{l.stock.available} {tx('available')}</span>
                            : <span className="inline-flex items-center gap-0.5 text-rose-700"><PackageX className="h-3 w-3" />{tx('Only')} {l.stock.available} {tx('available')}</span>)}
                        </div>
                      </td>
                      <td className="py-1.5 pr-2"><Input disabled={!editable} value={l.description} onChange={(e) => patchLine(i, { description: e.target.value, priceSource: l.priceSource })} /></td>
                      <td className="py-1.5 pr-2"><Input disabled={!editable} type="number" min={0} className="text-right" value={l.quantity} onChange={(e) => patchLine(i, { quantity: Number(e.target.value), priceSource: l.priceSource })} onBlur={() => l.productId && l.priceSource !== 'manual' && priceLines(i)} /></td>
                      <td className="py-1.5 pr-2"><Input disabled={!editable} type="number" min={0} className="text-right" value={l.unitPrice} onChange={(e) => patchLine(i, { unitPrice: Number(e.target.value) })} /></td>
                      <td className="py-1.5 pr-2"><Input disabled={!editable} type="number" min={0} max={100} className="text-right" value={l.discountPercent} onChange={(e) => patchLine(i, { discountPercent: Number(e.target.value) })} /></td>
                      <td className="py-1.5 pr-2">
                        <OptionalSelect disabled={!editable} value={l.taxId ?? ''} onChange={(v) => patchLine(i, { taxId: v || null, priceSource: l.priceSource })} options={(taxes.data ?? []).map((t) => ({ value: t.id, label: `${t.name} (${Number(t.rate)}%)` }))} placeholder={tx('No tax')} noneLabel={tx('No tax')} />
                      </td>
                      <td className="py-1.5 pr-2 pt-3.5 text-right tabular-nums">{money(l.lineSubtotal ?? sub, currency)}</td>
                      <td className="py-1.5 pt-2.5">{editable && <button className="rounded p-1 text-muted-foreground hover:bg-muted" onClick={() => { setLines((ls) => ls.filter((_, j) => j !== i)); setDirty(true); }}><Trash2 className="h-4 w-4" /></button>}</td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
          {editable && (
            <div className="flex flex-wrap gap-2">
              <Button variant="outline" size="sm" onClick={() => { setLines((ls) => [...ls, blankLine()]); setDirty(true); }}><Plus className="mr-1 h-4 w-4" />{tx('Add a line')}</Button>
              {canPrice && <Button variant="ghost" size="sm" onClick={() => priceLines()}><Sparkles className="mr-1 h-4 w-4" />{tx('Apply customer prices')}</Button>}
            </div>
          )}
          <div className="grid gap-3 sm:grid-cols-2">
            <Field label={tx('Notes')}><Textarea disabled={!editable} rows={3} value={notes} onChange={(e) => { setNotes(e.target.value); setDirty(true); }} /></Field>
            <Field label={tx('Terms & conditions')}><Textarea disabled={!editable} rows={3} value={terms} onChange={(e) => { setTerms(e.target.value); setDirty(true); }} /></Field>
          </div>
        </div>

        <div className="space-y-4">
          <div className="rounded-xl border bg-card p-4 text-sm shadow-sm">
            <h2 className="mb-2 font-semibold">{tx('Totals')}{dirty && <span className="ml-2 text-xs font-normal text-amber-600">{tx('(estimate — save to recalculate)')}</span>}</h2>
            <Row label={tx('Untaxed amount')} value={money(dirty || !q ? est.sub : q.subtotal, currency)} />
            {q && !dirty && Number(q.discountTotal) > 0 && <Row label={tx('Discount')} value={'− ' + money(q.discountTotal, currency)} />}
            <Row label={tx('Taxes')} value={money(dirty || !q ? est.tax : q.taxAmount, currency)} />
            <div className="mt-2 border-t pt-2"><Row label={tx('Total')} value={<strong className="text-lg">{money(dirty || !q ? est.total : q.total, currency)}</strong>} /></div>
            {q && Number(q.maxDiscountPercent) > 0 && <p className="mt-2 text-xs text-muted-foreground">{tx('Highest discount vs list')}: {Number(q.maxDiscountPercent).toFixed(1)}% · {tx('limit')} {lk?.settings.quoteMaxDiscountPercent}%</p>}
          </div>
          {q?.credit && (
            <div className={cn('rounded-xl border bg-card p-4 text-sm shadow-sm', (q.credit.creditHold || q.credit.exceeded) && 'border-rose-300')}>
              <h2 className="mb-2 font-semibold">{tx('Customer credit')}</h2>
              {q.credit.creditHold && <p className="mb-2 flex items-center gap-1.5 text-rose-700"><AlertTriangle className="h-4 w-4" />{tx('On credit hold')}</p>}
              <Row label={tx('Credit limit')} value={Number(q.credit.creditLimit) > 0 ? money(q.credit.creditLimit, currency) : tx('None')} />
              <Row label={tx('Open receivables')} value={money(q.credit.openReceivables, currency)} />
              <Row label={tx('Overdue')} value={<span className={Number(q.credit.overdueReceivables) > 0 ? 'text-rose-600' : ''}>{money(q.credit.overdueReceivables, currency)}</span>} />
              {q.credit.headroom != null && <Row label={tx('Headroom')} value={money(q.credit.headroom, currency)} />}
              <Link to={`/crm/customers/${q.partnerId}`} className="mt-2 inline-block text-xs text-primary hover:underline">{tx('Customer 360')} →</Link>
            </div>
          )}
          {q && (
            <div className="rounded-xl border bg-card p-4 text-xs text-muted-foreground shadow-sm">
              <p>{tx('Created')} {date(q.createdAt)}</p>
              {q.approvedAt && <p>{tx('Approved')} {date(q.approvedAt)}</p>}
              {q.sentAt && <p>{tx('Sent')} {date(q.sentAt)}</p>}
              {q.acceptedAt && <p>{tx('Accepted')} {date(q.acceptedAt)}</p>}
            </div>
          )}
        </div>
      </div>

      <Dialog open={dialog === 'reject' || dialog === 'decline'} onOpenChange={(v) => !v && setDialog(null)}>
        <DialogContent className="max-w-md">
          <DialogHeader><DialogTitle>{dialog === 'reject' ? tx('Reject quotation') : tx('Customer declined')}</DialogTitle></DialogHeader>
          <Field label={tx('Reason')}><Textarea rows={3} value={reason} onChange={(e) => setReason(e.target.value)} /></Field>
          <DialogFooter>
            <Button variant="outline" onClick={() => setDialog(null)}>{tx('Cancel')}</Button>
            <Button variant="destructive" onClick={() => { run(dialog === 'reject' ? 'reject' : 'decline', { reason }, tx('Recorded')); setDialog(null); }}>{tx('Confirm')}</Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      <Dialog open={dialog === 'convert'} onOpenChange={(v) => !v && setDialog(null)}>
        <DialogContent className="max-w-md">
          <DialogHeader><DialogTitle>{tx('Convert quotation')}</DialogTitle></DialogHeader>
          <div className="space-y-2 text-sm">
            <label className="flex items-start gap-2 rounded-lg border p-3"><input type="radio" className="mt-1" checked={target === 'order'} onChange={() => setTarget('order')} />
              <span><strong>{tx('Sales order')}</strong><span className="block text-xs text-muted-foreground">{tx('Creates an order; stock is checked and the order is invoiced from Orders.')}</span></span></label>
            <label className="flex items-start gap-2 rounded-lg border p-3"><input type="radio" className="mt-1" checked={target === 'proforma'} onChange={() => setTarget('proforma')} />
              <span><strong>{tx('Proforma invoice')}</strong><span className="block text-xs text-muted-foreground">{tx('Creates a draft proforma invoice for advance payment.')}</span></span></label>
            {shortages && (
              <div className="rounded-lg border border-rose-200 bg-rose-50 p-3 text-rose-800">
                <p className="font-medium">{tx('Not enough stock for')}: {shortages.join(', ')}</p>
                <label className="mt-2 flex items-center gap-2"><input type="checkbox" checked={backorder} onChange={(e) => setBackorder(e.target.checked)} />{tx('Create the order anyway (back-order)')}</label>
              </div>
            )}
            {lk?.settings.autoWinOnConversion && q?.dealId && <p className="text-xs text-muted-foreground">{tx('The opportunity will be marked as won.')}</p>}
          </div>
          <DialogFooter>
            <Button variant="outline" onClick={() => setDialog(null)}>{tx('Cancel')}</Button>
            <Button disabled={convert.isPending} onClick={() => convert.mutate({ id: q!.id, target, allowBackorder: backorder }, {
              onSuccess: (r) => { toast.success(tx('Created') + ' ' + r.converted.number); setDialog(null); },
              onError: (e) => {
                const data = (e as { response?: { status?: number; data?: { shortages?: string[] } } }).response;
                if (data?.status === 409 && data.data?.shortages) setShortages(data.data.shortages);
                else toast.error(apiErrorMessage(e, tx('Could not convert')));
              },
            })}>{tx('Convert')}</Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  );
}

function Row({ label, value }: { label: string; value: ReactNode }) {
  return <div className="flex items-center justify-between py-0.5"><span className="text-muted-foreground">{label}</span><span className="tabular-nums">{value}</span></div>;
}

function Banner({ tone, title, items, link }: { tone: 'warning' | 'danger' | 'success'; title: string; items: string[]; link?: string }) {
  const cls = tone === 'warning' ? 'border-amber-200 bg-amber-50 text-amber-900' : tone === 'danger' ? 'border-rose-200 bg-rose-50 text-rose-900' : 'border-emerald-200 bg-emerald-50 text-emerald-900';
  return (
    <div className={cn('rounded-xl border p-3 text-sm dark:bg-transparent', cls)}>
      <p className="font-semibold">{title}</p>
      <ul className="ml-4 list-disc">{items.filter(Boolean).map((i) => <li key={i}>{link ? <Link className="underline" to={link}>{i}</Link> : i}</li>)}</ul>
    </div>
  );
}

const esc = (s: unknown) => String(s ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]!));

/** Printable quotation in a new window. */
function printQuote(q: Quotation, owner?: string) {
  const w = window.open('', '_blank', 'width=900,height=1000');
  if (!w) return;
  const m = (v: number) => money(v, q.currencyCode);
  const rows = (q.lines ?? []).map((l) => `<tr><td>${esc(l.description)}</td><td class="r">${esc(l.quantity)}</td><td class="r">${m(l.unitPrice)}</td><td class="r">${Number(l.discountPercent) ? esc(l.discountPercent) + '%' : ''}</td><td class="r">${m(l.lineTotal ?? 0)}</td></tr>`).join('');
  w.document.write(`<!doctype html><html><head><meta charset="utf-8"><title>${esc(q.number)}</title><style>
    body{font-family:system-ui,sans-serif;color:#111;margin:40px}h1{margin:0 0 4px}table{width:100%;border-collapse:collapse;margin-top:24px}
    th,td{padding:8px;border-bottom:1px solid #ddd;text-align:left;font-size:13px}.r{text-align:right}.tot td{border:0}.muted{color:#666;font-size:13px}
    .grid{display:flex;justify-content:space-between;margin-top:16px}</style></head><body>
    <h1>${esc(tx('Quotation'))} ${esc(q.number)}</h1><div class="muted">${esc(tx('Date'))}: ${esc(date(q.issueDate))} · ${esc(tx('Valid until'))}: ${esc(q.validUntil ? date(q.validUntil) : '—')}</div>
    <div class="grid"><div><strong>${esc(q.partnerName)}</strong></div><div class="muted">${esc(tx('Salesperson'))}: ${esc(owner ?? q.ownerName ?? '')}</div></div>
    <table><thead><tr><th>${esc(tx('Description'))}</th><th class="r">${esc(tx('Qty'))}</th><th class="r">${esc(tx('Unit price'))}</th><th class="r">${esc(tx('Disc.'))}</th><th class="r">${esc(tx('Amount'))}</th></tr></thead>
    <tbody>${rows}</tbody><tbody class="tot"><tr><td colspan="4" class="r">${esc(tx('Untaxed amount'))}</td><td class="r">${m(q.subtotal)}</td></tr>
    <tr><td colspan="4" class="r">${esc(tx('Taxes'))}</td><td class="r">${m(q.taxAmount)}</td></tr><tr><td colspan="4" class="r"><strong>${esc(tx('Total'))}</strong></td><td class="r"><strong>${m(q.total)}</strong></td></tr></tbody></table>
    ${q.notes ? `<p class="muted">${esc(q.notes)}</p>` : ''}${q.terms ? `<h4>${esc(tx('Terms & conditions'))}</h4><p class="muted" style="white-space:pre-wrap">${esc(q.terms)}</p>` : ''}
    <script>window.onload=()=>window.print()</script></body></html>`);
  w.document.close();
}

import { Fragment, useEffect, useMemo, useState, type ReactNode } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { toast } from 'sonner';
import { useQuery } from '@tanstack/react-query';
import { ChevronDown, Copy, FileSignature, FileText, History, Pause, Play, Plus, Receipt, Scissors, Trash2, Truck, Undo2, XCircle } from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Textarea } from '@/components/ui/textarea';
import { Skeleton } from '@/components/ui/skeleton';
import { SearchableSelect } from '@/components/ui/searchable-select';
import { VariantSelect } from '@/features/products/variant-select';
import { Dialog, DialogContent, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuSeparator, DropdownMenuTrigger } from '@/components/ui/dropdown-menu';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs';
import { OptionalSelect } from '@/pages/fleet/fleet-ui';
import { useAuthStore } from '@/stores/auth.store';
import { api } from '@/lib/api';
import { apiErrorMessage } from '@/lib/api-error';
import { date, money } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';
import { cn } from '@/lib/utils';
import { useProductOptions, useTaxOptions } from '@/features/crm/suite-api';
import {
  salesApi, useChangeRequests, useOrderRevisions, useSalesMutation, useSalesOrder, type ChangeRequest, type SalesOrderLine,
} from '@/features/sales/api';
import { Field } from '@/pages/crm/crm-ui';
import { PartnerPicker } from '@/pages/crm/crm-dialogs';
import { DELIVERY_LABEL, INVOICE_LABEL, InlineField, OrderStatusPill, SmartButton, StatusRibbon } from './sales-ui';
import { CreditPanel } from './CreditPage';
import { AvailabilityPanel } from './AvailabilityPanel';
import { FulfilmentPanel } from './FulfilmentPanel';
import { HandoverTab } from './HandoverTab';
import { HandoverDraftPanel } from './HandoverDetails';
import { emptyHandover, saveHandoverDraft, type HandoverForm } from './handover-draft';
import { BillingPanel } from './BillingPanel';
import { NewReturnDialog } from './ReturnsPages';
import { AttachmentPanel } from '@/components/dms/AttachmentPanel';
import { MilestonesPanel, NewDeliveryDialog } from './DeliveriesPages';
import { blankHeader, OrderHeaderFields, type OrderHeader } from './OrderHeaderFields';

const blankLine = (): SalesOrderLine => ({ productId: null, description: '', quantity: 1, unitPrice: 0, discountPercent: 0, taxId: null });
const n = (v: unknown) => Number(v ?? 0);

/** Create / edit (draft) / process a sales order (ADR-S011). Confirmed orders change through change requests. */
export function SalesOrderPage() {
  const { id } = useParams();
  const isNew = !id || id === 'new';
  const navigate = useNavigate();
  const { data: o, isLoading } = useSalesOrder(isNew ? undefined : id);
  const products = useProductOptions();
  const taxes = useTaxOptions();
  const has = useAuthStore((s) => s.hasPermission);
  const P = PERMISSIONS.sales;

  const [partnerId, setPartnerId] = useState('');
  const [partnerName, setPartnerName] = useState<string | null>(null);
  const [customerRef, setCustomerRef] = useState('');
  const [agreementId, setAgreementId] = useState('');
  const [requestedDate, setRequestedDate] = useState('');
  const [notes, setNotes] = useState('');
  const [header, setHeader] = useState<OrderHeader>(blankHeader);
  const [orderDiscount, setOrderDiscount] = useState(0);
  const [handover, setHandover] = useState<HandoverForm>(() => emptyHandover('delivery'));
  const [lines, setLines] = useState<SalesOrderLine[]>([blankLine()]);
  const [dirty, setDirty] = useState(false);
  const [dialog, setDialog] = useState<'hold' | 'cancel' | 'change' | 'close' | 'revisions' | 'return' | 'delivery' | null>(null);
  const [reason, setReason] = useState('');
  const [closeLine, setCloseLine] = useState<string | null>(null);
  const [tab, setTab] = useState('lines');

  useEffect(() => {
    if (o) {
      setPartnerId(o.partnerId); setPartnerName(o.partnerName); setCustomerRef(o.customerRef ?? ''); setAgreementId(o.agreementId ?? '');
      setRequestedDate(o.requestedDate?.slice(0, 10) ?? '');
      setOrderDiscount(Number(o.orderDiscountPercent ?? 0));
      setHeader({ branchId: o.branchId ?? '', locationId: o.locationId ?? '', paymentTermId: o.paymentTermId ?? '', currencyCode: o.currencyCode ?? '', exchangeRate: Number(o.exchangeRate ?? 1), priority: Number(o.priority ?? 0), paymentMode: o.paymentMode ?? 'credit' });
      setNotes(o.notes ?? ''); setLines(o.lines?.length ? o.lines.map((l) => ({ ...l })) : [blankLine()]); setDirty(false);
    }
  }, [o]);

  const editable = isNew || (o?.status === 'draft' && has(P.orderWrite));
  const agreements = useQuery({
    queryKey: ['sales', 'agreements', 'active', partnerId],
    queryFn: async () => (await api.get<Array<{ id: string; number: string; name: string }>>('/sales/agreements', { params: { partnerId, status: 'active' } })).data,
    enabled: !!partnerId && editable && has(P.agreementRead), retry: false,
  });
  const productOpts = useMemo(() => (products.data ?? []).map((p) => ({ value: p.id, label: p.name, code: p.code ?? undefined })), [products.data]);
  const patchLine = (i: number, p: Partial<SalesOrderLine>) => { setLines((ls) => ls.map((l, j) => (j === i ? { ...l, ...p } : l))); setDirty(true); };

  const save = useSalesMutation(async () => {
    const body = {
      partnerId, customerRef, requestedDate: requestedDate || undefined, notes,
      branchId: header.branchId || null, locationId: header.locationId || null, paymentTermId: header.paymentTermId || null,
      orderDiscountPercent: orderDiscount || null, paymentMode: header.paymentMode,
      ...(header.currencyCode ? { currencyCode: header.currencyCode, exchangeRate: header.exchangeRate || 1 } : {}), priority: header.priority,
      ...(isNew ? { deliveryMethod: handover.method === 'pickup' ? 'collect' : 'delivery', deliveryAddress: handover.method === 'delivery' ? handover.address : undefined } : {}),
      agreementId: agreementId || (isNew ? undefined : null),
      lines: lines.filter((l) => l.productId || l.description).map((l) => ({
        productId: l.productId || undefined, variantId: l.variantId || undefined, description: l.description || undefined, quantity: n(l.quantity),
        unitPrice: (l.id && l.priceSource !== undefined) || l.priceSource === 'manual' || !l.productId ? n(l.unitPrice) : undefined,
        discountPercent: n(l.discountPercent), taxId: l.taxId || null, promisedDate: l.promisedDate || undefined,
      })),
    };
    return isNew ? salesApi.createOrder(body) : salesApi.updateOrder({ id: o!.id, ...body });
  });
  const action = useSalesMutation(salesApi.orderAction);
  const close = useSalesMutation(salesApi.closeShort);
  const del = useSalesMutation(salesApi.deleteOrder);

  const doSave = (then?: () => void) => {
    if (!partnerId) return toast.error(tx('Choose a customer'));
    save.mutate(undefined, {
      onSuccess: async (s) => {
        setDirty(false);
        if (isNew) {
          try { await saveHandoverDraft(`/sales/orders/${s.id}/handover`, handover); } catch (e) { toast.error(apiErrorMessage(e, tx('Order saved, but the delivery details could not be saved'))); }
          navigate(`/sales/orders/${s.id}`, { replace: true });
        } else toast.success(tx('Saved'));
        then?.();
      },
      onError: (e) => toast.error(apiErrorMessage(e, tx('Could not save'))),
    });
  };
  const run = (a: 'confirm' | 'hold' | 'release' | 'cancel' | 'duplicate' | 'approve' | 'reject', body?: Record<string, unknown>) =>
    action.mutate({ id: o!.id, action: a, body }, {
      onSuccess: (r) => { if (a === 'duplicate') navigate(`/sales/orders/${r.id}`); else toast.success(r.status === 'pending_approval' ? tx('Sent for approval') : a === 'confirm' && r.onHold ? tx('Confirmed — on hold: ') + (r.holdReason ?? '') : tx('Done')); },
      onError: (e) => toast.error(apiErrorMessage(e, tx('Action failed'))),
    });

  if (!isNew && isLoading) return <Skeleton className="h-96 w-full" />;
  if (!isNew && !o) return <p className="text-sm text-muted-foreground">{tx('Sales order not found.')}</p>;
  const status = o?.status ?? 'draft';
  const open = ['confirmed', 'in_fulfilment'].includes(status);
  const ribbon = [tx('Quotation'), tx('Sales order'), tx('Delivered'), tx('Invoiced')];
  const step = !o ? 1 : o.invoiceStatus === 'invoiced' ? 3 : o.deliveryStatus === 'full' ? 2 : 1;
  const currency = o?.currencyCode;
  const est = lines.reduce((s, l) => s + n(l.quantity) * n(l.unitPrice) * (1 - n(l.discountPercent) / 100), 0);
  const rel = o?.relations;
  const fulfilling = !!o && ['confirmed', 'in_fulfilment', 'done'].includes(o.status);
  const ico = 'mr-2 h-4 w-4';
  type MenuItem = { key: string; label: string; icon: ReactNode; onSelect: () => void; danger?: boolean };
  const menu: MenuItem[] = [];
  if (o) {
    if (open && !o.onHold && has(P.orderHoldRelease)) menu.push({ key: 'hold', label: tx('Hold'), icon: <Pause className={ico} />, onSelect: () => { setReason(''); setDialog('hold'); } });
    if ((status === 'draft' || (o.onHold && o.holdKind !== 'manual')) && has(P.orderWrite)) menu.push({ key: 'credit', label: tx('Ask for credit approval'), icon: <FileSignature className={ico} />, onSelect: () => { const reason = prompt(tx('Why should this order be allowed over the limit?')); if (reason) api.post(`/sales/orders/${o.id}/credit-request`, { reason }).then(() => toast.success(tx('Credit request sent'))).catch((err) => toast.error(apiErrorMessage(err, tx('Action failed')))); } });
    if (open && !o.onHold && has(P.deliveryWrite)) menu.push({ key: 'delivery', label: tx('New delivery'), icon: <Truck className={ico} />, onSelect: () => setDialog('delivery') });
    if (open && has(P.orderAmend)) menu.push({ key: 'change', label: tx('Request change'), icon: <FileSignature className={ico} />, onSelect: () => setDialog('change') });
    if (['in_fulfilment', 'done'].includes(status) && has(P.returnWrite)) menu.push({ key: 'return', label: tx('Return / claim'), icon: <Undo2 className={ico} />, onSelect: () => setDialog('return') });
    if (status !== 'cancelled' && has(P.orderWrite)) menu.push({ key: 'proforma', label: tx('Create proforma'), icon: <FileText className={ico} />, onSelect: () => { api.post(`/sales/orders/${o.id}/proforma`, {}, { headers: { 'Idempotency-Key': crypto.randomUUID() } }).then((r) => navigate(`/sales/proformas/${r.data.id}`)).catch((err) => toast.error(apiErrorMessage(err, tx('Could not create the proforma')))); } });
    if (has(P.orderWrite)) menu.push({ key: 'duplicate', label: tx('Duplicate'), icon: <Copy className={ico} />, onSelect: () => run('duplicate') });
    if (!['cancelled', 'done'].includes(status) && has(P.orderCancel)) menu.push({ key: 'cancel', label: tx('Cancel order'), icon: <XCircle className={ico} />, danger: true, onSelect: () => { setReason(''); setDialog('cancel'); } });
    if (status === 'draft' && has(P.orderWrite)) menu.push({ key: 'delete', label: tx('Delete draft'), icon: <Trash2 className={ico} />, danger: true, onSelect: () => { if (confirm(tx('Delete this draft?'))) del.mutate(o.id, { onSuccess: () => navigate('/sales/orders'), onError: (e) => toast.error(apiErrorMessage(e, tx('Could not delete'))) }); } });
  }

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div className="text-sm">
          <Link to="/sales/orders" className="font-medium text-primary hover:underline">{tx('Sales Orders')}</Link>
          <span className="mx-1.5 text-muted-foreground">/</span>
          <span className="font-semibold">{o ? o.number : tx('New')}</span>
          {o && o.revision > 0 && <span className="ml-1 text-xs text-muted-foreground">r{o.revision}</span>}
          {o && <span className="ml-3"><OrderStatusPill status={o.status} onHold={o.onHold} /></span>}
        </div>
        {status !== 'cancelled' && <div className="w-full max-w-md"><StatusRibbon steps={ribbon} current={step} /></div>}
      </div>

      <div className="flex flex-wrap items-start justify-between gap-3">
        <div className="flex flex-wrap items-center gap-2">
          {editable && <Button onClick={() => doSave()} disabled={save.isPending}>{tx('Save')}</Button>}
          {o?.status === 'draft' && has(P.orderConfirm) && <Button variant="secondary" disabled={action.isPending} onClick={() => dirty ? doSave(() => run('confirm')) : run('confirm')}>{tx('Confirm order')}</Button>}
          {o?.status === 'pending_approval' && has(P.quotationApprove) && <>
            <Button onClick={() => run('approve', {})}>{tx('Approve')}</Button>
            <Button variant="destructive" onClick={() => { const r = prompt(tx('Reason')); if (r) run('reject', { reason: r }); }}>{tx('Reject')}</Button>
          </>}
          {o?.onHold && has(P.orderHoldRelease) && <Button variant="outline" onClick={() => run('release')}><Play className="mr-1.5 h-4 w-4" />{tx('Release')}</Button>}
          {menu.length > 0 && (
            <DropdownMenu>
              <DropdownMenuTrigger asChild>
                <Button variant="outline">{tx('Actions')}<ChevronDown className="ml-1.5 h-4 w-4" /></Button>
              </DropdownMenuTrigger>
              <DropdownMenuContent align="start" className="min-w-[200px]">
                {menu.map((m, i) => (
                  <Fragment key={m.key}>
                    {m.danger && i > 0 && !menu[i - 1].danger && <DropdownMenuSeparator />}
                    <DropdownMenuItem onSelect={m.onSelect} className={cn(m.danger && 'text-destructive focus:text-destructive')}>{m.icon}{m.label}</DropdownMenuItem>
                  </Fragment>
                ))}
              </DropdownMenuContent>
            </DropdownMenu>
          )}
        </div>
        <div className="flex flex-wrap gap-2">
          <SmartButton icon={<FileText className="h-4 w-4" />} count={rel?.quotation ? 1 : 0} label={tx('Quotation')} disabled={!rel?.quotation} onClick={() => rel?.quotation && navigate(`/sales/quotations/${rel.quotation.id}`)} />
          <SmartButton icon={<Truck className="h-4 w-4" />} count={rel?.deliveries.length ?? 0} label={tx('Deliveries')} disabled={!rel?.deliveries.length} onClick={() => rel?.deliveries.length && navigate(`/sales/deliveries?orderId=${o?.id}`)} />
          <SmartButton icon={<Receipt className="h-4 w-4" />} count={rel?.invoices.length ?? 0} label={tx('Invoices')} disabled={!rel?.invoices.length} onClick={() => rel?.invoices.length && navigate(`/invoices/${rel.invoices[0].id}`)} />
          <SmartButton icon={<FileSignature className="h-4 w-4" />} count={rel?.changeRequests ?? 0} label={tx('Change requests')} disabled={!rel?.changeRequests} onClick={() => setTab('other')} />
          <SmartButton icon={<History className="h-4 w-4" />} count={rel?.revisions ?? 0} label={tx('Revisions')} disabled={!rel?.revisions} onClick={() => setDialog('revisions')} />
        </div>
      </div>

      {o?.status === 'pending_approval' && <div className="rounded-xl border border-amber-200 bg-amber-50 p-3 text-sm text-amber-900 dark:bg-transparent"><p className="font-semibold">{tx('Waiting for approval')}</p><ul className="ml-4 list-disc">{o.approvalReasons.map((r) => <li key={r}>{r}</li>)}</ul></div>}
      {o?.status === 'draft' && o.approvalReasons?.length > 0 && <div className="rounded-xl border border-rose-200 bg-rose-50 p-3 text-sm text-rose-900 dark:bg-transparent">{o.approvalReasons.join(' · ')}</div>}
      {o?.onHold && <div className="rounded-xl border border-rose-200 bg-rose-50 p-3 text-sm text-rose-900 dark:bg-transparent">{o.holdKind === 'credit' ? tx('Credit hold') : o.holdKind === 'customer' ? tx('Customer blocked') : tx('On hold')}: {o.holdReason}{o.holdKind === 'credit' && <span className="block text-xs">{tx('Release needs the credit override permission.')}</span>}</div>}

      <section className="rounded-xl border bg-card shadow-sm">
        <div className="px-5 pt-5">
          <p className="text-xs font-medium uppercase tracking-wide text-muted-foreground">{tx('Sales order')}</p>
          <h1 className="text-2xl font-bold">{o ? o.number : tx('New')}</h1>
        </div>
        <div className="grid gap-x-10 gap-y-3 p-5 md:grid-cols-2">
          <div className="space-y-3">
            <InlineField label={tx('Customer')}>
              {editable ? <PartnerPicker value={partnerId} label={partnerName} onPick={(i, nm) => { setPartnerId(i); setPartnerName(nm || null); setDirty(true); }} /> : <span className="font-medium">{o?.partnerName}</span>}
            </InlineField>
            {o && (
              <>
                <InlineField label={tx('Source Doc')}>
                  {o.quotationId && o.quotationNumber
                    ? <Link to={`/sales/quotations/${o.quotationId}`} className="text-primary hover:underline">{o.quotationNumber}</Link>
                    : <span className="text-muted-foreground">-</span>}
                </InlineField>
                <InlineField label={tx('Salesperson')}>{o.ownerName ?? <span className="text-muted-foreground">-</span>}</InlineField>
              </>
            )}
          </div>
          <div className="space-y-3">
            <OrderHeaderFields value={header} customerRef={customerRef} disabled={!editable} onChange={(h) => { setHeader(h); setDirty(true); }} onCustomerRef={(v) => { setCustomerRef(v); setDirty(true); }} />
            {(agreementId || (agreements.data?.length ?? 0) > 0) && (
              <InlineField label={tx('Agreement')}>
                <select disabled={!editable} className="h-9 w-full rounded-md border bg-background px-2 text-sm" value={agreementId} onChange={(e) => { setAgreementId(e.target.value); setDirty(true); }}>
                  <option value="">{tx('None')}</option>
                  {agreementId && !agreements.data?.some((a) => a.id === agreementId) && <option value={agreementId}>{o?.relations?.agreement?.number ?? agreementId}</option>}
                  {(agreements.data ?? []).map((a) => <option key={a.id} value={a.id}>{a.number} · {a.name}</option>)}
                </select>
              </InlineField>
            )}
            {o && (
              <>
                <InlineField label={tx('Order Date')}>{date(o.createdAt)}</InlineField>
                {o.confirmedAt && <InlineField label={tx('Confirmed')}>{date(o.confirmedAt)}</InlineField>}
                {o.cancelledAt && <InlineField label={tx('Cancelled')}>{date(o.cancelledAt)} — {o.cancelReason}</InlineField>}
              </>
            )}
          </div>
        </div>

        <Tabs value={tab} onValueChange={setTab}>
          <TabsList className="h-auto w-full justify-start gap-1 overflow-x-auto rounded-none border-b bg-transparent p-0 px-5">
            {([['lines', tx('Order lines')], ['delivery', tx('Delivery & fulfilment')], ['billing', tx('Billing')], ['other', tx('Other info')]] as const).map(([v, label]) => (
              <TabsTrigger key={v} value={v} className="-mb-px rounded-none rounded-t-md border border-transparent px-4 py-2 data-[state=active]:border-border data-[state=active]:border-b-card data-[state=active]:bg-card data-[state=active]:shadow-none">{label}</TabsTrigger>
            ))}
          </TabsList>

          <TabsContent value="lines" className="mt-0 space-y-4 p-5">
          <div className="overflow-x-auto">
            <table className="w-full min-w-[960px] text-sm">
              <thead className="border-b text-left text-xs font-semibold uppercase tracking-wide text-muted-foreground">
                <tr><th className="py-2 pr-2">{tx('Product')}</th><th className="py-2 pr-2">{tx('Description')}</th><th className="w-20 py-2 pr-2 text-right">{tx('Qty')}</th>
                  <th className="w-28 py-2 pr-2 text-right">{tx('Unit price')}</th><th className="w-16 py-2 pr-2 text-right">{tx('Disc. %')}</th><th className="w-32 py-2 pr-2">{tx('Tax')}</th>
                  {!editable && <><th className="py-2 pr-2 text-right">{tx('Delivered')}</th><th className="py-2 pr-2 text-right">{tx('Invoiced')}</th><th className="py-2 pr-2 text-right">{tx('Remaining')}</th></>}
                  <th className="w-28 py-2 pr-2 text-right">{tx('Subtotal')}</th><th className="w-8" /></tr>
              </thead>
              <tbody>
                {lines.map((l, i) => (
                  <tr key={l.id ?? i} className={cn('border-b align-top', l.closedShort && 'text-muted-foreground')}>
                    <td className="py-1.5 pr-2">
                      {editable
                        ? <SearchableSelect value={l.productId ?? ''} onValueChange={(v) => patchLine(i, { productId: v || null, variantId: null, description: l.description || productOpts.find((p) => p.value === v)?.label || '', priceSource: undefined })} options={productOpts} placeholder={tx('Product…')} className="w-52" />
                        : <span className="font-medium">{l.productName ?? '—'}</span>}
                      <VariantSelect productId={l.productId} value={l.variantId} disabled={!editable} label={l.variantName} className="w-52"
                        onChange={(v, opt) => patchLine(i, { variantId: v, priceSource: undefined, ...(opt && (!l.description || l.description === (productOpts.find((p) => p.value === l.productId)?.label ?? '')) ? { description: opt.displayName } : {}) })} />
                      {l.closedShort && <span className="mt-1 block text-[11px]">{tx('Closed short')}: {l.closeShortReason}</span>}
                    </td>
                    <td className="py-1.5 pr-2"><Input disabled={!editable} value={l.description} onChange={(e) => patchLine(i, { description: e.target.value })} /></td>
                    <td className="py-1.5 pr-2"><Input disabled={!editable} type="number" min={0} className="text-right" value={l.quantity} onChange={(e) => patchLine(i, { quantity: Number(e.target.value) })} /></td>
                    <td className="py-1.5 pr-2"><Input disabled={!editable} type="number" min={0} className="text-right" value={l.unitPrice} onChange={(e) => patchLine(i, { unitPrice: Number(e.target.value), priceSource: 'manual' })} /></td>
                    <td className="py-1.5 pr-2"><Input disabled={!editable} type="number" min={0} max={100} className="text-right" value={l.discountPercent} onChange={(e) => patchLine(i, { discountPercent: Number(e.target.value) })} /></td>
                    <td className="py-1.5 pr-2"><OptionalSelect disabled={!editable} value={l.taxId ?? ''} onChange={(v) => patchLine(i, { taxId: v || null })} options={(taxes.data ?? []).map((t) => ({ value: t.id, label: `${t.name} (${Number(t.rate)}%)` }))} placeholder={tx('No tax')} noneLabel={tx('No tax')} /></td>
                    {!editable && <>
                      <td className="py-1.5 pr-2 pt-3 text-right tabular-nums">{n(l.qty?.delivered)}</td>
                      <td className="py-1.5 pr-2 pt-3 text-right tabular-nums">{n(l.qty?.netInvoiced)}</td>
                      <td className="py-1.5 pr-2 pt-3 text-right tabular-nums">{n(l.qty?.remaining)}</td>
                    </>}
                    <td className="py-1.5 pr-2 pt-3 text-right tabular-nums">{money(l.lineSubtotal ?? n(l.quantity) * n(l.unitPrice) * (1 - n(l.discountPercent) / 100), currency)}</td>
                    <td className="py-1.5 pt-2">
                      {editable && <button className="rounded p-1 text-muted-foreground hover:bg-muted" onClick={() => { setLines((ls) => ls.filter((_, j) => j !== i)); setDirty(true); }}><Trash2 className="h-4 w-4" /></button>}
                      {!editable && open && !l.closedShort && n(l.qty?.remaining) > 0 && has(P.orderCloseShort) && (
                        <button title={tx('Close short')} className="rounded p-1 text-muted-foreground hover:bg-muted" onClick={() => { setCloseLine(l.id!); setReason(''); setDialog('close'); }}><Scissors className="h-4 w-4" /></button>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          {editable && <Button variant="outline" size="sm" onClick={() => { setLines((ls) => [...ls, blankLine()]); setDirty(true); }}><Plus className="mr-1 h-4 w-4" />{tx('Add a line')}</Button>}
          <div className="grid gap-4 border-t pt-4 md:grid-cols-[minmax(0,1fr)_320px]">
            <Field label={tx('Notes')}><Textarea disabled={!editable} rows={3} value={notes} onChange={(e) => { setNotes(e.target.value); setDirty(true); }} /></Field>
            <div className="text-sm">
              <h2 className="mb-2 font-semibold">{tx('Totals')}{dirty && <span className="ml-2 text-xs font-normal text-amber-600">{tx('(estimate — save to recalculate)')}</span>}</h2>
              <div className="mb-1 flex items-center justify-between gap-2"><span className="text-muted-foreground">{tx('Order discount %')}</span><Input aria-label={tx('Order discount %')} disabled={!editable} type="number" min={0} max={100} className="h-8 w-24 text-right" value={orderDiscount} onChange={(e) => { setOrderDiscount(Number(e.target.value)); setDirty(true); }} /></div>
              <Row label={tx('Untaxed amount')} value={money(dirty || !o ? est * (1 - orderDiscount / 100) : o.subtotal, currency)} />
              {o && !dirty && <Row label={tx('Taxes')} value={money(o.taxAmount, currency)} />}
              {o && !dirty && <div className="mt-2 border-t pt-2"><Row label={tx('Total')} value={<strong className="text-lg">{money(o.total, currency)}</strong>} /></div>}
            </div>
          </div>
          </TabsContent>

          <TabsContent value="delivery" className="mt-0 space-y-4 p-5">
            {isNew || !o ? (
              <>
                <Field label={tx('Requested date')}><Input type="date" className="max-w-xs" disabled={!editable} value={requestedDate} onChange={(e) => { setRequestedDate(e.target.value); setDirty(true); }} /></Field>
                <HandoverDraftPanel value={handover} onChange={(v) => { setHandover(v); setDirty(true); }} hint={tx('Plan how the goods reach the customer. Saved with the order; dispatch and delivery follow once it is confirmed.')} />
              </>
            ) : (
              <>
                <div className="flex flex-wrap items-end gap-x-6 gap-y-2 text-sm">
                  <Field label={tx('Requested date')}>{editable ? <Input type="date" value={requestedDate} onChange={(e) => { setRequestedDate(e.target.value); setDirty(true); }} /> : <p className="py-2">{o.requestedDate ? date(o.requestedDate) : '—'}</p>}</Field>
                  <Field label={tx('Stock delivery status')}><p className="py-2">{DELIVERY_LABEL[o.deliveryStatus]}</p></Field>
                </div>
                <HandoverTab path={`/sales/orders/${o.id}/handover`} canEdit={has(P.orderWrite)} sourceLabel={tx('sales order')} readyHint={tx('Confirm the sales order first')} issuedLabel={tx('Delivered from stock')} />
              </>
            )}
            {o && (
              <div className="grid items-start gap-4 lg:grid-cols-2">
                {fulfilling && <FulfilmentPanel order={o} />}
                <AvailabilityPanel order={o} />
              </div>
            )}
          </TabsContent>

          <TabsContent value="billing" className="mt-0 space-y-4 p-5">
            <TabSection title={tx('Invoicing')}>
              <Field label={tx('Invoice status')}><p className="py-2 text-sm">{o ? INVOICE_LABEL[o.invoiceStatus] : '—'}</p></Field>
              <Field label={tx('Order total')}><p className="py-2 text-sm font-medium tabular-nums">{money(o && !dirty ? o.total : est, currency)}</p></Field>
            </TabSection>
            {(o?.partnerId || partnerId) ? (
              <div className="grid items-start gap-4 lg:grid-cols-2">
                {o && fulfilling && <BillingPanel order={o} />}
                {o && fulfilling && <MilestonesPanel orderId={o.id} total={Number(o.total)} />}
                <CreditPanel partnerId={o?.partnerId ?? partnerId} />
              </div>
            ) : <p className="text-sm text-muted-foreground">{tx('Choose a customer to see credit and billing details.')}</p>}
          </TabsContent>

          <TabsContent value="other" className="mt-0 space-y-4 p-5">
            {o ? (
              <div className="grid items-start gap-4 lg:grid-cols-2">
                <ChangeRequestsPanel orderId={o.id} />
                <AmendmentHistory orderId={o.id} />
                <AttachmentPanel resType="sales_order" resId={o.id} />
              </div>
            ) : <p className="text-sm text-muted-foreground">{tx('Save the order to add attachments and change requests.')}</p>}
          </TabsContent>
        </Tabs>
      </section>

      <Dialog open={dialog === 'hold' || dialog === 'cancel' || dialog === 'close'} onOpenChange={(v) => !v && setDialog(null)}>
        <DialogContent className="max-w-md">
          <DialogHeader><DialogTitle>{dialog === 'hold' ? tx('Put order on hold') : dialog === 'cancel' ? tx('Cancel order') : tx('Close line short')}</DialogTitle></DialogHeader>
          <Field label={tx('Reason')}><Textarea rows={3} value={reason} onChange={(e) => setReason(e.target.value)} /></Field>
          <DialogFooter>
            <Button variant="outline" onClick={() => setDialog(null)}>{tx('Back')}</Button>
            <Button variant="destructive" disabled={!reason.trim()} onClick={() => {
              if (dialog === 'close' && closeLine) close.mutate({ id: o!.id, lineId: closeLine, reason }, { onError: (e) => toast.error(apiErrorMessage(e, tx('Action failed'))) });
              else run(dialog === 'hold' ? 'hold' : 'cancel', { reason });
              setDialog(null);
            }}>{tx('Confirm')}</Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      {o && dialog === 'change' && <ChangeDialog order={o} onClose={() => setDialog(null)} />}
      {o && dialog === 'delivery' && <NewDeliveryDialog orderId={o.id} onClose={() => setDialog(null)} />}
      {o && dialog === 'revisions' && <RevisionsDialog orderId={o.id} onClose={() => setDialog(null)} />}
      {o && dialog === 'return' && <NewReturnDialog order={o} onClose={() => setDialog(null)} />}
    </div>
  );
}

function TabSection({ title, children }: { title: string; children: ReactNode }) {
  return (
    <div>
      <h3 className="mb-3 border-b pb-1 text-xs font-semibold uppercase tracking-wide text-muted-foreground">{title}</h3>
      <div className="grid gap-3 sm:grid-cols-2 lg:max-w-3xl">{children}</div>
    </div>
  );
}

function Row({ label, value }: { label: string; value: ReactNode }) {
  return <div className="flex items-center justify-between py-0.5"><span className="text-muted-foreground">{label}</span><span className="tabular-nums">{value}</span></div>;
}

/** Request a change to a confirmed order: quantities, prices, promised dates, requested date. */
function ChangeDialog({ order, onClose }: { order: NonNullable<ReturnType<typeof useSalesOrder>['data']>; onClose: () => void }) {
  const [reason, setReason] = useState('');
  const [requestedDate, setRequestedDate] = useState(order.requestedDate?.slice(0, 10) ?? '');
  const [edits, setEdits] = useState<Record<string, { quantity?: number; unitPrice?: number; promisedDate?: string }>>({});
  const req = useSalesMutation(salesApi.requestChange);
  const lines = (order.lines ?? []).filter((l) => !l.closedShort);
  const submit = () => {
    const changes: Record<string, unknown> = {};
    if (requestedDate !== (order.requestedDate?.slice(0, 10) ?? '')) changes.header = { requestedDate };
    const ls = Object.entries(edits).map(([lineId, e]) => ({ lineId, ...e })).filter((e) => Object.keys(e).length > 1);
    if (ls.length) changes.lines = ls;
    req.mutate({ id: order.id, reason, changes }, {
      onSuccess: (cr) => { toast.success(tx('Change request') + ' ' + cr.number + ' ' + tx('sent for approval')); onClose(); },
      onError: (e) => toast.error(apiErrorMessage(e, tx('Could not request the change'))),
    });
  };
  const set = (id: string, p: Record<string, unknown>) => setEdits((e) => ({ ...e, [id]: { ...e[id], ...p } }));
  return (
    <Dialog open onOpenChange={(v) => !v && onClose()}>
      <DialogContent className="max-w-3xl">
        <DialogHeader><DialogTitle>{tx('Request a change')} — {order.number}</DialogTitle></DialogHeader>
        <p className="text-xs text-muted-foreground">{tx('Quantities cannot go below what is already delivered or invoiced. A price change on a line that already moved splits the line.')}</p>
        <div className="grid gap-3 sm:grid-cols-2">
          <Field label={tx('Requested date')}><Input type="date" value={requestedDate} onChange={(e) => setRequestedDate(e.target.value)} /></Field>
        </div>
        <table className="w-full text-sm">
          <thead className="text-left text-xs text-muted-foreground"><tr><th>{tx('Line')}</th><th className="text-right">{tx('Delivered')}</th><th className="w-24">{tx('Quantity')}</th><th className="w-28">{tx('Unit price')}</th><th className="w-36">{tx('Promised date')}</th></tr></thead>
          <tbody>
            {lines.map((l) => (
              <tr key={l.id} className="border-t">
                <td className="py-1.5">{l.description}</td>
                <td className="py-1.5 text-right tabular-nums">{n(l.qty?.delivered)}</td>
                <td className="py-1.5 pl-2"><Input type="number" defaultValue={l.quantity} onChange={(e) => set(l.id!, { quantity: Number(e.target.value) })} /></td>
                <td className="py-1.5 pl-2"><Input type="number" defaultValue={l.unitPrice} onChange={(e) => set(l.id!, { unitPrice: Number(e.target.value) })} /></td>
                <td className="py-1.5 pl-2"><Input type="date" defaultValue={l.promisedDate?.slice(0, 10) ?? ''} onChange={(e) => set(l.id!, { promisedDate: e.target.value })} /></td>
              </tr>
            ))}
          </tbody>
        </table>
        <Field label={tx('Reason')}><Textarea rows={2} value={reason} onChange={(e) => setReason(e.target.value)} /></Field>
        <DialogFooter>
          <Button variant="outline" onClick={onClose}>{tx('Cancel')}</Button>
          <Button disabled={!reason.trim() || req.isPending} onClick={submit}>{tx('Send for approval')}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

function ChangeRequestsPanel({ orderId }: { orderId: string }) {
  const { data } = useChangeRequests(orderId);
  const canApprove = useAuthStore((s) => s.hasPermission(PERMISSIONS.sales.orderAmendApprove));
  const decide = useSalesMutation(salesApi.decideChange);
  if (!data?.length) return null;
  const go = (cr: ChangeRequest, a: 'approve' | 'reject') => decide.mutate({ id: cr.id, action: a }, {
    onSuccess: () => toast.success(a === 'approve' ? tx('Change applied') : tx('Change rejected')),
    onError: (e) => toast.error(apiErrorMessage(e, tx('Action failed'))),
  });
  return (
    <div className="rounded-xl border bg-card p-4 text-sm shadow-sm">
      <h2 className="mb-2 font-semibold">{tx('Change requests')}</h2>
      <ul className="space-y-2">
        {data.map((cr) => (
          <li key={cr.id} className="rounded-lg border p-2">
            <div className="flex items-center justify-between"><span className="font-medium">{cr.number}</span><span className="text-xs text-muted-foreground">{cr.status.replace('_', ' ')}</span></div>
            <p className="text-xs text-muted-foreground">{cr.reason}</p>
            {cr.impact && <p className="text-xs">{tx('Value change')}: {money(Number((cr.impact as { valueDelta?: number }).valueDelta ?? 0))}</p>}
            {cr.status === 'pending_approval' && canApprove && (
              <div className="mt-1 flex gap-2"><Button size="sm" onClick={() => go(cr, 'approve')}>{tx('Approve & apply')}</Button><Button size="sm" variant="outline" onClick={() => go(cr, 'reject')}>{tx('Reject')}</Button></div>
            )}
          </li>
        ))}
      </ul>
    </div>
  );
}

/** Every applied amendment of this order, newest first, with what changed. */
function AmendmentHistory({ orderId }: { orderId: string }) {
  const { data } = useOrderRevisions(orderId, true);
  if (!data?.length) return null;
  return (
    <div className="rounded-xl border bg-card p-4 text-sm shadow-sm">
      <h2 className="mb-2 font-semibold">{tx('Amendment history')}</h2>
      <ul className="space-y-2">
        {data.map((r) => (
          <li key={r.id} className="rounded-lg border p-2">
            <div className="flex justify-between"><span className="font-medium">r{r.revision} · {r.reason}</span><span className="text-xs text-muted-foreground">{date(r.createdAt)} · {money(r.total)}</span></div>
            {r.diff != null && <pre className="mt-1 max-h-32 overflow-auto whitespace-pre-wrap break-words text-[11px] text-muted-foreground">{JSON.stringify(r.diff, null, 1)}</pre>}
          </li>
        ))}
      </ul>
    </div>
  );
}

function RevisionsDialog({ orderId, onClose }: { orderId: string; onClose: () => void }) {
  const { data } = useOrderRevisions(orderId, true);
  return (
    <Dialog open onOpenChange={(v) => !v && onClose()}>
      <DialogContent className="max-w-lg">
        <DialogHeader><DialogTitle>{tx('Revisions')}</DialogTitle></DialogHeader>
        <ul className="space-y-2 text-sm">
          {(data ?? []).map((r) => (
            <li key={r.id} className="flex justify-between rounded-lg border p-2"><span>r{r.revision} · {r.reason}</span><span className="text-muted-foreground">{date(r.createdAt)} · {money(r.total)}</span></li>
          ))}
        </ul>
      </DialogContent>
    </Dialog>
  );
}

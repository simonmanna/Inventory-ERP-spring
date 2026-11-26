import { Fragment, useEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import { Link, useLocation, useNavigate, useParams, useSearchParams } from 'react-router-dom';
import { RecordChatter } from '@/features/communication/record-chatter';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from '@/lib/api';
import { HeaderSelect, useHeaderOptions } from '@/pages/sales/OrderHeaderFields';
import { defaultUnitId, unitPrice as priceForUnit, withVariantPrices } from '@/features/products/units';
import { cachedVariantOption } from '@/features/products/variants-api';
import { toast } from 'sonner';
import { QuoteShareDialog } from './quote-share';
import { AlertTriangle, CheckCircle2, ChevronDown, Clock, Copy, FileStack, Handshake, History, Link2, Mail, PackageCheck, Printer, Send, ShoppingCart, ThumbsDown, Trash2, UserPlus, XCircle } from 'lucide-react';
import { PartnerCreateDialog } from '@/pages/partners/PartnerCreateDialog';
import { PERMISSIONS } from '@erp/shared';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Textarea } from '@/components/ui/textarea';
import { Skeleton } from '@/components/ui/skeleton';
import { Dialog, DialogContent, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuSeparator, DropdownMenuTrigger } from '@/components/ui/dropdown-menu';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs';
import { OptionalSelect } from '@/pages/fleet/fleet-ui';
import { useAuthStore } from '@/stores/auth.store';
import { apiErrorMessage } from '@/lib/api-error';
import { date, localDateInput, money } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';
import { cn } from '@/lib/utils';
import {
  crmApi, quoteBase, useCrmLookups, useCrmMutation, useOpportunity, useProductOptions, useQuotation, useTaxOptions,
  type Quotation,
} from '@/features/crm/suite-api';
import { Field, QuoteStatusPill } from './crm-ui';
import { PartnerPicker } from './crm-dialogs';
import { InlineField, SmartButton, StatusRibbon } from '@/pages/sales/sales-ui';
import { QuoteLinesTable, type QuoteLine } from './QuoteLinesTable';
import { EMPTY_SIZE, sizeOf, sizePayload } from '@/features/products/sizing';
import { counts, isLayout, lineAmounts, useQuoteImageColumn } from './quote-lines';
import { QuoteAcceptDialog, QuoteEmailDialog, QuoteRevisionsDialog, SaveTemplateDialog } from './quote-dialogs';

const blankLine = (taxInclusive?: boolean): QuoteLine => ({ productId: null, description: '', quantity: 1, unitPrice: 0, discountPercent: 0, taxId: null, size: EMPTY_SIZE, ...(taxInclusive !== undefined ? { taxInclusive } : {}) });
const layoutLine = (displayType: 'line_section' | 'line_note'): QuoteLine => ({ displayType, productId: null, description: '', quantity: 1, unitPrice: 0, discountPercent: 0, taxId: null });

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
  const canWrite = useAuthStore((s) => s.hasPermission(PERMISSIONS.crm.quotationWrite) || s.hasPermission(PERMISSIONS.sales.quotationWrite));
  const canConvert = useAuthStore((s) => s.hasPermission(PERMISSIONS.crm.quotationConvert) || s.hasPermission(PERMISSIONS.sales.quotationConvert));
  const canCreateCustomer = useAuthStore((s) => s.hasPermission(PERMISSIONS.partners.create));
  // PDF / print / email follow the same API family as the rest of the editor (quoteBase): /sales or /crm.
  const docBase = quoteBase();
  // Audit fix 11: Mark as sent, Email and the online link all need the send right of the API family in use.
  const canEmail = useAuthStore((s) => (docBase.startsWith('/sales') ? s.hasPermission(PERMISSIONS.sales.quotationSend) : s.hasPermission(PERMISSIONS.crm.quotationWrite)));
  const canAgreements = useAuthStore((s) => s.hasPermission(PERMISSIONS.sales.agreementRead));
  const [emailOpen, setEmailOpen] = useState(false);
  const [acceptOpen, setAcceptOpen] = useState(false);
  const [revisionsOpen, setRevisionsOpen] = useState(false);
  const [templateOpen, setTemplateOpen] = useState(false);
  const [contactId, setContactId] = useState('');
  const [siteId, setSiteId] = useState('');
  const [agreementId, setAgreementId] = useState('');
  const [currencyCode, setCurrencyCode] = useState('');
  const [exchangeRate, setExchangeRate] = useState(1);
  const [orderDisc, setOrderDisc] = useState<{ kind: 'percent' | 'amount'; value: number }>({ kind: 'percent', value: 0 });
  const [newCustomerOpen, setNewCustomerOpen] = useState(false);
  const [shareOpen, setShareOpen] = useState(false);
  const orgCurrency = useAuthStore((s) => s.organization?.currencyCode ?? 'IDR');
  const base = useLocation().pathname.startsWith('/sales') ? '/sales/quotations' : '/crm/quotations';
  const [internalNotes, setInternalNotes] = useState('');
  const [header, setHeader] = useState({ branchId: '', paymentTermId: '', deliveryAddress: '', commitmentDate: '' });
  /** Tax Excl. / Tax Incl. chosen by the user; null = as each product is priced. */
  const [taxMode, setTaxMode] = useState<'excl' | 'incl' | null>(null);
  const [lostReasonId, setLostReasonId] = useState('');
  const opts = useHeaderOptions();
  const uoms = useQuery({ queryKey: ['header-options', 'uoms'], queryFn: async () => { try { const d = (await api.get('/uoms', { params: { pageSize: 500 } })).data; return (Array.isArray(d) ? d : d.data ?? []) as Array<{ id: string; code: string; name: string }>; } catch { return []; } }, staleTime: 300_000, retry: false });
  const lostReasons = useQuery({ queryKey: ['sales', 'lost-reasons'], queryFn: async () => (await api.get<Array<{ id: string; name: string; isActive: boolean }>>('/sales/lost-reasons')).data, retry: false, enabled: useAuthStore.getState().hasPermission(PERMISSIONS.sales.quotationRead) });

  const [partnerId, setPartnerId] = useState('');
  const [partnerName, setPartnerName] = useState<string | null>(null);
  const [issueDate, setIssueDate] = useState(localDateInput());
  const [validUntil, setValidUntil] = useState('');
  const [ownerId, setOwnerId] = useState('');
  const [notes, setNotes] = useState('');
  const [terms, setTerms] = useState('');
  const [lines, setLines] = useState<QuoteLine[]>([]);
  const [dirty, setDirty] = useState(false);
  const [dialog, setDialog] = useState<'reject' | 'decline' | 'convert' | null>(null);
  const [reason, setReason] = useState('');
  const [target, setTarget] = useState<'sales_order' | 'order' | 'proforma'>('sales_order');
  const [backorder, setBackorder] = useState(false);
  const [shortages, setShortages] = useState<string[] | null>(null);
  const [tab, setTab] = useState('lines');
  // Header pick lists of the chosen customer; a list the user cannot read stays empty (and its field hidden).
  const contactsQ = useQuery({
    queryKey: ['quote-editor', 'contacts', partnerId],
    queryFn: async () => { try { return ((await api.get<{ contacts?: Array<{ id: string; firstName: string; lastName: string | null; email: string | null; isActive?: boolean }> }>(`/partners/${partnerId}`)).data.contacts ?? []).filter((c) => c.isActive !== false); } catch { return []; } },
    enabled: !!partnerId, staleTime: 60_000, retry: false,
  });
  const sitesQ = useQuery({
    queryKey: ['quote-editor', 'sites', partnerId],
    queryFn: async () => { try { const d = (await api.get('/fleet/customer-sites', { params: { partnerId, pageSize: 200 } })).data; return ((Array.isArray(d) ? d : d.data) ?? []) as Array<{ id: string; name: string }>; } catch { return []; } },
    enabled: !!partnerId, staleTime: 60_000, retry: false,
  });
  const agreementsQ = useQuery({
    queryKey: ['sales', 'agreements', 'active', partnerId],
    queryFn: async () => { try { return (await api.get<Array<{ id: string; number: string; name: string }>>('/sales/agreements', { params: { partnerId, status: 'active' } })).data; } catch { return []; } },
    enabled: !!partnerId && canAgreements, staleTime: 60_000, retry: false,
  });
  const contacts = contactsQ.data ?? [];
  const sites = sitesQ.data ?? [];
  const agreements = agreementsQ.data ?? [];
  const imageColumn = useQuoteImageColumn();
  /** The organization shows product images on quotations; each user still shows or hides the column. */
  const imagesOn = Boolean(lk?.settings.quoteLineImages ?? q?.lineImages);
  /** Print and PDF carry images exactly when this user sees the Image column. */
  const printImages = imagesOn && imageColumn.show;

  useEffect(() => {
    if (q) {
      setPartnerId(q.partnerId); setPartnerName(q.partnerName); setIssueDate(q.issueDate?.slice(0, 10) ?? '');
      setValidUntil(q.validUntil?.slice(0, 10) ?? ''); setOwnerId(q.ownerId ?? ''); setNotes(q.notes ?? ''); setTerms(q.terms ?? '');
      setInternalNotes(q.internalNotes ?? ''); setHeader({ branchId: q.branchId ?? '', paymentTermId: q.paymentTermId ?? '', deliveryAddress: q.deliveryAddress ?? '', commitmentDate: q.commitmentDate?.slice(0, 10) ?? '' });
      setLines(q.lines?.length ? q.lines.map((l) => ({ ...l, displayType: l.displayType ?? null, size: sizeOf(l as Parameters<typeof sizeOf>[0]) })) : []); setDirty(false); setTaxMode(null);
      setContactId(q.contactId ?? ''); setSiteId(q.siteId ?? ''); setAgreementId(q.agreementId ?? ''); setCurrencyCode(q.currencyCode ?? '');
      setExchangeRate(Number(q.exchangeRate ?? 1) || 1);
      setOrderDisc(Number(q.orderDiscountAmount ?? 0) > 0 ? { kind: 'amount', value: Number(q.orderDiscountAmount) } : { kind: 'percent', value: Number(q.orderDiscountPercent ?? 0) });
    }
  }, [q]);
  useEffect(() => {
    if (isNew && deal?.partner) { setPartnerId(deal.partner.id); setPartnerName(deal.partner.name); }
    else if (isNew && params.get('partnerId')) { setPartnerId(params.get('partnerId')!); setPartnerName(params.get('partnerName')); }
  }, [isNew, deal, params]);
  // A new quotation from an opportunity starts from its expected products (CRM enhancements T3b), once.
  const prefilled = useRef(false);
  useEffect(() => {
    if (!isNew || prefilled.current || !deal?.lines?.length) return;
    prefilled.current = true;
    setLines((ls) => ls.length ? ls : deal.lines!.map((l) => ({ ...blankLine(l.taxInclusive ?? undefined), productId: l.productId, variantId: l.variantId ?? null,
      description: l.description, quantity: Number(l.quantity), unitPrice: Number(l.unitPrice), discountPercent: Number(l.discountPercent), taxId: l.taxId ?? null })));
  }, [isNew, deal]);
  useEffect(() => { if (isNew && lk?.settings.defaultQuoteTerms) setTerms((t) => t || lk.settings.defaultQuoteTerms || ''); }, [isNew, lk]);
  // A new quotation from a template starts from its lines, notes and terms (prices follow the rules once a customer is set).
  const templateId = isNew ? params.get('templateId') : null;
  const template = useQuery({ queryKey: ['crm-suite', 'quote-template', templateId], queryFn: () => crmApi.quoteTemplate(templateId!), enabled: !!templateId, retry: false });
  const fromTemplate = useRef(false);
  useEffect(() => {
    if (!template.data || fromTemplate.current) return;
    fromTemplate.current = true;
    const t = template.data;
    setLines((t.lines ?? []).map((raw) => {
      const l = raw as Partial<QuoteLine> & { displayType?: string | null };
      if (l.displayType === 'line_section' || l.displayType === 'line_note') return layoutLine(l.displayType);
      return { ...blankLine(typeof l.taxInclusive === 'boolean' ? l.taxInclusive : undefined), productId: l.productId ?? null, variantId: l.variantId ?? null, uomId: l.uomId ?? null,
        description: l.description ?? '', quantity: Number(l.quantity ?? 1), unitPrice: Number(l.unitPrice ?? 0), discountPercent: Number(l.discountPercent ?? 0),
        taxId: l.taxId ?? null, isOptional: !!l.isOptional, leadTimeDays: l.leadTimeDays ?? null, priceSource: l.unitPrice != null ? 'manual' as const : undefined,
        size: sizeOf(l as Parameters<typeof sizeOf>[0]) };
    }).map((l, i) => (isLayout(l) ? { ...l, description: String(((template.data!.lines ?? [])[i] as { description?: string }).description ?? '') } : l)));
    if (t.notes) setNotes(t.notes);
    if (t.terms) setTerms(t.terms);
    setDirty(true);
  }, [template.data]);

  // A customer decline keeps the declined terms: change them through Revise (snapshot + new revision), not in place.
  const declined = q?.status === 'rejected' && !!q.declinedAt;
  const editable = isNew || (!!q && ['draft', 'rejected'].includes(q.status) && !declined && canWrite);
  const taxRate = (taxId: string | null) => Number(taxes.data?.find((t) => t.id === taxId)?.rate ?? 0);
  const useServer = !!q && !dirty;
  /** Untaxed amount, one row per tax and total: the saved figures, or an estimate while editing. */
  const est = useMemo(() => {
    let sub = 0; let tax = 0; let total = 0;
    const byTax = new Map<string, { label: string; amount: number }>();
    let net = 0;
    for (const l of lines) if (counts(l)) net += Number(l.quantity || 0) * Number(l.unitPrice || 0) * (1 - Number(l.discountPercent || 0) / 100);
    // The order discount the server folds into each counted line, as a share of the lines' net.
    const orderPct = orderDisc.kind === 'percent' ? Math.min(100, Math.max(0, orderDisc.value)) : net > 0 ? Math.min(100, (orderDisc.value / net) * 100) : 0;
    for (const l of lines) {
      if (!counts(l)) continue;
      const raw = useServer ? lineAmounts(l, taxRate(l.taxId), true) : lineAmounts(l, taxRate(l.taxId), false);
      const k = useServer ? 1 : 1 - orderPct / 100;
      const a = { sub: raw.sub * k, tax: raw.tax * k, total: raw.total * k };
      sub += a.sub; tax += a.tax; total += a.total;
      if (l.taxId && a.tax) {
        const t = taxes.data?.find((x) => x.id === l.taxId);
        const label = t ? `${t.name} ${Number(t.rate)}%` : l.taxName ?? tx('Tax');
        const row = byTax.get(l.taxId) ?? { label, amount: 0 };
        row.amount += a.tax; byTax.set(l.taxId, row);
      }
    }
    return { sub, tax, total, byTax: [...byTax.values()] };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [lines, taxes.data, useServer, orderDisc]);
  const pricedWithTax = lines.filter((l) => !isLayout(l) && l.taxId);
  const taxIncluded = taxMode ? taxMode === 'incl' : pricedWithTax.length > 0 && pricedWithTax.every((l) => l.taxInclusive);
  const chooseTaxMode = (mode: 'excl' | 'incl') => {
    setTaxMode(mode);
    if (!editable) return;
    setLines((ls) => ls.map((l) => (isLayout(l) ? l : { ...l, taxInclusive: mode === 'incl' })));
    setDirty(true);
  };

  /** Edit a line; a new price or discount makes it a manual price unless `keepSource`. */
  const patchLine = (i: number, p: Partial<QuoteLine>, keepSource = false) => {
    setLines((ls) => ls.map((l, j) => (j === i ? { ...l, ...p, priceSource: p.priceSource ?? (!keepSource && ('unitPrice' in p || 'discountPercent' in p) ? 'manual' : l.priceSource) } : l)));
    setDirty(true);
  };
  const qc = useQueryClient();
  const productById = (pid: string | null) => (pid ? products.data?.find((x) => x.id === pid) : undefined);
  const setProduct = (i: number, pid: string | null) => {
    const prod = productById(pid);
    const next = lines.map((x, j) => (j === i ? { ...x, productId: pid, variantId: null, size: EMPTY_SIZE, uomId: defaultUnitId(prod, 'sales') ?? null, priceSource: undefined,
      description: canWrite ? '' : prod?.name ?? '', unitPrice: prod ? Number(prod.salesPrice ?? 0) : x.unitPrice, taxId: prod?.taxId ?? x.taxId,
      ...(taxMode ? { taxInclusive: taxMode === 'incl' } : {}) } : x));
    setLines(next); setDirty(true); void priceLines(i, next);
  };
  const setUnit = (i: number, uomId: string) => {
    // D1: the variant's effective price (when one is set) replaces the product's, then the unit rule.
    const variant = cachedVariantOption(qc, lines[i].productId, lines[i].variantId);
    const price = priceForUnit(withVariantPrices(productById(lines[i].productId), variant), uomId, 'sales');
    // The server reprices the line in its new unit on save.
    setLines((ls) => ls.map((l, j) => (j === i ? { ...l, uomId, priceSource: undefined, ...(price !== null ? { unitPrice: Number(price.toFixed(6)) } : {}) } : l)));
    setDirty(true);
  };
  const addRow = (kind: 'line' | 'section' | 'note' | 'charge') => {
    const inclusive = taxMode ? taxMode === 'incl' : undefined;
    const row = kind === 'section' ? layoutLine('line_section') : kind === 'note' ? layoutLine('line_note')
      : kind === 'charge' ? { ...blankLine(inclusive), description: tx('Transport charge'), priceSource: 'manual' as const } : blankLine(inclusive);
    setLines((ls) => [...ls, row]); setDirty(true);
  };
  const moveRow = (from: number, to: number) => {
    setLines((ls) => { const next = [...ls]; const [row] = next.splice(from, 1); next.splice(to, 0, row); return next; });
    setDirty(true);
  };
  const catalogAdd = (pid: string, delta: number) => {
    const i = lines.findIndex((l) => !isLayout(l) && l.productId === pid);
    if (i >= 0) {
      const qty = Number(lines[i].quantity || 0) + delta;
      const next = qty <= 0 ? lines.filter((_, j) => j !== i) : lines.map((l, j) => (j === i ? { ...l, quantity: qty } : l));
      setLines(next); setDirty(true);
      if (qty > 0 && lines[i].priceSource !== 'manual') void priceLines(i, next);
      return;
    }
    if (delta <= 0) return;
    const prod = productById(pid);
    const row: QuoteLine = { ...blankLine(taxMode ? taxMode === 'incl' : undefined), productId: pid, uomId: defaultUnitId(prod, 'sales') ?? null,
      description: canWrite ? '' : prod?.name ?? '', unitPrice: Number(prod?.salesPrice ?? 0), taxId: prod?.taxId ?? null };
    // Replace a trailing empty line instead of leaving it behind.
    const base = lines.length && !isLayout(lines[lines.length - 1]) && !lines[lines.length - 1].productId && !lines[lines.length - 1].description ? lines.slice(0, -1) : lines;
    const next = [...base, row];
    setLines(next); setDirty(true); void priceLines(next.length - 1, next);
  };

  /**
   * Audit fix 5: price lines as saving will (customer, date, branch, site, currency and agreement of the header).
   * `ctx` carries a header value just picked (state updates later); `skipManual` leaves typed prices alone.
   */
  const priceLines = async (idx?: number, current: QuoteLine[] = lines, ctx: Partial<{ partnerId: string; agreementId: string; currencyCode: string; branchId: string; siteId: string; issueDate: string }> = {}, skipManual = false) => {
    if (!canWrite) return;
    const targets = current.map((l, i) => ({ l, i })).filter(({ l, i }) => !isLayout(l) && l.productId && (idx === undefined || i === idx) && !(skipManual && l.priceSource === 'manual'));
    if (!targets.length) return;
    const c = { partnerId, agreementId, currencyCode, branchId: header.branchId, siteId, issueDate, ...ctx };
    try {
      const res = await crmApi.resolveQuotePrices({ partnerId: c.partnerId || undefined, issueDate: c.issueDate || undefined, branchId: c.branchId || undefined,
        siteId: c.siteId || undefined, currencyCode: c.currencyCode || undefined, agreementId: c.agreementId || undefined, lines: targets.map(({ l }) => ({
        productId: l.productId!, quantity: Number(l.quantity || 1), variantId: l.variantId || undefined, uomId: l.uomId || undefined,
      })) });
      setLines((ls) => ls.map((l, i) => {
        const k = targets.findIndex((t) => t.i === i);
        if (k < 0) return l;
        // Review M-11: an answer for a line that changed while it was being priced (other product, variant, unit or
        // quantity, a price typed since, or a row moved or removed) is stale; the newer request's answer applies.
        const asked = targets[k].l;
        if (l.productId !== asked.productId || (l.variantId ?? null) !== (asked.variantId ?? null)
          || (l.uomId ?? null) !== (asked.uomId ?? null) || Number(l.quantity || 1) !== Number(asked.quantity || 1)
          || (l.priceSource === 'manual' && asked.priceSource !== 'manual')) return l;
        const r = res.lines[k];
        return { ...l, listPrice: r.listPrice, unitPrice: r.unitPrice, discountPercent: r.discountPercent, priceSource: r.source, taxId: l.taxId ?? r.taxId,
          taxInclusive: taxMode ? taxMode === 'incl' : r.taxInclusive, description: l.description || r.description };
      }));
      setDirty(true);
      if (idx === undefined && !skipManual) toast.success(tx('Customer prices applied'));
    } catch (e) { toast.error(apiErrorMessage(e, tx('Could not price the lines'))); }
  };

  const save = useCrmMutation(async () => {
    const body = {
      partnerId, issueDate, validUntil: validUntil || undefined, ownerId: ownerId || undefined, notes, terms, internalNotes,
      branchId: header.branchId || null, paymentTermId: header.paymentTermId || null, deliveryAddress: header.deliveryAddress || null,
      commitmentDate: header.commitmentDate || null,
      contactId: contactId || null, siteId: siteId || null, ...(agreementId ? { agreementId } : isNew ? {} : { agreementId: null }),
      ...(currencyCode ? { currencyCode } : {}), exchangeRate: exchangeRate > 0 ? exchangeRate : 1,
      orderDiscountPercent: orderDisc.kind === 'percent' && orderDisc.value > 0 ? orderDisc.value : null,
      orderDiscountAmount: orderDisc.kind === 'amount' && orderDisc.value > 0 ? orderDisc.value : null,
      // Audit fix 13: each line goes back with its id, so line ids stay stable across saves.
      lines: lines.filter((l) => (isLayout(l) ? l.description.trim() : l.productId || l.description)).map((l) => (isLayout(l)
        ? { ...(l.id ? { id: l.id } : {}), displayType: l.displayType, description: l.description.trim() }
        : {
          ...(l.id ? { id: l.id } : {}), isOptional: !!l.isOptional,
          productId: l.productId || undefined, variantId: l.variantId || undefined, uomId: l.uomId || undefined, description: l.description || undefined, quantity: Number(l.quantity),
          // ADR-S066: the typed size (only offered for products sold by size) decides the quantity server-side
          ...(l.productId ? sizePayload(l.size ?? EMPTY_SIZE, 'volume') : {}),
          unitPrice: l.priceSource === 'manual' || !l.productId ? Number(l.unitPrice) : undefined,
          discountPercent: l.priceSource === 'manual' || !l.productId ? Number(l.discountPercent) : undefined,
          taxId: l.taxId || null,
          ...(typeof l.taxInclusive === 'boolean' ? { taxInclusive: l.taxInclusive } : {}),
          ...(l.leadTimeDays != null ? { leadTimeDays: l.leadTimeDays } : {}),
        })),
    };
    if (isNew) return crmApi.createQuote({ ...body, dealId });
    return crmApi.updateQuote({ id: q!.id, ...body });
  });
  const action = useCrmMutation(crmApi.quoteAction);
  const convert = useCrmMutation(crmApi.convertQuote);
  // one Idempotency-Key per quotation + choice: a retry after a lost answer replays the first conversion
  const convertKeys = useRef(new Map<string, string>());
  const convertKey = (target: string, backorder: boolean) => {
    const k = `${q?.id}:${target}:${backorder}`;
    if (!convertKeys.current.has(k)) convertKeys.current.set(k, crypto.randomUUID());
    return convertKeys.current.get(k)!;
  };
  const del = useCrmMutation(crmApi.deleteQuote);
  const duplicate = useCrmMutation(crmApi.duplicateQuote);

  const doSave = (then?: (saved: Quotation) => void) => {
    if (!partnerId) return toast.error(tx('Choose a customer'));
    save.mutate(undefined, {
      onSuccess: (saved) => { setDirty(false); if (isNew) navigate(`${base}/${saved.id}`, { replace: true }); else toast.success(tx('Saved')); then?.(saved); },
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
  const currency = currencyCode || q?.currencyCode || deal?.currencyCode;
  // Audit fix 9: an offer past its date (the server expires it on the next list read or nightly) is shown as expired.
  const daysLeft = q?.validityDaysLeft ?? null;
  const overdue = !!q && ['approved', 'sent'].includes(status) && daysLeft !== null && daysLeft < 0;
  const hasOptional = (q?.lines ?? []).some((l) => !l.displayType && l.isOptional);

  const ribbon = [tx('Draft'), tx('Approved'), tx('Sent'), tx('Accepted'), tx('Converted')];
  const step = ({ approved: 1, sent: 2, accepted: 3, converted: 4 } as Record<string, number>)[status] ?? 0;
  const showRibbon = !['cancelled', 'expired'].includes(status);
  const ico = 'mr-2 h-4 w-4';
  type MenuItem = { key: string; label: string; icon: ReactNode; onSelect: () => void; danger?: boolean };
  const menu: MenuItem[] = [];
  if (q) {
    if (['approved', 'sent'].includes(status) && !overdue && canEmail) menu.push({ key: 'email', label: tx('Email to customer'), icon: <Mail className={ico} />, onSelect: () => setEmailOpen(true) });
    if (['approved', 'sent', 'accepted', 'rejected'].includes(status) && !overdue && canEmail) menu.push({ key: 'share', label: tx('Online link for the customer'), icon: <Link2 className={ico} />, onSelect: () => setShareOpen(true) });
    menu.push({ key: 'pdf', label: tx('Download PDF'), icon: <Printer className={ico} />, onSelect: () => downloadBlob(`${docBase}/${q.id}/pdf?images=${printImages}`, `${q.number}.pdf`) });
    menu.push({ key: 'print', label: tx('Print'), icon: <Printer className={ico} />, onSelect: () => printPdf(`${docBase}/${q.id}/pdf?images=${printImages}`) });
    if (['approved', 'sent'].includes(status) && !overdue && canWrite) menu.push({ key: 'decline', label: tx('Customer declined'), icon: <ThumbsDown className={ico} />, onSelect: () => { setReason(''); setLostReasonId(''); setDialog('decline'); } });
    menu.push({ key: 'revisions', label: tx('Revision history'), icon: <History className={ico} />, onSelect: () => setRevisionsOpen(true) });
    if (canWrite) menu.push({ key: 'duplicate', label: tx('Duplicate'), icon: <Copy className={ico} />, onSelect: () => duplicate.mutate(q.id, { onSuccess: (c) => { toast.success(tx('Copied to') + ' ' + c.number); navigate(`${base}/${c.id}`); }, onError: (e) => toast.error(apiErrorMessage(e, tx('Could not duplicate'))) }) });
    if (canWrite) menu.push({ key: 'template', label: tx('Save as template'), icon: <FileStack className={ico} />, onSelect: () => setTemplateOpen(true) });
    if (!['converted', 'draft'].includes(status) && canWrite) menu.push({ key: 'revise', label: tx('Revise'), icon: <History className={ico} />, onSelect: () => run('revise', undefined, tx('New revision in draft')) });
    if (!['converted', 'cancelled'].includes(status) && canWrite) menu.push({ key: 'cancel', label: tx('Cancel quotation'), icon: <XCircle className={ico} />, danger: true, onSelect: () => run('cancel', undefined, tx('Cancelled')) });
    if (status === 'draft' && canWrite) menu.push({ key: 'delete', label: tx('Delete draft'), icon: <Trash2 className={ico} />, danger: true, onSelect: () => { if (confirm(tx('Delete this draft?'))) del.mutate(q.id, { onSuccess: () => navigate(base), onError: (e) => toast.error(apiErrorMessage(e, tx('Could not delete'))) }); } });
  }
  const opportunityId = q?.dealId ?? dealId;
  const convertedLink = q?.convertedId ? (q.convertedType === 'sales_order' ? `/sales/orders/${q.convertedId}` : q.convertedType === 'order' ? `/orders/${q.convertedId}` : `/sales/proformas/${q.convertedId}`) : null;

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div className="text-sm">
          <Link to={base} className="font-medium text-primary hover:underline">{tx('Quotations')}</Link>
          <span className="mx-1.5 text-muted-foreground">/</span>
          <span className="font-semibold">{q ? q.number : tx('New')}</span>
          {q && q.revision > 0 && <span className="ml-1 text-xs text-muted-foreground">r{q.revision}</span>}
          {q && <span className="ml-3"><QuoteStatusPill status={status} /></span>}
          {q && ['approved', 'sent'].includes(status) && daysLeft !== null && (
            <span className={cn('ml-2 inline-flex items-center gap-1 rounded-full px-2 py-0.5 text-xs', daysLeft < 0 ? 'bg-rose-100 text-rose-800 dark:bg-rose-950 dark:text-rose-200' : daysLeft <= 7 ? 'bg-amber-100 text-amber-900 dark:bg-amber-950 dark:text-amber-200' : 'bg-muted text-muted-foreground')}>
              <Clock className="h-3 w-3" />{daysLeft < 0 ? tx('Expired') : daysLeft === 0 ? tx('Expires today') : `${tx('Valid')} ${daysLeft} ${daysLeft === 1 ? tx('more day') : tx('more days')}`}
            </span>
          )}
        </div>
        {showRibbon && <div data-tour="sales.quote.ribbon" className="w-full max-w-lg"><StatusRibbon steps={ribbon} current={step} /></div>}
      </div>

      <div className="flex flex-wrap items-start justify-between gap-3">
        <div data-tour="sales.quote.actions" className="flex flex-wrap items-center gap-2">
          {editable && <Button data-tour="sales.quote.save" onClick={() => doSave()} disabled={save.isPending}>{tx('Save')}</Button>}
          {q && editable && canWrite && <Button variant="secondary" disabled={action.isPending || save.isPending} onClick={() => dirty ? doSave(() => run('submit')) : run('submit')}>{tx('Submit for approval')}</Button>}
          {q?.status === 'pending_approval' && q.canApprove && <>
            <Button onClick={() => run('approve', {}, tx('Approved'))}><CheckCircle2 className="mr-1.5 h-4 w-4" />{tx('Approve')}</Button>
            <Button variant="destructive" onClick={() => { setReason(''); setDialog('reject'); }}>{tx('Reject')}</Button>
          </>}
          {q && status === 'approved' && !overdue && canEmail && <Button variant="secondary" onClick={() => run('send', undefined, tx('Marked as sent'))}><Send className="mr-1.5 h-4 w-4" />{tx('Mark as sent')}</Button>}
          {q && ['approved', 'sent'].includes(status) && !overdue && canWrite && <Button variant="secondary" onClick={() => (hasOptional ? setAcceptOpen(true) : run('accept', undefined, tx('Accepted by customer')))}>{tx('Customer accepted')}</Button>}
          {q && overdue && canWrite && <Button variant="secondary" onClick={() => run('revise', undefined, tx('New revision in draft'))}><History className="mr-1.5 h-4 w-4" />{tx('Revise')}</Button>}
          {q && ['approved', 'sent', 'accepted'].includes(status) && !overdue && canConvert && (
            <Button data-tour="sales.quote.convert" onClick={() => { setShortages(null); setBackorder(false); setDialog('convert'); }}><PackageCheck className="mr-1.5 h-4 w-4" />{tx('Convert')}</Button>
          )}
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
        <div data-tour="sales.quote.links" className="flex flex-wrap gap-2">
          <SmartButton icon={<Handshake className="h-4 w-4" />} count={opportunityId ? 1 : 0} label={tx('Opportunity')} disabled={!opportunityId} onClick={() => opportunityId && navigate(`/crm/opportunities/${opportunityId}`)} />
          <SmartButton icon={<ShoppingCart className="h-4 w-4" />} count={convertedLink ? 1 : 0} label={q?.convertedType === 'proforma' ? tx('Proforma') : tx('Order')} disabled={!convertedLink} onClick={() => convertedLink && navigate(convertedLink)} />
        </div>
      </div>

      {overdue && <Banner tone="danger" title={tx('Expired')} items={[tx('The validity of this offer has passed. Revise it to send the customer a new one.')]} />}
      {q && status === 'draft' && daysLeft !== null && daysLeft < 0 && <Banner tone="warning" title={tx('Expiration date passed')} items={[tx('Set a new expiration date before submitting.')]} />}
      {q?.status === 'pending_approval' && (
        <Banner tone="warning" title={tx('Waiting for approval')} items={q.approvalReasons} />
      )}
      {q?.status === 'rejected' && (declined
        ? <Banner tone="danger" title={tx('Declined by the customer')} items={[q.rejectionReason ?? '', canWrite ? tx('Use Actions → Revise to make a new offer.') : '']} />
        : <Banner tone="danger" title={tx('Rejected')} items={[q.rejectionReason ?? '']} />)}
      {q?.status === 'converted' && (
        <Banner tone="success" title={q.convertedType === 'proforma' ? tx('Converted to proforma invoice') : tx('Converted to order')}
          items={[q.convertedNumber ?? '']} link={convertedLink ?? undefined} />
      )}

      <section className="rounded-xl border bg-card shadow-sm">
        <div className="px-5 pt-5">
          <h1 className="flex items-baseline gap-1.5 text-2xl font-bold">
            <span>{q ? q.number : tx('New')}</span>
            <span className="text-sm font-medium text-muted-foreground">{tx('Quotation')}</span>
          </h1>
        </div>
        <div className="grid grid-cols-1 gap-x-12 px-5 pb-5 pt-4 md:grid-cols-2">
          <div>
            <InlineField label={tx('Customer')}>
              {editable ? (
                <div data-tour="sales.quote.customer" className="flex items-center gap-2">
                  <div className="min-w-0 flex-1">
                    <PartnerPicker value={partnerId} label={partnerName} onPick={(i, nm) => { setPartnerId(i); setPartnerName(nm || null); setContactId(''); setSiteId(''); setAgreementId(''); setDirty(true); void priceLines(undefined, lines, { partnerId: i, siteId: '', agreementId: '' }, true); }} />
                  </div>
                  {canCreateCustomer && (
                    <Button type="button" variant="outline" size="icon" className="shrink-0" title={tx('New customer')} aria-label={tx('New customer')} onClick={() => setNewCustomerOpen(true)}>
                      <UserPlus className="h-4 w-4" />
                    </Button>
                  )}
                </div>
              ) : <p className="py-2 text-sm font-medium">{q?.partnerName}</p>}
            </InlineField>
            <PartnerCreateDialog open={newCustomerOpen} onOpenChange={setNewCustomerOpen} onCreated={(c) => {
              // Use the new customer at once.
              setPartnerId(c.id); setPartnerName(c.name); setDirty(true);
            }} />
            {(contacts.length > 0 || contactId) && (
              <InlineField label={tx('Contact')}>
                <select className="h-9 w-full rounded-md border bg-background px-2 text-sm" disabled={!editable} value={contactId} onChange={(e) => { setContactId(e.target.value); setDirty(true); }}>
                  <option value="">{tx('None')}</option>
                  {contactId && !contacts.some((c) => c.id === contactId) && <option value={contactId}>{contactId}</option>}
                  {contacts.map((c) => <option key={c.id} value={c.id}>{[c.firstName, c.lastName].filter(Boolean).join(' ')}{c.email ? ` · ${c.email}` : ''}</option>)}
                </select>
              </InlineField>
            )}
            {(sites.length > 0 || siteId) && (
              <InlineField label={tx('Customer site')}>
                <select className="h-9 w-full rounded-md border bg-background px-2 text-sm" disabled={!editable} value={siteId} onChange={(e) => { setSiteId(e.target.value); setDirty(true); void priceLines(undefined, lines, { siteId: e.target.value }, true); }}>
                  <option value="">{tx('None')}</option>
                  {siteId && !sites.some((x) => x.id === siteId) && <option value={siteId}>{siteId}</option>}
                  {sites.map((x) => <option key={x.id} value={x.id}>{x.name}</option>)}
                </select>
              </InlineField>
            )}
            {(agreements.length > 0 || agreementId) && (
              <InlineField label={tx('Agreement')}>
                <select className="h-9 w-full rounded-md border bg-background px-2 text-sm" disabled={!editable} value={agreementId} onChange={(e) => { setAgreementId(e.target.value); setDirty(true); void priceLines(undefined, lines, { agreementId: e.target.value }, true); }}>
                  <option value="">{tx('None (automatic)')}</option>
                  {agreementId && !agreements.some((a) => a.id === agreementId) && <option value={agreementId}>{agreementId}</option>}
                  {agreements.map((a) => <option key={a.id} value={a.id}>{a.number} · {a.name}</option>)}
                </select>
              </InlineField>
            )}
            <InlineField label={tx('Delivery address')}><Input disabled={!editable} value={header.deliveryAddress} placeholder={tx('Same as customer')} onChange={(e) => { const deliveryAddress = e.target.value; setHeader((h) => ({ ...h, deliveryAddress })); setDirty(true); }} /></InlineField>
            <InlineField label={tx('Payment terms')}><HeaderSelect value={header.paymentTermId} options={opts.terms} disabled={!editable} none={tx('Customer default')} onChange={(paymentTermId) => { setHeader((h) => ({ ...h, paymentTermId })); setDirty(true); }} /></InlineField>
          </div>
          <div data-tour="sales.quote.dates">
            <InlineField label={tx('Expiration')}><Input type="date" disabled={!editable} value={validUntil} title={tx('After this date the quotation expires')} onChange={(e) => { setValidUntil(e.target.value); setDirty(true); }} /></InlineField>
            <InlineField label={tx('Quotation date')}><Input type="date" disabled={!editable} value={issueDate} onChange={(e) => { setIssueDate(e.target.value); setDirty(true); }} /></InlineField>
            <InlineField label={tx('Delivery date')}><Input type="date" disabled={!editable} value={header.commitmentDate} title={tx('The delivery date promised to the customer; it becomes the order commitment date')} onChange={(e) => { const commitmentDate = e.target.value; setHeader((h) => ({ ...h, commitmentDate })); setDirty(true); }} /></InlineField>
          </div>
        </div>

        <Tabs value={tab} onValueChange={setTab}>
          <div className="flex flex-wrap items-end justify-between gap-2 border-b px-5 pt-1.5">
            <TabsList className="h-auto justify-start gap-1 overflow-x-auto rounded-none bg-transparent p-0">
              {([['lines', tx('Order Lines')], ['other', tx('Other Info')]] as const).map(([v, label]) => (
                <TabsTrigger key={v} value={v} className="-mb-px rounded-none rounded-t-md border border-transparent px-4 py-2 data-[state=active]:border-border data-[state=active]:border-b-card data-[state=active]:bg-card data-[state=active]:shadow-none">{label}</TabsTrigger>
              ))}
            </TabsList>
            {tab === 'lines' && (
              <div className="mb-1.5 inline-flex overflow-hidden rounded-md border text-xs font-semibold" role="group" aria-label={tx('Tax display')}>
                {(['excl', 'incl'] as const).map((mode) => (
                  <button key={mode} type="button" onClick={() => chooseTaxMode(mode)} aria-pressed={taxIncluded === (mode === 'incl')}
                    title={editable ? (mode === 'incl' ? tx('Unit prices include tax') : tx('Unit prices exclude tax')) : (mode === 'incl' ? tx('Show amounts with tax') : tx('Show amounts without tax'))}
                    className={cn('px-3 py-1.5', mode === 'incl' && 'border-l', taxIncluded === (mode === 'incl') ? 'bg-primary/15 text-primary' : 'bg-muted/60 text-muted-foreground hover:text-foreground')}>
                    {mode === 'excl' ? tx('Tax Excl.') : tx('Tax Incl.')}
                  </button>
                ))}
              </div>
            )}
          </div>

          <TabsContent value="lines" className="mt-0 space-y-4 px-2 pb-5 pt-1 sm:px-5">
            <QuoteLinesTable lines={lines} editable={editable} useServer={useServer} currency={currency} taxIncluded={taxIncluded}
              products={products.data ?? []} taxes={taxes.data ?? []} uoms={uoms.data ?? []} canPrice={canWrite && editable}
              onPatch={patchLine} onProduct={setProduct} onUnit={setUnit} onReprice={(i) => void priceLines(i)}
              onRemove={(i) => { setLines((ls) => ls.filter((_, j) => j !== i)); setDirty(true); }}
              onMove={moveRow} onAdd={addRow} onCatalogAdd={catalogAdd} imageColumn={imagesOn ? imageColumn : undefined} />
            <div className="grid gap-6 pt-2 md:grid-cols-[minmax(0,1fr)_340px]">
              <Textarea disabled={!editable} rows={4} value={terms} placeholder={tx('Terms and conditions…')} aria-label={tx('Terms and conditions')}
                className="resize-y border-transparent bg-transparent px-0 shadow-none focus-visible:border-input focus-visible:px-3 disabled:cursor-default disabled:opacity-100"
                onChange={(e) => { setTerms(e.target.value); setDirty(true); }} />
              <div data-tour="sales.quote.totals" className="text-sm">
                {(editable || orderDisc.value > 0) && (
                  <div className="mb-2 flex items-center justify-between gap-2">
                    <span className="text-muted-foreground">{tx('Order discount')}</span>
                    <span className="flex items-center gap-1">
                      <Input aria-label={tx('Order discount')} type="number" min={0} step="any" disabled={!editable} className="h-8 w-24 text-right" value={orderDisc.value}
                        onChange={(e) => { setOrderDisc((d) => ({ ...d, value: Math.max(0, Number(e.target.value)) })); setDirty(true); }} />
                      <select aria-label={tx('Order discount type')} disabled={!editable} className="h-8 rounded-md border bg-background px-1 text-xs" value={orderDisc.kind}
                        onChange={(e) => { setOrderDisc((d) => ({ ...d, kind: e.target.value as 'percent' | 'amount' })); setDirty(true); }}>
                        <option value="percent">%</option><option value="amount">{currency ?? orgCurrency}</option>
                      </select>
                    </span>
                  </div>
                )}
                <div className="border-t pt-2">
                  <Row label={tx('Untaxed Amount') + ':'} value={<strong>{money(useServer ? q!.subtotal : est.sub, currency)}</strong>} />
                  {useServer && Number(q!.discountTotal) > 0 && <Row label={tx('Discount') + ':'} value={'− ' + money(q!.discountTotal, currency)} />}
                  {est.byTax.length > 1 || (est.byTax.length === 1 && !useServer)
                    ? est.byTax.map((t) => <Row key={t.label} label={t.label + ':'} value={money(t.amount, currency)} />)
                    : <Row label={(est.byTax[0]?.label ?? tx('Taxes')) + ':'} value={money(useServer ? q!.taxAmount : est.tax, currency)} />}
                </div>
                <div className="mt-2 border-t pt-2"><Row label={<strong>{tx('Total')}:</strong>} value={<strong className="text-lg">{money(useServer ? q!.total : est.total, currency)}</strong>} /></div>
                {dirty && q && <p className="mt-1 text-right text-xs text-amber-600">{tx('Estimate — save to recalculate')}</p>}
                {q && Number(q.maxDiscountPercent) > 0 && <p className="mt-2 text-right text-xs text-muted-foreground">{tx('Highest discount vs list')}: {Number(q.maxDiscountPercent).toFixed(1)}% · {tx('limit')} {lk?.settings.quoteMaxDiscountPercent}%</p>}
              </div>
            </div>
          </TabsContent>

          <TabsContent value="other" className="mt-0 p-5">
            <div className="grid gap-x-12 gap-y-6 md:grid-cols-2">
              <div>
                <SectionTitle>{tx('Sales')}</SectionTitle>
                <InlineField label={tx('Salesperson')}>
                  <OptionalSelect disabled={!editable} value={ownerId} onChange={(v) => { setOwnerId(v); setDirty(true); }} options={(lk?.users ?? []).map((u) => ({ value: u.id, label: u.name }))} placeholder={tx('Opportunity owner / me')} noneLabel={tx('Opportunity owner / me')} />
                </InlineField>
                <InlineField label={tx('Branch')}><HeaderSelect value={header.branchId} options={opts.branches} disabled={!editable} none={tx('From my scope')} onChange={(branchId) => { setHeader((h) => ({ ...h, branchId })); setDirty(true); }} /></InlineField>
                <InlineField label={tx('Currency')}>
                  {editable && opts.currencies.length > 0
                    ? <select className="h-9 w-full rounded-md border bg-background px-2 text-sm" value={currencyCode || currency || orgCurrency} onChange={(e) => { setCurrencyCode(e.target.value); if (e.target.value === orgCurrency) setExchangeRate(1); setDirty(true); void priceLines(undefined, lines, { currencyCode: e.target.value }, true); }}>
                        {opts.currencies.map((c) => <option key={c.id} value={c.code ?? c.id}>{c.code ?? c.name}</option>)}
                      </select>
                    : <span className="text-sm">{currency ?? orgCurrency}</span>}
                </InlineField>
                {(currency ?? orgCurrency) !== orgCurrency && (
                  <InlineField label={tx('Exchange rate')}><Input type="number" min={0} step="any" disabled={!editable} value={exchangeRate} title={`1 ${currency} = ? ${orgCurrency}`} onChange={(e) => { setExchangeRate(Number(e.target.value)); setDirty(true); }} /></InlineField>
                )}
                {opportunityId && <InlineField label={tx('Opportunity')}><Link to={`/crm/opportunities/${opportunityId}`} className="text-primary hover:underline">{q?.dealName ?? deal?.name ?? tx('Open')}</Link></InlineField>}
                {q && (
                  <>
                    <InlineField label={tx('Created')}><span>{date(q.createdAt)}</span></InlineField>
                    {q.approvedAt && <InlineField label={tx('Approved')}><span>{date(q.approvedAt)}</span></InlineField>}
                    {q.sentAt && <InlineField label={tx('Sent')}><span>{date(q.sentAt)}</span></InlineField>}
                    {q.acceptedAt && <InlineField label={tx('Accepted')}><span>{date(q.acceptedAt)}</span></InlineField>}
                  </>
                )}
              </div>
              <div>
                <SectionTitle>{tx('Customer credit')}</SectionTitle>
                {q?.credit ? (
                  <div className="text-sm">
                    {q.credit.creditHold && <p className="mb-2 flex items-center gap-1.5 text-destructive"><AlertTriangle className="h-4 w-4" />{tx('On credit hold')}</p>}
                    <Row label={tx('Credit limit')} value={Number(q.credit.creditLimit) > 0 ? money(q.credit.creditLimit, currency) : tx('None')} />
                    <Row label={tx('Open receivables')} value={money(q.credit.openReceivables, currency)} />
                    <Row label={tx('Overdue')} value={<span className={Number(q.credit.overdueReceivables) > 0 ? 'text-destructive' : ''}>{money(q.credit.overdueReceivables, currency)}</span>} />
                    {q.credit.headroom != null && <Row label={tx('Headroom')} value={money(q.credit.headroom, currency)} />}
                    <Link to={`/crm/customers/${q.partnerId}`} className="mt-2 inline-block text-xs text-primary hover:underline">{tx('Customer 360')} →</Link>
                  </div>
                ) : <p className="text-sm text-muted-foreground">{q ? tx('No credit information for this customer.') : tx('Save the quotation to see customer credit.')}</p>}
              </div>
              <div className="md:col-span-2">
                <SectionTitle>{tx('Notes')}</SectionTitle>
                <div className="grid gap-4 md:grid-cols-2">
                  <Field label={tx('Notes (printed on the quotation)')}><Textarea disabled={!editable} rows={3} value={notes} onChange={(e) => { setNotes(e.target.value); setDirty(true); }} /></Field>
                  <Field label={tx('Internal notes (never shown to the customer)')}><Textarea disabled={!editable} rows={3} value={internalNotes} onChange={(e) => { setInternalNotes(e.target.value); setDirty(true); }} /></Field>
                </div>
              </div>
            </div>
          </TabsContent>
        </Tabs>
      </section>

      {q && <RecordChatter contextType="SalesQuotation" contextId={q.id} />}

      {q && <QuoteShareDialog quotationId={q.id} status={status} open={shareOpen} onOpenChange={setShareOpen} />}
      {q && <QuoteEmailDialog q={q} open={emailOpen} onOpenChange={setEmailOpen} canShare={canEmail} onSent={() => void qc.invalidateQueries({ queryKey: ['crm-suite'] })} />}
      {q && <QuoteAcceptDialog q={q} open={acceptOpen} onOpenChange={setAcceptOpen} onDone={() => void qc.invalidateQueries({ queryKey: ['crm-suite'] })} />}
      {q && <QuoteRevisionsDialog q={q} open={revisionsOpen} onOpenChange={setRevisionsOpen} />}
      {q && <SaveTemplateDialog q={q} open={templateOpen} onOpenChange={setTemplateOpen} />}
      <Dialog open={dialog === 'reject' || dialog === 'decline'} onOpenChange={(v) => !v && setDialog(null)}>
        <DialogContent className="max-w-md">
          <DialogHeader><DialogTitle>{dialog === 'reject' ? tx('Reject quotation') : tx('Customer declined')}</DialogTitle></DialogHeader>
          {dialog === 'decline' && <Field label={tx('Lost reason')}><HeaderSelect value={lostReasonId} options={(lostReasons.data ?? []).filter((r) => r.isActive)} none={tx('Choose…')} onChange={setLostReasonId} /></Field>}
          <Field label={tx('Reason')}><Textarea rows={3} value={reason} onChange={(e) => setReason(e.target.value)} /></Field>
          <DialogFooter>
            <Button variant="outline" onClick={() => setDialog(null)}>{tx('Cancel')}</Button>
            <Button variant="destructive" onClick={() => { run(dialog === 'reject' ? 'reject' : 'decline', { reason, ...(dialog === 'decline' && lostReasonId ? { lostReasonId } : {}) }, tx('Recorded')); setDialog(null); }}>{tx('Confirm')}</Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      <Dialog open={dialog === 'convert'} onOpenChange={(v) => !v && setDialog(null)}>
        <DialogContent data-tour="sales.quote.convert-dialog" className="max-w-md">
          <DialogHeader><DialogTitle>{tx('Convert quotation')}</DialogTitle></DialogHeader>
          <div className="space-y-2 text-sm">
            <label data-tour="sales.quote.convert-target" className="flex items-start gap-2 rounded-lg border p-3"><input type="radio" className="mt-1" checked={target === 'sales_order'} onChange={() => setTarget('sales_order')} />
              <span><strong>{tx('Sales order')}</strong><span className="block text-xs text-muted-foreground">{tx('Creates a B2B sales order with the agreed prices: delivery, invoicing and changes are tracked on it.')}</span></span></label>
            <label className="flex items-start gap-2 rounded-lg border p-3"><input type="radio" className="mt-1" checked={target === 'order'} onChange={() => setTarget('order')} />
              <span><strong>{tx('Counter order')}</strong><span className="block text-xs text-muted-foreground">{tx('Creates an order; stock is checked and the order is invoiced from Orders.')}</span></span></label>
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
            <Button data-tour="sales.quote.convert-confirm" disabled={convert.isPending} onClick={() => convert.mutate({ id: q!.id, target, allowBackorder: backorder, idempotencyKey: convertKey(target, backorder) }, {
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

function SectionTitle({ children }: { children: ReactNode }) {
  return <h3 className="mb-2 border-b pb-1 text-xs font-semibold uppercase tracking-wide text-muted-foreground">{children}</h3>;
}

function Row({ label, value }: { label: ReactNode; value: ReactNode }) {
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

async function downloadBlob(path: string, name: string) {
  try {
    const r = await api.get(path, { responseType: 'blob' });
    const url = URL.createObjectURL(r.data as Blob);
    const a = document.createElement('a');
    a.href = url; a.download = name; a.click();
    URL.revokeObjectURL(url);
  } catch (err) { toast.error(apiErrorMessage(err, tx('Could not download'))); }
}

/**
 * Print the quotation PDF (laid out by Sales settings → Quotation layout) through a hidden frame, so Print, Download
 * PDF and the emailed PDF look the same. Opens the PDF in a tab when the browser cannot print a framed PDF.
 */
async function printPdf(path: string) {
  try {
    const r = await api.get(path, { responseType: 'blob' });
    const url = URL.createObjectURL(new Blob([r.data as Blob], { type: 'application/pdf' }));
    const frame = document.createElement('iframe');
    frame.style.cssText = 'position:fixed;right:0;bottom:0;width:0;height:0;border:0';
    frame.onload = () => {
      try {
        frame.contentWindow?.focus();
        frame.contentWindow?.print();
      } catch {
        window.open(url, '_blank');
      }
      setTimeout(() => { frame.remove(); URL.revokeObjectURL(url); }, 60_000);
    };
    frame.src = url;
    document.body.appendChild(frame);
  } catch (err) { toast.error(apiErrorMessage(err, tx('Could not print'))); }
}

import { useEffect, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { toast } from 'sonner';
import { PERMISSIONS } from '@erp/shared';
import { apiErrorMessage } from '@/lib/api-error';
import { HandoverDraftPanel } from '@/pages/sales/HandoverDetails';
import { emptyHandover, saveHandoverDraft, type HandoverForm } from '@/pages/sales/handover-draft';
import { useFieldArray, useForm } from 'react-hook-form';
import { Trash2, Loader2 } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Badge } from '@/components/ui/badge';
import { money } from '@/lib/format';
import { useAuthStore } from '@/stores/auth.store';
import { usePartners } from '@/features/partners/api';
import { useProducts } from '@/features/products/api';
import {
  useCreateInvoice,
  usePostInvoice,
  useInvoiceJournalPreview,
  type CreateInvoiceInput,
  type InvoiceJournalPreview,
} from '@/features/invoicing/api';
import {
  usePaymentTerms,
  useFiscalPositions,
  useJournals,
  useCompanySettings,
  type PaymentTerm,
  type Journal,
} from '@/features/accounting/api';
import { useHrEmployees, type HrEmployee } from '@/features/hr/api';
import { tx } from '@/lib/i18n/tx';
import { UnitSelect } from '@/components/unit-select';
import { LineColumnsPicker, useLineColumns, type LineColumn } from '@/components/line-columns';
import { SearchableSelect } from '@/components/ui/searchable-select';
import { defaultUnitId, unitPrice as priceForUnit } from '@/features/products/units';
import { resolveListPrice } from '@/features/invoicing/price-list-api';

/** Odoo-style flat field: no box, a hairline underline that turns blue on focus. */
const fieldClass =
  'h-8 w-full rounded-none border-0 border-b border-slate-200 bg-transparent px-1 text-sm shadow-none hover:border-slate-400 focus:border-blue-600 focus:outline-none focus-visible:ring-0 focus-visible:ring-offset-0';
const selectClass = fieldClass;
const selectClassSm = fieldClass;
/** Same flat field for line cells: invisible until hovered/focused. */
const cellClass =
  'h-8 w-full rounded-none border-0 border-b border-transparent bg-transparent px-1 text-sm shadow-none hover:border-slate-300 focus:border-blue-600 focus:outline-none focus-visible:ring-0 focus-visible:ring-offset-0';

/** Optional columns of the invoice lines editor (Odoo-style column picker). */
type LineCol = 'description' | 'uom' | 'discount';
const LINE_COLS: Array<LineColumn<LineCol>> = [['description', tx('Description')], ['uom', tx('UoM')], ['discount', tx('Disc.%')]];

/** Local date → yyyy-mm-dd without timezone shifting. */
function fmtLocal(d: Date): string {
  const y = d.getFullYear();
  const m = String(d.getMonth() + 1).padStart(2, '0');
  const day = String(d.getDate()).padStart(2, '0');
  return `${y}-${m}-${day}`;
}

/** Client-side mirror of the backend's due-date computation (PaymentTermService). */
export function dueDateForTerm(term: PaymentTerm | undefined, issueDate: string): string {
  if (!term || term.method === 'immediate') return issueDate;
  const base = new Date(`${issueDate}T00:00:00`);
  if (term.method === 'end_of_following_month') {
    const eom = new Date(base.getFullYear(), base.getMonth() + 2, 0);
    eom.setDate(eom.getDate() + term.netDays);
    return fmtLocal(eom);
  }
  base.setDate(base.getDate() + term.netDays);
  return fmtLocal(base);
}

/** Display labels for the back-office invoice payment method (InvoicePaymentMode). */
const PAYMENT_MODE_LABELS: Record<string, string> = {
  cash: tx('Cash'),
  card: tx('Card'),
  mobile_money: tx('Mobile Money'),
  mixed: tx('Mixed'),
  credit: tx('Credit (house account)'),
};

const INCOTERM_OPTIONS = ['EXW', 'FCA', 'CPT', 'CIP', 'DAP', 'DPU', 'DDP', 'FAS', 'FOB', 'CFR', 'CIF'];

/** Odoo notebook tab: the active tab is a raised, bordered page. */
function TabBtn({ active, children, onClick }: { active: boolean; children: React.ReactNode; onClick: () => void }) {
  return (
    <button
      type="button"
      onClick={onClick}
      className={`-mb-px whitespace-nowrap rounded-t-md border px-5 py-2.5 text-sm transition-colors ${
        active
          ? 'border-slate-200 border-b-white bg-white font-medium text-slate-900'
          : 'border-transparent text-slate-500 hover:text-blue-700'
      }`}
    >
      {children}
    </button>
  );
}

/** Totals row under the lines (right-aligned, Odoo style). */
function SummaryRow({ label, value, bold, accent }: { label: string; value: string; bold?: boolean; accent?: boolean }) {
  return (
    <div className={`grid grid-cols-[1fr_auto] gap-6 py-0.5 text-sm ${accent ? 'text-blue-700' : 'text-slate-700'}`}>
      <span className="text-right">{label}:</span>
      <span className={`min-w-[7rem] text-right tabular-nums ${bold ? 'font-bold text-slate-900' : ''}`}>{value}</span>
    </div>
  );
}

/** Header field: bold label on the left, flat control on the right. */
function MetaRow({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div className="grid min-h-12 grid-cols-[9rem_1fr] items-center gap-3 py-1.5">
      <span className="text-sm font-semibold text-slate-800">{label}</span>
      <div className="min-w-0">{children}</div>
    </div>
  );
}

interface LineForm {
  /** 'product' (default) | 'section' | 'note' (Odoo-style display rows). */
  lineType?: string;
  productId?: string;
  description?: string;
  quantity: number;
  unitPrice?: number;
  discountPercent?: number;
  /** Product unit (ADR-S003): Pack, Box, … defaults to the product's sales unit. */
  uomId?: string;
}
interface FormValues {
  partnerId: string;
  issueDate: string;
  dueDate?: string;
  paymentTermId?: string;
  /* Other Info (Odoo-style sale attributes) */
  paymentMode?: string;
  fiscalPositionId?: string;
  invoicingJournalId?: string;
  salespersonId?: string;
  sourceDocument?: string;
  /* Delivery */
  deliveryDate?: string;
  deliveryAddress?: string;
  incoterm?: string;
  incotermLocation?: string;
  reference?: string;
  lines: LineForm[];
}

export function InvoiceCreatePage() {
  const navigate = useNavigate();
  const partners = usePartners({ page: 1, pageSize: 200 });
  const products = useProducts({ page: 1, pageSize: 200 });
  const { data: paymentTerms } = usePaymentTerms();
  const { data: fiscalPositions } = useFiscalPositions();
  const { data: journalsData } = useJournals();
  const { data: employeesData } = useHrEmployees({ pageSize: 200 });
  const createInvoice = useCreateInvoice();
  const [handover, setHandover] = useState<HandoverForm>(() => emptyHandover());
  const canUpdateInvoice = useAuthStore((s) => s.hasPermission(PERMISSIONS.invoice.update));
    const postInvoice = usePostInvoice();
    const currency = useAuthStore((s) => s.organization?.currencyCode ?? 'IDR');
    const companySettingsQ = useCompanySettings();
    const journalPreview = useInvoiceJournalPreview();
    const [preview, setPreview] = useState<InvoiceJournalPreview | null>(null);
    const [previewError, setPreviewError] = useState<string | null>(null);
    const [previewPending, setPreviewPending] = useState(false);
    const defaultJournalApplied = useRef(false);

  const today = new Date().toISOString().slice(0, 10);
  const { register, control, handleSubmit, watch, setValue, getValues } = useForm<FormValues>({
    defaultValues: {
      partnerId: '',
      issueDate: today,
      dueDate: '',
      paymentTermId: '',
      paymentMode: '',
      fiscalPositionId: '',
      invoicingJournalId: '',
      salespersonId: '',
      sourceDocument: '',
      deliveryDate: '',
      deliveryAddress: '',
      incoterm: '',
      incotermLocation: '',
      reference: '',
      lines: [{ quantity: 1 }],
    },
  });
  const { fields, append, remove } = useFieldArray({ control, name: 'lines' });
  const lines = watch('lines');
  const { cols, show, toggle: toggleCol } = useLineColumns<LineCol>('invoice-edit.columns', LINE_COLS, ['description', 'uom', 'discount']);
  const colCount = 5 + LINE_COLS.filter(([c]) => show(c)).length;
  const productList = products.data?.data ?? [];
  const terms = paymentTerms?.filter((t) => t.isActive) ?? [];
  const fiscalPositionList = (fiscalPositions ?? []).filter((f) => f.isActive);
  const journalList = (journalsData?.data ?? []).filter(
    (j: Journal) => j.isActive && j.journalType === 'sales',
  );
  const salespersonList = (employeesData?.data ?? []) as HrEmployee[];

  /* When a payment term is chosen, auto-fill the due date from its method. */
  const selectedTermId = watch('paymentTermId');
  const issueDate = watch('issueDate');
  const partnerId = watch('partnerId');
  const invoicingJournalId = watch('invoicingJournalId');
  const selectedTerm = terms.find((t) => t.id === selectedTermId);
  const [activeTab, setActiveTab] = useState<'lines' | 'info' | 'delivery' | 'journal'>('lines');
  useEffect(() => {
    if (selectedTermId && issueDate) {
      setValue('dueDate', dueDateForTerm(selectedTerm, issueDate));
    }
  }, [selectedTermId, issueDate, selectedTerm, setValue]);

  /* Preselect the org's Default Sales Journal (Company Settings) in the
     "Invoicing Journal" picker when the user hasn't chosen one yet — so the
     entry is "automatic in the default sales journal" by default. */
  useEffect(() => {
    if (defaultJournalApplied.current || !companySettingsQ.data) return;
    defaultJournalApplied.current = true;
    const defId = companySettingsQ.data.defaultSalesJournalId;
    if (defId && !watch('invoicingJournalId')) setValue('invoicingJournalId', defId);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [companySettingsQ.data, invoicingJournalId, setValue]);

  /* Live, read-only preview of the journal entry that Save & Post will create.
     Uses getValues() when the debounce fires (watch('lines') from a useFieldArray
     can lag nested setValue calls), so the payload always carries the current
     line selections. */
  useEffect(() => {
    if (activeTab !== 'journal') return;
    let cancelled = false;
    const values = getValues();
    if (!values.partnerId) {
      setPreview(null);
      setPreviewError(null);
      return;
    }
    const payload: CreateInvoiceInput = {
      partnerId: values.partnerId,
      issueDate: values.issueDate || today,
      invoicingJournalId: values.invoicingJournalId || undefined,
      lines: (values.lines ?? [])
        .filter((l) => l.productId)
        .map((l) => ({
          lineType: l.lineType ?? 'product',
          productId: l.productId,
          description: l.description || undefined,
          quantity: Number.isFinite(l.quantity) ? Number(l.quantity) : 1,
          unitPrice: Number.isFinite(l.unitPrice) ? Number(l.unitPrice) : undefined,
          discountPercent: Number.isFinite(l.discountPercent) ? Number(l.discountPercent) : undefined,
          uomId: l.uomId || undefined,
        })),
    };
    const t = setTimeout(async () => {
      setPreviewPending(true);
      try {
        const data = await journalPreview.mutateAsync(payload);
        if (!cancelled) {
          setPreview(data);
          setPreviewError(null);
        }
      } catch (e: any) {
        if (!cancelled) setPreviewError(e?.response?.data?.message ?? e?.message ?? tx('Could not compute the entry'));
      } finally {
        if (!cancelled) setPreviewPending(false);
      }
    }, 400);
    return () => {
      cancelled = true;
      clearTimeout(t);
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [activeTab, partnerId, invoicingJournalId, lines]);

  const lineTotal = (l?: LineForm): number => {
    if (!l) return 0;
    const product = productList.find((p) => p.id === l.productId);
    const price = Number.isFinite(l.unitPrice)
      ? Number(l.unitPrice)
      : product?.salesPrice != null
        ? Number(product.salesPrice)
        : 0;
    const qty = Number.isFinite(l.quantity) ? Number(l.quantity) : 0;
    const disc = Number.isFinite(l.discountPercent) ? Number(l.discountPercent) : 0;
    return qty * price * (1 - disc / 100);
  };
  const subtotal = (lines ?? []).reduce((s, l) => s + lineTotal(l), 0);

  const onProductChange = (index: number, productId: string) => {
    setValue(`lines.${index}.productId`, productId);
    const p = productList.find((x) => x.id === productId);
    if (p) {
      setValue(`lines.${index}.description`, p.name);
      setValue(`lines.${index}.uomId`, defaultUnitId(p, 'sales'));
      if (p.salesPrice != null) setValue(`lines.${index}.unitPrice`, Number(p.salesPrice));
      // SALES-3: the customer's price list replaces the catalogue price when one applies.
      void resolveListPrice({
        partnerId: getValues('partnerId'), productId, quantity: Number(getValues(`lines.${index}.quantity`)) || 1,
        uomId: defaultUnitId(p, 'sales'),
      }).then((listed) => {
        if (listed?.unitPrice != null) setValue(`lines.${index}.unitPrice`, listed.unitPrice);
        if (listed && listed.discountPercent > 0) setValue(`lines.${index}.discountPercent`, listed.discountPercent);
      });
    }
  };

  const onUnitChange = (index: number, uomId: string) => {
    setValue(`lines.${index}.uomId`, uomId);
    const p = productList.find((x) => x.id === getValues(`lines.${index}.productId`));
    const price = priceForUnit(p, uomId, 'sales');
    if (price !== null) setValue(`lines.${index}.unitPrice`, Number(price.toFixed(6)));
  };

  const onSubmit = handleSubmit(async (values, event) => {
    const submitter = (event?.nativeEvent as SubmitEvent | undefined)?.submitter as
      | HTMLButtonElement
      | undefined;
    const action = submitter?.value ?? 'save';

    const payload: CreateInvoiceInput = {
      partnerId: values.partnerId,
      issueDate: values.issueDate,
      dueDate: values.dueDate || undefined,
      paymentTermId: values.paymentTermId || undefined,
      paymentMode: values.paymentMode || undefined,
      fiscalPositionId: values.fiscalPositionId || undefined,
      invoicingJournalId: values.invoicingJournalId || undefined,
      salespersonId: values.salespersonId || undefined,
      deliveryDate: handover.scheduledDate || values.deliveryDate || undefined,
      deliveryAddress: (handover.method === 'delivery' ? handover.address : '') || values.deliveryAddress || undefined,
      incoterm: values.incoterm || undefined,
      incotermLocation: values.incotermLocation || undefined,
      sourceDocument: values.sourceDocument || undefined,
      reference: values.reference || undefined,
      lines: values.lines.map((l) => ({
        lineType: l.lineType ?? 'product',
        productId: l.productId || undefined,
        description: l.description || undefined,
        quantity: Number.isFinite(l.quantity) ? Number(l.quantity) : 1,
        unitPrice: Number.isFinite(l.unitPrice) ? Number(l.unitPrice) : undefined,
        discountPercent: Number.isFinite(l.discountPercent) ? Number(l.discountPercent) : undefined,
        uomId: l.productId ? l.uomId || undefined : undefined,
      })),
    };

    const invoice = await createInvoice.mutateAsync(payload);
    if (action === 'post') await postInvoice.mutateAsync(invoice.id);
    if (canUpdateInvoice) {
      try { await saveHandoverDraft(`/sales/invoices/${invoice.id}/handover`, handover); }
      catch (e) { toast.error(apiErrorMessage(e, tx('Invoice saved, but the delivery details could not be saved'))); }
    }
    navigate(`/invoices/${invoice.id}`);
  });

  const busy = createInvoice.isPending || postInvoice.isPending;

  const partnerOptions = (partners.data?.data ?? []).map((p) => ({ value: p.id, label: p.name, code: p.code ?? undefined }));
  const linkBtn = 'text-sm text-blue-600 hover:text-blue-800 hover:underline';
  const thBase = 'py-2.5 px-2 text-sm font-semibold text-slate-800';

  return (
    <form onSubmit={onSubmit} className="mx-auto max-w-[1400px] px-1 md:px-2">
      {/* Breadcrumb */}
      <div className="mb-3 flex items-center gap-2 text-sm">
        <button type="button" onClick={() => navigate('/invoices')} className="text-blue-600 hover:text-blue-800">
          {tx('Invoices')}
        </button>
        <span className="text-slate-400">/</span>
        <span className="text-slate-700">{tx('New')}</span>
      </div>

      {/* Control panel: actions left, status bar right */}
      <div className="mb-3 flex flex-wrap items-center justify-between gap-3">
        <div className="flex gap-2">
          <Button
            type="submit"
            name="action"
            value="post"
            size="sm"
            className="bg-blue-600 px-4 font-medium text-white hover:bg-blue-700"
            disabled={busy}
          >
            {tx('Confirm')}
          </Button>
          <Button
            type="submit"
            name="action"
            value="save"
            size="sm"
            variant="secondary"
            className="bg-slate-100 px-4 text-slate-800 hover:bg-slate-200"
            disabled={busy}
          >
            {tx('Save draft')}
          </Button>
          <Button
            type="button"
            size="sm"
            variant="ghost"
            className="text-slate-600"
            onClick={() => navigate('/invoices')}
            disabled={busy}
          >
            {tx('Discard')}
          </Button>
        </div>
        <div className="flex overflow-hidden rounded-md border border-slate-200 text-sm">
          <span className="bg-blue-50 px-4 py-1.5 font-semibold text-blue-700">{tx('Draft')}</span>
          <span className="border-l border-slate-200 px-4 py-1.5 text-slate-400">{tx('Posted')}</span>
        </div>
      </div>

      {/* Document sheet */}
      <div className="rounded-md border border-slate-200 bg-white shadow-sm">
        <div className="px-6 pt-6 md:px-8">
          <div className="text-sm font-medium text-slate-700">{tx('Customer Invoice')}</div>
          <h1 className="mt-1 text-3xl font-bold text-slate-900">{tx('Draft')}</h1>

          {/* Header fields: customer left, dates & terms right */}
          <div className="mt-5 grid grid-cols-1 gap-x-12 md:grid-cols-2">
            <div>
              <MetaRow label={tx('Customer')}>
                <input type="hidden" {...register('partnerId', { required: true })} />
                <SearchableSelect
                  value={watch('partnerId') ?? ''}
                  onValueChange={(v) => setValue('partnerId', v, { shouldValidate: true, shouldDirty: true })}
                  options={partnerOptions}
                  placeholder={tx('Search a customer…')}
                  searchPlaceholder={tx('Search customers…')}
                  className={fieldClass}
                />
              </MetaRow>
              <MetaRow label={tx('Reference')}>
                <Input id="reference" className={fieldClass} {...register('reference')} />
              </MetaRow>
              <MetaRow label={tx('Salesperson')}>
                <select id="salespersonId" className={selectClassSm} {...register('salespersonId')}>
                  <option value="">{tx('— Not assigned —')}</option>
                  {salespersonList.map((e) => (
                    <option key={e.id} value={e.id}>
                      {e.firstName} {e.lastName ?? ''}
                    </option>
                  ))}
                </select>
              </MetaRow>
              <MetaRow label={tx('Source Doc')}>
                <Input id="sourceDocument" placeholder={tx('e.g. SO/0012')} className={fieldClass} {...register('sourceDocument')} />
              </MetaRow>
            </div>
            <div>
              <MetaRow label={tx('Invoice Date')}>
                <Input id="issueDate" type="date" className={fieldClass} {...register('issueDate', { required: true })} />
              </MetaRow>
              <MetaRow label={tx('Payment terms')}>
                <select id="paymentTermId" className={selectClassSm} {...register('paymentTermId')}>
                  <option value="">{tx('No payment terms')}</option>
                  {terms.map((t) => (
                    <option key={t.id} value={t.id}>
                      {t.name}
                    </option>
                  ))}
                </select>
              </MetaRow>
              <MetaRow label={tx('Due Date')}>
                <Input id="dueDate" type="date" className={fieldClass} {...register('dueDate')} />
              </MetaRow>
              <MetaRow label={tx('Currency')}>
                <span className="px-1 text-sm text-slate-700">{currency}</span>
              </MetaRow>
            </div>
          </div>
          {selectedTerm && (
            <p className="mt-1 text-xs text-slate-500">
              {tx('Due date derived from')} <span className="font-medium text-slate-700">{selectedTerm.name}</span>
            </p>
          )}
        </div>

        {/* Notebook tabs */}
        <div className="mt-6 flex gap-1 overflow-x-auto border-b border-slate-200 px-6 md:px-8">
          <TabBtn active={activeTab === 'lines'} onClick={() => setActiveTab('lines')}>
            {tx('Invoice Lines')}
          </TabBtn>
          <TabBtn active={activeTab === 'journal'} onClick={() => setActiveTab('journal')}>
            {tx('Journal Items')}
          </TabBtn>
          <TabBtn active={activeTab === 'info'} onClick={() => setActiveTab('info')}>
            {tx('Other Info')}
          </TabBtn>
          <TabBtn active={activeTab === 'delivery'} onClick={() => setActiveTab('delivery')}>
            {tx('Delivery')}
          </TabBtn>
        </div>

        {/* INVOICE LINES tab */}
        {activeTab === 'lines' && (
          <div className="pb-6">
            <div className="overflow-x-auto">
              <table className="w-full min-w-[820px] text-sm">
                <thead className="border-b border-slate-200 text-left">
                  <tr>
                    <th className="w-8" />
                    <th className={thBase}>{tx('Product')}</th>
                    {show('description') && <th className={thBase}>{tx('Description')}</th>}
                    <th className={`${thBase} w-24 text-right`}>{tx('Quantity')}</th>
                    {show('uom') && <th className={`${thBase} w-28`}>{tx('UoM')}</th>}
                    <th className={`${thBase} w-32 text-right`}>{tx('Price')}</th>
                    {show('discount') && <th className={`${thBase} w-24 text-right`}>{tx('Disc.%')}</th>}
                    <th className={`${thBase} w-36 text-right`}>{tx('Amount')}</th>
                    <th className="w-14 py-2.5 pr-6 text-right">
                      <LineColumnsPicker columns={LINE_COLS} cols={cols} toggle={toggleCol} />
                    </th>
                  </tr>
                </thead>
                <tbody>
                  {fields.map((field, index) => {
                    const rowType = watch(`lines.${index}.lineType`) ?? 'product';
                    const isMeta = rowType === 'section' || rowType === 'note';
                    const removeBtn = (
                      <button
                        type="button"
                        aria-label={tx('Remove line')}
                        onClick={() => remove(index)}
                        disabled={fields.length <= 1}
                        className="rounded p-1 text-slate-400 opacity-0 transition-opacity hover:text-red-600 group-hover:opacity-100 disabled:hidden"
                      >
                        <Trash2 className="h-4 w-4" />
                      </button>
                    );
                    if (isMeta) {
                      return (
                        <tr key={field.id} className={`group border-b border-slate-100 ${rowType === 'section' ? 'bg-slate-50' : ''}`}>
                          <td />
                          <td colSpan={colCount - 1} className="px-2 py-1.5">
                            <input
                              aria-label={rowType === 'section' ? tx('Section title') : tx('Note')}
                              className={`${cellClass} ${rowType === 'section' ? 'font-semibold' : 'italic text-slate-600'}`}
                              placeholder={rowType === 'section' ? tx('e.g. Services') : tx('e.g. Courtesy discount approved by manager')}
                              {...register(`lines.${index}.description`)}
                            />
                          </td>
                          <td className="py-1.5 pr-6 text-right">{removeBtn}</td>
                        </tr>
                      );
                    }
                    return (
                      <tr key={field.id} className="group border-b border-slate-100 align-middle hover:bg-slate-50/60">
                        <td className="pl-3 text-slate-300">⠿</td>
                        <td className="px-2 py-1.5">
                          <select
                            aria-label={tx('Product')}
                            className={cellClass}
                            value={watch(`lines.${index}.productId`) ?? ''}
                            onChange={(e) => onProductChange(index, e.target.value)}
                          >
                            <option value="">{tx('Select a product')}</option>
                            {productList.map((p) => (
                              <option key={p.id} value={p.id}>
                                {p.name}
                              </option>
                            ))}
                          </select>
                        </td>
                        {show('description') && (
                          <td className="px-2 py-1.5">
                            <input aria-label={tx('Description')} className={cellClass} {...register(`lines.${index}.description`)} />
                          </td>
                        )}
                        <td className="px-2 py-1.5">
                          <input aria-label={tx('Quantity')} type="number" step="any" className={`${cellClass} text-right tabular-nums`} {...register(`lines.${index}.quantity`, { valueAsNumber: true })} />
                        </td>
                        {show('uom') && (
                          <td className="px-2 py-1.5">
                            <UnitSelect
                              className={cellClass}
                              product={productList.find((p) => p.id === watch(`lines.${index}.productId`))}
                              role="sales"
                              value={watch(`lines.${index}.uomId`)}
                              onChange={(uomId) => onUnitChange(index, uomId)}
                            />
                          </td>
                        )}
                        <td className="px-2 py-1.5">
                          <input aria-label={tx('Unit price')} type="number" step="any" className={`${cellClass} text-right tabular-nums`} {...register(`lines.${index}.unitPrice`, { valueAsNumber: true })} />
                        </td>
                        {show('discount') && (
                          <td className="px-2 py-1.5">
                            <input
                              aria-label={tx('Disc.%')}
                              type="number"
                              step="any"
                              className={`${cellClass} text-right tabular-nums`}
                              {...register(`lines.${index}.discountPercent`, { valueAsNumber: true })}
                            />
                          </td>
                        )}
                        <td className="px-2 py-1.5 text-right tabular-nums text-slate-900">{money(lineTotal(lines?.[index]))}</td>
                        <td className="py-1.5 pr-6 text-right">{removeBtn}</td>
                      </tr>
                    );
                  })}
                  <tr className="border-b border-slate-100">
                    <td />
                    <td colSpan={colCount} className="px-2 py-2.5">
                      <div className="flex flex-wrap gap-6">
                        <button type="button" className={linkBtn} onClick={() => append({ quantity: 1 })}>
                          {tx('Add a line')}
                        </button>
                        <button type="button" className={linkBtn} onClick={() => append({ lineType: 'section', description: '', quantity: 0 })}>
                          {tx('Add a section')}
                        </button>
                        <button type="button" className={linkBtn} onClick={() => append({ lineType: 'note', description: '', quantity: 0 })}>
                          {tx('Add a note')}
                        </button>
                      </div>
                    </td>
                  </tr>
                </tbody>
              </table>
            </div>

            {/* Footer: note left, totals right */}
            <div className="mt-8 flex flex-col gap-6 px-6 md:flex-row md:items-start md:justify-between md:px-8">
              <p className="max-w-md text-sm text-slate-400">{tx('Tax is applied from each product when the invoice is posted.')}</p>
              <div className="w-full border-t border-slate-300 pt-2 md:w-80">
                <SummaryRow label={tx('Untaxed Amount')} value={money(subtotal)} bold />
                <div className="mt-1 border-t border-slate-200 pt-1">
                  <SummaryRow label={tx('Total')} value={money(subtotal)} bold />
                </div>
                <SummaryRow label={tx('Amount Due')} value={money(subtotal)} bold accent={subtotal > 0.005} />
              </div>
            </div>
          </div>
        )}

        {/* OTHER INFO tab */}
        {activeTab === 'info' && (
          <div className="grid grid-cols-1 gap-x-12 px-6 py-6 md:grid-cols-2 md:px-8">
            <div>
              <h3 className="mb-2 text-xs font-semibold uppercase tracking-wide text-slate-500">{tx('Accounting')}</h3>
              <MetaRow label={tx('Fiscal Position')}>
                <select id="fiscalPositionId" className={selectClass} {...register('fiscalPositionId')}>
                  <option value="">{tx('— No fiscal position —')}</option>
                  {fiscalPositionList.map((f) => (
                    <option key={f.id} value={f.id}>
                      {f.name}
                    </option>
                  ))}
                </select>
              </MetaRow>
              <MetaRow label={tx('Invoicing Journal')}>
                <select id="invoicingJournalId" className={selectClass} {...register('invoicingJournalId')}>
                  <option value="">{tx('— Default sales journal —')}</option>
                  {journalList.map((j) => (
                    <option key={j.id} value={j.id}>
                      {j.name}
                    </option>
                  ))}
                </select>
              </MetaRow>
            </div>
            <div>
              <h3 className="mb-2 text-xs font-semibold uppercase tracking-wide text-slate-500">{tx('Payment')}</h3>
              <MetaRow label={tx('Payment Method')}>
                <select id="paymentMode" className={selectClass} {...register('paymentMode')}>
                  <option value="">{tx('— Not set —')}</option>
                  {Object.entries(PAYMENT_MODE_LABELS).map(([value, label]) => (
                    <option key={value} value={value}>
                      {label}
                    </option>
                  ))}
                </select>
              </MetaRow>
            </div>
          </div>
        )}

        {/* DELIVERY tab */}
        {activeTab === 'delivery' && (
          <div className="px-6 py-6 md:px-8">
            <HandoverDraftPanel value={handover} onChange={setHandover} hint={tx('Plan how the goods reach the customer. Saved with the invoice; dispatch and delivery follow once it is posted.')} />
            <h3 className="mb-2 mt-6 text-xs font-semibold uppercase tracking-wide text-slate-500">{tx('Shipping terms')}</h3>
            <div className="grid grid-cols-1 gap-x-12 md:grid-cols-2">
              <div>
                <MetaRow label={tx('Incoterm')}>
                  <select id="incoterm" className={selectClass} {...register('incoterm')}>
                    <option value="">{tx('— None —')}</option>
                    {INCOTERM_OPTIONS.map((code) => (
                      <option key={code} value={code}>
                        {code}
                      </option>
                    ))}
                  </select>
                </MetaRow>
                <MetaRow label={tx('Incoterm Location')}>
                  <Input id="incotermLocation" placeholder={tx('e.g. Jakarta (ID)')} className={fieldClass} {...register('incotermLocation')} />
                </MetaRow>
              </div>
            </div>
          </div>
        )}

        {/* JOURNAL ITEMS tab — read-only preview of the entry created on post */}
        {activeTab === 'journal' && (
          <div className="space-y-4 px-6 py-6 md:px-8">
            <p className="text-sm text-slate-500">
              {tx('Preview of the journal entry created when this invoice is posted: the customer receivable is debited and the income and tax accounts of each line are credited, in the Invoicing Journal chosen under Other Info (or the default sales journal).')}
            </p>

            {!partnerId || !(lines ?? []).some((l) => l.productId) ? (
              <div className="py-12 text-center text-sm text-slate-400">
                {tx('Select a customer and add at least one product line to preview the journal entry.')}
              </div>
            ) : previewError ? (
              <div className="rounded-md border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-700">{previewError}</div>
            ) : preview ? (
              <div>
                <div className="mb-3 flex flex-wrap items-center gap-x-8 gap-y-1 text-sm">
                  <span>
                    <span className="font-semibold text-slate-800">{tx('Journal')}</span>{' '}
                    <span className="text-slate-700">{preview.journal.name} ({preview.journal.code})</span>
                  </span>
                  <span>
                    <span className="font-semibold text-slate-800">{tx('Entry Date')}</span>{' '}
                    <span className="text-slate-700">{preview.postingDate}</span>
                  </span>
                  <Badge
                    variant="outline"
                    className={preview.balanced ? 'border-blue-200 bg-blue-50 text-blue-700' : 'border-red-200 bg-red-50 text-red-700'}
                  >
                    {preview.balanced ? tx('Balanced') : tx('Unbalanced')}
                  </Badge>
                  {previewPending && (
                    <span className="flex items-center gap-1 text-xs text-slate-400">
                      <Loader2 className="h-3 w-3 animate-spin" /> {tx('updating…')}
                    </span>
                  )}
                </div>
                <div className="overflow-x-auto">
                  <table className="w-full text-sm">
                    <thead className="border-b border-slate-200 text-left">
                      <tr>
                        <th className={thBase}>{tx('Account')}</th>
                        <th className={thBase}>{tx('Label')}</th>
                        <th className={thBase}>{tx('Partner')}</th>
                        <th className={`${thBase} text-right`}>{tx('Debit')}</th>
                        <th className={`${thBase} text-right`}>{tx('Credit')}</th>
                      </tr>
                    </thead>
                    <tbody>
                      {preview.lines.map((line) => (
                        <tr key={line.lineNumber} className="border-b border-slate-100">
                          <td className="px-2 py-2 text-slate-900">
                            {line.account ? `${line.account.code} ${line.account.name}` : <span className="text-slate-400">—</span>}
                          </td>
                          <td className="px-2 py-2 text-slate-700">{line.description ?? '—'}</td>
                          <td className="px-2 py-2 text-slate-500">{line.partnerName ?? '—'}</td>
                          <td className="px-2 py-2 text-right tabular-nums">{line.debit ? money(Number(line.debit)) : ''}</td>
                          <td className="px-2 py-2 text-right tabular-nums">{line.credit ? money(Number(line.credit)) : ''}</td>
                        </tr>
                      ))}
                      <tr className="font-semibold text-slate-900">
                        <td colSpan={3} />
                        <td className="px-2 py-2 text-right tabular-nums">{money(Number(preview.debitTotal))}</td>
                        <td className="px-2 py-2 text-right tabular-nums">{money(Number(preview.creditTotal))}</td>
                      </tr>
                    </tbody>
                  </table>
                </div>
                <p className="mt-2 text-xs text-slate-400">
                  {tx('Preview only — the entry number and sequence are assigned when the invoice is posted.')}
                </p>
              </div>
            ) : (
              <div className="flex items-center gap-2 py-12 text-sm text-slate-400">
                <Loader2 className="h-4 w-4 animate-spin" /> {tx('Computing journal entry…')}
              </div>
            )}
          </div>
        )}
      </div>
    </form>
  );
}

import { useMemo, useState, type ReactNode } from 'react';
import { GripVertical, ImageOff, Minus, PackageCheck, PackageX, Plus, Search, Sparkles, Trash2 } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Textarea } from '@/components/ui/textarea';
import { SearchableSelect } from '@/components/ui/searchable-select';
import { Dialog, DialogContent, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { LineColumnsPicker, useLineColumns, type LineColumn } from '@/components/line-columns';
import { UnitSelect } from '@/components/unit-select';
import { VariantSelect } from '@/features/products/variant-select';
import { LineSizeFields, hasSize, useProductSizing, type LineSize } from '@/features/products/sizing';
import type { VariantOption } from '@/features/products/variants-api';
import { unitFactor } from '@/features/products/units';
import type { ProductOption, QuoteLine as ApiQuoteLine } from '@/features/crm/suite-api';

/** A quotation line with its typed size (ADR-S066: sold by length / area / volume). */
export type QuoteLine = ApiQuoteLine & { size?: LineSize };
import { money } from '@/lib/format';
import { resolveAssetUrl } from '@/lib/api';
import { tx } from '@/lib/i18n/tx';
import { cn } from '@/lib/utils';
import { IMAGE_COL, isLayout, lineAmounts, type ImageCol, type TaxOption } from './quote-lines';

export type QuoteCol = 'product' | 'variant' | 'uom' | 'leadTime' | 'discount' | 'taxes' | 'unitCost' | 'margin' | 'marginPct'
  | 'taxExcl' | 'taxIncl';
const COLS: ReadonlyArray<LineColumn<QuoteCol>> = [
  ['product', tx('Product')], ['variant', tx('Product Variant')], ['uom', tx('Unit of Measure')], ['leadTime', tx('Lead Time')],
  ['discount', tx('Disc.%')], ['taxes', tx('Taxes')], ['unitCost', tx('Unit Cost')], ['margin', tx('Margin')],
  ['marginPct', tx('Margin (%)')], ['taxExcl', tx('Tax Excl.')], ['taxIncl', tx('Tax Incl.')],
];
const DEFAULT_COLS: QuoteCol[] = ['uom', 'discount', 'taxes'];

/** Product thumbnail in a quotation line; a muted placeholder when the product has no picture. */
function LineImage({ src, alt }: { src?: string | null; alt: string }) {
  const [broken, setBroken] = useState(false);
  const url = src && !broken ? resolveAssetUrl(src) : undefined;
  return url
    ? <a href={url} target="_blank" rel="noreferrer" title={alt}><img src={url} alt={alt} loading="lazy" onError={() => setBroken(true)} className="h-12 w-12 rounded border bg-white object-contain" /></a>
    : <span className="flex h-12 w-12 items-center justify-center rounded border border-dashed text-muted-foreground/50" title={tx('No image')}><ImageOff className="h-4 w-4" /></span>;
}

const SOURCE_LABEL: Record<string, string> = {
  list: tx('List price'), rule: tx('Price rule'), customer: tx('Customer discount'), manual: tx('Manual'),
  pricelist: tx('Price list'), agreement: tx('Agreement'),
};

interface Props {
  lines: QuoteLine[];
  editable: boolean;
  /** Use the saved server amounts (no unsaved edits). */
  useServer: boolean;
  currency?: string;
  /** Amount column shows tax-included totals. */
  taxIncluded: boolean;
  products: ProductOption[];
  taxes: TaxOption[];
  uoms: Array<{ id: string; code: string }>;
  canPrice: boolean;
  onPatch: (i: number, p: Partial<QuoteLine>, keepSource?: boolean) => void;
  onProduct: (i: number, productId: string | null) => void;
  onUnit: (i: number, uomId: string) => void;
  onReprice: (i?: number) => void;
  onRemove: (i: number) => void;
  onMove: (from: number, to: number) => void;
  onAdd: (kind: 'line' | 'section' | 'note' | 'charge') => void;
  onCatalogAdd: (productId: string, delta: number) => void;
  /** The Image column switch from {@link useQuoteImageColumn}; omitted while the organization has images off. */
  imageColumn?: { show: boolean; toggle: () => void };
}

/** Picking a variant: its price (the server reprices on save) and its sales description when the line has none of its own. */
function variantPatch(l: QuoteLine, variantId: string | null, opt: VariantOption | null): Partial<QuoteLine> {
  if (!opt) return { variantId };
  const own = l.description && l.description !== l.productName && !l.description.startsWith(`${opt.productName} (`)
    && l.description !== opt.productName;
  return {
    variantId, ...(l.priceSource !== 'manual' ? { unitPrice: Number(opt.salesPrice) } : {}),
    ...(!own ? { description: opt.description || opt.displayName } : {}),
  };
}

/** Quantity input; derived (read-only) while the product is sold by size and the size is typed. */
function QuoteQtyInput({ line, className, onChange, onBlur }: {
  line: QuoteLine; className?: string; onChange: (q: number) => void; onBlur: () => void;
}) {
  const { data: sizing } = useProductSizing(line.productId);
  const derived = !!sizing?.sizingMode && hasSize(line.size);
  return <Input type="number" min={0} step="any" readOnly={derived} title={derived ? tx('Computed from the size') : undefined}
    className={cn(className, derived && 'bg-muted/50')} value={line.quantity} onChange={(e) => onChange(Number(e.target.value))} onBlur={onBlur} />;
}

/**
 * Odoo-style quotation lines: Description · Quantity · Unit Price · Amount, with optional columns (product, variant,
 * unit, lead time, discount, taxes, cost and margin, tax excluded / included), section and note rows with section
 * subtotals, drag to reorder, and a product catalog.
 */
export function QuoteLinesTable(p: Props) {
  const { lines, editable, currency } = p;
  const { cols, show, toggle } = useLineColumns<QuoteCol>('quotation-lines.columns.v1', COLS, DEFAULT_COLS);
  const [drag, setDrag] = useState<number | null>(null);
  const [over, setOver] = useState<number | null>(null);
  const [catalog, setCatalog] = useState(false);
  const productOpts = useMemo(() => p.products.map((x) => ({ value: x.id, label: x.name, code: x.code ?? undefined })), [p.products]);
  const product = (id: string | null) => (id ? p.products.find((x) => x.id === id) : undefined);
  const rate = (taxId: string | null) => Number(p.taxes.find((t) => t.id === taxId)?.rate ?? 0);
  const m = (v: number) => money(v, currency);

  const amounts = lines.map((l) => (isLayout(l) ? null : lineAmounts(l, rate(l.taxId), p.useServer)));
  const sectionTotals = useMemo(() => {
    const out = new Map<number, number>();
    let current = -1;
    lines.forEach((l, i) => {
      if (l.displayType === 'line_section') { current = i; out.set(i, 0); return; }
      const a = amounts[i];
      if (current >= 0 && a) out.set(current, (out.get(current) ?? 0) + (p.taxIncluded ? a.total : a.sub));
    });
    return out;
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [lines, p.taxes, p.useServer, p.taxIncluded]);

  const optional: QuoteCol[] = ['product', 'variant', 'uom', 'leadTime', 'discount', 'taxes', 'unitCost', 'margin', 'marginPct', 'taxExcl', 'taxIncl'];
  // handle + description + quantity + unit price + amount + trailing column
  const imageShown = !!p.imageColumn?.show;
  const colCount = 6 + optional.filter(show).length + (imageShown ? 1 : 0);
  // The picker lists Image first while images are on; it has its own remembered switch.
  type PickerCol = QuoteCol | ImageCol;
  const pickerCols: ReadonlyArray<LineColumn<PickerCol>> = p.imageColumn ? [...IMAGE_COL, ...COLS] : COLS;
  const pickerSet = new Set<PickerCol>(imageShown ? ['image', ...cols] : cols);
  const pickerToggle = (c: PickerCol) => (c === 'image' ? p.imageColumn?.toggle() : toggle(c));
  const th = 'py-2 pr-3 text-sm font-semibold whitespace-nowrap';
  const num = 'text-right tabular-nums';

  const unitCost = (l: QuoteLine) => {
    const prod = product(l.productId);
    if (!prod || prod.costPrice == null || prod.costPrice === '') return null;
    return Number(prod.costPrice) * (unitFactor(prod, l.uomId) ?? 1);
  };

  const productCell = (l: QuoteLine, i: number, withVariant: boolean) => (
    <>
      {editable
        ? <SearchableSelect value={l.productId ?? ''} onValueChange={(v) => p.onProduct(i, v || null)} options={productOpts} placeholder={tx('Select a product…')} className="w-full min-w-[12rem]" />
        : <span className="font-medium">{l.productName ?? (l.productId ? '' : tx('(no product)'))}</span>}
      {withVariant && <VariantSelect productId={l.productId} value={l.variantId ?? null} disabled={!editable} label={l.variantName ?? undefined} className="w-full" onChange={(v, opt) => p.onPatch(i, variantPatch(l, v, opt), true)} />}
      {l.productId && l.size && <LineSizeFields productId={l.productId} variantId={l.variantId} value={l.size} disabled={!editable}
        onChange={(size, q) => p.onPatch(i, { size, ...(q !== null ? { quantity: q } : {}) }, true)} />}
      <div className="mt-1 flex flex-wrap items-center gap-1.5 text-[11px] text-muted-foreground">
        {l.priceSource && l.productId && <span className="rounded bg-muted px-1.5 py-0.5">{SOURCE_LABEL[l.priceSource] ?? l.priceSource}</span>}
        {l.listPrice != null && Number(l.listPrice) !== Number(l.unitPrice) && <span>{tx('List')} {m(Number(l.listPrice))}</span>}
        {l.stock && (l.stock.sufficient
          ? <span className="inline-flex items-center gap-0.5"><PackageCheck className="h-3 w-3" />{l.stock.available} {tx('available')}</span>
          : <span className="inline-flex items-center gap-0.5 text-destructive"><PackageX className="h-3 w-3" />{tx('Only')} {l.stock.available} {tx('available')}</span>)}
      </div>
    </>
  );

  const dragProps = (i: number) => editable ? {
    onDragOver: (e: React.DragEvent) => { if (drag !== null) { e.preventDefault(); setOver(i); } },
    onDrop: (e: React.DragEvent) => { e.preventDefault(); if (drag !== null && drag !== i) p.onMove(drag, i); setDrag(null); setOver(null); },
  } : {};
  const handle = (i: number) => (
    <td className="w-6 py-2 pl-1 align-top">
      {editable && (
        <span draggable onDragStart={() => setDrag(i)} onDragEnd={() => { setDrag(null); setOver(null); }}
          className="mt-1.5 inline-flex cursor-grab text-muted-foreground/60 hover:text-foreground" title={tx('Drag to reorder')}>
          <GripVertical className="h-4 w-4" />
        </span>
      )}
    </td>
  );
  const removeCell = (i: number) => (
    <td className="w-10 py-2 text-right align-top">
      {editable && <button type="button" aria-label={tx('Remove line')} className="rounded p-1 text-muted-foreground hover:bg-muted hover:text-destructive" onClick={() => p.onRemove(i)}><Trash2 className="h-4 w-4" /></button>}
    </td>
  );

  return (
    <div className="overflow-x-auto">
      <table className="w-full min-w-[760px] text-sm">
        <thead className="border-b text-left">
          <tr>
            <th className="w-6" />
            {imageShown && <th className={cn(th, 'w-16')}>{tx('Image')}</th>}
            {show('product') && <th className={th}>{tx('Product')}</th>}
            <th className={cn(th, 'min-w-[14rem]')}>{tx('Description')}</th>
            {show('variant') && <th className={th}>{tx('Product Variant')}</th>}
            <th className={cn(th, 'w-24 text-right')}>{tx('Quantity')}</th>
            {show('uom') && <th className={cn(th, 'w-24')}>{tx('UoM')}</th>}
            {show('leadTime') && <th className={cn(th, 'w-24 text-right')}>{tx('Lead Time')}</th>}
            <th className={cn(th, 'w-28 text-right')}>{tx('Unit Price')}</th>
            {show('discount') && <th className={cn(th, 'w-20 text-right')}>{tx('Disc.%')}</th>}
            {show('taxes') && <th className={cn(th, 'w-36')}>{tx('Taxes')}</th>}
            {show('unitCost') && <th className={cn(th, 'w-24 text-right')}>{tx('Unit Cost')}</th>}
            {show('margin') && <th className={cn(th, 'w-24 text-right')}>{tx('Margin')}</th>}
            {show('marginPct') && <th className={cn(th, 'w-24 text-right')}>{tx('Margin (%)')}</th>}
            {show('taxExcl') && <th className={cn(th, 'w-28 text-right')}>{tx('Tax Excl.')}</th>}
            {show('taxIncl') && <th className={cn(th, 'w-28 text-right')}>{tx('Tax Incl.')}</th>}
            <th className={cn(th, 'w-28 text-right')}>{tx('Amount')}</th>
            <th className="w-10 py-2 text-right"><LineColumnsPicker columns={pickerCols} cols={pickerSet} toggle={pickerToggle} /></th>
          </tr>
        </thead>
        <tbody>
          {lines.map((l, i) => {
            const rowCls = cn('border-b align-top', drag === i && 'opacity-40', over === i && drag !== null && drag !== i && 'border-t-2 border-t-primary');
            if (l.displayType === 'line_section') {
              return (
                <tr key={i} className={cn(rowCls, 'bg-muted/60')} {...dragProps(i)}>
                  {handle(i)}
                  <td colSpan={colCount - 3} className="py-2 pr-3">
                    {editable
                      ? <Input value={l.description} placeholder={tx('Section name (e.g. Materials, Services)')} className="h-8 border-transparent bg-transparent px-1 font-semibold shadow-none focus-visible:border-input" onChange={(e) => p.onPatch(i, { description: e.target.value }, true)} />
                      : <span className="font-semibold">{l.description}</span>}
                  </td>
                  <td className={cn('py-2 pr-3 pt-3 font-semibold', num)}>{m(sectionTotals.get(i) ?? 0)}</td>
                  {removeCell(i)}
                </tr>
              );
            }
            if (l.displayType === 'line_note') {
              return (
                <tr key={i} className={rowCls} {...dragProps(i)}>
                  {handle(i)}
                  <td colSpan={colCount - 2} className="py-2 pr-3">
                    {editable
                      ? <Textarea rows={1} value={l.description} placeholder={tx('Add a note…')} className="min-h-8 resize-y border-transparent bg-transparent px-1 py-1 italic shadow-none focus-visible:border-input" onChange={(e) => p.onPatch(i, { description: e.target.value }, true)} />
                      : <p className="whitespace-pre-wrap italic text-muted-foreground">{l.description}</p>}
                  </td>
                  {removeCell(i)}
                </tr>
              );
            }
            const a = amounts[i]!;
            const cost = unitCost(l);
            const margin = cost == null ? null : a.sub - cost * Number(l.quantity || 0);
            const marginPct = margin == null || a.sub === 0 ? null : (margin / a.sub) * 100;
            const prod = product(l.productId);
            return (
              <tr key={i} className={rowCls} {...dragProps(i)}>
                {handle(i)}
                {imageShown && <td className="py-1.5 pr-3">{l.productId ? <LineImage key={(prod ? prod.image : l.productImage) ?? ''} src={prod ? prod.image : l.productImage} alt={l.productName ?? l.description} /> : null}</td>}
                {show('product') && <td className="py-1.5 pr-3">{productCell(l, i, !show('variant'))}</td>}
                <td className="py-1.5 pr-3">
                  {!show('product') && <div className="mb-1">{productCell(l, i, !show('variant'))}</div>}
                  {editable
                    ? <Input value={l.description} placeholder={l.productId ? tx('Description') : tx('Description (e.g. a charge)')} className="h-8" onChange={(e) => p.onPatch(i, { description: e.target.value }, true)} />
                    : (show('product') || l.description !== l.productName) && <span className={cn(!show('product') && 'text-muted-foreground')}>{l.description}</span>}
                </td>
                {show('variant') && (
                  <td className="py-1.5 pr-3">
                    <VariantSelect productId={l.productId} value={l.variantId ?? null} disabled={!editable} label={l.variantName ?? '—'} className="mt-0 w-full" onChange={(v, opt) => p.onPatch(i, variantPatch(l, v, opt), true)} />
                    {editable && !l.productId && <span className="text-muted-foreground">—</span>}
                  </td>
                )}
                <td className="py-1.5 pr-3">
                  {editable
                    ? <QuoteQtyInput line={l} className={cn('h-8', num)} onChange={(q) => p.onPatch(i, { quantity: q }, true)} onBlur={() => l.productId && l.priceSource !== 'manual' && p.onReprice(i)} />
                    : <p className={cn('pt-1.5', num)}>{Number(l.quantity).toLocaleString(undefined, { maximumFractionDigits: 4 })}</p>}
                </td>
                {show('uom') && (
                  <td className="py-1.5 pr-3 pt-2.5">
                    {editable && prod
                      ? <UnitSelect product={prod} role="sales" value={l.uomId ?? undefined} className="h-8 w-full rounded-md border bg-background px-2 text-sm" onChange={(uomId) => p.onUnit(i, uomId)} />
                      : <span className="text-muted-foreground">{p.uoms.find((u) => u.id === (l.uomId ?? prod?.uomId))?.code ?? '—'}</span>}
                  </td>
                )}
                {show('leadTime') && (
                  <td className="py-1.5 pr-3">
                    {editable
                      ? <Input type="number" min={0} step={1} className={cn('h-8', num)} placeholder="0" value={l.leadTimeDays ?? ''} onChange={(e) => p.onPatch(i, { leadTimeDays: e.target.value === '' ? null : Math.max(0, Math.round(Number(e.target.value))) }, true)} />
                      : <p className={cn('pt-1.5', num)}>{l.leadTimeDays != null ? `${l.leadTimeDays} ${tx('days')}` : '—'}</p>}
                  </td>
                )}
                <td className="py-1.5 pr-3">
                  {editable
                    ? <Input type="number" min={0} step="any" className={cn('h-8', num)} value={l.unitPrice} onChange={(e) => p.onPatch(i, { unitPrice: Number(e.target.value) })} />
                    : <p className={cn('pt-1.5', num)}>{m(Number(l.unitPrice))}</p>}
                </td>
                {show('discount') && (
                  <td className="py-1.5 pr-3">
                    {editable
                      ? <Input type="number" min={0} max={100} step="any" className={cn('h-8', num)} value={l.discountPercent} onChange={(e) => p.onPatch(i, { discountPercent: Number(e.target.value) })} />
                      : <p className={cn('pt-1.5', num)}>{Number(l.discountPercent) ? Number(l.discountPercent).toFixed(2) : '0.00'}</p>}
                  </td>
                )}
                {show('taxes') && (
                  <td className="py-1.5 pr-3">
                    {editable
                      ? (
                        <select aria-label={tx('Taxes')} className="h-8 w-full rounded-md border bg-background px-2 text-sm" value={l.taxId ?? ''} onChange={(e) => p.onPatch(i, { taxId: e.target.value || null }, true)}>
                          <option value="">{tx('No tax')}</option>
                          {p.taxes.map((t) => <option key={t.id} value={t.id}>{t.name} ({Number(t.rate)}%)</option>)}
                        </select>
                      )
                      : l.taxId
                        ? <span className="mt-1 inline-block rounded-full bg-muted px-2.5 py-0.5 text-xs font-medium" title={l.taxName ?? undefined}>{Number(p.taxes.find((t) => t.id === l.taxId)?.rate ?? 0)}%</span>
                        : <span className="text-muted-foreground">—</span>}
                  </td>
                )}
                {show('unitCost') && <td className={cn('py-1.5 pr-3 pt-3 text-muted-foreground', num)}>{cost == null ? '—' : m(cost)}</td>}
                {show('margin') && <td className={cn('py-1.5 pr-3 pt-3', num, margin != null && margin < 0 && 'text-destructive')}>{margin == null ? '—' : m(margin)}</td>}
                {show('marginPct') && <td className={cn('py-1.5 pr-3 pt-3', num, marginPct != null && marginPct < 0 && 'text-destructive')}>{marginPct == null ? '—' : `${marginPct.toFixed(1)}%`}</td>}
                {show('taxExcl') && <td className={cn('py-1.5 pr-3 pt-3', num)}>{m(a.sub)}</td>}
                {show('taxIncl') && <td className={cn('py-1.5 pr-3 pt-3', num)}>{m(a.total)}</td>}
                <td className={cn('py-1.5 pr-3 pt-3 font-medium', num)}>{m(p.taxIncluded ? a.total : a.sub)}</td>
                {removeCell(i)}
              </tr>
            );
          })}
          {lines.length === 0 && !editable && (
            <tr><td colSpan={colCount} className="py-6 text-center text-muted-foreground">{tx('No lines.')}</td></tr>
          )}
          {editable && (
            <tr className="border-b">
              <td />
              <td colSpan={colCount - 1} className="py-2.5 pr-3">
                <div className="flex flex-wrap items-center gap-x-5 gap-y-1">
                  <LinkButton onClick={() => p.onAdd('line')}>{tx('Add a line')}</LinkButton>
                  <LinkButton onClick={() => p.onAdd('section')}>{tx('Add a section')}</LinkButton>
                  <LinkButton onClick={() => p.onAdd('note')}>{tx('Add a note')}</LinkButton>
                  <LinkButton onClick={() => setCatalog(true)}>{tx('Catalog')}</LinkButton>
                  <span className="ml-auto flex flex-wrap gap-x-5">
                    <LinkButton onClick={() => p.onAdd('charge')}>{tx('Add a charge')}</LinkButton>
                    {p.canPrice && <LinkButton onClick={() => p.onReprice()}><Sparkles className="mr-1 inline h-3.5 w-3.5" />{tx('Apply customer prices')}</LinkButton>}
                  </span>
                </div>
              </td>
            </tr>
          )}
        </tbody>
      </table>
      {editable && (
        <CatalogDialog open={catalog} onOpenChange={setCatalog} products={p.products} lines={lines} currency={currency} onAdd={p.onCatalogAdd} />
      )}
    </div>
  );
}

function LinkButton({ onClick, children }: { onClick: () => void; children: ReactNode }) {
  return <button type="button" onClick={onClick} className="text-sm font-medium text-primary hover:underline">{children}</button>;
}

/** Product catalog: search the catalog and set quantities straight onto the quotation. */
function CatalogDialog({ open, onOpenChange, products, lines, currency, onAdd }: {
  open: boolean; onOpenChange: (v: boolean) => void; products: ProductOption[]; lines: QuoteLine[]; currency?: string;
  onAdd: (productId: string, delta: number) => void;
}) {
  const [q, setQ] = useState('');
  const inQuote = useMemo(() => {
    const out = new Map<string, number>();
    for (const l of lines) if (l.productId && !isLayout(l)) out.set(l.productId, (out.get(l.productId) ?? 0) + Number(l.quantity || 0));
    return out;
  }, [lines]);
  const shown = useMemo(() => {
    const s = q.trim().toLowerCase();
    return products.filter((x) => !s || x.name.toLowerCase().includes(s) || (x.code ?? '').toLowerCase().includes(s)).slice(0, 120);
  }, [products, q]);
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-w-4xl">
        <DialogHeader><DialogTitle>{tx('Catalog')}</DialogTitle></DialogHeader>
        <div className="relative">
          <Search className="absolute left-2.5 top-2.5 h-4 w-4 text-muted-foreground" />
          <Input autoFocus value={q} onChange={(e) => setQ(e.target.value)} placeholder={tx('Search products…')} className="pl-8" />
        </div>
        <div className="grid max-h-[60vh] grid-cols-1 gap-3 overflow-y-auto pr-1 sm:grid-cols-2 lg:grid-cols-3">
          {shown.map((x) => {
            const qty = inQuote.get(x.id) ?? 0;
            return (
              <div key={x.id} className={cn('flex flex-col justify-between rounded-lg border p-3', qty > 0 && 'border-primary bg-primary/5')}>
                <div>
                  <p className="font-medium leading-tight">{x.name}</p>
                  {x.code && <p className="text-xs text-muted-foreground">{x.code}</p>}
                  <p className="mt-1 text-sm tabular-nums">{money(Number(x.salesPrice ?? 0), currency)}</p>
                </div>
                <div className="mt-3 flex items-center justify-end gap-2">
                  {qty > 0
                    ? <>
                        <Button type="button" size="icon" variant="outline" className="h-8 w-8" aria-label={tx('Less')} onClick={() => onAdd(x.id, -1)}><Minus className="h-4 w-4" /></Button>
                        <span className="w-10 text-center tabular-nums">{qty}</span>
                        <Button type="button" size="icon" variant="outline" className="h-8 w-8" aria-label={tx('More')} onClick={() => onAdd(x.id, 1)}><Plus className="h-4 w-4" /></Button>
                      </>
                    : <Button type="button" size="sm" variant="secondary" onClick={() => onAdd(x.id, 1)}><Plus className="mr-1 h-4 w-4" />{tx('Add')}</Button>}
                </div>
              </div>
            );
          })}
          {shown.length === 0 && <p className="col-span-full py-8 text-center text-sm text-muted-foreground">{tx('No products found.')}</p>}
        </div>
      </DialogContent>
    </Dialog>
  );
}

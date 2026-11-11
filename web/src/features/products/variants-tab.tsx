/**
 * "Attributes & Variants" tab of the product page (ADR-S058): pick the attribute values a product is offered in,
 * set a price extra per value (Configure), and manage the generated variants (SKU, barcode, fixed price, cost, active).
 * Every button is type="button": the tab lives inside the product form.
 */
import { useEffect, useMemo, useState } from 'react';
import { GripVertical, Layers, Plus, Settings2, Trash2, X } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Badge } from '@/components/ui/badge';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Skeleton } from '@/components/ui/skeleton';
import { Popover, PopoverContent, PopoverTrigger } from '@/components/ui/popover';
import {
  Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle,
} from '@/components/ui/dialog';
import {
  Select, SelectContent, SelectItem, SelectTrigger, SelectValue,
} from '@/components/ui/select';
import { notify, apiMessage } from '@/lib/notify';
import { tx } from '@/lib/i18n/tx';
import {
  type AttributeLine, type DisplayType, type ProductVariantRow, type VariantAttribute,
  useDeleteVariantAttribute, useSaveAttributeLines, useSaveLinePriceExtras, useSaveVariantAttribute,
  useUpdateVariant, useVariantAttributes, useVariantConfig,
} from './variants-api';
import { SizingCard, VariantDetailsDialog } from './sizing-card';
import { sizeLabel, useProductSizing, type ProductSizing } from './sizing';

interface DraftLine {
  key: string;
  attributeId: string;
  valueIds: string[];
}

const num = (v: unknown) => (v === null || v === undefined || v === '' ? 0 : Number(v));
const money = (v: unknown) =>
  num(v).toLocaleString(undefined, { minimumFractionDigits: 0, maximumFractionDigits: 2 });

function Swatch({ color }: { color: string | null | undefined }) {
  if (!color) return null;
  return (
    <span
      className="inline-block h-3 w-3 rounded-full border border-black/15 flex-shrink-0"
      style={{ backgroundColor: color }}
    />
  );
}

function ValueChip({ name, color, onRemove }: { name: string; color?: string | null; onRemove?: () => void }) {
  return (
    <span className="inline-flex items-center gap-1.5 rounded-full bg-muted px-2.5 py-0.5 text-xs font-semibold">
      <Swatch color={color} />
      {name}
      {onRemove && (
        <button type="button" onClick={onRemove} className="text-muted-foreground hover:text-destructive"
          aria-label={tx('Remove')}>
          <X className="h-3 w-3" />
        </button>
      )}
    </span>
  );
}

const toDraft = (lines: AttributeLine[]): DraftLine[] =>
  lines.map((l) => ({ key: l.id, attributeId: l.attributeId, valueIds: l.values.map((v) => v.valueId) }));

export function VariantsTab({ productId }: { productId?: string }) {
  if (!productId) {
    return (
      <Card>
        <CardContent className="py-10 text-center text-sm text-muted-foreground">
          {tx('Save the product first, then add its attributes and variants.')}
        </CardContent>
      </Card>
    );
  }
  return <VariantsEditor productId={productId} />;
}

function VariantsEditor({ productId }: { productId: string }) {
  const { data: config, isLoading } = useVariantConfig(productId);
  const { data: attributes = [] } = useVariantAttributes();
  const saveLines = useSaveAttributeLines(productId);
  const [draft, setDraft] = useState<DraftLine[]>([]);
  const [configureLine, setConfigureLine] = useState<AttributeLine | null>(null);
  const [manageOpen, setManageOpen] = useState(false);
  const [showArchived, setShowArchived] = useState(false);
  const { data: sizing } = useProductSizing(productId);

  useEffect(() => {
    if (config) setDraft(toDraft(config.lines));
  }, [config]);

  const attrById = useMemo(() => new Map(attributes.map((a) => [a.id, a])), [attributes]);
  const dirty = useMemo(
    () => config !== undefined && JSON.stringify(toDraft(config.lines).map(({ attributeId, valueIds }) =>
      ({ attributeId, valueIds }))) !== JSON.stringify(draft.map(({ attributeId, valueIds }) => ({ attributeId, valueIds }))),
    [config, draft],
  );
  const combinations = draft.reduce((n, l) => n * Math.max(l.valueIds.length, 1), draft.length ? 1 : 0);

  const update = (key: string, patch: Partial<DraftLine>) =>
    setDraft((rows) => rows.map((r) => (r.key === key ? { ...r, ...patch } : r)));

  const onSave = async () => {
    const incomplete = draft.find((l) => !l.attributeId || l.valueIds.length === 0);
    if (incomplete) {
      notify.error(tx('Each line needs an attribute and at least one value'));
      return;
    }
    try {
      await saveLines.mutateAsync(draft.map((l) => ({ attributeId: l.attributeId, valueIds: l.valueIds })));
      notify.success(tx('Variants updated'));
    } catch (err) {
      notify.error(apiMessage(err, tx('Save failed')));
    }
  };

  if (isLoading || !config) {
    return <Skeleton className="h-64 w-full" />;
  }

  const variants = config.variants.filter((v) => showArchived || v.isActive);
  const archived = config.variants.filter((v) => !v.isActive).length;

  return (
    <div className="space-y-6">
      <SizingCard productId={productId} />
      <Card>
        <CardHeader className="pb-3 flex flex-row items-center justify-between space-y-0">
          <CardTitle className="text-base flex items-center gap-2">
            <Layers className="h-4 w-4 text-primary" /> {tx('Attributes')}
          </CardTitle>
          <Button type="button" variant="outline" size="sm" onClick={() => setManageOpen(true)}>
            <Settings2 className="h-4 w-4 mr-1.5" /> {tx('Manage attributes')}
          </Button>
        </CardHeader>
        <CardContent className="p-0">
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="border-y bg-muted/30 text-left">
                  <th className="w-8" />
                  <th className="px-3 py-2 font-semibold w-56">{tx('Attribute')}</th>
                  <th className="px-3 py-2 font-semibold">{tx('Values')}</th>
                  <th className="w-32" />
                  <th className="w-12" />
                </tr>
              </thead>
              <tbody>
                {draft.map((line) => {
                  const attr = attrById.get(line.attributeId);
                  const saved = config.lines.find((l) => l.attributeId === line.attributeId);
                  const used = new Set(draft.filter((d) => d.key !== line.key).map((d) => d.attributeId));
                  return (
                    <tr key={line.key} className="border-b align-middle">
                      <td className="pl-3 text-muted-foreground"><GripVertical className="h-4 w-4" /></td>
                      <td className="px-3 py-2">
                        {saved ? (
                          <span className="font-medium">{attr?.name ?? saved.attributeName}</span>
                        ) : (
                          <Select value={line.attributeId}
                            onValueChange={(v) => update(line.key, { attributeId: v, valueIds: [] })}>
                            <SelectTrigger className="h-8"><SelectValue placeholder={tx('Choose attribute')} /></SelectTrigger>
                            <SelectContent>
                              {attributes.filter((a) => a.isActive && !used.has(a.id)).map((a) => (
                                <SelectItem key={a.id} value={a.id}>{a.name}</SelectItem>
                              ))}
                            </SelectContent>
                          </Select>
                        )}
                      </td>
                      <td className="px-3 py-2">
                        <div className="flex flex-wrap items-center gap-1.5">
                          {line.valueIds.map((vid) => {
                            const v = attr?.values.find((x) => x.id === vid);
                            return (
                              <ValueChip key={vid} name={v?.name ?? '…'} color={v?.htmlColor}
                                onRemove={() => update(line.key, { valueIds: line.valueIds.filter((x) => x !== vid) })} />
                            );
                          })}
                          {attr && (
                            <ValuePicker attribute={attr} selected={line.valueIds}
                              onPick={(id) => update(line.key, { valueIds: [...line.valueIds, id] })} />
                          )}
                        </div>
                      </td>
                      <td className="px-3 py-2 text-right">
                        {saved && (
                          <Button type="button" variant="secondary" size="sm" className="h-7" disabled={dirty}
                            title={dirty ? tx('Save the attributes first') : undefined}
                            onClick={() => setConfigureLine(saved)}>
                            {tx('Configure')}
                          </Button>
                        )}
                      </td>
                      <td className="px-3 py-2 text-right">
                        <button type="button" className="text-muted-foreground hover:text-destructive"
                          aria-label={tx('Remove line')}
                          onClick={() => setDraft((rows) => rows.filter((r) => r.key !== line.key))}>
                          <Trash2 className="h-4 w-4" />
                        </button>
                      </td>
                    </tr>
                  );
                })}
                <tr className="border-b">
                  <td />
                  <td colSpan={4} className="px-3 py-2">
                    <button type="button" className="text-sm text-primary hover:underline"
                      onClick={() => setDraft((rows) => [...rows, { key: crypto.randomUUID(), attributeId: '', valueIds: [] }])}>
                      {tx('Add a line')}
                    </button>
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
          {dirty && (
            <div className="flex flex-wrap items-center justify-between gap-2 border-t bg-amber-50 px-4 py-2.5 text-sm">
              <span className="text-amber-800">
                {combinations > 0
                  ? tx('Saving generates {n} variant(s). Variants no longer offered are archived, never deleted.')
                    .replace('{n}', String(combinations))
                  : tx('No attribute lines: the product will have no variants.')}
              </span>
              <div className="flex gap-2">
                <Button type="button" variant="outline" size="sm" onClick={() => setDraft(toDraft(config.lines))}>
                  {tx('Discard')}
                </Button>
                <Button type="button" size="sm" onClick={onSave} disabled={saveLines.isPending}>
                  {saveLines.isPending ? tx('Saving…') : tx('Save attributes')}
                </Button>
              </div>
            </div>
          )}
        </CardContent>
      </Card>

      <Card>
        <CardHeader className="pb-3 flex flex-row items-center justify-between space-y-0">
          <CardTitle className="text-base">
            {tx('Variants')} <Badge variant="secondary" className="ml-1.5">{config.variants.filter((v) => v.isActive).length}</Badge>
          </CardTitle>
          {archived > 0 && (
            <label className="flex items-center gap-2 text-xs text-muted-foreground cursor-pointer">
              <input type="checkbox" checked={showArchived} onChange={(e) => setShowArchived(e.target.checked)} />
              {tx('Show archived')} ({archived})
            </label>
          )}
        </CardHeader>
        <CardContent className="p-0">
          {variants.length === 0 ? (
            <p className="px-4 pb-6 text-sm text-muted-foreground">
              {tx('Add attribute lines above to generate variants. Each variant has its own stock, cost and price.')}
            </p>
          ) : (
            <div className="overflow-x-auto">
              <table className="w-full text-sm">
                <thead>
                  <tr className="border-y bg-muted/30 text-left">
                    <th className="px-3 py-2 font-semibold">{tx('Variant')}</th>
                    <th className="px-3 py-2 font-semibold w-36">{tx('SKU')}</th>
                    <th className="px-3 py-2 font-semibold w-36">{tx('Barcode')}</th>
                    <th className="px-3 py-2 font-semibold w-32 text-right">{tx('Sales price')}</th>
                    <th className="px-3 py-2 font-semibold w-32 text-right">{tx('Cost')}</th>
                    <th className="px-3 py-2 font-semibold w-24 text-right">{tx('On hand')}</th>
                    <th className="px-3 py-2 font-semibold w-28 text-right">{tx('Stock value')}</th>
                    <th className="px-3 py-2 font-semibold w-20 text-center">{tx('Active')}</th>
                    <th className="w-12" />
                  </tr>
                </thead>
                <tbody>
                  {variants.map((v) => (
                    <VariantRow key={v.id} productId={productId} variant={v} lines={config.lines} sizing={sizing} />
                  ))}
                </tbody>
              </table>
            </div>
          )}
          <p className="px-4 py-2 text-[11px] text-muted-foreground border-t">
            {tx('Price = product price + value extras (Configure), unless a fixed price is typed. Leave price or cost empty to use the computed value.')}
          </p>
        </CardContent>
      </Card>

      {configureLine && (
        <ConfigureDialog productId={productId} line={configureLine} basePrice={num(config.salesPrice)}
          onClose={() => setConfigureLine(null)} />
      )}
      <ManageAttributesDialog open={manageOpen} onOpenChange={setManageOpen} attributes={attributes} />
    </div>
  );
}

/** "+" popover: add an existing value of the attribute, or create a new one. */
function ValuePicker({ attribute, selected, onPick }: {
  attribute: VariantAttribute; selected: string[]; onPick: (valueId: string) => void;
}) {
  const [open, setOpen] = useState(false);
  const [name, setName] = useState('');
  const save = useSaveVariantAttribute();
  const remaining = attribute.values.filter((v) => v.isActive && !selected.includes(v.id));

  const create = async () => {
    const trimmed = name.trim();
    if (!trimmed) return;
    try {
      const updated = await save.mutateAsync({
        id: attribute.id,
        data: { values: [...attribute.values.map((v) => ({ id: v.id, name: v.name, htmlColor: v.htmlColor,
          isActive: v.isActive })), { name: trimmed }] },
      });
      const created = updated.values.find((v) => v.name.toLowerCase() === trimmed.toLowerCase());
      if (created) onPick(created.id);
      setName('');
      setOpen(false);
    } catch (err) {
      notify.error(apiMessage(err, tx('Could not add the value')));
    }
  };

  return (
    <Popover open={open} onOpenChange={setOpen}>
      <PopoverTrigger asChild>
        <button type="button" className="inline-flex h-6 items-center gap-1 rounded-full border border-dashed px-2 text-xs text-muted-foreground hover:border-primary hover:text-primary">
          <Plus className="h-3 w-3" /> {tx('Value')}
        </button>
      </PopoverTrigger>
      <PopoverContent className="w-60 p-2" align="start">
        <div className="max-h-48 overflow-auto">
          {remaining.length === 0 && <p className="px-2 py-1 text-xs text-muted-foreground">{tx('All values added')}</p>}
          {remaining.map((v) => (
            <button key={v.id} type="button"
              className="flex w-full items-center gap-2 rounded px-2 py-1.5 text-sm hover:bg-muted"
              onClick={() => { onPick(v.id); setOpen(false); }}>
              <Swatch color={v.htmlColor} /> {v.name}
            </button>
          ))}
        </div>
        <div className="mt-2 flex gap-1.5 border-t pt-2">
          <Input value={name} onChange={(e) => setName(e.target.value)} placeholder={tx('New value')}
            className="h-8 text-sm"
            onKeyDown={(e) => { if (e.key === 'Enter') { e.preventDefault(); void create(); } }} />
          <Button type="button" size="sm" className="h-8" onClick={() => void create()} disabled={save.isPending}>
            {tx('Add')}
          </Button>
        </div>
      </PopoverContent>
    </Popover>
  );
}

/** Configure: price extra per value of one attribute line. */
function ConfigureDialog({ productId, line, basePrice, onClose }: {
  productId: string; line: AttributeLine; basePrice: number; onClose: () => void;
}) {
  const [extras, setExtras] = useState<Record<string, string>>(
    Object.fromEntries(line.values.map((v) => [v.valueId, num(v.priceExtra) ? String(num(v.priceExtra)) : ''])),
  );
  const save = useSaveLinePriceExtras(productId);

  const onSave = async () => {
    try {
      await save.mutateAsync({
        lineId: line.id,
        values: line.values.map((v) => ({ valueId: v.valueId, priceExtra: num(extras[v.valueId]) })),
      });
      notify.success(tx('Price extras saved'));
      onClose();
    } catch (err) {
      notify.error(apiMessage(err, tx('Save failed')));
    }
  };

  return (
    <Dialog open onOpenChange={(o) => { if (!o) onClose(); }}>
      <DialogContent className="max-w-md">
        <DialogHeader>
          <DialogTitle>{tx('Configure')} — {line.attributeName}</DialogTitle>
          <DialogDescription>
            {tx('Amount added to the product price ({base}) for variants with this value.').replace('{base}', money(basePrice))}
          </DialogDescription>
        </DialogHeader>
        <div className="space-y-2">
          <div className="grid grid-cols-[1fr_8rem_6rem] gap-2 text-xs font-semibold text-muted-foreground px-1">
            <span>{tx('Value')}</span><span className="text-right">{tx('Price extra')}</span>
            <span className="text-right">{tx('Price')}</span>
          </div>
          {line.values.map((v) => (
            <div key={v.valueId} className="grid grid-cols-[1fr_8rem_6rem] items-center gap-2">
              <ValueChip name={v.name} color={v.htmlColor} />
              <Input type="number" step="any" className="h-8 text-right" value={extras[v.valueId] ?? ''}
                placeholder="0"
                onChange={(e) => setExtras((x) => ({ ...x, [v.valueId]: e.target.value }))} />
              <span className="text-right text-sm tabular-nums">{money(basePrice + num(extras[v.valueId]))}</span>
            </div>
          ))}
        </div>
        <DialogFooter>
          <Button type="button" variant="outline" onClick={onClose}>{tx('Cancel')}</Button>
          <Button type="button" onClick={onSave} disabled={save.isPending}>
            {save.isPending ? tx('Saving…') : tx('Save')}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

/** One variant row: SKU / barcode / fixed price / cost edited inline (saved on blur), active toggle. */
function VariantRow({ productId, variant, lines, sizing }: {
  productId: string; variant: ProductVariantRow; lines: AttributeLine[]; sizing: ProductSizing | undefined;
}) {
  const update = useUpdateVariant(productId);
  const [details, setDetails] = useState(false);
  const size = variant.length == null ? null
    : sizeLabel({ pieces: 1, length: variant.length, width: variant.width, height: variant.height }, sizing?.sizingUnit)
      ?.replace(/^1 \S+ × /, '');
  const [form, setForm] = useState({
    sku: variant.sku ?? '',
    barcode: variant.barcode ?? '',
    price: variant.fixedPrice === null || variant.fixedPrice === undefined ? '' : String(num(variant.fixedPrice)),
    cost: variant.fixedCost === null || variant.fixedCost === undefined ? '' : String(num(variant.fixedCost)),
  });
  useEffect(() => {
    setForm({
      sku: variant.sku ?? '',
      barcode: variant.barcode ?? '',
      price: variant.fixedPrice === null || variant.fixedPrice === undefined ? '' : String(num(variant.fixedPrice)),
      cost: variant.fixedCost === null || variant.fixedCost === undefined ? '' : String(num(variant.fixedCost)),
    });
  }, [variant]);

  const colorOf = (attrName: string, valueName: string) =>
    lines.find((l) => l.attributeName === attrName)?.values.find((v) => v.name === valueName)?.htmlColor ?? null;

  const commit = async (field: 'sku' | 'barcode' | 'price' | 'cost') => {
    const value = form[field].trim();
    const current = field === 'sku' ? variant.sku ?? '' : field === 'barcode' ? variant.barcode ?? ''
      : field === 'price' ? (variant.fixedPrice == null ? '' : String(num(variant.fixedPrice)))
        : (variant.fixedCost == null ? '' : String(num(variant.fixedCost)));
    if (value === current) return;
    const data = field === 'sku' ? { sku: value || null } : field === 'barcode' ? { barcode: value || null }
      : field === 'price' ? { salesPrice: value === '' ? null : Number(value) }
        : { costPrice: value === '' ? null : Number(value) };
    try {
      await update.mutateAsync({ id: variant.id, data });
    } catch (err) {
      notify.error(apiMessage(err, tx('Save failed')));
      setForm((f) => ({ ...f, [field]: current }));
    }
  };

  const attrs = Object.entries(variant.attributes ?? {});
  return (
    <tr className={`border-b ${variant.isActive ? '' : 'opacity-50'}`}>
      <td className="px-3 py-2">
        <div className="flex flex-wrap gap-1">
          {attrs.length > 0
            ? attrs.map(([a, v]) => <ValueChip key={a} name={String(v)} color={colorOf(a, String(v))} />)
            : <span className="font-medium">{variant.name}</span>}
        </div>
        {(size || variant.description) && (
          <p className="mt-1 max-w-xs truncate text-xs text-muted-foreground" title={variant.description ?? undefined}>
            {[size, variant.description].filter(Boolean).join(' · ')}
          </p>
        )}
      </td>
      <td className="px-3 py-1.5">
        <Input className="h-8" value={form.sku} onChange={(e) => setForm({ ...form, sku: e.target.value })}
          onBlur={() => void commit('sku')} />
      </td>
      <td className="px-3 py-1.5">
        <Input className="h-8" value={form.barcode} onChange={(e) => setForm({ ...form, barcode: e.target.value })}
          onBlur={() => void commit('barcode')} />
      </td>
      <td className="px-3 py-1.5">
        <Input type="number" step="any" className="h-8 text-right" value={form.price}
          placeholder={money(variant.salesPrice)}
          title={form.price ? tx('Fixed price') : tx('Product price + value extras')}
          onChange={(e) => setForm({ ...form, price: e.target.value })} onBlur={() => void commit('price')} />
      </td>
      <td className="px-3 py-1.5">
        <Input type="number" step="any" className="h-8 text-right" value={form.cost}
          placeholder={variant.costPrice == null ? '—' : money(variant.costPrice)}
          onChange={(e) => setForm({ ...form, cost: e.target.value })} onBlur={() => void commit('cost')} />
      </td>
      <td className="px-3 py-2 text-right tabular-nums">{money(variant.onHand)}</td>
      <td className="px-3 py-2 text-right tabular-nums">{money(variant.stockValue)}</td>
      <td className="px-3 py-2 text-center">
        <input type="checkbox" checked={variant.isActive} aria-label={tx('Active')}
          onChange={(e) => update.mutate({ id: variant.id, data: { isActive: e.target.checked } },
            { onError: (err) => notify.error(apiMessage(err, tx('Save failed'))) })} />
      </td>
      <td className="px-2 py-2 text-center">
        <Button type="button" size="icon" variant="ghost" className="h-7 w-7" title={tx('Size & description')}
          onClick={() => setDetails(true)}><Settings2 className="h-4 w-4" /></Button>
        {details && <VariantDetailsDialog productId={productId} variant={variant} sizing={sizing} onClose={() => setDetails(false)} />}
      </td>
    </tr>
  );
}

const DISPLAY_TYPES: { value: DisplayType; label: string }[] = [
  { value: 'pills', label: tx('Pills') },
  { value: 'radio', label: tx('Radio') },
  { value: 'select', label: tx('Select') },
  { value: 'color', label: tx('Color') },
];

/** Attribute masters: create / rename / edit values / delete. */
function ManageAttributesDialog({ open, onOpenChange, attributes }: {
  open: boolean; onOpenChange: (o: boolean) => void; attributes: VariantAttribute[];
}) {
  const [editing, setEditing] = useState<VariantAttribute | 'new' | null>(null);
  const remove = useDeleteVariantAttribute();

  return (
    <Dialog open={open} onOpenChange={(o) => { onOpenChange(o); if (!o) setEditing(null); }}>
      <DialogContent className="max-w-2xl">
        <DialogHeader>
          <DialogTitle>{editing ? (editing === 'new' ? tx('New attribute') : editing.name) : tx('Variant attributes')}</DialogTitle>
          <DialogDescription>
            {tx('Attributes (e.g. Color, Size) and their values are shared by all products.')}
          </DialogDescription>
        </DialogHeader>
        {editing ? (
          <AttributeForm attribute={editing === 'new' ? null : editing} onDone={() => setEditing(null)} />
        ) : (
          <div className="space-y-2">
            <div className="max-h-96 overflow-auto divide-y rounded border">
              {attributes.length === 0 && (
                <p className="p-4 text-sm text-muted-foreground">{tx('No attributes yet.')}</p>
              )}
              {attributes.map((a) => (
                <div key={a.id} className="flex items-center gap-3 px-3 py-2">
                  <div className="w-36 font-medium">{a.name}</div>
                  <div className="flex-1 flex flex-wrap gap-1">
                    {a.values.filter((v) => v.isActive).map((v) => <ValueChip key={v.id} name={v.name} color={v.htmlColor} />)}
                  </div>
                  <span className="text-xs text-muted-foreground w-20 text-right">
                    {a.productCount} {tx('product(s)')}
                  </span>
                  <Button type="button" variant="ghost" size="sm" onClick={() => setEditing(a)}>{tx('Edit')}</Button>
                  <button type="button" className="text-muted-foreground hover:text-destructive disabled:opacity-30"
                    disabled={a.productCount > 0} aria-label={tx('Delete')}
                    title={a.productCount > 0 ? tx('Used by products') : undefined}
                    onClick={() => remove.mutate(a.id, { onError: (err) => notify.error(apiMessage(err, tx('Delete failed'))) })}>
                    <Trash2 className="h-4 w-4" />
                  </button>
                </div>
              ))}
            </div>
            <Button type="button" size="sm" onClick={() => setEditing('new')}>
              <Plus className="h-4 w-4 mr-1" /> {tx('New attribute')}
            </Button>
          </div>
        )}
      </DialogContent>
    </Dialog>
  );
}

function AttributeForm({ attribute, onDone }: { attribute: VariantAttribute | null; onDone: () => void }) {
  const save = useSaveVariantAttribute();
  const [name, setName] = useState(attribute?.name ?? '');
  const [displayType, setDisplayType] = useState<DisplayType>(attribute?.displayType ?? 'pills');
  const [values, setValues] = useState<{ id?: string; name: string; htmlColor: string }[]>(
    attribute?.values.map((v) => ({ id: v.id, name: v.name, htmlColor: v.htmlColor ?? '' })) ?? [{ name: '', htmlColor: '' }],
  );

  const onSave = async () => {
    const clean = values.filter((v) => v.name.trim());
    if (!name.trim()) { notify.error(tx('Name is required')); return; }
    try {
      await save.mutateAsync({
        id: attribute?.id,
        data: {
          name: name.trim(),
          displayType,
          values: clean.map((v) => ({ id: v.id, name: v.name.trim(),
            htmlColor: displayType === 'color' && v.htmlColor ? v.htmlColor : null })),
        },
      });
      notify.success(tx('Attribute saved'));
      onDone();
    } catch (err) {
      notify.error(apiMessage(err, tx('Save failed')));
    }
  };

  return (
    <div className="space-y-4">
      <div className="grid grid-cols-2 gap-3">
        <div className="space-y-1.5">
          <Label>{tx('Name')}</Label>
          <Input value={name} onChange={(e) => setName(e.target.value)} placeholder={tx('e.g. Color')} />
        </div>
        <div className="space-y-1.5">
          <Label>{tx('Display type')}</Label>
          <Select value={displayType} onValueChange={(v) => setDisplayType(v as DisplayType)}>
            <SelectTrigger><SelectValue /></SelectTrigger>
            <SelectContent>
              {DISPLAY_TYPES.map((d) => <SelectItem key={d.value} value={d.value}>{d.label}</SelectItem>)}
            </SelectContent>
          </Select>
        </div>
      </div>
      <div className="space-y-2">
        <Label>{tx('Values')}</Label>
        {values.map((v, i) => (
          <div key={v.id ?? `new-${i}`} className="flex items-center gap-2">
            {displayType === 'color' && (
              <input type="color" value={v.htmlColor || '#ffffff'} aria-label={tx('Colour')}
                className="h-8 w-10 cursor-pointer rounded border"
                onChange={(e) => setValues((vs) => vs.map((x, j) => (j === i ? { ...x, htmlColor: e.target.value } : x)))} />
            )}
            <Input className="h-8" value={v.name} placeholder={tx('Value name')}
              onChange={(e) => setValues((vs) => vs.map((x, j) => (j === i ? { ...x, name: e.target.value } : x)))} />
            <button type="button" className="text-muted-foreground hover:text-destructive" aria-label={tx('Remove')}
              onClick={() => setValues((vs) => vs.filter((_, j) => j !== i))}>
              <X className="h-4 w-4" />
            </button>
          </div>
        ))}
        <button type="button" className="text-sm text-primary hover:underline"
          onClick={() => setValues((vs) => [...vs, { name: '', htmlColor: '' }])}>
          {tx('Add a value')}
        </button>
      </div>
      <DialogFooter>
        <Button type="button" variant="outline" onClick={onDone}>{tx('Back')}</Button>
        <Button type="button" onClick={onSave} disabled={save.isPending}>
          {save.isPending ? tx('Saving…') : tx('Save')}
        </Button>
      </DialogFooter>
    </div>
  );
}

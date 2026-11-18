import { useState } from 'react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useNavigate } from 'react-router-dom';
import { Plus, Trash2, Save } from 'lucide-react';
import { Card, CardContent, CardHeader, CardTitle, CardDescription } from '@/components/ui/card';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { PRODUCT_PICKER_PANEL, SearchableSelect } from '@/components/ui/searchable-select';
import { notifyApiError } from '@/lib/api-error';
import { idempotentPost } from '@/lib/idempotent-request';
import { notify } from '@/lib/notify';
import { tx } from '@/lib/i18n/tx';
import { useSearchPicker } from '@/lib/use-search-picker';
import { UnitSelect } from '@/components/unit-select';
import { defaultUnitId, unitPrice, type UnitAwareProduct, type UnitRole } from '@/features/products/units';
import { useProductSearch } from '@/features/products/product-search';
import { locationOption, useStockLocations } from '@/features/inventory/locations';

interface Partner { id: string; name: string; code: string }
interface Product extends UnitAwareProduct { id: string; name: string; code: string; taxId?: string }

interface Line {
  /** Stable row identity: with index keys, removing line 1 left each dropdown showing the row it used to belong to. */
  uid: string;
  productId?: string; description: string; quantity: number; unitPrice: number; taxId?: string;
  /** Unit the returned quantity is in (ADR-S003); defaults to the product's purchase unit. */
  uomId?: string;
}

let lineSeq = 0;
const newLine = (): Line => ({ uid: `line-${++lineSeq}`, description: '', quantity: 1, unitPrice: 0 });

export function DebitNoteCreatePage() {
  const navigate = useNavigate();
  const qc = useQueryClient();
  const [direction, setDirection] = useState<'outbound' | 'inbound'>('outbound');
  const [partnerId, setPartnerId] = useState('');
  const [reason, setReason] = useState('correction');
  const [notes, setNotes] = useState('');
  const [locationId, setLocationId] = useState('');
  const [lines, setLines] = useState<Line[]>(() => [newLine()]);

  // A return-to-vendor (inbound + returned goods) ships stock back, so it needs
  // to know which warehouse the goods leave from.
  const isRtv = direction === 'inbound' && reason === 'returned_goods';

  // Customers and suppliers, searched on the server: one page of 200 left the rest unpickable.
  const partners = useSearchPicker<Partner>({
    key: 'partners',
    url: '/partners',
    params: { sortBy: 'name', sortOrder: 'asc' },
    toOption: (p) => ({ value: p.id, label: p.name, code: p.code }),
  });
  const products = useProductSearch<Product>();
  // Every warehouse: the default page held only the 25 newest locations.
  const warehouses = useStockLocations({ type: 'warehouse', enabled: isRtv });

  const create = useMutation({
    // A stable key per payload: a retry after a lost answer reuses it (see idempotent-request.ts).
    mutationFn: async () =>
      idempotentPost('/procurement/debit-notes', {
        direction,
        partnerId,
        reason,
        notes,
        ...(isRtv && locationId ? { locationId } : {}),
        lines: lines.filter((l) => l.description && l.quantity > 0).map((l) => ({
          productId: l.productId,
          description: l.description,
          quantity: l.quantity,
          unitPrice: l.unitPrice,
          taxId: l.taxId,
          uomId: l.uomId,
        })),
      }),
    onSuccess: (data: any) => {
      notify.success(tx('Debit note {{noteNumber}} created', { noteNumber: data.noteNumber }));
      qc.invalidateQueries({ queryKey: ['debit-notes'] });
      navigate('/procurement/debit-notes');
    },
    onError: (e: unknown) => notifyApiError(e, tx('Failed')),
  });

  const total = lines.reduce((s, l) => s + Number(l.quantity || 0) * Number(l.unitPrice || 0), 0);

  // Blocker fix B6: a supplier (inbound) note is priced at what we pay the supplier and a customer (outbound) note at
  // what we sell for. Supplier notes used to default to the sales price, so a return credited the supplier at retail.
  const roleOf = (d: 'outbound' | 'inbound'): UnitRole => (d === 'inbound' ? 'purchase' : 'sales');
  const priceRole = roleOf(direction);
  const changeDirection = (d: 'outbound' | 'inbound') => {
    setDirection(d);
    const role = roleOf(d);
    setLines((prev) => prev.map((l) => {
      const product = products.get(l.productId);
      if (!product) return l;
      const uomId = defaultUnitId(product, role);
      return { ...l, uomId, unitPrice: unitPrice(product, uomId, role) ?? l.unitPrice };
    }));
  };
  const addLine = () => setLines([...lines, newLine()]);
  const removeLine = (uid: string) => setLines(lines.filter((l) => l.uid !== uid));
  const updateLine = (uid: string, patch: Partial<Line>) =>
    setLines((cur) => cur.map((l) => (l.uid === uid ? { ...l, ...patch } : l)));
  const pickProduct = (line: Line, productId: string) => {
    const product = products.get(productId);
    if (!product) return;
    const uomId = defaultUnitId(product, priceRole);
    setLines((cur) => cur.map((l) => (l.uid !== line.uid ? l : {
      uid: l.uid,
      productId,
      description: product.name,
      quantity: l.quantity || 1,
      unitPrice: unitPrice(product, uomId, priceRole) ?? 0,
      taxId: product.taxId,
      uomId,
    })));
  };
  const pickUnit = (line: Line, uomId: string) => {
    const price = unitPrice(products.get(line.productId), uomId, priceRole);
    updateLine(line.uid, { uomId, unitPrice: price ?? line.unitPrice });
  };

  return (
    <div className="space-y-4">
      <h1 className="text-2xl font-semibold">{tx('New Debit Note')}</h1>

      <Card>
        <CardHeader>
          <CardTitle>{tx('Direction & partner')}</CardTitle>
          <CardDescription>
            {tx('Outbound = we send to a customer (increases their AR). Inbound = supplier sends us one (increases our AP).')}
          </CardDescription>
        </CardHeader>
        <CardContent className="grid gap-3 md:grid-cols-2">
          <div>
            <label className="text-sm font-medium">{tx('Direction')}</label>
            <select
              value={direction}
              onChange={(e) => changeDirection(e.target.value as 'outbound' | 'inbound')}
              className="mt-1 w-full rounded border bg-background px-2 py-1 text-sm"
            >
              <option value="outbound">{tx('Outbound (to customer)')}</option>
              <option value="inbound">{tx('Inbound (from supplier)')}</option>
            </select>
          </div>
          <div>
            <label className="text-sm font-medium">{tx('Partner')}</label>
            <SearchableSelect
              className="mt-1"
              value={partnerId}
              onValueChange={setPartnerId}
              options={partners.options}
              onQueryChange={partners.onQueryChange}
              loading={partners.loading}
              footer={partners.footer}
              selectedLabel={partners.labelOf(partnerId)}
              placeholder={tx('Select partner…')}
              searchPlaceholder={tx('Search partners…')}
            />
          </div>
          <div>
            <label className="text-sm font-medium">{tx('Reason')}</label>
            <select
              value={reason}
              onChange={(e) => setReason(e.target.value)}
              className="mt-1 w-full rounded border bg-background px-2 py-1 text-sm"
            >
              <option value="price_adjustment">{tx('Price adjustment')}</option>
              <option value="returned_goods">{tx('Returned goods')}</option>
              <option value="overcharge">{tx('Overcharge')}</option>
              <option value="correction">{tx('Correction')}</option>
              <option value="other">{tx('Other')}</option>
            </select>
          </div>
          <div>
            <label className="text-sm font-medium">{tx('Notes')}</label>
            <Input className="mt-1" value={notes} onChange={(e) => setNotes(e.target.value)} />
          </div>
          {isRtv && (
            <div>
              <label className="text-sm font-medium">{tx('Return from warehouse')}</label>
              <SearchableSelect
                className="mt-1"
                value={locationId}
                onValueChange={setLocationId}
                options={(warehouses.locations ?? []).map(locationOption)}
                footer={warehouses.footer}
                placeholder={tx('Default warehouse')}
                clearLabel={tx('Default warehouse')}
                searchPlaceholder={tx('Search warehouses…')}
              />
              <p className="mt-1 text-xs text-muted-foreground">
                {tx('Returned goods leave this warehouse and their value is reversed out of stock when the note is posted.')}
              </p>
            </div>
          )}
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <div className="flex items-center justify-between">
            <CardTitle>{tx('Lines')}</CardTitle>
            <Button size="sm" variant="outline" onClick={addLine}><Plus className="mr-2 h-3 w-3" />{tx('Add line')}</Button>
          </div>
        </CardHeader>
        <CardContent>
          <div className="space-y-2">
            {lines.map((ln) => (
              <div key={ln.uid} className="grid gap-2 md:grid-cols-12 items-center">
                <div className="md:col-span-4">
                  {/* Controlled by the line's own product, so the screen always shows what will be saved. */}
                  <SearchableSelect
                    value={ln.productId ?? ''}
                    onValueChange={(v) => pickProduct(ln, v)}
                    options={products.options}
                    onQueryChange={products.onQueryChange}
                    loading={products.loading}
                    footer={products.footer}
                    selectedLabel={products.labelOf(ln.productId) ?? (ln.productId ? ln.description : null)}
                    placeholder={tx('Pick product…')}
                    searchPlaceholder={tx('Search by code or name…')}
                    contentClassName={PRODUCT_PICKER_PANEL}
                    wrap
                  />
                </div>
                <div className="md:col-span-3">
                  <Input
                    placeholder={tx('Description')}
                    value={ln.description}
                    onChange={(e) => updateLine(ln.uid, { description: e.target.value })}
                  />
                </div>
                <div className="md:col-span-1">
                  <Input
                    type="number" step="0.01" min="0"
                    value={ln.quantity}
                    onChange={(e) => updateLine(ln.uid, { quantity: Number(e.target.value) })}
                  />
                </div>
                <div className="md:col-span-1">
                  <UnitSelect
                    product={products.get(ln.productId)}
                    role={priceRole}
                    value={ln.uomId}
                    onChange={(uomId) => pickUnit(ln, uomId)}
                  />
                </div>
                <div className="md:col-span-2">
                  <Input
                    type="number" step="0.01" min="0"
                    value={ln.unitPrice}
                    onChange={(e) => updateLine(ln.uid, { unitPrice: Number(e.target.value) })}
                  />
                </div>
                <div className="md:col-span-1 text-right text-sm tabular-nums">
                  {(ln.quantity * ln.unitPrice).toFixed(2)}
                </div>
                <Button size="icon" variant="ghost" onClick={() => removeLine(ln.uid)} aria-label={tx('Remove')}>
                  <Trash2 className="h-3 w-3 text-destructive" />
                </Button>
              </div>
            ))}
          </div>
          <div className="mt-4 flex items-center justify-between border-t pt-3">
            <span className="text-sm font-medium">{tx('Total')}</span>
            <span className="text-lg font-bold tabular-nums">{total.toFixed(2)}</span>
          </div>
        </CardContent>
      </Card>

      <div className="flex justify-end gap-2">
        <Button variant="outline" onClick={() => navigate(-1)}>{tx('Cancel')}</Button>
        <Button onClick={() => create.mutate()} disabled={!partnerId || lines.length === 0 || create.isPending}>
          <Save className="mr-2 h-4 w-4" />{tx('Save draft')}
        </Button>
      </div>
    </div>
  );
}

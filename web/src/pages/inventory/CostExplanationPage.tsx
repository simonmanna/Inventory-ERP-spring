import { useMemo, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { ExternalLink, Loader2, Route as RouteIcon } from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card';
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { SearchableSelect } from '@/components/ui/searchable-select';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { useProductPicker } from '@/features/menu/api';
import { api } from '@/lib/api';
import { dateTime, money } from '@/lib/format';
import { referenceLabel } from '@/lib/ledger-links';
import { useDebouncedValue } from '@/lib/use-debounced-value';
import { useAuthStore } from '@/stores/auth.store';
import { tx } from '@/lib/i18n/tx';

type Row = Record<string, unknown>;

/** GET /inventory/cost-explanation (review 2026-11-17, ease of use). */
interface Explanation {
  product: { id: string; name: string; sku: string | null; costingMethod: string | null; standardCost: unknown; uomCode: string | null };
  method: { code: string; label: string; explanation: string };
  locations: Array<{
    locationId: string;
    locationName: string;
    quantity: unknown;
    unitCost: unknown;
    value: unknown;
    priceDifference: unknown;
    nrvAllowance: unknown;
    carryingValue: unknown;
    carryingUnitCost: unknown;
  }>;
  totals: {
    quantity: unknown;
    value: unknown;
    priceDifference: unknown;
    nrvAllowance: unknown;
    carryingValue: unknown;
    unitCost: unknown;
    carryingUnitCost: unknown;
  };
  summary: string[];
  adjustments: Array<{ referenceType: string; label: string; amount: unknown; count: unknown }>;
  openAdjuncts: Array<{ locationId: string; locationName: string; component: string; label: string; openAmount: unknown; adjustments: unknown }>;
  movements: Movement[];
  movementsShown: number;
  movementsTotal: number;
}

interface Movement {
  ledgerId: string;
  createdAt: string;
  type: string;
  label: string;
  referenceType: string | null;
  referenceId: string | null;
  documentNo: string | null;
  locationId: string;
  locationName: string;
  quantityChange: unknown;
  unitCost: unknown;
  value: unknown;
  component: string | null;
  runningQuantity: unknown;
  runningValue: unknown;
  runningUnitCost: unknown;
  traceable: boolean;
}

const SELECT = 'h-9 rounded-md border border-input bg-background px-3 text-sm';
const s = (v: unknown) => (v == null ? '' : String(v));
const num = (v: unknown) => {
  const x = Number(v);
  return Number.isFinite(x) ? x : 0;
};
const qty = (v: unknown) => num(v).toLocaleString(undefined, { maximumFractionDigits: 4 });
const unit = (v: unknown) => (v == null || v === '' ? '—' : money(num(v), undefined, 4));
const signed = (v: unknown) => {
  const x = num(v);
  return `${x > 0 ? '+' : ''}${money(x)}`;
};

/** Where a cost goes (AvcoLineageCalculator.target / CostLineageService.byTarget), in words. */
const TARGETS: Record<string, () => string> = {
  on_hand: () => tx('Still in stock — added to the stock value'),
  cogs: () => tx('Already sold — cost of sales'),
  wip: () => tx('Used by a production order still open — work in process'),
  scrap: () => tx('Wasted or used internally — written off'),
  transfer: () => tx('Moved to another location — follows the stock there'),
  in_transit: () => tx('In transit between locations'),
  return: () => tx('Returned to the supplier'),
  negative_stock: () => tx('Issued before it was received (negative stock) — cost of sales'),
};
const targetLabel = (k: unknown) => TARGETS[s(k)]?.() ?? referenceLabel(s(k));

const MOVE_TYPES: Record<string, () => string> = {
  receipt: () => tx('Received'),
  issue: () => tx('Issued or sold'),
  production_consume: () => tx('Used in production'),
  production_output: () => tx('Produced'),
  transfer_in: () => tx('Transferred in'),
  transfer_out: () => tx('Transferred out'),
  cost_adjustment: () => tx('Cost change (value only)'),
  opening_balance: () => tx('Opening balance'),
  reversal_in: () => tx('Reversal (back in)'),
  reversal_out: () => tx('Reversal (back out)'),
  return_in: () => tx('Customer return'),
  return_to_supplier: () => tx('Returned to supplier'),
  internal_use: () => tx('Internal use'),
  waste: () => tx('Waste'),
  adjustment_in: () => tx('Count adjustment (up)'),
  adjustment_out: () => tx('Count adjustment (down)'),
  expiry_write_off: () => tx('Expiry write-off'),
  promo_sample: () => tx('Promotional sample'),
};
const moveLabel = (k: unknown) => MOVE_TYPES[s(k)]?.() ?? referenceLabel(s(k));

const ADJUSTMENT_TYPES: Record<string, () => string> = {
  landed_cost: () => tx('Landed cost (freight, duty, insurance)'),
  landed_accrual: () => tx('Landed cost accrual (estimate)'),
  cost_adjustment: () => tx('Late cost carried by a cost adjustment run'),
  nrv: () => tx('Net realisable value write-down'),
  std_price_difference: () => tx('Price difference against the standard'),
  revaluation: () => tx('Revaluation'),
  overhead_settlement: () => tx('Overhead settlement'),
  stripping_amortisation: () => tx('Stripping amortisation'),
  rehab_production: () => tx('Rehabilitation provision'),
  transfer_freight: () => tx('Transfer freight'),
  ml_price_diff: () => tx('Material ledger price difference'),
};
const adjustmentLabel = (k: unknown) => ADJUSTMENT_TYPES[s(k)]?.() ?? referenceLabel(s(k));

const COMPONENTS: Record<string, () => string> = {
  price_difference: () => tx('price difference'),
  nrv_allowance: () => tx('NRV allowance'),
};

function Stat({ label, value, hint }: { label: string; value: string; hint?: string }) {
  return (
    <div className="rounded-md border p-3">
      <div className="text-xs text-muted-foreground">{label}</div>
      <div className="text-lg font-semibold tabular-nums">{value}</div>
      {hint && <div className="text-xs text-muted-foreground">{hint}</div>}
    </div>
  );
}

/**
 * "Why does this item cost what it does?" (review 2026-11-17, ease of use): the item's cost built up step by step —
 * every receipt, issue and cost change with the running average after it, the amounts kept beside stock, and for any
 * receipt, where a cost added to it went.
 */
export function CostExplanationPage() {
  const has = useAuthStore((st) => st.hasPermission);
  const [params, setParams] = useSearchParams();
  const productId = params.get('productId') ?? '';
  const locationId = params.get('locationId') ?? '';
  const [limit, setLimit] = useState(100);
  const [search, setSearch] = useState('');
  const [pickedLabel, setPickedLabel] = useState<string | null>(null);
  const q = useDebouncedValue(search, 250);
  const { data: products = [], isFetching: searching } = useProductPicker(q);
  const { data: locations = [] } = useQuery({
    queryKey: ['cost-explanation', 'locations'],
    enabled: has(PERMISSIONS.inventoryLocation.read),
    staleTime: 60_000,
    queryFn: async () =>
      (await api.get<{ data: Array<{ id: string; code?: string | null; name: string }> }>('/inventory/locations', { params: { pageSize: 500 } })).data.data ?? [],
  });
  const explanation = useQuery({
    queryKey: ['cost-explanation', productId, locationId, limit],
    enabled: !!productId,
    queryFn: async () =>
      (await api.get<Explanation>('/inventory/cost-explanation', {
        params: { productId, locationId: locationId || undefined, limit },
      })).data,
  });
  const set = (patch: Record<string, string>) => {
    const next = new URLSearchParams(params);
    for (const [k, v] of Object.entries(patch)) {
      if (v) next.set(k, v);
      else next.delete(k);
    }
    setParams(next, { replace: true });
  };
  const [tracing, setTracing] = useState<Movement | null>(null);
  const data = explanation.data;
  // tolerate a partial response rather than white-screen
  const summary = data?.summary ?? [];
  const byLocation = data?.locations ?? [];
  const lifeAdjustments = data?.adjustments ?? [];
  const openAdjuncts = data?.openAdjuncts ?? [];
  const movements = data?.movements ?? [];
  const totals: Partial<Explanation['totals']> = data?.totals ?? {};
  const product: Partial<Explanation['product']> = data?.product ?? {};

  return (
    <div className="space-y-4">
      <div>
        <h1 className="text-2xl font-semibold">{tx('Why does this item cost what it does?')}</h1>
        <p className="text-sm text-muted-foreground">
          {tx('Pick an item to see its cost built up step by step: every receipt, issue and cost change, with what each one did to the cost per unit.')}
        </p>
      </div>
      <Card>
        <CardContent className="flex flex-wrap items-end gap-3 py-4">
          <div className="grid w-full max-w-md gap-1">
            <Label className="text-xs text-muted-foreground">{tx('Item')}</Label>
            <SearchableSelect
              value={productId}
              onValueChange={(v) => {
                const p = products.find((x) => x.id === v);
                setPickedLabel(p ? `${p.code} · ${p.name}` : null);
                setLimit(100);
                set({ productId: v });
              }}
              options={products.map((p) => ({ value: p.id, label: p.name, code: p.code }))}
              onQueryChange={setSearch}
              selectedLabel={product.name ? `${product.sku ? `${product.sku} · ` : ''}${product.name}` : pickedLabel}
              loading={searching}
              placeholder={tx('Search an item…')}
              searchPlaceholder={tx('Type at least 2 characters…')}
              wrap
            />
          </div>
          <div className="grid gap-1">
            <Label className="text-xs text-muted-foreground">{tx('Location')}</Label>
            <select className={SELECT} value={locationId} onChange={(e) => set({ locationId: e.target.value })}>
              <option value="">{tx('All locations')}</option>
              {locations.map((l) => <option key={l.id} value={l.id}>{l.name || s(l.code)}</option>)}
            </select>
          </div>
        </CardContent>
      </Card>

      {!productId ? (
        <p className="text-sm text-muted-foreground">{tx('Choose an item to begin.')}</p>
      ) : explanation.isLoading ? (
        <p className="flex items-center gap-2 text-sm text-muted-foreground"><Loader2 className="h-4 w-4 animate-spin" />{tx('Working out the cost…')}</p>
      ) : explanation.isError || !data ? (
        <p className="text-sm text-destructive">{tx('The explanation could not be loaded.')}</p>
      ) : (
        <>
          <Card>
            <CardHeader>
              <CardTitle className="flex flex-wrap items-center gap-2">
                {product.name}
                {product.sku && <code className="rounded bg-muted px-2 py-0.5 text-xs">{product.sku}</code>}
                {data.method?.label && <Badge variant="secondary">{data.method.label}</Badge>}
              </CardTitle>
              <CardDescription className="max-w-4xl">{data.method?.explanation}</CardDescription>
            </CardHeader>
            <CardContent className="space-y-4">
              {summary.length > 0 && (
                <ul className="space-y-1 rounded-md bg-muted/50 p-3 text-sm">
                  {summary.map((line, i) => <li key={i}>• {line}</li>)}
                </ul>
              )}
              <div className="grid gap-2 sm:grid-cols-2 lg:grid-cols-4">
                <Stat label={tx('Quantity on hand')} value={`${qty(totals.quantity)}${product.uomCode ? ` ${product.uomCode}` : ''}`} />
                <Stat label={tx('Stock value (books)')} value={money(num(totals.value))} />
                <Stat label={tx('Cost per unit')} value={unit(totals.unitCost)} />
                {product.standardCost != null && <Stat label={tx('Standard cost per unit')} value={unit(product.standardCost)} />}
                {num(totals.priceDifference) !== 0 && (
                  <Stat label={tx('Price difference beside stock')} value={money(num(totals.priceDifference))} hint={tx('Paid above (+) or below (−) the standard; released as the stock is used or sold.')} />
                )}
                {num(totals.nrvAllowance) !== 0 && (
                  <Stat label={tx('NRV allowance')} value={money(num(totals.nrvAllowance))} hint={tx('Write-down because the item sells for less than it cost.')} />
                )}
                {(num(totals.priceDifference) !== 0 || num(totals.nrvAllowance) !== 0) && (
                  <Stat label={tx('Carrying cost per unit')} value={unit(totals.carryingUnitCost)} hint={tx('Cost per unit including the amounts beside stock.')} />
                )}
              </div>
            </CardContent>
          </Card>

          {byLocation.length > 0 && (
            <Card>
              <CardHeader><CardTitle>{tx('By location')}</CardTitle></CardHeader>
              <CardContent className="overflow-x-auto">
                <Table>
                  <TableHeader>
                    <TableRow>
                      <TableHead>{tx('Location')}</TableHead>
                      <TableHead className="text-right">{tx('Quantity')}</TableHead>
                      <TableHead className="text-right">{tx('Cost per unit')}</TableHead>
                      <TableHead className="text-right">{tx('Stock value')}</TableHead>
                      <TableHead className="text-right">{tx('Price difference')}</TableHead>
                      <TableHead className="text-right">{tx('NRV allowance')}</TableHead>
                      <TableHead className="text-right">{tx('Carrying value')}</TableHead>
                      <TableHead className="text-right">{tx('Carrying cost per unit')}</TableHead>
                    </TableRow>
                  </TableHeader>
                  <TableBody>
                    {byLocation.map((l) => (
                      <TableRow key={l.locationId}>
                        <TableCell className="font-medium">
                          <button type="button" className="text-left hover:underline" onClick={() => set({ locationId: l.locationId })}>{l.locationName}</button>
                        </TableCell>
                        <TableCell className="text-right tabular-nums">{qty(l.quantity)}</TableCell>
                        <TableCell className="text-right tabular-nums">{unit(l.unitCost)}</TableCell>
                        <TableCell className="text-right tabular-nums">{money(num(l.value))}</TableCell>
                        <TableCell className="text-right tabular-nums">{num(l.priceDifference) === 0 ? '—' : money(num(l.priceDifference))}</TableCell>
                        <TableCell className="text-right tabular-nums">{num(l.nrvAllowance) === 0 ? '—' : money(num(l.nrvAllowance))}</TableCell>
                        <TableCell className="text-right tabular-nums">{money(num(l.carryingValue))}</TableCell>
                        <TableCell className="text-right tabular-nums">{unit(l.carryingUnitCost)}</TableCell>
                      </TableRow>
                    ))}
                  </TableBody>
                </Table>
              </CardContent>
            </Card>
          )}

          <div className="grid gap-4 lg:grid-cols-2">
            <Card>
              <CardHeader>
                <CardTitle>{tx('Cost changes over the item’s life')}</CardTitle>
                <CardDescription>{tx('Changes to value without a change in quantity: landed costs, late costs, revaluations and write-downs.')}</CardDescription>
              </CardHeader>
              <CardContent>
                {lifeAdjustments.length === 0 ? (
                  <p className="text-sm text-muted-foreground">{tx('None: the cost comes from the receipts alone.')}</p>
                ) : (
                  <Table>
                    <TableHeader>
                      <TableRow>
                        <TableHead>{tx('Kind')}</TableHead>
                        <TableHead className="text-right">{tx('Times')}</TableHead>
                        <TableHead className="text-right">{tx('Amount')}</TableHead>
                      </TableRow>
                    </TableHeader>
                    <TableBody>
                      {lifeAdjustments.map((a) => (
                        <TableRow key={a.referenceType}>
                          <TableCell>{a.label}</TableCell>
                          <TableCell className="text-right tabular-nums">{num(a.count)}</TableCell>
                          <TableCell className="text-right tabular-nums">{signed(a.amount)}</TableCell>
                        </TableRow>
                      ))}
                    </TableBody>
                  </Table>
                )}
              </CardContent>
            </Card>
            <Card>
              <CardHeader>
                <CardTitle>{tx('Amounts waiting beside stock')}</CardTitle>
                <CardDescription>{tx('Kept apart from the cost per unit and released to wherever the stock goes. Release them in the cost close.')}</CardDescription>
              </CardHeader>
              <CardContent className="space-y-2">
                {openAdjuncts.length === 0 ? (
                  <p className="text-sm text-muted-foreground">{tx('Nothing waiting.')}</p>
                ) : (
                  <Table>
                    <TableHeader>
                      <TableRow>
                        <TableHead>{tx('Location')}</TableHead>
                        <TableHead>{tx('Kind')}</TableHead>
                        <TableHead className="text-right">{tx('Open amount')}</TableHead>
                      </TableRow>
                    </TableHeader>
                    <TableBody>
                      {openAdjuncts.map((a, i) => (
                        <TableRow key={`${a.locationId}-${a.component}-${i}`}>
                          <TableCell>{a.locationName}</TableCell>
                          <TableCell>{a.label}</TableCell>
                          <TableCell className="text-right tabular-nums">{money(num(a.openAmount))}</TableCell>
                        </TableRow>
                      ))}
                    </TableBody>
                  </Table>
                )}
                {openAdjuncts.length > 0 && has(PERMISSIONS.costingClose.read) && (
                  <Link to="/costing/close?tab=adjuncts" className="inline-flex items-center gap-1 text-sm text-primary hover:underline">
                    {tx('Open the cost close')} <ExternalLink className="h-3 w-3" />
                  </Link>
                )}
              </CardContent>
            </Card>
          </div>

          <Card>
            <CardHeader>
              <CardTitle>{tx('Step by step')}</CardTitle>
              <CardDescription>
                {tx('Newest first. Each line shows the movement and the quantity, value and cost per unit right after it. Use “Trace” on a receipt to see what was added to it later and where that went.')}
              </CardDescription>
            </CardHeader>
            <CardContent className="space-y-3">
              <div className="overflow-x-auto">
                <Table>
                  <TableHeader>
                    <TableRow>
                      <TableHead>{tx('When')}</TableHead>
                      <TableHead>{tx('What happened')}</TableHead>
                      <TableHead>{tx('Document')}</TableHead>
                      {!locationId && <TableHead>{tx('Location')}</TableHead>}
                      <TableHead className="text-right">{tx('Quantity')}</TableHead>
                      <TableHead className="text-right">{tx('At')}</TableHead>
                      <TableHead className="text-right">{tx('Value')}</TableHead>
                      <TableHead className="text-right">{tx('Quantity after')}</TableHead>
                      <TableHead className="text-right">{tx('Value after')}</TableHead>
                      <TableHead className="text-right">{tx('Cost per unit after')}</TableHead>
                      <TableHead />
                    </TableRow>
                  </TableHeader>
                  <TableBody>
                    {movements.map((m) => {
                      const dq = num(m.quantityChange);
                      return (
                        <TableRow key={m.ledgerId}>
                          <TableCell className="whitespace-nowrap text-sm">{dateTime(m.createdAt)}</TableCell>
                          <TableCell>
                            {m.label || moveLabel(m.type)}
                            {m.component && <Badge variant="outline" className="ml-2 text-xs">{COMPONENTS[m.component]?.() ?? m.component}</Badge>}
                          </TableCell>
                          <TableCell className="text-sm">{m.documentNo ?? referenceLabel(m.referenceType)}</TableCell>
                          {!locationId && <TableCell className="text-sm">{m.locationName}</TableCell>}
                          <TableCell className={`text-right tabular-nums ${dq < 0 ? 'text-destructive' : ''}`}>{dq === 0 ? '—' : `${dq > 0 ? '+' : ''}${qty(dq)}`}</TableCell>
                          <TableCell className="text-right tabular-nums">{dq === 0 ? '—' : unit(m.unitCost)}</TableCell>
                          <TableCell className="text-right tabular-nums">{signed(m.value)}</TableCell>
                          <TableCell className="text-right tabular-nums">{qty(m.runningQuantity)}</TableCell>
                          <TableCell className="text-right tabular-nums">{money(num(m.runningValue))}</TableCell>
                          <TableCell className="text-right font-medium tabular-nums">{unit(m.runningUnitCost)}</TableCell>
                          <TableCell className="whitespace-nowrap text-right">
                            {m.traceable && (
                              <Button size="sm" variant="outline" onClick={() => setTracing(m)}>
                                <RouteIcon className="h-3.5 w-3.5" /> {tx('Trace')}
                              </Button>
                            )}
                            <Button size="sm" variant="ghost" asChild>
                              <Link to={`/inventory/ledger/${m.ledgerId}`}>{tx('View')}</Link>
                            </Button>
                          </TableCell>
                        </TableRow>
                      );
                    })}
                  </TableBody>
                </Table>
              </div>
              <div className="flex flex-wrap items-center justify-between gap-2 text-sm text-muted-foreground">
                <span>{tx('Showing the latest {{shown}} of {{total}} movements.', { shown: data.movementsShown ?? movements.length, total: data.movementsTotal ?? movements.length })}</span>
                {(data.movementsShown ?? 0) < (data.movementsTotal ?? 0) && limit < 500 && (
                  <Button size="sm" variant="outline" onClick={() => setLimit(Math.min(500, limit * 2 + 100))}>{tx('Show more')}</Button>
                )}
              </div>
            </CardContent>
          </Card>
        </>
      )}

      <TraceDialog movement={tracing} onClose={() => setTracing(null)} />
    </div>
  );
}

function TraceDialog({ movement, onClose }: { movement: Movement | null; onClose: () => void }) {
  const ledgerId = movement?.ledgerId ?? '';
  const trace = useQuery({
    queryKey: ['cost-explanation', 'trace', ledgerId],
    enabled: !!ledgerId,
    queryFn: async () => (await api.get<Row>(`/inventory/cost-layers/${ledgerId}/trace`)).data,
  });
  const [amount, setAmount] = useState('1000');
  const [asked, setAsked] = useState('');
  const distribution = useQuery({
    queryKey: ['cost-explanation', 'distribution', ledgerId, asked],
    enabled: !!ledgerId && asked !== '' && Number.isFinite(Number(asked)),
    queryFn: async () => (await api.get<Row>(`/inventory/cost-layers/${ledgerId}/distribution`, { params: { amount: asked } })).data,
  });
  const ledger = (trace.data?.ledger as Row | undefined) ?? {};
  const adjustments = (trace.data?.adjustmentsAsLayer as Row[] | undefined) ?? [];
  const received = (trace.data?.sharesReceived as Row[] | undefined) ?? [];
  const byTarget = useMemo(() => Object.entries((distribution.data?.byTarget as Record<string, unknown> | undefined) ?? {}).filter(([, v]) => num(v) !== 0), [distribution.data]);
  const consumed = (distribution.data?.consumed as Row[] | undefined) ?? [];

  return (
    <Dialog open={!!movement} onOpenChange={(open) => { if (!open) { onClose(); setAsked(''); } }}>
      <DialogContent className="max-h-[85vh] max-w-3xl overflow-y-auto">
        <DialogHeader>
          <DialogTitle>{tx('Trace of {{what}}', { what: movement ? `${movement.label || moveLabel(movement.type)} ${movement.documentNo ?? ''}`.trim() : '' })}</DialogTitle>
          <DialogDescription>
            {tx('What this receipt cost when it arrived, what was added to it later, and where each addition went — stock still on hand, cost of sales, or production.')}
          </DialogDescription>
        </DialogHeader>
        {trace.isLoading ? (
          <p className="text-sm text-muted-foreground">{tx('Loading…')}</p>
        ) : (
          <div className="space-y-4 text-sm">
            <div className="grid gap-2 sm:grid-cols-3">
              <Stat label={tx('Received')} value={qty(ledger.quantityChange)} />
              <Stat label={tx('At (when received)')} value={unit(trace.data?.originalUnitCost)} />
              <Stat label={tx('Added later')} value={money(num(trace.data?.adjustedTotal))} hint={tx('Reversed additions are left out.')} />
            </div>

            <div>
              <p className="mb-1 font-medium">{tx('Added to this receipt later')}</p>
              {adjustments.length === 0 ? (
                <p className="text-muted-foreground">{tx('Nothing was added to this receipt after it arrived.')}</p>
              ) : (
                <div className="space-y-2">
                  {adjustments.map((a) => {
                    const lines = (a.lines as Row[] | undefined) ?? [];
                    const where = new Map<string, number>();
                    for (const l of lines) where.set(s(l.target), (where.get(s(l.target)) ?? 0) + num(l.amount));
                    return (
                      <div key={s(a.id)} className="rounded-md border p-2">
                        <div className="flex flex-wrap items-center justify-between gap-2">
                          <span className="font-medium">{adjustmentLabel(a.adjustmentType)}</span>
                          <span className="tabular-nums">{signed(a.amount)}</span>
                        </div>
                        <div className="text-xs text-muted-foreground">
                          {referenceLabel(s(a.sourceType))} · {dateTime(s(a.createdAt))}
                          {a.reversedAt != null && <Badge variant="secondary" className="ml-2">{tx('Reversed')}</Badge>}
                        </div>
                        {where.size > 0 && (
                          <ul className="mt-1 space-y-0.5">
                            {[...where.entries()].map(([t, v]) => (
                              <li key={t} className="flex justify-between gap-2"><span>{targetLabel(t)}</span><span className="tabular-nums">{money(v)}</span></li>
                            ))}
                          </ul>
                        )}
                      </div>
                    );
                  })}
                </div>
              )}
            </div>

            {received.length > 0 && (
              <div>
                <p className="mb-1 font-medium">{tx('This movement itself took part of a later cost')}</p>
                <ul className="space-y-0.5">
                  {received.map((r) => (
                    <li key={s(r.id)} className="flex justify-between gap-2">
                      <span>{adjustmentLabel(r.adjustmentType)} → {targetLabel(r.target)}</span>
                      <span className="tabular-nums">{money(num(r.amount))}</span>
                    </li>
                  ))}
                </ul>
              </div>
            )}

            <div className="space-y-2 rounded-md border p-3">
              <p className="font-medium">{tx('Where would a late cost go?')}</p>
              <p className="text-muted-foreground">{tx('If a further cost were added to this receipt today (a late freight bill, say), this is how it would be split.')}</p>
              <div className="flex gap-2">
                <Input type="number" inputMode="decimal" className="w-40" value={amount} onChange={(e) => setAmount(e.target.value)} />
                <Button variant="outline" disabled={amount.trim() === '' || !Number.isFinite(Number(amount))} onClick={() => setAsked(amount.trim())}>{tx('Show the split')}</Button>
              </div>
              {distribution.isFetching && <p className="text-muted-foreground">{tx('Loading…')}</p>}
              {distribution.data && (
                <>
                  <p>{tx('{{pct}} of this receipt is still in stock.', { pct: `${(num(distribution.data.survival) * 100).toFixed(1)} %` })}</p>
                  <ul className="space-y-0.5">
                    {byTarget.map(([t, v]) => (
                      <li key={t} className="flex justify-between gap-2"><span>{targetLabel(t)}</span><span className="font-medium tabular-nums">{money(num(v))}</span></li>
                    ))}
                  </ul>
                  {consumed.length > 0 && (
                    <details>
                      <summary className="cursor-pointer text-muted-foreground">{tx('The movements that used this receipt ({{n}})', { n: consumed.length })}</summary>
                      <ul className="mt-1 space-y-0.5">
                        {consumed.slice(0, 50).map((c, i) => (
                          <li key={`${s(c.ledgerId)}-${i}`} className="flex justify-between gap-2">
                            <span>{moveLabel(c.moveType)} · {referenceLabel(s(c.referenceType))} → {targetLabel(c.target)}</span>
                            <span className="tabular-nums">{money(num(c.amount))}</span>
                          </li>
                        ))}
                      </ul>
                    </details>
                  )}
                </>
              )}
            </div>
          </div>
        )}
      </DialogContent>
    </Dialog>
  );
}

export default CostExplanationPage;

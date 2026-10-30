import { useEffect, useState } from 'react';
import { Plus, Save, Trash2 } from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { useAuthStore } from '@/stores/auth.store';
import { notify } from '@/lib/notify';
import { tx } from '@/lib/i18n/tx';
import { useLocationOptions, useProductOptions } from '@/features/manufacturing/options';
import {
  type AllocationMethod,
  type CoProduct,
  useBomStageSetup,
  useRoutingStageSetup,
  useSaveBomStageSetup,
  useSaveRoutingStageSetup,
} from '@/features/manufacturing/api-stages';

const NONE = '__none';
const errMsg = (e: any) => {
  const m = e?.response?.data?.message ?? e?.message;
  return Array.isArray(m) ? m.join(', ') : m;
};
const numOrNull = (v: unknown) => (v === '' || v === null || v === undefined ? null : Number(v));

type StageRow = {
  operationId: string;
  name: string;
  sequence: number;
  isLast: boolean;
  issueMode: 'backflush' | 'manual';
  outputProductId: string;
  outputPerUnit: string;
  stageLocationId: string;
  expectedYieldPct: string;
  qcGate: boolean;
  qcCheckpointCode: string;
  materialProductIds: string[];
};

const METHODS: Array<{ value: AllocationMethod; label: () => string }> = [
  { value: 'quantity', label: () => tx('By quantity') },
  { value: 'weight', label: () => tx('By weight') },
  { value: 'market_value', label: () => tx('By market value') },
  { value: 'fixed_pct', label: () => tx('By fixed %') },
];

/**
 * ADR-S011 set-up on the BOM page: stage behaviour of the routing's operations (issue mode, intermediate product,
 * stage location, yield, QC gate, materials per stage) and the BOM's co-products / by-products / waste with the
 * joint-cost allocation rule.
 */
export function BomStageSetupCard({ bom }: { bom: { id: string; routingId?: string | null; outputProductId: string; status: string; lines?: Array<{ componentProductId: string }> } }) {
  const hasPermission = useAuthStore((s) => s.hasPermission);
  const canEditBom = hasPermission(PERMISSIONS.bom.update) && bom.status !== 'archived';
  const canEditRouting = hasPermission(PERMISSIONS.routing.create);
  const { data: products } = useProductOptions();
  const { data: locations } = useLocationOptions();
  const { data: routing } = useRoutingStageSetup(bom.routingId);
  const { data: setup } = useBomStageSetup(bom.id);
  const saveRouting = useSaveRoutingStageSetup();
  const saveBom = useSaveBomStageSetup();

  const [stages, setStages] = useState<StageRow[]>([]);
  const [cos, setCos] = useState<CoProduct[]>([]);
  const [override, setOverride] = useState(false);
  const [method, setMethod] = useState<AllocationMethod>('quantity');
  const [mainWeight, setMainWeight] = useState('');
  const [mainMarket, setMainMarket] = useState('');

  useEffect(() => {
    if (!routing) return;
    setStages(routing.operations.map((o) => ({
      operationId: o.id,
      name: o.name,
      sequence: o.sequence,
      isLast: o.isLast,
      issueMode: o.stage?.issueMode ?? 'backflush',
      outputProductId: o.stage?.outputProductId ?? '',
      outputPerUnit: String(Number(o.stage?.outputPerUnit ?? 1)),
      stageLocationId: o.stage?.stageLocationId ?? '',
      expectedYieldPct: String(Number(o.stage?.expectedYieldPct ?? 100)),
      qcGate: !!o.stage?.qcGate,
      qcCheckpointCode: o.stage?.qcCheckpointCode ?? '',
      materialProductIds: o.stage?.materialProductIds ?? [],
    })));
  }, [routing]);

  useEffect(() => {
    if (!setup) return;
    setCos(setup.coProducts.map((c) => ({ ...c, quantity: String(Number(c.quantity)) })));
    setOverride(!!setup.allocationRule);
    setMethod((setup.allocationRule?.method ?? setup.effectiveRule.method) as AllocationMethod);
    setMainWeight(setup.allocationRule?.mainWeightPerUnit != null ? String(Number(setup.allocationRule.mainWeightPerUnit)) : '');
    setMainMarket(setup.allocationRule?.mainMarketValue != null ? String(Number(setup.allocationRule.mainMarketValue)) : '');
  }, [setup]);

  const productList = products ?? [];
  const locationList = (locations ?? []).filter((l: any) => l.isActive !== false);
  const components = (bom.lines ?? []).map((l) => l.componentProductId);
  const name = (id: string) => productList.find((p) => p.id === id)?.name ?? id.slice(0, 8);
  const patch = (i: number, p: Partial<StageRow>) => setStages((xs) => xs.map((x, j) => (j === i ? { ...x, ...p } : x)));
  const patchCo = (i: number, p: Partial<CoProduct>) => setCos((xs) => xs.map((x, j) => (j === i ? { ...x, ...p } : x)));

  const doSaveRouting = async () => {
    if (!bom.routingId) return;
    for (const s of stages) {
      const y = Number(s.expectedYieldPct);
      if (!(y > 0 && y <= 100)) return notify.error(tx('Expected yield must be between 0 and 100.'));
    }
    try {
      await saveRouting.mutateAsync({
        routingId: bom.routingId,
        stages: stages.map((s) => ({
          operationId: s.operationId,
          issueMode: s.issueMode,
          outputProductId: s.isLast || !s.outputProductId ? null : s.outputProductId,
          outputPerUnit: Number(s.outputPerUnit) > 0 ? Number(s.outputPerUnit) : 1,
          stageLocationId: s.stageLocationId || null,
          expectedYieldPct: Number(s.expectedYieldPct),
          qcGate: s.qcGate,
          qcCheckpointCode: s.qcCheckpointCode || null,
          materialProductIds: s.materialProductIds,
        })),
      });
      notify.success(tx('Stage set-up saved.'));
    } catch (e) {
      notify.error(tx('Save failed'), errMsg(e));
    }
  };

  const doSaveBom = async () => {
    if (cos.some((c) => !c.productId)) return notify.error(tx('Pick a product for every output line.'));
    try {
      await saveBom.mutateAsync({
        bomId: bom.id,
        body: {
          coProducts: cos.map((c) => ({
            productId: c.productId,
            role: c.role,
            operationId: c.routingOperationId || null,
            quantity: Number(c.quantity) || 0,
            valuation: c.valuation,
            nrvUnitValue: numOrNull(c.nrvUnitValue),
            marketValue: numOrNull(c.marketValue),
            weightPerUnit: numOrNull(c.weightPerUnit),
            fixedPct: numOrNull(c.fixedPct),
            stocked: c.stocked,
          })),
          allocationRule: override ? { method, mainWeightPerUnit: numOrNull(mainWeight), mainMarketValue: numOrNull(mainMarket) } : null,
        },
      });
      notify.success(tx('Co-products and allocation saved.'));
    } catch (e) {
      notify.error(tx('Save failed'), errMsg(e));
    }
  };

  return (
    <div className="space-y-6">
      <Card>
        <CardHeader><CardTitle>{tx('Stages (routing set-up)')}</CardTitle></CardHeader>
        <CardContent className="space-y-3">
          {!bom.routingId ? (
            <p className="text-sm text-muted-foreground">{tx('Link a routing to this BOM to run its orders in stages.')}</p>
          ) : stages.length === 0 ? (
            <p className="text-sm text-muted-foreground">{tx('The routing has no operations.')}</p>
          ) : (
            <>
              {stages.map((s, i) => (
                <div key={s.operationId} className="rounded border p-3">
                  <p className="mb-2 text-sm font-medium">{s.sequence}. {s.name}{s.isLast ? ` · ${tx('final stage (order output)')}` : ''}</p>
                  <div className="grid gap-2 sm:grid-cols-2 lg:grid-cols-4">
                    <div className="space-y-1">
                      <Label className="text-xs">{tx('Issue mode')}</Label>
                      <Select value={s.issueMode} onValueChange={(v) => patch(i, { issueMode: v as StageRow['issueMode'] })} disabled={!canEditRouting}>
                        <SelectTrigger><SelectValue /></SelectTrigger>
                        <SelectContent>
                          <SelectItem value="backflush">{tx('Backflush')}</SelectItem>
                          <SelectItem value="manual">{tx('Manual issue')}</SelectItem>
                        </SelectContent>
                      </Select>
                    </div>
                    {!s.isLast && (
                      <div className="space-y-1">
                        <Label className="text-xs">{tx('Intermediate product')}</Label>
                        <Select value={s.outputProductId || NONE} onValueChange={(v) => patch(i, { outputProductId: v === NONE ? '' : v })} disabled={!canEditRouting}>
                          <SelectTrigger><SelectValue /></SelectTrigger>
                          <SelectContent>
                            <SelectItem value={NONE}>{tx('None (pass-through)')}</SelectItem>
                            {productList.filter((p) => p.id !== bom.outputProductId).map((p) => <SelectItem key={p.id} value={p.id}>{p.code} · {p.name}</SelectItem>)}
                          </SelectContent>
                        </Select>
                      </div>
                    )}
                    {!s.isLast && (
                      <div className="space-y-1">
                        <Label className="text-xs">{tx('Stage location')}</Label>
                        <Select value={s.stageLocationId || NONE} onValueChange={(v) => patch(i, { stageLocationId: v === NONE ? '' : v })} disabled={!canEditRouting}>
                          <SelectTrigger><SelectValue /></SelectTrigger>
                          <SelectContent>
                            <SelectItem value={NONE}>{tx('Production location')}</SelectItem>
                            {locationList.map((l: any) => <SelectItem key={l.id} value={l.id}>{l.code} · {l.name}</SelectItem>)}
                          </SelectContent>
                        </Select>
                      </div>
                    )}
                    {!s.isLast && (
                      <div className="space-y-1">
                        <Label className="text-xs">{tx('Intermediate per output unit')}</Label>
                        <Input type="number" min="0" step="any" value={s.outputPerUnit} disabled={!canEditRouting} onChange={(e) => patch(i, { outputPerUnit: e.target.value })} />
                      </div>
                    )}
                    <div className="space-y-1">
                      <Label className="text-xs">{tx('Expected yield %')}</Label>
                      <Input type="number" min="0" max="100" step="any" value={s.expectedYieldPct} disabled={!canEditRouting} onChange={(e) => patch(i, { expectedYieldPct: e.target.value })} />
                    </div>
                    <div className="space-y-1">
                      <Label className="text-xs">{tx('QC checkpoint code')}</Label>
                      <Input value={s.qcCheckpointCode} disabled={!canEditRouting || !s.qcGate} onChange={(e) => patch(i, { qcCheckpointCode: e.target.value })} />
                    </div>
                    <label className="flex items-center gap-2 self-end pb-2 text-sm">
                      <input type="checkbox" checked={s.qcGate} disabled={!canEditRouting} onChange={(e) => patch(i, { qcGate: e.target.checked })} />
                      {tx('QC gate blocks the next stage')}
                    </label>
                  </div>
                  {components.length > 0 && (
                    <div className="mt-2 flex flex-wrap gap-3 text-xs">
                      <span className="text-muted-foreground">{tx('Materials consumed here')}:</span>
                      {components.map((c) => (
                        <label key={c} className="flex items-center gap-1">
                          <input type="checkbox" disabled={!canEditRouting} checked={s.materialProductIds.includes(c)}
                            onChange={(e) => patch(i, { materialProductIds: e.target.checked ? [...s.materialProductIds, c] : s.materialProductIds.filter((x) => x !== c) })} />
                          {name(c)}
                        </label>
                      ))}
                    </div>
                  )}
                </div>
              ))}
              <p className="text-xs text-muted-foreground">{tx('Components not ticked on any stage are consumed at the first stage.')}</p>
              {canEditRouting && <Button size="sm" onClick={doSaveRouting} disabled={saveRouting.isPending}><Save className="mr-1 h-3 w-3" /> {tx('Save stage set-up')}</Button>}
            </>
          )}
        </CardContent>
      </Card>

      <Card>
        <CardHeader><CardTitle>{tx('Co-products, by-products & waste')}</CardTitle></CardHeader>
        <CardContent className="space-y-3 overflow-x-auto">
          {cos.length === 0 && <p className="text-sm text-muted-foreground">{tx('No co-products. The order output takes the whole cost.')}</p>}
          {cos.length > 0 && (
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>{tx('Product')}</TableHead>
                  <TableHead>{tx('Role')}</TableHead>
                  <TableHead>{tx('Stage')}</TableHead>
                  <TableHead>{tx('Qty per BOM')}</TableHead>
                  <TableHead>{tx('Valuation')}</TableHead>
                  <TableHead>{tx('NRV / unit')}</TableHead>
                  <TableHead>{tx('Market value')}</TableHead>
                  <TableHead>{tx('Weight / unit')}</TableHead>
                  <TableHead>{tx('Fixed %')}</TableHead>
                  <TableHead>{tx('Stocked')}</TableHead>
                  <TableHead />
                </TableRow>
              </TableHeader>
              <TableBody>
                {cos.map((c, i) => (
                  <TableRow key={i}>
                    <TableCell className="min-w-48">
                      <Select value={c.productId || NONE} onValueChange={(v) => patchCo(i, { productId: v === NONE ? '' : v })} disabled={!canEditBom}>
                        <SelectTrigger><SelectValue /></SelectTrigger>
                        <SelectContent>
                          <SelectItem value={NONE}>{tx('Select…')}</SelectItem>
                          {productList.filter((p) => p.id !== bom.outputProductId).map((p) => <SelectItem key={p.id} value={p.id}>{p.code} · {p.name}</SelectItem>)}
                        </SelectContent>
                      </Select>
                    </TableCell>
                    <TableCell className="min-w-32">
                      <Select value={c.role} disabled={!canEditBom}
                        onValueChange={(v) => patchCo(i, { role: v as CoProduct['role'], valuation: v === 'co_product' ? 'allocated' : v === 'by_product' ? 'nrv' : 'zero' })}>
                        <SelectTrigger><SelectValue /></SelectTrigger>
                        <SelectContent>
                          <SelectItem value="co_product">{tx('Co-product')}</SelectItem>
                          <SelectItem value="by_product">{tx('By-product')}</SelectItem>
                          <SelectItem value="waste">{tx('Waste / fines')}</SelectItem>
                        </SelectContent>
                      </Select>
                    </TableCell>
                    <TableCell className="min-w-32">
                      <Select value={c.routingOperationId || NONE} onValueChange={(v) => patchCo(i, { routingOperationId: v === NONE ? null : v })} disabled={!canEditBom}>
                        <SelectTrigger><SelectValue /></SelectTrigger>
                        <SelectContent>
                          <SelectItem value={NONE}>{tx('Final stage')}</SelectItem>
                          {(routing?.operations ?? []).map((o) => <SelectItem key={o.id} value={o.id}>{o.sequence}. {o.name}</SelectItem>)}
                        </SelectContent>
                      </Select>
                    </TableCell>
                    <TableCell><Input className="w-24" type="number" min="0" step="any" value={String(c.quantity)} disabled={!canEditBom} onChange={(e) => patchCo(i, { quantity: e.target.value })} /></TableCell>
                    <TableCell className="min-w-32">
                      <Select value={c.valuation} onValueChange={(v) => patchCo(i, { valuation: v as CoProduct['valuation'] })} disabled={!canEditBom}>
                        <SelectTrigger><SelectValue /></SelectTrigger>
                        <SelectContent>
                          {c.role !== 'waste' && <SelectItem value="allocated">{tx('Shares joint cost')}</SelectItem>}
                          {c.role !== 'co_product' && <SelectItem value="nrv">{tx('Net realisable value')}</SelectItem>}
                          {c.role !== 'co_product' && <SelectItem value="zero">{tx('Zero value')}</SelectItem>}
                        </SelectContent>
                      </Select>
                    </TableCell>
                    <TableCell><Input className="w-24" type="number" min="0" step="any" value={c.nrvUnitValue == null ? '' : String(c.nrvUnitValue)} disabled={!canEditBom || c.valuation !== 'nrv'} onChange={(e) => patchCo(i, { nrvUnitValue: e.target.value })} /></TableCell>
                    <TableCell><Input className="w-24" type="number" min="0" step="any" value={c.marketValue == null ? '' : String(c.marketValue)} disabled={!canEditBom} onChange={(e) => patchCo(i, { marketValue: e.target.value })} /></TableCell>
                    <TableCell><Input className="w-24" type="number" min="0" step="any" value={c.weightPerUnit == null ? '' : String(c.weightPerUnit)} disabled={!canEditBom} onChange={(e) => patchCo(i, { weightPerUnit: e.target.value })} /></TableCell>
                    <TableCell><Input className="w-20" type="number" min="0" max="100" step="any" value={c.fixedPct == null ? '' : String(c.fixedPct)} disabled={!canEditBom} onChange={(e) => patchCo(i, { fixedPct: e.target.value })} /></TableCell>
                    <TableCell>
                      <input type="checkbox" checked={c.stocked} disabled={!canEditBom}
                        onChange={(e) => patchCo(i, { stocked: e.target.checked, valuation: e.target.checked ? c.valuation : 'zero' })} />
                    </TableCell>
                    <TableCell>{canEditBom && <Button size="icon" variant="ghost" onClick={() => setCos((xs) => xs.filter((_, j) => j !== i))}><Trash2 className="h-4 w-4" /></Button>}</TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          )}
          <div className="grid gap-2 sm:grid-cols-2 lg:grid-cols-4">
            <label className="flex items-center gap-2 self-end pb-2 text-sm">
              <input type="checkbox" checked={override} disabled={!canEditBom} onChange={(e) => setOverride(e.target.checked)} />
              {tx('Own allocation rule for this BOM')}
            </label>
            <div className="space-y-1">
              <Label className="text-xs">{tx('Allocation method')}</Label>
              <Select value={method} onValueChange={(v) => setMethod(v as AllocationMethod)} disabled={!canEditBom || !override}>
                <SelectTrigger><SelectValue /></SelectTrigger>
                <SelectContent>{METHODS.map((m) => <SelectItem key={m.value} value={m.value}>{m.label()}</SelectItem>)}</SelectContent>
              </Select>
            </div>
            <div className="space-y-1">
              <Label className="text-xs">{tx('Main output weight / unit')}</Label>
              <Input type="number" min="0" step="any" value={mainWeight} disabled={!canEditBom || !override} onChange={(e) => setMainWeight(e.target.value)} />
            </div>
            <div className="space-y-1">
              <Label className="text-xs">{tx('Main output market value')}</Label>
              <Input type="number" min="0" step="any" value={mainMarket} disabled={!canEditBom || !override} onChange={(e) => setMainMarket(e.target.value)} />
            </div>
          </div>
          {!override && setup && (
            <p className="text-xs text-muted-foreground">{tx('Using the organization default')}: {METHODS.find((m) => m.value === setup.effectiveRule.method)?.label()}</p>
          )}
          {canEditBom && (
            <div className="flex flex-wrap gap-2">
              <Button size="sm" variant="outline" onClick={() => setCos((xs) => [...xs, { productId: '', role: 'co_product', quantity: '0', valuation: 'allocated', stocked: true }])}>
                <Plus className="mr-1 h-3 w-3" /> {tx('Add output')}
              </Button>
              <Button size="sm" onClick={doSaveBom} disabled={saveBom.isPending}><Save className="mr-1 h-3 w-3" /> {tx('Save co-products')}</Button>
            </div>
          )}
        </CardContent>
      </Card>
    </div>
  );
}

export default BomStageSetupCard;

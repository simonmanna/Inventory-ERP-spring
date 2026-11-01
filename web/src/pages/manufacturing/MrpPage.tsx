import { useEffect, useMemo, useState } from 'react';
import { CheckCheck, Play, Save, XCircle } from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Badge } from '@/components/ui/badge';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs';
import { useAuthStore } from '@/stores/auth.store';
import { notify } from '@/lib/notify';
import { tx } from '@/lib/i18n/tx';
import { useLocationOptions, useProductOptions } from '@/features/manufacturing/options';
import { usePartners } from '@/features/partners/api';
import {
  type PlanningParams,
  useFirmRun,
  useMrpCapacity,
  useMrpSettings,
  useSaveMrpSettings,
  useMrpRun,
  useMrpRuns,
  usePlannedOrderAction,
  usePlanningParams,
  useRunMrp,
  useSavePlanningParams,
} from '@/features/manufacturing/api-advanced';

const fmtDate = (s?: string) => (s ? new Date(s).toLocaleDateString() : '—');
const n = (s: string | number | undefined) => Number(s ?? 0).toLocaleString(undefined, { maximumFractionDigits: 3 });

/** Time-phased MRP: run, review the netting worksheet and planned orders, firm them; planning parameters. */
export function MrpPage() {
  const hasPermission = useAuthStore((s) => s.hasPermission);
  const canRun = hasPermission(PERMISSIONS.productionPlan.confirm);
  const canEdit = hasPermission(PERMISSIONS.productionPlan.update);
  const { data: products } = useProductOptions();
  const { data: locations } = useLocationOptions();
  const productName = useMemo(() => {
    const m = new Map((products ?? []).map((p) => [p.id, `${p.code} · ${p.name}`]));
    return (id: string) => m.get(id) ?? id;
  }, [products]);

  const { data: runs } = useMrpRuns();
  const [selected, setSelected] = useState<string>();
  const runId = selected ?? runs?.[0]?.id;
  const { data: run } = useMrpRun(runId);
  const runMrp = useRunMrp();
  const firmRun = useFirmRun();
  const action = usePlannedOrderAction();
  const [horizon, setHorizon] = useState('30');
  const [locationId, setLocationId] = useState('');

  const call = async (fn: () => Promise<unknown>, ok: string) => {
    try {
      await fn();
      notify.success(ok);
    } catch (e: any) {
      notify.error(tx('Failed'), e?.response?.data?.message ?? e?.message);
    }
  };

  const open = (run?.plannedOrders ?? []).filter((p) => p.status === 'planned');

  return (
    <div className="space-y-6 p-6">
      <div className="flex flex-wrap items-end justify-between gap-4">
        <div>
          <h1 className="text-2xl font-semibold">{tx('Material Requirements Planning')}</h1>
          <p className="text-sm text-muted-foreground">
            {tx('Multi-level netting with lead times, safety stock and lot sizing. Planned orders are only proposals until firmed.')}
          </p>
        </div>
        {canRun && (
          <div className="flex items-end gap-2">
            <div className="space-y-1">
              <Label>{tx('Horizon (days)')}</Label>
              <Input className="w-24" type="number" min="1" value={horizon} onChange={(e) => setHorizon(e.target.value)} />
            </div>
            <div className="space-y-1">
              <Label>{tx('Location')}</Label>
              <Select value={locationId || 'all'} onValueChange={(v) => setLocationId(v === 'all' ? '' : v)}>
                <SelectTrigger className="w-48"><SelectValue /></SelectTrigger>
                <SelectContent>
                  <SelectItem value="all">{tx('All locations')}</SelectItem>
                  {(locations ?? []).map((l: any) => <SelectItem key={l.id} value={l.id}>{l.name}</SelectItem>)}
                </SelectContent>
              </Select>
            </div>
            <Button
              disabled={runMrp.isPending}
              onClick={() => call(async () => {
                const r = await runMrp.mutateAsync({ horizonDays: Number(horizon) || 30, ...(locationId ? { locationId } : {}) });
                setSelected(r.id);
              }, tx('MRP run completed.'))}
            >
              <Play className="mr-2 h-4 w-4" /> {tx('Run MRP')}
            </Button>
          </div>
        )}
      </div>

      <Tabs defaultValue="planned">
        <TabsList>
          <TabsTrigger value="planned">{tx('Planned orders')}</TabsTrigger>
          <TabsTrigger value="worksheet">{tx('Netting worksheet')}</TabsTrigger>
          <TabsTrigger value="capacity">{tx('Capacity')}</TabsTrigger>
          <TabsTrigger value="runs">{tx('Runs')}</TabsTrigger>
          <TabsTrigger value="params">{tx('Planning parameters')}</TabsTrigger>
          <TabsTrigger value="settings">{tx('Nightly run')}</TabsTrigger>
        </TabsList>

        <TabsContent value="planned">
          <Card>
            <CardHeader className="flex flex-row items-center justify-between">
              <CardTitle>
                {run ? `${run.runNumber} · ${fmtDate(run.startedAt)}` : tx('No MRP run yet')}
                {run && <Badge variant="outline" className="ml-2">{run.status}</Badge>}
              </CardTitle>
              {canRun && run && open.length > 0 && (
                <Button size="sm" onClick={() => call(() => firmRun.mutateAsync({ runId: run.id }), tx('Planned orders firmed.'))}>
                  <CheckCheck className="mr-2 h-4 w-4" /> {tx('Firm all ({{n}})', { n: open.length })}
                </Button>
              )}
            </CardHeader>
            <CardContent>
              {!run?.plannedOrders?.length ? (
                <p className="text-sm text-muted-foreground">{tx('Nothing to plan — supply covers every requirement.')}</p>
              ) : (
                <Table>
                  <TableHeader>
                    <TableRow>
                      <TableHead>{tx('Product')}</TableHead>
                      <TableHead>{tx('Type')}</TableHead>
                      <TableHead className="text-right">{tx('Qty')}</TableHead>
                      <TableHead>{tx('Release')}</TableHead>
                      <TableHead>{tx('Due')}</TableHead>
                      <TableHead>{tx('Covers')}</TableHead>
                      <TableHead>{tx('Status')}</TableHead>
                      <TableHead className="text-right">{tx('Actions')}</TableHead>
                    </TableRow>
                  </TableHeader>
                  <TableBody>
                    {run.plannedOrders.map((p) => {
                      const late = new Date(p.releaseDate) < new Date() && p.status === 'planned';
                      return (
                        <TableRow key={p.id}>
                          <TableCell className="text-sm">{productName(p.productId)}</TableCell>
                          <TableCell><Badge variant={p.kind === 'buy' ? 'secondary' : 'default'}>{p.kind}</Badge></TableCell>
                          <TableCell className="text-right">{n(p.quantity)}</TableCell>
                          <TableCell className={late ? 'text-destructive' : ''}>{fmtDate(p.releaseDate)}</TableCell>
                          <TableCell>{fmtDate(p.dueDate)}</TableCell>
                          <TableCell className="text-xs text-muted-foreground">
                            {(p.pegging ?? []).slice(0, 3).map((g) => `${g.type} ×${n(g.qty)}`).join(', ')}
                          </TableCell>
                          <TableCell><Badge variant="outline">{p.status}</Badge></TableCell>
                          <TableCell className="space-x-1 text-right">
                            {canRun && p.status === 'planned' && (
                              <>
                                <Button size="sm" variant="outline" onClick={() => call(() => action.mutateAsync({ id: p.id, action: 'firm' }), tx('Firmed.'))}>{tx('Firm')}</Button>
                                <Button size="sm" variant="ghost" onClick={() => call(() => action.mutateAsync({ id: p.id, action: 'cancel' }), tx('Cancelled.'))}><XCircle className="h-4 w-4" /></Button>
                              </>
                            )}
                            {p.status === 'firmed' && p.firmedDocType === 'production_order' && (
                              <a className="text-sm underline" href={`/manufacturing/orders/${p.firmedDocId}`}>{tx('Order')}</a>
                            )}
                          </TableCell>
                        </TableRow>
                      );
                    })}
                  </TableBody>
                </Table>
              )}
            </CardContent>
          </Card>
        </TabsContent>

        <TabsContent value="worksheet">
          <Card>
            <CardContent className="pt-6">
              <Table>
                <TableHeader>
                  <TableRow>
                    <TableHead>{tx('Level')}</TableHead>
                    <TableHead>{tx('Product')}</TableHead>
                    <TableHead className="text-right">{tx('Gross')}</TableHead>
                    <TableHead className="text-right">{tx('On hand')}</TableHead>
                    <TableHead className="text-right">{tx('Reserved')}</TableHead>
                    <TableHead className="text-right">{tx('Receipts')}</TableHead>
                    <TableHead className="text-right">{tx('Safety')}</TableHead>
                    <TableHead className="text-right">{tx('Net')}</TableHead>
                    <TableHead className="text-right">{tx('Planned')}</TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {(run?.items ?? []).map((i) => (
                    <TableRow key={i.id}>
                      <TableCell>{i.lowLevelCode}</TableCell>
                      <TableCell className="text-sm">{productName(i.productId)} <Badge variant="outline">{i.procurement}</Badge></TableCell>
                      <TableCell className="text-right">{n(i.grossRequirement)}</TableCell>
                      <TableCell className="text-right">{n(i.onHand)}</TableCell>
                      <TableCell className="text-right">{n(i.reserved)}</TableCell>
                      <TableCell className="text-right">{n(i.scheduledReceipts)}</TableCell>
                      <TableCell className="text-right">{n(i.safetyStock)}</TableCell>
                      <TableCell className="text-right">{n(i.netRequirement)}</TableCell>
                      <TableCell className="text-right font-medium">{n(i.plannedQty)}</TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            </CardContent>
          </Card>
        </TabsContent>

        <TabsContent value="runs">
          <Card>
            <CardContent className="pt-6">
              <Table>
                <TableHeader>
                  <TableRow>
                    <TableHead>{tx('Run')}</TableHead>
                    <TableHead>{tx('Started')}</TableHead>
                    <TableHead>{tx('Horizon')}</TableHead>
                    <TableHead>{tx('Planned orders')}</TableHead>
                    <TableHead>{tx('Late')}</TableHead>
                    <TableHead>{tx('Status')}</TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {(runs ?? []).map((r) => (
                    <TableRow key={r.id} className="cursor-pointer" onClick={() => setSelected(r.id)}>
                      <TableCell className="font-mono text-xs">{r.runNumber}</TableCell>
                      <TableCell>{new Date(r.startedAt).toLocaleString()}</TableCell>
                      <TableCell>{r.horizonDays} {tx('days')}</TableCell>
                      <TableCell>
                        {r.summary?.plannedOrders ?? 0} ({r.summary?.make ?? 0} {tx('make')}
                        {r.summary?.subcontract ? ` / ${r.summary.subcontract} ${tx('subcontract')}` : ''} / {r.summary?.buy ?? 0} {tx('buy')})
                      </TableCell>
                      <TableCell>{r.summary?.late ?? 0}</TableCell>
                      <TableCell><Badge variant={r.id === runId ? 'default' : 'outline'}>{r.status}</Badge></TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            </CardContent>
          </Card>
        </TabsContent>

        <TabsContent value="capacity">
          <CapacityTab runId={run?.id} />
        </TabsContent>

        <TabsContent value="settings">
          <SettingsTab canEdit={canEdit} locations={locations ?? []} />
        </TabsContent>

        <TabsContent value="params">
          <PlanningParamsEditor canEdit={canEdit} productName={productName} products={products ?? []} locations={locations ?? []} />
        </TabsContent>
      </Tabs>
    </div>
  );
}

const EMPTY: PlanningParams = {
  productId: '', procurement: 'buy', leadTimeDays: 0, safetyStock: 0, reorderPoint: 0, minOrderQty: 0, orderMultiple: 0,
};

function PlanningParamsEditor({ canEdit, productName, products, locations }: {
  canEdit: boolean;
  productName: (id: string) => string;
  products: Array<{ id: string; code: string; name: string }>;
  locations: any[];
}) {
  const { data: rows } = usePlanningParams();
  const save = useSavePlanningParams();
  const { data: partners } = usePartners({ page: 1, pageSize: 1000 });
  const suppliers = (partners?.data ?? []).filter((p) => p.isSupplier);
  const [draft, setDraft] = useState<PlanningParams>(EMPTY);
  const set = (k: keyof PlanningParams, v: unknown) => setDraft((d) => ({ ...d, [k]: v }));

  const submit = async () => {
    if (!draft.productId) {
      notify.error(tx('Choose a product.'));
      return;
    }
    try {
      await save.mutateAsync({
        ...draft,
        leadTimeDays: Number(draft.leadTimeDays) || 0,
        safetyStock: Number(draft.safetyStock) || 0,
        reorderPoint: Number(draft.reorderPoint) || 0,
        minOrderQty: Number(draft.minOrderQty) || 0,
        orderMultiple: Number(draft.orderMultiple) || 0,
        locationId: draft.locationId || undefined,
        supplierId: draft.supplierId || undefined,
        product: undefined,
        id: undefined,
      });
      notify.success(tx('Planning parameters saved.'));
      setDraft(EMPTY);
    } catch (e: any) {
      notify.error(tx('Failed'), e?.response?.data?.message ?? e?.message);
    }
  };

  return (
    <Card>
      <CardHeader>
        <CardTitle>{tx('Planning parameters')}</CardTitle>
        <p className="text-sm text-muted-foreground">
          {tx('Products without parameters are planned as make when they have an active BOM, otherwise buy, with their minimum quantity as reorder point.')}
        </p>
      </CardHeader>
      <CardContent className="space-y-4">
        {canEdit && (
          <div className="grid grid-cols-2 gap-3 md:grid-cols-4 lg:grid-cols-8">
            <div className="col-span-2 space-y-1">
              <Label>{tx('Product')}</Label>
              <Select value={draft.productId} onValueChange={(v) => {
                const existing = (rows ?? []).find((r) => r.productId === v);
                setDraft(existing ? { ...existing } : { ...EMPTY, productId: v });
              }}>
                <SelectTrigger><SelectValue placeholder={tx('Select…')} /></SelectTrigger>
                <SelectContent>
                  {products.map((p) => <SelectItem key={p.id} value={p.id}>{p.code} · {p.name}</SelectItem>)}
                </SelectContent>
              </Select>
            </div>
            <div className="space-y-1">
              <Label>{tx('Make / buy')}</Label>
              <Select value={draft.procurement} onValueChange={(v) => set('procurement', v)}>
                <SelectTrigger><SelectValue /></SelectTrigger>
                <SelectContent>
                  <SelectItem value="make">{tx('Make')}</SelectItem>
                  <SelectItem value="buy">{tx('Buy')}</SelectItem>
                  <SelectItem value="subcontract">{tx('Subcontract')}</SelectItem>
                </SelectContent>
              </Select>
            </div>
            {draft.procurement !== 'make' && (
              <div className="space-y-1">
                <Label>{draft.procurement === 'subcontract' ? tx('Subcontractor') : tx('Supplier')}</Label>
                <Select value={draft.supplierId || 'none'} onValueChange={(v) => set('supplierId', v === 'none' ? '' : v)}>
                  <SelectTrigger><SelectValue /></SelectTrigger>
                  <SelectContent>
                    <SelectItem value="none">{tx('Default')}</SelectItem>
                    {suppliers.map((s) => <SelectItem key={s.id} value={s.id}>{s.name}</SelectItem>)}
                  </SelectContent>
                </Select>
              </div>
            )}
            {(['leadTimeDays', 'safetyStock', 'reorderPoint', 'minOrderQty', 'orderMultiple'] as const).map((k) => (
              <div key={k} className="space-y-1">
                <Label>{tx(({ leadTimeDays: 'Lead time (days)', safetyStock: 'Safety stock', reorderPoint: 'Reorder point', minOrderQty: 'Min order qty', orderMultiple: 'Order multiple' } as const)[k])}</Label>
                <Input type="number" min="0" value={String(draft[k] ?? '')} onChange={(e) => set(k, e.target.value)} />
              </div>
            ))}
            <div className="space-y-1">
              <Label>{tx('Location')}</Label>
              <Select value={draft.locationId || 'none'} onValueChange={(v) => set('locationId', v === 'none' ? '' : v)}>
                <SelectTrigger><SelectValue /></SelectTrigger>
                <SelectContent>
                  <SelectItem value="none">{tx('Default')}</SelectItem>
                  {locations.map((l: any) => <SelectItem key={l.id} value={l.id}>{l.name}</SelectItem>)}
                </SelectContent>
              </Select>
            </div>
            <div className="flex items-end">
              <Button onClick={submit} disabled={save.isPending}><Save className="mr-2 h-4 w-4" /> {tx('Save')}</Button>
            </div>
          </div>
        )}
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>{tx('Product')}</TableHead>
              <TableHead>{tx('Make / buy')}</TableHead>
              <TableHead className="text-right">{tx('Lead time')}</TableHead>
              <TableHead className="text-right">{tx('Safety')}</TableHead>
              <TableHead className="text-right">{tx('Reorder pt')}</TableHead>
              <TableHead className="text-right">{tx('Min qty')}</TableHead>
              <TableHead className="text-right">{tx('Multiple')}</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {(rows ?? []).map((r) => (
              <TableRow key={r.productId} className={canEdit ? 'cursor-pointer' : ''} onClick={() => canEdit && setDraft({ ...r })}>
                <TableCell className="text-sm">{r.product ? `${r.product.code} · ${r.product.name}` : productName(r.productId)}</TableCell>
                <TableCell><Badge variant="outline">{r.procurement}</Badge></TableCell>
                <TableCell className="text-right">{r.leadTimeDays} {tx('d')}</TableCell>
                <TableCell className="text-right">{n(r.safetyStock)}</TableCell>
                <TableCell className="text-right">{n(r.reorderPoint)}</TableCell>
                <TableCell className="text-right">{n(r.minOrderQty)}</TableCell>
                <TableCell className="text-right">{n(r.orderMultiple)}</TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </CardContent>
    </Card>
  );
}

/** PR-5: rough-cut capacity — the run's planned make orders on top of already scheduled work orders. */
function CapacityTab({ runId }: { runId?: string }) {
  const [bucket, setBucket] = useState<'day' | 'week'>('week');
  const { data, isLoading, isError } = useMrpCapacity(runId, bucket);
  const centres = data?.workCenters ?? [];
  const periods = centres[0]?.buckets ?? [];
  return (
    <Card>
      <CardHeader className="flex flex-row items-center justify-between">
        <div>
          <CardTitle>{tx('Rough-cut capacity')}</CardTitle>
          <p className="text-sm text-muted-foreground">
            {tx('Hours per work centre: scheduled work orders plus the routing of this run\'s planned make orders, spread from release to due date.')}
          </p>
        </div>
        <Select value={bucket} onValueChange={(v) => setBucket(v as 'day' | 'week')}>
          <SelectTrigger className="w-32"><SelectValue /></SelectTrigger>
          <SelectContent>
            <SelectItem value="week">{tx('Weekly')}</SelectItem>
            <SelectItem value="day">{tx('Daily')}</SelectItem>
          </SelectContent>
        </Select>
      </CardHeader>
      <CardContent className="space-y-3">
        {!runId ? <p className="text-sm text-muted-foreground">{tx('No MRP run yet')}</p>
          : isLoading ? <p className="text-sm text-muted-foreground">{tx('Loading…')}</p>
            : isError ? <p className="text-sm text-destructive">{tx('Could not load the capacity. Try again.')}</p>
              : !centres.length ? <p className="text-sm text-muted-foreground">{tx('No active work centres.')}</p> : (
                <>
                  {data?.bottleneck && (
                    <p className="text-sm">
                      {tx('Bottleneck')}: <span className="font-medium">{data.bottleneck.name}</span> ({n(data.bottleneck.utilisationPct)} %)
                    </p>
                  )}
                  <div className="overflow-x-auto">
                    <Table>
                      <TableHeader>
                        <TableRow>
                          <TableHead>{tx('Work centre')}</TableHead>
                          <TableHead className="text-right">{tx('Scheduled h')}</TableHead>
                          <TableHead className="text-right">{tx('Planned h')}</TableHead>
                          <TableHead className="text-right">{tx('Capacity h')}</TableHead>
                          {periods.map((b) => <TableHead key={b.from} className="text-right whitespace-nowrap">{fmtDate(b.from)}</TableHead>)}
                        </TableRow>
                      </TableHeader>
                      <TableBody>
                        {centres.map((c) => (
                          <TableRow key={c.workCenterId}>
                            <TableCell className="text-sm">
                              {c.code} · {c.name} {c.isBottleneck && <Badge variant="destructive" className="ml-1">{tx('Bottleneck')}</Badge>}
                            </TableCell>
                            <TableCell className="text-right tabular-nums">{n(c.loadHours)}</TableCell>
                            <TableCell className="text-right tabular-nums">{n(c.plannedLoadHours)}</TableCell>
                            <TableCell className="text-right tabular-nums">{n(c.capacityHours)}</TableCell>
                            {c.buckets.map((b) => (
                              <TableCell key={b.from} className={`text-right tabular-nums ${b.overloaded ? 'font-medium text-destructive' : ''}`}
                                title={`${n(b.totalLoadHours)} / ${n(b.capacityHours)} h`}>
                                {b.totalUtilisationPct === null ? '—' : `${n(b.totalUtilisationPct)} %`}
                              </TableCell>
                            ))}
                          </TableRow>
                        ))}
                      </TableBody>
                    </Table>
                  </div>
                </>
              )}
      </CardContent>
    </Card>
  );
}

/** PR-5: the nightly MRP run (02:30 UTC). It plans only; firming stays manual. */
function SettingsTab({ canEdit, locations }: { canEdit: boolean; locations: any[] }) {
  const { data } = useMrpSettings();
  const save = useSaveMrpSettings();
  const [autoRun, setAutoRun] = useState(false);
  const [horizon, setHorizon] = useState('30');
  const [locationId, setLocationId] = useState('');
  useEffect(() => {
    if (data) {
      setAutoRun(data.autoRun);
      setHorizon(String(data.horizonDays));
      setLocationId(data.locationId ?? '');
    }
  }, [data]);
  const submit = async () => {
    try {
      await save.mutateAsync({ autoRun, horizonDays: Number(horizon) || 30, locationId });
      notify.success(tx('Settings saved.'));
    } catch (e: any) {
      notify.error(tx('Failed'), e?.response?.data?.message ?? e?.message);
    }
  };
  return (
    <Card>
      <CardHeader>
        <CardTitle>{tx('Nightly run')}</CardTitle>
        <p className="text-sm text-muted-foreground">
          {tx('Runs MRP every night at 02:30 UTC and replaces the previous run\'s unfirmed planned orders. Nothing is firmed automatically.')}
        </p>
      </CardHeader>
      <CardContent className="space-y-4">
        <label className="flex items-center gap-2 text-sm">
          <input type="checkbox" disabled={!canEdit} checked={autoRun} onChange={(e) => setAutoRun(e.target.checked)} />
          {tx('Run MRP every night')}
        </label>
        <div className="flex flex-wrap items-end gap-3">
          <div className="space-y-1">
            <Label>{tx('Horizon (days)')}</Label>
            <Input className="w-24" type="number" min="1" max="730" disabled={!canEdit} value={horizon} onChange={(e) => setHorizon(e.target.value)} />
          </div>
          <div className="space-y-1">
            <Label>{tx('Location')}</Label>
            <Select value={locationId || 'all'} disabled={!canEdit} onValueChange={(v) => setLocationId(v === 'all' ? '' : v)}>
              <SelectTrigger className="w-48"><SelectValue /></SelectTrigger>
              <SelectContent>
                <SelectItem value="all">{tx('All locations')}</SelectItem>
                {locations.map((l: any) => <SelectItem key={l.id} value={l.id}>{l.name}</SelectItem>)}
              </SelectContent>
            </Select>
          </div>
          {canEdit && <Button onClick={submit} disabled={save.isPending}><Save className="mr-2 h-4 w-4" /> {tx('Save')}</Button>}
        </div>
        {data?.lastAutoRunAt && (
          <p className="text-sm text-muted-foreground">{tx('Last nightly run')}: {new Date(data.lastAutoRunAt).toLocaleString()}</p>
        )}
      </CardContent>
    </Card>
  );
}

export default MrpPage;

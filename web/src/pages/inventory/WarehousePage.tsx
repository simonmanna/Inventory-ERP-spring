import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { PERMISSIONS } from '@erp/shared';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Skeleton } from '@/components/ui/skeleton';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs';
import { api } from '@/lib/api';
import { notify, apiMessage } from '@/lib/notify';
import { tx } from '@/lib/i18n/tx';
import { useAuthStore } from '@/stores/auth.store';
import { useProducts } from '@/features/products/api';
import { fmtQty } from '@/features/inventory/stock-levels';
import {
  useAbc,
  usePickOrder,
  usePickOrders,
  usePutawayRules,
  useWarehouseAction,
  useWarehouseBins,
  useWarehouseOverview,
  useWarehouseTasks,
  useWaves,
} from '@/features/inventory/warehouse-api';

const SELECT = 'h-9 rounded-md border border-input bg-background px-3 text-sm';
const q = (v: string | number | null | undefined) => (v === null || v === undefined ? '—' : fmtQty(Number(v)));

function useRun() {
  const action = useWarehouseAction();
  return {
    pending: action.isPending,
    run: async (method: 'post' | 'put' | 'patch' | 'delete', path: string, body?: unknown, done?: string) => {
      try {
        const res = await action.mutateAsync({ method, path, body });
        if (done) notify.success(done);
        return res;
      } catch (err) {
        notify.error(apiMessage(err, tx('Request failed')));
        return undefined;
      }
    },
  };
}

/** Warehouse management: bins, putaway, floor tasks, pick / pack / ship, waves and ABC cycle counts (roadmap 2.1). */
export default function WarehousePage() {
  const { data: locations } = useQuery({
    queryKey: ['inventory-locations-lookup'],
    queryFn: async () =>
      (await api.get<{ data: { id: string; code: string; name: string }[] }>('/inventory/locations')).data.data ?? [],
  });
  const [locationId, setLocationId] = useState<string>('');
  const loc = locationId || locations?.[0]?.id || '';

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-end justify-between gap-3">
        <div>
          <h1 className="text-2xl font-semibold">{tx('Warehouse management')}</h1>
          <p className="text-sm text-muted-foreground">
            {tx('Bins, putaway, picking, packing, shipping and cycle counts inside one location.')}
          </p>
        </div>
        <div className="space-y-1">
          <Label htmlFor="wh-loc">{tx('Location')}</Label>
          <select id="wh-loc" className={SELECT} value={loc} onChange={(e) => setLocationId(e.target.value)}>
            {(locations ?? []).map((l) => (
              <option key={l.id} value={l.id}>
                {l.code} — {l.name}
              </option>
            ))}
          </select>
        </div>
      </div>
      {!loc ? (
        <p className="text-sm text-muted-foreground">{tx('No locations yet.')}</p>
      ) : (
        <Tabs defaultValue="overview">
          <TabsList className="flex-wrap">
            <TabsTrigger value="overview">{tx('Overview')}</TabsTrigger>
            <TabsTrigger value="bins">{tx('Bins')}</TabsTrigger>
            <TabsTrigger value="rules">{tx('Putaway rules')}</TabsTrigger>
            <TabsTrigger value="tasks">{tx('Tasks')}</TabsTrigger>
            <TabsTrigger value="picks">{tx('Pick orders')}</TabsTrigger>
            <TabsTrigger value="abc">{tx('ABC cycle counts')}</TabsTrigger>
          </TabsList>
          <TabsContent value="overview"><OverviewTab loc={loc} /></TabsContent>
          <TabsContent value="bins"><BinsTab loc={loc} /></TabsContent>
          <TabsContent value="rules"><RulesTab loc={loc} /></TabsContent>
          <TabsContent value="tasks"><TasksTab loc={loc} /></TabsContent>
          <TabsContent value="picks"><PicksTab loc={loc} /></TabsContent>
          <TabsContent value="abc"><AbcTab loc={loc} /></TabsContent>
        </Tabs>
      )}
    </div>
  );
}

function OverviewTab({ loc }: { loc: string }) {
  const can = useAuthStore((s) => s.hasPermission);
  const { data, isLoading } = useWarehouseOverview(loc);
  const { run, pending } = useRun();
  const [steps, setSteps] = useState<string>('');
  if (isLoading || !data) return <Skeleton className="h-32 w-full" />;
  return (
    <div className="space-y-4">
      <div className="grid gap-3 sm:grid-cols-4">
        <Stat label={tx('Bins')} value={String(data.bins)} />
        <Stat label={tx('Open tasks')} value={String(data.openTasks)} />
        <Stat label={tx('Open pick orders')} value={String(data.openPickOrders)} />
        <Stat label={tx('Waiting for putaway')} value={q(data.unbinnedQty)} />
      </div>
      <div className="flex flex-wrap items-end gap-2">
        {can(PERMISSIONS.warehouse.operate) && (
          <Button disabled={pending} onClick={() => run('post', '/warehouse/putaway/generate', { locationId: loc }, tx('Putaway tasks created'))}>
            {tx('Generate putaway')}
          </Button>
        )}
        {can(PERMISSIONS.warehouse.manage) && (
          <>
            <select className={SELECT} value={steps || String(data.settings.outboundSteps)} onChange={(e) => setSteps(e.target.value)} aria-label={tx('Outbound steps')}>
              <option value="1">{tx('1 step: pick and ship')}</option>
              <option value="2">{tx('2 steps: pick, then ship')}</option>
              <option value="3">{tx('3 steps: pick, pack, ship')}</option>
            </select>
            <Button variant="outline" disabled={pending} onClick={() => run('put', `/warehouse/locations/${loc}/settings`, { outboundSteps: Number(steps || data.settings.outboundSteps) }, tx('Saved'))}>
              {tx('Save')}
            </Button>
          </>
        )}
      </div>
      <Table>
        <TableHeader>
          <TableRow>
            <TableHead>{tx('Product')}</TableHead>
            <TableHead className="text-right">{tx('On hand')}</TableHead>
            <TableHead className="text-right">{tx('In bins')}</TableHead>
            <TableHead className="text-right">{tx('Staged')}</TableHead>
            <TableHead className="text-right">{tx('Waiting for putaway')}</TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          {data.products.map((p) => (
            <TableRow key={p.productId + p.variantKey}>
              <TableCell>{p.productName}</TableCell>
              <TableCell className="text-right">{q(p.locationQty)}</TableCell>
              <TableCell className="text-right">{q(p.binnedQty)}</TableCell>
              <TableCell className="text-right">{q(p.stagedQty)}</TableCell>
              <TableCell className="text-right">{q(p.unbinnedQty)}</TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>
    </div>
  );
}

function Stat({ label, value }: { label: string; value: string }) {
  return (
    <Card>
      <CardContent className="p-4">
        <div className="text-xs text-muted-foreground">{label}</div>
        <div className="text-xl font-semibold">{value}</div>
      </CardContent>
    </Card>
  );
}

function BinsTab({ loc }: { loc: string }) {
  const canManage = useAuthStore((s) => s.hasPermission)(PERMISSIONS.warehouse.manage);
  const { data, isLoading } = useWarehouseBins(loc);
  const { run, pending } = useRun();
  const [code, setCode] = useState('');
  const [kind, setKind] = useState('bin');
  const [parentId, setParentId] = useState('');
  const [maxQty, setMaxQty] = useState('');
  const parents = (data ?? []).filter((b) => b.kind !== 'bin');
  return (
    <div className="space-y-3">
      {canManage && (
        <form
          className="flex flex-wrap items-end gap-2"
          onSubmit={async (e) => {
            e.preventDefault();
            const ok = await run('post', '/warehouse/bins', {
              locationId: loc, code, kind, parentId: parentId || undefined, maxQty: maxQty ? Number(maxQty) : undefined,
            }, tx('Saved'));
            if (ok) setCode('');
          }}
        >
          <div className="space-y-1">
            <Label htmlFor="bin-code">{tx('Code')}</Label>
            <Input id="bin-code" value={code} onChange={(e) => setCode(e.target.value)} placeholder="A-01-02" />
          </div>
          <div className="space-y-1">
            <Label htmlFor="bin-kind">{tx('Kind')}</Label>
            <select id="bin-kind" className={SELECT} value={kind} onChange={(e) => setKind(e.target.value)}>
              <option value="zone">{tx('Zone')}</option>
              <option value="aisle">{tx('Aisle')}</option>
              <option value="rack">{tx('Rack')}</option>
              <option value="bin">{tx('Bin')}</option>
            </select>
          </div>
          <div className="space-y-1">
            <Label htmlFor="bin-parent">{tx('Inside')}</Label>
            <select id="bin-parent" className={SELECT} value={parentId} onChange={(e) => setParentId(e.target.value)}>
              <option value="">—</option>
              {parents.map((p) => <option key={p.id} value={p.id}>{p.path}</option>)}
            </select>
          </div>
          <div className="space-y-1">
            <Label htmlFor="bin-max">{tx('Capacity')}</Label>
            <Input id="bin-max" type="number" value={maxQty} onChange={(e) => setMaxQty(e.target.value)} className="w-28" />
          </div>
          <Button type="submit" disabled={!code || pending}>{tx('Add')}</Button>
        </form>
      )}
      {isLoading ? <Skeleton className="h-24 w-full" /> : !data?.length ? (
        <p className="text-sm text-muted-foreground">{tx('No bins yet.')}</p>
      ) : (
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>{tx('Code')}</TableHead>
              <TableHead>{tx('Kind')}</TableHead>
              <TableHead className="text-right">{tx('Quantity')}</TableHead>
              <TableHead className="text-right">{tx('Capacity')}</TableHead>
              <TableHead>{tx('Status')}</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {data.map((b) => (
              <TableRow key={b.id}>
                <TableCell className="font-mono" style={{ paddingLeft: `${0.75 + b.depth * 1.25}rem` }}>{b.code}</TableCell>
                <TableCell>{b.kind}</TableCell>
                <TableCell className="text-right">{q(b.quantity)}</TableCell>
                <TableCell className="text-right">{q(b.maxQty)}</TableCell>
                <TableCell>
                  <Badge variant={b.isActive ? 'default' : 'secondary'}>{b.isActive ? tx('Active') : tx('Inactive')}</Badge>
                  {canManage && (
                    <Button size="sm" variant="ghost" disabled={pending} onClick={() => run('delete', `/warehouse/bins/${b.id}`, undefined, tx('Saved'))}>
                      {tx('Remove')}
                    </Button>
                  )}
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      )}
    </div>
  );
}

function ProductPicker({ value, onChange }: { value: string; onChange: (v: string) => void }) {
  const [search, setSearch] = useState('');
  const { data } = useProducts({ page: 1, pageSize: 50, search: search || undefined });
  return (
    <div className="flex gap-1">
      <Input value={search} onChange={(e) => setSearch(e.target.value)} placeholder={tx('Search products')} className="w-40" />
      <select className={SELECT} value={value} onChange={(e) => onChange(e.target.value)} aria-label={tx('Product')}>
        <option value="">—</option>
        {(data?.data ?? []).map((p) => <option key={p.id} value={p.id}>{p.name}</option>)}
      </select>
    </div>
  );
}

function RulesTab({ loc }: { loc: string }) {
  const canManage = useAuthStore((s) => s.hasPermission)(PERMISSIONS.warehouse.manage);
  const { data: rules } = usePutawayRules(loc);
  const { data: bins } = useWarehouseBins(loc);
  const { run, pending } = useRun();
  const [productId, setProductId] = useState('');
  const [target, setTarget] = useState('');
  const targets = (bins ?? []).filter((b) => b.isActive);
  return (
    <div className="space-y-3">
      {canManage && (
        <div className="flex flex-wrap items-end gap-2">
          <div className="space-y-1">
            <Label>{tx('Product (empty = any)')}</Label>
            <ProductPicker value={productId} onChange={setProductId} />
          </div>
          <div className="space-y-1">
            <Label htmlFor="rule-target">{tx('Put into')}</Label>
            <select id="rule-target" className={SELECT} value={target} onChange={(e) => setTarget(e.target.value)}>
              <option value="">—</option>
              {targets.map((b) => <option key={b.id} value={b.id}>{b.path}</option>)}
            </select>
          </div>
          <Button
            disabled={!target || pending}
            onClick={() => {
              const bin = targets.find((b) => b.id === target);
              run('post', '/warehouse/putaway-rules', {
                locationId: loc,
                productId: productId || undefined,
                ...(bin?.kind === 'bin' ? { targetBinId: target } : { targetZoneId: target }),
              }, tx('Saved'));
            }}
          >
            {tx('Add')}
          </Button>
        </div>
      )}
      <Table>
        <TableHeader>
          <TableRow>
            <TableHead>{tx('Applies to')}</TableHead>
            <TableHead>{tx('Put into')}</TableHead>
            <TableHead>{tx('Priority')}</TableHead>
            <TableHead />
          </TableRow>
        </TableHeader>
        <TableBody>
          {(rules ?? []).map((r) => (
            <TableRow key={r.id}>
              <TableCell>{r.productName ?? r.categoryName ?? tx('Any product')}</TableCell>
              <TableCell className="font-mono">{r.targetBinCode ?? r.targetZoneCode}</TableCell>
              <TableCell>{r.priority}</TableCell>
              <TableCell>
                {canManage && (
                  <Button size="sm" variant="ghost" disabled={pending} onClick={() => run('delete', `/warehouse/putaway-rules/${r.id}`, undefined, tx('Saved'))}>
                    {tx('Remove')}
                  </Button>
                )}
              </TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>
    </div>
  );
}

function TasksTab({ loc }: { loc: string }) {
  const canOperate = useAuthStore((s) => s.hasPermission)(PERMISSIONS.warehouse.operate);
  const [status, setStatus] = useState('open');
  const { data, isLoading } = useWarehouseTasks(loc, status);
  const { run, pending } = useRun();
  return (
    <div className="space-y-3">
      <select className={SELECT} value={status} onChange={(e) => setStatus(e.target.value)} aria-label={tx('Status')}>
        <option value="open">{tx('Open')}</option>
        <option value="done">{tx('Done')}</option>
        <option value="cancelled">{tx('Cancelled')}</option>
      </select>
      {isLoading ? <Skeleton className="h-24 w-full" /> : !data?.length ? (
        <p className="text-sm text-muted-foreground">{tx('No tasks.')}</p>
      ) : (
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>{tx('Task')}</TableHead>
              <TableHead>{tx('Type')}</TableHead>
              <TableHead>{tx('Product')}</TableHead>
              <TableHead className="text-right">{tx('Quantity')}</TableHead>
              <TableHead>{tx('From')}</TableHead>
              <TableHead>{tx('To')}</TableHead>
              <TableHead />
            </TableRow>
          </TableHeader>
          <TableBody>
            {data.map((t) => (
              <TableRow key={t.id}>
                <TableCell className="font-mono">{t.code}{t.pickOrderCode ? ` · ${t.pickOrderCode}` : ''}</TableCell>
                <TableCell>{t.type}</TableCell>
                <TableCell>{t.productName}</TableCell>
                <TableCell className="text-right">{q(t.doneQty ?? t.quantity)}</TableCell>
                <TableCell className="font-mono">{t.fromBinCode ?? '—'}</TableCell>
                <TableCell className="font-mono">{t.toBinCode ?? '—'}</TableCell>
                <TableCell className="whitespace-nowrap">
                  {canOperate && t.status === 'open' && (
                    <>
                      <Button size="sm" disabled={pending} onClick={() => run('post', `/warehouse/tasks/${t.id}/complete`, {}, tx('Done'))}>
                        {tx('Done')}
                      </Button>
                      <Button size="sm" variant="ghost" disabled={pending} onClick={() => run('post', `/warehouse/tasks/${t.id}/cancel`)}>
                        {tx('Cancel')}
                      </Button>
                    </>
                  )}
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      )}
    </div>
  );
}

function PicksTab({ loc }: { loc: string }) {
  const canOperate = useAuthStore((s) => s.hasPermission)(PERMISSIONS.warehouse.operate);
  const { data } = usePickOrders(loc);
  const { data: waves } = useWaves(loc);
  const { run, pending } = useRun();
  const [productId, setProductId] = useState('');
  const [qty, setQty] = useState('');
  const [ref, setRef] = useState('');
  const [selected, setSelected] = useState<string[]>([]);
  const [open, setOpen] = useState<string>();
  return (
    <div className="space-y-4">
      {canOperate && (
        <div className="flex flex-wrap items-end gap-2">
          <div className="space-y-1">
            <Label>{tx('Product')}</Label>
            <ProductPicker value={productId} onChange={setProductId} />
          </div>
          <div className="space-y-1">
            <Label htmlFor="pk-qty">{tx('Quantity')}</Label>
            <Input id="pk-qty" type="number" value={qty} onChange={(e) => setQty(e.target.value)} className="w-24" />
          </div>
          <div className="space-y-1">
            <Label htmlFor="pk-ref">{tx('Reference')}</Label>
            <Input id="pk-ref" value={ref} onChange={(e) => setRef(e.target.value)} className="w-32" />
          </div>
          <Button
            disabled={!productId || !(Number(qty) > 0) || pending}
            onClick={async () => {
              const ok = await run('post', '/warehouse/pick-orders', {
                locationId: loc, sourceRef: ref || undefined, lines: [{ productId, quantity: Number(qty) }],
              }, tx('Pick order created'));
              if (ok) { setQty(''); setRef(''); }
            }}
          >
            {tx('New pick order')}
          </Button>
          <Button
            variant="outline"
            disabled={!selected.length || pending}
            onClick={async () => {
              const ok = await run('post', '/warehouse/waves', { locationId: loc, pickOrderIds: selected }, tx('Wave created'));
              if (ok) setSelected([]);
            }}
          >
            {tx('Group into wave')} ({selected.length})
          </Button>
        </div>
      )}
      <Table>
        <TableHeader>
          <TableRow>
            <TableHead />
            <TableHead>{tx('Pick order')}</TableHead>
            <TableHead>{tx('Reference')}</TableHead>
            <TableHead>{tx('Steps')}</TableHead>
            <TableHead>{tx('Status')}</TableHead>
            <TableHead className="text-right">{tx('Picked')}</TableHead>
            <TableHead>{tx('Wave')}</TableHead>
            <TableHead />
          </TableRow>
        </TableHeader>
        <TableBody>
          {(data ?? []).map((o) => (
            <TableRow key={o.id} className={open === o.id ? 'bg-muted' : ''}>
              <TableCell>
                {o.status === 'draft' && !o.waveId && (
                  <input
                    type="checkbox"
                    aria-label={o.code}
                    checked={selected.includes(o.id)}
                    onChange={(e) => setSelected((s) => (e.target.checked ? [...s, o.id] : s.filter((x) => x !== o.id)))}
                  />
                )}
              </TableCell>
              <TableCell className="cursor-pointer font-mono" onClick={() => setOpen(o.id)}>{o.code}</TableCell>
              <TableCell>{o.sourceRef ?? '—'}</TableCell>
              <TableCell>{o.steps}</TableCell>
              <TableCell><Badge variant="outline">{o.status}</Badge></TableCell>
              <TableCell className="text-right">{q(o.pickedQty)} / {q(o.totalQty)}</TableCell>
              <TableCell className="font-mono">{o.waveCode ?? '—'}</TableCell>
              <TableCell className="whitespace-nowrap">
                {canOperate && o.status === 'draft' && !o.waveId && (
                  <Button size="sm" disabled={pending} onClick={() => run('post', `/warehouse/pick-orders/${o.id}/release`, null, tx('Released'))}>
                    {tx('Release')}
                  </Button>
                )}
                {canOperate && o.steps === 3 && (o.status === 'picked' || o.status === 'picking') && (
                  <Button size="sm" disabled={pending} onClick={() => run('post', `/warehouse/pick-orders/${o.id}/pack`, {}, tx('Packed'))}>
                    {tx('Pack')}
                  </Button>
                )}
                {canOperate && ((o.steps < 3 && o.status === 'picked') || o.status === 'packed') && (
                  <Button size="sm" disabled={pending} onClick={() => run('post', `/warehouse/pick-orders/${o.id}/ship`, {}, tx('Shipped'))}>
                    {tx('Ship')}
                  </Button>
                )}
              </TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>
      {open && <PickOrderDetail id={open} />}
      {!!waves?.length && (
        <Card>
          <CardHeader><CardTitle className="text-base">{tx('Waves')}</CardTitle></CardHeader>
          <CardContent>
            <Table>
              <TableBody>
                {waves.map((w) => (
                  <TableRow key={w.id}>
                    <TableCell className="font-mono">{w.code}</TableCell>
                    <TableCell><Badge variant="outline">{w.status}</Badge></TableCell>
                    <TableCell>{w.orderCount ?? 0} {tx('pick orders')}</TableCell>
                    <TableCell>
                      {canOperate && w.status === 'open' && (
                        <Button size="sm" disabled={pending} onClick={() => run('post', `/warehouse/waves/${w.id}/release`, null, tx('Released'))}>
                          {tx('Release')}
                        </Button>
                      )}
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </CardContent>
        </Card>
      )}
    </div>
  );
}

function PickOrderDetail({ id }: { id: string }) {
  const { data } = usePickOrder(id);
  if (!data) return <Skeleton className="h-20 w-full" />;
  return (
    <Card>
      <CardHeader><CardTitle className="text-base">{data.code}</CardTitle></CardHeader>
      <CardContent className="space-y-3">
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>{tx('Product')}</TableHead>
              <TableHead className="text-right">{tx('Quantity')}</TableHead>
              <TableHead className="text-right">{tx('Picked')}</TableHead>
              <TableHead className="text-right">{tx('Packed')}</TableHead>
              <TableHead className="text-right">{tx('Shipped')}</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {(data.lines ?? []).map((l) => (
              <TableRow key={l.id}>
                <TableCell>{l.productName}</TableCell>
                <TableCell className="text-right">{q(l.quantity)}</TableCell>
                <TableCell className="text-right">{q(l.pickedQty)}</TableCell>
                <TableCell className="text-right">{q(l.packedQty)}</TableCell>
                <TableCell className="text-right">{q(l.shippedQty)}</TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
        {!!data.packages?.length && (
          <p className="text-sm text-muted-foreground">
            {tx('Packages')}: {data.packages.map((p) => p.code).join(', ')}
          </p>
        )}
      </CardContent>
    </Card>
  );
}

function AbcTab({ loc }: { loc: string }) {
  const can = useAuthStore((s) => s.hasPermission);
  const { data, isLoading } = useAbc(loc);
  const { run, pending } = useRun();
  return (
    <div className="space-y-3">
      <div className="flex flex-wrap items-center gap-2">
        {can(PERMISSIONS.warehouse.manage) && (
          <Button variant="outline" disabled={pending} onClick={() => run('post', `/warehouse/locations/${loc}/abc/classify`, null, tx('Classified'))}>
            {tx('Classify by consumption')}
          </Button>
        )}
        {can(PERMISSIONS.warehouse.operate) && can(PERMISSIONS.inventoryCount.count) && (
          <Button disabled={pending || !data?.due} onClick={() => run('post', `/warehouse/locations/${loc}/cycle-counts`, {}, tx('Cycle count opened in Stock Count'))}>
            {tx('Open cycle count')} ({data?.due ?? 0})
          </Button>
        )}
      </div>
      {isLoading ? <Skeleton className="h-24 w-full" /> : !data?.rows.length ? (
        <p className="text-sm text-muted-foreground">{tx('Not classified yet.')}</p>
      ) : (
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>{tx('Product')}</TableHead>
              <TableHead>{tx('Class')}</TableHead>
              <TableHead className="text-right">{tx('Consumption value')}</TableHead>
              <TableHead>{tx('Last counted')}</TableHead>
              <TableHead>{tx('Status')}</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {data.rows.map((r) => (
              <TableRow key={r.productId}>
                <TableCell>{r.productName}</TableCell>
                <TableCell><Badge>{r.abcClass}</Badge></TableCell>
                <TableCell className="text-right">{q(r.consumptionValue)}</TableCell>
                <TableCell>{r.lastCountedAt ? new Date(r.lastCountedAt).toLocaleDateString() : '—'}</TableCell>
                <TableCell>
                  {r.scheduled ? tx('Scheduled') : r.due ? <Badge variant="destructive">{tx('Due')}</Badge> : tx('Up to date')}
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      )}
    </div>
  );
}

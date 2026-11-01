import { useState } from 'react';
import { PERMISSIONS } from '@erp/shared';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs';
import { useAccounts } from '@/features/accounting/api';
import {
  useAbcActivities,
  useAbcDrivers,
  useAbcRun,
  useAbcRuns,
  useComputeAbc,
  useCreateAbcActivity,
  useCreateMlRun,
  useDeleteAbcActivity,
  useMlAction,
  useMlRun,
  useMlRuns,
  useVarianceAnalysis,
} from '@/features/costing/advanced-api';
import { money } from '@/lib/format';
import { useAuthStore } from '@/stores/auth.store';
import { tx } from '@/lib/i18n/tx';

const SELECT = 'h-9 rounded-md border border-input bg-background px-3 text-sm';
const today = new Date().toISOString().slice(0, 10);
const monthStart = `${today.slice(0, 8)}01`;

/** Material ledger, variance analysis and activity-based costing (ADR-S030). */
export function AdvancedCostingPage() {
  return (
    <div className="space-y-4">
      <div>
        <h1 className="text-2xl font-semibold">{tx('Advanced costing')}</h1>
        <p className="text-sm text-muted-foreground">
          {tx('Actual cost of standard-costed stock, every variance in one place, and the cost to serve customers and products.')}
        </p>
      </div>
      <Tabs defaultValue="ml">
        <TabsList>
          <TabsTrigger value="ml">{tx('Material ledger')}</TabsTrigger>
          <TabsTrigger value="variances">{tx('Variance analysis')}</TabsTrigger>
          <TabsTrigger value="abc">{tx('Activity-based costing')}</TabsTrigger>
        </TabsList>
        <TabsContent value="ml"><MaterialLedgerTab /></TabsContent>
        <TabsContent value="variances"><VariancesTab /></TabsContent>
        <TabsContent value="abc"><AbcTab /></TabsContent>
      </Tabs>
    </div>
  );
}

function MaterialLedgerTab() {
  const has = useAuthStore((s) => s.hasPermission);
  const canManage = has(PERMISSIONS.advancedCosting.manage);
  const canPost = has(PERMISSIONS.advancedCosting.post);
  const { data: runs } = useMlRuns();
  const create = useCreateMlRun();
  const action = useMlAction();
  const [from, setFrom] = useState(monthStart);
  const [to, setTo] = useState(today);
  const [selected, setSelected] = useState<string>();
  const { data: run } = useMlRun(selected);

  return (
    <div className="space-y-4">
      {canManage && (
        <Card>
          <CardContent className="flex flex-wrap items-end gap-2 pt-6">
            <div className="space-y-1"><Label>{tx('From')}</Label><Input type="date" value={from} onChange={(e) => setFrom(e.target.value)} /></div>
            <div className="space-y-1"><Label>{tx('To')}</Label><Input type="date" value={to} onChange={(e) => setTo(e.target.value)} /></div>
            <Button disabled={create.isPending} onClick={() => create.mutate({ from, to }, { onSuccess: (r) => setSelected((r as { id: string }).id) })}>
              {tx('Compute run')}
            </Button>
          </CardContent>
        </Card>
      )}
      <Card>
        <CardContent className="pt-6">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>{tx('Run')}</TableHead>
                <TableHead>{tx('Period')}</TableHead>
                <TableHead className="text-right">{tx('Variance pool')}</TableHead>
                <TableHead className="text-right">{tx('To inventory')}</TableHead>
                <TableHead className="text-right">{tx('To consumption')}</TableHead>
                <TableHead>{tx('Status')}</TableHead>
                <TableHead />
              </TableRow>
            </TableHeader>
            <TableBody>
              {(runs ?? []).map((r) => (
                <TableRow key={r.id} className="cursor-pointer" onClick={() => setSelected(r.id)}>
                  <TableCell>{r.number}</TableCell>
                  <TableCell>{String(r.periodFrom)} – {String(r.periodTo)}</TableCell>
                  <TableCell className="text-right">{money(r.poolTotal)}</TableCell>
                  <TableCell className="text-right">{money(r.toInventory)}</TableCell>
                  <TableCell className="text-right">{money(r.toConsumption)}</TableCell>
                  <TableCell><Badge variant={r.status === 'posted' ? 'default' : 'secondary'}>{r.status}</Badge></TableCell>
                  <TableCell className="space-x-1 text-right" onClick={(e) => e.stopPropagation()}>
                    {r.status === 'draft' && canManage && (
                      <>
                        <Button size="sm" variant="ghost" onClick={() => action.mutate({ id: r.id, action: 'recompute' })}>{tx('Recompute')}</Button>
                        <Button size="sm" variant="ghost" onClick={() => action.mutate({ id: r.id, action: 'delete' })}>{tx('Delete')}</Button>
                      </>
                    )}
                    {r.status === 'draft' && canPost && (
                      <Button size="sm" onClick={() => action.mutate({ id: r.id, action: 'post' })}>{tx('Post')}</Button>
                    )}
                    {r.status === 'posted' && canPost && (
                      <Button size="sm" variant="outline" onClick={() => action.mutate({ id: r.id, action: 'reverse' })}>{tx('Reverse')}</Button>
                    )}
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </CardContent>
      </Card>
      {run?.lines && (
        <Card>
          <CardHeader><CardTitle className="text-base">{run.number}</CardTitle></CardHeader>
          <CardContent className="overflow-x-auto">
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>{tx('Product')}</TableHead>
                  <TableHead className="text-right">{tx('Standard')}</TableHead>
                  <TableHead className="text-right">{tx('Opening')}</TableHead>
                  <TableHead className="text-right">{tx('Received')}</TableHead>
                  <TableHead className="text-right">{tx('Consumed')}</TableHead>
                  <TableHead className="text-right">{tx('Ending')}</TableHead>
                  <TableHead className="text-right">{tx('Variance')}</TableHead>
                  <TableHead className="text-right">{tx('To inventory')}</TableHead>
                  <TableHead className="text-right">{tx('Actual unit cost')}</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {run.lines.map((l) => (
                  <TableRow key={l.productId}>
                    <TableCell>{l.productName}</TableCell>
                    <TableCell className="text-right">{money(l.standardCost)}</TableCell>
                    <TableCell className="text-right">{Number(l.openingQty)}</TableCell>
                    <TableCell className="text-right">{Number(l.receiptQty)}</TableCell>
                    <TableCell className="text-right">{Number(l.consumedQty)}</TableCell>
                    <TableCell className="text-right">{Number(l.endingQty)}</TableCell>
                    <TableCell className="text-right">{money(Number(l.carriedVariance) + Number(l.periodVariance))}</TableCell>
                    <TableCell className="text-right">{money(l.toInventory)}</TableCell>
                    <TableCell className="text-right">{money(l.periodicUnitCost)}</TableCell>
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

type Row = Record<string, unknown>;

function VariancesTab() {
  const [from, setFrom] = useState(monthStart);
  const [to, setTo] = useState(today);
  const { data } = useVarianceAnalysis(from, to);
  const totals = (data?.totals ?? {}) as Row;
  const byKind = (data?.byKind ?? []) as Row[];
  const byProduct = (data?.byProduct ?? []) as Row[];
  const bySupplier = (data?.bySupplier ?? []) as Row[];
  const gl = (data?.gl ?? {}) as Row;
  const production = ((data?.production ?? {}) as Row).totals as Row | undefined;

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-end gap-2">
        <div className="space-y-1"><Label>{tx('From')}</Label><Input type="date" value={from} onChange={(e) => setFrom(e.target.value)} /></div>
        <div className="space-y-1"><Label>{tx('To')}</Label><Input type="date" value={to} onChange={(e) => setTo(e.target.value)} /></div>
        <div className="text-sm">
          {tx('Net')}: <strong>{money(String(totals.net ?? 0))}</strong> · {tx('Unfavourable')}: {money(String(totals.unfavourable ?? 0))} · {tx('Favourable')}: {money(String(totals.favourable ?? 0))}
        </div>
      </div>
      <div className="grid gap-4 lg:grid-cols-2">
        <SimpleTable title={tx('By kind')} rows={byKind} cols={[['kind', tx('Kind')], ['unfavourable', tx('Unfavourable')], ['favourable', tx('Favourable')], ['net', tx('Net')], ['percentOfStandard', '%']]} />
        <SimpleTable title={tx('By product')} rows={byProduct} cols={[['name', tx('Product')], ['price', tx('Price')], ['production', tx('Production')], ['net', tx('Net')]]} />
        <SimpleTable title={tx('By supplier (purchase price)')} rows={bySupplier} cols={[['name', tx('Supplier')], ['receipts', tx('Receipts')], ['net', tx('Net')]]} />
        <Card>
          <CardHeader><CardTitle className="text-base">{tx('GL tie-out')}</CardTitle></CardHeader>
          <CardContent className="space-y-1 text-sm">
            <div>{tx('Variances in the GL')}: {money(String(gl.variancesInGl ?? 0))}</div>
            <div>{tx('Booked variances')}: {money(String(gl.bookedVariances ?? 0))}</div>
            <div>{tx('Difference')}: <Badge variant={Number(gl.difference ?? 0) === 0 ? 'default' : 'destructive'}>{money(String(gl.difference ?? 0))}</Badge></div>
            {production && (
              <div className="pt-2 text-muted-foreground">
                {tx('Production orders (on read)')}: {tx('price')} {money(String(production.materialPrice ?? 0))}, {tx('usage')} {money(String(production.materialUsage ?? 0))}, {tx('labour efficiency')} {money(String(production.labourEfficiency ?? 0))}, {tx('overhead')} {money(String(production.overhead ?? 0))}, {tx('yield')} {money(String(production.yield ?? 0))}
              </div>
            )}
          </CardContent>
        </Card>
      </div>
    </div>
  );
}

function SimpleTable({ title, rows, cols }: { title: string; rows: Row[]; cols: [string, string][] }) {
  return (
    <Card>
      <CardHeader><CardTitle className="text-base">{title}</CardTitle></CardHeader>
      <CardContent>
        <Table>
          <TableHeader><TableRow>{cols.map(([k, l]) => <TableHead key={k}>{l}</TableHead>)}</TableRow></TableHeader>
          <TableBody>
            {rows.map((r, i) => (
              <TableRow key={i}>{cols.map(([k]) => <TableCell key={k}>{r[k] === null || r[k] === undefined ? '—' : String(r[k])}</TableCell>)}</TableRow>
            ))}
          </TableBody>
        </Table>
      </CardContent>
    </Card>
  );
}

function AbcTab() {
  const canManage = useAuthStore((s) => s.hasPermission)(PERMISSIONS.advancedCosting.manage);
  const { data: drivers } = useAbcDrivers();
  const { data: activities } = useAbcActivities();
  const { data: runs } = useAbcRuns();
  const { data: accounts } = useAccounts();
  const create = useCreateAbcActivity();
  const remove = useDeleteAbcActivity();
  const compute = useComputeAbc();
  const [object, setObject] = useState<'customer' | 'product'>('customer');
  const [code, setCode] = useState('');
  const [name, setName] = useState('');
  const [driver, setDriver] = useState('sales_invoices');
  const [account, setAccount] = useState('');
  const [pct, setPct] = useState('100');
  const [from, setFrom] = useState(monthStart);
  const [to, setTo] = useState(today);
  const [selected, setSelected] = useState<string>();
  const { data: run } = useAbcRun(selected);
  const expenseAccounts = (accounts?.data ?? []).filter((a) => !a.isGroup && a.isActive);

  return (
    <div className="space-y-4">
      <Card>
        <CardHeader><CardTitle className="text-base">{tx('Activities')}</CardTitle></CardHeader>
        <CardContent className="space-y-3">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>{tx('Code')}</TableHead><TableHead>{tx('Name')}</TableHead><TableHead>{tx('Cost object')}</TableHead>
                <TableHead>{tx('Driver')}</TableHead><TableHead className="text-right">{tx('Pool %')}</TableHead><TableHead />
              </TableRow>
            </TableHeader>
            <TableBody>
              {(activities ?? []).map((a) => (
                <TableRow key={a.id}>
                  <TableCell>{a.code}</TableCell><TableCell>{a.name}</TableCell><TableCell>{a.costObject}</TableCell>
                  <TableCell>{a.driver}</TableCell><TableCell className="text-right">{Number(a.poolPercent)}</TableCell>
                  <TableCell className="text-right">
                    {canManage && <Button size="sm" variant="ghost" onClick={() => remove.mutate(a.id)}>{tx('Delete')}</Button>}
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
          {canManage && (
            <form className="flex flex-wrap items-end gap-2" onSubmit={(e) => {
              e.preventDefault();
              create.mutate({ code, name, costObject: object, driver, accountIds: [account], poolPercent: Number(pct) },
                { onSuccess: () => { setCode(''); setName(''); } });
            }}>
              <div className="space-y-1"><Label>{tx('Code')}</Label><Input value={code} onChange={(e) => setCode(e.target.value)} className="w-28" required /></div>
              <div className="space-y-1"><Label>{tx('Name')}</Label><Input value={name} onChange={(e) => setName(e.target.value)} className="w-48" required /></div>
              <div className="space-y-1"><Label>{tx('Cost object')}</Label>
                <select className={SELECT} value={object} onChange={(e) => { const o = e.target.value as 'customer'; setObject(o); setDriver(drivers?.[o]?.[0]?.driver ?? ''); }}>
                  <option value="customer">{tx('Customer')}</option><option value="product">{tx('Product')}</option>
                </select></div>
              <div className="space-y-1"><Label>{tx('Driver')}</Label>
                <select className={SELECT} value={driver} onChange={(e) => setDriver(e.target.value)}>
                  {(drivers?.[object] ?? []).map((d) => <option key={d.driver} value={d.driver}>{tx(d.label)}</option>)}
                </select></div>
              <div className="space-y-1"><Label>{tx('Cost account')}</Label>
                <select className={SELECT} value={account} onChange={(e) => setAccount(e.target.value)} required>
                  <option value="" />
                  {expenseAccounts.map((a) => <option key={a.id} value={a.id}>{a.code} — {a.name}</option>)}
                </select></div>
              <div className="space-y-1"><Label>{tx('Pool %')}</Label><Input type="number" value={pct} onChange={(e) => setPct(e.target.value)} className="w-20" /></div>
              <Button type="submit" disabled={create.isPending}>{tx('Add activity')}</Button>
            </form>
          )}
        </CardContent>
      </Card>
      <Card>
        <CardHeader><CardTitle className="text-base">{tx('Runs')}</CardTitle></CardHeader>
        <CardContent className="space-y-3">
          {canManage && (
            <div className="flex flex-wrap items-end gap-2">
              <select className={SELECT} value={object} onChange={(e) => setObject(e.target.value as 'customer')}>
                <option value="customer">{tx('Customer')}</option><option value="product">{tx('Product')}</option>
              </select>
              <Input type="date" value={from} onChange={(e) => setFrom(e.target.value)} className="w-40" />
              <Input type="date" value={to} onChange={(e) => setTo(e.target.value)} className="w-40" />
              <Button disabled={compute.isPending} onClick={() => compute.mutate({ costObject: object, from, to },
                { onSuccess: (r) => setSelected((r as { id: string }).id) })}>{tx('Compute run')}</Button>
            </div>
          )}
          <div className="flex flex-wrap gap-1">
            {(runs ?? []).map((r) => (
              <Button key={r.id} size="sm" variant={selected === r.id ? 'default' : 'outline'} onClick={() => setSelected(r.id)}>
                {r.costObject} {String(r.periodFrom)} – {String(r.periodTo)}
              </Button>
            ))}
          </div>
          {run?.results && (
            <>
              <div className="text-sm">
                {tx('Pool')}: {money(run.poolTotal)} · {tx('Assigned')}: {money(run.assignedTotal)} · {tx('Unassigned')}: {money(run.unassignedTotal)}
              </div>
              <Table>
                <TableHeader>
                  <TableRow>
                    <TableHead>{run.costObject === 'customer' ? tx('Customer') : tx('Product')}</TableHead>
                    <TableHead className="text-right">{tx('Revenue')}</TableHead>
                    <TableHead className="text-right">{tx('Cost of goods (est.)')}</TableHead>
                    <TableHead className="text-right">{tx('Activity cost')}</TableHead>
                    <TableHead className="text-right">{tx('Margin')}</TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {run.results.map((r) => (
                    <TableRow key={r.objectId}>
                      <TableCell>{r.objectName ?? r.objectId}</TableCell>
                      <TableCell className="text-right">{money(r.revenue)}</TableCell>
                      <TableCell className="text-right">{money(r.costOfGoods)}</TableCell>
                      <TableCell className="text-right">{money(r.activityCost)}</TableCell>
                      <TableCell className="text-right">{money(r.margin)}</TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            </>
          )}
        </CardContent>
      </Card>
    </div>
  );
}

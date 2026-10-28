import { useState } from 'react';
import { PERMISSIONS } from '@erp/shared';
import { Button } from '@/components/ui/button';
import { Badge } from '@/components/ui/badge';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { useAuthStore } from '@/stores/auth.store';
import { useAccounts } from '@/features/accounting/api';
import { errMsg, useCostComponents, useLandedCostReport, useSaveCostComponent, type CostComponent } from '@/features/costing/api';
import { notify } from '@/lib/notify';
import { date, money } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';

const yearStart = () => `${new Date().getFullYear()}-01-01`;
const today = () => new Date().toISOString().slice(0, 10);

/** Landed cost reports and the cost component master (ADR-S007 §7). */
export function LandedCostReportsPage() {
  const has = useAuthStore((s) => s.hasPermission);
  const [from, setFrom] = useState(yearStart());
  const [to, setTo] = useState(today());
  const byShipment = useLandedCostReport<any[]>('by-shipment', from, to);
  const byItem = useLandedCostReport<any[]>('by-item', from, to);
  const bySupplier = useLandedCostReport<any[]>('by-supplier', from, to);
  const margin = useLandedCostReport<any[]>('product-profitability', from, to);
  const clearing = useLandedCostReport<any>('clearing');

  return (
    <div className="space-y-6 p-6">
      <div>
        <h1 className="text-2xl font-semibold">{tx('Landed cost reports')}</h1>
        <p className="text-sm text-muted-foreground">{tx('What imported goods really cost, who charged it, and the margin left after acquisition costs.')}</p>
      </div>
      <div className="flex flex-wrap items-end gap-3">
        <div className="space-y-1"><Label>{tx('From')}</Label><Input type="date" value={from} onChange={(e) => setFrom(e.target.value)} /></div>
        <div className="space-y-1"><Label>{tx('To')}</Label><Input type="date" value={to} onChange={(e) => setTo(e.target.value)} /></div>
      </div>
      <Tabs defaultValue="shipment">
        <TabsList>
          <TabsTrigger value="shipment">{tx('By shipment')}</TabsTrigger>
          <TabsTrigger value="item">{tx('By item')}</TabsTrigger>
          <TabsTrigger value="supplier">{tx('By supplier')}</TabsTrigger>
          <TabsTrigger value="margin">{tx('Product margin')}</TabsTrigger>
          <TabsTrigger value="clearing">{tx('Clearing account')}</TabsTrigger>
          {has(PERMISSIONS.landedCost.configure) && <TabsTrigger value="components">{tx('Cost components')}</TabsTrigger>}
        </TabsList>

        <TabsContent value="shipment" className="space-y-4">
          {(byShipment.data ?? []).map((v) => (
            <Card key={v.id}>
              <CardHeader>
                <CardTitle className="text-base">
                  {v.code} · {date(v.date)} {v.shipmentCode ? `· ${v.shipmentCode} ${v.shipmentReference ?? ''}` : ''}
                </CardTitle>
              </CardHeader>
              <CardContent className="space-y-3 text-sm">
                <div className="flex flex-wrap gap-2">
                  <Badge variant="outline">{tx('Purchase value')}: {money(Number(v.purchaseValue))}</Badge>
                  {v.components.map((c: any) => <Badge key={c.component} variant="secondary">{c.name}: {money(Number(c.amount))}</Badge>)}
                  <Badge>{tx('Total landed')}: {money(Number(v.landedValue))}</Badge>
                </div>
                <Table>
                  <TableHeader>
                    <TableRow>
                      <TableHead>{tx('Item')}</TableHead>
                      <TableHead className="text-right">{tx('Qty')}</TableHead>
                      <TableHead className="text-right">{tx('Purchase unit cost')}</TableHead>
                      <TableHead className="text-right">{tx('Landed cost added')}</TableHead>
                      <TableHead className="text-right">{tx('Landed unit cost')}</TableHead>
                    </TableRow>
                  </TableHeader>
                  <TableBody>
                    {v.items.map((i: any) => (
                      <TableRow key={i.productId}>
                        <TableCell>{i.name}</TableCell>
                        <TableCell className="text-right">{Number(i.quantity).toLocaleString()}</TableCell>
                        <TableCell className="text-right">{money(Number(i.purchaseUnitCost))}</TableCell>
                        <TableCell className="text-right">{money(Number(i.allocated))}</TableCell>
                        <TableCell className="text-right font-medium">{money(Number(i.landedUnitCost))}</TableCell>
                      </TableRow>
                    ))}
                  </TableBody>
                </Table>
              </CardContent>
            </Card>
          ))}
          {!byShipment.data?.length && <p className="text-sm text-muted-foreground">{tx('No posted landed costs in this period.')}</p>}
        </TabsContent>

        <TabsContent value="item">
          <SimpleTable rows={byItem.data} cols={[
            [tx('Item'), (r) => r.name], [tx('Vouchers'), (r) => r.vouchers, true], [tx('Allocated'), (r) => money(Number(r.allocated)), true],
            [tx('To inventory'), (r) => money(Number(r.capitalized)), true], [tx('To COGS'), (r) => money(Number(r.expensed)), true],
          ]} />
        </TabsContent>

        <TabsContent value="supplier">
          <SimpleTable rows={bySupplier.data} cols={[
            [tx('Supplier'), (r) => r.name], [tx('Charges'), (r) => r.charges, true], [tx('Amount'), (r) => money(Number(r.amount)), true],
            [tx('Recoverable tax'), (r) => money(Number(r.tax)), true],
          ]} />
        </TabsContent>

        <TabsContent value="margin">
          <SimpleTable rows={margin.data} cols={[
            [tx('Product'), (r) => r.name], [tx('Qty sold'), (r) => Number(r.quantity).toLocaleString(), true], [tx('Revenue'), (r) => money(Number(r.revenue)), true],
            [tx('COGS incl. landed cost'), (r) => money(Number(r.cogs)), true], [tx('Gross profit'), (r) => money(Number(r.grossProfit)), true],
            [tx('Margin'), (r) => `${r.marginPct}%`, true],
          ]} />
        </TabsContent>

        <TabsContent value="clearing">
          <Card>
            <CardContent className="space-y-3 pt-6 text-sm">
              <div className="flex flex-wrap gap-2">
                <Badge variant="outline">{tx('Credited by vouchers')}: {money(Number(clearing.data?.credited ?? 0))}</Badge>
                <Badge variant="outline">{tx('Cleared by bills')}: {money(Number(clearing.data?.debited ?? 0))}</Badge>
                <Badge variant={Number(clearing.data?.openBalance ?? 0) === 0 ? 'default' : 'destructive'}>{tx('Open')}: {money(Number(clearing.data?.openBalance ?? 0))}</Badge>
              </div>
              <p className="text-xs text-muted-foreground">{tx('Book each forwarder, customs or insurer bill against the clearing account; the open balance is what is still to be billed.')}</p>
              <SimpleTable rows={clearing.data?.charges} cols={[
                [tx('Voucher'), (r) => r.code], [tx('Date'), (r) => date(r.date)], [tx('Supplier'), (r) => r.supplierName ?? '—'],
                [tx('Invoice'), (r) => r.supplierInvoiceRef ?? '—'], [tx('Charge'), (r) => r.description ?? r.kind], [tx('Amount'), (r) => money(Number(r.amount)), true],
              ]} />
            </CardContent>
          </Card>
        </TabsContent>

        <TabsContent value="components"><ComponentsTab /></TabsContent>
      </Tabs>
    </div>
  );
}

function SimpleTable({ rows, cols }: { rows?: any[]; cols: Array<[string, (r: any) => any, boolean?]> }) {
  if (!rows?.length) return <p className="text-sm text-muted-foreground">{tx('Nothing to show for this period.')}</p>;
  return (
    <Table>
      <TableHeader>
        <TableRow>{cols.map(([h, , right]) => <TableHead key={h} className={right ? 'text-right' : ''}>{h}</TableHead>)}</TableRow>
      </TableHeader>
      <TableBody>
        {rows.map((r, i) => (
          <TableRow key={r.id ?? r.productId ?? r.supplierId ?? i}>
            {cols.map(([h, f, right]) => <TableCell key={h} className={right ? 'text-right' : ''}>{f(r)}</TableCell>)}
          </TableRow>
        ))}
      </TableBody>
    </Table>
  );
}

function ComponentsTab() {
  const { data: components = [] } = useCostComponents();
  const { data: accounts } = useAccounts();
  const save = useSaveCostComponent();
  const [form, setForm] = useState<Partial<CostComponent>>({ treatment: 'capitalize', defaultAllocationMethod: 'value', kind: 'other' });

  const submit = async (body: Partial<CostComponent>) => {
    try {
      await save.mutateAsync({
        id: body.id, code: body.id ? undefined : body.code, name: body.name, kind: body.kind, treatment: body.treatment,
        defaultAllocationMethod: body.defaultAllocationMethod, expenseAccountId: body.expenseAccountId || undefined, isActive: body.isActive,
      });
      if (!body.id) setForm({ treatment: 'capitalize', defaultAllocationMethod: 'value', kind: 'other' });
    } catch (e) {
      notify.error(tx('Could not save component'), errMsg(e));
    }
  };

  return (
    <Card>
      <CardHeader><CardTitle>{tx('Cost components')}</CardTitle></CardHeader>
      <CardContent className="space-y-3 text-sm">
        <p className="text-xs text-muted-foreground">
          {tx('Defaults follow IAS 2: direct acquisition costs (duty, freight, clearing, insurance) are added to inventory; recoverable VAT goes to input tax; selling, administration and avoidable costs are expensed.')}
        </p>
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>{tx('Code')}</TableHead>
              <TableHead>{tx('Name')}</TableHead>
              <TableHead>{tx('Treatment')}</TableHead>
              <TableHead>{tx('Default basis')}</TableHead>
              <TableHead>{tx('Expense account')}</TableHead>
              <TableHead>{tx('Active')}</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {components.map((c) => (
              <TableRow key={c.id}>
                <TableCell className="font-mono text-xs">{c.code}</TableCell>
                <TableCell>{c.name}</TableCell>
                <TableCell>
                  <select className="rounded-md border p-1" value={c.treatment} onChange={(e) => submit({ id: c.id, treatment: e.target.value as any })}>
                    <option value="capitalize">{tx('Add to inventory')}</option>
                    <option value="expense">{tx('Expense')}</option>
                    <option value="recoverable_tax">{tx('Recoverable tax')}</option>
                  </select>
                </TableCell>
                <TableCell>
                  <select className="rounded-md border p-1" value={c.defaultAllocationMethod} onChange={(e) => submit({ id: c.id, defaultAllocationMethod: e.target.value as any })}>
                    {['value', 'quantity', 'weight', 'volume', 'equal', 'manual'].map((m) => <option key={m} value={m}>{m}</option>)}
                  </select>
                </TableCell>
                <TableCell>{c.expenseAccountCode ?? '—'}</TableCell>
                <TableCell><input type="checkbox" checked={c.isActive} onChange={(e) => submit({ id: c.id, isActive: e.target.checked })} /></TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
        <div className="grid gap-2 md:grid-cols-6">
          <Input placeholder={tx('Code')} value={form.code ?? ''} onChange={(e) => setForm({ ...form, code: e.target.value })} />
          <Input className="md:col-span-2" placeholder={tx('Name')} value={form.name ?? ''} onChange={(e) => setForm({ ...form, name: e.target.value })} />
          <select className="rounded-md border p-2" value={form.treatment} onChange={(e) => setForm({ ...form, treatment: e.target.value as any })}>
            <option value="capitalize">{tx('Add to inventory')}</option>
            <option value="expense">{tx('Expense')}</option>
            <option value="recoverable_tax">{tx('Recoverable tax')}</option>
          </select>
          <select className="rounded-md border p-2" value={form.expenseAccountId ?? ''} onChange={(e) => setForm({ ...form, expenseAccountId: e.target.value })}>
            <option value="">{tx('Expense account')}</option>
            {(accounts?.data ?? []).map((a: any) => <option key={a.id} value={a.id}>{a.code} · {a.name}</option>)}
          </select>
          <Button disabled={!form.code || !form.name} onClick={() => submit(form)}>{tx('Add component')}</Button>
        </div>
      </CardContent>
    </Card>
  );
}

export default LandedCostReportsPage;

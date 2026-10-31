import { useState } from 'react';
import { Link } from 'react-router-dom';
import { Calculator } from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Dialog, DialogContent, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { Input } from '@/components/ui/input';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs';
import { DateRangeFilter, FilterSelect, ListPageHeader, ListToolbar } from '@/components/list';
import { useAuthStore } from '@/stores/auth.store';
import { money } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';
import { apiMessage, notify } from '@/lib/notify';
import { Field } from '../../fleet/fleet-ui';
import {
  useCostRates, useCostSummary, useMargin, useSaveRate, useVariances, type CostRate,
} from '@/features/manufacturing/api-costing';
import { pct, qty } from '@/features/manufacturing/api-planning';

const ymd = (d: Date) => d.toISOString().slice(0, 10);

/** Cost by shift, plant, product or month; variances; gross margin; absorption rates (ADR-S018). */
export function CostingPage() {
  const today = new Date();
  const [from, setFrom] = useState(ymd(new Date(today.getFullYear(), today.getMonth(), 1)));
  const [to, setTo] = useState(ymd(today));
  return (
    <div className="space-y-4">
      <ListPageHeader icon={Calculator} title={tx('Manufacturing Costing')}
        description={tx('Actual cost by shift, plant and product, variances, margin and absorption rates.')} />
      <ListToolbar><DateRangeFilter from={from} to={to} onChange={(f, t) => { if (f && t) { setFrom(f); setTo(t); } }} /></ListToolbar>
      <Tabs defaultValue="cost">
        <TabsList>
          <TabsTrigger value="cost">{tx('Cost')}</TabsTrigger>
          <TabsTrigger value="variances">{tx('Variances')}</TabsTrigger>
          <TabsTrigger value="margin">{tx('Margin')}</TabsTrigger>
          <TabsTrigger value="rates">{tx('Rates')}</TabsTrigger>
        </TabsList>
        <TabsContent value="cost"><CostTab from={from} to={to} /></TabsContent>
        <TabsContent value="variances"><VariancesTab from={from} to={to} /></TabsContent>
        <TabsContent value="margin"><MarginTab from={from} to={to} /></TabsContent>
        <TabsContent value="rates"><RatesTab /></TabsContent>
      </Tabs>
    </div>
  );
}

function CostTab({ from, to }: { from: string; to: string }) {
  const [groupBy, setGroupBy] = useState('product');
  const { data, isLoading, isError } = useCostSummary({ from, to, groupBy });
  return (
    <Card>
      <CardHeader className="flex flex-row items-center justify-between">
        <CardTitle>{tx('Production cost')}</CardTitle>
        <FilterSelect value={groupBy} onChange={(v) => setGroupBy(v || 'product')} allLabel={tx('Group by')} options={[
          { value: 'product', label: tx('Product') }, { value: 'plant', label: tx('Plant') },
          { value: 'shift', label: tx('Shift') }, { value: 'month', label: tx('Month') },
        ]} />
      </CardHeader>
      <CardContent className="overflow-x-auto">
        {isLoading && <p className="text-muted-foreground">{tx('Loading…')}</p>}
        {isError && <p className="text-destructive">{tx('Could not load the report.')}</p>}
        {data?.rows.length === 0 && <p className="text-sm text-muted-foreground">{tx('No production in this period.')}</p>}
        {data && data.rows.length > 0 && (
          <Table>
            <TableHeader><TableRow>
              <TableHead /><TableHead className="text-right">{tx('Quantity')}</TableHead><TableHead className="text-right">{tx('Cost')}</TableHead><TableHead className="text-right">{tx('Unit cost')}</TableHead>
            </TableRow></TableHeader>
            <TableBody>
              {data.rows.map((r) => (
                <TableRow key={r.key}>
                  <TableCell>{r.label === '-' ? tx('Not assigned') : r.label}</TableCell><TableCell className="text-right tabular-nums">{qty(r.quantity)}</TableCell>
                  <TableCell className="text-right tabular-nums">{money(r.cost)}</TableCell>
                  <TableCell className="text-right tabular-nums">{r.unitCost == null ? '-' : money(r.unitCost)}</TableCell>
                </TableRow>
              ))}
              <TableRow className="font-semibold">
                <TableCell>{tx('Total')}</TableCell><TableCell className="text-right tabular-nums">{qty(data.totals.quantity)}</TableCell>
                <TableCell className="text-right tabular-nums">{money(data.totals.cost)}</TableCell>
                <TableCell className="text-right tabular-nums">{data.totals.unitCost == null ? '-' : money(data.totals.unitCost)}</TableCell>
              </TableRow>
            </TableBody>
          </Table>
        )}
      </CardContent>
    </Card>
  );
}

function VariancesTab({ from, to }: { from: string; to: string }) {
  const { data, isLoading, isError } = useVariances({ from, to });
  return (
    <Card>
      <CardHeader><CardTitle>{tx('Variances of completed orders')}</CardTitle></CardHeader>
      <CardContent className="overflow-x-auto">
        {isLoading && <p className="text-muted-foreground">{tx('Loading…')}</p>}
        {isError && <p className="text-destructive">{tx('Could not load the report.')}</p>}
        {data?.orders.length === 0 && <p className="text-sm text-muted-foreground">{tx('No completed orders in this period.')}</p>}
        {data && data.orders.length > 0 && (
          <Table>
            <TableHeader><TableRow>
              <TableHead>{tx('Order')}</TableHead><TableHead>{tx('Product')}</TableHead><TableHead className="text-right">{tx('Material price')}</TableHead>
              <TableHead className="text-right">{tx('Material usage')}</TableHead><TableHead className="text-right">{tx('Labour efficiency')}</TableHead>
              <TableHead className="text-right">{tx('Overhead')}</TableHead><TableHead className="text-right">{tx('Yield')}</TableHead><TableHead className="text-right">{tx('Total')}</TableHead>
            </TableRow></TableHeader>
            <TableBody>
              {data.orders.map((o) => (
                <TableRow key={o.orderId}>
                  <TableCell><Link className="underline" to={`/manufacturing/costing/orders/${o.orderId}`}>{o.orderCode}</Link></TableCell>
                  <TableCell>{o.productName}</TableCell><TableCell className="text-right tabular-nums">{money(o.materialPrice)}</TableCell>
                  <TableCell className="text-right tabular-nums">{money(o.materialUsage)}</TableCell><TableCell className="text-right tabular-nums">{money(o.labourEfficiency)}</TableCell>
                  <TableCell className="text-right tabular-nums">{money(o.overhead)}</TableCell><TableCell className="text-right tabular-nums">{money(o.yield)}</TableCell>
                  <TableCell className="text-right font-semibold tabular-nums">{money(o.total)}</TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        )}
      </CardContent>
    </Card>
  );
}

function MarginTab({ from, to }: { from: string; to: string }) {
  const [groupBy, setGroupBy] = useState('product');
  const { data, isLoading, isError } = useMargin({ from, to, groupBy });
  return (
    <Card>
      <CardHeader className="flex flex-row items-center justify-between">
        <CardTitle>{tx('Gross margin')}</CardTitle>
        <FilterSelect value={groupBy} onChange={(v) => setGroupBy(v || 'product')} allLabel={tx('Group by')} options={[
          { value: 'product', label: tx('Product') }, { value: 'customer', label: tx('Customer') },
        ]} />
      </CardHeader>
      <CardContent className="overflow-x-auto">
        {isLoading && <p className="text-muted-foreground">{tx('Loading…')}</p>}
        {isError && <p className="text-destructive">{tx('Could not load the report.')}</p>}
        {data?.rows.length === 0 && <p className="text-sm text-muted-foreground">{tx('No sales in this period.')}</p>}
        {data && data.rows.length > 0 && (
          <Table>
            <TableHeader><TableRow>
              <TableHead /><TableHead className="text-right">{tx('Quantity')}</TableHead><TableHead className="text-right">{tx('Revenue')}</TableHead>
              <TableHead className="text-right">{tx('Cost')}</TableHead><TableHead className="text-right">{tx('Margin')}</TableHead><TableHead className="text-right">{tx('Margin %')}</TableHead>
            </TableRow></TableHeader>
            <TableBody>
              {data.rows.map((r) => (
                <TableRow key={r.key}>
                  <TableCell>{r.label}</TableCell><TableCell className="text-right tabular-nums">{qty(r.quantity)}</TableCell>
                  <TableCell className="text-right tabular-nums">{money(r.revenue)}</TableCell><TableCell className="text-right tabular-nums">{money(r.cost)}</TableCell>
                  <TableCell className="text-right tabular-nums">{money(r.margin)}</TableCell><TableCell className="text-right">{pct(r.marginPct)}</TableCell>
                </TableRow>
              ))}
              <TableRow className="font-semibold">
                <TableCell>{tx('Total')}</TableCell><TableCell /><TableCell className="text-right tabular-nums">{money(data.totals.revenue)}</TableCell>
                <TableCell className="text-right tabular-nums">{money(data.totals.cost)}</TableCell><TableCell className="text-right tabular-nums">{money(data.totals.margin)}</TableCell>
                <TableCell className="text-right">{pct(data.totals.marginPct)}</TableCell>
              </TableRow>
            </TableBody>
          </Table>
        )}
      </CardContent>
    </Card>
  );
}

function RatesTab() {
  const { data, isLoading, isError } = useCostRates();
  const [editing, setEditing] = useState<CostRate | null>(null);
  const can = useAuthStore((s) => s.hasPermission);
  const canEdit = can(PERMISSIONS.standardCost.manage);
  return (
    <Card>
      <CardHeader><CardTitle>{tx('Absorption rates per work centre')}</CardTitle></CardHeader>
      <CardContent className="overflow-x-auto">
        {isLoading && <p className="text-muted-foreground">{tx('Loading…')}</p>}
        {isError && <p className="text-destructive">{tx('Could not load the rates.')}</p>}
        {data?.length === 0 && <p className="text-sm text-muted-foreground">{tx('No active work centres.')}</p>}
        {data && data.length > 0 && (
          <Table>
            <TableHeader><TableRow>
              <TableHead>{tx('Work centre')}</TableHead><TableHead className="text-right">{tx('Labour / h')}</TableHead><TableHead className="text-right">{tx('Machine / h')}</TableHead>
              <TableHead className="text-right">{tx('Overhead / h')}</TableHead><TableHead className="text-right">{tx('Energy / unit')}</TableHead><TableHead />
            </TableRow></TableHeader>
            <TableBody>
              {data.map((r) => (
                <TableRow key={r.workCenterId}>
                  <TableCell>{r.workCenterName}</TableCell>
                  <TableCell className="text-right tabular-nums">{money(r.labourRatePerHour ?? 0)}</TableCell>
                  <TableCell className="text-right tabular-nums">{money(r.effectiveMachineRate ?? r.machineRatePerHour ?? 0)}</TableCell>
                  <TableCell className="text-right tabular-nums">{money(r.overheadRatePerHour ?? 0)}</TableCell>
                  <TableCell className="text-right tabular-nums">{money(r.energyRatePerUnit ?? 0)}</TableCell>
                  <TableCell className="text-right">{canEdit && <Button size="sm" variant="outline" onClick={() => setEditing(r)}>{tx('Edit')}</Button>}</TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        )}
      </CardContent>
      {editing && <RateDialog key={editing.workCenterId} rate={editing} onClose={() => setEditing(null)} />}
    </Card>
  );
}

function RateDialog({ rate, onClose }: { rate: CostRate; onClose: () => void }) {
  const [labour, setLabour] = useState(String(rate.labourRatePerHour ?? 0));
  const [machine, setMachine] = useState(String(rate.machineRatePerHour ?? 0));
  const [overhead, setOverhead] = useState(String(rate.overheadRatePerHour ?? 0));
  const [energy, setEnergy] = useState(String(rate.energyRatePerUnit ?? 0));
  const [meter, setMeter] = useState(rate.energyMeterCode ?? '');
  const [basis, setBasis] = useState(rate.overheadBasis ?? 'machine_hours');
  const save = useSaveRate();
  const onSave = () => save.mutate({
    workCenterId: rate.workCenterId,
    body: {
      labourRatePerHour: Number(labour), machineRatePerHour: Number(machine), overheadRatePerHour: Number(overhead),
      overheadBasis: basis, energyRatePerUnit: Number(energy), energyMeterCode: meter || undefined,
    },
  }, {
    onSuccess: () => { notify.success(tx('Saved')); onClose(); },
    onError: (e) => notify.error(apiMessage(e, tx('Could not save'))),
  });
  return (
    <Dialog open onOpenChange={(o) => { if (!o) onClose(); }}>
      <DialogContent>
        <DialogHeader><DialogTitle>{rate.workCenterName}</DialogTitle></DialogHeader>
        <div className="grid grid-cols-2 gap-3">
          <Field label={tx('Labour / h')}><Input type="number" min="0" value={labour} onChange={(e) => setLabour(e.target.value)} /></Field>
          <Field label={tx('Machine / h')}><Input type="number" min="0" value={machine} onChange={(e) => setMachine(e.target.value)} /></Field>
          <Field label={tx('Overhead / h')}><Input type="number" min="0" value={overhead} onChange={(e) => setOverhead(e.target.value)} /></Field>
          <Field label={tx('Overhead basis')}>
            <FilterSelect value={basis} onChange={(v) => setBasis(v || 'machine_hours')} allLabel={tx('Overhead basis')} options={[
              { value: 'machine_hours', label: tx('Machine hours') }, { value: 'labour_hours', label: tx('Labour hours') },
            ]} />
          </Field>
          <Field label={tx('Energy / unit')}><Input type="number" min="0" value={energy} onChange={(e) => setEnergy(e.target.value)} /></Field>
          <Field label={tx('Energy meter code')}><Input value={meter} onChange={(e) => setMeter(e.target.value)} maxLength={50} /></Field>
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={onClose}>{tx('Cancel')}</Button>
          <Button disabled={save.isPending} onClick={onSave}>{tx('Save')}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

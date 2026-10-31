import { Link, useParams } from 'react-router-dom';
import { ArrowLeft, Calculator } from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { useAuthStore } from '@/stores/auth.store';
import { money } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';
import { apiMessage, notify } from '@/lib/notify';
import { useAbsorb, useOrderCost, useOrderVariances, type Num } from '@/features/manufacturing/api-costing';
import { qty } from '@/features/manufacturing/api-planning';

/** Actual cost build-up of one order, the absorption due and its variances (ADR-S018). */
export function OrderCostPage() {
  const { id = '' } = useParams();
  const cost = useOrderCost(id);
  const variances = useOrderVariances(id);
  const absorb = useAbsorb();
  const can = useAuthStore((s) => s.hasPermission);
  const c = cost.data;
  const v = variances.data;
  const due = c?.absorptionDue ?? [];
  const onAbsorb = () => absorb.mutate(id, {
    onSuccess: (r) => (r.skipped.length > 0
      ? notify.error(tx('Some cost could not be absorbed: the stages are closed'))
      : notify.success(tx('Absorbed {{amount}}', { amount: money(r.total) }))),
    onError: (e) => notify.error(apiMessage(e, tx('Could not absorb the cost'))),
  });
  const tiles: Array<[string, Num]> = c ? [
    [tx('Materials'), c.materialCost], [tx('Labour'), c.labourCost], [tx('Machine'), c.machineCost],
    [tx('Energy'), c.energyCost], [tx('Overhead'), c.overheadCost], [tx('Total'), c.totalCost],
  ] : [];
  return (
    <div className="space-y-4">
      <div className="flex items-center gap-2">
        <Button asChild variant="ghost" size="icon"><Link to={`/manufacturing/orders/${id}`} aria-label={tx('Back')}><ArrowLeft className="h-4 w-4" /></Link></Button>
        <Calculator className="h-5 w-5" /><h1 className="text-xl font-semibold">{c?.orderCode ?? tx('Order cost')}</h1>
      </div>
      {cost.isLoading && <p className="text-muted-foreground">{tx('Loading…')}</p>}
      {cost.isError && <p className="text-destructive">{tx('Could not load the order cost.')}</p>}
      {c && (
        <>
          <div className="grid grid-cols-2 gap-3 md:grid-cols-6">
            {tiles.map(([label, value]) => (
              <Card key={label}><CardContent className="pt-4"><div className="text-xs text-muted-foreground">{label}</div><div className="font-semibold tabular-nums">{money(value)}</div></CardContent></Card>
            ))}
          </div>
          <p className="text-sm text-muted-foreground">
            {tx('Produced {{qty}}, unit cost {{unit}}', { qty: qty(c.producedQty), unit: c.unitCost == null ? '-' : money(c.unitCost) })}
            {c.precast?.costPerAcceptableBlock != null && ` · ${tx('Cost per acceptable block')}: ${money(c.precast.costPerAcceptableBlock)}`}
            {c.crushing?.costPerTonne != null && ` · ${tx('Cost per tonne')}: ${money(c.crushing.costPerTonne)}`}
          </p>
          <Card>
            <CardHeader className="flex flex-row items-center justify-between">
              <CardTitle>{tx('Absorption due')}</CardTitle>
              {can(PERMISSIONS.productionOrder.complete) && due.length > 0 && (
                <Button disabled={absorb.isPending} onClick={onAbsorb}>{tx('Absorb into WIP')}</Button>
              )}
            </CardHeader>
            <CardContent className="overflow-x-auto">
              {due.length === 0 ? <p className="text-sm text-muted-foreground">{tx('Nothing to absorb. Check the work centre rates if hours were recorded.')}</p> : (
                <Table>
                  <TableHeader><TableRow>
                    <TableHead>{tx('Operation')}</TableHead><TableHead>{tx('Kind')}</TableHead><TableHead className="text-right">{tx('Basis')}</TableHead>
                    <TableHead className="text-right">{tx('Rate')}</TableHead><TableHead className="text-right">{tx('Amount')}</TableHead>
                  </TableRow></TableHeader>
                  <TableBody>
                    {due.map((d) => (
                      <TableRow key={`${d.workOrderId}-${d.kind}`}>
                        <TableCell>{d.workOrderName}</TableCell><TableCell>{d.kind}</TableCell>
                        <TableCell className="text-right tabular-nums">{qty(d.basisQty)}</TableCell>
                        <TableCell className="text-right tabular-nums">{money(d.rate)}</TableCell>
                        <TableCell className="text-right tabular-nums">{money(d.amount)}</TableCell>
                      </TableRow>
                    ))}
                  </TableBody>
                </Table>
              )}
            </CardContent>
          </Card>
        </>
      )}
      {v && (
        <Card>
          <CardHeader><CardTitle>{tx('Variances')}</CardTitle></CardHeader>
          <CardContent className="space-y-3 overflow-x-auto">
            <p className="text-sm text-muted-foreground">{tx('Positive is unfavourable. Hours: {{actual}} actual, {{standard}} standard.', { actual: qty(v.actualHours), standard: qty(v.standardHours) })}</p>
            <Table>
              <TableHeader><TableRow>
                <TableHead>{tx('Material price')}</TableHead><TableHead>{tx('Material usage')}</TableHead><TableHead>{tx('Labour efficiency')}</TableHead>
                <TableHead>{tx('Overhead')}</TableHead><TableHead>{tx('Yield')}</TableHead><TableHead>{tx('Total')}</TableHead>
              </TableRow></TableHeader>
              <TableBody><TableRow>
                <TableCell>{money(v.materialPrice)}</TableCell><TableCell>{money(v.materialUsage)}</TableCell><TableCell>{money(v.labourEfficiency)}</TableCell>
                <TableCell>{money(v.overhead)}</TableCell><TableCell>{money(v.yield)}</TableCell><TableCell className="font-semibold">{money(v.total)}</TableCell>
              </TableRow></TableBody>
            </Table>
            {v.materials.length > 0 && (
              <Table>
                <TableHeader><TableRow>
                  <TableHead>{tx('Material')}</TableHead><TableHead className="text-right">{tx('Planned')}</TableHead><TableHead className="text-right">{tx('Actual')}</TableHead>
                  <TableHead className="text-right">{tx('Material price')}</TableHead><TableHead className="text-right">{tx('Material usage')}</TableHead>
                </TableRow></TableHeader>
                <TableBody>
                  {v.materials.map((m) => (
                    <TableRow key={m.productId}>
                      <TableCell>{m.productName}</TableCell><TableCell className="text-right tabular-nums">{qty(m.plannedQty)}</TableCell>
                      <TableCell className="text-right tabular-nums">{qty(m.actualQty)}</TableCell>
                      <TableCell className="text-right tabular-nums">{money(m.priceVariance)}</TableCell>
                      <TableCell className="text-right tabular-nums">{money(m.usageVariance)}</TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            )}
          </CardContent>
        </Card>
      )}
    </div>
  );
}

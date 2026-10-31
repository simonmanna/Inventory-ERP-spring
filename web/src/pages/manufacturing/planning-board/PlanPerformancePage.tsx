import { useState } from 'react';
import { Link } from 'react-router-dom';
import { Activity } from 'lucide-react';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { DateRangeFilter, ListPageHeader, ListToolbar } from '@/components/list';
import { tx } from '@/lib/i18n/tx';
import { pct, qty, usePlanReport, type DemandProduct, type Num } from '@/features/manufacturing/api-planning';
import { ymd } from './planning-ui';

interface VsPlan {
  byProduct: Array<{ productId: string; productName: string; targetQty: Num; producedQty: Num; varianceQty: Num; attainmentPct: Num | null }>;
  totals: { targetQty: Num; producedQty: Num; attainmentPct: Num | null };
}
interface Adherence {
  orders: { due: number; onTime: number; late: number; overdue: number; open: number; adherencePct: Num | null };
  workOrders: { total: number; onTime: number; adherencePct: Num | null };
  shiftPlans: { published: number; met: number; adherencePct: Num | null };
  orderRows: Array<{ id: string; orderCode: string; productName: string; partnerName: string | null; outcome: string; minutesLate: number }>;
}
interface VsTarget {
  rows: Array<{ id: string; subjectName: string; targetQty: Num; actualQty: Num; targetToDate: Num; attainmentPct: Num | null; attainmentToDatePct: Num | null }>;
}

function outcomeLabel(o: string) {
  switch (o) {
    case 'on_time': return tx('On time');
    case 'late': return tx('Late');
    case 'overdue': return tx('Overdue');
    default: return tx('Open');
  }
}

/** Production vs plan, plan adherence, production vs target and the shortfall (ADR-S017). */
export function PlanPerformancePage() {
  const today = new Date();
  const [from, setFrom] = useState(ymd(new Date(today.getFullYear(), today.getMonth(), 1)));
  const [to, setTo] = useState(ymd(today));
  const filters = { from, to };
  const vsPlan = usePlanReport<VsPlan>('production-vs-plan', filters);
  const adherence = usePlanReport<Adherence>('plan-adherence', filters);
  const vsTarget = usePlanReport<VsTarget>('production-vs-target', filters);
  const shortfall = usePlanReport<{ products: DemandProduct[] }>('shortfall', {});
  const a = adherence.data;
  return (
    <div className="space-y-4">
      <ListPageHeader icon={Activity} title={tx('Plan Performance')}
        description={tx('Production against plan and target, due dates kept, and what is still short.')} />
      <ListToolbar><DateRangeFilter from={from} to={to} onChange={(f, t) => { if (f && t) { setFrom(f); setTo(t); } }} /></ListToolbar>
      {(vsPlan.isLoading || adherence.isLoading) && <p className="text-muted-foreground">{tx('Loading…')}</p>}
      {(vsPlan.isError || adherence.isError) && <p className="text-destructive">{tx('Could not load the report.')}</p>}
      {a && (
        <div className="grid gap-4 sm:grid-cols-3">
          <Card><CardHeader><CardTitle>{tx('Orders on time')}</CardTitle></CardHeader><CardContent>
            <div className="text-2xl font-semibold">{pct(a.orders.adherencePct)}</div>
            <p className="text-sm text-muted-foreground">{tx('{{onTime}} on time, {{late}} late, {{overdue}} overdue', a.orders)}</p>
          </CardContent></Card>
          <Card><CardHeader><CardTitle>{tx('Operations on time')}</CardTitle></CardHeader><CardContent>
            <div className="text-2xl font-semibold">{pct(a.workOrders.adherencePct)}</div>
            <p className="text-sm text-muted-foreground">{a.workOrders.onTime} / {a.workOrders.total}</p>
          </CardContent></Card>
          <Card><CardHeader><CardTitle>{tx('Shift plans met')}</CardTitle></CardHeader><CardContent>
            <div className="text-2xl font-semibold">{pct(a.shiftPlans.adherencePct)}</div>
            <p className="text-sm text-muted-foreground">{a.shiftPlans.met} / {a.shiftPlans.published}</p>
          </CardContent></Card>
        </div>
      )}
      {vsPlan.data && (
        <Card>
          <CardHeader><CardTitle>{tx('Production vs plan')}</CardTitle></CardHeader>
          <CardContent className="overflow-x-auto">
            {vsPlan.data.byProduct.length === 0 ? <p className="text-sm text-muted-foreground">{tx('No plan lines in this period.')}</p> : (
              <Table>
                <TableHeader><TableRow>
                  <TableHead>{tx('Product')}</TableHead><TableHead className="text-right">{tx('Target')}</TableHead>
                  <TableHead className="text-right">{tx('Produced')}</TableHead><TableHead className="text-right">{tx('Variance')}</TableHead>
                  <TableHead className="text-right">{tx('Attainment')}</TableHead>
                </TableRow></TableHeader>
                <TableBody>
                  {vsPlan.data.byProduct.map((p) => (
                    <TableRow key={p.productId}>
                      <TableCell>{p.productName}</TableCell><TableCell className="text-right tabular-nums">{qty(p.targetQty)}</TableCell>
                      <TableCell className="text-right tabular-nums">{qty(p.producedQty)}</TableCell>
                      <TableCell className="text-right tabular-nums">{qty(p.varianceQty)}</TableCell>
                      <TableCell className="text-right">{pct(p.attainmentPct)}</TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            )}
          </CardContent>
        </Card>
      )}
      {vsTarget.data && vsTarget.data.rows.length > 0 && (
        <Card>
          <CardHeader><CardTitle>{tx('Production vs target')}</CardTitle></CardHeader>
          <CardContent className="overflow-x-auto">
            <Table>
              <TableHeader><TableRow>
                <TableHead>{tx('Applies to')}</TableHead><TableHead className="text-right">{tx('Target')}</TableHead>
                <TableHead className="text-right">{tx('Target to date')}</TableHead><TableHead className="text-right">{tx('Actual')}</TableHead>
                <TableHead className="text-right">{tx('Attainment')}</TableHead>
              </TableRow></TableHeader>
              <TableBody>
                {vsTarget.data.rows.map((r) => (
                  <TableRow key={r.id}>
                    <TableCell>{r.subjectName}</TableCell><TableCell className="text-right tabular-nums">{qty(r.targetQty)}</TableCell>
                    <TableCell className="text-right tabular-nums">{qty(r.targetToDate)}</TableCell>
                    <TableCell className="text-right tabular-nums">{qty(r.actualQty)}</TableCell>
                    <TableCell className="text-right">{pct(r.attainmentPct)}</TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </CardContent>
        </Card>
      )}
      {a && a.orderRows.length > 0 && (
        <Card>
          <CardHeader><CardTitle>{tx('Orders due')}</CardTitle></CardHeader>
          <CardContent className="overflow-x-auto">
            <Table>
              <TableHeader><TableRow>
                <TableHead>{tx('Order')}</TableHead><TableHead>{tx('Product')}</TableHead><TableHead>{tx('Customer')}</TableHead><TableHead>{tx('Result')}</TableHead>
              </TableRow></TableHeader>
              <TableBody>
                {a.orderRows.map((o) => (
                  <TableRow key={o.id}>
                    <TableCell><Link className="underline" to={`/manufacturing/orders/${o.id}`}>{o.orderCode}</Link></TableCell>
                    <TableCell>{o.productName}</TableCell><TableCell>{o.partnerName}</TableCell><TableCell>{outcomeLabel(o.outcome)}</TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </CardContent>
        </Card>
      )}
      {shortfall.data && shortfall.data.products.length > 0 && (
        <Card>
          <CardHeader><CardTitle>{tx('Shortfall, next 30 days')}</CardTitle></CardHeader>
          <CardContent className="overflow-x-auto">
            <Table>
              <TableHeader><TableRow>
                <TableHead>{tx('Product')}</TableHead><TableHead className="text-right">{tx('Demand')}</TableHead>
                <TableHead className="text-right">{tx('Supply')}</TableHead><TableHead className="text-right">{tx('Shortfall')}</TableHead>
              </TableRow></TableHeader>
              <TableBody>
                {shortfall.data.products.map((p) => (
                  <TableRow key={p.productId}>
                    <TableCell>{p.productName}</TableCell><TableCell className="text-right tabular-nums">{qty(p.demandQty)}</TableCell>
                    <TableCell className="text-right tabular-nums">{qty(p.supplyQty)}</TableCell>
                    <TableCell className="text-right tabular-nums text-destructive">{qty(p.shortfallQty)}</TableCell>
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

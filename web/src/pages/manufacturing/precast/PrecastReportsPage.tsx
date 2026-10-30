import { useState } from 'react';
import { Link } from 'react-router-dom';
import { BarChart3, X } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { ListPageHeader } from '@/components/list';
import { money, useOrgCurrency } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';
import { useBreakage, useCostPerBlock, type ReportParams } from '@/features/manufacturing/api-precast';
import { Field, OrderStatusPill, ProductPicker } from './precast-ui';
import { num, pct } from './precast-format';

const monthStart = () => {
  const d = new Date();
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-01`;
};

/** Precast reports (2.4): cost per acceptable block per product and order; breakage by reason and stage, second grade. */
export function PrecastReportsPage() {
  const currency = useOrgCurrency();
  const m = (v: unknown) => (v == null ? '—' : money(v as string, currency));
  const [from, setFrom] = useState(monthStart());
  const [to, setTo] = useState('');
  const [productId, setProductId] = useState('');
  const [tab, setTab] = useState('cost');
  const params: ReportParams = { from: from || undefined, to: to || undefined, productId: productId || undefined };
  const cost = useCostPerBlock(params, tab === 'cost');
  const breakage = useBreakage(params, tab === 'breakage');

  return (
    <div className="space-y-4">
      <ListPageHeader icon={BarChart3} title={tx('Precast reports')} description={tx('Cost per acceptable block, breakage at press and cubing, second-grade output.')} />
      <Card>
        <CardContent className="flex flex-wrap items-end gap-3 p-4">
          <Field label={tx('From')}><Input type="date" value={from} onChange={(e) => setFrom(e.target.value)} /></Field>
          <Field label={tx('To')}><Input type="date" value={to} onChange={(e) => setTo(e.target.value)} /></Field>
          <Field label={tx('Product')} className="min-w-[240px]">
            <div className="flex gap-1">
              <div className="min-w-0 flex-1"><ProductPicker value={productId} onChange={setProductId} placeholder={tx('All products')} /></div>
              {productId && <Button size="icon" variant="ghost" aria-label={tx('Clear')} onClick={() => setProductId('')}><X className="h-4 w-4" /></Button>}
            </div>
          </Field>
        </CardContent>
      </Card>

      <Tabs value={tab} onValueChange={setTab}>
        <TabsList>
          <TabsTrigger value="cost">{tx('Cost per block')}</TabsTrigger>
          <TabsTrigger value="breakage">{tx('Breakage')}</TabsTrigger>
        </TabsList>

        <TabsContent value="cost" className="space-y-4">
          {cost.isLoading && <p className="text-sm text-muted-foreground">{tx('Loading…')}</p>}
          {cost.isError && <p className="text-sm text-destructive">{tx('Could not load the report.')}</p>}
          {cost.data && (
            <>
              <Card>
                <CardHeader><CardTitle>{tx('By product')}</CardTitle></CardHeader>
                <CardContent className="overflow-x-auto">
                  {!cost.data.byProduct.length ? <p className="text-sm text-muted-foreground">{tx('No precast orders in this period.')}</p> : (
                    <Table>
                      <TableHeader>
                        <TableRow>
                          <TableHead>{tx('Product')}</TableHead>
                          <TableHead className="text-right">{tx('Orders')}</TableHead>
                          <TableHead className="text-right">{tx('Blocks pressed')}</TableHead>
                          <TableHead className="text-right">{tx('Good blocks')}</TableHead>
                          <TableHead className="text-right">{tx('Second grade')}</TableHead>
                          <TableHead className="text-right">{tx('Broken at press')}</TableHead>
                          <TableHead className="text-right">{tx('Broken at cubing')}</TableHead>
                          <TableHead className="text-right">{tx('Rejection rate')}</TableHead>
                          <TableHead className="text-right">{tx('Total cost')}</TableHead>
                          <TableHead className="text-right">{tx('Finished goods value')}</TableHead>
                          <TableHead className="text-right">{tx('Cost per acceptable block')}</TableHead>
                        </TableRow>
                      </TableHeader>
                      <TableBody>
                        {cost.data.byProduct.map((r) => (
                          <TableRow key={r.productId}>
                            <TableCell className="font-medium">{r.productName}</TableCell>
                            <TableCell className="text-right tabular-nums">{num(r.orders, 0)}</TableCell>
                            <TableCell className="text-right tabular-nums">{num(r.blocksPressed, 0)}</TableCell>
                            <TableCell className="text-right tabular-nums">{num(r.goodBlocks, 0)}</TableCell>
                            <TableCell className="text-right tabular-nums">{num(r.secondGrade, 0)}</TableCell>
                            <TableCell className="text-right tabular-nums">{num(r.brokenAtPress, 0)}</TableCell>
                            <TableCell className="text-right tabular-nums">{num(r.brokenAtCubing, 0)}</TableCell>
                            <TableCell className="text-right tabular-nums">{pct(r.rejectionRatePct)}</TableCell>
                            <TableCell className="text-right tabular-nums">{m(r.totalCost)}</TableCell>
                            <TableCell className="text-right tabular-nums">{m(r.finishedGoodsValue)}</TableCell>
                            <TableCell className="text-right font-semibold tabular-nums">{m(r.costPerAcceptableBlock)}</TableCell>
                          </TableRow>
                        ))}
                      </TableBody>
                    </Table>
                  )}
                </CardContent>
              </Card>
              <Card>
                <CardHeader><CardTitle>{tx('By order')}</CardTitle></CardHeader>
                <CardContent className="overflow-x-auto">
                  {!cost.data.rows.length ? <p className="text-sm text-muted-foreground">{tx('No precast orders in this period.')}</p> : (
                    <Table>
                      <TableHeader>
                        <TableRow>
                          <TableHead>{tx('Order')}</TableHead>
                          <TableHead>{tx('Product')}</TableHead>
                          <TableHead>{tx('Status')}</TableHead>
                          <TableHead className="text-right">{tx('Blocks pressed')}</TableHead>
                          <TableHead className="text-right">{tx('Good blocks')}</TableHead>
                          <TableHead className="text-right">{tx('Second grade')}</TableHead>
                          <TableHead className="text-right">{tx('Broken at press')}</TableHead>
                          <TableHead className="text-right">{tx('Broken at cubing')}</TableHead>
                          <TableHead className="text-right">{tx('Rejection rate')}</TableHead>
                          <TableHead className="text-right">{tx('Total cost')}</TableHead>
                          <TableHead className="text-right">{tx('Finished goods value')}</TableHead>
                          <TableHead className="text-right">{tx('Cost per acceptable block')}</TableHead>
                        </TableRow>
                      </TableHeader>
                      <TableBody>
                        {cost.data.rows.map((r) => (
                          <TableRow key={r.id}>
                            <TableCell><Link className="font-mono text-xs text-primary underline" to={`/manufacturing/precast/orders/${r.id}`}>{r.orderCode}</Link></TableCell>
                            <TableCell>{r.productName}</TableCell>
                            <TableCell><OrderStatusPill status={r.status} /></TableCell>
                            <TableCell className="text-right tabular-nums">{num(r.blocksPressed, 0)}</TableCell>
                            <TableCell className="text-right tabular-nums">{num(r.goodBlocks, 0)}</TableCell>
                            <TableCell className="text-right tabular-nums">{num(r.secondGrade, 0)}</TableCell>
                            <TableCell className="text-right tabular-nums">{num(r.brokenAtPress, 0)}</TableCell>
                            <TableCell className="text-right tabular-nums">{num(r.brokenAtCubing, 0)}</TableCell>
                            <TableCell className="text-right tabular-nums">{pct(r.rejectionRatePct)}</TableCell>
                            <TableCell className="text-right tabular-nums">{m(r.totalCost)}</TableCell>
                            <TableCell className="text-right tabular-nums">{m(r.finishedGoodsValue)}</TableCell>
                            <TableCell className="text-right font-semibold tabular-nums">{m(r.costPerAcceptableBlock)}</TableCell>
                          </TableRow>
                        ))}
                      </TableBody>
                    </Table>
                  )}
                </CardContent>
              </Card>
            </>
          )}
        </TabsContent>

        <TabsContent value="breakage" className="space-y-4">
          {breakage.isLoading && <p className="text-sm text-muted-foreground">{tx('Loading…')}</p>}
          {breakage.isError && <p className="text-sm text-destructive">{tx('Could not load the report.')}</p>}
          {breakage.data && (
            <>
              <Card>
                <CardHeader><CardTitle>{tx('Breakage by reason')}</CardTitle></CardHeader>
                <CardContent className="overflow-x-auto">
                  {!breakage.data.byReason.length ? <p className="text-sm text-muted-foreground">{tx('No breakage in this period.')}</p> : (
                    <Table>
                      <TableHeader>
                        <TableRow>
                          <TableHead>{tx('Product')}</TableHead>
                          <TableHead>{tx('Stage')}</TableHead>
                          <TableHead>{tx('Reason')}</TableHead>
                          <TableHead className="text-right">{tx('Quantity')}</TableHead>
                          <TableHead className="text-right">{tx('Postings')}</TableHead>
                        </TableRow>
                      </TableHeader>
                      <TableBody>
                        {breakage.data.byReason.map((r, i) => (
                          <TableRow key={`${r.productId}-${r.stage}-${r.reasonCode ?? i}`}>
                            <TableCell>{r.productName}</TableCell>
                            <TableCell>{r.stage === 'pressing' ? tx('Pressing') : tx('Cubing')}</TableCell>
                            <TableCell>{r.reasonCode ? `${r.reasonCode}${r.reasonName ? ` · ${r.reasonName}` : ''}` : tx('No reason given')}</TableCell>
                            <TableCell className="text-right tabular-nums">{num(r.quantity, 0)}</TableCell>
                            <TableCell className="text-right tabular-nums">{num(r.postings, 0)}</TableCell>
                          </TableRow>
                        ))}
                      </TableBody>
                    </Table>
                  )}
                </CardContent>
              </Card>
              <Card>
                <CardHeader><CardTitle>{tx('Second grade')}</CardTitle></CardHeader>
                <CardContent className="overflow-x-auto">
                  {!breakage.data.secondGrade.length ? <p className="text-sm text-muted-foreground">{tx('No cubed batches in this period.')}</p> : (
                    <Table>
                      <TableHeader>
                        <TableRow>
                          <TableHead>{tx('Product')}</TableHead>
                          <TableHead className="text-right">{tx('Cured')}</TableHead>
                          <TableHead className="text-right">{tx('Good blocks')}</TableHead>
                          <TableHead className="text-right">{tx('Second grade')}</TableHead>
                        </TableRow>
                      </TableHeader>
                      <TableBody>
                        {breakage.data.secondGrade.map((r) => (
                          <TableRow key={r.productId}>
                            <TableCell>{r.productName}</TableCell>
                            <TableCell className="text-right tabular-nums">{num(r.cured, 0)}</TableCell>
                            <TableCell className="text-right tabular-nums">{num(r.goodBlocks, 0)}</TableCell>
                            <TableCell className="text-right tabular-nums">{num(r.secondGrade, 0)}</TableCell>
                          </TableRow>
                        ))}
                      </TableBody>
                    </Table>
                  )}
                </CardContent>
              </Card>
            </>
          )}
        </TabsContent>
      </Tabs>
    </div>
  );
}

export default PrecastReportsPage;

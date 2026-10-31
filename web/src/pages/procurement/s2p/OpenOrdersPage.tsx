import { useState } from 'react';
import { Link } from 'react-router-dom';
import { Clock } from 'lucide-react';
import { Card, CardContent } from '@/components/ui/card';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { ListPageHeader, StatusPill } from '@/components/list';
import { useOpenOrders } from '@/features/procurement/api';
import { date, money } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';

/** Open order lines still to be delivered, the late ones first (ADR-PROCUREMENT-001 wave 3). */
export function OpenOrdersPage() {
  const [overdueOnly, setOverdueOnly] = useState(false);
  const { data: rows = [], isLoading } = useOpenOrders({ overdueOnly });
  const late = rows.filter((r) => (r.daysLate ?? 0) > 0);
  const openValue = rows.reduce((s, r) => s + Number(r.openValue || 0), 0);

  return (
    <div className="space-y-4 p-6">
      <ListPageHeader title={tx('Open orders')} description={tx('What suppliers still owe you, and which deliveries are late.')} icon={Clock} />
      <div className="grid gap-3 sm:grid-cols-3">
        <Card><CardContent className="pt-6"><div className="text-xs text-muted-foreground">{tx('Open lines')}</div><div className="text-2xl font-semibold">{rows.length}</div></CardContent></Card>
        <Card><CardContent className="pt-6"><div className="text-xs text-muted-foreground">{tx('Late lines')}</div><div className="text-2xl font-semibold text-destructive">{late.length}</div></CardContent></Card>
        <Card><CardContent className="pt-6"><div className="text-xs text-muted-foreground">{tx('Open value')}</div><div className="text-2xl font-semibold">{money(openValue)}</div></CardContent></Card>
      </div>
      <label className="flex items-center gap-2 text-sm">
        <input type="checkbox" checked={overdueOnly} onChange={(e) => setOverdueOnly(e.target.checked)} /> {tx('Late only')}
      </label>
      <Card>
        <CardContent className="p-0">
          <Table>
            <TableHeader><TableRow>
              <TableHead>{tx('Order')}</TableHead><TableHead>{tx('Supplier')}</TableHead><TableHead>{tx('Item')}</TableHead>
              <TableHead className="text-right">{tx('Ordered')}</TableHead><TableHead className="text-right">{tx('Received')}</TableHead>
              <TableHead className="text-right">{tx('Open')}</TableHead><TableHead>{tx('Due')}</TableHead><TableHead className="text-right">{tx('Open value')}</TableHead>
            </TableRow></TableHeader>
            <TableBody>
              {isLoading && <TableRow><TableCell colSpan={8} className="text-muted-foreground">{tx('Loading…')}</TableCell></TableRow>}
              {!isLoading && !rows.length && <TableRow><TableCell colSpan={8} className="py-8 text-center text-muted-foreground">{tx('No open deliveries.')}</TableCell></TableRow>}
              {rows.map((r) => (
                <TableRow key={r.lineId}>
                  <TableCell><Link className="font-mono text-xs hover:underline" to={`/procurement/purchase-orders/${r.purchaseOrderId}`}>{r.orderNumber}</Link></TableCell>
                  <TableCell>{r.partnerName}</TableCell>
                  <TableCell>{r.description}</TableCell>
                  <TableCell className="text-right">{Number(r.quantity)}</TableCell>
                  <TableCell className="text-right">{Number(r.receivedQuantity)}</TableCell>
                  <TableCell className="text-right font-medium">{Number(r.openQuantity)}</TableCell>
                  <TableCell>
                    {r.dueDate ? (
                      <StatusPill tone={(r.daysLate ?? 0) > 0 ? 'danger' : 'neutral'}>
                        {date(r.dueDate)}{(r.daysLate ?? 0) > 0 ? ` · ${tx('{{n}} days late', { n: r.daysLate })}` : ''}
                      </StatusPill>
                    ) : '—'}
                  </TableCell>
                  <TableCell className="text-right">{money(Number(r.openValue), r.currencyCode)}</TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </CardContent>
      </Card>
    </div>
  );
}

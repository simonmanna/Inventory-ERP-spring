import { useState } from 'react';
import { CalendarClock, RotateCcw } from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Badge } from '@/components/ui/badge';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { useAuthStore } from '@/stores/auth.store';
import { notify } from '@/lib/notify';
import { tx } from '@/lib/i18n/tx';
import { useAvailability, useRework, useScheduleOrder, useVariances } from '@/features/manufacturing/api-advanced';

const errMsg = (e: any) => {
  const m = e?.response?.data?.message ?? e?.message;
  return Array.isArray(m) ? m.join(', ') : m;
};

/** ADR-S006 order tools: material availability + substitutes, finite scheduling, rework, standard-cost variances. */
export function OrderAdvancedPanel({ order }: { order: { id: string; status: string; standardCostId?: string | null } }) {
  const hasPermission = useAuthStore((s) => s.hasPermission);
  const canSchedule = hasPermission(PERMISSIONS.workOrder.manage) && !['completed', 'cancelled'].includes(order.status);
  const canRework = hasPermission(PERMISSIONS.productionOrder.qc) && order.status === 'qc_hold';
  const open = ['draft', 'confirmed'].includes(order.status);
  const { data: availability } = useAvailability(order.id, open);
  const { data: variances } = useVariances({ orderId: order.id }, order.status === 'completed' && !!order.standardCostId);
  const schedule = useScheduleOrder();
  const rework = useRework();
  const [startAt, setStartAt] = useState('');

  return (
    <>
      {open && !!availability?.length && (
        <Card>
          <CardHeader><CardTitle>{tx('Material availability')}</CardTitle></CardHeader>
          <CardContent>
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>{tx('Material')}</TableHead>
                  <TableHead className="text-right">{tx('Required')}</TableHead>
                  <TableHead className="text-right">{tx('On hand')}</TableHead>
                  <TableHead className="text-right">{tx('Shortage')}</TableHead>
                  <TableHead>{tx('Substitutes')}</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {availability.map((a: any) => (
                  <TableRow key={a.lineId}>
                    <TableCell>{a.productName}</TableCell>
                    <TableCell className="text-right">{Number(a.required)}</TableCell>
                    <TableCell className="text-right">{Number(a.onHand)}</TableCell>
                    <TableCell className={`text-right ${Number(a.shortage) > 0 ? 'text-destructive' : ''}`}>{Number(a.shortage)}</TableCell>
                    <TableCell className="text-xs">
                      {(a.substitutes ?? []).map((s: any) => (
                        <Badge key={s.productId} variant={s.covers ? 'default' : 'outline'} className="mr-1">
                          {s.name} ×{Number(s.ratio)} ({Number(s.onHand)})
                        </Badge>
                      ))}
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </CardContent>
        </Card>
      )}

      {(canSchedule || canRework) && (
        <Card>
          <CardHeader><CardTitle>{tx('Shop floor')}</CardTitle></CardHeader>
          <CardContent className="flex flex-wrap items-center gap-2">
            {canSchedule && (
              <>
                <Input className="w-56" type="datetime-local" value={startAt} onChange={(e) => setStartAt(e.target.value)} />
                <Button variant="outline" disabled={schedule.isPending} onClick={async () => {
                  try {
                    await schedule.mutateAsync({ orderId: order.id, startAt: startAt ? new Date(startAt).toISOString() : undefined });
                    notify.success(tx('Work orders scheduled.'));
                  } catch (e: any) {
                    notify.error(tx('Failed'), errMsg(e));
                  }
                }}>
                  <CalendarClock className="mr-2 h-4 w-4" /> {tx('Schedule work orders')}
                </Button>
              </>
            )}
            {canRework && (
              <Button variant="outline" disabled={rework.isPending} onClick={async () => {
                try {
                  const r = await rework.mutateAsync({ orderId: order.id });
                  notify.success(tx('Rework order {{code}} created.', { code: r.orderCode }));
                } catch (e: any) {
                  notify.error(tx('Failed'), errMsg(e));
                }
              }}>
                <RotateCcw className="mr-2 h-4 w-4" /> {tx('Create rework order')}
              </Button>
            )}
          </CardContent>
        </Card>
      )}

      {!!variances?.rows?.length && (
        <Card>
          <CardHeader><CardTitle>{tx('Variances vs standard')}</CardTitle></CardHeader>
          <CardContent>
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>{tx('Kind')}</TableHead>
                  <TableHead className="text-right">{tx('Standard')}</TableHead>
                  <TableHead className="text-right">{tx('Actual')}</TableHead>
                  <TableHead className="text-right">{tx('Variance')}</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {variances.rows.map((v: any) => (
                  <TableRow key={v.id}>
                    <TableCell>{v.kind}</TableCell>
                    <TableCell className="text-right">{Number(v.standardCost).toLocaleString()}</TableCell>
                    <TableCell className="text-right">{Number(v.actualCost).toLocaleString()}</TableCell>
                    <TableCell className={`text-right ${Number(v.amount) > 0 ? 'text-destructive' : ''}`}>{Number(v.amount).toLocaleString()}</TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </CardContent>
        </Card>
      )}
    </>
  );
}

import { useState } from 'react';
import { CalendarRange, Trash2 } from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Input } from '@/components/ui/input';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { DateRangeFilter, FilterSelect, ListPageHeader, ListToolbar } from '@/components/list';
import { useAuthStore } from '@/stores/auth.store';
import { date } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';
import { apiMessage, notify } from '@/lib/notify';
import { useWorkCenters } from '@/features/manufacturing/api';
import { useShifts } from '@/features/manufacturing/api-shopfloor';
import {
  pct, qty, useCreateShiftPlan, useCreateTarget, useDeleteShiftPlan, useDeleteTarget, usePublishShiftPlan, useShiftPlans,
  useTargets,
} from '@/features/manufacturing/api-planning';
import { addDays, ymd } from './planning-ui';

/** Shift plans (draft, publish, actual vs target) and period production targets (ADR-S019). */
export function ShiftPlansPage() {
  const today = new Date();
  const [from, setFrom] = useState(ymd(today));
  const [to, setTo] = useState(ymd(addDays(today, 6)));
  const [workCenterId, setWorkCenterId] = useState('');
  const [shiftId, setShiftId] = useState('');
  const [shiftDate, setShiftDate] = useState(ymd(today));
  const [target, setTarget] = useState('');
  const [periodTarget, setPeriodTarget] = useState('');
  const can = useAuthStore((s) => s.hasPermission);
  const centres = useWorkCenters();
  const shifts = useShifts();
  const plans = useShiftPlans({ from, to, workCenterId: workCenterId || undefined });
  const targets = useTargets({ from, to });
  const create = useCreateShiftPlan();
  const publish = usePublishShiftPlan();
  const remove = useDeleteShiftPlan();
  const createTarget = useCreateTarget();
  const removeTarget = useDeleteTarget();
  const canCreate = can(PERMISSIONS.productionPlan.create);
  const canUpdate = can(PERMISSIONS.productionPlan.update);
  const canPublish = can(PERMISSIONS.productionPlan.confirm);
  const fail = (e: unknown) => notify.error(apiMessage(e, tx('Could not save')));
  const centreOptions = (centres.data ?? []).map((c: { id: string; name: string }) => ({ value: c.id, label: c.name }));

  const addPlan = () => create.mutate({ shiftDate, shiftId, workCenterId, targetQty: Number(target) }, {
    onSuccess: () => setTarget(''), onError: fail,
  });
  const addTarget = () => createTarget.mutate({
    scope: 'work_center', workCenterId, periodFrom: from, periodTo: to, targetQty: Number(periodTarget),
  }, { onSuccess: () => setPeriodTarget(''), onError: fail });

  return (
    <div className="space-y-4">
      <ListPageHeader icon={CalendarRange} title={tx('Shift Plans')}
        description={tx('What each work centre should make per shift, and the production target of the period.')} />
      <ListToolbar>
        <DateRangeFilter from={from} to={to} onChange={(f, t) => { if (f && t) { setFrom(f); setTo(t); } }} />
        <FilterSelect value={workCenterId} onChange={setWorkCenterId} allLabel={tx('All work centres')} options={centreOptions} />
      </ListToolbar>
      {canCreate && (
        <Card>
          <CardContent className="flex flex-wrap items-center gap-2 pt-6">
            <Input type="date" className="w-40" value={shiftDate} onChange={(e) => setShiftDate(e.target.value)} />
            <FilterSelect value={shiftId} onChange={setShiftId} allLabel={tx('Shift')}
              options={(shifts.data ?? []).map((s) => ({ value: s.id, label: s.name }))} />
            <Input type="number" min="0" className="w-32" placeholder={tx('Target qty')} value={target} onChange={(e) => setTarget(e.target.value)} />
            <Button disabled={!workCenterId || !shiftId || !(Number(target) > 0) || create.isPending} onClick={addPlan}>{tx('Add shift plan')}</Button>
            <Input type="number" min="0" className="w-32" placeholder={tx('Period target')} value={periodTarget} onChange={(e) => setPeriodTarget(e.target.value)} />
            <Button variant="outline" disabled={!workCenterId || !(Number(periodTarget) > 0) || createTarget.isPending} onClick={addTarget}>{tx('Add target')}</Button>
          </CardContent>
        </Card>
      )}
      <Card>
        <CardHeader><CardTitle>{tx('Shift plans')}</CardTitle></CardHeader>
        <CardContent className="overflow-x-auto">
          {plans.isLoading && <p className="text-muted-foreground">{tx('Loading…')}</p>}
          {plans.isError && <p className="text-destructive">{tx('Could not load shift plans.')}</p>}
          {plans.data?.data.length === 0 && <p className="text-sm text-muted-foreground">{tx('No shift plans in this period.')}</p>}
          {plans.data && plans.data.data.length > 0 && (
            <Table>
              <TableHeader><TableRow>
                <TableHead>{tx('Date')}</TableHead><TableHead>{tx('Shift')}</TableHead><TableHead>{tx('Work centre')}</TableHead>
                <TableHead>{tx('Product')}</TableHead><TableHead className="text-right">{tx('Target')}</TableHead>
                <TableHead className="text-right">{tx('Actual')}</TableHead><TableHead className="text-right">{tx('Attainment')}</TableHead><TableHead />
              </TableRow></TableHeader>
              <TableBody>
                {plans.data.data.map((p) => (
                  <TableRow key={p.id}>
                    <TableCell>{date(p.shiftDate)}</TableCell><TableCell>{p.shiftName}</TableCell><TableCell>{p.workCenterName}</TableCell>
                    <TableCell>{p.productName ?? p.orderCode ?? '-'}</TableCell>
                    <TableCell className="text-right tabular-nums">{qty(p.targetQty)}</TableCell>
                    <TableCell className="text-right tabular-nums">{qty(p.actualQty)}</TableCell>
                    <TableCell className="text-right">{pct(p.attainmentPct)}</TableCell>
                    <TableCell className="space-x-1 whitespace-nowrap text-right">
                      {p.status === 'draft' && canPublish && <Button size="sm" variant="outline" onClick={() => publish.mutate(p.id, { onError: fail })}>{tx('Publish')}</Button>}
                      {p.status === 'draft' && canUpdate && (
                        <Button size="icon" variant="ghost" aria-label={tx('Delete')} onClick={() => remove.mutate(p.id, { onError: fail })}><Trash2 className="h-4 w-4" /></Button>
                      )}
                      {p.status === 'published' && <span className="text-xs text-muted-foreground">{tx('Published')}</span>}
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          )}
        </CardContent>
      </Card>
      <Card>
        <CardHeader><CardTitle>{tx('Production targets')}</CardTitle></CardHeader>
        <CardContent className="overflow-x-auto">
          {targets.data?.length === 0 && <p className="text-sm text-muted-foreground">{tx('No targets in this period.')}</p>}
          {targets.data && targets.data.length > 0 && (
            <Table>
              <TableHeader><TableRow>
                <TableHead>{tx('Applies to')}</TableHead><TableHead>{tx('From')}</TableHead><TableHead>{tx('To')}</TableHead>
                <TableHead className="text-right">{tx('Target')}</TableHead><TableHead />
              </TableRow></TableHeader>
              <TableBody>
                {targets.data.map((t) => (
                  <TableRow key={t.id}>
                    <TableCell>{t.subjectName}</TableCell><TableCell>{date(t.periodFrom)}</TableCell><TableCell>{date(t.periodTo)}</TableCell>
                    <TableCell className="text-right tabular-nums">{qty(t.targetQty)}</TableCell>
                    <TableCell className="text-right">
                      {canUpdate && <Button size="icon" variant="ghost" aria-label={tx('Delete')} onClick={() => removeTarget.mutate(t.id, { onError: fail })}><Trash2 className="h-4 w-4" /></Button>}
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          )}
        </CardContent>
      </Card>
    </div>
  );
}

import { useState } from 'react';
import { Link } from 'react-router-dom';
import { CalendarClock, ListOrdered, Wand2 } from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Input } from '@/components/ui/input';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs';
import { DateRangeFilter, FilterSelect, ListPageHeader, ListToolbar } from '@/components/list';
import { useAuthStore } from '@/stores/auth.store';
import { date, dateTime } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';
import { apiMessage, notify } from '@/lib/notify';
import { useLocationOptions } from '@/features/manufacturing/options';
import {
  qty, useDemand, useDemandForecast, usePlanBoard, usePlanFromDemand, useSaveOrderPlanning, useScheduleBackward, useScheduleOpen,
} from '@/features/manufacturing/api-planning';
import { addDays, demandTypeLabel, PriorityBadge, priorityOptions, ymd } from './planning-ui';

/** Demand, shortfall and plan generation, then order priorities and backward scheduling (ADR-S019, 2.5). */
export function PlanningWorkbenchPage() {
  return (
    <div className="space-y-4">
      <ListPageHeader icon={ListOrdered} title={tx('Planning Workbench')}
        description={tx('Demand from the delivery schedule, sales orders, forecast and stock policy, netted against stock and open production.')} />
      <Tabs defaultValue="demand">
        <TabsList>
          <TabsTrigger value="demand">{tx('Demand')}</TabsTrigger>
          <TabsTrigger value="forecast">{tx('Forecast')}</TabsTrigger>
          <TabsTrigger value="orders">{tx('Order priorities')}</TabsTrigger>
        </TabsList>
        <TabsContent value="demand"><DemandTab /></TabsContent>
        <TabsContent value="forecast"><ForecastTab /></TabsContent>
        <TabsContent value="orders"><OrdersTab /></TabsContent>
      </Tabs>
    </div>
  );
}

function DemandTab() {
  const today = new Date();
  const [from, setFrom] = useState(ymd(today));
  const [to, setTo] = useState(ymd(addDays(today, 30)));
  const [forecast, setForecast] = useState(false);
  const [smoothed, setSmoothed] = useState(false);
  const [locationId, setLocationId] = useState('');
  const can = useAuthStore((s) => s.hasPermission);
  const locations = useLocationOptions();
  const forecastMethod = smoothed ? 'smoothed' : 'run_rate';
  const { data, isLoading, isError } = useDemand({ from, to, includeForecast: forecast, forecastMethod });
  const generate = usePlanFromDemand();
  const onGenerate = () => generate.mutate({ dateTo: to, locationId, includeForecast: forecast, forecastMethod }, {
    onSuccess: () => notify.success(tx('Draft plan created')),
    onError: (e) => notify.error(apiMessage(e, tx('Could not create the plan'))),
  });
  return (
    <div className="space-y-4">
      <ListToolbar>
        <DateRangeFilter from={from} to={to} onChange={(f, t) => { setFrom(f); setTo(t); }} />
        <label className="flex items-center gap-2 text-sm">
          <input type="checkbox" checked={forecast} onChange={(e) => setForecast(e.target.checked)} />
          {tx('Include forecast')}
        </label>
        {forecast && (
          <label className="flex items-center gap-2 text-sm">
            <input type="checkbox" checked={smoothed} onChange={(e) => setSmoothed(e.target.checked)} />
            {tx('Smoothed, with aggregates and blocks')}
          </label>
        )}
        <FilterSelect value={locationId} onChange={setLocationId} allLabel={tx('Production location')}
          options={(locations.data ?? []).map((l: { id: string; name: string }) => ({ value: l.id, label: l.name }))} />
        {can(PERMISSIONS.productionPlan.create) && (
          <Button onClick={onGenerate} disabled={!locationId || generate.isPending}>
            <Wand2 className="mr-2 h-4 w-4" />{tx('Create plan from shortfall')}
          </Button>
        )}
      </ListToolbar>
      {isLoading && <p className="text-muted-foreground">{tx('Loading…')}</p>}
      {isError && <p className="text-destructive">{tx('Could not load the demand.')}</p>}
      {data && (
        <>
          <Card>
            <CardHeader><CardTitle>{tx('By product')}</CardTitle></CardHeader>
            <CardContent className="overflow-x-auto">
              {data.products.length === 0 ? <p className="text-sm text-muted-foreground">{tx('No demand in this period.')}</p> : (
                <Table>
                  <TableHeader><TableRow>
                    <TableHead>{tx('Product')}</TableHead><TableHead className="text-right">{tx('Demand')}</TableHead>
                    <TableHead className="text-right">{tx('On hand')}</TableHead><TableHead className="text-right">{tx('Supply')}</TableHead>
                    <TableHead className="text-right">{tx('Shortfall')}</TableHead>
                  </TableRow></TableHeader>
                  <TableBody>
                    {data.products.map((p) => (
                      <TableRow key={p.productId}>
                        <TableCell>{p.productName}{!p.makeable && <span className="ml-2 text-xs text-muted-foreground">{tx('No active BOM')}</span>}</TableCell>
                        <TableCell className="text-right tabular-nums">{qty(p.demandQty)}</TableCell>
                        <TableCell className="text-right tabular-nums">{qty(p.onHandQty)}</TableCell>
                        <TableCell className="text-right tabular-nums">{qty(p.supplyQty)}</TableCell>
                        <TableCell className={`text-right tabular-nums ${Number(p.shortfallQty) > 0 ? 'font-semibold text-destructive' : ''}`}>{qty(p.shortfallQty)}</TableCell>
                      </TableRow>
                    ))}
                  </TableBody>
                </Table>
              )}
            </CardContent>
          </Card>
          <Card>
            <CardHeader><CardTitle>{tx('Requirements in planning order')}</CardTitle></CardHeader>
            <CardContent className="overflow-x-auto">
              <Table>
                <TableHeader><TableRow>
                  <TableHead>{tx('Source')}</TableHead><TableHead>{tx('Reference')}</TableHead><TableHead>{tx('Product')}</TableHead>
                  <TableHead>{tx('Customer')}</TableHead><TableHead>{tx('Due')}</TableHead><TableHead>{tx('Priority')}</TableHead>
                  <TableHead className="text-right">{tx('Quantity')}</TableHead><TableHead className="text-right">{tx('Uncovered')}</TableHead>
                </TableRow></TableHeader>
                <TableBody>
                  {data.rows.map((r, i) => (
                    <TableRow key={`${r.demandType}-${r.demandId}-${r.productId}-${i}`}>
                      <TableCell>{demandTypeLabel(r.demandType)}</TableCell><TableCell>{r.ref}</TableCell>
                      <TableCell>{r.productName}</TableCell>
                      <TableCell>{r.partnerName}{r.siteName && <div className="text-xs text-muted-foreground">{r.siteName}</div>}</TableCell>
                      <TableCell>{date(r.dueAt)}</TableCell><TableCell><PriorityBadge priority={r.priority} /></TableCell>
                      <TableCell className="text-right tabular-nums">{qty(r.quantity)}</TableCell>
                      <TableCell className="text-right tabular-nums">{qty(r.uncoveredQty)}</TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            </CardContent>
          </Card>
        </>
      )}
    </div>
  );
}

const familyLabel = (family: string | null) =>
  (family === 'aggregate' ? tx('Aggregates') : family === 'block' ? tx('Blocks') : '-');
const confidenceLabel = (c: string) => (c === 'high' ? tx('High') : c === 'medium' ? tx('Medium') : tx('Low'));

/** Smoothed weekly sales per product and what they imply for the horizon. Plans nothing by itself. */
function ForecastTab() {
  const today = new Date();
  const [from, setFrom] = useState(ymd(today));
  const [to, setTo] = useState(ymd(addDays(today, 30)));
  const [family, setFamily] = useState('');
  const { data, isLoading, isError } = useDemandForecast({ from, to, family: family || undefined });
  return (
    <div className="space-y-4">
      <ListToolbar>
        <DateRangeFilter from={from} to={to} onChange={(f, t) => { setFrom(f); setTo(t); }} />
        <FilterSelect value={family} onChange={setFamily} allLabel={tx('All products')}
          options={[{ value: 'aggregate', label: tx('Aggregates') }, { value: 'block', label: tx('Blocks') }]} />
      </ListToolbar>
      {isLoading && <p className="text-muted-foreground">{tx('Loading…')}</p>}
      {isError && <p className="text-destructive">{tx('Could not load the forecast.')}</p>}
      {data && (
        <Card>
          <CardHeader><CardTitle>{tx('Forecast from the last {{weeks}} weeks of sales', { weeks: data.weeks })}</CardTitle></CardHeader>
          <CardContent className="overflow-x-auto">
            {data.products.length === 0 ? <p className="text-sm text-muted-foreground">{tx('Not enough sales history yet.')}</p> : (
              <Table>
                <TableHeader><TableRow>
                  <TableHead>{tx('Product')}</TableHead><TableHead>{tx('Family')}</TableHead>
                  <TableHead className="text-right">{tx('Next week')}</TableHead><TableHead className="text-right">{tx('Trend per week')}</TableHead>
                  <TableHead className="text-right">{tx('Period forecast')}</TableHead><TableHead>{tx('Confidence')}</TableHead>
                </TableRow></TableHeader>
                <TableBody>
                  {data.products.map((p) => (
                    <TableRow key={p.productId}>
                      <TableCell>{p.name}</TableCell><TableCell>{familyLabel(p.family)}</TableCell>
                      <TableCell className="text-right tabular-nums">{qty(p.nextWeekQty)}</TableCell>
                      <TableCell className="text-right tabular-nums">{qty(p.trendPerWeek)}</TableCell>
                      <TableCell className="text-right font-semibold tabular-nums">{qty(p.horizonQty)}</TableCell>
                      <TableCell>{confidenceLabel(p.confidence)}</TableCell>
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

function OrdersTab() {
  const { data, isLoading, isError } = usePlanBoard();
  const save = useSaveOrderPlanning();
  const backward = useScheduleBackward();
  const all = useScheduleOpen();
  const can = useAuthStore((s) => s.hasPermission);
  const canSchedule = can(PERMISSIONS.workOrder.manage);
  const canEdit = can(PERMISSIONS.productionPlan.update);
  const fail = (e: unknown) => notify.error(apiMessage(e, tx('Could not schedule the order')));
  return (
    <Card>
      <CardHeader className="flex flex-row items-center justify-between">
        <CardTitle>{tx('Open orders by priority')}</CardTitle>
        {canSchedule && (
          <Button variant="outline" disabled={all.isPending} onClick={() => all.mutate(undefined, {
            onSuccess: () => notify.success(tx('Open orders scheduled')), onError: fail,
          })}><CalendarClock className="mr-2 h-4 w-4" />{tx('Schedule all from due dates')}</Button>
        )}
      </CardHeader>
      <CardContent className="overflow-x-auto">
        {isLoading && <p className="text-muted-foreground">{tx('Loading…')}</p>}
        {isError && <p className="text-destructive">{tx('Could not load the orders.')}</p>}
        {data?.length === 0 && <p className="text-sm text-muted-foreground">{tx('No open orders.')}</p>}
        {data && data.length > 0 && (
          <Table>
            <TableHeader><TableRow>
              <TableHead>{tx('Order')}</TableHead><TableHead>{tx('Product')}</TableHead><TableHead>{tx('Customer')}</TableHead>
              <TableHead>{tx('Priority')}</TableHead><TableHead>{tx('Due')}</TableHead><TableHead>{tx('Planned start')}</TableHead>
              <TableHead>{tx('Planned end')}</TableHead><TableHead className="text-right">{tx('Late')}</TableHead><TableHead />
            </TableRow></TableHeader>
            <TableBody>
              {data.map((o) => (
                <TableRow key={o.id}>
                  <TableCell><Link className="underline" to={`/manufacturing/orders/${o.id}`}>{o.orderCode}</Link></TableCell>
                  <TableCell>{o.productName}</TableCell><TableCell>{o.partnerName}</TableCell>
                  <TableCell>
                    {canEdit ? (
                      <FilterSelect value={String(o.priority)} allLabel={tx('Priority')} options={priorityOptions()}
                        onChange={(v) => v && save.mutate({ id: o.id, priority: Number(v) }, {
                          onError: (e) => notify.error(apiMessage(e, tx('Could not save'))),
                        })} />
                    ) : <PriorityBadge priority={o.priority} />}
                  </TableCell>
                  <TableCell>
                    {canEdit ? (
                      <Input type="date" className="w-40" defaultValue={o.dueAt ? o.dueAt.slice(0, 10) : ''}
                        onBlur={(e) => e.target.value && e.target.value !== o.dueAt?.slice(0, 10) && save.mutate({ id: o.id, dueAt: e.target.value }, {
                          onError: (err) => notify.error(apiMessage(err, tx('Could not save'))),
                        })} />
                    ) : date(o.dueAt)}
                  </TableCell>
                  <TableCell>{o.plannedStartAt ? dateTime(o.plannedStartAt) : '-'}</TableCell>
                  <TableCell>{o.plannedEndAt ? dateTime(o.plannedEndAt) : '-'}</TableCell>
                  <TableCell className={`text-right ${o.lateMinutes > 0 ? 'text-destructive' : ''}`}>{o.lateMinutes > 0 ? `${Math.ceil(o.lateMinutes / 60)} h` : '-'}</TableCell>
                  <TableCell>
                    {canSchedule && (
                      <Button size="sm" variant="outline" disabled={!o.dueAt || backward.isPending}
                        onClick={() => backward.mutate(o.id, { onSuccess: () => notify.success(tx('Order scheduled')), onError: fail })}>
                        {tx('Schedule')}
                      </Button>
                    )}
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        )}
      </CardContent>
    </Card>
  );
}

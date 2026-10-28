import { useEffect, useMemo, useState } from 'react';
import { Plus, Save, Trash2 } from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Badge } from '@/components/ui/badge';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs';
import { useAuthStore } from '@/stores/auth.store';
import { notify } from '@/lib/notify';
import { tx } from '@/lib/i18n/tx';
import { useWorkCenters } from '@/features/manufacturing/api';
import { useProductOptions } from '@/features/manufacturing/options';
import {
  useCalendar,
  useQcPlans,
  useSaveCalendar,
  useSchedule,
  useScrapReasons,
} from '@/features/manufacturing/api-advanced';

const DAYS = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'];
const toHm = (m: number) => `${String(Math.floor(m / 60)).padStart(2, '0')}:${String(m % 60).padStart(2, '0')}`;
const fromHm = (s: string) => {
  const [h, m] = s.split(':').map(Number);
  return (h || 0) * 60 + (m || 0);
};

const errMsg = (e: any) => {
  const m = e?.response?.data?.message ?? e?.message;
  return Array.isArray(m) ? m.join(', ') : m;
};

/** Shop floor: finite schedule, work-centre calendars, scrap reasons and QC inspection plans. */
export function ShopFloorPage() {
  const hasPermission = useAuthStore((s) => s.hasPermission);
  const canCalendar = hasPermission(PERMISSIONS.workCenter.update);
  const canQuality = hasPermission(PERMISSIONS.productionOrder.qc);

  return (
    <div className="space-y-6 p-6">
      <div>
        <h1 className="text-2xl font-semibold">{tx('Shop floor')}</h1>
        <p className="text-sm text-muted-foreground">{tx('Finite-capacity schedule, work-centre calendars and quality masters.')}</p>
      </div>
      <Tabs defaultValue="schedule">
        <TabsList>
          <TabsTrigger value="schedule">{tx('Schedule')}</TabsTrigger>
          <TabsTrigger value="calendars">{tx('Calendars')}</TabsTrigger>
          <TabsTrigger value="scrap">{tx('Scrap reasons')}</TabsTrigger>
          <TabsTrigger value="qc">{tx('QC plans')}</TabsTrigger>
        </TabsList>
        <TabsContent value="schedule"><ScheduleView /></TabsContent>
        <TabsContent value="calendars"><CalendarEditor canEdit={canCalendar} /></TabsContent>
        <TabsContent value="scrap"><ScrapReasons canEdit={canQuality} /></TabsContent>
        <TabsContent value="qc"><QcPlans canEdit={canQuality} /></TabsContent>
      </Tabs>
    </div>
  );
}

function ScheduleView() {
  const { data: centers } = useWorkCenters();
  const [workCenterId, setWorkCenterId] = useState('');
  const [days, setDays] = useState('14');
  const from = useMemo(() => new Date(Date.now() - 86_400_000).toISOString(), []);
  const to = useMemo(() => new Date(Date.now() + (Number(days) || 14) * 86_400_000).toISOString(), [days]);
  const { data: rows } = useSchedule({ from, to, ...(workCenterId ? { workCenterId } : {}) });
  const span = new Date(to).getTime() - new Date(from).getTime();

  return (
    <Card>
      <CardHeader className="flex flex-row flex-wrap items-end justify-between gap-3">
        <CardTitle>{tx('Scheduled operations')}</CardTitle>
        <div className="flex items-end gap-2">
          <Select value={workCenterId || 'all'} onValueChange={(v) => setWorkCenterId(v === 'all' ? '' : v)}>
            <SelectTrigger className="w-48"><SelectValue /></SelectTrigger>
            <SelectContent>
              <SelectItem value="all">{tx('All work centres')}</SelectItem>
              {(centers ?? []).map((c: any) => <SelectItem key={c.id} value={c.id}>{c.name}</SelectItem>)}
            </SelectContent>
          </Select>
          <Input className="w-20" type="number" min="1" value={days} onChange={(e) => setDays(e.target.value)} />
          <span className="pb-2 text-sm text-muted-foreground">{tx('days')}</span>
        </div>
      </CardHeader>
      <CardContent>
        {!rows?.length ? (
          <p className="text-sm text-muted-foreground">
            {tx('Nothing scheduled. Generate work orders on a production order, then use Schedule.')}
          </p>
        ) : (
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>{tx('Order')}</TableHead>
                <TableHead>{tx('Operation')}</TableHead>
                <TableHead>{tx('Work centre')}</TableHead>
                <TableHead>{tx('Start')}</TableHead>
                <TableHead>{tx('End')}</TableHead>
                <TableHead className="w-1/3">{tx('Timeline')}</TableHead>
                <TableHead>{tx('Status')}</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {rows.map((r: any) => {
                const s = Math.max(0, new Date(r.plannedStartAt).getTime() - new Date(from).getTime());
                const e = Math.min(span, new Date(r.plannedEndAt).getTime() - new Date(from).getTime());
                return (
                  <TableRow key={r.id}>
                    <TableCell><a className="font-mono text-xs underline" href={`/manufacturing/orders/${r.productionOrderId}`}>{r.orderCode}</a></TableCell>
                    <TableCell>{r.name}</TableCell>
                    <TableCell>{r.workCenterName ?? '—'}</TableCell>
                    <TableCell className="text-xs">{new Date(r.plannedStartAt).toLocaleString()}</TableCell>
                    <TableCell className="text-xs">{new Date(r.plannedEndAt).toLocaleString()}</TableCell>
                    <TableCell>
                      <div className="relative h-3 rounded bg-muted">
                        <div className="absolute h-3 rounded bg-primary" style={{ left: `${(s / span) * 100}%`, width: `${Math.max(0.5, ((e - s) / span) * 100)}%` }} />
                      </div>
                    </TableCell>
                    <TableCell><Badge variant="outline">{r.status}</Badge></TableCell>
                  </TableRow>
                );
              })}
            </TableBody>
          </Table>
        )}
      </CardContent>
    </Card>
  );
}

interface ShiftRow { weekday: number; start: string; end: string }

function CalendarEditor({ canEdit }: { canEdit: boolean }) {
  const { data: centers } = useWorkCenters();
  const [wcId, setWcId] = useState('');
  const { data: cal } = useCalendar(wcId || undefined);
  const save = useSaveCalendar();
  const [shifts, setShifts] = useState<ShiftRow[]>([]);
  const [closures, setClosures] = useState<Array<{ date: string; reason: string }>>([]);

  useEffect(() => {
    if (!cal) return;
    setShifts((cal.shifts ?? []).map((s: any) => ({ weekday: Number(s.weekday), start: toHm(s.startMinute), end: toHm(s.endMinute) })));
    setClosures((cal.closures ?? []).filter((c: any) => c.workCenterId).map((c: any) => ({ date: String(c.date).slice(0, 10), reason: c.reason ?? '' })));
  }, [cal]);

  const submit = async () => {
    try {
      await save.mutateAsync({
        workCenterId: wcId,
        body: {
          shifts: shifts.map((s) => ({ weekday: s.weekday, startMinute: fromHm(s.start), endMinute: fromHm(s.end) || 1440 })),
          closures: closures.filter((c) => c.date).map((c) => ({ date: c.date, reason: c.reason || undefined })),
        },
      });
      notify.success(tx('Calendar saved.'));
    } catch (e: any) {
      notify.error(tx('Failed'), errMsg(e));
    }
  };

  return (
    <Card>
      <CardHeader>
        <CardTitle>{tx('Work-centre calendar')}</CardTitle>
        <p className="text-sm text-muted-foreground">{tx('No shifts = available around the clock. Times are UTC.')}</p>
      </CardHeader>
      <CardContent className="space-y-4">
        <Select value={wcId} onValueChange={setWcId}>
          <SelectTrigger className="w-64"><SelectValue placeholder={tx('Select a work centre…')} /></SelectTrigger>
          <SelectContent>
            {(centers ?? []).map((c: any) => <SelectItem key={c.id} value={c.id}>{c.code} · {c.name}</SelectItem>)}
          </SelectContent>
        </Select>
        {wcId && (
          <>
            <div className="space-y-2">
              <div className="flex items-center justify-between">
                <Label>{tx('Weekly shifts')}</Label>
                {canEdit && (
                  <Button size="sm" variant="outline" onClick={() => setShifts((s) => [...s, { weekday: 1, start: '08:00', end: '17:00' }])}>
                    <Plus className="mr-1 h-3 w-3" /> {tx('Add shift')}
                  </Button>
                )}
              </div>
              {shifts.map((s, i) => (
                <div key={i} className="flex items-center gap-2">
                  <Select value={String(s.weekday)} onValueChange={(v) => setShifts((ss) => ss.map((x, j) => (j === i ? { ...x, weekday: Number(v) } : x)))}>
                    <SelectTrigger className="w-28"><SelectValue /></SelectTrigger>
                    <SelectContent>{DAYS.map((d, k) => <SelectItem key={d} value={String(k)}>{tx(d)}</SelectItem>)}</SelectContent>
                  </Select>
                  <Input className="w-28" type="time" value={s.start} onChange={(e) => setShifts((ss) => ss.map((x, j) => (j === i ? { ...x, start: e.target.value } : x)))} />
                  <Input className="w-28" type="time" value={s.end} onChange={(e) => setShifts((ss) => ss.map((x, j) => (j === i ? { ...x, end: e.target.value } : x)))} />
                  {canEdit && <Button size="icon" variant="ghost" onClick={() => setShifts((ss) => ss.filter((_, j) => j !== i))}><Trash2 className="h-4 w-4" /></Button>}
                </div>
              ))}
            </div>
            <div className="space-y-2">
              <div className="flex items-center justify-between">
                <Label>{tx('Closures')}</Label>
                {canEdit && (
                  <Button size="sm" variant="outline" onClick={() => setClosures((c) => [...c, { date: '', reason: '' }])}>
                    <Plus className="mr-1 h-3 w-3" /> {tx('Add closure')}
                  </Button>
                )}
              </div>
              {closures.map((c, i) => (
                <div key={i} className="flex items-center gap-2">
                  <Input className="w-40" type="date" value={c.date} onChange={(e) => setClosures((cs) => cs.map((x, j) => (j === i ? { ...x, date: e.target.value } : x)))} />
                  <Input className="w-64" placeholder={tx('Reason')} value={c.reason} onChange={(e) => setClosures((cs) => cs.map((x, j) => (j === i ? { ...x, reason: e.target.value } : x)))} />
                  {canEdit && <Button size="icon" variant="ghost" onClick={() => setClosures((cs) => cs.filter((_, j) => j !== i))}><Trash2 className="h-4 w-4" /></Button>}
                </div>
              ))}
            </div>
            {canEdit && <Button onClick={submit} disabled={save.isPending}><Save className="mr-2 h-4 w-4" /> {tx('Save calendar')}</Button>}
          </>
        )}
      </CardContent>
    </Card>
  );
}

function ScrapReasons({ canEdit }: { canEdit: boolean }) {
  const { list, save, remove } = useScrapReasons();
  const [code, setCode] = useState('');
  const [name, setName] = useState('');
  const add = async () => {
    try {
      await save.mutateAsync({ body: { code, name } });
      setCode('');
      setName('');
    } catch (e: any) {
      notify.error(tx('Failed'), errMsg(e));
    }
  };
  return (
    <Card>
      <CardHeader>
        <CardTitle>{tx('Scrap reasons')}</CardTitle>
        <p className="text-sm text-muted-foreground">{tx('Once codes exist, QC failures and work-order scrap must use one of them.')}</p>
      </CardHeader>
      <CardContent className="space-y-4">
        {canEdit && (
          <div className="flex items-end gap-2">
            <div className="space-y-1"><Label>{tx('Code')}</Label><Input value={code} onChange={(e) => setCode(e.target.value)} /></div>
            <div className="space-y-1"><Label>{tx('Name')}</Label><Input value={name} onChange={(e) => setName(e.target.value)} /></div>
            <Button onClick={add} disabled={!code || !name}><Plus className="mr-1 h-4 w-4" /> {tx('Add')}</Button>
          </div>
        )}
        <Table>
          <TableHeader><TableRow><TableHead>{tx('Code')}</TableHead><TableHead>{tx('Name')}</TableHead><TableHead>{tx('Active')}</TableHead><TableHead /></TableRow></TableHeader>
          <TableBody>
            {(list.data ?? []).map((r: any) => (
              <TableRow key={r.id}>
                <TableCell className="font-mono text-xs">{r.code}</TableCell>
                <TableCell>{r.name}</TableCell>
                <TableCell>
                  <input type="checkbox" disabled={!canEdit} checked={!!r.isActive} onChange={(e) => save.mutate({ id: r.id, body: { isActive: e.target.checked } })} />
                </TableCell>
                <TableCell className="text-right">
                  {canEdit && <Button size="icon" variant="ghost" onClick={() => remove.mutate(r.id)}><Trash2 className="h-4 w-4" /></Button>}
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </CardContent>
    </Card>
  );
}

interface QcItem { key: string; label: string; type: 'boolean' | 'number' | 'text'; required: boolean; min?: string; max?: string }

function QcPlans({ canEdit }: { canEdit: boolean }) {
  const { list, save, remove } = useQcPlans();
  const { data: products } = useProductOptions();
  const productName = (id?: string) => (products ?? []).find((p) => p.id === id)?.name ?? '—';
  const [name, setName] = useState('');
  const [productId, setProductId] = useState('');
  const [items, setItems] = useState<QcItem[]>([]);

  const add = async () => {
    try {
      await save.mutateAsync({
        body: {
          name,
          productId: productId || undefined,
          items: items.filter((i) => i.label).map((i, k) => ({
            key: i.key || `item${k + 1}`, label: i.label, type: i.type, required: i.required,
            ...(i.type === 'number' && i.min !== undefined && i.min !== '' ? { min: Number(i.min) } : {}),
            ...(i.type === 'number' && i.max !== undefined && i.max !== '' ? { max: Number(i.max) } : {}),
          })),
        },
      });
      setName('');
      setProductId('');
      setItems([]);
      notify.success(tx('QC plan saved.'));
    } catch (e: any) {
      notify.error(tx('Failed'), errMsg(e));
    }
  };

  return (
    <Card>
      <CardHeader>
        <CardTitle>{tx('QC inspection plans')}</CardTitle>
        <p className="text-sm text-muted-foreground">{tx('Recording QC on an order checks the submitted checklist against its product plan.')}</p>
      </CardHeader>
      <CardContent className="space-y-4">
        {canEdit && (
          <div className="space-y-2 rounded border p-3">
            <div className="flex items-end gap-2">
              <div className="space-y-1"><Label>{tx('Name')}</Label><Input value={name} onChange={(e) => setName(e.target.value)} /></div>
              <div className="space-y-1">
                <Label>{tx('Product')}</Label>
                <Select value={productId} onValueChange={setProductId}>
                  <SelectTrigger className="w-64"><SelectValue placeholder={tx('Select…')} /></SelectTrigger>
                  <SelectContent>{(products ?? []).map((p) => <SelectItem key={p.id} value={p.id}>{p.code} · {p.name}</SelectItem>)}</SelectContent>
                </Select>
              </div>
              <Button variant="outline" onClick={() => setItems((i) => [...i, { key: '', label: '', type: 'boolean', required: true }])}>
                <Plus className="mr-1 h-4 w-4" /> {tx('Item')}
              </Button>
            </div>
            {items.map((it, i) => {
              const upd = (patch: Partial<QcItem>) => setItems((xs) => xs.map((x, j) => (j === i ? { ...x, ...patch } : x)));
              return (
                <div key={i} className="flex items-center gap-2">
                  <Input className="w-56" placeholder={tx('Check')} value={it.label} onChange={(e) => upd({ label: e.target.value })} />
                  <Select value={it.type} onValueChange={(v) => upd({ type: v as QcItem['type'] })}>
                    <SelectTrigger className="w-28"><SelectValue /></SelectTrigger>
                    <SelectContent>
                      <SelectItem value="boolean">{tx('Yes / no')}</SelectItem>
                      <SelectItem value="number">{tx('Number')}</SelectItem>
                      <SelectItem value="text">{tx('Text')}</SelectItem>
                    </SelectContent>
                  </Select>
                  {it.type === 'number' && (
                    <>
                      <Input className="w-20" placeholder={tx('Min')} value={it.min ?? ''} onChange={(e) => upd({ min: e.target.value })} />
                      <Input className="w-20" placeholder={tx('Max')} value={it.max ?? ''} onChange={(e) => upd({ max: e.target.value })} />
                    </>
                  )}
                  <label className="flex items-center gap-1 text-sm">
                    <input type="checkbox" checked={it.required} onChange={(e) => upd({ required: e.target.checked })} /> {tx('Required')}
                  </label>
                  <Button size="icon" variant="ghost" onClick={() => setItems((xs) => xs.filter((_, j) => j !== i))}><Trash2 className="h-4 w-4" /></Button>
                </div>
              );
            })}
            <Button onClick={add} disabled={!name || save.isPending}><Save className="mr-2 h-4 w-4" /> {tx('Save plan')}</Button>
          </div>
        )}
        <Table>
          <TableHeader><TableRow><TableHead>{tx('Plan')}</TableHead><TableHead>{tx('Product')}</TableHead><TableHead>{tx('Checks')}</TableHead><TableHead /></TableRow></TableHeader>
          <TableBody>
            {(list.data ?? []).map((p: any) => (
              <TableRow key={p.id}>
                <TableCell>{p.name}</TableCell>
                <TableCell>{productName(p.productId)}</TableCell>
                <TableCell className="text-xs text-muted-foreground">{(p.items ?? []).map((i: any) => i.label).join(', ')}</TableCell>
                <TableCell className="text-right">
                  {canEdit && <Button size="icon" variant="ghost" onClick={() => remove.mutate(p.id)}><Trash2 className="h-4 w-4" /></Button>}
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </CardContent>
    </Card>
  );
}

export default ShopFloorPage;

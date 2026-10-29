import { useState, type ReactNode } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { ArrowLeft, Check, Minus, Plus, RefreshCw, Trash2, Undo2 } from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Textarea } from '@/components/ui/textarea';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Skeleton } from '@/components/ui/skeleton';
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { DataTable, type Column } from '@/components/data-table';
import { StatusPill } from '@/components/list';
import { useAuthStore } from '@/stores/auth.store';
import { notify, apiMessage } from '@/lib/notify';
import { date, dateTime, money } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';
import { useFleetLookups, usePartnerPicker } from '@/features/fleet/api';
import {
  LINE_TYPE_LABEL, useAddTask, useCostLine, useDeleteTask, useEmployeePicker, useLineAction, usePartPicker,
  useUpdateTask, useWorkOrder, useWorkOrderAction, WO_TYPE_LABEL, type WorkOrder, type WorkOrderLine,
} from '@/features/fleet/maintenance-api';
import { Field, OptionalSelect, RemotePicker } from '../fleet-ui';
import { num } from '../fleet-format';
import { DefectStatusPill, PriorityPill, SeverityPill, toNumber, unitLabel, WorkOrderStatusPill } from './maint-ui';

type Prompt = { title: string; label: string; kind: 'reason' | 'quantity'; run: (value: string) => Promise<unknown> };

/** Work order detail (fleet 1.6): state actions, tasks, parts / labour / bills, cost summary, defects, downtime. */
export function WorkOrderDetailPage() {
  const { id = '' } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const has = useAuthStore((s) => s.hasPermission);
  const { data: w, isLoading, isError, refetch } = useWorkOrder(id);
  const action = useWorkOrderAction();
  const lineAction = useLineAction();
  const [dialog, setDialog] = useState<'part' | 'labour' | 'bill' | 'complete' | null>(null);
  const [prompt, setPrompt] = useState<Prompt | null>(null);

  if (isLoading) return <div className="space-y-3"><Skeleton className="h-12 w-1/2" /><Skeleton className="h-64 w-full" /></div>;
  if (isError || !w) {
    return (
      <div className="p-8 text-center text-muted-foreground">
        <p>{tx('Work order not found')}</p>
        <Button variant="link" onClick={() => refetch()}><RefreshCw className="mr-1 h-4 w-4" /> {tx('Retry')}</Button>
      </div>
    );
  }
  const allowed = w.allowed ?? [];
  const canUpdate = has(PERMISSIONS.fleetMaintenance.update);
  const canCost = has(PERMISSIONS.fleetMaintenance.complete);
  const partsOk = ['open', 'in_progress'].includes(w.status);
  const costsOk = partsOk || w.status === 'completed';

  const run = async (fn: () => Promise<unknown>, ok: string) => {
    try {
      await fn();
      notify.success(ok);
    } catch (err) {
      notify.error(apiMessage(err, tx('The action failed')));
    }
  };
  const act = (a: 'open' | 'start' | 'close') => run(() => action.mutateAsync({ id: w.id, action: a }), tx('Work order updated'));

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-start gap-3">
        <Button variant="ghost" size="icon" aria-label={tx('Back')} onClick={() => navigate('/fleet/maintenance/work-orders')}><ArrowLeft className="h-5 w-5" /></Button>
        <div className="min-w-0 flex-1">
          <div className="flex flex-wrap items-center gap-2">
            <h1 className="text-2xl font-bold">{w.code}</h1>
            <WorkOrderStatusPill status={w.status} />
            <PriorityPill priority={w.priority} />
          </div>
          <p className="text-sm">{w.title}</p>
          <p className="text-sm text-muted-foreground">
            <Link className="text-primary hover:underline" to={`/fleet/vehicles/${w.vehicleId}`}>{unitLabel(w.vehicle)}</Link>
            {' · '}{WO_TYPE_LABEL[w.type]}{w.schedule ? ` · ${w.schedule.name}` : ''}{w.dueOn ? ` · ${tx('Due')} ${date(w.dueOn)}` : ''}
          </p>
        </div>
        <div className="flex flex-wrap gap-2">
          {canUpdate && allowed.includes('open') && <Button onClick={() => act('open')}>{tx('Open')}</Button>}
          {canUpdate && allowed.includes('in_progress') && <Button onClick={() => act('start')}>{tx('Start work')}</Button>}
          {canCost && allowed.includes('completed') && <Button onClick={() => setDialog('complete')}>{tx('Complete')}</Button>}
          {canCost && allowed.includes('closed') && <Button variant="outline" onClick={() => act('close')}>{tx('Close')}</Button>}
          {canUpdate && allowed.includes('cancelled') && (
            <Button variant="outline" onClick={() => setPrompt({
              title: tx('Cancel work order'), label: tx('Reason *'), kind: 'reason',
              run: (reason) => action.mutateAsync({ id: w.id, action: 'cancel', body: { reason } }),
            })}>{tx('Cancel work order')}</Button>
          )}
        </div>
      </div>

      {w.description && <p className="whitespace-pre-wrap text-sm text-muted-foreground">{w.description}</p>}
      {w.cancelReason && <p className="text-sm text-rose-700">{tx('Cancelled')}: {w.cancelReason}</p>}
      {w.resolution && <p className="text-sm">{tx('Resolution')}: {w.resolution}</p>}

      <div className="grid grid-cols-2 gap-3 sm:grid-cols-4">
        <Tile label={tx('Parts')} value={money(w.partsCost)} />
        <Tile label={tx('Labour')} value={money(w.labourCost)} />
        <Tile label={tx('External bills')} value={money(w.externalCost)} />
        <Tile label={tx('Total cost')} value={money(w.totalCost)} strong />
      </div>
      {w.vehicle?.costCenterName && (
        <p className="text-xs text-muted-foreground">{tx('Costs post to cost centre {{cc}}.', { cc: `${w.vehicle.costCenterCode ?? ''} ${w.vehicle.costCenterName}`.trim() })}</p>
      )}

      <div className="grid grid-cols-1 gap-4 lg:grid-cols-3">
        <TasksCard w={w} editable={canUpdate && ['draft', 'open', 'in_progress'].includes(w.status)} />
        <Card className="lg:col-span-2">
          <CardHeader className="flex flex-row flex-wrap items-center justify-between gap-2 space-y-0">
            <CardTitle className="text-base">{tx('Cost lines')}</CardTitle>
            {canCost && (
              <div className="flex flex-wrap gap-2">
                <Button size="sm" variant="outline" disabled={!partsOk} onClick={() => setDialog('part')}><Plus className="mr-1 h-4 w-4" />{tx('Issue part')}</Button>
                <Button size="sm" variant="outline" disabled={!costsOk} onClick={() => setDialog('labour')}><Plus className="mr-1 h-4 w-4" />{tx('Book labour')}</Button>
                <Button size="sm" variant="outline" disabled={!costsOk} onClick={() => setDialog('bill')}><Plus className="mr-1 h-4 w-4" />{tx('Add workshop bill')}</Button>
              </div>
            )}
          </CardHeader>
          <CardContent>
            <LinesTable w={w} canAct={canCost && costsOk} onPrompt={setPrompt} lineAction={lineAction} />
          </CardContent>
        </Card>
      </div>

      {((w.defects?.length ?? 0) > 0 || (w.downtime?.length ?? 0) > 0) && (
        <div className="grid grid-cols-1 gap-4 lg:grid-cols-2">
          <Card>
            <CardHeader><CardTitle className="text-base">{tx('Defects')}</CardTitle></CardHeader>
            <CardContent className="space-y-2 text-sm">
              {(w.defects ?? []).map((d) => (
                <div key={d.id} className="flex flex-wrap items-center gap-2">
                  <span className="font-medium">{d.code}</span><span>{d.title}</span><SeverityPill severity={d.severity} /><DefectStatusPill status={d.status} />
                </div>
              ))}
              {(w.defects ?? []).length === 0 && <p className="text-muted-foreground">—</p>}
            </CardContent>
          </Card>
          <Card>
            <CardHeader><CardTitle className="text-base">{tx('Downtime')}</CardTitle></CardHeader>
            <CardContent className="space-y-2 text-sm">
              {(w.downtime ?? []).map((d) => (
                <div key={d.id}>{dateTime(d.startedAt)} → {d.endedAt ? dateTime(d.endedAt) : tx('still down')}</div>
              ))}
            </CardContent>
          </Card>
        </div>
      )}

      <PartDialog w={w} open={dialog === 'part'} onOpenChange={(o) => setDialog(o ? 'part' : null)} />
      <LabourDialog w={w} open={dialog === 'labour'} onOpenChange={(o) => setDialog(o ? 'labour' : null)} />
      <BillDialog w={w} open={dialog === 'bill'} onOpenChange={(o) => setDialog(o ? 'bill' : null)} />
      <CompleteDialog w={w} open={dialog === 'complete'} onOpenChange={(o) => setDialog(o ? 'complete' : null)} />
      <PromptDialog prompt={prompt} onClose={() => setPrompt(null)} />
    </div>
  );
}

function Tile({ label, value, strong }: { label: string; value: string; strong?: boolean }) {
  return (
    <div className="rounded-lg border bg-card p-3">
      <div className="text-xs text-muted-foreground">{label}</div>
      <div className={strong ? 'text-xl font-bold tabular-nums' : 'text-lg font-semibold tabular-nums'}>{value}</div>
    </div>
  );
}

function TasksCard({ w, editable }: { w: WorkOrder; editable: boolean }) {
  const add = useAddTask();
  const update = useUpdateTask();
  const remove = useDeleteTask();
  const [text, setText] = useState('');
  const tasks = w.tasks ?? [];
  const set = (taskId: string, status: 'pending' | 'done' | 'skipped') =>
    update.mutateAsync({ id: w.id, taskId, body: { status } }).catch((err) => notify.error(apiMessage(err, tx('The action failed'))));
  return (
    <Card>
      <CardHeader><CardTitle className="text-base">{tx('Tasks')} ({tasks.filter((t) => t.status !== 'pending').length}/{tasks.length})</CardTitle></CardHeader>
      <CardContent className="space-y-2">
        {tasks.map((t) => (
          <div key={t.id} className="flex items-center gap-2 rounded-md border px-2 py-1.5 text-sm">
            <span className={t.status === 'pending' ? 'flex-1' : 'flex-1 text-muted-foreground line-through'}>{t.description}</span>
            {t.status === 'skipped' && <StatusPill tone="neutral" dot={false}>{tx('Skipped')}</StatusPill>}
            {editable && (
              <>
                <Button size="icon" variant={t.status === 'done' ? 'default' : 'outline'} className="h-9 w-9" aria-label={tx('Done')}
                  onClick={() => set(t.id, t.status === 'done' ? 'pending' : 'done')}><Check className="h-4 w-4" /></Button>
                <Button size="icon" variant="outline" className="h-9 w-9" aria-label={tx('Skip')}
                  onClick={() => set(t.id, t.status === 'skipped' ? 'pending' : 'skipped')}><Minus className="h-4 w-4" /></Button>
                <Button size="icon" variant="ghost" className="h-9 w-9" aria-label={tx('Delete')}
                  onClick={() => remove.mutateAsync({ id: w.id, taskId: t.id }).catch((err) => notify.error(apiMessage(err, tx('The action failed'))))}><Trash2 className="h-4 w-4" /></Button>
              </>
            )}
          </div>
        ))}
        {tasks.length === 0 && <p className="text-sm text-muted-foreground">{tx('No tasks.')}</p>}
        {editable && (
          <form className="flex gap-2" onSubmit={async (e) => {
            e.preventDefault();
            if (!text.trim()) return;
            try { await add.mutateAsync({ id: w.id, description: text.trim() }); setText(''); } catch (err) { notify.error(apiMessage(err, tx('The action failed'))); }
          }}>
            <Input value={text} onChange={(e) => setText(e.target.value)} placeholder={tx('Add a task')} />
            <Button type="submit" variant="outline" disabled={!text.trim()}><Plus className="h-4 w-4" /></Button>
          </form>
        )}
      </CardContent>
    </Card>
  );
}

function LinesTable({ w, canAct, onPrompt, lineAction }: {
  w: WorkOrder; canAct: boolean; onPrompt: (p: Prompt) => void; lineAction: ReturnType<typeof useLineAction>;
}) {
  const detail = (l: WorkOrderLine): ReactNode => {
    if (l.lineType === 'part' || l.lineType === 'part_return') {
      return <>{l.productCode ? `${l.productCode} · ` : ''}{l.productName}<div className="text-xs text-muted-foreground">{num(l.quantity, 3)} @ {money(l.unitCost)}{l.locationName ? ` · ${l.locationName}` : ''}{Number(l.returnedQuantity) > 0 ? ` · ${tx('returned')} ${num(l.returnedQuantity, 3)}` : ''}</div></>;
    }
    if (l.lineType === 'labour') {
      return <>{l.employeeName}<div className="text-xs text-muted-foreground">{num(l.hours, 2)} h @ {money(l.unitCost)}{l.journalEntryId ? '' : ` · ${tx('memo cost')}`}</div></>;
    }
    return <>{l.partnerName} · {l.supplierReference}<div className="text-xs text-muted-foreground">{l.vendorBill ? `${l.vendorBill.documentNumber} (${l.vendorBill.status})` : ''}</div></>;
  };
  const columns: Column<WorkOrderLine>[] = [
    { key: 'type', header: tx('Type'), render: (l) => LINE_TYPE_LABEL[l.lineType] },
    { key: 'detail', header: tx('Details'), render: (l) => <div className="min-w-[12rem]">{detail(l)}{l.description && <div className="text-xs text-muted-foreground">{l.description}</div>}</div> },
    { key: 'amount', header: tx('Amount'), className: 'text-right', render: (l) => <span className={l.status === 'reversed' ? 'tabular-nums line-through text-muted-foreground' : 'tabular-nums'}>{money(l.amount)}</span> },
    { key: 'status', header: tx('Status'), render: (l) => (l.status === 'reversed' ? <StatusPill tone="neutral">{tx('Reversed')}</StatusPill> : <StatusPill tone="success">{tx('Posted')}</StatusPill>) },
    {
      key: 'actions', header: '', className: 'text-right',
      render: (l) => canAct && l.status === 'posted' && (
        <div className="flex justify-end gap-1">
          {l.lineType === 'part' && Number(l.quantity) > Number(l.returnedQuantity) && ['open', 'in_progress', 'completed'].includes(w.status) && (
            <Button size="sm" variant="ghost" onClick={() => onPrompt({
              title: tx('Return unused part'), label: tx('Quantity to return'), kind: 'quantity',
              run: (quantity) => lineAction.mutateAsync({ id: w.id, lineId: l.id, action: 'return', body: { quantity: Number(quantity) } }),
            })}><Undo2 className="mr-1 h-4 w-4" />{tx('Return')}</Button>
          )}
          <Button size="sm" variant="ghost" onClick={() => onPrompt({
            title: tx('Reverse line'), label: tx('Reason *'), kind: 'reason',
            run: (reason) => lineAction.mutateAsync({ id: w.id, lineId: l.id, action: 'reverse', body: { reason } }),
          })}>{tx('Reverse')}</Button>
        </div>
      ),
    },
  ];
  return <DataTable columns={columns} data={w.lines ?? []} getRowId={(l) => l.id} emptyMessage={tx('No costs booked yet.')} />;
}

function PromptDialog({ prompt, onClose }: { prompt: Prompt | null; onClose: () => void }) {
  const [value, setValue] = useState('');
  const [busy, setBusy] = useState(false);
  const submit = async () => {
    if (!prompt) return;
    setBusy(true);
    try {
      await prompt.run(value.trim());
      notify.success(tx('Done'));
      setValue('');
      onClose();
    } catch (err) {
      notify.error(apiMessage(err, tx('The action failed')));
    } finally {
      setBusy(false);
    }
  };
  return (
    <Dialog open={!!prompt} onOpenChange={(o) => { if (!o) { setValue(''); onClose(); } }}>
      <DialogContent>
        <DialogHeader><DialogTitle>{prompt?.title}</DialogTitle></DialogHeader>
        <Field label={prompt?.label ?? ''}>
          {prompt?.kind === 'quantity'
            ? <Input type="number" inputMode="decimal" min="0" step="any" value={value} onChange={(e) => setValue(e.target.value)} />
            : <Textarea rows={3} value={value} onChange={(e) => setValue(e.target.value)} />}
        </Field>
        <DialogFooter>
          <Button variant="outline" onClick={() => { setValue(''); onClose(); }}>{tx('Cancel')}</Button>
          <Button onClick={submit} disabled={busy || !value.trim() || (prompt?.kind === 'quantity' && !(Number(value) > 0))}>{tx('Confirm')}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

function PartDialog({ w, open, onOpenChange }: { w: WorkOrder; open: boolean; onOpenChange: (o: boolean) => void }) {
  const lookups = useFleetLookups();
  const [locationId, setLocationId] = useState('');
  const [search, setSearch] = useState('');
  const [picked, setPicked] = useState<{ id: string; label: string } | null>(null);
  const [quantity, setQuantity] = useState('');
  const parts = usePartPicker(search, locationId);
  const cost = useCostLine();
  const submit = async () => {
    if (!picked) return;
    try {
      await cost.mutateAsync({ id: w.id, kind: 'parts', body: { productId: picked.id, locationId, quantity: Number(quantity) } });
      notify.success(tx('Part issued'));
      setPicked(null); setQuantity(''); setSearch('');
      onOpenChange(false);
    } catch (err) {
      notify.error(apiMessage(err, tx('Could not issue the part')));
    }
  };
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>{tx('Issue part')}</DialogTitle>
          <DialogDescription>{tx('The part leaves stock now and its cost goes to this vehicle.')}</DialogDescription>
        </DialogHeader>
        <div className="space-y-4">
          <Field label={tx('Store / location *')}>
            <OptionalSelect value={locationId} onChange={(v) => { setLocationId(v); setPicked(null); }}
              options={(lookups.data?.locations ?? []).map((l) => ({ value: l.id, label: l.name }))} placeholder={tx('Choose a location')} />
          </Field>
          <Field label={tx('Spare part *')}>
            <RemotePicker value={picked?.id ?? ''} selectedLabel={picked?.label} search={search} onSearch={setSearch}
              results={(parts.data ?? []).map((p) => ({ value: p.id, label: `${p.code} · ${p.name}`, hint: `${tx('On hand')}: ${num(p.onHand, 3)}` }))}
              loading={parts.isFetching} onPick={(value, label) => setPicked(value ? { id: value, label } : null)}
              placeholder={locationId ? tx('Search parts…') : tx('Choose a location first')} clearLabel={tx('Clear')} />
          </Field>
          <Field label={tx('Quantity *')}><Input type="number" inputMode="decimal" min="0" step="any" value={quantity} onChange={(e) => setQuantity(e.target.value)} /></Field>
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={() => onOpenChange(false)}>{tx('Cancel')}</Button>
          <Button onClick={submit} disabled={!picked || !locationId || !(Number(quantity) > 0) || cost.isPending}>{tx('Issue part')}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

function LabourDialog({ w, open, onOpenChange }: { w: WorkOrder; open: boolean; onOpenChange: (o: boolean) => void }) {
  const [search, setSearch] = useState('');
  const [picked, setPicked] = useState<{ id: string; label: string; rate: string | null } | null>(null);
  const [hours, setHours] = useState('');
  const [rate, setRate] = useState('');
  const [workDate, setWorkDate] = useState('');
  const employees = useEmployeePicker(search);
  const cost = useCostLine();
  const submit = async () => {
    if (!picked) return;
    try {
      await cost.mutateAsync({ id: w.id, kind: 'labour', body: { employeeId: picked.id, hours: Number(hours), rate: toNumber(rate), workDate: workDate || undefined } });
      notify.success(tx('Labour booked'));
      setPicked(null); setHours(''); setRate('');
      onOpenChange(false);
    } catch (err) {
      notify.error(apiMessage(err, tx('Could not book the labour')));
    }
  };
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent>
        <DialogHeader><DialogTitle>{tx('Book labour')}</DialogTitle></DialogHeader>
        <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
          <Field label={tx('Mechanic / employee *')} className="sm:col-span-2">
            <RemotePicker value={picked?.id ?? ''} selectedLabel={picked?.label} search={search} onSearch={setSearch}
              results={(employees.data ?? []).map((e) => ({ value: e.id, label: `${e.firstName} ${e.lastName ?? ''}`.trim(), hint: e.employeeCode }))}
              loading={employees.isFetching}
              onPick={(value, label) => {
                const e = (employees.data ?? []).find((x) => x.id === value);
                setPicked(value ? { id: value, label, rate: e?.hourlyRate ?? null } : null);
                if (e?.hourlyRate) setRate(String(Number(e.hourlyRate)));
              }}
              placeholder={tx('Search employees…')} clearLabel={tx('Clear')} />
          </Field>
          <Field label={tx('Hours *')}><Input type="number" inputMode="decimal" min="0" step="0.25" value={hours} onChange={(e) => setHours(e.target.value)} /></Field>
          <Field label={tx('Hourly rate')}><Input type="number" inputMode="decimal" min="0" step="any" value={rate} onChange={(e) => setRate(e.target.value)} placeholder={picked?.rate ?? ''} /></Field>
          <Field label={tx('Work date')}><Input type="date" value={workDate} onChange={(e) => setWorkDate(e.target.value)} /></Field>
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={() => onOpenChange(false)}>{tx('Cancel')}</Button>
          <Button onClick={submit} disabled={!picked || !(Number(hours) > 0) || cost.isPending}>{tx('Book labour')}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

function BillDialog({ w, open, onOpenChange }: { w: WorkOrder; open: boolean; onOpenChange: (o: boolean) => void }) {
  const [search, setSearch] = useState('');
  const [picked, setPicked] = useState<{ id: string; label: string } | null>(null);
  const [reference, setReference] = useState('');
  const [billDate, setBillDate] = useState('');
  const [amount, setAmount] = useState('');
  const [description, setDescription] = useState('');
  const partners = usePartnerPicker(search, open && search.trim().length > 0);
  const cost = useCostLine();
  const submit = async () => {
    if (!picked) return;
    try {
      await cost.mutateAsync({ id: w.id, kind: 'bills', body: {
        partnerId: picked.id, supplierReference: reference.trim(), billDate: billDate || undefined, amount: Number(amount), description: description.trim(),
      } });
      notify.success(tx('Workshop bill added; a draft vendor bill is waiting in Accounts Payable'));
      setPicked(null); setReference(''); setAmount(''); setDescription('');
      onOpenChange(false);
    } catch (err) {
      notify.error(apiMessage(err, tx('Could not add the bill')));
    }
  };
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>{tx('Add workshop bill')}</DialogTitle>
          <DialogDescription>{tx('Outside repairs: the cost goes to this vehicle now; Accounts Payable approves and pays the supplier bill.')}</DialogDescription>
        </DialogHeader>
        <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
          <Field label={tx('Supplier / workshop *')} className="sm:col-span-2">
            <RemotePicker value={picked?.id ?? ''} selectedLabel={picked?.label} search={search} onSearch={setSearch}
              results={(partners.data ?? []).map((p) => ({ value: p.id, label: p.name, hint: p.code ?? undefined }))}
              loading={partners.isFetching} onPick={(value, label) => setPicked(value ? { id: value, label } : null)}
              placeholder={tx('Search suppliers…')} clearLabel={tx('Clear')} />
          </Field>
          <Field label={tx('Supplier invoice no. *')}><Input value={reference} onChange={(e) => setReference(e.target.value)} maxLength={80} /></Field>
          <Field label={tx('Bill date')}><Input type="date" value={billDate} onChange={(e) => setBillDate(e.target.value)} /></Field>
          <Field label={tx('Amount (excl. tax) *')}><Input type="number" inputMode="decimal" min="0" step="0.01" value={amount} onChange={(e) => setAmount(e.target.value)} /></Field>
          <Field label={tx('Work done *')} className="sm:col-span-2"><Input value={description} onChange={(e) => setDescription(e.target.value)} maxLength={500} /></Field>
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={() => onOpenChange(false)}>{tx('Cancel')}</Button>
          <Button onClick={submit} disabled={!picked || !reference.trim() || !description.trim() || !(Number(amount) > 0) || cost.isPending}>{tx('Add bill')}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

function CompleteDialog({ w, open, onOpenChange }: { w: WorkOrder; open: boolean; onOpenChange: (o: boolean) => void }) {
  const action = useWorkOrderAction();
  const [odometer, setOdometer] = useState('');
  const [hours, setHours] = useState('');
  const [resolution, setResolution] = useState('');
  const meter = w.vehicle?.meterType ?? 'odometer';
  const submit = async () => {
    try {
      await action.mutateAsync({ id: w.id, action: 'complete', body: { odometer: toNumber(odometer), hours: toNumber(hours), resolution: resolution.trim() || undefined } });
      notify.success(tx('Work order completed'));
      onOpenChange(false);
    } catch (err) {
      notify.error(apiMessage(err, tx('Could not complete the work order')));
    }
  };
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>{tx('Complete work order')}</DialogTitle>
          <DialogDescription>{tx('The vehicle returns to service, its downtime ends and the next service is planned from these readings.')}</DialogDescription>
        </DialogHeader>
        <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
          {(meter === 'odometer' || meter === 'both') && (
            <Field label={tx('Odometer (km)')}><Input type="number" inputMode="decimal" min="0" value={odometer} onChange={(e) => setOdometer(e.target.value)} /></Field>
          )}
          {(meter === 'hours' || meter === 'both') && (
            <Field label={tx('Engine hours')}><Input type="number" inputMode="decimal" min="0" value={hours} onChange={(e) => setHours(e.target.value)} /></Field>
          )}
          <Field label={tx('Resolution')} className="sm:col-span-2"><Textarea rows={3} value={resolution} onChange={(e) => setResolution(e.target.value)} /></Field>
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={() => onOpenChange(false)}>{tx('Cancel')}</Button>
          <Button onClick={submit} disabled={action.isPending}>{tx('Complete')}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

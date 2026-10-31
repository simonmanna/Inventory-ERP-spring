import { useState } from 'react';
import { Download, Pencil, Plus, RefreshCw, Upload, Wrench } from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs';
import { DataTablePagination, FilterSelect, ListPageHeader } from '@/components/list';
import { useAuthStore } from '@/stores/auth.store';
import { notify, apiMessage } from '@/lib/notify';
import { dateTime } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';
import { Field, OptionalSelect } from '../../fleet/fleet-ui';
import {
  REASON_CATEGORY_LABEL, fromHm, hm, qty, useDefaultReasons, useDefaultShifts, useImportMachineCsv, useMachineAdapters,
  useMachineEvents, usePollMachines, useProfiles, useReasons, useSaveProfile, useSaveReason, useSaveShift, useShifts,
  type DowntimeReason, type IngestResult, type ReasonCategory, type Shift, type WorkCenterProfile,
} from '@/features/manufacturing/api-shopfloor';

/** Shop-floor set-up (ADR-S015): shifts, downtime reasons, work-centre OEE profiles and machine data. */
export function FloorSetupPage() {
  const has = useAuthStore((s) => s.hasPermission);
  const canEdit = has(PERMISSIONS.workCenter.update);
  const canMachine = has(PERMISSIONS.shopFloor.machineData);
  return (
    <div className="space-y-4">
      <ListPageHeader icon={Wrench} title={tx('Shop-floor setup')}
        description={tx('Production shifts, the downtime reason list, ideal rates for OEE and machine data capture.')} />
      <Tabs defaultValue="shifts">
        <TabsList className="flex-wrap">
          <TabsTrigger value="shifts">{tx('Shifts')}</TabsTrigger>
          <TabsTrigger value="reasons">{tx('Downtime reasons')}</TabsTrigger>
          <TabsTrigger value="profiles">{tx('Work centres')}</TabsTrigger>
          <TabsTrigger value="machines">{tx('Machine data')}</TabsTrigger>
        </TabsList>
        <TabsContent value="shifts"><ShiftsTab canEdit={canEdit} /></TabsContent>
        <TabsContent value="reasons"><ReasonsTab canEdit={canEdit} /></TabsContent>
        <TabsContent value="profiles"><ProfilesTab canEdit={canEdit} /></TabsContent>
        <TabsContent value="machines"><MachinesTab canFeed={canMachine} /></TabsContent>
      </Tabs>
    </div>
  );
}

async function attempt(p: Promise<unknown>, ok: string, fail: string): Promise<boolean> {
  try {
    await p;
    notify.success(ok);
    return true;
  } catch (err) {
    notify.error(apiMessage(err, fail));
    return false;
  }
}

function ShiftsTab({ canEdit }: { canEdit: boolean }) {
  const shifts = useShifts();
  const defaults = useDefaultShifts();
  const [editing, setEditing] = useState<Shift | 'new' | null>(null);
  return (
    <Card>
      <CardHeader className="flex flex-row flex-wrap items-center justify-between gap-2">
        <CardTitle>{tx('Production shifts')}</CardTitle>
        {canEdit && (
          <div className="flex gap-2">
            {(shifts.data ?? []).length === 0 && (
              <Button variant="outline" disabled={defaults.isPending}
                onClick={() => attempt(defaults.mutateAsync(undefined), tx('Standard shifts created'), tx('Could not create the shifts'))}>
                <Download className="mr-1.5 h-4 w-4" />{tx('Load Day / Evening / Night')}
              </Button>
            )}
            <Button onClick={() => setEditing('new')}><Plus className="mr-1.5 h-4 w-4" />{tx('New shift')}</Button>
          </div>
        )}
      </CardHeader>
      <CardContent className="overflow-x-auto">
        <p className="mb-3 text-sm text-muted-foreground">{tx('Times are in the organization’s time zone; a shift ending before it starts runs past midnight. Active shifts must not overlap.')}</p>
        {shifts.isLoading ? <p className="text-muted-foreground">{tx('Loading…')}</p> : (shifts.data ?? []).length === 0 ? (
          <p className="text-sm text-muted-foreground">{tx('No shifts yet. Without shifts there are no shift reports and no OEE.')}</p>
        ) : (
          <Table>
            <TableHeader><TableRow>
              <TableHead>{tx('Code')}</TableHead><TableHead>{tx('Name')}</TableHead><TableHead>{tx('Start')}</TableHead>
              <TableHead>{tx('End')}</TableHead><TableHead className="text-right">{tx('Break')}</TableHead>
              <TableHead className="text-right">{tx('Length')}</TableHead><TableHead>{tx('Status')}</TableHead><TableHead />
            </TableRow></TableHeader>
            <TableBody>
              {shifts.data?.map((s) => (
                <TableRow key={s.id}>
                  <TableCell className="font-medium">{s.code}</TableCell>
                  <TableCell>{s.name}</TableCell>
                  <TableCell>{hm(s.startMinute)}</TableCell>
                  <TableCell>{hm(s.endMinute)}{s.endMinute <= s.startMinute ? ` (+1)` : ''}</TableCell>
                  <TableCell className="text-right">{tx('{{n}} min', { n: s.breakMinutes })}</TableCell>
                  <TableCell className="text-right">{tx('{{n}} min', { n: s.lengthMinutes })}</TableCell>
                  <TableCell>{s.isActive ? tx('Active') : tx('Inactive')}</TableCell>
                  <TableCell className="text-right">
                    {canEdit && <Button variant="ghost" size="icon" aria-label={tx('Edit')} onClick={() => setEditing(s)}><Pencil className="h-4 w-4" /></Button>}
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        )}
      </CardContent>
      {editing && <ShiftDialog shift={editing === 'new' ? undefined : editing} onClose={() => setEditing(null)} />}
    </Card>
  );
}

function ShiftDialog({ shift, onClose }: { shift?: Shift; onClose: () => void }) {
  const save = useSaveShift();
  const [code, setCode] = useState(shift?.code ?? '');
  const [name, setName] = useState(shift?.name ?? '');
  const [start, setStart] = useState(hm(shift?.startMinute ?? 360));
  const [end, setEnd] = useState(hm(shift?.endMinute ?? 840));
  const [brk, setBrk] = useState(String(shift?.breakMinutes ?? 30));
  const [active, setActive] = useState(shift?.isActive ?? true);
  const endMinute = fromHm(end) === 0 ? 1440 : fromHm(end);
  const valid = (shift || code.trim()) && name.trim() && fromHm(start) !== endMinute % 1440 && Number(brk) >= 0;
  const submit = async () => {
    const body = { name: name.trim(), startMinute: fromHm(start), endMinute, breakMinutes: Number(brk) || 0, isActive: active };
    const ok = await attempt(save.mutateAsync({ id: shift?.id, body: shift ? body : { ...body, code: code.trim() } }),
      tx('Shift saved'), tx('Could not save the shift'));
    if (ok) onClose();
  };
  return (
    <Dialog open onOpenChange={(o) => !o && onClose()}>
      <DialogContent className="sm:max-w-md">
        <DialogHeader>
          <DialogTitle>{shift ? tx('Edit shift {{code}}', { code: shift.code }) : tx('New shift')}</DialogTitle>
          <DialogDescription>{tx('Breaks are not planned production time.')}</DialogDescription>
        </DialogHeader>
        <div className="grid grid-cols-2 gap-3">
          {!shift && <Field label={tx('Code *')}><Input value={code} onChange={(e) => setCode(e.target.value.toUpperCase())} maxLength={20} /></Field>}
          <Field label={tx('Name *')} className={shift ? 'col-span-2' : ''}><Input value={name} onChange={(e) => setName(e.target.value)} /></Field>
          <Field label={tx('Start')}><Input type="time" value={start} onChange={(e) => setStart(e.target.value)} /></Field>
          <Field label={tx('End')}><Input type="time" value={end} onChange={(e) => setEnd(e.target.value)} /></Field>
          <Field label={tx('Break (min)')}><Input inputMode="numeric" value={brk} onChange={(e) => setBrk(e.target.value)} /></Field>
          <label className="flex items-end gap-2 pb-2 text-sm"><input type="checkbox" checked={active} onChange={(e) => setActive(e.target.checked)} />{tx('Active')}</label>
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={onClose}>{tx('Cancel')}</Button>
          <Button disabled={!valid || save.isPending} onClick={submit}>{tx('Save')}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

function ReasonsTab({ canEdit }: { canEdit: boolean }) {
  const reasons = useReasons(true);
  const defaults = useDefaultReasons();
  const [editing, setEditing] = useState<DowntimeReason | 'new' | null>(null);
  return (
    <Card>
      <CardHeader className="flex flex-row flex-wrap items-center justify-between gap-2">
        <CardTitle>{tx('Downtime reasons')}</CardTitle>
        {canEdit && (
          <div className="flex gap-2">
            <Button variant="outline" disabled={defaults.isPending}
              onClick={() => attempt(defaults.mutateAsync(undefined), tx('Standard reasons added'), tx('Could not add the reasons'))}>
              <Download className="mr-1.5 h-4 w-4" />{tx('Add standard reasons')}
            </Button>
            <Button onClick={() => setEditing('new')}><Plus className="mr-1.5 h-4 w-4" />{tx('New reason')}</Button>
          </div>
        )}
      </CardHeader>
      <CardContent className="overflow-x-auto">
        <p className="mb-3 text-sm text-muted-foreground">{tx('Planned stops (breaks, planned maintenance, no orders) do not count against availability; all other stops do.')}</p>
        {reasons.isLoading ? <p className="text-muted-foreground">{tx('Loading…')}</p> : (reasons.data ?? []).length === 0 ? (
          <p className="text-sm text-muted-foreground">{tx('No reasons yet. Add the standard list to start.')}</p>
        ) : (
          <Table>
            <TableHeader><TableRow>
              <TableHead>{tx('Code')}</TableHead><TableHead>{tx('Name')}</TableHead><TableHead>{tx('Category')}</TableHead>
              <TableHead>{tx('Planned')}</TableHead><TableHead>{tx('Status')}</TableHead><TableHead />
            </TableRow></TableHeader>
            <TableBody>
              {reasons.data?.map((r) => (
                <TableRow key={r.id}>
                  <TableCell className="font-medium">{r.code}</TableCell>
                  <TableCell>{r.name}</TableCell>
                  <TableCell>{REASON_CATEGORY_LABEL[r.category] ?? r.category}</TableCell>
                  <TableCell>{r.isPlanned ? tx('Yes') : tx('No')}</TableCell>
                  <TableCell>{r.isActive ? tx('Active') : tx('Inactive')}</TableCell>
                  <TableCell className="text-right">
                    {canEdit && <Button variant="ghost" size="icon" aria-label={tx('Edit')} onClick={() => setEditing(r)}><Pencil className="h-4 w-4" /></Button>}
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        )}
      </CardContent>
      {editing && <ReasonDialog reason={editing === 'new' ? undefined : editing} onClose={() => setEditing(null)} />}
    </Card>
  );
}

function ReasonDialog({ reason, onClose }: { reason?: DowntimeReason; onClose: () => void }) {
  const save = useSaveReason();
  const [code, setCode] = useState(reason?.code ?? '');
  const [name, setName] = useState(reason?.name ?? '');
  const [category, setCategory] = useState<ReasonCategory>(reason?.category ?? 'other');
  const [planned, setPlanned] = useState(reason?.isPlanned ?? false);
  const [active, setActive] = useState(reason?.isActive ?? true);
  const submit = async () => {
    const body = { name: name.trim(), category, isPlanned: planned, isActive: active };
    const ok = await attempt(save.mutateAsync({ id: reason?.id, body: reason ? body : { ...body, code: code.trim() } }),
      tx('Reason saved'), tx('Could not save the reason'));
    if (ok) onClose();
  };
  return (
    <Dialog open onOpenChange={(o) => !o && onClose()}>
      <DialogContent className="sm:max-w-md">
        <DialogHeader>
          <DialogTitle>{reason ? tx('Edit reason {{code}}', { code: reason.code }) : tx('New reason')}</DialogTitle>
          <DialogDescription>{tx('The code is kept on every stop and cannot change later.')}</DialogDescription>
        </DialogHeader>
        <div className="grid grid-cols-2 gap-3">
          {!reason && <Field label={tx('Code *')}><Input value={code} onChange={(e) => setCode(e.target.value.toUpperCase())} maxLength={30} /></Field>}
          <Field label={tx('Name *')} className={reason ? 'col-span-2' : ''}><Input value={name} onChange={(e) => setName(e.target.value)} /></Field>
          <Field label={tx('Category')} className="col-span-2">
            <OptionalSelect value={category} placeholder={tx('Category')}
              onChange={(v) => { const c = (v || 'other') as ReasonCategory; setCategory(c); if (c === 'planned') setPlanned(true); }}
              options={Object.entries(REASON_CATEGORY_LABEL).map(([value, label]) => ({ value, label }))} />
          </Field>
          <label className="flex items-center gap-2 text-sm"><input type="checkbox" checked={planned} onChange={(e) => setPlanned(e.target.checked)} />{tx('Planned stop')}</label>
          <label className="flex items-center gap-2 text-sm"><input type="checkbox" checked={active} onChange={(e) => setActive(e.target.checked)} />{tx('Active')}</label>
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={onClose}>{tx('Cancel')}</Button>
          <Button disabled={!name.trim() || (!reason && !code.trim()) || save.isPending} onClick={submit}>{tx('Save')}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

function ProfilesTab({ canEdit }: { canEdit: boolean }) {
  const profiles = useProfiles();
  const [editing, setEditing] = useState<WorkCenterProfile | null>(null);
  return (
    <Card>
      <CardHeader><CardTitle>{tx('Work centres on the shop floor')}</CardTitle></CardHeader>
      <CardContent className="overflow-x-auto">
        <p className="mb-3 text-sm text-muted-foreground">{tx('The ideal rate drives OEE performance; the machine code links PLC / counter data to the work centre (default: the work-centre code).')}</p>
        {profiles.isLoading ? <p className="text-muted-foreground">{tx('Loading…')}</p> : (profiles.data ?? []).length === 0 ? (
          <p className="text-sm text-muted-foreground">{tx('No active work centres.')}</p>
        ) : (
          <Table>
            <TableHeader><TableRow>
              <TableHead>{tx('Work centre')}</TableHead><TableHead className="text-right">{tx('Ideal rate / h')}</TableHead>
              <TableHead>{tx('Machine code')}</TableHead><TableHead>{tx('Meters')}</TableHead><TableHead />
            </TableRow></TableHeader>
            <TableBody>
              {profiles.data?.map((p) => (
                <TableRow key={p.workCenterId}>
                  <TableCell>{p.workCenterName}<div className="text-xs text-muted-foreground">{p.workCenterCode}</div></TableCell>
                  <TableCell className="text-right">{p.idealRatePerHour !== null ? `${qty(p.idealRatePerHour)} ${p.rateUom ?? ''}` : '—'}</TableCell>
                  <TableCell>{p.machineCode ?? <span className="text-muted-foreground">{p.workCenterCode}</span>}</TableCell>
                  <TableCell>{(p.meterCodes ?? []).join(', ') || '—'}</TableCell>
                  <TableCell className="text-right">
                    {canEdit && <Button variant="ghost" size="icon" aria-label={tx('Edit')} onClick={() => setEditing(p)}><Pencil className="h-4 w-4" /></Button>}
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        )}
      </CardContent>
      {editing && <ProfileDialog profile={editing} onClose={() => setEditing(null)} />}
    </Card>
  );
}

function ProfileDialog({ profile, onClose }: { profile: WorkCenterProfile; onClose: () => void }) {
  const save = useSaveProfile();
  const [rate, setRate] = useState(profile.idealRatePerHour !== null ? String(Number(profile.idealRatePerHour)) : '');
  const [uom, setUom] = useState(profile.rateUom ?? '');
  const [machine, setMachine] = useState(profile.machineCode ?? '');
  const [meters, setMeters] = useState((profile.meterCodes ?? []).join(', '));
  const rateOk = rate.trim() === '' || (Number.isFinite(Number(rate)) && Number(rate) > 0);
  const submit = async () => {
    const ok = await attempt(save.mutateAsync({ workCenterId: profile.workCenterId, body: {
      idealRatePerHour: rate.trim() ? Number(rate) : null, rateUom: uom.trim() || null, machineCode: machine.trim() || null,
      meterCodes: meters.split(',').map((m) => m.trim()).filter(Boolean),
    } }), tx('Work centre saved'), tx('Could not save the work centre'));
    if (ok) onClose();
  };
  return (
    <Dialog open onOpenChange={(o) => !o && onClose()}>
      <DialogContent className="sm:max-w-md">
        <DialogHeader>
          <DialogTitle>{profile.workCenterName}</DialogTitle>
          <DialogDescription>{tx('Ideal rate in the unit the floor reports (tonnes, blocks, pieces) per run hour.')}</DialogDescription>
        </DialogHeader>
        <div className="grid grid-cols-2 gap-3">
          <Field label={tx('Ideal rate / h')} error={rateOk ? undefined : tx('Must be a positive number')}>
            <Input inputMode="decimal" value={rate} onChange={(e) => setRate(e.target.value)} />
          </Field>
          <Field label={tx('Unit')}><Input value={uom} onChange={(e) => setUom(e.target.value)} placeholder={tx('e.g. t, pcs')} /></Field>
          <Field label={tx('Machine code')} className="col-span-2"><Input value={machine} onChange={(e) => setMachine(e.target.value)} placeholder={profile.workCenterCode} /></Field>
          <Field label={tx('Meters (comma separated)')} className="col-span-2"><Input value={meters} onChange={(e) => setMeters(e.target.value)} placeholder={tx('e.g. hours, kwh, tonnes')} /></Field>
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={onClose}>{tx('Cancel')}</Button>
          <Button disabled={!rateOk || save.isPending} onClick={submit}>{tx('Save')}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

const EVENT_STATUS: Record<string, string> = { drafted: tx('Drafted'), ignored: tx('Ignored'), error: tx('Error') };

function MachinesTab({ canFeed }: { canFeed: boolean }) {
  const adapters = useMachineAdapters();
  const poll = usePollMachines();
  const importCsv = useImportMachineCsv();
  const [status, setStatus] = useState('');
  const [page, setPage] = useState(1);
  const [pageSize, setPageSize] = useState(20);
  const events = useMachineEvents({ page, pageSize, status: status || undefined });
  const [result, setResult] = useState<IngestResult | null>(null);
  const run = async (p: Promise<IngestResult>) => {
    try {
      const r = await p;
      setResult(r);
      notify.success(tx('{{created}} new events, {{drafted}} drafts', { created: r.created, drafted: r.drafted }));
    } catch (err) {
      notify.error(apiMessage(err, tx('Machine data failed')));
    }
  };
  const onFile = async (file: File | undefined) => {
    if (!file) return;
    const content = await file.text();
    await run(importCsv.mutateAsync({ content, filename: file.name }));
  };
  return (
    <div className="space-y-4">
      <Card>
        <CardHeader><CardTitle>{tx('Sources')}</CardTitle></CardHeader>
        <CardContent className="space-y-3">
          <p className="text-sm text-muted-foreground">
            {tx('Machine data never posts by itself: counts and stops become drafts that operators confirm on the terminal. A gateway or MQTT bridge pushes to POST /api/v1/manufacturing/shop-floor/machine-data/push with a device account.')}
          </p>
          <div className="flex flex-wrap gap-2">
            {(adapters.data ?? []).filter((a) => a.pull).map((a) => (
              <Button key={a.code} variant="outline" disabled={!canFeed || !a.configured || poll.isPending}
                onClick={() => run(poll.mutateAsync({ adapter: a.code }))}>
                <RefreshCw className="mr-1.5 h-4 w-4" />{tx('Poll {{code}}', { code: a.code })}{a.configured ? '' : ` (${tx('not configured')})`}
              </Button>
            ))}
            <label className={`inline-flex h-10 cursor-pointer items-center rounded-md border px-4 text-sm ${canFeed ? '' : 'pointer-events-none opacity-50'}`}>
              <Upload className="mr-1.5 h-4 w-4" />{tx('Import CSV')}
              <input type="file" accept=".csv,text/csv" className="hidden" onChange={(e) => { void onFile(e.target.files?.[0]); e.target.value = ''; }} />
            </label>
          </div>
          <p className="text-xs text-muted-foreground">{tx('CSV columns: machineCode, type (count, weight, downtime, downtime_start, downtime_stop), occurredAt, endedAt, goodQty, scrapQty, reasonCode, externalId, notes.')}</p>
          {result && (
            <div className="rounded-md border p-3 text-sm">
              {tx('Received {{received}} · new {{created}} · duplicates {{duplicates}} · drafts {{drafted}} · ignored {{ignored}} · errors {{failed}}', {
                received: result.received, created: result.created, duplicates: result.duplicates, drafted: result.drafted, ignored: result.ignored, failed: result.errors.length,
              })}
              {result.errors.slice(0, 10).map((e) => <div key={`${e.row}-${e.message}`} className="text-destructive">#{e.row}: {e.message}</div>)}
            </div>
          )}
        </CardContent>
      </Card>
      <Card>
        <CardHeader className="flex flex-row flex-wrap items-center justify-between gap-2">
          <CardTitle>{tx('Machine events')}</CardTitle>
          <FilterSelect value={status} onChange={(v) => { setStatus(v); setPage(1); }} allLabel={tx('All statuses')}
            options={Object.entries(EVENT_STATUS).map(([value, label]) => ({ value, label }))} />
        </CardHeader>
        <CardContent className="overflow-x-auto">
          {events.isLoading ? <p className="text-muted-foreground">{tx('Loading…')}</p> : (events.data?.data ?? []).length === 0 ? (
            <p className="text-sm text-muted-foreground">{events.isError ? tx('Could not load the machine events.') : tx('No machine events yet.')}</p>
          ) : (
            <Table>
              <TableHeader><TableRow>
                <TableHead>{tx('Time')}</TableHead><TableHead>{tx('Machine')}</TableHead><TableHead>{tx('Type')}</TableHead>
                <TableHead className="text-right">{tx('Good')}</TableHead><TableHead className="text-right">{tx('Scrap')}</TableHead>
                <TableHead>{tx('Reason')}</TableHead><TableHead>{tx('Source')}</TableHead><TableHead>{tx('Status')}</TableHead>
              </TableRow></TableHeader>
              <TableBody>
                {events.data?.data.map((e) => (
                  <TableRow key={e.id}>
                    <TableCell className="whitespace-nowrap">{dateTime(e.occurredAt)}</TableCell>
                    <TableCell>{e.machineCode}{e.workCenterCode && e.workCenterCode !== e.machineCode ? ` · ${e.workCenterCode}` : ''}</TableCell>
                    <TableCell>{e.eventType}</TableCell>
                    <TableCell className="text-right tabular-nums">{qty(e.goodQty)}</TableCell>
                    <TableCell className="text-right tabular-nums">{qty(e.scrapQty)}</TableCell>
                    <TableCell>{e.reasonCode ?? '—'}</TableCell>
                    <TableCell>{e.adapter}</TableCell>
                    <TableCell>{EVENT_STATUS[e.status] ?? e.status}{e.error ? <div className="text-xs text-destructive">{e.error}</div> : null}</TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          )}
        </CardContent>
        {events.data?.meta && (
          <DataTablePagination page={events.data.meta.page} pageSize={pageSize} total={events.data.meta.total}
            totalPages={events.data.meta.totalPages} onPageChange={setPage} onPageSizeChange={(s) => { setPageSize(s); setPage(1); }} noun="event" />
        )}
      </Card>
    </div>
  );
}

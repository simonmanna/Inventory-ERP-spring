import { useEffect, useMemo, useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { ArrowLeft, ClipboardList, Plus, Trash2 } from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Textarea } from '@/components/ui/textarea';
import { Skeleton } from '@/components/ui/skeleton';
import { ListPageHeader } from '@/components/list';
import { useAuthStore } from '@/stores/auth.store';
import { notify, apiMessage } from '@/lib/notify';
import { tx } from '@/lib/i18n/tx';
import { Field, OptionalSelect } from '@/pages/fleet/fleet-ui';
import {
  LOSS_LABEL, METHOD_LABEL, SHIFT_LABEL, useBlasts, useCreateLog, useExtractionLog, useHaulage, useQuarryLookups, useTicketSearch,
  useUpdateLog, type LoadMethod, type LossKind, type Shift,
} from '@/features/quarry/api';
import { EmployeePicker, Section, qty, toOptions } from './quarry-ui';

interface LoadRow { method: LoadMethod; truckVehicleId: string; loadCount: string; capacityT: string; weighbridgeTicketId: string; ticketLabel: string; haulageId: string; tonnes: string; notes: string }
interface ResourceRow { kind: 'equipment' | 'labour'; vehicleId: string; employeeId: string; employeeLabel: string; role: string; hours: string; meterStart: string; meterEnd: string; fuelLitres: string; ratePerHour: string }
interface LossRow { kind: LossKind; tonnes: string; reason: string }

const emptyLoad = (method: LoadMethod): LoadRow => ({ method, truckVehicleId: '', loadCount: '', capacityT: '', weighbridgeTicketId: '', ticketLabel: '', haulageId: '', tonnes: '', notes: '' });
const emptyResource = (kind: 'equipment' | 'labour'): ResourceRow => ({ kind, vehicleId: '', employeeId: '', employeeLabel: '', role: '', hours: '', meterStart: '', meterEnd: '', fuelLitres: '', ratePerHour: '' });
const n = (v: string) => (v.trim() === '' ? undefined : Number(v));
const today = () => new Date(Date.now() - new Date().getTimezoneOffset() * 60_000).toISOString().slice(0, 10);

/** Weighbridge ticket chooser: completed internal-haulage tickets not used yet. */
function TicketChooser({ value, label, onPick }: { value: string; label: string; onPick: (id: string, label: string, tonnes: string) => void }) {
  const [search, setSearch] = useState('');
  const [open, setOpen] = useState(false);
  const q = useTicketSearch(search, open);
  return (
    <div className="space-y-1">
      <Input placeholder={tx('Ticket number or plate…')} value={value ? label : search} onFocus={() => setOpen(true)}
        onChange={(e) => { setSearch(e.target.value); if (value) onPick('', '', ''); }} />
      {open && !value && (
        <div className="max-h-40 overflow-y-auto rounded-md border bg-card text-sm">
          {(q.data ?? []).length === 0 && <div className="px-3 py-2 text-muted-foreground">{tx('No unused completed ticket')}</div>}
          {(q.data ?? []).map((t) => (
            <button key={t.id} type="button" className="flex w-full justify-between px-3 py-2 text-left hover:bg-accent"
              onClick={() => { onPick(t.id, t.ticketNumber, String(Number(t.netKg) / 1000)); setOpen(false); }}>
              <span>{t.ticketNumber} · {t.registrationPlate}</span><span className="tabular-nums">{qty(Number(t.netKg) / 1000, 3)} t</span>
            </button>
          ))}
        </div>
      )}
    </div>
  );
}

/**
 * Daily extraction entry (tablet / phone at the pit): site, zone, material, shift and supervisor; quantity lines as
 * loads × truck capacity, weighbridge tickets, haulage records or a survey; equipment (mobile plant, hours, fuel) and
 * labour; losses. Save as draft, or save and post into the ROM stockpile.
 */
export function ExtractionEntryPage() {
  const { id } = useParams();
  const navigate = useNavigate();
  const canPost = useAuthStore((s) => s.hasPermission(PERMISSIONS.quarryExtraction.post));
  const lookups = useQuarryLookups();
  const existing = useExtractionLog(id);
  const create = useCreateLog();
  const update = useUpdateLog();
  const [siteId, setSiteId] = useState('');
  const [zoneId, setZoneId] = useState('');
  const [materialId, setMaterialId] = useState('');
  const [stockpileId, setStockpileId] = useState('');
  const [blastId, setBlastId] = useState('');
  const [extractedOn, setExtractedOn] = useState(today());
  const [shift, setShift] = useState<Shift>('day');
  const [supervisor, setSupervisor] = useState({ id: '', label: '' });
  const [otherCost, setOtherCost] = useState('');
  const [otherCostNote, setOtherCostNote] = useState('');
  const [notes, setNotes] = useState('');
  const [loads, setLoads] = useState<LoadRow[]>([emptyLoad('loads')]);
  const [resources, setResources] = useState<ResourceRow[]>([]);
  const [losses, setLosses] = useState<LossRow[]>([]);
  const [error, setError] = useState('');
  const blasts = useBlasts({ siteId, status: 'fired', pageSize: 50 });
  const haulage = useHaulage({ siteId, unassigned: true, pageSize: 100, dateFrom: extractedOn, dateTo: extractedOn });

  useEffect(() => {
    const l = existing.data;
    if (!l) return;
    setSiteId(l.siteId); setZoneId(l.zoneId ?? ''); setMaterialId(l.materialId); setStockpileId(l.stockpileId); setBlastId(l.blastId ?? '');
    setExtractedOn(l.extractedOn); setShift(l.shift); setSupervisor({ id: l.supervisorEmployeeId ?? '', label: l.supervisor?.name ?? '' });
    setOtherCost(Number(l.otherCost) ? l.otherCost : ''); setOtherCostNote(l.otherCostNote ?? ''); setNotes(l.notes ?? '');
    setLoads(l.loads.map((x) => ({ method: x.method, truckVehicleId: x.truckVehicleId ?? '', loadCount: x.loadCount ? String(x.loadCount) : '',
      capacityT: x.method === 'loads' && x.capacityT ? x.capacityT : '', weighbridgeTicketId: x.weighbridgeTicketId ?? '', ticketLabel: x.ticketNumber ?? '',
      haulageId: x.haulageId ?? '', tonnes: x.tonnes, notes: x.notes ?? '' })));
    setResources(l.resources.map((r) => ({ kind: r.kind, vehicleId: r.vehicleId ?? '', employeeId: r.employeeId ?? '', employeeLabel: r.employeeName ?? '',
      role: r.role ?? '', hours: r.hours ?? '', meterStart: r.meterStart ?? '', meterEnd: r.meterEnd ?? '', fuelLitres: r.fuelLitres ?? '', ratePerHour: r.ratePerHour ?? '' })));
    setLosses(l.losses.map((x) => ({ kind: x.kind, tonnes: x.tonnes, reason: x.reason ?? '' })));
  }, [existing.data]);

  const L = lookups.data;
  const zones = (L?.zones ?? []).filter((z) => z.siteId === siteId);
  const stockpiles = (L?.stockpiles ?? []).filter((k) => k.siteId === siteId && k.kind === 'rom' && k.isActive);
  const trucks = L?.trucks ?? [];
  const haulRows = useMemo(() => {
    const own = existing.data?.loads.filter((x) => x.haulageId).map((x) => ({ id: x.haulageId as string, code: x.haulageCode ?? '', tonnes: x.tonnes })) ?? [];
    const free = (haulage.data?.data ?? []).map((h) => ({ id: h.id, code: `${h.code} · ${h.truckCode ?? ''}`, tonnes: h.tonnes }));
    return [...own, ...free.filter((f) => !own.some((o) => o.id === f.id))];
  }, [haulage.data, existing.data]);

  const lineTonnes = (l: LoadRow): number => {
    if (l.method === 'loads') {
      const cap = l.capacityT ? Number(l.capacityT) : Number(trucks.find((t) => t.id === l.truckVehicleId)?.payloadCapacityT ?? 0);
      return (Number(l.loadCount) || 0) * cap;
    }
    if (l.method === 'haulage') return Number(haulRows.find((h) => h.id === l.haulageId)?.tonnes ?? 0);
    return Number(l.tonnes) || 0;
  };
  const gross = loads.reduce((a, l) => a + lineTonnes(l), 0);
  const loss = losses.reduce((a, l) => a + (Number(l.tonnes) || 0), 0);

  const setLoad = (i: number, patch: Partial<LoadRow>) => setLoads((xs) => xs.map((x, j) => (j === i ? { ...x, ...patch } : x)));
  const setRes = (i: number, patch: Partial<ResourceRow>) => setResources((xs) => xs.map((x, j) => (j === i ? { ...x, ...patch } : x)));
  const setLoss = (i: number, patch: Partial<LossRow>) => setLosses((xs) => xs.map((x, j) => (j === i ? { ...x, ...patch } : x)));

  const submit = async (post: boolean) => {
    setError('');
    if (!siteId || !materialId || !extractedOn) { setError(tx('Choose the site, the material and the date.')); return; }
    if (post && gross <= 0) { setError(tx('Add at least one quantity line.')); return; }
    const body: Record<string, unknown> = {
      siteId, zoneId: zoneId || null, materialId, stockpileId: stockpileId || null, blastId: blastId || null, extractedOn, shift,
      supervisorEmployeeId: supervisor.id || null, otherCost: n(otherCost) ?? null, otherCostNote: otherCostNote.trim() || null, notes: notes.trim() || null,
      loads: loads.filter((l) => lineTonnes(l) > 0 || l.weighbridgeTicketId).map((l) => ({
        method: l.method, truckVehicleId: l.truckVehicleId || null, loadCount: l.method === 'loads' ? n(l.loadCount) ?? null : null,
        capacityT: l.method === 'loads' ? n(l.capacityT) ?? null : null, weighbridgeTicketId: l.method === 'weighbridge' ? l.weighbridgeTicketId || null : null,
        haulageId: l.method === 'haulage' ? l.haulageId || null : null, tonnes: l.method === 'survey' ? n(l.tonnes) ?? null : null, notes: l.notes.trim() || null,
      })),
      resources: resources.filter((r) => r.vehicleId || r.employeeId).map((r) => ({
        kind: r.kind, vehicleId: r.kind === 'equipment' ? r.vehicleId || null : null, employeeId: r.employeeId || null, role: r.role.trim() || null,
        hours: n(r.hours) ?? null, meterStart: n(r.meterStart) ?? null, meterEnd: n(r.meterEnd) ?? null,
        fuelLitres: r.kind === 'equipment' ? n(r.fuelLitres) ?? null : null, ratePerHour: n(r.ratePerHour) ?? null,
      })),
      losses: losses.filter((l) => Number(l.tonnes) > 0).map((l) => ({ kind: l.kind, tonnes: Number(l.tonnes), reason: l.reason.trim() || null })),
    };
    try {
      if (id) {
        const saved = await update.mutateAsync({ id, body });
        notify.success(tx('Extraction log saved'));
        navigate(`/quarry/extraction/${saved.id}`);
      } else {
        const saved = await create.mutateAsync({ ...body, post });
        notify.success(post ? tx('Extraction posted into ROM stock') : tx('Extraction log saved'));
        navigate(`/quarry/extraction/${saved.id}`);
      }
    } catch (err) {
      setError(apiMessage(err, tx('Could not save the extraction log')));
    }
  };

  if (id && existing.isLoading) return <Skeleton className="h-96" />;
  const busy = create.isPending || update.isPending;
  const opts = (xs: Array<{ id: string; code: string; name?: string | null }>) => xs.map((x) => ({ value: x.id, label: [x.code, x.name].filter(Boolean).join(' · ') }));

  return (
    <div className="mx-auto max-w-4xl space-y-4 pb-24">
      <Button variant="ghost" size="sm" asChild><Link to="/quarry/extraction"><ArrowLeft className="mr-1 h-4 w-4" /> {tx('Extraction logs')}</Link></Button>
      <ListPageHeader icon={ClipboardList} title={id ? tx('Edit extraction log {{code}}', { code: existing.data?.code ?? '' }) : tx('New extraction log')}
        description={tx('One shift of extraction at a site / zone, into its ROM stockpile.')} />
      <Section title={tx('Shift')}>
        <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
          <Field label={tx('Site *')}>
            <OptionalSelect value={siteId} onChange={(v) => { setSiteId(v); setZoneId(''); setStockpileId(''); setBlastId(''); }} placeholder={tx('Choose the site')}
              options={(L?.sites ?? []).filter((s) => s.status !== 'closed').map((s) => ({ value: s.id, label: `${s.code} · ${s.name}` }))} />
          </Field>
          <Field label={tx('Zone / bench / face')}>
            <OptionalSelect value={zoneId} onChange={setZoneId} placeholder={tx('Whole site')} noneLabel={tx('Whole site')} options={opts(zones)} />
          </Field>
          <Field label={tx('Material *')}>
            <OptionalSelect value={materialId} onChange={setMaterialId} placeholder={tx('Choose the material')} options={opts((L?.materials ?? []).filter((m) => m.isActive))} />
          </Field>
          <Field label={tx('ROM stockpile')}>
            <OptionalSelect value={stockpileId} onChange={setStockpileId} placeholder={tx('Site default')} noneLabel={tx('Site default')} options={opts(stockpiles)} />
          </Field>
          <Field label={tx('Date *')}><Input type="date" value={extractedOn} onChange={(e) => setExtractedOn(e.target.value)} /></Field>
          <Field label={tx('Shift *')}>
            <OptionalSelect value={shift} onChange={(v) => setShift((v || 'day') as Shift)} placeholder={tx('Shift')} options={toOptions(SHIFT_LABEL)} />
          </Field>
          <Field label={tx('Supervisor')}>
            <EmployeePicker value={supervisor.id} label={supervisor.label} onChange={(i, l) => setSupervisor({ id: i, label: l })} />
          </Field>
          <Field label={tx('Blast (fired)')}>
            <OptionalSelect value={blastId} onChange={setBlastId} placeholder={tx('No blast')} noneLabel={tx('No blast')}
              options={(blasts.data?.data ?? []).map((b) => ({ value: b.id, label: `${b.code}${b.zoneCode ? ` · ${b.zoneCode}` : ''}` }))} />
          </Field>
        </div>
      </Section>

      <Section title={tx('Quantity')} actions={
        <>
          {(['loads', 'weighbridge', 'haulage', 'survey'] as LoadMethod[]).map((m) => (
            <Button key={m} size="sm" variant="outline" onClick={() => setLoads((xs) => [...xs, emptyLoad(m)])}><Plus className="mr-1 h-3.5 w-3.5" /> {METHOD_LABEL[m]}</Button>
          ))}
        </>
      }>
        {loads.length === 0 && <p className="text-sm text-muted-foreground">{tx('Add the loads, weighbridge tickets, haulage records or a survey figure.')}</p>}
        {loads.map((l, i) => (
          <div key={i} className="grid grid-cols-2 items-end gap-3 rounded-lg border p-3 sm:grid-cols-5">
            <div className="col-span-2 text-sm font-medium sm:col-span-5">{METHOD_LABEL[l.method]} · <span className="tabular-nums">{qty(lineTonnes(l), 3)} t</span></div>
            {l.method === 'loads' && (
              <>
                <Field label={tx('Truck')} className="col-span-2">
                  <OptionalSelect value={l.truckVehicleId} onChange={(v) => setLoad(i, { truckVehicleId: v })} placeholder={tx('Choose the truck')} noneLabel={tx('None')}
                    options={trucks.map((t) => ({ value: t.id, label: `${t.code}${t.registrationPlate ? ` · ${t.registrationPlate}` : ''} · ${qty(t.payloadCapacityT)} t` }))} />
                </Field>
                <Field label={tx('Loads')}><Input type="number" inputMode="numeric" min="1" value={l.loadCount} onChange={(e) => setLoad(i, { loadCount: e.target.value })} /></Field>
                <Field label={tx('t / load')}><Input type="number" inputMode="decimal" min="0" placeholder={tx('Truck payload')} value={l.capacityT} onChange={(e) => setLoad(i, { capacityT: e.target.value })} /></Field>
              </>
            )}
            {l.method === 'weighbridge' && (
              <Field label={tx('Ticket')} className="col-span-2 sm:col-span-4">
                <TicketChooser value={l.weighbridgeTicketId} label={l.ticketLabel} onPick={(tid, tl, t) => setLoad(i, { weighbridgeTicketId: tid, ticketLabel: tl, tonnes: t })} />
              </Field>
            )}
            {l.method === 'haulage' && (
              <Field label={tx('Haulage record')} className="col-span-2 sm:col-span-4">
                <OptionalSelect value={l.haulageId} onChange={(v) => setLoad(i, { haulageId: v })} placeholder={tx('Unassigned haulage of the day')}
                  options={haulRows.map((h) => ({ value: h.id, label: `${h.code} · ${qty(h.tonnes)} t` }))} />
              </Field>
            )}
            {l.method === 'survey' && (
              <Field label={tx('Tonnes')} className="col-span-2 sm:col-span-4"><Input type="number" inputMode="decimal" min="0" value={l.tonnes} onChange={(e) => setLoad(i, { tonnes: e.target.value })} /></Field>
            )}
            <Button variant="ghost" size="icon" aria-label={tx('Remove')} onClick={() => setLoads((xs) => xs.filter((_, j) => j !== i))}><Trash2 className="h-4 w-4" /></Button>
          </div>
        ))}
      </Section>

      <Section title={tx('Equipment & labour')} actions={
        <>
          <Button size="sm" variant="outline" onClick={() => setResources((xs) => [...xs, emptyResource('equipment')])}><Plus className="mr-1 h-3.5 w-3.5" /> {tx('Equipment')}</Button>
          <Button size="sm" variant="outline" onClick={() => setResources((xs) => [...xs, emptyResource('labour')])}><Plus className="mr-1 h-3.5 w-3.5" /> {tx('Labour')}</Button>
        </>
      }>
        {resources.length === 0 && <p className="text-sm text-muted-foreground">{tx('Excavators, loaders and crew of the shift (hours, fuel). Rates come from Quarry settings unless entered.')}</p>}
        {resources.map((r, i) => (
          <div key={i} className="grid grid-cols-2 items-end gap-3 rounded-lg border p-3 sm:grid-cols-4">
            {r.kind === 'equipment' ? (
              <Field label={tx('Equipment *')} className="col-span-2">
                <OptionalSelect value={r.vehicleId} onChange={(v) => setRes(i, { vehicleId: v })} placeholder={tx('Excavator, loader…')}
                  options={(L?.plant ?? []).map((v) => ({ value: v.id, label: `${v.code} · ${v.className}` }))} />
              </Field>
            ) : <Field label={tx('Role')} className="col-span-2"><Input value={r.role} onChange={(e) => setRes(i, { role: e.target.value })} /></Field>}
            <Field label={r.kind === 'equipment' ? tx('Operator') : tx('Employee *')} className="col-span-2">
              <EmployeePicker value={r.employeeId} label={r.employeeLabel} onChange={(eid, el) => setRes(i, { employeeId: eid, employeeLabel: el })} />
            </Field>
            {r.kind === 'equipment' && (
              <>
                <Field label={tx('Hour meter start')}><Input type="number" inputMode="decimal" value={r.meterStart} onChange={(e) => setRes(i, { meterStart: e.target.value })} /></Field>
                <Field label={tx('Hour meter end')}><Input type="number" inputMode="decimal" value={r.meterEnd} onChange={(e) => setRes(i, { meterEnd: e.target.value })} /></Field>
              </>
            )}
            <Field label={tx('Hours')}><Input type="number" inputMode="decimal" min="0" max="24" value={r.hours} onChange={(e) => setRes(i, { hours: e.target.value })} /></Field>
            {r.kind === 'equipment' && (
              <Field label={tx('Fuel (L)')}><Input type="number" inputMode="decimal" min="0" value={r.fuelLitres} onChange={(e) => setRes(i, { fuelLitres: e.target.value })} /></Field>
            )}
            <Field label={tx('Rate / hour')}><Input type="number" inputMode="decimal" min="0" placeholder={tx('Default')} value={r.ratePerHour} onChange={(e) => setRes(i, { ratePerHour: e.target.value })} /></Field>
            <Button variant="ghost" size="icon" aria-label={tx('Remove')} onClick={() => setResources((xs) => xs.filter((_, j) => j !== i))}><Trash2 className="h-4 w-4" /></Button>
          </div>
        ))}
      </Section>

      <Section title={tx('Losses')} actions={<Button size="sm" variant="outline" onClick={() => setLosses((xs) => [...xs, { kind: 'overburden', tonnes: '', reason: '' }])}><Plus className="mr-1 h-3.5 w-3.5" /> {tx('Add loss')}</Button>}>
        {losses.map((l, i) => (
          <div key={i} className="grid grid-cols-2 items-end gap-3 sm:grid-cols-4">
            <Field label={tx('Kind')}><OptionalSelect value={l.kind} onChange={(v) => setLoss(i, { kind: (v || 'other') as LossKind })} placeholder={tx('Kind')} options={toOptions(LOSS_LABEL)} /></Field>
            <Field label={tx('Tonnes')}><Input type="number" inputMode="decimal" min="0" value={l.tonnes} onChange={(e) => setLoss(i, { tonnes: e.target.value })} /></Field>
            <Field label={tx('Reason')}><Input value={l.reason} onChange={(e) => setLoss(i, { reason: e.target.value })} /></Field>
            <Button variant="ghost" size="icon" aria-label={tx('Remove')} onClick={() => setLosses((xs) => xs.filter((_, j) => j !== i))}><Trash2 className="h-4 w-4" /></Button>
          </div>
        ))}
        <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
          <Field label={tx('Other direct cost')}><Input type="number" inputMode="decimal" min="0" value={otherCost} onChange={(e) => setOtherCost(e.target.value)} /></Field>
          <Field label={tx('Other cost note')}><Input value={otherCostNote} onChange={(e) => setOtherCostNote(e.target.value)} /></Field>
          <Field label={tx('Notes')} className="sm:col-span-2"><Textarea rows={2} value={notes} onChange={(e) => setNotes(e.target.value)} /></Field>
        </div>
      </Section>

      <div className="sticky bottom-0 z-10 flex flex-wrap items-center justify-between gap-3 rounded-xl border bg-card/95 p-3 shadow-lg backdrop-blur">
        <div className="text-sm tabular-nums">
          {tx('Gross')} <b>{qty(gross, 3)} t</b> · {tx('Losses')} <b>{qty(loss, 3)} t</b> · {tx('Net')} <b>{qty(gross - loss, 3)} t</b>
          {error && <div className="text-destructive">{error}</div>}
        </div>
        <div className="flex gap-2">
          <Button variant="outline" disabled={busy} onClick={() => submit(false)}>{tx('Save draft')}</Button>
          {canPost && !id && <Button disabled={busy} onClick={() => submit(true)}>{tx('Save & post')}</Button>}
        </div>
      </div>
    </div>
  );
}

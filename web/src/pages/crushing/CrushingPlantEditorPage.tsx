import { useEffect, useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { ArrowLeft, Cog, Plus, Trash2 } from 'lucide-react';
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
  METER_LABEL, STAGE_KIND_LABEL, UNIT_KIND_LABEL, useCrushingLookups, useDeletePlant, usePlant, useSavePlant,
} from '@/features/crushing/api';
import { ProfileStatusPill, Section, num, pct, toOptions } from './crushing-ui';

interface UnitRow { code: string; name: string; kind: string; capacityTph: string; powerKw: string; meterKind: string }
interface StageRow { name: string; kind: string; capacityTph: string; units: UnitRow[] }
const s = (v: string | number | null | undefined) => (v === null || v === undefined ? '' : String(v));
const newUnit = (): UnitRow => ({ code: '', name: '', kind: 'other', capacityTph: '', powerKw: '', meterKind: 'engine_hours' });
const DEFAULT_STAGES: StageRow[] = [
  { name: tx('Primary crushing'), kind: 'primary', capacityTph: '', units: [] },
  { name: tx('Secondary crushing'), kind: 'secondary', capacityTph: '', units: [] },
  { name: tx('Screening'), kind: 'screening', capacityTph: '', units: [] },
];

/** Plant set-up: rates that cost a run and the circuit (ordered stages with their equipment); its grading profiles. */
export function CrushingPlantEditorPage() {
  const { id } = useParams();
  const navigate = useNavigate();
  const canSetup = useAuthStore((st) => st.hasPermission(PERMISSIONS.crushing.setup));
  const lookups = useCrushingLookups();
  const existing = usePlant(id);
  const save = useSavePlant();
  const remove = useDeletePlant();
  const [form, setForm] = useState({ code: '', name: '', siteId: '', feedStockpileId: '', designCapacityTph: '', dieselCostPerLitre: '', electricityCostPerKwh: '', labourRatePerHour: '', notes: '', isActive: true });
  const [stages, setStages] = useState<StageRow[]>(DEFAULT_STAGES);
  const [loaded, setLoaded] = useState(!id);
  const [error, setError] = useState('');

  useEffect(() => {
    const p = existing.data;
    if (!id || !p || loaded) return;
    setForm({ code: p.code, name: p.name, siteId: p.siteId ?? '', feedStockpileId: p.feedStockpileId ?? '', designCapacityTph: s(p.designCapacityTph),
      dieselCostPerLitre: s(p.dieselCostPerLitre), electricityCostPerKwh: s(p.electricityCostPerKwh), labourRatePerHour: s(p.labourRatePerHour), notes: p.notes ?? '', isActive: p.isActive });
    setStages(p.stages.map((st) => ({ name: st.name, kind: st.kind, capacityTph: s(st.capacityTph), units: st.units.filter((u) => u.isActive).map((u) => ({
      code: u.code, name: u.name, kind: u.kind, capacityTph: s(u.capacityTph), powerKw: s(u.powerKw), meterKind: u.meterKind })) })));
    setLoaded(true);
  }, [id, existing.data, loaded]);

  if (id && !loaded) return <Skeleton className="h-96 w-full" />;
  const set = (k: keyof typeof form, v: string | boolean) => setForm((f) => ({ ...f, [k]: v }));
  const setStage = (i: number, patch: Partial<StageRow>) => setStages((x) => x.map((r, j) => (j === i ? { ...r, ...patch } : r)));
  const setUnit = (i: number, k: number, patch: Partial<UnitRow>) => setStage(i, { units: stages[i].units.map((u, j) => (j === k ? { ...u, ...patch } : u)) });
  const piles = (lookups.data?.stockpiles ?? []).filter((k) => !form.siteId || k.siteId === form.siteId);

  const submit = async () => {
    setError('');
    if (!form.name.trim() || stages.length === 0 || stages.some((st) => !st.name.trim() || st.units.some((u) => !u.code.trim() || !u.name.trim()))) {
      setError(tx('Give the plant a name and every stage and unit a name (units also a code).'));
      return;
    }
    const body = {
      code: form.code.trim() || null, name: form.name.trim(), siteId: form.siteId || null, feedStockpileId: form.feedStockpileId || null,
      designCapacityTph: num(form.designCapacityTph) ?? null, dieselCostPerLitre: num(form.dieselCostPerLitre) ?? 0,
      electricityCostPerKwh: num(form.electricityCostPerKwh) ?? 0, labourRatePerHour: num(form.labourRatePerHour) ?? 0,
      isActive: form.isActive, notes: form.notes.trim() || null,
      stages: stages.map((st) => ({ name: st.name.trim(), kind: st.kind, capacityTph: num(st.capacityTph) ?? null,
        units: st.units.map((u) => ({ code: u.code.trim(), name: u.name.trim(), kind: u.kind, capacityTph: num(u.capacityTph) ?? null, powerKw: num(u.powerKw) ?? null, meterKind: u.meterKind })) })),
    };
    try {
      const saved = await save.mutateAsync({ id, body });
      notify.success(tx('Saved'));
      setLoaded(false);
      if (!id) navigate(`/manufacturing/crushing/plants/${saved.id}`);
    } catch (err) {
      setError(apiMessage(err, tx('Could not save')));
    }
  };

  return (
    <div className="space-y-4">
      <ListPageHeader icon={Cog} title={id ? `${form.code} · ${form.name}` : tx('New plant')} description={tx('Crushing plant and circuit set-up')}
        actions={<div className="flex gap-2">
          <Button variant="outline" asChild><Link to="/manufacturing/crushing/plants"><ArrowLeft className="mr-1.5 h-4 w-4" />{tx('Back')}</Link></Button>
          {id && canSetup && <Button variant="outline" disabled={remove.isPending} onClick={async () => {
            try { await remove.mutateAsync(id); navigate('/manufacturing/crushing/plants'); } catch (err) { notify.error(apiMessage(err, tx('Could not delete'))); }
          }}><Trash2 className="mr-1.5 h-4 w-4" />{tx('Delete')}</Button>}
        </div>} />
      <Section title={tx('Plant')}>
        <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-4">
          <Field label={tx('Code')}><Input value={form.code} placeholder={tx('Automatic')} onChange={(e) => set('code', e.target.value)} /></Field>
          <Field label={tx('Name *')}><Input value={form.name} onChange={(e) => set('name', e.target.value)} /></Field>
          <Field label={tx('Site')}><OptionalSelect value={form.siteId} onChange={(v) => set('siteId', v)} placeholder={tx('Choose…')} noneLabel={tx('None')} options={(lookups.data?.sites ?? []).map((x) => ({ value: x.id, label: `${x.code} · ${x.name}` }))} /></Field>
          <Field label={tx('Default feed stockpile')}><OptionalSelect value={form.feedStockpileId} onChange={(v) => set('feedStockpileId', v)} placeholder={tx('Choose…')} noneLabel={tx('None')} options={piles.map((k) => ({ value: k.id, label: `${k.siteCode} · ${k.code}` }))} /></Field>
          <Field label={tx('Design capacity (t/h)')}><Input type="number" inputMode="decimal" min={0} step="any" value={form.designCapacityTph} onChange={(e) => set('designCapacityTph', e.target.value)} /></Field>
          <Field label={tx('Diesel cost per litre')}><Input type="number" inputMode="decimal" min={0} step="any" value={form.dieselCostPerLitre} onChange={(e) => set('dieselCostPerLitre', e.target.value)} /></Field>
          <Field label={tx('Electricity cost per kWh')}><Input type="number" inputMode="decimal" min={0} step="any" value={form.electricityCostPerKwh} onChange={(e) => set('electricityCostPerKwh', e.target.value)} /></Field>
          <Field label={tx('Labour rate per hour')}><Input type="number" inputMode="decimal" min={0} step="any" value={form.labourRatePerHour} onChange={(e) => set('labourRatePerHour', e.target.value)} /></Field>
          <Field label={tx('Notes')} className="sm:col-span-2"><Textarea rows={2} value={form.notes} onChange={(e) => set('notes', e.target.value)} /></Field>
          <label className="flex items-center gap-2 text-sm"><input type="checkbox" checked={form.isActive} onChange={(e) => set('isActive', e.target.checked)} /> {tx('Active')}</label>
        </div>
      </Section>
      <Section title={tx('Circuit')} actions={<Button variant="outline" size="sm" onClick={() => setStages((x) => [...x, { name: '', kind: 'other', capacityTph: '', units: [] }])}><Plus className="mr-1 h-4 w-4" />{tx('Add stage')}</Button>}>
        <p className="text-xs text-muted-foreground">{tx('Stages run in this order; each unit becomes a work centre. Changing the stages creates a new routing version.')}</p>
        {stages.map((st, i) => (
          <div key={i} className="space-y-2 rounded-lg border p-3">
            <div className="grid grid-cols-1 gap-2 sm:grid-cols-[3rem_1fr_12rem_8rem_auto]">
              <span className="self-center text-sm font-semibold">{i + 1}.</span>
              <Input placeholder={tx('Stage name')} value={st.name} onChange={(e) => setStage(i, { name: e.target.value })} />
              <OptionalSelect value={st.kind} onChange={(v) => setStage(i, { kind: v || 'other' })} placeholder={tx('Choose…')} options={toOptions(STAGE_KIND_LABEL)} />
              <Input type="number" inputMode="decimal" placeholder={tx('t/h')} value={st.capacityTph} onChange={(e) => setStage(i, { capacityTph: e.target.value })} />
              <Button variant="ghost" size="icon" aria-label={tx('Remove')} onClick={() => setStages((x) => x.filter((_, j) => j !== i))}><Trash2 className="h-4 w-4" /></Button>
            </div>
            {st.units.map((u, k) => (
              <div key={k} className="grid grid-cols-2 gap-2 pl-0 sm:grid-cols-[7rem_1fr_10rem_6rem_6rem_10rem_auto] sm:pl-12">
                <Input placeholder={tx('Code')} value={u.code} onChange={(e) => setUnit(i, k, { code: e.target.value })} />
                <Input placeholder={tx('Name')} value={u.name} onChange={(e) => setUnit(i, k, { name: e.target.value })} />
                <OptionalSelect value={u.kind} onChange={(v) => setUnit(i, k, { kind: v || 'other' })} placeholder={tx('Choose…')} options={toOptions(UNIT_KIND_LABEL)} />
                <Input type="number" inputMode="decimal" placeholder={tx('t/h')} value={u.capacityTph} onChange={(e) => setUnit(i, k, { capacityTph: e.target.value })} />
                <Input type="number" inputMode="decimal" placeholder="kW" value={u.powerKw} onChange={(e) => setUnit(i, k, { powerKw: e.target.value })} />
                <OptionalSelect value={u.meterKind} onChange={(v) => setUnit(i, k, { meterKind: v || 'none' })} placeholder={tx('Choose…')} options={toOptions(METER_LABEL)} />
                <Button variant="ghost" size="icon" aria-label={tx('Remove')} onClick={() => setStage(i, { units: st.units.filter((_, j) => j !== k) })}><Trash2 className="h-4 w-4" /></Button>
              </div>
            ))}
            <Button variant="ghost" size="sm" className="sm:ml-12" onClick={() => setStage(i, { units: [...st.units, newUnit()] })}><Plus className="mr-1 h-4 w-4" />{tx('Add unit')}</Button>
          </div>
        ))}
      </Section>
      {error && <p className="rounded-md border border-destructive/40 bg-destructive/10 p-3 text-sm text-destructive">{error}</p>}
      {canSetup && <div className="flex justify-end"><Button onClick={submit} disabled={save.isPending}>{tx('Save')}</Button></div>}
      {id && existing.data && (
        <Section title={tx('Grading profiles')} actions={canSetup && <Button size="sm" onClick={() => navigate(`/manufacturing/crushing/profiles/new?plantId=${id}`)}><Plus className="mr-1 h-4 w-4" />{tx('New grading profile')}</Button>}>
          {existing.data.profiles.length === 0 ? <p className="text-sm text-muted-foreground">{tx('No grading profile yet: define the expected size split of the feed.')}</p> : (
            <div className="space-y-2">
              {existing.data.profiles.map((g) => (
                <Link key={g.id} to={`/manufacturing/crushing/profiles/${g.id}`} className="flex items-center justify-between gap-2 rounded-lg border p-2 text-sm hover:bg-accent">
                  <span>{g.code} v{g.version} · {g.name} · {g.feedProductName}</span>
                  <span className="flex items-center gap-2">{pct(g.expectedRecoveryPct)} <ProfileStatusPill status={g.status} /></span>
                </Link>
              ))}
            </div>
          )}
        </Section>
      )}
    </div>
  );
}

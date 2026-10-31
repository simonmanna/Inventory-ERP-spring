import { useEffect, useMemo, useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { ArrowLeft, Factory, Plus, Trash2 } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Textarea } from '@/components/ui/textarea';
import { Skeleton } from '@/components/ui/skeleton';
import { ListPageHeader } from '@/components/list';
import { notify, apiMessage } from '@/lib/notify';
import { tx } from '@/lib/i18n/tx';
import { Field, OptionalSelect } from '@/pages/fleet/fleet-ui';
import {
  FEED_METHOD_LABEL, OUTPUT_METHOD_LABEL, ROLE_LABEL, SHIFT_LABEL, useCrushingLookups, usePlant, useProfile, useRun, useSaveRun,
  type Shift,
} from '@/features/crushing/api';
import { Section, num, pct, qty, toOptions, today } from './crushing-ui';

interface MeterRow { hoursStart: string; hoursEnd: string; kwhStart: string; kwhEnd: string; dieselLitres: string }
interface CostRow { kind: 'wear_parts' | 'maintenance' | 'other'; amount: string; description: string }
const MANUAL_COST: Record<CostRow['kind'], string> = { wear_parts: tx('Wear parts'), maintenance: tx('Maintenance'), other: tx('Other cost') };
const emptyMeter = (): MeterRow => ({ hoursStart: '', hoursEnd: '', kwhStart: '', kwhEnd: '', dieselLitres: '' });
const s = (v: string | number | null | undefined) => (v === null || v === undefined ? '' : String(v));

/**
 * Plant-operator entry (phone / tablet at the plant): plant, grading profile, shift, feed from the ROM stockpile,
 * actual output per size fraction (tonnes, or % of feed for a survey split), recirculated oversize, hours and
 * downtime, meter readings per unit and wear-part / maintenance costs. Saved as a draft for the supervisor to post.
 */
export function CrushingRunEntryPage() {
  const { id } = useParams();
  const navigate = useNavigate();
  const lookups = useCrushingLookups();
  const existing = useRun(id);
  const save = useSaveRun();
  const [plantId, setPlantId] = useState('');
  const [profileId, setProfileId] = useState('');
  const [runDate, setRunDate] = useState(today());
  const [shift, setShift] = useState<Shift>('day');
  const [operatorName, setOperatorName] = useState('');
  const [feedStockpileId, setFeedStockpileId] = useState('');
  const [feedTonnes, setFeedTonnes] = useState('');
  const [feedMethod, setFeedMethod] = useState('belt_scale');
  const [outputMethod, setOutputMethod] = useState('belt_scale');
  const [outputs, setOutputs] = useState<Record<string, string>>({});
  const [recirculated, setRecirculated] = useState('');
  const [operatingHours, setOperatingHours] = useState('');
  const [downtimeHours, setDowntimeHours] = useState('');
  const [downtimeReason, setDowntimeReason] = useState('');
  const [labourHours, setLabourHours] = useState('');
  const [meters, setMeters] = useState<Record<string, MeterRow>>({});
  const [costs, setCosts] = useState<CostRow[]>([]);
  const [notes, setNotes] = useState('');
  const [error, setError] = useState('');
  const [loaded, setLoaded] = useState(!id);

  const plant = usePlant(plantId || undefined);
  const plantRef = lookups.data?.plants.find((p) => p.id === plantId);
  const profile = useProfile(profileId || undefined);
  const byPct = outputMethod === 'survey_pct';

  useEffect(() => {
    const r = existing.data;
    if (!id || !r || loaded) return;
    setPlantId(r.plantId); setProfileId(r.profileId); setRunDate(r.runDate.slice(0, 10)); setShift(r.shift);
    setOperatorName(r.operatorName ?? ''); setFeedStockpileId(r.feedStockpileId); setFeedTonnes(s(r.feedTonnes));
    setFeedMethod(r.feedMethod); setOutputMethod(r.outputMethod); setRecirculated(s(r.recirculatedTonnes));
    setOperatingHours(s(r.operatingHours)); setDowntimeHours(s(r.downtimeHours)); setDowntimeReason(r.downtimeReason ?? '');
    setLabourHours(s(r.labourHours)); setNotes(r.notes ?? '');
    setOutputs(Object.fromEntries(r.outputs.map((o) => [o.gradingLineId, r.outputMethod === 'survey_pct' ? s(o.actualPct) : s(o.actualTonnes)])));
    setMeters(Object.fromEntries(r.meters.map((m) => [m.unitId, { hoursStart: s(m.hoursStart), hoursEnd: s(m.hoursEnd), kwhStart: s(m.kwhStart), kwhEnd: s(m.kwhEnd), dieselLitres: s(m.dieselLitres) }])));
    setCosts(r.costs.filter((c) => !c.auto).map((c) => ({ kind: c.kind as CostRow['kind'], amount: s(c.amount), description: c.description ?? '' })));
    setLoaded(true);
  }, [id, existing.data, loaded]);

  // A new run picks the plant's default feed stockpile and its (first) active profile.
  useEffect(() => {
    if (!plantRef || id) return;
    if (!feedStockpileId && plantRef.feedStockpileId) setFeedStockpileId(plantRef.feedStockpileId);
    if (!profileId && plantRef.profiles.length) setProfileId(plantRef.profiles[0].id);
  }, [plantRef, id, feedStockpileId, profileId]);

  const lines = useMemo(() => profile.data?.lines ?? [], [profile.data]);
  const meteredUnits = (plant.data?.stages ?? []).flatMap((st) => st.units.filter((u) => u.isActive && u.meterKind !== 'none').map((u) => ({ ...u, stageName: st.name })));
  const feed = Number(feedTonnes) || 0;
  const totals = useMemo(() => {
    const out = lines.reduce((sum, l) => {
      const v = Number(outputs[l.id] ?? '') || 0;
      return sum + (byPct ? (feed * v) / 100 : v);
    }, 0);
    return { out, loss: feed - out, yieldPct: feed > 0 ? (out / feed) * 100 : null };
  }, [lines, outputs, feed, byPct]);

  const submit = async () => {
    setError('');
    if (!plantId || !profileId || !feedStockpileId || !(feed > 0)) {
      setError(tx('Choose the plant, grading profile and feed stockpile, and enter the feed tonnes.'));
      return;
    }
    if (totals.loss < -0.0005) {
      setError(tx('The outputs exceed the feed.'));
      return;
    }
    const body = {
      plantId, profileId, runDate, shift, operatorName: operatorName.trim() || null, feedStockpileId, feedTonnes: feed, feedMethod, outputMethod,
      recirculatedTonnes: num(recirculated) ?? 0, operatingHours: num(operatingHours) ?? null, downtimeHours: num(downtimeHours) ?? 0,
      downtimeReason: downtimeReason.trim() || null, labourHours: num(labourHours) ?? 0, notes: notes.trim() || null,
      outputs: lines.map((l) => (byPct ? { gradingLineId: l.id, actualPct: num(outputs[l.id] ?? '') ?? 0 } : { gradingLineId: l.id, actualTonnes: num(outputs[l.id] ?? '') ?? 0 })),
      meters: meteredUnits.filter((u) => meters[u.id] && Object.values(meters[u.id]).some((v) => v.trim() !== '')).map((u) => {
        const m = meters[u.id];
        return { unitId: u.id, hoursStart: num(m.hoursStart), hoursEnd: num(m.hoursEnd), kwhStart: num(m.kwhStart), kwhEnd: num(m.kwhEnd), dieselLitres: num(m.dieselLitres) };
      }),
      costs: costs.filter((c) => c.amount.trim() !== '').map((c) => ({ kind: c.kind, amount: Number(c.amount), description: c.description.trim() || null })),
    };
    try {
      const saved = await save.mutateAsync({ id, body });
      notify.success(tx('Saved'));
      navigate(`/manufacturing/crushing/runs/${saved.id}`);
    } catch (err) {
      setError(apiMessage(err, tx('Could not save')));
    }
  };

  if (id && !loaded) return <Skeleton className="h-96 w-full" />;
  const setMeter = (unitId: string, k: keyof MeterRow, v: string) => setMeters((x) => ({ ...x, [unitId]: { ...(x[unitId] ?? emptyMeter()), [k]: v } }));
  return (
    <div className="space-y-4 pb-24">
      <ListPageHeader icon={Factory} title={id ? tx('Edit crushing run') : tx('New crushing run')}
        description={tx('Record the shift: feed, output per size fraction, hours and meter readings.')}
        actions={<Button variant="outline" asChild><Link to={id ? `/manufacturing/crushing/runs/${id}` : '/manufacturing/crushing'}><ArrowLeft className="mr-1.5 h-4 w-4" />{tx('Back')}</Link></Button>} />

      <Section title={tx('Shift')}>
        <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3">
          <Field label={tx('Plant *')}>
            <OptionalSelect value={plantId} onChange={(v) => { setPlantId(v); setProfileId(''); setFeedStockpileId(''); }} placeholder={tx('Choose…')}
              options={(lookups.data?.plants ?? []).map((p) => ({ value: p.id, label: `${p.code} · ${p.name}` }))} />
          </Field>
          <Field label={tx('Grading profile *')}>
            <OptionalSelect value={profileId} onChange={setProfileId} placeholder={tx('Choose…')}
              options={(plantRef?.profiles ?? []).map((p) => ({ value: p.id, label: `${p.code} v${p.version} · ${p.feedProductName}` }))} />
          </Field>
          <Field label={tx('Date *')}><Input type="date" value={runDate} max={today()} onChange={(e) => setRunDate(e.target.value)} /></Field>
          <Field label={tx('Shift')}><OptionalSelect value={shift} onChange={(v) => setShift((v || 'day') as Shift)} placeholder={tx('Choose…')} options={toOptions(SHIFT_LABEL)} /></Field>
          <Field label={tx('Operator')}><Input value={operatorName} onChange={(e) => setOperatorName(e.target.value)} /></Field>
        </div>
      </Section>

      <Section title={tx('Feed')}>
        <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-4">
          <Field label={tx('Feed stockpile *')}>
            <OptionalSelect value={feedStockpileId} onChange={setFeedStockpileId} placeholder={tx('Choose…')}
              options={(lookups.data?.stockpiles ?? []).map((k) => ({ value: k.id, label: `${k.siteCode} · ${k.code} · ${k.name}` }))} />
          </Field>
          <Field label={tx('Feed tonnes *')}><Input type="number" inputMode="decimal" min={0} step="any" value={feedTonnes} onChange={(e) => setFeedTonnes(e.target.value)} /></Field>
          <Field label={tx('Measured by')}><OptionalSelect value={feedMethod} onChange={(v) => setFeedMethod(v || 'belt_scale')} placeholder={tx('Choose…')} options={toOptions(FEED_METHOD_LABEL)} /></Field>
          <Field label={tx('Recirculated oversize (t)')}><Input type="number" inputMode="decimal" min={0} step="any" value={recirculated} onChange={(e) => setRecirculated(e.target.value)} /></Field>
        </div>
      </Section>

      <Section title={tx('Output per size fraction')} actions={
        <div className="w-56"><OptionalSelect value={outputMethod} onChange={(v) => setOutputMethod(v || 'belt_scale')} placeholder={tx('Choose…')} options={toOptions(OUTPUT_METHOD_LABEL)} /></div>
      }>
        {!profileId && <p className="text-sm text-muted-foreground">{tx('Choose a grading profile to list its size fractions.')}</p>}
        <div className="space-y-2">
          {lines.map((l) => (
            <div key={l.id} className="grid grid-cols-[1fr_8rem] items-center gap-3 rounded-lg border p-2 sm:grid-cols-[1fr_10rem_8rem]">
              <div className="min-w-0">
                <div className="truncate font-medium">{l.sizeLabel ? `${l.sizeLabel} · ` : ''}{l.productName}</div>
                <div className="text-xs text-muted-foreground">{ROLE_LABEL[l.role]} · {tx('expected')} {pct(l.expectedPct)}{feed > 0 ? ` = ${qty((feed * Number(l.expectedPct)) / 100)} t` : ''}{l.stockpileCode ? ` → ${l.stockpileCode}` : ''}</div>
              </div>
              <span className="hidden text-right text-xs text-muted-foreground sm:block">{l.stageSequence ? tx('Stage {{n}}', { n: l.stageSequence }) : tx('Last stage')}</span>
              <Input type="number" inputMode="decimal" min={0} step="any" aria-label={l.productName} placeholder={byPct ? '%' : 't'}
                value={outputs[l.id] ?? ''} onChange={(e) => setOutputs((x) => ({ ...x, [l.id]: e.target.value }))} />
            </div>
          ))}
        </div>
      </Section>

      <Section title={tx('Hours and meters')}>
        <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-4">
          <Field label={tx('Operating hours')}><Input type="number" inputMode="decimal" min={0} max={24} step="any" value={operatingHours} onChange={(e) => setOperatingHours(e.target.value)} /></Field>
          <Field label={tx('Downtime hours')}><Input type="number" inputMode="decimal" min={0} max={24} step="any" value={downtimeHours} onChange={(e) => setDowntimeHours(e.target.value)} /></Field>
          <Field label={tx('Downtime reason')}><Input value={downtimeReason} onChange={(e) => setDowntimeReason(e.target.value)} /></Field>
          <Field label={tx('Crew labour hours')}><Input type="number" inputMode="decimal" min={0} step="any" value={labourHours} onChange={(e) => setLabourHours(e.target.value)} /></Field>
        </div>
        <div className="space-y-2">
          {meteredUnits.map((u) => {
            const m = meters[u.id] ?? emptyMeter();
            const hours = u.meterKind === 'engine_hours' || u.meterKind === 'both';
            const kwh = u.meterKind === 'kwh' || u.meterKind === 'both';
            return (
              <div key={u.id} className="rounded-lg border p-2">
                <div className="mb-2 text-sm font-medium">{u.code} · {u.name} <span className="text-xs text-muted-foreground">({u.stageName})</span></div>
                <div className="grid grid-cols-2 gap-2 sm:grid-cols-5">
                  {hours && <Input type="number" inputMode="decimal" step="any" placeholder={tx('Hours start')} value={m.hoursStart} onChange={(e) => setMeter(u.id, 'hoursStart', e.target.value)} />}
                  {hours && <Input type="number" inputMode="decimal" step="any" placeholder={tx('Hours end')} value={m.hoursEnd} onChange={(e) => setMeter(u.id, 'hoursEnd', e.target.value)} />}
                  {kwh && <Input type="number" inputMode="decimal" step="any" placeholder={tx('kWh start')} value={m.kwhStart} onChange={(e) => setMeter(u.id, 'kwhStart', e.target.value)} />}
                  {kwh && <Input type="number" inputMode="decimal" step="any" placeholder={tx('kWh end')} value={m.kwhEnd} onChange={(e) => setMeter(u.id, 'kwhEnd', e.target.value)} />}
                  {hours && <Input type="number" inputMode="decimal" step="any" placeholder={tx('Diesel litres')} value={m.dieselLitres} onChange={(e) => setMeter(u.id, 'dieselLitres', e.target.value)} />}
                </div>
              </div>
            );
          })}
        </div>
      </Section>

      <Section title={tx('Wear parts and maintenance')} actions={
        <Button variant="outline" size="sm" onClick={() => setCosts((c) => [...c, { kind: 'wear_parts', amount: '', description: '' }])}><Plus className="mr-1 h-4 w-4" />{tx('Add cost')}</Button>
      }>
        {costs.length === 0 && <p className="text-sm text-muted-foreground">{tx('Diesel, electricity and crew labour are costed from the readings above.')}</p>}
        {costs.map((c, i) => (
          <div key={i} className="grid grid-cols-[1fr_7rem_auto] gap-2 sm:grid-cols-[12rem_8rem_1fr_auto]">
            <OptionalSelect value={c.kind} onChange={(v) => setCosts((x) => x.map((r, j) => (j === i ? { ...r, kind: (v || 'wear_parts') as CostRow['kind'] } : r)))} placeholder={tx('Choose…')} options={toOptions(MANUAL_COST)} />
            <Input type="number" inputMode="decimal" min={0} step="any" placeholder={tx('Amount')} value={c.amount} onChange={(e) => setCosts((x) => x.map((r, j) => (j === i ? { ...r, amount: e.target.value } : r)))} />
            <Input className="col-span-2 sm:col-span-1" placeholder={tx('Description')} value={c.description} onChange={(e) => setCosts((x) => x.map((r, j) => (j === i ? { ...r, description: e.target.value } : r)))} />
            <Button variant="ghost" size="icon" aria-label={tx('Remove')} onClick={() => setCosts((x) => x.filter((_, j) => j !== i))}><Trash2 className="h-4 w-4" /></Button>
          </div>
        ))}
        <Field label={tx('Notes')}><Textarea rows={2} value={notes} onChange={(e) => setNotes(e.target.value)} /></Field>
      </Section>

      {error && <p className="rounded-md border border-destructive/40 bg-destructive/10 p-3 text-sm text-destructive">{error}</p>}
      <div className="fixed inset-x-0 bottom-0 z-20 border-t bg-background/95 p-3 backdrop-blur">
        <div className="mx-auto flex max-w-5xl flex-wrap items-center justify-between gap-3">
          <div className="flex flex-wrap gap-4 text-sm tabular-nums">
            <span>{tx('Output')}: <b>{qty(totals.out)} t</b></span>
            <span className={totals.loss < 0 ? 'text-rose-600' : ''}>{tx('Loss')}: <b>{qty(totals.loss)} t</b></span>
            <span>{tx('Yield')}: <b>{pct(totals.yieldPct)}</b></span>
          </div>
          <Button onClick={submit} disabled={save.isPending}>{tx('Save draft')}</Button>
        </div>
      </div>
    </div>
  );
}

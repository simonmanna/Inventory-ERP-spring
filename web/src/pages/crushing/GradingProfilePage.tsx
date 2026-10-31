import { useEffect, useState } from 'react';
import { Link, useNavigate, useParams, useSearchParams } from 'react-router-dom';
import { ArrowLeft, CheckCircle2, Copy, Plus, Ruler, Trash2 } from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Skeleton } from '@/components/ui/skeleton';
import { ListPageHeader } from '@/components/list';
import { useAuthStore } from '@/stores/auth.store';
import { notify, apiMessage } from '@/lib/notify';
import { tx } from '@/lib/i18n/tx';
import { Field, OptionalSelect } from '@/pages/fleet/fleet-ui';
import {
  ALLOCATION_LABEL, ROLE_LABEL, VALUATION_LABEL, useCrushingLookups, useDeleteProfile, usePlant, useProfile, useProfileAction, useSaveProfile,
  type FractionRole,
} from '@/features/crushing/api';
import { CrushingProductPicker, ProfileStatusPill, Section, num, pct, toOptions } from './crushing-ui';

interface LineRow { productId: string; productLabel: string; sizeLabel: string; role: FractionRole; stageSequence: string; expectedPct: string; stocked: boolean; stockpileId: string; valuation: string; nrvUnitValue: string }
const s = (v: string | number | null | undefined) => (v === null || v === undefined ? '' : String(v));
const newLine = (role: FractionRole): LineRow => ({ productId: '', productLabel: '', sizeLabel: '', role, stageSequence: '', expectedPct: '', stocked: true, stockpileId: '', valuation: '', nrvUnitValue: '' });

/** Grading profile: expected yield split of a plant × feed (one line per size fraction); versioned, activation builds the BOM. */
export function GradingProfilePage() {
  const { id } = useParams();
  const [params] = useSearchParams();
  const navigate = useNavigate();
  const canSetup = useAuthStore((st) => st.hasPermission(PERMISSIONS.crushing.setup));
  const lookups = useCrushingLookups();
  const existing = useProfile(id);
  const save = useSaveProfile();
  const action = useProfileAction();
  const remove = useDeleteProfile();
  const [plantId, setPlantId] = useState(params.get('plantId') ?? '');
  const [head, setHead] = useState({ code: '', name: '', feedProductId: '', feedLabel: '', allocationMethod: 'quantity', notes: '' });
  const [lines, setLines] = useState<LineRow[]>([newLine('main')]);
  const [loaded, setLoaded] = useState(!id);
  const [error, setError] = useState('');
  const plant = usePlant(plantId || undefined);

  useEffect(() => {
    const g = existing.data;
    if (!id || !g || loaded) return;
    setPlantId(g.plantId);
    setHead({ code: g.code, name: g.name, feedProductId: g.feedProductId, feedLabel: g.feedProductName, allocationMethod: g.allocationMethod, notes: g.notes ?? '' });
    setLines(g.lines.map((l) => ({ productId: l.productId, productLabel: l.productName, sizeLabel: l.sizeLabel ?? '', role: l.role, stageSequence: s(l.stageSequence),
      expectedPct: s(l.expectedPct), stocked: l.stocked, stockpileId: l.stockpileId ?? '', valuation: l.valuation, nrvUnitValue: s(l.nrvUnitValue) })));
    setLoaded(true);
  }, [id, existing.data, loaded]);

  if (id && !loaded) return <Skeleton className="h-96 w-full" />;
  const g = existing.data;
  const editable = !id || g?.status === 'draft';
  const total = lines.reduce((sum, l) => sum + (Number(l.expectedPct) || 0), 0);
  const setLine = (i: number, patch: Partial<LineRow>) => setLines((x) => x.map((r, j) => (j === i ? { ...r, ...patch } : r)));
  const productPiles = (lookups.data?.stockpiles ?? []).filter((k) => k.kind === 'product');
  const stageOptions = (plant.data?.stages ?? []).map((st) => ({ value: String(st.sequence), label: `${st.sequence}. ${st.name}` }));

  const submit = async () => {
    setError('');
    if (!plantId || !head.name.trim() || !head.feedProductId || lines.some((l) => !l.productId || !(Number(l.expectedPct) > 0))) {
      setError(tx('Choose the plant and the feed material, name the profile, and give every line a product and an expected %.'));
      return;
    }
    const body = {
      plantId, code: head.code.trim() || null, name: head.name.trim(), feedProductId: head.feedProductId, allocationMethod: head.allocationMethod, notes: head.notes.trim() || null,
      lines: lines.map((l) => ({ productId: l.productId, sizeLabel: l.sizeLabel.trim() || null, role: l.role, stageSequence: l.role === 'main' ? null : num(l.stageSequence) ?? null,
        expectedPct: Number(l.expectedPct), stocked: l.stocked, stockpileId: l.stocked ? l.stockpileId || null : null, valuation: l.stocked ? l.valuation || null : 'zero',
        nrvUnitValue: num(l.nrvUnitValue) ?? null })),
    };
    try {
      const saved = await save.mutateAsync({ id, body });
      notify.success(tx('Saved'));
      setLoaded(false);
      if (!id) navigate(`/manufacturing/crushing/profiles/${saved.id}`);
    } catch (err) {
      setError(apiMessage(err, tx('Could not save')));
    }
  };
  const run = async (a: 'activate' | 'new-version' | 'archive') => {
    try {
      const res = await action.mutateAsync({ id: id!, action: a });
      notify.success(tx('Saved'));
      setLoaded(false);
      if (a === 'new-version') navigate(`/manufacturing/crushing/profiles/${res.id}`);
    } catch (err) {
      notify.error(apiMessage(err, tx('The action failed')));
    }
  };

  return (
    <div className="space-y-4">
      <ListPageHeader icon={Ruler} title={id && g ? `${g.code} v${g.version} · ${g.name}` : tx('New grading profile')}
        description={tx('Expected size split of the feed: main product, co-products, by-products and waste, each into its stockpile.')}
        actions={<div className="flex flex-wrap gap-2">
          <Button variant="outline" asChild><Link to={plantId ? `/manufacturing/crushing/plants/${plantId}` : '/manufacturing/crushing/plants'}><ArrowLeft className="mr-1.5 h-4 w-4" />{tx('Back')}</Link></Button>
          {id && canSetup && g?.status === 'draft' && <Button onClick={() => run('activate')} disabled={action.isPending}><CheckCircle2 className="mr-1.5 h-4 w-4" />{tx('Activate')}</Button>}
          {id && canSetup && <Button variant="outline" onClick={() => run('new-version')} disabled={action.isPending}><Copy className="mr-1.5 h-4 w-4" />{tx('New version')}</Button>}
          {id && canSetup && g?.status === 'active' && <Button variant="outline" onClick={() => run('archive')} disabled={action.isPending}>{tx('Archive')}</Button>}
          {id && canSetup && g?.status === 'draft' && <Button variant="outline" disabled={remove.isPending} onClick={async () => {
            try { await remove.mutateAsync(id); navigate(`/manufacturing/crushing/plants/${plantId}`); } catch (err) { notify.error(apiMessage(err, tx('Could not delete'))); }
          }}><Trash2 className="mr-1.5 h-4 w-4" />{tx('Delete')}</Button>}
        </div>} />
      {g && <div className="flex flex-wrap items-center gap-2 text-sm"><ProfileStatusPill status={g.status} />{g.bomCode && <span className="text-muted-foreground">{tx('BOM')} {g.bomCode}</span>}</div>}
      <Section title={tx('Profile')}>
        <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-4">
          <Field label={tx('Plant *')}><OptionalSelect value={plantId} onChange={setPlantId} disabled={!editable} placeholder={tx('Choose…')} options={(lookups.data?.plants ?? []).map((p) => ({ value: p.id, label: `${p.code} · ${p.name}` }))} /></Field>
          <Field label={tx('Code')}><Input value={head.code} disabled={!!id} placeholder={tx('Automatic')} onChange={(e) => setHead((h) => ({ ...h, code: e.target.value }))} /></Field>
          <Field label={tx('Name *')}><Input value={head.name} disabled={!editable} onChange={(e) => setHead((h) => ({ ...h, name: e.target.value }))} /></Field>
          <Field label={tx('Joint-cost allocation')}><OptionalSelect value={head.allocationMethod} disabled={!editable} onChange={(v) => setHead((h) => ({ ...h, allocationMethod: v || 'quantity' }))} placeholder={tx('Choose…')} options={toOptions(ALLOCATION_LABEL)} /></Field>
          <Field label={tx('Feed material *')} className="sm:col-span-2">
            {editable ? <CrushingProductPicker value={head.feedProductId} label={head.feedLabel} onChange={(v, l) => setHead((h) => ({ ...h, feedProductId: v, feedLabel: l }))} /> : <Input value={head.feedLabel} disabled />}
          </Field>
        </div>
      </Section>
      <Section title={tx('Size fractions')} actions={editable && <Button variant="outline" size="sm" onClick={() => setLines((x) => [...x, newLine('co_product')])}><Plus className="mr-1 h-4 w-4" />{tx('Add fraction')}</Button>}>
        <p className={`text-sm ${total > 100 ? 'text-rose-600' : 'text-muted-foreground'}`}>{tx('Expected recovery {{pct}}; the rest of the feed is the normal process loss.', { pct: pct(total) })}</p>
        {lines.map((l, i) => (
          <div key={i} className="space-y-2 rounded-lg border p-3">
            <div className="grid grid-cols-1 gap-2 sm:grid-cols-[1fr_8rem_10rem_10rem_7rem_auto]">
              {editable ? <CrushingProductPicker value={l.productId} label={l.productLabel} onChange={(v, lbl) => setLine(i, { productId: v, productLabel: lbl })} /> : <Input value={l.productLabel} disabled />}
              <Input placeholder={tx('Size, e.g. 0–6 mm')} value={l.sizeLabel} disabled={!editable} onChange={(e) => setLine(i, { sizeLabel: e.target.value })} />
              <OptionalSelect value={l.role} disabled={!editable} onChange={(v) => setLine(i, { role: (v || 'co_product') as FractionRole })} placeholder={tx('Choose…')} options={toOptions(ROLE_LABEL)} />
              <OptionalSelect value={l.role === 'main' ? '' : l.stageSequence} disabled={!editable || l.role === 'main'} onChange={(v) => setLine(i, { stageSequence: v })} placeholder={tx('Last stage')} noneLabel={tx('Last stage')} options={stageOptions} />
              <Input type="number" inputMode="decimal" min={0} max={100} step="any" placeholder="%" value={l.expectedPct} disabled={!editable} onChange={(e) => setLine(i, { expectedPct: e.target.value })} />
              {editable && <Button variant="ghost" size="icon" aria-label={tx('Remove')} onClick={() => setLines((x) => x.filter((_, j) => j !== i))}><Trash2 className="h-4 w-4" /></Button>}
            </div>
            <div className="grid grid-cols-1 gap-2 sm:grid-cols-[8rem_1fr_14rem_8rem]">
              <label className="flex items-center gap-2 text-sm"><input type="checkbox" checked={l.stocked} disabled={!editable || l.role === 'main'} onChange={(e) => setLine(i, { stocked: e.target.checked })} /> {tx('Stocked')}</label>
              <OptionalSelect value={l.stockpileId} disabled={!editable || !l.stocked} onChange={(v) => setLine(i, { stockpileId: v })} placeholder={tx('Stockpile…')} noneLabel={tx('None')} options={productPiles.map((k) => ({ value: k.id, label: `${k.siteCode} · ${k.code} · ${k.name}` }))} />
              <OptionalSelect value={l.valuation} disabled={!editable || !l.stocked || l.role === 'main' || l.role === 'co_product'} onChange={(v) => setLine(i, { valuation: v })} placeholder={tx('Default valuation')} noneLabel={tx('Default valuation')} options={toOptions(VALUATION_LABEL)} />
              <Input type="number" inputMode="decimal" min={0} step="any" placeholder={tx('NRV / t')} value={l.nrvUnitValue} disabled={!editable || l.valuation !== 'nrv'} onChange={(e) => setLine(i, { nrvUnitValue: e.target.value })} />
            </div>
          </div>
        ))}
      </Section>
      {error && <p className="rounded-md border border-destructive/40 bg-destructive/10 p-3 text-sm text-destructive">{error}</p>}
      {editable && canSetup && <div className="flex justify-end"><Button onClick={submit} disabled={save.isPending}>{tx('Save draft')}</Button></div>}
    </div>
  );
}

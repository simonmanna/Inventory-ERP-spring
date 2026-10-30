import { useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { ArrowLeft, Bomb, CheckCheck, Flame, PackagePlus, Pencil, Plus, Trash2, XCircle } from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Skeleton } from '@/components/ui/skeleton';
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { ListPageHeader } from '@/components/list';
import { useAuthStore } from '@/stores/auth.store';
import { notify, apiMessage } from '@/lib/notify';
import { date, dateTime, money } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';
import { Field, OptionalSelect } from '@/pages/fleet/fleet-ui';
import {
  useBlast, useBlastAction, useQuarryLookups, useRequestExplosives, useSaveRow, type BlastDetail,
} from '@/features/quarry/api';
import { BlastStatusPill, EmptyState, FormDialog, IssueStatusPill, KeyValues, LogStatusPill, ReasonDialog, Section, qty, tonnes } from './quarry-ui';
import { blastFields, fireFields } from './blast-fields';

/** Request explosives for a blast from a magazine (goes to the approval engine). */
function RequestDialog({ blast, onClose }: { blast: BlastDetail; onClose: () => void }) {
  const lookups = useQuarryLookups();
  const request = useRequestExplosives();
  const [magazineId, setMagazineId] = useState('');
  const [purpose, setPurpose] = useState('');
  const [lines, setLines] = useState([{ explosiveId: '', quantity: '' }]);
  const magazines = (lookups.data?.magazines ?? []).filter((m) => m.isActive);
  const valid = magazineId && lines.every((l) => l.explosiveId && Number(l.quantity) > 0);
  return (
    <Dialog open onOpenChange={(o) => !o && onClose()}>
      <DialogContent className="max-h-[90vh] overflow-y-auto">
        <DialogHeader>
          <DialogTitle>{tx('Request explosives for {{code}}', { code: blast.code })}</DialogTitle>
          <DialogDescription>{tx('Nothing leaves the magazine until the request is approved and issued by the magazine keeper.')}</DialogDescription>
        </DialogHeader>
        <div className="space-y-3">
          <Field label={tx('Magazine *')}>
            <OptionalSelect value={magazineId} onChange={setMagazineId} placeholder={tx('Choose the magazine')} options={magazines.map((m) => ({ value: m.id, label: `${m.code} · ${m.name}` }))} />
          </Field>
          {lines.map((l, i) => (
            <div key={i} className="grid grid-cols-[1fr_110px_40px] items-end gap-2">
              <Field label={tx('Explosive')}>
                <OptionalSelect value={l.explosiveId} onChange={(v) => setLines((xs) => xs.map((x, j) => (j === i ? { ...x, explosiveId: v } : x)))} placeholder={tx('Choose…')}
                  options={(lookups.data?.explosives ?? []).map((x) => ({ value: x.id, label: x.name }))} />
              </Field>
              <Field label={tx('Quantity')}>
                <Input type="number" inputMode="decimal" min="0" value={l.quantity} onChange={(e) => setLines((xs) => xs.map((x, j) => (j === i ? { ...x, quantity: e.target.value } : x)))} />
              </Field>
              <Button variant="ghost" size="icon" aria-label={tx('Remove')} disabled={lines.length === 1} onClick={() => setLines((xs) => xs.filter((_, j) => j !== i))}><Trash2 className="h-4 w-4" /></Button>
            </div>
          ))}
          <Button size="sm" variant="outline" onClick={() => setLines((xs) => [...xs, { explosiveId: '', quantity: '' }])}><Plus className="mr-1 h-3.5 w-3.5" /> {tx('Add line')}</Button>
          <Field label={tx('Purpose')}><Input value={purpose} onChange={(e) => setPurpose(e.target.value)} /></Field>
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={onClose}>{tx('Cancel')}</Button>
          <Button disabled={!valid || request.isPending} onClick={async () => {
            try {
              await request.mutateAsync({ blastId: blast.id, magazineId, purpose: purpose.trim() || undefined,
                lines: lines.map((l) => ({ explosiveId: l.explosiveId, quantity: Number(l.quantity) })) });
              notify.success(tx('Request sent for approval'));
              onClose();
            } catch (e) { notify.error(apiMessage(e, tx('Could not request the explosives'))); }
          }}>{tx('Send for approval')}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

/** A blast: pattern, compliance, cost pool (rate per tonne, absorbed), explosive issues and absorbing extraction. */
export function BlastDetailPage() {
  const { id = '' } = useParams();
  const has = useAuthStore((s) => s.hasPermission);
  const { data: b, isLoading, isError } = useBlast(id);
  const lookups = useQuarryLookups();
  const save = useSaveRow('/quarry/blasts');
  const action = useBlastAction();
  const [dialog, setDialog] = useState<'edit' | 'fire' | 'cancel' | 'request' | null>(null);
  if (isLoading) return <Skeleton className="h-96" />;
  if (isError || !b) return <EmptyState>{tx('Could not load the blast.')}</EmptyState>;
  const canUpdate = has(PERMISSIONS.quarryBlasting.update);
  const open = b.status === 'planned' || b.status === 'fired';
  const close = async () => {
    try { await action.mutateAsync({ id: b.id, action: 'close' }); notify.success(tx('Blast closed')); } catch (e) { notify.error(apiMessage(e, tx('Could not close the blast'))); }
  };
  return (
    <div className="space-y-4">
      <Button variant="ghost" size="sm" asChild><Link to="/quarry/blasts"><ArrowLeft className="mr-1 h-4 w-4" /> {tx('Blasts')}</Link></Button>
      <ListPageHeader icon={Bomb} title={`${b.code} · ${b.siteCode}${b.zoneCode ? ` · ${b.zoneCode}` : ''}`} description={b.materialName ?? undefined}
        actions={
          <>
            <BlastStatusPill status={b.status} />
            {open && has(PERMISSIONS.quarryBlasting.requestExplosive) && <Button variant="outline" onClick={() => setDialog('request')}><PackagePlus className="mr-1.5 h-4 w-4" /> {tx('Request explosives')}</Button>}
            {open && canUpdate && <Button variant="outline" onClick={() => setDialog('edit')}><Pencil className="mr-1.5 h-4 w-4" /> {tx('Edit')}</Button>}
            {b.status === 'planned' && canUpdate && <Button onClick={() => setDialog('fire')}><Flame className="mr-1.5 h-4 w-4" /> {tx('Record firing')}</Button>}
            {b.status === 'fired' && canUpdate && <Button onClick={close}><CheckCheck className="mr-1.5 h-4 w-4" /> {tx('Close blast')}</Button>}
            {b.status === 'planned' && canUpdate && <Button variant="outline" onClick={() => setDialog('cancel')}><XCircle className="mr-1.5 h-4 w-4" /> {tx('Cancel')}</Button>}
          </>
        } />
      <Section title={tx('Pattern & drilling')}>
        <KeyValues items={[
          [tx('Pattern'), b.patternType], [tx('Holes'), b.holeCount], [tx('Hole diameter (mm)'), b.holeDiameterMm],
          [tx('Average depth (m)'), b.averageDepthM], [tx('Burden × spacing (m)'), b.burdenM && b.spacingM ? `${b.burdenM} × ${b.spacingM}` : null],
          [tx('Sub-drill / stemming (m)'), `${b.subDrillM ?? '—'} / ${b.stemmingM ?? '—'}`], [tx('Drilled metres'), b.drilledMetres],
          [tx('Drill rig'), b.drillVehicle?.code], [tx('Estimated volume (m³)'), qty(b.estimatedVolumeM3)], [tx('Estimated tonnes'), tonnes(b.estimatedTonnes)],
        ]} />
      </Section>
      <Section title={tx('Cost per tonne')}>
        <KeyValues items={[
          [tx('Explosives (net of returns)'), `${money(b.explosivesCost)} · ${qty(b.explosivesKg)} kg`], [tx('Drilling cost'), money(b.drillingCost)],
          [tx('Other blast cost'), money(b.otherCost)], [tx('Total cost'), money(b.totalCost)], [tx('Cost per tonne'), b.costPerTonne ? money(b.costPerTonne) : '—'],
          [tx('Powder factor (kg/t)'), qty(b.powderFactorKgPerT, 4)], [tx('Absorbed by extraction'), money(b.absorbedCost)], [tx('Not yet absorbed'), money(b.remainingCost)],
        ]} />
      </Section>
      <Section title={tx('Compliance')}>
        <KeyValues items={[
          [tx('Shotfirer'), b.shotfirer?.name], [tx('Shotfirer licence number'), b.shotfirerLicenceNo], [tx('Blast permit number'), b.blastPermitNo],
          [tx('Permit expires on'), b.permitExpiresOn ? date(b.permitExpiresOn) : null], [tx('Exclusion zone (m)'), b.exclusionZoneM],
          [tx('Warning signal at'), b.warningSignalAt ? dateTime(b.warningSignalAt) : null], [tx('Fired at'), b.firedAt ? dateTime(b.firedAt) : null],
          [tx('All-clear at'), b.allClearAt ? dateTime(b.allClearAt) : null], [tx('Misfires'), b.misfireCount], [tx('Ground vibration PPV (mm/s)'), b.vibrationPpvMmS],
          [tx('Airblast (dB)'), b.airblastDb], [tx('Weather'), b.weather],
        ]} />
      </Section>
      <div className="grid gap-4 lg:grid-cols-2">
        <Section title={tx('Explosive issues')}>
          {b.issues.length === 0 ? <p className="text-sm text-muted-foreground">{tx('No explosives requested yet.')}</p> : (
            <ul className="divide-y text-sm">
              {b.issues.map((i) => (
                <li key={i.id} className="flex items-center justify-between gap-2 py-2">
                  <Link className="text-primary hover:underline" to={`/quarry/explosive-issues/${i.id}`}>{i.code} · {i.magazineCode}</Link>
                  <span className="flex items-center gap-2"><span className="tabular-nums">{money(i.issuedValue)}</span><IssueStatusPill status={i.status} /></span>
                </li>
              ))}
            </ul>
          )}
        </Section>
        <Section title={tx('Extraction absorbing this blast')}>
          {b.extractionLogs.length === 0 ? <p className="text-sm text-muted-foreground">{tx('No extraction linked yet.')}</p> : (
            <ul className="divide-y text-sm">
              {b.extractionLogs.map((l) => (
                <li key={l.id} className="flex items-center justify-between gap-2 py-2">
                  <Link className="text-primary hover:underline" to={`/quarry/extraction/${l.id}`}>{l.code} · {date(l.extractedOn)}</Link>
                  <span className="flex items-center gap-2 tabular-nums">{tonnes(l.netTonnes)} · {money(l.blastingCost)} <LogStatusPill status={l.status} /></span>
                </li>
              ))}
            </ul>
          )}
        </Section>
      </div>
      {dialog === 'edit' && (
        <FormDialog title={tx('Edit blast {{code}}', { code: b.code })} fields={blastFields(lookups.data, b.shotfirer?.name)} row={b} saving={save.isPending}
          onSave={(body) => save.mutateAsync({ id: b.id, body })} onClose={() => setDialog(null)} />
      )}
      {dialog === 'fire' && (
        <FormDialog title={tx('Record firing of {{code}}', { code: b.code })} fields={fireFields()} saving={action.isPending}
          description={tx('The shotfirer, licence, blast permit, exclusion zone and estimated tonnes must be on the record.')}
          defaults={{ firedAt: new Date(Date.now() - new Date().getTimezoneOffset() * 60_000).toISOString().slice(0, 16), misfireCount: '0' }}
          onSave={(body) => action.mutateAsync({ id: b.id, action: 'fire', body })} onClose={() => setDialog(null)} />
      )}
      {dialog === 'cancel' && (
        <ReasonDialog title={tx('Cancel blast {{code}}', { code: b.code })} confirmLabel={tx('Cancel blast')} busy={action.isPending}
          onConfirm={(reason) => action.mutateAsync({ id: b.id, action: 'cancel', body: { reason } })} onClose={() => setDialog(null)} />
      )}
      {dialog === 'request' && <RequestDialog blast={b} onClose={() => setDialog(null)} />}
    </div>
  );
}

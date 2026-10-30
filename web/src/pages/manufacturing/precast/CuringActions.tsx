import { useState, type ReactNode } from 'react';
import { Input } from '@/components/ui/input';
import { Textarea } from '@/components/ui/textarea';
import { Button } from '@/components/ui/button';
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { notify, apiMessage } from '@/lib/notify';
import { cn } from '@/lib/utils';
import { tx } from '@/lib/i18n/tx';
import { toLocalInput, fromLocalInput } from '../../fleet/fleet-format';
import {
  useCubeBatch, useCuringReading, useGateDecision, useMoveBatch, type CuringBatch,
} from '@/features/manufacturing/api-precast';
import { CuringAreaSelect, Field, ScrapReasonField } from './precast-ui';
import { cubeTotal, nonNegativeOrEmpty, num, optNum, today } from './precast-format';

/** Touch-friendly action dialogs of a curing batch (2.4), shared by the curing board and the batch page. */

export type CuringAction = 'reading' | 'move' | 'cube' | 'gate';

const BIG = 'h-12 text-lg';

function Shell({ title, description, onClose, children, footer }: {
  title: string; description?: string; onClose: () => void; children: ReactNode; footer: ReactNode;
}) {
  return (
    <Dialog open onOpenChange={(o) => !o && onClose()}>
      <DialogContent className="max-h-[92vh] overflow-y-auto sm:max-w-md">
        <DialogHeader>
          <DialogTitle>{title}</DialogTitle>
          {description && <DialogDescription>{description}</DialogDescription>}
        </DialogHeader>
        <div className="space-y-3">{children}</div>
        <DialogFooter className="gap-2">{footer}</DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

export function CuringActionDialog({ action, batch, onClose }: { action: CuringAction; batch: CuringBatch; onClose: () => void }) {
  if (action === 'reading') return <ReadingDialog batch={batch} onClose={onClose} />;
  if (action === 'move') return <MoveDialog batch={batch} onClose={onClose} />;
  if (action === 'cube') return <CubeDialog batch={batch} onClose={onClose} />;
  return <GateDialog batch={batch} onClose={onClose} />;
}

function ReadingDialog({ batch, onClose }: { batch: CuringBatch; onClose: () => void }) {
  const save = useCuringReading();
  const [temperatureC, setTemperatureC] = useState('');
  const [humidityPct, setHumidityPct] = useState('');
  const [readAt, setReadAt] = useState(toLocalInput(new Date()));
  const [notes, setNotes] = useState('');
  const tempOk = temperatureC.trim() !== '' && Number.isFinite(Number(temperatureC));
  const humOk = humidityPct.trim() === '' || (Number(humidityPct) >= 0 && Number(humidityPct) <= 100);
  const submit = async () => {
    try {
      await save.mutateAsync({ id: batch.id, body: { temperatureC: Number(temperatureC), humidityPct: optNum(humidityPct), readAt: fromLocalInput(readAt), notes: notes.trim() || undefined } });
      notify.success(tx('Reading saved'));
      onClose();
    } catch (err) {
      notify.error(apiMessage(err, tx('Could not save the reading')));
    }
  };
  return (
    <Shell title={tx('Temperature reading')} description={batch.code} onClose={onClose}
      footer={<><Button variant="outline" className="h-12" onClick={onClose}>{tx('Cancel')}</Button><Button className="h-12" onClick={submit} disabled={!tempOk || !humOk || save.isPending}>{tx('Save reading')}</Button></>}>
      <div className="grid grid-cols-2 gap-3">
        <Field label={tx('Temperature (°C) *')}><Input className={BIG} inputMode="decimal" value={temperatureC} onChange={(e) => setTemperatureC(e.target.value)} autoFocus /></Field>
        <Field label={tx('Humidity %')} error={!humOk ? tx('Between 0 and 100') : undefined}><Input className={BIG} inputMode="decimal" value={humidityPct} onChange={(e) => setHumidityPct(e.target.value)} /></Field>
      </div>
      <Field label={tx('Read at')}><Input className="h-11" type="datetime-local" value={readAt} onChange={(e) => setReadAt(e.target.value)} /></Field>
      <Field label={tx('Notes')}><Textarea rows={2} value={notes} onChange={(e) => setNotes(e.target.value)} /></Field>
    </Shell>
  );
}

function MoveDialog({ batch, onClose }: { batch: CuringBatch; onClose: () => void }) {
  const move = useMoveBatch();
  const [curingAreaId, setCuringAreaId] = useState(batch.curingAreaId ?? '');
  const [palletLocation, setPalletLocation] = useState(batch.palletLocation ?? '');
  const [notes, setNotes] = useState('');
  const changed = curingAreaId !== (batch.curingAreaId ?? '') || palletLocation.trim() !== (batch.palletLocation ?? '');
  const submit = async () => {
    try {
      await move.mutateAsync({ id: batch.id, body: { curingAreaId: curingAreaId || undefined, palletLocation: palletLocation.trim() || undefined, notes: notes.trim() || undefined } });
      notify.success(tx('Batch moved'));
      onClose();
    } catch (err) {
      notify.error(apiMessage(err, tx('Could not move the batch')));
    }
  };
  return (
    <Shell title={tx('Move pallets')} description={batch.code} onClose={onClose}
      footer={<><Button variant="outline" className="h-12" onClick={onClose}>{tx('Cancel')}</Button><Button className="h-12" onClick={submit} disabled={!changed || move.isPending}>{tx('Move')}</Button></>}>
      <Field label={tx('Curing area')}><CuringAreaSelect value={curingAreaId} onChange={setCuringAreaId} /></Field>
      <Field label={tx('Pallet location / bay')}><Input className={BIG} value={palletLocation} onChange={(e) => setPalletLocation(e.target.value)} /></Field>
      <Field label={tx('Notes')}><Textarea rows={2} value={notes} onChange={(e) => setNotes(e.target.value)} /></Field>
    </Shell>
  );
}

function CubeDialog({ batch, onClose }: { batch: CuringBatch; onClose: () => void }) {
  const cube = useCubeBatch();
  const [qtyGood, setQtyGood] = useState(String(Number(batch.quantity)));
  const [qtySecondGrade, setQtySecondGrade] = useState('0');
  const [qtyBroken, setQtyBroken] = useState('0');
  const [scrapReasonCode, setScrapReasonCode] = useState('');
  const [cubeDate, setCubeDate] = useState(today());
  const [notes, setNotes] = useState('');
  const check = cubeTotal(batch.quantity, qtyGood, qtySecondGrade, qtyBroken);
  const numsOk = [qtyGood, qtySecondGrade, qtyBroken].every((v) => v.trim() !== '' && nonNegativeOrEmpty(v));
  const submit = async () => {
    try {
      await cube.mutateAsync({
        id: batch.id,
        body: {
          qtyGood: Number(qtyGood), qtySecondGrade: optNum(qtySecondGrade), qtyBroken: optNum(qtyBroken),
          scrapReasonCode: scrapReasonCode.trim() || undefined, date: cubeDate || undefined, notes: notes.trim() || undefined,
        },
      });
      notify.success(tx('Batch cubed'));
      onClose();
    } catch (err) {
      notify.error(apiMessage(err, tx('Could not cube the batch')));
    }
  };
  return (
    <Shell title={tx('Cube batch')} description={`${batch.code} · ${batch.productName ?? ''}`} onClose={onClose}
      footer={<><Button variant="outline" className="h-12" onClick={onClose}>{tx('Cancel')}</Button><Button className="h-12" onClick={submit} disabled={!numsOk || !check.ok || cube.isPending}>{tx('Cube')}</Button></>}>
      {!batch.ready && <p className="rounded-md bg-amber-50 p-2 text-sm text-amber-800 dark:bg-amber-500/15 dark:text-amber-300">{tx('This batch has not finished curing yet.')}</p>}
      <div className="grid grid-cols-3 gap-2">
        <Field label={tx('Good')}><Input className={BIG} inputMode="numeric" value={qtyGood} onChange={(e) => setQtyGood(e.target.value)} /></Field>
        <Field label={tx('Second grade')}><Input className={BIG} inputMode="numeric" value={qtySecondGrade} onChange={(e) => setQtySecondGrade(e.target.value)} /></Field>
        <Field label={tx('Broken')}><Input className={BIG} inputMode="numeric" value={qtyBroken} onChange={(e) => setQtyBroken(e.target.value)} /></Field>
      </div>
      <p className={cn('text-sm font-medium', check.ok ? 'text-emerald-700 dark:text-emerald-300' : 'text-destructive')}>
        {tx('Must total {{total}}', { total: num(check.expected, 0) })} · {tx('Entered')}: {num(check.total, 0)}
        {!check.ok && ` · ${tx('Difference')}: ${num(check.remaining, 0)}`}
      </p>
      {Number(qtyBroken || 0) > 0 && <ScrapReasonField value={scrapReasonCode} onChange={setScrapReasonCode} />}
      <Field label={tx('Date')}><Input className="h-11" type="date" value={cubeDate} onChange={(e) => setCubeDate(e.target.value)} /></Field>
      <Field label={tx('Notes')}><Textarea rows={2} value={notes} onChange={(e) => setNotes(e.target.value)} /></Field>
    </Shell>
  );
}

function GateDialog({ batch, onClose }: { batch: CuringBatch; onClose: () => void }) {
  const gate = useGateDecision();
  const [reference, setReference] = useState('');
  const [notes, setNotes] = useState('');
  const decide = async (result: 'passed' | 'failed') => {
    try {
      const out = await gate.mutateAsync({ id: batch.id, body: { result, reference: reference.trim() || undefined, notes: notes.trim() || undefined } });
      notify.success(out.qcStatus === 'curing'
        ? tx('Passed: released once curing ends')
        : result === 'passed' ? tx('QC gate passed') : tx('QC gate failed'));
      onClose();
    } catch (err) {
      notify.error(apiMessage(err, tx('Could not record the QC decision')));
    }
  };
  return (
    <Shell title={tx('QC gate decision')} description={`${batch.code}${batch.qcCheckpointCode ? ` · ${batch.qcCheckpointCode}` : ''}`} onClose={onClose}
      footer={<>
        <Button variant="destructive" className="h-12 flex-1" onClick={() => decide('failed')} disabled={gate.isPending}>{tx('Fail')}</Button>
        <Button className="h-12 flex-1" onClick={() => decide('passed')} disabled={gate.isPending}>{tx('Pass')}</Button>
      </>}>
      <Field label={tx('Reference (test report, cube result)')}><Input className="h-11" value={reference} onChange={(e) => setReference(e.target.value)} /></Field>
      <Field label={tx('Notes')}><Textarea rows={2} value={notes} onChange={(e) => setNotes(e.target.value)} /></Field>
    </Shell>
  );
}

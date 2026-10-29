import { useState } from 'react';
import { Link } from 'react-router-dom';
import { Gauge, RotateCcw, Wrench } from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Textarea } from '@/components/ui/textarea';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { DataTable, type Column } from '@/components/data-table';
import { DataTablePagination, StatusPill, type Tone } from '@/components/list';
import { useAuthStore } from '@/stores/auth.store';
import { notify, apiMessage } from '@/lib/notify';
import { dateTime } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';
import {
  CORRECTION_STATUS_LABEL, meterUnit, useMeterCorrections, useRecordReading, useRequestCorrection, useSyncCorrection,
  useVehicleReadings, type MeterCorrection, type MeterReading, type Vehicle,
} from '@/features/fleet/api';
import { Field, OptionalSelect } from './fleet-ui';
import { fromLocalInput, num, toLocalInput } from './fleet-format';

type Meter = 'odometer' | 'hours';

const CORRECTION_TONE: Record<MeterCorrection['status'], Tone> = {
  pending: 'warning', applied: 'success', rejected: 'neutral', failed: 'danger',
};

const meterName = (m: Meter) => (m === 'hours' ? tx('Engine hours') : tx('Odometer'));

/** Meter readings with the monotonic rule, and approval-gated corrections (fleet 1.1). Phone-friendly entry form. */
export function VehicleMetersTab({ vehicle }: { vehicle: Vehicle }) {
  const tracked: Meter[] = vehicle.meterType === 'both' ? ['odometer', 'hours']
    : vehicle.meterType === 'none' ? [] : [vehicle.meterType];
  const canRecord = useAuthStore((s) => s.hasPermission(PERMISSIONS.fleetVehicle.meter)) && vehicle.status !== 'disposed';
  const [filter, setFilter] = useState('');
  const [showSuperseded, setShowSuperseded] = useState(false);
  const [page, setPage] = useState(1);
  const readings = useVehicleReadings(vehicle.id, filter, showSuperseded, page);
  const corrections = useMeterCorrections(vehicle.id);
  const [correcting, setCorrecting] = useState<{ kind: 'adjust' | 'replace'; meter: Meter; reading?: MeterReading } | null>(null);
  const sync = useSyncCorrection();

  if (tracked.length === 0) {
    return <Card><CardContent className="py-8 text-center text-sm text-muted-foreground">{tx('This vehicle has no meter.')}</CardContent></Card>;
  }

  const columns: Column<MeterReading>[] = [
    { key: 'readingAt', header: tx('When'), render: (r) => <span className="whitespace-nowrap">{dateTime(r.readingAt)}</span> },
    { key: 'meterType', header: tx('Meter'), render: (r) => meterName(r.meterType) },
    {
      key: 'value', header: tx('Reading'), className: 'text-right',
      render: (r) => (
        <span className={r.status === 'superseded' ? 'tabular-nums text-muted-foreground line-through' : 'tabular-nums font-medium'}>
          {num(r.value)} {meterUnit(r.meterType)}
        </span>
      ),
    },
    { key: 'lifetime', header: tx('Lifetime'), className: 'text-right', render: (r) => <span className="tabular-nums text-muted-foreground">{num(r.lifetimeValue)}</span> },
    { key: 'source', header: tx('Source'), render: (r) => r.source.replace(/_/g, ' ') },
    { key: 'notes', header: tx('Notes'), render: (r) => <span className="line-clamp-1 max-w-[14rem] text-muted-foreground">{r.notes ?? ''}</span> },
    {
      key: 'actions', header: '', className: 'w-24 text-right',
      render: (r) => canRecord && r.status === 'valid' && (
        <Button size="sm" variant="ghost" onClick={(e) => { e.stopPropagation(); setCorrecting({ kind: 'adjust', meter: r.meterType, reading: r }); }}>
          {tx('Correct')}
        </Button>
      ),
    },
  ];

  const applyNow = async (c: MeterCorrection) => {
    try {
      const out = await sync.mutateAsync(c.id);
      notify.success(CORRECTION_STATUS_LABEL[out.status]);
    } catch (err) {
      notify.error(apiMessage(err, tx('Could not apply the correction')));
    }
  };

  return (
    <div className="space-y-4">
      {canRecord && <ReadingForm vehicle={vehicle} tracked={tracked} />}

      <Card>
        <CardHeader className="flex flex-row flex-wrap items-center justify-between gap-2 space-y-0">
          <CardTitle className="text-base">{tx('Readings')}</CardTitle>
          <div className="flex flex-wrap items-center gap-2">
            {tracked.length > 1 && (
              <div className="w-40">
                <OptionalSelect value={filter} onChange={(v) => { setFilter(v); setPage(1); }} placeholder={tx('All meters')} noneLabel={tx('All meters')}
                  options={tracked.map((m) => ({ value: m, label: meterName(m) }))} />
              </div>
            )}
            <label className="flex items-center gap-2 text-sm">
              <input type="checkbox" checked={showSuperseded} onChange={(e) => { setShowSuperseded(e.target.checked); setPage(1); }} />
              {tx('Show corrected originals')}
            </label>
            {canRecord && tracked.map((m) => (
              <Button key={m} size="sm" variant="outline" onClick={() => setCorrecting({ kind: 'replace', meter: m })}>
                <RotateCcw className="mr-1 h-4 w-4" /> {tx('{{meter}} replaced', { meter: meterName(m) })}
              </Button>
            ))}
          </div>
        </CardHeader>
        <CardContent>
          <DataTable columns={columns} data={readings.data?.data ?? []} loading={readings.isLoading} getRowId={(r) => r.id}
            emptyMessage={readings.isError ? tx('Could not load the readings.') : tx('No readings yet.')} />
          {readings.data?.meta && readings.data.meta.total > 0 && (
            <DataTablePagination page={readings.data.meta.page} pageSize={readings.data.meta.pageSize} total={readings.data.meta.total}
              totalPages={readings.data.meta.totalPages} onPageChange={setPage} noun="reading" />
          )}
        </CardContent>
      </Card>

      <Card>
        <CardHeader><CardTitle className="text-base">{tx('Meter corrections')}</CardTitle></CardHeader>
        <CardContent className="space-y-2">
          <p className="text-xs text-muted-foreground">
            {tx('Lowering or changing a recorded reading needs approval. Approvers decide in')}{' '}
            <Link to="/approvals" className="text-primary hover:underline">{tx('Approvals')}</Link>.
          </p>
          {(corrections.data ?? []).length === 0 && <p className="text-sm text-muted-foreground">{tx('No corrections requested.')}</p>}
          {(corrections.data ?? []).map((c) => (
            <div key={c.id} className="flex flex-wrap items-center gap-3 rounded-lg border p-3 text-sm">
              <StatusPill tone={CORRECTION_TONE[c.status]}>{CORRECTION_STATUS_LABEL[c.status]}</StatusPill>
              <span className="font-medium">
                {c.kind === 'replace' ? tx('Meter replaced') : tx('Adjust reading')} · {meterName(c.meterType)}:{' '}
                <span className="tabular-nums">{num(c.previousValue)} → {num(c.correctedValue)} {meterUnit(c.meterType)}</span>
              </span>
              <span className="min-w-[10rem] flex-1 text-muted-foreground">{c.reason}{c.failureReason ? ` — ${c.failureReason}` : ''}</span>
              <span className="text-xs text-muted-foreground">{dateTime(c.requestedAt)}</span>
              {c.status === 'pending' && canRecord && (
                <Button size="sm" variant="outline" onClick={() => applyNow(c)} disabled={sync.isPending}>{tx('Check approval')}</Button>
              )}
            </div>
          ))}
        </CardContent>
      </Card>

      {correcting && (
        <CorrectionDialog vehicle={vehicle} kind={correcting.kind} meter={correcting.meter} reading={correcting.reading}
          onClose={() => setCorrecting(null)} />
      )}
    </div>
  );
}

function ReadingForm({ vehicle, tracked }: { vehicle: Vehicle; tracked: Meter[] }) {
  const [meter, setMeter] = useState<Meter>(tracked[0]);
  const [value, setValue] = useState('');
  const [at, setAt] = useState('');
  const [notes, setNotes] = useState('');
  const record = useRecordReading();
  const current = meter === 'hours' ? vehicle.meters.hours : vehicle.meters.odometer;
  const valid = value !== '' && Number.isFinite(Number(value)) && Number(value) >= 0;

  const submit = async () => {
    try {
      await record.mutateAsync({ id: vehicle.id, body: { meterType: meter, value: Number(value), readingAt: fromLocalInput(at), notes: notes.trim() || undefined } });
      notify.success(tx('Reading recorded'));
      setValue('');
      setAt('');
      setNotes('');
    } catch (err) {
      notify.error(apiMessage(err, tx('Could not record the reading')));
    }
  };

  return (
    <Card>
      <CardHeader><CardTitle className="flex items-center gap-2 text-base"><Gauge className="h-4 w-4" /> {tx('Record a reading')}</CardTitle></CardHeader>
      <CardContent className="grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-5 lg:items-end">
        {tracked.length > 1 && (
          <Field label={tx('Meter')}>
            <OptionalSelect value={meter} onChange={(v) => setMeter((v || 'odometer') as Meter)} placeholder={tx('Meter')}
              options={tracked.map((m) => ({ value: m, label: meterName(m) }))} />
          </Field>
        )}
        <Field label={`${meterName(meter)} (${meterUnit(meter)})`}>
          <Input inputMode="decimal" className="h-12 text-lg" value={value} onChange={(e) => setValue(e.target.value)}
            placeholder={current ? num(current.value) : ''} />
        </Field>
        <Field label={tx('Taken at (blank = now)')}>
          <Input type="datetime-local" value={at} max={toLocalInput(new Date())} onChange={(e) => setAt(e.target.value)} />
        </Field>
        <Field label={tx('Notes')}><Input value={notes} onChange={(e) => setNotes(e.target.value)} /></Field>
        <Button className="h-12" onClick={submit} disabled={!valid || record.isPending}>{tx('Record')}</Button>
      </CardContent>
    </Card>
  );
}

function CorrectionDialog({ vehicle, kind, meter, reading, onClose }: {
  vehicle: Vehicle; kind: 'adjust' | 'replace'; meter: Meter; reading?: MeterReading; onClose: () => void;
}) {
  const [value, setValue] = useState(kind === 'replace' ? '0' : '');
  const [at, setAt] = useState('');
  const [reason, setReason] = useState('');
  const request = useRequestCorrection();
  const valid = value !== '' && Number.isFinite(Number(value)) && Number(value) >= 0 && reason.trim().length > 0;

  const submit = async () => {
    try {
      const out = await request.mutateAsync({
        id: vehicle.id,
        body: {
          meterType: meter, kind, readingId: reading?.id, correctedValue: Number(value),
          readingAt: kind === 'replace' ? fromLocalInput(at) : undefined, reason: reason.trim(),
        },
      });
      notify.success(out.status === 'applied' ? tx('Correction applied') : tx('Correction sent for approval'));
      onClose();
    } catch (err) {
      notify.error(apiMessage(err, tx('Could not request the correction')));
    }
  };

  return (
    <Dialog open onOpenChange={(o) => !o && onClose()}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle className="flex items-center gap-2"><Wrench className="h-4 w-4" />
            {kind === 'replace' ? tx('{{meter}} replaced', { meter: meterName(meter) }) : tx('Correct a reading')}
          </DialogTitle>
          <DialogDescription>
            {kind === 'replace'
              ? tx('Record the value the new meter starts at. The lifetime total carries on from the old meter.')
              : tx('The original reading stays in the history, marked as corrected.')}
          </DialogDescription>
        </DialogHeader>
        <div className="space-y-4">
          {reading && (
            <p className="text-sm">{tx('Recorded')}: <span className="font-medium tabular-nums">{num(reading.value)} {meterUnit(meter)}</span> · {dateTime(reading.readingAt)}</p>
          )}
          <Field label={kind === 'replace' ? tx('New meter starts at') : tx('Correct value')}>
            <Input inputMode="decimal" value={value} onChange={(e) => setValue(e.target.value)} />
          </Field>
          {kind === 'replace' && (
            <Field label={tx('Replaced at (blank = now)')}>
              <Input type="datetime-local" value={at} max={toLocalInput(new Date())} onChange={(e) => setAt(e.target.value)} />
            </Field>
          )}
          <Field label={tx('Reason *')}><Textarea rows={3} value={reason} onChange={(e) => setReason(e.target.value)} /></Field>
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={onClose}>{tx('Cancel')}</Button>
          <Button onClick={submit} disabled={!valid || request.isPending}>{tx('Request correction')}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

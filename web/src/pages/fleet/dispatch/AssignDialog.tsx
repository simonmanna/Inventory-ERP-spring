import { useEffect, useState } from 'react';
import { AlertTriangle, CheckCircle2 } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { notify, apiMessage } from '@/lib/notify';
import { tx } from '@/lib/i18n/tx';
import {
  FREIGHT_BASIS_LABEL, toNum, useAssignTrip, useAssignmentCheck, useDispatchLookups, type TripSummary,
} from '@/features/fleet/dispatch-api';
import { Field, OptionalSelect } from '../fleet-ui';
import { num, toOptions } from '../fleet-format';

/**
 * Assign a trip (fleet 1.3): own truck + driver (+ trailer) with a live eligibility / capacity check, or a third-party
 * transporter with a rate card or an agreed basis + rate.
 */
export function AssignDialog({ trip, onClose }: { trip: TripSummary | null; onClose: () => void }) {
  const lookups = useDispatchLookups();
  const assign = useAssignTrip();
  const [carrier, setCarrier] = useState<'own' | 'transporter'>('own');
  const [vehicleId, setVehicleId] = useState('');
  const [trailerId, setTrailerId] = useState('');
  const [driverId, setDriverId] = useState('');
  const [partnerId, setPartnerId] = useState('');
  const [rateId, setRateId] = useState('');
  const [basis, setBasis] = useState('');
  const [rate, setRate] = useState('');
  const [plate, setPlate] = useState('');
  const [driverName, setDriverName] = useState('');

  useEffect(() => {
    if (!trip) return;
    setCarrier(trip.carrier);
    setVehicleId(trip.vehicleId ?? '');
    setTrailerId(trip.trailerId ?? '');
    setDriverId(trip.driverId ?? '');
    setPartnerId(trip.transporterPartnerId ?? '');
    setRateId(trip.transporterRateId ?? '');
    setBasis(trip.freightBasis ?? '');
    setRate(trip.freightRate ?? '');
    setPlate(trip.transporterPlate ?? '');
    setDriverName(trip.transporterDriverName ?? '');
  }, [trip]);

  const check = useAssignmentCheck(trip?.id ?? '', {
    vehicleId: vehicleId || undefined, trailerId: trailerId || undefined, driverId: driverId || undefined,
  }, !!trip && carrier === 'own' && !!vehicleId);

  const l = lookups.data;
  const trucks = (l?.vehicles ?? []).filter((v) => v.kind !== 'trailer');
  const trailers = (l?.vehicles ?? []).filter((v) => v.kind === 'trailer');
  const vLabel = (v: { code: string; registrationPlate: string | null; payloadCapacityT: string | null; bodyVolumeM3: string | null }) =>
    [v.code, v.registrationPlate, v.payloadCapacityT ? `${num(v.payloadCapacityT, 1)} t` : null, v.bodyVolumeM3 ? `${num(v.bodyVolumeM3, 1)} m³` : null]
      .filter(Boolean).join(' · ');
  const rates = (l?.transporterRates ?? []).filter((r) => r.partnerId === partnerId);

  const save = async () => {
    if (!trip) return;
    const body = carrier === 'own'
      ? { carrier, vehicleId, trailerId: trailerId || undefined, driverId: driverId || undefined }
      : {
        carrier, transporterPartnerId: partnerId, transporterRateId: rateId || undefined,
        freightBasis: rateId ? undefined : basis || undefined, freightRate: rateId ? undefined : toNum(rate),
        transporterPlate: plate || undefined, transporterDriverName: driverName || undefined,
      };
    try {
      await assign.mutateAsync({ id: trip.id, body });
      notify.success(tx('Trip {{code}} assigned', { code: trip.code }));
      onClose();
    } catch (err) {
      notify.error(apiMessage(err, tx('Could not assign the trip')));
    }
  };

  const c = check.data;
  const ownInvalid = carrier === 'own' && (!vehicleId || (c ? !c.eligible : false));
  const transporterInvalid = carrier === 'transporter' && (!partnerId || (!rateId && (!basis || !rate)));

  return (
    <Dialog open={!!trip} onOpenChange={(o) => { if (!o) onClose(); }}>
      <DialogContent className="max-w-lg">
        <DialogHeader>
          <DialogTitle>{tx('Assign trip {{code}}', { code: trip?.code ?? '' })}</DialogTitle>
          <DialogDescription>
            {trip?.plannedTonnes ? tx('Planned load: {{t}} t', { t: num(trip.plannedTonnes, 2) }) : tx('No planned tonnage (no weight data).')}
          </DialogDescription>
        </DialogHeader>
        <div className="space-y-3">
          <div className="grid grid-cols-2 gap-2">
            <Button type="button" variant={carrier === 'own' ? 'default' : 'outline'} onClick={() => setCarrier('own')}>{tx('Own truck')}</Button>
            <Button type="button" variant={carrier === 'transporter' ? 'default' : 'outline'} onClick={() => setCarrier('transporter')}>{tx('Transporter')}</Button>
          </div>
          {carrier === 'own' ? (
            <>
              <Field label={tx('Truck')}>
                <OptionalSelect value={vehicleId} onChange={setVehicleId} options={trucks.map((v) => ({ value: v.id, label: vLabel(v) }))} placeholder={tx('Choose the truck')} />
              </Field>
              <Field label={tx('Driver')}>
                <OptionalSelect value={driverId} onChange={setDriverId} noneLabel={tx('Later')} options={(l?.drivers ?? []).map((d) => ({ value: d.id, label: `${d.code} · ${d.name}` }))} placeholder={tx('Choose the driver')} />
              </Field>
              <Field label={tx('Trailer')}>
                <OptionalSelect value={trailerId} onChange={setTrailerId} noneLabel={tx('No trailer')} options={trailers.map((v) => ({ value: v.id, label: vLabel(v) }))} placeholder={tx('No trailer')} />
              </Field>
              {c && (
                <div className={`rounded-lg border p-3 text-sm ${c.eligible ? 'border-emerald-300 bg-emerald-50 text-emerald-900 dark:bg-emerald-500/10 dark:text-emerald-200' : 'border-rose-300 bg-rose-50 text-rose-900 dark:bg-rose-500/10 dark:text-rose-200'}`}>
                  <div className="flex items-center gap-1.5 font-medium">
                    {c.eligible ? <CheckCircle2 className="h-4 w-4" /> : <AlertTriangle className="h-4 w-4" />}
                    {c.eligible ? tx('Eligible') : tx('Not eligible')}
                    {c.capacityT && <span className="font-normal"> · {tx('Capacity {{t}} t', { t: num(c.capacityT, 1) })}</span>}
                  </div>
                  {c.reasons.map((r) => <div key={r}>• {r}</div>)}
                  {c.warnings.map((w) => <div key={w} className="text-amber-700 dark:text-amber-300">⚠ {w}</div>)}
                </div>
              )}
            </>
          ) : (
            <>
              <Field label={tx('Transporter')}>
                <OptionalSelect value={partnerId} onChange={(v) => { setPartnerId(v); setRateId(''); }} options={(l?.transporters ?? []).map((p) => ({ value: p.id, label: p.name }))} placeholder={tx('Choose the transporter')} />
              </Field>
              <Field label={tx('Rate card')}>
                <OptionalSelect value={rateId} onChange={setRateId} noneLabel={tx('Agreed rate below')} options={rates.map((r) => ({ value: r.id, label: `${r.name} · ${FREIGHT_BASIS_LABEL[r.basis]} · ${num(r.rate, 2)}` }))} placeholder={tx('Agreed rate below')} />
              </Field>
              {!rateId && (
                <div className="grid grid-cols-2 gap-3">
                  <Field label={tx('Freight basis')}>
                    <OptionalSelect value={basis} onChange={setBasis} options={toOptions(FREIGHT_BASIS_LABEL)} placeholder={tx('Choose…')} />
                  </Field>
                  <Field label={tx('Rate')}>
                    <Input type="number" min={0} step="0.01" value={rate} onChange={(e) => setRate(e.target.value)} />
                  </Field>
                </div>
              )}
              <div className="grid grid-cols-2 gap-3">
                <Field label={tx('Truck plate')}><Input value={plate} onChange={(e) => setPlate(e.target.value)} /></Field>
                <Field label={tx('Driver name')}><Input value={driverName} onChange={(e) => setDriverName(e.target.value)} /></Field>
              </div>
            </>
          )}
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={onClose}>{tx('Cancel')}</Button>
          <Button onClick={save} disabled={assign.isPending || ownInvalid || transporterInvalid}>{tx('Assign')}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

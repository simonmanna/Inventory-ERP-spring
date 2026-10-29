import { useEffect, useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { ArrowLeft, Camera, LogIn, LogOut, Search } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Textarea } from '@/components/ui/textarea';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Skeleton } from '@/components/ui/skeleton';
import { SearchableSelect } from '@/components/ui/searchable-select';
import { notify, apiMessage } from '@/lib/notify';
import { useDebouncedValue } from '@/lib/use-debounced-value';
import { dateTime } from '@/lib/format';
import { cn } from '@/lib/utils';
import { tx } from '@/lib/i18n/tx';
import { useVehicle, useVehicles, type Vehicle } from '@/features/fleet/api';
import {
  CONDITION_LABEL, DAMAGE_LABEL, useCheckIn, useCheckOut, useCheckout, useCheckoutPhoto, useDriverOptions, useEligibility,
  useOpenCheckout, type Checkout, type Condition, driverLabel,
} from '@/features/fleet/drivers-api';
import { Field, VehicleStatusPill } from './fleet-ui';
import { num } from './fleet-format';

const FUEL_STEPS = [0, 25, 50, 75, 100];

/**
 * Yard / driver screen (fleet 1.2), phone first: pick the vehicle, then check it out to a driver or back in — meters,
 * fuel level, condition, damage flags and photos (camera capture on phones).
 */
export function CheckoutPage() {
  const [params, setParams] = useSearchParams();
  const vehicleId = params.get('vehicleId') ?? '';
  const checkoutId = params.get('checkoutId') ?? '';
  const navigate = useNavigate();
  const vehicle = useVehicle(vehicleId || undefined);
  const open = useOpenCheckout(vehicleId || undefined);
  const viewed = useCheckout(checkoutId || undefined);

  return (
    <div className="mx-auto max-w-xl space-y-4 pb-10">
      <div className="flex items-center gap-2">
        <Button variant="ghost" size="icon" aria-label={tx('Back')} onClick={() => (vehicleId ? navigate(`/fleet/vehicles/${vehicleId}`) : navigate('/fleet'))}>
          <ArrowLeft className="h-5 w-5" />
        </Button>
        <h1 className="text-xl font-bold">{tx('Vehicle check-out / check-in')}</h1>
      </div>
      {!vehicleId && <VehiclePicker onPick={(id) => setParams({ vehicleId: id })} />}
      {vehicleId && (vehicle.isLoading || open.isLoading) && <Skeleton className="h-64 w-full" />}
      {vehicleId && vehicle.isError && <p className="p-6 text-center text-muted-foreground">{tx('Vehicle not found')}</p>}
      {vehicle.data && !open.isLoading && (
        <>
          <VehicleHeader v={vehicle.data} onChange={() => setParams({})} />
          {viewed.data && viewed.data.status === 'closed' ? (
            <Summary c={viewed.data} />
          ) : open.data ? (
            <CheckInForm v={vehicle.data} c={open.data} />
          ) : (
            <CheckOutForm v={vehicle.data} />
          )}
        </>
      )}
    </div>
  );
}

function VehiclePicker({ onPick }: { onPick: (id: string) => void }) {
  const [search, setSearch] = useState('');
  const q = useDebouncedValue(search, 300);
  const { data, isLoading } = useVehicles({ page: 1, pageSize: 20, search: q || undefined, sortBy: 'code' });
  return (
    <Card>
      <CardContent className="space-y-3 pt-4">
        <div className="relative">
          <Search className="absolute left-3 top-3.5 h-4 w-4 text-muted-foreground" />
          <Input autoFocus className="h-11 pl-9 text-base" placeholder={tx('Plate, code or fleet number…')} value={search} onChange={(e) => setSearch(e.target.value)} />
        </div>
        {isLoading && <Skeleton className="h-24 w-full" />}
        <div className="space-y-2">
          {(data?.data ?? []).map((v) => (
            <button key={v.id} type="button" onClick={() => onPick(v.id)}
              className="flex w-full items-center justify-between gap-2 rounded-lg border px-4 py-3 text-left hover:bg-accent">
              <span>
                <span className="block font-medium">{v.registrationPlate ?? v.code}</span>
                <span className="block text-xs text-muted-foreground">{[v.code, v.name ?? [v.make, v.model].filter(Boolean).join(' '), v.category?.name].filter(Boolean).join(' · ')}</span>
              </span>
              <VehicleStatusPill status={v.status} />
            </button>
          ))}
          {!isLoading && (data?.data ?? []).length === 0 && <p className="py-4 text-center text-sm text-muted-foreground">{tx('No vehicles found.')}</p>}
        </div>
      </CardContent>
    </Card>
  );
}

function VehicleHeader({ v, onChange }: { v: Vehicle; onChange: () => void }) {
  const odo = v.meters.odometer;
  const hours = v.meters.hours;
  return (
    <Card>
      <CardContent className="flex items-center justify-between gap-3 pt-4">
        <div className="min-w-0">
          <div className="flex items-center gap-2"><span className="text-lg font-semibold">{v.registrationPlate ?? v.code}</span><VehicleStatusPill status={v.status} /></div>
          <div className="text-xs text-muted-foreground">
            {[v.code, v.category?.name, odo ? `${num(odo.value, 0)} km` : null, hours ? `${num(hours.value)} h` : null].filter(Boolean).join(' · ')}
          </div>
        </div>
        <Button variant="outline" size="sm" onClick={onChange}>{tx('Change')}</Button>
      </CardContent>
    </Card>
  );
}

/** Meters, fuel, condition, damage, notes, photos — shared by check-out and check-in. */
interface Inspection {
  odometer: string;
  hours: string;
  fuel: number | null;
  condition: Condition;
  flags: string[];
  notes: string;
  photos: File[];
}

const emptyInspection = (): Inspection => ({ odometer: '', hours: '', fuel: null, condition: 'good', flags: [], notes: '', photos: [] });

function InspectionFields({ v, value, onChange, errors }: {
  v: Vehicle; value: Inspection; onChange: (i: Inspection) => void; errors: Partial<Record<'odometer' | 'hours', string>>;
}) {
  const tracksOdo = v.meterType === 'odometer' || v.meterType === 'both';
  const tracksHours = v.meterType === 'hours' || v.meterType === 'both';
  const set = (patch: Partial<Inspection>) => onChange({ ...value, ...patch });
  return (
    <div className="space-y-4">
      {(tracksOdo || tracksHours) && (
        <div className="grid grid-cols-2 gap-3">
          {tracksOdo && (
            <Field label={tx('Odometer (km) *')} error={errors.odometer}>
              <Input inputMode="decimal" className="h-12 text-lg" value={value.odometer} onChange={(e) => set({ odometer: e.target.value })}
                placeholder={v.meters.odometer ? num(v.meters.odometer.value, 0) : ''} />
            </Field>
          )}
          {tracksHours && (
            <Field label={tx('Engine hours *')} error={errors.hours}>
              <Input inputMode="decimal" className="h-12 text-lg" value={value.hours} onChange={(e) => set({ hours: e.target.value })}
                placeholder={v.meters.hours ? num(v.meters.hours.value) : ''} />
            </Field>
          )}
        </div>
      )}
      <Field label={tx('Fuel level')}>
        <div className="grid grid-cols-5 gap-2">
          {FUEL_STEPS.map((f) => (
            <button key={f} type="button" onClick={() => set({ fuel: f })}
              className={cn('h-11 rounded-md border text-sm font-medium', value.fuel === f && 'border-primary bg-primary text-primary-foreground')}>
              {f === 0 ? tx('Empty') : f === 100 ? tx('Full') : `${f}%`}
            </button>
          ))}
        </div>
      </Field>
      <Field label={tx('Condition')}>
        <div className="grid grid-cols-3 gap-2">
          {(Object.keys(CONDITION_LABEL) as Condition[]).map((c) => (
            <button key={c} type="button" onClick={() => set({ condition: c })}
              className={cn('h-11 rounded-md border text-sm font-medium', value.condition === c && 'border-primary bg-primary text-primary-foreground')}>
              {CONDITION_LABEL[c]}
            </button>
          ))}
        </div>
      </Field>
      <Field label={tx('Damage')}>
        <div className="flex flex-wrap gap-2">
          {Object.entries(DAMAGE_LABEL).map(([k, label]) => {
            const on = value.flags.includes(k);
            return (
              <button key={k} type="button" onClick={() => set({ flags: on ? value.flags.filter((x) => x !== k) : [...value.flags, k] })}
                className={cn('h-10 rounded-full border px-3 text-sm', on && 'border-amber-500 bg-amber-100 text-amber-900 dark:bg-amber-500/20 dark:text-amber-100')}>
                {label}
              </button>
            );
          })}
        </div>
      </Field>
      <Field label={tx('Notes')}><Textarea rows={3} value={value.notes} onChange={(e) => set({ notes: e.target.value })} /></Field>
      <Field label={tx('Photos')}>
        <label className="flex h-12 cursor-pointer items-center justify-center gap-2 rounded-md border border-dashed text-sm">
          <Camera className="h-4 w-4" /> {value.photos.length ? tx('{{count}} photo(s) selected', { count: value.photos.length }) : tx('Take or choose photos')}
          <input type="file" accept="image/*" capture="environment" multiple className="hidden"
            onChange={(e) => set({ photos: [...value.photos, ...Array.from(e.target.files ?? [])] })} />
        </label>
      </Field>
    </div>
  );
}

function meterErrors(v: Vehicle, i: Inspection) {
  const e: Partial<Record<'odometer' | 'hours', string>> = {};
  const bad = (s: string) => s.trim() === '' || !Number.isFinite(Number(s)) || Number(s) < 0;
  if ((v.meterType === 'odometer' || v.meterType === 'both') && bad(i.odometer)) e.odometer = tx('Enter the odometer reading');
  if ((v.meterType === 'hours' || v.meterType === 'both') && bad(i.hours)) e.hours = tx('Enter the engine hours');
  return e;
}

function inspectionBody(i: Inspection) {
  return {
    odometer: i.odometer.trim() ? Number(i.odometer) : undefined,
    hours: i.hours.trim() ? Number(i.hours) : undefined,
    fuelLevelPct: i.fuel ?? undefined,
    condition: i.condition,
    damageFlags: i.flags,
    notes: i.notes.trim() || undefined,
  };
}

function useUploadPhotos() {
  const upload = useCheckoutPhoto();
  return async (id: string, phase: 'out' | 'in', photos: File[]) => {
    let failed = 0;
    for (const file of photos) {
      try {
        await upload.mutateAsync({ id, file, phase });
      } catch {
        failed++;
      }
    }
    if (failed) notify.error(tx('{{count}} photo(s) could not be uploaded', { count: failed }));
  };
}

function CheckOutForm({ v }: { v: Vehicle }) {
  const drivers = useDriverOptions();
  const checkOut = useCheckOut();
  const uploadPhotos = useUploadPhotos();
  const [driverId, setDriverId] = useState('');
  const [purpose, setPurpose] = useState('');
  const [insp, setInsp] = useState<Inspection>(emptyInspection);
  const [errors, setErrors] = useState<Partial<Record<'odometer' | 'hours', string>>>({});
  const eligibility = useEligibility(driverId, v.id, 'checked out');

  const submit = async () => {
    const e = meterErrors(v, insp);
    setErrors(e);
    if (Object.keys(e).length || !driverId) return;
    try {
      const c = await checkOut.mutateAsync({ vehicleId: v.id, body: { driverId, purpose: purpose.trim() || undefined, ...inspectionBody(insp) } });
      await uploadPhotos(c.id, 'out', insp.photos);
      notify.success(tx('Checked out ({{code}})', { code: c.code }));
      setInsp(emptyInspection());
      setDriverId('');
    } catch (err) {
      notify.error(apiMessage(err, tx('Could not check the vehicle out')));
    }
  };

  return (
    <Card>
      <CardHeader><CardTitle className="flex items-center gap-2 text-base"><LogOut className="h-4 w-4" /> {tx('Check out')}</CardTitle></CardHeader>
      <CardContent className="space-y-4">
        <Field label={tx('Driver *')}>
          <SearchableSelect value={driverId} onValueChange={setDriverId} placeholder={tx('Choose a driver')} searchPlaceholder={tx('Search…')}
            options={(drivers.data ?? []).map((d) => ({ value: d.id, label: d.name ?? d.code, code: d.code, disabled: !!d.openCheckout,
              hint: d.openCheckout ? tx('Has {{vehicle}} out', { vehicle: d.openCheckout.vehicleRegistrationPlate ?? d.openCheckout.vehicleCode }) : undefined }))} />
        </Field>
        {eligibility.data && !eligibility.data.eligible && (
          <ul className="list-disc space-y-1 rounded-md border border-rose-300 bg-rose-50 py-2 pl-8 pr-3 text-sm text-rose-800 dark:border-rose-500/40 dark:bg-rose-500/10 dark:text-rose-200">
            {eligibility.data.reasons.map((r) => <li key={r}>{r}</li>)}
          </ul>
        )}
        <Field label={tx('Purpose')}><Input value={purpose} onChange={(e) => setPurpose(e.target.value)} placeholder={tx('e.g. deliveries to the Kira site')} /></Field>
        <InspectionFields v={v} value={insp} onChange={setInsp} errors={errors} />
        <Button className="h-12 w-full text-base" onClick={submit} disabled={!driverId || checkOut.isPending || eligibility.data?.eligible === false}>
          <LogOut className="mr-2 h-5 w-5" /> {tx('Check out')}
        </Button>
      </CardContent>
    </Card>
  );
}

function CheckInForm({ v, c }: { v: Vehicle; c: Checkout }) {
  const checkIn = useCheckIn();
  const uploadPhotos = useUploadPhotos();
  const [insp, setInsp] = useState<Inspection>(emptyInspection);
  const [breakdown, setBreakdown] = useState(false);
  const [errors, setErrors] = useState<Partial<Record<'odometer' | 'hours', string>>>({});
  useEffect(() => setInsp(emptyInspection()), [c.id]);

  const submit = async () => {
    const e = meterErrors(v, insp);
    if (!e.odometer && c.outOdometer && Number(insp.odometer) < Number(c.outOdometer)) e.odometer = tx('Lower than at check-out');
    if (!e.hours && c.outHours && Number(insp.hours) < Number(c.outHours)) e.hours = tx('Lower than at check-out');
    setErrors(e);
    if (Object.keys(e).length) return;
    try {
      const done = await checkIn.mutateAsync({ id: c.id, body: { ...inspectionBody(insp), reportBreakdown: breakdown || undefined } });
      await uploadPhotos(done.id, 'in', insp.photos);
      notify.success(tx('Checked in ({{code}})', { code: done.code }));
    } catch (err) {
      notify.error(apiMessage(err, tx('Could not check the vehicle in')));
    }
  };

  return (
    <Card>
      <CardHeader><CardTitle className="flex items-center gap-2 text-base"><LogIn className="h-4 w-4" /> {tx('Check in')}</CardTitle></CardHeader>
      <CardContent className="space-y-4">
        <div className="rounded-md bg-muted/50 px-3 py-2 text-sm">
          <div>{tx('Out with {{driver}} since {{since}}', { driver: `${c.driverCode} · ${driverLabel(c)}`, since: dateTime(c.checkedOutAt) })}</div>
          <div className="text-xs text-muted-foreground">
            {[c.code, c.outOdometer ? `${num(c.outOdometer, 0)} km` : null, c.outHours ? `${num(c.outHours)} h` : null,
              c.outFuelLevelPct ? tx('fuel {{pct}}%', { pct: num(c.outFuelLevelPct, 0) }) : null].filter(Boolean).join(' · ')}
          </div>
        </div>
        <InspectionFields v={v} value={insp} onChange={setInsp} errors={errors} />
        <label className="flex min-h-11 items-center gap-2 rounded-md border px-3 text-sm">
          <input type="checkbox" className="h-4 w-4" checked={breakdown} onChange={(e) => setBreakdown(e.target.checked)} />
          {tx('Report a breakdown (the vehicle is taken off the road)')}
        </label>
        <Button className="h-12 w-full text-base" onClick={submit} disabled={checkIn.isPending}>
          <LogIn className="mr-2 h-5 w-5" /> {tx('Check in')}
        </Button>
      </CardContent>
    </Card>
  );
}

function Summary({ c }: { c: Checkout }) {
  return (
    <Card>
      <CardHeader><CardTitle className="text-base">{tx('Check-out {{code}}', { code: c.code })}</CardTitle></CardHeader>
      <CardContent className="space-y-2 text-sm">
        <p>{tx('Driver')}: {c.driverCode} · {driverLabel(c)}</p>
        <p>{tx('Out')}: {dateTime(c.checkedOutAt)} · {c.outOdometer ? `${num(c.outOdometer, 0)} km` : ''} {c.outHours ? `${num(c.outHours)} h` : ''}</p>
        <p>{tx('In')}: {c.checkedInAt ? dateTime(c.checkedInAt) : '—'} · {c.inOdometer ? `${num(c.inOdometer, 0)} km` : ''} {c.inHours ? `${num(c.inHours)} h` : ''}</p>
        <p>{tx('Used')}: {[c.distanceKm ? `${num(c.distanceKm)} km` : null, c.hoursUsed ? `${num(c.hoursUsed)} h` : null].filter(Boolean).join(' · ') || '—'}</p>
        {(c.inNotes || c.outNotes) && <p className="whitespace-pre-wrap text-muted-foreground">{[c.outNotes, c.inNotes].filter(Boolean).join(' — ')}</p>}
        {(c.photos ?? []).length > 0 && (
          <div className="grid grid-cols-3 gap-2 pt-2">
            {(c.photos ?? []).map((p) => (
              <a key={p.id} href={p.url} target="_blank" rel="noreferrer" className="block overflow-hidden rounded-md border">
                <img src={p.url} alt={p.filename} className="h-24 w-full object-cover" />
                <span className="block px-1 py-0.5 text-[10px] text-muted-foreground">{p.phase === 'out' ? tx('Out') : tx('In')}</span>
              </a>
            ))}
          </div>
        )}
      </CardContent>
    </Card>
  );
}

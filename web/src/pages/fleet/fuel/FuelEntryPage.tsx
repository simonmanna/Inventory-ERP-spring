import { useEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import { useNavigate, useParams, useSearchParams } from 'react-router-dom';
import { ArrowLeft, Camera, Fuel } from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Textarea } from '@/components/ui/textarea';
import { Card, CardContent } from '@/components/ui/card';
import { Skeleton } from '@/components/ui/skeleton';
import { SearchableSelect } from '@/components/ui/searchable-select';
import { useAuthStore } from '@/stores/auth.store';
import { notify, apiMessage } from '@/lib/notify';
import { money } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';
import {
  PAYMENT_LABEL, SOURCE_LABEL, useCreateFuelLog, useEmployeePicker, useFuelLog, useFuelLookups, useUpdateFuelLog,
  useUploadReceipt, type FuelPayment, type FuelSource,
} from '@/features/fleet/fuel-api';
import { Field, OptionalSelect, RemotePicker } from '../fleet-ui';
import { fromLocalInput, num, toLocalInput } from '../fleet-format';
import { cn } from '@/lib/utils';

interface FormState {
  vehicleId: string;
  source: FuelSource;
  fuelledAt: string;
  litres: string;
  fullTank: boolean;
  odometer: string;
  hours: string;
  paymentMethod: FuelPayment;
  unitPrice: string;
  amount: string;
  paymentAccountId: string;
  fuelCardId: string;
  supplierId: string;
  stationName: string;
  receiptNumber: string;
  tankId: string;
  employeeId: string;
  employeeLabel: string;
  costCenterId: string;
  notes: string;
}

const EMPTY: FormState = {
  vehicleId: '', source: 'station', fuelledAt: '', litres: '', fullTank: true, odometer: '', hours: '',
  paymentMethod: 'fuel_card', unitPrice: '', amount: '', paymentAccountId: '', fuelCardId: '', supplierId: '',
  stationName: '', receiptNumber: '', tankId: '', employeeId: '', employeeLabel: '', costCenterId: '', notes: '',
};

/** Big tappable segment buttons for the yard (source, payment). */
function Segments<T extends string>({ value, onChange, options }: {
  value: T; onChange: (v: T) => void; options: Array<{ value: T; label: string }>;
}) {
  return (
    <div className="grid gap-2" style={{ gridTemplateColumns: `repeat(${options.length}, minmax(0, 1fr))` }}>
      {options.map((o) => (
        <button key={o.value} type="button" onClick={() => onChange(o.value)}
          className={cn('h-12 rounded-lg border px-2 text-sm font-medium transition-colors',
            value === o.value ? 'border-primary bg-primary text-primary-foreground' : 'bg-background hover:bg-accent')}>
          {o.label}
        </button>
      ))}
    </div>
  );
}

function Section({ children }: { children: ReactNode }) {
  return <Card><CardContent className="space-y-4 pt-4">{children}</CardContent></Card>;
}

/**
 * Fuel quick entry (fleet 1.5), phone first: vehicle, station or site tank, litres, meter, payment, receipt photo
 * (camera). Saves a draft, or posts at once with fleet_fuel:post. `/fleet/fuel/logs/:id/edit` edits a draft.
 */
export function FuelEntryPage() {
  const { id } = useParams<{ id: string }>();
  const [params] = useSearchParams();
  const navigate = useNavigate();
  const has = useAuthStore((s) => s.hasPermission);
  const editing = !!id;
  const existing = useFuelLog(id);
  const lookups = useFuelLookups();
  const create = useCreateFuelLog();
  const update = useUpdateFuelLog();
  const upload = useUploadReceipt();
  const [f, setF] = useState<FormState>(() => ({
    ...EMPTY,
    vehicleId: params.get('vehicleId') ?? '',
    tankId: params.get('tankId') ?? '',
    source: params.get('tankId') ? 'tank' : 'station',
    fuelledAt: toLocalInput(new Date()),
  }));
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [receipt, setReceipt] = useState<File | null>(null);
  const [employeeSearch, setEmployeeSearch] = useState('');
  const employees = useEmployeePicker(employeeSearch, employeeSearch.trim().length > 0);
  const fileRef = useRef<HTMLInputElement>(null);
  const loaded = useRef(false);

  useEffect(() => {
    const l = existing.data;
    if (!editing || !l || loaded.current) return;
    loaded.current = true;
    setF({
      vehicleId: l.vehicleId, source: l.source, fuelledAt: toLocalInput(new Date(l.fuelledAt)), litres: l.litres,
      fullTank: l.fullTank, odometer: l.odometer ?? '', hours: l.hours ?? '', paymentMethod: l.paymentMethod ?? 'fuel_card',
      unitPrice: l.unitPrice ?? '', amount: l.amount ?? '', paymentAccountId: l.paymentAccountId ?? '',
      fuelCardId: l.fuelCardId ?? '', supplierId: l.supplierId ?? '', stationName: l.stationName ?? '',
      receiptNumber: l.receiptNumber ?? '', tankId: l.tankId ?? '', employeeId: l.employeeId ?? '',
      employeeLabel: l.employee?.name ?? '', costCenterId: l.costCenterId ?? '', notes: l.notes ?? '',
    });
  }, [editing, existing.data]);

  const data = lookups.data;
  const vehicle = useMemo(() => data?.vehicles.find((v) => v.id === f.vehicleId), [data, f.vehicleId]);
  const tracksOdo = vehicle ? vehicle.meterType === 'odometer' || vehicle.meterType === 'both' : true;
  const tracksHours = vehicle ? vehicle.meterType === 'hours' || vehicle.meterType === 'both' : false;
  const tank = data?.tanks.find((t) => t.id === f.tankId);
  const set = <K extends keyof FormState>(k: K, v: FormState[K]) => setF((s) => ({ ...s, [k]: v }));

  // Suggest the card assigned to the vehicle.
  useEffect(() => {
    if (!vehicle || f.fuelCardId || editing) return;
    const card = data?.cards.find((c) => c.vehicleId === vehicle.id);
    if (card) setF((s) => ({ ...s, fuelCardId: card.id }));
  }, [vehicle, data, f.fuelCardId, editing]);

  if (editing && existing.isLoading) return <Skeleton className="h-96 w-full" />;
  if (editing && existing.data && existing.data.status !== 'draft') {
    return <p className="p-8 text-center text-muted-foreground">{tx('Only draft fuel logs can be edited.')}</p>;
  }

  const litresN = Number(f.litres);
  const estimate = f.source === 'station' && litresN > 0 && Number(f.unitPrice) > 0 && !f.amount
    ? litresN * Number(f.unitPrice) : null;
  const overfill = vehicle?.tankCapacityL && litresN > Number(vehicle.tankCapacityL);

  const validate = () => {
    const e: Record<string, string> = {};
    if (!f.vehicleId) e.vehicleId = tx('Choose the vehicle');
    if (!(litresN > 0)) e.litres = tx('Enter the litres');
    if (vehicle && vehicle.meterType !== 'none' && !f.odometer && !f.hours) e.meter = tx('Enter the meter reading');
    if (f.source === 'tank' && !f.tankId) e.tankId = tx('Choose the tank');
    if (f.source === 'station') {
      if (!f.unitPrice && !f.amount) e.amount = tx('Enter the amount or the price per litre');
      if (f.paymentMethod === 'cash' && !f.paymentAccountId) e.paymentAccountId = tx('Choose the account');
      if (f.paymentMethod === 'fuel_card' && !f.fuelCardId) e.fuelCardId = tx('Choose the card');
      if (f.paymentMethod === 'credit' && !f.supplierId) e.supplierId = tx('Choose the supplier');
    }
    setErrors(e);
    return Object.keys(e).length === 0;
  };

  const body = (post: boolean) => {
    const station = f.source === 'station';
    const b: Record<string, unknown> = {
      vehicleId: f.vehicleId,
      source: f.source,
      fuelledAt: fromLocalInput(f.fuelledAt),
      litres: Number(f.litres),
      fullTank: f.fullTank,
      odometer: f.odometer && tracksOdo ? Number(f.odometer) : undefined,
      hours: f.hours && tracksHours ? Number(f.hours) : undefined,
      tankId: station ? undefined : f.tankId,
      paymentMethod: station ? f.paymentMethod : undefined,
      unitPrice: station && f.unitPrice ? Number(f.unitPrice) : undefined,
      amount: station && f.amount ? Number(f.amount) : undefined,
      paymentAccountId: station && f.paymentMethod === 'cash' ? f.paymentAccountId : undefined,
      fuelCardId: station && f.paymentMethod === 'fuel_card' ? f.fuelCardId : undefined,
      supplierId: station && f.supplierId ? f.supplierId : undefined,
      stationName: station && f.stationName.trim() ? f.stationName.trim() : undefined,
      receiptNumber: f.receiptNumber.trim() || undefined,
      employeeId: f.employeeId || undefined,
      costCenterId: f.costCenterId || undefined,
      notes: f.notes.trim() || undefined,
    };
    if (!editing) b.post = post;
    return b;
  };

  const save = async (post: boolean) => {
    if (!validate()) return;
    try {
      let out;
      if (editing && id) {
        const patch = body(false);
        const clear = (['odometer', 'hours', 'unitPrice', 'amount', 'supplierId', 'stationName', 'receiptNumber', 'employeeId', 'costCenterId', 'notes'] as const)
          .filter((k) => patch[k] === undefined && existing.data?.[k] != null);
        out = await update.mutateAsync({ id, body: { ...patch, clear } });
      } else {
        out = await create.mutateAsync(body(post));
      }
      if (receipt) {
        try {
          await upload.mutateAsync({ id: out.id, file: receipt });
        } catch (err) {
          notify.error(apiMessage(err, tx('The log was saved but the receipt could not be uploaded')));
        }
      }
      notify.success(out.status === 'posted' ? tx('Fuel log {{code}} posted', { code: out.code }) : tx('Fuel log {{code}} saved as draft', { code: out.code }));
      navigate(`/fleet/fuel/logs/${out.id}`);
    } catch (err) {
      notify.error(apiMessage(err, tx('Could not save the fuel log')));
    }
  };

  const busy = create.isPending || update.isPending || upload.isPending;
  const vehicleOptions = (data?.vehicles ?? []).map((v) => ({
    value: v.id,
    label: [v.code, v.registrationPlate, v.name].filter(Boolean).join(' · '),
    hint: v.categoryName,
  }));

  return (
    <div className="mx-auto max-w-2xl space-y-4 pb-24">
      <div className="flex items-center gap-2">
        <Button variant="ghost" size="icon" aria-label={tx('Back')} onClick={() => navigate(-1)}><ArrowLeft className="h-5 w-5" /></Button>
        <Fuel className="h-5 w-5 text-primary" />
        <h1 className="text-xl font-bold">{editing ? tx('Edit fuel log') : tx('Record fuel')}</h1>
      </div>

      <Section>
        <Field label={tx('Vehicle / equipment *')} error={errors.vehicleId}>
          <SearchableSelect value={f.vehicleId} onValueChange={(v) => set('vehicleId', v)} options={vehicleOptions}
            placeholder={tx('Choose the vehicle')} searchPlaceholder={tx('Search code or plate…')} />
        </Field>
        {vehicle && (
          <p className="text-xs text-muted-foreground">
            {[vehicle.tankCapacityL ? tx('Tank {{litres}} L', { litres: num(vehicle.tankCapacityL, 0) }) : null,
              vehicle.currentOdometer ? tx('Last odometer {{km}} km', { km: num(vehicle.currentOdometer, 0) }) : null,
              vehicle.currentHours ? tx('Last hours {{h}} h', { h: num(vehicle.currentHours, 1) }) : null].filter(Boolean).join(' · ')}
          </p>
        )}
        <Field label={tx('Where from')}>
          <Segments value={f.source} onChange={(v) => set('source', v)} options={[
            { value: 'station', label: SOURCE_LABEL.station }, { value: 'tank', label: SOURCE_LABEL.tank },
          ]} />
        </Field>
        <div className="grid grid-cols-2 gap-3">
          <Field label={tx('Litres *')} error={errors.litres}>
            <Input type="number" inputMode="decimal" min="0" step="0.001" className="h-12 text-lg" value={f.litres} onChange={(e) => set('litres', e.target.value)} />
          </Field>
          <Field label={tx('When')}>
            <Input type="datetime-local" className="h-12" value={f.fuelledAt} onChange={(e) => set('fuelledAt', e.target.value)} />
          </Field>
        </div>
        {overfill && <p className="text-sm text-amber-700">{tx('More than the tank size — it will be flagged as an overfill.')}</p>}
        <label className="flex h-12 items-center gap-3 rounded-lg border px-3 text-sm">
          <input type="checkbox" className="h-5 w-5" checked={f.fullTank} onChange={(e) => set('fullTank', e.target.checked)} />
          {tx('Filled to full (used for the efficiency calculation)')}
        </label>
        <div className="grid grid-cols-2 gap-3">
          {tracksOdo && (
            <Field label={tx('Odometer (km)')} error={errors.meter}>
              <Input type="number" inputMode="decimal" min="0" step="0.1" className="h-12 text-lg" placeholder={vehicle?.currentOdometer ?? ''}
                value={f.odometer} onChange={(e) => set('odometer', e.target.value)} />
            </Field>
          )}
          {tracksHours && (
            <Field label={tx('Engine hours')} error={tracksOdo ? undefined : errors.meter}>
              <Input type="number" inputMode="decimal" min="0" step="0.1" className="h-12 text-lg" placeholder={vehicle?.currentHours ?? ''}
                value={f.hours} onChange={(e) => set('hours', e.target.value)} />
            </Field>
          )}
        </div>
      </Section>

      {f.source === 'tank' ? (
        <Section>
          <Field label={tx('Site tank / bowser *')} error={errors.tankId}>
            <OptionalSelect value={f.tankId} onChange={(v) => set('tankId', v)} placeholder={tx('Choose the tank')}
              options={(data?.tanks ?? []).map((t) => ({ value: t.id, label: `${t.code} · ${t.name} (${num(t.bookLitres, 0)} L)` }))} />
          </Field>
          {tank && <p className="text-xs text-muted-foreground">{tx('Issued at stock cost (average or FIFO) when posted. Book level {{litres}} L.', { litres: num(tank.bookLitres, 0) })}</p>}
        </Section>
      ) : (
        <Section>
          <Field label={tx('Paid by')}>
            <Segments value={f.paymentMethod} onChange={(v) => set('paymentMethod', v)} options={[
              { value: 'fuel_card', label: PAYMENT_LABEL.fuel_card }, { value: 'cash', label: PAYMENT_LABEL.cash },
              { value: 'credit', label: PAYMENT_LABEL.credit },
            ]} />
          </Field>
          <div className="grid grid-cols-2 gap-3">
            <Field label={tx('Price per litre')}>
              <Input type="number" inputMode="decimal" min="0" step="0.0001" className="h-12" value={f.unitPrice} onChange={(e) => set('unitPrice', e.target.value)} />
            </Field>
            <Field label={tx('Amount paid')} error={errors.amount}>
              <Input type="number" inputMode="decimal" min="0" step="0.01" className="h-12" placeholder={estimate ? String(Math.round(estimate * 100) / 100) : ''}
                value={f.amount} onChange={(e) => set('amount', e.target.value)} />
            </Field>
          </div>
          {estimate !== null && <p className="text-xs text-muted-foreground">{tx('Amount: {{amount}}', { amount: money(estimate) })}</p>}
          {f.paymentMethod === 'fuel_card' && (
            <Field label={tx('Fuel card *')} error={errors.fuelCardId}>
              <OptionalSelect value={f.fuelCardId} onChange={(v) => set('fuelCardId', v)} placeholder={tx('Choose the card')}
                options={(data?.cards ?? []).map((c) => ({ value: c.id, label: [c.maskedNumber, c.label].filter(Boolean).join(' · ') }))} />
            </Field>
          )}
          {f.paymentMethod === 'cash' && (
            <Field label={tx('Paid from account *')} error={errors.paymentAccountId}>
              <OptionalSelect value={f.paymentAccountId} onChange={(v) => set('paymentAccountId', v)} placeholder={tx('Choose the account')}
                options={(data?.paymentAccounts ?? []).map((a) => ({ value: a.id, label: a.name }))} />
            </Field>
          )}
          <Field label={f.paymentMethod === 'credit' ? tx('Supplier *') : tx('Supplier')} error={errors.supplierId}>
            <SearchableSelect value={f.supplierId} onValueChange={(v) => set('supplierId', v)} placeholder={tx('Choose the supplier')}
              options={(data?.suppliers ?? []).map((s) => ({ value: s.id, label: s.name }))} />
          </Field>
          <div className="grid grid-cols-2 gap-3">
            <Field label={tx('Station')}>
              <Input value={f.stationName} onChange={(e) => set('stationName', e.target.value)} />
            </Field>
            <Field label={tx('Receipt no.')}>
              <Input value={f.receiptNumber} onChange={(e) => set('receiptNumber', e.target.value)} />
            </Field>
          </div>
        </Section>
      )}

      <Section>
        <Field label={tx('Receipt photo')}>
          <input ref={fileRef} type="file" accept="image/*,application/pdf" capture="environment" className="hidden"
            onChange={(e) => setReceipt(e.target.files?.[0] ?? null)} />
          <Button type="button" variant="outline" className="h-12 w-full" onClick={() => fileRef.current?.click()}>
            <Camera className="mr-2 h-5 w-5" /> {receipt ? receipt.name : tx('Take or choose a photo')}
          </Button>
        </Field>
        <Field label={tx('Fuelled by (employee)')}>
          <RemotePicker value={f.employeeId} selectedLabel={f.employeeLabel} search={employeeSearch} onSearch={setEmployeeSearch}
            results={(employees.data ?? []).map((e) => ({ value: e.id, label: e.name ?? e.employeeCode, hint: e.employeeCode }))}
            loading={employees.isFetching} onPick={(v, label) => setF((s) => ({ ...s, employeeId: v, employeeLabel: label }))}
            placeholder={tx('Type a name or employee code…')} clearLabel={tx('Clear')} />
        </Field>
        <Field label={tx('Cost centre')}>
          <OptionalSelect value={f.costCenterId} onChange={(v) => set('costCenterId', v)} placeholder={tx("Vehicle's cost centre")}
            noneLabel={tx("Vehicle's cost centre")} options={(data?.costCenters ?? []).map((c) => ({ value: c.id, label: `${c.code} · ${c.name}` }))} />
        </Field>
        <Field label={tx('Notes')}>
          <Textarea rows={2} value={f.notes} onChange={(e) => set('notes', e.target.value)} />
        </Field>
      </Section>

      <div className="fixed inset-x-0 bottom-0 z-10 border-t bg-background/95 p-3 backdrop-blur sm:static sm:border-0 sm:bg-transparent sm:p-0">
        <div className="mx-auto flex max-w-2xl gap-2">
          <Button variant="outline" className="h-12 flex-1" disabled={busy} onClick={() => save(false)}>
            {editing ? tx('Save changes') : tx('Save draft')}
          </Button>
          {!editing && has(PERMISSIONS.fleetFuel.post) && (
            <Button className="h-12 flex-1" disabled={busy} onClick={() => save(true)}>{tx('Save & post')}</Button>
          )}
        </div>
      </div>
    </div>
  );
}

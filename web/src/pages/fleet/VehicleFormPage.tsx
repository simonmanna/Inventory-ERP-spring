import { useEffect, useMemo, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { useForm, Controller } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { z } from 'zod';
import { ArrowLeft, Save } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Textarea } from '@/components/ui/textarea';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Skeleton } from '@/components/ui/skeleton';
import { notify, apiMessage } from '@/lib/notify';
import { useDebouncedValue } from '@/lib/use-debounced-value';
import { tx } from '@/lib/i18n/tx';
import {
  FUEL_LABEL, METER_TYPE_LABEL, OWNERSHIP_LABEL, useAssetPicker, useCreateVehicle, useFleetLookups,
  usePartnerPicker, useUpdateVehicle, useVehicle, type Vehicle,
} from '@/features/fleet/api';
import { Field, OptionalSelect, RemotePicker } from './fleet-ui';
import { toOptions } from './fleet-format';

const nextYear = new Date().getFullYear() + 1;
const decimal = z.string().refine((v) => v === '' || (Number.isFinite(Number(v)) && Number(v) >= 0), tx('Enter a number of 0 or more'));

const schema = z.object({
  code: z.string().max(40),
  name: z.string().max(120),
  registrationPlate: z.string().max(20),
  vin: z.string().max(40),
  engineNumber: z.string().max(40),
  make: z.string().max(80),
  model: z.string().max(80),
  year: z.string().refine((v) => v === '' || (Number.isInteger(Number(v)) && Number(v) >= 1950 && Number(v) <= nextYear), tx('Enter a model year between 1950 and next year')),
  color: z.string().max(40),
  categoryId: z.string().min(1, tx('Choose a class')),
  ownership: z.enum(['owned', 'leased', 'hired', 'third_party']),
  ownerPartnerId: z.string(),
  payloadCapacityT: decimal,
  bodyVolumeM3: decimal,
  axleConfig: z.string().max(20),
  fuelType: z.string(),
  tankCapacityL: decimal,
  meterType: z.enum(['odometer', 'hours', 'both', 'none']),
  openingOdometer: decimal,
  openingHours: decimal,
  branchId: z.string(),
  homeLocationId: z.string(),
  costCenterId: z.string(),
  assetId: z.string(),
  acquiredOn: z.string(),
  notes: z.string().max(2000),
});
type FormValues = z.infer<typeof schema>;

const EMPTY: FormValues = {
  code: '', name: '', registrationPlate: '', vin: '', engineNumber: '', make: '', model: '', year: '', color: '',
  categoryId: '', ownership: 'owned', ownerPartnerId: '', payloadCapacityT: '', bodyVolumeM3: '', axleConfig: '',
  fuelType: 'diesel', tankCapacityL: '', meterType: 'odometer', openingOdometer: '', openingHours: '', branchId: '',
  homeLocationId: '', costCenterId: '', assetId: '', acquiredOn: '', notes: '',
};

const NUMERIC = ['year', 'payloadCapacityT', 'bodyVolumeM3', 'tankCapacityL', 'openingOdometer', 'openingHours'] as const;
/** Server-side clearable fields (FleetCommands.VEHICLE_CLEARABLE). */
const CLEARABLE = ['name', 'registrationPlate', 'vin', 'engineNumber', 'make', 'model', 'year', 'color', 'ownerPartnerId',
  'payloadCapacityT', 'bodyVolumeM3', 'axleConfig', 'tankCapacityL', 'branchId', 'homeLocationId', 'costCenterId',
  'assetId', 'acquiredOn', 'notes'];

function fromVehicle(v: Vehicle): FormValues {
  const s = (x: string | number | null | undefined) => (x === null || x === undefined ? '' : String(x));
  return {
    ...EMPTY,
    code: v.code, name: s(v.name), registrationPlate: s(v.registrationPlate), vin: s(v.vin),
    engineNumber: s(v.engineNumber), make: s(v.make), model: s(v.model), year: s(v.year), color: s(v.color),
    categoryId: v.categoryId, ownership: v.ownership, ownerPartnerId: s(v.ownerPartnerId),
    payloadCapacityT: s(v.payloadCapacityT), bodyVolumeM3: s(v.bodyVolumeM3), axleConfig: s(v.axleConfig),
    fuelType: v.fuelType, tankCapacityL: s(v.tankCapacityL), meterType: v.meterType, branchId: s(v.branchId),
    homeLocationId: s(v.homeLocationId), costCenterId: s(v.costCenterId), assetId: s(v.assetId),
    acquiredOn: s(v.acquiredOn), notes: s(v.notes),
  };
}

/** Register or edit a vehicle / piece of equipment (fleet 1.1). Mobile-friendly single column on phones. */
export function VehicleFormPage() {
  const { id } = useParams<{ id: string }>();
  const editing = !!id;
  const navigate = useNavigate();
  const existing = useVehicle(id);
  const lookups = useFleetLookups();
  const create = useCreateVehicle();
  const update = useUpdateVehicle();
  const form = useForm<FormValues>({ resolver: zodResolver(schema), defaultValues: EMPTY });
  const { register, handleSubmit, control, watch, setValue, reset, setError, formState: { errors, isSubmitting } } = form;

  const [partnerSearch, setPartnerSearch] = useState('');
  const [assetSearch, setAssetSearch] = useState('');
  const [partnerLabel, setPartnerLabel] = useState<string | null>(null);
  const [assetLabel, setAssetLabel] = useState<string | null>(null);
  const partners = usePartnerPicker(useDebouncedValue(partnerSearch, 300), partnerSearch.trim().length > 0);
  const assets = useAssetPicker(useDebouncedValue(assetSearch, 300), assetSearch.trim().length > 0);

  useEffect(() => {
    if (existing.data) {
      reset(fromVehicle(existing.data));
      setPartnerLabel(existing.data.ownerPartner?.name ?? null);
      setAssetLabel(existing.data.asset ? `${existing.data.asset.assetCode} · ${existing.data.asset.name}` : null);
    }
  }, [existing.data, reset]);

  const categories = useMemo(() => lookups.data?.categories ?? [], [lookups.data]);
  const categoryId = watch('categoryId');
  const ownership = watch('ownership');
  const meterType = watch('meterType');
  const category = categories.find((c) => c.id === categoryId);
  const plateRequired = category?.kind === 'road_vehicle' || category?.kind === 'trailer';
  const ownerRequired = ownership === 'hired' || ownership === 'third_party';

  // A new vehicle takes its class's default meter.
  useEffect(() => {
    if (!editing && category) setValue('meterType', category.defaultMeterType);
  }, [editing, category, setValue]);

  const onSubmit = async (values: FormValues) => {
    if (plateRequired && !values.registrationPlate.trim()) {
      setError('registrationPlate', { message: tx('A registration plate is required for road vehicles and trailers') });
      return;
    }
    if (ownerRequired && !values.ownerPartnerId) {
      setError('ownerPartnerId', { message: tx('Choose the owner / transporter') });
      return;
    }
    const body: Record<string, unknown> = {};
    const clear: string[] = [];
    const original = existing.data ? fromVehicle(existing.data) : EMPTY;
    for (const [key, raw] of Object.entries(values) as Array<[keyof FormValues, string]>) {
      const value = typeof raw === 'string' ? raw.trim() : raw;
      if (editing && (key === 'openingOdometer' || key === 'openingHours')) continue;
      if (value === '') {
        if (editing && original[key] !== '' && CLEARABLE.includes(key)) clear.push(key);
        continue;
      }
      if (editing && value === original[key]) continue;
      body[key] = (NUMERIC as readonly string[]).includes(key) ? Number(value) : value;
    }
    if (clear.length) body.clear = clear;
    try {
      if (editing) {
        await update.mutateAsync({ id: id!, body });
        notify.success(tx('Vehicle updated'));
        navigate(`/fleet/vehicles/${id}`);
      } else {
        const created = await create.mutateAsync(body);
        notify.success(tx('Vehicle registered'));
        navigate(`/fleet/vehicles/${created.id}`);
      }
    } catch (err) {
      notify.error(apiMessage(err, tx('Could not save the vehicle')));
    }
  };

  if (editing && existing.isLoading) return <Skeleton className="h-96 w-full" />;
  if (editing && existing.isError) return <p className="p-8 text-center text-muted-foreground">{tx('Vehicle not found')}</p>;

  const lk = lookups.data;

  return (
    <form onSubmit={handleSubmit(onSubmit)} className="mx-auto max-w-5xl space-y-4 pb-10">
      <div className="flex flex-wrap items-center gap-3">
        <Button type="button" variant="ghost" size="icon" aria-label={tx('Back')} onClick={() => navigate(editing ? `/fleet/vehicles/${id}` : '/fleet/vehicles')}>
          <ArrowLeft className="h-5 w-5" />
        </Button>
        <h1 className="text-2xl font-bold">{editing ? tx('Edit vehicle {{code}}', { code: existing.data?.code ?? '' }) : tx('Register vehicle')}</h1>
        <div className="ml-auto">
          <Button type="submit" disabled={isSubmitting}><Save className="mr-1.5 h-4 w-4" /> {tx('Save')}</Button>
        </div>
      </div>

      <Card>
        <CardHeader><CardTitle className="text-base">{tx('Identity')}</CardTitle></CardHeader>
        <CardContent className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3">
          <Field label={tx('Class')} error={errors.categoryId?.message}>
            <Controller control={control} name="categoryId" render={({ field }) => (
              <OptionalSelect value={field.value} onChange={field.onChange} placeholder={tx('Choose a class')}
                options={categories.map((c) => ({ value: c.id, label: c.name }))} />
            )} />
          </Field>
          <Field label={tx('Code')} error={errors.code?.message}>
            <Input {...register('code')} placeholder={editing ? '' : tx('Leave blank to number automatically')} />
          </Field>
          <Field label={tx('Name / fleet number')}><Input {...register('name')} /></Field>
          <Field label={plateRequired ? tx('Registration plate *') : tx('Registration plate')} error={errors.registrationPlate?.message}>
            <Input {...register('registrationPlate')} className="uppercase" />
          </Field>
          <Field label={tx('VIN / chassis number')}><Input {...register('vin')} className="uppercase" /></Field>
          <Field label={tx('Engine number')}><Input {...register('engineNumber')} /></Field>
          <Field label={tx('Make')}><Input {...register('make')} /></Field>
          <Field label={tx('Model')}><Input {...register('model')} /></Field>
          <Field label={tx('Year')} error={errors.year?.message}><Input inputMode="numeric" {...register('year')} /></Field>
          <Field label={tx('Colour')}><Input {...register('color')} /></Field>
        </CardContent>
      </Card>

      <Card>
        <CardHeader><CardTitle className="text-base">{tx('Ownership')}</CardTitle></CardHeader>
        <CardContent className="grid grid-cols-1 gap-4 sm:grid-cols-2">
          <Field label={tx('Ownership')}>
            <Controller control={control} name="ownership" render={({ field }) => (
              <OptionalSelect value={field.value} onChange={(v) => field.onChange(v || 'owned')} placeholder={tx('Ownership')} options={toOptions(OWNERSHIP_LABEL)} />
            )} />
          </Field>
          <Field label={ownerRequired ? tx('Owner / transporter *') : tx('Owner / lessor / transporter')} error={errors.ownerPartnerId?.message}>
            <Controller control={control} name="ownerPartnerId" render={({ field }) => (
              <RemotePicker
                value={field.value}
                selectedLabel={partnerLabel}
                search={partnerSearch}
                onSearch={setPartnerSearch}
                loading={partners.isFetching}
                results={(partners.data ?? []).map((p) => ({ value: p.id, label: p.name, hint: p.code ?? undefined }))}
                onPick={(v, label) => { field.onChange(v); setPartnerLabel(label || null); }}
                placeholder={tx('Search partners…')}
                clearLabel={tx('Clear')}
              />
            )} />
          </Field>
        </CardContent>
      </Card>

      <Card>
        <CardHeader><CardTitle className="text-base">{tx('Capacity, fuel and meters')}</CardTitle></CardHeader>
        <CardContent className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3">
          <Field label={tx('Payload capacity (t)')} error={errors.payloadCapacityT?.message}><Input inputMode="decimal" {...register('payloadCapacityT')} /></Field>
          <Field label={tx('Body volume (m³)')} error={errors.bodyVolumeM3?.message}><Input inputMode="decimal" {...register('bodyVolumeM3')} /></Field>
          <Field label={tx('Axle configuration')}><Input {...register('axleConfig')} placeholder="6x4" /></Field>
          <Field label={tx('Fuel type')}>
            <Controller control={control} name="fuelType" render={({ field }) => (
              <OptionalSelect value={field.value} onChange={(v) => field.onChange(v || 'diesel')} placeholder={tx('Fuel type')} options={toOptions(FUEL_LABEL)} />
            )} />
          </Field>
          <Field label={tx('Tank size (L)')} error={errors.tankCapacityL?.message}><Input inputMode="decimal" {...register('tankCapacityL')} /></Field>
          <Field label={tx('Meter type')}>
            <Controller control={control} name="meterType" render={({ field }) => (
              <OptionalSelect value={field.value} onChange={(v) => field.onChange(v || 'odometer')} placeholder={tx('Meter type')} options={toOptions(METER_TYPE_LABEL)} />
            )} />
          </Field>
          {!editing && (meterType === 'odometer' || meterType === 'both') && (
            <Field label={tx('Opening odometer (km)')} error={errors.openingOdometer?.message}><Input inputMode="decimal" {...register('openingOdometer')} /></Field>
          )}
          {!editing && (meterType === 'hours' || meterType === 'both') && (
            <Field label={tx('Opening engine hours')} error={errors.openingHours?.message}><Input inputMode="decimal" {...register('openingHours')} /></Field>
          )}
        </CardContent>
      </Card>

      <Card>
        <CardHeader><CardTitle className="text-base">{tx('Location, costing and asset')}</CardTitle></CardHeader>
        <CardContent className="grid grid-cols-1 gap-4 sm:grid-cols-2">
          <Field label={tx('Home branch')}>
            <Controller control={control} name="branchId" render={({ field }) => (
              <OptionalSelect value={field.value} onChange={field.onChange} placeholder={tx('None')} noneLabel={tx('None')}
                options={(lk?.branches ?? []).map((b) => ({ value: b.id, label: b.name }))} />
            )} />
          </Field>
          <Field label={tx('Home site / yard')}>
            <Controller control={control} name="homeLocationId" render={({ field }) => (
              <OptionalSelect value={field.value} onChange={field.onChange} placeholder={tx('None')} noneLabel={tx('None')}
                options={(lk?.locations ?? []).map((l) => ({ value: l.id, label: l.name }))} />
            )} />
          </Field>
          <Field label={tx('Cost centre')}>
            <Controller control={control} name="costCenterId" render={({ field }) => (
              <OptionalSelect value={field.value} onChange={field.onChange} placeholder={tx('None')} noneLabel={tx('None')}
                options={(lk?.costCenters ?? []).map((c) => ({ value: c.id, label: `${c.code ?? ''} ${c.name}`.trim() }))} />
            )} />
          </Field>
          <Field label={tx('Acquired on')}><Input type="date" {...register('acquiredOn')} /></Field>
          <Field label={tx('Fixed asset (depreciation stays in Fixed Assets)')} className="sm:col-span-2">
            <Controller control={control} name="assetId" render={({ field }) => (
              <RemotePicker
                value={field.value}
                selectedLabel={assetLabel}
                search={assetSearch}
                onSearch={setAssetSearch}
                loading={assets.isFetching}
                results={(assets.data ?? []).map((a) => ({
                  value: a.id,
                  label: `${a.assetCode} · ${a.name}`,
                  hint: a.linkedVehicleCode ? tx('Linked to {{code}}', { code: a.linkedVehicleCode }) : undefined,
                }))}
                onPick={(v, label) => { field.onChange(v); setAssetLabel(label || null); }}
                placeholder={tx('Search fixed assets…')}
                clearLabel={tx('Clear')}
              />
            )} />
          </Field>
          <Field label={tx('Notes')} className="sm:col-span-2"><Textarea rows={3} {...register('notes')} /></Field>
        </CardContent>
      </Card>
    </form>
  );
}

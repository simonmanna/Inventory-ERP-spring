import { useEffect, useState } from 'react';
import { useNavigate, useParams, useSearchParams } from 'react-router-dom';
import { ArrowLeft, LocateFixed, Plus, Trash2 } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Textarea } from '@/components/ui/textarea';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Skeleton } from '@/components/ui/skeleton';
import { SearchableSelect } from '@/components/ui/searchable-select';
import { notify, apiMessage } from '@/lib/notify';
import { tx } from '@/lib/i18n/tx';
import { useVehicles } from '@/features/fleet/api';
import {
  AT_FAULT_LABEL, INCIDENT_TYPE_LABEL, SEVERITY_LABEL, useCreateIncident, useEmployeePicker, useIncident,
  useUpdateIncident, type ThirdParty,
} from '@/features/fleet/tyres-api';
import { Field, OptionalSelect, RemotePicker } from './fleet-ui';
import { fromLocalInput, toLocalInput, toOptions } from './fleet-format';

const isNum = (v: string) => v !== '' && Number.isFinite(Number(v));

/** Report or edit an incident (fleet 1.7). Phone-friendly: large inputs, "use my location" for the GPS fix. */
export function IncidentFormPage() {
  const { id } = useParams<{ id: string }>();
  const [params] = useSearchParams();
  const navigate = useNavigate();
  const existing = useIncident(id);
  const vehicles = useVehicles({ page: 1, pageSize: 200, sortBy: 'code' });
  const create = useCreateIncident();
  const update = useUpdateIncident();
  const [vehicleId, setVehicleId] = useState(params.get('vehicleId') ?? '');
  const [driver, setDriver] = useState({ id: '', label: '' });
  const [driverSearch, setDriverSearch] = useState('');
  const [occurredAt, setOccurredAt] = useState(toLocalInput(new Date()));
  const [location, setLocation] = useState('');
  const [lat, setLat] = useState('');
  const [lon, setLon] = useState('');
  const [type, setType] = useState('collision');
  const [severity, setSeverity] = useState('minor');
  const [description, setDescription] = useState('');
  const [damage, setDamage] = useState('');
  const [estimate, setEstimate] = useState('');
  const [atFault, setAtFault] = useState('unknown');
  const [injuries, setInjuries] = useState('');
  const [parties, setParties] = useState<ThirdParty[]>([]);
  const [police, setPolice] = useState('');
  const [station, setStation] = useState('');
  const [repairRef, setRepairRef] = useState('');
  const [repairCost, setRepairCost] = useState('');
  const [workOrderId, setWorkOrderId] = useState('');
  const [notes, setNotes] = useState('');
  const [breakdown, setBreakdown] = useState(false);
  const [touched, setTouched] = useState(false);
  const employees = useEmployeePicker(driverSearch, driverSearch.trim().length > 0);

  const i = existing.data;
  useEffect(() => {
    if (!i) return;
    setVehicleId(i.vehicleId);
    setDriver({ id: i.driverEmployeeId ?? '', label: i.driver?.name ?? '' });
    setOccurredAt(toLocalInput(new Date(i.occurredAt)));
    setLocation(i.location ?? '');
    setLat(i.latitude ?? '');
    setLon(i.longitude ?? '');
    setType(i.incidentType);
    setSeverity(i.severity);
    setDescription(i.description);
    setDamage(i.damageDescription ?? '');
    setEstimate(i.estimatedCost ?? '');
    setAtFault(i.atFault);
    setInjuries(i.injuries ?? '');
    setParties(i.thirdParties ?? []);
    setPolice(i.policeReportNumber ?? '');
    setStation(i.policeStation ?? '');
    setRepairRef(i.repairReference ?? '');
    setRepairCost(i.repairCost ?? '');
    setWorkOrderId(i.workOrderId ?? '');
    setNotes(i.notes ?? '');
  }, [i]);

  if (id && existing.isLoading) return <Skeleton className="h-96 w-full" />;

  const errors = {
    vehicle: !vehicleId ? tx('Choose the vehicle') : undefined,
    description: !description.trim() ? tx('Describe what happened') : undefined,
    estimate: estimate !== '' && (!isNum(estimate) || Number(estimate) < 0) ? tx('Enter a valid amount') : undefined,
    coords: (lat !== '' && (!isNum(lat) || Math.abs(Number(lat)) > 90)) || (lon !== '' && (!isNum(lon) || Math.abs(Number(lon)) > 180))
      ? tx('Enter valid coordinates') : undefined,
    parties: parties.some((p) => !p.name.trim()) ? tx('Each third party needs a name') : undefined,
  };
  const valid = Object.values(errors).every((e) => !e);

  const locate = () => {
    if (!navigator.geolocation) return;
    navigator.geolocation.getCurrentPosition(
      (pos) => { setLat(pos.coords.latitude.toFixed(6)); setLon(pos.coords.longitude.toFixed(6)); },
      () => notify.error(tx('Could not read the location')),
    );
  };

  const submit = async () => {
    setTouched(true);
    if (!valid) return;
    const num = (v: string) => (v === '' ? undefined : Number(v));
    const body: Record<string, unknown> = {
      driverEmployeeId: driver.id || undefined, occurredAt: fromLocalInput(occurredAt), location: location.trim() || undefined,
      latitude: num(lat), longitude: num(lon), incidentType: type, severity, description: description.trim(),
      damageDescription: damage.trim() || undefined, estimatedCost: num(estimate), atFault, injuries: injuries.trim() || undefined,
      thirdParties: parties.map((p) => ({ name: p.name.trim(), phone: p.phone || undefined, vehiclePlate: p.vehiclePlate || undefined, insurer: p.insurer || undefined })),
      policeReportNumber: police.trim() || undefined, policeStation: station.trim() || undefined,
      repairReference: repairRef.trim() || undefined, repairCost: num(repairCost), workOrderId: workOrderId.trim() || undefined,
      notes: notes.trim() || undefined,
    };
    try {
      if (i) {
        const clear = ['driverEmployeeId', 'location', 'latitude', 'longitude', 'damageDescription', 'estimatedCost', 'injuries',
          'policeReportNumber', 'policeStation', 'workOrderId', 'repairReference', 'repairCost', 'notes']
          .filter((k) => body[k] === undefined && (i as unknown as Record<string, unknown>)[k] != null);
        await update.mutateAsync({ id: i.id, body: { ...body, clear } });
        notify.success(tx('Incident saved'));
        navigate(`/fleet/incidents/${i.id}`);
      } else {
        const out = await create.mutateAsync({ ...body, vehicleId, markVehicleBreakdown: breakdown });
        notify.success(tx('Incident reported'));
        navigate(`/fleet/incidents/${out.id}`);
      }
    } catch (err) {
      notify.error(apiMessage(err, tx('Could not save the incident')));
    }
  };

  const setParty = (idx: number, patch: Partial<ThirdParty>) =>
    setParties((ps) => ps.map((p, n) => (n === idx ? { ...p, ...patch } : p)));
  const show = (e?: string) => (touched ? e : undefined);

  return (
    <div className="mx-auto max-w-4xl space-y-4">
      <div className="flex items-center gap-3">
        <Button variant="ghost" size="icon" aria-label={tx('Back')} onClick={() => navigate(i ? `/fleet/incidents/${i.id}` : '/fleet/incidents')}><ArrowLeft className="h-5 w-5" /></Button>
        <h1 className="text-2xl font-bold">{i ? tx('Edit incident {{number}}', { number: i.incidentNumber }) : tx('Report incident')}</h1>
      </div>
      <Card>
        <CardHeader><CardTitle className="text-base">{tx('What happened')}</CardTitle></CardHeader>
        <CardContent className="grid grid-cols-1 gap-3 sm:grid-cols-2">
          <Field label={tx('Vehicle *')} error={show(errors.vehicle)}>
            {i ? <p className="text-sm font-medium">{i.vehicle.code} {i.vehicle.registrationPlate ?? ''}</p> : (
              <SearchableSelect value={vehicleId} onValueChange={setVehicleId} placeholder={tx('Choose a vehicle')} searchPlaceholder={tx('Search code or plate…')}
                options={(vehicles.data?.data ?? []).map((v) => ({ value: v.id, label: v.code, hint: v.registrationPlate ?? undefined }))} />
            )}
          </Field>
          <Field label={tx('Driver / operator')}>
            <RemotePicker value={driver.id} selectedLabel={driver.label} search={driverSearch} onSearch={setDriverSearch}
              results={(employees.data ?? []).map((e) => ({ value: e.id, label: e.name, hint: e.employeeCode }))}
              loading={employees.isFetching} onPick={(v, label) => setDriver({ id: v, label })} placeholder={tx('Search employees…')} clearLabel={tx('Clear')} />
          </Field>
          <Field label={tx('When *')}><Input type="datetime-local" className="h-11" value={occurredAt} max={toLocalInput(new Date())} onChange={(e) => setOccurredAt(e.target.value)} /></Field>
          <Field label={tx('Place')}><Input className="h-11" value={location} onChange={(e) => setLocation(e.target.value)} /></Field>
          <Field label={tx('GPS (latitude, longitude)')} error={show(errors.coords)} className="sm:col-span-2">
            <div className="flex gap-2">
              <Input inputMode="decimal" className="h-11" placeholder={tx('Latitude')} value={lat} onChange={(e) => setLat(e.target.value)} />
              <Input inputMode="decimal" className="h-11" placeholder={tx('Longitude')} value={lon} onChange={(e) => setLon(e.target.value)} />
              <Button type="button" variant="outline" className="h-11" onClick={locate}><LocateFixed className="mr-1 h-4 w-4" /> {tx('My location')}</Button>
            </div>
          </Field>
          <Field label={tx('Type')}><OptionalSelect value={type} onChange={(v) => setType(v || 'collision')} placeholder={tx('Type')} options={toOptions(INCIDENT_TYPE_LABEL)} /></Field>
          <Field label={tx('Severity')}><OptionalSelect value={severity} onChange={(v) => setSeverity(v || 'minor')} placeholder={tx('Severity')} options={toOptions(SEVERITY_LABEL)} /></Field>
          <Field label={tx('Description *')} error={show(errors.description)} className="sm:col-span-2">
            <Textarea rows={3} value={description} onChange={(e) => setDescription(e.target.value)} />
          </Field>
          <Field label={tx('Damage')} className="sm:col-span-2"><Textarea rows={2} value={damage} onChange={(e) => setDamage(e.target.value)} /></Field>
          <Field label={tx('Estimated cost')} error={show(errors.estimate)}><Input inputMode="decimal" value={estimate} onChange={(e) => setEstimate(e.target.value)} /></Field>
          <Field label={tx('At fault')}><OptionalSelect value={atFault} onChange={(v) => setAtFault(v || 'unknown')} placeholder={tx('At fault')} options={toOptions(AT_FAULT_LABEL)} /></Field>
          <Field label={tx('Injuries')} className="sm:col-span-2"><Input value={injuries} onChange={(e) => setInjuries(e.target.value)} /></Field>
          {!i && (
            <label className="flex items-center gap-2 text-sm sm:col-span-2">
              <input type="checkbox" checked={breakdown} onChange={(e) => setBreakdown(e.target.checked)} /> {tx('The vehicle cannot be used: mark it as breakdown')}
            </label>
          )}
        </CardContent>
      </Card>

      <Card>
        <CardHeader className="flex flex-row items-center justify-between space-y-0">
          <CardTitle className="text-base">{tx('Third parties')}</CardTitle>
          <Button size="sm" variant="outline" onClick={() => setParties((ps) => [...ps, { name: '' }])}><Plus className="mr-1 h-4 w-4" /> {tx('Add')}</Button>
        </CardHeader>
        <CardContent className="space-y-3">
          {parties.length === 0 && <p className="text-sm text-muted-foreground">{tx('No third parties involved.')}</p>}
          {parties.map((p, idx) => (
            <div key={idx} className="grid grid-cols-1 gap-2 rounded-lg border p-3 sm:grid-cols-5">
              <Input placeholder={tx('Name *')} value={p.name} onChange={(e) => setParty(idx, { name: e.target.value })} />
              <Input placeholder={tx('Phone')} value={p.phone ?? ''} onChange={(e) => setParty(idx, { phone: e.target.value })} />
              <Input placeholder={tx('Vehicle plate')} value={p.vehiclePlate ?? ''} onChange={(e) => setParty(idx, { vehiclePlate: e.target.value })} />
              <Input placeholder={tx('Insurer')} value={p.insurer ?? ''} onChange={(e) => setParty(idx, { insurer: e.target.value })} />
              <Button variant="ghost" size="icon" aria-label={tx('Remove')} onClick={() => setParties((ps) => ps.filter((_, n) => n !== idx))}><Trash2 className="h-4 w-4" /></Button>
            </div>
          ))}
          {show(errors.parties) && <p className="text-xs text-destructive">{errors.parties}</p>}
        </CardContent>
      </Card>

      <Card>
        <CardHeader><CardTitle className="text-base">{tx('Police and repair')}</CardTitle></CardHeader>
        <CardContent className="grid grid-cols-1 gap-3 sm:grid-cols-2">
          <Field label={tx('Police report number')}><Input value={police} onChange={(e) => setPolice(e.target.value)} /></Field>
          <Field label={tx('Police station')}><Input value={station} onChange={(e) => setStation(e.target.value)} /></Field>
          <Field label={tx('Repair reference')}><Input value={repairRef} onChange={(e) => setRepairRef(e.target.value)} /></Field>
          <Field label={tx('Repair cost')}><Input inputMode="decimal" value={repairCost} onChange={(e) => setRepairCost(e.target.value)} /></Field>
          <Field label={tx('Maintenance work order (id)')}><Input value={workOrderId} onChange={(e) => setWorkOrderId(e.target.value)} /></Field>
          <Field label={tx('Notes')} className="sm:col-span-2"><Textarea rows={2} value={notes} onChange={(e) => setNotes(e.target.value)} /></Field>
        </CardContent>
      </Card>

      <div className="flex justify-end gap-2">
        <Button variant="outline" onClick={() => navigate(-1)}>{tx('Cancel')}</Button>
        <Button className="h-11" onClick={submit} disabled={create.isPending || update.isPending}>{i ? tx('Save') : tx('Report incident')}</Button>
      </div>
    </div>
  );
}

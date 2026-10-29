import { useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { ArrowLeft, Plus, Save, Trash2 } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Textarea } from '@/components/ui/textarea';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { SearchableSelect } from '@/components/ui/searchable-select';
import { notify, apiMessage } from '@/lib/notify';
import { tx } from '@/lib/i18n/tx';
import { useVehicles } from '@/features/fleet/api';
import {
  BASIS_LABEL, EXPENSE_TYPE_LABEL, useCreateClaim, useDriverOptions, type AllowanceBasis, type ExpenseType,
} from '@/features/fleet/drivers-api';
import { Field, OptionalSelect } from './fleet-ui';
import { toOptions } from './fleet-format';

interface LineDraft {
  key: number;
  lineType: 'allowance' | 'expense';
  basis: AllowanceBasis;
  expenseType: ExpenseType;
  quantity: string;
  amount: string;
  description: string;
}

let seq = 0;
const newLine = (lineType: LineDraft['lineType']): LineDraft => ({
  key: ++seq, lineType, basis: 'per_day', expenseType: 'fuel', quantity: '1', amount: '', description: '',
});

/** New driver allowance / trip-expense claim (fleet 1.2). Allowance lines are priced by the allowance rules. */
export function ClaimFormPage() {
  const navigate = useNavigate();
  const [params] = useSearchParams();
  const drivers = useDriverOptions();
  const vehicles = useVehicles({ page: 1, pageSize: 200, sortBy: 'code' });
  const create = useCreateClaim();
  const today = new Date().toISOString().slice(0, 10);
  const [driverId, setDriverId] = useState(params.get('driverId') ?? '');
  const [vehicleId, setVehicleId] = useState(params.get('vehicleId') ?? '');
  const [claimDate, setClaimDate] = useState(today);
  const [routeZone, setRouteZone] = useState('');
  const [tripId, setTripId] = useState('');
  const [description, setDescription] = useState('');
  const [notes, setNotes] = useState('');
  const [lines, setLines] = useState<LineDraft[]>([newLine('allowance')]);
  const [errors, setErrors] = useState<Record<string, string>>({});

  const setLine = (key: number, patch: Partial<LineDraft>) => setLines((ls) => ls.map((l) => (l.key === key ? { ...l, ...patch } : l)));

  const validate = () => {
    const e: Record<string, string> = {};
    if (!driverId) e.driver = tx('Choose the driver');
    if (!claimDate) e.date = tx('Enter the claim date');
    else if (claimDate > today) e.date = tx('The claim date cannot be in the future');
    for (const l of lines) {
      if (l.lineType === 'allowance' && !(Number(l.quantity) > 0)) e[`q${l.key}`] = tx('Enter a quantity greater than zero');
      if (l.lineType === 'expense' && !(Number(l.amount) > 0)) e[`a${l.key}`] = tx('Enter an amount greater than zero');
    }
    setErrors(e);
    return Object.keys(e).length === 0;
  };

  const submit = async () => {
    if (!validate()) return;
    try {
      const claim = await create.mutateAsync({
        driverId, vehicleId: vehicleId || undefined, claimDate, routeZone: routeZone.trim() || undefined,
        tripId: tripId.trim() || undefined, description: description.trim() || undefined, notes: notes.trim() || undefined,
        lines: lines.map((l) => (l.lineType === 'allowance'
          ? { lineType: 'allowance', basis: l.basis, quantity: Number(l.quantity), description: l.description.trim() || undefined }
          : { lineType: 'expense', expenseType: l.expenseType, amount: Number(l.amount), description: l.description.trim() || undefined })),
      });
      notify.success(tx('Claim {{code}} saved as draft', { code: claim.code }));
      navigate(`/fleet/claims/${claim.id}`);
    } catch (err) {
      notify.error(apiMessage(err, tx('Could not save the claim')));
    }
  };

  return (
    <form onSubmit={(e) => { e.preventDefault(); void submit(); }} className="mx-auto max-w-4xl space-y-4 pb-10">
      <div className="flex flex-wrap items-center gap-3">
        <Button type="button" variant="ghost" size="icon" aria-label={tx('Back')} onClick={() => navigate('/fleet/claims')}><ArrowLeft className="h-5 w-5" /></Button>
        <h1 className="text-2xl font-bold">{tx('New claim')}</h1>
        <div className="ml-auto"><Button type="submit" disabled={create.isPending}><Save className="mr-1.5 h-4 w-4" /> {tx('Save draft')}</Button></div>
      </div>

      <Card>
        <CardHeader><CardTitle className="text-base">{tx('Claim')}</CardTitle></CardHeader>
        <CardContent className="grid grid-cols-1 gap-4 sm:grid-cols-2">
          <Field label={tx('Driver *')} error={errors.driver}>
            <SearchableSelect value={driverId} onValueChange={setDriverId} placeholder={tx('Choose a driver')} searchPlaceholder={tx('Search…')}
              options={(drivers.data ?? []).map((d) => ({ value: d.id, label: d.name ?? d.code, code: d.code }))} />
          </Field>
          <Field label={tx('Vehicle')}>
            <SearchableSelect value={vehicleId} onValueChange={setVehicleId} placeholder={tx('No vehicle')} searchPlaceholder={tx('Search…')}
              options={[{ value: '', label: tx('No vehicle') }, ...(vehicles.data?.data ?? []).map((v) => ({ value: v.id, label: v.registrationPlate ?? v.code, code: v.code, hint: v.category?.name }))]} />
          </Field>
          <Field label={tx('Date *')} error={errors.date}><Input type="date" max={today} value={claimDate} onChange={(e) => setClaimDate(e.target.value)} /></Field>
          <Field label={tx('Route zone')}><Input value={routeZone} onChange={(e) => setRouteZone(e.target.value)} /></Field>
          <Field label={tx('Trip reference')}><Input value={tripId} onChange={(e) => setTripId(e.target.value)} /></Field>
          <Field label={tx('Description')}><Input value={description} onChange={(e) => setDescription(e.target.value)} placeholder={tx('e.g. Kampala → Gulu delivery')} /></Field>
          <Field label={tx('Notes')} className="sm:col-span-2"><Textarea rows={2} value={notes} onChange={(e) => setNotes(e.target.value)} /></Field>
        </CardContent>
      </Card>

      <Card>
        <CardHeader className="flex flex-row items-center justify-between gap-2">
          <CardTitle className="text-base">{tx('Lines')}</CardTitle>
          <div className="flex gap-2">
            <Button type="button" size="sm" variant="outline" onClick={() => setLines((ls) => [...ls, newLine('allowance')])}><Plus className="mr-1 h-4 w-4" /> {tx('Allowance')}</Button>
            <Button type="button" size="sm" variant="outline" onClick={() => setLines((ls) => [...ls, newLine('expense')])}><Plus className="mr-1 h-4 w-4" /> {tx('Expense')}</Button>
          </div>
        </CardHeader>
        <CardContent className="space-y-3">
          <p className="text-sm text-muted-foreground">{tx('Allowance rates come from the allowance rules for the claim date, vehicle class and route zone.')}</p>
          {lines.length === 0 && <p className="text-sm text-muted-foreground">{tx('No lines yet.')}</p>}
          {lines.map((l) => (
            <div key={l.key} className="grid grid-cols-1 gap-3 rounded-md border p-3 sm:grid-cols-12">
              <div className="sm:col-span-3">
                {l.lineType === 'allowance' ? (
                  <Field label={tx('Allowance')}>
                    <OptionalSelect value={l.basis} onChange={(v) => setLine(l.key, { basis: (v || 'per_day') as AllowanceBasis })} placeholder={tx('Basis')} options={toOptions(BASIS_LABEL)} />
                  </Field>
                ) : (
                  <Field label={tx('Expense')}>
                    <OptionalSelect value={l.expenseType} onChange={(v) => setLine(l.key, { expenseType: (v || 'fuel') as ExpenseType })} placeholder={tx('Type')} options={toOptions(EXPENSE_TYPE_LABEL)} />
                  </Field>
                )}
              </div>
              <div className="sm:col-span-2">
                {l.lineType === 'allowance' ? (
                  <Field label={tx('Quantity')} error={errors[`q${l.key}`]}><Input inputMode="decimal" value={l.quantity} onChange={(e) => setLine(l.key, { quantity: e.target.value })} /></Field>
                ) : (
                  <Field label={tx('Amount')} error={errors[`a${l.key}`]}><Input inputMode="decimal" value={l.amount} onChange={(e) => setLine(l.key, { amount: e.target.value })} /></Field>
                )}
              </div>
              <div className="sm:col-span-6">
                <Field label={tx('Description')}><Input value={l.description} onChange={(e) => setLine(l.key, { description: e.target.value })} /></Field>
              </div>
              <div className="flex items-end sm:col-span-1">
                <Button type="button" variant="ghost" size="icon" aria-label={tx('Remove line')} onClick={() => setLines((ls) => ls.filter((x) => x.key !== l.key))}><Trash2 className="h-4 w-4" /></Button>
              </div>
            </div>
          ))}
        </CardContent>
      </Card>
    </form>
  );
}

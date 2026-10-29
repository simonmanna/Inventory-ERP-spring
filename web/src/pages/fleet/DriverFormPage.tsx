import { useEffect, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { ArrowLeft, Save } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Textarea } from '@/components/ui/textarea';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Skeleton } from '@/components/ui/skeleton';
import { SearchableSelect } from '@/components/ui/searchable-select';
import { notify, apiMessage } from '@/lib/notify';
import { useDebouncedValue } from '@/lib/use-debounced-value';
import { tx } from '@/lib/i18n/tx';
import { useFleetLookups } from '@/features/fleet/api';
import {
  DRIVER_STATUS_LABEL, DRIVER_TYPE_LABEL, useCreateDriver, useDriver, useEmployeePicker, useUpdateDriver,
  type Driver,
} from '@/features/fleet/drivers-api';
import { Field, OptionalSelect } from './fleet-ui';
import { toOptions } from './fleet-format';

interface FormState {
  employeeId: string;
  code: string;
  driverType: string;
  licenceNumber: string;
  licenceClasses: string;
  licenceIssuer: string;
  licenceIssuedOn: string;
  licenceExpiresOn: string;
  medicalExpiresOn: string;
  permitNumber: string;
  permitExpiresOn: string;
  reminderLeadDays: string;
  status: string;
  statusReason: string;
  notes: string;
  qualified: string[];
}

const EMPTY: FormState = {
  employeeId: '', code: '', driverType: 'driver', licenceNumber: '', licenceClasses: '', licenceIssuer: '',
  licenceIssuedOn: '', licenceExpiresOn: '', medicalExpiresOn: '', permitNumber: '', permitExpiresOn: '',
  reminderLeadDays: '', status: 'active', statusReason: '', notes: '', qualified: [],
};

/** Server-side clearable fields (DriverCommands.DRIVER_CLEARABLE). */
const CLEARABLE = ['licenceNumber', 'licenceIssuer', 'licenceIssuedOn', 'licenceExpiresOn', 'medicalExpiresOn', 'permitNumber',
  'permitExpiresOn', 'statusReason', 'notes'] as const;

function fromDriver(d: Driver): FormState {
  const s = (x: string | number | null | undefined) => (x === null || x === undefined ? '' : String(x));
  return {
    employeeId: d.employeeId, code: d.code, driverType: d.driverType, licenceNumber: s(d.licenceNumber),
    licenceClasses: d.licenceClasses.join(', '), licenceIssuer: s(d.licenceIssuer), licenceIssuedOn: s(d.licenceIssuedOn),
    licenceExpiresOn: s(d.licenceExpiresOn), medicalExpiresOn: s(d.medicalExpiresOn), permitNumber: s(d.permitNumber),
    permitExpiresOn: s(d.permitExpiresOn), reminderLeadDays: s(d.reminderLeadDays), status: d.status,
    statusReason: s(d.statusReason), notes: s(d.notes), qualified: d.qualifications.map((q) => q.id),
  };
}

/** Add or edit a driver / operator profile (fleet 1.2). Single column on phones. */
export function DriverFormPage() {
  const { id } = useParams<{ id: string }>();
  const editing = !!id;
  const navigate = useNavigate();
  const existing = useDriver(id);
  const lookups = useFleetLookups();
  const create = useCreateDriver();
  const update = useUpdateDriver();
  const [form, setForm] = useState<FormState>(EMPTY);
  const [errors, setErrors] = useState<Partial<Record<keyof FormState, string>>>({});
  const [employeeSearch, setEmployeeSearch] = useState('');
  const employees = useEmployeePicker(useDebouncedValue(employeeSearch, 300));

  useEffect(() => {
    if (existing.data) setForm(fromDriver(existing.data));
  }, [existing.data]);

  const set = <K extends keyof FormState>(key: K, value: FormState[K]) => setForm((f) => ({ ...f, [key]: value }));
  const categories = lookups.data?.categories ?? [];

  const validate = (): boolean => {
    const e: Partial<Record<keyof FormState, string>> = {};
    if (!editing && !form.employeeId) e.employeeId = tx('Choose the employee');
    if (form.licenceIssuedOn && form.licenceExpiresOn && form.licenceExpiresOn < form.licenceIssuedOn) {
      e.licenceExpiresOn = tx('The licence expiry date must be on or after the issue date');
    }
    const lead = form.reminderLeadDays.trim();
    if (lead && (!Number.isInteger(Number(lead)) || Number(lead) < 0 || Number(lead) > 365)) {
      e.reminderLeadDays = tx('Enter 0 to 365 days');
    }
    if (form.status === 'suspended' && !form.statusReason.trim()) e.statusReason = tx('A reason is required to suspend a driver');
    setErrors(e);
    return Object.keys(e).length === 0;
  };

  const submit = async () => {
    if (!validate()) return;
    const original = existing.data ? fromDriver(existing.data) : EMPTY;
    const body: Record<string, unknown> = {};
    const clear: string[] = [];
    const text: Array<keyof FormState> = ['code', 'driverType', 'licenceNumber', 'licenceIssuer', 'licenceIssuedOn',
      'licenceExpiresOn', 'medicalExpiresOn', 'permitNumber', 'permitExpiresOn', 'notes'];
    if (editing) text.push('status', 'statusReason');
    for (const key of text) {
      const value = String(form[key]).trim();
      if (value === '') {
        if (editing && original[key] !== '' && (CLEARABLE as readonly string[]).includes(key)) clear.push(key);
        continue;
      }
      if (editing && value === original[key]) continue;
      body[key] = value;
    }
    if (form.reminderLeadDays.trim() && form.reminderLeadDays !== original.reminderLeadDays) body.reminderLeadDays = Number(form.reminderLeadDays);
    if (!editing || form.licenceClasses !== original.licenceClasses) {
      body.licenceClasses = form.licenceClasses.split(/[,\s]+/).map((c) => c.trim()).filter(Boolean);
    }
    if (!editing || form.qualified.join() !== original.qualified.join()) body.qualifiedCategoryIds = form.qualified;
    if (clear.length) body.clear = clear;
    try {
      if (editing) {
        await update.mutateAsync({ id: id!, body });
        notify.success(tx('Driver updated'));
        navigate(`/fleet/drivers/${id}`);
      } else {
        body.employeeId = form.employeeId;
        const created = await create.mutateAsync(body);
        notify.success(tx('Driver added'));
        navigate(`/fleet/drivers/${created.id}`);
      }
    } catch (err) {
      notify.error(apiMessage(err, tx('Could not save the driver')));
    }
  };

  if (editing && existing.isLoading) return <Skeleton className="h-96 w-full" />;
  if (editing && existing.isError) return <p className="p-8 text-center text-muted-foreground">{tx('Driver not found')}</p>;

  const employeeOptions = (employees.data ?? []).map((e) => ({
    value: e.id,
    label: [e.firstName, e.lastName].filter(Boolean).join(' ') || e.employeeCode || e.id,
    code: e.employeeCode ?? undefined,
    hint: e.driverCode ? tx('Already driver {{code}}', { code: e.driverCode }) : e.isActive ? undefined : tx('Inactive'),
    disabled: !!e.driverCode,
  }));

  return (
    <form onSubmit={(e) => { e.preventDefault(); void submit(); }} className="mx-auto max-w-4xl space-y-4 pb-10">
      <div className="flex flex-wrap items-center gap-3">
        <Button type="button" variant="ghost" size="icon" aria-label={tx('Back')} onClick={() => navigate(editing ? `/fleet/drivers/${id}` : '/fleet/drivers')}>
          <ArrowLeft className="h-5 w-5" />
        </Button>
        <h1 className="text-2xl font-bold">{editing ? tx('Edit driver {{code}}', { code: existing.data?.code ?? '' }) : tx('Add driver')}</h1>
        <div className="ml-auto">
          <Button type="submit" disabled={create.isPending || update.isPending}><Save className="mr-1.5 h-4 w-4" /> {tx('Save')}</Button>
        </div>
      </div>

      <Card>
        <CardHeader><CardTitle className="text-base">{tx('Person')}</CardTitle></CardHeader>
        <CardContent className="grid grid-cols-1 gap-4 sm:grid-cols-2">
          <Field label={tx('Employee *')} error={errors.employeeId}>
            {editing ? (
              <Input value={existing.data?.name ?? ''} disabled />
            ) : (
              <div className="space-y-1.5">
                <Input placeholder={tx('Search employees…')} value={employeeSearch} onChange={(e) => setEmployeeSearch(e.target.value)} />
                <SearchableSelect value={form.employeeId} onValueChange={(v) => set('employeeId', v)} options={employeeOptions}
                  placeholder={tx('Choose the employee')} searchPlaceholder={tx('Search…')} />
              </div>
            )}
          </Field>
          <Field label={tx('Code')}>
            <Input value={form.code} onChange={(e) => set('code', e.target.value)} placeholder={editing ? '' : tx('Leave blank to number automatically')} />
          </Field>
          <Field label={tx('Type')}>
            <OptionalSelect value={form.driverType} onChange={(v) => set('driverType', v || 'driver')} placeholder={tx('Type')} options={toOptions(DRIVER_TYPE_LABEL)} />
          </Field>
          {editing && (
            <Field label={tx('Status')}>
              <OptionalSelect value={form.status} onChange={(v) => set('status', v || 'active')} placeholder={tx('Status')} options={toOptions(DRIVER_STATUS_LABEL)} />
            </Field>
          )}
          {editing && form.status !== 'active' && (
            <Field label={form.status === 'suspended' ? tx('Reason *') : tx('Reason')} error={errors.statusReason} className="sm:col-span-2">
              <Input value={form.statusReason} onChange={(e) => set('statusReason', e.target.value)} />
            </Field>
          )}
        </CardContent>
      </Card>

      <Card>
        <CardHeader><CardTitle className="text-base">{tx('Driving licence')}</CardTitle></CardHeader>
        <CardContent className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3">
          <Field label={tx('Licence number')}><Input value={form.licenceNumber} onChange={(e) => set('licenceNumber', e.target.value)} className="uppercase" /></Field>
          <Field label={tx('Licence classes')}><Input value={form.licenceClasses} onChange={(e) => set('licenceClasses', e.target.value)} placeholder="B, C, CE" className="uppercase" /></Field>
          <Field label={tx('Issued by')}><Input value={form.licenceIssuer} onChange={(e) => set('licenceIssuer', e.target.value)} /></Field>
          <Field label={tx('Issued on')}><Input type="date" value={form.licenceIssuedOn} onChange={(e) => set('licenceIssuedOn', e.target.value)} /></Field>
          <Field label={tx('Expires on')} error={errors.licenceExpiresOn}><Input type="date" value={form.licenceExpiresOn} onChange={(e) => set('licenceExpiresOn', e.target.value)} /></Field>
          <Field label={tx('Reminder lead (days)')} error={errors.reminderLeadDays}>
            <Input inputMode="numeric" value={form.reminderLeadDays} onChange={(e) => set('reminderLeadDays', e.target.value)} placeholder="30" />
          </Field>
        </CardContent>
      </Card>

      <Card>
        <CardHeader><CardTitle className="text-base">{tx('Medical certificate and operator permit')}</CardTitle></CardHeader>
        <CardContent className="grid grid-cols-1 gap-4 sm:grid-cols-3">
          <Field label={tx('Medical certificate expires on')}><Input type="date" value={form.medicalExpiresOn} onChange={(e) => set('medicalExpiresOn', e.target.value)} /></Field>
          <Field label={tx('Operator permit number')}><Input value={form.permitNumber} onChange={(e) => set('permitNumber', e.target.value)} /></Field>
          <Field label={tx('Permit expires on')}><Input type="date" value={form.permitExpiresOn} onChange={(e) => set('permitExpiresOn', e.target.value)} /></Field>
        </CardContent>
      </Card>

      <Card>
        <CardHeader><CardTitle className="text-base">{tx('Qualified vehicle classes')}</CardTitle></CardHeader>
        <CardContent>
          <p className="mb-3 text-sm text-muted-foreground">{tx('The driver can only be assigned to and check out vehicles of these classes.')}</p>
          <div className="grid grid-cols-1 gap-2 sm:grid-cols-2 lg:grid-cols-3">
            {categories.map((c) => (
              <label key={c.id} className="flex min-h-10 items-center gap-2 rounded-md border px-3 py-2 text-sm">
                <input
                  type="checkbox"
                  className="h-4 w-4"
                  checked={form.qualified.includes(c.id)}
                  onChange={(e) => set('qualified', e.target.checked ? [...form.qualified, c.id] : form.qualified.filter((x) => x !== c.id))}
                />
                {c.name}
              </label>
            ))}
          </div>
        </CardContent>
      </Card>

      <Card>
        <CardHeader><CardTitle className="text-base">{tx('Notes')}</CardTitle></CardHeader>
        <CardContent><Textarea rows={3} value={form.notes} onChange={(e) => set('notes', e.target.value)} /></CardContent>
      </Card>
    </form>
  );
}

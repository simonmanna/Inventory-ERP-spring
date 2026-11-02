import { useState } from 'react';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { useHrEmployees, useHrLeaveTypes } from '@/features/hr/api';
import { day, fullName, useHrGet, useHrSend, type Page, type Row } from '@/features/hr/hr-pro-api';
import { tx } from '@/lib/i18n/tx';
import { DataTable, Field, PageHeader, Section, done, selectClass } from './hr-ui';

/** Leave policies (HP-4): accrual, tenure tiers, carry-forward and expiry, encashment, ledger. */
export function HrLeavePoliciesPage() {
  const { data, isLoading } = useHrGet<Page>('/hr/leave/policies');
  const { data: types } = useHrLeaveTypes();
  const post = useHrSend('post');
  const del = useHrSend('delete');
  const empty = { leaveTypeId: '', accrualMethod: 'MONTHLY', daysPerYear: '21', carryForwardMax: '5', carryForwardExpiryMonths: '3', encashable: false, maxEncashDays: '', minServiceMonths: '0', tierYears: '', tierDays: '' };
  const [f, setF] = useState(empty);
  const [asOf, setAsOf] = useState(new Date().toISOString().slice(0, 10));
  const [year, setYear] = useState(String(new Date().getFullYear() - 1));
  const typeRows: Row[] = (types as Row)?.rows ?? (Array.isArray(types) ? types : []);
  const submit = () => post.mutate({ path: '/hr/leave/policies', body: {
    leaveTypeId: f.leaveTypeId, accrualMethod: f.accrualMethod, daysPerYear: Number(f.daysPerYear),
    carryForwardMax: Number(f.carryForwardMax || 0), carryForwardExpiryMonths: Number(f.carryForwardExpiryMonths || 0),
    encashable: f.encashable, maxEncashDays: f.maxEncashDays ? Number(f.maxEncashDays) : undefined,
    minServiceMonths: Number(f.minServiceMonths || 0),
    tenureTiers: f.tierYears && f.tierDays ? [{ years: Number(f.tierYears), daysPerYear: Number(f.tierDays) }] : [],
  } }, { ...done(tx('Leave policy saved')), onSettled: () => setF(empty) });
  return (
    <div className="space-y-4 p-6">
      <PageHeader title={tx('Leave policies')} subtitle={tx('How leave is earned, carried into the next year, expires and is paid out.')} />
      <Section title={tx('New policy')}>
        <div className="grid gap-2 md:grid-cols-5">
          <Field label={tx('Leave type')}>
            <select className={selectClass} value={f.leaveTypeId} onChange={(e) => setF({ ...f, leaveTypeId: e.target.value })}>
              <option value="">—</option>
              {typeRows.map((t) => <option key={t.id} value={t.id}>{t.code} · {t.name}</option>)}
            </select>
          </Field>
          <Field label={tx('Accrual')}>
            <select className={selectClass} value={f.accrualMethod} onChange={(e) => setF({ ...f, accrualMethod: e.target.value })}>
              <option value="MONTHLY">{tx('Monthly')}</option>
              <option value="ANNUAL_UPFRONT">{tx('Up front on 1 January')}</option>
            </select>
          </Field>
          <Field label={tx('Days per year')}><Input type="number" value={f.daysPerYear} onChange={(e) => setF({ ...f, daysPerYear: e.target.value })} /></Field>
          <Field label={tx('Carry forward max days')}><Input type="number" value={f.carryForwardMax} onChange={(e) => setF({ ...f, carryForwardMax: e.target.value })} /></Field>
          <Field label={tx('Carried days expire after (months)')}><Input type="number" value={f.carryForwardExpiryMonths} onChange={(e) => setF({ ...f, carryForwardExpiryMonths: e.target.value })} /></Field>
          <Field label={tx('Eligible after (months)')}><Input type="number" value={f.minServiceMonths} onChange={(e) => setF({ ...f, minServiceMonths: e.target.value })} /></Field>
          <Field label={tx('Tier: after years')}><Input type="number" value={f.tierYears} onChange={(e) => setF({ ...f, tierYears: e.target.value })} /></Field>
          <Field label={tx('Tier: days per year')}><Input type="number" value={f.tierDays} onChange={(e) => setF({ ...f, tierDays: e.target.value })} /></Field>
          <label className="flex items-end gap-1 pb-2 text-sm"><input type="checkbox" checked={f.encashable} onChange={(e) => setF({ ...f, encashable: e.target.checked })} />{tx('Encashable')}</label>
          {f.encashable && <Field label={tx('Max encash days')}><Input type="number" value={f.maxEncashDays} onChange={(e) => setF({ ...f, maxEncashDays: e.target.value })} /></Field>}
          <div className="flex items-end"><Button disabled={!f.leaveTypeId || post.isPending} onClick={submit}>{tx('Save')}</Button></div>
        </div>
      </Section>
      <Section title={tx('Policies')} actions={<>
        <Input type="date" className="h-8 w-40" aria-label={tx('Accrue up to')} value={asOf} onChange={(e) => setAsOf(e.target.value)} />
        <Button size="sm" variant="outline" onClick={() => post.mutate({ path: '/hr/leave/accruals/run', body: { asOf } }, done(tx('Accrual posted')))}>{tx('Run accrual')}</Button>
        <Input type="number" className="h-8 w-24" aria-label={tx('Year')} value={year} onChange={(e) => setYear(e.target.value)} />
        <Button size="sm" variant="outline" onClick={() => window.confirm(tx('Close the leave year {{y}}? Unused days are carried up to the cap; the rest lapse.', { y: year })) && post.mutate({ path: '/hr/leave/year-end', body: { year: Number(year) } }, done(tx('Year closed')))}>{tx('Year end')}</Button>
      </>}>
        <DataTable loading={isLoading} rows={data?.rows} empty={tx('No policies: leave types keep their fixed yearly days.')} cols={[
          { key: 'leaveTypeCode', label: tx('Leave type'), render: (r) => `${r.leaveTypeCode} · ${r.leaveTypeName}` },
          { key: 'accrualMethod', label: tx('Accrual') },
          { key: 'daysPerYear', label: tx('Days/year'), align: 'right' },
          { key: 'tenureTiers', label: tx('Tiers'), render: (r) => (r.tenureTiers ?? []).map((t: Row) => `${t.years}y→${t.daysPerYear}`).join(', ') || '—' },
          { key: 'carryForwardMax', label: tx('Carry max'), align: 'right' },
          { key: 'encashable', label: tx('Encash'), render: (r) => (r.encashable ? <Badge variant="secondary">{tx('yes')}</Badge> : '—') },
          { key: 'x', label: '', render: (r) => <Button size="sm" variant="ghost" onClick={() => del.mutate({ path: `/hr/leave/policies/${r.id}` }, done(tx('Removed')))}>{tx('Remove')}</Button> },
        ]} />
      </Section>
      <LedgerAndEncash types={typeRows} />
    </div>
  );
}

function LedgerAndEncash({ types }: { types: Row[] }) {
  const { data: emps } = useHrEmployees({ isActive: 'true', pageSize: 200 });
  const [empId, setEmpId] = useState('');
  const [typeId, setTypeId] = useState('');
  const [days, setDays] = useState('');
  const { data, isLoading } = useHrGet<Page>(empId ? '/hr/leave/ledger' : null, { employeeId: empId, leaveTypeId: typeId || undefined });
  const post = useHrSend('post');
  return (
    <Section title={tx('Leave ledger and encashment')}>
      <div className="grid gap-2 md:grid-cols-4">
        <Field label={tx('Employee')}>
          <select className={selectClass} value={empId} onChange={(e) => setEmpId(e.target.value)}>
            <option value="">—</option>
            {(emps?.rows ?? []).map((e: Row) => <option key={e.id} value={e.id}>{e.employeeCode} · {fullName(e)}</option>)}
          </select>
        </Field>
        <Field label={tx('Leave type')}>
          <select className={selectClass} value={typeId} onChange={(e) => setTypeId(e.target.value)}>
            <option value="">{tx('All')}</option>
            {types.map((t) => <option key={t.id} value={t.id}>{t.code}</option>)}
          </select>
        </Field>
        <Field label={tx('Days to encash')}><Input type="number" value={days} onChange={(e) => setDays(e.target.value)} /></Field>
        <div className="flex items-end">
          <Button disabled={!empId || !typeId || !days} onClick={() => post.mutate({ path: '/hr/leave/encashments', body: { employeeId: empId, leaveTypeId: typeId, days: Number(days) } }, done(tx('Encashment queued for payroll')))}>{tx('Encash')}</Button>
        </div>
      </div>
      <DataTable loading={isLoading} rows={data?.rows} empty={tx('Pick an employee to see the ledger.')} cols={[
        { key: 'postedOn', label: tx('Date'), render: (r) => day(r.postedOn) },
        { key: 'leaveTypeCode', label: tx('Type') },
        { key: 'year', label: tx('Year') },
        { key: 'entryType', label: tx('Entry') },
        { key: 'days', label: tx('Days'), align: 'right' },
        { key: 'note', label: tx('Note') },
      ]} />
    </Section>
  );
}

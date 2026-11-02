import { useState } from 'react';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs';
import { useHrEmployees } from '@/features/hr/api';
import { day, fullName, money, useHrGet, useHrSend, type Page, type Row } from '@/features/hr/hr-pro-api';
import { tx } from '@/lib/i18n/tx';
import { DataTable, Field, PageHeader, Section, done, selectClass } from './hr-ui';

/** Compensation (HP-1): grades, pay elements, salary structures, salary revisions and per-employee elements. */
export function HrCompensationPage() {
  return (
    <div className="space-y-4 p-6">
      <PageHeader title={tx('Compensation')} subtitle={tx('Salary grades, pay elements, structures and approved salary revisions.')} />
      <Tabs defaultValue="revisions">
        <TabsList>
          <TabsTrigger value="revisions">{tx('Salary revisions')}</TabsTrigger>
          <TabsTrigger value="employee">{tx('Employee pay')}</TabsTrigger>
          <TabsTrigger value="elements">{tx('Pay elements')}</TabsTrigger>
          <TabsTrigger value="structures">{tx('Structures')}</TabsTrigger>
          <TabsTrigger value="grades">{tx('Grades')}</TabsTrigger>
        </TabsList>
        <TabsContent value="revisions"><Revisions /></TabsContent>
        <TabsContent value="employee"><EmployeePay /></TabsContent>
        <TabsContent value="elements"><Elements /></TabsContent>
        <TabsContent value="structures"><Structures /></TabsContent>
        <TabsContent value="grades"><Grades /></TabsContent>
      </Tabs>
    </div>
  );
}

function Revisions() {
  const [status, setStatus] = useState('PENDING');
  const { data, isLoading } = useHrGet<Page>('/hr/compensation/revisions', { status });
  const send = useHrSend('post');
  const reject = (id: string) => {
    const reason = window.prompt(tx('Reason for rejecting'));
    if (reason) send.mutate({ path: `/hr/compensation/revisions/${id}/reject`, body: { reason } }, done(tx('Revision rejected')));
  };
  return (
    <Section title={tx('Salary revisions')} actions={
      <select className={selectClass} value={status} onChange={(e) => setStatus(e.target.value)} aria-label={tx('Status')}>
        {['PENDING', 'ACTIVE', 'SUPERSEDED', 'REJECTED'].map((s) => <option key={s} value={s}>{s}</option>)}
      </select>}>
      <p className="text-xs text-muted-foreground">{tx('The person who proposed a revision cannot approve it.')}</p>
      <DataTable loading={isLoading} rows={data?.rows} empty={tx('No revisions.')} cols={[
        { key: 'employee', label: tx('Employee'), render: (r) => `${r.employeeCode} · ${fullName(r)}` },
        { key: 'effectiveFrom', label: tx('Effective'), render: (r) => day(r.effectiveFrom) },
        { key: 'previousBasic', label: tx('From'), align: 'right', render: (r) => money(r.previousBasic) },
        { key: 'basicSalary', label: tx('To'), align: 'right', render: (r) => money(r.basicSalary) },
        { key: 'reason', label: tx('Reason') },
        { key: 'actions', label: '', render: (r) => r.status === 'PENDING' ? (
          <div className="flex gap-1">
            <Button size="sm" onClick={() => send.mutate({ path: `/hr/compensation/revisions/${r.id}/approve` }, done(tx('Revision approved')))}>{tx('Approve')}</Button>
            <Button size="sm" variant="outline" onClick={() => reject(r.id)}>{tx('Reject')}</Button>
          </div>) : <Badge variant="outline">{r.status}</Badge> },
      ]} />
    </Section>
  );
}

function EmployeePay() {
  const [search, setSearch] = useState('');
  const [empId, setEmpId] = useState('');
  const { data: emps } = useHrEmployees({ search: search || undefined, pageSize: 50 });
  const { data: hist, isLoading } = useHrGet<Page & { current: Row | null }>(empId ? `/hr/compensation/employees/${empId}` : null);
  const { data: els } = useHrGet<Page>(empId ? `/hr/compensation/employees/${empId}/elements` : null);
  const { data: catalogue } = useHrGet<Page>('/hr/compensation/elements');
  const { data: structures } = useHrGet<Page>('/hr/compensation/structures');
  const { data: grades } = useHrGet<Page>('/hr/compensation/grades');
  const post = useHrSend('post');
  const del = useHrSend('delete');
  const [rev, setRev] = useState({ effectiveFrom: '', basicSalary: '', gradeId: '', structureId: '', reason: '' });
  const [el, setEl] = useState({ elementId: '', amount: '', rate: '', effectiveFrom: '' });
  return (
    <div className="grid gap-4 lg:grid-cols-3">
      <Section title={tx('Employee')}>
        <Input placeholder={tx('Search')} aria-label={tx('Search')} value={search} onChange={(e) => setSearch(e.target.value)} />
        <ul className="max-h-96 divide-y overflow-y-auto text-sm">
          {(emps?.rows ?? []).map((e: Row) => (
            <li key={e.id}>
              <button type="button" className={`w-full py-1.5 text-left ${empId === e.id ? 'font-semibold' : ''}`} onClick={() => setEmpId(e.id)}>
                <span className="mr-2 font-mono text-xs text-muted-foreground">{e.employeeCode}</span>{fullName(e)}
              </button>
            </li>
          ))}
        </ul>
      </Section>
      <div className="space-y-4 lg:col-span-2">
        {!empId ? <p className="text-sm text-muted-foreground">{tx('Pick an employee.')}</p> : (
          <>
            <Section title={tx('Compensation history')}>
              <p className="text-sm">{tx('Current basic')}: <span className="font-semibold">{money(hist?.current?.basicSalary)}</span></p>
              <DataTable loading={isLoading} rows={hist?.rows} empty={tx('No compensation recorded.')} cols={[
                { key: 'effectiveFrom', label: tx('From'), render: (r) => day(r.effectiveFrom) },
                { key: 'effectiveTo', label: tx('To'), render: (r) => day(r.effectiveTo) },
                { key: 'basicSalary', label: tx('Basic'), align: 'right', render: (r) => money(r.basicSalary) },
                { key: 'status', label: tx('Status'), render: (r) => <Badge variant="outline">{r.status}</Badge> },
                { key: 'reason', label: tx('Reason') },
              ]} />
              <div className="grid gap-2 md:grid-cols-3">
                <Field label={tx('Effective from')}><Input type="date" value={rev.effectiveFrom} onChange={(e) => setRev({ ...rev, effectiveFrom: e.target.value })} /></Field>
                <Field label={tx('Basic salary')}><Input type="number" value={rev.basicSalary} onChange={(e) => setRev({ ...rev, basicSalary: e.target.value })} /></Field>
                <Field label={tx('Grade')}>
                  <select className={selectClass} value={rev.gradeId} onChange={(e) => setRev({ ...rev, gradeId: e.target.value })}>
                    <option value="">—</option>
                    {(grades?.rows ?? []).map((g) => <option key={g.id} value={g.id}>{g.code} · {g.name}</option>)}
                  </select>
                </Field>
                <Field label={tx('Structure')}>
                  <select className={selectClass} value={rev.structureId} onChange={(e) => setRev({ ...rev, structureId: e.target.value })}>
                    <option value="">—</option>
                    {(structures?.rows ?? []).map((s) => <option key={s.id} value={s.id}>{s.code} · {s.name}</option>)}
                  </select>
                </Field>
                <Field label={tx('Reason')}><Input value={rev.reason} onChange={(e) => setRev({ ...rev, reason: e.target.value })} /></Field>
                <div className="flex items-end">
                  <Button disabled={!rev.effectiveFrom || !rev.basicSalary || post.isPending} onClick={() => post.mutate({
                    path: `/hr/compensation/employees/${empId}/revisions`,
                    body: { effectiveFrom: rev.effectiveFrom, basicSalary: Number(rev.basicSalary), gradeId: rev.gradeId || undefined, structureId: rev.structureId || undefined, reason: rev.reason || undefined },
                  }, done(tx('Revision proposed for approval')))}>{tx('Propose revision')}</Button>
                </div>
              </div>
            </Section>
            <Section title={tx('Employee pay elements')}>
              <DataTable rows={els?.rows} empty={tx('No employee-specific elements.')} cols={[
                { key: 'code', label: tx('Element'), render: (r) => `${r.code} · ${r.name}` },
                { key: 'amount', label: tx('Amount'), align: 'right', render: (r) => money(r.amount) },
                { key: 'rate', label: tx('Rate %'), align: 'right', render: (r) => r.rate ?? '—' },
                { key: 'effectiveFrom', label: tx('From'), render: (r) => day(r.effectiveFrom) },
                { key: 'effectiveTo', label: tx('To'), render: (r) => day(r.effectiveTo) },
                { key: 'x', label: '', render: (r) => <Button size="sm" variant="ghost" onClick={() => del.mutate({ path: `/hr/compensation/employees/${empId}/elements/${r.id}` }, done(tx('Removed')))}>{tx('Remove')}</Button> },
              ]} />
              <div className="grid gap-2 md:grid-cols-5">
                <Field label={tx('Element')}>
                  <select className={selectClass} value={el.elementId} onChange={(e) => setEl({ ...el, elementId: e.target.value })}>
                    <option value="">—</option>
                    {(catalogue?.rows ?? []).map((c) => <option key={c.id} value={c.id}>{c.code} · {c.name}</option>)}
                  </select>
                </Field>
                <Field label={tx('Amount')}><Input type="number" value={el.amount} onChange={(e) => setEl({ ...el, amount: e.target.value })} /></Field>
                <Field label={tx('Rate %')}><Input type="number" value={el.rate} onChange={(e) => setEl({ ...el, rate: e.target.value })} /></Field>
                <Field label={tx('Effective from')}><Input type="date" value={el.effectiveFrom} onChange={(e) => setEl({ ...el, effectiveFrom: e.target.value })} /></Field>
                <div className="flex items-end">
                  <Button disabled={!el.elementId || !el.effectiveFrom} onClick={() => post.mutate({
                    path: `/hr/compensation/employees/${empId}/elements`,
                    body: { elementId: el.elementId, effectiveFrom: el.effectiveFrom, amount: el.amount ? Number(el.amount) : undefined, rate: el.rate ? Number(el.rate) : undefined },
                  }, done(tx('Element added')))}>{tx('Add')}</Button>
                </div>
              </div>
            </Section>
          </>
        )}
      </div>
    </div>
  );
}

const KINDS = ['EARNING', 'DEDUCTION', 'EMPLOYER_CONTRIBUTION', 'INFO'];
const METHODS = ['FIXED', 'PERCENT_OF_BASIC', 'PERCENT_OF_GROSS', 'FORMULA'];

function Elements() {
  const { data, isLoading } = useHrGet<Page>('/hr/compensation/elements');
  const post = useHrSend('post');
  const del = useHrSend('delete');
  const [f, setF] = useState({ code: '', name: '', kind: 'EARNING', calcMethod: 'FIXED', amount: '', rate: '', formula: '', isTaxable: true, isPensionable: false, isProrated: true });
  return (
    <Section title={tx('Pay elements')}>
      <p className="text-xs text-muted-foreground">{tx('Formulas may use BASIC, GROSS, DAYS_PAID, DAYS_IN_PERIOD, WORKING_DAYS, HOURS, OT_HOURS, HOURLY_RATE, DAILY_RATE, YEARS_OF_SERVICE, PRORATION with + - * / MIN MAX ROUND IF.')}</p>
      <div className="grid gap-2 md:grid-cols-4">
        <Field label={tx('Code')}><Input value={f.code} onChange={(e) => setF({ ...f, code: e.target.value })} /></Field>
        <Field label={tx('Name')}><Input value={f.name} onChange={(e) => setF({ ...f, name: e.target.value })} /></Field>
        <Field label={tx('Kind')}>
          <select className={selectClass} value={f.kind} onChange={(e) => setF({ ...f, kind: e.target.value })}>{KINDS.map((k) => <option key={k}>{k}</option>)}</select>
        </Field>
        <Field label={tx('Method')}>
          <select className={selectClass} value={f.calcMethod} onChange={(e) => setF({ ...f, calcMethod: e.target.value })}>{METHODS.map((k) => <option key={k}>{k}</option>)}</select>
        </Field>
        {f.calcMethod === 'FIXED' && <Field label={tx('Amount')}><Input type="number" value={f.amount} onChange={(e) => setF({ ...f, amount: e.target.value })} /></Field>}
        {f.calcMethod.startsWith('PERCENT') && <Field label={tx('Rate %')}><Input type="number" value={f.rate} onChange={(e) => setF({ ...f, rate: e.target.value })} /></Field>}
        {f.calcMethod === 'FORMULA' && <Field label={tx('Formula')}><Input value={f.formula} onChange={(e) => setF({ ...f, formula: e.target.value })} placeholder="BASIC * 0.25" /></Field>}
        <div className="flex items-end gap-3 text-sm">
          <label className="flex items-center gap-1"><input type="checkbox" checked={f.isTaxable} onChange={(e) => setF({ ...f, isTaxable: e.target.checked })} />{tx('Taxable')}</label>
          <label className="flex items-center gap-1"><input type="checkbox" checked={f.isPensionable} onChange={(e) => setF({ ...f, isPensionable: e.target.checked })} />{tx('Pensionable')}</label>
          <label className="flex items-center gap-1"><input type="checkbox" checked={f.isProrated} onChange={(e) => setF({ ...f, isProrated: e.target.checked })} />{tx('Prorated')}</label>
        </div>
        <div className="flex items-end">
          <Button disabled={!f.code || !f.name} onClick={() => post.mutate({ path: '/hr/compensation/elements', body: {
            code: f.code, name: f.name, kind: f.kind, calcMethod: f.calcMethod, isTaxable: f.isTaxable, isPensionable: f.isPensionable, isProrated: f.isProrated,
            amount: f.amount ? Number(f.amount) : undefined, rate: f.rate ? Number(f.rate) : undefined, formula: f.formula || undefined,
          } }, done(tx('Pay element added')))}>{tx('Add')}</Button>
        </div>
      </div>
      <DataTable loading={isLoading} rows={data?.rows} empty={tx('No pay elements yet.')} cols={[
        { key: 'code', label: tx('Code') },
        { key: 'name', label: tx('Name') },
        { key: 'kind', label: tx('Kind') },
        { key: 'calcMethod', label: tx('Method') },
        { key: 'value', label: tx('Value'), render: (r) => r.formula ?? (r.rate != null ? `${r.rate} %` : money(r.amount)) },
        { key: 'flags', label: '', render: (r) => [r.isTaxable && tx('taxable'), r.isPensionable && tx('pensionable'), !r.isActive && tx('inactive')].filter(Boolean).join(' · ') },
        { key: 'x', label: '', render: (r) => <Button size="sm" variant="ghost" onClick={() => del.mutate({ path: `/hr/compensation/elements/${r.id}` }, done(tx('Removed')))}>{tx('Remove')}</Button> },
      ]} />
    </Section>
  );
}

function Structures() {
  const { data, isLoading } = useHrGet<Page>('/hr/compensation/structures');
  const { data: elements } = useHrGet<Page>('/hr/compensation/elements');
  const { data: grades } = useHrGet<Page>('/hr/compensation/grades');
  const post = useHrSend('post');
  const [f, setF] = useState<{ code: string; name: string; gradeId: string; elementIds: string[] }>({ code: '', name: '', gradeId: '', elementIds: [] });
  const name = (id: string) => (elements?.rows ?? []).find((e) => e.id === id)?.code ?? id;
  return (
    <Section title={tx('Salary structures')}>
      <div className="grid gap-2 md:grid-cols-4">
        <Field label={tx('Code')}><Input value={f.code} onChange={(e) => setF({ ...f, code: e.target.value })} /></Field>
        <Field label={tx('Name')}><Input value={f.name} onChange={(e) => setF({ ...f, name: e.target.value })} /></Field>
        <Field label={tx('Grade')}>
          <select className={selectClass} value={f.gradeId} onChange={(e) => setF({ ...f, gradeId: e.target.value })}>
            <option value="">—</option>
            {(grades?.rows ?? []).map((g) => <option key={g.id} value={g.id}>{g.code}</option>)}
          </select>
        </Field>
        <div className="flex items-end">
          <Button disabled={!f.code || !f.name} onClick={() => post.mutate({ path: '/hr/compensation/structures', body: {
            code: f.code, name: f.name, gradeId: f.gradeId || undefined, lines: f.elementIds.map((elementId) => ({ elementId })),
          } }, done(tx('Structure added')))}>{tx('Add')}</Button>
        </div>
      </div>
      <div className="flex flex-wrap gap-3 text-sm">
        {(elements?.rows ?? []).map((e) => (
          <label key={e.id} className="flex items-center gap-1">
            <input type="checkbox" checked={f.elementIds.includes(e.id)} onChange={(ev) => setF({ ...f, elementIds: ev.target.checked ? [...f.elementIds, e.id] : f.elementIds.filter((x) => x !== e.id) })} />
            {e.code}
          </label>
        ))}
      </div>
      <DataTable loading={isLoading} rows={data?.rows} empty={tx('No structures yet.')} cols={[
        { key: 'code', label: tx('Code') },
        { key: 'name', label: tx('Name') },
        { key: 'lines', label: tx('Elements'), render: (r) => (r.lines ?? []).map((l: Row) => name(l.elementId)).join(', ') || '—' },
      ]} />
    </Section>
  );
}

function Grades() {
  const { data, isLoading } = useHrGet<Page>('/hr/compensation/grades');
  const post = useHrSend('post');
  const [f, setF] = useState({ code: '', name: '', minAmount: '', midAmount: '', maxAmount: '' });
  return (
    <Section title={tx('Salary grades')}>
      <div className="grid gap-2 md:grid-cols-6">
        <Field label={tx('Code')}><Input value={f.code} onChange={(e) => setF({ ...f, code: e.target.value })} /></Field>
        <Field label={tx('Name')}><Input value={f.name} onChange={(e) => setF({ ...f, name: e.target.value })} /></Field>
        <Field label={tx('Minimum')}><Input type="number" value={f.minAmount} onChange={(e) => setF({ ...f, minAmount: e.target.value })} /></Field>
        <Field label={tx('Midpoint')}><Input type="number" value={f.midAmount} onChange={(e) => setF({ ...f, midAmount: e.target.value })} /></Field>
        <Field label={tx('Maximum')}><Input type="number" value={f.maxAmount} onChange={(e) => setF({ ...f, maxAmount: e.target.value })} /></Field>
        <div className="flex items-end">
          <Button disabled={!f.code || !f.name} onClick={() => post.mutate({ path: '/hr/compensation/grades', body: {
            code: f.code, name: f.name, minAmount: f.minAmount ? Number(f.minAmount) : undefined, midAmount: f.midAmount ? Number(f.midAmount) : undefined, maxAmount: f.maxAmount ? Number(f.maxAmount) : undefined,
          } }, done(tx('Grade added')))}>{tx('Add')}</Button>
        </div>
      </div>
      <DataTable loading={isLoading} rows={data?.rows} empty={tx('No grades yet.')} cols={[
        { key: 'code', label: tx('Code') },
        { key: 'name', label: tx('Name') },
        { key: 'minAmount', label: tx('Minimum'), align: 'right', render: (r) => money(r.minAmount) },
        { key: 'midAmount', label: tx('Midpoint'), align: 'right', render: (r) => money(r.midAmount) },
        { key: 'maxAmount', label: tx('Maximum'), align: 'right', render: (r) => money(r.maxAmount) },
      ]} />
    </Section>
  );
}

import { useState } from 'react';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs';
import { useHrEmployees } from '@/features/hr/api';
import { day, fullName, money, useHrGet, useHrSend, type Page, type Row } from '@/features/hr/hr-pro-api';
import { tx } from '@/lib/i18n/tx';
import { DataTable, Field, PageHeader, Section, done, selectClass } from './hr-ui';

const TYPES = ['RESIGNATION', 'TERMINATION', 'END_OF_CONTRACT', 'RETIREMENT', 'REDUNDANCY', 'DISMISSAL', 'DEATH'];

/** Separation (HP-6): offboarding checklist, final settlement, approval to the GL, payment; gratuity provision. */
export function HrSeparationsPage() {
  const { data, isLoading } = useHrGet<Page>('/hr/separations');
  const [selected, setSelected] = useState('');
  return (
    <div className="space-y-4 p-6">
      <PageHeader title={tx('Separations')} subtitle={tx('Exit checklist, final settlement and end-of-service gratuity.')} />
      <Tabs defaultValue="exits">
        <TabsList>
          <TabsTrigger value="exits">{tx('Exits')}</TabsTrigger>
          <TabsTrigger value="gratuity">{tx('Gratuity provision')}</TabsTrigger>
        </TabsList>
        <TabsContent value="exits" className="space-y-4">
          <NewSeparation onCreated={setSelected} />
          <Section title={tx('Separations')}>
            <DataTable loading={isLoading} rows={data?.rows} selectedId={selected} onRow={(r) => setSelected(r.id)} empty={tx('No separations.')} cols={[
              { key: 'separationNumber', label: tx('Number') },
              { key: 'employee', label: tx('Employee'), render: (r) => `${r.employeeCode} · ${fullName(r)}` },
              { key: 'separationType', label: tx('Type') },
              { key: 'lastWorkingDay', label: tx('Last day'), render: (r) => day(r.lastWorkingDay) },
              { key: 'netAmount', label: tx('Net settlement'), align: 'right', render: (r) => money(r.netAmount) },
              { key: 'status', label: tx('Status'), render: (r) => <Badge variant="outline">{r.status}</Badge> },
            ]} />
          </Section>
          {selected && <Detail id={selected} />}
        </TabsContent>
        <TabsContent value="gratuity"><Gratuity /></TabsContent>
      </Tabs>
    </div>
  );
}

function NewSeparation({ onCreated }: { onCreated: (id: string) => void }) {
  const { data: emps } = useHrEmployees({ isActive: 'true', pageSize: 200 });
  const post = useHrSend<unknown, Row>('post');
  const today = new Date().toISOString().slice(0, 10);
  const [f, setF] = useState({ employeeId: '', separationType: 'RESIGNATION', noticeDate: today, lastWorkingDay: today, noticeDaysRequired: '30', reason: '' });
  return (
    <Section title={tx('New separation')}>
      <div className="grid gap-2 md:grid-cols-6">
        <Field label={tx('Employee')}>
          <select className={selectClass} value={f.employeeId} onChange={(e) => setF({ ...f, employeeId: e.target.value })}>
            <option value="">—</option>
            {(emps?.rows ?? []).map((e: Row) => <option key={e.id} value={e.id}>{e.employeeCode} · {fullName(e)}</option>)}
          </select>
        </Field>
        <Field label={tx('Type')}>
          <select className={selectClass} value={f.separationType} onChange={(e) => setF({ ...f, separationType: e.target.value })}>
            {TYPES.map((t) => <option key={t}>{t}</option>)}
          </select>
        </Field>
        <Field label={tx('Notice date')}><Input type="date" value={f.noticeDate} onChange={(e) => setF({ ...f, noticeDate: e.target.value })} /></Field>
        <Field label={tx('Last working day')}><Input type="date" value={f.lastWorkingDay} onChange={(e) => setF({ ...f, lastWorkingDay: e.target.value })} /></Field>
        <Field label={tx('Notice days required')}><Input type="number" value={f.noticeDaysRequired} onChange={(e) => setF({ ...f, noticeDaysRequired: e.target.value })} /></Field>
        <Field label={tx('Reason')}><Input value={f.reason} onChange={(e) => setF({ ...f, reason: e.target.value })} /></Field>
      </div>
      <Button disabled={!f.employeeId || post.isPending} onClick={() => post.mutate({ path: '/hr/separations', body: { ...f, noticeDaysRequired: Number(f.noticeDaysRequired), reason: f.reason || undefined } },
        { ...done(tx('Separation opened')), onSuccess: (r) => onCreated(r.id as string) })}>{tx('Open separation')}</Button>
    </Section>
  );
}

function Detail({ id }: { id: string }) {
  const { data: s } = useHrGet<Row>(`/hr/separations/${id}`);
  const { data: accounts } = useHrGet<{ data: Row[] }>('/accounts', { pageSize: 200 });
  const post = useHrSend('post');
  const patch = useHrSend('patch');
  const [accountId, setAccountId] = useState('');
  if (!s) return null;
  const open = !['APPROVED', 'PAID', 'CANCELLED'].includes(s.status);
  const lines: Row[] = s.settlement?.lines ?? [];
  return (
    <div className="grid gap-4 lg:grid-cols-2">
      <Section title={tx('Clearance checklist')}>
        <ul className="space-y-1 text-sm">
          {(s.tasks ?? []).map((t: Row) => (
            <li key={t.id} className="flex items-center gap-2">
              <input type="checkbox" checked={t.isDone} disabled={!open} aria-label={t.task}
                onChange={(e) => patch.mutate({ path: `/hr/separations/${id}/tasks/${t.id}`, body: { isDone: e.target.checked } })} />
              <span className={t.isDone ? 'line-through text-muted-foreground' : ''}>{t.task}</span>
              <span className="text-xs text-muted-foreground">{t.department}</span>
            </li>
          ))}
        </ul>
      </Section>
      <Section title={tx('Final settlement')} actions={<>
        {open && <Button size="sm" variant="outline" onClick={() => post.mutate({ path: `/hr/separations/${id}/calculate` }, done(tx('Settlement calculated')))}>{tx('Calculate')}</Button>}
        {s.status === 'CALCULATED' && <Button size="sm" onClick={() => post.mutate({ path: `/hr/separations/${id}/approve` }, done(tx('Settlement approved and posted')))}>{tx('Approve')}</Button>}
        {open && <Button size="sm" variant="ghost" onClick={() => { const reason = window.prompt(tx('Why cancel?')); if (reason) post.mutate({ path: `/hr/separations/${id}/cancel`, body: { reason } }, done(tx('Cancelled'))); }}>{tx('Cancel')}</Button>}
      </>}>
        {s.settlement && <p className="text-xs text-muted-foreground">{tx('Basic')}: {money(s.settlement.basicMonthly)} · {tx('Service years')}: {s.settlement.serviceYears ?? '—'}</p>}
        <DataTable rows={lines} empty={tx('Calculate to see the settlement.')} cols={[
          { key: 'name', label: tx('Item') },
          { key: 'kind', label: tx('Kind') },
          { key: 'quantity', label: tx('Days'), align: 'right', render: (r) => r.quantity ?? '—' },
          { key: 'amount', label: tx('Amount'), align: 'right', render: (r) => money(r.amount) },
        ]} />
        {s.settlement && <p className="text-right text-base font-semibold">{tx('Net settlement')}: {money(s.netAmount)}</p>}
        {s.status === 'APPROVED' && (
          <div className="flex flex-wrap items-end gap-2">
            <Field label={tx('Pay from account')}>
              <select className={selectClass} value={accountId} onChange={(e) => setAccountId(e.target.value)}>
                <option value="">—</option>
                {(accounts?.data ?? []).filter((a) => a.isPostable !== false).map((a) => <option key={a.id} value={a.id}>{a.code} · {a.name}</option>)}
              </select>
            </Field>
            <Button disabled={!accountId} onClick={() => post.mutate({ path: `/hr/separations/${id}/pay`, body: { accountId } }, done(tx('Settlement paid')))}>{tx('Record payment')}</Button>
          </div>
        )}
      </Section>
    </div>
  );
}

function Gratuity() {
  const { data, isLoading } = useHrGet<Page & { liability: number }>('/hr/separations/gratuity');
  const post = useHrSend('post');
  const [asOf, setAsOf] = useState(new Date().toISOString().slice(0, 10));
  return (
    <Section title={tx('Gratuity provision')} actions={<>
      <Input type="date" className="h-8 w-40" aria-label={tx('As of')} value={asOf} onChange={(e) => setAsOf(e.target.value)} />
      <Button size="sm" onClick={() => post.mutate({ path: '/hr/separations/gratuity/accrue', body: { asOf } }, done(tx('Provision posted')))}>{tx('Accrue provision')}</Button>
    </>}>
      <p className="text-sm">{tx('Total liability')}: <span className="font-semibold">{money(data?.liability)}</span></p>
      <p className="text-xs text-muted-foreground">{tx('UAE: 21 days of basic a year for five years, 30 after, capped at two years. Other packs: the contractual days set in payroll settings.')}</p>
      <DataTable loading={isLoading} rows={data?.rows} empty={tx('No provision yet.')} cols={[
        { key: 'employee', label: tx('Employee'), render: (r) => `${r.employeeCode} · ${fullName(r)}` },
        { key: 'asOf', label: tx('As of'), render: (r) => day(r.asOf) },
        { key: 'liability', label: tx('Liability'), align: 'right', render: (r) => money(r.liability) },
        { key: 'movement', label: tx('Last movement'), align: 'right', render: (r) => money(r.movement) },
      ]} />
    </Section>
  );
}

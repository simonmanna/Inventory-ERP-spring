import { useState } from 'react';
import { Download, Mail } from 'lucide-react';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs';
import { useHrEmployees } from '@/features/hr/api';
import { downloadHrFile, fullName, money, useHrGet, useHrSend, type Page, type Row } from '@/features/hr/hr-pro-api';
import { notify } from '@/lib/notify';
import { apiErrorMessage } from '@/lib/api-error';
import { tx } from '@/lib/i18n/tx';
import { DataTable, Field, PageHeader, Section, done, selectClass } from './hr-ui';

/**
 * Payroll operations (HP-2, HP-3, HP-7): one-off inputs, the payroll register and variance of a run, the statutory
 * remittance schedule, bank / mobile-money / WPS files, payslip e-mail and net-pay settlement.
 */
export function HrPayrollOperationsPage() {
  const { data: runs } = useHrGet<Page>('/hr/payroll/runs', { take: 50 });
  const [runId, setRunId] = useState('');
  const run = (runs?.rows ?? []).find((r) => r.id === runId);
  return (
    <div className="space-y-4 p-6">
      <PageHeader title={tx('Payroll operations')} subtitle={tx('Inputs, review, statutory remittances, bank files and payment of a payroll run.')} />
      <Tabs defaultValue="run">
        <TabsList>
          <TabsTrigger value="run">{tx('Run review')}</TabsTrigger>
          <TabsTrigger value="inputs">{tx('One-off inputs')}</TabsTrigger>
          <TabsTrigger value="remit">{tx('Statutory remittances')}</TabsTrigger>
        </TabsList>
        <TabsContent value="run" className="space-y-4">
          <div className="flex flex-wrap items-end gap-3">
            <Field label={tx('Payroll run')}>
              <select className={selectClass} value={runId} onChange={(e) => setRunId(e.target.value)}>
                <option value="">—</option>
                {(runs?.rows ?? []).map((r) => <option key={r.id} value={r.id}>{r.runNumber} · {r.period?.periodCode} · {r.status}</option>)}
              </select>
            </Field>
            {run && <Badge variant="outline">{run.status}</Badge>}
          </div>
          {run && <RunReview run={run} />}
        </TabsContent>
        <TabsContent value="inputs"><Inputs /></TabsContent>
        <TabsContent value="remit"><Remittances runs={runs?.rows ?? []} /></TabsContent>
      </Tabs>
    </div>
  );
}

function RunReview({ run }: { run: Row }) {
  const { data: reg, isLoading } = useHrGet<Row>(`/hr/analytics/payroll-register/${run.id}`);
  const { data: variance } = useHrGet<Row>(`/hr/payroll/runs/${run.id}/variance`);
  const { data: accounts } = useHrGet<{ data: Row[] }>('/accounts', { pageSize: 200 });
  const post = useHrSend('post');
  const [accountId, setAccountId] = useState('');
  const approved = run.status === 'APPROVED' || run.status === 'PAID';
  const cols: string[] = reg?.columns ?? [];
  const accountRows: Row[] = accounts?.data ?? [];
  const file = (format: string) =>
    downloadHrFile(`/hr/payroll/runs/${run.id}/bank-file?format=${format}`, `payroll-${run.runNumber}.txt`)
      .catch((err) => notify.error(apiErrorMessage(err, tx('Could not create the file'))));
  return (
    <div className="space-y-4">
      <Section title={tx('Payroll register')} actions={
        <Button size="sm" variant="outline" onClick={() => downloadHrFile(`/hr/analytics/payroll-register/${run.id}/csv`, 'register.csv')}>
          <Download className="mr-1 h-4 w-4" />CSV
        </Button>}>
        <DataTable loading={isLoading} rows={reg?.rows} empty={tx('Calculate the run to see its register.')} cols={[
          { key: 'employeeCode', label: tx('Code') },
          { key: 'name', label: tx('Employee') },
          ...cols.map((c) => ({ key: c, label: c, align: 'right' as const, render: (r: Row) => money(r[c]) })),
          { key: 'grossPay', label: tx('Gross'), align: 'right', render: (r) => money(r.grossPay) },
          { key: 'totalDeductions', label: tx('Deductions'), align: 'right', render: (r) => money(r.totalDeductions) },
          { key: 'netPay', label: tx('Net'), align: 'right', render: (r) => money(r.netPay) },
        ]} />
      </Section>
      <Section title={tx('Variance against the previous run')} actions={variance && <Badge variant={variance.flagged ? 'destructive' : 'secondary'}>{tx('{{n}} flagged', { n: variance.flagged })}</Badge>}>
        <DataTable rows={variance?.rows} empty={tx('No previous run to compare with.')} cols={[
          { key: 'employeeCode', label: tx('Code') },
          { key: 'name', label: tx('Employee') },
          { key: 'status', label: tx('Status') },
          { key: 'previousNet', label: tx('Previous net'), align: 'right', render: (r) => money(r.previousNet) },
          { key: 'net', label: tx('Net'), align: 'right', render: (r) => money(r.net) },
          { key: 'netChangePct', label: '%', align: 'right', render: (r) => (r.netChangePct == null ? '—' : `${r.netChangePct}`) },
          { key: 'flagged', label: '', render: (r) => (r.flagged ? <Badge variant="destructive">{tx('Check')}</Badge> : null) },
        ]} />
      </Section>
      {approved && (
        <Section title={tx('Pay out')}>
          <div className="flex flex-wrap gap-2">
            <Button size="sm" variant="outline" onClick={() => file('CSV')}><Download className="mr-1 h-4 w-4" />{tx('Bank file (CSV)')}</Button>
            <Button size="sm" variant="outline" onClick={() => file('MOBILE_MONEY')}><Download className="mr-1 h-4 w-4" />{tx('Mobile money file')}</Button>
            <Button size="sm" variant="outline" onClick={() => file('WPS_SIF')}><Download className="mr-1 h-4 w-4" />{tx('UAE WPS (SIF)')}</Button>
            <Button size="sm" variant="outline" onClick={() => post.mutate({ path: `/hr/payroll/runs/${run.id}/payslips/email` }, done(tx('Payslips e-mailed')))}>
              <Mail className="mr-1 h-4 w-4" />{tx('E-mail payslips')}
            </Button>
          </div>
          {run.status === 'APPROVED' && (
            <div className="flex flex-wrap items-end gap-2">
              <Field label={tx('Pay from account')}>
                <select className={selectClass} value={accountId} onChange={(e) => setAccountId(e.target.value)}>
                  <option value="">—</option>
                  {accountRows.filter((a) => a.isPostable !== false).map((a) => <option key={a.id} value={a.id}>{a.code} · {a.name}</option>)}
                </select>
              </Field>
              <Button disabled={!accountId || post.isPending} onClick={() => post.mutate({ path: `/hr/payroll/runs/${run.id}/settle`, body: { accountId } }, done(tx('Net pay settled')))}>
                {tx('Record net pay as paid')} ({money(run.totalNet)})
              </Button>
            </div>
          )}
        </Section>
      )}
    </div>
  );
}

function Inputs() {
  const [status, setStatus] = useState('pending');
  const { data, isLoading } = useHrGet<Page>('/hr/payroll/inputs', { status });
  const { data: emps } = useHrEmployees({ isActive: 'true', pageSize: 200 });
  const post = useHrSend('post');
  const del = useHrSend('delete');
  const [f, setF] = useState({ employeeId: '', kind: 'earning', name: '', amount: '', isTaxable: true });
  return (
    <Section title={tx('One-off inputs')} actions={
      <select className={selectClass} value={status} onChange={(e) => setStatus(e.target.value)} aria-label={tx('Status')}>
        {['pending', 'included', 'cancelled'].map((s) => <option key={s}>{s}</option>)}
      </select>}>
      <p className="text-xs text-muted-foreground">{tx('Bonuses, commissions, arrears or deductions paid / recovered in the next payroll run.')}</p>
      <div className="grid gap-2 md:grid-cols-6">
        <Field label={tx('Employee')}>
          <select className={selectClass} value={f.employeeId} onChange={(e) => setF({ ...f, employeeId: e.target.value })}>
            <option value="">—</option>
            {(emps?.rows ?? []).map((e: Row) => <option key={e.id} value={e.id}>{e.employeeCode} · {fullName(e)}</option>)}
          </select>
        </Field>
        <Field label={tx('Kind')}>
          <select className={selectClass} value={f.kind} onChange={(e) => setF({ ...f, kind: e.target.value })}>
            <option value="earning">{tx('Earning')}</option>
            <option value="deduction">{tx('Deduction')}</option>
          </select>
        </Field>
        <Field label={tx('Description')}><Input value={f.name} onChange={(e) => setF({ ...f, name: e.target.value })} /></Field>
        <Field label={tx('Amount')}><Input type="number" value={f.amount} onChange={(e) => setF({ ...f, amount: e.target.value })} /></Field>
        <label className="flex items-end gap-1 pb-2 text-sm"><input type="checkbox" checked={f.isTaxable} onChange={(e) => setF({ ...f, isTaxable: e.target.checked })} />{tx('Taxable')}</label>
        <div className="flex items-end">
          <Button disabled={!f.employeeId || !f.name || !f.amount} onClick={() => post.mutate({ path: '/hr/payroll/inputs', body: { ...f, amount: Number(f.amount) } }, done(tx('Input queued')))}>{tx('Add')}</Button>
        </div>
      </div>
      <DataTable loading={isLoading} rows={data?.rows} empty={tx('No inputs.')} cols={[
        { key: 'employee', label: tx('Employee'), render: (r) => `${r.employeeCode} · ${fullName(r)}` },
        { key: 'kind', label: tx('Kind') },
        { key: 'name', label: tx('Description') },
        { key: 'source', label: tx('Source') },
        { key: 'amount', label: tx('Amount'), align: 'right', render: (r) => money(r.amount) },
        { key: 'x', label: '', render: (r) => (r.status === 'pending' && r.source === 'manual' && !r.runId
          ? <Button size="sm" variant="ghost" onClick={() => del.mutate({ path: `/hr/payroll/inputs/${r.id}` }, done(tx('Input cancelled')))}>{tx('Cancel')}</Button> : null) },
      ]} />
    </Section>
  );
}

function Remittances({ runs }: { runs: Row[] }) {
  const periods = Array.from(new Map(runs.filter((r) => r.period).map((r) => [r.period.id, r.period])).values());
  const [periodId, setPeriodId] = useState('');
  const { data, isLoading } = useHrGet<Row>(periodId ? '/hr/payroll/remittances' : null, { periodId });
  const { data: accounts } = useHrGet<{ data: Row[] }>('/accounts', { pageSize: 200 });
  const accountRows: Row[] = accounts?.data ?? [];
  const post = useHrSend('post');
  const [accountId, setAccountId] = useState('');
  return (
    <Section title={tx('Statutory remittances')} actions={
      <select className={selectClass} value={periodId} onChange={(e) => setPeriodId(e.target.value)} aria-label={tx('Period')}>
        <option value="">{tx('Period')}</option>
        {periods.map((p: Row) => <option key={p.id} value={p.id}>{p.periodCode}</option>)}
      </select>}>
      <div className="flex flex-wrap items-end gap-2">
        <Field label={tx('Pay from account')}>
          <select className={selectClass} value={accountId} onChange={(e) => setAccountId(e.target.value)}>
            <option value="">—</option>
            {accountRows.filter((a) => a.isPostable !== false).map((a) => <option key={a.id} value={a.id}>{a.code} · {a.name}</option>)}
          </select>
        </Field>
      </div>
      <DataTable loading={isLoading} rows={data?.rows} empty={tx('Pick a period with an approved engine-v2 run.')} cols={[
        { key: 'statutoryCode', label: tx('Code') },
        { key: 'employee', label: tx('Employee share'), align: 'right', render: (r) => money(r.employee) },
        { key: 'employer', label: tx('Employer share'), align: 'right', render: (r) => money(r.employer) },
        { key: 'total', label: tx('Total'), align: 'right', render: (r) => money(r.total) },
        { key: 'employees', label: tx('Employees'), align: 'right', render: (r) => r.employees?.length ?? 0 },
        { key: 'x', label: '', render: (r) => (r.payment ? <Badge variant="secondary">{tx('Paid')}</Badge>
          : <Button size="sm" disabled={!accountId} onClick={() => post.mutate({ path: '/hr/payroll/remittances/pay', body: { periodId, statutoryCode: r.statutoryCode, accountId } }, done(tx('Remittance recorded')))}>{tx('Pay')}</Button>) },
      ]} />
    </Section>
  );
}

import { useState } from 'react';
import { Input } from '@/components/ui/input';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs';
import { day, fullName, money, useHrGet, type Page, type Row } from '@/features/hr/hr-pro-api';
import { tx } from '@/lib/i18n/tx';
import { DataTable, PageHeader, Section } from './hr-ui';

/** Workforce analytics (HP-10): payroll cost, department cost, headcount and turnover, salary changes, statutory, leave liability. */
export function HrWorkforceAnalyticsPage() {
  const now = new Date();
  const [range, setRange] = useState({ from: `${now.getFullYear()}-01-01`, to: now.toISOString().slice(0, 10) });
  const year = range.to.slice(0, 4);
  const { data: summary, isLoading: l1 } = useHrGet<Page>('/hr/analytics/payroll-summary', range);
  const { data: depts, isLoading: l2 } = useHrGet<Page>('/hr/analytics/cost-by-department', range);
  const { data: heads, isLoading: l3 } = useHrGet<Page>('/hr/analytics/headcount', range);
  const { data: changes, isLoading: l4 } = useHrGet<Page>('/hr/analytics/salary-changes', range);
  const { data: statutory, isLoading: l5 } = useHrGet<Page>('/hr/analytics/statutory', { year });
  const { data: leave, isLoading: l6 } = useHrGet<Page & { liability: number }>('/hr/analytics/leave-liability', { year });
  const total = (summary?.rows ?? []).reduce((s: number, r: Row) => s + Number(r.totalCost ?? 0), 0);
  return (
    <div className="space-y-4 p-6">
      <PageHeader title={tx('Workforce analytics')} subtitle={tx('From approved payroll runs; figures reconcile to the payroll journals.')} />
      <div className="flex flex-wrap gap-2">
        <Input type="date" className="w-40" aria-label={tx('From')} value={range.from} onChange={(e) => setRange({ ...range, from: e.target.value })} />
        <Input type="date" className="w-40" aria-label={tx('To')} value={range.to} onChange={(e) => setRange({ ...range, to: e.target.value })} />
      </div>
      <Tabs defaultValue="payroll">
        <TabsList>
          <TabsTrigger value="payroll">{tx('Payroll cost')}</TabsTrigger>
          <TabsTrigger value="departments">{tx('By department')}</TabsTrigger>
          <TabsTrigger value="headcount">{tx('Headcount')}</TabsTrigger>
          <TabsTrigger value="salary">{tx('Salary changes')}</TabsTrigger>
          <TabsTrigger value="statutory">{tx('Statutory')}</TabsTrigger>
          <TabsTrigger value="leave">{tx('Leave liability')}</TabsTrigger>
        </TabsList>
        <TabsContent value="payroll">
          <Section title={tx('Payroll cost by period')}>
            <p className="text-sm">{tx('Total employment cost')}: <span className="font-semibold">{money(total)}</span></p>
            <DataTable loading={l1} rows={summary?.rows} empty={tx('No approved runs in the range.')} cols={[
              { key: 'periodCode', label: tx('Period') },
              { key: 'runNumber', label: tx('Run') },
              { key: 'headcount', label: tx('Paid'), align: 'right' },
              { key: 'gross', label: tx('Gross'), align: 'right', render: (r) => money(r.gross) },
              { key: 'tax', label: tx('Tax'), align: 'right', render: (r) => money(r.tax) },
              { key: 'net', label: tx('Net'), align: 'right', render: (r) => money(r.net) },
              { key: 'employer', label: tx('Employer contributions'), align: 'right', render: (r) => money(r.employer) },
              { key: 'totalCost', label: tx('Total cost'), align: 'right', render: (r) => money(r.totalCost) },
            ]} />
          </Section>
        </TabsContent>
        <TabsContent value="departments">
          <Section title={tx('Payroll cost by department')}>
            <DataTable loading={l2} rows={depts?.rows} empty={tx('No data.')} cols={[
              { key: 'department', label: tx('Department') },
              { key: 'employees', label: tx('Employees'), align: 'right' },
              { key: 'gross', label: tx('Gross'), align: 'right', render: (r) => money(r.gross) },
              { key: 'employer', label: tx('Employer'), align: 'right', render: (r) => money(r.employer) },
              { key: 'totalCost', label: tx('Total cost'), align: 'right', render: (r) => money(r.totalCost) },
            ]} />
          </Section>
        </TabsContent>
        <TabsContent value="headcount">
          <Section title={tx('Headcount and turnover')}>
            <DataTable loading={l3} rows={heads?.rows} empty={tx('No data.')} cols={[
              { key: 'month', label: tx('Month') },
              { key: 'opening', label: tx('Opening'), align: 'right' },
              { key: 'hires', label: tx('Hires'), align: 'right' },
              { key: 'leavers', label: tx('Leavers'), align: 'right' },
              { key: 'closing', label: tx('Closing'), align: 'right' },
              { key: 'turnoverPct', label: tx('Turnover %'), align: 'right' },
            ]} />
          </Section>
        </TabsContent>
        <TabsContent value="salary">
          <Section title={tx('Salary changes')}>
            <DataTable loading={l4} rows={changes?.rows} empty={tx('No approved revisions in the range.')} cols={[
              { key: 'employee', label: tx('Employee'), render: (r) => `${r.employeeCode} · ${fullName(r)}` },
              { key: 'effectiveFrom', label: tx('Effective'), render: (r) => day(r.effectiveFrom) },
              { key: 'previousBasic', label: tx('From'), align: 'right', render: (r) => money(r.previousBasic) },
              { key: 'basicSalary', label: tx('To'), align: 'right', render: (r) => money(r.basicSalary) },
              { key: 'changePct', label: '%', align: 'right', render: (r) => r.changePct ?? '—' },
              { key: 'reason', label: tx('Reason') },
            ]} />
          </Section>
        </TabsContent>
        <TabsContent value="statutory">
          <Section title={tx('Statutory deductions and contributions')}>
            <DataTable loading={l5} rows={statutory?.rows} empty={tx('No statutory lines this year.')} cols={[
              { key: 'statutoryCode', label: tx('Code') },
              { key: 'month', label: tx('Month') },
              { key: 'employee', label: tx('Employee'), align: 'right', render: (r) => money(r.employee) },
              { key: 'employer', label: tx('Employer'), align: 'right', render: (r) => money(r.employer) },
            ]} />
          </Section>
        </TabsContent>
        <TabsContent value="leave">
          <Section title={tx('Leave liability')}>
            <p className="text-sm">{tx('Total')}: <span className="font-semibold">{money(leave?.liability)}</span></p>
            <DataTable loading={l6} rows={leave?.rows} empty={tx('No unused paid leave.')} cols={[
              { key: 'employee', label: tx('Employee'), render: (r) => `${r.employeeCode} · ${fullName(r)}` },
              { key: 'days', label: tx('Days'), align: 'right' },
              { key: 'amount', label: tx('Value'), align: 'right', render: (r) => money(r.amount) },
            ]} />
          </Section>
        </TabsContent>
      </Tabs>
    </div>
  );
}

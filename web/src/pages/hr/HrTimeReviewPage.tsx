import { useState } from 'react';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { day, fullName, useHrGet, useHrSend, type Page, type Row } from '@/features/hr/hr-pro-api';
import { tx } from '@/lib/i18n/tx';
import { DataTable, PageHeader, Section, done, selectClass } from './hr-ui';

/** Time to pay (HP-5): overtime approval and the pre-payroll review of a period. */
export function HrTimeReviewPage() {
  const { data: periods } = useHrGet<Page>('/hr/payroll/periods', { take: 60 });
  const [periodId, setPeriodId] = useState('');
  const { data: summary, isLoading } = useHrGet<Row>(periodId ? `/hr/time/periods/${periodId}/summary` : null);
  const today = new Date().toISOString().slice(0, 10);
  const [range, setRange] = useState({ from: today.slice(0, 8) + '01', to: today, status: 'pending' });
  const { data: ot, isLoading: otLoading } = useHrGet<Page>('/hr/time/overtime', range);
  const [picked, setPicked] = useState<string[]>([]);
  const post = useHrSend('post');
  const review = (approved: boolean) => post.mutate({ path: '/hr/time/overtime/review', body: { attendanceIds: picked, approved } },
    { ...done(approved ? tx('Overtime approved') : tx('Overtime rejected')), onSettled: () => setPicked([]) });
  return (
    <div className="space-y-4 p-6">
      <PageHeader title={tx('Time review')} subtitle={tx('Approve overtime and check hours, absences and unpaid leave before running payroll.')} />
      <Section title={tx('Overtime')} actions={<>
        <Input type="date" className="h-8 w-40" aria-label={tx('From')} value={range.from} onChange={(e) => setRange({ ...range, from: e.target.value })} />
        <Input type="date" className="h-8 w-40" aria-label={tx('To')} value={range.to} onChange={(e) => setRange({ ...range, to: e.target.value })} />
        <select className={selectClass} aria-label={tx('Status')} value={range.status} onChange={(e) => setRange({ ...range, status: e.target.value })}>
          {['pending', 'approved', 'rejected', 'all'].map((s) => <option key={s}>{s}</option>)}
        </select>
        <Button size="sm" disabled={!picked.length} onClick={() => review(true)}>{tx('Approve')} ({picked.length})</Button>
        <Button size="sm" variant="outline" disabled={!picked.length} onClick={() => review(false)}>{tx('Reject')}</Button>
      </>}>
        <DataTable loading={otLoading} rows={ot?.rows} empty={tx('No overtime in the range.')} cols={[
          { key: 'pick', label: '', render: (r) => <input type="checkbox" aria-label={tx('Select')} checked={picked.includes(r.id)} onChange={(e) => setPicked(e.target.checked ? [...picked, r.id] : picked.filter((x) => x !== r.id))} /> },
          { key: 'date', label: tx('Date'), render: (r) => day(r.date) },
          { key: 'employee', label: tx('Employee'), render: (r) => `${r.employeeCode} · ${fullName(r)}` },
          { key: 'workedMinutes', label: tx('Worked h'), align: 'right', render: (r) => (Number(r.workedMinutes ?? 0) / 60).toFixed(2) },
          { key: 'overtimeMinutes', label: tx('Overtime h'), align: 'right', render: (r) => (Number(r.overtimeMinutes) / 60).toFixed(2) },
          { key: 'overtimeApproved', label: tx('Status'), render: (r) => (r.overtimeApproved == null ? <Badge variant="outline">{tx('pending')}</Badge> : r.overtimeApproved ? <Badge variant="secondary">{tx('approved')}</Badge> : <Badge variant="destructive">{tx('rejected')}</Badge>) },
        ]} />
      </Section>
      <Section title={tx('Period review')} actions={
        <select className={selectClass} aria-label={tx('Period')} value={periodId} onChange={(e) => setPeriodId(e.target.value)}>
          <option value="">{tx('Period')}</option>
          {(periods?.rows ?? []).map((p) => <option key={p.id} value={p.id}>{p.periodCode} · {p.status}</option>)}
        </select>}>
        {summary && <p className="text-sm">{tx('Overtime still pending')}: <span className="font-semibold">{summary.overtimePendingHours} h</span>
          {summary.requireOvertimeApproval ? ` · ${tx('only approved overtime is paid')}` : ''}</p>}
        <DataTable loading={isLoading} rows={summary?.rows} empty={tx('Pick a payroll period.')} cols={[
          { key: 'name', label: tx('Employee'), render: (r) => `${r.employeeCode} · ${r.name}` },
          { key: 'attendanceDays', label: tx('Days'), align: 'right' },
          { key: 'workedHours', label: tx('Worked h'), align: 'right' },
          { key: 'overtimeApprovedHours', label: tx('OT approved'), align: 'right' },
          { key: 'overtimePendingHours', label: tx('OT pending'), align: 'right' },
          { key: 'absentDays', label: tx('Absent'), align: 'right' },
          { key: 'lateMinutes', label: tx('Late min'), align: 'right' },
          { key: 'unpaidLeaveDays', label: tx('Unpaid leave'), align: 'right' },
        ]} />
      </Section>
    </div>
  );
}

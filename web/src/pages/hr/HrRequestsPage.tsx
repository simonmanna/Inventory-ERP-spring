import { useState } from 'react';
import { Button } from '@/components/ui/button';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs';
import { day, fullName, money, useHrGet, useHrSend, type Page, type Row } from '@/features/hr/hr-pro-api';
import { tx } from '@/lib/i18n/tx';
import { DataTable, PageHeader, Section, done, selectClass } from './hr-ui';

/** HR review of self-service requests (HP-8): profile changes and pay claims. */
export function HrRequestsPage() {
  return (
    <div className="space-y-4 p-6">
      <PageHeader title={tx('Employee requests')} subtitle={tx('Approve profile changes and pay claims submitted from self-service.')} />
      <Tabs defaultValue="claims">
        <TabsList>
          <TabsTrigger value="claims">{tx('Pay claims')}</TabsTrigger>
          <TabsTrigger value="profile">{tx('Profile changes')}</TabsTrigger>
        </TabsList>
        <TabsContent value="claims"><Claims /></TabsContent>
        <TabsContent value="profile"><Profile /></TabsContent>
      </Tabs>
    </div>
  );
}

function StatusSelect({ value, onChange, options }: { value: string; onChange: (v: string) => void; options: string[] }) {
  return (
    <select className={selectClass} aria-label={tx('Status')} value={value} onChange={(e) => onChange(e.target.value)}>
      {options.map((s) => <option key={s}>{s}</option>)}
    </select>
  );
}

function Claims() {
  const [status, setStatus] = useState('PENDING');
  const { data, isLoading } = useHrGet<Page>('/hr/claims', { status });
  const post = useHrSend('post');
  const reject = (id: string) => {
    const note = window.prompt(tx('Reason for rejecting'));
    if (note) post.mutate({ path: `/hr/claims/${id}/reject`, body: { note } }, done(tx('Claim rejected')));
  };
  return (
    <Section title={tx('Pay claims')} actions={<StatusSelect value={status} onChange={setStatus} options={['PENDING', 'APPROVED', 'REJECTED', 'CANCELLED']} />}>
      <p className="text-xs text-muted-foreground">{tx('An approved claim is paid in the next payroll run (not taxable unless marked).')}</p>
      <DataTable loading={isLoading} rows={data?.rows} empty={tx('No claims.')} cols={[
        { key: 'claimNumber', label: tx('Number') },
        { key: 'employee', label: tx('Employee'), render: (r) => `${r.employeeCode} · ${fullName(r)}` },
        { key: 'claimType', label: tx('Type') },
        { key: 'claimDate', label: tx('Date'), render: (r) => day(r.claimDate) },
        { key: 'description', label: tx('Description') },
        { key: 'amount', label: tx('Amount'), align: 'right', render: (r) => money(r.amount) },
        { key: 'x', label: '', render: (r) => (r.status === 'PENDING' ? (
          <div className="flex gap-1">
            <Button size="sm" onClick={() => post.mutate({ path: `/hr/claims/${r.id}/approve`, body: {} }, done(tx('Claim approved')))}>{tx('Approve')}</Button>
            <Button size="sm" variant="outline" onClick={() => reject(r.id)}>{tx('Reject')}</Button>
          </div>) : r.reviewNote ?? null) },
      ]} />
    </Section>
  );
}

function Profile() {
  const [status, setStatus] = useState('PENDING');
  const { data, isLoading } = useHrGet<Page>('/hr/profile-requests', { status });
  const post = useHrSend('post');
  const changes = (r: Row) => Object.entries(r.changes ?? {}).map(([k, v]) => `${k}: ${r.previous?.[k] ?? '—'} → ${v ?? '—'}`).join('; ');
  const reject = (id: string) => {
    const note = window.prompt(tx('Reason for rejecting'));
    if (note) post.mutate({ path: `/hr/profile-requests/${id}/reject`, body: { note } }, done(tx('Request rejected')));
  };
  return (
    <Section title={tx('Profile changes')} actions={<StatusSelect value={status} onChange={setStatus} options={['PENDING', 'APPROVED', 'REJECTED', 'CANCELLED']} />}>
      <DataTable loading={isLoading} rows={data?.rows} empty={tx('No requests.')} cols={[
        { key: 'employee', label: tx('Employee'), render: (r) => `${r.employeeCode} · ${fullName(r)}` },
        { key: 'createdAt', label: tx('Date'), render: (r) => day(r.createdAt) },
        { key: 'changes', label: tx('Changes'), render: changes },
        { key: 'x', label: '', render: (r) => (r.status === 'PENDING' ? (
          <div className="flex gap-1">
            <Button size="sm" onClick={() => post.mutate({ path: `/hr/profile-requests/${r.id}/approve`, body: {} }, done(tx('Change applied')))}>{tx('Apply')}</Button>
            <Button size="sm" variant="outline" onClick={() => reject(r.id)}>{tx('Reject')}</Button>
          </div>) : r.reviewNote ?? null) },
      ]} />
    </Section>
  );
}

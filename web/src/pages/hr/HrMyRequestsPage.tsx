import { useState } from 'react';
import { Download } from 'lucide-react';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs';
import { day, downloadHrFile, fullName, money, useHrGet, useHrSend, type Page, type Row } from '@/features/hr/hr-pro-api';
import { notify } from '@/lib/notify';
import { apiErrorMessage } from '@/lib/api-error';
import { tx } from '@/lib/i18n/tx';
import { DataTable, Field, PageHeader, Section, done, selectClass } from './hr-ui';

const CLAIM_TYPES = ['TRANSPORT', 'MEALS', 'MEDICAL', 'PHONE', 'TRAINING', 'TRAVEL', 'OTHER'];
const PROFILE_FIELDS: [string, string][] = [
  ['phone', 'Phone'], ['email', 'E-mail'], ['address', 'Address'], ['emergencyContactName', 'Emergency contact'],
  ['emergencyContactPhone', 'Emergency phone'], ['bankName', 'Bank'], ['bankAccountName', 'Account name'],
  ['bankAccountNumber', 'Account number'], ['mobileMoneyNumber', 'Mobile money number'],
];

/** Self-service (HP-8): payslip PDFs, annual earnings, claims, profile changes, approvals inbox, appraisals. */
export function HrMyRequestsPage() {
  return (
    <div className="space-y-4 p-6">
      <PageHeader title={tx('My pay and requests')} subtitle={tx('Download payslips, claim expenses, update your details and review your team.')} />
      <Tabs defaultValue="pay">
        <TabsList>
          <TabsTrigger value="pay">{tx('Payslips')}</TabsTrigger>
          <TabsTrigger value="claims">{tx('Claims')}</TabsTrigger>
          <TabsTrigger value="profile">{tx('My details')}</TabsTrigger>
          <TabsTrigger value="inbox">{tx('Approvals')}</TabsTrigger>
          <TabsTrigger value="appraisals">{tx('Appraisals')}</TabsTrigger>
        </TabsList>
        <TabsContent value="pay"><Payslips /></TabsContent>
        <TabsContent value="claims"><Claims /></TabsContent>
        <TabsContent value="profile"><Profile /></TabsContent>
        <TabsContent value="inbox"><Inbox /></TabsContent>
        <TabsContent value="appraisals"><Appraisals /></TabsContent>
      </Tabs>
    </div>
  );
}

const fail = (err: unknown) => notify.error(apiErrorMessage(err, tx('Could not download the file')));

function Payslips() {
  const { data, isLoading } = useHrGet<Page>('/hr/me/payslips');
  const rows: Row[] = data?.rows ?? [];
  const [year, setYear] = useState(String(new Date().getFullYear()));
  return (
    <Section title={tx('Payslips')} actions={<>
      <Input type="number" className="h-8 w-24" aria-label={tx('Year')} value={year} onChange={(e) => setYear(e.target.value)} />
      <Button size="sm" variant="outline" onClick={() => downloadHrFile(`/hr/me/earnings/${year}/pdf`, `earnings-${year}.pdf`).catch(fail)}>
        <Download className="mr-1 h-4 w-4" />{tx('Annual earnings certificate')}
      </Button>
    </>}>
      <DataTable loading={isLoading} rows={rows} empty={tx('No payslips yet.')} cols={[
        { key: 'payslipNumber', label: tx('Payslip') },
        { key: 'run', label: tx('Run'), render: (r) => r.item?.run?.runNumber ?? '—' },
        { key: 'issuedAt', label: tx('Issued'), render: (r) => day(r.issuedAt) },
        { key: 'netPay', label: tx('Net pay'), align: 'right', render: (r) => money(r.item?.netPay) },
        { key: 'x', label: '', render: (r) => (
          <Button size="sm" variant="ghost" onClick={() => downloadHrFile(`/hr/me/payslips/${r.id}/pdf`, 'payslip.pdf').catch(fail)}>
            <Download className="mr-1 h-4 w-4" />PDF
          </Button>) },
      ]} />
    </Section>
  );
}

function Claims() {
  const { data, isLoading } = useHrGet<Page>('/hr/me/claims');
  const post = useHrSend('post');
  const [f, setF] = useState({ claimType: 'TRANSPORT', amount: '', claimDate: new Date().toISOString().slice(0, 10), description: '' });
  return (
    <Section title={tx('Claims')}>
      <div className="grid gap-2 md:grid-cols-5">
        <Field label={tx('Type')}>
          <select className={selectClass} value={f.claimType} onChange={(e) => setF({ ...f, claimType: e.target.value })}>
            {CLAIM_TYPES.map((t) => <option key={t}>{t}</option>)}
          </select>
        </Field>
        <Field label={tx('Amount')}><Input type="number" value={f.amount} onChange={(e) => setF({ ...f, amount: e.target.value })} /></Field>
        <Field label={tx('Date')}><Input type="date" value={f.claimDate} onChange={(e) => setF({ ...f, claimDate: e.target.value })} /></Field>
        <Field label={tx('Description')}><Input value={f.description} onChange={(e) => setF({ ...f, description: e.target.value })} /></Field>
        <div className="flex items-end">
          <Button disabled={!f.amount || post.isPending} onClick={() => post.mutate({ path: '/hr/me/claims', body: { ...f, amount: Number(f.amount) } }, done(tx('Claim submitted')))}>{tx('Submit')}</Button>
        </div>
      </div>
      <DataTable loading={isLoading} rows={data?.rows} empty={tx('No claims yet.')} cols={[
        { key: 'claimNumber', label: tx('Number') },
        { key: 'claimType', label: tx('Type') },
        { key: 'claimDate', label: tx('Date'), render: (r) => day(r.claimDate) },
        { key: 'amount', label: tx('Amount'), align: 'right', render: (r) => money(r.amount) },
        { key: 'status', label: tx('Status'), render: (r) => <Badge variant="outline">{r.status}</Badge> },
        { key: 'x', label: '', render: (r) => (r.status === 'PENDING'
          ? <Button size="sm" variant="ghost" onClick={() => post.mutate({ path: `/hr/me/claims/${r.id}/cancel` }, done(tx('Claim cancelled')))}>{tx('Cancel')}</Button>
          : r.reviewNote ?? null) },
      ]} />
    </Section>
  );
}

function Profile() {
  const { data, isLoading } = useHrGet<Page>('/hr/me/profile-requests');
  const post = useHrSend('post');
  const [changes, setChanges] = useState<Record<string, string>>({});
  const filled = Object.fromEntries(Object.entries(changes).filter(([, v]) => v.trim() !== ''));
  return (
    <Section title={tx('Request a change to my details')}>
      <p className="text-xs text-muted-foreground">{tx('Fill only what changes. HR applies the change after review.')}</p>
      <div className="grid gap-2 md:grid-cols-3">
        {PROFILE_FIELDS.map(([k, label]) => (
          <Field key={k} label={tx(label)}><Input value={changes[k] ?? ''} onChange={(e) => setChanges({ ...changes, [k]: e.target.value })} /></Field>
        ))}
      </div>
      <Button disabled={!Object.keys(filled).length || post.isPending} onClick={() => post.mutate({ path: '/hr/me/profile-requests', body: { changes: filled } },
        { ...done(tx('Request sent to HR')), onSettled: () => setChanges({}) })}>{tx('Send request')}</Button>
      <DataTable loading={isLoading} rows={data?.rows} empty={tx('No requests yet.')} cols={[
        { key: 'createdAt', label: tx('Date'), render: (r) => day(r.createdAt) },
        { key: 'changes', label: tx('Changes'), render: (r) => Object.keys(r.changes ?? {}).join(', ') },
        { key: 'status', label: tx('Status'), render: (r) => <Badge variant="outline">{r.status}</Badge> },
        { key: 'reviewNote', label: tx('Note') },
      ]} />
    </Section>
  );
}

function Inbox() {
  const { data } = useHrGet<Row>('/hr/me/approvals');
  const post = useHrSend('post');
  const reject = (id: string) => {
    const note = window.prompt(tx('Reason for rejecting'));
    if (note) post.mutate({ path: `/hr/me/approvals/claims/${id}/reject`, body: { note } }, done(tx('Claim rejected')));
  };
  return (
    <div className="space-y-4">
      <Section title={tx('Leave requests of my team')}>
        <DataTable rows={data?.leave} empty={tx('Nothing waiting.')} cols={[
          { key: 'employee', label: tx('Employee'), render: (r) => fullName(r) },
          { key: 'leaveType', label: tx('Type') },
          { key: 'startDate', label: tx('From'), render: (r) => day(r.startDate) },
          { key: 'endDate', label: tx('To'), render: (r) => day(r.endDate) },
          { key: 'days', label: tx('Days'), align: 'right' },
          { key: 'x', label: '', render: (r) => (
            <div className="flex gap-1">
              <Button size="sm" onClick={() => post.mutate({ path: `/hr/team/leave/${r.id}/approve`, body: {} }, done(tx('Leave approved')))}>{tx('Approve')}</Button>
              <Button size="sm" variant="outline" onClick={() => post.mutate({ path: `/hr/team/leave/${r.id}/reject`, body: { reason: tx('Rejected by manager') } }, done(tx('Leave rejected')))}>{tx('Reject')}</Button>
            </div>) },
        ]} />
      </Section>
      <Section title={tx('Claims of my team')}>
        <DataTable rows={data?.claims} empty={tx('Nothing waiting.')} cols={[
          { key: 'employee', label: tx('Employee'), render: (r) => fullName(r) },
          { key: 'claimType', label: tx('Type') },
          { key: 'amount', label: tx('Amount'), align: 'right', render: (r) => money(r.amount) },
          { key: 'description', label: tx('Description') },
          { key: 'x', label: '', render: (r) => (
            <div className="flex gap-1">
              <Button size="sm" onClick={() => post.mutate({ path: `/hr/me/approvals/claims/${r.id}/approve`, body: {} }, done(tx('Claim approved')))}>{tx('Approve')}</Button>
              <Button size="sm" variant="outline" onClick={() => reject(r.id)}>{tx('Reject')}</Button>
            </div>) },
        ]} />
      </Section>
    </div>
  );
}

function Appraisals() {
  const { data, isLoading } = useHrGet<Page>('/hr/me/appraisals');
  const [openId, setOpenId] = useState('');
  return (
    <div className="space-y-4">
      <Section title={tx('Appraisals to complete')}>
        <DataTable loading={isLoading} rows={data?.rows} onRow={(r) => setOpenId(r.id)} selectedId={openId} empty={tx('No appraisals.')} cols={[
          { key: 'cycleName', label: tx('Cycle') },
          { key: 'employee', label: tx('Employee'), render: (r) => fullName(r) },
          { key: 'reviewerType', label: tx('As') },
          { key: 'status', label: tx('Status'), render: (r) => <Badge variant="outline">{r.status}</Badge> },
          { key: 'overallScore', label: tx('Score'), align: 'right', render: (r) => r.overallScore ?? '—' },
        ]} />
      </Section>
      {openId && <AppraisalForm id={openId} path={`/hr/me/appraisals/${openId}`} submitPath={`/hr/me/appraisals/${openId}/submit`} />}
    </div>
  );
}

/** Score goals and competencies 1–5 and submit (shared with the HR talent screen). */
export function AppraisalForm({ id, path, submitPath }: { id: string; path: string; submitPath: string }) {
  const { data: a } = useHrGet<Row>(path);
  const post = useHrSend('post');
  const [goal, setGoal] = useState<Record<string, number>>({});
  const [comp, setComp] = useState<Record<string, number>>({});
  const [comments, setComments] = useState('');
  if (!a) return null;
  const score = (v: number | undefined, set: (n: number) => void) => (
    <select className={selectClass} value={v ?? ''} onChange={(e) => set(Number(e.target.value))} aria-label={tx('Score')}>
      <option value="">—</option>
      {[1, 2, 3, 4, 5].map((n) => <option key={n} value={n}>{n}</option>)}
    </select>
  );
  return (
    <Section title={`${tx('Appraisal')} · ${a.cycle?.name ?? ''}`}>
      <p className="text-xs text-muted-foreground">{tx('1 = well below expectations, 3 = meets, 5 = far exceeds.')}</p>
      {(a.goals ?? []).map((g: Row) => (
        <div key={g.id} className="flex items-center justify-between gap-2 text-sm">
          <span>{g.title} <span className="text-muted-foreground">({g.weight}%)</span></span>
          {score(goal[g.id] ?? a.goalScores?.[g.id], (n) => setGoal({ ...goal, [g.id]: n }))}
        </div>
      ))}
      {(a.competencies ?? []).map((c: Row) => (
        <div key={c.id} className="flex items-center justify-between gap-2 text-sm">
          <span>{c.name}</span>
          {score(comp[c.id] ?? a.competencyScores?.[c.id], (n) => setComp({ ...comp, [c.id]: n }))}
        </div>
      ))}
      <Field label={tx('Comments')}><Input value={comments} onChange={(e) => setComments(e.target.value)} /></Field>
      <Button disabled={a.status === 'FINALIZED' || post.isPending} onClick={() => post.mutate({ path: submitPath, body: {
        goalScores: { ...(a.goalScores ?? {}), ...goal }, competencyScores: { ...(a.competencyScores ?? {}), ...comp }, comments: comments || undefined,
      } }, done(tx('Appraisal submitted')))}>{tx('Submit')}</Button>
      <input type="hidden" value={id} />
    </Section>
  );
}

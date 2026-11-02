import { useState } from 'react';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs';
import { useHrDepartments, useHrEmployees } from '@/features/hr/api';
import { day, fullName, useHrGet, useHrSend, type Page, type Row } from '@/features/hr/hr-pro-api';
import { tx } from '@/lib/i18n/tx';
import { AppraisalForm } from './HrMyRequestsPage';
import { DataTable, Field, PageHeader, Section, done, selectClass } from './hr-ui';

/** Talent (HP-9): competencies, review cycles with weighted goals, appraisals and results. */
export function HrTalentPage() {
  return (
    <div className="space-y-4 p-6">
      <PageHeader title={tx('Performance cycles')} subtitle={tx('Goals, competencies and appraisals; closing a cycle records each result as a performance review.')} />
      <Tabs defaultValue="cycles">
        <TabsList>
          <TabsTrigger value="cycles">{tx('Cycles')}</TabsTrigger>
          <TabsTrigger value="competencies">{tx('Competencies')}</TabsTrigger>
        </TabsList>
        <TabsContent value="cycles"><Cycles /></TabsContent>
        <TabsContent value="competencies"><Competencies /></TabsContent>
      </Tabs>
    </div>
  );
}

function Cycles() {
  const { data, isLoading } = useHrGet<Page>('/hr/talent/cycles');
  const { data: comps } = useHrGet<Page>('/hr/talent/competencies');
  const { data: depts } = useHrDepartments();
  const post = useHrSend<unknown, Row>('post');
  const [selected, setSelected] = useState('');
  const [f, setF] = useState({ name: '', periodStart: '', periodEnd: '', goalWeightPct: '60', departmentId: '', peerReview: false });
  return (
    <div className="space-y-4">
      <Section title={tx('New cycle')}>
        <div className="grid gap-2 md:grid-cols-6">
          <Field label={tx('Name')}><Input value={f.name} onChange={(e) => setF({ ...f, name: e.target.value })} /></Field>
          <Field label={tx('From')}><Input type="date" value={f.periodStart} onChange={(e) => setF({ ...f, periodStart: e.target.value })} /></Field>
          <Field label={tx('To')}><Input type="date" value={f.periodEnd} onChange={(e) => setF({ ...f, periodEnd: e.target.value })} /></Field>
          <Field label={tx('Goals weight %')}><Input type="number" value={f.goalWeightPct} onChange={(e) => setF({ ...f, goalWeightPct: e.target.value })} /></Field>
          <Field label={tx('Department')}>
            <select className={selectClass} value={f.departmentId} onChange={(e) => setF({ ...f, departmentId: e.target.value })}>
              <option value="">{tx('Everyone')}</option>
              {(depts?.rows ?? []).map((d: Row) => <option key={d.id} value={d.id}>{d.name}</option>)}
            </select>
          </Field>
          <div className="flex items-end">
            <Button disabled={!f.name || !f.periodStart || !f.periodEnd} onClick={() => post.mutate({ path: '/hr/talent/cycles', body: {
              ...f, goalWeightPct: Number(f.goalWeightPct), departmentId: f.departmentId || undefined,
              competencyIds: (comps?.rows ?? []).filter((c) => c.isActive !== false).map((c) => c.id),
            } }, { ...done(tx('Cycle created')), onSuccess: (r) => setSelected(r.id as string) })}>{tx('Create')}</Button>
          </div>
        </div>
      </Section>
      <Section title={tx('Cycles')}>
        <DataTable loading={isLoading} rows={data?.rows} onRow={(r) => setSelected(r.id)} selectedId={selected} empty={tx('No cycles yet.')} cols={[
          { key: 'name', label: tx('Name') },
          { key: 'period', label: tx('Period'), render: (r) => `${day(r.periodStart)} – ${day(r.periodEnd)}` },
          { key: 'participants', label: tx('People'), align: 'right' },
          { key: 'submitted', label: tx('Submitted'), align: 'right' },
          { key: 'status', label: tx('Status'), render: (r) => <Badge variant="outline">{r.status}</Badge> },
        ]} />
      </Section>
      {selected && <CycleDetail id={selected} />}
    </div>
  );
}

function CycleDetail({ id }: { id: string }) {
  const { data: c } = useHrGet<Row>(`/hr/talent/cycles/${id}`);
  const { data: emps } = useHrEmployees({ isActive: 'true', pageSize: 200 });
  const post = useHrSend('post');
  const [empId, setEmpId] = useState('');
  const { data: goals } = useHrGet<Page>(empId ? '/hr/talent/goals' : null, { cycleId: id, employeeId: empId });
  const [g, setG] = useState({ title: '', weight: '', target: '' });
  const [appraisal, setAppraisal] = useState('');
  if (!c) return null;
  return (
    <div className="space-y-4">
      <Section title={c.name} actions={<>
        {c.status === 'DRAFT' && <Button size="sm" onClick={() => post.mutate({ path: `/hr/talent/cycles/${id}/launch` }, done(tx('Cycle launched')))}>{tx('Launch')}</Button>}
        {c.status === 'OPEN' && <Button size="sm" onClick={() => window.confirm(tx('Close the cycle and record the results?')) && post.mutate({ path: `/hr/talent/cycles/${id}/close` }, done(tx('Cycle closed')))}>{tx('Close')}</Button>}
      </>}>
        <DataTable rows={c.appraisals} onRow={(r) => setAppraisal(r.id)} selectedId={appraisal} empty={tx('Launch the cycle to create appraisals.')} cols={[
          { key: 'employee', label: tx('Employee'), render: (r) => `${r.employeeCode} · ${fullName(r)}` },
          { key: 'reviewerType', label: tx('Reviewer') },
          { key: 'reviewer', label: tx('By'), render: (r) => [r.reviewerFirstName, r.reviewerLastName].filter(Boolean).join(' ') || '—' },
          { key: 'goalScore', label: tx('Goals'), align: 'right', render: (r) => r.goalScore ?? '—' },
          { key: 'competencyScore', label: tx('Competencies'), align: 'right', render: (r) => r.competencyScore ?? '—' },
          { key: 'overallScore', label: tx('Overall'), align: 'right', render: (r) => r.overallScore ?? '—' },
          { key: 'status', label: tx('Status'), render: (r) => <Badge variant="outline">{r.status}</Badge> },
        ]} />
      </Section>
      {appraisal && c.status === 'OPEN' && <AppraisalForm id={appraisal} path={`/hr/talent/appraisals/${appraisal}`} submitPath={`/hr/talent/appraisals/${appraisal}/submit`} />}
      {c.status !== 'CLOSED' && (
        <Section title={tx('Goals')}>
          <div className="grid gap-2 md:grid-cols-5">
            <Field label={tx('Employee')}>
              <select className={selectClass} value={empId} onChange={(e) => setEmpId(e.target.value)}>
                <option value="">—</option>
                {(emps?.rows ?? []).map((e: Row) => <option key={e.id} value={e.id}>{e.employeeCode} · {fullName(e)}</option>)}
              </select>
            </Field>
            <Field label={tx('Goal')}><Input value={g.title} onChange={(e) => setG({ ...g, title: e.target.value })} /></Field>
            <Field label={tx('Weight %')}><Input type="number" value={g.weight} onChange={(e) => setG({ ...g, weight: e.target.value })} /></Field>
            <Field label={tx('Target')}><Input value={g.target} onChange={(e) => setG({ ...g, target: e.target.value })} /></Field>
            <div className="flex items-end">
              <Button disabled={!empId || !g.title || !g.weight} onClick={() => post.mutate({ path: '/hr/talent/goals', body: { cycleId: id, employeeId: empId, title: g.title, weight: Number(g.weight), target: g.target || undefined } },
                { ...done(tx('Goal added')), onSettled: () => setG({ title: '', weight: '', target: '' }) })}>{tx('Add')}</Button>
            </div>
          </div>
          <DataTable rows={goals?.rows} empty={tx('Pick an employee to see or add goals.')} cols={[
            { key: 'title', label: tx('Goal') },
            { key: 'weight', label: tx('Weight %'), align: 'right' },
            { key: 'target', label: tx('Target') },
            { key: 'score', label: tx('Score'), align: 'right', render: (r) => r.score ?? '—' },
            { key: 'status', label: tx('Status') },
          ]} />
        </Section>
      )}
    </div>
  );
}

function Competencies() {
  const { data, isLoading } = useHrGet<Page>('/hr/talent/competencies');
  const post = useHrSend('post');
  const del = useHrSend('delete');
  const [f, setF] = useState({ code: '', name: '', description: '' });
  return (
    <Section title={tx('Competencies')}>
      <div className="grid gap-2 md:grid-cols-4">
        <Field label={tx('Code')}><Input value={f.code} onChange={(e) => setF({ ...f, code: e.target.value })} /></Field>
        <Field label={tx('Name')}><Input value={f.name} onChange={(e) => setF({ ...f, name: e.target.value })} /></Field>
        <Field label={tx('Description')}><Input value={f.description} onChange={(e) => setF({ ...f, description: e.target.value })} /></Field>
        <div className="flex items-end">
          <Button disabled={!f.code || !f.name} onClick={() => post.mutate({ path: '/hr/talent/competencies', body: { ...f, description: f.description || undefined } },
            { ...done(tx('Competency added')), onSettled: () => setF({ code: '', name: '', description: '' }) })}>{tx('Add')}</Button>
        </div>
      </div>
      <DataTable loading={isLoading} rows={data?.rows} empty={tx('No competencies yet.')} cols={[
        { key: 'code', label: tx('Code') },
        { key: 'name', label: tx('Name') },
        { key: 'description', label: tx('Description') },
        { key: 'x', label: '', render: (r) => <Button size="sm" variant="ghost" onClick={() => del.mutate({ path: `/hr/talent/competencies/${r.id}` }, done(tx('Removed')))}>{tx('Remove')}</Button> },
      ]} />
    </Section>
  );
}

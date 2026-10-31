import { useState } from 'react';
import { Link } from 'react-router-dom';
import { Plus } from 'lucide-react';
import {
  useApplicant,
  useApplicants,
  useJobOpenings,
  useRecruitmentAction,
  type Applicant,
  type JobOpening,
} from '@/features/hr/recruitment-api';
import { useHrDepartments, useHrPositions } from '@/features/hr/api';
import { Card, CardContent } from '@/components/ui/card';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Skeleton } from '@/components/ui/skeleton';
import { Textarea } from '@/components/ui/textarea';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs';
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog';
import { notify } from '@/lib/notify';
import { apiErrorMessage } from '@/lib/api-error';
import { tx } from '@/lib/i18n/tx';

const PAGE = 25;
const STAGES = ['applied', 'screening', 'interview', 'offer', 'hired', 'rejected', 'withdrawn'];
const OPENING_STATUSES = ['draft', 'open', 'on_hold', 'closed', 'cancelled'];
/** The stages an applicant may move to next; hiring has its own action. */
const NEXT_STAGE: Record<string, string[]> = {
  applied: ['screening', 'interview', 'rejected', 'withdrawn'],
  screening: ['interview', 'offer', 'rejected', 'withdrawn'],
  interview: ['offer', 'rejected', 'withdrawn'],
  offer: ['interview', 'rejected', 'withdrawn'],
};
const NEXT_STATUS: Record<string, string[]> = {
  draft: ['open', 'cancelled'],
  open: ['on_hold', 'closed', 'cancelled'],
  on_hold: ['open', 'closed', 'cancelled'],
  closed: ['open'],
};
const TONE: Record<string, string> = {
  open: 'bg-emerald-100 text-emerald-800',
  on_hold: 'bg-amber-100 text-amber-800',
  hired: 'bg-emerald-100 text-emerald-800',
  offer: 'bg-sky-100 text-sky-800',
  rejected: 'bg-rose-100 text-rose-800',
  cancelled: 'bg-rose-100 text-rose-800',
};
const label = (s: string) => s.replace(/_/g, ' ');
const SELECT = 'h-9 rounded-md border bg-background px-3 text-sm';

function Pager({ skip, total, onSkip }: { skip: number; total: number; onSkip: (n: number) => void }) {
  if (total <= PAGE) return null;
  return (
    <div className="mt-3 flex items-center justify-end gap-2 text-sm text-muted-foreground">
      <span>
        {skip + 1}–{Math.min(skip + PAGE, total)} / {total}
      </span>
      <Button size="sm" variant="outline" disabled={skip === 0} onClick={() => onSkip(Math.max(0, skip - PAGE))}>
        {tx('Previous')}
      </Button>
      <Button size="sm" variant="outline" disabled={skip + PAGE >= total} onClick={() => onSkip(skip + PAGE)}>
        {tx('Next')}
      </Button>
    </div>
  );
}

/** Job openings and the applicants moving through hiring. */
export function HrRecruitmentPage() {
  const [tab, setTab] = useState('openings');
  const [openingOpen, setOpeningOpen] = useState(false);
  const [applicantOpen, setApplicantOpen] = useState(false);
  const [selected, setSelected] = useState<string | null>(null);

  return (
    <div className="space-y-4 p-6">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <h1 className="text-xl font-semibold">{tx('Recruitment')}</h1>
          <p className="text-sm text-muted-foreground">
            {tx('Job openings, applicants and interviews, through to the hire.')}
          </p>
        </div>
        <div className="flex gap-2">
          <Button size="sm" variant="outline" onClick={() => setOpeningOpen(true)}>
            <Plus className="mr-1.5 h-4 w-4" />
            {tx('New opening')}
          </Button>
          <Button size="sm" onClick={() => setApplicantOpen(true)}>
            <Plus className="mr-1.5 h-4 w-4" />
            {tx('New applicant')}
          </Button>
        </div>
      </div>
      <Tabs value={tab} onValueChange={setTab}>
        <TabsList>
          <TabsTrigger value="openings">{tx('Openings')}</TabsTrigger>
          <TabsTrigger value="applicants">{tx('Applicants')}</TabsTrigger>
        </TabsList>
        <TabsContent value="openings" className="mt-4">
          <OpeningsTab />
        </TabsContent>
        <TabsContent value="applicants" className="mt-4">
          <ApplicantsTab onSelect={setSelected} />
        </TabsContent>
      </Tabs>
      <OpeningDialog open={openingOpen} onOpenChange={setOpeningOpen} />
      <ApplicantDialog open={applicantOpen} onOpenChange={setApplicantOpen} />
      <ApplicantDetail id={selected} onClose={() => setSelected(null)} />
    </div>
  );
}

function OpeningsTab() {
  const [status, setStatus] = useState('');
  const [q, setQ] = useState('');
  const [skip, setSkip] = useState(0);
  const { data, isLoading, isError } = useJobOpenings({ status, q, take: PAGE, skip });
  const action = useRecruitmentAction();

  const setOpeningStatus = async (o: JobOpening, next: string) => {
    try {
      await action.mutateAsync({ method: 'post', path: `/hr/recruitment/openings/${o.id}/status`, body: { status: next } });
      notify.success(tx('Opening updated'));
    } catch (err) {
      notify.error(apiErrorMessage(err, tx('Could not update the opening')));
    }
  };

  return (
    <Card>
      <CardContent className="space-y-3 pt-6">
        <div className="flex flex-wrap gap-2">
          <Input
            className="max-w-xs"
            placeholder={tx('Search')}
            value={q}
            onChange={(e) => {
              setQ(e.target.value);
              setSkip(0);
            }}
          />
          <select
            className={SELECT}
            value={status}
            onChange={(e) => {
              setStatus(e.target.value);
              setSkip(0);
            }}
          >
            <option value="">{tx('All statuses')}</option>
            {OPENING_STATUSES.map((s) => (
              <option key={s} value={s}>
                {label(s)}
              </option>
            ))}
          </select>
        </div>
        {isLoading ? (
          <Skeleton className="h-40 w-full" />
        ) : isError ? (
          <p className="py-8 text-center text-sm text-destructive">{tx('Could not load the openings')}</p>
        ) : data?.rows.length ? (
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="border-b text-left text-xs uppercase text-muted-foreground">
                  <th className="py-2">{tx('Code')}</th>
                  <th className="py-2">{tx('Title')}</th>
                  <th className="py-2">{tx('Department')}</th>
                  <th className="py-2 text-right">{tx('Applicants')}</th>
                  <th className="py-2 text-right">{tx('Hired')}</th>
                  <th className="py-2">{tx('Status')}</th>
                </tr>
              </thead>
              <tbody>
                {data.rows.map((o) => (
                  <tr key={o.id} className="border-b last:border-0">
                    <td className="py-2 font-mono text-xs">{o.code}</td>
                    <td className="py-2">{o.title}</td>
                    <td className="py-2">{o.department?.name ?? '—'}</td>
                    <td className="py-2 text-right">{o.applicantCount ?? 0}</td>
                    <td className="py-2 text-right">
                      {o.hiredCount ?? 0} / {o.headcount}
                    </td>
                    <td className="py-2">
                      <select
                        className={`rounded border px-2 py-1 text-xs ${TONE[o.status] ?? 'bg-background'}`}
                        value={o.status}
                        onChange={(e) => setOpeningStatus(o, e.target.value)}
                      >
                        <option value={o.status}>{label(o.status)}</option>
                        {(NEXT_STATUS[o.status] ?? []).map((s) => (
                          <option key={s} value={s}>
                            {label(s)}
                          </option>
                        ))}
                      </select>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        ) : (
          <p className="py-8 text-center text-sm text-muted-foreground">{tx('No job openings yet.')}</p>
        )}
        <Pager skip={skip} total={data?.total ?? 0} onSkip={setSkip} />
      </CardContent>
    </Card>
  );
}

function ApplicantsTab({ onSelect }: { onSelect: (id: string) => void }) {
  const [stage, setStage] = useState('');
  const [q, setQ] = useState('');
  const [skip, setSkip] = useState(0);
  const { data, isLoading, isError } = useApplicants({ stage, q, take: PAGE, skip });

  return (
    <Card>
      <CardContent className="space-y-3 pt-6">
        <div className="flex flex-wrap gap-2">
          <Input
            className="max-w-xs"
            placeholder={tx('Search')}
            value={q}
            onChange={(e) => {
              setQ(e.target.value);
              setSkip(0);
            }}
          />
          <select
            className={SELECT}
            value={stage}
            onChange={(e) => {
              setStage(e.target.value);
              setSkip(0);
            }}
          >
            <option value="">{tx('All stages')}</option>
            {STAGES.map((s) => (
              <option key={s} value={s}>
                {label(s)}
              </option>
            ))}
          </select>
        </div>
        {isLoading ? (
          <Skeleton className="h-40 w-full" />
        ) : isError ? (
          <p className="py-8 text-center text-sm text-destructive">{tx('Could not load the applicants')}</p>
        ) : data?.rows.length ? (
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="border-b text-left text-xs uppercase text-muted-foreground">
                  <th className="py-2">{tx('Code')}</th>
                  <th className="py-2">{tx('Name')}</th>
                  <th className="py-2">{tx('Opening')}</th>
                  <th className="py-2">{tx('Applied')}</th>
                  <th className="py-2">{tx('Stage')}</th>
                </tr>
              </thead>
              <tbody>
                {data.rows.map((a) => (
                  <tr key={a.id} className="cursor-pointer border-b last:border-0 hover:bg-muted/50" onClick={() => onSelect(a.id)}>
                    <td className="py-2 font-mono text-xs">{a.code}</td>
                    <td className="py-2">
                      {a.firstName} {a.lastName ?? ''}
                    </td>
                    <td className="py-2">{a.opening?.title ?? '—'}</td>
                    <td className="py-2">{a.appliedOn}</td>
                    <td className="py-2">
                      <Badge className={TONE[a.stage] ?? 'bg-muted text-muted-foreground'}>{label(a.stage)}</Badge>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        ) : (
          <p className="py-8 text-center text-sm text-muted-foreground">{tx('No applicants yet.')}</p>
        )}
        <Pager skip={skip} total={data?.total ?? 0} onSkip={setSkip} />
      </CardContent>
    </Card>
  );
}

function OpeningDialog({ open, onOpenChange }: { open: boolean; onOpenChange: (v: boolean) => void }) {
  const action = useRecruitmentAction();
  const { data: departments } = useHrDepartments();
  const { data: positions } = useHrPositions();
  const [form, setForm] = useState({ title: '', departmentId: '', positionId: '', headcount: '1', closesOn: '', description: '' });
  const set = (k: string, v: string) => setForm((f) => ({ ...f, [k]: v }));

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>{tx('New job opening')}</DialogTitle>
          <DialogDescription>{tx('An opening starts as a draft; open it to accept applicants.')}</DialogDescription>
        </DialogHeader>
        <div className="grid gap-3">
          <div className="grid gap-1.5">
            <Label>{tx('Title')}</Label>
            <Input value={form.title} onChange={(e) => set('title', e.target.value)} />
          </div>
          <div className="grid grid-cols-2 gap-3">
            <div className="grid gap-1.5">
              <Label>{tx('Department')}</Label>
              <select className={SELECT} value={form.departmentId} onChange={(e) => set('departmentId', e.target.value)}>
                <option value="">{tx('Select…')}</option>
                {(departments?.rows ?? []).map((d: { id: string; name: string }) => (
                  <option key={d.id} value={d.id}>
                    {d.name}
                  </option>
                ))}
              </select>
            </div>
            <div className="grid gap-1.5">
              <Label>{tx('Position')}</Label>
              <select className={SELECT} value={form.positionId} onChange={(e) => set('positionId', e.target.value)}>
                <option value="">{tx('Select…')}</option>
                {(positions?.rows ?? []).map((p: { id: string; name: string }) => (
                  <option key={p.id} value={p.id}>
                    {p.name}
                  </option>
                ))}
              </select>
            </div>
          </div>
          <div className="grid grid-cols-2 gap-3">
            <div className="grid gap-1.5">
              <Label>{tx('Headcount')}</Label>
              <Input type="number" min={1} value={form.headcount} onChange={(e) => set('headcount', e.target.value)} />
            </div>
            <div className="grid gap-1.5">
              <Label>{tx('Closes on')}</Label>
              <Input type="date" value={form.closesOn} onChange={(e) => set('closesOn', e.target.value)} />
            </div>
          </div>
          <div className="grid gap-1.5">
            <Label>{tx('Description')}</Label>
            <Textarea rows={3} value={form.description} onChange={(e) => set('description', e.target.value)} />
          </div>
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={() => onOpenChange(false)}>
            {tx('Cancel')}
          </Button>
          <Button
            disabled={action.isPending || !form.title || Number(form.headcount) < 1}
            onClick={async () => {
              try {
                await action.mutateAsync({
                  method: 'post',
                  path: '/hr/recruitment/openings',
                  body: {
                    title: form.title,
                    departmentId: form.departmentId || undefined,
                    positionId: form.positionId || undefined,
                    headcount: Number(form.headcount),
                    closesOn: form.closesOn || undefined,
                    description: form.description || undefined,
                  },
                });
                notify.success(tx('Opening created'));
                onOpenChange(false);
                setForm({ title: '', departmentId: '', positionId: '', headcount: '1', closesOn: '', description: '' });
              } catch (err) {
                notify.error(apiErrorMessage(err, tx('Could not create the opening')));
              }
            }}
          >
            {tx('Create')}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

function ApplicantDialog({ open, onOpenChange }: { open: boolean; onOpenChange: (v: boolean) => void }) {
  const action = useRecruitmentAction();
  const { data: openings } = useJobOpenings({ status: 'open', take: 200 });
  const [form, setForm] = useState({ openingId: '', firstName: '', lastName: '', email: '', phone: '', source: '' });
  const set = (k: string, v: string) => setForm((f) => ({ ...f, [k]: v }));

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>{tx('New applicant')}</DialogTitle>
          <DialogDescription>{tx('Applicants can only be added to an open job opening.')}</DialogDescription>
        </DialogHeader>
        <div className="grid gap-3">
          <div className="grid gap-1.5">
            <Label>{tx('Opening')}</Label>
            <select className={SELECT} value={form.openingId} onChange={(e) => set('openingId', e.target.value)}>
              <option value="">{tx('Select…')}</option>
              {(openings?.rows ?? []).map((o) => (
                <option key={o.id} value={o.id}>
                  {o.code} — {o.title}
                </option>
              ))}
            </select>
          </div>
          <div className="grid grid-cols-2 gap-3">
            <div className="grid gap-1.5">
              <Label>{tx('First name')}</Label>
              <Input value={form.firstName} onChange={(e) => set('firstName', e.target.value)} />
            </div>
            <div className="grid gap-1.5">
              <Label>{tx('Last name')}</Label>
              <Input value={form.lastName} onChange={(e) => set('lastName', e.target.value)} />
            </div>
          </div>
          <div className="grid grid-cols-2 gap-3">
            <div className="grid gap-1.5">
              <Label>{tx('Email')}</Label>
              <Input type="email" value={form.email} onChange={(e) => set('email', e.target.value)} />
            </div>
            <div className="grid gap-1.5">
              <Label>{tx('Phone')}</Label>
              <Input value={form.phone} onChange={(e) => set('phone', e.target.value)} />
            </div>
          </div>
          <div className="grid gap-1.5">
            <Label>{tx('Source')}</Label>
            <Input value={form.source} onChange={(e) => set('source', e.target.value)} />
          </div>
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={() => onOpenChange(false)}>
            {tx('Cancel')}
          </Button>
          <Button
            disabled={action.isPending || !form.openingId || !form.firstName}
            onClick={async () => {
              try {
                await action.mutateAsync({
                  method: 'post',
                  path: '/hr/recruitment/applicants',
                  body: {
                    openingId: form.openingId,
                    firstName: form.firstName,
                    lastName: form.lastName || undefined,
                    email: form.email || undefined,
                    phone: form.phone || undefined,
                    source: form.source || undefined,
                  },
                });
                notify.success(tx('Applicant added'));
                onOpenChange(false);
                setForm({ openingId: '', firstName: '', lastName: '', email: '', phone: '', source: '' });
              } catch (err) {
                notify.error(apiErrorMessage(err, tx('Could not add the applicant')));
              }
            }}
          >
            {tx('Create')}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

function ApplicantDetail({ id, onClose }: { id: string | null; onClose: () => void }) {
  const { data: applicant } = useApplicant(id);
  const action = useRecruitmentAction();
  const [reason, setReason] = useState('');
  const [when, setWhen] = useState('');
  const [interviewer, setInterviewer] = useState('');
  const [hireDate, setHireDate] = useState('');

  const run = async (path: string, body: unknown, done: string) => {
    try {
      await action.mutateAsync({ method: 'post', path, body });
      notify.success(done);
      setReason('');
      setWhen('');
      setInterviewer('');
    } catch (err) {
      notify.error(apiErrorMessage(err, tx('The action failed')));
    }
  };
  const move = (a: Applicant, stage: string) =>
    run(`/hr/recruitment/applicants/${a.id}/stage`, { stage, reason: reason || undefined }, tx('Applicant updated'));

  return (
    <Dialog open={!!id} onOpenChange={(v) => !v && onClose()}>
      <DialogContent className="max-h-[85vh] overflow-y-auto">
        <DialogHeader>
          <DialogTitle>
            {applicant ? `${applicant.firstName} ${applicant.lastName ?? ''}` : tx('Applicant')}
          </DialogTitle>
          <DialogDescription>
            {applicant?.opening?.title ?? ''} {applicant?.email ? `· ${applicant.email}` : ''}{' '}
            {applicant?.phone ? `· ${applicant.phone}` : ''}
          </DialogDescription>
        </DialogHeader>
        {applicant ? (
          <div className="grid gap-4 text-sm">
            <div className="flex items-center gap-2">
              <Badge className={TONE[applicant.stage] ?? 'bg-muted text-muted-foreground'}>{label(applicant.stage)}</Badge>
              {applicant.stageReason ? <span className="text-muted-foreground">{applicant.stageReason}</span> : null}
              {applicant.employee ? (
                <Link className="underline" to={`/hr/employees/${applicant.employee.id}`}>
                  {applicant.employee.employeeCode}
                </Link>
              ) : null}
            </div>
            {NEXT_STAGE[applicant.stage] ? (
              <div className="grid gap-2">
                <Label>{tx('Move to')}</Label>
                <Input
                  placeholder={tx('Reason (required to reject or withdraw)')}
                  value={reason}
                  onChange={(e) => setReason(e.target.value)}
                />
                <div className="flex flex-wrap gap-2">
                  {NEXT_STAGE[applicant.stage].map((s) => (
                    <Button key={s} size="sm" variant="outline" disabled={action.isPending} onClick={() => move(applicant, s)}>
                      {label(s)}
                    </Button>
                  ))}
                </div>
              </div>
            ) : null}
            {applicant.stage === 'offer' ? (
              <div className="grid gap-2 rounded-md border p-3">
                <Label>{tx('Hire date')}</Label>
                <Input type="date" value={hireDate} onChange={(e) => setHireDate(e.target.value)} />
                <p className="text-xs text-muted-foreground">
                  {tx('Hiring creates the employee and copies the onboarding checklist. Set the pay on the employee afterwards.')}
                </p>
                <Button
                  size="sm"
                  disabled={action.isPending}
                  onClick={() =>
                    run(`/hr/recruitment/applicants/${applicant.id}/hire`, { hireDate: hireDate || undefined }, tx('Applicant hired'))
                  }
                >
                  {tx('Hire')}
                </Button>
              </div>
            ) : null}
            <div className="grid gap-2">
              <Label>{tx('Interviews')}</Label>
              {applicant.interviews?.length ? (
                <ul className="space-y-1">
                  {applicant.interviews.map((i) => (
                    <li key={i.id} className="flex flex-wrap items-center justify-between gap-2 rounded border px-2 py-1">
                      <span>
                        {new Date(i.scheduledAt).toLocaleString()} {i.interviewer ? `· ${i.interviewer}` : ''}
                      </span>
                      <select
                        className="rounded border bg-background px-2 py-1 text-xs"
                        value={i.outcome ?? ''}
                        onChange={async (e) => {
                          try {
                            await action.mutateAsync({
                              method: 'patch',
                              path: `/hr/recruitment/interviews/${i.id}`,
                              body: { status: 'completed', outcome: e.target.value },
                            });
                          } catch (err) {
                            notify.error(apiErrorMessage(err, tx('The action failed')));
                          }
                        }}
                      >
                        <option value="">{tx('Outcome')}</option>
                        <option value="pass">pass</option>
                        <option value="hold">hold</option>
                        <option value="fail">fail</option>
                      </select>
                    </li>
                  ))}
                </ul>
              ) : (
                <p className="text-muted-foreground">{tx('No interviews yet.')}</p>
              )}
              {NEXT_STAGE[applicant.stage] ? (
                <div className="flex flex-wrap gap-2">
                  <Input className="max-w-[14rem]" type="datetime-local" value={when} onChange={(e) => setWhen(e.target.value)} />
                  <Input
                    className="max-w-[12rem]"
                    placeholder={tx('Interviewer')}
                    value={interviewer}
                    onChange={(e) => setInterviewer(e.target.value)}
                  />
                  <Button
                    size="sm"
                    variant="outline"
                    disabled={action.isPending || !when}
                    onClick={() =>
                      run(
                        `/hr/recruitment/applicants/${applicant.id}/interviews`,
                        { scheduledAt: new Date(when).toISOString(), interviewer: interviewer || undefined },
                        tx('Interview scheduled'),
                      )
                    }
                  >
                    {tx('Schedule')}
                  </Button>
                </div>
              ) : null}
            </div>
          </div>
        ) : (
          <Skeleton className="h-32 w-full" />
        )}
      </DialogContent>
    </Dialog>
  );
}

import { useState } from 'react';
import { Link } from 'react-router-dom';
import { Plus, Trash2 } from 'lucide-react';
import {
  useOnboardingTasks,
  useOnboardingTemplates,
  useRecruitmentAction,
} from '@/features/hr/recruitment-api';
import { Card, CardContent } from '@/components/ui/card';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Skeleton } from '@/components/ui/skeleton';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs';
import { notify } from '@/lib/notify';
import { apiErrorMessage } from '@/lib/api-error';
import { tx } from '@/lib/i18n/tx';

const SELECT = 'h-9 rounded-md border bg-background px-3 text-sm';

/** The onboarding checklist of new hires and the templates it is copied from. */
export function HrOnboardingPage() {
  const [tab, setTab] = useState('tasks');
  return (
    <div className="space-y-4 p-6">
      <div>
        <h1 className="text-xl font-semibold">{tx('Onboarding')}</h1>
        <p className="text-sm text-muted-foreground">
          {tx('Checklists for new hires, copied from the templates when someone is hired.')}
        </p>
      </div>
      <Tabs value={tab} onValueChange={setTab}>
        <TabsList>
          <TabsTrigger value="tasks">{tx('Tasks')}</TabsTrigger>
          <TabsTrigger value="templates">{tx('Templates')}</TabsTrigger>
        </TabsList>
        <TabsContent value="tasks" className="mt-4">
          <TasksTab />
        </TabsContent>
        <TabsContent value="templates" className="mt-4">
          <TemplatesTab />
        </TabsContent>
      </Tabs>
    </div>
  );
}

function TasksTab() {
  const [status, setStatus] = useState('pending');
  const { data, isLoading, isError } = useOnboardingTasks({ status, take: 200 });
  const action = useRecruitmentAction();

  const setTask = async (id: string, next: string) => {
    try {
      await action.mutateAsync({ method: 'patch', path: `/hr/onboarding/tasks/${id}`, body: { status: next } });
      notify.success(tx('Task updated'));
    } catch (err) {
      notify.error(apiErrorMessage(err, tx('Could not update the task')));
    }
  };

  return (
    <Card>
      <CardContent className="space-y-3 pt-6">
        <select className={SELECT} value={status} onChange={(e) => setStatus(e.target.value)}>
          <option value="">{tx('All statuses')}</option>
          <option value="pending">pending</option>
          <option value="done">done</option>
          <option value="skipped">skipped</option>
        </select>
        {isLoading ? (
          <Skeleton className="h-40 w-full" />
        ) : isError ? (
          <p className="py-8 text-center text-sm text-destructive">{tx('Could not load the tasks')}</p>
        ) : data?.rows.length ? (
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="border-b text-left text-xs uppercase text-muted-foreground">
                  <th className="py-2">{tx('Employee')}</th>
                  <th className="py-2">{tx('Task')}</th>
                  <th className="py-2">{tx('Responsible')}</th>
                  <th className="py-2">{tx('Due')}</th>
                  <th className="py-2">{tx('Status')}</th>
                  <th className="py-2" />
                </tr>
              </thead>
              <tbody>
                {data.rows.map((t) => (
                  <tr key={t.id} className="border-b last:border-0">
                    <td className="py-2">
                      <Link className="hover:underline" to={`/hr/employees/${t.employeeId}`}>
                        {t.employee?.firstName} {t.employee?.lastName ?? ''}
                      </Link>
                    </td>
                    <td className="py-2">
                      {t.title}
                      {t.isRequired ? <span className="ml-1 text-rose-600">*</span> : null}
                    </td>
                    <td className="py-2">{t.responsible ?? '—'}</td>
                    <td className="py-2">{t.dueOn ?? '—'}</td>
                    <td className="py-2">
                      <Badge className={t.status === 'done' ? 'bg-emerald-100 text-emerald-800' : 'bg-muted text-muted-foreground'}>
                        {t.status}
                      </Badge>
                    </td>
                    <td className="py-2 text-right">
                      {t.status === 'pending' ? (
                        <div className="flex justify-end gap-1">
                          <Button size="sm" variant="outline" onClick={() => setTask(t.id, 'done')}>
                            {tx('Done')}
                          </Button>
                          {t.isRequired ? null : (
                            <Button size="sm" variant="ghost" onClick={() => setTask(t.id, 'skipped')}>
                              {tx('Skip')}
                            </Button>
                          )}
                        </div>
                      ) : (
                        <Button size="sm" variant="ghost" onClick={() => setTask(t.id, 'pending')}>
                          {tx('Reopen')}
                        </Button>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        ) : (
          <p className="py-8 text-center text-sm text-muted-foreground">{tx('No onboarding tasks here.')}</p>
        )}
      </CardContent>
    </Card>
  );
}

function TemplatesTab() {
  const { data, isLoading, isError } = useOnboardingTemplates();
  const action = useRecruitmentAction();
  const [title, setTitle] = useState('');
  const [dueDays, setDueDays] = useState('0');
  const [responsible, setResponsible] = useState('');

  const add = async () => {
    try {
      await action.mutateAsync({
        method: 'post',
        path: '/hr/onboarding/templates',
        body: { title, dueDays: Number(dueDays), responsible: responsible || undefined },
      });
      notify.success(tx('Template added'));
      setTitle('');
      setDueDays('0');
      setResponsible('');
    } catch (err) {
      notify.error(apiErrorMessage(err, tx('Could not add the template')));
    }
  };

  const remove = async (id: string) => {
    try {
      await action.mutateAsync({ method: 'delete', path: `/hr/onboarding/templates/${id}` });
    } catch (err) {
      notify.error(apiErrorMessage(err, tx('The action failed')));
    }
  };

  return (
    <Card>
      <CardContent className="space-y-4 pt-6">
        <div className="flex flex-wrap items-end gap-2">
          <div className="grid gap-1.5">
            <Label>{tx('Task')}</Label>
            <Input className="w-64" value={title} onChange={(e) => setTitle(e.target.value)} />
          </div>
          <div className="grid gap-1.5">
            <Label>{tx('Due after (days)')}</Label>
            <Input className="w-28" type="number" min={0} value={dueDays} onChange={(e) => setDueDays(e.target.value)} />
          </div>
          <div className="grid gap-1.5">
            <Label>{tx('Responsible')}</Label>
            <Input className="w-48" value={responsible} onChange={(e) => setResponsible(e.target.value)} />
          </div>
          <Button size="sm" disabled={action.isPending || !title} onClick={add}>
            <Plus className="mr-1.5 h-4 w-4" />
            {tx('Add')}
          </Button>
        </div>
        {isLoading ? (
          <Skeleton className="h-32 w-full" />
        ) : isError ? (
          <p className="py-8 text-center text-sm text-destructive">{tx('Could not load the templates')}</p>
        ) : data?.rows.length ? (
          <ul className="divide-y text-sm">
            {data.rows.map((t) => (
              <li key={t.id} className="flex items-center justify-between gap-3 py-2">
                <span>
                  {t.title} <span className="text-muted-foreground">· +{t.dueDays} {tx('days')}</span>
                  {t.responsible ? <span className="text-muted-foreground"> · {t.responsible}</span> : null}
                </span>
                <Button size="sm" variant="ghost" onClick={() => remove(t.id)} aria-label={tx('Delete')}>
                  <Trash2 className="h-4 w-4" />
                </Button>
              </li>
            ))}
          </ul>
        ) : (
          <p className="py-8 text-center text-sm text-muted-foreground">{tx('No onboarding templates yet.')}</p>
        )}
      </CardContent>
    </Card>
  );
}

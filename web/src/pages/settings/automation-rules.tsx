import { useState } from 'react';
import { PERMISSIONS } from '@erp/shared';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import {
  type AutomationRule,
  useAutomationCatalog,
  useAutomationRules,
  useAutomationRuns,
  useDeleteAutomationRule,
  useSaveAutomationRule,
  useTestAutomationRule,
} from '@/features/customization/api';
import { useAuthStore } from '@/stores/auth.store';
import { tx } from '@/lib/i18n/tx';

const SELECT = 'h-9 rounded-md border border-input bg-background px-3 text-sm';

type Condition = { field: string; op: string; value?: unknown };
type Action = Record<string, unknown> & { type: string };

const ACTION_FIELDS: Record<string, { key: string; label: string; area?: boolean }[]> = {
  notify: [{ key: 'roleId', label: 'Role id' }, { key: 'title', label: 'Title' }, { key: 'body', label: 'Message', area: true }],
  email: [{ key: 'to', label: 'To (e.g. {{partner.email}})' }, { key: 'subject', label: 'Subject' }, { key: 'body', label: 'Body', area: true }],
  webhook: [{ key: 'url', label: 'HTTPS URL' }],
  set_custom_field: [{ key: 'key', label: 'Custom field key' }, { key: 'value', label: 'Value' }],
  create_task: [{ key: 'title', label: 'Title' }, { key: 'description', label: 'Description', area: true },
    { key: 'assigneeId', label: 'Assignee user id' }, { key: 'dueInDays', label: 'Due in days' }],
};

/** Automation rules: when an event happens and the conditions hold, run the actions (ADR-S029 §4). */
export default function AutomationRulesPage() {
  const canManage = useAuthStore((s) => s.hasPermission)(PERMISSIONS.automation.manage);
  const { data: rules } = useAutomationRules();
  const { data: catalog } = useAutomationCatalog();
  const [editing, setEditing] = useState<AutomationRule | 'new' | null>(null);
  const [runsOf, setRunsOf] = useState<string>();

  const label = (event: string) => catalog?.triggers.find((t) => t.event === event)?.label ?? event;

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div>
          <h1 className="text-2xl font-semibold">{tx('Automation rules')}</h1>
          <p className="text-sm text-muted-foreground">{tx('When something happens and the conditions hold, run the actions.')}</p>
        </div>
        {canManage && <Button onClick={() => setEditing('new')}>{tx('New rule')}</Button>}
      </div>
      {editing && catalog && (
        <RuleEditor rule={editing === 'new' ? null : editing} catalog={catalog} onDone={() => setEditing(null)} />
      )}
      <Card>
        <CardContent className="pt-6">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>{tx('Rule')}</TableHead>
                <TableHead>{tx('When')}</TableHead>
                <TableHead className="text-right">{tx('Runs')}</TableHead>
                <TableHead>{tx('Status')}</TableHead>
                <TableHead />
              </TableRow>
            </TableHeader>
            <TableBody>
              {(rules ?? []).map((r) => (
                <TableRow key={r.id}>
                  <TableCell>{r.name}</TableCell>
                  <TableCell>{tx(label(r.trigger))}</TableCell>
                  <TableCell className="text-right">{r.runCount}</TableCell>
                  <TableCell><Badge variant={r.isActive ? 'default' : 'secondary'}>{r.isActive ? tx('Active') : tx('Inactive')}</Badge></TableCell>
                  <TableCell className="space-x-1 text-right">
                    <Button size="sm" variant="ghost" onClick={() => setRunsOf(runsOf === r.id ? undefined : r.id)}>{tx('Runs')}</Button>
                    {canManage && <Button size="sm" variant="ghost" onClick={() => setEditing(r)}>{tx('Edit')}</Button>}
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </CardContent>
      </Card>
      {runsOf && <RunsCard id={runsOf} />}
    </div>
  );
}

function RuleEditor({ rule, catalog, onDone }: {
  rule: AutomationRule | null;
  catalog: { triggers: { event: string; label: string; entity: string }[]; operators: string[]; actions: string[] };
  onDone: () => void;
}) {
  const save = useSaveAutomationRule();
  const remove = useDeleteAutomationRule();
  const test = useTestAutomationRule();
  const [name, setName] = useState(rule?.name ?? '');
  const [trigger, setTrigger] = useState(rule?.trigger ?? catalog.triggers[0]?.event ?? '');
  const [active, setActive] = useState(rule?.isActive ?? true);
  const [conditions, setConditions] = useState<Condition[]>(rule?.conditions ?? []);
  const [actions, setActions] = useState<Action[]>((rule?.actions as Action[]) ?? [{ type: 'notify' }]);
  const [testRecord, setTestRecord] = useState('');

  const setCondition = (i: number, patch: Partial<Condition>) =>
    setConditions((cs) => cs.map((c, j) => (j === i ? { ...c, ...patch } : c)));
  const setAction = (i: number, patch: Record<string, unknown>) =>
    setActions((as) => as.map((a, j) => (j === i ? { ...a, ...patch } : a)));

  const body = () => ({
    name, trigger, isActive: active,
    conditions: conditions.map((c) => ({ ...c, value: c.op === 'in' && typeof c.value === 'string'
      ? c.value.split(',').map((v) => v.trim()) : c.value })),
    actions: actions.map((a) => {
      const out: Record<string, unknown> = {};
      Object.entries(a).forEach(([k, v]) => { if (v !== '' && v !== undefined) out[k] = k === 'dueInDays' ? Number(v) : v; });
      return out;
    }),
  });

  return (
    <Card>
      <CardHeader>
        <CardTitle className="text-base">{rule ? tx('Edit rule') : tx('New rule')}</CardTitle>
      </CardHeader>
      <CardContent className="space-y-4">
        <div className="grid gap-3 md:grid-cols-3">
          <div className="space-y-1">
            <Label>{tx('Name')}</Label>
            <Input value={name} onChange={(e) => setName(e.target.value)} />
          </div>
          <div className="space-y-1">
            <Label>{tx('When')}</Label>
            <select className={`${SELECT} w-full`} value={trigger} onChange={(e) => setTrigger(e.target.value)}>
              {catalog.triggers.map((t) => <option key={t.event} value={t.event}>{tx(t.label)}</option>)}
            </select>
          </div>
          <label className="flex items-end gap-2 pb-2 text-sm">
            <input type="checkbox" checked={active} onChange={(e) => setActive(e.target.checked)} />{tx('Active')}
          </label>
        </div>
        <div className="space-y-2">
          <Label>{tx('Conditions (all must hold)')}</Label>
          {conditions.map((c, i) => (
            <div key={i} className="flex flex-wrap gap-2">
              <Input className="w-64" value={c.field} placeholder="document.totalRaw" onChange={(e) => setCondition(i, { field: e.target.value })} />
              <select className={SELECT} value={c.op} onChange={(e) => setCondition(i, { op: e.target.value })}>
                {catalog.operators.map((o) => <option key={o} value={o}>{o}</option>)}
              </select>
              {c.op !== 'empty' && c.op !== 'not_empty' && (
                <Input className="w-48" value={c.value === undefined ? '' : String(c.value)}
                  onChange={(e) => setCondition(i, { value: e.target.value })} />
              )}
              <Button size="sm" variant="ghost" onClick={() => setConditions((cs) => cs.filter((_, j) => j !== i))}>✕</Button>
            </div>
          ))}
          <Button size="sm" variant="outline" onClick={() => setConditions((cs) => [...cs, { field: '', op: 'eq', value: '' }])}>
            + {tx('Condition')}
          </Button>
        </div>
        <div className="space-y-2">
          <Label>{tx('Actions (in order)')}</Label>
          {actions.map((a, i) => (
            <div key={i} className="space-y-2 rounded border p-3">
              <div className="flex items-center gap-2">
                <select className={SELECT} value={a.type} onChange={(e) => setActions((as) => as.map((x, j) => (j === i ? { type: e.target.value } : x)))}>
                  {catalog.actions.map((t) => <option key={t} value={t}>{t}</option>)}
                </select>
                <Button size="sm" variant="ghost" onClick={() => setActions((as) => as.filter((_, j) => j !== i))}>✕</Button>
              </div>
              <div className="grid gap-2 md:grid-cols-2">
                {(ACTION_FIELDS[a.type] ?? []).map((f) => (
                  <div key={f.key} className={`space-y-1 ${f.area ? 'md:col-span-2' : ''}`}>
                    <Label className="text-xs">{tx(f.label)}</Label>
                    {f.area ? (
                      <textarea className="min-h-16 w-full rounded-md border border-input bg-background p-2 text-sm"
                        value={String(a[f.key] ?? '')} onChange={(e) => setAction(i, { [f.key]: e.target.value })} />
                    ) : (
                      <Input value={String(a[f.key] ?? '')} onChange={(e) => setAction(i, { [f.key]: e.target.value })} />
                    )}
                  </div>
                ))}
              </div>
            </div>
          ))}
          <Button size="sm" variant="outline" onClick={() => setActions((as) => [...as, { type: 'notify' }])}>+ {tx('Action')}</Button>
        </div>
        <div className="flex flex-wrap gap-2">
          <Button disabled={save.isPending} onClick={() => save.mutate({ id: rule?.id, ...body() } as never, { onSuccess: onDone })}>{tx('Save')}</Button>
          <Button variant="outline" onClick={onDone}>{tx('Cancel')}</Button>
          {rule && <Button variant="outline" onClick={() => remove.mutate(rule.id, { onSuccess: onDone })}>{tx('Delete')}</Button>}
        </div>
        {rule && (
          <div className="space-y-2 border-t pt-3">
            <Label>{tx('Test against a record')}</Label>
            <div className="flex gap-2">
              <Input className="w-80" value={testRecord} onChange={(e) => setTestRecord(e.target.value.trim())} placeholder={tx('Record id')} />
              <Button variant="outline" disabled={!testRecord || test.isPending} onClick={() => test.mutate({ id: rule.id, recordId: testRecord })}>
                {tx('Test')}
              </Button>
            </div>
            {test.data && (
              <div className="space-y-1 text-sm">
                <div>{test.data.matched ? tx('The rule would run.') : tx('The conditions do not hold; nothing would run.')}</div>
                {test.data.conditions.map((c, i) => (
                  <div key={i} className={c.holds ? 'text-emerald-600' : 'text-destructive'}>
                    {c.field} {c.op} → {String(c.actual ?? '')}
                  </div>
                ))}
                <pre className="max-h-48 overflow-auto rounded bg-muted p-2 text-xs">{JSON.stringify(test.data.actions, null, 2)}</pre>
              </div>
            )}
          </div>
        )}
      </CardContent>
    </Card>
  );
}

function RunsCard({ id }: { id: string }) {
  const { data } = useAutomationRuns(id);
  return (
    <Card>
      <CardHeader>
        <CardTitle className="text-base">{tx('Recent runs')}</CardTitle>
      </CardHeader>
      <CardContent>
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>{tx('When')}</TableHead>
              <TableHead>{tx('Record')}</TableHead>
              <TableHead>{tx('Status')}</TableHead>
              <TableHead>{tx('Results')}</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {(data?.data ?? []).map((r) => (
              <TableRow key={r.id}>
                <TableCell>{String(r.createdAt).replace('T', ' ').slice(0, 16)}</TableCell>
                <TableCell className="font-mono text-xs">{r.recordId}</TableCell>
                <TableCell><Badge variant={r.status === 'failed' ? 'destructive' : 'secondary'}>{r.status}</Badge></TableCell>
                <TableCell className="text-xs">
                  {r.results.map((x, i) => <div key={i} className={x.ok ? '' : 'text-destructive'}>{x.type}: {x.message}</div>)}
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </CardContent>
    </Card>
  );
}

import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { Link } from 'react-router-dom';
import { Plus, Trash2 } from 'lucide-react';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card';
import { Dialog, DialogContent, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Skeleton } from '@/components/ui/skeleton';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs';
import { isHeldForApproval, useBranchChoices, useDepartmentChoices, type Option } from '@/features/approval-controls/api';
import { api } from '@/lib/api';
import { ENTITY_TYPE_LABELS, entityTypeLabel } from '@/lib/approval-entity-types';
import { dateTime } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';
import { notify } from '@/lib/notify';
import { useAuthStore } from '@/stores/auth.store';

interface ApprovalLimit {
  id: string;
  name: string;
  entityType: string | null;
  userId: string | null;
  permission: string | null;
  branchId: string | null;
  departmentId: string | null;
  maxAmount: number | string;
  isActive: boolean;
}

interface SodRule {
  pairKey: string;
  name: string;
  maker: string;
  checker: string;
  alwaysOn: boolean;
  isActive: boolean;
  notes: string | null;
}

interface ConfigChange {
  id: string;
  changeType: string;
  targetId: string | null;
  status: string;
  approvalRequestId: string;
  createdAt: string;
  decidedAt: string | null;
}

const selectClass = 'h-9 w-full rounded-md border border-input bg-transparent px-3 py-1 text-sm';

const errorText = (e: any) => e?.response?.data?.message ?? tx('Failed');

function savedOrHeld(body: unknown) {
  notify.success(isHeldForApproval(body) ? tx('Held for approval') : tx('Saved'));
}

function OptionSelect({ value, onChange, options, anyLabel }: {
  value: string;
  onChange: (v: string) => void;
  options: Option[];
  anyLabel: string;
}) {
  return (
    <select className={selectClass} value={value} onChange={(e) => onChange(e.target.value)}>
      <option value="">{anyLabel}</option>
      {options.map((o) => <option key={o.id} value={o.id}>{o.name}</option>)}
    </select>
  );
}

const emptyLimit = {
  name: '',
  entityType: '',
  subject: 'permission' as 'permission' | 'user',
  subjectValue: '',
  branchId: '',
  departmentId: '',
  maxAmount: '',
};

function LimitsTab({ canManage }: { canManage: boolean }) {
  const qc = useQueryClient();
  const [form, setForm] = useState<typeof emptyLimit | null>(null);
  const branches = useBranchChoices();
  const departments = useDepartmentChoices();
  const list = useQuery<ApprovalLimit[]>({
    queryKey: ['approval-limits'],
    queryFn: async () => (await api.get('/approval-controls/limits')).data,
  });
  const name = (options: Option[] | undefined, id: string | null) =>
    id ? options?.find((o) => o.id === id)?.name ?? id : tx('All');

  const save = useMutation({
    mutationFn: async () => {
      const f = form!;
      if (!f.name.trim()) throw new Error(tx('Enter a name'));
      if (!f.subjectValue.trim()) throw new Error(tx('Enter a user or a permission'));
      if (f.maxAmount === '' || Number(f.maxAmount) < 0) throw new Error(tx('Enter a limit of zero or more'));
      return (await api.post('/approval-controls/limits', {
        name: f.name,
        entityType: f.entityType || null,
        userId: f.subject === 'user' ? f.subjectValue : null,
        permission: f.subject === 'permission' ? f.subjectValue : null,
        branchId: f.branchId || null,
        departmentId: f.departmentId || null,
        maxAmount: Number(f.maxAmount),
      })).data;
    },
    onSuccess: (body) => {
      savedOrHeld(body);
      setForm(null);
      qc.invalidateQueries({ queryKey: ['approval-limits'] });
      qc.invalidateQueries({ queryKey: ['config-changes'] });
    },
    onError: (e: any) => notify.error(e?.response?.data?.message ?? e.message),
  });

  const remove = useMutation({
    mutationFn: async (id: string) => (await api.delete(`/approval-controls/limits/${id}`)).data,
    onSuccess: (body) => {
      savedOrHeld(body);
      qc.invalidateQueries({ queryKey: ['approval-limits'] });
      qc.invalidateQueries({ queryKey: ['config-changes'] });
    },
    onError: (e) => notify.error(errorText(e)),
  });

  return (
    <div className="space-y-3">
      <div className="flex items-center justify-between gap-2">
        <p className="text-sm text-muted-foreground">
          {tx('The most someone may approve. A step above their limit stays open for someone with a higher one. Without a limit there is no cap.')}
        </p>
        {canManage && (
          <Button onClick={() => setForm({ ...emptyLimit })}><Plus className="mr-1 h-4 w-4" />{tx('Add limit')}</Button>
        )}
      </div>
      {list.isLoading && <Skeleton className="h-32 w-full" />}
      {list.isError && <p className="text-sm text-destructive">{errorText(list.error)}</p>}
      {list.data?.length === 0 && (
        <Card><CardContent className="p-8 text-center text-sm text-muted-foreground">{tx('No approval limits yet.')}</CardContent></Card>
      )}
      <div className="grid gap-3 md:grid-cols-2">
        {list.data?.map((l) => (
          <Card key={l.id}>
            <CardContent className="flex items-start justify-between gap-2 p-4">
              <div className="min-w-0 space-y-1 text-sm">
                <p className="font-semibold">{l.name}</p>
                <p className="text-muted-foreground">
                  {tx('Up to')} <span className="font-medium text-foreground">{Number(l.maxAmount).toLocaleString()}</span> · {l.entityType ? entityTypeLabel(l.entityType) : tx('All documents')}
                </p>
                <p className="truncate font-mono text-xs text-muted-foreground">{l.permission ?? l.userId}</p>
                <p className="text-xs text-muted-foreground">
                  {tx('Branch')}: {name(branches.data, l.branchId)} · {tx('Department')}: {name(departments.data, l.departmentId)}
                </p>
              </div>
              {canManage && (
                <Button
                  variant="ghost"
                  size="icon"
                  disabled={remove.isPending}
                  onClick={() => { if (confirm(tx('Delete this limit?'))) remove.mutate(l.id); }}
                >
                  <Trash2 className="h-4 w-4 text-red-500" />
                </Button>
              )}
            </CardContent>
          </Card>
        ))}
      </div>

      <Dialog open={form !== null} onOpenChange={(open) => { if (!open) setForm(null); }}>
        <DialogContent>
          <DialogHeader><DialogTitle>{tx('Add limit')}</DialogTitle></DialogHeader>
          {form && (
            <div className="grid gap-3 sm:grid-cols-2">
              <div className="space-y-1.5 sm:col-span-2">
                <Label>{tx('Name *')}</Label>
                <Input value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} />
              </div>
              <div className="space-y-1.5">
                <Label>{tx('Applies to')}</Label>
                <select
                  className={selectClass}
                  value={form.subject}
                  onChange={(e) => setForm({ ...form, subject: e.target.value as 'permission' | 'user', subjectValue: '' })}
                >
                  <option value="permission">{tx('Permission')}</option>
                  <option value="user">{tx('User id')}</option>
                </select>
              </div>
              <div className="space-y-1.5">
                <Label>{form.subject === 'permission' ? tx('Permission *') : tx('User id *')}</Label>
                <Input
                  placeholder={form.subject === 'permission' ? 'purchase_order:approve' : ''}
                  value={form.subjectValue}
                  onChange={(e) => setForm({ ...form, subjectValue: e.target.value })}
                />
              </div>
              <div className="space-y-1.5">
                <Label>{tx('Document type')}</Label>
                <select className={selectClass} value={form.entityType} onChange={(e) => setForm({ ...form, entityType: e.target.value })}>
                  <option value="">{tx('All documents')}</option>
                  {Object.entries(ENTITY_TYPE_LABELS).map(([key, label]) => <option key={key} value={key}>{label}</option>)}
                </select>
              </div>
              <div className="space-y-1.5">
                <Label>{tx('Maximum amount *')}</Label>
                <Input type="number" min={0} value={form.maxAmount} onChange={(e) => setForm({ ...form, maxAmount: e.target.value })} />
              </div>
              <div className="space-y-1.5">
                <Label>{tx('Branch')}</Label>
                <OptionSelect value={form.branchId} onChange={(branchId) => setForm({ ...form, branchId })} options={branches.data ?? []} anyLabel={tx('All')} />
              </div>
              <div className="space-y-1.5">
                <Label>{tx('Department')}</Label>
                <OptionSelect value={form.departmentId} onChange={(departmentId) => setForm({ ...form, departmentId })} options={departments.data ?? []} anyLabel={tx('All')} />
              </div>
            </div>
          )}
          <DialogFooter>
            <Button variant="outline" onClick={() => setForm(null)}>{tx('Cancel')}</Button>
            <Button disabled={save.isPending} onClick={() => save.mutate()}>{tx('Save')}</Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  );
}

function SodTab({ canManage }: { canManage: boolean }) {
  const qc = useQueryClient();
  const rules = useQuery<SodRule[]>({
    queryKey: ['sod-rules'],
    queryFn: async () => (await api.get('/approval-controls/sod-rules')).data,
  });
  const toggle = useMutation({
    mutationFn: async (r: SodRule) =>
      (await api.put(`/approval-controls/sod-rules/${r.pairKey}`, { isActive: !r.isActive, notes: r.notes })).data,
    onSuccess: (body) => {
      savedOrHeld(body);
      qc.invalidateQueries({ queryKey: ['sod-rules'] });
      qc.invalidateQueries({ queryKey: ['config-changes'] });
    },
    onError: (e) => notify.error(errorText(e)),
  });

  return (
    <div className="space-y-3">
      <p className="text-sm text-muted-foreground">
        {tx('While a pair is on, the person who made a document cannot also clear it, unless someone else approved it first. Every check appears in the audit log as SodCheck.')}
      </p>
      {rules.isLoading && <Skeleton className="h-32 w-full" />}
      {rules.isError && <p className="text-sm text-destructive">{errorText(rules.error)}</p>}
      <div className="grid gap-3 md:grid-cols-2">
        {rules.data?.map((r) => (
          <Card key={r.pairKey}>
            <CardHeader>
              <div className="flex items-start justify-between gap-2">
                <CardTitle className="text-base">{r.name}</CardTitle>
                <Badge variant={r.isActive ? 'default' : 'outline'}>{r.isActive ? tx('On') : tx('Off')}</Badge>
              </div>
              <CardDescription>{r.maker} / {r.checker}</CardDescription>
            </CardHeader>
            <CardContent>
              {r.alwaysOn ? (
                <p className="text-xs text-muted-foreground">{tx('Always enforced')}</p>
              ) : canManage && (
                <Button variant="outline" size="sm" disabled={toggle.isPending} onClick={() => toggle.mutate(r)}>
                  {r.isActive ? tx('Switch off') : tx('Switch on')}
                </Button>
              )}
            </CardContent>
          </Card>
        ))}
      </div>
    </div>
  );
}

function ChangesTab() {
  const changes = useQuery<ConfigChange[]>({
    queryKey: ['config-changes'],
    queryFn: async () => (await api.get('/approval-controls/config-changes')).data,
  });

  return (
    <div className="space-y-3">
      <p className="text-sm text-muted-foreground">
        {tx('With an active workflow for Configuration Changes, changes to workflows, limits, separation of duties and document numbering wait here until someone else approves them in the Approvals inbox.')}
      </p>
      {changes.isLoading && <Skeleton className="h-32 w-full" />}
      {changes.isError && <p className="text-sm text-destructive">{errorText(changes.error)}</p>}
      {changes.data?.length === 0 && (
        <Card><CardContent className="p-8 text-center text-sm text-muted-foreground">{tx('No held changes.')}</CardContent></Card>
      )}
      <div className="grid gap-3 md:grid-cols-2">
        {changes.data?.map((c) => (
          <Card key={c.id}>
            <CardContent className="space-y-1 p-4 text-sm">
              <div className="flex items-center justify-between gap-2">
                <span className="truncate font-mono text-xs">{c.changeType}</span>
                <Badge variant={c.status === 'pending' ? 'secondary' : 'outline'}>{c.status}</Badge>
              </div>
              <p className="text-xs text-muted-foreground">{dateTime(c.createdAt)}</p>
              {c.status === 'pending' && (
                <Link className="text-xs text-primary underline" to="/approvals">{tx('Open approvals')}</Link>
              )}
            </CardContent>
          </Card>
        ))}
      </div>
    </div>
  );
}

export function ApprovalControlsPage() {
  const hasPermission = useAuthStore((s) => s.hasPermission);
  const canManage = hasPermission('approvals:manage');
  return (
    <div className="space-y-4">
      <div>
        <h1 className="text-2xl font-semibold">{tx('Approval Controls')}</h1>
        <p className="text-sm text-muted-foreground">{tx('Transaction limits, separation of duties and controlled configuration changes.')}</p>
      </div>
      <Tabs defaultValue="limits">
        <TabsList>
          <TabsTrigger value="limits">{tx('Limits')}</TabsTrigger>
          <TabsTrigger value="sod">{tx('Separation of duties')}</TabsTrigger>
          <TabsTrigger value="changes">{tx('Held changes')}</TabsTrigger>
        </TabsList>
        <TabsContent value="limits"><LimitsTab canManage={canManage} /></TabsContent>
        <TabsContent value="sod"><SodTab canManage={canManage} /></TabsContent>
        <TabsContent value="changes"><ChangesTab /></TabsContent>
      </Tabs>
    </div>
  );
}

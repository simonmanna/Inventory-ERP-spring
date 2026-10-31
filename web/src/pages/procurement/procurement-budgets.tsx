import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { Plus } from 'lucide-react';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card';
import { Dialog, DialogContent, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Skeleton } from '@/components/ui/skeleton';
import { useBranchChoices, useDepartmentChoices } from '@/features/approval-controls/api';
import { api } from '@/lib/api';
import { localDay } from '@/features/finance/api';
import { tx } from '@/lib/i18n/tx';
import { notify } from '@/lib/notify';
import { useAuthStore } from '@/stores/auth.store';

interface Budget {
  id: string;
  name: string;
  startDate: string;
  endDate: string;
  branchId: string | null;
  departmentId: string | null;
  categoryId: string | null;
  amount: number | string;
  committed: number | string;
  reserved: number | string;
  remaining: number | string;
  percent: number | string;
  warnPercent: number | string;
  controlMode: 'warn' | 'block';
  status: 'draft' | 'active' | 'closed';
}

interface BudgetPage {
  data: Budget[];
  meta: { page: number; pageSize: number; total: number; totalPages: number };
}

const selectClass = 'h-9 w-full rounded-md border border-input bg-transparent px-3 py-1 text-sm';
const amount = (v: number | string) => Number(v).toLocaleString();
const errorText = (e: any) => e?.response?.data?.message ?? e?.message ?? tx('Failed');

const emptyForm = () => ({
  name: '',
  startDate: localDay(),
  endDate: localDay(),
  branchId: '',
  departmentId: '',
  amount: '',
  warnPercent: '90',
  controlMode: 'block',
  notes: '',
});

export function ProcurementBudgetsPage() {
  const qc = useQueryClient();
  const canManage = useAuthStore((s) => s.hasPermission)('procurement_budget:manage');
  const [status, setStatus] = useState('');
  const [page, setPage] = useState(1);
  const [form, setForm] = useState<ReturnType<typeof emptyForm> | null>(null);
  const [revising, setRevising] = useState<Budget | null>(null);
  const [revision, setRevision] = useState({ amount: '', reason: '' });
  const branches = useBranchChoices();
  const departments = useDepartmentChoices();

  const list = useQuery<BudgetPage>({
    queryKey: ['procurement-budgets', status, page],
    queryFn: async () => (await api.get('/procurement/budgets', { params: { page, pageSize: 20, status: status || undefined } })).data,
  });
  const done = (message: string) => {
    notify.success(message);
    qc.invalidateQueries({ queryKey: ['procurement-budgets'] });
  };

  const create = useMutation({
    mutationFn: async () => {
      const f = form!;
      if (!f.name.trim()) throw new Error(tx('Enter a name'));
      if (f.amount === '' || Number(f.amount) < 0) throw new Error(tx('Enter an amount of zero or more'));
      if (f.endDate < f.startDate) throw new Error(tx('The end date is before the start date'));
      return api.post('/procurement/budgets', {
        name: f.name,
        startDate: f.startDate,
        endDate: f.endDate,
        branchId: f.branchId || null,
        departmentId: f.departmentId || null,
        amount: Number(f.amount),
        warnPercent: Number(f.warnPercent) || 90,
        controlMode: f.controlMode,
        notes: f.notes || null,
      });
    },
    onSuccess: () => {
      setForm(null);
      done(tx('Saved'));
    },
    onError: (e) => notify.error(errorText(e)),
  });
  const act = useMutation({
    mutationFn: async (v: { id: string; action: 'activate' | 'close' }) => api.post(`/procurement/budgets/${v.id}/${v.action}`),
    onSuccess: () => done(tx('Updated')),
    onError: (e) => notify.error(errorText(e)),
  });
  const remove = useMutation({
    mutationFn: async (id: string) => api.delete(`/procurement/budgets/${id}`),
    onSuccess: () => done(tx('Deleted')),
    onError: (e) => notify.error(errorText(e)),
  });
  const revise = useMutation({
    mutationFn: async () => api.post(`/procurement/budgets/${revising!.id}/revise`, {
      amount: Number(revision.amount),
      reason: revision.reason,
    }),
    onSuccess: () => {
      setRevising(null);
      done(tx('Updated'));
    },
    onError: (e) => notify.error(errorText(e)),
  });

  const name = (options: { id: string; name: string }[] | undefined, id: string | null) =>
    id ? options?.find((o) => o.id === id)?.name ?? id : tx('All');

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div>
          <h1 className="text-2xl font-semibold">{tx('Procurement Budgets')}</h1>
          <p className="text-sm text-muted-foreground">{tx('Spend ceilings per period, branch and department, checked on every request and purchase. Amounts are in base currency, excluding tax.')}</p>
        </div>
        {canManage && <Button onClick={() => setForm(emptyForm())}><Plus className="mr-1 h-4 w-4" />{tx('New budget')}</Button>}
      </div>

      <select className={`${selectClass} max-w-xs`} value={status} onChange={(e) => { setStatus(e.target.value); setPage(1); }}>
        <option value="">{tx('All statuses')}</option>
        <option value="draft">{tx('Draft')}</option>
        <option value="active">{tx('Active')}</option>
        <option value="closed">{tx('Closed')}</option>
      </select>

      {list.isLoading && <Skeleton className="h-32 w-full" />}
      {list.isError && <p className="text-sm text-destructive">{errorText(list.error)}</p>}
      {list.data?.data.length === 0 && (
        <Card><CardContent className="p-8 text-center text-sm text-muted-foreground">{tx('No procurement budgets yet.')}</CardContent></Card>
      )}

      <div className="grid gap-3 md:grid-cols-2">
        {list.data?.data.map((b) => {
          const percent = Math.min(100, Number(b.percent));
          return (
            <Card key={b.id}>
              <CardHeader>
                <div className="flex items-start justify-between gap-2">
                  <CardTitle className="text-base">{b.name}</CardTitle>
                  <Badge variant={b.status === 'active' ? 'default' : 'secondary'}>{b.status}</Badge>
                </div>
                <CardDescription>
                  {b.startDate} – {b.endDate} · {tx('Branch')}: {name(branches.data, b.branchId)} · {tx('Department')}: {name(departments.data, b.departmentId)}
                </CardDescription>
              </CardHeader>
              <CardContent className="space-y-2 text-sm">
                <div className="h-2 overflow-hidden rounded bg-muted">
                  <div
                    className={Number(b.percent) >= Number(b.warnPercent) ? 'h-full bg-amber-500' : 'h-full bg-primary'}
                    style={{ width: `${percent}%` }}
                  />
                </div>
                <div className="grid grid-cols-2 gap-x-4 text-xs text-muted-foreground">
                  <span>{tx('Budget')}: {amount(b.amount)}</span>
                  <span>{tx('Committed')}: {amount(b.committed)}</span>
                  <span>{tx('Reserved')}: {amount(b.reserved)}</span>
                  <span>{tx('Remaining')}: {amount(b.remaining)}</span>
                </div>
                <p className="text-xs text-muted-foreground">{b.controlMode === 'block' ? tx('Blocks documents that go over') : tx('Warns only')}</p>
                {canManage && (
                  <div className="flex flex-wrap gap-1 border-t pt-2">
                    {b.status === 'draft' && (
                      <>
                        <Button size="sm" disabled={act.isPending} onClick={() => act.mutate({ id: b.id, action: 'activate' })}>{tx('Activate')}</Button>
                        <Button size="sm" variant="ghost" disabled={remove.isPending} onClick={() => { if (confirm(tx('Delete this budget?'))) remove.mutate(b.id); }}>{tx('Delete')}</Button>
                      </>
                    )}
                    {b.status === 'active' && (
                      <>
                        <Button size="sm" variant="outline" onClick={() => { setRevising(b); setRevision({ amount: String(Number(b.amount)), reason: '' }); }}>{tx('Revise')}</Button>
                        <Button size="sm" variant="ghost" disabled={act.isPending} onClick={() => act.mutate({ id: b.id, action: 'close' })}>{tx('Close')}</Button>
                      </>
                    )}
                  </div>
                )}
              </CardContent>
            </Card>
          );
        })}
      </div>

      {list.data && list.data.meta.totalPages > 1 && (
        <div className="flex items-center justify-end gap-2 text-sm">
          <Button variant="outline" size="sm" disabled={page <= 1} onClick={() => setPage((p) => p - 1)}>{tx('Previous')}</Button>
          <span>{page} / {list.data.meta.totalPages}</span>
          <Button variant="outline" size="sm" disabled={page >= list.data.meta.totalPages} onClick={() => setPage((p) => p + 1)}>{tx('Next')}</Button>
        </div>
      )}

      <Dialog open={form !== null} onOpenChange={(o) => { if (!o) setForm(null); }}>
        <DialogContent>
          <DialogHeader><DialogTitle>{tx('New budget')}</DialogTitle></DialogHeader>
          {form && (
            <div className="grid gap-3 sm:grid-cols-2">
              <div className="space-y-1.5 sm:col-span-2">
                <Label>{tx('Name *')}</Label>
                <Input value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} />
              </div>
              <div className="space-y-1.5">
                <Label>{tx('Start date')}</Label>
                <Input type="date" value={form.startDate} onChange={(e) => setForm({ ...form, startDate: e.target.value })} />
              </div>
              <div className="space-y-1.5">
                <Label>{tx('End date')}</Label>
                <Input type="date" value={form.endDate} onChange={(e) => setForm({ ...form, endDate: e.target.value })} />
              </div>
              <div className="space-y-1.5">
                <Label>{tx('Branch')}</Label>
                <select className={selectClass} value={form.branchId} onChange={(e) => setForm({ ...form, branchId: e.target.value })}>
                  <option value="">{tx('All')}</option>
                  {branches.data?.map((b) => <option key={b.id} value={b.id}>{b.name}</option>)}
                </select>
              </div>
              <div className="space-y-1.5">
                <Label>{tx('Department')}</Label>
                <select className={selectClass} value={form.departmentId} onChange={(e) => setForm({ ...form, departmentId: e.target.value })}>
                  <option value="">{tx('All')}</option>
                  {departments.data?.map((d) => <option key={d.id} value={d.id}>{d.name}</option>)}
                </select>
              </div>
              <div className="space-y-1.5">
                <Label>{tx('Amount *')}</Label>
                <Input type="number" min={0} value={form.amount} onChange={(e) => setForm({ ...form, amount: e.target.value })} />
              </div>
              <div className="space-y-1.5">
                <Label>{tx('Warn at %')}</Label>
                <Input type="number" min={1} max={100} value={form.warnPercent} onChange={(e) => setForm({ ...form, warnPercent: e.target.value })} />
              </div>
              <div className="space-y-1.5 sm:col-span-2">
                <Label>{tx('When a document goes over')}</Label>
                <select className={selectClass} value={form.controlMode} onChange={(e) => setForm({ ...form, controlMode: e.target.value })}>
                  <option value="block">{tx('Refuse it')}</option>
                  <option value="warn">{tx('Let it through and record it')}</option>
                </select>
              </div>
            </div>
          )}
          <DialogFooter>
            <Button variant="outline" onClick={() => setForm(null)}>{tx('Cancel')}</Button>
            <Button disabled={create.isPending} onClick={() => create.mutate()}>{tx('Save')}</Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      <Dialog open={revising !== null} onOpenChange={(o) => { if (!o) setRevising(null); }}>
        <DialogContent>
          <DialogHeader><DialogTitle>{tx('Revise budget')}</DialogTitle></DialogHeader>
          <div className="space-y-3">
            <div className="space-y-1.5">
              <Label>{tx('Amount *')}</Label>
              <Input type="number" min={0} value={revision.amount} onChange={(e) => setRevision({ ...revision, amount: e.target.value })} />
            </div>
            <div className="space-y-1.5">
              <Label>{tx('Reason *')}</Label>
              <Input value={revision.reason} onChange={(e) => setRevision({ ...revision, reason: e.target.value })} />
            </div>
          </div>
          <DialogFooter>
            <Button variant="outline" onClick={() => setRevising(null)}>{tx('Cancel')}</Button>
            <Button disabled={!revision.reason.trim() || revision.amount === '' || revise.isPending} onClick={() => revise.mutate()}>{tx('Save')}</Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  );
}

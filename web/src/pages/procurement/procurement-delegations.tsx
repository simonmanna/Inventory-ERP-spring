import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { Plus, Trash2 } from 'lucide-react';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card, CardContent } from '@/components/ui/card';
import { Dialog, DialogContent, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Skeleton } from '@/components/ui/skeleton';
import { useBranchChoices } from '@/features/approval-controls/api';
import { api } from '@/lib/api';
import { tx } from '@/lib/i18n/tx';
import { notify } from '@/lib/notify';
import { useAuthStore } from '@/stores/auth.store';

interface Delegation {
  id: string;
  branchId: string;
  mode: 'central' | 'delegated';
  delegateUserId: string | null;
  maxOrderAmount: number | string | null;
  validFrom: string | null;
  validTo: string | null;
  isActive: boolean;
}

const selectClass = 'h-9 w-full rounded-md border border-input bg-transparent px-3 py-1 text-sm';
const errorText = (e: any) => e?.response?.data?.message ?? e?.message ?? tx('Failed');
const emptyForm = { branchId: '', mode: 'delegated', delegateUserId: '', maxOrderAmount: '', validFrom: '', validTo: '' };

export function ProcurementDelegationsPage() {
  const qc = useQueryClient();
  const canManage = useAuthStore((s) => s.hasPermission)('procurement_delegation:manage');
  const [form, setForm] = useState<typeof emptyForm | null>(null);
  const branches = useBranchChoices();
  const list = useQuery<Delegation[]>({
    queryKey: ['procurement-delegations'],
    queryFn: async () => (await api.get('/procurement/delegations')).data,
  });
  const branchName = (id: string) => branches.data?.find((b) => b.id === id)?.name ?? id;

  const create = useMutation({
    mutationFn: async () => {
      const f = form!;
      if (!f.branchId) throw new Error(tx('Select a branch'));
      const delegated = f.mode === 'delegated';
      return api.post('/procurement/delegations', {
        branchId: f.branchId,
        mode: f.mode,
        delegateUserId: delegated && f.delegateUserId ? f.delegateUserId : null,
        maxOrderAmount: delegated && f.maxOrderAmount !== '' ? Number(f.maxOrderAmount) : null,
        validFrom: f.validFrom || null,
        validTo: f.validTo || null,
      });
    },
    onSuccess: () => {
      setForm(null);
      notify.success(tx('Saved'));
      qc.invalidateQueries({ queryKey: ['procurement-delegations'] });
    },
    onError: (e) => notify.error(errorText(e)),
  });
  const remove = useMutation({
    mutationFn: async (id: string) => api.delete(`/procurement/delegations/${id}`),
    onSuccess: () => {
      notify.success(tx('Deleted'));
      qc.invalidateQueries({ queryKey: ['procurement-delegations'] });
    },
    onError: (e) => notify.error(errorText(e)),
  });

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div>
          <h1 className="text-2xl font-semibold">{tx('Purchasing Delegation')}</h1>
          <p className="text-sm text-muted-foreground">{tx('A branch without a rule buys freely. Once it has a rule, only central buyers and its delegates can place orders for it.')}</p>
        </div>
        {canManage && <Button onClick={() => setForm({ ...emptyForm })}><Plus className="mr-1 h-4 w-4" />{tx('New rule')}</Button>}
      </div>

      {list.isLoading && <Skeleton className="h-32 w-full" />}
      {list.isError && <p className="text-sm text-destructive">{errorText(list.error)}</p>}
      {list.data?.length === 0 && (
        <Card><CardContent className="p-8 text-center text-sm text-muted-foreground">{tx('No delegation rules yet.')}</CardContent></Card>
      )}

      <div className="grid gap-3 md:grid-cols-2">
        {list.data?.map((d) => (
          <Card key={d.id}>
            <CardContent className="flex items-start justify-between gap-2 p-4">
              <div className="min-w-0 space-y-1 text-sm">
                <div className="flex items-center gap-2">
                  <span className="font-semibold">{branchName(d.branchId)}</span>
                  <Badge variant={d.mode === 'central' ? 'secondary' : 'default'}>{d.mode === 'central' ? tx('Central') : tx('Delegated')}</Badge>
                </div>
                {d.mode === 'delegated' && (
                  <p className="text-muted-foreground">
                    {d.delegateUserId ? <span className="font-mono text-xs">{d.delegateUserId}</span> : tx('Anyone in the branch')}
                    {' · '}
                    {d.maxOrderAmount != null ? `${tx('Up to')} ${Number(d.maxOrderAmount).toLocaleString()}` : tx('No limit')}
                  </p>
                )}
                {(d.validFrom || d.validTo) && <p className="text-xs text-muted-foreground">{d.validFrom ?? '…'} – {d.validTo ?? '…'}</p>}
              </div>
              {canManage && (
                <Button variant="ghost" size="icon" disabled={remove.isPending} onClick={() => { if (confirm(tx('Delete this rule?'))) remove.mutate(d.id); }}>
                  <Trash2 className="h-4 w-4 text-red-500" />
                </Button>
              )}
            </CardContent>
          </Card>
        ))}
      </div>

      <Dialog open={form !== null} onOpenChange={(o) => { if (!o) setForm(null); }}>
        <DialogContent>
          <DialogHeader><DialogTitle>{tx('New rule')}</DialogTitle></DialogHeader>
          {form && (
            <div className="grid gap-3 sm:grid-cols-2">
              <div className="space-y-1.5">
                <Label>{tx('Branch')}</Label>
                <select className={selectClass} value={form.branchId} onChange={(e) => setForm({ ...form, branchId: e.target.value })}>
                  <option value="">{tx('Select a branch')}</option>
                  {branches.data?.map((b) => <option key={b.id} value={b.id}>{b.name}</option>)}
                </select>
              </div>
              <div className="space-y-1.5">
                <Label>{tx('Purchasing')}</Label>
                <select className={selectClass} value={form.mode} onChange={(e) => setForm({ ...form, mode: e.target.value })}>
                  <option value="delegated">{tx('Delegated')}</option>
                  <option value="central">{tx('Central')}</option>
                </select>
              </div>
              {form.mode === 'delegated' && (
                <>
                  <div className="space-y-1.5">
                    <Label>{tx('User id')}</Label>
                    <Input placeholder={tx('Empty = anyone in the branch')} value={form.delegateUserId} onChange={(e) => setForm({ ...form, delegateUserId: e.target.value })} />
                  </div>
                  <div className="space-y-1.5">
                    <Label>{tx('Maximum order amount')}</Label>
                    <Input type="number" min={0} placeholder={tx('No limit')} value={form.maxOrderAmount} onChange={(e) => setForm({ ...form, maxOrderAmount: e.target.value })} />
                  </div>
                </>
              )}
              <div className="space-y-1.5">
                <Label>{tx('Valid from')}</Label>
                <Input type="date" value={form.validFrom} onChange={(e) => setForm({ ...form, validFrom: e.target.value })} />
              </div>
              <div className="space-y-1.5">
                <Label>{tx('Valid to')}</Label>
                <Input type="date" value={form.validTo} onChange={(e) => setForm({ ...form, validTo: e.target.value })} />
              </div>
            </div>
          )}
          <DialogFooter>
            <Button variant="outline" onClick={() => setForm(null)}>{tx('Cancel')}</Button>
            <Button disabled={create.isPending} onClick={() => create.mutate()}>{tx('Save')}</Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  );
}

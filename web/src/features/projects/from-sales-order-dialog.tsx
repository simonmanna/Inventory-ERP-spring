import { useEffect, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { useNavigate } from 'react-router-dom';
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { SearchableSelect } from '@/components/ui/searchable-select';
import { useAuthStore } from '@/stores/auth.store';
import { useDebouncedValue } from '@/lib/use-debounced-value';
import { tx } from '@/lib/i18n/tx';
import { api } from '@/lib/api';
import type { SalesOrder } from '@/features/sales/api';
import { useAssignableUsers, useProjectBoard, usePmMutation } from './api';
import { selectClass } from './ui';

interface Props {
  open: boolean;
  onClose: () => void;
  /** Opened from a sales order: the order is fixed. Otherwise the user picks one. */
  salesOrder?: { id: string; number: string; partnerName?: string | null } | null;
}

/**
 * Start a project from a sales order (POST /projects/from-sales-order): customer, branch and contract value come
 * from the order, and by default every order line becomes a task.
 */
export function FromSalesOrderDialog({ open, onClose, salesOrder }: Props) {
  const navigate = useNavigate();
  const me = useAuthStore((s) => s.user);
  const { data: users = [] } = useAssignableUsers();
  const { data: templates = [] } = useProjectBoard({ templates: true });
  const [query, setQuery] = useState('');
  const q = useDebouncedValue(query, 250);
  // Only when the user picks the order themselves (the list needs sales:order:read; callers gate on it).
  const { data: orders } = useQuery({
    queryKey: ['sales', 'orders', 'project-picker', q],
    queryFn: async () => (await api.get<{ data: SalesOrder[] }>('/sales/orders', { params: { q: q || undefined, pageSize: 20 } })).data,
    enabled: open && !salesOrder,
  });
  const blank = { salesOrderId: '', name: '', managerId: me?.id ?? '', templateId: '', taskPerLine: true };
  const [f, setF] = useState(blank);
  useEffect(() => {
    if (open) setF({ ...blank, salesOrderId: salesOrder?.id ?? '' });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open, salesOrder?.id]);
  const set = <K extends keyof typeof f>(k: K, v: (typeof f)[K]) => setF((s) => ({ ...s, [k]: v }));

  const create = usePmMutation<void, { id: string }>(() => ({
    method: 'post',
    url: '/projects/from-sales-order',
    body: {
      salesOrderId: f.salesOrderId, name: f.name.trim() || undefined, managerId: f.managerId || undefined,
      templateId: f.templateId || undefined, taskPerLine: f.taskPerLine,
    },
  }), { success: tx('Project created') });

  const submit = async () => {
    const res = await create.mutateAsync();
    onClose();
    if (res?.id) navigate(`/projects/${res.id}`);
  };

  const options = (orders?.data ?? [])
    .filter((o) => o.status !== 'cancelled')
    .map((o) => ({ value: o.id, label: o.number, hint: o.partnerName ?? undefined }));

  return (
    <Dialog open={open} onOpenChange={(o) => !o && onClose()}>
      <DialogContent className="max-w-lg">
        <DialogHeader>
          <DialogTitle>{tx('Project from sales order')}</DialogTitle>
          <DialogDescription>
            {tx('The customer, branch and contract value come from the order. Each order line can become a task.')}
          </DialogDescription>
        </DialogHeader>
        <form
          id="so-project-form"
          className="grid gap-4"
          onSubmit={(e) => { e.preventDefault(); if (f.salesOrderId) void submit(); }}
        >
          <div className="space-y-1.5">
            <Label>{tx('Sales order')}</Label>
            {salesOrder ? (
              <p className="text-sm font-medium">
                {salesOrder.number}{salesOrder.partnerName && <span className="text-muted-foreground"> · {salesOrder.partnerName}</span>}
              </p>
            ) : (
              <SearchableSelect
                value={f.salesOrderId}
                onValueChange={(v) => set('salesOrderId', v)}
                options={options}
                onQueryChange={setQuery}
                placeholder={tx('Pick an order')}
                searchPlaceholder={tx('Search order number or customer…')}
                emptyText={tx('No orders found')}
              />
            )}
          </div>
          <div className="space-y-1.5">
            <Label htmlFor="so-pname">{tx('Project name')}</Label>
            <Input id="so-pname" value={f.name} onChange={(e) => set('name', e.target.value)} placeholder={tx('Order number and customer')} />
          </div>
          <div className="grid gap-4 sm:grid-cols-2">
            <div className="space-y-1.5">
              <Label>{tx('Project manager')}</Label>
              <select className={selectClass} value={f.managerId} onChange={(e) => set('managerId', e.target.value)}>
                {users.map((u) => <option key={u.id} value={u.id}>{u.name || u.email}</option>)}
              </select>
            </div>
            <div className="space-y-1.5">
              <Label>{tx('Start from template')}</Label>
              <select className={selectClass} value={f.templateId} onChange={(e) => set('templateId', e.target.value)}>
                <option value="">{tx('Blank project')}</option>
                {templates.map((t) => <option key={t.id} value={t.id}>{t.code} · {t.name}</option>)}
              </select>
            </div>
          </div>
          <label className="flex items-center gap-2 text-sm">
            <input type="checkbox" className="h-4 w-4 accent-primary" checked={f.taskPerLine} onChange={(e) => set('taskPerLine', e.target.checked)} />
            {tx('Create a task for each order line')}
          </label>
        </form>
        <DialogFooter>
          <Button variant="outline" onClick={onClose}>{tx('Cancel')}</Button>
          <Button type="submit" form="so-project-form" disabled={!f.salesOrderId || create.isPending}>{tx('Create project')}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

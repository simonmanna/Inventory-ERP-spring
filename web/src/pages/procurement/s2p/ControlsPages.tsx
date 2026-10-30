import { useState } from 'react';
import { Link } from 'react-router-dom';
import { Check, ClipboardCheck, Landmark, MessageSquareWarning, Plus, X } from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { Button } from '@/components/ui/button';
import { Card, CardContent } from '@/components/ui/card';
import { Dialog, DialogContent, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Textarea } from '@/components/ui/textarea';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { ListPageHeader, StatusPill } from '@/components/list';
import { useAuthStore } from '@/stores/auth.store';
import {
  CLAIM_STATUS_TONE,
  claimApi,
  errMsg,
  poChangeApi,
  useClaimMutation,
  useClaims,
  useCommitments,
  usePendingPoChanges,
  usePoChangeMutation,
  useSupplierOptions,
  type ClaimStatus,
  type ClaimType,
  type SupplierClaim,
} from '@/features/procurement/api';
import { notify } from '@/lib/notify';
import { date, money } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';

const CLAIM_TYPES: Array<{ value: ClaimType; label: string }> = [
  { value: 'shortage', label: tx('Short delivery') },
  { value: 'damage', label: tx('Damaged goods') },
  { value: 'quality', label: tx('Quality reject') },
  { value: 'price', label: tx('Price difference') },
  { value: 'late_delivery', label: tx('Late delivery') },
  { value: 'other', label: tx('Other') },
];
const TYPE_LABEL = Object.fromEntries(CLAIM_TYPES.map((t) => [t.value, t.label])) as Record<ClaimType, string>;
const STATUS_LABEL: Record<ClaimStatus, string> = {
  open: tx('Open'),
  submitted: tx('Sent to supplier'),
  accepted: tx('Accepted'),
  rejected: tx('Rejected'),
  settled: tx('Settled'),
  cancelled: tx('Cancelled'),
};

/**
 * Supplier claims (ADR-PROCUREMENT-001 wave 5): raise a claim for a short, damaged, rejected, mispriced or late
 * delivery, send it, record the supplier's answer and settle it with a debit note.
 */
export function SupplierClaimsPage() {
  const has = useAuthStore((s) => s.hasPermission);
  const canManage = has(PERMISSIONS.supplierClaim.manage);
  const [status, setStatus] = useState('');
  const { data } = useClaims({ status });
  const { data: suppliers = [] } = useSupplierOptions();
  const mutate = useClaimMutation((fn: () => Promise<unknown>) => fn());
  const [open, setOpen] = useState(false);
  const [form, setForm] = useState({ partnerId: '', type: 'shortage' as ClaimType, amount: '', description: '' });
  const [accepting, setAccepting] = useState<SupplierClaim | null>(null);
  const [agreed, setAgreed] = useState({ amount: '', response: '' });
  const rows = data?.data ?? [];

  const run = async (fn: () => Promise<unknown>, ok: string) => {
    try {
      await mutate.mutateAsync(fn);
      notify.success(ok);
      return true;
    } catch (e) {
      notify.error(tx('Action failed'), errMsg(e));
      return false;
    }
  };

  const create = async () => {
    if (await run(() => claimApi.create({ ...form, amount: Number(form.amount) }), tx('Claim raised'))) {
      setOpen(false);
      setForm({ partnerId: '', type: 'shortage', amount: '', description: '' });
    }
  };

  const reject = async (c: SupplierClaim) => {
    const response = window.prompt(tx("What did the supplier say?")) ?? '';
    if (response.trim()) await run(() => claimApi.reject(c.id, response), tx('Claim rejected'));
  };

  return (
    <div className="space-y-4 p-6">
      <ListPageHeader title={tx('Supplier claims')} icon={MessageSquareWarning}
        description={tx('Short, damaged, rejected or mispriced deliveries claimed back from suppliers and settled by debit note.')}
        actions={canManage && <Button onClick={() => setOpen(true)}><Plus className="mr-1 h-4 w-4" />{tx('New claim')}</Button>} />
      <div className="flex gap-2">
        <select className="h-9 rounded-md border bg-background px-2 text-sm" value={status} onChange={(e) => setStatus(e.target.value)}>
          <option value="">{tx('All statuses')}</option>
          {(Object.keys(STATUS_LABEL) as ClaimStatus[]).map((s) => <option key={s} value={s}>{STATUS_LABEL[s]}</option>)}
        </select>
      </div>
      <Card>
        <CardContent className="p-0">
          <Table>
            <TableHeader><TableRow>
              <TableHead>{tx('Claim')}</TableHead><TableHead>{tx('Supplier')}</TableHead><TableHead>{tx('Type')}</TableHead>
              <TableHead>{tx('Order')}</TableHead><TableHead className="text-right">{tx('Amount')}</TableHead>
              <TableHead>{tx('Status')}</TableHead><TableHead>{tx('Debit note')}</TableHead><TableHead />
            </TableRow></TableHeader>
            <TableBody>
              {!rows.length && <TableRow><TableCell colSpan={8} className="py-8 text-center text-muted-foreground">{tx('No claims.')}</TableCell></TableRow>}
              {rows.map((c) => (
                <TableRow key={c.id}>
                  <TableCell>
                    <div className="font-mono text-xs">{c.claimNumber}</div>
                    <div className="text-xs text-muted-foreground">{c.description}</div>
                  </TableCell>
                  <TableCell>{c.partnerName}</TableCell>
                  <TableCell>{TYPE_LABEL[c.type]}</TableCell>
                  <TableCell>{c.purchaseOrderId ? <Link className="font-mono text-xs hover:underline" to={`/procurement/purchase-orders/${c.purchaseOrderId}`}>{c.orderNumber}</Link> : '—'}</TableCell>
                  <TableCell className="text-right">{money(c.amount)}</TableCell>
                  <TableCell><StatusPill tone={CLAIM_STATUS_TONE[c.status]}>{STATUS_LABEL[c.status]}</StatusPill></TableCell>
                  <TableCell className="font-mono text-xs">{c.debitNoteNumber ?? '—'}</TableCell>
                  <TableCell className="space-x-1 whitespace-nowrap text-right">
                    {canManage && c.status === 'open' && (
                      <>
                        <Button size="sm" variant="outline" onClick={() => run(() => claimApi.action(c.id, 'submit'), tx('Claim sent'))}>{tx('Send')}</Button>
                        <Button size="sm" variant="ghost" onClick={() => run(() => claimApi.action(c.id, 'cancel'), tx('Claim cancelled'))}>{tx('Cancel')}</Button>
                      </>
                    )}
                    {canManage && (c.status === 'open' || c.status === 'submitted') && (
                      <>
                        <Button size="sm" onClick={() => { setAccepting(c); setAgreed({ amount: String(Number(c.amount)), response: '' }); }}><Check className="mr-1 h-4 w-4" />{tx('Accepted')}</Button>
                        <Button size="sm" variant="ghost" onClick={() => reject(c)}><X className="mr-1 h-4 w-4" />{tx('Rejected')}</Button>
                      </>
                    )}
                    {canManage && c.status === 'accepted' && (
                      <Button size="sm" onClick={() => run(() => claimApi.settle(c.id), tx('Debit note created — post it to reduce what you owe'))}>{tx('Settle')}</Button>
                    )}
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </CardContent>
      </Card>

      <Dialog open={open} onOpenChange={setOpen}>
        <DialogContent>
          <DialogHeader><DialogTitle>{tx('New claim')}</DialogTitle></DialogHeader>
          <div className="space-y-3">
            <div>
              <Label>{tx('Supplier')}</Label>
              <select className="h-9 w-full rounded-md border bg-background px-2 text-sm" value={form.partnerId} onChange={(e) => setForm({ ...form, partnerId: e.target.value })}>
                <option value="">{tx('Select…')}</option>
                {suppliers.map((s) => <option key={s.id} value={s.id}>{s.name}</option>)}
              </select>
            </div>
            <div className="grid gap-3 sm:grid-cols-2">
              <div>
                <Label>{tx('Type')}</Label>
                <select className="h-9 w-full rounded-md border bg-background px-2 text-sm" value={form.type} onChange={(e) => setForm({ ...form, type: e.target.value as ClaimType })}>
                  {CLAIM_TYPES.map((t) => <option key={t.value} value={t.value}>{t.label}</option>)}
                </select>
              </div>
              <div>
                <Label>{tx('Amount')}</Label>
                <Input type="number" min={0} value={form.amount} onChange={(e) => setForm({ ...form, amount: e.target.value })} />
              </div>
            </div>
            <div>
              <Label>{tx('What happened')}</Label>
              <Textarea value={form.description} onChange={(e) => setForm({ ...form, description: e.target.value })} />
            </div>
          </div>
          <DialogFooter>
            <Button variant="ghost" onClick={() => setOpen(false)}>{tx('Cancel')}</Button>
            <Button onClick={create} disabled={!form.partnerId || form.amount === '' || !form.description.trim()}>{tx('Raise claim')}</Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      <Dialog open={!!accepting} onOpenChange={(v) => !v && setAccepting(null)}>
        <DialogContent>
          <DialogHeader><DialogTitle>{tx('Supplier accepted the claim')}</DialogTitle></DialogHeader>
          <div className="space-y-3">
            <div>
              <Label>{tx('Agreed amount')}</Label>
              <Input type="number" min={0} value={agreed.amount} onChange={(e) => setAgreed({ ...agreed, amount: e.target.value })} />
            </div>
            <div>
              <Label>{tx('Supplier response')}</Label>
              <Textarea value={agreed.response} onChange={(e) => setAgreed({ ...agreed, response: e.target.value })} />
            </div>
          </div>
          <DialogFooter>
            <Button variant="ghost" onClick={() => setAccepting(null)}>{tx('Cancel')}</Button>
            <Button onClick={async () => {
              if (accepting && await run(() => claimApi.accept(accepting.id, Number(agreed.amount), agreed.response || undefined), tx('Claim accepted'))) setAccepting(null);
            }}>{tx('Save')}</Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  );
}

/** Order changes waiting for a second approver (ADR-PROCUREMENT-001 wave 5). */
export function PoChangeApprovalsPage() {
  const userId = useAuthStore((s) => s.user?.id);
  const { data: rows = [] } = usePendingPoChanges();
  const mutate = usePoChangeMutation((fn: () => Promise<unknown>) => fn());

  const decide = async (id: string, approve: boolean) => {
    try {
      if (approve) {
        await mutate.mutateAsync(() => poChangeApi.approve(id));
        notify.success(tx('Change approved and applied'));
      } else {
        const note = window.prompt(tx('Why is the change rejected?')) ?? '';
        if (!note.trim()) return;
        await mutate.mutateAsync(() => poChangeApi.reject(id, note));
        notify.success(tx('Change rejected'));
      }
    } catch (e) {
      notify.error(tx('Action failed'), errMsg(e));
    }
  };

  return (
    <div className="space-y-4 p-6">
      <ListPageHeader title={tx('Order changes to approve')} icon={ClipboardCheck}
        description={tx('Changes that raise an order above the allowed limit wait here for someone other than the requester.')} />
      <Card>
        <CardContent className="p-0">
          <Table>
            <TableHeader><TableRow>
              <TableHead>{tx('Order')}</TableHead><TableHead>{tx('Supplier')}</TableHead><TableHead>{tx('Reason')}</TableHead>
              <TableHead className="text-right">{tx('Was')}</TableHead><TableHead className="text-right">{tx('Becomes')}</TableHead>
              <TableHead>{tx('Asked')}</TableHead><TableHead />
            </TableRow></TableHeader>
            <TableBody>
              {!rows.length && <TableRow><TableCell colSpan={7} className="py-8 text-center text-muted-foreground">{tx('Nothing waiting for approval.')}</TableCell></TableRow>}
              {rows.map((c) => (
                <TableRow key={c.id}>
                  <TableCell><Link className="font-mono text-xs hover:underline" to={`/procurement/purchase-orders/${c.purchaseOrderId}`}>{c.orderNumber}</Link></TableCell>
                  <TableCell>{c.partnerName}</TableCell>
                  <TableCell>{c.reason}</TableCell>
                  <TableCell className="text-right">{money(c.oldTotal)}</TableCell>
                  <TableCell className="text-right font-medium">{money(c.newTotal)}</TableCell>
                  <TableCell>{date(c.createdAt)}</TableCell>
                  <TableCell className="space-x-1 whitespace-nowrap text-right">
                    {c.requestedBy !== userId ? (
                      <>
                        <Button size="sm" onClick={() => decide(c.id, true)}><Check className="mr-1 h-4 w-4" />{tx('Approve')}</Button>
                        <Button size="sm" variant="ghost" onClick={() => decide(c.id, false)}><X className="mr-1 h-4 w-4" />{tx('Reject')}</Button>
                      </>
                    ) : <span className="text-xs text-muted-foreground">{tx('Your request')}</span>}
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </CardContent>
      </Card>
    </div>
  );
}

/** Open purchase commitments against budget accounts (ADR-PROCUREMENT-001 wave 5). */
export function CommitmentsPage() {
  const [status, setStatus] = useState('open');
  const [page, setPage] = useState(1);
  const { data } = useCommitments({ status, page });
  const rows = data?.data ?? [];
  const total = data?.meta?.total ?? 0;
  const open = rows.reduce((s, r) => s + Number(r.openAmount || 0), 0);

  return (
    <div className="space-y-4 p-6">
      <ListPageHeader title={tx('Budget commitments')} icon={Landmark}
        description={tx('Money promised to suppliers on approved orders but not yet received. It counts against budgets before the bill arrives.')} />
      <div className="flex items-center gap-3">
        <select className="h-9 rounded-md border bg-background px-2 text-sm" value={status} onChange={(e) => { setStatus(e.target.value); setPage(1); }}>
          <option value="open">{tx('Open')}</option>
          <option value="closed">{tx('Closed')}</option>
          <option value="">{tx('All')}</option>
        </select>
        <span className="text-sm text-muted-foreground">{tx('Open on this page')}: <span className="font-semibold text-foreground">{money(open)}</span></span>
      </div>
      <Card>
        <CardContent className="p-0">
          <Table>
            <TableHeader><TableRow>
              <TableHead>{tx('Order')}</TableHead><TableHead>{tx('Account')}</TableHead><TableHead>{tx('Month')}</TableHead>
              <TableHead className="text-right">{tx('Committed')}</TableHead><TableHead className="text-right">{tx('Still open')}</TableHead>
            </TableRow></TableHeader>
            <TableBody>
              {!rows.length && <TableRow><TableCell colSpan={5} className="py-8 text-center text-muted-foreground">{tx('No commitments.')}</TableCell></TableRow>}
              {rows.map((r) => (
                <TableRow key={r.id}>
                  <TableCell><Link className="font-mono text-xs hover:underline" to={`/procurement/purchase-orders/${r.sourceId}`}>{r.sourceNumber ?? r.sourceId}</Link></TableCell>
                  <TableCell>{r.accountCode} · {r.accountName}</TableCell>
                  <TableCell>{r.period}</TableCell>
                  <TableCell className="text-right">{money(r.amount)}</TableCell>
                  <TableCell className="text-right font-medium">{money(r.openAmount)}</TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </CardContent>
      </Card>
      {total > rows.length && (
        <div className="flex justify-end gap-2">
          <Button size="sm" variant="outline" disabled={page <= 1} onClick={() => setPage((p) => p - 1)}>{tx('Previous')}</Button>
          <Button size="sm" variant="outline" disabled={page * 50 >= total} onClick={() => setPage((p) => p + 1)}>{tx('Next')}</Button>
        </div>
      )}
    </div>
  );
}

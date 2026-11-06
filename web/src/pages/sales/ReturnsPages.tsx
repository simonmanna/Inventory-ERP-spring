import { useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { toast } from 'sonner';
import { MessageSquareWarning, Undo2 } from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Textarea } from '@/components/ui/textarea';
import { Skeleton } from '@/components/ui/skeleton';
import { Dialog, DialogContent, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { ListPageHeader } from '@/components/list';
import { api } from '@/lib/api';
import { useAuthStore } from '@/stores/auth.store';
import { apiErrorMessage } from '@/lib/api-error';
import { date } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';
import type { SalesOrder } from '@/features/sales/api';
import { Field } from '@/pages/crm/crm-ui';
import { AttachmentPanel } from '@/components/dms/AttachmentPanel';
import { HeaderSelect, useHeaderOptions } from './OrderHeaderFields';

interface ReturnLine { id: string; orderLineId: string; description: string; quantity: number; receivedQty: number; disposition: string | null; creditQty: number | null; inspectionNotes: string | null }
interface SalesReturn {
  id: string; number: string; orderId: string; orderNumber: string; partnerName: string; status: string; reason: string; physicalReturn: boolean;
  outcome: string | null; creditNoteId: string | null; refundPaymentId: string | null; replacementOrderId: string | null; createdAt: string; lines?: ReturnLine[];
}
interface Complaint { id: string; number: string; partnerName: string; orderNumber: string | null; category: string; description: string; status: string; returnNumber: string | null; createdAt: string; resolution: string | null }

const REASONS: Record<string, string> = {
  wrong_grade: tx('Wrong grade'), damaged: tx('Damaged'), short_quantity: tx('Short quantity'), excess_quantity: tx('Excess quantity'),
  quality_rejection: tx('Quality rejection'), other: tx('Other'),
};
const idem = () => ({ headers: { 'Idempotency-Key': crypto.randomUUID() } });

/** Return (RMA) register (ADR-S011 S7). */
export function ReturnsPage() {
  const navigate = useNavigate();
  const { data, isLoading } = useQuery({ queryKey: ['sales', 'returns'], queryFn: async () => (await api.get<SalesReturn[]>('/sales/returns')).data });
  return (
    <div className="space-y-4">
      <ListPageHeader icon={Undo2} title={tx('Returns')} description={tx('Return authorisations: receive, inspect, then credit, refund or replace.')} />
      <div className="overflow-x-auto rounded-xl border bg-card shadow-sm">
        <table className="w-full min-w-[760px] text-sm">
          <thead className="bg-muted/40 text-left text-xs font-semibold uppercase text-muted-foreground"><tr>
            <th className="px-3 py-2">{tx('Number')}</th><th className="px-3 py-2">{tx('Order')}</th><th className="px-3 py-2">{tx('Customer')}</th>
            <th className="px-3 py-2">{tx('Reason')}</th><th className="px-3 py-2">{tx('Status')}</th><th className="px-3 py-2">{tx('Outcome')}</th><th className="px-3 py-2">{tx('Date')}</th></tr></thead>
          <tbody>
            {isLoading && <tr><td colSpan={7} className="p-3"><Skeleton className="h-24 w-full" /></td></tr>}
            {(data ?? []).map((r) => (
              <tr key={r.id} className="cursor-pointer border-t hover:bg-muted/30" onClick={() => navigate(`/sales/returns/${r.id}`)}>
                <td className="px-3 py-2 font-medium">{r.number}</td><td className="px-3 py-2">{r.orderNumber}</td><td className="px-3 py-2">{r.partnerName}</td>
                <td className="px-3 py-2">{REASONS[r.reason]}</td><td className="px-3 py-2">{r.status}</td><td className="px-3 py-2">{r.outcome ?? '—'}</td>
                <td className="px-3 py-2">{date(r.createdAt)}</td>
              </tr>
            ))}
            {data && data.length === 0 && <tr><td colSpan={7} className="p-6 text-center text-muted-foreground">{tx('No returns.')}</td></tr>}
          </tbody>
        </table>
      </div>
    </div>
  );
}

/** One return with its workflow actions. */
export function ReturnDetailPage() {
  const { id } = useParams();
  const qc = useQueryClient();
  const navigate = useNavigate();
  const has = useAuthStore((s) => s.hasPermission);
  const { data: r } = useQuery({ queryKey: ['sales', 'return', id], queryFn: async () => (await api.get<SalesReturn>(`/sales/returns/${id}`)).data, enabled: !!id });
  const options = useHeaderOptions();
  const [disp, setDisp] = useState<Record<string, { disposition: string; locationId?: string; rejectedQty?: number; rejectReason?: string }>>({});
  const [outcome, setOutcome] = useState('credit_note');
  const act = useMutation({
    mutationFn: async ({ action, body }: { action: string; body?: Record<string, unknown> }) =>
      (await api.post<SalesReturn>(`/sales/returns/${id}/${action}`, body ?? {}, ['receive', 'inspect', 'close'].includes(action) ? idem() : undefined)).data,
    onSuccess: () => { qc.invalidateQueries({ queryKey: ['sales'] }); toast.success(tx('Done')); },
    onError: (e) => toast.error(apiErrorMessage(e, tx('Action failed'))),
  });
  if (!r) return <Skeleton className="h-64 w-full" />;
  const canWrite = has(PERMISSIONS.sales.returnWrite);
  const canApprove = has(PERMISSIONS.sales.returnApprove);
  const closable = r.physicalReturn ? r.status === 'inspected' : r.status === 'approved';
  return (
    <div className="space-y-4">
      <div className="text-sm"><button className="font-medium text-primary hover:underline" onClick={() => navigate('/sales/returns')}>{tx('Returns')}</button> / <strong>{r.number}</strong> · {r.status}
        <button className="ml-3 text-primary hover:underline" onClick={() => navigate(`/sales/orders/${r.orderId}`)}>{r.orderNumber}</button></div>
      <div className="flex flex-wrap gap-2">
        {r.status === 'requested' && canApprove && <Button onClick={() => act.mutate({ action: 'approve' })}>{tx('Approve')}</Button>}
        {['requested', 'approved'].includes(r.status) && canApprove && <Button variant="outline" onClick={() => { const reason = prompt(tx('Reason')); if (reason) act.mutate({ action: 'reject', body: { reason } }); }}>{tx('Reject')}</Button>}
        {r.status === 'approved' && r.physicalReturn && canWrite && <Button onClick={() => act.mutate({ action: 'receive' })}>{tx('Receive goods')}</Button>}
        {r.status === 'received' && canWrite && <Button onClick={() => act.mutate({ action: 'inspect', body: { lines: Object.entries(disp).map(([lineId, d]) => ({ lineId, ...d })) } })}>{tx('Record inspection')}</Button>}
        {closable && canApprove && <>
          <select className="h-9 rounded-md border bg-background px-2 text-sm" value={outcome} onChange={(e) => setOutcome(e.target.value)}>
            <option value="credit_note">{tx('Credit note')}</option><option value="refund">{tx('Credit note + refund')}</option>
            <option value="replacement">{tx('Replacement order')}</option><option value="none">{tx('No settlement')}</option>
          </select>
          <Button onClick={() => act.mutate({ action: 'close', body: { outcome } })}>{tx('Close return')}</Button>
        </>}
      </div>
      <div className="rounded-xl border bg-card p-4 text-sm shadow-sm">
        <p>{tx('Customer')}: {r.partnerName} · {tx('Reason')}: {REASONS[r.reason]} · {r.physicalReturn ? tx('Goods returned') : tx('Claim without goods')}</p>
        {r.creditNoteId && <button className="text-primary hover:underline" onClick={() => navigate(`/credit-notes/${r.creditNoteId}`)}>{tx('Credit note')}</button>}
        {r.replacementOrderId && <button className="ml-3 text-primary hover:underline" onClick={() => navigate(`/sales/orders/${r.replacementOrderId}`)}>{tx('Replacement order')}</button>}
        <table className="mt-3 w-full text-sm">
          <thead className="text-left text-xs text-muted-foreground"><tr><th>{tx('Line')}</th><th className="text-right">{tx('Claimed')}</th><th className="text-right">{tx('Received')}</th><th>{tx('Disposition')}</th><th className="text-right">{tx('Credited')}</th></tr></thead>
          <tbody>
            {(r.lines ?? []).map((l) => (
              <tr key={l.id} className="border-t">
                <td className="py-1">{l.description}</td><td className="py-1 text-right">{Number(l.quantity)}</td><td className="py-1 text-right">{Number(l.receivedQty)}</td>
                <td className="py-1">{r.status === 'received' ? (
                  <div className="flex gap-1">
                    <select className="h-8 rounded border bg-background px-1" value={disp[l.id]?.disposition ?? 'restock'} onChange={(e) => setDisp({ ...disp, [l.id]: { ...disp[l.id], disposition: e.target.value } })}>
                      <option value="restock">{tx('Restock')}</option><option value="quarantine">{tx('Quarantine')}</option><option value="damaged">{tx('Damaged')}</option><option value="scrap">{tx('Scrap')}</option>
                    </select>
                    {['quarantine', 'damaged'].includes(disp[l.id]?.disposition ?? '') && <HeaderSelect value={disp[l.id]?.locationId ?? ''} options={options.locations} none={tx('Location…')} onChange={(locationId) => setDisp({ ...disp, [l.id]: { ...disp[l.id], locationId } })} />}
                    <Input aria-label={tx('Rejected quantity')} title={tx('Rejected quantity: refused, goes back to the customer, no credit')} className="h-8 w-20" type="number" min={0} placeholder={tx('Reject')} onChange={(e) => setDisp({ ...disp, [l.id]: { disposition: 'restock', ...disp[l.id], rejectedQty: Number(e.target.value) } })} />
                    {Number(disp[l.id]?.rejectedQty ?? 0) > 0 && <Input className="h-8 w-40" placeholder={tx('Why rejected')} onChange={(e) => setDisp({ ...disp, [l.id]: { ...disp[l.id], rejectReason: e.target.value } })} />}
                  </div>) : l.disposition ?? '—'}</td>
                <td className="py-1 text-right">{l.creditQty == null ? '—' : Number(l.creditQty)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}

/** Start a return from a sales order. */
export function NewReturnDialog({ order, onClose }: { order: SalesOrder; onClose: () => void }) {
  const navigate = useNavigate();
  const [reason, setReason] = useState('damaged');
  const [physical, setPhysical] = useState(true);
  const [qty, setQty] = useState<Record<string, number>>({});
  const create = useMutation({
    mutationFn: async () => (await api.post<SalesReturn>('/sales/returns', {
      orderId: order.id, reason, physicalReturn: physical, lines: Object.entries(qty).filter(([, q]) => q > 0).map(([orderLineId, quantity]) => ({ orderLineId, quantity })),
    })).data,
    onSuccess: (r) => navigate(`/sales/returns/${r.id}`), onError: (e) => toast.error(apiErrorMessage(e, tx('Could not create the return'))),
  });
  return (
    <Dialog open onOpenChange={(v) => !v && onClose()}>
      <DialogContent className="max-w-lg">
        <DialogHeader><DialogTitle>{tx('New return')} — {order.number}</DialogTitle></DialogHeader>
        <Field label={tx('Reason')}><select className="h-9 w-full rounded-md border bg-background px-2 text-sm" value={reason} onChange={(e) => setReason(e.target.value)}>
          {Object.entries(REASONS).map(([k, v]) => <option key={k} value={k}>{v}</option>)}</select></Field>
        <label className="flex items-center gap-2 text-sm"><input type="checkbox" checked={physical} onChange={(e) => setPhysical(e.target.checked)} />{tx('Goods come back')}</label>
        {(order.lines ?? []).map((l) => (
          <Field key={l.id} label={`${l.description} (${tx('delivered')} ${Number(l.qty?.delivered ?? 0)})`}><Input type="number" min={0} value={qty[l.id!] ?? 0} onChange={(e) => setQty({ ...qty, [l.id!]: Number(e.target.value) })} /></Field>
        ))}
        <DialogFooter><Button variant="outline" onClick={onClose}>{tx('Cancel')}</Button><Button disabled={create.isPending} onClick={() => create.mutate()}>{tx('Create')}</Button></DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

/** Customer complaints / service requests. */
export function ComplaintsPage() {
  const qc = useQueryClient();
  const { data } = useQuery({ queryKey: ['sales', 'complaints'], queryFn: async () => (await api.get<Complaint[]>('/sales/complaints')).data });
  const canWrite = useAuthStore((s) => s.hasPermission(PERMISSIONS.sales.complaintWrite));
  const [edit, setEdit] = useState<Complaint | null>(null);
  const [status, setStatus] = useState('investigating');
  const [resolution, setResolution] = useState('');
  const save = useMutation({
    mutationFn: async () => (await api.put(`/sales/complaints/${edit!.id}`, { status, resolution })).data,
    onSuccess: () => { qc.invalidateQueries({ queryKey: ['sales'] }); setEdit(null); }, onError: (e) => toast.error(apiErrorMessage(e, tx('Could not save'))),
  });
  return (
    <div className="space-y-4">
      <ListPageHeader icon={MessageSquareWarning} title={tx('Complaints')} description={tx('Quality, quantity, delivery and billing complaints; approved delivery discrepancies open one automatically.')} />
      <div className="overflow-x-auto rounded-xl border bg-card shadow-sm">
        <table className="w-full min-w-[760px] text-sm">
          <thead className="bg-muted/40 text-left text-xs font-semibold uppercase text-muted-foreground"><tr>
            <th className="px-3 py-2">{tx('Number')}</th><th className="px-3 py-2">{tx('Customer')}</th><th className="px-3 py-2">{tx('Order')}</th>
            <th className="px-3 py-2">{tx('Category')}</th><th className="px-3 py-2">{tx('Description')}</th><th className="px-3 py-2">{tx('Status')}</th><th className="px-3 py-2">{tx('Return')}</th></tr></thead>
          <tbody>
            {(data ?? []).map((c) => (
              <tr key={c.id} className="cursor-pointer border-t hover:bg-muted/30" onClick={() => { if (canWrite) { setEdit(c); setStatus(c.status); setResolution(c.resolution ?? ''); } }}>
                <td className="px-3 py-2 font-medium">{c.number}</td><td className="px-3 py-2">{c.partnerName}</td><td className="px-3 py-2">{c.orderNumber ?? '—'}</td>
                <td className="px-3 py-2">{c.category}</td><td className="px-3 py-2">{c.description}</td><td className="px-3 py-2">{c.status}</td><td className="px-3 py-2">{c.returnNumber ?? '—'}</td>
              </tr>
            ))}
            {data && data.length === 0 && <tr><td colSpan={7} className="p-6 text-center text-muted-foreground">{tx('No complaints.')}</td></tr>}
          </tbody>
        </table>
      </div>
      {edit && (
        <Dialog open onOpenChange={(v) => !v && setEdit(null)}>
          <DialogContent className="max-w-md">
            <DialogHeader><DialogTitle>{edit.number}</DialogTitle></DialogHeader>
            <Field label={tx('Status')}><select className="h-9 w-full rounded-md border bg-background px-2 text-sm" value={status} onChange={(e) => setStatus(e.target.value)}>
              <option value="open">{tx('Open')}</option><option value="investigating">{tx('Investigating')}</option><option value="resolved">{tx('Resolved')}</option><option value="closed">{tx('Closed')}</option></select></Field>
            <Field label={tx('Resolution')}><Textarea rows={3} value={resolution} onChange={(e) => setResolution(e.target.value)} /></Field>
            <DialogFooter><Button variant="outline" onClick={() => setEdit(null)}>{tx('Cancel')}</Button><Button onClick={() => save.mutate()}>{tx('Save')}</Button></DialogFooter>
          </DialogContent>
        </Dialog>
      )}
    </div>
  );
}

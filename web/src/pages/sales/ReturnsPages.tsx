import { useState } from 'react';
import { idempotentPost } from '@/lib/idempotent-request';
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
import { useAsk } from '@/components/ask-dialog';

interface ReturnLine {
  id: string; orderLineId: string; description: string; quantity: number; receivedQty: number; disposition: string | null; creditQty: number | null; inspectionNotes: string | null;
  /** ADR-S094 §7.2: the delivery the goods come back from, the serial numbers that came back and the condition found. */
  loadLineId?: string | null; loadCode?: string | null; serialNumbers?: string[] | null; serialTracking?: boolean | null; condition?: string | null;
}
/** What an order line can still come back from, per delivery (GET /sales/returns/returnable). */
interface Returnable {
  orderLineId: string; description: string; serialTracking: boolean; returnable: number;
  deliveries: Array<{ loadLineId: string; loadCode: string; deliveredAt: string | null; delivered: number; returnable: number; serialNumbers: string[] }>;
}
const CONDITIONS: Record<string, string> = { usable: tx('Usable'), damaged: tx('Damaged'), defective: tx('Defective') };
const serialList = (v?: string) => (v ?? '').split(/[\s,;]+/).map((x) => x.trim()).filter(Boolean);
interface SalesReturn {
  id: string; number: string; orderId: string; orderNumber: string; partnerName: string; status: string; reason: string; physicalReturn: boolean;
  outcome: string | null; creditNoteId: string | null; refundPaymentId: string | null; replacementOrderId: string | null; createdAt: string; lines?: ReturnLine[];
}
interface Complaint { id: string; number: string; partnerName: string; orderNumber: string | null; category: string; description: string; status: string; returnNumber: string | null; createdAt: string; resolution: string | null }

const REASONS: Record<string, string> = {
  wrong_grade: tx('Wrong grade'), damaged: tx('Damaged'), short_quantity: tx('Short quantity'), excess_quantity: tx('Excess quantity'),
  quality_rejection: tx('Quality rejection'), other: tx('Other'),
};

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
  const [disp, setDisp] = useState<Record<string, { disposition: string; locationId?: string; rejectedQty?: number; rejectReason?: string; condition?: string }>>({});
  const [recv, setRecv] = useState<Record<string, { receivedQty?: number; serials?: string }>>({});
  const [outcome, setOutcome] = useState('credit_note');
  const act = useMutation({
    mutationFn: async ({ action, body }: { action: string; body?: Record<string, unknown> }) =>
      ['receive', 'inspect', 'close'].includes(action)
        ? idempotentPost<SalesReturn>(`/sales/returns/${id}/${action}`, body ?? {})
        : (await api.post<SalesReturn>(`/sales/returns/${id}/${action}`, body ?? {})).data,
    onSuccess: () => { qc.invalidateQueries({ queryKey: ['sales'] }); toast.success(tx('Done')); },
    onError: (e) => toast.error(apiErrorMessage(e, tx('Action failed'))),
  });
  const { ask, dialog } = useAsk();
  if (!r) return <Skeleton className="h-64 w-full" />;
  const canWrite = has(PERMISSIONS.sales.returnWrite);
  const canApprove = has(PERMISSIONS.sales.returnApprove);
  const closable = r.physicalReturn ? r.status === 'inspected' : r.status === 'approved';
  return (
    <div className="space-y-4">
      {dialog}
      <div className="text-sm"><button className="font-medium text-primary hover:underline" onClick={() => navigate('/sales/returns')}>{tx('Returns')}</button> / <strong>{r.number}</strong> · {r.status}
        <button className="ml-3 text-primary hover:underline" onClick={() => navigate(`/sales/orders/${r.orderId}`)}>{r.orderNumber}</button></div>
      <div className="flex flex-wrap gap-2">
        {r.status === 'requested' && canApprove && <Button onClick={() => act.mutate({ action: 'approve' })}>{tx('Approve')}</Button>}
        {['requested', 'approved'].includes(r.status) && canApprove && <Button variant="outline" onClick={async () => { const reason = await ask({ title: `${tx('Reject')} ${r.number}?`, label: tx('Reason'), confirmLabel: tx('Reject'), destructive: true }); if (reason) act.mutate({ action: 'reject', body: { reason } }); }}>{tx('Reject')}</Button>}
        {r.status === 'approved' && r.physicalReturn && canWrite && <Button onClick={() => act.mutate({ action: 'receive', body: { lines: (r.lines ?? []).map((l) => ({
          lineId: l.id, ...(recv[l.id]?.receivedQty != null ? { receivedQty: recv[l.id]?.receivedQty } : {}), serialNumbers: serialList(recv[l.id]?.serials),
        })) } })}>{tx('Receive goods')}</Button>}
        {/* Every line is sent with the disposition its select shows ("Restock" until changed, the server's own default). */}
        {r.status === 'received' && canWrite && <Button onClick={() => act.mutate({ action: 'inspect', body: { lines: (r.lines ?? []).map((l) => ({ lineId: l.id, ...disp[l.id], disposition: disp[l.id]?.disposition ?? 'restock' })) } })}>{tx('Record inspection')}</Button>}
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
          <thead className="text-left text-xs text-muted-foreground"><tr><th>{tx('Line')}</th><th>{tx('Delivery')}</th><th className="text-right">{tx('Claimed')}</th><th className="text-right">{tx('Received')}</th><th>{tx('Disposition')}</th><th className="text-right">{tx('Credited')}</th></tr></thead>
          <tbody>
            {(r.lines ?? []).map((l) => (
              <tr key={l.id} className="border-t">
                <td className="py-1">{l.description}{(l.serialNumbers?.length ?? 0) > 0 && <div className="text-xs text-muted-foreground">{tx('Serials')}: {l.serialNumbers!.join(', ')}</div>}</td>
                <td className="py-1">{l.loadCode ?? '—'}</td>
                <td className="py-1 text-right">{Number(l.quantity)}</td>
                <td className="py-1 text-right">{r.status === 'approved' && r.physicalReturn && canWrite ? (
                  <div className="flex flex-col items-end gap-1">
                    <Input aria-label={tx('Received quantity')} className="h-8 w-20" type="number" min={0} max={Number(l.quantity)} placeholder={String(Number(l.quantity))}
                      onChange={(e) => setRecv({ ...recv, [l.id]: { ...recv[l.id], receivedQty: e.target.value === '' ? undefined : Number(e.target.value) } })} />
                    {l.serialTracking && <Input aria-label={tx('Serial numbers that came back')} className="h-8 w-56" placeholder={tx('Serial numbers, comma separated')}
                      onChange={(e) => setRecv({ ...recv, [l.id]: { ...recv[l.id], serials: e.target.value } })} />}
                  </div>) : Number(l.receivedQty)}</td>
                <td className="py-1">{r.status === 'received' ? (
                  <div className="flex gap-1">
                    <select className="h-8 rounded border bg-background px-1" value={disp[l.id]?.disposition ?? 'restock'} onChange={(e) => setDisp({ ...disp, [l.id]: { ...disp[l.id], disposition: e.target.value } })}>
                      <option value="restock">{tx('Restock')}</option><option value="quarantine">{tx('Quarantine')}</option><option value="damaged">{tx('Damaged')}</option><option value="scrap">{tx('Scrap')}</option>
                    </select>
                    <select aria-label={tx('Condition')} className="h-8 rounded border bg-background px-1" value={disp[l.id]?.condition ?? ''} onChange={(e) => setDisp({ ...disp, [l.id]: { ...disp[l.id], disposition: disp[l.id]?.disposition ?? 'restock', condition: e.target.value || undefined } })}>
                      <option value="">{tx('Condition…')}</option>{Object.entries(CONDITIONS).map(([k, v]) => <option key={k} value={k}>{v}</option>)}
                    </select>
                    {['quarantine', 'damaged'].includes(disp[l.id]?.disposition ?? '') && <HeaderSelect value={disp[l.id]?.locationId ?? ''} options={options.locations} none={tx('Location…')} onChange={(locationId) => setDisp({ ...disp, [l.id]: { ...disp[l.id], locationId } })} />}
                    <Input aria-label={tx('Rejected quantity')} title={tx('Rejected quantity: refused, goes back to the customer, no credit')} className="h-8 w-20" type="number" min={0} placeholder={tx('Reject')} onChange={(e) => setDisp({ ...disp, [l.id]: { ...disp[l.id], disposition: disp[l.id]?.disposition ?? 'restock', rejectedQty: Number(e.target.value) } })} />
                    {Number(disp[l.id]?.rejectedQty ?? 0) > 0 && <Input className="h-8 w-40" placeholder={tx('Why rejected')} onChange={(e) => setDisp({ ...disp, [l.id]: { ...disp[l.id], rejectReason: e.target.value } })} />}
                  </div>) : <>{l.disposition ?? '—'}{l.condition && <span className="ml-1 text-xs text-muted-foreground">({CONDITIONS[l.condition] ?? l.condition})</span>}</>}</td>
                <td className="py-1 text-right">{l.creditQty == null ? '—' : Number(l.creditQty)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <AttachmentPanel resType="sales_return" resId={r.id} />
    </div>
  );
}

/** Start a return from a sales order. */
export function NewReturnDialog({ order, onClose }: { order: SalesOrder; onClose: () => void }) {
  const navigate = useNavigate();
  const canWriteOff = useAuthStore((s) => s.hasPermission(PERMISSIONS.sales.returnWriteOff));
  const [reason, setReason] = useState('damaged');
  const [physical, setPhysical] = useState(true);
  const [qty, setQty] = useState<Record<string, number>>({});
  const [from, setFrom] = useState<Record<string, string>>({});
  const { data: returnable } = useQuery({
    queryKey: ['sales', 'returnable', order.id],
    queryFn: async () => (await api.get<Returnable[]>('/sales/returns/returnable', { params: { orderId: order.id } })).data,
  });
  const create = useMutation({
    mutationFn: async () => (await api.post<SalesReturn>('/sales/returns', {
      orderId: order.id, reason, physicalReturn: physical, lines: Object.entries(qty).filter(([, q]) => q > 0).map(([orderLineId, quantity]) => ({
        orderLineId, quantity, ...(physical && from[orderLineId] ? { loadLineId: from[orderLineId] } : {}),
      })),
    })).data,
    onSuccess: (r) => navigate(`/sales/returns/${r.id}`), onError: (e) => toast.error(apiErrorMessage(e, tx('Could not create the return'))),
  });
  return (
    <Dialog open onOpenChange={(v) => !v && onClose()}>
      <DialogContent className="max-w-lg">
        <DialogHeader><DialogTitle>{tx('New return')} — {order.number}</DialogTitle></DialogHeader>
        <Field label={tx('Reason')}><select className="h-9 w-full rounded-md border bg-background px-2 text-sm" value={reason} onChange={(e) => setReason(e.target.value)}>
          {Object.entries(REASONS).map(([k, v]) => <option key={k} value={k}>{v}</option>)}</select></Field>
        <label className="flex items-center gap-2 text-sm"><input type="checkbox" checked={physical} disabled={physical && !canWriteOff} onChange={(e) => setPhysical(e.target.checked)} />{tx('Goods come back')}</label>
        {!physical && <p className="text-xs text-muted-foreground">{tx('A claim without goods credits what the customer keeps or lost (a write-off). Someone else approves it.')}</p>}
        {(returnable ?? []).map((l) => {
          const open = l.deliveries.filter((d) => d.returnable > 0);
          return (
            <Field key={l.orderLineId} label={`${l.description} (${tx('can come back')} ${Number(l.returnable)})`}>
              <div className="flex gap-2">
                <Input type="number" min={0} max={Number(l.returnable)} value={qty[l.orderLineId] ?? 0} onChange={(e) => setQty({ ...qty, [l.orderLineId]: Number(e.target.value) })} />
                {physical && open.length > 1 && (
                  <select aria-label={tx('Delivery')} className="h-9 rounded-md border bg-background px-2 text-sm" value={from[l.orderLineId] ?? ''} onChange={(e) => setFrom({ ...from, [l.orderLineId]: e.target.value })}>
                    <option value="">{tx('From delivery…')}</option>
                    {open.map((d) => <option key={d.loadLineId} value={d.loadLineId}>{d.loadCode}{d.deliveredAt ? ` · ${date(d.deliveredAt)}` : ''} · {Number(d.returnable)}</option>)}
                  </select>
                )}
              </div>
            </Field>
          );
        })}
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

import { useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { toast } from 'sonner';
import { Camera, CheckCircle2, FileText, PackageCheck, Truck } from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Dialog, DialogContent, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs';
import { ListPageHeader } from '@/components/list';
import { api } from '@/lib/api';
import { apiErrorMessage } from '@/lib/api-error';
import { date } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';
import { Field } from '@/pages/crm/crm-ui';
import { useAuthStore } from '@/stores/auth.store';

interface Delivery { id: string; code: string; status: string; vehiclePlate: string | null; driverName: string | null; plannedDate: string | null; dispatchedAt: string | null; deliveredAt: string | null; deliveryNoteNumber: string | null; approvalRequired: boolean; approvedAt: string | null; manualGross: number | null; manualTare: number | null; cancelReason: string | null; returnReason: string | null; orderId: string; orderNumber: string | null; partnerName: string | null; dispatchedQty: number; deliveredQty: number; damagedQty: number; rejectedQty: number;
  lines?: Array<{ id: string; productName: string; uomCode: string | null; plannedQty: number; loadedQty: number | null; dispatchedQty: number | null; deliveredQty: number | null; damagedQty: number; rejectedQty: number }>;
  pod?: { receiverName: string; receiverPhone: string | null; receivedAt: string; notes: string | null } | null;
  photos?: Array<{ id: string; fileId: string; caption: string | null }> }
interface Recon { orderId: string; lineId: string; number: string; partnerName: string | null; description: string; ordered: number; dispatched: number; accepted: number; damaged: number; rejected: number; billable: number; invoiced: number; deliveryVariance: number; toInvoice: number; percentDelivered: number }

const n = (v: unknown) => Number(v ?? 0);
const th = 'px-3 py-2';
const key = () => ({ headers: { 'Idempotency-Key': crypto.randomUUID() } });

function DeliveryTable({ rows }: { rows: Delivery[] }) {
  const navigate = useNavigate();
  return (
    <div className="overflow-x-auto rounded-xl border bg-card shadow-sm">
      <table className="w-full min-w-[820px] text-sm">
        <thead className="bg-muted/40 text-left text-xs font-semibold uppercase text-muted-foreground"><tr><th className={th}>{tx('Delivery')}</th><th className={th}>{tx('Order')}</th><th className={th}>{tx('Customer')}</th><th className={th}>{tx('Vehicle')}</th><th className={th}>{tx('Status')}</th><th className={`${th} text-right`}>{tx('Sent')}</th><th className={`${th} text-right`}>{tx('Delivered')}</th><th className={`${th} text-right`}>{tx('Damaged')}</th><th className={`${th} text-right`}>{tx('Rejected')}</th></tr></thead>
        <tbody>
          {rows.map((d) => (
            <tr key={d.id} className="cursor-pointer border-t hover:bg-muted/30" onClick={() => navigate(`/sales/deliveries/${d.id}`)}>
              <td className={`${th} font-medium`}>{d.code}</td><td className={th}>{d.orderNumber}</td><td className={th}>{d.partnerName}</td><td className={th}>{d.vehiclePlate ?? '—'}</td><td className={th}>{tx(d.status)}</td>
              <td className={`${th} text-right tabular-nums`}>{n(d.dispatchedQty)}</td><td className={`${th} text-right tabular-nums`}>{n(d.deliveredQty)}</td><td className={`${th} text-right tabular-nums`}>{n(d.damagedQty)}</td><td className={`${th} text-right tabular-nums`}>{n(d.rejectedQty)}</td>
            </tr>
          ))}
          {!rows.length && <tr><td colSpan={9} className="p-6 text-center text-muted-foreground">{tx('Nothing to show.')}</td></tr>}
        </tbody>
      </table>
    </div>
  );
}

/** All sales deliveries, the failed ones, and order lines reconciled against what moved. */
export function DeliveriesPage() {
  const all = useQuery({ queryKey: ['sales', 'deliveries'], queryFn: async () => (await api.get<Delivery[]>('/sales/deliveries')).data });
  const failed = useQuery({ queryKey: ['sales', 'deliveries', 'failed'], queryFn: async () => (await api.get<Delivery[]>('/sales/deliveries', { params: { failed: true } })).data });
  const recon = useQuery({ queryKey: ['sales', 'deliveries', 'recon'], queryFn: async () => (await api.get<Recon[]>('/sales/deliveries/reconciliation')).data });
  return (
    <div className="space-y-4">
      <ListPageHeader icon={Truck} title={tx('Deliveries')} description={tx('Delivery notes, proof of delivery, damaged and rejected goods. Start a delivery from a confirmed sales order.')} />
      <Tabs defaultValue="all">
        <TabsList><TabsTrigger value="all">{tx('All')}</TabsTrigger><TabsTrigger value="failed">{tx('Failed or short')}</TabsTrigger><TabsTrigger value="recon">{tx('Reconciliation')}</TabsTrigger></TabsList>
        <TabsContent value="all" className="mt-3"><DeliveryTable rows={all.data ?? []} /></TabsContent>
        <TabsContent value="failed" className="mt-3"><DeliveryTable rows={failed.data ?? []} /></TabsContent>
        <TabsContent value="recon" className="mt-3">
          <div className="overflow-x-auto rounded-xl border bg-card shadow-sm">
            <table className="w-full min-w-[960px] text-sm">
              <thead className="bg-muted/40 text-left text-xs font-semibold uppercase text-muted-foreground"><tr><th className={th}>{tx('Order')}</th><th className={th}>{tx('Line')}</th>{['Ordered', 'Sent', 'Accepted', 'Damaged', 'Rejected', 'Billable', 'Invoiced', 'To invoice', '% delivered'].map((h) => <th key={h} className={`${th} text-right`}>{tx(h)}</th>)}</tr></thead>
              <tbody>
                {(recon.data ?? []).map((r) => (
                  <tr key={r.lineId} className="border-t"><td className={th}><Link className="text-primary hover:underline" to={`/sales/orders/${r.orderId}`}>{r.number}</Link></td><td className={th}>{r.description}</td>
                    {[r.ordered, r.dispatched, r.accepted, r.damaged, r.rejected, r.billable, r.invoiced, r.toInvoice, r.percentDelivered].map((v, i) => <td key={i} className={`${th} text-right tabular-nums`}>{n(v)}</td>)}</tr>
                ))}
              </tbody>
            </table>
          </div>
        </TabsContent>
      </Tabs>
    </div>
  );
}

/** One delivery: the note, stock issue, proof of delivery with damaged and rejected quantities, and photos. */
export function DeliveryPage() {
  const { id } = useParams();
  const qc = useQueryClient();
  const qk = ['sales', 'delivery', id];
  const { data: d } = useQuery({ queryKey: qk, queryFn: async () => (await api.get<Delivery>(`/sales/deliveries/${id}`)).data });
  const canWrite = useAuthStore((s) => s.hasPermission(PERMISSIONS.sales.deliveryWrite));
  const canApprove = useAuthStore((s) => s.hasPermission(PERMISSIONS.sales.deliveryApprove));
  const [receiver, setReceiver] = useState('');
  const [phone, setPhone] = useState('');
  const [entry, setEntry] = useState<Record<string, { deliveredQty: number; damagedQty: number; rejectedQty: number; reason: string }>>({});
  const [podOpen, setPodOpen] = useState(false);
  const done = () => { toast.success(tx('Done')); void qc.invalidateQueries({ queryKey: ['sales'] }); };
  const fail = (e: unknown) => toast.error(apiErrorMessage(e, tx('Action failed')));
  if (!d) return <p className="text-sm text-muted-foreground">{tx('Loading…')}</p>;
  const openPod = () => {
    setEntry(Object.fromEntries((d.lines ?? []).map((l) => [l.id, { deliveredQty: n(l.dispatchedQty), damagedQty: 0, rejectedQty: 0, reason: '' }])));
    setPodOpen(true);
  };
  const submitPod = () => api.post(`/sales/deliveries/${id}/confirm`, { receiverName: receiver, receiverPhone: phone || undefined, lines: Object.entries(entry).map(([loadLineId, e]) => ({ loadLineId, ...e, reason: e.reason || undefined })) }, key()).then(() => { setPodOpen(false); done(); }).catch(fail);
  const photo = async (f: File | undefined) => {
    if (!f) return;
    const dataUrl = await new Promise<string>((res, rej) => { const r = new FileReader(); r.onload = () => res(String(r.result)); r.onerror = rej; r.readAsDataURL(f); });
    api.post(`/sales/deliveries/${id}/photos`, { dataUrl }).then(done).catch(fail);
  };
  const note = async () => {
    try {
      const r = await api.get(`/sales/deliveries/${id}/note`, { responseType: 'blob' });
      const url = URL.createObjectURL(r.data as Blob);
      const a = document.createElement('a'); a.href = url; a.download = `${d.deliveryNoteNumber ?? d.code}.pdf`; a.click(); URL.revokeObjectURL(url);
    } catch (e) { fail(e); }
  };
  const waiting = d.approvalRequired && !d.approvedAt;
  return (
    <div className="space-y-4">
      <div className="text-sm"><Link to="/sales/deliveries" className="font-medium text-primary hover:underline">{tx('Deliveries')}</Link><span className="mx-1.5 text-muted-foreground">/</span><span className="font-semibold">{d.code}</span><span className="ml-3 text-muted-foreground">{tx(d.status)}</span></div>
      <div className="flex flex-wrap gap-2">
        {waiting && canApprove && <Button onClick={() => api.post(`/sales/deliveries/${id}/approve`).then(done).catch(fail)}><CheckCircle2 className="mr-1.5 h-4 w-4" />{tx('Approve')}</Button>}
        {d.status === 'planned' && canWrite && <Button disabled={waiting} onClick={() => api.post(`/sales/deliveries/${id}/issue`, null, key()).then(done).catch(fail)}><PackageCheck className="mr-1.5 h-4 w-4" />{tx('Issue stock')}</Button>}
        {['dispatched', 'in_transit'].includes(d.status) && canWrite && <Button onClick={openPod}><CheckCircle2 className="mr-1.5 h-4 w-4" />{tx('Confirm delivery')}</Button>}
        {d.deliveryNoteNumber && <Button variant="outline" onClick={note}><FileText className="mr-1.5 h-4 w-4" />{tx('Delivery note')}</Button>}
        {canWrite && <label className="inline-flex h-9 cursor-pointer items-center rounded-md border bg-background px-3 text-sm hover:bg-muted"><Camera className="mr-1.5 h-4 w-4" />{tx('Add photo')}<input type="file" accept="image/*" className="hidden" onChange={(e) => { void photo(e.target.files?.[0]); e.target.value = ''; }} /></label>}
      </div>
      {waiting && <div className="rounded-xl border border-amber-200 bg-amber-50 p-3 text-sm text-amber-900 dark:bg-transparent">{tx('Waiting for approval before stock can be issued.')}</div>}
      <section className="grid gap-3 rounded-xl border bg-card p-5 text-sm shadow-sm sm:grid-cols-2 lg:grid-cols-4">
        <Field label={tx('Order')}><Link className="text-primary hover:underline" to={`/sales/orders/${d.orderId}`}>{d.orderNumber}</Link></Field>
        <Field label={tx('Customer')}><p>{d.partnerName}</p></Field>
        <Field label={tx('Vehicle')}><p>{d.vehiclePlate ?? '—'}</p></Field>
        <Field label={tx('Driver')}><p>{d.driverName ?? '—'}</p></Field>
        <Field label={tx('Delivery note')}><p>{d.deliveryNoteNumber ?? '—'}</p></Field>
        <Field label={tx('Issued')}><p>{d.dispatchedAt ? date(d.dispatchedAt) : '—'}</p></Field>
        <Field label={tx('Delivered')}><p>{d.deliveredAt ? date(d.deliveredAt) : '—'}</p></Field>
        {d.manualGross != null && <Field label={tx('Gross / tare / net')}><p>{n(d.manualGross)} / {n(d.manualTare)} / {n(d.manualGross) - n(d.manualTare)}</p></Field>}
      </section>
      <div className="overflow-x-auto rounded-xl border bg-card shadow-sm">
        <table className="w-full text-sm"><thead className="bg-muted/40 text-left text-xs font-semibold uppercase text-muted-foreground"><tr><th className={th}>{tx('Product')}</th>{['Planned', 'Sent', 'Delivered', 'Damaged', 'Rejected'].map((h) => <th key={h} className={`${th} text-right`}>{tx(h)}</th>)}</tr></thead>
          <tbody>{(d.lines ?? []).map((l) => <tr key={l.id} className="border-t"><td className={th}>{l.productName} {l.uomCode && <span className="text-muted-foreground">({l.uomCode})</span>}</td>{[l.plannedQty, l.dispatchedQty, l.deliveredQty, l.damagedQty, l.rejectedQty].map((v, i) => <td key={i} className={`${th} text-right tabular-nums`}>{n(v)}</td>)}</tr>)}</tbody></table>
      </div>
      {d.pod && <p className="text-sm">{tx('Received by')} <strong>{d.pod.receiverName}</strong> {d.pod.receiverPhone} · {date(d.pod.receivedAt)}</p>}
      {(d.photos?.length ?? 0) > 0 && <p className="text-sm text-muted-foreground">{d.photos!.length} {tx('photos attached')}</p>}
      <Dialog open={podOpen} onOpenChange={setPodOpen}>
        <DialogContent className="max-w-2xl">
          <DialogHeader><DialogTitle>{tx('Confirm delivery')}</DialogTitle></DialogHeader>
          <div className="grid gap-3 sm:grid-cols-2"><Field label={tx('Received by')}><Input value={receiver} onChange={(e) => setReceiver(e.target.value)} /></Field><Field label={tx('Phone')}><Input value={phone} onChange={(e) => setPhone(e.target.value)} /></Field></div>
          <table className="w-full text-sm"><thead className="text-left text-xs text-muted-foreground"><tr><th>{tx('Product')}</th><th className="w-20">{tx('Delivered')}</th><th className="w-20">{tx('Damaged')}</th><th className="w-20">{tx('Rejected')}</th></tr></thead>
            <tbody>{(d.lines ?? []).map((l) => { const e = entry[l.id]; if (!e) return null; const set = (p: Partial<typeof e>) => setEntry((x) => ({ ...x, [l.id]: { ...e, ...p } })); return (
              <tr key={l.id} className="border-t"><td className="py-1">{l.productName}</td>
                <td className="py-1 pl-1"><Input type="number" min={0} value={e.deliveredQty} onChange={(v) => set({ deliveredQty: Number(v.target.value) })} /></td>
                <td className="py-1 pl-1"><Input type="number" min={0} value={e.damagedQty} onChange={(v) => set({ damagedQty: Number(v.target.value) })} /></td>
                <td className="py-1 pl-1"><Input type="number" min={0} value={e.rejectedQty} onChange={(v) => set({ rejectedQty: Number(v.target.value) })} /></td></tr>); })}</tbody></table>
          <DialogFooter><Button variant="outline" onClick={() => setPodOpen(false)}>{tx('Cancel')}</Button><Button disabled={!receiver.trim()} onClick={submitPod}>{tx('Confirm')}</Button></DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  );
}

/** New delivery from a sales order: vehicle and driver typed in, optional weighing. */
export function NewDeliveryDialog({ orderId, onClose }: { orderId: string; onClose: () => void }) {
  const navigate = useNavigate();
  const [plate, setPlate] = useState('');
  const [driver, setDriver] = useState('');
  const [gross, setGross] = useState('');
  const [tare, setTare] = useState('');
  const submit = () => api.post(`/sales/orders/${orderId}/deliveries`, { vehiclePlate: plate, driverName: driver || undefined, ...(gross && tare ? { gross: Number(gross), tare: Number(tare) } : {}) }).then((r) => navigate(`/sales/deliveries/${r.data.id}`)).catch((e) => toast.error(apiErrorMessage(e, tx('Could not create the delivery'))));
  return (
    <Dialog open onOpenChange={(v) => !v && onClose()}>
      <DialogContent className="max-w-md">
        <DialogHeader><DialogTitle>{tx('New delivery')}</DialogTitle></DialogHeader>
        <div className="grid gap-3 sm:grid-cols-2">
          <Field label={tx('Vehicle plate')}><Input value={plate} onChange={(e) => setPlate(e.target.value)} /></Field>
          <Field label={tx('Driver')}><Input value={driver} onChange={(e) => setDriver(e.target.value)} /></Field>
          <Field label={tx('Gross weight')}><Input type="number" min={0} value={gross} onChange={(e) => setGross(e.target.value)} /></Field>
          <Field label={tx('Tare weight')}><Input type="number" min={0} value={tare} onChange={(e) => setTare(e.target.value)} /></Field>
        </div>
        <p className="text-xs text-muted-foreground">{tx('Weights are optional. With both, the quantity sent is gross minus tare.')}</p>
        <DialogFooter><Button variant="outline" onClick={onClose}>{tx('Cancel')}</Button><Button disabled={!plate.trim()} onClick={submit}>{tx('Create')}</Button></DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

/** Milestone plan of a sales order, billed one by one. */
export function MilestonesPanel({ orderId, total }: { orderId: string; total: number }) {
  const qc = useQueryClient();
  const qk = ['sales', 'milestones', orderId];
  const { data } = useQuery({ queryKey: qk, queryFn: async () => (await api.get<Array<{ id: string; name: string; percent: number | null; amount: number; dueDate: string | null; status: string; documentNumber: string | null }>>(`/sales/orders/${orderId}/milestones`)).data, retry: false });
  const [name, setName] = useState('');
  const [percent, setPercent] = useState('');
  const canInvoice = useAuthStore((s) => s.hasPermission(PERMISSIONS.sales.invoiceCreate));
  const rows = data ?? [];
  const fail = (e: unknown) => toast.error(apiErrorMessage(e, tx('Action failed')));
  const add = () => api.put(`/sales/orders/${orderId}/milestones`, { milestones: [...rows.filter((r) => r.status === 'pending').map((r) => ({ name: r.name, amount: Number(r.amount), dueDate: r.dueDate })), { name, percent: Number(percent) }] }).then(() => { setName(''); setPercent(''); void qc.invalidateQueries({ queryKey: qk }); }).catch(fail);
  return (
    <div className="rounded-xl border bg-card p-4 text-sm shadow-sm">
      <h2 className="mb-2 font-semibold">{tx('Milestone billing')}</h2>
      <ul className="space-y-1">{rows.map((m) => (
        <li key={m.id} className="flex items-center justify-between rounded border p-2"><span>{m.name} · {Number(m.amount).toLocaleString()} {m.documentNumber && <span className="text-xs text-muted-foreground">({m.documentNumber})</span>}</span>
          {m.status === 'pending' && canInvoice && <Button size="sm" variant="outline" onClick={() => api.post(`/sales/orders/${orderId}/milestones/${m.id}/invoice`, null, key()).then(() => qc.invalidateQueries({ queryKey: qk })).catch(fail)}>{tx('Invoice')}</Button>}</li>
      ))}</ul>
      <div className="mt-2 flex gap-2"><Input placeholder={tx('Milestone')} value={name} onChange={(e) => setName(e.target.value)} /><Input className="w-24" type="number" min={0} max={100} placeholder="%" value={percent} onChange={(e) => setPercent(e.target.value)} /><Button size="sm" variant="outline" disabled={!name.trim() || !Number(percent)} onClick={add}>{tx('Add')}</Button></div>
      <p className="mt-1 text-xs text-muted-foreground">{tx('Order total')}: {Number(total).toLocaleString()}</p>
    </div>
  );
}

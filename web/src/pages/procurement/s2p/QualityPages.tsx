import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { useQueryClient } from '@tanstack/react-query';
import { ShieldCheck, Siren } from 'lucide-react';
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
  emergencyApi,
  errMsg,
  inspectionApi,
  useEmergencyReport,
  useInspection,
  useInspectionMutation,
  useInspections,
  usePoLifecycle,
  type InspectionStatus,
} from '@/features/procurement/api';
import { notify } from '@/lib/notify';
import { date, money } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';

const TONE: Record<InspectionStatus, 'neutral' | 'info' | 'success' | 'warning' | 'danger'> = {
  pending: 'warning', passed: 'success', partial: 'info', failed: 'danger', cancelled: 'neutral',
};
const LABEL: Record<InspectionStatus, string> = {
  pending: tx('To inspect'), passed: tx('Passed'), partial: tx('Partly rejected'), failed: tx('Rejected'), cancelled: tx('Cancelled'),
};

/**
 * Incoming inspection (ADR-PROCUREMENT-001 wave 6): receipts waiting for a quality check, line results (accept /
 * reject with defect notes) and completion. Rejected goods become a quality claim against the supplier automatically.
 */
export function InspectionsPage() {
  const has = useAuthStore((s) => s.hasPermission);
  const canManage = has(PERMISSIONS.inspection.manage);
  const [status, setStatus] = useState('pending');
  const { data: rows = [] } = useInspections(status);
  const [openId, setOpenId] = useState<string | null>(null);

  return (
    <div className="space-y-4 p-6">
      <ListPageHeader title={tx('Incoming inspection')} icon={ShieldCheck}
        description={tx('Check delivered goods before they are paid for. Rejected quantities are claimed back from the supplier.')} />
      <select className="h-9 rounded-md border bg-background px-2 text-sm" value={status} onChange={(e) => setStatus(e.target.value)}>
        {(Object.keys(LABEL) as InspectionStatus[]).map((s) => <option key={s} value={s}>{LABEL[s]}</option>)}
        <option value="">{tx('All')}</option>
      </select>
      <Card>
        <CardContent className="p-0">
          <Table>
            <TableHeader><TableRow>
              <TableHead>{tx('Inspection')}</TableHead><TableHead>{tx('Receipt')}</TableHead><TableHead>{tx('Order')}</TableHead>
              <TableHead>{tx('Supplier')}</TableHead><TableHead>{tx('Status')}</TableHead><TableHead>{tx('Claim')}</TableHead><TableHead />
            </TableRow></TableHeader>
            <TableBody>
              {!rows.length && <TableRow><TableCell colSpan={7} className="py-8 text-center text-muted-foreground">{tx('Nothing to inspect.')}</TableCell></TableRow>}
              {rows.map((r) => (
                <TableRow key={r.id}>
                  <TableCell className="font-mono text-xs">{r.inspectionNumber}</TableCell>
                  <TableCell><Link className="font-mono text-xs hover:underline" to={`/procurement/goods-receipts/${r.goodsReceiptId}`}>{r.receiptNumber}</Link></TableCell>
                  <TableCell>{r.purchaseOrderId ? <Link className="font-mono text-xs hover:underline" to={`/procurement/purchase-orders/${r.purchaseOrderId}`}>{r.orderNumber}</Link> : '—'}</TableCell>
                  <TableCell>{r.partnerName}</TableCell>
                  <TableCell><StatusPill tone={TONE[r.status]}>{LABEL[r.status]}</StatusPill></TableCell>
                  <TableCell className="font-mono text-xs">{r.claimNumber ?? '—'}</TableCell>
                  <TableCell className="text-right">
                    <Button size="sm" variant={r.status === 'pending' && canManage ? 'default' : 'outline'} onClick={() => setOpenId(r.id)}>
                      {r.status === 'pending' && canManage ? tx('Inspect') : tx('View')}
                    </Button>
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </CardContent>
      </Card>
      {openId && <InspectionDialog id={openId} canManage={canManage} onClose={() => setOpenId(null)} />}
    </div>
  );
}

type LineDraft = { rejected: string; notes: string };

function InspectionDialog({ id, canManage, onClose }: { id: string; canManage: boolean; onClose: () => void }) {
  const { data } = useInspection(id);
  const mutate = useInspectionMutation((fn: () => Promise<unknown>) => fn());
  const [draft, setDraft] = useState<Record<string, LineDraft>>({});
  const [notes, setNotes] = useState('');
  const editable = canManage && data?.status === 'pending';

  useEffect(() => {
    if (!data?.lines) return;
    setDraft(Object.fromEntries(data.lines.map((l) => [l.id, { rejected: String(Number(l.rejectedQuantity)), notes: l.defectNotes ?? '' }])));
    setNotes(data.notes ?? '');
  }, [data]);

  if (!data) return null;
  const save = () => inspectionApi.record(id, {
    lines: (data.lines ?? []).map((l) => ({ lineId: l.id, rejectedQuantity: Number(draft[l.id]?.rejected || 0), defectNotes: draft[l.id]?.notes || undefined })),
    notes: notes || undefined,
  });
  const complete = async () => {
    try {
      await mutate.mutateAsync(save);
      const done = (await mutate.mutateAsync(() => inspectionApi.complete(id))) as { status: InspectionStatus; claimNumber: string | null };
      notify.success(done.claimNumber ? tx('Inspection completed — claim {{n}} raised for the rejected goods', { n: done.claimNumber }) : tx('Inspection completed — all goods accepted'));
      onClose();
    } catch (e) {
      notify.error(tx('Could not complete the inspection'), errMsg(e));
    }
  };

  return (
    <Dialog open onOpenChange={(v) => !v && onClose()}>
      <DialogContent className="max-w-3xl">
        <DialogHeader><DialogTitle>{data.inspectionNumber} · {data.receiptNumber} · {data.partnerName}</DialogTitle></DialogHeader>
        <Table>
          <TableHeader><TableRow>
            <TableHead>{tx('Item')}</TableHead><TableHead className="text-right">{tx('Received')}</TableHead>
            <TableHead className="w-28">{tx('Rejected')}</TableHead><TableHead className="text-right">{tx('Accepted')}</TableHead><TableHead>{tx('Defects')}</TableHead>
          </TableRow></TableHeader>
          <TableBody>
            {(data.lines ?? []).map((l) => {
              const rej = Number(draft[l.id]?.rejected || 0);
              return (
                <TableRow key={l.id}>
                  <TableCell>{l.description}</TableCell>
                  <TableCell className="text-right">{Number(l.receivedQuantity)}</TableCell>
                  <TableCell>
                    {editable ? (
                      <Input type="number" min={0} max={Number(l.receivedQuantity)} value={draft[l.id]?.rejected ?? '0'}
                        onChange={(e) => setDraft((d) => ({ ...d, [l.id]: { ...d[l.id], rejected: e.target.value } }))} />
                    ) : Number(l.rejectedQuantity)}
                  </TableCell>
                  <TableCell className="text-right">{Math.max(0, Number(l.receivedQuantity) - rej)}</TableCell>
                  <TableCell>
                    {editable ? (
                      <Input value={draft[l.id]?.notes ?? ''} placeholder={tx('e.g. torn bags, wet, wrong grade')}
                        onChange={(e) => setDraft((d) => ({ ...d, [l.id]: { ...d[l.id], notes: e.target.value } }))} />
                    ) : (l.defectNotes ?? '—')}
                  </TableCell>
                </TableRow>
              );
            })}
          </TableBody>
        </Table>
        <div>
          <Label>{tx('Notes')}</Label>
          <Textarea value={notes} disabled={!editable} onChange={(e) => setNotes(e.target.value)} />
        </div>
        {data.claimNumber && <p className="text-sm">{tx('Claim')}: <Link className="font-mono hover:underline" to="/procurement/claims">{data.claimNumber}</Link></p>}
        <DialogFooter>
          <Button variant="ghost" onClick={onClose}>{tx('Close')}</Button>
          {editable && (
            <>
              <Button variant="outline" onClick={async () => { try { await mutate.mutateAsync(save); notify.success(tx('Saved')); } catch (e) { notify.error(tx('Could not save'), errMsg(e)); } }}>{tx('Save')}</Button>
              <Button onClick={complete} disabled={mutate.isPending}>{tx('Complete inspection')}</Button>
            </>
          )}
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

/** Emergency purchases waiting for (or past) their after-the-fact review (ADR-PROCUREMENT-001 wave 6). */
export function EmergencyPurchasesPage() {
  const [reviewed, setReviewed] = useState<boolean | undefined>(false);
  const { data: rows = [] } = useEmergencyReport(reviewed);
  const qc = useQueryClient();

  const review = async (id: string) => {
    const note = window.prompt(tx('Review note (was the shortcut justified?)')) ?? '';
    if (!note.trim()) return;
    try {
      await emergencyApi.review(id, note);
      qc.invalidateQueries({ queryKey: ['emergency-purchases'] });
      notify.success(tx('Review recorded'));
    } catch (e) {
      notify.error(tx('Action failed'), errMsg(e));
    }
  };

  return (
    <div className="space-y-4 p-6">
      <ListPageHeader title={tx('Emergency purchases')} icon={Siren}
        description={tx('Orders placed outside the normal process because work could not wait. Each one is reviewed by someone other than the buyer.')} />
      <select className="h-9 rounded-md border bg-background px-2 text-sm" value={reviewed === undefined ? '' : String(reviewed)}
        onChange={(e) => setReviewed(e.target.value === '' ? undefined : e.target.value === 'true')}>
        <option value="false">{tx('Waiting for review')}</option>
        <option value="true">{tx('Reviewed')}</option>
        <option value="">{tx('All')}</option>
      </select>
      <Card>
        <CardContent className="p-0">
          <Table>
            <TableHeader><TableRow>
              <TableHead>{tx('Order')}</TableHead><TableHead>{tx('Supplier')}</TableHead><TableHead>{tx('Date')}</TableHead>
              <TableHead className="text-right">{tx('Total')}</TableHead><TableHead>{tx('Reason')}</TableHead><TableHead>{tx('Review')}</TableHead>
            </TableRow></TableHeader>
            <TableBody>
              {!rows.length && <TableRow><TableCell colSpan={6} className="py-8 text-center text-muted-foreground">{tx('No emergency purchases.')}</TableCell></TableRow>}
              {rows.map((r) => (
                <TableRow key={r.id}>
                  <TableCell><Link className="font-mono text-xs hover:underline" to={`/procurement/purchase-orders/${r.id}`}>{r.orderNumber}</Link></TableCell>
                  <TableCell>{r.partnerName}</TableCell>
                  <TableCell>{date(r.orderDate)}</TableCell>
                  <TableCell className="text-right">{money(r.totalAmount, r.currencyCode)}</TableCell>
                  <TableCell>{r.emergencyReason}</TableCell>
                  <TableCell>
                    {r.emergencyReviewedAt ? <span className="text-sm">{r.emergencyReviewNote}</span>
                      : <Button size="sm" onClick={() => review(r.id)}>{tx('Review')}</Button>}
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

/** "Emergency purchase" flag on the order page. */
export function EmergencyFlag({ id }: { id: string }) {
  const { data: po } = usePoLifecycle(id);
  const isEmergency = po?.isEmergency;
  const reason = po?.emergencyReason;
  const has = useAuthStore((s) => s.hasPermission);
  const qc = useQueryClient();
  const [open, setOpen] = useState(false);
  const [text, setText] = useState('');
  if (isEmergency) {
    return <div className="rounded-md border border-amber-300 bg-amber-50 px-3 py-2 text-sm text-amber-900 dark:bg-amber-950 dark:text-amber-200"><Siren className="mr-1 inline h-4 w-4" />{tx('Emergency purchase')}: {reason}</div>;
  }
  if (!po || po.status === 'cancelled' || !has(PERMISSIONS.procurement.purchaseOrder.update)) return null;
  return (
    <>
      <Button size="sm" variant="ghost" onClick={() => setOpen(true)}><Siren className="mr-1 h-4 w-4" />{tx('Mark as emergency purchase')}</Button>
      <Dialog open={open} onOpenChange={setOpen}>
        <DialogContent>
          <DialogHeader><DialogTitle>{tx('Emergency purchase')}</DialogTitle></DialogHeader>
          <Label>{tx('Why could this not follow the normal process?')}</Label>
          <Textarea value={text} onChange={(e) => setText(e.target.value)} />
          <DialogFooter>
            <Button variant="ghost" onClick={() => setOpen(false)}>{tx('Cancel')}</Button>
            <Button disabled={!text.trim()} onClick={async () => {
              try {
                await emergencyApi.mark(id, text);
                qc.invalidateQueries({ queryKey: ['purchase-order', id] });
                qc.invalidateQueries({ queryKey: ['emergency-purchases'] });
                notify.success(tx('Flagged for review'));
                setOpen(false);
              } catch (e) { notify.error(tx('Action failed'), errMsg(e)); }
            }}>{tx('Flag')}</Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </>
  );
}

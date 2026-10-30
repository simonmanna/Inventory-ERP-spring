import { useState } from 'react';
import { Link } from 'react-router-dom';
import { CheckCircle2, Circle, Printer, Send, XCircle } from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Dialog, DialogContent, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Textarea } from '@/components/ui/textarea';
import { useAuthStore } from '@/stores/auth.store';
import { errMsg, usePoLifecycle, usePoLifecycleAction } from '@/features/procurement/api';
import { notify } from '@/lib/notify';
import { date } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';

const STEPS: Array<{ key: string; label: string }> = [
  { key: 'awaiting_approval', label: tx('Approval') },
  { key: 'approved', label: tx('Approved') },
  { key: 'sent', label: tx('Sent to supplier') },
  { key: 'confirmed', label: tx('Confirmed') },
  { key: 'partially_received', label: tx('Receiving') },
  { key: 'received', label: tx('Received') },
  { key: 'closed', label: tx('Closed') },
];

/**
 * Order progress and the actions after approval (ADR-PROCUREMENT-001 wave 3): a step timeline, send to the supplier
 * (with a printable order), record the supplier's confirmation, short-close an unfinished balance, close.
 */
export function PoLifecycleCard({ id }: { id: string }) {
  const { data } = usePoLifecycle(id);
  const act = usePoLifecycleAction(id);
  const has = useAuthStore((s) => s.hasPermission);
  const canSend = has(PERMISSIONS.procurement.purchaseOrder.send);
  const canClose = has(PERMISSIONS.procurement.purchaseOrder.cancel);
  const [ackOpen, setAckOpen] = useState(false);
  const [ack, setAck] = useState({ supplierReference: '', promisedDate: '' });
  const [closeOpen, setCloseOpen] = useState(false);
  const [reason, setReason] = useState('');
  if (!data) return null;

  const run = async (action: 'send' | 'acknowledge' | 'short-close' | 'close', body?: Record<string, unknown>, ok?: string) => {
    try {
      await act.mutateAsync({ action, body });
      notify.success(ok ?? tx('Order updated'));
      return true;
    } catch (e) {
      notify.error(tx('Action failed'), errMsg(e));
      return false;
    }
  };

  const reached = (key: string) => {
    if (data.status === 'cancelled') return false;
    const order = STEPS.map((s) => s.key);
    const current = data.stage === 'awaiting_approval' ? 0 : order.indexOf(data.stage);
    return order.indexOf(key) <= current;
  };
  const open = data.status === 'active' || data.status === 'partially_received';

  return (
    <Card>
      <CardHeader className="flex flex-row flex-wrap items-center justify-between gap-2">
        <CardTitle>{tx('Order progress')}</CardTitle>
        <div className="flex flex-wrap gap-2">
          <Button size="sm" variant="outline" asChild>
            <Link to={`/procurement/purchase-orders/${id}/print`} target="_blank"><Printer className="mr-1 h-4 w-4" />{tx('Print')}</Link>
          </Button>
          {canSend && open && !data.sentAt && (
            <Button size="sm" onClick={() => run('send', undefined, tx('Marked as sent to the supplier'))}><Send className="mr-1 h-4 w-4" />{tx('Mark as sent')}</Button>
          )}
          {canSend && open && !data.acknowledgedAt && (
            <Button size="sm" variant="outline" onClick={() => setAckOpen(true)}><CheckCircle2 className="mr-1 h-4 w-4" />{tx('Record confirmation')}</Button>
          )}
          {canClose && open && (
            <Button size="sm" variant="ghost" onClick={() => setCloseOpen(true)}><XCircle className="mr-1 h-4 w-4" />{tx('Short-close')}</Button>
          )}
          {canClose && data.status === 'received' && (
            <Button size="sm" variant="outline" onClick={() => run('close', undefined, tx('Order closed'))}>{tx('Close order')}</Button>
          )}
        </div>
      </CardHeader>
      <CardContent className="space-y-3">
        <ol className="flex flex-wrap items-center gap-x-2 gap-y-1 text-xs">
          {STEPS.map((s, i) => (
            <li key={s.key} className="flex items-center gap-1">
              {reached(s.key) ? <CheckCircle2 className="h-4 w-4 text-emerald-600" /> : <Circle className="h-4 w-4 text-muted-foreground" />}
              <span className={reached(s.key) ? 'font-medium' : 'text-muted-foreground'}>{s.label}</span>
              {i < STEPS.length - 1 && <span className="text-muted-foreground">›</span>}
            </li>
          ))}
          {data.status === 'cancelled' && <li className="font-medium text-destructive">{tx('Cancelled')}</li>}
        </ol>
        <div className="grid gap-2 text-sm sm:grid-cols-3">
          <div><span className="text-muted-foreground">{tx('Sent')}:</span> {data.sentAt ? date(data.sentAt) : '—'}</div>
          <div><span className="text-muted-foreground">{tx('Supplier ref.')}:</span> {data.supplierReference ?? '—'}</div>
          <div><span className="text-muted-foreground">{tx('Promised')}:</span> {data.promisedDate ? date(data.promisedDate) : data.expectedDeliveryDate ? date(data.expectedDeliveryDate) : '—'}</div>
          {data.closeReason && <div className="sm:col-span-3"><span className="text-muted-foreground">{tx('Close reason')}:</span> {data.closeReason}</div>}
        </div>
        {data.bills.length > 0 && (
          <div className="text-sm">
            <span className="text-muted-foreground">{tx('Bills')}:</span>{' '}
            {data.bills.map((b) => `${b.documentNumber} (${b.status})`).join(', ')}
          </div>
        )}
      </CardContent>

      <Dialog open={ackOpen} onOpenChange={setAckOpen}>
        <DialogContent>
          <DialogHeader><DialogTitle>{tx('Supplier confirmation')}</DialogTitle></DialogHeader>
          <div className="grid gap-3">
            <div><Label>{tx("Supplier's order reference")}</Label><Input value={ack.supplierReference} onChange={(e) => setAck({ ...ack, supplierReference: e.target.value })} /></div>
            <div><Label>{tx('Promised delivery date')}</Label><Input type="date" value={ack.promisedDate} onChange={(e) => setAck({ ...ack, promisedDate: e.target.value })} /></div>
          </div>
          <DialogFooter>
            <Button variant="outline" onClick={() => setAckOpen(false)}>{tx('Cancel')}</Button>
            <Button onClick={async () => {
              if (await run('acknowledge', { supplierReference: ack.supplierReference || undefined, promisedDate: ack.promisedDate || undefined }, tx('Confirmation recorded'))) setAckOpen(false);
            }}>{tx('Save')}</Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      <Dialog open={closeOpen} onOpenChange={setCloseOpen}>
        <DialogContent>
          <DialogHeader><DialogTitle>{tx('Short-close order')}</DialogTitle></DialogHeader>
          <p className="text-sm text-muted-foreground">
            {tx('The undelivered balance is cancelled and handed back to its purchase request so it can be ordered elsewhere. What was received stays billable.')}
          </p>
          <Label>{tx('Reason')}</Label>
          <Textarea value={reason} onChange={(e) => setReason(e.target.value)} />
          <DialogFooter>
            <Button variant="outline" onClick={() => setCloseOpen(false)}>{tx('Cancel')}</Button>
            <Button variant="destructive" disabled={!reason.trim()} onClick={async () => {
              if (await run('short-close', { reason: reason.trim() }, tx('Order short-closed'))) { setCloseOpen(false); setReason(''); }
            }}>{tx('Short-close')}</Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </Card>
  );
}

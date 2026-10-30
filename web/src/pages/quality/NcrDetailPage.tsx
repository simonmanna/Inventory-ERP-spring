import { useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { ArrowLeft, Edit, Plus, RefreshCw } from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { Button } from '@/components/ui/button';
import { Textarea } from '@/components/ui/textarea';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Skeleton } from '@/components/ui/skeleton';
import { Dialog, DialogContent, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { useAuthStore } from '@/stores/auth.store';
import { notify, apiMessage } from '@/lib/notify';
import { date, dateTime } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';
import { NCR_SOURCE_LABEL, NCR_STATUS_LABEL, useNcr, useNcrStatus, type Ncr, type NcrStatus } from '@/features/quality/api';
import { CapaStatusPill, Field, Item, NcrStatusPill, SeverityPill } from './quality-ui';
import { num } from './quality-format';
import { NcrFormDialog } from './NcrsPage';
import { CapaFormDialog } from './CapasPage';

const MOVES: Record<NcrStatus, NcrStatus[]> = {
  open: ['investigating', 'action', 'closed', 'cancelled'],
  investigating: ['action', 'closed', 'cancelled'],
  action: ['investigating', 'closed', 'cancelled'],
  closed: ['open'],
  cancelled: [],
};

/** NCR detail (ADR-S013): status workflow, root cause, linked CAPAs. */
export function NcrDetailPage() {
  const { id = '' } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const { data: n, isLoading, isError, refetch } = useNcr(id);
  const has = useAuthStore((s) => s.hasPermission);
  const [moveTo, setMoveTo] = useState<NcrStatus | null>(null);
  const [editing, setEditing] = useState(false);
  const [newCapa, setNewCapa] = useState(false);

  if (isLoading) return <div className="space-y-3"><Skeleton className="h-12 w-1/2" /><Skeleton className="h-64 w-full" /></div>;
  if (isError || !n) {
    return (
      <div className="p-8 text-center text-muted-foreground">
        <p>{tx('NCR not found')}</p>
        <Button variant="link" onClick={() => refetch()}><RefreshCw className="mr-1 h-4 w-4" /> {tx('Retry')}</Button>
      </div>
    );
  }
  const canEdit = has(PERMISSIONS.quality.ncr);
  const open = n.status !== 'closed' && n.status !== 'cancelled';
  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-start gap-3">
        <Button variant="ghost" size="icon" aria-label={tx('Back')} onClick={() => navigate('/quality/ncrs')}><ArrowLeft className="h-5 w-5" /></Button>
        <div className="min-w-0 flex-1">
          <div className="flex flex-wrap items-center gap-2">
            <h1 className="text-2xl font-bold">{n.ncrNumber}</h1>
            <NcrStatusPill status={n.status} />
            <SeverityPill severity={n.severity} />
          </div>
          <p className="text-sm text-muted-foreground">{n.title}</p>
        </div>
        {canEdit && (
          <div className="flex flex-wrap gap-2">
            {MOVES[n.status].map((to) => (
              <Button key={to} variant={to === 'closed' ? 'default' : to === 'cancelled' ? 'ghost' : 'outline'} onClick={() => setMoveTo(to)}>
                {to === 'open' ? tx('Reopen') : NCR_STATUS_LABEL[to]}
              </Button>
            ))}
            {open && <Button variant="outline" onClick={() => setEditing(true)}><Edit className="mr-1.5 h-4 w-4" /> {tx('Edit')}</Button>}
          </div>
        )}
      </div>

      <Card>
        <CardContent className="pt-6">
          <dl className="grid grid-cols-2 gap-4 sm:grid-cols-4">
            <Item label={tx('Source')}>{NCR_SOURCE_LABEL[n.source]}</Item>
            <Item label={tx('Lot')}>{n.lotId ? <Link className="text-primary hover:underline" to={`/quality/lots/${n.lotId}`}>{n.lotNumber}</Link> : null}</Item>
            <Item label={tx('Product')}>{n.productName}</Item>
            <Item label={tx('Batch number')}>{n.batchNumber}</Item>
            <Item label={tx('Supplier')}>{n.supplierName}</Item>
            <Item label={tx('Customer')}>{n.customerName}</Item>
            <Item label={tx('Work centre')}>{n.workCenterName}</Item>
            <Item label={tx('Quantity affected')}>{n.quantityAffected ? num(n.quantityAffected, 3) : null}</Item>
            <Item label={tx('Owner')}>{n.ownerName}</Item>
            <Item label={tx('Raised')}>{dateTime(n.createdAt)}</Item>
            <Item label={tx('Closed')}>{n.closedAt ? dateTime(n.closedAt) : null}</Item>
            <Item label={tx('Disposition')}>{n.disposition}</Item>
          </dl>
          {n.description && <p className="mt-4 whitespace-pre-wrap text-sm">{n.description}</p>}
          <div className="mt-4 rounded-md bg-muted/40 p-3 text-sm">
            <p className="text-xs font-medium text-muted-foreground">{tx('Root cause')}</p>
            <p className="whitespace-pre-wrap">{n.rootCause ?? '—'}</p>
          </div>
          {n.closureNotes && <p className="mt-2 text-sm text-muted-foreground">{tx('Closure notes')}: {n.closureNotes}</p>}
        </CardContent>
      </Card>

      <Card>
        <CardHeader className="flex flex-row items-center justify-between gap-2">
          <CardTitle className="text-base">{tx('Corrective and preventive actions')}</CardTitle>
          {has(PERMISSIONS.quality.capa) && open && <Button size="sm" variant="outline" onClick={() => setNewCapa(true)}><Plus className="mr-1 h-4 w-4" /> {tx('New CAPA')}</Button>}
        </CardHeader>
        <CardContent>
          {(n.capas ?? []).length === 0 ? <p className="text-sm text-muted-foreground">{tx('No CAPA yet.')}</p> : (
            <ul className="divide-y text-sm">
              {(n.capas ?? []).map((c) => (
                <li key={c.id} className="flex flex-wrap items-center justify-between gap-2 py-2">
                  <Link className="text-primary hover:underline" to={`/quality/capas/${c.id}`}>{c.capaNumber}</Link>
                  <span className="min-w-0 flex-1 truncate">{c.title}</span>
                  <span className="text-muted-foreground">{c.dueDate ? date(c.dueDate) : '—'}</span>
                  <CapaStatusPill status={c.status} />
                </li>
              ))}
            </ul>
          )}
        </CardContent>
      </Card>

      {moveTo && <NcrMoveDialog ncr={n} to={moveTo} onClose={() => setMoveTo(null)} />}
      {editing && <NcrFormDialog ncr={n} onClose={() => setEditing(false)} />}
      {newCapa && <CapaFormDialog ncr={n} onClose={() => setNewCapa(false)} onSaved={(c) => navigate(`/quality/capas/${c.id}`)} />}
    </div>
  );
}

function NcrMoveDialog({ ncr, to, onClose }: { ncr: Ncr; to: NcrStatus; onClose: () => void }) {
  const [notes, setNotes] = useState('');
  const move = useNcrStatus();
  const submit = async () => {
    try {
      await move.mutateAsync({ id: ncr.id, status: to, notes });
      notify.success(NCR_STATUS_LABEL[to]);
      onClose();
    } catch (err) {
      notify.error(apiMessage(err, tx('Could not update the NCR')));
    }
  };
  return (
    <Dialog open onOpenChange={(o) => !o && onClose()}>
      <DialogContent className="sm:max-w-md">
        <DialogHeader><DialogTitle>{ncr.ncrNumber} → {to === 'open' ? tx('Reopen') : NCR_STATUS_LABEL[to]}</DialogTitle></DialogHeader>
        <Field label={to === 'closed' ? tx('Closure notes') : tx('Notes')}><Textarea rows={3} value={notes} onChange={(e) => setNotes(e.target.value)} /></Field>
        <DialogFooter>
          <Button variant="outline" onClick={onClose}>{tx('Cancel')}</Button>
          <Button onClick={submit} disabled={move.isPending}>{tx('Confirm')}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

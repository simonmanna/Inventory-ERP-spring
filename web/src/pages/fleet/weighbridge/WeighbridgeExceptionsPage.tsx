import { useState } from 'react';
import { Link } from 'react-router-dom';
import { AlertTriangle } from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { Button } from '@/components/ui/button';
import { Textarea } from '@/components/ui/textarea';
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { DataTable, type Column } from '@/components/data-table';
import { DataTablePagination, FilterSelect, ListCard, ListPageHeader, ListToolbar, StatusPill, useListState } from '@/components/list';
import { useAuthStore } from '@/stores/auth.store';
import { notify, apiMessage } from '@/lib/notify';
import { dateTime } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';
import { useDecideException, useExceptions, type WeighbridgeException } from '@/features/fleet/weighbridge-api';
import { ManualFlag } from './weighbridge-ui';
import { kg, qty } from './weighbridge-format';

const STATUS: Record<string, string> = { pending: tx('Pending'), approved: tx('Approved'), rejected: tx('Rejected') };

/** Out-of-tolerance exceptions (fleet 1.4): approve or reject; the ticket's operators cannot decide their own. */
export function WeighbridgeExceptionsPage() {
  const list = useListState({ status: 'pending' });
  const status = list.filters.status;
  const canDecide = useAuthStore((s) => s.hasPermission(PERMISSIONS.weighbridge.approveException));
  const { data, isLoading, isError } = useExceptions({ page: list.page, pageSize: list.pageSize, status: status || undefined });
  const [deciding, setDeciding] = useState<{ e: WeighbridgeException; approve: boolean } | null>(null);
  const columns: Column<WeighbridgeException>[] = [
    { key: 'raised', header: tx('Raised'), render: (e) => <span className="whitespace-nowrap">{dateTime(e.raisedAt)}</span> },
    {
      key: 'ticket', header: tx('Ticket'),
      render: (e) => (
        <div>
          <Link className="font-medium text-primary" to={`/fleet/weighbridge/tickets/${e.ticketId}`}>{e.ticket?.ticketNumber}</Link>
          <div className="text-xs text-muted-foreground">{e.ticket?.registrationPlate} · {e.ticket?.stationCode}</div>
        </div>
      ),
    },
    { key: 'product', header: tx('Product'), render: (e) => <div>{e.ticket?.productName ?? '—'}<div className="text-xs text-muted-foreground">{e.ticket?.partnerName}</div></div> },
    { key: 'expected', header: tx('Expected'), className: 'text-right', render: (e) => <div className="tabular-nums">{kg(e.expectedKg)}<div className="text-xs text-muted-foreground">{qty(e.ticket?.orderedQty, e.ticket?.orderedUomCode)}</div></div> },
    { key: 'net', header: tx('Net'), className: 'text-right', render: (e) => <span className="tabular-nums">{kg(e.netKg)}</span> },
    {
      key: 'variance', header: tx('Variance'), className: 'text-right',
      render: (e) => <div className="tabular-nums text-rose-600">{kg(e.varianceKg)} ({e.variancePct ?? '—'} %)<div className="text-xs text-muted-foreground">±{kg(e.allowedVarianceKg)}</div></div>,
    },
    {
      key: 'status', header: tx('Status'),
      render: (e) => (
        <div className="space-y-1">
          <StatusPill tone={e.status === 'pending' ? 'warning' : e.status === 'approved' ? 'success' : 'neutral'}>{STATUS[e.status]}</StatusPill>
          {e.ticket?.isManual && <ManualFlag />}
          {e.decisionNotes && <div className="max-w-[14rem] text-xs text-muted-foreground">{e.decisionNotes}</div>}
        </div>
      ),
    },
    {
      key: 'actions', header: '', className: 'text-right',
      render: (e) => e.status === 'pending' && canDecide && (
        e.ownTicket ? <span className="text-xs text-muted-foreground">{tx('Your own ticket')}</span> : (
          <div className="flex justify-end gap-1">
            <Button size="sm" onClick={() => setDeciding({ e, approve: true })}>{tx('Approve')}</Button>
            <Button size="sm" variant="outline" onClick={() => setDeciding({ e, approve: false })}>{tx('Reject')}</Button>
          </div>
        )
      ),
    },
  ];
  return (
    <div className="space-y-4">
      <ListPageHeader icon={AlertTriangle} title={tx('Weighbridge Exceptions')}
        description={tx('Tickets whose net weight is outside the tolerance. A different person than the operator decides.')} />
      <ListToolbar>
        <FilterSelect value={status} onChange={(v) => list.setFilter('status', v)} options={Object.entries(STATUS).map(([value, label]) => ({ value, label }))} allLabel={tx('All statuses')} />
      </ListToolbar>
      <ListCard>
        <DataTable columns={columns} data={data?.data ?? []} loading={isLoading} getRowId={(e) => e.id}
          emptyMessage={isError ? tx('Could not load the exceptions.') : tx('No exceptions.')} />
      </ListCard>
      {data?.meta && (
        <DataTablePagination page={data.meta.page} pageSize={list.pageSize} total={data.meta.total} totalPages={data.meta.totalPages}
          onPageChange={list.setPage} onPageSizeChange={list.setPageSize} noun="exception" />
      )}
      {deciding && <DecisionDialog e={deciding.e} approve={deciding.approve} onClose={() => setDeciding(null)} />}
    </div>
  );
}

function DecisionDialog({ e, approve, onClose }: { e: WeighbridgeException; approve: boolean; onClose: () => void }) {
  const [notes, setNotes] = useState('');
  const decide = useDecideException();
  const submit = async () => {
    try {
      await decide.mutateAsync({ id: e.id, approve, notes });
      notify.success(approve ? tx('Exception approved: the ticket is completed') : tx('Exception rejected: the truck must be weighed again'));
      onClose();
    } catch (err) {
      notify.error(apiMessage(err, tx('Could not decide the exception')));
    }
  };
  return (
    <Dialog open onOpenChange={(o) => !o && onClose()}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>{approve ? tx('Approve exception') : tx('Reject exception')}</DialogTitle>
          <DialogDescription>{e.ticket?.ticketNumber} · {tx('Variance {{variance}} vs allowed ±{{allowed}}', { variance: kg(e.varianceKg), allowed: kg(e.allowedVarianceKg) })}</DialogDescription>
        </DialogHeader>
        <Textarea value={notes} onChange={(ev) => setNotes(ev.target.value)} rows={3} placeholder={approve ? tx('Notes (optional)') : tx('Reason (required)')} />
        <DialogFooter>
          <Button variant="outline" onClick={onClose}>{tx('Cancel')}</Button>
          <Button variant={approve ? 'default' : 'destructive'} onClick={submit} disabled={(!approve && !notes.trim()) || decide.isPending}>
            {approve ? tx('Approve') : tx('Reject')}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

import { useState } from 'react';
import { Link, useNavigate, useSearchParams } from 'react-router-dom';
import { Scale } from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { Button } from '@/components/ui/button';
import { DataTable, type Column } from '@/components/data-table';
import { DataTablePagination, FilterSelect, ListCard, ListPageHeader, ListToolbar, useListState } from '@/components/list';
import { useAuthStore } from '@/stores/auth.store';
import { notify, apiMessage } from '@/lib/notify';
import { dateTime, money } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';
import {
  DISCREPANCY_KIND_LABEL, DISCREPANCY_STATUS_LABEL, REASON_LABEL, useApproveDiscrepancy, useDiscrepancies,
  useRejectDiscrepancy, type Discrepancy,
} from '@/features/fleet/dispatch-api';
import { caseApi } from '@/features/crm/cases-api';
import { num, toOptions } from '../fleet-format';
import { DiscrepancyStatusPill, ReasonDialog } from './dispatch-ui';

const FILTERS = { status: '', kind: '' };
const STATUS_FILTER: Record<string, string> = { ...DISCREPANCY_STATUS_LABEL, all: tx('All') };

/**
 * Delivery discrepancies (fleet 1.3): shortages, excesses and returns from the proof of delivery, decided here or in
 * the approvals inbox. Approving adjusts the billable quantity and, on invoiced goods, raises a draft credit note.
 */
export function DiscrepanciesPage() {
  const has = useAuthStore((s) => s.hasPermission);
  const [params] = useSearchParams();
  const deliveryOrderId = params.get('deliveryOrderId') ?? undefined;
  const list = useListState(FILTERS);
  const status = list.filters.status || 'pending';
  const { kind } = list.filters;
  const { data, isLoading, isError } = useDiscrepancies({
    page: list.page, pageSize: list.pageSize, status: status === 'all' ? undefined : status, kind: kind || undefined, deliveryOrderId,
  });
  const approve = useApproveDiscrepancy();
  const reject = useRejectDiscrepancy();
  const [approving, setApproving] = useState<Discrepancy | null>(null);
  const [rejecting, setRejecting] = useState<Discrepancy | null>(null);
  const canDecide = has(PERMISSIONS.fleetDispatch.approveDiscrepancy);
  const canOpenCase = has(PERMISSIONS.crm.caseWrite);
  const navigate = useNavigate();
  const rows = data?.data ?? [];
  const meta = data?.meta;

  const run = async (fn: () => Promise<Discrepancy>, ok: string) => {
    try {
      const out = await fn();
      notify.success(out.creditNoteNumber ? tx('{{ok}} · draft credit note {{n}}', { ok, n: out.creditNoteNumber }) : ok);
      setApproving(null);
      setRejecting(null);
    } catch (err) {
      notify.error(apiMessage(err, tx('Could not decide the discrepancy')));
    }
  };

  const openCase = async (discrepancyId: string) => {
    try {
      const created = await caseApi.fromDiscrepancy(discrepancyId);
      navigate(`/crm/cases/${created.id}`);
    } catch (err) {
      notify.error(apiMessage(err, tx('Could not open the case')));
    }
  };

  const columns: Column<Discrepancy>[] = [
    { key: 'code', header: tx('Discrepancy'), render: (d) => <span className="font-medium">{d.code}</span> },
    { key: 'when', header: tx('Raised'), render: (d) => <span className="whitespace-nowrap">{dateTime(d.createdAt)}</span> },
    { key: 'trip', header: tx('Trip'), render: (d) => <Link className="text-primary hover:underline" to={`/fleet/trips/${d.loadId}`}>{d.tripCode}</Link> },
    { key: 'order', header: tx('Delivery order'), render: (d) => <Link className="text-primary hover:underline" to={`/fleet/delivery-orders/${d.deliveryOrderId}`}>{d.deliveryOrderCode}</Link> },
    { key: 'customer', header: tx('Customer'), render: (d) => d.partnerName },
    { key: 'product', header: tx('Product'), render: (d) => d.productName },
    { key: 'kind', header: tx('Kind'), render: (d) => DISCREPANCY_KIND_LABEL[d.kind] },
    { key: 'qty', header: tx('Dispatched → delivered'), className: 'text-right', render: (d) => <span className="tabular-nums">{num(d.dispatchedQty, 3)} → {num(d.deliveredQty, 3)} {d.uomCode ?? ''}</span> },
    { key: 'diff', header: tx('Difference'), className: 'text-right', render: (d) => <span className="font-medium tabular-nums">{num(d.differenceQty, 3)}</span> },
    { key: 'reason', header: tx('Reason'), render: (d) => <span className="text-sm">{REASON_LABEL[d.reason] ?? d.reason}{d.notes ? ` · ${d.notes}` : ''}</span> },
    { key: 'cn', header: tx('Credit note'), render: (d) => (d.creditNoteNumber ? `${d.creditNoteNumber}${d.creditAmount ? ` · ${money(d.creditAmount)}` : ''}` : '—') },
    { key: 'status', header: tx('Status'), render: (d) => <DiscrepancyStatusPill status={d.status} /> },
    {
      key: 'actions', header: '', className: 'text-right',
      render: (d) => (
        <div className="flex justify-end gap-1">
          {canDecide && d.status === 'pending' && (
            <>
              <Button size="sm" onClick={() => setApproving(d)}>{tx('Approve')}</Button>
              <Button size="sm" variant="outline" onClick={() => setRejecting(d)}>{tx('Reject')}</Button>
            </>
          )}
          {canOpenCase && <Button size="sm" variant="ghost" onClick={() => openCase(d.id)}>{tx('Open case')}</Button>}
        </div>
      ),
    },
  ];

  return (
    <div className="space-y-4">
      <ListPageHeader icon={Scale} title={tx('Delivery Discrepancies')} description={tx('Delivered versus dispatched: shortages, excesses and returned goods awaiting a decision.')} />
      <ListToolbar>
        <FilterSelect value={list.filters.status} onChange={(v) => list.setFilter('status', v)} options={toOptions(STATUS_FILTER)} allLabel={tx('Pending')} />
        <FilterSelect value={kind} onChange={(v) => list.setFilter('kind', v)} options={toOptions(DISCREPANCY_KIND_LABEL)} allLabel={tx('All kinds')} />
      </ListToolbar>
      <ListCard>
        <div className="overflow-x-auto">
          <DataTable columns={columns} data={rows} loading={isLoading} loadingRows={6} getRowId={(d) => d.id} cellClassName="py-2.5 px-4" headerRowClassName="h-10"
            emptyMessage={isError ? tx('Could not load the discrepancies. Try again.') : tx('No discrepancies.')} />
        </div>
      </ListCard>
      {meta && (
        <DataTablePagination page={meta.page} pageSize={list.pageSize} total={meta.total} totalPages={meta.totalPages}
          onPageChange={list.setPage} onPageSizeChange={list.setPageSize} noun="discrepancy" />
      )}
      <ReasonDialog open={!!approving} required={false} title={tx('Approve {{code}}', { code: approving?.code ?? '' })}
        description={tx('The billable quantity is adjusted; on invoiced goods a draft credit note is raised for Accounts Receivable.')}
        confirmLabel={tx('Approve')} busy={approve.isPending} onClose={() => setApproving(null)}
        onConfirm={(notes) => approving && run(() => approve.mutateAsync({ id: approving.id, notes }), tx('Discrepancy approved'))} />
      <ReasonDialog open={!!rejecting} title={tx('Reject {{code}}', { code: rejecting?.code ?? '' })}
        description={tx('The customer stays billed for the dispatched quantity.')}
        confirmLabel={tx('Reject')} busy={reject.isPending} onClose={() => setRejecting(null)}
        onConfirm={(notes) => rejecting && run(() => reject.mutateAsync({ id: rejecting.id, notes }), tx('Discrepancy rejected'))} />
    </div>
  );
}

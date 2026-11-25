import { useNavigate } from 'react-router-dom';
import { ShieldAlert } from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { Button } from '@/components/ui/button';
import { DataTable, type Column } from '@/components/data-table';
import { DataTablePagination, ListCard, ListPageHeader, useListState } from '@/components/list';
import { useAuthStore } from '@/stores/auth.store';
import { notify, apiMessage } from '@/lib/notify';
import { dateTime } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';
import { SOURCE_LABEL, useQcChecks, useRetryQuarantine, type QcCheck } from '@/features/quality-control/api';
import { CheckStatusPill } from './qc-ui';
import { num } from './qc-format';

/**
 * Quarantine exceptions (ADR-S087 §7): failed quantities that could not be moved into quarantine when their source
 * completed (stock reserved or consumed meanwhile). Nothing is shown as quarantined until it moved; retry once the
 * stock is there.
 */
export function QuarantineExceptionsPage() {
  const navigate = useNavigate();
  const canManage = useAuthStore((s) => s.hasPermission(PERMISSIONS.qualityControl.manage));
  const list = useListState({});
  const { data, isLoading, isError } = useQcChecks({ page: list.page, pageSize: list.pageSize, quarantineStatus: 'failed' });
  const retry = useRetryQuarantine();
  const run = async (c: QcCheck) => {
    try {
      await retry.mutateAsync(c.id);
      notify.success(tx('{{number}}: moved into quarantine', { number: c.checkNumber }));
    } catch (err) {
      notify.error(apiMessage(err, tx('The quarantine move failed again')));
    }
  };
  const columns: Column<QcCheck>[] = [
    { key: 'number', header: tx('Check'), render: (c) => <span className="font-medium text-primary">{c.checkNumber}</span> },
    { key: 'source', header: tx('Source'), render: (c) => <div><div>{c.sourceRef ?? '—'}</div><div className="text-xs text-muted-foreground">{c.sourceType !== 'manual' ? SOURCE_LABEL[c.sourceType] : ''}</div></div> },
    { key: 'product', header: tx('Product'), render: (c) => <div><div>{c.productName ?? '—'}</div><div className="text-xs text-muted-foreground">{c.batchNumber ?? ''}</div></div> },
    { key: 'qty', header: tx('Failed qty'), render: (c) => <span className="tabular-nums">{c.failedQty != null ? num(c.failedQty) : '—'}</span> },
    { key: 'error', header: tx('Why it did not move'), render: (c) => <span className="text-sm text-destructive">{c.quarantineError ?? '—'}</span> },
    { key: 'status', header: tx('Check'), render: (c) => <CheckStatusPill status={c.status} /> },
    { key: 'done', header: tx('Done'), render: (c) => <span className="whitespace-nowrap">{c.doneAt ? dateTime(c.doneAt) : '—'}</span> },
    {
      key: 'retry', header: '', render: (c) => canManage
        ? <Button size="sm" variant="outline" disabled={retry.isPending} onClick={(e) => { e.stopPropagation(); void run(c); }}>{tx('Retry move')}</Button>
        : null,
    },
  ];
  return (
    <div className="space-y-4">
      <ListPageHeader icon={ShieldAlert} title={tx('Quarantine Exceptions')}
        description={tx('Failed quantities that could not be moved into quarantine when their document completed. Retry once the stock is available.')} />
      <ListCard>
        <DataTable columns={columns} data={data?.data ?? []} loading={isLoading} getRowId={(c) => c.id}
          onRowClick={(c) => navigate(`/quality-control/checks/${c.id}`)} cellClassName="py-2.5 px-4" headerRowClassName="h-10"
          emptyMessage={isError ? tx('Could not load the exceptions.') : tx('No quarantine exceptions.')} />
        {data?.meta && (
          <DataTablePagination page={data.meta.page} pageSize={list.pageSize} total={data.meta.total} totalPages={data.meta.totalPages}
            onPageChange={list.setPage} onPageSizeChange={list.setPageSize} noun="check" />
        )}
      </ListCard>
    </div>
  );
}

import { useState } from 'react';
import { toast } from 'sonner';
import { Trash2 } from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { DataTable, type Column } from '@/components/data-table';
import {
  useApproveCommissions, useCalculateCommissions, useCommissionEntries, useCommissionRules, useDeleteCommissionRule,
  useExportCommissions, useSaveCommissionRule, type CommissionEntry, type CommissionRule,
} from '@/features/crm/commission-api';
import { apiErrorMessage } from '@/lib/api-error';
import { date, money } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';
import { useAuthStore } from '@/stores/auth.store';

const PAGE_SIZE = 25;

/** Salesperson commissions (SALES-4): rules, calculation from posted invoices, approval, export to payroll. */
export function CommissionsPage() {
  const hasPermission = useAuthStore((s) => s.hasPermission);
  const canManage = hasPermission(PERMISSIONS.commission.manage);
  const canApprove = hasPermission(PERMISSIONS.commission.approve);
  const [status, setStatus] = useState('accrued');
  const [page, setPage] = useState(1);
  const [selected, setSelected] = useState<string[]>([]);
  const [from, setFrom] = useState('');
  const [to, setTo] = useState('');
  const { data, isLoading, isError } = useCommissionEntries({ status: status || undefined, page, pageSize: PAGE_SIZE });
  const calculate = useCalculateCommissions();
  const approve = useApproveCommissions();
  const exportRun = useExportCommissions();
  const onError = (e: unknown) => toast.error(apiErrorMessage(e, tx('Could not save')));
  const meta = data?.meta;

  const columns: Column<CommissionEntry>[] = [
    {
      key: 'pick',
      header: '',
      render: (r) => r.status === 'accrued' && canApprove && (
        <input
          type="checkbox"
          aria-label={tx('Select')}
          checked={selected.includes(r.id)}
          onChange={() => setSelected((s) => (s.includes(r.id) ? s.filter((x) => x !== r.id) : [...s, r.id]))}
        />
      ),
    },
    { key: 'firstName', header: tx('Salesperson'), render: (r) => [r.firstName, r.lastName].filter(Boolean).join(' ') },
    { key: 'documentNumber', header: tx('Invoice') },
    { key: 'productName', header: tx('Product') },
    { key: 'earnedOn', header: tx('Date'), render: (r) => date(r.earnedOn) },
    { key: 'baseAmount', header: tx('Sales'), className: 'text-right', render: (r) => money(r.baseAmount) },
    { key: 'ratePercent', header: tx('Rate %'), className: 'text-right' },
    { key: 'amount', header: tx('Commission'), className: 'text-right font-medium', render: (r) => money(r.amount) },
    { key: 'status', header: tx('Status'), render: (r) => <Badge variant="secondary">{r.status}</Badge> },
  ];

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-semibold">{tx('Sales Commissions')}</h1>
        <p className="text-sm text-muted-foreground">
          {tx('Earned on posted invoices that name a salesperson. Approved commissions go to payroll as a taxable earning.')}
        </p>
      </div>
      <div className="flex flex-wrap items-center gap-2">
        <select
          aria-label={tx('Status')}
          className="rounded border bg-background px-2 py-1.5 text-sm"
          value={status}
          onChange={(e) => { setStatus(e.target.value); setPage(1); setSelected([]); }}
        >
          <option value="">{tx('All')}</option>
          <option value="accrued">{tx('Accrued')}</option>
          <option value="approved">{tx('Approved')}</option>
          <option value="exported">{tx('Sent to payroll')}</option>
          <option value="cancelled">{tx('Cancelled')}</option>
        </select>
        {canManage && (
          <>
            <Input type="date" aria-label={tx('From')} className="w-40" value={from} onChange={(e) => setFrom(e.target.value)} />
            <Input type="date" aria-label={tx('To')} className="w-40" value={to} onChange={(e) => setTo(e.target.value)} />
            <Button
              variant="outline"
              disabled={calculate.isPending}
              onClick={() => calculate.mutate({ from: from || undefined, to: to || undefined }, {
                onSuccess: (r) => toast.success(`${tx('Entries created')}: ${r.created} · ${tx('Lines without a rule')}: ${r.linesWithoutRule}`),
                onError,
              })}
            >
              {tx('Calculate')}
            </Button>
          </>
        )}
        {canApprove && (
          <>
            <Button
              disabled={selected.length === 0 || approve.isPending}
              onClick={() => approve.mutate(selected, { onSuccess: () => setSelected([]), onError })}
            >
              {tx('Approve selected')}
            </Button>
            <Button
              variant="outline"
              disabled={exportRun.isPending}
              onClick={() => exportRun.mutate(undefined, {
                onSuccess: (r) => toast.success(`${tx('Sent to payroll')}: ${r.entries}`),
                onError,
              })}
            >
              {tx('Send approved to payroll')}
            </Button>
          </>
        )}
      </div>
      {isError ? (
        <p className="text-sm text-destructive">{tx('Could not load')}</p>
      ) : (
        <div className="overflow-x-auto">
          <DataTable
            columns={columns}
            data={data?.data ?? []}
            loading={isLoading}
            getRowId={(r) => r.id}
            emptyMessage={tx('No commission entries.')}
          />
        </div>
      )}
      {meta && meta.totalPages > 1 && (
        <div className="flex items-center justify-end gap-2 text-sm text-muted-foreground">
          <Button variant="outline" size="sm" disabled={page <= 1} onClick={() => setPage((p) => p - 1)}>{tx('Previous')}</Button>
          <span>{tx('Page')} {meta.page} {tx('of')} {meta.totalPages}</span>
          <Button variant="outline" size="sm" disabled={page >= meta.totalPages} onClick={() => setPage((p) => p + 1)}>{tx('Next')}</Button>
        </div>
      )}
      <RulesSection canManage={canManage} />
    </div>
  );
}

function RulesSection({ canManage }: { canManage: boolean }) {
  const { data = [], isLoading } = useCommissionRules();
  const save = useSaveCommissionRule();
  const del = useDeleteCommissionRule();
  const [name, setName] = useState('');
  const [rate, setRate] = useState('');
  const onError = (e: unknown) => toast.error(apiErrorMessage(e, tx('Could not save')));

  const columns: Column<CommissionRule>[] = [
    { key: 'name', header: tx('Name') },
    { key: 'firstName', header: tx('Salesperson'), render: (r) => ([r.firstName, r.lastName].filter(Boolean).join(' ') || tx('Everyone')) },
    { key: 'productName', header: tx('Product'), render: (r) => r.productName ?? r.productCategoryName ?? tx('All products') },
    { key: 'ratePercent', header: tx('Rate %'), className: 'text-right' },
    {
      key: 'actions',
      header: '',
      render: (r) => canManage && (
        <Button size="icon" variant="ghost" aria-label={tx('Delete')} onClick={() => del.mutate(r.id, { onError })}>
          <Trash2 className="h-4 w-4 text-destructive" />
        </Button>
      ),
    },
  ];

  return (
    <div className="space-y-2">
      <h2 className="text-lg font-semibold">{tx('Commission rules')}</h2>
      {canManage && (
        <div className="flex flex-wrap items-center gap-2">
          <Input className="max-w-xs" placeholder={tx('Name')} value={name} onChange={(e) => setName(e.target.value)} />
          <Input className="w-28" type="number" min={0} max={100} placeholder={tx('Rate %')} value={rate} onChange={(e) => setRate(e.target.value)} />
          <Button
            disabled={!name.trim() || rate === '' || save.isPending}
            onClick={() => save.mutate({ name, ratePercent: Number(rate) }, {
              onSuccess: () => { setName(''); setRate(''); },
              onError,
            })}
          >
            {tx('Add rule')}
          </Button>
        </div>
      )}
      <DataTable columns={columns} data={data} loading={isLoading} getRowId={(r) => r.id} emptyMessage={tx('No commission rules yet.')} />
    </div>
  );
}

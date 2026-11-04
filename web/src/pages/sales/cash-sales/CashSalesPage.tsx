import { useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { AlertTriangle, Plus, Store } from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { ListPageHeader } from '@/components/list';
import { api } from '@/lib/api';
import { date, money } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';
import { cn } from '@/lib/utils';
import { useAuthStore } from '@/stores/auth.store';
import type { CashSale, Dashboard } from '@/features/sales/cash-sales-api';
import { ExecutionPill, FiscalPill, ProcessingPill } from './cash-sales-ui';

type Queue = 'all' | 'drafts' | 'pending' | 'approved' | 'returned' | 'rejected' | 'invoiced';

const QUEUE_FILTER: Record<Queue, Record<string, string>> = {
  all: {},
  drafts: { executionStatus: 'draft' },
  pending: { processingStatus: 'submitted,under_review' },
  approved: { processingStatus: 'approved' },
  returned: { processingStatus: 'returned_for_correction' },
  rejected: { processingStatus: 'rejected', executionStatus: 'confirmed' },
  invoiced: { processingStatus: 'invoiced' },
};

/**
 * Branch cash sales (ADR-S056). Showroom view: own branch submissions and tiles. Head-office view (read_all):
 * processing queues for every branch, totals by branch and the rejected-sale exception queue.
 */
export function CashSalesPage({ scope }: { scope: 'branch' | 'head_office' }) {
  const navigate = useNavigate();
  const canCreate = useAuthStore((s) => s.hasPermission(PERMISSIONS.sales.cashSaleCreate));
  const [queue, setQueue] = useState<Queue>(scope === 'head_office' ? 'pending' : 'all');
  const [q, setQ] = useState('');
  const dash = useQuery({
    queryKey: ['cash-sales', 'dashboard', scope],
    queryFn: async () => (await api.get<Dashboard>('/sales/cash-sales/dashboard', { params: { scope } })).data,
  });
  const list = useQuery({
    queryKey: ['cash-sales', 'list', scope, queue, q],
    queryFn: async () => (await api.get<{ data: CashSale[]; meta: { total: number } }>('/sales/cash-sales', {
      params: { ...QUEUE_FILTER[queue], q: q || undefined, pageSize: 100 },
    })).data,
  });
  const t = dash.data?.tiles;
  const QUEUES: Array<[Queue, string]> = scope === 'head_office'
    ? [['pending', tx('Pending review')], ['approved', tx('Awaiting invoice')], ['returned', tx('Returned')], ['rejected', tx('Rejected')], ['invoiced', tx('Invoiced')], ['all', tx('All')]]
    : [['all', tx('All')], ['drafts', tx('Drafts')], ['pending', tx('Pending')], ['returned', tx('Returned')], ['invoiced', tx('Invoiced')]];
  return (
    <div className="space-y-4">
      <ListPageHeader
        icon={Store}
        title={scope === 'head_office' ? tx('Branch Sales Processing') : tx('Cash Sales')}
        description={scope === 'head_office'
          ? tx('Review showroom sales from every branch, return or reject them, approve and issue the official invoice.')
          : tx('Record showroom sales, capture and verify payments, confirm the handover and submit to head office.')}
        actions={scope === 'branch' && canCreate ? <Button onClick={() => navigate('/sales/cash-sales/new')}><Plus className="mr-1 h-4 w-4" />{tx('New cash sale')}</Button> : undefined}
      />
      {t && (
        <div className="grid grid-cols-2 gap-2 sm:grid-cols-3 lg:grid-cols-6">
          <Tile label={tx('Total submissions')} value={t.total} />
          <Tile label={tx('Pending approval')} value={t.pending} tone="amber" />
          <Tile label={tx('Awaiting invoice')} value={t.awaitingInvoice} tone="sky" />
          <Tile label={tx('Invoiced')} value={t.invoiced} tone="emerald" />
          <Tile label={tx('Returned')} value={t.returned} tone="orange" />
          <Tile label={tx('Uninvoiced sales')} value={money(t.uninvoicedTotal)} tone="rose" />
        </div>
      )}
      {scope === 'head_office' && dash.data && (dash.data.exceptions.length > 0 || (dash.data.byBranch?.length ?? 0) > 0) && (
        <div className="grid gap-3 lg:grid-cols-2">
          <div className="rounded-xl border bg-card p-3 shadow-sm">
            <h3 className="mb-2 flex items-center gap-1.5 text-sm font-semibold"><AlertTriangle className="h-4 w-4 text-rose-600" />{tx('Rejected sales — still posted')}</h3>
            <p className="mb-2 text-xs text-muted-foreground">{tx('Revenue, stock and the customer payment stay posted until the sale is reopened or returned.')}</p>
            {dash.data.exceptions.length === 0 ? <p className="text-sm text-muted-foreground">{tx('No unresolved rejections.')}</p> : (
              <ul className="divide-y text-sm">
                {dash.data.exceptions.map((e) => (
                  <li key={e.id} className="flex items-center justify-between gap-2 py-1.5">
                    <Link className="font-medium text-primary hover:underline" to={`/sales/cash-sales/${e.id}`}>{e.number}</Link>
                    <span className="truncate text-xs text-muted-foreground">{e.branchName} · {e.rejectionReason}</span>
                    <span className={cn('whitespace-nowrap text-xs', e.overdue ? 'font-semibold text-rose-600' : 'text-muted-foreground')}>
                      {e.ageDays} {tx('days')} · {e.ownerName ?? '—'}
                    </span>
                  </li>
                ))}
              </ul>
            )}
          </div>
          <div className="rounded-xl border bg-card p-3 shadow-sm">
            <h3 className="mb-2 text-sm font-semibold">{tx('By branch')}</h3>
            <table className="w-full text-sm">
              <thead className="text-left text-xs uppercase text-muted-foreground"><tr><th className="py-1">{tx('Branch')}</th><th className="py-1 text-right">{tx('Sales')}</th><th className="py-1 text-right">{tx('Pending')}</th><th className="py-1 text-right">{tx('Confirmed total')}</th></tr></thead>
              <tbody>
                {(dash.data.byBranch ?? []).map((b) => <tr key={b.branchId} className="border-t"><td className="py-1">{b.branchName}</td><td className="py-1 text-right tabular-nums">{b.count}</td><td className="py-1 text-right tabular-nums">{b.pending}</td><td className="py-1 text-right tabular-nums">{money(b.total)}</td></tr>)}
              </tbody>
            </table>
            {dash.data.processing && <p className="mt-2 text-xs text-muted-foreground">{tx('Average time from submission to invoice')}: {Number(dash.data.processing.avgHoursToInvoice).toFixed(1)} h</p>}
          </div>
        </div>
      )}
      <div className="flex flex-wrap items-center gap-2">
        <div className="flex flex-wrap rounded-lg border bg-card p-0.5">
          {QUEUES.map(([k, l]) => <button key={k} onClick={() => setQueue(k)} className={cn('rounded-md px-3 py-1.5 text-sm', queue === k ? 'bg-primary text-primary-foreground' : 'hover:bg-muted')}>{l}</button>)}
        </div>
        <Input className="h-9 w-60" placeholder={tx('Search number or customer…')} value={q} onChange={(e) => setQ(e.target.value)} />
      </div>
      <div className="overflow-x-auto rounded-xl border bg-card shadow-sm">
        <table className="w-full min-w-[900px] text-sm">
          <thead className="bg-muted/40 text-left text-xs font-semibold uppercase text-muted-foreground"><tr>
            <th className="px-3 py-2">{tx('Number')}</th><th className="px-3 py-2">{tx('Date')}</th><th className="px-3 py-2">{tx('Branch')}</th>
            <th className="px-3 py-2">{tx('Customer')}</th><th className="px-3 py-2 text-right">{tx('Total')}</th>
            <th className="px-3 py-2">{tx('Sale')}</th><th className="px-3 py-2">{tx('Head office')}</th><th className="px-3 py-2">{tx('Invoice')}</th>
          </tr></thead>
          <tbody>
            {(list.data?.data ?? []).map((s) => (
              <tr key={s.id} className="cursor-pointer border-t hover:bg-muted/30" onClick={() => navigate(`/sales/cash-sales/${s.id}`)}>
                <td className="px-3 py-2 font-medium">{s.number}</td>
                <td className="px-3 py-2">{date(s.saleDate)}</td>
                <td className="px-3 py-2">{s.branchName}</td>
                <td className="px-3 py-2">{s.customerName ?? s.partnerName}</td>
                <td className="px-3 py-2 text-right tabular-nums">{money(s.grandTotal)}</td>
                <td className="px-3 py-2"><ExecutionPill status={s.executionStatus} /></td>
                <td className="px-3 py-2"><ProcessingPill status={s.processingStatus} />{s.fiscalStatus !== 'none' && <FiscalPill status={s.fiscalStatus} className="ml-1" />}</td>
                <td className="px-3 py-2 text-xs">{s.invoiceNumber ?? '—'}</td>
              </tr>
            ))}
            {list.data && list.data.data.length === 0 && <tr><td colSpan={8} className="p-6 text-center text-muted-foreground">{tx('No cash sales in this view.')}</td></tr>}
          </tbody>
        </table>
      </div>
    </div>
  );
}

function Tile({ label, value, tone }: { label: string; value: number | string; tone?: 'amber' | 'sky' | 'emerald' | 'orange' | 'rose' }) {
  const color = { amber: 'text-amber-600', sky: 'text-sky-600', emerald: 'text-emerald-600', orange: 'text-orange-600', rose: 'text-rose-600' }[tone ?? 'sky'];
  return (
    <div className="rounded-xl border bg-card p-3 shadow-sm">
      <p className="text-xs text-muted-foreground">{label}</p>
      <p className={cn('mt-1 text-xl font-semibold tabular-nums', tone && color)}>{value}</p>
    </div>
  );
}

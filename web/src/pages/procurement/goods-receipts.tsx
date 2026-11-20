import { useEffect, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Link, useNavigate, useSearchParams } from 'react-router-dom';
import { PackageCheck, Plus } from 'lucide-react';
import { PERMISSIONS, type PaginatedResult } from '@erp/shared';
import { Button } from '@/components/ui/button';
import { Skeleton } from '@/components/ui/skeleton';
import { DataTablePagination, ListPageHeader, SearchInput } from '@/components/list';
import { api } from '@/lib/api';
import { useAuthStore } from '@/stores/auth.store';
import { useDebouncedValue } from '@/lib/use-debounced-value';
import { date, money } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';
import { cn } from '@/lib/utils';
import { GrnStatusPill } from './po-ui';

interface GRN {
  id: string;
  receiptNumber: string;
  status: 'draft' | 'posted' | 'cancelled' | 'reversed';
  receivedAt: string;
  notes: string | null;
  purchaseOrderId?: string | null;
  order?: { orderNumber: string } | null;
  lines?: Array<{ quantity: number | string; unitCost: number | string }>;
}

const TABS: Array<{ key: string; label: string }> = [
  { key: '', label: tx('All') },
  { key: 'draft', label: tx('Drafts') },
  { key: 'posted', label: tx('Posted') },
  { key: 'reversed', label: tx('Reversed') },
  { key: 'cancelled', label: tx('Cancelled') },
];

const PAGE_SIZE = 50;
const costOf = (g: GRN) => (g.lines ?? []).reduce((s, l) => s + Number(l.quantity) * Number(l.unitCost ?? 0), 0);

/** Goods receipt register, laid out like the sales order register. */
export function GoodsReceiptsPage() {
  const navigate = useNavigate();
  const [params, setParams] = useSearchParams();
  const canCreate = useAuthStore((s) => s.hasPermission(PERMISSIONS.procurement.goodsReceipt.create));

  const set = (k: string, v: string) => setParams((p) => {
    const n = new URLSearchParams(p);
    if (v) n.set(k, v); else n.delete(k);
    if (k !== 'page') n.delete('page');
    return n;
  }, { replace: true });

  const tab = params.get('tab') ?? '';
  const page = Number(params.get('page') ?? 1);
  const [searchInput, setSearchInput] = useState(params.get('q') ?? '');
  const search = useDebouncedValue(searchInput, 300);
  useEffect(() => {
    if (search !== (params.get('q') ?? '')) set('q', search);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [search]);

  const queryParams: Record<string, string | number> = { page, pageSize: PAGE_SIZE };
  if (tab) queryParams.status = tab;
  if (params.get('q')) queryParams.search = params.get('q')!;

  const { data, isLoading } = useQuery<PaginatedResult<GRN>>({
    queryKey: ['goods-receipts', queryParams],
    queryFn: async () =>
      (await api.get<PaginatedResult<GRN>>('/procurement/goods-receipts', { params: queryParams })).data,
  });

  const rows = data?.data ?? [];
  const meta = data?.meta;

  return (
    <div className="space-y-4">
      <ListPageHeader
        icon={PackageCheck}
        title={tx('Goods Receipts')}
        description={tx('Stock received against purchases (auto-generated)')}
        actions={canCreate && (
          <Button onClick={() => navigate('/procurement/goods-receipts/new')}><Plus className="mr-1 h-4 w-4" />{tx('New Receipt')}</Button>
        )}
      />

      <div className="flex flex-wrap items-center gap-2">
        <div className="flex flex-wrap rounded-lg border bg-card p-0.5">
          {TABS.map((x) => (
            <button key={x.key} type="button" onClick={() => set('tab', x.key)}
              className={cn('rounded-md px-3 py-1.5 text-sm', tab === x.key ? 'bg-primary text-primary-foreground' : 'hover:bg-muted')}>
              {x.label}
            </button>
          ))}
        </div>
        <SearchInput value={searchInput} onChange={setSearchInput} placeholder={tx('Receipt number, PO number, notes…')} />
      </div>

      <div className="overflow-x-auto rounded-xl border bg-card shadow-sm">
        <table className="w-full min-w-[820px] text-sm">
          <thead className="bg-muted/40 text-left text-xs font-semibold uppercase tracking-wide text-muted-foreground">
            <tr>
              <th className="px-3 py-2">{tx('Number')}</th>
              <th className="px-3 py-2">{tx('Purchase order')}</th>
              <th className="px-3 py-2">{tx('Received Date')}</th>
              <th className="px-3 py-2">{tx('Status')}</th>
              <th className="px-3 py-2 text-right">{tx('Lines')}</th>
              <th className="px-3 py-2">{tx('Notes')}</th>
              <th className="px-3 py-2 text-right">{tx('Total Cost')}</th>
            </tr>
          </thead>
          <tbody>
            {isLoading && <tr><td colSpan={7} className="p-3"><Skeleton className="h-32 w-full" /></td></tr>}
            {rows.map((g) => (
              <tr key={g.id} className="cursor-pointer border-t hover:bg-muted/30" onClick={() => navigate(`/procurement/goods-receipts/${g.id}`)}>
                <td className="px-3 py-2 font-medium">{g.receiptNumber}</td>
                <td className="px-3 py-2">
                  {g.order?.orderNumber && g.purchaseOrderId
                    ? <Link to={`/procurement/purchase-orders/${g.purchaseOrderId}`} onClick={(e) => e.stopPropagation()} className="text-primary hover:underline">{g.order.orderNumber}</Link>
                    : <span className="text-muted-foreground">—</span>}
                </td>
                <td className="px-3 py-2">{date(g.receivedAt)}</td>
                <td className="px-3 py-2"><GrnStatusPill status={g.status} /></td>
                <td className="px-3 py-2 text-right tabular-nums">{g.lines?.length ?? 0}</td>
                <td className="px-3 py-2"><span className="block max-w-xs truncate text-muted-foreground">{g.notes ?? '—'}</span></td>
                <td className="px-3 py-2 text-right tabular-nums">{money(costOf(g))}</td>
              </tr>
            ))}
            {data && rows.length === 0 && (
              <tr><td colSpan={7} className="p-6 text-center text-muted-foreground">{tx('No goods receipts found.')}</td></tr>
            )}
          </tbody>
          {meta && rows.length > 0 && (
            <tfoot>
              <tr className="border-t font-semibold">
                <td colSpan={6} className="px-3 py-2">{meta.total} {tx('receipt(s)')}</td>
                <td className="px-3 py-2 text-right tabular-nums">{meta.totalPages <= 1 ? money(rows.reduce((s, g) => s + costOf(g), 0)) : ''}</td>
              </tr>
            </tfoot>
          )}
        </table>
        {meta && meta.totalPages > 1 && (
          <DataTablePagination page={meta.page} pageSize={meta.pageSize} total={meta.total} totalPages={meta.totalPages} onPageChange={(p) => set('page', String(p))} />
        )}
      </div>
    </div>
  );
}

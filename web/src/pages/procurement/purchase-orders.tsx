import { useEffect, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { Link, useNavigate, useSearchParams } from 'react-router-dom';
import { CreditCard, Plus, ShoppingBag, Truck } from 'lucide-react';
import { PERMISSIONS, type PaginatedResult } from '@erp/shared';
import { Button } from '@/components/ui/button';
import { Skeleton } from '@/components/ui/skeleton';
import { DataTablePagination, ListPageHeader, SearchInput } from '@/components/list';
import { api } from '@/lib/api';
import { useAuthStore } from '@/stores/auth.store';
import { useDebouncedValue } from '@/lib/use-debounced-value';
import { money, date } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';
import { cn } from '@/lib/utils';
import { PaymentTypeTag, PoStatusPill, PO_PAYMENT_STATUS_LABELS } from './po-ui';

interface PurchaseOrder {
  id: string;
  orderNumber: string;
  partnerId: string;
  partner?: { id: string; name: string } | null;
  status: string;
  paymentType: string;
  paymentStatus: string | null;
  description: string | null;
  totalAmount: number | string;
  totalPaid: number | string;
  orderDate: string;
  expectedDeliveryDate: string | null;
  currencyCode: string;
}

/** Status tabs; each maps to the list route's own filters (status, paymentType, paymentStatus). */
const TABS: Array<{ key: string; label: string; filter: Record<string, string> }> = [
  { key: '', label: tx('All'), filter: {} },
  { key: 'active', label: tx('To receive'), filter: { status: 'active' } },
  { key: 'partially_received', label: tx('Partially received'), filter: { status: 'partially_received' } },
  { key: 'to_pay', label: tx('To pay'), filter: { status: 'received', paymentType: 'credit', paymentStatus: 'not_paid' } },
  { key: 'received', label: tx('Received'), filter: { status: 'received' } },
  { key: 'cancelled', label: tx('Cancelled'), filter: { status: 'cancelled' } },
];

const PAGE_SIZE = 50;

/** Purchase order register, laid out like the sales order register. */
export function PurchaseOrdersPage() {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const [params, setParams] = useSearchParams();
  const has = useAuthStore((s) => s.hasPermission);
  const orgCurrency = useAuthStore((s) => s.organization?.currencyCode);
  const P = PERMISSIONS.procurement.purchaseOrder;

  const set = (k: string, v: string) => setParams((p) => {
    const n = new URLSearchParams(p);
    if (v) n.set(k, v); else n.delete(k);
    if (k !== 'page') n.delete('page');
    return n;
  }, { replace: true });

  const tab = params.get('tab') ?? '';
  const def = TABS.find((x) => x.key === tab) ?? TABS[0];
  const paymentType = params.get('type') ?? '';
  const paymentStatus = params.get('pay') ?? '';
  const page = Number(params.get('page') ?? 1);

  // Search typed here, debounced into the URL.
  const [searchInput, setSearchInput] = useState(params.get('q') ?? '');
  const search = useDebouncedValue(searchInput, 300);
  useEffect(() => {
    if (search !== (params.get('q') ?? '')) set('q', search);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [search]);

  const queryParams: Record<string, string | number> = { page, pageSize: PAGE_SIZE };
  if (paymentType) queryParams.paymentType = paymentType;
  if (paymentStatus) queryParams.paymentStatus = paymentStatus;
  Object.assign(queryParams, def.filter);
  if (params.get('q')) queryParams.search = params.get('q')!;
  if (params.get('from')) queryParams.dateFrom = params.get('from')!;
  if (params.get('to')) queryParams.dateTo = params.get('to')!;

  const { data, isLoading } = useQuery<PaginatedResult<PurchaseOrder>>({
    queryKey: ['purchase-orders', queryParams],
    queryFn: async () =>
      (await api.get<PaginatedResult<PurchaseOrder>>('/procurement/purchase-orders', { params: queryParams })).data,
  });

  const rows = data?.data ?? [];
  const meta = data?.meta;
  // The route returns no grand total: the sum is shown only when every match is on this page.
  const singlePage = !!meta && meta.totalPages <= 1;
  const currencies = new Set(rows.map((r) => r.currencyCode ?? orgCurrency));
  const pageTotal = rows.reduce((s, r) => s + Number(r.totalAmount ?? 0), 0);
  const selectCls = 'h-9 rounded-md border bg-background px-2 text-sm';

  return (
    <div className="space-y-4">
      <ListPageHeader
        icon={ShoppingBag}
        title={t('procurement.purchaseOrders.title')}
        description={t('procurement.purchaseOrders.description')}
        actions={has(P.create) && (
          <Button onClick={() => navigate('/procurement/purchase-orders/new')}><Plus className="mr-1 h-4 w-4" />{tx('New Purchase')}</Button>
        )}
      />

      <div className="flex flex-wrap items-center gap-2">
        <div className="flex flex-wrap rounded-lg border bg-card p-0.5">
          {TABS.map((x) => (
            <button key={x.key} type="button" onClick={() => set('tab', x.key)}
              className={cn('rounded-md px-3 py-1.5 text-sm', def.key === x.key ? 'bg-primary text-primary-foreground' : 'hover:bg-muted')}>
              {x.label}
            </button>
          ))}
        </div>
        <SearchInput value={searchInput} onChange={setSearchInput} placeholder={tx('Number, supplier, description…')} />
        <select aria-label={tx('Payment type')} className={selectCls} value={paymentType} onChange={(e) => set('type', e.target.value)}
          disabled={def.key === 'to_pay'}>
          <option value="">{tx('All types')}</option>
          <option value="cash">{tx('Cash')}</option>
          <option value="credit">{tx('Credit')}</option>
        </select>
        <select aria-label={tx('Payment status')} className={selectCls} value={paymentStatus} onChange={(e) => set('pay', e.target.value)}
          disabled={def.key === 'to_pay'}>
          <option value="">{tx('All payments')}</option>
          <option value="not_paid">{tx('Unpaid')}</option>
          <option value="partial">{tx('Partial')}</option>
          <option value="paid">{tx('Paid')}</option>
        </select>
        <input type="date" aria-label={tx('From')} className={selectCls} value={params.get('from') ?? ''} onChange={(e) => set('from', e.target.value)} />
        <input type="date" aria-label={tx('To')} className={selectCls} value={params.get('to') ?? ''} onChange={(e) => set('to', e.target.value)} />
      </div>

      <div className="overflow-x-auto rounded-xl border bg-card shadow-sm">
        <table className="w-full min-w-[960px] text-sm">
          <thead className="bg-muted/40 text-left text-xs font-semibold uppercase tracking-wide text-muted-foreground">
            <tr>
              <th className="px-3 py-2">{tx('Number')}</th>
              <th className="px-3 py-2">{tx('Supplier')}</th>
              <th className="px-3 py-2">{tx('Order Date')}</th>
              <th className="px-3 py-2">{tx('Expected')}</th>
              <th className="px-3 py-2">{tx('Status')}</th>
              <th className="px-3 py-2">{tx('Payment')}</th>
              <th className="px-3 py-2 text-right">{tx('Total')}</th>
              <th className="w-28 px-3 py-2" />
            </tr>
          </thead>
          <tbody>
            {isLoading && <tr><td colSpan={8} className="p-3"><Skeleton className="h-32 w-full" /></td></tr>}
            {rows.map((po) => {
              const canReceive = (po.status === 'active' || po.status === 'partially_received') && has(P.receive);
              const canPay = po.status === 'received' && po.paymentType === 'credit' && po.paymentStatus !== 'paid' && has(P.pay);
              return (
                <tr key={po.id} className="cursor-pointer border-t hover:bg-muted/30" onClick={() => navigate(`/procurement/purchase-orders/${po.id}`)}>
                  <td className="px-3 py-2 font-medium">{po.orderNumber}</td>
                  <td className="px-3 py-2">
                    {po.partner?.name ?? '—'}
                    {po.description && <span className="block max-w-xs truncate text-xs text-muted-foreground">{po.description}</span>}
                  </td>
                  <td className="px-3 py-2">{date(po.orderDate)}</td>
                  <td className="px-3 py-2">{po.expectedDeliveryDate ? date(po.expectedDeliveryDate) : '—'}</td>
                  <td className="px-3 py-2"><PoStatusPill status={po.status} /></td>
                  <td className="px-3 py-2 text-xs">
                    <span className="inline-flex items-center gap-1.5">
                      <PaymentTypeTag type={po.paymentType} />
                      {po.paymentType === 'credit'
                        ? <span>{PO_PAYMENT_STATUS_LABELS[po.paymentStatus ?? 'not_paid'] ?? po.paymentStatus}</span>
                        : po.status === 'received' && <span className="text-muted-foreground">{tx('Auto-paid')}</span>}
                    </span>
                  </td>
                  <td className="px-3 py-2 text-right tabular-nums">{money(po.totalAmount, po.currencyCode ?? orgCurrency)}</td>
                  <td className="px-3 py-1.5 text-right">
                    {canReceive && (
                      <Button size="sm" variant="outline" onClick={(e) => { e.stopPropagation(); navigate(`/procurement/purchase-orders/${po.id}/receive`); }}>
                        <Truck className="mr-1 h-3.5 w-3.5" />{tx('Receive')}
                      </Button>
                    )}
                    {canPay && (
                      <Button size="sm" variant="outline" onClick={(e) => { e.stopPropagation(); navigate(`/procurement/purchase-orders/${po.id}/pay`); }}>
                        <CreditCard className="mr-1 h-3.5 w-3.5" />{tx('Pay')}
                      </Button>
                    )}
                  </td>
                </tr>
              );
            })}
            {data && rows.length === 0 && (
              <tr><td colSpan={8} className="p-6 text-center text-muted-foreground">
                {t('procurement.purchaseOrders.noData')}{' '}
                {has(P.create) && <Link to="/procurement/purchase-orders/new" className="text-primary underline">{tx('Create one')}</Link>}
              </td></tr>
            )}
          </tbody>
          {meta && rows.length > 0 && (
            <tfoot>
              <tr className="border-t font-semibold">
                <td colSpan={6} className="px-3 py-2">{meta.total} {tx('orders')}</td>
                <td className="px-3 py-2 text-right tabular-nums">
                  {singlePage && currencies.size === 1 ? money(pageTotal, [...currencies][0]) : ''}
                </td>
                <td />
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

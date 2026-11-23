import { useQuery } from '@tanstack/react-query';
import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Eye, RefreshCw } from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { Card } from '@/components/ui/card';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Badge } from '@/components/ui/badge';
import { Skeleton } from '@/components/ui/skeleton';
import {
  Select, SelectContent, SelectItem, SelectTrigger, SelectValue,
} from '@/components/ui/select';
import { PRODUCT_PICKER_PANEL, SearchableSelect } from '@/components/ui/searchable-select';
import { locationOption, useStockLocations } from '@/features/inventory/locations';
import { useProductSearch } from '@/features/products/product-search';
import { api } from '@/lib/api';
import { formatMoney, dateTime } from '@/lib/format';
import { referenceLabel } from '@/lib/ledger-links';
import { useAuthStore } from '@/stores/auth.store';
import { DirectStockActions } from './DirectStockActions';
import { tx } from '@/lib/i18n/tx';

interface LedgerEntry {
  id: string;
  ledgerCode: string;
  productId: string;
  type: string;
  qtyBefore: number;
  quantityChange: number;
  balanceAfter: number;
  /** Null without `inventory_valuation:read` (hardening R5b): formatMoney shows a dash. */
  unitCost: number | null;
  totalValue: number | null;
  referenceType: string | null;
  referenceId: string | null;
  notes: string | null;
  performedBy: string | null;
  createdAt: string;
  product: { id: string; code: string; name: string };
  variant: { id: string; name: string } | null;
  location: { id: string; code: string; name: string };
  batch: { id: string; batchNumber: string; expiryDate: string } | null;
}

interface LedgerMeta { page: number; pageSize: number; total: number; totalPages: number }

const MOVE_TYPE_LABELS: Record<string, string> = {
  receipt: tx('Receipt'),
  issue: tx('Issue'),
  adjustment_in: tx('Adj In'),
  adjustment_out: tx('Adj Out'),
  transfer_in: tx('Transfer In'),
  transfer_out: tx('Transfer Out'),
  opening_balance: tx('Opening Bal'),
  waste: tx('Waste'),
  return_in: tx('Return In'),
  return_to_supplier: tx('Rtn to Supplier'),
  expiry_write_off: tx('Expiry Write-Off'),
};

function MoveBadge({ type }: { type: string }) {
  const isIn = type.includes('in') || type === 'receipt' || type === 'opening_balance' || type === 'return_in';
  return (
    <Badge variant={isIn ? 'default' : 'destructive'} className={isIn ? 'bg-emerald-100 text-emerald-800' : undefined}>
      {MOVE_TYPE_LABELS[type] ?? type}
    </Badge>
  );
}

export function StockLedgerPage() {
  const navigate = useNavigate();
  const [page, setPage] = useState(1);
  const [productId, setProductId] = useState('');
  const [locationId, setLocationId] = useState('');
  const [typeFilter, setTypeFilter] = useState('');
  const [refTypeFilter, setRefTypeFilter] = useState('');
  const [dateFrom, setDateFrom] = useState('');
  const [dateTo, setDateTo] = useState('');

  const locations = useStockLocations();
  // A product picker searched on the server, instead of a typed product id that refetched the ledger per keystroke.
  // It lists products (product:read), which the ledger itself does not need: without it the filter is not offered.
  const canPickProduct = useAuthStore((s) => s.hasPermission)(PERMISSIONS.product.read);
  const products = useProductSearch<{ id: string; code: string; name: string }>({ enabled: canPickProduct });

  const params = new URLSearchParams();
  params.set('page', String(page));
  params.set('pageSize', '25');
  if (productId) params.set('productId', productId);
  if (locationId) params.set('locationId', locationId);
  if (typeFilter) params.set('type', typeFilter);
  if (refTypeFilter) params.set('referenceType', refTypeFilter);
  if (dateFrom) params.set('dateFrom', dateFrom);
  if (dateTo) params.set('dateTo', dateTo);

  const ledger = useQuery<{ data: LedgerEntry[]; meta: LedgerMeta }>({
    queryKey: ['inventory-ledger', page, productId, locationId, typeFilter, refTypeFilter, dateFrom, dateTo],
    queryFn: async () => (await api.get(`/inventory/ledger?${params.toString()}`)).data,
  });

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <h1 className="text-2xl font-semibold">{tx('Stock Ledger')}</h1>
        <div className="flex items-center gap-2">
          <DirectStockActions />
          <Button variant="outline" size="sm" onClick={() => ledger.refetch()}>
            <RefreshCw className="mr-2 h-3 w-3" />{tx('Refresh')}
          </Button>
        </div>
      </div>

      <Card className="p-3">
        <div className="flex flex-wrap items-end gap-2">
          {canPickProduct && <div className="flex-1 min-w-[200px]">
            <label className="mb-1 block text-xs text-muted-foreground">{tx('Product')}</label>
            <SearchableSelect
              value={productId}
              onValueChange={(v) => { setProductId(v); setPage(1); }}
              options={products.options}
              onQueryChange={products.onQueryChange}
              loading={products.loading}
              footer={products.footer}
              selectedLabel={products.labelOf(productId)}
              placeholder={tx('All products')}
              clearLabel={tx('All products')}
              searchPlaceholder={tx('Search by code or name…')}
              contentClassName={PRODUCT_PICKER_PANEL}
              wrap
            />
          </div>}
          <div>
            <label className="mb-1 block text-xs text-muted-foreground">{tx('Location')}</label>
            <SearchableSelect
              className="w-44"
              value={locationId}
              onValueChange={(v) => { setLocationId(v); setPage(1); }}
              options={(locations.locations ?? []).map(locationOption)}
              footer={locations.footer}
              placeholder={tx('All locations')}
              clearLabel={tx('All locations')}
              searchPlaceholder={tx('Search locations…')}
            />
          </div>
          <div>
            <label className="mb-1 block text-xs text-muted-foreground">{tx('Type')}</label>
            <Select value={typeFilter} onValueChange={(v) => { setTypeFilter(v); setPage(1); }}>
              <SelectTrigger className="w-36"><SelectValue placeholder={tx('All types')} /></SelectTrigger>
              <SelectContent>
                <SelectItem value="">{tx('All types')}</SelectItem>
                {Object.entries(MOVE_TYPE_LABELS).map(([k, v]) => (
                  <SelectItem key={k} value={k}>{v}</SelectItem>
                ))}
              </SelectContent>
            </Select>
          </div>
          <div>
            <label className="mb-1 block text-xs text-muted-foreground">{tx('Reference')}</label>
            <Select value={refTypeFilter} onValueChange={(v) => { setRefTypeFilter(v); setPage(1); }}>
              <SelectTrigger className="w-36"><SelectValue placeholder={tx('All')} /></SelectTrigger>
              <SelectContent>
                <SelectItem value="">{tx('All references')}</SelectItem>
                <SelectItem value="direct_stock_in">{tx('Direct Stock In')}</SelectItem>
                <SelectItem value="direct_stock_out">{tx('Direct Stock Out')}</SelectItem>
                <SelectItem value="stock_out">{tx('Stock Out')}</SelectItem>
                <SelectItem value="waste">{tx('Waste')}</SelectItem>
              </SelectContent>
            </Select>
          </div>
          <div>
            <label className="mb-1 block text-xs text-muted-foreground">{tx('From')}</label>
            <Input type="date" className="w-36" value={dateFrom} onChange={(e) => { setDateFrom(e.target.value); setPage(1); }} />
          </div>
          <div>
            <label className="mb-1 block text-xs text-muted-foreground">{tx('To')}</label>
            <Input type="date" className="w-36" value={dateTo} onChange={(e) => { setDateTo(e.target.value); setPage(1); }} />
          </div>
        </div>
      </Card>

      {ledger.isLoading && <Skeleton className="h-64 w-full" />}

      {ledger.data && (
        <>
          <div className="rounded-md border overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="border-b bg-muted/50">
                  <th className="px-3 py-2 text-left font-medium whitespace-nowrap">{tx('Date')}</th>
                  <th className="px-3 py-2 text-left font-medium whitespace-nowrap">{tx('Code')}</th>
                  <th className="px-3 py-2 text-left font-medium">{tx('Product')}</th>
                  <th className="px-3 py-2 text-left font-medium">{tx('Location')}</th>
                  <th className="px-3 py-2 text-center font-medium">{tx('Type')}</th>
                  <th className="px-3 py-2 text-right font-medium whitespace-nowrap">{tx('Qty Before')}</th>
                  <th className="px-3 py-2 text-right font-medium whitespace-nowrap">{tx('Change')}</th>
                  <th className="px-3 py-2 text-right font-medium whitespace-nowrap">{tx('Qty After')}</th>
                  <th className="px-3 py-2 text-right font-medium whitespace-nowrap">{tx('Unit Cost')}</th>
                  <th className="px-3 py-2 text-right font-medium whitespace-nowrap">{tx('Total Value')}</th>
                  <th className="px-3 py-2 text-left font-medium">{tx('Reference')}</th>
                  <th className="px-3 py-2 text-left font-medium">{tx('Notes')}</th>
                  <th className="px-3 py-2 text-center font-medium">{tx('View')}</th>
                </tr>
              </thead>
              <tbody>
                {ledger.data.data.length === 0 && (
                  <tr><td colSpan={13} className="px-3 py-8 text-center text-muted-foreground">{tx('No ledger entries found')}</td></tr>
                )}
                {ledger.data.data.map((entry) => (
                  <tr key={entry.id} className="border-b hover:bg-muted/30 cursor-pointer" onClick={() => navigate(`/inventory/items/${entry.productId}`)}>
                    <td className="px-3 py-2 text-muted-foreground whitespace-nowrap text-xs">{dateTime(entry.createdAt)}</td>
                    <td className="px-3 py-2 font-mono text-xs text-muted-foreground">{entry.ledgerCode}</td>
                    <td className="px-3 py-2 font-medium hover:text-primary hover:underline whitespace-nowrap">
                      {entry.product.name}
                      {entry.variant && <span className="text-muted-foreground"> · {entry.variant.name}</span>}
                    </td>
                    <td className="px-3 py-2 text-muted-foreground">{entry.location.code}</td>
                    <td className="px-3 py-2 text-center"><MoveBadge type={entry.type} /></td>
                    <td className="px-3 py-2 text-right font-mono tabular-nums">{entry.qtyBefore}</td>
                    <td className={`px-3 py-2 text-right font-mono tabular-nums ${entry.quantityChange > 0 ? 'text-emerald-600' : entry.quantityChange < 0 ? 'text-destructive' : ''}`}>
                      {entry.quantityChange > 0 ? '+' : ''}{entry.quantityChange}
                    </td>
                    <td className="px-3 py-2 text-right font-mono tabular-nums">{entry.balanceAfter}</td>
                    <td className="px-3 py-2 text-right font-mono tabular-nums">{formatMoney(entry.unitCost)}</td>
                    <td className="px-3 py-2 text-right font-mono tabular-nums">{formatMoney(entry.totalValue)}</td>
                    <td className="px-3 py-2 text-xs text-muted-foreground">
                      {entry.referenceType && entry.referenceId
                        ? <span>{referenceLabel(entry.referenceType)}<br/><span className="font-mono">{entry.referenceId}</span></span>
                        : '—'}
                    </td>
                    <td className="px-3 py-2 text-xs text-muted-foreground max-w-[120px] truncate">{entry.notes ?? '—'}</td>
                    <td className="px-3 py-2 text-center">
                      <Button
                        size="sm"
                        variant="outline"
                        onClick={(e) => { e.stopPropagation(); navigate(`/inventory/ledger/${entry.id}`); }}
                        title={tx('Open the transaction this movement came from')}
                      >
                        <Eye className="mr-1 h-3 w-3" />{tx('View')}
                      </Button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <div className="flex items-center justify-between text-sm text-muted-foreground">
            <span>{ledger.data.meta.total} {tx('entry(ies)')}</span>
            <div className="flex items-center gap-2">
              <Button size="sm" variant="outline" disabled={page <= 1} onClick={() => setPage((p) => p - 1)}>{tx('Previous')}</Button>
              <span>{tx('Page')} {ledger.data.meta.page} {tx('of')} {ledger.data.meta.totalPages}</span>
              <Button size="sm" variant="outline" disabled={page >= ledger.data.meta.totalPages} onClick={() => setPage((p) => p + 1)}>{tx('Next')}</Button>
            </div>
          </div>
        </>
      )}
    </div>
  );
}

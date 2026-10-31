import { useState, type FormEvent } from 'react';
import { Link } from 'react-router-dom';
import { Plus, Tags } from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { DataTable, type Column } from '@/components/data-table';
import {
  DataTablePagination,
  FilterSelect,
  ListCard,
  ListPageHeader,
  ListToolbar,
  SearchInput,
  useListState,
} from '@/components/list';
import { usePartners } from '@/features/partners/api';
import { useProducts } from '@/features/products/api';
import {
  useCancelPriceAgreement,
  useCreatePriceAgreement,
  useEndPriceAgreement,
  usePriceAgreements,
  type PriceAgreement,
} from '@/features/procurement/api';
import { date, formatMoney, statusLabel } from '@/lib/format';
import { apiMessage, notify } from '@/lib/notify';
import { useAuthStore } from '@/stores/auth.store';
import { tx } from '@/lib/i18n/tx';

const selectClass = 'flex h-10 w-full rounded-md border border-input bg-background px-3 py-2 text-sm';

const STATE_VARIANT: Record<PriceAgreement['state'], 'default' | 'outline' | 'destructive'> = {
  active: 'default',
  expired: 'outline',
  cancelled: 'destructive',
};

/** Agreed supplier prices: a new price is a new agreement, so history keeps what applied then. */
export function PriceAgreementsPage() {
  const list = useListState({ status: '', partnerId: '' });
  const { data, isLoading, isError } = usePriceAgreements({
    page: list.page,
    pageSize: list.pageSize,
    search: list.search || undefined,
    status: list.filters.status || undefined,
    partnerId: list.filters.partnerId || undefined,
  });
  const has = useAuthStore((s) => s.hasPermission);
  const org = useAuthStore((s) => s.organization);
  const canManage = has(PERMISSIONS.procurement.priceAgreement.manage);
  const { data: partners } = usePartners({ page: 1, pageSize: 200 });
  const { data: products } = useProducts({ page: 1, pageSize: 200 });
  const suppliers = (partners?.data ?? []).filter((p) => p.isSupplier);

  const create = useCreatePriceAgreement();
  const end = useEndPriceAgreement();
  const cancel = useCancelPriceAgreement();

  const today = new Date().toISOString().slice(0, 10);
  const [open, setOpen] = useState(false);
  const [partnerId, setPartnerId] = useState('');
  const [productId, setProductId] = useState('');
  const [unitPrice, setUnitPrice] = useState('');
  const [currencyCode, setCurrencyCode] = useState(org?.currencyCode ?? '');
  const [minQuantity, setMinQuantity] = useState('');
  const [validFrom, setValidFrom] = useState(today);
  const [validTo, setValidTo] = useState('');
  const [reference, setReference] = useState('');
  const [ending, setEnding] = useState<PriceAgreement | null>(null);
  const [endDate, setEndDate] = useState(today);

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    try {
      await create.mutateAsync({
        partnerId,
        productId,
        unitPrice: Number(unitPrice),
        currencyCode,
        minQuantity: minQuantity === '' ? undefined : Number(minQuantity),
        validFrom,
        validTo: validTo || undefined,
        reference: reference || undefined,
      });
      setOpen(false);
      setUnitPrice('');
      setMinQuantity('');
      setValidTo('');
      setReference('');
    } catch (error) {
      notify.error(apiMessage(error, tx('Could not save the agreement')));
    }
  };

  const confirmEnd = async () => {
    if (!ending) return;
    try {
      await end.mutateAsync({
        id: ending.id,
        validTo: endDate,
        reference: ending.reference ?? undefined,
        notes: ending.notes ?? undefined,
      });
      setEnding(null);
    } catch (error) {
      notify.error(apiMessage(error, tx('Could not end the agreement')));
    }
  };

  const doCancel = async (id: string) => {
    try {
      await cancel.mutateAsync(id);
    } catch (error) {
      notify.error(apiMessage(error, tx('Could not cancel the agreement')));
    }
  };

  const columns: Column<PriceAgreement>[] = [
    { key: 'partnerName', header: tx('Supplier'), render: (a) => a.partnerName ?? '-' },
    {
      key: 'productName',
      header: tx('Product'),
      render: (a) => (
        <Link to={`/procurement/price-history?product=${a.productId}`} className="text-primary hover:underline">
          {a.productName ?? '-'}
        </Link>
      ),
    },
    {
      key: 'unitPrice',
      header: tx('Unit price'),
      className: 'text-right',
      render: (a) => formatMoney(a.unitPrice, a.currencyCode),
    },
    { key: 'minQuantity', header: tx('From quantity'), className: 'text-right', render: (a) => Number(a.minQuantity) },
    { key: 'validFrom', header: tx('Valid from'), render: (a) => date(a.validFrom) },
    { key: 'validTo', header: tx('Valid until'), render: (a) => (a.validTo ? date(a.validTo) : '-') },
    {
      key: 'state',
      header: tx('Status'),
      render: (a) => <Badge variant={STATE_VARIANT[a.state]}>{statusLabel(a.state)}</Badge>,
    },
    {
      key: 'actions',
      header: '',
      render: (a) =>
        canManage && a.state === 'active' ? (
          <div className="flex gap-1">
            <Button variant="ghost" size="sm" onClick={() => { setEnding(a); setEndDate(today); }}>{tx('End')}</Button>
            <Button variant="ghost" size="sm" onClick={() => void doCancel(a.id)} disabled={cancel.isPending}>
              {tx('Cancel')}
            </Button>
          </div>
        ) : null,
    },
  ];

  return (
    <div className="space-y-4">
      <ListPageHeader
        title={tx('Price Agreements')}
        description={tx('Prices agreed with suppliers, per base unit, over a date range.')}
        icon={Tags}
        actions={
          canManage ? (
            <Button onClick={() => setOpen(true)}><Plus className="mr-2 h-4 w-4" />{tx('New agreement')}</Button>
          ) : undefined
        }
      />
      <ListToolbar>
        <SearchInput
          value={list.searchInput}
          onChange={list.setSearchInput}
          placeholder={tx('Search supplier, product or reference...')}
        />
        <FilterSelect
          value={list.filters.status}
          onChange={(v) => list.setFilter('status', v)}
          allLabel={tx('All statuses')}
          options={[
            { value: 'active', label: tx('Active') },
            { value: 'expired', label: tx('Expired') },
            { value: 'cancelled', label: tx('Cancelled') },
          ]}
        />
        <FilterSelect
          value={list.filters.partnerId}
          onChange={(v) => list.setFilter('partnerId', v)}
          allLabel={tx('All suppliers')}
          options={suppliers.map((p) => ({ value: p.id, label: p.name }))}
          className="w-[200px]"
        />
      </ListToolbar>
      {isError && <p className="text-sm text-destructive">{tx('Could not load price agreements.')}</p>}
      <ListCard>
        <DataTable
          columns={columns}
          data={data?.data ?? []}
          loading={isLoading}
          getRowId={(a) => a.id}
          emptyMessage={tx('No price agreements yet.')}
        />
        {data?.meta && (
          <DataTablePagination
            page={data.meta.page}
            pageSize={data.meta.pageSize}
            total={data.meta.total}
            totalPages={data.meta.totalPages}
            onPageChange={list.setPage}
            onPageSizeChange={list.setPageSize}
            noun={tx('agreement')}
          />
        )}
      </ListCard>

      <Dialog open={open} onOpenChange={setOpen}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>{tx('New agreement')}</DialogTitle>
            <DialogDescription>{tx('To change a price, end the current agreement and add a new one.')}</DialogDescription>
          </DialogHeader>
          <form onSubmit={submit} className="space-y-3">
            <div className="space-y-2">
              <Label htmlFor="agreementSupplier">{tx('Supplier')}</Label>
              <select
                id="agreementSupplier"
                className={selectClass}
                value={partnerId}
                onChange={(e) => setPartnerId(e.target.value)}
                required
              >
                <option value="">{tx('Select supplier')}</option>
                {suppliers.map((p) => <option key={p.id} value={p.id}>{p.name}</option>)}
              </select>
            </div>
            <div className="space-y-2">
              <Label htmlFor="agreementProduct">{tx('Product')}</Label>
              <select
                id="agreementProduct"
                className={selectClass}
                value={productId}
                onChange={(e) => setProductId(e.target.value)}
                required
              >
                <option value="">{tx('Select product')}</option>
                {(products?.data ?? []).map((p) => <option key={p.id} value={p.id}>{p.name}</option>)}
              </select>
            </div>
            <div className="grid gap-3 sm:grid-cols-3">
              <div className="space-y-2">
                <Label htmlFor="agreementPrice">{tx('Unit price')}</Label>
                <Input
                  id="agreementPrice"
                  type="number"
                  step="any"
                  min="0"
                  value={unitPrice}
                  onChange={(e) => setUnitPrice(e.target.value)}
                  required
                />
              </div>
              <div className="space-y-2">
                <Label htmlFor="agreementCurrency">{tx('Currency')}</Label>
                <Input
                  id="agreementCurrency"
                  value={currencyCode}
                  onChange={(e) => setCurrencyCode(e.target.value)}
                  required
                />
              </div>
              <div className="space-y-2">
                <Label htmlFor="agreementMin">{tx('From quantity')}</Label>
                <Input
                  id="agreementMin"
                  type="number"
                  step="any"
                  min="0"
                  value={minQuantity}
                  onChange={(e) => setMinQuantity(e.target.value)}
                />
              </div>
              <div className="space-y-2">
                <Label htmlFor="agreementFrom">{tx('Valid from')}</Label>
                <Input
                  id="agreementFrom"
                  type="date"
                  value={validFrom}
                  onChange={(e) => setValidFrom(e.target.value)}
                  required
                />
              </div>
              <div className="space-y-2">
                <Label htmlFor="agreementTo">{tx('Valid until')}</Label>
                <Input
                  id="agreementTo"
                  type="date"
                  min={validFrom}
                  value={validTo}
                  onChange={(e) => setValidTo(e.target.value)}
                />
              </div>
              <div className="space-y-2">
                <Label htmlFor="agreementRef">{tx('Reference')}</Label>
                <Input id="agreementRef" value={reference} onChange={(e) => setReference(e.target.value)} />
              </div>
            </div>
            <DialogFooter>
              <Button type="button" variant="outline" onClick={() => setOpen(false)}>{tx('Cancel')}</Button>
              <Button type="submit" disabled={create.isPending}>{tx('Save')}</Button>
            </DialogFooter>
          </form>
        </DialogContent>
      </Dialog>

      <Dialog open={!!ending} onOpenChange={(o) => !o && setEnding(null)}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>{tx('End agreement')}</DialogTitle>
            <DialogDescription>{ending ? `${ending.partnerName ?? ''} · ${ending.productName ?? ''}` : ''}</DialogDescription>
          </DialogHeader>
          <div className="space-y-2">
            <Label htmlFor="endDate">{tx('Valid until')}</Label>
            <Input id="endDate" type="date" value={endDate} onChange={(e) => setEndDate(e.target.value)} />
          </div>
          <DialogFooter>
            <Button variant="outline" onClick={() => setEnding(null)}>{tx('Cancel')}</Button>
            <Button onClick={() => void confirmEnd()} disabled={end.isPending}>{tx('Save')}</Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  );
}

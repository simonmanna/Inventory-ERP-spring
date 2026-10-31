import { useState } from 'react';
import { Link } from 'react-router-dom';
import { Plus, RefreshCw, Smartphone } from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { Button } from '@/components/ui/button';
import { Dialog, DialogContent, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { Input } from '@/components/ui/input';
import { DataTable, type Column } from '@/components/data-table';
import {
  DataTablePagination, FilterSelect, ListCard, ListPageHeader, ListToolbar, StatusPill, useListState, type Tone,
} from '@/components/list';
import { useAuthStore } from '@/stores/auth.store';
import { dateTime, money } from '@/lib/format';
import { apiMessage, notify } from '@/lib/notify';
import { useDebouncedValue } from '@/lib/use-debounced-value';
import { tx } from '@/lib/i18n/tx';
import { Field, RemotePicker } from '@/pages/fleet/fleet-ui';
import { usePartners } from '@/features/partners/api';
import {
  useMobileMoneyCollections, useMobileMoneySettings, useRefreshCollection, useStartCollection,
  type CollectionStatus, type MobileMoneyCollection,
} from '@/features/invoicing/mobile-money-api';

const STATUS: Record<CollectionStatus, { label: string; tone: Tone }> = {
  pending: { label: tx('Waiting for customer'), tone: 'warning' },
  successful: { label: tx('Received'), tone: 'success' },
  failed: { label: tx('Failed'), tone: 'danger' },
};

/** Request a payment from a customer's phone and follow it (INT-1). */
export function MobileMoneyPage() {
  const list = useListState({ status: '' });
  const canCreate = useAuthStore((s) => s.hasPermission(PERMISSIONS.payment.create));
  const settings = useMobileMoneySettings();
  const { data, isLoading, isError } = useMobileMoneyCollections({
    page: list.page, pageSize: list.pageSize, status: list.filters.status || undefined,
  });
  const refresh = useRefreshCollection();
  const [starting, setStarting] = useState(false);

  const check = async (c: MobileMoneyCollection) => {
    try {
      const after = await refresh.mutateAsync(c.id);
      notify.success(STATUS[after.status].label);
    } catch (err) {
      notify.error(apiMessage(err, tx('Could not check the payment')));
    }
  };

  const columns: Column<MobileMoneyCollection>[] = [
    { key: 'reference', header: tx('Reference'), render: (c) => <span className="font-medium">{c.reference}</span> },
    { key: 'customer', header: tx('Customer'), render: (c) => c.partnerName },
    { key: 'phone', header: tx('Phone'), render: (c) => <span className="tabular-nums">{c.msisdnMasked}</span> },
    { key: 'amount', header: tx('Amount'), className: 'text-right tabular-nums', render: (c) => money(c.amount) },
    { key: 'at', header: tx('Requested'), render: (c) => dateTime(c.createdAt) },
    {
      key: 'status', header: tx('Status'),
      render: (c) => (
        <div>
          <StatusPill tone={STATUS[c.status].tone}>{STATUS[c.status].label}</StatusPill>
          {c.failureReason && <div className="text-xs text-muted-foreground">{c.failureReason}</div>}
        </div>
      ),
    },
    {
      key: 'receipt', header: tx('Receipt'),
      render: (c) => (c.paymentId ? <Link className="text-primary hover:underline" to={`/payments/${c.paymentId}`}>{c.paymentNumber}</Link> : '—'),
    },
    {
      key: 'actions', header: '', className: 'w-12 text-right',
      render: (c) => c.status === 'pending' && canCreate && (
        <Button variant="ghost" size="icon" className="h-8 w-8" aria-label={tx('Check now')} disabled={refresh.isPending} onClick={() => check(c)}>
          <RefreshCw className="h-4 w-4" />
        </Button>
      ),
    },
  ];

  return (
    <div className="space-y-4">
      <ListPageHeader icon={Smartphone} title={tx('Mobile Money')}
        description={settings.data?.provider === 'mock'
          ? tx('Test provider: no money moves until a real provider is configured.')
          : tx('Ask a customer to approve a payment on their phone. A received payment becomes a customer receipt.')}
        actions={canCreate && <Button onClick={() => setStarting(true)}><Plus className="mr-1.5 h-4 w-4" /> {tx('Request payment')}</Button>} />
      <ListToolbar>
        <FilterSelect value={list.filters.status} onChange={(v) => list.setFilter('status', v)} allLabel={tx('All statuses')}
          options={(Object.keys(STATUS) as CollectionStatus[]).map((s) => ({ value: s, label: STATUS[s].label }))} />
      </ListToolbar>
      <ListCard>
        <DataTable columns={columns} data={data?.data ?? []} loading={isLoading} getRowId={(c) => c.id}
          cellClassName="py-2.5 px-4" headerRowClassName="h-10"
          emptyMessage={isError ? tx('Could not load the payments. Try again.') : tx('No mobile money requests yet.')} />
        {data?.meta && (
          <DataTablePagination page={data.meta.page} pageSize={list.pageSize} total={data.meta.total} totalPages={data.meta.totalPages}
            onPageChange={list.setPage} onPageSizeChange={list.setPageSize} noun="request" />
        )}
      </ListCard>
      {starting && <RequestDialog onClose={() => setStarting(false)} />}
    </div>
  );
}

function RequestDialog({ onClose }: { onClose: () => void }) {
  const [customer, setCustomer] = useState<{ id: string; label: string } | null>(null);
  const [search, setSearch] = useState('');
  const debounced = useDebouncedValue(search, 300);
  const partners = usePartners({ page: 1, pageSize: 20, search: debounced || undefined }, { enabled: debounced.length > 0 });
  const [amount, setAmount] = useState('');
  const [msisdn, setMsisdn] = useState('');
  const start = useStartCollection();
  const phoneOk = /^\+?[0-9]{9,15}$/.test(msisdn.trim());
  const ready = !!customer && Number(amount) > 0 && phoneOk;

  const submit = async () => {
    if (!customer) return;
    try {
      const c = await start.mutateAsync({ partnerId: customer.id, amount: Number(amount), msisdn: msisdn.trim() });
      if (c.status === 'failed') notify.error(c.failureReason ?? tx('The provider refused the payment'));
      else notify.success(tx('Request {{ref}} sent', { ref: c.reference }));
      onClose();
    } catch (err) {
      notify.error(apiMessage(err, tx('Could not send the request')));
    }
  };

  return (
    <Dialog open onOpenChange={(o) => !o && onClose()}>
      <DialogContent>
        <DialogHeader><DialogTitle>{tx('Request payment')}</DialogTitle></DialogHeader>
        <div className="space-y-3">
          <Field label={tx('Customer *')}>
            <RemotePicker value={customer?.id ?? ''} selectedLabel={customer?.label} search={search} onSearch={setSearch}
              results={(partners.data?.data ?? []).map((p) => ({ value: p.id, label: p.name }))} loading={partners.isFetching}
              onPick={(value, label) => setCustomer(value ? { id: value, label } : null)}
              placeholder={tx('Search customers…')} clearLabel={tx('Clear')} />
          </Field>
          <Field label={tx('Amount *')}><Input type="number" inputMode="decimal" value={amount} onChange={(e) => setAmount(e.target.value)} /></Field>
          <Field label={tx('Phone number *')} error={msisdn && !phoneOk ? tx('Enter 9 to 15 digits') : undefined}>
            <Input inputMode="tel" placeholder="+256…" value={msisdn} onChange={(e) => setMsisdn(e.target.value)} />
          </Field>
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={onClose}>{tx('Cancel')}</Button>
          <Button disabled={!ready || start.isPending} onClick={submit}>{tx('Send request')}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

import { useEffect, useState } from 'react';
import { PERMISSIONS } from '@erp/shared';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Dialog, DialogContent, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { DataTable, type Column } from '@/components/data-table';
import { date, money } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';
import { useAuthStore } from '@/stores/auth.store';
import {
  useCreditCustomer, useCreditCustomers, useGrantCreditOverride, useRevokeCreditOverride, useSaveCreditPolicy,
  type CreditPosition,
} from '@/features/invoicing/credit-api';

const PAGE_SIZE = 25;

function errorText(e: unknown): string {
  const message = (e as { response?: { data?: { message?: string } } })?.response?.data?.message;
  return message ?? tx('Could not save');
}

/** Customers with a credit limit or hold: exposure including orders not yet billed (SALES-1). */
export function CreditControlPage() {
  const [search, setSearch] = useState('');
  const [status, setStatus] = useState('');
  const [page, setPage] = useState(1);
  const [openId, setOpenId] = useState<string | undefined>();
  const { data, isLoading, isError } = useCreditCustomers({
    search: search || undefined, status: status || undefined, page, pageSize: PAGE_SIZE,
  });
  const rows = data?.data ?? [];
  const meta = data?.meta;

  const columns: Column<CreditPosition>[] = [
    { key: 'partnerName', header: tx('Customer') },
    {
      key: 'creditHold',
      header: tx('Status'),
      render: (r) => (
        <span className="flex gap-1">
          {r.creditHold && <Badge variant="destructive">{tx('On hold')}</Badge>}
          {r.exceeded && <Badge variant="destructive">{tx('Over limit')}</Badge>}
          {!r.creditHold && !r.exceeded && <Badge variant="secondary">{tx('OK')}</Badge>}
        </span>
      ),
    },
    { key: 'creditLimit', header: tx('Limit'), className: 'text-right', render: (r) => (r.creditLimit > 0 ? money(r.creditLimit) : tx('No limit')) },
    { key: 'openReceivables', header: tx('Open invoices'), className: 'text-right', render: (r) => money(r.openReceivables) },
    { key: 'overdueReceivables', header: tx('Overdue'), className: 'text-right', render: (r) => money(r.overdueReceivables) },
    { key: 'unbilledOrders', header: tx('Unbilled orders'), className: 'text-right', render: (r) => money(r.unbilledOrders + r.uninvoicedDeliveries) },
    { key: 'exposure', header: tx('Exposure'), className: 'text-right font-medium', render: (r) => money(r.exposure) },
    { key: 'headroom', header: tx('Headroom'), className: 'text-right', render: (r) => (r.headroom === null ? '-' : money(r.headroom)) },
  ];

  return (
    <div className="space-y-4">
      <div>
        <h1 className="text-2xl font-semibold">{tx('Credit Control')}</h1>
        <p className="text-sm text-muted-foreground">
          {tx('Customers with a credit limit or hold; search to reach any customer. Exposure counts open invoices plus orders not yet billed.')}
        </p>
      </div>
      <div className="flex flex-wrap gap-2">
        <Input
          className="max-w-xs"
          placeholder={tx('Search customer')}
          value={search}
          onChange={(e) => { setSearch(e.target.value); setPage(1); }}
        />
        <select
          aria-label={tx('Status')}
          className="rounded border bg-background px-2 py-1 text-sm"
          value={status}
          onChange={(e) => { setStatus(e.target.value); setPage(1); }}
        >
          <option value="">{tx('All')}</option>
          <option value="hold">{tx('On hold')}</option>
          <option value="exceeded">{tx('Over limit')}</option>
        </select>
      </div>
      {isError ? (
        <p className="text-sm text-destructive">{tx('Could not load')}</p>
      ) : (
        <DataTable
          columns={columns}
          data={rows}
          loading={isLoading}
          getRowId={(r) => r.partnerId}
          onRowClick={(r) => setOpenId(r.partnerId)}
          emptyMessage={tx('No customers with a credit limit or hold.')}
        />
      )}
      {meta && meta.totalPages > 1 && (
        <div className="flex items-center justify-end gap-2 text-sm text-muted-foreground">
          <Button variant="outline" size="sm" disabled={page <= 1} onClick={() => setPage((p) => p - 1)}>{tx('Previous')}</Button>
          <span>{tx('Page')} {meta.page} {tx('of')} {meta.totalPages}</span>
          <Button variant="outline" size="sm" disabled={page >= meta.totalPages} onClick={() => setPage((p) => p + 1)}>{tx('Next')}</Button>
        </div>
      )}
      {openId && <CreditDialog partnerId={openId} onClose={() => setOpenId(undefined)} />}
    </div>
  );
}

function CreditDialog({ partnerId, onClose }: { partnerId: string; onClose: () => void }) {
  const hasPermission = useAuthStore((s) => s.hasPermission);
  const { data } = useCreditCustomer(partnerId);
  const savePolicy = useSaveCreditPolicy(partnerId);
  const grant = useGrantCreditOverride(partnerId);
  const revoke = useRevokeCreditOverride();
  const [limit, setLimit] = useState('');
  const [reason, setReason] = useState('');
  const [amount, setAmount] = useState('');
  const [coversHold, setCoversHold] = useState(false);
  const [overrideReason, setOverrideReason] = useState('');
  const [expires, setExpires] = useState('');
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (data) setLimit(String(data.creditLimit));
  }, [data]);

  const canManage = hasPermission(PERMISSIONS.creditControl.manage);
  const canOverride = hasPermission(PERMISSIONS.creditControl.override);
  const onError = (e: unknown) => setError(errorText(e));

  return (
    <Dialog open onOpenChange={(open) => { if (!open) onClose(); }}>
      <DialogContent className="max-w-lg">
        <DialogHeader>
          <DialogTitle>{data?.partnerName ?? tx('Customer')}</DialogTitle>
        </DialogHeader>
        {data && (
          <div className="space-y-4 text-sm">
            <dl className="grid grid-cols-2 gap-x-4 gap-y-1">
              <dt className="text-muted-foreground">{tx('Exposure')}</dt><dd className="text-right font-medium">{money(data.exposure)}</dd>
              <dt className="text-muted-foreground">{tx('Open invoices')}</dt><dd className="text-right">{money(data.openReceivables)}</dd>
              <dt className="text-muted-foreground">{tx('Unbilled orders')}</dt><dd className="text-right">{money(data.unbilledOrders)}</dd>
              <dt className="text-muted-foreground">{tx('Uninvoiced deliveries')}</dt><dd className="text-right">{money(data.uninvoicedDeliveries)}</dd>
            </dl>
            {canManage && (
              <div className="space-y-2 border-t pt-3">
                <Label>{tx('Credit limit (0 = no limit)')}</Label>
                <Input type="number" min={0} value={limit} onChange={(e) => setLimit(e.target.value)} />
                <Label>{tx('Reason')}</Label>
                <Input value={reason} onChange={(e) => setReason(e.target.value)} />
                <div className="flex flex-wrap gap-2">
                  <Button
                    size="sm"
                    disabled={savePolicy.isPending}
                    onClick={() => savePolicy.mutate({ creditLimit: Number(limit) || 0, reason: reason || undefined }, { onError })}
                  >
                    {tx('Save limit')}
                  </Button>
                  <Button
                    size="sm"
                    variant="outline"
                    disabled={savePolicy.isPending}
                    onClick={() => savePolicy.mutate({ creditHold: !data.creditHold, reason: reason || undefined }, { onError })}
                  >
                    {data.creditHold ? tx('Release hold') : tx('Put on hold')}
                  </Button>
                </div>
              </div>
            )}
            {canOverride && (
              <div className="space-y-2 border-t pt-3">
                <p className="font-medium">{tx('Temporary override')}</p>
                <p className="text-xs text-muted-foreground">{tx('An override does not apply to sales made by the person who granted it.')}</p>
                <Label>{tx('Extra amount')}</Label>
                <Input type="number" min={0} value={amount} onChange={(e) => setAmount(e.target.value)} />
                <label className="flex items-center gap-2">
                  <input type="checkbox" checked={coversHold} onChange={(e) => setCoversHold(e.target.checked)} />
                  {tx('Also covers the hold')}
                </label>
                <Label>{tx('Valid until')}</Label>
                <Input type="date" value={expires} onChange={(e) => setExpires(e.target.value)} />
                <Label>{tx('Reason')}</Label>
                <Input value={overrideReason} onChange={(e) => setOverrideReason(e.target.value)} />
                <Button
                  size="sm"
                  disabled={grant.isPending || !expires || !overrideReason.trim()}
                  onClick={() => grant.mutate({
                    amount: Number(amount) || 0,
                    coversHold,
                    reason: overrideReason,
                    expiresAt: new Date(`${expires}T23:59:59`).toISOString(),
                  }, { onError })}
                >
                  {tx('Grant override')}
                </Button>
              </div>
            )}
            {data.overrides.length > 0 && (
              <ul className="space-y-1 border-t pt-3">
                {data.overrides.map((o) => (
                  <li key={o.id} className="flex items-center justify-between gap-2">
                    <span>
                      {money(o.amount)}{o.coversHold ? ` · ${tx('covers hold')}` : ''} · {date(o.expiresAt)} · {o.reason}
                      {o.revokedAt ? ` · ${tx('revoked')}` : ''}
                    </span>
                    {canOverride && !o.revokedAt && (
                      <Button size="sm" variant="ghost" onClick={() => revoke.mutate(o.id, { onError })}>{tx('Revoke')}</Button>
                    )}
                  </li>
                ))}
              </ul>
            )}
            {error && <p className="text-destructive">{error}</p>}
          </div>
        )}
      </DialogContent>
    </Dialog>
  );
}

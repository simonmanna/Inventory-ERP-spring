import { useState } from 'react';
import { Globe, KeyRound, Plus } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Dialog, DialogContent, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { Input } from '@/components/ui/input';
import { DataTable, type Column } from '@/components/data-table';
import {
  DataTablePagination, ListCard, ListPageHeader, ListToolbar, SearchInput, StatusPill, useListState,
} from '@/components/list';
import { dateTime } from '@/lib/format';
import { apiMessage, notify } from '@/lib/notify';
import { useDebouncedValue } from '@/lib/use-debounced-value';
import { tx } from '@/lib/i18n/tx';
import { Field, RemotePicker } from '@/pages/fleet/fleet-ui';
import {
  useCreatePortalAccount, usePortalAccounts, usePortalCustomers, useResetPortalPassword, useSetPortalAccountActive,
  type PortalAccount,
} from '@/features/portal/api';

/** Staff screen: who may sign in to the customer portal (PORTAL-1). */
export function PortalAccountsPage() {
  const list = useListState({});
  const { data, isLoading, isFetching, isError } = usePortalAccounts({
    page: list.page, pageSize: list.pageSize, search: list.search || undefined,
  });
  const setActive = useSetPortalAccountActive();
  const [creating, setCreating] = useState(false);
  const [resetting, setResetting] = useState<PortalAccount | null>(null);

  const toggle = async (a: PortalAccount) => {
    try {
      await setActive.mutateAsync({ id: a.id, active: a.status !== 'active' });
      notify.success(a.status === 'active' ? tx('Account disabled') : tx('Account enabled'));
    } catch (err) {
      notify.error(apiMessage(err, tx('Could not change the account')));
    }
  };

  const columns: Column<PortalAccount>[] = [
    { key: 'customer', header: tx('Customer'), render: (a) => <span className="font-medium">{a.partnerName}</span> },
    { key: 'name', header: tx('Name'), render: (a) => [a.firstName, a.lastName].filter(Boolean).join(' ') },
    { key: 'email', header: tx('E-mail'), render: (a) => a.email },
    { key: 'last', header: tx('Last sign-in'), render: (a) => (a.lastLoginAt ? dateTime(a.lastLoginAt) : '—') },
    {
      key: 'status', header: tx('Status'),
      render: (a) => <StatusPill tone={a.status === 'active' ? 'success' : 'neutral'}>{a.status === 'active' ? tx('Active') : tx('Disabled')}</StatusPill>,
    },
    {
      key: 'actions', header: '', className: 'text-right whitespace-nowrap',
      render: (a) => (
        <div className="flex justify-end gap-1">
          <Button variant="ghost" size="icon" className="h-8 w-8" aria-label={tx('Set a new password')} onClick={() => setResetting(a)}><KeyRound className="h-4 w-4" /></Button>
          <Button variant="outline" size="sm" disabled={setActive.isPending} onClick={() => toggle(a)}>{a.status === 'active' ? tx('Disable') : tx('Enable')}</Button>
        </div>
      ),
    },
  ];

  return (
    <div className="space-y-4">
      <ListPageHeader icon={Globe} title={tx('Customer Portal Accounts')}
        description={tx('Logins for customers to follow their orders, deliveries, invoices and statement.')}
        actions={<Button onClick={() => setCreating(true)}><Plus className="mr-1.5 h-4 w-4" /> {tx('Open account')}</Button>} />
      <ListToolbar>
        <SearchInput value={list.searchInput} onChange={list.setSearchInput} placeholder={tx('Search customer or e-mail…')} />
      </ListToolbar>
      <ListCard>
        <div className={isFetching && !isLoading ? 'opacity-60 transition-opacity' : 'transition-opacity'}>
          <DataTable columns={columns} data={data?.data ?? []} loading={isLoading} getRowId={(a) => a.id}
            cellClassName="py-2.5 px-4" headerRowClassName="h-10"
            emptyMessage={isError ? tx('Could not load the accounts. Try again.') : tx('No portal accounts yet.')} />
        </div>
        {data?.meta && (
          <DataTablePagination page={data.meta.page} pageSize={list.pageSize} total={data.meta.total} totalPages={data.meta.totalPages}
            onPageChange={list.setPage} onPageSizeChange={list.setPageSize} noun="account" />
        )}
      </ListCard>
      {creating && <AccountDialog onClose={() => setCreating(false)} />}
      {resetting && <PasswordDialog account={resetting} onClose={() => setResetting(null)} />}
    </div>
  );
}

function AccountDialog({ onClose }: { onClose: () => void }) {
  const [customer, setCustomer] = useState<{ id: string; label: string } | null>(null);
  const [search, setSearch] = useState('');
  const debounced = useDebouncedValue(search, 300);
  const customers = usePortalCustomers(debounced, true);
  const [firstName, setFirstName] = useState('');
  const [lastName, setLastName] = useState('');
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const create = useCreatePortalAccount();
  const ready = !!customer && firstName.trim() !== '' && email.includes('@') && password.length >= 10;

  const submit = async () => {
    if (!customer) return;
    try {
      await create.mutateAsync({ partnerId: customer.id, email: email.trim(), password, firstName: firstName.trim(), lastName: lastName.trim() || undefined });
      notify.success(tx('Account opened'));
      onClose();
    } catch (err) {
      notify.error(apiMessage(err, tx('Could not open the account')));
    }
  };

  return (
    <Dialog open onOpenChange={(o) => !o && onClose()}>
      <DialogContent>
        <DialogHeader><DialogTitle>{tx('Open account')}</DialogTitle></DialogHeader>
        <div className="space-y-3">
          <Field label={tx('Customer *')}>
            <RemotePicker value={customer?.id ?? ''} selectedLabel={customer?.label} search={search} onSearch={setSearch}
              results={(customers.data ?? []).map((c) => ({ value: c.id, label: c.name, hint: c.code ?? undefined }))}
              loading={customers.isFetching} onPick={(value, label) => setCustomer(value ? { id: value, label } : null)}
              placeholder={tx('Search customers…')} clearLabel={tx('Clear')} />
          </Field>
          <div className="grid grid-cols-2 gap-3">
            <Field label={tx('First name *')}><Input value={firstName} onChange={(e) => setFirstName(e.target.value)} /></Field>
            <Field label={tx('Last name')}><Input value={lastName} onChange={(e) => setLastName(e.target.value)} /></Field>
          </div>
          <Field label={tx('E-mail *')}><Input type="email" value={email} onChange={(e) => setEmail(e.target.value)} /></Field>
          <Field label={tx('First password (at least 10 characters) *')}><Input type="password" autoComplete="new-password" value={password} onChange={(e) => setPassword(e.target.value)} /></Field>
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={onClose}>{tx('Cancel')}</Button>
          <Button disabled={!ready || create.isPending} onClick={submit}>{tx('Open account')}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

function PasswordDialog({ account, onClose }: { account: PortalAccount; onClose: () => void }) {
  const [password, setPassword] = useState('');
  const reset = useResetPortalPassword();
  const submit = async () => {
    try {
      await reset.mutateAsync({ id: account.id, password });
      notify.success(tx('Password changed'));
      onClose();
    } catch (err) {
      notify.error(apiMessage(err, tx('Could not change the password')));
    }
  };
  return (
    <Dialog open onOpenChange={(o) => !o && onClose()}>
      <DialogContent>
        <DialogHeader><DialogTitle>{tx('Set a new password')}</DialogTitle></DialogHeader>
        <Field label={account.email}><Input type="password" autoComplete="new-password" value={password} onChange={(e) => setPassword(e.target.value)} /></Field>
        <DialogFooter>
          <Button variant="outline" onClick={onClose}>{tx('Cancel')}</Button>
          <Button disabled={password.length < 10 || reset.isPending} onClick={submit}>{tx('Save')}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

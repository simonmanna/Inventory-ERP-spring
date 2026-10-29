import { useState } from 'react';
import { CreditCard, Edit, Plus, Trash2 } from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Textarea } from '@/components/ui/textarea';
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { SearchableSelect } from '@/components/ui/searchable-select';
import { DataTable, type Column } from '@/components/data-table';
import {
  DataTablePagination, FilterSelect, ListCard, ListPageHeader, ListToolbar, SearchInput, useListState,
} from '@/components/list';
import { useAuthStore } from '@/stores/auth.store';
import { notify, apiMessage } from '@/lib/notify';
import { date, money } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';
import {
  CARD_STATUS_LABEL, LIMIT_PERIOD_LABEL, useCreateFuelCard, useDeleteFuelCard, useEmployeePicker, useFuelCards,
  useFuelLookups, useUpdateFuelCard, type CardStatus, type FuelCard,
} from '@/features/fleet/fuel-api';
import { Field, OptionalSelect, RemotePicker } from '../fleet-ui';
import { toOptions } from '../fleet-format';
import { CardStatusPill } from './fuel-ui';

const FILTERS = { status: '' };

/** Fuel-card register (fleet 1.5): masked number, provider, assigned vehicle / driver, limit and current spend. */
export function FuelCardsPage() {
  const has = useAuthStore((s) => s.hasPermission);
  const list = useListState(FILTERS);
  const { data, isLoading, isError } = useFuelCards({
    page: list.page, pageSize: list.pageSize, search: list.search || undefined, status: list.filters.status || undefined,
  });
  const [editing, setEditing] = useState<FuelCard | 'new' | null>(null);
  const remove = useDeleteFuelCard();

  const del = async (c: FuelCard) => {
    if (!window.confirm(tx('Delete fuel card {{card}}?', { card: c.maskedNumber }))) return;
    try {
      await remove.mutateAsync(c.id);
      notify.success(tx('Fuel card deleted'));
    } catch (err) {
      notify.error(apiMessage(err, tx('Could not delete the fuel card')));
    }
  };

  const columns: Column<FuelCard>[] = [
    { key: 'number', header: tx('Card'), render: (c) => <span className="font-mono">{c.maskedNumber}</span> },
    { key: 'label', header: tx('Label'), render: (c) => c.label ?? '—' },
    { key: 'provider', header: tx('Provider'), render: (c) => c.provider?.name ?? '—' },
    { key: 'vehicle', header: tx('Vehicle'), render: (c) => (c.vehicle ? [c.vehicle.code, c.vehicle.registrationPlate].filter(Boolean).join(' · ') : '—') },
    { key: 'employee', header: tx('Driver'), render: (c) => c.employee?.name ?? '—' },
    {
      key: 'limit', header: tx('Limit / spent'), className: 'text-right',
      render: (c) => (
        <div className="tabular-nums">
          <div>{c.limitAmount ? `${money(c.limitAmount)} ${LIMIT_PERIOD_LABEL[c.limitPeriod].toLowerCase()}` : tx('No limit')}</div>
          <div className={c.limitRemaining && Number(c.limitRemaining) < 0 ? 'text-xs text-rose-600' : 'text-xs text-muted-foreground'}>{tx('Spent {{amount}}', { amount: money(c.periodSpend) })}</div>
        </div>
      ),
    },
    { key: 'expires', header: tx('Expires'), render: (c) => (c.expiresOn ? date(c.expiresOn) : '—') },
    { key: 'status', header: tx('Status'), render: (c) => <CardStatusPill status={c.status} /> },
    {
      key: 'actions', header: '', className: 'text-right',
      render: (c) => (
        <div className="flex justify-end gap-1">
          {has(PERMISSIONS.fleetFuel.update) && (
            <>
              <Button size="icon" variant="ghost" className="h-8 w-8" aria-label={tx('Edit')} onClick={() => setEditing(c)}><Edit className="h-4 w-4" /></Button>
              <Button size="icon" variant="ghost" className="h-8 w-8" aria-label={tx('Delete')} onClick={() => del(c)}><Trash2 className="h-4 w-4" /></Button>
            </>
          )}
        </div>
      ),
    },
  ];

  return (
    <div className="space-y-4">
      <ListPageHeader icon={CreditCard} title={tx('Fuel Cards')}
        description={tx('Card numbers are stored masked. Assign cards to vehicles and drivers and set spend limits.')}
        actions={has(PERMISSIONS.fleetFuel.create) && (
          <Button onClick={() => setEditing('new')}><Plus className="mr-1.5 h-4 w-4" /> {tx('Add card')}</Button>
        )} />
      <ListToolbar>
        <SearchInput value={list.searchInput} onChange={list.setSearchInput} placeholder={tx('Last 4 digits, label, vehicle…')} />
        <FilterSelect value={list.filters.status} onChange={(v) => list.setFilter('status', v)} options={toOptions(CARD_STATUS_LABEL)} allLabel={tx('All statuses')} />
      </ListToolbar>
      <ListCard>
        <DataTable columns={columns} data={data?.data ?? []} loading={isLoading} getRowId={(c) => c.id} cellClassName="py-2.5 px-4"
          emptyMessage={isError ? tx('Could not load the fuel cards. Try again.') : tx('No fuel cards yet.')} />
        {data?.meta && (
          <DataTablePagination page={data.meta.page} pageSize={list.pageSize} total={data.meta.total} totalPages={data.meta.totalPages}
            onPageChange={list.setPage} onPageSizeChange={list.setPageSize} noun="card" />
        )}
      </ListCard>
      {editing && <CardDialog card={editing === 'new' ? null : editing} onClose={() => setEditing(null)} />}
    </div>
  );
}

function CardDialog({ card, onClose }: { card: FuelCard | null; onClose: () => void }) {
  const lookups = useFuelLookups();
  const create = useCreateFuelCard();
  const update = useUpdateFuelCard();
  const [number, setNumber] = useState('');
  const [label, setLabel] = useState(card?.label ?? '');
  const [provider, setProvider] = useState(card?.providerPartnerId ?? '');
  const [vehicleId, setVehicleId] = useState(card?.vehicleId ?? '');
  const [employeeId, setEmployeeId] = useState(card?.employeeId ?? '');
  const [employeeLabel, setEmployeeLabel] = useState(card?.employee?.name ?? '');
  const [employeeSearch, setEmployeeSearch] = useState('');
  const employees = useEmployeePicker(employeeSearch, employeeSearch.trim().length > 0);
  const [status, setStatus] = useState<CardStatus>(card?.status ?? 'active');
  const [expiresOn, setExpiresOn] = useState(card?.expiresOn ?? '');
  const [limitAmount, setLimitAmount] = useState(card?.limitAmount ?? '');
  const [limitPeriod, setLimitPeriod] = useState<FuelCard['limitPeriod']>(card?.limitPeriod ?? 'monthly');
  const [notes, setNotes] = useState(card?.notes ?? '');
  const busy = create.isPending || update.isPending;

  const submit = async () => {
    const body: Record<string, unknown> = {
      label: label.trim() || undefined,
      providerPartnerId: provider || undefined,
      vehicleId: vehicleId || undefined,
      employeeId: employeeId || undefined,
      status,
      expiresOn: expiresOn || undefined,
      limitAmount: limitAmount ? Number(limitAmount) : undefined,
      limitPeriod,
      notes: notes.trim() || undefined,
    };
    try {
      if (card) {
        const clear = (['label', 'providerPartnerId', 'vehicleId', 'employeeId', 'expiresOn', 'limitAmount', 'notes'] as const)
          .filter((k) => body[k] === undefined && card[k] != null);
        await update.mutateAsync({ id: card.id, body: { ...body, clear } });
        notify.success(tx('Fuel card updated'));
      } else {
        await create.mutateAsync({ ...body, cardNumber: number });
        notify.success(tx('Fuel card added'));
      }
      onClose();
    } catch (err) {
      notify.error(apiMessage(err, tx('Could not save the fuel card')));
    }
  };

  return (
    <Dialog open onOpenChange={(o) => !o && onClose()}>
      <DialogContent className="max-h-[90vh] overflow-y-auto">
        <DialogHeader>
          <DialogTitle>{card ? tx('Edit fuel card {{card}}', { card: card.maskedNumber }) : tx('Add fuel card')}</DialogTitle>
          <DialogDescription>{tx('Only the last four digits are kept; the full number is never stored.')}</DialogDescription>
        </DialogHeader>
        <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
          {!card && (
            <Field label={tx('Card number *')} className="sm:col-span-2">
              <Input inputMode="numeric" autoComplete="off" value={number} onChange={(e) => setNumber(e.target.value)} />
            </Field>
          )}
          <Field label={tx('Label')}><Input value={label} onChange={(e) => setLabel(e.target.value)} /></Field>
          <Field label={tx('Status')}>
            <OptionalSelect value={status} onChange={(v) => setStatus((v || 'active') as CardStatus)} placeholder={tx('Status')} options={toOptions(CARD_STATUS_LABEL)} />
          </Field>
          <Field label={tx('Provider')} className="sm:col-span-2">
            <SearchableSelect value={provider} onValueChange={setProvider} placeholder={tx('Choose the provider')}
              options={(lookups.data?.suppliers ?? []).map((s) => ({ value: s.id, label: s.name }))} />
          </Field>
          <Field label={tx('Vehicle')}>
            <SearchableSelect value={vehicleId} onValueChange={setVehicleId} placeholder={tx('Not assigned')}
              options={(lookups.data?.vehicles ?? []).map((v) => ({ value: v.id, label: [v.code, v.registrationPlate].filter(Boolean).join(' · ') }))} />
          </Field>
          <Field label={tx('Driver')}>
            <RemotePicker value={employeeId} selectedLabel={employeeLabel} search={employeeSearch} onSearch={setEmployeeSearch}
              results={(employees.data ?? []).map((e) => ({ value: e.id, label: e.name ?? e.employeeCode, hint: e.employeeCode }))}
              loading={employees.isFetching} onPick={(v, l) => { setEmployeeId(v); setEmployeeLabel(l); }}
              placeholder={tx('Type a name or employee code…')} clearLabel={tx('Clear')} />
          </Field>
          <Field label={tx('Spend limit')}><Input type="number" inputMode="decimal" min="0" value={limitAmount} onChange={(e) => setLimitAmount(e.target.value)} /></Field>
          <Field label={tx('Limit period')}>
            <OptionalSelect value={limitPeriod} onChange={(v) => setLimitPeriod((v || 'monthly') as FuelCard['limitPeriod'])} placeholder={tx('Limit period')} options={toOptions(LIMIT_PERIOD_LABEL)} />
          </Field>
          <Field label={tx('Expires on')}><Input type="date" value={expiresOn} onChange={(e) => setExpiresOn(e.target.value)} /></Field>
          <Field label={tx('Notes')} className="sm:col-span-2"><Textarea rows={2} value={notes} onChange={(e) => setNotes(e.target.value)} /></Field>
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={onClose}>{tx('Cancel')}</Button>
          <Button onClick={submit} disabled={busy || (!card && number.replace(/[\s-]/g, '').length < 4)}>{tx('Save')}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

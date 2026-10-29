import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Banknote, Eye, Plus } from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Textarea } from '@/components/ui/textarea';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs';
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { SearchableSelect } from '@/components/ui/searchable-select';
import { DataTable, type Column } from '@/components/data-table';
import {
  DataTablePagination, FilterChips, FilterSelect, ListCard, ListPageHeader, ListToolbar, SearchInput, StatusPill,
  useListState, type ActiveChip, type Tone,
} from '@/components/list';
import { useAuthStore } from '@/stores/auth.store';
import { notify, apiMessage } from '@/lib/notify';
import { date, dateTime, money } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';
import { useVehicles } from '@/features/fleet/api';
import {
  DEDUCTION_STATUS_LABEL, FINE_STATUS_LABEL, useCreateFine, useDeductions, useDeductionStatus, useEmployeePicker,
  useFines, useUpdateFine, type DeductionStatus, type Fine, type FineDeduction, type FineStatus,
} from '@/features/fleet/tyres-api';
import { Field, OptionalSelect, RemotePicker } from './fleet-ui';
import { fromLocalInput, toLocalInput, toOptions } from './fleet-format';

const FILTERS = { status: '', chargedTo: '', overdue: '' };

const FINE_TONE: Record<FineStatus, Tone> = { open: 'warning', disputed: 'accent', charged: 'info', settled: 'success', cancelled: 'neutral' };
const DEDUCTION_TONE: Record<DeductionStatus, Tone> = { pending: 'warning', exported: 'info', settled: 'success', cancelled: 'neutral' };

export function FineStatusPill({ status }: { status: FineStatus }) {
  return <StatusPill tone={FINE_TONE[status] ?? 'neutral'}>{FINE_STATUS_LABEL[status] ?? status}</StatusPill>;
}

export function DeductionStatusPill({ status }: { status: DeductionStatus }) {
  return <StatusPill tone={DEDUCTION_TONE[status] ?? 'neutral'}>{DEDUCTION_STATUS_LABEL[status] ?? status}</StatusPill>;
}

const CHARGED_TO: Record<string, string> = { company: tx('Company'), driver: tx('Driver') };

/** Traffic fines (fleet 1.7): list with status / charged-to / overdue filters, and the payroll deduction requests. */
export function FinesPage() {
  const navigate = useNavigate();
  const has = useAuthStore((s) => s.hasPermission);
  const list = useListState(FILTERS);
  const { status, chargedTo, overdue } = list.filters;
  const [creating, setCreating] = useState(false);
  const [tab, setTab] = useState('fines');
  const { data, isLoading, isFetching, isError } = useFines({
    page: list.page, pageSize: list.pageSize, search: list.search || undefined, status: status || undefined,
    chargedTo: chargedTo || undefined, overdue: overdue === 'true' ? true : undefined,
  });
  const chips: ActiveChip[] = [
    ...(list.search ? [{ key: 'q', label: `“${list.search}”`, onRemove: () => list.setSearchInput('') }] : []),
    ...(status ? [{ key: 'status', label: FINE_STATUS_LABEL[status as FineStatus] ?? status, onRemove: () => list.setFilter('status', '') }] : []),
    ...(chargedTo ? [{ key: 'to', label: CHARGED_TO[chargedTo] ?? chargedTo, onRemove: () => list.setFilter('chargedTo', '') }] : []),
    ...(overdue ? [{ key: 'overdue', label: tx('Overdue'), onRemove: () => list.setFilter('overdue', '') }] : []),
  ];
  const columns: Column<Fine>[] = [
    { key: 'number', header: tx('Number'), render: (f) => <span className="font-medium text-primary">{f.fineNumber}</span> },
    { key: 'offenceAt', header: tx('When'), render: (f) => <span className="whitespace-nowrap">{dateTime(f.offenceAt)}</span> },
    { key: 'vehicle', header: tx('Vehicle'), render: (f) => <div><div>{f.vehicle.code}</div><div className="text-xs text-muted-foreground">{f.vehicle.registrationPlate ?? ''}</div></div> },
    { key: 'driver', header: tx('Driver'), render: (f) => f.driver?.name ?? '—' },
    { key: 'offence', header: tx('Offence'), render: (f) => <span className="line-clamp-1 max-w-[16rem]">{f.offence}</span> },
    { key: 'amount', header: tx('Amount'), className: 'text-right', render: (f) => <span className="tabular-nums">{money(f.amount)}</span> },
    {
      key: 'due', header: tx('Due'),
      render: (f) => (f.dueDate ? <span className={f.overdue ? 'font-medium text-rose-600' : ''}>{date(f.dueDate)}</span> : '—'),
    },
    { key: 'chargedTo', header: tx('Charged to'), render: (f) => (f.chargedTo ? CHARGED_TO[f.chargedTo] : '—') },
    { key: 'status', header: tx('Status'), render: (f) => <FineStatusPill status={f.status} /> },
    {
      key: 'actions', header: '', className: 'w-12 text-right',
      render: (f) => (
        <Button variant="ghost" size="icon" className="h-8 w-8" aria-label={tx('View fine')} onClick={() => navigate(`/fleet/fines/${f.id}`)}>
          <Eye className="h-4 w-4" />
        </Button>
      ),
    },
  ];

  return (
    <div className="space-y-4">
      <ListPageHeader icon={Banknote} title={tx('Traffic Fines')}
        description={tx('Fines of vehicles and drivers, charged to the company (expense on the vehicle cost centre) or the driver (salary deduction).')}
        actions={has(PERMISSIONS.fleetIncident.create) && <Button onClick={() => setCreating(true)}><Plus className="mr-1.5 h-4 w-4" /> {tx('Record fine')}</Button>} />
      <Tabs value={tab} onValueChange={setTab}>
        <TabsList>
          <TabsTrigger value="fines">{tx('Fines')}</TabsTrigger>
          <TabsTrigger value="deductions">{tx('Salary deductions')}</TabsTrigger>
        </TabsList>
        <TabsContent value="fines" className="space-y-4">
          <ListToolbar chips={<FilterChips chips={chips} onClearAll={list.clearFilters} />}>
            <SearchInput value={list.searchInput} onChange={list.setSearchInput} placeholder={tx('Search number, notice, vehicle, offence…')} />
            <FilterSelect value={status} onChange={(v) => list.setFilter('status', v)} options={toOptions(FINE_STATUS_LABEL)} allLabel={tx('All statuses')} />
            <FilterSelect value={chargedTo} onChange={(v) => list.setFilter('chargedTo', v)} options={toOptions(CHARGED_TO)} allLabel={tx('Charged to anyone')} />
            <FilterSelect value={overdue} onChange={(v) => list.setFilter('overdue', v)} options={[{ value: 'true', label: tx('Overdue') }]} allLabel={tx('Any due date')} />
          </ListToolbar>
          <ListCard>
            <div className={isFetching && !isLoading ? 'opacity-60 transition-opacity' : 'transition-opacity'}>
              <DataTable columns={columns} data={data?.data ?? []} loading={isLoading} loadingRows={8} getRowId={(f) => f.id}
                onRowClick={(f) => navigate(`/fleet/fines/${f.id}`)} cellClassName="py-2.5 px-4" headerRowClassName="h-10"
                emptyMessage={isError ? tx('Could not load the fines. Try again.') : chips.length ? tx('No fines match these filters.') : tx('No fines recorded.')} />
            </div>
            {data?.meta && (
              <DataTablePagination page={data.meta.page} pageSize={list.pageSize} total={data.meta.total} totalPages={data.meta.totalPages}
                onPageChange={list.setPage} onPageSizeChange={list.setPageSize} noun="fine" />
            )}
          </ListCard>
        </TabsContent>
        <TabsContent value="deductions"><DeductionsTab active={tab === 'deductions'} /></TabsContent>
      </Tabs>
      {creating && <FineFormDialog onClose={() => setCreating(false)} onSaved={(f) => navigate(`/fleet/fines/${f.id}`)} />}
    </div>
  );
}

function DeductionsTab({ active }: { active: boolean }) {
  const [status, setStatus] = useState('pending');
  const [page, setPage] = useState(1);
  const canCharge = useAuthStore((s) => s.hasPermission(PERMISSIONS.fleetIncident.charge));
  const { data, isLoading, isError } = useDeductions({ page, pageSize: 20, status: status || undefined });
  const change = useDeductionStatus();
  if (!active) return null;
  const move = async (d: FineDeduction, to: 'exported' | 'settled') => {
    try {
      await change.mutateAsync({ id: d.id, status: to });
      notify.success(DEDUCTION_STATUS_LABEL[to]);
    } catch (err) {
      notify.error(apiMessage(err, tx('Could not update the deduction')));
    }
  };
  const columns: Column<FineDeduction>[] = [
    { key: 'employee', header: tx('Employee'), render: (d) => <div><div>{d.employee?.name}</div><div className="text-xs text-muted-foreground">{d.employee?.employeeCode}</div></div> },
    { key: 'fine', header: tx('Fine'), render: (d) => <div><div>{d.fine?.fineNumber}</div><div className="text-xs text-muted-foreground">{d.fine?.offence}</div></div> },
    { key: 'vehicle', header: tx('Vehicle'), render: (d) => d.vehicle?.code ?? '—' },
    { key: 'amount', header: tx('Amount'), className: 'text-right', render: (d) => <span className="tabular-nums">{money(d.amount)}</span> },
    { key: 'requestedAt', header: tx('Requested'), render: (d) => date(d.requestedAt) },
    { key: 'status', header: tx('Status'), render: (d) => <DeductionStatusPill status={d.status} /> },
    {
      key: 'actions', header: '', className: 'text-right',
      render: (d) => canCharge && (
        <div className="flex justify-end gap-1">
          {d.status === 'pending' && <Button size="sm" variant="outline" onClick={() => move(d, 'exported')}>{tx('Mark exported')}</Button>}
          {(d.status === 'pending' || d.status === 'exported') && <Button size="sm" onClick={() => move(d, 'settled')}>{tx('Mark settled')}</Button>}
        </div>
      ),
    },
  ];
  return (
    <div className="space-y-3">
      <p className="text-sm text-muted-foreground">{tx('Fines charged to drivers wait here until payroll deducts them. Payroll picks up new requests automatically once the hr integration is enabled.')}</p>
      <div className="w-56">
        <OptionalSelect value={status} onChange={(v) => { setStatus(v); setPage(1); }} placeholder={tx('All statuses')} noneLabel={tx('All statuses')} options={toOptions(DEDUCTION_STATUS_LABEL)} />
      </div>
      <ListCard>
        <DataTable columns={columns} data={data?.data ?? []} loading={isLoading} getRowId={(d) => d.id}
          emptyMessage={isError ? tx('Could not load the deductions.') : tx('No deduction requests.')} />
        {data?.meta && data.meta.total > 0 && (
          <DataTablePagination page={data.meta.page} pageSize={20} total={data.meta.total} totalPages={data.meta.totalPages} onPageChange={setPage} noun="deduction" />
        )}
      </ListCard>
    </div>
  );
}

export function FineFormDialog({ fine, onClose, onSaved, vehicleId: fixedVehicle }: {
  fine?: Fine; onClose: () => void; onSaved?: (f: Fine) => void; vehicleId?: string;
}) {
  const [vehicleId, setVehicleId] = useState(fine?.vehicleId ?? fixedVehicle ?? '');
  const [driver, setDriver] = useState({ id: fine?.driverEmployeeId ?? '', label: fine?.driver?.name ?? '' });
  const [driverSearch, setDriverSearch] = useState('');
  const [offenceAt, setOffenceAt] = useState(toLocalInput(fine ? new Date(fine.offenceAt) : new Date()));
  const [offence, setOffence] = useState(fine?.offence ?? '');
  const [location, setLocation] = useState(fine?.location ?? '');
  const [authority, setAuthority] = useState(fine?.authority ?? '');
  const [notice, setNotice] = useState(fine?.noticeNumber ?? '');
  const [amount, setAmount] = useState(fine?.amount ?? '');
  const [dueDate, setDueDate] = useState(fine?.dueDate ?? '');
  const [notes, setNotes] = useState(fine?.notes ?? '');
  const vehicles = useVehicles({ page: 1, pageSize: 200, sortBy: 'code' });
  const employees = useEmployeePicker(driverSearch, driverSearch.trim().length > 0);
  const create = useCreateFine();
  const update = useUpdateFine();
  const valid = !!vehicleId && offence.trim().length > 0 && amount !== '' && Number(amount) > 0;
  const submit = async () => {
    const body: Record<string, unknown> = {
      driverEmployeeId: driver.id || undefined, offenceAt: fromLocalInput(offenceAt), offence: offence.trim(),
      location: location.trim() || undefined, authority: authority.trim() || undefined, noticeNumber: notice.trim() || undefined,
      amount: Number(amount), dueDate: dueDate || undefined, notes: notes.trim() || undefined,
    };
    try {
      const clear = fine ? ['driverEmployeeId', 'location', 'authority', 'noticeNumber', 'dueDate', 'notes']
        .filter((k) => body[k] === undefined && (fine as unknown as Record<string, unknown>)[k] != null) : [];
      const out = fine ? await update.mutateAsync({ id: fine.id, body: { ...body, clear } })
        : await create.mutateAsync({ ...body, vehicleId });
      notify.success(fine ? tx('Fine saved') : tx('Fine recorded'));
      onSaved?.(out);
      onClose();
    } catch (err) {
      notify.error(apiMessage(err, tx('Could not save the fine')));
    }
  };
  return (
    <Dialog open onOpenChange={(o) => !o && onClose()}>
      <DialogContent className="max-h-[90vh] overflow-y-auto sm:max-w-2xl">
        <DialogHeader><DialogTitle>{fine ? tx('Edit fine {{number}}', { number: fine.fineNumber }) : tx('Record fine')}</DialogTitle><DialogDescription>{tx('Charge it to the company or the driver once recorded.')}</DialogDescription></DialogHeader>
        <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
          <Field label={tx('Vehicle *')}>
            {fine ? <p className="text-sm font-medium">{fine.vehicle.code}</p> : <SearchableSelect value={vehicleId} onValueChange={setVehicleId} placeholder={tx('Choose a vehicle')} searchPlaceholder={tx('Search code or plate…')}
              options={(vehicles.data?.data ?? []).map((v) => ({ value: v.id, label: v.code, hint: v.registrationPlate ?? undefined }))} />}
          </Field>
          <Field label={tx('Driver')}>
            <RemotePicker value={driver.id} selectedLabel={driver.label} search={driverSearch} onSearch={setDriverSearch}
              results={(employees.data ?? []).map((e) => ({ value: e.id, label: e.name, hint: e.employeeCode }))}
              loading={employees.isFetching} onPick={(v, label) => setDriver({ id: v, label })} placeholder={tx('Search employees…')} clearLabel={tx('Clear')} />
          </Field>
          <Field label={tx('When *')}><Input type="datetime-local" value={offenceAt} max={toLocalInput(new Date())} onChange={(e) => setOffenceAt(e.target.value)} /></Field>
          <Field label={tx('Place')}><Input value={location} onChange={(e) => setLocation(e.target.value)} /></Field>
          <Field label={tx('Offence *')} className="sm:col-span-2"><Input value={offence} onChange={(e) => setOffence(e.target.value)} /></Field>
          <Field label={tx('Authority')}><Input value={authority} onChange={(e) => setAuthority(e.target.value)} /></Field>
          <Field label={tx('Notice number')}><Input value={notice} onChange={(e) => setNotice(e.target.value)} /></Field>
          <Field label={tx('Amount *')}><Input inputMode="decimal" value={amount} onChange={(e) => setAmount(e.target.value)} /></Field>
          <Field label={tx('Due date')}><Input type="date" value={dueDate} onChange={(e) => setDueDate(e.target.value)} /></Field>
          <Field label={tx('Notes')} className="sm:col-span-2"><Textarea rows={2} value={notes} onChange={(e) => setNotes(e.target.value)} /></Field>
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={onClose}>{tx('Cancel')}</Button>
          <Button onClick={submit} disabled={!valid || create.isPending || update.isPending}>{tx('Save')}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

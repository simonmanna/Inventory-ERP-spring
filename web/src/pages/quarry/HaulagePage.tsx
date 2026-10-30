import { useState } from 'react';
import { Pencil, Plus, Trash2, Truck } from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { Button } from '@/components/ui/button';
import { DataTable, type Column } from '@/components/data-table';
import { DataTablePagination, DateRangeFilter, FilterSelect, ListCard, ListPageHeader, ListToolbar, SearchInput, StatusPill, useListState } from '@/components/list';
import { useAuthStore } from '@/stores/auth.store';
import { notify, apiMessage } from '@/lib/notify';
import { date } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';
import { DESTINATION_LABEL, SHIFT_LABEL, useDeleteRow, useHaulage, useQuarryLookups, useSaveRow, useTicketSearch, type Haulage } from '@/features/quarry/api';
import { FormDialog, toOptions, tonnes, type FieldSpec } from './quarry-ui';

/** Haulage face → ROM stockpile / crusher: loads × truck capacity or a weighbridge ticket; pulled into extraction logs. */
export function HaulagePage() {
  const has = useAuthStore((s) => s.hasPermission);
  const list = useListState({ siteId: '', destination: '', from: '', to: '' });
  const f = list.filters;
  const lookups = useQuarryLookups();
  const { data, isLoading, isError } = useHaulage({ page: list.page, pageSize: list.pageSize, search: list.search, siteId: f.siteId,
    destination: f.destination, dateFrom: f.from, dateTo: f.to });
  const save = useSaveRow('/quarry/haulage-trips');
  const remove = useDeleteRow('/quarry/haulage-trips');
  const [editing, setEditing] = useState<Haulage | 'new' | null>(null);
  const tickets = useTicketSearch('', editing !== null);
  const L = lookups.data;
  const ticketOptions = [
    ...(editing && editing !== 'new' && editing.weighbridgeTicketId ? [{ value: editing.weighbridgeTicketId, label: editing.ticketNumber ?? editing.weighbridgeTicketId }] : []),
    ...(tickets.data ?? []).map((t) => ({ value: t.id, label: `${t.ticketNumber} · ${t.registrationPlate} · ${Number(t.netKg) / 1000} t` })),
  ];
  const today = new Date().toISOString().slice(0, 10);
  const fields: FieldSpec[] = [
    { name: 'siteId', label: tx('Site'), type: 'select', required: true, options: (L?.sites ?? []).map((s) => ({ value: s.id, label: `${s.code} · ${s.name}` })) },
    { name: 'fromZoneId', label: tx('From face / bench'), type: 'select', options: (L?.zones ?? []).map((z) => ({ value: z.id, label: `${z.code} · ${z.name}` })) },
    { name: 'destination', label: tx('Destination'), type: 'select', required: true, options: toOptions(DESTINATION_LABEL) },
    { name: 'stockpileId', label: tx('Stockpile'), type: 'select', options: (L?.stockpiles ?? []).map((k) => ({ value: k.id, label: `${k.code} · ${k.name}` })) },
    { name: 'hauledOn', label: tx('Date'), type: 'date', required: true },
    { name: 'shift', label: tx('Shift'), type: 'select', required: true, options: toOptions(SHIFT_LABEL) },
    { name: 'method', label: tx('Measured by'), type: 'select', required: true, options: [{ value: 'loads', label: tx('Loads × truck capacity') }, { value: 'weighbridge', label: tx('Weighbridge ticket') }] },
    { name: 'truckVehicleId', label: tx('Truck'), type: 'select', options: (L?.trucks ?? []).map((t) => ({ value: t.id, label: `${t.code} · ${t.payloadCapacityT} t` })) },
    { name: 'loadCount', label: tx('Loads'), type: 'number', min: 1 },
    { name: 'capacityT', label: tx('t / load'), type: 'number', min: 0, hint: tx('Blank = truck payload') },
    { name: 'weighbridgeTicketId', label: tx('Weighbridge ticket'), type: 'select', options: ticketOptions, hint: tx('Completed internal-haulage ticket') },
    { name: 'driverEmployeeId', label: tx('Driver'), type: 'employee' },
    { name: 'materialId', label: tx('Material'), type: 'select', options: (L?.materials ?? []).map((m) => ({ value: m.id, label: m.name })) },
    { name: 'destinationNote', label: tx('Destination note') },
    { name: 'notes', label: tx('Notes'), type: 'textarea' },
  ];
  const columns: Column<Haulage>[] = [
    { key: 'date', header: tx('Date'), render: (h) => `${date(h.hauledOn)} · ${SHIFT_LABEL[h.shift]}` },
    { key: 'code', header: tx('Haul'), render: (h) => <span className="font-medium">{h.code}</span> },
    { key: 'route', header: tx('Route'), render: (h) => `${h.zoneCode ?? h.siteCode} → ${h.stockpileCode ?? DESTINATION_LABEL[h.destination]}` },
    { key: 'truck', header: tx('Truck'), render: (h) => h.truckCode ?? '—' },
    { key: 'qty', header: tx('Measure'), render: (h) => (h.method === 'weighbridge' ? h.ticketNumber ?? '—' : `${h.loadCount} × ${h.capacityT} t`) },
    { key: 'tonnes', header: tx('Tonnes'), className: 'text-right', render: (h) => tonnes(h.tonnes) },
    { key: 'log', header: tx('Extraction log'), render: (h) => (h.extractionLogCode ? <StatusPill tone="success">{h.extractionLogCode}</StatusPill> : <StatusPill tone="warning">{tx('Not yet logged')}</StatusPill>) },
    { key: 'actions', header: '', className: 'w-20 text-right', render: (h) => has(PERMISSIONS.quarryExtraction.haul) && !h.extractionLogId && (
      <span className="flex justify-end gap-1">
        <Button variant="ghost" size="icon" className="h-8 w-8" aria-label={tx('Edit')} onClick={() => setEditing(h)}><Pencil className="h-4 w-4" /></Button>
        <Button variant="ghost" size="icon" className="h-8 w-8" aria-label={tx('Delete')}
          onClick={async () => { try { await remove.mutateAsync(h.id); notify.success(tx('Deleted')); } catch (e) { notify.error(apiMessage(e, tx('Could not delete'))); } }}>
          <Trash2 className="h-4 w-4" /></Button>
      </span>
    ) },
  ];
  return (
    <div className="space-y-4">
      <ListPageHeader icon={Truck} title={tx('Haulage')} description={tx('Internal trips from the face to the ROM stockpile or crusher.')}
        actions={has(PERMISSIONS.quarryExtraction.haul) && <Button onClick={() => setEditing('new')}><Plus className="mr-1.5 h-4 w-4" /> {tx('Log haulage')}</Button>} />
      <ListToolbar>
        <SearchInput value={list.searchInput} onChange={list.setSearchInput} placeholder={tx('Search haul, truck…')} />
        <FilterSelect value={f.siteId} onChange={(v) => list.setFilter('siteId', v)} options={(L?.sites ?? []).map((s) => ({ value: s.id, label: s.code }))} allLabel={tx('All sites')} />
        <FilterSelect value={f.destination} onChange={(v) => list.setFilter('destination', v)} options={toOptions(DESTINATION_LABEL)} allLabel={tx('All destinations')} />
        <DateRangeFilter from={f.from} to={f.to} onChange={(a, b) => list.setFilters({ from: a, to: b })} />
      </ListToolbar>
      {data?.summary && <p className="text-sm text-muted-foreground">{tx('In this view: {{tonnes}} in {{loads}} loads', { tonnes: tonnes(data.summary.tonnes), loads: data.summary.loads })}</p>}
      <ListCard>
        <DataTable columns={columns} data={data?.data ?? []} loading={isLoading} getRowId={(h) => h.id} cellClassName="py-2.5 px-4"
          emptyMessage={isError ? tx('Could not load the haulage records. Try again.') : tx('No haulage logged yet.')} />
      </ListCard>
      {data?.meta && <DataTablePagination page={data.meta.page} pageSize={list.pageSize} total={data.meta.total} totalPages={data.meta.totalPages}
        onPageChange={list.setPage} onPageSizeChange={list.setPageSize} noun="haul" />}
      {editing && (
        <FormDialog title={editing === 'new' ? tx('Log haulage') : tx('Edit {{code}}', { code: editing.code })} fields={fields}
          row={editing === 'new' ? null : editing} defaults={{ destination: 'rom_stockpile', hauledOn: today, shift: 'day', method: 'loads', siteId: f.siteId }}
          saving={save.isPending} onSave={(body) => save.mutateAsync({ id: editing === 'new' ? undefined : editing.id, body })} onClose={() => setEditing(null)} />
      )}
    </div>
  );
}

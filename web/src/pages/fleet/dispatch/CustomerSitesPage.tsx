import { useEffect, useState } from 'react';
import { Edit, MapPin, Plus, Trash2 } from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Textarea } from '@/components/ui/textarea';
import { Dialog, DialogContent, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { DataTable, type Column } from '@/components/data-table';
import {
  DataTablePagination, ListCard, ListPageHeader, ListToolbar, SearchInput, StatusPill, useListState,
} from '@/components/list';
import { useAuthStore } from '@/stores/auth.store';
import { notify, apiMessage } from '@/lib/notify';
import { tx } from '@/lib/i18n/tx';
import { toNum, useCreateSite, useCustomerSites, useDeleteSite, useUpdateSite, type CustomerSite } from '@/features/fleet/dispatch-api';
import { Field } from '../fleet-ui';
import { num } from '../fleet-format';
import { CustomerPicker } from './dispatch-ui';

const FILTERS = { inactive: '' };

/** Customer construction sites / delivery addresses (fleet 1.3): GPS, contact, access notes, charge zone. */
export function CustomerSitesPage() {
  const has = useAuthStore((s) => s.hasPermission);
  const list = useListState(FILTERS);
  const { data, isLoading, isError } = useCustomerSites({
    page: list.page, pageSize: list.pageSize, search: list.search || undefined, includeInactive: list.filters.inactive === '1',
  });
  const remove = useDeleteSite();
  const [editing, setEditing] = useState<CustomerSite | 'new' | null>(null);
  const rows = data?.data ?? [];
  const meta = data?.meta;

  const doDelete = async (s: CustomerSite) => {
    if (!window.confirm(tx('Delete site {{code}}?', { code: s.code }))) return;
    try {
      const out = await remove.mutateAsync(s.id);
      notify.success(out.deactivated ? tx('Site is used by delivery orders: deactivated') : tx('Site deleted'));
    } catch (err) {
      notify.error(apiMessage(err, tx('Could not delete the site')));
    }
  };

  const columns: Column<CustomerSite>[] = [
    { key: 'code', header: tx('Code'), render: (s) => <span className="font-medium">{s.code}</span> },
    { key: 'name', header: tx('Site'), render: (s) => s.name },
    { key: 'customer', header: tx('Customer'), render: (s) => s.partnerName },
    { key: 'address', header: tx('Address'), render: (s) => [s.address, s.city].filter(Boolean).join(', ') || '—' },
    { key: 'zone', header: tx('Zone'), render: (s) => s.zone ?? '—' },
    { key: 'km', header: tx('Distance'), className: 'text-right', render: (s) => (s.distanceKm ? `${num(s.distanceKm, 1)} km` : '—') },
    { key: 'gps', header: 'GPS', render: (s) => (s.latitude ? <MapPin className="h-4 w-4 text-emerald-600" /> : '—') },
    { key: 'status', header: tx('Status'), render: (s) => <StatusPill tone={s.isActive ? 'success' : 'neutral'}>{s.isActive ? tx('Active') : tx('Inactive')}</StatusPill> },
    {
      key: 'actions', header: '', className: 'text-right',
      render: (s) => (
        <div className="flex justify-end gap-1">
          {has(PERMISSIONS.fleetDispatch.update) && (
            <>
              <Button variant="ghost" size="icon" className="h-8 w-8" aria-label={tx('Edit')} onClick={() => setEditing(s)}><Edit className="h-4 w-4" /></Button>
              <Button variant="ghost" size="icon" className="h-8 w-8" aria-label={tx('Delete')} onClick={() => doDelete(s)}><Trash2 className="h-4 w-4" /></Button>
            </>
          )}
        </div>
      ),
    },
  ];

  return (
    <div className="space-y-4">
      <ListPageHeader icon={MapPin} title={tx('Customer Sites')} description={tx('Construction sites and delivery addresses with GPS, contact and access notes.')}
        actions={has(PERMISSIONS.fleetDispatch.create) && <Button onClick={() => setEditing('new')}><Plus className="mr-1.5 h-4 w-4" /> {tx('New site')}</Button>} />
      <ListToolbar>
        <SearchInput value={list.searchInput} onChange={list.setSearchInput} placeholder={tx('Search site, customer, address…')} />
        <label className="flex items-center gap-2 text-sm">
          <input type="checkbox" checked={list.filters.inactive === '1'} onChange={(e) => list.setFilter('inactive', e.target.checked ? '1' : '')} />
          {tx('Show inactive')}
        </label>
      </ListToolbar>
      <ListCard>
        <div className="overflow-x-auto">
          <DataTable columns={columns} data={rows} loading={isLoading} loadingRows={6} getRowId={(s) => s.id} cellClassName="py-2.5 px-4" headerRowClassName="h-10"
            emptyMessage={isError ? tx('Could not load the sites. Try again.') : tx('No customer sites yet.')} />
        </div>
      </ListCard>
      {meta && (
        <DataTablePagination page={meta.page} pageSize={list.pageSize} total={meta.total} totalPages={meta.totalPages}
          onPageChange={list.setPage} onPageSizeChange={list.setPageSize} noun="site" />
      )}
      {editing && <SiteDialog site={editing === 'new' ? null : editing} onClose={() => setEditing(null)} />}
    </div>
  );
}

function SiteDialog({ site, onClose }: { site: CustomerSite | null; onClose: () => void }) {
  const create = useCreateSite();
  const update = useUpdateSite();
  const [partner, setPartner] = useState({ id: site?.partnerId ?? '', name: site?.partnerName ?? '' });
  const [f, setF] = useState({
    name: site?.name ?? '', address: site?.address ?? '', city: site?.city ?? '', latitude: site?.latitude ?? '',
    longitude: site?.longitude ?? '', contactName: site?.contactName ?? '', contactPhone: site?.contactPhone ?? '',
    accessNotes: site?.accessNotes ?? '', zone: site?.zone ?? '', distanceKm: site?.distanceKm ?? '', isActive: site?.isActive ?? true,
  });
  const [error, setError] = useState('');
  useEffect(() => setError(''), [f, partner]);
  const set = (k: keyof typeof f, v: string | boolean) => setF({ ...f, [k]: v });

  const useMyLocation = () => {
    navigator.geolocation?.getCurrentPosition((p) => setF((x) => ({ ...x, latitude: p.coords.latitude.toFixed(6), longitude: p.coords.longitude.toFixed(6) })));
  };

  const save = async () => {
    if (!site && !partner.id) { setError(tx('Choose the customer')); return; }
    if (!f.name.trim()) { setError(tx('Enter the site name')); return; }
    if ((f.latitude === '') !== (f.longitude === '')) { setError(tx('Give both latitude and longitude, or neither')); return; }
    const text = (v: string) => (v.trim() ? v.trim() : undefined);
    try {
      if (site) {
        const clear = (['address', 'city', 'latitude', 'longitude', 'contactName', 'contactPhone', 'accessNotes', 'zone', 'distanceKm'] as const)
          .filter((k) => site[k] && !String(f[k]).trim());
        await update.mutateAsync({ id: site.id, body: {
          name: f.name.trim(), address: text(f.address), city: text(f.city), latitude: toNum(f.latitude), longitude: toNum(f.longitude),
          contactName: text(f.contactName), contactPhone: text(f.contactPhone), accessNotes: text(f.accessNotes), zone: text(f.zone),
          distanceKm: toNum(f.distanceKm), isActive: f.isActive, clear: clear.length ? clear : undefined,
        } });
      } else {
        await create.mutateAsync({
          partnerId: partner.id, name: f.name.trim(), address: text(f.address), city: text(f.city), latitude: toNum(f.latitude),
          longitude: toNum(f.longitude), contactName: text(f.contactName), contactPhone: text(f.contactPhone),
          accessNotes: text(f.accessNotes), zone: text(f.zone), distanceKm: toNum(f.distanceKm),
        });
      }
      notify.success(tx('Site saved'));
      onClose();
    } catch (err) {
      notify.error(apiMessage(err, tx('Could not save the site')));
    }
  };

  return (
    <Dialog open onOpenChange={(o) => { if (!o) onClose(); }}>
      <DialogContent className="max-w-lg">
        <DialogHeader><DialogTitle>{site ? tx('Edit site {{code}}', { code: site.code }) : tx('New site')}</DialogTitle></DialogHeader>
        <div className="grid max-h-[70vh] grid-cols-2 gap-3 overflow-y-auto">
          {!site && (
            <Field label={tx('Customer')} className="col-span-2">
              <CustomerPicker value={partner.id} label={partner.name} onPick={(id, name) => setPartner({ id, name })} />
            </Field>
          )}
          <Field label={tx('Site name')} className="col-span-2"><Input value={f.name} onChange={(e) => set('name', e.target.value)} /></Field>
          <Field label={tx('Address')} className="col-span-2"><Input value={f.address} onChange={(e) => set('address', e.target.value)} /></Field>
          <Field label={tx('City')}><Input value={f.city} onChange={(e) => set('city', e.target.value)} /></Field>
          <Field label={tx('Zone')}><Input value={f.zone} onChange={(e) => set('zone', e.target.value)} /></Field>
          <Field label={tx('Latitude')}><Input type="number" step="0.000001" value={f.latitude} onChange={(e) => set('latitude', e.target.value)} /></Field>
          <Field label={tx('Longitude')}><Input type="number" step="0.000001" value={f.longitude} onChange={(e) => set('longitude', e.target.value)} /></Field>
          <div className="col-span-2"><Button type="button" variant="outline" size="sm" onClick={useMyLocation}><MapPin className="mr-1 h-4 w-4" /> {tx('Use my location')}</Button></div>
          <Field label={tx('Distance from the yard (km)')}><Input type="number" min={0} step="0.1" value={f.distanceKm} onChange={(e) => set('distanceKm', e.target.value)} /></Field>
          <Field label={tx('Contact name')}><Input value={f.contactName} onChange={(e) => set('contactName', e.target.value)} /></Field>
          <Field label={tx('Contact phone')}><Input value={f.contactPhone} onChange={(e) => set('contactPhone', e.target.value)} /></Field>
          <Field label={tx('Access notes')} className="col-span-2"><Textarea rows={2} value={f.accessNotes} onChange={(e) => set('accessNotes', e.target.value)} /></Field>
          {site && (
            <label className="col-span-2 flex items-center gap-2 text-sm">
              <input type="checkbox" checked={f.isActive} onChange={(e) => set('isActive', e.target.checked)} /> {tx('Active')}
            </label>
          )}
          {error && <p className="col-span-2 text-sm text-destructive">{error}</p>}
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={onClose}>{tx('Cancel')}</Button>
          <Button disabled={create.isPending || update.isPending} onClick={save}>{tx('Save')}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

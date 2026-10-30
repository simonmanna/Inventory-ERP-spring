import { useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { ArrowLeft, MapPinned, Pencil, Plus, Trash2 } from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { Button } from '@/components/ui/button';
import { Skeleton } from '@/components/ui/skeleton';
import { ListPageHeader, StatusPill } from '@/components/list';
import { useAuthStore } from '@/stores/auth.store';
import { notify, apiMessage } from '@/lib/notify';
import { date, money } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';
import {
  SITE_STATUS_LABEL, ZONE_KIND_LABEL, useDeleteRow, useQuarryLookups, useSaveRow, useSite, type Stockpile, type Zone,
} from '@/features/quarry/api';
import { EmptyState, FormDialog, KeyValues, Section, toOptions, tonnes, type FieldSpec } from './quarry-ui';
import { siteFields } from './SitesPage';

/** A site: licence and details, the zone → bench → face tree, ROM stockpiles, magazines and open plans. */
export function SiteDetailPage() {
  const { id = '' } = useParams();
  const has = useAuthStore((s) => s.hasPermission);
  const { data: s, isLoading, isError } = useSite(id);
  const lookups = useQuarryLookups();
  const saveSite = useSaveRow('/quarry/sites');
  const saveZone = useSaveRow('/quarry/zones');
  const deleteZone = useDeleteRow('/quarry/zones');
  const saveStockpile = useSaveRow('/quarry/stockpiles');
  const [editSite, setEditSite] = useState(false);
  const [zone, setZone] = useState<Zone | 'new' | null>(null);
  const [stockpile, setStockpile] = useState<Stockpile | 'new' | null>(null);

  if (isLoading) return <Skeleton className="h-64" />;
  if (isError || !s) return <EmptyState>{tx('Could not load the site.')}</EmptyState>;

  const zoneOptions = s.zones.filter((z) => z.kind !== 'face' && (zone === 'new' || zone?.id !== z.id))
    .map((z) => ({ value: z.id, label: `${ZONE_KIND_LABEL[z.kind]} ${z.code} · ${z.name}` }));
  const zoneFields: FieldSpec[] = [
    { name: 'siteId', label: tx('Site'), hidden: true },
    { name: 'kind', label: tx('Level'), type: 'select', required: true, options: toOptions(ZONE_KIND_LABEL) },
    { name: 'parentId', label: tx('Inside (zone / bench)'), type: 'select', options: zoneOptions, hint: tx('Benches sit in a zone, faces on a bench or zone') },
    { name: 'code', label: tx('Code'), hint: tx('Automatic when blank') },
    { name: 'name', label: tx('Name'), required: true },
    { name: 'materialId', label: tx('Material (geology)'), type: 'select', options: (lookups.data?.materials ?? []).map((m) => ({ value: m.id, label: `${m.code} · ${m.name}` })) },
    { name: 'status', label: tx('Status'), type: 'select', required: true, options: [{ value: 'active', label: tx('Active') }, { value: 'inactive', label: tx('Inactive') }, { value: 'exhausted', label: tx('Exhausted') }] },
    { name: 'elevationM', label: tx('Elevation (m)'), type: 'number' },
    { name: 'benchHeightM', label: tx('Bench height (m)'), type: 'number', min: 0 },
    { name: 'geology', label: tx('Geology notes'), wide: true },
    { name: 'notes', label: tx('Notes'), type: 'textarea' },
  ];
  const stockpileFields: FieldSpec[] = [
    { name: 'siteId', label: tx('Site'), hidden: true },
    { name: 'code', label: tx('Code'), hint: tx('Automatic when blank') },
    { name: 'name', label: tx('Name'), required: true },
    { name: 'kind', label: tx('Kind'), type: 'select', required: true, options: [{ value: 'rom', label: tx('ROM (run-of-mine)') }, { value: 'product', label: tx('Product stockpile') }] },
    { name: 'locationId', label: tx('Inventory location'), type: 'select', disabled: stockpile !== 'new',
      options: (lookups.data?.locations ?? []).map((l) => ({ value: l.id, label: `${l.code} · ${l.name}` })), hint: tx('Leave blank to create a new location') },
    { name: 'capacityT', label: tx('Capacity (t)'), type: 'number', min: 0 },
    { name: 'isDefault', label: tx('Default'), type: 'checkbox', hint: tx('Default ROM stockpile of the site') },
    { name: 'isActive', label: tx('Active'), type: 'checkbox', hint: tx('Active') },
    { name: 'notes', label: tx('Notes'), type: 'textarea' },
  ];

  return (
    <div className="space-y-4">
      <Button variant="ghost" size="sm" asChild><Link to="/quarry/sites"><ArrowLeft className="mr-1 h-4 w-4" /> {tx('Sites')}</Link></Button>
      <ListPageHeader icon={MapPinned} title={`${s.code} · ${s.name}`} description={s.address ?? undefined}
        actions={has(PERMISSIONS.quarrySite.update) && <Button variant="outline" onClick={() => setEditSite(true)}><Pencil className="mr-1.5 h-4 w-4" /> {tx('Edit')}</Button>} />
      <Section title={tx('Site')}>
        <KeyValues items={[
          [tx('Status'), <StatusPill key="s">{SITE_STATUS_LABEL[s.status] ?? s.status}</StatusPill>],
          [tx('Mining licence number'), s.licenceNumber],
          [tx('Licensing authority'), s.licenceAuthority],
          [tx('Licence expires on'), s.licenceExpiresOn ? date(s.licenceExpiresOn) : null],
          [tx('Branch'), s.branch?.name],
          [tx('Cost centre'), s.costCenter ? `${s.costCenter.code} · ${s.costCenter.name}` : null],
          [tx('Area (ha)'), s.areaHectares],
        ]} />
      </Section>

      <Section title={tx('Zones, benches and faces')}
        actions={has(PERMISSIONS.quarrySite.create) && <Button size="sm" onClick={() => setZone('new')}><Plus className="mr-1 h-4 w-4" /> {tx('Add')}</Button>}>
        {s.zones.length === 0 ? <p className="text-sm text-muted-foreground">{tx('No zones yet.')}</p> : (
          <ul className="divide-y text-sm">
            {s.zones.map((z) => (
              <li key={z.id} className={`flex items-center justify-between gap-2 py-2 ${z.parentId ? 'pl-6' : ''}`}>
                <span>
                  <StatusPill tone={z.kind === 'zone' ? 'info' : z.kind === 'bench' ? 'accent' : 'neutral'} dot={false}>{ZONE_KIND_LABEL[z.kind]}</StatusPill>{' '}
                  <span className="font-medium">{z.code}</span> · {z.name}
                  {z.materialName && <span className="text-muted-foreground"> · {z.materialName}</span>}
                  {z.status !== 'active' && <span className="text-muted-foreground"> · {z.status}</span>}
                </span>
                <span className="flex gap-1">
                  {has(PERMISSIONS.quarrySite.update) && (
                    <Button variant="ghost" size="icon" className="h-8 w-8" aria-label={tx('Edit')} onClick={() => setZone(z)}><Pencil className="h-4 w-4" /></Button>
                  )}
                  {has(PERMISSIONS.quarrySite.delete) && (
                    <Button variant="ghost" size="icon" className="h-8 w-8" aria-label={tx('Delete')}
                      onClick={async () => {
                        try { await deleteZone.mutateAsync(z.id); notify.success(tx('Deleted')); } catch (e) { notify.error(apiMessage(e, tx('Could not delete'))); }
                      }}><Trash2 className="h-4 w-4" /></Button>
                  )}
                </span>
              </li>
            ))}
          </ul>
        )}
      </Section>

      <Section title={tx('Stockpiles')}
        actions={has(PERMISSIONS.quarrySite.create) && <Button size="sm" onClick={() => setStockpile('new')}><Plus className="mr-1 h-4 w-4" /> {tx('Add')}</Button>}>
        {s.stockpiles.length === 0 ? <p className="text-sm text-muted-foreground">{tx('No stockpile yet: add the ROM stockpile extraction posts into.')}</p> : (
          <div className="grid gap-3 sm:grid-cols-2">
            {s.stockpiles.map((k) => (
              <div key={k.id} className="rounded-lg border p-3 text-sm">
                <div className="flex items-center justify-between gap-2">
                  <Link to={`/quarry/stockpiles/${k.id}`} className="font-medium text-primary hover:underline">{k.code} · {k.name}</Link>
                  <span className="flex gap-1">
                    {k.isDefault && <StatusPill tone="info" dot={false}>{tx('Default')}</StatusPill>}
                    {!k.isActive && <StatusPill tone="neutral">{tx('Inactive')}</StatusPill>}
                    {has(PERMISSIONS.quarrySite.update) && (
                      <Button variant="ghost" size="icon" className="h-7 w-7" aria-label={tx('Edit')} onClick={() => setStockpile(k)}><Pencil className="h-3.5 w-3.5" /></Button>
                    )}
                  </span>
                </div>
                <div className="mt-1 text-muted-foreground">{tonnes(k.onHandTonnes)} · {money(k.onHandValue)} · {k.locationCode}</div>
              </div>
            ))}
          </div>
        )}
      </Section>

      <div className="grid gap-4 lg:grid-cols-2">
        <Section title={tx('Magazines')}>
          {s.magazines.length === 0 ? <p className="text-sm text-muted-foreground">{tx('No magazine at this site.')}</p> : (
            <ul className="text-sm">{s.magazines.map((m) => <li key={m.id}><Link className="text-primary hover:underline" to={`/quarry/magazines/${m.id}`}>{m.code} · {m.name}</Link>{m.licenceExpiresOn ? ` · ${date(m.licenceExpiresOn)}` : ''}</li>)}</ul>
          )}
        </Section>
        <Section title={tx('Open plans')}>
          {s.plans.length === 0 ? <p className="text-sm text-muted-foreground">{tx('No open extraction plan.')}</p> : (
            <ul className="text-sm">{s.plans.map((p) => <li key={p.id}>{p.code} · {p.name} · {date(p.periodStart)} – {date(p.periodEnd)} · {tonnes(p.targetTonnes)}</li>)}</ul>
          )}
        </Section>
      </div>

      {editSite && (
        <FormDialog title={tx('Edit site {{code}}', { code: s.code })} fields={siteFields(lookups.data)} row={s} saving={saveSite.isPending}
          onSave={(body) => saveSite.mutateAsync({ id: s.id, body })} onClose={() => setEditSite(false)} />
      )}
      {zone && (
        <FormDialog title={zone === 'new' ? tx('Add zone, bench or face') : tx('Edit {{code}}', { code: zone.code })} fields={zoneFields}
          row={zone === 'new' ? null : zone} defaults={{ siteId: s.id, kind: 'zone', status: 'active' }} saving={saveZone.isPending}
          onSave={(body) => saveZone.mutateAsync({ id: zone === 'new' ? undefined : zone.id, body: { ...body, siteId: s.id } })}
          onClose={() => setZone(null)} />
      )}
      {stockpile && (
        <FormDialog title={stockpile === 'new' ? tx('Add stockpile') : tx('Edit {{code}}', { code: stockpile.code })} fields={stockpileFields}
          row={stockpile === 'new' ? null : stockpile} defaults={{ siteId: s.id, kind: 'rom', isActive: true }} saving={saveStockpile.isPending}
          description={tx('A stockpile is an inventory location: extraction receives raw stone into it at its cost per tonne.')}
          onSave={(body) => saveStockpile.mutateAsync({ id: stockpile === 'new' ? undefined : stockpile.id, body: { ...body, siteId: s.id } })}
          onClose={() => setStockpile(null)} />
      )}
    </div>
  );
}

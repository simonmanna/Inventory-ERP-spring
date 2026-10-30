import { useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { ArrowLeft, Pencil, Plus, Warehouse } from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { Button } from '@/components/ui/button';
import { Skeleton } from '@/components/ui/skeleton';
import { DataTable, type Column } from '@/components/data-table';
import { ListCard, ListPageHeader, StatusPill } from '@/components/list';
import { useAuthStore } from '@/stores/auth.store';
import { date, dateTime, money } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';
import {
  EXPLOSIVE_KIND_LABEL, useExplosives, useMagazine, useMagazines, useQuarryLookups, useSaveRow, type Explosive, type Magazine,
} from '@/features/quarry/api';
import { EmptyState, FormDialog, IssueStatusPill, KeyValues, Section, qty, toOptions, type FieldSpec } from './quarry-ui';

function magazineFields(L: ReturnType<typeof useQuarryLookups>['data'], editing: Magazine | 'new'): FieldSpec[] {
  return [
    { name: 'code', label: tx('Code'), hint: tx('Automatic when blank') },
    { name: 'name', label: tx('Name'), required: true },
    { name: 'siteId', label: tx('Site'), type: 'select', options: (L?.sites ?? []).map((s) => ({ value: s.id, label: `${s.code} · ${s.name}` })) },
    { name: 'locationId', label: tx('Inventory location'), type: 'select', disabled: editing !== 'new',
      options: (L?.locations ?? []).map((l) => ({ value: l.id, label: `${l.code} · ${l.name}` })), hint: tx('Leave blank to create a new location') },
    { name: 'licenceNumber', label: tx('Magazine licence number') },
    { name: 'licenceAuthority', label: tx('Licensing authority') },
    { name: 'licenceExpiresOn', label: tx('Licence expires on'), type: 'date' },
    { name: 'capacityKg', label: tx('Capacity (kg NEQ)'), type: 'number', min: 0 },
    { name: 'keeperEmployeeId', label: tx('Magazine keeper'), type: 'employee', pickedLabel: editing !== 'new' ? editing.keeperName : null },
    { name: 'isActive', label: tx('Active'), type: 'checkbox', hint: tx('Active') },
    { name: 'notes', label: tx('Notes'), type: 'textarea' },
  ];
}

/** Explosives magazines (licensed inventory locations) and the registry of controlled explosive products. */
export function MagazinesPage() {
  const has = useAuthStore((s) => s.hasPermission);
  const canManage = has(PERMISSIONS.quarryBlasting.magazine);
  const lookups = useQuarryLookups();
  const magazines = useMagazines();
  const explosives = useExplosives();
  const saveMagazine = useSaveRow('/quarry/magazines');
  const saveExplosive = useSaveRow('/quarry/explosives');
  const [magazine, setMagazine] = useState<Magazine | 'new' | null>(null);
  const [explosive, setExplosive] = useState<Explosive | 'new' | null>(null);
  const explosiveFields: FieldSpec[] = [
    { name: 'productId', label: tx('Inventory product'), type: 'product', required: true, disabled: explosive !== 'new',
      pickedLabel: explosive && explosive !== 'new' ? explosive.productName : null },
    { name: 'kind', label: tx('Kind'), type: 'select', required: true, options: toOptions(EXPLOSIVE_KIND_LABEL) },
    { name: 'hazardClass', label: tx('Hazard class'), hint: tx('e.g. 1.1D') },
    { name: 'unNumber', label: tx('UN number'), hint: tx('e.g. UN0241') },
    { name: 'netExplosiveKg', label: tx('Net explosive kg per unit'), type: 'number', min: 0 },
    { name: 'controlled', label: tx('Controlled'), type: 'checkbox', hint: tx('Issued only through approved explosive issues') },
    { name: 'isActive', label: tx('Active'), type: 'checkbox', hint: tx('Active') },
    { name: 'notes', label: tx('Notes'), type: 'textarea' },
  ];
  const mCols: Column<Magazine>[] = [
    { key: 'code', header: tx('Magazine'), render: (m) => <Link className="font-medium text-primary hover:underline" to={`/quarry/magazines/${m.id}`}>{m.code} · {m.name}</Link> },
    { key: 'site', header: tx('Site'), render: (m) => m.siteCode ?? '—' },
    { key: 'licence', header: tx('Licence'), render: (m) => (m.licenceNumber ? `${m.licenceNumber}${m.licenceExpiresOn ? ` · ${date(m.licenceExpiresOn)}` : ''}` : '—') },
    { key: 'keeper', header: tx('Keeper'), render: (m) => m.keeperName || '—' },
    { key: 'kg', header: tx('Stock (kg NEQ)'), className: 'text-right', render: (m) => qty(m.netExplosiveKg) },
    { key: 'active', header: tx('Status'), render: (m) => (m.isActive ? <StatusPill tone="success">{tx('Active')}</StatusPill> : <StatusPill>{tx('Inactive')}</StatusPill>) },
    { key: 'edit', header: '', className: 'w-12', render: (m) => canManage && <Button variant="ghost" size="icon" className="h-8 w-8" aria-label={tx('Edit')} onClick={() => setMagazine(m)}><Pencil className="h-4 w-4" /></Button> },
  ];
  const xCols: Column<Explosive>[] = [
    { key: 'name', header: tx('Explosive'), render: (x) => `${x.productName} · ${x.productCode}` },
    { key: 'kind', header: tx('Kind'), render: (x) => EXPLOSIVE_KIND_LABEL[x.kind] ?? x.kind },
    { key: 'class', header: tx('Hazard class / UN'), render: (x) => [x.hazardClass, x.unNumber].filter(Boolean).join(' · ') || '—' },
    { key: 'stock', header: tx('In magazines'), className: 'text-right', render: (x) => qty(x.magazineStock, 3) },
    { key: 'controlled', header: tx('Controlled'), render: (x) => (x.controlled ? <StatusPill tone="danger">{tx('Controlled')}</StatusPill> : '—') },
    { key: 'edit', header: '', className: 'w-12', render: (x) => canManage && <Button variant="ghost" size="icon" className="h-8 w-8" aria-label={tx('Edit')} onClick={() => setExplosive(x)}><Pencil className="h-4 w-4" /></Button> },
  ];
  return (
    <div className="space-y-4">
      <ListPageHeader icon={Warehouse} title={tx('Magazines & Explosives')} description={tx('Explosives are controlled stock: received into a licensed magazine, issued only after approval.')}
        actions={canManage && (
          <>
            <Button variant="outline" onClick={() => setExplosive('new')}><Plus className="mr-1.5 h-4 w-4" /> {tx('Register explosive')}</Button>
            <Button onClick={() => setMagazine('new')}><Plus className="mr-1.5 h-4 w-4" /> {tx('Add magazine')}</Button>
          </>
        )} />
      <p className="text-sm text-muted-foreground">{tx('Receive explosives into a magazine with a goods receipt (Procurement → Goods receipts) to its inventory location.')}</p>
      <ListCard>
        <DataTable columns={mCols} data={magazines.data ?? []} loading={magazines.isLoading} getRowId={(m) => m.id} cellClassName="py-2.5 px-4"
          emptyMessage={magazines.isError ? tx('Could not load the magazines. Try again.') : tx('No magazines yet.')} />
      </ListCard>
      <h2 className="font-semibold">{tx('Controlled explosive registry')}</h2>
      <ListCard>
        <DataTable columns={xCols} data={explosives.data ?? []} loading={explosives.isLoading} getRowId={(x) => x.id} cellClassName="py-2.5 px-4"
          emptyMessage={tx('No explosive products registered.')} />
      </ListCard>
      {magazine && (
        <FormDialog title={magazine === 'new' ? tx('Add magazine') : tx('Edit {{code}}', { code: magazine.code })} fields={magazineFields(lookups.data, magazine)}
          row={magazine === 'new' ? null : magazine} defaults={{ isActive: true }} saving={saveMagazine.isPending}
          onSave={(body) => saveMagazine.mutateAsync({ id: magazine === 'new' ? undefined : magazine.id, body })} onClose={() => setMagazine(null)} />
      )}
      {explosive && (
        <FormDialog title={explosive === 'new' ? tx('Register explosive') : tx('Edit explosive')} fields={explosiveFields}
          row={explosive === 'new' ? null : explosive} defaults={{ kind: 'high_explosive', netExplosiveKg: '1', controlled: true, isActive: true }} saving={saveExplosive.isPending}
          onSave={(body) => saveExplosive.mutateAsync({ id: explosive === 'new' ? undefined : explosive.id, body: explosive === 'new' ? body : { ...body, productId: explosive.productId } })}
          onClose={() => setExplosive(null)} />
      )}
    </div>
  );
}

/** A magazine: licence, keeper, explosive stock per product and the latest issues from it. */
export function MagazineDetailPage() {
  const { id = '' } = useParams();
  const { data: m, isLoading, isError } = useMagazine(id);
  if (isLoading) return <Skeleton className="h-64" />;
  if (isError || !m) return <EmptyState>{tx('Could not load the magazine.')}</EmptyState>;
  return (
    <div className="space-y-4">
      <Button variant="ghost" size="sm" asChild><Link to="/quarry/magazines"><ArrowLeft className="mr-1 h-4 w-4" /> {tx('Magazines')}</Link></Button>
      <ListPageHeader icon={Warehouse} title={`${m.code} · ${m.name}`} description={m.locationCode} />
      <Section title={tx('Licence')}>
        <KeyValues items={[[tx('Magazine licence number'), m.licenceNumber], [tx('Licensing authority'), m.licenceAuthority],
          [tx('Licence expires on'), m.licenceExpiresOn ? date(m.licenceExpiresOn) : null], [tx('Capacity (kg NEQ)'), m.capacityKg], [tx('Magazine keeper'), m.keeperName]]} />
      </Section>
      <Section title={tx('Stock')}>
        <ul className="divide-y text-sm">
          {m.stock.map((s) => (
            <li key={s.explosiveId} className="flex justify-between gap-2 py-2">
              <span>{s.productName} · {EXPLOSIVE_KIND_LABEL[s.kind] ?? s.kind}{s.hazardClass ? ` · ${s.hazardClass}` : ''}</span>
              <span className="tabular-nums">{qty(s.quantity, 3)} · {money(s.value)}</span>
            </li>
          ))}
        </ul>
      </Section>
      <Section title={tx('Recent issues')}>
        {m.recentIssues.length === 0 ? <p className="text-sm text-muted-foreground">{tx('No issues yet.')}</p> : (
          <ul className="divide-y text-sm">
            {m.recentIssues.map((i) => (
              <li key={i.id} className="flex items-center justify-between gap-2 py-2">
                <Link className="text-primary hover:underline" to={`/quarry/explosive-issues/${i.id}`}>{i.code} · {i.blastCode}</Link>
                <span className="flex items-center gap-2">{i.issuedAt ? dateTime(i.issuedAt) : ''} <IssueStatusPill status={i.status} /></span>
              </li>
            ))}
          </ul>
        )}
      </Section>
    </div>
  );
}

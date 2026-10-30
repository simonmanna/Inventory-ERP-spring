import { useEffect, useState } from 'react';
import { Pencil, Plus, Settings2, Trash2 } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { DataTable, type Column } from '@/components/data-table';
import { ListCard, ListPageHeader, StatusPill } from '@/components/list';
import { notify, apiMessage } from '@/lib/notify';
import { date, money } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';
import { Field, OptionalSelect } from '@/pages/fleet/fleet-ui';
import {
  useDeleteRow, useEquipmentRates, useQuarryLookups, useQuarrySettings, useRoyaltyRates, useSaveRow, useSaveSettings, type EquipmentRate, type RoyaltyRate,
} from '@/features/quarry/api';
import { FormDialog, Section, type FieldSpec } from './quarry-ui';

/** Quarry settings: cost-allocation rule, fallback rates, explosive SoD, plant hourly rates, royalty / levy rates. */
export function QuarrySettingsPage() {
  const settings = useQuarrySettings();
  const saveSettings = useSaveSettings();
  const lookups = useQuarryLookups();
  const rates = useEquipmentRates();
  const royalties = useRoyaltyRates();
  const saveRate = useSaveRow('/quarry/equipment-rates');
  const deleteRate = useDeleteRow('/quarry/equipment-rates');
  const saveRoyalty = useSaveRow('/quarry/royalty-rates');
  const deleteRoyalty = useDeleteRow('/quarry/royalty-rates');
  const [form, setForm] = useState({ allocationBasis: 'net_tonnes', fuelCostPerLitre: '', labourRatePerHour: '', equipmentRatePerHour: '', distinctExplosiveIssuer: true });
  const [rate, setRate] = useState<EquipmentRate | 'new' | null>(null);
  const [royalty, setRoyalty] = useState<RoyaltyRate | 'new' | null>(null);
  useEffect(() => {
    const s = settings.data;
    if (s) setForm({ allocationBasis: s.allocationBasis, fuelCostPerLitre: s.fuelCostPerLitre, labourRatePerHour: s.labourRatePerHour,
      equipmentRatePerHour: s.equipmentRatePerHour, distinctExplosiveIssuer: s.distinctExplosiveIssuer });
  }, [settings.data]);
  const L = lookups.data;
  const rateFields: FieldSpec[] = [
    { name: 'categoryId', label: tx('Vehicle class'), type: 'select', disabled: rate !== 'new', options: (L?.vehicleClasses ?? []).filter((c) => c.kind === 'mobile_plant').map((c) => ({ value: c.id, label: c.name })) },
    { name: 'vehicleId', label: tx('Or one vehicle'), type: 'select', disabled: rate !== 'new', options: (L?.plant ?? []).map((v) => ({ value: v.id, label: `${v.code} · ${v.className}` })) },
    { name: 'ratePerHour', label: tx('Rate per hour'), type: 'number', required: true, min: 0 },
    { name: 'notes', label: tx('Notes'), wide: true },
  ];
  const royaltyFields: FieldSpec[] = [
    { name: 'name', label: tx('Name'), required: true },
    { name: 'kind', label: tx('Kind'), type: 'select', required: true, options: [{ value: 'royalty', label: tx('Royalty') }, { value: 'levy', label: tx('Levy') }, { value: 'fee', label: tx('Fee') }] },
    { name: 'siteId', label: tx('Site'), type: 'select', hint: tx('Blank = every site'), options: (L?.sites ?? []).map((s) => ({ value: s.id, label: `${s.code} · ${s.name}` })) },
    { name: 'materialId', label: tx('Material'), type: 'select', hint: tx('Blank = every material'), options: (L?.materials ?? []).map((m) => ({ value: m.id, label: m.name })) },
    { name: 'basis', label: tx('Per'), type: 'select', required: true, options: [{ value: 'net_tonnes', label: tx('Net tonne') }, { value: 'gross_tonnes', label: tx('Gross tonne') }] },
    { name: 'ratePerTonne', label: tx('Rate per tonne'), type: 'number', required: true, min: 0 },
    { name: 'authorityPartnerId', label: tx('Authority (payee)'), type: 'partner', pickedLabel: royalty && royalty !== 'new' ? royalty.authorityName : null },
    { name: 'effectiveFrom', label: tx('From'), type: 'date', required: true },
    { name: 'effectiveTo', label: tx('To'), type: 'date' },
    { name: 'isActive', label: tx('Active'), type: 'checkbox', hint: tx('Active') },
    { name: 'notes', label: tx('Notes'), type: 'textarea' },
  ];
  const del = async (fn: () => Promise<unknown>) => { try { await fn(); notify.success(tx('Deleted')); } catch (e) { notify.error(apiMessage(e, tx('Could not delete'))); } };
  const rateCols: Column<EquipmentRate>[] = [
    { key: 'target', header: tx('Class / vehicle'), render: (r) => r.vehicleCode ?? r.categoryName ?? '—' },
    { key: 'rate', header: tx('Rate per hour'), className: 'text-right', render: (r) => money(r.ratePerHour) },
    { key: 'actions', header: '', className: 'w-20 text-right', render: (r) => (
      <span className="flex justify-end gap-1">
        <Button variant="ghost" size="icon" className="h-8 w-8" aria-label={tx('Edit')} onClick={() => setRate(r)}><Pencil className="h-4 w-4" /></Button>
        <Button variant="ghost" size="icon" className="h-8 w-8" aria-label={tx('Delete')} onClick={() => del(() => deleteRate.mutateAsync(r.id))}><Trash2 className="h-4 w-4" /></Button>
      </span>
    ) },
  ];
  const royaltyCols: Column<RoyaltyRate>[] = [
    { key: 'name', header: tx('Name'), render: (r) => `${r.name} (${r.kind})` },
    { key: 'scope', header: tx('Scope'), render: (r) => [r.siteCode ?? tx('All sites'), r.materialName ?? tx('All materials')].join(' · ') },
    { key: 'rate', header: tx('Rate'), className: 'text-right', render: (r) => `${money(r.ratePerTonne)} / ${r.basis === 'gross_tonnes' ? tx('gross t') : tx('net t')}` },
    { key: 'period', header: tx('Effective'), render: (r) => `${date(r.effectiveFrom)} – ${r.effectiveTo ? date(r.effectiveTo) : '…'}` },
    { key: 'authority', header: tx('Authority'), render: (r) => r.authorityName ?? '—' },
    { key: 'active', header: tx('Status'), render: (r) => (r.isActive ? <StatusPill tone="success">{tx('Active')}</StatusPill> : <StatusPill>{tx('Inactive')}</StatusPill>) },
    { key: 'actions', header: '', className: 'w-20 text-right', render: (r) => (
      <span className="flex justify-end gap-1">
        <Button variant="ghost" size="icon" className="h-8 w-8" aria-label={tx('Edit')} onClick={() => setRoyalty(r)}><Pencil className="h-4 w-4" /></Button>
        <Button variant="ghost" size="icon" className="h-8 w-8" aria-label={tx('Delete')} onClick={() => del(() => deleteRoyalty.mutateAsync(r.id))}><Trash2 className="h-4 w-4" /></Button>
      </span>
    ) },
  ];
  return (
    <div className="space-y-4">
      <ListPageHeader icon={Settings2} title={tx('Quarry Settings')} description={tx('How extraction is costed per tonne, and who may issue explosives.')} />
      <Section title={tx('Costing')}>
        <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
          <Field label={tx('Cost allocation')}>
            <OptionalSelect value={form.allocationBasis} onChange={(v) => setForm((f) => ({ ...f, allocationBasis: v || 'net_tonnes' }))} placeholder={tx('Cost allocation')}
              options={[{ value: 'net_tonnes', label: tx('Per net tonne (losses carried by the good stone)') }, { value: 'gross_tonnes', label: tx('Per gross tonne (loss share written off)') }]} />
          </Field>
          <Field label={tx('Fuel cost per litre')}><Input type="number" min="0" value={form.fuelCostPerLitre} onChange={(e) => setForm((f) => ({ ...f, fuelCostPerLitre: e.target.value }))} /></Field>
          <Field label={tx('Labour rate per hour')}><Input type="number" min="0" value={form.labourRatePerHour} onChange={(e) => setForm((f) => ({ ...f, labourRatePerHour: e.target.value }))} /></Field>
          <Field label={tx('Default equipment rate per hour')}><Input type="number" min="0" value={form.equipmentRatePerHour} onChange={(e) => setForm((f) => ({ ...f, equipmentRatePerHour: e.target.value }))} /></Field>
          <label className="flex items-center gap-2 text-sm sm:col-span-2">
            <input type="checkbox" checked={form.distinctExplosiveIssuer} onChange={(e) => setForm((f) => ({ ...f, distinctExplosiveIssuer: e.target.checked }))} />
            {tx('The person who requested explosives may not issue them from the magazine')}
          </label>
        </div>
        <Button disabled={saveSettings.isPending} onClick={async () => {
          try {
            await saveSettings.mutateAsync({ allocationBasis: form.allocationBasis, fuelCostPerLitre: Number(form.fuelCostPerLitre || 0),
              labourRatePerHour: Number(form.labourRatePerHour || 0), equipmentRatePerHour: Number(form.equipmentRatePerHour || 0),
              distinctExplosiveIssuer: form.distinctExplosiveIssuer });
            notify.success(tx('Saved'));
          } catch (e) { notify.error(apiMessage(e, tx('Could not save'))); }
        }}>{tx('Save settings')}</Button>
      </Section>
      <Section title={tx('Equipment hourly rates')} actions={<Button size="sm" onClick={() => setRate('new')}><Plus className="mr-1 h-4 w-4" /> {tx('Add')}</Button>}>
        <ListCard><DataTable columns={rateCols} data={rates.data ?? []} loading={rates.isLoading} getRowId={(r) => r.id} cellClassName="py-2 px-4" emptyMessage={tx('No equipment rates yet.')} /></ListCard>
      </Section>
      <Section title={tx('Royalties, levies and fees')} actions={<Button size="sm" onClick={() => setRoyalty('new')}><Plus className="mr-1 h-4 w-4" /> {tx('Add')}</Button>}>
        <ListCard><DataTable columns={royaltyCols} data={royalties.data ?? []} loading={royalties.isLoading} getRowId={(r) => r.id} cellClassName="py-2 px-4" emptyMessage={tx('No royalty or levy rates yet.')} /></ListCard>
      </Section>
      {rate && (
        <FormDialog title={rate === 'new' ? tx('Add equipment rate') : tx('Edit equipment rate')} fields={rateFields} row={rate === 'new' ? null : rate}
          saving={saveRate.isPending} onSave={(body) => saveRate.mutateAsync({ id: rate === 'new' ? undefined : rate.id, body })} onClose={() => setRate(null)} />
      )}
      {royalty && (
        <FormDialog title={royalty === 'new' ? tx('Add royalty or levy') : tx('Edit {{name}}', { name: royalty.name })} fields={royaltyFields}
          row={royalty === 'new' ? null : royalty} defaults={{ kind: 'royalty', basis: 'net_tonnes', isActive: true, effectiveFrom: new Date().toISOString().slice(0, 10) }}
          saving={saveRoyalty.isPending} onSave={(body) => saveRoyalty.mutateAsync({ id: royalty === 'new' ? undefined : royalty.id, body })} onClose={() => setRoyalty(null)} />
      )}
    </div>
  );
}

import { useState } from 'react';
import { Edit, Plus, Settings2, Trash2 } from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Dialog, DialogContent, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs';
import { DataTable, type Column } from '@/components/data-table';
import { ListCard, ListPageHeader, StatusPill } from '@/components/list';
import { useAuthStore } from '@/stores/auth.store';
import { notify, apiMessage } from '@/lib/notify';
import { money } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';
import {
  CHARGE_BASIS_LABEL, FREIGHT_BASIS_LABEL, toNum, useChargeRules, useCreateChargeRule, useCreateRate,
  useDeleteChargeRule, useDeleteLoadFactor, useDeleteRate, useDispatchLookups, useLoadFactors, usePutLoadFactor,
  useTransporterRates, useUpdateChargeRule, useUpdateRate, type ChargeRule, type LoadFactor, type ProductOption,
  type TransporterRate,
} from '@/features/fleet/dispatch-api';
import { Field, OptionalSelect } from '../fleet-ui';
import { num, toOptions } from '../fleet-format';
import { CustomerPicker, ProductPicker } from './dispatch-ui';

const text = (v: string) => (v.trim() ? v.trim() : undefined);

/** Dispatch settings (fleet 1.3): delivery-charge rules, transporter freight rates, product load factors. */
export function DispatchSettingsPage() {
  const [tab, setTab] = useState('charges');
  const canEdit = useAuthStore((s) => s.hasPermission(PERMISSIONS.fleet.settings));
  return (
    <div className="space-y-4">
      <ListPageHeader icon={Settings2} title={tx('Dispatch Settings')} description={tx('Delivery charges billed to customers, transporter freight rates and product load factors.')} />
      <Tabs value={tab} onValueChange={setTab}>
        <TabsList>
          <TabsTrigger value="charges">{tx('Delivery charges')}</TabsTrigger>
          <TabsTrigger value="rates">{tx('Transporter rates')}</TabsTrigger>
          <TabsTrigger value="factors">{tx('Load factors')}</TabsTrigger>
        </TabsList>
        <TabsContent value="charges"><ChargeRulesTab canEdit={canEdit} /></TabsContent>
        <TabsContent value="rates"><RatesTab canEdit={canEdit} /></TabsContent>
        <TabsContent value="factors"><FactorsTab canEdit={canEdit} /></TabsContent>
      </Tabs>
    </div>
  );
}

function ActiveDot({ active }: { active: boolean }) {
  return <StatusPill tone={active ? 'success' : 'neutral'}>{active ? tx('Active') : tx('Inactive')}</StatusPill>;
}

// ---------------------------------------------------------------- charge rules

function ChargeRulesTab({ canEdit }: { canEdit: boolean }) {
  const { data, isLoading } = useChargeRules(true);
  const remove = useDeleteChargeRule();
  const [editing, setEditing] = useState<ChargeRule | 'new' | null>(null);
  const del = async (r: ChargeRule) => {
    if (!window.confirm(tx('Delete rule {{name}}?', { name: r.name }))) return;
    try { await remove.mutateAsync(r.id); notify.success(tx('Rule removed')); } catch (err) { notify.error(apiMessage(err, tx('Could not delete the rule'))); }
  };
  const columns: Column<ChargeRule>[] = [
    { key: 'name', header: tx('Rule'), render: (r) => <span className="font-medium">{r.name}</span> },
    { key: 'customer', header: tx('Customer'), render: (r) => r.partnerName ?? tx('Any') },
    { key: 'zone', header: tx('Zone'), render: (r) => r.zone ?? tx('Any') },
    { key: 'band', header: tx('Distance band'), render: (r) => (r.minDistanceKm || r.maxDistanceKm ? `${num(r.minDistanceKm ?? 0, 1)}–${r.maxDistanceKm ? num(r.maxDistanceKm, 1) : '∞'} km` : tx('Any')) },
    { key: 'basis', header: tx('Basis'), render: (r) => CHARGE_BASIS_LABEL[r.basis] },
    { key: 'rate', header: tx('Rate'), className: 'text-right', render: (r) => money(r.rate) },
    { key: 'min', header: tx('Minimum'), className: 'text-right', render: (r) => (r.minAmount ? money(r.minAmount) : '—') },
    { key: 'status', header: tx('Status'), render: (r) => <ActiveDot active={r.isActive} /> },
    { key: 'actions', header: '', className: 'text-right', render: (r) => canEdit && (
      <div className="flex justify-end gap-1">
        <Button variant="ghost" size="icon" className="h-8 w-8" aria-label={tx('Edit')} onClick={() => setEditing(r)}><Edit className="h-4 w-4" /></Button>
        <Button variant="ghost" size="icon" className="h-8 w-8" aria-label={tx('Delete')} onClick={() => del(r)}><Trash2 className="h-4 w-4" /></Button>
      </div>
    ) },
  ];
  return (
    <div className="space-y-3 pt-3">
      <div className="flex items-center justify-between gap-2">
        <p className="text-sm text-muted-foreground">{tx('The most specific matching rule wins: customer + zone, customer, zone, then general.')}</p>
        {canEdit && <Button onClick={() => setEditing('new')}><Plus className="mr-1.5 h-4 w-4" /> {tx('New rule')}</Button>}
      </div>
      <ListCard><div className="overflow-x-auto">
        <DataTable columns={columns} data={data ?? []} loading={isLoading} getRowId={(r) => r.id} cellClassName="py-2.5 px-4" headerRowClassName="h-10" emptyMessage={tx('No delivery-charge rules yet.')} />
      </div></ListCard>
      {editing && <ChargeRuleDialog rule={editing === 'new' ? null : editing} onClose={() => setEditing(null)} />}
    </div>
  );
}

function ChargeRuleDialog({ rule, onClose }: { rule: ChargeRule | null; onClose: () => void }) {
  const lookups = useDispatchLookups();
  const create = useCreateChargeRule();
  const update = useUpdateChargeRule();
  const [partner, setPartner] = useState({ id: rule?.partnerId ?? '', name: rule?.partnerName ?? '' });
  const [f, setF] = useState({
    name: rule?.name ?? '', zone: rule?.zone ?? '', minDistanceKm: rule?.minDistanceKm ?? '', maxDistanceKm: rule?.maxDistanceKm ?? '',
    basis: rule?.basis ?? 'per_tonne', rate: rule?.rate ?? '', minAmount: rule?.minAmount ?? '', taxId: rule?.taxId ?? '', isActive: rule?.isActive ?? true,
  });
  const set = (k: keyof typeof f, v: string | boolean) => setF({ ...f, [k]: v });
  const invalid = !f.name.trim() || f.rate === '' || Number(f.rate) < 0;
  const save = async () => {
    const body = {
      name: f.name.trim(), partnerId: partner.id || undefined, zone: text(f.zone), minDistanceKm: toNum(f.minDistanceKm),
      maxDistanceKm: toNum(f.maxDistanceKm), basis: f.basis, rate: toNum(f.rate), minAmount: toNum(f.minAmount), taxId: f.taxId || undefined,
    };
    try {
      if (rule) {
        const clear = ([['partnerId', partner.id], ['zone', f.zone], ['minDistanceKm', f.minDistanceKm], ['maxDistanceKm', f.maxDistanceKm],
          ['minAmount', f.minAmount], ['taxId', f.taxId]] as const).filter(([k, v]) => rule[k] && !String(v).trim()).map(([k]) => k);
        await update.mutateAsync({ id: rule.id, body: { ...body, isActive: f.isActive, clear: clear.length ? clear : undefined } });
      } else {
        await create.mutateAsync(body);
      }
      notify.success(tx('Rule saved'));
      onClose();
    } catch (err) {
      notify.error(apiMessage(err, tx('Could not save the rule')));
    }
  };
  return (
    <Dialog open onOpenChange={(o) => { if (!o) onClose(); }}>
      <DialogContent className="max-w-lg">
        <DialogHeader><DialogTitle>{rule ? tx('Edit delivery-charge rule') : tx('New delivery-charge rule')}</DialogTitle></DialogHeader>
        <div className="grid grid-cols-2 gap-3">
          <Field label={tx('Name')} className="col-span-2"><Input value={f.name} onChange={(e) => set('name', e.target.value)} /></Field>
          <Field label={tx('Customer (blank = any)')} className="col-span-2">
            <CustomerPicker value={partner.id} label={partner.name} onPick={(id, name) => setPartner({ id, name })} />
          </Field>
          <Field label={tx('Zone (blank = any)')}><Input value={f.zone} onChange={(e) => set('zone', e.target.value)} /></Field>
          <Field label={tx('Basis')}><OptionalSelect value={f.basis} onChange={(v) => set('basis', v || 'per_tonne')} options={toOptions(CHARGE_BASIS_LABEL)} placeholder={tx('Choose…')} /></Field>
          <Field label={tx('From km')}><Input type="number" min={0} step="0.1" value={f.minDistanceKm} onChange={(e) => set('minDistanceKm', e.target.value)} /></Field>
          <Field label={tx('To km')}><Input type="number" min={0} step="0.1" value={f.maxDistanceKm} onChange={(e) => set('maxDistanceKm', e.target.value)} /></Field>
          <Field label={tx('Rate')}><Input type="number" min={0} step="0.01" value={f.rate} onChange={(e) => set('rate', e.target.value)} /></Field>
          <Field label={tx('Minimum charge')}><Input type="number" min={0} step="0.01" value={f.minAmount} onChange={(e) => set('minAmount', e.target.value)} /></Field>
          <Field label={tx('Tax')} className="col-span-2">
            <OptionalSelect value={f.taxId} onChange={(v) => set('taxId', v)} noneLabel={tx('No tax')} options={(lookups.data?.taxes ?? []).map((t) => ({ value: t.id, label: `${t.name} (${num(t.rate, 2)}%)` }))} placeholder={tx('No tax')} />
          </Field>
          {rule && <label className="col-span-2 flex items-center gap-2 text-sm"><input type="checkbox" checked={f.isActive} onChange={(e) => set('isActive', e.target.checked)} /> {tx('Active')}</label>}
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={onClose}>{tx('Cancel')}</Button>
          <Button disabled={invalid || create.isPending || update.isPending} onClick={save}>{tx('Save')}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

// ---------------------------------------------------------------- transporter rates

function RatesTab({ canEdit }: { canEdit: boolean }) {
  const { data, isLoading } = useTransporterRates(true);
  const remove = useDeleteRate();
  const [editing, setEditing] = useState<TransporterRate | 'new' | null>(null);
  const del = async (r: TransporterRate) => {
    if (!window.confirm(tx('Delete rate {{name}}?', { name: r.name }))) return;
    try { await remove.mutateAsync(r.id); notify.success(tx('Rate removed')); } catch (err) { notify.error(apiMessage(err, tx('Could not delete the rate'))); }
  };
  const columns: Column<TransporterRate>[] = [
    { key: 'partner', header: tx('Transporter'), render: (r) => <span className="font-medium">{r.partnerName}</span> },
    { key: 'name', header: tx('Rate card'), render: (r) => r.name },
    { key: 'basis', header: tx('Basis'), render: (r) => FREIGHT_BASIS_LABEL[r.basis] },
    { key: 'rate', header: tx('Rate'), className: 'text-right', render: (r) => money(r.rate) },
    { key: 'zone', header: tx('Zone'), render: (r) => r.zone ?? '—' },
    { key: 'valid', header: tx('Valid'), render: (r) => [r.effectiveFrom, r.effectiveTo].filter(Boolean).join(' → ') || '—' },
    { key: 'status', header: tx('Status'), render: (r) => <ActiveDot active={r.isActive} /> },
    { key: 'actions', header: '', className: 'text-right', render: (r) => canEdit && (
      <div className="flex justify-end gap-1">
        <Button variant="ghost" size="icon" className="h-8 w-8" aria-label={tx('Edit')} onClick={() => setEditing(r)}><Edit className="h-4 w-4" /></Button>
        <Button variant="ghost" size="icon" className="h-8 w-8" aria-label={tx('Delete')} onClick={() => del(r)}><Trash2 className="h-4 w-4" /></Button>
      </div>
    ) },
  ];
  return (
    <div className="space-y-3 pt-3">
      <div className="flex justify-end">{canEdit && <Button onClick={() => setEditing('new')}><Plus className="mr-1.5 h-4 w-4" /> {tx('New rate')}</Button>}</div>
      <ListCard><div className="overflow-x-auto">
        <DataTable columns={columns} data={data ?? []} loading={isLoading} getRowId={(r) => r.id} cellClassName="py-2.5 px-4" headerRowClassName="h-10" emptyMessage={tx('No transporter rates yet.')} />
      </div></ListCard>
      {editing && <RateDialog rate={editing === 'new' ? null : editing} onClose={() => setEditing(null)} />}
    </div>
  );
}

function RateDialog({ rate, onClose }: { rate: TransporterRate | null; onClose: () => void }) {
  const lookups = useDispatchLookups();
  const create = useCreateRate();
  const update = useUpdateRate();
  const [f, setF] = useState({
    partnerId: rate?.partnerId ?? '', name: rate?.name ?? '', basis: rate?.basis ?? 'per_tonne', rate: rate?.rate ?? '',
    zone: rate?.zone ?? '', effectiveFrom: rate?.effectiveFrom ?? '', effectiveTo: rate?.effectiveTo ?? '', isActive: rate?.isActive ?? true,
  });
  const set = (k: keyof typeof f, v: string | boolean) => setF({ ...f, [k]: v });
  const invalid = (!rate && !f.partnerId) || !f.name.trim() || f.rate === '';
  const save = async () => {
    try {
      if (rate) {
        const clear = ([['zone', f.zone], ['effectiveFrom', f.effectiveFrom], ['effectiveTo', f.effectiveTo]] as const)
          .filter(([k, v]) => rate[k] && !String(v).trim()).map(([k]) => k);
        await update.mutateAsync({ id: rate.id, body: {
          name: f.name.trim(), rate: toNum(f.rate), zone: text(f.zone), effectiveFrom: f.effectiveFrom || undefined,
          effectiveTo: f.effectiveTo || undefined, isActive: f.isActive, clear: clear.length ? clear : undefined,
        } });
      } else {
        await create.mutateAsync({
          partnerId: f.partnerId, name: f.name.trim(), basis: f.basis, rate: toNum(f.rate), zone: text(f.zone),
          effectiveFrom: f.effectiveFrom || undefined, effectiveTo: f.effectiveTo || undefined,
        });
      }
      notify.success(tx('Rate saved'));
      onClose();
    } catch (err) {
      notify.error(apiMessage(err, tx('Could not save the rate')));
    }
  };
  return (
    <Dialog open onOpenChange={(o) => { if (!o) onClose(); }}>
      <DialogContent className="max-w-lg">
        <DialogHeader><DialogTitle>{rate ? tx('Edit transporter rate') : tx('New transporter rate')}</DialogTitle></DialogHeader>
        <div className="grid grid-cols-2 gap-3">
          <Field label={tx('Transporter')} className="col-span-2">
            <OptionalSelect value={f.partnerId} disabled={!!rate} onChange={(v) => set('partnerId', v)} options={(lookups.data?.transporters ?? []).map((p) => ({ value: p.id, label: p.name }))} placeholder={tx('Choose the transporter')} />
          </Field>
          <Field label={tx('Rate card name')} className="col-span-2"><Input value={f.name} onChange={(e) => set('name', e.target.value)} /></Field>
          <Field label={tx('Basis')}><OptionalSelect value={f.basis} disabled={!!rate} onChange={(v) => set('basis', v || 'per_tonne')} options={toOptions(FREIGHT_BASIS_LABEL)} placeholder={tx('Choose…')} /></Field>
          <Field label={tx('Rate')}><Input type="number" min={0} step="0.01" value={f.rate} onChange={(e) => set('rate', e.target.value)} /></Field>
          <Field label={tx('Zone')}><Input value={f.zone} onChange={(e) => set('zone', e.target.value)} /></Field>
          <div />
          <Field label={tx('Valid from')}><Input type="date" value={f.effectiveFrom} onChange={(e) => set('effectiveFrom', e.target.value)} /></Field>
          <Field label={tx('Valid to')}><Input type="date" value={f.effectiveTo} onChange={(e) => set('effectiveTo', e.target.value)} /></Field>
          {rate && <label className="col-span-2 flex items-center gap-2 text-sm"><input type="checkbox" checked={f.isActive} onChange={(e) => set('isActive', e.target.checked)} /> {tx('Active')}</label>}
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={onClose}>{tx('Cancel')}</Button>
          <Button disabled={invalid || create.isPending || update.isPending} onClick={save}>{tx('Save')}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

// ---------------------------------------------------------------- load factors

function FactorsTab({ canEdit }: { canEdit: boolean }) {
  const { data, isLoading } = useLoadFactors();
  const put = usePutLoadFactor();
  const remove = useDeleteLoadFactor();
  const [product, setProduct] = useState<ProductOption | null>(null);
  const [t, setT] = useState('');
  const [m, setM] = useState('');
  const save = async () => {
    if (!product) return;
    try {
      await put.mutateAsync({ productId: product.id, body: { tonnesPerUnit: toNum(t), cubicMetresPerUnit: toNum(m) } });
      notify.success(tx('Load factor saved'));
      setProduct(null); setT(''); setM('');
    } catch (err) {
      notify.error(apiMessage(err, tx('Could not save the load factor')));
    }
  };
  const del = async (f: LoadFactor) => {
    try { await remove.mutateAsync(f.productId); notify.success(tx('Load factor removed')); } catch (err) { notify.error(apiMessage(err, tx('Could not delete the load factor'))); }
  };
  const columns: Column<LoadFactor>[] = [
    { key: 'product', header: tx('Product'), render: (f) => <span className="font-medium">{f.productName}</span> },
    { key: 'unit', header: tx('Base unit'), render: (f) => f.uomCode ?? '—' },
    { key: 't', header: tx('t / unit'), className: 'text-right', render: (f) => num(f.tonnesPerUnit, 6) },
    { key: 'm', header: tx('m³ / unit'), className: 'text-right', render: (f) => num(f.cubicMetresPerUnit, 6) },
    { key: 'actions', header: '', className: 'text-right', render: (f) => canEdit && (
      <div className="flex justify-end gap-1">
        <Button variant="ghost" size="icon" className="h-8 w-8" aria-label={tx('Edit')} onClick={() => {
          setProduct({ id: f.productId, name: f.productName, code: f.productCode, uomId: null, uomCode: f.uomCode, salesPrice: null, trackInventory: true, batchTracking: false, tonnesPerUnit: f.tonnesPerUnit, m3PerUnit: f.cubicMetresPerUnit });
          setT(f.tonnesPerUnit ?? ''); setM(f.cubicMetresPerUnit ?? '');
        }}><Edit className="h-4 w-4" /></Button>
        <Button variant="ghost" size="icon" className="h-8 w-8" aria-label={tx('Delete')} onClick={() => del(f)}><Trash2 className="h-4 w-4" /></Button>
      </div>
    ) },
  ];
  return (
    <div className="space-y-3 pt-3">
      <p className="text-sm text-muted-foreground">{tx('Weight and volume per product base unit, used to split orders into loads. Without a factor the product weight (kg) and volume (m³), or a tonne / m³ unit, are used.')}</p>
      {canEdit && (
        <div className="grid grid-cols-1 gap-3 rounded-xl border bg-card p-3 sm:grid-cols-4 sm:items-end">
          <Field label={tx('Product')} className="sm:col-span-2"><ProductPicker value={product?.id ?? ''} label={product?.name} onPick={(p) => { setProduct(p); setT(p?.tonnesPerUnit ?? ''); setM(p?.m3PerUnit ?? ''); }} /></Field>
          <Field label={tx('t / unit')}><Input type="number" min={0} step="0.000001" value={t} onChange={(e) => setT(e.target.value)} /></Field>
          <Field label={tx('m³ / unit')}><Input type="number" min={0} step="0.000001" value={m} onChange={(e) => setM(e.target.value)} /></Field>
          <div className="sm:col-span-4 flex justify-end">
            <Button disabled={!product || (!t && !m) || put.isPending} onClick={save}>{tx('Save load factor')}</Button>
          </div>
        </div>
      )}
      <ListCard><div className="overflow-x-auto">
        <DataTable columns={columns} data={data ?? []} loading={isLoading} getRowId={(f) => f.id} cellClassName="py-2.5 px-4" headerRowClassName="h-10" emptyMessage={tx('No load factors yet.')} />
      </div></ListCard>
    </div>
  );
}

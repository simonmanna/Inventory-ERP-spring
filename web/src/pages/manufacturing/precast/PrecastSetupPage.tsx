import { useState } from 'react';
import { Pencil, Plus, Settings2 } from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Textarea } from '@/components/ui/textarea';
import { Dialog, DialogContent, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { DataTable, type Column } from '@/components/data-table';
import { DataTablePagination, ListCard, ListPageHeader, ListToolbar, SearchInput, StatusPill, useListState } from '@/components/list';
import { useAuthStore } from '@/stores/auth.store';
import { notify, apiMessage } from '@/lib/notify';
import { tx } from '@/lib/i18n/tx';
import {
  AREA_KIND_LABEL, BLOCK_TYPE_LABEL, useCuringAreas, useProductSpecs, useSaveCuringArea, useSaveProductSpec,
  type BlockType, type CuringArea, type CuringAreaKind, type ProductSpec,
} from '@/features/manufacturing/api-precast';
import { Field, OptionalSelect, ProductPicker } from './precast-ui';
import { ClearableLocation } from './MixDesignForm';
import { dims, nonNegativeOrEmpty, num, optNum, toOptions } from './precast-format';

/** Precast set-up (2.4): block specifications per product and curing areas (chambers, yards, sheds). */
export function PrecastSetupPage() {
  const [tab, setTab] = useState('specs');
  return (
    <div className="space-y-4">
      <ListPageHeader icon={Settings2} title={tx('Precast set-up')}
        description={tx('Block dimensions, strength, pallet and press-cycle sizes; curing areas and their capacity.')} />
      <Tabs value={tab} onValueChange={setTab}>
        <TabsList>
          <TabsTrigger value="specs">{tx('Block specifications')}</TabsTrigger>
          <TabsTrigger value="areas">{tx('Curing areas')}</TabsTrigger>
        </TabsList>
        <TabsContent value="specs"><SpecsSection /></TabsContent>
        <TabsContent value="areas"><AreasSection /></TabsContent>
      </Tabs>
    </div>
  );
}

const s = (v: unknown) => (v === null || v === undefined ? '' : String(v));

function SpecsSection() {
  const has = useAuthStore((st) => st.hasPermission);
  const canEdit = has(PERMISSIONS.bom.update);
  const list = useListState({});
  const { data, isLoading, isFetching, isError } = useProductSpecs({ page: list.page, pageSize: list.pageSize, search: list.search || undefined });
  const [editing, setEditing] = useState<ProductSpec | 'new' | null>(null);
  const columns: Column<ProductSpec>[] = [
    { key: 'product', header: tx('Product'), render: (r) => <div><div className="font-medium">{r.productName}</div><div className="text-xs text-muted-foreground">{r.productSku ?? ''}</div></div> },
    { key: 'type', header: tx('Block type'), render: (r) => BLOCK_TYPE_LABEL[r.blockType] ?? r.blockType },
    { key: 'dims', header: tx('Dimensions (mm)'), render: (r) => <span className="whitespace-nowrap tabular-nums">{dims(r)}</span> },
    { key: 'strength', header: tx('Strength class'), render: (r) => [r.strengthClass, r.strengthMpa != null ? `${num(r.strengthMpa, 1)} MPa` : null].filter(Boolean).join(' · ') || '—' },
    { key: 'pallet', header: tx('Blocks per pallet'), className: 'text-right', render: (r) => num(r.unitsPerPallet, 0) },
    { key: 'cycle', header: tx('Blocks per press cycle'), className: 'text-right', render: (r) => num(r.unitsPerCycle, 0) },
    { key: 'second', header: tx('Second-grade product'), render: (r) => r.secondGradeProductName ?? '—' },
    { key: 'active', header: tx('Status'), render: (r) => <StatusPill tone={r.isActive ? 'success' : 'neutral'}>{r.isActive ? tx('Active') : tx('Inactive')}</StatusPill> },
    {
      key: 'actions', header: '', className: 'w-12 text-right',
      render: (r) => canEdit && <Button variant="ghost" size="icon" className="h-8 w-8" aria-label={tx('Edit')} onClick={() => setEditing(r)}><Pencil className="h-4 w-4" /></Button>,
    },
  ];
  return (
    <div className="space-y-3 pt-2">
      <ListToolbar>
        <SearchInput value={list.searchInput} onChange={list.setSearchInput} placeholder={tx('Search products…')} />
        {canEdit && <Button onClick={() => setEditing('new')}><Plus className="mr-1.5 h-4 w-4" /> {tx('Add spec')}</Button>}
      </ListToolbar>
      <ListCard>
        <div className={isFetching && !isLoading ? 'opacity-60 transition-opacity' : 'transition-opacity'}>
          <DataTable columns={columns} data={data?.data ?? []} loading={isLoading} getRowId={(r) => r.id} cellClassName="py-2.5 px-4" headerRowClassName="h-10"
            emptyMessage={isError ? tx('Could not load the block specifications.') : tx('No block specifications yet.')} />
        </div>
        {data?.meta && (
          <DataTablePagination page={data.meta.page} pageSize={list.pageSize} total={data.meta.total} totalPages={data.meta.totalPages}
            onPageChange={list.setPage} onPageSizeChange={list.setPageSize} noun="spec" />
        )}
      </ListCard>
      {editing && <SpecDialog spec={editing === 'new' ? null : editing} onClose={() => setEditing(null)} />}
    </div>
  );
}

function SpecDialog({ spec, onClose }: { spec: ProductSpec | null; onClose: () => void }) {
  const save = useSaveProductSpec();
  const [productId, setProductId] = useState(s(spec?.productId));
  const [blockType, setBlockType] = useState<BlockType>(spec?.blockType ?? 'hollow');
  const [lengthMm, setLengthMm] = useState(s(spec?.lengthMm));
  const [widthMm, setWidthMm] = useState(s(spec?.widthMm));
  const [heightMm, setHeightMm] = useState(s(spec?.heightMm));
  const [strengthClass, setStrengthClass] = useState(s(spec?.strengthClass));
  const [strengthMpa, setStrengthMpa] = useState(s(spec?.strengthMpa));
  const [weightKg, setWeightKg] = useState(s(spec?.weightKg));
  const [unitsPerPallet, setUnitsPerPallet] = useState(s(spec?.unitsPerPallet));
  const [unitsPerCycle, setUnitsPerCycle] = useState(s(spec?.unitsPerCycle));
  const [secondGradeProductId, setSecondGradeProductId] = useState(s(spec?.secondGradeProductId));
  const [notes, setNotes] = useState(s(spec?.notes));
  const [isActive, setIsActive] = useState(spec?.isActive ?? true);
  const numbers = [lengthMm, widthMm, heightMm, strengthMpa, weightKg, unitsPerPallet, unitsPerCycle];
  const numbersOk = numbers.every(nonNegativeOrEmpty);
  const valid = !!productId && numbersOk && secondGradeProductId !== productId;
  const submit = async () => {
    try {
      await save.mutateAsync({
        productId,
        body: {
          blockType, lengthMm: optNum(lengthMm), widthMm: optNum(widthMm), heightMm: optNum(heightMm),
          strengthClass: strengthClass.trim() || undefined, strengthMpa: optNum(strengthMpa), weightKg: optNum(weightKg),
          unitsPerPallet: optNum(unitsPerPallet), unitsPerCycle: optNum(unitsPerCycle),
          secondGradeProductId: secondGradeProductId || undefined, notes: notes.trim() || undefined, isActive,
        },
      });
      notify.success(tx('Block specification saved'));
      onClose();
    } catch (err) {
      notify.error(apiMessage(err, tx('Could not save the block specification')));
    }
  };
  const numField = (label: string, value: string, set: (v: string) => void) => (
    <Field label={label} error={!nonNegativeOrEmpty(value) ? tx('Enter zero or more') : undefined}>
      <Input inputMode="decimal" value={value} onChange={(e) => set(e.target.value)} />
    </Field>
  );
  return (
    <Dialog open onOpenChange={(o) => !o && onClose()}>
      <DialogContent className="max-h-[90vh] overflow-y-auto sm:max-w-2xl">
        <DialogHeader><DialogTitle>{spec ? tx('Edit block specification') : tx('Add spec')}</DialogTitle></DialogHeader>
        <div className="grid grid-cols-1 gap-3 sm:grid-cols-3">
          <Field label={tx('Product *')} className="sm:col-span-2">
            {spec ? <Input value={spec.productName ?? ''} disabled /> : <ProductPicker value={productId} onChange={setProductId} />}
          </Field>
          <Field label={tx('Block type')}>
            <OptionalSelect value={blockType} onChange={(v) => setBlockType((v || 'hollow') as BlockType)} placeholder={tx('Block type')} options={toOptions(BLOCK_TYPE_LABEL)} />
          </Field>
          {numField(tx('Length (mm)'), lengthMm, setLengthMm)}
          {numField(tx('Width (mm)'), widthMm, setWidthMm)}
          {numField(tx('Height (mm)'), heightMm, setHeightMm)}
          <Field label={tx('Strength class')}><Input value={strengthClass} onChange={(e) => setStrengthClass(e.target.value)} placeholder="C7" /></Field>
          {numField(tx('Strength (MPa)'), strengthMpa, setStrengthMpa)}
          {numField(tx('Weight (kg)'), weightKg, setWeightKg)}
          {numField(tx('Blocks per pallet'), unitsPerPallet, setUnitsPerPallet)}
          {numField(tx('Blocks per press cycle'), unitsPerCycle, setUnitsPerCycle)}
          <Field label={tx('Second-grade product')} error={secondGradeProductId && secondGradeProductId === productId ? tx('Must differ from the block product') : undefined}>
            <ProductPicker value={secondGradeProductId} onChange={setSecondGradeProductId} placeholder={tx('None')} />
          </Field>
          <Field label={tx('Notes')} className="sm:col-span-3"><Textarea rows={2} value={notes} onChange={(e) => setNotes(e.target.value)} /></Field>
          <label className="flex items-center gap-2 text-sm"><input type="checkbox" checked={isActive} onChange={(e) => setIsActive(e.target.checked)} /> {tx('Active')}</label>
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={onClose}>{tx('Cancel')}</Button>
          <Button onClick={submit} disabled={!valid || save.isPending}>{tx('Save')}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

function AreasSection() {
  const has = useAuthStore((st) => st.hasPermission);
  const canEdit = has(PERMISSIONS.bom.update);
  const { data, isLoading, isError } = useCuringAreas();
  const [editing, setEditing] = useState<CuringArea | 'new' | null>(null);
  return (
    <div className="space-y-3 pt-2">
      {canEdit && <div className="flex justify-end"><Button onClick={() => setEditing('new')}><Plus className="mr-1.5 h-4 w-4" /> {tx('New curing area')}</Button></div>}
      <ListCard>
        <div className="overflow-x-auto">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>{tx('Code')}</TableHead>
                <TableHead>{tx('Name')}</TableHead>
                <TableHead>{tx('Kind')}</TableHead>
                <TableHead>{tx('Location')}</TableHead>
                <TableHead className="text-right">{tx('Capacity (pallets)')}</TableHead>
                <TableHead className="text-right">{tx('Pallets in use')}</TableHead>
                <TableHead className="text-right">{tx('Batches curing')}</TableHead>
                <TableHead>{tx('Status')}</TableHead>
                <TableHead />
              </TableRow>
            </TableHeader>
            <TableBody>
              {isLoading && <TableRow><TableCell colSpan={9} className="h-20 text-center text-muted-foreground">{tx('Loading…')}</TableCell></TableRow>}
              {!isLoading && !data?.length && (
                <TableRow><TableCell colSpan={9} className="h-20 text-center text-muted-foreground">{isError ? tx('Could not load the curing areas.') : tx('No curing areas yet.')}</TableCell></TableRow>
              )}
              {(data ?? []).map((a) => {
                const full = a.capacityPallets != null && Number(a.capacityPallets) > 0 && Number(a.palletsInUse) >= Number(a.capacityPallets);
                return (
                  <TableRow key={a.id}>
                    <TableCell className="font-mono text-xs">{a.code}</TableCell>
                    <TableCell className="font-medium">{a.name}</TableCell>
                    <TableCell>{AREA_KIND_LABEL[a.kind] ?? a.kind}</TableCell>
                    <TableCell>{a.locationName ?? '—'}</TableCell>
                    <TableCell className="text-right tabular-nums">{num(a.capacityPallets, 0)}</TableCell>
                    <TableCell className={`text-right tabular-nums ${full ? 'font-semibold text-destructive' : ''}`}>{num(a.palletsInUse, 0)}</TableCell>
                    <TableCell className="text-right tabular-nums">{num(a.batchesCuring, 0)}</TableCell>
                    <TableCell><StatusPill tone={a.isActive ? 'success' : 'neutral'}>{a.isActive ? tx('Active') : tx('Inactive')}</StatusPill></TableCell>
                    <TableCell className="text-right">
                      {canEdit && <Button variant="ghost" size="icon" className="h-8 w-8" aria-label={tx('Edit')} onClick={() => setEditing(a)}><Pencil className="h-4 w-4" /></Button>}
                    </TableCell>
                  </TableRow>
                );
              })}
            </TableBody>
          </Table>
        </div>
      </ListCard>
      {editing && <AreaDialog area={editing === 'new' ? null : editing} onClose={() => setEditing(null)} />}
    </div>
  );
}

function AreaDialog({ area, onClose }: { area: CuringArea | null; onClose: () => void }) {
  const save = useSaveCuringArea();
  const [code, setCode] = useState(s(area?.code));
  const [name, setName] = useState(s(area?.name));
  const [kind, setKind] = useState<CuringAreaKind>(area?.kind ?? 'yard');
  const [capacityPallets, setCapacityPallets] = useState(s(area?.capacityPallets));
  const [locationId, setLocationId] = useState(s(area?.locationId));
  const [targetTemperatureC, setTargetTemperatureC] = useState(s(area?.targetTemperatureC));
  const [notes, setNotes] = useState(s(area?.notes));
  const [isActive, setIsActive] = useState(area?.isActive ?? true);
  const tempOk = targetTemperatureC.trim() === '' || Number.isFinite(Number(targetTemperatureC));
  const valid = (!!area || !!code.trim()) && !!name.trim() && nonNegativeOrEmpty(capacityPallets) && tempOk;
  const submit = async () => {
    const body = {
      name: name.trim(), kind, capacityPallets: optNum(capacityPallets), locationId: locationId || undefined,
      targetTemperatureC: optNum(targetTemperatureC), notes: notes.trim() || undefined, isActive,
    };
    try {
      await save.mutateAsync(area ? { id: area.id, body } : { body: { ...body, code: code.trim() } });
      notify.success(tx('Curing area saved'));
      onClose();
    } catch (err) {
      notify.error(apiMessage(err, tx('Could not save the curing area')));
    }
  };
  return (
    <Dialog open onOpenChange={(o) => !o && onClose()}>
      <DialogContent className="max-h-[90vh] overflow-y-auto sm:max-w-lg">
        <DialogHeader><DialogTitle>{area ? tx('Edit curing area') : tx('New curing area')}</DialogTitle></DialogHeader>
        <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
          <Field label={tx('Code *')}><Input value={code} onChange={(e) => setCode(e.target.value)} disabled={!!area} /></Field>
          <Field label={tx('Name *')}><Input value={name} onChange={(e) => setName(e.target.value)} /></Field>
          <Field label={tx('Kind')}>
            <OptionalSelect value={kind} onChange={(v) => setKind((v || 'yard') as CuringAreaKind)} placeholder={tx('Kind')} options={toOptions(AREA_KIND_LABEL)} />
          </Field>
          <Field label={tx('Capacity (pallets)')} error={!nonNegativeOrEmpty(capacityPallets) ? tx('Enter zero or more') : undefined}>
            <Input inputMode="numeric" value={capacityPallets} onChange={(e) => setCapacityPallets(e.target.value)} />
          </Field>
          <Field label={tx('Stock location')}><ClearableLocation value={locationId} onChange={setLocationId} /></Field>
          <Field label={tx('Target temperature (°C)')} error={!tempOk ? tx('Enter a number') : undefined}>
            <Input inputMode="decimal" value={targetTemperatureC} onChange={(e) => setTargetTemperatureC(e.target.value)} />
          </Field>
          <Field label={tx('Notes')} className="sm:col-span-2"><Textarea rows={2} value={notes} onChange={(e) => setNotes(e.target.value)} /></Field>
          <label className="flex items-center gap-2 text-sm"><input type="checkbox" checked={isActive} onChange={(e) => setIsActive(e.target.checked)} /> {tx('Active')}</label>
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={onClose}>{tx('Cancel')}</Button>
          <Button onClick={submit} disabled={!valid || save.isPending}>{tx('Save')}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

export default PrecastSetupPage;

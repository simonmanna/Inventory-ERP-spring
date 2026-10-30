import { useState } from 'react';
import { Plus, Trash2, X } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Textarea } from '@/components/ui/textarea';
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { notify, apiMessage } from '@/lib/notify';
import { tx } from '@/lib/i18n/tx';
import { useProductOptions } from '@/features/manufacturing/options';
import {
  useCreateMixDesign, useUpdateMixDesign, type MixDesignDetail,
} from '@/features/manufacturing/api-precast';
import { CuringAreaSelect, Field, LocationPicker, OptionalSelect, ProductPicker } from './precast-ui';
import { nonNegativeOrEmpty, optNum, positive } from './precast-format';

interface LineRow { key: string; productId: string; qtyPerBatch: string; scrapPct: string }
const s = (v: unknown) => (v === null || v === undefined ? '' : String(v));
const rowKey = () => Math.random().toString(36).slice(2);

/** Create (POST) or edit a draft (PATCH) mix design. Product, routing and locations are fixed after creation. */
export function MixDesignFormDialog({ initial, onClose, onSaved }: {
  initial?: MixDesignDetail;
  onClose: () => void;
  onSaved: (m: MixDesignDetail) => void;
}) {
  const editing = !!initial;
  const products = useProductOptions();
  const productLabel = (id: string) => products.data?.find((p) => p.id === id)?.name ?? id;
  const [name, setName] = useState(s(initial?.name));
  const [productId, setProductId] = useState(s(initial?.productId));
  const [unitsPerBatch, setUnitsPerBatch] = useState(s(initial?.unitsPerBatch));
  const [lines, setLines] = useState<LineRow[]>(
    initial?.lines.length
      ? initial.lines.map((l) => ({ key: l.id, productId: l.componentProductId, qtyPerBatch: s(l.quantity), scrapPct: s(l.scrapPct) }))
      : [{ key: rowKey(), productId: '', qtyPerBatch: '', scrapPct: '' }],
  );
  const [cementProductId, setCementProductId] = useState(s(initial?.cementProductId));
  const [cementPerBag, setCementPerBag] = useState(s(initial?.cementPerBag ?? 50));
  const [batchVolumeM3, setBatchVolumeM3] = useState(s(initial?.batchVolumeM3));
  const [waterCementRatio, setWaterCementRatio] = useState(s(initial?.waterCementRatio));
  const [minCuringDays, setMinCuringDays] = useState(s(initial?.minCuringDays ?? 7));
  const [curingCheckpointCode, setCuringCheckpointCode] = useState(s(initial?.curingCheckpointCode));
  const [defaultCuringAreaId, setDefaultCuringAreaId] = useState(s(initial?.defaultCuringAreaId));
  const [targetMaturity, setTargetMaturity] = useState(s(initial?.targetMaturity));
  const [expectedYieldPct, setExpectedYieldPct] = useState(s(initial?.expectedYieldPct ?? 100));
  const [defaultLocationId, setDefaultLocationId] = useState(s(initial?.defaultLocationId));
  const [defaultOutputLocationId, setDefaultOutputLocationId] = useState(s(initial?.defaultOutputLocationId));
  const [notes, setNotes] = useState(s(initial?.notes));
  const [submitted, setSubmitted] = useState(false);
  const create = useCreateMixDesign();
  const update = useUpdateMixDesign();

  const setLine = (key: string, patch: Partial<LineRow>) => setLines((ls) => ls.map((l) => (l.key === key ? { ...l, ...patch } : l)));
  const filled = lines.filter((l) => l.productId);
  const errors = {
    name: !name.trim() ? tx('Required') : undefined,
    productId: !productId ? tx('Required') : undefined,
    unitsPerBatch: !positive(unitsPerBatch) ? tx('Enter a positive number') : undefined,
    lines: !filled.length
      ? tx('Add at least one material')
      : filled.some((l) => !positive(l.qtyPerBatch) || !nonNegativeOrEmpty(l.scrapPct))
        ? tx('Each material needs a positive quantity per batch')
        : undefined,
    cementPerBag: cementPerBag !== '' && !positive(cementPerBag) ? tx('Enter a positive number') : undefined,
    batchVolumeM3: batchVolumeM3 !== '' && !positive(batchVolumeM3) ? tx('Enter a positive number') : undefined,
    waterCementRatio: waterCementRatio !== '' && !positive(waterCementRatio) ? tx('Enter a positive number') : undefined,
    minCuringDays: !nonNegativeOrEmpty(minCuringDays) ? tx('Enter zero or more') : undefined,
    targetMaturity: targetMaturity !== '' && !positive(targetMaturity) ? tx('Enter a positive number') : undefined,
    expectedYieldPct: expectedYieldPct !== '' && (!positive(expectedYieldPct) || Number(expectedYieldPct) > 100) ? tx('Between 0 and 100') : undefined,
  };
  const valid = Object.values(errors).every((e) => !e);
  const show = (e?: string) => (submitted ? e : undefined);

  const submit = async () => {
    setSubmitted(true);
    if (!valid) return;
    const common = {
      name: name.trim(),
      unitsPerBatch: Number(unitsPerBatch),
      lines: filled.map((l) => ({ productId: l.productId, qtyPerBatch: Number(l.qtyPerBatch), ...(l.scrapPct !== '' ? { scrapPct: Number(l.scrapPct) } : {}) })),
      cementProductId: cementProductId && filled.some((l) => l.productId === cementProductId) ? cementProductId : undefined,
      cementPerBag: optNum(cementPerBag),
      batchVolumeM3: optNum(batchVolumeM3),
      waterCementRatio: optNum(waterCementRatio),
      minCuringDays: optNum(minCuringDays),
      curingCheckpointCode: curingCheckpointCode.trim() || undefined,
      defaultCuringAreaId: defaultCuringAreaId || undefined,
      targetMaturity: optNum(targetMaturity),
      expectedYieldPct: optNum(expectedYieldPct),
      notes: notes.trim() || undefined,
    };
    try {
      const out = editing
        ? await update.mutateAsync({ id: initial.id, body: common })
        : await create.mutateAsync({
          ...common, productId, defaultLocationId: defaultLocationId || undefined, defaultOutputLocationId: defaultOutputLocationId || undefined,
        });
      notify.success(editing ? tx('Mix design saved') : tx('Mix design created as a draft'));
      onSaved(out);
      onClose();
    } catch (err) {
      notify.error(apiMessage(err, tx('Could not save the mix design')));
    }
  };

  return (
    <Dialog open onOpenChange={(o) => !o && onClose()}>
      <DialogContent className="max-h-[90vh] overflow-y-auto sm:max-w-3xl">
        <DialogHeader>
          <DialogTitle>{editing ? tx('Edit mix design') : tx('New mix design')}</DialogTitle>
          <DialogDescription>{tx('Quantities are per batch (one mixer load). Activate the draft to produce against it.')}</DialogDescription>
        </DialogHeader>
        <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
          <Field label={tx('Name *')} error={show(errors.name)}><Input value={name} onChange={(e) => setName(e.target.value)} /></Field>
          <Field label={tx('Block product *')} error={show(errors.productId)}>
            {editing ? <Input value={initial.productName ?? ''} disabled /> : <ProductPicker value={productId} onChange={setProductId} />}
          </Field>
          <Field label={tx('Blocks per batch *')} error={show(errors.unitsPerBatch)}><Input inputMode="decimal" value={unitsPerBatch} onChange={(e) => setUnitsPerBatch(e.target.value)} /></Field>
          <Field label={tx('Expected yield %')} error={show(errors.expectedYieldPct)}><Input inputMode="decimal" value={expectedYieldPct} onChange={(e) => setExpectedYieldPct(e.target.value)} /></Field>
        </div>

        <div className="space-y-2">
          <div className="flex items-center justify-between">
            <p className="text-sm font-medium">{tx('Materials per batch')}</p>
            <Button size="sm" variant="outline" onClick={() => setLines((ls) => [...ls, { key: rowKey(), productId: '', qtyPerBatch: '', scrapPct: '' }])}>
              <Plus className="mr-1 h-3 w-3" /> {tx('Add')}
            </Button>
          </div>
          {lines.map((l) => (
            <div key={l.key} className="grid grid-cols-[1fr_6rem_4.5rem_auto] gap-2 sm:grid-cols-[1fr_8rem_6rem_auto]">
              <ProductPicker value={l.productId} onChange={(v) => setLine(l.key, { productId: v })} placeholder={tx('Material…')} />
              <Input inputMode="decimal" placeholder={tx('Qty per batch')} aria-label={tx('Qty per batch')} value={l.qtyPerBatch} onChange={(e) => setLine(l.key, { qtyPerBatch: e.target.value })} />
              <Input inputMode="decimal" placeholder={tx('Scrap %')} aria-label={tx('Scrap %')} value={l.scrapPct} onChange={(e) => setLine(l.key, { scrapPct: e.target.value })} />
              <Button size="icon" variant="ghost" aria-label={tx('Remove')} onClick={() => setLines((ls) => ls.filter((x) => x.key !== l.key))}><Trash2 className="h-4 w-4" /></Button>
            </div>
          ))}
          {show(errors.lines) && <p className="text-xs text-destructive">{errors.lines}</p>}
        </div>

        <div className="grid grid-cols-1 gap-3 sm:grid-cols-3">
          <Field label={tx('Cement material')}>
            <OptionalSelect value={cementProductId} onChange={setCementProductId} placeholder={tx('Choose a material')} noneLabel={tx('None')}
              options={filled.map((l) => ({ value: l.productId, label: productLabel(l.productId) }))} />
          </Field>
          <Field label={tx('Cement per bag')} error={show(errors.cementPerBag)}><Input inputMode="decimal" value={cementPerBag} onChange={(e) => setCementPerBag(e.target.value)} /></Field>
          <Field label={tx('Batch volume (m³)')} error={show(errors.batchVolumeM3)}><Input inputMode="decimal" value={batchVolumeM3} onChange={(e) => setBatchVolumeM3(e.target.value)} /></Field>
          <Field label={tx('Water/cement ratio')} error={show(errors.waterCementRatio)}><Input inputMode="decimal" value={waterCementRatio} onChange={(e) => setWaterCementRatio(e.target.value)} /></Field>
          <Field label={tx('Minimum curing days')} error={show(errors.minCuringDays)}><Input inputMode="numeric" value={minCuringDays} onChange={(e) => setMinCuringDays(e.target.value)} /></Field>
          <Field label={tx('Target maturity (°C·h)')} error={show(errors.targetMaturity)}><Input inputMode="decimal" value={targetMaturity} onChange={(e) => setTargetMaturity(e.target.value)} /></Field>
          <Field label={tx('Curing QC checkpoint')}><Input value={curingCheckpointCode} onChange={(e) => setCuringCheckpointCode(e.target.value)} /></Field>
          <Field label={tx('Default curing area')}><CuringAreaSelect value={defaultCuringAreaId} onChange={setDefaultCuringAreaId} /></Field>
        </div>

        {!editing && (
          <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
            <Field label={tx('Default production location')}><ClearableLocation value={defaultLocationId} onChange={setDefaultLocationId} /></Field>
            <Field label={tx('Default output location')}><ClearableLocation value={defaultOutputLocationId} onChange={setDefaultOutputLocationId} /></Field>
          </div>
        )}
        <Field label={tx('Notes')}><Textarea rows={2} value={notes} onChange={(e) => setNotes(e.target.value)} /></Field>

        <DialogFooter>
          <Button variant="outline" onClick={onClose}>{tx('Cancel')}</Button>
          <Button onClick={submit} disabled={create.isPending || update.isPending || (submitted && !valid)}>{editing ? tx('Save') : tx('Create draft')}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

export function ClearableLocation({ value, onChange }: { value: string; onChange: (v: string) => void }) {
  return (
    <div className="flex gap-1">
      <div className="min-w-0 flex-1"><LocationPicker value={value} onChange={onChange} /></div>
      {value && <Button size="icon" variant="ghost" aria-label={tx('Clear')} onClick={() => onChange('')}><X className="h-4 w-4" /></Button>}
    </div>
  );
}

/**
 * Product page (Attributes & Variants tab): how the product is sold by size (ADR-S066), and the size / description
 * of one variant. Every button is type="button": the tab lives inside the product form.
 */
import { useEffect, useState } from 'react';
import { Ruler } from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Textarea } from '@/components/ui/textarea';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { notify, apiMessage } from '@/lib/notify';
import { tx } from '@/lib/i18n/tx';
import { useAuthStore } from '@/stores/auth.store';
import {
  SIZING_MODE_LABEL, SIZING_QTY_UNIT, sizingDims, useProductSizing, useSaveProductSizing,
  type ProductSizing, type SizingMode, type SizingUnit,
} from './sizing';
import { useUpdateVariant, type ProductVariantRow } from './variants-api';

const str = (v: unknown) => (v === null || v === undefined ? '' : String(Number(v)));
const numOrNull = (v: string) => (v.trim() === '' ? null : Number(v));
const DIM_LABEL = { length: tx('Length'), width: tx('Width'), height: tx('Height / thickness') } as const;

/** "Sold by size" settings: mode, unit and the default size offered on new lines. */
export function SizingCard({ productId }: { productId: string }) {
  const { data } = useProductSizing(productId);
  const save = useSaveProductSizing(productId);
  const canEdit = useAuthStore((s) => s.hasPermission(PERMISSIONS.product.update));
  const [form, setForm] = useState({ mode: '' as SizingMode | '', unit: 'm' as SizingUnit, length: '', width: '', height: '' });
  useEffect(() => {
    if (data) {
      setForm({ mode: data.sizingMode ?? '', unit: data.sizingUnit, length: str(data.defaultLength),
        width: str(data.defaultWidth), height: str(data.defaultHeight) });
    }
  }, [data]);
  if (!data) return null;
  const mode = form.mode || null;
  const dirty = (data.sizingMode ?? '') !== form.mode || data.sizingUnit !== form.unit
    || str(data.defaultLength) !== form.length || str(data.defaultWidth) !== form.width
    || str(data.defaultHeight) !== form.height;
  const onSave = async () => {
    try {
      await save.mutateAsync({
        sizingMode: mode, sizingUnit: form.unit, defaultLength: numOrNull(form.length),
        defaultWidth: numOrNull(form.width), defaultHeight: numOrNull(form.height),
      });
      notify.success(tx('Sizing saved'));
    } catch (err) {
      notify.error(apiMessage(err, tx('Save failed')));
    }
  };
  return (
    <Card>
      <CardHeader className="pb-3 flex flex-row items-center justify-between space-y-0">
        <CardTitle className="text-base flex items-center gap-2"><Ruler className="h-4 w-4" />{tx('Sold by size')}</CardTitle>
        {canEdit && dirty && (
          <Button type="button" size="sm" disabled={save.isPending} onClick={() => void onSave()}>{tx('Save sizing')}</Button>
        )}
      </CardHeader>
      <CardContent className="space-y-3">
        <div className="grid gap-3 sm:grid-cols-5">
          <div className="space-y-1 sm:col-span-2">
            <Label>{tx('Sell')}</Label>
            <select className="h-9 w-full rounded-md border bg-background px-2 text-sm" disabled={!canEdit} value={form.mode}
              onChange={(e) => setForm({ ...form, mode: e.target.value as SizingMode | '' })}>
              <option value="">{tx('By quantity (no size)')}</option>
              {(Object.keys(SIZING_MODE_LABEL) as SizingMode[]).map((m) => <option key={m} value={m}>{SIZING_MODE_LABEL[m]}</option>)}
            </select>
          </div>
          <div className="space-y-1">
            <Label>{tx('Size typed in')}</Label>
            <select className="h-9 w-full rounded-md border bg-background px-2 text-sm" disabled={!canEdit || !mode} value={form.unit}
              onChange={(e) => setForm({ ...form, unit: e.target.value as SizingUnit })}>
              <option value="m">{tx('metres (m)')}</option>
              <option value="cm">{tx('centimetres (cm)')}</option>
              <option value="mm">{tx('millimetres (mm)')}</option>
            </select>
          </div>
          {mode && sizingDims(mode).map((d) => (
            <div key={d} className="space-y-1">
              <Label>{tx('Default')} {DIM_LABEL[d].toLowerCase()} ({form.unit})</Label>
              <Input type="number" min={0} step="any" disabled={!canEdit} value={form[d]}
                onChange={(e) => setForm({ ...form, [d]: e.target.value })} />
            </div>
          ))}
        </div>
        <p className="text-xs text-muted-foreground">
          {mode
            ? tx('Lines take pieces × {{dims}} and the quantity is computed in {{unit}}: price the product per {{unit}} and give it a {{unit}} unit of measure.', {
              dims: sizingDims(mode).map((d) => DIM_LABEL[d].toLowerCase()).join(' × '), unit: SIZING_QTY_UNIT[mode] })
            : tx('Choose a sizing to sell this product by the metre, square metre or cubic metre (e.g. granite 4 pcs × 3 m = 12 m). Each variant can carry its own standard size.')}
          {data.uomCode ? ` ${tx('Current unit:')} ${data.uomCode}.` : ''}
        </p>
      </CardContent>
    </Card>
  );
}

/** Standard size and sales description of one variant. */
export function VariantDetailsDialog({ productId, variant, sizing, onClose }: {
  productId: string; variant: ProductVariantRow; sizing: ProductSizing | undefined; onClose: () => void;
}) {
  const update = useUpdateVariant(productId);
  const mode = sizing?.sizingMode ?? null;
  const [form, setForm] = useState({
    length: str(variant.length), width: str(variant.width), height: str(variant.height),
    description: variant.description ?? '',
  });
  const onSave = async () => {
    try {
      await update.mutateAsync({ id: variant.id, data: {
        length: numOrNull(form.length), width: numOrNull(form.width), height: numOrNull(form.height),
        description: form.description.trim() || null,
      } });
      notify.success(tx('Variant saved'));
      onClose();
    } catch (err) {
      notify.error(apiMessage(err, tx('Save failed')));
    }
  };
  return (
    <Dialog open onOpenChange={(o) => !o && onClose()}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>{variant.name}</DialogTitle>
          <DialogDescription>{tx('Details offered when this variant is put on a quotation, order, cash sale or invoice.')}</DialogDescription>
        </DialogHeader>
        <div className="space-y-3">
          {mode ? (
            <div className="grid gap-3 sm:grid-cols-3">
              {sizingDims(mode).map((d) => (
                <div key={d} className="space-y-1">
                  <Label>{DIM_LABEL[d]} ({sizing?.sizingUnit})</Label>
                  <Input type="number" min={0} step="any" value={form[d]} onChange={(e) => setForm({ ...form, [d]: e.target.value })} />
                </div>
              ))}
            </div>
          ) : (
            <p className="text-xs text-muted-foreground">{tx('Set "Sold by size" on the product to give variants a standard size.')}</p>
          )}
          <div className="space-y-1">
            <Label>{tx('Sales description')}</Label>
            <Textarea rows={3} value={form.description} onChange={(e) => setForm({ ...form, description: e.target.value })}
              placeholder={tx('e.g. Polished black granite step, 3 m × 30 cm × 3 cm')} />
          </div>
        </div>
        <DialogFooter>
          <Button type="button" variant="outline" onClick={onClose}>{tx('Cancel')}</Button>
          <Button type="button" disabled={update.isPending} onClick={() => void onSave()}>{tx('Save')}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

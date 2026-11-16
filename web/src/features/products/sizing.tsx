/**
 * Sell by size (ADR-S066): a product sold by length (m), area (m²) or volume (m³). Each document line records the
 * number of pieces and their length [× width [× height]] in the product's sizing unit; the quantity is
 * pieces × length × width × height in metres (the server recomputes it — this is the live preview).
 */
import { useEffect, useRef } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Input } from '@/components/ui/input';
import { api } from '@/lib/api';
import { tx } from '@/lib/i18n/tx';
import { cn } from '@/lib/utils';
import { useProductVariantOptions } from './variants-api';

export type SizingMode = 'length' | 'area' | 'volume';
export type SizingUnit = 'm' | 'cm' | 'mm';

export interface ProductSizing {
  productId: string;
  sizingMode: SizingMode | null;
  sizingUnit: SizingUnit;
  defaultLength: string | number | null;
  defaultWidth: string | number | null;
  defaultHeight: string | number | null;
  uomCode: string | null;
}

/** The size typed on a document line ('' = empty input). */
export interface LineSize {
  pieces: number | '';
  length: number | '';
  width: number | '';
  height: number | '';
}

export const EMPTY_SIZE: LineSize = { pieces: '', length: '', width: '', height: '' };

export const SIZING_MODE_LABEL: Record<SizingMode, string> = {
  length: tx('By length (m)'), area: tx('By area (m²)'), volume: tx('By volume (m³)'),
};
export const SIZING_QTY_UNIT: Record<SizingMode, string> = { length: 'm', area: 'm²', volume: 'm³' };

const FACTOR: Record<SizingUnit, number> = { m: 1, cm: 0.01, mm: 0.001 };

const KEY = ['product-sizing'];

export function useProductSizing(productId: string | null | undefined) {
  return useQuery({
    queryKey: [...KEY, productId],
    enabled: !!productId,
    staleTime: 60_000,
    queryFn: async () => (await api.get<ProductSizing>(`/products/${productId}/sizing`)).data,
  });
}

export function useSaveProductSizing(productId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (data: Partial<Omit<ProductSizing, 'productId' | 'uomCode'>>) =>
      (await api.put<ProductSizing>(`/products/${productId}/sizing`, data)).data,
    onSuccess: (d) => qc.setQueryData([...KEY, productId], d),
  });
}

const n = (v: unknown) => (v === '' || v === null || v === undefined ? null : Number(v));

/** Dimensions the mode uses. */
export function sizingDims(mode: SizingMode): Array<'length' | 'width' | 'height'> {
  return mode === 'length' ? ['length'] : mode === 'area' ? ['length', 'width'] : ['length', 'width', 'height'];
}

/** Quantity of a sized line (m / m² / m³), or null while incomplete. Mirrors ProductSizingService#compute. */
export function sizedQuantity(mode: SizingMode | null | undefined, unit: SizingUnit | undefined, s: LineSize): number | null {
  if (!mode) return null;
  const pieces = n(s.pieces) ?? 1;
  if (pieces <= 0) return null;
  let q = pieces;
  for (const d of sizingDims(mode)) {
    const v = n(s[d]);
    if (v === null || v <= 0) return null;
    q *= v * FACTOR[unit ?? 'm'];
  }
  return Math.round(q * 1e6) / 1e6;
}

/** True when the line carries a size (any field typed). */
export function hasSize(s: Partial<LineSize> | null | undefined): boolean {
  return !!s && [s.pieces, s.length, s.width, s.height].some((v) => v !== '' && v !== null && v !== undefined);
}

/** Request fields of a sized line (empty for a plain line); `mode` drops the dimensions it does not use. */
export function sizePayload(s: LineSize, mode: SizingMode | null | undefined): Record<string, number> {
  if (!mode || !hasSize(s)) return {};
  const out: Record<string, number> = { pieces: n(s.pieces) ?? 1 };
  for (const d of sizingDims(mode)) {
    const v = n(s[d]);
    if (v !== null) out[d] = v;
  }
  return out;
}

/** Size of a saved line, from the API row. */
export function sizeOf(row: { pieces?: unknown; length?: unknown; width?: unknown; height?: unknown } | null | undefined): LineSize {
  const f = (v: unknown): number | '' => (v === null || v === undefined || v === '' ? '' : Number(v));
  return row ? { pieces: f(row.pieces), length: f(row.length), width: f(row.width), height: f(row.height) } : EMPTY_SIZE;
}

/** Printable size: "4 pcs × 3 m", "2 pcs × 240 × 120 cm"; null for a plain line. */
export function sizeLabel(row: { pieces?: unknown; length?: unknown; width?: unknown; height?: unknown; sizingUnit?: unknown } | null | undefined,
  unit?: string | null): string | null {
  if (!row || row.pieces === null || row.pieces === undefined || row.length === null || row.length === undefined) return null;
  const fmt = (v: unknown) => String(Number(v));
  const dims = [row.length, row.width, row.height].filter((v) => v !== null && v !== undefined && v !== '').map(fmt);
  return `${fmt(row.pieces)} ${tx('pcs')} × ${dims.join(' × ')} ${unit ?? (row.sizingUnit as string | undefined) ?? 'm'}`;
}

interface LineSizeFieldsProps {
  productId: string | null | undefined;
  /** The line's variant: its standard size is offered when it changes. */
  variantId?: string | null;
  value: LineSize;
  /** New size and the quantity it gives (null while incomplete). */
  onChange: (size: LineSize, quantity: number | null) => void;
  disabled?: boolean;
  className?: string;
}

/**
 * Pieces × length [× width [× height]] inputs for a document line. Renders nothing for a product not sold by size.
 * A new line is prefilled with the variant's standard size, else the product defaults; changing the variant offers its
 * size again.
 */
export function LineSizeFields({ productId, variantId, value, onChange, disabled, className }: LineSizeFieldsProps) {
  const { data: cfg } = useProductSizing(productId);
  const mode = cfg?.sizingMode ?? null;
  const { data: variants = [], isFetched } = useProductVariantOptions(productId ?? undefined, !disabled && !!mode);
  const lastProduct = useRef<string | null | undefined>(undefined);
  const lastVariant = useRef<string | null | undefined>(undefined);

  useEffect(() => {
    if (!mode || disabled || !cfg) return;
    if (variantId && !isFetched) return; // wait for the variant's standard size
    const variant = variantId ? variants.find((v) => v.id === variantId) : undefined;
    const productChanged = lastProduct.current !== productId;
    const variantChanged = lastVariant.current !== variantId;
    lastProduct.current = productId;
    lastVariant.current = variantId;
    const std = (d: 'length' | 'width' | 'height') => variant?.[d];
    const variantHasSize = !!variant && (['length', 'width', 'height'] as const).some((d) => std(d) != null);
    // a saved line keeps its size; a new line takes the variant's size, else the product defaults
    if (productChanged ? hasSize(value) : !(variantChanged && variantHasSize)) return;
    const pick = (d: 'length' | 'width' | 'height', def: unknown, current: number | ''): number | '' => {
      const v = std(d) ?? def;
      return v === null || v === undefined || v === '' ? current : Number(v);
    };
    const defaults = productChanged;
    const next: LineSize = {
      pieces: value.pieces === '' ? 1 : value.pieces,
      length: pick('length', defaults ? cfg.defaultLength : null, value.length),
      width: mode === 'length' ? '' : pick('width', defaults ? cfg.defaultWidth : null, value.width),
      height: mode === 'volume' ? pick('height', defaults ? cfg.defaultHeight : null, value.height) : '',
    };
    onChange(next, sizedQuantity(mode, cfg.sizingUnit, next));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [mode, cfg, productId, variantId, variants, isFetched, disabled]);

  if (!mode || !cfg) return null;
  const unit = cfg.sizingUnit;
  const qty = sizedQuantity(mode, unit, value);
  if (disabled) {
    const label = sizeLabel(value, unit);
    return label ? <span className={cn('mt-0.5 block text-xs text-muted-foreground', className)}>{label}</span> : null;
  }
  const set = (k: keyof LineSize, raw: string) => {
    const next = { ...value, [k]: raw === '' ? '' : Number(raw) } as LineSize;
    onChange(next, sizedQuantity(mode, unit, next));
  };
  const box = (k: keyof LineSize, label: string) => (
    <label className="flex items-center gap-1">
      <span className="text-[10px] uppercase text-muted-foreground">{label}</span>
      <Input type="number" min={0} step="any" value={value[k]} onChange={(e) => set(k, e.target.value)}
        className={cn('h-7 w-16 px-1.5 text-xs', value[k] === '' && k !== 'pieces' && 'border-amber-500')} />
    </label>
  );
  return (
    <div className={cn('mt-1 flex flex-wrap items-center gap-1.5 text-xs', className)}>
      {box('pieces', tx('Pcs'))}
      <span className="text-muted-foreground">×</span>
      {box('length', tx('L'))}
      {mode !== 'length' && <><span className="text-muted-foreground">×</span>{box('width', tx('W'))}</>}
      {mode === 'volume' && <><span className="text-muted-foreground">×</span>{box('height', tx('H'))}</>}
      <span className="text-muted-foreground">{unit}</span>
      <span className="ml-1 tabular-nums font-medium">
        = {qty === null ? '—' : qty.toLocaleString(undefined, { maximumFractionDigits: 4 })} {SIZING_QTY_UNIT[mode]}
      </span>
    </div>
  );
}

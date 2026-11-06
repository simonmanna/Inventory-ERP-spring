/**
 * Variant picker for a document line (ADR-S058). Renders nothing for a product without variants; for a product with
 * variants it shows a compact select, highlighted until a variant is chosen (the server refuses the line without one).
 */
import { useEffect } from 'react';
import {
  Select, SelectContent, SelectItem, SelectTrigger, SelectValue,
} from '@/components/ui/select';
import { cn } from '@/lib/utils';
import { tx } from '@/lib/i18n/tx';
import { useProductVariantOptions, type VariantOption } from './variants-api';

interface VariantSelectProps {
  productId: string | null | undefined;
  value: string | null | undefined;
  onChange: (variantId: string | null, variant: VariantOption | null) => void;
  disabled?: boolean;
  className?: string;
  /** Read-only label shown when disabled (e.g. the saved line's variant name). */
  label?: string | null;
}

export function VariantSelect({ productId, value, onChange, disabled, className, label }: VariantSelectProps) {
  const { data: options = [] } = useProductVariantOptions(productId ?? undefined, !disabled);

  // A variant of another product (product changed on the line) is cleared.
  useEffect(() => {
    if (value && options.length > 0 && !options.some((o) => o.id === value)) onChange(null, null);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [productId, options]);

  if (disabled) {
    return label ? <span className="mt-0.5 block text-xs text-muted-foreground">{label}</span> : null;
  }
  if (!productId || options.length === 0) return null;
  return (
    <Select value={value ?? ''} onValueChange={(v) => onChange(v, options.find((o) => o.id === v) ?? null)}>
      <SelectTrigger className={cn('mt-1 h-7 text-xs', !value && 'border-amber-500 text-amber-700', className)}>
        <SelectValue placeholder={tx('Choose variant…')} />
      </SelectTrigger>
      <SelectContent className="max-h-72">
        {options.map((o) => (
          <SelectItem key={o.id} value={o.id}>
            {o.name}
            {o.sku ? <span className="ml-1 text-muted-foreground">· {o.sku}</span> : null}
          </SelectItem>
        ))}
      </SelectContent>
    </Select>
  );
}

/**
 * Variant picker for a document line (ADR-S058). Renders nothing for a product without variants; for a product with
 * variants it shows a compact select, highlighted until a variant is chosen (the server refuses the line without one).
 * A saved variant archived since stays selected as a read-only "(Archived)" entry instead of being cleared; new choices
 * are active variants only.
 */
import { useEffect } from 'react';
import {
  Select, SelectContent, SelectItem, SelectTrigger, SelectValue,
} from '@/components/ui/select';
import { cn } from '@/lib/utils';
import { money } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';
import { useProductVariantOptions, useVariantConfig, type VariantOption } from './variants-api';

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
  const { data: options = [], isSuccess } = useProductVariantOptions(productId ?? undefined, !disabled);
  // The saved variant is not among the active choices: either archived since (kept, shown read-only) or a variant of
  // another product (product changed on the line, cleared). Only then are the product's archived variants loaded.
  const missing = !disabled && !!productId && !!value && isSuccess && !options.some((o) => o.id === value);
  const { data: cfg, isSuccess: cfgLoaded } = useVariantConfig(missing ? productId ?? undefined : undefined);
  const archived = missing ? cfg?.variants.find((v) => v.id === value) : undefined;

  useEffect(() => {
    if (missing && cfgLoaded && !archived) onChange(null, null);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [missing, cfgLoaded, archived]);

  if (disabled) {
    return label ? <span className="mt-0.5 block text-xs text-muted-foreground">{label}</span> : null;
  }
  if (!productId || (options.length === 0 && !archived)) return null;
  return (
    <Select value={value ?? ''} onValueChange={(v) => onChange(v, options.find((o) => o.id === v) ?? null)}>
      <SelectTrigger className={cn('mt-1 h-7 text-xs', (!value || archived) && 'border-amber-500 text-amber-700', className)}>
        <SelectValue placeholder={tx('Choose variant…')} />
      </SelectTrigger>
      <SelectContent className="max-h-72">
        {archived && (
          <SelectItem key={archived.id} value={archived.id} disabled>
            {archived.name}
            {archived.sku ? <span className="ml-1 text-muted-foreground">· {archived.sku}</span> : null}
            <span className="ml-1 text-muted-foreground">({tx('Archived')})</span>
          </SelectItem>
        )}
        {options.map((o) => (
          <SelectItem key={o.id} value={o.id}>
            {o.name}
            {o.sku ? <span className="ml-1 text-muted-foreground">· {o.sku}</span> : null}
            <span className="ml-1 text-muted-foreground tabular-nums">· {money(o.salesPrice)}</span>
          </SelectItem>
        ))}
      </SelectContent>
    </Select>
  );
}

/**
 * Optional variant for a pricing rule (audit P-1, V488): pricelist items, promotions and agreement lines. Empty means
 * the rule covers every variant of the product; a chosen variant narrows it to that variant (and beats the same tier's
 * whole-product rule). Renders nothing for a product without variants.
 */
export function RuleVariantSelect({ productId, value, onChange, className, label }: {
  productId: string | null | undefined;
  value: string | null | undefined;
  onChange: (variantId: string | null, variant: VariantOption | null) => void;
  className?: string;
  /** Saved variant name, kept visible when the variant is no longer among the active options. */
  label?: string | null;
}) {
  const { data: options = [] } = useProductVariantOptions(productId ?? undefined);
  if (!productId || (options.length === 0 && !value)) return null;
  const savedMissing = !!value && !options.some((o) => o.id === value);
  return (
    <select
      aria-label={tx('Variant')}
      className={cn('h-9 w-full rounded-md border bg-background px-2 text-sm', className)}
      value={value ?? ''}
      onChange={(e) => onChange(e.target.value || null, options.find((o) => o.id === e.target.value) ?? null)}
    >
      <option value="">{tx('All variants')}</option>
      {savedMissing && <option value={value!}>{label ?? value} ({tx('Archived')})</option>}
      {options.map((o) => (
        <option key={o.id} value={o.id}>{o.name}{o.sku ? ` · ${o.sku}` : ''} · {money(o.salesPrice)}</option>
      ))}
    </select>
  );
}

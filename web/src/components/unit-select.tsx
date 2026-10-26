import { tx } from '@/lib/i18n/tx';
import {
  unitHint, unitOptions, type UnitAwareProduct, type UnitRole,
} from '@/features/products/units';

interface UnitSelectProps {
  product: UnitAwareProduct | undefined;
  role: UnitRole;
  value: string | undefined;
  onChange: (uomId: string) => void;
  className?: string;
  disabled?: boolean;
}

/**
 * Unit picker for a document line (ADR-S003): the product's base unit plus every unit it is bought (or sold) in.
 * Renders the single unit as plain text when there is nothing to choose.
 */
export function UnitSelect({ product, role, value, onChange, className, disabled }: UnitSelectProps) {
  const options = unitOptions(product, role);
  if (options.length === 0) {
    return <span className="text-xs text-muted-foreground">—</span>;
  }
  if (options.length === 1) {
    return <span className="text-sm">{options[0].code}</span>;
  }
  const current = value ?? options[0].uomId;
  return (
    <select
      aria-label={tx('Unit')}
      title={unitHint(product, current)}
      className={className ?? 'w-full rounded border bg-background px-2 py-1 text-sm'}
      value={current}
      disabled={disabled}
      onChange={(e) => onChange(e.target.value)}
    >
      {options.map((o) => (
        <option key={o.uomId} value={o.uomId}>
          {o.code}{o.factor !== null && o.factor !== 1 ? ` (${Number(o.factor.toFixed(6))})` : ''}
        </option>
      ))}
    </select>
  );
}

import { tx } from '@/lib/i18n/tx';
import { cn } from '@/lib/utils';
import type { Tax } from '@/features/accounting/api';

/**
 * Several taxes on a document line (ADR-S071): toggle chips, in the order picked. An empty selection leaves the line
 * on its product's default tax. `kind` limits the choice: sales lines cannot carry withholding or reverse charge.
 */
export function TaxPicker({
  taxes,
  value,
  onChange,
  kind,
}: {
  taxes: Tax[];
  value: string[];
  onChange: (ids: string[]) => void;
  kind: 'sales' | 'purchase';
}) {
  const choices = taxes.filter(
    (t) => t.isActive && t.type !== 'withholding' && (kind === 'purchase' || !t.reverseCharge),
  );
  if (choices.length === 0) return null;
  return (
    <div className="flex flex-wrap items-center gap-1">
      <span className="text-xs text-muted-foreground">{tx('Taxes')}:</span>
      {choices.map((t) => {
        const on = value.includes(t.id);
        return (
          <button
            key={t.id}
            type="button"
            onClick={() => onChange(on ? value.filter((v) => v !== t.id) : [...value, t.id])}
            className={cn(
              'rounded-full border px-2 py-0.5 text-xs transition-colors',
              on ? 'border-indigo-600 bg-indigo-50 text-indigo-700' : 'text-muted-foreground hover:bg-slate-50',
            )}
            title={t.isGroup ? (t.children ?? []).map((c) => c.name).join(' + ') : undefined}
          >
            {t.name}
            {t.isGroup ? ` (${tx('group')})` : ` ${Number(t.rate).toFixed(2)}%`}
            {t.reverseCharge ? ` · ${tx('RC')}` : ''}
          </button>
        );
      })}
      {value.length === 0 && <span className="text-xs text-muted-foreground">{tx('(product default)')}</span>}
    </div>
  );
}

/** Withholding tax selector (vendor bill header, ADR-S071). */
export function WithholdingSelect({
  taxes,
  value,
  onChange,
  className,
}: {
  taxes: Tax[];
  value: string;
  onChange: (id: string) => void;
  className?: string;
}) {
  const choices = taxes.filter((t) => t.isActive && t.type === 'withholding');
  return (
    <select className={className} value={value} onChange={(e) => onChange(e.target.value)}>
      <option value="">{tx('No withholding')}</option>
      {choices.map((t) => (
        <option key={t.id} value={t.id}>
          {t.name} ({Number(t.rate).toFixed(2)}%)
        </option>
      ))}
    </select>
  );
}

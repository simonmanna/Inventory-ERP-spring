import { useState } from 'react';
import { SlidersHorizontal } from 'lucide-react';
import { DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuLabel, DropdownMenuTrigger } from '@/components/ui/dropdown-menu';
import { tx } from '@/lib/i18n/tx';

/** One optional column of a document lines table: `[key, label]`. */
export type LineColumn<K extends string> = readonly [K, string];

/**
 * Odoo-style optional columns of a document lines table, remembered per table in the browser.
 * Returns the `show(key)` test, the `toggle(key)` action and the current set.
 */
export function useLineColumns<K extends string>(storeKey: string, columns: ReadonlyArray<LineColumn<K>>, defaults: K[]) {
  const [cols, setCols] = useState<Set<K>>(() => {
    try {
      const raw = localStorage.getItem(storeKey);
      if (raw) return new Set((JSON.parse(raw) as K[]).filter((c) => columns.some(([k]) => k === c)));
    } catch { /* storage unavailable */ }
    return new Set(defaults);
  });
  const toggle = (c: K) => setCols((prev) => {
    const next = new Set(prev);
    if (next.has(c)) next.delete(c); else next.add(c);
    try { localStorage.setItem(storeKey, JSON.stringify([...next])); } catch { /* storage unavailable */ }
    return next;
  });
  return { cols, show: (c: K) => cols.has(c), toggle };
}

/** The sliders button at the end of a lines table header that opens the optional-columns checklist. */
export function LineColumnsPicker<K extends string>({ columns, cols, toggle }: {
  columns: ReadonlyArray<LineColumn<K>>; cols: Set<K>; toggle: (c: K) => void;
}) {
  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <button type="button" className="rounded border p-1 text-muted-foreground hover:text-foreground" aria-label={tx('Optional columns')}>
          <SlidersHorizontal className="h-4 w-4" />
        </button>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="end" className="min-w-[180px]">
        <DropdownMenuLabel className="text-xs normal-case tracking-normal text-muted-foreground">{tx('Optional columns')}</DropdownMenuLabel>
        {columns.map(([c, label]) => (
          <DropdownMenuItem key={c} onSelect={(e) => { e.preventDefault(); toggle(c); }} className="gap-2 normal-case tracking-normal">
            <input type="checkbox" readOnly checked={cols.has(c)} className="h-4 w-4 accent-primary" tabIndex={-1} />
            {label}
          </DropdownMenuItem>
        ))}
      </DropdownMenuContent>
    </DropdownMenu>
  );
}

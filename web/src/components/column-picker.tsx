import { useCallback, useMemo, useState } from 'react';
import { SlidersHorizontal } from 'lucide-react';
import { Button } from '@/components/ui/button';
import {
  DropdownMenu, DropdownMenuCheckboxItem, DropdownMenuContent, DropdownMenuItem, DropdownMenuLabel, DropdownMenuSeparator,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu';
import { tx } from '@/lib/i18n/tx';

/** One optional table column. {@code locked} columns are always shown and not offered in the picker. */
export interface ColumnDef<K extends string = string> { key: K; label: string; defaultVisible?: boolean; locked?: boolean }

export interface ColumnState<K extends string = string> {
  defs: ColumnDef<K>[]; shown: (key: K) => boolean; toggle: (key: K, on: boolean) => void; reset: () => void; count: number;
}

const read = (storageKey: string): Record<string, boolean> | null => {
  try {
    const raw = window.localStorage.getItem(`cols:${storageKey}`);
    return raw ? (JSON.parse(raw) as Record<string, boolean>) : null;
  } catch { return null; }
};

const write = (storageKey: string, v: Record<string, boolean> | null) => {
  try {
    if (v) window.localStorage.setItem(`cols:${storageKey}`, JSON.stringify(v));
    else window.localStorage.removeItem(`cols:${storageKey}`);
  } catch { /* storage unavailable: the choice lasts for this view only */ }
};

/**
 * Which optional columns of a table are visible. The choice is remembered per browser under {@code storageKey}; a
 * column the user never touched follows its {@code defaultVisible}.
 */
export function useColumns<K extends string>(storageKey: string, defs: ColumnDef<K>[]): ColumnState<K> {
  const [prefs, setPrefs] = useState<Record<string, boolean>>(() => read(storageKey) ?? {});
  const byKey = useMemo(() => new Map(defs.map((d) => [d.key, d])), [defs]);
  const shown = useCallback((key: K) => {
    const d = byKey.get(key);
    if (!d) return false;
    if (d.locked) return true;
    return prefs[key] ?? d.defaultVisible ?? true;
  }, [byKey, prefs]);
  const toggle = useCallback((key: K, on: boolean) => {
    setPrefs((p) => { const next = { ...p, [key]: on }; write(storageKey, next); return next; });
  }, [storageKey]);
  const reset = useCallback(() => { setPrefs({}); write(storageKey, null); }, [storageKey]);
  const count = defs.filter((d) => shown(d.key)).length;
  return { defs, shown, toggle, reset, count };
}

/** The "optional columns" button of a table header (sliders icon) with one checkbox per column. */
export function ColumnPicker<K extends string>({ columns, className }: { columns: ColumnState<K>; className?: string }) {
  const optional = columns.defs.filter((d) => !d.locked);
  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <Button type="button" size="icon" variant="ghost" className={className ?? 'h-7 w-7'} aria-label={tx('Choose columns')} title={tx('Choose columns')}>
          <SlidersHorizontal className="h-4 w-4" />
        </Button>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="end" className="w-56">
        <DropdownMenuLabel className="text-xs">{tx('Columns')}</DropdownMenuLabel>
        <DropdownMenuSeparator />
        {optional.map((d) => (
          <DropdownMenuCheckboxItem key={d.key} checked={columns.shown(d.key)} onSelect={(e) => e.preventDefault()}
            onCheckedChange={(v) => columns.toggle(d.key, !!v)}>
            {d.label}
          </DropdownMenuCheckboxItem>
        ))}
        <DropdownMenuSeparator />
        <DropdownMenuItem className="text-xs text-muted-foreground" onSelect={() => columns.reset()}>{tx('Reset to default')}</DropdownMenuItem>
      </DropdownMenuContent>
    </DropdownMenu>
  );
}

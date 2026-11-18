import { useEffect, useRef, useState } from 'react';
import { useSupplierOptions, type SupplierOption } from '@/features/procurement/api';
import { useDebouncedValue } from '@/lib/use-debounced-value';
import { tx } from '@/lib/i18n/tx';

/** The most suppliers `GET /procurement/suppliers/options` returns for one search (sorted by name). */
const SUPPLIER_OPTIONS_LIMIT = 50;

/**
 * Props for a remote `SearchableSelect` over the supplier options, searched on the server as the user types. Without
 * a search the route returns only the first 50 suppliers by name, which used to be the whole dropdown; a full page
 * now says to keep typing.
 */
export function useSupplierPicker() {
  const [query, setQuery] = useState('');
  const search = useDebouncedValue(query.trim(), 250);
  const suppliers = useSupplierOptions(search);
  const rows = suppliers.data ?? [];
  const known = useRef(new Map<string, SupplierOption>());
  useEffect(() => {
    for (const s of suppliers.data ?? []) known.current.set(s.id, s);
  }, [suppliers.data]);

  return {
    options: rows.map((s) => ({ value: s.id, label: s.name, code: s.code ?? undefined })),
    onQueryChange: setQuery,
    loading: suppliers.isFetching,
    footer: rows.length >= SUPPLIER_OPTIONS_LIMIT
      ? tx('Showing the first {{n}} matches: keep typing to narrow the list.', { n: SUPPLIER_OPTIONS_LIMIT })
      : undefined,
    /** Trigger label for a picked supplier that is not among the current results. */
    labelOf: (id: string | null | undefined): string | null => {
      const s = id ? rows.find((r) => r.id === id) ?? known.current.get(id) : undefined;
      if (!s) return null;
      return s.code ? `${s.code} — ${s.name}` : s.name;
    },
  };
}

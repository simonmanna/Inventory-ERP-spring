import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { useEffect, useRef, useState } from 'react';
import type { SearchableSelectOption } from '@/components/ui/searchable-select';
import { api } from '@/lib/api';
import { tx } from '@/lib/i18n/tx';
import { useDebouncedValue } from '@/lib/use-debounced-value';

interface Page<T> { data: T[]; meta?: { total?: number } }

export interface SearchPickerConfig<T> {
  /** Query-key namespace, e.g. 'products'. */
  key: string;
  /** A list route that takes `search` and `pageSize` and answers `{ data, meta: { total } }`. */
  url: string;
  /** Fixed extra query parameters (filters, sort order). */
  params?: Record<string, string | number | undefined>;
  /** Rows per search (default 25). */
  pageSize?: number;
  toOption: (row: T) => SearchableSelectOption;
  enabled?: boolean;
}

export interface SearchPicker<T> {
  /** Props for a remote `SearchableSelect`: options, onQueryChange, loading and footer. */
  options: SearchableSelectOption[];
  onQueryChange: (query: string) => void;
  loading: boolean;
  /** Set when the server has more matches than it sent: the list is never cut short silently. */
  footer: string | undefined;
  /** A row seen in any search so far, so a picked line keeps its product after the search moves on. */
  get: (id: string | null | undefined) => T | undefined;
  /** Trigger label for a picked row that is not among the current results. */
  labelOf: (id: string | null | undefined) => string | null;
}

/**
 * Server-side search for a picker over a list too long to load up front (products, partners): debounced, at most
 * `pageSize` matches per search, and a footer saying when the server holds more ("keep typing to narrow") instead of
 * silently showing only the first page.
 */
export function useSearchPicker<T extends { id: string }>(cfg: SearchPickerConfig<T>): SearchPicker<T> {
  const pageSize = cfg.pageSize ?? 25;
  const [query, setQuery] = useState('');
  const search = useDebouncedValue(query.trim(), 250);
  const result = useQuery({
    queryKey: ['search-picker', cfg.key, cfg.url, cfg.params ?? {}, search, pageSize],
    enabled: cfg.enabled ?? true,
    placeholderData: keepPreviousData,
    staleTime: 30_000,
    queryFn: async () =>
      (await api.get<Page<T>>(cfg.url, { params: { ...cfg.params, search: search || undefined, pageSize } })).data,
  });
  const rows = result.data?.data;
  const known = useRef(new Map<string, T>());
  useEffect(() => {
    for (const row of rows ?? []) known.current.set(row.id, row);
  }, [rows]);

  const total = result.data?.meta?.total ?? 0;
  const shown = rows?.length ?? 0;
  const get = (id: string | null | undefined) => (id ? rows?.find((r) => r.id === id) ?? known.current.get(id) : undefined);
  return {
    options: (rows ?? []).map(cfg.toOption),
    onQueryChange: setQuery,
    loading: result.isFetching,
    footer: total > shown
      ? tx('Showing {{shown}} of {{total}}: keep typing to narrow the list.', { shown, total })
      : undefined,
    get,
    labelOf: (id) => {
      const row = get(id);
      if (!row) return null;
      const option = cfg.toOption(row);
      return option.code ? `${option.code} — ${option.label}` : option.label;
    },
  };
}

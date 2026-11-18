import { useSearchPicker } from '@/lib/use-search-picker';

/**
 * Products for a document-line picker, searched on the server (code, SKU or name) instead of loading one capped page
 * up front: product #201 used to be impossible to pick. `get(id)` returns the full product row (units, prices, tax)
 * of anything seen in a search, for the line's unit and price helpers.
 */
export function useProductSearch<P extends { id: string; code?: string | null; name: string }>(
  opts: { pageSize?: number; enabled?: boolean } = {},
) {
  return useSearchPicker<P>({
    key: 'products',
    url: '/products',
    params: { sortBy: 'name', sortOrder: 'asc' },
    pageSize: opts.pageSize ?? 25,
    enabled: opts.enabled,
    toOption: (p) => ({ value: p.id, label: p.name, code: p.code ?? undefined }),
  });
}

import { useQuery } from '@tanstack/react-query';
import { api } from '@/lib/api';
import { tx } from '@/lib/i18n/tx';
import type { SearchableSelectOption } from '@/components/ui/searchable-select';

export interface StockLocation { id: string; code: string; name: string; type?: string; isActive?: boolean }

/** The most `/inventory/locations` answers in one page. */
const LIMIT = 1000;

/**
 * Every stock location (optionally of one type) for pickers and code look-ups. `/inventory/locations` without a page
 * size answers only the 25 newest, so the oldest location — usually the main warehouse — could not be picked; this
 * asks for the whole list (up to 1,000, sorted by code) and says when even that is capped.
 */
export function useStockLocations(opts: { type?: string; enabled?: boolean } = {}) {
  const query = useQuery({
    queryKey: ['inventory-locations', 'all', opts.type ?? ''],
    enabled: opts.enabled ?? true,
    staleTime: 60_000,
    queryFn: async () => (await api.get<{ data: StockLocation[]; meta?: { total?: number } }>('/inventory/locations', {
      params: { pageSize: LIMIT, type: opts.type || undefined, sortBy: 'code', sortOrder: 'asc' },
    })).data,
  });
  const locations = query.data?.data;
  const capped = (query.data?.meta?.total ?? 0) > (locations?.length ?? 0);
  return {
    locations,
    isLoading: query.isLoading,
    /** Footer for a location picker when the organisation has more locations than one page holds. */
    footer: capped ? tx('Only the first {{n}} locations are listed.', { n: LIMIT.toLocaleString() }) : undefined,
  };
}

export const locationOption = (l: StockLocation): SearchableSelectOption => ({ value: l.id, label: l.name, code: l.code });

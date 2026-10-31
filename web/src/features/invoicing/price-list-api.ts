import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import type { PaginatedResult } from '@erp/shared';
import { api } from '@/lib/api';

export type PriceListTier = 'retail' | 'wholesale' | 'contract' | 'negotiated';

export interface PriceListRow {
  id: string;
  code: string;
  name: string;
  tier: PriceListTier;
  isDefault: boolean;
  branchId: string | null;
  branchName?: string | null;
  validFrom: string | null;
  validTo: string | null;
  priority: number;
  isActive: boolean;
  itemCount?: number;
}

export interface PriceListItem {
  productId?: string | null;
  productName?: string | null;
  productCategoryId?: string | null;
  uomId?: string | null;
  siteId?: string | null;
  zone?: string | null;
  minQuantity?: number;
  unitPrice?: number | null;
  discountPercent?: number | null;
}

export interface PriceListDetail extends PriceListRow {
  notes: string | null;
  items: PriceListItem[];
  partnerIds: string[];
  partnerCategoryIds: string[];
}

export type PriceListInput = Omit<PriceListDetail, 'id' | 'branchName' | 'itemCount'>;

export function usePriceLists(params: { search?: string; tier?: string; page: number; pageSize: number }) {
  return useQuery({
    queryKey: ['price-lists', params],
    queryFn: async () => (await api.get<PaginatedResult<PriceListRow>>('/price-lists', { params })).data,
  });
}

export function usePriceList(id: string | undefined) {
  return useQuery({
    queryKey: ['price-lists', 'detail', id],
    queryFn: async () => (await api.get<PriceListDetail>(`/price-lists/${id}`)).data,
    enabled: !!id,
  });
}

export function useSavePriceList(id: string | undefined) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (input: PriceListInput) => (id
      ? await api.put<PriceListDetail>(`/price-lists/${id}`, input)
      : await api.post<PriceListDetail>('/price-lists', input)).data,
    onSuccess: () => qc.invalidateQueries({ queryKey: ['price-lists'] }),
  });
}

export function useDeletePriceList() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (id: string) => (await api.delete(`/price-lists/${id}`)).data,
    onSuccess: () => qc.invalidateQueries({ queryKey: ['price-lists'] }),
  });
}

/** The list price of one line, or null: no list applies, or the user may not read lists. Never throws. */
export async function resolveListPrice(input: {
  partnerId?: string; productId: string; quantity?: number; uomId?: string; branchId?: string;
}): Promise<{ unitPrice: number | null; discountPercent: number } | null> {
  if (!input.partnerId) return null;
  try {
    const r = (await api.post<{ matched: boolean; unitPrice?: number | null; discountPercent?: number }>(
      '/price-lists/resolve', input)).data;
    return r.matched ? { unitPrice: r.unitPrice ?? null, discountPercent: r.discountPercent ?? 0 } : null;
  } catch {
    return null;
  }
}

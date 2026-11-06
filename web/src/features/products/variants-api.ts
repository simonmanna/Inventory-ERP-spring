/** Product attributes & variants (ADR-S058). */
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from '@/lib/api';

export type DisplayType = 'radio' | 'pills' | 'select' | 'color';

export interface VariantAttributeValue {
  id: string;
  attributeId: string;
  name: string;
  htmlColor: string | null;
  sequence: number;
  isActive: boolean;
}

export interface VariantAttribute {
  id: string;
  name: string;
  displayType: DisplayType;
  sequence: number;
  isActive: boolean;
  productCount: number;
  values: VariantAttributeValue[];
}

export interface AttributeLineValue {
  valueId: string;
  name: string;
  htmlColor: string | null;
  priceExtra: string | number;
}

export interface AttributeLine {
  id: string;
  attributeId: string;
  attributeName: string;
  displayType: DisplayType;
  sequence: number;
  values: AttributeLineValue[];
}

export interface ProductVariantRow {
  id: string;
  productId: string;
  name: string;
  sku: string | null;
  barcode: string | null;
  attributes: Record<string, string>;
  priceExtra: string | number;
  /** Fixed price override (null = product price + extras). */
  fixedPrice: string | number | null;
  fixedCost: string | number | null;
  /** Effective price / cost. */
  salesPrice: string | number;
  costPrice: string | number | null;
  isActive: boolean;
  onHand: string | number;
  stockValue: string | number;
}

export interface VariantConfig {
  productId: string;
  hasVariants: boolean;
  salesPrice: string | number | null;
  costPrice: string | number | null;
  lines: AttributeLine[];
  variants: ProductVariantRow[];
}

/** A variant as offered by line pickers (sales / purchase documents). */
export interface VariantOption {
  id: string;
  productId: string;
  productCode: string;
  productName: string;
  name: string;
  displayName: string;
  sku: string | null;
  barcode: string | null;
  salesPrice: string | number;
  costPrice: string | number | null;
}

const KEY = ['product-variants'];

export function useVariantAttributes() {
  return useQuery({
    queryKey: [...KEY, 'attributes'],
    queryFn: async () => (await api.get<VariantAttribute[]>('/variant-attributes')).data,
  });
}

export function useVariantConfig(productId: string | undefined) {
  return useQuery({
    queryKey: [...KEY, 'config', productId],
    enabled: !!productId,
    queryFn: async () => (await api.get<VariantConfig>(`/products/${productId}/variant-config`)).data,
  });
}

/** Active variants of a product for line pickers; disabled for products without variants. */
export function useProductVariantOptions(productId: string | undefined, enabled = true) {
  return useQuery({
    queryKey: [...KEY, 'options', productId],
    enabled: !!productId && enabled,
    staleTime: 30_000,
    queryFn: async () => (await api.get<VariantOption[]>('/product-variants', { params: { productId } })).data,
  });
}

function useInvalidate() {
  const qc = useQueryClient();
  return () => {
    qc.invalidateQueries({ queryKey: KEY });
    qc.invalidateQueries({ queryKey: ['products'] });
  };
}

export interface AttributeInput {
  name: string;
  displayType?: DisplayType;
  values?: { id?: string; name: string; htmlColor?: string | null; isActive?: boolean }[];
}

export function useSaveVariantAttribute() {
  const invalidate = useInvalidate();
  return useMutation({
    mutationFn: async ({ id, data }: { id?: string; data: Partial<AttributeInput> }) =>
      id
        ? (await api.patch<VariantAttribute>(`/variant-attributes/${id}`, data)).data
        : (await api.post<VariantAttribute>('/variant-attributes', data)).data,
    onSuccess: invalidate,
  });
}

export function useDeleteVariantAttribute() {
  const invalidate = useInvalidate();
  return useMutation({
    mutationFn: async (id: string) => (await api.delete(`/variant-attributes/${id}`)).data,
    onSuccess: invalidate,
  });
}

export function useSaveAttributeLines(productId: string) {
  const invalidate = useInvalidate();
  return useMutation({
    mutationFn: async (lines: { attributeId: string; valueIds: string[] }[]) =>
      (await api.put<VariantConfig>(`/products/${productId}/attribute-lines`, { lines })).data,
    onSuccess: invalidate,
  });
}

export function useSaveLinePriceExtras(productId: string) {
  const invalidate = useInvalidate();
  return useMutation({
    mutationFn: async ({ lineId, values }: { lineId: string; values: { valueId: string; priceExtra: number }[] }) =>
      (await api.patch<VariantConfig>(`/products/${productId}/attribute-lines/${lineId}/values`, { values })).data,
    onSuccess: invalidate,
  });
}

export interface VariantPatch {
  sku?: string | null;
  barcode?: string | null;
  salesPrice?: number | null;
  costPrice?: number | null;
  isActive?: boolean;
}

export function useUpdateVariant(productId: string) {
  const invalidate = useInvalidate();
  return useMutation({
    mutationFn: async ({ id, data }: { id: string; data: VariantPatch }) =>
      (await api.patch<ProductVariantRow>(`/products/${productId}/variants/${id}`, data)).data,
    onSuccess: invalidate,
  });
}

import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import type { PaginatedResult } from '@erp/shared';
import { api } from '@/lib/api';
import type { ProductUnit, UomRef } from './units';

export interface Product {
  id: string;
  code: string;
  sku: string | null;
  name: string;
  productType: string;
  costingMethod: string;
  categoryId: string | null;
  category: { id: string; name: string } | null;
  // UOM roles.
  uomId?: string | null;
  purchaseUomId?: string | null;
  salesUomId?: string | null;
  recipeUomId?: string | null;
  productionUomId?: string | null;
  uomConversion?: string | null;
  uom?: UomRef | null;
  purchaseUom?: UomRef | null;
  salesUom?: UomRef | null;
  /** Extra units with per-product conversions (ADR-S003). */
  units?: ProductUnit[];
  reorderQty?: string | null;
  /** KDS routing: KitchenStation.code. */
  station?: string | null;
  allowFractionalSale?: boolean;
  minSaleQty?: string | null;
  maxSaleQty?: string | null;
  packagings?: { id: string; name: string; quantity: string; barcode: string | null; isActive: boolean }[];
  salesPrice: string | null;
  costPrice: string | null;
  image?: string | null;
  isActive: boolean;
  trackInventory: boolean;
  // Inventory tracking configuration.
  batchTracking?: boolean;
  canBeSold?: boolean;
  stockPolicy?: string;
  manufacturingRole?: string | null;
  expiryTracking?: boolean;
  serialTracking?: boolean;
  pickingStrategy?: string;
  createdAt: string;
  // Beverage Control (bar alcohol) — digital-weight measurement.
  measurementMethod?: string;
  containerVolumeMl?: string | null;
  emptyBottleWeightG?: string | null;
  actualEmptyWeightG?: string | null;
  fullBottleWeightG?: string | null;
  liquidWeightG?: string | null;
  conversionFactorMlPerG?: string | null;
  standardPourMl?: string | null;
  doublePourMl?: string | null;
  allowPartialBottle?: boolean;
  varianceToleranceG?: string | null;
  // M3 — Inventory account overrides (optional; null = inherit).
  incomeAccountOverrideId?: string | null;
  expenseAccountOverrideId?: string | null;
  inventoryAccountOverrideId?: string | null;
  cogsAccountOverrideId?: string | null;
  shrinkageAccountOverrideId?: string | null;
  damageAccountOverrideId?: string | null;
  expiryAccountOverrideId?: string | null;
  varianceGainAccountOverrideId?: string | null;
}

export interface ProductCategory {
  id: string;
  name: string;
  parentId: string | null;
  incomeAccountId?: string | null;
  expenseAccountId?: string | null;
  inventoryAccountId?: string | null;
  cogsAccountId?: string | null;
  shrinkageAccountId?: string | null;
  damageAccountId?: string | null;
  expiryAccountId?: string | null;
  varianceGainAccountId?: string | null;
}

export interface ListParams {
  page: number;
  pageSize: number;
  search?: string;
  categoryId?: string;
  productType?: string;
  sortBy?: string;
  sortOrder?: 'asc' | 'desc';
}

export interface CreateProductInput {
  code: string;
  sku?: string;
  name: string;
  productType: string;
  costingMethod?: string;
  categoryId?: string;
  // UOM roles + purchasing/sale rules.
  uomId?: string;
  purchaseUomId?: string;
  salesUomId?: string;
  recipeUomId?: string;
  productionUomId?: string;
  uomConversion?: number;
  reorderQty?: number;
  /** KDS routing: KitchenStation.code. */
  station?: string;
  allowFractionalSale?: boolean;
  minSaleQty?: number;
  maxSaleQty?: number;
  packagings?: { name: string; quantity: number; barcode?: string; isActive?: boolean }[];
  /** Replaces the product's extra units when sent. */
  units?: {
    uomId: string; factor: number; isPurchase: boolean; isSales: boolean;
    salePrice?: number; purchasePrice?: number; isActive?: boolean;
  }[];
  salesPrice?: number;
  costPrice?: number;
  image?: string;
  trackInventory?: boolean;
  // Inventory tracking configuration.
  batchTracking?: boolean;
  canBeSold?: boolean;
  stockPolicy?: string;
  manufacturingRole?: string | null;
  expiryTracking?: boolean;
  serialTracking?: boolean;
  pickingStrategy?: string;
  // Beverage Control (bar alcohol) — digital-weight measurement.
  measurementMethod?: string;
  containerVolumeMl?: number;
  emptyBottleWeightG?: number;
  actualEmptyWeightG?: number;
  fullBottleWeightG?: number;
  standardPourMl?: number;
  doublePourMl?: number;
  allowPartialBottle?: boolean;
  varianceToleranceG?: number;
}

export function useProducts(params: ListParams) {
  return useQuery({
    queryKey: ['products', params],
    queryFn: async () => (await api.get<PaginatedResult<Product>>('/products', { params })).data,
  });
}

export function useCreateProduct() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (input: CreateProductInput) =>
      (await api.post<Product>('/products', input)).data,
    onSuccess: () => qc.invalidateQueries({ queryKey: ['products'] }),
  });
}

export function useUpdateProduct() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async ({ id, data }: { id: string; data: Partial<CreateProductInput> }) =>
      (await api.patch<Product>(`/products/${id}`, data)).data,
    onSuccess: () => qc.invalidateQueries({ queryKey: ['products'] }),
  });
}

export function useProductCategories() {
  return useQuery({
    queryKey: ['product-categories'],
    queryFn: async () =>
      (await api.get<PaginatedResult<ProductCategory>>('/product-categories', { params: { pageSize: 200 } })).data?.data ?? [],
    staleTime: 5 * 60 * 1000,
  });
}

export interface CreateProductCategoryInput {
  name: string;
  parentId?: string;
  incomeAccountId?: string;
  expenseAccountId?: string;
}

export function useCreateProductCategory() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (input: CreateProductCategoryInput) =>
      (await api.post<ProductCategory>('/product-categories', input)).data,
    onSuccess: () => qc.invalidateQueries({ queryKey: ['product-categories'] }),
  });
}

export function useUpdateProductCategory() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async ({ id, data }: { id: string; data: Partial<CreateProductCategoryInput> }) =>
      (await api.patch<ProductCategory>(`/product-categories/${id}`, data)).data,
    onSuccess: () => qc.invalidateQueries({ queryKey: ['product-categories'] }),
  });
}

export function useDeleteProductCategory() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (id: string) =>
      (await api.delete(`/product-categories/${id}`)).data,
    onSuccess: () => qc.invalidateQueries({ queryKey: ['product-categories'] }),
  });
}

export function useDeleteProduct() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (id: string) =>
      (await api.delete(`/products/${id}`)).data,
    onSuccess: () => qc.invalidateQueries({ queryKey: ['products'] }),
  });
}

export function useRestoreProduct() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (id: string) =>
      (await api.patch(`/products/${id}/restore`, {})).data,
    onSuccess: () => qc.invalidateQueries({ queryKey: ['products'] }),
  });
}

// UOM-1: density, weights and truckload of one product, plus a quantity in every unit it converts to.

export interface ProductUnitProfile {
  productId: string;
  baseUomId: string | null;
  densityTonnesPerM3: number | null;
  kgPerPiece: number | null;
  kgPerBag: number | null;
  tonnesPerTruckload: number | null;
}

export type ProductUnitProfileInput = Partial<Pick<ProductUnitProfile,
  'densityTonnesPerM3' | 'kgPerPiece' | 'kgPerBag' | 'tonnesPerTruckload'>>;

export interface ProductUnitEquivalent {
  uomId: string;
  code: string;
  name: string;
  symbol: string | null;
  quantity: number;
  isBase: boolean;
}

export function useProductUnitProfile(id: string | undefined) {
  return useQuery({
    queryKey: ['products', id, 'unit-profile'],
    queryFn: async () => (await api.get<ProductUnitProfile>(`/products/${id}/unit-profile`)).data,
    enabled: !!id,
  });
}

export function useSaveProductUnitProfile(id: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (input: ProductUnitProfileInput) =>
      (await api.put<ProductUnitProfile>(`/products/${id}/unit-profile`, input)).data,
    onSuccess: () => qc.invalidateQueries({ queryKey: ['products'] }),
  });
}

export function useProductUnitEquivalents(id: string | undefined, quantity: number | undefined) {
  return useQuery({
    queryKey: ['products', id, 'unit-equivalents', quantity],
    queryFn: async () => (await api.get<{ units: ProductUnitEquivalent[] }>(`/products/${id}/unit-equivalents`, {
      params: { quantity },
    })).data.units,
    enabled: !!id && quantity !== undefined,
  });
}

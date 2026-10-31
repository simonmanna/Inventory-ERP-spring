/** Product grades, attributes and specifications (INV-2). */
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from '@/lib/api';

export interface ProductGrade {
  id: string;
  code: string;
  name: string;
  description: string | null;
  sequence: number;
  isActive: boolean;
}

export interface ProductAttribute {
  id: string;
  code: string;
  name: string;
  dataType: 'text' | 'number' | 'boolean' | 'option';
  unit: string | null;
  options: string[];
  categoryId: string | null;
  isRequired: boolean;
  isActive: boolean;
}

export interface AttributeValue {
  attributeId: string;
  valueText: string | null;
  valueNumber: number | string | null;
  valueBoolean: boolean | null;
}

export interface ProductSpecification {
  productId: string;
  productCode: string;
  productName: string;
  specification: { gradeId: string | null; standardRef: string | null; specification: string | null } | null;
  values: AttributeValue[];
  attributes: ProductAttribute[];
}

const KEY = ['product-specs'];

export function useProductGrades() {
  return useQuery({
    queryKey: [...KEY, 'grades'],
    queryFn: async () => (await api.get<ProductGrade[]>('/product-grades')).data,
  });
}

export function useProductAttributes() {
  return useQuery({
    queryKey: [...KEY, 'attributes'],
    queryFn: async () => (await api.get<ProductAttribute[]>('/product-attributes')).data,
  });
}

export function useProductSpecification(productId: string) {
  return useQuery({
    queryKey: [...KEY, 'specification', productId],
    enabled: !!productId,
    queryFn: async () => (await api.get<ProductSpecification>(`/product-specifications/${productId}`)).data,
  });
}

export function useSpecAction() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async ({ method, path, body }: { method: 'post' | 'patch' | 'put' | 'delete'; path: string; body?: unknown }) =>
      (await api.request({ method, url: path, data: body })).data,
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: KEY });
    },
  });
}

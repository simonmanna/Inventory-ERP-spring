import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from '@/lib/api';
import { idempotentPost } from '@/lib/idempotent-request';

/**
 * ADR-PROCUREMENT-001 — Source-to-Pay: purchasing policies, supplier lifecycle (qualification, dual-control bank
 * accounts, certificates, catalogue, price history) and eligibility. Typed hooks over `/procurement/*` extension
 * routes; the Nest-parity pages keep their own inline calls.
 */

export const errMsg = (e: any): string => {
  const m = e?.response?.data?.message;
  return Array.isArray(m) ? m.join(', ') : (m ?? e?.message ?? 'Request failed');
};

// ------------------------------------------------------------------ policies

export type PolicyKind = 'enum' | 'number' | 'optional_number' | 'string_list' | 'boolean';
export type PolicyStatus = 'draft' | 'approved' | 'active' | 'superseded' | 'cancelled';
export type PolicyScope = 'org' | 'branch' | 'category' | 'department' | 'cost_center' | 'project';

export interface PolicyDefinition {
  key: string;
  group: string;
  label: string;
  description: string;
  kind: PolicyKind;
  default: unknown;
  allowed: string[] | null;
  min: number | null;
  max: number | null;
  effective: { value: unknown; source: 'default' | 'policy'; policyId: string | null };
}

export interface Policy {
  id: string;
  key: string;
  scopeType: PolicyScope;
  scopeId: string | null;
  version: number;
  status: PolicyStatus;
  effectiveFrom: string;
  value: unknown;
  notes: string | null;
  createdBy: string | null;
  approvedBy: string | null;
  approvedAt: string | null;
  activatedAt: string | null;
}

export function usePolicyDefinitions() {
  return useQuery({
    queryKey: ['procurement-policies', 'definitions'],
    queryFn: async () => (await api.get<PolicyDefinition[]>('/procurement/policies/definitions')).data,
  });
}

export function usePolicies(key?: string) {
  return useQuery({
    queryKey: ['procurement-policies', 'list', key ?? ''],
    queryFn: async () => (await api.get<Policy[]>('/procurement/policies', { params: { key: key || undefined } })).data,
  });
}

export function useCreatePolicy() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (body: { key: string; scopeType: PolicyScope; scopeId?: string; effectiveFrom?: string; value: unknown; notes?: string }) =>
      (await api.post<Policy>('/procurement/policies', body)).data,
    onSuccess: () => qc.invalidateQueries({ queryKey: ['procurement-policies'] }),
  });
}

export function usePolicyAction() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async ({ id, action }: { id: string; action: 'approve' | 'activate' | 'cancel' }) =>
      await idempotentPost<Policy>(`/procurement/policies/${id}/${action}`),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['procurement-policies'] }),
  });
}

// ------------------------------------------------------------------ suppliers

export type SupplierStatus = 'prospect' | 'provisional' | 'approved' | 'suspended' | 'blacklisted';

export interface SupplierRow {
  id: string;
  code: string | null;
  name: string;
  email: string | null;
  phone: string | null;
  taxNumber: string | null;
  lifecycleStatus: SupplierStatus | null;
  riskClass: 'low' | 'medium' | 'high' | null;
  categories: string[] | null;
  leadTimeDays: number | null;
  certificatesExpiring: number;
}

export interface Paged<T> {
  data: T[];
  meta: { page: number; pageSize: number; total: number; totalPages: number };
}

export interface BankAccount {
  id: string;
  bankName: string;
  branchName: string | null;
  accountName: string;
  accountNumberMasked: string;
  swiftCode: string | null;
  currencyCode: string | null;
  isDefault: boolean;
  status: 'pending_verification' | 'verified' | 'revoked';
  createdBy: string | null;
  verifiedAt: string | null;
}

export interface Certificate {
  id: string;
  type: string;
  number: string | null;
  issuer: string | null;
  validFrom: string | null;
  validTo: string | null;
  partnerName?: string;
  partnerId: string;
}

export interface SupplierProduct {
  id: string;
  productId: string;
  productCode: string | null;
  productName: string | null;
  supplierSku: string | null;
  minOrderQty: string | null;
  leadTimeDays: number | null;
  preferred: boolean;
}

export interface Eligibility {
  partnerId: string;
  lifecycleStatus: SupplierStatus | null;
  eligible: boolean;
  blocked: string[];
  warnings: string[];
}

export interface SupplierDetail {
  partner: { id: string; code: string | null; name: string; email: string | null; phone: string | null; taxNumber: string | null; website: string | null };
  profile: null | {
    lifecycleStatus: SupplierStatus;
    riskClass: 'low' | 'medium' | 'high';
    categories: string[];
    currencyCode: string | null;
    leadTimeDays: number | null;
    registrationNumber: string | null;
    tradingName: string | null;
    statusReason: string | null;
    notes: string | null;
    approvedAt: string | null;
  };
  bankAccounts: BankAccount[];
  certificates: Certificate[];
  products: SupplierProduct[];
  eligibility: Eligibility;
}

export function useSuppliers(params: { search?: string; status?: string; page?: number; pageSize?: number }) {
  return useQuery({
    queryKey: ['procurement-suppliers', params],
    queryFn: async () => (await api.get<Paged<SupplierRow>>('/procurement/suppliers', { params })).data,
  });
}

export function useSupplier(partnerId?: string) {
  return useQuery({
    queryKey: ['procurement-suppliers', 'detail', partnerId],
    enabled: !!partnerId,
    queryFn: async () => (await api.get<SupplierDetail>(`/procurement/suppliers/${partnerId}`)).data,
  });
}

export function useExpiringCertificates(days = 30) {
  return useQuery({
    queryKey: ['procurement-suppliers', 'expiring', days],
    queryFn: async () => (await api.get<Certificate[]>('/procurement/suppliers/certificates/expiring', { params: { days } })).data,
  });
}

export function usePriceHistory(productId?: string) {
  return useQuery({
    queryKey: ['procurement-suppliers', 'prices', productId],
    enabled: !!productId,
    queryFn: async () =>
      (await api.get<{ history: any[]; suppliers: Array<{ partnerId: string; partnerName: string; currencyCode: string; observations: number; minPrice: string; maxPrice: string; avgPrice: string; lastPrice: string; lastDate: string }> }>(
        '/procurement/suppliers/price-history', { params: { productId } })).data,
  });
}

/** Any supplier write; every call invalidates the supplier caches. */
export function useSupplierMutation<TBody = unknown>(fn: (body: TBody) => Promise<unknown>) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: fn,
    onSuccess: () => qc.invalidateQueries({ queryKey: ['procurement-suppliers'] }),
  });
}

export const supplierApi = {
  saveProfile: (partnerId: string, body: Record<string, unknown>) => api.put(`/procurement/suppliers/${partnerId}/profile`, body),
  transition: (partnerId: string, action: string, reason?: string) =>
    idempotentPost(`/procurement/suppliers/${partnerId}/profile/${action}`, { reason }),
  addBankAccount: (partnerId: string, body: Record<string, unknown>) => api.post(`/procurement/suppliers/${partnerId}/bank-accounts`, body),
  verifyBankAccount: (id: string) => idempotentPost(`/procurement/suppliers/bank-accounts/${id}/verify`),
  revokeBankAccount: (id: string) => idempotentPost(`/procurement/suppliers/bank-accounts/${id}/revoke`),
  addCertificate: (partnerId: string, body: Record<string, unknown>) => api.post(`/procurement/suppliers/${partnerId}/certificates`, body),
  removeCertificate: (id: string) => api.delete(`/procurement/suppliers/certificates/${id}`),
  saveProduct: (partnerId: string, body: Record<string, unknown>) => api.put(`/procurement/suppliers/${partnerId}/products`, body),
  removeProduct: (id: string) => api.delete(`/procurement/suppliers/products/${id}`),
};

export const SUPPLIER_STATUS_TONE: Record<SupplierStatus | 'none', 'neutral' | 'info' | 'success' | 'warning' | 'danger'> = {
  none: 'neutral',
  prospect: 'neutral',
  provisional: 'info',
  approved: 'success',
  suspended: 'warning',
  blacklisted: 'danger',
};

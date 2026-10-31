import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import type { PaginatedResult } from '@erp/shared';
import { api } from '@/lib/api';
import { idempotentPost } from '@/lib/idempotent-request';

/** One customer's credit position (SALES-1). */
export interface CreditPosition {
  partnerId: string;
  partnerName: string;
  creditLimit: number;
  creditHold: boolean;
  openReceivables: number;
  overdueReceivables: number;
  unbilledOrders: number;
  uninvoicedDeliveries: number;
  exposure: number;
  overrideAmount: number;
  headroom: number | null;
  exceeded: boolean;
}

export interface CreditOverride {
  id: string;
  amount: number;
  coversHold: boolean;
  reason: string;
  expiresAt: string;
  createdAt: string;
  revokedAt: string | null;
}

export interface CreditCustomerDetail extends CreditPosition {
  overrides: CreditOverride[];
}

export function useCreditCustomers(params: { search?: string; status?: string; page: number; pageSize: number }) {
  return useQuery({
    queryKey: ['credit-control', 'customers', params],
    queryFn: async () => (await api.get<PaginatedResult<CreditPosition>>('/credit-control/customers', { params })).data,
  });
}

export function useCreditCustomer(partnerId: string | undefined) {
  return useQuery({
    queryKey: ['credit-control', 'customer', partnerId],
    queryFn: async () => (await api.get<CreditCustomerDetail>(`/credit-control/customers/${partnerId}`)).data,
    enabled: !!partnerId,
  });
}

export function useSaveCreditPolicy(partnerId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (input: { creditLimit?: number; creditHold?: boolean; reason?: string }) =>
      (await api.put(`/credit-control/customers/${partnerId}/policy`, input)).data,
    onSuccess: () => qc.invalidateQueries({ queryKey: ['credit-control'] }),
  });
}

export function useGrantCreditOverride(partnerId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (input: { amount: number; coversHold: boolean; reason: string; expiresAt: string }) =>
      idempotentPost(`/credit-control/customers/${partnerId}/overrides`, input),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['credit-control'] }),
  });
}

export function useRevokeCreditOverride() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (id: string) => (await api.post(`/credit-control/overrides/${id}/revoke`, {})).data,
    onSuccess: () => qc.invalidateQueries({ queryKey: ['credit-control'] }),
  });
}

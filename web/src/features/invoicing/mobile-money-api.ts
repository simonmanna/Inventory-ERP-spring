import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import type { PaginatedResult } from '@erp/shared';
import { api } from '@/lib/api';
import { idempotentPost } from '@/lib/idempotent-request';

// ---- Mobile money collections (INT-1) ----

export type CollectionStatus = 'pending' | 'successful' | 'failed';

export interface MobileMoneyCollection {
  id: string;
  reference: string;
  provider: string;
  partnerId: string;
  partnerName: string;
  msisdnMasked: string;
  amount: string;
  status: CollectionStatus;
  failureReason: string | null;
  paymentId: string | null;
  paymentNumber: string | null;
  createdAt: string;
  completedAt: string | null;
}

export interface MobileMoneySettings {
  provider: string;
  available: string[];
  configured: boolean;
  callbackSecretConfigured: boolean;
}

const KEY = ['mobile-money'];

export function useMobileMoneySettings() {
  return useQuery({
    queryKey: [...KEY, 'settings'],
    staleTime: 60_000,
    queryFn: async () => (await api.get<MobileMoneySettings>('/mobile-money/settings')).data,
  });
}

export function useMobileMoneyCollections(params: { page: number; pageSize: number; status?: string }) {
  return useQuery({
    queryKey: [...KEY, 'list', params],
    placeholderData: keepPreviousData,
    refetchInterval: 30_000,
    queryFn: async () => (await api.get<PaginatedResult<MobileMoneyCollection>>('/mobile-money/collections', { params })).data,
  });
}

export function useStartCollection() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (body: { partnerId: string; amount: number; msisdn: string; description?: string }) =>
      idempotentPost<MobileMoneyCollection>('/mobile-money/collections', body),
    onSuccess: () => qc.invalidateQueries({ queryKey: KEY }),
  });
}

export function useRefreshCollection() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (id: string) => idempotentPost<MobileMoneyCollection>(`/mobile-money/collections/${id}/refresh`),
    onSuccess: () => qc.invalidateQueries({ queryKey: KEY }),
  });
}

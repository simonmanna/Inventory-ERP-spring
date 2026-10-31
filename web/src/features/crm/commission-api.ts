import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import type { PaginatedResult } from '@erp/shared';
import { api } from '@/lib/api';

export interface CommissionRule {
  id: string;
  name: string;
  employeeId: string | null;
  firstName?: string | null;
  lastName?: string | null;
  productId: string | null;
  productName?: string | null;
  productCategoryId: string | null;
  productCategoryName?: string | null;
  ratePercent: number;
  validFrom: string | null;
  validTo: string | null;
  priority: number;
  isActive: boolean;
}

export interface CommissionEntry {
  id: string;
  firstName?: string | null;
  lastName?: string | null;
  documentNumber: string | null;
  productName?: string | null;
  baseAmount: number;
  ratePercent: number;
  amount: number;
  earnedOn: string;
  status: 'accrued' | 'approved' | 'exported' | 'cancelled';
}

const KEY = ['commissions'];

function useInvalidate() {
  const qc = useQueryClient();
  return () => qc.invalidateQueries({ queryKey: KEY });
}

export function useCommissionRules() {
  return useQuery({
    queryKey: [...KEY, 'rules'],
    queryFn: async () => (await api.get<CommissionRule[]>('/commissions/rules')).data,
  });
}

export function useCommissionEntries(params: { status?: string; page: number; pageSize: number }) {
  return useQuery({
    queryKey: [...KEY, 'entries', params],
    queryFn: async () => (await api.get<PaginatedResult<CommissionEntry>>('/commissions/entries', { params })).data,
  });
}

export function useSaveCommissionRule() {
  const invalidate = useInvalidate();
  return useMutation({
    mutationFn: async ({ id, ...body }: Partial<CommissionRule> & { id?: string }) => (id
      ? await api.put(`/commissions/rules/${id}`, body)
      : await api.post('/commissions/rules', body)).data,
    onSuccess: invalidate,
  });
}

export function useDeleteCommissionRule() {
  const invalidate = useInvalidate();
  return useMutation({
    mutationFn: async (id: string) => (await api.delete(`/commissions/rules/${id}`)).data,
    onSuccess: invalidate,
  });
}

export function useCalculateCommissions() {
  const invalidate = useInvalidate();
  return useMutation({
    mutationFn: async (input: { from?: string; to?: string }) =>
      (await api.post<{ created: number; cancelled: number; linesWithoutRule: number }>('/commissions/calculate', input)).data,
    onSuccess: invalidate,
  });
}

export function useApproveCommissions() {
  const invalidate = useInvalidate();
  return useMutation({
    mutationFn: async (ids: string[]) => (await api.post('/commissions/approve', { ids })).data,
    onSuccess: invalidate,
  });
}

export function useExportCommissions() {
  const invalidate = useInvalidate();
  return useMutation({
    mutationFn: async () => (await api.post<{ payrollInputs: number; entries: number }>('/commissions/export-payroll', {})).data,
    onSuccess: invalidate,
  });
}

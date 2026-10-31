/** Employment contract records (HR-2). */
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from '@/lib/api';

export interface HrContract {
  id: string;
  code: string;
  employeeId: string;
  contractType: string;
  status: string;
  startDate: string;
  endDate: string | null;
  hoursPerWeek: number | string | null;
  signedOn: string | null;
  terminatedOn: string | null;
  terminationReason: string | null;
  renewalOfId: string | null;
  notes: string | null;
  employee?: { id: string; employeeCode: string; firstName: string; lastName: string | null } | null;
}

type Rows = { rows: HrContract[]; total: number };
type Params = Record<string, string | number | undefined>;

const clean = (p: Params) =>
  Object.fromEntries(Object.entries(p).filter(([, v]) => v !== undefined && v !== ''));

export function useHrContracts(params: Params = {}) {
  return useQuery({
    queryKey: ['hr', 'contracts', params],
    queryFn: async () => (await api.get<Rows>('/hr/contracts', { params: clean(params) })).data,
  });
}

export function useExpiringContracts(days: number) {
  return useQuery({
    queryKey: ['hr', 'contracts', 'expiring', days],
    queryFn: async () => (await api.get<Rows>('/hr/contracts/expiring', { params: { days } })).data,
  });
}

export function useContractAction() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async ({ method, path, body }: { method: 'post' | 'patch' | 'delete'; path: string; body?: unknown }) =>
      (await api.request({ method, url: path, data: body })).data,
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['hr', 'contracts'] });
    },
  });
}

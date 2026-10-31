import { useQuery } from '@tanstack/react-query';
import { api } from '@/lib/api';

export interface Option {
  id: string;
  name: string;
}

/** Branch choices for a condition; empty when the viewer cannot read branches. */
export function useBranchChoices() {
  return useQuery<Option[]>({
    queryKey: ['approval-controls', 'branches'],
    retry: false,
    queryFn: async () => (await api.get('/branches', { params: { pageSize: 200 } })).data.data ?? [],
  });
}

/** Department choices; empty when HR is off or the viewer cannot read it. */
export function useDepartmentChoices() {
  return useQuery<Option[]>({
    queryKey: ['approval-controls', 'departments'],
    retry: false,
    queryFn: async () => {
      const body = (await api.get('/hr/departments')).data;
      return Array.isArray(body) ? body : body.data ?? [];
    },
  });
}

/** A write under controlled configuration answers this instead of the row. */
export function isHeldForApproval(body: unknown): boolean {
  return !!(body as { pendingApproval?: boolean } | null)?.pendingApproval;
}

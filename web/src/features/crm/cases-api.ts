/** Customer complaints and service requests (CRM-1). */
import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from '@/lib/api';
import type { Paged } from '@/features/crm/suite-api';

export interface CaseEvent {
  id: string;
  kind: 'note' | 'status' | 'assignment' | 'discrepancy';
  body: string | null;
  fromStatus: string | null;
  toStatus: string | null;
  actorName: string | null;
  createdAt: string;
}

export interface CrmCase {
  id: string;
  code: string;
  caseType: 'complaint' | 'service_request';
  category: string;
  subject: string;
  description: string | null;
  partnerId: string;
  partnerName: string | null;
  contactName: string | null;
  contactPhone: string | null;
  priority: string;
  status: string;
  assigneeId: string | null;
  assigneeName: string | null;
  dueAt: string | null;
  overdue: boolean;
  deliveryOrderId: string | null;
  deliveryOrderCode: string | null;
  discrepancyId: string | null;
  discrepancyCode: string | null;
  resolution: string | null;
  createdAt: string;
  events?: CaseEvent[];
}

export const CASE_STATUS_TONE: Record<string, string> = {
  open: 'bg-sky-100 text-sky-800',
  in_progress: 'bg-amber-100 text-amber-800',
  resolved: 'bg-emerald-100 text-emerald-800',
  cancelled: 'bg-muted text-muted-foreground',
  closed: 'bg-muted text-muted-foreground',
};
export const PRIORITY_TONE: Record<string, string> = {
  urgent: 'bg-rose-100 text-rose-800',
  high: 'bg-amber-100 text-amber-800',
  normal: 'bg-muted text-muted-foreground',
  low: 'bg-muted text-muted-foreground',
};

const clean = (p: Record<string, unknown>) =>
  Object.fromEntries(Object.entries(p).filter(([, v]) => v !== undefined && v !== null && v !== ''));

export function useCases(params: Record<string, string | number | undefined>) {
  return useQuery({
    queryKey: ['crm-cases', params],
    queryFn: async () => (await api.get<Paged<CrmCase>>('/crm/cases', { params: clean(params) })).data,
    placeholderData: keepPreviousData,
  });
}

export function useCase(id: string | undefined) {
  return useQuery({
    queryKey: ['crm-case', id],
    enabled: !!id,
    queryFn: async () => (await api.get<CrmCase>(`/crm/cases/${id}`)).data,
  });
}

export const caseApi = {
  create: (body: Record<string, unknown>) => api.post<CrmCase>('/crm/cases', body).then((r) => r.data),
  fromDiscrepancy: (id: string) => api.post<CrmCase>(`/crm/cases/from-discrepancy/${id}`, {}).then((r) => r.data),
  assign: (id: string, assigneeId: string | null) =>
    api.post<CrmCase>(`/crm/cases/${id}/assign`, { assigneeId }).then((r) => r.data),
  note: (id: string, body: string) => api.post<CrmCase>(`/crm/cases/${id}/notes`, { body }).then((r) => r.data),
  status: (id: string, status: string, note?: string) =>
    api.post<CrmCase>(`/crm/cases/${id}/status`, { status, note }).then((r) => r.data),
};

/** Wraps a case call so every case list and the open case refresh after it. */
export function useCaseMutation<A>(fn: (arg: A) => Promise<CrmCase>) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: fn,
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['crm-cases'] });
      qc.invalidateQueries({ queryKey: ['crm-case'] });
    },
  });
}

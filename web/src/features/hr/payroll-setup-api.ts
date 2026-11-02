import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from '@/lib/api';

/** HP-0 payroll foundations: settings, statutory packs, pay groups, pay calendars, period locks. */

export interface PayrollSettings {
  id: string | null;
  engine: 'v1' | 'v2';
  statutoryPack: 'NONE' | 'UG' | 'AE';
  prorationBasis: 'CALENDAR_DAYS' | 'WORKING_DAYS' | 'FIXED_30';
  weekendDays: string;
  standardHoursPerDay: number | string;
  roundingScale: number;
  requireSeparateApprover: boolean;
  lockPeriodOnApproval: boolean;
  payslipNote: string | null;
  configured: boolean;
}

export interface StatutoryPack {
  code: string;
  name: string;
  countryCode: string | null;
  currencyCode: string | null;
  description: string;
  available: boolean;
}

export interface PayGroup {
  id: string;
  code: string;
  name: string;
  payFrequency: 'MONTHLY' | 'BIWEEKLY' | 'WEEKLY';
  anchorDate: string | null;
  paymentDay: number;
  isDefault: boolean;
  isActive: boolean;
  description: string | null;
  employeeCount: number;
}

export interface PayGroupMember {
  id: string;
  employeeCode: string;
  firstName: string;
  lastName: string | null;
  isActive: boolean;
  payGroupId: string | null;
}

export interface CalendarPeriod {
  id: string;
  periodCode: string;
  startDate: string;
  endDate: string;
  status: 'OPEN' | 'CLOSED' | 'LOCKED';
  payGroupId: string | null;
  paymentDate: string | null;
  lockedAt: string | null;
  run: { id: string; runNumber: string; status: string; totalNet: number | string } | null;
}

const KEY = ['hr', 'payroll-setup'];

export function usePayrollSettings() {
  return useQuery({
    queryKey: [...KEY, 'settings'],
    queryFn: async () => (await api.get<PayrollSettings>('/hr/payroll/settings')).data,
  });
}

export function useStatutoryPacks() {
  return useQuery({
    queryKey: [...KEY, 'packs'],
    queryFn: async () => (await api.get<StatutoryPack[]>('/hr/payroll/statutory-packs')).data,
    staleTime: Infinity,
  });
}

export function useSavePayrollSettings() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (dto: Partial<PayrollSettings>) => (await api.put<PayrollSettings>('/hr/payroll/settings', dto)).data,
    onSuccess: () => qc.invalidateQueries({ queryKey: [...KEY, 'settings'] }),
  });
}

export function usePayGroups() {
  return useQuery({
    queryKey: [...KEY, 'groups'],
    queryFn: async () => (await api.get<{ rows: PayGroup[]; total: number }>('/hr/payroll/pay-groups')).data,
  });
}

export function useSavePayGroup() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async ({ id, dto }: { id?: string; dto: Record<string, unknown> }) =>
      (id ? await api.patch<PayGroup>(`/hr/payroll/pay-groups/${id}`, dto) : await api.post<PayGroup>('/hr/payroll/pay-groups', dto)).data,
    onSuccess: () => qc.invalidateQueries({ queryKey: KEY }),
  });
}

export function useDeletePayGroup() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (id: string) => (await api.delete(`/hr/payroll/pay-groups/${id}`)).data,
    onSuccess: () => qc.invalidateQueries({ queryKey: KEY }),
  });
}

export function usePayGroupMembers(id?: string, search?: string) {
  return useQuery({
    queryKey: [...KEY, 'members', id, search],
    queryFn: async () =>
      (await api.get<{ rows: PayGroupMember[]; total: number }>(`/hr/payroll/pay-groups/${id}/members`, { params: { search, take: 500 } })).data,
    enabled: !!id,
  });
}

export function useAssignPayGroupMembers() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async ({ id, employeeIds }: { id: string; employeeIds: string[] }) =>
      (await api.put(`/hr/payroll/pay-groups/${id}/members`, { employeeIds })).data,
    onSuccess: () => qc.invalidateQueries({ queryKey: KEY }),
  });
}

export function usePayCalendar(payGroupId?: string, year?: number) {
  return useQuery({
    queryKey: [...KEY, 'calendar', payGroupId, year],
    queryFn: async () =>
      (await api.get<{ rows: CalendarPeriod[]; total: number }>('/hr/payroll/calendar', { params: { payGroupId, year, take: 60 } })).data,
    enabled: !!payGroupId && !!year,
  });
}

export function useGeneratePeriods() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async ({ id, year }: { id: string; year: number }) =>
      (await api.post<{ created: CalendarPeriod[]; skipped: number }>(`/hr/payroll/pay-groups/${id}/periods`, { year })).data,
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: [...KEY, 'calendar'] });
      qc.invalidateQueries({ queryKey: ['hr', 'payroll'] });
    },
  });
}

export function usePeriodLock() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async ({ id, lock, reason }: { id: string; lock: boolean; reason?: string }) =>
      (await api.post(`/hr/payroll/periods/${id}/${lock ? 'lock' : 'unlock'}`, lock ? undefined : { reason })).data,
    onSuccess: () => qc.invalidateQueries({ queryKey: [...KEY, 'calendar'] }),
  });
}

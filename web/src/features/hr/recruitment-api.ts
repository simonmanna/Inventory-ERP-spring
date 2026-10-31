/** Recruitment and onboarding (HR-1): job openings, applicants, interviews, hiring and onboarding checklists. */
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from '@/lib/api';

export interface JobOpening {
  id: string;
  code: string;
  title: string;
  departmentId: string | null;
  positionId: string | null;
  branchId: string | null;
  hiringManagerId: string | null;
  employmentType: string;
  headcount: number;
  status: string;
  openedOn: string | null;
  closesOn: string | null;
  description: string | null;
  applicantCount?: number;
  hiredCount?: number;
  department?: { id: string; name: string } | null;
  position?: { id: string; name: string } | null;
}

export interface Interview {
  id: string;
  applicantId: string;
  scheduledAt: string;
  interviewer: string | null;
  location: string | null;
  status: string;
  outcome: string | null;
  score: number | null;
  notes: string | null;
}

export interface Applicant {
  id: string;
  code: string;
  openingId: string;
  firstName: string;
  lastName: string | null;
  email: string | null;
  phone: string | null;
  source: string | null;
  stage: string;
  stageReason: string | null;
  appliedOn: string;
  notes: string | null;
  employeeId: string | null;
  opening?: { id: string; code: string; title: string; status?: string } | null;
  employee?: { id: string; employeeCode: string; firstName: string; lastName: string | null } | null;
  interviews?: Interview[];
}

export interface OnboardingTemplate {
  id: string;
  title: string;
  description: string | null;
  sequence: number;
  dueDays: number;
  isRequired: boolean;
  responsible: string | null;
  isActive: boolean;
}

export interface OnboardingTask {
  id: string;
  employeeId: string;
  title: string;
  description: string | null;
  dueOn: string | null;
  isRequired: boolean;
  responsible: string | null;
  status: string;
  completedAt: string | null;
  notes: string | null;
  employee?: { id: string; employeeCode: string; firstName: string; lastName: string | null } | null;
}

export interface Rows<T> {
  rows: T[];
  total: number;
}

type Params = Record<string, string | number | undefined>;

const clean = (p: Params) =>
  Object.fromEntries(Object.entries(p).filter(([, v]) => v !== undefined && v !== ''));

const KEY = ['hr', 'recruitment'];

export function useJobOpenings(params: Params = {}) {
  return useQuery({
    queryKey: [...KEY, 'openings', params],
    queryFn: async () =>
      (await api.get<Rows<JobOpening>>('/hr/recruitment/openings', { params: clean(params) })).data,
  });
}

export function useApplicants(params: Params = {}) {
  return useQuery({
    queryKey: [...KEY, 'applicants', params],
    queryFn: async () =>
      (await api.get<Rows<Applicant>>('/hr/recruitment/applicants', { params: clean(params) })).data,
  });
}

export function useApplicant(id: string | null) {
  return useQuery({
    queryKey: [...KEY, 'applicant', id],
    enabled: !!id,
    queryFn: async () => (await api.get<Applicant>(`/hr/recruitment/applicants/${id}`)).data,
  });
}

/** POST / PATCH / DELETE against a recruitment path; every list refreshes afterwards. */
export function useRecruitmentAction() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async ({ method, path, body }: { method: 'post' | 'patch' | 'delete'; path: string; body?: unknown }) =>
      (await api.request({ method, url: path, data: body })).data,
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['hr'] });
    },
  });
}

export function useOnboardingTemplates() {
  return useQuery({
    queryKey: [...KEY, 'templates'],
    queryFn: async () => (await api.get<Rows<OnboardingTemplate>>('/hr/onboarding/templates')).data,
  });
}

export function useOnboardingTasks(params: Params = {}) {
  return useQuery({
    queryKey: [...KEY, 'tasks', params],
    queryFn: async () =>
      (await api.get<Rows<OnboardingTask>>('/hr/onboarding/tasks', { params: clean(params) })).data,
  });
}

import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import type { PaginatedResult } from '@erp/shared';
import { api } from '@/lib/api';

export interface Partner {
  id: string;
  code: string;
  name: string;
  isCompany: boolean;
  isCustomer: boolean;
  isSupplier: boolean;
  isEmployee: boolean;
  email: string | null;
  phone: string | null;
  website: string | null;
  taxNumber: string | null;
  membershipLevel: string | null;
  gender: string | null;
  notes: string | null;
  openingBalance: number | null;
  paymentTermId: string | null;
  status: string;
  categoryId: string | null;
  category?: { id: string; name: string } | null;
  createdAt: string;
  updatedAt: string;
  loyaltyEarned?: number;
  contacts?: Array<{ id: string; firstName: string; lastName: string | null; position: string | null; email: string | null; phone: string | null; isPrimary: boolean; isActive?: boolean }>;
  addresses?: Array<{ id: string; type: string; line1: string; line2: string | null; city: string | null; state: string | null; postalCode: string | null; country: string | null; isPrimary: boolean }>;
}

export interface ListParams {
  page: number;
  pageSize: number;
  search?: string;
}

export interface CreatePartnerInput {
  code: string;
  name: string;
  email?: string;
  phone?: string;
  membershipLevel?: string;
  gender?: string;
  isCompany?: boolean;
  isCustomer?: boolean;
  isSupplier?: boolean;
  openingBalance?: number;
  paymentTermId?: string;
  contacts?: Array<Record<string, unknown>>;
  addresses?: Array<Record<string, unknown>>;
}

export function usePartners(params: ListParams, options?: { enabled?: boolean }) {
  return useQuery({
    ...options,
    queryKey: ['partners', params],
    queryFn: async () => (await api.get<PaginatedResult<Partner>>('/partners', { params })).data,
  });
}

export function usePartner(id: string | undefined) {
  return useQuery({
    queryKey: ['partner', id],
    queryFn: async () => (await api.get<Partner>(`/partners/${id}`)).data,
    enabled: !!id,
  });
}

export interface CustomerStatementEntry {
  date: string;
  type: 'credit_issue' | 'payment' | 'write_off';
  reference: string;
  invoiceId: string;
  invoiceNumber: string;
  amount: number;
  runningBalance: number;
}

export interface CustomerStatement {
  partner: { id: string; name: string; code: string | null };
  /** 0 = no limit configured. */
  creditLimit: number;
  outstanding: number;
  /** Headroom left, or null when there is no limit. */
  available: number | null;
  /** Manual block on new credit sales (Partner.creditHold). */
  creditHold: boolean;
  entries: CustomerStatementEntry[];
}

/** Derived house-account (credit) statement for a customer. */
export function useCustomerStatement(partnerId: string | undefined) {
  return useQuery({
    queryKey: ['customer-statement', partnerId],
    queryFn: async () => (await api.get<CustomerStatement>(`/pos/customers/${partnerId}/statement`)).data,
    enabled: !!partnerId,
  });
}

/**
 * Partner count for the dashboard tile.
 *
 * `/partners` is a BaseCrudService list route, so it answers `{ data, meta }`
 * like every other list endpoint — the count is on `meta.total`, not at the top
 * level. Typing it as `{ total: number }` made `data.total` permanently
 * `undefined`, so the tile rendered blank without ever erroring.
 */
export function usePartnerStats() {
  return useQuery({
    queryKey: ['partners-stats'],
    queryFn: async () =>
      (await api.get<PaginatedResult<Partner>>('/partners', { params: { page: 1, pageSize: 1 } })).data,
  });
}

export function useCreatePartner() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (input: CreatePartnerInput) =>
      (await api.post<Partner>('/partners', input)).data,
    onSuccess: () => qc.invalidateQueries({ queryKey: ['partners'] }),
  });
}

export function useUpdatePartner() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async ({ id, data }: { id: string; data: Partial<CreatePartnerInput> }) =>
      (await api.patch<Partner>(`/partners/${id}`, data)).data,
    onSuccess: (_r, v) => {
      qc.invalidateQueries({ queryKey: ['partners'] });
      qc.invalidateQueries({ queryKey: ['partner', v.id] });
    },
  });
}

export function useDeletePartner() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (id: string) =>
      (await api.delete(`/partners/${id}`)).data,
    onSuccess: () => qc.invalidateQueries({ queryKey: ['partners'] }),
  });
}

export function useLoyaltyEarned(partnerId: string, options?: { enabled?: boolean }) {
  return useQuery({
    ...options,
    queryKey: ['loyalty-earned', partnerId],
    queryFn: async () => (await api.get<{ totalEarned: number }>(`/pos/loyalty/earned/${partnerId}`)).data,
    enabled: options?.enabled ?? !!partnerId,
  });
}

// ---------------------------------------------------------------------------
// Customer detail page: contacts CRUD, sales / accounting profile, follow-up
// ---------------------------------------------------------------------------

export interface PartnerContact {
  id: string;
  firstName: string;
  lastName: string | null;
  position: string | null;
  email: string | null;
  phone: string | null;
  isPrimary: boolean;
  isActive?: boolean;
}

export interface ContactInput {
  firstName?: string;
  lastName?: string | null;
  position?: string | null;
  email?: string | null;
  phone?: string | null;
  isPrimary?: boolean;
  isActive?: boolean;
}

function invalidatePartner(qc: ReturnType<typeof useQueryClient>, partnerId: string) {
  qc.invalidateQueries({ queryKey: ['partner', partnerId] });
  qc.invalidateQueries({ queryKey: ['partners'] });
}

export function useAddContact(partnerId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (input: ContactInput) =>
      (await api.post<PartnerContact>(`/partners/${partnerId}/contacts`, input)).data,
    onSuccess: () => invalidatePartner(qc, partnerId),
  });
}

export function useUpdateContact(partnerId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async ({ id, data }: { id: string; data: ContactInput }) =>
      (await api.patch<PartnerContact>(`/partners/${partnerId}/contacts/${id}`, data)).data,
    onSuccess: () => invalidatePartner(qc, partnerId),
  });
}

export function useDeleteContact(partnerId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (id: string) => (await api.delete(`/partners/${partnerId}/contacts/${id}`)).data,
    onSuccess: () => invalidatePartner(qc, partnerId),
  });
}

export interface PartnerBankAccount {
  bankName: string | null;
  accountNumber: string | null;
  holderName: string | null;
  iban: string | null;
  swift: string | null;
}

export type AutoPostBills = 'ask' | 'always' | 'never';

export interface PartnerProfile {
  salespersonId: string | null;
  paymentTermId: string | null;
  paymentModeId: string | null;
  incoterm: string | null;
  incotermLocation: string | null;
  receivableAccountId: string | null;
  payableAccountId: string | null;
  autoPostBills: AutoPostBills;
  bankAccounts: PartnerBankAccount[];
}

export const INCOTERMS = ['EXW', 'FCA', 'CPT', 'CIP', 'DAP', 'DPU', 'DDP', 'FAS', 'FOB', 'CFR', 'CIF'] as const;

export const EMPTY_PROFILE: PartnerProfile = {
  salespersonId: null,
  paymentTermId: null,
  paymentModeId: null,
  incoterm: null,
  incotermLocation: null,
  receivableAccountId: null,
  payableAccountId: null,
  autoPostBills: 'ask',
  bankAccounts: [],
};

export function usePartnerProfile(partnerId: string | undefined) {
  return useQuery({
    queryKey: ['partner', partnerId, 'profile'],
    queryFn: async () => (await api.get<PartnerProfile & { id: string }>(`/partners/${partnerId}/profile`)).data,
    enabled: !!partnerId,
  });
}

export function useUpdatePartnerProfile() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async ({ id, data }: { id: string; data: Partial<PartnerProfile> }) =>
      (await api.patch<PartnerProfile>(`/partners/${id}/profile`, data)).data,
    onSuccess: (_r, v) => qc.invalidateQueries({ queryKey: ['partner', v.id] }),
  });
}

export type FollowupStatus = 'in_need_of_action' | 'with_overdue_invoices' | 'no_action_needed';

export interface FollowupLevel {
  id: string;
  sequence: number;
  name: string;
  daysOverdue: number;
  channel: 'email' | 'letter' | 'none';
  isActive: boolean;
}

export interface FollowupLine {
  id: string;
  documentNumber: string;
  documentType: string;
  issueDate: string;
  dueDate: string | null;
  balance: number;
  company: string | null;
}

export interface PartnerFollowup {
  partnerId: string;
  status: FollowupStatus;
  followupLevelId: string | null;
  level: FollowupLevel | null;
  suggestedLevel: FollowupLevel | null;
  followupNextReminder: string | null;
  followupResponsibleId: string | null;
  followupExcluded: boolean;
  followupNote: string | null;
  amountDue: number;
  amountOverdue: number;
  oldestDueDate: string | null;
  daysOverdue: number;
  promise: { id: string; amount: number; promisedDate: string; notes: string | null } | null;
  lines: FollowupLine[];
}

export interface FollowupInput {
  followupLevelId?: string | null;
  followupNextReminder?: string | null;
  followupResponsibleId?: string | null;
  followupExcluded?: boolean;
  followupNote?: string | null;
  promisedDate?: string | null;
  promiseNote?: string | null;
}

export function usePartnerFollowup(partnerId: string | undefined, enabled = true) {
  return useQuery({
    queryKey: ['partner', partnerId, 'followup'],
    queryFn: async () => (await api.get<PartnerFollowup>(`/sales/collections/partners/${partnerId}/followup`)).data,
    enabled: !!partnerId && enabled,
  });
}

export function useSavePartnerFollowup(partnerId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (data: FollowupInput) =>
      (await api.put<PartnerFollowup>(`/sales/collections/partners/${partnerId}/followup`, data)).data,
    onSuccess: (r) => qc.setQueryData(['partner', partnerId, 'followup'], r),
  });
}

export function useFollowupLevels(enabled = true) {
  return useQuery({
    queryKey: ['sales', 'dunning-levels'],
    queryFn: async () => (await api.get<FollowupLevel[]>('/sales/collections/levels')).data,
    enabled,
    staleTime: 60_000,
  });
}

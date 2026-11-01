import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import type { PaginatedResult } from '@erp/shared';
import { api } from '@/lib/api';
import { idempotentPost } from '@/lib/idempotent-request';
import { notify } from '@/lib/notify';
import { tx } from '@/lib/i18n/tx';

/** Intercompany & multi-company (roadmap 2.7, ADR-S028). */
export interface GroupMember {
  organizationId: string;
  code: string;
  name: string;
  currencyCode: string;
  ownershipPercent: string;
  isParent: boolean;
}

export interface CompanyGroup {
  id: string;
  name: string;
  parentOrganizationId: string;
  currencyCode: string;
  members: GroupMember[];
}

export interface IntercompanyPartnerLink {
  counterpartOrganizationId: string;
  counterpartCode: string;
  counterpartName: string;
  partnerId: string | null;
  partnerCode: string | null;
  partnerName: string | null;
}

export interface IntercompanySettings {
  mirrorMode: 'off' | 'draft' | 'post';
  billExpenseAccountId: string | null;
  effectiveBillExpenseAccountId: string | null;
}

export interface IntercompanyTransaction {
  id: string;
  direction: 'outgoing' | 'incoming';
  sourceDocumentNumber: string | null;
  sourceCompanyName: string;
  targetCompanyName: string;
  targetDocumentNumber: string | null;
  amount: string;
  currencyCode: string | null;
  status: 'mirrored' | 'failed' | 'skipped';
  error: string | null;
  createdAt: string;
}

export interface IntercompanyJournal {
  id: string;
  number: string;
  direction: 'outgoing' | 'incoming';
  postingDate: string;
  description: string;
  amount: string;
  currencyCode: string;
  targetAmount: string;
  targetCurrencyCode: string;
  sourceCompanyName: string;
  targetCompanyName: string;
  sourceEntryNumber: string;
  targetEntryNumber: string;
}

export interface ReconciliationPair {
  companyA: { organizationId: string; code: string; name: string };
  companyB: { organizationId: string; code: string; name: string };
  balanceA: string;
  balanceB: string;
  difference: string;
  matched: boolean;
}

export interface ConsolidationRow {
  code: string;
  name: string;
  type: string;
  byCompany: Record<string, string>;
}

export interface Consolidation {
  statement: 'pl' | 'tb';
  currencyCode: string;
  companies: (GroupMember & { rate: string })[];
  rows: ConsolidationRow[];
  totals: Record<string, Record<string, string> | string>;
}

const KEY = ['intercompany'];

function useIcMutation<T>(fn: (input: T) => Promise<unknown>, success: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: fn,
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: KEY });
      qc.invalidateQueries({ queryKey: ['intercompany-companies'] });
      notify.success(success);
    },
  });
}

export function useCompanyGroup() {
  return useQuery({
    queryKey: [...KEY, 'group'],
    queryFn: async () => ((await api.get<CompanyGroup | ''>('/intercompany/group')).data || null) as CompanyGroup | null,
  });
}

export function useCreateGroup() {
  return useIcMutation(async (name: string) => (await api.post('/intercompany/group', { name })).data, tx('Company group created'));
}

export function useAddGroupMember() {
  return useIcMutation(
    async (input: { organizationCode: string; ownershipPercent?: number }) =>
      (await api.post('/intercompany/group/members', input)).data,
    tx('Company added to the group'),
  );
}

export function useUpdateGroupMember() {
  return useIcMutation(
    async ({ organizationId, ownershipPercent }: { organizationId: string; ownershipPercent: number }) =>
      (await api.patch(`/intercompany/group/members/${organizationId}`, { ownershipPercent })).data,
    tx('Ownership updated'),
  );
}

export function useRemoveGroupMember() {
  return useIcMutation(
    async (organizationId: string) => (await api.delete(`/intercompany/group/members/${organizationId}`)).data,
    tx('Company removed from the group'),
  );
}

export function useIntercompanyPartners(enabled: boolean) {
  return useQuery({
    queryKey: [...KEY, 'partners'],
    queryFn: async () => (await api.get<IntercompanyPartnerLink[]>('/intercompany/partners')).data,
    enabled,
  });
}

export function useLinkIntercompanyPartner() {
  return useIcMutation(
    async ({ counterpartOrganizationId, partnerId }: { counterpartOrganizationId: string; partnerId?: string }) =>
      (await api.put(`/intercompany/partners/${counterpartOrganizationId}`, partnerId ? { partnerId } : {})).data,
    tx('Intercompany partner linked'),
  );
}

export function useIntercompanySettings() {
  return useQuery({
    queryKey: [...KEY, 'settings'],
    queryFn: async () => (await api.get<IntercompanySettings>('/intercompany/settings')).data,
  });
}

export function useSaveIntercompanySettings() {
  return useIcMutation(
    async (input: { mirrorMode?: string; billExpenseAccountId?: string }) =>
      (await api.put('/intercompany/settings', input)).data,
    tx('Settings saved'),
  );
}

export function useIntercompanyTransactions(status?: string) {
  return useQuery({
    queryKey: [...KEY, 'transactions', status],
    queryFn: async () =>
      (await api.get<PaginatedResult<IntercompanyTransaction>>('/intercompany/transactions', {
        params: { pageSize: 100, ...(status ? { status } : {}) },
      })).data,
  });
}

export function useRetryIntercompanyTransaction() {
  return useIcMutation(async (id: string) => (await api.post(`/intercompany/transactions/${id}/retry`)).data, tx('Retried'));
}

export function useIntercompanyJournals() {
  return useQuery({
    queryKey: [...KEY, 'journals'],
    queryFn: async () =>
      (await api.get<PaginatedResult<IntercompanyJournal>>('/intercompany/journals', { params: { pageSize: 100 } })).data,
  });
}

export function usePostIntercompanyJournal() {
  return useIcMutation(
    async (input: {
      targetOrganizationId: string;
      date: string;
      description: string;
      amount: number;
      sourceAccountId: string;
      targetAccountCode: string;
    }) => (await idempotentPost('/intercompany/journals', input)).data,
    tx('Intercompany journal posted'),
  );
}

export function useIntercompanyReconciliation(enabled: boolean, asOf?: string) {
  return useQuery({
    queryKey: [...KEY, 'reconciliation', asOf],
    queryFn: async () =>
      (await api.get<{ currencyCode: string; pairs: ReconciliationPair[] }>('/intercompany/reconciliation', {
        params: asOf ? { asOf } : {},
      })).data,
    enabled,
  });
}

export function useConsolidation(enabled: boolean, statement: 'pl' | 'tb', from?: string, to?: string) {
  return useQuery({
    queryKey: [...KEY, 'consolidation', statement, from, to],
    queryFn: async () =>
      (await api.get<Consolidation>('/intercompany/consolidation', {
        params: { statement, ...(from ? { from } : {}), ...(to ? { to } : {}) },
      })).data,
    enabled,
  });
}

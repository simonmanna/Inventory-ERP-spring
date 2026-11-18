import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from '@/lib/api';
import { idempotentPost } from '@/lib/idempotent-request';

/* ADR-S073: recurring journal entries, deferral schedules, bank matching rules / allocations, asset impairment. */

// ---------------------------------------------------------------- recurring journal entries

export type Frequency = 'daily' | 'weekly' | 'monthly' | 'quarterly' | 'yearly';

export interface TemplateLine {
  accountId: string;
  debit?: number | null;
  credit?: number | null;
  description?: string | null;
  partnerId?: string | null;
}

export interface RecurringTemplate {
  id: string;
  name: string;
  journalCode: string;
  description: string | null;
  frequency: Frequency;
  intervalCount: number;
  startDate: string;
  endDate: string | null;
  nextRunDate: string | null;
  lastRunDate: string | null;
  mode: 'post' | 'draft';
  status: 'active' | 'paused' | 'ended';
  totalAmount: string;
  runCount: number;
  lines: TemplateLine[];
  createdBy: string | null;
  /** R3 maker-checker: who last changed what the template posts; a change clears the approval. */
  lastEditedBy: string | null;
  approvedBy: string | null;
  approvedAt: string | null;
}

export interface RecurringRun {
  id: string;
  runDate: string;
  status: 'posted' | 'draft' | 'failed';
  trigger: 'job' | 'manual';
  error: string | null;
  journalEntryId: string | null;
  entryNumber: string | null;
  entryStatus: string | null;
}

export interface TemplateInput {
  name: string;
  journalCode?: string;
  description?: string;
  frequency: Frequency;
  intervalCount?: number;
  startDate: string;
  endDate?: string;
  mode: 'post' | 'draft';
  lines: TemplateLine[];
}

const RK = 'recurring-entries';

export function useRecurringTemplates() {
  return useQuery({
    queryKey: [RK],
    queryFn: async () => (await api.get<RecurringTemplate[]>('/recurring-entries')).data,
  });
}

export function useRecurringRuns(id: string | undefined) {
  return useQuery({
    queryKey: [RK, id, 'runs'],
    queryFn: async () => (await api.get<RecurringRun[]>(`/recurring-entries/${id}/runs`)).data,
    enabled: !!id,
  });
}

function useRecurringMutation<V, R>(fn: (vars: V) => Promise<R>) {
  const qc = useQueryClient();
  return useMutation({ mutationFn: fn, onSuccess: () => qc.invalidateQueries({ queryKey: [RK] }) });
}

export function useSaveRecurringTemplate() {
  return useRecurringMutation(async (input: { id?: string; data: TemplateInput }) =>
    input.id
      ? (await api.patch<RecurringTemplate>(`/recurring-entries/${input.id}`, input.data)).data
      : (await api.post<RecurringTemplate>('/recurring-entries', input.data)).data,
  );
}

export function useRecurringAction() {
  return useRecurringMutation(async (input: { id: string; action: 'pause' | 'resume' }) =>
    (await api.post<RecurringTemplate>(`/recurring-entries/${input.id}/${input.action}`)).data,
  );
}

export function useRunRecurringNow() {
  return useRecurringMutation((input: { id: string; date?: string }) =>
    idempotentPost<RecurringRun & { replayed: boolean }>(`/recurring-entries/${input.id}/run`, input.date ? { date: input.date } : {}),
  );
}

/** R3: approve a template so its post-mode occurrences post (until then they are staged as drafts). */
export function useApproveRecurringTemplate() {
  return useRecurringMutation(async (id: string) => (await api.post<RecurringTemplate>(`/recurring-entries/${id}/approve`)).data);
}

export function useDeleteRecurringTemplate() {
  return useRecurringMutation(async (id: string) => (await api.delete(`/recurring-entries/${id}`)).data);
}

// ---------------------------------------------------------------- deferral schedules

export interface DeferralRelease {
  id: string;
  periodIndex: number;
  periodStart: string;
  periodEnd: string;
  amount: string;
  status: 'planned' | 'posted' | 'cancelled';
  journalEntryId: string | null;
  entryNumber: string | null;
}

export interface DeferralSchedule {
  id: string;
  name: string;
  kind: 'revenue' | 'expense';
  sourceAccountId: string;
  targetAccountId: string;
  sourceAccountCode?: string;
  sourceAccountName?: string;
  targetAccountCode?: string;
  targetAccountName?: string;
  totalAmount: string;
  releasedAmount: string;
  remainingAmount: string;
  startDate: string;
  months: number;
  method: 'monthly' | 'daily';
  status: 'active' | 'completed' | 'cancelled';
  journalLineId: string | null;
  createdBy: string | null;
  /** R3 maker-checker: a schedule posts nothing until approved by a second user. */
  approvedBy: string | null;
  approvedAt: string | null;
  releases?: DeferralRelease[];
}

export interface DeferralReport {
  asOf: string;
  totalRemaining: string;
  accounts: { accountId: string; accountCode: string; accountName: string; kind: string; scheduleRemaining: string; glBalance: string; difference: string }[];
  schedules: { id: string; name: string; kind: string; status: string; sourceAccountCode: string; totalAmount: string; released: string; remaining: string }[];
}

const DK = 'deferrals';

export function useDeferrals() {
  return useQuery({ queryKey: [DK], queryFn: async () => (await api.get<DeferralSchedule[]>('/deferrals')).data });
}

export function useDeferral(id: string | undefined) {
  return useQuery({
    queryKey: [DK, id],
    queryFn: async () => (await api.get<DeferralSchedule>(`/deferrals/${id}`)).data,
    enabled: !!id,
  });
}

export function useDeferralReport(asOf: string) {
  return useQuery({
    queryKey: [DK, 'report', asOf],
    queryFn: async () => (await api.get<DeferralReport>('/deferrals/report', { params: { asOf } })).data,
  });
}

function useDeferralMutation<V, R>(fn: (vars: V) => Promise<R>) {
  const qc = useQueryClient();
  return useMutation({ mutationFn: fn, onSuccess: () => qc.invalidateQueries({ queryKey: [DK] }) });
}

export function useCreateDeferral() {
  return useDeferralMutation(async (data: Record<string, unknown>) =>
    data.journalLineId
      ? idempotentPost<DeferralSchedule>('/deferrals/from-journal-line', data)
      : (await api.post<DeferralSchedule>('/deferrals', data)).data,
  );
}

export function useReleaseDeferral() {
  return useDeferralMutation((input: { id?: string; asOf?: string }) =>
    idempotentPost<Record<string, unknown>>(input.id ? `/deferrals/${input.id}/release` : '/deferrals/run', input.asOf ? { asOf: input.asOf } : {}),
  );
}

/** R3: approve a schedule (posts the reclassification of a deferred P&L line, then allows releases). */
export function useApproveDeferral() {
  return useDeferralMutation((id: string) => idempotentPost<DeferralSchedule>(`/deferrals/${id}/approve`, {}));
}

export function useCancelDeferral() {
  return useDeferralMutation(async (input: { id: string; reason?: string }) =>
    (await api.post<DeferralSchedule>(`/deferrals/${input.id}/cancel`, { reason: input.reason })).data,
  );
}

// ---------------------------------------------------------------- bank matching rules and allocations

export interface BankRule {
  id: string;
  bankAccountId: string;
  name: string;
  priority: number;
  isActive: boolean;
  matchField: 'description' | 'reference' | 'any';
  matchType: 'contains' | 'regex';
  pattern: string | null;
  minAmount: string | null;
  maxAmount: string | null;
  direction: 'inbound' | 'outbound' | 'any';
  action: 'match_payment' | 'post_entry';
  counterAccountId: string | null;
  counterAccountCode?: string | null;
  taxId: string | null;
  partnerId: string | null;
  dateToleranceDays: number;
}

export interface OpenPayment {
  id: string;
  paymentNumber: string;
  paymentDate: string;
  amount: string;
  openAmount: string;
  direction: string;
  partnerName: string | null;
}

const BK = 'bank-reconciliation';

export function useBankRules(bankAccountId: string) {
  return useQuery({
    queryKey: [BK, 'rules', bankAccountId],
    queryFn: async () => (await api.get<BankRule[]>('/bank-reconciliation/rules', { params: { bankAccountId } })).data,
    enabled: !!bankAccountId,
  });
}

export function useOpenPayments(lineId: string | undefined) {
  return useQuery({
    queryKey: [BK, 'open-payments', lineId],
    queryFn: async () => (await api.get<OpenPayment[]>(`/bank-reconciliation/lines/${lineId}/open-payments`)).data,
    enabled: !!lineId,
  });
}

function useBankMutation<V, R>(fn: (vars: V) => Promise<R>) {
  const qc = useQueryClient();
  return useMutation({ mutationFn: fn, onSuccess: () => qc.invalidateQueries({ queryKey: [BK] }) });
}

export function useSaveBankRule() {
  return useBankMutation(async (input: { id?: string; data: Partial<BankRule> }) =>
    input.id
      ? (await api.patch<BankRule>(`/bank-reconciliation/rules/${input.id}`, input.data)).data
      : (await api.post<BankRule>('/bank-reconciliation/rules', input.data)).data,
  );
}

export function useDeleteBankRule() {
  return useBankMutation(async (id: string) => (await api.delete(`/bank-reconciliation/rules/${id}`)).data);
}

export function useApplyBankRules() {
  return useBankMutation((bankAccountId: string) =>
    idempotentPost<{ matched: number; posted: number; skipped: number; failures: { lineId: string; error: string }[] }>(
      `/bank-reconciliation/rules/apply?bankAccountId=${encodeURIComponent(bankAccountId)}`,
    ),
  );
}

export function useAllocateLine() {
  return useBankMutation((input: { lineId: string; allocations: { paymentId: string; amount?: number }[] }) =>
    idempotentPost<{ status: string }>(`/bank-reconciliation/lines/${input.lineId}/allocate`, { allocations: input.allocations }),
  );
}

export function useAllocatePayment() {
  return useBankMutation((input: { paymentId: string; lineIds: string[] }) =>
    idempotentPost(`/bank-reconciliation/payments/${input.paymentId}/allocate`, { lineIds: input.lineIds }),
  );
}

// ---------------------------------------------------------------- asset impairment

export interface ImpairmentSummary {
  assetId: string;
  carryingAmount: string;
  accumulatedImpairment: string;
  maxReversal: string;
  impairments: {
    id: string;
    kind: 'impairment' | 'reversal';
    impairmentDate: string;
    carryingBefore: string;
    recoverableAmount: string;
    amount: string;
    carryingAfter: string;
    remainingLifeMonths: number;
    reason: string | null;
    entryNumber: string | null;
  }[];
}

export function useAssetImpairments(assetId: string) {
  return useQuery({
    queryKey: ['fixed-asset', assetId, 'impairments'],
    queryFn: async () => (await api.get<ImpairmentSummary>(`/fixed-assets/${assetId}/impairments`)).data,
    enabled: !!assetId,
  });
}

export function useRecordImpairment() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (input: { assetId: string; reversal: boolean; recoverableAmount: number; date?: string; reason?: string }) =>
      idempotentPost(`/fixed-assets/${input.assetId}/impairments${input.reversal ? '/reversal' : ''}`, {
        recoverableAmount: input.recoverableAmount,
        date: input.date || undefined,
        reason: input.reason || undefined,
      }),
    onSuccess: (_, { assetId }) => qc.invalidateQueries({ queryKey: ['fixed-asset', assetId] }),
  });
}

import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import type { PaginatedResult } from '@erp/shared';
import { api } from '@/lib/api';
import { idempotentPost } from '@/lib/idempotent-request';
import { apiMessage, notify } from '@/lib/notify';
import { tx } from '@/lib/i18n/tx';

// ADR-S005 — budgets, opening balances, tax summary, statements, consolidation, invoice e-mail.

export interface BudgetLine {
  id?: string;
  accountId: string;
  accountCode?: string;
  accountName?: string;
  period: string;
  amount: string | number;
  notes?: string | null;
}

export interface Budget {
  id: string;
  name: string;
  fiscalYear: number;
  startDate: string;
  endDate: string;
  branchId?: string | null;
  costCenterId?: string | null;
  status: 'draft' | 'approved';
  notes?: string | null;
  approvedAt?: string | null;
  totalAmount: string;
  lineCount?: number;
  lines?: BudgetLine[];
}

export interface BudgetVsActualRow {
  accountId: string;
  code: string;
  name: string;
  type: 'income' | 'expense';
  budget: string;
  actual: string;
  variance: string;
  variancePct: string | null;
  utilizationPct: string | null;
  favorable: boolean;
  months: { period: string; budget: string; actual: string }[];
}

export interface BudgetVsActual {
  from: string;
  to: string;
  months: string[];
  rows: BudgetVsActualRow[];
  totals: Record<'income' | 'expense' | 'net', { budget: string; actual: string; variance: string }>;
}

export function useBudgets(params: { page?: number; pageSize?: number; search?: string; status?: string }) {
  return useQuery({
    queryKey: ['budgets', params],
    queryFn: async () => (await api.get<PaginatedResult<Budget>>('/budgets', { params })).data,
  });
}

export function useBudget(id: string | undefined) {
  return useQuery({
    queryKey: ['budget', id],
    enabled: !!id,
    queryFn: async () => (await api.get<Budget>(`/budgets/${id}`)).data,
  });
}

export function useBudgetVsActual(id: string | undefined, params: { from?: string; to?: string }) {
  return useQuery({
    queryKey: ['budget-vs-actual', id, params],
    enabled: !!id,
    queryFn: async () => (await api.get<BudgetVsActual>(`/budgets/${id}/vs-actual`, { params })).data,
  });
}

function useBudgetMutation<T>(fn: (input: T) => Promise<unknown>, success: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: fn,
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['budgets'] });
      qc.invalidateQueries({ queryKey: ['budget'] });
      qc.invalidateQueries({ queryKey: ['budget-vs-actual'] });
      notify.success(success);
    },
    onError: (e) => notify.error(apiMessage(e, tx('Request failed'))),
  });
}

export function useCreateBudget() {
  return useBudgetMutation(
    async (body: { name: string; fiscalYear: number; branchId?: string; costCenterId?: string; notes?: string }) =>
      (await api.post<Budget>('/budgets', body)).data,
    tx('Budget created'),
  );
}

export function useSaveBudgetLines(id: string) {
  return useBudgetMutation(
    async (lines: BudgetLine[]) =>
      (await api.put<Budget>(`/budgets/${id}/lines`, {
        lines: lines.map((l) => ({ accountId: l.accountId, period: l.period, amount: Number(l.amount) })),
      })).data,
    tx('Budget lines saved'),
  );
}

export function useBudgetAction(id: string) {
  return useBudgetMutation(
    async (action: 'approve' | 'reopen') => (await api.post<Budget>(`/budgets/${id}/${action}`)).data,
    tx('Budget updated'),
  );
}

export function useDeleteBudget() {
  return useBudgetMutation(async (id: string) => api.delete(`/budgets/${id}`), tx('Budget deleted'));
}

// ---------------------------------------------------------------- opening balances

export interface OpeningBalancePayload {
  date: string;
  reference?: string;
  accounts: { accountId: string; debit?: number; credit?: number; description?: string }[];
  customers: { partnerId: string; amount: number; dueDate?: string; reference?: string }[];
  suppliers: { partnerId: string; amount: number; dueDate?: string; reference?: string }[];
}

export interface OpeningBalanceList {
  entries: { id: string; entryNumber: string; postingDate: string; description: string; total: string; status: string }[];
  documents: {
    id: string;
    documentNumber: string;
    documentType: string;
    partnerName: string;
    issueDate: string;
    totalAmount: string;
    amountResidual: string;
    status: string;
  }[];
}

export function useOpeningBalances() {
  return useQuery({
    queryKey: ['opening-balances'],
    queryFn: async () => (await api.get<OpeningBalanceList>('/opening-balances')).data,
  });
}

export function usePostOpeningBalances() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (body: OpeningBalancePayload) => (await idempotentPost('/opening-balances', body)).data,
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['opening-balances'] });
      qc.invalidateQueries({ queryKey: ['trial-balance'] });
      notify.success(tx('Opening balances posted'));
    },
    onError: (e) => notify.error(apiMessage(e, tx('Could not post opening balances'))),
  });
}

// ---------------------------------------------------------------- tax

export interface TaxLine {
  taxId: string | null;
  name: string;
  code: string | null;
  rate: string | null;
  vatCategory: string;
  taxableAmount: string;
  taxAmount: string;
  documents: number;
}

export interface TaxSide {
  lines: TaxLine[];
  taxableTotal: string;
  taxTotal: string;
  untaxedAmount: string;
  byCategory: Record<string, string>;
}

export interface TaxSummary {
  output: TaxSide;
  input: TaxSide;
  netTaxPayable: string;
  position: 'payable' | 'refundable';
  gl: { accounts: { accountId: string; code: string; name: string; debit: string; credit: string; net: string }[]; net: string };
}

export function useTaxSummary(params: { from?: string; to?: string; branchId?: string }) {
  return useQuery({
    queryKey: ['tax-summary', params],
    queryFn: async () => (await api.get<TaxSummary>('/reports/tax/summary', { params })).data,
  });
}

// ---------------------------------------------------------------- statements

export interface Statement {
  side: 'ar' | 'ap';
  partner: { id: string; name: string; email?: string | null };
  openingBalance: string;
  totalDebit: string;
  totalCredit: string;
  closingBalance: string;
  lines: {
    date: string;
    entryId: string;
    reference: string;
    type: string;
    description: string | null;
    dueDate: string | null;
    debit: string;
    credit: string;
    balance: string;
  }[];
  aging: {
    buckets: Record<'current' | 'd1_30' | 'd31_60' | 'd61_90' | 'd90_plus', string>;
    totalOpen: string;
    documents: { documentNumber: string; dueDate: string | null; residual: string; daysOverdue: number }[];
  };
}

export function statementPath(side: 'ar' | 'ap', partnerId: string) {
  return side === 'ar' ? `/reports/ar/customers/${partnerId}/statement` : `/reports/ap/suppliers/${partnerId}/statement`;
}

export function useStatement(side: 'ar' | 'ap', partnerId: string | undefined, params: { from?: string; to?: string }) {
  return useQuery({
    queryKey: ['statement', side, partnerId, params],
    enabled: !!partnerId,
    queryFn: async () => (await api.get<Statement>(statementPath(side, partnerId!), { params })).data,
  });
}

/** Fetch a PDF through the authenticated client and open it in a new tab. */
export async function openPdf(path: string, params?: Record<string, string | undefined>) {
  try {
    const res = await api.get(path, { params, responseType: 'blob' });
    const url = URL.createObjectURL(new Blob([res.data], { type: 'application/pdf' }));
    window.open(url, '_blank', 'noopener');
    setTimeout(() => URL.revokeObjectURL(url), 60_000);
  } catch (e) {
    notify.error(apiMessage(e, tx('Could not open the PDF')));
  }
}

export function useEmailStatement() {
  return useMutation({
    mutationFn: async (input: { partnerId: string; from?: string; to?: string; recipient?: string; message?: string }) =>
      (
        await api.post<{ ok: boolean; sentTo: string; message: string }>(
          `/reports/ar/customers/${input.partnerId}/statement/email`,
          { to: input.recipient || undefined, message: input.message || undefined },
          { params: { from: input.from, to: input.to } },
        )
      ).data,
    onSuccess: (r) => notify.success(r.message),
    onError: (e) => notify.error(apiMessage(e, tx('Could not email the statement'))),
  });
}

export function useEmailInvoice() {
  return useMutation({
    mutationFn: async (input: { id: string; to?: string; message?: string }) =>
      (
        await api.post<{ ok: boolean; sentTo: string; message: string }>(`/invoices/${input.id}/email`, {
          to: input.to || undefined,
          message: input.message || undefined,
        })
      ).data,
    onSuccess: (r) => notify.success(r.message),
    onError: (e) => notify.error(apiMessage(e, tx('Could not email the invoice'))),
  });
}

// ---------------------------------------------------------------- consolidation

export interface Consolidated {
  statement: 'pl' | 'tb';
  branches: { id: string; code: string; name: string }[];
  rows: { accountId: string; code: string; name: string; type: string; byBranch: Record<string, string>; total: string }[];
  totals: Record<string, { byBranch: Record<string, string>; total: string }>;
}

export function useConsolidated(params: { from?: string; to?: string; statement: 'pl' | 'tb' }) {
  return useQuery({
    queryKey: ['consolidated', params],
    queryFn: async () => (await api.get<Consolidated>('/reports/accounting/consolidated', { params })).data,
  });
}

export interface BranchOption {
  id: string;
  code: string;
  name: string;
}

export function useBranchOptions() {
  return useQuery({
    queryKey: ['branch-options'],
    queryFn: async () => (await api.get<PaginatedResult<BranchOption>>('/branches', { params: { pageSize: 200 } })).data.data,
  });
}

/** Local calendar date as YYYY-MM-DD (no UTC shift). */
export function localDay(d: Date = new Date()): string {
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
}

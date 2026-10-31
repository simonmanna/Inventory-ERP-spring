import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import type { PaginatedResult } from '@erp/shared';
import { api } from '@/lib/api';
import { idempotentPost } from '@/lib/idempotent-request';

export interface StatementLineRow {
  id: string;
  postedAt: string;
  externalRef: string | null;
  description: string;
  amount: string;
  currencyCode: string;
  status: 'unmatched' | 'matched' | 'excluded';
  note: string | null;
  matchedPaymentId: string | null;
  matchedPaymentNumber: string | null;
}

export interface BankReconciliationSummary {
  bookBalance: string;
  statementTotal: string;
  matchedTotal: string;
  unmatchedTotal: string;
  unmatchedCount: number;
  excludedTotal: string;
  excludedCount: number;
  outstandingInbound: string;
  outstandingOutbound: string;
  outstandingPayments: number;
}

export interface PaymentCandidate {
  id: string;
  paymentNumber: string;
  paymentDate: string;
  amount: string;
  direction: string;
  reference: string | null;
  partnerName: string | null;
}

export interface ReconciliationRun {
  id: string;
  startedAt: string;
  finishedAt: string | null;
  matched: number;
  unmatched: number;
  totalAmount: string;
  notes: string | null;
}

export interface ParsedStatementLine {
  postedAt: string;
  externalRef: string;
  description: string;
  amount: number;
}

/** One CSV row into cells; a quoted cell may hold commas and doubled quotes. */
function csvCells(row: string): string[] {
  const cells: string[] = [];
  let cell = '';
  let quoted = false;
  for (let i = 0; i < row.length; i++) {
    const c = row[i];
    if (c === '"') {
      if (quoted && row[i + 1] === '"') {
        cell += '"';
        i++;
      } else {
        quoted = !quoted;
      }
    } else if (c === ',' && !quoted) {
      cells.push(cell.trim());
      cell = '';
    } else {
      cell += c;
    }
  }
  cells.push(cell.trim());
  return cells;
}

/**
 * Generic statement CSV: `date (YYYY-MM-DD), reference, description, amount`, outflows negative. A header row is
 * skipped. A row without a reference gets a stable one, so importing the same file twice adds nothing.
 */
export function parseStatementCsv(text: string): { lines: ParsedStatementLine[]; errors: number[] } {
  const lines: ParsedStatementLine[] = [];
  const errors: number[] = [];
  const seen = new Map<string, number>();
  text.split(/\r?\n/).forEach((raw, index) => {
    if (!raw.trim()) return;
    const [date = '', reference = '', description = '', amountText = ''] = csvCells(raw);
    const amount = Number(amountText.replace(/,/g, ''));
    const valid = /^\d{4}-\d{2}-\d{2}$/.test(date) && amountText !== '' && Number.isFinite(amount);
    if (!valid) {
      if (index > 0) errors.push(index + 1);
      return;
    }
    const base = `${date}|${amount}|${description}`;
    const occurrence = (seen.get(base) ?? 0) + 1;
    seen.set(base, occurrence);
    lines.push({
      postedAt: date,
      externalRef: reference || `${base}|${occurrence}`,
      description: description || reference || date,
      amount,
    });
  });
  return { lines, errors };
}

const KEY = 'bank-reconciliation';

export function useStatementLines(params: {
  bankAccountId: string;
  status?: string;
  search?: string;
  page: number;
  pageSize: number;
}) {
  return useQuery({
    queryKey: [KEY, 'lines', params],
    queryFn: async () =>
      (await api.get<PaginatedResult<StatementLineRow>>('/bank-reconciliation/lines', { params })).data,
    enabled: !!params.bankAccountId,
  });
}

export function useBankReconciliationSummary(bankAccountId: string) {
  return useQuery({
    queryKey: [KEY, 'summary', bankAccountId],
    queryFn: async () =>
      (await api.get<BankReconciliationSummary>('/bank-reconciliation/summary', { params: { bankAccountId } })).data,
    enabled: !!bankAccountId,
  });
}

export function useReconciliationRuns(bankAccountId: string) {
  return useQuery({
    queryKey: [KEY, 'runs', bankAccountId],
    queryFn: async () =>
      (await api.get<ReconciliationRun[]>('/bank-reconciliation/runs', { params: { bankAccountId } })).data,
    enabled: !!bankAccountId,
  });
}

export function usePaymentCandidates(lineId: string | undefined) {
  return useQuery({
    queryKey: [KEY, 'candidates', lineId],
    queryFn: async () => (await api.get<PaymentCandidate[]>(`/bank-reconciliation/lines/${lineId}/candidates`)).data,
    enabled: !!lineId,
  });
}

function useReconciliationMutation<V, R>(fn: (vars: V) => Promise<R>) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: fn,
    onSuccess: () => qc.invalidateQueries({ queryKey: [KEY] }),
  });
}

export function useImportStatement() {
  return useReconciliationMutation(
    (input: { bankAccountId: string; currencyCode: string; lines: ParsedStatementLine[] }) =>
      idempotentPost<{ imported: number; skipped: number }>('/bank-reconciliation/import', input),
  );
}

export function useAutoMatch() {
  return useReconciliationMutation((input: { bankAccountId: string; dateToleranceDays: number }) =>
    idempotentPost<{ matched: number; unmatched: number }>(
      `/bank-reconciliation/match?bankAccountId=${encodeURIComponent(input.bankAccountId)}`,
      { dateToleranceDays: input.dateToleranceDays, signed: true },
    ),
  );
}

export function useMatchLine() {
  return useReconciliationMutation((input: { lineId: string; paymentId: string }) =>
    idempotentPost(`/bank-reconciliation/lines/${input.lineId}/match`, { paymentId: input.paymentId }),
  );
}

export function useExcludeLine() {
  return useReconciliationMutation((input: { lineId: string; note: string }) =>
    idempotentPost(`/bank-reconciliation/lines/${input.lineId}/exclude`, { note: input.note }),
  );
}

export function useUnmatchLine() {
  return useReconciliationMutation((lineId: string) => idempotentPost(`/bank-reconciliation/unmatch/${lineId}`));
}

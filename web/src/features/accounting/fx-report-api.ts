import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from '@/lib/api';
import { idempotentPost } from '@/lib/idempotent-request';

/** FX-7 (ADR-S082): FX gain / loss report grouping. */
export type FxReportGroupBy = 'partner' | 'account' | 'currency' | 'origin' | 'document';

/** Amounts are decimal strings in the functional currency; positive = gain. */
export interface FxReportRow {
  key: string | null;
  label: string;
  realised: string;
  unrealised: string;
  total: string;
  entries: number;
}

export interface FxReport {
  functionalCurrency: string;
  groupBy: FxReportGroupBy;
  realised: string;
  unrealised: string;
  total: string;
  rows: FxReportRow[];
}

export interface FxReportAllocation {
  documentId: string;
  documentNumber: string | null;
  amount: string;
  carryingRate: string;
  settlementRate: string;
  fxAmount: string;
}

export interface FxReportLine {
  journalEntryId: string;
  entryNumber: string;
  postingDate: string;
  description: string | null;
  sourceType: string | null;
  sourceId: string | null;
  kind: 'realised' | 'unrealised';
  partnerId: string | null;
  partnerName: string | null;
  accountId: string | null;
  accountCode: string | null;
  accountName: string | null;
  currency: string | null;
  gain: string;
  allocations?: FxReportAllocation[];
}

export interface FxReportLineFilter {
  from: string;
  to: string;
  kind?: 'realised' | 'unrealised';
  partnerId?: string;
  accountId?: string;
  currency?: string;
}

export function useFxReport(from: string, to: string, groupBy: FxReportGroupBy, enabled = true) {
  return useQuery({
    queryKey: ['fx-report', from, to, groupBy],
    enabled: enabled && !!from && !!to,
    queryFn: async () => (await api.get<FxReport>('/fx-treasury/report', { params: { from, to, groupBy } })).data,
  });
}

export function useFxReportLines(filter: FxReportLineFilter | null) {
  return useQuery({
    queryKey: ['fx-report-lines', filter],
    enabled: !!filter,
    queryFn: async () => (await api.get<FxReportLine[]>('/fx-treasury/report/lines', { params: filter ?? {} })).data,
  });
}

/** FX-8: a used currency without an approved rate dated within `maxAgeDays`. */
export interface MissingRate {
  currency: string;
  latestRateDate: string | null;
}

export function useMissingRates(maxAgeDays = 3, enabled = true) {
  return useQuery({
    queryKey: ['currency-rates', 'missing', maxAgeDays],
    enabled,
    queryFn: async () => (await api.get<MissingRate[]>('/currencies/rates/missing', { params: { maxAgeDays } })).data,
  });
}

export function useRateFetchStatus(enabled = true) {
  return useQuery({
    queryKey: ['currency-rates', 'fetch-status'],
    enabled,
    queryFn: async () => (await api.get<{ configured: boolean }>('/currencies/rates/fetch-status')).data,
  });
}

export interface RateFetchResult {
  functionalCurrency: string;
  rateType: string;
  stored: { currency: string; rate: string | number }[];
  /** Currencies whose manual rate for today is kept. */
  skipped: string[];
  /** Currencies the provider does not offer. */
  unavailable: string[];
}

export function useFetchRates() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: () => idempotentPost<RateFetchResult>('/currencies/rates/fetch', {}),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['currency-rates'] });
    },
  });
}

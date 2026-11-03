import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from '@/lib/api';
import { idempotentPost } from '@/lib/idempotent-request';
import { apiMessage, notify } from '@/lib/notify';
import { tx } from '@/lib/i18n/tx';

/** One (account, currency) revaluation line (ACC-2, `/fx-revaluations/preview`). */
export interface FxRevaluationLine {
  accountId: string;
  accountCode: string;
  currencyCode: string;
  foreignBalance: string | number;
  carryingBase: string | number;
  rate: string | number;
  revaluedBase: string | number;
  adjustment: string | number;
  alreadyBooked: string | number;
  toPost: string | number;
}

export function useFxRevaluationPreview(asOf: string) {
  return useQuery({
    queryKey: ['fx-revaluation-preview', asOf],
    enabled: !!asOf,
    queryFn: async () => (await api.post<FxRevaluationLine[]>('/fx-revaluations/preview', { asOf })).data,
  });
}

export function useRunFxRevaluation() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (asOf: string) =>
      await idempotentPost<{ revalued: number; totalGain: string }>('/fx-revaluations', { asOf }),
    onSuccess: (r) => {
      notify.success(tx('FX revaluation posted'), tx('{{v1}} balances revalued, net {{v2}}', { v1: r.revalued, v2: r.totalGain }));
      qc.invalidateQueries({ queryKey: ['fx-revaluation-preview'] });
    },
    onError: (e) => notify.error(tx('FX revaluation failed'), apiMessage(e, tx('Please try again'))),
  });
}

/** VAT for a period (ACC-7, `/tax-settlements/preview`). */
export interface TaxSettlementPreview {
  from: string;
  to: string;
  outputTax: string;
  inputTax: string;
  netPayable: string;
  settledThrough: string | null;
}

export function useTaxSettlementPreview(from: string, to: string) {
  return useQuery({
    queryKey: ['tax-settlement-preview', from, to],
    enabled: !!from && !!to,
    queryFn: async () =>
      (await api.get<TaxSettlementPreview>('/tax-settlements/preview', { params: { from, to } })).data,
  });
}

export function useSettleTax() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (body: { from: string; to: string }) =>
      await idempotentPost<TaxSettlementPreview>('/tax-settlements', body),
    onSuccess: () => {
      notify.success(tx('VAT period settled and locked'));
      qc.invalidateQueries({ queryKey: ['tax-settlement-preview'] });
    },
    onError: (e) => notify.error(tx('VAT settlement failed'), apiMessage(e, tx('Please try again'))),
  });
}

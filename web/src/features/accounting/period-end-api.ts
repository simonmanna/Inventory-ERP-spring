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
  partnerId?: string | null;
  partnerName?: string | null;
}

/** FX-2 C: a condition the revaluation run refuses on unless acknowledged. */
export interface FxRevaluationWarning {
  code: string;
  message: string;
  accountId: string;
  accountCode: string;
}

export function useFxRevaluationWarnings(asOf: string) {
  return useQuery({
    queryKey: ['fx-revaluation-warnings', asOf],
    enabled: !!asOf,
    queryFn: async () => (await api.post<FxRevaluationWarning[]>('/fx-revaluations/warnings', { asOf })).data,
  });
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
    mutationFn: async ({ asOf, acknowledgeWarnings }: { asOf: string; acknowledgeWarnings?: boolean }) =>
      await idempotentPost<{ revalued: number; totalGain: string }>('/fx-revaluations', { asOf, acknowledgeWarnings }),
    onSuccess: (r) => {
      notify.success(tx('FX revaluation posted'), tx('{{v1}} balances revalued, net {{v2}}', { v1: r.revalued, v2: r.totalGain }));
      qc.invalidateQueries({ queryKey: ['fx-revaluation-preview'] });
      qc.invalidateQueries({ queryKey: ['fx-revaluation-warnings'] });
    },
    onError: (e) => notify.error(tx('FX revaluation failed'), apiMessage(e, tx('Please try again'))),
  });
}

/** FX-2 C: a balance-sheet account with its revaluation setting and effective treatment (`/fx-revaluations/accounts`). */
export type FxRevaluationSetting = 'inherit' | 'monetary' | 'non_monetary';

export interface FxRevaluationAccount {
  id: string;
  code: string;
  name: string;
  categoryKey: string | null;
  fxRevaluation: FxRevaluationSetting;
  /** What `inherit` resolves to (from the account category). */
  monetaryDefault: boolean;
  /** The effective treatment: true = revalued at the closing rate. */
  monetary: boolean;
}

export function useFxRevaluationAccounts(enabled = true) {
  return useQuery({
    queryKey: ['fx-revaluation-accounts'],
    enabled,
    queryFn: async () => (await api.get<FxRevaluationAccount[]>('/fx-revaluations/accounts')).data,
  });
}

export function useSetFxRevaluationAccount() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async ({ id, fxRevaluation }: { id: string; fxRevaluation: FxRevaluationSetting }) =>
      (await api.put(`/fx-revaluations/accounts/${id}`, { fxRevaluation })).data,
    onSuccess: () => {
      notify.success(tx('Revaluation setting saved'));
      qc.invalidateQueries({ queryKey: ['fx-revaluation-accounts'] });
      qc.invalidateQueries({ queryKey: ['fx-revaluation-preview'] });
      qc.invalidateQueries({ queryKey: ['fx-revaluation-warnings'] });
    },
    onError: (e) => notify.error(apiMessage(e, tx('Could not save the revaluation setting'))),
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

/** A VAT settlement on record (`GET /tax-settlements`, newest first; a reversed one carries `reversedAt`). */
export interface TaxSettlement {
  id: string;
  periodFrom: string;
  periodTo: string;
  journalEntryId: string | null;
  reversedAt: string | null;
}

/**
 * Undo the latest VAT settlement in force (finance audit A3): reverses its offset entry and unlocks its period, which
 * can then be corrected and settled again.
 */
export function useUndoLatestTaxSettlement() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async () => {
      const settlements = (await api.get<TaxSettlement[]>('/tax-settlements')).data;
      const latest = settlements.find((s) => !s.reversedAt);
      if (!latest) throw new Error(tx('No VAT settlement to undo'));
      return await idempotentPost<TaxSettlement>(`/tax-settlements/${latest.id}/reverse`);
    },
    onSuccess: () => {
      notify.success(tx('VAT settlement undone; the period is unlocked'));
      qc.invalidateQueries({ queryKey: ['tax-settlement-preview'] });
    },
    onError: (e) =>
      notify.error(
        tx('Could not undo the VAT settlement'),
        apiMessage(e, e instanceof Error ? e.message : tx('Please try again')),
      ),
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

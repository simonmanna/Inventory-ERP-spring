import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from '@/lib/api';
import { idempotentPost } from '@/lib/idempotent-request';
import { notify, apiMessage } from '@/lib/notify';
import { tx } from '@/lib/i18n/tx';
import type { Num } from './api';

/**
 * Contract commercials of a project (CM-8b, ADR-S039): IFRS 15.35 criteria, variations, customer advances, progress
 * claims, subcontracts and valuations, retentions, revenue recognition runs, onerous-contract assessments and the
 * contract position. Rows are the raw table rows (camelCase columns); every list is a plain array.
 *
 * Routes that post to the ledger are `@Idempotent(required = true)`: they go through {@link idempotentPost}, so a
 * retry after a timeout or a double click reuses the same Idempotency-Key and the server replays the first result.
 */

export type Modification = 'cumulative_catch_up' | 'separate_contract' | 'prospective';
export type RetentionCondition = 'completion_certificate' | 'defects_period' | 'date_only';
export type OnerousIndicator = 'negative_margin' | 'eac_increase' | 'dispute' | 'penalty_notice' | 'management_flag';
export type RecognitionMethod = 'over_time_cost_to_cost' | 'point_in_time' | string;

export const MODIFICATIONS: Modification[] = ['cumulative_catch_up', 'separate_contract', 'prospective'];
export const RETENTION_CONDITIONS: RetentionCondition[] = ['completion_certificate', 'defects_period', 'date_only'];
export const ONEROUS_INDICATORS: OnerousIndicator[] = ['negative_margin', 'eac_increase', 'dispute', 'penalty_notice', 'management_flag'];
export const COST_ELEMENTS = ['material', 'labour', 'equipment', 'subcontract', 'other', 'overhead'] as const;

export interface CriteriaAssessment {
  id: string;
  projectId: string;
  criterionA: boolean;
  criterionB: boolean;
  criterionC: boolean;
  evidence: string;
  status: 'draft' | 'approved' | string;
  assessedBy?: string | null;
  approvedBy?: string | null;
  approvedAt?: string | null;
  createdAt?: string;
}

export interface Variation {
  id: string;
  projectId: string;
  number: string;
  description: string;
  priceDelta: Num;
  costDelta: Num;
  wbsId?: string | null;
  costElement?: string | null;
  modification: Modification;
  status: 'pending' | 'approved' | 'rejected';
  budgetId?: string | null;
  createdBy?: string | null;
  decidedBy?: string | null;
  decidedAt?: string | null;
  createdAt?: string;
}

export interface Advance {
  id: string;
  projectId: string;
  amount: Num;
  recoveryPct: Num;
  recovered: Num;
  /** amount - recovered (list route only). */
  outstanding?: Num;
  status: 'open' | 'recovered' | string;
  invoiceId?: string | null;
  createdAt?: string;
}

export interface Claim {
  id: string;
  projectId: string;
  number: string;
  claimDate: string;
  cumulativeCertified: Num;
  previousCertified: Num;
  thisClaim: Num;
  retentionPct: Num;
  retention: Num;
  retentionCondition: RetentionCondition;
  retentionReleaseDate?: string | null;
  advanceRecovery: Num;
  netAmount: Num;
  taxId?: string | null;
  status: 'draft' | 'certified' | 'cancelled';
  invoiceId?: string | null;
  retentionInvoiceId?: string | null;
  journalEntryId?: string | null;
  certifiedAt?: string | null;
  createdAt?: string;
}

export interface Subcontract {
  id: string;
  projectId: string;
  number: string;
  supplierId: string;
  wbsId: string;
  scope: string;
  value: Num;
  retentionPct: Num;
  retentionCondition: RetentionCondition;
  status: 'active' | 'completed' | 'cancelled' | string;
  createdAt?: string;
  /** List route only. */
  supplierName?: string | null;
  /** List route only: valuations oldest first. */
  valuations?: Valuation[];
}

export interface Valuation {
  id: string;
  subcontractId: string;
  number: string;
  valuationDate: string;
  cumulativeValue: Num;
  previousValue: Num;
  thisValuation: Num;
  retention: Num;
  netAmount: Num;
  billId?: string | null;
  journalEntryId?: string | null;
}

export interface Retention {
  id: string;
  projectId: string;
  side: 'customer' | 'subcontractor';
  partnerId: string;
  claimId?: string | null;
  valuationId?: string | null;
  amount: Num;
  backCharged: Num;
  releaseCondition: RetentionCondition;
  releaseDate?: string | null;
  classification: 'contract_asset' | 'receivable' | 'payable';
  status: 'held' | 'released';
  releaseDocumentId?: string | null;
  releasedAt?: string | null;
  createdAt?: string;
}

/** What a revenue run for a period end would post (the preview) — also the core of a posted run. */
export interface RevenueFigures {
  method: RecognitionMethod;
  transactionPrice: Num;
  costToDate: Num;
  estimatedTotalCost: Num;
  progress: Num;
  cumulativeRevenue: Num;
  revenueThisPeriod: Num;
  cumulativeCost: Num;
  costThisPeriod: Num;
}

export interface RevenueRun extends RevenueFigures {
  id: string;
  projectId: string;
  periodEnd: string;
  status: 'posted' | 'reversed';
  journalEntryId?: string | null;
  reversalEntryId?: string | null;
  createdAt?: string;
}

export interface OnerousAssessment {
  id: string;
  projectId: string;
  asOf: string;
  /** Comma-separated indicator codes. */
  indicators: string;
  remainingPrice: Num;
  probableClaims: Num;
  expectedBenefits: Num;
  incrementalCost: Num;
  directlyRelatedCost: Num;
  excludedCost: Num;
  costOfFulfilling: Num;
  costOfExiting?: Num | null;
  exitEvidence?: string | null;
  unavoidableCost: Num;
  impairmentRecognised: Num;
  impairmentReference?: string | null;
  provision: Num;
  previousProvision: Num;
  movement: Num;
  status: 'draft' | 'approved' | 'posted' | 'rejected';
  createdBy?: string | null;
  journalEntryId?: string | null;
  createdAt?: string;
}

export interface TieOut { subledger: Num; gl: Num; difference: Num }

export interface ContractPosition {
  projectId: string;
  method: RecognitionMethod;
  transactionPrice: Num;
  revenueRecognised: Num;
  grossBilled: Num;
  contractAsset: Num;
  contractLiability: Num;
  retentionContractAsset: Num;
  retentionReceivable: Num;
  retentionPayable: Num;
  advancesOutstanding: Num;
  onerousProvision: Num;
  glTieOut: {
    retentionContractAsset: TieOut;
    retentionPayable: TieOut;
    customerAdvances: TieOut;
    onerousProvision: TieOut;
  };
}

// ---- reads ----------------------------------------------------------------------------------------------------------

const KEY = ['contract'] as const;
const base = (projectId: string) => `/projects/${projectId}`;

function useList<T>(projectId: string, what: string, enabled: boolean) {
  return useQuery({
    queryKey: [...KEY, projectId, what],
    queryFn: async () => (await api.get<T[]>(`${base(projectId)}/${what}`)).data,
    enabled: !!projectId && enabled,
  });
}

export const useContractPosition = (projectId: string, enabled = true) =>
  useQuery({
    queryKey: [...KEY, projectId, 'position'],
    queryFn: async () => (await api.get<ContractPosition>(`${base(projectId)}/contract-position`)).data,
    enabled: !!projectId && enabled,
  });

export const useVariations = (projectId: string, enabled = true) => useList<Variation>(projectId, 'variations', enabled);
export const useAdvances = (projectId: string, enabled = true) => useList<Advance>(projectId, 'advances', enabled);
export const useSubcontracts = (projectId: string, enabled = true) => useList<Subcontract>(projectId, 'subcontracts', enabled);
export const useCriteriaAssessments = (projectId: string, enabled = true) =>
  useList<CriteriaAssessment>(projectId, 'revenue-criteria', enabled);
export const useClaims = (projectId: string, enabled = true) => useList<Claim>(projectId, 'claims', enabled);
export const useRetentions = (projectId: string, enabled = true) => useList<Retention>(projectId, 'retentions', enabled);
export const useRevenueRuns = (projectId: string, enabled = true) => useList<RevenueRun>(projectId, 'revenue-runs', enabled);
export const useOnerousAssessments = (projectId: string, enabled = true) =>
  useList<OnerousAssessment>(projectId, 'onerous-assessments', enabled);

export const useClaim = (claimId: string | null | undefined) =>
  useQuery({
    queryKey: [...KEY, 'claim', claimId],
    queryFn: async () => (await api.get<Claim>(`/projects/claims/${claimId}`)).data,
    enabled: !!claimId,
  });

/** Preview of a revenue run; `periodEnd` is a yyyy-mm-dd date not in the future. */
export const useRevenuePreview = (projectId: string, periodEnd: string, enabled = true) =>
  useQuery({
    queryKey: [...KEY, projectId, 'revenue-preview', periodEnd],
    queryFn: async () =>
      (await api.get<RevenueFigures>(`${base(projectId)}/revenue-runs/preview`, { params: { periodEnd } })).data,
    enabled: !!projectId && !!periodEnd && enabled,
    retry: false,
  });

// ---- writes ---------------------------------------------------------------------------------------------------------

type Write = { url: string; body?: unknown; money?: boolean };

/**
 * One mutation hook for every contract write: money routes use a stable Idempotency-Key, every error is toasted
 * verbatim (e.g. "A variation must be approved by someone other than its author"), and all contract queries plus the
 * project overview (job costing figures move with variations and valuations) are refreshed.
 */
export function useContractMutation<V = void, R = unknown>(build: (v: V) => Write, opts: { success?: string } = {}) {
  const qc = useQueryClient();
  return useMutation<R, unknown, V>({
    mutationFn: async (v: V) => {
      const { url, body, money } = build(v);
      if (money) return idempotentPost<R>(url, body ?? {});
      return (await api.post<R>(url, body ?? {})).data;
    },
    onSuccess: () => {
      if (opts.success) notify.success(opts.success);
      void qc.invalidateQueries({ queryKey: KEY });
      void qc.invalidateQueries({ queryKey: ['pm'] });
    },
    onError: (err) => notify.error(apiMessage(err, tx('Something went wrong'))),
  });
}

export interface CriteriaBody { criterionA: boolean; criterionB: boolean; criterionC: boolean; evidence: string }
export interface VariationBody {
  description: string;
  priceDelta: string;
  costDelta?: string;
  wbsId?: string;
  costElement?: string;
  modification?: Modification;
}
export interface AdvanceBody { amount: string; recoveryPct: string; date?: string }
export interface ClaimBody {
  claimDate?: string;
  cumulativeCertified: string;
  retentionPct?: string;
  retentionCondition?: RetentionCondition;
  retentionReleaseDate?: string;
  taxId?: string;
}
export interface SubcontractBody {
  supplierId: string;
  wbsId: string;
  scope?: string;
  value: string;
  retentionPct?: string;
  retentionCondition?: RetentionCondition;
}
export interface ValuationBody { cumulativeValue: string; date?: string }
export interface BackChargeBody { amount: string; reason?: string }
export interface OnerousBody {
  asOf?: string;
  indicators: OnerousIndicator[];
  probableClaims?: string;
  costOfExiting?: string;
  exitEvidence?: string;
  impairmentRecognised?: string;
  impairmentReference?: string;
}

/** Route builders, one per ContractController endpoint (paths and money flags mirror the backend). */
export const contractApi = {
  assessCriteria: (projectId: string, body: CriteriaBody): Write => ({ url: `${base(projectId)}/revenue-criteria`, body }),
  approveCriteria: (assessmentId: string): Write => ({ url: `/projects/revenue-criteria/${assessmentId}/approve` }),

  createVariation: (projectId: string, body: VariationBody): Write => ({ url: `${base(projectId)}/variations`, body }),
  approveVariation: (id: string): Write => ({ url: `/projects/variations/${id}/approve` }),
  rejectVariation: (id: string): Write => ({ url: `/projects/variations/${id}/reject` }),

  createAdvance: (projectId: string, body: AdvanceBody): Write => ({ url: `${base(projectId)}/advances`, body, money: true }),

  createClaim: (projectId: string, body: ClaimBody): Write => ({ url: `${base(projectId)}/claims`, body }),
  certifyClaim: (id: string): Write => ({ url: `/projects/claims/${id}/certify`, money: true }),
  cancelClaim: (id: string): Write => ({ url: `/projects/claims/${id}/cancel` }),

  createSubcontract: (projectId: string, body: SubcontractBody): Write => ({ url: `${base(projectId)}/subcontracts`, body }),
  valuation: (subcontractId: string, body: ValuationBody): Write => ({
    url: `/projects/subcontracts/${subcontractId}/valuations`, body, money: true,
  }),
  completeSubcontract: (id: string): Write => ({ url: `/projects/subcontracts/${id}/complete` }),
  cancelSubcontract: (id: string): Write => ({ url: `/projects/subcontracts/${id}/cancel` }),

  backCharge: (retentionId: string, body: BackChargeBody): Write => ({
    url: `/projects/retentions/${retentionId}/back-charge`, body, money: true,
  }),
  release: (retentionId: string, evidence?: string): Write => ({
    url: `/projects/retentions/${retentionId}/release`, body: evidence ? { evidence } : {}, money: true,
  }),

  runRevenue: (projectId: string, periodEnd: string): Write => ({ url: `${base(projectId)}/revenue-runs`, body: { periodEnd }, money: true }),
  reverseRun: (runId: string): Write => ({ url: `/projects/revenue-runs/${runId}/reverse`, money: true }),

  assessOnerous: (projectId: string, body: OnerousBody): Write => ({ url: `${base(projectId)}/onerous-assessments`, body }),
  approveOnerous: (id: string): Write => ({ url: `/projects/onerous-assessments/${id}/approve` }),
  rejectOnerous: (id: string): Write => ({ url: `/projects/onerous-assessments/${id}/reject` }),
  postOnerous: (id: string): Write => ({ url: `/projects/onerous-assessments/${id}/post`, money: true }),
};

// ---- labels ---------------------------------------------------------------------------------------------------------

export const modificationLabel = (m: string) => ({
  cumulative_catch_up: tx('Cumulative catch-up'),
  separate_contract: tx('Separate contract'),
  prospective: tx('Prospective'),
} as Record<string, string>)[m] ?? m;

export const conditionLabel = (c: string) => ({
  completion_certificate: tx('Practical completion certificate'),
  defects_period: tx('End of defects period'),
  date_only: tx('Release date only'),
} as Record<string, string>)[c] ?? c;

export const indicatorLabel = (i: string) => ({
  negative_margin: tx('Negative margin'),
  eac_increase: tx('Estimate at completion increase'),
  dispute: tx('Dispute'),
  penalty_notice: tx('Penalty notice'),
  management_flag: tx('Management flag'),
} as Record<string, string>)[i] ?? i;

export const methodLabel = (m: string) => ({
  over_time_cost_to_cost: tx('Over time (cost-to-cost)'),
  point_in_time: tx('Point in time'),
} as Record<string, string>)[m] ?? m;

/** Blank → undefined, so optional numeric fields are omitted rather than sent as "". */
export const opt = (v: string | null | undefined) => (v == null || v.trim() === '' ? undefined : v.trim());

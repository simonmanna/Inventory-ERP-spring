import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import type { PaginatedResult } from '@erp/shared';
import { api } from '@/lib/api';
import { idempotentPost } from '@/lib/idempotent-request';
import { tx } from '@/lib/i18n/tx';

// ---- Types (quality management, ADR-S013) ----

export type SpecStatus = 'draft' | 'active' | 'superseded' | 'archived';
export type ParamKind = 'numeric' | 'sieve' | 'boolean' | 'text';
export type Stage = 'incoming' | 'in_process' | 'final';
export type LotStatus = 'open' | 'hold' | 'quarantined' | 'closed' | 'cancelled';
export type LotResult = 'pending' | 'pass' | 'fail';
export type SamplingMethod = 'fixed' | 'percent' | 'sqrt' | 'per_quantity';
export type HoldPolicy = 'quarantine' | 'hold' | 'none';
export type NcrStatus = 'open' | 'investigating' | 'action' | 'closed' | 'cancelled';
export type Severity = 'minor' | 'major' | 'critical';
export type NcrSource = 'incoming' | 'in_process' | 'final' | 'customer' | 'supplier' | 'internal';
export type CapaStatus = 'open' | 'in_progress' | 'verification' | 'closed' | 'cancelled';

export interface SievePoint { sieveMm: string | number; minPassingPct: string | number | null; maxPassingPct: string | number | null }
export interface SieveReading { sieveMm: string | number; passingPct: string | number }

export interface SpecParameter {
  id: string;
  code: string;
  name: string;
  kind: ParamKind;
  unit: string | null;
  testMethod: string | null;
  minValue: string | null;
  maxValue: string | null;
  targetValue: string | null;
  decimals: number;
  expectedText: string | null;
  sieveEnvelope: SievePoint[];
  isCritical: boolean;
  sortOrder: number;
}

export interface Specification {
  id: string;
  code: string;
  version: number;
  name: string;
  productId: string | null;
  productName?: string | null;
  grade: string | null;
  status: SpecStatus;
  standard: string | null;
  effectiveFrom: string | null;
  notes: string | null;
  activatedAt: string | null;
  createdAt: string;
  parameterCount?: number;
  parameters?: SpecParameter[];
  versions?: Array<{ id: string; version: number; status: SpecStatus; activatedAt: string | null; createdAt: string }>;
}

export interface InspectionPlan {
  id: string;
  code: string;
  name: string;
  stage: Stage;
  productId: string;
  productName: string | null;
  productSku: string | null;
  supplierPartnerId: string | null;
  supplierName: string | null;
  stageKey: string | null;
  specificationId: string;
  specificationCode: string | null;
  specificationName: string | null;
  specificationVersion: number | null;
  specificationStatus: SpecStatus | null;
  activeSpecificationId?: string | null;
  samplingMethod: SamplingMethod;
  sampleSize: number | null;
  samplePct: string | null;
  perQuantity: string | null;
  minSamples: number;
  maxSamples: number | null;
  holdPolicy: HoldPolicy;
  independentRelease: boolean;
  autoNcr: boolean;
  isActive: boolean;
  notes: string | null;
}

export interface LotResultRow {
  id: string;
  parameterId: string;
  sampleNo: number;
  numericValue: string | null;
  textValue: string | null;
  sieveResults: SieveReading[];
  passed: boolean | null;
  source: string;
  labReference: string | null;
  testedAt: string | null;
}

export interface LotParameter extends SpecParameter { results: LotResultRow[] }

export interface EvaluationParam {
  parameterId: string;
  code: string;
  name: string;
  kind: ParamKind;
  status: 'pending' | 'pass' | 'fail' | 'deviation';
  samples: number;
  mean: string | null;
  min: string | null;
  max: string | null;
  messages: string[];
}

export interface LotDecision {
  id: string;
  decision: 'hold' | 'quarantine' | 'release' | 'reject' | 'downgrade' | 'cancel';
  quantity: string | null;
  fromLocationCode: string | null;
  toLocationCode: string | null;
  disposition: string | null;
  downgradeProductName: string | null;
  downgradeBatchNumber: string | null;
  downgradeQty: string | null;
  writeDownValue: string | null;
  ledgerCodes: string | null;
  journalEntryId: string | null;
  reason: string | null;
  concession: boolean;
  decidedByName: string | null;
  decidedAt: string;
}

export interface InspectionLot {
  id: string;
  lotNumber: string;
  stage: Stage;
  planId: string | null;
  planCode: string | null;
  specificationId: string | null;
  specificationCode: string | null;
  specificationVersion: number | null;
  specificationName: string | null;
  productId: string;
  productName: string | null;
  productSku: string | null;
  uom: string | null;
  batchNumber: string | null;
  quantity: string;
  openQty: string;
  releasedQty: string;
  rejectedQty: string;
  downgradedQty: string;
  locationId: string | null;
  locationCode: string | null;
  locationName: string | null;
  quarantineLocationId: string | null;
  stockControl: 'quality' | 'source';
  sourceType: string;
  sourceId: string | null;
  sourceRef: string | null;
  stageKey: string | null;
  supplierPartnerId: string | null;
  supplierName: string | null;
  workCenterName: string | null;
  sampleSize: number;
  status: LotStatus;
  result: LotResult;
  evaluation?: { result?: LotResult; failures?: number; deviations?: number; pending?: number; parameters?: EvaluationParam[] };
  independentRelease: boolean;
  inspectedBy: string | null;
  inspectedAt: string | null;
  closedAt: string | null;
  cancelReason: string | null;
  notes: string | null;
  createdAt: string;
  parameters?: LotParameter[];
  decisions?: LotDecision[];
  ncrs?: Array<{ id: string; ncrNumber: string; title: string; severity: Severity; status: NcrStatus; createdAt: string }>;
}

export interface Ncr {
  id: string;
  ncrNumber: string;
  title: string;
  description: string | null;
  source: NcrSource;
  severity: Severity;
  status: NcrStatus;
  lotId: string | null;
  lotNumber: string | null;
  productId: string | null;
  productName: string | null;
  batchNumber: string | null;
  supplierName: string | null;
  customerName: string | null;
  workCenterName: string | null;
  quantityAffected: string | null;
  disposition: string | null;
  rootCause: string | null;
  ownerUserId: string | null;
  ownerName: string | null;
  closedAt: string | null;
  closureNotes: string | null;
  capaCount: number;
  createdAt: string;
  capas?: Array<{ id: string; capaNumber: string; title: string; status: CapaStatus; dueDate: string | null }>;
}

export interface CapaAction {
  id: string;
  capaId: string;
  kind: 'corrective' | 'preventive';
  description: string;
  ownerUserId: string | null;
  ownerName: string | null;
  dueDate: string | null;
  status: 'open' | 'done' | 'cancelled';
  completedAt: string | null;
  notes: string | null;
  overdue: boolean;
}

export interface Capa {
  id: string;
  capaNumber: string;
  ncrId: string | null;
  ncrNumber: string | null;
  title: string;
  rootCause: string | null;
  rootCauseMethod: string | null;
  ownerUserId: string | null;
  ownerName: string | null;
  dueDate: string | null;
  status: CapaStatus;
  effectivenessNotes: string | null;
  verifiedAt: string | null;
  closedAt: string | null;
  actionCount: number;
  openActionCount: number;
  overdue: boolean;
  createdAt: string;
  actions?: CapaAction[];
}

export interface LabImport {
  id: string;
  adapter: string;
  filename: string | null;
  status: 'completed' | 'partial' | 'failed';
  rowsTotal: number;
  rowsImported: number;
  rowsRejected: number;
  errors: Array<{ row: number; lotNumber?: string; parameterCode?: string; message: string }>;
  lotIds: string[];
  importedByName?: string | null;
  createdAt: string;
}

export interface TraceNode {
  kind: 'batch' | 'goods_receipt' | 'supplier' | 'production_order' | 'material' | 'downgrade' | 'receipt' | 'delivery' | 'customer' | 'site' | 'issue';
  key: string;
  label: string | null;
  productId?: string;
  productName?: string | null;
  batchNumber?: string;
  quantity?: string | null;
  date?: string | null;
  ref?: string | null;
  deliveryOrder?: string | null;
  lots?: Array<{ id: string; lotNumber: string; stage: Stage; status: LotStatus; result: LotResult }>;
  cycle?: boolean;
  truncated?: boolean;
  untracked?: boolean;
  children: TraceNode[];
}

export interface ReportRow {
  key: string;
  label: string;
  lots: number;
  evaluated: number;
  passed: number;
  failed: number;
  passRate: string | null;
  quantity: string;
  releasedQty: string;
  rejectedQty: string;
  downgradedQty: string;
  defectQty: string;
  ncrCount: number;
}

export interface QualityReport {
  groupBy: string;
  from: string;
  to: string;
  rows: ReportRow[];
  totals: { lots: number; evaluated: number; passed: number; failed: number; passRate: string | null; ncrCount: number };
}

// ---- Labels ----

export const SPEC_STATUS_LABEL: Record<SpecStatus, string> = {
  draft: tx('Draft'), active: tx('Active'), superseded: tx('Superseded'), archived: tx('Archived'),
};
export const KIND_LABEL: Record<ParamKind, string> = {
  numeric: tx('Numeric'), sieve: tx('Sieve analysis'), boolean: tx('Yes / no'), text: tx('Text'),
};
export const STAGE_LABEL: Record<Stage, string> = {
  incoming: tx('Incoming'), in_process: tx('In-process'), final: tx('Final'),
};
export const LOT_STATUS_LABEL: Record<LotStatus, string> = {
  open: tx('Open'), hold: tx('On hold'), quarantined: tx('Quarantined'), closed: tx('Closed'), cancelled: tx('Cancelled'),
};
export const RESULT_LABEL: Record<LotResult | 'deviation', string> = {
  pending: tx('Pending'), pass: tx('Pass'), fail: tx('Fail'), deviation: tx('Deviation'),
};
export const SAMPLING_LABEL: Record<SamplingMethod, string> = {
  fixed: tx('Fixed number'), percent: tx('Percent of quantity'), sqrt: tx('√N + 1'), per_quantity: tx('One per quantity'),
};
export const HOLD_LABEL: Record<HoldPolicy, string> = {
  quarantine: tx('Move to quarantine'), hold: tx('Hold (no stock move)'), none: tx('None'),
};
export const NCR_STATUS_LABEL: Record<NcrStatus, string> = {
  open: tx('Open'), investigating: tx('Investigating'), action: tx('Action'), closed: tx('Closed'), cancelled: tx('Cancelled'),
};
export const SEVERITY_LABEL: Record<Severity, string> = { minor: tx('Minor'), major: tx('Major'), critical: tx('Critical') };
export const NCR_SOURCE_LABEL: Record<NcrSource, string> = {
  incoming: tx('Incoming'), in_process: tx('In-process'), final: tx('Final'), customer: tx('Customer'),
  supplier: tx('Supplier'), internal: tx('Internal'),
};
export const CAPA_STATUS_LABEL: Record<CapaStatus, string> = {
  open: tx('Open'), in_progress: tx('In progress'), verification: tx('Verification'), closed: tx('Closed'), cancelled: tx('Cancelled'),
};
export const ROOT_CAUSE_LABEL: Record<string, string> = {
  five_why: tx('5 Whys'), fishbone: tx('Fishbone (Ishikawa)'), fault_tree: tx('Fault tree'), other: tx('Other'),
};
export const DECISION_LABEL: Record<LotDecision['decision'], string> = {
  hold: tx('Hold'), quarantine: tx('Quarantine'), release: tx('Release'), reject: tx('Reject'), downgrade: tx('Downgrade'),
  cancel: tx('Cancel'),
};

// ---- Queries ----

const KEY = ['quality'] as const;
type Body = Record<string, unknown>;

function useQualityMutation<TVars, TResult = unknown>(fn: (vars: TVars) => Promise<TResult>) {
  const qc = useQueryClient();
  return useMutation({ mutationFn: fn, onSuccess: () => qc.invalidateQueries({ queryKey: KEY }) });
}

function useList<T>(path: string, params: object, enabled = true) {
  return useQuery({
    queryKey: [...KEY, path, params],
    enabled,
    placeholderData: (prev) => prev,
    queryFn: async () => (await api.get<PaginatedResult<T>>(path, { params })).data,
  });
}

function useOne<T>(path: string, id: string | undefined) {
  return useQuery({
    queryKey: [...KEY, path, id],
    enabled: !!id,
    queryFn: async () => (await api.get<T>(`${path}/${id}`)).data,
  });
}

export interface PageParams { page: number; pageSize: number; search?: string }

export const useSpecifications = (p: PageParams & { status?: string; productId?: string }) =>
  useList<Specification>('/quality/specifications', p);
export const useSpecification = (id?: string) => useOne<Specification>('/quality/specifications', id);
export const useInspectionPlans = (p: PageParams & { stage?: string; productId?: string; active?: boolean }) =>
  useList<InspectionPlan>('/quality/inspection-plans', p);
export const useInspectionLots = (p: PageParams & { status?: string; stage?: string; result?: string; productId?: string; batchNumber?: string }) =>
  useList<InspectionLot>('/quality/inspection-lots', p);
export const useInspectionLot = (id?: string) => useOne<InspectionLot>('/quality/inspection-lots', id);
export const useNcrs = (p: PageParams & { status?: string; severity?: string; source?: string; lotId?: string }) =>
  useList<Ncr>('/quality/ncrs', p);
export const useNcr = (id?: string) => useOne<Ncr>('/quality/ncrs', id);
export const useCapas = (p: PageParams & { status?: string; ncrId?: string; overdue?: boolean }) =>
  useList<Capa>('/quality/capas', p);
export const useCapa = (id?: string) => useOne<Capa>('/quality/capas', id);
export const useLabImports = (p: { page: number; pageSize: number }) => useList<LabImport>('/quality/lab-imports', p);

export function useLabAdapters() {
  return useQuery({
    queryKey: [...KEY, 'lab-adapters'],
    queryFn: async () => (await api.get<Array<{ code: string; configured: boolean; fetch: boolean }>>('/quality/lab-adapters')).data,
  });
}

export function useOverdueCapa() {
  return useQuery({
    queryKey: [...KEY, 'overdue'],
    queryFn: async () => (await api.get<{ asOf: string; actions: Array<CapaAction & { capaNumber: string; capaTitle: string; daysOverdue: number }>; capas: Array<Capa & { daysOverdue: number }> }>('/quality/capa-actions/overdue')).data,
  });
}

export function useTraceability(params: { productId: string; batchNumber: string; direction: 'backward' | 'forward'; depth?: number } | null) {
  return useQuery({
    queryKey: [...KEY, 'trace', params],
    enabled: !!params,
    queryFn: async () => (await api.get<{ direction: string; root: TraceNode }>('/quality/traceability', { params: params ?? undefined })).data,
  });
}

export function useQualityReport(params: { groupBy: string; stage?: string; from?: string; to?: string }) {
  return useQuery({
    queryKey: [...KEY, 'report', params],
    placeholderData: (prev) => prev,
    queryFn: async () => (await api.get<QualityReport>('/quality/reports/summary', { params })).data,
  });
}

// ---- Mutations ----

export const useCreateSpecification = () =>
  useQualityMutation(async (body: Body) => (await api.post<Specification>('/quality/specifications', body)).data);
export const useUpdateSpecification = () =>
  useQualityMutation(async ({ id, body }: { id: string; body: Body }) => (await api.patch<Specification>(`/quality/specifications/${id}`, body)).data);
export const useSpecAction = () =>
  useQualityMutation(async ({ id, action }: { id: string; action: 'activate' | 'revise' | 'archive' }) =>
    (await api.post<Specification>(`/quality/specifications/${id}/${action}`)).data);

export const useSavePlan = () =>
  useQualityMutation(async ({ id, body }: { id?: string; body: Body }) =>
    (id ? await api.patch<InspectionPlan>(`/quality/inspection-plans/${id}`, body) : await api.post<InspectionPlan>('/quality/inspection-plans', body)).data);
export const useDeletePlan = () =>
  useQualityMutation(async (id: string) => (await api.delete(`/quality/inspection-plans/${id}`)).data);

export const useCreateLot = () =>
  useQualityMutation(async (body: Body) => (await api.post<InspectionLot>('/quality/inspection-lots', body)).data);
export const useRecordResults = () =>
  useQualityMutation(async ({ id, body }: { id: string; body: Body }) => (await api.post<InspectionLot>(`/quality/inspection-lots/${id}/results`, body)).data);

export type DecisionAction = 'hold' | 'quarantine' | 'release' | 'reject' | 'downgrade' | 'cancel';
/** Stock-moving decisions carry a stable Idempotency-Key per attempt (reject / downgrade post GL). */
export const useLotDecision = () =>
  useQualityMutation(async ({ id, action, body }: { id: string; action: DecisionAction; body: Body }) =>
    action === 'hold'
      ? (await api.post<InspectionLot>(`/quality/inspection-lots/${id}/hold`, body)).data
      : idempotentPost<InspectionLot>(`/quality/inspection-lots/${id}/${action}`, body));

/** Opens the certificate of analysis PDF in a new tab. */
export function usePrintCertificate() {
  return useMutation({
    mutationFn: async (id: string) => {
      const res = await api.get(`/quality/inspection-lots/${id}/certificate`, { responseType: 'blob' });
      const url = URL.createObjectURL(new Blob([res.data as BlobPart], { type: 'application/pdf' }));
      window.open(url, '_blank', 'noopener');
      setTimeout(() => URL.revokeObjectURL(url), 60_000);
    },
  });
}

export const useCreateNcr = () => useQualityMutation(async (body: Body) => (await api.post<Ncr>('/quality/ncrs', body)).data);
export const useUpdateNcr = () =>
  useQualityMutation(async ({ id, body }: { id: string; body: Body }) => (await api.patch<Ncr>(`/quality/ncrs/${id}`, body)).data);
export const useNcrStatus = () =>
  useQualityMutation(async ({ id, status, notes }: { id: string; status: NcrStatus; notes?: string }) =>
    (await api.post<Ncr>(`/quality/ncrs/${id}/status`, { status, notes: notes || undefined })).data);

export const useCreateCapa = () => useQualityMutation(async (body: Body) => (await api.post<Capa>('/quality/capas', body)).data);
export const useUpdateCapa = () =>
  useQualityMutation(async ({ id, body }: { id: string; body: Body }) => (await api.patch<Capa>(`/quality/capas/${id}`, body)).data);
export const useCapaStatus = () =>
  useQualityMutation(async ({ id, status, notes }: { id: string; status: CapaStatus; notes?: string }) =>
    (await api.post<Capa>(`/quality/capas/${id}/status`, { status, notes: notes || undefined })).data);
export const useAddCapaAction = () =>
  useQualityMutation(async ({ id, body }: { id: string; body: Body }) => (await api.post<Capa>(`/quality/capas/${id}/actions`, body)).data);
export const useCompleteCapaAction = () =>
  useQualityMutation(async ({ actionId, status, notes }: { actionId: string; status: 'done' | 'cancelled'; notes?: string }) =>
    (await api.post<Capa>(`/quality/capa-actions/${actionId}/complete`, { status, notes: notes || undefined })).data);

export const useImportLabCsv = () =>
  useQualityMutation(async (body: { content: string; filename?: string }) => (await api.post<LabImport>('/quality/lab-imports/csv', body)).data);
export const useFetchLab = () =>
  useQualityMutation(async (body: { adapter: string; lotIds: string[] }) => (await api.post<LabImport>('/quality/lab-imports/fetch', body)).data);

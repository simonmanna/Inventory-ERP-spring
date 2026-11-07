import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import type { PaginatedResult } from '@erp/shared';
import { api } from '@/lib/api';
import { idempotentPost } from '@/lib/idempotent-request';
import { tx } from '@/lib/i18n/tx';

// ---- Multi-stage processing: sell at any stage (ADR-S060) ----

type Dec = string | null;
export type JobStatus = 'draft' | 'in_progress' | 'partially_output' | 'qc_hold' | 'completed' | 'cancelled' | 'reversed';
export type TemplateStatus = 'draft' | 'active' | 'archived';
export type OutputRole = 'main' | 'co_product' | 'by_product' | 'residual' | 'waste';

export const JOB_STATUS_LABEL: Record<JobStatus, string> = {
  draft: tx('Draft'), in_progress: tx('In progress'), partially_output: tx('Partly output'), qc_hold: tx('QC hold'),
  completed: tx('Completed'), cancelled: tx('Cancelled'), reversed: tx('Reversed'),
};
export const OUTPUT_ROLE_LABEL: Record<OutputRole, string> = {
  main: tx('Main product'), co_product: tx('Co-product'), by_product: tx('By-product'), residual: tx('Recoverable residual'), waste: tx('Waste'),
};
export const VALUATION_LABEL: Record<string, string> = {
  allocated: tx('Shares the cost'), nrv: tx('Net realisable value'), zero: tx('Zero value'),
};
export const ALLOCATION_LABEL: Record<string, string> = {
  market_value: tx('By market value'), weight: tx('By weight'), quantity: tx('By quantity (same unit only)'), fixed_pct: tx('Fixed %'),
};
export const GENEALOGY_LABEL: Record<string, string> = {
  mass: tx('By mass'), value: tx('By value'), fixed_pct: tx('Fixed %'), manual: tx('Entered per job'),
};
export const COST_LABEL: Record<string, string> = {
  labour: tx('Labour'), machine: tx('Machine'), fuel: tx('Fuel'), utility: tx('Electricity and water'), consumable: tx('Consumables'),
  subcontract: tx('Subcontract'), overhead: tx('Overhead'),
};
export const STAGE_LABEL: Record<string, string> = {
  raw: tx('Raw material'), semi_finished: tx('Semi-finished'), finished: tx('Finished'),
};

export interface TemplateLine {
  id: string; sequence: number; productId: string; productCode: string; productName: string; uomName: string | null; batchTracking: boolean;
  role: string; basisFactor: Dec;
}
export interface TemplateInput extends TemplateLine { genealogyBasis: string; fixedPct: Dec }
export interface TemplateOutput extends TemplateLine { role: OutputRole; expectedPct: string; valuation: string; nrvUnitValue: Dec; marketValue: Dec; stocked: boolean; locationId: string | null }
export interface Template {
  id: string; code: string; version: number; name: string; description: string | null; status: TemplateStatus; workCenterId: string | null;
  allocationMethod: string; yieldBasisUnit: string; expectedYieldPct: string; qcGate: boolean; qcCheckpointCode: string | null;
  defaultInputLocationId: string | null; defaultOutputLocationId: string | null; inputs: TemplateInput[]; outputs: TemplateOutput[];
}
export interface JobSummary {
  id: string; code: string; jobDate: string; status: JobStatus; templateId: string; templateCode: string; templateName: string; templateVersion: number;
  orderCode: string | null; yieldBasisUnit: string; expectedYieldPct: string; wipValue: Dec; inputBasis: Dec; outputBasis: Dec; lossBasis: Dec;
  inputSummary?: Array<{ productName: string; batchNumber: string | null }>; outputSummary?: Array<{ productName: string; batchNumber: string | null }>;
}
export interface JobInput { id: string; productId: string; productName: string; uomName: string | null; role: string; batchNumber: string | null; plannedQty: string; issuedQty: string; issuedCost: string; basisQty: string; basisSource: string }
export interface JobOutput {
  id: string; templateOutputId: string; productId: string; productName: string; uomName: string | null; role: OutputRole; batchNumber: string | null;
  quantity: string; value: string; unitCost: Dec; grade: string | null; basisQty: string; basisSource: string; marketValue: Dec; weight: Dec; length: Dec; width: Dec;
  thickness: Dec; area: Dec; volume: Dec; density: Dec;
}
export interface JobCost { id: string; category: string; description: string | null; amount: string; currency: string; baseAmount: string; costDate: string; sourceType: string | null; sourceId: string | null; reversedAt: string | null }
export interface JobLoss { id: string; kind: string; basisQty: string; value: string; reason: string | null; reversedAt: string | null }
export interface GenealogyLink {
  id: string; sourceProductName: string; sourceBatchNumber: string | null; sourceQty: string; outputProductName: string; outputBatchNumber: string | null;
  attributedQty: string; allocationMethod: string; sharePct: string; attributedCost: string; reversedAt: string | null;
}
export interface Reconciliation { inputBasis: string; outputBasis: string; lossBasis: string; unaccountedBasis: string; massYieldPct: Dec; commercialYield: Dec; basisUnit: string; estimated: boolean }
export interface Job extends JobSummary {
  locationId: string; outputLocationId: string; reprocessOfJobId: string | null; notes: string | null; cancelReason: string | null; overYieldReason: string | null;
  inputs: JobInput[]; outputs: JobOutput[]; costs: JobCost[]; losses: JobLoss[]; genealogy: GenealogyLink[]; reconciliation: Reconciliation;
}
export interface Lot {
  id: string; productId: string; productCode: string; productName: string; canBeSold: boolean; stage: string | null; categoryName: string | null; uomName: string | null;
  batchNumber: string; locationId: string; locationName: string | null; quantity: string; unitCost: string; value: string; reserved: string; jobId: string | null;
}
export interface LotSale { referenceType: string; referenceId: string; documentNumber: string | null; partnerName: string | null; quantity: string; cost: string; unitCost: string; createdAt: string }
export interface GenealogyNode {
  productId: string; productName: string | null; batchNumber: string | null; jobId?: string; jobCode?: string; attributedQty?: string; sharePct?: string; attributedCost?: string;
  children: GenealogyNode[]; sales?: LotSale[]; truncated?: boolean;
}
export interface RouteStep { id: string; sequence: number; templateCode: string; templateId: string | null; templateName: string | null; templateVersion: number | null; notes: string | null }
export interface ProcessingRoute { id: string; code: string; name: string; description: string | null; isActive: boolean; steps: RouteStep[] }
export interface MarginRow { productId: string; productCode: string; productName: string; stage: string | null; categoryName: string | null; quantity: string; revenue: string; cogs: string; grossProfit: string; marginPct: Dec }
export interface YieldRow { code: string; name: string; yieldBasisUnit: string; expectedYieldPct: string; jobs: number; inputBasis: string; outputBasis: string; lossBasis: string; outputValue: string }
export interface ProcessingDashboard {
  from: string; to: string; jobs: Array<{ status: JobStatus; count: number }>; stockByStage: Array<{ stage: string | null; products: number; value: string; quantity: string }>;
  wipValue: string; yieldByTemplate: YieldRow[]; margin: MarginRow[]; recentJobs: Array<{ id: string; code: string; jobDate: string; templateName: string; status: JobStatus }>;
}

const BASE = '/manufacturing/processing';
const KEY = ['processing'];
type Body = Record<string, unknown>;
const get = async <T>(url: string, params?: Record<string, unknown>) => (await api.get<T>(`${BASE}${url}`, { params })).data;
const clean = (params: Record<string, unknown>) => Object.fromEntries(Object.entries(params).filter(([, v]) => v !== '' && v !== undefined && v !== null));

export const useTemplates = (params: { status?: string; inputProductId?: string } = {}) => useQuery({
  queryKey: [...KEY, 'templates', params], queryFn: () => get<Template[]>('/templates', clean(params)),
});
export const useTemplate = (id?: string) => useQuery({ queryKey: [...KEY, 'template', id], queryFn: () => get<Template>(`/templates/${id}`), enabled: !!id });
export const useJobs = (params: Record<string, unknown>) => useQuery({
  queryKey: [...KEY, 'jobs', params], queryFn: () => get<PaginatedResult<JobSummary>>('/jobs', clean(params)), placeholderData: (p) => p,
});
export const useJob = (id?: string) => useQuery({ queryKey: [...KEY, 'job', id], queryFn: () => get<Job>(`/jobs/${id}`), enabled: !!id });
export const useLots = (params: { productId?: string; search?: string }, enabled = true) => useQuery({
  queryKey: [...KEY, 'lots', params], queryFn: () => get<Lot[]>('/lots', clean(params)), enabled, placeholderData: (p) => p,
});
export const useRoutes = () => useQuery({ queryKey: [...KEY, 'routes'], queryFn: () => get<ProcessingRoute[]>('/routes') });
export const useGenealogy = (productId?: string, batchNumber?: string, direction?: string) => useQuery({
  queryKey: [...KEY, 'genealogy', productId, batchNumber, direction],
  queryFn: () => get<{ direction: string; root: GenealogyNode }>('/genealogy', { productId, batchNumber, direction }),
  enabled: !!productId && !!batchNumber,
});
export const useProcessingDashboard = (from: string, to: string, enabled = true) => useQuery({
  queryKey: [...KEY, 'dashboard', from, to], queryFn: () => get<ProcessingDashboard>('/dashboard', { from, to }), enabled, placeholderData: (p) => p,
});

function useProcessingMutation<A, R>(fn: (arg: A) => Promise<R>) {
  const qc = useQueryClient();
  return useMutation({ mutationFn: fn, onSuccess: () => qc.invalidateQueries({ queryKey: KEY }) });
}

export const useSaveTemplate = () => useProcessingMutation(async ({ id, body }: { id?: string; body: Body }) =>
  (id ? api.put<Template>(`${BASE}/templates/${id}`, body) : api.post<Template>(`${BASE}/templates`, body)).then((r) => r.data));
export const useTemplateAction = () => useProcessingMutation(async ({ id, action }: { id: string; action: 'activate' | 'archive' | 'new-version' }) =>
  (await api.post<Template>(`${BASE}/templates/${id}/${action}`)).data);
export const useCreateJob = () => useProcessingMutation((body: Body) => idempotentPost<Job>(`${BASE}/jobs`, body));
export const usePostJob = () => useProcessingMutation((body: Body) => idempotentPost<Job>(`${BASE}/jobs/post`, body));
export const useStartJob = () => useProcessingMutation((id: string) => idempotentPost<Job>(`${BASE}/jobs/${id}/start`));
export const useAddCost = () => useProcessingMutation(({ id, body }: { id: string; body: Body }) => idempotentPost<Job>(`${BASE}/jobs/${id}/costs`, body));
export const useRecordOutput = () => useProcessingMutation(({ id, body }: { id: string; body: Body }) => idempotentPost<Job>(`${BASE}/jobs/${id}/output`, body));
export const useCancelJob = () => useProcessingMutation(async ({ id, reason }: { id: string; reason: string }) =>
  (await api.post<Job>(`${BASE}/jobs/${id}/cancel`, { reason })).data);
export const useReverseJob = () => useProcessingMutation(({ id, reason }: { id: string; reason: string }) => idempotentPost<Job>(`${BASE}/jobs/${id}/reverse`, { reason }));
export const useSaveRoute = () => useProcessingMutation(async ({ id, body }: { id?: string; body: Body }) =>
  (id ? api.put<ProcessingRoute>(`${BASE}/routes/${id}`, body) : api.post<ProcessingRoute>(`${BASE}/routes`, body)).then((r) => r.data));
export const useDeleteRoute = () => useProcessingMutation(async (id: string) => (await api.delete(`${BASE}/routes/${id}`)).data);

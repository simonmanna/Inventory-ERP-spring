import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from '@/lib/api';
import { idempotentPost } from '@/lib/idempotent-request';
import type { ProductionMaterial, ProductionOrder, ProductionOutput } from './types';

/**
 * ADR-S011 multi-stage production (Phase 2.1): stage set-up on routings, co-products + allocation rules on BOMs,
 * staged order execution (issue / output / conversion / QC gate / reversal), child orders and the stage WIP report.
 * Stock-moving calls carry an Idempotency-Key (idempotentPost).
 */

export type StageStatus = 'pending' | 'in_progress' | 'completed' | 'cancelled';
export type QcStatus = 'not_required' | 'pending' | 'passed' | 'failed';
export type OutputRole = 'main' | 'co_product' | 'by_product' | 'waste';

export interface StagePostingLine {
  id: string;
  stageId: string;
  role: string;
  productId: string | null;
  quantity: string;
  unitCost: string;
  value: string;
  locationId: string | null;
  ledgerCode: string | null;
  costSource: string | null;
}

export interface StagePosting {
  id: string;
  code: string;
  kind: 'issue' | 'output' | 'conversion' | 'qc_release';
  postedAt: string;
  qtyGood: string;
  qtyScrap: string;
  qtyAbnormalScrap: string;
  completesStage: boolean;
  allocationMethod: string | null;
  notes: string | null;
  reversedAt: string | null;
  lines: StagePostingLine[];
}

export interface OrderStage {
  id: string;
  sequence: number;
  name: string;
  routingOperationId: string | null;
  issueMode: 'backflush' | 'manual';
  outputProductId: string | null;
  stageLocationId: string | null;
  plannedOutputQty: string;
  expectedYieldPct: string;
  isLast: boolean;
  qcGate: boolean;
  qcCheckpointCode: string | null;
  status: StageStatus;
  qcStatus: QcStatus;
  qcReference: string | null;
  qtyGood: string;
  qtyScrap: string;
  qtyProcessed: string;
  qtyQuarantined: string;
  materialCost: string;
  transferredInCost: string;
  conversionCost: string;
  outputCost: string;
  scrapCost: string;
  wipValue: string;
  intermediateAvailable: string;
  blockedBy: string | null;
  materials: ProductionMaterial[];
  outputs: (ProductionOutput & { outputRole?: OutputRole | null })[];
  postings: StagePosting[];
}

export interface StagedOrder extends ProductionOrder {
  multiStage: boolean;
  wipValue: string;
  stages: OrderStage[];
}

export interface OrderTreeNode {
  id: string;
  orderCode: string;
  status: string;
  outputProductId: string;
  productName: string | null;
  plannedQty: string;
  producedQty: string;
  multiStage: boolean;
  stages: number;
  stagesCompleted: number;
  rolledStatus: string;
  progressPct: string;
  childrenDone: number;
  childrenOpen: number;
  peg: { quantity: string; level: number } | null;
  children: OrderTreeNode[];
}

export interface StageSetup {
  issueMode: 'backflush' | 'manual';
  outputProductId: string | null;
  outputPerUnit: string;
  stageLocationId: string | null;
  expectedYieldPct: string;
  qcGate: boolean;
  qcCheckpointCode: string | null;
  materialProductIds: string[];
}

export interface RoutingStageSetup {
  id: string;
  name: string;
  operations: Array<{ id: string; sequence: number; name: string; isLast: boolean; stage: StageSetup | null }>;
}

export interface CoProduct {
  id?: string;
  productId: string;
  productName?: string;
  role: 'co_product' | 'by_product' | 'waste';
  routingOperationId?: string | null;
  quantity: string | number;
  valuation: 'allocated' | 'nrv' | 'zero';
  nrvUnitValue?: string | number | null;
  marketValue?: string | number | null;
  weightPerUnit?: string | number | null;
  fixedPct?: string | number | null;
  stocked: boolean;
}

export type AllocationMethod = 'quantity' | 'weight' | 'market_value' | 'fixed_pct';

export interface AllocationRule {
  method: AllocationMethod;
  mainWeightPerUnit?: string | number | null;
  mainMarketValue?: string | number | null;
  source?: 'bom' | 'organization' | 'default';
}

export interface BomStageSetup {
  bomId: string;
  routingId: string | null;
  coProducts: CoProduct[];
  allocationRule: AllocationRule | null;
  effectiveRule: AllocationRule;
}

export interface StageWipRow {
  stageId: string;
  orderId: string;
  orderCode: string;
  orderStatus: string;
  sequence: number;
  name: string;
  status: StageStatus;
  qcStatus: QcStatus;
  qtyGood: string;
  qtyScrap: string;
  wipValue: string;
  yieldPct: string | null;
}

export interface StageWipReport {
  totals: {
    stageWip: string;
    legacyOrdersWip: string;
    processCostingWip: string;
    expectedWip: string;
    glWipBalance: string;
    drift: string;
    balanced: boolean;
    intermediateStockValue: string;
  };
  stages: StageWipRow[];
  legacyOrders: Array<{ id: string; orderCode: string; wip: string; unabsorbedConversion: string }>;
  intermediates: Array<{ productId: string; productName: string; locationId: string; locationName: string; quantity: string; value: string }>;
}

const get = async <T,>(url: string) => (await api.get<T>(url)).data;

// ---- staged orders ----
export function useOrderStages(orderId: string) {
  return useQuery({
    queryKey: ['order-stages', orderId],
    queryFn: () => get<StagedOrder>(`/manufacturing/production-orders/${orderId}/stages`),
    enabled: !!orderId,
  });
}

export function useOrderTree(orderId: string) {
  return useQuery({
    queryKey: ['order-tree', orderId],
    queryFn: () => get<OrderTreeNode>(`/manufacturing/production-orders/${orderId}/order-tree`),
    enabled: !!orderId,
  });
}

function useStageMutation<TVars>(fn: (v: TVars) => Promise<unknown>) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: fn,
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['order-stages'] });
      qc.invalidateQueries({ queryKey: ['order-tree'] });
      qc.invalidateQueries({ queryKey: ['production-order'] });
      qc.invalidateQueries({ queryKey: ['production-orders'] });
      qc.invalidateQueries({ queryKey: ['stage-wip'] });
    },
  });
}

type Body = Record<string, unknown>;

export const useInitializeStages = () =>
  useStageMutation((orderId: string) => api.post(`/manufacturing/production-orders/${orderId}/stages/initialize`, {}));
export const useCreateChildOrders = () =>
  useStageMutation(({ orderId, body }: { orderId: string; body: Body }) =>
    api.post(`/manufacturing/production-orders/${orderId}/child-orders`, body));
export const useStageIssue = () =>
  useStageMutation(({ stageId, body }: { stageId: string; body: Body }) =>
    idempotentPost(`/manufacturing/production-stages/${stageId}/issue`, body));
export const useStageOutput = () =>
  useStageMutation(({ stageId, body }: { stageId: string; body: Body }) =>
    idempotentPost(`/manufacturing/production-stages/${stageId}/output`, body));
export const useStageConversion = () =>
  useStageMutation(({ stageId, body }: { stageId: string; body: Body }) =>
    idempotentPost(`/manufacturing/production-stages/${stageId}/conversion-costs`, body));
export const useStageQcDecision = () =>
  useStageMutation(({ stageId, body }: { stageId: string; body: Body }) =>
    idempotentPost(`/manufacturing/production-stages/${stageId}/qc-decision`, body));
export const useReverseStagePosting = () =>
  useStageMutation(({ postingId, reason }: { postingId: string; reason: string }) =>
    idempotentPost(`/manufacturing/stage-postings/${postingId}/reverse`, { reason }));
export const useReverseStagedOrder = () =>
  useStageMutation(({ orderId, reason }: { orderId: string; reason: string }) =>
    idempotentPost(`/manufacturing/production-orders/${orderId}/stages/reverse`, { reason }));

// ---- set-up ----
export function useRoutingStageSetup(routingId?: string | null) {
  return useQuery({
    queryKey: ['routing-stage-setup', routingId],
    queryFn: () => get<RoutingStageSetup>(`/manufacturing/routings/${routingId}/stage-setup`),
    enabled: !!routingId,
  });
}

export function useSaveRoutingStageSetup() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async ({ routingId, stages }: { routingId: string; stages: Body[] }) =>
      (await api.put<RoutingStageSetup>(`/manufacturing/routings/${routingId}/stage-setup`, { stages })).data,
    onSuccess: () => qc.invalidateQueries({ queryKey: ['routing-stage-setup'] }),
  });
}

export function useBomStageSetup(bomId: string) {
  return useQuery({
    queryKey: ['bom-stage-setup', bomId],
    queryFn: () => get<BomStageSetup>(`/manufacturing/boms/${bomId}/stage-setup`),
    enabled: !!bomId,
  });
}

export function useSaveBomStageSetup() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async ({ bomId, body }: { bomId: string; body: Body }) =>
      (await api.put<BomStageSetup>(`/manufacturing/boms/${bomId}/stage-setup`, body)).data,
    onSuccess: () => qc.invalidateQueries({ queryKey: ['bom-stage-setup'] }),
  });
}

export function useAllocationRules() {
  return useQuery({
    queryKey: ['cost-allocation-rules'],
    queryFn: () => get<{ organizationDefault: AllocationRule | null; effectiveDefault: AllocationRule; bomOverrides: Array<AllocationRule & { bomId: string; bomCode: string; bomName: string }> }>(
      '/manufacturing/cost-allocation-rules'),
  });
}

export function useSaveDefaultAllocationRule() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (body: Body) => (await api.put('/manufacturing/cost-allocation-rules/default', body)).data,
    onSuccess: () => qc.invalidateQueries({ queryKey: ['cost-allocation-rules'] }),
  });
}

// ---- report ----
export function useStageWipReport() {
  return useQuery({ queryKey: ['stage-wip'], queryFn: () => get<StageWipReport>('/manufacturing/reports/stage-wip') });
}

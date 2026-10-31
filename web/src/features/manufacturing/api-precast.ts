import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import type { PaginatedResult } from '@erp/shared';
import { api } from '@/lib/api';
import { idempotentPost } from '@/lib/idempotent-request';
import { tx } from '@/lib/i18n/tx';
import type { BomStatus, ProductionOrderStatus } from './types';
import type { QcStatus, StageStatus } from './api-stages';

/**
 * Sub-module 2.4 concrete block / precast manufacturing: block specs, curing areas, mix designs (BOM-backed) with
 * batch scaling, precast orders (pressing runs → curing batches → cubing), curing board, pallet labels, reports.
 * Decimal columns arrive as strings over JSON; stock-moving calls carry an Idempotency-Key (idempotentPost).
 */

export type Dec = string | number;

export type BlockType = 'hollow' | 'solid' | 'paver' | 'kerb' | 'lintel' | 'slab' | 'other';
export type CuringAreaKind = 'chamber' | 'yard' | 'shed';
export type ScaleBasis = 'units' | 'batches' | 'cement_bags' | 'volume_m3' | 'cycles' | 'pallets';
export type CuringBatchStatus = 'curing' | 'cubed' | 'released' | 'failed' | 'cancelled';
export type StageRole = 'pressing' | 'curing' | 'other';

export interface ProductSpec {
  id: string;
  productId: string;
  productName: string | null;
  productSku: string | null;
  blockType: BlockType;
  lengthMm: Dec | null;
  widthMm: Dec | null;
  heightMm: Dec | null;
  strengthClass: string | null;
  strengthMpa: Dec | null;
  weightKg: Dec | null;
  unitsPerPallet: Dec | null;
  unitsPerCycle: Dec | null;
  secondGradeProductId: string | null;
  secondGradeProductName: string | null;
  notes: string | null;
  isActive: boolean;
}

export interface CuringArea {
  id: string;
  code: string;
  name: string;
  kind: CuringAreaKind;
  capacityPallets: Dec | null;
  locationId: string | null;
  locationName: string | null;
  targetTemperatureC: Dec | null;
  notes: string | null;
  isActive: boolean;
  palletsInUse: Dec;
  batchesCuring: Dec;
}

export interface MixDesign {
  id: string;
  bomId: string;
  bomCode: string;
  name: string;
  version: number;
  status: BomStatus;
  unitsPerBatch: Dec;
  expectedYieldPct: Dec | null;
  routingId: string | null;
  productId: string;
  productName: string | null;
  productSku: string | null;
  cementProductId: string | null;
  cementProductName: string | null;
  cementPerBag: Dec | null;
  batchVolumeM3: Dec | null;
  waterCementRatio: Dec | null;
  minCuringDays: Dec | null;
  curingCheckpointCode: string | null;
  defaultCuringAreaId: string | null;
  defaultCuringAreaName: string | null;
  targetMaturity: Dec | null;
  datumTemperatureC: Dec | null;
  defaultLocationId: string | null;
  defaultOutputLocationId: string | null;
  notes: string | null;
}

export interface MixLine {
  id: string;
  componentProductId: string;
  productName: string | null;
  productSku: string | null;
  quantity: Dec;
  uomId: string | null;
  scrapPct: Dec | null;
  sequence: number;
  notes: string | null;
}

export interface ScaleMaterial {
  productId: string;
  productName: string | null;
  uomId: string | null;
  qtyPerBatch: Dec;
  quantity: Dec;
  cement: boolean;
  bags: Dec | null;
  onHand: Dec | null;
}

export interface Scaling {
  mixDesignId: string;
  basis: ScaleBasis;
  requested: Dec;
  wholeBatches: boolean;
  batches: Dec;
  units: Dec;
  expectedGood: Dec;
  pallets: Dec | null;
  cycles: Dec | null;
  volumeM3: Dec | null;
  cementQty: Dec | null;
  cementBags: Dec | null;
  cementBagsToDraw: Dec | null;
  materials: ScaleMaterial[];
}

export interface MixDesignDetail extends MixDesign {
  lines: MixLine[];
  spec: ProductSpec | null;
  versions: Array<{ id: string; bomCode: string; version: number; status: BomStatus; createdAt: string }>;
  coProducts: unknown[];
  perBatch: Scaling | null;
}

export interface PrecastOrder {
  id: string;
  orderCode: string;
  status: ProductionOrderStatus;
  plannedQty: Dec;
  producedQty: Dec;
  scrapQty: Dec;
  outputUnitCost: Dec | null;
  totalCost: Dec | null;
  scheduledFor: string | null;
  batchNumber: string | null;
  createdAt: string;
  outputProductId: string;
  productName: string | null;
  productSku: string | null;
  mixDesignId: string | null;
  bomCode: string | null;
  bomVersion: number | null;
  curingBatches: Dec;
}

export interface PrecastStage {
  id: string;
  sequence: number;
  name: string;
  role: StageRole;
  status: StageStatus;
  qcStatus: QcStatus;
  qcGate: boolean;
  qcCheckpointCode: string | null;
  minCuringDays: Dec | null;
  qcPassDueAt: string | null;
  qcReference: string | null;
  qtyGood: Dec;
  qtyScrap: Dec;
  qtyProcessed: Dec;
  qtyQuarantined: Dec;
  plannedOutputQty: Dec;
}

export interface MaterialVariance {
  productId: string;
  productName: string | null;
  qtyPlanned: Dec;
  standardQty: Dec;
  actualQty: Dec;
  varianceQty: Dec;
  variancePct: Dec | null;
  varianceValue: Dec;
}

export interface BlockCosting {
  blocksPressed: Dec;
  goodOffPress: Dec;
  brokenAtPress: Dec;
  brokenAtCubing: Dec;
  secondGrade: Dec;
  waste: Dec;
  goodBlocks: Dec;
  inCuring: Dec;
  rejectionRatePct: Dec | null;
  materialCost: Dec;
  conversionCost: Dec;
  conversionByKind: Record<string, Dec>;
  totalCost: Dec;
  finishedGoodsValue: Dec;
  secondGradeValue: Dec;
  abnormalScrapValue: Dec;
  costPerAcceptableBlock: Dec | null;
  costPerPressedBlock: Dec | null;
  materialVariance: MaterialVariance[];
}

export interface CuringBatch {
  id: string;
  code: string;
  orderId: string;
  orderCode: string;
  orderStatus: ProductionOrderStatus;
  stageId: string | null;
  productId: string;
  productName: string | null;
  productSku: string | null;
  batchNumber: string | null;
  quantity: Dec;
  brokenAtPress: Dec | null;
  cycles: Dec | null;
  pallets: Dec | null;
  curingAreaId: string | null;
  curingAreaCode: string | null;
  curingAreaName: string | null;
  palletLocation: string | null;
  shift: string | null;
  startedAt: string;
  minCuringDays: Dec | null;
  curingUntil: string | null;
  status: CuringBatchStatus;
  daysCured: Dec | null;
  ready: boolean;
  maturity: Dec | null;
  maturityPct: Dec | null;
  targetMaturity: Dec | null;
  lastTemperatureC: Dec | null;
  lastReadingAt: string | null;
  qtyGood: Dec | null;
  qtySecondGrade: Dec | null;
  qtyBroken: Dec | null;
  scrapReasonCode: string | null;
  cubedAt: string | null;
  releasedAt: string | null;
  finalLotId: string | null;
  qcStatus: QcStatus | null;
  qcReference: string | null;
  qcPassDueAt: string | null;
  qcCheckpointCode: string | null;
  notes: string | null;
}

export interface CuringReading {
  id: string;
  kind: 'temperature' | 'move';
  readAt: string;
  temperatureC: Dec | null;
  humidityPct: Dec | null;
  curingAreaId: string | null;
  curingAreaCode: string | null;
  palletLocation: string | null;
  notes: string | null;
}

export interface CuringBatchDetail extends CuringBatch {
  readings: CuringReading[];
  qualityLots: Array<{ id: string; lotNumber: string; stage: string; status: string; result: string; quantity: Dec; createdAt: string }>;
}

export interface PrecastOrderDetail extends PrecastOrder {
  mixDesign: MixDesign | null;
  spec: ProductSpec | null;
  stages: PrecastStage[];
  batches: CuringBatch[];
  costing: BlockCosting | null;
  scaling: Scaling | null;
}

export interface PalletLabel {
  palletNo: number;
  pallets: number;
  palletCode: string;
  quantity: Dec;
  payload: string;
  qrSvg: string;
}

export interface LabelSheet {
  batch: CuringBatch;
  spec: ProductSpec | null;
  labels: PalletLabel[];
}

export interface CostPerBlockRow {
  id: string;
  orderCode: string;
  status: ProductionOrderStatus;
  createdAt: string;
  outputProductId: string;
  productName: string | null;
  blocksPressed: Dec;
  goodOffPress: Dec;
  brokenAtPress: Dec;
  brokenAtCubing: Dec;
  secondGrade: Dec;
  waste: Dec;
  goodBlocks: Dec;
  inCuring: Dec;
  rejectionRatePct: Dec | null;
  materialCost: Dec;
  conversionCost: Dec;
  totalCost: Dec;
  finishedGoodsValue: Dec;
  secondGradeValue: Dec;
  abnormalScrapValue: Dec;
  costPerAcceptableBlock: Dec | null;
  costPerPressedBlock: Dec | null;
}

export interface CostPerBlockReport {
  from: string | null;
  to: string | null;
  rows: CostPerBlockRow[];
  byProduct: Array<{
    productId: string; productName: string | null; orders: Dec; blocksPressed: Dec; goodBlocks: Dec; secondGrade: Dec;
    brokenAtPress: Dec; brokenAtCubing: Dec; totalCost: Dec; finishedGoodsValue: Dec; costPerAcceptableBlock: Dec | null;
    rejectionRatePct: Dec | null;
  }>;
}

export interface BreakageReport {
  from: string | null;
  to: string | null;
  byReason: Array<{ productId: string; productName: string | null; stage: 'pressing' | 'cubing'; reasonCode: string | null; reasonName: string | null; quantity: Dec; postings: Dec }>;
  secondGrade: Array<{ productId: string; productName: string | null; secondGrade: Dec; goodBlocks: Dec; cured: Dec }>;
}

// ---- labels ----
export const BLOCK_TYPE_LABEL: Record<BlockType, string> = {
  hollow: tx('Hollow'), solid: tx('Solid'), paver: tx('Paver'), kerb: tx('Kerb'), lintel: tx('Lintel'), slab: tx('Slab'), other: tx('Other'),
};
export const AREA_KIND_LABEL: Record<CuringAreaKind, string> = { chamber: tx('Chamber'), yard: tx('Yard'), shed: tx('Shed') };
export const BASIS_LABEL: Record<ScaleBasis, string> = {
  units: tx('Blocks'), batches: tx('Batches'), cement_bags: tx('Cement bags'), volume_m3: tx('Volume (m³)'), cycles: tx('Press cycles'), pallets: tx('Pallets'),
};
export const BATCH_STATUS_LABEL: Record<CuringBatchStatus, string> = {
  curing: tx('Curing'), cubed: tx('Cubed'), released: tx('Released'), failed: tx('Failed'), cancelled: tx('Cancelled'),
};
export const MIX_STATUS_LABEL: Record<BomStatus, string> = { draft: tx('Draft'), active: tx('Active'), archived: tx('Archived') };
export const ORDER_STATUS_LABEL: Record<ProductionOrderStatus, string> = {
  draft: tx('Draft'), confirmed: tx('Confirmed'), in_progress: tx('In progress'), qc_hold: tx('QC hold'), completed: tx('Completed'), cancelled: tx('Cancelled'),
};
export const QC_STATUS_LABEL: Record<QcStatus, string> = {
  not_required: tx('Not required'), pending: tx('Pending'), passed: tx('Passed'), failed: tx('Failed'), curing: tx('Curing'),
};

// ---- hooks ----
const KEY = ['precast'] as const;
const BASE = '/manufacturing/precast';
type Body = Record<string, unknown>;
export interface PageParams { page: number; pageSize: number; search?: string }

function useList<T>(path: string, params: object, enabled = true) {
  return useQuery({
    queryKey: [...KEY, path, params],
    enabled,
    placeholderData: (prev) => prev,
    queryFn: async () => (await api.get<PaginatedResult<T>>(`${BASE}${path}`, { params })).data,
  });
}

function useOne<T>(path: string, id: string | undefined) {
  return useQuery({
    queryKey: [...KEY, path, id],
    enabled: !!id,
    queryFn: async () => (await api.get<T>(`${BASE}${path}/${id}`)).data,
  });
}

/** Every precast write refreshes the precast caches plus the generic order/stage caches it touches. */
function usePrecastMutation<TVars, TOut>(fn: (v: TVars) => Promise<TOut>) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: fn,
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: KEY });
      qc.invalidateQueries({ queryKey: ['production-orders'] });
      qc.invalidateQueries({ queryKey: ['production-order'] });
      qc.invalidateQueries({ queryKey: ['order-stages'] });
      qc.invalidateQueries({ queryKey: ['boms'] });
    },
  });
}

// set-up
export const useProductSpecs = (p: PageParams) => useList<ProductSpec>('/product-specs', p);
export const useSaveProductSpec = () =>
  usePrecastMutation(async ({ productId, body }: { productId: string; body: Body }) =>
    (await api.put<ProductSpec>(`${BASE}/product-specs/${productId}`, body)).data);

export function useCuringAreas(active?: boolean) {
  return useQuery({
    queryKey: [...KEY, 'curing-areas', active ?? null],
    queryFn: async () => (await api.get<CuringArea[]>(`${BASE}/curing-areas`, { params: active === undefined ? {} : { active } })).data,
  });
}
export const useSaveCuringArea = () =>
  usePrecastMutation(async ({ id, body }: { id?: string; body: Body }) =>
    (id ? await api.patch<CuringArea>(`${BASE}/curing-areas/${id}`, body) : await api.post<CuringArea>(`${BASE}/curing-areas`, body)).data);

// mix designs
export const useMixDesigns = (p: PageParams & { status?: string; productId?: string }) => useList<MixDesign>('/mix-designs', p);
export const useMixDesign = (id?: string) => useOne<MixDesignDetail>('/mix-designs', id);
export const useCreateMixDesign = () =>
  usePrecastMutation(async (body: Body) => (await api.post<MixDesignDetail>(`${BASE}/mix-designs`, body)).data);
export const useUpdateMixDesign = () =>
  usePrecastMutation(async ({ id, body }: { id: string; body: Body }) => (await api.patch<MixDesignDetail>(`${BASE}/mix-designs/${id}`, body)).data);
export const useActivateMixDesign = () =>
  usePrecastMutation(async (id: string) => (await api.post<MixDesignDetail>(`${BASE}/mix-designs/${id}/activate`, {})).data);
export const useReviseMixDesign = () =>
  usePrecastMutation(async ({ id, body }: { id: string; body?: Body }) => (await api.post<MixDesignDetail>(`${BASE}/mix-designs/${id}/revise`, body ?? {})).data);

export interface ScaleParams { basis: ScaleBasis; value: number; wholeBatches: boolean }
export function useMixScale(id: string | undefined, p: ScaleParams | null) {
  return useQuery({
    queryKey: [...KEY, 'scale', id, p],
    enabled: !!id && !!p && Number.isFinite(p.value) && p.value > 0,
    placeholderData: (prev) => prev,
    queryFn: async () => (await api.get<Scaling>(`${BASE}/mix-designs/${id}/scale`, { params: p })).data,
  });
}

// orders
export const usePrecastOrders = (p: PageParams & { status?: string; productId?: string }) => useList<PrecastOrder>('/orders', p);
export const usePrecastOrder = (id?: string) => useOne<PrecastOrderDetail>('/orders', id);
export const useCreatePrecastOrder = () =>
  usePrecastMutation(async (body: Body) => (await api.post<PrecastOrderDetail>(`${BASE}/orders`, body)).data);
export const usePressingRun = () =>
  usePrecastMutation(({ orderId, body }: { orderId: string; body: Body }) =>
    idempotentPost<{ posting: unknown; batch: CuringBatch | null; order: PrecastOrderDetail }>(`${BASE}/orders/${orderId}/pressing-runs`, body));

// curing
export const useCuringBatches = (p: PageParams & { status?: string; curingAreaId?: string; orderId?: string; ready?: boolean }) =>
  useList<CuringBatch>('/curing-batches', p);
export const useCuringBatch = (id?: string) => useOne<CuringBatchDetail>('/curing-batches', id);
export function useBatchLabels(id?: string) {
  return useQuery({
    queryKey: [...KEY, 'labels', id],
    enabled: !!id,
    queryFn: async () => (await api.get<LabelSheet>(`${BASE}/curing-batches/${id}/labels`)).data,
  });
}
export const useCuringReading = () =>
  usePrecastMutation(async ({ id, body }: { id: string; body: Body }) => (await api.post<CuringBatchDetail>(`${BASE}/curing-batches/${id}/readings`, body)).data);
export const useMoveBatch = () =>
  usePrecastMutation(async ({ id, body }: { id: string; body: Body }) => (await api.post<CuringBatchDetail>(`${BASE}/curing-batches/${id}/move`, body)).data);
export const useCubeBatch = () =>
  usePrecastMutation(({ id, body }: { id: string; body: Body }) =>
    idempotentPost<{ posting: unknown; batch: CuringBatch }>(`${BASE}/curing-batches/${id}/cube`, body));
export const useGateDecision = () =>
  usePrecastMutation(({ id, body }: { id: string; body: Body }) =>
    idempotentPost<CuringBatchDetail>(`${BASE}/curing-batches/${id}/gate-decision`, body));
export const useFinalInspection = () =>
  usePrecastMutation(async (id: string) => (await api.post<CuringBatchDetail>(`${BASE}/curing-batches/${id}/final-inspection`, {})).data);

// reports
export interface ReportParams { from?: string; to?: string; productId?: string }
export function useCostPerBlock(p: ReportParams, enabled = true) {
  return useQuery({
    queryKey: [...KEY, 'report-cost', p],
    enabled,
    queryFn: async () => (await api.get<CostPerBlockReport>(`${BASE}/reports/cost-per-block`, { params: p })).data,
  });
}
export function useBreakage(p: ReportParams, enabled = true) {
  return useQuery({
    queryKey: [...KEY, 'report-breakage', p],
    enabled,
    queryFn: async () => (await api.get<BreakageReport>(`${BASE}/reports/breakage`, { params: p })).data,
  });
}

import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from '@/lib/api';
import { idempotentPost } from '@/lib/idempotent-request';

/**
 * Manufacturing costing & analytics (ADR-S018, Phase 2.8): absorption rates, actual order cost, absorption through the
 * posting engine (Idempotency-Key), variances, cost by shift / plant / product / month, gross margin.
 */

const C = '/manufacturing/costing';
const KEY = ['mfg-costing'];

export type Num = string | number;
type Filters = Record<string, string | undefined>;

export interface CostRate {
  workCenterId: string; workCenterCode: string; workCenterName: string; configured: boolean;
  labourRatePerHour?: Num; machineRatePerHour?: Num; overheadRatePerHour?: Num; overheadBasis?: string;
  energyRatePerUnit?: Num; energyMeterCode?: string | null; effectiveMachineRate?: Num | null;
}
export interface RateInput {
  labourRatePerHour: number; machineRatePerHour: number; overheadRatePerHour: number; overheadBasis: string;
  energyRatePerUnit: number; energyMeterCode?: string;
}
export interface AbsorptionDue { workOrderId: string; workOrderName: string; kind: string; basisQty: Num; rate: Num; amount: Num }
export interface OrderCost {
  orderId: string; orderCode: string; status: string; producedQty: Num; materialCost: Num; labourCost: Num; machineCost: Num;
  energyCost: Num; overheadCost: Num; totalCost: Num; unitCost: Num | null; absorptionDue: AbsorptionDue[];
  stages: Array<{ sequence: number; name: string; status: string; materialCost: Num; conversionCost: Num; outputCost: Num }>;
  precast?: { costPerAcceptableBlock: Num | null; goodBlocks: Num };
  crushing?: { code: string; costPerTonne: Num | null; outputTonnes: Num | null };
}
export interface MaterialVariance {
  productId: string; productName: string; plannedQty: Num; actualQty: Num; standardUnitCost: Num; priceVariance: Num; usageVariance: Num;
}
export interface OrderVariances {
  materials: MaterialVariance[]; materialPrice: Num; materialUsage: Num; labourEfficiency: Num; overhead: Num; yield: Num;
  total: Num; actualHours: Num; standardHours: Num; expectedOutput: Num; producedQty: Num; standardUnitCost: Num;
}
export interface VarianceRow {
  orderId?: string; orderCode?: string; productId?: string; productName: string; materialPrice: Num; materialUsage: Num;
  labourEfficiency: Num; overhead: Num; yield: Num; total: Num;
}
export interface VarianceSummary { totals: VarianceRow; byProduct: VarianceRow[]; orders: VarianceRow[] }
export interface CostSummary {
  rows: Array<{ key: string; label: string; quantity: Num; cost: Num; unitCost: Num | null }>;
  totals: { quantity: Num; cost: Num; unitCost: Num | null };
}
export interface MarginReport {
  rows: Array<{ key: string; label: string; quantity: Num; revenue: Num; cost: Num; margin: Num; marginPct: Num | null; costBasis?: string }>;
  totals: { revenue: Num; cost: Num; margin: Num; marginPct: Num | null };
}

function get<T>(path: string, params?: Filters) {
  return api.get<T>(`${C}${path}`, { params }).then((r) => r.data);
}

export const useCostRates = () => useQuery({ queryKey: [...KEY, 'rates'], queryFn: () => get<CostRate[]>('/rates') });
export const useOrderCost = (id: string) => useQuery({ queryKey: [...KEY, 'order', id], queryFn: () => get<OrderCost>(`/orders/${id}`) });
export const useOrderVariances = (id: string) =>
  useQuery({ queryKey: [...KEY, 'order-variances', id], queryFn: () => get<OrderVariances>(`/orders/${id}/variances`) });
export const useVariances = (f: Filters) => useQuery({ queryKey: [...KEY, 'variances', f], queryFn: () => get<VarianceSummary>('/variances', f) });
export const useCostSummary = (f: Filters) => useQuery({ queryKey: [...KEY, 'summary', f], queryFn: () => get<CostSummary>('/reports/summary', f) });
export const useMargin = (f: Filters) => useQuery({ queryKey: [...KEY, 'margin', f], queryFn: () => get<MarginReport>('/reports/margin', f) });

function useChange<V, R = unknown>(fn: (v: V) => Promise<R>) {
  const qc = useQueryClient();
  return useMutation({ mutationFn: fn, onSuccess: () => { qc.invalidateQueries({ queryKey: KEY }); } });
}

export const useSaveRate = () => useChange((v: { workCenterId: string; body: RateInput }) =>
  api.put(`${C}/rates/${v.workCenterId}`, v.body).then((r) => r.data));
export const useAbsorb = () => useChange((id: string) =>
  idempotentPost<{ total: Num; skipped: unknown[] }>(`${C}/orders/${id}/absorb`, {}));

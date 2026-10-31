import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import type { PaginatedResult } from '@erp/shared';
import { api } from '@/lib/api';

/**
 * Advanced planning & scheduling (ADR-S017, Phase 2.5): demand and pegging, plans from demand, order priorities,
 * backward scheduling, the Gantt board, capacity, aggregate crushing plan, shift plans, targets, plan performance.
 */

const P = '/manufacturing/planning';
const KEY = ['planning'];

export type Num = string | number;
export type Filters = Record<string, string | boolean | undefined>;

export interface DemandRow {
  demandType: string; demandId: string | null; ref: string | null; productId: string; productCode: string | null;
  productName: string | null; partnerName: string | null; siteName: string | null; dueAt: string; priority: number;
  quantity: Num; coveredQty: Num; uncoveredQty: Num;
}
export interface DemandProduct {
  productId: string; productCode: string | null; productName: string | null; demandQty: Num; onHandQty: Num;
  supplyQty: Num; shortfallQty: Num; makeable: boolean;
}
export interface DemandView { from: string; to: string; rows: DemandRow[]; products: DemandProduct[] }
export interface ForecastProduct {
  productId: string; code: string | null; name: string | null; family: 'aggregate' | 'block' | null; level: Num;
  trendPerWeek: Num; meanAbsoluteError: Num; nextWeekQty: Num; horizonQty: Num; confidence: 'low' | 'medium' | 'high';
}
export interface ForecastView { from: string; to: string; weeks: number; products: ForecastProduct[] }
export interface BoardOrder {
  id: string; orderCode: string; status: string; productName: string | null; plannedQty: Num; dueAt: string | null;
  priority: number; priorityReason: string | null; partnerName: string | null; scheduleMode: string;
  plannedStartAt: string | null; plannedEndAt: string | null; curingDays: number; lateMinutes: number;
}
export interface GanttTask {
  id: string; name: string; status: string; workCenterId: string | null; plannedStartAt: string; plannedEndAt: string;
  productionOrderId: string; orderCode: string; productName: string | null; dueAt: string | null; priority: number;
  partnerName: string | null; late: boolean;
}
export interface GanttLane {
  workCenterId: string; code: string; name: string; parallelSlots: number; closedDates: string[]; tasks: GanttTask[];
}
export interface GanttView { from: string; to: string; lanes: GanttLane[]; unscheduled: GanttTask[] }
export interface CapacityBucket { from: string; to: string; capacityHours: Num; loadHours: Num; utilisationPct: Num | null; overloaded: boolean }
export interface CapacityRow {
  workCenterId: string; code: string; name: string; capacityHours: Num; loadHours: Num; unscheduledHours: Num;
  utilisationPct: Num | null; ratePerHour: Num | null; capacityQty: Num | null; loadQty: Num | null;
  isBottleneck: boolean; buckets: CapacityBucket[];
}
export interface PlantCapacity {
  plantId: string; code: string; name: string; designCapacityTph: Num | null; bottleneckTph: Num | null;
  bottleneck: string | null; availableHours: Num | null; capacityTonnes: Num | null;
}
export interface CapacityView {
  workCenters: CapacityRow[]; plants: PlantCapacity[];
  bottleneck: { workCenterId: string; name: string; utilisationPct: Num } | null;
}
export interface AggregateFraction {
  productId: string; name: string; sizeLabel: string | null; demandQty: Num; onHandQty: Num; netDemandQty: Num;
  expectedOutputQty: Num; surplusQty: Num;
}
export interface AggregatePlan {
  profileId: string; plantName: string; profileName: string; feedProductName: string; feedTonnes: Num; feedOnHand: Num;
  feedShortfall: Num; bottleneck: string | null; hoursRequired: Num | null; hoursAvailable: Num | null;
  feasible: boolean | null; fractions: AggregateFraction[];
}
export interface ShiftPlan {
  id: string; shiftDate: string; shiftId: string; shiftName: string; workCenterId: string; workCenterName: string;
  orderCode: string | null; productName: string | null; targetQty: Num; actualQty: Num; attainmentPct: Num | null;
  status: 'draft' | 'published';
}
export interface Target {
  id: string; scope: string; subjectName: string | null; periodFrom: string; periodTo: string; targetQty: Num;
}
export interface ShiftPlanInput { shiftDate: string; shiftId: string; workCenterId: string; targetQty: number; notes?: string }
export interface TargetInput {
  scope: string; workCenterId?: string; plantId?: string; productId?: string; periodFrom: string; periodTo: string;
  targetQty: number;
}

function get<T>(path: string, params?: Filters) {
  return api.get<T>(`${P}${path}`, { params }).then((r) => r.data);
}

function useChange<V, R = unknown>(fn: (v: V) => Promise<R>) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: fn,
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: KEY });
      qc.invalidateQueries({ queryKey: ['production-orders'] });
    },
  });
}

export const useDemand = (f: Filters) => useQuery({ queryKey: [...KEY, 'demand', f], queryFn: () => get<DemandView>('/demand', f) });
export const usePlanBoard = () => useQuery({ queryKey: [...KEY, 'orders'], queryFn: () => get<BoardOrder[]>('/orders') });
export const useGantt = (f: Filters) => useQuery({ queryKey: [...KEY, 'gantt', f], queryFn: () => get<GanttView>('/gantt', f) });
export const useCapacity = (f: Filters) => useQuery({ queryKey: [...KEY, 'capacity', f], queryFn: () => get<CapacityView>('/capacity', f) });
export const useAggregates = (f: Filters) =>
  useQuery({ queryKey: [...KEY, 'aggregates', f], queryFn: () => get<{ plans: AggregatePlan[] }>('/aggregates', f) });
export const useShiftPlans = (f: Filters) =>
  useQuery({ queryKey: [...KEY, 'shift-plans', f], queryFn: () => get<PaginatedResult<ShiftPlan>>('/shift-plans', f) });
export const useTargets = (f: Filters) => useQuery({ queryKey: [...KEY, 'targets', f], queryFn: () => get<Target[]>('/targets', f) });
export const usePlanReport = <T,>(name: string, f: Filters) =>
  useQuery({ queryKey: [...KEY, 'report', name, f], queryFn: () => get<T>(`/reports/${name}`, f) });

export const useDemandForecast = (f: Filters) =>
  useQuery({ queryKey: [...KEY, 'forecast', f], queryFn: () => get<ForecastView>('/forecast', f) });

export const usePlanFromDemand = () => useChange((b: { dateTo: string; locationId: string; includeForecast: boolean; forecastMethod?: string }) =>
  api.post(`${P}/plans/from-demand`, b).then((r) => r.data));
export const useSaveOrderPlanning = () => useChange((v: { id: string; dueAt?: string; priority?: number }) =>
  api.put(`${P}/orders/${v.id}`, { dueAt: v.dueAt, priority: v.priority }).then((r) => r.data));
export const useScheduleBackward = () => useChange((id: string) => api.post(`${P}/orders/${id}/schedule-backward`, {}).then((r) => r.data));
export const useScheduleOpen = () => useChange((_: void) => api.post(`${P}/schedule-open-orders`, {}).then((r) => r.data));
export const useReschedule = () => useChange((v: { id: string; startAt: string; workCenterId?: string }) =>
  api.post<{ warnings: string[]; snapped: boolean }>(`${P}/work-orders/${v.id}/reschedule`,
    { startAt: v.startAt, workCenterId: v.workCenterId }).then((r) => r.data));
export const useCreateShiftPlan = () => useChange((b: ShiftPlanInput) => api.post(`${P}/shift-plans`, b).then((r) => r.data));
export const usePublishShiftPlan = () => useChange((id: string) => api.post(`${P}/shift-plans/${id}/publish`).then((r) => r.data));
export const useDeleteShiftPlan = () => useChange((id: string) => api.delete(`${P}/shift-plans/${id}`).then((r) => r.data));
export const useCreateTarget = () => useChange((b: TargetInput) => api.post(`${P}/targets`, b).then((r) => r.data));
export const useDeleteTarget = () => useChange((id: string) => api.delete(`${P}/targets/${id}`).then((r) => r.data));

export const qty = (v: Num | null | undefined) =>
  v == null ? '-' : Number(v).toLocaleString(undefined, { maximumFractionDigits: 3 });
export const pct = (v: Num | null | undefined) => (v == null ? '-' : `${Number(v).toFixed(1)} %`);

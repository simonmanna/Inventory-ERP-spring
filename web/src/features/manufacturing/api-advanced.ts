import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from '@/lib/api';

/**
 * ADR-S006 production-ready manufacturing: MRP runs + planning parameters, shop floor (calendars, scheduling, time
 * logs, scrap reasons, QC plans, rework) and costing & engineering (roll-up, standards, variances, substitutes, ECOs).
 */

export interface PlannedOrder {
  id: string;
  productId: string;
  kind: 'make' | 'buy';
  quantity: string;
  dueDate: string;
  releaseDate: string;
  lowLevelCode: number;
  status: 'planned' | 'firmed' | 'cancelled';
  firmedDocType?: string | null;
  firmedDocId?: string | null;
  supplierId?: string | null;
  pegging: Array<{ type: string; id: string; qty: string }>;
}

export interface MrpRunItem {
  id: string;
  productId: string;
  lowLevelCode: number;
  procurement: string;
  grossRequirement: string;
  onHand: string;
  reserved: string;
  scheduledReceipts: string;
  safetyStock: string;
  netRequirement: string;
  plannedQty: string;
}

export interface MrpRun {
  id: string;
  runNumber: string;
  status: string;
  horizonDays: number;
  startedAt: string;
  summary: { products?: number; plannedOrders?: number; make?: number; buy?: number; late?: number };
  items?: MrpRunItem[];
  plannedOrders?: PlannedOrder[];
}

export interface PlanningParams {
  id?: string;
  productId: string;
  procurement: 'make' | 'buy';
  leadTimeDays: number;
  safetyStock: string | number;
  reorderPoint: string | number;
  minOrderQty: string | number;
  orderMultiple: string | number;
  supplierId?: string | null;
  locationId?: string | null;
  isActive?: boolean;
  product?: { id: string; code: string; name: string };
}

const get = async <T,>(url: string, params?: Record<string, unknown>) => (await api.get<T>(url, { params })).data;

// ---- MRP ----
export function useMrpRuns() {
  return useQuery({ queryKey: ['mrp-runs'], queryFn: () => get<MrpRun[]>('/manufacturing/mrp/runs', { limit: 20 }) });
}
export function useMrpRun(id?: string) {
  return useQuery({ queryKey: ['mrp-run', id], queryFn: () => get<MrpRun>(`/manufacturing/mrp/runs/${id}`), enabled: !!id });
}
export function useRunMrp() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (body: { horizonDays?: number; locationId?: string }) =>
      (await api.post<MrpRun>('/manufacturing/mrp/runs', body)).data,
    onSuccess: () => qc.invalidateQueries({ queryKey: ['mrp-runs'] }),
  });
}
export function usePlannedOrderAction() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async ({ id, action }: { id: string; action: 'firm' | 'cancel' }) =>
      (await api.post(`/manufacturing/mrp/planned-orders/${id}/${action}`, {})).data,
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['mrp-run'] });
      qc.invalidateQueries({ queryKey: ['production-orders'] });
    },
  });
}
export function useFirmRun() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async ({ runId, ids }: { runId: string; ids?: string[] }) =>
      (await api.post(`/manufacturing/mrp/runs/${runId}/firm`, ids ? { ids } : {})).data,
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['mrp-run'] });
      qc.invalidateQueries({ queryKey: ['production-orders'] });
    },
  });
}
export function usePlanningParams() {
  return useQuery({ queryKey: ['planning-params'], queryFn: () => get<PlanningParams[]>('/manufacturing/planning-params') });
}
export function useSavePlanningParams() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async ({ productId, ...body }: PlanningParams) =>
      (await api.put(`/manufacturing/planning-params/${productId}`, body)).data,
    onSuccess: () => qc.invalidateQueries({ queryKey: ['planning-params'] }),
  });
}

// ---- Shop floor ----
export function useSchedule(params: { from?: string; to?: string; workCenterId?: string }) {
  return useQuery({ queryKey: ['mfg-schedule', params], queryFn: () => get<any[]>('/manufacturing/schedule', params) });
}
export function useScheduleOrder() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async ({ orderId, startAt }: { orderId: string; startAt?: string }) =>
      (await api.post(`/manufacturing/production-orders/${orderId}/schedule`, startAt ? { startAt } : {})).data,
    onSuccess: (_, { orderId }) => {
      qc.invalidateQueries({ queryKey: ['work-orders', orderId] });
      qc.invalidateQueries({ queryKey: ['mfg-schedule'] });
    },
  });
}
export function useCalendar(workCenterId?: string) {
  return useQuery({
    queryKey: ['wc-calendar', workCenterId],
    queryFn: () => get<any>(`/manufacturing/work-centers/${workCenterId}/calendar`),
    enabled: !!workCenterId,
  });
}
export function useSaveCalendar() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async ({ workCenterId, body }: { workCenterId: string; body: any }) =>
      (await api.put(`/manufacturing/work-centers/${workCenterId}/calendar`, body)).data,
    onSuccess: (_, { workCenterId }) => qc.invalidateQueries({ queryKey: ['wc-calendar', workCenterId] }),
  });
}
export function useTimeLogs(workOrderId?: string) {
  return useQuery({
    queryKey: ['time-logs', workOrderId],
    queryFn: () => get<any[]>(`/manufacturing/work-orders/${workOrderId}/time-logs`),
    enabled: !!workOrderId,
  });
}
export function useAddTimeLog() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async ({ workOrderId, body }: { workOrderId: string; orderId: string; body: any }) =>
      (await api.post(`/manufacturing/work-orders/${workOrderId}/time-logs`, body)).data,
    onSuccess: (_, { workOrderId, orderId }) => {
      qc.invalidateQueries({ queryKey: ['time-logs', workOrderId] });
      qc.invalidateQueries({ queryKey: ['work-orders', orderId] });
    },
  });
}
function useCrud(path: 'scrap-reasons' | 'qc-plans') {
  const qc = useQueryClient();
  const list = useQuery({ queryKey: [path], queryFn: () => get<any[]>(`/manufacturing/${path}`) });
  const save = useMutation({
    mutationFn: async ({ id, body }: { id?: string; body: any }) =>
      (id ? await api.patch(`/manufacturing/${path}/${id}`, body) : await api.post(`/manufacturing/${path}`, body)).data,
    onSuccess: () => qc.invalidateQueries({ queryKey: [path] }),
  });
  const remove = useMutation({
    mutationFn: async (id: string) => api.delete(`/manufacturing/${path}/${id}`),
    onSuccess: () => qc.invalidateQueries({ queryKey: [path] }),
  });
  return { list, save, remove };
}
export const useScrapReasons = () => useCrud('scrap-reasons');
export const useQcPlans = () => useCrud('qc-plans');
export function useRework() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async ({ orderId, body }: { orderId: string; body?: any }) =>
      (await api.post(`/manufacturing/production-orders/${orderId}/rework`, body ?? {})).data,
    onSuccess: () => qc.invalidateQueries({ queryKey: ['production-orders'] }),
  });
}
export function useOrderQcPlan(orderId: string) {
  return useQuery({ queryKey: ['order-qc-plan', orderId], queryFn: () => get<any>(`/manufacturing/production-orders/${orderId}/qc-plan`) });
}

// ---- Costing & engineering ----
export function useBomCost(bomId?: string) {
  return useQuery({ queryKey: ['bom-cost', bomId], queryFn: () => get<any>(`/manufacturing/boms/${bomId}/cost`), enabled: !!bomId });
}
export function useStandardCosts(bomId?: string) {
  return useQuery({ queryKey: ['bom-standards', bomId], queryFn: () => get<any[]>(`/manufacturing/boms/${bomId}/standard-costs`), enabled: !!bomId });
}
export function useFreezeStandard() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (bomId: string) => (await api.post(`/manufacturing/boms/${bomId}/standard-cost`, {})).data,
    onSuccess: (_, bomId) => qc.invalidateQueries({ queryKey: ['bom-standards', bomId] }),
  });
}
export function useBomSubstitutes(bomId?: string) {
  return useQuery({ queryKey: ['bom-substitutes', bomId], queryFn: () => get<any[]>(`/manufacturing/boms/${bomId}/substitutes`), enabled: !!bomId });
}
export function useSaveSubstitutes() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async ({ bomId, lineId, substitutes }: { bomId: string; lineId: string; substitutes: any[] }) =>
      (await api.put(`/manufacturing/boms/${bomId}/lines/${lineId}/substitutes`, { substitutes })).data,
    onSuccess: (_, { bomId }) => qc.invalidateQueries({ queryKey: ['bom-substitutes', bomId] }),
  });
}
export function useReviseBom() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async ({ bomId, title, reason }: { bomId: string; title?: string; reason?: string }) =>
      (await api.post(`/manufacturing/boms/${bomId}/revise`, { title, reason })).data,
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['boms'] });
      qc.invalidateQueries({ queryKey: ['ecos'] });
    },
  });
}
export function useBomCompare(fromId?: string, toId?: string) {
  return useQuery({
    queryKey: ['bom-compare', fromId, toId],
    queryFn: () => get<any>(`/manufacturing/boms/${fromId}/compare/${toId}`),
    enabled: !!fromId && !!toId,
  });
}
export function useEcos() {
  return useQuery({ queryKey: ['ecos'], queryFn: () => get<any[]>('/manufacturing/engineering-changes') });
}
export function useEcoAction() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async ({ id, action }: { id: string; action: 'approve' | 'reject' | 'apply' }) =>
      (await api.post(`/manufacturing/engineering-changes/${id}/${action}`, {})).data,
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['ecos'] });
      qc.invalidateQueries({ queryKey: ['boms'] });
    },
  });
}
export function useVariances(params: { from?: string; to?: string; orderId?: string }, enabled = true) {
  return useQuery({ queryKey: ['mfg-variances', params], queryFn: () => get<any>('/manufacturing/reports/variances', params), enabled });
}
export function useWhereUsed(productId?: string) {
  return useQuery({ queryKey: ['where-used', productId], queryFn: () => get<any[]>(`/manufacturing/reports/where-used/${productId}`), enabled: !!productId });
}
export function useAvailability(orderId: string, enabled = true) {
  return useQuery({ queryKey: ['mfg-availability', orderId], queryFn: () => get<any[]>(`/manufacturing/production-orders/${orderId}/availability`), enabled });
}

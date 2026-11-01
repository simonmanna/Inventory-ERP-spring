import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from '@/lib/api';

/* ── Warehouse management (roadmap 2.1, ADR-S026) ──────────────── */

type Num = string | number;

export interface WarehouseSettings {
  locationId: string;
  enabled: boolean;
  outboundSteps: number;
  abcAPercent: Num;
  abcBPercent: Num;
  abcLookbackDays: number;
  countDaysA: number;
  countDaysB: number;
  countDaysC: number;
  configured: boolean;
}

export interface WarehouseOverviewRow {
  productId: string;
  variantKey: string;
  productName: string;
  sku: string | null;
  locationQty: Num;
  binnedQty: Num;
  stagedQty: Num;
  putawayQty: Num;
  unbinnedQty: Num;
  mismatch: boolean;
}

export interface WarehouseOverview {
  locationId: string;
  settings: WarehouseSettings;
  bins: number;
  openTasks: number;
  openPickOrders: number;
  unbinnedQty: Num;
  products: WarehouseOverviewRow[];
}

export interface WarehouseBin {
  id: string;
  parentId: string | null;
  code: string;
  name: string | null;
  kind: 'zone' | 'aisle' | 'rack' | 'bin';
  usage: 'storage' | 'receiving' | 'output' | 'quarantine';
  pickSequence: number;
  maxQty: Num | null;
  mixedProducts: boolean;
  isActive: boolean;
  depth: number;
  path: string;
  quantity: Num;
  products: number;
}

export interface PutawayRule {
  id: string;
  productId: string | null;
  productName: string | null;
  categoryId: string | null;
  categoryName: string | null;
  targetBinId: string | null;
  targetBinCode: string | null;
  targetZoneId: string | null;
  targetZoneCode: string | null;
  priority: number;
  isActive: boolean;
}

export interface WarehouseTask {
  id: string;
  code: string;
  type: 'putaway' | 'move' | 'pick';
  status: 'open' | 'done' | 'cancelled';
  productId: string;
  productName: string;
  quantity: Num;
  doneQty: Num | null;
  fromBinCode: string | null;
  toBinCode: string | null;
  pickOrderCode: string | null;
  sequence: number;
}

export interface PickLine {
  id: string;
  productId: string;
  productName: string;
  quantity: Num;
  pickedQty: Num;
  packedQty: Num;
  shippedQty: Num;
}

export interface PickOrder {
  id: string;
  code: string;
  sourceType: string;
  sourceRef: string | null;
  steps: number;
  status: string;
  priority: number;
  waveId: string | null;
  waveCode?: string | null;
  lineCount?: number;
  totalQty?: Num;
  pickedQty?: Num;
  carrier: string | null;
  trackingRef: string | null;
  createdAt: string;
  lines?: PickLine[];
  tasks?: WarehouseTask[];
  packages?: { id: string; code: string; packageType: string | null; weightKg: Num | null; status: string }[];
}

export interface Wave {
  id: string;
  code: string;
  status: string;
  orderCount?: number;
  createdAt: string;
}

export interface AbcRow {
  productId: string;
  productName: string;
  sku: string | null;
  abcClass: 'A' | 'B' | 'C';
  consumptionValue: Num;
  cumulativeShare: Num;
  lastCountedAt: string | null;
  nextCountDue: string | null;
  countEveryDays: number;
  due: boolean;
  scheduled: boolean;
}

const KEY = ['warehouse'];

export function useWarehouseOverview(locationId?: string) {
  return useQuery({
    queryKey: [...KEY, 'overview', locationId],
    enabled: !!locationId,
    queryFn: async () => (await api.get<WarehouseOverview>(`/warehouse/locations/${locationId}/overview`)).data,
  });
}

export function useWarehouseBins(locationId?: string) {
  return useQuery({
    queryKey: [...KEY, 'bins', locationId],
    enabled: !!locationId,
    queryFn: async () =>
      (await api.get<WarehouseBin[]>('/warehouse/bins', { params: { locationId, includeInactive: 'true' } })).data,
  });
}

export function usePutawayRules(locationId?: string) {
  return useQuery({
    queryKey: [...KEY, 'rules', locationId],
    enabled: !!locationId,
    queryFn: async () => (await api.get<PutawayRule[]>('/warehouse/putaway-rules', { params: { locationId } })).data,
  });
}

export function useWarehouseTasks(locationId?: string, status = 'open') {
  return useQuery({
    queryKey: [...KEY, 'tasks', locationId, status],
    enabled: !!locationId,
    queryFn: async () => (await api.get<WarehouseTask[]>('/warehouse/tasks', { params: { locationId, status } })).data,
  });
}

export function usePickOrders(locationId?: string) {
  return useQuery({
    queryKey: [...KEY, 'pick-orders', locationId],
    enabled: !!locationId,
    queryFn: async () => (await api.get<PickOrder[]>('/warehouse/pick-orders', { params: { locationId } })).data,
  });
}

export function usePickOrder(id?: string) {
  return useQuery({
    queryKey: [...KEY, 'pick-order', id],
    enabled: !!id,
    queryFn: async () => (await api.get<PickOrder>(`/warehouse/pick-orders/${id}`)).data,
  });
}

export function useWaves(locationId?: string) {
  return useQuery({
    queryKey: [...KEY, 'waves', locationId],
    enabled: !!locationId,
    queryFn: async () => (await api.get<Wave[]>('/warehouse/waves', { params: { locationId } })).data,
  });
}

export function useAbc(locationId?: string) {
  return useQuery({
    queryKey: [...KEY, 'abc', locationId],
    enabled: !!locationId,
    queryFn: async () =>
      (await api.get<{ rows: AbcRow[]; due: number }>(`/warehouse/locations/${locationId}/abc`)).data,
  });
}

/** Any warehouse write; every warehouse query is refreshed afterwards. */
export function useWarehouseAction() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async ({ method, path, body }: { method: 'post' | 'put' | 'patch' | 'delete'; path: string; body?: unknown }) =>
      (method === 'delete' ? await api.delete(path) : await api[method](path, body ?? {})).data,
    onSuccess: () => qc.invalidateQueries({ queryKey: KEY }),
  });
}

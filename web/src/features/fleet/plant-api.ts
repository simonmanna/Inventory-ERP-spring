import { useQuery } from '@tanstack/react-query';
import type { PaginatedResult } from '@erp/shared';
import { api } from '@/lib/api';
import type { VehicleCategory, VehicleStatus } from './api';

// ---- Fixed plant (CMMS-1): the fleet register seen as a plant register; maintenance runs on the 1.6 routes ----

export interface PlantUnit {
  id: string;
  code: string;
  name: string | null;
  make: string | null;
  model: string | null;
  status: VehicleStatus;
  categoryName: string;
  categoryCode: string;
  branchName: string | null;
  locationCode: string | null;
  locationName: string | null;
  assetId: string | null;
  assetCode: string | null;
  assetStatus: string | null;
  lifetimeHours: string | null;
  openWorkOrders: number;
  openDowntime: number;
}

export interface PlantCostRow {
  vehicleId?: string;
  code?: string;
  name?: string | null;
  categoryName?: string;
  branchName?: string | null;
  status?: VehicleStatus;
  depreciation: string;
  fuel: string;
  maintenance: string;
  total: string;
  hours: string | null;
  perHour: string | null;
  workOrders: number;
  downtimeHours: string;
}

export interface PlantCostReport {
  from: string;
  to: string;
  rows: PlantCostRow[];
  totals: PlantCostRow;
}

const KEY = ['fleet', 'plant'];

export function usePlantClasses() {
  return useQuery({
    queryKey: [...KEY, 'classes'],
    staleTime: 60_000,
    queryFn: async () => (await api.get<VehicleCategory[]>('/fleet/plant/classes')).data,
  });
}

export function usePlantUnits(params: {
  page?: number; pageSize?: number; search?: string; categoryId?: string; status?: string; branchId?: string; locationId?: string;
}) {
  return useQuery({
    queryKey: [...KEY, 'list', params],
    queryFn: async () => (await api.get<PaginatedResult<PlantUnit>>('/fleet/plant', { params })).data,
  });
}

export function usePlantCosts(params: { from?: string; to?: string; categoryId?: string; branchId?: string; locationId?: string }) {
  return useQuery({
    queryKey: [...KEY, 'costs', params],
    queryFn: async () => (await api.get<PlantCostReport>('/fleet/plant/costs', { params })).data,
  });
}

/** The plant unit maintained for a fixed asset; only asked when the fleet module and permission are present. */
export function usePlantByAsset(assetId: string, enabled: boolean) {
  return useQuery({
    queryKey: [...KEY, 'by-asset', assetId],
    enabled,
    retry: false,
    queryFn: async () => (await api.get<{ unit: { id: string; code: string; name: string | null; status: VehicleStatus; categoryName: string } | null }>(
      `/fleet/plant/by-asset/${assetId}`,
    )).data,
  });
}

import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from '@/lib/api';
import { idempotentPost } from '@/lib/idempotent-request';
import type { InspectionTemplate } from '@/features/fleet/maintenance-api';

// ---- Driver web app (DRIVER-1): everything is scoped by the server to the signed-in driver ----

export interface DriverUnit {
  id: string;
  code: string;
  name: string | null;
  registrationPlate: string | null;
  make: string | null;
  model: string | null;
  status: string;
  meterType: 'odometer' | 'hours' | 'both' | 'none';
  currentOdometer: string | null;
  currentHours: string | null;
  categoryName: string;
  openDefects: number;
  preTripToday: 'pass' | 'fail' | null;
}

export interface DriverHome {
  driver: { id: string; code: string; firstName: string; lastName: string | null; licenceExpiresOn: string | null; medicalExpiresOn: string | null };
  units: DriverUnit[];
  liveTrips: number;
}

export interface FuelOptions {
  tanks: Array<{ id: string; code: string; name: string }>;
  cards: Array<{ id: string; maskedNumber: string; label: string | null }>;
  paymentAccounts: Array<{ id: string; name: string }>;
  suppliers: Array<{ id: string; name: string }>;
}

export interface DriverFuelLog {
  id: string;
  code: string;
  fuelledAt: string;
  source: 'station' | 'tank';
  litres: string;
  amount: string | null;
  status: string;
  anomalyCount: number;
  vehicleCode: string;
  vehiclePlate: string | null;
}

export interface PreTrip {
  unit: Pick<DriverUnit, 'id' | 'code' | 'name' | 'registrationPlate' | 'meterType' | 'currentOdometer' | 'currentHours'>;
  template: InspectionTemplate | null;
}

export interface DriverInspectionResult {
  id: string;
  code: string;
  result: 'pass' | 'fail';
  defectCount: number;
}

const KEY = ['driver-app'];

export function useDriverHome() {
  return useQuery({
    queryKey: [...KEY, 'home'],
    retry: false,
    queryFn: async () => (await api.get<DriverHome>('/fleet/driver-app/home')).data,
  });
}

export function useFuelOptions(vehicleId: string) {
  return useQuery({
    queryKey: [...KEY, 'fuel-options', vehicleId],
    enabled: !!vehicleId,
    staleTime: 60_000,
    queryFn: async () => (await api.get<FuelOptions>('/fleet/driver-app/fuel-options', { params: { vehicleId } })).data,
  });
}

export function useMyFuelLogs() {
  return useQuery({
    queryKey: [...KEY, 'fuel-logs'],
    queryFn: async () => (await api.get<DriverFuelLog[]>('/fleet/driver-app/fuel-logs')).data,
  });
}

export function usePreTrip(vehicleId: string) {
  return useQuery({
    queryKey: [...KEY, 'pre-trip', vehicleId],
    enabled: !!vehicleId,
    queryFn: async () => (await api.get<PreTrip>('/fleet/driver-app/pre-trip', { params: { vehicleId } })).data,
  });
}

function upload(path: string, file: File, params?: Record<string, string>) {
  const fd = new FormData();
  fd.append('file', file);
  return api.post(path, fd, { params, headers: { 'Content-Type': 'multipart/form-data' } });
}

export function useCreateDriverFuelLog() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async ({ body, receipt }: { body: Record<string, unknown>; receipt?: File | null }) => {
      const log = await idempotentPost<{ id: string; code: string }>('/fleet/driver-app/fuel-logs', body);
      if (receipt) await upload(`/fleet/driver-app/fuel-logs/${log.id}/receipt`, receipt);
      return log;
    },
    onSuccess: () => qc.invalidateQueries({ queryKey: KEY }),
  });
}

export function useRecordPreTrip() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async ({ body, photos }: { body: Record<string, unknown>; photos: Array<{ itemCode: string; file: File }> }) => {
      const result = await idempotentPost<DriverInspectionResult>('/fleet/driver-app/inspections', body);
      for (const p of photos) {
        await upload(`/fleet/driver-app/inspections/${result.id}/photos`, p.file, { itemCode: p.itemCode });
      }
      return result;
    },
    onSuccess: () => qc.invalidateQueries({ queryKey: KEY }),
  });
}

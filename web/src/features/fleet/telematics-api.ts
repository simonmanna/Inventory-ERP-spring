import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import type { PaginatedResult } from '@erp/shared';
import { api } from '@/lib/api';
import { tx } from '@/lib/i18n/tx';

// ---- Types (fleet 1.8 — telematics SPI, ADR-S009 §7) ----

export interface TelematicsSettings {
  provider: string;
  available: string[];
  baseUrlConfigured: boolean;
  apiKeyConfigured: boolean;
  retentionDays: number;
  syncIntervalMinutes: number;
}

export interface TelematicsDevice {
  id: string;
  vehicleId: string | null;
  provider: string;
  externalUnitId: string;
  name: string | null;
  imei: string | null;
  simNumber: string | null;
  isActive: boolean;
  syncOdometer: boolean;
  syncHours: boolean;
  lastSyncAt: string | null;
  lastSyncStatus: 'ok' | 'partial' | 'error' | null;
  lastError: string | null;
  lastPositionAt: string | null;
  lastLatitude: string | null;
  lastLongitude: string | null;
  lastSpeedKmh: string | null;
  lastIgnition: boolean | null;
  lastOdometerKm: string | null;
  lastEngineHours: string | null;
  notes: string | null;
  vehicle: { id: string; code: string; registrationPlate: string | null; name: string | null } | null;
}

export interface LatestPosition {
  deviceId: string;
  externalUnitId: string;
  provider: string;
  lastPositionAt: string | null;
  lastLatitude: string | null;
  lastLongitude: string | null;
  lastSpeedKmh: string | null;
  lastIgnition: boolean | null;
  lastSyncAt: string | null;
  lastSyncStatus: string | null;
  lastError: string | null;
  lastOdometerKm: string | null;
  vehicleId: string;
  code: string;
  registrationPlate: string | null;
  name: string | null;
  status: string;
  openAlerts: number;
}

export interface PositionRow {
  id: string;
  recordedAt: string;
  latitude: string;
  longitude: string;
  speedKmh: string | null;
  ignition: boolean | null;
}

export interface TelematicsEvent {
  id: string;
  vehicleId: string;
  eventType: string;
  occurredAt: string;
  latitude: string | null;
  longitude: string | null;
  speedKmh: string | null;
  data: Record<string, unknown>;
  isAlert: boolean;
  severity: 'info' | 'warning' | 'critical';
  acknowledgedAt: string | null;
  acknowledgeNote: string | null;
  acknowledgedByName: string | null;
  vehicle: { id: string; code: string; registrationPlate: string | null };
}

export interface SyncResult {
  devices: number;
  positions: number;
  events: number;
  alerts: number;
  meterReadings: number;
  errors: number;
  purged: number;
}

export const EVENT_TYPE_LABEL: Record<string, string> = {
  ignition_on: tx('Ignition on'),
  ignition_off: tx('Ignition off'),
  speeding: tx('Speeding'),
  idling: tx('Idling'),
  geofence_enter: tx('Geofence entered'),
  geofence_exit: tx('Geofence exited'),
  harsh_braking: tx('Harsh braking'),
  harsh_acceleration: tx('Harsh acceleration'),
  other: tx('Other'),
};

/** Link to the coordinates on a map (opens in a new tab). */
export const mapUrl = (lat: string | number, lon: string | number) =>
  `https://www.openstreetmap.org/?mlat=${lat}&mlon=${lon}#map=15/${lat}/${lon}`;

// ---- Queries ----

const KEY = ['fleet', 'telematics'] as const;

export function useTelematicsSettings(enabled = true) {
  return useQuery({
    queryKey: [...KEY, 'settings'],
    enabled,
    queryFn: async () => (await api.get<TelematicsSettings>('/fleet/telematics/settings')).data,
  });
}

export function useTelematicsDevices(params: { includeInactive?: boolean; vehicleId?: string }) {
  return useQuery({
    queryKey: [...KEY, 'devices', params],
    queryFn: async () => (await api.get<TelematicsDevice[]>('/fleet/telematics/devices', { params })).data,
  });
}

export function useLatestPositions(enabled = true) {
  return useQuery({
    queryKey: [...KEY, 'latest'],
    enabled,
    refetchInterval: 60_000,
    queryFn: async () => (await api.get<LatestPosition[]>('/fleet/telematics/positions/latest')).data,
  });
}

export function useVehiclePositions(vehicleId: string, page: number, enabled: boolean) {
  return useQuery({
    queryKey: [...KEY, 'positions', vehicleId, page],
    enabled,
    placeholderData: (prev) => prev,
    queryFn: async () =>
      (await api.get<PaginatedResult<PositionRow>>(`/fleet/vehicles/${vehicleId}/positions`, { params: { page, pageSize: 20 } })).data,
  });
}

export function useVehicleDistance(vehicleId: string, hours: number, enabled: boolean) {
  return useQuery({
    queryKey: [...KEY, 'distance', vehicleId, hours],
    enabled,
    queryFn: async () => {
      const to = new Date();
      const from = new Date(to.getTime() - hours * 3_600_000);
      return (await api.get<{ distanceKm: string; points: number }>(`/fleet/vehicles/${vehicleId}/distance`, {
        params: { from: from.toISOString(), to: to.toISOString() },
      })).data;
    },
  });
}

export function useLastPosition(vehicleId: string, enabled: boolean) {
  return useQuery({
    queryKey: [...KEY, 'last', vehicleId],
    enabled,
    queryFn: async () =>
      (await api.get<{ vehicleId: string; position: { at: string; latitude: string; longitude: string; speedKmh: string | null } | null }>(
        `/fleet/vehicles/${vehicleId}/last-position`)).data,
  });
}

export interface EventListParams {
  page: number;
  pageSize: number;
  vehicleId?: string;
  eventType?: string;
  alertsOnly?: boolean;
  open?: boolean;
}

export function useTelematicsEvents(params: EventListParams, enabled = true) {
  return useQuery({
    queryKey: [...KEY, 'events', params],
    enabled,
    placeholderData: (prev) => prev,
    queryFn: async () => (await api.get<PaginatedResult<TelematicsEvent>>('/fleet/telematics/events', { params })).data,
  });
}

// ---- Mutations ----

function useTelematicsMutation<TVars, TResult = unknown>(fn: (vars: TVars) => Promise<TResult>) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: fn,
    onSuccess: () => qc.invalidateQueries({ queryKey: ['fleet'] }),
  });
}

export const useCreateDevice = () =>
  useTelematicsMutation(async (body: Record<string, unknown>) => (await api.post<TelematicsDevice>('/fleet/telematics/devices', body)).data);
export const useUpdateDevice = () =>
  useTelematicsMutation(async ({ id, body }: { id: string; body: Record<string, unknown> }) =>
    (await api.patch<TelematicsDevice>(`/fleet/telematics/devices/${id}`, body)).data);
export const useDeleteDevice = () =>
  useTelematicsMutation(async (id: string) => (await api.delete<{ deleted: boolean; deactivated: boolean }>(`/fleet/telematics/devices/${id}`)).data);
export const useSyncTelematics = () =>
  useTelematicsMutation(async () => (await api.post<SyncResult>('/fleet/telematics/sync')).data);
export const useAcknowledgeAlert = () =>
  useTelematicsMutation(async ({ id, note }: { id: string; note?: string }) =>
    (await api.post<TelematicsEvent>(`/fleet/telematics/events/${id}/acknowledge`, { note: note || undefined })).data);

import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import type { PaginatedResult } from '@erp/shared';
import { api } from '@/lib/api';
import { tx } from '@/lib/i18n/tx';

// ---- Types (fleet 1.1 — vehicle & equipment registry, ADR-S009) ----

export type VehicleStatus = 'active' | 'in_workshop' | 'breakdown' | 'idle' | 'disposed';
export type Ownership = 'owned' | 'leased' | 'hired' | 'third_party';
export type MeterType = 'odometer' | 'hours' | 'both' | 'none';
export type CategoryKind = 'road_vehicle' | 'trailer' | 'mobile_plant' | 'fixed_plant' | 'other';
export type DocType = 'registration' | 'insurance' | 'road_licence' | 'inspection' | 'operator_permit' | 'other';
export type ComplianceStateValue = 'expired' | 'expiring' | 'valid' | 'no_expiry' | 'renewed';

export interface Ref {
  id: string;
  code: string | null;
  name: string;
}

export interface VehicleCategory {
  id: string;
  code: string;
  name: string;
  kind: CategoryKind;
  defaultMeterType: MeterType;
  description: string | null;
  sortOrder: number;
  isSystem: boolean;
  isActive: boolean;
  vehicleCount?: number;
}

export interface MeterValue {
  value: string;
  lifetime: string;
  at: string | null;
}

export interface Vehicle {
  id: string;
  code: string;
  name: string | null;
  registrationPlate: string | null;
  vin: string | null;
  engineNumber: string | null;
  make: string | null;
  model: string | null;
  year: number | null;
  color: string | null;
  categoryId: string;
  ownership: Ownership;
  ownerPartnerId: string | null;
  payloadCapacityT: string | null;
  bodyVolumeM3: string | null;
  axleConfig: string | null;
  fuelType: string;
  tankCapacityL: string | null;
  meterType: MeterType;
  odometerOffset: string;
  hoursOffset: string;
  branchId: string | null;
  homeLocationId: string | null;
  costCenterId: string | null;
  status: VehicleStatus;
  statusReason: string | null;
  statusChangedAt: string | null;
  assetId: string | null;
  acquiredOn: string | null;
  disposedAt: string | null;
  notes: string | null;
  createdAt: string;
  updatedAt: string;
  category: (Ref & { kind: CategoryKind }) | null;
  branch: Ref | null;
  homeLocation: Ref | null;
  costCenter: Ref | null;
  ownerPartner: Ref | null;
  asset?: { id: string; assetCode: string; name: string; status: string } | null;
  meters: { odometer: MeterValue | null; hours: MeterValue | null };
  compliance?: { expired: number; expiring: number; total: number };
  pendingCorrections?: number;
}

export interface MeterReading {
  id: string;
  meterType: 'odometer' | 'hours';
  readingAt: string;
  value: string;
  lifetimeValue: string;
  source: string;
  sourceId: string | null;
  status: 'valid' | 'superseded';
  notes: string | null;
  createdByFirstName?: string | null;
  createdByLastName?: string | null;
}

export interface MeterCorrection {
  id: string;
  meterType: 'odometer' | 'hours';
  kind: 'adjust' | 'replace';
  readingId: string | null;
  previousValue: string | null;
  correctedValue: string;
  readingAt: string | null;
  reason: string;
  status: 'pending' | 'applied' | 'rejected' | 'failed';
  approvalRequestId: string | null;
  failureReason: string | null;
  requestedAt: string;
  decidedAt: string | null;
  requestedByFirstName?: string | null;
  decidedByFirstName?: string | null;
}

export interface StatusHistoryRow {
  id: string;
  fromStatus: string | null;
  toStatus: VehicleStatus;
  reason: string | null;
  source: string;
  odometer: string | null;
  hours: string | null;
  changedAt: string;
  changedByFirstName?: string | null;
  changedByLastName?: string | null;
}

export interface ComplianceDocument {
  id: string;
  vehicleId: string;
  docType: DocType;
  documentNumber: string | null;
  issuer: string | null;
  issuedOn: string | null;
  expiresOn: string | null;
  reminderLeadDays: number;
  status: 'active' | 'renewed';
  renewedById: string | null;
  notes: string | null;
  state: ComplianceStateValue;
  daysToExpiry: number | null;
  attachments?: Array<{ id: string; fileId: string; filename: string; title: string | null }>;
  vehicle?: { id: string; code: string; name: string | null; registrationPlate: string | null };
}

export interface VehicleAttachment {
  id: string;
  fileId: string;
  kind: 'photo' | 'document';
  complianceDocumentId: string | null;
  title: string | null;
  filename: string;
  contentType: string;
  byteSize: number;
  url: string;
  createdAt: string;
}

export interface LinkedAsset {
  asset: {
    id: string; assetCode: string; name: string; status: string; purchaseDate: string | null;
    purchaseCost: string | null; currentValue: string | null; usefulLife: number; usefulLifeUnit: string;
    categoryName: string | null;
  } | null;
  accumulatedDepreciation?: string;
  bookValue?: string | null;
  depreciation: Array<{ id: string; period: string; depreciationAmount: string; accumulatedDepr: string; bookValue: string; isPosted: boolean }>;
}

export interface FleetLookups {
  categories: VehicleCategory[];
  branches: Ref[];
  locations: Array<Ref & { type: string }>;
  costCenters: Ref[];
}

export interface FleetOverview {
  total: number;
  byStatus: Array<{ status: VehicleStatus; count: number }>;
  byCategory: Array<{ id: string; code: string; name: string; kind: CategoryKind; count: number }>;
  compliance: { expired: number; expiring: number };
  pendingCorrections: number;
}

// ---- Labels ----

export const STATUS_LABEL: Record<VehicleStatus, string> = {
  active: tx('Active'),
  in_workshop: tx('In workshop'),
  breakdown: tx('Breakdown'),
  idle: tx('Idle'),
  disposed: tx('Disposed'),
};

export const OWNERSHIP_LABEL: Record<Ownership, string> = {
  owned: tx('Owned'),
  leased: tx('Leased'),
  hired: tx('Hired'),
  third_party: tx('Third-party'),
};

export const METER_TYPE_LABEL: Record<MeterType, string> = {
  odometer: tx('Odometer (km)'),
  hours: tx('Engine hours'),
  both: tx('Odometer and engine hours'),
  none: tx('No meter'),
};

export const KIND_LABEL: Record<CategoryKind, string> = {
  road_vehicle: tx('Road vehicle'),
  trailer: tx('Trailer'),
  mobile_plant: tx('Mobile plant'),
  fixed_plant: tx('Fixed plant'),
  other: tx('Other'),
};

export const FUEL_LABEL: Record<string, string> = {
  diesel: tx('Diesel'),
  petrol: tx('Petrol'),
  electric: tx('Electric'),
  hybrid: tx('Hybrid'),
  lpg: tx('LPG'),
  cng: tx('CNG'),
  none: tx('None'),
};

export const DOC_TYPE_LABEL: Record<DocType, string> = {
  registration: tx('Registration'),
  insurance: tx('Insurance'),
  road_licence: tx('Road licence / permit'),
  inspection: tx('Inspection certificate'),
  operator_permit: tx('Operator permit'),
  other: tx('Other'),
};

export const COMPLIANCE_STATE_LABEL: Record<ComplianceStateValue, string> = {
  expired: tx('Expired'),
  expiring: tx('Expiring soon'),
  valid: tx('Valid'),
  no_expiry: tx('No expiry'),
  renewed: tx('Renewed'),
};

export const CORRECTION_STATUS_LABEL: Record<MeterCorrection['status'], string> = {
  pending: tx('Waiting for approval'),
  applied: tx('Applied'),
  rejected: tx('Rejected'),
  failed: tx('Failed'),
};

export const meterUnit = (m: 'odometer' | 'hours') => (m === 'hours' ? 'h' : 'km');

// ---- Queries ----

const KEY = ['fleet'] as const;

export function useFleetOverview() {
  return useQuery({ queryKey: [...KEY, 'overview'], queryFn: async () => (await api.get<FleetOverview>('/fleet/overview')).data });
}

export function useFleetLookups() {
  return useQuery({
    queryKey: [...KEY, 'lookups'],
    staleTime: 60_000,
    queryFn: async () => (await api.get<FleetLookups>('/fleet/lookups')).data,
  });
}

export function useFleetCategories(includeInactive = false) {
  return useQuery({
    queryKey: [...KEY, 'categories', includeInactive],
    queryFn: async () => (await api.get<VehicleCategory[]>('/fleet/categories', { params: { includeInactive } })).data,
  });
}

export interface VehicleListParams {
  page: number;
  pageSize: number;
  search?: string;
  categoryId?: string;
  status?: string;
  branchId?: string;
  ownership?: string;
  sortBy?: string;
  sortOrder?: 'asc' | 'desc';
}

export function useVehicles(params: VehicleListParams) {
  return useQuery({
    queryKey: [...KEY, 'vehicles', params],
    placeholderData: (prev) => prev,
    queryFn: async () => (await api.get<PaginatedResult<Vehicle>>('/fleet/vehicles', { params })).data,
  });
}

export function useVehicle(id: string | undefined) {
  return useQuery({
    queryKey: [...KEY, 'vehicle', id],
    enabled: !!id,
    queryFn: async () => (await api.get<Vehicle>(`/fleet/vehicles/${id}`)).data,
  });
}

export function useVehicleReadings(id: string, meterType: string, includeSuperseded: boolean, page: number) {
  return useQuery({
    queryKey: [...KEY, 'vehicle', id, 'readings', meterType, includeSuperseded, page],
    placeholderData: (prev) => prev,
    queryFn: async () =>
      (await api.get<PaginatedResult<MeterReading>>(`/fleet/vehicles/${id}/meter-readings`, {
        params: { meterType: meterType || undefined, includeSuperseded, page, pageSize: 20 },
      })).data,
  });
}

export function useMeterCorrections(id: string) {
  return useQuery({
    queryKey: [...KEY, 'vehicle', id, 'corrections'],
    queryFn: async () => (await api.get<MeterCorrection[]>(`/fleet/vehicles/${id}/meter-corrections`)).data,
  });
}

export function useStatusHistory(id: string) {
  return useQuery({
    queryKey: [...KEY, 'vehicle', id, 'status-history'],
    queryFn: async () => (await api.get<StatusHistoryRow[]>(`/fleet/vehicles/${id}/status-history`)).data,
  });
}

export function useVehicleCompliance(id: string) {
  return useQuery({
    queryKey: [...KEY, 'vehicle', id, 'compliance'],
    queryFn: async () => (await api.get<ComplianceDocument[]>(`/fleet/vehicles/${id}/compliance-documents`)).data,
  });
}

export function useVehicleAttachments(id: string) {
  return useQuery({
    queryKey: [...KEY, 'vehicle', id, 'attachments'],
    queryFn: async () => (await api.get<VehicleAttachment[]>(`/fleet/vehicles/${id}/attachments`)).data,
  });
}

export function useLinkedAsset(id: string, enabled: boolean) {
  return useQuery({
    queryKey: [...KEY, 'vehicle', id, 'asset'],
    enabled,
    queryFn: async () => (await api.get<LinkedAsset>(`/fleet/vehicles/${id}/asset`)).data,
  });
}

export interface ComplianceListParams {
  page: number;
  pageSize: number;
  due?: string;
  docType?: string;
  branchId?: string;
}

export function useComplianceDue(params: ComplianceListParams) {
  return useQuery({
    queryKey: [...KEY, 'compliance', params],
    placeholderData: (prev) => prev,
    queryFn: async () => (await api.get<PaginatedResult<ComplianceDocument>>('/fleet/compliance-documents', { params })).data,
  });
}

export function useAssetPicker(search: string, enabled: boolean) {
  return useQuery({
    queryKey: [...KEY, 'asset-picker', search],
    enabled,
    queryFn: async () =>
      (await api.get<Array<{ id: string; assetCode: string; name: string; status: string; linkedVehicleCode: string | null }>>(
        '/fleet/lookups/assets', { params: { search } })).data,
  });
}

export function usePartnerPicker(search: string, enabled: boolean) {
  return useQuery({
    queryKey: [...KEY, 'partner-picker', search],
    enabled,
    queryFn: async () => (await api.get<Ref[]>('/fleet/lookups/partners', { params: { search } })).data,
  });
}

// ---- Mutations ----

function useFleetMutation<TVars, TResult = unknown>(fn: (vars: TVars) => Promise<TResult>) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: fn,
    onSuccess: () => qc.invalidateQueries({ queryKey: KEY }),
  });
}

export const useCreateVehicle = () =>
  useFleetMutation(async (body: Record<string, unknown>) => (await api.post<Vehicle>('/fleet/vehicles', body)).data);

export const useUpdateVehicle = () =>
  useFleetMutation(async ({ id, body }: { id: string; body: Record<string, unknown> }) =>
    (await api.patch<Vehicle>(`/fleet/vehicles/${id}`, body)).data);

export const useDeleteVehicle = () => useFleetMutation(async (id: string) => api.delete(`/fleet/vehicles/${id}`));

export const useChangeStatus = () =>
  useFleetMutation(async ({ id, status, reason }: { id: string; status: VehicleStatus; reason?: string }) =>
    (await api.post<Vehicle>(`/fleet/vehicles/${id}/status`, { status, reason: reason || undefined })).data);

export const useRecordReading = () =>
  useFleetMutation(async ({ id, body }: { id: string; body: Record<string, unknown> }) =>
    (await api.post<MeterReading>(`/fleet/vehicles/${id}/meter-readings`, body)).data);

export const useRequestCorrection = () =>
  useFleetMutation(async ({ id, body }: { id: string; body: Record<string, unknown> }) =>
    (await api.post<MeterCorrection>(`/fleet/vehicles/${id}/meter-corrections`, body)).data);

export const useSyncCorrection = () =>
  useFleetMutation(async (id: string) => (await api.post<MeterCorrection>(`/fleet/meter-corrections/${id}/sync`)).data);

export const useCreateCompliance = () =>
  useFleetMutation(async ({ vehicleId, body }: { vehicleId: string; body: Record<string, unknown> }) =>
    (await api.post<ComplianceDocument>(`/fleet/vehicles/${vehicleId}/compliance-documents`, body)).data);

export const useUpdateCompliance = () =>
  useFleetMutation(async ({ id, body }: { id: string; body: Record<string, unknown> }) =>
    (await api.patch<ComplianceDocument>(`/fleet/compliance-documents/${id}`, body)).data);

export const useRenewCompliance = () =>
  useFleetMutation(async ({ id, body }: { id: string; body: Record<string, unknown> }) =>
    (await api.post<ComplianceDocument>(`/fleet/compliance-documents/${id}/renew`, body)).data);

export const useDeleteCompliance = () =>
  useFleetMutation(async (id: string) => api.delete(`/fleet/compliance-documents/${id}`));

export const useUploadAttachment = () =>
  useFleetMutation(async ({ vehicleId, file, kind, title, complianceDocumentId }: {
    vehicleId: string; file: File; kind: 'photo' | 'document'; title?: string; complianceDocumentId?: string;
  }) => {
    const fd = new FormData();
    fd.append('file', file);
    fd.append('kind', kind);
    if (title) fd.append('title', title);
    if (complianceDocumentId) fd.append('complianceDocumentId', complianceDocumentId);
    return (await api.post<VehicleAttachment>(`/fleet/vehicles/${vehicleId}/attachments`, fd, {
      headers: { 'Content-Type': 'multipart/form-data' },
    })).data;
  });

export const useDeleteAttachment = () =>
  useFleetMutation(async ({ vehicleId, attachmentId }: { vehicleId: string; attachmentId: string }) =>
    api.delete(`/fleet/vehicles/${vehicleId}/attachments/${attachmentId}`));

export const useCreateCategory = () =>
  useFleetMutation(async (body: Record<string, unknown>) => (await api.post<VehicleCategory>('/fleet/categories', body)).data);

export const useUpdateCategory = () =>
  useFleetMutation(async ({ id, body }: { id: string; body: Record<string, unknown> }) =>
    (await api.patch<VehicleCategory>(`/fleet/categories/${id}`, body)).data);

export const useDeleteCategory = () => useFleetMutation(async (id: string) => api.delete(`/fleet/categories/${id}`));

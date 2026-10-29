import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import type { PaginatedResult } from '@erp/shared';
import { api } from '@/lib/api';
import { idempotentPost } from '@/lib/idempotent-request';
import { tx } from '@/lib/i18n/tx';

// ---- Types (fleet 1.5 — fuel & energy, ADR-S009) ----

export type FuelSource = 'station' | 'tank';
export type FuelPayment = 'cash' | 'fuel_card' | 'credit';
export type FuelLogStatus = 'draft' | 'posted' | 'reversed';
export type AnomalyType =
  | 'overfill' | 'meter_not_increasing' | 'high_consumption' | 'low_consumption'
  | 'card_vehicle_mismatch' | 'card_limit_exceeded';
export type AnomalyStatus = 'open' | 'acknowledged' | 'resolved';
export type CardStatus = 'active' | 'blocked' | 'expired' | 'cancelled';
export type DipStatus = 'open' | 'accepted' | 'pending_approval' | 'adjusted' | 'rejected' | 'failed';

interface CodeRef { id: string; code: string | null; name?: string | null }

export interface FuelAnomaly {
  id: string;
  fuelLogId: string;
  vehicleId: string;
  type: AnomalyType;
  detail: string;
  observed: string | null;
  expectedMin: string | null;
  expectedMax: string | null;
  status: AnomalyStatus;
  note: string | null;
  acknowledgedAt: string | null;
  createdAt: string;
  fuelLog?: { id: string; code: string; fuelledAt: string; litres: string; source: FuelSource; status: FuelLogStatus };
  vehicle?: { id: string; code: string; registrationPlate: string | null };
  acknowledgedByName?: string | null;
}

export interface FuelLog {
  id: string;
  code: string;
  vehicleId: string;
  fuelledAt: string;
  source: FuelSource;
  fuelType: string | null;
  litres: string;
  unitPrice: string | null;
  amount: string | null;
  fullTank: boolean;
  odometer: string | null;
  hours: string | null;
  supplierId: string | null;
  stationName: string | null;
  paymentMethod: FuelPayment | null;
  paymentAccountId: string | null;
  fuelCardId: string | null;
  tankId: string | null;
  receiptNumber: string | null;
  receiptAttachmentId: string | null;
  employeeId: string | null;
  costCenterId: string | null;
  notes: string | null;
  status: FuelLogStatus;
  previousFullLogId: string | null;
  segmentLitres: string | null;
  distanceKm: string | null;
  hoursRun: string | null;
  lPer100Km: string | null;
  lPerHour: string | null;
  anomalyCount: number;
  meterNote: string | null;
  odometerReadingId: string | null;
  hoursReadingId: string | null;
  expenseId: string | null;
  journalEntryId: string | null;
  stockLedgerCode: string | null;
  stockUnitCost: string | null;
  stockValue: string | null;
  postedAt: string | null;
  reversedAt: string | null;
  reversalReason: string | null;
  createdAt: string;
  vehicle: { id: string; code: string; name: string | null; registrationPlate: string | null; tankCapacityL?: string | null; meterType?: string; fuelType?: string } | null;
  tank: CodeRef | null;
  fuelCard: { id: string; maskedNumber: string; label?: string | null; status?: CardStatus } | null;
  /** List rows: open / acknowledged anomaly types. Detail: full anomaly rows. */
  anomalies: AnomalyType[] | FuelAnomaly[];
}

export interface JournalLineRow {
  id: string; lineNumber: number; debit: string; credit: string; description: string | null;
  accountCode: string; accountName: string; costCenterCode: string | null;
}

export interface FuelLogDetail extends Omit<FuelLog, 'anomalies'> {
  anomalies: FuelAnomaly[];
  supplier: CodeRef | null;
  employee: { id: string; employeeCode: string; name: string | null } | null;
  costCenter: CodeRef | null;
  paymentAccount: CodeRef | null;
  previousFullLog: { id: string; code: string; fuelledAt: string; odometer: string | null; hours: string | null } | null;
  expense: { id: string; expenseCode: string; status: string; paymentStatus: string; amount: string; amountPaid: string } | null;
  journal: Array<{ id: string; entryNumber: string; status: string; postingDate: string; description: string | null; postingType: string; reversalOfId: string | null; lines: JournalLineRow[] }>;
  receipt: { id: string; fileId: string; kind: 'photo' | 'document'; title: string | null; filename: string; contentType: string; url: string } | null;
}

export interface FuelLogList extends PaginatedResult<FuelLog> {
  summary: { litres: string; amount: string };
}

export interface FuelCard {
  id: string;
  maskedNumber: string;
  last4: string;
  label: string | null;
  providerPartnerId: string | null;
  vehicleId: string | null;
  employeeId: string | null;
  status: CardStatus;
  expiresOn: string | null;
  limitAmount: string | null;
  limitPeriod: 'daily' | 'weekly' | 'monthly';
  notes: string | null;
  vehicle: { id: string; code: string; registrationPlate: string | null } | null;
  provider: CodeRef | null;
  employee: { id: string; employeeCode: string; name: string | null } | null;
  periodSpend: string;
  periodStart: string;
  limitRemaining: string | null;
}

export interface FuelDip {
  id: string;
  tankId: string;
  dippedAt: string;
  dipLitres: string;
  bookLitres: string;
  varianceLitres: string;
  notes: string | null;
  status: DipStatus;
  reason: string | null;
  approvalRequestId: string | null;
  adjustmentLedgerCode: string | null;
  adjustedLitres: string | null;
  adjustmentValue: string | null;
  failureReason: string | null;
  reconciledAt: string | null;
  decidedAt: string | null;
  recordedByFirstName?: string | null;
  recordedByLastName?: string | null;
}

export interface FuelTank {
  id: string;
  code: string;
  name: string;
  kind: 'tank' | 'bowser';
  locationId: string;
  productId: string;
  capacityL: string;
  reorderLevelL: string | null;
  branchId: string | null;
  bowserVehicleId: string | null;
  isActive: boolean;
  notes: string | null;
  location: CodeRef;
  product: CodeRef;
  branch: CodeRef | null;
  bowserVehicle: { id: string; code: string } | null;
  bookLitres: string;
  fillPct: string | null;
  averageCost: string;
  bookValue: string;
  belowReorder: boolean;
  overCapacity: boolean;
  draftIssues: number;
  lastDip: { id: string; dippedAt: string; dipLitres: string; varianceLitres: string; status: DipStatus } | null;
  recentDips?: FuelDip[];
}

export interface TankMovement {
  id: string; ledgerCode: string; type: string; quantityChange: string; balanceAfter: string; unitCost: string;
  totalValue: string; referenceType: string | null; referenceId: string | null; notes: string | null; createdAt: string;
  fuelLogCode: string | null; vehicleCode: string | null;
}

export interface FuelBandRow {
  categoryId: string; code: string; name: string; kind: string; defaultMeterType: string;
  minLPer100Km: string | null; maxLPer100Km: string | null; minLPerHour: string | null; maxLPerHour: string | null;
}

export interface FuelSettings {
  id: string;
  overfillTolerancePct: string;
  dipApprovalLitres: string;
  dipApprovalPct: string;
  bands: FuelBandRow[];
}

export interface FuelLookups {
  vehicles: Array<{ id: string; code: string; name: string | null; registrationPlate: string | null; fuelType: string; tankCapacityL: string | null; meterType: 'odometer' | 'hours' | 'both' | 'none'; currentOdometer: string | null; currentHours: string | null; costCenterId: string | null; status: string; categoryName: string }>;
  tanks: Array<{ id: string; code: string; name: string; kind: string; bookLitres: string; capacityL: string; product: CodeRef }>;
  cards: Array<{ id: string; maskedNumber: string; label: string | null; vehicleId: string | null }>;
  paymentAccounts: Array<{ id: string; name: string; currency: string; currentBalance: number }>;
  suppliers: Array<{ id: string; name: string; phone?: string }>;
  locations: Array<{ id: string; code: string; name: string; type: string }>;
  costCenters: Array<{ id: string; code: string; name: string }>;
}

export interface FuelOverview {
  monthStart: string;
  month: { litres: string; amount: string; tankLitres: string; stationLitres: string; logs: number };
  drafts: number;
  openAnomalies: number;
  tanks: number;
  tanksBelowReorder: number;
  pendingReconciliations: number;
}

export interface VehicleFuel {
  summary: { logs: number; litres: string; amount: string; lastFuelledAt: string | null; averageLPer100Km: string | null; averageLPerHour: string | null; anomalies: number };
  band: { minLPer100Km: string | null; maxLPer100Km: string | null; minLPerHour: string | null; maxLPerHour: string | null };
  trend: Array<{ id: string; code: string; fuelledAt: string; litres: string; segmentLitres: string | null; distanceKm: string | null; hoursRun: string | null; lPer100Km: string | null; lPerHour: string | null; anomalyCount: number }>;
  recent: FuelLog[];
}

// ---- Labels ----

export const SOURCE_LABEL: Record<FuelSource, string> = {
  station: tx('Fuel station'),
  tank: tx('Site tank / bowser'),
};

export const PAYMENT_LABEL: Record<FuelPayment, string> = {
  cash: tx('Cash / bank'),
  fuel_card: tx('Fuel card'),
  credit: tx('Supplier credit'),
};

export const LOG_STATUS_LABEL: Record<FuelLogStatus, string> = {
  draft: tx('Draft'),
  posted: tx('Posted'),
  reversed: tx('Reversed'),
};

export const ANOMALY_LABEL: Record<AnomalyType, string> = {
  overfill: tx('Overfill'),
  meter_not_increasing: tx('Meter not increasing'),
  high_consumption: tx('High consumption'),
  low_consumption: tx('Low consumption'),
  card_vehicle_mismatch: tx('Card on another vehicle'),
  card_limit_exceeded: tx('Card limit exceeded'),
};

export const ANOMALY_STATUS_LABEL: Record<AnomalyStatus, string> = {
  open: tx('Open'),
  acknowledged: tx('Acknowledged'),
  resolved: tx('Resolved'),
};

export const CARD_STATUS_LABEL: Record<CardStatus, string> = {
  active: tx('Active'),
  blocked: tx('Blocked'),
  expired: tx('Expired'),
  cancelled: tx('Cancelled'),
};

export const LIMIT_PERIOD_LABEL: Record<FuelCard['limitPeriod'], string> = {
  daily: tx('Per day'),
  weekly: tx('Per week'),
  monthly: tx('Per month'),
};

export const DIP_STATUS_LABEL: Record<DipStatus, string> = {
  open: tx('Not reconciled'),
  accepted: tx('No variance'),
  pending_approval: tx('Waiting for approval'),
  adjusted: tx('Adjusted'),
  rejected: tx('Rejected'),
  failed: tx('Failed'),
};

export const FUEL_TYPE_LABEL: Record<string, string> = {
  diesel: tx('Diesel'),
  petrol: tx('Petrol'),
  lpg: tx('LPG'),
  cng: tx('CNG'),
  electric: tx('Electric'),
  other: tx('Other'),
};

// ---- Queries ----

const KEY = ['fleet'] as const;

export interface FuelLogParams {
  page: number;
  pageSize: number;
  search?: string;
  vehicleId?: string;
  source?: string;
  status?: string;
  anomaly?: string;
  tankId?: string;
  fuelCardId?: string;
  dateFrom?: string;
  dateTo?: string;
  sortBy?: string;
  sortOrder?: 'asc' | 'desc';
}

export function useFuelLogs(params: FuelLogParams) {
  return useQuery({
    queryKey: [...KEY, 'fuel-logs', params],
    placeholderData: (prev) => prev,
    queryFn: async () => (await api.get<FuelLogList>('/fleet/fuel-logs', { params })).data,
  });
}

export function useFuelLog(id: string | undefined) {
  return useQuery({
    queryKey: [...KEY, 'fuel-log', id],
    enabled: !!id,
    queryFn: async () => (await api.get<FuelLogDetail>(`/fleet/fuel-logs/${id}`)).data,
  });
}

export function useFuelLookups() {
  return useQuery({
    queryKey: [...KEY, 'fuel-lookups'],
    staleTime: 30_000,
    queryFn: async () => (await api.get<FuelLookups>('/fleet/fuel/lookups')).data,
  });
}

export function useFuelOverview() {
  return useQuery({ queryKey: [...KEY, 'fuel-overview'], queryFn: async () => (await api.get<FuelOverview>('/fleet/fuel/overview')).data });
}

export function useEmployeePicker(search: string, enabled: boolean) {
  return useQuery({
    queryKey: [...KEY, 'fuel-employees', search],
    enabled,
    queryFn: async () =>
      (await api.get<Array<{ id: string; employeeCode: string; name: string | null }>>('/fleet/fuel/lookups/employees', { params: { search } })).data,
  });
}

export function useProductPicker(search: string, enabled: boolean) {
  return useQuery({
    queryKey: [...KEY, 'fuel-products', search],
    enabled,
    queryFn: async () =>
      (await api.get<Array<{ id: string; code: string; name: string; costingMethod: string }>>('/fleet/fuel/lookups/products', { params: { search } })).data,
  });
}

export function useVehicleFuel(vehicleId: string, enabled: boolean) {
  return useQuery({
    queryKey: [...KEY, 'vehicle', vehicleId, 'fuel'],
    enabled,
    queryFn: async () => (await api.get<VehicleFuel>(`/fleet/vehicles/${vehicleId}/fuel`)).data,
  });
}

export function useFuelAnomalies(params: { page: number; pageSize: number; status?: string; type?: string; vehicleId?: string }) {
  return useQuery({
    queryKey: [...KEY, 'fuel-anomalies', params],
    placeholderData: (prev) => prev,
    queryFn: async () => (await api.get<PaginatedResult<FuelAnomaly>>('/fleet/fuel-anomalies', { params })).data,
  });
}

export function useFuelCards(params: { page: number; pageSize: number; search?: string; status?: string; vehicleId?: string }) {
  return useQuery({
    queryKey: [...KEY, 'fuel-cards', params],
    placeholderData: (prev) => prev,
    queryFn: async () => (await api.get<PaginatedResult<FuelCard>>('/fleet/fuel-cards', { params })).data,
  });
}

export function useFuelTanks(includeInactive = false) {
  return useQuery({
    queryKey: [...KEY, 'fuel-tanks', includeInactive],
    queryFn: async () => (await api.get<FuelTank[]>('/fleet/fuel-tanks', { params: { includeInactive } })).data,
  });
}

export function useFuelTank(id: string | undefined) {
  return useQuery({
    queryKey: [...KEY, 'fuel-tank', id],
    enabled: !!id,
    queryFn: async () => (await api.get<FuelTank>(`/fleet/fuel-tanks/${id}`)).data,
  });
}

export function useTankDips(id: string) {
  return useQuery({
    queryKey: [...KEY, 'fuel-tank', id, 'dips'],
    queryFn: async () => (await api.get<FuelDip[]>(`/fleet/fuel-tanks/${id}/dips`)).data,
  });
}

export function useTankMovements(id: string, page: number) {
  return useQuery({
    queryKey: [...KEY, 'fuel-tank', id, 'movements', page],
    placeholderData: (prev) => prev,
    queryFn: async () => (await api.get<PaginatedResult<TankMovement>>(`/fleet/fuel-tanks/${id}/movements`, { params: { page, pageSize: 20 } })).data,
  });
}

export function useFuelSettings() {
  return useQuery({ queryKey: [...KEY, 'fuel-settings'], queryFn: async () => (await api.get<FuelSettings>('/fleet/fuel-settings')).data });
}

// ---- Mutations ----

function useFuelMutation<TVars, TResult = unknown>(fn: (vars: TVars) => Promise<TResult>) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: fn,
    onSuccess: () => qc.invalidateQueries({ queryKey: KEY }),
  });
}

/** Quick entry; `post: true` posts at once — money / stock, so the Idempotency-Key survives retries. */
export const useCreateFuelLog = () =>
  useFuelMutation(async (body: Record<string, unknown>) => idempotentPost<FuelLogDetail>('/fleet/fuel-logs', body));

export const useUpdateFuelLog = () =>
  useFuelMutation(async ({ id, body }: { id: string; body: Record<string, unknown> }) =>
    (await api.patch<FuelLogDetail>(`/fleet/fuel-logs/${id}`, body)).data);

export const useDeleteFuelLog = () => useFuelMutation(async (id: string) => api.delete(`/fleet/fuel-logs/${id}`));

export const usePostFuelLog = () =>
  useFuelMutation(async (id: string) => idempotentPost<FuelLogDetail>(`/fleet/fuel-logs/${id}/post`));

export const useReverseFuelLog = () =>
  useFuelMutation(async ({ id, reason }: { id: string; reason: string }) =>
    idempotentPost<FuelLogDetail>(`/fleet/fuel-logs/${id}/reverse`, { reason }));

export const useUploadReceipt = () =>
  useFuelMutation(async ({ id, file }: { id: string; file: File }) => {
    const fd = new FormData();
    fd.append('file', file);
    return (await api.post<FuelLogDetail>(`/fleet/fuel-logs/${id}/receipt`, fd, {
      headers: { 'Content-Type': 'multipart/form-data' },
    })).data;
  });

export const useAcknowledgeAnomaly = () =>
  useFuelMutation(async ({ id, note }: { id: string; note: string }) =>
    (await api.post<FuelAnomaly>(`/fleet/fuel-anomalies/${id}/acknowledge`, { note })).data);

export const useCreateFuelCard = () =>
  useFuelMutation(async (body: Record<string, unknown>) => (await api.post<FuelCard>('/fleet/fuel-cards', body)).data);

export const useUpdateFuelCard = () =>
  useFuelMutation(async ({ id, body }: { id: string; body: Record<string, unknown> }) =>
    (await api.patch<FuelCard>(`/fleet/fuel-cards/${id}`, body)).data);

export const useDeleteFuelCard = () => useFuelMutation(async (id: string) => api.delete(`/fleet/fuel-cards/${id}`));

export const useCreateTank = () =>
  useFuelMutation(async (body: Record<string, unknown>) => (await api.post<FuelTank>('/fleet/fuel-tanks', body)).data);

export const useUpdateTank = () =>
  useFuelMutation(async ({ id, body }: { id: string; body: Record<string, unknown> }) =>
    (await api.patch<FuelTank>(`/fleet/fuel-tanks/${id}`, body)).data);

export const useDeleteTank = () => useFuelMutation(async (id: string) => api.delete(`/fleet/fuel-tanks/${id}`));

export const useRecordDip = () =>
  useFuelMutation(async ({ tankId, body }: { tankId: string; body: Record<string, unknown> }) =>
    (await api.post<FuelDip>(`/fleet/fuel-tanks/${tankId}/dips`, body)).data);

export const useReconcileDip = () =>
  useFuelMutation(async ({ id, reason }: { id: string; reason?: string }) =>
    idempotentPost<FuelDip>(`/fleet/fuel-dips/${id}/reconcile`, { reason: reason || undefined }));

export const useSyncDip = () =>
  useFuelMutation(async (id: string) => idempotentPost<FuelDip>(`/fleet/fuel-dips/${id}/sync`));

export const useUpdateFuelSettings = () =>
  useFuelMutation(async (body: Record<string, unknown>) => (await api.put<FuelSettings>('/fleet/fuel-settings', body)).data);

export const usePutFuelBand = () =>
  useFuelMutation(async ({ categoryId, body }: { categoryId: string; body: Record<string, unknown> }) =>
    (await api.put(`/fleet/fuel-settings/bands/${categoryId}`, body)).data);

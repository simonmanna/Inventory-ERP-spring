import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import type { PaginatedResult } from '@erp/shared';
import { api } from '@/lib/api';
import { tx } from '@/lib/i18n/tx';

// ---- Types (fleet 1.4 — weighbridge, ADR-S009 §3 / §7) ----

export type SubjectType = 'sale_delivery' | 'purchase_receipt' | 'internal_haulage' | 'other';
export type TicketStatus = 'open' | 'first_weighed' | 'exception' | 'completed' | 'cancelled' | 'void';
export type WeighSource = 'device' | 'manual' | 'stored_tare';
export type TicketQueue = 'live' | 'open' | 'awaiting_second' | 'exceptions' | 'completed' | 'void' | 'cancelled' | 'all';

export interface Station {
  id: string;
  code: string;
  name: string;
  branchId: string | null;
  locationId: string | null;
  tolerancePct: string;
  toleranceKg: string;
  tareValidityDays: number;
  minWeightKg: string;
  maxWeightKg: string;
  isActive: boolean;
  notes: string | null;
  branch: { id: string; name: string } | null;
  location: { id: string; code: string; name: string } | null;
  deviceCount: number;
}

export interface Device {
  id: string;
  stationId: string;
  code: string;
  name: string | null;
  adapterType: string;
  config: Record<string, unknown>;
  readTimeoutMs: number;
  isActive: boolean;
  lastReadAt: string | null;
  lastReadKg: string | null;
  lastStatus: 'ok' | 'error' | null;
  lastError: string | null;
  notes: string | null;
  station: { id: string; code: string; name: string };
}

export interface ProductSetting {
  id: string;
  productId: string;
  kgPerUnit: string | null;
  tolerancePct: string | null;
  toleranceKg: string | null;
  notes: string | null;
  product: { id: string; code: string; name: string; baseUnit: string | null };
}

export interface Unit { id: string; code: string; name: string; symbol: string | null; category: string }

export interface WeighbridgeLookups {
  stations: Station[];
  devices: Device[];
  adapters: string[];
  subjectTypes: SubjectType[];
  units: Unit[];
  locations: Array<{ id: string; code: string; name: string }>;
}

export interface ProductOption {
  id: string; code: string; name: string; uomId: string | null; salesUomId: string | null; purchaseUomId: string | null;
  uomCode: string | null; kgPerUnit: string | null;
}

export interface PartnerOption { id: string; code: string | null; name: string; isCustomer: boolean; isSupplier: boolean }

export interface PurchaseOrderOption {
  id: string;
  orderNumber: string;
  partnerId: string;
  partnerName: string | null;
  status: string;
  lines: Array<{
    id: string; productId: string | null; productName: string | null; description: string; quantity: string;
    receivedQuantity: string; unitOfMeasureId: string | null; uomCode: string | null;
  }>;
}

export interface WeighbridgeException {
  id: string;
  ticketId: string;
  kind: string;
  expectedKg: string;
  netKg: string;
  varianceKg: string;
  variancePct: string | null;
  allowedVarianceKg: string;
  reason: string | null;
  status: 'pending' | 'approved' | 'rejected';
  approvalRequestId: string | null;
  raisedBy: string | null;
  raisedAt: string;
  decidedBy: string | null;
  decidedAt: string | null;
  decisionNotes: string | null;
  ticket?: {
    id: string; ticketNumber: string; registrationPlate: string; status: TicketStatus; isManual: boolean;
    orderedQty: string | null; orderedUomCode: string | null; productName: string | null; partnerName: string | null;
    stationCode: string;
  };
  ownTicket?: boolean;
}

export interface Ticket {
  id: string;
  ticketNumber: string;
  stationId: string;
  subjectType: SubjectType;
  direction: 'outbound' | 'inbound' | 'internal';
  status: TicketStatus;
  vehicleId: string | null;
  registrationPlate: string;
  trailerPlate: string | null;
  driverId: string | null;
  driverName: string | null;
  transporterPartnerId: string | null;
  partnerId: string | null;
  salesOrderId: string | null;
  deliveryOrderId: string | null;
  tripId: string | null;
  purchaseOrderId: string | null;
  purchaseOrderLineId: string | null;
  referenceNumber: string | null;
  productId: string | null;
  orderedQty: string | null;
  orderedUomId: string | null;
  expectedKg: string | null;
  locationId: string | null;
  firstWeightKg: string | null;
  firstWeighedAt: string | null;
  firstSource: WeighSource | null;
  firstDeviceId: string | null;
  firstRawFrame: string | null;
  firstManualReason: string | null;
  firstBy: string | null;
  secondWeightKg: string | null;
  secondWeighedAt: string | null;
  secondSource: WeighSource | null;
  secondDeviceId: string | null;
  secondRawFrame: string | null;
  secondManualReason: string | null;
  secondBy: string | null;
  grossKg: string | null;
  tareKg: string | null;
  netKg: string | null;
  netQty: string | null;
  netUomId: string | null;
  varianceKg: string | null;
  variancePct: string | null;
  allowedVarianceKg: string | null;
  tolerancePct: string | null;
  toleranceKg: string | null;
  withinTolerance: boolean | null;
  isManual: boolean;
  usedStoredTare: boolean;
  completedAt: string | null;
  printCount: number;
  firstPrintedAt: string | null;
  lastPrintedAt: string | null;
  cancelReason: string | null;
  voidedAt: string | null;
  voidReason: string | null;
  reissuedFromId: string | null;
  replacedById: string | null;
  goodsReceiptId: string | null;
  tripFeedStatus: 'fed' | 'skipped' | 'failed' | 'cleared' | null;
  tripFeedMessage: string | null;
  tripFedAt: string | null;
  notes: string | null;
  createdAt: string;
  station: { id: string; code: string; name: string };
  partner: { id: string; name: string } | null;
  transporter: { id: string; name: string } | null;
  product: { id: string; code: string; name: string } | null;
  vehicle: { id: string; code: string } | null;
  driver: { id: string; code: string } | null;
  purchaseOrder: { id: string; orderNumber: string } | null;
  orderedUomCode: string | null;
  netUomCode: string | null;
  trip: { id: string; code: string; status: string } | null;
  deliveryOrder: { id: string; code: string } | null;
  // detail only
  exceptions?: WeighbridgeException[];
  reissuedFrom?: { id: string; ticketNumber: string } | null;
  replacedBy?: { id: string; ticketNumber: string } | null;
  goodsReceipt?: { id: string; receiptNumber: string; status: string } | null;
  users?: Record<string, string>;
  devices?: Record<string, string>;
}

/** An open trip the weighbridge can weigh (fleet 1.4b trip picker). */
export interface WeighTripOption {
  id: string;
  code: string;
  status: string;
  plannedDate: string;
  deliveryOrderId: string;
  deliveryOrderCode: string;
  partnerName: string | null;
  vehicleCode: string | null;
  vehiclePlate: string | null;
  transporterPlate: string | null;
  driverName: string | null;
  plannedTonnes: string | null;
  liveTicket: { id: string; ticketNumber: string; status: string } | null;
  products: Array<{ productId: string; productName: string; plannedQty: string; uomCode: string | null }>;
}

/** Ticket header pre-filled from a trip. */
export interface TripPrefill {
  tripId: string;
  tripCode: string;
  tripStatus: string;
  open: boolean;
  subjectType: SubjectType;
  deliveryOrderId: string;
  deliveryOrderCode: string | null;
  vehicleId?: string;
  vehicleCode: string | null;
  vehiclePlate: string | null;
  registrationPlate?: string;
  trailerPlate?: string;
  driverId?: string;
  driverName?: string;
  driverDisplayName: string | null;
  transporterPartnerId?: string;
  transporterName: string | null;
  partnerId?: string;
  partnerName: string | null;
  salesOrderId?: string;
  referenceNumber?: string;
  productId?: string;
  productName: string | null;
  orderedQty?: string;
  orderedUomId?: string;
  orderedUomCode: string | null;
  locationId?: string;
  plannedTonnes: string | null;
  liveTicket: { id: string; ticketNumber: string; status: string } | null;
  weighbridge: { required: boolean; reason: string | null };
}

export type RequirementMode = 'off' | 'all' | 'bulk' | 'categories';

/** "Weighbridge required for dispatch" rule of the organization (no branch) or a branch. */
export interface DispatchRequirement {
  id: string;
  branchId: string | null;
  branchCode: string | null;
  branchName: string | null;
  mode: RequirementMode;
  categoryIds: string[];
  categories: Array<{ id: string; name: string }>;
  notes: string | null;
  updatedAt: string;
}

export interface DispatchRequirementOverview {
  rules: DispatchRequirement[];
  branches: Array<{ id: string; code: string; name: string }>;
  categories: Array<{ id: string; name: string; parentId: string | null }>;
}

export const REQUIREMENT_MODE_LABEL: Record<RequirementMode, string> = {
  off: tx('Not required'),
  all: tx('All products'),
  bulk: tx('Bulk products (sold by weight / volume)'),
  categories: tx('Selected product categories'),
};

export const TRIP_FEED_LABEL: Record<'fed' | 'skipped' | 'failed' | 'cleared', string> = {
  fed: tx('Fed the trip'),
  skipped: tx('Trip not changed'),
  failed: tx('Could not feed the trip'),
  cleared: tx('Trip weight cleared'),
};

export interface StoredTare {
  id: string;
  vehicleId: string | null;
  registrationPlate: string;
  tareKg: string;
  weighedAt: string;
  expiresAt: string;
  stationId: string | null;
  deviceId: string | null;
  source: 'device' | 'manual';
  rawFrame: string | null;
  manualReason: string | null;
  ticketId: string | null;
  status: 'active' | 'superseded' | 'revoked';
  state: 'valid' | 'expired' | 'superseded' | 'revoked';
  revokedReason: string | null;
  vehicleCode?: string | null;
  stationCode?: string | null;
  deviceCode?: string | null;
}

export interface QueueCounts { open: number; awaitingSecond: number; exceptions: number; completedToday: number }

export interface LiveReading { deviceId: string; weightKg: string; rawFrame: string; stable: boolean; at: string }

export interface DiscrepancyRow {
  key: string | null;
  label: string;
  tickets: number;
  compared: number;
  expectedKg: string;
  netKg: string;
  comparedNetKg: string;
  varianceKg: string;
  absVarianceKg: string;
  variancePct: string | null;
  outOfTolerance: number;
  manual: number;
  storedTare: number;
}

export interface DiscrepancyReport { groupBy: string; groups: DiscrepancyRow[]; totals: DiscrepancyRow }

export const SUBJECT_LABEL: Record<SubjectType, string> = {
  sale_delivery: tx('Sale delivery (outbound)'),
  purchase_receipt: tx('Purchase receipt (inbound)'),
  internal_haulage: tx('Internal haulage'),
  other: tx('Other'),
};

export const TICKET_STATUS_LABEL: Record<TicketStatus, string> = {
  open: tx('Open'),
  first_weighed: tx('Awaiting second weighing'),
  exception: tx('Exception'),
  completed: tx('Completed'),
  cancelled: tx('Cancelled'),
  void: tx('Void'),
};

export const SOURCE_LABEL: Record<WeighSource, string> = {
  device: tx('Device'),
  manual: tx('Manual entry'),
  stored_tare: tx('Stored tare'),
};

export const QUEUE_LABEL: Record<TicketQueue, string> = {
  live: tx('In progress'),
  open: tx('Open'),
  awaiting_second: tx('Awaiting second weighing'),
  exceptions: tx('Exceptions'),
  completed: tx('Completed'),
  void: tx('Void'),
  cancelled: tx('Cancelled'),
  all: tx('All'),
};

export const GROUP_LABEL: Record<string, string> = {
  product: tx('Product'),
  partner: tx('Customer / supplier'),
  station: tx('Station'),
  vehicle: tx('Truck'),
  subject: tx('Subject'),
  day: tx('Day'),
  month: tx('Month'),
};

// ---- Queries ----

const KEY = ['fleet', 'weighbridge'] as const;

export function useWeighbridgeLookups() {
  return useQuery({
    queryKey: [...KEY, 'lookups'],
    queryFn: async () => (await api.get<WeighbridgeLookups>('/fleet/weighbridge/lookups')).data,
  });
}

export function useStations(includeInactive = false) {
  return useQuery({
    queryKey: [...KEY, 'stations', includeInactive],
    queryFn: async () => (await api.get<Station[]>('/fleet/weighbridge/stations', { params: { includeInactive } })).data,
  });
}

export function useDevices(includeInactive = false) {
  return useQuery({
    queryKey: [...KEY, 'devices', includeInactive],
    queryFn: async () => (await api.get<Device[]>('/fleet/weighbridge/devices', { params: { includeInactive } })).data,
  });
}

export function useProductSettings() {
  return useQuery({
    queryKey: [...KEY, 'product-settings'],
    queryFn: async () => (await api.get<ProductSetting[]>('/fleet/weighbridge/product-settings')).data,
  });
}

export function useProductOptions(search: string, enabled = true) {
  return useQuery({
    queryKey: [...KEY, 'products', search],
    enabled,
    queryFn: async () => (await api.get<ProductOption[]>('/fleet/weighbridge/lookups/products', { params: { search } })).data,
  });
}

export function usePartnerOptions(search: string, role?: 'customer' | 'supplier', enabled = true) {
  return useQuery({
    queryKey: [...KEY, 'partners', search, role],
    enabled,
    queryFn: async () =>
      (await api.get<PartnerOption[]>('/fleet/weighbridge/lookups/partners', { params: { search, role } })).data,
  });
}

export function usePurchaseOrderOptions(search: string, enabled = true) {
  return useQuery({
    queryKey: [...KEY, 'purchase-orders', search],
    enabled,
    queryFn: async () =>
      (await api.get<PurchaseOrderOption[]>('/fleet/weighbridge/lookups/purchase-orders', { params: { search } })).data,
  });
}

export function useWeighTripOptions(search: string, enabled = true) {
  return useQuery({
    queryKey: [...KEY, 'trips', search],
    enabled,
    queryFn: async () => (await api.get<WeighTripOption[]>('/fleet/weighbridge/lookups/trips', { params: { search } })).data,
  });
}

export function useTripPrefill(tripId: string | undefined) {
  return useQuery({
    queryKey: [...KEY, 'trip-prefill', tripId],
    enabled: !!tripId,
    queryFn: async () => (await api.get<TripPrefill>(`/fleet/weighbridge/lookups/trips/${tripId}`)).data,
  });
}

export function useDispatchRequirements() {
  return useQuery({
    queryKey: [...KEY, 'dispatch-requirements'],
    queryFn: async () => (await api.get<DispatchRequirementOverview>('/fleet/weighbridge/dispatch-requirements')).data,
  });
}

export interface TicketListParams {
  page: number;
  pageSize: number;
  queue?: TicketQueue;
  stationId?: string;
  subjectType?: string;
  search?: string;
  manual?: boolean;
  outOfTolerance?: boolean;
  from?: string;
  to?: string;
}

export function useTickets(params: TicketListParams) {
  return useQuery({
    queryKey: [...KEY, 'tickets', params],
    placeholderData: (prev) => prev,
    refetchInterval: 15_000,
    queryFn: async () => (await api.get<PaginatedResult<Ticket>>('/fleet/weighbridge/tickets', { params })).data,
  });
}

export function useQueueCounts(stationId?: string) {
  return useQuery({
    queryKey: [...KEY, 'summary', stationId],
    refetchInterval: 15_000,
    queryFn: async () => (await api.get<QueueCounts>('/fleet/weighbridge/tickets/summary', { params: { stationId } })).data,
  });
}

export function useTicket(id: string | undefined) {
  return useQuery({
    queryKey: [...KEY, 'ticket', id],
    enabled: !!id,
    queryFn: async () => (await api.get<Ticket>(`/fleet/weighbridge/tickets/${id}`)).data,
  });
}

export function useTares(params: { page: number; pageSize: number; plate?: string; state?: string }) {
  return useQuery({
    queryKey: [...KEY, 'tares', params],
    placeholderData: (prev) => prev,
    queryFn: async () => (await api.get<PaginatedResult<StoredTare>>('/fleet/weighbridge/tares', { params })).data,
  });
}

export function useExceptions(params: { page: number; pageSize: number; status?: string }) {
  return useQuery({
    queryKey: [...KEY, 'exceptions', params],
    placeholderData: (prev) => prev,
    queryFn: async () =>
      (await api.get<PaginatedResult<WeighbridgeException>>('/fleet/weighbridge/exceptions', { params })).data,
  });
}

export function useDiscrepancyReport(params: { groupBy: string; stationId?: string; subjectType?: string; from?: string; to?: string }) {
  return useQuery({
    queryKey: [...KEY, 'report', params],
    queryFn: async () => (await api.get<DiscrepancyReport>('/fleet/weighbridge/reports/discrepancies', { params })).data,
  });
}

// ---- Mutations ----

function useWbMutation<TVars, TResult = unknown>(fn: (vars: TVars) => Promise<TResult>) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: fn,
    onSuccess: () => qc.invalidateQueries({ queryKey: [...KEY] }),
  });
}

type Body = Record<string, unknown>;

export const useCreateStation = () =>
  useWbMutation(async (body: Body) => (await api.post<Station>('/fleet/weighbridge/stations', body)).data);
export const useUpdateStation = () =>
  useWbMutation(async ({ id, body }: { id: string; body: Body }) =>
    (await api.patch<Station>(`/fleet/weighbridge/stations/${id}`, body)).data);
export const useCreateDevice = () =>
  useWbMutation(async (body: Body) => (await api.post<Device>('/fleet/weighbridge/devices', body)).data);
export const useUpdateDevice = () =>
  useWbMutation(async ({ id, body }: { id: string; body: Body }) =>
    (await api.patch<Device>(`/fleet/weighbridge/devices/${id}`, body)).data);
export const useTestRead = () =>
  useWbMutation(async (id: string) =>
    (await api.post<{ ok: boolean; reachable: boolean; detail: string; weightKg?: string; rawFrame?: string; error?: string }>(
      `/fleet/weighbridge/devices/${id}/test-read`)).data);
export const useLiveRead = () =>
  useMutation({ mutationFn: async (id: string) => (await api.post<LiveReading>(`/fleet/weighbridge/devices/${id}/read`)).data });
export const useSimulatorWeight = () =>
  useWbMutation(async ({ id, weightKg }: { id: string; weightKg: number }) =>
    (await api.post<Device>(`/fleet/weighbridge/devices/${id}/simulator-weight`, { weightKg })).data);
export const usePutProductSetting = () =>
  useWbMutation(async ({ productId, body }: { productId: string; body: Body }) =>
    (await api.put<ProductSetting>(`/fleet/weighbridge/product-settings/${productId}`, body)).data);

export const useCreateTicket = () =>
  useWbMutation(async (body: Body) => (await api.post<Ticket>('/fleet/weighbridge/tickets', body)).data);
export const useUpdateTicket = () =>
  useWbMutation(async ({ id, body }: { id: string; body: Body }) =>
    (await api.patch<Ticket>(`/fleet/weighbridge/tickets/${id}`, body)).data);
export const useWeigh = () =>
  useWbMutation(async ({ id, slot, body }: { id: string; slot: 'first' | 'second'; body: Body }) =>
    (await api.post<Ticket>(`/fleet/weighbridge/tickets/${id}/${slot}-weigh`, body)).data);
export const useUseStoredTare = () =>
  useWbMutation(async (id: string) => (await api.post<Ticket>(`/fleet/weighbridge/tickets/${id}/stored-tare`, {})).data);
export const useCancelTicket = () =>
  useWbMutation(async ({ id, reason }: { id: string; reason: string }) =>
    (await api.post<Ticket>(`/fleet/weighbridge/tickets/${id}/cancel`, { reason })).data);
export const useVoidTicket = () =>
  useWbMutation(async ({ id, body }: { id: string; body: Body }) =>
    (await api.post<{ voided: Ticket; reissued: Ticket | null }>(`/fleet/weighbridge/tickets/${id}/void`, body)).data);
export const useTicketGoodsReceipt = () =>
  useWbMutation(async ({ id, locationId }: { id: string; locationId?: string }) =>
    (await api.post<{ created: boolean; ticket: Ticket }>(`/fleet/weighbridge/tickets/${id}/goods-receipt`, { locationId })).data);
export const useCreateTare = () =>
  useWbMutation(async (body: Body) => (await api.post<StoredTare>('/fleet/weighbridge/tares', body)).data);
export const useRevokeTare = () =>
  useWbMutation(async ({ id, reason }: { id: string; reason: string }) =>
    (await api.post<StoredTare>(`/fleet/weighbridge/tares/${id}/revoke`, { reason })).data);
/** Feeding a trip changes dispatch data too: refresh both. */
export function useFeedTrip() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (id: string) => (await api.post<Ticket>(`/fleet/weighbridge/tickets/${id}/feed-trip`)).data,
    onSuccess: () => qc.invalidateQueries({ queryKey: ['fleet'] }),
  });
}
export const usePutDispatchRequirement = () =>
  useWbMutation(async (body: { branchId?: string; mode: RequirementMode; categoryIds?: string[]; notes?: string }) =>
    (await api.put<DispatchRequirement>('/fleet/weighbridge/dispatch-requirements', body)).data);
export const useDeleteDispatchRequirement = () =>
  useWbMutation(async (id: string) => (await api.delete(`/fleet/weighbridge/dispatch-requirements/${id}`)).data);
export const useDecideException = () =>
  useWbMutation(async ({ id, approve, notes }: { id: string; approve: boolean; notes?: string }) =>
    (await api.post<Ticket>(`/fleet/weighbridge/exceptions/${id}/${approve ? 'approve' : 'reject'}`, { notes: notes || undefined })).data);

/** Prints the ticket (counts a copy) and opens the PDF in a new tab. */
export function usePrintTicket() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (id: string) => {
      const res = await api.post(`/fleet/weighbridge/tickets/${id}/print`, null, { responseType: 'blob' });
      const url = URL.createObjectURL(new Blob([res.data as BlobPart], { type: 'application/pdf' }));
      window.open(url, '_blank', 'noopener');
      setTimeout(() => URL.revokeObjectURL(url), 60_000);
    },
    onSuccess: () => qc.invalidateQueries({ queryKey: [...KEY] }),
  });
}

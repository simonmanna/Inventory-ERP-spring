import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import type { PaginatedResult } from '@erp/shared';
import { api } from '@/lib/api';
import { idempotentPost } from '@/lib/idempotent-request';
import { tx } from '@/lib/i18n/tx';

// ---- Types (fleet 1.3 — trips, delivery planning & dispatch, ADR-S009) ----

export type DeliveryOrderStatus = 'open' | 'planned' | 'in_progress' | 'delivered' | 'closed' | 'cancelled';
export type TripStatus =
  | 'planned' | 'loading' | 'weighed' | 'dispatched' | 'in_transit' | 'delivered' | 'closed' | 'cancelled' | 'returned';
export type DiscrepancyStatus = 'pending' | 'approved' | 'rejected';
export type DiscrepancyKind = 'shortage' | 'excess' | 'returned';
export type SourceType = 'manual' | 'sales_order' | 'invoice';
export type Priority = 'low' | 'normal' | 'high' | 'urgent';
export type FreightBasis = 'per_trip' | 'per_tonne' | 'per_km';
export type ChargeBasis = 'flat' | 'per_trip' | 'per_tonne' | 'per_m3' | 'per_km';

export const DO_STATUS_LABEL: Record<DeliveryOrderStatus, string> = {
  open: tx('Open'),
  planned: tx('Planned'),
  in_progress: tx('In progress'),
  delivered: tx('Delivered'),
  closed: tx('Closed'),
  cancelled: tx('Cancelled'),
};

export const TRIP_STATUS_LABEL: Record<TripStatus, string> = {
  planned: tx('Planned'),
  loading: tx('Loading'),
  weighed: tx('Weighed'),
  dispatched: tx('Dispatched'),
  in_transit: tx('In transit'),
  delivered: tx('Delivered'),
  closed: tx('Closed'),
  cancelled: tx('Cancelled'),
  returned: tx('Returned'),
};

export const DISCREPANCY_STATUS_LABEL: Record<DiscrepancyStatus, string> = {
  pending: tx('Pending'),
  approved: tx('Approved'),
  rejected: tx('Rejected'),
};

export const DISCREPANCY_KIND_LABEL: Record<DiscrepancyKind, string> = {
  shortage: tx('Shortage'),
  excess: tx('Excess'),
  returned: tx('Returned'),
};

export const REASON_LABEL: Record<string, string> = {
  spillage: tx('Spillage'),
  weighing_variance: tx('Weighing variance'),
  damaged: tx('Damaged'),
  customer_rejected: tx('Customer rejected'),
  wrong_product: tx('Wrong product'),
  theft: tx('Theft'),
  site_closed: tx('Site closed'),
  other: tx('Other'),
};

export const SOURCE_TYPE_LABEL: Record<SourceType, string> = {
  manual: tx('Manual'),
  sales_order: tx('Sales order'),
  invoice: tx('Invoice'),
};

export const PRIORITY_LABEL: Record<Priority, string> = {
  low: tx('Low'),
  normal: tx('Normal'),
  high: tx('High'),
  urgent: tx('Urgent'),
};

export const FREIGHT_BASIS_LABEL: Record<FreightBasis, string> = {
  per_trip: tx('Per trip'),
  per_tonne: tx('Per tonne'),
  per_km: tx('Per km'),
};

export const CHARGE_BASIS_LABEL: Record<ChargeBasis, string> = {
  flat: tx('Flat per order'),
  per_trip: tx('Per trip'),
  per_tonne: tx('Per tonne'),
  per_m3: tx('Per m³'),
  per_km: tx('Per km'),
};

export interface CustomerSite {
  id: string;
  code: string;
  partnerId: string;
  partnerName?: string | null;
  partnerCode?: string | null;
  name: string;
  address: string | null;
  city: string | null;
  latitude: string | null;
  longitude: string | null;
  contactName: string | null;
  contactPhone: string | null;
  accessNotes: string | null;
  zone: string | null;
  distanceKm: string | null;
  isActive: boolean;
  notes: string | null;
  deliveryOrders?: number;
}

export interface DeliveryOrderLine {
  id: string;
  lineNumber: number;
  productId: string;
  productCode: string | null;
  productName: string;
  trackInventory: boolean;
  description: string | null;
  uomId: string | null;
  uomCode: string | null;
  uomFactor: string;
  orderedQty: string;
  plannedQty: string;
  dispatchedQty: string;
  deliveredQty: string;
  returnedQty: string;
  remainingQty: string;
  unitPrice: string | null;
  taxId: string | null;
  batchNumber: string | null;
  tonnesPerUnit: string | null;
  m3PerUnit: string | null;
  factorSource: string | null;
}

export interface TripSummary {
  id: string;
  code: string;
  deliveryOrderId: string;
  deliveryOrderCode: string;
  sequence: number;
  plannedDate: string;
  status: TripStatus;
  carrier: 'own' | 'transporter';
  vehicleId: string | null;
  vehicleCode: string | null;
  vehiclePlate: string | null;
  trailerId: string | null;
  trailerCode: string | null;
  driverId: string | null;
  driverCode: string | null;
  driverName: string | null;
  transporterPartnerId: string | null;
  transporterName: string | null;
  transporterPlate: string | null;
  transporterDriverName: string | null;
  transporterRateId: string | null;
  freightBasis: FreightBasis | null;
  freightRate: string | null;
  freightAmount: string | null;
  partnerId: string;
  partnerName: string | null;
  siteName: string | null;
  siteCode: string | null;
  capacityT: string | null;
  capacityM3: string | null;
  plannedTonnes: string | null;
  plannedM3: string | null;
  loadedTonnes: string | null;
  deliveredTonnes: string | null;
  locationId: string;
  costCenterId: string | null;
  purpose: string | null;
  startOdometer: string | null;
  endOdometer: string | null;
  distanceKm: string | null;
  distanceSource: string | null;
  weighSourceType: string | null;
  weighSourceId: string | null;
  deliveryNoteNumber: string | null;
  deliveryNotePrints: number;
  dispatchedAt: string | null;
  departedAt: string | null;
  deliveredAt: string | null;
  closedAt: string | null;
  cancelledAt: string | null;
  returnedAt: string | null;
  cancelReason: string | null;
  returnReason: string | null;
  notes: string | null;
}

export interface Discrepancy {
  id: string;
  code: string;
  deliveryOrderId: string;
  deliveryOrderCode?: string;
  loadId: string;
  tripCode?: string;
  loadLineId: string;
  productId: string;
  productName?: string | null;
  productCode?: string | null;
  partnerName?: string | null;
  uomCode?: string | null;
  kind: DiscrepancyKind;
  reason: string;
  dispatchedQty: string;
  deliveredQty: string;
  differenceQty: string;
  notes: string | null;
  status: DiscrepancyStatus;
  approvalRequestId: string | null;
  raisedBy: string | null;
  decidedAt: string | null;
  decisionNotes: string | null;
  creditNoteId: string | null;
  creditNoteNumber: string | null;
  creditAmount: string | null;
  createdAt: string;
}

export interface DeliveryCharge {
  id: string;
  ruleId: string | null;
  description: string;
  basis: ChargeBasis;
  quantity: string;
  rate: string;
  amount: string;
  invoiceId: string | null;
  invoiceNumber: string | null;
}

export interface DeliveryOrder {
  id: string;
  code: string;
  partnerId: string;
  partnerName: string | null;
  partnerCode: string | null;
  siteId: string | null;
  siteCode: string | null;
  siteName: string | null;
  siteAddress: string | null;
  siteCity: string | null;
  siteZone: string | null;
  sourceType: SourceType;
  sourceId: string | null;
  sourceNumber: string | null;
  stockMode: 'dispatch' | 'invoiced';
  goodsInvoiceId: string | null;
  goodsInvoiceNumber: string | null;
  chargesInvoiceId: string | null;
  locationId: string;
  locationName: string | null;
  costCenterId: string | null;
  requestedDate: string;
  windowFrom: string | null;
  windowTo: string | null;
  priority: Priority;
  status: DeliveryOrderStatus;
  contactName: string | null;
  contactPhone: string | null;
  notes: string | null;
  cancelReason: string | null;
  closeReason: string | null;
  createdAt: string;
  lineCount: number;
  orderedQty: string;
  unplannedQty: string;
  deliveredQty: string;
  tripCount: number;
  tripsDone: number;
}

export interface DeliveryOrderDetail extends DeliveryOrder {
  site: CustomerSite | null;
  lines: DeliveryOrderLine[];
  trips: TripSummary[];
  discrepancies: Discrepancy[];
  charges: DeliveryCharge[];
  weighbridgeTickets?: LinkedWeighTicket[];
  createdTripIds?: string[];
  warnings?: string[];
  invoice?: { id: string; documentNumber: string; totalAmount: string; status: string };
}

export interface TripLine {
  id: string;
  deliveryOrderLineId: string;
  productId: string;
  productCode: string | null;
  productName: string;
  trackInventory: boolean;
  uomId: string | null;
  uomCode: string | null;
  plannedQty: string;
  loadedQty: string | null;
  dispatchedQty: string | null;
  deliveredQty: string | null;
  returnedQty: string;
  billableQty: string | null;
  orderedQty: string;
  tonnesPerUnit: string | null;
  batchNumber: string | null;
  stockLedgerCode: string | null;
  unitCost: string | null;
  stockValue: string | null;
  returnLedgerCode: string | null;
}

export interface ProofOfDelivery {
  id: string;
  receiverName: string;
  receiverPhone: string | null;
  receivedAt: string;
  latitude: string | null;
  longitude: string | null;
  locationSource: 'device' | 'telematics' | 'none';
  signatureUrl: string | null;
  photoUrl: string | null;
  notes: string | null;
}

/** A weighbridge ticket linked to a trip / delivery order (fleet 1.4b). */
export interface LinkedWeighTicket {
  id: string;
  ticketNumber: string;
  status: 'open' | 'first_weighed' | 'exception' | 'completed' | 'cancelled' | 'void';
  tripId: string | null;
  tripCode: string | null;
  registrationPlate: string;
  grossKg: string | null;
  tareKg: string | null;
  netKg: string | null;
  netQty: string | null;
  netUomCode: string | null;
  expectedKg: string | null;
  varianceKg: string | null;
  variancePct: string | null;
  withinTolerance: boolean | null;
  isManual: boolean;
  usedStoredTare: boolean;
  completedAt: string | null;
  voidedAt: string | null;
  voidReason: string | null;
  replacedById: string | null;
  tripFeedStatus: 'fed' | 'skipped' | 'failed' | 'cleared' | null;
  tripFeedMessage: string | null;
  tripFedAt: string | null;
  createdAt: string;
}

/** Weighbridge state of a trip: required for dispatch (and why), live ticket, fed by a completed ticket. */
export interface TripWeighbridge {
  required: boolean;
  reason: string | null;
  ruleId: string | null;
  mode: 'off' | 'all' | 'bulk' | 'categories';
  branchId: string | null;
  liveTicket: { id: string; ticketNumber: string; status: string } | null;
  fedByTicket: boolean;
}

export interface TripDetail extends TripSummary {
  lines: TripLine[];
  deliveryOrder: (DeliveryOrder & { site?: CustomerSite | null }) | null;
  pod: ProofOfDelivery | null;
  discrepancies: Discrepancy[];
  weighbridgeTickets?: LinkedWeighTicket[];
  weighbridge?: TripWeighbridge;
}

export interface BoardDay { date: string; trips: TripSummary[] }
export interface Board { from: string; to: string; days: BoardDay[]; unplanned: DeliveryOrder[] }

export interface DispatchLookups {
  vehicles: Array<{
    id: string; code: string; registrationPlate: string | null; status: string; ownership: string;
    payloadCapacityT: string | null; bodyVolumeM3: string | null; categoryName: string | null; kind: string | null;
  }>;
  drivers: Array<{ id: string; code: string; status: string; name: string }>;
  transporters: Array<{ id: string; code: string | null; name: string }>;
  transporterRates: Array<{ id: string; partnerId: string; name: string; basis: FreightBasis; rate: string; zone: string | null }>;
  locations: Array<{ id: string; code: string | null; name: string; type: string }>;
  defaultLocationId: string | null;
  costCenters: Array<{ id: string; code: string; name: string }>;
  taxes: Array<{ id: string; name: string; rate: string }>;
}

export interface SourceDoc {
  id: string; number: string; status: string; date: string | null; total: string | null;
  partnerId: string | null; partnerName: string | null; invoiceId?: string | null;
}

export interface ProductOption {
  id: string; code: string | null; name: string; uomId: string | null; uomCode: string | null;
  salesPrice: string | null; trackInventory: boolean; batchTracking: boolean;
  tonnesPerUnit: string | null; m3PerUnit: string | null;
}

export interface CustomerOption { id: string; code: string | null; name: string; phone: string | null }

export interface LoadPlanPreview {
  loads: Array<{ lines: Array<{ deliveryOrderLineId: string; productName: string; uomCode: string | null; quantity: string }>; tonnes: string; m3: string }>;
  warnings: string[];
  capacityT: string | null;
  capacityM3: string | null;
}

export interface AssignmentCheck {
  eligible: boolean;
  reasons: string[];
  warnings: string[];
  capacityT: string | null;
  capacityM3: string | null;
  plannedTonnes: string | null;
  plannedM3: string | null;
  loadedTonnes: string | null;
}

export interface ChargePreview {
  facts: { zone: string | null; siteDistanceKm: string | null; trips: number; tonnes: string; m3: string; tripKm: string };
  rule: { id: string; name: string; basis: ChargeBasis } | null;
  charge: { description: string; quantity: string; rate: string; amount: string; taxId: string | null } | null;
  alreadyInvoiced: boolean;
}

export interface TransporterRate {
  id: string; partnerId: string; partnerName: string | null; name: string; basis: FreightBasis; rate: string;
  zone: string | null; effectiveFrom: string | null; effectiveTo: string | null; isActive: boolean; notes: string | null;
  usage?: number;
}

export interface ChargeRule {
  id: string; name: string; partnerId: string | null; partnerName: string | null; zone: string | null;
  minDistanceKm: string | null; maxDistanceKm: string | null; basis: ChargeBasis; rate: string;
  minAmount: string | null; taxId: string | null; taxName: string | null; isActive: boolean; notes: string | null;
}

export interface LoadFactor {
  id: string; productId: string; productCode: string | null; productName: string; uomCode: string | null;
  tonnesPerUnit: string | null; cubicMetresPerUnit: string | null; notes: string | null;
}

// ---- Queries ----

const KEY = ['fleet', 'dispatch'] as const;

export function useDispatchLookups() {
  return useQuery({
    queryKey: [...KEY, 'lookups'],
    staleTime: 30_000,
    queryFn: async () => (await api.get<DispatchLookups>('/fleet/dispatch/lookups')).data,
  });
}

export function useCustomerPicker(search: string, enabled: boolean) {
  return useQuery({
    queryKey: [...KEY, 'customers', search],
    enabled,
    queryFn: async () => (await api.get<CustomerOption[]>('/fleet/dispatch/lookups/customers', { params: { search } })).data,
  });
}

export function useProductPicker(search: string, enabled: boolean) {
  return useQuery({
    queryKey: [...KEY, 'products', search],
    enabled,
    queryFn: async () => (await api.get<ProductOption[]>('/fleet/dispatch/lookups/products', { params: { search } })).data,
  });
}

export function useDispatchSources(type: 'sales_order' | 'invoice', search: string, enabled: boolean) {
  return useQuery({
    queryKey: [...KEY, 'sources', type, search],
    enabled,
    queryFn: async () => (await api.get<SourceDoc[]>('/fleet/dispatch/sources', { params: { type, search } })).data,
  });
}

export function useDispatchBoard(params: { from?: string; to?: string; partnerId?: string; siteId?: string }) {
  return useQuery({
    queryKey: [...KEY, 'board', params],
    placeholderData: (prev) => prev,
    queryFn: async () => (await api.get<Board>('/fleet/dispatch-board', { params })).data,
  });
}

export function useCustomerSites(params: { page: number; pageSize: number; search?: string; partnerId?: string; zone?: string; includeInactive?: boolean }, enabled = true) {
  return useQuery({
    queryKey: [...KEY, 'sites', params],
    enabled,
    placeholderData: (prev) => prev,
    queryFn: async () => (await api.get<PaginatedResult<CustomerSite>>('/fleet/customer-sites', { params })).data,
  });
}

export function useDeliveryOrders(params: Record<string, unknown>) {
  return useQuery({
    queryKey: [...KEY, 'orders', params],
    placeholderData: (prev) => prev,
    queryFn: async () => (await api.get<PaginatedResult<DeliveryOrder>>('/fleet/delivery-orders', { params })).data,
  });
}

export function useDeliveryOrder(id: string | undefined) {
  return useQuery({
    queryKey: [...KEY, 'order', id],
    enabled: !!id,
    queryFn: async () => (await api.get<DeliveryOrderDetail>(`/fleet/delivery-orders/${id}`)).data,
  });
}

export function useLoadPlan(id: string, params: { vehicleId?: string; trailerId?: string; payloadT?: string; volumeM3?: string; maxLoads?: number }, enabled: boolean) {
  return useQuery({
    queryKey: [...KEY, 'order', id, 'plan', params],
    enabled,
    retry: false,
    queryFn: async () => (await api.get<LoadPlanPreview>(`/fleet/delivery-orders/${id}/load-plan`, { params })).data,
  });
}

export function useChargePreview(id: string, enabled: boolean) {
  return useQuery({
    queryKey: [...KEY, 'order', id, 'charge'],
    enabled,
    queryFn: async () => (await api.get<ChargePreview>(`/fleet/delivery-orders/${id}/delivery-charge`)).data,
  });
}

export function useTrips(params: Record<string, unknown>) {
  return useQuery({
    queryKey: [...KEY, 'trips', params],
    placeholderData: (prev) => prev,
    queryFn: async () => (await api.get<PaginatedResult<TripSummary>>('/fleet/trips', { params })).data,
  });
}

export function useTrip(id: string | undefined) {
  return useQuery({
    queryKey: [...KEY, 'trip', id],
    enabled: !!id,
    queryFn: async () => (await api.get<TripDetail>(`/fleet/trips/${id}`)).data,
  });
}

export function useAssignmentCheck(id: string, params: { vehicleId?: string; trailerId?: string; driverId?: string }, enabled: boolean) {
  return useQuery({
    queryKey: [...KEY, 'trip', id, 'check', params],
    enabled,
    queryFn: async () => (await api.get<AssignmentCheck>(`/fleet/trips/${id}/assignment-check`, { params })).data,
  });
}

export function useDiscrepancies(params: Record<string, unknown>) {
  return useQuery({
    queryKey: [...KEY, 'discrepancies', params],
    placeholderData: (prev) => prev,
    queryFn: async () => (await api.get<PaginatedResult<Discrepancy>>('/fleet/delivery-discrepancies', { params })).data,
  });
}

export function useTransporterRates(includeInactive = false) {
  return useQuery({
    queryKey: [...KEY, 'rates', includeInactive],
    queryFn: async () => (await api.get<TransporterRate[]>('/fleet/transporter-rates', { params: { includeInactive } })).data,
  });
}

export function useChargeRules(includeInactive = false) {
  return useQuery({
    queryKey: [...KEY, 'charge-rules', includeInactive],
    queryFn: async () => (await api.get<ChargeRule[]>('/fleet/delivery-charge-rules', { params: { includeInactive } })).data,
  });
}

export function useLoadFactors() {
  return useQuery({
    queryKey: [...KEY, 'load-factors'],
    queryFn: async () => (await api.get<LoadFactor[]>('/fleet/product-load-factors')).data,
  });
}

/** Delivery note PDF in a new tab (counts a print; reprints say COPY). */
export async function openDeliveryNote(tripId: string) {
  const res = await api.get<Blob>(`/fleet/trips/${tripId}/delivery-note`, { responseType: 'blob' });
  const url = URL.createObjectURL(new Blob([res.data], { type: 'application/pdf' }));
  window.open(url, '_blank', 'noopener');
  setTimeout(() => URL.revokeObjectURL(url), 60_000);
}

// ---- Mutations ----

function useDispatchMutation<TVars, TResult = unknown>(fn: (vars: TVars) => Promise<TResult>) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: fn,
    onSuccess: () => qc.invalidateQueries({ queryKey: KEY }),
  });
}

type Body = Record<string, unknown>;

/** Form text → JSON number (the API takes decimals as numbers); blank → undefined. */
export const toNum = (v?: string | null) => (v === undefined || v === null || v.trim() === '' ? undefined : Number(v));

export const useCreateSite = () =>
  useDispatchMutation(async (body: Body) => (await api.post<CustomerSite>('/fleet/customer-sites', body)).data);
export const useUpdateSite = () =>
  useDispatchMutation(async ({ id, body }: { id: string; body: Body }) => (await api.patch<CustomerSite>(`/fleet/customer-sites/${id}`, body)).data);
export const useDeleteSite = () =>
  useDispatchMutation(async (id: string) => (await api.delete<{ deleted: boolean; deactivated: boolean }>(`/fleet/customer-sites/${id}`)).data);

export const useCreateDeliveryOrder = () =>
  useDispatchMutation(async (body: Body) => (await api.post<DeliveryOrderDetail>('/fleet/delivery-orders', body)).data);
export const useCreateFromSource = () =>
  useDispatchMutation(async ({ type, body }: { type: 'sales_order' | 'invoice'; body: Body }) =>
    (await api.post<DeliveryOrderDetail>(type === 'invoice' ? '/fleet/delivery-orders/from-invoice' : '/fleet/delivery-orders/from-sales-order', body)).data);
export const useUpdateDeliveryOrder = () =>
  useDispatchMutation(async ({ id, body }: { id: string; body: Body }) => (await api.patch<DeliveryOrderDetail>(`/fleet/delivery-orders/${id}`, body)).data);
export const useCancelDeliveryOrder = () =>
  useDispatchMutation(async ({ id, reason }: { id: string; reason: string }) => (await api.post<DeliveryOrderDetail>(`/fleet/delivery-orders/${id}/cancel`, { reason })).data);
export const useCloseDeliveryOrder = () =>
  useDispatchMutation(async ({ id, reason }: { id: string; reason?: string }) => (await api.post<DeliveryOrderDetail>(`/fleet/delivery-orders/${id}/close`, { reason: reason || undefined })).data);
export const usePlanLoads = () =>
  useDispatchMutation(async ({ id, body }: { id: string; body: Body }) => (await api.post<DeliveryOrderDetail>(`/fleet/delivery-orders/${id}/plan-loads`, body)).data);
export const useAddTrip = () =>
  useDispatchMutation(async ({ id, body }: { id: string; body: Body }) => (await api.post<TripDetail>(`/fleet/delivery-orders/${id}/trips`, body)).data);
/** Draft customer invoice (goods + transport charge) — Idempotency-Key. */
export const useInvoiceDeliveryOrder = () =>
  useDispatchMutation(async ({ id, body }: { id: string; body?: Body }) => idempotentPost<DeliveryOrderDetail>(`/fleet/delivery-orders/${id}/invoice`, body ?? {}));

export const useUpdateTrip = () =>
  useDispatchMutation(async ({ id, body }: { id: string; body: Body }) => (await api.patch<TripDetail>(`/fleet/trips/${id}`, body)).data);
export const useAssignTrip = () =>
  useDispatchMutation(async ({ id, body }: { id: string; body: Body }) => (await api.post<TripDetail>(`/fleet/trips/${id}/assign`, body)).data);
export const useStartLoading = () =>
  useDispatchMutation(async ({ id, odometer }: { id: string; odometer?: string }) => (await api.post<TripDetail>(`/fleet/trips/${id}/start-loading`, odometer ? { odometer: toNum(odometer) } : {})).data);
export const useLoadedQuantity = () =>
  useDispatchMutation(async ({ id, lines }: { id: string; lines: Array<{ loadLineId: string; quantity: string; batchNumber?: string }> }) =>
    (await api.post<TripDetail>(`/fleet/trips/${id}/loaded-quantity`, { lines: lines.map((l) => ({ ...l, quantity: toNum(l.quantity) })) })).data);
/** Issues the stock: Idempotency-Key. */
export const useDispatchTrip = () =>
  useDispatchMutation(async ({ id, odometer }: { id: string; odometer?: string }) => idempotentPost<TripDetail>(`/fleet/trips/${id}/dispatch`, odometer ? { odometer: toNum(odometer) } : {}));
export const useDepartTrip = () =>
  useDispatchMutation(async (id: string) => (await api.post<TripDetail>(`/fleet/trips/${id}/depart`)).data);
/** POD (returned goods go back to stock): Idempotency-Key. */
export const useProofOfDelivery = () =>
  useDispatchMutation(async ({ id, body }: { id: string; body: Body }) => idempotentPost<TripDetail>(`/fleet/trips/${id}/proof-of-delivery`, body));
export const usePodPhoto = () =>
  useDispatchMutation(async ({ id, file }: { id: string; file: File }) => {
    const fd = new FormData();
    fd.append('file', file);
    return (await api.post<TripDetail>(`/fleet/trips/${id}/pod-photo`, fd, { headers: { 'Content-Type': 'multipart/form-data' } })).data;
  });
export const useCloseTrip = () =>
  useDispatchMutation(async ({ id, body }: { id: string; body: Body }) => (await api.post<TripDetail>(`/fleet/trips/${id}/close`, body)).data);
export const useCancelTrip = () =>
  useDispatchMutation(async ({ id, reason }: { id: string; reason: string }) => (await api.post<TripDetail>(`/fleet/trips/${id}/cancel`, { reason })).data);
/** Whole load back to the yard (stock in): Idempotency-Key. */
export const useReturnTrip = () =>
  useDispatchMutation(async ({ id, reason, reasonCode }: { id: string; reason: string; reasonCode?: string }) =>
    idempotentPost<TripDetail>(`/fleet/trips/${id}/return`, { reason, reasonCode: reasonCode || undefined }));

/** Approve (may raise a draft credit note): Idempotency-Key. */
export const useApproveDiscrepancy = () =>
  useDispatchMutation(async ({ id, notes }: { id: string; notes?: string }) => idempotentPost<Discrepancy>(`/fleet/delivery-discrepancies/${id}/approve`, { notes: notes || undefined }));
export const useRejectDiscrepancy = () =>
  useDispatchMutation(async ({ id, notes }: { id: string; notes: string }) => (await api.post<Discrepancy>(`/fleet/delivery-discrepancies/${id}/reject`, { notes })).data);
export const useSyncDiscrepancy = () =>
  useDispatchMutation(async (id: string) => (await api.post<Discrepancy>(`/fleet/delivery-discrepancies/${id}/sync`)).data);

export const useCreateRate = () =>
  useDispatchMutation(async (body: Body) => (await api.post<TransporterRate>('/fleet/transporter-rates', body)).data);
export const useUpdateRate = () =>
  useDispatchMutation(async ({ id, body }: { id: string; body: Body }) => (await api.patch<TransporterRate>(`/fleet/transporter-rates/${id}`, body)).data);
export const useDeleteRate = () =>
  useDispatchMutation(async (id: string) => (await api.delete(`/fleet/transporter-rates/${id}`)).data);
export const useCreateChargeRule = () =>
  useDispatchMutation(async (body: Body) => (await api.post<ChargeRule>('/fleet/delivery-charge-rules', body)).data);
export const useUpdateChargeRule = () =>
  useDispatchMutation(async ({ id, body }: { id: string; body: Body }) => (await api.patch<ChargeRule>(`/fleet/delivery-charge-rules/${id}`, body)).data);
export const useDeleteChargeRule = () =>
  useDispatchMutation(async (id: string) => (await api.delete(`/fleet/delivery-charge-rules/${id}`)).data);
export const usePutLoadFactor = () =>
  useDispatchMutation(async ({ productId, body }: { productId: string; body: Body }) => (await api.put<LoadFactor>(`/fleet/product-load-factors/${productId}`, body)).data);
export const useDeleteLoadFactor = () =>
  useDispatchMutation(async (productId: string) => (await api.delete(`/fleet/product-load-factors/${productId}`)).data);

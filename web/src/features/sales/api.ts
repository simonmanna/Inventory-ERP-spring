import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from '@/lib/api';

// ---- Types (Sales / order-to-cash, ADR-S011) ----

export type SalesOrderStatus = 'draft' | 'pending_approval' | 'confirmed' | 'in_fulfilment' | 'done' | 'cancelled';
export type DeliveryStatus = 'none' | 'partial' | 'full';
export type InvoiceStatus = 'nothing' | 'to_invoice' | 'partial' | 'invoiced';
export type ChangeRequestStatus = 'draft' | 'pending_approval' | 'applied' | 'rejected';

export interface LineQuantities {
  ordered: number;
  cancelled: number;
  reserved: number;
  delivered: number;
  billable: number;
  grossInvoiced: number;
  credited: number;
  netInvoiced: number;
  returned: number;
  remaining: number;
  outstandingBillable: number;
}

export interface SalesOrderLine {
  id?: string;
  sortOrder?: number;
  productId: string | null;
  productName?: string | null;
  productCode?: string | null;
  /** ADR-S058: product variant (required for a product with variants). */
  variantId?: string | null;
  variantName?: string | null;
  description: string;
  quantity: number;
  uomId?: string | null;
  uomFactor?: number;
  unitPrice: number;
  listPrice?: number;
  discountPercent: number;
  priceSource?: string;
  taxId: string | null;
  taxInclusive?: boolean;
  lineSubtotal?: number;
  lineTax?: number;
  lineTotal?: number;
  promisedDate?: string | null;
  sourceLocationId?: string | null;
  closedShort?: boolean;
  closeShortReason?: string | null;
  qty?: LineQuantities;
}

export interface SalesOrder {
  id: string;
  number: string;
  revision: number;
  status: SalesOrderStatus;
  onHold: boolean;
  holdReason: string | null;
  holdKind: 'manual' | 'credit' | 'customer' | null;
  approvalReasons: string[];
  deliveryStatus: DeliveryStatus;
  invoiceStatus: InvoiceStatus;
  partnerId: string;
  partnerName: string | null;
  quotationId: string | null;
  quotationNumber: string | null;
  branchId: string | null;
  locationId: string | null;
  siteId: string | null;
  deliveryAddress: string | null;
  deliveryMethod: 'delivery' | 'collect';
  paymentTermId: string | null;
  currencyCode: string;
  exchangeRate: number;
  customerRef: string | null;
  agreementId?: string | null;
  requestedDate: string | null;
  commitmentDate: string | null;
  ownerId: string | null;
  ownerName: string | null;
  notes: string | null;
  subtotal: number;
  discountTotal: number;
  taxAmount: number;
  total: number;
  confirmedAt: string | null;
  cancelledAt: string | null;
  cancelReason: string | null;
  createdAt: string;
  lines?: SalesOrderLine[];
  relations?: { quotation: { id: string; number: string } | null; agreement?: { id: string; number: string; name: string; status: string } | null; deliveries: Array<{ id: string; number: string; status: string }>; invoices: Array<{ id: string; number: string; status: string }>; changeRequests: number; revisions: number };
}

export interface ChangeRequest {
  id: string;
  orderId: string;
  number: string;
  status: ChangeRequestStatus;
  reason: string;
  changes: Record<string, unknown>;
  impact: Record<string, unknown> | null;
  requestedById: string | null;
  decidedById: string | null;
  decisionComment: string | null;
  appliedRevision: number | null;
  createdAt: string;
}

export interface Paged<T, Totals = unknown> {
  data: T[];
  meta: { page: number; pageSize: number; total: number; totalPages: number };
  totals?: Totals;
}

export interface SalesSummary {
  quotations: Record<string, { count: number; total: number }>;
  pendingApprovals: number;
  openOrders: { count: number; total: number };
  toFulfil: { count: number; total: number };
  onHold: number;
  conversion: { quotes: number; converted: number; rate: number };
  monthly: Array<{ month: string; orders: number; total: number }>;
}

const clean = (p: Record<string, unknown>) => Object.fromEntries(Object.entries(p).filter(([, v]) => v !== undefined && v !== ''));
const idem = () => ({ headers: { 'Idempotency-Key': crypto.randomUUID() } });

export const salesApi = {
  createOrder: async (body: Record<string, unknown>) => (await api.post<SalesOrder>('/sales/orders', body)).data,
  updateOrder: async ({ id, ...body }: { id: string } & Record<string, unknown>) => (await api.put<SalesOrder>(`/sales/orders/${id}`, body)).data,
  deleteOrder: async (id: string) => (await api.delete(`/sales/orders/${id}`)).data,
  orderAction: async ({ id, action, body }: { id: string; action: 'confirm' | 'hold' | 'release' | 'cancel' | 'duplicate' | 'approve' | 'reject'; body?: Record<string, unknown> }) =>
    (await api.post<SalesOrder>(`/sales/orders/${id}/${action}`, body ?? {}, action === 'confirm' ? idem() : undefined)).data,
  closeShort: async ({ id, lineId, reason }: { id: string; lineId: string; reason: string }) =>
    (await api.post<SalesOrder>(`/sales/orders/${id}/lines/${lineId}/close-short`, { reason })).data,
  requestChange: async ({ id, ...body }: { id: string; reason: string; changes: Record<string, unknown> }) =>
    (await api.post<ChangeRequest>(`/sales/orders/${id}/change-requests`, body)).data,
  decideChange: async ({ id, action, comment }: { id: string; action: 'approve' | 'reject'; comment?: string }) =>
    (await api.post<ChangeRequest>(`/sales/change-requests/${id}/${action}`, { comment }, idem())).data,
  updateSettings: async (body: Record<string, unknown>) => (await api.put('/sales/settings', body)).data,
};

export function useSalesMutation<TVars, TOut = unknown>(fn: (v: TVars) => Promise<TOut>) {
  const qc = useQueryClient();
  return useMutation({ mutationFn: fn, onSuccess: () => qc.invalidateQueries({ queryKey: ['sales'] }) });
}

export function useSalesOrders(params: Record<string, string | number | undefined>) {
  return useQuery({
    queryKey: ['sales', 'orders', params],
    queryFn: async () => (await api.get<Paged<SalesOrder, { count: number; total: number }>>('/sales/orders', { params: clean(params) })).data,
    placeholderData: keepPreviousData,
  });
}

export function useSalesOrder(id: string | undefined) {
  return useQuery({
    queryKey: ['sales', 'order', id],
    queryFn: async () => (await api.get<SalesOrder>(`/sales/orders/${id}`)).data,
    enabled: !!id,
  });
}

export function useChangeRequests(orderId: string | undefined) {
  return useQuery({
    queryKey: ['sales', 'changes', orderId],
    queryFn: async () => (await api.get<ChangeRequest[]>(`/sales/orders/${orderId}/change-requests`)).data,
    enabled: !!orderId,
  });
}

export function useOrderRevisions(orderId: string | undefined, enabled: boolean) {
  return useQuery({
    queryKey: ['sales', 'revisions', orderId],
    queryFn: async () => (await api.get<Array<{ id: string; revision: number; reason: string | null; createdAt: string; total: number }>>(`/sales/orders/${orderId}/revisions`)).data,
    enabled: !!orderId && enabled,
  });
}

export function useSalesSummary() {
  return useQuery({
    queryKey: ['sales', 'summary'],
    queryFn: async () => (await api.get<SalesSummary>('/sales/reports/summary')).data,
  });
}

export function useSalesSettings() {
  return useQuery({
    queryKey: ['sales', 'settings'],
    queryFn: async () => (await api.get<Record<string, unknown>>('/sales/settings')).data,
  });
}

// ---- Pricing (phase S2) ----

export type PricelistType = 'retail' | 'wholesale' | 'contractor' | 'distributor' | 'contract' | 'other';
export type PriceCompute = 'fixed' | 'discount' | 'surcharge' | 'cost_plus';

export interface PricelistItem {
  id?: string;
  scope: 'product' | 'category' | 'all';
  productId: string | null;
  productName?: string | null;
  productCategoryId: string | null;
  partnerId: string | null;
  partnerName?: string | null;
  partnerCategoryId: string | null;
  uomId: string | null;
  minQuantity: number;
  siteZone: string | null;
  branchId: string | null;
  validFrom: string | null;
  validTo: string | null;
  compute: PriceCompute;
  value: number;
  priority: number;
  notes?: string | null;
}

export interface Pricelist {
  id: string;
  code: string;
  name: string;
  type: PricelistType;
  currencyCode: string | null;
  branchId: string | null;
  basePricelistId: string | null;
  validFrom: string | null;
  validTo: string | null;
  isDefault: boolean;
  isActive: boolean;
  notes: string | null;
  itemCount?: number;
  customerCount?: number;
  items?: PricelistItem[];
  customers?: Array<{ id: string; name: string }>;
  categories?: Array<{ id: string; name: string }>;
}

export interface MarginFloor {
  id?: string;
  productId: string | null;
  productName?: string | null;
  productCategoryId: string | null;
  branchId: string | null;
  partnerCategoryId: string | null;
  minMarginPercent: number;
  validFrom: string | null;
  validTo: string | null;
  isActive: boolean;
}

export interface ResolvedPrice {
  productId: string;
  quantity: number;
  listPrice: number;
  unitPrice: number;
  discountPercent: number;
  net: number;
  source: string;
  pricelistId: string | null;
  pricelistName: string | null;
  itemId: string | null;
  floorPrice: number | null;
  minMarginPercent: number | null;
  cost: number | null;
  marginPercent: number | null;
  description: string;
}

export const pricingApi = {
  savePricelist: async ({ id, ...body }: Partial<Pricelist> & { id?: string }) =>
    (id ? await api.put<Pricelist>(`/sales/pricelists/${id}`, body) : await api.post<Pricelist>('/sales/pricelists', body)).data,
  deletePricelist: async (id: string) => (await api.delete(`/sales/pricelists/${id}`)).data,
  saveItem: async ({ pricelistId, id, ...body }: PricelistItem & { pricelistId: string }) =>
    (id ? await api.put<PricelistItem>(`/sales/pricelists/${pricelistId}/items/${id}`, body) : await api.post<PricelistItem>(`/sales/pricelists/${pricelistId}/items`, body)).data,
  deleteItem: async ({ pricelistId, id }: { pricelistId: string; id: string }) => (await api.delete(`/sales/pricelists/${pricelistId}/items/${id}`)).data,
  assign: async ({ pricelistId, partnerIds, partnerCategoryIds }: { pricelistId: string; partnerIds?: string[]; partnerCategoryIds?: string[] }) =>
    (await api.post<Pricelist>(`/sales/pricelists/${pricelistId}/assign`, { partnerIds, partnerCategoryIds })).data,
  saveFloor: async ({ id, ...body }: MarginFloor) =>
    (id ? await api.put<MarginFloor>(`/sales/margin-floors/${id}`, body) : await api.post<MarginFloor>('/sales/margin-floors', body)).data,
  deleteFloor: async (id: string) => (await api.delete(`/sales/margin-floors/${id}`)).data,
  resolve: async (body: { partnerId?: string; branchId?: string; siteId?: string; date?: string; lines: Array<{ productId: string; quantity: number; uomId?: string }> }) =>
    (await api.post<{ lines: ResolvedPrice[] }>('/sales/pricing/resolve', body)).data,
};

export function usePricelists() {
  return useQuery({ queryKey: ['sales', 'pricelists'], queryFn: async () => (await api.get<Pricelist[]>('/sales/pricelists')).data });
}

export function usePricelist(id: string | undefined) {
  return useQuery({ queryKey: ['sales', 'pricelist', id], queryFn: async () => (await api.get<Pricelist>(`/sales/pricelists/${id}`)).data, enabled: !!id });
}

export function useMarginFloors() {
  return useQuery({ queryKey: ['sales', 'floors'], queryFn: async () => (await api.get<MarginFloor[]>('/sales/margin-floors')).data });
}

// ---- Credit (phase S3) ----

export interface CreditExposure {
  partnerId: string;
  currencyCode: string;
  limit: number;
  limitSource: 'credit_limit' | 'partner' | 'group' | 'none';
  components: { undelivered: number; deliveredUninvoiced: number; receivables: number; other: number; deposits: number; credits: number };
  exposure: number;
  headroom: number | null;
  overdue: { amount: number; maxDays: number };
  creditHold: boolean;
  riskCategory: string | null;
  group: { parentId: string; limit: number; exposure: number } | null;
}

export interface CreditDecision {
  id: string;
  partnerId: string;
  partnerName?: string;
  checkpoint: string;
  documentType: string | null;
  documentNumber: string | null;
  amount: number;
  limitAmount: number;
  result: 'pass' | 'hold' | 'blocked' | 'override';
  reasons: string[];
  exposure: CreditExposure;
  decidedByName?: string | null;
  createdAt: string;
}

export interface CreditLimit {
  id?: string;
  partnerId: string;
  partnerName?: string;
  branchId: string | null;
  currencyCode: string | null;
  amount: number;
  riskCategory: string | null;
  reviewDate: string | null;
  periodDays: number | null;
  validFrom: string | null;
  validTo: string | null;
  notes: string | null;
}

export const creditApi = {
  saveLimit: async ({ id, ...body }: CreditLimit) =>
    (id ? await api.put<CreditLimit>(`/credit/limits/${id}`, body) : await api.post<CreditLimit>('/credit/limits', body)).data,
  deleteLimit: async (id: string) => (await api.delete(`/credit/limits/${id}`)).data,
  setHold: async ({ partnerId, hold, reason }: { partnerId: string; hold: boolean; reason: string }) =>
    (await api.post(`/credit/partners/${partnerId}/hold`, { hold, reason })).data,
  updatePolicy: async (body: Record<string, unknown>) => (await api.put('/credit/policy', body)).data,
};

export function useCreditExposure(partnerId: string | undefined) {
  return useQuery({
    queryKey: ['sales', 'credit', partnerId],
    queryFn: async () => (await api.get<CreditExposure>(`/credit/partners/${partnerId}/exposure`)).data,
    enabled: !!partnerId,
  });
}

export function useCreditReview() {
  return useQuery({
    queryKey: ['sales', 'credit-review'],
    queryFn: async () => (await api.get<Array<CreditExposure & { partnerName: string }>>('/credit/review-queue')).data,
  });
}

export function useCreditDecisions(params: Record<string, string | undefined>) {
  return useQuery({
    queryKey: ['sales', 'credit-decisions', params],
    queryFn: async () => (await api.get<CreditDecision[]>('/credit/decisions', { params: clean(params) })).data,
  });
}

export function useCreditLimits() {
  return useQuery({ queryKey: ['sales', 'credit-limits'], queryFn: async () => (await api.get<CreditLimit[]>('/credit/limits')).data });
}

export function useCreditPolicy() {
  return useQuery({ queryKey: ['sales', 'credit-policy'], queryFn: async () => (await api.get<Record<string, unknown>>('/credit/policy')).data });
}

// ---- ATP / reservations (phase S4) ----

export interface LineAtp {
  lineId?: string;
  productId: string;
  quantity: number;
  availableNow: number;
  canPromiseToday: boolean;
  fullFillLocation: string | null;
  bestLocation: string | null;
  earliestDate: string | null;
  shortage: number;
  reserved?: number;
  promisedDate?: string | null;
  promiseAtRisk?: boolean;
  locations: Array<{ locationId: string; name: string; onHand: number; reserved: number; available: number }>;
  supply: Array<{ type: string; number: string; expectedDate: string | null; available: number }>;
  split: Array<{ locationId: string; name: string; quantity: number }>;
  pegs?: Array<{ supplyType: string; supplyNumber: string; quantity: number; expectedDate: string | null }>;
}

export interface BackorderRow {
  orderId: string; number: string; priority: number; partnerName: string; lineId: string; description: string;
  open: number; reserved: number; backorder: number; promisedDate: string | null; promiseAtRisk: boolean; nextSupply: string | null;
}

export function useOrderAtp(orderId: string | undefined, enabled: boolean) {
  return useQuery({
    queryKey: ['sales', 'atp', orderId],
    queryFn: async () => (await api.get<{ lines: LineAtp[]; production: { connected: boolean } }>(`/sales/orders/${orderId}/atp`)).data,
    enabled: !!orderId && enabled,
  });
}

export function useBackorders() {
  return useQuery({ queryKey: ['sales', 'backorders'], queryFn: async () => (await api.get<BackorderRow[]>('/sales/reports/backorders')).data });
}

export const atpApi = {
  reserve: async (orderId: string) => (await api.post<{ reserved: number; backorder: number }>(`/sales/orders/${orderId}/reserve`, {})).data,
};

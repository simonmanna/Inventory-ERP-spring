import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from '@/lib/api';
import { idempotentPost } from '@/lib/idempotent-request';

/**
 * ADR-PROCUREMENT-001 — Source-to-Pay: purchasing policies, supplier lifecycle (qualification, dual-control bank
 * accounts, certificates, catalogue, price history) and eligibility. Typed hooks over `/procurement/*` extension
 * routes; the Nest-parity pages keep their own inline calls.
 */

export const errMsg = (e: any): string => {
  const m = e?.response?.data?.message;
  return Array.isArray(m) ? m.join(', ') : (m ?? e?.message ?? 'Request failed');
};

// ------------------------------------------------------------------ policies

export type PolicyKind = 'enum' | 'number' | 'optional_number' | 'string_list' | 'boolean';
export type PolicyStatus = 'draft' | 'approved' | 'active' | 'superseded' | 'cancelled';
export type PolicyScope = 'org' | 'branch' | 'category' | 'department' | 'cost_center' | 'project';

export interface PolicyDefinition {
  key: string;
  group: string;
  label: string;
  description: string;
  kind: PolicyKind;
  default: unknown;
  allowed: string[] | null;
  min: number | null;
  max: number | null;
  effective: { value: unknown; source: 'default' | 'policy'; policyId: string | null };
}

export interface Policy {
  id: string;
  key: string;
  scopeType: PolicyScope;
  scopeId: string | null;
  version: number;
  status: PolicyStatus;
  effectiveFrom: string;
  value: unknown;
  notes: string | null;
  createdBy: string | null;
  approvedBy: string | null;
  approvedAt: string | null;
  activatedAt: string | null;
}

export function usePolicyDefinitions() {
  return useQuery({
    queryKey: ['procurement-policies', 'definitions'],
    queryFn: async () => (await api.get<PolicyDefinition[]>('/procurement/policies/definitions')).data,
  });
}

export function usePolicies(key?: string) {
  return useQuery({
    queryKey: ['procurement-policies', 'list', key ?? ''],
    queryFn: async () => (await api.get<Policy[]>('/procurement/policies', { params: { key: key || undefined } })).data,
  });
}

export function useCreatePolicy() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (body: { key: string; scopeType: PolicyScope; scopeId?: string; effectiveFrom?: string; value: unknown; notes?: string }) =>
      (await api.post<Policy>('/procurement/policies', body)).data,
    onSuccess: () => qc.invalidateQueries({ queryKey: ['procurement-policies'] }),
  });
}

export function usePolicyAction() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async ({ id, action }: { id: string; action: 'approve' | 'activate' | 'cancel' }) =>
      await idempotentPost<Policy>(`/procurement/policies/${id}/${action}`),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['procurement-policies'] }),
  });
}

// ------------------------------------------------------------------ suppliers

export type SupplierStatus = 'prospect' | 'provisional' | 'approved' | 'suspended' | 'blacklisted';

export interface SupplierRow {
  id: string;
  code: string | null;
  name: string;
  email: string | null;
  phone: string | null;
  taxNumber: string | null;
  lifecycleStatus: SupplierStatus | null;
  riskClass: 'low' | 'medium' | 'high' | null;
  categories: string[] | null;
  leadTimeDays: number | null;
  certificatesExpiring: number;
}

export interface Paged<T> {
  data: T[];
  meta: { page: number; pageSize: number; total: number; totalPages: number };
}

export interface BankAccount {
  id: string;
  bankName: string;
  branchName: string | null;
  accountName: string;
  accountNumberMasked: string;
  swiftCode: string | null;
  currencyCode: string | null;
  isDefault: boolean;
  status: 'pending_verification' | 'verified' | 'revoked';
  createdBy: string | null;
  verifiedAt: string | null;
}

export interface Certificate {
  id: string;
  type: string;
  number: string | null;
  issuer: string | null;
  validFrom: string | null;
  validTo: string | null;
  partnerName?: string;
  partnerId: string;
}

export interface SupplierProduct {
  id: string;
  productId: string;
  productCode: string | null;
  productName: string | null;
  supplierSku: string | null;
  minOrderQty: string | null;
  leadTimeDays: number | null;
  preferred: boolean;
}

export interface Eligibility {
  partnerId: string;
  lifecycleStatus: SupplierStatus | null;
  eligible: boolean;
  blocked: string[];
  warnings: string[];
}

export interface SupplierDetail {
  partner: { id: string; code: string | null; name: string; email: string | null; phone: string | null; taxNumber: string | null; website: string | null };
  profile: null | {
    lifecycleStatus: SupplierStatus;
    riskClass: 'low' | 'medium' | 'high';
    categories: string[];
    currencyCode: string | null;
    leadTimeDays: number | null;
    registrationNumber: string | null;
    tradingName: string | null;
    statusReason: string | null;
    notes: string | null;
    approvedAt: string | null;
  };
  bankAccounts: BankAccount[];
  certificates: Certificate[];
  products: SupplierProduct[];
  eligibility: Eligibility;
}

export function useSuppliers(params: { search?: string; status?: string; page?: number; pageSize?: number }) {
  return useQuery({
    queryKey: ['procurement-suppliers', params],
    queryFn: async () => (await api.get<Paged<SupplierRow>>('/procurement/suppliers', { params })).data,
  });
}

export function useSupplier(partnerId?: string) {
  return useQuery({
    queryKey: ['procurement-suppliers', 'detail', partnerId],
    enabled: !!partnerId,
    queryFn: async () => (await api.get<SupplierDetail>(`/procurement/suppliers/${partnerId}`)).data,
  });
}

export function useExpiringCertificates(days = 30) {
  return useQuery({
    queryKey: ['procurement-suppliers', 'expiring', days],
    queryFn: async () => (await api.get<Certificate[]>('/procurement/suppliers/certificates/expiring', { params: { days } })).data,
  });
}

export function usePriceHistory(productId?: string) {
  return useQuery({
    queryKey: ['procurement-suppliers', 'prices', productId],
    enabled: !!productId,
    queryFn: async () =>
      (await api.get<{ history: any[]; suppliers: Array<{ partnerId: string; partnerName: string; currencyCode: string; observations: number; minPrice: string; maxPrice: string; avgPrice: string; lastPrice: string; lastDate: string }> }>(
        '/procurement/suppliers/price-history', { params: { productId } })).data,
  });
}

/** Any supplier write; every call invalidates the supplier caches. */
export function useSupplierMutation<TBody = unknown>(fn: (body: TBody) => Promise<unknown>) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: fn,
    onSuccess: () => qc.invalidateQueries({ queryKey: ['procurement-suppliers'] }),
  });
}

export const supplierApi = {
  saveProfile: (partnerId: string, body: Record<string, unknown>) => api.put(`/procurement/suppliers/${partnerId}/profile`, body),
  transition: (partnerId: string, action: string, reason?: string) =>
    idempotentPost(`/procurement/suppliers/${partnerId}/profile/${action}`, { reason }),
  addBankAccount: (partnerId: string, body: Record<string, unknown>) => api.post(`/procurement/suppliers/${partnerId}/bank-accounts`, body),
  verifyBankAccount: (id: string) => idempotentPost(`/procurement/suppliers/bank-accounts/${id}/verify`),
  revokeBankAccount: (id: string) => idempotentPost(`/procurement/suppliers/bank-accounts/${id}/revoke`),
  addCertificate: (partnerId: string, body: Record<string, unknown>) => api.post(`/procurement/suppliers/${partnerId}/certificates`, body),
  removeCertificate: (id: string) => api.delete(`/procurement/suppliers/certificates/${id}`),
  saveProduct: (partnerId: string, body: Record<string, unknown>) => api.put(`/procurement/suppliers/${partnerId}/products`, body),
  removeProduct: (id: string) => api.delete(`/procurement/suppliers/products/${id}`),
};

export const SUPPLIER_STATUS_TONE: Record<SupplierStatus | 'none', 'neutral' | 'info' | 'success' | 'warning' | 'danger'> = {
  none: 'neutral',
  prospect: 'neutral',
  provisional: 'info',
  approved: 'success',
  suspended: 'warning',
  blacklisted: 'danger',
};

// ------------------------------------------------------------------ requisition → order (wave 3)

export interface SupplierOption { id: string; code: string | null; name: string; lifecycleStatus: SupplierStatus | null }

export function useSupplierOptions(search?: string) {
  return useQuery({
    queryKey: ['procurement-suppliers', 'options', search ?? ''],
    queryFn: async () => (await api.get<SupplierOption[]>('/procurement/suppliers/options', { params: { search: search || undefined } })).data,
    staleTime: 60_000,
  });
}

export interface OpenRequestLine {
  id: string;
  purchaseRequestId: string;
  requestNumber: string;
  branchId: string | null;
  productId: string | null;
  productCode: string | null;
  productName: string | null;
  description: string;
  quantity: string;
  orderedQuantity: string;
  openQuantity: string;
  unitOfMeasureId: string | null;
  estimatedUnitPrice: string | null;
  requiredDate: string | null;
  neededBy: string | null;
  demandSource: string;
  suggestedSupplierId: string | null;
  suggestedSupplier: { id: string; name: string } | null;
  lastPrice: { unitPrice: string; currencyCode: string; validFrom: string; partnerName: string } | null;
}

export function useOpenRequestLines(requestId?: string) {
  return useQuery({
    queryKey: ['sourcing', 'open-lines', requestId ?? ''],
    queryFn: async () => (await api.get<OpenRequestLine[]>('/procurement/sourcing/open-lines', { params: { requestId: requestId || undefined } })).data,
  });
}

export interface OrderFromRequestBody {
  partnerId: string;
  warehouseId: string;
  branchId?: string;
  paymentType?: 'cash' | 'credit';
  currencyCode?: string;
  expectedDeliveryDate?: string;
  notes?: string;
  lines: Array<{ requestLineId: string; quantity?: number; unitPrice?: number; taxRate?: number }>;
}

export function useOrderFromRequests() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (body: OrderFromRequestBody) => idempotentPost<{ id: string; orderNumber: string; status: string; warnings?: string[] }>('/procurement/sourcing/orders', body),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['sourcing'] });
      qc.invalidateQueries({ queryKey: ['purchase-orders'] });
      qc.invalidateQueries({ queryKey: ['purchase-requests'] });
    },
  });
}

export interface PoLifecycle {
  id: string;
  orderNumber: string;
  status: string;
  stage: 'awaiting_approval' | 'approved' | 'sent' | 'confirmed' | 'partially_received' | 'received' | 'closed' | 'cancelled' | string;
  partnerId: string | null;
  partnerName: string | null;
  orderDate: string;
  expectedDeliveryDate: string | null;
  promisedDate: string | null;
  sentAt: string | null;
  acknowledgedAt: string | null;
  supplierReference: string | null;
  approvedAt: string | null;
  closedAt: string | null;
  closeReason: string | null;
  requestId: string | null;
  currencyCode: string;
  totalAmount: string;
  totalPaid: string;
  isEmergency?: boolean;
  emergencyReason?: string | null;
  emergencyReviewedAt?: string | null;
  lines: Array<{ id: string; description: string; quantity: string; receivedQuantity: string; billedQuantity: string; cancelledQuantity: string; openQuantity: string; unitPrice: string }>;
  receipts: Array<{ id: string; receiptNumber: string; status: string; receivedAt: string }>;
  bills: Array<{ id: string; documentNumber: string; status: string; totalAmount: string; amountResidual: string }>;
  approvals: Array<{ id: string; status: string; createdAt: string; decidedAt: string | null }>;
}

export function usePoLifecycle(id?: string) {
  return useQuery({
    queryKey: ['purchase-order', id, 'lifecycle'],
    enabled: !!id,
    queryFn: async () => (await api.get<PoLifecycle>(`/procurement/purchase-orders/${id}/lifecycle`)).data,
  });
}

export function usePoLifecycleAction(id?: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async ({ action, body }: { action: 'send' | 'acknowledge' | 'short-close' | 'close'; body?: Record<string, unknown> }) =>
      idempotentPost<PoLifecycle>(`/procurement/purchase-orders/${id}/${action}`, body ?? {}),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['purchase-order', id] });
      qc.invalidateQueries({ queryKey: ['purchase-orders'] });
    },
  });
}

export interface OpenOrderLine {
  purchaseOrderId: string;
  orderNumber: string;
  partnerId: string;
  partnerName: string;
  lineId: string;
  description: string;
  quantity: string;
  receivedQuantity: string;
  openQuantity: string;
  unitPrice: string;
  openValue: string;
  currencyCode: string;
  dueDate: string | null;
  daysLate: number | null;
  sentAt: string | null;
  acknowledgedAt: string | null;
}

export function useOpenOrders(params: { partnerId?: string; overdueOnly?: boolean }) {
  return useQuery({
    queryKey: ['procurement-reports', 'open-orders', params],
    queryFn: async () => (await api.get<OpenOrderLine[]>('/procurement/reports/open-orders', { params })).data,
  });
}

// ------------------------------------------------------------------ strategic sourcing (wave 4)

export type RfqStatus = 'draft' | 'sent' | 'closed' | 'awarded' | 'cancelled';

export interface RfqRow { id: string; rfqNumber: string; title: string; status: RfqStatus; deadline: string | null; createdAt: string; lines: number; invited: number; responded: number }

export interface Rfq {
  id: string;
  rfqNumber: string;
  title: string;
  status: RfqStatus;
  deadline: string | null;
  weights: Record<string, number>;
  notes: string | null;
  lines: Array<{ id: string; lineNumber: number; productId: string | null; description: string; quantity: string; requiredDate: string | null; requestLineId: string | null }>;
  suppliers: Array<{ partnerId: string; partnerName: string; status: 'invited' | 'responded' | 'declined'; lastRevision: number | null; declineReason: string | null }>;
  awards: Array<{ id: string; rfqLineId: string; partnerId: string; partnerName: string; quantity: string; unitPrice: string; lowestPrice: boolean; justification: string | null; purchaseOrderId: string | null; orderNumber: string | null }>;
}

export interface RfqComparison {
  functionalCurrency: string;
  weights: Record<string, number>;
  recommendedPartnerId: string | null;
  lines: Array<Rfq['lines'][number] & {
    bestPartnerId: string | null;
    offers: Array<{ quotationId: string; partnerId: string; unitPrice: string; quantity: string; deliveredUnitCost: string; deliveredTotal: string; leadTimeDays: number | null; lowest: boolean; alternative: string | null }>;
  }>;
  suppliers: Array<{ quotationId: string; partnerId: string; partnerName: string; revision: number; currencyCode: string; deliveredTotal: string; leadTimeDays: number; paymentTermsDays: number | null; coveragePct: number; weightedScore: number; scores: Record<string, number> }>;
}

export function useRfqs(status?: string) {
  return useQuery({ queryKey: ['rfqs', status ?? ''], queryFn: async () => (await api.get<RfqRow[]>('/procurement/rfqs', { params: { status: status || undefined } })).data });
}

export function useRfq(id?: string) {
  return useQuery({ queryKey: ['rfqs', 'detail', id], enabled: !!id, queryFn: async () => (await api.get<Rfq>(`/procurement/rfqs/${id}`)).data });
}

export function useRfqComparison(id?: string, enabled = true) {
  return useQuery({ queryKey: ['rfqs', 'comparison', id], enabled: !!id && enabled, queryFn: async () => (await api.get<RfqComparison>(`/procurement/rfqs/${id}/comparison`)).data });
}

/** Any RFQ write; invalidates RFQ, workbench and order caches. */
export function useRfqMutation<TBody = unknown>(fn: (body: TBody) => Promise<unknown>) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: fn,
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['rfqs'] });
      qc.invalidateQueries({ queryKey: ['sourcing'] });
      qc.invalidateQueries({ queryKey: ['purchase-orders'] });
    },
  });
}

export const rfqApi = {
  create: (body: Record<string, unknown>) => api.post<Rfq>('/procurement/rfqs', body).then((r) => r.data),
  addSupplier: (id: string, partnerId: string) => api.post(`/procurement/rfqs/${id}/suppliers`, { partnerId }),
  removeSupplier: (id: string, partnerId: string) => api.delete(`/procurement/rfqs/${id}/suppliers/${partnerId}`),
  action: (id: string, action: 'send' | 'close' | 'cancel') => api.post(`/procurement/rfqs/${id}/${action}`),
  quote: (id: string, body: Record<string, unknown>) => idempotentPost(`/procurement/rfqs/${id}/quotations`, body),
  decline: (id: string, partnerId: string, reason?: string) => api.post(`/procurement/rfqs/${id}/decline`, { partnerId, reason }),
  award: (id: string, lines: Array<{ rfqLineId: string; partnerId: string; quantity?: number; justification?: string }>) =>
    idempotentPost<Rfq & { approvalRequired?: boolean }>(`/procurement/rfqs/${id}/award`, { lines }),
  orders: (id: string, body: { warehouseId: string; paymentType?: string; expectedDeliveryDate?: string }) =>
    idempotentPost<{ orders: Array<{ id: string; orderNumber: string; partnerId: string }> }>(`/procurement/rfqs/${id}/orders`, body),
};

export interface Agreement {
  id: string;
  agreementNumber: string;
  partnerId: string;
  partnerName?: string;
  partner?: { id: string; name: string };
  type: 'blanket' | 'rate';
  title: string;
  status: 'draft' | 'active' | 'on_hold' | 'exhausted' | 'expired' | 'terminated';
  validFrom: string;
  validTo: string;
  currencyCode: string;
  committedValue: string | null;
  releasedValue: string;
  statusReason: string | null;
  createdBy: string | null;
  lines?: Array<{ id: string; description: string; productId: string | null; unitPrice: string; committedQuantity: string | null; releasedQuantity: string; remainingQuantity: string | null }>;
  releases?: Array<{ id: string; orderNumber: string; status: string; orderDate: string; totalAmount: string }>;
}

export function useAgreements(status?: string) {
  return useQuery({ queryKey: ['agreements', status ?? ''], queryFn: async () => (await api.get<Agreement[]>('/procurement/agreements', { params: { status: status || undefined } })).data });
}

export function useAgreement(id?: string) {
  return useQuery({ queryKey: ['agreements', 'detail', id], enabled: !!id, queryFn: async () => (await api.get<Agreement>(`/procurement/agreements/${id}`)).data });
}

export function useAgreementMutation<TBody = unknown>(fn: (body: TBody) => Promise<unknown>) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: fn,
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['agreements'] });
      qc.invalidateQueries({ queryKey: ['purchase-orders'] });
    },
  });
}

export const agreementApi = {
  create: (body: Record<string, unknown>) => api.post<Agreement>('/procurement/agreements', body).then((r) => r.data),
  action: (id: string, action: 'activate' | 'hold' | 'resume' | 'terminate', reason?: string) => api.post(`/procurement/agreements/${id}/${action}`, { reason }),
  release: (id: string, body: { warehouseId: string; expectedDeliveryDate?: string; lines: Array<{ agreementLineId: string; quantity: number }> }) =>
    idempotentPost<{ id: string; orderNumber: string }>(`/procurement/agreements/${id}/releases`, body),
};

// ------------------------------------------------------------------ wave 5: controls

export type ChangeStatus = 'pending' | 'approved' | 'applied' | 'rejected' | 'cancelled';

export interface PoChange {
  id: string;
  purchaseOrderId: string;
  changeNumber: number;
  status: ChangeStatus;
  reason: string;
  changes: { expectedDeliveryDate: string | null; lines: Array<{ lineId: string; quantity: string | null; unitPrice: string | null }> };
  oldTotal: string;
  newTotal: string;
  needsApproval: boolean;
  requestedBy: string | null;
  decidedBy: string | null;
  decisionNote: string | null;
  createdAt: string;
  orderNumber?: string;
  partnerName?: string;
}

export interface PoRevisionSnapshot {
  orderNumber: string;
  expectedDeliveryDate: string | null;
  totalAmount: string;
  lines: Array<{ id: string; description: string; quantity: string; unitPrice: string; subtotal: string }>;
}

export interface PoChangeHistory {
  purchaseOrderId: string;
  revision: number;
  changes: PoChange[];
  revisions: Array<{ id: string; revision: number; changeRequestId: string | null; createdAt: string; snapshot: PoRevisionSnapshot }>;
}

export function usePoChanges(poId?: string) {
  return useQuery({ queryKey: ['po-changes', poId], enabled: !!poId, queryFn: async () => (await api.get<PoChangeHistory>(`/procurement/purchase-orders/${poId}/changes`)).data });
}

export function usePendingPoChanges(enabled = true) {
  return useQuery({ queryKey: ['po-changes', 'pending'], enabled, queryFn: async () => (await api.get<PoChange[]>('/procurement/po-changes/pending')).data });
}

export function usePoChangeMutation<TBody = unknown>(fn: (body: TBody) => Promise<unknown>) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: fn,
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['po-changes'] });
      qc.invalidateQueries({ queryKey: ['purchase-orders'] });
      qc.invalidateQueries({ queryKey: ['purchase-order'] });
    },
  });
}

export const poChangeApi = {
  request: (poId: string, body: { reason: string; expectedDeliveryDate?: string; lines?: Array<{ lineId: string; quantity?: number; unitPrice?: number }> }) =>
    idempotentPost<PoChange>(`/procurement/purchase-orders/${poId}/changes`, body),
  approve: (id: string, note?: string) => idempotentPost<PoChange>(`/procurement/po-changes/${id}/approve`, { note }),
  reject: (id: string, note: string) => api.post<PoChange>(`/procurement/po-changes/${id}/reject`, { note }).then((r) => r.data),
  cancel: (id: string) => api.post<PoChange>(`/procurement/po-changes/${id}/cancel`).then((r) => r.data),
};

export type ClaimStatus = 'open' | 'submitted' | 'accepted' | 'rejected' | 'settled' | 'cancelled';
export type ClaimType = 'shortage' | 'damage' | 'quality' | 'price' | 'late_delivery' | 'other';

export interface SupplierClaim {
  id: string;
  claimNumber: string;
  partnerId: string;
  partnerName: string | null;
  purchaseOrderId: string | null;
  orderNumber: string | null;
  goodsReceiptId: string | null;
  receiptNumber: string | null;
  vendorBillId: string | null;
  billNumber: string | null;
  type: ClaimType;
  status: ClaimStatus;
  amount: string;
  settledAmount: string;
  description: string;
  supplierResponse: string | null;
  debitNoteId: string | null;
  debitNoteNumber: string | null;
  debitNoteStatus: string | null;
  createdAt: string;
}

export const CLAIM_STATUS_TONE: Record<ClaimStatus, 'neutral' | 'info' | 'success' | 'warning' | 'danger'> = {
  open: 'warning',
  submitted: 'info',
  accepted: 'info',
  rejected: 'danger',
  settled: 'success',
  cancelled: 'neutral',
};

export function useClaims(params: { status?: string; partnerId?: string; purchaseOrderId?: string; page?: number }) {
  return useQuery({
    queryKey: ['supplier-claims', params],
    queryFn: async () => (await api.get<{ data: SupplierClaim[]; meta: { total: number; page: number; pageSize: number } }>('/procurement/claims', {
      params: { status: params.status || undefined, partnerId: params.partnerId || undefined, purchaseOrderId: params.purchaseOrderId || undefined, page: params.page ?? 1 },
    })).data,
  });
}

export function useClaimMutation<TBody = unknown>(fn: (body: TBody) => Promise<unknown>) {
  const qc = useQueryClient();
  return useMutation({ mutationFn: fn, onSuccess: () => qc.invalidateQueries({ queryKey: ['supplier-claims'] }) });
}

export const claimApi = {
  create: (body: { partnerId: string; type: ClaimType; amount: number; description: string; purchaseOrderId?: string; goodsReceiptId?: string; vendorBillId?: string }) =>
    api.post<SupplierClaim>('/procurement/claims', body).then((r) => r.data),
  action: (id: string, action: 'submit' | 'cancel') => api.post<SupplierClaim>(`/procurement/claims/${id}/${action}`).then((r) => r.data),
  accept: (id: string, amount?: number, response?: string) => api.post<SupplierClaim>(`/procurement/claims/${id}/accept`, { amount, response }).then((r) => r.data),
  reject: (id: string, response: string) => api.post<SupplierClaim>(`/procurement/claims/${id}/reject`, { response }).then((r) => r.data),
  settle: (id: string) => idempotentPost<SupplierClaim>(`/procurement/claims/${id}/settle`, {}),
};

export interface CommitmentRow {
  id: string;
  sourceType: string;
  sourceId: string;
  sourceNumber: string | null;
  accountId: string;
  accountCode: string | null;
  accountName: string | null;
  branchId: string | null;
  period: string;
  amount: string;
  openAmount: string;
  status: 'open' | 'closed';
  updatedAt: string;
}

export function useCommitments(params: { status?: string; page?: number }) {
  return useQuery({
    queryKey: ['commitments', params],
    queryFn: async () => (await api.get<{ data: CommitmentRow[]; meta: { total: number; page: number; pageSize: number } }>('/procurement/commitments', {
      params: { status: params.status || undefined, page: params.page ?? 1 },
    })).data,
  });
}

// ------------------------------------------------------------------ wave 6: receiving quality, emergencies

export type InspectionStatus = 'pending' | 'passed' | 'failed' | 'partial' | 'cancelled';

export interface Inspection {
  id: string;
  inspectionNumber: string;
  goodsReceiptId: string;
  receiptNumber: string | null;
  purchaseOrderId: string | null;
  orderNumber: string | null;
  partnerName: string | null;
  status: InspectionStatus;
  notes: string | null;
  fileIds: string[];
  claimId: string | null;
  claimNumber: string | null;
  createdAt: string;
  completedAt: string | null;
  lines?: Array<{ id: string; description: string; receivedQuantity: string; acceptedQuantity: string | null; rejectedQuantity: string; unitPrice: string; result: 'pass' | 'fail' | 'partial' | null; defectNotes: string | null }>;
}

export function useInspections(status?: string) {
  return useQuery({ queryKey: ['inspections', status ?? ''], queryFn: async () => (await api.get<Inspection[]>('/procurement/inspections', { params: { status: status || undefined } })).data });
}

export function useInspection(id?: string) {
  return useQuery({ queryKey: ['inspections', 'detail', id], enabled: !!id, queryFn: async () => (await api.get<Inspection>(`/procurement/inspections/${id}`)).data });
}

export function useInspectionMutation<TBody = unknown>(fn: (body: TBody) => Promise<unknown>) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: fn,
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['inspections'] });
      qc.invalidateQueries({ queryKey: ['supplier-claims'] });
    },
  });
}

export const inspectionApi = {
  open: (goodsReceiptId: string) => api.post<Inspection>(`/procurement/goods-receipts/${goodsReceiptId}/inspection`).then((r) => r.data),
  record: (id: string, body: { lines: Array<{ lineId: string; acceptedQuantity?: number; rejectedQuantity?: number; defectNotes?: string }>; notes?: string; fileIds?: string[] }) =>
    api.post<Inspection>(`/procurement/inspections/${id}/results`, body).then((r) => r.data),
  complete: (id: string) => idempotentPost<Inspection>(`/procurement/inspections/${id}/complete`, {}),
  cancel: (id: string) => api.post<Inspection>(`/procurement/inspections/${id}/cancel`).then((r) => r.data),
};

export interface EmergencyRow {
  id: string;
  orderNumber: string;
  status: string;
  orderDate: string;
  totalAmount: string;
  currencyCode: string;
  emergencyReason: string;
  emergencyReviewedAt: string | null;
  emergencyReviewNote: string | null;
  partnerName: string | null;
}

export function useEmergencyReport(reviewed?: boolean) {
  return useQuery({
    queryKey: ['emergency-purchases', reviewed ?? 'all'],
    queryFn: async () => (await api.get<EmergencyRow[]>('/procurement/reports/emergency', { params: { reviewed } })).data,
  });
}

export const emergencyApi = {
  mark: (poId: string, reason: string) => api.post(`/procurement/purchase-orders/${poId}/emergency`, { reason }).then((r) => r.data),
  review: (poId: string, note: string) => api.post(`/procurement/purchase-orders/${poId}/emergency-review`, { note }).then((r) => r.data),
};

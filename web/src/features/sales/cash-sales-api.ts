import { useQuery } from '@tanstack/react-query';
import { api } from '@/lib/api';
import { tx } from '@/lib/i18n/tx';

/** Branch cash sales → head-office invoicing (ADR-S056, docs/spec/cash-sales.md). */
export type ExecutionStatus = 'draft' | 'confirmed' | 'voided' | 'cancelled';
export type ProcessingStatus = 'not_submitted' | 'submitted' | 'under_review' | 'returned_for_correction' | 'approved' | 'invoiced' | 'rejected';
export type DeliveryStatus = 'pending' | 'scheduled' | 'dispatched' | 'delivered' | 'failed';
export type FiscalStatus = 'none' | 'pending' | 'issued' | 'failed' | 'cancellation_pending' | 'cancelled';

export interface CashSaleLine {
  id: string; productId: string; productName: string | null; productCode: string | null; description: string;
  /** ADR-S058: product variant. */
  variantId?: string | null; variantName?: string | null;
  quantity: number; uomCode: string | null; unitPrice: number; discountPercent: number; taxId: string | null; taxName: string | null;
  lineSubtotal: number; lineTax: number; lineTotal: number; costSnapshot: number | null; trackInventory: boolean;
  deliveredQty?: number; invoicedQty?: number;
}
export interface CashSalePayment {
  id: string; method: 'cash' | 'mobile_money' | 'bank'; provider: string | null; amount: number; reference: string | null;
  paymentModeId: string | null; paymentModeLabel: string | null;
  accountCode: string | null; accountName: string | null; collectedByName: string | null; collectedAt: string;
  verificationStatus: 'pending' | 'verified' | 'failed'; verifiedByName: string | null; verifiedAt: string | null;
  verificationSource: string | null; settlementStatus: string;
}
export interface CashSale {
  id: string; number: string; branchId: string; branchName: string | null; locationName: string | null; partnerId: string; partnerName: string | null;
  customerName: string | null; customerPhone: string | null; customerTin: string | null; customerAddress: string | null;
  salespersonId: string | null; salespersonName: string | null; saleDate: string; currencyCode: string;
  subtotal: number; discountTotal: number; taxTotal: number; grandTotal: number; paidTotal: number;
  executionStatus: ExecutionStatus; processingStatus: ProcessingStatus; fiscalStatus: FiscalStatus;
  confirmedAt: string | null; submittedAt: string | null; approvedAt: string | null; invoicedAt: string | null; rejectedAt: string | null;
  rejectionReason: string | null; ownerName: string | null; voidReason: string | null; remarks: string | null;
  currentInvoiceId: string | null; invoiceNumber: string | null; createdAt: string; updatedAt: string;
  deliveryMethod?: 'pickup' | 'delivery'; deliveryStatus?: DeliveryStatus; deliveryAddress?: string | null;
  deliveryContactName?: string | null; deliveryContactPhone?: string | null; deliveryScheduledDate?: string | null;
  deliveryDriver?: string | null; deliveryVehicle?: string | null; deliveryReceivedBy?: string | null; deliveryNotes?: string | null;
  deliveryLandmark?: string | null; deliveryLoadingSite?: string | null; deliveryScope?: 'full' | 'partial' | null;
  deliveryOffloadingEquipment?: 'yes' | 'no' | null;
  deliveredAt?: string | null; deliveredByName?: string | null;
  lines?: CashSaleLine[]; payments?: CashSalePayment[]; actions?: string[];
  invoices?: Array<{ id: string; attempt: number; invoiceId: string; documentNumber: string; status: 'active' | 'cancelled'; cancelReason: string | null; createdAt: string }>;
  deposits?: Array<{ id: string; number: string; amount: number; appliedAmount: number; refundedAmount: number; status: string; source: string }>;
  fiscalDocuments?: Array<{ id: string; operation: string; provider: string; requestRef: string; status: string; attempts: number; lastError: string | null; fiscalNumber: string | null; createdAt: string }>;
  ageDays?: number; overdue?: boolean;
}
export interface TimelineEvent {
  id: string; action: string; fromProcessing: string | null; toProcessing: string | null; fromExecution: string | null; toExecution: string | null;
  reason: string | null; field: string | null; oldValue: string | null; newValue: string | null; userName: string | null; createdAt: string;
}
export interface Dashboard {
  scope: 'branch' | 'head_office';
  tiles: { total: number; drafts: number; pending: number; awaitingInvoice: number; invoiced: number; returned: number; rejected: number; confirmedTotal: number; uninvoicedTotal: number };
  recent: CashSale[]; exceptions: CashSale[];
  byBranch?: Array<{ branchId: string; branchName: string; count: number; total: number; pending: number }>;
  bySalesperson?: Array<{ salespersonId: string; name: string | null; count: number; total: number }>;
  processing?: { avgHoursToInvoice: number; invoiced: number };
}
export interface BranchProfile {
  id: string; branchId: string; branchName: string | null; locationId: string; locationName: string | null;
  cashAccountId: string | null; momoMtnAccountId: string | null; momoAirtelAccountId: string | null; bankClearingAccountId: string | null;
  walkInPartnerId: string | null; walkInPartnerName: string | null; numberPrefix: string; requireSegregation: boolean;
  fiscalTrigger: 'at_confirm' | 'at_invoice' | null; fiscalProvider: string; rejectedAlertDays: number; isActive: boolean;
}

/** An organization payment mode a cash sale can take (cash / mobile money / bank); it decides the receiving account. */
export interface PaymentMode {
  id: string; code: string; label: string; kind: 'cash' | 'mobile_money' | 'bank'; provider: string | null;
  accountId: string | null; accountName: string | null; accountCode: string | null; requiresReference: boolean;
}

export const EXECUTION_LABEL: Record<ExecutionStatus, string> = {
  draft: tx('Draft'), confirmed: tx('Confirmed'), voided: tx('Voided'), cancelled: tx('Discarded'),
};
export const PROCESSING_LABEL: Record<ProcessingStatus, string> = {
  not_submitted: tx('Not submitted'), submitted: tx('Submitted'), under_review: tx('Under review'),
  returned_for_correction: tx('Returned for correction'), approved: tx('Approved'), invoiced: tx('Invoiced'), rejected: tx('Rejected'),
};
export const FISCAL_LABEL: Record<FiscalStatus, string> = {
  none: tx('Not fiscalised'), pending: tx('Fiscal pending'), issued: tx('Fiscal issued'), failed: tx('Fiscal failed'),
  cancellation_pending: tx('Fiscal cancel pending'), cancelled: tx('Fiscal cancelled'),
};
export const DELIVERY_STATUS_LABEL: Record<DeliveryStatus, string> = {
  pending: tx('Pending'), scheduled: tx('Scheduled'), dispatched: tx('Dispatched'), delivered: tx('Delivered'), failed: tx('Failed'),
};
export const METHOD_LABEL: Record<string, string> = { cash: tx('Cash'), mobile_money: tx('Mobile money'), bank: tx('Bank') };

export const idem = () => ({ headers: { 'Idempotency-Key': crypto.randomUUID() } });

export function useCashSale(id: string | undefined) {
  return useQuery({
    queryKey: ['cash-sales', id],
    enabled: !!id,
    queryFn: async () => (await api.get<CashSale>(`/sales/cash-sales/${id}`)).data,
  });
}

export function usePaymentModes() {
  return useQuery({
    queryKey: ['cash-sales', 'payment-modes'],
    queryFn: async () => (await api.get<PaymentMode[]>('/sales/cash-sales/payment-modes')).data,
    staleTime: 60_000,
  });
}

export function useBranchProfiles() {
  return useQuery({
    queryKey: ['cash-sales', 'profiles'],
    queryFn: async () => (await api.get<BranchProfile[]>('/sales/branch-profiles')).data,
    staleTime: 60_000,
  });
}

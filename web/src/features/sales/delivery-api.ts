import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { toast } from 'sonner';
import { api } from '@/lib/api';
import { apiErrorMessage } from '@/lib/api-error';
import { tx } from '@/lib/i18n/tx';
import type { LinkedWeighTicket, WeighVerification } from '@/features/fleet/dispatch-api';

/** How one delivery reaches the customer. */
export type DeliveryMethod = 'customer_collect' | 'site_delivery' | 'warehouse_transfer' | 'courier';

/** Stage of one delivery (derived on the server from the trip status and its proof of delivery). */
export type DeliveryStage =
  | 'scheduled' | 'dispatched' | 'in_transit' | 'partially_delivered' | 'delivered' | 'failed' | 'returned' | 'cancelled';

/** Delivery stage of a whole order, derived from its deliveries. */
export type OrderDeliveryStage = 'pending' | 'scheduled' | 'dispatched' | 'partially_delivered' | 'delivered';

export interface DeliveryLine {
  id: string; deliveryOrderLineId: string; orderLineId: string | null; productId: string; productName: string | null;
  uomCode: string | null; plannedQty: number; loadedQty: number | null; dispatchedQty: number | null; deliveredQty: number | null;
  returnedQty: number | null; damagedQty: number; rejectedQty: number; shortageQty: number; batchNumber: string | null;
}

export interface SalesDelivery {
  id: string; code: string; status: string; stage: DeliveryStage; carrier: string;
  vehiclePlate: string | null; driverName: string | null; plannedDate: string | null; scheduledTime: string | null;
  dispatchedAt: string | null; departedAt: string | null; deliveredAt: string | null; cancelledAt: string | null; returnedAt: string | null;
  deliveryNoteNumber: string | null; approvalRequired: boolean; approvedAt: string | null;
  cancelReason: string | null; returnReason: string | null; attemptFailed: boolean; rescheduledFromId: string | null;
  deliveryMethod: DeliveryMethod | null; driverPhone: string | null; vehicleType: string | null; transportCompany: string | null;
  trackingNumber: string | null; destinationLocationId: string | null; destinationLocationName: string | null;
  deliveryAddress: string | null; siteContactName: string | null; siteContactPhone: string | null; route: string | null;
  offloadingEquipment: string | null; expectedArrivalAt: string | null; arrivedAt: string | null;
  transportCost: number | null; tripExpenses: number | null; notes: string | null;
  locationId: string | null; locationName: string | null; createdAt: string;
  orderId: string; orderNumber: string | null; partnerName: string | null;
  plannedQty: number; dispatchedQty: number; deliveredQty: number; damagedQty: number; rejectedQty: number;
  lineCount: number; uomCodes: string | null;
  lines?: DeliveryLine[];
  /** Feeding weighbridge ticket of the truck (Fleet is the source; read-only here). */
  weighTicketId?: string | null; weighTicketNumber?: string | null; weighNetKg?: string | number | null;
  weighTicketStatus?: string | null; weighWithinTolerance?: boolean | null; weighVerification?: WeighVerification | null;
  /** Detail only: every ticket of the truck (void / reissue chain included) and whether the weighbridge is required. */
  weighbridgeTickets?: LinkedWeighTicket[];
  weighbridge?: { required: boolean; feedingTicketId: string | null };
  pod?: { receiverName: string; receiverPhone: string | null; receivedAt: string; notes: string | null; signatureUrl: string | null } | null;
  photos?: Array<{ id: string; fileId: string; caption: string | null; createdAt: string; url: string | null }>;
}

export interface SummaryLine {
  lineId: string; description: string; productId: string | null; productName: string | null; uomCode: string | null;
  ordered: number; unitPrice: number; scheduled: number; dispatched: number; delivered: number; rejected: number;
  damaged: number; shortage: number; returned: number; outstanding: number; toSchedule: number; percentDelivered: number;
  /** Loaded onto a truck (or dispatched) on active / completed deliveries. */
  picked: number;
  /** Invoiced net of credit notes. */
  invoiced: number;
  /** Came back from the customer on a sales return (received). */
  customerReturned: number;
  /** Earliest planned date of a delivery not yet dispatched; last proof-of-delivery time. */
  scheduledDate: string | null; lastDeliveredAt: string | null;
}

/** One return against a document (sales return / RMA, credit note, or a void that brought the goods back). */
export interface ReturnView {
  id: string; kind: 'sales_return' | 'credit_note' | 'void'; number: string; date: string | null; status: string;
  reason?: string | null; total?: number | null; counted?: boolean; outcome?: string | null;
  lines: Array<{ productName: string | null; description?: string | null; quantity: number; receivedQty?: number | null; uomCode: string | null; disposition?: string | null }>;
}

/** A customer return (RMA) of the order. */
export interface OrderReturn {
  id: string; number: string; status: string; reason: string; physicalReturn: boolean; outcome: string | null;
  createdAt: string; receivedAt: string | null; closedAt: string | null; notes: string | null;
  lines: Array<{ orderLineId: string; description: string; quantity: number; receivedQty: number; disposition: string | null; uomCode: string | null }>;
}

export interface UnitTotals {
  uomCode: string | null; ordered: number; scheduled: number; dispatched: number; delivered: number; outstanding: number;
  toSchedule: number; rejected: number; damaged: number; shortage: number;
}

export interface DeliverySummary {
  orderId: string; stage: OrderDeliveryStage; percentDelivered: number; trips: number;
  stages: Partial<Record<DeliveryStage, number>>; totals: UnitTotals[]; lines: SummaryLine[];
  /** Plan deadline, else the order commitment date, else the requested date. */
  deadline: string | null; returns: OrderReturn[];
}

export interface HistoryEntry {
  id: string; action: string; oldValues: Record<string, unknown> | null; newValues: Record<string, unknown> | null;
  createdAt: string; actorName: string | null;
}

/** Order-level delivery plan (the handover of a sales order). */
export type PlanMethod = 'pickup' | 'delivery' | 'transfer' | 'courier';
export interface DeliveryPlan {
  stage: 'plan' | 'ready' | 'closed'; method: PlanMethod; status: string; locationId: string | null; locationName: string | null;
  address: string | null; contactName: string | null; contactPhone: string | null; scheduledDate: string | null;
  notes: string | null; landmark: string | null; loadingSite: string | null; deliveryScope: 'full' | 'partial' | null;
  offloadingEquipment: 'yes' | 'no' | null; gpsCoordinates: string | null; offloadingSite: string | null;
  destinationLocationId: string | null; destinationLocationName: string | null; courierName: string | null;
  deliveredAt: string | null; deliveredByName: string | null; saved: boolean; deadline: string | null;
}

const KEY = ['sales', 'order-delivery'] as const;
export const idem = () => ({ headers: { 'Idempotency-Key': crypto.randomUUID() } });

export function useDeliverySummary(orderId: string) {
  return useQuery({
    queryKey: [...KEY, 'summary', orderId], enabled: !!orderId,
    queryFn: async () => (await api.get<DeliverySummary>(`/sales/orders/${orderId}/delivery-summary`)).data,
  });
}

export function useOrderDeliveries(orderId: string) {
  return useQuery({
    queryKey: [...KEY, 'list', orderId],
    queryFn: async () => (await api.get<SalesDelivery[]>('/sales/deliveries', { params: { orderId } })).data,
  });
}

export function useDelivery(id: string | null) {
  return useQuery({
    queryKey: [...KEY, 'one', id], enabled: !!id,
    queryFn: async () => (await api.get<SalesDelivery>(`/sales/deliveries/${id}`)).data,
  });
}

export function useDeliveryHistory(id: string | null, enabled = true) {
  return useQuery({
    queryKey: [...KEY, 'history', id], enabled: !!id && enabled,
    queryFn: async () => (await api.get<HistoryEntry[]>(`/sales/deliveries/${id}/history`)).data,
  });
}

export function useDeliveryPlan(orderId: string) {
  return useQuery({
    queryKey: ['handover', `/sales/orders/${orderId}/handover`],
    queryFn: async () => (await api.get<DeliveryPlan>(`/sales/orders/${orderId}/handover`)).data,
  });
}

/** A delivery action: runs {@code fn}, refreshes everything delivery / sales related, toasts {@code ok}. */
export function useDeliveryAction<T = unknown>(fn: (v: T) => Promise<unknown>, ok?: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: fn,
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: KEY });
      void qc.invalidateQueries({ queryKey: ['sales'] });
      void qc.invalidateQueries({ queryKey: ['handover'] });
      if (ok) toast.success(ok);
    },
    onError: (e) => toast.error(apiErrorMessage(e, tx('Action failed'))),
  });
}

/** Download (or open for printing) the PDF delivery note of a dispatched delivery. */
export async function openDeliveryNote(d: Pick<SalesDelivery, 'id' | 'code' | 'deliveryNoteNumber'>, mode: 'download' | 'print') {
  try {
    const r = await api.get(`/sales/deliveries/${d.id}/note`, { responseType: 'blob' });
    const url = URL.createObjectURL(r.data as Blob);
    if (mode === 'print') {
      const w = window.open(url, '_blank');
      if (!w) toast.error(tx('Allow pop-ups to print the delivery note'));
      setTimeout(() => URL.revokeObjectURL(url), 60_000);
      return;
    }
    const a = document.createElement('a');
    a.href = url; a.download = `${d.deliveryNoteNumber ?? d.code}.pdf`; a.click();
    URL.revokeObjectURL(url);
  } catch (e) {
    toast.error(apiErrorMessage(e, tx('Could not open the delivery note')));
  }
}

/** Read a picked image as a data URL. */
export function readDataUrl(f: File) {
  return new Promise<string>((res, rej) => { const r = new FileReader(); r.onload = () => res(String(r.result)); r.onerror = rej; r.readAsDataURL(f); });
}

/** Opens a weighbridge ticket of the delivery as a PDF view copy (not counted as a print; audited on the server). */
export async function openWeighTicketCopy(deliveryId: string, ticketId: string) {
  try {
    const r = await api.get(`/sales/deliveries/${deliveryId}/weigh-tickets/${ticketId}/pdf`, { responseType: 'blob' });
    const url = URL.createObjectURL(r.data as Blob);
    const w = window.open(url, '_blank');
    if (!w) toast.error(tx('Allow pop-ups to open the weighbridge ticket'));
    setTimeout(() => URL.revokeObjectURL(url), 60_000);
  } catch (e) {
    toast.error(apiErrorMessage(e, tx('Could not open the weighbridge ticket')));
  }
}

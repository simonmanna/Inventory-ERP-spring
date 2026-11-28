import { useQuery } from '@tanstack/react-query';
import { api } from '@/lib/api';
import { idempotentPost } from '@/lib/idempotent-request';
import { tx } from '@/lib/i18n/tx';
import type {
  FiscalOperation, FiscalOperationStatus, ReversalStage, SaleEvent, SaleFiscalStatus, SalePaymentMode, SalesOrder, SalesOrderLine,
  TenderMethod,
} from './api';

// ADR-S094 C6: the cash Sale on the Sale screens — tenders, confirm with handover, Reverse, the fiscal outbox and the
// timeline. Every route is under /sales/orders/:id; the legacy /sales/cash-sales screens only show older sales.

/** The handover block of a cash confirm (§5.3): the goods leave the counter in full, to a named person. */
export interface HandoverBody {
  affirmed: boolean;
  receivedByName: string;
  receivedByPhone?: string;
  notes?: string;
  lines?: Array<{ lineId: string; serialNumbers: string[] }>;
}

/** A cash sale's Reverse (§7.4): every goods line comes back in full, as the cashier found it. */
export interface ReverseBody {
  reason: string;
  settlement: 'refund' | 'credit';
  returnReason?: string;
  locationId?: string;
  lines: Array<{
    orderLineId: string; quantity: number; serialNumbers?: string[]; condition?: string; disposition?: string;
    locationId?: string; notes?: string;
  }>;
}

/** Settle by hand a fiscal operation whose outcome is unknown, after checking with the provider (§5.5). */
export interface ResolveBody {
  resolution: 'issued' | 'not_received';
  fiscalNumber?: string;
  externalRef?: string;
  note: string;
}

export const saleCashApi = {
  addTender: async ({ id, body }: { id: string; body: Record<string, unknown> }) =>
    (await api.post<SalesOrder>(`/sales/orders/${id}/payments`, body)).data,
  removeTender: async ({ id, paymentId }: { id: string; paymentId: string }) =>
    (await api.delete<SalesOrder>(`/sales/orders/${id}/payments/${paymentId}`)).data,
  verifyTender: async ({ id, paymentId, status }: { id: string; paymentId: string; status: 'verified' | 'failed' }) =>
    (await api.post<SalesOrder>(`/sales/orders/${id}/payments/${paymentId}/verify`, { status })).data,
  /** One transaction: pickup, stock issue, invoice, one receipt per tender and the fiscal request (money route). */
  confirm: ({ id, handover }: { id: string; handover: HandoverBody }) =>
    idempotentPost<SalesOrder>(`/sales/orders/${id}/confirm`, { handover }),
  reverse: ({ id, body }: { id: string; body: ReverseBody }) => idempotentPost<SalesOrder>(`/sales/orders/${id}/reverse`, body),
  retryFiscal: async (id: string) => (await api.post<FiscalOperation[]>(`/sales/orders/${id}/fiscal/retry`, {})).data,
  resolveFiscal: async ({ id, fiscalId, body }: { id: string; fiscalId: string; body: ResolveBody }) =>
    (await api.post<FiscalOperation>(`/sales/orders/${id}/fiscal/${fiscalId}/resolve`, body)).data,
};

export function useSalePaymentModes(enabled = true) {
  return useQuery({
    queryKey: ['sales', 'payment-modes'],
    queryFn: async () => (await api.get<SalePaymentMode[]>('/sales/orders/payment-modes')).data,
    staleTime: 60_000,
    enabled,
  });
}

export function useSaleFiscal(id: string | undefined, enabled = true) {
  return useQuery({
    queryKey: ['sales', 'order', id, 'fiscal'],
    queryFn: async () => (await api.get<FiscalOperation[]>(`/sales/orders/${id}/fiscal`)).data,
    enabled: !!id && enabled,
  });
}

export function useSaleEvents(id: string | undefined, enabled = true) {
  return useQuery({
    queryKey: ['sales', 'order', id, 'events'],
    queryFn: async () => (await api.get<SaleEvent[]>(`/sales/orders/${id}/events`)).data,
    enabled: !!id && enabled,
  });
}

/** A branch's cash-sale settings (`GET /sales/branch-profiles`). */
export interface CashProfile {
  id: string; branchId: string; branchName: string | null; branchCode: string | null; locationId: string | null;
  locationName: string | null; walkInPartnerId: string | null; walkInPartnerName: string | null; fiscalProvider: string;
  isActive: boolean;
}

export function useCashProfiles(enabled = true) {
  return useQuery({
    queryKey: ['sales', 'branch-profiles'],
    queryFn: async () => (await api.get<CashProfile[]>('/sales/branch-profiles')).data,
    staleTime: 300_000,
    retry: false,
    enabled,
  });
}

// ---- Buyer identity of a cash sale ----

/** Who buys at the counter, as printed on the invoice (a walk-in customer is named here). */
export interface CashIdentity { customerName: string; customerPhone: string; customerTin: string; customerAddress: string }
export const blankIdentity: CashIdentity = { customerName: '', customerPhone: '', customerTin: '', customerAddress: '' };
export const identityOf = (o?: SalesOrder | null): CashIdentity => ({
  customerName: o?.customerName ?? '', customerPhone: o?.customerPhone ?? '', customerTin: o?.customerTin ?? '',
  customerAddress: o?.customerAddress ?? '',
});

// ---- Request bodies (pure; unit-tested against the field sets the API accepts) ----

const text = (v?: string | null) => (v ?? '').trim() || undefined;
const money2 = (n: number) => Math.round(n * 100) / 100;

/** Serial numbers typed as a list: commas, semicolons, spaces or new lines. */
export const serialList = (v?: string | null) => (v ?? '').split(/[\s,;]+/).map((x) => x.trim()).filter(Boolean);

/** Change owed for physical cash: what was handed over minus what is applied (never negative). */
export const changeDue = (amount: number, tendered?: number | null) =>
  tendered != null && tendered > amount ? money2(tendered - amount) : 0;

export interface TenderForm {
  /** A configured payment mode; when none is configured the method (and the mobile-money provider) is sent instead. */
  modeId?: string;
  method: TenderMethod;
  provider?: string;
  amount: number;
  /** Physical cash only: the amount the customer handed over. */
  tendered?: number | null;
  reference?: string;
  paymentDate?: string;
  payerName?: string;
  payerAccount?: string;
  notes?: string;
  attachmentIds?: string[];
}

/** Body of `POST /sales/orders/:id/payments`. */
export function tenderBody(f: TenderForm): Record<string, unknown> {
  const body: Record<string, unknown> = f.modeId ? { paymentModeId: f.modeId } : { method: f.method };
  if (!f.modeId && f.method === 'mobile_money' && text(f.provider)) body.provider = text(f.provider);
  body.amount = money2(f.amount);
  if (f.method === 'cash' && f.tendered != null && f.tendered > 0) body.amountTendered = money2(f.tendered);
  const optional = { reference: text(f.reference), paymentDate: f.paymentDate || undefined, payerName: text(f.payerName),
    payerAccount: text(f.payerAccount), notes: text(f.notes) };
  for (const [k, v] of Object.entries(optional)) if (v !== undefined) body[k] = v;
  if (f.attachmentIds?.length) body.attachmentIds = f.attachmentIds;
  return body;
}

/** The goods lines of a sale: what is handed over and what a Reverse takes back. */
export const goodsLines = (lines: SalesOrderLine[] = []) =>
  lines.filter((l) => !!l.productId && !!l.id && Number(l.quantity) - Number(l.qtyCancelled ?? 0) > 0);

/** Serial numbers a serial-tracked line needs: one per base unit, when that is a whole number. */
export const serialsExpected = (l: SalesOrderLine) => {
  const n = (Number(l.quantity) - Number(l.qtyCancelled ?? 0)) * Number(l.uomFactor ?? 1);
  return Number.isInteger(Math.round(n * 1e6) / 1e6) ? Math.round(n) : null;
};

export interface HandoverForm { affirmed: boolean; receivedByName: string; receivedByPhone: string; notes: string; serials: Record<string, string> }

/** Body of a cash confirm. Lines are sent (each in full) only when one of them needs serial numbers. */
export function handoverBody(f: HandoverForm, lines: SalesOrderLine[] = []): HandoverBody {
  const body: HandoverBody = { affirmed: f.affirmed, receivedByName: f.receivedByName.trim() };
  if (text(f.receivedByPhone)) body.receivedByPhone = text(f.receivedByPhone);
  if (text(f.notes)) body.notes = text(f.notes);
  const goods = goodsLines(lines);
  if (goods.some((l) => l.serialTracking)) {
    body.lines = goods.map((l) => ({ lineId: l.id!, serialNumbers: l.serialTracking ? serialList(f.serials[l.id!]) : [] }));
  }
  return body;
}

export type Disposition = 'restock' | 'quarantine' | 'damaged' | 'scrap';
export type Condition = 'usable' | 'damaged' | 'defective';
export interface ReverseLineForm { serials?: string; condition?: Condition | ''; disposition?: Disposition; locationId?: string; notes?: string }
export interface ReverseForm {
  reason: string; settlement: 'refund' | 'credit'; returnReason: string; locationId: string; lines: Record<string, ReverseLineForm>;
}

/** Body of `POST /sales/orders/:id/reverse`: every goods line, in full, with what the cashier found. */
export function reverseBody(f: ReverseForm, lines: SalesOrderLine[] = []): ReverseBody {
  const body: ReverseBody = { reason: f.reason.trim(), settlement: f.settlement, lines: [] };
  if (f.returnReason) body.returnReason = f.returnReason;
  if (f.locationId) body.locationId = f.locationId;
  body.lines = goodsLines(lines).map((l) => {
    const g = f.lines[l.id!] ?? {};
    const line: ReverseBody['lines'][number] = { orderLineId: l.id!, quantity: Number(l.quantity) - Number(l.qtyCancelled ?? 0) };
    if (l.serialTracking) line.serialNumbers = serialList(g.serials);
    if (g.condition) line.condition = g.condition;
    if (g.disposition) line.disposition = g.disposition;
    if (g.locationId && (g.disposition === 'quarantine' || g.disposition === 'damaged')) line.locationId = g.locationId;
    if (text(g.notes)) line.notes = text(g.notes);
    return line;
  });
  return body;
}

/** Damaged or defective goods never go back to sellable stock (AD-9): the problem to show, or null. */
export function reverseLineProblem(g: ReverseLineForm): string | null {
  return (g.disposition ?? 'restock') === 'restock' && g.condition && g.condition !== 'usable'
    ? tx('Damaged or defective goods cannot go back to sellable stock: choose quarantine or scrap')
    : null;
}

// ---- Labels ----

export const TENDER_METHOD_LABEL: Record<TenderMethod, string> = {
  cash: tx('Cash'), mobile_money: tx('Mobile money'), bank: tx('Bank'), card: tx('Card'),
};
export const MOBILE_MONEY_PROVIDERS: Array<[string, string]> = [['mtn_momo', tx('MTN Mobile Money')], ['airtel_money', tx('Airtel Money')]];

export const SALE_FISCAL_LABEL: Record<SaleFiscalStatus, string> = {
  none: tx('Not fiscalised'), pending: tx('Fiscal pending'), issued: tx('Fiscal issued'), failed: tx('Fiscal failed'),
  cancellation_pending: tx('Fiscal cancel pending'), cancelled: tx('Fiscal cancelled'),
};
export const FISCAL_OPERATION_LABEL: Record<FiscalOperation['operation'], string> = {
  issue: tx('Issue'), cancel: tx('Cancel'), correct: tx('Correction'), credit_note: tx('Credit note'),
};
export const FISCAL_STATUS_LABEL: Record<FiscalOperationStatus, string> = {
  pending: tx('Queued'), sent: tx('Sending'), unknown: tx('Outcome unknown'), review: tx('Needs reconciliation'),
  succeeded: tx('Registered'), failed: tx('Failed'),
};
/** Fiscal operations that are still open: the job, or "Send again", carries them on. */
export const FISCAL_OPEN: FiscalOperationStatus[] = ['pending', 'sent', 'unknown', 'review'];

export const REVERSAL_STAGE_LABEL: Record<ReversalStage, string> = {
  reversed: tx('Reversed'),
  approval_pending: tx('Reversal waiting for approval'),
  approval_rejected: tx('Reversal rejected'),
  fiscal_cancel_pending: tx('Fiscal cancel pending'),
  reversal_pending: tx('Reversal to complete'),
};

export const RETURN_REASON_LABEL: Record<string, string> = {
  wrong_grade: tx('Wrong grade'), damaged: tx('Damaged'), short_quantity: tx('Short quantity'), excess_quantity: tx('Excess quantity'),
  quality_rejection: tx('Quality rejection'), other: tx('Other'),
};
export const CONDITION_LABEL: Record<Condition, string> = { usable: tx('Usable'), damaged: tx('Damaged'), defective: tx('Defective') };
export const DISPOSITION_LABEL: Record<Disposition, string> = {
  restock: tx('Restock'), quarantine: tx('Quarantine'), damaged: tx('Damaged'), scrap: tx('Scrap'),
};

const EVENT_LABEL: Record<string, string> = {
  create: tx('Created'), discard: tx('Discarded'), confirm: tx('Confirmed and handed over'),
  payment_added: tx('Payment added'), payment_verified: tx('Payment verified'), payment_failed: tx('Payment not received'),
  payment_removed: tx('Payment removed'), reverse: tx('Reversed'), reverse_approval_requested: tx('Reversal sent for approval'),
};
const FISCAL_STEP_LABEL: Record<string, string> = {
  requested: tx('requested'), succeeded: tx('registered'), failed: tx('failed'), review: tx('to reconcile'),
  resolved: tx('reconciled'), stuck: tx('stuck'),
};

/** A timeline action in words, e.g. `fiscal_issue_succeeded` → "Fiscal issue · registered". */
export function eventLabel(action: string): string {
  if (EVENT_LABEL[action]) return EVENT_LABEL[action];
  const m = /^fiscal_(issue|cancel|correct|credit_note)_(\w+)$/.exec(action);
  if (m) {
    const op = FISCAL_OPERATION_LABEL[m[1] as FiscalOperation['operation']];
    return `${tx('Fiscal')} ${op.toLowerCase()} · ${FISCAL_STEP_LABEL[m[2]] ?? m[2].replace(/_/g, ' ')}`;
  }
  return action.replace(/_/g, ' ');
}

/** The invoice or credit note a fiscal operation registers, from its request. */
export function fiscalDocumentNumber(op: FiscalOperation): string | null {
  const p = op.requestPayload ?? {};
  const v = p.creditNoteNumber ?? p.invoiceNumber ?? p.number;
  return typeof v === 'string' && v ? v : null;
}

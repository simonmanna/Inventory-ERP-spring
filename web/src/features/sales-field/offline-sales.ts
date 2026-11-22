/**
 * ADR-S083 O5 — field sales on a registered device (pure: no React, no axios; tested in isolation).
 *
 * The device estimates prices from the synced price lists so the rep can quote a figure offline; the server reprices
 * every order and quote, and anything beyond the tolerance goes to review (the price audit). Credit is a local
 * warning from the synced snapshot plus this device's unsynced orders; the rep's offline order cap is enforced here
 * and again on the server. Payments record what was actually received.
 */
import type { SyncOp } from '@/lib/offline/types';

type Row = Record<string, unknown>;

const num = (v: unknown, d = 0): number => {
  const n = typeof v === 'number' ? v : typeof v === 'string' && v.trim() !== '' ? Number(v) : NaN;
  return Number.isFinite(n) ? n : d;
};
const round2 = (n: number) => Math.round((n + Number.EPSILON) * 100) / 100;

export interface PriceItem {
  id: string; scope: 'product' | 'category' | 'all' | string; productId?: string | null; productCategoryId?: string | null;
  partnerId?: string | null; partnerCategoryId?: string | null; variantId?: string | null; uomId?: string | null;
  minQuantity?: number; branchId?: string | null; validFrom?: string | null; validTo?: string | null;
  compute: 'fixed' | 'discount' | 'surcharge' | string; value: number; priority?: number;
}
export interface PriceList { id: string; name: string; isDefault?: boolean; currencyCode?: string | null; items: PriceItem[] }
export interface CustomerCredit {
  id: string; pricelistId?: string | null; paymentTermId?: string | null; salespersonId?: string | null;
  creditHold?: boolean; limit?: number; exposure?: number | null; headroom?: number | null; overdue?: number; asOf?: string;
}

export interface Estimate { unitPrice: number; discountPercent: number; source: 'price_list' | 'list' }

const day = (s?: string | null) => (s ? s.slice(0, 10) : null);
const current = (i: { validFrom?: string | null; validTo?: string | null }, today: string) =>
  (!day(i.validFrom) || day(i.validFrom)! <= today) && (!day(i.validTo) || day(i.validTo)! >= today);
const scopeRank = (s: string) => (s === 'product' ? 3 : s === 'category' ? 2 : 1);

/**
 * The device's best estimate of a line price: the customer's pricelist (else the default one), the best matching rule
 * as the server orders them (priority, variant, product > category > all, customer-specific, larger quantity break),
 * computed as fixed / discount / surcharge on the list price. Anything subtler (agreements, promotions, site lists) is
 * the server's: the price audit catches the difference.
 */
export function estimatePrice(input: { product: Row; quantity: number; variantId?: string | null; customer?: CustomerCredit;
    priceLists: PriceList[]; branchId?: string | null; today: string }): Estimate {
  const list = num(input.product.salesPrice);
  const pl = input.priceLists.find((p) => p.id === input.customer?.pricelistId)
    ?? input.priceLists.find((p) => p.isDefault);
  if (!pl) return { unitPrice: list, discountPercent: 0, source: 'list' };
  const partner = input.customer?.id;
  const candidates = pl.items.filter((i) => current(i, input.today)
    && ((i.scope === 'product' && i.productId === input.product.id)
      || (i.scope === 'category' && !!i.productCategoryId && i.productCategoryId === input.product.categoryId)
      || i.scope === 'all')
    && (!i.variantId || i.variantId === input.variantId)
    && num(i.minQuantity) <= input.quantity
    && (!i.branchId || !input.branchId || i.branchId === input.branchId)
    && (!i.partnerId || i.partnerId === partner) && !i.partnerCategoryId);
  candidates.sort((a, b) => num(b.priority) - num(a.priority) || Number(!!b.variantId) - Number(!!a.variantId)
    || scopeRank(b.scope) - scopeRank(a.scope) || Number(!!b.partnerId) - Number(!!a.partnerId)
    || num(b.minQuantity) - num(a.minQuantity));
  const best = candidates[0];
  if (!best) return { unitPrice: list, discountPercent: 0, source: 'list' };
  const v = num(best.value);
  if (best.compute === 'fixed') return { unitPrice: v, discountPercent: 0, source: 'price_list' };
  if (best.compute === 'discount') return { unitPrice: list, discountPercent: v, source: 'price_list' };
  if (best.compute === 'surcharge') return { unitPrice: list * (1 + v / 100), discountPercent: 0, source: 'price_list' };
  return { unitPrice: list, discountPercent: 0, source: 'list' }; // cost-plus: the server knows the cost
}

export interface OrderLine {
  productId: string; variantId?: string | null; name: string; quantity: number; unitPrice: number;
  /** The rule's discount (part of the estimate). */
  ruleDiscountPercent: number;
  /** A discount the rep grants by hand; sent to the server (its approval rules apply). */
  manualDiscountPercent?: number;
  taxRate: number; taxInclusive: boolean;
}

/** Line total as the server would compute it: net of discounts, plus exclusive tax. */
export function lineTotal(l: OrderLine): number {
  const disc = l.manualDiscountPercent != null && l.manualDiscountPercent > 0 ? l.manualDiscountPercent : l.ruleDiscountPercent;
  const net = l.quantity * l.unitPrice * (1 - disc / 100);
  return l.taxInclusive ? net : net * (1 + l.taxRate / 100);
}

export const orderTotal = (lines: OrderLine[]) => round2(lines.reduce((s, l) => s + lineTotal(l), 0));

/** A line for a product from the synced scopes. */
export function lineFor(product: Row, quantity: number, est: Estimate, variantId?: string | null): OrderLine {
  const tax = (product.tax ?? null) as Row | null;
  return { productId: String(product.id), variantId: variantId ?? null, name: String(product.name ?? ''), quantity,
    unitPrice: round2(est.unitPrice), ruleDiscountPercent: est.discountPercent, taxRate: num(tax?.rate),
    taxInclusive: tax?.isInclusive === true };
}

export interface CreditView { state: 'ok' | 'warn' | 'blocked'; message?: string; headroomAfter: number | null }

/**
 * Local credit warning: the snapshot's headroom less this device's orders for the customer not yet synced, less this
 * order. A hold blocks; going over warns (the server decides: hold, credit request or refusal).
 */
export function creditView(c: CustomerCredit | undefined, orderTotalAmount: number, unsyncedForCustomer: number): CreditView {
  if (!c) return { state: 'ok', headroomAfter: null };
  if (c.creditHold) return { state: 'blocked', message: 'This customer is on credit hold.', headroomAfter: null };
  if (c.headroom == null || num(c.limit) <= 0) return { state: 'ok', headroomAfter: null };
  const after = round2(num(c.headroom) - unsyncedForCustomer - orderTotalAmount);
  if (after < 0) {
    return { state: 'warn', headroomAfter: after,
      message: `Over the credit limit by ${round2(-after)}; the order will be held or need a credit approval.` };
  }
  return { state: 'ok', headroomAfter: after };
}

/** Totals of this device's unsynced offline orders (all, and per customer) — from the op queue. */
export function unsyncedOrders(ops: SyncOp[]): { total: number; byCustomer: Record<string, number> } {
  const byCustomer: Record<string, number> = {};
  let total = 0;
  for (const o of ops) {
    if (o.type !== 'salesOrder.create' || o.status === 'applied' || o.status === 'rejected') continue;
    const t = num(o.payload.expectedTotal);
    total += t;
    const c = String(o.payload.partnerId ?? '');
    byCustomer[c] = (byCustomer[c] ?? 0) + t;
  }
  return { total: round2(total), byCustomer };
}

/** The rep's offline order cap (0 = none): unsynced orders + this one must stay within it. */
export function capView(cap: number | undefined, unsyncedTotal: number, thisOrder: number): { ok: boolean; message?: string } {
  if (!cap || cap <= 0) return { ok: true };
  if (unsyncedTotal + thisOrder > cap) {
    return { ok: false, message: `Offline orders would reach ${round2(unsyncedTotal + thisOrder)}, over your cap of ${cap}. Sync first.` };
  }
  return { ok: true };
}

export interface OpDraft { type: string; payload: Record<string, unknown> }

function linePayload(lines: OrderLine[]) {
  return lines.filter((l) => l.quantity > 0).map((l) => ({
    productId: l.productId, variantId: l.variantId ?? undefined, quantity: l.quantity,
    discountPercent: l.manualDiscountPercent != null && l.manualDiscountPercent > 0 ? l.manualDiscountPercent : undefined,
    expectedUnitPrice: l.unitPrice,
  }));
}

/** `salesOrder.create` (the server prices it; `expectedTotal` is what the rep saw, for the price audit). */
export function orderOp(input: { clientId: string; partnerId: string; lines: OrderLine[]; paymentMode?: 'cash' | 'credit';
    paymentTermId?: string; requestedDate?: string; customerRef?: string; notes?: string; deliveryAddress?: string;
    confirm?: boolean }): OpDraft {
  if (!input.partnerId) throw new Error('Choose the customer.');
  const lines = linePayload(input.lines);
  if (!lines.length) throw new Error('Add at least one item.');
  if (input.lines.some((l) => (l.manualDiscountPercent ?? 0) < 0 || (l.manualDiscountPercent ?? 0) > 100)) {
    throw new Error('A discount must be between 0 and 100%.');
  }
  return { type: 'salesOrder.create', payload: { clientId: input.clientId, partnerId: input.partnerId, lines,
    expectedTotal: orderTotal(input.lines.filter((l) => l.quantity > 0)), paymentMode: input.paymentMode,
    paymentTermId: input.paymentTermId || undefined, requestedDate: input.requestedDate || undefined,
    customerRef: input.customerRef || undefined, notes: input.notes || undefined,
    deliveryAddress: input.deliveryAddress || undefined, confirm: input.confirm ?? true } };
}

/** `quote.create`. */
export function quoteOp(input: { clientId: string; partnerId: string; lines: OrderLine[]; validUntil?: string; notes?: string;
    submit?: boolean }): OpDraft {
  if (!input.partnerId) throw new Error('Choose the customer.');
  const lines = linePayload(input.lines);
  if (!lines.length) throw new Error('Add at least one item.');
  return { type: 'quote.create', payload: { clientId: input.clientId, partnerId: input.partnerId, lines,
    expectedTotal: orderTotal(input.lines.filter((l) => l.quantity > 0)), validUntil: input.validUntil || undefined,
    notes: input.notes || undefined, submit: input.submit ?? true } };
}

export interface OpenInvoice { id: string; documentNumber: string; partnerId: string; amountResidual: number; dueDate?: string | null;
  totalAmount?: number; currencyId?: string | null }

/** Spread an amount over invoices oldest-due first (the rep can change it). */
export function autoAllocate(amount: number, invoices: OpenInvoice[]): Record<string, number> {
  let left = round2(amount);
  const out: Record<string, number> = {};
  const sorted = [...invoices].sort((a, b) => String(a.dueDate ?? '9999').localeCompare(String(b.dueDate ?? '9999')));
  for (const i of sorted) {
    if (left <= 0) break;
    const take = round2(Math.min(left, num(i.amountResidual)));
    if (take > 0) {
      out[i.id] = take;
      left = round2(left - take);
    }
  }
  return out;
}

/** `payment.receive`: the amount actually received (no tolerance), and how the rep wants it allocated. */
export function paymentOp(input: { clientId: string; partnerId: string; amount: number; paymentMethod: string;
    reference?: string; allocations: Record<string, number> }): OpDraft {
  if (!input.partnerId) throw new Error('Choose the customer.');
  if (!(input.amount > 0)) throw new Error('Enter the amount received.');
  const allocations = Object.entries(input.allocations).filter(([, a]) => a > 0)
    .map(([documentId, amount]) => ({ documentId, amount: round2(amount) }));
  const sum = round2(allocations.reduce((s, a) => s + a.amount, 0));
  if (sum > round2(input.amount)) throw new Error('The allocations add up to more than the amount received.');
  return { type: 'payment.receive', payload: { clientId: input.clientId, partnerId: input.partnerId, amount: round2(input.amount),
    paymentMethod: input.paymentMethod, reference: input.reference || undefined, allocations } };
}

export const SALES_OP_TYPES = ['salesOrder.create', 'quote.create', 'payment.receive'];

const LABELS: Record<string, string> = {
  confirmed: 'Confirmed',
  on_hold: 'On hold',
  pending_approval: 'Waiting for approval',
  credit_approval: 'Waiting for a credit approval',
  draft: 'Saved as draft — back office to confirm',
  price_review: 'Price differs — in review',
  approved: 'Approved',
  posted: 'Posted',
  partly_unallocated: 'Posted — part left on account',
};

/** A one-line status for a field-sales op on this device. */
export function describeSale(op: SyncOp): { label: string; tone: 'ok' | 'wait' | 'warn' | 'bad'; detail?: string } {
  if (op.status === 'rejected') return { label: 'Refused — see Sync dead letters', tone: 'bad', detail: op.lastError };
  if (op.status === 'held') return { label: 'Held — the device needs attention', tone: 'bad', detail: op.lastError };
  if (op.status !== 'applied') return { label: 'Waiting to sync', tone: 'wait' };
  const state = op.result?.outcome?.state;
  if (!state) return { label: 'Synced', tone: 'ok' };
  const tone = ['confirmed', 'approved', 'posted'].includes(state) ? 'ok'
    : ['pending_approval', 'credit_approval', 'draft'].includes(state) ? 'wait' : 'warn';
  return { label: LABELS[state] ?? state, tone, detail: op.result?.outcome?.reason };
}

/**
 * ADR-S083 O4 — warehouse work on a registered device (pure: no React, no axios; tested in isolation).
 *
 * Builds the four warehouse ops from what the screen collected, and resolves a scanned code against the synced
 * scopes. The server does the rest (S4): a refused posting keeps its document and is held for a supervisor; a
 * receipt before its dispatch waits; stock going below zero is flagged.
 */
import type { SyncOp } from '@/lib/offline/types';

type Row = Record<string, unknown>;

export interface Scopes {
  products: Row[];
  productVariants: Row[];
  productPackagings: Row[];
  lots: Row[];
  serials: Row[];
}

/** What a scanned code is. `quantity` is how many base units one scan means (a pack barcode counts its pack). */
export type ScanHit =
  | { kind: 'product'; productId: string; variantId: string | null; name: string; quantity: number }
  | { kind: 'lot'; productId: string; variantId: string | null; name: string; batchNumber: string; quantity: number }
  | { kind: 'serial'; productId: string; variantId: string | null; name: string; serialNumber: string; quantity: number };

const live = (r: Row) => !r.deletedAt && r.isActive !== false;
const eq = (a: unknown, code: string) => typeof a === 'string' && a.trim().toLowerCase() === code;

/**
 * Resolve a scanned or typed code: serial number, then variant barcode / SKU, packaging barcode, product barcode /
 * SKU / code, then lot number. `locationId` narrows lots and serials to the location being worked.
 */
export function resolveScan(raw: string, s: Scopes, locationId?: string): ScanHit | null {
  const code = raw.trim().toLowerCase();
  if (!code) return null;
  const productName = (id: unknown) => String(s.products.find((p) => p.id === id)?.name ?? 'Unknown item');
  const here = (r: Row) => !locationId || r.locationId === locationId;

  const serial = s.serials.find((r) => eq(r.serialNumber, code) && r.status === 'in_stock' && here(r))
    ?? s.serials.find((r) => eq(r.serialNumber, code));
  if (serial) {
    return { kind: 'serial', productId: String(serial.productId), variantId: (serial.variantId as string) ?? null,
      name: productName(serial.productId), serialNumber: String(serial.serialNumber), quantity: 1 };
  }
  const variant = s.productVariants.find((v) => live(v) && (eq(v.barcode, code) || eq(v.sku, code)));
  if (variant) {
    return { kind: 'product', productId: String(variant.productId), variantId: String(variant.id),
      name: `${productName(variant.productId)}${variant.name ? ` — ${variant.name}` : ''}`, quantity: 1 };
  }
  const pack = s.productPackagings.find((p) => live(p) && eq(p.barcode, code));
  if (pack) {
    const qty = Number(pack.quantity ?? pack.factor ?? 1);
    return { kind: 'product', productId: String(pack.productId), variantId: (pack.variantId as string) ?? null,
      name: `${productName(pack.productId)}${pack.name ? ` (${pack.name})` : ''}`, quantity: qty > 0 ? qty : 1 };
  }
  const product = s.products.find((p) => live(p) && (eq(p.barcode, code) || eq(p.sku, code) || eq(p.code, code)));
  if (product) {
    return { kind: 'product', productId: String(product.id), variantId: null, name: String(product.name ?? ''), quantity: 1 };
  }
  const lot = s.lots.find((l) => eq(l.batchNumber, code) && here(l) && Number(l.quantity ?? 0) > 0)
    ?? s.lots.find((l) => eq(l.batchNumber, code));
  if (lot) {
    return { kind: 'lot', productId: String(lot.productId), variantId: (lot.variantId as string) ?? null,
      name: productName(lot.productId), batchNumber: String(lot.batchNumber), quantity: 1 };
  }
  return null;
}

/** One line on a warehouse screen: the item plus the quantity being worked. */
export interface WorkLine {
  productId: string;
  variantId: string | null;
  name: string;
  quantity: number;
  batchNumber?: string;
  serialNumbers?: string[];
}

/** Add a scan to the lines: same item (and lot) adds up; a serial is never counted twice. */
export function addScan(lines: WorkLine[], hit: ScanHit): WorkLine[] {
  const batch = hit.kind === 'lot' ? hit.batchNumber : undefined;
  const i = lines.findIndex((l) => l.productId === hit.productId && l.variantId === hit.variantId && l.batchNumber === batch);
  if (hit.kind === 'serial') {
    if (lines.some((l) => l.serialNumbers?.includes(hit.serialNumber))) return lines;
    if (i >= 0) {
      const l = lines[i];
      return lines.map((x, j) => (j === i ? { ...l, quantity: l.quantity + 1, serialNumbers: [...(l.serialNumbers ?? []),
        hit.serialNumber] } : x));
    }
    return [...lines, { productId: hit.productId, variantId: hit.variantId, name: hit.name, quantity: 1,
      serialNumbers: [hit.serialNumber] }];
  }
  if (i >= 0) return lines.map((x, j) => (j === i ? { ...x, quantity: x.quantity + hit.quantity } : x));
  return [...lines, { productId: hit.productId, variantId: hit.variantId, name: hit.name, quantity: hit.quantity,
    batchNumber: batch }];
}

export interface OpDraft {
  type: string;
  payload: Record<string, unknown>;
}

const positive = (lines: WorkLine[]) => lines.filter((l) => Number.isFinite(l.quantity) && l.quantity > 0);

/** `transfer.dispatch` of an approved transfer, or a new transit transfer from the scanned lines. */
export function dispatchOp(input: { clientId: string; transferId?: string; fromLocationId?: string; toLocationId?: string;
    lines?: WorkLine[]; notes?: string }): OpDraft {
  if (input.transferId) return { type: 'transfer.dispatch', payload: { clientId: input.clientId, transferId: input.transferId } };
  const lines = positive(input.lines ?? []);
  if (!input.fromLocationId || !input.toLocationId) throw new Error('Choose where the goods go from and to.');
  if (input.fromLocationId === input.toLocationId) throw new Error('Source and destination must differ.');
  if (!lines.length) throw new Error('Scan at least one item.');
  return { type: 'transfer.dispatch', payload: { clientId: input.clientId, fromLocationId: input.fromLocationId,
    toLocationId: input.toLocationId, notes: input.notes || undefined, items: lines.map((l) => ({ productId: l.productId,
      variantId: l.variantId ?? undefined, batchNumber: l.batchNumber, quantity: l.quantity })) } };
}

/** Transfer item as pulled (`transfers` scope). */
export interface TransferItem { id: string; productId: string; variantId?: string | null; productName?: string;
  qtyDispatched?: number; qtyRequested?: number; qtyReceived?: number; qtyDamaged?: number; qtyShort?: number;
  qtyRecalled?: number }

/** Still to arrive on a transfer line: dispatched (or, before dispatch, requested) minus what is accounted for. */
export function outstanding(item: TransferItem, dispatched: boolean): number {
  const base = Number(dispatched ? item.qtyDispatched ?? 0 : item.qtyRequested ?? 0);
  return Math.max(0, base - Number(item.qtyReceived ?? 0) - Number(item.qtyDamaged ?? 0) - Number(item.qtyShort ?? 0)
    - Number(item.qtyRecalled ?? 0));
}

/** `transfer.receive`: per item, what arrived, what arrived damaged, what is missing. */
export function receiveOp(input: { clientId: string; transferId: string; lines: Array<{ itemId: string; received: number;
    damaged?: number; short?: number }>; notes?: string }): OpDraft {
  const lines = input.lines.filter((l) => l.received > 0 || (l.damaged ?? 0) > 0 || (l.short ?? 0) > 0);
  if (!lines.length) throw new Error('Enter what arrived.');
  if (lines.some((l) => l.received < 0 || (l.damaged ?? 0) < 0 || (l.short ?? 0) < 0)) {
    throw new Error('Quantities cannot be negative.');
  }
  return { type: 'transfer.receive', payload: { clientId: input.clientId, transferId: input.transferId,
    notes: input.notes || undefined, lines: lines.map((l) => ({ itemId: l.itemId, received: l.received,
      damaged: l.damaged || undefined, short: l.short || undefined })) } };
}

/** `stock.adjust`: signed changes found at a location (the server waits for approval as for any adjustment). */
export function adjustOp(input: { clientId: string; locationId: string; reason: string; lines: Array<WorkLine & {
    direction: 'in' | 'out' }>; notes?: string }): OpDraft {
  const lines = input.lines.filter((l) => l.quantity > 0);
  if (!input.locationId) throw new Error('Choose the location.');
  if (!input.reason.trim()) throw new Error('Give a reason.');
  if (!lines.length) throw new Error('Scan at least one item.');
  return { type: 'stock.adjust', payload: { clientId: input.clientId, locationId: input.locationId,
    reason: input.reason.trim(), notes: input.notes || undefined, lines: lines.map((l) => ({ productId: l.productId,
      variantId: l.variantId ?? undefined, batchNumber: l.batchNumber,
      qtyChange: l.direction === 'out' ? -l.quantity : l.quantity })) } };
}

/** Purchase-order line as pulled (`openPurchaseOrders` scope). */
export interface PoLine { id: string; productId: string; variantId?: string | null; description?: string; quantity: number;
  receivedQuantity?: number; cancelledQuantity?: number; unitOfMeasureId?: string | null }

/** Still to receive on a purchase-order line, in the line's unit. */
export function openOnLine(l: PoLine): number {
  return Math.max(0, Number(l.quantity) - Number(l.receivedQuantity ?? 0) - Number(l.cancelledQuantity ?? 0));
}

/** `grn.receive` against a purchase order. */
export function grnOp(input: { clientId: string; purchaseOrderId: string; warehouseId: string; lines: Array<{
    line: PoLine; quantity: number; batchNumber?: string; expiryDate?: string }>; notes?: string }): OpDraft {
  const lines = input.lines.filter((l) => l.quantity > 0);
  if (!input.warehouseId) throw new Error('Choose the receiving location.');
  if (!lines.length) throw new Error('Enter what was received.');
  return { type: 'grn.receive', payload: { clientId: input.clientId, purchaseOrderId: input.purchaseOrderId,
    warehouseId: input.warehouseId, notes: input.notes || undefined, lines: lines.map(({ line, quantity, batchNumber,
      expiryDate }) => ({ purchaseOrderLineId: line.id, productId: line.productId, variantId: line.variantId ?? undefined,
      description: line.description ?? 'Received', quantity, uomId: line.unitOfMeasureId ?? undefined,
      batchNumber: batchNumber || undefined, expiryDate: expiryDate || undefined })) } };
}

/** `stock.count`: what was counted (the server measures the variance against on-hand when it was counted). */
export function countOp(input: { locationId: string; lines: WorkLine[]; reason?: string }): OpDraft {
  if (!input.locationId) throw new Error('Choose the location.');
  if (!input.lines.length) throw new Error('Scan at least one item.');
  return { type: 'stock.count', payload: { locationId: input.locationId, reason: input.reason || undefined,
    lines: input.lines.map((l) => ({ productId: l.productId, variantId: l.variantId ?? undefined, countedQty: l.quantity })) } };
}

export const WAREHOUSE_OP_TYPES = ['transfer.dispatch', 'transfer.receive', 'stock.adjust', 'grn.receive', 'stock.count',
  'stock.in', 'stock.out'];

const STATE_LABELS: Record<string, string> = {
  dispatched: 'Dispatched',
  received: 'Received',
  posted: 'Posted',
  pending_approval: 'Waiting for approval',
  awaiting_dispatch: 'Waiting for the dispatch to sync',
  held: 'Held for a supervisor',
};

/** A one-line status for the device's warehouse work. */
export function describe(op: SyncOp): { label: string; tone: 'ok' | 'wait' | 'warn' | 'bad'; detail?: string } {
  if (op.status === 'rejected') return { label: 'Refused — see Sync dead letters', tone: 'bad', detail: op.lastError };
  if (op.status === 'held') return { label: 'Held — the device needs attention', tone: 'bad', detail: op.lastError };
  if (op.status !== 'applied') return { label: 'Waiting to sync', tone: 'wait' };
  const state = op.result?.outcome?.state;
  if (!state) return { label: 'Synced', tone: 'ok' };
  return {
    label: STATE_LABELS[state] ?? state,
    tone: state === 'held' ? 'warn' : state === 'pending_approval' || state === 'awaiting_dispatch' ? 'wait' : 'ok',
    detail: op.result?.outcome?.reason,
  };
}

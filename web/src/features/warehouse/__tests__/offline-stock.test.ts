import { describe as suite, expect, it } from 'vitest';
import {
  addScan, adjustOp, countOp, describe, dispatchOp, grnOp, openOnLine, outstanding, receiveOp, resolveScan, type Scopes,
} from '../offline-stock';
import type { SyncOp } from '@/lib/offline/types';

const scopes: Scopes = {
  products: [
    { id: 'p1', name: 'Water 500ml', barcode: '6001', sku: 'WAT-500', code: 'W1' },
    { id: 'p2', name: 'Shirt', barcode: null, sku: 'SHIRT' },
    { id: 'p3', name: 'Retired', barcode: '9999', deletedAt: '2026-01-01' },
  ],
  productVariants: [{ id: 'v1', productId: 'p2', name: 'Blue', barcode: '7001', sku: 'SHIRT-B' }],
  productPackagings: [{ id: 'k1', productId: 'p1', name: 'Crate', quantity: 24, barcode: '6024' }],
  lots: [{ productId: 'p1', batchNumber: 'LOT-A', locationId: 'L1', quantity: 10 }],
  serials: [{ productId: 'p2', variantId: 'v1', serialNumber: 'SN-1', status: 'in_stock', locationId: 'L1' }],
};

suite('warehouse offline scanning (ADR-S083 O4)', () => {
  it('resolves serial, variant, pack, product and lot codes; ignores deleted products', () => {
    expect(resolveScan(' sn-1 ', scopes)).toMatchObject({ kind: 'serial', productId: 'p2', variantId: 'v1', quantity: 1 });
    expect(resolveScan('7001', scopes)).toMatchObject({ kind: 'product', variantId: 'v1', name: 'Shirt — Blue' });
    expect(resolveScan('6024', scopes)).toMatchObject({ productId: 'p1', quantity: 24 });
    expect(resolveScan('wat-500', scopes)).toMatchObject({ productId: 'p1', quantity: 1 });
    expect(resolveScan('LOT-A', scopes, 'L1')).toMatchObject({ kind: 'lot', batchNumber: 'LOT-A' });
    expect(resolveScan('9999', scopes)).toBeNull();
    expect(resolveScan('', scopes)).toBeNull();
  });

  it('adds scans up per item and lot, and never counts a serial twice', () => {
    let lines = addScan([], resolveScan('6001', scopes)!);
    lines = addScan(lines, resolveScan('6024', scopes)!);
    expect(lines).toHaveLength(1);
    expect(lines[0].quantity).toBe(25);
    lines = addScan(lines, resolveScan('LOT-A', scopes)!);
    expect(lines).toHaveLength(2);
    lines = addScan(lines, resolveScan('SN-1', scopes)!);
    lines = addScan(lines, resolveScan('SN-1', scopes)!);
    expect(lines[2]).toMatchObject({ quantity: 1, serialNumbers: ['SN-1'] });
  });

  it('builds dispatch ops for an approved transfer or scanned goods, and refuses bad input', () => {
    expect(dispatchOp({ clientId: 'c', transferId: 't' })).toEqual({ type: 'transfer.dispatch', payload: { clientId: 'c', transferId: 't' } });
    const op = dispatchOp({ clientId: 'c', fromLocationId: 'A', toLocationId: 'B', lines: [
      { productId: 'p1', variantId: null, name: 'W', quantity: 3 }, { productId: 'p2', variantId: 'v1', name: 'S', quantity: 0 }] });
    expect(op.payload.items).toEqual([{ productId: 'p1', variantId: undefined, batchNumber: undefined, quantity: 3 }]);
    expect(() => dispatchOp({ clientId: 'c', fromLocationId: 'A', toLocationId: 'A', lines: [] })).toThrow('differ');
    expect(() => dispatchOp({ clientId: 'c', fromLocationId: 'A', toLocationId: 'B', lines: [] })).toThrow('Scan');
  });

  it('receive, adjust, GRN and count payloads match the server contract', () => {
    expect(receiveOp({ clientId: 'c', transferId: 't', lines: [{ itemId: 'i1', received: 2, damaged: 1 }, { itemId: 'i2', received: 0 }] })
      .payload.lines).toEqual([{ itemId: 'i1', received: 2, damaged: 1, short: undefined }]);
    expect(() => receiveOp({ clientId: 'c', transferId: 't', lines: [{ itemId: 'i', received: 0 }] })).toThrow();
    expect(adjustOp({ clientId: 'c', locationId: 'L', reason: ' damaged ', lines: [
      { productId: 'p1', variantId: null, name: 'W', quantity: 2, direction: 'out' },
      { productId: 'p2', variantId: 'v1', name: 'S', quantity: 1, direction: 'in' }] }).payload)
      .toMatchObject({ reason: 'damaged', lines: [{ productId: 'p1', qtyChange: -2 }, { productId: 'p2', variantId: 'v1', qtyChange: 1 }] });
    const line = { id: 'pl', productId: 'p1', quantity: 10, receivedQuantity: 4, description: 'Water' };
    expect(openOnLine(line)).toBe(6);
    expect(grnOp({ clientId: 'c', purchaseOrderId: 'po', warehouseId: 'L', lines: [{ line, quantity: 5, batchNumber: 'B1' }] })
      .payload.lines).toEqual([{ purchaseOrderLineId: 'pl', productId: 'p1', variantId: undefined, description: 'Water',
      quantity: 5, uomId: undefined, batchNumber: 'B1', expiryDate: undefined }]);
    expect(countOp({ locationId: 'L', lines: [{ productId: 'p1', variantId: null, name: 'W', quantity: 0 }] }).payload.lines)
      .toEqual([{ productId: 'p1', variantId: undefined, countedQty: 0 }]);
  });

  it('outstanding transfer quantity uses dispatched once dispatched, requested before', () => {
    const item = { id: 'i', productId: 'p', qtyRequested: 10, qtyDispatched: 8, qtyReceived: 3, qtyDamaged: 1 };
    expect(outstanding(item, true)).toBe(4);
    expect(outstanding(item, false)).toBe(6);
  });

  it('describes the server outcome of synced work', () => {
    const base = { opId: 'o', deviceSeq: 1, type: 'transfer.dispatch', actorUserId: 'u', occurredAt: '', payload: {},
      attempts: 1, nextAttemptAt: 0, createdAt: 0, updatedAt: 0 } as Omit<SyncOp, 'status'>;
    expect(describe({ ...base, status: 'queued' }).tone).toBe('wait');
    expect(describe({ ...base, status: 'applied', result: { outcome: { state: 'held', reason: 'Insufficient stock' } } }))
      .toEqual({ label: 'Held for a supervisor', tone: 'warn', detail: 'Insufficient stock' });
    expect(describe({ ...base, status: 'applied', result: { outcome: { state: 'awaiting_dispatch' } } }).tone).toBe('wait');
    expect(describe({ ...base, status: 'rejected', lastError: 'x' }).tone).toBe('bad');
  });
});

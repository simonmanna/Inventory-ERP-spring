import { describe, expect, it } from 'vitest';
import {
  autoAllocate, capView, creditView, describeSale, estimatePrice, lineFor, lineTotal, orderOp, orderTotal, paymentOp,
  quoteOp, unsyncedOrders, type PriceList,
} from '../offline-sales';
import type { SyncOp } from '@/lib/offline/types';

const product = { id: 'p1', name: 'Cement', salesPrice: 100, categoryId: 'cat', tax: { rate: 18, isInclusive: false } };
const today = '2026-11-21';
const lists: PriceList[] = [
  { id: 'default', name: 'Default', isDefault: true, items: [
    { id: 'a', scope: 'all', compute: 'discount', value: 5, minQuantity: 0 },
  ] },
  { id: 'trade', name: 'Trade', items: [
    { id: 'c', scope: 'category', productCategoryId: 'cat', compute: 'discount', value: 10, minQuantity: 0 },
    { id: 'p', scope: 'product', productId: 'p1', compute: 'fixed', value: 90, minQuantity: 10 },
    { id: 'x', scope: 'product', productId: 'p1', compute: 'fixed', value: 1, minQuantity: 0, validTo: '2026-01-01' },
    { id: 'o', scope: 'product', productId: 'p1', compute: 'fixed', value: 2, minQuantity: 0, partnerId: 'someone-else' },
  ] },
];

describe('field sales offline (ADR-S083 O5)', () => {
  it('estimates from the customer pricelist (product > category, quantity breaks, dates, partner) or the default', () => {
    expect(estimatePrice({ product, quantity: 1, customer: { id: 'c1', pricelistId: 'trade' }, priceLists: lists, today }))
      .toEqual({ unitPrice: 100, discountPercent: 10, source: 'price_list' });
    expect(estimatePrice({ product, quantity: 12, customer: { id: 'c1', pricelistId: 'trade' }, priceLists: lists, today }))
      .toEqual({ unitPrice: 90, discountPercent: 0, source: 'price_list' });
    expect(estimatePrice({ product, quantity: 1, customer: { id: 'c2' }, priceLists: lists, today }).discountPercent).toBe(5);
    expect(estimatePrice({ product, quantity: 1, priceLists: [], today })).toEqual({ unitPrice: 100, discountPercent: 0, source: 'list' });
    expect(estimatePrice({ product, quantity: 1, priceLists: [{ id: 's', name: 'S', isDefault: true, items: [
      { id: 's1', scope: 'all', compute: 'surcharge', value: 10 }] }], today }).unitPrice).toBeCloseTo(110);
  });

  it('totals lines as the server does: discount, then exclusive tax; a manual discount replaces the rule', () => {
    const l = lineFor(product, 2, { unitPrice: 100, discountPercent: 10, source: 'price_list' });
    expect(lineTotal(l)).toBeCloseTo(212.4); // 2 × 100 × 0.9 × 1.18
    expect(lineTotal({ ...l, manualDiscountPercent: 20 })).toBeCloseTo(188.8);
    expect(lineTotal({ ...l, taxInclusive: true })).toBeCloseTo(180);
    expect(orderTotal([l, l])).toBe(424.8);
  });

  it('warns on credit from the snapshot less unsynced orders; a hold blocks', () => {
    expect(creditView({ id: 'c', creditHold: true }, 10, 0).state).toBe('blocked');
    expect(creditView({ id: 'c', limit: 1000, headroom: 300 }, 200, 50)).toEqual({ state: 'ok', headroomAfter: 50 });
    const over = creditView({ id: 'c', limit: 1000, headroom: 300 }, 200, 150);
    expect(over.state).toBe('warn');
    expect(over.headroomAfter).toBe(-50);
    expect(creditView({ id: 'c', limit: 0, headroom: null }, 1e9, 0).state).toBe('ok');
  });

  it('counts unsynced orders and enforces the offline cap', () => {
    const op = (status: SyncOp['status'], total: number, partnerId = 'c1') => ({ opId: Math.random().toString(),
      deviceSeq: 1, type: 'salesOrder.create', actorUserId: 'u', occurredAt: '', payload: { partnerId, expectedTotal: total },
      status, attempts: 0, nextAttemptAt: 0, createdAt: 0, updatedAt: 0 }) as SyncOp;
    const u = unsyncedOrders([op('queued', 100), op('in_flight', 50, 'c2'), op('applied', 999), op('rejected', 999)]);
    expect(u).toEqual({ total: 150, byCustomer: { c1: 100, c2: 50 } });
    expect(capView(200, u.total, 40).ok).toBe(true);
    expect(capView(200, u.total, 60).ok).toBe(false);
    expect(capView(0, 1e9, 1e9).ok).toBe(true);
  });

  it('builds order and quote ops: server-priced lines, the expected total for the audit', () => {
    const l = lineFor(product, 2, { unitPrice: 100, discountPercent: 10, source: 'price_list' });
    const o = orderOp({ clientId: 'c', partnerId: 'pa', lines: [l, { ...l, productId: 'p2', quantity: 0 }], paymentMode: 'credit' });
    expect(o.type).toBe('salesOrder.create');
    expect(o.payload).toMatchObject({ partnerId: 'pa', expectedTotal: 212.4, confirm: true });
    expect(o.payload.lines).toEqual([{ productId: 'p1', variantId: undefined, quantity: 2, discountPercent: undefined,
      expectedUnitPrice: 100 }]);
    expect(() => orderOp({ clientId: 'c', partnerId: '', lines: [l] })).toThrow('customer');
    expect(() => orderOp({ clientId: 'c', partnerId: 'pa', lines: [] })).toThrow('item');
    expect(quoteOp({ clientId: 'q', partnerId: 'pa', lines: [{ ...l, manualDiscountPercent: 15 }] }).payload.lines)
      .toEqual([{ productId: 'p1', variantId: undefined, quantity: 2, discountPercent: 15, expectedUnitPrice: 100 }]);
  });

  it('allocates oldest due first and refuses allocations above the amount', () => {
    const inv = [{ id: 'b', documentNumber: 'B', partnerId: 'c', amountResidual: 80, dueDate: '2026-12-01' },
      { id: 'a', documentNumber: 'A', partnerId: 'c', amountResidual: 50, dueDate: '2026-11-01' }];
    expect(autoAllocate(100, inv)).toEqual({ a: 50, b: 50 });
    expect(autoAllocate(500, inv)).toEqual({ a: 50, b: 80 });
    expect(paymentOp({ clientId: 'p', partnerId: 'c', amount: 100, paymentMethod: 'cash', allocations: { a: 50, b: 0 } }).payload)
      .toMatchObject({ amount: 100, allocations: [{ documentId: 'a', amount: 50 }] });
    expect(() => paymentOp({ clientId: 'p', partnerId: 'c', amount: 10, paymentMethod: 'cash', allocations: { a: 50 } }))
      .toThrow('more than');
    expect(() => paymentOp({ clientId: 'p', partnerId: 'c', amount: 0, paymentMethod: 'cash', allocations: {} })).toThrow();
  });

  it('describes the server outcome', () => {
    const base = { opId: 'o', deviceSeq: 1, type: 'salesOrder.create', actorUserId: 'u', occurredAt: '', payload: {},
      attempts: 1, nextAttemptAt: 0, createdAt: 0, updatedAt: 0 } as Omit<SyncOp, 'status'>;
    expect(describeSale({ ...base, status: 'queued' }).tone).toBe('wait');
    expect(describeSale({ ...base, status: 'applied', result: { outcome: { state: 'confirmed' } } }).tone).toBe('ok');
    expect(describeSale({ ...base, status: 'applied', result: { outcome: { state: 'price_review', reason: 'x' } } }))
      .toEqual({ label: 'Price differs — in review', tone: 'warn', detail: 'x' });
    expect(describeSale({ ...base, status: 'rejected' }).tone).toBe('bad');
  });
});

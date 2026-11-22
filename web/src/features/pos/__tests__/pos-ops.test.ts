import 'fake-indexeddb/auto';
import { afterEach, describe, expect, it } from 'vitest';
import { SyncEngine } from '@/lib/offline/engine';
import { InProcessLocks } from '@/lib/offline/lock';
import { SyncStore } from '@/lib/offline/store';
import { FakeServer } from '@/lib/offline/__tests__/fake-server';
import {
  attentionOps, NeedsConnectionError, openOfflineShifts, opFor, provisionalNumber, recordOffline, refusal, resolveForRest,
} from '../pos-ops';
import { menuFromRows } from '../offline-shapes';
import { DEFAULT_POLICY } from '@/lib/offline/types';

let n = 0;
const engines: SyncEngine[] = [];
const H = 3_600_000;

async function device(server = new FakeServer(), opts: { synced?: boolean } = { synced: true }) {
  const store = new SyncStore(`pos-ops-${++n}`);
  const engine = new SyncEngine({ store, transport: server, locks: new InProcessLocks(), channel: null, random: () => 1 });
  engines.push(engine);
  await engine.adopt({ id: 'abcd1234-0000-0000-0000-000000000000', name: 'Till', organizationId: 'org' }, 'tok-1');
  if (opts.synced) await engine.sync();
  return { engine, store, server };
}

afterEach(() => { for (const e of engines.splice(0)) e.dispose(); });

describe('POS writes as sync ops (ADR-S083 O2)', () => {
  it('maps the POS REST writes that have an offline form, and only those', () => {
    expect(opFor('/pos/checkout', { lines: [], occurredAt: 'x', _idemKey: 'k' })).toEqual({ type: 'sale.checkout',
      payload: { lines: [] }, isSale: true });
    expect(opFor('/pos/tabs/t%201/settle', { tenders: [] })).toEqual({ type: 'tab.settle',
      payload: { tenders: [], tableId: 't 1' }, isSale: true });
    expect(opFor('/cash-sessions/close', { sessionId: 's', pendingSyncCount: 0 })?.payload).toEqual({ sessionId: 's' });
    expect(opFor('/cash-sessions/open', {})?.type).toBe('cash_session.open');
    expect(opFor('/cash-sessions/movement', {})?.type).toBe('cash_session.movement');
    expect(opFor('/pos/cash-drawer/openings', {})?.type).toBe('cash_drawer.open');
    expect(opFor('/partners', { name: 'Ann', isCustomer: true })).toEqual({ type: 'customer.upsert',
      payload: { name: 'Ann', phone: null, email: null }, isSale: false });
    expect(opFor('/partners', { name: 'Supplier' })).toBeNull();
    expect(opFor('/pos/orders/o1/settle', {})).toBeNull();
    expect(opFor('/pos/invoices/i1/refund', {})).toBeNull();
  });

  it('refuses manager approvals and an expired or rolled-back offline window', () => {
    const p = { ...DEFAULT_POLICY, offlineWindowHours: 72 };
    expect(refusal({ overrideById: 'm', overridePin: '1234' }, 10 * H, p, 11 * H)).toMatch(/manager PIN/);
    expect(refusal({ managerPin: '9' }, 10 * H, p, 11 * H)).toMatch(/manager PIN/);
    expect(refusal({ overridePin: '' }, 10 * H, p, 11 * H)).toBeNull();
    expect(refusal({}, null, p, 11 * H)).toMatch(/not synced yet/);
    expect(refusal({}, 10 * H, p, 83 * H)).toMatch(/offline too long/);
    expect(refusal({}, 10 * H, p, 9 * H)).toMatch(/clock/);
  });

  it('records a sale under the cart key with a provisional number from the device sequence', async () => {
    const { engine } = await device();
    const op = await recordOffline(engine, { endpoint: '/pos/checkout', body: { lines: [{ productId: 'p' }], tenders: [] },
      actorUserId: 'u1', key: 'cart-key-1' });
    expect(op.opId).toBe('cart-key-1');
    expect(op.payload.clientId).toBe('cart-key-1');
    expect(op.payload.provisionalNumber).toBe(provisionalNumber('abcd1234-0000-0000-0000-000000000000', op.deviceSeq));
    expect(op.payload.provisionalNumber).toMatch(/^OFF-ABCD-\d{6}$/);
    // A double tap records nothing new.
    const again = await recordOffline(engine, { endpoint: '/pos/checkout', body: { lines: [] }, actorUserId: 'u1', key: 'cart-key-1' });
    expect(again.deviceSeq).toBe(op.deviceSeq);
    expect(await engine.store.listOps()).toHaveLength(1);
  });

  it('a customer gets the key as its id; writes with no offline form or a PIN are refused', async () => {
    const { engine } = await device();
    const c = await recordOffline(engine, { endpoint: '/partners', body: { name: 'Ann', isCustomer: true }, actorUserId: 'u',
      key: 'cust-1' });
    expect(c.payload).toEqual({ name: 'Ann', phone: null, email: null, id: 'cust-1' });
    await expect(recordOffline(engine, { endpoint: '/pos/orders/x/settle', body: {}, actorUserId: 'u' }))
      .rejects.toBeInstanceOf(NeedsConnectionError);
    await expect(recordOffline(engine, { endpoint: '/pos/checkout', body: { overrideById: 'm', overridePin: '1' },
      actorUserId: 'u' })).rejects.toThrow(/manager PIN/);
  });

  it('a device that never synced cannot record offline', async () => {
    const { engine } = await device(new FakeServer(), { synced: false });
    await expect(recordOffline(engine, { endpoint: '/pos/checkout', body: {}, actorUserId: 'u' })).rejects.toThrow(/not synced/);
  });

  it('offline shift: open → sale → close; references stay client ids, the server resolves them; the id map follows', async () => {
    const server = new FakeServer();
    const { engine } = await device(server);
    server.offline = true;
    await recordOffline(engine, { endpoint: '/cash-sessions/open', body: { cashRegisterId: 'reg-1', openingFloat: 50 },
      actorUserId: 'u', key: 'shift-1' });
    expect(openOfflineShifts(await engine.store.listOps())).toMatchObject([{ id: 'shift-1', cashRegisterId: 'reg-1',
      openingFloat: '50', offline: true }]);
    const sale = await recordOffline(engine, { endpoint: '/pos/checkout', body: { cashSessionId: 'shift-1', lines: [] },
      actorUserId: 'u', key: 'sale-1' });
    // Online REST write referencing the unsynced shift must go as an op too.
    expect(await resolveForRest(engine, { cashSessionId: 'shift-1' })).toBeNull();
    expect(await resolveForRest(engine, { cashSessionId: 'srv-known' })).toEqual({ cashSessionId: 'srv-known' });
    await recordOffline(engine, { endpoint: '/cash-sessions/close', body: { sessionId: 'shift-1', closingCounted: 50 },
      actorUserId: 'u', key: 'close-1' });
    expect(openOfflineShifts(await engine.store.listOps())).toEqual([]);
    expect(attentionOps(await engine.store.listOps()).pending).toHaveLength(3);

    server.offline = false;
    await engine.sync();
    const sent = server.pushCalls.at(-1)!.ops;
    expect(sent.map((o) => o.type)).toEqual(['cash_session.open', 'sale.checkout', 'cash_session.close']);
    expect((sent[1].payload as { cashSessionId: string }).cashSessionId).toBe('shift-1'); // never rewritten
    expect(await engine.store.resolveId('shift-1')).toBe('srv-shift-1');
    expect(await resolveForRest(engine, { cashSessionId: 'shift-1' })).toEqual({ cashSessionId: 'srv-shift-1' });
    expect((await engine.store.getOp(sale.opId))?.status).toBe('applied');
  });

  it('rebuilds the available menu from pulled scopes like the server does', () => {
    const menu = menuFromRows(
      [{ id: 'b', name: 'B', displayOrder: 2 }, { id: 'a', name: 'A', displayOrder: 1 }, { id: 'x', isAvailable: false },
        { id: 'd', deletedAt: '2026-01-01' }],
      [{ id: 'c2', name: 'Drinks', displayOrder: 2 }, { id: 'c1', name: 'Food', displayOrder: 1 }, { id: 'c3', deletedAt: 'x' }]);
    expect(menu?.items.map((i) => i.id)).toEqual(['a', 'b']);
    expect(menu?.categories.map((c) => c.id)).toEqual(['c1', 'c2']);
    expect(menuFromRows([], [])).toBeUndefined();
  });
});

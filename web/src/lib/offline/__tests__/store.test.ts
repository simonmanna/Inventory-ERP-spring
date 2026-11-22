import 'fake-indexeddb/auto';
import { describe, expect, it } from 'vitest';
import { dbName, SyncStore } from '../store';

let n = 0;
const fresh = () => new SyncStore(`store-test-${++n}`);

describe('SyncStore', () => {
  it('one database per organization', () => {
    expect(dbName('org-a')).not.toBe(dbName('org-b'));
  });

  it('enqueue mints unique opIds and a gap-free deviceSeq, even concurrently', async () => {
    const store = fresh();
    const ops = await Promise.all(Array.from({ length: 20 }, (_, i) =>
      store.enqueue({ type: 'tax.upsert', actorUserId: 'u', payload: { i } })));
    expect(new Set(ops.map((o) => o.opId)).size).toBe(20);
    expect(ops.map((o) => o.deviceSeq).sort((a, b) => a - b)).toEqual(Array.from({ length: 20 }, (_, i) => i + 1));
    expect((await store.listOps()).map((o) => o.deviceSeq)).toEqual(Array.from({ length: 20 }, (_, i) => i + 1));
  });

  it('the sequence survives a reopen (closing the tab)', async () => {
    const store = fresh();
    await store.enqueue({ type: 't', actorUserId: 'u', payload: {} });
    store.close();
    const again = new SyncStore(store.name);
    expect((await again.enqueue({ type: 't', actorUserId: 'u', payload: {} })).deviceSeq).toBe(2);
  });

  it('applyPull: upserts, removes tombstones, replaces full-set scopes, writes the cursor with the rows', async () => {
    const store = fresh();
    await store.applyPull({ taxes: [{ id: 'a' }, { id: 'b' }], paymentMethods: [{ id: 'p1' }, { id: 'p2' }] },
      ['paymentMethods'], { cursor: 'c1' });
    await store.applyPull({ taxes: [{ id: 'a', deletedAt: '2026-01-01' }, { id: 'c' }], paymentMethods: [{ id: 'p2' }] },
      ['paymentMethods'], { cursor: 'c2' });
    expect((await store.getRows('taxes')).map((r) => r.id).sort()).toEqual(['b', 'c']);
    expect((await store.getRows('paymentMethods')).map((r) => r.id)).toEqual(['p2']);
    expect(await store.getMeta('cursor')).toBe('c2');
  });

  it('a failed pull transaction leaves rows and cursor unchanged', async () => {
    const store = fresh();
    await store.applyPull({ taxes: [{ id: 'a' }] }, [], { cursor: 'c1' });
    // A value IndexedDB cannot clone aborts the whole transaction.
    await expect(store.applyPull({ taxes: [{ id: 'b', fn: () => 1 }] }, [], { cursor: 'c2' })).rejects.toBeTruthy();
    expect((await store.getRows('taxes')).map((r) => r.id)).toEqual(['a']);
    expect(await store.getMeta('cursor')).toBe('c1');
  });

  it('wipe keeps only the listed meta keys', async () => {
    const store = fresh();
    await store.enqueue({ type: 't', actorUserId: 'u', payload: {} });
    await store.setMeta({ revokedExport: { ops: [] }, cursor: 'c' });
    await store.wipe(['revokedExport']);
    expect(await store.listOps()).toHaveLength(0);
    expect(await store.getMeta('cursor')).toBeUndefined();
    expect(await store.getMeta('revokedExport')).toEqual({ ops: [] });
  });
});

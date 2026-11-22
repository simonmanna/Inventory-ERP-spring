import 'fake-indexeddb/auto';
import { describe, expect, it } from 'vitest';
import { runSnapshot, SnapshotVerificationError } from '../snapshot';
import { SyncStore } from '../store';
import { TransportError } from '../transport';
import type { SnapshotProgress } from '../types';
import { FakeServer } from './fake-server';

let n = 0;
const fresh = () => new SyncStore(`snapshot-test-${++n}`);

describe('snapshot client (ADR-S083 C5)', () => {
  it('downloads verified pages into staging and activates them atomically with the cursor', async () => {
    const store = fresh();
    const server = new FakeServer({ pageSize: 1 });
    const out = await runSnapshot(store, server, 'tok-1');
    expect(out).toMatchObject({ cursor: 'snap-cursor', rows: 3, resumed: false });
    expect((await store.getRows('taxes')).map((r) => r.id).sort()).toEqual(['t1', 't2']);
    expect(await store.getMeta('cursor')).toBe('snap-cursor');
    expect(await store.getMeta('snapshot')).toBeUndefined();
    expect(await store.stagedCount('snap-1')).toBe(0);
  });

  it('a page failing its checksum is fetched again', async () => {
    const store = fresh();
    const server = new FakeServer();
    server.corruptPages = 2;
    await runSnapshot(store, server, 'tok-1');
    expect(await store.getRows('taxes')).toHaveLength(2);
  });

  it('a page that keeps failing aborts without touching the active dataset', async () => {
    const store = fresh();
    await store.applyPull({ taxes: [{ id: 'old', name: 'Old' }] }, [], { cursor: null });
    const server = new FakeServer();
    server.corruptPages = 99;
    await expect(runSnapshot(store, server, 'tok-1')).rejects.toBeInstanceOf(SnapshotVerificationError);
    expect((await store.getRows('taxes')).map((r) => r.id)).toEqual(['old']);
  });

  it('resumes at the last stored page after an interruption', async () => {
    const store = fresh();
    const server = new FakeServer({ pageSize: 1 });
    const realPage = server.snapshotPage.bind(server);
    let calls = 0;
    server.snapshotPage = async (...a) => {
      if (++calls === 2) throw new TransportError('Failed to fetch', 0);
      return realPage(...a);
    };
    await expect(runSnapshot(store, server, 'tok-1')).rejects.toMatchObject({ status: 0 });
    const progress = await store.getMeta<SnapshotProgress>('snapshot');
    expect(progress?.perScope.taxes.after).toBe('t1');
    expect(await store.getRows('taxes')).toHaveLength(0); // nothing activated yet
    const out = await runSnapshot(store, server, 'tok-1');
    expect(out.resumed).toBe(true);
    expect(server.snapshots.size).toBe(1);
    expect((await store.getRows('taxes')).map((r) => r.id).sort()).toEqual(['t1', 't2']);
  });

  it('an expired snapshot (410) or refused completion (409) starts a new snapshot once', async () => {
    const store = fresh();
    const server = new FakeServer();
    server.expireSnapshotOnce = true;
    await runSnapshot(store, server, 'tok-1');
    expect(server.snapshots.size).toBe(2);
    const store2 = fresh();
    const server2 = new FakeServer();
    server2.failComplete = 409;
    await runSnapshot(store2, server2, 'tok-1');
    expect(server2.snapshots.size).toBe(2);
    expect(await store2.getRows('taxes')).toHaveLength(2);
  });

  it('a failed completion leaves the previous dataset active', async () => {
    const store = fresh();
    await store.applyPull({ taxes: [{ id: 'old' }] }, [], {});
    const server = new FakeServer();
    server.completeSnapshot = async () => {
      throw new TransportError('Failed to fetch', 0);
    };
    await expect(runSnapshot(store, server, 'tok-1')).rejects.toBeInstanceOf(TransportError);
    expect((await store.getRows('taxes')).map((r) => r.id)).toEqual(['old']);
  });
});

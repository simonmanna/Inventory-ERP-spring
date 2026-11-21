import 'fake-indexeddb/auto';
import { afterEach, describe, expect, it } from 'vitest';
import { SyncEngine } from '../engine';
import { InProcessLocks } from '../lock';
import { SyncStore } from '../store';
import { FakeChannelHub, FakeServer } from './fake-server';

let n = 0;
const engines: SyncEngine[] = [];

function setup(server = new FakeServer(), opts: { clock?: () => number; store?: SyncStore; locks?: InProcessLocks;
    channel?: ReturnType<FakeChannelHub['join']> | null } = {}) {
  const store = opts.store ?? new SyncStore(`engine-test-${++n}`);
  const engine = new SyncEngine({ store, transport: server, locks: opts.locks ?? new InProcessLocks(),
    channel: opts.channel ?? null, clock: opts.clock, random: () => 1 });
  engines.push(engine);
  return { store, engine, server };
}

async function registered(server = new FakeServer(), opts: Parameters<typeof setup>[1] = {}) {
  const ctx = setup(server, opts);
  await ctx.engine.adopt({ id: 'dev-1', name: 'Till 1', organizationId: 'org-1' }, 'tok-1');
  return ctx;
}

const op = (type = 'tax.upsert', payload: Record<string, unknown> = { id: 'x', name: 'VAT', rate: 1 }) =>
  ({ type, actorUserId: 'u1', payload });

afterEach(() => {
  for (const e of engines.splice(0)) e.dispose();
});

describe('SyncEngine', () => {
  it('is unregistered until a device is adopted; the token is stored encrypted', async () => {
    const { engine, store } = setup();
    expect((await engine.sync())?.state).toBe('unregistered');
    await engine.adopt({ id: 'dev-1', name: 'Till', organizationId: 'org-1' }, 'tok-1');
    expect(await engine.vault.get('deviceToken')).toBe('tok-1');
    const sealed = await store.getMeta<{ data: ArrayBuffer }>('vault:deviceToken');
    expect(new TextDecoder().decode(sealed!.data)).not.toContain('tok-1');
  });

  it('first sync: snapshot into staging, activated with the cursor; then deltas from that cursor', async () => {
    const { engine, store, server } = await registered();
    const s = await engine.sync();
    expect(s?.state).toBe('idle');
    expect((await store.getRows('taxes')).map((r) => r.id).sort()).toEqual(['t1', 't2']);
    expect(server.snapshots.get('snap-1')?.completed).toBe(true);
    // The delta pull after the snapshot moved the cursor, stored the policy and the rotated token.
    expect(await store.getMeta('cursor')).toBe('c1');
    expect((await store.getMeta<{ offlineWindowHours: number }>('offlinePolicy'))?.offlineWindowHours).toBe(48);
    expect(await engine.vault.get('deviceToken')).toBe('tok-2');
    server.delta = { taxes: [{ id: 't1', name: 'VAT', rate: 18, deletedAt: '2026-11-21T00:00:00Z' },
      { id: 't3', name: 'New', rate: 5 }] };
    await engine.sync();
    expect((await store.getRows('taxes')).map((r) => r.id).sort()).toEqual(['t2', 't3']); // tombstone removed t1
  });

  it('pushes queued ops in device order before pulling, and records id mappings', async () => {
    const { engine, store, server } = await registered();
    const a = await engine.enqueue(op('customer.upsert', { clientId: 'c-1', name: 'Ann' }));
    const b = await engine.enqueue(op());
    expect([a.deviceSeq, b.deviceSeq]).toEqual([1, 2]);
    await engine.sync();
    expect(server.pushCalls[0].ops.map((o) => o.opId)).toEqual([a.opId, b.opId]);
    expect((await store.getOp(a.opId))?.status).toBe('applied');
    expect(await store.resolveId('c-1')).toBe('srv-c-1');
  });

  it('lost response: the op is retried under the SAME opId and applied exactly once', async () => {
    let now = 1_000_000;
    const { engine, store, server } = await registered(new FakeServer(), { clock: () => now });
    const o = await engine.enqueue(op());
    server.loseNextResponse = true;
    expect((await engine.sync())?.state).toBe('offline');
    const after = await store.getOp(o.opId);
    expect(after?.status).toBe('queued');
    expect(after?.attempts).toBe(1);
    // Not due yet: backoff.
    await engine.sync();
    expect(server.pushCalls).toHaveLength(1);
    now += 10 * 60_000;
    await engine.sync();
    expect(server.pushCalls).toHaveLength(2);
    expect(server.pushCalls[1].ops[0].opId).toBe(o.opId);
    expect(server.pushCalls[1].key).not.toBe(server.pushCalls[0].key);
    expect((await store.getOp(o.opId))?.status).toBe('applied');
    expect(server.applications.get(o.opId)).toBe(1);
  });

  it('crash mid-push: an op left in_flight is re-sent by the next tab under the same opId, applied once', async () => {
    const server = new FakeServer();
    const { store } = await registered(server);
    const first = new SyncEngine({ store, transport: server, locks: new InProcessLocks(), channel: null });
    engines.push(first);
    const o = await first.enqueue(op());
    // The server applied it, then the tab died before recording the answer.
    server.loseNextResponse = true;
    const flying = { ...(await store.getOp(o.opId))!, status: 'in_flight' as const };
    await store.putOps([flying]);
    await server.push('tok-1', 'dev-1', [flying], 'lost').catch(() => undefined);
    // A new tab (fresh engine) picks it up.
    const second = new SyncEngine({ store, transport: server, locks: new InProcessLocks(), channel: null });
    engines.push(second);
    await second.sync();
    expect((await store.getOp(o.opId))?.status).toBe('applied');
    expect(server.applications.get(o.opId)).toBe(1);
    expect(server.pushCalls.at(-1)?.ops[0].opId).toBe(o.opId);
  });

  it('a re-sent identical batch replays the stored per-op results (batch Idempotency-Key)', async () => {
    const { engine, store, server } = await registered();
    const o = await engine.enqueue(op());
    const snapshot = (await store.getOp(o.opId))!;
    await engine.sync();
    const key = server.pushCalls[0].key;
    const replay = await server.push('tok-1', 'dev-1', [{ ...snapshot, status: 'in_flight' }], key);
    expect(replay.results[0].status).toBe('applied');
    expect(server.applications.get(o.opId)).toBe(1);
  });

  it('retryable failure: same opId later; definitive rejection: never retried; quarantine: held', async () => {
    let now = 5_000_000;
    const { engine, store, server } = await registered(new FakeServer(), { clock: () => now });
    const retry = await engine.enqueue(op('tax.upsert', { id: 'r' }));
    const reject = await engine.enqueue(op('tax.upsert', { id: 'j' }));
    server.decide = (o) => {
      const id = (o.payload as { id: string }).id;
      if (id === 'r' && !server.ledger.size) return { code: 'SERVER_ERROR', retryable: true, httpStatus: 500 };
      if (id === 'j') return { code: 'BUSINESS_RULE', retryable: false, httpStatus: 400 };
      return null;
    };
    await engine.sync();
    expect((await store.getOp(retry.opId))?.status).toBe('queued');
    expect((await store.getOp(reject.opId))?.status).toBe('rejected');
    // Device order: the queued op (seq 1) is not due, so nothing after it is pushed either.
    const third = await engine.enqueue(op('tax.upsert', { id: 'ok' }));
    await engine.sync();
    expect(server.pushCalls).toHaveLength(1);
    now += 10 * 60_000;
    server.decide = (o) => ((o.payload as { id: string }).id === 'j' ? { code: 'BUSINESS_RULE' } : null);
    await engine.sync();
    const sent = server.pushCalls.slice(1).flatMap((c) => c.ops.map((o) => o.opId));
    expect(sent).toEqual([retry.opId, third.opId]);
    expect(sent).not.toContain(reject.opId);
    expect((await store.getOp(retry.opId))?.status).toBe('applied');
    const s = await engine.refreshStatus('idle');
    expect(s.rejected).toBe(1);
  });

  it('a malformed op is isolated (400 batch) and the rest still sync', async () => {
    const { engine, store, server } = await registered();
    const good = await engine.enqueue(op());
    const bad = await engine.enqueue(op('invalid', {}));
    const good2 = await engine.enqueue(op('tax.upsert', { id: 'y' }));
    await engine.sync();
    expect((await store.getOp(good.opId))?.status).toBe('applied');
    expect((await store.getOp(bad.opId))?.status).toBe('rejected');
    expect((await store.getOp(bad.opId))?.code).toBe('VALIDATION');
    expect((await store.getOp(good2.opId))?.status).toBe('applied');
    expect(server.applications.get(good.opId)).toBe(1);
  });

  it('5xx on the whole batch: ops stay queued with their opIds', async () => {
    const { engine, store, server } = await registered();
    const o = await engine.enqueue(op());
    server.failNextPush = true;
    expect((await engine.sync())?.state).toBe('error');
    expect((await store.getOp(o.opId))?.status).toBe('queued');
  });

  it('multi-tab: only one tab syncs at a time; the other is told the outcome over the channel', async () => {
    const server = new FakeServer();
    const hub = new FakeChannelHub();
    const locks = new InProcessLocks();
    const { store, engine: tabA } = await registered(server, { locks, channel: hub.join() });
    const tabB = new SyncEngine({ store, transport: server, locks, channel: hub.join() });
    engines.push(tabB);
    const seen: string[] = [];
    tabB.subscribe((s) => seen.push(s.state));
    const o = await tabA.enqueue(op());
    const [a, b] = await Promise.all([tabA.sync(), tabB.sync()]);
    expect([a, b].filter((x) => x === null)).toHaveLength(1);
    expect(server.applications.get(o.opId)).toBe(1);
    expect(server.pushCalls).toHaveLength(1);
    expect(seen).toContain('syncing');
    expect(seen.at(-1)).toBe('idle');
  });

  it('token expired: pull refused, ops still pushed, local data kept', async () => {
    const { engine, store, server } = await registered();
    await engine.sync();
    server.expired = true;
    const o = await engine.enqueue(op());
    const s = await engine.sync();
    expect(s?.state).toBe('token_expired');
    expect((await store.getOp(o.opId))?.status).toBe('applied');
    expect(await store.getRows('taxes')).toHaveLength(2);
  });

  it('revoked: unsynced ops exported for a supervisor, local data wiped', async () => {
    const { engine, store, server } = await registered();
    await engine.sync();
    server.revoked = true;
    const o = await engine.enqueue(op());
    const s = await engine.sync();
    expect(s?.state).toBe('revoked');
    const exp = await engine.revokedExport();
    expect(exp?.ops.map((x) => x.opId)).toEqual([o.opId]);
    expect(exp?.ops[0].status).toBe('held');
    expect(await store.getRows('taxes')).toHaveLength(0);
    expect(await engine.vault.get('deviceToken')).toBeUndefined();
    expect((await engine.sync())?.state).toBe('unregistered');
  });

  it('unknown token (e.g. deleted device): unregistered, nothing wiped', async () => {
    const { engine, store, server } = await registered();
    await engine.sync();
    await engine.enqueue(op());
    server.tokens.clear();
    expect((await engine.sync())?.state).toBe('unregistered');
    expect(await store.getRows('taxes')).toHaveLength(2);
    expect(await store.listOps(['queued'])).toHaveLength(1);
  });

  it('426 on pull: push still drained, state upgrade_required', async () => {
    const { engine, store, server } = await registered();
    await engine.sync();
    server.protocolGone = true;
    const o = await engine.enqueue(op());
    expect((await engine.sync())?.state).toBe('upgrade_required');
    expect((await store.getOp(o.opId))?.status).toBe('applied');
  });

  it('offline: nothing lost, state offline', async () => {
    const { engine, store, server } = await registered();
    server.offline = true;
    const o = await engine.enqueue(op());
    expect((await engine.sync())?.state).toBe('offline');
    expect((await store.getOp(o.opId))?.opId).toBe(o.opId);
  });
});

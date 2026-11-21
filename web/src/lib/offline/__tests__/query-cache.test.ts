import { afterEach, describe, expect, it, vi } from 'vitest';
import { QueryClient } from '@tanstack/react-query';
import type { PersistedClient } from '@tanstack/react-query-persist-client';
import {
  cacheKey, createScopedPersister, dropPermissionBound, permissionHash, restore, scopeWatcher, serialize,
  type CacheScope, type KeyValue,
} from '../query-cache';
import {
  installReachability, isReachable, markReachable, markUnreachable, PROBE_MS, resetReachability, subscribeReachability,
} from '../reachability';

const H = 3_600_000;
const NOW = 1_000 * H;

function q(key: unknown[], data: unknown, at = NOW - H, status = 'success') {
  return { queryKey: key, queryHash: JSON.stringify(key), state: { data, dataUpdatedAt: at, status } };
}

function client(queries: ReturnType<typeof q>[]): PersistedClient {
  return { timestamp: NOW, buster: 'v2', clientState: { mutations: [], queries } } as unknown as PersistedClient;
}

function memory(): KeyValue & { map: Map<string, unknown> } {
  const map = new Map<string, unknown>();
  return { map, get: async (k) => map.get(k), set: async (k, v) => { map.set(k, v); }, del: async (k) => { map.delete(k); } };
}

const families = (c: PersistedClient | undefined) => c?.clientState.queries.map((x) => x.queryKey[0]);

describe('on-device query cache (ADR-S083 S3 / O3)', () => {
  it('writes only allowed, successful results', () => {
    const json = serialize(client([q(['products'], [1]), q(['license'], { k: 1 }), q(['api-clients'], []),
      q(['brand-new-screen'], []), q(['invoices'], [], NOW, 'error')]));
    expect(families(JSON.parse(json))).toEqual(['products']);
  });

  it('size cap keeps the most recently fetched results', () => {
    const big = 'x'.repeat(400);
    const entries = [q(['products'], big, NOW - 3 * H), q(['taxes'], big, NOW - H), q(['uoms'], big, NOW - 2 * H),
      q(['currencies'], 'small', NOW - 9 * H)];
    const size = (i: number) => JSON.stringify(entries[i]).length;
    // Room for two big results and the small one: the oldest big one is dropped, the small old one still fits.
    const json = serialize(client(entries), size(1) + size(2) + size(3));
    expect(families(JSON.parse(json))).toEqual(['taxes', 'uoms', 'currencies']);
  });

  it('restore applies per-class retention', () => {
    const json = serialize(client([q(['products'], 1, NOW - 6 * 24 * H), q(['partners'], 1, NOW - 4 * 24 * H),
      q(['invoices'], 1, NOW - 2 * 24 * H), q(['trial-balance'], 1, NOW - 25 * H), q(['pnl'], 1, NOW - 2 * H)]));
    expect(families(restore(json, true, NOW))).toEqual(['pnl', 'invoices', 'products']); // newest first
  });

  it('restore after a permission change keeps catalogue only', () => {
    const json = serialize(client([q(['products'], 1), q(['invoices'], 1), q(['pnl'], 1)]));
    expect(families(restore(json, false, NOW))).toEqual(['products']);
    expect(restore('{broken', true, NOW)).toBeUndefined();
  });

  it('entries are isolated per organization and user, and stamped with the permissions', async () => {
    const kv = memory();
    let scope: CacheScope | null = { organizationId: 'A', userId: 'u1', permissions: ['invoice:read'] };
    const p = createScopedPersister(kv, () => scope, { now: () => NOW });
    await p.persistClient(client([q(['invoices'], ['a-secret'])]));
    scope = { organizationId: 'B', userId: 'u1', permissions: ['invoice:read'] };
    expect(await p.restoreClient()).toBeUndefined();
    scope = { organizationId: 'A', userId: 'u2', permissions: ['invoice:read'] };
    expect(await p.restoreClient()).toBeUndefined();
    scope = { organizationId: 'A', userId: 'u1', permissions: ['invoice:read'] };
    expect(families(await p.restoreClient())).toEqual(['invoices']);
    scope = { organizationId: 'A', userId: 'u1', permissions: [] };
    expect(families(await p.restoreClient())).toEqual([]);
    scope = null;
    await p.persistClient(client([q(['products'], 1)]));
    expect([...kv.map.keys()]).toEqual([cacheKey({ organizationId: 'A', userId: 'u1' })]);
  });

  it('permission hash ignores order and duplicates', () => {
    expect(permissionHash(['b', 'a', 'a'])).toBe(permissionHash(['a', 'b']));
    expect(permissionHash(['a'])).not.toBe(permissionHash(['a', 'b']));
  });

  it('sign-out and organization switch wipe the old entry; a permission change drops bound results', async () => {
    const kv = memory();
    const wipe = vi.fn();
    const perms = vi.fn();
    const watch = scopeWatcher(kv, wipe, perms);
    const a: CacheScope = { organizationId: 'A', userId: 'u1', permissions: ['x'] };
    kv.map.set(cacheKey(a), 'stored');
    await watch(a); // start-up
    expect(wipe).not.toHaveBeenCalled();
    await watch({ ...a, permissions: ['x'] });
    expect(perms).not.toHaveBeenCalled();
    await watch({ ...a, permissions: ['x', 'y'] });
    expect(perms).toHaveBeenCalledTimes(1);
    await watch({ organizationId: 'B', userId: 'u1', permissions: ['x', 'y'] });
    expect(wipe).toHaveBeenCalledTimes(1);
    expect(kv.map.has(cacheKey(a))).toBe(false);
    kv.map.set(cacheKey({ organizationId: 'B', userId: 'u1' }), 'stored');
    await watch(null);
    expect(wipe).toHaveBeenCalledTimes(2);
    expect(kv.map.size).toBe(0);
  });

  it('dropPermissionBound removes records and reports from memory, keeps catalogue', () => {
    const qc = new QueryClient();
    qc.setQueryData(['products'], [1]);
    qc.setQueryData(['invoices'], [2]);
    qc.setQueryData(['trial-balance'], [3]);
    dropPermissionBound(qc);
    expect(qc.getQueryCache().getAll().map((x) => x.queryKey[0])).toEqual(['products']);
  });
});

describe('server reachability (ADR-S083 O3)', () => {
  afterEach(() => {
    resetReachability();
    vi.useRealTimers();
  });

  it('is sticky until an answer, and notifies once per change', () => {
    const seen: boolean[] = [];
    subscribeReachability(() => seen.push(isReachable()));
    markUnreachable();
    markUnreachable();
    expect(isReachable()).toBe(false);
    markReachable();
    expect(seen).toEqual([false, true]);
  });

  it('probes while unreachable and recovers when the probe succeeds', async () => {
    vi.useFakeTimers();
    let up = false;
    const probe = vi.fn(async () => up);
    installReachability(probe);
    markUnreachable();
    await vi.advanceTimersByTimeAsync(PROBE_MS);
    expect(probe).toHaveBeenCalledTimes(1);
    expect(isReachable()).toBe(false);
    up = true;
    await vi.advanceTimersByTimeAsync(PROBE_MS);
    expect(isReachable()).toBe(true);
    await vi.advanceTimersByTimeAsync(5 * PROBE_MS);
    expect(probe).toHaveBeenCalledTimes(2); // stops probing once reachable
  });
});

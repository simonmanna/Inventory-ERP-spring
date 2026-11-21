/**
 * ADR-S083 S3 / O3 — the on-device copy of React Query results behind the read-only offline view.
 *
 * - Only results the cache policy allows are written (`cache-policy.ts`; unclassified = never).
 * - Isolation: one IndexedDB entry per organization + user. Logging out, switching organization or user, and device
 *   revocation wipe it. A change in the user's permissions drops every permission-dependent result (memory and disk),
 *   keeping only catalogue data.
 * - Retention per class is applied on restore; a size cap keeps the most recently fetched results.
 */
import type { PersistedClient, Persister } from '@tanstack/react-query-persist-client';
import type { QueryClient } from '@tanstack/react-query';
import { classify, withinRetention, type CacheClass } from './cache-policy';

export interface CacheScope {
  organizationId: string;
  userId: string;
  permissions: readonly string[];
}

export interface KeyValue {
  get(key: string): Promise<unknown>;
  set(key: string, value: unknown): Promise<void>;
  del(key: string): Promise<void>;
}

interface Stored {
  permHash: string;
  savedAt: number;
  client: string;
}

export const CACHE_PREFIX = 'erp-query-cache:';
/** Default cap on the serialized size of the stored results (characters). */
export const DEFAULT_CAP_CHARS = 20_000_000;

export function cacheKey(scope: Pick<CacheScope, 'organizationId' | 'userId'>): string {
  return `${CACHE_PREFIX}${scope.organizationId}:${scope.userId}`;
}

/** Stable, order-independent fingerprint of a permission list (FNV-1a; not a secret). */
export function permissionHash(permissions: readonly string[]): string {
  let h = 0x811c9dc5;
  for (const ch of [...new Set(permissions)].sort().join('\n')) {
    h ^= ch.charCodeAt(0);
    h = Math.imul(h, 0x01000193) >>> 0;
  }
  return h.toString(16).padStart(8, '0');
}

const PERMISSION_BOUND: ReadonlySet<CacheClass> = new Set(['cache-permissioned', 'cache-strict']);

type DehydratedQuery = PersistedClient['clientState']['queries'][number];

/** The results to keep on disk: allowed classes only, newest first, within the size cap. Returns the JSON. */
export function serialize(client: PersistedClient, capChars = DEFAULT_CAP_CHARS): string {
  const parts: Array<{ json: string; at: number }> = [];
  for (const q of client.clientState.queries) {
    if (classify(q.queryKey) === 'never' || q.state.status !== 'success') continue;
    parts.push({ json: JSON.stringify(q), at: q.state.dataUpdatedAt });
  }
  parts.sort((a, b) => b.at - a.at);
  const kept: string[] = [];
  let size = 0;
  for (const p of parts) {
    if (size + p.json.length > capChars) continue; // a smaller, older result may still fit
    kept.push(p.json);
    size += p.json.length;
  }
  return `{"timestamp":${client.timestamp},"buster":${JSON.stringify(client.buster)},"clientState":{"mutations":${
    JSON.stringify(client.clientState.mutations ?? [])},"queries":[${kept.join(',')}]}}`;
}

/** Parse a stored client, dropping what is past retention, no longer allowed, or bound to old permissions. */
export function restore(json: string, sameUserPermissions: boolean, now: number): PersistedClient | undefined {
  let client: PersistedClient;
  try {
    client = JSON.parse(json) as PersistedClient;
  } catch {
    return undefined;
  }
  const queries = (client.clientState?.queries ?? []).filter((q: DehydratedQuery) => {
    const cls = classify(q.queryKey);
    if (!withinRetention(cls, q.state.dataUpdatedAt, now)) return false;
    return sameUserPermissions || !PERMISSION_BOUND.has(cls);
  });
  return { ...client, clientState: { ...client.clientState, queries } };
}

/** A persister whose storage entry follows the signed-in organization + user. */
export function createScopedPersister(storage: KeyValue, scope: () => CacheScope | null,
    opts: { capChars?: number; now?: () => number } = {}): Persister {
  const now = opts.now ?? Date.now;
  return {
    async persistClient(client) {
      const s = scope();
      if (!s) return;
      const value: Stored = { permHash: permissionHash(s.permissions), savedAt: now(), client: serialize(client, opts.capChars) };
      await storage.set(cacheKey(s), value);
    },
    async restoreClient() {
      const s = scope();
      if (!s) return undefined;
      const stored = (await storage.get(cacheKey(s))) as Stored | undefined;
      if (!stored || typeof stored.client !== 'string') return undefined;
      return restore(stored.client, stored.permHash === permissionHash(s.permissions), now());
    },
    async removeClient() {
      const s = scope();
      if (s) await storage.del(cacheKey(s));
    },
  };
}

/** Drop permission-dependent results from memory (the next persist rewrites the disk copy without them). */
export function dropPermissionBound(queryClient: QueryClient): void {
  queryClient.removeQueries({ predicate: (q) => PERMISSION_BOUND.has(classify(q.queryKey)) });
}

/**
 * Watch the signed-in scope and wipe the cache when it changes. `onWipe` lets the caller also drop in-memory data.
 * Returns the function to call with each new scope (null = signed out).
 */
export function scopeWatcher(storage: KeyValue, onWipe: () => void, onPermissionsChanged: () => void) {
  let current: CacheScope | null | undefined;
  return async (next: CacheScope | null): Promise<void> => {
    const prev = current;
    current = next;
    if (prev === undefined) return; // first observation (start-up): nothing to compare
    const sameScope = !!prev && !!next && prev.organizationId === next.organizationId && prev.userId === next.userId;
    if (sameScope) {
      if (permissionHash(prev.permissions) !== permissionHash(next.permissions)) onPermissionsChanged();
      return;
    }
    if (prev) await storage.del(cacheKey(prev));
    onWipe();
  };
}

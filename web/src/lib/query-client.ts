import { QueryClient, onlineManager } from '@tanstack/react-query';
import { get, set, del } from 'idb-keyval';
import { useAuthStore } from '@/stores/auth.store';
import { isReachable, subscribeReachability } from '@/lib/offline/reachability';
import {
  createScopedPersister, dropPermissionBound, scopeWatcher, type CacheScope, type KeyValue,
} from '@/lib/offline/query-cache';
import { classify, family, ruleFor } from '@/lib/offline/cache-policy';

export const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      retry: 1,
      refetchOnWindowFocus: false,
      staleTime: 30_000,
      refetchIntervalInBackground: false,
      // Cached data must outlive the tab. The default 5-minute gcTime would
      // evict it long before someone reopens a screen mid-outage.
      gcTime: 7 * 24 * 60 * 60 * 1000,
    },
    mutations: {
      // A write always runs, online or not, so its caller sees the outcome (the POS offline paths and the offline
      // write guard depend on it). Paused mutations would sit silently until reconnect.
      networkMode: 'always',
    },
  },
});

/*
 * ADR-S083 O3 — React Query follows the server's reachability, not navigator.onLine: while the server is
 * unreachable, queries pause and keep showing what was cached (no error screens); they resume and refetch when it
 * answers again. Queries that read local offline data themselves (the POS) use `networkMode: 'always'`.
 */
onlineManager.setEventListener((setOnline) => {
  setOnline(isReachable());
  return subscribeReachability(() => setOnline(isReachable()));
});

const storage: KeyValue = {
  get: (k) => get(k),
  set: (k, v) => set(k, v),
  del: (k) => del(k),
};

function currentScope(): CacheScope | null {
  const s = useAuthStore.getState();
  if (!s.user?.id || !s.organization?.id) return null;
  return { organizationId: s.organization.id, userId: s.user.id, permissions: s.permissions ?? [] };
}

/**
 * The on-device copy behind the read-only offline view: IndexedDB, one entry per organization + user, only what
 * `lib/offline/cache-policy.ts` allows, with per-class retention and a size cap (`lib/offline/query-cache.ts`).
 */
export const queryPersister = createScopedPersister(storage, currentScope);

/**
 * Keys built at run time are invisible to the source scan in cache-policy.test.ts. In development, name each
 * unclassified family once in the console so it gets a rule (until then it is never stored).
 */
const warned = new Set<string>();
function warnUnclassified(queryKey: readonly unknown[]): void {
  const name = family(queryKey);
  if (warned.has(name) || ruleFor(name) !== null) return;
  warned.add(name);
  console.warn(`[offline cache] query family "${name}" has no rule in lib/offline/cache-policy.ts; it is not kept offline.`);
}

export const persistOptions = {
  persister: queryPersister,
  maxAge: 7 * 24 * 60 * 60 * 1000,
  // Bump when cached shapes change, so old entries are dropped rather than
  // rehydrated into a UI that no longer understands them.
  buster: 'v2',
  dehydrateOptions: {
    shouldDehydrateQuery: (query: { queryKey: readonly unknown[]; state: { status: string } }) => {
      if (query.state.status !== 'success') return false;
      if (import.meta.env.DEV) warnUnclassified(query.queryKey);
      return classify(query.queryKey) !== 'never';
    },
  },
};

/* Wipe on sign-out, organization or user switch; drop permission-bound results when permissions change. */
const watch = scopeWatcher(storage, () => queryClient.clear(), () => dropPermissionBound(queryClient));
void watch(currentScope());
useAuthStore.subscribe(() => void watch(currentScope()));

// The single pre-O3 entry (catalogue for every user of the browser) is replaced by the scoped ones.
void del('pos-query-cache').catch(() => undefined);

/** Device revoked (ADR-S083 S2): the engine wipes its own store; the query cache goes with it. */
export async function wipeQueryCache(): Promise<void> {
  queryClient.clear();
  await queryPersister.removeClient();
}

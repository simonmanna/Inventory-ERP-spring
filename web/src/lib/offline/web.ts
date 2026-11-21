/**
 * ADR-S083 O1 — browser wiring of the sync engine: one engine per organization, device registration through the
 * admin's session, and automatic passes. Screens adopt this in O2 (POS) and O3 (read-only cache); nothing calls it
 * yet, so O1 changes no existing behaviour.
 */
import { api, getApiBaseUrl } from '@/lib/api';
import { SyncEngine } from './engine';
import { requestPersistence } from './storage';
import { SyncStore } from './store';
import { FetchTransport } from './transport';

const engines = new Map<string, SyncEngine>();

export function syncEngineFor(organizationId: string): SyncEngine {
  let e = engines.get(organizationId);
  if (!e) {
    e = new SyncEngine({ store: SyncStore.forOrganization(organizationId), transport: new FetchTransport(getApiBaseUrl()) });
    engines.set(organizationId, e);
  }
  return e;
}

/**
 * Register this browser as a sync device (`POST /sync/devices/register`, needs `organization:update`) and keep its
 * token in the encrypted vault. Asks for persistent storage so the outbox is not evicted under storage pressure.
 */
export async function registerThisBrowser(organizationId: string, name: string, branchId?: string): Promise<void> {
  const body: Record<string, unknown> = { name, platform: 'web' };
  if (branchId) body.branchId = branchId;
  const { data } = await api.post<{ id: string; deviceToken: string }>('/sync/devices/register', body);
  await syncEngineFor(organizationId).adopt({ id: data.id, name, organizationId }, data.deviceToken);
  await requestPersistence();
}

/**
 * Run a pass now, when the connection returns, when the tab becomes visible, after an op is queued (debounced) and
 * every `intervalMs`. Returns a stop function.
 */
export function startAutoSync(engine: SyncEngine, intervalMs = 30_000): () => void {
  if (typeof window === 'undefined') return () => undefined;
  let timer: ReturnType<typeof setTimeout> | null = null;
  const run = () => {
    void engine.sync().catch(() => undefined);
  };
  const soon = () => {
    if (timer) clearTimeout(timer);
    timer = setTimeout(run, 500);
  };
  const onVisible = () => {
    if (document.visibilityState === 'visible') soon();
  };
  window.addEventListener('online', soon);
  document.addEventListener('visibilitychange', onVisible);
  const interval = setInterval(run, intervalMs);
  const unsubscribe = engine.subscribe(() => undefined);
  const enqueue = engine.enqueue.bind(engine);
  engine.enqueue = async (op, opts) => {
    const created = await enqueue(op, opts);
    soon();
    return created;
  };
  soon();
  return () => {
    window.removeEventListener('online', soon);
    document.removeEventListener('visibilitychange', onVisible);
    clearInterval(interval);
    if (timer) clearTimeout(timer);
    unsubscribe();
    engine.enqueue = enqueue;
  };
}

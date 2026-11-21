/**
 * ADR-S083 O2 — POS reads that keep working offline on a registered device.
 *
 * Online the REST answer is used (and, on a registered device, kept as the read's last good answer). When the request
 * gets no answer, the read is served from the pulled scopes when the server's shape can be rebuilt from them (menu,
 * payment methods), else from that last good answer. A refusal (any HTTP status) is never masked.
 */
import type { SyncEngine } from '@/lib/offline/engine';
import { menuFromRows } from './offline-shapes';
import { posEngine } from './pos-sync';


export async function offlineRead<T>(key: string, fetch: () => Promise<T>,
    fromScopes?: (engine: SyncEngine) => Promise<T | undefined>): Promise<T> {
  try {
    const value = await fetch();
    void posEngine().then((e) => e?.store.setMeta({ [`lastGood:${key}`]: value })).catch(() => undefined);
    return value;
  } catch (e) {
    if ((e as { response?: unknown })?.response) throw e;
    const engine = await posEngine();
    if (!engine) throw e;
    const scoped = fromScopes ? await fromScopes(engine) : undefined;
    if (scoped !== undefined) return scoped;
    const last = await engine.store.getMeta<T>(`lastGood:${key}`);
    if (last !== undefined) return last;
    throw e;
  }
}

export async function menuFromScopes<T>(engine: SyncEngine): Promise<T | undefined> {
  return menuFromRows(await engine.store.getRows('menuItems'), await engine.store.getRows('menuCategories')) as T | undefined;
}

export async function paymentMethodsFromScopes<T>(engine: SyncEngine): Promise<T | undefined> {
  const rows = await engine.store.getRows('paymentMethods');
  return rows.length ? ([...rows].sort((a, b) => Number(a.sortOrder ?? 0) - Number(b.sortOrder ?? 0)) as T) : undefined;
}

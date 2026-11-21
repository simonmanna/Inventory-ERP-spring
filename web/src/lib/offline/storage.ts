/**
 * ADR-S083 O1 — storage durability.
 *
 * Browsers may evict IndexedDB under storage pressure unless the origin holds persistent storage. The engine asks
 * for it (`navigator.storage.persist()`), and detects an eviction: a marker is written to `localStorage` when the
 * device registers; if the marker exists but the database has no `device` entry, the local store was lost. Any ops
 * queued in it are gone from this device — the UI must say so instead of silently starting over, and the device must
 * register again (its server-side record and any ops it already pushed are untouched).
 */
import type { SyncStore } from './store';

export interface StorageHealth {
  persisted: boolean | null;
  usage?: number;
  quota?: number;
  evicted: boolean;
}

function markerKey(storeName: string): string {
  return `${storeName}:registered`;
}

function ls(): Storage | null {
  try {
    return typeof localStorage !== 'undefined' ? localStorage : null;
  } catch {
    return null;
  }
}

export function markRegistered(storeName: string, deviceId: string): void {
  ls()?.setItem(markerKey(storeName), deviceId);
}

export function clearRegistered(storeName: string): void {
  ls()?.removeItem(markerKey(storeName));
}

export async function requestPersistence(): Promise<boolean | null> {
  const s = typeof navigator !== 'undefined' ? navigator.storage : undefined;
  if (!s?.persist) return null;
  try {
    return (await s.persisted?.()) || (await s.persist());
  } catch {
    return null;
  }
}

export async function checkStorage(store: SyncStore): Promise<StorageHealth> {
  const marker = ls()?.getItem(markerKey(store.name)) ?? null;
  const device = await store.getMeta('device');
  const evicted = marker !== null && !device;
  let usage: number | undefined;
  let quota: number | undefined;
  const s = typeof navigator !== 'undefined' ? navigator.storage : undefined;
  try {
    const est = await s?.estimate?.();
    usage = est?.usage;
    quota = est?.quota;
  } catch {
    /* estimate unsupported */
  }
  let persisted: boolean | null = null;
  try {
    persisted = (await s?.persisted?.()) ?? null;
  } catch {
    persisted = null;
  }
  return { persisted, usage, quota, evicted };
}

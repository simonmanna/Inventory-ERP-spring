/**
 * ADR-S083 O3 — is the server reachable? One sticky answer for the whole app.
 *
 * A request that got no HTTP answer marks the server unreachable; any answered request (even a 4xx) or a successful
 * health probe marks it reachable again. While unreachable, a light probe runs every `PROBE_MS` so the app notices
 * the server coming back even when nothing else is requesting. Deliberately NOT `navigator.onLine` as the source of
 * truth: a till that hosts the API itself reads "offline" when the cable is out while the server is fine — the
 * browser's events only trigger a probe.
 */
import { useSyncExternalStore } from 'react';

export const PROBE_MS = 10_000;

let reachable = true;
let since = Date.now();
const listeners = new Set<() => void>();
let probe: (() => Promise<boolean>) | null = null;
let timer: ReturnType<typeof setInterval> | null = null;

function emit() {
  for (const l of [...listeners]) l();
}

function stopProbing() {
  if (timer) clearInterval(timer);
  timer = null;
}

async function probeOnce() {
  if (!probe) return;
  if (await probe().catch(() => false)) markReachable();
}

function startProbing() {
  if (timer || !probe || typeof setInterval === 'undefined') return;
  timer = setInterval(() => void probeOnce(), PROBE_MS);
}

export function markReachable(): void {
  if (reachable) return;
  reachable = true;
  since = Date.now();
  stopProbing();
  emit();
}

export function markUnreachable(): void {
  if (!reachable) return;
  reachable = false;
  since = Date.now();
  startProbing();
  emit();
}

export function isReachable(): boolean {
  return reachable;
}

/** When the current state (reachable or not) began. */
export function reachabilitySince(): number {
  return since;
}

export function subscribeReachability(fn: () => void): () => void {
  listeners.add(fn);
  return () => listeners.delete(fn);
}

/** Install the health probe and browser hints (called once at start-up). */
export function installReachability(healthProbe: () => Promise<boolean>): void {
  probe = healthProbe;
  if (!reachable) startProbing();
  if (typeof window === 'undefined') return;
  window.addEventListener('online', () => void probeOnce());
  window.addEventListener('offline', () => {
    markUnreachable();
    void probeOnce(); // a till hosting the API answers anyway
  });
}

/** True while the server answers. Re-renders on change. */
export function useOnline(): boolean {
  return useSyncExternalStore(subscribeReachability, isReachable, () => true);
}

/**
 * Screens that record work offline themselves — the POS (ADR-S083 O2), the warehouse tablet (O4) and field sales
 * (O5). The read-only rules of O3 (write guard, disabled submits, offline banner) are for the rest of the back office.
 */
export function isOfflineCapablePath(path = typeof window !== 'undefined' ? window.location.pathname : ''): boolean {
  return path === '/pos' || path.startsWith('/pos/') || path === '/warehouse/tablet' || path === '/sales/field';
}

/**
 * True while writes cannot reach the server from this screen: unreachable and outside the POS (which records work
 * offline itself). Write controls (form submits, confirm actions) disable themselves on it.
 */
export function useWritesBlocked(): boolean {
  return !useOnline() && !isOfflineCapablePath();
}

/** Test hook. */
export function resetReachability(): void {
  stopProbing();
  reachable = true;
  since = Date.now();
  listeners.clear();
  probe = null;
}

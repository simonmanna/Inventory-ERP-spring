import type { AxiosRequestConfig } from 'axios';
import { api } from '@/lib/api';
import { tx } from '@/lib/i18n/tx';

/**
 * Money-moving requests carry an Idempotency-Key that stays the SAME until the
 * server gives a definitive answer. A timeout, dropped connection, 5xx or a
 * double-click retries with the original key, so the server replays the first
 * result instead of posting the money twice. The key is released only on
 * success or on a definitive client error (4xx), after which a corrected
 * submission is genuinely a new operation.
 *
 * Keys are keyed by (method, url, payload) and kept in sessionStorage so a page
 * reload during an outage still reuses them.
 *
 * What happened to the attempts made with a key is kept with it, because the
 * server answers a key it still holds as unfinished with 409 OPERATION_PENDING,
 * and retrying that forever helps nobody:
 *  - an attempt that got no answer (timeout, lost connection, a gateway error
 *    page) or that is still running may have been applied. The key is kept, a
 *    retry replays the result once the server has one, and a fresh attempt is
 *    never offered;
 *  - an attempt the API itself answered with a server error (a 5xx carrying the
 *    API's error body) failed. When the server then still holds its key, the
 *    error offers `startFreshAttempt()` (see `idempotencyOf`), which drops the
 *    key so the next submit is a new operation. So does a server that marks the
 *    pending answer `safeToRetry`. The user is asked to check the list first;
 *    the key is never dropped silently.
 */
const PREFIX = 'idem:';

/** What the client knows about the earlier attempts made with one key. */
type History = 'none' | 'failed' | 'unknown';

interface Slot {
  /** The Idempotency-Key. */
  k: string;
  h: History;
}

/** How a keyed request ended, as far as the client can tell. */
export type AttemptOutcome =
  /** Refused (4xx): nothing was applied. The key is released; the next submit is a new operation. */
  | 'rejected'
  /** The API answered with a server error (5xx). The key is kept. */
  | 'failed'
  /** No answer (timeout, lost connection, gateway error page) or still running: it may be applied. The key is kept. */
  | 'unknown'
  /** 409 OPERATION_PENDING: the server still holds an earlier attempt with this key as unfinished. */
  | 'pending';

export interface IdempotencyInfo {
  outcome: AttemptOutcome;
  /** What to tell the user when the server's own message would mislead (absent: use the server's). */
  message?: string;
  /**
   * Present only on `pending` when the earlier attempts failed on the server (or the server says a retry is safe):
   * drops the key so the next submit is a fresh operation. Absent whenever an earlier attempt may have been applied.
   */
  startFreshAttempt?: () => void;
}

const INFO = 'idempotency';

/** What the idempotent request layer knows about a failed request; undefined for any other error. */
export function idempotencyOf(error: unknown): IdempotencyInfo | undefined {
  if (!error || typeof error !== 'object') return undefined;
  const info = (error as Record<string, unknown>)[INFO];
  return info && typeof info === 'object' ? (info as IdempotencyInfo) : undefined;
}

function fingerprint(method: string, url: string, body: unknown): string {
  const text = `${method} ${url} ${JSON.stringify(body ?? {})}`;
  // FNV-1a 32-bit ×2 (different seeds) — compact, stable, collision-safe enough for a per-tab cache key.
  let a = 0x811c9dc5;
  let b = 0x01000193 ^ text.length;
  for (let i = 0; i < text.length; i++) {
    const c = text.charCodeAt(i);
    a = Math.imul(a ^ c, 0x01000193) >>> 0;
    b = Math.imul(b ^ c, 0x5bd1e995) >>> 0;
  }
  return `${PREFIX}${a.toString(16)}${b.toString(16)}`;
}

function store(): Storage | null {
  try { return window.sessionStorage; } catch { return null; }
}

function newKey(): string {
  try { return crypto.randomUUID(); } catch { return `${Date.now().toString(36)}-${Math.random().toString(36).slice(2)}`; }
}

function readSlot(s: Storage | null, name: string): Slot | null {
  let raw: string | null = null;
  try { raw = s?.getItem(name) ?? null; } catch { raw = null; }
  if (!raw) return null;
  if (raw.startsWith('{')) {
    try {
      const v = JSON.parse(raw) as Partial<Slot>;
      if (typeof v.k === 'string' && v.k) return { k: v.k, h: v.h === 'failed' || v.h === 'unknown' ? v.h : 'none' };
    } catch { /* not ours: treated as a bare key below */ }
  }
  // A bare key, stored before attempts were tracked: nothing is known about how its attempt ended.
  return { k: raw, h: 'unknown' };
}

function writeSlot(s: Storage | null, name: string, slot: Slot): void {
  try { s?.setItem(name, JSON.stringify(slot)); } catch { /* storage unavailable: the key lives for this call only */ }
}

export function operationKeyFor(method: string, url: string, body: unknown): { key: string; release: () => void } {
  const name = fingerprint(method, url, body);
  const s = store();
  let slot = readSlot(s, name);
  if (!slot) {
    slot = { k: newKey(), h: 'none' };
    writeSlot(s, name, slot);
  }
  return { key: slot.k, release: () => { try { s?.removeItem(name); } catch { /* ignore */ } } };
}

type ErrorResponse = { status?: number; data?: unknown };

function responseOf(error: unknown): ErrorResponse | undefined {
  return (error as { response?: ErrorResponse } | null)?.response;
}

function bodyOf(error: unknown): Record<string, unknown> {
  const data = responseOf(error)?.data;
  return data && typeof data === 'object' ? (data as Record<string, unknown>) : {};
}

/** The same key is running in another request right now (the server's insert race). */
function isConcurrent(error: unknown): boolean {
  const message = bodyOf(error).message;
  return responseOf(error)?.status === 409 && typeof message === 'string' && message.includes('being processed concurrently');
}

function classify(error: unknown): AttemptOutcome {
  const status = responseOf(error)?.status;
  if (typeof status !== 'number') return 'unknown';
  const data = bodyOf(error);
  if (status === 409 && data.code === 'OPERATION_PENDING') return 'pending';
  // Running in another request right now: as uncertain as an unanswered attempt.
  if (isConcurrent(error)) return 'unknown';
  if (status >= 400 && status < 500) return 'rejected';
  // A 5xx with the API's own error body means the handler ran and failed. Anything else (a proxy's 502/504 page)
  // means the API may still be working on it.
  if (status >= 500 && typeof data.statusCode === 'number') return 'failed';
  return 'unknown';
}

function pendingInfo(error: unknown, slot: Slot, release: () => void): IdempotencyInfo {
  const data = bodyOf(error);
  const recovery = data.recovery && typeof data.recovery === 'object' ? (data.recovery as Record<string, unknown>) : {};
  if (slot.h === 'failed' || data.safeToRetry === true || recovery.safeToRetry === true) {
    return {
      outcome: 'pending',
      message: tx('The last attempt of this action failed on the server, but the server still holds it as unfinished. Check the list: if it is not there, start a fresh attempt.'),
      startFreshAttempt: release,
    };
  }
  return {
    outcome: 'pending',
    message: tx('An earlier attempt of this action has no final result yet and may already be saved. Check the list before you try again.'),
  };
}

async function send<T>(method: 'post' | 'patch', url: string, body?: unknown, config?: AxiosRequestConfig): Promise<T> {
  const name = fingerprint(method, url, body);
  const s = store();
  const slot = readSlot(s, name) ?? { k: newKey(), h: 'none' as History };
  writeSlot(s, name, slot);
  const release = () => {
    // Only this key: a success or a fresh attempt may already have replaced or removed the slot.
    if (readSlot(s, name)?.k === slot.k) { try { s?.removeItem(name); } catch { /* ignore */ } }
  };
  try {
    const res = await api.request<T>({ ...config, method, url, data: body, headers: { ...(config?.headers ?? {}), 'Idempotency-Key': slot.k } });
    release();
    return res.data;
  } catch (error) {
    const outcome = classify(error);
    let info: IdempotencyInfo;
    if (outcome === 'rejected') {
      release();
      info = { outcome };
    } else if (outcome === 'pending') {
      info = pendingInfo(error, slot, release);
    } else {
      // Keep the key and remember the worst that may have happened with it, unless the slot was released meanwhile
      // (an overlapping attempt with this key succeeded): a finished operation must not get its key back.
      const current = readSlot(s, name);
      if (current?.k === slot.k) {
        writeSlot(s, name, { k: slot.k, h: outcome === 'failed' && current.h !== 'unknown' ? 'failed' : 'unknown' });
      }
      if (outcome === 'failed') info = { outcome };
      else if (isConcurrent(error)) info = { outcome, message: tx('This action is already being processed. Wait a moment, then check the list before you try again.') };
      else info = { outcome, message: tx('The server did not answer, so this may already be saved. Check the list before you submit it again.') };
    }
    if (error && typeof error === 'object') (error as Record<string, unknown>)[INFO] = info;
    throw error;
  }
}

export const idempotentPost = <T = any>(url: string, body?: unknown, config?: AxiosRequestConfig) => send<T>('post', url, body, config);
export const idempotentPatch = <T = any>(url: string, body?: unknown, config?: AxiosRequestConfig) => send<T>('patch', url, body, config);

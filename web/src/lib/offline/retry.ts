/**
 * ADR-S083 S1 — client retry rules, as pure functions.
 *
 * | Situation                                   | Client                                   |
 * |---------------------------------------------|------------------------------------------|
 * | No answer / 5xx / 429 for the whole batch   | back to `queued`, SAME opId, backoff     |
 * | `applied` / `replayed`                      | `applied` (mapping + finalNumbers kept)  |
 * | `failed`, `retryable: true`                 | back to `queued`, SAME opId, backoff     |
 * | `failed`, quarantined (revoked / expired)   | `held` (supervisor recovery only)        |
 * | `failed`, not retryable                     | `rejected` — never retried automatically |
 *
 * An opId is never re-minted: a correction is a NEW op that a person creates with `supersedesOpId`.
 */
import type { PushResult, SyncOp } from './types';

/** Server codes that mean "retry the same op later" (contract `retryableCodes`). */
export const RETRYABLE_CODES = new Set(['DEPENDENCY_FAILED', 'SERVER_ERROR', 'OPERATION_PENDING', 'OPERATION_INCOMPLETE']);

/** Server codes that quarantine an op for supervisor recovery. */
export const HELD_CODES = new Set(['DEVICE_REVOKED', 'DEVICE_TOKEN_EXPIRED']);

export const BASE_BACKOFF_MS = 2_000;
export const MAX_BACKOFF_MS = 5 * 60_000;

/**
 * Exponential backoff with full jitter: attempt 1 → up to 2 s, 2 → 4 s, ... capped at 5 min. `random` is injectable
 * so tests are deterministic.
 */
export function backoffMs(attempts: number, random: () => number = Math.random): number {
  const ceiling = Math.min(MAX_BACKOFF_MS, BASE_BACKOFF_MS * 2 ** Math.max(0, attempts - 1));
  return Math.max(BASE_BACKOFF_MS / 2, Math.floor(ceiling * random()));
}

/** Whether a failed result should be retried under the same opId. Servers before ADR-S083 send no `retryable`. */
export function isRetryable(r: PushResult): boolean {
  if (typeof r.retryable === 'boolean') return r.retryable;
  if (r.code && RETRYABLE_CODES.has(r.code)) return true;
  return r.httpStatus >= 500 || r.httpStatus === 424 || r.httpStatus === 429;
}

/** The op after the server answered for it. */
export function applyResult(op: SyncOp, r: PushResult, now: number, random?: () => number): SyncOp {
  const base = { ...op, updatedAt: now, httpStatus: r.httpStatus, code: r.code };
  if (r.status === 'applied' || r.status === 'replayed') {
    return {
      ...base,
      status: 'applied',
      lastError: undefined,
      result: { mapping: r.mapping, finalNumbers: r.finalNumbers },
    };
  }
  if (r.code && HELD_CODES.has(r.code)) {
    return { ...base, status: 'held', lastError: r.error ?? undefined };
  }
  if (isRetryable(r)) {
    return {
      ...base,
      status: 'queued',
      attempts: op.attempts + 1,
      nextAttemptAt: now + backoffMs(op.attempts + 1, random),
      lastError: r.error ?? undefined,
    };
  }
  return { ...base, status: 'rejected', lastError: r.error ?? undefined };
}

/** The op after the batch got no usable answer (network, 5xx, 429): same opId, later. */
export function applyTransientFailure(op: SyncOp, message: string, now: number, random?: () => number): SyncOp {
  return {
    ...op,
    status: 'queued',
    attempts: op.attempts + 1,
    nextAttemptAt: now + backoffMs(op.attempts + 1, random),
    lastError: message,
    updatedAt: now,
  };
}

/**
 * Ops to send now, in deviceSeq order: `in_flight` (left over from a crash — re-sent under the same opId) and due
 * `queued` ops. Stops at the first op that is not due, so ops are never pushed out of device order.
 */
export function dueOps(ops: SyncOp[], now: number, limit: number): SyncOp[] {
  const out: SyncOp[] = [];
  for (const op of [...ops].sort((a, b) => a.deviceSeq - b.deviceSeq)) {
    if (op.status !== 'queued' && op.status !== 'in_flight') continue;
    if (op.status === 'queued' && op.nextAttemptAt > now) break;
    out.push(op);
    if (out.length >= limit) break;
  }
  return out;
}

import { describe, expect, it } from 'vitest';
import { applyResult, backoffMs, dueOps, isRetryable, MAX_BACKOFF_MS } from '../retry';
import type { SyncOp } from '../types';

const op = (seq: number, over: Partial<SyncOp> = {}): SyncOp => ({ opId: `o${seq}`, deviceSeq: seq, type: 't',
  actorUserId: 'u', occurredAt: '', payload: {}, status: 'queued', attempts: 0, nextAttemptAt: 0, createdAt: 0,
  updatedAt: 0, ...over });

describe('S1 retry rules', () => {
  it('backoff grows and is capped', () => {
    expect(backoffMs(1, () => 1)).toBe(2000);
    expect(backoffMs(3, () => 1)).toBe(8000);
    expect(backoffMs(50, () => 1)).toBe(MAX_BACKOFF_MS);
    expect(backoffMs(3, () => 0)).toBe(1000);
  });

  it('retryability follows the server flag, else the code, else the status (pre-S083 servers)', () => {
    const base = { opId: 'o', status: 'failed' as const, error: 'x' };
    expect(isRetryable({ ...base, httpStatus: 500, retryable: false })).toBe(false);
    expect(isRetryable({ ...base, httpStatus: 400, retryable: true })).toBe(true);
    expect(isRetryable({ ...base, httpStatus: 409, code: 'OPERATION_PENDING' })).toBe(true);
    expect(isRetryable({ ...base, httpStatus: 503 })).toBe(true);
    expect(isRetryable({ ...base, httpStatus: 400 })).toBe(false);
  });

  it('maps results to op states, keeping the opId', () => {
    const o = op(1);
    expect(applyResult(o, { opId: 'o1', status: 'replayed', httpStatus: 201, error: null, mapping: { a: 'b' } }, 5))
      .toMatchObject({ opId: 'o1', status: 'applied', result: { mapping: { a: 'b' } } });
    expect(applyResult(o, { opId: 'o1', status: 'failed', httpStatus: 403, error: 'r', code: 'DEVICE_REVOKED',
      retryable: false }, 5).status).toBe('held');
    expect(applyResult(o, { opId: 'o1', status: 'failed', httpStatus: 400, error: 'r', code: 'BUSINESS_RULE',
      retryable: false }, 5).status).toBe('rejected');
    const r = applyResult(o, { opId: 'o1', status: 'failed', httpStatus: 500, error: 'r', code: 'SERVER_ERROR',
      retryable: true }, 5, () => 1);
    expect(r).toMatchObject({ opId: 'o1', status: 'queued', attempts: 1, nextAttemptAt: 2005 });
  });

  it('due ops go in device order and stop at the first op that is not due', () => {
    const ops = [op(3), op(1, { status: 'in_flight' }), op(2, { nextAttemptAt: 100 }), op(4, { status: 'rejected' })];
    expect(dueOps(ops, 50, 10).map((o) => o.deviceSeq)).toEqual([1]);
    expect(dueOps(ops, 150, 10).map((o) => o.deviceSeq)).toEqual([1, 2, 3]);
    expect(dueOps(ops, 150, 2).map((o) => o.deviceSeq)).toEqual([1, 2]);
  });
});

import { useRef } from 'react';

/**
 * True when the server answered that an earlier request with this key is still unfinished (409 OPERATION_PENDING,
 * or the same key running concurrently): it may yet be applied, so the key must not be replaced by a new one.
 */
function stillRunning(error: unknown): boolean {
  const res = (error as { response?: { status?: number; data?: { code?: unknown; message?: unknown } } })?.response;
  if (res?.status !== 409) return false;
  return res.data?.code === 'OPERATION_PENDING'
    || (typeof res.data?.message === 'string' && res.data.message.includes('being processed concurrently'));
}

/**
 * One `Idempotency-Key` per user action. The key is kept while the action has no final server answer (a network
 * failure, or the server still holding an earlier attempt as unfinished: either may yet apply it, so the retry must
 * replay rather than post twice) and is dropped as soon as the server answered, success or error, so the next action
 * gets a fresh key.
 */
export function useIdempotencyKey() {
  const key = useRef<string | null>(null);
  return {
    /** Request config carrying the current action's key (minted on first use). */
    config: () => {
      if (!key.current) key.current = crypto.randomUUID();
      return { headers: { 'Idempotency-Key': key.current } };
    },
    /** Call from onSuccess / onError: keeps the key only when the request has no final answer yet. */
    settle: (error?: unknown) => {
      const answered = !error || (!!(error as { response?: unknown })?.response && !stillRunning(error));
      if (answered) key.current = null;
    },
  };
}

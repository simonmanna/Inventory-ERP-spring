import { useRef } from 'react';

/**
 * One `Idempotency-Key` per user action. The key is kept while the action has no server answer (a network failure:
 * the server may already have applied it, so the retry must replay rather than post twice) and is dropped as soon as
 * the server answered, success or error, so the next action gets a fresh key.
 */
export function useIdempotencyKey() {
  const key = useRef<string | null>(null);
  return {
    /** Request config carrying the current action's key (minted on first use). */
    config: () => {
      if (!key.current) key.current = crypto.randomUUID();
      return { headers: { 'Idempotency-Key': key.current } };
    },
    /** Call from onSuccess / onError: keeps the key only when the request never got an answer. */
    settle: (error?: unknown) => {
      const answered = !error || !!(error as { response?: unknown })?.response;
      if (answered) key.current = null;
    },
  };
}

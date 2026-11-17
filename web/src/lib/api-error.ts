import { toast } from 'sonner';
import { wasNotified } from '@/lib/api';
import { idempotencyOf } from '@/lib/idempotent-request';
import { notify } from '@/lib/notify';
import { tx } from '@/lib/i18n/tx';

/**
 * Pull a human-readable message out of an axios error.
 *
 * Nest's ValidationPipe returns `message` as a string[] — one entry per failed
 * constraint — so rendering it straight into a toast prints "[object Object]"
 * or a bare comma-blob, and a validation failure looks like an opaque server
 * error on screen. Every HR mutation surfaces real, actionable 400s (overlapping
 * leave, a duplicate link, a missing termination reason), so those messages are
 * worth showing properly.
 */
export function apiErrorMessage(err: unknown, fallback: string): string {
  const message = (err as { response?: { data?: { message?: unknown } } })?.response?.data?.message;
  if (Array.isArray(message)) return message.filter(Boolean).map(String).join('; ') || fallback;
  if (typeof message === 'string' && message.trim()) return message;
  return fallback;
}

/**
 * The message to show for a failed request, e.g. inline in a dialog: what the idempotent request layer knows (an
 * unanswered or held-back operation) first, then the server's own words, then `fallback`.
 */
export function describeApiError(err: unknown, fallback: string): string {
  return idempotencyOf(err)?.message ?? apiErrorMessage(err, fallback);
}

/**
 * Toast a failed request once.
 *
 * The API client already toasts a 403, a 5xx, a licence refusal and the first lost connection of an outage; those
 * are not toasted a second time here. An operation the server still holds as unfinished (409 OPERATION_PENDING) is
 * explained, and when every earlier attempt failed on the server the toast offers to start a fresh attempt.
 */
export function notifyApiError(err: unknown, fallback: string): void {
  const info = idempotencyOf(err);
  if (info?.outcome === 'pending' && info.message) {
    const fresh = info.startFreshAttempt;
    if (fresh) {
      toast.error(info.message, {
        duration: 20_000,
        action: {
          label: tx('Start a fresh attempt'),
          onClick: () => {
            fresh();
            notify.info(tx('Submit it again to make a fresh attempt.'));
          },
        },
      });
    } else {
      notify.error(info.message);
    }
    return;
  }
  if (wasNotified(err)) return;
  notify.error(describeApiError(err, fallback));
}

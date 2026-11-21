import axios, { AxiosError, type AxiosRequestConfig } from 'axios';
import { useAuthStore } from '@/stores/auth.store';
import { getPosToken, posSessionRevoked } from '@/features/pos/pos-session';
import { toast } from 'sonner';
import { notify } from '@/lib/notify';
import { tx } from '@/lib/i18n/tx';
import { refusalFromResponse, useLicenseStore } from '@/features/license/license.store';
import { installReachability, isPosPath, isReachable, markReachable, markUnreachable } from '@/lib/offline/reachability';

function getApiBaseUrl(): string {
  const raw = import.meta.env.VITE_API_URL;
  if (!raw) return '/api/v1';
  return `${raw.replace(/\/$/, '')}/api/v1`;
}

const baseURL = getApiBaseUrl();
export { getApiBaseUrl };

/**
 * Absolutize a server-provided asset path (e.g. a signed file download URL) so
 * it loads from the API origin, not the web dev-server origin.
 *
 * The API mints image URLs as relative paths like `/api/v1/files/:id/download`.
 * When VITE_API_URL points at a different origin (dev: API on :3000, web on
 * :5173) and there is no proxy, an <img src="/api/v1/..."> would hit :5173 and
 * 404. Prefix the API origin in that case. Already-absolute (http) URLs and the
 * proxy case (no VITE_API_URL) pass through unchanged.
 */
export function resolveAssetUrl(path?: string | null): string | undefined {
  if (!path) return undefined;
  if (/^https?:\/\//i.test(path)) return path;
  const raw = import.meta.env.VITE_API_URL as string | undefined;
  const origin = raw ? raw.replace(/\/$/, '') : '';
  return `${origin}${path.startsWith('/') ? '' : '/'}${path}`;
}

export const api = axios.create({
  baseURL,
  withCredentials: false,
  timeout: 30_000,
  headers: { 'Content-Type': 'application/json' },
});

/* ── Server reachability ─────────────────────────────────────────────────────
 * A response with no HTTP status never reached the API: the network is down, the
 * service is stopped, or the request timed out. The POS needs to know that
 * WITHOUT probing, so it can stop walking into a full timeout on every keystroke
 * and keep selling against the local cart instead.
 *
 * Deliberately NOT `navigator.onLine`: the common install runs the API and the
 * database on the till itself, where an unplugged cable reads as "offline" while
 * the server is perfectly reachable. Only a request that actually failed counts,
 * and any answered request — including a 4xx — clears it.
 */
/**
 * True when this client already showed the user a message for the failed request (a 403, a 5xx, a licence refusal,
 * the first lost connection of an outage). Call sites check it so one failure is not toasted twice.
 */
export function wasNotified(error: unknown): boolean {
  return !!error && typeof error === 'object' && (error as { notified?: unknown }).notified === true;
}

export function noteServerUnreachable(): void { markUnreachable(); }
export function noteServerReachable(): void { markReachable(); }
/**
 * True while the server is known unreachable: from a request that got no answer until any answered request or a
 * successful health probe (ADR-S083 O3, `lib/offline/reachability.ts`).
 */
export function serverUnreachable(): boolean {
  return !isReachable();
}

installReachability(async () => {
  try {
    const controller = new AbortController();
    const t = setTimeout(() => controller.abort(), 4000);
    const res = await fetch(`${baseURL}/health`, { signal: controller.signal, credentials: 'omit' });
    clearTimeout(t);
    return res.ok;
  } catch {
    return false;
  }
});

/* ── Offline write guard (ADR-S083 O3) ───────────────────────────────────────
 * While the server is known unreachable, a write from the back office is refused at once with "Needs connection"
 * instead of waiting for a timeout: the offline view is read-only. Exempt: the POS screens (their own offline
 * handling — the replay queue and sync ops — runs only there, ADR-S083 O2) and sign-in.
 */
export const NEEDS_CONNECTION_MESSAGE = 'Needs connection — changes cannot be saved while the server is unreachable.';

export function isNeedsConnection(e: unknown): boolean {
  return !!e && typeof e === 'object' && (e as { needsConnection?: unknown }).needsConnection === true;
}

export function guardExempt(config: AxiosRequestConfig): boolean {
  if ((config.url ?? '').includes('/auth/')) return true;
  return isPosPath();
}

function needsConnectionError(config: AxiosRequestConfig): AxiosError {
  const message = tx(NEEDS_CONNECTION_MESSAGE);
  const data = { statusCode: 503, code: 'NEEDS_CONNECTION', message };
  return Object.assign(new AxiosError(message, 'ERR_NEEDS_CONNECTION', config as never), {
    needsConnection: true,
    response: { status: 503, statusText: 'Needs connection', data, headers: {}, config },
  });
}

api.interceptors.request.use((config) => {
  const token = useAuthStore.getState().accessToken;
  if (token) {
    config.headers = config.headers ?? {};
    config.headers.Authorization = `Bearer ${token}`;
  }
  // Attribute POS requests to the cashier who PINned in (the backend overrides
  // the request identity from this token; the bearer JWT still bounds the org).
  const posToken = getPosToken();
  if (posToken) {
    config.headers = config.headers ?? {};
    config.headers['X-Pos-User'] = posToken;
  }
  // Money-touching routes require an Idempotency-Key (retry can never charge
  // twice). Callers that manage their own stable key — checkout, settle and
  // the offline replay queue — set it first; everything else gets a fresh
  // per-attempt key. crypto.randomUUID only exists in secure contexts, so
  // fall back for LAN-over-http terminals.
  const method = (config.method || '').toLowerCase();
  if (['post', 'put', 'patch', 'delete'].includes(method) && serverUnreachable() && !guardExempt(config)) {
    return Promise.reject(needsConnectionError(config));
  }
  if (['post', 'put', 'patch', 'delete'].includes(method)) {
    config.headers = config.headers ?? {};
    if (!config.headers['Idempotency-Key']) {
      const uuid = typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function'
        ? crypto.randomUUID()
        : `${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}-${Math.random().toString(36).slice(2, 10)}`;
      config.headers['Idempotency-Key'] = uuid;
    }
  }
  return config;
});

// ---- Automatic refresh on 401 (single-flight) ----
let refreshing: Promise<string | null> | null = null;

async function refresh(): Promise<string | null> {
  const refreshToken = useAuthStore.getState().refreshToken;
  if (!refreshToken) return null;
  try {
    const res = await axios.post<{ accessToken: string; refreshToken: string }>(
      `${baseURL}/auth/refresh`,
      { refreshToken },
    );
    useAuthStore.getState().setTokens(res.data.accessToken, res.data.refreshToken);
    return res.data.accessToken;
  } catch {
    useAuthStore.getState().clear();
    return null;
  }
}

/* ── Licence state ───────────────────────────────────────────────────────────
 * The API advertises the licence on every response it has a view on:
 * `X-License-Warning` while the licence needs attention, and HTTP 402 when a
 * write was refused because it has run out. Both are recorded centrally so one
 * banner and one dialog cover the whole app, rather than every call site having
 * to think about billing.
 *
 * 402 deliberately does NOT toast: an expired licence is not a transient error
 * to be waved away, and a toast per blocked keystroke would be unusable. The
 * blocking dialog in the app shell explains it once, with a way out.
 */
function noteLicenceHeaders(headers: unknown): void {
  const warning = (headers as Record<string, string> | undefined)?.['x-license-warning'];
  if (warning) useLicenseStore.getState().noteWarning(warning);
}

api.interceptors.response.use(
  (r) => { noteServerReachable(); noteLicenceHeaders(r.headers); return r; },
  async (err: AxiosError) => {
    // Refused locally by the offline write guard: the server said nothing, so it is still unreachable.
    if (isNeedsConnection(err)) {
      (err as AxiosError & { userMessage?: string }).userMessage = err.message;
      toast.error(err.message, { id: 'needs-connection' }); // one toast however many writes were tried
      (err as AxiosError & { notified?: boolean }).notified = true;
      throw err;
    }
    const status = err.response?.status;
    // The server answered — whatever it said, it is up.
    if (status) noteServerReachable();
    const original = err.config as (AxiosRequestConfig & { _retry?: boolean }) | undefined;
    // The cashier's POS token was revoked (disabled, deleted, or taken off the
    // tills by HR). The back-office session is fine, so do NOT refresh or sign
    // the terminal out: drop the cashier and return to the PIN screen.
    if (status === 401 && err.response?.headers?.['x-pos-session'] === 'revoked') {
      posSessionRevoked();
      notify.error((err.response?.data as any)?.message || tx('This cashier can no longer use the POS.'));
      throw err;
    }
    // A 401 for a wrong PIN (manager override, approval, void/refund with a manager PIN) is an answer about the PIN,
    // not an expired access token: refreshing and re-sending it would count a second failed attempt against the
    // manager's lockout and hide the real message.
    const pinRequest = !!original && (original.url?.includes('/pos/override') === true
      || (typeof original.data === 'string' && /"(managerPin|overridePin|incomingPin|pin)"\s*:/.test(original.data)));
    if (status === 401 && original && !original._retry && !original.url?.includes('/auth/') && !pinRequest) {
      original._retry = true;
      refreshing = refreshing ?? refresh();
      const newToken = await refreshing;
      refreshing = null;
      if (newToken) {
        original.headers = original.headers ?? {};
        (original.headers as Record<string, string>).Authorization = `Bearer ${newToken}`;
        return api.request(original);
      }
    }
    // Surface the server's actionable message rather than generic Axios text.
    // The API returns { message: string | string[] } on 4xx; expose it as
    // `err.userMessage` so call sites and toasts can show the real reason.
    const body: any = err.response?.data;
    const serverMsg = Array.isArray(body?.message) ? body.message.join('; ') : (typeof body?.message === 'string' ? body.message : undefined);
    (err as any).userMessage = serverMsg;
    // Marks the failures this interceptor tells the user about itself (see `wasNotified`).
    const flag = err as AxiosError & { notified?: boolean };
    noteLicenceHeaders(err.response?.headers);
    if (status === 402) {
      useLicenseStore.getState().noteRefusal(
        refusalFromResponse(body, serverMsg || tx('Your licence does not allow this.')),
      );
      flag.notified = true;
      throw err;
    }
    if (status === 403) { notify.error(serverMsg || tx('Permission denied')); flag.notified = true; }
    if (status && status >= 500) { notify.error(serverMsg || tx('Server error — please retry')); flag.notified = true; }
    // A cancelled request (a component unmounted, a query superseded) says
    // nothing about the server, so it must not be read as an outage.
    if (!status && !axios.isCancel(err) && err.code !== 'ERR_CANCELED') {
      // One notice per outage, not one per request: an offline terminal saves,
      // quotes and polls constantly, and it used to toast on every one of them.
      const alreadyKnown = serverUnreachable();
      noteServerUnreachable();
      if (!alreadyKnown) { notify.error(tx('No connection to the server — work is kept on this device')); flag.notified = true; }
    }
    throw err;
  },
);

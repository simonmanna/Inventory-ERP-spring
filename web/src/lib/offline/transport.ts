/**
 * ADR-S083 O1 — HTTP transport for the device-token sync routes. Uses `fetch` (not the app's axios instance): the
 * sync routes authenticate with `X-Device-Token`, never the user's bearer, and the engine needs the raw body of
 * snapshot pages to verify their checksum.
 *
 * Every request carries `X-Sync-Protocol`. A response without an HTTP status (network down, DNS, CORS, timeout) is a
 * `TransportError` with `status === 0`; the engine treats it as "offline", never as a rejection.
 */
import type { PullResponse, PushResponse, SnapshotComplete, SnapshotOpen, SnapshotPage, SyncOp } from './types';
import { SYNC_PROTOCOL } from './types';

export class TransportError extends Error {
  constructor(
    message: string,
    /** HTTP status; 0 = the request never got an answer. */
    readonly status: number,
    readonly body?: unknown,
  ) {
    super(message);
    this.name = 'TransportError';
  }

  get offline(): boolean {
    return this.status === 0;
  }
}

export interface PageWithRaw {
  page: SnapshotPage;
  raw: string;
}

/** What the engine needs from the server. Tests supply a fake. */
export interface SyncTransport {
  pull(token: string, cursor: string | null, scopes?: string[]): Promise<PullResponse>;
  push(token: string, deviceId: string, ops: SyncOp[], batchKey: string): Promise<PushResponse>;
  openSnapshot(token: string, scopes?: string[]): Promise<SnapshotOpen>;
  snapshotPage(token: string, snapshotId: string, scope: string, after: string): Promise<PageWithRaw>;
  completeSnapshot(token: string, snapshotId: string,
      scopes: Record<string, { rowCount: number; checksum: string }>): Promise<SnapshotComplete>;
}

/** Wire form of one op (the client-only fields are not sent). */
export function wireOp(op: SyncOp): Record<string, unknown> {
  const o: Record<string, unknown> = {
    opId: op.opId,
    deviceSeq: op.deviceSeq,
    type: op.type,
    actorUserId: op.actorUserId,
    occurredAt: op.occurredAt,
    payload: op.payload,
  };
  if (op.supersedesOpId) o.supersedesOpId = op.supersedesOpId;
  return o;
}

export class FetchTransport implements SyncTransport {
  constructor(
    private readonly baseUrl: string,
    private readonly fetchImpl: typeof fetch = (...a) => fetch(...a),
    private readonly timeoutMs = 30_000,
  ) {}

  private async request(method: string, path: string, token: string, body?: unknown,
      extra: Record<string, string> = {}): Promise<{ status: number; raw: string; json: unknown }> {
    const ctl = new AbortController();
    const timer = setTimeout(() => ctl.abort(), this.timeoutMs);
    let res: Response;
    try {
      res = await this.fetchImpl(`${this.baseUrl}${path}`, {
        method,
        headers: {
          'Content-Type': 'application/json',
          'X-Device-Token': token,
          'X-Sync-Protocol': SYNC_PROTOCOL,
          ...extra,
        },
        body: body === undefined ? undefined : JSON.stringify(body),
        signal: ctl.signal,
      });
    } catch (e) {
      throw new TransportError(e instanceof Error ? e.message : 'Network error', 0);
    } finally {
      clearTimeout(timer);
    }
    const raw = await res.text().catch(() => '');
    let json: unknown = null;
    try {
      json = raw ? JSON.parse(raw) : null;
    } catch {
      json = raw;
    }
    if (!res.ok) {
      const msg = (json && typeof json === 'object' && 'message' in json)
        ? String((json as { message: unknown }).message) : `HTTP ${res.status}`;
      throw new TransportError(msg, res.status, json);
    }
    return { status: res.status, raw, json };
  }

  async pull(token: string, cursor: string | null, scopes?: string[]): Promise<PullResponse> {
    const q = new URLSearchParams();
    if (cursor) q.set('cursor', cursor);
    if (scopes?.length) q.set('scopes', scopes.join(','));
    const qs = q.toString();
    return (await this.request('GET', `/sync/pull${qs ? `?${qs}` : ''}`, token)).json as PullResponse;
  }

  async push(token: string, deviceId: string, ops: SyncOp[], batchKey: string): Promise<PushResponse> {
    return (await this.request('POST', '/sync/push', token, { deviceId, ops: ops.map(wireOp) },
      { 'Idempotency-Key': batchKey })).json as PushResponse;
  }

  async openSnapshot(token: string, scopes?: string[]): Promise<SnapshotOpen> {
    return (await this.request('POST', '/sync/snapshot', token, scopes?.length ? { scopes: scopes.join(',') } : {}))
      .json as SnapshotOpen;
  }

  async snapshotPage(token: string, snapshotId: string, scope: string, after: string): Promise<PageWithRaw> {
    const q = new URLSearchParams({ scope, after });
    const r = await this.request('GET', `/sync/snapshot/${encodeURIComponent(snapshotId)}/page?${q}`, token);
    return { page: r.json as SnapshotPage, raw: r.raw };
  }

  async completeSnapshot(token: string, snapshotId: string,
      scopes: Record<string, { rowCount: number; checksum: string }>): Promise<SnapshotComplete> {
    return (await this.request('POST', `/sync/snapshot/${encodeURIComponent(snapshotId)}/complete`, token, { scopes }))
      .json as SnapshotComplete;
  }
}

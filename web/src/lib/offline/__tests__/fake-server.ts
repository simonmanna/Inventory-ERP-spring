/**
 * Test double of the sync server: an applied-op ledger keyed by opId (repeat → `replayed`, never a second
 * application), batch idempotency by key, token rotation, revocation, snapshots with real page checksums, and hooks to
 * inject failures (lost responses, 5xx, rejections).
 */
import { sha256Hex } from '../checksum';
import { TransportError, type PageWithRaw, type SyncTransport, wireOp } from '../transport';
import type { PullResponse, PushResponse, PushResult, SnapshotComplete, SnapshotOpen, SyncOp } from '../types';

export interface FakeServerOptions {
  pageSize?: number;
}

export class FakeServer implements SyncTransport {
  /** opId → stored result; the count of real applications per opId. */
  ledger = new Map<string, PushResult>();
  applications = new Map<string, number>();
  batches = new Map<string, PushResponse>();
  pushCalls: Array<{ ops: Record<string, unknown>[]; key: string }> = [];
  pullCalls = 0;
  tokens = new Set<string>(['tok-1']);
  revoked = false;
  expired = false;
  protocolGone = false;
  rotate = true;
  offline = false;
  /** Called per op before applying; return a result to fail it instead. */
  decide: (op: Record<string, unknown>) => Partial<PushResult> | null = () => null;
  /** Apply the batch, then throw as if the response was lost. */
  loseNextResponse = false;
  /** Throw 503 for the whole next push. */
  failNextPush = false;
  /** Corrupt the first N served page checksums. */
  corruptPages = 0;
  /** Fail `complete` with this status once. */
  failComplete: number | null = null;
  expireSnapshotOnce = false;
  data: Record<string, Array<Record<string, unknown>>> = {
    taxes: [{ id: 't1', name: 'VAT', rate: 18 }, { id: 't2', name: 'Zero', rate: 0 }],
    staff: [],
    paymentMethods: [{ id: 'pm1', name: 'Cash' }],
  };
  delta: Record<string, Array<Record<string, unknown>>> = {};
  snapshots = new Map<string, { scopes: string[]; served: Map<string, string[]>; completed: boolean }>();
  private seq = 0;
  private tokenSeq = 1;

  constructor(private readonly opts: FakeServerOptions = {}) {}

  private auth(token: string, route: 'pull' | 'push'): void {
    if (this.offline) throw new TransportError('Failed to fetch', 0);
    if (!this.tokens.has(token)) throw new TransportError('A valid X-Device-Token is required', 401);
    if (route === 'push') return;
    if (this.revoked) throw new TransportError('A valid X-Device-Token is required', 401);
    if (this.expired) throw new TransportError("This device's token expired — register the device again", 401);
  }

  async push(token: string, _deviceId: string, ops: SyncOp[], key: string): Promise<PushResponse> {
    this.auth(token, 'push');
    const wire = ops.map(wireOp);
    if (wire.length === 0) throw new TransportError('ops must contain at least 1 elements', 400);
    this.pushCalls.push({ ops: wire, key });
    if (this.failNextPush) {
      this.failNextPush = false;
      throw new TransportError('Service Unavailable', 503);
    }
    const stored = this.batches.get(key);
    if (stored) return structuredClone(stored);
    const results: PushResult[] = [];
    for (const o of wire) {
      const opId = String(o.opId);
      const prior = this.ledger.get(opId);
      if (prior) {
        results.push({ ...prior, status: 'replayed' });
        continue;
      }
      if (o.type === 'invalid') throw new TransportError('ops.0.type must be one of ...', 400);
      if (this.revoked) {
        results.push({ opId, status: 'failed', httpStatus: 403, error: 'Device revoked', code: 'DEVICE_REVOKED',
          retryable: false });
        continue;
      }
      const fail = this.decide(o);
      if (fail) {
        results.push({ opId, status: 'failed', httpStatus: 400, error: 'Refused', retryable: false, ...fail });
        continue;
      }
      const payload = o.payload as Record<string, unknown>;
      const r: PushResult = { opId, status: 'applied', httpStatus: 201, error: null };
      if (typeof payload?.clientId === 'string') r.mapping = { [payload.clientId]: `srv-${payload.clientId}` };
      this.ledger.set(opId, r);
      this.applications.set(opId, (this.applications.get(opId) ?? 0) + 1);
      results.push(r);
    }
    const res: PushResponse = { results, lastPushSeq: ++this.seq, serverTime: new Date().toISOString() };
    this.batches.set(key, res);
    if (this.loseNextResponse) {
      this.loseNextResponse = false;
      throw new TransportError('Network error', 0);
    }
    return structuredClone(res);
  }

  async pull(token: string, cursor: string | null): Promise<PullResponse> {
    this.auth(token, 'pull');
    if (this.protocolGone) throw new TransportError('Upgrade required', 426);
    this.pullCalls++;
    const res: PullResponse = {
      data: cursor ? structuredClone(this.delta) : structuredClone(this.data),
      cursor: `c${this.pullCalls}`,
      serverTime: new Date().toISOString(),
      protocolVersion: '2.0',
      offlinePolicy: { offlineWindowHours: 48, idleTimeoutMinutes: 15, pinMaxAttempts: 3, pinLockoutMinutes: 2 },
      serverId: 'hq',
      deviceTokenExpiresAt: '2099-01-01T00:00:00Z',
    };
    this.delta = {};
    if (this.rotate) {
      const next = `tok-${++this.tokenSeq}`;
      this.tokens.add(next); // the old one stays valid (grace)
      res.deviceToken = next;
    }
    return res;
  }

  async openSnapshot(token: string, scopes?: string[]): Promise<SnapshotOpen> {
    this.auth(token, 'pull');
    const id = `snap-${this.snapshots.size + 1}`;
    const s = scopes ?? Object.keys(this.data);
    this.snapshots.set(id, { scopes: s, served: new Map(), completed: false });
    return { snapshotId: id, snapshotAt: '2026-11-21T00:00:00Z', expiresAt: '2099-01-01T00:00:00Z', scopes: s,
      pageSize: this.opts.pageSize ?? 1, pages: {}, pageCount: 0 };
  }

  async snapshotPage(token: string, snapshotId: string, scope: string, after: string): Promise<PageWithRaw> {
    this.auth(token, 'pull');
    const snap = this.snapshots.get(snapshotId);
    if (!snap) throw new TransportError('Snapshot not found', 404);
    if (this.expireSnapshotOnce) {
      this.expireSnapshotOnce = false;
      throw new TransportError('Snapshot expired — start a new one', 410);
    }
    const size = this.opts.pageSize ?? 1;
    const all = [...(this.data[scope] ?? [])].sort((a, b) => String(a.id).localeCompare(String(b.id)));
    const rest = all.filter((r) => !after || String(r.id) > after);
    const rows = rest.slice(0, size);
    const hasMore = rest.length > size;
    const rowsJson = JSON.stringify(rows);
    let checksum = await sha256Hex(rowsJson);
    const chain = snap.served.get(scope) ?? [];
    chain.push(checksum);
    snap.served.set(scope, chain);
    if (this.corruptPages > 0) {
      this.corruptPages--;
      chain.pop();
      checksum = 'bad';
    }
    const nextAfter = hasMore ? String(rows[rows.length - 1].id) : null;
    const raw = `{"snapshotId":${JSON.stringify(snapshotId)},"scope":${JSON.stringify(scope)},"after":${
      JSON.stringify(after)},"rows":${rowsJson},"nextAfter":${JSON.stringify(nextAfter)},"hasMore":${hasMore},`
      + `"pageChecksum":${JSON.stringify(checksum)}}`;
    return { page: JSON.parse(raw), raw };
  }

  async completeSnapshot(token: string, snapshotId: string,
      scopes: Record<string, { rowCount: number; checksum: string }>): Promise<SnapshotComplete> {
    this.auth(token, 'pull');
    if (this.failComplete) {
      const s = this.failComplete;
      this.failComplete = null;
      throw new TransportError('Snapshot verification failed', s);
    }
    const snap = this.snapshots.get(snapshotId)!;
    for (const scope of snap.scopes) {
      const expected = await sha256Hex((snap.served.get(scope) ?? []).join(','));
      if (scopes[scope]?.checksum !== expected || scopes[scope]?.rowCount !== (this.data[scope] ?? []).length) {
        throw new TransportError(`Snapshot verification failed — download the listed scopes again: ${scope}`, 409);
      }
    }
    snap.completed = true;
    return { snapshotId, status: 'completed', snapshotAt: '2026-11-21T00:00:00Z', cursor: 'snap-cursor' };
  }
}

/** Two "tabs" talking over one channel. */
export class FakeChannelHub {
  private members = new Set<FakeChannel>();
  join(): FakeChannel {
    const c = new FakeChannel(this.members);
    this.members.add(c);
    return c;
  }
}

class FakeChannel {
  private fns = new Set<(e: MessageEvent) => void>();
  constructor(private readonly members: Set<FakeChannel>) {}
  postMessage(msg: unknown): void {
    for (const m of this.members) if (m !== this) for (const fn of m.fns) fn({ data: msg } as MessageEvent);
  }
  addEventListener(_t: 'message', fn: (e: MessageEvent) => void): void {
    this.fns.add(fn);
  }
  removeEventListener(_t: 'message', fn: (e: MessageEvent) => void): void {
    this.fns.delete(fn);
  }
  close(): void {
    this.members.delete(this);
  }
}

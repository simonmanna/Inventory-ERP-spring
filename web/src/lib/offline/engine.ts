/**
 * ADR-S083 O1 — the web sync engine. One pass = push the outbox, then pull (a snapshot first when there is no
 * cursor yet). Exactly one tab runs a pass at a time (Web Locks); every tab hears the outcome (BroadcastChannel).
 *
 * Crash safety:
 * - an op is marked `in_flight` BEFORE it is sent; a pass that dies mid-push leaves it `in_flight`, and the next pass
 *   re-sends it under the SAME opId (the server's applied-op ledger replays it — never a second application);
 * - the batch `Idempotency-Key` is derived from the batch's opIds and attempt round, so a re-sent identical batch
 *   replays the stored per-op results, while a batch retried after a failure gets a fresh key;
 * - pulled rows and the cursor commit in one IndexedDB transaction; a rotated device token is stored before the rows,
 *   and the replaced token stays valid server-side for a grace period, so a crash between the two is harmless.
 *
 * Device state (C4): token expired → pull refused, ops still pushed (the server holds them); revoked → unsynced ops
 * exported for a supervisor, local data wiped; unknown token → unregistered, local data kept.
 */
import { sha256Hex } from './checksum';
import { type ChannelLike, defaultLocks, type LockLike, openChannel } from './lock';
import { applyResult, applyTransientFailure, dueOps } from './retry';
import { runSnapshot } from './snapshot';
import { clearRegistered, markRegistered } from './storage';
import type { SyncStore } from './store';
import { TransportError, type SyncTransport } from './transport';
import type { DeviceInfo, NewOp, OfflinePolicy, PullResponse, PushResult, SyncOp, SyncStatus } from './types';
import { FULL_SET_SCOPES } from './types';
import { Vault } from './vault';

export const PUSH_BATCH = 100;
export const MAX_PULL_ROUNDS = 20;
const TOKEN = 'deviceToken';

export interface EngineOptions {
  store: SyncStore;
  transport: SyncTransport;
  locks?: LockLike;
  channel?: ChannelLike | null;
  clock?: () => number;
  random?: () => number;
  batchSize?: number;
  scopes?: string[];
}

export interface RevokedExport {
  deviceId: string | null;
  exportedAt: number;
  ops: SyncOp[];
}

type Msg = { kind: 'status'; status: SyncStatus } | { kind: 'enqueued' };

export class SyncEngine {
  readonly vault: Vault;
  private readonly locks: LockLike;
  private readonly channel: ChannelLike | null;
  private readonly clock: () => number;
  private readonly listeners = new Set<(s: SyncStatus) => void>();
  private status: SyncStatus = { state: 'idle', lastSyncAt: null, pending: 0, inFlight: 0, rejected: 0, held: 0 };
  private readonly onMessage = (e: MessageEvent) => {
    const m = e.data as Msg;
    if (m?.kind === 'status') this.emit(m.status, false);
  };

  constructor(private readonly opts: EngineOptions) {
    this.vault = new Vault(opts.store);
    this.locks = opts.locks ?? defaultLocks();
    this.channel = opts.channel === undefined ? openChannel(`erp-sync:${opts.store.name}`) : opts.channel;
    this.clock = opts.clock ?? Date.now;
    this.channel?.addEventListener('message', this.onMessage);
  }

  get store(): SyncStore {
    return this.opts.store;
  }

  dispose(): void {
    this.channel?.removeEventListener('message', this.onMessage);
    this.channel?.close();
  }

  subscribe(fn: (s: SyncStatus) => void): () => void {
    this.listeners.add(fn);
    fn(this.status);
    return () => this.listeners.delete(fn);
  }

  current(): SyncStatus {
    return this.status;
  }

  private emit(s: SyncStatus, broadcast = true): void {
    this.status = s;
    for (const fn of this.listeners) fn(s);
    if (broadcast) this.channel?.postMessage({ kind: 'status', status: s } satisfies Msg);
  }

  // ── registration ──────────────────────────────────────────────────────────

  /** Adopt a registration (`POST /sync/devices/register`, done online by an admin). The token goes to the vault. */
  async adopt(device: Omit<DeviceInfo, 'registeredAt'>, deviceToken: string): Promise<void> {
    await this.vault.put(TOKEN, deviceToken);
    await this.store.setMeta({ device: { ...device, registeredAt: this.clock() } satisfies DeviceInfo });
    markRegistered(this.store.name, device.id);
    await this.refreshStatus('idle');
  }

  async device(): Promise<DeviceInfo | undefined> {
    return this.store.getMeta<DeviceInfo>('device');
  }

  // ── outbox ────────────────────────────────────────────────────────────────

  async enqueue(op: NewOp): Promise<SyncOp> {
    const created = await this.store.enqueue(op, this.clock());
    this.channel?.postMessage({ kind: 'enqueued' } satisfies Msg);
    await this.refreshStatus(this.status.state);
    return created;
  }

  // ── one pass ──────────────────────────────────────────────────────────────

  /**
   * Run one push + pull pass. Returns `null` when another tab holds the sync lock (that tab broadcasts the result).
   */
  async sync(): Promise<SyncStatus | null> {
    return this.locks.request(`erp-sync:${this.store.name}`, { ifAvailable: true }, async (lock) => {
      if (!lock) return null;
      return this.pass();
    });
  }

  private async pass(): Promise<SyncStatus> {
    const device = await this.device();
    const token = await this.vault.get(TOKEN);
    if (!device || !token) return this.refreshStatus('unregistered');
    await this.refreshStatus('syncing');

    let pushedOk = false;
    try {
      pushedOk = await this.pushAll(token, device.id);
    } catch (e) {
      return this.failed(e);
    }

    try {
      await this.pull(token, pushedOk, device.id);
    } catch (e) {
      return this.failed(e);
    }
    return this.refreshStatus('idle');
  }

  private async failed(e: unknown): Promise<SyncStatus> {
    const tagged = stateOf(e);
    if (tagged) return this.refreshStatus(tagged, e instanceof Error ? e.message : undefined);
    if (e instanceof TransportError && e.status === 401) return this.refreshStatus('unregistered', e.message);
    if (e instanceof TransportError) {
      if (e.offline) return this.refreshStatus('offline', e.message);
      if (e.status === 426) return this.refreshStatus('upgrade_required', e.message);
    }
    return this.refreshStatus('error', e instanceof Error ? e.message : String(e));
  }

  /** Push every due op. Returns true when the server answered at least one push (token recognised). */
  private async pushAll(token: string, deviceId: string): Promise<boolean> {
    const size = this.opts.batchSize ?? PUSH_BATCH;
    let answered = false;
    let single = false;
    for (;;) {
      const batch = dueOps(await this.store.listOps(['queued', 'in_flight']), this.clock(), single ? 1 : size);
      if (batch.length === 0) return answered;
      const now = this.clock();
      const flying = batch.map((o) => ({ ...o, status: 'in_flight' as const, updatedAt: now }));
      await this.store.putOps(flying);
      const round = Math.max(...flying.map((o) => o.attempts));
      const key = await sha256Hex(`${deviceId}:${flying.map((o) => o.opId).join(',')}:${round}`);
      let results: PushResult[];
      try {
        results = (await this.opts.transport.push(token, deviceId, flying, key)).results;
      } catch (e) {
        if (e instanceof TransportError && e.status === 400) {
          if (flying.length > 1) {
            // One malformed op must not block the rest: put them back and isolate it.
            await this.store.putOps(flying.map((o) => ({ ...o, status: 'queued' as const })));
            single = true;
            continue;
          }
          await this.store.putOps([{ ...flying[0], status: 'rejected', httpStatus: 400, code: 'VALIDATION',
            lastError: e.message, updatedAt: this.clock() }]);
          answered = true;
          continue;
        }
        if (e instanceof TransportError && e.status === 401) {
          await this.store.putOps(flying.map((o) => ({ ...o, status: 'queued' as const })));
          throw e;
        }
        // No answer, 5xx, 429, 409 batch pending: same opIds, later.
        const msg = e instanceof Error ? e.message : String(e);
        await this.store.putOps(flying.map((o) => applyTransientFailure(o, msg, this.clock(), this.opts.random)));
        throw e;
      }
      answered = true;
      const byId = new Map(results.map((r) => [r.opId, r]));
      const updated = flying.map((o) => {
        const r = byId.get(o.opId);
        return r ? applyResult(o, r, this.clock(), this.opts.random)
          : applyTransientFailure(o, 'No result for this op', this.clock(), this.opts.random);
      });
      await this.store.putOps(updated);
      await this.store.putIdMappings(updated.flatMap((o) => {
        const clientId = o.payload?.clientId;
        const mapped = typeof clientId === 'string' ? o.result?.mapping?.[clientId] : undefined;
        return o.status === 'applied' && typeof mapped === 'string'
          ? [{ clientId: clientId as string, serverId: mapped, key: 'clientId', opId: o.opId }] : [];
      }));
    }
  }

  private async pull(token: string, pushAnswered: boolean, deviceId: string): Promise<void> {
    let cursor = (await this.store.getMeta<string>('cursor')) ?? null;
    try {
      if (!cursor) {
        const snap = await runSnapshot(this.store, this.opts.transport, token, { scopes: this.opts.scopes, clock: this.clock });
        cursor = snap.cursor;
      }
      for (let round = 0; round < MAX_PULL_ROUNDS; round++) {
        const res = await this.opts.transport.pull(token, cursor, this.opts.scopes);
        await this.applyPull(res);
        if (res.hasMore !== true || res.cursor === cursor) break;
        cursor = res.cursor;
      }
    } catch (e) {
      if (e instanceof TransportError && e.status === 401) {
        await this.onUnauthorized(e, token, pushAnswered, deviceId);
        return;
      }
      throw e;
    }
  }

  private async applyPull(res: PullResponse): Promise<void> {
    if (res.deviceToken) await this.vault.put(TOKEN, res.deviceToken);
    const meta: Record<string, unknown> = { cursor: res.cursor, lastSyncAt: this.clock() };
    if (res.offlinePolicy) meta.offlinePolicy = res.offlinePolicy satisfies OfflinePolicy;
    if (res.deviceTokenExpiresAt !== undefined) meta.deviceTokenExpiresAt = res.deviceTokenExpiresAt;
    if (res.serverId) meta.serverId = res.serverId;
    await this.store.applyPull(res.data ?? {}, FULL_SET_SCOPES, meta);
  }

  /**
   * 401 on pull. Expired token → keep everything (ops are still pushable). Otherwise probe with an empty push (the
   * server authenticates before validating, so 401 = token unknown, 400 = token recognised but revoked).
   */
  private async onUnauthorized(e: TransportError, token: string, pushAnswered: boolean, deviceId: string): Promise<void> {
    if (/expired/i.test(e.message)) {
      await this.store.setMeta({ tokenExpired: true });
      throw Object.assign(new TransportError(e.message, 401), { state: 'token_expired' });
    }
    let recognised = pushAnswered;
    if (!recognised) {
      try {
        await this.opts.transport.push(token, deviceId, [], 'probe');
        recognised = true;
      } catch (p) {
        recognised = p instanceof TransportError && p.status === 400;
        if (p instanceof TransportError && p.offline) throw p;
      }
    }
    if (recognised) {
      await this.revoke(deviceId);
      throw Object.assign(new TransportError('This device was revoked', 401), { state: 'revoked' });
    }
    throw Object.assign(new TransportError('This device is not registered', 401), { state: 'unregistered' });
  }

  /** Export every op not applied (for supervisor import), then wipe the local store. */
  private async revoke(deviceId: string): Promise<void> {
    const ops = (await this.store.listOps()).filter((o) => o.status !== 'applied');
    const exported: RevokedExport = { deviceId, exportedAt: this.clock(), ops };
    await this.store.setMeta({ revokedExport: exported });
    await this.store.wipe(['revokedExport']);
    clearRegistered(this.store.name);
  }

  async revokedExport(): Promise<RevokedExport | undefined> {
    return this.store.getMeta<RevokedExport>('revokedExport');
  }

  // ── status ────────────────────────────────────────────────────────────────

  async refreshStatus(state: SyncStatus['state'], message?: string): Promise<SyncStatus> {
    const ops = await this.store.listOps();
    const count = (s: SyncOp['status']) => ops.filter((o) => o.status === s).length;
    const s: SyncStatus = {
      state,
      lastSyncAt: (await this.store.getMeta<number>('lastSyncAt')) ?? null,
      pending: count('queued'),
      inFlight: count('in_flight'),
      rejected: count('rejected'),
      held: count('held'),
      message,
    };
    this.emit(s);
    return s;
  }
}

/** Map an engine failure to its state (the `state` tag set in `onUnauthorized`). */
export function stateOf(e: unknown): SyncStatus['state'] | undefined {
  return (e as { state?: SyncStatus['state'] })?.state;
}

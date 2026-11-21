/**
 * ADR-S083 O1 — the device's local sync store (IndexedDB), one database per organization so an org switch can never
 * mix data.
 *
 * Object stores:
 * - `ops`     outbox; key `opId`, indexes `byStatus`, `bySeq`
 * - `rows`    pulled data; key `[scope, id]`, index `byScope`
 * - `staging` snapshot pages being downloaded; key `[snapshotId, scope, id]`, index `bySnapshot`
 * - `idmap`   client-minted id → server id (from push `mapping`)
 * - `meta`    key/value: cursor, deviceSeq counter, device, policy, vault, snapshot progress, PIN throttle, ...
 *
 * Every multi-step change (enqueue + sequence counter, pull rows + cursor, snapshot activation) is ONE IndexedDB
 * transaction, so a crash leaves either the old state or the new one, never a mix.
 */
import type { NewOp, SyncOp } from './types';

const DB_VERSION = 1;

export function dbName(organizationId: string): string {
  return `erp-offline:${organizationId}`;
}

function req<T>(r: IDBRequest<T>): Promise<T> {
  return new Promise((resolve, reject) => {
    r.onsuccess = () => resolve(r.result);
    r.onerror = () => reject(r.error);
  });
}

function done(tx: IDBTransaction): Promise<void> {
  return new Promise((resolve, reject) => {
    tx.oncomplete = () => resolve();
    tx.onerror = () => reject(tx.error);
    tx.onabort = () => reject(tx.error ?? new Error('IndexedDB transaction aborted'));
  });
}

export interface StoredRow {
  scope: string;
  id: string;
  row: Record<string, unknown>;
}

export class SyncStore {
  private db: IDBDatabase | null = null;

  constructor(readonly name: string, private readonly idb: IDBFactory = indexedDB) {}

  static forOrganization(organizationId: string, idb?: IDBFactory): SyncStore {
    return new SyncStore(dbName(organizationId), idb);
  }

  async open(): Promise<IDBDatabase> {
    if (this.db) return this.db;
    const r = this.idb.open(this.name, DB_VERSION);
    r.onupgradeneeded = () => {
      const db = r.result;
      const ops = db.createObjectStore('ops', { keyPath: 'opId' });
      ops.createIndex('byStatus', 'status');
      ops.createIndex('bySeq', 'deviceSeq', { unique: true });
      const rows = db.createObjectStore('rows', { keyPath: ['scope', 'id'] });
      rows.createIndex('byScope', 'scope');
      const staging = db.createObjectStore('staging', { keyPath: ['snapshotId', 'scope', 'id'] });
      staging.createIndex('bySnapshot', 'snapshotId');
      db.createObjectStore('idmap', { keyPath: 'clientId' });
      db.createObjectStore('meta', { keyPath: 'key' });
    };
    this.db = await req(r);
    this.db.onversionchange = () => this.close();
    return this.db;
  }

  close(): void {
    this.db?.close();
    this.db = null;
  }

  /** Delete the whole database (device revoked, or storage reset). */
  async destroy(): Promise<void> {
    this.close();
    await req(this.idb.deleteDatabase(this.name) as unknown as IDBRequest<unknown>);
  }

  private async tx(stores: string[], mode: IDBTransactionMode): Promise<IDBTransaction> {
    return (await this.open()).transaction(stores, mode);
  }

  // ── meta ──────────────────────────────────────────────────────────────────

  async getMeta<T>(key: string): Promise<T | undefined> {
    const t = await this.tx(['meta'], 'readonly');
    const rec = await req<{ key: string; value: T } | undefined>(t.objectStore('meta').get(key));
    return rec?.value;
  }

  async setMeta(entries: Record<string, unknown>): Promise<void> {
    const t = await this.tx(['meta'], 'readwrite');
    const s = t.objectStore('meta');
    for (const [key, value] of Object.entries(entries)) {
      if (value === undefined) s.delete(key);
      else s.put({ key, value });
    }
    await done(t);
  }

  // ── ops (outbox) ──────────────────────────────────────────────────────────

  /**
   * Add an op with a fresh opId and the next deviceSeq, atomically (the counter and the op commit together, so a
   * sequence number is never reused or skipped by a crash). `decorate` adds payload fields derived from the
   * sequence (a provisional receipt number) in the same transaction.
   */
  async enqueue(input: NewOp, now = Date.now(), opId: string = crypto.randomUUID(),
      decorate?: (deviceSeq: number) => Record<string, unknown>): Promise<SyncOp> {
    const t = await this.tx(['ops', 'meta'], 'readwrite');
    const meta = t.objectStore('meta');
    const current = await req<{ key: string; value: number } | undefined>(meta.get('deviceSeq'));
    const deviceSeq = (current?.value ?? 0) + 1;
    meta.put({ key: 'deviceSeq', value: deviceSeq });
    const op: SyncOp = {
      opId,
      deviceSeq,
      type: input.type,
      actorUserId: input.actorUserId,
      occurredAt: input.occurredAt ?? new Date(now).toISOString(),
      payload: decorate ? { ...input.payload, ...decorate(deviceSeq) } : input.payload,
      supersedesOpId: input.supersedesOpId,
      status: 'queued',
      attempts: 0,
      nextAttemptAt: 0,
      createdAt: now,
      updatedAt: now,
    };
    t.objectStore('ops').add(op);
    await done(t);
    return op;
  }

  async getOp(opId: string): Promise<SyncOp | undefined> {
    const t = await this.tx(['ops'], 'readonly');
    return req<SyncOp | undefined>(t.objectStore('ops').get(opId));
  }

  /** Ops in deviceSeq order, optionally only some statuses. */
  async listOps(statuses?: SyncOp['status'][]): Promise<SyncOp[]> {
    const t = await this.tx(['ops'], 'readonly');
    const all = await req<SyncOp[]>(t.objectStore('ops').index('bySeq').getAll());
    return statuses ? all.filter((o) => statuses.includes(o.status)) : all;
  }

  async putOps(ops: SyncOp[]): Promise<void> {
    if (ops.length === 0) return;
    const t = await this.tx(['ops'], 'readwrite');
    const s = t.objectStore('ops');
    for (const op of ops) s.put(op);
    await done(t);
  }

  // ── pulled rows ───────────────────────────────────────────────────────────

  async getRows<T = Record<string, unknown>>(scope: string): Promise<T[]> {
    const t = await this.tx(['rows'], 'readonly');
    const recs = await req<StoredRow[]>(t.objectStore('rows').index('byScope').getAll(scope));
    return recs.map((r) => r.row as T);
  }

  async getRow<T = Record<string, unknown>>(scope: string, id: string): Promise<T | undefined> {
    const t = await this.tx(['rows'], 'readonly');
    const rec = await req<StoredRow | undefined>(t.objectStore('rows').get([scope, id]));
    return rec?.row as T | undefined;
  }

  /**
   * Apply one pull in a single transaction: delta rows are upserted (a row carrying `deletedAt` is a tombstone and is
   * removed), full-set scopes are replaced, and the meta entries (cursor, lastSyncAt, policy...) are written with
   * them — so the cursor never moves past rows that were not stored.
   */
  async applyPull(data: Record<string, Array<Record<string, unknown>>>, fullSet: readonly string[],
      meta: Record<string, unknown>): Promise<void> {
    const t = await this.tx(['rows', 'meta'], 'readwrite');
    const rows = t.objectStore('rows');
    for (const [scope, list] of Object.entries(data)) {
      if (!Array.isArray(list)) continue;
      if (fullSet.includes(scope)) {
        rows.delete(IDBKeyRange.bound([scope, ''], [scope, '￿']));
      }
      for (const row of list) {
        const id = row?.id;
        if (id === undefined || id === null) continue;
        if (row.deletedAt) rows.delete([scope, String(id)]);
        else rows.put({ scope, id: String(id), row });
      }
    }
    const m = t.objectStore('meta');
    for (const [key, value] of Object.entries(meta)) {
      if (value === undefined) m.delete(key);
      else m.put({ key, value });
    }
    await done(t);
  }

  // ── snapshot staging ──────────────────────────────────────────────────────

  /** Store one downloaded page and the new progress together (resume restarts at the last stored page). */
  async stagePage(snapshotId: string, scope: string, rows: Array<Record<string, unknown>>,
      progress: unknown): Promise<void> {
    const t = await this.tx(['staging', 'meta'], 'readwrite');
    const s = t.objectStore('staging');
    for (const row of rows) {
      if (row?.id === undefined || row.id === null) continue;
      s.put({ snapshotId, scope, id: String(row.id), row });
    }
    t.objectStore('meta').put({ key: 'snapshot', value: progress });
    await done(t);
  }

  async stagedCount(snapshotId: string): Promise<number> {
    const t = await this.tx(['staging'], 'readonly');
    return req(t.objectStore('staging').index('bySnapshot').count(snapshotId));
  }

  /** Drop a snapshot's staged rows and its progress (expired, or verification failed). */
  async discardStaging(snapshotId: string): Promise<void> {
    const t = await this.tx(['staging', 'meta'], 'readwrite');
    t.objectStore('staging').delete(IDBKeyRange.bound([snapshotId, '', ''], [snapshotId, '￿', '￿']));
    t.objectStore('meta').delete('snapshot');
    await done(t);
  }

  /**
   * Atomic activation (C5): in ONE transaction the snapshot's scopes are replaced by the staged rows, the staging is
   * cleared and the delta cursor is set. Until this commits the active dataset is untouched; if it fails, nothing
   * changed.
   */
  async activateSnapshot(snapshotId: string, scopes: string[], meta: Record<string, unknown>): Promise<void> {
    const t = await this.tx(['rows', 'staging', 'meta'], 'readwrite');
    const rows = t.objectStore('rows');
    for (const scope of scopes) {
      rows.delete(IDBKeyRange.bound([scope, ''], [scope, '￿']));
    }
    const staging = t.objectStore('staging');
    const range = IDBKeyRange.bound([snapshotId, '', ''], [snapshotId, '￿', '￿']);
    const staged = await req<Array<StoredRow & { snapshotId: string }>>(staging.getAll(range));
    for (const s of staged) rows.put({ scope: s.scope, id: s.id, row: s.row });
    staging.delete(range);
    const m = t.objectStore('meta');
    m.delete('snapshot');
    for (const [key, value] of Object.entries(meta)) {
      if (value === undefined) m.delete(key);
      else m.put({ key, value });
    }
    await done(t);
  }

  // ── id map ────────────────────────────────────────────────────────────────

  async putIdMappings(entries: Array<{ clientId: string; serverId: string; key: string; opId: string }>): Promise<void> {
    if (entries.length === 0) return;
    const t = await this.tx(['idmap'], 'readwrite');
    const s = t.objectStore('idmap');
    for (const e of entries) s.put(e);
    await done(t);
  }

  async resolveId(clientId: string): Promise<string | undefined> {
    const t = await this.tx(['idmap'], 'readonly');
    const rec = await req<{ serverId: string } | undefined>(t.objectStore('idmap').get(clientId));
    return rec?.serverId;
  }

  // ── wipe ──────────────────────────────────────────────────────────────────

  /** Clear everything except the listed meta keys (used on revocation, keeping the unsynced-ops export). */
  async wipe(keepMeta: string[] = []): Promise<void> {
    const kept: Array<{ key: string; value: unknown }> = [];
    for (const key of keepMeta) {
      const value = await this.getMeta(key);
      if (value !== undefined) kept.push({ key, value });
    }
    const t = await this.tx(['ops', 'rows', 'staging', 'idmap', 'meta'], 'readwrite');
    for (const s of ['ops', 'rows', 'staging', 'idmap', 'meta']) t.objectStore(s).clear();
    for (const k of kept) t.objectStore('meta').put(k);
    await done(t);
  }
}

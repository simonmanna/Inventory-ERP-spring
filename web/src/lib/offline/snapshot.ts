/**
 * ADR-S083 C5 — client side of the first-sync snapshot.
 *
 * 1. `POST /sync/snapshot` pins one `snapshotAt`; progress is stored so a restart resumes.
 * 2. Pages are downloaded per scope; each page's raw `rows` must hash to its `pageChecksum`, else the page is
 *    fetched again (and the sync fails after a few attempts). A verified page and the new progress are stored in one
 *    transaction, into the staging store — never into the active dataset.
 * 3. `POST /complete` sends per-scope row counts and combined checksums; the server verifies them against the pages
 *    it served and answers with the delta cursor at `snapshotAt`.
 * 4. Only after that acknowledgement are the staged rows swapped in, with the cursor, in one transaction.
 *
 * 410 (snapshot expired) or a 409 verification failure discards the staging and starts a new snapshot once.
 */
import { scopeChecksum, verifyPage } from './checksum';
import type { SyncStore } from './store';
import { TransportError, type SyncTransport } from './transport';
import type { SnapshotProgress } from './types';

export const PAGE_VERIFY_ATTEMPTS = 3;

export class SnapshotVerificationError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'SnapshotVerificationError';
  }
}

export interface SnapshotOutcome {
  cursor: string;
  snapshotAt: string;
  rows: number;
  resumed: boolean;
}

export async function runSnapshot(store: SyncStore, transport: SyncTransport, token: string,
    opts: { scopes?: string[]; clock?: () => number; extraMeta?: Record<string, unknown> } = {}): Promise<SnapshotOutcome> {
  try {
    return await attempt(store, transport, token, opts);
  } catch (e) {
    if (e instanceof TransportError && (e.status === 410 || e.status === 409 || e.status === 404)) {
      // Expired, already completed elsewhere, verification refused or unknown: start over once.
      const p = await store.getMeta<SnapshotProgress>('snapshot');
      if (p) await store.discardStaging(p.snapshotId);
      return attempt(store, transport, token, opts);
    }
    throw e;
  }
}

async function attempt(store: SyncStore, transport: SyncTransport, token: string,
    opts: { scopes?: string[]; clock?: () => number; extraMeta?: Record<string, unknown> }): Promise<SnapshotOutcome> {
  const clock = opts.clock ?? Date.now;
  let progress = await store.getMeta<SnapshotProgress>('snapshot');
  let resumed = true;
  if (progress && Date.parse(progress.expiresAt) <= clock()) {
    await store.discardStaging(progress.snapshotId);
    progress = undefined;
  }
  if (!progress) {
    resumed = false;
    const open = await transport.openSnapshot(token, opts.scopes);
    progress = {
      snapshotId: open.snapshotId,
      expiresAt: open.expiresAt,
      scopes: open.scopes,
      perScope: Object.fromEntries(open.scopes.map((s) => [s, { after: '', done: false, rowCount: 0, pageChecksums: [] }])),
    };
    await store.setMeta({ snapshot: progress });
  }

  for (const scope of progress.scopes) {
    const ps = progress.perScope[scope];
    while (!ps.done) {
      let verified = false;
      for (let i = 0; i < PAGE_VERIFY_ATTEMPTS && !verified; i++) {
        const { page, raw } = await transport.snapshotPage(token, progress.snapshotId, scope, ps.after);
        if (!(await verifyPage(raw, page.pageChecksum))) continue;
        verified = true;
        ps.rowCount += page.rows.length;
        ps.pageChecksums.push(page.pageChecksum);
        if (page.hasMore && page.nextAfter) ps.after = page.nextAfter;
        else ps.done = true;
        await store.stagePage(progress.snapshotId, scope, page.rows, progress);
      }
      if (!verified) {
        throw new SnapshotVerificationError(`Snapshot page for ${scope} failed its checksum ${PAGE_VERIFY_ATTEMPTS} times`);
      }
    }
  }

  const claimed: Record<string, { rowCount: number; checksum: string }> = {};
  for (const scope of progress.scopes) {
    const ps = progress.perScope[scope];
    claimed[scope] = { rowCount: ps.rowCount, checksum: await scopeChecksum(ps.pageChecksums) };
  }
  const ack = await transport.completeSnapshot(token, progress.snapshotId, claimed);
  const rows = Object.values(progress.perScope).reduce((n, s) => n + s.rowCount, 0);
  await store.activateSnapshot(progress.snapshotId, progress.scopes, {
    cursor: ack.cursor,
    snapshotAt: ack.snapshotAt,
    ...opts.extraMeta,
  });
  return { cursor: ack.cursor, snapshotAt: ack.snapshotAt, rows, resumed };
}

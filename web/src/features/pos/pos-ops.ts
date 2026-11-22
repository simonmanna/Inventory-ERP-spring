/**
 * ADR-S083 O2 — POS writes as sync ops (pure; no React, no axios, no stores — tested in isolation).
 *
 * Rule (owner plan O2, see ADR-S083 "O2"):
 * - online, the POS keeps its REST routes unchanged (immediate receipts, Nest parity);
 * - a REST attempt that got no answer is finished over REST with the SAME key and body (legacy write-ahead queue) —
 *   it is never converted, because the server could not match the two requests;
 * - on a registered device, a write made while the server is known unreachable becomes a sync op here. It carries a
 *   provisional number, and references between offline writes use client ids (the server resolves them, also across
 *   push batches).
 *
 * Manager approvals (override / manager PINs) cannot be verified offline without storing a PIN on the device, so a
 * write carrying one is refused offline ("needs connection") — the same rule the REST queue already had.
 */
import type { SyncEngine } from '@/lib/offline/engine';
import { offlineWindow } from '@/lib/offline/session';
import type { OfflinePolicy, SyncOp } from '@/lib/offline/types';
import { DEFAULT_POLICY } from '@/lib/offline/types';

/** Fields that mean "a manager approved this online" — never stored offline. */
export const SECRET_FIELDS = ['overridePin', 'managerPin', 'incomingPin', 'approvalToken', 'approverEmail', 'pin'] as const;

/** Request fields that only make sense on the REST route. */
const REST_ONLY = ['occurredAt', 'pendingSyncCount', '_idemKey'];

export interface PosOp {
  type: string;
  payload: Record<string, unknown>;
  /** Shows a provisional receipt number. */
  isSale: boolean;
}

/** The sync op for a POS REST write, or null when that write has no offline form. */
export function opFor(endpoint: string, body: Record<string, unknown>): PosOp | null {
  const payload: Record<string, unknown> = { ...body };
  for (const k of REST_ONLY) delete payload[k];
  if (endpoint === '/pos/checkout') return { type: 'sale.checkout', payload, isSale: true };
  const tab = /^\/pos\/tabs\/([^/]+)\/settle$/.exec(endpoint);
  if (tab) return { type: 'tab.settle', payload: { ...payload, tableId: decodeURIComponent(tab[1]) }, isSale: true };
  if (endpoint === '/cash-sessions/open') return { type: 'cash_session.open', payload, isSale: false };
  if (endpoint === '/cash-sessions/close') return { type: 'cash_session.close', payload, isSale: false };
  if (endpoint === '/cash-sessions/movement') return { type: 'cash_session.movement', payload, isSale: false };
  if (endpoint === '/pos/cash-drawer/openings') return { type: 'cash_drawer.open', payload, isSale: false };
  if (endpoint === '/partners' && body.isCustomer === true) {
    const { name, phone, email } = body as { name?: string; phone?: string; email?: string };
    return { type: 'customer.upsert', payload: { name, phone: phone ?? null, email: email ?? null }, isSale: false };
  }
  return null;
}

export class NeedsConnectionError extends Error {
  readonly needsConnection = true;
  constructor(message: string) {
    super(message);
    this.name = 'NeedsConnectionError';
  }
}

/**
 * Thrown in place of the REST call when a write was recorded as an offline op. It has no `response`, so every call
 * site treats it exactly like a request that never reached the server ("saved — posts when the connection returns"),
 * and it carries the provisional number for the receipt.
 */
export class RecordedOfflineError extends Error {
  readonly response = undefined;
  readonly recordedOffline = true;
  constructor(readonly op: { opId: string; clientId: string; type: string; provisionalNumber?: string }) {
    super('Recorded offline — posts when the connection returns');
    this.name = 'RecordedOfflineError';
  }
}

export function isRecordedOffline(e: unknown): e is RecordedOfflineError {
  return !!e && typeof e === 'object' && (e as { recordedOffline?: unknown }).recordedOffline === true;
}

/** `OFF-<first 4 of the device id>-<device sequence, 6 digits>`: unique per device, readable on a receipt. */
export function provisionalNumber(deviceId: string, deviceSeq: number): string {
  return `OFF-${deviceId.replace(/-/g, '').slice(0, 4).toUpperCase()}-${String(deviceSeq).padStart(6, '0')}`;
}

export interface RecordInput {
  endpoint: string;
  body: Record<string, unknown>;
  actorUserId: string;
  /** The write's stable key (the cart's idempotency key); it becomes the opId and the clientId. */
  key?: string;
  now?: number;
}

/** Why this write cannot be recorded offline, or null when it can. */
export function refusal(body: Record<string, unknown>, lastSyncAt: number | null, policy: OfflinePolicy,
    now: number): string | null {
  if (SECRET_FIELDS.some((k) => body[k] !== undefined && body[k] !== null && body[k] !== '')) {
    return 'This needs a connection: a manager PIN cannot be checked offline.';
  }
  const w = offlineWindow(lastSyncAt, policy, now);
  if (!w.writable) {
    return w.reason === 'clock_rollback'
      ? 'This device clock is behind its last sync. Fix the date and time, then try again.'
      : w.reason === 'never_synced'
        ? 'This device has not synced yet. Connect once before working offline.'
        : 'This device has been offline too long. Connect to sync before recording more.';
  }
  return null;
}

/**
 * Record a POS write as a sync op. Throws `NeedsConnectionError` when it has no offline form or is refused, else
 * returns the queued op (opId = clientId = key, provisional number for sales).
 */
export async function recordOffline(engine: SyncEngine, input: RecordInput): Promise<SyncOp> {
  const op = opFor(input.endpoint, input.body);
  if (!op) throw new NeedsConnectionError('This needs a connection.');
  const device = await engine.device();
  if (!device) throw new NeedsConnectionError('This needs a connection: the device is not registered for offline use.');
  const now = input.now ?? Date.now();
  const policy = { ...DEFAULT_POLICY, ...(await engine.store.getMeta<OfflinePolicy>('offlinePolicy')) };
  const why = refusal(input.body, (await engine.store.getMeta<number>('lastSyncAt')) ?? null, policy, now);
  if (why) throw new NeedsConnectionError(why);
  const key = input.key ?? crypto.randomUUID();
  const existing = await engine.store.getOp(key);
  if (existing) return existing; // the same write recorded twice (double tap): keep the first
  const occurredAt = typeof input.body.occurredAt === 'string' ? input.body.occurredAt : new Date(now).toISOString();
  const payload = op.type === 'customer.upsert' ? { ...op.payload, id: key } : { ...op.payload, clientId: key };
  return engine.enqueue({ type: op.type, actorUserId: input.actorUserId, occurredAt, payload }, {
    opId: key,
    decorate: op.isSale ? (seq) => ({ provisionalNumber: provisionalNumber(device.id, seq) }) : undefined,
  });
}

/** A local shift opened offline, shown until the server has it. */
export interface OfflineShift {
  id: string;
  cashRegisterId: string;
  userId: string | null;
  status: 'open';
  openingFloat: string;
  openedAt: string;
  offline: true;
}

/** The offline shifts still open: an applied `cash_session.open` is the server's; a later close ends it. */
export function openOfflineShifts(ops: SyncOp[]): OfflineShift[] {
  const open = new Map<string, OfflineShift>();
  for (const o of [...ops].sort((a, b) => a.deviceSeq - b.deviceSeq)) {
    if (o.type === 'cash_session.open' && o.status !== 'applied' && o.status !== 'rejected') {
      const p = o.payload as { clientId: string; cashRegisterId: string; openingFloat?: number };
      open.set(p.clientId, {
        id: p.clientId,
        cashRegisterId: p.cashRegisterId,
        userId: o.actorUserId,
        status: 'open',
        openingFloat: String(p.openingFloat ?? 0),
        openedAt: o.occurredAt,
        offline: true,
      });
    }
    if (o.type === 'cash_session.close') open.delete(String((o.payload as { sessionId?: string }).sessionId));
  }
  return [...open.values()];
}

/**
 * Before an ONLINE REST write: swap client ids the server already mapped for server ids. Returns null when the body
 * still references a client id that has not reached the server — that write must go as an op too, behind the op
 * that creates the reference, or REST would refuse an id it does not know.
 */
export async function resolveForRest(engine: SyncEngine, body: Record<string, unknown>):
    Promise<Record<string, unknown> | null> {
  const out = { ...body };
  const ops = await engine.store.listOps();
  const unsent = new Set(ops.filter((o) => o.status !== 'applied').map((o) =>
    String((o.payload as { clientId?: unknown; id?: unknown }).clientId ?? (o.payload as { id?: unknown }).id ?? o.opId)));
  for (const k of ['cashSessionId', 'sessionId', 'invoiceId', 'reservationId', 'partnerId']) {
    const v = out[k];
    if (typeof v !== 'string' || !v) continue;
    const mapped = await engine.store.resolveId(v);
    if (mapped) out[k] = mapped;
    else if (unsent.has(v)) return null;
  }
  return out;
}

/** Ops shown to the cashier: what still needs the server or a person. */
export function attentionOps(ops: SyncOp[]): { pending: SyncOp[]; rejected: SyncOp[]; held: SyncOp[] } {
  return {
    pending: ops.filter((o) => o.status === 'queued' || o.status === 'in_flight'),
    rejected: ops.filter((o) => o.status === 'rejected'),
    held: ops.filter((o) => o.status === 'held'),
  };
}

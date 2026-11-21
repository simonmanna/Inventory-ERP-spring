/**
 * ADR-S083 O2 — the POS side of the sync engine: which writes go REST and which become ops, the offline-shift
 * overlay, and the order of syncing (legacy REST attempts first, then ops).
 *
 * A browser takes part only once it is registered as a sync device (Devices page). An unregistered browser keeps the
 * pre-O2 behaviour exactly.
 */
import { serverUnreachable } from '@/lib/api';
import type { SyncEngine } from '@/lib/offline/engine';
import type { SyncOp } from '@/lib/offline/types';
import { syncEngineFor } from '@/lib/offline/web';
import { useAuthStore } from '@/stores/auth.store';
import { OfflineSessions } from '@/lib/offline/session';
import { usePosAuthStore, type PosAuthUser } from './pos-auth.store';
import {
  NeedsConnectionError, openOfflineShifts, opFor, RecordedOfflineError, recordOffline, resolveForRest,
  type OfflineShift,
} from './pos-ops';

/** The sync engine of the current organization when this browser is a registered device, else null. */
export async function posEngine(): Promise<SyncEngine | null> {
  const org = useAuthStore.getState().organization?.id;
  if (!org || typeof indexedDB === 'undefined') return null;
  const engine = syncEngineFor(org);
  try {
    return (await engine.device()) ? engine : null;
  } catch {
    return null;
  }
}

export function posActorId(): string | undefined {
  return usePosAuthStore.getState().user?.userId ?? useAuthStore.getState().user?.id;
}

/** A refusal that reads like a server 4xx, so call sites keep the cart and show the message (never "saved"). */
export function asRejection(e: NeedsConnectionError): NeedsConnectionError & { response: unknown } {
  return Object.assign(e, { response: { status: 409, data: { statusCode: 409, message: e.message } } });
}

const sessions = new WeakMap<SyncEngine, OfflineSessions>();
function sessionsFor(engine: SyncEngine): OfflineSessions {
  let s = sessions.get(engine);
  if (!s) {
    s = new OfflineSessions(engine.store);
    sessions.set(engine, s);
  }
  return s;
}

/** Staff who can sign in offline (synced `staff` scope), in the shape of `GET /pos/auth/staff`. */
export async function offlineStaff(): Promise<Array<{ id: string; firstName: string; lastName: string | null;
    email: string | null; hasPin: boolean }> | null> {
  const engine = await posEngine();
  if (!engine) return null;
  const rows = await engine.store.getRows<{ id: string; firstName?: string; lastName?: string | null; email?: string | null;
    pinHash?: string | null; isActive?: boolean; deletedAt?: string | null }>('staff');
  return rows.filter((r) => r.isActive !== false && !r.deletedAt)
    .map((r) => ({ id: r.id, firstName: r.firstName ?? '', lastName: r.lastName ?? null, email: r.email ?? null,
      hasPin: !!r.pinHash }))
    .sort((a, b) => a.firstName.localeCompare(b.firstName));
}

const LOGIN_MESSAGES: Record<string, string> = {
  wrong_pin: 'Invalid PIN',
  locked: 'Too many wrong PINs on this device. Try again later.',
  inactive: 'This user cannot use the POS.',
  no_pin: 'This user has no POS PIN.',
  unknown_user: 'This user is not on this device yet. Connect to sync.',
};

/** PIN login against the synced staff list (ADR-S083 S2/C4). `message` null = not a registered device. */
export async function offlinePinLogin(userId: string, pin: string):
    Promise<{ ok: true; user: PosAuthUser } | { ok: false; message: string | null }> {
  const engine = await posEngine();
  if (!engine) return { ok: false, message: null };
  const r = await sessionsFor(engine).login(userId, pin);
  if (!r.ok) {
    const until = r.reason === 'locked' && r.lockedUntil ? ` (until ${new Date(r.lockedUntil).toLocaleTimeString()})` : '';
    return { ok: false, message: (LOGIN_MESSAGES[r.reason] ?? 'Invalid PIN') + until };
  }
  const staff = await engine.store.getRow<{ firstName?: string; lastName?: string | null; email?: string }>('staff', userId);
  return { ok: true, user: { userId, firstName: staff?.firstName ?? r.session.displayName, lastName: staff?.lastName ?? null,
    email: staff?.email ?? '', permissions: r.session.permissions, offline: true } };
}

/** An offline sign-in must still be live (idle timeout) to record work; activity keeps it alive. */
async function requireLiveOfflineSession(engine: SyncEngine): Promise<void> {
  const user = usePosAuthStore.getState().user;
  if (!user?.offline) return;
  const s = sessionsFor(engine);
  if (!(await s.active())) {
    usePosAuthStore.setState({ user: null, error: null });
    throw asRejection(new NeedsConnectionError('Your offline session timed out. Sign in with your PIN again.'));
  }
  await s.touch();
}

/**
 * Decide how a POS write goes. Returns the body to send over REST, or throws:
 * - `RecordedOfflineError` — recorded as a sync op (call sites treat it as "saved, posts when the connection returns");
 * - a 409-shaped `NeedsConnectionError` — refused offline (manager approval, offline too long, no offline form).
 *
 * `hasPriorAttempt`: the write was already sent over REST once — it is finished over REST with the same key.
 */
export async function routePosWrite(endpoint: string, body: Record<string, unknown>, key: string | undefined,
    hasPriorAttempt: boolean): Promise<Record<string, unknown>> {
  if (hasPriorAttempt) return body;
  const engine = await posEngine();
  if (!engine) return body;
  const resolved = await resolveForRest(engine, body);
  if (!serverUnreachable() && resolved) return resolved;
  if (!opFor(endpoint, body)) {
    if (resolved) return resolved; // no offline form: REST fails as it always did
    throw asRejection(new NeedsConnectionError('This uses work recorded offline that has not synced yet. Sync first.'));
  }
  const actor = posActorId();
  if (!actor) throw asRejection(new NeedsConnectionError('Sign in before recording offline.'));
  await requireLiveOfflineSession(engine);
  let op: SyncOp;
  try {
    op = await recordOffline(engine, { endpoint, body, actorUserId: actor, key });
  } catch (e) {
    if (e instanceof NeedsConnectionError) throw asRejection(e);
    throw e;
  }
  void engine.sync().catch(() => undefined);
  throw new RecordedOfflineError({
    opId: op.opId,
    clientId: String((op.payload as { clientId?: unknown; id?: unknown }).clientId ?? op.payload.id ?? op.opId),
    type: op.type,
    provisionalNumber: (op.payload as { provisionalNumber?: string }).provisionalNumber,
  });
}

/** Offline shifts still open on this device (optionally one register). */
export async function offlineShifts(registerId?: string): Promise<OfflineShift[]> {
  const engine = await posEngine();
  if (!engine) return [];
  const shifts = openOfflineShifts(await engine.store.listOps());
  return registerId ? shifts.filter((s) => s.cashRegisterId === registerId) : shifts;
}

/** The server id an offline-created record got, once synced. */
export async function serverIdFor(clientId: string): Promise<string | undefined> {
  return (await posEngine())?.store.resolveId(clientId);
}

/** The op recorded under `key` (the cart's idempotency key), if any. */
export async function offlineOp(key: string): Promise<SyncOp | undefined> {
  return (await posEngine())?.store.getOp(key);
}

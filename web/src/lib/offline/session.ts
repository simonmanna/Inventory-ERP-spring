/**
 * ADR-S083 S2 / C4 — offline user sessions. Three separate concepts, each enforced on its own:
 *
 * 1. **Offline authorization window** (`offlineWindowHours`, default 72): measured from the last successful sync.
 *    Past it — or when the clock reads earlier than the last sync (clock rolled back) — the device is read-only
 *    until it syncs. The server re-checks permissions on replay regardless (invariant 5).
 * 2. **Idle timeout** (`idleTimeoutMinutes`): an offline session with no activity for that long ends.
 * 3. **PIN throttling** (`pinMaxAttempts` failures → `pinLockoutMinutes` lockout, doubling on each further lockout).
 *
 * The PIN is checked against the bcrypt `pinHash` of the pulled `staff` scope; it is never stored, logged or sent.
 * The permission snapshot is the union of the user's role permissions as of the last sync, stamped with that time.
 * Device token expiry and revocation are server-side (the engine), not here.
 */
import bcrypt from 'bcryptjs';
import type { SyncStore } from './store';
import type { OfflinePolicy } from './types';
import { DEFAULT_POLICY } from './types';

export interface StaffRow {
  id: string;
  firstName?: string | null;
  lastName?: string | null;
  email?: string | null;
  pinHash?: string | null;
  isActive?: boolean;
  deletedAt?: string | null;
  roles?: Array<{ name?: string; permissions?: string[] | null }>;
}

export interface OfflineSession {
  userId: string;
  displayName: string;
  permissions: string[];
  /** When the permission snapshot was taken (the last successful sync), epoch ms. */
  permissionsAt: number | null;
  startedAt: number;
  lastActivityAt: number;
}

interface Throttle {
  failures: number;
  lockouts: number;
  lockedUntil: number;
}

export type LoginResult =
  | { ok: true; session: OfflineSession }
  | { ok: false; reason: 'unknown_user' | 'no_pin' | 'inactive' | 'wrong_pin' | 'locked'; lockedUntil?: number;
      attemptsLeft?: number };

/** Tolerated clock skew before a clock earlier than the last sync counts as rolled back. */
export const CLOCK_SKEW_MS = 5 * 60_000;

export type WindowState =
  | { writable: true; expiresAt: number }
  | { writable: false; reason: 'never_synced' | 'window_expired' | 'clock_rollback' };

/** Whether the device may still record offline writes (concept 1). */
export function offlineWindow(lastSyncAt: number | null, policy: OfflinePolicy, now: number): WindowState {
  if (lastSyncAt === null) return { writable: false, reason: 'never_synced' };
  if (now < lastSyncAt - CLOCK_SKEW_MS) return { writable: false, reason: 'clock_rollback' };
  const expiresAt = lastSyncAt + policy.offlineWindowHours * 3_600_000;
  return now <= expiresAt ? { writable: true, expiresAt } : { writable: false, reason: 'window_expired' };
}

export function permissionsOf(staff: StaffRow): string[] {
  const set = new Set<string>();
  for (const r of staff.roles ?? []) for (const p of r.permissions ?? []) set.add(p);
  return [...set].sort();
}

export class OfflineSessions {
  private current: OfflineSession | null = null;

  constructor(private readonly store: SyncStore, private readonly clock: () => number = Date.now) {}

  private async policy(): Promise<OfflinePolicy> {
    return { ...DEFAULT_POLICY, ...(await this.store.getMeta<OfflinePolicy>('offlinePolicy')) };
  }

  private async throttle(userId: string): Promise<Throttle> {
    return (await this.store.getMeta<Throttle>(`pinThrottle:${userId}`)) ?? { failures: 0, lockouts: 0, lockedUntil: 0 };
  }

  /** Check a PIN offline. Throttling is persisted, so reloading the page does not reset it. */
  async login(userId: string, pin: string): Promise<LoginResult> {
    const now = this.clock();
    const policy = await this.policy();
    const t = await this.throttle(userId);
    if (t.lockedUntil > now) return { ok: false, reason: 'locked', lockedUntil: t.lockedUntil };
    const staff = await this.store.getRow<StaffRow>('staff', userId);
    if (!staff) return { ok: false, reason: 'unknown_user' };
    if (staff.isActive === false || staff.deletedAt) return { ok: false, reason: 'inactive' };
    if (!staff.pinHash) return { ok: false, reason: 'no_pin' };
    const ok = await bcrypt.compare(pin, staff.pinHash);
    if (!ok) {
      const failures = t.failures + 1;
      if (failures >= policy.pinMaxAttempts) {
        const lockedUntil = now + policy.pinLockoutMinutes * 60_000 * 2 ** t.lockouts;
        await this.store.setMeta({ [`pinThrottle:${userId}`]: { failures: 0, lockouts: t.lockouts + 1, lockedUntil } });
        return { ok: false, reason: 'locked', lockedUntil };
      }
      await this.store.setMeta({ [`pinThrottle:${userId}`]: { ...t, failures, lockedUntil: 0 } });
      return { ok: false, reason: 'wrong_pin', attemptsLeft: policy.pinMaxAttempts - failures };
    }
    await this.store.setMeta({ [`pinThrottle:${userId}`]: undefined });
    const name = [staff.firstName, staff.lastName].filter(Boolean).join(' ') || staff.email || userId;
    this.current = {
      userId,
      displayName: name,
      permissions: permissionsOf(staff),
      permissionsAt: (await this.store.getMeta<number>('lastSyncAt')) ?? null,
      startedAt: now,
      lastActivityAt: now,
    };
    return { ok: true, session: this.current };
  }

  /** The active session, or null once it idled out (concept 2). */
  async active(): Promise<OfflineSession | null> {
    if (!this.current) return null;
    const policy = await this.policy();
    if (this.clock() - this.current.lastActivityAt > policy.idleTimeoutMinutes * 60_000) {
      this.current = null;
    }
    return this.current;
  }

  /** Record activity (keeps the idle timer alive). */
  async touch(): Promise<void> {
    const s = await this.active();
    if (s) s.lastActivityAt = this.clock();
  }

  logout(): void {
    this.current = null;
  }

  /** May the active session record this write offline? Needs a live session, the permission and an open window. */
  async canWrite(permission: string): Promise<{ ok: true } | { ok: false; reason: string }> {
    const s = await this.active();
    if (!s) return { ok: false, reason: 'no_session' };
    if (!s.permissions.includes(permission)) return { ok: false, reason: 'permission' };
    const w = offlineWindow((await this.store.getMeta<number>('lastSyncAt')) ?? null, await this.policy(), this.clock());
    return w.writable ? { ok: true } : { ok: false, reason: w.reason };
  }
}

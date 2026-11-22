import 'fake-indexeddb/auto';
import bcrypt from 'bcryptjs';
import { describe, expect, it } from 'vitest';
import { CLOCK_SKEW_MS, OfflineSessions, offlineWindow } from '../session';
import { SyncStore } from '../store';
import { DEFAULT_POLICY } from '../types';

let n = 0;
const H = 3_600_000;

async function setup(now: { t: number }, policy = { offlineWindowHours: 72, idleTimeoutMinutes: 30, pinMaxAttempts: 3,
    pinLockoutMinutes: 5 }) {
  const store = new SyncStore(`session-test-${++n}`);
  await store.applyPull({ staff: [
    { id: 'u1', firstName: 'Ann', lastName: 'Cashier', pinHash: bcrypt.hashSync('1234', 4), isActive: true,
      roles: [{ name: 'Cashier', permissions: ['pos:checkout', 'pos:read'] }, { name: 'X', permissions: ['pos:read'] }] },
    { id: 'u2', firstName: 'Gone', pinHash: bcrypt.hashSync('1111', 4), isActive: false, roles: [] },
    { id: 'u3', firstName: 'NoPin', pinHash: null, isActive: true, roles: [] },
  ] }, [], { lastSyncAt: now.t, offlinePolicy: policy });
  return { store, sessions: new OfflineSessions(store, () => now.t) };
}

describe('offline sessions (ADR-S083 S2/C4)', () => {
  it('checks the PIN against the pulled bcrypt hash and snapshots permissions', async () => {
    const now = { t: 10 * H };
    const { sessions } = await setup(now);
    const r = await sessions.login('u1', '1234');
    expect(r.ok).toBe(true);
    if (r.ok) {
      expect(r.session.permissions).toEqual(['pos:checkout', 'pos:read']);
      expect(r.session.permissionsAt).toBe(10 * H);
      expect(r.session.displayName).toBe('Ann Cashier');
    }
    expect(await sessions.login('u2', '1111')).toMatchObject({ ok: false, reason: 'inactive' });
    expect(await sessions.login('u3', '')).toMatchObject({ ok: false, reason: 'no_pin' });
    expect(await sessions.login('nobody', '1')).toMatchObject({ ok: false, reason: 'unknown_user' });
  });

  it('locks out after the allowed failures, escalating, and survives a reload', async () => {
    const now = { t: 10 * H };
    const { store, sessions } = await setup(now);
    expect(await sessions.login('u1', '0000')).toMatchObject({ reason: 'wrong_pin', attemptsLeft: 2 });
    expect(await sessions.login('u1', '0000')).toMatchObject({ reason: 'wrong_pin', attemptsLeft: 1 });
    const locked = await sessions.login('u1', '0000');
    expect(locked).toMatchObject({ reason: 'locked', lockedUntil: now.t + 5 * 60_000 });
    const reloaded = new OfflineSessions(store, () => now.t);
    expect(await reloaded.login('u1', '1234')).toMatchObject({ ok: false, reason: 'locked' });
    now.t += 5 * 60_000 + 1;
    for (let i = 0; i < 2; i++) await reloaded.login('u1', '0000');
    const second = await reloaded.login('u1', '0000');
    expect(second).toMatchObject({ reason: 'locked', lockedUntil: now.t + 10 * 60_000 }); // doubled
    now.t += 10 * 60_000 + 1;
    expect((await reloaded.login('u1', '1234')).ok).toBe(true);
  });

  it('offline window: writable until it lapses; a clock rolled back is read-only', () => {
    const p = { ...DEFAULT_POLICY, offlineWindowHours: 72 };
    expect(offlineWindow(null, p, 0)).toEqual({ writable: false, reason: 'never_synced' });
    expect(offlineWindow(100 * H, p, 171 * H)).toMatchObject({ writable: true });
    expect(offlineWindow(100 * H, p, 173 * H)).toEqual({ writable: false, reason: 'window_expired' });
    expect(offlineWindow(100 * H, p, 100 * H - CLOCK_SKEW_MS - 1)).toEqual({ writable: false, reason: 'clock_rollback' });
    expect(offlineWindow(100 * H, p, 100 * H - 1000)).toMatchObject({ writable: true });
  });

  it('canWrite needs a live session, the permission and an open window; idle timeout ends the session', async () => {
    const now = { t: 10 * H };
    const { sessions } = await setup(now);
    expect(await sessions.canWrite('pos:checkout')).toEqual({ ok: false, reason: 'no_session' });
    await sessions.login('u1', '1234');
    expect(await sessions.canWrite('pos:checkout')).toEqual({ ok: true });
    expect(await sessions.canWrite('pos:override')).toEqual({ ok: false, reason: 'permission' });
    now.t += 29 * 60_000;
    await sessions.touch();
    now.t += 29 * 60_000;
    expect(await sessions.active()).not.toBeNull();
    now.t += 31 * 60_000;
    expect(await sessions.active()).toBeNull();
    await sessions.login('u1', '1234');
    now.t = 10 * H + 73 * H;
    await sessions.touch();
    expect(await sessions.canWrite('pos:checkout')).toEqual({ ok: false, reason: 'no_session' });
  });

  it('window expiry blocks writes even with a live session', async () => {
    const now = { t: 10 * H };
    const { sessions } = await setup(now, { offlineWindowHours: 1, idleTimeoutMinutes: 600, pinMaxAttempts: 3,
      pinLockoutMinutes: 5 });
    await sessions.login('u1', '1234');
    now.t += 2 * H;
    expect(await sessions.canWrite('pos:checkout')).toEqual({ ok: false, reason: 'window_expired' });
  });
});

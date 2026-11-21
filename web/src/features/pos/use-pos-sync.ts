/**
 * ADR-S083 O2 — keeps a registered POS device in sync while a terminal is open.
 *
 * Each pass first finishes REST attempts that never got an answer (legacy write-ahead queue, same key and body), then
 * runs one engine pass (push ops, pull). That order keeps device order across the two paths: anything sent over REST
 * before the outage posts before the ops recorded during it. Passes run on mount, every 15 s, when the browser comes
 * back online and when the tab becomes visible.
 *
 * Back online with a cashier who signed in offline: they sign in with their PIN again, so later REST writes carry a
 * server-issued POS token.
 */
import { useCallback, useEffect, useRef, useState } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { toast } from 'sonner';
import type { SyncEngine } from '@/lib/offline/engine';
import type { SyncOp, SyncStatus } from '@/lib/offline/types';
import { tx } from '@/lib/i18n/tx';
import { replayAll } from './offline-queue';
import { usePosAuthStore } from './pos-auth.store';
import { posEngine } from './pos-sync';

export interface PosSyncState {
  registered: boolean;
  status: SyncStatus | null;
  ops: SyncOp[];
  syncNow: () => Promise<void>;
}

const PASS_MS = 15_000;

export function usePosSync(): PosSyncState {
  const qc = useQueryClient();
  const [engine, setEngine] = useState<SyncEngine | null>(null);
  const [status, setStatus] = useState<SyncStatus | null>(null);
  const [ops, setOps] = useState<SyncOp[]>([]);
  const running = useRef(false);

  useEffect(() => {
    let alive = true;
    void posEngine().then((e) => { if (alive) setEngine(e); });
    return () => { alive = false; };
  }, []);

  const refreshOps = useCallback(async (e: SyncEngine) => {
    setOps(await e.store.listOps());
  }, []);

  const syncNow = useCallback(async () => {
    if (!engine || running.current) return;
    running.current = true;
    try {
      const before = (await engine.store.listOps(['queued', 'in_flight'])).length;
      await replayAll().catch(() => undefined);
      const s = await engine.sync();
      await refreshOps(engine);
      if (s?.state === 'idle') {
        if (before > 0) {
          qc.invalidateQueries({ queryKey: ['cash-session'] });
          qc.invalidateQueries({ queryKey: ['pos-orders'] });
        }
        if (usePosAuthStore.getState().user?.offline) {
          usePosAuthStore.setState({ user: null, error: null });
          toast.info(tx('The connection is back. Sign in with your PIN again.'));
        }
      }
    } finally {
      running.current = false;
    }
  }, [engine, qc, refreshOps]);

  useEffect(() => {
    if (!engine) return;
    const unsubscribe = engine.subscribe((s) => { setStatus(s); void refreshOps(engine); });
    void syncNow();
    const timer = setInterval(() => void syncNow(), PASS_MS);
    const onOnline = () => void syncNow();
    const onVisible = () => { if (document.visibilityState === 'visible') void syncNow(); };
    window.addEventListener('online', onOnline);
    document.addEventListener('visibilitychange', onVisible);
    return () => {
      unsubscribe();
      clearInterval(timer);
      window.removeEventListener('online', onOnline);
      document.removeEventListener('visibilitychange', onVisible);
    };
  }, [engine, syncNow, refreshOps]);

  return { registered: !!engine, status, ops, syncNow };
}

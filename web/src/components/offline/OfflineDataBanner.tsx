/**
 * ADR-S083 O3 — "Offline: data as of HH:MM". Shown above every back-office page while the server is unreachable.
 * The time is the oldest result the current screen is showing, so nobody reads stale numbers as live.
 */
import { useEffect, useState } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { WifiOff } from 'lucide-react';
import { isOfflineCapablePath, useOnline } from '@/lib/offline/reachability';
import { tx } from '@/lib/i18n/tx';

/** The oldest `dataUpdatedAt` among the queries mounted on screen, or null when none has data. */
export function oldestOnScreen(queries: Array<{ state: { dataUpdatedAt: number }; isActive(): boolean }>): number | null {
  let oldest: number | null = null;
  for (const q of queries) {
    const at = q.state.dataUpdatedAt;
    if (!q.isActive() || !at) continue;
    if (oldest == null || at < oldest) oldest = at;
  }
  return oldest;
}

export function OfflineDataBanner() {
  const online = useOnline();
  const qc = useQueryClient();
  const [asOf, setAsOf] = useState<number | null>(null);

  useEffect(() => {
    if (online) return;
    const cache = qc.getQueryCache();
    const update = () => setAsOf(oldestOnScreen(cache.getAll()));
    update();
    return cache.subscribe(update);
  }, [online, qc]);

  // The POS screens work offline (ADR-S083 O2) and show their own connection indicator.
  if (online || isOfflineCapablePath()) return null;
  const time = asOf ? new Date(asOf) : null;
  const sameDay = time && time.toDateString() === new Date().toDateString();
  const label = time
    ? (sameDay ? time.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }) : time.toLocaleString([], {
      dateStyle: 'medium', timeStyle: 'short' }))
    : null;
  return (
    <div role="status" className="flex items-center gap-2 border-b border-amber-300 bg-amber-50 px-4 py-2 text-sm text-amber-900 dark:border-amber-700 dark:bg-amber-950 dark:text-amber-100">
      <WifiOff className="h-4 w-4 shrink-0" />
      <span>
        {label ? tx('Offline: data as of {{time}}.', { time: label }) : tx('Offline: no saved data for this screen.')}{' '}
        {tx('Changes need a connection.')}
      </span>
    </div>
  );
}

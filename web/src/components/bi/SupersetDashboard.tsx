import { useEffect, useRef, useState } from 'react';
import { embedDashboard } from '@superset-ui/embedded-sdk';
import { tx } from '@/lib/i18n/tx';
import { guestToken } from './use-bi-dashboards';

/**
 * One module's Superset dashboard in an iframe (ADR-S078). The API mints each guest token for the signed-in user's
 * organization; the SDK asks for a new one (through fetchGuestToken) before the current one expires.
 */
export function SupersetDashboard({ module }: { module: string }) {
  const mount = useRef<HTMLDivElement>(null);
  const [failed, setFailed] = useState(false);

  useEffect(() => {
    let cancelled = false;
    let unmount: (() => void) | undefined;
    setFailed(false);
    (async () => {
      const first = await guestToken(module);
      if (cancelled || !mount.current) return;
      let pending: string | null = first.token;
      const dashboard = await embedDashboard({
        id: first.dashboardId,
        supersetDomain: first.supersetDomain,
        mountPoint: mount.current,
        fetchGuestToken: async () => {
          if (pending) {
            const t = pending;
            pending = null;
            return t;
          }
          return (await guestToken(module)).token;
        },
        dashboardUiConfig: { hideTitle: true, filters: { expanded: false } },
      });
      if (cancelled) dashboard.unmount();
      else unmount = () => dashboard.unmount();
    })().catch(() => {
      if (!cancelled) setFailed(true);
    });
    return () => {
      cancelled = true;
      unmount?.();
    };
  }, [module]);

  if (failed) {
    return <p className="text-sm text-destructive">{tx('Could not open the dashboard. Try again, or ask an administrator whether the dashboard service is running.')}</p>;
  }
  return (
    <div ref={mount}
      className="min-h-[70vh] w-full overflow-hidden rounded-md border bg-card [&>iframe]:h-[80vh] [&>iframe]:w-full [&>iframe]:border-0" />
  );
}

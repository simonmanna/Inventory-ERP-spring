import { useEffect, useRef, useState } from 'react';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import {
  Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle,
} from '@/components/ui/dialog';
import { getApiBaseUrl, resolveAssetUrl } from '@/lib/api';
import { dateTime } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';
import { apiMessage } from '@/lib/notify';
import { startLpLive, stopLpLive, type LiveSession } from '@/features/loss-prevention/vision-api';
import {
  EVIDENCE_STATE_LABELS, EVIDENCE_STATE_VARIANT, HEALTH_LABELS, HEALTH_REASON_LABELS, HEALTH_VARIANT, STREAM_LABELS,
  labelOf,
} from './vision-ui';

export function HealthBadge({ health, reasons }: { health: string; reasons?: string[] | null }) {
  const why = (reasons ?? []).map((r) => labelOf(HEALTH_REASON_LABELS, r)).join(', ');
  return (
    <Badge variant={HEALTH_VARIANT[health] ?? 'outline'} title={why || undefined}>
      {labelOf(HEALTH_LABELS, health)}{why ? ` · ${why}` : ''}
    </Badge>
  );
}

export function StreamBadge({ state }: { state: string | null }) {
  if (!state) return <span className="text-muted-foreground">—</span>;
  const variant = state === 'ok' ? 'default' : state === 'lost' ? 'destructive' : 'outline';
  return <Badge variant={variant}>{labelOf(STREAM_LABELS, state)}</Badge>;
}

export function EvidenceStateBadge({ state }: { state: string }) {
  return <Badge variant={EVIDENCE_STATE_VARIANT[state] ?? 'outline'}>{labelOf(EVIDENCE_STATE_LABELS, state)}</Badge>;
}

/** The signed stream link from the API, made loadable from this page (the API may live on another origin). */
function streamSource(url: string): string {
  if (/^https?:\/\//i.test(url)) return url;
  if (url.startsWith('/api/')) return resolveAssetUrl(url) ?? url;
  return `${getApiBaseUrl()}${url.startsWith('/') ? '' : '/'}${url}`;
}

/**
 * Live view of one camera, relayed by the server from the agent (MJPEG, a few pictures a second). The session is
 * opened when the window opens and stopped when it closes; both are recorded in the evidence access trail.
 */
export function LiveViewDialog({ camera, onClose }: { camera: { id: string; name: string }; onClose: () => void }) {
  const [session, setSession] = useState<LiveSession | null>(null);
  const [problem, setProblem] = useState<string | null>(null);
  const [streamLost, setStreamLost] = useState(false);
  const [attempt, setAttempt] = useState(0);
  const open = useRef<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    setSession(null);
    setProblem(null);
    setStreamLost(false);
    startLpLive(camera.id)
      .then((s) => {
        if (cancelled) {
          void stopLpLive(s.sessionId).catch(() => undefined);
          return;
        }
        open.current = s.sessionId;
        setSession(s);
      })
      .catch((e) => {
        if (!cancelled) setProblem(apiMessage(e, tx('Live view could not start')));
      });
    return () => {
      cancelled = true;
      const id = open.current;
      open.current = null;
      if (id) void stopLpLive(id).catch(() => undefined);
    };
  }, [camera.id, attempt]);

  return (
    <Dialog open onOpenChange={(o) => { if (!o) onClose(); }}>
      <DialogContent className="max-w-4xl">
        <DialogHeader>
          <DialogTitle>{camera.name} · {tx('Live view')}</DialogTitle>
          <DialogDescription>
            {tx('Opening and closing live view is recorded with your name. Nothing of the picture is stored here.')}
          </DialogDescription>
        </DialogHeader>
        <div className="relative w-full overflow-hidden rounded-md bg-black" style={{ aspectRatio: '16 / 9' }}>
          {session && !streamLost && (
            <img src={streamSource(session.streamUrl)} alt={camera.name} className="h-full w-full object-contain"
              onError={() => setStreamLost(true)} />
          )}
          {!session && !problem && (
            <p className="absolute inset-0 flex items-center justify-center text-sm text-white/70">{tx('Connecting to the agent…')}</p>
          )}
          {(problem || streamLost) && (
            <p className="absolute inset-0 flex items-center justify-center p-6 text-center text-sm text-white/80">
              {problem ?? tx('The picture stopped. The agent may be offline, or the session ended.')}
            </p>
          )}
        </div>
        {session && (
          <p className="text-xs text-muted-foreground">{tx('This session ends at')} {dateTime(session.expiresAt)}.</p>
        )}
        <DialogFooter>
          {(problem || streamLost) && (
            <Button variant="outline" onClick={() => setAttempt((a) => a + 1)}>{tx('Try again')}</Button>
          )}
          <Button variant="outline" onClick={onClose}>{tx('Close window')}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

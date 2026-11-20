import { useState } from 'react';
import { Camera, Film, Video } from 'lucide-react';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import {
  Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle,
} from '@/components/ui/dialog';
import { dateTime } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';
import { apiMessage, notify } from '@/lib/notify';
import {
  useLpFootage, useLpRangeClip, useLpStill, type FootageQuery,
} from '@/features/loss-prevention/vision-api';
import { EvidencePanel } from './EvidenceTab';
import { useFootageAvailable } from './use-footage';
import { EVIDENCE_KIND_LABELS, SELECT, clipLength, labelOf } from './vision-ui';
import { EvidenceStateBadge } from './vision-shared';

/** "View footage" for a POS sale or a ledger record; nothing when cameras are not available to this user. */
export function ViewFootageButton({ query, label, size = 'sm', variant = 'outline' }: {
  query: FootageQuery;
  /** What the footage is of, shown in the window's title (a receipt number, a record type). */
  label?: string;
  size?: 'sm' | 'default';
  variant?: 'outline' | 'ghost' | 'link';
}) {
  const available = useFootageAvailable();
  const [open, setOpen] = useState(false);
  if (!available) return null;
  return (
    <>
      <Button size={size} variant={variant} onClick={() => setOpen(true)}>
        <Video className="mr-1.5 h-4 w-4" />{tx('View footage')}
      </Button>
      {open && <FootageDialog query={query} label={label} onClose={() => setOpen(false)} />}
    </>
  );
}

/**
 * The footage around a sale, a record or a moment (LP1c): the evidence already kept for that window, opened in place,
 * or the window asked of the edge box and played as soon as it is uploaded. A still of the moment can be asked too.
 * Every request and view is recorded with the user's name.
 */
export function FootageDialog({ query, label, onClose }: { query: FootageQuery; label?: string; onClose: () => void }) {
  const { data, isLoading, isError, error } = useLpFootage(query);
  const clip = useLpRangeClip();
  const still = useLpStill();
  const [cameraId, setCameraId] = useState('');
  const [openId, setOpenId] = useState<string | null>(null);
  const [asked, setAsked] = useState<string | null>(null);
  const cameras = data?.cameras ?? [];
  const chosen = cameraId || cameras.find((c) => c.watches)?.id || (cameras.length === 1 ? cameras[0].id : '');
  const camera = cameras.find((c) => c.id === chosen);
  const usable = !!camera && camera.enabled && camera.agentStatus === 'active';
  const reason = label ? `${tx('Footage for')} ${label}` : tx('Footage view');

  const onShow = () => {
    if (!data || !chosen) return;
    clip.mutate({ cameraId: chosen, from: data.from, to: data.to, reason }, {
      onSuccess: (e) => { setAsked(e.id); setOpenId(e.id); },
      onError: (e) => notify.error(apiMessage(e, tx('Could not ask for the footage'))),
    });
  };
  const onStill = () => {
    if (!data || !chosen) return;
    still.mutate({ cameraId: chosen, at: data.at, reason }, {
      onSuccess: (e) => { setAsked(e.id); setOpenId(e.id); },
      onError: (e) => notify.error(apiMessage(e, tx('Could not ask for the still'))),
    });
  };

  return (
    <Dialog open onOpenChange={(o) => { if (!o) onClose(); }}>
      <DialogContent className="max-h-[90vh] max-w-3xl overflow-y-auto">
        <DialogHeader>
          <DialogTitle>{tx('Footage')}{label ? ` · ${label}` : ''}</DialogTitle>
          <DialogDescription>
            {data
              ? `${dateTime(data.at)} · ${tx('from')} ${dateTime(data.from)} ${tx('to')} ${dateTime(data.to)}`
              : tx('The cameras of the branch around this moment.')}
          </DialogDescription>
        </DialogHeader>

        {isLoading && <p className="text-sm text-muted-foreground">{tx('Loading…')}</p>}
        {isError && <p className="text-sm text-destructive">{apiMessage(error, tx('Could not load the footage.'))}</p>}

        {data && (
          <div className="space-y-4">
            <div className="space-y-1">
              <p className="text-sm font-medium">{tx('Already kept for this moment')} ({data.evidence.length})</p>
              <div className="space-y-1 rounded-md border p-2 text-xs">
                {data.evidence.length === 0 && <p className="text-muted-foreground">{tx('Nothing kept yet.')}</p>}
                {data.evidence.map((e) => (
                  <div key={e.id} className="flex flex-wrap items-center justify-between gap-2 py-1">
                    <button type="button" className="text-left hover:underline" aria-expanded={openId === e.id}
                      onClick={() => setOpenId(openId === e.id ? null : e.id)}>
                      {e.kind === 'snapshot' ? <Camera className="mr-1 inline h-3.5 w-3.5" /> : <Film className="mr-1 inline h-3.5 w-3.5" />}
                      {dateTime(e.startedAt)} · {e.cameraName ?? e.cameraId} · {e.kind === 'snapshot' ? labelOf(EVIDENCE_KIND_LABELS, e.kind) : clipLength(e.startedAt, e.endedAt)}
                    </button>
                    <EvidenceStateBadge state={e.state} />
                  </div>
                ))}
              </div>
            </div>

            {cameras.length === 0 ? (
              <p className="text-sm text-muted-foreground">{tx('No camera is set up at this branch.')}</p>
            ) : !data.inBuffer ? (
              <p className="text-sm text-muted-foreground">
                {tx('The edge box keeps video for')} {data.bufferDays} {tx('days; this moment is older, so only what was kept can be shown.')}
              </p>
            ) : (
              <div className="space-y-2 rounded-md border p-3">
                <label className="block space-y-1 text-sm">
                  <span>{tx('Camera')}</span>
                  <select className={SELECT} value={chosen} onChange={(e) => setCameraId(e.target.value)}>
                    <option value="">{tx('Choose…')}</option>
                    {cameras.map((c) => (
                      <option key={c.id} value={c.id}>
                        {c.name}{c.watches ? ` — ${tx('watches this till')}` : ''}{!c.enabled || c.agentStatus !== 'active' ? ` (${tx('unavailable')})` : ''}
                      </option>
                    ))}
                  </select>
                </label>
                {camera?.watches && <Badge variant="secondary">{tx('This camera watches the till of the sale')}</Badge>}
                <div className="flex flex-wrap gap-2">
                  <Button size="sm" disabled={!usable || clip.isPending} onClick={onShow}>
                    <Film className="mr-1.5 h-4 w-4" />{tx('Show footage')}
                  </Button>
                  <Button size="sm" variant="outline" disabled={!usable || still.isPending} onClick={onStill}>
                    <Camera className="mr-1.5 h-4 w-4" />{tx('Still at this moment')}
                  </Button>
                </div>
                <p className="text-xs text-muted-foreground">
                  {tx('The edge box uploads the recording when it is next online; it plays here as soon as it arrives. The request is recorded with your name.')}
                </p>
              </div>
            )}

            {openId && (
              <div className="rounded-md border p-3 text-sm">
                <EvidencePanel id={openId} autoPlay={openId === asked} onFrameSaved={(id) => setOpenId(id)} />
              </div>
            )}
          </div>
        )}
        <DialogFooter><Button variant="outline" onClick={onClose}>{tx('Close window')}</Button></DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

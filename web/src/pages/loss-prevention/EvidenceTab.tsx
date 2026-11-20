import { useEffect, useRef, useState } from 'react';
import { Archive, Camera, Download, ImageDown, Play, Scissors } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import {
  Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle,
} from '@/components/ui/dialog';
import { DataTable, type Column } from '@/components/data-table';
import { DataTablePagination, DateRangeFilter, FilterSelect, ListCard, ListToolbar } from '@/components/list';
import { dateTime, formatBytes } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';
import { apiMessage, notify } from '@/lib/notify';
import type { LedgerEvent } from '@/features/loss-prevention/api';
import {
  EVIDENCE_STATES, MAX_CLIP_MINUTES, blobErrorMessage, fetchLpEvidenceContent, fetchLpEvidenceExport, saveBlob,
  useLpCameras, useLpEvidence, useLpEvidenceList, useLpRangeClip, useLpStill, useRequestLpEvidence, useSaveLpFrame,
  type Evidence,
} from '@/features/loss-prevention/vision-api';
import {
  ACCESS_LABELS, CASE_STATE_LABELS, EVIDENCE_KIND_LABELS, EVIDENCE_ORIGIN_LABELS, EVIDENCE_STATE_LABELS, SELECT, TABLE,
  cameraChoices, clipLength, labelOf, toLocalInput, toNumber, useObjectUrl,
} from './vision-ui';
import { EvidenceStateBadge } from './vision-shared';

const EVIDENCE_KINDS = ['clip', 'snapshot'] as const;
const offsetText = (ms: number | null) => {
  if (ms === null) return '';
  const s = Math.floor(ms / 1000);
  return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, '0')}`;
};

/**
 * Video and still-image evidence (LP1, LP1c). Needs `lp:evidence`. A clip stays on the edge box until a case needs it or someone asks for
 * it; every view, download, export and request is written to its access trail, and the subject of a linked case never
 * sees it.
 */
export function EvidenceTab({ canConfigure }: { canConfigure: boolean }) {
  const [state, setState] = useState('');
  const [kind, setKind] = useState('');
  const [cameraId, setCameraId] = useState('');
  const [from, setFrom] = useState('');
  const [to, setTo] = useState('');
  const [page, setPage] = useState(1);
  const [pageSize, setPageSize] = useState(25);
  const [openId, setOpenId] = useState<string | null>(null);
  const [asking, setAsking] = useState<'' | 'clip' | 'still'>('');
  const { data, isLoading, isError } = useLpEvidenceList({
    state: state || undefined, kind: kind || undefined, cameraId: cameraId || undefined, from: from || undefined,
    to: to || undefined, page, pageSize,
  });
  const cameras = useLpCameras({}, canConfigure);
  const rows = data?.data ?? [];
  const choices = cameraChoices(cameras.data, rows, cameraId || undefined);
  const columns: Column<Evidence>[] = [
    { key: 'startedAt', header: tx('Recorded'), render: (e) => dateTime(e.startedAt) },
    { key: 'length', header: tx('Length'), render: (e) => (e.kind === 'snapshot' ? '—' : clipLength(e.startedAt, e.endedAt)) },
    {
      key: 'cameraName', header: tx('Camera'), render: (e) => (
        <div>
          <p>{e.cameraName ?? e.cameraId}</p>
          {e.zoneName && <p className="text-xs text-muted-foreground">{e.zoneName}</p>}
        </div>
      ),
    },
    {
      key: 'kind', header: tx('Kind'),
      render: (e) => `${labelOf(EVIDENCE_KIND_LABELS, e.kind)} · ${labelOf(EVIDENCE_ORIGIN_LABELS, e.origin)}`,
    },
    { key: 'byteSize', header: tx('Size'), className: 'text-right', render: (e) => formatBytes(toNumber(e.byteSize)) },
    { key: 'state', header: tx('State'), render: (e) => <EvidenceStateBadge state={e.state} /> },
    { key: 'caseIds', header: tx('Cases'), className: 'text-right', render: (e) => e.caseIds?.length ?? '—' },
  ];
  return (
    <>
      <p className="text-sm text-muted-foreground">
        {tx('Clips stay on the edge box until a case needs one or someone asks for it. Every view, download and export is recorded with your name.')}
        {' '}{tx('Still images are taken from the same recording.')}
      </p>
      <ListToolbar>
        <FilterSelect value={state} onChange={(v) => { setState(v); setPage(1); }} allLabel={tx('All states')}
          options={EVIDENCE_STATES.map((s) => ({ value: s, label: labelOf(EVIDENCE_STATE_LABELS, s) }))} />
        <FilterSelect value={kind} onChange={(v) => { setKind(v); setPage(1); }} allLabel={tx('Clips and stills')}
          options={EVIDENCE_KINDS.map((k) => ({ value: k, label: labelOf(EVIDENCE_KIND_LABELS, k) }))} />
        <FilterSelect value={cameraId} onChange={(v) => { setCameraId(v); setPage(1); }} allLabel={tx('All cameras')}
          options={choices.map((c) => ({ value: c.id, label: c.name }))} />
        <DateRangeFilter from={from} to={to} onChange={(f, t) => { setFrom(f); setTo(t); setPage(1); }} />
        <Button size="sm" variant="outline" onClick={() => setAsking('clip')}>
          <Scissors className="mr-1.5 h-4 w-4" />{tx('Clip from the buffer')}
        </Button>
        <Button size="sm" variant="outline" onClick={() => setAsking('still')}>
          <Camera className="mr-1.5 h-4 w-4" />{tx('Take a still')}
        </Button>
      </ListToolbar>
      <ListCard>
        <div className="overflow-x-auto">
          <DataTable columns={columns} data={rows} loading={isLoading} loadingRows={6} getRowId={(e) => e.id} {...TABLE}
            onRowClick={(e) => setOpenId(e.id)}
            emptyMessage={isError ? tx('Could not load the evidence. Try again.') : tx('No clips here.')} />
        </div>
      </ListCard>
      {data?.meta && (
        <DataTablePagination page={data.meta.page} pageSize={pageSize} total={data.meta.total} totalPages={data.meta.totalPages}
          onPageChange={setPage} onPageSizeChange={setPageSize} noun="clip" />
      )}
      {openId && <EvidenceDialog id={openId} onClose={() => setOpenId(null)} />}
      {asking === 'clip' && <RangeClipDialog cameras={choices} onClose={() => setAsking('')} />}
      {asking === 'still' && <StillDialog cameras={choices} onClose={() => setAsking('')} />}
    </>
  );
}

function EvidenceDialog({ id, onClose }: { id: string; onClose: () => void }) {
  return (
    <Dialog open onOpenChange={(o) => { if (!o) onClose(); }}>
      <DialogContent className="max-h-[90vh] max-w-3xl overflow-y-auto">
        <DialogHeader>
          <DialogTitle>{tx('Evidence')}</DialogTitle>
          <DialogDescription>
            {tx('The clip is checked against the fingerprint taken when it was recorded, at upload and again every time it is opened.')}
          </DialogDescription>
        </DialogHeader>
        <EvidencePanel id={id} />
        <DialogFooter><Button variant="outline" onClick={onClose}>{tx('Close window')}</Button></DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

const observationText = (e: LedgerEvent) => {
  const p: Record<string, unknown> = e.payload ?? {};
  const bits = ['class', 'object', 'to', 'direction', 'count', 'action', 'reason']
    .filter((k) => p[k] !== undefined && p[k] !== null && typeof p[k] !== 'object')
    .map((k) => `${k}: ${String(p[k])}`);
  return bits.join(' · ');
};

/**
 * One clip or still: facts, the player and downloads when uploaded, a request when still on the edge box, and its
 * trail. With `autoPlay` the media opens by itself once uploaded (the footage view waits for it this way). While a clip
 * plays, the frame on screen can be kept as a still of the clip.
 */
export function EvidencePanel({ id, caseId, autoPlay, onFrameSaved }: {
  id: string; caseId?: string; autoPlay?: boolean; onFrameSaved?: (frameId: string) => void;
}) {
  const { data: ev, isLoading, isError, refetch } = useLpEvidence(id);
  const request = useRequestLpEvidence();
  const saveFrame = useSaveLpFrame();
  const videoRef = useRef<HTMLVideoElement>(null);
  const [reason, setReason] = useState('');
  const [media, setMedia] = useState<Blob | null>(null);
  const mediaUrl = useObjectUrl(media);
  const [busy, setBusy] = useState<'' | 'play' | 'download' | 'export'>('');
  const [problem, setProblem] = useState<string | null>(null);
  const [autoTried, setAutoTried] = useState(false);

  const run = async (what: 'play' | 'download' | 'export') => {
    if (!ev) return;
    setBusy(what);
    setProblem(null);
    try {
      if (what === 'export') {
        const r = await fetchLpEvidenceExport(id);
        saveBlob(r.blob, r.fileName);
      } else {
        const r = await fetchLpEvidenceContent(id, what === 'download');
        if (what === 'download') saveBlob(r.blob, r.fileName);
        else setMedia(ev.contentType && r.blob.type !== ev.contentType ? new Blob([r.blob], { type: ev.contentType }) : r.blob);
      }
    } catch (e) {
      setProblem(await blobErrorMessage(e, tx('The clip could not be opened')));
    } finally {
      setBusy('');
      void refetch();
    }
  };
  const uploaded = ev?.state === 'uploaded';
  useEffect(() => {
    if (autoPlay && uploaded && !autoTried) {
      setAutoTried(true);
      void run('play');
    }
    // run reads the latest evidence; it only needs to fire once the upload is there.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [autoPlay, uploaded, autoTried]);

  if (isLoading) return <p className="text-muted-foreground">{tx('Loading…')}</p>;
  if (isError || !ev) return <p className="text-destructive">{tx('Could not load the evidence.')}</p>;

  const image = ev.kind === 'snapshot' || (ev.contentType ?? '').startsWith('image/');
  const onSaveFrame = () => {
    const v = videoRef.current;
    if (!v || !v.videoWidth) return;
    const canvas = document.createElement('canvas');
    canvas.width = v.videoWidth;
    canvas.height = v.videoHeight;
    canvas.getContext('2d')?.drawImage(v, 0, 0, canvas.width, canvas.height);
    const offsetMs = v.currentTime * 1000;
    canvas.toBlob((blob) => {
      if (!blob) {
        notify.error(tx('The frame could not be read from the player'));
        return;
      }
      saveFrame.mutate({ id, offsetMs, image: blob, reason: reason.trim() || undefined }, {
        onSuccess: (f) => { notify.success(tx('The frame was kept as a still image.')); onFrameSaved?.(f.id); },
        onError: (e) => notify.error(apiMessage(e, tx('Could not keep the frame'))),
      });
    }, 'image/jpeg', 0.92);
  };
  const onRequest = () => request.mutate({ id, caseId, reason: reason.trim() || undefined }, {
    onSuccess: () => { notify.success(tx('The agent was asked to upload the clip.')); setReason(''); },
    onError: (e) => notify.error(apiMessage(e, tx('Could not request the clip'))),
  });
  const caseNumber = (cid: string | null) => (cid ? ev.cases?.find((c) => c.id === cid)?.caseNumber ?? '' : '');
  const received = toNumber(ev.receivedBytes);
  const size = toNumber(ev.byteSize);

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-center gap-2 text-sm">
        <EvidenceStateBadge state={ev.state} />
        <span className="text-muted-foreground">
          {ev.cameraName ?? ev.cameraId}{ev.zoneName ? ` · ${ev.zoneName}` : ''} · {labelOf(EVIDENCE_KIND_LABELS, ev.kind)} · {labelOf(EVIDENCE_ORIGIN_LABELS, ev.origin)}
        </span>
      </div>
      <dl className="grid gap-x-6 gap-y-1 text-sm sm:grid-cols-2">
        <div className="flex justify-between gap-2"><dt className="text-muted-foreground">{tx('Recorded')}</dt><dd>{dateTime(ev.startedAt)}</dd></div>
        {image
          ? <div className="flex justify-between gap-2"><dt className="text-muted-foreground">{tx('Kind')}</dt><dd>{labelOf(EVIDENCE_KIND_LABELS, ev.kind)}</dd></div>
          : <div className="flex justify-between gap-2"><dt className="text-muted-foreground">{tx('Length')}</dt><dd>{clipLength(ev.startedAt, ev.endedAt)}</dd></div>}
        <div className="flex justify-between gap-2"><dt className="text-muted-foreground">{tx('Size')}</dt><dd>{formatBytes(size)}</dd></div>
        <div className="flex justify-between gap-2"><dt className="text-muted-foreground">{tx('Uploaded')}</dt><dd>{dateTime(ev.uploadedAt)}</dd></div>
        <div className="flex justify-between gap-2"><dt className="text-muted-foreground">{tx('Kept until')}</dt><dd>{dateTime(ev.retainUntil)}</dd></div>
        <div className="flex justify-between gap-2">
          <dt className="text-muted-foreground">{tx('Requested')}</dt>
          <dd className="text-right">{dateTime(ev.requestedAt)}{ev.requestReason ? ` — ${ev.requestReason}` : ''}</dd>
        </div>
        {ev.origin === 'frame' && (
          <div className="flex justify-between gap-2 sm:col-span-2">
            <dt className="text-muted-foreground">{tx('Saved from a clip at')}</dt>
            <dd>{offsetText(toNumber(ev.frameOffsetMs))}</dd>
          </div>
        )}
        <div className="flex justify-between gap-2 sm:col-span-2">
          <dt className="text-muted-foreground">SHA-256</dt>
          <dd className="truncate font-mono text-xs" title={ev.sha256}>{ev.sha256}</dd>
        </div>
      </dl>

      {ev.state === 'uploaded' && (
        <div className="space-y-2">
          {mediaUrl && (image
            ? <img src={mediaUrl} alt="" className="w-full rounded-md bg-black object-contain" />
            : <video ref={videoRef} src={mediaUrl} controls autoPlay className="w-full rounded-md bg-black" />)}
          <div className="flex flex-wrap gap-2">
            {!mediaUrl && (
              <Button size="sm" disabled={!!busy} onClick={() => void run('play')}>
                <Play className="mr-1.5 h-4 w-4" />{image ? tx('Show') : tx('Play')}
              </Button>
            )}
            <Button size="sm" variant="outline" disabled={!!busy} onClick={() => void run('download')}>
              <Download className="mr-1.5 h-4 w-4" />{tx('Download')}
            </Button>
            <Button size="sm" variant="outline" disabled={!!busy} onClick={() => void run('export')}>
              <Archive className="mr-1.5 h-4 w-4" />{tx('Export with manifest')}
            </Button>
            {mediaUrl && !image && (
              <Button size="sm" variant="outline" disabled={saveFrame.isPending} onClick={onSaveFrame}>
                <ImageDown className="mr-1.5 h-4 w-4" />{tx('Keep this frame as a still')}
              </Button>
            )}
          </div>
          <p className="text-xs text-muted-foreground">{tx('Opening, downloading and exporting are recorded with your name.')}</p>
          {ev.origin === 'frame' && (
            <p className="text-xs text-muted-foreground">{tx('Saved from the player: its fingerprint was taken when it was saved, not when it was recorded.')}</p>
          )}
        </div>
      )}
      {ev.state === 'on_agent' && (
        <div className="space-y-2 rounded-md border p-3">
          <p className="text-sm">{tx('The clip is still on the edge box. Ask the agent to upload it; it is uploaded the next time the agent is online.')}</p>
          <div className="flex flex-wrap gap-2">
            <Input className="max-w-sm" value={reason} maxLength={500} onChange={(e) => setReason(e.target.value)} placeholder={tx('Reason (optional)')} />
            <Button size="sm" disabled={request.isPending} onClick={onRequest}>{tx('Request upload')}</Button>
          </div>
        </div>
      )}
      {(ev.state === 'requested' || ev.state === 'uploading') && (
        <p className="text-sm text-muted-foreground">
          {tx('Waiting for the agent to upload the clip.')}
          {received !== null && size ? ` ${formatBytes(received)} / ${formatBytes(size)}` : ''}
        </p>
      )}
      {ev.state === 'missing' && (
        <p className="text-sm text-muted-foreground">{tx('The edge box no longer has this clip; its video buffer may have been overwritten.')}</p>
      )}
      {ev.state === 'integrity_failed' && (
        <p className="text-sm text-destructive">{tx('The upload did not match the fingerprint taken when the clip was recorded. It cannot be opened.')}</p>
      )}
      {ev.state === 'deleted' && (
        <p className="text-sm text-muted-foreground">
          {tx('Deleted under the retention rules')} {dateTime(ev.deletedAt)}{ev.deleteReason ? ` — ${ev.deleteReason}` : ''}
        </p>
      )}
      {problem && <p className="text-sm text-destructive">{problem}</p>}

      <div>
        <p className="mb-1 text-sm font-medium">{tx('Linked cases')} ({ev.cases?.length ?? 0})</p>
        <div className="space-y-1 rounded-md border p-2 text-xs">
          {(ev.cases ?? []).length === 0 && <p className="text-muted-foreground">{tx('Not linked to a case.')}</p>}
          {(ev.cases ?? []).map((c) => (
            <p key={c.id}>
              <span className="font-mono">{c.caseNumber}</span>
              {c.title ? ` · ${c.title}` : ''} · <span className="text-muted-foreground">{labelOf(CASE_STATE_LABELS, c.state)}</span>
            </p>
          ))}
        </div>
      </div>
      <div>
        <p className="mb-1 text-sm font-medium">{tx('Observations in the clip')} ({ev.observations?.length ?? 0})</p>
        <div className="max-h-40 space-y-1 overflow-y-auto rounded-md border p-2 text-xs">
          {(ev.observations ?? []).length === 0 && <p className="text-muted-foreground">{tx('None recorded.')}</p>}
          {(ev.observations ?? []).map((o) => (
            <div key={o.id} className="flex justify-between gap-2">
              <span>{dateTime(o.occurredAt)} · {o.type}</span>
              <span className="text-muted-foreground">{observationText(o)}</span>
            </div>
          ))}
        </div>
      </div>
      <div>
        <p className="mb-1 text-sm font-medium">{tx('Access trail')}</p>
        <div className="max-h-48 space-y-1 overflow-y-auto rounded-md border p-2 text-xs">
          {(ev.access ?? []).length === 0 && <p className="text-muted-foreground">{tx('Nobody has opened it yet.')}</p>}
          {(ev.access ?? []).map((x) => (
            <div key={x.id}>
              <span className="text-muted-foreground">{dateTime(x.createdAt)} · {x.userName ?? (x.userId ? tx('A user') : tx('System'))} · </span>
              <span className="font-medium">{labelOf(ACCESS_LABELS, x.action)}</span>
              {caseNumber(x.caseId) && <span className="text-muted-foreground"> · {caseNumber(x.caseId)}</span>}
            </div>
          ))}
        </div>
      </div>
    </div>
  );
}

/** A clip cut from the agent's buffer: a camera and a period of {@link MAX_CLIP_MINUTES} minutes at most. */
/** Asks the agent for a clip cut from its buffer; also offered by the research review (LP7) around an activity. */
export function RangeClipForm({ cameras, caseId, defaultCameraId, defaultFrom, defaultTo, defaultReason, onDone }: {
  cameras: { id: string; name: string }[];
  caseId?: string;
  defaultCameraId?: string;
  defaultFrom?: Date;
  defaultTo?: Date;
  defaultReason?: string;
  onDone: () => void;
}) {
  const start = defaultFrom ?? new Date(Date.now() - 5 * 60_000);
  const [cameraId, setCameraId] = useState(defaultCameraId ?? (cameras.length === 1 ? cameras[0].id : ''));
  const [from, setFrom] = useState(() => toLocalInput(start));
  const [to, setTo] = useState(() => toLocalInput(defaultTo ?? new Date(start.getTime() + 3 * 60_000)));
  const [reason, setReason] = useState(defaultReason ?? '');
  const clip = useLpRangeClip();
  const f = new Date(from);
  const t = new Date(to);
  const minutes = (t.getTime() - f.getTime()) / 60_000;
  const problem = !cameraId.trim() ? tx('Choose a camera.')
    : Number.isNaN(f.getTime()) || Number.isNaN(t.getTime()) ? tx('Enter the start and the end.')
      : minutes <= 0 ? tx('The end must be after the start.')
        : minutes > MAX_CLIP_MINUTES ? tx('A clip is 10 minutes at most.')
          : t.getTime() > Date.now() + 60_000 ? tx('The end cannot be in the future: the clip is cut from recorded video.')
            : null;
  const onAsk = () => clip.mutate({
    cameraId: cameraId.trim(), from: f.toISOString(), to: t.toISOString(), caseId, reason: reason.trim() || undefined,
  }, {
    onSuccess: () => { notify.success(tx('The agent was asked for the clip. It appears in the list once uploaded.')); onDone(); },
    onError: (e) => notify.error(apiMessage(e, tx('Could not request the clip'))),
  });
  return (
    <div className="space-y-3">
      <div className="space-y-1">
        <Label>{tx('Camera')}</Label>
        {cameras.length > 0 ? (
          <select className={SELECT} value={cameraId} onChange={(e) => setCameraId(e.target.value)}>
            <option value="">{tx('Choose…')}</option>
            {cameras.map((c) => <option key={c.id} value={c.id}>{c.name}</option>)}
          </select>
        ) : (
          <Input value={cameraId} onChange={(e) => setCameraId(e.target.value)} placeholder={tx('Camera id')} />
        )}
      </div>
      <div className="grid gap-2 sm:grid-cols-2">
        <div className="space-y-1">
          <Label>{tx('From')}</Label>
          <Input type="datetime-local" step={1} value={from} onChange={(e) => setFrom(e.target.value)} />
        </div>
        <div className="space-y-1">
          <Label>{tx('To')}</Label>
          <Input type="datetime-local" step={1} value={to} onChange={(e) => setTo(e.target.value)} />
        </div>
      </div>
      <div className="space-y-1">
        <Label>{tx('Reason')}</Label>
        <Input value={reason} maxLength={500} onChange={(e) => setReason(e.target.value)} placeholder={tx('Why the clip is needed (optional)')} />
      </div>
      {caseId && <p className="text-xs text-muted-foreground">{tx('The clip is linked to this case.')}</p>}
      {problem && <p className="text-xs text-destructive">{problem}</p>}
      <div className="flex justify-end">
        <Button disabled={!!problem || clip.isPending} onClick={onAsk}>{tx('Ask the agent for the clip')}</Button>
      </div>
    </div>
  );
}

function RangeClipDialog({ cameras, onClose }: { cameras: { id: string; name: string }[]; onClose: () => void }) {
  return (
    <Dialog open onOpenChange={(o) => { if (!o) onClose(); }}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>{tx('Clip from the buffer')}</DialogTitle>
          <DialogDescription>
            {tx('The edge box keeps recent video for a few days. Ask for up to 10 minutes of one camera; the request is recorded.')}
          </DialogDescription>
        </DialogHeader>
        <RangeClipForm cameras={cameras} onDone={onClose} />
      </DialogContent>
    </Dialog>
  );
}

/** Asks the agent for a still of one camera at a moment (default now), taken from its recording (LP1c). */
export function StillForm({ cameras, caseId, defaultCameraId, defaultAt, defaultReason, onDone }: {
  cameras: { id: string; name: string }[];
  caseId?: string;
  defaultCameraId?: string;
  defaultAt?: Date;
  defaultReason?: string;
  onDone: (evidenceId: string) => void;
}) {
  const [cameraId, setCameraId] = useState(defaultCameraId ?? (cameras.length === 1 ? cameras[0].id : ''));
  const [now, setNow] = useState(!defaultAt);
  const [at, setAt] = useState(() => toLocalInput(defaultAt ?? new Date()));
  const [reason, setReason] = useState(defaultReason ?? '');
  const still = useLpStill();
  const t = new Date(at);
  const problem = !cameraId.trim() ? tx('Choose a camera.')
    : !now && Number.isNaN(t.getTime()) ? tx('Enter the moment.')
      : !now && t.getTime() > Date.now() + 5_000 ? tx('The moment cannot be in the future: the still is taken from recorded video.')
        : null;
  const onAsk = () => still.mutate({
    cameraId: cameraId.trim(), at: now ? undefined : t.toISOString(), caseId, reason: reason.trim() || undefined,
  }, {
    onSuccess: (e) => { notify.success(tx('The agent was asked for the still. It appears once uploaded.')); onDone(e.id); },
    onError: (e) => notify.error(apiMessage(e, tx('Could not ask for the still'))),
  });
  return (
    <div className="space-y-3">
      <div className="space-y-1">
        <Label>{tx('Camera')}</Label>
        {cameras.length > 0 ? (
          <select className={SELECT} value={cameraId} onChange={(e) => setCameraId(e.target.value)}>
            <option value="">{tx('Choose…')}</option>
            {cameras.map((c) => <option key={c.id} value={c.id}>{c.name}</option>)}
          </select>
        ) : (
          <Input value={cameraId} onChange={(e) => setCameraId(e.target.value)} placeholder={tx('Camera id')} />
        )}
      </div>
      <label className="flex items-center gap-2 text-sm">
        <input type="checkbox" checked={now} onChange={(e) => setNow(e.target.checked)} />
        {tx('Right now')}
      </label>
      {!now && (
        <div className="space-y-1">
          <Label>{tx('Moment')}</Label>
          <Input type="datetime-local" step={1} value={at} onChange={(e) => setAt(e.target.value)} />
        </div>
      )}
      <div className="space-y-1">
        <Label>{tx('Reason')}</Label>
        <Input value={reason} maxLength={500} onChange={(e) => setReason(e.target.value)} placeholder={tx('Why the still is needed (optional)')} />
      </div>
      {caseId && <p className="text-xs text-muted-foreground">{tx('The still is linked to this case.')}</p>}
      {problem && <p className="text-xs text-destructive">{problem}</p>}
      <div className="flex justify-end">
        <Button disabled={!!problem || still.isPending} onClick={onAsk}>
          <Camera className="mr-1.5 h-4 w-4" />{tx('Ask the agent for the still')}
        </Button>
      </div>
    </div>
  );
}

function StillDialog({ cameras, onClose }: { cameras: { id: string; name: string }[]; onClose: () => void }) {
  return (
    <Dialog open onOpenChange={(o) => { if (!o) onClose(); }}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>{tx('Still image')}</DialogTitle>
          <DialogDescription>
            {tx('The edge box takes the recorded frame nearest the moment, stamps the time on it and uploads it with its fingerprint. The request is recorded.')}
          </DialogDescription>
        </DialogHeader>
        <StillForm cameras={cameras} onDone={onClose} />
      </DialogContent>
    </Dialog>
  );
}

/**
 * The video evidence of a case, shown inside the case window: the clips linked to it, each opening in place, and a
 * clip cut from the buffer around the first record of the case.
 */
export function CaseEvidence({ caseId, events, canConfigure }: {
  caseId: string; events: LedgerEvent[]; canConfigure: boolean;
}) {
  const { data, isLoading, isError } = useLpEvidenceList({ caseId, pageSize: 50 });
  const cameras = useLpCameras({}, canConfigure);
  const [open, setOpen] = useState<string | null>(null);
  const [asking, setAsking] = useState<'' | 'clip' | 'still'>('');
  const rows = data?.data ?? [];
  const eventCamera = events.map((e) => e.payload?.cameraId).find((v): v is string => typeof v === 'string');
  const times = events.map((e) => new Date(e.occurredAt).getTime()).filter((n) => !Number.isNaN(n));
  const firstAt = times.length ? new Date(Math.min(...times) - 60_000) : undefined;
  return (
    <div className="space-y-2">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <p className="text-sm font-medium">{tx('Video evidence')} ({data?.meta?.total ?? rows.length})</p>
        <div className="flex gap-2">
          <Button size="sm" variant="outline" onClick={() => setAsking((v) => (v === 'clip' ? '' : 'clip'))}>
            <Scissors className="mr-1.5 h-4 w-4" />{asking === 'clip' ? tx('Cancel') : tx('Clip from the buffer')}
          </Button>
          <Button size="sm" variant="outline" onClick={() => setAsking((v) => (v === 'still' ? '' : 'still'))}>
            <Camera className="mr-1.5 h-4 w-4" />{asking === 'still' ? tx('Cancel') : tx('Take a still')}
          </Button>
        </div>
      </div>
      {asking === 'clip' && (
        <div className="rounded-md border p-3">
          <RangeClipForm cameras={cameraChoices(cameras.data, rows, eventCamera)} caseId={caseId} defaultCameraId={eventCamera}
            defaultFrom={firstAt} onDone={() => setAsking('')} />
        </div>
      )}
      {asking === 'still' && (
        <div className="rounded-md border p-3">
          <StillForm cameras={cameraChoices(cameras.data, rows, eventCamera)} caseId={caseId} defaultCameraId={eventCamera}
            defaultAt={firstAt ? new Date(firstAt.getTime() + 60_000) : undefined} onDone={() => setAsking('')} />
        </div>
      )}
      <div className="space-y-1 rounded-md border p-2 text-xs">
        {isLoading && <p className="text-muted-foreground">{tx('Loading…')}</p>}
        {isError && <p className="text-destructive">{tx('Could not load the evidence.')}</p>}
        {!isLoading && !isError && rows.length === 0 && <p className="text-muted-foreground">{tx('No clips are linked to this case.')}</p>}
        {rows.map((e) => (
          <div key={e.id}>
            <div className="flex flex-wrap items-center justify-between gap-2 py-1">
              <button type="button" className="text-left hover:underline" aria-expanded={open === e.id}
                onClick={() => setOpen(open === e.id ? null : e.id)}>
                {dateTime(e.startedAt)} · {e.cameraName ?? e.cameraId}{e.zoneName ? ` · ${e.zoneName}` : ''} · {e.kind === 'snapshot' ? labelOf(EVIDENCE_KIND_LABELS, e.kind) : clipLength(e.startedAt, e.endedAt)}
              </button>
              <EvidenceStateBadge state={e.state} />
            </div>
            {open === e.id && <div className="mb-2 rounded-md border p-3 text-sm"><EvidencePanel id={e.id} caseId={caseId} /></div>}
          </div>
        ))}
      </div>
    </div>
  );
}

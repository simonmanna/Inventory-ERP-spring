import { useEffect, useRef, useState } from 'react';
import { tx } from '@/lib/i18n/tx';
import { apiMessage } from '@/lib/notify';
import {
  blobErrorMessage, fetchLpSnapshot, requestLpSnapshot, type Evidence, type LpCamera, type Point, type Snapshot,
} from '@/features/loss-prevention/vision-api';

/** Labels, helpers and hooks shared by the camera and evidence screens of loss prevention (LP1, LP2). */

export const SELECT = 'h-9 w-full rounded-md border bg-background px-2 text-sm';
export const TABLE = { cellClassName: 'py-2.5 px-4', headerRowClassName: 'h-10' } as const;

export const words = (code: string) => code.replace(/_/g, ' ');
export const labelOf = (labels: Record<string, string>, code: string | null | undefined) =>
  (code ? labels[code] ?? words(code) : '—');

export const AGENT_STATUS_LABELS: Record<string, string> = {
  pending: tx('Waiting for enrolment'), active: tx('Enrolled'), revoked: tx('Revoked'),
};
export const HEALTH_LABELS: Record<string, string> = {
  unknown: tx('Not heard from yet'), online: tx('Online'), degraded: tx('Degraded'), offline: tx('Offline'),
};
export const HEALTH_VARIANT: Record<string, 'default' | 'secondary' | 'destructive' | 'outline'> = {
  unknown: 'outline', online: 'default', degraded: 'secondary', offline: 'destructive',
};
export const HEALTH_REASON_LABELS: Record<string, string> = {
  stream_lost: tx('A camera has no picture'),
  disk_low: tx('Disk almost full'),
  clock_drift: tx('Clock is off'),
  queue_backlog: tx('Observations waiting to be sent'),
};
export const AGENT_EVENT_LABELS: Record<string, string> = {
  created: tx('Created'), code_issued: tx('Enrolment code issued'), enrolled: tx('Enrolled'), online: tx('Came online'),
  degraded: tx('Degraded'), offline: tx('Went offline'), revoked: tx('Revoked'), updated: tx('Changed'),
};
export const COMMAND_KIND_LABELS: Record<string, string> = {
  config_changed: tx('Read the settings again'), snapshot: tx('Take a still'), calibrate: tx('Calibrate a drawer'),
  clip_upload: tx('Upload a clip'), live_start: tx('Start live view'), live_stop: tx('Stop live view'),
};
export const COMMAND_STATE_LABELS: Record<string, string> = {
  pending: tx('Waiting for the box'), delivered: tx('Taken by the box'), done: tx('Done'), failed: tx('Failed'),
  expired: tx('Expired'),
};
export const STREAM_LABELS: Record<string, string> = {
  ok: tx('Picture OK'), connecting: tx('Connecting'), lost: tx('No picture'), disabled: tx('Switched off'),
};
export const ZONE_LABELS: Record<string, string> = {
  cash_drawer: tx('Cash drawer'),
  checkout: tx('Checkout, customer side'),
  bar_counter: tx('Bar counter'),
  kitchen_pass: tx('Kitchen pass'),
  cafe_counter: tx('Cafe counter'),
  dining: tx('Dining area'),
  storage: tx('Storage'),
  entrance: tx('Entrance'),
  scan_area: tx('Scanner, retail checkout'),
  queue: tx('Queue'),
};
/** What a zone feeds in this release; a type no rule or metric reads yet falls back to a general text. */
export const ZONE_USES: Record<string, string> = {
  cash_drawer: tx('Used by rule V-C1: drawer opened with no sale, refund or cash movement near it.'),
  checkout: tx('Used by rules V-C2 and V-C3: refunds with no customer at the till, customers served with no sale.'),
  bar_counter: tx('Used by rules V-B1 and V-B2: pours seen with no drink sale near them, or with no meter reading.'),
  kitchen_pass: tx('Used by rules V-K1 and V-K2: more plates leaving the kitchen than the tickets account for, and tickets marked served with no plate seen leaving.'),
  cafe_counter: tx('Used by rule V-F1: more items handed over at the counter than were sold. Linked to the drinks station, also by rule V-F2: fewer items handed over than the station prepared.'),
  scan_area: tx('Used by rules V-R1 and V-R2, which are off until turned on: items passed over or around the scanner, compared with the sale. Draw the area over the scanner and link the till.'),
  storage: tx('Used by rule V-S1 when linked to a stock location: goods carried out of the store room with no stock movement recorded.'),
  entrance: tx('Used by the insights for footfall and conversion. It counts people crossing the line and never identifies anyone.'),
  queue: tx('Used by the insights for queue length and waiting time. It counts people and never identifies anyone.'),
  dining: tx('Used by the insights for table occupancy and seatings, and, when linked to a table, the wait to the first order and rule V-K3: plates delivered while the table had no open order. It counts people and plates and never identifies anyone.'),
};
export const LINK_LABELS: Record<string, string> = {
  till: tx('Till'), station: tx('Station'), table_area: tx('Table area'), counter: tx('Counter'),
  location: tx('Stock location'), table: tx('Table'),
};
export const PARAM_LABELS: Record<string, string> = {
  method: tx('Detection method'),
  openThreshold: tx('Change that counts as open (0 to 1)'),
  minOpenSeconds: tx('Open for at least (seconds)'),
  minClosedSeconds: tx('Closed for at least (seconds)'),
  preRollSeconds: tx('Clip starts this many seconds before'),
  postRollSeconds: tx('Clip continues this many seconds after'),
  presenceMethod: tx('How presence is detected'),
  minPresenceSeconds: tx('Present for at least (seconds)'),
  clipMinDwellSeconds: tx('Keep a clip when someone stays (seconds)'),
  occupancyEverySeconds: tx('Count every (seconds)'),
  classes: tx('Objects to count (comma-separated)'),
  objects: tx('Objects to watch (comma-separated)'),
  minConfidence: tx('Minimum confidence (0 to 1)'),
  flowDirection: tx('Direction items travel, from the unscanned side to the bagging side'),
  laneMargin: tx('Lane width beside the scanner (0 to 1)'),
  minWaitSeconds: tx('Waiting for at least (seconds)'),
};
/** Values of the text parameters with a closed set of values; others show their code in words. */
export const PARAM_CHOICE_LABELS: Record<string, string> = {
  left_to_right: tx('Left to right'),
  right_to_left: tx('Right to left'),
  top_to_bottom: tx('Top to bottom'),
  bottom_to_top: tx('Bottom to top'),
};
export const EVIDENCE_STATE_LABELS: Record<string, string> = {
  on_agent: tx('On the edge box'),
  requested: tx('Upload requested'),
  uploading: tx('Uploading'),
  uploaded: tx('Uploaded'),
  missing: tx('No longer on the edge box'),
  integrity_failed: tx('Failed the integrity check'),
  deleted: tx('Deleted under retention'),
};
export const EVIDENCE_STATE_VARIANT: Record<string, 'default' | 'secondary' | 'destructive' | 'outline'> = {
  on_agent: 'outline', requested: 'secondary', uploading: 'secondary', uploaded: 'default', missing: 'outline',
  integrity_failed: 'destructive', deleted: 'outline',
};
export const EVIDENCE_KIND_LABELS: Record<string, string> = { clip: tx('Clip'), snapshot: tx('Still image') };
export const EVIDENCE_ORIGIN_LABELS: Record<string, string> = {
  flagged: tx('Kept by the agent'), range: tx('Cut on request'),
};
export const ACCESS_LABELS: Record<string, string> = {
  view: tx('Viewed'), download: tx('Downloaded'), export: tx('Exported'), request: tx('Upload requested'),
  snapshot: tx('Still image taken'), live_view_start: tx('Live view opened'), live_view_stop: tx('Live view closed'),
  uploaded: tx('Uploaded'), integrity_failed: tx('Integrity check failed'), missing: tx('Reported missing'),
  deleted: tx('Deleted'),
};
export const CASE_STATE_LABELS: Record<string, string> = {
  open: tx('Not started'), reviewing: tx('In review'), escalated: tx('Escalated'), closed: tx('Closed'),
};
export const SETTING_LABELS: Record<string, { label: string; unit: string }> = {
  visionSettleMinutes: { label: tx('Wait before reconciling camera observations'), unit: tx('minutes') },
  agentSilentMinutes: { label: tx('An agent counts as offline after'), unit: tx('minutes without a heartbeat') },
  maxClockDriftMs: { label: tx('Largest clock difference allowed'), unit: tx('milliseconds') },
  evidenceBufferDays: { label: tx('Video kept on the edge box'), unit: tx('days') },
  evidenceCaseDays: { label: tx('Uploaded clip of an open case kept'), unit: tx('days after upload') },
  evidenceClosedCaseDays: { label: tx('Other uploaded clips kept'), unit: tx('days after the case closed or the upload') },
  heartbeatRetentionDays: { label: tx('Heartbeat history kept'), unit: tx('days') },
};

/** A number from the API (a bigint may arrive as a string), or null. */
export const toNumber = (v: number | string | null | undefined): number | null =>
  (v === null || v === undefined || v === '' || Number.isNaN(Number(v)) ? null : Number(v));

/** `m:ss` between two instants. */
export function clipLength(from: string, to: string): string {
  const s = Math.max(0, Math.round((new Date(to).getTime() - new Date(from).getTime()) / 1000));
  if (Number.isNaN(s)) return '—';
  return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, '0')}`;
}

/** The value of a `datetime-local` input for a date, in the browser's time zone. */
export function toLocalInput(d: Date): string {
  const p = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}T${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`;
}

/** Cameras to choose from: the camera list when the viewer may read it, else the cameras seen on evidence rows. */
export function cameraChoices(cameras: LpCamera[] | undefined, rows: Evidence[], extraId?: string) {
  const out = new Map<string, string>();
  for (const c of cameras ?? []) out.set(c.id, c.name);
  for (const r of rows) if (!out.has(r.cameraId)) out.set(r.cameraId, r.cameraName ?? r.cameraId);
  if (extraId && !out.has(extraId)) out.set(extraId, extraId);
  return [...out].map(([id, name]) => ({ id, name }));
}

export const clamp01 = (n: number) => Math.min(1, Math.max(0, n));
export const round4 = (n: number) => Math.round(n * 10_000) / 10_000;
export const centroid = (ps: Point[]): Point => [
  ps.reduce((a, p) => a + p[0], 0) / Math.max(1, ps.length),
  ps.reduce((a, p) => a + p[1], 0) / Math.max(1, ps.length),
];

/** An object URL for a blob, revoked when the blob changes or the component goes away. */
export function useObjectUrl(blob: Blob | null | undefined): string | null {
  const [url, setUrl] = useState<string | null>(null);
  useEffect(() => {
    if (!blob) {
      setUrl(null);
      return undefined;
    }
    const u = URL.createObjectURL(blob);
    setUrl(u);
    return () => URL.revokeObjectURL(u);
  }, [blob]);
  return url;
}

export type SnapshotState = 'idle' | 'loading' | 'waiting' | 'none' | 'timeout' | 'error';

/**
 * The latest still of a camera (kept in server memory for 10 minutes). `refresh()` asks the agent for a new one and
 * polls for 30 seconds at most; a new still is told from the old one by its `X-Snapshot-Taken-At` header, or by its
 * size when the server sends no header.
 */
export function useCameraSnapshot(cameraId: string, enabled: boolean) {
  const [snapshot, setSnapshot] = useState<Snapshot | null>(null);
  const [state, setState] = useState<SnapshotState>('idle');
  const [error, setError] = useState<string | null>(null);
  const current = useRef<Snapshot | null>(null);
  const timer = useRef<number | undefined>(undefined);
  const generation = useRef(0);

  useEffect(() => {
    if (!enabled) return undefined;
    const gen = ++generation.current;
    setState('loading');
    setError(null);
    fetchLpSnapshot(cameraId)
      .then((s) => {
        if (gen !== generation.current) return;
        current.current = s;
        setSnapshot(s);
        setState(s ? 'idle' : 'none');
      })
      .catch(async (e) => {
        const message = await blobErrorMessage(e, tx('Could not load the picture'));
        if (gen !== generation.current) return;
        setError(message);
        setState('error');
      });
    return () => {
      generation.current += 1;
      window.clearTimeout(timer.current);
    };
  }, [cameraId, enabled]);

  const refresh = async () => {
    window.clearTimeout(timer.current);
    const gen = ++generation.current;
    const before = current.current;
    setError(null);
    setState('waiting');
    try {
      await requestLpSnapshot(cameraId);
    } catch (e) {
      if (gen === generation.current) {
        setError(apiMessage(e, tx('Could not ask the agent for a picture')));
        setState('error');
      }
      return;
    }
    let tries = 0;
    const poll = async () => {
      if (gen !== generation.current) return;
      tries += 1;
      try {
        const s = await fetchLpSnapshot(cameraId);
        if (gen !== generation.current) return;
        const fresh = !!s && (!before
          || (s.takenAt && before.takenAt ? s.takenAt !== before.takenAt : s.blob.size !== before.blob.size));
        if (s && fresh) {
          current.current = s;
          setSnapshot(s);
          setState('idle');
          return;
        }
      } catch (e) {
        const message = await blobErrorMessage(e, tx('Could not load the picture'));
        if (gen !== generation.current) return;
        setError(message);
        setState('error');
        return;
      }
      if (tries >= 15) {
        setState('timeout');
        return;
      }
      timer.current = window.setTimeout(poll, 2000);
    };
    timer.current = window.setTimeout(poll, 1500);
  };

  return { snapshot, state, error, refresh, enabled };
}

export type CameraSnapshot = ReturnType<typeof useCameraSnapshot>;

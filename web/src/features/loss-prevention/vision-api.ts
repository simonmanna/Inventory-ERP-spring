import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from '@/lib/api';
import { apiMessage } from '@/lib/notify';
import type { Filters, LedgerEvent, Paged } from './api';

/**
 * Loss prevention with cameras (ADR-S077, LP1 camera platform and LP2 cashier vision): edge agents, cameras, zones,
 * video evidence and live view. Every route needs the `lpVision` licence feature, so the screens using these hooks are
 * only offered once the licence is known to include it (a refused feature answers 402 and opens the licence dialog).
 *
 * The backend never connects to a camera: an agent on the customer's network reads the cameras, keeps the video and
 * uploads a clip only when a case needs it or an investigator asks for it.
 */

type Num = number | string | null;
export type Point = [number, number];
export type AgentStatus = 'pending' | 'active' | 'revoked';
export type AgentHealth = 'unknown' | 'online' | 'degraded' | 'offline';
export type StreamState = 'ok' | 'connecting' | 'lost' | 'disabled';
export type ZoneType =
  | 'cash_drawer' | 'checkout' | 'bar_counter' | 'kitchen_pass' | 'cafe_counter' | 'dining' | 'storage' | 'entrance'
  | 'scan_area' | 'queue';
/** `table` (LP6b) is a POS table of this ERP; `till` a cash register; `location` a stock location. */
export type LinkedType = 'till' | 'station' | 'table_area' | 'counter' | 'location' | 'table';
export type ZoneShape = 'area' | 'line';
export type Side = 'left' | 'right';
export type EvidenceState =
  | 'on_agent' | 'requested' | 'uploading' | 'uploaded' | 'missing' | 'integrity_failed' | 'deleted';

export interface LpAgent {
  id: string;
  branchId: string;
  /** Not fixed by the spec: the branch name joined for the list. */
  branchName?: string | null;
  name: string;
  status: AgentStatus;
  health: AgentHealth;
  healthReasons: string[] | null;
  enrolCodeExpiresAt: string | null;
  apiClientId: string | null;
  hostname: string | null;
  agentVersion: string | null;
  modelVersion: string | null;
  desiredModelVersion: string | null;
  desiredAgentVersion: string | null;
  configVersion: number | null;
  reportedConfigVersion: number | null;
  lastHeartbeatAt: string | null;
  observedThrough: string | null;
  clockDriftMs: Num;
  diskFreeBytes: Num;
  diskTotalBytes: Num;
  queueLength: Num;
  coverageMarkedAt: string | null;
  enrolledAt: string | null;
  revokedAt: string | null;
  revokedBy: string | null;
  revokeReason: string | null;
  createdAt: string;
  updatedAt: string;
  /** Not fixed by the spec: cameras of the agent, for the list. */
  cameraCount?: number | null;
}

/** `POST /agents` and `POST /agents/{id}/enrolment-code`: the code is shown once and never again. */
export interface EnrolledAgent extends LpAgent {
  enrolmentCode: string;
  enrolmentCodeExpiresAt: string;
}

export interface Heartbeat {
  id: string;
  receivedAt: string;
  agentVersion: string | null;
  modelVersion: string | null;
  configVersion: number | null;
  clockDriftMs: Num;
  diskFreeBytes: Num;
  diskTotalBytes: Num;
  queueLength: Num;
  observedThrough: string | null;
  cameras: unknown;
  health: AgentHealth;
  healthReasons: string[] | null;
}

export interface AgentEvent {
  id: string;
  kind: string;
  actorId: string | null;
  actorName?: string | null;
  data: Record<string, unknown> | null;
  createdAt: string;
}

export interface LpAgentDetail extends LpAgent {
  cameras: LpCamera[];
  heartbeats: Heartbeat[];
  timeline: AgentEvent[];
}

export interface VisionModel {
  version: string;
  detector?: string | null;
  classes?: string[] | null;
  inputSize?: number | null;
}

export interface EdgeRelease {
  version: string;
  image?: string | null;
  digest?: string | null;
}

/** `GET /models`: what this installation offers (`LP_VISION_MODELS`, `LP_EDGE_RELEASES`). */
export interface LpModels {
  models: VisionModel[];
  releases: EdgeRelease[];
}

export interface LpCamera {
  id: string;
  branchId: string;
  agentId: string;
  /** Not fixed by the spec: the agent name joined for the list. */
  agentName?: string | null;
  name: string;
  streamAlias: string;
  enabled: boolean;
  analysed: boolean;
  notes: string | null;
  streamState: StreamState | null;
  fps: Num;
  lastFrameAt: string | null;
  createdAt: string;
  updatedAt: string;
  /** Not fixed by the spec: active zones of the camera, for the list. */
  zoneCount?: number | null;
}

export interface LpZone {
  id: string;
  branchId: string;
  cameraId: string;
  name: string;
  zoneType: ZoneType;
  /** `[x, y]` from 0 to 1, x to the right and y down. Exactly 2 points is a counting line, A then B. */
  polygon: Point[];
  inSide: Side | null;
  linkedType: LinkedType | null;
  linkedId: string | null;
  params: Record<string, unknown> | null;
  enabled: boolean;
  retiredAt: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface LpCameraDetail extends LpCamera {
  zones: LpZone[];
}

export interface CameraCreate {
  agentId: string;
  name: string;
  streamAlias: string;
  enabled: boolean;
  analysed: boolean;
  notes: string | null;
}

/** `PUT /cameras/{id}`: a camera never moves to another agent. */
export type CameraUpdate = Omit<CameraCreate, 'agentId'>;

export interface ZoneUpdate {
  name: string;
  polygon: Point[];
  inSide: Side | null;
  linkedType: LinkedType | null;
  linkedId: string | null;
  params: Record<string, unknown>;
  enabled: boolean;
}

/** `POST /zones`; the type of a zone never changes afterwards. */
export interface ZoneCreate extends ZoneUpdate {
  cameraId: string;
  zoneType: ZoneType;
}

export interface LiveSession {
  sessionId: string;
  streamUrl: string;
  expiresAt: string;
}

export interface Evidence {
  id: string;
  branchId: string;
  agentId: string;
  cameraId: string;
  /** Not fixed by the spec: joined for the list. */
  cameraName?: string | null;
  zoneId: string | null;
  /** Not fixed by the spec: joined for the list. */
  zoneName?: string | null;
  clipId: string;
  kind: 'clip' | 'snapshot';
  origin: 'flagged' | 'range';
  startedAt: string;
  endedAt: string;
  sha256: string;
  byteSize: Num;
  contentType: string | null;
  state: EvidenceState;
  observationEventIds: string[] | null;
  requestedAt: string | null;
  requestedBy: string | null;
  requestReason: string | null;
  receivedBytes: Num;
  uploadedAt: string | null;
  retainUntil: string | null;
  deletedAt: string | null;
  deleteReason: string | null;
  createdAt: string;
  updatedAt: string;
  /** Not fixed by the spec: the cases the clip is linked to. */
  caseIds?: string[] | null;
}

export interface EvidenceAccess {
  id: string;
  action: string;
  userId: string | null;
  userName?: string | null;
  caseId: string | null;
  detail: unknown;
  createdAt: string;
}

export interface EvidenceDetail extends Evidence {
  cases?: { id: string; caseNumber: string; state: string; title?: string | null }[] | null;
  observations?: LedgerEvent[] | null;
  access?: EvidenceAccess[] | null;
}

/** `GET/PUT /loss-prevention/settings`. A PUT replaces the whole map of its scope. */
export interface LpSettings {
  defaults: Record<string, number>;
  organization: Record<string, number>;
  branches: Record<string, Record<string, number>>;
  effective: Record<string, number>;
}

export interface Choice {
  id: string;
  name: string;
  code?: string | null;
  branchId?: string | null;
  isActive?: boolean | null;
}

export interface Snapshot {
  blob: Blob;
  /** `X-Snapshot-Taken-At`, when the server sends it. */
  takenAt: string | null;
}

// ─── Contract facts (docs/spec/loss-prevention.md, "Zones") ─────────────────────────────────────────────────────────

export const ZONE_TYPES: ZoneType[] = [
  'cash_drawer', 'checkout', 'scan_area', 'queue', 'bar_counter', 'kitchen_pass', 'cafe_counter', 'dining', 'storage',
  'entrance',
];

/** Which links a zone type takes; the first is the default when one is required. */
export const ZONE_LINKS: Record<ZoneType, { types: LinkedType[]; required: boolean }> = {
  cash_drawer: { types: ['till'], required: true },
  checkout: { types: ['till'], required: true },
  bar_counter: { types: ['station', 'location'], required: false },
  kitchen_pass: { types: ['station'], required: false },
  cafe_counter: { types: ['counter', 'till'], required: false },
  dining: { types: ['table_area', 'table'], required: false },
  storage: { types: ['location'], required: false },
  entrance: { types: [], required: false },
  scan_area: { types: ['till'], required: true },
  queue: { types: ['till'], required: false },
};

/** The shapes a zone type allows; the first is the default for a new zone. */
export const ZONE_SHAPES: Record<ZoneType, ZoneShape[]> = {
  cash_drawer: ['area'],
  checkout: ['area'],
  bar_counter: ['area'],
  kitchen_pass: ['line', 'area'],
  cafe_counter: ['line', 'area'],
  dining: ['area'],
  storage: ['line', 'area'],
  entrance: ['line'],
  scan_area: ['area'],
  queue: ['area'],
};

/** Defaults the agent merges `params` over; every type also has `minConfidence` 0.5. */
export const ZONE_DEFAULT_PARAMS: Record<ZoneType, Record<string, number | string | string[]>> = {
  cash_drawer: {
    method: 'reference_diff', openThreshold: 0.18, minOpenSeconds: 1, minClosedSeconds: 2, preRollSeconds: 20,
    postRollSeconds: 30, minConfidence: 0.5,
  },
  checkout: {
    presenceMethod: 'detector', minPresenceSeconds: 3, clipMinDwellSeconds: 90, occupancyEverySeconds: 60,
    preRollSeconds: 10, postRollSeconds: 10, minConfidence: 0.5,
  },
  bar_counter: { objects: ['bottle'], preRollSeconds: 20, postRollSeconds: 30, minConfidence: 0.5 },
  kitchen_pass: { classes: ['plate'], preRollSeconds: 20, postRollSeconds: 30, minConfidence: 0.5 },
  cafe_counter: { classes: ['cup'], preRollSeconds: 20, postRollSeconds: 30, minConfidence: 0.5 },
  dining: { classes: ['plate', 'person'], occupancyEverySeconds: 300, minConfidence: 0.5 },
  storage: { classes: ['person', 'bag'], preRollSeconds: 20, postRollSeconds: 30, minConfidence: 0.5 },
  entrance: { classes: ['person'], minConfidence: 0.5 },
  scan_area: {
    classes: ['item'], flowDirection: 'left_to_right', laneMargin: 0.15, preRollSeconds: 10, postRollSeconds: 10,
    minConfidence: 0.5,
  },
  queue: { classes: ['person'], minWaitSeconds: 5, occupancyEverySeconds: 60, minConfidence: 0.5 },
};

/** Text parameters with a closed set of values. */
export const PARAM_CHOICES: Record<string, string[]> = {
  method: ['reference_diff'],
  presenceMethod: ['detector', 'motion'],
  /** The way items travel on the picture from the unscanned side to the bagging side (`scan_area`). */
  flowDirection: ['left_to_right', 'right_to_left', 'top_to_bottom', 'bottom_to_top'],
};

/** Parameters that are a fraction from 0 to 1. */
export const FRACTION_PARAMS = new Set(['openThreshold', 'minConfidence', 'laneMargin']);

/**
 * A parameter of any zone type, passed to the agent unchanged (LP7): `true` makes the agent also report the activities
 * its recognizers see in the zone (`vision.activity`), for review only. No rule reads them.
 */
export const ACTIVITY_RESEARCH_PARAM = 'activityResearch';

/** A camera is named by an alias; the stream address and its password stay on the edge box. */
export const STREAM_ALIAS_RE = /^[a-z0-9][a-z0-9_-]{0,63}$/;
export const LINK_MAX_LENGTH = 200;
export const MAX_CLIP_MINUTES = 10;

export const EVIDENCE_STATES: EvidenceState[] = [
  'on_agent', 'requested', 'uploading', 'uploaded', 'missing', 'integrity_failed', 'deleted',
];

/** Settings keys added by LP1 to `GET/PUT /loss-prevention/settings`. */
export const VISION_SETTINGS = [
  'visionSettleMinutes', 'agentSilentMinutes', 'maxClockDriftMs', 'evidenceBufferDays', 'evidenceCaseDays',
  'evidenceClosedCaseDays', 'heartbeatRetentionDays',
] as const;

// ─── Queries and changes ─────────────────────────────────────────────────────────────────────────────────────────────

const KEY = ['loss-prevention', 'vision'] as const;
const SETTINGS_KEY = ['loss-prevention', 'settings'] as const;
const BASE = '/loss-prevention';

/** A change to agents, cameras, zones or evidence; every vision list is read again afterwards. */
function useVisionChange<V, R>(fn: (v: V) => Promise<R>) {
  const qc = useQueryClient();
  return useMutation({ mutationFn: fn, onSuccess: () => qc.invalidateQueries({ queryKey: KEY }) });
}

export const useLpAgents = (enabled = true) => useQuery({
  queryKey: [...KEY, 'agents'],
  enabled,
  refetchInterval: 30_000,
  queryFn: async () => (await api.get<LpAgent[]>(`${BASE}/agents`)).data,
});

export const useLpAgent = (id: string | null) => useQuery({
  queryKey: [...KEY, 'agent', id],
  enabled: !!id,
  refetchInterval: 30_000,
  queryFn: async () => (await api.get<LpAgentDetail>(`${BASE}/agents/${id}`)).data,
});

export const useCreateLpAgent = () => useVisionChange(async (body: { branchId: string; name: string }) =>
  (await api.post<EnrolledAgent>(`${BASE}/agents`, body)).data);

export const useUpdateLpAgent = () => useVisionChange(async (v: {
  id: string; body: { name: string; desiredModelVersion: string | null; desiredAgentVersion: string | null };
}) => (await api.put<LpAgent>(`${BASE}/agents/${v.id}`, v.body)).data);

export const useNewLpEnrolmentCode = () => useVisionChange(async (id: string) =>
  (await api.post<EnrolledAgent>(`${BASE}/agents/${id}/enrolment-code`, {})).data);

export const useRevokeLpAgent = () => useVisionChange(async (v: { id: string; reason?: string }) =>
  (await api.post<LpAgent>(`${BASE}/agents/${v.id}/revoke`, { reason: v.reason })).data);

export const useLpModels = (enabled = true) => useQuery({
  queryKey: [...KEY, 'models'],
  enabled,
  staleTime: 5 * 60_000,
  queryFn: async () => (await api.get<LpModels>(`${BASE}/models`)).data,
});

export const useLpCameras = (filters: Filters, enabled = true) => useQuery({
  queryKey: [...KEY, 'cameras', filters],
  enabled,
  refetchInterval: 60_000,
  queryFn: async () => (await api.get<LpCamera[]>(`${BASE}/cameras`, { params: filters })).data,
});

export const useLpCamera = (id: string | null) => useQuery({
  queryKey: [...KEY, 'camera', id],
  enabled: !!id,
  queryFn: async () => (await api.get<LpCameraDetail>(`${BASE}/cameras/${id}`)).data,
});

export const useCreateLpCamera = () => useVisionChange(async (body: CameraCreate) =>
  (await api.post<LpCamera>(`${BASE}/cameras`, body)).data);

export const useUpdateLpCamera = () => useVisionChange(async (v: { id: string; body: CameraUpdate }) =>
  (await api.put<LpCamera>(`${BASE}/cameras/${v.id}`, v.body)).data);

export const useCreateLpZone = () => useVisionChange(async (body: ZoneCreate) =>
  (await api.post<LpZone>(`${BASE}/zones`, body)).data);

export const useUpdateLpZone = () => useVisionChange(async (v: { id: string; body: ZoneUpdate }) =>
  (await api.put<LpZone>(`${BASE}/zones/${v.id}`, v.body)).data);

/** Retires the zone; its past observations keep pointing at it. */
export const useRetireLpZone = () => useVisionChange(async (id: string) =>
  (await api.delete(`${BASE}/zones/${id}`)).data);

/** Asks the agent to take the current picture as the zone's resting state (a closed drawer). */
export const useCalibrateLpZone = () => useMutation({
  mutationFn: async (id: string) => (await api.post<{ commandId: string }>(`${BASE}/zones/${id}/calibrate`, {})).data,
});

const busy = (s: EvidenceState | undefined) => s === 'requested' || s === 'uploading';

export const useLpEvidenceList = (filters: Filters, enabled = true) => useQuery({
  queryKey: [...KEY, 'evidence', filters],
  enabled,
  // A requested clip arrives when the agent uploads it; keep the list moving while one is on its way.
  refetchInterval: (q) => (q.state.data?.data.some((e) => busy(e.state)) ? 10_000 : false),
  queryFn: async () => (await api.get<Paged<Evidence>>(`${BASE}/evidence`, { params: filters })).data,
});

export const useLpEvidence = (id: string | null) => useQuery({
  queryKey: [...KEY, 'evidence-item', id],
  enabled: !!id,
  refetchInterval: (q) => (busy(q.state.data?.state) ? 5_000 : false),
  queryFn: async () => (await api.get<EvidenceDetail>(`${BASE}/evidence/${id}`)).data,
});

export const useRequestLpEvidence = () => useVisionChange(async (v: { id: string; caseId?: string; reason?: string }) =>
  (await api.post<Evidence>(`${BASE}/evidence/${v.id}/request`, { caseId: v.caseId, reason: v.reason })).data);

/** A clip cut from the agent's buffer, at most {@link MAX_CLIP_MINUTES} minutes. */
export const useLpRangeClip = () => useVisionChange(async (body: {
  cameraId: string; from: string; to: string; caseId?: string; reason?: string;
}) => (await api.post<Evidence>(`${BASE}/evidence/range`, body)).data);

export const useLpSettings = (enabled = true) => useQuery({
  queryKey: SETTINGS_KEY,
  enabled,
  queryFn: async () => (await api.get<LpSettings>(`${BASE}/settings`)).data,
});

export const useSaveLpSettings = () => {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (body: { branchId?: string; values: Record<string, number> }) =>
      (await api.put<LpSettings>(`${BASE}/settings`, body)).data,
    onSuccess: (data) => qc.setQueryData(SETTINGS_KEY, data),
  });
};

/** Choices for a picker, read only when the viewer may read the list (a refused read toasts "Permission denied"). */
function useChoices(name: string, path: string, pageSize: number, enabled: boolean) {
  return useQuery({
    queryKey: [...KEY, 'choices', name],
    enabled,
    retry: false,
    staleTime: 5 * 60_000,
    queryFn: async () => (await api.get<{ data: Choice[] }>(path, { params: { pageSize } })).data?.data ?? [],
  });
}

export const useLpBranchChoices = (enabled: boolean) => useChoices('branches', '/branches', 200, enabled);
export const useLpTillChoices = (enabled: boolean) => useChoices('tills', '/cash-registers', 100, enabled);
export const useLpLocationChoices = (enabled: boolean) =>
  useChoices('locations', '/inventory/locations', 500, enabled);

/** POS tables for a `table` link (LP6b); `GET /pos/tables` (`tables:view`) answers a plain array. */
export const useLpTableChoices = (enabled: boolean) => useQuery({
  queryKey: [...KEY, 'choices', 'tables'],
  enabled,
  retry: false,
  staleTime: 5 * 60_000,
  queryFn: async () => {
    const r = await api.get<{ id: string; name: string; number?: number | null; active?: boolean | null }[]>('/pos/tables');
    return (Array.isArray(r.data) ? r.data : []).map((t): Choice => ({
      id: t.id, name: t.name, code: t.number === null || t.number === undefined ? null : String(t.number),
      isActive: t.active ?? null,
    }));
  },
});

/** Zones of the organization (`lp:configure`), retired ones too with `includeRetired`; a plain array. */
export const useLpZones = (filters: Filters, enabled = true) => useQuery({
  queryKey: [...KEY, 'zones', filters],
  enabled,
  retry: false,
  staleTime: 60_000,
  queryFn: async () => (await api.get<LpZone[]>(`${BASE}/zones`, { params: filters })).data,
});

// ─── Binary content: stills, clips, exports, live view ──────────────────────────────────────────────────────────────

const statusOf = (e: unknown) => (e as { response?: { status?: number } })?.response?.status;

/** The latest still of a camera, or null while the agent has not sent one (404). */
export async function fetchLpSnapshot(cameraId: string): Promise<Snapshot | null> {
  try {
    const r = await api.get<Blob>(`${BASE}/cameras/${cameraId}/snapshot`, { responseType: 'blob' });
    const takenAt = r.headers?.['x-snapshot-taken-at'];
    return { blob: r.data, takenAt: takenAt ? String(takenAt) : null };
  } catch (e) {
    if (statusOf(e) === 404) return null;
    throw e;
  }
}

export const requestLpSnapshot = async (cameraId: string) =>
  (await api.post<{ commandId: string }>(`${BASE}/cameras/${cameraId}/snapshot`, {})).data;

export const startLpLive = async (cameraId: string) =>
  (await api.post<LiveSession>(`${BASE}/cameras/${cameraId}/live`, {})).data;

export const stopLpLive = async (sessionId: string) => {
  await api.delete(`${BASE}/live/${sessionId}`);
};

function fileName(disposition: unknown, fallback: string): string {
  const m = /filename\*?=(?:UTF-8'')?"?([^";]+)"?/i.exec(String(disposition ?? ''));
  if (!m) return fallback;
  try {
    return decodeURIComponent(m[1]);
  } catch {
    return m[1];
  }
}

/** Clips can be large: no 30-second limit on these. */
const LONG = 10 * 60_000;

/** The clip, decrypted and checked by the server; recorded as a view, or as a download with `download`. */
export async function fetchLpEvidenceContent(id: string, download = false) {
  const r = await api.get<Blob>(`${BASE}/evidence/${id}/content`, {
    params: download ? { download: true } : undefined, responseType: 'blob', timeout: LONG,
  });
  return { blob: r.data, fileName: fileName(r.headers?.['content-disposition'], `evidence-${id}`) };
}

/** A ZIP with the clip, its manifest (metadata, SHA-256, cases) and the access trail; recorded. */
export async function fetchLpEvidenceExport(id: string) {
  const r = await api.get<Blob>(`${BASE}/evidence/${id}/export`, { responseType: 'blob', timeout: LONG });
  return { blob: r.data, fileName: fileName(r.headers?.['content-disposition'], `evidence-${id}.zip`) };
}

export function saveBlob(blob: Blob, name: string) {
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = name;
  a.click();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}

/** The server's message for a failed binary request: the error body is a Blob, so read it as JSON first. */
export async function blobErrorMessage(e: unknown, fallback: string): Promise<string> {
  const data = (e as { response?: { data?: unknown } })?.response?.data;
  if (typeof Blob !== 'undefined' && data instanceof Blob) {
    try {
      const body = JSON.parse(await data.text()) as { message?: unknown };
      if (Array.isArray(body.message)) return body.message.map(String).join('; ') || fallback;
      if (typeof body.message === 'string' && body.message.trim()) return body.message;
    } catch {
      // Not JSON: keep the fallback.
    }
    return fallback;
  }
  return apiMessage(e, fallback);
}

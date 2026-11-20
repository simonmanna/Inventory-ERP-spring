import { beforeEach, describe, expect, it, vi } from 'vitest';

/**
 * The hooks are thin wrappers over React Query. With `useQuery`/`useMutation` replaced by the identity, a hook returns
 * its options, so the query key, the request path and the parameters can be checked without rendering anything.
 */
const { api, qc } = vi.hoisted(() => ({
  api: { get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn() },
  qc: { invalidateQueries: vi.fn(), setQueryData: vi.fn() },
}));
vi.mock('@/lib/api', () => ({ api }));
vi.mock('@tanstack/react-query', () => ({
  useQuery: (o: unknown) => o,
  useMutation: (o: unknown) => o,
  useQueryClient: () => qc,
}));

import {
  EVIDENCE_STATES, FRACTION_PARAMS, MAX_CLIP_MINUTES, PARAM_CHOICES, STREAM_ALIAS_RE, VISION_SETTINGS,
  ZONE_DEFAULT_PARAMS, ZONE_LINKS, ZONE_SHAPES, ZONE_TYPES, blobErrorMessage, fetchLpEvidenceContent,
  fetchLpEvidenceExport, fetchLpSnapshot, requestLpSnapshot, startLpLive, stopLpLive, useCalibrateLpZone,
  useCreateLpAgent, useCreateLpCamera, useCreateLpZone, useLpAgent, useLpAgents, useLpBranchChoices, useLpCamera,
  useLpCameras, useLpEvidence, useLpEvidenceList, useLpLocationChoices, useLpModels, useLpRangeClip, useLpSettings,
  useLpTableChoices, useLpTillChoices, useLpZones, useNewLpEnrolmentCode, useRequestLpEvidence, useRetireLpZone,
  useRevokeLpAgent, useSaveLpSettings, useUpdateLpAgent, useUpdateLpCamera, useUpdateLpZone, useLpStill,
  useSaveLpFrame, useLpFootage,
} from './vision-api';

interface QueryOptions {
  queryKey: unknown[];
  enabled?: boolean;
  queryFn: () => Promise<unknown>;
  refetchInterval?: number | false | ((q: { state: { data: unknown } }) => number | false);
}
interface MutationOptions {
  mutationFn: (v?: unknown) => Promise<unknown>;
  onSuccess?: (data?: unknown) => unknown;
}
const query = (o: unknown) => o as QueryOptions;
const mutation = (o: unknown) => o as MutationOptions;

beforeEach(() => {
  vi.clearAllMocks();
  for (const m of Object.values(api)) m.mockResolvedValue({ data: 'DATA', headers: {} });
});

describe('zone contract tables', () => {
  it('lists the 10 zone types once each', () => {
    expect(ZONE_TYPES).toHaveLength(10);
    expect(new Set(ZONE_TYPES).size).toBe(ZONE_TYPES.length);
  });

  it('defines links, shapes and defaults for every zone type and nothing else', () => {
    const sorted = [...ZONE_TYPES].sort();
    expect(Object.keys(ZONE_LINKS).sort()).toEqual(sorted);
    expect(Object.keys(ZONE_SHAPES).sort()).toEqual(sorted);
    expect(Object.keys(ZONE_DEFAULT_PARAMS).sort()).toEqual(sorted);
  });

  it('requires a link only where there is one to choose', () => {
    for (const t of ZONE_TYPES) if (ZONE_LINKS[t].required) expect(ZONE_LINKS[t].types.length, t).toBeGreaterThan(0);
    expect(ZONE_LINKS.entrance).toEqual({ types: [], required: false });
  });

  it('links the till for the cashier zones', () => {
    for (const t of ['cash_drawer', 'checkout', 'scan_area'] as const) {
      expect(ZONE_LINKS[t]).toEqual({ types: ['till'], required: true });
    }
  });

  it('allows at least one shape per type; an entrance is a counting line', () => {
    for (const t of ZONE_TYPES) {
      expect(ZONE_SHAPES[t].length, t).toBeGreaterThan(0);
      for (const s of ZONE_SHAPES[t]) expect(['area', 'line']).toContain(s);
    }
    expect(ZONE_SHAPES.entrance).toEqual(['line']);
  });

  it('gives every zone type a minimum confidence of 0.5', () => {
    for (const t of ZONE_TYPES) expect(ZONE_DEFAULT_PARAMS[t].minConfidence, t).toBe(0.5);
  });

  it('keeps default values valid: fractions in 0..1, choices from their closed set', () => {
    for (const t of ZONE_TYPES) {
      for (const [k, v] of Object.entries(ZONE_DEFAULT_PARAMS[t])) {
        if (FRACTION_PARAMS.has(k)) {
          expect(typeof v).toBe('number');
          expect(v as number).toBeGreaterThanOrEqual(0);
          expect(v as number).toBeLessThanOrEqual(1);
        }
        if (k in PARAM_CHOICES) expect(PARAM_CHOICES[k], `${t}.${k}`).toContain(v);
        if (typeof v === 'number') expect(v, `${t}.${k}`).toBeGreaterThanOrEqual(0);
      }
    }
  });

  it('knows the evidence states, settings and clip limit', () => {
    expect(EVIDENCE_STATES).toEqual([
      'on_agent', 'requested', 'uploading', 'uploaded', 'missing', 'integrity_failed', 'deleted',
    ]);
    expect(VISION_SETTINGS).toHaveLength(7);
    expect(MAX_CLIP_MINUTES).toBe(10);
  });
});

describe('STREAM_ALIAS_RE', () => {
  it.each(['till1', '0cam', 'bar-main_2', 'a'.repeat(64)])('accepts %s', (v) => {
    expect(STREAM_ALIAS_RE.test(v)).toBe(true);
  });

  it.each(['', 'Till1', '-cam', '_cam', 'rtsp://host/stream', 'a b', 'a'.repeat(65)])('rejects %s', (v) => {
    expect(STREAM_ALIAS_RE.test(v)).toBe(false);
  });
});

describe('agent requests', () => {
  it('lists and reads agents', async () => {
    const list = query(useLpAgents(false));
    expect(list.queryKey).toEqual(['loss-prevention', 'vision', 'agents']);
    expect(list.enabled).toBe(false);
    expect(await list.queryFn()).toBe('DATA');
    expect(api.get).toHaveBeenCalledWith('/loss-prevention/agents');

    const one = query(useLpAgent('a1'));
    expect(one.queryKey).toEqual(['loss-prevention', 'vision', 'agent', 'a1']);
    await one.queryFn();
    expect(api.get).toHaveBeenLastCalledWith('/loss-prevention/agents/a1');
    expect(query(useLpAgent(null)).enabled).toBe(false);
  });

  it('creates, updates, re-enrols and revokes, then reads the vision lists again', async () => {
    const create = mutation(useCreateLpAgent());
    await create.mutationFn({ branchId: 'b1', name: 'Box' });
    expect(api.post).toHaveBeenLastCalledWith('/loss-prevention/agents', { branchId: 'b1', name: 'Box' });
    create.onSuccess?.();
    expect(qc.invalidateQueries).toHaveBeenCalledWith({ queryKey: ['loss-prevention', 'vision'] });

    const body = { name: 'Box', desiredModelVersion: null, desiredAgentVersion: '1.2.0' };
    await mutation(useUpdateLpAgent()).mutationFn({ id: 'a1', body });
    expect(api.put).toHaveBeenLastCalledWith('/loss-prevention/agents/a1', body);

    await mutation(useNewLpEnrolmentCode()).mutationFn('a1');
    expect(api.post).toHaveBeenLastCalledWith('/loss-prevention/agents/a1/enrolment-code', {});

    await mutation(useRevokeLpAgent()).mutationFn({ id: 'a1', reason: 'stolen' });
    expect(api.post).toHaveBeenLastCalledWith('/loss-prevention/agents/a1/revoke', { reason: 'stolen' });
  });

  it('reads the models', async () => {
    const q = query(useLpModels());
    expect(q.queryKey).toEqual(['loss-prevention', 'vision', 'models']);
    await q.queryFn();
    expect(api.get).toHaveBeenLastCalledWith('/loss-prevention/models');
  });
});

describe('camera and zone requests', () => {
  it('lists cameras with the filters as query parameters', async () => {
    const filters = { agentId: 'a1', branchId: 'b1' };
    const q = query(useLpCameras(filters));
    expect(q.queryKey).toEqual(['loss-prevention', 'vision', 'cameras', filters]);
    await q.queryFn();
    expect(api.get).toHaveBeenLastCalledWith('/loss-prevention/cameras', { params: filters });

    await query(useLpCamera('c1')).queryFn();
    expect(api.get).toHaveBeenLastCalledWith('/loss-prevention/cameras/c1');
  });

  it('creates and updates cameras', async () => {
    const body = { agentId: 'a1', name: 'Till 1', streamAlias: 'till1' };
    await mutation(useCreateLpCamera()).mutationFn(body);
    expect(api.post).toHaveBeenLastCalledWith('/loss-prevention/cameras', body);
    await mutation(useUpdateLpCamera()).mutationFn({ id: 'c1', body: { name: 'Till 2' } });
    expect(api.put).toHaveBeenLastCalledWith('/loss-prevention/cameras/c1', { name: 'Till 2' });
  });

  it('creates, updates, retires and calibrates zones', async () => {
    const body = { cameraId: 'c1', name: 'Drawer', zoneType: 'cash_drawer' };
    await mutation(useCreateLpZone()).mutationFn(body);
    expect(api.post).toHaveBeenLastCalledWith('/loss-prevention/zones', body);
    await mutation(useUpdateLpZone()).mutationFn({ id: 'z1', body: { name: 'Drawer 2' } });
    expect(api.put).toHaveBeenLastCalledWith('/loss-prevention/zones/z1', { name: 'Drawer 2' });
    await mutation(useRetireLpZone()).mutationFn('z1');
    expect(api.delete).toHaveBeenLastCalledWith('/loss-prevention/zones/z1');
    await mutation(useCalibrateLpZone()).mutationFn('z1');
    expect(api.post).toHaveBeenLastCalledWith('/loss-prevention/zones/z1/calibrate', {});
  });

  it('lists zones with the filters', async () => {
    const filters = { includeRetired: 'true' };
    await query(useLpZones(filters)).queryFn();
    expect(api.get).toHaveBeenLastCalledWith('/loss-prevention/zones', { params: filters });
  });
});

describe('evidence requests', () => {
  it('lists evidence and keeps polling only while a clip is on its way', async () => {
    const filters = { cameraId: 'c1', page: 2 };
    const q = query(useLpEvidenceList(filters));
    await q.queryFn();
    expect(api.get).toHaveBeenLastCalledWith('/loss-prevention/evidence', { params: filters });
    const interval = q.refetchInterval as (s: { state: { data: unknown } }) => number | false;
    expect(interval({ state: { data: { data: [{ state: 'uploaded' }, { state: 'requested' }] } } })).toBe(10_000);
    expect(interval({ state: { data: { data: [{ state: 'uploading' }] } } })).toBe(10_000);
    expect(interval({ state: { data: { data: [{ state: 'uploaded' }] } } })).toBe(false);
    expect(interval({ state: { data: undefined } })).toBe(false);
  });

  it('reads one item and polls it while it is busy', async () => {
    const q = query(useLpEvidence('e1'));
    await q.queryFn();
    expect(api.get).toHaveBeenLastCalledWith('/loss-prevention/evidence/e1');
    const interval = q.refetchInterval as (s: { state: { data: unknown } }) => number | false;
    expect(interval({ state: { data: { state: 'requested' } } })).toBe(5_000);
    expect(interval({ state: { data: { state: 'on_agent' } } })).toBe(false);
    expect(query(useLpEvidence(null)).enabled).toBe(false);
  });

  it('requests an upload and a clip from the buffer', async () => {
    await mutation(useRequestLpEvidence()).mutationFn({ id: 'e1', caseId: 'k1', reason: 'check' });
    expect(api.post).toHaveBeenLastCalledWith('/loss-prevention/evidence/e1/request', { caseId: 'k1', reason: 'check' });
    const body = { cameraId: 'c1', from: '2026-11-19T10:00:00Z', to: '2026-11-19T10:05:00Z' };
    await mutation(useLpRangeClip()).mutationFn(body);
    expect(api.post).toHaveBeenLastCalledWith('/loss-prevention/evidence/range', body);
  });

  it('asks for a still and keeps a frame from the player as multipart', async () => {
    await mutation(useLpStill()).mutationFn({ cameraId: 'c1', at: '2026-11-19T10:00:00Z' });
    expect(api.post).toHaveBeenLastCalledWith('/loss-prevention/evidence/still', { cameraId: 'c1', at: '2026-11-19T10:00:00Z' });
    const image = new Blob(['jpeg'], { type: 'image/jpeg' });
    await mutation(useSaveLpFrame()).mutationFn({ id: 'e1', offsetMs: 1499.6, image, reason: 'hand' });
    const [path, form, config] = api.post.mock.calls.at(-1) as [string, FormData, { params: unknown; headers: unknown }];
    expect(path).toBe('/loss-prevention/evidence/e1/frame');
    expect(form.get('reason')).toBe('hand');
    expect((form.get('file') as File).name).toBe('frame.jpg');
    expect(config.params).toEqual({ offsetMs: 1500 });
    expect(config.headers).toEqual({ 'Content-Type': 'multipart/form-data' });
  });

  it('reads the footage around a sale and polls while something is on its way', async () => {
    expect(query(useLpFootage(null)).enabled).toBe(false);
    const q = query(useLpFootage({ invoiceId: 'i1' }));
    expect(q.enabled).toBe(true);
    await q.queryFn();
    expect(api.get).toHaveBeenLastCalledWith('/loss-prevention/footage', { params: { invoiceId: 'i1' } });
    const interval = q.refetchInterval as (s: { state: { data: unknown } }) => number | false;
    expect(interval({ state: { data: { evidence: [{ state: 'requested' }] } } })).toBe(5_000);
    expect(interval({ state: { data: { evidence: [{ state: 'uploaded' }] } } })).toBe(false);
  });
});

describe('settings and pickers', () => {
  it('reads and saves the settings, keeping the saved answer in the cache', async () => {
    const q = query(useLpSettings());
    expect(q.queryKey).toEqual(['loss-prevention', 'settings']);
    await q.queryFn();
    expect(api.get).toHaveBeenLastCalledWith('/loss-prevention/settings');

    const save = mutation(useSaveLpSettings());
    const body = { values: { agentSilentMinutes: 5 } };
    await save.mutationFn(body);
    expect(api.put).toHaveBeenLastCalledWith('/loss-prevention/settings', body);
    save.onSuccess?.('SAVED');
    expect(qc.setQueryData).toHaveBeenCalledWith(['loss-prevention', 'settings'], 'SAVED');
  });

  it('reads the branch, till and location choices with their page sizes', async () => {
    api.get.mockResolvedValue({ data: { data: [{ id: 'x', name: 'X' }] } });
    expect(await query(useLpBranchChoices(true)).queryFn()).toEqual([{ id: 'x', name: 'X' }]);
    expect(api.get).toHaveBeenLastCalledWith('/branches', { params: { pageSize: 200 } });
    await query(useLpTillChoices(true)).queryFn();
    expect(api.get).toHaveBeenLastCalledWith('/cash-registers', { params: { pageSize: 100 } });
    await query(useLpLocationChoices(true)).queryFn();
    expect(api.get).toHaveBeenLastCalledWith('/inventory/locations', { params: { pageSize: 500 } });

    api.get.mockResolvedValue({ data: undefined });
    expect(await query(useLpTillChoices(true)).queryFn()).toEqual([]);
  });

  it('maps POS tables to choices', async () => {
    api.get.mockResolvedValue({
      data: [{ id: 't1', name: 'T1', number: 4, active: true }, { id: 't2', name: 'T2', number: null }],
    });
    expect(await query(useLpTableChoices(true)).queryFn()).toEqual([
      { id: 't1', name: 'T1', code: '4', isActive: true }, { id: 't2', name: 'T2', code: null, isActive: null },
    ]);
    expect(api.get).toHaveBeenLastCalledWith('/pos/tables');

    api.get.mockResolvedValue({ data: { not: 'an array' } });
    expect(await query(useLpTableChoices(true)).queryFn()).toEqual([]);
  });
});

describe('binary content', () => {
  it('reads a still with the time it was taken', async () => {
    const blob = new Blob(['img']);
    api.get.mockResolvedValue({ data: blob, headers: { 'x-snapshot-taken-at': '2026-11-19T10:00:00Z' } });
    expect(await fetchLpSnapshot('c1')).toEqual({ blob, takenAt: '2026-11-19T10:00:00Z' });
    expect(api.get).toHaveBeenLastCalledWith('/loss-prevention/cameras/c1/snapshot', { responseType: 'blob' });

    api.get.mockResolvedValue({ data: blob, headers: {} });
    expect(await fetchLpSnapshot('c1')).toEqual({ blob, takenAt: null });
  });

  it('answers null while there is no still (404) and rethrows other errors', async () => {
    api.get.mockRejectedValueOnce({ response: { status: 404 } });
    expect(await fetchLpSnapshot('c1')).toBeNull();
    const err = { response: { status: 500 } };
    api.get.mockRejectedValueOnce(err);
    await expect(fetchLpSnapshot('c1')).rejects.toBe(err);
  });

  it('asks for a still and opens and closes live view', async () => {
    await requestLpSnapshot('c1');
    expect(api.post).toHaveBeenLastCalledWith('/loss-prevention/cameras/c1/snapshot', {});
    await startLpLive('c1');
    expect(api.post).toHaveBeenLastCalledWith('/loss-prevention/cameras/c1/live', {});
    await stopLpLive('s1');
    expect(api.delete).toHaveBeenLastCalledWith('/loss-prevention/live/s1');
  });

  it('reads a clip with a long timeout, as a view or a download', async () => {
    api.get.mockResolvedValue({ data: 'B', headers: { 'content-disposition': 'attachment; filename="clip 1.mp4"' } });
    expect(await fetchLpEvidenceContent('e1')).toEqual({ blob: 'B', fileName: 'clip 1.mp4' });
    expect(api.get).toHaveBeenLastCalledWith('/loss-prevention/evidence/e1/content', {
      params: undefined, responseType: 'blob', timeout: 600_000,
    });
    await fetchLpEvidenceContent('e1', true);
    expect(api.get).toHaveBeenLastCalledWith('/loss-prevention/evidence/e1/content', {
      params: { download: true }, responseType: 'blob', timeout: 600_000,
    });
  });

  it('takes the file name from Content-Disposition, else a fallback', async () => {
    api.get.mockResolvedValue({ data: 'B', headers: { 'content-disposition': "attachment; filename*=UTF-8''caf%C3%A9.zip" } });
    expect((await fetchLpEvidenceExport('e1')).fileName).toBe('café.zip');
    expect(api.get).toHaveBeenLastCalledWith('/loss-prevention/evidence/e1/export', {
      responseType: 'blob', timeout: 600_000,
    });

    api.get.mockResolvedValue({ data: 'B', headers: { 'content-disposition': "attachment; filename*=UTF-8''bad%E0.zip" } });
    expect((await fetchLpEvidenceExport('e1')).fileName).toBe('bad%E0.zip');

    api.get.mockResolvedValue({ data: 'B', headers: {} });
    expect((await fetchLpEvidenceExport('e1')).fileName).toBe('evidence-e1.zip');
    expect((await fetchLpEvidenceContent('e1')).fileName).toBe('evidence-e1');
  });
});

describe('blobErrorMessage', () => {
  const blobError = (body: string) => ({ response: { data: new Blob([body], { type: 'application/json' }) } });

  it('reads the message from a JSON error body sent as a blob', async () => {
    expect(await blobErrorMessage(blobError('{"message":"Camera not found"}'), 'fallback')).toBe('Camera not found');
    expect(await blobErrorMessage(blobError('{"message":["a","b"]}'), 'fallback')).toBe('a; b');
  });

  it('falls back for an empty message or a body that is not JSON', async () => {
    expect(await blobErrorMessage(blobError('{"message":"  "}'), 'fallback')).toBe('fallback');
    expect(await blobErrorMessage(blobError('{"message":[]}'), 'fallback')).toBe('fallback');
    expect(await blobErrorMessage(blobError('<html>'), 'fallback')).toBe('fallback');
  });

  it('reads a plain JSON error like any other request', async () => {
    expect(await blobErrorMessage({ response: { data: { message: 'Denied' } } }, 'fallback')).toBe('Denied');
    expect(await blobErrorMessage(new Error('x'), 'fallback')).toBe('fallback');
  });
});

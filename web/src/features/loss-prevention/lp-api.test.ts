import { beforeEach, describe, expect, it, vi } from 'vitest';

/** Request paths, parameters and cache keys of the case, insights, meter and research hooks (see vision-api.test.ts). */
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
  CAUSE_CODES, OUTCOMES, useLpBarVariance, useLpByCashier, useLpCase, useLpCaseAction, useLpCases, useLpDashboard,
  useLpPerformance, useLpReplay, useLpRules, useLpRunComparison, useLpRuns, useLpSync, useSaveLpRule,
} from './api';
import {
  useInsightsFootfall, useInsightsHourly, useInsightsKitchen, useInsightsOccupancy, useInsightsPour,
  useInsightsQueues, useInsightsRecompute, useInsightsStaff, useInsightsSummary, useInsightsTables,
} from './insights-api';
import { METER_KINDS, useCreateLpMeter, useLpMeters, useRetireLpMeter, useUpdateLpMeter } from './measurement-api';
import {
  ACTIVITY_LABEL_RE, MAX_REVIEW_NOTE, VERDICTS, VERDICT_FILTERS, useLpActivities, useLpActivitySummary,
  useReviewLpActivity,
} from './research-api';

interface QueryOptions { queryKey: unknown[]; enabled?: boolean; queryFn: () => Promise<unknown> }
interface MutationOptions { mutationFn: (v?: unknown) => Promise<unknown>; onSuccess?: (data?: unknown) => unknown }
const query = (o: unknown) => o as QueryOptions;
const mutation = (o: unknown) => o as MutationOptions;

beforeEach(() => {
  vi.clearAllMocks();
  for (const m of Object.values(api)) m.mockResolvedValue({ data: 'DATA' });
});

describe('case constants', () => {
  it('lists the review outcomes and cause codes once each', () => {
    expect(OUTCOMES).toEqual(['explained', 'confirmed_variance', 'false_alarm', 'inconclusive']);
    expect(new Set(CAUSE_CODES).size).toBe(CAUSE_CODES.length);
    expect(CAUSE_CODES).toContain('unexplained');
  });
});

describe('cases, rules and runs', () => {
  it('reads the dashboard, the case list and one case', async () => {
    const dash = query(useLpDashboard());
    expect(dash.queryKey).toEqual(['loss-prevention', 'dashboard']);
    expect(await dash.queryFn()).toBe('DATA');
    expect(api.get).toHaveBeenLastCalledWith('/loss-prevention/dashboard');

    const filters = { state: 'open', page: 1 };
    const cases = query(useLpCases(filters));
    expect(cases.queryKey).toEqual(['loss-prevention', 'cases', filters]);
    await cases.queryFn();
    expect(api.get).toHaveBeenLastCalledWith('/loss-prevention/cases', { params: filters });

    await query(useLpCase('k1')).queryFn();
    expect(api.get).toHaveBeenLastCalledWith('/loss-prevention/cases/k1');
    expect(query(useLpCase(null)).enabled).toBe(false);
  });

  it('posts a review step to the case and reads everything again', async () => {
    const action = mutation(useLpCaseAction());
    await action.mutationFn({ id: 'k1', action: 'close', body: { outcome: 'explained' } });
    expect(api.post).toHaveBeenLastCalledWith('/loss-prevention/cases/k1/close', { outcome: 'explained' });
    await action.mutationFn({ id: 'k1', action: 'start-review' });
    expect(api.post).toHaveBeenLastCalledWith('/loss-prevention/cases/k1/start-review', {});
    action.onSuccess?.();
    expect(qc.invalidateQueries).toHaveBeenCalledWith({ queryKey: ['loss-prevention'] });
  });

  it('reads and saves rules', async () => {
    await query(useLpRules()).queryFn();
    expect(api.get).toHaveBeenLastCalledWith('/loss-prevention/rules');
    const save = mutation(useSaveLpRule());
    await save.mutationFn({ code: 'V-C1', body: { enabled: true } });
    expect(api.put).toHaveBeenLastCalledWith('/loss-prevention/rules/V-C1', { enabled: true });
    save.onSuccess?.();
    expect(qc.invalidateQueries).toHaveBeenCalledWith({ queryKey: ['loss-prevention', 'rules'] });
  });

  it('lists and compares runs', async () => {
    await query(useLpRuns({ engine: 'cash' })).queryFn();
    expect(api.get).toHaveBeenLastCalledWith('/loss-prevention/runs', { params: { engine: 'cash' } });
    const cmp = query(useLpRunComparison('r1', 'r2'));
    await cmp.queryFn();
    expect(api.get).toHaveBeenLastCalledWith('/loss-prevention/runs/r1/compare/r2');
    expect(query(useLpRunComparison('r1', null)).enabled).toBe(false);
    expect(query(useLpRunComparison(null, 'r2')).enabled).toBe(false);
  });

  it('replays a range with a fresh idempotency key each time', async () => {
    const replay = mutation(useLpReplay());
    const body = { engine: 'cash', from: '2026-11-01', to: '2026-11-02' };
    await replay.mutationFn(body);
    await replay.mutationFn(body);
    const [first, second] = api.post.mock.calls;
    expect(first[0]).toBe('/loss-prevention/runs/replay');
    expect(first[1]).toEqual(body);
    const key1 = first[2].headers['Idempotency-Key'];
    const key2 = second[2].headers['Idempotency-Key'];
    expect(key1).toMatch(/^[0-9a-f-]{36}$/);
    expect(key1).not.toBe(key2);
  });

  it('runs the projection now', async () => {
    await mutation(useLpSync()).mutationFn();
    expect(api.post).toHaveBeenLastCalledWith('/loss-prevention/projection/run');
  });

  it('reads the reports with the filters', async () => {
    const f = { from: '2026-11-01', to: '2026-11-30' };
    await query(useLpPerformance(f)).queryFn();
    expect(api.get).toHaveBeenLastCalledWith('/loss-prevention/reports/performance', { params: f });
    await query(useLpByCashier(f)).queryFn();
    expect(api.get).toHaveBeenLastCalledWith('/loss-prevention/reports/by-cashier', { params: f });
    await query(useLpBarVariance(f)).queryFn();
    expect(api.get).toHaveBeenLastCalledWith('/loss-prevention/reports/bar-variance', { params: f });
  });
});

describe('insights', () => {
  const f = { from: '2026-11-01', to: '2026-11-07', branchId: 'b1' };

  it.each([
    ['summary', useInsightsSummary],
    ['hourly', useInsightsHourly],
    ['kitchen', useInsightsKitchen],
    ['staff', useInsightsStaff],
    ['tables', useInsightsTables],
    ['pour', useInsightsPour],
  ] as const)('reads /%s with the filters', async (name, hook) => {
    const q = query(hook(f));
    expect(q.queryKey).toEqual(['loss-prevention', 'insights', name, f]);
    expect(await q.queryFn()).toBe('DATA');
    expect(api.get).toHaveBeenLastCalledWith(`/loss-prevention/insights/${name}`, { params: f });
  });

  it.each([
    ['footfall', useInsightsFootfall],
    ['queues', useInsightsQueues],
    ['occupancy', useInsightsOccupancy],
  ] as const)('reads the camera report /%s only when enabled', async (name, hook) => {
    expect(query(hook(f)).enabled).toBe(true);
    const q = query(hook(f, false));
    expect(q.enabled).toBe(false);
    await q.queryFn();
    expect(api.get).toHaveBeenLastCalledWith(`/loss-prevention/insights/${name}`, { params: f });
  });

  it('marks a period for computing again', async () => {
    const m = mutation(useInsightsRecompute());
    await m.mutationFn({ from: '2026-11-01', to: '2026-11-02' });
    expect(api.post).toHaveBeenLastCalledWith('/loss-prevention/insights/recompute', { from: '2026-11-01', to: '2026-11-02' });
    m.onSuccess?.();
    expect(qc.invalidateQueries).toHaveBeenCalledWith({ queryKey: ['loss-prevention', 'insights'] });
  });
});

describe('pour meters', () => {
  it('knows the meter kinds', () => {
    expect(METER_KINDS).toEqual(['pour_spout', 'flow_meter', 'dispenser']);
  });

  it('leaves empty filters out of the query', async () => {
    await query(useLpMeters({ branchId: '', includeRetired: false })).queryFn();
    expect(api.get).toHaveBeenLastCalledWith('/loss-prevention/measurement-devices', {
      params: { branchId: undefined, includeRetired: undefined },
    });
    await query(useLpMeters({ branchId: 'b1', includeRetired: true })).queryFn();
    expect(api.get).toHaveBeenLastCalledWith('/loss-prevention/measurement-devices', {
      params: { branchId: 'b1', includeRetired: true },
    });
  });

  it('creates, updates (without the id in the body) and retires', async () => {
    await mutation(useCreateLpMeter()).mutationFn({ branchId: 'b1', externalId: 'm1', kind: 'pour_spout' });
    expect(api.post).toHaveBeenLastCalledWith('/loss-prevention/measurement-devices', {
      branchId: 'b1', externalId: 'm1', kind: 'pour_spout',
    });
    await mutation(useUpdateLpMeter()).mutationFn({ id: 'd1', station: 'bar' });
    expect(api.put).toHaveBeenLastCalledWith('/loss-prevention/measurement-devices/d1', { station: 'bar' });
    const retire = mutation(useRetireLpMeter());
    await retire.mutationFn('d1');
    expect(api.post).toHaveBeenLastCalledWith('/loss-prevention/measurement-devices/d1/retire');
    retire.onSuccess?.();
    expect(qc.invalidateQueries).toHaveBeenCalledWith({ queryKey: ['loss-prevention', 'meters'] });
  });
});

describe('activity research', () => {
  it('knows the verdicts and the unreviewed filter', () => {
    expect(VERDICTS).toEqual(['correct', 'incorrect', 'unsure']);
    expect(VERDICT_FILTERS).toEqual(['unreviewed', 'correct', 'incorrect', 'unsure']);
    expect(MAX_REVIEW_NOTE).toBe(1000);
  });

  it.each(['item_scanned', 'a', 'pour2', 'a'.repeat(64)])('accepts the label %s', (v) => {
    expect(ACTIVITY_LABEL_RE.test(v)).toBe(true);
  });

  it.each(['', '2pour', '_x', 'Item', 'item-scanned', 'a b', 'a'.repeat(65)])('rejects the label %s', (v) => {
    expect(ACTIVITY_LABEL_RE.test(v)).toBe(false);
  });

  it('lists activities and their summary with the filters', async () => {
    const f = { verdict: 'unreviewed' };
    await query(useLpActivities(f)).queryFn();
    expect(api.get).toHaveBeenLastCalledWith('/loss-prevention/research/activities', { params: f });
    await query(useLpActivitySummary(f, false)).queryFn();
    expect(api.get).toHaveBeenLastCalledWith('/loss-prevention/research/activities/summary', { params: f });
  });

  it('encodes the event id in the review path', async () => {
    const m = mutation(useReviewLpActivity());
    await m.mutationFn({ eventId: 'ev/1 2', body: { verdict: 'correct' } });
    expect(api.post).toHaveBeenLastCalledWith('/loss-prevention/research/activities/ev%2F1%202/review', {
      verdict: 'correct',
    });
    m.onSuccess?.();
    expect(qc.invalidateQueries).toHaveBeenCalledWith({ queryKey: ['loss-prevention', 'research'] });
  });
});

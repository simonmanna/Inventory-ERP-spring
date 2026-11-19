import { describe, expect, it, vi } from 'vitest';

// Labels are built at import time; return the English source so the tests do not depend on the active language.
vi.mock('@/lib/i18n/tx', () => ({ tx: (s: string) => s }));
// The API client pulls in the auth and licence stores; these tests only need the module's pure exports.
vi.mock('@/lib/api', () => ({ api: {} }));

const {
  ACCESS_LABELS, AGENT_EVENT_LABELS, AGENT_STATUS_LABELS, CASE_STATE_LABELS, EVIDENCE_KIND_LABELS,
  EVIDENCE_ORIGIN_LABELS, EVIDENCE_STATE_LABELS, EVIDENCE_STATE_VARIANT, HEALTH_LABELS, HEALTH_REASON_LABELS,
  HEALTH_VARIANT, LINK_LABELS, PARAM_CHOICE_LABELS, PARAM_LABELS, SETTING_LABELS, STREAM_LABELS, ZONE_LABELS, ZONE_USES,
  cameraChoices, centroid, clamp01, clipLength, labelOf, round4, toLocalInput, toNumber, words,
} = await import('./vision-ui');
const {
  EVIDENCE_STATES, PARAM_CHOICES, VISION_SETTINGS, ZONE_DEFAULT_PARAMS, ZONE_LINKS, ZONE_TYPES,
} = await import('@/features/loss-prevention/vision-api');

const nonEmpty = (labels: Record<string, string>) => Object.values(labels).every((v) => v.trim().length > 0);

describe('label tables cover the contract', () => {
  it('names and explains every zone type', () => {
    for (const t of ZONE_TYPES) {
      expect(ZONE_LABELS[t], t).toBeTruthy();
      expect(ZONE_USES[t], t).toBeTruthy();
    }
    expect(Object.keys(ZONE_LABELS).sort()).toEqual([...ZONE_TYPES].sort());
  });

  it('names every link a zone type can take', () => {
    for (const t of ZONE_TYPES) for (const l of ZONE_LINKS[t].types) expect(LINK_LABELS[l], `${t}/${l}`).toBeTruthy();
  });

  it('names every zone parameter', () => {
    for (const t of ZONE_TYPES) {
      for (const k of Object.keys(ZONE_DEFAULT_PARAMS[t])) expect(PARAM_LABELS[k], `${t}.${k}`).toBeTruthy();
    }
  });

  it('names every flow direction', () => {
    for (const v of PARAM_CHOICES.flowDirection) expect(PARAM_CHOICE_LABELS[v], v).toBeTruthy();
  });

  it('names and colours every evidence state', () => {
    for (const s of EVIDENCE_STATES) {
      expect(EVIDENCE_STATE_LABELS[s], s).toBeTruthy();
      expect(EVIDENCE_STATE_VARIANT[s], s).toBeTruthy();
    }
  });

  it('names every vision setting with its unit', () => {
    for (const k of VISION_SETTINGS) {
      expect(SETTING_LABELS[k]?.label, k).toBeTruthy();
      expect(SETTING_LABELS[k]?.unit, k).toBeTruthy();
    }
    expect(Object.keys(SETTING_LABELS).sort()).toEqual([...VISION_SETTINGS].sort());
  });

  it('colours every agent health it names', () => {
    expect(Object.keys(HEALTH_VARIANT).sort()).toEqual(Object.keys(HEALTH_LABELS).sort());
  });

  it('has no empty label', () => {
    for (const labels of [
      ACCESS_LABELS, AGENT_EVENT_LABELS, AGENT_STATUS_LABELS, CASE_STATE_LABELS, EVIDENCE_KIND_LABELS,
      EVIDENCE_ORIGIN_LABELS, EVIDENCE_STATE_LABELS, HEALTH_LABELS, HEALTH_REASON_LABELS, LINK_LABELS,
      PARAM_CHOICE_LABELS, PARAM_LABELS, STREAM_LABELS, ZONE_LABELS, ZONE_USES,
    ]) expect(nonEmpty(labels)).toBe(true);
  });

  it('maps codes to the expected English source text', () => {
    expect(AGENT_STATUS_LABELS.pending).toBe('Waiting for enrolment');
    expect(HEALTH_LABELS.unknown).toBe('Not heard from yet');
    expect(HEALTH_VARIANT.offline).toBe('destructive');
    expect(STREAM_LABELS.lost).toBe('No picture');
    expect(CASE_STATE_LABELS.open).toBe('Not started');
    expect(EVIDENCE_STATE_VARIANT.integrity_failed).toBe('destructive');
    expect(ACCESS_LABELS.request).toBe(EVIDENCE_STATE_LABELS.requested);
  });
});

describe('words and labelOf', () => {
  it('turns a code into words', () => {
    expect(words('integrity_failed')).toBe('integrity failed');
    expect(words('plain')).toBe('plain');
  });

  it('uses the label, else the code in words, else —', () => {
    expect(labelOf({ a_b: 'Label' }, 'a_b')).toBe('Label');
    expect(labelOf({}, 'new_code')).toBe('new code');
    expect(labelOf({}, null)).toBe('—');
    expect(labelOf({}, undefined)).toBe('—');
    expect(labelOf({}, '')).toBe('—');
  });
});

describe('toNumber', () => {
  it('reads numbers and numeric strings (a bigint arrives as a string)', () => {
    expect(toNumber(5)).toBe(5);
    expect(toNumber('12')).toBe(12);
    expect(toNumber('9007199254740993')).toBe(Number('9007199254740993'));
    expect(toNumber(0)).toBe(0);
  });

  it('is null for missing or non-numeric values', () => {
    expect(toNumber(null)).toBeNull();
    expect(toNumber(undefined)).toBeNull();
    expect(toNumber('')).toBeNull();
    expect(toNumber('abc')).toBeNull();
  });
});

describe('clipLength', () => {
  it('shows m:ss between two instants', () => {
    expect(clipLength('2026-01-01T10:00:00Z', '2026-01-01T10:00:05Z')).toBe('0:05');
    expect(clipLength('2026-01-01T10:00:00Z', '2026-01-01T10:10:00Z')).toBe('10:00');
    expect(clipLength('2026-01-01T10:00:00Z', '2026-01-01T10:01:29.6Z')).toBe('1:30');
  });

  it('never goes below zero', () => {
    expect(clipLength('2026-01-01T10:00:05Z', '2026-01-01T10:00:00Z')).toBe('0:00');
  });

  it('shows — for an unreadable instant', () => {
    expect(clipLength('not a date', '2026-01-01T10:00:00Z')).toBe('—');
  });
});

describe('toLocalInput', () => {
  it('formats a date for a datetime-local input in local time', () => {
    expect(toLocalInput(new Date(2026, 0, 2, 3, 4, 5))).toBe('2026-01-02T03:04:05');
    expect(toLocalInput(new Date(2026, 10, 19, 23, 59, 0))).toBe('2026-11-19T23:59:00');
  });
});

describe('cameraChoices', () => {
  const row = (cameraId: string, cameraName?: string | null) => ({ cameraId, cameraName }) as never;

  it('lists the cameras, then cameras seen only on evidence rows, then the extra id', () => {
    const out = cameraChoices(
      [{ id: 'c1', name: 'Till 1' } as never],
      [row('c1', 'Other name'), row('c2', 'Bar'), row('c3', null)],
      'c4',
    );
    expect(out).toEqual([
      { id: 'c1', name: 'Till 1' }, { id: 'c2', name: 'Bar' }, { id: 'c3', name: 'c3' }, { id: 'c4', name: 'c4' },
    ]);
  });

  it('works without the camera list and does not repeat the extra id', () => {
    expect(cameraChoices(undefined, [row('c2', 'Bar')], 'c2')).toEqual([{ id: 'c2', name: 'Bar' }]);
    expect(cameraChoices(undefined, [])).toEqual([]);
  });
});

describe('geometry helpers', () => {
  it('clamps to 0..1', () => {
    expect(clamp01(-0.2)).toBe(0);
    expect(clamp01(0.4)).toBe(0.4);
    expect(clamp01(1.7)).toBe(1);
  });

  it('rounds to 4 decimals', () => {
    expect(round4(0.123456)).toBe(0.1235);
    expect(round4(1 / 3)).toBe(0.3333);
    expect(round4(0.5)).toBe(0.5);
  });

  it('finds the centroid of the points', () => {
    expect(centroid([[0, 0], [1, 0], [1, 1], [0, 1]])).toEqual([0.5, 0.5]);
    expect(centroid([[0.2, 0.4]])).toEqual([0.2, 0.4]);
    expect(centroid([])).toEqual([0, 0]);
  });
});

import { describe, expect, it } from 'vitest';
import { classify } from '@/lib/offline/cache-policy';
import {
  cellText,
  checkState,
  failuresText,
  findingsLink,
  goLiveBlockers,
  type IntegrityCheck,
  orderedFeatures,
  postingMonitorLink,
  promotingFeatures,
  rowColumns,
  summarise,
  visibleSections,
} from './integrity';

function check(key: string, over: Partial<IntegrityCheck> = {}): IntegrityCheck {
  return { key, ok: true, skipped: false, invariant: '', tolerance: null, failures: 0, truncated: false, rows: [], note: null, ...over };
}

describe('inventory integrity page (IPR T33)', () => {
  it('gates each section on the permission its route needs', () => {
    const only = (...perms: string[]) => (p: string) => perms.includes(p);
    expect(visibleSections(only())).toEqual({ integrity: false, featureUsage: false, glTieOut: false, findings: false });
    expect(visibleSections(only('inventory_valuation:read'))).toEqual({
      integrity: true, featureUsage: true, glTieOut: false, findings: false,
    });
    expect(visibleSections(only('report:accounting', 'pos:reports'))).toEqual({
      integrity: false, featureUsage: false, glTieOut: true, findings: true,
    });
  });

  it('links each check to its Posting Monitor findings', () => {
    expect(findingsLink({ key: 'ledger_vs_quant' })).toBe('/inventory/posting-monitor?kind=integrity_ledger_vs_quant');
    expect(postingMonitorLink('gl_tieout_variance')).toBe('/inventory/posting-monitor?kind=gl_tieout_variance');
    expect(postingMonitorLink('a b')).toBe('/inventory/posting-monitor?kind=a%20b');
  });

  it('summarises checks with failing ones first, most failures first', () => {
    const s = summarise([
      check('b_ok'),
      check('a_skip', { skipped: true, ok: true }),
      check('few', { ok: false, failures: 2 }),
      check('many', { ok: false, failures: 9 }),
    ]);
    expect([s.total, s.ok, s.failing, s.skipped]).toEqual([4, 1, 2, 1]);
    expect(s.ordered.map((c) => c.key)).toEqual(['many', 'few', 'a_skip', 'b_ok']);
    expect(summarise(undefined).total).toBe(0);
    expect(checkState(check('x', { ok: false, skipped: true }))).toBe('skipped');
  });

  it('lays out failing rows and says when they are truncated', () => {
    expect(rowColumns([{ a: 1, b: 2 }, { b: 3, c: null }])).toEqual(['a', 'b', 'c']);
    expect(rowColumns([{ a: 1, b: 2, c: 3 }], 2)).toEqual(['a', 'b']);
    expect(cellText(null)).toBe('—');
    expect(cellText({ x: 1 })).toBe('{"x":1}');
    expect(cellText(0)).toBe('0');
    expect(failuresText({ failures: 1, truncated: false, rows: [] })).toBe('1 failing row');
    expect(failuresText({ failures: 6000, truncated: true, rows: new Array(5000).fill({}) }))
      .toBe('6,000+ failing rows (first 5,000 shown)');
  });

  it('orders features in use first and marks the ones that promote a P2', () => {
    const f = orderedFeatures([
      { key: 'rental', label: 'Rental', inUse: false, count: 0, evidence: null },
      { key: 'serial', label: 'Serial', inUse: true, count: 3, evidence: null },
      { key: 'bins', label: 'Bins', inUse: true, count: 1, evidence: null },
    ]);
    expect(f.map((x) => x.key)).toEqual(['bins', 'serial', 'rental']);
    expect([...promotingFeatures([{ id: 'S11', title: 'Bins', matchedFeatures: ['warehouse_bins'] },
      { id: 'C-9', title: 'Serial', matchedFeatures: ['serial_products'] }])]).toEqual(['warehouse_bins', 'serial_products']);
  });

  it('lists the go-live blockers of the organisation (failing checks and promoted P2s)', () => {
    const report = { checkedAt: '', locationId: null, ok: false, rowLimit: 5000, reportOnlyOk: true, reportOnlyChecks: [],
      checks: [check('ok'), check('lots', { ok: false, failures: 1 })] };
    const usage = { checkedAt: '', organizationId: 'o', features: [],
      promotedP2s: [{ id: 'S11', title: 'Warehouse bins', matchedFeatures: ['warehouse_bins'] }] };
    expect(goLiveBlockers(report, usage)).toEqual(['Integrity check lots fails', 'Open P2 S11 on a feature in use: Warehouse bins']);
    expect(goLiveBlockers(undefined, undefined)).toEqual([]);
  });

  it('keeps the page queries out of the long-lived offline cache', () => {
    expect(classify(['inventory-integrity', null])).toBe('cache-strict');
    expect(classify(['inventory-feature-usage'])).toBe('cache-strict');
    expect(classify(['inventory-gl-tieout', undefined])).toBe('cache-strict');
  });
});

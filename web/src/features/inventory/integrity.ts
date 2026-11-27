/**
 * IPR T33 — the inventory integrity and reconciliation page: the shapes of the three read-only routes it shows and the
 * pure helpers it uses (summaries, Posting Monitor links, which sections a user may see). Kept free of React so the
 * logic is unit tested in Node.
 *
 * Routes (all already on the server):
 * - `GET /inventory/reports/integrity` (`inventory_valuation:read`): checks plus the report-only checks;
 * - `GET /inventory/reports/feature-usage` (`inventory_valuation:read`): features in use and the promoted P2s (R6);
 * - `GET /reports/inventory/gl-tieout` (`report:accounting`): the sub-ledger against the inventory control accounts.
 * Findings the daily monitors raise live in the Posting Monitor (`pos:reports`).
 */

export interface IntegrityCheck {
  key: string;
  ok: boolean;
  skipped: boolean;
  invariant: string;
  tolerance: number | string | null;
  failures: number;
  truncated: boolean;
  rows: Record<string, unknown>[];
  note: string | null;
}

export interface IntegrityReport {
  checkedAt: string;
  locationId: string | null;
  ok: boolean;
  rowLimit: number;
  checks: IntegrityCheck[];
  reportOnlyOk: boolean;
  reportOnlyChecks: IntegrityCheck[];
}

export interface FeatureUsageFeature {
  key: string;
  label: string;
  inUse: boolean;
  count: number;
  evidence: string | null;
}

export interface PromotedP2 {
  id: string;
  title: string;
  matchedFeatures: string[];
}

/** A P2 fixed in the IPR programme: no longer promoted; `fixedBy` is its evidence file (additive field, may be absent). */
export interface FixedP2 {
  id: string;
  title: string;
  fixedBy: string;
}

export interface FeatureUsage {
  checkedAt: string;
  organizationId: string;
  features: FeatureUsageFeature[];
  promotedP2s: PromotedP2[];
  fixedP2s?: FixedP2[];
}

export const INTEGRITY_PERMISSION = 'inventory_valuation:read';
export const GL_TIEOUT_PERMISSION = 'report:accounting';
export const POSTING_MONITOR_PERMISSION = 'pos:reports';

/** Posting Monitor kind of an integrity check's finding (the daily monitor raises `integrity_<check>`). */
export const INTEGRITY_KIND_PREFIX = 'integrity_';
/** Posting Monitor kind of a GL tie-out variance (InventoryTieOutMonitor). */
export const GL_TIEOUT_KIND = 'gl_tieout_variance';

/** Which sections of the page the user may see; the server refuses the same routes with 403. */
export interface Sections {
  integrity: boolean;
  featureUsage: boolean;
  glTieOut: boolean;
  findings: boolean;
}

export function visibleSections(has: (permission: string) => boolean): Sections {
  const integrity = has(INTEGRITY_PERMISSION);
  return {
    integrity,
    featureUsage: integrity,
    glTieOut: has(GL_TIEOUT_PERMISSION),
    findings: has(POSTING_MONITOR_PERMISSION),
  };
}

/** The Posting Monitor, filtered to one finding kind (open findings). */
export function postingMonitorLink(kind: string): string {
  return `/inventory/posting-monitor?kind=${encodeURIComponent(kind)}`;
}

/** The Posting Monitor link of an integrity check's findings. */
export function findingsLink(check: Pick<IntegrityCheck, 'key'>): string {
  return postingMonitorLink(INTEGRITY_KIND_PREFIX + check.key);
}

export type CheckState = 'ok' | 'failing' | 'skipped';

export function checkState(c: Pick<IntegrityCheck, 'ok' | 'skipped'>): CheckState {
  if (c.skipped) return 'skipped';
  return c.ok ? 'ok' : 'failing';
}

export interface CheckSummary {
  total: number;
  ok: number;
  failing: number;
  skipped: number;
  /** Failing checks first (most failures first), then skipped, then passing; stable by key. */
  ordered: IntegrityCheck[];
}

const STATE_ORDER: Record<CheckState, number> = { failing: 0, skipped: 1, ok: 2 };

export function summarise(checks: IntegrityCheck[] | undefined): CheckSummary {
  const list = checks ?? [];
  const ordered = [...list].sort((a, b) => {
    const s = STATE_ORDER[checkState(a)] - STATE_ORDER[checkState(b)];
    if (s !== 0) return s;
    if (b.failures !== a.failures) return b.failures - a.failures;
    return a.key.localeCompare(b.key);
  });
  return {
    total: list.length,
    ok: list.filter((c) => checkState(c) === 'ok').length,
    failing: list.filter((c) => checkState(c) === 'failing').length,
    skipped: list.filter((c) => checkState(c) === 'skipped').length,
    ordered,
  };
}

/** The columns of a check's rows: every key seen, in first-seen order (rows of one check share a shape). */
export function rowColumns(rows: Record<string, unknown>[] | undefined, max = 12): string[] {
  const cols: string[] = [];
  for (const r of rows ?? []) {
    for (const k of Object.keys(r)) {
      if (!cols.includes(k)) cols.push(k);
      if (cols.length >= max) return cols;
    }
  }
  return cols;
}

/** A cell value as text: null and undefined as a dash, objects as JSON. */
export function cellText(v: unknown): string {
  if (v === null || v === undefined || v === '') return '—';
  if (typeof v === 'object') return JSON.stringify(v);
  return String(v);
}

/** "12 failing rows" / "5,000+ failing rows (first 5,000 shown)". */
export function failuresText(c: Pick<IntegrityCheck, 'failures' | 'truncated' | 'rows'>): string {
  const n = c.failures.toLocaleString('en-US');
  if (c.truncated) return `${n}+ failing rows (first ${c.rows.length.toLocaleString('en-US')} shown)`;
  return `${n} failing ${c.failures === 1 ? 'row' : 'rows'}`;
}

/** Features in use first, then by label. */
export function orderedFeatures(features: FeatureUsageFeature[] | undefined): FeatureUsageFeature[] {
  return [...(features ?? [])].sort((a, b) => (a.inUse === b.inUse ? a.label.localeCompare(b.label) : a.inUse ? -1 : 1));
}

/** The features (by key) that promote at least one open P2 — highlighted in the features table. */
export function promotingFeatures(promoted: PromotedP2[] | undefined): Set<string> {
  const s = new Set<string>();
  for (const p of promoted ?? []) for (const f of p.matchedFeatures) s.add(f);
  return s;
}

/** The go-live verdict for this organisation from the report (R6 / gate G6 is per organisation). */
export function goLiveBlockers(report: IntegrityReport | undefined, usage: FeatureUsage | undefined): string[] {
  const out: string[] = [];
  for (const c of report?.checks ?? []) if (checkState(c) === 'failing') out.push(`Integrity check ${c.key} fails`);
  for (const p of usage?.promotedP2s ?? []) out.push(`Open P2 ${p.id} on a feature in use: ${p.title}`);
  return out;
}

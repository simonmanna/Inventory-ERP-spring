import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';
import { classify, RULES, ruleFor, withinRetention } from '../cache-policy';

const SRC = join(__dirname, '..', '..', '..');

function sourceFiles(dir: string, out: string[] = []): string[] {
  for (const name of readdirSync(dir)) {
    const p = join(dir, name);
    if (statSync(p).isDirectory()) {
      if (name !== '__tests__' && name !== 'node_modules') sourceFiles(p, out);
    } else if (/\.(ts|tsx)$/.test(name) && !/\.test\.tsx?$/.test(name)) {
      out.push(p);
    }
  }
  return out;
}

/** Literal query-key families: `queryKey: ['x'` and key constants such as `const KEY = ['x'` / `DK = { a: ['x'`. */
function literalFamilies(): Map<string, string> {
  const found = new Map<string, string>();
  const patterns = [
    /queryKey:\s*\[\s*'([a-zA-Z0-9_:./-]+)'/g,
    /\b(?:[A-Z_]*KEY|[a-zA-Z]*Key|[A-Z_]*PREFIX|qk)\s*(?::[^=\n]+)?=\s*\[\s*'([a-zA-Z0-9_:./-]+)'/g,
  ];
  for (const file of sourceFiles(SRC)) {
    const text = readFileSync(file, 'utf8');
    if (!text.includes('useQuery') && !text.includes('queryKey') && !/KEY|Key/.test(text)) continue;
    for (const re of patterns) {
      for (const m of text.matchAll(re)) if (!found.has(m[1])) found.set(m[1], file.slice(SRC.length + 1));
    }
  }
  return found;
}

describe('offline cache policy (ADR-S083 S3)', () => {
  it('every literal query-key family in the app is classified', () => {
    const families = literalFamilies();
    expect(families.size).toBeGreaterThan(300);
    const unclassified = [...families].filter(([name]) => ruleFor(name) === null).map(([n, f]) => `${n} (${f})`);
    expect(unclassified, 'classify these in lib/offline/cache-policy.ts').toEqual([]);
  });

  it('no rule entry is dead or contradicted by an earlier rule', () => {
    const problems: string[] = [];
    for (const [cls, patterns] of RULES) {
      for (const p of patterns) {
        if (typeof p === 'string' && ruleFor(p) !== cls) problems.push(`${p}: listed as ${cls}, resolves to ${ruleFor(p)}`);
      }
    }
    expect(problems).toEqual([]);
  });

  it('classifies the starting families as the ADR says', () => {
    expect(classify(['license'])).toBe('never');
    expect(classify(['api-clients'])).toBe('never');
    expect(classify(['cash-session', 'open'])).toBe('never');
    expect(classify(['sync-dead-letters'])).toBe('never');
    expect(classify(['products', 'org'])).toBe('cache');
    expect(classify(['menu-items-available'])).toBe('cache');
    expect(classify(['partners'])).toBe('cache-permissioned');
    expect(classify(['invoices', { page: 1 }])).toBe('cache-permissioned');
    expect(classify(['inventory-items'])).toBe('cache-permissioned');
    expect(classify(['trial-balance'])).toBe('cache-strict');
    expect(classify(['inventory-report'])).toBe('cache-strict');
    expect(classify(['something-new'])).toBe('never');
    expect(classify([{ not: 'a string' }])).toBe('never');
    expect(classify([])).toBe('never');
  });

  it('retention: 7 days catalogue, 3 days records, 24 hours reports, never kept', () => {
    const H = 3_600_000;
    const now = 100 * 24 * H;
    expect(withinRetention('cache', now - 7 * 24 * H, now)).toBe(true);
    expect(withinRetention('cache', now - 7 * 24 * H - 1, now)).toBe(false);
    expect(withinRetention('cache-permissioned', now - 3 * 24 * H - 1, now)).toBe(false);
    expect(withinRetention('cache-strict', now - 23 * H, now)).toBe(true);
    expect(withinRetention('cache-strict', now - 25 * H, now)).toBe(false);
    expect(withinRetention('never', now, now)).toBe(false);
    expect(withinRetention('cache', 0, now)).toBe(false);
  });
});

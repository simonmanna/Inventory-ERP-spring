import { readdirSync, readFileSync, statSync } from 'node:fs';
import path from 'node:path';
import { describe, expect, it } from 'vitest';
import { allTours } from './tours';

/** Every `data-tour` value written in the web source, literal or from the nav (`nav:${item.to}`). */
function sourceAnchors(): Set<string> {
  const root = path.resolve(__dirname, '../..');
  const out = new Set<string>();
  const walk = (dir: string) => {
    for (const name of readdirSync(dir)) {
      const full = path.join(dir, name);
      if (statSync(full).isDirectory()) { walk(full); continue; }
      if (!/\.tsx?$/.test(name) || name.endsWith('.test.ts')) continue;
      const text = readFileSync(full, 'utf8');
      for (const m of text.matchAll(/data-tour="([^"]+)"/g)) out.add(m[1]);
      for (const m of text.matchAll(/tour="([^"]+)"/g)) out.add(m[1]);
      for (const m of text.matchAll(/data-tour=\{`sales\.flow\.\$\{step\}`\}/g)) { void m; [1, 2, 3, 4].forEach((n) => out.add(`sales.flow.${n}`)); }
    }
  };
  walk(root);
  return out;
}

/** Route paths declared in the app's route files. */
function sourceRoutes(): string[] {
  const root = path.resolve(__dirname, '../..');
  const files = [path.join(root, 'App.tsx'), path.join(root, 'pages/sales/routes.tsx')];
  return files.flatMap((f) => [...readFileSync(f, 'utf8').matchAll(/path="([^"]+)"/g)].map((m) => m[1]));
}

describe('tour catalog', () => {
  const tours = allTours();
  const anchors = sourceAnchors();
  const routes = new Set(sourceRoutes());
  const navPaths = new Set([...readFileSync(path.resolve(__dirname, '../../components/layout/app-shell.tsx'), 'utf8')
    .matchAll(/\{ to: '([^']+)'/g)].map((m) => `nav:${m[1]}`));

  it('has unique keys that the server accepts', () => {
    const keys = tours.map((t) => t.key);
    expect(new Set(keys).size).toBe(keys.length);
    keys.forEach((k) => expect(k).toMatch(/^[a-z0-9.-]{1,100}$/));
  });

  it.each(tours.map((t) => [t.key, t] as const))('%s points only at anchors and routes that exist', (_, t) => {
    expect(t.steps.length).toBeGreaterThan(0);
    expect(routes.has(t.startPath)).toBe(true);
    for (const s of t.steps) {
      const named = [s.anchor, typeof s.advanceOn === 'object' ? Object.values(s.advanceOn)[0] : undefined];
      for (const a of named) {
        if (!a || a.startsWith('/')) continue;
        expect(anchors.has(a) || navPaths.has(a), `anchor ${a}`).toBe(true);
      }
      if (s.route) expect(routes.has(s.route), `route ${s.route}`).toBe(true);
      if (typeof s.advanceOn === 'object' && 'route' in s.advanceOn) expect(routes.has(s.advanceOn.route)).toBe(true);
    }
  });
});

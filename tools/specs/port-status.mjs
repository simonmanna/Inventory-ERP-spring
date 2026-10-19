#!/usr/bin/env node
// Creates docs/PORT_STATUS.md (one checkbox per route) if it does not exist, or appends routes that
// are missing from it (after a reference update). Existing ticks are never touched.
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const routes = JSON.parse(fs.readFileSync(path.join(root, 'docs/spec/routes.json'), 'utf8'));
const file = path.join(root, 'docs/PORT_STATUS.md');
const existing = fs.existsSync(file) ? fs.readFileSync(file, 'utf8') : '';
const key = (r) => `${r.method} ${r.path}`;
const order = ['kernel', 'core', 'accounting', 'inventory', 'invoicing', 'expenses', 'crm', 'task', 'documents', 'procurement', 'pos', 'sync', 'backup', 'beverage', 'fixedasset', 'hr', 'manufacturing', 'rental', 'repair', 'orders', 'communication'];

if (!existing) {
  const by = {};
  for (const r of routes) (by[r.module] ??= []).push(r);
  const out = [
    '# Port status',
    '',
    'Tick a route only when it meets the definition of done in `AGENTS.md` §7 (contract parity, tests, authz).',
    'Format: `- [x] METHOD /path — handler`. Regenerate missing rows with `node tools/specs/port-status.mjs`.',
    '',
    '## Summary',
    '',
    '| Module | Phase | Routes | Done |',
    '|---|---|---|---|',
  ];
  const phase = { kernel: 'P1', core: 'P2', accounting: 'P2', inventory: 'P3a', invoicing: 'P3b', expenses: 'P3b', crm: 'P3c', task: 'P3c', documents: 'P3c', procurement: 'P4', pos: 'P5', sync: 'P6', backup: 'P6' };
  for (const m of order) if (by[m]) out.push(`| [${m}](spec/${m}.md) | ${phase[m] ?? 'P7'} | ${by[m].length} | 0 |`);
  out.push(`| **total** | | **${routes.length}** | **0** |`, '');
  out.push('Health endpoints (`/api/v1/health*`) were completed in P0.', '');
  for (const m of order) {
    if (!by[m]) continue;
    out.push(`## ${m}`, '');
    for (const r of by[m].sort((a, b) => a.path.localeCompare(b.path) || a.method.localeCompare(b.method))) {
      const done = m === 'kernel' && r.path.startsWith('/api/v1/health');
      out.push(`- [${done ? 'x' : ' '}] ${key(r)} — ${r.handler}`);
    }
    out.push('');
  }
  fs.writeFileSync(file, out.join('\n'));
  console.log('created PORT_STATUS.md with', routes.length, 'routes');
} else {
  const missing = routes.filter((r) => !existing.includes(`] ${key(r)} `));
  if (missing.length) {
    fs.appendFileSync(file, '\n## Added after reference update\n\n' + missing.map((r) => `- [ ] ${key(r)} — ${r.handler} (${r.module})`).join('\n') + '\n');
  }
  console.log('missing routes appended:', missing.length);
}

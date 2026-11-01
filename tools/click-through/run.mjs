#!/usr/bin/env node
// Browser click-through of every web route against a running stack (PR-1 part 2).
//
//   cd tools/click-through && npm install && npx playwright-core install chromium-headless-shell
//   WEB_URL=http://localhost:5173 node run.mjs [--only /pos] [--out report.json]
//
// Signs in through the login form, then opens every route declared in web/src (App.tsx + pages/**/routes.tsx).
// Parameterised routes (/x/:id) are resolved from the first matching link on the parent list page.
// A page fails on: an uncaught page error, a console error, an /api response >= 500 or 404 (missing route), or the
// error-boundary text. Exit code = number of failing pages (capped at 1 for CI).
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium } from 'playwright-core';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const WEB = (process.env.WEB_URL ?? 'http://localhost:5173').replace(/\/$/, '');
const EMAIL = process.env.CT_EMAIL ?? 'admin@demo.test';
const PASSWORD = process.env.CT_PASSWORD ?? 'Admin@123';
// Default: Playwright's headless shell (`npx playwright-core install chromium-headless-shell`).
const CHROME = process.env.CHROME_PATH;
const SETTLE_MS = Number(process.env.CT_SETTLE_MS ?? 1500);
const args = process.argv.slice(2);
const only = args.includes('--only') ? args[args.indexOf('--only') + 1] : null;
const out = args.includes('--out') ? args[args.indexOf('--out') + 1] : 'click-through-report.json';

// Shells without a staff session, or flows that need a device/second screen.
const SKIP = [/^\/login/, /^\/portal/, /^\/driver/, /^\/public/, /^\/p\//, /^\/reset-password/, /^\/accept-invite/,
  /^\/pos\/display/, /^\/print\//, /\/print$/, /^\/kiosk/, /^\/menu-board/, /^\/qr\//, /^\/order\//];
// "Failed to load resource" repeats what the response listener reports (with the URL), so it is not counted twice.
const IGNORED_CONSOLE = [/Download the React DevTools/, /React Router Future Flag/, /\[vite\]/, /^Failed to load resource/];
// API misses the web expects and handles.
const EXPECTED_API = [
  // Receipt settings read optional keys; Nest has no GET /settings/:key either, the page falls back on the 404.
  /^404 GET \/api\/v1\/settings\/receipt\.(header|footer)$/,
];

function collectRoutes() {
  const files = [path.join(root, 'web/src/App.tsx')];
  const walk = (d) => {
    for (const e of fs.readdirSync(d, { withFileTypes: true })) {
      const p = path.join(d, e.name);
      if (e.isDirectory()) walk(p);
      else if (e.name === 'routes.tsx') files.push(p);
    }
  };
  walk(path.join(root, 'web/src/pages'));
  const set = new Set();
  for (const f of files) {
    for (const m of fs.readFileSync(f, 'utf8').matchAll(/path="(\/[^"]*)"/g)) set.add(m[1]);
  }
  return [...set].filter((r) => !SKIP.some((s) => s.test(r)) && !r.includes('*')).sort();
}

const isParam = (r) => r.includes('/:');
const routeRegex = (r) => new RegExp('^' + r.replace(/:[^/]+/g, '[^/?#]+') + '$');
const parentOf = (r) => r.slice(0, r.indexOf('/:')) || '/';

async function main() {
  const routes = collectRoutes().filter((r) => !only || r.startsWith(only));
  const browser = await chromium.launch({ ...(CHROME ? { executablePath: CHROME } : {}), headless: true });
  const context = await browser.newContext({ viewport: { width: 1440, height: 900 } });
  const page = await context.newPage();
  let current = null;
  const bucket = () => current ?? { console: [], pageErrors: [], api: [] };
  page.on('console', (m) => {
    if (m.type() === 'error' && !IGNORED_CONSOLE.some((r) => r.test(m.text()))) bucket().console.push(m.text().slice(0, 300));
  });
  page.on('pageerror', (e) => bucket().pageErrors.push(String(e.message).slice(0, 300)));
  page.on('response', (r) => {
    const u = r.url();
    if (u.includes('/api/v1/') && (r.status() >= 500 || r.status() === 404)) {
      const line = `${r.status()} ${r.request().method()} ${u.replace(/^https?:\/\/[^/]+/, '')}`;
      if (!EXPECTED_API.some((x) => x.test(line))) bucket().api.push(line);
    }
  });

  await page.goto(`${WEB}/login`);
  await page.locator('form input').first().fill(EMAIL);
  await page.locator('form input[autocomplete="current-password"]').fill(PASSWORD);
  await page.locator('form button[type="submit"]').click();
  await page.waitForURL((u) => !u.pathname.startsWith('/login'), { timeout: 30000 });

  const linkCache = new Map();
  async function linksOn(listPath) {
    if (!linkCache.has(listPath)) {
      try {
        await page.goto(WEB + listPath, { waitUntil: 'networkidle', timeout: 20000 });
      } catch { /* slow page: use what rendered */ }
      linkCache.set(listPath, await page.$$eval('a[href]', (as) => as.map((a) => a.getAttribute('href'))));
    }
    return linkCache.get(listPath);
  }

  const results = [];
  for (const route of routes) {
    let target = route;
    if (isParam(route)) {
      const rx = routeRegex(route);
      const hit = (await linksOn(parentOf(route))).find((h) => h && rx.test(h.split('?')[0]));
      if (!hit) {
        results.push({ route, status: 'unresolved' });
        continue;
      }
      target = hit;
    }
    current = { console: [], pageErrors: [], api: [] };
    let navError = null;
    try {
      await page.goto(WEB + target, { waitUntil: 'networkidle', timeout: 25000 });
    } catch (e) {
      navError = String(e.message).split('\n')[0];
    }
    await page.waitForTimeout(SETTLE_MS);
    const boundary = await page.getByText(/Something went wrong|Unexpected Application Error/i).count();
    const landed = new URL(page.url()).pathname;
    const failed = current.pageErrors.length || current.console.length || current.api.length || boundary;
    results.push({ route, target, landed, status: failed ? 'fail' : navError ? 'slow' : 'pass', navError,
      boundary: boundary > 0, ...current });
    process.stdout.write(`${failed ? 'FAIL' : navError ? 'SLOW' : 'ok  '} ${target}${landed !== target.split('?')[0] ? ` -> ${landed}` : ''}\n`);
    current = null;
  }
  await browser.close();

  const count = (s) => results.filter((r) => r.status === s).length;
  const summary = { web: WEB, at: new Date().toISOString(), total: results.length, pass: count('pass'),
    fail: count('fail'), slow: count('slow'), unresolved: count('unresolved') };
  fs.writeFileSync(out, JSON.stringify({ summary, results }, null, 2));
  console.log('\nSUMMARY', JSON.stringify(summary));
  process.exitCode = summary.fail ? 1 : 0;
}

main().catch((e) => {
  console.error(e);
  process.exitCode = 2;
});

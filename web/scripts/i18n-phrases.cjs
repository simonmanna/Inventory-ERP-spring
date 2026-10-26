/**
 * Collect every phrase passed to tx(...) in the web sources and report
 * coverage per locale. Source of truth for the phrase maps in
 * src/lib/i18n/phrases/<lng>.json.
 *
 *   node scripts/i18n-phrases.cjs            # coverage report
 *   node scripts/i18n-phrases.cjs --write    # add missing keys (value = "")
 *   node scripts/i18n-phrases.cjs --prune    # drop keys no longer in source
 *   node scripts/i18n-phrases.cjs --list ti  # print untranslated phrases
 *   node scripts/i18n-phrases.cjs --check    # CI gate: fail on any gap (pnpm web:i18n)
 */
const fs = require('fs');
const path = require('path');

const ROOT = path.resolve(__dirname, '..');
const SRC = path.join(ROOT, 'src');
const PHRASE_DIR = path.join(SRC, 'lib', 'i18n', 'phrases');
const LOCALES = ['ti', 'lg', 'sw'];

const files = [];
(function walk(d) {
  for (const e of fs.readdirSync(d, { withFileTypes: true })) {
    const p = path.join(d, e.name);
    if (e.isDirectory()) walk(p);
    else if (/\.(tsx|ts)$/.test(p)) files.push(p);
  }
})(SRC);

const phrases = new Set();
const RE = /\btx\(\s*('(?:[^'\\]|\\.)*'|"(?:[^"\\]|\\.)*")/g;
for (const f of files) {
  const src = fs.readFileSync(f, 'utf8');
  let m;
  while ((m = RE.exec(src))) {
    const lit = m[1];
    const body = lit.slice(1, -1);
    phrases.add(body.replace(/\\(['"\\])/g, '$1'));
  }
}
const all = [...phrases].sort((a, b) => a.localeCompare(b));

const listFor = process.argv.includes('--list') ? process.argv[process.argv.indexOf('--list') + 1] : null;
const write = process.argv.includes('--write');
const prune = process.argv.includes('--prune');

console.log('phrases in source:', all.length);
for (const lng of LOCALES) {
  const file = path.join(PHRASE_DIR, lng + '.json');
  const map = JSON.parse(fs.readFileSync(file, 'utf8'));
  const missing = all.filter((p) => !map[p] || !String(map[p]).trim());
  const stale = Object.keys(map).filter((k) => !phrases.has(k));
  console.log(
    `  ${lng}: ${all.length - missing.length}/${all.length} translated` +
    ` (${Math.round(((all.length - missing.length) / all.length) * 100)}%)` +
    `, missing ${missing.length}, stale ${stale.length}`,
  );
  if (listFor === lng) missing.forEach((p) => console.log(p));
  if (write || prune) {
    const next = {};
    for (const p of all) next[p] = map[p] ?? '';
    if (!prune) for (const k of stale) next[k] = map[k];
    fs.writeFileSync(file, JSON.stringify(next, null, 2) + '\n');
  }
}
if (write || prune) console.log('phrase maps updated');

// --check: CI gate. Fails when a primary locale has an untranslated or stale
// phrase, a translation introduces a {{var}} the source does not have, or a
// keyed locale (locales/<lng>.json) is missing a key present in en.json.
if (process.argv.includes('--check')) {
  const problems = [];
  const vars = (s) => s.match(/\{\{\w+\}\}/g) || [];
  for (const lng of LOCALES) {
    const map = JSON.parse(fs.readFileSync(path.join(PHRASE_DIR, lng + '.json'), 'utf8'));
    for (const p of all) {
      const v = map[p];
      if (!v || !String(v).trim()) problems.push(`${lng}: untranslated phrase "${p}"`);
      else {
        const allowed = new Set(vars(p));
        for (const x of vars(v)) if (!allowed.has(x)) problems.push(`${lng}: unknown placeholder ${x} in "${p}"`);
      }
    }
    for (const k of Object.keys(map)) if (!phrases.has(k)) problems.push(`${lng}: stale phrase "${k}"`);
  }
  const LOCALE_DIR = path.join(SRC, 'lib', 'i18n', 'locales');
  const flat = (o, pre = '') =>
    Object.entries(o).flatMap(([k, v]) => (v && typeof v === 'object' ? flat(v, pre + k + '.') : [pre + k]));
  const enKeys = flat(JSON.parse(fs.readFileSync(path.join(LOCALE_DIR, 'en.json'), 'utf8')));
  for (const lng of LOCALES) {
    const keys = new Set(flat(JSON.parse(fs.readFileSync(path.join(LOCALE_DIR, lng + '.json'), 'utf8'))));
    for (const k of enKeys) if (!keys.has(k)) problems.push(`${lng}: locales/${lng}.json missing key "${k}"`);
  }
  if (problems.length) {
    problems.slice(0, 50).forEach((p) => console.error('  ' + p));
    console.error(`i18n check failed: ${problems.length} problem(s). Run with --write, translate, then re-check.`);
    process.exit(1);
  }
  console.log('i18n check passed');
}
